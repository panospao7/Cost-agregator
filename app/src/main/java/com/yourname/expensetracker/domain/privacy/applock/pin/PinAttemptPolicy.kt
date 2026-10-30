package com.yourname.expensetracker.domain.privacy.applock.pin

/** Why the current delay exists (domain-spec 6.2, AMD-11). */
enum class PinDelayReason {
    /** Real failed attempts. */
    FAILURES,

    /** The attempt state was unreadable and was repaired; no remaining-tries value may be shown. */
    ATTEMPT_STATE_UNREADABLE
}

/**
 * The bounded delay tier that follows a wrong PIN. Names only: no counts are exposed. [NONE] covers failures 1..4.
 */
enum class PinDelayTier {
    NONE,
    S30,
    M1,
    M5,
    M15,
    M60
}

/** Where the persisted counter came from (domain-spec 2.7). */
enum class PinCounterOrigin {
    FAILURES,
    REPAIRED_UNREADABLE
}

/**
 * The persisted attempt state (domain-spec 2.7). [bootCount] is [PinAttemptPolicy.NO_BOOT_COUNT] when it was unknown
 * at the time the delay started. [toString] never prints the counters (INV-38 / no raw counts in logs).
 */
data class PinAttemptState(
    val consecutiveFailures: Int,
    val currentDelayMillis: Long,
    val delayStartedElapsed: Long,
    val bootCount: Int,
    val counterOrigin: PinCounterOrigin
) {
    override fun toString(): String = "PinAttemptState(origin=$counterOrigin)"
}

/** Outcome of evaluating the persisted delay against the clock (INV-39). */
sealed interface PinDelayEvaluation {
    /** No delay is active: the entry may be evaluated. The state is unchanged. */
    data object Idle : PinDelayEvaluation

    /** A delay is active: reject without evaluating. The state is unchanged. */
    data class Active(val remainingMillis: Long, val reason: PinDelayReason) : PinDelayEvaluation {
        override fun toString(): String = "Active(reason=$reason)"
    }

    /**
     * A reboot was detected, or the boot count is unknown on the first evaluation in this process: the current tier
     * restarts in full. The caller must persist [state] durably before reporting the delay. Bounded at 60 min.
     */
    data class Restarted(
        val state: PinAttemptState,
        val remainingMillis: Long,
        val reason: PinDelayReason
    ) : PinDelayEvaluation {
        override fun toString(): String = "Restarted(reason=$reason)"
    }
}

/**
 * Pure attempt-limiting rules (domain-spec 6.2, INV-39, AMD-2, AMD-11; S24 design decision 3). No clock, no I/O.
 *
 * Schedule: failures 1..4 no delay; 5 -> 30 s; 6 -> 1 min; 7 -> 5 min; 8 -> 15 min; 9 and later -> 60 min (cap).
 * There is never a data wipe. The counter saturates at [MAX_FAILURES].
 */
object PinAttemptPolicy {
    const val MAX_FAILURES: Int = 1000
    const val NO_BOOT_COUNT: Int = -1
    const val REPAIRED_FAILURES: Int = 4
    const val REPAIRED_DELAY_MILLIS: Long = 30_000L
    const val MAX_DELAY_MILLIS: Long = 60L * 60_000L

    /** The state after a correct PIN or a first run: no failures, no delay. */
    val CLEAN: PinAttemptState = PinAttemptState(
        consecutiveFailures = 0,
        currentDelayMillis = 0L,
        delayStartedElapsed = 0L,
        bootCount = NO_BOOT_COUNT,
        counterOrigin = PinCounterOrigin.FAILURES
    )

    /** The delay that a counter of [failures] failures carries. */
    fun delayMillisForFailures(failures: Int): Long = when {
        failures < 5 -> 0L
        failures == 5 -> 30_000L
        failures == 6 -> 60_000L
        failures == 7 -> 5L * 60_000L
        failures == 8 -> 15L * 60_000L
        else -> MAX_DELAY_MILLIS
    }

    /** The bounded tier for [failures] failures. */
    fun tierForFailures(failures: Int): PinDelayTier = when {
        failures < 5 -> PinDelayTier.NONE
        failures == 5 -> PinDelayTier.S30
        failures == 6 -> PinDelayTier.M1
        failures == 7 -> PinDelayTier.M5
        failures == 8 -> PinDelayTier.M15
        else -> PinDelayTier.M60
    }

