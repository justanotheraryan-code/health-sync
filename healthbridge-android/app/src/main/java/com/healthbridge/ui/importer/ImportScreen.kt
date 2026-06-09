package com.healthbridge.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.healthbridge.ui.theme.ErrorRed
import com.healthbridge.ui.theme.SurfaceDark
import com.healthbridge.ui.theme.TealAccent
import com.healthbridge.ui.theme.TextPrimary
import com.healthbridge.ui.theme.TextSecondary
import com.healthbridge.ui.theme.TextTertiary

/**
 * Import (file picker) screen — step 1 of the sync flow.
 *
 * The user drops in their Apple Health export ZIP. We launch the Storage Access
 * Framework picker (mime `application/zip`), then:
 *   1. surface a file card (name / size / modified),
 *   2. cheaply validate that the archive looks like an Apple Health export
 *      (i.e. it contains `export.xml`),
 *   3. enable "Start processing" only when validation passes.
 *
 * Everything heavy (real ZIP inspection, extraction to cacheDir, parsing) happens
 * on the Processing screen. Here we only do a lightweight peek so the user gets
 * immediate feedback before committing to a parse.
 *
 * NOTE: the navigation graph ([com.healthbridge.ui.nav.HealthBridgeApp]) wires this
 * screen with `onFileSelected = { fileUri -> navigate(Processing.routeFor(fileUri)) }`.
 * That callback is the PRD's "Start processing" handoff (per the assignment it is the
 * `onStartProcessing(fileName)` lambda); we forward the picked URI string so the
 * Processing screen can resume from the chosen file.
 */
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onFileSelected: (fileUri: String) -> Unit,
) {
    var picked by remember { mutableStateOf<PickedFile?>(null) }

    // SAF document picker. We restrict to ZIP via mime type, but Apple's export is
    // sometimes reported as octet-stream by the OS, so the real archive sniffing
    // (export.xml present?) is what actually gates the CTA.
    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument,
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // TODO: take a persistable URI permission so we can re-open across process death:
        //   context.contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)
        // TODO: read DocumentsContract metadata (DISPLAY_NAME, SIZE, LAST_MODIFIED) off the
        //   main thread, then peek the ZIP central directory for an `export.xml` entry to
        //   compute `isValidExport`. For now we mock a selection so the UI is exercisable.
        picked = mockPickedFromUri(uri.toString())
    }

    val launchPicker: () -> Unit = {
        // OpenDocument takes an array of acceptable mime types.
        pickLauncher.launch(arrayOf(MIME_ZIP))
    }

    val current = picked
    val canProcess = current?.isValidExport == true

    HbScaffold(
        title = "Import export",
        onBack = onBack,
        footer = {
            HbPrimaryButton(
                text = "Start processing",
                onClick = {
                    val file = picked ?: return@HbPrimaryButton
                    // TODO: hand the real selected URI to ProcessingScreen via SyncEngine.
                    onFileSelected(file.uri)
                },
                enabled = canProcess,
            )
            if (current != null) {
                Spacer(Modifier.height(8.dp))
                HbGhostButton(
                    text = "Choose a different file",
                    onClick = launchPicker,
                )
            }
        },
    ) {
        if (current == null) {
            Dropzone(onClick = launchPicker)
        } else {
            HbSectionLabel("Selected file")
            Spacer(Modifier.height(8.dp))
            FileCard(file = current)
            Spacer(Modifier.height(16.dp))
            ValidationResult(isValid = current.isValidExport)
        }
    }
}

// ===========================================================================
// Dropzone (empty state)
// ===========================================================================

/**
 * Tappable dashed dropzone shown before any file is selected. Tapping launches
 * the SAF picker (drag-and-drop is not a thing on phones, so the whole surface is
 * a button).
 */
@Composable
private fun Dropzone(onClick: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .border(
                width = 1.dp,
                color = TealAccent.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TealAccent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.UploadFile,
                    contentDescription = null,
                    tint = TealAccent,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Select your Apple Health export",
                color = TextPrimary,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tap to choose the export.zip from your device",
                color = TextSecondary,
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    HelpHint()
}

/**
 * A persistent breadcrumb reminding the user where the export lives, so an
 * empty dropzone is never a dead end.
 */
@Composable
private fun HelpHint() {
    HbCard {
        Text(
            text = "Where do I get this?",
            color = TextPrimary,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = EXPORT_INSTRUCTIONS,
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontSize = 13.sp,
        )
    }
}

// ===========================================================================
// File card (selected state)
// ===========================================================================

/**
 * Card summarising the picked archive: filename + a metadata line (size · modified).
 * Size / timestamps render in monospace per the type spec for data values.
 */
@Composable
private fun FileCard(file: PickedFile) {
    HbCard(accent = file.isValidExport) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDark),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.UploadFile,
                    contentDescription = null,
                    tint = TealAccent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = TextPrimary,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${file.sizeLabel} · ${file.modifiedLabel}",
                    color = TextTertiary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            HbChip(
                text = if (file.isValidExport) "VALID" else "INVALID",
                kind = if (file.isValidExport) HbChipKind.Ok else HbChipKind.Error,
            )
        }
    }
}

