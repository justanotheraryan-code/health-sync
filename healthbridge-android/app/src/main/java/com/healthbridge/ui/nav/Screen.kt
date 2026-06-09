package com.healthbridge.ui.nav

/**
 * Type-safe navigation routes for the HealthBridge app.
 *
 * Routes that operate on a selected Apple Health export (Processing, Delta, Writing)
 * carry a `fileUri` path argument so the relevant engine can resume from the chosen file.
 *
 * NOTE: the import/processing screens live in package `com.healthbridge.ui.importer`
 * (NOT `ui.import`) because `import` is a reserved Kotlin keyword and cannot be a
 * package segment. The nav route string is still the plain word "import".
 */
sealed class Screen(val route: String) {

    data object Onboarding : Screen("onboarding")

    data object Home : Screen("home")

    data object Import : Screen("import")

    /** Parses + fingerprints the chosen export. Carries the picked file URI. */
    data object Processing : Screen("processing/{fileUri}") {
        const val ARG_FILE_URI: String = "fileUri"
        fun routeFor(fileUri: String): String = "processing/${encode(fileUri)}"
    }

    /** Shows the new/skipped delta the user can confirm before writing. */
    data object Delta : Screen("delta/{fileUri}") {
        const val ARG_FILE_URI: String = "fileUri"
        fun routeFor(fileUri: String): String = "delta/${encode(fileUri)}"
    }

    /** Streams the batched write into Health Connect. */
    data object Writing : Screen("writing/{fileUri}") {
        const val ARG_FILE_URI: String = "fileUri"
        fun routeFor(fileUri: String): String = "writing/${encode(fileUri)}"
    }

    data object History : Screen("history")

    data object Settings : Screen("settings")

    companion object {
        /**
         * Minimal encoding so a `content://` / `file://` URI survives being embedded
         * as a single nav path segment. NavType decoding is handled on read.
         */
        private fun encode(raw: String): String =
            android.net.Uri.encode(raw)
    }
}
