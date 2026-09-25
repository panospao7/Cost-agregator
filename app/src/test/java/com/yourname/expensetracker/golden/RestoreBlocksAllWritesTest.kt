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
import java.nio.file.Files

/**
 * Golden Scenario Test 9: Restore Blocks All Writes
 *
 * Verifies that when RestoreMaintenanceMode is active,
 * DatabaseWriteBarrier blocks ALL write operations.
 * This is the fundamental safety contract for backup/restore.
 */
class RestoreBlocksAllWritesTest {

    private fun maintenanceMode(modeValue: String): RestoreMaintenanceMode {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.all } returns mapOf("current_mode" to modeValue)
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.noBackupFilesDir } returns
            Files.createTempDirectory("golden-maintenance-").toFile().apply {
                deleteOnExit()
            }
        val mode = RestoreMaintenanceMode(context, FakeTimeProvider(1716163200000L))
        assertEquals(RestoreMaintenanceMode.Mode.valueOf(modeValue), mode.currentMode())
        return mode
    }

    @Test
    fun `write barrier allows writes in NORMAL mode`() {
        val mode = maintenanceMode("NORMAL")
        val barrier = DatabaseWriteBarrier(mode)

        // Should not throw
        barrier.checkWritesAllowed("test_operation")
    }

    @Test
    fun `write barrier blocks writes in RESTORE_PREPARING mode`() {
        val mode = maintenanceMode("RESTORE_PREPARING")
        val barrier = DatabaseWriteBarrier(mode)

        assertThrows(IllegalStateException::class.java) {
            barrier.checkWritesAllowed("test_operation")
        }
    }

    @Test
    fun `write barrier blocks writes in BACKUP_EXPORTING mode`() {
        val mode = maintenanceMode("BACKUP_EXPORTING")
        val barrier = DatabaseWriteBarrier(mode)

        assertThrows(IllegalStateException::class.java) {
            barrier.checkWritesAllowed("test_operation")
        }
    }

    @Test
    fun `write barrier blocks writes in RESTORE_COMPLETE_RESTART_REQUIRED mode`() {
        val mode = maintenanceMode("RESTORE_COMPLETE_RESTART_REQUIRED")
        val barrier = DatabaseWriteBarrier(mode)

        assertThrows(IllegalStateException::class.java) {
            barrier.checkWritesAllowed("test_operation")
        }
    }

    @Test
    fun `write barrier blocks writes in RESTORE_SWAPPING mode`() {
        val mode = maintenanceMode("RESTORE_SWAPPING")
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
            val mode = maintenanceMode(modeName)
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
