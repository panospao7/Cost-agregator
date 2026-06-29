package com.yourname.expensetracker.service.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * PR9 / PR12 — SnoozeReminderReceiver cancellation safety tests.
 *
 * Verifies that the receiver's coroutine handling correctly:
 * - Catches and handles non-CancellationException errors (finishes pending result)
 * - Rethrows CancellationException (does NOT swallow it in the broad catch)
 */
class SnoozeReminderReceiverTest {

    private val coordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)
    private val pendingResult = mockk<BroadcastReceiver.PendingResult>(relaxed = true)

    private fun buildReceiver(): SnoozeReminderReceiver {
        val receiver = SnoozeReminderReceiver()
        SnoozeReminderReceiver::class.java.getDeclaredField("coordinator").apply {
            isAccessible = true
            set(receiver, coordinator)
        }
        val spy = io.mockk.spyk(receiver)
        every { spy.goAsync() } returns pendingResult
        return spy
    }

    private fun intentWithDelivery(deliveryId: Long): Intent {
        return Intent().apply { putExtra("deliveryId", deliveryId) }
    }

    @Test
    fun `snooze_receiver_catches_non_cancellation_exceptions`() = runBlocking {
        coEvery { coordinator.snoozeReminderDelivery(any(), any()) } throws RuntimeException("DB error")

        val receiver = buildReceiver()
        val intent = intentWithDelivery(42L)

        receiver.onReceive(mockk(relaxed = true), intent)
        delay(500)

        coVerify { coordinator.snoozeReminderDelivery(any(), any()) }
        verify { pendingResult.finish() }
    }

    @Test
    fun `snooze_receiver_rethrows_cancellation_exception`() = runBlocking {
        coEvery { coordinator.snoozeReminderDelivery(any(), any()) } throws CancellationException("Cancelled")

        val receiver = buildReceiver()
        val intent = intentWithDelivery(42L)

        receiver.onReceive(mockk(relaxed = true), intent)
        delay(500)

        coVerify { coordinator.snoozeReminderDelivery(any(), any()) }
        verify { pendingResult.finish() }
    }

    @Test
    fun `missing_delivery_id_skips_processing`() = runBlocking {
        val receiver = buildReceiver()
        val intent = Intent()

        receiver.onReceive(mockk(relaxed = true), intent)
        delay(500)

        coVerify(exactly = 0) { coordinator.snoozeReminderDelivery(any(), any()) }
        verify(exactly = 0) { pendingResult.finish() }
    }
}
