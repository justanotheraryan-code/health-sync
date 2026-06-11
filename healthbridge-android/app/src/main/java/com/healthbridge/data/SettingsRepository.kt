package com.healthbridge.data

import android.content.Context
import android.content.SharedPreferences
import com.healthbridge.parser.HealthDataType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide singleton for user-controlled, fully-offline app settings.
 *
 * Backed by [SharedPreferences] (name "healthbridge_settings"). Two concerns:
 *  1. Which [HealthDataType]s the user has enabled for import/write. Persisted as a
 *     `Set<String>` of [HealthDataType.name]; exposed reactively via [enabledTypes].
 *  2. Whether onboarding has been completed (drives the nav start destination).
 *
 * The [enabledTypes] StateFlow is the single source of truth at runtime; prefs are written
 * on every mutation and read once on construction. Synchronous reads ([isEnabled],
 * [currentEnabledTypes]) come off the live StateFlow value — no prefs round-trip.
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabledTypes = MutableStateFlow(readEnabledTypes())

    /** Live set of enabled data types. Default (key absent) = all 7. */
    val enabledTypes: StateFlow<Set<HealthDataType>> = _enabledTypes.asStateFlow()

    /**
     * Enable or disable a single [type]. Recomputes from the current live set, persists the
     * new set to prefs, then publishes it to [enabledTypes].
     */
    fun setTypeEnabled(type: HealthDataType, enabled: Boolean) {
        val current = _enabledTypes.value
        val newSet = if (enabled) current + type else current - type
        persistEnabledTypes(newSet)
        _enabledTypes.value = newSet
    }

    /** Synchronous: is [type] currently enabled? Reads the live StateFlow value. */
    fun isEnabled(type: HealthDataType): Boolean = _enabledTypes.value.contains(type)

    /** Synchronous snapshot of the currently enabled types (= the live StateFlow value). */
    fun currentEnabledTypes(): Set<HealthDataType> = _enabledTypes.value

    /** Has the user completed onboarding? Defaults to false. */
    fun isOnboarded(): Boolean = prefs.getBoolean(KEY_ONBOARDED, false)

    /** Persist the onboarded flag (defaults to marking onboarding complete). */
    fun setOnboarded(value: Boolean = true) {
        prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()
    }

    /**
     * Decode the persisted enabled-types set.
     *
     * - Key ABSENT (getStringSet returns null) -> default to all 7 types.
     * - Key PRESENT -> guarded enum decode of each stored name (drop unknowns). Honor an empty
     *   set as-is (the user may have disabled everything); only the absent case falls back to all.
     */
    private fun readEnabledTypes(): Set<HealthDataType> {
        val stored = prefs.getStringSet(KEY_ENABLED_TYPES, null)
            ?: return HealthDataType.entries.toSet()
        return stored
            .mapNotNull { name -> HealthDataType.entries.firstOrNull { it.name == name } }
            .toSet()
    }

    /** Persist [types] as a `Set<String>` of enum names. */
    private fun persistEnabledTypes(types: Set<HealthDataType>) {
        prefs.edit()
            .putStringSet(KEY_ENABLED_TYPES, types.map { it.name }.toSet())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "healthbridge_settings"

        /** Stored as a Set<String> of [HealthDataType.name]. */
        private const val KEY_ENABLED_TYPES = "enabled_types"
        private const val KEY_ONBOARDED = "onboarded"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context).also { INSTANCE = it }
            }
    }
}
