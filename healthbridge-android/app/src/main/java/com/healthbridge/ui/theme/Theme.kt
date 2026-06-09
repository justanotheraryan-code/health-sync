package com.healthbridge.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * HealthBridge is dark-only. We map the PRD tokens onto a single
 * [darkColorScheme] and force it regardless of system setting. Elevation is
 * expressed via surface color (SurfaceDark / SurfaceElevated), never shadow.
 */
private val HealthBridgeColorScheme = darkColorScheme(
    primary = TealAccent,
    onPrimary = BackgroundDark,
    secondary = TealAccent,
    onSecondary = BackgroundDark,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = BackgroundDark,
    outline = DividerColor,
    outlineVariant = DividerColor,
)

/**
 * Root theme wrapper. Always dark.
 *
 * @param darkTheme present for API symmetry but ignored — HealthBridge does not
 *   support a light scheme. Defaults to the system value purely so previews and
 *   callers read naturally.
 */
@Composable
fun HealthBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Forced dark: HealthBridge has no light variant by design.
    val colorScheme = HealthBridgeColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = BackgroundDark.toArgb()
            window.navigationBarColor = BackgroundDark.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            // Dark background -> light status bar icons.
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HealthBridgeTypography,
        content = content,
    )
}
