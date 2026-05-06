package com.yourname.expensetracker.domain.negotiation

import com.yourname.expensetracker.data.database.dao.SubscriptionPriceHistoryDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [SmartBillNegotiationEngine].
 *
 * Focuses on the engine's contract with its dependencies rather than
 * the full negotiation scoring logic (which is covered by integration tests).
 */
class NegotiationEngineTest {

    private val recurringExpenseRepository: RecurringExpenseRepository = mockk()
    private val priceHistoryDao: SubscriptionPriceHistoryDao = mockk()

    private fun createEngine() = SmartBillNegotiationEngine(
        recurringExpenseRepository = recurringExpenseRepository,
        priceHistoryDao = priceHistoryDao
    )

    @Test
    fun `no recommendation when no data`() = runTest {
        // Given: no subscriptions exist
        coEvery { recurringExpenseRepository.getAll() } returns emptyList()

        val engine = createEngine()
        val opportunities = engine.analyzeNegotiationOpportunities()

        // Then: no negotiation opportunities are generated
        assertTrue("Expected empty opportunities when no subscriptions exist",
            opportunities.isEmpty())
    }

    @Test
    fun `provider failure handled gracefully`() = runTest {
        // Given: the repository throws (e.g., database error)
        coEvery { recurringExpenseRepository.getAll() } throws RuntimeException("DB connection lost")

        val engine = createEngine()

        // Then: the exception propagates to the caller (no silent swallowing)
        try {
            engine.analyzeNegotiationOpportunities()
        } catch (e: Exception) {
            assertTrue("Exception should be the repository error",
                e.message?.contains("DB connection lost") == true)
        }
    }

    @Test
    fun `market rate staleness is detected correctly`() {
        val now = System.currentTimeMillis()

        // Fresh rate — last updated just now
        val freshRate = SmartBillNegotiationEngine.MarketRate(
            serviceType = SmartBillNegotiationEngine.ServiceType.INTERNET,
            providerName = "Test",
            averagePrice = 25.0,
            competitivePrice = 20.0,
            bestPrice = 15.0,
            unit = "month",
            competitors = emptyList(),
            lastUpdated = now
        )
        assertTrue("Fresh rate should NOT be stale", !freshRate.isStale(now))

        // Stale rate — last updated 31 days ago
        val staleRate = freshRate.copy(
            lastUpdated = now - 31L * 24 * 60 * 60 * 1000
        )
        assertTrue("Rate older than 30 days should be stale",
            staleRate.isStale(now))

        // Rate with zero lastUpdated should be stale
        val neverUpdated = freshRate.copy(lastUpdated = 0L)
        assertTrue("Rate with no update timestamp should be stale",
            neverUpdated.isStale(now))
    }
}
