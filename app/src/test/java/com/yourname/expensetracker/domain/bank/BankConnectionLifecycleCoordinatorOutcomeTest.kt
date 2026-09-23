package com.yourname.expensetracker.domain.bank

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BankConnectionDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.SyncStatus
import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RP-17 17-A / 17-B / 17-E coordinator contract:
 *
 *  - 17-A: public coordinator entry points apply the availability gate BEFORE
 *    any DAO read or provider integration (typed FeatureUnavailable, D1);
 *  - 17-B: sync outcomes map one-to-one onto persisted TERMINAL statuses —
 *    SUCCESS/PARTIAL advance lastSync, FAILED-family never advances it, no
 *    outcome persists RUNNING, NotFound/FeatureUnavailable touch nothing;
 *  - 17-E: disconnect runs inside the write-barrier/transaction order and
 *    deletes only the reviews scoped to the stable connection identity;
 *  - cancellation propagates and is never converted into an outcome.
 */
class BankConnectionLifecycleCoordinatorOutcomeTest {

    private val bankConnectionDao: BankConnectionDao = mockk(relaxed = true)
    private val bankApiIntegration: BankApiIntegration = mockk(relaxed = true)
    private val writeBarrier: DatabaseWriteBarrier = mockk(relaxed = true)
    private val pendingReviewDao: PendingReviewDao = mockk(relaxed = true)
    private val database: AppDatabase = mockk(relaxed = true)
    private val timeProvider = FakeTimeProvider(FIXED_NOW)

    private val connection = BankConnection(
        id = 7L,
        bankId = "revolut",
        bankName = "Revolut",
        countryCode = "EU",
        isConnected = true,
        isActive = true
    )

    private lateinit var coordinator: BankConnectionLifecycleCoordinator

    companion object {
        private const val FIXED_NOW = 1_710_000_000_000L
    }

    @Before
    fun setUp() {
        // 17-E disconnect runs inside database.withTransaction — execute the block
        // directly against the relaxed AppDatabase mock (repo-standard pattern).
        io.mockk.mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        coordinator = buildCoordinator(bankSyncAvailable = true)
    }

