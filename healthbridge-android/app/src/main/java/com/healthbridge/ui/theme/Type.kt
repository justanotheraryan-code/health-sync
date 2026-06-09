package com.healthbridge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * HealthBridge typography (PRD §6).
 *
 * Two families:
 *  - [FontFamily.Default] (Inter-like) for labels / body copy.
 *  - [FontFamily.Monospace] for data values — counts, timestamps, fingerprints.
 *
 * Type scale (sp): micro 10, small 12, base 14, md 16, lg 20, xl 28, display 40.
 * We map the scale onto the Material3 [Typography] slots so Material components
 * pick up sane defaults, while data-heavy screens can pull the monospace styles
 * directly from [HbType].
 */

/** Raw, named text styles exposed for direct use by data-dense screens. */
object HbType {

    // ----- Labels / body (FontFamily.Default) -----
    val micro = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
    )
    val small = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
    val base = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    val md = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
    val lg = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    )
    val xl = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    )
    val display = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
    )

    // ----- Data values (FontFamily.Monospace) -----
    val dataSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
    val dataBase = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    val dataMd = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
    val dataXl = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    )
    val dataDisplay = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
    )
}

/**
 * Material3 [Typography] mapped onto the HealthBridge scale. Components that
 * read `MaterialTheme.typography` resolve to these. Data styles intentionally
 * live in [HbType] (e.g. [HbType.dataDisplay]) since Material slots are
 * semantic, not family-specific.
 */
val HealthBridgeTypography = Typography(
    displayLarge = HbType.display,
    displayMedium = HbType.xl,
    headlineLarge = HbType.xl,
    headlineMedium = HbType.lg,
    headlineSmall = HbType.lg,
    titleLarge = HbType.md,
    titleMedium = HbType.md,
    titleSmall = HbType.base,
    bodyLarge = HbType.base,
    bodyMedium = HbType.base,
    bodySmall = HbType.small,
    labelLarge = HbType.small,
    labelMedium = HbType.small,
    labelSmall = HbType.micro,
)
