package com.yourname.expensetracker.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode.Mode
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * R25/R27: the pure persisted-state decoder shared by [RestoreMaintenanceMode] and
 * [RestoreMaintenanceModePeek], and the process-wide swap latch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreMaintenanceModeDecoderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetPersistedState()
        RestoreMaintenanceMode.setSwapLatchForTests(false)
    }

    @After
    fun tearDown() {
        resetPersistedState()
        RestoreMaintenanceMode.setSwapLatchForTests(false)
    }

    private fun resetPersistedState() {
        context.getSharedPreferences(RestoreMaintenanceMode.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.noBackupFilesDir, RestoreMaintenanceMode.CRITICAL_SENTINEL_FILE).delete()
    }

    private fun decode(
        snapshot: Map<String, *>?,
        sentinel: Boolean? = false,
        prefsAvailable: Boolean = true
    ) = RestoreMaintenanceMode.decodePersistedState(sentinel, snapshot, prefsAvailable)

    private fun assertState(
        raw: Mode,
        pending: Boolean,
        effective: Mode,
        result: RestoreMaintenanceMode.ReadResult
    ) {
        assertNull(result.failureReason)
        assertEquals(RestoreMaintenanceMode.PersistedState(raw, pending, effective), result.state)
    }

    private fun assertFailure(reason: String, result: RestoreMaintenanceMode.ReadResult) {
        assertNull(result.state)
        assertEquals(reason, result.failureReason)
    }

    private val mode = RestoreMaintenanceMode.KEY_MAINTENANCE_MODE
    private val pending = RestoreMaintenanceMode.KEY_WORKER_RESUME_PENDING
    private val reason = RestoreMaintenanceMode.KEY_CRITICAL_REASON
    private val timestamp = RestoreMaintenanceMode.KEY_CRITICAL_TIMESTAMP

    @Test
    fun sentinelDecidesBeforePreferences() {
        val critical = Mode.CRITICAL_RECOVERY_REQUIRED
        assertState(critical, false, critical, decode(null, sentinel = true, prefsAvailable = false))
        assertState(critical, false, critical, decode(mapOf(mode to "NORMAL"), sentinel = true))
        assertFailure("RESTORE_MAINTENANCE_MODE_READ_FAILED", decode(emptyMap<String, Any>(), sentinel = null))
    }

    @Test
    fun unavailableOrUnreadablePreferencesFail() {
        assertFailure(
            "RESTORE_MAINTENANCE_MODE_PREFERENCES_UNAVAILABLE",
            decode(null, prefsAvailable = false)
        )
        assertFailure("RESTORE_MAINTENANCE_MODE_READ_FAILED", decode(null))
    }

    @Test
    fun emptyPreferencesAreNormal() {
        assertState(Mode.NORMAL, false, Mode.NORMAL, decode(emptyMap<String, Any>()))
    }

    @Test
    fun everyPlainModeDecodesToItself() {
        for (m in Mode.values()) {
            val result = decode(mapOf(mode to m.name))
            assertState(m, false, m, result)
        }
    }

    @Test
    fun orphanMarkersWithoutModeAreInvalid() {
        val invalid = "RESTORE_MAINTENANCE_MODE_STATE_INVALID"
        assertFailure(invalid, decode(mapOf(pending to true)))
        assertFailure(invalid, decode(mapOf(reason to "r")))
        assertFailure(invalid, decode(mapOf(timestamp to 5L)))
    }

    @Test
    fun malformedModeValuesAreInvalid() {
        val invalid = "RESTORE_MAINTENANCE_MODE_STATE_INVALID"
        assertFailure(invalid, decode(mapOf(mode to 3)))
        assertFailure(invalid, decode(mapOf(mode to "")))
        assertFailure(invalid, decode(mapOf(mode to "   ")))
        assertFailure(invalid, decode(mapOf(mode to "NOT_A_MODE")))
        assertFailure(invalid, decode(mapOf<String, Any?>(mode to null)))
    }

    @Test
    fun resumePendingRules() {
        val invalid = "RESTORE_MAINTENANCE_MODE_STATE_INVALID"
        assertState(
            Mode.NORMAL, true, Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
            decode(mapOf(mode to "NORMAL", pending to true))
        )
        assertFailure(invalid, decode(mapOf(mode to "NORMAL", pending to false)))
        assertFailure(invalid, decode(mapOf(mode to "NORMAL", pending to "true")))
        assertFailure(invalid, decode(mapOf(mode to "RESTORE_SWAPPING", pending to true)))
        assertState(
            Mode.CRITICAL_RECOVERY_REQUIRED, true, Mode.CRITICAL_RECOVERY_REQUIRED,
            decode(mapOf(mode to "CRITICAL_RECOVERY_REQUIRED", pending to true))
        )
    }

    @Test
    fun criticalMetadataRules() {
        val invalid = "RESTORE_MAINTENANCE_MODE_STATE_INVALID"
        assertState(
            Mode.CRITICAL_RECOVERY_REQUIRED, false, Mode.CRITICAL_RECOVERY_REQUIRED,
            decode(mapOf(mode to "CRITICAL_RECOVERY_REQUIRED", reason to "r", timestamp to 5L))
        )
        assertFailure(invalid, decode(mapOf(mode to "RESTORE_SWAPPING", reason to "r")))
        assertFailure(invalid, decode(mapOf(mode to "NORMAL", timestamp to 5L)))
    }

    @Test
    fun decoderMatchesLiveClassOnRepresentativeStates() {
        val prefs = context.getSharedPreferences(RestoreMaintenanceMode.PREFS_NAME, Context.MODE_PRIVATE)
        val sentinelFile = File(context.noBackupFilesDir, RestoreMaintenanceMode.CRITICAL_SENTINEL_FILE)

        val cases: List<Pair<Map<String, Any>, Mode>> = listOf(
            emptyMap<String, Any>() to Mode.NORMAL,
            mapOf<String, Any>(mode to "RESTORE_STAGING") to Mode.RESTORE_STAGING,
            mapOf<String, Any>(mode to "RESTORE_SWAPPING") to Mode.RESTORE_SWAPPING,
            mapOf<String, Any>(mode to "NORMAL", pending to true) to Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
            mapOf<String, Any>(mode to "RESTORE_SWAPPING", pending to true) to Mode.CRITICAL_RECOVERY_REQUIRED,
            mapOf<String, Any>(mode to "BOGUS") to Mode.CRITICAL_RECOVERY_REQUIRED
        )
        for ((stored, expected) in cases) {
            resetPersistedState()
            val editor = prefs.edit()
            for ((k, v) in stored) {
                when (v) {
                    is String -> editor.putString(k, v)
                    is Boolean -> editor.putBoolean(k, v)
                }
            }
            assertTrue(editor.commit())

            val decoded = RestoreMaintenanceMode.decodePersistedState(false, prefs.all.toMap(), true)
            val decodedEffective = decoded.state?.effectiveMode ?: Mode.CRITICAL_RECOVERY_REQUIRED
            assertEquals(expected, decodedEffective)
            // The peek reads before the live class can latch CRITICAL into the same preferences.
            assertEquals(decoded.state?.effectiveMode, RestoreMaintenanceModePeek.effectiveMode(context))
            assertEquals(
                expected,
                RestoreMaintenanceMode(context, FakeTimeProvider(1716163200000L)).currentMode()
            )
        }

        resetPersistedState()
        sentinelFile.parentFile?.mkdirs()
        sentinelFile.writeBytes(byteArrayOf(1))
        assertEquals(Mode.CRITICAL_RECOVERY_REQUIRED, RestoreMaintenanceModePeek.effectiveMode(context))
    }

    @Test
    fun peekReportsSwappingOnlyForSwappingState() {
        val prefs = context.getSharedPreferences(RestoreMaintenanceMode.PREFS_NAME, Context.MODE_PRIVATE)
        assertFalse(RestoreMaintenanceModePeek.isSwapping(context))
        assertTrue(prefs.edit().putString(mode, "RESTORE_SWAPPING").commit())
        assertTrue(RestoreMaintenanceModePeek.isSwapping(context))
        assertTrue(prefs.edit().putString(mode, "RESTORE_VERIFYING").commit())
        assertFalse(RestoreMaintenanceModePeek.isSwapping(context))
        assertTrue(prefs.edit().putString(mode, "garbage").commit())
        assertNull(RestoreMaintenanceModePeek.effectiveMode(context))
        assertFalse(RestoreMaintenanceModePeek.isSwapping(context))
    }

    @Test
    fun swapLatchIsRaisedBySwappingAndClearedByAnyOtherMode() {
        val maintenance = RestoreMaintenanceMode(context, FakeTimeProvider(1716163200000L))
        assertFalse(RestoreMaintenanceMode.isSwapLatched())

        maintenance.enter(Mode.RESTORE_STAGING)
        assertFalse(RestoreMaintenanceMode.isSwapLatched())

        maintenance.enter(Mode.RESTORE_SWAPPING)
        assertTrue(RestoreMaintenanceMode.isSwapLatched())
        assertTrue(RestoreMaintenanceModePeek.isSwapping(context))

        maintenance.enter(Mode.RESTORE_VERIFYING)
        assertFalse(RestoreMaintenanceMode.isSwapLatched())
        assertFalse(RestoreMaintenanceModePeek.isSwapping(context))

        maintenance.enter(Mode.RESTORE_SWAPPING)
        assertTrue(RestoreMaintenanceMode.isSwapLatched())
        maintenance.exit(forceRestartRequired = true)
        assertFalse(RestoreMaintenanceMode.isSwapLatched())
    }
}
