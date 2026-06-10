package com.healthbridge.ui.sync

import android.app.Application
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healthbridge.parser.AppleHealthRecord
import com.healthbridge.parser.DeltaResult
import com.healthbridge.parser.HealthDataType
import com.healthbridge.parser.SyncSession
import com.healthbridge.sync.SyncEngine
import com.healthbridge.sync.SyncProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * SyncViewModel — owns the single [SyncEngine] for the whole import flow and the two-phase,
 * user-gated pipeline (Processing → Delta → Writing).
 *
 * TWO-PHASE GATING (PRD):
 *  - Phase 1 ([startAnalyze]) runs parse + dedupe and stops at [SyncProgress.DeltaReady]; the NEW
 *    records it computes are CACHED here ([newRecords]) — they cannot ride the pinned [SyncProgress]
 *    sealed type, and nav only carries the fileUri String.
 *  - The user confirms on the Delta screen; [confirmWrite] then runs phase 2 (Writing → Done) using
 *    the cached records.
 *
 * Because the Processing/Delta/Writing screens each get their own NavBackStackEntry, ALL THREE must
 * read the SAME instance of this VM (Activity-scoped — hoisted once in HealthBridgeApp and passed
 * down) so the cached records survive between them. See HealthBridgeApp wiring.
 *
 * The split runAnalyze/runWrite design (rather than one flow with a suspending gate) is deliberate:
 * nav tears down/rebuilds composables between Processing and Delta, which would cancel a suspended
 * collector. Splitting the phases survives that.
 *
 * AVAILABILITY: phase 1 needs NO Health Connect client (pure parse + dedupe), so the engine builds
 * its [HealthConnectClient] LAZILY — analyze works even when Health Connect is unavailable, and the
 * client is only created when [confirmWrite] runs. Callers should gate [confirmWrite] on
 * `HealthConnectManager.isAvailable && hasAllPermissions()`.
 */
class SyncViewModel(app: Application) : AndroidViewModel(app) {

    // Engine is built lazily so constructing the HealthConnectClient (which throws when the HC SDK is
    // unavailable) is deferred until the write phase actually needs it. Phase 1 (analyze) never
    // touches the client.
    private val engine: SyncEngine by lazy {
        SyncEngine(
            context = app,
            hcClient = HealthConnectClient.getOrCreate(app),
        )
    }

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    /** Latest engine emission across both phases. Starts null. */
    val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    private val _delta = MutableStateFlow<DeltaResult?>(null)
    /** Set once phase 1 produces a [SyncProgress.DeltaReady]. Starts null. */
    val delta: StateFlow<DeltaResult?> = _delta.asStateFlow()

    // ── Cached handoff between phases (NOT passed through nav) ──────────────────────────────────
    private var newRecords: List<AppleHealthRecord> = emptyList()
    private var lastUri: Uri? = null
    private var enabledTypes: Set<HealthDataType> = emptySet()
    private var sourceFilename: String = "export.zip"
    private var startedAt: Long = 0L

    /** Number of NEW records computed by phase 1 (for screens that show it). */
    val newRecordCount: Int get() = newRecords.size

    /**
     * Phase 1 — launches [SyncEngine.runAnalyze] for the Processing UI and, in parallel work,
     * obtains the concrete NEW records via the suspend [SyncEngine.analyze] so phase 2 can write
     * exactly what was analyzed.
     *
     * Idempotent on recompose: re-invoking with the same [uri] while a run is already in flight (or
     * completed) is a no-op, so a Processing recomposition won't restart the pipeline.
     */
    fun startAnalyze(uri: Uri, enabled: Set<HealthDataType>) {
        // Guard against recompose-triggered restarts of the same analyze.
        if (_progress.value != null && lastUri == uri) return

        lastUri = uri
        enabledTypes = enabled

        viewModelScope.launch {
            // Drive the analyze result (delta + records) from the suspend entry point; this also
            // gives us sourceFilename + startedAt for an honest cross-phase duration.
            runCatching { engine.analyze(uri, enabled) }
                .onSuccess { result ->
                    newRecords = result.newRecords
                    sourceFilename = result.sourceFilename
                    startedAt = result.startedAt
                    _delta.value = result.delta

                    // Replay the phase-1 progress lifecycle for the Processing stepper. We emit the
                    // coarse markers here (rather than collecting runAnalyze separately) so the
                    // cached records and the UI advance from one authoritative pass.
                    _progress.value = SyncProgress.Unpacking(100)
                    _progress.value = SyncProgress.Deduplicating(known = result.delta.scannedTotal)
                    _progress.value = SyncProgress.DeltaReady(result.delta)
                }
                .onFailure { t ->
                    _progress.value = SyncProgress.Failed(t.message ?: "Unknown import error")
                }
        }
    }

    /** Alias of [startAnalyze] for callers that prefer the contract's named `start`. */
    fun start(uri: Uri, enabled: Set<HealthDataType>) = startAnalyze(uri, enabled)

    /**
     * Phase 2 — writes the cached NEW records to Health Connect, emitting Writing/Done/Failed into
     * [progress]. A no-op write (zero-delta) emits a Done(empty session) directly so the Delta
     * "Done" path still terminates cleanly.
     */
    fun confirmWrite() {
        viewModelScope.launch {
            if (newRecords.isEmpty()) {
                // Zero-delta: nothing to write. Emit an empty Done so Writing settles into success.
                _progress.value = SyncProgress.Done(
                    SyncSession(
                        timestamp = if (startedAt != 0L) startedAt else System.currentTimeMillis(),
                        sourceFilename = sourceFilename,
                        durationMs = 0L,
                        recordsWritten = emptyMap(),
                        recordsSkipped = _delta.value?.skippedTotal ?: 0,
                        status = SyncEngine.STATUS_SUCCESS,
                    )
                )
                return@launch
            }

            engine.runWrite(
                newRecords = newRecords,
                sourceFilename = sourceFilename,
                startedAt = if (startedAt != 0L) startedAt else System.currentTimeMillis(),
                enabled = enabledTypes,
            ).collect { _progress.value = it }
        }
    }

    /** Re-runs the write for failed batches (delegates to the writer's retry path). */
    fun retry() {
        viewModelScope.launch {
            engine.runRetry(
                sourceFilename = sourceFilename,
                startedAt = if (startedAt != 0L) startedAt else System.currentTimeMillis(),
            ).collect { _progress.value = it }
        }
    }

    /** Clears all state for a fresh import. */
    fun reset() {
        _progress.value = null
        _delta.value = null
        newRecords = emptyList()
        lastUri = null
        enabledTypes = emptySet()
        sourceFilename = "export.zip"
        startedAt = 0L
    }
}
