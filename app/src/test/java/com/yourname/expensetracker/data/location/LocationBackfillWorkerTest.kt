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
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.location.LocationResolutionResult
import com.yourname.expensetracker.domain.location.LocationResolver
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
class LocationBackfillWorkerTest {

    private lateinit var context: Context
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var locationResolver: LocationResolver
    private lateinit var merchantLocationRepository: MerchantLocationRepository
    private lateinit var executionGuard: WorkerExecutionGuard

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        expenseRepository = mockk(relaxed = true)
        locationResolver = mockk(relaxed = true)
        merchantLocationRepository = mockk(relaxed = true)
        executionGuard = mockk()
        coEvery { executionGuard.runGuarded(any(), any<suspend () -> Any>()) } coAnswers {
            WorkerGuardResult.Success(secondArg<suspend () -> Any>().invoke())
        }
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

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { expenseRepository.conditionallySetLocation(any(), any(), any(), any(), any(), any()) }
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