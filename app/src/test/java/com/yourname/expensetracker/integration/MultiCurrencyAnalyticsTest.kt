package com.yourname.expensetracker.integration

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.model.Result
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.Assert.assertTrue
import java.time.LocalDate
import java.time.ZoneId

class MultiCurrencyAnalyticsTest : AnalyticsEngineTestBase() {

    @Test
    fun `multi_currency_analytics_contract`() = runTest {
        val exchangeRateStore = mockk<ExchangeRateStore>(relaxed = true)
        every { timeProvider.now() } returns ms(2026, 4, 15)

        val converter = CurrencyConverter(exchangeRateStore)
        val repository = MultiCurrencyRepository(expenseDao, converter, timeProvider)

        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.8,
            lastUpdated = ms(2026, 4, 1),
            source = "test"
        )
        coEvery { exchangeRateStore.getRate("EUR", "EUR") } returns DomainExchangeRate(
            fromCurrency = "EUR",
            toCurrency = "EUR",
            rate = 1.0,
            lastUpdated = ms(2026, 4, 1),
            source = "test"
        )
        coEvery { exchangeRateStore.getRate("JPY", "EUR") } returns null

        val start = ms(2026, 4, 1)
        val end = ms(2026, 5, 1)
        coEvery { expenseDao.getExpensesBetween(start, end) } returns listOf(
            expense(1, 100.0, "EUR", TransactionType.PURCHASE, ms(2026, 4, 2)),
            expense(2, 50.0, "USD", TransactionType.PURCHASE, ms(2026, 4, 3)),
            expense(3, 1000.0, "JPY", TransactionType.PURCHASE, ms(2026, 4, 4))
        )

        val totalInEur = repository.getTotalExpensesInHomeCurrency(start, end, homeCurrency = "EUR")

        // Missing JPY->EUR rate should fail instead of mixing currencies in total.
        assertTrue(totalInEur is Result.Error)
        val message = (totalInEur as Result.Error).message.orEmpty()
        assertTrue(message.contains("Missing exchange rates"))
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
