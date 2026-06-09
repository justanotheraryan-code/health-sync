package com.healthbridge.ui.writing

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthbridge.ui.components.HbCard
import com.healthbridge.ui.components.HbChip
import com.healthbridge.ui.components.HbChipKind
import com.healthbridge.ui.components.HbGhostButton
import com.healthbridge.ui.components.HbPrimaryButton
import com.healthbridge.ui.components.HbScaffold
import com.healthbridge.ui.components.HbSectionLabel
import com.healthbridge.ui.theme.BackgroundDark
import com.healthbridge.ui.theme.DividerColor
import com.healthbridge.ui.theme.ErrorRed
import com.healthbridge.ui.theme.SurfaceElevated
import com.healthbridge.ui.theme.TealAccent
import com.healthbridge.ui.theme.TextPrimary
import com.healthbridge.ui.theme.TextSecondary
import com.healthbridge.ui.theme.TextTertiary
import kotlinx.coroutines.delay

/**
 * Writing screen — the terminal step of a sync run.
 *
 * Streams the batched write into Google Health Connect (batches of 500, the HC
 * insert ceiling) and renders three mutually-exclusive phases off a single hoisted
 * [WriteUiState]:
 *
 *  - [WritePhase.Writing]  — determinate progress bar ("Writing N records to Health
 *    Connect…"), a live monospace written-counter, and a "Batch k / m" readout.
 *  - [WritePhase.Success]  — an animated check mark draws in, "Sync complete. N
 *    records added.", and a Done CTA wired to [onDone].
 *  - [WritePhase.Error]    — a partial-failure summary (written vs. failed) with a
 *    "Retry failed batch" action plus Done.
 *
 * The phase progression here is driven by a sample [LaunchedEffect] ticker so the
 * scaffold previews and runs standalone. Real wiring replaces that ticker with the
 * HealthConnectWriter stream (see // TODO markers).
 *
 * @param onDone invoked when the user dismisses a completed (success or error) run.
 */
@Composable
fun WritingScreen(
    onDone: () -> Unit,
    // TODO: write the records for this file via HealthConnectWriter; currently sample-animated.
    fileUri: String = "",
    // Optional secondary action on the success state → Sync History.
    onViewHistory: () -> Unit = {},
) {
    // -----------------------------------------------------------------------
    // Hoisted UI state. A WritingViewModel will own this and collect the
    // HealthConnectWriter progress callbacks into it.
    // -----------------------------------------------------------------------
    var state by remember { mutableStateOf(WriteUiState.startWriting(totalRecords = 1284)) }

    // TODO: replace this sample ticker with the real write stream:
    //
    //   val writer = HealthConnectWriter(client, mapper, fingerprintEngine)
    //   val result = writer.write(newRecords) { written, total ->
    //       state = state.onProgress(written = written, total = total)
    //   }
    //   state = if (writer.hasFailures()) state.toError(...) else state.toSuccess()
    //
    // The block below simulates ~24 batches landing one tick at a time, then drops
    // into the success phase (flip SIMULATE_FAILURE to exercise the error branch).
    LaunchedEffect(Unit) {
        val batchSize = WriteUiState.BATCH_SIZE
        val total = state.totalRecords
        val totalBatches = ceilDiv(total, batchSize)

        var written = 0
        for (batch in 1..totalBatches) {
            delay(140)
            written = minOf(written + batchSize, total)
            state = state.onProgress(written = written, currentBatch = batch, totalBatches = totalBatches)
        }

        delay(160)
        @Suppress("KotlinConstantConditions")
        state = if (SIMULATE_FAILURE) {
            // One batch (500) failed its insert; the rest are durable.
            state.toError(written = total - batchSize, failed = batchSize, failedBatches = 1)
        } else {
            state.toSuccess(written = total)
        }
    }

    when (state.phase) {
        WritePhase.Writing -> WritingInProgress(state = state)
        WritePhase.Success -> WritingSuccess(written = state.written, onDone = onDone)
        WritePhase.Error -> WritingError(
            written = state.written,
            failed = state.failed,
            failedBatches = state.failedBatches,
            onRetry = {
                // TODO: writer.retryFailed { written, total -> state = state.onProgress(...) }
                //  then resolve to Success/Error from the returned WriteResult.
                state = WriteUiState.startWriting(totalRecords = state.failed)
            },
            onDone = onDone,
        )
    }
}

/* ----------------------------------------------------------------------------------------------- */
/* Phase: WRITING                                                                                   */
/* ----------------------------------------------------------------------------------------------- */

