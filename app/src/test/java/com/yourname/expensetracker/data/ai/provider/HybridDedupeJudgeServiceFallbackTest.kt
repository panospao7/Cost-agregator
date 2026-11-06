package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion
import com.yourname.expensetracker.domain.ai.model.DuplicateVerdict
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Privacy fail-closed fallback direction of [HybridDedupeJudgeService]:
 * an explicit ON_DEVICE preference never falls back to cloud; AUTO still does;
 * the cloud-first direction (cloud failure to on-device) is unchanged.
 */
class HybridDedupeJudgeServiceFallbackTest {

    private val cloud = mockk<CloudDedupeJudgeService>()
    private val onDevice = mockk<OnDeviceDedupeJudgeService>()
    private val noOp = mockk<NoOpDedupeJudgeService>()
    private val input = mockk<DedupeJudgeInput>()

    private val success = AiServiceResult.Success(DedupeJudgeSuggestion(DuplicateVerdict.LIKELY_DISTINCT))
    private val otherSuccess = AiServiceResult.Success(DedupeJudgeSuggestion(DuplicateVerdict.UNCERTAIN))
    private val onDeviceFailure = AiServiceResult.Failure(AiServiceError.Offline)
    private val cloudFailure = AiServiceResult.Failure(AiServiceError.Timeout)

    private fun service(route: AiRoute, mode: AiMode): HybridDedupeJudgeService {
        val settings = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true,
            receiptAssistEnabled = true,
            categorizationFallbackEnabled = true,
            queryInterpretationEnabled = true,
            preferredMode = mode
        )
        val repository = mockk<AiSettingsRepository> { every { settings() } returns flowOf(settings) }
        val router = mockk<AiCapabilityRouter> {
            coEvery { decide(AiCapability.DEDUPE_JUDGE, settings) } returns AiRouteDecision(route, "test_route")
        }
        return HybridDedupeJudgeService(repository, router, cloud, onDevice, noOp)
    }

    @Test
    fun `ON_DEVICE preferred with on-device failure never calls cloud and returns the on-device failure`() = runTest {
        coEvery { onDevice.judge(any()) } returns onDeviceFailure
        coEvery { cloud.judge(any()) } returns success

        val result = service(AiRoute.ON_DEVICE, AiMode.ON_DEVICE).judge(input)

        assertEquals(onDeviceFailure, result)
        coVerify(exactly = 1) { onDevice.judge(any()) }
        coVerify(exactly = 0) { cloud.judge(any()) }
    }

    @Test
    fun `ON_DEVICE preferred with on-device exception never calls cloud and fails closed`() = runTest {
        coEvery { onDevice.judge(any()) } throws RuntimeException("synthetic")
        coEvery { cloud.judge(any()) } returns success

        val result = service(AiRoute.ON_DEVICE, AiMode.ON_DEVICE).judge(input)

        assertEquals(AiServiceResult.Failure(AiServiceError.Unknown("UNKNOWN_ERROR")), result)
        coVerify(exactly = 0) { cloud.judge(any()) }
    }

    @Test
    fun `AUTO with on-device failure still falls back to cloud and returns the cloud success`() = runTest {
        coEvery { onDevice.judge(any()) } returns onDeviceFailure
        coEvery { cloud.judge(any()) } returns success

        val result = service(AiRoute.ON_DEVICE, AiMode.AUTO).judge(input)

        assertEquals(success, result)
        coVerify(exactly = 1) { onDevice.judge(any()) }
        coVerify(exactly = 1) { cloud.judge(any()) }
    }

    @Test
    fun `AUTO with both routes failing returns the primary on-device failure`() = runTest {
        coEvery { onDevice.judge(any()) } returns onDeviceFailure
        coEvery { cloud.judge(any()) } returns cloudFailure

        val result = service(AiRoute.ON_DEVICE, AiMode.AUTO).judge(input)

        assertEquals(onDeviceFailure, result)
        coVerify(exactly = 1) { cloud.judge(any()) }
    }

    @Test
    fun `on-device success makes no cloud call in ON_DEVICE preferred mode`() = runTest {
        coEvery { onDevice.judge(any()) } returns success

        val result = service(AiRoute.ON_DEVICE, AiMode.ON_DEVICE).judge(input)

        assertEquals(success, result)
        coVerify(exactly = 0) { cloud.judge(any()) }
    }

    @Test
    fun `on-device success makes no cloud call in AUTO mode`() = runTest {
        coEvery { onDevice.judge(any()) } returns success

        val result = service(AiRoute.ON_DEVICE, AiMode.AUTO).judge(input)

        assertEquals(success, result)
        coVerify(exactly = 0) { cloud.judge(any()) }
    }

    @Test
    fun `CLOUD route with cloud failure still falls back to on-device`() = runTest {
        coEvery { cloud.judge(any()) } returns cloudFailure
        coEvery { onDevice.judge(any()) } returns otherSuccess

        val result = service(AiRoute.CLOUD, AiMode.CLOUD).judge(input)

        assertEquals(otherSuccess, result)
        coVerify(exactly = 1) { cloud.judge(any()) }
        coVerify(exactly = 1) { onDevice.judge(any()) }
    }

    @Test
    fun `CLOUD route with cloud success makes no on-device call`() = runTest {
        coEvery { cloud.judge(any()) } returns success

        val result = service(AiRoute.CLOUD, AiMode.CLOUD).judge(input)

        assertEquals(success, result)
        coVerify(exactly = 0) { onDevice.judge(any()) }
    }
}
