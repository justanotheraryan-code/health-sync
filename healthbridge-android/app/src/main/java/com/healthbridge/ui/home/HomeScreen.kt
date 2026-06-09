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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * State is hoisted via [HomeUiState] (sample values for now). Real values will be
 * supplied by a HomeViewModel reading from the Room sync log + fingerprint DB.
 */

// ===========================================================================
// UI State
// ===========================================================================

/**
 * Immutable view state for [HomeScreen].
 *
 * @param hasSynced            whether at least one sync has ever completed. When
 *                             false, the home surface renders the empty state.
 * @param lastSyncDate         human-readable date of the most recent sync
 *                             (e.g. "Jun 8, 2026, 14:32"). Null until first sync.
 * @param lastSyncNewRecords   number of NEW records written in the most recent sync.
 * @param workoutsWritten      lifetime count of workout sessions written to Health Connect.
 * @param stepsWritten         lifetime count of steps records written.
 * @param sleepWritten         lifetime count of sleep sessions written.
 */
data class HomeUiState(
    val hasSynced: Boolean = true,
    val lastSyncDate: String? = "Jun 8, 2026 · 14:32",
    val lastSyncNewRecords: Int = 1_284,
    val workoutsWritten: Int = 312,
    val stepsWritten: Int = 9_431,
    val sleepWritten: Int = 188,
) {
    companion object {
        /** Sample post-sync state for previews and scaffolding. */
        val Sample = HomeUiState()

        /** Sample "never synced yet" state. */
        val Empty = HomeUiState(
            hasSynced = false,
            lastSyncDate = null,
            lastSyncNewRecords = 0,
            workoutsWritten = 0,
            stepsWritten = 0,
            sleepWritten = 0,
        )
    }
}

// ===========================================================================
// Screen
// ===========================================================================

@Composable
fun HomeScreen(
    onImport: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    // TODO: replace with HomeViewModel state collected via collectAsStateWithLifecycle().
    //  The VM should read the latest SyncLogEntity for the banner + lifetime
    //  per-type counts (aggregated from recordsWritten JSON) for the metrics row.
    val state = HomeUiState.Sample

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
        if (state.hasSynced) {
            LastSyncBanner(
                lastSyncDate = state.lastSyncDate.orEmpty(),
                newRecords = state.lastSyncNewRecords,
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
                    HbMetric(value = formatCount(state.workoutsWritten), label = "WORKOUTS")
                    HbMetric(value = formatCount(state.stepsWritten), label = "STEPS")
                    HbMetric(value = formatCount(state.sleepWritten), label = "SLEEP")
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
                subtitle = if (state.hasSynced) "View past imports & write logs" else "Nothing here yet",
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
 * "Last synced: <date> — N new records written".
 */
@Composable
private fun LastSyncBanner(
    lastSyncDate: String,
    newRecords: Int,
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
    // NOTE: HomeScreen currently uses HomeUiState.Sample internally; this preview
    //  renders the synced layout. Wire HomeUiState.Empty through a param/VM to
    //  preview the empty state once the ViewModel exists.
    Box(modifier = Modifier.background(BackgroundDark)) {
        HomeScreen(onImport = {}, onHistory = {}, onSettings = {})
    }
}
