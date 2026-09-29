package com.yourname.expensetracker.ui.screens.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import com.yourname.expensetracker.domain.backup.DatabaseImportResult
import com.yourname.expensetracker.domain.backup.DatabaseStats
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDeniedException
import com.yourname.expensetracker.domain.privacy.PrivacyGateReasonCodes
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import java.io.ByteArrayInputStream
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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

    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val viewModels = mutableListOf<BackupRestoreViewModel>()

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
        // Harness stub: the restore flow stages a temp file under cacheDir; the relaxed
        // Context mock returns a mock File whose path is null, NPE-ing inside createTempFile.
        every { context.cacheDir } returns temporaryFolder.root
        // Provider size metadata is unavailable; stream preflight still runs below.
        every { context.contentResolver.query(any(), any(), any(), any(), any()) } returns null
        // Only the ViewModel preflight is real here; the repository owns decryption
        // and is mocked below. Supply the valid v1 header so denial/success tests
        // actually reach that boundary instead of stopping on an empty stream.
        every { context.contentResolver.openInputStream(any()) } answers {
            ByteArrayInputStream("COSTBACKUP1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 1))
        }
        coEvery { databaseBackupRepository.getDatabaseStats() } returns DatabaseStats(
            transactionCount = 0,
            categoryCount = 0,
            merchantCount = 0,
            pendingReviewCount = 0,
            lastBackupDate = null
        )
    }

    @Test
    fun unavailableBackupInfoIsNotPresentedAsNoPreviousBackup() = runTest(testDispatcher) {
        for (reason in com.yourname.expensetracker.domain.backup.DatabaseStatsFailureReason.values()) {
            coEvery { databaseBackupRepository.getDatabaseStats() } throws
                com.yourname.expensetracker.domain.backup.DatabaseStatsUnavailableException(reason)
            val vm = createViewModel()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.backupInfoUnavailable)
            assertNull(vm.uiState.value.lastBackupDate)
            assertNull(vm.uiState.value.privacyBlocked)
            assertNull(vm.uiState.value.errorMessage)
        }
    }

    @Test
    fun unexpectedStatsFailureDoesNotLeakIntoBackupUiState() = runTest(testDispatcher) {
        coEvery { databaseBackupRepository.getDatabaseStats() } throws
            IllegalStateException("SECRET /private/account.db financial payload")
        val vm = createViewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.backupInfoUnavailable)
        assertFalse(vm.uiState.value.toString().contains("SECRET"))
        assertFalse(vm.uiState.value.toString().contains("/private/"))
    }

    @Test
    fun successfulEmptyStatsRemainAvailableWithoutInventingBackupDate() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.backupInfoUnavailable)
        assertNull(vm.uiState.value.lastBackupDate)
    }

    @Test
    fun successfulBackupTimestampRemainsAvailable() = runTest(testDispatcher) {
        coEvery { databaseBackupRepository.getDatabaseStats() } returns
            DatabaseStats(0, 0, 0, 0, lastBackupDate = 1_700_000_000_000L)
        val vm = createViewModel()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.backupInfoUnavailable)
        assertNotNull(vm.uiState.value.lastBackupDate)
    }

    @Test
    fun statsCancellationCancelsTheLoadInsteadOfPublishingUnavailability() = runTest(testDispatcher) {
        var loadJob: Job? = null
        coEvery { databaseBackupRepository.getDatabaseStats() } coAnswers {
            loadJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            throw kotlinx.coroutines.CancellationException("test cancellation")
        }
        val vm = createViewModel()
        advanceUntilIdle()
        assertTrue(requireNotNull(loadJob).isCancelled)
        assertFalse(vm.uiState.value.backupInfoUnavailable)
        assertNull(vm.uiState.value.errorMessage)
    }

    private fun createViewModel() = BackupRestoreViewModel(
        context,
        databaseBackupRepository,
        restoreMaintenanceMode,
        FakeTimeProvider(1_700_000_000_000L)
    ).also { viewModels += it }

    @After
    override fun tearDown() {
        try {
            runTest(testDispatcher) {
                viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        } finally {
            super.tearDown()
        }
    }

    // ── 1. Privacy-gate denial (typed exception from the repository) ──────────

    @Test
    fun `backup privacy denial converges on typed blocked state without message matching`() =
        runTest(testDispatcher) {
            val destination = destinationUri()
            coEvery {
                databaseBackupRepository.createCostBackup(any(), any(), any(), any(), any())
            } returns Result.failure(PrivacyDeniedException(
                PrivacyCapability.ENCRYPTED_BACKUP,
                reasonCode = PrivacyGateReasonCodes.ENCRYPTED_BACKUP_DISABLED
            ))

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
        } returns Result.failure(PrivacyDeniedException(
            PrivacyCapability.ENCRYPTED_BACKUP,
            reasonCode = PrivacyGateReasonCodes.ENCRYPTED_BACKUP_DISABLED
        ))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.restoreBackup(uri = destinationUri(), password = "secret")
        advanceUntilIdle()

        val blocked = vm.uiState.value.privacyBlocked
        coVerify(exactly = 1) { databaseBackupRepository.restoreCostBackup(any(), "secret") }
        assertNotNull(blocked)
        assertEquals(PrivacyCapability.ENCRYPTED_BACKUP, blocked!!.capability)
        assertEquals(PrivacyGateReasonCodes.ENCRYPTED_BACKUP_DISABLED, blocked.reasonCode)
        assertNull(vm.uiState.value.errorMessage)
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
            assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, blocked.reasonCode)
        }

    @Test
    fun malformedDenialReasonFailsClosedWithoutLeakingToStateOrLogs() = runTest(testDispatcher) {
        val failure = PrivacyDeniedException(
            PrivacyCapability.ENCRYPTED_BACKUP,
            reasonCode = "/private/receipt financial_data"
        )
        coEvery { databaseBackupRepository.createCostBackup(any(), any(), any(), any(), any()) } returns Result.failure(failure)
        coEvery { databaseBackupRepository.restoreCostBackup(any(), any()) } returns Result.failure(failure)
        val logs = mutableListOf<Pair<Throwable?, String>>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += t to message
            }
        }
        timber.log.Timber.plant(tree)
        try {
            for (restore in listOf(false, true)) {
                val vm = createViewModel()
                advanceUntilIdle()
                if (restore) vm.restoreBackup(destinationUri(), "secret")
                else vm.createBackup(destinationUri(), "secret")
                advanceUntilIdle()

                val state = vm.uiState.value
                assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, state.privacyBlocked!!.reasonCode)
                assertNull(state.errorMessage)
                assertFalse(state.isBackingUp || state.isRestoring)
            }
            coVerify(exactly = 1) { databaseBackupRepository.restoreCostBackup(any(), "secret") }
            assertTrue(logs.any { it.second.contains("BACKUP_CREATE_FAILED") })
            assertTrue(logs.any { it.second.contains("RESTORE_FAILED") })
            assertTrue(logs.all {
                it.first == null && !it.second.contains("/private/") && !it.second.contains("financial_data")
            })
        } finally {
            timber.log.Timber.uproot(tree)
        }
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
        coVerify(exactly = 0) { databaseBackupRepository.restoreCostBackup(any(), any()) }
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
        } returns Result.success(DatabaseImportResult.Success(summary = com.yourname.expensetracker.domain.backup.DatabaseImportSummary(transactionCount = 0, categoryCount = 0, merchantCount = 0, pendingReviewCount = 0, budgetCount = 0)))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.restoreBackup(uri = destinationUri(), password = "secret")
        advanceUntilIdle()

        coVerify(exactly = 1) { databaseBackupRepository.restoreCostBackup(any(), "secret") }
        val state = vm.uiState.value
        assertNull(state.privacyBlocked)
        assertNull(state.errorMessage)
        assertEquals("Restore completed successfully!", state.successMessage)
        assertFalse(state.isRestoring)
    }
}