@Composable
private fun WritingInProgress(state: WriteUiState) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.fraction,
        animationSpec = tween(durationMillis = 200, easing = LinearEasing),
        label = "writeProgress",
    )

    HbScaffold(title = "Writing to Health Connect") {
        Spacer(Modifier.height(24.dp))

        Text(
            text = "Writing ${state.totalRecords.grouped()} records to Health Connect…",
            color = TextPrimary,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Keep HealthBridge open. New records are committed in batches; nothing " +
                "is re-written if you have synced this export before.",
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontSize = 14.sp,
        )

        Spacer(Modifier.height(32.dp))

        HbCard(accent = true) {
            // Live written-counter (monospace data value).
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.written.grouped(),
                    color = TealAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 40.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "/ ${state.totalRecords.grouped()}",
                    color = TextTertiary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "RECORDS WRITTEN",
                color = TextSecondary,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(16.dp))
            ProgressBar(fraction = animatedProgress)

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Batch ${state.currentBatch} / ${state.totalBatches}",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                Text(
                    text = "${(state.fraction * 100).toInt()}%",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        HbSectionLabel(text = "Status")
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HbChip(text = "WRITING", kind = HbChipKind.Ok)
            Text(
                text = "${WriteUiState.BATCH_SIZE} records per batch",
                color = TextTertiary,
                fontFamily = FontFamily.Default,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Determinate progress bar. Flat track + teal fill, rounded ends, no elevation.
 */
@Composable
private fun ProgressBar(fraction: Float) {
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

/* ----------------------------------------------------------------------------------------------- */
/* Phase: SUCCESS                                                                                   */
/* ----------------------------------------------------------------------------------------------- */

@Composable
private fun WritingSuccess(written: Int, onDone: () -> Unit) {
    HbScaffold(
        title = null,
        footer = {
            HbPrimaryButton(
                text = "Done",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            AnimatedCheck()

            Text(
                text = "Sync complete.",
                color = TextPrimary,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
            )

            HbCard(accent = true, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = written.grouped(),
                        color = TealAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 40.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "records added",
                        color = TextSecondary,
                        fontFamily = FontFamily.Default,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            Text(
                text = "Your new Apple Health records are now in Google Health Connect. " +
                    "Re-importing the same export will skip everything you just wrote.",
                color = TextSecondary,
                fontFamily = FontFamily.Default,
                fontSize = 14.sp,
            )
        }
    }
}

/**
 * A check mark that strokes itself in once on mount. Two line segments drawn over an
 * animatable [0f, 1f] progress, plus a fading-in teal ring behind it. Transform / alpha
 * only — no shadows, in keeping with the flat surface language.
 */
@Composable
private fun AnimatedCheck() {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 420, easing = LinearEasing),
        )
    }

    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(TealAccent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(48.dp)) {
            val w = size.width
            val h = size.height
            // Check geometry as fractions of the canvas box.
            val start = Offset(0.16f * w, 0.54f * h)
            val mid = Offset(0.42f * w, 0.78f * h)
            val end = Offset(0.84f * w, 0.26f * h)

            val p = progress.value
            val strokeWidth = 4.dp.toPx()

            // First segment (start -> mid) draws over the first half of progress,
            // the second (mid -> end) over the back half.
            val firstT = (p / 0.4f).coerceIn(0f, 1f)
            val firstEnd = lerp(start, mid, firstT)
            drawLine(
                color = TealAccent,
                start = start,
                end = firstEnd,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )

            if (p > 0.4f) {
                val secondT = ((p - 0.4f) / 0.6f).coerceIn(0f, 1f)
                val secondEnd = lerp(mid, end, secondT)
                drawLine(
                    color = TealAccent,
                    start = mid,
                    end = secondEnd,
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/* ----------------------------------------------------------------------------------------------- */
/* Phase: ERROR (partial failure + retry)                                                          */
/* ----------------------------------------------------------------------------------------------- */

@Composable
private fun WritingError(
    written: Int,
    failed: Int,
    failedBatches: Int,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    HbScaffold(
        title = null,
        footer = {
            HbPrimaryButton(
                text = "Retry failed batch",
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            HbGhostButton(
                text = "Done",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(ErrorRed.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = ErrorRed,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column {
                    Text(
                        text = "Sync incomplete",
                        color = TextPrimary,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                    )
                    Text(
                        text = "$failedBatches batch(es) failed to write.",
                        color = TextSecondary,
                        fontFamily = FontFamily.Default,
                        fontSize = 14.sp,
                    )
                }
            }

            Text(
                text = "The records that were written are durable — only the failed batch is " +
                    "queued for retry. Nothing is duplicated: fingerprints are committed only " +
                    "after a batch lands.",
                color = TextSecondary,
                fontFamily = FontFamily.Default,
                fontSize = 14.sp,
            )

            HbCard {
                ResultStat(
                    label = "Written",
                    value = written.grouped(),
                    valueColor = TealAccent,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(DividerColor),
                )
                Spacer(Modifier.height(12.dp))
                ResultStat(
                    label = "Failed",
                    value = failed.grouped(),
                    valueColor = ErrorRed,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Retry re-attempts only the ${WriteUiState.BATCH_SIZE}-record batch that failed.",
                    color = TextTertiary,
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun ResultStat(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
        )
        Text(
            text = value,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
        )
    }
}

/* ----------------------------------------------------------------------------------------------- */
/* UI state model                                                                                   */
/* ----------------------------------------------------------------------------------------------- */

/** The phase a [WritingScreen] is currently rendering. */
sealed interface WritePhase {
    data object Writing : WritePhase
    data object Success : WritePhase
    data object Error : WritePhase
}

/**
 * Immutable snapshot of the write run, hoisted out of [WritingScreen]. A
 * WritingViewModel will produce these from the HealthConnectWriter progress
 * callbacks; the screen renders purely off this value.
 *
 * @param totalRecords  total NEW records selected for this write.
 * @param written       records confirmed written so far.
 * @param failed        records that could not be written (error phase only).
 * @param failedBatches count of batches queued for retry (error phase only).
 * @param currentBatch  1-based index of the batch currently being written.
 * @param totalBatches  total number of batches for this run.
 * @param phase         which [WritePhase] to render.
 */
data class WriteUiState(
    val totalRecords: Int,
    val written: Int,
    val failed: Int,
    val failedBatches: Int,
    val currentBatch: Int,
    val totalBatches: Int,
    val phase: WritePhase,
) {
    /** Fraction [0f, 1f] of records written, used to drive the progress bar. */
    val fraction: Float
        get() = if (totalRecords <= 0) 0f else (written.toFloat() / totalRecords).coerceIn(0f, 1f)

    /** Advance the live counters after a batch lands. */
    fun onProgress(written: Int, currentBatch: Int, totalBatches: Int): WriteUiState =
        copy(
            written = written.coerceAtMost(totalRecords),
            currentBatch = currentBatch,
            totalBatches = totalBatches,
            phase = WritePhase.Writing,
        )

    /** Settle into the success terminal state. */
    fun toSuccess(written: Int): WriteUiState =
        copy(written = written, failed = 0, failedBatches = 0, phase = WritePhase.Success)

    /** Settle into the partial-failure terminal state. */
    fun toError(written: Int, failed: Int, failedBatches: Int): WriteUiState =
        copy(
            written = written,
            failed = failed,
            failedBatches = failedBatches,
            phase = WritePhase.Error,
        )

    companion object {
        /** Health Connect rejects single inserts larger than 500 records. */
        const val BATCH_SIZE: Int = 500

        /** Fresh "writing" state for [totalRecords], counters zeroed. */
        fun startWriting(totalRecords: Int): WriteUiState =
            WriteUiState(
                totalRecords = totalRecords,
                written = 0,
                failed = 0,
                failedBatches = 0,
                currentBatch = if (totalRecords > 0) 1 else 0,
                totalBatches = ceilDiv(totalRecords, BATCH_SIZE),
                phase = WritePhase.Writing,
            )
    }
}

/* ----------------------------------------------------------------------------------------------- */
/* Local helpers                                                                                    */
/* ----------------------------------------------------------------------------------------------- */

/**
 * Sample driver toggle. Flip to true to preview/exercise the partial-failure
 * (error + retry) branch instead of the success path. Real builds derive this
 * from the HealthConnectWriter result, not a constant.
 */
private const val SIMULATE_FAILURE: Boolean = false

/** Ceiling integer division, guarding the divide-by-zero / empty-set case. */
private fun ceilDiv(value: Int, divisor: Int): Int =
    if (value <= 0 || divisor <= 0) 0 else (value + divisor - 1) / divisor

/** Thousands-grouped string for monospace data readouts (e.g. 1284 -> "1,284"). */
private fun Int.grouped(): String = "%,d".format(this)

/** Linear interpolation between two [Offset]s. */
private fun lerp(a: Offset, b: Offset, t: Float): Offset =
    Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/* ----------------------------------------------------------------------------------------------- */
/* Previews                                                                                         */
/* ----------------------------------------------------------------------------------------------- */

@Preview(name = "Writing — in progress", showBackground = true, backgroundColor = 0xFF0A0E1A)
@Composable
private fun WritingInProgressPreview() {
    WritingInProgress(
        state = WriteUiState(
            totalRecords = 1284,
            written = 1000,
            failed = 0,
            failedBatches = 0,
            currentBatch = 2,
            totalBatches = 3,
            phase = WritePhase.Writing,
        ),
    )
}

@Preview(name = "Writing — success", showBackground = true, backgroundColor = 0xFF0A0E1A)
@Composable
private fun WritingSuccessPreview() {
    WritingSuccess(written = 1284, onDone = {})
}

@Preview(name = "Writing — error", showBackground = true, backgroundColor = 0xFF0A0E1A)
@Composable
private fun WritingErrorPreview() {
    WritingError(
        written = 784,
        failed = 500,
        failedBatches = 1,
        onRetry = {},
        onDone = {},
    )
}
