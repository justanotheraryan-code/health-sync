package com.healthbridge.sync

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.health.connect.client.HealthConnectClient
import com.healthbridge.fingerprint.FingerprintDatabase
import com.healthbridge.fingerprint.FingerprintEngine
import com.healthbridge.healthconnect.HealthConnectMapper
import com.healthbridge.healthconnect.HealthConnectWriter
import com.healthbridge.parser.AppleHealthParser
import com.healthbridge.parser.AppleHealthRecord
import com.healthbridge.parser.DeltaResult
import com.healthbridge.parser.HealthDataType
import com.healthbridge.parser.SyncSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
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
 * TWO-PHASE GATING (PRD: the user reviews the delta before anything is written):
 *
 *   Phase 1 — [runAnalyze] / [analyze]  : Unpacking → Parsing → Deduplicating → DeltaReady. NO write,
 *             NO Health Connect access required. Works even if Health Connect is unavailable.
 *   Phase 2 — [runWrite]                : Writing → Done. Requires Health Connect + granted permissions
 *             (the writer's [HealthConnectClient] is touched only here).
 *
 * The NEW-records handoff between phases cannot ride the (pinned) [SyncProgress] sealed type, so the
 * caller (SyncViewModel) obtains them from the suspend [analyze] entry point ([AnalyzeResult]) and
 * feeds them back into [runWrite]. The [runImport] convenience method runs both phases back-to-back
 * for non-gated callers/tests.
 *
 * The phases are exposed as cold [Flow]s so the UI (ProcessingScreen / WritingScreen) can render live
 * progress and the work only runs when collected. All heavy work runs on [Dispatchers.IO].
 *
 * SECURITY: no network is ever touched. The extracted XML lives only in cacheDir and is deleted in a
 * `finally` block regardless of success or failure.
 */
class SyncEngine(
    private val context: Context,
    // The Health Connect client is required so this engine can build a [HealthConnectWriter]. It is
    // only touched during the write phase, so construct the engine lazily/late (after availability is
    // confirmed) when used for writing. Analyze-only callers can pass any client.
    private val hcClient: HealthConnectClient,
    private val db: FingerprintDatabase = FingerprintDatabase.getInstance(context),
    private val parser: AppleHealthParser = AppleHealthParser(),
    // NOTE: HealthConnectMapper is an `object` (HealthConnectMapper.map(record, workout?)), not a
    //       class — pass the object reference; HealthConnectWriter calls mapper.map(record).
    private val mapper: HealthConnectMapper = HealthConnectMapper,
    private val fingerprintEngine: FingerprintEngine = FingerprintEngine(db.fingerprintDao()),
    private val writer: HealthConnectWriter =
        HealthConnectWriter(hcClient, mapper, fingerprintEngine),
    private val logger: SyncLogger = SyncLogger(db.syncLogDao()),
) {

    /**
     * Result of the phase-1 [analyze] pass. Carries everything the caller needs to (a) render the
     * delta for user confirmation and (b) drive the phase-2 [runWrite] with the EXACT records that
     * were computed during analyze — no re-parse, no second DB read.
     *
     * @param delta          per-type NEW vs SKIPPED breakdown for the DeltaReview screen.
     * @param newRecords     the concrete NEW (unknown-hash, enabled-type) records to write.
     * @param sourceFilename human-friendly source filename for the sync log.
     * @param startedAt      epoch millis the run started, so the logged duration spans both phases.
     */
    data class AnalyzeResult(
        val delta: DeltaResult,
        val newRecords: List<AppleHealthRecord>,
        val sourceFilename: String,
        val startedAt: Long,
    )

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Phase 1 — ANALYZE (parse + dedupe; stops at DeltaReady; no write)
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Phase-1 progress stream: Unpacking → Parsing → Deduplicating → DeltaReady. Terminates at
     * [SyncProgress.DeltaReady] and does NOT write. On any error emits [SyncProgress.Failed].
     *
     * This is collected purely for the Processing UI. The concrete NEW records are obtained
     * separately via the suspend [analyze] entry point (the pinned [SyncProgress] cannot carry them).
     *
     * @param uri     content Uri of the user-picked Apple Health export ZIP.
     * @param enabled the set of data types the user has toggled ON.
     */
    fun runAnalyze(uri: Uri, enabled: Set<HealthDataType>): Flow<SyncProgress> = channelFlow {
        var extractedXml: File? = null
        try {
            // ── Stage 1: UNPACK ──────────────────────────────────────────────────────────────
            send(SyncProgress.Unpacking(0))
            extractedXml = ZipExtractor.extractExportXml(context, uri) { /* indeterminate; see NOTE */ }
            send(SyncProgress.Unpacking(100))

            // ── Stage 2: PARSE ───────────────────────────────────────────────────────────────
            // parser.parse(File, ...) owns the stream lifecycle (opens/reads/closes) and invokes the
            // suspending onProgress so we can forward Parsing emissions directly into this channel.
            val parsed = parser.parse(extractedXml, enabled) { scanned, currentType ->
                // SyncProgress.Parsing.currentType is a String?; pass the enum's displayName so the
                // Processing mapper can recover it via HealthDataType.entries.firstOrNull { ... }.
                send(SyncProgress.Parsing(scanned = scanned, currentType = currentType?.displayName))
            }

            // ── Stage 3: DEDUPLICATE (compute delta) ─────────────────────────────────────────
            send(SyncProgress.Deduplicating(known = db.fingerprintDao().count()))
            val (delta, _) = fingerprintEngine.computeDeltaAndNew(parsed, enabled)
            send(SyncProgress.DeltaReady(delta))
        } catch (t: Throwable) {
            send(SyncProgress.Failed(t.message ?: "Unknown import error"))
        } finally {
            // SECURITY: the extracted XML must never outlive the parse. Delete unconditionally.
            extractedXml?.delete()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Phase-1 (suspend variant): runs Unpacking → Parsing → Deduplicating and returns the full
     * [AnalyzeResult] — the delta AND the concrete NEW records to write. Does NOT emit progress
     * (use [runAnalyze] for that). The caller (SyncViewModel) typically collects [runAnalyze] for
     * the Processing UI and calls this to obtain the records to hand to [runWrite].
     *
     * No Health Connect access is required: this is pure parse + dedupe.
     */
    suspend fun analyze(uri: Uri, enabled: Set<HealthDataType>): AnalyzeResult {
        val startedAt = System.currentTimeMillis()
        val sourceFilename = resolveDisplayName(uri)
        var extractedXml: File? = null
        try {
            extractedXml = ZipExtractor.extractExportXml(context, uri) { /* indeterminate */ }
            val parsed = parser.parse(extractedXml, enabled) { _, _ -> /* progress unused here */ }
            val (delta, newRecords) = fingerprintEngine.computeDeltaAndNew(parsed, enabled)
            return AnalyzeResult(
                delta = delta,
                newRecords = newRecords,
                sourceFilename = sourceFilename,
                startedAt = startedAt,
            )
        } finally {
            extractedXml?.delete()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Phase 2 — WRITE (map + batched Health Connect insert; logs the session)
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Phase-2 progress stream: Writing(written,total)* → Done(session) (or Failed).
     *
     * Bridges the writer's NON-suspend onProgress callback into the Flow via [channelFlow] +
     * `trySend`. Touches the Health Connect client (via [writer]) — callers must ensure Health
     * Connect is available and all permissions are granted before collecting this.
     *
     * @param newRecords     the NEW records computed during [analyze] (phase 1).
     * @param sourceFilename for the logged [SyncSession].
     * @param startedAt      epoch millis the whole run started (phase 1), so the logged duration is honest.
     * @param enabled        used only for a belt-and-suspenders re-filter via [DataTypeFilter].
     */
    fun runWrite(
        newRecords: List<AppleHealthRecord>,
        sourceFilename: String,
        startedAt: Long,
        enabled: Set<HealthDataType>,
    ): Flow<SyncProgress> = channelFlow {
        try {
            // computeDeltaAndNew already restricted to enabled types; this is a redundant safety net
            // that is a no-op for correctly-scoped input.
            val toWrite = DataTypeFilter.apply(newRecords, enabled)
            val total = toWrite.size

            send(SyncProgress.Writing(written = 0, total = total))

            val result = writer.write(toWrite) { written, t ->
                // Non-suspend lambda → bridge into the Flow without emit(); trySend is safe here.
                trySend(SyncProgress.Writing(written = written, total = t))
            }

            // Per-type write tally for the session summary. The writer drops records whose mapper
            // returns null, but for the MVP we attribute the confirmed-written count proportionally
            // by source type (mapper.map is null only for true edge cases). We report the actual
            // written total against the types present in the input.
            val writtenByType = linkedMapOf<String, Int>()
            if (result.written > 0) {
                // Distribute by type using the confirmed-written prefix (records are written in
                // input order, batch by batch), which keeps the per-type counts faithful even on
                // a partial write.
                toWrite.take(result.written)
                    .groupingBy { it.type.name }
                    .eachCount()
                    .forEach { (type, n) -> writtenByType[type] = n }
            }

            val status = when (result.status) {
                HealthConnectWriter.STATUS_SUCCESS -> STATUS_SUCCESS
                HealthConnectWriter.STATUS_PARTIAL -> STATUS_PARTIAL
                else -> STATUS_FAILED
            }

            val session = SyncSession(
                timestamp = startedAt,
                sourceFilename = sourceFilename,
                durationMs = System.currentTimeMillis() - startedAt,
                recordsWritten = writtenByType,
                // Skipped count is computed during analyze; the write phase only sees NEW records,
                // so skipped == 0 from this phase's vantage point.
                recordsSkipped = 0,
                status = status,
            )
            logger.log(session)

            send(SyncProgress.Done(session))
        } catch (t: Throwable) {
            send(SyncProgress.Failed(t.message ?: "Unknown write error"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Re-attempts ONLY the batches that failed during the preceding [runWrite] (delegates to
     * [HealthConnectWriter.retryFailed]). Emits Writing* → Done (or Failed).
     *
     * @param sourceFilename for the logged retry [SyncSession].
     * @param startedAt      epoch millis used for the logged timestamp/duration.
     */
    fun runRetry(sourceFilename: String, startedAt: Long): Flow<SyncProgress> = channelFlow {
        try {
            val pending = writer.pendingRetryCount()
            send(SyncProgress.Writing(written = 0, total = pending))

            val result = writer.retryFailed { written, t ->
                trySend(SyncProgress.Writing(written = written, total = t))
            }

            val status = when (result.status) {
                HealthConnectWriter.STATUS_SUCCESS -> STATUS_SUCCESS
                HealthConnectWriter.STATUS_PARTIAL -> STATUS_PARTIAL
                else -> STATUS_FAILED
            }
            val session = SyncSession(
                timestamp = startedAt,
                sourceFilename = sourceFilename,
                durationMs = System.currentTimeMillis() - startedAt,
                recordsWritten = linkedMapOf(),
                recordsSkipped = 0,
                status = status,
            )
            logger.log(session)
            send(SyncProgress.Done(session))
        } catch (t: Throwable) {
            send(SyncProgress.Failed(t.message ?: "Unknown retry error"))
        }
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Convenience — non-gated, runs both phases back-to-back (for tests / non-UI callers)
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Runs the full pipeline (analyze THEN write) in one flow, without the user-confirmation gate.
     * Retained for non-gated callers and tests; the gated UI uses [runAnalyze] + [runWrite].
     */
    fun runImport(uri: Uri, enabled: Set<HealthDataType>): Flow<SyncProgress> = callbackFlow {
        try {
            val analysis = analyze(uri, enabled)
            // Replay coarse phase markers so a non-UI collector still observes the lifecycle.
            send(SyncProgress.Unpacking(100))
            send(SyncProgress.Deduplicating(known = db.fingerprintDao().count()))
            send(SyncProgress.DeltaReady(analysis.delta))
            runWrite(
                newRecords = analysis.newRecords,
                sourceFilename = analysis.sourceFilename,
                startedAt = analysis.startedAt,
                enabled = enabled,
            ).collect { send(it) }
            close()
        } catch (t: Throwable) {
            send(SyncProgress.Failed(t.message ?: "Unknown import error"))
            close()
        }
        awaitClose { /* nothing to release */ }
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Resolves a human-friendly source filename from a content [uri] for the sync log, querying
     * [OpenableColumns.DISPLAY_NAME] and falling back to the last path segment.
     */
    private fun resolveDisplayName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                } ?: uri.lastPathSegment ?: "export.zip"
        } catch (_: Throwable) {
            uri.lastPathSegment ?: "export.zip"
        }
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
            onProgress: (pct: Int) -> Unit,
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
                                // NOTE: true unzip-% stays TODO (entry.size is often -1 in streamed
                                //       ZIPs); emit indeterminate ticks per the rules.
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
     * per-type enable/disable choices from Settings/DeltaReview. Since [FingerprintEngine.computeDeltaAndNew]
     * already restricts to enabled types, this is a redundant safety net (no-op for scoped input).
     */
    private object DataTypeFilter {
        fun apply(
            records: List<AppleHealthRecord>,
            enabled: Set<HealthDataType>,
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
