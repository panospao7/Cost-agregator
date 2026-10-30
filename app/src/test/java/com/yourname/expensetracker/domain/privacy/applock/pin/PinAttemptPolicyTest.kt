package com.yourname.expensetracker.domain.privacy.applock.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** INV-39 / OD-14 / AMD-2 / AMD-11 / S24 decision 3: pure attempt-limiting rules. */
class PinAttemptPolicyTest {

    private val min = 60_000L

    private fun state(
        failures: Int,
        delay: Long,
        started: Long = 1_000L,
        boot: Int = 7,
        origin: PinCounterOrigin = PinCounterOrigin.FAILURES
    ) = PinAttemptState(failures, delay, started, boot, origin)

    @Test
    fun `INV-39 failures 1 through 4 carry no delay`() {
        for (f in 0..4) {
            assertEquals("failures $f", 0L, PinAttemptPolicy.delayMillisForFailures(f))
            assertEquals(PinDelayTier.NONE, PinAttemptPolicy.tierForFailures(f))
        }
    }

    @Test
    fun `INV-39 schedule is 30s 1m 5m 15m then 60m cap from failure 5`() {
        assertEquals(30_000L, PinAttemptPolicy.delayMillisForFailures(5))
        assertEquals(1 * min, PinAttemptPolicy.delayMillisForFailures(6))
        assertEquals(5 * min, PinAttemptPolicy.delayMillisForFailures(7))
        assertEquals(15 * min, PinAttemptPolicy.delayMillisForFailures(8))
        assertEquals(60 * min, PinAttemptPolicy.delayMillisForFailures(9))
        assertEquals(60 * min, PinAttemptPolicy.delayMillisForFailures(10))
        assertEquals(60 * min, PinAttemptPolicy.delayMillisForFailures(1000))
    }

    @Test
    fun `INV-39 tiers map to schedule names`() {
        assertEquals(PinDelayTier.S30, PinAttemptPolicy.tierForFailures(5))
        assertEquals(PinDelayTier.M1, PinAttemptPolicy.tierForFailures(6))
        assertEquals(PinDelayTier.M5, PinAttemptPolicy.tierForFailures(7))
        assertEquals(PinDelayTier.M15, PinAttemptPolicy.tierForFailures(8))
        assertEquals(PinDelayTier.M60, PinAttemptPolicy.tierForFailures(9))
        assertEquals(PinDelayTier.M60, PinAttemptPolicy.tierForFailures(500))
    }

    @Test
    fun `INV-39 afterFailure increments and starts the tier at now`() {
        val s4 = PinAttemptPolicy.afterFailure(PinAttemptPolicy.CLEAN, 100L, 3)
        assertEquals(1, s4.consecutiveFailures)
        assertEquals(0L, s4.currentDelayMillis)
        var s = s4
        repeat(3) { s = PinAttemptPolicy.afterFailure(s, 200L, 3) }
        assertEquals(4, s.consecutiveFailures)
        assertEquals(0L, s.currentDelayMillis)
        val s5 = PinAttemptPolicy.afterFailure(s, 5_000L, 3)
        assertEquals(5, s5.consecutiveFailures)
        assertEquals(30_000L, s5.currentDelayMillis)
        assertEquals(5_000L, s5.delayStartedElapsed)
        assertEquals(3, s5.bootCount)
        assertEquals(PinCounterOrigin.FAILURES, s5.counterOrigin)
        assertEquals(PinDelayTier.S30, PinAttemptPolicy.tierOf(s5))
    }

    @Test
    fun `INV-39 afterFailure stores null boot count as no boot count`() {
        val s = PinAttemptPolicy.afterFailure(PinAttemptPolicy.CLEAN, 10L, null)
        assertEquals(PinAttemptPolicy.NO_BOOT_COUNT, s.bootCount)
    }

    @Test
    fun `INV-39 counter saturates at 1000 and delay stays at cap`() {
        val atMax = state(PinAttemptPolicy.MAX_FAILURES, 60 * min)
        val next = PinAttemptPolicy.afterFailure(atMax, 5L, 7)
        assertEquals(1000, next.consecutiveFailures)
        assertEquals(60 * min, next.currentDelayMillis)
        val below = PinAttemptPolicy.afterFailure(state(999, 60 * min), 5L, 7)
        assertEquals(1000, below.consecutiveFailures)
    }

