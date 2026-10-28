package com.yourname.expensetracker.ui.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** NotificationPermissionPromptOnce (S20-b, R2, AE-39): once per process, never persisted. Plain JVM. */
class NotificationPermissionPromptOnceTest {

    @Test
    fun firstClaim_isTrue() {
        assertTrue(NotificationPermissionPromptOnce().tryClaim())
    }

    @Test
    fun laterClaims_areFalse() {
        val once = NotificationPermissionPromptOnce()
        assertTrue(once.tryClaim())
        repeat(5) { assertFalse(once.tryClaim()) }
    }

    @Test
    fun aNewInstance_isUnclaimed_theClaimIsNotPersisted() {
        val first = NotificationPermissionPromptOnce()
        assertTrue(first.tryClaim())
        assertTrue(NotificationPermissionPromptOnce().tryClaim())
    }

    @Test
    fun concurrentClaims_giveExactlyOneTrue() {
        val threads = 16
        repeat(20) { round ->
            val once = NotificationPermissionPromptOnce()
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val ready = CountDownLatch(threads)
                val go = CountDownLatch(1)
                val winners = AtomicInteger(0)
                val done = CountDownLatch(threads)
                repeat(threads) {
                    pool.execute {
                        ready.countDown()
                        go.await()
                        if (once.tryClaim()) winners.incrementAndGet()
                        done.countDown()
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                go.countDown()
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertEquals("round $round", 1, winners.get())
            } finally {
                pool.shutdownNow()
            }
        }
    }
}
