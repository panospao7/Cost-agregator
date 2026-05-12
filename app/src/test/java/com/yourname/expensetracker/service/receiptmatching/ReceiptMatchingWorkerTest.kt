package com.yourname.expensetracker.service.receiptmatching

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorker
import com.yourname.expensetracker.domain.receiptmatching.MatchResult
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
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
@Suppress("DEPRECATION_ERROR")
class ReceiptMatchingWorkerTest {

    private lateinit var context: Context
    private lateinit var receiptRepository: ReceiptRepository
    private lateinit var matcher: ReceiptTransactionMatcher
    private lateinit var notificationService: NotificationService
    private lateinit var executionGuard: WorkerExecutionGuard

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        receiptRepository = mockk(relaxed = true)
        matcher = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        executionGuard = mockk()
        coEvery { executionGuard.runGuarded(any(), any<suspend () -> Any>()) } coAnswers {
            WorkerGuardResult.Success(secondArg<suspend () -> Any>().invoke())
        }
    }

    private fun buildWorker(): ReceiptMatchingWorker {
        return TestListenableWorkerBuilder<ReceiptMatchingWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ReceiptMatchingWorker {
                    return ReceiptMatchingWorker(
                        appContext,
                        workerParameters,
                        receiptRepository,
                        matcher,
                        receiptLinkService = mockk(relaxed = true),
                        notificationService = notificationService,
                        executionGuard = executionGuard
                    )
                }
            })
            .build()
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `unmatched receipts matching is attempted`() = runTest {
        val receipt = sampleReceipt(id = 10L)
        coEvery { receiptRepository.getUnmatchedReceipts() } returns listOf(receipt)
        coEvery { matcher.findBestMatch(receipt) } returns MatchResult.NoMatch

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { matcher.findBestMatch(receipt) }
    }

    @Test
    fun `all receipts matched no work needed`() = runTest {
        coEvery { receiptRepository.getUnmatchedReceipts() } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 0) { matcher.findBestMatch(any()) }
        coVerify(exactly = 0) { receiptRepository.linkReceiptToExpense(any(), any(), any()) }
    }

    @Test
    fun `worker returns success`() = runTest {
        coEvery { receiptRepository.getUnmatchedReceipts() } returns listOf(sampleReceipt(id = 11L))
        coEvery { matcher.findBestMatch(any()) } returns MatchResult.NoMatch

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
    }

    @Test
    fun `worker handles db error gracefully`() = runTest {
        coEvery { receiptRepository.getUnmatchedReceipts() } throws IllegalStateException("db error")

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 0) { matcher.findBestMatch(any()) }
    }

    @Test
    fun `worker stops retrying malformed receipt failures`() = runTest {
        coEvery { receiptRepository.getUnmatchedReceipts() } throws IllegalArgumentException("malformed receipt data")

        val result = buildWorker().doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { matcher.findBestMatch(any()) }
    }

    @Test
    fun `worker stops retrying logical conflicts`() = runTest {
        coEvery { receiptRepository.getUnmatchedReceipts() } throws IllegalStateException("receipt matching conflict")

        val result = buildWorker().doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { matcher.findBestMatch(any()) }
    }

    private fun sampleReceipt(id: Long): ScannedReceipt {
        return ScannedReceipt(
            id = id,
            imagePath = null,
            rawOcrText = "sample",
            parsedTotal = 12.34,
            parsedMerchant = "Store",
            parsedDate = 1_700_000_000_000L,
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.9f,
            matchStatus = MatchStatus.UNMATCHED
        )
    }
}