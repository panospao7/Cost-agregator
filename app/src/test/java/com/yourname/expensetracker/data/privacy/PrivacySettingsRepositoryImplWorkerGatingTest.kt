package com.yourname.expensetracker.data.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.workers.WorkerRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Worker-gating tests for [PrivacySettingsRepositoryImpl.applyPrivacyChange].
 *
 * P9-P1-11 / PR8 regression guards. These assert that the merchant-key
 * over-cancel bug and the no-reschedule bug cannot recur:
 *  - Disabling background location must NOT cancel `merchant_key_backfill`
 *    (merchant-key generation is local), but MUST cancel `location_backfill`.
 *  - Disabling notification capture cancels the notification-derived workers
 *    while keeping `data_retention` running.
 *  - Re-enabling a toggle reschedules its workers (the old code only cancelled).
 *
 * [WorkManager] is statically mocked so cancel calls can be asserted directly,
 * matching [com.yourname.expensetracker.domain.workers.WorkerSpecSchedulerTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivacySettingsRepositoryImplWorkerGatingTest {

    private lateinit var context: Context
    private val workManager: WorkManager = mockk(relaxed = true)
    private lateinit var repository: PrivacySettingsRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
        // Constructor reads WorkManager.getInstance(context) — now mocked.
        repository = PrivacySettingsRepositoryImpl(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `disabling background location does not cancel merchant_key_backfill`() {
        val old = PrivacySettings(backgroundLocationBackfillEnabled = true)
        val persisted = old.copy(backgroundLocationBackfillEnabled = false)

        repository.applyPrivacyChange(old, persisted)

        verify(exactly = 0) { workManager.cancelUniqueWork("merchant_key_backfill") }
    }

    @Test
    fun `disabling background location cancels location_backfill`() {
        val old = PrivacySettings(backgroundLocationBackfillEnabled = true)
        val persisted = old.copy(backgroundLocationBackfillEnabled = false)

        repository.applyPrivacyChange(old, persisted)

        verify(exactly = 1) { workManager.cancelUniqueWork("location_backfill") }
    }

    @Test
    fun `disabling notification capture cancels receipt warranty bill but keeps data_retention`() {
        val old = PrivacySettings(notificationCaptureEnabled = true)
        val persisted = old.copy(notificationCaptureEnabled = false)

        repository.applyPrivacyChange(old, persisted)

        verify(exactly = 1) { workManager.cancelUniqueWork("receipt_matching") }
        verify(exactly = 1) { workManager.cancelUniqueWork("warranty_expiration_check") }
        verify(exactly = 1) { workManager.cancelUniqueWork("bill_reminder_periodic") }
        verify(exactly = 0) { workManager.cancelUniqueWork("data_retention") }
    }

    @Test
    fun `re-enabling cloud AI reschedules ai_daily_briefing`() {
        // Route reschedule through a fake WorkerRegistry entry so the call is
        // observable without standing up the real worker companions.
        mockkObject(WorkerRegistry)
        val scheduled = mutableListOf<String>()
        every { WorkerRegistry.entries } returns listOf(
            WorkerRegistry.Entry("ai_daily_briefing") { scheduled.add("ai_daily_briefing") }
        )

        val old = PrivacySettings(cloudAiEnabled = false)
        val persisted = old.copy(cloudAiEnabled = true)

        repository.applyPrivacyChange(old, persisted)

        assertTrue(
            "Re-enabling cloud AI must reschedule ai_daily_briefing",
            scheduled.contains("ai_daily_briefing")
        )
        // Re-enable path must not cancel anything.
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }

    @Test
    fun `no transition leaves workers untouched`() {
        val settings = PrivacySettings()

        repository.applyPrivacyChange(settings, settings)

        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }

    @Test
    fun `disabling background location does not cancel notification workers`() {
        // Isolation guard: only the flipped toggle's workers are affected.
        val old = PrivacySettings(backgroundLocationBackfillEnabled = true)
        val persisted = old.copy(backgroundLocationBackfillEnabled = false)

        repository.applyPrivacyChange(old, persisted)

        verify(exactly = 0) { workManager.cancelUniqueWork("receipt_matching") }
        verify(exactly = 0) { workManager.cancelUniqueWork("ai_daily_briefing") }
        // data_retention is never gated by any privacy toggle.
        verify(exactly = 0) { workManager.cancelUniqueWork("data_retention") }
    }
}
