package com.healthbridge.ui.home

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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthbridge.parser.HealthDataType
import com.healthbridge.parser.SyncSession
import com.healthbridge.ui.components.HbCard
import com.healthbridge.ui.components.HbChip
import com.healthbridge.ui.components.HbChipKind
import com.healthbridge.ui.components.HbDataRow
import com.healthbridge.ui.components.HbMetric
import com.healthbridge.ui.components.HbPrimaryButton
import com.healthbridge.ui.components.HbScaffold
import com.healthbridge.ui.components.HbSectionLabel
import com.healthbridge.ui.theme.BackgroundDark
import com.healthbridge.ui.theme.SurfaceElevated
import com.healthbridge.ui.theme.TealAccent
import com.healthbridge.ui.theme.TextPrimary
import com.healthbridge.ui.theme.TextSecondary
import com.healthbridge.ui.theme.TextTertiary

/**
 * Home / dashboard screen — the app's landing surface after onboarding.
 *
 * Surfaces the most recent sync at a glance (an accent banner or a never-synced
 * empty state), a compact post-sync summary of the headline record types, a
 * tappable route into the full sync history, and a docked primary CTA that kicks
 * off a fresh Apple Health import.
 *
 * Real state is supplied by [HomeViewModel] (see HomeViewModel.kt, which owns the
 * authoritative [HomeUiState] reading from the Room sync log + fingerprint ledger).
 * When [HomeScreen] is invoked without a VM (e.g. @Preview), it falls back to the
 * inline [SampleHome] state so the layout still renders.
 */

// ===========================================================================
// Sample fallback (preview / no-VM)
// ===========================================================================

/**
 * Sample [HomeUiState] used when no [HomeViewModel] is supplied (previews / scaffolding).
 * Shapes a representative post-sync state: a recent session plus lifetime per-type counts.
 */
private val SampleHome = HomeUiState(
    lastSync = SyncSession(
        timestamp = 1_749_393_120_000L, // Jun 8, 2026 · 14:32 (approx, device-local)
        sourceFilename = "export.zip",
        durationMs = 42_000L,
        recordsWritten = mapOf(
            HealthDataType.WORKOUT.name to 312,
            HealthDataType.STEPS.name to 9_431,
            HealthDataType.SLEEP.name to 188,
        ),
        recordsSkipped = 0,
        status = "SUCCESS",
    ),
    countsByType = mapOf(
        HealthDataType.WORKOUT to 312,
        HealthDataType.STEPS to 9_431,
        HealthDataType.SLEEP to 188,
    ),
    totalSynced = 312 + 9_431 + 188,
)

// ===========================================================================
// Screen
// ===========================================================================

