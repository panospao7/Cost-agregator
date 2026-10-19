package com.yourname.expensetracker.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.assertMoneyExact
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.ClassRule
import org.junit.Test
import java.time.Instant

class InsightsEngineNormalizedDailyMoneyTest {

    companion object {
        // Zone pinned to UTC for the whole class: buildDailyTotals buckets by system-default days.
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()
    }

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

        val values = engine.buildDailyTotals(input, 1).values.toList()

        assertEquals("exactly one daily bucket expected", 1, values.size)
        assertMoneyExact(70.0, values.single(), "40.0 + 30.0 normalized amounts, summed once")
    }

    @Test
    fun `valid empty daily input emits explicit zero buckets`() {
        // unavailable-not-zero counterpart: a VALID empty input is an available zero per day.
        val result = engine.buildDailyTotals(NormalizedAnalyticsInput(homeCurrency = "EUR"), 7)

        assertThat(result).hasSize(7)
        result.entries.forEach { (day, amount) -> assertMoneyExact(0.0, amount, "bucket $day") }
    }

    @Test
    fun `unknown date exclusions do not become empty spending buckets`() {
        // unavailable-not-zero: an exclusion with unknown date must make the result unavailable,
        // never silently produce zero buckets.
        val input = NormalizedAnalyticsInput(
            homeCurrency = "EUR", excludedExpenses = listOf(ExcludedExpense(1L, 10.0, "USD", ExclusionReason.CONVERSION_FAILED))
        )
        val failure = assertThrows(MoneyDisplayUnavailableException::class.java) { engine.buildDailyTotals(input, 7) }

        assertThat(failure.reasonCode).isEqualTo(MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE)
    }

    @Test
    fun `daily currency mismatch duplicate ids and nonfinite amounts fail closed`() {
        val cases = listOf(
            "normalized currency differs from home currency" to
                NormalizedAnalyticsInput(homeCurrency = "EUR", includedExpenses = listOf(expense(1L, 10.0, "USD").copy(normalizedCurrency = "USD"))),
            "duplicate expense ids" to
                NormalizedAnalyticsInput(homeCurrency = "EUR", includedExpenses = listOf(expense(1L, 10.0, "EUR"), expense(1L, 20.0, "EUR"))),
            "non-finite (NaN) amount" to
                NormalizedAnalyticsInput(homeCurrency = "EUR", includedExpenses = listOf(expense(1L, Double.NaN, "EUR"))),
            "unknown home currency" to
                NormalizedAnalyticsInput(homeCurrency = "UNKNOWN")
        )
        assertEquals("row table must keep all four fail-closed cases", 4, cases.size)
        for ((name, input) in cases) {
            assertThrows("row '$name' must fail closed", MoneyDisplayUnavailableException::class.java) {
                engine.buildDailyTotals(input, 1)
            }
        }
    }

    private fun expense(id: Long, amount: Double, originalCurrency: String) = NormalizedExpense(
        id = id, originalAmount = 50.0, originalEffectiveAmount = 50.0, originalCurrency = originalCurrency,
        normalizedAmount = amount, normalizedCurrency = "EUR", date = now, merchant = "merchant", merchantKey = null,
        categoryId = null, categoryNameSnapshot = null, transactionType = "PURCHASE", isNotMine = false,
        isSharedExpense = false, ownershipMode = null, source = "TEST"
    )
}
