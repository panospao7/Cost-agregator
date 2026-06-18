package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarning
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarningType
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.NormalizedExpenseSnapshot
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [AnalyticsRepository.getSpendingSummary] ensuring the
 * [com.yourname.expensetracker.domain.core.money.MoneyAggregate] field is
 * correctly populated from the normalizer output.
 *
 * PR1: Replace aggregate=null with a real [buildMoneyAggregate] implementation.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AnalyticsRepositoryAggregateTest {

    // ── Mocks ────────────────────────────────────────────────────────────────

    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    private val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)
    private val analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)
    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
    private lateinit var repository: AnalyticsRepository

    private val homeCurrency = "EUR"
    // Arbitrary time range — actual values are irrelevant because DAO is mocked.
    private val start = 1704067200000L // 2024-01-01T00:00:00Z
    private val end = 1706659200000L   // 2024-01-31T00:00:00Z

    // ── Setup ────────────────────────────────────────────────────────────────

    @Before
    fun setup() {
        every { currencySettingsRepository.homeCurrency() } returns flowOf(homeCurrency)
        repository = AnalyticsRepository(
            expenseDao = expenseDao,
            categoryRepository = categoryRepository,
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencyConverter = currencyConverter
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Creates an [Expense] with the given properties and sensible defaults for
     * fields that are not central to the aggregate test.
     */
    private fun createExpense(
        id: Long,
        amount: Double,
        currency: String,
        date: Long
    ): Expense = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = "TestMerchant_$id",
        transactionType = TransactionType.PURCHASE,
        date = date
    )

    /**
     * Creates an [ExpenseSnapshot] from the given [Expense] and normalized amount.
     * The snapshot represents the expense AFTER conversion to home currency.
     */
    private fun expenseToSnapshot(
        expense: Expense,
        normalizedAmount: Double
    ): ExpenseSnapshot = ExpenseSnapshot(
        id = expense.id,
        amount = normalizedAmount,
        effectiveAmount = normalizedAmount,
        currency = homeCurrency,
        merchant = expense.merchant,
        merchantKey = null,
        transactionType = DomainTransactionType.PURCHASE,
        date = expense.date,
        categoryId = null,
        isNotMine = false,
        transferDirection = null,
        notes = null
    )

    /**
     * Creates a [NormalizedExpenseSnapshot] for an expense that was successfully
     * converted. The original amount/currency come from the raw [Expense]; the
     * normalized amount is the home-currency value after conversion.
     */
    private fun normalizedSnapshot(
        expense: Expense,
        normalizedAmount: Double
    ): NormalizedExpenseSnapshot = NormalizedExpenseSnapshot(
        snapshot = expenseToSnapshot(expense, normalizedAmount),
        originalCurrency = expense.currency,
        originalEffectiveAmount = expense.effectiveAmount,
        normalizedEffectiveAmount = normalizedAmount
    )

    // ── Test 1: aggregate is non-null with correct display currency ──────────

    @Test
    fun `spendingSummary_populatesAggregate()`() = runTest {
        val expenses = listOf(
            createExpense(id = 1L, amount = 50.0, currency = "USD", date = start),
            createExpense(id = 2L, amount = 30.0, currency = "EUR", date = start)
        )

        coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), any()) } returnsMany listOf(expenses, emptyList())

        val normalizedSnapshots = expenses.map { normalizedSnapshot(it, it.effectiveAmount) }

        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } returnsMany listOf(
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = normalizedSnapshots,
                includedExpenses = normalizedSnapshots.map { it.snapshot },
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = expenses.size
            ),
            // Previous period — empty
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        )

        val result = repository.getSpendingSummary(start, end).first()

        assertNotNull("aggregate must not be null", result.aggregate)
        assertEquals("displayCurrency must match home currency",
            homeCurrency, result.aggregate!!.displayCurrency.code)
    }

    // ── Test 2: aggregate.displayAmount equals totalSpent ───────────────────

    @Test
    fun `spendingSummary_aggregateDisplayAmountEqualsTotalSpent()`() = runTest {
        val expenses = listOf(
            createExpense(id = 1L, amount = 50.0, currency = "USD", date = start),
            createExpense(id = 2L, amount = 30.0, currency = "EUR", date = start),
            createExpense(id = 3L, amount = 20.0, currency = "USD", date = start)
        )

        coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), any()) } returnsMany listOf(expenses, emptyList())

        val normalizedSnapshots = expenses.map { normalizedSnapshot(it, it.effectiveAmount) }

        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } returnsMany listOf(
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = normalizedSnapshots,
                includedExpenses = normalizedSnapshots.map { it.snapshot },
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = expenses.size
            ),
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        )

        val result = repository.getSpendingSummary(start, end).first()

        assertNotNull("aggregate must not be null", result.aggregate)
        assertEquals("aggregate.displayAmount must equal totalSpent",
            result.totalSpent, result.aggregate!!.displayAmount, 0.001)
    }

    // ── Test 3: aggregate.displayAmount equals sum of dailyHistory ──────────

    @Test
    fun `spendingSummary_aggregateTotalEqualsDailyHistorySumWithinTolerance()`() = runTest {
        val day1 = start
        val day2 = start + 86_400_000L // +1 day
        val day3 = start + 2 * 86_400_000L // +2 days

        val expenses = listOf(
            createExpense(id = 1L, amount = 50.0, currency = "USD", date = day1),
            createExpense(id = 2L, amount = 30.0, currency = "EUR", date = day2),
            createExpense(id = 3L, amount = 20.0, currency = "USD", date = day3)
        )

        coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), any()) } returnsMany listOf(expenses, emptyList())

        val normalizedSnapshots = expenses.map { normalizedSnapshot(it, it.effectiveAmount) }

        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } returnsMany listOf(
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = normalizedSnapshots,
                includedExpenses = normalizedSnapshots.map { it.snapshot },
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = expenses.size
            ),
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        )

        val result = repository.getSpendingSummary(start, end).first()

        assertNotNull("aggregate must not be null", result.aggregate)
        val dailySum = result.dailyHistory.sum()
        assertEquals("aggregate.displayAmount must equal sum of dailyHistory",
            dailySum, result.aggregate!!.displayAmount, 0.001)
    }

    // ── Test 4: partial conversion produces failures ────────────────────────

    @Test
    fun `spendingSummary_partialConversionAggregateContainsFailure()`() = runTest {
        // Two expenses; one will be excluded by the normalizer (conversion failure).
        val excludedExpense = createExpense(id = 1L, amount = 100.0, currency = "XYZ", date = start)
        val includedExpense = createExpense(id = 2L, amount = 50.0, currency = "USD", date = start)
        val expenses = listOf(excludedExpense, includedExpense)

        coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), any()) } returnsMany listOf(expenses, emptyList())

        // Only the USD expense is included in the normalized result.
        val includedNormalized = normalizedSnapshot(includedExpense, includedExpense.effectiveAmount)

        val warning = AnalyticsConversionWarning(
            type = AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
            message = "Analytics excluded transaction(s) because exchange rates were unavailable.",
            affectedTransactionCount = 1,
            sourceCurrencies = listOf("XYZ")
        )

        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } returnsMany listOf(
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = listOf(includedNormalized),
                includedExpenses = listOf(includedNormalized.snapshot),
                warnings = listOf(warning),
                latestRateTimestamp = null,
                totalInputCount = expenses.size,
                excludedReasons = mapOf(
                    excludedExpense.id to Pair(
                        AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
                        "Analytics excluded transaction(s) because exchange rates were unavailable."
                    )
                )
            ),
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        )

        val result = repository.getSpendingSummary(start, end).first()

        assertNotNull("aggregate must not be null", result.aggregate)
        assertTrue("aggregate.isPartial must be true when there are conversion failures",
            result.aggregate!!.isPartial)
        assertTrue("conversionFailures must not be empty when expenses are excluded",
            result.aggregate!!.conversionFailures.isNotEmpty())

        // Verify the failure references the excluded expense
        val failure = result.aggregate!!.conversionFailures.first()
        assertEquals("failure originalCurrency must match excluded expense currency",
            excludedExpense.currency, failure.originalAmount.currency.code)
        assertEquals("failure originalAmount must match excluded expense effectiveAmount",
            excludedExpense.effectiveAmount, failure.originalAmount.amount, 0.001)
    }

    // ── Test 5: invalid currency warning message contains raw code ──────────

    @Test
    fun `spendingSummary_invalidCurrencyWarningMessageContainsRawCode`() = runTest {
        // Expense with invalid currency code "XYZ"
        val invalidCurrencyExpense = createExpense(id = 99L, amount = 50.0, currency = "XYZ", date = start)
        val expenses = listOf(invalidCurrencyExpense)

        coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), any()) } returnsMany listOf(expenses, emptyList())

        val warning = AnalyticsConversionWarning(
            type = AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY,
            message = "Analytics excluded transaction(s) with invalid currency codes.",
            affectedTransactionCount = 1,
            sourceCurrencies = listOf("XYZ")
        )

        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } returnsMany listOf(
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = listOf(warning),
                latestRateTimestamp = null,
                totalInputCount = expenses.size,
                excludedReasons = mapOf(
                    invalidCurrencyExpense.id to Pair(
                        AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY,
                        "Analytics excluded transaction(s) with invalid currency codes."
                    )
                )
            ),
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        )

        val result = repository.getSpendingSummary(start, end).first()

        assertNotNull("aggregate must not be null", result.aggregate)
        assertNotNull("warningMessage must be present for invalid currency", result.aggregate!!.warningMessage)
        assertTrue("warningMessage must contain raw invalid code XYZ",
            result.aggregate!!.warningMessage!!.contains("XYZ"))
        assertTrue("warningMessage must contain 'Invalid source currency'",
            result.aggregate!!.warningMessage!!.contains("Invalid source currency"))
    }

    // ── Test 6: empty period produces zero aggregate ────────────────────────

    @Test
    fun `spendingSummary_emptyPeriodAggregateIsZeroHomeCurrency()`() = runTest {
        coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), any()) } returnsMany listOf(emptyList<Expense>(), emptyList())

        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } returnsMany listOf(
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            ),
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        )

        val result = repository.getSpendingSummary(start, end).first()

        assertNotNull("aggregate must not be null even for empty period", result.aggregate)
        assertEquals("aggregate.displayAmount must be 0.0 for empty period",
            0.0, result.aggregate!!.displayAmount, 0.001)
        assertEquals("aggregate.displayCurrency must match home currency",
            homeCurrency, result.aggregate!!.displayCurrency.code)
    }

    // ── Test 7: sourceBuckets grouped by original currency ────────────────────

    @Test
    fun `spendingSummary_sourceBucketsGroupByOriginalCurrency()`() = runTest {
        // Expenses in EUR and USD — should produce two separate sourceBuckets
        val expenses = listOf(
            createExpense(id = 1L, amount = 100.0, currency = "EUR", date = start),
            createExpense(id = 2L, amount = 200.0, currency = "USD", date = start),
            createExpense(id = 3L, amount = 50.0, currency = "EUR", date = start)
        )

        coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), any()) } returnsMany listOf(expenses, emptyList())

        val normalizedSnapshots = expenses.map { normalizedSnapshot(it, it.effectiveAmount) }

        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } returnsMany listOf(
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = normalizedSnapshots,
                includedExpenses = normalizedSnapshots.map { it.snapshot },
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = expenses.size
            ),
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        )

        val result = repository.getSpendingSummary(start, end).first()

        assertNotNull("aggregate must not be null", result.aggregate)

        val buckets = result.aggregate!!.sourceBuckets
        assertEquals("there should be 2 source buckets (EUR and USD)", 2, buckets.size)

        val eurBucket = buckets.find { it.currency.code == "EUR" }
        assertNotNull("EUR bucket must exist", eurBucket)
        assertEquals("EUR original amount must be 150.0", 150.0, eurBucket!!.amount, 0.001)
        assertEquals("EUR transactionCount must be 2", 2, eurBucket.transactionCount)

        val usdBucket = buckets.find { it.currency.code == "USD" }
        assertNotNull("USD bucket must exist", usdBucket)
        assertEquals("USD original amount must be 200.0", 200.0, usdBucket!!.amount, 0.001)
        assertEquals("USD transactionCount must be 1", 1, usdBucket.transactionCount)
    }

    // ── Test 8: rateBasis is TRANSACTION_DATE ────────────────────────────────

    @Test
    fun `spendingSummary_rateBasisIsTransactionDate()`() = runTest {
        val expenses = listOf(
            createExpense(id = 1L, amount = 50.0, currency = "USD", date = start)
        )

        coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), any()) } returnsMany listOf(expenses, emptyList())

        val normalizedSnapshots = expenses.map { normalizedSnapshot(it, it.effectiveAmount) }

        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } returnsMany listOf(
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = normalizedSnapshots,
                includedExpenses = normalizedSnapshots.map { it.snapshot },
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = expenses.size
            ),
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        )

        val result = repository.getSpendingSummary(start, end).first()

        assertNotNull("aggregate must not be null", result.aggregate)
        assertEquals("rateBasis must be TRANSACTION_DATE",
            com.yourname.expensetracker.domain.core.money.RateBasis.TRANSACTION_DATE,
            result.aggregate!!.rateBasis)
    }

    // ── Test 9: conversionFailures grouped by currency and reason ────────────

    @Test
    fun `spendingSummary_conversionFailuresGroupedByCurrencyAndReason()`() = runTest {
        // Two excluded expenses in same currency (XYZ) with same reason (MISSING_EXCHANGE_RATE)
        // and one included expense in USD.
        val excluded1 = createExpense(id = 1L, amount = 100.0, currency = "XYZ", date = start)
        val excluded2 = createExpense(id = 2L, amount = 50.0, currency = "XYZ", date = start)
        val includedExpense = createExpense(id = 3L, amount = 75.0, currency = "USD", date = start)
        val expenses = listOf(excluded1, excluded2, includedExpense)

        coEvery { expenseDao.getExpensesByTypeBetween(any(), any(), any()) } returnsMany listOf(expenses, emptyList())

        val includedNormalized = normalizedSnapshot(includedExpense, includedExpense.effectiveAmount)

        val warning = AnalyticsConversionWarning(
            type = AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
            message = "Missing exchange rate for XYZ.",
            affectedTransactionCount = 2,
            sourceCurrencies = listOf("XYZ")
        )

        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } returnsMany listOf(
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = listOf(includedNormalized),
                includedExpenses = listOf(includedNormalized.snapshot),
                warnings = listOf(warning),
                latestRateTimestamp = null,
                totalInputCount = expenses.size,
                excludedReasons = mapOf(
                    excluded1.id to Pair(
                        AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
                        "Missing exchange rate for XYZ."
                    ),
                    excluded2.id to Pair(
                        AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
                        "Missing exchange rate for XYZ."
                    )
                )
            ),
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = emptyList(),
                includedExpenses = emptyList(),
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = 0
            )
        )

        val result = repository.getSpendingSummary(start, end).first()

        assertNotNull("aggregate must not be null", result.aggregate)

        val failures = result.aggregate!!.conversionFailures
        assertEquals("there should be exactly 1 grouped conversion failure",
            1, failures.size)

        val failure = failures.first()
        assertEquals("original currency must be XYZ", "XYZ", failure.originalAmount.currency.code)
        assertEquals("target currency must be EUR", homeCurrency, failure.targetCurrency.code)
        assertEquals("original amount must be sum of both excluded amounts",
            150.0, failure.originalAmount.amount, 0.001)
        assertEquals("transactionCount must be 2 (both XYZ expenses grouped)",
            2, failure.transactionCount)
        assertEquals("failure reason must be MISSING_RATE",
            com.yourname.expensetracker.domain.core.money.FailureReason.MISSING_RATE,
            failure.reason)
    }
}