@Composable
fun HomeScreen(
    onImport: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    vm: HomeViewModel? = null,
) {
    // Real state from the VM (sync log + fingerprint counts) when present; sample fallback for
    // previews / no-VM. Mirrors the optional-vm pattern DeltaReviewScreen uses.
    val state: HomeUiState = vm?.uiState?.collectAsState()?.value ?: SampleHome

    // Derive the screen's render off the authoritative VM shape.
    val lastSync = state.lastSync
    val hasSynced = lastSync != null
    val lastSyncDate = lastSync?.let { formatSyncTimestamp(it.timestamp) }.orEmpty()
    val lastSyncNewRecords = lastSync?.recordsWritten?.values?.sum() ?: 0
    val workoutsWritten = state.countsByType[HealthDataType.WORKOUT] ?: 0
    val stepsWritten = state.countsByType[HealthDataType.STEPS] ?: 0
    val sleepWritten = state.countsByType[HealthDataType.SLEEP] ?: 0

    HbScaffold(
        title = "HealthBridge",
        action = {
            SettingsAction(onClick = onSettings)
        },
        footer = {
            HbPrimaryButton(
                text = "Import Apple Health Export",
                onClick = onImport,
            )
        },
    ) {
        Spacer(Modifier.height(8.dp))

        // --- Last-sync banner OR never-synced empty state --------------------
        if (hasSynced) {
            LastSyncBanner(
                lastSyncDate = lastSyncDate,
                newRecords = lastSyncNewRecords,
                sourceFilename = lastSync?.sourceFilename.orEmpty(),
            )

            Spacer(Modifier.height(24.dp))

            // --- Post-sync summary metrics row -------------------------------
            HbSectionLabel("Written to Health Connect")
            Spacer(Modifier.height(8.dp))
            HbCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    HbMetric(value = formatCount(workoutsWritten), label = "WORKOUTS")
                    HbMetric(value = formatCount(stepsWritten), label = "STEPS")
                    HbMetric(value = formatCount(sleepWritten), label = "SLEEP")
                }
            }
        } else {
            NeverSyncedEmptyState()
        }

        Spacer(Modifier.height(24.dp))

        // --- Sync history entry point ---------------------------------------
        HbSectionLabel("Activity")
        Spacer(Modifier.height(8.dp))
        HbCard {
            HbDataRow(
                title = "Sync history",
                subtitle = if (hasSynced) "View past imports & write logs" else "Nothing here yet",
                trailing = "›",
                onClick = onHistory,
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ===========================================================================
// Pieces
// ===========================================================================

/**
 * Top-bar trailing action: a settings cog routing to the settings screen.
 * Uses a Material3 rounded icon (no extra icon dependency).
 */
@Composable
private fun SettingsAction(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Settings,
            contentDescription = "Settings",
            tint = TextSecondary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Accent banner summarizing the most recent successful sync:
 * "Last synced: <date> — N new records written", plus the source filename.
 */
@Composable
private fun LastSyncBanner(
    lastSyncDate: String,
    newRecords: Int,
    sourceFilename: String,
) {
    HbCard(accent = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Last synced",
                    color = TextSecondary,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = lastSyncDate,
                    color = TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${formatCount(newRecords)} new records written",
                    color = TealAccent,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                )
                if (sourceFilename.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "from $sourceFilename",
                        color = TextTertiary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            HbChip(text = "SYNCED", kind = HbChipKind.Ok)
        }
    }
}

/**
 * Empty state shown when no sync has ever completed. Nudges the user toward the
 * docked import CTA without duplicating it.
 */
@Composable
private fun NeverSyncedEmptyState() {
    HbCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "No data synced yet",
                color = TextPrimary,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Import an Apple Health export to write your history to " +
                    "Google Health Connect. Everything stays on-device.",
                color = TextSecondary,
                fontFamily = FontFamily.Default,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(SurfaceElevated),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Tap “Import Apple Health Export” below to get started.",
                color = TextTertiary,
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
            )
        }
    }
}

// ===========================================================================
// Helpers
// ===========================================================================

/** Group-separated formatting for count display (e.g. 9431 -> "9,431"). */
private fun formatCount(value: Int): String {
    // TODO: localize grouping separator via NumberFormat for the active locale.
    return "%,d".format(value)
}

/**
 * Format a sync timestamp (epoch millis) for the last-sync banner, e.g. "Jun 8, 2026 · 14:32".
 * Rendered in the device's default locale + time zone.
 */
private fun formatSyncTimestamp(epochMillis: Long): String {
    // TODO: respect a user 12h/24h preference; SimpleDateFormat uses 24h here for compactness.
    val formatter = java.text.SimpleDateFormat("MMM d, yyyy · HH:mm", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(epochMillis))
}

// ===========================================================================
// Previews
// ===========================================================================

@Preview(name = "Home · Synced", backgroundColor = 0xFF0A0E1A, showBackground = true)
@Composable
private fun HomeScreenSyncedPreview() {
    Box(modifier = Modifier.background(BackgroundDark)) {
        HomeScreen(onImport = {}, onHistory = {}, onSettings = {})
    }
}

@Preview(name = "Home · Empty", backgroundColor = 0xFF0A0E1A, showBackground = true)
@Composable
private fun HomeScreenEmptyPreview() {
    // NOTE: with vm == null, HomeScreen falls back to SampleHome (a synced state), so this
    //  preview renders the synced layout. The real never-synced empty state appears at runtime
    //  when the VM emits HomeUiState(lastSync = null) on a fresh install.
    Box(modifier = Modifier.background(BackgroundDark)) {
        HomeScreen(onImport = {}, onHistory = {}, onSettings = {})
    }
}
