package com.yourname.expensetracker.data.location

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2 instrumented tests for [MerchantKeyBackfillWorker.doWork].
 *
 * Uses [TestListenableWorkerBuilder] with a custom [WorkerFactory] to inject
 * a mocked [ExpenseRepository], avoiding the full Hilt test infrastructure.
 */
@RunWith(AndroidJUnit4::class)
class MerchantKeyBackfillWorkerTest {

    private lateinit var context: Context
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var executionGuard: WorkerExecutionGuard

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        expenseRepository = mockk()
        // S4: production now calls runGuardedWithContext; a bare relaxed mock would
        // never invoke the block, so the doWork verifications below would never run.
        // Stub it to invoke the block with a relaxed run context.
        executionGuard = mockk(relaxed = true)
        coEvery {
            executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>())
        } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            WorkerGuardResult.Success(block.invoke(mockk(relaxed = true)))
        }
        coEvery { executionGuard.checkpoint(any()) } returns Unit
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
                    executionGuard = executionGuard
                )
            })
            .build()

    private fun makeExpense(id: Long, merchant: String, merchantKey: String? = null) = Expense(
        id          = id,
        amount      = 10.0,
        currency    = "EUR",
        merchant    = merchant,
        transactionType = TransactionType.PURCHASE,
        date        = System.currentTimeMillis(),
        merchantKey = merchantKey
    )

    @Test
    fun doWork_happyPath_populatesNullMerchantKeys() = runBlocking {
        val expense = makeExpense(id = 1L, merchant = "Σκλαβενίτης", merchantKey = null)

        // First call returns the un-keyed row; second call returns empty (done)
        coEvery { expenseRepository.getExpensesWithNullMerchantKey(any()) } returnsMany
                listOf(listOf(expense), emptyList())
        coEvery { expenseRepository.updateMerchantKey(any(), any()) } returns Unit

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // Must have written a key derived from the merchant name
        coVerify(exactly = 1) { expenseRepository.updateMerchantKey(1L, "sklavenitis") }
    }

    @Test
    fun doWork_idempotent_skipsExpensesAlreadyHavingAKey() = runBlocking {
        // Worker only fetches rows WHERE merchantKey IS NULL — none are returned here
        coEvery { expenseRepository.getExpensesWithNullMerchantKey(any()) } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // updateMerchantKey must never be called
        coVerify(exactly = 0) { expenseRepository.updateMerchantKey(any(), any()) }
    }

    @Test
    fun doWork_emptyDatabase_returnsSuccess() = runBlocking {
        coEvery { expenseRepository.getExpensesWithNullMerchantKey(any()) } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
    }
}
