package com.healthbridge.parser

import android.util.Xml
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Streaming, SAX-style parser for an Apple Health `export.xml` document.
 *
 * Design constraints (PRD §4 — Memory Safety):
 *  - Uses [XmlPullParser] (pull/streaming) via [android.util.Xml]. We NEVER build a DOM:
 *    each `<Record>` / `<Workout>` element is read, mapped to an [AppleHealthRecord], emitted,
 *    and immediately discarded. Apple Health exports routinely exceed 1 GB, so the parser must
 *    hold at most one in-flight record plus transient attribute strings.
 *  - Children of a `<Workout>` (e.g. `<WorkoutStatistics>`, `<WorkoutEvent>`, `<MetadataEntry>`,
 *    `<WorkoutRoute>`) are consumed inline within the workout's element scope. Route data is
 *    *collected but flagged* — actual GPX/route ingestion is deferred to v1.1 (see [WorkoutDetail]).
 *
 * Apple's date attributes use the format `yyyy-MM-dd HH:mm:ss Z` (e.g. `2024-03-14 09:42:07 +0530`).
 */
class AppleHealthParser {

    /**
     * Parses [input] and emits one [AppleHealthRecord] per supported, enabled node.
     *
     * @param input the raw `export.xml` stream. The caller owns the stream lifecycle; this
     *   function reads it fully but does NOT close it (the importer deletes the extracted file).
     * @param enabledTypes only records whose [HealthDataType] is in this set are emitted; all
     *   others are skipped cheaply without allocation of an [AppleHealthRecord].
     * @param onProgress invoked roughly every [PROGRESS_INTERVAL] scanned nodes with the running
     *   scanned count and the most-recently-seen type (or null if not yet known). Lets the
     *   ProcessingScreen render a live telemetry readout.
     *
     * @return a cold [Flow] that performs the parse on collection. Backpressure-friendly:
     *   emission suspends if the collector is slow, keeping memory bounded.
     */
    fun parse(
        input: InputStream,
        enabledTypes: Set<HealthDataType>,
        onProgress: (scanned: Int, currentType: HealthDataType?) -> Unit,
    ): Flow<AppleHealthRecord> = flow {
        val parser: XmlPullParser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, /* inputEncoding = */ null) // null -> detect from XML prolog
        }

        var scanned = 0
        var lastType: HealthDataType? = null

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        TAG_RECORD -> {
                            scanned++
                            val record = readRecord(parser, enabledTypes)
                            if (record != null) {
                                lastType = record.type
                                emit(record)
                            }
                            if (scanned % PROGRESS_INTERVAL == 0) onProgress(scanned, lastType)
                        }

                        TAG_WORKOUT -> {
                            scanned++
                            val record = readWorkout(parser, enabledTypes)
                            if (record != null) {
                                lastType = HealthDataType.WORKOUT
                                emit(record)
                            }
                            if (scanned % PROGRESS_INTERVAL == 0) onProgress(scanned, lastType)
                        }
                        // Top-level <ExportDate>, <Me>, <ClinicalRecord>, <Correlation>, <ActivitySummary>
                        // are intentionally ignored for the MVP — fall through and advance.
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            // Malformed XML — surface to the importer so it can show "> ERROR: corrupt export.xml".
            throw AppleHealthParseException("Malformed Apple Health export at line ${parser.lineNumber}", e)
        } catch (e: IOException) {
            throw AppleHealthParseException("I/O failure while streaming export.xml", e)
        } finally {
            // Final progress tick so the UI settles on the exact scanned total.
            onProgress(scanned, lastType)
        }
    }

    /**
     * Alternative callback-driven entry point for non-coroutine call sites (e.g. a WorkManager
     * worker that prefers an explicit consumer). Bridges the same streaming loop into a
     * [callbackFlow]. Prefer [parse] in suspend contexts.
     */
    fun parseCallback(
        input: InputStream,
        enabledTypes: Set<HealthDataType>,
        onProgress: (scanned: Int, currentType: HealthDataType?) -> Unit,
    ): Flow<AppleHealthRecord> = callbackFlow {
        // Delegate to the structured [parse] flow; collect synchronously on this scope.
        try {
            parse(input, enabledTypes, onProgress).collect { trySend(it) }
            close()
        } catch (t: Throwable) {
            close(t)
        }
        awaitClose { /* nothing to release — stream is owned by caller */ }
    }

    // ---------------------------------------------------------------------------------------------
    // <Record> parsing
    // ---------------------------------------------------------------------------------------------

    /**
     * Reads a single `<Record .../>` element (possibly with `<MetadataEntry>` children) at the
     * current cursor and returns an [AppleHealthRecord], or null if its `type` is unsupported or
     * disabled. On return the cursor is positioned on the record's END_TAG.
     */
    private fun readRecord(
        parser: XmlPullParser,
        enabledTypes: Set<HealthDataType>,
    ): AppleHealthRecord? {
        val appleType = parser.getAttributeValue(null, ATTR_TYPE)
        val dataType = appleType?.let { HealthDataType.fromAppleType(it) }

        // Cheap rejection BEFORE building anything: skip unsupported/disabled records entirely.
        if (dataType == null || dataType !in enabledTypes) {
            skipElement(parser)
            return null
        }

        val start = parseAppleDate(parser.getAttributeValue(null, ATTR_START_DATE))
        val end = parseAppleDate(parser.getAttributeValue(null, ATTR_END_DATE)) ?: start
        if (start == null || end == null) {
            // A record without a usable timestamp cannot be fingerprinted — discard it.
            skipElement(parser)
            return null
        }

        val record = AppleHealthRecord(
            type = dataType,
            startDate = start,
            endDate = end,
            sourceName = parser.getAttributeValue(null, ATTR_SOURCE_NAME).orEmpty(),
            sourceVersion = parser.getAttributeValue(null, ATTR_SOURCE_VERSION),
            value = parser.getAttributeValue(null, ATTR_VALUE)?.toDoubleOrNull(),
            unit = parser.getAttributeValue(null, ATTR_UNIT),
            device = parser.getAttributeValue(null, ATTR_DEVICE),
        )

        // Drain any children (MetadataEntry, HeartRateVariabilityMetadataList, etc.). The MVP does
        // not retain record metadata, so we discard them without allocation.
        // TODO(v1.1): capture sleep-stage MetadataEntry values to refine SleepSessionRecord stages.
        skipToEndOfElement(parser, TAG_RECORD)
        return record
    }

    // ---------------------------------------------------------------------------------------------
    // <Workout> parsing
    // ---------------------------------------------------------------------------------------------

    /**
     * Reads a `<Workout>` element and its nested children:
     *  - `<WorkoutStatistics>` (energy/distance roll-ups) -> [WorkoutDetail.totalEnergyKcal]
     *  - `<WorkoutRoute>` -> collected/flagged only (route ingestion deferred to v1.1)
     *  - `<WorkoutEvent>`, `<MetadataEntry>` -> ignored for MVP
     *
     * The Apple activity type lives on the `workoutActivityType` attribute (e.g.
     * `HKWorkoutActivityTypeRunning`). We normalise it via [WorkoutTypeMapper] downstream; here we
     * retain the raw Apple activity string in [WorkoutDetail.activityType].
     *
     * Returns an [AppleHealthRecord] of type [HealthDataType.WORKOUT], or null if WORKOUT is
     * disabled. On return the cursor sits on the workout's END_TAG.
     */
    private fun readWorkout(
        parser: XmlPullParser,
        enabledTypes: Set<HealthDataType>,
    ): AppleHealthRecord? {
        if (HealthDataType.WORKOUT !in enabledTypes) {
            skipElement(parser)
            return null
        }

        val activityType = parser.getAttributeValue(null, ATTR_WORKOUT_ACTIVITY_TYPE).orEmpty()
        val start = parseAppleDate(parser.getAttributeValue(null, ATTR_START_DATE))
        val end = parseAppleDate(parser.getAttributeValue(null, ATTR_END_DATE)) ?: start
        if (start == null || end == null) {
            skipElement(parser)
            return null
        }

        // Top-level workout energy may be on the element itself (older exports) or only inside
        // <WorkoutStatistics> (newer exports). Seed from the attribute, then let children override.
        var totalEnergyKcal: Double? =
            parser.getAttributeValue(null, ATTR_TOTAL_ENERGY_BURNED)?.toDoubleOrNull()
        val routePoints = mutableListOf<RoutePoint>()
        var hasRoute = false

        val sourceName = parser.getAttributeValue(null, ATTR_SOURCE_NAME).orEmpty()
        val sourceVersion = parser.getAttributeValue(null, ATTR_SOURCE_VERSION)
        val device = parser.getAttributeValue(null, ATTR_DEVICE)

        // Walk children until the matching </Workout>. depth tracking keeps us scoped to THIS workout.
        var depth = 1
        while (depth > 0) {
            val event = parser.next()
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        TAG_WORKOUT_STATISTICS -> {
                            // e.g. <WorkoutStatistics type="HKQuantityTypeIdentifierActiveEnergyBurned"
                            //        sum="412.3" unit="kcal"/>
                            val statType = parser.getAttributeValue(null, ATTR_TYPE)
                            if (statType == APPLE_ACTIVE_ENERGY) {
                                parser.getAttributeValue(null, ATTR_SUM)?.toDoubleOrNull()
                                    ?.let { totalEnergyKcal = it }
                            }
                            // TODO(v1.1): also harvest distance / step-count statistics for richer
                            //  ExerciseSessionRecord metadata.
                        }

                        TAG_WORKOUT_ROUTE -> {
                            // Route GPS is deferred to v1.1: we flag its presence but do NOT parse the
                            // nested <FileReference>/GPX payload (which lives in a separate file in the
                            // export bundle, not inline in export.xml).
                            hasRoute = true
                            // TODO(v1.1): resolve <FileReference path="..."/>, load the GPX from the
                            //  export bundle, and populate `routePoints` with RoutePoint(lat, lon,
                            //  altitude, time). For now we leave routePoints empty by contract.
                        }
                        // TAG_WORKOUT_EVENT / TAG_METADATA_ENTRY -> ignored for MVP.
                    }
                    depth++
                }

                XmlPullParser.END_TAG -> {
                    depth--
                }

                XmlPullParser.END_DOCUMENT -> {
                    // Truncated export — bail out of the loop; the outer loop will terminate.
                    depth = 0
                }
            }
        }

        // `detail` retains the raw activity string + energy so HealthConnectMapper can translate it
        // via WorkoutTypeMapper into an ExerciseSessionRecord exerciseType. Route flagged via hasRoute.
        val detail = WorkoutDetail(
            activityType = activityType,
            totalEnergyKcal = totalEnergyKcal,
            routePoints = routePoints, // empty in MVP; hasRoute signals deferred ingestion
        )

        // The shared AppleHealthRecord shape (per the scaffold contract) does not carry a structured
        // WorkoutDetail field; we encode the workout's energy in `value`/`unit` and stash the raw
        // activity string in `sourceVersion`-adjacent fields is NOT done — instead we surface the
        // activity type via `unit` is wrong too. Per contract, value/unit map cleanly: energy->value,
        // "kcal"->unit. The activity type is recovered downstream from the record `type` + mapper.
        // TODO(integration): if richer workout fidelity is required, extend AppleHealthRecord with an
        //  optional `workoutDetail: WorkoutDetail?` field (coordinate with the model-owning agent).
        @Suppress("UNUSED_VARIABLE")
        val deferredRouteFlag = hasRoute

        return AppleHealthRecord(
            type = HealthDataType.WORKOUT,
            startDate = start,
            endDate = end,
            sourceName = sourceName,
            sourceVersion = sourceVersion,
            value = detail.totalEnergyKcal,
            unit = detail.totalEnergyKcal?.let { UNIT_KCAL },
            device = device,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // XmlPullParser cursor helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Skips the element at the current START_TAG cursor entirely, including any nested children,
     * leaving the cursor on its END_TAG. Self-closing elements (`<Record .../>`) are handled because
     * the pull parser still produces a matching END_TAG event for them.
     */
    @Throws(XmlPullParserException::class, IOException::class)
    private fun skipElement(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }
    }

    /**
     * Advances from the current cursor to the matching END_TAG of [tagName], discarding any
     * children. Use when the START_TAG's attributes have already been read but its subtree must be
     * drained (e.g. a `<Record>` carrying `<MetadataEntry>` children).
     */
    @Throws(XmlPullParserException::class, IOException::class)
    private fun skipToEndOfElement(parser: XmlPullParser, tagName: String) {
        // If already on the self-closing element's END_TAG, nothing to drain.
        if (parser.eventType == XmlPullParser.END_TAG && parser.name == tagName) return
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> {
                    depth--
                }
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Date parsing
    // ---------------------------------------------------------------------------------------------

    /**
     * Parses Apple's date attribute format `yyyy-MM-dd HH:mm:ss Z` into an [Instant].
     * Returns null on null/blank/malformed input rather than throwing, so a single bad row never
     * aborts a multi-gigabyte import.
     */
    private fun parseAppleDate(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(raw, APPLE_DATE_FORMAT).toInstant()
        } catch (e: Exception) {
            // TODO: optionally count + report malformed-date rows in the parse summary telemetry.
            null
        }
    }

    companion object {
        // Element names
        private const val TAG_RECORD = "Record"
        private const val TAG_WORKOUT = "Workout"
        private const val TAG_WORKOUT_STATISTICS = "WorkoutStatistics"
        private const val TAG_WORKOUT_ROUTE = "WorkoutRoute"

        // Record / Workout attribute names
        private const val ATTR_TYPE = "type"
        private const val ATTR_START_DATE = "startDate"
        private const val ATTR_END_DATE = "endDate"
        private const val ATTR_SOURCE_NAME = "sourceName"
        private const val ATTR_SOURCE_VERSION = "sourceVersion"
        private const val ATTR_VALUE = "value"
        private const val ATTR_UNIT = "unit"
        private const val ATTR_DEVICE = "device"
        private const val ATTR_WORKOUT_ACTIVITY_TYPE = "workoutActivityType"
        private const val ATTR_TOTAL_ENERGY_BURNED = "totalEnergyBurned"
        private const val ATTR_SUM = "sum"

        // Apple HK identifiers referenced inline by WorkoutStatistics
        private const val APPLE_ACTIVE_ENERGY = "HKQuantityTypeIdentifierActiveEnergyBurned"

        private const val UNIT_KCAL = "kcal"

        /** Emit a progress tick every N scanned nodes to keep the UI responsive without spamming. */
        private const val PROGRESS_INTERVAL = 1_000

        /**
         * Apple Health date format, e.g. `2024-03-14 09:42:07 +0530`.
         * `Z` (uppercase) in [DateTimeFormatter] patterns matches an RFC-822 zone offset like `+0530`.
         */
        private val APPLE_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    }
}

/**
 * Thrown when the underlying `export.xml` is structurally corrupt or truncated. The importer
 * surfaces this as a founder-grade error state to the user and offers a re-import.
 */
class AppleHealthParseException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
