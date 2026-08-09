package com.yourname.expensetracker.data.ai.provider

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [DefaultAiEnvironmentMonitor] cache/TTL behavior with a fake clock:
 * - Cached status is reused within the 1500ms TTL.
 * - Status is refreshed at exactly the 1500ms boundary and beyond.
 * - ML Kit [FeatureStatus] constants are mapped to domain statuses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultAiEnvironmentMonitorTest {

    private val context: Context = mockk(relaxed = true)
    private val model: GenerativeModel = mockk(relaxed = true)
    private val timeProvider = FakeTimeProvider(1_710_000_000_000L)
    private lateinit var monitor: DefaultAiEnvironmentMonitor

    @Before
    fun setUp() {
        mockkObject(Generation::class)
        every { Generation.getClient() } returns model
        monitor = DefaultAiEnvironmentMonitor(context, timeProvider)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `within 1500ms returns cached status and checkStatus exactly once`() = runTest {
        coEvery { model.checkStatus() } returns FeatureStatus.AVAILABLE

        val first = monitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING)
        assertEquals(OnDeviceModelStatus.AVAILABLE, first)

        timeProvider.advanceTime(1_000L)

        val second = monitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING)
        assertEquals(OnDeviceModelStatus.AVAILABLE, second)

        coVerify(exactly = 1) { model.checkStatus() }
    }

    @Test
    fun `exactly 1500ms refreshes and calls checkStatus twice`() = runTest {
        coEvery { model.checkStatus() } returns FeatureStatus.AVAILABLE

        val first = monitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING)
        assertEquals(OnDeviceModelStatus.AVAILABLE, first)

        timeProvider.advanceTime(1_500L)

        val second = monitor.getOnDeviceModelStatus(AiCapability.DASHBOARD_BRIEFING)
        assertEquals(OnDeviceModelStatus.AVAILABLE, second)

        coVerify(exactly = 2) { model.checkStatus() }
    }

    @Test
    fun `past 1500ms refreshes and maps DOWNLOADABLE to expected status`() = runTest {
        coEvery { model.checkStatus() } returns FeatureStatus.DOWNLOADABLE

        val first = monitor.getOnDeviceModelStatus(AiCapability.RECEIPT_EXTRACTION)
        assertEquals(OnDeviceModelStatus.NOT_INSTALLED, first)

        timeProvider.advanceTime(2_000L)

        val second = monitor.getOnDeviceModelStatus(AiCapability.RECEIPT_EXTRACTION)
        assertEquals(OnDeviceModelStatus.NOT_INSTALLED, second)

        coVerify(exactly = 2) { model.checkStatus() }
    }

    @Test
    fun `provider advancement across boundary refreshes`() = runTest {
        coEvery { model.checkStatus() } returns FeatureStatus.AVAILABLE

        val first = monitor.getOnDeviceModelStatus(AiCapability.QUERY_INTERPRETATION)
        assertEquals(OnDeviceModelStatus.AVAILABLE, first)

        // Still within TTL: cached result returned, no refresh yet.
        timeProvider.advanceTime(1_499L)
        val cached = monitor.getOnDeviceModelStatus(AiCapability.QUERY_INTERPRETATION)
        assertEquals(OnDeviceModelStatus.AVAILABLE, cached)

        // Cross the exact 1500ms boundary: refresh happens.
        timeProvider.advanceTime(1L)
        val refreshed = monitor.getOnDeviceModelStatus(AiCapability.QUERY_INTERPRETATION)
        assertEquals(OnDeviceModelStatus.AVAILABLE, refreshed)

        coVerify(exactly = 2) { model.checkStatus() }
    }
}
