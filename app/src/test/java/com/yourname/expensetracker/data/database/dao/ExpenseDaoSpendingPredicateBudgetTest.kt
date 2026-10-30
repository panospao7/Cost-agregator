package com.yourname.expensetracker.data.database.dao

import android.content.Context
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.dao.ExpenseDaoSpendingPredicateFixture.Companion.day
import com.yourname.expensetracker.data.database.dao.ExpenseDaoSpendingPredicateFixture.Companion.monthStart
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Loan-installments S4a (plan amendment A12.2/A12.5): the 15 budget and multi-currency-hub
 * aggregate queries now use the canonical spending predicate.
 *
 * For every converted query:
 *  1. neutrality: the verbatim pre-edit SQL text, run through the raw database handle,
 *     returns exactly what the converted DAO method returns while every flag is NULL
 *     (isNotMine x share x type x currency x date-bucket fixture);
 *  2. flagged case: with INSTALLMENT_PURCHASE and INSTALLMENT_REVERSED rows flagged through
 *     the coordinator, the DAO result equals the pre-edit SQL over the same data without
 *     those rows, and the row count drops by exactly the flagged rows.
 *
 * Plus the aggregate parts of AE-1, AE-6 and AE-24. The deprecated-ERROR DAO methods are
 * called on purpose (they have live callers), hence the class-level suppression.
 */
