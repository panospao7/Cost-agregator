package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.RateBasis
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * N-08-5: [TotalsAggregationEngine.getYearlyTotals] must keep PAST years whose total is
 * unavailable (they carry a 0.0 placeholder that is not "no spend"), while still dropping
 * genuinely empty past years.
 */
class TotalsAggregationEngineUnavailableYearsTest {

    private val eur = CurrencyCode("EUR")
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>()
    private val multiCurrencyRepo = mockk<MultiCurrencyRepository>()
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val engine = TotalsAggregationEngine(
        expenseRepository, timeProvider, multiCurrencyRepo, categoryRepository, Dispatchers.Unconfined
    )

    private val apr15of2026 = LocalDate.of(2026, 4, 15).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun stubYears(perYear: Map<Int, MoneyAggregateResult>) {
        every { timeProvider.now() } returns apr15of2026
        every { expenseRepository.getTotalSpent() } returns flowOf(null)
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns emptyList()
        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } answers {
            val year = Instant.ofEpochMilli(firstArg<Long>()).atZone(ZoneId.systemDefault()).year
            perYear[year] ?: MoneyAggregateResult.Available(MoneyAggregate.empty(eur))
        }
    }

    private fun unavailable() = MoneyAggregateResult.Unavailable(
        reason = "DISPLAY_CONVERSION_UNAVAILABLE",
        requestedRateBasis = RateBasis.TRANSACTION_DATE
    )

    @Test
    fun `N-08-5 control past years with data are kept and empty past years are dropped`() = runTest {
        stubYears(
            mapOf(
                2023 to MoneyAggregateResult.Available(MoneyAggregate.singleCurrency(700.0, eur, 7)),
                2025 to MoneyAggregateResult.Available(MoneyAggregate.singleCurrency(900.0, eur, 9))
            )
        )

        val years = engine.getYearlyTotals().first()

        assertEquals(listOf("2023", "2025", "2026"), years.map { it.periodKey })
        assertTrue(years.none { it.isUnavailable })
    }

    @Test
    fun `N-08-5 past years whose total is unavailable stay listed as unavailable not dropped`() = runTest {
        stubYears(
            mapOf(
                2023 to unavailable(),
                2024 to MoneyAggregateResult.Available(MoneyAggregate.empty(eur)),
                2025 to MoneyAggregateResult.Available(MoneyAggregate.singleCurrency(900.0, eur, 9))
            )
        )

        val years = engine.getYearlyTotals().first()

        // 2024 is genuinely empty and past: dropped. 2023 is unavailable: kept. 2026 is the current year.
        assertEquals(listOf("2023", "2025", "2026"), years.map { it.periodKey })
        val unavailableYear = years.single { it.periodKey == "2023" }
        assertTrue(unavailableYear.isUnavailable)
        assertFalse("unavailable must not be conflated with partial", unavailableYear.isPartial)
        assertEquals("placeholder amount, never a real zero spend", 0.0, unavailableYear.totalAmount, 0.0)
        assertTrue(unavailableYear.moneyResult is MoneyAggregateResult.Unavailable)
        assertEquals(900.0, years.single { it.periodKey == "2025" }.totalAmount, 0.001)
    }

    @Test
    fun `N-08-5 every past year unavailable keeps all five years listed`() = runTest {
        stubYears(
            (2022..2026).associateWith { unavailable() }
        )

        val years = engine.getYearlyTotals().first()

        assertEquals(listOf("2022", "2023", "2024", "2025", "2026"), years.map { it.periodKey })
        assertTrue(years.all { it.isUnavailable })
    }
}
