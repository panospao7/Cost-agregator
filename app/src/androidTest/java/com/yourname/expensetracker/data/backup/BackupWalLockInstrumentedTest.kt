package com.yourname.expensetracker.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * R26 device evidence for the backup SQLITE_BUSY defect, run on the real
 * framework SQLite (never Robolectric).
 *
 * A1: on a WAL database with a Room-like writer (framework
 *     [SQLiteDatabase] with write-ahead logging, i.e. a pooled primary writer
 *     connection) and a second read-only connection holding SHARED in a read
 *     transaction:
 *       - the removed `PRAGMA locking_mode = EXCLUSIVE` makes the next insert
 *         throw [SQLiteDatabaseLockedException];
 *       - the production snapshot path ([SqliteSnapshotCreator]) followed by
 *         an insert succeeds.
 * A2: the deciding test for the OPEN_READONLY VACUUM INTO in
 *     [SqliteSnapshotCreator] (API 30+): the snapshot has integrity_check ok,
 *     equal row counts, the live journal_mode stays wal, and the VACUUM_INTO
 *     method (not the file-copy fallback) was used.
 *
 * Only counts, flags and SQLite keywords are asserted or reported; no row
 * content or paths appear in failure messages.
 */
@RunWith(AndroidJUnit4::class)
class BackupWalLockInstrumentedTest {

    private lateinit var context: Context
    private val openDbs = mutableListOf<SQLiteDatabase>()
    private val files = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        openDbs.asReversed().forEach { runCatching { if (it.isOpen) it.close() } }
        files.forEach { base ->
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                File(base.path + suffix).delete()
            }
        }
    }

    private fun newFile(name: String): File {
        val file = context.getDatabasePath("r26_${name}_${System.nanoTime()}.db")
        file.parentFile?.mkdirs()
        files += file
        return file
    }

    /** A Room-like writer: framework database in WAL mode with pooled connections. */
    private fun openWalWriter(file: File, rows: Int): SQLiteDatabase {
        val writer = SQLiteDatabase.openOrCreateDatabase(file, null)
        openDbs += writer
        assertTrue("WAL must be enabled on the writer", writer.enableWriteAheadLogging())
        writer.execSQL("CREATE TABLE IF NOT EXISTS items (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
        writer.beginTransaction()
        try {
            repeat(rows) { i ->
                writer.insertOrThrow("items", null, ContentValues().apply { put("value", "v$i") })
            }
            writer.setTransactionSuccessful()
        } finally {
            writer.endTransaction()
        }
        assertEquals("wal", scalar(writer, "PRAGMA journal_mode"))
        return writer
    }

    /**
     * A second, read-only connection holding a SHARED read snapshot inside a
     * read transaction. `beginTransactionReadOnly` exists from API 35 only.
     */
    private fun openSharedReader(file: File): SQLiteDatabase {
        val reader = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        openDbs += reader
        reader.beginTransactionReadOnly()
        // The first read inside the transaction pins the read snapshot (SHARED).
        assertTrue(scalar(reader, "SELECT COUNT(*) FROM items").toInt() > 0)
        return reader
    }

    private fun scalar(db: SQLiteDatabase, sql: String): String =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getString(0) else "" }

    private fun insertRow(db: SQLiteDatabase) {
        db.insertOrThrow("items", null, ContentValues().apply { put("value", "late") })
    }

    @Test
    fun a1_exclusiveLockingModeBlocksWriterWhileSharedReaderHeld() {
        assumeTrue(
            "A1 needs beginTransactionReadOnly (API 35+) to hold SHARED in a read transaction",
            Build.VERSION.SDK_INT >= 35
        )
        val file = newFile("a1_defect")
        val writer = openWalWriter(file, rows = 20)
        openSharedReader(file)

        // The removed production statement.
        scalar(writer, "PRAGMA locking_mode = EXCLUSIVE")

        try {
            insertRow(writer)
            fail("REFUTED: insert succeeded although locking_mode=EXCLUSIVE and a SHARED reader are held")
        } catch (expected: SQLiteDatabaseLockedException) {
            // SQLITE_BUSY: the defect reproduced.
        }
    }

    @Test
    fun a1_productionSnapshotPathLeavesWriterUsableWhileSharedReaderHeld() {
        assumeTrue(
            "A1 needs beginTransactionReadOnly (API 35+) to hold SHARED in a read transaction",
            Build.VERSION.SDK_INT >= 35
        )
        val file = newFile("a1_fixed")
        val target = newFile("a1_fixed_snapshot")
        val writer = openWalWriter(file, rows = 20)
        openSharedReader(file)

        val result = SqliteSnapshotCreator().createSnapshot(file, target)

        assertTrue("snapshot file must exist", target.exists() && target.length() > 0L)
        assertTrue(
            "unexpected snapshot method ${result.method}",
            result.method == SnapshotMethod.VACUUM_INTO || result.method == SnapshotMethod.DRAINED_FILE_COPY
        )
        // The decisive assertion: the writer is not left holding an exclusive lock.
        insertRow(writer)
        assertEquals("21", scalar(writer, "SELECT COUNT(*) FROM items"))
        assertEquals("wal", scalar(writer, "PRAGMA journal_mode"))
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun a2_readOnlyVacuumIntoProducesConsistentSnapshotAndKeepsLiveWal() {
        val file = newFile("a2_live")
        val direct = newFile("a2_direct_snapshot")
        val viaCreator = newFile("a2_creator_snapshot")
        val writer = openWalWriter(file, rows = 50)

        // Direct OPEN_READONLY VACUUM INTO against the live WAL database.
        val readOnly = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            readOnly.execSQL("VACUUM INTO ?", arrayOf(direct.path))
        } finally {
            readOnly.close()
        }

        assertSnapshotMatchesLive(direct, liveCount = 50)
        assertEquals("live journal_mode must stay wal", "wal", scalar(writer, "PRAGMA journal_mode"))

        // The production creator must use VACUUM INTO (no silent fallback) on API 30+.
        val result = SqliteSnapshotCreator().createSnapshot(file, viaCreator)
        assertEquals(SnapshotMethod.VACUUM_INTO, result.method)
        assertSnapshotMatchesLive(viaCreator, liveCount = 50)
        assertEquals("live journal_mode must stay wal", "wal", scalar(writer, "PRAGMA journal_mode"))

        // The live writer remains writable afterwards.
        insertRow(writer)
        assertEquals("51", scalar(writer, "SELECT COUNT(*) FROM items"))
    }

    private fun assertSnapshotMatchesLive(snapshot: File, liveCount: Int) {
        assertTrue("snapshot file must exist", snapshot.exists() && snapshot.length() > 0L)
        val db = SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertEquals("ok", scalar(db, "PRAGMA integrity_check"))
            assertEquals(liveCount.toString(), scalar(db, "SELECT COUNT(*) FROM items"))
        } finally {
            db.close()
        }
    }
}
