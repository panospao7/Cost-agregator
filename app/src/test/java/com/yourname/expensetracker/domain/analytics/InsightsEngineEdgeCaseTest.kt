package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.toAnalyticsCategoryRefs
import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.assertMoneyEquals
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.database.dao.MerchantStats
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import java.time.Instant

class InsightsEngineEdgeCaseTest {

    companion object {
        // Zone pinned to UTC for the whole class: month/day bucketing reads the system default zone.
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()

        /** Fixed "now" (2026-03-15T12:00:00Z) so results never depend on the wall clock. */
        private val fixedNow: Long = Instant.parse("2026-03-15T12:00:00Z").toEpochMilli()
    }

    private var nextExpenseId = 1L

    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val recurringEngine = mockk<com.yourname.expensetracker.domain.logic.RecurringExpenseEngine>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var engine: InsightsEngine

    @Before
    fun setup() {
        val spendingPaceCalculator = mockk<SpendingPaceCalculator>(relaxed = true)
        val anomalyDetector = mockk<AnomalyDetector>(relaxed = true)
        val monthlyComparisonCalculator = MonthlyComparisonCalculator()
        val categoryInsightEngine = mockk<CategoryInsightEngine>(relaxed = true)
        val merchantInsightEngine = mockk<MerchantInsightEngine>(relaxed = true)
        val dayOfWeekAnalyzer = mockk<DayOfWeekAnalyzer>(relaxed = true)

        every { timeProvider.now() } returns fixedNow
        coEvery { recurringEngine.getPatterns(any()) } returns emptyList()

        engine = InsightsEngine(
            expenseRepository = expenseRepository,
            recurringExpenseEngine = recurringEngine,
            timeProvider = timeProvider,
            spendingPaceCalculator = spendingPaceCalculator,
            anomalyDetector = anomalyDetector,
            monthlyComparisonCalculator = monthlyComparisonCalculator,
            categoryInsightEngine = categoryInsightEngine,
            merchantInsightEngine = merchantInsightEngine,
            dayOfWeekAnalyzer = dayOfWeekAnalyzer,
            installmentEligibility = noPlansInstallmentEligibility()
        )

        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseRepository.getCountForPeriod(any(), any()) } returns 0
        coEvery { expenseRepository.getCategoryTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAllMerchantStats() } returns emptyList()
        coEvery { expenseRepository.getMerchantStats() } returns emptyList()
        coEvery { expenseRepository.getRecurringCandidates() } returns emptyList()
        coEvery { expenseRepository.getTopMerchantsForPeriod(any(), any(), any()) } returns emptyList()
        coEvery { expenseRepository.getLargestExpenseForPeriod(any(), any()) } returns null
    }

    @Test
    fun `leap year february calculations are correct`() {
        val feb29Noon = Instant.parse("2024-02-29T12:00:00Z").toEpochMilli()
        every { timeProvider.now() } returns feb29Noon
        val period = engine.getMonthPeriod(feb29Noon)

        assertEquals(2024, period.year)
        assertEquals("zero-based month for February", 1, period.month)

        // Month period spans exactly Feb 1 00:00 UTC (inclusive) to Mar 1 00:00 UTC (exclusive).
        assertEquals("start = 2024-02-01T00:00:00Z", 1_706_745_600_000L, period.startMs)
        assertEquals("end = 2024-03-01T00:00:00Z", 1_709_251_200_000L, period.endMs)
        assertEquals("leap February has 29 days", 29 * 86_400_000L, period.endMs - period.startMs)
    }

    @Test
    fun `negative amounts are handled in buildDailyTotals`() = runBlocking {
        val expenses = listOf(
            makeExpense(merchant = "Refund", amount = -50.0, daysAgo = 0),
            makeExpense(merchant = "Purchase", amount = 100.0, daysAgo = 0)
        )

        val totals = engine.buildDailyTotals(expenses.toExpenseSnapshots(), 1)

        val totalSpent = totals.values.sum()
        assertMoneyEquals(50.0, totalSpent, "refund -50 plus purchase 100")
    }

    @Test
    fun `very large amounts do not overflow`() = runBlocking {
        val largeAmount = Double.MAX_VALUE / 10
        val expenses = listOf(
            makeExpense(merchant = "BigPurchase", amount = largeAmount, daysAgo = 1)
        )

        val totals = engine.buildDailyTotals(expenses.toExpenseSnapshots(), 7)
        assertNotNull("Large amounts should not cause crash", totals)
    }

    @Test
    fun `anomaly detection skips merchants with zero historical average`() = runBlocking {
        val now = fixedNow
        val expense = Expense(
            id = 99L,
            amount = 120.0,
            currency = "EUR",
            merchant = "Zero Avg Merchant",
            transactionType = TransactionType.PURCHASE,
            date = now - 86_400_000L
        )
        val stats = MerchantStats(
            merchantName = "zero_avg_merchant",
            displayName = "Zero Avg Merchant",
            totalAmount = 0.0,
            transactionCount = 5,
            averageAmount = 0.0,
            minAmount = 0.0,
            maxAmount = 0.0,
            firstDate = now - 10 * 86_400_000L,
            lastDate = now - 2 * 86_400_000L
        )
        val topCurrent = stats.copy(maxAmount = 120.0)

        coEvery { expenseRepository.getMerchantStats() } returns listOf(stats)
        coEvery { expenseRepository.getTopMerchantsForPeriod(any(), any(), any()) } returns listOf(topCurrent)
        coEvery { expenseRepository.getLargestExpenseForMerchant(any(), any(), any()) } returns expense

        val snapshot = engine.generateInsights(
            categories = listOf(Category(id = 1L, name = "Food", icon = "food", color = "#FFFFFF")).toAnalyticsCategoryRefs(),
            allExpenses = listOf(expense).toExpenseSnapshots(),
            displayCurrency = "EUR"
        )
        assertTrue("Zero-average merchants must not generate divide-by-zero anomalies", snapshot.anomalies.isEmpty())
    }

    // ── fix-1.0.4 (owner-approved revalidation fix, item 143): calendar-aligned previous comparison window ──

    private fun utc(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Suppress("DEPRECATION")
    private fun previousWindowOf(startIso: String, endIso: String): MonthPeriod = runBlocking {
        engine.generateInsights(
            periodRange = com.yourname.expensetracker.domain.model.PeriodRange(utc(startIso), utc(endIso)),
            categories = emptyList(),
            allExpenses = emptyList(),
            displayCurrency = "EUR"
        ).monthlyComparison.previousMonth!!
    }

    @Test
    fun `march window compares with the whole of february`() {
        val previous = previousWindowOf("2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z")

        // February 2026 has 28 days; an equal-duration (31 day) window would start on 2026-01-29.
        assertEquals(utc("2026-02-01T00:00:00Z"), previous.startMs)
        assertEquals(utc("2026-03-01T00:00:00Z"), previous.endMs)
        assertEquals(2026, previous.year)
        assertEquals("zero-based February", 1, previous.month)
    }

    @Test
    fun `march window in a leap year compares with february 29 days`() {
        val previous = previousWindowOf("2024-03-01T00:00:00Z", "2024-04-01T00:00:00Z")

        assertEquals(utc("2024-02-01T00:00:00Z"), previous.startMs)
        assertEquals(utc("2024-03-01T00:00:00Z"), previous.endMs)
        assertEquals(29 * 86_400_000L, previous.endMs - previous.startMs)
    }

    @Test
    fun `january window compares with the previous december`() {
        val previous = previousWindowOf("2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z")

        assertEquals(utc("2025-12-01T00:00:00Z"), previous.startMs)
        assertEquals(utc("2026-01-01T00:00:00Z"), previous.endMs)
        assertEquals(2025, previous.year)
        assertEquals("zero-based December", 11, previous.month)
    }

    @Test
    fun `two month aligned window compares with the prior two calendar months`() {
        val previous = previousWindowOf("2026-03-01T00:00:00Z", "2026-05-01T00:00:00Z")

        assertEquals(utc("2026-01-01T00:00:00Z"), previous.startMs)
        assertEquals(utc("2026-03-01T00:00:00Z"), previous.endMs)
    }

    @Test
    fun `non aligned window falls back to the equal duration window`() {
        // 15 days mid-month: previous is the 15 days before it.
        val midMonth = previousWindowOf("2026-03-05T00:00:00Z", "2026-03-20T00:00:00Z")
        assertEquals(utc("2026-02-18T00:00:00Z"), midMonth.startMs)
        assertEquals(utc("2026-03-05T00:00:00Z"), midMonth.endMs)

        // Starts on a month start but ends mid-month: not whole calendar months.
        val partialMonth = previousWindowOf("2026-03-01T00:00:00Z", "2026-03-16T00:00:00Z")
        assertEquals(utc("2026-02-14T00:00:00Z"), partialMonth.startMs)
        assertEquals(utc("2026-03-01T00:00:00Z"), partialMonth.endMs)

        // Month start to a day past the next month start: 32 days, equal-duration fallback.
        val overshoot = previousWindowOf("2026-03-01T00:00:00Z", "2026-04-02T00:00:00Z")
        assertEquals(utc("2026-01-28T00:00:00Z"), overshoot.startMs)
        assertEquals(utc("2026-03-01T00:00:00Z"), overshoot.endMs)
    }

    private fun makeExpense(merchant: String, amount: Double, daysAgo: Int) = Expense(
        id = nextExpenseId++,
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = fixedNow - daysAgo * 86_400_000L
    )
}
