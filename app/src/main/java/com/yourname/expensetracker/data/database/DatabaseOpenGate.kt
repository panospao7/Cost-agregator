package com.yourname.expensetracker.data.database

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * R25 core open gate: a one-shot, strictly read-only probe of the live database file that decides
 * whether Room may open it at all.
 *
 * Outcomes are bounded: [Result.Ok], [Result.Fresh] (no file, or an empty v0 file with no user
 * tables) or [Result.Blocked] with a bounded reason code. The probe never writes, never repairs and
 * never deletes: the framework default error handler would delete a file it considers corrupt, so a
 * no-op [DatabaseErrorHandler] is mandatory. The result is cached for the process lifetime.
 */
object DatabaseOpenGate {

    sealed interface Result {
        object Ok : Result
        object Fresh : Result
        class Blocked(val reasonCode: String) : Result
    }

    const val REASON_SCHEMA_TOO_OLD = "DB_SCHEMA_TOO_OLD"
    const val REASON_SCHEMA_TOO_NEW = "DB_SCHEMA_TOO_NEW"
    const val REASON_OPEN_FAILED = "DB_OPEN_FAILED"

    private const val LOG_TAG = "DatabaseOpenGate"
    private const val USER_TABLE_COUNT_SQL =
        "SELECT count(*) FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' " +
            "AND name NOT IN ('android_metadata', 'room_master_table')"

    private val lock = Any()

    @Volatile
    private var cached: Result? = null
    private val probeCount = AtomicInteger(0)

    @Volatile
    private var testOverride: Result? = null

    /** Evaluates the gate at most once per process; later calls return the cached result. */
    fun evaluate(context: Context): Result {
        testOverride?.let { return it }
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: probe(context).also {
                cached = it
            }
        }
    }

    /**
     * R25 D-M2: drops the cached verdict and probes once more. Only for the startup path that has
     * just completed restore-journal crash recovery, which may have repaired the live file.
     */
    fun reevaluate(context: Context): Result {
        testOverride?.let { return it }
        return synchronized(lock) {
            cached = null
            probe(context).also { cached = it }
        }
    }

    /** The blocking reason code, or null when the open may proceed. */
    fun blockedReasonOrNull(context: Context): String? =
        (evaluate(context) as? Result.Blocked)?.reasonCode

    /** The live database file plus its -wal and -shm companions (for exports). */
    fun liveDatabaseFiles(context: Context): List<File> {
        val db = databaseFile(context)
        return listOf(db, File(db.path + "-wal"), File(db.path + "-shm"))
    }

    private fun databaseFile(context: Context): File =
        context.getDatabasePath(AppDatabase.DATABASE_NAME)

    private fun probe(context: Context): Result {
        probeCount.incrementAndGet()

        return try {
            // Path resolution and exists() are inside the try so any throw fails closed.
            val file: File = databaseFile(context)
            if (!file.exists()) return Result.Fresh

            SQLiteDatabase.openDatabase(
                file.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                // No-op handler: the default handler deletes files it considers corrupt.
                DatabaseErrorHandler { }
            ).use { db -> classify(db) }
        } catch (e: Exception) {
            // Any probe failure (including a failing close) becomes a bounded block. The probe is
            // not suspend and runs no coroutines, so there is no cancellation to propagate.
            Log.w(LOG_TAG, "probe failed class=" + e.javaClass.simpleName)
            Result.Blocked(REASON_OPEN_FAILED)
        }
    }

    private fun classify(db: SQLiteDatabase): Result {
        val version = db.version
        if (version == 0) {
            val userTables = DatabaseUtils.longForQuery(db, USER_TABLE_COUNT_SQL, null)
            if (userTables == 0L) return Result.Fresh
        }
        return when {
            version < DatabaseSchemaPolicy.MIGRATION_BASELINE -> Result.Blocked(REASON_SCHEMA_TOO_OLD)
            version > APP_DATABASE_SCHEMA_VERSION -> Result.Blocked(REASON_SCHEMA_TOO_NEW)
            else -> Result.Ok
        }
    }

    internal fun probeCountForTests(): Int = probeCount.get()

    /** Test hook: drops the cached result so the next [evaluate] probes again. */
    internal fun resetForTests() {
        synchronized(lock) {
            cached = null
            testOverride = null
            probeCount.set(0)
        }
    }

    /**
     * Test-only seam: forces [evaluate] to return [Result.Blocked] with [reason] (or clears the
     * override when null) without probing any file. Cleared by [resetForTests].
     */
    internal fun overrideForTests(reason: String?) {
        testOverride = reason?.let { Result.Blocked(it) }
    }
}
