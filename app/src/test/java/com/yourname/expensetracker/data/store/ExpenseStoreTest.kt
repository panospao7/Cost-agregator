package com.yourname.expensetracker.data.store

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ExpenseStoreTest {

    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private lateinit var readStore: ExpenseReadStore

    private val expense = mockk<Expense>(relaxed = true)

    @Before
    fun setup() {
        readStore = ExpenseReadStore(expenseDao)
    }

    @Test
    fun read_store_getById_delegates_to_dao() = runTest {
        coEvery { expenseDao.getById(1L) } returns expense
        val result = readStore.getById(1L)
        assertEquals(expense, result)
    }

    @Test
    fun read_store_getTotalCount_delegates_to_dao() = runTest {
        coEvery { expenseDao.getTotalCount() } returns 99
        assertEquals(99, readStore.getTotalCount())
    }
}
