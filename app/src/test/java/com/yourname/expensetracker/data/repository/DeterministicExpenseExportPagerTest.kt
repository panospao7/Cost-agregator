package com.yourname.expensetracker.data.repository

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DeterministicExpenseExportPagerTest {

    private val expenseRepository: ExpenseRepository = mockk()
    private val pager = DeterministicExpenseExportPager(expenseRepository)

    @Test
    fun `fetchAllBetween exhausts all deterministic pages`() = runTest {
        val startDate = 100L
        val endDate = 200L
        val pageSize = 2
        val pageOne = listOf(expense(1L), expense(2L))
        val pageTwo = listOf(expense(3L))

        coEvery {
            expenseRepository.getExpensesBetweenPagedForDeterministicExport(startDate, endDate, pageSize, 0)
        } returns pageOne
        coEvery {
            expenseRepository.getExpensesBetweenPagedForDeterministicExport(startDate, endDate, pageSize, pageSize)
        } returns pageTwo

        val result = pager.fetchAllBetween(startDate, endDate, pageSize)

        assertThat(result.map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenPagedForDeterministicExport(startDate, endDate, pageSize, 0)
        }
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenPagedForDeterministicExport(startDate, endDate, pageSize, pageSize)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fetchAllBetween rejects non positive page sizes`() = runTest {
        pager.fetchAllBetween(100L, 200L, 0)
    }

    private fun expense(id: Long): Expense {
        return Expense(
            id = id,
            amount = 10.0,
            merchant = "Merchant$id",
            transactionType = TransactionType.PURCHASE,
            date = id
        )
    }
}
