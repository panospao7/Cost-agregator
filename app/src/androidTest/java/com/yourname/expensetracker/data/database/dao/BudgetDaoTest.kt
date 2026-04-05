package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BudgetDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var budgetDao: BudgetDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        budgetDao = database.budgetDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun makeBudget(
        amount: Double,
        period: BudgetPeriod,
        startDate: Long,
        notifyAtWarning: Float = 0.75f,
        createdAt: Long = 1_700_000_000_000L
    ) = Budget(
        categoryId = null,
        amount = amount,
        period = period,
        startDate = startDate,
        notifyAtWarning = notifyAtWarning,
        createdAt = createdAt
    )

    @Test
    fun `insert budget then retrieve by id returns persisted budget`() = runBlocking {
        val budget = makeBudget(
            amount = 500.0,
            period = BudgetPeriod.MONTHLY,
            startDate = 1_706_745_600_000L
        )

        val id = budgetDao.insert(budget)
        val stored = budgetDao.getById(id)

        assertNotNull(stored)
        assertEquals(id, stored!!.id)
        assertEquals(500.0, stored.amount, 0.0001)
        assertEquals(BudgetPeriod.MONTHLY, stored.period)
    }

    @Test
    fun `query budgets by period date range returns matching budgets`() = runBlocking {
        val janStart = 1_704_067_200_000L // 2024-01-01 UTC
        val febStart = 1_706_745_600_000L // 2024-02-01 UTC
        val marStart = 1_709_251_200_000L // 2024-03-01 UTC

        budgetDao.insert(makeBudget(amount = 300.0, period = BudgetPeriod.MONTHLY, startDate = janStart))
        budgetDao.insert(makeBudget(amount = 400.0, period = BudgetPeriod.MONTHLY, startDate = febStart))
        budgetDao.insert(makeBudget(amount = 450.0, period = BudgetPeriod.MONTHLY, startDate = marStart))

        val rangeStart = febStart
        val rangeEnd = marStart
        val inRange = budgetDao.getAll().filter { it.startDate in rangeStart..rangeEnd }

        assertEquals(2, inRange.size)
        assertEquals(setOf(febStart, marStart), inRange.map { it.startDate }.toSet())
    }

    @Test
    fun `update warning threshold persists new value`() = runBlocking {
        val id = budgetDao.insert(
            makeBudget(
                amount = 800.0,
                period = BudgetPeriod.MONTHLY,
                startDate = 1_706_745_600_000L,
                notifyAtWarning = 0.75f
            )
        )

        val original = budgetDao.getById(id)!!
        budgetDao.update(original.copy(notifyAtWarning = 0.85f))

        val updated = budgetDao.getById(id)
        assertNotNull(updated)
        assertEquals(0.85f, updated!!.notifyAtWarning)
    }

    @Test
    fun `delete budget then getById returns null`() = runBlocking {
        val id = budgetDao.insert(
            makeBudget(
                amount = 250.0,
                period = BudgetPeriod.WEEKLY,
                startDate = 1_706_745_600_000L
            )
        )
        val stored = budgetDao.getById(id)!!

        budgetDao.delete(stored)

        val deleted = budgetDao.getById(id)
        assertNull(deleted)
    }
}
