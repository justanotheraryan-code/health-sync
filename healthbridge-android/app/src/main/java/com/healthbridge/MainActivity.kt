package com.healthbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.healthbridge.ui.nav.HealthBridgeApp
import com.healthbridge.ui.theme.HealthBridgeTheme

/**
 * Single activity hosting the Compose [HealthBridgeApp] NavHost.
 *
 * HealthBridge is fully offline — there is no network init, no auth, no service binding
 * here. The activity only stands up the theme + nav graph; all work (parse, fingerprint,
 * Health Connect writes) happens inside the screens / engines reached via navigation.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            HealthBridgeTheme {
                HealthBridgeApp()
            }
        }
    }
}
