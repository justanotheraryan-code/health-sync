package com.healthbridge.ui.importer

import android.content.Intent
import android.provider.OpenableColumns
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream
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
    val context = LocalContext.current
    var picked by remember { mutableStateOf<PickedFile?>(null) }
    // The raw picked Uri, set synchronously in the launcher callback; the heavy inspection
    // (metadata + ZIP peek) runs off-main in a LaunchedEffect keyed on this value.
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // SAF document picker. We restrict to ZIP via mime type, but Apple's export is
    // sometimes reported as octet-stream by the OS, so the real archive sniffing
    // (export.xml present?) is what actually gates the CTA.
    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument,
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Take a persistable read permission so we can re-open the export across process death
        // (the Processing screen resumes from the same Uri string).
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        pendingUri = uri
    }

    // Off-main inspection: query OpenableColumns (DISPLAY_NAME / SIZE) and peek the ZIP for an
    // `export.xml` entry. Both touch the ContentResolver, so they must not run on the main thread.
    LaunchedEffect(pendingUri) {
        val uri = pendingUri ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { inspectPickedZip(context, uri) }
        picked = result
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
                    // Hand the real selected URI string to the Processing screen; the shared
                    // SyncViewModel resumes parse+dedupe from it.
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
 * Inspects a picked archive [uri] OFF the main thread and builds a [PickedFile]:
 *  1. queries [OpenableColumns.DISPLAY_NAME] / [OpenableColumns.SIZE] for the card header, and
 *  2. streams the ZIP central directory looking for an `export.xml` entry (matched by leaf name so
 *     `apple_health_export/export.xml` also counts) to set [PickedFile.isValidExport].
 *
 * The ZIP peek reads only entry headers via [ZipInputStream] (it never inflates the multi-GB
 * `export.xml` payload), so it is cheap even on large exports. Any I/O failure yields an INVALID
 * result rather than throwing, so a bad pick degrades to the guidance copy.
 *
 * MUST be called from a background dispatcher — it touches the ContentResolver and reads bytes.
 */
private fun inspectPickedZip(context: android.content.Context, uri: android.net.Uri): PickedFile {
    val resolver = context.contentResolver

    // --- Metadata (name + size) -------------------------------------------------------------
    var name = uri.lastPathSegment ?: "archive.zip"
    var sizeBytes: Long = -1L
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) sizeBytes = cursor.getLong(sizeIdx)
                }
            }
    }

    // --- Validation: does the ZIP contain an export.xml entry? ------------------------------
    val isValidExport = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val leaf = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && leaf.equals("export.xml", ignoreCase = true)) {
                        return@runCatching true
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        false
    }.getOrDefault(false)

    return PickedFile(
        uri = uri.toString(),
        name = name,
        sizeLabel = formatBytes(sizeBytes),
        modifiedLabel = "—",
        isValidExport = isValidExport,
    )
}

/** Human-readable byte size (e.g. 248.6 MB). Returns "Unknown size" when the size is unavailable. */
private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "Unknown size"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
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
