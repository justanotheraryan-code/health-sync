package com.healthbridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthbridge.ui.theme.BackgroundDark
import com.healthbridge.ui.theme.ErrorRed
import com.healthbridge.ui.theme.SurfaceDark
import com.healthbridge.ui.theme.SurfaceElevated
import com.healthbridge.ui.theme.TealAccent
import com.healthbridge.ui.theme.TextPrimary
import com.healthbridge.ui.theme.TextSecondary
import com.healthbridge.ui.theme.TextTertiary

/**
 * HealthBridge shared component library.
 *
 * Every screen MUST consume these composables instead of redefining its own
 * scaffolds, buttons or cards. This keeps the visual language (flat surfaces,
 * no elevation, teal accent, monospace data values) consistent across the app.
 *
 * Design tokens come from [com.healthbridge.ui.theme]. Radii / spacing follow
 * the PRD: cards 12.dp, buttons 8.dp, chips 4.dp; spacing scale 4/8/12/16/24/32/48.
 * Elevation is expressed purely via surface color — never shadows.
 */

// ---------------------------------------------------------------------------
// Local design constants (kept here so the component file is self-contained).
// These intentionally mirror the values defined in ui.theme.Dimens.
// ---------------------------------------------------------------------------

private val RadiusCard = 12.dp
private val RadiusButton = 8.dp
private val RadiusChip = 4.dp

private val ButtonHeight = 56.dp
private val ScreenHPadding = 24.dp

// ===========================================================================
// HbScaffold
// ===========================================================================

/**
 * Standard screen shell: an optional top bar (back arrow + title + trailing
 * action slot), a vertically scrollable content column padded 24.dp on the
 * horizontal edges, and an optional docked footer that stays pinned to the
 * bottom (typically the primary CTA).
 */
@Composable
fun HbScaffold(
    title: String?,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
    ) {
        // --- Top bar ---------------------------------------------------------
        if (title != null || onBack != null || action != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(RadiusButton))
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                if (title != null) {
                    Text(
                        text = title,
                        color = TextPrimary,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (action != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    action()
                }
            }
        }

        // --- Scrollable content ---------------------------------------------
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenHPadding, vertical = 8.dp),
            content = content,
        )

        // --- Docked footer ---------------------------------------------------
        if (footer != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BackgroundDark)
                    .padding(horizontal = ScreenHPadding, vertical = 16.dp),
                content = footer,
            )
        }
    }
}

// ===========================================================================
// Buttons
// ===========================================================================

/**
 * Primary call-to-action: solid [TealAccent] background, [BackgroundDark] text,
 * full width, 56.dp tall, 8.dp corners, flat (no elevation). Disabled state
 * dims the surface and ink.
 */
@Composable
fun HbPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val bg = if (enabled) TealAccent else SurfaceElevated
    val fg = if (enabled) BackgroundDark else TextTertiary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ButtonHeight)
            .clip(RoundedCornerShape(RadiusButton))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = fg,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
    }
}

/**
 * Ghost button: transparent fill with a [TealAccent] outline and teal ink.
 * Same footprint as the primary button.
 */
@Composable
fun HbGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ButtonHeight)
            .clip(RoundedCornerShape(RadiusButton))
            .border(width = 1.dp, color = TealAccent, shape = RoundedCornerShape(RadiusButton))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = TealAccent,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
    }
}

/**
 * Low-emphasis text button: no fill, no border. Used for tertiary actions
 * ("Skip", "Cancel", "Learn more").
 */
@Composable
fun HbTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(RadiusButton))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
    }
}

// ===========================================================================
// HbCard
// ===========================================================================

/**
 * Flat surface card with 12.dp corners and 16.dp internal padding. When
 * [accent] is true the card sits on the elevated surface with a subtle
 * teal-tinted top border to draw the eye (elevation by color, never shadow).
 */