@Suppress("DEPRECATION_ERROR")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExpenseDaoSpendingPredicateBudgetTest {

    private val fixtures = mutableListOf<ExpenseDaoSpendingPredicateFixture>()

    @Before
    fun setUp() {
        fixtures.clear()
    }

    @After
    fun tearDown() {
        fixtures.forEach { it.close() }
        fixtures.clear()
    }

    private suspend fun newFixture(): ExpenseDaoSpendingPredicateFixture {
        val context: Context = ApplicationProvider.getApplicationContext()
        return ExpenseDaoSpendingPredicateFixture.create(context).also { fixtures += it }
    }

    // ── Query registry ──────────────────────────────────────────────────────

    private class QueryCase(
        val name: String,
        /** Verbatim pre-edit SQL text (named parameters are bound as literals). */
        val preEditSql: String,
        val params: Map<String, Any?>,
        /** Index of the total column in a result row. */
        val totalIdx: Int,
        /** Index of the txCount column, or null when the query returns no count. */
        val countIdx: Int?,
        /** False when the flagged fixture rows are outside this query's row filter. */
        val flaggedRowsApply: Boolean = true,
        val run: suspend (ExpenseDao) -> List<List<Any?>>
    )

    private val start = monthStart(2027, 1)
    private val end = monthStart(2028, 6)

    private fun cases(f: ExpenseDaoSpendingPredicateFixture): List<QueryCase> {
        val c1 = f.cat1
        val c2 = f.cat2
        val window = mapOf<String, Any?>("startDate" to start, "endDate" to end)
        val periodWindow = mapOf<String, Any?>("startMs" to start, "endMs" to end)
        return listOf(
            QueryCase(
                "getTotalSpentBetweenByCurrency",
                """
        SELECT UPPER(currency) AS currency,
               SUM(${EA}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${ST}
          AND date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY UPPER(currency)
        ORDER BY total DESC
    """,
                window, totalIdx = 1, countIdx = 2
            ) { d -> d.getTotalSpentBetweenByCurrency(start, end).map { listOf(it.currency, it.total, it.txCount) } },
            QueryCase(
                "getCategoryTotalsBetweenByCurrency",
                """
        SELECT categoryId,
               UPPER(currency) AS currency,
               SUM(${EA}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${ST}
          AND date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY categoryId, UPPER(currency)
        ORDER BY total DESC
    """,
                window, totalIdx = 2, countIdx = 3
            ) { d ->
                d.getCategoryTotalsBetweenByCurrency(start, end)
                    .map { listOf(it.categoryId, it.currency, it.total, it.txCount) }
            },
            QueryCase(
                "getMerchantTotalsBetweenByCurrency",
                """
        SELECT MIN(merchant) AS merchant,
               UPPER(currency) AS currency,
               SUM(${EA}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${ST}
          AND date >= :startDate AND date < :endDate
          AND isNotMine = 0
          AND merchantKey IS NOT NULL
        GROUP BY merchantKey, UPPER(currency)
        ORDER BY total DESC
    """,
                window, totalIdx = 2, countIdx = 3
            ) { d ->
                d.getMerchantTotalsBetweenByCurrency(start, end)
                    .map { listOf(it.merchant, it.currency, it.total, it.txCount) }
            },
            QueryCase(
                "getMonthlyTotalsBetweenByCurrency",
                """
        SELECT strftime('%Y-%m', date/1000, 'unixepoch', 'localtime') AS monthKey,
               UPPER(currency) AS currency,
               SUM(${EA}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${ST}
          AND date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY monthKey, UPPER(currency)
        ORDER BY monthKey ASC, total DESC
    """,
                window, totalIdx = 2, countIdx = 3
            ) { d ->
                d.getMonthlyTotalsBetweenByCurrency(start, end)
                    .map { listOf(it.monthKey, it.currency, it.total, it.txCount) }
            },
            QueryCase(
                "getAllSpentBetweenByCurrency",
                """
        SELECT UPPER(currency) AS currency,
               SUM(${EA}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY UPPER(currency)
        ORDER BY total DESC
    """,
                window, totalIdx = 1, countIdx = 2
            ) { d -> d.getAllSpentBetweenByCurrency(start, end).map { listOf(it.currency, it.total, it.txCount) } },
            QueryCase(
                "getAllCategoryTotalsBetweenByCurrency",
                """
        SELECT categoryId,
               UPPER(currency) AS currency,
               SUM(${EA}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY categoryId, UPPER(currency)
        ORDER BY total DESC
    """,
                window, totalIdx = 2, countIdx = 3
            ) { d ->
                d.getAllCategoryTotalsBetweenByCurrency(start, end)
                    .map { listOf(it.categoryId, it.currency, it.total, it.txCount) }
            },
            QueryCase(
                "getAllMerchantTotalsBetweenByCurrency",
                """
        SELECT merchant AS merchant,
               UPPER(currency) AS currency,
               SUM(${EA}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY merchant, UPPER(currency)
        ORDER BY total DESC
    """,
                window, totalIdx = 2, countIdx = 3
            ) { d ->
                d.getAllMerchantTotalsBetweenByCurrency(start, end)
                    .map { listOf(it.merchant, it.currency, it.total, it.txCount) }
            },
            QueryCase(
                "getAllMonthlyTotalsBetweenByCurrency",
                """
        SELECT strftime('%Y-%m', date/1000, 'unixepoch', 'localtime') AS monthKey,
               UPPER(currency) AS currency,
               SUM(${EA}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY monthKey, UPPER(currency)
        ORDER BY monthKey ASC, total DESC
    """,
                window, totalIdx = 2, countIdx = 3
            ) { d ->
                d.getAllMonthlyTotalsBetweenByCurrency(start, end)
                    .map { listOf(it.monthKey, it.currency, it.total, it.txCount) }
            },
            QueryCase(
                "getCategorySpentInPeriod",
                """
        SELECT COALESCE(SUM(${EA}), 0.0) FROM expenses
        WHERE ${ST}
        AND categoryId = :categoryId
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        """,
                periodWindow + ("categoryId" to c1), totalIdx = 0, countIdx = null
            ) { d -> listOf(listOf(d.getCategorySpentInPeriod(c1, start, end))) },
            QueryCase(
                "getCategorySpentInPeriodFlow",
                """
        SELECT COALESCE(SUM(${EA}), 0.0) FROM expenses
        WHERE ${ST}
        AND categoryId = :categoryId
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        """,
                periodWindow + ("categoryId" to c1), totalIdx = 0, countIdx = null
            ) { d -> listOf(listOf(d.getCategorySpentInPeriodFlow(c1, start, end).first())) },
            QueryCase(
                "getCategorySpentTotalsInPeriod",
                """
        SELECT categoryId, COALESCE(SUM(${EA}), 0.0) AS total
        FROM expenses
        WHERE ${ST}
          AND categoryId IN (:categoryIds)
          AND date >= :startMs AND date < :endMs
          AND isNotMine = 0
        GROUP BY categoryId
    """,
                periodWindow + ("categoryIds" to listOf(c1, c2)), totalIdx = 1, countIdx = null
            ) { d ->
                d.getCategorySpentTotalsInPeriod(listOf(c1, c2), start, end).map { listOf(it.categoryId, it.total) }
            },
            QueryCase(
                "getEffectiveSpentBetweenForCategory(category)",
                """
        SELECT COALESCE(SUM(${EA}), 0.0) FROM expenses
        WHERE date >= :startDate AND date < :endDate
          AND isNotMine = 0
          AND ((:categoryId IS NULL AND categoryId IS NULL) OR categoryId = :categoryId)
    """,
                window + ("categoryId" to c1), totalIdx = 0, countIdx = null
            ) { d -> listOf(listOf(d.getEffectiveSpentBetweenForCategory(start, end, c1))) },
            QueryCase(
                "getEffectiveSpentBetweenForCategory(uncategorized)",
                """
        SELECT COALESCE(SUM(${EA}), 0.0) FROM expenses
        WHERE date >= :startDate AND date < :endDate
          AND isNotMine = 0
          AND ((:categoryId IS NULL AND categoryId IS NULL) OR categoryId = :categoryId)
    """,
                window + ("categoryId" to null), totalIdx = 0, countIdx = null, flaggedRowsApply = false
            ) { d -> listOf(listOf(d.getEffectiveSpentBetweenForCategory(start, end, null))) },
            QueryCase(
                "getMonthlySpendingTotals",
                """
        SELECT strftime('%Y-%m', date/1000, 'unixepoch', 'localtime') AS monthKey,
               SUM(${EA}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${ST}
          AND isNotMine = 0
        GROUP BY monthKey
        ORDER BY monthKey ASC
    """,
                emptyMap(), totalIdx = 1, countIdx = 2
            ) { d -> d.getMonthlySpendingTotals().map { listOf(it.monthKey, it.total, it.txCount) } },
            QueryCase(
                "getMonthlySpendingTotalsBetween",
                """
        SELECT strftime('%Y-%m', date/1000, 'unixepoch', 'localtime') AS monthKey,
               SUM(${EA}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${ST}
          AND date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY monthKey
        ORDER BY monthKey ASC
    """,
                window, totalIdx = 1, countIdx = 2
            ) { d -> d.getMonthlySpendingTotalsBetween(start, end).map { listOf(it.monthKey, it.total, it.txCount) } },
            QueryCase(
                "getMonthlySpendingTotalsByCategoryBetween",
                """
        SELECT strftime('%Y-%m', date/1000, 'unixepoch', 'localtime') AS monthKey,
               SUM(${EA}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${ST}
          AND categoryId = :categoryId
          AND date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY monthKey
        ORDER BY monthKey ASC
    """,
                window + ("categoryId" to c1), totalIdx = 1, countIdx = 2
            ) { d ->
                d.getMonthlySpendingTotalsByCategoryBetween(c1, start, end)
                    .map { listOf(it.monthKey, it.total, it.txCount) }
            }
        )
    }

    // ── Seeding ─────────────────────────────────────────────────────────────

    /**
     * NULL-flag data: isNotMine x share kind x transaction type x currency x date bucket, with
     * rotating categories (including none) and merchants. Buckets cover before-window,
     * inside-window (three months), exactly-at-the-exclusive-end, and after-window.
     */
    private suspend fun seedBase(f: ExpenseDaoSpendingPredicateFixture) {
        val buckets = listOf(
            day(2026, 12, 15),
            day(2027, 2, 15),
            day(2027, 8, 15),
            day(2028, 4, 15),
            end,
            day(2028, 7, 15)
        )
        var idx = 0
        for (notMine in listOf(false, true)) {
            for (share in 0..2) {
                for (type in listOf(TransactionType.PURCHASE, TransactionType.DEPOSIT, TransactionType.TRANSFER)) {
                    for (currency in listOf("EUR", "USD")) {
                        for (date in buckets) {
                            idx++
                            val amount = 10.0 + idx * 1.25
                            f.insert(
                                amount = amount,
                                date = date,
                                type = type,
                                currency = currency,
                                merchant = "Merch${idx % 4}",
                                categoryId = listOf<Long?>(f.cat1, f.cat2, null)[idx % 3],
                                isNotMine = notMine,
                                isShared = share != 0,
                                sharePct = if (share == 1) 50 else null,
                                shareAmount = if (share == 2) amount * 0.4 else null
                            )
                        }
                    }
                }
            }
        }
    }

    private class Flagged(val amount: Double, val date: Long, val reason: SpendingExclusionReason)

    private val flaggedRows = listOf(
        Flagged(600.0, day(2027, 3, 10), SpendingExclusionReason.INSTALLMENT_PURCHASE),
        Flagged(300.0, day(2027, 5, 10), SpendingExclusionReason.INSTALLMENT_PURCHASE),
        Flagged(250.0, day(2027, 6, 10), SpendingExclusionReason.INSTALLMENT_REVERSED)
    )

    /** Seeds [flaggedRows] as plain PURCHASE rows, then flags each through the coordinator. */
    private suspend fun seedFlaggedRows(f: ExpenseDaoSpendingPredicateFixture) {
        for (row in flaggedRows) {
            val id = f.insert(amount = row.amount, date = row.date, merchant = "Flagged Shop", categoryId = f.cat1)
            f.flag(id, row.reason)
        }
    }

    // ── Raw SQL execution and comparison ────────────────────────────────────

    private fun runRaw(f: ExpenseDaoSpendingPredicateFixture, sql: String, params: Map<String, Any?>): List<List<Any?>> {
        val literal = Regex(":(\\w+)").replace(sql) { m ->
            require(params.containsKey(m.groupValues[1])) { "unbound parameter ${m.value}" }
            when (val v = params[m.groupValues[1]]) {
                null -> "NULL"
                is Iterable<*> -> v.joinToString(",")
                else -> v.toString()
            }
        }
        f.raw.query(literal).use { cursor ->
            val rows = mutableListOf<List<Any?>>()
            while (cursor.moveToNext()) {
                rows += (0 until cursor.columnCount).map { i ->
                    when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                        else -> cursor.getString(i)
                    }
                }
            }
            return rows
        }
    }

    private fun normalized(rows: List<List<Any?>>): List<String> =
        rows.map { row ->
            row.joinToString("|") { v ->
                when (v) {
                    null -> "null"
                    is Number -> String.format(java.util.Locale.ROOT, "%.6f", v.toDouble())
                    else -> v.toString()
                }
            }
        }.sorted()

    private fun sumColumn(rows: List<List<Any?>>, idx: Int): Double =
        rows.sumOf { (it[idx] as Number).toDouble() }

    // ── (1) Neutrality: NULL flags => converted == verbatim pre-edit SQL ────

    @Test
    fun `S4a queries are neutral over a NULL-flag fixture`() = runBlocking {
        val f = newFixture()
        seedBase(f)
        val failures = mutableListOf<String>()
        for (case in cases(f)) {
            val expected = normalized(runRaw(f, case.preEditSql, case.params))
            val actual = normalized(case.run(f.dao))
            if (expected != actual) failures += "${case.name}: expected=$expected actual=$actual"
            // Guard against a vacuous fixture: every query must see at least one row.
            if (expected.isEmpty()) failures += "${case.name}: neutrality fixture produced no rows"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ── (2) Flagged case ────────────────────────────────────────────────────

    @Test
    fun `S4a queries drop INSTALLMENT_PURCHASE and INSTALLMENT_REVERSED rows and only those`() = runBlocking {
        val full = newFixture()
        seedBase(full)
        seedFlaggedRows(full)
        // The reference database holds exactly the same data minus the flagged rows.
        val reference = newFixture()
        seedBase(reference)

        val flaggedAmount = flaggedRows.sumOf { it.amount }
        val failures = mutableListOf<String>()
        for ((fullCase, refCase) in cases(full).zip(cases(reference))) {
            val converted = fullCase.run(full.dao)
            val preEditOnFull = runRaw(full, fullCase.preEditSql, fullCase.params)
            val preEditOnReference = runRaw(reference, refCase.preEditSql, refCase.params)

            if (normalized(converted) != normalized(preEditOnReference)) {
                failures += "${fullCase.name}: converted != reference without flagged rows " +
                    "(converted=${normalized(converted)} reference=${normalized(preEditOnReference)})"
            }
            val expectedAmountDrop = if (fullCase.flaggedRowsApply) flaggedAmount else 0.0
            val amountDrop = sumColumn(preEditOnFull, fullCase.totalIdx) - sumColumn(converted, fullCase.totalIdx)
            if (abs(amountDrop - expectedAmountDrop) > 1e-6) {
                failures += "${fullCase.name}: total dropped by $amountDrop, expected $expectedAmountDrop"
            }
            fullCase.countIdx?.let { idx ->
                val expectedCountDrop = if (fullCase.flaggedRowsApply) flaggedRows.size.toDouble() else 0.0
                val countDrop = sumColumn(preEditOnFull, idx) - sumColumn(converted, idx)
                if (abs(countDrop - expectedCountDrop) > 1e-9) {
                    failures += "${fullCase.name}: COUNT dropped by $countDrop, expected $expectedCountDrop"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ── AE-1 / AE-6 / AE-24 ─────────────────────────────────────────────────

    /** A 600.00 purchase flagged in 2027-03 plus twelve 50.00 installment expenses 2027-04..2028-03. */
    private suspend fun seedInstallmentScenario(f: ExpenseDaoSpendingPredicateFixture) {
        val purchaseId = f.insert(amount = 600.0, date = day(2027, 3, 10), merchant = "Electronics Shop")
        f.flag(purchaseId, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        var year = 2027
        var month = 4
        repeat(12) {
            f.insert(amount = 50.0, date = day(year, month, 15), merchant = "Electronics Shop")
            if (month == 12) {
                month = 1
                year++
            } else {
                month++
            }
        }
    }

    private val scenarioMonths = listOf(
        "2027-04", "2027-05", "2027-06", "2027-07", "2027-08", "2027-09",
        "2027-10", "2027-11", "2027-12", "2028-01", "2028-02", "2028-03"
    )

    private fun monthMap(rows: List<Pair<String, Double>>): Map<String, Double> =
        rows.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sum() }

    @Test
    fun `AE-1 aggregate - purchase month is 0_00 and each installment month is 50_00`() = runBlocking {
        val f = newFixture()
        seedInstallmentScenario(f)
        val rangeStart = monthStart(2027, 3)
        val rangeEnd = monthStart(2028, 4)

        val perQuery = mapOf(
            "getAllMonthlyTotalsBetweenByCurrency" to
                monthMap(f.dao.getAllMonthlyTotalsBetweenByCurrency(rangeStart, rangeEnd).map { it.monthKey to it.total }),
            "getMonthlyTotalsBetweenByCurrency" to
                monthMap(f.dao.getMonthlyTotalsBetweenByCurrency(rangeStart, rangeEnd).map { it.monthKey to it.total }),
            "getMonthlySpendingTotalsBetween" to
                monthMap(f.dao.getMonthlySpendingTotalsBetween(rangeStart, rangeEnd).map { it.monthKey to it.total }),
            "getMonthlySpendingTotals" to
                monthMap(f.dao.getMonthlySpendingTotals().map { it.monthKey to it.total }),
            "getMonthlySpendingTotalsByCategoryBetween" to
                monthMap(
                    f.dao.getMonthlySpendingTotalsByCategoryBetween(f.cat1, rangeStart, rangeEnd)
                        .map { it.monthKey to it.total }
                )
        )
        for ((name, months) in perQuery) {
            assertEquals("$name 2027-03", 0.0, months["2027-03"] ?: 0.0, 1e-9)
            for (m in scenarioMonths) {
                assertEquals("$name $m", 50.0, months[m] ?: Double.NaN, 1e-9)
            }
        }
        // Whole-range totals: twelve installments, the 600.00 purchase record counts nothing.
        val total = f.dao.getTotalSpentBetweenByCurrency(rangeStart, rangeEnd).single()
        assertEquals("EUR", total.currency)
        assertEquals(600.0, total.total, 1e-9)
        assertEquals(12, total.txCount)
        val allTotal = f.dao.getAllSpentBetweenByCurrency(rangeStart, rangeEnd).single()
        assertEquals(600.0, allTotal.total, 1e-9)
        assertEquals(12, allTotal.txCount)
    }

    @Test
    fun `AE-6 - no month ever shows the full purchase amount`() = runBlocking {
        val f = newFixture()
        seedInstallmentScenario(f)
        val rangeStart = monthStart(2027, 3)
        val rangeEnd = monthStart(2028, 4)

        val allMonthTotals = buildList {
            addAll(f.dao.getAllMonthlyTotalsBetweenByCurrency(rangeStart, rangeEnd).map { it.monthKey to it.total })
            addAll(f.dao.getMonthlyTotalsBetweenByCurrency(rangeStart, rangeEnd).map { it.monthKey to it.total })
            addAll(f.dao.getMonthlySpendingTotalsBetween(rangeStart, rangeEnd).map { it.monthKey to it.total })
            addAll(f.dao.getMonthlySpendingTotals().map { it.monthKey to it.total })
            addAll(
                f.dao.getMonthlySpendingTotalsByCategoryBetween(f.cat1, rangeStart, rangeEnd)
                    .map { it.monthKey to it.total }
            )
        }
        assertTrue(allMonthTotals.isNotEmpty())
        for ((month, total) in allMonthTotals) {
            assertTrue("month $month shows $total", abs(total - 600.0) > 1e-9 && abs(total - 650.0) > 1e-9)
        }
        // Category budget view of the purchase month: nothing counted.
        val marchStart = monthStart(2027, 3)
        val marchEnd = monthStart(2027, 4)
        assertEquals(0.0, f.dao.getCategorySpentInPeriod(f.cat1, marchStart, marchEnd), 1e-9)
        assertEquals(0.0, f.dao.getCategorySpentInPeriodFlow(f.cat1, marchStart, marchEnd).first(), 1e-9)
        assertEquals(0.0, f.dao.getEffectiveSpentBetweenForCategory(marchStart, marchEnd, f.cat1), 1e-9)
        assertTrue(f.dao.getCategorySpentTotalsInPeriod(listOf(f.cat1), marchStart, marchEnd).all { it.total == 0.0 })
    }

    @Test
    fun `AE-24 - Electronics April is 110_00 and March excludes the 600_00 purchase`() = runBlocking {
        val f = newFixture()
        seedInstallmentScenario(f)
        // 60.00 of ordinary Electronics spending in April next to the 50.00 installment.
        f.insert(amount = 60.0, date = day(2027, 4, 5), merchant = "Cable Shop")

        val marchStart = monthStart(2027, 3)
        val aprilStart = monthStart(2027, 4)
        val mayStart = monthStart(2027, 5)

        assertEquals(110.0, f.dao.getCategorySpentInPeriod(f.cat1, aprilStart, mayStart), 1e-9)
        assertEquals(110.0, f.dao.getCategorySpentInPeriodFlow(f.cat1, aprilStart, mayStart).first(), 1e-9)
        assertEquals(110.0, f.dao.getEffectiveSpentBetweenForCategory(aprilStart, mayStart, f.cat1), 1e-9)
        assertEquals(
            110.0,
            f.dao.getCategorySpentTotalsInPeriod(listOf(f.cat1), aprilStart, mayStart).single().total,
            1e-9
        )
        val monthly = f.dao.getMonthlySpendingTotalsByCategoryBetween(f.cat1, marchStart, mayStart)
        assertEquals(110.0, monthly.single { it.monthKey == "2027-04" }.total, 1e-9)
        assertTrue("March must not show the 600.00 purchase", monthly.none { it.monthKey == "2027-03" && it.total != 0.0 })

        // March alone: the flagged 600.00 purchase is the only row and it counts nothing.
        assertEquals(0.0, f.dao.getCategorySpentInPeriod(f.cat1, marchStart, aprilStart), 1e-9)
        assertEquals(0.0, f.dao.getEffectiveSpentBetweenForCategory(marchStart, aprilStart, f.cat1), 1e-9)
        assertTrue(f.dao.getCategoryTotalsBetweenByCurrency(marchStart, aprilStart).none { it.total != 0.0 })
        assertTrue(f.dao.getAllCategoryTotalsBetweenByCurrency(marchStart, aprilStart).none { it.total != 0.0 })
        assertTrue(f.dao.getTotalSpentBetweenByCurrency(marchStart, aprilStart).none { it.total != 0.0 })
        assertTrue(f.dao.getMerchantTotalsBetweenByCurrency(marchStart, aprilStart).none { it.total != 0.0 })
    }

    private companion object {
        /** Pre-edit effective-amount expression, verbatim from the DAO companion. */
        const val EA: String = ExpenseDao.EFFECTIVE_AMOUNT_SQL

        /** Pre-edit spending-type predicate, verbatim from the DAO companion. */
        const val ST: String = ExpenseDao.SPENDING_TYPE_SQL
    }
}
