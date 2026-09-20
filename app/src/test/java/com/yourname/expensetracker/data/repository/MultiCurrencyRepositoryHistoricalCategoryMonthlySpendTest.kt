package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.SpendScope
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * RP-08 (slice C1): contract tests for
 * [MultiCurrencyRepository.getHistoricalCategoryMonthlySpend].
 *
 * Covers the fixed method contract from the RP-08 "Contract gate":
 * PURCHASE-only filtering, `SpendScope.Category(null)` for uncategorized rows,
 * separate `SpendScope.Overall` scope, half-open range pass-through,
 * multi-currency grouping with transaction-date conversion, typed partial
 * outcomes for failed conversions (never silently dropped, never a sentinel),
 * empty-range behavior, and typed home-currency failure propagation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiCurrencyRepositoryHistoricalCategoryMonthlySpendTest {

    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repository: MultiCurrencyRepository

    @Before
    fun setUp() {
        everyHomeCurrencyResolved("EUR")
        every { timeProvider.now() } returns millis(2026, 4, 15)
        repository = MultiCurrencyRepository(
            expenseDao = expenseDao,
            currencyConverter = currencyConverter,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettingsRepository,
            applicationScope = CoroutineScope(Dispatchers.Unconfined)
        )
    }

    // ── PURCHASE-only filtering ────────────────────────────────────────────

    @Test
    fun `PURCHASE only - deposits and transfers are excluded`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getExpensesBetweenUncapped(startDate, endDate) } returns listOf(
            expense(id = 1, amount = 100.0, currency = "EUR", date = millis(2026, 4, 10), categoryId = 1L),
            expense(id = 2, amount = 500.0, currency = "EUR", date = millis(2026, 4, 11), categoryId = 1L, type = TransactionType.DEPOSIT),
            expense(id = 3, amount = 200.0, currency = "EUR", date = millis(2026, 4, 12), categoryId = 1L, type = TransactionType.TRANSFER),
            expense(id = 4, amount = 50.0, currency = "EUR", date = millis(2026, 4, 13), categoryId = 1L)
        )

        val result = repository.getHistoricalCategoryMonthlySpend(startDate, endDate)

        // One category row + one overall row, both PURCHASE-only (150.0).
        val categoryRow = result.single { it.scope == SpendScope.Category(1L) }
        assertApproxEquals(150.0, categoryRow.aggregate.displayAmount, 0.0001)
        val overallRow = result.single { it.scope == SpendScope.Overall }
        assertApproxEquals(150.0, overallRow.aggregate.displayAmount, 0.0001)

        // Identity conversions only — converter must never be called.
        coVerify(exactly = 0) { currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `No PURCHASE rows in range returns empty list`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getExpensesBetweenUncapped(startDate, endDate) } returns listOf(
            expense(id = 1, amount = 500.0, currency = "EUR", date = millis(2026, 4, 11), type = TransactionType.DEPOSIT)
        )

        val result = repository.getHistoricalCategoryMonthlySpend(startDate, endDate)

        assertTrue(result.isEmpty())
    }

    // ── Scope semantics ────────────────────────────────────────────────────

    @Test
    fun `Uncategorized purchases become a real Category-null bucket`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getExpensesBetweenUncapped(startDate, endDate) } returns listOf(
            expense(id = 1, amount = 80.0, currency = "EUR", date = millis(2026, 4, 10), categoryId = null),
            expense(id = 2, amount = 20.0, currency = "EUR", date = millis(2026, 4, 11), categoryId = 5L)
        )

        val result = repository.getHistoricalCategoryMonthlySpend(startDate, endDate)

        val uncategorized = result.first { it.scope == SpendScope.Category(null) }
        assertEquals("2026-04", uncategorized.monthKey)
        assertApproxEquals(80.0, uncategorized.aggregate.displayAmount, 0.0001)

        val categorized = result.first { it.scope == SpendScope.Category(5L) }
        assertApproxEquals(20.0, categorized.aggregate.displayAmount, 0.0001)
    }

    @Test
    fun `Overall scope is separate from Category-null and includes all categories`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getExpensesBetweenUncapped(startDate, endDate) } returns listOf(
            expense(id = 1, amount = 60.0, currency = "EUR", date = millis(2026, 4, 10), categoryId = 1L),
            expense(id = 2, amount = 40.0, currency = "EUR", date = millis(2026, 4, 11), categoryId = 2L),
            expense(id = 3, amount = 30.0, currency = "EUR", date = millis(2026, 4, 12), categoryId = null)
        )

        val result = repository.getHistoricalCategoryMonthlySpend(startDate, endDate)

        // 3 category buckets + 1 overall bucket = 4 rows.
        assertEquals(4, result.size)
        val overallRows = result.filter { it.scope == SpendScope.Overall }
        assertEquals(1, overallRows.size)
        assertApproxEquals(130.0, overallRows.single().aggregate.displayAmount, 0.0001)

        // Overall must be a distinct scope — never the null-category bucket.
        val nullCategoryRow = result.first { it.scope == SpendScope.Category(null) }
        assertApproxEquals(30.0, nullCategoryRow.aggregate.displayAmount, 0.0001)
        assertTrue(overallRows.single().scope != nullCategoryRow.scope)
    }

    // ── Month keys + half-open range pass-through ──────────────────────────

    @Test
    fun `Month keys follow local yyyy-MM policy and range args pass through half-open`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        // Rows on the first millisecond of the window and the last millisecond
        // before its end are both inside [startDate, endDate).
        val lastMsBeforeEnd = millis(2026, 4, 30) + (24 * 60 * 60 * 1000L) - 1
        coEvery { expenseDao.getExpensesBetweenUncapped(startDate, endDate) } returns listOf(
            expense(id = 1, amount = 10.0, currency = "EUR", date = startDate, categoryId = 1L),
            expense(id = 2, amount = 15.0, currency = "EUR", date = lastMsBeforeEnd, categoryId = 1L)
        )

        val result = repository.getHistoricalCategoryMonthlySpend(startDate, endDate)

        // Both rows are in local month 2026-04.
        val categoryRow = result.single { it.scope == SpendScope.Category(1L) }
        assertEquals("2026-04", categoryRow.monthKey)
        assertApproxEquals(25.0, categoryRow.aggregate.displayAmount, 0.0001)

        // The half-open range is delegated unchanged to the DAO read; the SQL
        // WHERE clause (`date >= :startDate AND date < :endDate`) enforces the
        // exclusion of endDate rows at the database level.
        coVerify(exactly = 1) { expenseDao.getExpensesBetweenUncapped(startDate, endDate) }
    }

    @Test
    fun `Months are grouped separately across month boundaries`() = runTest(testDispatcher) {
        val startDate = millis(2026, 3, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getExpensesBetweenUncapped(startDate, endDate) } returns listOf(
            expense(id = 1, amount = 100.0, currency = "EUR", date = millis(2026, 3, 10), categoryId = 1L),
            expense(id = 2, amount = 70.0, currency = "EUR", date = millis(2026, 4, 10), categoryId = 1L)
        )

        val result = repository.getHistoricalCategoryMonthlySpend(startDate, endDate)

        val categoryRows = result.filter { it.scope == SpendScope.Category(1L) }
        assertEquals(2, categoryRows.size)
        assertEquals(
            setOf("2026-03", "2026-04"),
            categoryRows.map { it.monthKey }.toSet()
        )
        val overallRows = result.filter { it.scope == SpendScope.Overall }
        assertEquals(2, overallRows.size)
    }

    // ── Multi-currency grouping with transaction-date conversion ───────────

    @Test
    fun `Multi-currency history converts at transaction-date rates per month`() = runTest(testDispatcher) {
        val startDate = millis(2026, 3, 1)
        val endDate = millis(2026, 5, 1)
        val aprUsdDate = millis(2026, 4, 20)
        val marUsdDate = millis(2026, 3, 20)

        coEvery { expenseDao.getExpensesBetweenUncapped(startDate, endDate) } returns listOf(
            expense(id = 1, amount = 100.0, currency = "EUR", date = millis(2026, 4, 10), categoryId = 1L),
            expense(id = 2, amount = 50.0, currency = "USD", date = aprUsdDate, categoryId = 1L),
            expense(id = 3, amount = 50.0, currency = "USD", date = marUsdDate, categoryId = 2L)
        )

        // Rate changed between March and April — the same USD amount must
        // convert differently per transaction date (TRANSACTION_DATE basis).
        coEvery {
            currencyConverter.convertOutcome(50.0, "USD", "EUR", RateBasis.TRANSACTION_DATE, marUsdDate, any())
        } returns convertedOutcome(50.0, "USD", rate = 0.8)
        coEvery {
            currencyConverter.convertOutcome(50.0, "USD", "EUR", RateBasis.TRANSACTION_DATE, aprUsdDate, any())
        } returns convertedOutcome(50.0, "USD", rate = 0.9)

        val result = repository.getHistoricalCategoryMonthlySpend(startDate, endDate)

        val aprCategory = result.first { it.scope == SpendScope.Category(1L) && it.monthKey == "2026-04" }
        assertApproxEquals(145.0, aprCategory.aggregate.displayAmount, 0.0001) // 100 + 50*0.9

        val marCategory = result.first { it.scope == SpendScope.Category(2L) && it.monthKey == "2026-03" }
        assertApproxEquals(40.0, marCategory.aggregate.displayAmount, 0.0001) // 50*0.8

        val aprOverall = result.first { it.scope == SpendScope.Overall && it.monthKey == "2026-04" }
        assertApproxEquals(145.0, aprOverall.aggregate.displayAmount, 0.0001)

        val marOverall = result.first { it.scope == SpendScope.Overall && it.monthKey == "2026-03" }
        assertApproxEquals(40.0, marOverall.aggregate.displayAmount, 0.0001)

        // TRANSACTION_DATE basis is requested on every aggregate.
        result.forEach { row ->
            assertEquals(RateBasis.TRANSACTION_DATE, row.aggregate.rateBasis)
        }

        // Conversion happens per dated bucket with the transaction dates. Each
        // dated conversion runs twice: once for the Category aggregate and once
        // for the Overall aggregate of the same month.
        coVerify(exactly = 2) {
            currencyConverter.convertOutcome(50.0, "USD", "EUR", RateBasis.TRANSACTION_DATE, marUsdDate, any())
        }
        coVerify(exactly = 2) {
            currencyConverter.convertOutcome(50.0, "USD", "EUR", RateBasis.TRANSACTION_DATE, aprUsdDate, any())
        }
    }

    // ── Typed partial outcome for conversion failures ──────────────────────

    @Test
    fun `Conversion failure surfaces as partial aggregate - not dropped, not a sentinel`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)
        val jpyDate = millis(2026, 4, 12)

        coEvery { expenseDao.getExpensesBetweenUncapped(startDate, endDate) } returns listOf(
            expense(id = 1, amount = 100.0, currency = "EUR", date = millis(2026, 4, 10), categoryId = 1L),
            expense(id = 2, amount = 1000.0, currency = "JPY", date = jpyDate, categoryId = 2L)
        )

        coEvery {
            currencyConverter.convertOutcome(1000.0, "JPY", "EUR", RateBasis.TRANSACTION_DATE, jpyDate, any())
        } returns failedOutcome(1000.0, "JPY")

        val result = repository.getHistoricalCategoryMonthlySpend(startDate, endDate)

        // The failed bucket must still be present as a typed partial aggregate —
        // not silently dropped and not substituted with a fake total.
        val failedCategory = result.first { it.scope == SpendScope.Category(2L) }
        assertTrue(failedCategory.aggregate.isPartial)
        assertEquals(1, failedCategory.aggregate.conversionFailures.size)
        // FailureReason is mapped by the engine; MISSING_RATE is the controlled
        // mapping for a missing-rate conversion failure.
        assertEquals(
            com.yourname.expensetracker.domain.core.money.FailureReason.MISSING_RATE,
            failedCategory.aggregate.conversionFailures.single().reason
        )
        assertApproxEquals(0.0, failedCategory.aggregate.displayAmount, 0.0001)
        assertEquals(0, failedCategory.aggregate.metadata.includedTransactionCount)
        assertEquals(1, failedCategory.aggregate.metadata.excludedTransactionCount)
        assertEquals(1, failedCategory.aggregate.metadata.missingRateCount)

        // The overall aggregate keeps the converted EUR total and reports the
        // exclusion — it must not become a fabricated complete total.
        val overall = result.single { it.scope == SpendScope.Overall }
        assertTrue(overall.aggregate.isPartial)
        assertApproxEquals(100.0, overall.aggregate.displayAmount, 0.0001)
        assertEquals(1, overall.aggregate.metadata.excludedTransactionCount)
        assertEquals(1, overall.aggregate.failedTransactionCount)

        // Result is a non-empty list with typed partial data, not empty.
        assertFalse(result.isEmpty())
    }

    // ── Empty range ────────────────────────────────────────────────────────

    @Test
    fun `Empty range returns empty list`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { expenseDao.getExpensesBetweenUncapped(startDate, endDate) } returns emptyList()

        val result = repository.getHistoricalCategoryMonthlySpend(startDate, endDate)

        assertTrue(result.isEmpty())
        coVerify(exactly = 1) { expenseDao.getExpensesBetweenUncapped(startDate, endDate) }
    }

    // ── Typed home-currency failure propagation ────────────────────────────

    @Test
    fun `Home-currency resolution failure throws typed exception - never empty list`() = runTest(testDispatcher) {
        val startDate = millis(2026, 4, 1)
        val endDate = millis(2026, 5, 1)

        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Failed("datastore read error")

        var thrown: Throwable? = null
        try {
            repository.getHistoricalCategoryMonthlySpend(startDate, endDate)
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull("A typed failure must propagate — never an empty list", thrown)
        assertTrue(
            "Expected HomeCurrencyUnavailableException but was ${thrown?.javaClass?.name}",
            thrown is HomeCurrencyUnavailableException
        )

        // No DAO read is attempted when home currency is unavailable.
        coVerify(exactly = 0) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun everyHomeCurrencyResolved(code: String) {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode(code))
    }

    private fun expense(
        id: Long,
        amount: Double,
        currency: String,
        date: Long,
        categoryId: Long? = null,
        type: TransactionType = TransactionType.PURCHASE
    ): Expense {
        return Expense(
            id = id,
            amount = amount,
            currency = currency,
            merchant = "Merchant$id",
            transactionType = type,
            date = date,
            categoryId = categoryId
        )
    }

    private fun convertedOutcome(
        amount: Double,
        from: String,
        rate: Double,
        to: String = "EUR"
    ): ConversionOutcome.Converted {
        return ConversionOutcome.Converted(
            originalAmount = amount,
            originalCurrency = CurrencyCode(from),
            convertedAmount = amount * rate,
            targetCurrency = CurrencyCode(to),
            rateUsed = rate,
            rateBasis = RateBasis.TRANSACTION_DATE,
            rateValidDate = null,
            rateLastUpdated = null,
            rateSource = null,
            conversionPath = ConversionPath.DIRECT
        )
    }

    private fun failedOutcome(amount: Double, from: String, to: String = "EUR"): ConversionOutcome.Failed {
        return ConversionOutcome.Failed(
            originalAmount = amount,
            originalCurrency = from,
            targetCurrency = to,
            rateBasis = RateBasis.TRANSACTION_DATE,
            failureType = ConversionFailureType.MISSING_RATE,
            message = "Missing exchange rate from $from to $to"
        )
    }

    private fun millis(year: Int, month: Int, day: Int): Long {
        return LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
