package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecurringExpenseDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var recurringExpenseDao: RecurringExpenseDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        recurringExpenseDao = database.recurringExpenseDao()
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
    fun `insert recurring expense then retrieve by id returns persisted expense`() = runBlocking {
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
    fun `query active recurring expenses returns only active rows`() = runBlocking {
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

        val active = recurringExpenseDao.getAll().filter { it.isActive }

        assertEquals(1, active.size)
        assertEquals("Spotify", active.first().merchant)
    }

    @Test
    fun `update next occurrence date persists new nextDate`() = runBlocking {
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
    fun `delete recurring expense then verify removed`() = runBlocking {
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
}
