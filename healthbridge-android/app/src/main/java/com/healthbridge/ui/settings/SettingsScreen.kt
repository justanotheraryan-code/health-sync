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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
 * State (per-type permission grants, per-type sync toggles, DB stats) lives in the VM's
 * [SettingsUiState] (declared in SettingsViewModel.kt). When a [SettingsViewModel] is supplied
 * the screen renders real values from [com.healthbridge.healthconnect.HealthConnectManager]
 * (permissions) and the Room fingerprint DB (counts/size), and persists toggle changes through
 * [com.healthbridge.data.SettingsRepository]. When `vm == null` (previews) a static sample is used.
 */

// ===========================================================================
// Sample / preview state
// ===========================================================================

/**
 * Static sample [SettingsUiState] for `@Preview` + the `vm == null` scaffolding path. The
 * authoritative [SettingsUiState] data class is declared in SettingsViewModel.kt; this is just a
 * populated instance (VO2 Max ungranted, everything enabled) so the screen renders without a VM.
 */
private val SampleSettings = SettingsUiState(
    enabledByType = HealthDataType.entries.associateWith { true },
    permissionByType = HealthDataType.entries.associateWith { type ->
        type != HealthDataType.VO2_MAX
    },
    fingerprintCount = 18_472,
    storageLabel = "1.1 MB",
)

/** User-facing app version. Not part of the VM state; rendered as a constant in [AboutCard]. */
private const val APP_VERSION = "1.0.0"

// ===========================================================================
// Screen
// ===========================================================================

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel? = null,
    onRequestPermissions: () -> Unit = {},
) {
    // Real state from the VM when present; otherwise the static sample (previews / scaffolding).
    val state = if (vm != null) {
        vm.uiState.collectAsState().value
    } else {
        SampleSettings
    }

    // --- Destructive-reset confirmation dialog visibility ------------------
    var showResetDialog by remember { mutableStateOf(false) }

    // After returning from the Health Connect consent flow (the in-app result OR the system HC
    // settings), re-read per-type grant state so the permission rows reflect reality. Mirrors how
    // MainActivity refreshes its own granted flag on resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm?.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                    granted = state.permissionByType[type] ?: false,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HbGhostButton(
            text = "Re-trigger permission grant",
            onClick = onRequestPermissions,
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
                    enabled = state.enabledByType[type] ?: true,
                    onToggle = { checked ->
                        // Persist + reflect through the VM (no-op in the preview/sample path).
                        vm?.setTypeEnabled(type, checked)
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
            // VM emits a pre-formatted label; render it directly (bypassing formatBytes).
            StatRow(label = "Storage size", value = state.storageLabel)
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
        AboutCard(appVersion = APP_VERSION)

        Spacer(Modifier.height(24.dp))
    }

    // --- Destructive reset confirmation ------------------------------------
    if (showResetDialog) {
        ResetSyncHistoryDialog(
            onDismiss = { showResetDialog = false },
            onConfirm = {
                showResetDialog = false
                // Clears the fingerprint ledger (sync_log + Health Connect untouched), then the
                // VM refreshes the record count + storage label above.
                vm?.resetFingerprints()
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

// Storage size is now pre-formatted by SettingsViewModel (state.storageLabel), so the byte
// formatter that used to live here was removed — the screen renders the label string directly.

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
