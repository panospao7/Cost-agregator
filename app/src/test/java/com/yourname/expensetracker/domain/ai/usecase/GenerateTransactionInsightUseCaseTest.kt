package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.DashboardBriefing
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.model.TransactionInsightAmountBucket
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.DashboardBriefingService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GenerateTransactionInsightUseCaseTest {

    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var aiArtifactRepository: AiArtifactRepository
    private lateinit var dashboardBriefingService: DashboardBriefingService
    private lateinit var aiCapabilityRouter: AiCapabilityRouter
    private lateinit var aiPolicy: AiPolicy
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var useCase: GenerateTransactionInsightUseCase

    @Before
    fun setup() {
        aiSettingsRepository = mockk()
        aiArtifactRepository = mockk(relaxed = true)
        dashboardBriefingService = mockk()
        aiCapabilityRouter = mockk()
        aiPolicy = mockk()
        timeProvider = FakeTimeProvider(fixedTime = NOW)

        useCase = GenerateTransactionInsightUseCase(
            aiSettingsRepository = aiSettingsRepository,
            aiArtifactRepository = aiArtifactRepository,
            dashboardBriefingService = dashboardBriefingService,
            aiCapabilityRouter = aiCapabilityRouter,
            aiPolicy = aiPolicy,
            inputBuilder = TransactionInsightInputBuilder(),
            timeProvider = timeProvider
        )
    }

    @Test
    fun `invoke redacts merchant and exact amount for redacted cloud mode`() = runTest {
        val transaction = Expense(
            id = 42L,
            amount = 187.43,
            currency = "EUR",
            merchant = "Secret Market",
            transactionType = TransactionType.PURCHASE,
            date = NOW
        )
        val inputSlot = slot<DashboardBriefingInput>()

        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(
                aiEnabled = true,
                allowCloudAi = true,
                dashboardBriefingEnabled = true,
                redactBeforeCloud = true
            )
        )
        coEvery { aiCapabilityRouter.decide(AiCapability.DASHBOARD_BRIEFING, any(), any()) } returns AiRouteDecision(
            route = AiRoute.CLOUD,
            reason = "Cloud allowed",
            providerName = AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_PROVIDER,
            modelName = AppConfig.Ai.DASHBOARD_BRIEFING_CLOUD_MODEL
        )
        every { aiPolicy.shouldRedact(any(), AiCapability.DASHBOARD_BRIEFING) } returns true
        coEvery { dashboardBriefingService.generate(capture(inputSlot)) } returns AiServiceResult.Success(
            DashboardBriefing(
                title = "Insight",
                text = "Looks fine",
                tone = "neutral"
            )
        )

        val result = useCase(transaction)

        assertNotNull(result)
        assertEquals(AiMode.CLOUD, result?.mode)
        assertRedacted(inputSlot.captured, transaction)
        coVerify(exactly = 1) { dashboardBriefingService.generate(any()) }
    }

    private fun assertRedacted(input: DashboardBriefingInput, transaction: Expense) {
        val insight = input.transactionInsight

        assertNotNull(insight)
        assertEquals(transaction.merchant, insight?.merchantName)
        assertTrue(insight?.redactForPrompt == true)
        assertEquals(TransactionInsightAmountBucket.RANGE_100_249, insight?.amountBucket)
        assertEquals(175.0, input.totalCommitted, 0.0)
        assertEquals(175.0, input.currentMonthSpent, 0.0)
        assertTrue(input.budgetWarnings.isEmpty())
        assertTrue(input.upcomingItems.isEmpty())
        assertEquals("", renderUiText(input.weatherHeadline))
        assertEquals("", renderUiText(input.weatherSummary))
    }

    private fun renderUiText(text: UiText): String = when (text) {
        is UiText.DynamicString -> text.value
        is UiText.MessageKey -> if (text.args.isEmpty()) text.key else "${text.key} ${text.args.joinToString(", ")}"
        is UiText.StringResource -> text.resId.toString()
        is UiText.PluralResource -> text.resId.toString()
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
