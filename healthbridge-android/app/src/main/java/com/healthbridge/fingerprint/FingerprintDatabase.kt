package com.healthbridge.fingerprint

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local, fully-offline persistence for HealthBridge.
 *
 * Two responsibilities:
 *  1. [FingerprintEntity] — the dedup ledger. One row per Apple Health record we have already
 *     considered, keyed by a SHA-256 [hash] of (type + startDate + endDate + sourceName).
 *     The unique index `idx_fingerprints_hash` guarantees we never double-count a record and
 *     lets [FingerprintDao.existsByHash] answer "have I seen this before?" cheaply.
 *  2. [SyncLogEntity] — an append-only audit trail of every sync session (what file, how long,
 *     what was written/skipped, final status). Surfaced in the Sync History screen.
 *
 * value/calories are deliberately EXCLUDED from the fingerprint hash upstream (see
 * [com.healthbridge.fingerprint.FingerprintEngine]); edits to a record's value are ignored by
 * design, so they never produce a "new" delta.
 */

// region Entities

@Entity(
    tableName = "fingerprints",
    indices = [
        // Unique index backing dedup lookups. Named so migrations/inspection are predictable.
        Index(value = ["hash"], unique = true, name = "idx_fingerprints_hash")
    ]
)
data class FingerprintEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** SHA-256 hex of (type + startDate + endDate + sourceName). Unique across the table. */
    @ColumnInfo(name = "hash")
    val hash: String,

    /** [com.healthbridge.parser.HealthDataType] name, e.g. "STEPS". Kept as String for stability. */
    @ColumnInfo(name = "dataType")
    val dataType: String,

    /** ISO-8601 instant of the record's start, e.g. "2026-06-10T09:42:07Z". */
    @ColumnInfo(name = "recordDate")
    val recordDate: String,

    /** Epoch millis at which this fingerprint was committed locally. */
    @ColumnInfo(name = "syncedAt")
    val syncedAt: Long
)

@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** Epoch millis when the sync session completed. */
    @ColumnInfo(name = "syncedAt")
    val syncedAt: Long,

    /** Original ZIP filename the records were parsed from. */
    @ColumnInfo(name = "sourceFilename")
    val sourceFilename: String,

    /** Wall-clock duration of the full sync in milliseconds. */
    @ColumnInfo(name = "durationMs")
    val durationMs: Long,

    /** JSON map of HealthDataType -> count written, e.g. {"STEPS":1240,"WORKOUT":3}. */
    @ColumnInfo(name = "recordsWritten")
    val recordsWritten: String,

    /** Total records skipped as duplicates in this session. */
    @ColumnInfo(name = "recordsSkipped")
    val recordsSkipped: Int,

    /** Final session status: e.g. "SUCCESS", "PARTIAL", "FAILED". */
    @ColumnInfo(name = "status")
    val status: String
)

// endregion

// region Query projections

/**
 * Projection for [FingerprintDao.countByType]: how many fingerprints exist per data type.
 * [dataType] holds the [com.healthbridge.parser.HealthDataType] enum name (e.g. "STEPS");
 * consumers map it back via `HealthDataType.entries.firstOrNull { it.name == row.dataType }`,
 * dropping unknown names.
 */
data class TypeCount(
    @ColumnInfo(name = "dataType") val dataType: String,
    @ColumnInfo(name = "cnt") val cnt: Int
)

// endregion

// region DAOs

@Dao
interface FingerprintDao {

    /**
     * Bulk-insert fingerprints after a Health Connect batch write is confirmed.
     * IGNORE on conflict: re-seeing a known hash is a no-op (idempotent), not an error.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(fingerprints: List<FingerprintEntity>)

    /** Returns 1 if the hash is already known, 0 otherwise. Backed by the unique index. */
    @Query("SELECT EXISTS(SELECT 1 FROM fingerprints WHERE hash = :hash)")
    suspend fun existsByHash(hash: String): Int

    /** Total number of fingerprints stored. */
    @Query("SELECT COUNT(*) FROM fingerprints")
    suspend fun count(): Int

    /** All known hashes — used to build an in-memory dedup set for a fast pass over a large import. */
    @Query("SELECT hash FROM fingerprints")
    suspend fun allHashes(): List<String>

    /** Wipe the dedup ledger (Settings -> reset). */
    @Query("DELETE FROM fingerprints")
    suspend fun clear()

    /**
     * Count of stored fingerprints grouped by data type. The `dataType` column holds the
     * [com.healthbridge.parser.HealthDataType] enum name; map back via guarded enum decode.
     */
    @Query("SELECT dataType, COUNT(*) AS cnt FROM fingerprints GROUP BY dataType")
    suspend fun countByType(): List<TypeCount>
}

@Dao
interface SyncLogDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: SyncLogEntity)

    /** Most recent sessions first, for the Sync History screen. */
    @Query("SELECT * FROM sync_log ORDER BY syncedAt DESC")
    suspend fun getAll(): List<SyncLogEntity>

    @Query("SELECT COUNT(*) FROM sync_log")
    suspend fun count(): Int

    /** Approximate on-disk size proxy: total records written across all logged sessions is
     *  tracked elsewhere; here we expose the row count for the history summary. */
    @Query("SELECT COALESCE(SUM(recordsSkipped), 0) FROM sync_log")
    suspend fun totalSkipped(): Int
}

// endregion

// region Database

@Database(
    entities = [FingerprintEntity::class, SyncLogEntity::class],
    version = 1,
    // No schema export location is configured (single version + destructive migration), so keep
    // this false to avoid the KSP "Schema export directory is not provided" build warning.
    exportSchema = false
)
abstract class FingerprintDatabase : RoomDatabase() {

    abstract fun fingerprintDao(): FingerprintDao

    abstract fun syncLogDao(): SyncLogDao

    companion object {
        private const val DB_NAME = "healthbridge_fingerprints.db"

        @Volatile
        private var INSTANCE: FingerprintDatabase? = null

        /**
         * Process-wide singleton. Double-checked locking so concurrent first-access from the
         * import + sync coroutines doesn't build two databases.
         */
        fun getInstance(context: Context): FingerprintDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }

        private fun buildDatabase(context: Context): FingerprintDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                FingerprintDatabase::class.java,
                DB_NAME
            )
                // TODO: add explicit Migration objects once schema evolves past v1.
                .fallbackToDestructiveMigration()
                .build()
    }
}

// endregion
