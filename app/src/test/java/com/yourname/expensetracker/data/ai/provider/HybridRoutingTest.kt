package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.AssistantUnsupportedReason
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.CategoryOption
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/**
 * Hybrid AI service routing tables (merged from HybridServiceDelegationTest and
 * HybridDedupeJudgeServiceTest). Privacy: every row asserts exactly-0 invocations of
 * every provider the routing decision did not select, in particular the cloud provider.
 */
class HybridRoutingTest {

    private enum class Target { CLOUD, ON_DEVICE, NO_OP }

    private data class DelegationRow(
        val name: String,
        val mode: AiMode,
        val aiEnabled: Boolean,
        val route: AiRoute,
        val routeReason: String,
        val target: Target,
        val receiptResult: AiServiceResult<ReceiptAssistSuggestion>,
        val categorizationResult: CategoryAssistSuggestion?,
        val queryResult: FinancialQueryInterpretationResult,
        val verifySettingsReadOnce: Boolean = false
    )

    private data class UsedImageRow(
        val name: String,
        val mode: AiMode,
        val aiEnabled: Boolean,
        val inputs: List<ReceiptAssistInput>,
        val verifyCloudNotConsulted: Boolean
    )

    // ---- routing decision -> exactly one provider table ----

    @Test
    fun `routing decision delegates every hybrid service to exactly the routed provider`() = runTest {
        val rows = delegationRows()
        assertEquals("delegation table must cover cloud, on-device, fallback and disabled", 4, rows.size)
        rows.forEach { assertDelegation(it) }
    }

    private fun delegationRows(): List<DelegationRow> = listOf(
        DelegationRow(
            name = "CLOUD route (cloud mode)",
            mode = AiMode.CLOUD,
            aiEnabled = true,
            route = AiRoute.CLOUD,
            routeReason = "Cloud mode",
            target = Target.CLOUD,
            receiptResult = AiServiceResult.Success(ReceiptAssistSuggestion(notes = listOf("cloud"))),
            categorizationResult = CategoryAssistSuggestion(
                categoryId = 10L,
                categoryName = "Groceries",
                confidence = 0.91f,
                rationale = "cloud"
            ),
            queryResult = FinancialQueryInterpretationResult.Unsupported(AssistantUnsupportedReason.PROVIDER_UNAVAILABLE)
        ),
        DelegationRow(
            name = "ON_DEVICE route (on-device mode)",
            mode = AiMode.ON_DEVICE,
            aiEnabled = true,
            route = AiRoute.ON_DEVICE,
            routeReason = "On-device mode",
            target = Target.ON_DEVICE,
            receiptResult = AiServiceResult.Success(ReceiptAssistSuggestion(notes = listOf("on-device"))),
            categorizationResult = CategoryAssistSuggestion(
                categoryId = 20L,
                categoryName = "Transport",
                confidence = 0.88f,
                rationale = "on-device"
            ),
            queryResult = FinancialQueryInterpretationResult.Unsupported(AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID)
        ),
        DelegationRow(
            name = "DETERMINISTIC_FALLBACK route (auto mode)",
            mode = AiMode.AUTO,
            aiEnabled = true,
            route = AiRoute.DETERMINISTIC_FALLBACK,
            routeReason = "Fallback mode",
            target = Target.NO_OP,
            receiptResult = AiServiceResult.Failure(AiServiceError.Disabled("fallback")),
            categorizationResult = null,
            queryResult = FinancialQueryInterpretationResult.Unsupported(AssistantUnsupportedReason.INTERPRETATION_FAILED)
        ),
        DelegationRow(
            name = "DISABLED route (ai disabled)",
            mode = AiMode.AUTO,
            aiEnabled = false,
            route = AiRoute.DISABLED,
            routeReason = "Disabled",
            target = Target.NO_OP,
            receiptResult = AiServiceResult.Failure(AiServiceError.Disabled("disabled")),
            categorizationResult = null,
            queryResult = FinancialQueryInterpretationResult.Unsupported(AssistantUnsupportedReason.INTERPRETATION_DISABLED),
            verifySettingsReadOnce = true
        )
    )

