package com.yourname.expensetracker.ui.screens.export

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * RP-19 (19-C): export start serialization — a new run cancels the previous
 * job and JOINS it (waits for full termination, cleanup included) before any
 * new work starts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportJobSerializerTest {

    @Test
    fun `cancel-and-join - second run starts only after first fully terminates`() = runTest {
        val serializer = ExportJobSerializer()
        val events = mutableListOf<String>()
        val firstSuspended = CompletableDeferred<Unit>()

        val first = async {
            serializer.launch(this@runTest) {
                events.add("first:start")
                firstSuspended.complete(Unit)
                try {
                    // Simulates the streaming loop being cancelled mid-page.
                    awaitCancellation()
                    events.add("first:normal-end")
                } finally {
                    // Cancellation-safe cleanup runs BEFORE the successor starts.
                    events.add("first:cleanup")
                }
            }
        }
        firstSuspended.await()

        serializer.launch(this@runTest) {
            events.add("second:start")
        }

        advanceUntilIdle()
        first.await()

        assertThat(events).containsAtLeast("first:start", "first:cleanup", "second:start")
        assertThat(events.indexOf("first:cleanup")).isLessThan(events.indexOf("second:start"))
    }

    @Test
    fun `sequential runs do not overlap and complete in order`() = runTest {
        val serializer = ExportJobSerializer()
        val events = mutableListOf<String>()
        val active = AtomicInteger(0)
        var overlapDetected = false

        suspend fun runExport(name: String) {
            serializer.launch(this@runTest) {
                if (active.incrementAndGet() > 1) overlapDetected = true
                events.add("$name:start")
                events.add("$name:end")
                active.decrementAndGet()
            }
        }

        runExport("a")
        // Let "a" actually run to completion before starting "b".
        advanceUntilIdle()
        runExport("b")
        advanceUntilIdle()

        assertThat(overlapDetected).isFalse()
        assertThat(events).containsExactly("a:start", "a:end", "b:start", "b:end").inOrder()
        assertThat(events.indexOf("a:end")).isLessThan(events.indexOf("b:start"))
    }

    @Test
    fun `cancelActive cancels the running job`() = runTest {
        val serializer = ExportJobSerializer()
        val started = CompletableDeferred<Unit>()
        var cancelledBlockRan = false

        val job = serializer.launch(this@runTest) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelledBlockRan = true
            }
        }
        started.await()

        serializer.cancelActive()
        advanceUntilIdle()

        assertThat(job.isCancelled).isTrue()
        assertThat(cancelledBlockRan).isTrue()
    }

    @Test
    fun `cancelActive with no active job is a no-op`() = runTest {
        val serializer = ExportJobSerializer()
        serializer.cancelActive()
        advanceUntilIdle()
        // Did not throw — that is the contract.
    }
}
