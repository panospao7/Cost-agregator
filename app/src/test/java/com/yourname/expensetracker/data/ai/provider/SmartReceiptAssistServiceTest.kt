package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.SuggestedValue
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SmartReceiptAssistService orchestrates an ordered attempt chain (fall-through, bounded
 * attempt codes, usedImageInput); the route table below covers only the first routing step.
 * Privacy: every row asserts exactly-0 invocations of the providers the route must not use.
 */
class SmartReceiptAssistServiceTest {

    private data class RouteRow(
        val name: String,
        val route: AiRoute,
        val onDeviceProbeRoute: AiRoute?,
        val cloudResult: AiServiceResult<ReceiptAssistSuggestion>?,
        val onDeviceResult: AiServiceResult<ReceiptAssistSuggestion>?,
        val noOpResult: AiServiceResult<ReceiptAssistSuggestion>?,
        val expectSuccess: Boolean,
        val cloudCalls: Int,
        val onDeviceCalls: Int,
        val noOpCalls: Int?
    )

    private class Harness(
        val cloud: CloudReceiptAssistService = mockk(),
        val onDevice: OnDeviceReceiptAssistService = mockk(),
        val noOp: NoOpReceiptAssistService = mockk(),
        val router: AiCapabilityRouter = mockk(),
        val settingsRepository: AiSettingsRepository = mockk(),
        val policy: AiPolicy = mockk(),
        val privacyGate: PrivacyGate = mockk()
    ) {
        val service = SmartReceiptAssistService(
            cloudReceiptAssistService = cloud,
            onDeviceReceiptAssistService = onDevice,
            noOpReceiptAssistService = noOp,
            aiCapabilityRouter = router,
            aiSettingsRepository = settingsRepository,
            aiPolicy = policy,
            privacyGate = privacyGate
        )
    }

    @Test
    fun `route decision selects exactly the routed provider and never calls the others`() = runTest {
        val rows = listOf(
            RouteRow(
                name = "ON_DEVICE route never calls cloud",
                route = AiRoute.ON_DEVICE,
                onDeviceProbeRoute = null,
                cloudResult = null,
                onDeviceResult = successfulSuggestionResult(),
                noOpResult = null,
                expectSuccess = true,
                cloudCalls = 0,
                onDeviceCalls = 1,
                noOpCalls = null
            ),
            RouteRow(
                name = "CLOUD route never calls on-device",
                route = AiRoute.CLOUD,
                onDeviceProbeRoute = AiRoute.DETERMINISTIC_FALLBACK,
                cloudResult = successfulSuggestionResult(),
                onDeviceResult = null,
                noOpResult = null,
                expectSuccess = true,
                cloudCalls = 1,
                onDeviceCalls = 0,
                noOpCalls = null
            ),
            RouteRow(
                name = "DETERMINISTIC_FALLBACK route skips both AI providers",
                route = AiRoute.DETERMINISTIC_FALLBACK,
                onDeviceProbeRoute = null,
                cloudResult = null,
                onDeviceResult = null,
                noOpResult = successfulSuggestionResult(),
                expectSuccess = true,
                cloudCalls = 0,
                onDeviceCalls = 0,
                noOpCalls = 1
            ),
            RouteRow(
                name = "DISABLED route skips both AI providers and reports failure",
                route = AiRoute.DISABLED,
                onDeviceProbeRoute = null,
                cloudResult = null,
                onDeviceResult = null,
                noOpResult = AiServiceResult.Failure(AiServiceError.Disabled("Receipt assist unavailable")),
                expectSuccess = false,
                cloudCalls = 0,
                onDeviceCalls = 0,
                noOpCalls = 1
            )
        )
        assertEquals("route table must cover on-device, cloud, fallback and disabled", 4, rows.size)

        rows.forEach { row ->
            val h = harness(row.route, row.onDeviceProbeRoute)
            row.cloudResult?.let { coEvery { h.cloud.suggest(any()) } returns it }
            row.onDeviceResult?.let { coEvery { h.onDevice.suggest(any()) } returns it }
            row.noOpResult?.let { coEvery { h.noOp.suggest(any()) } returns it }

            val result = h.service.suggest(defaultInput())

            assertEquals("[${row.name}] result success flag", row.expectSuccess, result is AiServiceResult.Success)
            rowCheck(row.name, "cloud invocations") {
                coVerify(exactly = row.cloudCalls) { h.cloud.suggest(any()) }
            }
            rowCheck(row.name, "on-device invocations") {
                coVerify(exactly = row.onDeviceCalls) { h.onDevice.suggest(any()) }
            }
            row.noOpCalls?.let { expected ->
                rowCheck(row.name, "no-op invocations") {
                    coVerify(exactly = expected) { h.noOp.suggest(any()) }
                }
            }
        }
    }

    @Test
    fun `suggest falls through from cloud to on device when cloud attempt fails`() = runTest {
        val h = harness(AiRoute.CLOUD, onDeviceProbeRoute = AiRoute.ON_DEVICE)
        coEvery { h.cloud.suggest(any()) } returnsMany listOf(
            AiServiceResult.Failure(AiServiceError.Timeout),
            AiServiceResult.Failure(AiServiceError.Timeout)
        )
        coEvery { h.onDevice.suggest(any()) } returns successfulSuggestionResult()

        val result = h.service.suggest(defaultInput())

        assertTrue(result is AiServiceResult.Success)
        val suggestion = (result as AiServiceResult.Success).value
        assertEquals(2, suggestion.attemptDetails.size)
        assertEquals("CLOUD_VISION", suggestion.attemptDetails[0].method)
        assertEquals("ON_DEVICE_VISION", suggestion.attemptDetails[1].method)
        coVerify(atLeast = 1) { h.cloud.suggest(any()) }
        coVerify(atLeast = 1) { h.onDevice.suggest(any()) }
    }

