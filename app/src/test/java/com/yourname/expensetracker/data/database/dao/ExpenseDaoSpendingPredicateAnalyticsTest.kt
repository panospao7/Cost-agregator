package com.yourname.expensetracker.data.database.dao

import android.content.Context
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.dao.ExpenseDaoSpendingPredicateFixture.Companion.day
import com.yourname.expensetracker.data.database.dao.ExpenseDaoSpendingPredicateFixture.Companion.monthStart
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Loan-installments S4c (plan amendment A12.2/A12.5): the 13 analytics, trends, merchant and
 * location queries now use the canonical spending predicate.
 *
 * For every converted query:
 *  1. neutrality: the verbatim pre-edit SQL text, run through the raw database handle, returns
 *     exactly what the converted DAO method returns while every flag is NULL
 *     (isNotMine x share x type x currency x date-bucket x location fixture). Row-valued queries
 *     (percentile list, the two largest-expense lookups) compare the returned rows;
 *  2. flagged case: with INSTALLMENT_PURCHASE and INSTALLMENT_REVERSED rows flagged through the
 *     coordinator, the DAO result equals the pre-edit SQL over the same data without those rows,
 *     and the row count drops by exactly the flagged rows where the query returns a count.
 *
 * Plus the analytics parts of AE-6 (merchant totals and merchant stats count only the twelve
 * installment expenses), the largest-expense lookups never return the flagged purchase (E1), the
 * percentile list holds no 600.00 value, and 12 x 50.00 is a recurring candidate with E1 excluded.
 * The deprecated-ERROR DAO methods are called on purpose (they have live callers), hence the
 * class-level suppression.
 */
