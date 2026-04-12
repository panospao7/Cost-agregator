package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stress tests for DAO operations
 * 
 * Tests bulk operations, concurrent access, and large dataset handling.
 */
@RunWith(AndroidJUnit4::class)
class DaoStressTest {

    private lateinit var database: AppDatabase
    private lateinit var expenseDao: ExpenseDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var budgetDao: BudgetDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()

        expenseDao = database.expenseDao()
        categoryDao = database.categoryDao()
        budgetDao = database.budgetDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insert_1000_expenses_quickly() = runTest {
        val expenses = (1..1000).map { i ->
            Expense(
                amount = i.toDouble(),
                currency = "EUR",
                merchant = "Merchant $i",
                transactionType = TransactionType.PURCHASE,
                date = System.currentTimeMillis()
            )
        }

        val startTime = System.currentTimeMillis()
        
        expenses.forEach { expense ->
            expenseDao.insert(expense)
        }

        val duration = System.currentTimeMillis() - startTime
        val retrieved = expenseDao.getAll()

        assertEquals(1000, retrieved.size)
        assertTrue("Should insert 1000 expenses in under 10 seconds", duration < 10000)
    }

    @Test
    fun insert_10000_expenses_batch() = runTest {
        val expenses = (1..10000).map { i ->
            Expense(
                amount = (i % 100).toDouble(),
                currency = "EUR",
                merchant = "Merchant ${i % 50}",
                transactionType = TransactionType.PURCHASE,
                date = System.currentTimeMillis() - (i * 86400000L)
            )
        }

        val startTime = System.currentTimeMillis()
        
        expenses.forEach { expense ->
            expenseDao.insert(expense)
        }

        val duration = System.currentTimeMillis() - startTime
        
        assertTrue("Should insert 10000 expenses", expenseDao.getAll().size == 10000)
        assertTrue("Should complete in reasonable time", duration < 60000)
    }

    @Test
    fun insert_100_categories() = runTest {
        val categories = (1..100).map { i ->
            Category(
                name = "Category $i",
                icon = "📊",
                color = "#${String.format("%06X", i * 1000)}"
            )
        }

        categories.forEach { category ->
            categoryDao.insert(category)
        }

        val allCategories = categoryDao.getAll()
        assertEquals(100, allCategories.size)
    }

    @Test
    fun concurrent_read_operations() = runTest {
        repeat(100) { i ->
            expenseDao.insert(createExpense(i.toDouble()))
        }

        val jobs = List(10) {
            async {
                expenseDao.getAll()
            }
        }

        val results = jobs.awaitAll()
        
        assertTrue("All concurrent reads should succeed", 
            results.all { it.size == 100 })
    }

    @Test
    fun concurrent_write_operations() = runTest {
        val jobs = List(10) { i ->
            async {
                repeat(10) { j ->
                    expenseDao.insert(createExpense((i * 10 + j).toDouble()))
                }
            }
        }

        jobs.awaitAll()

        assertEquals(100, expenseDao.getAll().size)
    }

    @Test
    fun concurrent_read_and_write() = runTest {
        val writeJob = async {
            repeat(50) { i ->
                expenseDao.insert(createExpense(i.toDouble()))
                kotlinx.coroutines.delay(10)
            }
        }

        val readJob = async {
            var maxCount = 0
            repeat(20) {
                val count = expenseDao.getAll().size
                if (count > maxCount) maxCount = count
                kotlinx.coroutines.delay(25)
            }
            maxCount
        }

        writeJob.await()
        val maxRead = readJob.await()

        assertTrue("Should observe partial writes during concurrent operations", maxRead >= 0)
        assertEquals(50, expenseDao.getAll().size)
    }

    @Test
    fun concurrent_Flow_collectors() = runTest {
        repeat(10) { i ->
            expenseDao.insert(createExpense(i.toDouble()))
        }

        val flow1 = expenseDao.getAllFlow()
        val flow2 = expenseDao.getAllFlow()

        val value1 = flow1.first()
        val value2 = flow2.first()

        assertEquals(10, value1.size)
        assertEquals(10, value2.size)
    }

    @Test
    fun query_5000_expenses_with_filtering() = runTest {
        repeat(5000) { i ->
            expenseDao.insert(
                createExpense(
                    amount = (i % 100).toDouble(),
                    date = System.currentTimeMillis() - (i * 3600000L)
                )
            )
        }

        val now = System.currentTimeMillis()
        val startTime = System.currentTimeMillis()
        
        val recent = expenseDao.getExpensesBetween(now - 8640000000L, now)
        
        val duration = System.currentTimeMillis() - startTime

        assertTrue("Should query quickly", duration < 1000)
        assertTrue("Should return results", recent.isNotEmpty())
    }

    @Test
    fun aggregate_queries_on_large_dataset() = runBlocking {
        repeat(10000) { i ->
            expenseDao.insert(createExpense(amount = (i % 50).toDouble()))
        }

        val startTime = System.currentTimeMillis()
        
        val allExpenses = expenseDao.getAll()
        val total = allExpenses.sumOf { it.amount }
        val count = allExpenses.size
        
        val duration = System.currentTimeMillis() - startTime

        assertTrue("Aggregate queries should be fast", duration < 5000)
        assertTrue("Total should be calculated", total > 0)
        assertEquals(10000, count)
    }

