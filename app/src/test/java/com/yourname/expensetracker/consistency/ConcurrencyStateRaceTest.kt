package com.yourname.expensetracker.consistency

import app.cash.turbine.test
import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrencyStateRaceTest : AnalyticsEngineTestBase() {

    @Test
    fun `rapid re-scan does not cause stale state writes`() = runTest {
        val stateFlow = MutableStateFlow(0.0)
        var latestRequestId = 0

        fun launchScan(requestId: Int, result: Double, delayMs: Long) = launch {
            delay(delayMs)
            if (requestId == latestRequestId) {
                stateFlow.value = result
            }
        }

        val firstRequest = ++latestRequestId
        launchScan(requestId = firstRequest, result = 15.0, delayMs = 200)

        val secondRequest = ++latestRequestId
        launchScan(requestId = secondRequest, result = 80.0, delayMs = 50)

        stateFlow.test {
            assertApproxEquals(0.0, awaitItem(), 0.0)

            advanceUntilIdle()

            val finalValue = awaitItem()
            assertApproxEquals(80.0, finalValue, 0.0001)
            assertApproxEquals(80.0, stateFlow.value, 0.0001)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `concurrent operations do not corrupt state`() = runTest {
        val stateFlow = MutableStateFlow(0.0)
        val workers = 8
        val incrementsPerWorker = 250
        val expected = (workers * incrementsPerWorker).toDouble()

        launch {
            delay(1)
            repeat(workers) {
                launch {
                    repeat(incrementsPerWorker) {
                        stateFlow.update { current -> current + 1.0 }
                    }
                }
            }
        }

        stateFlow.test {
            assertApproxEquals(0.0, awaitItem(), 0.0)

            advanceUntilIdle()

            var observed = awaitItem()
            var guard = 0
            while (observed < expected && guard < 10_000) {
                observed = awaitItem()
                guard++
            }

            assertApproxEquals(expected, observed, 0.0001)
            assertApproxEquals(expected, stateFlow.value, 0.0001)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancellation of in-flight operations cleans up`() = runTest {
        val stateFlow = MutableStateFlow(OperationState(progress = 0.0, phase = "idle"))

        val job = launch {
            delay(1)
            try {
                stateFlow.value = OperationState(progress = 25.0, phase = "running")
                delay(5_000)
                stateFlow.value = OperationState(progress = 100.0, phase = "completed")
            } finally {
                if (!isActive) {
                    stateFlow.value = OperationState(progress = 0.0, phase = "idle")
                }
            }
        }

        launch {
            delay(100)
            job.cancel()
        }

        stateFlow.test {
            val initial = awaitItem()
            assertApproxEquals(0.0, initial.progress, 0.0)
            assertEquals("idle", initial.phase)

            advanceUntilIdle()

            val running = awaitItem()
            assertApproxEquals(25.0, running.progress, 0.0001)
            assertEquals("running", running.phase)

            val cleaned = awaitItem()
            assertApproxEquals(0.0, cleaned.progress, 0.0001)
            assertEquals("idle", cleaned.phase)

            assertApproxEquals(0.0, stateFlow.value.progress, 0.0001)
            assertEquals("idle", stateFlow.value.phase)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `StateFlow emissions are ordered correctly`() = runTest {
        val stateFlow = MutableStateFlow(0.0)

        launch {
            delay(1)
            launch {
                delay(10)
                stateFlow.value = 1.0
            }
            launch {
                delay(20)
                stateFlow.value = 2.0
            }
            launch {
                delay(30)
                stateFlow.value = 3.0
            }
            launch {
                delay(40)
                stateFlow.value = 4.0
            }
        }

        stateFlow.test {
            assertApproxEquals(0.0, awaitItem(), 0.0)

            advanceUntilIdle()

            assertApproxEquals(1.0, awaitItem(), 0.0001)
            assertApproxEquals(2.0, awaitItem(), 0.0001)
            assertApproxEquals(3.0, awaitItem(), 0.0001)
            assertApproxEquals(4.0, awaitItem(), 0.0001)
            assertApproxEquals(4.0, stateFlow.value, 0.0001)

            cancelAndIgnoreRemainingEvents()
        }
    }

    private data class OperationState(
        val progress: Double,
        val phase: String
    )
}
