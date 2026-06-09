package com.healthbridge.sync

import android.content.Context
import android.net.Uri
import com.healthbridge.fingerprint.FingerprintDatabase
import com.healthbridge.parser.AppleHealthRecord
import com.healthbridge.parser.DeltaResult
import com.healthbridge.parser.HealthDataType
import com.healthbridge.parser.SyncSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * SyncEngine — the orchestrator for the full HealthBridge import pipeline (PRD §3).
 *
 * Pipeline (each stage emits [SyncProgress] as it runs):
 *
 *   ZipExtractor        unpack export.xml from the user's Apple Health export ZIP into cacheDir,
 *                       validate its presence, and guarantee deletion of the extracted XML after parse.
 *        │
 *   AppleHealthParser   stream-parse export.xml into normalized [AppleHealthRecord]s.
 *        │
 *   FingerprintEngine   compute the delta: SHA-256 each record's identity and compare against the
 *                       local Room ledger; classify every record as NEW or SKIPPED (duplicate).
 *        │
 *   DataTypeFilter      drop records whose [HealthDataType] is toggled OFF by the user, plus any
 *                       unsupported types that slipped through.
 *        │
 *   HealthConnect       map NEW records to Health Connect records and write them in batches of 500
 *   Mapper/Writer       (HC insert limit), persisting fingerprints ONLY after each batch is confirmed.
 *        │
 *   SyncLogger          persist a [SyncSession] row summarizing the run for the Sync History screen.
 *
 * The whole flow is exposed as a cold [Flow] so the UI (ProcessingScreen / WritingScreen) can render
 * live progress and the pipeline only runs when collected. All heavy work runs on [Dispatchers.IO].
 *
 * SECURITY: no network is ever touched. The extracted XML lives only in cacheDir and is deleted in a
 * `finally` block regardless of success or failure.
 */
