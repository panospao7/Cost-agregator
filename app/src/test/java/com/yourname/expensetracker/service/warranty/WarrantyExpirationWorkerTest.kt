package com.yourname.expensetracker.service.warranty

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.WarrantyDao
import com.yourname.expensetracker.data.database.dao.WarrantyReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyReminderDelivery
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository.ExpiryReconciliationResult
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.workers.RetryableWorkerException
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [WarrantyExpirationWorker].
 *
 * S9 / P9-P1-09 (PR6): the worker's sent-state is now a durable, claim-before-notify
 * [WarrantyReminderDelivery] Room entity (no more SharedPreferences). These tests use a
 * REAL in-memory [WarrantyReminderDeliveryDao] so that claim atomicity and durable,
 * cross-run dedup are genuinely exercised. Parent warranties are seeded into the same DB
 * to satisfy the warrantyId foreign key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WarrantyExpirationWorkerTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var deliveryDao: WarrantyReminderDeliveryDao
    private lateinit var warrantyDao: WarrantyDao
    private lateinit var warrantyRepository: WarrantyTrackerRepository
    private lateinit var notificationService: NotificationService
    private lateinit var executionGuard: WorkerExecutionGuard

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.inMemoryBuilder(context).build()
        deliveryDao = database.warrantyReminderDeliveryDao()
        warrantyDao = database.warrantyDao()

        warrantyRepository = mockk()
        notificationService = mockk(relaxed = true)
        executionGuard = mockk()
        // Faithful guard stub: mirror WorkerExecutionGuard's catch precedence exactly —
        // CancellationException rethrown (highest), then an explicit RetryableWorkerException
        // maps to Retry, then the classifyTransient keyword heuristic maps to Retry, else
        // Failed. A blanket-Retry mock would LIE about production behavior, so we model the
        // real classification here.
        coEvery { executionGuard.runGuarded(any(), any<suspend () -> Any>()) } coAnswers {
            val block = secondArg<suspend () -> Any>()
            try {
                WorkerGuardResult.Success(block.invoke())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
        coEvery { warrantyRepository.reconcileExpiredItems(any<Long>()) } returns ExpiryReconciliationResult(0, 0)
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(any()) } returns emptyList()
        // Default: deliveries succeed.
        every { notificationService.sendBudgetAlert(any(), any(), any()) } returns
            NotificationService.DeliveryResult.DELIVERED
    }

    @After
    fun teardown() {
        database.close()
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
                        notificationService,
                        deliveryDao = deliveryDao,
                        executionGuard = executionGuard,
                    )
                }
            })
            .build()
    }

    /** Inserts a parent warranty (so the FK is satisfied) and returns it with its real id. */
    private suspend fun seedWarranty(
        endDate: Long = 1_800_000_000_000L
    ): Warranty {
        val base = Warranty(
            receiptId = null,
            productName = "Laptop",
            merchantName = "Tech Store",
            purchaseDate = 1_700_000_000_000L,
            warrantyDurationMonths = 24,
            warrantyEndDate = endDate,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L
        )
        val id = warrantyDao.insertWarranty(base)
        return base.copy(id = id)
    }

    // -------------------------------------------------------------------------
    // Durable, claim-before-notify behavior
    // -------------------------------------------------------------------------

    @Test
    fun `delivered result marks SENT and notifies once`() = runTest {
        val warranty = seedWarranty()
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(7) } returns listOf(warranty)
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(30) } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        verify(exactly = 1) { notificationService.sendBudgetAlert(any(), any(), any()) }

        val row = deliveryDao.getByKey(warranty.id, 7, warranty.warrantyEndDate)
        assertThat(row).isNotNull()
        assertThat(row!!.status).isEqualTo("SENT")
        // notificationId is persisted, in the warranty notification range (<20000).
        assertThat(row.notificationId).isNotNull()
        assertThat(row.notificationId!!).isLessThan(20000)
    }

    @Test
    fun `NOT_DELIVERED does not mark SENT and records failure`() = runTest {
        val warranty = seedWarranty()
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(7) } returns listOf(warranty)
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(30) } returns emptyList()
        every { notificationService.sendBudgetAlert(any(), any(), any()) } returns
            NotificationService.DeliveryResult.NOT_DELIVERED

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        val row = deliveryDao.getByKey(warranty.id, 7, warranty.warrantyEndDate)
        assertThat(row).isNotNull()
        assertThat(row!!.status).isEqualTo("FAILED")
        assertThat(row.notificationId).isNull()
        assertThat(row.failureReason).isEqualTo("notification_not_delivered")
    }

    @Test
    fun `already-sent delivery is not re-notified across runs`() = runTest {
        val warranty = seedWarranty()
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(7) } returns listOf(warranty)
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(30) } returns emptyList()

        // First run delivers and marks SENT.
        buildWorker().doWork()
        // Second run (e.g. after reboot/reschedule) must NOT re-notify — durable dedup.
        buildWorker().doWork()

        verify(exactly = 1) { notificationService.sendBudgetAlert(any(), any(), any()) }
        assertThat(deliveryDao.getByKey(warranty.id, 7, warranty.warrantyEndDate)!!.status)
            .isEqualTo("SENT")
    }

    @Test
    fun `pre-existing SENT delivery blocks notification (claim race protection)`() = runTest {
        val warranty = seedWarranty()
        // Simulate a concurrent run that already claimed + sent this delivery.
        deliveryDao.insertOrIgnore(
            WarrantyReminderDelivery(
                warrantyId = warranty.id,
                windowDays = 7,
                expiryDate = warranty.warrantyEndDate,
                status = "SENT",
                notificationId = 10001,
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_000L
            )
        )
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(7) } returns listOf(warranty)
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(30) } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        // Claim returns 0 because the row is already SENT -> no second notification.
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    @Test
    fun `7-day and 30-day windows for distinct warranties each notify once`() = runTest {
        val w7 = seedWarranty(endDate = 1_800_000_000_000L)
        val w30 = seedWarranty(endDate = 1_810_000_000_000L)
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(7) } returns listOf(w7)
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(30) } returns listOf(w30)

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        verify(exactly = 2) { notificationService.sendBudgetAlert(any(), any(), any()) }
        assertThat(deliveryDao.getByKey(w7.id, 7, w7.warrantyEndDate)!!.status).isEqualTo("SENT")
        assertThat(deliveryDao.getByKey(w30.id, 30, w30.warrantyEndDate)!!.status).isEqualTo("SENT")
    }

    // -------------------------------------------------------------------------
    // Preserved behavior
    // -------------------------------------------------------------------------

    @Test
    fun `no expiring warranties sends no notification`() = runTest {
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(7) } returns emptyList()
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(30) } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    @Test
    fun `worker handles non-transient exception as failure`() = runTest {
        coEvery { warrantyRepository.reconcileExpiredItems(any<Long>()) } throws IllegalStateException("db unavailable")
        // P9-NEW-13: "db unavailable" matches NO transient keyword and is not a
        // RetryableWorkerException, so the REAL guard classifies it as a PERMANENT
        // failure -> Result.failure(). The faithful default guard stub (see setup)
        // models classifyTransient + RetryableWorkerException precedence, so this
        // documents the TRUE production result instead of a blanket-Retry lie.
        val result = buildWorker().doWork()

        assertEquals(Result.failure(), result)
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    @Test
    fun `worker retries when block throws RetryableWorkerException`() = runTest {
        coEvery { warrantyRepository.reconcileExpiredItems(any<Long>()) } throws
            RetryableWorkerException("transient warranty reconcile failure, will retry")
        // P9-NEW-13: an explicit typed retry signal is recognized by the guard
        // independent of message keywords -> Result.retry(). Together with the
        // non-transient failure test above, both real behaviors are pinned.
        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    @Test
    fun `worker propagates CancellationException instead of returning retry`() = runTest {
        coEvery { warrantyRepository.reconcileExpiredItems(any<Long>()) } throws CancellationException("cancelled")

        try {
            buildWorker().doWork()
            throw AssertionError("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected — cancellation must not be swallowed
        }
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    @Test
    fun `worker reconciles expired items before notifications`() = runTest {
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(7) } returns emptyList()
        coEvery { warrantyRepository.getWarrantiesExpiringSoon(30) } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        io.mockk.coVerify(exactly = 1) { warrantyRepository.reconcileExpiredItems(any<Long>()) }
    }

    @Test
    fun `warranty run skipped when notifications disabled does not notify`() = runTest {
        // The guard owns the notification-permission gate (S1): when notifications are
        // disabled it short-circuits with a durable SKIPPED run and never invokes the
        // worker block, so no notification is sent.
        coEvery { executionGuard.runGuarded(any(), any<suspend () -> Any>()) } returns
            WorkerGuardResult.Skipped(DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED.name)

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }
}
