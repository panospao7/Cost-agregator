package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.ai.provider.OnDeviceDashboardBriefingService
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.DashboardBriefing
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.AppForegroundState
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * B2d pass 1: on-device-only transaction insight (INV-15..21, AE-25..32, AE-39, AE-40).
 *
 * The two pre-existing cloud-redaction tests were retargeted: the cloud route no longer exists
 * for the insight, so "redacts on cloud route" became AE-26 (CLOUD decision rejected, no provider
 * call) and "redacts on cloud route when privacy requires it" became AE-25 (ON_DEVICE artifact
 * with capability TRANSACTION_INSIGHT, unredacted input).
 */
class GenerateTransactionInsightUseCaseTest {

    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var onDeviceService: OnDeviceDashboardBriefingService
    private lateinit var aiCapabilityRouter: AiCapabilityRouter
    private lateinit var appForegroundState: AppForegroundState
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var diagnostics: AiRuntimeDiagnostics
    private lateinit var useCase: GenerateTransactionInsightUseCase

    @Before
    fun setup() {
        aiSettingsRepository = mockk()
        onDeviceService = mockk()
        aiCapabilityRouter = mockk()
        appForegroundState = mockk()
        timeProvider = FakeTimeProvider(fixedTime = NOW)
        diagnostics = AiRuntimeDiagnostics(timeProvider)

        every { appForegroundState.isAppInForeground() } returns true
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        coEvery { aiCapabilityRouter.decide(any(), any(), any()) } returns onDeviceDecision()

        useCase = GenerateTransactionInsightUseCase(
            aiSettingsRepository = aiSettingsRepository,
            onDeviceBriefingService = onDeviceService,
            aiCapabilityRouter = aiCapabilityRouter,
            appForegroundState = appForegroundState,
            inputBuilder = TransactionInsightInputBuilder(),
            timeProvider = timeProvider,
            aiRuntimeDiagnostics = diagnostics
        )
    }

    // ── AE-25 (retargeted from the cloud "redacts merchant and exact amount" test) ──

    @Test
    fun `AE-25 invoke generates an on-device TRANSACTION_INSIGHT artifact with unredacted input`() = runTest {
        val transaction = purchase()
        val inputSlot = slot<DashboardBriefingInput>()
        // Cloud fully enabled and briefing on: neither may influence the insight route.
        every { aiSettingsRepository.settings() } returns flowOf(
            enabledSettings().copy(allowCloudAi = true, dashboardBriefingEnabled = true)
        )
        coEvery { onDeviceService.generate(capture(inputSlot)) } returns success("Big spend at Cafe X")

        val outcome = useCase(transaction)

        assertTrue(outcome is TransactionInsightOutcome.Generated)
        val artifact = outcome.artifactOrNull
        assertNotNull(artifact)
        assertEquals(AiCapability.TRANSACTION_INSIGHT, artifact?.capability)
        assertEquals(AiMode.ON_DEVICE, artifact?.mode)
        assertEquals(AiArtifactStatus.READY, artifact?.status)
        assertEquals("transaction:42", artifact?.targetKey)
        assertEquals(42L, artifact?.targetId)
        assertEquals("Big spend at Cafe X", artifact?.summaryText)
        assertEquals(NOW + AppConfig.Ai.TRANSACTION_INSIGHT_TTL_MS, artifact?.expiresAt)
        assertEquals(NOW + 24L * 60 * 60 * 1000L, artifact?.expiresAt)
        // Input is unredacted: data never leaves the device.
        val insight = inputSlot.captured.transactionInsight
        assertNotNull(insight)
        assertFalse(insight?.redactForPrompt == true)
        assertEquals(transaction.merchant, insight?.merchantName)
        coVerify(exactly = 1) { onDeviceService.generate(any()) }
        coVerify(exactly = 1) { aiCapabilityRouter.decide(AiCapability.TRANSACTION_INSIGHT, any(), any()) }
        coVerify(exactly = 0) { aiCapabilityRouter.decide(AiCapability.DASHBOARD_BRIEFING, any(), any()) }
        assertTrue(diagnostics.getRecentEvents().isEmpty())
    }

    @Test
    fun `AE-25 summary text is truncated to the briefing length cap`() = runTest {
        coEvery { onDeviceService.generate(any()) } returns
            success("x".repeat(AppConfig.Ai.MAX_BRIEFING_LENGTH_CHARS + 50))

        val outcome = useCase(purchase())

        assertEquals(AppConfig.Ai.MAX_BRIEFING_LENGTH_CHARS, outcome.artifactOrNull?.summaryText?.length)
    }

