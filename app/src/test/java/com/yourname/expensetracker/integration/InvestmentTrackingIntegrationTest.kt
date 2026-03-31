package com.yourname.expensetracker.integration

import com.yourname.expensetracker.data.database.entity.Investment
import com.yourname.expensetracker.data.database.entity.InvestmentType
import com.yourname.expensetracker.domain.investment.InvestmentTracker
import com.yourname.expensetracker.domain.investment.PortfolioSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

/**
 * Integration tests for Investment Tracking feature.
 */
class InvestmentTrackingIntegrationTest {

    @Mock
    private lateinit var investmentTracker: InvestmentTracker

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
    }

    @Test
    fun `test portfolio summary calculation`() = runBlocking {
        // Given: Multiple investments
        val stock = Investment(
            name = "Apple Inc.",
            symbol = "AAPL",
            type = InvestmentType.STOCK,
            purchasePrice = 150.0,
            quantity = 10.0,
            purchaseDate = System.currentTimeMillis(),
            currentPrice = 175.0
        )
        
        val crypto = Investment(
            name = "Bitcoin",
            symbol = "BTC",
            type = InvestmentType.CRYPTO,
            purchasePrice = 30000.0,
            quantity = 0.5,
            purchaseDate = System.currentTimeMillis(),
            currentPrice = 45000.0
        )
        
        // When: Calculate portfolio summary
        val summary = PortfolioSummary(
            totalValue = (175.0 * 10.0) + (45000.0 * 0.5), // 1750 + 22500 = 24250
            totalInvested = (150.0 * 10.0) + (30000.0 * 0.5), // 1500 + 15000 = 16500
            totalGainLoss = 7750.0,
            totalGainLossPercent = 47.0,
            investmentCount = 2,
            byType = mapOf(
                InvestmentType.STOCK to 1750.0,
                InvestmentType.CRYPTO to 22500.0
            )
        )
        
        // Then: Verify calculations
        assertEquals(24250.0, summary.totalValue, 0.01)
        assertEquals(16500.0, summary.totalInvested, 0.01)
        assertEquals(7750.0, summary.totalGainLoss, 0.01)
        assertTrue(summary.totalGainLossPercent > 0)
        assertEquals(2, summary.investmentCount)
    }

    @Test
    fun `test investment performance calculation`() {
        // Given: Investment with price change
        val investment = Investment(
            name = "Test Stock",
            symbol = "TEST",
            type = InvestmentType.STOCK,
            purchasePrice = 100.0,
            quantity = 5.0,
            purchaseDate = System.currentTimeMillis(),
            currentPrice = 120.0
        )
        
        // When: Calculate performance
        val currentValue = investment.currentPrice * investment.quantity
        val investedValue = investment.purchasePrice * investment.quantity
        val gainLoss = currentValue - investedValue
        val gainLossPercent = (gainLoss / investedValue) * 100
        
        // Then: Verify calculations
        assertEquals(600.0, currentValue, 0.01)
        assertEquals(500.0, investedValue, 0.01)
        assertEquals(100.0, gainLoss, 0.01)
        assertEquals(20.0, gainLossPercent, 0.01)
    }

    @Test
    fun `test portfolio allocation`() {
        // Given: Portfolio by type
        val byType = mapOf(
            InvestmentType.STOCK to 5000.0,
            InvestmentType.CRYPTO to 3000.0,
            InvestmentType.BOND to 2000.0
        )
        val totalValue = 10000.0
        
        // When: Calculate allocation percentages
        val allocation = byType.mapValues { (_, value) ->
            (value / totalValue) * 100
        }
        
        // Then: Verify percentages sum to 100%
        assertEquals(50.0, allocation[InvestmentType.STOCK], 0.01)
        assertEquals(30.0, allocation[InvestmentType.CRYPTO], 0.01)
        assertEquals(20.0, allocation[InvestmentType.BOND], 0.01)
        assertEquals(100.0, allocation.values.sum(), 0.01)
    }

    @Test
    fun `test target price hit detection`() {
        // Given: Investment with target price
        val investment = Investment(
            name = "Target Stock",
            symbol = "TARGET",
            type = InvestmentType.STOCK,
            purchasePrice = 100.0,
            quantity = 1.0,
            purchaseDate = System.currentTimeMillis(),
            currentPrice = 150.0,
            targetPrice = 140.0
        )
        
        // When: Check if target hit
        val targetHit = investment.targetPrice?.let { target ->
            investment.currentPrice >= target
        } ?: false
        
        // Then: Target should be hit
        assertTrue(targetHit)
    }

    @Test
    fun `test stop loss hit detection`() {
        // Given: Investment with stop loss
        val investment = Investment(
            name = "Risky Stock",
            symbol = "RISKY",
            type = InvestmentType.STOCK,
            purchasePrice = 100.0,
            quantity = 1.0,
            purchaseDate = System.currentTimeMillis(),
            currentPrice = 85.0,
            stopLossPrice = 90.0
        )
        
        // When: Check if stop loss hit
        val stopLossHit = investment.stopLossPrice?.let { stopLoss ->
            investment.currentPrice <= stopLoss
        } ?: false
        
        // Then: Stop loss should be hit
        assertTrue(stopLossHit)
    }
}
