package com.yourname.expensetracker.verification

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.lifestyle.LifestyleInflationDetector
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LifestyleAnalysisTest : AnalyticsEngineTestBase() {

    private lateinit var detector: LifestyleInflationDetector

    @Before
    override fun setUp() {
        super.setUp()
        rateStore = LifestyleFakeRateStore()
        homeCurrencyRepo = mockk<CurrencySettingsRepository>()
        every { homeCurrencyRepo.homeCurrency() } returns flowOf("EUR")
        detector = LifestyleInflationDetector(
            expenseDao,
            this.timeProvider,
            AnalyticsCurrencyNormalizer(CurrencyConverter(rateStore, timeProvider = mockk())),
            homeCurrencyRepo
        )
    }

    private lateinit var rateStore: LifestyleFakeRateStore
    private lateinit var homeCurrencyRepo: CurrencySettingsRepository

    @Test
    fun `BL-05 correlation pairs only months present in both series in chronological order`() = runTest {
        // Income Jan/Feb/Mar = 100/200/300, spending Feb/Mar = 20/30 (no January spending).
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                incomeId(1, "2026-01-10", 100.0), incomeId(2, "2026-02-10", 200.0), incomeId(3, "2026-03-10", 300.0),
                spendId(4, "2026-02-12", 20.0), spendId(5, "2026-03-12", 30.0)
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 6)

        // Only the (Feb, Mar) pairs (200,20) and (300,30) are correlated: exactly +1.0.
        // Unaligned pairing of [100,200,300] with [20,30] would have produced 0.0.
        assertApproxEquals(1.0, report.incomeSpendingCorrelation, 0.0001)
    }

    @Test
    fun `BL-05 correlation pairs months by month not by series insertion order`() = runTest {
        // Income inserted Mar, Jan, Feb; spending inserted Jan, Feb, Mar.
        // By month: income 100/200/300 vs spending 10/30/20 => hand-computed r = 0.5.
        // Pairing by insertion order would give (300,10),(100,30),(200,20) => r = -1.0.
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                incomeId(1, "2026-03-10", 300.0), incomeId(2, "2026-01-10", 100.0), incomeId(3, "2026-02-10", 200.0),
                spendId(4, "2026-01-12", 10.0), spendId(5, "2026-02-12", 30.0), spendId(6, "2026-03-12", 20.0)
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 6)

        assertApproxEquals(0.5, report.incomeSpendingCorrelation, 0.0001)
    }

    @Test
    fun `BL-05 unconvertible JPY without a rate throws MoneyDisplayUnavailableException`() = runTest {
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                incomeId(1, "2026-03-05", 1000.0),
                spendId(2, "2026-03-10", 1000.0, currency = "JPY")
            )
        )

        try {
            detector.analyzeLifestyleInflation(monthsToAnalyze = 3)
            fail("Expected MoneyDisplayUnavailableException: no partial report may be produced")
        } catch (expected: MoneyDisplayUnavailableException) {
            // fail closed
        }
    }

    private fun typedUsd(id: Long, type: TransactionType): Expense = Expense(
        id = id,
        amount = 200.0,
        currency = "USD",
        merchant = "Synthetic $type",
        transactionType = type,
        date = millis("2026-03-10"),
        createdAt = 1L
    )

    @Test
    fun `BL-05 a 200 USD TRANSFER with no rate does not throw and the report is produced`() = runTest {
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(incomeId(1, "2026-03-05", 1000.0), spendId(2, "2026-03-08", 100.0), typedUsd(3, TransactionType.TRANSFER))
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 3)

        // The neutral transfer never reaches conversion; the EUR spend stays the March spending.
        val march = report.monthlyData.single { it.month == "2026-03" }
        assertApproxEquals(100.0, march.totalSpending, 0.0001)
    }

    @Test
    fun `BL-05 a 200 USD WITHDRAWAL with no rate does not throw and the report is produced`() = runTest {
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(incomeId(1, "2026-03-05", 1000.0), spendId(2, "2026-03-08", 100.0), typedUsd(3, TransactionType.WITHDRAWAL))
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 3)

        assertTrue("a report is produced for the month", report.monthlyData.any { it.month == "2026-03" })
    }

    @Test
    fun `BL-05 a 200 USD PURCHASE with no rate still throws MoneyDisplayUnavailableException`() = runTest {
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(incomeId(1, "2026-03-05", 1000.0), typedUsd(2, TransactionType.PURCHASE))
        )

        try {
            detector.analyzeLifestyleInflation(monthsToAnalyze = 3)
            fail("Expected MoneyDisplayUnavailableException for an unconvertible PURCHASE")
        } catch (expected: MoneyDisplayUnavailableException) {
            // fail closed
        }
    }

    @Test
    fun `BL-05 EUR plus JPY at a known rate sums to the converted home-currency value`() = runTest {
        rateStore.putRate("JPY", "EUR", rate = 0.0062)
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                incomeId(1, "2026-03-05", 1000.0),
                spendId(2, "2026-03-10", 100.0),
                spendId(3, "2026-03-11", 10_000.0, currency = "JPY")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 3)

        // 10,000 JPY * 0.0062 = 62 EUR, plus 100 EUR = 162 EUR (never 10,100 raw).
        val march = report.monthlyData.single { it.month == "2026-03" }
        assertApproxEquals(162.0, march.totalSpending, 0.0001)
        assertApproxEquals(83.8, march.savingsRate, 0.0001)
    }

    @Test
    fun `BL-05 a stale but present exchange rate still gives a report with the converted value`() = runTest {
        // validDate two months before the spend: more than 7 days apart, so the normalizer
        // raises exactly one STALE_EXCHANGE_RATE warning and nothing severe.
        rateStore.putRate("JPY", "EUR", rate = 0.0062, validDate = millis("2026-01-10"))
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                incomeId(1, "2026-03-05", 1000.0),
                spendId(2, "2026-03-10", 100.0),
                spendId(3, "2026-03-11", 10_000.0, currency = "JPY")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 3)

        val march = report.monthlyData.single { it.month == "2026-03" }
        assertApproxEquals(162.0, march.totalSpending, 0.0001)
    }

    @Test
    fun `BL-05 CancellationException from the home currency source propagates`() = runTest {
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(emptyList())
        every { homeCurrencyRepo.homeCurrency() } returns flow { throw CancellationException("cancelled") }

        try {
            detector.analyzeLifestyleInflation(monthsToAnalyze = 3)
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            // propagated, not converted into unavailable
        }
    }

    private fun incomeId(id: Long, date: String, amount: Double): Expense = Expense(
        id = id,
        amount = amount,
        merchant = "Employer",
        transactionType = TransactionType.DEPOSIT,
        date = millis(date),
        createdAt = 1L
    )

    private fun spendId(id: Long, date: String, amount: Double, currency: String = "EUR"): Expense = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = "supermarket",
        transactionType = TransactionType.PURCHASE,
        date = millis(date),
        createdAt = 1L
    )

    private class LifestyleFakeRateStore : ExchangeRateStore {
        private val rates = mutableMapOf<Pair<String, String>, DomainExchangeRate>()

        fun putRate(from: String, to: String, rate: Double, validDate: Long = 0L) {
            rates[from to to] = DomainExchangeRate(
                fromCurrency = from, toCurrency = to, rate = rate, lastUpdated = 1234L, validDate = validDate
            )
        }

        override suspend fun getRate(fromCurrency: String, toCurrency: String) = rates[fromCurrency to toCurrency]
        override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String) =
            rates[fromCurrency to toCurrency]
        override suspend fun insertOrUpdate(rate: DomainExchangeRate) {
            rates[rate.fromCurrency to rate.toCurrency] = rate
        }
        override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) = rates.forEach { insertOrUpdate(it) }
        override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long) =
            getRate(fromCurrency, toCurrency)
        override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = emptyFlow()
        override suspend fun getLatestRate(): DomainExchangeRate? = rates.values.maxByOrNull { it.lastUpdated }
        override suspend fun deleteOldRates(olderThan: Long) {
            rates.entries.removeAll { (_, rate) -> rate.lastUpdated < olderThan }
        }
    }

    @Test
    fun `income elasticity calculates correctly`() = runTest {
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "supermarket"),
                income("2026-02-05", 1100.0), spend("2026-02-10", 550.0, "supermarket")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 3)

        // elasticity = (%Δspending) / (%Δincome) = (10%) / (10%) = 1.0
        assertApproxEquals(1.0, report.incomeElasticity, 0.0001)
    }

    @Test
    fun `growth trends use chronological month keys rather than source insertion order`() = runTest {
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-03-05", 1200.0), spend("2026-03-10", 900.0, "restaurant"),
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "supermarket"),
                income("2026-02-05", 1100.0), spend("2026-02-10", 550.0, "supermarket")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 6)

        assertApproxEquals(0.20, report.incomeGrowthRate, 0.0001)
        assertApproxEquals(0.80, report.spendingGrowthRate, 0.0001)
    }

    @Test
    fun `lifestyle inflation detects creep`() = runTest {
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "Grocer"),
                income("2026-02-05", 1100.0), spend("2026-02-10", 650.0, "restaurant"),
                income("2026-03-05", 1200.0), spend("2026-03-10", 900.0, "restaurant")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 6)

        assertTrue(report.spendingGrowthRate > report.incomeGrowthRate)
        assertTrue(report.lifestyleCreepDetected)
        assertTrue(report.lifestyleCreepAlerts.isNotEmpty())
    }

    @Test
    fun `hedonic adaptation detects discretionary volatility`() = runTest {
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 2000.0), spend("2026-01-10", 100.0, "restaurant"),
                income("2026-02-05", 2000.0), spend("2026-02-10", 500.0, "vacation"),
                income("2026-03-05", 2000.0), spend("2026-03-10", 200.0, "streaming")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 6)

        assertTrue(report.hedonicAdaptationScore > 0.0)
    }

    @Test
    fun `spending patterns analyze monthly distribution fields`() = runTest {
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-03-05", 1000.0),
                spend("2026-03-10", 300.0, "supermarket"),
                spend("2026-03-12", 200.0, "restaurant")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 2)
        val march = report.monthlyData.single { it.month == "2026-03" }

        assertApproxEquals(500.0, march.totalSpending, 0.0001)
        assertApproxEquals(200.0, march.discretionarySpending, 0.0001)
        assertApproxEquals(300.0, march.essentialSpending, 0.0001)
        assertApproxEquals(50.0, march.savingsRate, 0.0001)
    }

    @Test
    fun `empty dataset returns neutral analysis`() = runTest {
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(emptyList())

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 12)

        assertApproxEquals(0.0, report.incomeElasticity, 0.0)
        assertApproxEquals(0.0, report.incomeGrowthRate, 0.0)
        assertApproxEquals(0.0, report.spendingGrowthRate, 0.0)
        assertApproxEquals(0.0, report.hedonicAdaptationScore, 0.0)
        assertFalse(report.lifestyleCreepDetected)
        assertTrue(report.monthlyData.isEmpty())
    }

    @Test
    fun `single income change event handled`() = runTest {
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-03-05", 3000.0),
                spend("2026-03-10", 900.0, "supermarket")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 2)

        assertApproxEquals(0.0, report.incomeElasticity, 0.0)
    }

    @Test
    fun `income_elasticity_zero_and_negative_income_change`() = runTest {
        // Case A: zero income change should avoid divide-by-zero and yield neutral elasticity
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "Grocer"),
                income("2026-02-05", 1000.0), spend("2026-02-10", 550.0, "Grocer")
            )
        )
        val zeroIncomeChange = detector.analyzeLifestyleInflation(monthsToAnalyze = 3)
        assertApproxEquals(0.0, zeroIncomeChange.incomeElasticity, 0.0001)

        // Case B: negative income change with spending decrease should produce finite, stable elasticity
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "Grocer"),
                income("2026-02-05", 900.0), spend("2026-02-10", 450.0, "Grocer")
            )
        )
        val negativeIncomeChange = detector.analyzeLifestyleInflation(monthsToAnalyze = 3)
        assertApproxEquals(1.0, negativeIncomeChange.incomeElasticity, 0.0001)
        assertTrue(negativeIncomeChange.incomeElasticity.isFinite())
    }

    // ── fix-1.0.4 (owner-approved revalidation fix, item 143): sustained-growth creep + calendar look-back ──

    @Test
    fun `look-back start is the calendar month subtraction of monthsToAnalyze from now`() = runTest {
        val zone = ZoneId.systemDefault()
        // 31 Mar minus 6 months clamps to 30 Sep; minus 1 month clamps to 28 Feb 2026; minus 12 is 31 Mar 2025.
        val end = java.time.ZonedDateTime.of(2026, 3, 31, 12, 0, 0, 0, zone)
        every { timeProvider.now() } returns end.toInstant().toEpochMilli()
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(emptyList())

        for ((months, expectedStart) in listOf(
            6 to java.time.ZonedDateTime.of(2025, 9, 30, 12, 0, 0, 0, zone),
            1 to java.time.ZonedDateTime.of(2026, 2, 28, 12, 0, 0, 0, zone),
            12 to java.time.ZonedDateTime.of(2025, 3, 31, 12, 0, 0, 0, zone)
        )) {
            detector.analyzeLifestyleInflation(monthsToAnalyze = months)

            io.mockk.verify {
                expenseDao.getExpensesBetweenFlowUncapped(
                    expectedStart.toInstant().toEpochMilli(),
                    end.toInstant().toEpochMilli()
                )
            }
        }
    }

    @Test
    fun `creep single-month spike does not raise an alert`() = runTest {
        // Jan to Feb spending falls (no prior growth); only March spikes (+60% spending vs +10% income).
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 510.0, "Grocer"),
                income("2026-02-05", 1001.0), spend("2026-02-10", 500.0, "Grocer"),
                income("2026-03-05", 1100.0), spend("2026-03-10", 800.0, "Grocer")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 6)

        assertFalse(report.lifestyleCreepDetected)
        assertTrue(report.lifestyleCreepAlerts.isEmpty())
    }

    @Test
    fun `creep previous month where spending grew slower than income does not raise an alert`() = runTest {
        // Feb: income +20%, spending +8%. March alone grows fast (+8.3% income, +48% spending).
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "Grocer"),
                income("2026-02-05", 1200.0), spend("2026-02-10", 540.0, "Grocer"),
                income("2026-03-05", 1300.0), spend("2026-03-10", 800.0, "Grocer")
            )
        )

        val report = detector.analyzeLifestyleInflation(monthsToAnalyze = 6)

        assertTrue(report.lifestyleCreepAlerts.isEmpty())
    }

    @Test
    fun `creep with a missing calendar month in the series raises no alert`() = runTest {
        // Jan, Feb, Apr: April is not adjacent to February, so there is no consecutive trio.
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "Grocer"),
                income("2026-02-05", 1100.0), spend("2026-02-10", 650.0, "Grocer"),
                income("2026-04-05", 1200.0), spend("2026-04-10", 900.0, "Grocer")
            )
        )
        val gapAfterFirst = detector.analyzeLifestyleInflation(monthsToAnalyze = 6)

        // Jan, Mar, Apr: January is not adjacent to March.
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "Grocer"),
                income("2026-03-05", 1100.0), spend("2026-03-10", 650.0, "Grocer"),
                income("2026-04-05", 1200.0), spend("2026-04-10", 900.0, "Grocer")
            )
        )
        val gapBeforeLast = detector.analyzeLifestyleInflation(monthsToAnalyze = 6)

        assertTrue(gapAfterFirst.lifestyleCreepAlerts.isEmpty())
        assertTrue(gapBeforeLast.lifestyleCreepAlerts.isEmpty())
    }

    @Test
    fun `creep two consecutive growth months with spending outpacing income raises a HIGH alert`() = runTest {
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "Grocer"),
                income("2026-02-05", 1100.0), spend("2026-02-10", 650.0, "Grocer"),
                income("2026-03-05", 1200.0), spend("2026-03-10", 900.0, "Grocer")
            )
        )

        val alert = detector.analyzeLifestyleInflation(monthsToAnalyze = 6).lifestyleCreepAlerts.single()

        // Hand-computed: income 100/1100 = 9.0909%, spending 250/650 = 38.4615% (> 2x income: HIGH).
        assertEquals("2026-03", alert.month)
        assertApproxEquals(9.0909, alert.incomeGrowthPercent, 0.001)
        assertApproxEquals(38.4615, alert.spendingGrowthPercent, 0.001)
        assertEquals(LifestyleInflationDetector.CreepSeverity.HIGH, alert.severity)
    }

    @Test
    fun `creep threshold boundary spending growth at 1_5x income growth is not an alert and above it is MEDIUM`() = runTest {
        // Feb: income +10%, spending +20% (sustained). March income growth is 100/1100 = 9.0909%,
        // so the 1.5x threshold is 13.636% spending growth: 680/600 = +13.33% (no), 690/600 = +15% (yes, MEDIUM <= 2x).
        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "Grocer"),
                income("2026-02-05", 1100.0), spend("2026-02-10", 600.0, "Grocer"),
                income("2026-03-05", 1200.0), spend("2026-03-10", 680.0, "Grocer")
            )
        )
        val below = detector.analyzeLifestyleInflation(monthsToAnalyze = 6)

        every { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) } returns flowOf(
            listOf(
                income("2026-01-05", 1000.0), spend("2026-01-10", 500.0, "Grocer"),
                income("2026-02-05", 1100.0), spend("2026-02-10", 600.0, "Grocer"),
                income("2026-03-05", 1200.0), spend("2026-03-10", 690.0, "Grocer")
            )
        )
        val above = detector.analyzeLifestyleInflation(monthsToAnalyze = 6)

        assertTrue(below.lifestyleCreepAlerts.isEmpty())
        assertEquals(LifestyleInflationDetector.CreepSeverity.MEDIUM, above.lifestyleCreepAlerts.single().severity)
    }

    private fun income(date: String, amount: Double): Expense = Expense(
        id = amount.toLong(),
        amount = amount,
        merchant = "Employer",
        transactionType = TransactionType.DEPOSIT,
        date = millis(date),
        createdAt = System.currentTimeMillis()
    )

    private fun spend(date: String, amount: Double, merchant: String): Expense = Expense(
        id = (amount * 10).toLong(),
        amount = amount,
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = millis(date),
        createdAt = System.currentTimeMillis()
    )

    private fun millis(isoDate: String): Long =
        LocalDate.parse(isoDate)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
