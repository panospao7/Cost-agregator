package com.yourname.expensetracker.data.database.dao

import android.content.Context
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.dao.ExpenseDaoSpendingPredicateFixture.Companion.day
import com.yourname.expensetracker.data.database.dao.ExpenseDaoSpendingPredicateFixture.Companion.monthStart
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
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
 * Loan-installments S4d (plan amendment A12.1/A12.2/A12.5): the five CASE-only business
 * aggregates now take their SELECT amount from SpendingInclusionSql.EFFECTIVE_AMOUNT. No WHERE
 * is added, so a flagged purchase still counts in COUNT (owner decision O2) but contributes 0.0 to
 * the sum.
 *
 * For every converted query:
 *  1. neutrality: the verbatim pre-edit SQL text (8575fbe5), run through the raw database handle,
 *     returns exactly what the converted DAO method returns while every flag is NULL
 *     (isNotMine x share x type x currency x business category x project x date bucket);
 *  2. flagged case: with INSTALLMENT_PURCHASE and INSTALLMENT_REVERSED rows flagged through the
 *     coordinator, the sum drops by exactly the flagged effective amounts and every COUNT is
 *     unchanged. The reference is the pre-edit SQL over the same data where the flagged rows were
 *     inserted with amount 0.0 and no flag.
 *
 * Plus AE-1 business: a declared business purchase contributes 0 and each paid installment counts.
 * The deprecated-ERROR DAO methods are called on purpose (they have live callers), hence the
 * class-level suppression.
 */