// ===========================================================================
// Validation result
// ===========================================================================

/**
 * Inline validation block under the file card. On success we confirm `export.xml`
 * was found; on failure we show the EXACT PRD copy guiding the user to re-export.
 */
@Composable
private fun ValidationResult(isValid: Boolean) {
    if (isValid) {
        ValidationRow(
            icon = Icons.Rounded.CheckCircle,
            tint = TealAccent,
            title = "Looks like a valid Apple Health export",
            body = "Found export.xml. Ready to scan for new records.",
        )
    } else {
        ValidationRow(
            icon = Icons.Rounded.ErrorOutline,
            tint = ErrorRed,
            title = "Invalid export",
            body = INVALID_EXPORT_MESSAGE,
        )
    }
}

@Composable
private fun ValidationRow(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(
                width = 1.dp,
                color = tint.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                color = TextPrimary,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                color = TextSecondary,
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
            )
        }
    }
}

// ===========================================================================
// Model + helpers
// ===========================================================================

/**
 * Lightweight description of the picked archive, derived from SAF document metadata
 * plus a cheap "is this an Apple Health export?" check.
 */
private data class PickedFile(
    val uri: String,
    val name: String,
    val sizeLabel: String,
    val modifiedLabel: String,
    val isValidExport: Boolean,
)

private const val MIME_ZIP = "application/zip"

/** The EXACT copy required by the PRD for an invalid/non-export archive. */
private const val INVALID_EXPORT_MESSAGE =
    "This doesn't look like an Apple Health export. Export from: " +
        "iPhone Health app -> Profile icon -> Export All Health Data."

private const val EXPORT_INSTRUCTIONS =
    "On your iPhone: open the Health app -> tap your Profile icon -> " +
        "Export All Health Data. Transfer the resulting export.zip to this device."

/**
 * Stand-in that fabricates a [PickedFile] from a picked URI so the UI is fully
 * exercisable without real document inspection.
 *
 * TODO: replace with real metadata + ZIP central-directory inspection:
 *   - DISPLAY_NAME / SIZE / LAST_MODIFIED from DocumentsContract
 *   - scan entries for `export.xml` (or `apple_health_export/export.xml`)
 */
private fun mockPickedFromUri(uri: String): PickedFile {
    val looksLikeExport = uri.contains("export", ignoreCase = true)
    return PickedFile(
        uri = uri,
        name = if (looksLikeExport) "export.zip" else "archive.zip",
        sizeLabel = "248.6 MB",
        modifiedLabel = "2026-06-09 21:14",
        isValidExport = looksLikeExport,
    )
}

// ===========================================================================
// Previews
// ===========================================================================

@Preview(name = "Import — empty", backgroundColor = 0xFF0A0E1A, showBackground = true)
@Composable
private fun ImportScreenEmptyPreview() {
    ImportScreen(onBack = {}, onFileSelected = {})
}

@Preview(name = "Import — valid file", backgroundColor = 0xFF0A0E1A, showBackground = true)
@Composable
private fun ImportScreenValidPreview() {
    PreviewShell(
        file = PickedFile(
            uri = "content://mock/export.zip",
            name = "export.zip",
            sizeLabel = "248.6 MB",
            modifiedLabel = "2026-06-09 21:14",
            isValidExport = true,
        ),
    )
}

@Preview(name = "Import — invalid file", backgroundColor = 0xFF0A0E1A, showBackground = true)
@Composable
private fun ImportScreenInvalidPreview() {
    PreviewShell(
        file = PickedFile(
            uri = "content://mock/photos.zip",
            name = "photos_backup.zip",
            sizeLabel = "12.4 MB",
            modifiedLabel = "2026-05-30 08:02",
            isValidExport = false,
        ),
    )
}

/**
 * Static render of the "file selected" layout for previews (no launcher / no SAF),
 * so both the valid and invalid validation states are visible in @Preview.
 */
@Composable
private fun PreviewShell(file: PickedFile) {
    HbScaffold(
        title = "Import export",
        onBack = {},
        footer = {
            HbPrimaryButton(
                text = "Start processing",
                onClick = {},
                enabled = file.isValidExport,
            )
            Spacer(Modifier.height(8.dp))
            HbGhostButton(text = "Choose a different file", onClick = {})
        },
    ) {
        HbSectionLabel("Selected file")
        Spacer(Modifier.height(8.dp))
        FileCard(file = file)
        Spacer(Modifier.height(16.dp))
        ValidationResult(isValid = file.isValidExport)
    }
}
