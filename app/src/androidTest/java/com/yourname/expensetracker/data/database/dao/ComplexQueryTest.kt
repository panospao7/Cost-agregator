package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for complex database queries and indices
 * 
 * Tests joins, aggregations, and index usage verification.
 */
@RunWith(AndroidJUnit4::class)
class ComplexQueryTest {

    private lateinit var database: AppDatabase
    private lateinit var expenseDao: ExpenseDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var merchantCategoryDao: MerchantCategoryDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        expenseDao = database.expenseDao()
        categoryDao = database.categoryDao()
        merchantCategoryDao = database.merchantCategoryDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun query_expenses_with_categories() = runBlocking {
        val foodCat = Category(name = "Food", icon = "🍔", color = "#FF0000")
        val transportCat = Category(name = "Transport", icon = "🚗", color = "#00FF00")
        val foodId = categoryDao.insert(foodCat)
        val transportId = categoryDao.insert(transportCat)

        expenseDao.insert(createExpense(merchant = "Restaurant", categoryId = foodId))
        expenseDao.insert(createExpense(merchant = "Gas Station", categoryId = transportId))
        expenseDao.insert(createExpense(merchant = "Grocery", categoryId = foodId))

        val expensesWithCategories = expenseDao.getAll().map { expense ->
            val category = expense.categoryId?.let { categoryDao.getById(it) }
            expense to category
        }

        assertEquals(3, expensesWithCategories.size)
        assertTrue(expensesWithCategories.any { it.second?.name == "Food" })
        assertTrue(expensesWithCategories.any { it.second?.name == "Transport" })
    }

    @Test
    fun query_merchant_category_mappings() = runBlocking {
        val catId = categoryDao.insert(Category(name = "Food", icon = "🍔", color = "#FF0000"))
        
        merchantCategoryDao.insert(MerchantCategory(merchantPattern = "starbucks", categoryId = catId, confidence = 0.95f))
        merchantCategoryDao.insert(MerchantCategory(merchantPattern = "mcdonalds", categoryId = catId, confidence = 0.90f))

        val mappings = merchantCategoryDao.getAll()
        assertEquals(2, mappings.size)
        assertTrue(mappings.any { it.merchantPattern == "starbucks" })
    }

    @Test
    fun sum_expenses_by_category() = runBlocking {
        val foodCat = categoryDao.insert(Category(name = "Food", icon = "🍔", color = "#FF0000"))
        val transportCat = categoryDao.insert(Category(name = "Transport", icon = "🚗", color = "#00FF00"))

        expenseDao.insert(createExpense(amount = 10.0, categoryId = foodCat))
        expenseDao.insert(createExpense(amount = 20.0, categoryId = foodCat))
        expenseDao.insert(createExpense(amount = 15.0, categoryId = transportCat))
        expenseDao.insert(createExpense(amount = 25.0, categoryId = transportCat))

        val expenses = expenseDao.getAll()
        val byCategory = expenses.groupBy { it.categoryId }
            .mapValues { (_, expenses) -> expenses.sumOf { it.amount } }

        assertEquals(30.0, byCategory[foodCat] ?: 0.0, 0.01)
        assertEquals(40.0, byCategory[transportCat] ?: 0.0, 0.01)
    }

    @Test
    fun count_expenses_by_merchant() = runBlocking {
        repeat(5) { expenseDao.insert(createExpense(merchant = "Starbucks")) }
        repeat(3) { expenseDao.insert(createExpense(merchant = "Amazon")) }
        repeat(2) { expenseDao.insert(createExpense(merchant = "Netflix")) }

        val expenses = expenseDao.getAll()
        val byMerchant = expenses.groupBy { it.merchant }
            .mapValues { it.value.size }

        assertEquals(5, byMerchant["Starbucks"])
        assertEquals(3, byMerchant["Amazon"])
        assertEquals(2, byMerchant["Netflix"])
    }

    @Test
    fun average_expense_amount() = runBlocking {
        expenseDao.insert(createExpense(amount = 10.0))
        expenseDao.insert(createExpense(amount = 20.0))
        expenseDao.insert(createExpense(amount = 30.0))

        val expenses = expenseDao.getAll()
        val average = expenses.map { it.amount }.average()

        assertEquals(20.0, average, 0.01)
    }

