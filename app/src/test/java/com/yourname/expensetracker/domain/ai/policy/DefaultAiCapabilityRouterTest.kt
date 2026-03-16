package com.yourname.expensetracker.domain.ai.policy

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DefaultAiCapabilityRouterTest {

    private lateinit var policy: AiPolicy
    private lateinit var environmentMonitor: AiEnvironmentMonitor
    private lateinit var aiRuntimeDiagnostics: AiRuntimeDiagnostics
    private lateinit var router: DefaultAiCapabilityRouter

    @Before
    fun setup() {
        policy = AiPolicyImpl()
        environmentMonitor = mockk()
        aiRuntimeDiagnostics = mockk(relaxed = true)
        router = DefaultAiCapabilityRouter(policy, environmentMonitor, aiRuntimeDiagnostics)
    }

    @Test
    fun `decide returns DISABLED when AI is off`() = runTest {
        val result = router.decide(AiCapability.REVIEW_EXPLANATION, AiSettings(aiEnabled = false))
        assertEquals(AiRoute.DISABLED, result.route)
    }

    @Test
    fun `decide returns CLOUD in AUTO for cloud-first capability when network available`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns true
        every { environmentMonitor.isWifiConnected() } returns true
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.REVIEW_EXPLANATION) } returns OnDeviceModelStatus.NOT_INSTALLED

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            reviewExplanationEnabled = true,
            preferredMode = AiMode.AUTO
        )

        val result = router.decide(AiCapability.REVIEW_EXPLANATION, settings)

        assertEquals(AiRoute.CLOUD, result.route)
    }

    @Test
    fun `decide returns ON_DEVICE in AUTO for on-device-first capability when local model available`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns false
        every { environmentMonitor.isWifiConnected() } returns false
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.CATEGORIZATION_FALLBACK) } returns OnDeviceModelStatus.AVAILABLE

        val settings = AiSettings(
            aiEnabled = true,
            allowOnDeviceAi = true,
            categorizationFallbackEnabled = true,
            preferredMode = AiMode.AUTO
        )

        val result = router.decide(AiCapability.CATEGORIZATION_FALLBACK, settings)

        assertEquals(AiRoute.ON_DEVICE, result.route)
        assertEquals(AppConfig.Ai.ON_DEVICE_PROVIDER_NAME, result.providerName)
        assertEquals(AppConfig.Ai.ON_DEVICE_CATEGORIZATION_MODEL, result.modelName)
    }

    @Test
    fun `decide returns ON_DEVICE for receipt extraction when local model available`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns false
        every { environmentMonitor.isWifiConnected() } returns false
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.RECEIPT_EXTRACTION) } returns OnDeviceModelStatus.AVAILABLE

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            receiptAssistEnabled = true,
            preferredMode = AiMode.AUTO
        )

        val result = router.decide(AiCapability.RECEIPT_EXTRACTION, settings)

        assertEquals(AiRoute.ON_DEVICE, result.route)
        assertEquals(AppConfig.Ai.ON_DEVICE_PROVIDER_NAME, result.providerName)
        assertEquals(AppConfig.Ai.ON_DEVICE_RECEIPT_MODEL, result.modelName)
    }

    @Test
    fun `decide returns ON_DEVICE for review explanation when local model available`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns false
        every { environmentMonitor.isWifiConnected() } returns false
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.REVIEW_EXPLANATION) } returns OnDeviceModelStatus.AVAILABLE

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            reviewExplanationEnabled = true,
            preferredMode = AiMode.AUTO
        )

        val result = router.decide(AiCapability.REVIEW_EXPLANATION, settings)

        assertEquals(AiRoute.ON_DEVICE, result.route)
        assertEquals(AppConfig.Ai.ON_DEVICE_PROVIDER_NAME, result.providerName)
        assertEquals(AppConfig.Ai.ON_DEVICE_REVIEW_MODEL, result.modelName)
    }

    @Test
    fun `decide returns ON_DEVICE for dedupe judge when local model available`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns false
        every { environmentMonitor.isWifiConnected() } returns false
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.DEDUPE_JUDGE) } returns OnDeviceModelStatus.AVAILABLE

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            dedupeJudgeEnabled = true,
            preferredMode = AiMode.AUTO
        )

        val result = router.decide(AiCapability.DEDUPE_JUDGE, settings)

        assertEquals(AiRoute.ON_DEVICE, result.route)
        assertEquals(AppConfig.Ai.ON_DEVICE_PROVIDER_NAME, result.providerName)
        assertEquals(AppConfig.Ai.ON_DEVICE_DEDUPE_MODEL, result.modelName)
    }

    @Test
    fun `decide respects wifiOnlyForCloud and falls back when wifi unavailable`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns true
        every { environmentMonitor.isWifiConnected() } returns false
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.REVIEW_EXPLANATION) } returns OnDeviceModelStatus.NOT_INSTALLED

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            reviewExplanationEnabled = true,
            wifiOnlyForCloud = true,
            preferredMode = AiMode.CLOUD
        )

        val result = router.decide(AiCapability.REVIEW_EXPLANATION, settings)

        assertEquals(AiRoute.DETERMINISTIC_FALLBACK, result.route)
    }

    @Test
    fun `decide reports not implemented for unshipped on-device capability`() = runTest {
        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            queryInterpretationEnabled = true,
            preferredMode = AiMode.ON_DEVICE
        )

        val result = router.decide(AiCapability.QUERY_INTERPRETATION, settings)

        assertEquals(AiRoute.DETERMINISTIC_FALLBACK, result.route)
        assertTrue(result.reason.contains("not implemented", ignoreCase = true))
    }

    @Test
    fun `decide reports unsupported android version for on-device preferred mode`() = runTest {
        coEvery { environmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.UNSUPPORTED_ANDROID_VERSION

        val settings = AiSettings(
            aiEnabled = true,
            allowOnDeviceAi = true,
            categorizationFallbackEnabled = true,
            preferredMode = AiMode.ON_DEVICE
        )

        val result = router.decide(AiCapability.CATEGORIZATION_FALLBACK, settings)

        assertEquals(AiRoute.DETERMINISTIC_FALLBACK, result.route)
        assertTrue(result.reason.contains("Android version", ignoreCase = true))
    }

    @Test
    fun `decide reports unsupported device when auto has no cloud and no local`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns false
        every { environmentMonitor.isWifiConnected() } returns false
        coEvery { environmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.UNSUPPORTED_DEVICE

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            categorizationFallbackEnabled = true,
            preferredMode = AiMode.AUTO
        )

        val result = router.decide(AiCapability.CATEGORIZATION_FALLBACK, settings)

        assertEquals(AiRoute.DETERMINISTIC_FALLBACK, result.route)
        assertTrue(result.reason.contains("device", ignoreCase = true))
    }

    @Test
    fun `decide reports downloading model when on-device preferred mode is waiting on runtime`() = runTest {
        coEvery { environmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.DOWNLOADING

        val settings = AiSettings(
            aiEnabled = true,
            allowOnDeviceAi = true,
            categorizationFallbackEnabled = true,
            preferredMode = AiMode.ON_DEVICE
        )

        val result = router.decide(AiCapability.CATEGORIZATION_FALLBACK, settings)

        assertEquals(AiRoute.DETERMINISTIC_FALLBACK, result.route)
        assertTrue(result.reason.contains("downloading", ignoreCase = true))
    }

    @Test
    fun `decide reports unavailable model when auto has no cloud fallback`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns false
        every { environmentMonitor.isWifiConnected() } returns false
        coEvery { environmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.UNAVAILABLE

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            categorizationFallbackEnabled = true,
            preferredMode = AiMode.AUTO
        )

        val result = router.decide(AiCapability.CATEGORIZATION_FALLBACK, settings)

        assertEquals(AiRoute.DETERMINISTIC_FALLBACK, result.route)
        assertTrue(result.reason.contains("unavailable", ignoreCase = true))
    }
}
