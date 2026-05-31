package com.yourname.expensetracker.ui.screens.backup

import android.content.Context
import android.net.Uri
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import com.yourname.expensetracker.domain.backup.DatabaseImportResult
import com.yourname.expensetracker.domain.backup.DatabaseStats
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreViewModelTest : ViewModelTestUtils() {

    private val context = mockk<Context>(relaxed = true)
    private val databaseBackupRepository = mockk<DatabaseBackupRepository>(relaxed = true)
    private val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true).also {
        every { it.isWritesAllowed() } returns true
    }

    /** A minimal valid .costbackup byte stream: COSTBACKUP1 magic + format version 1 + body. */
    private fun validBundleBytes(bodySize: Int = 64): ByteArray {
        val magic = "COSTBACKUP1".toByteArray(Charsets.US_ASCII)
        val version = byteArrayOf(0x00, 0x01)
        return magic + version + ByteArray(bodySize) { it.toByte() }
    }

    private fun bundleInputStream(bodySize: Int = 64) =
        java.io.ByteArrayInputStream(validBundleBytes(bodySize))

    @Before
    override fun setup() {
        super.setup()
        coEvery { databaseBackupRepository.getDatabaseStats() } returns DatabaseStats(
            transactionCount = 0,
            categoryCount = 0,
            merchantCount = 0,
            pendingReviewCount = 0,
            lastBackupDate = null
        )
    }

    private fun createViewModel(): BackupRestoreViewModel {
        return BackupRestoreViewModel(context, databaseBackupRepository, restoreMaintenanceMode)
    }

    @Test
    fun `initial state has no backup info when no prior backup`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertFalse(state.isBackingUp)
        assertFalse(state.isRestoring)
        assertNull(state.lastBackupDate)
        assertNull(state.errorMessage)
        assertNull(state.successMessage)
    }

    @Test
    fun `createBackup sets isBackingUp and shows success on completion`() = runTest(testDispatcher) {
        val backupFile = File("/tmp/test_backup.costbackup")
        coEvery { databaseBackupRepository.createCostBackup(any(), any(), any(), any()) } returns Result.success(backupFile)

        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup("test-password")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isBackingUp)
        assertNotNull(state.successMessage)
        assertTrue(state.successMessage!!.contains("Backup created"))
        assertNull(state.errorMessage)
    }

    @Test
    fun `createBackup shows error when repository fails`() = runTest(testDispatcher) {
        coEvery { databaseBackupRepository.createCostBackup(any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("Storage full"))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup("test-password")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isBackingUp)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Backup failed"))
        assertNull(state.successMessage)
    }

    @Test
    fun `createBackup with blank password shows error immediately`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup("")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isBackingUp)
        assertEquals("Password cannot be empty", state.errorMessage)
    }

    @Test
    fun `restoreBackup succeeds and sets restartRequired`() = runTest(testDispatcher) {
        val uri = Uri.parse("content://backups/test.costbackup")
        coEvery { databaseBackupRepository.restoreCostBackup(any(), any()) } returns
            Result.success(DatabaseImportResult.SuccessNeedsRestart(
                com.yourname.expensetracker.domain.backup.DatabaseImportSummary(
                    transactionCount = 10,
                    categoryCount = 5,
                    merchantCount = 3,
                    pendingReviewCount = 0,
                    budgetCount = 0,
                    receiptCount = 0,
                    warrantyCount = 0,
                    groupCount = 0,
                    subscriptionCount = 0,
                    savingsGoalCount = 0,
                    allTableCounts = emptyMap()
                )
            ))
        every { context.contentResolver.openInputStream(uri) } returns bundleInputStream()

        val vm = createViewModel()
        advanceUntilIdle()
        vm.restoreBackup(uri, "test-password")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isRestoring)
        assertNotNull(state.successMessage)
        assertTrue(state.restartRequired)
    }

    @Test
    fun `clearError resets error message`() = runTest(testDispatcher) {
        coEvery { databaseBackupRepository.createCostBackup(any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("Error"))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup("test-password")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.errorMessage)

        vm.clearError()
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `dismissRestartRequired does NOT clear the restart-required flag`() = runTest(testDispatcher) {
        // P7-CURRENT-019: restart-required is a global, non-dismissible lock. The deprecated
        // dismiss call must be a no-op so a caller/test cannot hide the banner while writes
        // remain globally blocked.
        val uri = Uri.parse("content://backups/test.costbackup")
        coEvery { databaseBackupRepository.restoreCostBackup(any(), any()) } returns
            Result.success(DatabaseImportResult.SuccessNeedsRestart(
                com.yourname.expensetracker.domain.backup.DatabaseImportSummary(
                    transactionCount = 1,
                    categoryCount = 0,
                    merchantCount = 0,
                    pendingReviewCount = 0,
                    budgetCount = 0,
                    receiptCount = 0,
                    warrantyCount = 0,
                    groupCount = 0,
                    subscriptionCount = 0,
                    savingsGoalCount = 0,
                    allTableCounts = emptyMap()
                )
            ))
        every { context.contentResolver.openInputStream(uri) } returns bundleInputStream()

        val vm = createViewModel()
        advanceUntilIdle()
        vm.restoreBackup(uri, "test-password")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.restartRequired)

        @Suppress("DEPRECATION")
        vm.dismissRestartRequired()
        assertTrue(
            "dismissRestartRequired() must be a no-op — the restart-required lock cannot be dismissed",
            vm.uiState.value.restartRequired
        )
    }

    // ── P7-CURRENT-017: restore URI header + size preflight ───────

    @Test
    fun `copyBackupWithPreflight accepts a valid header and copies body`() = runTest(testDispatcher) {
        val vm = createViewModel()
        val dest = File.createTempFile("preflight_ok_", ".costbackup")
        dest.deleteOnExit()
        try {
            vm.copyBackupWithPreflight(bundleInputStream(bodySize = 128), dest)
            // header (13) + body (128)
            assertEquals(13L + 128L, dest.length())
        } finally {
            dest.delete()
        }
    }

    @Test
    fun `copyBackupWithPreflight rejects a file with wrong magic before full copy`() = runTest(testDispatcher) {
        val vm = createViewModel()
        val dest = File.createTempFile("preflight_bad_", ".costbackup")
        dest.deleteOnExit()
        // 13+ bytes of non-COSTBACKUP data.
        val garbage = java.io.ByteArrayInputStream(ByteArray(64) { 0x7A })
        try {
            assertThrows(
                com.yourname.expensetracker.data.backup.CostbackupBundle.InvalidBackupFormatException::class.java
            ) {
                vm.copyBackupWithPreflight(garbage, dest)
            }
        } finally {
            dest.delete()
        }
    }

    @Test
    fun `copyBackupWithPreflight rejects a file shorter than the header`() = runTest(testDispatcher) {
        val vm = createViewModel()
        val dest = File.createTempFile("preflight_short_", ".costbackup")
        dest.deleteOnExit()
        val tooShort = java.io.ByteArrayInputStream(ByteArray(4))
        try {
            assertThrows(
                com.yourname.expensetracker.data.backup.CostbackupBundle.InvalidBackupFormatException::class.java
            ) {
                vm.copyBackupWithPreflight(tooShort, dest)
            }
        } finally {
            dest.delete()
        }
    }

    @Test
    fun `copyBackupWithPreflight rejects a body exceeding the size cap`() = runTest(testDispatcher) {
        val vm = createViewModel()
        val dest = File.createTempFile("preflight_big_", ".costbackup")
        dest.deleteOnExit()
        try {
            // Valid header but body well over a 32-byte cap.
            assertThrows(
                com.yourname.expensetracker.data.backup.CostbackupBundle.BackupTooLargeException::class.java
            ) {
                vm.copyBackupWithPreflight(bundleInputStream(bodySize = 4096), dest, maxBytes = 32L)
            }
        } finally {
            dest.delete()
        }
    }

    @Test
    fun `restore maps extract-phase BackupTooLargeException to a size message`() = runTest(testDispatcher) {
        // P7-CURRENT-023: a zip-bomb / oversized bundle rejected inside restoreCostBackup()
        // (the extract phase, after preflight) must surface a clear size message, not the
        // generic "Restore failed".
        val uri = Uri.parse("content://backups/huge.costbackup")
        every { context.contentResolver.openInputStream(uri) } returns bundleInputStream()
        coEvery { databaseBackupRepository.restoreCostBackup(any(), any()) } returns
            Result.failure(
                com.yourname.expensetracker.data.backup.CostbackupBundle.BackupTooLargeException(
                    "Backup exceeds total decompressed-size limit"
                )
            )

        val vm = createViewModel()
        advanceUntilIdle()
        vm.restoreBackup(uri, "test-password")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isRestoring)
        assertNotNull(state.errorMessage)
        assertTrue(
            "expected a size/entry-count message, got: ${state.errorMessage}",
            state.errorMessage!!.contains("too large", ignoreCase = true) ||
                state.errorMessage!!.contains("too many", ignoreCase = true)
        )
        assertFalse(
            "must not fall through to the generic message",
            state.errorMessage!!.startsWith("Restore failed:")
        )
    }
}