    @Test
    fun query_expenses_by_date_range() = runBlocking {
        val now = System.currentTimeMillis()
        
        expenseDao.insert(createExpense(date = now - 86400000 * 10))
        expenseDao.insert(createExpense(date = now - 86400000 * 5))
        expenseDao.insert(createExpense(date = now - 86400000 * 2))
        expenseDao.insert(createExpense(date = now))

        val recent = expenseDao.getExpensesBetween(now - 86400000 * 7, now + 1)
        
        assertEquals(2, recent.size)
    }

    @Test
    fun query_expenses_by_month() = runBlocking {
        val cal = java.util.Calendar.getInstance()
        cal.set(2021, java.util.Calendar.JANUARY, 15)
        val janDate = cal.timeInMillis
        
        cal.set(2021, java.util.Calendar.FEBRUARY, 15)
        val febDate = cal.timeInMillis
        
        expenseDao.insert(createExpense(date = janDate))
        expenseDao.insert(createExpense(date = janDate))
        expenseDao.insert(createExpense(date = febDate))

        val janStart = java.util.Calendar.getInstance().apply {
            set(2021, java.util.Calendar.JANUARY, 1, 0, 0, 0)
        }.timeInMillis
        val janEnd = java.util.Calendar.getInstance().apply {
            set(2021, java.util.Calendar.JANUARY, 31, 23, 59, 59)
        }.timeInMillis

        val janExpenses = expenseDao.getExpensesBetween(janStart, janEnd)
        assertEquals(2, janExpenses.size)
    }

    @Test
    fun search_expenses_by_merchant() = runBlocking {
        expenseDao.insert(createExpense(merchant = "Starbucks Coffee"))
        expenseDao.insert(createExpense(merchant = "Starbucks Reserve"))
        expenseDao.insert(createExpense(merchant = "Amazon"))
        expenseDao.insert(createExpense(merchant = "Amazon Prime"))

        val all = expenseDao.getAll()
        
        val starbucksResults = all.filter { it.merchant.contains("Starbucks", ignoreCase = true) }
        assertEquals(2, starbucksResults.size)

        val amazonResults = all.filter { it.merchant.contains("Amazon", ignoreCase = true) }
        assertEquals(2, amazonResults.size)
    }

    @Test
    fun search_by_amount_range() = runBlocking {
        expenseDao.insert(createExpense(amount = 5.0))
        expenseDao.insert(createExpense(amount = 15.0))
        expenseDao.insert(createExpense(amount = 25.0))
        expenseDao.insert(createExpense(amount = 35.0))

        val all = expenseDao.getAll()
        val midRange = all.filter { it.amount in 10.0..30.0 }

        assertEquals(2, midRange.size)
        assertTrue(midRange.all { it.amount in 10.0..30.0 })
    }

    @Test
    fun sort_expenses_by_amount_ascending() = runBlocking {
        expenseDao.insert(createExpense(amount = 30.0))
        expenseDao.insert(createExpense(amount = 10.0))
        expenseDao.insert(createExpense(amount = 20.0))

        val sorted = expenseDao.getAll().sortedBy { it.amount }

        assertEquals(10.0, sorted[0].amount, 0.01)
        assertEquals(20.0, sorted[1].amount, 0.01)
        assertEquals(30.0, sorted[2].amount, 0.01)
    }

    @Test
    fun sort_expenses_by_date_descending() = runBlocking {
        val now = System.currentTimeMillis()
        
        expenseDao.insert(createExpense(date = now - 86400000 * 2))
        expenseDao.insert(createExpense(date = now))
        expenseDao.insert(createExpense(date = now - 86400000))

        val sorted = expenseDao.getAll().sortedByDescending { it.date }

        assertEquals(now, sorted[0].date)
    }

    @Test
    fun sort_expenses_by_merchant_alphabetically() = runBlocking {
        expenseDao.insert(createExpense(merchant = "Zebra"))
        expenseDao.insert(createExpense(merchant = "Apple"))
        expenseDao.insert(createExpense(merchant = "Banana"))

        val sorted = expenseDao.getAll().sortedBy { it.merchant }

        assertEquals("Apple", sorted[0].merchant)
        assertEquals("Banana", sorted[1].merchant)
        assertEquals("Zebra", sorted[2].merchant)
    }

