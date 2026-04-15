package com.yourname.expensetracker.service.warranty

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.domain.service.NotificationService
import io.mockk.capture
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WarrantyExpirationWorkerTest {

    private lateinit var context: Context
    private lateinit var warrantyRepository: WarrantyTrackerRepository
    private lateinit var notificationService: NotificationService

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        warrantyRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
    }

    private fun buildWorker(): WarrantyExpirationWorker {
        return TestListenableWorkerBuilder<WarrantyExpirationWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): WarrantyExpirationWorker {
                    return WarrantyExpirationWorker(
                        appContext,
                        workerParameters,
                        warrantyRepository,
                        notificationService
                    )
                }
            })
            .build()
    }

    @Test
    fun `expiring warranty triggers notification`() = runTest {
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(7) } returns listOf(sampleWarranty(id = 1L))
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(30) } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        verify(exactly = 1) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    @Test
    fun `no expiring warranties sends no notification`() = runTest {
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(7) } returns emptyList()
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(30) } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    @Test
    fun `worker returns success result`() = runTest {
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(7) } returns emptyList()
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(30) } returns listOf(sampleWarranty(id = 2L))
        val notificationIds = mutableListOf<Int>()
        every { notificationService.sendBudgetAlert(capture(notificationIds), any(), any()) } returns Unit

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        assertThat(notificationIds).hasSize(1)
        assertThat(notificationIds.single()).isLessThan(20000)
    }

    @Test
    fun `worker handles exception gracefully`() = runTest {
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(7) } throws IllegalStateException("db unavailable")

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    @Test
    fun `worker propagates CancellationException instead of returning retry`() = runTest {
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(7) } throws CancellationException("cancelled")

        try {
            buildWorker().doWork()
            throw AssertionError("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected — cancellation must not be swallowed
        }
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    private fun sampleWarranty(id: Long): Warranty {
        return Warranty(
            id = id,
            receiptId = id,
            productName = "Laptop",
            merchantName = "Tech Store",
            purchaseDate = 1_700_000_000_000L,
            warrantyDurationMonths = 24,
            warrantyEndDate = 1_800_000_000_000L
        )
    }
}
