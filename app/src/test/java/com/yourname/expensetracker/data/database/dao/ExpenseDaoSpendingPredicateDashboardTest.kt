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
 * Loan-installments S4b (plan amendment A12.2/A12.5): the 11 dashboard aggregate queries now use
 * the canonical spending predicate.
 *
 * For every converted query:
 *  1. neutrality: the verbatim pre-edit SQL text, run through the raw database handle, returns
 *     exactly what the converted DAO method returns while every flag is NULL
 *     (isNotMine x share x type x currency x date-bucket fixture);
 *  2. flagged case: with INSTALLMENT_PURCHASE and INSTALLMENT_REVERSED rows flagged through the
 *     coordinator, the DAO result equals the pre-edit SQL over the same data without those rows,
 *     and the row count drops by exactly the flagged rows.
 *
 * Plus the dashboard parts of AE-1 (getTotalForPeriod), AE-6 (weekly, monthly, daily), the AE-17
 * control (two unflagged 50.00 rows in June count 100.00) and getPurchaseCount. The deprecated-ERROR
 * DAO methods are called on purpose (they have live callers), hence the class-level suppression.
 */
@Suppress("DEPRECATION_ERROR")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExpenseDaoSpendingPredicateDashboardTest {

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
        /** Index of the total column in a result row, or null when the query returns no amount sum. */
        val totalIdx: Int?,
        /** Index of the count column, or null when the query returns no count. */
        val countIdx: Int?,
        val run: suspend (ExpenseDao) -> List<List<Any?>>
    )

    private val start = monthStart(2027, 1)
    private val end = monthStart(2028, 6)

    private fun cases(): List<QueryCase> {
        val window = mapOf<String, Any?>("startDate" to start, "endDate" to end)
        val periodWindow = mapOf<String, Any?>("startMs" to start, "endMs" to end)
        return listOf(
            QueryCase(
                "getTotalSpentFlow",
                "SELECT SUM(${EA}) FROM expenses WHERE ${ST} AND isNotMine = 0",
                emptyMap(), totalIdx = 0, countIdx = null
            ) { d -> listOf(listOf(d.getTotalSpentFlow().first())) },
            QueryCase(
                "getTotalSpentBetween",
                """
        SELECT SUM(${EA}) FROM expenses
        WHERE ${ST}
        AND date >= :startDate AND date < :endDate
        AND isNotMine = 0
        """,
                window, totalIdx = 0, countIdx = null
            ) { d -> listOf(listOf(d.getTotalSpentBetween(start, end))) },
            QueryCase(
                "getCategoryTotalsBetween",
                """
        SELECT categoryId, SUM(${EA}) as total, COUNT(*) as txCount
        FROM expenses
        WHERE ${ST}
        AND date >= :startDate AND date < :endDate
        AND categoryId IS NOT NULL
        AND isNotMine = 0
        GROUP BY categoryId
        ORDER BY total DESC
    """,
                window, totalIdx = 1, countIdx = 2
            ) { d -> d.getCategoryTotalsBetween(start, end).map { listOf(it.categoryId, it.total, it.txCount) } },
            QueryCase(
                "getPurchaseCount",
                "SELECT COUNT(*) FROM expenses WHERE ${ST} AND isNotMine = 0",
                emptyMap(), totalIdx = null, countIdx = 0
            ) { d -> listOf(listOf(d.getPurchaseCount())) },
            QueryCase(
                "getSpendingDailyTotalsBetween",
                """
        SELECT CAST(strftime('%Y%m%d', date/1000, 'unixepoch', 'localtime') AS INTEGER) as dayEpoch,
               MIN(date) as startDate,
               MAX(date) as endDate,
               SUM(${EA}) as total,
               COUNT(*) as txCount
        FROM expenses
        WHERE ${ST}
          AND date >= :startMs AND date < :endMs
          AND isNotMine = 0
        GROUP BY dayEpoch
        ORDER BY dayEpoch ASC
        """,
                periodWindow, totalIdx = 3, countIdx = 4
            ) { d ->
                d.getSpendingDailyTotalsBetween(start, end)
                    .map { listOf(it.dayEpoch, it.startDate, it.endDate, it.total, it.txCount) }
            },
            QueryCase(
                "getTotalForPeriod",
                """
        SELECT COALESCE(SUM(${EA}), 0.0) FROM expenses
        WHERE ${ST}
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
    """,
                periodWindow, totalIdx = 0, countIdx = null
            ) { d -> listOf(listOf(d.getTotalForPeriod(start, end))) },
            QueryCase(
                "getWeeklyTotalsForPeriod",
                """
        SELECT date(date/1000, 'unixepoch', 'localtime', '-6 days', 'weekday 1') as weekKey,
               MIN(date) as startDate,
               MAX(date) as endDate,
               SUM(${EA}) as total,
               COUNT(*) as txCount
        FROM expenses
        WHERE ${ST}
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        GROUP BY weekKey
        ORDER BY weekKey ASC
    """,
                periodWindow, totalIdx = 3, countIdx = 4
            ) { d ->
                d.getWeeklyTotalsForPeriod(start, end)
                    .map { listOf(it.weekKey, it.startDate, it.endDate, it.total, it.txCount) }
            },
            QueryCase(
                "getMonthlyTotalsForPeriod",
                """
        SELECT strftime('%Y-%m', date/1000, 'unixepoch', 'localtime') as monthKey,
               MIN(date) as startDate,
               MAX(date) as endDate,
               SUM(${EA}) as total,
               COUNT(*) as txCount
        FROM expenses
        WHERE ${ST}
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        GROUP BY monthKey
        ORDER BY monthKey ASC
    """,
                periodWindow, totalIdx = 3, countIdx = 4
            ) { d ->
                d.getMonthlyTotalsForPeriod(start, end)
                    .map { listOf(it.monthKey, it.startDate, it.endDate, it.total, it.txCount) }
            },
            QueryCase(
                "getDailyTotalsWithDatesForPeriod",
                """
        SELECT CAST(strftime('%Y%m%d', date/1000, 'unixepoch', 'localtime') AS INTEGER) as dayEpoch,
               MIN(date) as startDate,
               MAX(date) as endDate,
               SUM(${EA}) as total,
               COUNT(*) as txCount
        FROM expenses
        WHERE ${ST}
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        GROUP BY dayEpoch
        ORDER BY dayEpoch ASC
    """,
                periodWindow, totalIdx = 3, countIdx = 4
            ) { d ->
                d.getDailyTotalsWithDatesForPeriod(start, end)
                    .map { listOf(it.dayEpoch, it.startDate, it.endDate, it.total, it.txCount) }
            },
            QueryCase(
                "getAverageDailySpend",
                """
        SELECT AVG(daily_total) FROM (
            SELECT SUM(${EA}) as daily_total
            FROM expenses
            WHERE ${ST}
            AND date >= :startMs AND date < :endMs
            AND isNotMine = 0
            GROUP BY strftime('%Y-%m-%d', date/1000, 'unixepoch', 'localtime')
        )
    """,
                periodWindow, totalIdx = null, countIdx = null
            ) { d -> listOf(listOf(d.getAverageDailySpend(start, end))) },
            QueryCase(
                "getCategoryBreakdown",
                """
        SELECT c.id, c.name, c.icon, c.color,
               SUM(${EA_E}) as total,
               COUNT(*) as txCount
        FROM expenses e
        LEFT JOIN categories c ON e.categoryId = c.id
        WHERE ${ST_E}
        AND e.date >= :startMs AND e.date < :endMs
        AND e.isNotMine = 0
        GROUP BY c.id
        ORDER BY total DESC
        LIMIT 10
    """,
                periodWindow, totalIdx = 4, countIdx = 5
            ) { d ->
                d.getCategoryBreakdown(start, end)
                    .map { listOf(it.id, it.name, it.icon, it.color, it.total, it.txCount) }
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
        rows.sumOf { (it[idx] as Number?)?.toDouble() ?: 0.0 }

    // ── (1) Neutrality: NULL flags => converted == verbatim pre-edit SQL ────

    @Test
    fun `S4b queries are neutral over a NULL-flag fixture`() = runBlocking {
        val f = newFixture()
        seedBase(f)
        val failures = mutableListOf<String>()
        for (case in cases()) {
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
    fun `S4b queries drop INSTALLMENT_PURCHASE and INSTALLMENT_REVERSED rows and only those`() = runBlocking {
        val full = newFixture()
        seedBase(full)
        seedFlaggedRows(full)
        // The reference database holds exactly the same data minus the flagged rows.
        val reference = newFixture()
        seedBase(reference)

        val flaggedAmount = flaggedRows.sumOf { it.amount }
        val failures = mutableListOf<String>()
        for (case in cases()) {
            val converted = case.run(full.dao)
            val preEditOnFull = runRaw(full, case.preEditSql, case.params)
            val preEditOnReference = runRaw(reference, case.preEditSql, case.params)

            if (normalized(converted) != normalized(preEditOnReference)) {
                failures += "${case.name}: converted != reference without flagged rows " +
                    "(converted=${normalized(converted)} reference=${normalized(preEditOnReference)})"
            }
            // Sanity: the flagged rows do change the verbatim pre-edit result (non-vacuous case).
            if (normalized(preEditOnFull) == normalized(preEditOnReference)) {
                failures += "${case.name}: flagged rows did not affect the pre-edit result"
            }
            case.totalIdx?.let { idx ->
                val amountDrop = sumColumn(preEditOnFull, idx) - sumColumn(converted, idx)
                if (abs(amountDrop - flaggedAmount) > 1e-6) {
                    failures += "${case.name}: total dropped by $amountDrop, expected $flaggedAmount"
                }
            }
            case.countIdx?.let { idx ->
                val countDrop = sumColumn(preEditOnFull, idx) - sumColumn(converted, idx)
                if (abs(countDrop - flaggedRows.size.toDouble()) > 1e-9) {
                    failures += "${case.name}: COUNT dropped by $countDrop, expected ${flaggedRows.size}"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ── AE-1 / AE-6 / AE-17 ─────────────────────────────────────────────────

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

    @Test
    fun `AE-1 getTotalForPeriod - purchase month is 0_00 and each installment month is 50_00`() = runBlocking {
        val f = newFixture()
        seedInstallmentScenario(f)

        assertEquals(0.0, f.dao.getTotalForPeriod(monthStart(2027, 3), monthStart(2027, 4)), 1e-9)
        assertEquals(50.0, f.dao.getTotalForPeriod(monthStart(2027, 4), monthStart(2027, 5)), 1e-9)
        assertEquals(50.0, f.dao.getTotalForPeriod(monthStart(2028, 3), monthStart(2028, 4)), 1e-9)
        // Whole range: twelve installments; the 600.00 purchase record counts nothing.
        val rangeStart = monthStart(2027, 3)
        val rangeEnd = monthStart(2028, 4)
        assertEquals(600.0, f.dao.getTotalForPeriod(rangeStart, rangeEnd), 1e-9)
        assertEquals(600.0, f.dao.getTotalSpentBetween(rangeStart, rangeEnd) ?: Double.NaN, 1e-9)
        assertEquals(600.0, f.dao.getTotalSpentFlow().first() ?: Double.NaN, 1e-9)
        assertEquals(12, f.dao.getPurchaseCount())

        val categoryTotal = f.dao.getCategoryTotalsBetween(rangeStart, rangeEnd).single()
        assertEquals(f.cat1, categoryTotal.categoryId)
        assertEquals(600.0, categoryTotal.total, 1e-9)
        assertEquals(12, categoryTotal.txCount)
        val breakdown = f.dao.getCategoryBreakdown(rangeStart, rangeEnd).single()
        assertEquals(f.cat1, breakdown.id)
        assertEquals(600.0, breakdown.total, 1e-9)
        assertEquals(12, breakdown.txCount)
        assertEquals(50.0, f.dao.getAverageDailySpend(rangeStart, rangeEnd) ?: Double.NaN, 1e-9)

        // March alone: the flagged purchase is the only row and it counts nothing at all.
        val marchStart = monthStart(2027, 3)
        val aprilStart = monthStart(2027, 4)
        assertTrue(f.dao.getCategoryTotalsBetween(marchStart, aprilStart).isEmpty())
        assertTrue(f.dao.getCategoryBreakdown(marchStart, aprilStart).isEmpty())
        assertTrue(f.dao.getSpendingDailyTotalsBetween(marchStart, aprilStart).isEmpty())
        assertTrue(f.dao.getWeeklyTotalsForPeriod(marchStart, aprilStart).isEmpty())
        assertTrue(f.dao.getMonthlyTotalsForPeriod(marchStart, aprilStart).isEmpty())
        assertTrue(f.dao.getDailyTotalsWithDatesForPeriod(marchStart, aprilStart).isEmpty())
    }

    @Test
    fun `AE-6 weekly monthly and daily - no bucket ever shows the full purchase amount`() = runBlocking {
        val f = newFixture()
        seedInstallmentScenario(f)
        val rangeStart = monthStart(2027, 3)
        val rangeEnd = monthStart(2028, 4)

        val monthly = f.dao.getMonthlyTotalsForPeriod(rangeStart, rangeEnd)
        assertEquals(scenarioMonths, monthly.map { it.monthKey })
        monthly.forEach { assertEquals("month ${it.monthKey}", 50.0, it.total, 1e-9) }
        assertTrue(monthly.none { it.monthKey == "2027-03" })

        val weekly = f.dao.getWeeklyTotalsForPeriod(rangeStart, rangeEnd)
        assertEquals(12, weekly.size)
        weekly.forEach { assertEquals("week ${it.weekKey}", 50.0, it.total, 1e-9) }

        val daily = f.dao.getDailyTotalsWithDatesForPeriod(rangeStart, rangeEnd)
        assertEquals(12, daily.size)
        daily.forEach { assertEquals("day ${it.dayEpoch}", 50.0, it.total, 1e-9) }

        val spendingDaily = f.dao.getSpendingDailyTotalsBetween(rangeStart, rangeEnd)
        assertEquals(12, spendingDaily.size)
        spendingDaily.forEach { assertEquals("day ${it.dayEpoch}", 50.0, it.total, 1e-9) }

        val allTotals = monthly.map { it.total } + weekly.map { it.total } +
            daily.map { it.total } + spendingDaily.map { it.total }
        assertTrue(allTotals.none { abs(it - 600.0) < 1e-9 || abs(it - 650.0) < 1e-9 })
    }

    @Test
    fun `AE-17 control - two unflagged 50_00 rows in June count 100_00`() = runBlocking {
        val f = newFixture()
        seedInstallmentScenario(f)
        // Captured charge C3 stays a plain expense next to the 2027-06 installment (nothing flagged).
        f.insert(amount = 50.0, date = day(2027, 6, 11), merchant = "Captured Charge")

        val juneStart = monthStart(2027, 6)
        val julyStart = monthStart(2027, 7)
        assertEquals(100.0, f.dao.getTotalForPeriod(juneStart, julyStart), 1e-9)
        assertEquals(100.0, f.dao.getTotalSpentBetween(juneStart, julyStart) ?: Double.NaN, 1e-9)

        val monthly = f.dao.getMonthlyTotalsForPeriod(monthStart(2027, 3), monthStart(2028, 4))
        assertEquals(100.0, monthly.single { it.monthKey == "2027-06" }.total, 1e-9)
        assertEquals(2, monthly.single { it.monthKey == "2027-06" }.txCount)
        monthly.filter { it.monthKey != "2027-06" }.forEach { assertEquals(50.0, it.total, 1e-9) }
        assertEquals(13, f.dao.getPurchaseCount())
    }

    private companion object {
        /** Pre-edit effective-amount expression, verbatim from the DAO companion. */
        const val EA: String = ExpenseDao.EFFECTIVE_AMOUNT_SQL

        /** Pre-edit effective-amount expression with the e. alias, verbatim from the DAO companion. */
        const val EA_E: String = ExpenseDao.EFFECTIVE_AMOUNT_E_SQL

        /** Pre-edit spending-type predicate, verbatim from the DAO companion. */
        const val ST: String = ExpenseDao.SPENDING_TYPE_SQL

        /** Pre-edit spending-type predicate with the e. alias, verbatim from the DAO companion. */
        const val ST_E: String = ExpenseDao.SPENDING_TYPE_E_SQL
    }
}
