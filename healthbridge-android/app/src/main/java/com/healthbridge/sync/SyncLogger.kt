package com.healthbridge.sync

import com.healthbridge.fingerprint.SyncLogDao
import com.healthbridge.fingerprint.SyncLogEntity
import com.healthbridge.parser.SyncSession

/**
 * Append-only audit trail for HealthBridge sync sessions.
 *
 * Bridges the domain model [SyncSession] (used by the UI / [SyncEngine]) and the persisted
 * [SyncLogEntity] (Room). The only non-trivial part of the mapping is [SyncSession.recordsWritten]:
 * a `Map<String, Int>` of HealthDataType -> count, which Room stores as a JSON string column.
 *
 * JSON handling is intentionally dependency-light: a tiny hand-rolled encoder/decoder
 * ([encodeCounts] / [decodeCounts]) covers the exact, flat `{"STEPS":1240,"WORKOUT":3}` shape
 * we control on both ends. No org.json, Gson, or Moshi required. Keys are HealthDataType enum
 * names (alphanumeric + underscore) and values are non-negative ints, so escaping concerns are
 * minimal — but the encoder still escapes `"` and `\` defensively in case a source name ever
 * leaks into a key.
 *
 * Everything is suspending; callers are expected to invoke from a coroutine on an IO dispatcher.
 */
class SyncLogger(private val dao: SyncLogDao) {

    /**
     * Persist a completed [session] to the audit trail.
     *
     * Maps domain -> entity. [SyncSession.timestamp] (epoch millis when the run completed) is
     * stored as [SyncLogEntity.syncedAt]. The per-type write counts are encoded to JSON.
     */
    suspend fun log(session: SyncSession) {
        dao.insert(session.toEntity())
    }

    /**
     * Return the full sync history, most-recent-first (ordering is enforced by the DAO query),
     * mapped back into domain [SyncSession]s.
     */
    suspend fun history(): List<SyncSession> =
        dao.getAll().map { it.toSession() }

    /**
     * Lightweight summary for the Sync History screen header.
     *
     * @return [Stats] with the number of logged sessions and a "total size" proxy
     *         (sum of skipped records across all sessions — see [SyncLogDao.totalSkipped]).
     */
    suspend fun stats(): Stats =
        Stats(
            sessionCount = dao.count(),
            totalSize = dao.totalSkipped()
        )

    /** Aggregate counters surfaced above the history list. */
    data class Stats(
        /** Total number of recorded sync sessions. */
        val sessionCount: Int,
        /**
         * "Total size" proxy for the history summary: the sum of [SyncSession.recordsSkipped]
         * across every session (i.e. how many duplicate records dedup has saved us from writing).
         */
        val totalSize: Int
    )

    // region Mapping

    private fun SyncSession.toEntity(): SyncLogEntity =
        SyncLogEntity(
            // id = 0 -> Room autogenerates.
            syncedAt = timestamp,
            sourceFilename = sourceFilename,
            durationMs = durationMs,
            recordsWritten = encodeCounts(recordsWritten),
            recordsSkipped = recordsSkipped,
            status = status
        )

    private fun SyncLogEntity.toSession(): SyncSession =
        SyncSession(
            timestamp = syncedAt,
            sourceFilename = sourceFilename,
            durationMs = durationMs,
            recordsWritten = decodeCounts(recordsWritten),
            recordsSkipped = recordsSkipped,
            status = status
        )

    // endregion

    // region Tiny JSON codec (flat Map<String, Int>)

    /**
     * Encode a flat `Map<String, Int>` into a compact JSON object string.
     * e.g. {"STEPS":1240,"WORKOUT":3}. Empty map -> "{}".
     */
    internal fun encodeCounts(counts: Map<String, Int>): String =
        counts.entries.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}"
        ) { (key, value) -> "\"${escape(key)}\":$value" }

    /**
     * Decode the compact JSON object produced by [encodeCounts] back into a `Map<String, Int>`.
     *
     * This is a deliberately narrow parser for the exact `{"key":int,...}` shape we write — it is
     * NOT a general JSON parser. Malformed or unexpected input yields an empty map rather than
     * throwing, so a single corrupt log row can't break the whole history screen.
     */
    internal fun decodeCounts(json: String?): Map<String, Int> {
        val trimmed = json?.trim().orEmpty()
        if (trimmed.length < 2 || trimmed.first() != '{' || trimmed.last() != '}') return emptyMap()

        val body = trimmed.substring(1, trimmed.length - 1).trim()
        if (body.isEmpty()) return emptyMap()

        val result = LinkedHashMap<String, Int>()
        for (pair in splitTopLevel(body)) {
            val colon = pair.indexOf(':')
            if (colon <= 0) continue

            val rawKey = pair.substring(0, colon).trim()
            val rawValue = pair.substring(colon + 1).trim()

            // Key must be a quoted string; value must be a plain integer.
            if (rawKey.length < 2 || rawKey.first() != '"' || rawKey.last() != '"') continue
            val key = unescape(rawKey.substring(1, rawKey.length - 1))
            val value = rawValue.toIntOrNull() ?: continue

            result[key] = value
        }
        return result
    }

    /**
     * Split a JSON object body on top-level commas, ignoring commas inside quoted strings.
     * Our keys never contain commas in practice, but this keeps the decoder honest.
     */
    private fun splitTopLevel(body: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var escaped = false

        for (c in body) {
            when {
                escaped -> {
                    current.append(c)
                    escaped = false
                }
                c == '\\' -> {
                    current.append(c)
                    escaped = true
                }
                c == '"' -> {
                    current.append(c)
                    inString = !inString
                }
                c == ',' && !inString -> {
                    parts.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts
    }

    /** Minimal JSON string escaping for the small set of chars that can appear in a key. */
    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    /** Inverse of [escape] for the handful of escape sequences we emit. */
    private fun unescape(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> append('\\')
                    '"' -> append('"')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(c)
                i += 1
            }
        }
    }

    // endregion
}
