package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdvancedAnalyticsEngineNormalizedTest {

    private lateinit var engine: AdvancedAnalyticsEngine
    private val expenseRepository: ExpenseRepository = mockk()
    private val categoryRepository: CategoryRepository = mockk()
    private val budgetRepository: BudgetRepository = mockk()
    private val timeProvider: TimeProvider = mockk()
    private val currencySettingsRepository = TestCurrencySettingsRepository()
    private val analyticsCurrencyNormalizer = testAnalyticsCurrencyNormalizer()

    private val fixedTimestamp = 1704067200000L // Mon Jan 1, 2024 00:00:00 UTC
    private val dayMs = 86_400_000L

    @Test
    fun `getSpendingPatterns with NormalizedAnalyticsInput detects weekend warrior pattern`() = runTest {
        every { timeProvider.now() } returns fixedTimestamp

        engine = AdvancedAnalyticsEngine(
            expenseRepository,
            categoryRepository,
            budgetRepository,
            currencySettingsRepository,
            analyticsCurrencyNormalizer,
            timeProvider,
            Dispatchers.Unconfined,
            Dispatchers.Unconfined
        )

        // Mon Jan 1  = index 0, Tue Jan 2 = index 1, Wed Jan 3 = index 2,
        // Thu Jan 4 = index 3, Fri Jan 5 = index 4, Sat Jan 6 = index 5
        val weekdayExpenses = listOf(
            normExpense(1L, amount = 10.0, date = fixedTimestamp),                              // Mon
            normExpense(2L, amount = 10.0, date = fixedTimestamp + 1 * dayMs),                  // Tue
            normExpense(3L, amount = 10.0, date = fixedTimestamp + 2 * dayMs),                  // Wed
            normExpense(4L, amount = 10.0, date = fixedTimestamp + 3 * dayMs),                  // Thu
            normExpense(5L, amount = 10.0, date = fixedTimestamp + 4 * dayMs),                  // Fri
        )
        val weekendExpenses = listOf(
            normExpense(6L, amount = 100.0, date = fixedTimestamp + 5 * dayMs),                 // Sat
        )

        val warnings = listOf(
            AnalyticsConversionWarning(
                type = AnalyticsConversionWarningType.STALE_EXCHANGE_RATE,
                message = "Rate is stale",
                affectedTransactionCount = 1
            )
        )

        val input = NormalizedAnalyticsInput(
            period = null,
            homeCurrency = "EUR",
            includedExpenses = weekdayExpenses + weekendExpenses,
            dataQuality = AnalyticsDataQuality(warnings = warnings)
        )

        val (analysis, resultWarnings) = engine.getSpendingPatterns(input)

        assertTrue(
            analysis.detectedPatterns.any { it.type == SpendingPatternType.WEEKEND_WARRIOR },
            "Expected WEEKEND_WARRIOR pattern to be detected"
        )

        assertEquals(warnings, resultWarnings, "Warnings from input.dataQuality.warnings should be returned")
    }

    @Test
    fun `getStatisticalInsights with NormalizedAnalyticsInput computes correct statistics`() = runTest {
        every { timeProvider.now() } returns fixedTimestamp

        engine = AdvancedAnalyticsEngine(
            expenseRepository,
            categoryRepository,
            budgetRepository,
            currencySettingsRepository,
            analyticsCurrencyNormalizer,
            timeProvider,
            Dispatchers.Unconfined,
            Dispatchers.Unconfined
        )

        // One expense per day so that each day's total equals that expense's amount.
        val amounts = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        val expenses = amounts.mapIndexed { index, amount ->
            normExpense(index.toLong() + 1, amount = amount, date = fixedTimestamp + index * dayMs)
        }

        val warnings = listOf(
            AnalyticsConversionWarning(
                type = AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
                message = "Missing rate for USD",
                affectedTransactionCount = 2
            )
        )

        val input = NormalizedAnalyticsInput(
            period = null,
            homeCurrency = "EUR",
            includedExpenses = expenses,
            dataQuality = AnalyticsDataQuality(warnings = warnings)
        )

        val (stats, resultWarnings) = engine.getStatisticalInsights(input)

        assertEquals(30.0, stats.meanTransaction, 0.001, "meanTransaction should be 30.0")
        assertEquals(50.0, stats.maxDailySpend, 0.001, "maxDailySpend should be 50.0 (one expense per day)")
        assertEquals(warnings, resultWarnings, "Warnings from input.dataQuality.warnings should be returned")
    }

    @Test
    fun `getSpendingPatterns with empty NormalizedAnalyticsInput returns empty safe result`() = runTest {
        every { timeProvider.now() } returns fixedTimestamp

        engine = AdvancedAnalyticsEngine(
            expenseRepository,
            categoryRepository,
            budgetRepository,
            currencySettingsRepository,
            analyticsCurrencyNormalizer,
            timeProvider,
            Dispatchers.Unconfined,
            Dispatchers.Unconfined
        )

        val input = NormalizedAnalyticsInput(
            period = null,
            homeCurrency = "EUR",
            includedExpenses = emptyList(),
            dataQuality = AnalyticsDataQuality()
        )

        val (analysis, _) = engine.getSpendingPatterns(input)

        assertTrue(analysis.dayOfWeekStats.isEmpty(), "dayOfWeekStats should be empty for empty input")
        assertTrue(analysis.detectedPatterns.isEmpty(), "detectedPatterns should be empty for empty input")
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun normExpense(
        id: Long,
        amount: Double,
        date: Long,
        merchant: String = "TestMerchant",
        categoryId: Long? = null,
        transactionType: String = "PURCHASE"
    ): NormalizedExpense = NormalizedExpense(
        id = id,
        originalAmount = amount,
        originalEffectiveAmount = amount,
        originalCurrency = "EUR",
        normalizedAmount = amount,
        normalizedCurrency = "EUR",
        date = date,
        merchant = merchant,
        merchantKey = null,
        categoryId = categoryId,
        categoryNameSnapshot = null,
        transactionType = transactionType,
        isNotMine = false,
        isSharedExpense = false,
        ownershipMode = null,
        source = null
    )
}
