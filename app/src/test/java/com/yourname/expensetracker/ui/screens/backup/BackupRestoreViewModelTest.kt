package com.yourname.expensetracker.ui.screens.backup

import android.content.Context
import android.net.Uri
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import com.yourname.expensetracker.domain.backup.DatabaseImportResult
import com.yourname.expensetracker.domain.backup.DatabaseStats
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreViewModelTest : ViewModelTestUtils() {

    private val context = mockk<Context>(relaxed = true)
    private val databaseBackupRepository = mockk<DatabaseBackupRepository>(relaxed = true)
    private val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true)

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
        coEvery { databaseBackupRepository.createCostBackup(any(), any(), any()) } returns Result.success(backupFile)

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
        coEvery { databaseBackupRepository.createCostBackup(any(), any(), any()) } returns
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
        coEvery { context.contentResolver.openInputStream(uri) } returns mockk(relaxed = true)

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
        coEvery { databaseBackupRepository.createCostBackup(any(), any(), any()) } returns
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
    fun `dismissRestartRequired resets restart flag`() = runTest(testDispatcher) {
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
        coEvery { context.contentResolver.openInputStream(uri) } returns mockk(relaxed = true)

        val vm = createViewModel()
        advanceUntilIdle()
        vm.restoreBackup(uri, "test-password")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.restartRequired)

        vm.dismissRestartRequired()
        assertFalse(vm.uiState.value.restartRequired)
    }
}