@Suppress("DEPRECATION_ERROR")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExpenseDaoSpendingPredicateBusinessTest {

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

    private companion object {
        /** One space: pre-edit SQL copies carry the base file's trailing space at these exact line ends. */
        const val SP: String = " "

        /** Pre-edit effective-amount expression, verbatim from the DAO companion. */
        const val EA: String = ExpenseDao.EFFECTIVE_AMOUNT_SQL

        /** Pre-edit spending-type predicate, verbatim from the DAO companion. */
        const val ST: String = ExpenseDao.SPENDING_TYPE_SQL
    }

    // ── Query registry ──────────────────────────────────────────────────────

    private class QueryCase(
        val name: String,
        /** Verbatim pre-edit SQL text (named parameters are bound as literals). */
        val preEditSql: String,
        /** Index of the total column in a result row. */
        val totalIdx: Int,
        /** Index of the count column, or null when the query returns no count. */
        val countIdx: Int?,
        val run: suspend (ExpenseDao) -> List<List<Any?>>
    )

    private val start = monthStart(2027, 1)
    private val end = monthStart(2028, 6)

    private fun cases(): List<QueryCase> = listOf(
        QueryCase(
            "getTotalBusinessExpensesBetween",
            """
        SELECT COALESCE(SUM(${EA}), 0.0) FROM expenses${SP}
        WHERE isBusinessExpense = 1${SP}
        AND ${ST}
        AND date >= :startDate AND date < :endDate
    """,
            totalIdx = 0, countIdx = null
        ) { d -> listOf(listOf<Any?>(d.getTotalBusinessExpensesBetween(start, end))) },
        QueryCase(
            "getBusinessExpensesBetweenByCurrency",
            """
        SELECT UPPER(COALESCE(currency, 'EUR')) AS currency,
               SUM(COALESCE(CASE${SP}
                   WHEN isNotMine = 1 THEN 0.0
                   WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                   WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                   ELSE amount END, 0)) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE isBusinessExpense = 1
          AND ${ST}
          AND date >= :startDate AND date < :endDate
        GROUP BY UPPER(COALESCE(currency, 'EUR'))
        ORDER BY total DESC
    """,
            totalIdx = 1, countIdx = 2
        ) { d -> d.getBusinessExpensesBetweenByCurrency(start, end).map { listOf(it.currency, it.total, it.txCount) } },
        QueryCase(
            "getBusinessExpensesByCategory",
            """
        SELECT businessCategory,
               SUM(${EA}) as total,
               COUNT(*) as count${SP}
        FROM expenses${SP}
        WHERE isBusinessExpense = 1${SP}
        AND ${ST}
        AND date >= :startDate AND date < :endDate
        AND businessCategory IS NOT NULL
        GROUP BY businessCategory
        ORDER BY total DESC
    """,
            totalIdx = 1, countIdx = 2
        ) { d -> d.getBusinessExpensesByCategory(start, end).map { listOf(it.businessCategory, it.total, it.count) } },
        QueryCase(
            "getBusinessExpensesByProject",
            """
        SELECT businessProject,
               SUM(${EA}) as total,
               COUNT(*) as count${SP}
        FROM expenses${SP}
        WHERE isBusinessExpense = 1${SP}
        AND ${ST}
        AND date >= :startDate AND date < :endDate
        AND businessProject IS NOT NULL
        GROUP BY businessProject
        ORDER BY total DESC
    """,
            totalIdx = 1, countIdx = 2
        ) { d -> d.getBusinessExpensesByProject(start, end).map { listOf(it.businessProject, it.total, it.count) } },
        QueryCase(
            "getBusinessCategoryCurrencyTotals",
            """
        SELECT COALESCE(businessCategory, 'Uncategorized') AS businessCategory,
               currency,
               SUM(${EA}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE isBusinessExpense = 1
          AND ${ST}
          AND date >= :startDate AND date < :endDate
        GROUP BY COALESCE(businessCategory, 'Uncategorized'), currency
        ORDER BY businessCategory ASC
    """,
            totalIdx = 2, countIdx = 3
        ) { d ->
            d.getBusinessCategoryCurrencyTotals(start, end)
                .map { listOf(it.businessCategory, it.currency, it.total, it.txCount) }
        }
    )

    private val params: Map<String, Any?> = mapOf("startDate" to start, "endDate" to end)

    // ── Seeding ─────────────────────────────────────────────────────────────

    @OptIn(RestrictedExpenseDaoMutation::class)
    private suspend fun insertBusiness(
        f: ExpenseDaoSpendingPredicateFixture,
        amount: Double,
        date: Long,
        type: TransactionType = TransactionType.PURCHASE,
        currency: String = "EUR",
        isBusiness: Boolean = true,
        category: String? = "Travel",
        project: String? = "Alpha",
        isNotMine: Boolean = false,
        isShared: Boolean = false,
        sharePct: Int? = null,
        shareAmount: Double? = null
    ): Long {
        val id = f.dao.insert(
            Expense(
                amount = amount,
                currency = currency,
                merchant = "Biz",
                merchantKey = "biz",
                transactionType = type,
                date = date,
                categoryId = f.cat1,
                isNotMine = isNotMine,
                isSharedExpense = isShared,
                mySharePercentage = sharePct,
                myShareAmount = shareAmount,
                isBusinessExpense = isBusiness,
                businessCategory = category,
                businessProject = project
            )
        )
        assertTrue(id > 0L)
        return id
    }

    /**
     * NULL-flag data: isNotMine x share kind x transaction type x currency x date bucket, with
     * rotating business membership, category (including none) and project (including none).
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
                            insertBusiness(
                                f,
                                amount = amount,
                                date = date,
                                type = type,
                                currency = currency,
                                isBusiness = idx % 5 != 0,
                                category = listOf<String?>("Travel", "Meals", null)[idx % 3],
                                project = listOf<String?>("Alpha", "Beta", null)[(idx / 2) % 3],
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
        Flagged(250.0, day(2027, 6, 10), SpendingExclusionReason.INSTALLMENT_REVERSED)
    )

    /**
     * Seeds [flaggedRows] as plain business PURCHASE rows (category and project set so every one of
     * the five queries sees them). With [flag] true each row is flagged through the coordinator; with
     * [flag] false (the reference database) the row is inserted with amount 0.0 and no flag, which is
     * exactly what a flagged row must contribute to a sum.
     */
    private suspend fun seedFlaggedRows(f: ExpenseDaoSpendingPredicateFixture, flag: Boolean) {
        for (row in flaggedRows) {
            val id = insertBusiness(f, amount = if (flag) row.amount else 0.0, date = row.date)
            if (flag) f.flag(id, row.reason)
        }
    }

    // ── Raw SQL execution and comparison ────────────────────────────────────

    private fun runRaw(f: ExpenseDaoSpendingPredicateFixture, sql: String): List<List<Any?>> {
        val literal = Regex(":(\\w+)").replace(sql) { m ->
            require(params.containsKey(m.groupValues[1])) { "unbound parameter ${m.value}" }
            params[m.groupValues[1]].toString()
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
    fun `S4d business queries are neutral over a NULL-flag fixture`() = runBlocking {
        val f = newFixture()
        seedBase(f)
        val failures = mutableListOf<String>()
        for (case in cases()) {
            val expected = normalized(runRaw(f, case.preEditSql))
            val actual = normalized(case.run(f.dao))
            if (expected != actual) failures += "${case.name}: expected=$expected actual=$actual"
            // Guard against a vacuous fixture: every query must see at least one row.
            if (expected.isEmpty()) failures += "${case.name}: neutrality fixture produced no rows"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ── (2) Flagged case ────────────────────────────────────────────────────

    @Test
    fun `S4d business sums drop exactly the flagged amounts and COUNT is unchanged`() = runBlocking {
        val full = newFixture()
        seedBase(full)
        seedFlaggedRows(full, flag = true)
        // Same data, flagged rows inserted with amount 0.0 and no flag.
        val reference = newFixture()
        seedBase(reference)
        seedFlaggedRows(reference, flag = false)

        val flaggedAmount = flaggedRows.sumOf { it.amount }
        val failures = mutableListOf<String>()
        for (case in cases()) {
            val converted = case.run(full.dao)
            val preEditOnFull = runRaw(full, case.preEditSql)
            val preEditOnReference = runRaw(reference, case.preEditSql)

            if (normalized(converted) != normalized(preEditOnReference)) {
                failures += "${case.name}: converted != reference with zeroed flagged rows " +
                    "(converted=${normalized(converted)} reference=${normalized(preEditOnReference)})"
            }
            // Non-vacuous: the verbatim pre-edit SQL does count the flagged amounts.
            if (normalized(preEditOnFull) == normalized(converted)) {
                failures += "${case.name}: flagged rows did not affect the pre-edit result"
            }
            val amountDrop = sumColumn(preEditOnFull, case.totalIdx) - sumColumn(converted, case.totalIdx)
            if (abs(amountDrop - flaggedAmount) > 1e-6) {
                failures += "${case.name}: total dropped by $amountDrop, expected $flaggedAmount"
            }
            case.countIdx?.let { idx ->
                val countDrop = sumColumn(preEditOnFull, idx) - sumColumn(converted, idx)
                if (abs(countDrop) > 1e-9) {
                    failures += "${case.name}: COUNT changed by $countDrop, expected unchanged (O2)"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ── AE-1 business ───────────────────────────────────────────────────────

    /**
     * A 600.00 declared business purchase (E1, flagged) in 2027-03 plus twelve 50.00 business
     * installment expenses 2027-04..2028-03. Returns the purchase id.
     */
    private suspend fun seedInstallmentScenario(f: ExpenseDaoSpendingPredicateFixture): Long {
        val purchaseId = insertBusiness(f, amount = 600.0, date = day(2027, 3, 10))
        f.flag(purchaseId, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        var year = 2027
        var month = 4
        repeat(12) {
            insertBusiness(f, amount = 50.0, date = day(year, month, 15))
            if (month == 12) {
                month = 1
                year++
            } else {
                month++
            }
        }
        return purchaseId
    }

    @Test
    fun `AE-1 business a declared purchase counts 0 and the twelve installments count 600 once`() = runBlocking {
        val f = newFixture()
        seedInstallmentScenario(f)
        val from = monthStart(2027, 3)
        val to = monthStart(2028, 4)

        assertEquals(600.0, f.dao.getTotalBusinessExpensesBetween(from, to)!!, 1e-9)

        val byCurrency = f.dao.getBusinessExpensesBetweenByCurrency(from, to).single()
        assertEquals("EUR", byCurrency.currency)
        assertEquals(600.0, byCurrency.total, 1e-9)
        // COUNT keeps including the flagged purchase (O2).
        assertEquals(13, byCurrency.txCount)

        val byCategory = f.dao.getBusinessExpensesByCategory(from, to).single()
        assertEquals("Travel", byCategory.businessCategory)
        assertEquals(600.0, byCategory.total, 1e-9)
        assertEquals(13, byCategory.count)

        val byProject = f.dao.getBusinessExpensesByProject(from, to).single()
        assertEquals("Alpha", byProject.businessProject)
        assertEquals(600.0, byProject.total, 1e-9)
        assertEquals(13, byProject.count)

        val categoryCurrency = f.dao.getBusinessCategoryCurrencyTotals(from, to).single()
        assertEquals("Travel", categoryCurrency.businessCategory)
        assertEquals("EUR", categoryCurrency.currency)
        assertEquals(600.0, categoryCurrency.total, 1e-9)
        assertEquals(13, categoryCurrency.txCount)
    }

    @Test
    fun `AE-1 business purchase month alone is 0 and each paid installment adds its own amount`() = runBlocking {
        val f = newFixture()
        seedInstallmentScenario(f)

        // March holds only the flagged purchase: zero in every sum, still one counted row.
        val marchFrom = monthStart(2027, 3)
        val marchTo = monthStart(2027, 4)
        assertEquals(0.0, f.dao.getTotalBusinessExpensesBetween(marchFrom, marchTo)!!, 1e-9)
        assertEquals(0.0, f.dao.getBusinessExpensesBetweenByCurrency(marchFrom, marchTo).single().total, 1e-9)
        assertEquals(1, f.dao.getBusinessExpensesBetweenByCurrency(marchFrom, marchTo).single().txCount)
        assertEquals(0.0, f.dao.getBusinessExpensesByCategory(marchFrom, marchTo).single().total, 1e-9)
        assertEquals(0.0, f.dao.getBusinessExpensesByProject(marchFrom, marchTo).single().total, 1e-9)
        assertEquals(0.0, f.dao.getBusinessCategoryCurrencyTotals(marchFrom, marchTo).single().total, 1e-9)

        // March..May: the purchase (0) plus the April and May installments.
        val to = monthStart(2027, 6)
        assertEquals(100.0, f.dao.getTotalBusinessExpensesBetween(marchFrom, to)!!, 1e-9)
        val byCurrency = f.dao.getBusinessExpensesBetweenByCurrency(marchFrom, to).single()
        assertEquals(100.0, byCurrency.total, 1e-9)
        assertEquals(3, byCurrency.txCount)
    }
}