    private suspend fun assertDelegation(row: DelegationRow) {
        val settings = aiSettings(mode = row.mode, aiEnabled = row.aiEnabled)
        val receipt = receiptHarness(settings)
        val categorization = categorizationHarness(settings)
        val query = queryHarness(settings)
        val decision = AiRouteDecision(row.route, row.routeReason)

        coEvery { receipt.router.decide(AiCapability.RECEIPT_EXTRACTION, settings, any()) } returns decision
        coEvery { categorization.router.decide(AiCapability.CATEGORIZATION_FALLBACK, settings, any()) } returns decision
        coEvery { query.router.decide(AiCapability.QUERY_INTERPRETATION, settings, any()) } returns decision

        when (row.target) {
            Target.CLOUD -> {
                coEvery { receipt.cloudService.suggest(receiptInput) } returns row.receiptResult
                coEvery { categorization.cloudService.suggest(categorizationInput) } returns row.categorizationResult
                coEvery { query.cloudService.interpret(queryInput) } returns row.queryResult
            }
            Target.ON_DEVICE -> {
                coEvery { receipt.onDeviceService.suggest(receiptInput) } returns row.receiptResult
                coEvery { categorization.onDeviceService.suggest(categorizationInput) } returns row.categorizationResult
                coEvery { query.onDeviceService.interpret(queryInput) } returns row.queryResult
            }
            Target.NO_OP -> {
                coEvery { receipt.noOpService.suggest(receiptInput) } returns row.receiptResult
                coEvery { categorization.noOpService.suggest(categorizationInput) } returns row.categorizationResult
                coEvery { query.noOpService.interpret(queryInput) } returns row.queryResult
            }
        }

        val receiptResult = receipt.service.suggest(receiptInput)
        val categorizationResult = categorization.service.suggest(categorizationInput)
        val queryResult = query.service.interpret(queryInput)

        assertEquals("[${row.name}] receipt result", row.receiptResult, receiptResult)
        assertEquals("[${row.name}] categorization result", row.categorizationResult, categorizationResult)
        assertEquals("[${row.name}] query result", row.queryResult, queryResult)

        val cloudCalls = if (row.target == Target.CLOUD) 1 else 0
        val onDeviceCalls = if (row.target == Target.ON_DEVICE) 1 else 0
        val noOpCalls = if (row.target == Target.NO_OP) 1 else 0

        rowCheck(row.name, "receipt cloud invocations") {
            coVerify(exactly = cloudCalls) {
                receipt.cloudService.suggest(if (cloudCalls == 1) receiptInput else any())
            }
        }
        rowCheck(row.name, "categorization cloud invocations") {
            coVerify(exactly = cloudCalls) {
                categorization.cloudService.suggest(if (cloudCalls == 1) categorizationInput else any())
            }
        }
        rowCheck(row.name, "query cloud invocations") {
            coVerify(exactly = cloudCalls) {
                query.cloudService.interpret(if (cloudCalls == 1) queryInput else any())
            }
        }
        rowCheck(row.name, "receipt on-device invocations") {
            coVerify(exactly = onDeviceCalls) {
                receipt.onDeviceService.suggest(if (onDeviceCalls == 1) receiptInput else any())
            }
        }
        rowCheck(row.name, "categorization on-device invocations") {
            coVerify(exactly = onDeviceCalls) {
                categorization.onDeviceService.suggest(if (onDeviceCalls == 1) categorizationInput else any())
            }
        }
        rowCheck(row.name, "query on-device invocations") {
            coVerify(exactly = onDeviceCalls) {
                query.onDeviceService.interpret(if (onDeviceCalls == 1) queryInput else any())
            }
        }
        rowCheck(row.name, "receipt no-op invocations") {
            coVerify(exactly = noOpCalls) {
                receipt.noOpService.suggest(if (noOpCalls == 1) receiptInput else any())
            }
        }
        rowCheck(row.name, "categorization no-op invocations") {
            coVerify(exactly = noOpCalls) {
                categorization.noOpService.suggest(if (noOpCalls == 1) categorizationInput else any())
            }
        }
        rowCheck(row.name, "query no-op invocations") {
            coVerify(exactly = noOpCalls) {
                query.noOpService.interpret(if (noOpCalls == 1) queryInput else any())
            }
        }

        if (row.verifySettingsReadOnce) {
            rowCheck(row.name, "receipt settings reads") {
                verify(exactly = 1) { receipt.aiSettingsRepository.settings() }
            }
            rowCheck(row.name, "categorization settings reads") {
                verify(exactly = 1) { categorization.aiSettingsRepository.settings() }
            }
            rowCheck(row.name, "query settings reads") {
                verify(exactly = 1) { query.aiSettingsRepository.settings() }
            }
        }
    }