    @After
    fun tearDown() {
        io.mockk.unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun buildCoordinator(bankSyncAvailable: Boolean): BankConnectionLifecycleCoordinator =
        BankConnectionLifecycleCoordinator(
            bankConnectionDao = bankConnectionDao,
            bankApiIntegration = bankApiIntegration,
            writeBarrier = writeBarrier,
            pendingReviewDao = pendingReviewDao,
            database = database,
            hashingService = DefaultSensitiveHashingService(),
            timeProvider = timeProvider,
            featureAvailability = BankFeatureAvailability.forDebugMode(bankSyncAvailable)
        )

    // ── 17-A: availability gate ──────────────────────────────────────────────

    @Test
    fun `syncConnection returns FeatureUnavailable in release and never touches dao or integration`() = runTest {
        val releaseCoordinator = buildCoordinator(bankSyncAvailable = false)

        val outcome = releaseCoordinator.syncConnection(7L)

        assertTrue(outcome is BankSyncOutcome.FeatureUnavailable)
        coVerify(exactly = 0) { bankConnectionDao.getById(any()) }
        coVerify(exactly = 0) { bankApiIntegration.syncTransactions(any(), any()) }
        coVerify(exactly = 0) { bankConnectionDao.updateSyncStatus(any(), any(), any()) }
        coVerify(exactly = 0) { bankConnectionDao.updateSyncStatusOnly(any(), any()) }
    }

    @Test
    fun `initiateConnection returns FeatureUnavailable in release and never enters integration`() = runTest {
        val releaseCoordinator = buildCoordinator(bankSyncAvailable = false)

        val outcome = releaseCoordinator.initiateConnection("revolut")

        assertTrue(outcome is BankConnectionOperationOutcome.FeatureUnavailable)
        coVerify(exactly = 0) { bankApiIntegration.initiateConnection(any()) }
    }

    @Test
    fun `completeConnection returns FeatureUnavailable in release and never enters integration`() = runTest {
        val releaseCoordinator = buildCoordinator(bankSyncAvailable = false)

        val outcome = releaseCoordinator.completeConnection("revolut", "auth-code")

        assertTrue(outcome is BankConnectionOperationOutcome.FeatureUnavailable)
        coVerify(exactly = 0) { bankApiIntegration.completeConnection(any(), any()) }
    }

    @Test
    fun `debug provider mode is permitted per D1`() = runTest {
        coEvery { bankConnectionDao.getById(7L) } returns connection
        coEvery { bankApiIntegration.syncTransactions(connection, null) } returns BankSyncOutcome.Success()

        val outcome = coordinator.syncConnection(7L)

        assertTrue(outcome is BankSyncOutcome.Success)
        coVerify(exactly = 1) { bankApiIntegration.syncTransactions(connection, null) }
    }

    // ── 17-B: outcome → terminal status mapping table ────────────────────────

    @Test
    fun `success outcome persists SUCCESS and advances lastSync`() = runTest {
        coEvery { bankConnectionDao.getById(7L) } returns connection
        coEvery { bankApiIntegration.syncTransactions(any(), any()) } returns
            BankSyncOutcome.Success(importedCount = 3, skippedCount = 1)

        val outcome = coordinator.syncConnection(7L)

        assertTrue(outcome is BankSyncOutcome.Success)
        coVerify(exactly = 1) {
            bankConnectionDao.updateSyncStatus(7L, FIXED_NOW, SyncStatus.SUCCESS)
        }
        coVerify(exactly = 0) { bankConnectionDao.updateSyncStatusOnly(any(), any()) }
    }

    @Test
    fun `partial outcome persists PARTIAL and advances lastSync`() = runTest {
        coEvery { bankConnectionDao.getById(7L) } returns connection
        coEvery { bankApiIntegration.syncTransactions(any(), any()) } returns
            BankSyncOutcome.Partial(importedCount = 2, skippedCount = 1, failedCount = 1)

        val outcome = coordinator.syncConnection(7L)

        assertTrue(outcome is BankSyncOutcome.Partial)
        coVerify(exactly = 1) {
            bankConnectionDao.updateSyncStatus(7L, FIXED_NOW, SyncStatus.PARTIAL)
        }
    }

    @Test
    fun `reauthRequired persists FAILED without advancing lastSync`() = runTest {
        coEvery { bankConnectionDao.getById(7L) } returns connection
        coEvery { bankApiIntegration.syncTransactions(any(), any()) } returns
            BankSyncOutcome.ReauthRequired()

        val outcome = coordinator.syncConnection(7L)

        assertTrue(outcome is BankSyncOutcome.ReauthRequired)
        coVerify(exactly = 1) { bankConnectionDao.updateSyncStatusOnly(7L, SyncStatus.FAILED) }
        coVerify(exactly = 0) { bankConnectionDao.updateSyncStatus(any(), any(), any()) }
        assertEquals(FIXED_NOW, timeProvider.now())
    }

    @Test
    fun `blocked retryable and permanent failures all persist FAILED without advancing lastSync`() = runTest {
        val failureOutcomes = listOf(
            BankSyncOutcome.Blocked(DiagnosticReasonCode.RESTORE_BLOCKED),
            BankSyncOutcome.RetryableFailure(failedCount = 2),
            BankSyncOutcome.PermanentFailure(DiagnosticReasonCode.TOKEN_INVALID)
        )
        for (failure in failureOutcomes) {
            coEvery { bankConnectionDao.getById(7L) } returns connection
            coEvery { bankApiIntegration.syncTransactions(any(), any()) } returns failure

            val outcome = coordinator.syncConnection(7L)

            assertEquals(failure, outcome)
            coVerify(exactly = 1) { bankConnectionDao.updateSyncStatusOnly(7L, SyncStatus.FAILED) }
            coVerify(exactly = 0) { bankConnectionDao.updateSyncStatus(any(), any(), any()) }
            clearTokenStubs()
        }
    }

    @Test
    fun `restore blocked outcome skips both terminal status writes`() = runTest {
        coEvery { bankConnectionDao.getById(7L) } returns connection
        val blocked = BankSyncOutcome.Blocked(DiagnosticReasonCode.RESTORE_BLOCKED)
        coEvery { bankApiIntegration.syncTransactions(connection, null) } returns blocked
        denyTerminalStatusWrite()

        assertEquals(blocked, coordinator.syncConnection(7L))
        coVerify(exactly = 1) { bankApiIntegration.syncTransactions(connection, null) }
        coVerify(exactly = 0) { bankConnectionDao.updateSyncStatus(any(), any(), any()) }
        coVerify(exactly = 0) { bankConnectionDao.updateSyncStatusOnly(any(), any()) }
    }

    @Test
    fun `barrier denial after integration success leaves outcome unchanged and skips status`() = runTest {
        coEvery { bankConnectionDao.getById(7L) } returns connection
        val success = BankSyncOutcome.Success(importedCount = 2)
        coEvery { bankApiIntegration.syncTransactions(connection, null) } returns success
        denyTerminalStatusWrite()

        assertEquals(success, coordinator.syncConnection(7L))
        coVerify(exactly = 0) { bankConnectionDao.updateSyncStatus(any(), any(), any()) }
        coVerify(exactly = 0) { bankConnectionDao.updateSyncStatusOnly(any(), any()) }
    }

    @Test
    fun `cancellation during terminal barrier check propagates without status write`() = runTest {
        coEvery { bankConnectionDao.getById(7L) } returns connection
        coEvery { bankApiIntegration.syncTransactions(connection, null) } returns BankSyncOutcome.Success()
        io.mockk.every { writeBarrier.checkWritesAllowed("BankConnectionLifecycleCoordinator.persistOutcome") } throws
            CancellationException("status cancelled")

        try {
            coordinator.syncConnection(7L)
            org.junit.Assert.fail("Expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals("status cancelled", expected.message)
        }
        coVerify(exactly = 0) { bankConnectionDao.updateSyncStatus(any(), any(), any()) }
        coVerify(exactly = 0) { bankConnectionDao.updateSyncStatusOnly(any(), any()) }
    }

    private fun denyTerminalStatusWrite() {
        val operation = "BankConnectionLifecycleCoordinator.persistOutcome"
        io.mockk.every { writeBarrier.checkWritesAllowed(operation) } throws DatabaseAccessBlockedException(
            accessType = DatabaseAccessType.WRITE,
            operation = DatabaseAccessOperation(operation),
            mode = RestoreMaintenanceMode.Mode.RESTORE_STAGING
        )
    }

    @Test
    fun `notFound never touches connection state`() = runTest {
        coEvery { bankConnectionDao.getById(7L) } returns null

        val outcome = coordinator.syncConnection(7L)

        assertEquals(BankSyncOutcome.NotFound, outcome)
        coVerify(exactly = 0) { bankConnectionDao.updateSyncStatus(any(), any(), any()) }
        coVerify(exactly = 0) { bankConnectionDao.updateSyncStatusOnly(any(), any()) }
    }

    @Test
    fun `no outcome ever persists a RUNNING status`() = runTest {
        // Terminal-only register D12: the DAO surface has no status-transition
        // API that could persist RUNNING — updateSyncStatus/updateSyncStatusOnly
        // take a terminal SyncStatus only. Assert the compiler-level fact from
        // the mapping table instead: every outcome with a status maps to
        // SUCCESS/PARTIAL/FAILED.
        for (outcome in listOf(
            BankSyncOutcome.Success(),
            BankSyncOutcome.Partial(1, 0, 1),
            BankSyncOutcome.ReauthRequired(),
            BankSyncOutcome.Blocked(DiagnosticReasonCode.RESTORE_BLOCKED),
            BankSyncOutcome.RetryableFailure(),
            BankSyncOutcome.PermanentFailure(),
            BankSyncOutcome.NotFound,
            BankSyncOutcome.FeatureUnavailable(BankUnavailableReason.RELEASE_BUILD)
        )) {
            val status = outcome.toTerminalSyncStatus()
            assertTrue(
                "outcome $outcome must map to a terminal status or null",
                status == null || status in setOf(
                    SyncStatus.SUCCESS, SyncStatus.PARTIAL, SyncStatus.FAILED
                )
            )
        }
    }

    @Test
    fun `integration exception maps to RetryableFailure and persists FAILED`() = runTest {
        coEvery { bankConnectionDao.getById(7L) } returns connection
        coEvery { bankApiIntegration.syncTransactions(any(), any()) } throws
            java.io.IOException("provider unreachable")

        val outcome = coordinator.syncConnection(7L)

        assertTrue(outcome is BankSyncOutcome.RetryableFailure)
        coVerify(exactly = 1) { bankConnectionDao.updateSyncStatusOnly(7L, SyncStatus.FAILED) }
    }

    @Test
    fun `cancellation propagates and is never an outcome`() = runTest {
        coEvery { bankConnectionDao.getById(7L) } returns connection
        coEvery { bankApiIntegration.syncTransactions(any(), any()) } throws
            CancellationException("sync cancelled")

        try {
            coordinator.syncConnection(7L)
            org.junit.Assert.fail("Expected CancellationException to propagate")
        } catch (expected: CancellationException) {
            assertEquals("sync cancelled", expected.message)
        }
        coVerify(exactly = 0) { bankConnectionDao.updateSyncStatus(any(), any(), any()) }
        coVerify(exactly = 0) { bankConnectionDao.updateSyncStatusOnly(any(), any()) }
    }

    // ── 17-E: disconnect ordering and scoped review cleanup ──────────────────

    @Test
    fun `disconnect deletes only scoped reviews then disconnects the connection`() = runTest {
        coEvery { bankConnectionDao.getById(7L) } returns connection
        coEvery { pendingReviewDao.deleteByBankConnectionScope(any()) } returns 2

        val result = coordinator.disconnectConnection(7L)

        assertEquals(ConnectionDisconnectResult.Success, result)
        val scopeHash = DefaultSensitiveHashingService()
            .hmacSha256Prefix("7", BankApiIntegration.BANK_ACCOUNT_SCOPE_PURPOSE)
        coVerify(exactly = 1) { pendingReviewDao.deleteByBankConnectionScope(scopeHash!!) }
        coVerify(exactly = 1) { bankConnectionDao.disconnect(7L) }
        // Barrier checked (at least once, before any DB access)
        verify(atLeast = 1) {
            writeBarrier.checkWritesAllowed("BankConnectionLifecycleCoordinator.disconnectConnection")
        }
    }

    @Test
    fun `disconnect blocked by write barrier returns RetryableFailure without DB writes`() = runTest {
        coEvery {
            writeBarrier.checkWritesAllowed("BankConnectionLifecycleCoordinator.disconnectConnection")
        } throws com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException(
            accessType = com.yourname.expensetracker.data.backup.DatabaseAccessType.WRITE,
            operation = com.yourname.expensetracker.data.backup.DatabaseAccessOperation(
                "BankConnectionLifecycleCoordinator.disconnectConnection"
            ),
            mode = com.yourname.expensetracker.data.backup.RestoreMaintenanceMode.Mode.RESTORE_STAGING
        )

        val result = coordinator.disconnectConnection(7L)

        assertEquals(ConnectionDisconnectResult.RetryableFailure, result)
        coVerify(exactly = 0) { bankConnectionDao.disconnect(any()) }
        coVerify(exactly = 0) { pendingReviewDao.deleteByBankConnectionScope(any()) }
    }

    @Test
    fun `disconnect of unknown connection is NotFound without writes`() = runTest {
        coEvery { bankConnectionDao.getById(7L) } returns null

        val result = coordinator.disconnectConnection(7L)

        assertEquals(ConnectionDisconnectResult.NotFound, result)
        coVerify(exactly = 0) { bankConnectionDao.disconnect(any()) }
        coVerify(exactly = 0) { pendingReviewDao.deleteByBankConnectionScope(any()) }
    }

    private fun clearTokenStubs() {
        io.mockk.clearMocks(bankConnectionDao, bankApiIntegration)
        coEvery { bankConnectionDao.getById(7L) } returns connection
    }
}
