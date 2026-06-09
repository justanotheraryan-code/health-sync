package com.healthbridge.parser

import androidx.health.connect.client.records.ExerciseSessionRecord

/**
 * Maps Apple HealthKit workout activity type strings to Google Health Connect
 * [ExerciseSessionRecord] exercise type constants.
 *
 * The mapping intentionally covers the MVP subset of workout subtypes defined in the
 * HealthBridge contract. Any unrecognized Apple workout type falls back to
 * [ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT] so that no workout is ever dropped.
 *
 * Apple exports workout activity types either as bare names (e.g. "Running") or, in some
 * export variants, prefixed with the HealthKit identifier (e.g.
 * "HKWorkoutActivityTypeRunning"). Both forms are normalized before lookup.
 */
object WorkoutTypeMapper {

    private const val HK_PREFIX = "HKWorkoutActivityType"

    /**
     * Canonical Apple workout type -> Health Connect [ExerciseSessionRecord] exercise type.
     * Keys are normalized (prefix-stripped, lowercased) for resilient matching.
     */
    private val appleToHealthConnect: Map<String, Int> = mapOf(
        normalize("Running") to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        normalize("Cycling") to ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        normalize("Swimming") to ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        normalize("Walking") to ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        normalize("Yoga") to ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
        normalize("HighIntensityIntervalTraining") to
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
        normalize("StrengthTraining") to ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
    )

    /**
     * Health Connect exercise type -> human-readable display name for UI surfaces
     * (delta review rows, sync history, etc.).
     */
    private val displayNames: Map<Int, String> = mapOf(
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "Running",
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "Cycling",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Swimming",
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "Walking",
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "Yoga",
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "Strength Training",
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "Other Workout",
    )

    /**
     * Converts an Apple workout type string to a Health Connect exercise type constant.
     *
     * @param appleWorkoutType Apple HealthKit activity type, with or without the
     *   "HKWorkoutActivityType" prefix (e.g. "Running" or "HKWorkoutActivityTypeRunning").
     * @return the matching [ExerciseSessionRecord] exercise type, or
     *   [ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT] if unrecognized.
     */
    fun toExerciseType(appleWorkoutType: String): Int =
        appleToHealthConnect[normalize(appleWorkoutType)]
            ?: ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT

    /**
     * Reverse helper: returns a human-readable display name for a Health Connect
     * exercise type constant, for rendering in the UI.
     *
     * @param exerciseType an [ExerciseSessionRecord] EXERCISE_TYPE_* constant.
     * @return a display name, defaulting to "Other Workout" for unmapped types.
     */
    fun displayName(exerciseType: Int): String =
        displayNames[exerciseType] ?: "Other Workout"

    /**
     * Convenience: resolve an Apple workout type string directly to its UI display name
     * (Apple -> HC exercise type -> display name).
     */
    fun displayNameForApple(appleWorkoutType: String): String =
        displayName(toExerciseType(appleWorkoutType))

    /**
     * Normalizes an Apple workout type string: strips the optional HealthKit prefix and
     * lowercases for case-insensitive, prefix-insensitive lookups.
     */
    private fun normalize(raw: String): String =
        raw.trim().removePrefix(HK_PREFIX).lowercase()
}
