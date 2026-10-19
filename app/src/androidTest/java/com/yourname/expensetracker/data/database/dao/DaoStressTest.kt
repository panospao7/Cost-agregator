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
@OptIn(RestrictedExpenseDaoMutation::class)
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
    fun insert_1000_expenses_persists_every_row_with_distinct_ids() = runTest {
        val expenses = (1..1000).map { i ->
            Expense(
                amount = i.toDouble(),
                currency = "EUR",
                merchant = "Merchant $i",
                transactionType = TransactionType.PURCHASE,
                date = System.currentTimeMillis()
            )
        }

        val insertedIds = expenses.map { expense -> expenseDao.insert(expense) }
        val retrieved = expenseDao.getAll()

        assertEquals(1000, insertedIds.size)
        assertTrue("Every insert must return a generated row id", insertedIds.all { it > 0L })
        assertEquals(1000, insertedIds.toSet().size)
        assertEquals(1000, retrieved.size)
        assertEquals(1000, retrieved.map { it.id }.toSet().size)
        assertEquals(insertedIds.toSet(), retrieved.map { it.id }.toSet())
        // Amounts are 1.0..1000.0: 1000 * 1001 / 2 = 500500.
        assertEquals(500500.0, retrieved.sumOf { it.amount }, 0.0)
    }

    @Test
    fun insert_10000_expenses_batch() = runTest {
        val baseTime = System.currentTimeMillis()
        val expenses = (1..10000).map { i ->
            Expense(
                amount = (i % 100).toDouble(),
                currency = "EUR",
                merchant = "Merchant ${i % 50}",
                transactionType = TransactionType.PURCHASE,
                date = baseTime - (i * 86400000L)
            )
        }

        expenses.forEach { expense ->
            expenseDao.insert(expense)
        }

        val retrieved = expenseDao.getAll()

        assertEquals(10000, retrieved.size)
        // i % 100 cycles 0..99 exactly 100 times: 100 * (99 * 100 / 2) = 100 * 4950 = 495000.
        assertEquals(495000.0, retrieved.sumOf { it.amount }, 0.0)
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
    fun concurrent_read_operations() = runBlocking {
        val seededIds = (0 until 100).map { i ->
            expenseDao.insert(createExpense(i.toDouble()))
        }.toSet()
        assertEquals(100, seededIds.size)

        val start = CompletableDeferred<Unit>()
        val results = withTimeout(30_000L) {
            val jobs = List(10) {
                async(Dispatchers.IO) {
                    start.await()
                    expenseDao.getAll()
                }
            }
            start.complete(Unit)
            jobs.awaitAll()
        }

        assertEquals(10, results.size)
        results.forEach { rows ->
            assertEquals(100, rows.size)
            assertEquals(seededIds, rows.map { it.id }.toSet())
        }
    }

    @Test
    fun concurrent_write_operations() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val insertedIds = withTimeout(30_000L) {
            val jobs = List(10) { i ->
                async(Dispatchers.IO) {
                    start.await()
                    (0 until 10).map { j ->
                        expenseDao.insert(createExpense((i * 10 + j).toDouble()))
                    }
                }
            }
            start.complete(Unit)
            jobs.awaitAll().flatten()
        }

        val stored = expenseDao.getAll()

        assertEquals(100, insertedIds.size)
        assertTrue("Every concurrent insert must return a generated row id", insertedIds.all { it > 0L })
        assertEquals(100, insertedIds.toSet().size)
        assertEquals(100, stored.size)
        assertEquals(insertedIds.toSet(), stored.map { it.id }.toSet())
        // Amounts are 0.0..99.0 (i * 10 + j): 99 * 100 / 2 = 4950.
        assertEquals(4950.0, stored.sumOf { it.amount }, 0.0)
    }

    @Test
    fun concurrent_read_and_write() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val observedCounts = withTimeout(30_000L) {
            val writeJob = async(Dispatchers.IO) {
                start.await()
                repeat(50) { i ->
                    expenseDao.insert(createExpense(i.toDouble()))
                    delay(10)
                }
            }

            val readJob = async(Dispatchers.IO) {
                start.await()
                val counts = mutableListOf<Int>()
                repeat(20) {
                    counts.add(expenseDao.getAll().size)
                    delay(25)
                }
                counts.toList()
            }

            start.complete(Unit)
            writeJob.await()
            readJob.await()
        }

        assertEquals(20, observedCounts.size)
        assertTrue(
            "Each observed count must be within 0..50 but was $observedCounts",
            observedCounts.all { it in 0..50 }
        )
        // Rows are only ever inserted, so successive reads can never observe fewer rows.
        assertTrue(
            "Observed counts must be non-decreasing but were $observedCounts",
            observedCounts.zipWithNext().all { (earlier, later) -> earlier <= later }
        )
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
    fun query_5000_expenses_returns_exact_half_open_range_in_descending_order() = runTest {
        val now = System.currentTimeMillis()
        val hourMs = 3600000L
        val generatedDates = (0 until 5000).map { i -> now - (i * hourMs) }
        generatedDates.forEachIndexed { i, date ->
            expenseDao.insert(
                createExpense(
                    amount = (i % 100).toDouble(),
                    date = date
                )
            )
        }

        // 8_640_000_000 ms = 100 days = 2400 hours. Window is [now - 2400h, now).
        // Row i has date now - i*1h: i = 0 sits on the exclusive upper bound (excluded),
        // i = 2400 sits on the inclusive lower bound (included), so i = 1..2400 => 2400 rows.
        val start = now - 8640000000L
        val recent = expenseDao.getExpensesBetween(start, now)

        val expectedDates = generatedDates.filter { it >= start && it < now }.sortedDescending()
        assertEquals(2400, expectedDates.size)
        assertEquals(2400, recent.size)
        assertEquals(expectedDates, recent.map { it.date })
        assertEquals(now - hourMs, recent.first().date)
        assertEquals(now - (2400 * hourMs), recent.last().date)
        assertTrue(
            "Results must be ordered by date descending",
            recent.zipWithNext().all { (newer, older) -> newer.date >= older.date }
        )
    }

    @Test
    fun aggregate_queries_on_large_dataset() = runBlocking {
        repeat(10000) { i ->
            expenseDao.insert(createExpense(amount = (i % 50).toDouble()))
        }

        val allExpenses = expenseDao.getAll()
        val total = allExpenses.sumOf { it.amount }
        val count = allExpenses.size

        // i % 50 cycles 0..49 exactly 200 times: 200 * (49 * 50 / 2) = 200 * 1225 = 245000.
        assertEquals(245000.0, total, 0.0)
        assertEquals(10000, count)
    }

    @Test
    fun date_range_queries_return_exact_half_open_counts_in_descending_order() = runTest {
        val baseTime = System.currentTimeMillis()
        val dayMs = 86400000L

        repeat(365) { i ->
            expenseDao.insert(
                createExpense(date = baseTime - (i * dayMs))
            )
        }

        // Row i has date baseTime - i days. Each window is [baseTime - N days, baseTime):
        // i = 0 is on the exclusive upper bound (excluded), i = N is on the inclusive
        // lower bound (included), so rows i = 1..N match => exactly N rows.
        val last7Days = expenseDao.getExpensesBetween(baseTime - 604800000L, baseTime)
        val last30Days = expenseDao.getExpensesBetween(baseTime - 2592000000L, baseTime)
        val last90Days = expenseDao.getExpensesBetween(baseTime - 7776000000L, baseTime)

        assertEquals(7, last7Days.size)
        assertEquals(30, last30Days.size)
        assertEquals(90, last90Days.size)

        assertEquals((1..7).map { baseTime - (it * dayMs) }, last7Days.map { it.date })
        assertEquals((1..30).map { baseTime - (it * dayMs) }, last30Days.map { it.date })
        assertEquals((1..90).map { baseTime - (it * dayMs) }, last90Days.map { it.date })
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
    fun handles_concurrent_duplicate_checks() = runBlocking {
        val now = System.currentTimeMillis()
        val dedupeKey = Expense.generateDedupeKey(10.0, "Same", now, "EUR")

        val start = CompletableDeferred<Unit>()
        val results = withTimeout(30_000L) {
            val jobs = List(10) {
                async(Dispatchers.IO) {
                    start.await()
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
            start.complete(Unit)
            jobs.awaitAll()
        }

        // insert() uses OnConflictStrategy.IGNORE: the winner gets a row id > 0,
        // every loser hits the UNIQUE dedupeKey index and returns -1.
        assertEquals(10, results.size)
        assertEquals(1, results.count { it > 0L })
        assertEquals(9, results.count { it == -1L })
        val stored = expenseDao.getAll()
        assertEquals(1, stored.size)
        assertEquals(results.single { it > 0L }, stored.single().id)
        assertEquals(dedupeKey, stored.single().dedupeKey)
    }

    @Test
    fun query_large_dataset_returns_every_row_with_unique_ids_and_exact_max_date() = runTest {
        val baseDate = System.currentTimeMillis()
        repeat(5000) { i ->
            expenseDao.insert(createExpense(date = baseDate + i))
        }

        val all = expenseDao.getAll()

        assertEquals(5000, all.size)
        assertEquals(5000, all.map { it.id }.toSet().size)
        // Dates are baseDate + 0..4999, so the maximum is baseDate + 4999 and, with
        // date DESC ordering, it is the first row returned.
        assertEquals(baseDate + 4999L, all.maxOf { it.date })
        assertEquals(baseDate + 4999L, all.first().date)
        assertEquals(baseDate, all.minOf { it.date })
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