    // ---- usedImageInput compatibility shim table ----

    @Test
    fun `usedImageInput is false on every hybrid route and never consults cloud where asserted`() {
        val rows = usedImageRows()
        assertEquals("usedImageInput table must cover cloud, on-device and disabled routes", 3, rows.size)
        rows.forEach { row ->
            val harness = receiptHarness(aiSettings(mode = row.mode, aiEnabled = row.aiEnabled))
            row.inputs.forEachIndexed { index, input ->
                assertFalse(
                    "[${row.name}] usedImageInput for input #$index must never over-report",
                    harness.service.usedImageInput(input)
                )
            }
            if (row.verifyCloudNotConsulted) {
                rowCheck(row.name, "cloud usedImageInput invocations") {
                    verify(exactly = 0) { harness.cloudService.usedImageInput(any()) }
                }
            }
        }
    }

    private fun usedImageRows(): List<UsedImageRow> = listOf(
        UsedImageRow(
            name = "CLOUD mode with image metadata and plain input",
            mode = AiMode.CLOUD,
            aiEnabled = true,
            inputs = listOf(
                imageInput(
                    id = 2L,
                    ocr = "SCAN 9.99",
                    path = "/storage/emulated/0/receipt.jpg",
                    merchant = "Superstore",
                    currency = "USD"
                ),
                receiptInput
            ),
            verifyCloudNotConsulted = false
        ),
        UsedImageRow(
            name = "ON_DEVICE mode with image metadata",
            mode = AiMode.ON_DEVICE,
            aiEnabled = true,
            inputs = listOf(
                imageInput(
                    id = 3L,
                    ocr = "SCAN 5.00",
                    path = "/storage/emulated/0/scan.jpg",
                    merchant = "Local Shop",
                    currency = "EUR"
                )
            ),
            verifyCloudNotConsulted = true
        ),
        UsedImageRow(
            name = "DISABLED (ai off) with image metadata",
            mode = AiMode.AUTO,
            aiEnabled = false,
            inputs = listOf(
                imageInput(
                    id = 4L,
                    ocr = "SCAN 3.00",
                    path = "/storage/emulated/0/img.jpg",
                    merchant = "Corner Store",
                    currency = "EUR"
                )
            ),
            verifyCloudNotConsulted = true
        )
    )

    private fun imageInput(
        id: Long,
        ocr: String,
        path: String,
        merchant: String,
        currency: String
    ): ReceiptAssistInput = ReceiptAssistInput(
        receiptId = id,
        rawOcrText = ocr,
        imagePath = path,
        imageMimeType = "image/jpeg",
        isImageAnalysisMode = true,
        redactBeforeCloud = false,
        parsedMerchant = merchant,
        parsedTotal = null,
        parsedDate = null,
        parsedTaxAmount = null,
        currency = currency,
        lineItemsJson = null,
        currentTimeMs = 1_700_000_000_000
    )

    // ---- dedupe judge CLOUD route (R6 regression: no swallowed cancellation, no raw text leak) ----

