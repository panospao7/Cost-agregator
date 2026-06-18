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
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.domain.location.GeocodingError
import com.yourname.expensetracker.domain.workers.RetryableWorkerException
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import com.yourname.expensetracker.domain.location.LocationResolutionResult
import com.yourname.expensetracker.domain.location.LocationResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocationBackfillWorkerTest {

    private lateinit var context: Context
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var locationResolver: LocationResolver
    private lateinit var merchantLocationRepository: MerchantLocationRepository
    private lateinit var executionGuard: WorkerExecutionGuard

    // Relaxed run context so behavioral tests can run the guarded block AND
    // coVerify the worker's per-row counter calls (scanned/updated/skipped).
    private lateinit var ctx: WorkerRunContext

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        expenseRepository = mockk(relaxed = true)
        locationResolver = mockk(relaxed = true)
        merchantLocationRepository = mockk(relaxed = true)
        executionGuard = mockk(relaxed = true)
        ctx = mockk(relaxed = true)
        coEvery {
            executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>())
        } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            try {
                WorkerGuardResult.Success(block.invoke(ctx))
            } catch (e: CancellationException) {
                // Mirror the real guard: cancellation has highest precedence and is rethrown.
                throw e
            } catch (e: Exception) {
                // Mirror WorkerExecutionGuard precedence exactly: an explicit typed retry
                // signal wins over the message-based keyword heuristic; everything else
                // falls back to classifyTransient, then Failed.
                val msg = e.message ?: ""
                val transient = listOf("timeout", "interrupted", "deadlock", "SQLITE_BUSY", "database is locked")
                    .any { msg.contains(it, ignoreCase = true) } || e is java.io.IOException
                when {
                    e is RetryableWorkerException -> WorkerGuardResult.Retry(msg, e)
                    transient -> WorkerGuardResult.Retry(msg, e)
                    else -> WorkerGuardResult.Failed(msg, e)
                }
            }
        }
        coEvery { executionGuard.checkpoint(any()) } returns Unit
    }

    private fun buildWorker(): LocationBackfillWorker {
        return TestListenableWorkerBuilder<LocationBackfillWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): LocationBackfillWorker {
                    return LocationBackfillWorker(
                        appContext,
                        workerParameters,
                        expenseRepository,
                        locationResolver,
                        merchantLocationRepository,
                        executionGuard = executionGuard
                    )
                }
            })
            .build()
    }

    @Test
    fun `expenses without location backfill attempted`() = runTest {
        val expense = sampleExpense(id = 1L, merchant = "Cafe")
        coEvery { expenseRepository.getUnlocatedExpensesForBackfill(any()) } returns listOf(expense)
        coEvery {
            locationResolver.resolve(rawMerchantName = expense.merchant, transactionDateMs = expense.date)
        } returns LocationResolutionResult.Unresolved

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) {
            locationResolver.resolve(rawMerchantName = expense.merchant, transactionDateMs = expense.date)
        }
    }

    @Test
    fun `all expenses have location no work`() = runTest {
        coEvery { expenseRepository.getUnlocatedExpensesForBackfill(any()) } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 0) { locationResolver.resolve(any(), any(), any(), any()) }
        // P9-S4 zero-count: no unlocated expenses => no rows scanned/updated/skipped.
        coVerify(exactly = 0) { ctx.addRowsScanned() }
        coVerify(exactly = 0) { ctx.addRowsUpdated() }
        coVerify(exactly = 0) { ctx.addRowsSkipped() }
    }

    @Test
    fun `worker returns success`() = runTest {
        val expense = sampleExpense(id = 2L, merchant = "Market")
        coEvery { expenseRepository.getUnlocatedExpensesForBackfill(any()) } returns listOf(expense)
        coEvery {
            locationResolver.resolve(rawMerchantName = expense.merchant, transactionDateMs = expense.date)
        } returns LocationResolutionResult.Resolved(
            latitude = 37.98,
            longitude = 23.72,
            source = "MERCHANT_GEOCODE"
        )
        // affected > 0 => the worker takes the "updated" branch (not skipped).
        coEvery {
            expenseRepository.conditionallySetLocation(any(), any(), any(), any(), any(), any())
        } returns 1

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { expenseRepository.conditionallySetLocation(any(), any(), any(), any(), any(), any()) }
        // P9-S4 counts: one expense scanned, and an affected>0 write counts as updated.
        coVerify(exactly = 1) { ctx.addRowsScanned() }
        coVerify(exactly = 1) { ctx.addRowsUpdated() }
        coVerify(exactly = 0) { ctx.addRowsSkipped() }
    }

    @Test
    fun `resolved but already located counts as skipped`() = runTest {
        // affected == 0 means a user-set location was preserved: the worker must
        // count the row as skipped, not updated.
        val expense = sampleExpense(id = 5L, merchant = "Bakery")
        coEvery { expenseRepository.getUnlocatedExpensesForBackfill(any()) } returns listOf(expense)
        coEvery {
            locationResolver.resolve(rawMerchantName = expense.merchant, transactionDateMs = expense.date)
        } returns LocationResolutionResult.Resolved(
            latitude = 37.98,
            longitude = 23.72,
            source = "MERCHANT_GEOCODE"
        )
        coEvery {
            expenseRepository.conditionallySetLocation(any(), any(), any(), any(), any(), any())
        } returns 0

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { ctx.addRowsScanned() }
        coVerify(exactly = 1) { ctx.addRowsSkipped() }
        coVerify(exactly = 0) { ctx.addRowsUpdated() }
    }

    @Test
    fun `worker handles geocoding failure gracefully`() = runTest {
        val expense = sampleExpense(id = 3L, merchant = "Broken Merchant")
        coEvery { expenseRepository.getUnlocatedExpensesForBackfill(any()) } returns listOf(expense)
        coEvery {
            locationResolver.resolve(rawMerchantName = expense.merchant, transactionDateMs = expense.date)
        } throws IllegalStateException("geocoder failure")

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 0) { expenseRepository.incrementBackfillAttempts(expense.id) }
    }

    @Test
    fun `retryable resolver result does not consume attempt budget`() = runTest {
        val expense = sampleExpense(id = 4L, merchant = "Flaky Merchant")
        coEvery { expenseRepository.getUnlocatedExpensesForBackfill(any()) } returns listOf(expense)
        coEvery {
            locationResolver.resolve(rawMerchantName = expense.merchant, transactionDateMs = expense.date)
        } returns LocationResolutionResult.Retryable(GeocodingError.Timeout)

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 0) { expenseRepository.incrementBackfillAttempts(expense.id) }
    }

    private fun sampleExpense(id: Long, merchant: String): Expense {
        return Expense(
            id = id,
            amount = 20.0,
            currency = "EUR",
            merchant = merchant,
            transactionType = TransactionType.PURCHASE,
            date = 1_700_000_000_000L
        )
    }
}