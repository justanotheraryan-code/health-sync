package com.healthbridge.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healthbridge.fingerprint.FingerprintDatabase
import com.healthbridge.parser.HealthDataType
import com.healthbridge.parser.SyncSession
import com.healthbridge.sync.SyncLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Immutable view state backing [HomeScreen] when driven by real on-device data.
 *
 * This is the authoritative Home state shape (it supersedes the old screen-local sample
 * `HomeUiState`): the screen derives its banner + metrics from these fields.
 *
 * @param lastSync     the most recent persisted sync session (newest in the sync log), or null
 *                     if no sync has ever completed. Drives the last-sync banner / empty state.
 * @param countsByType lifetime count of fingerprints (records committed) per [HealthDataType],
 *                     aggregated from the fingerprint ledger. Drives the metrics row.
 * @param totalSynced  sum of [countsByType] values — total records ever written across all types.
 */
data class HomeUiState(
    val lastSync: SyncSession? = null,
    val countsByType: Map<HealthDataType, Int> = emptyMap(),
    val totalSynced: Int = 0,
)

/**
 * Home / dashboard ViewModel.
 *
 * Reads the read-side of the local store:
 *  - the newest [SyncSession] from the sync log (via [SyncLogger.history], which JSON-decodes the
 *    per-type write counts and returns newest-first) for the last-sync banner, and
 *  - per-type fingerprint counts (via [com.healthbridge.fingerprint.FingerprintDao.countByType])
 *    for the metrics row.
 *
 * State is exposed as an immutable [StateFlow] and (re)loaded on [init] and [refresh]. All DAO
 * calls are suspend and run on Room's IO executor inside [viewModelScope].
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val db = FingerprintDatabase.getInstance(app)
    private val logger = SyncLogger(db.syncLogDao())

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** Re-reads the sync log + fingerprint counts and re-emits [uiState]. */
    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            // Newest persisted session (domain object, with recordsWritten decoded). history() is
            // already newest-first, so first() is the most recent sync.
            val lastSync: SyncSession? = logger.history().firstOrNull()

            // Lifetime per-type fingerprint counts. The stored dataType column holds the enum name;
            // guard the decode and drop any unknown names defensively.
            val countsByType: Map<HealthDataType, Int> = db.fingerprintDao().countByType()
                .mapNotNull { row ->
                    val type = HealthDataType.entries.firstOrNull { it.name == row.dataType }
                    type?.let { it to row.cnt }
                }
                .toMap()

            _uiState.value = HomeUiState(
                lastSync = lastSync,
                countsByType = countsByType,
                totalSynced = countsByType.values.sum(),
            )
        }
    }
}
