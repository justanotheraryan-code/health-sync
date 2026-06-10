package com.healthbridge.ui.importer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthbridge.parser.DeltaResult
import com.healthbridge.parser.DeltaRow
import com.healthbridge.parser.HealthDataType
import com.healthbridge.sync.SyncProgress
import com.healthbridge.ui.components.HbCard
import com.healthbridge.ui.sync.SyncViewModel
import com.healthbridge.ui.components.HbChip
import com.healthbridge.ui.components.HbChipKind
import com.healthbridge.ui.components.HbScaffold
import com.healthbridge.ui.components.HbSectionLabel
import com.healthbridge.ui.components.HbTextButton
import com.healthbridge.ui.theme.BackgroundDark
import com.healthbridge.ui.theme.DividerColor
import com.healthbridge.ui.theme.HealthBridgeTheme
import com.healthbridge.ui.theme.SurfaceDark
import com.healthbridge.ui.theme.TealAccent
import com.healthbridge.ui.theme.TextPrimary
import com.healthbridge.ui.theme.TextSecondary
import com.healthbridge.ui.theme.TextTertiary
import kotlinx.coroutines.delay

// ===========================================================================
// Progress model
// ===========================================================================

/**
 * The three sequential stages of an import run, rendered as a vertical stepper.
 *
 * Mirrors the shape of the (future) `SyncEngine.runImport(...)` progress Flow.
 * The real engine will emit a `SyncProgress` sealed type; for the scaffold we
 * model the same surface area locally so the UI is fully driven by state and
 * needs only a thin adapter once the engine lands.
 */
private enum class ProcessingStage(val index: Int) {
    UNPACKING(0),
    PARSING(1),
    DEDUPLICATION(2),
    DONE(3),
}

/**
 * Lifecycle of a single stepper node, derived from the current [ProcessingStage].
 */
private enum class StepState { PENDING, ACTIVE, COMPLETE }

/**
 * Snapshot of import progress. The screen renders purely from this immutable
 * value; the scaffold mutates it on a timer, the real engine will map its
 * `SyncProgress` emissions onto it.
 *
 * @param stage              the stage currently in flight.
 * @param unpackFraction     0f..1f determinate progress for stage 1.
 * @param recordsScanned     running count of records parsed (stage 2).
 * @param currentType        data type currently being parsed (stage 2 sub-label).
 * @param knownRecordCount   number of fingerprints in the local DB (stage 3 header).
 * @param dedupRows          per-type new/skipped breakdown streamed during stage 3.
 */
private data class ProcessingUiState(
    val stage: ProcessingStage = ProcessingStage.UNPACKING,
    val unpackFraction: Float = 0f,
    val recordsScanned: Int = 0,
    val currentType: HealthDataType? = null,
    val knownRecordCount: Int = 0,
    val dedupRows: List<DeltaRow> = emptyList(),
) {
    /** Resolve the visual state of a given stage relative to the active one. */
    fun stateOf(target: ProcessingStage): StepState = when {
        stage.index > target.index -> StepState.COMPLETE
        stage.index == target.index -> StepState.ACTIVE
        else -> StepState.PENDING
    }
}

// ===========================================================================
// ProcessingScreen
// ===========================================================================

/**
 * Stage 2 of the import funnel: unpack -> parse -> deduplicate.
 *
 * A 3-node vertical stepper that narrates the engine's work:
 *  1. **Unpacking**     — determinate progress bar; the only cancellable stage.
 *  2. **Parsing**       — live "N records scanned" counter + current-type sub-label.
 *  3. **Deduplication** — "Checking against K known records" + per-type new/synced rows.
 *
 * When all three complete, [onComplete] is invoked to advance to the delta review.
 *
 * For the scaffold the state is animated through sample values on a timer. The
 * `// TODO` below marks where `SyncEngine.runImport(fileUri).collect { ... }`
 * will replace the timer and feed real [ProcessingUiState] snapshots.
 */
