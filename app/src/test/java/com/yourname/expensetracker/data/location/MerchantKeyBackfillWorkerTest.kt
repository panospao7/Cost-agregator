package com.yourname.expensetracker.data.location

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MerchantKeyBackfillWorkerTest {

    private lateinit var context: Context
    private lateinit var expenseRepository: ExpenseRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        expenseRepository = mockk(relaxed = true)
    }

    private fun buildWorker(): MerchantKeyBackfillWorker =
        TestListenableWorkerBuilder<MerchantKeyBackfillWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): MerchantKeyBackfillWorker = MerchantKeyBackfillWorker(
                    appContext,
                    workerParameters,
                    expenseRepository,
                    restoreMaintenanceMode = mockk(relaxed = true)
                )
            })
            .build()

    @Test
    fun `doWork happy path populates null merchant keys`() = runTest {
        val expense = makeExpense(id = 1L, merchant = "Σκλαβενίτης")

        coEvery { expenseRepository.getExpensesWithNullMerchantKey(any()) } returnsMany
            listOf(listOf(expense), emptyList())
        coEvery { expenseRepository.updateMerchantKey(any(), any()) } returns Unit

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { expenseRepository.updateMerchantKey(1L, "sklavenitis") }
    }

    @Test
    fun `doWork idempotent skips expenses already having a key`() = runTest {
        coEvery { expenseRepository.getExpensesWithNullMerchantKey(any()) } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 0) { expenseRepository.updateMerchantKey(any(), any()) }
    }

    @Test
    fun `doWork empty database returns success`() = runTest {
        coEvery { expenseRepository.getExpensesWithNullMerchantKey(any()) } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
    }

    @Test
    fun `doWork retries when same failing row repeats after partial progress`() = runTest {
        val successfulExpense = makeExpense(id = 1L, merchant = "Σκλαβενίτης")
        val failingExpense = makeExpense(id = 2L, merchant = "Broken Merchant")

        coEvery { expenseRepository.getExpensesWithNullMerchantKey(any()) } returnsMany listOf(
            listOf(successfulExpense, failingExpense),
            listOf(failingExpense)
        )
        coEvery { expenseRepository.updateMerchantKey(1L, any()) } returns Unit
        coEvery { expenseRepository.updateMerchantKey(2L, any()) } throws IllegalStateException("db failure")

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 1) { expenseRepository.updateMerchantKey(1L, "sklavenitis") }
        coVerify(exactly = 1) { expenseRepository.updateMerchantKey(2L, "broken_merchant") }
        coVerify(exactly = 2) { expenseRepository.getExpensesWithNullMerchantKey(any()) }
    }

    @Test
    fun `doWork retries when batch makes no progress`() = runTest {
        val failingExpense = makeExpense(id = 3L, merchant = "Still Broken")

        coEvery { expenseRepository.getExpensesWithNullMerchantKey(any()) } returns listOf(failingExpense)
        coEvery { expenseRepository.updateMerchantKey(3L, any()) } throws IllegalStateException("db failure")

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 1) { expenseRepository.updateMerchantKey(3L, "still_broken") }
        coVerify(exactly = 1) { expenseRepository.getExpensesWithNullMerchantKey(any()) }
    }

    private fun makeExpense(id: Long, merchant: String, merchantKey: String? = null) = Expense(
        id = id,
        amount = 10.0,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = 1_700_000_000_000L,
        merchantKey = merchantKey
    )
}