    /** The tier a wrong PIN produced, derived from the state written for that failure. */
    fun tierOf(state: PinAttemptState): PinDelayTier = tierForFailures(state.consecutiveFailures)

    /**
     * The state after one real failure (T24): the counter is incremented (saturating at [MAX_FAILURES]), the origin
     * becomes [PinCounterOrigin.FAILURES] and the new tier starts now. [bootCount] null is stored as [NO_BOOT_COUNT].
     */
    fun afterFailure(state: PinAttemptState, nowElapsed: Long, bootCount: Int?): PinAttemptState {
        val failures = (state.consecutiveFailures.coerceAtLeast(0) + 1).coerceAtMost(MAX_FAILURES)
        return PinAttemptState(
            consecutiveFailures = failures,
            currentDelayMillis = delayMillisForFailures(failures),
            delayStartedElapsed = nowElapsed,
            bootCount = bootCount ?: NO_BOOT_COUNT,
            counterOrigin = PinCounterOrigin.FAILURES
        )
    }

    /**
     * The repaired state for an unreadable attempt state (AMD-11): 4 failures, one 30 s delay starting now, origin
     * [PinCounterOrigin.REPAIRED_UNREADABLE]. Never a wipe.
     */
    fun repairedState(nowElapsed: Long, bootCount: Int?): PinAttemptState = PinAttemptState(
        consecutiveFailures = REPAIRED_FAILURES,
        currentDelayMillis = REPAIRED_DELAY_MILLIS,
        delayStartedElapsed = nowElapsed,
        bootCount = bootCount ?: NO_BOOT_COUNT,
        counterOrigin = PinCounterOrigin.REPAIRED_UNREADABLE
    )

    /** False when a stored value is out of range, which makes the whole attempt state unreadable (repair). */
    fun isPlausible(state: PinAttemptState): Boolean =
        state.consecutiveFailures in 0..MAX_FAILURES &&
            state.currentDelayMillis in 0L..MAX_DELAY_MILLIS &&
            state.delayStartedElapsed >= 0L &&
            state.bootCount >= NO_BOOT_COUNT

    /**
     * Evaluates the persisted delay (INV-39).
     * - No delay, or the delay has elapsed: [PinDelayEvaluation.Idle].
     * - Reboot detected: the known boot count differs from the stored one, OR [nowElapsed] is below the stored start
     *   (elapsedRealtime restarts at boot): the full current tier restarts ([PinDelayEvaluation.Restarted]).
     * - Boot count unknown (the current one is null, or the stored one is [NO_BOOT_COUNT]) and this is the first
     *   evaluation in this process ([firstEvaluationInProcess]): re-anchor the same way, a full tier restart. Later
     *   evaluations in the process use elapsed time only.
     * - Otherwise the remaining time is `delayStartedElapsed + currentDelayMillis - nowElapsed`.
     *
     * Wall-clock time plays no part.
     */
    fun evaluate(
        state: PinAttemptState,
        nowElapsed: Long,
        bootCount: Int?,
        firstEvaluationInProcess: Boolean
    ): PinDelayEvaluation {
        val delay = state.currentDelayMillis.coerceIn(0L, MAX_DELAY_MILLIS)
        if (delay == 0L) return PinDelayEvaluation.Idle
        val reason = reasonOf(state)
        val bootKnown = bootCount != null && state.bootCount != NO_BOOT_COUNT
        val rebooted = (bootKnown && bootCount != state.bootCount) || nowElapsed < state.delayStartedElapsed
        val unknownFirst = !bootKnown && firstEvaluationInProcess
        if (rebooted || unknownFirst) {
            val restarted = state.copy(
                currentDelayMillis = delay,
                delayStartedElapsed = nowElapsed,
                bootCount = bootCount ?: NO_BOOT_COUNT
            )
            return PinDelayEvaluation.Restarted(restarted, delay, reason)
        }
        val remaining = state.delayStartedElapsed + delay - nowElapsed
        return if (remaining <= 0L) {
            PinDelayEvaluation.Idle
        } else {
            PinDelayEvaluation.Active(remaining.coerceAtMost(delay), reason)
        }
    }

    private fun reasonOf(state: PinAttemptState): PinDelayReason =
        if (state.counterOrigin == PinCounterOrigin.REPAIRED_UNREADABLE) {
            PinDelayReason.ATTEMPT_STATE_UNREADABLE
        } else {
            PinDelayReason.FAILURES
        }
}