    @Test
    fun `INV-39 afterFailure clears the repaired origin`() {
        val repaired = PinAttemptPolicy.repairedState(10L, 7)
        val next = PinAttemptPolicy.afterFailure(repaired, 20L, 7)
        assertEquals(PinCounterOrigin.FAILURES, next.counterOrigin)
        assertEquals(5, next.consecutiveFailures)
    }

    @Test
    fun `AMD-11 repairedState is 4 failures with a 30s delay and unreadable origin`() {
        val r = PinAttemptPolicy.repairedState(4_000L, 9)
        assertEquals(4, r.consecutiveFailures)
        assertEquals(30_000L, r.currentDelayMillis)
        assertEquals(4_000L, r.delayStartedElapsed)
        assertEquals(9, r.bootCount)
        assertEquals(PinCounterOrigin.REPAIRED_UNREADABLE, r.counterOrigin)
        assertEquals(PinAttemptPolicy.NO_BOOT_COUNT, PinAttemptPolicy.repairedState(1L, null).bootCount)
        assertTrue(PinAttemptPolicy.isPlausible(r))
    }

    @Test
    fun `AMD-11 repaired state evaluates with the unreadable reason`() {
        val r = PinAttemptPolicy.repairedState(1_000L, 7)
        val e = PinAttemptPolicy.evaluate(r, 11_000L, 7, firstEvaluationInProcess = false)
        assertEquals(PinDelayEvaluation.Active(20_000L, PinDelayReason.ATTEMPT_STATE_UNREADABLE), e)
    }

    @Test
    fun `INV-39 no delay evaluates as idle`() {
        assertEquals(
            PinDelayEvaluation.Idle,
            PinAttemptPolicy.evaluate(state(3, 0L), 5_000L, 7, firstEvaluationInProcess = true)
        )
        assertEquals(
            PinDelayEvaluation.Idle,
            PinAttemptPolicy.evaluate(PinAttemptPolicy.CLEAN, 5_000L, null, firstEvaluationInProcess = true)
        )
    }

    @Test
    fun `INV-39 active delay reports remaining time from elapsed`() {
        val s = state(6, 1 * min, started = 10_000L, boot = 7)
        val e = PinAttemptPolicy.evaluate(s, 25_000L, 7, firstEvaluationInProcess = false)
        assertEquals(PinDelayEvaluation.Active(45_000L, PinDelayReason.FAILURES), e)
    }

    @Test
    fun `INV-39 delay boundary exactly elapsed is idle and one millisecond before is active`() {
        val s = state(5, 30_000L, started = 10_000L, boot = 7)
        assertEquals(
            PinDelayEvaluation.Idle,
            PinAttemptPolicy.evaluate(s, 40_000L, 7, firstEvaluationInProcess = false)
        )
        assertEquals(
            PinDelayEvaluation.Active(1L, PinDelayReason.FAILURES),
            PinAttemptPolicy.evaluate(s, 39_999L, 7, firstEvaluationInProcess = false)
        )
        assertEquals(
            PinDelayEvaluation.Idle,
            PinAttemptPolicy.evaluate(s, 90_000L, 7, firstEvaluationInProcess = false)
        )
    }

    @Test
    fun `INV-39 boot count change restarts the full tier`() {
        val s = state(7, 5 * min, started = 100_000L, boot = 7)
        val e = PinAttemptPolicy.evaluate(s, 500_000L, 8, firstEvaluationInProcess = false)
        assertTrue(e is PinDelayEvaluation.Restarted)
        e as PinDelayEvaluation.Restarted
        assertEquals(5 * min, e.remainingMillis)
        assertEquals(PinDelayReason.FAILURES, e.reason)
        assertEquals(500_000L, e.state.delayStartedElapsed)
        assertEquals(8, e.state.bootCount)
        assertEquals(5 * min, e.state.currentDelayMillis)
        assertEquals(7, e.state.consecutiveFailures)
    }

    @Test
    fun `INV-39 elapsed below stored start restarts even with the same boot count`() {
        val s = state(8, 15 * min, started = 900_000L, boot = 7)
        val e = PinAttemptPolicy.evaluate(s, 1_000L, 7, firstEvaluationInProcess = false)
        assertTrue(e is PinDelayEvaluation.Restarted)
        e as PinDelayEvaluation.Restarted
        assertEquals(15 * min, e.remainingMillis)
        assertEquals(1_000L, e.state.delayStartedElapsed)
    }

