package com.yourname.expensetracker.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * J-20-1: `RestoreMaintenanceMode.isCriticalRecoveryPersisted(Context)` is the Room-free, instance-free, read-only
 * answer that gates the raw database export. It must be true only when the durable state positively decodes to
 * CRITICAL_RECOVERY_REQUIRED, and false (fail closed) for every other mode, every undecodable state and every
 * read failure. Real files and SharedPreferences are used; only the failure cases wrap the context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreMaintenanceModeCriticalRecoveryPersistedTest {

    private lateinit var context: Context

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
        sentinel().delete()
        prefs().edit().clear().commit()
    }

    private fun sentinel(): File = File(context.noBackupFilesDir, RestoreMaintenanceMode.CRITICAL_SENTINEL_FILE)

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(RestoreMaintenanceMode.PREFS_NAME, Context.MODE_PRIVATE)

    private fun writeSentinel() {
        sentinel().parentFile?.mkdirs()
        sentinel().writeBytes(byteArrayOf(1))
    }

    private fun storeMode(name: Any) {
        assertTrue(prefs().edit().putString(RestoreMaintenanceMode.KEY_MAINTENANCE_MODE, name.toString()).commit())
    }

    // ---- positive controls ---------------------------------------------------------------------------------------

    @Test
    fun `sentinel file present reads as critical recovery`() {
        writeSentinel()

        assertTrue(RestoreMaintenanceMode.isCriticalRecoveryPersisted(context))
    }

    @Test
    fun `sentinel wins over a stored NORMAL mode`() {
        writeSentinel()
        storeMode(RestoreMaintenanceMode.Mode.NORMAL)

        assertTrue(RestoreMaintenanceMode.isCriticalRecoveryPersisted(context))
    }

    @Test
    fun `prefs-only CRITICAL_RECOVERY_REQUIRED without a sentinel reads as critical recovery`() {
        assertFalse(sentinel().exists())
        storeMode(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED)

        assertTrue(RestoreMaintenanceMode.isCriticalRecoveryPersisted(context))
    }

    @Test
    fun `prefs CRITICAL with reason and timestamp metadata reads as critical recovery`() {
        storeMode(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED)
        prefs().edit()
            .putString(RestoreMaintenanceMode.KEY_CRITICAL_REASON, "SYNTHETIC_REASON")
            .putLong(RestoreMaintenanceMode.KEY_CRITICAL_TIMESTAMP, 1_716_163_200_000L)
            .commit()

        assertTrue(RestoreMaintenanceMode.isCriticalRecoveryPersisted(context))
    }

    // ---- other modes are false ------------------------------------------------------------------------------------

    @Test
    fun `every mode other than CRITICAL_RECOVERY_REQUIRED reads as not critical`() {
        val others = RestoreMaintenanceMode.Mode.values()
            .filter { it != RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED }
        assertTrue(others.size >= 10)
        for (mode in others) {
            prefs().edit().clear().commit()
            storeMode(mode)

            assertFalse("$mode must not enable the raw export", RestoreMaintenanceMode.isCriticalRecoveryPersisted(context))
        }
    }

    @Test
    fun `NORMAL with a worker-resume-pending flag is restart-required and not critical`() {
        storeMode(RestoreMaintenanceMode.Mode.NORMAL)
        prefs().edit().putBoolean(RestoreMaintenanceMode.KEY_WORKER_RESUME_PENDING, true).commit()

        assertFalse(RestoreMaintenanceMode.isCriticalRecoveryPersisted(context))
    }

    // ---- no state / undecodable state ------------------------------------------------------------------------------

    @Test
    fun `no persisted state reads as not critical`() {
        assertFalse(sentinel().exists())
        assertTrue(prefs().all.isEmpty())

        assertFalse(RestoreMaintenanceMode.isCriticalRecoveryPersisted(context))
    }

    @Test
    fun `a stored mode name that is not a mode reads as not critical`() {
        storeMode("NOT_A_MODE")

        assertFalse(RestoreMaintenanceMode.isCriticalRecoveryPersisted(context))
    }

    @Test
    fun `a blank stored mode reads as not critical`() {
        storeMode("  ")

        assertFalse(RestoreMaintenanceMode.isCriticalRecoveryPersisted(context))
    }

    @Test
    fun `a stored mode of the wrong type reads as not critical`() {
        assertTrue(prefs().edit().putInt(RestoreMaintenanceMode.KEY_MAINTENANCE_MODE, 7).commit())

        assertFalse(RestoreMaintenanceMode.isCriticalRecoveryPersisted(context))
    }

    @Test
    fun `critical metadata without a mode is invalid and reads as not critical`() {
        prefs().edit().putString(RestoreMaintenanceMode.KEY_CRITICAL_REASON, "SYNTHETIC_REASON").commit()

        assertFalse(RestoreMaintenanceMode.isCriticalRecoveryPersisted(context))
    }

    @Test
    fun `critical metadata on a non-critical mode is invalid and reads as not critical`() {
        storeMode(RestoreMaintenanceMode.Mode.NORMAL)
        prefs().edit().putLong(RestoreMaintenanceMode.KEY_CRITICAL_TIMESTAMP, 5L).commit()

        assertFalse(RestoreMaintenanceMode.isCriticalRecoveryPersisted(context))
    }

    @Test
    fun `a non-true worker-resume-pending value is invalid and reads as not critical`() {
        storeMode(RestoreMaintenanceMode.Mode.NORMAL)
        prefs().edit().putBoolean(RestoreMaintenanceMode.KEY_WORKER_RESUME_PENDING, false).commit()

        assertFalse(RestoreMaintenanceMode.isCriticalRecoveryPersisted(context))
    }

    // ---- read failures fail closed ----------------------------------------------------------------------------------

    @Test
    fun `an exception while checking the sentinel reads as not critical`() {
        // Prefs say CRITICAL, but the sentinel could not be checked: the state is unreadable, never critical.
        storeMode(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED)
        val failing = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = throw IOException("synthetic")
        }

        assertFalse(RestoreMaintenanceMode.isCriticalRecoveryPersisted(failing))
    }

    @Test
    fun `an exception while reading the preferences reads as not critical`() {
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                throw IllegalStateException("synthetic")
        }

        assertFalse(RestoreMaintenanceMode.isCriticalRecoveryPersisted(failing))
    }

    @Test
    fun `an exception while snapshotting the preferences reads as not critical`() {
        val brokenPrefs = mockk<SharedPreferences>()
        every { brokenPrefs.all } throws IllegalStateException("synthetic")
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = brokenPrefs
        }

        assertFalse(RestoreMaintenanceMode.isCriticalRecoveryPersisted(failing))
    }

    @Test
    fun `a sentinel decides without touching the preferences even when they are unreadable`() {
        writeSentinel()
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                throw IllegalStateException("synthetic")
        }

        assertTrue(RestoreMaintenanceMode.isCriticalRecoveryPersisted(failing))
    }

    // ---- read-only ---------------------------------------------------------------------------------------------------

    @Test
    fun `the read does not create or modify any persisted state`() {
        storeMode(RestoreMaintenanceMode.Mode.NORMAL)
        val before = prefs().all.toMap()

        RestoreMaintenanceMode.isCriticalRecoveryPersisted(context)

        assertFalse("reading must not create the sentinel", sentinel().exists())
        assertTrue(before == prefs().all.toMap())
    }
}
