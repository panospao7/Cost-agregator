package com.yourname.expensetracker.ui.screens.bank

import com.yourname.expensetracker.domain.bank.BankConnectionLifecycleCoordinator
import com.yourname.expensetracker.domain.bank.BankConnectionSummary
import com.yourname.expensetracker.domain.bank.ConnectionDisconnectResult
import com.yourname.expensetracker.domain.bank.ConnectionSyncResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BankConnectionsViewModelTest {

    private val coordinator: BankConnectionLifecycleCoordinator = mockk(relaxed = true)
    private lateinit var viewModel: BankConnectionsViewModel

    @Test
    fun `init observes coordinator connections`() = runTest {
        val connections = listOf(
            BankConnectionSummary(
                id = 1L, bankId = "revolut", bankName = "Revolut",
                countryCode = "EU", isConnected = true, isActive = true,
                lastSync = null, lastSyncStatus = null, syncFrequency = "MANUAL"
            )
        )
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
    fun `syncConnection calls coordinator sync`() = runTest {
        every { coordinator.observeConnections() } returns flowOf(emptyList())
        coEvery { coordinator.syncConnection(1L) } returns ConnectionSyncResult.Success

        viewModel = BankConnectionsViewModel(coordinator)
        viewModel.syncConnection(1L)

        coVerify { coordinator.syncConnection(1L) }
    }

    @Test
    fun `disconnect calls coordinator disconnect`() = runTest {
        every { coordinator.observeConnections() } returns flowOf(emptyList())
        coEvery { coordinator.disconnectConnection(1L) } returns ConnectionDisconnectResult.Success

        viewModel = BankConnectionsViewModel(coordinator)
        viewModel.disconnect(1L)

        coVerify { coordinator.disconnectConnection(1L) }
    }
}
