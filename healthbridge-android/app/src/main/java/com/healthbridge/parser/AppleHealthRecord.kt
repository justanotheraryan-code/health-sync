package com.healthbridge.parser

import java.time.Instant

/**
 * The seven MVP health data types supported by HealthBridge.
 *
 * Each entry carries:
 *  - [appleType]   the Apple Health (HealthKit) type identifier as it appears in export.xml
 *  - [hcRecord]    the Health Connect record class this type maps to
 *  - [displayName] a human-friendly label for UI surfaces
 *
 * Apple -> Health Connect mapping (PRD):
 *   WORKOUT            HKWorkoutActivityType*                     -> ExerciseSessionRecord
 *   STEPS              HKQuantityTypeIdentifierStepCount          -> StepsRecord
 *   HEART_RATE         HKQuantityTypeIdentifierHeartRate          -> HeartRateRecord
 *   SLEEP              HKCategoryTypeIdentifierSleepAnalysis      -> SleepSessionRecord
 *   ACTIVE_ENERGY      HKQuantityTypeIdentifierActiveEnergyBurned -> ActiveCaloriesBurnedRecord
 *   RESTING_HEART_RATE HKQuantityTypeIdentifierRestingHeartRate   -> RestingHeartRateRecord
 *   VO2_MAX            HKQuantityTypeIdentifierVO2Max             -> Vo2MaxRecord
 */
enum class HealthDataType(
    val appleType: String,
    val hcRecord: String,
    val displayName: String
) {
    WORKOUT(
        appleType = "HKWorkoutActivityType",
        hcRecord = "ExerciseSessionRecord",
        displayName = "Workout"
    ),
    STEPS(
        appleType = "HKQuantityTypeIdentifierStepCount",
        hcRecord = "StepsRecord",
        displayName = "Steps"
    ),
    HEART_RATE(
        appleType = "HKQuantityTypeIdentifierHeartRate",
        hcRecord = "HeartRateRecord",
        displayName = "Heart Rate"
    ),
    SLEEP(
        appleType = "HKCategoryTypeIdentifierSleepAnalysis",
        hcRecord = "SleepSessionRecord",
        displayName = "Sleep"
    ),
    ACTIVE_ENERGY(
        appleType = "HKQuantityTypeIdentifierActiveEnergyBurned",
        hcRecord = "ActiveCaloriesBurnedRecord",
        displayName = "Active Energy"
    ),
    RESTING_HEART_RATE(
        appleType = "HKQuantityTypeIdentifierRestingHeartRate",
        hcRecord = "RestingHeartRateRecord",
        displayName = "Resting Heart Rate"
    ),
    VO2_MAX(
        appleType = "HKQuantityTypeIdentifierVO2Max",
        hcRecord = "Vo2MaxRecord",
        displayName = "VO2 Max"
    );

    companion object {
        /**
         * Resolve a [HealthDataType] from a raw Apple Health type string.
         *
         * Apple's export encodes workouts with a concrete activity-type suffix
         * (e.g. "HKWorkoutActivityTypeRunning"), so [WORKOUT] is matched by prefix.
         * All other types are matched by exact equality on [appleType].
         *
         * @return the matching [HealthDataType], or null if unsupported.
         */
        fun fromAppleType(appleType: String?): HealthDataType? {
            if (appleType.isNullOrBlank()) return null

            // Workouts arrive as "HKWorkoutActivityType<Subtype>" — match by prefix.
            if (appleType.startsWith(WORKOUT.appleType)) return WORKOUT

            // Everything else is an exact quantity/category identifier.
            return entries.firstOrNull { it != WORKOUT && it.appleType == appleType }
        }
    }
}

/**
 * A single normalized record extracted from an Apple Health export.
 *
 * The fingerprint identity is derived from (type + startDate + endDate + sourceName);
 * [value]/[unit] are intentionally excluded so that later edits to a record's value
 * do not produce a "new" fingerprint.
 */
data class AppleHealthRecord(
    val type: HealthDataType,
    val startDate: Instant,
    val endDate: Instant,
    val sourceName: String,
    val sourceVersion: String?,
    val value: Double?,
    val unit: String?,
    val device: String?,
    /**
     * For [HealthDataType.WORKOUT] records only: the parsed workout detail (concrete activity
     * subtype + energy). Carried on the record so the Health Connect mapper can build a proper
     * [androidx.health.connect.client.records.ExerciseSessionRecord], and so the fingerprint can
     * include the activity subtype (two distinct workouts in the same time window must not collide).
     */
    val workoutDetail: WorkoutDetail? = null
)

/**
 * Extra detail for [HealthDataType.WORKOUT] records.
 *
 * @param activityType    the concrete Apple activity-type subtype (e.g. "Running").
 * @param totalEnergyKcal total active energy burned during the session, if known.
 * @param routePoints     GPS route samples; deferred to v1.1 (empty for MVP).
 */
data class WorkoutDetail(
    val activityType: String,
    val totalEnergyKcal: Double?,
    val routePoints: List<RoutePoint> = emptyList()
)

/** A single GPS sample along a workout route. */
data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val altitude: Double?,
    val time: Instant
)

/** One row of the delta-review summary: per-type new vs. skipped counts. */
data class DeltaRow(
    val type: HealthDataType,
    val newCount: Int,
    val skippedCount: Int,
    val dateRange: String
)

/** Aggregate result of comparing parsed records against existing fingerprints. */
data class DeltaResult(
    val rows: List<DeltaRow>,
    val newTotal: Int,
    val skippedTotal: Int,
    val scannedTotal: Int
)

/**
 * A completed sync run, persisted to the sync log.
 *
 * @param recordsWritten per-type count of records written to Health Connect.
 * @param status         terminal status string (e.g. "SUCCESS", "PARTIAL", "FAILED").
 */
data class SyncSession(
    val timestamp: Long,
    val sourceFilename: String,
    val durationMs: Long,
    val recordsWritten: Map<String, Int>,
    val recordsSkipped: Int,
    val status: String
)
