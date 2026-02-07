package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
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
        expenseDao.updateCategory(id, 5L)

        val updated = expenseDao.getAll().first()
        assertEquals(5L, updated.categoryId)
    }

    @Test
    fun getExpensesBetweenReturnsCorrectRange() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(date = now - 86400000 * 2)) // 2 days ago
        expenseDao.insert(makeExpense(date = now - 86400000))     // 1 day ago
        expenseDao.insert(makeExpense(date = now))                 // now

        val between = expenseDao.getExpensesBetween(now - 86400000 * 3, now - 86400000 + 1)
        assertEquals(1, between.size) // only the 2-days-ago one, depending on exact timing
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
}
