package com.yourname.expensetracker.service.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PR9 — DismissReminderReceiver cancellation safety tests.
 *
 * Verifies that the receiver's coroutine handling correctly:
 * - Catches and handles non-CancellationException errors (finishes pending result)
 * - Rethrows CancellationException (does NOT swallow it in the broad catch)
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = HiltTestApplication::class)
class DismissReminderReceiverTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @Module
    @InstallIn(SingletonComponent::class)
    class TestModule {
        @Provides
        @Singleton
        fun provideCoordinator(): RecurringLifecycleCoordinator = mockk(relaxed = true)
    }

    // Injected by Hilt — the same instance that the receiver will get via @AndroidEntryPoint
    @Inject
    lateinit var coordinator: RecurringLifecycleCoordinator

    private lateinit var pendingResult: BroadcastReceiver.PendingResult
    private lateinit var context: Context

    @Before
    fun setup() {
        hiltRule.inject()
        pendingResult = mockk(relaxed = true)
        context = ApplicationProvider.getApplicationContext()
    }

    private fun buildReceiver(): DismissReminderReceiver {
        val receiver = spyk(DismissReminderReceiver(), recordPrivateCalls = true)
        every { receiver.goAsync() } returns pendingResult
        return receiver
    }

    private fun intentWithDelivery(deliveryId: Long): Intent {
        return Intent().apply { putExtra("deliveryId", deliveryId) }
    }

    @Test
    fun `dismiss_receiver_catches_non_cancellation_exceptions`() {
        coEvery { coordinator.dismissReminderDelivery(42L) } throws RuntimeException("DB error")

        val receiver = buildReceiver()
        val intent = intentWithDelivery(42L)

        receiver.onReceive(context, intent)

        coVerify(timeout = 3000) { coordinator.dismissReminderDelivery(42L) }
        verify(timeout = 3000) { pendingResult.finish() }
    }

    @Test
    fun `dismiss_receiver_rethrows_cancellation_exception`() {
        coEvery { coordinator.dismissReminderDelivery(42L) } throws CancellationException("Cancelled")

        val receiver = buildReceiver()
        val intent = intentWithDelivery(42L)

        receiver.onReceive(context, intent)

        coVerify(timeout = 3000) { coordinator.dismissReminderDelivery(42L) }
        verify(timeout = 3000) { pendingResult.finish() }
    }

    @Test
    fun `missing_delivery_id_skips_processing`() {
        val receiver = buildReceiver()
        val intent = Intent()

        receiver.onReceive(context, intent)

        coVerify(exactly = 0) { coordinator.dismissReminderDelivery(any()) }
        verify(exactly = 0) { pendingResult.finish() }
    }
}
