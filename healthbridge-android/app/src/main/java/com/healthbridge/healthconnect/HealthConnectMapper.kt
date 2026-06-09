package com.healthbridge.healthconnect

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import com.healthbridge.parser.AppleHealthRecord
import com.healthbridge.parser.HealthDataType
import com.healthbridge.parser.WorkoutDetail
import com.healthbridge.parser.WorkoutTypeMapper
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Converts normalized [AppleHealthRecord]s into Google Health Connect
 * [Record] instances ready for batched insertion.
 *
 * The mapping is modeled as an exhaustive `when` over [HealthDataType]. Each branch
 * constructs the concrete Health Connect record class declared in the HealthBridge
 * contract:
 *
 * ```
 *   WORKOUT            -> ExerciseSessionRecord
 *   STEPS              -> StepsRecord
 *   HEART_RATE         -> HeartRateRecord
 *   SLEEP              -> SleepSessionRecord
 *   ACTIVE_ENERGY      -> ActiveCaloriesBurnedRecord
 *   RESTING_HEART_RATE -> RestingHeartRateRecord
 *   VO2_MAX            -> Vo2MaxRecord
 * ```
 *
 * Extensible for v2 types: adding a new supported [HealthDataType] is a matter of
 * adding one enum entry and one `when` branch here — the surrounding pipeline
 * (fingerprinting, batching, writing) is type-agnostic.
 *
 * Time handling: every record is stamped with [AppleHealthRecord.startDate] /
 * [AppleHealthRecord.endDate] (already normalized to UTC [Instant]s by the parser) and a
 * resolved [ZoneOffset] so Health Connect can render local-time aggregates correctly.
 */
object HealthConnectMapper {

    /**
     * The zone used to derive a [ZoneOffset] for each record. Apple's export stores wall-clock
     * timestamps with explicit offsets, but the parser collapses them to UTC [Instant]s. We
     * recover a sensible offset from the device's current zone at the sample's instant.
     *
     * TODO(parser-handoff): thread the original per-record UTC offset from export.xml
     *   (the trailing "+0530" on each timestamp) through [AppleHealthRecord] instead of
     *   inferring it here, so historical samples keep their true local offset.
     */
    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * Maps a single [AppleHealthRecord] to its Health Connect [Record].
     *
     * @param record   the normalized Apple Health sample.
     * @param workout  optional [WorkoutDetail] required only for [HealthDataType.WORKOUT];
     *   supplies the exercise subtype and total energy. Ignored for all other types.
     * @return the constructed Health Connect [Record], or `null` when the record cannot be
     *   represented (e.g. a WORKOUT with no [workout] detail, or a quantity type with a
     *   missing [AppleHealthRecord.value]). Null records are filtered out before batching.
     */
    fun map(record: AppleHealthRecord, workout: WorkoutDetail? = null): Record? =
        when (record.type) {
            HealthDataType.WORKOUT -> mapWorkout(record, workout)
            HealthDataType.STEPS -> mapSteps(record)
            HealthDataType.HEART_RATE -> mapHeartRate(record)
            HealthDataType.SLEEP -> mapSleep(record)
            HealthDataType.ACTIVE_ENERGY -> mapActiveEnergy(record)
            HealthDataType.RESTING_HEART_RATE -> mapRestingHeartRate(record)
            HealthDataType.VO2_MAX -> mapVo2Max(record)
            // Extensible for v2 types: new HealthDataType branches slot in here.
        }

    /**
     * Convenience bulk mapping. Workout detail is keyed externally; for the MVP, plain
     * (non-workout) records dominate, so callers that have no [WorkoutDetail] map can use this.
     * Records that fail to map (return null) are dropped.
     */
    fun mapAll(records: List<AppleHealthRecord>): List<Record> =
        records.mapNotNull { map(it, workout = null) }

    // ------------------------------------------------------------------------------------------
    // Per-type mappers
    // ------------------------------------------------------------------------------------------

    private fun mapWorkout(record: AppleHealthRecord, workout: WorkoutDetail?): Record? {
        // A workout without its detail row cannot resolve an exercise type — skip it.
        val detail = workout ?: return null
        val exerciseType = WorkoutTypeMapper.toExerciseType(detail.activityType)
        return ExerciseSessionRecord(
            startTime = record.startDate,
            startZoneOffset = offsetAt(record.startDate),
            endTime = record.endDate,
            endZoneOffset = offsetAt(record.endDate),
            exerciseType = exerciseType,
            title = WorkoutTypeMapper.displayName(exerciseType),
            metadata = metadataFor(record),
            // TODO(v1.1): emit ExerciseSegment / ExerciseRoute from detail.routePoints
            //   once route export is enabled. Lap/segment series are deferred for MVP.
        )
        // NOTE: detail.totalEnergyKcal is carried separately as an ActiveCaloriesBurnedRecord
        //   spanning the same window; ExerciseSessionRecord does not hold an energy field.
    }

