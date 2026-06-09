package com.healthbridge.healthconnect

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import com.healthbridge.parser.HealthDataType

/**
 * Thin wrapper around [HealthConnectClient] for HealthBridge.
 *
 * Responsibilities:
 *  - Report Health Connect SDK availability ([availability], [isAvailable]).
 *  - Declare the exact set of [REQUIRED_PERMISSIONS] the app needs (write for all 7 MVP
 *    record types + read for the 5 verification-relevant types = 12 permissions total).
 *  - Check whether all required permissions are currently granted ([hasAllPermissions]).
 *  - Expose the system permission-request [ActivityResultContract]
 *    ([permissionsLauncherContract]) so a screen can launch the consent flow.
 *  - Provide a per-[HealthDataType] grant map ([permissionStatusByType]) for UI surfaces.
 *
 * HealthBridge is a *write-only* client: it pushes deduplicated records into Health
 * Connect and never renders a dashboard. The read permissions exist purely so the sync
 * engine can (optionally) verify writes and so the settings screen can show an honest
 * per-type permission state. No network, no accounts — everything here is on-device.
 */
class HealthConnectManager(private val context: Context) {

    /**
     * Lazily-created Health Connect client.
     *
     * Only valid to access when [isAvailable] is true; constructing the client when the
     * SDK is unavailable will throw, so callers must gate on availability first.
     */
    val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    /**
     * Current SDK status for the active user profile.
     *
     * One of [HealthConnectClient.SDK_AVAILABLE],
     * [HealthConnectClient.SDK_UNAVAILABLE], or
     * [HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED].
     */
    val availability: Int
        get() = HealthConnectClient.getSdkStatus(context)

    /** True only when Health Connect is installed, up-to-date, and ready for use. */
    val isAvailable: Boolean
        get() = availability == HealthConnectClient.SDK_AVAILABLE

    /**
     * True when the provider is present but must be updated before use. The UI should
     * deep-link the user to the Play Store / system update flow in this case.
     */
    val providerUpdateRequired: Boolean
        get() = availability == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

    companion object {
        /**
         * The complete set of Health Connect permission strings HealthBridge requests.
         *
         * 7 WRITE permissions — one per supported [HealthDataType], since the app's whole
         * purpose is writing parsed Apple Health records:
         *   ExerciseSession, Steps, HeartRate, SleepSession, ActiveCaloriesBurned,
         *   RestingHeartRate, Vo2Max.
         *
         * 5 READ permissions — for the verification-relevant types the sync engine reads
         * back to confirm writes / power the settings status view:
         *   ExerciseSession, Steps, HeartRate, SleepSession, ActiveCaloriesBurned.
         *
         * 7 + 5 = 12 permissions total.
         */
        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            // --- Writes (7) ---
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(SleepSessionRecord::class),
            HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(RestingHeartRateRecord::class),
            HealthPermission.getWritePermission(Vo2MaxRecord::class),
            // --- Reads (5) ---
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        )

        /**
         * The single write permission each [HealthDataType] depends on to be syncable.
         * Used by [permissionStatusByType] to compute per-type grant state.
         */
        private val WRITE_PERMISSION_BY_TYPE: Map<HealthDataType, String> = mapOf(
            HealthDataType.WORKOUT to
                HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthDataType.STEPS to
                HealthPermission.getWritePermission(StepsRecord::class),
            HealthDataType.HEART_RATE to
                HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthDataType.SLEEP to
                HealthPermission.getWritePermission(SleepSessionRecord::class),
            HealthDataType.ACTIVE_ENERGY to
                HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
            HealthDataType.RESTING_HEART_RATE to
                HealthPermission.getWritePermission(RestingHeartRateRecord::class),
            HealthDataType.VO2_MAX to
                HealthPermission.getWritePermission(Vo2MaxRecord::class),
        )
    }

    /**
     * The set of permissions currently granted to HealthBridge by the user.
     *
     * @throws IllegalStateException-style errors if the SDK is unavailable; callers
     *         should gate on [isAvailable] before invoking.
     */
    suspend fun grantedPermissions(): Set<String> {
        // TODO: surface a typed result/error if [isAvailable] is false rather than
        //       letting the client throw on access.
        return client.permissionController.getGrantedPermissions()
    }

    /**
     * True iff every permission in [REQUIRED_PERMISSIONS] has been granted.
     *
     * This is the gate the import / sync flow checks before attempting any write.
     */
    suspend fun hasAllPermissions(): Boolean {
        if (!isAvailable) return false
        return grantedPermissions().containsAll(REQUIRED_PERMISSIONS)
    }

    /**
     * The [ActivityResultContract] used to launch the Health Connect permission request.
     *
     * A screen registers this via `rememberLauncherForActivityResult(...)`, then launches
     * it with [REQUIRED_PERMISSIONS]; the result is the set of permissions actually
     * granted, which can be re-checked with [hasAllPermissions].
     */
    fun permissionsLauncherContract(): ActivityResultContract<Set<String>, Set<String>> {
        return PermissionController.createRequestPermissionResultContract()
    }

    /**
     * Per-[HealthDataType] grant state, keyed by every supported MVP type.
     *
     * A type is considered "granted" when its corresponding *write* permission is held,
     * since write access is what gates syncing that type. Powers the settings screen's
     * per-type status rows and the pre-sync readiness check.
     */
    suspend fun permissionStatusByType(): Map<HealthDataType, Boolean> {
        if (!isAvailable) {
            // Nothing is syncable when Health Connect itself is unavailable.
            return HealthDataType.entries.associateWith { false }
        }
        val granted = grantedPermissions()
        return HealthDataType.entries.associateWith { type ->
            val writePerm = WRITE_PERMISSION_BY_TYPE[type]
            writePerm != null && writePerm in granted
        }
    }

    /**
     * Revoke all permissions previously granted to HealthBridge.
     *
     * Exposed for the settings "disconnect" affordance. No-op when unavailable.
     */
    suspend fun revokeAllPermissions() {
        if (!isAvailable) return
        // TODO: confirm this is the desired UX (full revoke) vs. deep-linking the user
        //       to the Health Connect app's data-management screen.
        client.permissionController.revokeAllPermissions()
    }
}
