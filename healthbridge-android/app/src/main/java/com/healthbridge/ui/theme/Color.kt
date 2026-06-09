package com.healthbridge.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * HealthBridge color tokens (PRD §6).
 *
 * The app is dark-only. Elevation is expressed purely through surface color
 * (no shadows / no Material elevation overlays). These vals are the single
 * source of truth for color across the UI layer.
 */

// Accent
val TealAccent = Color(0xFF00D4B8)

// Backgrounds & surfaces (elevation via color, not shadow)
val BackgroundDark = Color(0xFF0A0E1A)
val SurfaceDark = Color(0xFF131929)
val SurfaceElevated = Color(0xFF1C2437)

// Text
val TextPrimary = Color(0xFFE8EDF5)
val TextSecondary = Color(0xFF8A96B0)
val TextTertiary = Color(0xFF56607A)

// Semantic
val ErrorRed = Color(0xFFFF4D4D)
val SuccessGreen = TealAccent

// Lines
val DividerColor = Color(0xFF232B3E)
