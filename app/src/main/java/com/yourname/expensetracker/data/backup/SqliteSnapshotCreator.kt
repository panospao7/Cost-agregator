package com.yourname.expensetracker.data.backup

import android.database.sqlite.SQLiteDatabase
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class SnapshotMethod { VACUUM_INTO, DRAINED_FILE_COPY }

data class SnapshotCreationResult(
    val method: SnapshotMethod,
    val liveCountsCaptured: Boolean
)

/**
 * Creates a consistent SQLite snapshot.
 * Prefers VACUUM INTO (atomic, no WAL issues); falls back to drained file-copy.
 * Caller must ensure maintenance mode is active and workers are drained.
 */
@Singleton
class SqliteSnapshotCreator @Inject constructor() {

    fun createSnapshot(
        sourceDbFile: File,
        targetSnapshotFile: File,
        liveCountsBeforeCopy: Map<String, Int>? = null
    ): SnapshotCreationResult {
        // Try VACUUM INTO first (SQLite 3.27+, Android API 30+)
        if (tryVacuumInto(sourceDbFile, targetSnapshotFile)) {
            return SnapshotCreationResult(SnapshotMethod.VACUUM_INTO, liveCountsCaptured = liveCountsBeforeCopy != null)
        }
        // Fallback: drained file-copy (caller already checkpointed WAL)
        sourceDbFile.inputStream().use { input ->
            targetSnapshotFile.outputStream().use { output -> input.copyTo(output) }
        }
        Timber.d("SqliteSnapshotCreator: used drained file-copy fallback")
        return SnapshotCreationResult(SnapshotMethod.DRAINED_FILE_COPY, liveCountsCaptured = liveCountsBeforeCopy != null)
    }

    private fun tryVacuumInto(source: File, target: File): Boolean {
        return try {
            val db = SQLiteDatabase.openDatabase(
                source.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
            )
            db.use { it.execSQL("VACUUM INTO ?", arrayOf(target.absolutePath)) }
            Timber.d("SqliteSnapshotCreator: VACUUM INTO succeeded")
            true
        } catch (e: Exception) {
            Timber.d("SqliteSnapshotCreator: VACUUM INTO not supported, falling back: ${e.message}")
            target.delete()
            false
        }
    }
}
