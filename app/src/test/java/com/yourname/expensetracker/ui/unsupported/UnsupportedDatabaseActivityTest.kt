package com.yourname.expensetracker.ui.unsupported

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.DatabaseOpenGate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R25 slice B2: the unsupported-database screen is a plain activity (no Hilt) whose only data action
 * is a user-started verbatim zip export of the db and -wal files, never -shm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UnsupportedDatabaseActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseOpenGate.resetForTests()
        DatabaseOpenGate.overrideForTests(DatabaseOpenGate.REASON_SCHEMA_TOO_NEW)
        deleteLiveFiles()
    }

    @After
    fun tearDown() {
        deleteLiveFiles()
        DatabaseOpenGate.resetForTests()
    }

    private fun deleteLiveFiles() {
        DatabaseOpenGate.liveDatabaseFiles(context).forEach { it.delete() }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    @Test
    fun `activity launches as a plain activity without hilt`() {
        val activity = Robolectric.buildActivity(UnsupportedDatabaseActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        assertNotNull(activity)
        assertFalse(activity.isFinishing)
        assertFalse(
            "must not be a Hilt entry point",
            activity.javaClass.annotations.any { it.annotationClass.java.name.contains("AndroidEntryPoint") }
        )
    }

    @Test
    fun `export writes db and wal bytes verbatim and never shm and leaves sources untouched`() {
        val (db, wal, shm) = DatabaseOpenGate.liveDatabaseFiles(context)
        db.parentFile?.mkdirs()
        val dbBytes = ByteArray(5000) { (it * 7).toByte() }
        val walBytes = ByteArray(3000) { (it * 13 + 1).toByte() }
        val shmBytes = ByteArray(32768) { 0x5A }
        db.writeBytes(dbBytes)
        wal.writeBytes(walBytes)
        shm.writeBytes(shmBytes)
        val before = listOf(db, wal, shm).map { sha256(it) }

        val out = ByteArrayOutputStream()
        UnsupportedDatabaseActivity.writeBackupZip(out, DatabaseOpenGate.liveDatabaseFiles(context))

        val entries = unzip(out.toByteArray())
        assertEquals(setOf(db.name, wal.name), entries.keys)
        assertFalse(entries.keys.any { it.endsWith("-shm") })
        assertArrayEquals(dbBytes, entries.getValue(db.name))
        assertArrayEquals(walBytes, entries.getValue(wal.name))
        assertEquals(before, listOf(db, wal, shm).map { sha256(it) })
        assertTrue(shm.exists())
    }

    @Test
    fun `export of a database without wal contains only the db entry`() {
        val (db, wal, _) = DatabaseOpenGate.liveDatabaseFiles(context)
        db.parentFile?.mkdirs()
        val dbBytes = ByteArray(1200) { (it % 251).toByte() }
        db.writeBytes(dbBytes)
        assertFalse(wal.exists())

        val out = ByteArrayOutputStream()
        UnsupportedDatabaseActivity.writeBackupZip(out, DatabaseOpenGate.liveDatabaseFiles(context))

        val entries = unzip(out.toByteArray())
        assertEquals(setOf(db.name), entries.keys)
        assertArrayEquals(dbBytes, entries.getValue(db.name))
    }

    @Test
    fun `activity finishes and export throws when the gate does not block`() {
        DatabaseOpenGate.resetForTests() // no override, no live file -> Fresh -> not blocked
        assertEquals(null, DatabaseOpenGate.blockedReasonOrNull(context))

        val activity = Robolectric.buildActivity(UnsupportedDatabaseActivity::class.java)
            .create()
            .get()

        assertTrue("must finish when the gate is not blocked", activity.isFinishing)
        try {
            activity.writeBackupTo(android.net.Uri.parse("content://example/document/1"))
            org.junit.Assert.fail("export must be refused while the gate is not blocked")
        } catch (e: IllegalStateException) {
            assertEquals("export not permitted", e.message)
        }
    }

    @Test
    fun `export without a main database file fails instead of writing an empty zip`() {
        val sources = DatabaseOpenGate.liveDatabaseFiles(context)
        assertFalse(sources.first().exists())
        val out = ByteArrayOutputStream()

        try {
            UnsupportedDatabaseActivity.writeBackupZip(out, sources)
            org.junit.Assert.fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertEquals("main database file missing", e.message)
        }
        assertEquals(0, out.size())
    }

    @Test
    fun `partial output cleanup runs non-cancellable and swallows delete failures`() {
        val activity = Robolectric.buildActivity(UnsupportedDatabaseActivity::class.java).create().get()
        val uri = android.net.Uri.parse("content://example/document/2")
        val calls = java.util.concurrent.atomic.AtomicInteger()

        runBlocking {
            // Each call in its own already-cancelled job: the NonCancellable cleanup body must
            // still run and a failing delete must not throw (withContext then rethrows the
            // caller's cancellation on return, which ends that job only).
            listOf<(android.net.Uri) -> Boolean>(
                { calls.incrementAndGet(); throw IllegalStateException("denied") },
                { calls.incrementAndGet(); false }
            ).forEach { del ->
                launch {
                    cancel()
                    activity.deletePartialOutput(uri, del)
                }.join()
            }
        }

        assertEquals(2, calls.get())
    }

    @Test
    fun `reason codes map to distinct reason strings`() {
        assertEquals(
            R.string.unsupported_db_reason_too_old,
            UnsupportedDatabaseActivity.reasonTextRes(DatabaseOpenGate.REASON_SCHEMA_TOO_OLD)
        )
        assertEquals(
            R.string.unsupported_db_reason_too_new,
            UnsupportedDatabaseActivity.reasonTextRes(DatabaseOpenGate.REASON_SCHEMA_TOO_NEW)
        )
        assertEquals(
            R.string.unsupported_db_reason_unreadable,
            UnsupportedDatabaseActivity.reasonTextRes(DatabaseOpenGate.REASON_OPEN_FAILED)
        )
        assertEquals(
            R.string.unsupported_db_reason_unreadable,
            UnsupportedDatabaseActivity.reasonTextRes(null)
        )
    }

    @Test
    fun `main activity redirects a blocked gate before intent handling and content`() {
        val relative = "com/yourname/expensetracker/ui/MainActivity.kt"
        val file = listOf("app/src/main/java/", "src/main/java/", "../app/src/main/java/")
            .map { File(it + relative) }
            .firstOrNull { it.isFile }
        assertNotNull("MainActivity.kt not found from the test working directory", file)
        val source = file!!.readText()
        val onCreate = source.indexOf("override fun onCreate(")
        val gate = source.indexOf("DatabaseOpenGate.blockedReasonOrNull(applicationContext)", onCreate)
        val redirect = source.indexOf("UnsupportedDatabaseActivity::class.java", onCreate)
        val handle = source.indexOf("handleIntent(intent)", onCreate)
        val content = source.indexOf("setContent {", onCreate)

        assertTrue(onCreate >= 0 && gate > onCreate)
        assertTrue(redirect > gate)
        assertTrue("redirect must precede intent handling", redirect < handle)
        assertTrue("redirect must precede setContent", redirect < content)
        assertTrue(source.contains("FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK"))
    }

    @Test
    fun `activity surface has no hilt activity room privacy gate or export coupling and exactly one entry point lookup`() {
        // R25 pin, narrowed by app-lock S21-c (owner decision 73a): the surface is the activity file plus its
        // export-auth file. Exactly ONE entry point lookup is allowed across both; every other R25 token stays out.
        val surface = listOf(
            "com/yourname/expensetracker/ui/unsupported/UnsupportedDatabaseActivity.kt",
            "com/yourname/expensetracker/ui/unsupported/UnsupportedDatabaseExportAuth.kt"
        ).map { relative ->
            val file = listOf("app/src/main/java/", "src/main/java/", "../app/src/main/java/")
                .map { File(it + relative) }
                .firstOrNull { it.isFile }
            assertNotNull("$relative not found from the test working directory", file)
            relative to file!!.readText()
        }

        surface.forEach { (relative, source) ->
            listOf("@AndroidEntryPoint", "AppDatabase", "Dao", "exportDatabase", "PrivacyGate").forEach { token ->
                assertFalse("$relative must not contain $token", source.contains(token))
            }
        }
        val lookups = surface.sumOf { (_, source) -> source.split("EntryPointAccessors.fromApplication(").size - 1 }
        assertEquals("exactly one entry point lookup across the activity surface", 1, lookups)
    }
}
