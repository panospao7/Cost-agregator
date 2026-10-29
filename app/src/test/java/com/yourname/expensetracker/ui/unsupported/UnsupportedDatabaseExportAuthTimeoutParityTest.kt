package com.yourname.expensetracker.ui.unsupported

import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S22-b.2 (F6-ii): the export prompt watchdog of the unsupported-database screen uses the same bound as the app lock
 * controller's prompt watchdog. The screen cannot inject the controller, so the two constants are kept equal by this
 * pin: five minutes, a positive finite bound.
 */
class UnsupportedDatabaseExportAuthTimeoutParityTest {

    @Test
    fun s22_f6_exportPromptTimeout_equalsTheControllerPromptWatchdog_andIsFiveMinutes() {
        assertEquals(AppLockController.PROMPT_WATCHDOG_MILLIS, EXPORT_AUTH_PROMPT_TIMEOUT_MILLIS)
        assertEquals(300_000L, EXPORT_AUTH_PROMPT_TIMEOUT_MILLIS)
        assertEquals(300_000L, AppLockController.PROMPT_WATCHDOG_MILLIS)
    }

    @Test
    fun s22_f6_exportPromptTimeout_isPositiveAndFinite() {
        assertTrue(EXPORT_AUTH_PROMPT_TIMEOUT_MILLIS > 0L)
        assertTrue(EXPORT_AUTH_PROMPT_TIMEOUT_MILLIS < Long.MAX_VALUE)
    }
}
