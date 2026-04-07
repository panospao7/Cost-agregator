package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpenseDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var expenseDao: ExpenseDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        expenseDao = database.expenseDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun makeExpense(
        amount: Double = 10.0,
        merchant: String = "Test",
        date: Long = System.currentTimeMillis()
    ) = Expense(
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = date
    )

    @Test
    fun insertAndRetrieve() = runBlocking {
        val expense = makeExpense()
        val id = expenseDao.insert(expense)
        assertTrue(id > 0)

        val all = expenseDao.getAll()
        assertEquals(1, all.size)
        assertEquals(10.0, all[0].amount, 0.01)
    }

    @Test
    fun getAllFlowEmitsUpdates() = runBlocking {
        expenseDao.insert(makeExpense(merchant = "A"))
        expenseDao.insert(makeExpense(merchant = "B"))

        val expenses = expenseDao.getAllFlow().first()
        assertEquals(2, expenses.size)
    }

    @Test
    fun deleteExpense() = runBlocking {
        val expense = makeExpense()
        val id = expenseDao.insert(expense)
        val inserted = expenseDao.getAll().first()
        expenseDao.delete(inserted)
        assertEquals(0, expenseDao.getAll().size)
    }

    @Test
    fun deleteAllExpenses() = runBlocking {
        expenseDao.insert(makeExpense(merchant = "A"))
        expenseDao.insert(makeExpense(merchant = "B"))
        expenseDao.deleteAll()
        assertEquals(0, expenseDao.getAll().size)
    }

    @Test
    fun getTotalSpentFlowOnlyCountsPurchases() = runBlocking {
        expenseDao.insert(makeExpense(amount = 10.0))
        expenseDao.insert(Expense(
            amount = 100.0, currency = "EUR", merchant = "Deposit",
            transactionType = TransactionType.DEPOSIT,
            date = System.currentTimeMillis()
        ))

        val total = expenseDao.getTotalSpentFlow().first()
        assertEquals(10.0, total!!, 0.01)
    }

    @Test
    fun isDuplicateDetectsWithinWindow() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop", date = now))

        val isDupe = expenseDao.isDuplicate(10.0, "Shop", now, 300000)
        assertTrue(isDupe)
    }

    @Test
    fun isDuplicateIgnoresOutsideWindow() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop", date = now - 600000))

        val isDupe = expenseDao.isDuplicate(10.0, "Shop", now, 300000)
        assertFalse(isDupe)
    }

    @Test
    fun isDuplicateIgnoresDifferentMerchant() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop A", date = now))

        val isDupe = expenseDao.isDuplicate(10.0, "Shop B", now, 300000)
        assertFalse(isDupe)
    }

    @Test
    fun isDuplicateIgnoresDifferentAmount() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop", date = now))

        val isDupe = expenseDao.isDuplicate(20.0, "Shop", now, 300000)
        assertFalse(isDupe)
    }

    @Test
    fun updateCategory() = runBlocking {
        val id = expenseDao.insert(makeExpense())
        val categoryId = database.categoryDao().insert(
            Category(name = "Food", icon = "🍔", color = "#FF0000")
        )
        expenseDao.updateCategory(id, categoryId)

        val updated = expenseDao.getAll().first()
        assertEquals(categoryId, updated.categoryId)
    }

    @Test
    fun getExpensesBetweenReturnsCorrectRange() = runBlocking {
        val now = 1_700_000_000_000L
        val twoDaysAgo = now - 2L * 86_400_000L
        val oneDayAgo = now - 86_400_000L
        expenseDao.insert(makeExpense(date = twoDaysAgo))
        expenseDao.insert(makeExpense(date = oneDayAgo))
        expenseDao.insert(makeExpense(date = now))

        val between = expenseDao.getExpensesBetween(now - 3L * 86_400_000L, oneDayAgo + 1L)
        assertEquals(2, between.size)
    }

    @Test
    fun purchaseCountOnlyCountsPurchases() = runBlocking {
        expenseDao.insert(makeExpense())
        expenseDao.insert(Expense(
            amount = 50.0, currency = "EUR", merchant = "ATM",
            transactionType = TransactionType.WITHDRAWAL,
            date = System.currentTimeMillis()
        ))

        assertEquals(1, expenseDao.getPurchaseCount())
    }

    @Test
    fun ignoreConflictOnDuplicateInsert() = runBlocking {
        val expense = makeExpense()
        val id1 = expenseDao.insert(expense)
        val id2 = expenseDao.insert(expense.copy(id = id1)) // Same ID
        // IGNORE strategy: id2 should be -1 (not inserted)
        assertEquals(-1L, id2)
        assertEquals(1, expenseDao.getAll().size)
    }

    // ── Shared-expense regression tests (A.1 effectiveAmount standardization) ────────────────

    /**
     * A shared purchase with an explicit myShareAmount should contribute only myShareAmount
     * to the total, not the full posted amount.
     */
    @Test
    fun getTotalSpent_sharedExpense_withExplicitShareAmount_usesShareAmount() = runBlocking {
        val now = System.currentTimeMillis()
        // Full bill = €100, user's share = €40
        expenseDao.insert(Expense(
            amount = 100.0, currency = "EUR", merchant = "Restaurant",
            transactionType = TransactionType.PURCHASE, date = now,
            isSharedExpense = true, myShareAmount = 40.0
        ))
        val total = expenseDao.getTotalSpentFlow().first()
        assertEquals("Shared expense with explicit share amount should contribute 40.0",
            40.0, total!!, 0.01)
    }

    /**
     * A shared purchase with mySharePercentage should contribute the proportional amount.
     */
    @Test
    fun getTotalSpent_sharedExpense_withSharePercentage_usesProportionalAmount() = runBlocking {
        val now = System.currentTimeMillis()
        // Full bill = €80, user's share = 50% → €40
        expenseDao.insert(Expense(
            amount = 80.0, currency = "EUR", merchant = "Cafe",
            transactionType = TransactionType.PURCHASE, date = now,
            isSharedExpense = true, mySharePercentage = 50
        ))
        val total = expenseDao.getTotalSpentFlow().first()
        assertEquals("Shared expense with 50% share should contribute 40.0",
            40.0, total!!, 0.01)
    }

    /**
     * An isNotMine purchase must contribute 0.0 to all totals (not the full amount).
     */
    @Test
    fun getTotalSpent_isNotMine_contributesZero() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(Expense(
            amount = 200.0, currency = "EUR", merchant = "Someone Else",
            transactionType = TransactionType.PURCHASE, date = now,
            isNotMine = true
        ))
        // isNotMine=1 rows are filtered out by the WHERE isNotMine=0 clause
        val total = expenseDao.getTotalSpentFlow().first()
        // Should be null (no qualifying rows) or 0, not 200
        val safeTotal = total ?: 0.0
        assertEquals("isNotMine expense must not contribute to totals", 0.0, safeTotal, 0.01)
    }

    /**
     * A deposit (income) should not be affected by effective amount; getTotalDepositsForPeriod
     * should use the effective amount for shared deposit rows.
     */
    @Test
    fun getTotalDepositsForPeriod_normalDeposit_usesFullAmount() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(Expense(
            amount = 1000.0, currency = "EUR", merchant = "Salary",
            transactionType = TransactionType.DEPOSIT, date = now
        ))
        val start = now - 60_000L
        val end = now + 60_000L
        val total = expenseDao.getTotalDepositsForPeriod(start, end)
        assertEquals("Normal deposit should contribute full amount", 1000.0, total, 0.01)
    }

    /**
     * getTotalForPeriod with mixed regular, shared (share amount), shared (percentage), and
     * isNotMine rows — verifies the aggregate uses effective amounts throughout.
     */
    @Test
    fun getTotalForPeriod_mixedOwnership_sumsEffectiveAmounts() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        // Regular purchase: contributes full 50.0
        expenseDao.insert(makeExpense(amount = 50.0).copy(date = now))
        // Shared with explicit share: contributes 30.0 (not 100.0)
        expenseDao.insert(Expense(
            amount = 100.0, currency = "EUR", merchant = "SharedFixed",
            transactionType = TransactionType.PURCHASE, date = now,
            isSharedExpense = true, myShareAmount = 30.0
        ))
        // Shared with percentage: contributes 25.0 (50% of 50.0)
        expenseDao.insert(Expense(
            amount = 50.0, currency = "EUR", merchant = "SharedPct",
            transactionType = TransactionType.PURCHASE, date = now,
            isSharedExpense = true, mySharePercentage = 50
        ))
        // isNotMine: contributes 0 (filtered by WHERE isNotMine=0)
        expenseDao.insert(Expense(
            amount = 999.0, currency = "EUR", merchant = "NotMine",
            transactionType = TransactionType.PURCHASE, date = now,
            isNotMine = true
        ))

        val total = expenseDao.getTotalForPeriod(start, end)
        // Expected: 50.0 + 30.0 + 25.0 = 105.0
        assertEquals("Mixed ownership rows should sum to 105.0 effective total",
            105.0, total, 0.01)
    }

    /**
     * getCategorySpentInPeriod — shared-expense budget regression.
     * Verifies that budget spend calculations use effective amounts for shared rows.
     */
    @Test
    fun getCategorySpentInPeriod_sharedExpense_usesEffectiveAmount() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        val categoryId = database.categoryDao().insert(
            Category(name = "Food", icon = "🍽", color = "#FF5733")
        )

        // Shared purchase with explicit share amount
        expenseDao.insert(Expense(
            amount = 120.0, currency = "EUR", merchant = "Restaurant",
            transactionType = TransactionType.PURCHASE, date = now,
            categoryId = categoryId,
            isSharedExpense = true, myShareAmount = 40.0
        ))
        // isNotMine purchase in same category — must not add to budget spend
        expenseDao.insert(Expense(
            amount = 80.0, currency = "EUR", merchant = "CafeMine",
            transactionType = TransactionType.PURCHASE, date = now,
            categoryId = categoryId,
            isNotMine = true
        ))

        val spent = expenseDao.getCategorySpentInPeriod(categoryId, start, end)
        assertEquals("Budget spend for category must use effectiveAmount: 40.0",
            40.0, spent, 0.01)
    }

    /**
     * getAmountsForPercentileCalc — percentile inputs must be effective amounts, not raw.
     */
    @Test
    fun getAmountsForPercentileCalc_sharedExpense_returnsEffectiveAmounts() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        // Regular purchase: 100.0
        expenseDao.insert(makeExpense(amount = 100.0).copy(date = now))
        // Shared with explicit share: effective = 40.0 (not 200.0)
        expenseDao.insert(Expense(
            amount = 200.0, currency = "EUR", merchant = "SharedShop",
            transactionType = TransactionType.PURCHASE, date = now,
            isSharedExpense = true, myShareAmount = 40.0
        ))

        val amounts = expenseDao.getAmountsForPercentileCalc(start, end)
        assertEquals("Should have 2 amounts (both rows are mine)", 2, amounts.size)
        // Sorted ascending: [40.0, 100.0]
        assertEquals("First amount should be 40.0 (effective share)", 40.0, amounts[0], 0.01)
        assertEquals("Second amount should be 100.0 (full ownership)", 100.0, amounts[1], 0.01)
    }

    /**
     * getTotalBusinessExpensesBetween — must use effective amount for shared business rows.
     */
    @Test
    fun getTotalBusinessExpensesBetween_sharedExpense_usesEffectiveAmount() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        // Regular business expense: contributes 150.0
        expenseDao.insert(Expense(
            amount = 150.0, currency = "EUR", merchant = "Client Lunch",
            transactionType = TransactionType.PURCHASE, date = now,
            isBusinessExpense = true
        ))
        // Shared business expense: contributes 25.0 (50% of 50.0), not 50.0
        expenseDao.insert(Expense(
            amount = 50.0, currency = "EUR", merchant = "Conference",
            transactionType = TransactionType.PURCHASE, date = now,
            isBusinessExpense = true,
            isSharedExpense = true, mySharePercentage = 50
        ))
        // isNotMine business expense: contributes 0.0
        expenseDao.insert(Expense(
            amount = 300.0, currency = "EUR", merchant = "Colleague Expense",
            transactionType = TransactionType.PURCHASE, date = now,
            isBusinessExpense = true, isNotMine = true
        ))

        val total = expenseDao.getTotalBusinessExpensesBetween(start, end) ?: 0.0
        // Expected: 150.0 + 25.0 + 0.0 = 175.0
        assertEquals("Business expense total must use effective amounts: 175.0",
            175.0, total, 0.01)
    }
}