    private fun mapSteps(record: AppleHealthRecord): Record? {
        val count = record.value?.toLong() ?: return null
        return StepsRecord(
            startTime = record.startDate,
            startZoneOffset = offsetAt(record.startDate),
            endTime = record.endDate,
            endZoneOffset = offsetAt(record.endDate),
            count = count.coerceAtLeast(0L),
            metadata = metadataFor(record),
        )
    }

    private fun mapHeartRate(record: AppleHealthRecord): Record? {
        val bpm = record.value?.toLong() ?: return null
        return HeartRateRecord(
            startTime = record.startDate,
            startZoneOffset = offsetAt(record.startDate),
            endTime = record.endDate.coerceAtLeast(record.startDate),
            endZoneOffset = offsetAt(record.endDate),
            // Apple emits instantaneous HR samples; we represent each as a single-element series.
            // TODO(series): when the parser groups contiguous HR samples into one window,
            //   build a multi-sample list here instead of a single mid-window sample.
            samples = listOf(
                HeartRateRecord.Sample(
                    time = record.startDate,
                    beatsPerMinute = bpm.coerceAtLeast(1L),
                )
            ),
            metadata = metadataFor(record),
        )
    }

    private fun mapSleep(record: AppleHealthRecord): Record =
        SleepSessionRecord(
            startTime = record.startDate,
            startZoneOffset = offsetAt(record.startDate),
            endTime = record.endDate,
            endZoneOffset = offsetAt(record.endDate),
            // TODO(series): split Apple's per-stage SleepAnalysis categories (InBed/Asleep/
            //   REM/Deep/Core) into SleepSessionRecord.Stage entries. MVP writes a flat session.
            stages = emptyList(),
            metadata = metadataFor(record),
        )

    private fun mapActiveEnergy(record: AppleHealthRecord): Record? {
        val kcal = record.value ?: return null
        return ActiveCaloriesBurnedRecord(
            startTime = record.startDate,
            startZoneOffset = offsetAt(record.startDate),
            endTime = record.endDate,
            endZoneOffset = offsetAt(record.endDate),
            energy = Energy.kilocalories(kcal.coerceAtLeast(0.0)),
            metadata = metadataFor(record),
        )
    }

    private fun mapRestingHeartRate(record: AppleHealthRecord): Record? {
        val bpm = record.value?.toLong() ?: return null
        return RestingHeartRateRecord(
            time = record.startDate,
            zoneOffset = offsetAt(record.startDate),
            beatsPerMinute = bpm.coerceAtLeast(1L),
            metadata = metadataFor(record),
        )
    }

    private fun mapVo2Max(record: AppleHealthRecord): Record? {
        val vo2 = record.value ?: return null
        return Vo2MaxRecord(
            time = record.startDate,
            zoneOffset = offsetAt(record.startDate),
            vo2MillilitersPerMinuteKilogram = vo2.coerceAtLeast(0.0),
            // Apple does not export the measurement method; default to OTHER.
            measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_OTHER,
            metadata = metadataFor(record),
        )
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Builds Health Connect [Metadata] for a record. The source app/device provenance from the
     * Apple export is preserved where Health Connect allows it.
     *
     * TODO(provenance): once we register a Health Connect data origin, attach
     *   [androidx.health.connect.client.records.metadata.Device] and clientRecordId
     *   (derived from the FingerprintEngine hash) so re-imports are idempotent at the HC layer
     *   too. For MVP, dedup is owned entirely by the local fingerprint DB.
     */
    private fun metadataFor(record: AppleHealthRecord): Metadata {
        // record.sourceName / record.sourceVersion / record.device are intentionally available
        // for future provenance wiring; the default (auto-id, empty origin) is used for now.
        return Metadata()
    }

    /** Resolves the [ZoneOffset] in effect at [instant] for the configured [zone]. */
    private fun offsetAt(instant: Instant): ZoneOffset =
        zone.rules.getOffset(instant)

    /** Ensures end >= start so Health Connect's start<=end invariant is never violated. */
    private fun Instant.coerceAtLeast(min: Instant): Instant =
        if (this.isBefore(min)) min else this
}
