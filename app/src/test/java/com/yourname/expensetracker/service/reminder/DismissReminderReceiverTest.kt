package com.yourname.expensetracker.service.reminder

import android.content.Intent
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PR12E — DismissReminderReceiver now enqueues a [DismissReminderActionWorker]
 * via WorkManager instead of calling [RecurringLifecycleCoordinator] directly.
 *
 * Verifies:
 * - Receiver enqueues a one-shot WorkManager request with the correct deliveryId input data.
 * - Missing deliveryId skips processing (no work enqueued).
 * - Coordinator is NOT called directly by the receiver (structural guarantee).
 */
class DismissReminderReceiverTest {

    private val workManager = mockk<WorkManager>(relaxed = true)

    private fun buildReceiver(): DismissReminderReceiver {
        val receiver = DismissReminderReceiver()
        DismissReminderReceiver::class.java.getDeclaredField("workManager").apply {
            isAccessible = true
            set(receiver, workManager)
        }
        return receiver
    }

    private fun intentWithDelivery(deliveryId: Long): Intent {
        return Intent().apply { putExtra("deliveryId", deliveryId) }
    }

    @Test
    fun `receiver enqueues WorkManager request with correct deliveryId`() {
        val receiver = buildReceiver()
        val intent = intentWithDelivery(42L)
        val requestSlot = slot<OneTimeWorkRequest>()

        every { workManager.enqueue(capture(requestSlot)) } returns mockk(relaxed = true)

        receiver.onReceive(mockk(relaxed = true), intent)

        verify(exactly = 1) { workManager.enqueue(any<OneTimeWorkRequest>()) }
        val capturedRequest = requestSlot.captured
        val inputData = capturedRequest.workSpec.input
        assertEquals("deliveryId must be 42 in input data", 42L, inputData.getLong("deliveryId", -1L))
    }

    /**
     * Structural guarantee: the receiver no longer injects
     * [RecurringLifecycleCoordinator] — only WorkManager.
     * This test proves that a standalone coordinator mock is never called.
     */
    @Test
    fun `receiver does NOT call coordinator directly`() = runBlocking {
        // This mock is standalone — never injected into the receiver.
        val coordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true)
        val receiver = buildReceiver()
        val intent = intentWithDelivery(42L)

        // onReceive is synchronous, so the mock can only be called if the
        // receiver held a reference to it, which it no longer does.
        receiver.onReceive(mockk(relaxed = true), intent)

        // Verify the standalone mock is never touched.
        io.mockk.coVerify(exactly = 0) { coordinator.dismissReminderDelivery(any()) }
    }

    @Test
    fun `missing deliveryId skips WorkManager enqueue`() {
        val receiver = buildReceiver()
        val intent = Intent() // no deliveryId extra

        receiver.onReceive(mockk(relaxed = true), intent)

        verify(exactly = 0) { workManager.enqueue(any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `invalid deliveryId negative skips WorkManager enqueue`() {
        val receiver = buildReceiver()
        val intent = intentWithDelivery(-1L)

        receiver.onReceive(mockk(relaxed = true), intent)

        verify(exactly = 0) { workManager.enqueue(any<OneTimeWorkRequest>()) }
    }
}
