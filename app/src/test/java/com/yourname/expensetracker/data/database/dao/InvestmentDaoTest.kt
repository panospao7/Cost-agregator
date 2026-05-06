package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Investment
import com.yourname.expensetracker.data.database.entity.InvestmentType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * Unit tests for [InvestmentDao] covering insert, query investments,
 * and value field verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InvestmentDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: InvestmentDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.investmentDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createInvestment(
        name: String = "Apple Inc.",
        symbol: String = "AAPL",
        type: InvestmentType = InvestmentType.STOCK,
        currency: String = "EUR",
        exchange: String? = "NASDAQ",
        purchasePrice: Double = 150.0,
        quantity: Double = 10.0,
        purchaseDate: Long = FIXED_NOW - 30L * 24 * 60 * 60 * 1000,
        purchaseFees: Double = 5.0,
        currentPrice: Double = 175.0,
        lastUpdated: Long = FIXED_NOW,
        category: String? = "Tech",
        notes: String? = "Long term hold",
        isActive: Boolean = true,
        targetPrice: Double? = 200.0,
        stopLossPrice: Double? = 100.0,
        createdAt: Long = FIXED_NOW - 30L * 24 * 60 * 60 * 1000
    ): Investment = Investment(
        name = name,
        symbol = symbol,
        type = type,
        currency = currency,
        exchange = exchange,
        purchasePrice = purchasePrice,
        quantity = quantity,
        purchaseDate = purchaseDate,
        purchaseFees = purchaseFees,
        currentPrice = currentPrice,
        lastUpdated = lastUpdated,
        category = category,
        notes = notes,
        isActive = isActive,
        targetPrice = targetPrice,
        stopLossPrice = stopLossPrice,
        createdAt = createdAt
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `insert an investment holding`() = runTest {
        val investment = createInvestment()
        val id = dao.insert(investment)

        assertTrue(id > 0)

        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertEquals("Apple Inc.", loaded.name)
        assertEquals("AAPL", loaded.symbol)
        assertEquals(InvestmentType.STOCK, loaded.type)
    }

    @Test
    fun `query all investments returns inserted holdings`() = runTest {
        val aapl = createInvestment(name = "Apple Inc.", symbol = "AAPL")
        val msft = createInvestment(name = "Microsoft Corp.", symbol = "MSFT")
        val btc = createInvestment(name = "Bitcoin", symbol = "BTC", type = InvestmentType.CRYPTO)

        dao.insert(aapl)
        dao.insert(msft)
        dao.insert(btc)

        val all = dao.getAllInvestments()
        assertEquals(3, all.size)
    }

    @Test
    fun `getAllActiveInvestments returns only active investments`() = runTest {
        dao.insert(createInvestment(name = "Active Holding", isActive = true))
        dao.insert(createInvestment(name = "Sold Holding", isActive = false))

        val flow = dao.getAllActiveInvestments()
        val active = flow.first()

        assertEquals(1, active.size)
        assertEquals("Active Holding", active[0].name)
    }

    @Test
    fun `verify value fields for an investment`() = runTest {
        val id = dao.insert(
            createInvestment(
                name = "Test Investment",
                symbol = "TST",
                purchasePrice = 100.0,
                quantity = 5.0,
                currentPrice = 120.0,
                purchaseFees = 2.5,
                currency = "USD"
            )
        )

        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertEquals(100.0, loaded.purchasePrice, 0.0001)
        assertEquals(5.0, loaded.quantity, 0.0001)
        assertEquals(120.0, loaded.currentPrice, 0.0001)
        assertEquals(2.5, loaded.purchaseFees, 0.0001)
        assertEquals("USD", loaded.currency)
    }

    @Test
    fun `getTotalPortfolioValue returns sum of currentPrice times quantity`() = runTest {
        dao.insert(createInvestment(symbol = "AAPL", currentPrice = 150.0, quantity = 10.0))
        dao.insert(createInvestment(symbol = "MSFT", currentPrice = 300.0, quantity = 5.0))

        val total = dao.getTotalPortfolioValue()
        assertNotNull(total)
        // 150 * 10 + 300 * 5 = 1500 + 1500 = 3000
        assertEquals(3000.0, total, 0.0001)
    }

    @Test
    fun `getTotalUnrealizedGainLoss returns correct sum`() = runTest {
        // AAPL: (150 - 100) * 10 = 500 gain
        dao.insert(createInvestment(symbol = "AAPL", purchasePrice = 100.0, currentPrice = 150.0, quantity = 10.0))
        // MSFT: (250 - 300) * 5 = -250 loss
        dao.insert(createInvestment(symbol = "MSFT", purchasePrice = 300.0, currentPrice = 250.0, quantity = 5.0))

        val gainLoss = dao.getTotalUnrealizedGainLoss()
        assertNotNull(gainLoss)
        // 500 + (-250) = 250
        assertEquals(250.0, gainLoss, 0.0001)
    }

    @Test
    fun `getTotalInvestedAmount returns sum of purchasePrice times quantity`() = runTest {
        dao.insert(createInvestment(symbol = "AAPL", purchasePrice = 100.0, quantity = 10.0))
        dao.insert(createInvestment(symbol = "MSFT", purchasePrice = 300.0, quantity = 5.0))

        val totalInvested = dao.getTotalInvestedAmount()
        assertNotNull(totalInvested)
        // 100 * 10 + 300 * 5 = 1000 + 1500 = 2500
        assertEquals(2500.0, totalInvested, 0.0001)
    }

    @Test
    fun `aggregate functions return null when no active investments`() = runTest {
        val portfolioValue = dao.getTotalPortfolioValue()
        assertEquals(null, portfolioValue)

        val gainLoss = dao.getTotalUnrealizedGainLoss()
        assertEquals(null, gainLoss)

        val totalInvested = dao.getTotalInvestedAmount()
        assertEquals(null, totalInvested)
    }

    @Test
    fun `updatePrice changes current price and lastUpdated`() = runTest {
        val id = dao.insert(createInvestment())
        val newPrice = 200.0
        val newTimestamp = FIXED_NOW + 5000

        dao.updatePrice(id, newPrice, newTimestamp)

        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertEquals(newPrice, loaded.currentPrice, 0.0001)
        assertEquals(newTimestamp, loaded.lastUpdated)
    }

    @Test
    fun `update investment modifies fields`() = runTest {
        val id = dao.insert(createInvestment())
        val loaded = dao.getById(id)!!
        val updated = loaded.copy(name = "Updated Name", notes = "Updated notes")
        dao.update(updated)

        val reloaded = dao.getById(id)
        assertNotNull(reloaded)
        assertEquals("Updated Name", reloaded.name)
        assertEquals("Updated notes", reloaded.notes)
    }

    @Test
    fun `delete investment removes it`() = runTest {
        val id = dao.insert(createInvestment())
        val loaded = dao.getById(id)!!
        dao.delete(loaded)

        val deleted = dao.getById(id)
        assertEquals(null, deleted)
    }

    @Test
    fun `getByType returns active investments of a given type`() = runTest {
        dao.insert(createInvestment(symbol = "AAPL", type = InvestmentType.STOCK, isActive = true))
        dao.insert(createInvestment(symbol = "MSFT", type = InvestmentType.STOCK, isActive = true))
        dao.insert(createInvestment(symbol = "BTC", type = InvestmentType.CRYPTO, isActive = true))
        dao.insert(createInvestment(symbol = "ETH", type = InvestmentType.CRYPTO, isActive = false))

        val stocksFlow = dao.getByType(InvestmentType.STOCK)
        val stocks = stocksFlow.first()
        assertEquals(2, stocks.size)

        val cryptosFlow = dao.getByType(InvestmentType.CRYPTO)
        val cryptos = cryptosFlow.first()
        assertEquals(1, cryptos.size)
        assertEquals("BTC", cryptos[0].symbol)
    }
}
