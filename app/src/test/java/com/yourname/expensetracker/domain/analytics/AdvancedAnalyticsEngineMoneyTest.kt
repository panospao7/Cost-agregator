package com.yourname.expensetracker.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertThrows
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import java.time.LocalDate
import java.time.ZoneId

class AdvancedAnalyticsEngineMoneyTest {

    private val timeProvider = mockk<TimeProvider>().also {
        every { it.now() } returns date("2026-04-01")
    }
    private val engine = AdvancedAnalyticsEngine(
        mockk<ExpenseRepository>(), mockk<CategoryRepository>(), mockk<BudgetRepository>(),
        TestCurrencySettingsRepository(), testAnalyticsCurrencyNormalizer(), timeProvider,
        Dispatchers.Unconfined, Dispatchers.Unconfined
    )
    private val categories = listOf(Category(1L, "Food", "", "#123456"))

    @Test
    fun `category totals and velocity retain provenance without converting twice`() = runTest {
        val current = input(listOf(
            expense(1L, 10.0, "USD", "2026-03-02"),
            expense(2L, 30.0, "GBP", "2026-03-03")
        ))
        val previous = input(
            listOf(expense(3L, 20.0, "USD", "2026-02-02")),
            start = "2026-02-01", end = "2026-03-01"
        )

        val result = engine.getCategoryAnalytics(current, previous, categories, emptyList()).first.single()
        val total = (result.totalMoneyResult as MoneyAggregateResult.Available).aggregate
        val velocity = (result.velocityMoneyResult as MoneyAggregateResult.Available).aggregate

        assertThat(total.displayAmount).isEqualTo(40.0)
        assertThat(total.displayCurrency).isEqualTo(CurrencyCode.EUR)
        assertThat(total.metadata.includedTransactionCount).isEqualTo(2)
        assertThat(total.metadata.sourceCurrencyCodes).containsExactly(CurrencyCode.USD, CurrencyCode.GBP)
        assertThat(total.metadata.rateValidDates).containsExactly(date("2026-03-02"), date("2026-03-03"))
        assertThat(velocity.displayAmount).isEqualTo(20.0)
        assertThat(velocity.metadata).isEqualTo(total.metadata)
        assertThat(result.changePercent).isEqualTo(100f)
    }

    @Test
    fun `category change percent ignores current rows after the comparison cutoff but total keeps them`() = runTest {
        val current = input(listOf(
            expense(1L, 10.0, "EUR", "2026-03-02"),
            expense(2L, 30.0, "EUR", "2026-03-20")
        ))
        val previous = input(
            listOf(expense(3L, 20.0, "EUR", "2026-02-02")),
            start = "2026-02-01", end = "2026-03-01"
        )

        val full = engine.getCategoryAnalytics(current, previous, categories, emptyList()).first.single()
        val cut = engine.getCategoryAnalytics(
            current, previous, categories, emptyList(),
            comparisonCutoffMillisInclusive = date("2026-03-10")
        ).first.single()

        assertThat(full.changePercent).isEqualTo(100f)
        assertThat(cut.changePercent).isEqualTo(-50f)
        assertThat(cut.totalSpent).isEqualTo(40.0)
        assertThat(cut.transactionCount).isEqualTo(2)
    }

