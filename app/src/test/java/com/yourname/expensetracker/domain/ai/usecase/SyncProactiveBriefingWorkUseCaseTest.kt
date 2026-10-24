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
