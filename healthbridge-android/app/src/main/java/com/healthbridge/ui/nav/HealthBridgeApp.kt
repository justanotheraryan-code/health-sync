package com.healthbridge.ui.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.healthbridge.parser.HealthDataType
import com.healthbridge.ui.delta.DeltaReviewScreen
import com.healthbridge.ui.history.SyncHistoryScreen
import com.healthbridge.ui.home.HomeScreen
import com.healthbridge.ui.importer.ImportScreen
import com.healthbridge.ui.importer.ProcessingScreen
import com.healthbridge.ui.onboarding.OnboardingScreen
import com.healthbridge.ui.settings.SettingsScreen
import com.healthbridge.ui.sync.SyncViewModel
import com.healthbridge.ui.writing.WritingScreen

/**
 * Root navigation host. Single [NavHost] wiring all 8 screens.
 *
 * Screens receive plain `onNavigate` / `onBack` lambdas rather than the [NavController] directly,
 * so each screen stays decoupled from the nav graph and is preview-friendly.
 *
 * SHARED IMPORT VM: the Processing → Delta → Writing screens each get their own NavBackStackEntry,
 * but they MUST observe the SAME [SyncViewModel] so the phase-1 NEW records computed during
 * Processing survive into the Writing phase (nav only carries the fileUri String). We therefore
 * hoist ONE [SyncViewModel] here via `viewModel()` — scoped to the NavHost's ViewModelStoreOwner
 * (the Activity) — and pass that single instance down to all three screens. (Wiring option (A) from
 * the contract: simplest, matches "the VM owns one SyncEngine".)
 *
 * @param healthConnectAvailable            whether the Health Connect SDK is usable on this device.
 * @param healthConnectPermissionsGranted   whether all required HC permissions are granted.
 * @param onRequestHealthConnectPermissions launches the HC permission sheet (owned by MainActivity).
 * @param onRefreshHealthConnectPermissions re-reads live grant state after returning from HC settings.
 */
@Composable
fun HealthBridgeApp(
    healthConnectAvailable: Boolean = false,
    healthConnectPermissionsGranted: Boolean = false,
    onRequestHealthConnectPermissions: () -> Unit = {},
    onRefreshHealthConnectPermissions: () -> Unit = {},
) {
    val navController = rememberNavController()

    // ONE VM for the entire import flow, shared across Processing/Delta/Writing. Activity-scoped
    // (default `viewModel()` owner under the NavHost), so the cached phase-1 records persist between
    // the three back-stack entries.
    val syncViewModel: SyncViewModel = viewModel()

    // The set of data types to import. For the MVP we sync every supported type; Settings toggles
    // would narrow this in a later iteration.
    val enabledTypes: Set<HealthDataType> = HealthDataType.entries.toSet()

    NavHost(
        navController = navController,
        startDestination = Screen.Onboarding.route,
    ) {
        // ── ONBOARDING ──────────────────────────────────────────────────────
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onFinish = {
                    navController.navigate(Screen.Home.route) {
                        // Onboarding is one-shot: don't return to it on back.
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
            )
        }

        // ── HOME ────────────────────────────────────────────────────────────
        composable(Screen.Home.route) {
            HomeScreen(
                onImport = {
                    // Fresh import: clear any prior pipeline state before entering the flow.
                    syncViewModel.reset()
                    // Make sure HC permission state is current before the user reaches Writing.
                    onRefreshHealthConnectPermissions()
                    navController.navigate(Screen.Import.route)
                },
                onHistory = { navController.navigate(Screen.History.route) },
                onSettings = { navController.navigate(Screen.Settings.route) },
            )
        }

        // ── IMPORT (file picker) ────────────────────────────────────────────
        composable(Screen.Import.route) {
            ImportScreen(
                onBack = { navController.popBackStack() },
                onFileSelected = { fileUri ->
                    navController.navigate(Screen.Processing.routeFor(fileUri))
                },
            )
        }

        // ── PROCESSING (parse + fingerprint) ────────────────────────────────
        composable(
            route = Screen.Processing.route,
            arguments = listOf(
                navArgument(Screen.Processing.ARG_FILE_URI) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val fileUri = backStackEntry.requireFileUri(Screen.Processing.ARG_FILE_URI)
            ProcessingScreen(
                vm = syncViewModel,
                enabledTypes = enabledTypes,
                fileUri = fileUri,
                onBack = { navController.popBackStack() },
                onComplete = {
                    navController.navigate(Screen.Delta.routeFor(fileUri)) {
                        // Don't allow returning to the spinner once parsing finishes.
                        popUpTo(Screen.Processing.route) { inclusive = true }
                    }
                },
            )
        }

        // ── DELTA REVIEW (confirm new vs skipped) ───────────────────────────
        composable(
            route = Screen.Delta.route,
            arguments = listOf(
                navArgument(Screen.Delta.ARG_FILE_URI) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val fileUri = backStackEntry.requireFileUri(Screen.Delta.ARG_FILE_URI)
            DeltaReviewScreen(
                vm = syncViewModel,
                fileUri = fileUri,
                onBack = { navController.popBackStack() },
                onWrite = {
                    // Gate phase 2: start the write, then advance to the Writing screen.
                    syncViewModel.confirmWrite()
                    navController.navigate(Screen.Writing.routeFor(fileUri))
                },
            )
        }

        // ── WRITING (batched Health Connect insert) ─────────────────────────
        composable(
            route = Screen.Writing.route,
            arguments = listOf(
                navArgument(Screen.Writing.ARG_FILE_URI) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val fileUri = backStackEntry.requireFileUri(Screen.Writing.ARG_FILE_URI)
            WritingScreen(
                vm = syncViewModel,
                fileUri = fileUri,
                onDone = {
                    // Sync complete → return to Home, clearing the import flow.
                    syncViewModel.reset()
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onViewHistory = {
                    navController.navigate(Screen.History.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                },
            )
        }

        // ── HISTORY ─────────────────────────────────────────────────────────
        composable(Screen.History.route) {
            SyncHistoryScreen(
                onBack = { navController.popBackStack() },
            )
        }

        // ── SETTINGS ────────────────────────────────────────────────────────
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Reads a required, URL-encoded file-URI nav argument and decodes it back to a usable URI.
 */
private fun androidx.navigation.NavBackStackEntry.requireFileUri(key: String): String {
    val raw = arguments?.getString(key).orEmpty()
    return android.net.Uri.decode(raw)
}