class SyncEngine(
    private val context: Context,
    // NOTE: these collaborators are owned by sibling agents; constructor-injected so this engine is
    // testable and so the exact production types can be swapped in once they land. Defaults wire the
    // real implementations.
    private val db: FingerprintDatabase = FingerprintDatabase.getInstance(context),
    // private val parser: AppleHealthParser = AppleHealthParser(),
    // private val fingerprintEngine: FingerprintEngine = FingerprintEngine(db.fingerprintDao()),
    // private val mapper: HealthConnectMapper = HealthConnectMapper(),
    // private val writer: HealthConnectWriter = HealthConnectWriter(context),
    // private val logger: SyncLogger = SyncLogger(db.syncLogDao()),
) {

    /**
     * Runs the full import pipeline for the export ZIP at [uri], writing only NEW records whose
     * [HealthDataType] is present in [enabled].
     *
     * @param uri     content Uri of the user-picked Apple Health export ZIP.
     * @param enabled the set of data types the user has toggled ON in Settings/DeltaReview.
     * @return a cold [Flow] of [SyncProgress] terminating in [SyncProgress.Done] or [SyncProgress.Failed].
     */
    fun runImport(uri: Uri, enabled: Set<HealthDataType>): Flow<SyncProgress> = flow {
        val startedAt = System.currentTimeMillis()
        val sourceFilename = resolveDisplayName(uri)
        var extractedXml: File? = null

        try {
            // ── Stage 1: UNPACK ──────────────────────────────────────────────────────────────
            emit(SyncProgress.Unpacking(0))
            extractedXml = ZipExtractor.extractExportXml(context, uri) { pct ->
                // TODO: surface true unzip progress; ZipExtractor invokes this with 0..100.
                // (Cannot emit from a non-suspend lambda here — see streaming note in ZipExtractor.)
            }
            emit(SyncProgress.Unpacking(100))

            // ── Stage 2: PARSE ───────────────────────────────────────────────────────────────
            // TODO: replace mock with parser.parse(extractedXml). Parser should stream and call back
            //       per-N-records so we can emit Parsing(scanned, currentType) without buffering all
            //       records in memory for very large exports.
            val parsed: List<AppleHealthRecord> = parseWithProgress(extractedXml) { scanned, currentType ->
                emit(SyncProgress.Parsing(scanned = scanned, currentType = currentType))
            }

            // ── Stage 3: DEDUPLICATE (compute delta) ─────────────────────────────────────────
            val knownCount = db.fingerprintDao().count()
            emit(SyncProgress.Deduplicating(known = knownCount))

            // FingerprintEngine.computeDelta: hashes each record (type+startDate+endDate+sourceName),
            // compares against the ledger, and returns per-type NEW vs SKIPPED counts plus the list of
            // NEW records to write.
            // TODO: val delta = fingerprintEngine.computeDelta(parsed)
            val (delta, newRecords) = computeDelta(parsed)
            emit(SyncProgress.DeltaReady(delta))

            // ── Stage 4: FILTER (drop toggled-off + unsupported types) ───────────────────────
            val toWrite = DataTypeFilter.apply(newRecords, enabled)

            // ── Stage 5: MAP + WRITE to Health Connect (batches of 500) ──────────────────────
            val total = toWrite.size
            var written = 0
            val writtenByType = linkedMapOf<String, Int>()
            emit(SyncProgress.Writing(written = 0, total = total))

            for (batch in toWrite.chunked(HC_BATCH_SIZE)) {
                // 1) map Apple -> Health Connect records for this batch.
                // TODO: val hcRecords = mapper.map(batch)
                // 2) write the batch; on success HC returns inserted ids.
                // TODO: val result = writer.write(hcRecords)
                // 3) ONLY after the batch write is confirmed, persist this batch's fingerprints.
                //    On partial failure: mark the failed batch and allow retry (do NOT persist
                //    fingerprints for unconfirmed records). See HealthConnectWriter retry contract.
                // TODO: fingerprintEngine.commit(batch)

                written += batch.size
                batch.groupingBy { it.type.name }.eachCount().forEach { (type, n) ->
                    writtenByType[type] = (writtenByType[type] ?: 0) + n
                }
                emit(SyncProgress.Writing(written = written, total = total))
            }

            // ── Stage 6: LOG the session ─────────────────────────────────────────────────────
            val durationMs = System.currentTimeMillis() - startedAt
            val session = SyncSession(
                timestamp = startedAt,
                sourceFilename = sourceFilename,
                durationMs = durationMs,
                recordsWritten = writtenByType,
                recordsSkipped = delta.skippedTotal,
                status = if (written == total) STATUS_SUCCESS else STATUS_PARTIAL
            )
            // TODO: logger.log(session)   // persists a SyncLogEntity via SyncLogDao.

            emit(SyncProgress.Done(session))
        } catch (t: Throwable) {
            // TODO: distinguish recoverable (HC permission/quota) vs. fatal (corrupt ZIP) errors.
            emit(SyncProgress.Failed(t.message ?: "Unknown import error"))
        } finally {
            // SECURITY: the extracted XML must never outlive the parse. Delete unconditionally.
            extractedXml?.delete()
        }
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Internal stage helpers — thin shims over sibling collaborators. Each is a // TODO seam.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Resolves a human-friendly source filename from a content [uri] for the sync log.
     */
    private fun resolveDisplayName(uri: Uri): String {
        // TODO: query OpenableColumns.DISPLAY_NAME via contentResolver; fall back to last path segment.
        return uri.lastPathSegment ?: "export.zip"
    }

    /**
     * Parse seam. In production this delegates to AppleHealthParser and forwards streaming progress.
     * @param onProgress invoked periodically with (recordsScanned, currentTypeDisplayName?).
     */
    private suspend fun parseWithProgress(
        xml: File,
        onProgress: suspend (scanned: Int, currentType: String?) -> Unit
    ): List<AppleHealthRecord> {
        // TODO: return parser.parse(xml, onProgress)
        onProgress(0, null)
        TODO("wire AppleHealthParser.parse(xml) with streaming progress callbacks")
    }

    /**
     * Delta seam. In production this delegates to FingerprintEngine.computeDelta and returns both the
     * UI-facing [DeltaResult] and the concrete list of NEW [AppleHealthRecord]s to be written.
     */
    private suspend fun computeDelta(
        parsed: List<AppleHealthRecord>
    ): Pair<DeltaResult, List<AppleHealthRecord>> {
        // TODO: val delta = fingerprintEngine.computeDelta(parsed); return delta to delta.newRecords
        TODO("wire FingerprintEngine.computeDelta(parsed)")
    }

    companion object {
        /** Health Connect insertRecords limit per call. Writes are chunked to this size. */
        const val HC_BATCH_SIZE = 500

        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_PARTIAL = "PARTIAL"
        const val STATUS_FAILED = "FAILED"
    }

    /**
     * ZipExtractor — extracts `export.xml` from an Apple Health export ZIP into the app cacheDir.
     *
     * Apple's export ZIP nests the file as `apple_health_export/export.xml`; we match by leaf name so
     * we're resilient to layout changes. The extracted file lives only in cacheDir and the caller is
     * responsible for deleting it after parse (SyncEngine does this in a `finally`).
     *
     * Kept private/nested: it is an implementation detail of the engine, not part of the public API.
     */
    private object ZipExtractor {

        private const val TARGET_ENTRY = "export.xml"
        private const val EXTRACT_PREFIX = "hb_export_"

        /**
         * Streams the ZIP at [uri] and writes the first `export.xml` entry into cacheDir.
         *
         * @param onProgress invoked with an estimated 0..100 percent as bytes are inflated.
         * @return the extracted XML [File] in cacheDir.
         * @throws IllegalStateException if the ZIP cannot be opened or contains no export.xml.
         */
        fun extractExportXml(
            context: Context,
            uri: Uri,
            onProgress: (pct: Int) -> Unit
        ): File {
            val input: InputStream = context.contentResolver.openInputStream(uri)
                ?: error("Could not open export ZIP: $uri")

            val outFile = File.createTempFile(EXTRACT_PREFIX, ".xml", context.cacheDir)

            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val leaf = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && leaf.equals(TARGET_ENTRY, ignoreCase = true)) {
                        outFile.outputStream().buffered().use { out ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var read = zip.read(buffer)
                            while (read >= 0) {
                                out.write(buffer, 0, read)
                                // TODO: compute real pct from entry.size when available (often -1 in
                                //       streamed ZIPs); for now emit indeterminate ticks.
                                onProgress(-1)
                                read = zip.read(buffer)
                            }
                        }
                        zip.closeEntry()
                        onProgress(100)
                        return outFile
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            // No export.xml found — clean up the empty temp file and fail loudly.
            outFile.delete()
            error("Invalid Apple Health export: '$TARGET_ENTRY' not found in archive.")
        }
    }

    /**
     * DataTypeFilter — drops records the user has toggled OFF and any unsupported types.
     *
     * Because [AppleHealthRecord.type] is a [HealthDataType] (already one of the 7 supported types),
     * "unsupported" records are filtered out earlier by the parser; this stage enforces the user's
     * per-type enable/disable choices from Settings/DeltaReview.
     */
    private object DataTypeFilter {
        fun apply(
            records: List<AppleHealthRecord>,
            enabled: Set<HealthDataType>
        ): List<AppleHealthRecord> = records.filter { it.type in enabled }
    }
}

/**
 * SyncProgress — the sealed set of states emitted across the import pipeline. Collected by
 * ProcessingScreen (Unpacking/Parsing/Deduplicating/DeltaReady) and WritingScreen (Writing/Done/Failed).
 */
sealed class SyncProgress {

    /** Inflating the export ZIP. [pct] is 0..100, or -1 when indeterminate. */
    data class Unpacking(val pct: Int) : SyncProgress()

    /** Streaming export.xml. [scanned] records seen so far; [currentType] the type being read, if known. */
    data class Parsing(val scanned: Int, val currentType: String?) : SyncProgress()

    /** Comparing parsed records against the local fingerprint ledger. [known] = existing fingerprints. */
    data class Deduplicating(val known: Int) : SyncProgress()

    /** Delta computed; UI can show the per-type NEW vs SKIPPED breakdown for user confirmation. */
    data class DeltaReady(val delta: DeltaResult) : SyncProgress()

    /** Writing NEW records to Health Connect. [written] of [total] complete. */
    data class Writing(val written: Int, val total: Int) : SyncProgress()

    /** Pipeline finished; [session] is the persisted summary of the run. */
    data class Done(val session: SyncSession) : SyncProgress()

    /** Terminal failure with a user-presentable [message]. */
    data class Failed(val message: String) : SyncProgress()
}
