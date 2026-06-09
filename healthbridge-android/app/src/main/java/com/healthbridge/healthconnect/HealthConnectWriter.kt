package com.healthbridge.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import com.healthbridge.fingerprint.FingerprintEngine
import com.healthbridge.parser.AppleHealthRecord

/**
 * Writes NEW Apple Health records into Google Health Connect, batched to respect the
 * platform insert ceiling, and persists a dedup fingerprint for each record ONLY after the
 * batch that produced it has been confirmed by the Health Connect client.
 *
 * Contract / ordering guarantees:
 *  1. Records are inserted in batches of [BATCH_SIZE] (Health Connect rejects inserts larger
 *     than 500 records in a single call).
 *  2. We map each [AppleHealthRecord] -> Health Connect [Record] via [mapper] before writing.
 *  3. After [HealthConnectClient.insertRecords] returns successfully for a batch, and the
 *     response is verified (one returned UID per inserted record), we persist the matching
 *     fingerprints via [fingerprintEngine]. If the write throws, NO fingerprints are written
 *     for that batch — so a later retry re-attempts the exact same records (idempotent at the
 *     fingerprint layer, since the dedup ledger only advances on confirmed writes).
 *  4. Partial failure is surfaced (not swallowed): a failed batch is recorded in
 *     [WriteResult.failed] and the batch is retained for [retryFailed].
 *
 * This class performs NO network I/O of its own — Health Connect is an on-device service.
 * It is constructed with collaborators rather than building them, to keep it unit-testable.
 *
 * @param client           the Health Connect client used to insert records.
 * @param mapper           converts a normalized [AppleHealthRecord] into a HC [Record].
 * @param fingerprintEngine computes hashes and persists confirmed fingerprints to Room.
 */
