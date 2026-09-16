package com.yourname.expensetracker.domain.notification.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/** RP-10 10b (P1-003): the shared retry ladder — single source for worker retries and enqueue failures. */
class NotificationIntakeRetryPolicyTest {

    @Test
    fun `ladder matches the worker backoff semantics`() {
        assertEquals(30_000L, NotificationIntakeRetryPolicy.backoffFor(1))
        assertEquals(120_000L, NotificationIntakeRetryPolicy.backoffFor(2))
        assertEquals(600_000L, NotificationIntakeRetryPolicy.backoffFor(3))
        assertEquals(1_800_000L, NotificationIntakeRetryPolicy.backoffFor(4))
        assertEquals(3_600_000L, NotificationIntakeRetryPolicy.backoffFor(5))
    }

    @Test
    fun `out-of-range attempts clamp to the ladder bounds`() {
        assertEquals(30_000L, NotificationIntakeRetryPolicy.backoffFor(0))
        assertEquals(30_000L, NotificationIntakeRetryPolicy.backoffFor(-3))
        assertEquals(3_600_000L, NotificationIntakeRetryPolicy.backoffFor(9))
    }
}
