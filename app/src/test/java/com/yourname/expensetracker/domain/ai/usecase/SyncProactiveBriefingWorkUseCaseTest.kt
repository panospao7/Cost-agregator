package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.AiWorkScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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

    @Test
    fun `invoke cancels work when proactive briefings are disabled`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                dashboardBriefingEnabled = true,
                proactiveBriefingsEnabled = false
            )
        )

        useCase()

        verify { aiWorkScheduler.cancelDailyBriefing() }
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