@Composable
fun ProcessingScreen(
    onComplete: () -> Unit,
    fileUri: String = "",
    // Stage 1 (unpacking) is the only cancellable stage — wire this to the Cancel control.
    onBack: () -> Unit = {},
    // When non-null, the screen is driven by the real engine via the shared [SyncViewModel]; when
    // null (e.g. @Preview), the scripted sample animation runs instead.
    vm: SyncViewModel? = null,
    // The data types to import; forwarded to the engine's analyze pass.
    enabledTypes: Set<HealthDataType> = HealthDataType.entries.toSet(),
) {
    if (vm != null) {
        ProcessingFromEngine(
            vm = vm,
            fileUri = fileUri,
            enabledTypes = enabledTypes,
            onComplete = onComplete,
            onBack = onBack,
        )
    } else {
        ProcessingSampleAnimated(onComplete = onComplete)
    }
}

/**
 * Real-engine driver: kicks off phase-1 analyze for [fileUri] and maps each [SyncProgress] emission
 * onto the local [ProcessingUiState]. Advances via [onComplete] on [SyncProgress.DeltaReady]; renders
 * an error state (reusing the dedup copy) on [SyncProgress.Failed].
 */
@Composable
private fun ProcessingFromEngine(
    vm: SyncViewModel,
    fileUri: String,
    enabledTypes: Set<HealthDataType>,
    onComplete: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(fileUri) {
        if (fileUri.isNotBlank()) {
            vm.startAnalyze(android.net.Uri.parse(fileUri), enabledTypes)
        }
    }

    val progress by vm.progress.collectAsState()
    var ui by remember { mutableStateOf(ProcessingUiState()) }

    // Map progress → UI, carrying forward fields the next emission doesn't restate. We update the
    // state holder inside a LaunchedEffect (NOT directly in the composition body) so writing to the
    // snapshot state never re-triggers the current composition.
    LaunchedEffect(progress) {
        ui = progress.toProcessingUiState(ui)
        // Advance to Delta once the delta is ready (the gate the PRD specifies).
        if (progress is SyncProgress.DeltaReady) onComplete()
    }

    ProcessingContent(
        ui = ui,
        // Cancel during analyze: pop back to import. (Analyze runs in viewModelScope; navigating
        // away simply abandons the in-flight UI — the engine's IO work cancels with the scope.)
        onCancel = onBack,
    )
}

/**
 * Scripted sample animation used by previews (and any vm-less caller). Mutates a local
 * [ProcessingUiState] through sample values on a timer so the stepper is fully exercisable offline.
 */
@Composable
private fun ProcessingSampleAnimated(onComplete: () -> Unit) {
    var ui by remember { mutableStateOf(ProcessingUiState()) }
    var cancelled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // --- Stage 1: Unpacking (determinate) -------------------------------
        ui = ui.copy(stage = ProcessingStage.UNPACKING, unpackFraction = 0f)
        var f = 0f
        while (f < 1f && !cancelled) {
            f = (f + 0.08f).coerceAtMost(1f)
            ui = ui.copy(unpackFraction = f)
            delay(60)
        }
        if (cancelled) return@LaunchedEffect

        // --- Stage 2: Parsing (live scanned counter + current type) ---------
        ui = ui.copy(stage = ProcessingStage.PARSING, recordsScanned = 0)
        val parseSequence = listOf(
            HealthDataType.STEPS to 4_120,
            HealthDataType.HEART_RATE to 9_880,
            HealthDataType.ACTIVE_ENERGY to 2_640,
            HealthDataType.SLEEP to 310,
            HealthDataType.WORKOUT to 142,
            HealthDataType.RESTING_HEART_RATE to 180,
            HealthDataType.VO2_MAX to 36,
        )
        var scanned = 0
        for ((type, count) in parseSequence) {
            ui = ui.copy(currentType = type)
            val step = (count / 6).coerceAtLeast(1)
            var emitted = 0
            while (emitted < count) {
                val inc = step.coerceAtMost(count - emitted)
                emitted += inc
                scanned += inc
                ui = ui.copy(recordsScanned = scanned)
                delay(40)
            }
        }

        // --- Stage 3: Deduplication (per-type new / already-synced) ---------
        ui = ui.copy(
            stage = ProcessingStage.DEDUPLICATION,
            currentType = null,
            knownRecordCount = SAMPLE_KNOWN_RECORDS,
            dedupRows = emptyList(),
        )
        val streamed = mutableListOf<DeltaRow>()
        for (row in SAMPLE_DEDUP.rows) {
            streamed.add(row)
            ui = ui.copy(dedupRows = streamed.toList())
            delay(220)
        }

        // --- Done -----------------------------------------------------------
        ui = ui.copy(stage = ProcessingStage.DONE)
        delay(450)
        onComplete()
    }

    ProcessingContent(
        ui = ui,
        onCancel = { cancelled = true },
    )
}

