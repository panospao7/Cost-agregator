package com.yourname.expensetracker.ui.screens.backup

import android.content.Context
import android.net.Uri
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import com.yourname.expensetracker.domain.backup.DatabaseImportResult
import com.yourname.expensetracker.domain.backup.DatabaseStats
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDeniedException
import com.yourname.expensetracker.domain.privacy.PrivacyGateReasonCodes
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RP-15 (15-D) — BackupRestoreViewModel denial convergence.
 *
 * Three denial classes must land on the typed [PrivacyBlockedUiState]:
 * 1. privacy-gate denial ([PrivacyDeniedException] from the repository),
 * 2. fail-closed generic failure (bounded message, NO blocked state),
 * 3. SecurityException on the SAF stream (typed state, no raw message).
 * A permitted path must leave the state null.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreViewModelPrivacyDenialTest : ViewModelTestUtils() {

    private val context = mockk<Context>(relaxed = true)
    private val databaseBackupRepository = mockk<DatabaseBackupRepository>(relaxed = true)
    private val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true).also {
        every { it.isWritesAllowed() } returns true
    }

    /** SAF Uri; built per test via mockk (established repo pattern). */
    private fun destinationUri(): Uri = mockk(relaxed = true)

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

    private fun createViewModel() = BackupRestoreViewModel(
        context,
        databaseBackupRepository,
        restoreMaintenanceMode,
        FakeTimeProvider(1_700_000_000_000L)
    )

    // ── 1. Privacy-gate denial (typed exception from the repository) ──────────

    @Test
    fun `backup privacy denial converges on typed blocked state without message matching`() =
        runTest(testDispatcher) {
            val destination = destinationUri()
            coEvery {
                databaseBackupRepository.createCostBackup(any(), any(), any(), any(), any())
            } returns Result.failure(PrivacyDeniedException(PrivacyCapability.ENCRYPTED_BACKUP))

            val vm = createViewModel()
            advanceUntilIdle()
            vm.createBackup(destination, "secret")
            advanceUntilIdle()

            val state = vm.uiState.value
            val blocked = state.privacyBlocked
            assertNotNull("denial must set the typed state", blocked)
            assertEquals(PrivacyCapability.ENCRYPTED_BACKUP, blocked!!.capability)
            assertEquals(PrivacyGateReasonCodes.ENCRYPTED_BACKUP_DISABLED, blocked.reasonCode)
            assertNull("denial must not ALSO show the generic error", state.errorMessage)
            assertFalse(state.isBackingUp)
        }

    @Test
    fun `restore privacy denial converges on typed blocked state`() = runTest(testDispatcher) {
        coEvery {
            databaseBackupRepository.restoreCostBackup(any(), any())
        } returns Result.failure(PrivacyDeniedException(PrivacyCapability.ENCRYPTED_BACKUP))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.restoreBackup(uri = destinationUri(), password = "secret")
        advanceUntilIdle()

        val blocked = vm.uiState.value.privacyBlocked
        assertNotNull(blocked)
        assertEquals(PrivacyCapability.ENCRYPTED_BACKUP, blocked!!.capability)
        assertFalse(vm.uiState.value.isRestoring)
    }

    @Test
    fun `denial state carries a controlled code - never decision or exception text`() =
        runTest(testDispatcher) {
            coEvery {
                databaseBackupRepository.createCostBackup(any(), any(), any(), any(), any())
            } returns Result.failure(PrivacyDeniedException(PrivacyCapability.ENCRYPTED_BACKUP))

            val vm = createViewModel()
            advanceUntilIdle()
            vm.createBackup(destinationUri(), "secret")
            advanceUntilIdle()

            val blocked = vm.uiState.value.privacyBlocked!!
            assertTrue(blocked.reasonCode in PrivacyGateReasonCodes.ALL)
        }

    // ── 2. Fail-closed generic failure (no blocked state, bounded message) ────

    @Test
    fun `generic failure keeps bounded message and clears any blocked state`() = runTest(testDispatcher) {
        coEvery {
            databaseBackupRepository.createCostBackup(any(), any(), any(), any(), any())
        } returns Result.failure(IllegalStateException("db snapshot failed with SECRETDATA"))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup(destinationUri(), "secret")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull("non-privacy failures must not raise the blocked state", state.privacyBlocked)
        assertEquals("Backup failed. Please try again.", state.errorMessage)
        assertFalse(state.errorMessage!!.contains("SECRETDATA"))
    }

    // ── 3. SecurityException on the SAF stream (restore preflight) ────────────

    @Test
    fun `security exception during restore converges on typed blocked state`() = runTest(testDispatcher) {
        val uri = destinationUri()
        every { context.contentResolver.openInputStream(uri) } throws SecurityException("SAF grant revoked")

        val vm = createViewModel()
        advanceUntilIdle()
        vm.restoreBackup(uri = uri, password = "secret")
        advanceUntilIdle()

        val state = vm.uiState.value
        val blocked = state.privacyBlocked
        assertNotNull("SecurityException must set the typed state", blocked)
        assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, blocked!!.reasonCode)
        assertFalse(
            "raw exception text must not be rendered",
            (state.errorMessage ?: "").contains("SAF grant revoked")
        )
        assertFalse(state.isRestoring)
    }

    // ── Permitted path unchanged ───────────────────────────────────────────────

    @Test
    fun `permitted backup leaves blocked state null`() = runTest(testDispatcher) {
        coEvery {
            databaseBackupRepository.createCostBackup(any(), any(), any(), any(), any())
        } returns Result.success(Unit)

        val vm = createViewModel()
        advanceUntilIdle()
        vm.createBackup(destinationUri(), "secret")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull(state.privacyBlocked)
        assertTrue(state.successMessage!!.contains("Backup created"))
    }

    @Test
    fun `permitted restore leaves blocked state null`() = runTest(testDispatcher) {
        coEvery {
            databaseBackupRepository.restoreCostBackup(any(), any())
        } returns Result.success(DatabaseImportResult.Success)

        val vm = createViewModel()
        advanceUntilIdle()
        vm.restoreBackup(uri = destinationUri(), password = "secret")
        advanceUntilIdle()

        assertNull(vm.uiState.value.privacyBlocked)
    }
}