    private class DedupeHarness {
        val settingsMock = mockk<AiSettings>()
        val settingsRepository = mockk<AiSettingsRepository> {
            every { settings() } returns flowOf(settingsMock)
        }
        val router = mockk<AiCapabilityRouter> {
            coEvery { decide(any(), settingsMock) } returns AiRouteDecision(AiRoute.CLOUD, "test_route")
        }
        val cloud = mockk<CloudDedupeJudgeService>()
        val onDevice = mockk<OnDeviceDedupeJudgeService>()
        val noOp = mockk<NoOpDedupeJudgeService>()
        val input = mockk<DedupeJudgeInput>()
        val loggedLines = mutableListOf<String>()
        val loggedThrowables = mutableListOf<Throwable>()
        val service = HybridDedupeJudgeService(settingsRepository, router, cloud, onDevice, noOp)
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                loggedLines += message
                if (t != null) loggedThrowables += t
            }
        }
    }

    @Test
    fun `dedupe cloud route propagates CancellationException and never falls back to on-device`() = runTest {
        val h = DedupeHarness()
        Timber.plant(h.tree)
        try {
            coEvery { h.cloud.judge(any()) } throws CancellationException("cancelled")
            coEvery { h.onDevice.judge(any()) } returns AiServiceResult.Failure(AiServiceError.Offline)

            try {
                h.service.judge(h.input)
                fail("Expected CancellationException to propagate")
            } catch (_: CancellationException) {
                // expected
            }

            coVerify(exactly = 1) { h.cloud.judge(any()) }
            coVerify(exactly = 0) { h.onDevice.judge(any()) }
        } finally {
            Timber.uproot(h.tree)
        }
    }

    @Test
    fun `dedupe cloud route exception text never reaches the result or logs`() = runTest {
        val h = DedupeHarness()
        Timber.plant(h.tree)
        try {
            val sentinel = "R6SENTINEL_9f2c"
            coEvery { h.cloud.judge(any()) } throws RuntimeException("boom $sentinel merchant text")
            coEvery { h.onDevice.judge(any()) } throws RuntimeException("device $sentinel")

            val result = h.service.judge(h.input)

            assertTrue(result is AiServiceResult.Failure)
            val error = (result as AiServiceResult.Failure).error
            assertEquals(AiServiceError.Unknown("UNKNOWN_ERROR"), error)
            assertFalse(result.toString().contains(sentinel))
            assertTrue("expected at least one log line", h.loggedLines.isNotEmpty())
            assertTrue(
                "no log line may contain the raw exception text",
                h.loggedLines.none { it.contains(sentinel) }
            )
            assertTrue("no throwable may be attached to logs", h.loggedThrowables.isEmpty())
        } finally {
            Timber.uproot(h.tree)
        }
    }

    // ---- fixtures ----

    private inline fun rowCheck(rowName: String, what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("[$rowName] $what: ${e.message}", e)
        }
    }

    private fun aiSettings(mode: AiMode, aiEnabled: Boolean): AiSettings {
        return AiSettings(
            aiEnabled = aiEnabled,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            receiptAssistEnabled = true,
            categorizationFallbackEnabled = true,
            queryInterpretationEnabled = true,
            preferredMode = mode
        )
    }

    private fun receiptHarness(settings: AiSettings): ReceiptHarness {
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val router = mockk<AiCapabilityRouter>()
        val cloudService = mockk<CloudReceiptAssistService>()
        val onDeviceService = mockk<OnDeviceReceiptAssistService>()
        val noOpService = mockk<NoOpReceiptAssistService>()
        every { aiSettingsRepository.settings() } returns flowOf(settings)

        val service = HybridReceiptAssistService(
            aiSettingsRepository = aiSettingsRepository,
            router = router,
            cloudReceiptAssistService = cloudService,
            onDeviceReceiptAssistService = onDeviceService,
            noOpReceiptAssistService = noOpService
        )

        return ReceiptHarness(
            service = service,
            aiSettingsRepository = aiSettingsRepository,
            router = router,
            cloudService = cloudService,
            onDeviceService = onDeviceService,
            noOpService = noOpService
        )
    }

    private fun categorizationHarness(settings: AiSettings): CategorizationHarness {
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val router = mockk<AiCapabilityRouter>()
        val cloudService = mockk<CloudCategorizationAssistService>()
        val onDeviceService = mockk<OnDeviceCategorizationAssistService>()
        val noOpService = mockk<NoOpCategorizationAssistService>()
        every { aiSettingsRepository.settings() } returns flowOf(settings)

        val service = HybridCategorizationAssistService(
            aiSettingsRepository = aiSettingsRepository,
            router = router,
            cloudCategorizationAssistService = cloudService,
            onDeviceCategorizationAssistService = onDeviceService,
            noOpCategorizationAssistService = noOpService
        )

        return CategorizationHarness(
            service = service,
            aiSettingsRepository = aiSettingsRepository,
            router = router,
            cloudService = cloudService,
            onDeviceService = onDeviceService,
            noOpService = noOpService
        )
    }

    private fun queryHarness(settings: AiSettings): QueryHarness {
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val router = mockk<AiCapabilityRouter>()
        val cloudService = mockk<CloudQueryInterpretationService>()
        val onDeviceService = mockk<OnDeviceQueryInterpretationService>()
        val noOpService = mockk<NoOpQueryInterpretationService>()
        every { aiSettingsRepository.settings() } returns flowOf(settings)

        val service = HybridQueryInterpretationService(
            aiSettingsRepository = aiSettingsRepository,
            router = router,
            cloudQueryInterpretationService = cloudService,
            onDeviceQueryInterpretationService = onDeviceService,
            noOpQueryInterpretationService = noOpService
        )

        return QueryHarness(
            service = service,
            aiSettingsRepository = aiSettingsRepository,
            router = router,
            cloudService = cloudService,
            onDeviceService = onDeviceService,
            noOpService = noOpService
        )
    }

    private data class ReceiptHarness(
        val service: HybridReceiptAssistService,
        val aiSettingsRepository: AiSettingsRepository,
        val router: AiCapabilityRouter,
        val cloudService: CloudReceiptAssistService,
        val onDeviceService: OnDeviceReceiptAssistService,
        val noOpService: NoOpReceiptAssistService
    )

    private data class CategorizationHarness(
        val service: HybridCategorizationAssistService,
        val aiSettingsRepository: AiSettingsRepository,
        val router: AiCapabilityRouter,
        val cloudService: CloudCategorizationAssistService,
        val onDeviceService: OnDeviceCategorizationAssistService,
        val noOpService: NoOpCategorizationAssistService
    )

    private data class QueryHarness(
        val service: HybridQueryInterpretationService,
        val aiSettingsRepository: AiSettingsRepository,
        val router: AiCapabilityRouter,
        val cloudService: CloudQueryInterpretationService,
        val onDeviceService: OnDeviceQueryInterpretationService,
        val noOpService: NoOpQueryInterpretationService
    )

    private companion object {
        private val receiptInput = ReceiptAssistInput(
            receiptId = 1L,
            rawOcrText = "MILK 2.50",
            imagePath = null,
            imageMimeType = null,
            isImageAnalysisMode = false,
            redactBeforeCloud = false,
            parsedMerchant = "Mini Market",
            parsedTotal = null,
            parsedDate = null,
            parsedTaxAmount = null,
            currency = "EUR",
            lineItemsJson = null,
            currentTimeMs = 1_700_000_000_000
        )

        private val categorizationInput = CategorizationAssistInput(
            targetType = AiTargetType.PENDING_REVIEW,
            targetId = 44L,
            merchant = "Metro",
            amount = 25.0,
            currency = "EUR",
            transactionType = DomainTransactionType.PURCHASE,
            date = 1_700_000_000_000,
            currentCategoryId = null,
            deterministicMatchType = null,
            deterministicExplanation = null,
            candidateCategories = listOf(
                CategoryOption(id = 10L, name = "Groceries"),
                CategoryOption(id = 20L, name = "Transport")
            )
        )

        private val queryInput = FinancialQueryInterpretationInput(
            rawQuery = "How much did I spend this month?",
            currentTimeMs = 1_700_000_000_000,
            localeTag = "en-US"
        )
    }
}
