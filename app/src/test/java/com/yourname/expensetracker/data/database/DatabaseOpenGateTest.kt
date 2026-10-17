package com.yourname.expensetracker.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * R25 core open gate: read-only, one-shot classification of the live database file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseOpenGateTest {

    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        deleteLiveFiles()
        DatabaseOpenGate.resetForTests()
    }

    @After
    fun tearDown() {
        deleteLiveFiles()
        DatabaseOpenGate.resetForTests()
    }

    private fun deleteLiveFiles() {
        DatabaseOpenGate.liveDatabaseFiles(context).forEach { it.delete() }
    }

    private fun createDb(version: Int, withUserTable: Boolean) {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            if (withUserTable) db.execSQL("CREATE TABLE sample (id INTEGER PRIMARY KEY)")
            db.version = version
        } finally {
            db.close()
        }
    }

    private fun sha256(file: File): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun assertBlocked(code: String, result: DatabaseOpenGate.Result) {
        assertTrue("expected Blocked but was $result", result is DatabaseOpenGate.Result.Blocked)
        assertEquals(code, (result as DatabaseOpenGate.Result.Blocked).reasonCode)
    }

    @Test
    fun missingFileIsFresh() {
        assertSame(DatabaseOpenGate.Result.Fresh, DatabaseOpenGate.evaluate(context))
        assertNull(DatabaseOpenGate.blockedReasonOrNull(context))
    }

    @Test
    fun emptyVersionZeroWithoutUserTablesIsFresh() {
        createDb(version = 0, withUserTable = false)
        assertSame(DatabaseOpenGate.Result.Fresh, DatabaseOpenGate.evaluate(context))
    }

    @Test
    fun versionZeroWithUserTablesIsTooOld() {
        createDb(version = 0, withUserTable = true)
        assertBlocked("DB_SCHEMA_TOO_OLD", DatabaseOpenGate.evaluate(context))
    }

    @Test
    fun versionBelowBaselineIsTooOld() {
        createDb(version = DatabaseSchemaPolicy.MIGRATION_BASELINE - 1, withUserTable = true)
        assertBlocked("DB_SCHEMA_TOO_OLD", DatabaseOpenGate.evaluate(context))
        assertEquals("DB_SCHEMA_TOO_OLD", DatabaseOpenGate.blockedReasonOrNull(context))
    }

    @Test
    fun baselineVersionIsOk() {
        createDb(version = DatabaseSchemaPolicy.MIGRATION_BASELINE, withUserTable = true)
        assertSame(DatabaseOpenGate.Result.Ok, DatabaseOpenGate.evaluate(context))
    }

    @Test
    fun currentVersionIsOk() {
        createDb(version = APP_DATABASE_SCHEMA_VERSION, withUserTable = true)
        assertSame(DatabaseOpenGate.Result.Ok, DatabaseOpenGate.evaluate(context))
    }

    @Test
    fun versionAboveCurrentIsTooNew() {
        createDb(version = APP_DATABASE_SCHEMA_VERSION + 1, withUserTable = true)
        assertBlocked("DB_SCHEMA_TOO_NEW", DatabaseOpenGate.evaluate(context))
    }

    @Test
    fun garbageBytesAreBlockedAndFileIsNeitherModifiedNorDeleted() {
        val garbage = ByteArray(8192) { (it * 31 + 7).toByte() }
        dbFile.writeBytes(garbage)
        val before = sha256(dbFile)

        assertBlocked("DB_OPEN_FAILED", DatabaseOpenGate.evaluate(context))

        assertTrue("probe must never delete the file", dbFile.exists())
        assertArrayEquals(before, sha256(dbFile))
        assertArrayEquals(garbage, dbFile.readBytes())
    }

    @Test
    fun probeNeverWritesToAValidDatabaseFile() {
        createDb(version = APP_DATABASE_SCHEMA_VERSION, withUserTable = true)
        val before = sha256(dbFile)
        val sizeBefore = dbFile.length()

        assertSame(DatabaseOpenGate.Result.Ok, DatabaseOpenGate.evaluate(context))

        assertArrayEquals(before, sha256(dbFile))
        assertEquals(sizeBefore, dbFile.length())
    }

    @Test
    fun probeLeavesExistingWalFileInPlaceAndMainFileUntouched() {
        val writer = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            writer.enableWriteAheadLogging()
            writer.execSQL("CREATE TABLE sample (id INTEGER PRIMARY KEY)")
            writer.version = APP_DATABASE_SCHEMA_VERSION
            val walFile = File(dbFile.path + "-wal")
            assertTrue("precondition: WAL file exists while a writer is open", walFile.exists())
            val mainBefore = sha256(dbFile)
            val walSizeBefore = walFile.length()

            assertSame(DatabaseOpenGate.Result.Ok, DatabaseOpenGate.evaluate(context))

            assertTrue("probe must not remove the WAL file", walFile.exists())
            assertEquals(walSizeBefore, walFile.length())
            assertArrayEquals(mainBefore, sha256(dbFile))
        } finally {
            writer.close()
        }
    }

    @Test
    fun resultIsEvaluatedOnceAndCached() {
        createDb(version = APP_DATABASE_SCHEMA_VERSION, withUserTable = true)
        val first = DatabaseOpenGate.evaluate(context)
        assertEquals(1, DatabaseOpenGate.probeCountForTests())

        // Changing the file after the first evaluation must not change the cached verdict.
        dbFile.delete()
        dbFile.writeBytes(ByteArray(4096) { 0x5A })

        assertSame(first, DatabaseOpenGate.evaluate(context))
        assertNull(DatabaseOpenGate.blockedReasonOrNull(context))
        assertEquals(1, DatabaseOpenGate.probeCountForTests())

        DatabaseOpenGate.resetForTests()
        assertBlocked("DB_OPEN_FAILED", DatabaseOpenGate.evaluate(context))
        assertEquals(1, DatabaseOpenGate.probeCountForTests())
    }

    @Test
    fun throwingDatabasePathResolutionIsCachedAsOpenFailedInsteadOfEscaping() {
        val throwing = object : android.content.ContextWrapper(context) {
            override fun getDatabasePath(name: String?): File =
                throw IllegalStateException("path unavailable")
        }

        assertBlocked("DB_OPEN_FAILED", DatabaseOpenGate.evaluate(throwing))
        // Cached: a later healthy context does not change the verdict until reevaluated.
        assertBlocked("DB_OPEN_FAILED", DatabaseOpenGate.evaluate(context))
        assertEquals(1, DatabaseOpenGate.probeCountForTests())
    }

    @Test
    fun reevaluateDropsTheCachedVerdictAndProbesOnce() {
        dbFile.writeBytes(ByteArray(4096) { 0x5A })
        assertBlocked("DB_OPEN_FAILED", DatabaseOpenGate.evaluate(context))
        assertEquals(1, DatabaseOpenGate.probeCountForTests())

        // Simulates journal recovery replacing the unreadable file with a healthy one.
        dbFile.delete()
        createDb(version = APP_DATABASE_SCHEMA_VERSION, withUserTable = true)

        assertSame(DatabaseOpenGate.Result.Ok, DatabaseOpenGate.reevaluate(context))
        assertEquals(2, DatabaseOpenGate.probeCountForTests())
        assertSame(DatabaseOpenGate.Result.Ok, DatabaseOpenGate.evaluate(context))
        assertEquals(2, DatabaseOpenGate.probeCountForTests())
    }

    @Test
    fun liveDatabaseFilesListsDbWalAndShm() {
        val files = DatabaseOpenGate.liveDatabaseFiles(context)
        assertEquals(
            listOf(dbFile.path, dbFile.path + "-wal", dbFile.path + "-shm"),
            files.map { it.path }
        )
    }
}
