package com.yourname.expensetracker.domain.ai.policy

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DefaultAiCapabilityRouterTest {

    private lateinit var policy: AiPolicy
    private lateinit var environmentMonitor: AiEnvironmentMonitor
    private lateinit var aiRuntimeDiagnostics: AiRuntimeDiagnostics
    private lateinit var secureKeyStorage: SecureKeyStorage
    private lateinit var timeProvider: TimeProvider
    private lateinit var privacyGate: PrivacyGate
    private lateinit var router: DefaultAiCapabilityRouter

    @Before
    fun setup() {
        policy = AiPolicyImpl()
        environmentMonitor = mockk()
        secureKeyStorage = mockk()
        timeProvider = mockk()
        privacyGate = mockk(relaxed = true)
        every { timeProvider.now() } returns 1_700_000_000_000L
        aiRuntimeDiagnostics = AiRuntimeDiagnostics(timeProvider)
        // By default, assume API key is present for existing tests
        every { secureKeyStorage.hasKey(SecureKeyStorage.KEY_GEMINI) } returns true
        router = DefaultAiCapabilityRouter(
            policy,
            environmentMonitor,
            aiRuntimeDiagnostics,
            secureKeyStorage,
            privacyGate,
        )
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
    fun `decide still routes receipt extraction to cloud when image toggle is enabled`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns true
        every { environmentMonitor.isWifiConnected() } returns true
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.RECEIPT_EXTRACTION) } returns OnDeviceModelStatus.UNAVAILABLE

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            receiptAssistEnabled = true,
            receiptImageCloudEnabled = true,
            preferredMode = AiMode.CLOUD
        )

        val result = router.decide(AiCapability.RECEIPT_EXTRACTION, settings)

        assertEquals(AiRoute.CLOUD, result.route)
        assertEquals(AppConfig.Ai.RECEIPT_ASSIST_CLOUD_PROVIDER, result.providerName)
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
    fun `decide routes warranty extraction to cloud in auto when network available`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns true
        every { environmentMonitor.isWifiConnected() } returns true
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.WARRANTY_EXTRACTION) } returns OnDeviceModelStatus.UNAVAILABLE

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            receiptAssistEnabled = true,
            // ai-fixes B1c: the default is now false (INV-7); the router table entry is kept.
            warrantyExtractionEnabled = true,
            preferredMode = AiMode.AUTO
        )

        val result = router.decide(AiCapability.WARRANTY_EXTRACTION, settings)

        assertEquals(AiRoute.CLOUD, result.route)
        assertEquals(AppConfig.Ai.RECEIPT_ASSIST_CLOUD_PROVIDER, result.providerName)
        assertEquals(AppConfig.Ai.RECEIPT_ASSIST_CLOUD_MODEL, result.modelName)
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
        assertTrue(result.reason.contains("Wi-Fi", ignoreCase = true))
    }

    @Test
    fun `decide routes query interpretation to cloud when cloud is preferred`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns true
        every { environmentMonitor.isWifiConnected() } returns true
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.QUERY_INTERPRETATION) } returns OnDeviceModelStatus.UNAVAILABLE

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            queryInterpretationEnabled = true,
            preferredMode = AiMode.CLOUD
        )

        val result = router.decide(AiCapability.QUERY_INTERPRETATION, settings)

        assertEquals(AiRoute.CLOUD, result.route)
        assertEquals(AppConfig.Ai.QUERY_INTERPRETATION_CLOUD_PROVIDER, result.providerName)
        assertEquals(AppConfig.Ai.QUERY_INTERPRETATION_CLOUD_MODEL, result.modelName)
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

    @Test
    fun `decide reports cloud disabled explicitly when cloud setting is off`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns true
        every { environmentMonitor.isWifiConnected() } returns true
        coEvery { environmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.UNAVAILABLE

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = false,
            allowOnDeviceAi = true,
            reviewExplanationEnabled = true,
            preferredMode = AiMode.CLOUD
        )

        val result = router.decide(AiCapability.REVIEW_EXPLANATION, settings)

        assertEquals(AiRoute.DETERMINISTIC_FALLBACK, result.route)
        assertTrue(result.reason.contains("Cloud AI is disabled", ignoreCase = true))
    }

    @Test
    fun `decide falls back to on device when cloud preferred capability has no cloud route but local is available`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns false
        every { environmentMonitor.isWifiConnected() } returns false
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.NOTIFICATION_PARSE) } returns OnDeviceModelStatus.AVAILABLE

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            preferredMode = AiMode.CLOUD
        )

        val result = router.decide(AiCapability.NOTIFICATION_PARSE, settings)

        assertEquals(AiRoute.ON_DEVICE, result.route)
        assertEquals(AppConfig.Ai.ON_DEVICE_PROVIDER_NAME, result.providerName)
    }

    @Test
    fun `decide returns DETERMINISTIC_FALLBACK when on device preferred and local unavailable - no cloud leak`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns true
        every { environmentMonitor.isWifiConnected() } returns true
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.REVIEW_EXPLANATION) } returns OnDeviceModelStatus.UNAVAILABLE

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            reviewExplanationEnabled = true,
            preferredMode = AiMode.ON_DEVICE
        )

        val result = router.decide(AiCapability.REVIEW_EXPLANATION, settings)

        // PRIVACY FIX: ON_DEVICE mode no longer falls back to cloud.
        // User explicitly chose on-device for privacy; cloud is blocked.
        assertEquals(AiRoute.DETERMINISTIC_FALLBACK, result.route)
        assertTrue(result.reason.contains("privacy", ignoreCase = true))
    }

    @Test
    fun `decide returns DETERMINISTIC_FALLBACK when cloud preferred but API key is missing`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns true
        every { environmentMonitor.isWifiConnected() } returns true
        every { secureKeyStorage.hasKey(SecureKeyStorage.KEY_GEMINI) } returns false
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.REVIEW_EXPLANATION) } returns OnDeviceModelStatus.UNAVAILABLE

        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            reviewExplanationEnabled = true,
            preferredMode = AiMode.CLOUD
        )

        val result = router.decide(AiCapability.REVIEW_EXPLANATION, settings)

        assertEquals(AiRoute.DETERMINISTIC_FALLBACK, result.route)
        assertTrue(result.reason.contains("API key", ignoreCase = true))
    }

    // ── TRANSACTION_INSIGHT (ai-fixes B2a, INV-16, AE-27) ─────────────────────

    private fun fullyCloudEnabledSettings(mode: AiMode) = AiSettings(
        aiEnabled = true,
        allowCloudAi = true,
        allowOnDeviceAi = true,
        wifiOnlyForCloud = false,
        dashboardBriefingEnabled = true,
        preferredMode = mode
    )

    @Test
    fun `AE-27 TRANSACTION_INSIGHT is DETERMINISTIC_FALLBACK never CLOUD when on-device NOT_INSTALLED in every mode`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns true
        every { environmentMonitor.isWifiConnected() } returns true
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.TRANSACTION_INSIGHT) } returns OnDeviceModelStatus.NOT_INSTALLED

        val modes = listOf(AiMode.AUTO, AiMode.CLOUD, AiMode.ON_DEVICE)
        assertEquals("mode table size guard", 3, modes.size)

        modes.forEach { mode ->
            val result = router.decide(AiCapability.TRANSACTION_INSIGHT, fullyCloudEnabledSettings(mode))

            assertEquals("[$mode] route", AiRoute.DETERMINISTIC_FALLBACK, result.route)
            assertNull("[$mode] no provider name on fallback", result.providerName)
        }

        // Cloud was never consulted for TRANSACTION_INSIGHT (checked before the positive control below).
        coVerify(exactly = 0) { privacyGate.check(any(), any()) }
        verify(exactly = 0) { secureKeyStorage.hasKey(any()) }

        // Positive control: the same fully cloud-enabled settings do route a cloud-capable capability to CLOUD.
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING) } returns OnDeviceModelStatus.NOT_INSTALLED
        val control = router.decide(AiCapability.DASHBOARD_BRIEFING, fullyCloudEnabledSettings(AiMode.CLOUD))
        assertEquals("positive control route", AiRoute.CLOUD, control.route)
    }

    @Test
    fun `TRANSACTION_INSIGHT routes ON_DEVICE and never CLOUD when local model is available in every mode`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns true
        every { environmentMonitor.isWifiConnected() } returns true
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.TRANSACTION_INSIGHT) } returns OnDeviceModelStatus.AVAILABLE

        listOf(AiMode.AUTO, AiMode.CLOUD, AiMode.ON_DEVICE).forEach { mode ->
            val result = router.decide(AiCapability.TRANSACTION_INSIGHT, fullyCloudEnabledSettings(mode))

            assertEquals("[$mode] route", AiRoute.ON_DEVICE, result.route)
            assertEquals("[$mode] provider", AppConfig.Ai.ON_DEVICE_PROVIDER_NAME, result.providerName)
            assertEquals("[$mode] model", AppConfig.Ai.ON_DEVICE_BRIEFING_MODEL, result.modelName)
        }

        coVerify(exactly = 0) { privacyGate.check(any(), any()) }
        verify(exactly = 0) { secureKeyStorage.hasKey(any()) }
    }

    @Test
    fun `INV-15 TRANSACTION_INSIGHT routes ON_DEVICE independent of dashboardBriefingEnabled`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns true
        every { environmentMonitor.isWifiConnected() } returns true
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.TRANSACTION_INSIGHT) } returns OnDeviceModelStatus.AVAILABLE

        listOf(AiMode.AUTO, AiMode.CLOUD, AiMode.ON_DEVICE).forEach { mode ->
            val result = router.decide(
                AiCapability.TRANSACTION_INSIGHT,
                fullyCloudEnabledSettings(mode).copy(dashboardBriefingEnabled = false)
            )

            assertEquals("[$mode] route", AiRoute.ON_DEVICE, result.route)
        }

        coVerify(exactly = 0) { privacyGate.check(any(), any()) }
        verify(exactly = 0) { secureKeyStorage.hasKey(any()) }
    }

    @Test
    fun `TRANSACTION_INSIGHT is never CLOUD when on-device is disallowed even with cloud fully enabled`() = runTest {
        every { environmentMonitor.isNetworkAvailable() } returns true
        every { environmentMonitor.isWifiConnected() } returns true
        coEvery { environmentMonitor.getOnDeviceModelStatus(AiCapability.TRANSACTION_INSIGHT) } returns OnDeviceModelStatus.AVAILABLE

        listOf(AiMode.AUTO, AiMode.CLOUD, AiMode.ON_DEVICE).forEach { mode ->
            val result = router.decide(
                AiCapability.TRANSACTION_INSIGHT,
                fullyCloudEnabledSettings(mode).copy(allowOnDeviceAi = false)
            )

            assertEquals("[$mode] route", AiRoute.DETERMINISTIC_FALLBACK, result.route)
        }
    }

    @Test
    fun `TRANSACTION_INSIGHT is DISABLED when AI is off`() = runTest {
        val result = router.decide(AiCapability.TRANSACTION_INSIGHT, AiSettings(aiEnabled = false))

        assertEquals(AiRoute.DISABLED, result.route)
    }
}
