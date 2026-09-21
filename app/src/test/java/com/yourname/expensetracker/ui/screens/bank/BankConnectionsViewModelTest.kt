package com.yourname.expensetracker.ui.screens.bank

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.bank.BankConnectionLifecycleCoordinator
import com.yourname.expensetracker.domain.bank.BankConnectionSummary
import com.yourname.expensetracker.domain.bank.BankSyncOutcome
import com.yourname.expensetracker.domain.bank.ConnectionDisconnectResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RP-17 17-B: ViewModel mapping of typed [BankSyncOutcome]s (one-to-one onto
 * user messages), the ViewModel-only in-flight state (register D12 — no durable
 * running status is ever persisted), and the re-auth signal surface.
 *
 * Main is an [UnconfinedTestDispatcher] so viewModelScope coroutines run
 * eagerly and deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BankConnectionsViewModelTest {

    private val coordinator: BankConnectionLifecycleCoordinator = mockk(relaxed = true)
    private lateinit var viewModel: BankConnectionsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun summary(id: Long) = BankConnectionSummary(
        id = id, bankId = "revolut", bankName = "Revolut",
        countryCode = "EU", isConnected = true, isActive = true,
        lastSync = null, lastSyncStatus = null, syncFrequency = "MANUAL"
    )

    private fun newViewModel() {
        every { coordinator.observeConnections() } returns flowOf(emptyList<BankConnectionSummary>())
        viewModel = BankConnectionsViewModel(coordinator)
    }

    @Test
    fun `init observes coordinator connections`() = runTest {
        val connections = listOf(summary(1L))
        every { coordinator.observeConnections() } returns flowOf(connections)

        viewModel = BankConnectionsViewModel(coordinator)

        assertEquals(connections, viewModel.connections.value)
    }

    @Test
    fun `init handles empty flow gracefully`() = runTest {
        every { coordinator.observeConnections() } returns emptyFlow()

        viewModel = BankConnectionsViewModel(coordinator)

        assertTrue(viewModel.connections.value.isEmpty())
    }

    @Test
    fun `syncConnection maps outcome to user message and clears in-flight id`() = runTest {
        newViewModel()
        coEvery { coordinator.syncConnection(1L) } returns BankSyncOutcome.Success(importedCount = 2)

        assertTrue(viewModel.syncingConnectionIds.value.isEmpty())
        viewModel.syncConnection(1L)

        coVerify { coordinator.syncConnection(1L) }
        val message = viewModel.userMessage.value
        assertTrue(message is BankSyncUserMessage.SyncResult)
        assertEquals(R.string.bank_sync_result_success, (message as BankSyncUserMessage.SyncResult).messageRes)
        assertTrue("in-flight id cleared when sync settles", viewModel.syncingConnectionIds.value.isEmpty())
    }

    @Test
    fun `reauthRequired outcome surfaces the reauth message type`() = runTest {
        newViewModel()
        coEvery { coordinator.syncConnection(1L) } returns BankSyncOutcome.ReauthRequired()

        viewModel.syncConnection(1L)

        assertEquals(BankSyncUserMessage.ReauthRequired, viewModel.userMessage.value)
    }

    @Test
    fun `failed and blocked outcomes map to their messages`() = runTest {
        newViewModel()
        val cases = listOf(
            BankSyncOutcome.Partial(1, 0, 1) to R.string.bank_sync_result_partial,
            BankSyncOutcome.Blocked(com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.RESTORE_BLOCKED) to R.string.bank_sync_result_blocked,
            BankSyncOutcome.RetryableFailure() to R.string.bank_sync_result_retryable,
            BankSyncOutcome.PermanentFailure() to R.string.bank_sync_result_permanent,
            BankSyncOutcome.NotFound to R.string.bank_sync_result_connection_missing,
            BankSyncOutcome.FeatureUnavailable(com.yourname.expensetracker.domain.bank.BankUnavailableReason.RELEASE_BUILD) to R.string.bank_feature_unavailable
        )
        for ((index, case) in cases.withIndex()) {
            val connectionId = index + 1L
            coEvery { coordinator.syncConnection(connectionId) } returns case.first

            viewModel.syncConnection(connectionId)

            val message = viewModel.userMessage.value
            assertTrue(message is BankSyncUserMessage.SyncResult)
            assertEquals(case.second, (message as BankSyncUserMessage.SyncResult).messageRes)
            viewModel.consumeUserMessage()
        }
    }

    @Test
    fun `consumeUserMessage clears the message`() = runTest {
        newViewModel()
        coEvery { coordinator.syncConnection(1L) } returns BankSyncOutcome.NotFound

        viewModel.syncConnection(1L)
        assertEquals(
            BankSyncUserMessage.SyncResult(R.string.bank_sync_result_connection_missing),
            viewModel.userMessage.value
        )

        viewModel.consumeUserMessage()
        assertEquals(null, viewModel.userMessage.value)
    }

    @Test
    fun `disconnect calls coordinator disconnect`() = runTest {
        newViewModel()
        coEvery { coordinator.disconnectConnection(1L) } returns ConnectionDisconnectResult.Success

        viewModel.disconnect(1L)

        coVerify { coordinator.disconnectConnection(1L) }
    }
}