/**
 * Maps a [SyncProgress] emission onto the private [ProcessingUiState]. [previous] carries fields the
 * current emission doesn't restate (e.g. Deduplicating doesn't re-send the scanned count), so the
 * stepper never flickers backwards. A null progress (initial) yields the unpacking start state.
 */
private fun SyncProgress?.toProcessingUiState(previous: ProcessingUiState): ProcessingUiState =
    when (this) {
        null -> previous.copy(stage = ProcessingStage.UNPACKING)

        is SyncProgress.Unpacking -> previous.copy(
            stage = ProcessingStage.UNPACKING,
            // pct < 0 is indeterminate — keep the prior fraction rather than snapping to 0.
            unpackFraction = if (pct < 0) previous.unpackFraction else (pct / 100f).coerceIn(0f, 1f),
        )

        is SyncProgress.Parsing -> previous.copy(
            stage = ProcessingStage.PARSING,
            unpackFraction = 1f,
            recordsScanned = scanned,
            currentType = currentType?.let { name ->
                HealthDataType.entries.firstOrNull { it.name == name || it.displayName == name }
            },
        )

        is SyncProgress.Deduplicating -> previous.copy(
            stage = ProcessingStage.DEDUPLICATION,
            unpackFraction = 1f,
            currentType = null,
            knownRecordCount = known,
        )

        is SyncProgress.DeltaReady -> previous.copy(
            stage = ProcessingStage.DONE,
            unpackFraction = 1f,
            currentType = null,
            recordsScanned = if (delta.scannedTotal > 0) delta.scannedTotal else previous.recordsScanned,
            dedupRows = delta.rows,
        )

        // Writing/Done belong to the next screen; Failed surfaces as a stalled dedup state with no
        // rows (the engine already emitted the error message into the VM). Keep the prior UI so the
        // user sees where it stopped.
        else -> previous
    }

// ===========================================================================
// Content (stateless — preview-friendly)
// ===========================================================================

