package com.yourname.expensetracker.integration

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ExchangeRateDao
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertTrue
import java.time.LocalDate
import java.time.ZoneId

class MultiCurrencyAnalyticsTest {

    @Test
    fun `multi_currency_analytics_contract`() = runTest {
        val expenseDao = mockk<ExpenseDao>(relaxed = true)
        val exchangeRateDao = mockk<ExchangeRateDao>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns ms(2026, 4, 15)

        val converter = CurrencyConverter(exchangeRateDao)
        val repository = MultiCurrencyRepository(expenseDao, converter, timeProvider)

        coEvery { exchangeRateDao.getRate("USD", "EUR") } returns ExchangeRate(fromCurrency = "USD", toCurrency = "EUR", rate = 0.8)
        coEvery { exchangeRateDao.getRate("EUR", "EUR") } returns ExchangeRate(fromCurrency = "EUR", toCurrency = "EUR", rate = 1.0)
        coEvery { exchangeRateDao.getRate("JPY", "EUR") } returns null // fallback path

        val start = ms(2026, 4, 1)
        val end = ms(2026, 5, 1)
        coEvery { expenseDao.getExpensesBetween(start, end) } returns listOf(
            expense(1, 100.0, "EUR", TransactionType.PURCHASE, ms(2026, 4, 2)),
            expense(2, 50.0, "USD", TransactionType.PURCHASE, ms(2026, 4, 3)),
            expense(3, 1000.0, "JPY", TransactionType.PURCHASE, ms(2026, 4, 4))
        )

        val totalInEur = repository.getTotalExpensesInHomeCurrency(start, end, homeCurrency = "EUR")

        // 100 EUR + (50 USD * 0.8) + (1000 JPY fallback as-is when no rate)
        assertTrue(totalInEur is Result.Success)
        assertApproxEquals(1140.0, (totalInEur as Result.Success).data, 0.0001)
    }

    private fun expense(id: Long, amount: Double, currency: String, type: TransactionType, date: Long) = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = "M$id",
        transactionType = type,
        date = date
    )

    private fun ms(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