class HealthConnectWriter(
    private val client: HealthConnectClient,
    private val mapper: HealthConnectMapper,
    private val fingerprintEngine: FingerprintEngine
) {

    /**
     * One unit of work: a contiguous slice of records, the HC records they map to, and the
     * fingerprint entities to commit iff the write is confirmed. Retained on failure so the
     * exact same slice can be retried without re-mapping.
     */
    private data class Batch(
        val index: Int,
        val source: List<AppleHealthRecord>,
        val hcRecords: List<Record>,
        // (fingerprint hash, originating record) pairs — committed only on confirmed write.
        val fingerprints: List<Pair<String, AppleHealthRecord>>
    )

    /** Batches whose insert threw or failed verification, kept for [retryFailed]. */
    private val failedBatches: MutableList<Batch> = mutableListOf()

    /**
     * Write [records] to Health Connect in confirmed batches.
     *
     * @param records    the NEW records (already deduped against the fingerprint DB upstream).
     * @param onProgress invoked after each batch with (writtenSoFar, total). Always called at
     *                   least once with the final tally so callers can settle their UI.
     * @return a [WriteResult] summarizing written/failed counts and a terminal status string.
     */
    suspend fun write(
        records: List<AppleHealthRecord>,
        onProgress: (written: Int, total: Int) -> Unit
    ): WriteResult {
        failedBatches.clear()

        val total = records.size
        if (total == 0) {
            onProgress(0, 0)
            return WriteResult(written = 0, failed = 0, status = STATUS_SUCCESS)
        }

        var written = 0
        var failed = 0

        records.chunked(BATCH_SIZE).forEachIndexed { chunkIndex, chunk ->
            val batch = buildBatch(chunkIndex, chunk)
            val ok = writeBatch(batch)
            if (ok) {
                written += batch.hcRecords.size
            } else {
                failed += batch.source.size
                failedBatches += batch
            }
            onProgress(written, total)
        }

        return WriteResult(
            written = written,
            failed = failed,
            status = statusFor(written = written, failed = failed)
        )
    }

    /**
     * Retry ONLY the batches that previously failed (no re-mapping of already-written data).
     * Successfully-retried batches are removed from the failed set; any still failing remain
     * queued for a further retry.
     *
     * @param onProgress invoked after each retried batch with (writtenSoFar, totalRetried).
     * @return a [WriteResult] scoped to the retried records only.
     */
    suspend fun retryFailed(
        onProgress: (written: Int, total: Int) -> Unit
    ): WriteResult {
        if (failedBatches.isEmpty()) {
            onProgress(0, 0)
            return WriteResult(written = 0, failed = 0, status = STATUS_SUCCESS)
        }

        val toRetry = failedBatches.toList()
        failedBatches.clear()

        val total = toRetry.sumOf { it.source.size }
        var written = 0
        var failed = 0

        toRetry.forEach { batch ->
            val ok = writeBatch(batch)
            if (ok) {
                written += batch.hcRecords.size
            } else {
                failed += batch.source.size
                failedBatches += batch
            }
            onProgress(written, total)
        }

        return WriteResult(
            written = written,
            failed = failed,
            status = statusFor(written = written, failed = failed)
        )
    }

    /** Whether any batches are awaiting retry after the last [write] / [retryFailed]. */
    fun hasFailures(): Boolean = failedBatches.isNotEmpty()

    /** Number of source records currently queued for retry. */
    fun pendingRetryCount(): Int = failedBatches.sumOf { it.source.size }

    // region internals

    /**
     * Map a source chunk into a [Batch], pairing each HC record with its fingerprint entity so
     * the two stay index-aligned. Records that fail to map are dropped from the batch (a mapper
     * returning null signals an unsupported/edge-case record we intentionally skip).
     */
    private fun buildBatch(index: Int, chunk: List<AppleHealthRecord>): Batch {
        val hcRecords = ArrayList<Record>(chunk.size)
        val source = ArrayList<AppleHealthRecord>(chunk.size)
        val fingerprints = ArrayList<Pair<String, AppleHealthRecord>>(chunk.size)

        for (record in chunk) {
            // mapper.map may return null for unsupported subtypes;
            // TODO: decide whether those should be counted as skipped vs. failed at the sync layer.
            val mapped = mapper.map(record) ?: continue
            hcRecords += mapped
            source += record
            // Pair the precomputed hash with its record; FingerprintEngine.persist() builds the
            // Room entity (and stamps syncedAt) once the batch write is confirmed.
            fingerprints += fingerprintEngine.fingerprint(record) to record
        }

        return Batch(
            index = index,
            source = source,
            hcRecords = hcRecords,
            fingerprints = fingerprints
        )
    }

    /**
     * Insert a single [batch] and, on confirmed success, persist its fingerprints.
     *
     * @return true if the batch was written and verified; false on any failure (the caller
     *         is responsible for queuing the batch for retry).
     */
    private suspend fun writeBatch(batch: Batch): Boolean {
        if (batch.hcRecords.isEmpty()) return true

        return try {
            val response = client.insertRecords(batch.hcRecords)

            // Verify the write before advancing the dedup ledger: Health Connect returns one
            // record UID per inserted record. A short/empty response means the batch did not
            // fully land — treat it as a failure so we never persist fingerprints for records
            // that were not actually written.
            if (!isResponseComplete(response.recordIdsList.size, batch.hcRecords.size)) {
                return false
            }

            // Only now is the write durable on-device — commit fingerprints so these records
            // are deduped out of future imports.
            fingerprintEngine.persist(batch.fingerprints)
            true
        } catch (t: Throwable) {
            // TODO: route through SyncLogger so failed batches are observable in Sync History.
            //  Intentionally swallow here (returning false) so a partial failure is recoverable
            //  via retryFailed() rather than aborting the entire sync.
            false
        }
    }

    /** A response is complete when HC returned exactly one UID per record we asked it to insert. */
    private fun isResponseComplete(returnedUids: Int, expected: Int): Boolean =
        returnedUids == expected

    /** Derive a terminal status from the written/failed tallies. */
    private fun statusFor(written: Int, failed: Int): String = when {
        failed == 0 -> STATUS_SUCCESS
        written == 0 -> STATUS_FAILED
        else -> STATUS_PARTIAL
    }

    // endregion

    companion object {
        /** Health Connect rejects single inserts larger than 500 records. */
        const val BATCH_SIZE: Int = 500

        const val STATUS_SUCCESS: String = "SUCCESS"
        const val STATUS_PARTIAL: String = "PARTIAL"
        const val STATUS_FAILED: String = "FAILED"
    }
}

/**
 * Outcome of a [HealthConnectWriter.write] (or [HealthConnectWriter.retryFailed]) run.
 *
 * @param written number of records confirmed written to Health Connect.
 * @param failed  number of source records that could not be written (queued for retry).
 * @param status  terminal status: [HealthConnectWriter.STATUS_SUCCESS],
 *                [HealthConnectWriter.STATUS_PARTIAL], or [HealthConnectWriter.STATUS_FAILED].
 */
data class WriteResult(
    val written: Int,
    val failed: Int,
    val status: String
)
