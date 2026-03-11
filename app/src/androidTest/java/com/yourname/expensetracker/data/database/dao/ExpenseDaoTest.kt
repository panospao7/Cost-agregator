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
        date: Long = System.currentTimeMillis(),
        merchantKey: String? = null
    ) = Expense(
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = date,
        merchantKey = merchantKey
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

    // ──────────────────────────────────────────────────────────────────────────
    // merchantKey unification — P1 instrumented tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun getAllMerchantStats_groupsByMerchantKey() = runBlocking {
        val key = "sklavenitis"
        // Two expenses — different raw merchant string, same canonical merchantKey
        expenseDao.insert(makeExpense(amount = 30.0, merchant = "Σκλαβενίτης", merchantKey = key))
        expenseDao.insert(makeExpense(amount = 60.0, merchant = "ΣΚΛΑΒΕΝΙΤΗΣ",  merchantKey = key))

        val stats = expenseDao.getAllMerchantStats()

        // Must collapse to one row
        assertEquals(1, stats.size)
        assertEquals(key, stats.first().merchantName)
        assertEquals(90.0, stats.first().totalAmount, 0.01)
        assertEquals(2, stats.first().transactionCount)
    }

    @Test
    fun getTopMerchantsForPeriod_groupsByMerchantKey() = runBlocking {
        val key = "mymerchant"
        val now = System.currentTimeMillis()
        val start = now - 86_400_000L * 7
        val end   = now + 1L

        expenseDao.insert(makeExpense(amount = 20.0, merchant = "MyMerchant",   date = now - 1000, merchantKey = key))
        expenseDao.insert(makeExpense(amount = 40.0, merchant = "MYMERCHANT",   date = now - 2000, merchantKey = key))
        // A different merchant that should be separate
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Other",        date = now - 3000, merchantKey = "other"))

        val top = expenseDao.getTopMerchantsForPeriod(start, end, limit = 10)

        // Two distinct keys → two rows; the key group leads by total
        assertEquals(2, top.size)
        assertEquals(key, top.first().merchantName)
        assertEquals(60.0, top.first().totalAmount, 0.01)
    }

    @Test
    fun getExpensesWithCategoryFilteredFlow_filtersByMerchantKey() = runBlocking {
        val targetKey = "targetmerchant"
        val now = System.currentTimeMillis()

        expenseDao.insert(makeExpense(merchant = "Target",  merchantKey = targetKey, date = now - 1000))
        expenseDao.insert(makeExpense(merchant = "Other",   merchantKey = "other",   date = now - 2000))

        val results = expenseDao.getExpensesWithCategoryFilteredFlow(
            startMs     = now - 86_400_000L,
            endMs       = now + 1L,
            type        = null,
            categoryId  = null,
            merchantKey = targetKey
        ).first()

        assertEquals(1, results.size)
        assertEquals(targetKey, results.first().expense.merchantKey)
    }

    @Test
    fun searchMerchants_groupsByMerchantKey() = runBlocking {
        val key = "supermarket"
        // Two expenses — different raw names but same canonical key
        expenseDao.insert(makeExpense(merchant = "Supermarket A", merchantKey = key))
        expenseDao.insert(makeExpense(merchant = "Supermarket B", merchantKey = key))

        val suggestions = expenseDao.searchMerchants("Supermarket")

        // GROUP BY merchantKey → one suggestion row, count = 2
        assertEquals(1, suggestions.size)
        assertEquals(2, suggestions.first().txCount)
    }
}
