package com.healthbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.healthbridge.healthconnect.HealthConnectManager
import com.healthbridge.ui.nav.HealthBridgeApp
import com.healthbridge.ui.theme.HealthBridgeTheme
import kotlinx.coroutines.launch

/**
 * Single activity hosting the Compose [HealthBridgeApp] NavHost.
 *
 * HealthBridge is fully offline — there is no network init, no auth, no service binding here. The
 * activity stands up the theme + nav graph and owns the Health Connect availability check plus the
 * permission-request launcher (registered before `onCreate` returns, per the Activity Result API
 * contract). Granted-state is surfaced to onboarding/settings via [HealthBridgeApp].
 */
class MainActivity : ComponentActivity() {

    /** Availability + permission gateway. Constructed eagerly; only touches the HC client lazily. */
    private val healthConnectManager: HealthConnectManager by lazy { HealthConnectManager(this) }

    /**
     * Single source of truth for "all required HC permissions granted", as a Compose state so both
     * the launcher result and the cold-start check recompose the UI. Held on the activity so the
     * launcher callback (registered in onCreate, before setContent) can write to it.
     */
    private val permissionsGranted: MutableState<Boolean> = mutableStateOf(false)

    /**
     * Launcher for the Health Connect permission sheet. Registered with the manager's
     * [HealthConnectManager.requestPermissionsContract] (PermissionController contract). Must be
     * created during onCreate, never lazily on click.
     */
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Register the HC permission launcher up-front. The result is the set of permissions the
        // user actually granted; recompute the granted-all flag from it for the UI.
        permissionLauncher = registerForActivityResult(
            healthConnectManager.requestPermissionsContract()
        ) { granted ->
            permissionsGranted.value = granted.containsAll(HealthConnectManager.REQUIRED_PERMISSIONS)
        }

        // Reflect any permissions already held on cold start into the UI state.
        lifecycleScope.launch {
            permissionsGranted.value = healthConnectManager.hasAllPermissions()
        }

        setContent {
            HealthBridgeTheme {
                val granted by permissionsGranted

                HealthBridgeApp(
                    healthConnectAvailable = healthConnectManager.isAvailable,
                    healthConnectPermissionsGranted = granted,
                    onRequestHealthConnectPermissions = {
                        if (healthConnectManager.isAvailable) {
                            permissionLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                        }
                    },
                    onRefreshHealthConnectPermissions = {
                        // Re-read the live grant state (e.g. after returning from the HC settings app).
                        lifecycleScope.launch {
                            permissionsGranted.value = healthConnectManager.hasAllPermissions()
                        }
                    },
                )
            }
        }
    }

    /**
     * Re-read the live HC grant state whenever the activity resumes. This covers the case where the
     * user leaves the app to toggle permissions in the Health Connect settings app and returns: the
     * launcher callback only fires for our own request sheet, so onResume is the reliable hook for
     * externally-changed grants. Safe to run unconditionally — [HealthConnectManager.hasAllPermissions]
     * returns false when HC is unavailable.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            permissionsGranted.value = healthConnectManager.hasAllPermissions()
        }
    }
}