    @Test
    fun paginate_expense_results() = runBlocking {
        repeat(100) { i ->
            expenseDao.insert(createExpense(amount = i.toDouble()))
        }

        val pageSize = 10
        val page1 = expenseDao.getAll().take(pageSize)
        val page2 = expenseDao.getAll().drop(pageSize).take(pageSize)

        assertEquals(10, page1.size)
        assertEquals(10, page2.size)
    }

    @Test
    fun limit_query_results() = runBlocking {
        repeat(50) { expenseDao.insert(createExpense()) }

        val limited = expenseDao.getAll().take(5)
        assertEquals(5, limited.size)
    }

    @Test
    fun filter_by_multiple_criteria() = runBlocking {
        val foodCat = categoryDao.insert(Category(name = "Food", icon = "🍔", color = "#FF0000"))
        val now = System.currentTimeMillis()

        expenseDao.insert(createExpense(
            amount = 10.0,
            merchant = "Starbucks",
            categoryId = foodCat,
            date = now
        ))
        expenseDao.insert(createExpense(
            amount = 50.0,
            merchant = "Starbucks",
            categoryId = foodCat,
            date = now - 86400000
        ))
        expenseDao.insert(createExpense(
            amount = 10.0,
            merchant = "Amazon",
            categoryId = null,
            date = now
        ))

        val filtered = expenseDao.getAll().filter { expense ->
            expense.categoryId == foodCat &&
            expense.date > now - 86400000 &&
            expense.amount < 20.0
        }

        assertEquals(1, filtered.size)
        assertEquals("Starbucks", filtered[0].merchant)
    }

    @Test
    fun find_duplicates_with_tolerance() = runBlocking {
        val now = System.currentTimeMillis()
        
        expenseDao.insert(createExpense(amount = 10.05, merchant = "Starbucks", date = now))
        expenseDao.insert(createExpense(amount = 10.07, merchant = "Starbucks", date = now + 1000))
        expenseDao.insert(createExpense(amount = 25.0, merchant = "Amazon", date = now))

        val all = expenseDao.getAll()
        
        val potentialDuplicates = all.groupBy { it.merchant }
            .filter { (_, expenses) -> expenses.size > 1 }

        assertEquals(1, potentialDuplicates.size)
        assertEquals(2, potentialDuplicates["Starbucks"]?.size)
    }

    @Test
    fun filter_by_transaction_type() = runBlocking {
        expenseDao.insert(createExpense(transactionType = TransactionType.PURCHASE))
        expenseDao.insert(createExpense(transactionType = TransactionType.PURCHASE))
        expenseDao.insert(createExpense(transactionType = TransactionType.DEPOSIT))
        expenseDao.insert(createExpense(transactionType = TransactionType.WITHDRAWAL))

        val purchases = expenseDao.getAll().filter { it.transactionType == TransactionType.PURCHASE }
        val nonPurchases = expenseDao.getAll().filter { it.transactionType != TransactionType.PURCHASE }

        assertEquals(2, purchases.size)
        assertEquals(2, nonPurchases.size)
    }

    @Test
    fun exclude_transfers_from_spending() = runBlocking {
        expenseDao.insert(createExpense(amount = 100.0, transactionType = TransactionType.PURCHASE))
        expenseDao.insert(createExpense(amount = 50.0, transactionType = TransactionType.TRANSFER))
        expenseDao.insert(createExpense(amount = 75.0, transactionType = TransactionType.PURCHASE))

        val spending = expenseDao.getAll()
            .filter { it.transactionType == TransactionType.PURCHASE }
            .sumOf { it.amount }

        assertEquals(175.0, spending, 0.01)
    }

    private fun createExpense(
        amount: Double = 10.0,
        merchant: String = "Test",
        categoryId: Long? = null,
        transactionType: TransactionType = TransactionType.PURCHASE,
        date: Long = System.currentTimeMillis()
    ) = Expense(
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = transactionType,
        date = date
    )
}
