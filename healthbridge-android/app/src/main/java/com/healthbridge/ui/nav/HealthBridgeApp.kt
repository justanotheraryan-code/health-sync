package com.healthbridge.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.healthbridge.ui.delta.DeltaReviewScreen
import com.healthbridge.ui.history.SyncHistoryScreen
import com.healthbridge.ui.home.HomeScreen
import com.healthbridge.ui.importer.ImportScreen
import com.healthbridge.ui.importer.ProcessingScreen
import com.healthbridge.ui.onboarding.OnboardingScreen
import com.healthbridge.ui.settings.SettingsScreen
import com.healthbridge.ui.writing.WritingScreen

/**
 * Root navigation host. Single [NavHost] wiring all 8 screens.
 *
 * Screens receive plain `onNavigate` / `onBack` lambdas rather than the [NavController]
 * directly, so each screen stays decoupled from the nav graph and is preview-friendly.
 */
@Composable
fun HealthBridgeApp() {
    val navController = rememberNavController()

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
                onImport = { navController.navigate(Screen.Import.route) },
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
                fileUri = fileUri,
                onBack = { navController.popBackStack() },
                onWrite = {
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
                fileUri = fileUri,
                onDone = {
                    // Sync complete → return to Home, clearing the import flow.
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
