package com.yourname.expensetracker.golden

import android.content.Context
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Golden Scenario Test 9: Restore Blocks All Writes
 *
 * Verifies that when RestoreMaintenanceMode is active,
 * DatabaseWriteBarrier blocks ALL write operations.
 * This is the fundamental safety contract for backup/restore.
 */
class RestoreBlocksAllWritesTest {

    @Test
    fun `write barrier allows writes in NORMAL mode`() {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "NORMAL"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val mode = RestoreMaintenanceMode(context, FakeTimeProvider(1716163200000L))
        val barrier = DatabaseWriteBarrier(mode)

        // Should not throw
        barrier.checkWritesAllowed("test_operation")
    }

    @Test
    fun `write barrier blocks writes in RESTORE_PREPARING mode`() {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "RESTORE_PREPARING"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val mode = RestoreMaintenanceMode(context, FakeTimeProvider(1716163200000L))
        val barrier = DatabaseWriteBarrier(mode)

        assertThrows(IllegalStateException::class.java) {
            barrier.checkWritesAllowed("test_operation")
        }
    }

    @Test
    fun `write barrier blocks writes in BACKUP_EXPORTING mode`() {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "BACKUP_EXPORTING"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val mode = RestoreMaintenanceMode(context, FakeTimeProvider(1716163200000L))
        val barrier = DatabaseWriteBarrier(mode)

        assertThrows(IllegalStateException::class.java) {
            barrier.checkWritesAllowed("test_operation")
        }
    }

    @Test
    fun `write barrier blocks writes in RESTORE_COMPLETE_RESTART_REQUIRED mode`() {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "RESTORE_COMPLETE_RESTART_REQUIRED"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val mode = RestoreMaintenanceMode(context, FakeTimeProvider(1716163200000L))
        val barrier = DatabaseWriteBarrier(mode)

        assertThrows(IllegalStateException::class.java) {
            barrier.checkWritesAllowed("test_operation")
        }
    }

    @Test
    fun `write barrier blocks writes in RESTORE_SWAPPING mode`() {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "RESTORE_SWAPPING"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val mode = RestoreMaintenanceMode(context, FakeTimeProvider(1716163200000L))
        val barrier = DatabaseWriteBarrier(mode)

        assertThrows(IllegalStateException::class.java) {
            barrier.checkWritesAllowed("test_operation")
        }
    }

    @Test
    fun `all non-NORMAL modes block writes`() {
        val nonNormalModes = listOf(
            "RESTORE_PREPARING", "RESTORE_SWAPPING", "RESTORE_VERIFYING",
            "BACKUP_EXPORTING", "RESTORE_COMPLETE_RESTART_REQUIRED"
        )

        nonNormalModes.forEach { modeName ->
            val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
            every { prefs.getString(any(), any()) } returns modeName
            val context = mockk<Context>(relaxed = true)
            every { context.getSharedPreferences(any(), any()) } returns prefs
            val mode = RestoreMaintenanceMode(context, FakeTimeProvider(1716163200000L))
            val barrier = DatabaseWriteBarrier(mode)

            try {
                barrier.checkWritesAllowed("test_$modeName")
                fail("Expected IllegalStateException for mode $modeName")
            } catch (e: IllegalStateException) {
                // Expected
            }
        }
    }
}
