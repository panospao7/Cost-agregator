package com.yourname.expensetracker.scenarios

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.InvestmentDao
import com.yourname.expensetracker.data.database.dao.InvestmentTransactionDao
import com.yourname.expensetracker.data.database.dao.InvestmentValueDao
import com.yourname.expensetracker.data.database.entity.Investment
import com.yourname.expensetracker.data.database.entity.InvestmentType
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.investment.InvestmentTracker
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TEST_DATE = 1_710_000_000_000L

/**
 * Golden scenario tests for InvestmentTracker.
 *
 * Covers:
 * - investmentAddHoldingAtomic — atomic holding creation with value snapshot
 * - investmentPortfolioSummaryMoneyAggregate — multi-currency aggregate summary
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Config.OLDEST_SDK])
class InvestmentGoldenScenarioTest {

    private lateinit var database: AppDatabase
    private lateinit var investmentDao: InvestmentDao
    private lateinit var investmentValueDao: InvestmentValueDao
    private lateinit var investmentTransactionDao: InvestmentTransactionDao
    private lateinit var tracker: InvestmentTracker
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)

    @Before
    fun setup() {
        database = AppDatabaseTestFactory.create(
            ApplicationProvider.getApplicationContext()
        )
        every { timeProvider.now() } returns TEST_DATE

        investmentDao = database.investmentDao()
        investmentValueDao = database.investmentValueDao()
        investmentTransactionDao = database.investmentTransactionDao()

        tracker = InvestmentTracker(
            database = database,
            investmentDao = investmentDao,
            investmentValueDao = investmentValueDao,
            investmentTransactionDao = investmentTransactionDao,
            timeProvider = timeProvider,
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            ioDispatcher = Dispatchers.IO
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── investmentAddHoldingAtomic ──────────────────────────────────────────

    @Test
    fun `investmentAddHoldingAtomic`() = runTest {
        val investment = Investment(
            name = "Apple Inc.",
            symbol = "AAPL",
            type = InvestmentType.STOCK,
            currency = "USD",
            purchasePrice = 150.0,
            quantity = 10.0,
            purchaseDate = TEST_DATE - 86_400_000L,
            exchange = "NASDAQ"
        )

        val result = tracker.addHolding(investment)
        val holdingId = result.getOrNull() ?: error("addHolding failed")
        assertThat(holdingId).isGreaterThan(0)

        // Verify the investment exists in the DB with expected values
        val saved = investmentDao.getById(holdingId)
        assertThat(saved).isNotNull()
        assertThat(saved!!.name).isEqualTo("Apple Inc.")
        assertThat(saved.symbol).isEqualTo("AAPL")
        assertThat(saved.type).isEqualTo(InvestmentType.STOCK)
        assertThat(saved.quantity).isEqualTo(10.0)
        assertThat(saved.purchasePrice).isEqualTo(150.0)

        // Verify a value snapshot was created
        val values = investmentValueDao.getValuesForInvestment(holdingId).first()
        assertThat(values).isNotEmpty()
        assertThat(values.first().price).isEqualTo(150.0)
        assertThat(values.first().totalValue).isEqualTo(1500.0)

        // Verify a BUY transaction was recorded
        val txns = investmentTransactionDao.getByHoldingId(holdingId)
        assertThat(txns).hasSize(1)
        assertThat(txns.first().type).isEqualTo("BUY")
        assertThat(txns.first().quantity).isEqualTo(10.0)
    }

    // ── investmentPortfolioSummaryMoneyAggregate ────────────────────────────

    @Test
    fun `investmentPortfolioSummaryMoneyAggregate`() = runTest {
        // Seed two holdings in different currencies
        val eurId = seedHolding("Siemens", "SIE", InvestmentType.STOCK, "EUR", 180.0, 5.0)
        val usdId = seedHolding("Tesla", "TSLA", InvestmentType.STOCK, "USD", 250.0, 3.0)

        // Update prices to distinct values via the DAO so the aggregate sees them
        investmentDao.updatePrice(eurId, 190.0, TEST_DATE)
        investmentDao.updatePrice(usdId, 260.0, TEST_DATE)

        // Mock home currency and converter behavior
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")

        // Make converter return a successful conversion for USD→EUR
        coEvery { currencyConverter.convertMultiple(any(), any()) } answers {
            val amounts = arg<List<Pair<Double, String>>>(0)
            val target = arg<String>(1)
            val totalConverted = amounts.sumOf { (amount, _) -> amount * 0.92 } // pretend 1 USD = 0.92 EUR
            com.yourname.expensetracker.domain.currency.MultiConversionAggregate(
                total = totalConverted,
                targetCurrency = target,
                failedConversions = emptyList()
            )
        }

        val holdings = investmentDao.getAllActiveInvestments().first()
        assertThat(holdings).hasSize(2)

        val (summary, aggregate, dataQuality) = tracker.getPortfolioSummaryAggregate(holdings)

        // Summary totals
        assertThat(summary.investmentCount).isEqualTo(2)
        assertThat(summary.totalValue).isEqualTo(190.0 * 5.0 + 260.0 * 3.0) // 950 + 780 = 1730

        // Aggregate must have multiple source buckets (EUR and USD)
        assertThat(aggregate.sourceBuckets).hasSize(2)
        val bucketCurrencies = aggregate.sourceBuckets.map { it.currency.code }
        assertThat(bucketCurrencies).containsExactly("EUR", "USD")

        // Exact bucket amounts
        val eurBucket = aggregate.sourceBuckets.first { it.currency.code == "EUR" }
        val usdBucket = aggregate.sourceBuckets.first { it.currency.code == "USD" }
        assertThat(eurBucket.amount).isWithin(0.01).of(950.0)  // 5 × 190
        assertThat(usdBucket.amount).isWithin(0.01).of(780.0)  // 3 × 260

        // Display total in EUR: all amounts × 0.92 (mocked converter)
        // Converter receives [(950, EUR), (780, USD)] and returns sum * 0.92
        // = (950 + 780) * 0.92 = 1591.6
        assertThat(aggregate.displayAmount).isWithin(1.0).of(1591.6)

        assertThat(dataQuality.isPartial).isFalse()
    }

    // ── investment performance has dataQuality per row ──────────────────────

    @Test
    fun `investment performance has dataQuality per row`() = runTest {
        // Seed a holding
        val investment = Investment(name = "Test", symbol = "TST", type = InvestmentType.STOCK, currency = "EUR", quantity = 10.0, purchasePrice = 100.0, purchaseDate = TEST_DATE)
        tracker.addHolding(investment)
        // Get performances
        val performances = tracker.getInvestmentPerformances()
        assertThat(performances).isNotEmpty()
        val perf = performances.first()
        assertThat(perf.dataQuality).isNotNull()
        assertThat(perf.dataQuality.isPartial).isFalse() // newly added, price is fresh
        assertThat(perf.dataQuality.staleHoldingCount).isEqualTo(0)
        assertThat(perf.dataQuality.lastUpdatedAt).isGreaterThan(0)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private suspend fun seedHolding(
        name: String,
        symbol: String,
        type: InvestmentType,
        currency: String,
        purchasePrice: Double,
        quantity: Double
    ): Long {
        val investment = Investment(
            name = name,
            symbol = symbol,
            type = type,
            currency = currency,
            purchasePrice = purchasePrice,
            quantity = quantity,
            purchaseDate = TEST_DATE - 86_400_000L,
            currentPrice = purchasePrice,
            createdAt = TEST_DATE,
            lastUpdated = TEST_DATE
        )
        val id = investmentDao.insert(investment)
        // Manually record initial value snapshot (what addHolding does atomically)
        investmentValueDao.insert(
            com.yourname.expensetracker.data.database.entity.InvestmentValue(
                investmentId = id,
                price = purchasePrice,
                totalValue = purchasePrice * quantity,
                timestamp = TEST_DATE
            )
        )
        return id
    }
}
