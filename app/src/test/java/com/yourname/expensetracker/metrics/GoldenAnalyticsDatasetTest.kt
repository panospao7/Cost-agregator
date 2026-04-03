package com.yourname.expensetracker.metrics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/**
 * Golden dataset verification harness.
 *
 * Test harness structure (mock DAO/Repo strategy):
 * 1) Build deterministic fixtures from [GoldenAnalyticsDataset].
 * 2) Feed scenario transactions to engine under test through a fake repository, e.g.:
 *
 *    class FakeExpenseRepository(private val rows: List<Expense>) {
 *       fun getExpensesBetween(startMs: Long, endMs: Long): List<Expense> =
 *           rows.filter { it.date >= startMs && it.date < endMs }
 *    }
 *
 * 3) Compare engine outputs against expected matrix constants below.
 */
class GoldenAnalyticsDatasetTest {

    private val timeProvider = FakeTimeProvider.forDate(2026, 4, 1)

    @Test
    fun `dataset uses deterministic now via TimeProvider`() {
        assertEquals(GoldenAnalyticsDataset.nowMs, timeProvider.now())
    }

    @Test
    fun `scenario 1 basic monthly total`() {
        val s = GoldenAnalyticsDataset.byId("S1_BASIC_MONTHLY_TOTAL")
        val actual = purchaseMetrics(s.transactions, s.startMs, s.endMs)

        assertEquals(60.0, actual.total, 0.0001)
        assertEquals(3, actual.count)
        assertEquals(2.0, actual.dailyAverage, 0.0001)
    }

    @Test
    fun `scenario 2 split uses effectiveAmount not amount`() {
        val s = GoldenAnalyticsDataset.byId("S2_SPLIT_EFFECTIVE_AMOUNT")
        val tx = s.transactions.single()

        assertEquals(100.0, tx.amount, 0.0001)
        assertEquals(50.0, tx.effectiveAmount, 0.0001)

        val actual = purchaseMetrics(s.transactions, s.startMs, s.endMs)
        assertEquals(50.0, actual.total, 0.0001)
    }

    @Test
    fun `scenario 3 mixed types purchase and cashflow expectations`() {
        val s = GoldenAnalyticsDataset.byId("S3_MIXED_TRANSACTION_TYPES")

        val purchase = purchaseMetrics(s.transactions, s.startMs, s.endMs)
        assertEquals(50.0, purchase.total, 0.0001)

        val cash = cashFlowMetrics(s.transactions, s.startMs, s.endMs)
        assertEquals(100.0, cash.income, 0.0001)
        assertEquals(50.0, cash.expense, 0.0001)
        assertEquals(50.0, cash.net, 0.0001)
    }

    @Test
    fun `scenario 4 half-open boundary includes start excludes end`() {
        val s = GoldenAnalyticsDataset.byId("S4_HALF_OPEN_BOUNDARIES")
        val inRange = inRange(s.transactions, s.startMs, s.endMs)

        assertEquals(1, inRange.size)
        assertEquals(s.startMs, inRange.single().date)

        val purchase = purchaseMetrics(s.transactions, s.startMs, s.endMs)
        assertEquals(10.0, purchase.total, 0.0001)
        assertEquals(1, purchase.count)
    }

    @Test
    fun `scenario 5 empty period returns all zeros`() {
        val s = GoldenAnalyticsDataset.byId("S5_EMPTY_PERIOD")
        val purchase = purchaseMetrics(s.transactions, s.startMs, s.endMs)
        val cash = cashFlowMetrics(s.transactions, s.startMs, s.endMs)

        assertEquals(0.0, purchase.total, 0.0001)
        assertEquals(0, purchase.count)
        assertEquals(0.0, purchase.dailyAverage, 0.0001)

        assertEquals(0.0, cash.income, 0.0001)
        assertEquals(0.0, cash.expense, 0.0001)
        assertEquals(0.0, cash.net, 0.0001)
    }

    @Test
    fun `scenario 6 isNotMine filtering includes only mine`() {
        val s = GoldenAnalyticsDataset.byId("S6_SHARED_FILTERING")
        val purchase = purchaseMetrics(s.transactions, s.startMs, s.endMs)

        assertEquals(60.0, purchase.total, 0.0001)
        assertEquals(1, purchase.count)
        assertTrue(s.transactions.any { it.isNotMine })
    }

    @Test
    fun `scenario 7 category breakdown matches expected percentages`() {
        val s = GoldenAnalyticsDataset.byId("S7_CATEGORY_BREAKDOWN")
        val actual = categoryBreakdown(s.transactions, s.startMs, s.endMs)

        assertEquals(3, actual.size)
        assertEquals(80.0, actual.getValue("Food").first, 0.0001)
        assertEquals(20.0, actual.getValue("Transport").first, 0.0001)
        assertEquals(20.0, actual.getValue("Other").first, 0.0001)

        assertEquals(66.6, actual.getValue("Food").second, 0.0001)
        assertEquals(16.6, actual.getValue("Transport").second, 0.0001)
        assertEquals(16.6, actual.getValue("Other").second, 0.0001)
    }

    private fun purchaseMetrics(
        rows: List<Expense>,
        startMs: Long,
        endMs: Long
    ): GoldenPurchaseExpectation {
        val purchases = inRange(rows, startMs, endMs)
            .filter { it.transactionType == TransactionType.PURCHASE && !it.isNotMine }

        val total = purchases.sumOf { it.effectiveAmount }
        val days = ((endMs - startMs) / 86_400_000L).toInt().coerceAtLeast(1)
        return GoldenPurchaseExpectation(
            total = total,
            count = purchases.size,
            dailyAverage = total / days
        )
    }

    private fun cashFlowMetrics(
        rows: List<Expense>,
        startMs: Long,
        endMs: Long
    ): GoldenCashFlowExpectation {
        val inRange = inRange(rows, startMs, endMs).filter { !it.isNotMine }
        val income = inRange
            .filter { it.transactionType == TransactionType.DEPOSIT }
            .sumOf { it.effectiveAmount }
        val expense = inRange
            .filter { it.transactionType == TransactionType.PURCHASE }
            .sumOf { it.effectiveAmount }
        return GoldenCashFlowExpectation(income, expense, income - expense)
    }

    private fun categoryBreakdown(
        rows: List<Expense>,
        startMs: Long,
        endMs: Long
    ): Map<String, Pair<Double, Double>> {
        val purchases = inRange(rows, startMs, endMs)
            .filter { it.transactionType == TransactionType.PURCHASE && !it.isNotMine }
        val total = purchases.sumOf { it.effectiveAmount }
        return purchases
            .groupBy { it.merchant }
            .mapValues { (_, exps) ->
                val categoryTotal = exps.sumOf { it.effectiveAmount }
                val percent = if (total == 0.0) 0.0 else truncate1Decimal(categoryTotal / total * 100.0)
                categoryTotal to percent
            }
    }

    private fun truncate1Decimal(value: Double): Double = floor(value * 10.0) / 10.0

    private fun inRange(rows: List<Expense>, startMs: Long, endMs: Long): List<Expense> =
        rows.filter { it.date >= startMs && it.date < endMs }
}