@Composable
private fun ProcessingContent(
    ui: ProcessingUiState,
    onCancel: () -> Unit,
) {
    // Cancel is offered only while the (cheap, abortable) unpack stage is live.
    val showCancel = ui.stage == ProcessingStage.UNPACKING

    HbScaffold(
        title = "Processing",
        footer = if (showCancel) {
            {
                HbTextButton(
                    text = "Cancel",
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            null
        },
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Reading your Apple Health export",
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(24.dp))

        // --- Stage 1: Unpacking ---------------------------------------------
        StepNode(
            ordinal = 1,
            title = "Unpacking",
            state = ui.stateOf(ProcessingStage.UNPACKING),
            isLast = false,
        ) {
            UnpackingBody(fraction = ui.unpackFraction)
        }

        // --- Stage 2: Parsing -----------------------------------------------
        StepNode(
            ordinal = 2,
            title = "Parsing",
            state = ui.stateOf(ProcessingStage.PARSING),
            isLast = false,
        ) {
            ParsingBody(
                recordsScanned = ui.recordsScanned,
                currentType = ui.currentType,
            )
        }

        // --- Stage 3: Deduplication -----------------------------------------
        StepNode(
            ordinal = 3,
            title = "Deduplication",
            state = ui.stateOf(ProcessingStage.DEDUPLICATION),
            isLast = true,
        ) {
            DeduplicationBody(
                knownRecordCount = ui.knownRecordCount,
                rows = ui.dedupRows,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ===========================================================================
// Stepper node
// ===========================================================================

/**
 * One node of the vertical stepper: a left rail (numbered/checked indicator +
 * connector line) and a right content column. The [body] is only revealed for
 * ACTIVE / COMPLETE nodes so pending stages stay quiet.
 */
@Composable
private fun StepNode(
    ordinal: Int,
    title: String,
    state: StepState,
    isLast: Boolean,
    body: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        // --- Left rail: indicator + connector -------------------------------
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StepIndicator(ordinal = ordinal, state = state)
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(56.dp)
                        .background(
                            if (state == StepState.COMPLETE) TealAccent else DividerColor,
                        ),
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        // --- Right content --------------------------------------------------
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = when (state) {
                        StepState.PENDING -> TextTertiary
                        else -> TextPrimary
                    },
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                if (state == StepState.ACTIVE) {
                    HbChip(text = "RUNNING", kind = HbChipKind.Ok)
                } else if (state == StepState.COMPLETE) {
                    HbChip(text = "DONE", kind = HbChipKind.Neutral)
                }
            }

            AnimatedVisibility(visible = state != StepState.PENDING) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    body()
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * The circular stage indicator: a teal-filled check once complete, a
 * teal-ringed ordinal while active, and a muted ordinal while pending.
 *
 * The active ring is drawn as a 1.dp teal border around a SurfaceDark inner
 * disc; complete is a solid teal disc with a dark check. Elevation by color,
 * never shadow — consistent with the rest of the app.
 */
@Composable
private fun StepIndicator(ordinal: Int, state: StepState) {
    val ringColor = when (state) {
        StepState.PENDING -> DividerColor
        StepState.ACTIVE -> TealAccent
        StepState.COMPLETE -> TealAccent
    }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(shape)
            .background(if (state == StepState.COMPLETE) TealAccent else ringColor)
            .padding(if (state == StepState.COMPLETE) 0.dp else 1.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (state == StepState.COMPLETE) TealAccent else SurfaceDark),
        contentAlignment = Alignment.Center,
    ) {
        if (state == StepState.COMPLETE) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Complete",
                tint = BackgroundDark,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Text(
                text = ordinal.toString(),
                color = if (state == StepState.ACTIVE) TealAccent else TextTertiary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
    }
}

// ===========================================================================
// Stage 1 body — Unpacking
// ===========================================================================

@Composable
private fun UnpackingBody(fraction: Float) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        label = "unpackProgress",
    )
    HbCard {
        Text(
            text = "Extracting export.xml from the ZIP archive",
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        DeterminateBar(fraction = animated)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${(animated * 100).toInt()}%",
            color = TealAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

/** Flat determinate progress bar (no elevation; teal fill on a divider track). */
@Composable
private fun DeterminateBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(DividerColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(TealAccent),
        )
    }
}

// ===========================================================================
// Stage 2 body — Parsing
// ===========================================================================

@Composable
private fun ParsingBody(recordsScanned: Int, currentType: HealthDataType?) {
    HbCard {
        Text(
            text = formatCount(recordsScanned),
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "records scanned",
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontSize = 12.sp,
        )
        if (currentType != null) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Reading ",
                    color = TextTertiary,
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                )
                Text(
                    text = currentType.displayName,
                    color = TealAccent,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

// ===========================================================================
// Stage 3 body — Deduplication
// ===========================================================================

@Composable
private fun DeduplicationBody(knownRecordCount: Int, rows: List<DeltaRow>) {
    HbCard {
        Text(
            text = "Checking against ${formatCount(knownRecordCount)} known records",
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontSize = 13.sp,
        )
        if (rows.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HbSectionLabel("Delta by type")
            Spacer(Modifier.height(4.dp))
            rows.forEach { row ->
                DedupRow(row)
            }
        }
    }
}

/**
 * One per-type dedup line: type name on the left, "X new / Y already synced"
 * on the right with the new-count tinted to signal whether anything will be
 * written.
 */
@Composable
private fun DedupRow(row: DeltaRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.type.displayName,
                color = TextPrimary,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            Text(
                text = row.dateRange,
                color = TextTertiary,
                fontFamily = FontFamily.Default,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${formatCount(row.newCount)} new",
                color = if (row.newCount > 0) TealAccent else TextTertiary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
            Text(
                text = " / ${formatCount(row.skippedCount)} synced",
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
    }
}

// ===========================================================================
// Helpers + sample data
// ===========================================================================

/** Group-by-thousands formatting for monospace counters (locale-independent). */
private fun formatCount(n: Int): String =
    "%,d".format(n)

/** Sample size of the local fingerprint DB used by the scaffold's stage 3. */
private const val SAMPLE_KNOWN_RECORDS = 12_840

/**
 * Sample deduplication result streamed during stage 3 of the scaffold. The real
 * values come from the fingerprint engine via `SyncEngine`.
 */
private val SAMPLE_DEDUP: DeltaResult = DeltaResult(
    rows = listOf(
        DeltaRow(HealthDataType.STEPS, newCount = 612, skippedCount = 3_508, dateRange = "Jan 2024 — Jun 2024"),
        DeltaRow(HealthDataType.HEART_RATE, newCount = 1_204, skippedCount = 8_676, dateRange = "Jan 2024 — Jun 2024"),
        DeltaRow(HealthDataType.ACTIVE_ENERGY, newCount = 388, skippedCount = 2_252, dateRange = "Jan 2024 — Jun 2024"),
        DeltaRow(HealthDataType.SLEEP, newCount = 41, skippedCount = 269, dateRange = "Feb 2024 — Jun 2024"),
        DeltaRow(HealthDataType.WORKOUT, newCount = 18, skippedCount = 124, dateRange = "Jan 2024 — Jun 2024"),
        DeltaRow(HealthDataType.RESTING_HEART_RATE, newCount = 22, skippedCount = 158, dateRange = "Jan 2024 — Jun 2024"),
        DeltaRow(HealthDataType.VO2_MAX, newCount = 0, skippedCount = 36, dateRange = "Mar 2024 — Jun 2024"),
    ),
    newTotal = 2_285,
    skippedTotal = 15_023,
    scannedTotal = 17_308,
)

// ===========================================================================
// Previews
// ===========================================================================

@Preview(name = "Parsing stage", backgroundColor = 0xFF0A0E1A, showBackground = true)
@Composable
private fun ProcessingParsingPreview() {
    HealthBridgeTheme {
        ProcessingContent(
            ui = ProcessingUiState(
                stage = ProcessingStage.PARSING,
                unpackFraction = 1f,
                recordsScanned = 8_412,
                currentType = HealthDataType.HEART_RATE,
            ),
            onCancel = {},
        )
    }
}

@Preview(name = "Deduplication stage", backgroundColor = 0xFF0A0E1A, showBackground = true)
@Composable
private fun ProcessingDedupPreview() {
    HealthBridgeTheme {
        ProcessingContent(
            ui = ProcessingUiState(
                stage = ProcessingStage.DEDUPLICATION,
                unpackFraction = 1f,
                recordsScanned = SAMPLE_DEDUP.scannedTotal,
                knownRecordCount = SAMPLE_KNOWN_RECORDS,
                dedupRows = SAMPLE_DEDUP.rows,
            ),
            onCancel = {},
        )
    }
}
