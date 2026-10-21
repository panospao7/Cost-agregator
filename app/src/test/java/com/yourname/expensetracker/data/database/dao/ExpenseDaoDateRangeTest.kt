package com.yourname.expensetracker.data.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real in-memory Room proofs for the half-open date-range queries
 * `ExpenseDao.getExpensesBetween` and `ExpenseDao.countExpensesBetween`
 * (`date >= start AND date < end AND isNotMine = 0`), plus a field roundtrip.
 *
 * All dates are fixed UTC instants; no wall-clock access.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExpenseDaoDateRangeTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    private val may1 = dateMs(2026, 5, 1)
    private val may15 = dateMs(2026, 5, 15)
    private val jun1 = dateMs(2026, 6, 1)
    private val jul1 = dateMs(2026, 7, 1)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun expense(
        merchant: String,
        date: Long,
        amount: Double = 10.0,
        isNotMine: Boolean = false
    ) = Expense(
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = date,
        isNotMine = isNotMine
    )

    @Test
    fun `half-open range includes start and excludes end ordered by date desc`() = runTest {
        val dao = db.expenseDao()
        dao.insert(expense("MayFirst", may1))
        dao.insert(expense("MayMid", may15))
        dao.insert(expense("JuneFirst", jun1))

        val may = dao.getExpensesBetween(startDate = may1, endDate = jun1)
        assertEquals(listOf("MayMid", "MayFirst"), may.map { it.merchant })

        val june = dao.getExpensesBetween(startDate = jun1, endDate = jul1)
        assertEquals(listOf("JuneFirst"), june.map { it.merchant })
    }

    @Test
    fun `not-mine row inside range is excluded from list and count`() = runTest {
        val dao = db.expenseDao()
        dao.insert(expense("MayFirst", may1))
        dao.insert(expense("MayMid", may15))
        dao.insert(expense("NotMineMay", dateMs(2026, 5, 20), isNotMine = true))

        val may = dao.getExpensesBetween(startDate = may1, endDate = jun1)
        assertEquals(listOf("MayMid", "MayFirst"), may.map { it.merchant })
        assertEquals(2, dao.countExpensesBetween(startDate = may1, endDate = jun1))
    }

    @Test
    fun `countExpensesBetween counts half-open ranges and skips not-mine`() = runTest {
        val dao = db.expenseDao()
        dao.insert(expense("M1", may1))
        dao.insert(expense("M2", dateMs(2026, 5, 2)))
        dao.insert(expense("M3", jun1))
        dao.insert(expense("M4", jul1))
        dao.insert(expense("NotMineMay", dateMs(2026, 5, 3), isNotMine = true))

        assertEquals(2, dao.countExpensesBetween(startDate = may1, endDate = jun1))
        assertEquals(3, dao.countExpensesBetween(startDate = may1, endDate = jul1))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `core expense fields survive insert and getById roundtrip exactly`() = runTest {
        val categoryId = db.categoryDao().insert(
            Category(name = "Shopping", icon = "🛍", color = "#3366FF")
        )
        val original = Expense(
            amount = 99.99,
            currency = "USD",
            merchant = "Amazon.com",
            transactionType = TransactionType.PURCHASE,
            date = may1,
            categoryId = categoryId,
            source = "manual",
            notes = "Roundtrip test expense"
        )

        val id = db.expenseDao().insert(original)
        val saved = db.expenseDao().getById(id)

        assertNotNull(saved)
        assertEquals(original.copy(id = id), saved)
        assertEquals(99.99, saved!!.amount, 0.0)
        assertEquals("USD", saved.currency)
        assertEquals("Amazon.com", saved.merchant)
        assertEquals(may1, saved.date)
        assertEquals("Roundtrip test expense", saved.notes)
        assertEquals(TransactionType.PURCHASE, saved.transactionType)
        assertEquals(categoryId, saved.categoryId)
    }
}
