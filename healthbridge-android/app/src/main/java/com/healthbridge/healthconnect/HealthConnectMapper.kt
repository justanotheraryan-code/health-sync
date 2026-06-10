package com.healthbridge.healthconnect

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Device
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

    /** Manufacturer attributed to imported Apple Health samples in [Device] provenance. */
    private const val APPLE_MANUFACTURER: String = "Apple"

    /**
     * The zone used to derive a [ZoneOffset] for each record. Apple's export stores wall-clock
     * timestamps with explicit offsets, but the parser collapses them to UTC [Instant]s. We
     * recover a sensible offset from the device's current zone at the sample's instant.
     *
     * NOTE(parser-handoff): a future parser revision can thread the original per-record UTC
     *   offset from export.xml (the trailing "+0530" on each timestamp) through
     *   [AppleHealthRecord] so historical samples keep their true local offset; until then the
     *   system zone yields a correct, insertable offset for every record.
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
            // Fall back to the detail carried on the record itself, so callers that don't pass an
            // explicit WorkoutDetail (the batch writer) still produce a real ExerciseSessionRecord.
            HealthDataType.WORKOUT -> mapWorkout(record, workout ?: record.workoutDetail)
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
            endTime = record.endDate.endStrictlyAfter(record.startDate),
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
            endTime = record.endDate.endStrictlyAfter(record.startDate),
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
            endTime = record.endDate.endStrictlyAfter(record.startDate),
            endZoneOffset = offsetAt(record.endDate),
            // Apple emits instantaneous HR samples; we represent each as a single-element series,
            // which is a valid, insertable HeartRateRecord.
            // NOTE(series): if a future parser revision groups contiguous HR samples into one
            //   window, build a multi-sample list here instead of a single sample.
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
            // SleepSessionRecord (like all interval records) requires startTime STRICTLY before
            // endTime; Apple sleep samples (or malformed exports) can have start == end, which would
            // throw inside insertRecords and fail the whole batch — so coerce end past start.
            endTime = record.endDate.endStrictlyAfter(record.startDate),
            endZoneOffset = offsetAt(record.endDate),
            // A flat session (no stages) is a valid, insertable SleepSessionRecord.
            // NOTE(series): a future parser revision can split Apple's per-stage SleepAnalysis
            //   categories (InBed/Asleep/REM/Deep/Core) into SleepSessionRecord.Stage entries.
            stages = emptyList(),
            metadata = metadataFor(record),
        )

    private fun mapActiveEnergy(record: AppleHealthRecord): Record? {
        val kcal = record.value ?: return null
        return ActiveCaloriesBurnedRecord(
            startTime = record.startDate,
            startZoneOffset = offsetAt(record.startDate),
            endTime = record.endDate.endStrictlyAfter(record.startDate),
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
     * Builds Health Connect [Metadata] for a record, tagged as a manual/imported entry and
     * carrying the source app/device provenance recovered from the Apple export.
     *
     * Provenance wired here:
     *  - [Device]: a watch/phone descriptor synthesized from [AppleHealthRecord.device] (the
     *    originating HealthKit device string, e.g. "Apple Watch") so Health Connect surfaces a
     *    sensible "imported from" device. Falls back to [Device.TYPE_UNKNOWN] when absent.
     *  - clientRecordId: a stable per-record id derived from the same identity tuple the
     *    FingerprintEngine hashes (type|start|end|source). This makes a re-import idempotent at
     *    the Health Connect layer too — HC upserts on a repeated clientRecordId rather than
     *    creating a duplicate — complementing the local fingerprint dedup ledger.
     *
     * NOTE: verify-against-SDK — in connect-client 1.1.0-alpha07 the canonical "manual entry"
     *   marker (`Metadata.manualEntry(...)` / `RECORDING_METHOD_MANUAL_ENTRY`) does not yet exist;
     *   it arrived with the later required-Metadata refactor. We therefore use the all-default
     *   [Metadata] constructor with explicit [clientRecordId] + [Device], which is the most
     *   widely-available shape on this pinned version. When bumping past the metadata refactor,
     *   switch to the manual-entry factory and pass `recordingMethod`.
     */
    private fun metadataFor(record: AppleHealthRecord): Metadata =
        Metadata(
            clientRecordId = clientRecordIdFor(record),
            device = deviceFor(record),
        )

    /**
     * Synthesizes a Health Connect [Device] from the Apple export's device string. We classify
     * the descriptor by a coarse keyword match (watch vs. phone), defaulting to
     * [Device.TYPE_UNKNOWN] when the export omits a device.
     *
     * NOTE: verify-against-SDK — [Device]'s constructor params (`manufacturer`, `model`, `type`)
     *   are stable across the 1.1.0 alphas; `type` takes a `Device.TYPE_*` Int constant.
     */
    private fun deviceFor(record: AppleHealthRecord): Device {
        val raw = record.device?.trim().orEmpty()
        val type = when {
            raw.contains("watch", ignoreCase = true) -> Device.TYPE_WATCH
            raw.contains("iphone", ignoreCase = true) ||
                raw.contains("phone", ignoreCase = true) -> Device.TYPE_PHONE
            else -> Device.TYPE_UNKNOWN
        }
        return Device(
            manufacturer = APPLE_MANUFACTURER,
            model = raw.ifBlank { record.sourceName.ifBlank { APPLE_MANUFACTURER } },
            type = type,
        )
    }

    /**
     * Builds a stable Health Connect clientRecordId from the record's identity tuple — the same
     * fields the FingerprintEngine uses for dedup (type, start, end, source). Re-importing the
     * same Apple sample yields the same id, so Health Connect treats the second write as an
     * upsert rather than a duplicate.
     */
    private fun clientRecordIdFor(record: AppleHealthRecord): String =
        buildString {
            append(record.type.name)
            append('|')
            append(record.startDate.toEpochMilli())
            append('|')
            append(record.endDate.toEpochMilli())
            append('|')
            append(record.sourceName)
        }

    /** Resolves the [ZoneOffset] in effect at [instant] for the configured [zone]. */
    private fun offsetAt(instant: Instant): ZoneOffset =
        zone.rules.getOffset(instant)

    /**
     * Ensures an end instant is STRICTLY after [start] (+1ms minimum). Health Connect interval
     * records (Exercise/Steps/HeartRate/Sleep/ActiveCalories) require startTime < endTime; Apple
     * exports frequently carry instantaneous (start == end) samples that would otherwise throw
     * inside insertRecords and fail the entire 500-record batch.
     */
    private fun Instant.endStrictlyAfter(start: Instant): Instant =
        if (this.isAfter(start)) this else start.plusMillis(1)
}
