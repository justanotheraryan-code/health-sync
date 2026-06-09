package com.healthbridge.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * HealthBridge spacing & radii tokens (PRD §6).
 *
 * Spacing scale: 4, 8, 12, 16, 24, 32, 48 dp exposed as [s1]..[s7], with the
 * full 12-step alias surface ([s1]..[s12]) provided for call-site convenience.
 * Radii: cards 12.dp, buttons 8.dp, chips 4.dp.
 */
object Dimens {

    // ----- Spacing scale -----
    val s1: Dp = 4.dp
    val s2: Dp = 8.dp
    val s3: Dp = 12.dp
    val s4: Dp = 16.dp
    val s5: Dp = 24.dp
    val s6: Dp = 32.dp
    val s7: Dp = 48.dp
    // Extended aliases (non-linear top end) for screens that need more headroom.
    val s8: Dp = 64.dp
    val s9: Dp = 80.dp
    val s10: Dp = 96.dp
    val s11: Dp = 128.dp
    val s12: Dp = 160.dp

    // ----- Corner radii -----
    val cardRadius: Dp = 12.dp
    val buttonRadius: Dp = 8.dp
    val chipRadius: Dp = 4.dp

    // ----- Common component sizing -----
    /** Full-width primary button height (PRD §6). */
    val buttonHeight: Dp = 56.dp

    /** Hairline divider thickness. */
    val dividerThickness: Dp = 1.dp
}
