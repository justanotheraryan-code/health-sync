package com.healthbridge.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.healthbridge.data.SettingsRepository
import com.healthbridge.fingerprint.FingerprintDatabase
import com.healthbridge.healthconnect.HealthConnectManager
import com.healthbridge.parser.HealthDataType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Immutable view state for [SettingsScreen], supplied by [SettingsViewModel].
 *
 * @param enabledByType    per-[HealthDataType] "sync this type" toggle, sourced from
 *                         [SettingsRepository.isEnabled]. Default (no data) = empty.
 * @param permissionByType per-[HealthDataType] Health Connect write-permission grant state,
 *                         mirroring [HealthConnectManager.permissionStatusByType] (all-false
 *                         when Health Connect is unavailable).
 * @param fingerprintCount number of fingerprint rows currently stored locally.
 * @param storageLabel     pre-formatted, human-readable on-disk size of the fingerprint ledger
 *                         (e.g. "1.1 MB"), derived from [fingerprintCount] at ~64 bytes/row.
 */
data class SettingsUiState(
    val enabledByType: Map<HealthDataType, Boolean> = emptyMap(),
    val permissionByType: Map<HealthDataType, Boolean> = emptyMap(),
    val fingerprintCount: Int = 0,
    val storageLabel: String = "0 B",
)

/**
 * Backs the Settings screen with real on-device state:
 *  - per-type enabled toggles from [SettingsRepository] (persisted to SharedPreferences),
 *  - per-type Health Connect permission grants from [HealthConnectManager],
 *  - fingerprint-DB record count + a computed storage label from the Room [FingerprintDatabase],
 *  - a destructive "reset" that clears the fingerprint ledger (Health Connect data untouched).
 *
 * All dependencies are built from the application [Context] in [init]; no custom factory is
 * required because [AndroidViewModel] is auto-constructable by Compose's default `viewModel()`.
 * Every Room/HC call is suspending and runs on [viewModelScope] (Room uses its own IO executor).
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = FingerprintDatabase.getInstance(app)
    private val manager = HealthConnectManager(app)
    private val settings = SettingsRepository.getInstance(app)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // Enabled toggles read synchronously off the settings StateFlow snapshot.
        _uiState.update { s ->
            s.copy(enabledByType = HealthDataType.entries.associateWith { settings.isEnabled(it) })
        }
        viewModelScope.launch {
            refreshPermissions()
            reloadDbStats()
        }
    }

    /**
     * Enable or disable [type] for future syncs. Persists via [SettingsRepository] and reflects the
     * change immediately in [uiState] so the switch responds without waiting on a recomposition.
     */
    fun setTypeEnabled(type: HealthDataType, enabled: Boolean) {
        settings.setTypeEnabled(type, enabled)
        _uiState.update { it.copy(enabledByType = it.enabledByType + (type to enabled)) }
    }

    /**
     * Re-read per-type Health Connect grant state. Returns all-false when HC is unavailable, so no
     * try/catch is needed here. Safe to call after returning from the system consent flow.
     */
    fun refreshPermissions() {
        viewModelScope.launch {
            val perms = manager.permissionStatusByType()
            _uiState.update { it.copy(permissionByType = perms) }
        }
    }

    /**
     * Wipe the local fingerprint ledger (Settings -> "Reset sync history"), then refresh the
     * record count + storage label. Does NOT clear the sync_log and never touches Health Connect.
     */
    fun resetFingerprints() {
        viewModelScope.launch {
            db.fingerprintDao().clear()
            reloadDbStats()
        }
    }

    /** Reload [SettingsUiState.fingerprintCount] + [SettingsUiState.storageLabel] from Room. */
    private suspend fun reloadDbStats() {
        val count = db.fingerprintDao().count()
        _uiState.update { it.copy(fingerprintCount = count, storageLabel = formatBytes(count.toLong() * BYTES_PER_ROW)) }
    }

    companion object {
        /** Rough on-disk cost per fingerprint row (hash + small metadata + index overhead). */
        private const val BYTES_PER_ROW = 64L

        /** Compact human-readable byte size (e.g. 1_182_208 -> "1.1 MB"). */
        private fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "%.0f KB".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024) return "%.1f MB".format(mb)
            val gb = mb / 1024.0
            return "%.1f GB".format(gb)
        }
    }
}
