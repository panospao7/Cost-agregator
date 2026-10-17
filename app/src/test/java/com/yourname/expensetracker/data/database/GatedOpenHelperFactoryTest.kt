package com.yourname.expensetracker.data.database

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode.Mode
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * R25/R27: the gated open helper refuses a (re)open when the open gate blocked the file or a
 * restore swap is in progress, and otherwise delegates untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GatedOpenHelperFactoryTest {

    private lateinit var context: Context

    private class FakeDelegateFactory : SupportSQLiteOpenHelper.Factory {
        var writableCalls = 0
        var readableCalls = 0
        var closeCalls = 0
        var walCalls = 0
        var databaseOpen = true
        val database: SupportSQLiteDatabase = mockk(relaxed = true)

        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            every { database.isOpen } answers { databaseOpen }
            return object : SupportSQLiteOpenHelper {
                override val databaseName: String? = "fake-db"
                override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                    walCalls++
                }
                override val writableDatabase: SupportSQLiteDatabase
                    get() {
                        writableCalls++
                        return database
                    }
                override val readableDatabase: SupportSQLiteDatabase
                    get() {
                        readableCalls++
                        return database
                    }
                override fun close() {
                    closeCalls++
                }
            }
        }
    }

    /** Context whose preferences cannot be read; application context resolves to itself. */
    private class UnreadablePrefsContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            throw IllegalStateException("prefs unavailable")
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        reset()
    }

    @After
    fun tearDown() {
        reset()
    }

    private fun reset() {
        DatabaseOpenGate.liveDatabaseFiles(context).forEach { it.delete() }
        DatabaseOpenGate.resetForTests()
        RestoreMaintenanceMode.setSwapLatchForTests(false)
        context.getSharedPreferences(RestoreMaintenanceMode.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.noBackupFilesDir, RestoreMaintenanceMode.CRITICAL_SENTINEL_FILE).delete()
    }

    private fun persistMode(mode: Mode) {
        assertTrue(
            context.getSharedPreferences(RestoreMaintenanceMode.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(RestoreMaintenanceMode.KEY_MAINTENANCE_MODE, mode.name).commit()
        )
    }

    private fun configuration(): SupportSQLiteOpenHelper.Configuration =
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("fake-db")
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

    private fun helper(
        delegate: FakeDelegateFactory,
        ctx: Context = context
    ): SupportSQLiteOpenHelper = GatedOpenHelperFactory(ctx, delegate).create(configuration())

    private fun assertRefused(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected DatabaseOpenBlockedException($code)")
        } catch (e: DatabaseOpenBlockedException) {
            assertEquals(code, e.reasonCode)
            assertEquals(code, e.message)
            assertFalse("must not be a SQLiteException", e is android.database.sqlite.SQLiteException)
        }
    }

    @Test
    fun blockedGateRefusesWritableAndReadableAndNeverReachesTheDelegate() {
        File(context.getDatabasePath(AppDatabase.DATABASE_NAME).path).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4096) { 0x5A })
        }
        val delegate = FakeDelegateFactory()
        val helper = helper(delegate)

        assertRefused("DB_OPEN_FAILED") { helper.writableDatabase }
        assertRefused("DB_OPEN_FAILED") { helper.readableDatabase }
        assertEquals(0, delegate.writableCalls)
        assertEquals(0, delegate.readableCalls)
    }

    @Test
    fun swappingIsRefusedAndEveryOtherPersistedModeIsAllowed() {
        for (mode in Mode.values()) {
            reset()
            persistMode(mode)
            val delegate = FakeDelegateFactory()
            val helper = helper(delegate)

            if (mode == Mode.RESTORE_SWAPPING) {
                assertRefused("DB_OPEN_REFUSED_DURING_SWAP") { helper.writableDatabase }
                assertEquals("mode=$mode", 0, delegate.writableCalls)
            } else {
                assertSame("mode=$mode", delegate.database, helper.writableDatabase)
                assertEquals("mode=$mode", 1, delegate.writableCalls)
            }
        }
    }

    @Test
    fun namedNonSwappingMaintenanceModesAreAllowed() {
        val allowed = listOf(
            Mode.RESTORE_STAGING,
            Mode.RESTORE_VERIFYING,
            Mode.RESTORE_ROLLING_BACK,
            Mode.RESETTING_DATABASE,
            Mode.ASSETS_RESTORING,
            Mode.BACKUP_EXPORTING,
            Mode.CRITICAL_RECOVERY_REQUIRED
        )
        for (mode in allowed) {
            reset()
            persistMode(mode)
            val delegate = FakeDelegateFactory()
            assertSame("mode=$mode", delegate.database, helper(delegate).readableDatabase)
            assertEquals("mode=$mode", 1, delegate.readableCalls)
        }
    }

    @Test
    fun criticalSentinelOverridesPersistedSwapping() {
        persistMode(Mode.RESTORE_SWAPPING)
        File(context.noBackupFilesDir, RestoreMaintenanceMode.CRITICAL_SENTINEL_FILE).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val delegate = FakeDelegateFactory()
        assertSame(delegate.database, helper(delegate).writableDatabase)
    }

    @Test
    fun latchRefusesEvenWhenPersistedModeIsNormalOrUnreadable() {
        RestoreMaintenanceMode.setSwapLatchForTests(true)

        val normalDelegate = FakeDelegateFactory()
        assertRefused("DB_OPEN_REFUSED_DURING_SWAP") { helper(normalDelegate).writableDatabase }
        assertEquals(0, normalDelegate.writableCalls)

        val unreadableDelegate = FakeDelegateFactory()
        val unreadable = helper(unreadableDelegate, UnreadablePrefsContext(context))
        assertRefused("DB_OPEN_REFUSED_DURING_SWAP") { unreadable.writableDatabase }
        assertEquals(0, unreadableDelegate.writableCalls)
    }

    @Test
    fun unreadablePreferencesWithoutLatchAreAllowed() {
        val delegate = FakeDelegateFactory()
        val helper = helper(delegate, UnreadablePrefsContext(context))
        assertSame(delegate.database, helper.writableDatabase)
        assertEquals(1, delegate.writableCalls)
    }

    @Test
    fun alreadyOpenDatabaseUsesTheFastPathWithoutRechecking() {
        val delegate = FakeDelegateFactory()
        val helper = helper(delegate)
        assertSame(delegate.database, helper.writableDatabase)

        persistMode(Mode.RESTORE_SWAPPING)
        RestoreMaintenanceMode.setSwapLatchForTests(true)

        assertSame(delegate.database, helper.writableDatabase)
        assertSame(delegate.database, helper.readableDatabase)
        assertEquals(1, delegate.writableCalls)
        assertEquals(0, delegate.readableCalls)
    }

    @Test
    fun raisedRollbackOrResetLatchRefusesOpenUntilNextModeCommitClearsIt() {
        // Rollback and reset raise the latch without persisting RESTORE_SWAPPING.
        persistMode(Mode.RESTORE_ROLLING_BACK)
        RestoreMaintenanceMode.raiseSwapLatch()
        assertTrue(RestoreMaintenanceMode.isSwapLatched())

        val delegate = FakeDelegateFactory()
        val helper = helper(delegate)
        assertRefused("DB_OPEN_REFUSED_DURING_SWAP") { helper.writableDatabase }
        assertRefused("DB_OPEN_REFUSED_DURING_SWAP") { helper.readableDatabase }
        assertEquals(0, delegate.writableCalls)
        assertEquals(0, delegate.readableCalls)

        // The next durable mode commit (modelled by clearing the latch) admits opens again.
        RestoreMaintenanceMode.setSwapLatchForTests(false)
        assertSame(delegate.database, helper.writableDatabase)
    }

    @Test
    fun cachedReadOnlyHandleDoesNotSatisfyAWritableRequest() {
        val delegate = FakeDelegateFactory()
        every { delegate.database.isReadOnly } returns true
        val helper = helper(delegate)

        assertSame(delegate.database, helper.readableDatabase)
        assertEquals(1, delegate.readableCalls)

        // Readable requests may reuse the cached read-only handle.
        assertSame(delegate.database, helper.readableDatabase)
        assertEquals(1, delegate.readableCalls)

        // A writable request must go back through the gate and the delegate.
        assertSame(delegate.database, helper.writableDatabase)
        assertEquals(1, delegate.writableCalls)
    }

    @Test
    fun closedDatabaseIsRecheckedAndRefusedDuringSwap() {
        val delegate = FakeDelegateFactory()
        val helper = helper(delegate)
        assertSame(delegate.database, helper.writableDatabase)

        delegate.databaseOpen = false
        persistMode(Mode.RESTORE_SWAPPING)

        assertRefused("DB_OPEN_REFUSED_DURING_SWAP") { helper.writableDatabase }
        assertEquals(1, delegate.writableCalls)
    }

    @Test
    fun closeNameAndWalDelegateWithoutChecks() {
        RestoreMaintenanceMode.setSwapLatchForTests(true)
        persistMode(Mode.RESTORE_SWAPPING)
        val delegate = FakeDelegateFactory()
        val helper = helper(delegate)

        assertEquals("fake-db", helper.databaseName)
        helper.setWriteAheadLoggingEnabled(true)
        helper.close()

        assertEquals(1, delegate.walCalls)
        assertEquals(1, delegate.closeCalls)
    }

    @Test
    fun gatedFactoryIsConstructedOnlyInDatabaseModule() {
        val mainRoot = listOf("app/src/main/java", "../app/src/main/java")
            .map { File(it) }
            .firstOrNull { it.isDirectory }
            ?: error("main source root not found from ${File(".").absolutePath}")
        val constructionSite = Regex("""(?<!class )GatedOpenHelperFactory\(""")

        val offenders = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { constructionSite.containsMatchIn(it.readText()) }
            .map { it.invariantSeparatorsPath }
            .toList()

        assertEquals(1, offenders.size)
        assertTrue(
            "unexpected construction site: $offenders",
            offenders.single().endsWith("com/yourname/expensetracker/di/DatabaseModule.kt")
        )
    }
}
