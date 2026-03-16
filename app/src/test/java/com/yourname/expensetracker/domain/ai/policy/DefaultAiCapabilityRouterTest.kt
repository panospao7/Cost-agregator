package com.yourname.expensetracker.domain.ai.policy

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DefaultAiCapabilityRouterTest {

    private lateinit var policy: AiPolicy
    private lateinit var environmentMonitor: AiEnvironmentMonitor
    private lateinit var router: DefaultAiCapabilityRouter

    @Before
    fun setup() {
        policy = AiPolicyImpl()
        environmentMonitor = mockk()
        router = DefaultAiCapabilityRouter(policy, environmentMonitor)
    }

    @Test
    fun `decide returns DISABLED when AI is off`() {
        val result = router.decide(AiCapability.REVIEW_EXPLANATION, AiSettings(aiEnabled = false))
        assertEquals(AiRoute.DISABLED, result.route)
    }

    @Test
    fun `decide returns CLOUD in AUTO for cloud-first capability when network available`() {
        every { environmentMonitor.isNetworkAvailable() } returns true
        every { environmentMonitor.isWifiConnected() } returns true
        every { environmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.NOT_INSTALLED

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
    fun `decide returns ON_DEVICE in AUTO for on-device-first capability when local model available`() {
        every { environmentMonitor.isNetworkAvailable() } returns false
        every { environmentMonitor.isWifiConnected() } returns false
        every { environmentMonitor.getOnDeviceModelStatus(AiCapability.CATEGORIZATION_FALLBACK) } returns OnDeviceModelStatus.AVAILABLE

        val settings = AiSettings(
            aiEnabled = true,
            allowOnDeviceAi = true,
            categorizationFallbackEnabled = true,
            preferredMode = AiMode.AUTO
        )

        val result = router.decide(AiCapability.CATEGORIZATION_FALLBACK, settings)

        assertEquals(AiRoute.ON_DEVICE, result.route)
    }

    @Test
    fun `decide respects wifiOnlyForCloud and falls back when wifi unavailable`() {
        every { environmentMonitor.isNetworkAvailable() } returns true
        every { environmentMonitor.isWifiConnected() } returns false
        every { environmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.NOT_INSTALLED

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
    fun `decide respects ON_DEVICE preferred mode without cloud fallback`() {
        every { environmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.NOT_INSTALLED

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            receiptAssistEnabled = true,
            preferredMode = AiMode.ON_DEVICE
        )

        val result = router.decide(AiCapability.RECEIPT_EXTRACTION, settings)

        assertEquals(AiRoute.DETERMINISTIC_FALLBACK, result.route)
        assertTrue(result.reason.contains("not installed", ignoreCase = true))
    }

    @Test
    fun `decide reports unsupported android version for on-device preferred mode`() {
        every { environmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.UNSUPPORTED_ANDROID_VERSION

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
    fun `decide reports unsupported device when auto has no cloud and no local`() {
        every { environmentMonitor.isNetworkAvailable() } returns false
        every { environmentMonitor.isWifiConnected() } returns false
        every { environmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.UNSUPPORTED_DEVICE

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
    fun `decide reports downloading model when on-device preferred mode is waiting on runtime`() {
        every { environmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.DOWNLOADING

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
    fun `decide reports unavailable model when auto has no cloud fallback`() {
        every { environmentMonitor.isNetworkAvailable() } returns false
        every { environmentMonitor.isWifiConnected() } returns false
        every { environmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.UNAVAILABLE

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