    @Test
    fun `on-device route retries on-device text then deterministic fallback and never calls cloud`() = runTest {
        val h = harness(AiRoute.ON_DEVICE)
        coEvery { h.onDevice.suggest(any()) } returnsMany listOf(
            AiServiceResult.Failure(AiServiceError.ParseError("bad parse")),
            AiServiceResult.Failure(AiServiceError.ParseError("bad parse"))
        )
        coEvery { h.noOp.suggest(any()) } returns successfulSuggestionResult()

        val result = h.service.suggest(defaultInput())

        assertTrue(result is AiServiceResult.Success)
        val suggestion = (result as AiServiceResult.Success).value
        assertEquals("ON_DEVICE_VISION", suggestion.attemptDetails[0].method)
        assertEquals("ON_DEVICE_TEXT", suggestion.attemptDetails[1].method)
        assertFalse(suggestion.usedImageInput)
        coVerify(exactly = 2) { h.onDevice.suggest(any()) }
        coVerify(exactly = 0) { h.cloud.suggest(any()) }
        coVerify(exactly = 1) { h.noOp.suggest(any()) }
    }

    @Test
    fun `attempt details persist only fixed bounded codes for failed attempts then success`() = runTest {
        val sentinel = "R6BSENTINEL_7d41"
        val h = harness(AiRoute.ON_DEVICE)
        // Attempt 1 (on-device vision) fails with sentinel text, attempt 3 (on-device text) fails too,
        // then the deterministic fallback succeeds.
        coEvery { h.onDevice.suggest(any()) } returnsMany listOf(
            AiServiceResult.Failure(AiServiceError.Unknown(sentinel)),
            AiServiceResult.Failure(AiServiceError.ParseError(sentinel))
        )
        coEvery { h.noOp.suggest(any()) } returns successfulSuggestionResult()

        val result = h.service.suggest(defaultInput())

        assertTrue(result is AiServiceResult.Success)
        val details = (result as AiServiceResult.Success).value.attemptDetails
        assertEquals("UNKNOWN_ERROR", details[0].errorMessage)
        assertEquals("PARSER_FAILED", details[1].errorMessage)
        assertTrue(details.none { it.errorMessage?.contains(sentinel) == true })
        assertTrue(details.none { it.toString().contains(sentinel) })
        assertTrue(details.last().success)
        assertEquals(null, details.last().errorMessage)
    }

    @Test
    fun `usedImageInput reflects the selected execution result`() {
        val h = harness(AiRoute.ON_DEVICE)
        coEvery { h.onDevice.suggest(any()) } returns AiServiceResult.Success(
            ReceiptAssistSuggestion(
                total = SuggestedValue(value = 12.34, confidence = 0.95f),
                usedImageInput = true
            )
        )

        assertTrue(h.service.usedImageInput(defaultInput()))
        assertFalse(h.service.usedImageInput(defaultInput().copy(isImageAnalysisMode = false)))
    }

    private inline fun rowCheck(rowName: String, what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("[$rowName] $what: ${e.message}", e)
        }
    }

    /**
     * Router returns [route] for the user's own settings; when [onDeviceProbeRoute] is set it also
     * answers the on-device viability probe (preferredMode = ON_DEVICE).
     */
    private fun harness(route: AiRoute, onDeviceProbeRoute: AiRoute? = null): Harness {
        val h = Harness()
        val settings = defaultSettings()
        coEvery { h.privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed
        every { h.settingsRepository.settings() } returns flowOf(settings)
        coEvery {
            h.router.decide(AiCapability.RECEIPT_EXTRACTION, match { it.preferredMode == settings.preferredMode }, any())
        } returns AiRouteDecision(route = route, reason = "route under test")
        if (onDeviceProbeRoute != null) {
            coEvery {
                h.router.decide(AiCapability.RECEIPT_EXTRACTION, match { it.preferredMode == AiMode.ON_DEVICE }, any())
            } returns AiRouteDecision(route = onDeviceProbeRoute, reason = "on-device probe")
        }
        return h
    }

    private fun successfulSuggestionResult(): AiServiceResult.Success<ReceiptAssistSuggestion> {
        return AiServiceResult.Success(
            ReceiptAssistSuggestion(
                total = SuggestedValue(value = 12.34, confidence = 0.95f)
            )
        )
    }

    private fun defaultSettings(): AiSettings {
        return AiSettings(
            aiEnabled = true,
            receiptAssistEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            receiptImageCloudEnabled = true
        )
    }

    private fun defaultInput(): ReceiptAssistInput {
        return ReceiptAssistInput(
            receiptId = 1L,
            rawOcrText = "LIDL TOTAL 12.34",
            imagePath = "receipt.jpg",
            imageMimeType = "image/jpeg",
            isImageAnalysisMode = true,
            parsedMerchant = null,
            parsedTotal = null,
            parsedDate = null,
            parsedTaxAmount = null,
            currency = "EUR",
            lineItemsJson = null,
            currentTimeMs = 1_000L
        )
    }
}
