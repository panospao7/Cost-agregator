package com.yourname.expensetracker.domain.ai

import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.test.assertFailsWith

class HybridRouterTest {
    @Test
    fun readsCurrentSettingsForEveryRequestInsteadOfCachingRoute() = runTest {
        val first = mockk<AiSettings>()
        val second = mockk<AiSettings>()
        val state = MutableStateFlow(first)
        val repository = mockk<AiSettingsRepository>()
        every { repository.settings() } returns state
        val decisionRouter = mockk<AiCapabilityRouter>()
        coEvery { decisionRouter.decide(AiCapability.QUERY_INTERPRETATION, first) } returns AiRouteDecision(AiRoute.CLOUD, "test_cloud")
        coEvery { decisionRouter.decide(AiCapability.QUERY_INTERPRETATION, second) } returns AiRouteDecision(AiRoute.DISABLED, "test_disabled")
        val calls = mutableListOf<String>()
        val router = HybridRouter<String, String>(repository, decisionRouter, AiCapability.QUERY_INTERPRETATION,
            cloudFn = { calls += "cloud"; it },
            onDeviceFn = { calls += "device"; it },
            fallbackFn = { calls += "fallback"; it })

        assertEquals("first", router.execute("first"))
        state.value = second
        assertEquals("second", router.execute("second"))
        assertEquals(listOf("cloud", "fallback"), calls)
        verify(exactly = 2) { repository.settings() }
        coVerify(exactly = 1) { decisionRouter.decide(AiCapability.QUERY_INTERPRETATION, second) }
    }

    @Test
    fun cancellationPropagatesFromEverySelectedRouteWithoutFailover() = runTest {
        for (route in AiRoute.values()) {
            val settings = mockk<AiSettings>()
            val repository = mockk<AiSettingsRepository>()
            every { repository.settings() } returns flowOf(settings)
            val decisionRouter = mockk<AiCapabilityRouter>()
            coEvery { decisionRouter.decide(AiCapability.QUERY_INTERPRETATION, settings) } returns AiRouteDecision(route, "test_route")
            val cancellation = CancellationException("test cancellation")
            val calls = mutableListOf<String>()
            val router = HybridRouter<String, String>(repository, decisionRouter, AiCapability.QUERY_INTERPRETATION,
                cloudFn = { calls += "cloud"; throw cancellation },
                onDeviceFn = { calls += "device"; throw cancellation },
                fallbackFn = { calls += "fallback"; throw cancellation })

            assertSame(cancellation, assertFailsWith<CancellationException> { router.execute("input") })
            assertEquals(listOf(when (route) {
                AiRoute.CLOUD -> "cloud"
                AiRoute.ON_DEVICE -> "device"
                else -> "fallback"
            }), calls)
        }
    }

    @Test
    fun settingsFailureDoesNotDispatchOrInventFallback() = runTest {
        val failure = IllegalStateException("test settings failure")
        val repository = mockk<AiSettingsRepository>()
        every { repository.settings() } returns flow { throw failure }
        val decisionRouter = mockk<AiCapabilityRouter>()
        val router = HybridRouter<String, String>(repository, decisionRouter, AiCapability.QUERY_INTERPRETATION,
            cloudFn = { error("unexpected cloud") }, onDeviceFn = { error("unexpected device") },
            fallbackFn = { error("unexpected fallback") })
        assertSame(failure, assertFailsWith<IllegalStateException> { router.execute("input") })
        coVerify(exactly = 0) { decisionRouter.decide(any(), any(), any()) }
    }

    @Test
    fun decisionFailureDoesNotDispatchOrInventFallback() = runTest {
        val settings = mockk<AiSettings>()
        val repository = mockk<AiSettingsRepository>()
        every { repository.settings() } returns flowOf(settings)
        val failure = IllegalStateException("test decision failure")
        val decisionRouter = mockk<AiCapabilityRouter>()
        coEvery { decisionRouter.decide(any(), any(), any()) } throws failure
        val router = HybridRouter<String, String>(repository, decisionRouter, AiCapability.QUERY_INTERPRETATION,
            cloudFn = { error("unexpected cloud") }, onDeviceFn = { error("unexpected device") },
            fallbackFn = { error("unexpected fallback") })
        assertSame(failure, assertFailsWith<IllegalStateException> { router.execute("input") })
    }
}

