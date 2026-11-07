package com.yourname.expensetracker.data.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.query.SpendingInclusionSql
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * INV-1 parity: the SQL projection of the spending-inclusion rule ([SpendingInclusionSql]) must
 * equal the Kotlin projection ([Expense.effectiveAmount], [Expense.countsAsSpending]) over a
 * mixed fixture of reason (null / PURCHASE / REVERSED) x isNotMine x shared. Real in-memory Room
 * database; fixture rows are inserted with the column set directly (test setup only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpendingInclusionParityTest {

    private lateinit var db: AppDatabase

    private enum class Share { NONE, PERCENTAGE, AMOUNT }

    private val reasons: List<SpendingExclusionReason?> = listOf(
        null,
        SpendingExclusionReason.INSTALLMENT_PURCHASE,
        SpendingExclusionReason.INSTALLMENT_REVERSED
    )

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)

        var n = 0
        for (reason in reasons) {
            for (notMine in listOf(false, true)) {
                for (share in Share.values()) {
                    n++
                    val id = db.expenseDao().insert(
                        Expense(
                            amount = 100.0,
                            currency = "EUR",
                            merchant = "Parity Merchant $n",
                            merchantKey = "parity merchant $n",
                            transactionType = TransactionType.PURCHASE,
                            date = 1_700_000_000_000L + n * 86_400_000L,
                            isNotMine = notMine,
                            isSharedExpense = share != Share.NONE,
                            mySharePercentage = if (share == Share.PERCENTAGE) 25 else null,
                            myShareAmount = if (share == Share.AMOUNT) 30.0 else null,
                            spendingExclusionReason = reason?.name
                        )
                    )
                    assertTrue(id > 0L)
                }
            }
        }
        // A non-spending type with a flag-free row, to prove the type filter is independent.
        db.expenseDao().insert(
            Expense(
                amount = 5_000.0,
                currency = "EUR",
                merchant = "Salary",
                merchantKey = "salary",
                transactionType = TransactionType.DEPOSIT,
                date = 1_700_000_000_000L
            )
        )
        Unit
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun allExpenses(): List<Expense> = db.expenseDao().getAll()

    private fun queryDouble(sql: String): Double =
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getDouble(0)
        }

    private fun queryLong(sql: String): Long =
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getLong(0)
        }

    @Test
    fun `fixture covers every reason by ownership by share combination`() = runBlocking {
        val purchases = allExpenses().filter { it.transactionType == TransactionType.PURCHASE }
        assertEquals(3 * 2 * 3, purchases.size)
        assertEquals(setOf<String?>(null, "INSTALLMENT_PURCHASE", "INSTALLMENT_REVERSED"),
            purchases.map { it.spendingExclusionReason }.toSet())
    }

    @Test
    fun `SQL SUM of the canonical effective amount equals the Kotlin sum over every fixture`() = runBlocking {
        val purchases = allExpenses().filter { it.transactionType == TransactionType.PURCHASE }
        val expected = purchases.sumOf { it.effectiveAmount }

        val sql = queryDouble(
            "SELECT COALESCE(SUM(${SpendingInclusionSql.EFFECTIVE_AMOUNT}), 0.0) FROM expenses " +
                "WHERE ${ExpenseDao.SPENDING_TYPE_SQL}"
        )
        val aliased = queryDouble(
            "SELECT COALESCE(SUM(${SpendingInclusionSql.EFFECTIVE_AMOUNT_E}), 0.0) FROM expenses e " +
                "WHERE ${ExpenseDao.SPENDING_TYPE_E_SQL}"
        )

        assertEquals(expected, sql, 1e-9)
        assertEquals(expected, aliased, 1e-9)
    }

    @Test
    fun `the canonical effective amount equals Kotlin effectiveAmount row by row`() = runBlocking {
        val byId = allExpenses().associateBy { it.id }
        var checked = 0
        db.openHelper.readableDatabase.query(
            "SELECT e.id, ${SpendingInclusionSql.EFFECTIVE_AMOUNT_E} FROM expenses e"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val expense = byId.getValue(cursor.getLong(0))
                assertEquals(
                    "row ${expense.merchant} reason=${expense.spendingExclusionReason} " +
                        "notMine=${expense.isNotMine} shared=${expense.isSharedExpense}",
                    expense.effectiveAmount,
                    cursor.getDouble(1),
                    1e-9
                )
                checked++
            }
        }
        assertEquals(byId.size, checked)
    }

    @Test
    fun `SQL COUNT with the predicate equals the Kotlin countsAsSpending count`() = runBlocking {
        val purchases = allExpenses().filter { it.transactionType == TransactionType.PURCHASE }
        val expected = purchases.count { it.countsAsSpending }.toLong()

        val sql = queryLong(
            "SELECT COUNT(*) FROM expenses WHERE " +
                "${ExpenseDao.SPENDING_TYPE_SQL} " +
                "AND ${SpendingInclusionSql.SPENDING_PREDICATE}"
        )
        val aliased = queryLong(
            "SELECT COUNT(*) FROM expenses e WHERE " +
                "${ExpenseDao.SPENDING_TYPE_E_SQL} " +
                "AND ${SpendingInclusionSql.SPENDING_PREDICATE_E}"
        )

        // Only the unflagged, mine rows count: 3 share variants.
        assertEquals(3L, expected)
        assertEquals(expected, sql)
        assertEquals(expected, aliased)
    }

    @Test
    fun `SQL SUM and AVG over the predicate equal Kotlin over countsAsSpending rows`() = runBlocking {
        val counted = allExpenses()
            .filter { it.transactionType == TransactionType.PURCHASE && it.countsAsSpending }
        val expectedSum = counted.sumOf { it.effectiveAmount }
        val expectedAvg = counted.map { it.effectiveAmount }.average()

        val where = "${ExpenseDao.SPENDING_TYPE_SQL} " +
            "AND ${SpendingInclusionSql.SPENDING_PREDICATE}"
        val sqlSum = queryDouble(
            "SELECT COALESCE(SUM(${SpendingInclusionSql.EFFECTIVE_AMOUNT}), 0.0) FROM expenses WHERE $where"
        )
        val sqlAvg = queryDouble(
            "SELECT AVG(${SpendingInclusionSql.EFFECTIVE_AMOUNT}) FROM expenses WHERE $where"
        )

        assertEquals(expectedSum, sqlSum, 1e-9)
        assertEquals(expectedAvg, sqlAvg, 1e-9)
    }

    @Test
    fun `adding the predicate to a canonical SUM never changes the total`() = runBlocking {
        val withoutPredicate = queryDouble(
            "SELECT COALESCE(SUM(${SpendingInclusionSql.EFFECTIVE_AMOUNT}), 0.0) FROM expenses"
        )
        val withPredicate = queryDouble(
            "SELECT COALESCE(SUM(${SpendingInclusionSql.EFFECTIVE_AMOUNT}), 0.0) FROM expenses " +
                "WHERE ${SpendingInclusionSql.SPENDING_PREDICATE}"
        )

        assertEquals(withoutPredicate, withPredicate, 1e-9)
    }

    @Test
    fun `the predicate is exactly the documented text`() {
        assertEquals(
            "isNotMine = 0 AND spendingExclusionReason IS NULL",
            SpendingInclusionSql.SPENDING_PREDICATE
        )
        assertEquals(
            "e.isNotMine = 0 AND e.spendingExclusionReason IS NULL",
            SpendingInclusionSql.SPENDING_PREDICATE_E
        )
    }

    @Test
    fun `a flagged row contributes zero to effective and normalized amounts and does not count`() {
        val flagged = Expense(
            amount = 600.0,
            currency = "USD",
            merchant = "Flagged",
            transactionType = TransactionType.PURCHASE,
            date = 1L,
            baseAmount = 550.0,
            baseCurrency = "EUR",
            exchangeRateUsed = 0.9166667,
            spendingExclusionReason = SpendingExclusionReason.INSTALLMENT_PURCHASE.name
        )
        val plain = flagged.copy(spendingExclusionReason = null)

        assertEquals(0.0, flagged.effectiveAmount, 0.0)
        assertEquals(0.0, flagged.normalizedEffectiveAmount!!, 0.0)
        assertFalse(flagged.countsAsSpending)

        // Unflagged rows keep their previous semantics.
        assertEquals(600.0, plain.effectiveAmount, 0.0)
        assertEquals(550.0, plain.normalizedEffectiveAmount!!, 1e-9)
        assertTrue(plain.countsAsSpending)
    }

    @Test
    fun `a flagged row without a snapshot still normalizes to zero and an unflagged one stays null`() {
        val noSnapshot = Expense(
            amount = 600.0,
            currency = "USD",
            merchant = "No snapshot",
            transactionType = TransactionType.PURCHASE,
            date = 1L,
            baseAmount = 0.0,
            baseCurrency = "",
            exchangeRateUsed = 0.0
        )

        assertEquals(null, noSnapshot.normalizedEffectiveAmount)
        assertEquals(
            0.0,
            noSnapshot.copy(spendingExclusionReason = "INSTALLMENT_PURCHASE").normalizedEffectiveAmount!!,
            0.0
        )
    }

    @Test
    fun `the flag and the new source name round trip through Room as text`() = runBlocking {
        val id = db.expenseDao().insert(
            Expense(
                amount = 10.0,
                currency = "EUR",
                merchant = "Round trip",
                merchantKey = "round trip",
                transactionType = TransactionType.PURCHASE,
                date = 1_700_000_000_000L,
                source = ExpenseSource.INSTALLMENT_PLAN.name,
                spendingExclusionReason = SpendingExclusionReason.INSTALLMENT_REVERSED.name
            )
        )

        val read = db.expenseDao().getById(id)
        assertNotNull(read)
        assertEquals("INSTALLMENT_PLAN", read!!.source)
        assertEquals("INSTALLMENT_REVERSED", read.spendingExclusionReason)
        assertEquals(ExpenseSource.INSTALLMENT_PLAN, ExpenseSource.valueOf(read.source!!))
        assertEquals(
            SpendingExclusionReason.INSTALLMENT_REVERSED,
            SpendingExclusionReason.valueOf(read.spendingExclusionReason!!)
        )
    }

    // ── refund-netting S2a: AE-19 grid (INV-6, INV-9 neutrality) ─────────────────────────────
    // Additive only: every test below inserts its own rows into the fresh per-test database, so
    // the original fixture and the tests above are unchanged.

    private enum class Ownership(
        val isNotMine: Boolean,
        val shared: Boolean,
        val percentage: Int?,
        val shareAmount: Double?,
        val reason: String?
    ) {
        UNSHARED(false, false, null, null, null),
        NOT_MINE(true, false, null, null, null),
        NOT_MINE_SHARED_CONFLICT(true, true, 50, null, null),
        PCT_0(false, true, 0, null, null),
        PCT_33(false, true, 33, null, null),
        PCT_50(false, true, 50, null, null),
        PCT_100(false, true, 100, null, null),
        FIXED_SHARE_30(false, true, null, 30.0, null),
        FIXED_SHARE_0(false, true, null, 0.0, null),
        FIXED_SHARE_WINS_OVER_PCT(false, true, 50, 30.0, null),
        SHARED_WITHOUT_ANY_SHARE(false, true, null, null, null),
        FLAGGED_PURCHASE(false, false, null, null, "INSTALLMENT_PURCHASE"),
        FLAGGED_SHARED(false, true, 50, null, "INSTALLMENT_REVERSED"),
        FLAGGED_NOT_MINE(true, false, null, null, "INSTALLMENT_PURCHASE")
    }

    /** Refund values for a gross [amount]: negative (defensive), none, tiny, partial, just under, equal, over. */
    private fun refundsFor(amount: Double): List<Double> =
        listOf(-5.0, 0.0, 0.01, amount * 0.4, amount - 0.01, amount, amount + 1.0)

    /** Inserts the cross product ownership x refund x amount x type; returns the number of rows. */
    private suspend fun insertRefundGrid(): Int {
        var n = 0
        for (ownership in Ownership.values()) {
            for (amount in listOf(100.0, 37.37)) {
                for (refund in refundsFor(amount)) {
                    for (type in listOf(TransactionType.PURCHASE, TransactionType.DEPOSIT)) {
                        n++
                        val id = db.expenseDao().insert(
                            Expense(
                                amount = amount,
                                currency = "EUR",
                                merchant = "Grid Merchant $n",
                                merchantKey = "grid merchant $n",
                                transactionType = type,
                                date = 1_700_000_000_000L + n * 60_000L,
                                isNotMine = ownership.isNotMine,
                                isSharedExpense = ownership.shared,
                                mySharePercentage = ownership.percentage,
                                myShareAmount = ownership.shareAmount,
                                spendingExclusionReason = ownership.reason,
                                refundNettedAmount = refund
                            )
                        )
                        assertTrue(id > 0L)
                    }
                }
            }
        }
        return n
    }

    private fun assertOuterEqualsKotlinOnEveryRow(gridRows: Int) = runBlocking {
        val byId = allExpenses().associateBy { it.id }
        assertEquals(19 + gridRows, byId.size) // original 18 purchases + the salary deposit, plus the grid
        val nettedChecked = intArrayOf(0)
        for ((label, sql) in listOf(
            "EFFECTIVE_AMOUNT" to "SELECT id, ${SpendingInclusionSql.EFFECTIVE_AMOUNT} FROM expenses",
            "EFFECTIVE_AMOUNT_E" to "SELECT e.id, ${SpendingInclusionSql.EFFECTIVE_AMOUNT_E} FROM expenses e"
        )) {
            var checked = 0
            db.openHelper.readableDatabase.query(sql).use { cursor ->
                while (cursor.moveToNext()) {
                    val expense = byId.getValue(cursor.getLong(0))
                    // Exact equality (delta 0.0): the SQL projection must be bit-identical.
                    assertEquals(
                        "$label type=${expense.transactionType} amount=${expense.amount} " +
                            "r=${expense.refundNettedAmount} notMine=${expense.isNotMine} " +
                            "shared=${expense.isSharedExpense} pct=${expense.mySharePercentage} " +
                            "fixed=${expense.myShareAmount} reason=${expense.spendingExclusionReason}",
                        expense.effectiveAmount,
                        cursor.getDouble(1),
                        0.0
                    )
                    if (expense.refundNettedAmount > 0.0) nettedChecked[0]++
                    checked++
                }
            }
            assertEquals(byId.size, checked)
        }
        // Non-vacuity: 5 of the 7 refund values are positive, counted once per constant (2 passes).
        assertEquals(2 * 5 * 14 * 2 * 2, nettedChecked[0])
    }

    @Test
    fun `AE19 INV6 the outer SQL constants equal Kotlin effectiveAmount exactly over the refund grid`() = runBlocking {
        val gridRows = insertRefundGrid()
        assertEquals(14 * 2 * 7 * 2, gridRows)

        assertOuterEqualsKotlinOnEveryRow(gridRows)
    }

    @Test
    fun `AE19 INV6 hand computed examples pin the SQL branches independently of the Kotlin getter`() = runBlocking {
        fun row(
            name: String,
            amount: Double,
            refund: Double,
            notMine: Boolean = false,
            shared: Boolean = false,
            pct: Int? = null,
            fixed: Double? = null,
            reason: String? = null
        ) = Expense(
            amount = amount, currency = "EUR", merchant = name, merchantKey = name.lowercase(),
            transactionType = TransactionType.PURCHASE, date = 1_700_000_000_000L + name.hashCode(),
            isNotMine = notMine, isSharedExpense = shared, mySharePercentage = pct, myShareAmount = fixed,
            spendingExclusionReason = reason, refundNettedAmount = refund
        )
        // name -> (row, expected effective amount), all values hand computed from spec 1.4 / AE-1, 7, 8, 9.
        val cases = listOf(
            row("plain partial", 100.0, 40.0) to 60.0,                       // AE-1
            row("plain equal", 100.0, 100.0) to 0.0,                          // AE-2: refund covers all
            row("plain over", 100.0, 101.0) to 0.0,                           // never negative
            row("plain none", 100.0, 0.0) to 100.0,                           // neutral
            row("pct 50", 100.0, 40.0, shared = true, pct = 50) to 30.0,      // AE-7: (100-40)*50/100
            row("fixed 30", 100.0, 40.0, shared = true, fixed = 30.0) to 18.0, // AE-8: 30*60/100
            row("fixed equal", 100.0, 100.0, shared = true, fixed = 30.0) to 0.0,
            row("not mine", 80.0, 80.0, notMine = true) to 0.0,               // AE-9
            row("not mine partial", 80.0, 30.0, notMine = true) to 0.0,
            row("flagged", 100.0, 40.0, reason = "INSTALLMENT_PURCHASE") to 0.0,
            row("negative r", 100.0, -5.0) to 100.0                           // r <= 0 takes today's expression
        )
        cases.forEach { (expense, _) -> db.expenseDao().insert(expense) }
        val expectedByMerchant = cases.associate { (expense, expected) -> expense.merchant to expected }

        var checked = 0
        db.openHelper.readableDatabase.query(
            "SELECT merchant, ${SpendingInclusionSql.EFFECTIVE_AMOUNT} FROM expenses " +
                "WHERE merchant IN (${expectedByMerchant.keys.joinToString(",") { "'$it'" }})"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                assertEquals(name, expectedByMerchant.getValue(name), cursor.getDouble(1), 0.0)
                checked++
            }
        }
        assertEquals(cases.size, checked)
        // The Kotlin getter agrees with the same hand-computed numbers.
        cases.forEach { (expense, expected) -> assertEquals(expense.merchant, expected, expense.effectiveAmount, 0.0) }
    }

    @Test
    fun `INV6 neutrality for refundNettedAmount at or below zero the outer constant equals the frozen pre-netting constant exactly`() = runBlocking {
        insertRefundGrid()
        val byId = allExpenses().associateBy { it.id }

        var neutralRows = 0
        var nettedRowsThatMoved = 0
        db.openHelper.readableDatabase.query(
            "SELECT id, ${SpendingInclusionSql.EFFECTIVE_AMOUNT}, " +
                "${SpendingInclusionSql.EFFECTIVE_AMOUNT_PRE_REFUND_NETTING} FROM expenses"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val expense = byId.getValue(cursor.getLong(0))
                val outer = cursor.getDouble(1)
                val pre = cursor.getDouble(2)
                if (expense.refundNettedAmount <= 0.0) {
                    assertEquals(
                        "r=${expense.refundNettedAmount} amount=${expense.amount} reason=${expense.spendingExclusionReason}",
                        pre, outer, 0.0
                    )
                    neutralRows++
                } else if (outer != pre) {
                    nettedRowsThatMoved++
                }
            }
        }
        // The whole original fixture (19 rows) plus the r <= 0 part of the grid took the neutral path.
        assertTrue("neutral rows: $neutralRows", neutralRows >= 19 + 14 * 2 * 2 * 2)
        // Control: the constants are not trivially equal; positive refunds move unflagged mine rows.
        assertTrue("moved rows: $nettedRowsThatMoved", nettedRowsThatMoved > 50)

        // Same statement in SQL, and for the aliased constant against its inner twin.
        assertEquals(
            0L,
            queryLong(
                "SELECT COUNT(*) FROM expenses WHERE refundNettedAmount <= 0.0 AND " +
                    "(${SpendingInclusionSql.EFFECTIVE_AMOUNT}) IS NOT (${SpendingInclusionSql.EFFECTIVE_AMOUNT_PRE_REFUND_NETTING})"
            )
        )
        assertEquals(
            0L,
            queryLong(
                "SELECT COUNT(*) FROM expenses e WHERE e.refundNettedAmount <= 0.0 AND " +
                    "(${SpendingInclusionSql.EFFECTIVE_AMOUNT_E}) IS NOT " +
                    "(CASE WHEN e.spendingExclusionReason IS NOT NULL THEN 0.0 ELSE (${ExpenseDao.EFFECTIVE_AMOUNT_E_SQL}) END)"
            )
        )
    }

    @Test
    fun `INV6 neutrality the original fixture sums are identical under the outer and the frozen constant`() = runBlocking {
        val outer = queryDouble(
            "SELECT COALESCE(SUM(${SpendingInclusionSql.EFFECTIVE_AMOUNT}), 0.0) FROM expenses"
        )
        val pre = queryDouble(
            "SELECT COALESCE(SUM(${SpendingInclusionSql.EFFECTIVE_AMOUNT_PRE_REFUND_NETTING}), 0.0) FROM expenses"
        )

        assertEquals(pre, outer, 0.0)
        // Hand computed: 3 unflagged mine rows (100 + 25 + 30) + 5000 salary deposit.
        assertEquals(5155.0, outer, 1e-9)
    }
}
