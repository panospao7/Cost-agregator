package com.yourname.expensetracker.ui.screens.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import com.yourname.expensetracker.domain.backup.DatabaseImportResult
import com.yourname.expensetracker.domain.backup.DatabaseStats
import com.yourname.expensetracker.domain.util.FakeTimeProvider
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

    @get:org.junit.Rule
    val temporaryFolder = org.junit.rules.TemporaryFolder()
    private val viewModels = mutableListOf<BackupRestoreViewModel>()

    private val context = mockk<Context>(relaxed = true)
    private val databaseBackupRepository = mockk<DatabaseBackupRepository>(relaxed = true)
    private val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true).also {
        every { it.isWritesAllowed() } returns true
    }

    /**
     * RP-03A (P7-003): SAF destination Uri for create-backup tests. Built per
     * test via mockk (established repo pattern) — `Uri.parse` is null-backed on
     * the JVM android stub, which a class-level property would reject on access.
     */
    private fun destinationUri(): Uri = mockk(relaxed = true)

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
        every { context.cacheDir } returns temporaryFolder.root
        every { context.contentResolver.query(any(), any(), any(), any(), any()) } returns null
        coEvery { databaseBackupRepository.getDatabaseStats() } returns DatabaseStats(
            transactionCount = 0,
            categoryCount = 0,
            merchantCount = 0,
            pendingReviewCount = 0,
            lastBackupDate = null
        )
    }

    @org.junit.After
    override fun tearDown() {
        try {
            runTest(testDispatcher) {
                viewModels.forEach { it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
            }
        } finally {
            super.tearDown()
        }
    }

    private fun createViewModel(): BackupRestoreViewModel {
        return BackupRestoreViewModel(
            context,
            databaseBackupRepository,
            restoreMaintenanceMode,
            FakeTimeProvider(1_700_000_000_000L)
        ).also { viewModels += it }
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
        val destination = destinationUri()
        coEvery {
            databaseBackupRepository.createCostBackup(destination, "test-password", any(), any(), any())
        } returns Result.success(Unit)

        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup(destination, "test-password")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isBackingUp)
        assertNotNull(state.successMessage)
        assertTrue(state.successMessage!!.contains("Backup created"))
        assertNull(state.errorMessage)
    }

    @Test
    fun `createBackup shows error when repository fails`() = runTest(testDispatcher) {
        val destination = destinationUri()
        coEvery {
            databaseBackupRepository.createCostBackup(destination, "test-password", any(), any(), any())
        } returns Result.failure(RuntimeException("Storage full"))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup(destination, "test-password")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isBackingUp)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Backup failed"))
        assertNull(state.successMessage)
    }

    @Test
    fun `createBackup with blank password shows error immediately`() = runTest(testDispatcher) {
        val destination = destinationUri()
        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup(destination, "")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isBackingUp)
        assertEquals("Password cannot be empty", state.errorMessage)
    }

    @Test
    fun `restoreBackup succeeds and sets restartRequired`() = runTest(testDispatcher) {
        val uri = destinationUri()
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
        val destination = destinationUri()
        coEvery {
            databaseBackupRepository.createCostBackup(destination, "test-password", any(), any(), any())
        } returns Result.failure(RuntimeException("Error"))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup(destination, "test-password")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.errorMessage)

        vm.clearError()
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `dismissRestartRequired clears the restart-required flag and unblocks writes`() = runTest(testDispatcher) {
        // P7-P1-08: dismissRestartRequired must clear the screen-local banner AND exit
        // maintenance mode so writes are unblocked.
        val uri = destinationUri()
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

        // After restore is complete, isWritesAllowed() returns false (restart-required mode)
        every { restoreMaintenanceMode.isWritesAllowed() } returns false

        val vm = createViewModel()
        advanceUntilIdle()
        vm.restoreBackup(uri, "test-password")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.restartRequired)

        // When exit is called with forceRestartRequired=false, the mock transitions back to NORMAL
        every { restoreMaintenanceMode.exit(forceRestartRequired = false) } answers {
            every { restoreMaintenanceMode.isWritesAllowed() } returns true
        }

        vm.dismissRestartRequired()
        assertFalse(
            "dismissRestartRequired() must clear the screen-local restart-required banner",
            vm.uiState.value.restartRequired
        )
        // Verify maintenance mode was exited (writes unblocked)
        assertTrue(
            "dismissRestartRequired() must unblock writes by exiting maintenance mode",
            restoreMaintenanceMode.isWritesAllowed()
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
        val uri = destinationUri()
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

    // ── RP-03A (P7-003): SAF destination create-backup failure matrix ──
    // Each SAF failure the repository can produce (null openOutputStream, open
    // exception, write exception, close exception, failed delete-cleanup) must
    // surface a bounded typed/generic message — never raw exception text — and
    // must reset isBackingUp with no success state.

    private fun stubSafCreateFailure(destination: Uri, failure: Throwable) {
        coEvery {
            databaseBackupRepository.createCostBackup(destination, "test-password", any(), any(), any())
        } returns Result.failure(failure)
    }

    @Test
    fun `SAF createBackup maps null destination stream to the typed destination message`() = runTest(testDispatcher) {
        val destination = destinationUri()
        stubSafCreateFailure(
            destination,
            com.yourname.expensetracker.domain.backup.BackupDestinationException(
                "Backup destination could not be opened"
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup(destination, "test-password")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isBackingUp)
        assertNull(state.successMessage)
        assertEquals(
            "Could not save the backup to the selected location. Please try again.",
            state.errorMessage
        )
    }

    @Test
    fun `SAF createBackup maps a destination open exception to the typed destination message`() = runTest(testDispatcher) {
        val destination = destinationUri()
        stubSafCreateFailure(
            destination,
            com.yourname.expensetracker.domain.backup.BackupDestinationException(
                "Backup destination could not be opened",
                java.io.IOException("provider refused /content/secret-path")
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup(destination, "test-password")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isBackingUp)
        assertNull(state.successMessage)
        assertEquals(
            "Could not save the backup to the selected location. Please try again.",
            state.errorMessage
        )
        assertFalse(
            "raw cause text must never reach the UI",
            state.errorMessage!!.contains("secret-path")
        )
    }

    @Test
    fun `SAF createBackup maps write failure to the generic message without raw text`() = runTest(testDispatcher) {
        val destination = destinationUri()
        stubSafCreateFailure(destination, java.io.IOException("EPIPE write failed /storage/emulated/0/Downloads"))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup(destination, "test-password")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isBackingUp)
        assertNull(state.successMessage)
        assertEquals("Backup failed. Please try again.", state.errorMessage)
        assertFalse(
            "raw exception text must never reach the UI",
            state.errorMessage!!.contains("EPIPE") || state.errorMessage!!.contains("/storage/")
        )
    }

    @Test
    fun `SAF createBackup maps close failure to the typed destination message`() = runTest(testDispatcher) {
        val destination = destinationUri()
        stubSafCreateFailure(
            destination,
            com.yourname.expensetracker.domain.backup.BackupDestinationException(
                "Backup destination write failed",
                java.io.IOException("close failed after partial write")
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup(destination, "test-password")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isBackingUp)
        assertNull(state.successMessage)
        assertEquals(
            "Could not save the backup to the selected location. Please try again.",
            state.errorMessage
        )
    }

    @Test
    fun `SAF createBackup stays failed when document delete-cleanup also fails`() = runTest(testDispatcher) {
        val destination = destinationUri()
        // The repository performs the best-effort DocumentsContract cleanup itself
        // and reports the original typed failure; the ViewModel must stay failed
        // (no success message, no stuck progress) even when cleanup failed too.
        stubSafCreateFailure(
            destination,
            com.yourname.expensetracker.domain.backup.BackupDestinationException(
                "Backup destination could not be opened",
                RuntimeException("deleteDocument rejected")
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup(destination, "test-password")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isBackingUp)
        assertNull(state.successMessage)
        assertNotNull(state.errorMessage)
        assertFalse(state.errorMessage!!.contains("deleteDocument"))
    }
}
