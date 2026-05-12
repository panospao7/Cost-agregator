package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
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
        val mode = RestoreMaintenanceMode(prefs)
        val barrier = DatabaseWriteBarrier(mode)

        // Should not throw
        barrier.checkWritesAllowed("test_operation")
    }

    @Test
    fun `write barrier blocks writes in RESTORE_PREPARING mode`() {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "RESTORE_PREPARING"
        val mode = RestoreMaintenanceMode(prefs)
        val barrier = DatabaseWriteBarrier(mode)

        assertThrows(IllegalStateException::class.java) {
            barrier.checkWritesAllowed("test_operation")
        }
    }

    @Test
    fun `write barrier blocks writes in BACKUP_EXPORTING mode`() {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "BACKUP_EXPORTING"
        val mode = RestoreMaintenanceMode(prefs)
        val barrier = DatabaseWriteBarrier(mode)

        assertThrows(IllegalStateException::class.java) {
            barrier.checkWritesAllowed("test_operation")
        }
    }

    @Test
    fun `write barrier blocks writes in RESTORE_COMPLETE_RESTART_REQUIRED mode`() {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "RESTORE_COMPLETE_RESTART_REQUIRED"
        val mode = RestoreMaintenanceMode(prefs)
        val barrier = DatabaseWriteBarrier(mode)

        assertThrows(IllegalStateException::class.java) {
            barrier.checkWritesAllowed("test_operation")
        }
    }

    @Test
    fun `write barrier blocks writes in RESTORE_SWAPPING mode`() {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "RESTORE_SWAPPING"
        val mode = RestoreMaintenanceMode(prefs)
        val barrier = DatabaseWriteBarrier(mode)

        assertThrows(IllegalStateException::class.java) {
            barrier.checkWritesAllowed("test_operation")
        }
    }

    @Test
    fun `all non-NORMAL modes block writes`() {
        val nonNormalModes = listOf(
            "RESTORE_PREPARING", "RESTORE_SWAPPING", "RESTORE_VERIFYING",
            "BACKUP_EXPORTING", "RESTORE_COMPLETE_RESTART_REQUIRED", "ASSETS_RESTORING"
        )

        nonNormalModes.forEach { modeName ->
            val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
            every { prefs.getString(any(), any()) } returns modeName
            val mode = RestoreMaintenanceMode(prefs)
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
