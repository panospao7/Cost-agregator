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
}