    @Test
    fun `category exclusions cannot be assigned to an arbitrary category`() = runTest {
        val current = input(
            listOf(expense(1L, 10.0, "USD", "2026-03-02")),
            listOf(excluded(2L, "2026-03-03"))
        )
        val previous = input(
            listOf(expense(3L, 20.0, "EUR", "2026-02-02")),
            start = "2026-02-01", end = "2026-03-01"
        )

        val failure = assertThrows(MoneyDisplayUnavailableException::class.java) {
            kotlinx.coroutines.runBlocking { engine.getCategoryAnalytics(current, previous, categories, emptyList()) }
        }
        assertThat(failure.reasonCode).isEqualTo(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
    }

    @Test
    fun `merchant totals and weekdays use the normalized money boundary`() = runTest {
        val current = input(listOf(
            expense(1L, 10.0, "USD", "2026-03-02"),
            expense(2L, 30.0, "GBP", "2026-03-03")
        ))

        val merchant = engine.getMerchantAnalytics(current, input(emptyList())).first.single()
        val total = (merchant.totalMoneyResult as MoneyAggregateResult.Available).aggregate
        val monday = (merchant.spendingByDayMoneyResults.getValue(0) as MoneyAggregateResult.Available).aggregate

        assertThat(total.displayAmount).isEqualTo(40.0)
        assertThat(total.metadata.includedTransactionCount).isEqualTo(2)
        assertThat(monday.displayAmount).isEqualTo(10.0)
        assertThat(monday.metadata.sourceCurrencyCodes).containsExactly(CurrencyCode.USD)
        assertThat(merchant.spendingByDayOfWeek[1]).isEqualTo(30.0)
    }

    @Test
    fun `weekday quality is slice local and incomplete inputs do not emit confident patterns`() = runTest {
        val current = input(
            listOf(expense(1L, 10.0, "EUR", "2026-03-02"), expense(2L, 30.0, "USD", "2026-03-03")),
            listOf(excluded(3L, "2026-03-02"))
        )

        val result = engine.getSpendingPatterns(current).first
        val monday = (result.dayOfWeekMoneyResults.getValue(0) as MoneyAggregateResult.Available).aggregate
        val tuesday = (result.dayOfWeekMoneyResults.getValue(1) as MoneyAggregateResult.Available).aggregate

        assertThat(monday.isPartial).isTrue()
        assertThat(monday.metadata.excludedTransactionCount).isEqualTo(1)
        assertThat(tuesday.isPartial).isFalse()
        assertThat(tuesday.metadata.excludedTransactionCount).isEqualTo(0)
        assertThat(result.detectedPatterns).isEmpty()
        assertThat(result.weekendVsWeekday.weekendMoneyResult).isInstanceOf(MoneyAggregateResult.Available::class.java)
    }

    @Test
    fun `statistics and histogram retain amounts counts and currency provenance`() = runTest {
        val current = input(listOf(
            expense(1L, 10.0, "USD", "2026-03-02"),
            expense(2L, 20.0, "GBP", "2026-03-02"),
            expense(3L, 30.0, "EUR", "2026-03-03")
        ))

        val result = engine.getStatisticalInsights(current).first
        val total = (result.totalMoneyResult as MoneyAggregateResult.Available).aggregate
        val bins = result.histogramBins.map { (it.moneyResult as MoneyAggregateResult.Available).aggregate }

        assertThat(total.displayAmount).isEqualTo(60.0)
        assertThat(result.meanTransaction).isEqualTo(20.0)
        assertThat(result.medianTransaction).isEqualTo(20.0)
        assertThat(result.maxDailySpend).isEqualTo(30.0)
        assertThat(result.daysWithSpending).isEqualTo(2)
        assertThat(result.histogramBins).hasSize(10)
        assertThat(result.histogramBins.sumOf { it.count }).isEqualTo(3)
        assertThat(bins.sumOf { it.displayAmount }).isEqualTo(60.0)
        assertThat(bins.sumOf { it.metadata.includedTransactionCount }).isEqualTo(3)
        assertThat(bins.all { it.displayCurrency == CurrencyCode.EUR }).isTrue()
        assertThat(total.metadata.sourceCurrencyCodes).containsExactly(CurrencyCode.USD, CurrencyCode.GBP, CurrencyCode.EUR)
    }

    @Test
    fun `equal values form one typed histogram bin`() = runTest {
        val current = input(listOf(
            expense(1L, 10.0, "USD", "2026-03-02"),
            expense(2L, 10.0, "GBP", "2026-03-03")
        ))

        val bin = engine.getStatisticalInsights(current).first.histogramBins.single()

        assertThat(bin.count).isEqualTo(2)
        assertThat(bin.percentage).isEqualTo(100f)
        assertThat((bin.moneyResult as MoneyAggregateResult.Available).aggregate.displayAmount).isEqualTo(20.0)
    }

    @Test
    fun `unavailable totals are distinct from a valid empty input`() = runTest {
        val empty = engine.getStatisticalInsights(input(emptyList())).first
        val cases = listOf(
            input(emptyList(), listOf(excluded(1L, null))) to MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE,
            input(listOf(expense(2L, 10.0, "USD", "2026-03-02").copy(normalizedCurrency = "USD"))) to MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY,
            input(listOf(expense(3L, Double.NaN, "EUR", "2026-03-02"))) to MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT
        )
        assertThat(empty.totalMoneyResult).isInstanceOf(MoneyAggregateResult.Available::class.java)
        for ((source, reason) in cases) {
            val failure = assertThrows(MoneyDisplayUnavailableException::class.java) {
                kotlinx.coroutines.runBlocking { engine.getStatisticalInsights(source) }
            }
            assertThat(failure.reasonCode).isEqualTo(reason)
        }
    }

    @Test
    fun `unknown date exclusions stay unavailable and out of period rows are not aggregated`() = runTest {
        val current = input(
            listOf(expense(1L, 10.0, "EUR", "2026-03-02"), expense(2L, 90.0, "USD", "2026-04-02")),
            listOf(excluded(3L, "2026-04-02"))
        )
        val category = engine.getCategoryAnalytics(current, null, categories, emptyList()).first.single()
        val failure = assertThrows(MoneyDisplayUnavailableException::class.java) {
            kotlinx.coroutines.runBlocking { engine.getSpendingPatterns(current.copy(excludedExpenses = listOf(excluded(4L, null)))) }
        }
        assertThat((category.totalMoneyResult as MoneyAggregateResult.Available).aggregate.displayAmount).isEqualTo(10.0)
        assertThat(category.transactionCount).isEqualTo(1)
        assertThat(failure.reasonCode).isEqualTo(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
    }

    @Test
    fun `historical merchant trends do not compare incompatible normalized currencies`() = runTest {
        val current = input(listOf(expense(1L, 10.0, "EUR", "2026-03-02")))
        val historical = input(listOf(
            expense(2L, 10.0, "USD", "2026-02-02").copy(normalizedCurrency = "USD"),
            expense(3L, 30.0, "USD", "2026-02-03").copy(normalizedCurrency = "USD")
        ), start = "2026-02-01", end = "2026-03-01").copy(homeCurrency = "USD")

        val result = engine.getMerchantAnalytics(current, historical).first.single()

        assertThat(result.priceTrend).isEqualTo(MerchantPriceTrend.INSUFFICIENT_DATA)
        assertThat(result.priceChangePercent).isNull()
        assertThat(result.totalMoneyResult).isInstanceOf(MoneyAggregateResult.Available::class.java)
    }

    private fun input(
        expenses: List<NormalizedExpense>,
        excluded: List<ExcludedExpense> = emptyList(),
        start: String = "2026-03-01",
        end: String = "2026-04-01"
    ) = NormalizedAnalyticsInput(
        period = PeriodRange(PeriodKind.CUSTOM, date(start), date(end)),
        homeCurrency = "EUR", includedExpenses = expenses, excludedExpenses = excluded
    )

    private fun expense(id: Long, amount: Double, sourceCurrency: String, day: String) = NormalizedExpense(
        id = id, originalAmount = amount * 2, originalEffectiveAmount = amount * 2,
        originalCurrency = sourceCurrency, normalizedAmount = amount, normalizedCurrency = "EUR",
        date = date(day), merchant = "Merchant", merchantKey = "merchant", categoryId = 1L,
        categoryNameSnapshot = "Food", transactionType = "PURCHASE", isNotMine = false,
        isSharedExpense = false, ownershipMode = null, source = "TEST", rateUsed = 0.5,
        rateValidDate = date(day)
    )

    private fun excluded(id: Long, day: String?) = ExcludedExpense(
        id, 10.0, "USD", ExclusionReason.CONVERSION_FAILED, day?.let(::date),
        AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE
    )

    private fun date(day: String): Long = LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
