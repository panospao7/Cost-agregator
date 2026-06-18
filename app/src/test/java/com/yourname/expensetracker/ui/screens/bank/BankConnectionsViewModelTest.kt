package com.yourname.expensetracker.ui.screens.bank

import com.yourname.expensetracker.data.database.dao.BankConnectionDao
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.SyncStatus
import com.yourname.expensetracker.domain.bank.BankApiIntegration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BankConnectionsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var bankConnectionDao: BankConnectionDao
    private lateinit var bankApiIntegration: BankApiIntegration
    private lateinit var viewModel: BankConnectionsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        bankConnectionDao = mockk(relaxed = true)
        bankApiIntegration = mockk(relaxed = true)

        // Default: DAO returns an empty flow
        every { bankConnectionDao.getAllConnections() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── P10-P0-02: ViewModel wiring ───────────────────────────────────────────

    @Test
    fun `init collects connections from dao`() = runTest {
        val connections = listOf(
            BankConnection(
                bankId = "revolut", bankName = "Revolut",
                countryCode = "EU", isConnected = true, isActive = true,
                createdAt = 1000L
            )
        )
        every { bankConnectionDao.getAllConnections() } returns flowOf(connections)

        viewModel = BankConnectionsViewModel(bankConnectionDao, bankApiIntegration)

        assertEquals(connections, viewModel.connections.value)
    }

    @Test
    fun `init falls back to supported banks when dao returns empty`() = runTest {
        every { bankConnectionDao.getAllConnections() } returns flowOf(emptyList())

        viewModel = BankConnectionsViewModel(bankConnectionDao, bankApiIntegration)

        val connections = viewModel.connections.value
        assertTrue("Should show supported banks as placeholder", connections.isNotEmpty())
        connections.forEach { conn ->
            assertFalse("Placeholder connections must be disconnected", conn.isConnected)
            assertFalse("Placeholder connections must be inactive", conn.isActive)
        }
    }

    // ── P10-P0-02: syncConnection ─────────────────────────────────────────────

    @Test
    fun `syncConnection calls api sync for existing connection`() = runTest {
        val connection = BankConnection(
            id = 1L, bankId = "revolut", bankName = "Revolut",
            countryCode = "EU", isConnected = true, isActive = true,
            createdAt = 1000L
        )
        every { bankConnectionDao.getAllConnections() } returns flowOf(listOf(connection))
        coEvery { bankConnectionDao.getById(1L) } returns connection
        coEvery { bankApiIntegration.syncTransactions(connection, any()) } returns mockk()

        viewModel = BankConnectionsViewModel(bankConnectionDao, bankApiIntegration)
        viewModel.syncConnection(1L)

        coVerify { bankConnectionDao.getById(1L) }
        coVerify { bankApiIntegration.syncTransactions(connection, any()) }
    }

    @Test
    fun `syncConnection skips api call when connection not found`() = runTest {
        every { bankConnectionDao.getAllConnections() } returns flowOf(emptyList())
        coEvery { bankConnectionDao.getById(99L) } returns null

        viewModel = BankConnectionsViewModel(bankConnectionDao, bankApiIntegration)
        viewModel.syncConnection(99L)

        coVerify { bankConnectionDao.getById(99L) }
        coVerify(inverse = true) { bankApiIntegration.syncTransactions(any(), any()) }
    }

    // ── P10-P0-02: disconnect ─────────────────────────────────────────────────

    @Test
    fun `disconnect calls dao disconnect`() = runTest {
        every { bankConnectionDao.getAllConnections() } returns flowOf(emptyList())
        coEvery { bankConnectionDao.disconnect(1L) } just runs

        viewModel = BankConnectionsViewModel(bankConnectionDao, bankApiIntegration)
        viewModel.disconnect(1L)

        coVerify { bankConnectionDao.disconnect(1L) }
    }

    // ── P10-P0-02: isDemoMode ─────────────────────────────────────────────────

    @Test
    fun `isDemoMode is false when repository is wired`() {
        every { bankConnectionDao.getAllConnections() } returns flowOf(emptyList())

        viewModel = BankConnectionsViewModel(bankConnectionDao, bankApiIntegration)

        assertFalse("isDemoMode must be false when DAO is injected", viewModel.isDemoMode)
    }
}
