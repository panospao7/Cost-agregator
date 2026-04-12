package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import kotlinx.coroutines.runBlocking
import org.junit.After
import android.database.sqlite.SQLiteConstraintException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@Suppress("DEPRECATION") // RecurringExpenseDao is deprecated; tests exercise it intentionally
@RunWith(AndroidJUnit4::class)
class RecurringExpenseDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var recurringExpenseDao: RecurringExpenseDao
    private lateinit var manualRecurringExpenseDao: ManualRecurringExpenseDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        recurringExpenseDao = database.recurringExpenseDao()
        manualRecurringExpenseDao = database.manualRecurringExpenseDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun makeRecurringExpense(
        merchant: String,
        amount: Double,
        nextDate: Long,
        isActive: Boolean = true
    ) = ManualRecurringExpense(
        merchant = merchant,
        amount = amount,
        frequency = RecurrenceFrequency.MONTHLY,
        nextDate = nextDate,
        isActive = isActive,
        createdAt = 1_700_000_000_000L
    )

    @Test
    fun insert_recurring_expense_then_retrieve_by_id_returns_persisted_expense() = runBlocking {
        val id = recurringExpenseDao.insert(
            makeRecurringExpense(
                merchant = "Netflix",
                amount = 15.99,
                nextDate = 1_710_288_000_000L
            )
        )

        val stored = recurringExpenseDao.getById(id)

        assertTrue(id > 0)
        assertNotNull(stored)
        assertEquals("Netflix", stored!!.merchant)
        assertEquals(15.99, stored.amount, 0.0001)
    }

    @Test
    fun query_active_recurring_expenses_returns_only_active_rows() = runBlocking {
        recurringExpenseDao.insert(
            makeRecurringExpense(
                merchant = "Spotify",
                amount = 9.99,
                nextDate = 1_710_374_400_000L,
                isActive = true
            )
        )
        recurringExpenseDao.insert(
            makeRecurringExpense(
                merchant = "Old Service",
                amount = 5.00,
                nextDate = 1_710_460_800_000L,
                isActive = false
            )
        )

        val active = recurringExpenseDao.getAllActive()

        assertEquals(1, active.size)
        assertEquals("Spotify", active.first().merchant)
    }

    @Test
    fun getAllActive_excludes_inactive_rows_while_getAllIncludingInactive_returns_both() = runBlocking {
        recurringExpenseDao.insert(
            makeRecurringExpense(
                merchant = "Active1",
                amount = 1.00,
                nextDate = 1_710_374_400_000L,
                isActive = true
            )
        )
        recurringExpenseDao.insert(
            makeRecurringExpense(
                merchant = "Inactive1",
                amount = 2.00,
                nextDate = 1_710_460_800_000L,
                isActive = false
            )
        )
        recurringExpenseDao.insert(
            makeRecurringExpense(
                merchant = "Active2",
                amount = 3.00,
                nextDate = 1_710_547_200_000L,
                isActive = true
            )
        )

        val activeOnly = recurringExpenseDao.getAllActive()
        val all = recurringExpenseDao.getAllIncludingInactive()

        assertEquals(2, activeOnly.size)
        assertTrue(activeOnly.all { it.isActive })
        assertEquals(3, all.size)
    }

    @Test
    fun update_next_occurrence_date_persists_new_nextDate() = runBlocking {
        val id = recurringExpenseDao.insert(
            makeRecurringExpense(
                merchant = "Gym",
                amount = 29.99,
                nextDate = 1_710_547_200_000L
            )
        )

        val existing = recurringExpenseDao.getById(id)!!
        val updatedNextDate = 1_713_139_200_000L
        recurringExpenseDao.update(existing.copy(nextDate = updatedNextDate))

        val updated = recurringExpenseDao.getById(id)
        assertNotNull(updated)
        assertEquals(updatedNextDate, updated!!.nextDate)
    }

    @Test
    fun delete_recurring_expense_then_verify_removed() = runBlocking {
        val id = recurringExpenseDao.insert(
            makeRecurringExpense(
                merchant = "Cloud Storage",
                amount = 2.99,
                nextDate = 1_710_633_600_000L
            )
        )

        recurringExpenseDao.deleteById(id)

        val deleted = recurringExpenseDao.getById(id)
        assertNull(deleted)
    }

    @Test
    fun insert_duplicate_id_via_recurringExpenseDao_throws_instead_of_replacing() = runBlocking {
        val id = recurringExpenseDao.insert(
            makeRecurringExpense(
                merchant = "Netflix",
                amount = 15.99,
                nextDate = 1_710_288_000_000L
            )
        )

        val duplicate = ManualRecurringExpense(
            id = id,
            merchant = "Netflix v2",
            amount = 19.99,
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = 1_713_000_000_000L,
            isActive = true,
            createdAt = 1_700_000_000_000L
        )

        try {
            recurringExpenseDao.insert(duplicate)
            fail("Expected SQLiteConstraintException for duplicate PK insert")
        } catch (_: SQLiteConstraintException) {
            // expected — ABORT semantics
        }

        // Original row must be untouched
        val original = recurringExpenseDao.getById(id)
        assertNotNull(original)
        assertEquals("Netflix", original!!.merchant)
        assertEquals(15.99, original.amount, 0.0001)
    }

    @Test
    fun insert_duplicate_id_via_manualRecurringExpenseDao_throws_instead_of_replacing() = runBlocking {
        val id = manualRecurringExpenseDao.insert(
            makeRecurringExpense(
                merchant = "Spotify",
                amount = 9.99,
                nextDate = 1_710_374_400_000L
            )
        )

        val duplicate = ManualRecurringExpense(
            id = id,
            merchant = "Spotify v2",
            amount = 14.99,
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = 1_713_000_000_000L,
            isActive = true,
            createdAt = 1_700_000_000_000L
        )

        try {
            manualRecurringExpenseDao.insert(duplicate)
            fail("Expected SQLiteConstraintException for duplicate PK insert")
        } catch (_: SQLiteConstraintException) {
            // expected — ABORT semantics
        }

        // Original row must be untouched
        val original = manualRecurringExpenseDao.getById(id)
        assertNotNull(original)
        assertEquals("Spotify", original!!.merchant)
        assertEquals(9.99, original.amount, 0.0001)
    }
}
