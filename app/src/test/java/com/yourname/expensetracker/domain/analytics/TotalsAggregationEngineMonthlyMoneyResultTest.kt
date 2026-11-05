package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MonthMoneyAggregate
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.model.PeriodTotal
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * N-08-5 (monthly path): an UNAVAILABLE-quality month must surface as
 * [MoneyAggregateResult.Unavailable] (as the yearly path does), never as an Available aggregate whose
 * 0.0 amount would render as zero spend. A month with no aggregate at all has no rows, so a null
 * moneyResult and a total of 0 stay correct there.
 */
class TotalsAggregationEngineMonthlyMoneyResultTest {

    private val eur = CurrencyCode("EUR")
    private val usd = CurrencyCode("USD")
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>()
    private val multiCurrencyRepo = mockk<MultiCurrencyRepository>()
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val engine = TotalsAggregationEngine(
        expenseRepository, timeProvider, multiCurrencyRepo, categoryRepository, Dispatchers.Unconfined
    )

    private val apr15of2026 =
        LocalDate.of(2026, 4, 15).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun stubMonths(vararg months: MonthMoneyAggregate) {
        every { timeProvider.now() } returns apr15of2026
        every { expenseRepository.getTotalSpent() } returns flowOf(null)
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns months.toList()
    }

    /** A month whose every bucket failed conversion: nothing can be displayed in the home currency. */
    private fun unavailableAggregate(isPartialFlag: Boolean = false, warning: String? = "rates missing") =
        MoneyAggregate(
            displayAmount = 0.0,
            displayCurrency = eur,
            sourceBuckets = listOf(MoneyBucket(usd, 250.0, 4)),
            conversionFailures = emptyList(),
            isPartial = isPartialFlag,
            warningMessage = warning,
            rateBasis = RateBasis.TRANSACTION_DATE,
            requestedRateBasis = RateBasis.TRANSACTION_DATE,
            actualRateBasis = RateBasis.TRANSACTION_DATE,
            conversionQuality = ConversionQuality.UNAVAILABLE
        )

    private fun List<PeriodTotal>.month(key: String) = single { it.periodKey == key }

    @Test
    fun `N-08-5 control a normal month carries an Available money result with its total`() = runTest {
        stubMonths(MonthMoneyAggregate("2026-03", MoneyAggregate.singleCurrency(300.0, eur, 3)))

        val march = engine.getMonthlyTotals(2026).first().month("2026-03")

        assertTrue(march.moneyResult is MoneyAggregateResult.Available)
        assertEquals(
            300.0,
            (march.moneyResult as MoneyAggregateResult.Available).aggregate.displayAmount,
            0.0001
        )
        assertEquals(300.0, march.totalAmount, 0.0001)
        assertEquals(3, march.transactionCount)
        assertFalse(march.isUnavailable)
        assertFalse(march.isPartial)
    }

    @Test
    fun `N-08-5 an UNAVAILABLE quality month is Unavailable and flagged isUnavailable`() = runTest {
        stubMonths(MonthMoneyAggregate("2026-03", unavailableAggregate()))

        val march = engine.getMonthlyTotals(2026).first().month("2026-03")

        assertTrue(
            "UNAVAILABLE quality must not be wrapped as Available, got ${march.moneyResult}",
            march.moneyResult is MoneyAggregateResult.Unavailable
        )
        assertTrue(march.isUnavailable)
        assertFalse("unavailable is not conflated with partial", march.isPartial)
        val unavailable = march.moneyResult as MoneyAggregateResult.Unavailable
        assertEquals(RateBasis.TRANSACTION_DATE, unavailable.requestedRateBasis)
        assertEquals("rates missing", unavailable.warningMessage)
        assertEquals("rates missing", march.warningMessage)
    }

    @Test
    fun `N-08-5 an UNAVAILABLE month that also reports isPartial stays unavailable not partial`() = runTest {
        stubMonths(MonthMoneyAggregate("2026-03", unavailableAggregate(isPartialFlag = true)))

        val march = engine.getMonthlyTotals(2026).first().month("2026-03")

        assertTrue(march.moneyResult is MoneyAggregateResult.Unavailable)
        assertTrue(march.isUnavailable)
        assertFalse(march.isPartial)
    }

    @Test
    fun `N-08-5 an UNAVAILABLE month without a warning still carries a non blank reason`() = runTest {
        stubMonths(MonthMoneyAggregate("2026-03", unavailableAggregate(warning = null)))

        val march = engine.getMonthlyTotals(2026).first().month("2026-03")

        val unavailable = march.moneyResult as MoneyAggregateResult.Unavailable
        assertTrue(unavailable.reason.isNotBlank())
        assertTrue(unavailable.warningMessage.isNotBlank())
    }

    @Test
    fun `N-08-5 a month without any aggregate has null money result and zero total`() = runTest {
        stubMonths(MonthMoneyAggregate("2026-03", MoneyAggregate.singleCurrency(300.0, eur, 3)))

        val february = engine.getMonthlyTotals(2026).first().month("2026-02")

        assertNull(february.moneyResult)
        assertEquals(0.0, february.totalAmount, 0.0)
        assertEquals(0, february.transactionCount)
        assertFalse(february.isUnavailable)
        assertFalse(february.isPartial)
    }

    @Test
    fun `N-08-5 a genuinely empty available month is Available zero and distinct from unavailable`() = runTest {
        stubMonths(
            MonthMoneyAggregate("2026-02", MoneyAggregate.empty(eur)),
            MonthMoneyAggregate("2026-03", unavailableAggregate())
        )

        val months = engine.getMonthlyTotals(2026).first()
        val february = months.month("2026-02")
        val march = months.month("2026-03")

        assertTrue(february.moneyResult is MoneyAggregateResult.Available)
        assertFalse(february.isUnavailable)
        assertEquals(0.0, february.totalAmount, 0.0)
        assertTrue(march.moneyResult is MoneyAggregateResult.Unavailable)
        assertNotNull(march.moneyResult)
    }

    @Test
    fun `N-08-5 a mixed year keeps unavailable and available months independent`() = runTest {
        stubMonths(
            MonthMoneyAggregate("2026-01", MoneyAggregate.singleCurrency(100.0, eur, 1)),
            MonthMoneyAggregate("2026-02", unavailableAggregate()),
            MonthMoneyAggregate("2026-03", MoneyAggregate.singleCurrency(200.0, eur, 2))
        )

        val months = engine.getMonthlyTotals(2026).first()

        assertEquals(12, months.size)
        assertTrue(months.month("2026-01").moneyResult is MoneyAggregateResult.Available)
        assertTrue(months.month("2026-02").moneyResult is MoneyAggregateResult.Unavailable)
        assertTrue(months.month("2026-03").moneyResult is MoneyAggregateResult.Available)
        assertEquals(1, months.count { it.isUnavailable })
    }
}
