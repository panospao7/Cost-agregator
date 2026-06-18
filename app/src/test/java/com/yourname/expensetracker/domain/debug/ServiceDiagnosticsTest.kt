package com.yourname.expensetracker.domain.debug

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ServiceDiagnosticsTest {

    private lateinit var diagnostics: ServiceDiagnostics

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Clear any stale state from previous test runs
        context.getSharedPreferences("service_diagnostics", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        diagnostics = ServiceDiagnostics(context)
    }

    @Test
    fun `initial stats are all zero`() {
        val stats = diagnostics.getStats()
        assertEquals(0, stats.startCount)
        assertEquals(0, stats.killedCount)
        assertEquals(0, stats.disconnectCount)
        assertEquals(0L, stats.lastRestartTime)
        assertEquals(0L, stats.lastKillTime)
    }

    @Test
    fun `recordServiceStart increments start count`() {
        diagnostics.recordServiceStart()
        diagnostics.recordServiceStart()
        diagnostics.recordServiceStart()

        assertEquals(3, diagnostics.getServiceStartCount())
    }

    @Test
    fun `recordServiceKilled increments killed count`() {
        diagnostics.recordServiceKilled()
        diagnostics.recordServiceKilled()

        assertEquals(2, diagnostics.getServiceKilledCount())
    }

    @Test
    fun `recordListenerDisconnected increments disconnect count`() {
        diagnostics.recordListenerDisconnected()

        assertEquals(1, diagnostics.getListenerDisconnectCount())
    }

    @Test
    fun `recordServiceStart updates last restart time`() {
        val before = System.currentTimeMillis()
        diagnostics.recordServiceStart()
        val after = System.currentTimeMillis()

        val restartTime = diagnostics.getLastRestartTime()
        assertTrue(
            "Expected restart time $restartTime to be between $before and $after",
            restartTime in before..after
        )
    }

    @Test
    fun `recordServiceKilled updates last kill time`() {
        val before = System.currentTimeMillis()
        diagnostics.recordServiceKilled()
        val after = System.currentTimeMillis()

        val killTime = diagnostics.getLastKillTime()
        assertTrue(
            "Expected kill time $killTime to be between $before and $after",
            killTime in before..after
        )
    }

    @Test
    fun `resetStats clears all counters`() {
        diagnostics.recordServiceStart()
        diagnostics.recordServiceKilled()
        diagnostics.recordListenerDisconnected()

        diagnostics.resetStats()

        val stats = diagnostics.getStats()
        assertEquals(0, stats.startCount)
        assertEquals(0, stats.killedCount)
        assertEquals(0, stats.disconnectCount)
        assertEquals(0L, stats.lastRestartTime)
        assertEquals(0L, stats.lastKillTime)
    }

    @Test
    fun `getStats returns consistent snapshot of all counters`() {
        diagnostics.recordServiceStart()
        diagnostics.recordServiceStart()
        diagnostics.recordServiceKilled()
        diagnostics.recordListenerDisconnected()
        diagnostics.recordListenerDisconnected()
        diagnostics.recordListenerDisconnected()

        val stats = diagnostics.getStats()
        assertEquals(2, stats.startCount)
        assertEquals(1, stats.killedCount)
        assertEquals(3, stats.disconnectCount)
        assertTrue("lastRestartTime should be > 0", stats.lastRestartTime > 0L)
        assertTrue("lastKillTime should be > 0", stats.lastKillTime > 0L)
    }

    /**
     * Concurrent snapshot consistency regression test.
     *
     * Races [ServiceDiagnostics.getStats] against [recordServiceStart],
     * [recordServiceKilled], and [resetStats] running in parallel threads.
     *
     * Invariant checked on every snapshot: if startCount > 0 then lastRestartTime
     * must also be > 0, and if killedCount > 0 then lastKillTime must also be > 0.
     * A mixed snapshot (non-zero count with zero matching timestamp) would mean
     * getStats read across a partially-committed write — i.e. the lock does not
     * protect the snapshot atomically.
     */
    @Test
    fun `getStats never returns impossible mixed snapshot under contention`() {
        val writerCount = 4
        val iterationsPerWriter = 60
        val snapshotCount = 200
        val inconsistencyFound = AtomicBoolean(false)
        val inconsistencyDetail = StringBuilder()

        val executor = Executors.newFixedThreadPool(writerCount + 2)
        val startLatch = CountDownLatch(1)
        val writersDone = CountDownLatch(writerCount + 1) // writers + resetter

        // Writer threads: continuously record starts and kills
        repeat(writerCount / 2) {
            executor.submit {
                startLatch.await()
                repeat(iterationsPerWriter) {
                    diagnostics.recordServiceStart()
                }
                writersDone.countDown()
            }
        }
        repeat(writerCount / 2) {
            executor.submit {
                startLatch.await()
                repeat(iterationsPerWriter) {
                    diagnostics.recordServiceKilled()
                }
                writersDone.countDown()
            }
        }

        // Resetter thread: periodically resets stats to force the snapshot
        // reader to observe zero-counts alongside potential stale timestamps
        executor.submit {
            startLatch.await()
            repeat(iterationsPerWriter / 3) {
                diagnostics.resetStats()
                Thread.sleep(1)
            }
            writersDone.countDown()
        }

        // Snapshot reader thread: runs concurrently and checks invariants
        val readerDone = CountDownLatch(1)
        executor.submit {
            startLatch.await()
            repeat(snapshotCount) {
                val stats = diagnostics.getStats()
                // Invariant: startCount > 0 implies lastRestartTime > 0
                if (stats.startCount > 0 && stats.lastRestartTime == 0L) {
                    if (inconsistencyFound.compareAndSet(false, true)) {
                        inconsistencyDetail.append(
                            "Impossible snapshot: startCount=${stats.startCount} but lastRestartTime=0"
                        )
                    }
                }
                // Invariant: killedCount > 0 implies lastKillTime > 0
                if (stats.killedCount > 0 && stats.lastKillTime == 0L) {
                    if (inconsistencyFound.compareAndSet(false, true)) {
                        inconsistencyDetail.append(
                            "Impossible snapshot: killedCount=${stats.killedCount} but lastKillTime=0"
                        )
                    }
                }
            }
            readerDone.countDown()
        }

        startLatch.countDown()
        writersDone.await(30, TimeUnit.SECONDS)
        readerDone.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(
            "getStats returned an inconsistent snapshot under contention: $inconsistencyDetail",
            !inconsistencyFound.get()
        )
    }

    @Test
    fun `concurrent counter increments do not lose updates`() {
        val threadCount = 8
        val incrementsPerThread = 50
        val expectedTotal = threadCount * incrementsPerThread

        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        repeat(threadCount) {
            executor.submit {
                startLatch.await()
                repeat(incrementsPerThread) {
                    diagnostics.recordServiceStart()
                }
                doneLatch.countDown()
            }
        }

        // Release all threads at once for maximum contention
        startLatch.countDown()
        doneLatch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(
            "Expected $expectedTotal service starts but got ${diagnostics.getServiceStartCount()}",
            expectedTotal,
            diagnostics.getServiceStartCount()
        )
    }

    @Test
    fun `concurrent mixed operations do not lose updates`() {
        val threadCount = 4
        val opsPerThread = 30
        val expectedStarts = threadCount * opsPerThread
        val expectedKills = threadCount * opsPerThread
        val expectedDisconnects = threadCount * opsPerThread

        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        repeat(threadCount) {
            executor.submit {
                startLatch.await()
                repeat(opsPerThread) {
                    diagnostics.recordServiceStart()
                    diagnostics.recordServiceKilled()
                    diagnostics.recordListenerDisconnected()
                }
                doneLatch.countDown()
            }
        }

        startLatch.countDown()
        doneLatch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        val stats = diagnostics.getStats()
        assertEquals("start count", expectedStarts, stats.startCount)
        assertEquals("killed count", expectedKills, stats.killedCount)
        assertEquals("disconnect count", expectedDisconnects, stats.disconnectCount)
    }
}
