package com.healthbridge.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthbridge.parser.HealthDataType
import com.healthbridge.ui.components.HbCard
import com.healthbridge.ui.components.HbChip
import com.healthbridge.ui.components.HbChipKind
import com.healthbridge.ui.components.HbGhostButton
import com.healthbridge.ui.components.HbScaffold
import com.healthbridge.ui.components.HbSectionLabel
import com.healthbridge.ui.theme.BackgroundDark
import com.healthbridge.ui.theme.DividerColor
import com.healthbridge.ui.theme.ErrorRed
import com.healthbridge.ui.theme.SurfaceDark
import com.healthbridge.ui.theme.SurfaceElevated
import com.healthbridge.ui.theme.TealAccent
import com.healthbridge.ui.theme.TextPrimary
import com.healthbridge.ui.theme.TextSecondary
import com.healthbridge.ui.theme.TextTertiary

/**
 * Settings screen — the app's configuration + maintenance surface.
 *
 * Four sections, top to bottom:
 *  1. Health Connect Permissions — a per-type granted/denied row plus a
 *     "Re-trigger permission grant" action that re-launches the consent flow.
 *  2. Supported Data Types — a [Switch] per [HealthDataType] (all on by default)
 *     letting the user opt a type out of future syncs; the HC record class name
 *     is shown as the row subtitle.
 *  3. Fingerprint Database — record count + on-disk size, and a destructive
 *     "Reset sync history" that wipes the local fingerprint/sync DB behind a
 *     confirmation dialog (does NOT touch Health Connect data).
 *  4. About — version, the on-device privacy posture, and an open-source note.
 *
 * State (per-type permission grants, per-type sync toggles, DB stats) is hoisted
 * into [SettingsUiState] with sample values for scaffolding. A SettingsViewModel
 * will later supply real values from [com.healthbridge.healthconnect.HealthConnectManager]
 * (permissions) and the Room fingerprint DB (counts/size), and persist toggle changes.
 */

// ===========================================================================
// UI State
// ===========================================================================

/**
 * Immutable view state for [SettingsScreen].
 *
 * @param permissionGranted per-[HealthDataType] Health Connect write-permission grant state.
 *                          Mirrors HealthConnectManager.permissionStatusByType().
 * @param typeEnabled       per-[HealthDataType] "sync this type" toggle (default all true).
 * @param fingerprintCount  number of fingerprint rows currently stored locally.
 * @param storageBytes      approximate on-disk size of the fingerprint/sync DB, in bytes.
 * @param appVersion        the user-facing version string.
 */
data class SettingsUiState(
    val permissionGranted: Map<HealthDataType, Boolean>,
    val typeEnabled: Map<HealthDataType, Boolean>,
    val fingerprintCount: Int,
    val storageBytes: Long,
    val appVersion: String = "1.0.0",
) {
    companion object {
        /** Sample populated state for previews and scaffolding. */
        val Sample = SettingsUiState(
            permissionGranted = HealthDataType.entries.associateWith { type ->
                // Sample: VO2 max not yet granted, everything else granted.
                type != HealthDataType.VO2_MAX
            },
            typeEnabled = HealthDataType.entries.associateWith { true },
            fingerprintCount = 18_472,
            storageBytes = 2_310_000L,
        )
    }
}

