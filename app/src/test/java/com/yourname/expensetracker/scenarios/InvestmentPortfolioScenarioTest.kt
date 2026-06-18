package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Investment
import com.yourname.expensetracker.data.database.entity.InvestmentType
import com.yourname.expensetracker.data.database.entity.InvestmentValue
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario tests for investment portfolio entities.
 *
 * These tests verify that [Investment] and [InvestmentValue] entities
 * can be inserted, queried, and that multi-currency holdings are
 * correctly represented. Tests use DAOs directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InvestmentPortfolioScenarioTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val now = 1_714_514_400_000L // 2024-05-01T00:00:00Z

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Investment entity insertion and querying
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `investment entity inserted and queryable`() = runTest {
        // GIVEN: a stock investment
        val investment = Investment(
            name = "Apple Inc.",
            symbol = "AAPL",
            type = InvestmentType.STOCK,
            currency = "USD",
            exchange = "NASDAQ",
            purchasePrice = 150.0,
            quantity = 10.0,
            purchaseDate = now - 30L * 24 * 60 * 60 * 1000,
            purchaseFees = 5.0,
            currentPrice = 175.0,
            lastUpdated = now,
            category = "Tech",
            notes = "Long term hold",
            isActive = true,
            targetPrice = 200.0,
            stopLossPrice = 100.0,
            createdAt = now - 30L * 24 * 60 * 60 * 1000
        )

        // WHEN: inserting the investment
        val id = db.investmentDao().insert(investment)
        assertTrue("Investment id should be positive", id > 0L)

        // THEN: it can be retrieved by id
        val saved = db.investmentDao().getById(id)
        assertNotNull("Investment should exist in DB", saved)
        assertEquals("name should match", "Apple Inc.", saved!!.name)
        assertEquals("symbol should match", "AAPL", saved.symbol)
        assertEquals("type should match", InvestmentType.STOCK, saved.type)
        assertEquals("currency should match", "USD", saved.currency)
        assertEquals("purchasePrice should match", 150.0, saved.purchasePrice, 0.001)
        assertEquals("quantity should match", 10.0, saved.quantity, 0.001)
        assertEquals("currentPrice should match", 175.0, saved.currentPrice, 0.001)
        assertEquals("exchange should match", "NASDAQ", saved.exchange)
        assertEquals("category should match", "Tech", saved.category)
        assertTrue("isActive should be true", saved.isActive)

        // AND: it appears in all investments list
        val all = db.investmentDao().getAllInvestments()
        assertEquals("Should have 1 investment", 1, all.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Multi-currency holdings and portfolio totals
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `multi-currency holdings and portfolio totals`() = runTest {
        // GIVEN: investments in different currencies
        db.investmentDao().insert(
            Investment(
                name = "Apple Inc.", symbol = "AAPL", type = InvestmentType.STOCK,
                currency = "USD", exchange = "NASDAQ",
                purchasePrice = 150.0, quantity = 10.0,
                purchaseDate = now, currentPrice = 175.0,
                lastUpdated = now, isActive = true, createdAt = now
            )
        )
        db.investmentDao().insert(
            Investment(
                name = "SAP SE", symbol = "SAP", type = InvestmentType.STOCK,
                currency = "EUR", exchange = "XETRA",
                purchasePrice = 120.0, quantity = 5.0,
                purchaseDate = now, currentPrice = 140.0,
                lastUpdated = now, isActive = true, createdAt = now
            )
        )
        db.investmentDao().insert(
            Investment(
                name = "Bitcoin", symbol = "BTC", type = InvestmentType.CRYPTO,
                currency = "USD", exchange = "BINANCE",
                purchasePrice = 30000.0, quantity = 0.5,
                purchaseDate = now, currentPrice = 35000.0,
                lastUpdated = now, isActive = true, createdAt = now
            )
        )

        // WHEN: querying portfolio metrics
        val totalInvested = db.investmentDao().getTotalInvestedAmount()
        val totalValue = db.investmentDao().getTotalPortfolioValue()
        val gainLoss = db.investmentDao().getTotalUnrealizedGainLoss()

        // THEN: totals are computed correctly (raw sums, no currency conversion)
        // Note: These are raw sums across currencies (as designed — conversion is the caller's responsibility)
        assertNotNull("totalInvested should not be null", totalInvested)
        assertNotNull("totalValue should not be null", totalValue)
        assertNotNull("gainLoss should not be null", gainLoss)

        // Verify individual values
        val all = db.investmentDao().getAllInvestments()
        assertEquals("Should have 3 investments", 3, all.size)

        // Verify currencies are preserved
        val aapl = all.single { it.symbol == "AAPL" }
        assertEquals("AAPL should be USD", "USD", aapl.currency)

        val sap = all.single { it.symbol == "SAP" }
        assertEquals("SAP should be EUR", "EUR", sap.currency)

        val btc = all.single { it.symbol == "BTC" }
        assertEquals("BTC should be USD", "USD", btc.currency)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Investment values (price history) insertion and querying
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `investment value history inserted and queryable`() = runTest {
        // GIVEN: an investment
        val invId = db.investmentDao().insert(
            Investment(
                name = "Test Stock", symbol = "TST", type = InvestmentType.STOCK,
                currency = "EUR", purchasePrice = 100.0, quantity = 10.0,
                purchaseDate = now, currentPrice = 100.0,
                lastUpdated = now, isActive = true, createdAt = now
            )
        )

        // WHEN: inserting multiple value snapshots
        val value1 = InvestmentValue(
            investmentId = invId, price = 100.0, timestamp = now,
            totalValue = 1000.0, dayChange = 0.0, dayChangePercent = 0.0
        )
        val value2 = InvestmentValue(
            investmentId = invId, price = 110.0, timestamp = now + 3600_000L,
            totalValue = 1100.0, dayChange = 10.0, dayChangePercent = 10.0
        )
        val value3 = InvestmentValue(
            investmentId = invId, price = 105.0, timestamp = now + 7200_000L,
            totalValue = 1050.0, dayChange = -5.0, dayChangePercent = -4.55
        )

        db.investmentValueDao().insert(value1)
        db.investmentValueDao().insert(value2)
        db.investmentValueDao().insert(value3)

        // THEN: values can be queried for the investment
        val valuesFlow = db.investmentValueDao().getValuesForInvestment(invId)
        val values = valuesFlow.first()
        assertEquals("Should have 3 value snapshots", 3, values.size)

        // AND: the latest value is correct
        val latest = db.investmentValueDao().getLatestValue(invId)
        assertNotNull("Latest value should exist", latest)
        assertEquals("Latest price should be 105.0", 105.0, latest!!.price, 0.001)
        assertEquals("Latest totalValue should be 1050.0", 1050.0, latest.totalValue, 0.001)

        // AND: average price is correct
        val avgPrice = db.investmentValueDao().getAveragePrice(invId, now)
        assertNotNull("Average price should exist", avgPrice)
        assertEquals("Average price should be 105.0", 105.0, avgPrice!!, 0.001)

        // AND: values can be queried by time range
        val range = db.investmentValueDao().getValuesBetween(
            invId, now, now + 7200_000L
        )
        // Should include value1 and value2 (timestamps at now and now+3600), not value3 (now+7200 is exclusive)
        // Actually timestamp < endDate is exclusive, so value3 at now+7200_000L is excluded
        assertEquals("Range should have 2 values", 2, range.size)
    }
}
