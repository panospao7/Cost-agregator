package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MonthMoneyAggregate
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.model.PeriodStatus
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import timber.log.Timber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlin.test.assertFailsWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Failure-mode, fail-closed, cancellation, status and repository-failure tests for
 * [TotalsAggregationEngine]. Exact period/average/category value pins live in
 * [TotalsAggregationEngineValidationTest] and [TotalsAggregationEngineDeepTest].
 *
 * Standalone (never tabled, so one failure cannot hide another): cancellation
 * propagation, bounded failure logging, and the fail-closed baseline refusals.
 */
class TotalsAggregationEngineTest {
    private lateinit var engine: TotalsAggregationEngine
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val multiCurrencyRepo = mockk<MultiCurrencyRepository>()
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val logs = mutableListOf<Pair<Throwable?, String>>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (tag == "TotalsAggregationEngine") logs += t to message
        }
    }

    @After fun tearDown() { Timber.uproot(logTree) }

    @Before
    fun setup() {
        Timber.plant(logTree)
        stubDefaults()
        engine = TotalsAggregationEngine(expenseRepository, timeProvider, multiCurrencyRepo, categoryRepository, Dispatchers.Unconfined)
        every { timeProvider.now() } returns System.currentTimeMillis()
    }

    private fun stubDefaults() {
        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } returns
            com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Available(MoneyAggregate.empty(CurrencyCode("EUR")))
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns emptyList()
        coEvery { multiCurrencyRepo.getCategoryAggregatesHistorical(any(), any()) } returns emptyMap()
        coEvery { multiCurrencyRepo.getWeeklyAggregatesHistorical(any(), any()) } returns emptyList()
        coEvery { multiCurrencyRepo.getDailyAggregatesHistorical(any(), any()) } returns emptyList()

        // Reactive flows depend on expenseRepository.getTotalSpent() to trigger emission
        every { expenseRepository.getTotalSpent() } returns flowOf(null)
    }

    /** Fresh mocks for a table row: clear every recorded call/answer, re-stub, pin the clock. */
    private fun resetMocks(now: Long) {
        clearMocks(expenseRepository, timeProvider, multiCurrencyRepo, categoryRepository)
        stubDefaults()
        every { timeProvider.now() } returns now
    }

    private fun assertSafeLog(operation: String) {
        assertTrue("missing $operation: $logs", logs.any { it.second == "UNKNOWN_ERROR operation=$operation class=IllegalStateException" })
        assertTrue(logs.all { it.first == null && !it.second.contains("secret") && !it.second.contains("918") })
    }

    // ========== Cancellation: never swallowed, never a zero/empty fallback ==========

    @Test
    fun `average cancellation propagates without zero fallback or failure log`() = runTest {
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } throws
            CancellationException("secret SQL /data/918")

        assertFailsWith<CancellationException> { engine.getAverageForPeriodType(PeriodType.MONTH, false) }
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `reactive totals cancellation emits no fallback and cancels suspended collection`() = runTest {
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } throws
            CancellationException("secret SQL /data/918")

        // An inner flatMapLatest cancellation must not become an empty-list emission.
        assertTrue(engine.getMonthlyTotals(2026).toList().isEmpty())
        assertTrue(logs.isEmpty())

        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } coAnswers {
            entered.complete(Unit)
            try { awaitCancellation() } finally { exited.complete(Unit) }
        }
        val collection = async { engine.getMonthlyTotals(2026).toList() }
        entered.await()
        val cancellation = CancellationException("secret SQL /data/918")
        collection.cancel(cancellation)
        val thrown = assertFailsWith<CancellationException> { collection.await() }
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === cancellation })
        exited.await()
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `category cancellation emits no fallback and cancels suspended collection`() = runTest {
        coEvery { categoryRepository.getAll() } throws CancellationException("secret SQL /data/918")

        assertTrue(engine.getCategoryBreakdown(0, 100, "range").toList().isEmpty())
        assertTrue(logs.isEmpty())

        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        coEvery { categoryRepository.getAll() } coAnswers {
            entered.complete(Unit)
            try { awaitCancellation() } finally { exited.complete(Unit) }
        }
        val collection = async { engine.getCategoryBreakdown(0, 100, "range").toList() }
        entered.await()
        val cancellation = CancellationException("secret SQL /data/918")
        collection.cancel(cancellation)
        val thrown = assertFailsWith<CancellationException> { collection.await() }
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === cancellation })
        exited.await()
        assertTrue(logs.isEmpty())
    }

    // ========== Failure catch: bounded log, then recovery ==========

    @Test
    fun `average catch returns unavailable and emits bounded log`() = runTest {
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } throws
            IllegalStateException("secret SQL /data/918")
        assertNull(engine.getAverageForPeriodType(PeriodType.MONTH, false))
        assertSafeLog("average")
    }

    @Test
    fun `reactive totals catch recovers on subsequent emission`() = runTest {
        every { expenseRepository.getTotalSpent() } returns flowOf(null, null)
        var calls = 0
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } answers {
            if (++calls == 1) throw IllegalStateException("secret SQL /data/918")
            emptyList()
        }
        val emissions = engine.getMonthlyTotals(2026).toList()
        assertEquals(2, emissions.size)
        assertTrue(emissions[0].isEmpty())
        assertEquals(12, emissions[1].size)
        assertSafeLog("reactive_flow")
    }

    @Test
    fun `reactive category catch recovers on subsequent emission`() = runTest {
        every { expenseRepository.getTotalSpent() } returns flowOf(null, null)
        var calls = 0
        coEvery { categoryRepository.getAll() } answers {
            if (++calls == 1) throw IllegalStateException("secret SQL /data/918")
            emptyList()
        }
        val emissions = engine.getCategoryBreakdown(0, 100, "range").toList()
        assertEquals(2, emissions.size)
        assertTrue(emissions.all { it.isEmpty() })
        assertSafeLog("reactive_category_breakdown")
    }

    // ========== TABLE 1: repository failure falls back to empty / unavailable ==========

    private class FailureRow(
        val name: String,
        val stubFailure: () -> Unit,
        /** Returns the first emission or the average; compared with [expected]. */
        val run: suspend () -> Any?,
        /** `null` means unavailable (average), otherwise the exact fallback value. */
        val expected: Any?
    )

    @Test
    fun `repository failure table - every public path degrades to empty or unavailable`() = runTest {
        val rows = listOf(
            FailureRow(
                "average MONTH",
                { coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } throws RuntimeException("DB error") },
                { engine.getAverageForPeriodType(PeriodType.MONTH, excludeCurrent = false) },
                null
            ),
            FailureRow(
                "monthly totals",
                { coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } throws RuntimeException("DB error") },
                { engine.getMonthlyTotals(2026).first() },
                emptyList<Any>()
            ),
            FailureRow(
                "weekly totals",
                { coEvery { multiCurrencyRepo.getWeeklyAggregatesHistorical(any(), any()) } throws RuntimeException("DB error") },
                { engine.getWeeklyTotals(2026, 1).first() },
                emptyList<Any>()
            ),
            FailureRow(
                "daily totals",
                { coEvery { multiCurrencyRepo.getDailyAggregatesHistorical(any(), any()) } throws RuntimeException("DB error") },
                { engine.getDailyTotals(2026, 3).first() },
                emptyList<Any>()
            ),
            FailureRow(
                "category breakdown",
                { coEvery { multiCurrencyRepo.getCategoryAggregatesHistorical(any(), any()) } throws RuntimeException("DB error") },
                { engine.getCategoryBreakdown(0L, 1_000L, "Jan").first() },
                emptyList<Any>()
            )
        )
        assertEquals("repository failure table size", 5, rows.size)
        rows.forEach { row ->
            resetMocks(LocalDate.of(2026, 4, 15).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            row.stubFailure()

            assertEquals("${row.name}: fallback", row.expected, row.run())
        }
    }

    // ========== TABLE 2: getPeriodStatus ==========

    private class StatusRow(
        val name: String,
        /** `null` total means unavailable money. */
        val total: Double?,
        val averageAmount: Double?,
        val expected: PeriodStatus
    )

    @Test
    fun `period status table - under over equal and no data boundaries`() {
        val rows = listOf(
            StatusRow("50 below average 100", 50.0, 100.0, PeriodStatus.UNDER_AVERAGE),
            StatusRow("800 below average 1000", 800.0, 1000.0, PeriodStatus.UNDER_AVERAGE),
            StatusRow("99.99 just below average 100", 99.99, 100.0, PeriodStatus.UNDER_AVERAGE),
            StatusRow("150 above average 100", 150.0, 100.0, PeriodStatus.OVER_AVERAGE),
            StatusRow("120 above average 100", 120.0, 100.0, PeriodStatus.OVER_AVERAGE),
            StatusRow("1200 above average 1000", 1200.0, 1000.0, PeriodStatus.OVER_AVERAGE),
            StatusRow("100.01 just above average 100", 100.01, 100.0, PeriodStatus.OVER_AVERAGE),
            StatusRow("100 equal to average 100 is OVER", 100.0, 100.0, PeriodStatus.OVER_AVERAGE),
            StatusRow("average 0 is NO_DATA", 50.0, 0.0, PeriodStatus.NO_DATA),
            StatusRow("total 1000 with average 0 is NO_DATA", 1000.0, 0.0, PeriodStatus.NO_DATA),
            StatusRow("negative average is NO_DATA", 50.0, -10.0, PeriodStatus.NO_DATA),
            StatusRow("unavailable total is NO_DATA", null, 100.0, PeriodStatus.NO_DATA)
        )
        assertEquals("period status table size", 12, rows.size)
        rows.forEach { row ->
            val status = engine.getPeriodStatus(
                row.total?.let { MoneyAggregate.singleCurrency(it, CurrencyCode.EUR) },
                row.averageAmount?.let { com.yourname.expensetracker.domain.core.money.MoneyAmount(it, CurrencyCode.EUR) }
            )

            assertEquals(row.name, row.expected, status)
        }
    }

    // ========== Fail-closed baselines (standalone, unweakened) ==========

    @Test
    fun `getPeriodStatus refuses cross currency and unavailable baselines`() {
        val aggregate = MoneyAggregate.singleCurrency(50.0, CurrencyCode.EUR)
        assertEquals(PeriodStatus.NO_DATA, engine.getPeriodStatus(
            aggregate, com.yourname.expensetracker.domain.core.money.MoneyAmount(100.0, CurrencyCode.USD)
        ))
        assertEquals(PeriodStatus.NO_DATA, engine.getPeriodStatus(aggregate, null))
    }

    @Test
    fun `monthly average refuses mixed normalized currency labels`() = runTest {
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns listOf(
            MonthMoneyAggregate("2026-01", MoneyAggregate.singleCurrency(50.0, CurrencyCode.EUR)),
            MonthMoneyAggregate("2026-02", MoneyAggregate.singleCurrency(100.0, CurrencyCode.USD))
        )

        assertNull(engine.getAverageForPeriodType(PeriodType.MONTH, false))
    }

    @Test
    fun `daily average stays unavailable when conversion is unavailable`() = runTest {
        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } returns
            com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Unavailable(
                "DISPLAY_CONVERSION_UNAVAILABLE", com.yourname.expensetracker.domain.core.money.RateBasis.TRANSACTION_DATE
            )

        assertNull(engine.getAverageForPeriodType(PeriodType.DAY, false))
    }

    @Test
    fun `partial monthly conversion cannot become a complete baseline`() = runTest {
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns listOf(
            MonthMoneyAggregate("2026-01", MoneyAggregate.singleCurrency(100.0, CurrencyCode.EUR)),
            MonthMoneyAggregate("2026-02", partialAggregate(50.0, 1))
        )

        assertNull(engine.getAverageForPeriodType(PeriodType.MONTH, false))
    }

    @Test
    fun `partial daily conversion cannot become a complete baseline`() = runTest {
        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } returns
            com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Available(
                partialAggregate(50.0, 1)
            )

        assertNull(engine.getAverageForPeriodType(PeriodType.DAY, false))
    }

    @Test
    fun `partial zero year cannot be silently removed from the baseline`() = runTest {
        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } returns
            com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Available(
                partialAggregate(0.0, 1)
            )

        assertNull(engine.getAverageForPeriodType(PeriodType.YEAR, false))
    }

    @Test
    fun `partial total has no comparison status`() {
        assertEquals(PeriodStatus.NO_DATA, engine.getPeriodStatus(
            partialAggregate(50.0, 1),
            com.yourname.expensetracker.domain.core.money.MoneyAmount(100.0, CurrencyCode.EUR)
        ))
    }

    private fun partialAggregate(amount: Double, count: Int): MoneyAggregate =
        MoneyAggregate.partial(
            displayAmount = amount,
            displayCurrency = CurrencyCode("EUR"),
            sourceBuckets = listOf(com.yourname.expensetracker.domain.core.money.MoneyBucket(CurrencyCode("EUR"), amount, count)),
            failures = listOf(
                com.yourname.expensetracker.domain.core.money.ConversionFailure(
                    originalAmount = com.yourname.expensetracker.domain.core.money.MoneyAmount(50.0, CurrencyCode("USD")),
                    targetCurrency = CurrencyCode("EUR"),
                    reason = com.yourname.expensetracker.domain.core.money.FailureReason.MISSING_RATE,
                    transactionCount = 1
                )
            )
        )
}
