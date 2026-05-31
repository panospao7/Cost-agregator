package com.yourname.expensetracker.domain.workers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P9-PR1 (NEW-P9-003): Verifies WorkerRunContext counters are thread-safe
 * under concurrent access.
 */
class WorkerRunContextThreadSafetyTest {

    @Test
    fun `concurrent counter increments produce correct totals`() = runBlocking {
        val ctx = WorkerRunContext { /* no-op checkpoint */ }
        val iterations = 1000

        // Launch many concurrent coroutines incrementing counters
        val jobs = (1..iterations).map {
            async(Dispatchers.Default) {
                ctx.addRowsScanned()
                ctx.addRowsUpdated()
                ctx.addNotificationsSent()
            }
        }
        jobs.awaitAll()

        assertEquals(iterations, ctx.rowsScanned)
        assertEquals(iterations, ctx.rowsUpdated)
        assertEquals(iterations, ctx.notificationsSent)
    }

    @Test
    fun `addRowsScanned with custom count`() {
        val ctx = WorkerRunContext { }
        ctx.addRowsScanned(5)
        ctx.addRowsScanned(3)
        assertEquals(8, ctx.rowsScanned)
    }
}
