package com.healthbridge.ui.delta

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthbridge.parser.DeltaResult
import com.healthbridge.parser.DeltaRow
import com.healthbridge.parser.HealthDataType
import com.healthbridge.parser.WorkoutDetail
import com.healthbridge.ui.components.HbCard
import com.healthbridge.ui.components.HbChip
import com.healthbridge.ui.components.HbChipKind
import com.healthbridge.ui.components.HbPrimaryButton
import com.healthbridge.ui.components.HbScaffold
import com.healthbridge.ui.components.HbSectionLabel
import com.healthbridge.ui.components.HbTextButton
import com.healthbridge.ui.sync.SyncViewModel
import com.healthbridge.ui.theme.BackgroundDark
import com.healthbridge.ui.theme.DividerColor
import com.healthbridge.ui.theme.SurfaceElevated
import com.healthbridge.ui.theme.TealAccent
import com.healthbridge.ui.theme.TextPrimary
import com.healthbridge.ui.theme.TextSecondary
import com.healthbridge.ui.theme.TextTertiary

/**
 * Delta Review — the decision screen.
 *
 * After parsing + fingerprint diffing, this screen tells the user exactly what is
 * about to change before anything is written to Health Connect:
 *
 *  - A headline of the total NEW records that will be written.
 *  - A subheader of the records already on this device that were SKIPPED.
 *  - A per-type breakdown; each row expands to reveal a READ-ONLY list of the
 *    individual records that will be written (workouts show type/date/duration/calories).
 *  - An empty state when the delta is zero ("Nothing new to sync").
 *
 * The docked footer holds the irreversible action ([onWrite]) plus a Cancel.
 *
 * State is hoisted: the caller supplies the already-computed [DeltaResult]. This
 * screen owns only ephemeral UI state (which rows are expanded). Wiring to the real
 * [com.healthbridge.sync.SyncEngine] / fingerprint diff happens at the call site.
 *
 * @param onBack  navigate back to the processing/import flow.
 * @param onWrite proceed to the WritingScreen and begin the Health Connect write.
 */
@Composable
fun DeltaReviewScreen(
    onBack: () -> Unit,
    onWrite: () -> Unit,
    fileUri: String = "",
    // Sample default keeps @Preview working; overridden by the real VM delta when [vm] != null.
    delta: DeltaResult = sampleDeltaResult,
    // Per-type expanded item samples for preview. Real per-record hydration is OPTIONAL for the MVP
    // (buildItemsForType returns empty -> "Item preview unavailable.").
    itemsByType: Map<HealthDataType, List<DeltaItem>> = sampleItemsByType,
    // When non-null, the real engine delta (computed during Processing) is read from the shared VM.
    vm: SyncViewModel? = null,
) {
    // Prefer the VM's computed delta when present; while it's still null (e.g. process death landed
    // straight on Delta), fall back to the provided/sample delta so the screen always renders.
    val vmDelta = vm?.delta?.collectAsState()?.value
    val effectiveDelta = vmDelta ?: delta

    DeltaReviewContent(
        onBack = onBack,
        onWrite = onWrite,
        delta = effectiveDelta,
        itemsByType = itemsByType,
    )
}

/**
 * Stateless content of the delta-review screen, rendered purely from [delta]. Split out so the
 * vm-driven and preview paths share one body.
 */
