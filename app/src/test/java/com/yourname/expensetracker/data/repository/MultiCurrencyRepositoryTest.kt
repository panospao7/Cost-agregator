package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.CategoryCurrencyTotal
import com.yourname.expensetracker.data.database.dao.CategoryTotal
import com.yourname.expensetracker.data.database.dao.CurrencyTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MerchantCurrencyTotal
import com.yourname.expensetracker.data.database.dao.MerchantTotal
import com.yourname.expensetracker.data.database.dao.MonthlyCurrencyTotal
import com.yourname.expensetracker.data.database.dao.MonthlySpendingTotal
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        repository = MultiCurrencyRepository(expenseDao, currencyConverter, timeProvider, currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true), applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
    }

    // ── getMonthlyTotalsInHomeCurrency ─────────────────────────────────────

    @Test
    fun `Missing exchange rate returns home currency total`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        // Uses type-agnostic grouped aggregate helper (A.9 Batch 5).
        coEvery { expenseDao.getAllMonthlyTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            MonthlyCurrencyTotal("2026-04", "EUR", 100.0, 1),
            MonthlyCurrencyTotal("2026-04", "USD", 50.0, 1)
        )
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

        // Verify type-agnostic grouped aggregate helper was called, NOT the uncapped row scan.
        coVerify(exactly = 1) { expenseDao.getAllMonthlyTotalsBetweenByCurrency(startDate, endDate) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `Known rate converts correctly via aggregate path`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        // Single currency — now also uses grouped aggregate (no single-currency fast path).
        coEvery { expenseDao.getAllMonthlyTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            MonthlyCurrencyTotal("2026-04", "EUR", 60.0, 2)
        )
        coEvery { currencyConverter.convertMultiple(listOf(60.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 60.0, targetCurrency = "EUR", failedConversions = emptyList()
        )

        val result = repository.getMonthlyTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val monthTotals = (result as Result.Success).data
        assertEquals(1, monthTotals.size)
        assertApproxEquals(60.0, monthTotals.single().total, 0.0001)
        assertTrue(monthTotals.single().failedConversions.isEmpty())

        // Verify type-agnostic aggregate helpers were called, NOT the uncapped row scan.
        coVerify(exactly = 1) { expenseDao.getAllMonthlyTotalsBetweenByCurrency(startDate, endDate) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `Stale rate uses last known rate with warning`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllMonthlyTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            MonthlyCurrencyTotal("2026-04", "USD", 100.0, 1),
            MonthlyCurrencyTotal("2026-04", "EUR", 0.0, 0)
        )
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

        // Verify type-agnostic grouped aggregate helper was called, NOT the uncapped row scan.
        coVerify(exactly = 1) { expenseDao.getAllMonthlyTotalsBetweenByCurrency(startDate, endDate) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    @Test
    fun `Multiple currencies all converted and summed via multi-currency path`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllMonthlyTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            MonthlyCurrencyTotal("2026-04", "EUR", 10.0, 1),
            MonthlyCurrencyTotal("2026-04", "USD", 10.0, 1),
            MonthlyCurrencyTotal("2026-04", "GBP", 10.0, 1)
        )
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

        // Verify type-agnostic grouped aggregate helper was called, NOT the uncapped row scan.
        coVerify(exactly = 1) { expenseDao.getAllMonthlyTotalsBetweenByCurrency(startDate, endDate) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    // ── getExpensesByCurrency ──────────────────────────────────────────────

    /**
     * A.9 Batch 5: getExpensesByCurrency uses type-agnostic aggregate DAO helper.
     * Verifies that getAllSpentBetweenByCurrency is called and row scan is not.
     */
    @Test
    fun `getExpensesByCurrency uses aggregate DAO helper`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllSpentBetweenByCurrency(startDate, endDate) } returns listOf(
            CurrencyTotal("EUR", 15000.0, 1500),
            CurrencyTotal("USD", 5000.0, 1000)
        )

        val result = repository.getExpensesByCurrency(startDate, endDate)

        assertTrue(result is Result.Success)
        val byCurrency = (result as Result.Success).data
        assertApproxEquals(15000.0, byCurrency["EUR"]!!, 0.0001)
        assertApproxEquals(5000.0, byCurrency["USD"]!!, 0.0001)

        // Verify type-agnostic aggregate helper was called, NOT the uncapped row scan.
        coVerify(exactly = 1) { expenseDao.getAllSpentBetweenByCurrency(startDate, endDate) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    /**
     * A.9 regression: multi-currency totals must not be silently truncated
     * when the expense count exceeds the old LIMIT 2000 default.
     * Now uses aggregate DAO path so truncation is impossible.
     */
    @Test
    fun `multi-currency totals are not truncated when expense count exceeds old LIMIT 2000`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        // Simulate >2000 expenses via aggregate — the aggregate path sums in SQL,
        // so the row count doesn't matter.
        coEvery { expenseDao.getAllSpentBetweenByCurrency(startDate, endDate) } returns listOf(
            CurrencyTotal("EUR", 15000.0, 1500),
            CurrencyTotal("USD", 5000.0, 1000)
        )

        val result = repository.getExpensesByCurrency(startDate, endDate)

        assertTrue(result is Result.Success)
        val byCurrency = (result as Result.Success).data
        // 1500 x 10.0 = 15000.0 EUR (aggregate from SQL)
        assertApproxEquals(15000.0, byCurrency["EUR"]!!, 0.0001)
        // 1000 x 5.0 = 5000.0 USD (aggregate from SQL)
        assertApproxEquals(5000.0, byCurrency["USD"]!!, 0.0001)
    }

    // ── getTotalExpensesInHomeCurrency ─────────────────────────────────────

    /**
     * A.9 Batch 5: getTotalExpensesInHomeCurrency uses type-agnostic aggregate DAO helper.
     */
    @Test
    fun `getTotalExpensesInHomeCurrency uses aggregate DAO path`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllSpentBetweenByCurrency(startDate, endDate) } returns listOf(
            CurrencyTotal("EUR", 100.0, 2),
            CurrencyTotal("USD", 50.0, 1)
        )
        coEvery { currencyConverter.convertMultiple(any(), "EUR") } returns MultiConversionAggregate(
            total = 145.0,
            targetCurrency = "EUR",
            failedConversions = emptyList()
        )

        val result = repository.getTotalExpensesInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        assertApproxEquals(145.0, (result as Result.Success).data, 0.0001)

        // Verify type-agnostic aggregate helper was called, NOT the uncapped row scan.
        coVerify(exactly = 1) { expenseDao.getAllSpentBetweenByCurrency(startDate, endDate) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    /**
     * A.9 Batch 5: getTotalExpensesInHomeCurrency returns error on missing rate.
     */
    @Test
    fun `getTotalExpensesInHomeCurrency returns error on missing rate`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllSpentBetweenByCurrency(startDate, endDate) } returns listOf(
            CurrencyTotal("EUR", 100.0, 1),
            CurrencyTotal("JPY", 1000.0, 1)
        )
        coEvery { currencyConverter.convertMultiple(any(), "EUR") } returns MultiConversionAggregate(
            total = 100.0,
            targetCurrency = "EUR",
            failedConversions = listOf(
                FailedConversion(
                    originalAmount = 1000.0,
                    originalCurrency = "JPY",
                    targetCurrency = "EUR",
                    reason = "Missing exchange rate from JPY to EUR"
                )
            )
        )

        val result = repository.getTotalExpensesInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Error)
        val message = (result as Result.Error).message.orEmpty()
        assertTrue(message.contains("Missing exchange rates"))
    }

    // ── getCategoryTotalsInHomeCurrency ────────────────────────────────────

    /**
     * A.9 Batch 5: category totals use type-agnostic grouped aggregate path
     * for all scenarios (single and multi-currency).
     */
    @Test
    fun `getCategoryTotalsInHomeCurrency uses grouped aggregate path`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllCategoryTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            CategoryCurrencyTotal(categoryId = 1L, currency = "EUR", total = 120.0, txCount = 2),
            CategoryCurrencyTotal(categoryId = 2L, currency = "EUR", total = 80.0, txCount = 1)
        )
        coEvery { currencyConverter.convertMultiple(listOf(120.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 120.0, targetCurrency = "EUR", failedConversions = emptyList()
        )
        coEvery { currencyConverter.convertMultiple(listOf(80.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 80.0, targetCurrency = "EUR", failedConversions = emptyList()
        )

        val result = repository.getCategoryTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertApproxEquals(120.0, data[1L]!!, 0.0001)
        assertApproxEquals(80.0, data[2L]!!, 0.0001)

        // Verify type-agnostic grouped aggregate helpers were called, NOT the uncapped row scan.
        coVerify(exactly = 1) { expenseDao.getAllCategoryTotalsBetweenByCurrency(startDate, endDate) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    /**
     * A.9 Batch 5: multi-currency uses grouped aggregate helper for category totals.
     */
    @Test
    fun `getCategoryTotalsInHomeCurrency multi-currency uses grouped aggregate`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        // Grouped aggregate: (categoryId=1, EUR, 100) and (categoryId=2, USD, 50)
        coEvery { expenseDao.getAllCategoryTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            CategoryCurrencyTotal(categoryId = 1L, currency = "EUR", total = 100.0, txCount = 1),
            CategoryCurrencyTotal(categoryId = 2L, currency = "USD", total = 50.0, txCount = 1)
        )
        coEvery { currencyConverter.convertMultiple(listOf(100.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 100.0, targetCurrency = "EUR", failedConversions = emptyList()
        )
        coEvery { currencyConverter.convertMultiple(listOf(50.0 to "USD"), "EUR") } returns MultiConversionAggregate(
            total = 45.0, targetCurrency = "EUR", failedConversions = emptyList()
        )

        val result = repository.getCategoryTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertApproxEquals(100.0, data[1L]!!, 0.0001)
        assertApproxEquals(45.0, data[2L]!!, 0.0001)

        // Verify type-agnostic grouped aggregate helper was called, NOT the uncapped row scan.
        coVerify(exactly = 1) { expenseDao.getAllCategoryTotalsBetweenByCurrency(startDate, endDate) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    /**
     * A.9 Batch 5 regression: null categoryId rows must be preserved in the
     * result map, not silently dropped. Pre-A.9 row scan included uncategorized
     * expenses; the single-currency fast path regressed by using
     * getCategoryTotalsBetween which filtered `AND categoryId IS NOT NULL`.
     */
    @Test
    fun `getCategoryTotalsInHomeCurrency preserves null categoryId rows`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        // Grouped aggregate includes a null-categoryId bucket.
        coEvery { expenseDao.getAllCategoryTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            CategoryCurrencyTotal(categoryId = 1L, currency = "EUR", total = 80.0, txCount = 1),
            CategoryCurrencyTotal(categoryId = null, currency = "EUR", total = 30.0, txCount = 2)
        )
        coEvery { currencyConverter.convertMultiple(listOf(80.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 80.0, targetCurrency = "EUR", failedConversions = emptyList()
        )
        coEvery { currencyConverter.convertMultiple(listOf(30.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 30.0, targetCurrency = "EUR", failedConversions = emptyList()
        )

        val result = repository.getCategoryTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        // null key must be present — uncategorized expenses must not be dropped.
        assertTrue("null categoryId key must be present", data.containsKey(null))
        assertApproxEquals(30.0, data[null]!!, 0.0001)
        assertApproxEquals(80.0, data[1L]!!, 0.0001)
    }

    // ── getMerchantTotalsInHomeCurrency ────────────────────────────────────

    /**
     * A.9 Batch 5: merchant totals use type-agnostic grouped aggregate path
     * for all scenarios (single and multi-currency).
     */
    @Test
    fun `getMerchantTotalsInHomeCurrency uses grouped aggregate path`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllMerchantTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            MerchantCurrencyTotal(merchant = "Amazon", currency = "USD", total = 200.0, txCount = 2),
            MerchantCurrencyTotal(merchant = "Walmart", currency = "USD", total = 100.0, txCount = 1)
        )
        coEvery { currencyConverter.convertMultiple(listOf(200.0 to "USD"), "EUR") } returns MultiConversionAggregate(
            total = 180.0, targetCurrency = "EUR", failedConversions = emptyList()
        )
        coEvery { currencyConverter.convertMultiple(listOf(100.0 to "USD"), "EUR") } returns MultiConversionAggregate(
            total = 90.0, targetCurrency = "EUR", failedConversions = emptyList()
        )

        val result = repository.getMerchantTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertApproxEquals(180.0, data["Amazon"]!!, 0.0001)  // 200 * 0.9
        assertApproxEquals(90.0, data["Walmart"]!!, 0.0001)  // 100 * 0.9

        // Verify type-agnostic grouped aggregate helpers were called, NOT the uncapped row scan.
        coVerify(exactly = 1) { expenseDao.getAllMerchantTotalsBetweenByCurrency(startDate, endDate) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    /**
     * A.9 Batch 5: merchant totals are sorted descending by total.
     */
    @Test
    fun `getMerchantTotalsInHomeCurrency results are sorted descending by total`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllMerchantTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            MerchantCurrencyTotal(merchant = "Big", currency = "EUR", total = 200.0, txCount = 1),
            MerchantCurrencyTotal(merchant = "Small", currency = "EUR", total = 100.0, txCount = 1)
        )
        coEvery { currencyConverter.convertMultiple(listOf(200.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 200.0, targetCurrency = "EUR", failedConversions = emptyList()
        )
        coEvery { currencyConverter.convertMultiple(listOf(100.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 100.0, targetCurrency = "EUR", failedConversions = emptyList()
        )

        val result = repository.getMerchantTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val keys = (result as Result.Success).data.keys.toList()
        assertEquals("Big", keys[0])
        assertEquals("Small", keys[1])
    }

    /**
     * A.9 Batch 5: multi-currency uses grouped aggregate helper for merchant totals.
     */
    @Test
    fun `getMerchantTotalsInHomeCurrency multi-currency uses grouped aggregate`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        // Grouped aggregate: (Amazon, EUR, 100), (Walmart, USD, 80)
        coEvery { expenseDao.getAllMerchantTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            MerchantCurrencyTotal(merchant = "Amazon", currency = "EUR", total = 100.0, txCount = 1),
            MerchantCurrencyTotal(merchant = "Walmart", currency = "USD", total = 80.0, txCount = 1)
        )
        coEvery { currencyConverter.convertMultiple(listOf(100.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 100.0, targetCurrency = "EUR", failedConversions = emptyList()
        )
        coEvery { currencyConverter.convertMultiple(listOf(80.0 to "USD"), "EUR") } returns MultiConversionAggregate(
            total = 72.0, targetCurrency = "EUR", failedConversions = emptyList()
        )

        val result = repository.getMerchantTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertApproxEquals(100.0, data["Amazon"]!!, 0.0001)
        assertApproxEquals(72.0, data["Walmart"]!!, 0.0001)

        // Verify type-agnostic grouped aggregate helper was called, NOT the uncapped row scan.
        coVerify(exactly = 1) { expenseDao.getAllMerchantTotalsBetweenByCurrency(startDate, endDate) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    /**
     * A.9 Batch 5: multi-currency merchant totals are sorted descending by converted total.
     */
    @Test
    fun `getMerchantTotalsInHomeCurrency multi-currency results are sorted descending`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllMerchantTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            MerchantCurrencyTotal(merchant = "Small", currency = "EUR", total = 50.0, txCount = 1),
            MerchantCurrencyTotal(merchant = "Big", currency = "USD", total = 200.0, txCount = 1)
        )
        coEvery { currencyConverter.convertMultiple(listOf(50.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 50.0, targetCurrency = "EUR", failedConversions = emptyList()
        )
        coEvery { currencyConverter.convertMultiple(listOf(200.0 to "USD"), "EUR") } returns MultiConversionAggregate(
            total = 180.0, targetCurrency = "EUR", failedConversions = emptyList()
        )

        val result = repository.getMerchantTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val keys = (result as Result.Success).data.keys.toList()
        assertEquals("Big", keys[0])    // 180 > 50
        assertEquals("Small", keys[1])
    }

    /**
     * A.9 Batch 5 regression: same merchantKey but different raw merchant labels
     * must remain **separate** buckets. Pre-A.9 row scan grouped by the raw
     * `expense.merchant` string; aggregating by `COALESCE(merchantKey, merchant)`
     * merged them, changing the output. Grouping by raw `merchant` restores parity.
     */
    @Test
    fun `getMerchantTotalsInHomeCurrency keeps different merchant labels as separate buckets`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        // The DAO now groups by raw merchant, so two rows with different display
        // names come back as two separate MerchantCurrencyTotal rows — even
        // though in the real DB they would share the same merchantKey.
        coEvery { expenseDao.getAllMerchantTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            MerchantCurrencyTotal(merchant = "Amazon.com", currency = "EUR", total = 80.0, txCount = 2),
            MerchantCurrencyTotal(merchant = "AMAZON MARKETPLACE", currency = "EUR", total = 40.0, txCount = 1)
        )
        coEvery { currencyConverter.convertMultiple(listOf(80.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 80.0, targetCurrency = "EUR", failedConversions = emptyList()
        )
        coEvery { currencyConverter.convertMultiple(listOf(40.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 40.0, targetCurrency = "EUR", failedConversions = emptyList()
        )

        val result = repository.getMerchantTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        // Both raw merchant labels must appear as separate keys.
        assertEquals("Both merchant labels must be separate keys", 2, data.size)
        assertTrue("Amazon.com must be present", data.containsKey("Amazon.com"))
        assertTrue("AMAZON MARKETPLACE must be present", data.containsKey("AMAZON MARKETPLACE"))
        assertApproxEquals(80.0, data["Amazon.com"]!!, 0.0001)
        assertApproxEquals(40.0, data["AMAZON MARKETPLACE"]!!, 0.0001)
    }

    /**
     * A.9 Batch 5 regression: merchants with null merchantKey must be included
     * in the result. Pre-A.9 row scan included them; the aggregate path now
     * groups by raw `merchant` (not by merchantKey) so null-merchantKey rows
     * are naturally included.
     */
    @Test
    fun `getMerchantTotalsInHomeCurrency includes null merchantKey rows`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        // SQL returns a row for a merchant that has null merchantKey, grouped by raw name.
        coEvery { expenseDao.getAllMerchantTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            MerchantCurrencyTotal(merchant = "Amazon", currency = "EUR", total = 100.0, txCount = 1),
            MerchantCurrencyTotal(merchant = "Corner Shop", currency = "EUR", total = 25.0, txCount = 3)
        )
        coEvery { currencyConverter.convertMultiple(listOf(100.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 100.0, targetCurrency = "EUR", failedConversions = emptyList()
        )
        coEvery { currencyConverter.convertMultiple(listOf(25.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 25.0, targetCurrency = "EUR", failedConversions = emptyList()
        )

        val result = repository.getMerchantTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        // "Corner Shop" (from a null-merchantKey row) must be present.
        assertTrue("null-merchantKey merchant must be present", data.containsKey("Corner Shop"))
        assertApproxEquals(25.0, data["Corner Shop"]!!, 0.0001)
        assertApproxEquals(100.0, data["Amazon"]!!, 0.0001)
    }

    // ── getMonthlyTotalsInHomeCurrency (single-currency aggregate path) ───

    /**
     * A.9 Batch 5: single-currency monthly totals use type-agnostic aggregate DAO helper.
     */
    @Test
    fun `getMonthlyTotalsInHomeCurrency single-currency uses aggregate path`() = runTest(testDispatcher) {
        val startDate = millis(2026, 1, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllMonthlyTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            MonthlyCurrencyTotal("2026-01", "USD", 100.0, 1),
            MonthlyCurrencyTotal("2026-02", "USD", 100.0, 1),
            MonthlyCurrencyTotal("2026-03", "USD", 100.0, 1),
            MonthlyCurrencyTotal("2026-04", "USD", 100.0, 1)
        )
        coEvery { currencyConverter.convertMultiple(any(), "EUR") } coAnswers {
            val amounts = firstArg<List<Pair<Double, String>>>()
            val total = amounts.sumOf { it.first * 0.9 }
            MultiConversionAggregate(total = total, targetCurrency = "EUR", failedConversions = emptyList())
        }

        val result = repository.getMonthlyTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val months = (result as Result.Success).data
        assertEquals(4, months.size)
        months.forEach { mt ->
            assertApproxEquals(90.0, mt.total, 0.0001) // 100 * 0.9
            assertEquals("EUR", mt.homeCurrency)
            assertTrue(mt.failedConversions.isEmpty())
        }
        assertEquals("2026-01", months.first().monthKey)
        assertEquals("2026-04", months.last().monthKey)

        // Verify type-agnostic aggregate helpers were called, NOT the uncapped row scan.
        coVerify(exactly = 1) { expenseDao.getAllMonthlyTotalsBetweenByCurrency(startDate, endDate) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    // ── Empty results ─────────────────────────────────────────────────────

    /**
     * A.9 Batch 5: empty aggregate result returns empty map/list.
     */
    @Test
    fun `getExpensesByCurrency returns empty map when no expenses`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllSpentBetweenByCurrency(startDate, endDate) } returns emptyList()

        val result = repository.getExpensesByCurrency(startDate, endDate)

        assertTrue(result is Result.Success)
        assertTrue((result as Result.Success).data.isEmpty())
    }

    @Test
    fun `getMonthlyTotalsInHomeCurrency returns empty list when no expenses`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllMonthlyTotalsBetweenByCurrency(startDate, endDate) } returns emptyList()

        val result = repository.getMonthlyTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        assertTrue((result as Result.Success).data.isEmpty())
    }

    @Test
    fun `getCategoryTotalsInHomeCurrency returns empty map when no expenses`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllCategoryTotalsBetweenByCurrency(startDate, endDate) } returns emptyList()

        val result = repository.getCategoryTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        assertTrue((result as Result.Success).data.isEmpty())
    }

    @Test
    fun `getMerchantTotalsInHomeCurrency returns empty map when no expenses`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllMerchantTotalsBetweenByCurrency(startDate, endDate) } returns emptyList()

        val result = repository.getMerchantTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        assertTrue((result as Result.Success).data.isEmpty())
    }

    // ── >2000 completeness via aggregate ──────────────────────────────────

    /**
     * A.9 regression: getTotalExpensesInHomeCurrency handles >2000 expenses via aggregate.
     * The aggregate path sums in SQL so row count does not matter.
     */
    @Test
    fun `getTotalExpensesInHomeCurrency handles over 2000 expenses via aggregate`() = runTest(testDispatcher) {
        val startDate = millis(2026, 1, 1)
        val endDate = millis(2026, 5, 1)

        // 3000 EUR expenses summed to 30000.0 by aggregate SQL
        coEvery { expenseDao.getAllSpentBetweenByCurrency(startDate, endDate) } returns listOf(
            CurrencyTotal("EUR", 30000.0, 3000)
        )
        coEvery { currencyConverter.convertMultiple(any(), "EUR") } returns MultiConversionAggregate(
            total = 30000.0,
            targetCurrency = "EUR",
            failedConversions = emptyList()
        )

        val result = repository.getTotalExpensesInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        assertApproxEquals(30000.0, (result as Result.Success).data, 0.0001)
    }

    /**
     * A.9 regression: getExpensesByCurrency handles >2000 expenses via aggregate.
     */
    @Test
    fun `getExpensesByCurrency handles over 2000 expenses via aggregate`() = runTest(testDispatcher) {
        val startDate = millis(2026, 1, 1)
        val endDate = millis(2026, 5, 1)

        // 2500 total: 1500 EUR + 1000 USD
        coEvery { expenseDao.getAllSpentBetweenByCurrency(startDate, endDate) } returns listOf(
            CurrencyTotal("EUR", 15000.0, 1500),
            CurrencyTotal("USD", 5000.0, 1000)
        )

        val result = repository.getExpensesByCurrency(startDate, endDate)

        assertTrue(result is Result.Success)
        val byCurrency = (result as Result.Success).data
        assertApproxEquals(15000.0, byCurrency["EUR"]!!, 0.0001)
        assertApproxEquals(5000.0, byCurrency["USD"]!!, 0.0001)
    }

    /**
     * A.9 regression: getCategoryTotalsInHomeCurrency handles >2000 via aggregate.
     */
    @Test
    fun `getCategoryTotalsInHomeCurrency handles over 2000 expenses via aggregate`() = runTest(testDispatcher) {
        val startDate = millis(2026, 1, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getAllCategoryTotalsBetweenByCurrency(startDate, endDate) } returns listOf(
            CategoryCurrencyTotal(categoryId = 1L, currency = "EUR", total = 20000.0, txCount = 2000),
            CategoryCurrencyTotal(categoryId = 2L, currency = "EUR", total = 10000.0, txCount = 1000)
        )
        coEvery { currencyConverter.convertMultiple(listOf(20000.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 20000.0, targetCurrency = "EUR", failedConversions = emptyList()
        )
        coEvery { currencyConverter.convertMultiple(listOf(10000.0 to "EUR"), "EUR") } returns MultiConversionAggregate(
            total = 10000.0, targetCurrency = "EUR", failedConversions = emptyList()
        )

        val result = repository.getCategoryTotalsInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertApproxEquals(20000.0, data[1L]!!, 0.0001)
        assertApproxEquals(10000.0, data[2L]!!, 0.0001)
    }

    // ── Regression: non-PURCHASE rows are not excluded ────────────────────

    /**
     * A.9 Batch 5 regression: the aggregate helpers must be type-agnostic.
     * Pre-A.10, the MultiCurrencyRepository included all transaction types
     * (PURCHASE, DEPOSIT, TRANSFER, etc.). The previous Batch 5 implementation
     * narrowed to PURCHASE-only via getTotalSpentBetweenByCurrency, which was
     * scope drift into A.10. The type-agnostic helpers restore parity.
     *
     * This test verifies that a DEPOSIT row contributed by the DAO aggregate
     * is not silently excluded — the repository calls the type-agnostic DAO
     * method and forwards whatever the DB returns.
     */
    @Test
    fun `getExpensesByCurrency is type-agnostic and includes non-PURCHASE rows`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        // The type-agnostic DAO helper returns totals that include deposits.
        coEvery { expenseDao.getAllSpentBetweenByCurrency(startDate, endDate) } returns listOf(
            CurrencyTotal("EUR", 350.0, 5)  // 3 purchases + 2 deposits summed
        )

        val result = repository.getExpensesByCurrency(startDate, endDate)

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        // Total must reflect ALL types, not just PURCHASE.
        assertApproxEquals(350.0, data["EUR"]!!, 0.0001)

        // Must call type-agnostic method, not PURCHASE-only.
        coVerify(exactly = 1) { expenseDao.getAllSpentBetweenByCurrency(startDate, endDate) }
        coVerify(exactly = 0) { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) }
    }

    /**
     * A.9 Batch 5 regression: getTotalExpensesInHomeCurrency is type-agnostic.
     */
    @Test
    fun `getTotalExpensesInHomeCurrency is type-agnostic`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        // Type-agnostic total includes DEPOSIT + PURCHASE.
        coEvery { expenseDao.getAllSpentBetweenByCurrency(startDate, endDate) } returns listOf(
            CurrencyTotal("EUR", 500.0, 10)  // mixed types
        )
        coEvery { currencyConverter.convertMultiple(any(), "EUR") } returns MultiConversionAggregate(
            total = 500.0, targetCurrency = "EUR", failedConversions = emptyList()
        )

        val result = repository.getTotalExpensesInHomeCurrency(startDate, endDate, "EUR")

        assertTrue(result is Result.Success)
        assertApproxEquals(500.0, (result as Result.Success).data, 0.0001)

        coVerify(exactly = 1) { expenseDao.getAllSpentBetweenByCurrency(startDate, endDate) }
        coVerify(exactly = 0) { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun expense(
        id: Long,
        amount: Double,
        currency: String,
        date: Long,
        categoryId: Long? = null
    ): Expense {
        return Expense(
            id = id,
            amount = amount,
            currency = currency,
            merchant = "Merchant$id",
            transactionType = TransactionType.PURCHASE,
            date = date,
            categoryId = categoryId
        )
    }

    private fun millis(year: Int, month: Int, day: Int): Long {
        return LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}