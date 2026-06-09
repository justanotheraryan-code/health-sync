package com.healthbridge.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthbridge.parser.HealthDataType
import com.healthbridge.parser.SyncSession
import com.healthbridge.ui.components.HbCard
import com.healthbridge.ui.components.HbChip
import com.healthbridge.ui.components.HbChipKind
import com.healthbridge.ui.components.HbScaffold
import com.healthbridge.ui.components.HbSectionLabel
import com.healthbridge.ui.theme.DividerColor
import com.healthbridge.ui.theme.TealAccent
import com.healthbridge.ui.theme.TextPrimary
import com.healthbridge.ui.theme.TextSecondary
import com.healthbridge.ui.theme.TextTertiary
import com.healthbridge.ui.theme.Dimens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Sync History screen.
 *
 * Renders every persisted [SyncSession] in reverse-chronological order. Each
 * collapsed row shows the run's date/time, the total number of records written,
 * and the source filename. Tapping a row expands it to reveal the full
 * per-type breakdown, the skipped count, the run duration and the terminal
 * status.
 *
 * Per the PRD this screen is read-only — there is intentionally NO delete
 * affordance. When no syncs exist yet, an empty state is shown instead.
 *
 * Real data will be sourced from the sync log via [com.healthbridge.sync.SyncLogger]
 * / the Room `sync_log` table; for now a sample list drives the layout.
 */
@Composable
fun SyncHistoryScreen(onBack: () -> Unit) {
    // TODO: replace with sessions collected from SyncLogger / SyncLogDao.getAll(),
    //       hoisted via a ViewModel and observed as State. Sort newest-first.
    val sessions = remember { sampleSyncSessions() }

    HbScaffold(
        title = "Sync History",
        onBack = onBack,
        action = {
            if (sessions.isNotEmpty()) {
                HbChip(text = "${sessions.size} RUNS", kind = HbChipKind.Neutral)
            }
        },
    ) {
        if (sessions.isEmpty()) {
            EmptyHistoryState()
        } else {
            HistoryList(sessions = sessions)
        }
    }
}

// ===========================================================================
// List
// ===========================================================================

@Composable
private fun HistoryList(sessions: List<SyncSession>) {
    // Defensive: present newest-first regardless of incoming order.
    val ordered = remember(sessions) { sessions.sortedByDescending { it.timestamp } }

    Spacer(Modifier.height(Dimens.s2))
    HbSectionLabel("Past Syncs")
    Spacer(Modifier.height(Dimens.s2))

    ordered.forEachIndexed { index, session ->
        SyncSessionRow(session = session)
        if (index != ordered.lastIndex) {
            Spacer(Modifier.height(Dimens.s3))
        }
    }

    Spacer(Modifier.height(Dimens.s5))
}

// ===========================================================================
// Row (collapsed header + expandable detail)
// ===========================================================================

@Composable
private fun SyncSessionRow(session: SyncSession) {
    var expanded by remember { mutableStateOf(false) }

    val totalWritten = remember(session) { session.recordsWritten.values.sum() }
    val statusKind = remember(session.status) { session.status.toChipKind() }

    HbCard {
        // --- Collapsed header (always visible, toggles expansion) ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatTimestamp(session.timestamp),
                    color = TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = session.sourceFilename,
                    color = TextSecondary,
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(Dimens.s3))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatCount(totalWritten),
                    color = TealAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    maxLines = 1,
                )
                Text(
                    text = "written",
                    color = TextTertiary,
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.width(Dimens.s2))

            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // --- Expanded detail ------------------------------------------------
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(Dimens.s3))
                Divider()
                Spacer(Modifier.height(Dimens.s3))

                // Per-type breakdown.
                HbSectionLabel("Records Written")
                Spacer(Modifier.height(Dimens.s1))
                if (session.recordsWritten.isEmpty()) {
                    DetailLine(label = "No records written this run", value = "—")
                } else {
                    session.recordsWritten.forEach { (type, count) ->
                        DetailLine(
                            label = displayNameForType(type),
                            value = formatCount(count),
                            valueColor = TealAccent,
                        )
                    }
                }

                Spacer(Modifier.height(Dimens.s3))
                Divider()
                Spacer(Modifier.height(Dimens.s3))

                // Run-level metadata.
                DetailLine(label = "Skipped (duplicates)", value = formatCount(session.recordsSkipped))
                DetailLine(label = "Duration", value = formatDuration(session.durationMs))

                Spacer(Modifier.height(Dimens.s3))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Status",
                        color = TextSecondary,
                        fontFamily = FontFamily.Default,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    HbChip(text = session.status.uppercase(Locale.US), kind = statusKind)
                }
            }
        }
    }
}

/**
 * A single label/value line used inside the expanded detail. Label on the
 * left (body type), value right-aligned in monospace (data type per PRD).
 */