@Composable
private fun DeltaReviewContent(
    onBack: () -> Unit,
    onWrite: () -> Unit,
    delta: DeltaResult,
    itemsByType: Map<HealthDataType, List<DeltaItem>>,
) {
    val isEmpty = delta.newTotal <= 0

    HbScaffold(
        title = "Review Changes",
        onBack = onBack,
        footer = {
            if (isEmpty) {
                // Empty delta: nothing to write, so the only path forward is "Done".
                HbPrimaryButton(text = "Done", onClick = onWrite)
            } else {
                HbPrimaryButton(
                    text = "Write to Health Connect",
                    onClick = onWrite,
                    leadingIcon = Icons.Filled.CheckCircle,
                )
                Spacer(Modifier.height(8.dp))
                HbTextButton(
                    text = "Cancel",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        if (isEmpty) {
            DeltaEmptyState(skippedTotal = delta.skippedTotal)
        } else {
            DeltaHeader(delta = delta)

            Spacer(Modifier.height(24.dp))
            HbSectionLabel("Breakdown by type")
            Spacer(Modifier.height(8.dp))

            // Only show types that actually contribute new records.
            val visibleRows = delta.rows.filter { it.newCount > 0 }
            visibleRows.forEachIndexed { index, row ->
                DeltaTypeRow(
                    row = row,
                    items = itemsByType[row.type].orEmpty(),
                )
                if (index != visibleRows.lastIndex) {
                    Spacer(Modifier.height(12.dp))
                }
            }

            Spacer(Modifier.height(24.dp))
            DeltaFootnote(scannedTotal = delta.scannedTotal, skippedTotal = delta.skippedTotal)
            // Bottom breathing room so the last card clears the docked footer.
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ===========================================================================
// Header
// ===========================================================================

/**
 * The headline block: "Ready to sync X new records" over the skipped subheader.
 */
@Composable
private fun DeltaHeader(delta: DeltaResult) {
    HbCard(accent = true) {
        Text(
            text = "Ready to sync",
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatCount(delta.newTotal),
                color = TealAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 40.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (delta.newTotal == 1) "new record" else "new records",
                color = TextPrimary,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${formatCount(delta.skippedTotal)} records already on this device — skipped",
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontSize = 13.sp,
        )
    }
}

/**
 * A muted summary line tying the new/skipped counts back to the total scanned.
 */
@Composable
private fun DeltaFootnote(scannedTotal: Int, skippedTotal: Int) {
    Text(
        text = "Scanned ${formatCount(scannedTotal)} records · " +
            "${formatCount(skippedTotal)} duplicates skipped. " +
            "Values & calories are ignored when matching, so edits won't re-sync.",
        color = TextTertiary,
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
    )
}

// ===========================================================================
// Per-type expandable row
// ===========================================================================

/**
 * One expandable breakdown row for a [HealthDataType].
 *
 * Collapsed: type icon + display name + new-count chip + date range + chevron.
 * Expanded: a hairline divider followed by a READ-ONLY list of individual records.
 */
@Composable
private fun DeltaTypeRow(
    row: DeltaRow,
    items: List<DeltaItem>,
) {
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevronRotation",
    )

    HbCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeIcon(row.type)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.type.displayName,
                    color = TextPrimary,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.dateRange,
                    color = TextSecondary,
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            HbChip(text = "+${formatCount(row.newCount)}", kind = HbChipKind.Ok)
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = TextTertiary,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(chevronRotation),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(DividerColor),
                )
                Spacer(Modifier.height(8.dp))

                if (items.isEmpty()) {
                    // TODO: hydrate per-record detail from the parser for this type.
                    Text(
                        text = "Item preview unavailable.",
                        color = TextTertiary,
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    items.forEach { item ->
                        DeltaItemRow(item)
                    }
                    if (items.size < row.newCount) {
                        Text(
                            text = "+ ${formatCount(row.newCount - items.size)} more",
                            color = TextTertiary,
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A single READ-ONLY record row inside an expanded type group.
 *
 * For workouts the [DeltaItem.subtitle] carries the duration/calories summary; the
 * leading line shows the concrete activity type. Non-workout types show a generic
 * timestamp + value summary. Nothing here is interactive — this is review-only.
 */
@Composable
private fun DeltaItemRow(item: DeltaItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = TextPrimary,
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.subtitle,
                    color = TextSecondary,
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.trailing.isNotBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = item.trailing,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}

// ===========================================================================
// Empty state (delta == 0)
// ===========================================================================

@Composable
private fun DeltaEmptyState(skippedTotal: Int) {
    Spacer(Modifier.height(48.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = TealAccent,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Nothing new to sync",
            color = TextPrimary,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (skippedTotal > 0) {
                "All ${formatCount(skippedTotal)} records in this export are already " +
                    "on this device. You're fully up to date."
            } else {
                "This export contained no records of a supported type. " +
                    "Nothing was written to Health Connect."
            },
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontSize = 14.sp,
        )
    }
}

// ===========================================================================
// Type iconography
// ===========================================================================

/**
 * Maps a [HealthDataType] to a Material icon shown in the breakdown row. Uses
 * only the built-in Material icon set (no extra icon dependency).
 */
@Composable
private fun TypeIcon(type: HealthDataType) {
    // Core (material-icons-core) icons only — no extended icon dependency.
    val icon: ImageVector = when (type) {
        HealthDataType.WORKOUT -> Icons.Filled.PlayArrow
        HealthDataType.STEPS -> Icons.Filled.DateRange
        HealthDataType.HEART_RATE -> Icons.Filled.Favorite
        HealthDataType.SLEEP -> Icons.Filled.Refresh
        HealthDataType.ACTIVE_ENERGY -> Icons.Filled.Star
        HealthDataType.RESTING_HEART_RATE -> Icons.Filled.FavoriteBorder
        HealthDataType.VO2_MAX -> Icons.Filled.Refresh
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = type.displayName,
            tint = TealAccent,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ===========================================================================
// UI-only view model for an expanded record row
// ===========================================================================

/**
 * A flattened, presentation-only projection of a single record to be written.
 *
 * This is intentionally a thin UI struct (not the domain model) so the breakdown
 * list never needs to reach back into [com.healthbridge.parser.AppleHealthRecord]
 * or [WorkoutDetail] formatting concerns. The real call site builds these from the
 * parsed records — see [buildItemsForType].
 *
 * @param title    primary line (e.g. "Running", or "9:41 AM").
 * @param subtitle secondary line (e.g. "32 min · 412 kcal" for workouts).
 * @param trailing right-aligned monospace value (e.g. "8,431 steps", "72 bpm").
 */
data class DeltaItem(
    val title: String,
    val subtitle: String = "",
    val trailing: String = "",
)

/**
 * Builds the READ-ONLY [DeltaItem] preview list for a given type.
 *
 * For [HealthDataType.WORKOUT] this should map each [WorkoutDetail] to a row with
 * activity type as the title and "duration · calories" as the subtitle.
 */
@Suppress("UNUSED_PARAMETER")
fun buildItemsForType(type: HealthDataType): List<DeltaItem> {
    // TODO: project real parsed records (AppleHealthRecord / WorkoutDetail) into
    //       DeltaItem rows for this type. Workouts -> title=activityType,
    //       subtitle="<duration> · <calories> kcal".
    return emptyList()
}

// ---------------------------------------------------------------------------
// Number formatting (thousands separators on data values).
// ---------------------------------------------------------------------------
private fun formatCount(n: Int): String = "%,d".format(n)

// ===========================================================================
// Sample data (preview + scaffold default). Replaced by real engine output.
// ===========================================================================

private val sampleDeltaResult = DeltaResult(
    rows = listOf(
        DeltaRow(HealthDataType.WORKOUT, newCount = 18, skippedCount = 142, dateRange = "Jan 2024 — Jun 2024"),
        DeltaRow(HealthDataType.STEPS, newCount = 1240, skippedCount = 8800, dateRange = "Jan 2024 — Jun 2024"),
        DeltaRow(HealthDataType.HEART_RATE, newCount = 9120, skippedCount = 41200, dateRange = "Jan 2024 — Jun 2024"),
        DeltaRow(HealthDataType.SLEEP, newCount = 121, skippedCount = 410, dateRange = "Jan 2024 — Jun 2024"),
        DeltaRow(HealthDataType.ACTIVE_ENERGY, newCount = 2310, skippedCount = 12000, dateRange = "Jan 2024 — Jun 2024"),
        DeltaRow(HealthDataType.RESTING_HEART_RATE, newCount = 88, skippedCount = 320, dateRange = "Jan 2024 — Jun 2024"),
        DeltaRow(HealthDataType.VO2_MAX, newCount = 0, skippedCount = 14, dateRange = "Mar 2024 — Jun 2024"),
    ),
    newTotal = 12_897,
    skippedTotal = 62_886,
    scannedTotal = 75_783,
)

private val sampleItemsByType: Map<HealthDataType, List<DeltaItem>> = mapOf(
    HealthDataType.WORKOUT to listOf(
        DeltaItem(title = "Running", subtitle = "32 min · 412 kcal", trailing = "Jun 08"),
        DeltaItem(title = "Cycling", subtitle = "1 hr 04 min · 720 kcal", trailing = "Jun 07"),
        DeltaItem(title = "Strength Training", subtitle = "45 min · 268 kcal", trailing = "Jun 06"),
        DeltaItem(title = "Yoga", subtitle = "25 min · 96 kcal", trailing = "Jun 05"),
    ),
    HealthDataType.STEPS to listOf(
        DeltaItem(title = "Jun 08, 2024", trailing = "8,431 steps"),
        DeltaItem(title = "Jun 07, 2024", trailing = "11,204 steps"),
        DeltaItem(title = "Jun 06, 2024", trailing = "6,002 steps"),
    ),
    HealthDataType.RESTING_HEART_RATE to listOf(
        DeltaItem(title = "Jun 08, 2024 · 7:14 AM", trailing = "54 bpm"),
        DeltaItem(title = "Jun 07, 2024 · 7:02 AM", trailing = "56 bpm"),
    ),
)

// ===========================================================================
// Previews
// ===========================================================================

@Preview(name = "Delta — Populated", backgroundColor = 0xFF0A0E1A, showBackground = true)
@Composable
private fun DeltaReviewScreenPreview() {
    DeltaReviewScreen(onBack = {}, onWrite = {})
}

@Preview(name = "Delta — Empty", backgroundColor = 0xFF0A0E1A, showBackground = true)
@Composable
private fun DeltaReviewScreenEmptyPreview() {
    DeltaReviewScreen(
        onBack = {},
        onWrite = {},
        delta = DeltaResult(
            rows = emptyList(),
            newTotal = 0,
            skippedTotal = 62_886,
            scannedTotal = 62_886,
        ),
        itemsByType = emptyMap(),
    )
}