// ===========================================================================
// Screen
// ===========================================================================

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    // TODO: replace with SettingsViewModel state collected via collectAsStateWithLifecycle().
    //  permissionGranted <- HealthConnectManager.permissionStatusByType()
    //  fingerprintCount  <- FingerprintDao.count()
    //  storageBytes      <- SyncLogDao.totalSize() / db file length
    val state = SettingsUiState.Sample

    // --- Hoisted, locally-mutable toggle state -----------------------------
    // TODO: persist toggle changes (DataStore) and feed them back into the sync
    //  engine's type filter; for now they live only in this composition.
    val typeEnabled = remember {
        mutableStateMapOf<HealthDataType, Boolean>().apply { putAll(state.typeEnabled) }
    }

    // --- Destructive-reset confirmation dialog visibility ------------------
    var showResetDialog by remember { mutableStateOf(false) }

    HbScaffold(
        title = "Settings",
        onBack = onBack,
    ) {
        Spacer(Modifier.height(8.dp))

        // --- 1. Health Connect permissions ----------------------------------
        HbSectionLabel("Health Connect Permissions")
        Spacer(Modifier.height(8.dp))
        HbCard {
            HealthDataType.entries.forEachIndexed { index, type ->
                if (index > 0) RowDivider()
                PermissionRow(
                    type = type,
                    granted = state.permissionGranted[type] ?: false,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HbGhostButton(
            text = "Re-trigger permission grant",
            onClick = {
                // TODO: launch HealthConnectManager.permissionsLauncherContract() with
                //  REQUIRED_PERMISSIONS via rememberLauncherForActivityResult, then refresh
                //  permissionStatusByType() on the returned result.
            },
        )

        Spacer(Modifier.height(32.dp))

        // --- 2. Supported data types ----------------------------------------
        HbSectionLabel("Supported Data Types")
        Spacer(Modifier.height(8.dp))
        HbCard {
            HealthDataType.entries.forEachIndexed { index, type ->
                if (index > 0) RowDivider()
                DataTypeToggleRow(
                    type = type,
                    enabled = typeEnabled[type] ?: true,
                    onToggle = { checked ->
                        typeEnabled[type] = checked
                        // TODO: persist + propagate to the sync engine type filter.
                    },
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // --- 3. Fingerprint database ----------------------------------------
        HbSectionLabel("Fingerprint Database")
        Spacer(Modifier.height(8.dp))
        HbCard {
            StatRow(label = "Records stored", value = formatCount(state.fingerprintCount))
            RowDivider()
            StatRow(label = "Storage size", value = formatBytes(state.storageBytes))
        }
        Spacer(Modifier.height(12.dp))
        DangerButton(
            text = "Reset sync history",
            onClick = { showResetDialog = true },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Clears the local fingerprint database only. Nothing is removed " +
                "from Health Connect.",
            color = TextTertiary,
            fontFamily = FontFamily.Default,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(32.dp))

        // --- 4. About --------------------------------------------------------
        HbSectionLabel("About")
        Spacer(Modifier.height(8.dp))
        AboutCard(appVersion = state.appVersion)

        Spacer(Modifier.height(24.dp))
    }

    // --- Destructive reset confirmation ------------------------------------
    if (showResetDialog) {
        ResetSyncHistoryDialog(
            onDismiss = { showResetDialog = false },
            onConfirm = {
                showResetDialog = false
                // TODO: clear fingerprints + sync_log via FingerprintDatabase
                //  (FingerprintDao + SyncLogDao) on a background dispatcher, then
                //  refresh the DB stats above.
            },
        )
    }
}

// ===========================================================================
// Section 1 — Permission row
// ===========================================================================

/**
 * One Health Connect permission row: the type's display name + HC record class,
 * with a GRANTED / DENIED status chip on the trailing edge.
 */
@Composable
private fun PermissionRow(
    type: HealthDataType,
    granted: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = type.displayName,
                color = TextPrimary,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = type.hcRecord,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (granted) {
            HbChip(text = "GRANTED", kind = HbChipKind.Ok)
        } else {
            HbChip(text = "DENIED", kind = HbChipKind.Error)
        }
    }
}

// ===========================================================================
// Section 2 — Data type toggle row
// ===========================================================================

/**
 * One supported-data-type row: display name over the HC record class, with a
 * trailing [Switch] gating whether the type is included in future syncs.
 */
@Composable
private fun DataTypeToggleRow(
    type: HealthDataType,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = type.displayName,
                color = TextPrimary,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = type.hcRecord,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BackgroundDark,
                checkedTrackColor = TealAccent,
                checkedBorderColor = TealAccent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceElevated,
                uncheckedBorderColor = DividerColor,
            ),
        )
    }
}

// ===========================================================================
// Section 3 — Stat row, danger button & confirmation dialog
// ===========================================================================

/**
 * A label/value stat row: muted label on the left, monospace data value
 * (teal) on the right.
 */
@Composable
private fun StatRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            color = TealAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
        )
    }
}

/**
 * Destructive action button: a full-width [ErrorRed] outline with red ink.
 * Mirrors the footprint of [HbGhostButton] but signals danger instead of
 * promotion (flat, no elevation).
 */
@Composable
private fun DangerButton(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ErrorRed.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = ErrorRed,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
    }
}

/**
 * Confirmation dialog for the destructive "Reset sync history" action.
 *
 * The body copy is verbatim from the PRD and must not be paraphrased — it sets
 * the exact expectation that Health Connect data is untouched but a re-import
 * may produce duplicates.
 */
@Composable
private fun ResetSyncHistoryDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = "Reset sync history?",
                color = TextPrimary,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
        },
        text = {
            Text(
                // EXACT PRD copy — do not edit.
                text = "This will not delete any data from Health Connect. It will " +
                    "cause all records to be re-evaluated on your next import, which " +
                    "may result in duplicates if records already exist in Health Connect.",
                color = TextSecondary,
                fontFamily = FontFamily.Default,
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onConfirm)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Reset",
                    color = ErrorRed,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
        },
        dismissButton = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Cancel",
                    color = TextSecondary,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                )
            }
        },
    )
}

// ===========================================================================
// Section 4 — About
// ===========================================================================

/**
 * About card: version, the on-device privacy posture line, and an open-source note.
 */
@Composable
private fun AboutCard(appVersion: String) {
    HbCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "HealthBridge",
                color = TextPrimary,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "v$appVersion",
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "No network · No accounts · No analytics · On-device",
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DividerColor),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Open source. The full parsing, fingerprinting, and write pipeline " +
                "is auditable — nothing leaves your device.",
            color = TextTertiary,
            fontFamily = FontFamily.Default,
            fontSize = 13.sp,
        )
    }
}

// ===========================================================================
// Shared pieces & helpers
// ===========================================================================

/** Thin 1.dp divider used between rows inside an [HbCard]. */
@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DividerColor),
    )
}

/** Group-separated formatting for count display (e.g. 18472 -> "18,472"). */
private fun formatCount(value: Int): String {
    // TODO: localize grouping separator via NumberFormat for the active locale.
    return "%,d".format(value)
}

/** Compact human-readable byte size (e.g. 2310000 -> "2.2 MB"). */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.1f GB".format(gb)
}

// ===========================================================================
// Previews
// ===========================================================================

@Preview(name = "Settings", backgroundColor = 0xFF0A0E1A, showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme {
        Box(modifier = Modifier.background(BackgroundDark)) {
            SettingsScreen(onBack = {})
        }
    }
}

@Preview(name = "Settings · Reset dialog", backgroundColor = 0xFF0A0E1A, showBackground = true)
@Composable
private fun ResetSyncHistoryDialogPreview() {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark),
            contentAlignment = Alignment.Center,
        ) {
            ResetSyncHistoryDialog(onDismiss = {}, onConfirm = {})
        }
    }
}