@Composable
fun HbCard(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val surface = if (accent) SurfaceElevated else SurfaceDark
    val shape = RoundedCornerShape(RadiusCard)

    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(surface)

    val bordered = if (accent) {
        base.border(width = 1.dp, color = TealAccent.copy(alpha = 0.35f), shape = shape)
    } else {
        base
    }

    Column(
        modifier = bordered.padding(16.dp),
        content = content,
    )
}

// ===========================================================================
// HbDataRow
// ===========================================================================

/**
 * Two-line list row: [title] over [subtitle] on the left, a monospace
 * [trailing] data value on the right. Optionally tappable.
 */
@Composable
fun HbDataRow(
    title: String,
    subtitle: String,
    trailing: String,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(vertical = 12.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextSecondary,
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = trailing,
            color = TealAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            maxLines = 1,
        )
    }
}

// ===========================================================================
// HbMetric
// ===========================================================================

/**
 * Large stacked metric: monospace [value] over a small [label]. Used in the
 * delta summary and sync history headers.
 */
@Composable
fun HbMetric(
    value: String,
    label: String,
) {
    Column {
        Text(
            text = value,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = TextSecondary,
            fontFamily = FontFamily.Default,
            fontSize = 12.sp,
        )
    }
}

// ===========================================================================
// HbChip
// ===========================================================================

/**
 * Visual kind of an [HbChip]. Drives the chip's ink + tinted background.
 */
enum class HbChipKind { Neutral, Ok, Warn, Error }

/**
 * Small status pill with 4.dp corners. Background is a low-alpha tint of the
 * kind's color; the text uses the full-strength color.
 */
@Composable
fun HbChip(
    text: String,
    kind: HbChipKind = HbChipKind.Neutral,
) {
    val color = when (kind) {
        HbChipKind.Neutral -> TextSecondary
        HbChipKind.Ok -> TealAccent
        HbChipKind.Warn -> WarnAmber
        HbChipKind.Error -> ErrorRed
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(RadiusChip))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = color,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
        )
    }
}

// ===========================================================================
// HbSectionLabel
// ===========================================================================

/**
 * Uppercase, letter-spaced section divider label used above grouped content.
 */
@Composable
fun HbSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = TextTertiary,
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

// ---------------------------------------------------------------------------
// Local color not present in the shared token set: an amber for "warn" chips.
// Kept private to this file so screens still consume the canonical tokens.
// ---------------------------------------------------------------------------
private val WarnAmber = Color(0xFFFFB020)

// ===========================================================================
// Previews
// ===========================================================================

@Preview(backgroundColor = 0xFF0A0E1A, showBackground = true)
@Composable
private fun HbComponentsPreview() {
    HbScaffold(
        title = "Components",
        onBack = {},
        action = { HbChip(text = "BETA", kind = HbChipKind.Ok) },
        footer = { HbPrimaryButton(text = "Continue", onClick = {}) },
    ) {
        HbSectionLabel("Buttons")
        Spacer(Modifier.height(8.dp))
        HbGhostButton(text = "Select export", onClick = {})
        Spacer(Modifier.height(8.dp))
        HbTextButton(text = "Skip for now", onClick = {})

        Spacer(Modifier.height(24.dp))
        HbSectionLabel("Cards")
        Spacer(Modifier.height(8.dp))
        HbCard(accent = true) {
            HbMetric(value = "1,284", label = "NEW RECORDS")
        }
        Spacer(Modifier.height(12.dp))
        HbCard {
            HbDataRow(
                title = "Steps",
                subtitle = "Jan 2024 — Jun 2024",
                trailing = "9,431",
                onClick = {},
            )
            HbDataRow(
                title = "Heart Rate",
                subtitle = "Skipped (duplicate)",
                trailing = "0",
            )
        }

        Spacer(Modifier.height(24.dp))
        HbSectionLabel("Chips")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HbChip("NEUTRAL", HbChipKind.Neutral)
            HbChip("OK", HbChipKind.Ok)
            HbChip("WARN", HbChipKind.Warn)
            HbChip("ERROR", HbChipKind.Error)
        }
    }
}
