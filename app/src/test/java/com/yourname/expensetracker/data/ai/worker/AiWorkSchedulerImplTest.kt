package com.yourname.expensetracker.data.ai.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * J-09-1: [AiWorkSchedulerImpl.enqueueBriefingNow] contract. WorkManager is statically mocked
 * (same approach as WorkerSpecSchedulerTest) so the exact unique name, policy, delay and
 * constraints of the enqueued request can be asserted. The real [com.yourname.expensetracker.domain.workers.WorkerSpec.DEFAULTS]
 * is used, so a spec change that drops network or battery protections also fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AiWorkSchedulerImplTest {

    private lateinit var context: Context
    private val workManager: WorkManager = mockk(relaxed = true)
    private val timeProvider = FakeTimeProvider(1716163200000L)
    private lateinit var scheduler: AiWorkSchedulerImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
        context.getSharedPreferences("worker_spec_versions", Context.MODE_PRIVATE)
            .edit().clear().commit()
        scheduler = AiWorkSchedulerImpl(context, timeProvider)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `enqueueBriefingNow uses the daily briefing unique name with REPLACE`() {
        val nameSlot = slot<String>()
        val policySlot = slot<ExistingWorkPolicy>()
        every {
            workManager.enqueueUniqueWork(capture(nameSlot), capture(policySlot), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)

        scheduler.enqueueBriefingNow()

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>())
        }
        assertEquals("ai_daily_briefing", nameSlot.captured)
        assertEquals(ExistingWorkPolicy.REPLACE, policySlot.captured)
    }

    @Test
    fun `enqueueBriefingNow REPLACE wins even when the persisted spec version is current`() {
        val policies = mutableListOf<ExistingWorkPolicy>()
        every {
            workManager.enqueueUniqueWork(any<String>(), capture(policies), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)

        scheduler.enqueueBriefingNow()
        // Second call: version is now persisted, so the spec's KEEP would apply without the override.
        scheduler.enqueueBriefingNow()

        assertEquals(listOf(ExistingWorkPolicy.REPLACE, ExistingWorkPolicy.REPLACE), policies)
    }

    @Test
    fun `enqueueBriefingNow requests an immediate run without charging and keeps network and battery protections`() {
        val requestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(any<String>(), any(), capture(requestSlot))
        } returns mockk(relaxed = true)

        scheduler.enqueueBriefingNow()

        val workSpec = requestSlot.captured.workSpec
        assertEquals("no initial delay", 0L, workSpec.initialDelay)
        assertFalse("must not wait for charging", workSpec.constraints.requiresCharging())
        assertEquals(NetworkType.UNMETERED, workSpec.constraints.requiredNetworkType)
        assertTrue("battery-not-low must stay", workSpec.constraints.requiresBatteryNotLow())
        assertEquals(
            DailyBriefingWorker::class.java.name,
            workSpec.workerClassName
        )
    }

    @Test
    fun `enqueueBriefingNow does not cancel the unique work`() {
        every {
            workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)

        scheduler.enqueueBriefingNow()

        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }
}