    @Test
    fun `INV-39 same boot count and monotonic elapsed is not a reboot`() {
        val s = state(8, 15 * min, started = 1_000L, boot = 7)
        val e = PinAttemptPolicy.evaluate(s, 1_000L, 7, firstEvaluationInProcess = true)
        assertEquals(PinDelayEvaluation.Active(15 * min, PinDelayReason.FAILURES), e)
    }

    @Test
    fun `INV-39 null boot count on first evaluation re-anchors bounded at 60 minutes`() {
        val s = state(9, 60 * min, started = 1_000L, boot = 7)
        val e = PinAttemptPolicy.evaluate(s, 2_000L, null, firstEvaluationInProcess = true)
        assertTrue(e is PinDelayEvaluation.Restarted)
        e as PinDelayEvaluation.Restarted
        assertEquals(60 * min, e.remainingMillis)
        assertEquals(2_000L, e.state.delayStartedElapsed)
        assertEquals(PinAttemptPolicy.NO_BOOT_COUNT, e.state.bootCount)
    }

    @Test
    fun `INV-39 stored no boot count on first evaluation re-anchors`() {
        val s = state(6, 1 * min, started = 1_000L, boot = PinAttemptPolicy.NO_BOOT_COUNT)
        val e = PinAttemptPolicy.evaluate(s, 2_000L, 7, firstEvaluationInProcess = true)
        assertTrue(e is PinDelayEvaluation.Restarted)
        e as PinDelayEvaluation.Restarted
        assertEquals(1 * min, e.remainingMillis)
        assertEquals(7, e.state.bootCount)
    }

    @Test
    fun `INV-39 unknown boot count later evaluations use elapsed time only`() {
        val s = state(6, 1 * min, started = 1_000L, boot = PinAttemptPolicy.NO_BOOT_COUNT)
        val e = PinAttemptPolicy.evaluate(s, 21_000L, null, firstEvaluationInProcess = false)
        assertEquals(PinDelayEvaluation.Active(40_000L, PinDelayReason.FAILURES), e)
    }

    @Test
    fun `INV-39 restart is bounded even when the stored delay exceeds the cap`() {
        val s = state(9, 10 * 60 * min, started = 1_000L, boot = 7)
        val e = PinAttemptPolicy.evaluate(s, 2_000L, null, firstEvaluationInProcess = true)
        assertTrue(e is PinDelayEvaluation.Restarted)
        e as PinDelayEvaluation.Restarted
        assertEquals(PinAttemptPolicy.MAX_DELAY_MILLIS, e.remainingMillis)
        val active = PinAttemptPolicy.evaluate(s, 2_000L, 7, firstEvaluationInProcess = false)
        assertEquals(PinDelayEvaluation.Active(PinAttemptPolicy.MAX_DELAY_MILLIS - 1_000L, PinDelayReason.FAILURES), active)
    }

    @Test
    fun `INV-39 isPlausible accepts clean and in range states`() {
        assertTrue(PinAttemptPolicy.isPlausible(PinAttemptPolicy.CLEAN))
        assertTrue(PinAttemptPolicy.isPlausible(state(1000, 60 * min, started = 0L, boot = 0)))
    }

    @Test
    fun `INV-39 isPlausible rejects negative or out of range values`() {
        assertFalse(PinAttemptPolicy.isPlausible(state(-1, 0L)))
        assertFalse(PinAttemptPolicy.isPlausible(state(1001, 60 * min)))
        assertFalse(PinAttemptPolicy.isPlausible(state(5, -1L)))
        assertFalse(PinAttemptPolicy.isPlausible(state(5, 60 * min + 1L)))
        assertFalse(PinAttemptPolicy.isPlausible(state(5, 30_000L, started = -1L)))
        assertFalse(PinAttemptPolicy.isPlausible(state(5, 30_000L, boot = -2)))
    }

    @Test
    fun `INV-38 attempt state toString never prints counters`() {
        val text = state(7, 5 * min, started = 123_456L, boot = 42).toString()
        assertFalse(text.contains("123456"))
        assertFalse(text.contains("300000"))
        assertFalse(text.contains("42"))
    }
}