@Composable
private fun DetailLine(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Dimens.s3))
        Text(
            text = value,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.dividerThickness)
            .background(DividerColor),
    )
}

// ===========================================================================
// Empty state
// ===========================================================================

@Composable
private fun EmptyHistoryState() {
    Spacer(Modifier.height(Dimens.s9))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(
                    color = TealAccent.copy(alpha = 0.10f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(Dimens.cardRadius),
                ),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = TealAccent,
                modifier = Modifier.size(30.dp),
            )
        }

        Spacer(Modifier.height(Dimens.s4))
        Text(
            text = "No syncs yet",
            color = TextPrimary,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(Dimens.s2))
        Text(
            text = "Once you import an Apple Health export and write to Health Connect, each run will appear here.",
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontSize = 14.sp,
        )
    }
}

// ===========================================================================
// Formatting helpers
// ===========================================================================

private val DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", Locale.US)

/** Epoch-millis -> "Jun 8, 2026 · 14:32" in the device's default zone. */
private fun formatTimestamp(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DATE_TIME_FORMATTER)

/** Group-separated count, e.g. 12842 -> "12,842". */
private fun formatCount(count: Int): String = "%,d".format(Locale.US, count)

/** Human-readable duration, e.g. 4200L -> "4.2s", 92000L -> "1m 32s". */
private fun formatDuration(durationMs: Long): String {
    if (durationMs < 0) return "—"
    val totalSeconds = durationMs / 1000.0
    return if (totalSeconds < 60) {
        "%.1fs".format(Locale.US, totalSeconds)
    } else {
        val minutes = (durationMs / 60_000)
        val seconds = (durationMs % 60_000) / 1000
        "${minutes}m ${seconds}s"
    }
}

/**
 * Resolve a stored record-type key (the [HealthDataType] enum name as persisted
 * in [SyncSession.recordsWritten]) to its human-friendly display name. Falls
 * back to a title-cased version of the raw key for forward compatibility.
 */
private fun displayNameForType(key: String): String =
    runCatching { HealthDataType.valueOf(key).displayName }
        .getOrElse {
            key.split('_').joinToString(" ") { part ->
                part.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
            }
        }

/** Map a terminal status string to the appropriate chip color kind. */
private fun String.toChipKind(): HbChipKind = when (uppercase(Locale.US)) {
    "SUCCESS", "COMPLETE", "OK" -> HbChipKind.Ok
    "PARTIAL", "RETRYING", "WARN" -> HbChipKind.Warn
    "FAILED", "ERROR", "CANCELLED" -> HbChipKind.Error
    else -> HbChipKind.Neutral
}

// ===========================================================================
// Sample data (preview + scaffold). Remove once wired to real sync log.
// ===========================================================================

private fun sampleSyncSessions(): List<SyncSession> {
    val now = System.currentTimeMillis()
    val day = 24L * 60 * 60 * 1000

    return listOf(
        SyncSession(
            timestamp = now - (1 * day) - (3_600_000L),
            sourceFilename = "export_2026-06-08.zip",
            durationMs = 8_400L,
            recordsWritten = linkedMapOf(
                HealthDataType.STEPS.name to 9_431,
                HealthDataType.HEART_RATE.name to 2_104,
                HealthDataType.WORKOUT.name to 12,
                HealthDataType.SLEEP.name to 7,
            ),
            recordsSkipped = 1_842,
            status = "SUCCESS",
        ),
        SyncSession(
            timestamp = now - (5 * day),
            sourceFilename = "export_2026-06-04.zip",
            durationMs = 92_000L,
            recordsWritten = linkedMapOf(
                HealthDataType.STEPS.name to 14_002,
                HealthDataType.ACTIVE_ENERGY.name to 6_318,
                HealthDataType.RESTING_HEART_RATE.name to 30,
                HealthDataType.VO2_MAX.name to 4,
            ),
            recordsSkipped = 512,
            status = "PARTIAL",
        ),
        SyncSession(
            timestamp = now - (18 * day),
            sourceFilename = "apple_health_export.zip",
            durationMs = 3_100L,
            recordsWritten = emptyMap(),
            recordsSkipped = 0,
            status = "FAILED",
        ),
    )
}

// ===========================================================================
// Previews
// ===========================================================================

@Preview(name = "Sync History — populated", backgroundColor = 0xFF0A0E1A, showBackground = true)
@Composable
private fun SyncHistoryScreenPreview() {
    SyncHistoryScreen(onBack = {})
}

@Preview(name = "Sync History — empty", backgroundColor = 0xFF0A0E1A, showBackground = true)
@Composable
private fun SyncHistoryEmptyPreview() {
    HbScaffold(title = "Sync History", onBack = {}) {
        EmptyHistoryState()
    }
}
