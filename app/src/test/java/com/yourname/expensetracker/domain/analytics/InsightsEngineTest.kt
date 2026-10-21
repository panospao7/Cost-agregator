package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertMoneyEquals
import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimeProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import io.mockk.mockk
import io.mockk.every
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber
import org.junit.After
import java.time.Instant
import kotlin.test.assertFailsWith

class InsightsEngineTest {

    companion object {
        // Zone pinned to UTC for the whole class: buildDailyTotals buckets by system-default days.
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()

        /** Fixed "now" (2026-03-15T12:00:00Z) so day buckets never depend on the wall clock. */
        private val fixedNow: Long = Instant.parse("2026-03-15T12:00:00Z").toEpochMilli()
    }

    private var nextExpenseId = 1L
    private lateinit var engine: InsightsEngine
    private lateinit var timeProvider: TimeProvider
    private lateinit var recurringEngine: com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
    private lateinit var monthlyCalculator: MonthlyComparisonCalculator
    private lateinit var categoryCalculator: CategoryInsightEngine
    private lateinit var merchantCalculator: MerchantInsightEngine
    private lateinit var paceCalculator: SpendingPaceCalculator
    private lateinit var anomalyCalculator: AnomalyDetector
    private lateinit var weekdayCalculator: DayOfWeekAnalyzer
    private val logs = mutableListOf<Pair<Throwable?, String>>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += t to message
        }
    }

    @After fun tearDown() { Timber.uproot(logTree) }

    @Before
    fun setup() {
        Timber.plant(logTree)
        rebuild()
    }

    /** Creates a fresh set of mocks, a fresh engine and an empty log; used per table row too. */
    private fun rebuild() {
        logs.clear()
        val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
        timeProvider = mockk(relaxed = true)
        recurringEngine = mockk(relaxed = true)
        monthlyCalculator = mockk(relaxed = true)
        categoryCalculator = mockk(relaxed = true)
        merchantCalculator = mockk(relaxed = true)
        paceCalculator = mockk(relaxed = true)
        anomalyCalculator = mockk(relaxed = true)
        weekdayCalculator = mockk(relaxed = true)
        coEvery { recurringEngine.getPatterns(any()) } returns emptyList()
        coEvery { recurringEngine.getPatternsFromSnapshots(any()) } returns emptyList()
        every { timeProvider.now() } returns fixedNow

        engine = InsightsEngine(
            expenseRepository = expenseRepository,
            recurringExpenseEngine = recurringEngine,
            timeProvider = timeProvider,
            spendingPaceCalculator = paceCalculator,
            anomalyDetector = anomalyCalculator,
            monthlyComparisonCalculator = monthlyCalculator,
            categoryInsightEngine = categoryCalculator,
            merchantInsightEngine = merchantCalculator,
            dayOfWeekAnalyzer = weekdayCalculator
        )
    }

    private val dayMs = 86_400_000L

    private class CancellationRow(
        val stage: String,
        /** When true the propagated exception must be exactly [CancellationException], not a subclass. */
        val exactClass: Boolean = false,
        val stub: () -> Unit
    )

    @Test
    fun `cancellation at every insight stage propagates without fallback log`() = runTest {
        // One row per async insight stage. INV: CancellationException is never swallowed into a
        // degraded (null/zero) section and never produces an UNKNOWN_ERROR fallback log.
        val rows = listOf(
            CancellationRow("monthlyComparison") {
                every { monthlyCalculator.calculate(any(), any(), any<List<com.yourname.expensetracker.domain.model.ExpenseSnapshot>>(), any()) } throws CancellationException("private receipt")
            },
            CancellationRow("categoryInsights") {
                every { categoryCalculator.calculate(any(), any(), any(), any<List<com.yourname.expensetracker.domain.model.ExpenseSnapshot>>(), any()) } throws CancellationException("private receipt")
            },
            CancellationRow("topMerchants") {
                every { merchantCalculator.calculate(any(), any()) } throws CancellationException("private receipt")
            },
            CancellationRow("spendingPace") {
                every { paceCalculator.calculate(any(), any(), any(), any<List<com.yourname.expensetracker.domain.model.ExpenseSnapshot>>(), any(), any()) } throws CancellationException("private receipt")
            },
            CancellationRow("anomalies") {
                every { anomalyCalculator.detect(any(), any(), any(), any(), any()) } throws CancellationException("private receipt")
            },
            CancellationRow("dayOfWeekPattern") {
                every {
                    weekdayCalculator.analyze(
                        any(),
                        any(),
                        any<NormalizedAnalyticsInput>(),
                        any(),
                        any()
                    )
                } throws CancellationException("private receipt")
            },
            CancellationRow("recurringExpenses", exactClass = true) {
                coEvery { recurringEngine.getPatternsFromSnapshots(any()) } throws CancellationException("private receipt cancellation")
            }
        )
        assertEquals(
            "row table must cover all seven insight stages",
            listOf("monthlyComparison", "categoryInsights", "topMerchants", "spendingPace", "anomalies", "dayOfWeekPattern", "recurringExpenses"),
            rows.map { it.stage }
        )

        for (row in rows) {
            rebuild() // fresh mocks, engine and log per row
            row.stub()

            val thrown = assertFailsWith<CancellationException>("stage=${row.stage}: CancellationException must propagate") {
                engine.generateInsights(emptyList(), emptyList(), "EUR")
            }
            if (row.exactClass) {
                assertEquals("stage=${row.stage}: exact exception class", CancellationException::class.java, thrown::class.java)
            }
            assertTrue(
                "stage=${row.stage}: no fallback/private log expected but was $logs",
                logs.none { it.first != null || it.second.contains("UNKNOWN_ERROR") || it.second.contains("private") }
            )
        }
    }

    @Test
    fun `all seven insight boundaries degrade without leaking exception text`() = runTest {
        val hostile = IllegalStateException("merchant=private /storage/secret.db amount=918")
        every { monthlyCalculator.calculate(any(), any(), any<List<com.yourname.expensetracker.domain.model.ExpenseSnapshot>>(), any()) } throws hostile
        every { categoryCalculator.calculate(any(), any(), any(), any<List<com.yourname.expensetracker.domain.model.ExpenseSnapshot>>(), any()) } throws hostile
        every { merchantCalculator.calculate(any(), any()) } throws hostile
        every { paceCalculator.calculate(any(), any(), any(), any<List<com.yourname.expensetracker.domain.model.ExpenseSnapshot>>(), any(), any()) } throws hostile
        every { anomalyCalculator.detect(any(), any(), any(), any(), any()) } throws hostile
        coEvery { recurringEngine.getPatternsFromSnapshots(any()) } throws hostile
        every {
            weekdayCalculator.analyze(
                any(),
                any(),
                any<NormalizedAnalyticsInput>(),
                any(),
                any()
            )
        } throws hostile

        val snapshot = engine.generateInsights(emptyList(), emptyList(), "EUR")
        // unavailable-not-zero: a failed monthly stage must stay null, never become 0.0.
        org.junit.Assert.assertNull(snapshot.monthlyComparison.currentTotal)
        assertTrue(snapshot.categoryInsights.isEmpty())
        assertTrue(snapshot.topMerchants.isEmpty())
        assertEquals(PaceStatus.NO_BASELINE, snapshot.spendingPace.paceStatus)
        assertTrue(snapshot.anomalies.isEmpty())
        assertTrue(snapshot.recurringExpenses.isEmpty())
        assertTrue(snapshot.dayOfWeekPattern.isEmpty())
        for (stage in listOf("monthlyComparison", "categoryInsights", "topMerchants", "spendingPace", "anomalies", "recurringExpenses", "dayOfWeekPattern")) {
            assertTrue("missing $stage: $logs", logs.any { it.second == "InsightsEngine: UNKNOWN_ERROR stage=$stage class=IllegalStateException" })
        }
        assertTrue(logs.all { it.first == null && !it.second.contains("private") && !it.second.contains("918") })
    }

    private fun makeExpense(merchant: String, amount: Double, daysAgo: Int) = Expense(
        id = nextExpenseId++,
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = fixedNow - daysAgo * dayMs
    )

    @Test
    fun `buildDailyTotals includes all requested days`() {
        val expenses = listOf(
            makeExpense("Shop", 10.00, 0),
            makeExpense("Shop", 20.00, 1)
        )
        val totals = engine.buildDailyTotals(expenses.toExpenseSnapshots(), 7)
        assertEquals(7, totals.size)
    }

    @Test
    fun `buildDailyTotals sums same-day purchases`() {
        val expenses = listOf(
            Expense(id = 1, amount = 10.0, currency = "EUR", merchant = "A", transactionType = TransactionType.PURCHASE, date = fixedNow),
            Expense(id = 2, amount = 20.0, currency = "EUR", merchant = "B", transactionType = TransactionType.PURCHASE, date = fixedNow)
        )
        val totals = engine.buildDailyTotals(expenses.toExpenseSnapshots(), 1)
        val todayTotal = totals.values.last()
        assertMoneyEquals(30.0, todayTotal, "same-day purchases sum")
    }

    @Test
    fun `buildDailyTotals ignores non-purchase types`() {
        val expenses = listOf(
            Expense(id = 1, amount = 10.0, currency = "EUR", merchant = "A", transactionType = TransactionType.PURCHASE, date = fixedNow),
            Expense(id = 2, amount = 100.0, currency = "EUR", merchant = "B", transactionType = TransactionType.DEPOSIT, date = fixedNow)
        )
        val totals = engine.buildDailyTotals(expenses.toExpenseSnapshots(), 1)
        val todayTotal = totals.values.last()
        assertMoneyEquals(10.0, todayTotal, "deposit must not count as spending")
    }
}
