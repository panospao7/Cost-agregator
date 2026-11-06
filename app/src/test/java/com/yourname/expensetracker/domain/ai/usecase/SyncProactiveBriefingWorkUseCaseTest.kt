package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.AiWorkScheduler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SyncProactiveBriefingWorkUseCaseTest {

    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var aiWorkScheduler: AiWorkScheduler
    private lateinit var useCase: SyncProactiveBriefingWorkUseCase

    @Before
    fun setup() {
        aiSettingsRepository = mockk(relaxed = true)
        aiWorkScheduler = mockk(relaxed = true)
        useCase = SyncProactiveBriefingWorkUseCase(aiSettingsRepository, aiWorkScheduler)
    }

    @Test
    fun `invoke schedules work when proactive dashboard briefings are fully enabled`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                dashboardBriefingEnabled = true,
                proactiveBriefingsEnabled = true
            )
        )

        useCase()

        verify { aiWorkScheduler.scheduleDailyBriefing() }
    }

    // AE-45 (retargeted from "cancels work when proactive briefings are disabled": that
    // holds only when allowCloudAi is false after INV-25).
    @Test
    fun `invoke cancels work when proactive briefings are disabled and cloud AI is not allowed`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                allowCloudAi = false,
                dashboardBriefingEnabled = true,
                proactiveBriefingsEnabled = false
            )
        )

        useCase()

        verify(exactly = 1) { aiWorkScheduler.cancelDailyBriefing() }
        verify(exactly = 0) { aiWorkScheduler.scheduleDailyBriefing() }
    }

    // AE-46
    @Test
    fun `invoke schedules work when cloud AI is not allowed but proactive briefings are on`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                allowCloudAi = false,
                dashboardBriefingEnabled = true,
                proactiveBriefingsEnabled = true
            )
        )

        useCase()

        verify(exactly = 1) { aiWorkScheduler.scheduleDailyBriefing() }
        verify(exactly = 0) { aiWorkScheduler.cancelDailyBriefing() }
    }

    // AE-47: all 16 (aiEnabled, briefing, allowCloudAi, proactive) combinations.
    @Test
    fun `invoke arms work exactly when ai and briefing and cloud or proactive`() = runTest {
        val booleans = listOf(false, true)
        var rows = 0
        for (ai in booleans) for (briefing in booleans) for (cloud in booleans) for (proactive in booleans) {
            val scheduler = mockk<AiWorkScheduler>(relaxed = true)
            val repository = mockk<AiSettingsRepository>(relaxed = true)
            every { repository.settings() } returns flowOf(
                AiSettings(
                    aiEnabled = ai,
                    allowCloudAi = cloud,
                    dashboardBriefingEnabled = briefing,
                    proactiveBriefingsEnabled = proactive
                )
            )

            SyncProactiveBriefingWorkUseCase(repository, scheduler)()

            val expectedArmed = ai && briefing && (cloud || proactive)
            // Row label is visible in the MockK failure context via the loop variables.
            verify(exactly = if (expectedArmed) 1 else 0) { scheduler.scheduleDailyBriefing() }
            verify(exactly = if (expectedArmed) 0 else 1) { scheduler.cancelDailyBriefing() }
            rows++
        }
        assertEquals(16, rows)
    }

    // AE-42 arming half / INV-24: a cloud-route user with proactive off is armed.
    @Test
    fun `invoke schedules work for a cloud route user with proactive briefings off`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                allowCloudAi = true,
                dashboardBriefingEnabled = true,
                proactiveBriefingsEnabled = false
            )
        )

        useCase()

        verify(exactly = 1) { aiWorkScheduler.scheduleDailyBriefing() }
        verify(exactly = 0) { aiWorkScheduler.cancelDailyBriefing() }
    }

    // AE-48 (INV-26, Sync half): Sync reads AI settings and talks to the AI scheduler only.
    @Test
    fun `invoke touches only the settings repository and the AI work scheduler`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                allowCloudAi = true,
                dashboardBriefingEnabled = true,
                proactiveBriefingsEnabled = true
            )
        )

        useCase()

        verify(exactly = 1) { aiSettingsRepository.settings() }
        verify(exactly = 1) { aiWorkScheduler.scheduleDailyBriefing() }
        confirmVerified(aiWorkScheduler)
    }

    // J-09-1: off to on while armed enqueues exactly one immediate run.
    @Test
    fun `off to on with work armed enqueues one immediate briefing`() = runTest {
        val previous = AiSettings(aiEnabled = true, allowCloudAi = true, dashboardBriefingEnabled = false)
        val current = previous.copy(dashboardBriefingEnabled = true)

        useCase(current, previousSettings = previous)

        verify(exactly = 1) { aiWorkScheduler.scheduleDailyBriefing() }
        verify(exactly = 1) { aiWorkScheduler.enqueueBriefingNow() }
        verify(exactly = 0) { aiWorkScheduler.cancelDailyBriefing() }
    }

    @Test
    fun `off to on via proactive route without cloud also enqueues once`() = runTest {
        val previous = AiSettings(
            aiEnabled = true,
            allowCloudAi = false,
            dashboardBriefingEnabled = false,
            proactiveBriefingsEnabled = true
        )

        useCase(previous.copy(dashboardBriefingEnabled = true), previousSettings = previous)

        verify(exactly = 1) { aiWorkScheduler.enqueueBriefingNow() }
    }

    @Test
    fun `on to on never enqueues an immediate briefing`() = runTest {
        val previous = AiSettings(aiEnabled = true, allowCloudAi = true, dashboardBriefingEnabled = true)

        useCase(previous.copy(proactiveBriefingsEnabled = true), previousSettings = previous)

        verify(exactly = 1) { aiWorkScheduler.scheduleDailyBriefing() }
        verify(exactly = 0) { aiWorkScheduler.enqueueBriefingNow() }
    }

    @Test
    fun `null previous settings never enqueue an immediate briefing`() = runTest {
        useCase(
            AiSettings(aiEnabled = true, allowCloudAi = true, dashboardBriefingEnabled = true),
            previousSettings = null
        )

        verify(exactly = 1) { aiWorkScheduler.scheduleDailyBriefing() }
        verify(exactly = 0) { aiWorkScheduler.enqueueBriefingNow() }
    }

    @Test
    fun `startup sync reading repository settings never enqueues an immediate briefing`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, allowCloudAi = true, dashboardBriefingEnabled = true)
        )

        useCase()

        verify(exactly = 0) { aiWorkScheduler.enqueueBriefingNow() }
    }

    @Test
    fun `off to on while not armed cancels and never enqueues`() = runTest {
        // aiEnabled false: briefing toggle flips on but the predicate is not armed.
        val previous = AiSettings(aiEnabled = false, allowCloudAi = true, dashboardBriefingEnabled = false)
        useCase(previous.copy(dashboardBriefingEnabled = true), previousSettings = previous)

        // cloud off and proactive off: also not armed.
        val previousNoRoute = AiSettings(
            aiEnabled = true,
            allowCloudAi = false,
            dashboardBriefingEnabled = false,
            proactiveBriefingsEnabled = false
        )
        useCase(previousNoRoute.copy(dashboardBriefingEnabled = true), previousSettings = previousNoRoute)

        verify(exactly = 2) { aiWorkScheduler.cancelDailyBriefing() }
        verify(exactly = 0) { aiWorkScheduler.scheduleDailyBriefing() }
        verify(exactly = 0) { aiWorkScheduler.enqueueBriefingNow() }
    }

    @Test
    fun `on to off never enqueues and cancels`() = runTest {
        val previous = AiSettings(aiEnabled = true, allowCloudAi = true, dashboardBriefingEnabled = true)

        useCase(previous.copy(dashboardBriefingEnabled = false), previousSettings = previous)

        verify(exactly = 1) { aiWorkScheduler.cancelDailyBriefing() }
        verify(exactly = 0) { aiWorkScheduler.enqueueBriefingNow() }
    }

    @Test
    fun `invoke uses provided override settings`() = runTest {
        useCase(
            AiSettings(
                aiEnabled = false,
                dashboardBriefingEnabled = true,
                proactiveBriefingsEnabled = true
            )
        )

        verify { aiWorkScheduler.cancelDailyBriefing() }
    }
}
