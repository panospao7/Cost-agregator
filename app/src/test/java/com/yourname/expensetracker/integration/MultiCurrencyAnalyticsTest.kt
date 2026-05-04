package com.yourname.expensetracker.integration

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.TestCurrencySettingsRepository
import com.yourname.expensetracker.data.database.dao.CurrencyTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MerchantCurrencyTotal
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.currency.MultiConversionAggregate
import com.yourname.expensetracker.domain.model.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import java.time.LocalDate
import java.time.ZoneId

class MultiCurrencyAnalyticsTest : AnalyticsEngineTestBase() {

    @Test
    fun `multi_currency_analytics_contract`() = runTest {
        val exchangeRateStore = mockk<ExchangeRateStore>(relaxed = true)
        every { timeProvider.now() } returns ms(2026, 4, 15)

        val converter = CurrencyConverter(exchangeRateStore, timeProvider = mockk())
        val repository = MultiCurrencyRepository(expenseDao, converter, timeProvider, TestCurrencySettingsRepository())

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

        // A.9 Batch 5: getTotalExpensesInHomeCurrency now uses aggregate DAO path.
        coEvery { expenseDao.getAllSpentBetweenByCurrency(start, end) } returns listOf(
            CurrencyTotal("EUR", 100.0, 1),
            CurrencyTotal("USD", 50.0, 1),
            CurrencyTotal("JPY", 1000.0, 1)
        )

        val totalInEur = repository.getTotalExpensesInHomeCurrency(start, end, homeCurrency = "EUR")

        // Missing JPY->EUR rate should fail instead of mixing currencies in total.
        assertTrue(totalInEur is Result.Error)
        val message = (totalInEur as Result.Error).message.orEmpty()
        assertTrue(message.contains("Missing exchange rates"))

        // Verify aggregate helper was called, NOT the uncapped row scan.
        coVerify(exactly = 1) { expenseDao.getAllSpentBetweenByCurrency(start, end) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    /**
     * A.9 Batch 5: getExpensesByCurrency uses aggregate DAO path and returns correct
     * per-currency totals without row scanning.
     */
    @Test
    fun `getExpensesByCurrency uses aggregate path`() = runTest {
        every { timeProvider.now() } returns ms(2026, 4, 15)

        val repository = MultiCurrencyRepository(expenseDao, mockk(relaxed = true), timeProvider, TestCurrencySettingsRepository())

        val start = ms(2026, 4, 1)
        val end = ms(2026, 5, 1)

        coEvery { expenseDao.getAllSpentBetweenByCurrency(start, end) } returns listOf(
            CurrencyTotal("EUR", 500.0, 5),
            CurrencyTotal("USD", 200.0, 3)
        )

        val result = repository.getExpensesByCurrency(start, end)

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals(500.0, data["EUR"]!!, 0.0001)
        assertEquals(200.0, data["USD"]!!, 0.0001)

        coVerify(exactly = 1) { expenseDao.getAllSpentBetweenByCurrency(start, end) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    /**
     * A.9 Batch 5 regression: aggregate path handles >2000 expenses correctly
     * because SQL SUM runs on the full dataset regardless of row count.
     */
    @Test
    fun `aggregate path handles over 2000 expenses without truncation`() = runTest {
        every { timeProvider.now() } returns ms(2026, 4, 15)

        val repository = MultiCurrencyRepository(expenseDao, mockk(relaxed = true), timeProvider, TestCurrencySettingsRepository())

        val start = ms(2026, 1, 1)
        val end = ms(2026, 5, 1)

        // Simulate 3000 EUR expenses summed by aggregate SQL.
        coEvery { expenseDao.getAllSpentBetweenByCurrency(start, end) } returns listOf(
            CurrencyTotal("EUR", 30000.0, 3000)
        )

        val result = repository.getExpensesByCurrency(start, end)

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals(30000.0, data["EUR"]!!, 0.0001)
    }

    /**
     * A.9 Batch 5 regression: same merchantKey but different raw merchant labels
     * must remain separate buckets in getMerchantTotalsInHomeCurrency.
     * The DAO groups by raw `merchant` (not merchantKey) to match the pre-A.9
     * row-scan grouping where each `expense.merchant` string was a distinct key.
     */
    @Test
    fun `merchant totals keep different raw labels as separate buckets`() = runTest {
        every { timeProvider.now() } returns ms(2026, 4, 15)

        val converter = mockk<CurrencyConverter>(relaxed = true)
        coEvery { converter.convertMultiple(any(), "EUR") } coAnswers {
            val amounts = firstArg<List<Pair<Double, String>>>()
            MultiConversionAggregate(
                total = amounts.sumOf { it.first },
                targetCurrency = "EUR",
                failedConversions = emptyList()
            )
        }

        val repository = MultiCurrencyRepository(expenseDao, converter, timeProvider, TestCurrencySettingsRepository())

        val start = ms(2026, 4, 1)
        val end = ms(2026, 5, 1)

        // Two rows with same merchantKey but different raw merchant label
        coEvery { expenseDao.getAllMerchantTotalsBetweenByCurrency(start, end) } returns listOf(
            MerchantCurrencyTotal(merchant = "Amazon.com", currency = "EUR", total = 100.0, txCount = 3),
            MerchantCurrencyTotal(merchant = "AMAZON MARKETPLACE", currency = "EUR", total = 50.0, txCount = 1)
        )

        val result = repository.getMerchantTotalsInHomeCurrency(start, end, "EUR")

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals(2, data.size)
        assertTrue(data.containsKey("Amazon.com"))
        assertTrue(data.containsKey("AMAZON MARKETPLACE"))
        assertEquals(100.0, data["Amazon.com"]!!, 0.0001)
        assertEquals(50.0, data["AMAZON MARKETPLACE"]!!, 0.0001)
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