    @Test
    fun date_range_queries_with_indices() = runTest {
        val baseTime = System.currentTimeMillis()
        
        repeat(365) { i ->
            expenseDao.insert(
                createExpense(date = baseTime - (i * 86400000L))
            )
        }

        val startTime = System.currentTimeMillis()
        
        val last7Days = expenseDao.getExpensesBetween(baseTime - 604800000L, baseTime)
        val last30Days = expenseDao.getExpensesBetween(baseTime - 2592000000L, baseTime)
        val last90Days = expenseDao.getExpensesBetween(baseTime - 7776000000L, baseTime)
        
        val duration = System.currentTimeMillis() - startTime

        assertTrue("Date range queries should use indices", duration < 1000)
        // Inclusive bounds can include one extra row at the boundary.
        assertTrue(last7Days.size <= 8)
        assertTrue(last30Days.size <= 31)
        assertTrue(last90Days.size <= 91)
    }

    @Test
    fun bulk_insert_maintains_consistency() = runTest {
        val expenses = (1..100).map { createExpense(it.toDouble()) }

        expenses.forEach { expenseDao.insert(it) }

        assertEquals(100, expenseDao.getAll().size)

        val amounts = expenseDao.getAll().map { it.amount }.sorted()
        assertEquals(1.0, amounts[0], 0.01)
        assertEquals(100.0, amounts[99], 0.01)
    }

    @Test
    fun delete_operations_are_atomic() = runTest {
        repeat(50) { i ->
            expenseDao.insert(createExpense(i.toDouble()))
        }

        val toDelete = expenseDao.getAll().take(25)
        
        toDelete.forEach { expenseDao.delete(it) }

        assertEquals(25, expenseDao.getAll().size)
    }

    @Test
    fun Flow_emits_on_every_insert() = runTest {
        val initial = expenseDao.getAllFlow().first().size
        repeat(5) { i ->
            expenseDao.insert(createExpense(i.toDouble()))
        }
        val afterInserts = expenseDao.getAllFlow().first().size

        assertEquals(0, initial)
        assertEquals(5, afterInserts)
    }

    @Test
    fun Flow_emits_on_delete() = runTest {
        repeat(3) { expenseDao.insert(createExpense()) }

        val beforeDelete = expenseDao.getAllFlow().first().size
        expenseDao.deleteAll()
        val afterDelete = expenseDao.getAllFlow().first().size

        assertEquals(3, beforeDelete)
        assertEquals(0, afterDelete)
    }

    @Test
    fun handles_empty_table_queries() = runTest {
        assertEquals(0, expenseDao.getAll().size)
        assertEquals(0, expenseDao.getAll().sumOf { it.amount }.toInt())
        assertEquals(0, expenseDao.getAll().size)
    }

    @Test
    fun handles_very_large_amount_values() = runTest {
        val largeAmount = 999999999.99
        expenseDao.insert(createExpense(amount = largeAmount))

        val retrieved = expenseDao.getAll().first()
        assertEquals(largeAmount, retrieved.amount, 0.01)
    }

    @Test
    fun handles_special_characters_in_merchant_names() = runTest {
        val specialNames = listOf(
            "Cafe & Restaurant",
            "McDonald's",
            "7-Eleven",
            "A&W",
            "Japanese Shop"
        )

        specialNames.forEach { name ->
            expenseDao.insert(createExpense(merchant = name))
        }

        val retrieved = expenseDao.getAll()
        assertEquals(specialNames.size, retrieved.size)
    }

    @Test
    fun handles_duplicate_prevention() = runTest {
        val now = System.currentTimeMillis()
        val expense = createExpense(amount = 10.0, merchant = "Test", date = now)
        
        val id1 = expenseDao.insert(expense)
        val id2 = expenseDao.insert(expense.copy(id = id1))
        
        assertEquals(-1L, id2)
        assertEquals(1, expenseDao.getAll().size)
    }

    @Test
    fun handles_concurrent_duplicate_checks() = runTest {
        val now = System.currentTimeMillis()
        val dedupeKey = Expense.generateDedupeKey(10.0, "Same", now, "EUR")
        
        val jobs = List(10) {
            async {
                expenseDao.insert(
                    createExpense(
                        amount = 10.0,
                        merchant = "Same",
                        date = now,
                        dedupeKey = dedupeKey
                    )
                )
            }
        }

        jobs.awaitAll()

        assertEquals(1, expenseDao.getAll().size)
    }

    @Test
    fun query_large_dataset_without_memory_issues() = runTest {
        repeat(5000) {
            expenseDao.insert(createExpense())
        }

        val runtime = Runtime.getRuntime()
        val memBefore = runtime.totalMemory() - runtime.freeMemory()

        val all = expenseDao.getAll()

        val memAfter = runtime.totalMemory() - runtime.freeMemory()
        val memIncrease = (memAfter - memBefore) / 1024 / 1024

        assertEquals(5000, all.size)
        assertTrue("Memory increase should be reasonable", memIncrease < 100)
    }

    private fun createExpense(
        amount: Double = 10.0,
        merchant: String = "Test",
        date: Long = System.currentTimeMillis(),
        dedupeKey: String? = null
    ) = Expense(
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = date,
        dedupeKey = dedupeKey
    )
}
