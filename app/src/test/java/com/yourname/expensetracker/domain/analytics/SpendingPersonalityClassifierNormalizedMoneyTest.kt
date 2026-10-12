package com.yourname.expensetracker.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.mockk
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SpendingPersonalityClassifierNormalizedMoneyTest {

    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val classifier = SpendingPersonalityClassifier(
        expenseRepository = expenseRepository,
        budgetRepository = budgetRepository,
        insightsEngine = mockk(relaxed = true),
        spendingPaceCalculator = mockk(relaxed = true),
        anomalyDetector = mockk(relaxed = true),
        totalsAggregationEngine = mockk(relaxed = true),
        timeProvider = timeProvider
    )

    @Test
    fun `normalized classification uses normalized amounts for mixed source currencies`() = runTest {
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            includedExpenses = (1L..10L).map { id ->
                normalizedExpense(
                    id = id,
                    originalAmount = if (id % 2L == 0L) 100.0 else 80.0,
                    originalCurrency = if (id % 2L == 0L) "USD" else "GBP",
                    normalizedAmount = 40.0 + id
                )
            }
        )

        val result = classifier.classify(input)

        assertThat(result.featureScores["avgTransactionSize"]).isWithin(0.000001).of(0.2275)
        assertThat(result.featureScores["transactionsPerMonth"]).isWithin(0.000001).of(10.0 / 3.0)
        assertThat(result.confidence).isWithin(0.000001).of(0.8)
        coVerify(exactly = 0) { expenseRepository.getExpenseSnapshotsBetween(any(), any()) }
        coVerify(exactly = 0) { budgetRepository.getActiveBudgetSnapshots() }
    }

    @Test
    fun `normalized classification lowers confidence for partial input without restoring excluded rows`() = runTest {
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            includedExpenses = (1L..10L).map { id -> normalizedExpense(id, 20.0, "USD", 20.0) },
            excludedExpenses = listOf(
                ExcludedExpense(
                    id = 100L,
                    originalAmount = 20.0,
                    originalCurrency = "JPY",
                    reason = ExclusionReason.CONVERSION_FAILED,
                    date = date("2026-03-02"),
                    warningType = AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE
                )
            ),
            dataQuality = AnalyticsDataQuality(
                isPartial = true,
                excludedCount = 1,
                missingRateCount = 1,
                confidencePenalty = 0.1,
                confidenceMultiplier = 0.8
            )
        )

        val result = classifier.classify(input)

        assertThat(result.confidence).isWithin(0.000001).of(0.54)
        assertThat(result.featureScores["transactionsPerMonth"]).isWithin(0.000001).of(10.0 / 3.0)
        assertThat(result.featureScores["avgTransactionSize"]).isWithin(0.000001).of(0.1)
    }

    @Test
    fun `normalized classifier returns insufficient data without using raw repository path`() = runTest {
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            includedExpenses = listOf(
                normalizedExpense(1L, 100.0, "USD", 10.0),
                normalizedExpense(2L, 100.0, "GBP", 10.0)
            )
        )

        val result = classifier.classify(input)

        assertThat(result.featureScores).isEmpty()
        assertThat(result.confidence).isEqualTo(0.0)
        coVerify(exactly = 0) { expenseRepository.getExpenseSnapshotsBetween(any(), any()) }
    }

    @Test
    fun `normalized classification scopes features to the requested period`() = runTest {
        val period = PeriodRange(
            kind = PeriodKind.CUSTOM,
            startInclusiveMillis = date("2026-03-01"),
            endExclusiveMillis = date("2026-04-01")
        )
        val input = NormalizedAnalyticsInput(
            period = period,
            homeCurrency = "EUR",
            includedExpenses = (1L..10L).map { id ->
                normalizedExpense(id, 20.0, "USD", 40.0 + id)
            } + normalizedExpense(11L, 2_000.0, "USD", 1_000.0).copy(
                date = date("2026-04-02")
            )
        )

        val result = classifier.classify(input)

        assertThat(result.featureScores["transactionsPerMonth"])
            .isWithin(0.000001)
            .of(10.0 / 3.0)
        assertThat(result.featureScores["avgTransactionSize"])
            .isWithin(0.000001)
            .of(0.2275)
    }

    @Test
    fun `normalized classification fails closed for invalid source currency`() = runTest {
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR",
            includedExpenses = (1L..10L).map { id ->
                normalizedExpense(id, 20.0, "USD", 20.0).copy(
                    originalCurrency = if (id == 1L) "INVALID" else "USD"
                )
            }
        )

        val result = classifier.classify(input)
        val moneyResult = result.moneyResult

        assertThat(moneyResult).isInstanceOf(MoneyAggregateResult.Unavailable::class.java)
        assertThat((moneyResult as MoneyAggregateResult.Unavailable).reason)
            .isEqualTo(MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name)
        assertThat(result.featureScores).isEmpty()
        assertThat(result.confidence).isEqualTo(0.0)
    }

    private fun normalizedExpense(
        id: Long,
        originalAmount: Double,
        originalCurrency: String,
        normalizedAmount: Double
    ) = NormalizedExpense(
        id = id,
        originalAmount = originalAmount,
        originalEffectiveAmount = originalAmount,
        originalCurrency = originalCurrency,
        normalizedAmount = normalizedAmount,
        normalizedCurrency = "EUR",
        date = date("2026-03-${(id % 7L + 2L).toString().padStart(2, '0')}"),
        merchant = "Merchant $id",
        merchantKey = "merchant-$id",
        categoryId = id % 3L,
        categoryNameSnapshot = "Category ${id % 3L}",
        transactionType = "PURCHASE",
        isNotMine = false,
        isSharedExpense = false,
        ownershipMode = null,
        source = "TEST"
    )

    private fun date(value: String): Long =
        LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
