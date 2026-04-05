package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.FailedConversion
import com.yourname.expensetracker.domain.currency.MultiConversionAggregate
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class MultiCurrencyRepositoryTest {

    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repository: MultiCurrencyRepository

    @Before
    fun setUp() {
        every { timeProvider.now() } returns millis(2026, 4, 15)
        repository = MultiCurrencyRepository(expenseDao, currencyConverter, timeProvider)
    }

    @Test
    fun `Missing exchange rate returns home currency total`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)
        val expenses = listOf(
            expense(id = 1L, amount = 100.0, currency = "EUR", date = millis(2026, 4, 2)),
            expense(id = 2L, amount = 50.0, currency = "USD", date = millis(2026, 4, 3))
        )

        coEvery { expenseDao.getExpensesBetween(startDate, endDate) } returns expenses
        coEvery { currencyConverter.convertMultiple(any(), "EUR") } returns MultiConversionAggregate(
            total = 100.0,
            targetCurrency = "EUR",
            failedConversions = listOf(
                FailedConversion(
                    originalAmount = 50.0,
                    originalCurrency = "USD",
                    targetCurrency = "EUR",
                    reason = "Missing exchange rate from USD to EUR"
                )
            )
        )

        val result = repository.getMonthlyTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val monthTotals = (result as Result.Success).data
        assertEquals(1, monthTotals.size)
        assertApproxEquals(100.0, monthTotals.single().total, 0.0001)
        assertEquals("EUR", monthTotals.single().homeCurrency)
        assertEquals(1, monthTotals.single().failedConversions.size)
        assertTrue(monthTotals.single().failedConversions.first().reason.contains("Missing exchange rate"))
    }

    @Test
    fun `Known rate converts correctly`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)
        val expenses = listOf(
            expense(id = 10L, amount = 40.0, currency = "EUR", date = millis(2026, 4, 5)),
            expense(id = 11L, amount = 20.0, currency = "USD", date = millis(2026, 4, 6))
        )

        coEvery { expenseDao.getExpensesBetween(startDate, endDate) } returns expenses
        coEvery { currencyConverter.convertMultiple(any(), "EUR") } returns MultiConversionAggregate(
            total = 58.0,
            targetCurrency = "EUR",
            failedConversions = emptyList()
        )

        val result = repository.getMonthlyTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val monthTotals = (result as Result.Success).data
        assertEquals(1, monthTotals.size)
        assertApproxEquals(58.0, monthTotals.single().total, 0.0001)
        assertTrue(monthTotals.single().failedConversions.isEmpty())
    }

    @Test
    fun `Stale rate uses last known rate with warning`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)
        val expenses = listOf(
            expense(id = 20L, amount = 100.0, currency = "USD", date = millis(2026, 4, 7))
        )

        coEvery { expenseDao.getExpensesBetween(startDate, endDate) } returns expenses
        coEvery { currencyConverter.convertMultiple(any(), "EUR") } returns MultiConversionAggregate(
            total = 92.0,
            targetCurrency = "EUR",
            failedConversions = listOf(
                FailedConversion(
                    originalAmount = 100.0,
                    originalCurrency = "USD",
                    targetCurrency = "EUR",
                    reason = "Stale rate detected; used last known USD→EUR rate"
                )
            )
        )

        val result = repository.getMonthlyTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val monthTotal = (result as Result.Success).data.single()
        assertApproxEquals(92.0, monthTotal.total, 0.0001)
        assertEquals(1, monthTotal.failedConversions.size)
        assertTrue(monthTotal.failedConversions.first().reason.contains("Stale rate"))
    }

    @Test
    fun `Multiple currencies all converted and summed`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)
        val expenses = listOf(
            expense(id = 30L, amount = 10.0, currency = "EUR", date = millis(2026, 4, 8)),
            expense(id = 31L, amount = 10.0, currency = "USD", date = millis(2026, 4, 9)),
            expense(id = 32L, amount = 10.0, currency = "GBP", date = millis(2026, 4, 10))
        )

        coEvery { expenseDao.getExpensesBetween(startDate, endDate) } returns expenses
        coEvery { currencyConverter.convertMultiple(any(), "EUR") } coAnswers {
            val amounts = firstArg<List<Pair<Double, String>>>()
            val rates = mapOf("EUR" to 1.0, "USD" to 0.9, "GBP" to 1.2)
            val total = amounts.sumOf { (amount, currency) -> amount * (rates[currency] ?: 0.0) }
            MultiConversionAggregate(
                total = total,
                targetCurrency = "EUR",
                failedConversions = emptyList()
            )
        }

        val result = repository.getMonthlyTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val monthTotal = (result as Result.Success).data.single()
        assertApproxEquals(31.0, monthTotal.total, 0.0001)
        coVerify(exactly = 1) {
            currencyConverter.convertMultiple(
                match { amounts ->
                    amounts.size == 3 &&
                        amounts.contains(10.0 to "EUR") &&
                        amounts.contains(10.0 to "USD") &&
                        amounts.contains(10.0 to "GBP")
                },
                "EUR"
            )
        }
    }

    private fun expense(id: Long, amount: Double, currency: String, date: Long): Expense {
        return Expense(
            id = id,
            amount = amount,
            currency = currency,
            merchant = "Merchant$id",
            transactionType = TransactionType.PURCHASE,
            date = date
        )
    }

    private fun millis(year: Int, month: Int, day: Int): Long {
        return LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
