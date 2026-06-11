package com.healthbridge.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healthbridge.fingerprint.FingerprintDatabase
import com.healthbridge.parser.SyncSession
import com.healthbridge.sync.SyncLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Immutable UI state for the Sync History screen.
 *
 * @param sessions the persisted sync runs, newest-first (as returned by
 *        [SyncLogger.history]). The screen re-sorts defensively, so order here
 *        is advisory but already correct.
 */
data class HistoryUiState(
    val sessions: List<SyncSession> = emptyList(),
)

/**
 * Backs [SyncHistoryScreen] with the real sync-log audit trail.
 *
 * Builds its own dependencies from the application context (no DI framework):
 * the Room database singleton -> [SyncLogDao] -> [SyncLogger], which maps the
 * persisted `sync_log` rows back into domain [SyncSession]s (decoding the
 * per-type `recordsWritten` JSON).
 *
 * Auto-constructable by Compose's default `viewModel()` factory because it is an
 * [AndroidViewModel] taking only the [Application].
 */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val db = FingerprintDatabase.getInstance(app)
    private val logger = SyncLogger(db.syncLogDao())

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** Re-read the sync history from the log (e.g. after a new run completes). */
    fun refresh() {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            // logger.history() is already newest-first; Room runs the suspend
            // query on its own IO executor.
            _uiState.value = HistoryUiState(sessions = logger.history())
        }
    }
}
