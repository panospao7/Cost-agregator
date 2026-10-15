package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/**
 * R6 regression: the CLOUD route of [HybridDedupeJudgeService] must never swallow
 * cancellation and must never leak raw exception text into results or logs.
 */
class HybridDedupeJudgeServiceTest {

    private val settings = mockk<AiSettings>()
    private val settingsRepository = mockk<AiSettingsRepository> {
        every { settings() } returns flowOf(this@HybridDedupeJudgeServiceTest.settings)
    }
    private val router = mockk<AiCapabilityRouter> {
        coEvery { decide(any(), settings) } returns AiRouteDecision(AiRoute.CLOUD, "test_route")
    }
    private val cloud = mockk<CloudDedupeJudgeService>()
    private val onDevice = mockk<OnDeviceDedupeJudgeService>()
    private val noOp = mockk<NoOpDedupeJudgeService>()
    private val input = mockk<DedupeJudgeInput>()

    private val loggedLines = mutableListOf<String>()
    private val loggedThrowables = mutableListOf<Throwable>()
    private val capturingTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            loggedLines += message
            if (t != null) loggedThrowables += t
        }
    }

    private lateinit var service: HybridDedupeJudgeService

    @Before
    fun setUp() {
        Timber.plant(capturingTree)
        service = HybridDedupeJudgeService(settingsRepository, router, cloud, onDevice, noOp)
    }

    @After
    fun tearDown() {
        Timber.uproot(capturingTree)
    }

    @Test
    fun `cloud route propagates CancellationException and never falls back to on-device`() = runTest {
        coEvery { cloud.judge(any()) } throws CancellationException("cancelled")
        coEvery { onDevice.judge(any()) } returns AiServiceResult.Failure(AiServiceError.Offline)

        try {
            service.judge(input)
            fail("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected
        }

        coVerify(exactly = 1) { cloud.judge(any()) }
        coVerify(exactly = 0) { onDevice.judge(any()) }
    }

    @Test
    fun `cloud route exception text never reaches the result or logs`() = runTest {
        val sentinel = "R6SENTINEL_9f2c"
        coEvery { cloud.judge(any()) } throws RuntimeException("boom $sentinel merchant text")
        coEvery { onDevice.judge(any()) } throws RuntimeException("device $sentinel")

        val result = service.judge(input)

        assertTrue(result is AiServiceResult.Failure)
        val error = (result as AiServiceResult.Failure).error
        assertEquals(AiServiceError.Unknown("UNKNOWN_ERROR"), error)
        assertFalse(result.toString().contains(sentinel))
        assertTrue("expected at least one log line", loggedLines.isNotEmpty())
        assertTrue(
            "no log line may contain the raw exception text",
            loggedLines.none { it.contains(sentinel) }
        )
        assertTrue("no throwable may be attached to logs", loggedThrowables.isEmpty())
    }
}
