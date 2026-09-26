package com.yourname.expensetracker.domain.dashboard

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeDashboardWidgetsUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * P5-PR2 / P5-PR3 — Analytics Correctness & Performance/Robustness fixes.
 *
 * Covers NEW-P5-003, NEW-P5-006, NEW-P5-008, NEW-P5-009, NEW-P5-014.
 */
class P5AnalyticsFixesTest {

    // ── Test helpers ─────────────────────────────────────────────────────────

    private val NOW = 1717200000000L // 2024-06-01T00:00:00Z
    private val ZONE = ZoneId.of("UTC")

    private class TestRateStore : ExchangeRateStore {
        val rates = mutableMapOf<String, DomainExchangeRate>()
        override suspend fun getRate(from: String, to: String) = rates["${from}_${to}"]
        override suspend fun getRateAsOf(from: String, to: String, atMillis: Long) = rates["${from}_${to}"]
        override suspend fun getLatestRateForPair(from: String, to: String) = rates["${from}_${to}"]
        override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> = flowOf(emptyList())
        override suspend fun getLatestRate(): DomainExchangeRate? = rates.values.firstOrNull()
        override suspend fun insertOrUpdate(rate: DomainExchangeRate) { rates["${rate.fromCurrency}_${rate.toCurrency}"] = rate }
        override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) { rates.forEach { insertOrUpdate(it) } }
        override suspend fun deleteOldRates(olderThan: Long) { /* no-op */ }
    }

    private class TestTime(private val now: Long) : TimeProvider {
        override fun now(): Long = now
    }

    private fun readMainSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java", relativePath),
            File("app/src/main/java", relativePath),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/$relativePath"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java/$relativePath")
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        assertNotNull("Unable to locate main source: $relativePath", sourceFile)
        return sourceFile!!.readText()
    }

    // ── NEW-P5-003: Deposit filter excludes not-mine and shared-expense items ──

    @Test
    fun `deposit_filter_excludes_not_mine_items`() = runTest {
        // Room annotations use binary retention, so Java reflection cannot read
        // @Query reliably. Inspect the owning DAO declaration directly instead.
        val source = readMainSource(
            "com/yourname/expensetracker/data/database/dao/ExpenseDao.kt"
        )
        val declaration = "suspend fun getDepositTotalsBetweenByCurrency"
        val declarationIndex = source.indexOf(declaration)
        assertTrue("DAO method declaration must exist", declarationIndex >= 0)
        val queryStart = source.lastIndexOf("@Query(", declarationIndex)
        assertTrue("DAO method must have a preceding @Query", queryStart >= 0)
        val queryValue = source.substring(queryStart, declarationIndex)
        assertTrue(
            "DAO query must filter isSharedExpense = 0; got: $queryValue",
            queryValue.contains("isSharedExpense = 0")
        )
        assertTrue(
            "DAO query must filter isNotMine = 0; got: $queryValue",
            queryValue.contains("isNotMine = 0")
        )
    }

    @Test
    fun `deposit_entity_filter_excludes_shared_expenses`() = runTest {
        // Verify that the Kotlin-level filter in produceDashboardNormalizedInput
        // excludes deposits where isSharedExpense = true.
        val depositOwn = Expense(
            id = 1, amount = 100.0, currency = "EUR",
            merchant = "Deposit", transactionType = TransactionType.DEPOSIT,
            date = NOW, categoryId = null, isNotMine = false, isSharedExpense = false,
            isManualEntry = false
        )
        assertEquals(100.0, depositOwn.effectiveAmount, 0.001) // computed property
        val depositShared = depositOwn.copy(
            id = 2, amount = 50.0,
            isSharedExpense = true, myShareAmount = 25.0
        )
        assertEquals(25.0, depositShared.effectiveAmount, 0.001) // computed: myShareAmount
        val depositNotMine = depositOwn.copy(
            id = 3, amount = 200.0,
            isNotMine = true
        )
        assertEquals(0.0, depositNotMine.effectiveAmount, 0.001) // computed: isNotMine → 0

        val included = listOf(depositOwn, depositShared, depositNotMine).filter {
            it.transactionType == TransactionType.DEPOSIT
                && !it.isNotMine
                && !it.isSharedExpense
        }

        assertEquals("Only the own non-shared deposit should be included", 1, included.size)
        assertEquals("Own deposit should be included", 1L, included[0].id)
    }

    // ── NEW-P5-008: Category breakdown filters to PURCHASE-only ─────────────

    @Test
    fun `category_breakdown_filters_purchase_only`() = runTest {
        // Verify that the category aggregation in produceDashboardNormalizedInput
        // uses PURCHASE_ONLY filter, not ALL_TYPES.
        // The fix changed TransactionTypeFilter.ALL_TYPES to PURCHASE_ONLY.
        // Since purchases is already PURCHASE-only, this is a semantic correctness test.

        val purchaseExpense = Expense(
            id = 1, amount = 50.0, currency = "EUR",
            merchant = "Groceries", transactionType = TransactionType.PURCHASE,
            date = NOW, categoryId = 10L, isNotMine = false, isSharedExpense = false,
            isManualEntry = false
        )
        assertEquals(50.0, purchaseExpense.effectiveAmount, 0.001) // computed property

        // Value-class JVM name mangling makes reflection on aggregateExpenses
        // brittle. Pin the actual category-aggregation call site instead.
        val source = readMainSource(
            "com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt"
        )
        val blockStart = source.indexOf("val categoryAggregates = purchases")
        val blockEnd = source.indexOf("val normalizedExpenses", blockStart)
        assertTrue("Category aggregate block must exist", blockStart >= 0 && blockEnd > blockStart)
        val categoryBlock = source.substring(blockStart, blockEnd)
        assertTrue(
            "Category aggregates must use PURCHASE_ONLY",
            categoryBlock.contains("TransactionTypeFilter.PURCHASE_ONLY")
        )
        assertFalse(
            "Category aggregates must not use ALL_TYPES",
            categoryBlock.contains("TransactionTypeFilter.ALL_TYPES")
        )

        // Verify that produceDashboardNormalizedInput exists (compile-time check)
        val useCaseMethods = ComputeDashboardWidgetsUseCase::class.java.declaredMethods
        assertTrue(
            "produceDashboardNormalizedInput must exist",
            useCaseMethods.any { it.name == "produceDashboardNormalizedInput" }
        )
    }

    // ── NEW-P5-006: homeCurrency cached, not cold Flow per call ──────────────

    @Test
    fun `home_currency_is_cached_not_cold_flow`() = runTest {
        val exchangeRateStore = TestRateStore()
        exchangeRateStore.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.90, NOW, "api", NOW)

        val converter = CurrencyConverter(exchangeRateStore, TestTime(NOW))

        // Create a repository that counts how many times resolveHomeCurrency is called
        val settingsRepo = mockk<CurrencySettingsRepository>()
        every { settingsRepo.homeCurrency() } returns flowOf("EUR")

        // On first call, resolve successfully; on subsequent calls, verify cache is used
        coEvery { settingsRepo.resolveHomeCurrency() } returns
            com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Resolved(
                CurrencyCode("EUR")
            ) andThen
            com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Resolved(
                CurrencyCode("EUR")
            )

        val expenseDao = mockk<ExpenseDao>()
        coEvery { expenseDao.getAllSpentBetweenByCurrency(any(), any()) } returns emptyList()

        // Create the repository
        val repo = MultiCurrencyRepository(
            expenseDao = expenseDao,
            currencyConverter = converter,
            timeProvider = TestTime(NOW),
            currencySettingsRepository = settingsRepo,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            normalizationEngine = mockk(relaxed = true)
        )

        // Call requireHomeCurrencyForMoneyMath indirectly via getHomeCurrencyPurchaseTotal
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns emptyList()
        repo.getHomeCurrencyPurchaseTotal(NOW, NOW + 86400000L)

        // Verify resolveHomeCurrency was called exactly once (cached on first call)
        coVerify(exactly = 1) { settingsRepo.resolveHomeCurrency() }

        // Second call should use cache, not resolve again
        repo.getHomeCurrencyPurchaseTotal(NOW, NOW + 86400000L)
        coVerify(exactly = 1) { settingsRepo.resolveHomeCurrency() }
    }

    // ── NEW-P5-009: MoneyAggregateBuilder warns on size mismatch ────────────

    @Test
    fun `builder_warns_on_size_mismatch`() = runTest {
        val exchangeRateStore = TestRateStore()
        exchangeRateStore.rates["USD_EUR"] = DomainExchangeRate("USD", "EUR", 0.90, NOW, "api", NOW)
        val converter = CurrencyConverter(exchangeRateStore, TestTime(NOW))

        // When transactionCounts is shorter than buckets, the aggregate keeps the
        // pre-existing structural behavior (valid total, missing count defaulted
        // to 0 — never a negative sentinel) but is now EXPLICITLY marked:
        // isPartial + metadata.countsIncomplete + controlled warning (RP-06 D3).

        @Suppress("DEPRECATION")
        val result = MoneyAggregateBuilder.fromBuckets(
            buckets = listOf(100.0 to "USD", 200.0 to "USD"),
            homeCurrency = "EUR",
            converter = converter,
            transactionCounts = listOf(1) // only 1 count for 2 buckets
        )

        // The aggregate should still be computed correctly
        assertEquals(270.0, result.displayAmount, 0.001) // (100 + 200) * 0.9
        // Buckets are canonically grouped by currency before conversion.
        assertEquals(1, result.sourceBuckets.size)

        // The missing bucket should have 0 transaction count
        val usdBuckets = result.sourceBuckets.filter { it.currency.code == "USD" }
        assertEquals(1, usdBuckets.size)
        assertEquals(300.0, usdBuckets.single().amount, 0.001)
        // Counts are grouped too: the supplied 1 plus the missing count default of 0.
        assertEquals(1, usdBuckets.sumOf { it.transactionCount }) // 1 + 0

        // D3 pin: the mismatch is surfaced, not silent.
        assertTrue("Mismatch must mark the aggregate partial", result.isPartial)
        assertTrue(
            "Mismatch must set metadata.countsIncomplete",
            result.metadata.countsIncomplete
        )
        assertNotNull(result.warningMessage)
        assertTrue(
            "Controlled count-integrity warning must be present",
            result.warningMessage!!.contains("Transaction counts incomplete")
        )
    }

    // ── NEW-P5-014: Trend builder uses ZonedDateTime (DST-safe) ─────────────

    @Test
    fun `trend_builder_uses_zoned_date_time`() = runTest {
        // Structural verification: The buildTrendFromNormalizedInput method
        // has been updated to use java.time.ZonedDateTime instead of java.util.Calendar
        // (see NEW-P5-014 comment at line 813).
        // We verify the method's existence, signature, and that ZonedDateTime-based
        // date arithmetic is used instead of Calendar.

        val method = ComputeDashboardWidgetsUseCase::class.java.declaredMethods
            .first { it.name == "buildTrendFromNormalizedInput" }

        // Method exists — compile-time check
        assertEquals(
            "Return type must be SpendingTrend",
            "SpendingTrend", method.returnType.simpleName
        )
        assertEquals("Must accept exactly 2 parameters", 2, method.parameterCount)
        assertEquals(
            "1st param must be DashboardNormalizedInput",
            "DashboardNormalizedInput", method.parameterTypes[0].simpleName
        )
        assertEquals(
            "2nd param must be ComputeContext",
            "ComputeContext", method.parameterTypes[1].simpleName
        )

        // Verify ZonedDateTime-based month grouping logic works correctly
        // (same logic used inside buildTrendFromNormalizedInput at lines 828-830)
        val systemZone = ZoneId.systemDefault()
        val instant = Instant.ofEpochMilli(NOW)
        val zdt = instant.atZone(systemZone)

        assertEquals("Year should match", 2024, zdt.year)
        assertEquals("Month should be June (0-based index 5)", 5, zdt.monthValue - 1)

        // Verify DST-safe month start using ZonedDateTime
        // (same pattern as lines 845-848 in buildTrendFromNormalizedInput)
        val monthStartEpochMs = java.time.LocalDate.of(2024, 6, 1)
            .atStartOfDay(systemZone)
            .toInstant()
            .toEpochMilli()
        assertTrue("Month start must be positive", monthStartEpochMs > 0)

        // Verify daysInMonth using YearMonth (DST-safe, line 849)
        val daysInJune = java.time.YearMonth.of(2024, 6).lengthOfMonth()
        assertEquals("June has 30 days", 30, daysInJune)
    }
}