@Suppress("DEPRECATION_ERROR")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExpenseDaoSpendingPredicateAnalyticsTest {

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
        /** True when every result row is one expense (percentile list): the row count must drop. */
        val rowCountDrop: Boolean = false,
        /** When set, only this raw column is read (row-valued queries compare the expense id). */
        val idColumn: String? = null,
        val run: suspend (ExpenseDao) -> List<List<Any?>>
    )

    private val start = monthStart(2027, 1)
    private val end = monthStart(2028, 6)
    private val recurringKey = "rent co"

    private fun statsRow(s: MerchantStats): List<Any?> = listOf(
        s.merchantName, s.displayName, s.totalAmount, s.transactionCount,
        s.averageAmount, s.minAmount, s.maxAmount, s.firstDate, s.lastDate
    )

    private fun cases(): List<QueryCase> {
        val window = mapOf<String, Any?>("startDate" to start, "endDate" to end)
        val periodWindow = mapOf<String, Any?>("startMs" to start, "endMs" to end)
        return listOf(
            QueryCase(
                "getAmountsForPercentileCalc",
                """
        SELECT ${EA}
        FROM expenses${SP}
        WHERE ${ST}${SP}
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        ORDER BY 1 ASC
    """,
                periodWindow, totalIdx = 0, countIdx = null, rowCountDrop = true
            ) { d -> d.getAmountsForPercentileCalc(start, end).map { listOf<Any?>(it) } },
            QueryCase(
                "getMerchantTotalsBetween",
                """
        SELECT merchantKey as merchantKey, MIN(merchant) as merchant,
               SUM(${EA}) as total, COUNT(*) as cnt${SP}
        FROM expenses${SP}
        WHERE ${ST}${SP}
        AND date >= :startDate AND date < :endDate
        AND isNotMine = 0
        AND merchantKey IS NOT NULL
        GROUP BY merchantKey
        ORDER BY total DESC
    """,
                window, totalIdx = 2, countIdx = 3
            ) { d -> d.getMerchantTotalsBetween(start, end).map { listOf(it.merchantKey, it.merchant, it.total, it.cnt) } },
            QueryCase(
                "getCategoryTotalsForPeriod",
                """
        SELECT categoryId, SUM(${EA}) as total, COUNT(*) as txCount
        FROM expenses${SP}
        WHERE ${ST}${SP}
        AND date >= :startMs AND date < :endMs
        AND categoryId IS NOT NULL
        AND isNotMine = 0
        GROUP BY categoryId
        ORDER BY total DESC
    """,
                periodWindow, totalIdx = 1, countIdx = 2
            ) { d -> d.getCategoryTotalsForPeriod(start, end).map { listOf(it.categoryId, it.total, it.txCount) } },
            QueryCase(
                "getMerchantStats",
                """
        SELECT merchantKey as merchantName,${SP}
               MIN(merchant) as displayName,
               SUM(${EA}) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(${EA}) as averageAmount,
               MIN(${EA}) as minAmount,
               MAX(${EA}) as maxAmount,
               MIN(date) as firstDate,${SP}
               MAX(date) as lastDate
        FROM expenses${SP}
        WHERE ${ST}
        AND isNotMine = 0
        AND merchantKey IS NOT NULL
        GROUP BY merchantKey
        HAVING transactionCount >= 2
        ORDER BY totalAmount DESC
    """,
                emptyMap(), totalIdx = 2, countIdx = 3
            ) { d -> d.getMerchantStats().map(::statsRow) },
            QueryCase(
                "getAllMerchantStats",
                """
        SELECT merchantKey as merchantName,${SP}
               MIN(merchant) as displayName,
               SUM(${EA}) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(${EA}) as averageAmount,
               MIN(${EA}) as minAmount,
               MAX(${EA}) as maxAmount,
               MIN(date) as firstDate,${SP}
               MAX(date) as lastDate
        FROM expenses${SP}
        WHERE ${ST}
        AND isNotMine = 0
        AND merchantKey IS NOT NULL
        GROUP BY merchantKey
        ORDER BY totalAmount DESC
    """,
                emptyMap(), totalIdx = 2, countIdx = 3
            ) { d -> d.getAllMerchantStats().map(::statsRow) },
            QueryCase(
                "getTopMerchantsForPeriod",
                """
        SELECT merchantKey as merchantName,${SP}
               MIN(merchant) as displayName,
               SUM(${EA}) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(${EA}) as averageAmount,
               MIN(${EA}) as minAmount,
               MAX(${EA}) as maxAmount,
               MIN(date) as firstDate,${SP}
               MAX(date) as lastDate
        FROM expenses${SP}
        WHERE ${ST}${SP}
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        AND merchantKey IS NOT NULL
        GROUP BY merchantKey
        ORDER BY totalAmount DESC
        LIMIT :limit
    """,
                periodWindow + ("limit" to 10), totalIdx = 2, countIdx = 3
            ) { d -> d.getTopMerchantsForPeriod(start, end, 10).map(::statsRow) },
            QueryCase(
                "getLargestExpenseForPeriod",
                """
        SELECT * FROM expenses${SP}
        WHERE ${ST}${SP}
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        ORDER BY ${EA} DESC
        LIMIT 1
    """,
                periodWindow, totalIdx = null, countIdx = null, idColumn = "id"
            ) { d -> d.getLargestExpenseForPeriod(start, end)?.let { listOf(listOf<Any?>(it.id)) } ?: emptyList() },
            QueryCase(
                "getLargestExpenseForMerchant",
                """
        SELECT * FROM expenses${SP}
        WHERE ${ST}${SP}
        AND date >= :startMs AND date < :endMs
        AND merchantKey = :merchantKey
        AND isNotMine = 0
        ORDER BY ${EA} DESC
        LIMIT 1
    """,
                periodWindow + ("merchantKey" to "'$recurringKey'"), totalIdx = null, countIdx = null, idColumn = "id"
            ) { d ->
                d.getLargestExpenseForMerchant(recurringKey, start, end)?.let { listOf(listOf<Any?>(it.id)) } ?: emptyList()
            },
            QueryCase(
                "getDailyTotalsForPeriod",
                """
        SELECT CAST(strftime('%Y%m%d', date/1000, 'unixepoch', 'localtime') AS INTEGER) as dayEpoch,
               MIN(date) as startDate, MAX(date) as endDate,
               SUM(${EA}) as total, COUNT(*) as txCount
        FROM expenses${SP}
        WHERE ${ST}${SP}
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        GROUP BY dayEpoch
        ORDER BY dayEpoch ASC
    """,
                periodWindow, totalIdx = 3, countIdx = 4
            ) { d ->
                d.getDailyTotalsForPeriod(start, end)
                    .map { listOf(it.dayEpoch, it.startDate, it.endDate, it.total, it.txCount) }
            },
            QueryCase(
                "getRecurringCandidates",
                """
        SELECT merchantKey as merchantName,
               MIN(merchant) as displayName,
               SUM(${EA}) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(${EA}) as averageAmount,
               MIN(${EA}) as minAmount,
               MAX(${EA}) as maxAmount,
               MIN(date) as firstDate,${SP}
               MAX(date) as lastDate
        FROM expenses${SP}
        WHERE ${ST}
        AND isNotMine = 0
        AND merchantKey IS NOT NULL
        GROUP BY merchantKey
        HAVING transactionCount >= 2${SP}
        AND (maxAmount - minAmount) < (averageAmount * 0.15)
        ORDER BY transactionCount DESC
    """,
                // The flagged rows change which merchants pass the variance filter, so the drop
                // arithmetic does not apply; result equality with the reference covers it.
                emptyMap(), totalIdx = null, countIdx = null
            ) { d -> d.getRecurringCandidates().map(::statsRow) },
            QueryCase(
                "getDayOfWeekPattern",
                """
        SELECT${SP}
            ((CAST(strftime('%w', date/1000, 'unixepoch', 'localtime') AS INTEGER) + 6) % 7) as dayOfWeek,
            SUM(${EA}) as total,
            COUNT(*) as txCount,
            AVG(${EA}) as avgAmount
        FROM expenses
        WHERE ${ST}
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        GROUP BY dayOfWeek
        ORDER BY dayOfWeek ASC
    """,
                periodWindow, totalIdx = 1, countIdx = 2
            ) { d ->
                d.getDayOfWeekPattern(start, end).map { listOf(it.dayOfWeek, it.total, it.txCount, it.avgAmount) }
            },
            QueryCase(
                "getLocatedMerchantTotals",
                """
        SELECT merchantKey as merchantKey, MIN(merchant) as merchant,
               SUM(${EA}) as total, COUNT(*) as cnt
        FROM expenses
        WHERE latitude IS NOT NULL
          AND longitude IS NOT NULL
          AND ${ST}
          AND isNotMine = 0
          AND merchantKey IS NOT NULL
        GROUP BY merchantKey
        ORDER BY total DESC
    """,
                emptyMap(), totalIdx = 2, countIdx = 3
            ) { d -> d.getLocatedMerchantTotals().map { listOf(it.merchantKey, it.merchant, it.total, it.cnt) } },
            QueryCase(
                "getLocatedMerchantTotalsByCurrency",
                """
        SELECT MIN(merchant) AS merchant,
               UPPER(COALESCE(currency, 'EUR')) AS currency,
               SUM(COALESCE(CASE${SP}
                   WHEN isNotMine = 1 THEN 0.0
                   WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                   WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                   ELSE amount END, 0)) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE latitude IS NOT NULL
          AND longitude IS NOT NULL
          AND ${ST}
          AND isNotMine = 0
          AND merchantKey IS NOT NULL
        GROUP BY merchantKey, UPPER(COALESCE(currency, 'EUR'))
        ORDER BY total DESC
    """,
                emptyMap(), totalIdx = 2, countIdx = 3
            ) { d ->
                d.getLocatedMerchantTotalsByCurrency().map { listOf(it.merchant, it.currency, it.total, it.txCount) }
            }
        )
    }

    // ── Seeding ─────────────────────────────────────────────────────────────

    private companion object {
        /** One space: pre-edit SQL copies carry the base file's trailing space at these exact line ends. */
        const val SP: String = " "

        const val LAT: Double = 37.97
        const val LON: Double = 23.72

        /** Pre-edit effective-amount expression, verbatim from the DAO companion. */
        const val EA: String = ExpenseDao.EFFECTIVE_AMOUNT_SQL

        /** Pre-edit spending-type predicate, verbatim from the DAO companion. */
        const val ST: String = ExpenseDao.SPENDING_TYPE_SQL
    }

    /**
     * NULL-flag data: isNotMine x share kind x transaction type x currency x date bucket, with
     * rotating categories (including none), merchants and coordinates (two thirds are located),
     * plus a small recurring merchant (80, 81, 82 inside the window) so the recurring-candidate
     * query and the per-merchant largest lookup are never vacuous.
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
                            val located = idx % 3 != 0
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
                                shareAmount = if (share == 2) amount * 0.4 else null,
                                latitude = if (located) LAT else null,
                                longitude = if (located) LON else null
                            )
                        }
                    }
                }
            }
        }
        listOf(day(2027, 2, 20) to 80.0, day(2027, 8, 20) to 81.0, day(2028, 4, 20) to 82.0).forEach { (date, amount) ->
            f.insert(amount = amount, date = date, merchant = "Rent Co", categoryId = f.cat2, latitude = LAT, longitude = LON)
        }
    }

    private class Flagged(val amount: Double, val date: Long, val reason: SpendingExclusionReason)

    private val flaggedRows = listOf(
        Flagged(600.0, day(2027, 3, 10), SpendingExclusionReason.INSTALLMENT_PURCHASE),
        Flagged(300.0, day(2027, 5, 10), SpendingExclusionReason.INSTALLMENT_PURCHASE),
        Flagged(250.0, day(2027, 6, 10), SpendingExclusionReason.INSTALLMENT_REVERSED)
    )

    /**
     * Seeds [flaggedRows] as plain located PURCHASE rows of the recurring merchant, then flags each
     * through the coordinator. Their amounts break the merchant's variance filter before the edit.
     */
    private suspend fun seedFlaggedRows(f: ExpenseDaoSpendingPredicateFixture) {
        for (row in flaggedRows) {
            val id = f.insert(
                amount = row.amount,
                date = row.date,
                merchant = "Rent Co",
                categoryId = f.cat1,
                latitude = LAT,
                longitude = LON
            )
            f.flag(id, row.reason)
        }
    }

    // ── Raw SQL execution and comparison ────────────────────────────────────

    private fun runRaw(
        f: ExpenseDaoSpendingPredicateFixture,
        sql: String,
        params: Map<String, Any?>,
        idColumn: String? = null
    ): List<List<Any?>> {
        val literal = Regex(":(\\w+)").replace(sql) { m ->
            require(params.containsKey(m.groupValues[1])) { "unbound parameter ${m.value}" }
            when (val v = params[m.groupValues[1]]) {
                null -> "NULL"
                is Iterable<*> -> v.joinToString(",")
                else -> v.toString()
            }
        }
        f.raw.query(literal).use { cursor ->
            val columns = if (idColumn != null) {
                listOf(cursor.getColumnIndexOrThrow(idColumn))
            } else {
                (0 until cursor.columnCount).toList()
            }
            val rows = mutableListOf<List<Any?>>()
            while (cursor.moveToNext()) {
                rows += columns.map { i ->
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
    fun `S4c queries are neutral over a NULL-flag fixture`() = runBlocking {
        val f = newFixture()
        seedBase(f)
        val failures = mutableListOf<String>()
        for (case in cases()) {
            val expected = normalized(runRaw(f, case.preEditSql, case.params, case.idColumn))
            val actual = normalized(case.run(f.dao))
            if (expected != actual) failures += "${case.name}: expected=$expected actual=$actual"
            // Guard against a vacuous fixture: every query must see at least one row.
            if (expected.isEmpty()) failures += "${case.name}: neutrality fixture produced no rows"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ── (2) Flagged case ────────────────────────────────────────────────────

    @Test
    fun `S4c queries drop INSTALLMENT_PURCHASE and INSTALLMENT_REVERSED rows and only those`() = runBlocking {
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
            val preEditOnFull = runRaw(full, case.preEditSql, case.params, case.idColumn)
            val preEditOnReference = runRaw(reference, case.preEditSql, case.params, case.idColumn)

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
            if (case.rowCountDrop) {
                val rowDrop = preEditOnFull.size - converted.size
                if (rowDrop != flaggedRows.size) {
                    failures += "${case.name}: row count dropped by $rowDrop, expected ${flaggedRows.size}"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ── AE-6 and the analytics parts of AE-1 ────────────────────────────────

    private val installmentKey = "electronics shop"

    /**
     * A 600.00 purchase (E1) flagged in 2027-03 plus twelve 50.00 installment expenses
     * 2027-04..2028-03, all located and all with the same merchant. Returns the purchase id.
     */
    private suspend fun seedInstallmentScenario(f: ExpenseDaoSpendingPredicateFixture): Long {
        val purchaseId = f.insert(
            amount = 600.0,
            date = day(2027, 3, 10),
            merchant = "Electronics Shop",
            latitude = LAT,
            longitude = LON
        )
        f.flag(purchaseId, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        var year = 2027
        var month = 4
        repeat(12) {
            f.insert(
                amount = 50.0,
                date = day(year, month, 15),
                merchant = "Electronics Shop",
                latitude = LAT,
                longitude = LON
            )
            if (month == 12) {
                month = 1
                year++
            } else {
                month++
            }
        }
        return purchaseId
    }

    private val scenarioStart = monthStart(2027, 3)
    private val scenarioEnd = monthStart(2028, 4)

    @Test
    fun `AE-6 merchant totals and merchant stats count the twelve installments only`() = runBlocking {
        val f = newFixture()
        seedInstallmentScenario(f)

        val merchantTotal = f.dao.getMerchantTotalsBetween(scenarioStart, scenarioEnd).single()
        assertEquals(installmentKey, merchantTotal.merchantKey)
        assertEquals(600.0, merchantTotal.total, 1e-9)
        assertEquals(12, merchantTotal.cnt)

        val located = f.dao.getLocatedMerchantTotals().single()
        assertEquals(installmentKey, located.merchantKey)
        assertEquals(600.0, located.total, 1e-9)
        assertEquals(12, located.cnt)
        val locatedByCurrency = f.dao.getLocatedMerchantTotalsByCurrency().single()
        assertEquals("EUR", locatedByCurrency.currency)
        assertEquals(600.0, locatedByCurrency.total, 1e-9)
        assertEquals(12, locatedByCurrency.txCount)

        val stats = mapOf(
            "getMerchantStats" to f.dao.getMerchantStats().single(),
            "getAllMerchantStats" to f.dao.getAllMerchantStats().single(),
            "getTopMerchantsForPeriod" to f.dao.getTopMerchantsForPeriod(scenarioStart, scenarioEnd, 10).single()
        )
        stats.forEach { (name, s) ->
            assertEquals("$name merchant", installmentKey, s.merchantName)
            assertEquals("$name transactionCount", 12, s.transactionCount)
            assertEquals("$name totalAmount", 600.0, s.totalAmount, 1e-9)
            assertEquals("$name averageAmount", 50.0, s.averageAmount, 1e-9)
            assertEquals("$name minAmount", 50.0, s.minAmount, 1e-9)
            assertEquals("$name maxAmount", 50.0, s.maxAmount, 1e-9)
            // The flagged purchase (2027-03-10) is not even the first counted date.
            assertEquals("$name firstDate", day(2027, 4, 15), s.firstDate)
            assertEquals("$name lastDate", day(2028, 3, 15), s.lastDate)
        }

        val categoryTotal = f.dao.getCategoryTotalsForPeriod(scenarioStart, scenarioEnd).single()
        assertEquals(f.cat1, categoryTotal.categoryId)
        assertEquals(600.0, categoryTotal.total, 1e-9)
        assertEquals(12, categoryTotal.txCount)

        // March alone: the flagged purchase is the only row and it counts nothing at all.
        val marchStart = monthStart(2027, 3)
        val aprilStart = monthStart(2027, 4)
        assertTrue(f.dao.getCategoryTotalsForPeriod(marchStart, aprilStart).isEmpty())
        assertTrue(f.dao.getMerchantTotalsBetween(marchStart, aprilStart).isEmpty())
        assertTrue(f.dao.getTopMerchantsForPeriod(marchStart, aprilStart, 10).isEmpty())
        assertTrue(f.dao.getDailyTotalsForPeriod(marchStart, aprilStart).isEmpty())
        assertTrue(f.dao.getDayOfWeekPattern(marchStart, aprilStart).isEmpty())
        assertTrue(f.dao.getAmountsForPercentileCalc(marchStart, aprilStart).isEmpty())
    }

    @Test
    fun `AE-6 daily totals and day-of-week pattern never show the full purchase amount`() = runBlocking {
        val f = newFixture()
        seedInstallmentScenario(f)

        val daily = f.dao.getDailyTotalsForPeriod(scenarioStart, scenarioEnd)
        assertEquals(12, daily.size)
        daily.forEach { assertEquals("day ${it.dayEpoch}", 50.0, it.total, 1e-9) }

        val pattern = f.dao.getDayOfWeekPattern(scenarioStart, scenarioEnd)
        assertEquals(600.0, pattern.sumOf { it.total }, 1e-9)
        assertEquals(12, pattern.sumOf { it.txCount })
        pattern.forEach { assertEquals("weekday ${it.dayOfWeek}", 50.0, it.avgAmount, 1e-9) }

        val allTotals = daily.map { it.total } + pattern.map { it.total }
        assertTrue(allTotals.none { abs(it - 650.0) < 1e-9 })
        assertTrue(daily.none { abs(it.total - 600.0) < 1e-9 })
    }

    @Test
    fun `largest expense lookups never return the flagged purchase E1`() = runBlocking {
        val f = newFixture()
        val purchaseId = seedInstallmentScenario(f)

        val largestOfPeriod = f.dao.getLargestExpenseForPeriod(scenarioStart, scenarioEnd)
        assertNotNull(largestOfPeriod)
        assertNotEquals(purchaseId, largestOfPeriod!!.id)
        assertEquals(50.0, largestOfPeriod.amount, 1e-9)

        val largestOfMerchant = f.dao.getLargestExpenseForMerchant(installmentKey, scenarioStart, scenarioEnd)
        assertNotNull(largestOfMerchant)
        assertNotEquals(purchaseId, largestOfMerchant!!.id)
        assertEquals(50.0, largestOfMerchant.amount, 1e-9)

        // March alone holds only E1, and it is excluded: there is no largest expense at all.
        val marchStart = monthStart(2027, 3)
        val aprilStart = monthStart(2027, 4)
        assertEquals(null, f.dao.getLargestExpenseForPeriod(marchStart, aprilStart))
        assertEquals(null, f.dao.getLargestExpenseForMerchant(installmentKey, marchStart, aprilStart))
    }

    @Test
    fun `percentile list holds the twelve installments and no 600`() = runBlocking {
        val f = newFixture()
        seedInstallmentScenario(f)

        val amounts = f.dao.getAmountsForPercentileCalc(scenarioStart, scenarioEnd)
        assertEquals(12, amounts.size)
        amounts.forEach { assertEquals(50.0, it, 1e-9) }
        assertTrue(amounts.none { abs(it - 600.0) < 1e-9 })
    }

    @Test
    fun `12 x 50_00 is a recurring candidate and E1 is excluded from it`() = runBlocking {
        val f = newFixture()
        seedInstallmentScenario(f)

        val candidate = f.dao.getRecurringCandidates().single()
        assertEquals(installmentKey, candidate.merchantName)
        assertEquals(12, candidate.transactionCount)
        assertEquals(600.0, candidate.totalAmount, 1e-9)
        assertEquals(50.0, candidate.averageAmount, 1e-9)
        assertEquals(50.0, candidate.minAmount, 1e-9)
        // With E1 counted the spread would be 550.00 against a 15 percent band and the merchant
        // would not be a candidate at all.
        assertEquals(50.0, candidate.maxAmount, 1e-9)
    }
}
