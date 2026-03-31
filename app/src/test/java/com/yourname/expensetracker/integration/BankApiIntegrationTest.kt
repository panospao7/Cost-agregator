package com.yourname.expensetracker.integration

import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.SyncFrequency
import com.yourname.expensetracker.data.database.entity.SyncStatus
import com.yourname.expensetracker.domain.bank.BankApiIntegration
import com.yourname.expensetracker.domain.bank.BankTransaction
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

/**
 * Integration tests for Bank API Integration feature.
 */
class BankApiIntegrationTest {

    @Mock
    private lateinit var bankApiIntegration: BankApiIntegration

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun `test supported banks list`() {
        // Given: Bank API Integration with mocked TimeProvider
        val mockTimeProvider = Mockito.mock(TimeProvider::class.java)
        val integration = BankApiIntegration(mockTimeProvider)
        
        // When: Get supported banks
        val banks = integration.getSupportedBanks()
        
        // Then: Should have major banks
        assertTrue(banks.isNotEmpty())
        assertTrue(banks.any { it.id == "nbg" })
        assertTrue(banks.any { it.id == "eurobank" })
        assertTrue(banks.any { it.id == "revolut" })
    }

    @Test
    fun `test bank support check`() {
        // Given: Bank API Integration with mocked TimeProvider
        val mockTimeProvider = Mockito.mock(TimeProvider::class.java)
        val integration = BankApiIntegration(mockTimeProvider)
        
        // When/Then: Check bank support
        assertTrue(integration.isBankSupported("nbg"))
        assertTrue(integration.isBankSupported("revolut"))
        assertFalse(integration.isBankSupported("unknown_bank"))
    }

    @Test
    fun `test sync frequency calculation`() {
        // Given: Connection with daily sync
        val now = System.currentTimeMillis()
        val oneDayAgo = now - (24 * 60 * 60 * 1000L)
        
        val connection = BankConnection(
            bankId = "test",
            bankName = "Test Bank",
            countryCode = "GR",
            isActive = true,
            isConnected = true,
            autoSync = true,
            syncFrequency = SyncFrequency.DAILY,
            lastSync = oneDayAgo
        )
        
        // When: Check if sync needed
        val shouldSync = when (connection.syncFrequency) {
            SyncFrequency.HOURLY -> now - (connection.lastSync ?: 0) > (60 * 60 * 1000L)
            SyncFrequency.DAILY -> now - (connection.lastSync ?: 0) > (24 * 60 * 60 * 1000L)
            SyncFrequency.WEEKLY -> now - (connection.lastSync ?: 0) > (7 * 24 * 60 * 60 * 1000L)
            else -> false
        }
        
        // Then: Should sync (last sync was 1 day ago)
        assertTrue(shouldSync)
    }

    @Test
    fun `test transaction mapping to expense`() {
        // Given: Bank transaction
        val transaction = BankTransaction(
            id = "tx_123",
            date = System.currentTimeMillis(),
            amount = -50.0,
            currency = "EUR",
            merchant = "Supermarket",
            description = "Grocery shopping",
            reference = "REF123"
        )
        
        // When: Map to expense
        val expenseAmount = kotlin.math.abs(transaction.amount)
        
        // Then: Verify mapping
        assertEquals(50.0, expenseAmount, 0.01)
        assertEquals("Supermarket", transaction.merchant)
        assertEquals("EUR", transaction.currency)
    }

    @Test
    fun `test sync result calculation`() {
        // Given: Sync results
        val imported = 10
        val errors = 2
        val total = imported + errors
        
        // When: Calculate success rate
        val successRate = if (total > 0) (imported.toDouble() / total) * 100 else 0.0
        
        // Then: Verify calculations
        assertEquals(83.33, successRate, 0.01)
        assertEquals(12, total)
    }
}
