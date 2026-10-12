package com.yourname.expensetracker.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class InsightsEngineNormalizedDailyMoneyTest {
    private val now = Instant.parse("2026-03-15T12:00:00Z").toEpochMilli()
    private val timeProvider = mockk<TimeProvider>().also { every { it.now() } returns now }
    private val engine = InsightsEngine(
        mockk(), mockk(), timeProvider, mockk(), mockk(), mockk(), mockk(), mockk(), mockk()
    )

    @Test
    fun `daily utility aggregates normalized amounts once without converting source values`() {
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR", includedExpenses = listOf(expense(1L, 40.0, "USD"), expense(2L, 30.0, "EUR"))
        )

        assertThat(engine.buildDailyTotals(input, 1).values).containsExactly(70.0)
    }

    @Test
    fun `valid empty daily input emits explicit zero buckets`() {
        val result = engine.buildDailyTotals(NormalizedAnalyticsInput(homeCurrency = "EUR"), 7)

        assertThat(result).hasSize(7)
        assertThat(result.values).containsExactly(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }

    @Test
    fun `unknown date exclusions do not become empty spending buckets`() {
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR", excludedExpenses = listOf(ExcludedExpense(1L, 10.0, "USD", ExclusionReason.CONVERSION_FAILED))
        )
        val failure = assertThrows(MoneyDisplayUnavailableException::class.java) { engine.buildDailyTotals(input, 7) }

        assertThat(failure.reasonCode).isEqualTo(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
    }

    @Test
    fun `daily currency mismatch duplicate ids and nonfinite amounts fail closed`() {
        val cases = listOf(
            NormalizedAnalyticsInput(homeCurrency = "EUR", includedExpenses = listOf(expense(1L, 10.0, "USD").copy(normalizedCurrency = "USD"))),
            NormalizedAnalyticsInput(homeCurrency = "EUR", includedExpenses = listOf(expense(1L, 10.0, "EUR"), expense(1L, 20.0, "EUR"))),
            NormalizedAnalyticsInput(homeCurrency = "EUR", includedExpenses = listOf(expense(1L, Double.NaN, "EUR"))),
            NormalizedAnalyticsInput(homeCurrency = "UNKNOWN")
        )
        for (input in cases) {
            assertThrows(MoneyDisplayUnavailableException::class.java) { engine.buildDailyTotals(input, 1) }
        }
    }

    private fun expense(id: Long, amount: Double, originalCurrency: String) = NormalizedExpense(
        id = id, originalAmount = 50.0, originalEffectiveAmount = 50.0, originalCurrency = originalCurrency,
        normalizedAmount = amount, normalizedCurrency = "EUR", date = now, merchant = "merchant", merchantKey = null,
        categoryId = null, categoryNameSnapshot = null, transactionType = "PURCHASE", isNotMine = false,
        isSharedExpense = false, ownershipMode = null, source = "TEST"
    )
}