    @Test
    fun `AE-25 insight never changes the expense`() = runTest {
        val transaction = purchase()
        val before = transaction.copy()
        coEvery { onDeviceService.generate(any()) } returns success("Insight")

        useCase(transaction)

        assertEquals(before, transaction)
    }

    // ── AE-26 (retargeted from the cloud "redacts when privacy requires it" test) ──

    @Test
    fun `AE-26 a CLOUD route decision is rejected with zero provider calls`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            enabledSettings().copy(allowCloudAi = true, redactBeforeCloud = true)
        )
        coEvery { aiCapabilityRouter.decide(AiCapability.TRANSACTION_INSIGHT, any(), any()) } returns
            AiRouteDecision(route = AiRoute.CLOUD, reason = "Cloud allowed")

        val outcome = useCase(purchase())

        assertEquals(TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.ROUTE_REJECTED), outcome)
        coVerify(exactly = 0) { onDeviceService.generate(any()) }
        assertEquals(listOf("ROUTE_REJECTED"), diagnosticMessages())
    }

    @Test
    fun `AE-26 DISABLED and DETERMINISTIC_FALLBACK routes are skipped as ROUTE_NOT_ON_DEVICE`() = runTest {
        for (route in listOf(AiRoute.DISABLED, AiRoute.DETERMINISTIC_FALLBACK)) {
            coEvery { aiCapabilityRouter.decide(any(), any(), any()) } returns
                AiRouteDecision(route = route, reason = "no")

            val outcome = useCase(purchase())

            assertEquals(
                TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.ROUTE_NOT_ON_DEVICE),
                outcome
            )
        }
        coVerify(exactly = 0) { onDeviceService.generate(any()) }
    }

    // ── AE-28 foreground short-circuit (INV-17) ────────────────────────────────

    @Test
    fun `AE-28 background app is skipped before any router or provider call`() = runTest {
        every { appForegroundState.isAppInForeground() } returns false

        val outcome = useCase(purchase())

        assertEquals(TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.APP_NOT_FOREGROUND), outcome)
        assertNull(outcome.artifactOrNull)
        coVerify(exactly = 0) { aiCapabilityRouter.decide(any(), any(), any()) }
        coVerify(exactly = 0) { onDeviceService.generate(any()) }
        assertEquals(listOf("APP_NOT_FOREGROUND"), diagnosticMessages())
        assertEquals("transaction_insight", diagnostics.getRecentEvents().single().type)
    }

    // ── AE-29 provider errors collapse to PROVIDER_ERROR (INV-19, G2-12) ───────

    @Test
    fun `AE-29 GenAI failure result is PROVIDER_ERROR and diagnostics carry only the enum name`() = runTest {
        coEvery { onDeviceService.generate(any()) } returns
            AiServiceResult.Failure(AiServiceError.Unknown("GenAI error code=9"))

        val outcome = useCase(purchase())

        assertEquals(TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.PROVIDER_ERROR), outcome)
        assertEquals(listOf("PROVIDER_ERROR"), diagnosticMessages())
    }

    @Test
    fun `AE-29 a thrown provider exception is PROVIDER_ERROR and its text is never logged`() = runTest {
        val secret = "Secret Market 187.43"
        val tree = CapturingTree()
        Timber.plant(tree)
        try {
            coEvery { onDeviceService.generate(any()) } throws IllegalStateException(secret)

            val outcome = useCase(purchase())

            assertEquals(TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.PROVIDER_ERROR), outcome)
        } finally {
            Timber.uproot(tree)
        }
        assertTrue(tree.entries.isNotEmpty())
        assertTrue(tree.entries.all { it.throwable == null })
        assertTrue(tree.entries.none { it.message.contains(secret) || it.message.contains("Secret") })
        assertEquals(listOf("PROVIDER_ERROR"), diagnosticMessages())
    }

    @Test
    fun `AE-29 other service errors map to bounded reasons`() = runTest {
        coEvery { onDeviceService.generate(any()) } returns
            AiServiceResult.Failure(AiServiceError.ParseError("Empty response"))
        assertEquals(
            TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.EMPTY_OUTPUT),
            useCase(purchase())
        )

        coEvery { onDeviceService.generate(any()) } returns AiServiceResult.Failure(AiServiceError.Timeout)
        assertEquals(
            TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.TIMEOUT),
            useCase(purchase())
        )

        coEvery { onDeviceService.generate(any()) } returns success("   ")
        assertEquals(
            TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.EMPTY_OUTPUT),
            useCase(purchase())
        )
    }

    // ── AE-30 timeout (INV-18) ─────────────────────────────────────────────────

    @Test
    fun `AE-30 a provider that never returns is Skipped TIMEOUT after the insight timeout`() = runTest {
        coEvery { onDeviceService.generate(any()) } coAnswers { awaitCancellation() }

        var outcome: TransactionInsightOutcome? = null
        val job = launch { outcome = useCase(purchase()) }
        runCurrent()
        advanceTimeBy(AppConfig.Ai.TRANSACTION_INSIGHT_TIMEOUT_MS - 1)
        runCurrent()
        assertNull(outcome)
        advanceTimeBy(2)
        runCurrent()
        job.join()

        assertEquals(TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.TIMEOUT), outcome)
        assertEquals(listOf("TIMEOUT"), diagnosticMessages())
    }

    @Test
    fun `AE-30 a router decision that never returns is Skipped TIMEOUT with zero provider calls`() = runTest {
        coEvery { aiCapabilityRouter.decide(any(), any(), any()) } coAnswers { awaitCancellation() }

        var outcome: TransactionInsightOutcome? = null
        val job = launch { outcome = useCase(purchase()) }
        runCurrent()
        advanceTimeBy(AppConfig.Ai.TRANSACTION_INSIGHT_TIMEOUT_MS - 1)
        runCurrent()
        assertNull(outcome)
        advanceTimeBy(2)
        runCurrent()
        job.join()

        assertEquals(TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.TIMEOUT), outcome)
        coVerify(exactly = 0) { onDeviceService.generate(any()) }
        assertEquals(listOf("TIMEOUT"), diagnosticMessages())
    }

    @Test
    fun `AE-30 a slow router decision shares the same insight timeout budget as generation`() = runTest {
        coEvery { aiCapabilityRouter.decide(any(), any(), any()) } coAnswers {
            delay(AppConfig.Ai.TRANSACTION_INSIGHT_TIMEOUT_MS - 500)
            onDeviceDecision()
        }
        coEvery { onDeviceService.generate(any()) } coAnswers { delay(1_000L); success("late") }

        val outcome = useCase(purchase())

        assertEquals(TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.TIMEOUT), outcome)
        assertEquals(AppConfig.Ai.TRANSACTION_INSIGHT_TIMEOUT_MS, currentTime)
    }

    @Test
    fun `AE-30 constants keep the recommendation job budget above the insight timeout`() {
        assertEquals(3_000L, AppConfig.Ai.TRANSACTION_INSIGHT_TIMEOUT_MS)
        assertEquals(5_000L, AppConfig.Ai.RECOMMENDATION_JOB_TIMEOUT_MS)
        assertTrue(
            AppConfig.Ai.RECOMMENDATION_JOB_TIMEOUT_MS >=
                AppConfig.Ai.TRANSACTION_INSIGHT_TIMEOUT_MS + 2_000L
        )
    }

    // ── AE-31 cancellation is rethrown (INV-4) ─────────────────────────────────

    @Test
    fun `AE-31 a CancellationException from the provider is rethrown and no outcome is recorded`() = runTest {
        coEvery { onDeviceService.generate(any()) } throws CancellationException("cancelled")

        try {
            useCase(purchase())
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            // expected
        }

        assertTrue(diagnostics.getRecentEvents().isEmpty())
    }

    @Test
    fun `AE-31 cancelling the caller while the provider is suspended records nothing`() = runTest {
        coEvery { onDeviceService.generate(any()) } coAnswers { delay(Long.MAX_VALUE); success("never") }

        var outcome: TransactionInsightOutcome? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) { outcome = useCase(purchase()) }
        runCurrent()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertNull(outcome)
        assertTrue(diagnostics.getRecentEvents().isEmpty())
    }

    // ── AE-32 / AE-40 gates (INV-15) ───────────────────────────────────────────

    @Test
    fun `AE-32 insight is attempted when briefing is off and insight is on`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            enabledSettings().copy(dashboardBriefingEnabled = false, proactiveBriefingsEnabled = false)
        )
        coEvery { onDeviceService.generate(any()) } returns success("Insight")

        val outcome = useCase(purchase())

        assertTrue(outcome is TransactionInsightOutcome.Generated)
    }

    @Test
    fun `AE-32 insight off with briefing on is INSIGHT_DISABLED without router or provider calls`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            enabledSettings().copy(dashboardBriefingEnabled = true, transactionInsightEnabled = false)
        )

        val outcome = useCase(purchase())

        assertEquals(TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.INSIGHT_DISABLED), outcome)
        coVerify(exactly = 0) { aiCapabilityRouter.decide(any(), any(), any()) }
        coVerify(exactly = 0) { onDeviceService.generate(any()) }
        assertEquals(listOf("INSIGHT_DISABLED"), diagnosticMessages())
    }

    @Test
    fun `AI disabled is AI_DISABLED and wins over the other gates`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            enabledSettings().copy(aiEnabled = false, transactionInsightEnabled = false, allowOnDeviceAi = false)
        )

        val outcome = useCase(purchase())

        assertEquals(TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.AI_DISABLED), outcome)
        coVerify(exactly = 0) { aiCapabilityRouter.decide(any(), any(), any()) }
        coVerify(exactly = 0) { onDeviceService.generate(any()) }
    }

    @Test
    fun `gates are evaluated before the foreground check`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings().copy(aiEnabled = false))

        useCase(purchase())

        io.mockk.verify(exactly = 0) { appForegroundState.isAppInForeground() }
    }

    @Test
    fun `AE-40 allowOnDeviceAi false is ON_DEVICE_NOT_ALLOWED with zero AICore calls`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings().copy(allowOnDeviceAi = false))

        val outcome = useCase(purchase())

        assertEquals(TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.ON_DEVICE_NOT_ALLOWED), outcome)
        io.mockk.verify(exactly = 0) { appForegroundState.isAppInForeground() }
        coVerify(exactly = 0) { aiCapabilityRouter.decide(any(), any(), any()) }
        coVerify(exactly = 0) { onDeviceService.generate(any()) }
        assertEquals(listOf("ON_DEVICE_NOT_ALLOWED"), diagnosticMessages())
    }

    // ── AE-39 no cloud dependency (INV-16) ─────────────────────────────────────

    @Test
    fun `AE-39 the use case constructor takes exactly the seven allowed dependency types`() {
        val constructor = GenerateTransactionInsightUseCase::class.java.constructors.single()
        val parameterTypeNames = constructor.parameterTypes.map { it.simpleName }

        assertEquals(
            listOf(
                "AiRuntimeDiagnostics",
                "AiSettingsRepository",
                "AiCapabilityRouter",
                "AppForegroundState",
                "OnDeviceDashboardBriefingService",
                "TimeProvider",
                "TransactionInsightInputBuilder"
            ).sorted(),
            parameterTypeNames.sorted()
        )
    }

    @Test
    fun `AE-39 five foreground saves with cloud enabled route only TRANSACTION_INSIGHT and never DASHBOARD_BRIEFING`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            enabledSettings().copy(allowCloudAi = true, dashboardBriefingEnabled = true)
        )
        coEvery { onDeviceService.generate(any()) } returns success("Insight")

        repeat(5) { useCase(purchase()) }

        coVerify(exactly = 5) { onDeviceService.generate(any()) }
        coVerify(exactly = 5) { aiCapabilityRouter.decide(AiCapability.TRANSACTION_INSIGHT, any(), any()) }
        coVerify(exactly = 0) { aiCapabilityRouter.decide(AiCapability.DASHBOARD_BRIEFING, any(), any()) }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun diagnosticMessages(): List<String> = diagnostics.getRecentEvents().map { it.message }

    private fun enabledSettings(): AiSettings = AiSettings(
        aiEnabled = true,
        allowOnDeviceAi = true,
        transactionInsightEnabled = true
    )

    private fun onDeviceDecision(): AiRouteDecision = AiRouteDecision(
        route = AiRoute.ON_DEVICE,
        reason = "On-device available",
        providerName = AppConfig.Ai.ON_DEVICE_PROVIDER_NAME,
        modelName = AppConfig.Ai.ON_DEVICE_BRIEFING_MODEL
    )

    private fun purchase(): Expense = Expense(
        id = 42L,
        amount = 187.43,
        currency = "EUR",
        merchant = "Secret Market",
        transactionType = TransactionType.PURCHASE,
        date = NOW
    )

    private fun success(text: String): AiServiceResult<DashboardBriefing> =
        AiServiceResult.Success(DashboardBriefing(title = "Insight", text = text, tone = "neutral"))

    private class CapturingTree : Timber.Tree() {
        data class Entry(val message: String, val throwable: Throwable?)

        val entries = mutableListOf<Entry>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            entries += Entry(message, t)
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
