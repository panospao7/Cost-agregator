package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RP-05 batch 1 (P5-001 + P5-004): dashboard aggregates must be scoped to the
 * CURRENT period even though the adapter fetches a two-month window, and the
 * shared-expense identity must survive the boundary so deposit exclusion is
 * live. Direct behavioral coverage of
 * [ComputeDashboardWidgetsUseCase.produceDashboardNormalizedInput] — the
 * previous P5AnalyticsFixesTest re-implemented the filters inline.
 */
class ComputeDashboardNormalizedInputWindowTest {

    private val NOW = 1_717_200_000_000L // 2024-06-01T00:00:00Z

    private class TestRateStore : ExchangeRateStore {
        val rates = mutableMapOf<String, com.yourname.expensetracker.domain.currency.DomainExchangeRate>()
        override suspend fun getRate(from: String, to: String) = rates["${from}_${to}"]
        override suspend fun getRateAsOf(from: String, to: String, atMillis: Long) = rates["${from}_${to}"]
        override suspend fun getLatestRateForPair(from: String, to: String) = rates["${from}_${to}"]
        override fun getRatesToCurrency(targetCurrency: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.yourname.expensetracker.domain.currency.DomainExchangeRate>())
        override suspend fun getLatestRate() = rates.values.firstOrNull()
        override suspend fun insertOrUpdate(rate: com.yourname.expensetracker.domain.currency.DomainExchangeRate) { rates["${rate.fromCurrency}_${rate.toCurrency}"] = rate }
        override suspend fun insertOrUpdateAll(rates: List<com.yourname.expensetracker.domain.currency.DomainExchangeRate>) { rates.forEach { insertOrUpdate(it) } }
        override suspend fun deleteOldRates(olderThan: Long) { /* no-op */ }
    }

    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)

    private lateinit var useCase: ComputeDashboardWidgetsUseCase

    @Before
    fun setup() {
        useCase = ComputeDashboardWidgetsUseCase(
            writeBarrier = mockk(relaxed = true),
            insightsEngine = mockk(relaxed = true),
            synthesisEngine = mockk(relaxed = true),
            monteCarloSimulator = mockk(relaxed = true),
            timeProvider = object : TimeProvider { override fun now() = NOW },
            multiCurrencyRepository = mockk(relaxed = true),
            healthCalculator = mockk(relaxed = true),
            healthScoreV2 = mockk(relaxed = true),
            lifestyleSavingsPromptUseCase = mockk(relaxed = true),
            monthlySavingsSweepUseCase = mockk(relaxed = true),
            computeMoneyRadarUseCase = mockk(relaxed = true),
            stressForecastEngine = mockk(relaxed = true),
            forecastInputAssembler = mockk(relaxed = true),
            currencyConverter = CurrencyConverter(
                TestRateStore(),
                object : TimeProvider { override fun now() = NOW }
            ),
            currencySettingsRepository = currencySettingsRepository
        )
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
    }

    private fun purchase(id: Long, amount: Double, date: Long, categoryId: Long? = 7L) = Expense(
        id = id, amount = amount, currency = "EUR", merchant = "M$id",
        transactionType = TransactionType.PURCHASE, date = date,
        categoryId = categoryId, isNotMine = false, isSharedExpense = false,
        isManualEntry = false
    )

    private fun deposit(id: Long, amount: Double, date: Long, shared: Boolean) = Expense(
        id = id, amount = amount, currency = "EUR", merchant = "D$id",
        transactionType = TransactionType.DEPOSIT, date = date,
        categoryId = null, isNotMine = false, isSharedExpense = shared,
        isManualEntry = false
    )

    @Test
    fun `period aggregates exclude the previous-month fetch window`() = runTest {
        val (periodStart, periodEnd) = TimePeriodUtils.getMonthRange(NOW)
        val previousMonth = TimePeriodUtils.getMonthRange(NOW, -1)
        val currentPurchase = purchase(1, 100.0, periodStart + 86_400_000L) // June 2
        val previousPurchase = purchase(2, 250.0, previousMonth.first + 86_400_000L) // May 2

        val result = useCase.produceDashboardNormalizedInput(
            listOf(currentPurchase, previousPurchase), periodStart, periodEnd
        ) as DashboardNormalizedInputResult.Available

        val input = result.input
        assertEquals(100.0, input.periodAggregate.displayAmount, 0.001)
        assertEquals(100.0, input.monthAggregate.displayAmount, 0.001)
        // P5-001: the previous-month purchase must appear ONLY in previousMonthAggregate.
        assertEquals(250.0, input.previousMonthAggregate?.displayAmount ?: -1.0, 0.001)
        // Category aggregates are current-month only (single category bucket).
        assertEquals(
            100.0,
            input.categoryAggregates.values.sumOf { it.displayAmount },
            0.001
        )
    }

    @Test
    fun `period aggregates respect the exclusive period end`() = runTest {
        val (periodStart, periodEnd) = TimePeriodUtils.getMonthRange(NOW)
        val inPeriod = purchase(1, 40.0, periodStart + 3_600_000L)
        val atBoundary = purchase(2, 60.0, periodEnd) // excluded by [start, end)
        val afterBoundary = purchase(3, 80.0, periodEnd + 86_400_000L)

        val result = useCase.produceDashboardNormalizedInput(
            listOf(inPeriod, atBoundary, afterBoundary), periodStart, periodEnd
        ) as DashboardNormalizedInputResult.Available

        assertEquals(40.0, result.input.periodAggregate.displayAmount, 0.001)
        assertEquals(40.0, result.input.monthAggregate.displayAmount, 0.001)
    }

    @Test
    fun `previous month aggregate uses the calendar-previous month`() = runTest {
        val (periodStart, periodEnd) = TimePeriodUtils.getMonthRange(NOW)
        val previousMonth = TimePeriodUtils.getMonthRange(NOW, -1)
        val inPreviousMonth = purchase(1, 90.0, previousMonth.first + 86_400_000L)
        val twoMonthsAgo = purchase(2, 500.0, previousMonth.first - 86_400_000L)

        val result = useCase.produceDashboardNormalizedInput(
            listOf(inPreviousMonth, twoMonthsAgo), periodStart, periodEnd
        ) as DashboardNormalizedInputResult.Available

        assertEquals(90.0, result.input.previousMonthAggregate?.displayAmount ?: -1.0, 0.001)
        assertEquals(0.0, result.input.periodAggregate.displayAmount, 0.001)
    }

    @Test
    fun `shared deposits are excluded from the deposit aggregate`() = runTest {
        val (periodStart, periodEnd) = TimePeriodUtils.getMonthRange(NOW)
        val own = deposit(1, 100.0, periodStart + 86_400_000L, shared = false)
        val shared = deposit(2, 50.0, periodStart + 172_800_000L, shared = true)

        val result = useCase.produceDashboardNormalizedInput(
            listOf(own, shared), periodStart, periodEnd
        ) as DashboardNormalizedInputResult.Available

        assertEquals(100.0, result.input.depositAggregate?.displayAmount ?: -1.0, 0.001)
    }

    @Test
    fun `normalized expenses keep the full fetched window for the forecast baseline`() = runTest {
        val (periodStart, periodEnd) = TimePeriodUtils.getMonthRange(NOW)
        val previousMonth = TimePeriodUtils.getMonthRange(NOW, -1)
        val current = purchase(1, 100.0, periodStart + 86_400_000L)
        val previous = purchase(2, 250.0, previousMonth.first + 86_400_000L)

        val result = useCase.produceDashboardNormalizedInput(
            listOf(current, previous), periodStart, periodEnd
        ) as DashboardNormalizedInputResult.Available

        assertTrue(
            "normalizedExpenses must retain the wider fetch (MoM/trend baseline)",
            result.input.normalizedExpenses.any { it.id == 2L }
        )
        assertEquals(2, result.input.normalizedExpenses.size)
    }
}
