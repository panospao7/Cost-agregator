package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkHost
import com.yourname.expensetracker.domain.privacy.deeplink.DeepLinkRequest

/**
 * The bounded answer of the controller's link LOCK_QUERY for a link that just arrived (T20, T21, INV-31).
 * It carries no link content: the caller keeps the link it passed in and applies it only on [APPLY_NOW].
 */
enum class LinkDecision {
    /** T21: the app is open and no prompt or hand-off is pending. The caller applies the link now, once. */
    APPLY_NOW,

    /**
     * T20: the app is `LOCKED`, `RESOLVING`, in an `AUTHENTICATING` prompt of any purpose, or an open-origin
     * success is still being applied. The controller holds the link in memory. The caller must not look up,
     * write or navigate; it pulls the release from the controller later.
     */
    HOLD
}

/**
 * The bounded answer of the controller's held-link release. A link is returned at most once.
 */
sealed interface HeldLinkRelease {
    /**
     * Nothing is held, or the link must still be held (the app is not in the foreground, locked, resolving, a
     * prompt or an apply is pending).
     */
    data object None : HeldLinkRelease

    /**
     * The held link may be applied now, exactly once, after the caller re-validates its state-dependent rules
     * (INV-16, INV-30). [afterUnlock] is true when an UNLOCK success happened since the link was held.
     */
    data class Apply(val request: DeepLinkRequest, val afterUnlock: Boolean) : HeldLinkRelease {
        override fun toString(): String = "Apply(host=${request.host}, afterUnlock=$afterUnlock)"
    }

    /**
     * The link was held longer than `T_held` (5 minutes, monotonic, OD-7a), or the clock went backwards. It is
     * dropped, `DL_HELD_EXPIRED` is recorded, and the caller opens Home (AE-60). The same happens when the T33
     * gate lapsed (T_held without an apply completion) with a link held: the link is never applied before the
     * apply completed, so it is dropped as expired.
     */
    data object Expired : HeldLinkRelease
}

/**
 * The single memory-only held deep link (spec 2.6, 5.5, INV-30, T20 to T22).
 *
 * It wraps the already validated, bounded [DeepLinkRequest] from the S3 policy, never raw URI text. It is
 * created and read only by [AppLockController] under its monitor. It is never put in a Bundle or a
 * saved-state, never persisted and never logged: it is not serializable or parcelable, and [toString] names
 * the host only (INV-18). Process death drops it (T22).
 *
 * This type does not start any prompt and has no behaviour of its own beyond the TTL check.
 *
 * @property heldAtMillis the monotonic time the link was held (never a wall-clock input).
 * @property unlockCountAtHold the controller's unlock counter when the link was held, to tell whether an
 *   unlock happened before release (`afterUnlock`).
 */
internal class HeldDeepLink(
    val request: DeepLinkRequest,
    val heldAtMillis: Long,
    val unlockCountAtHold: Long
) {
    val host: DeepLinkHost get() = request.host

    /** True after [HELD_LINK_TTL_MILLIS], and fail-closed true when the monotonic clock went backwards. */
    fun isExpired(nowMillis: Long): Boolean {
        val elapsed = nowMillis - heldAtMillis
        return elapsed < 0L || elapsed >= HELD_LINK_TTL_MILLIS
    }

    override fun toString(): String = "HeldDeepLink(host=$host)"

    companion object {
        /** `T_held` (OD-7a decided: 5 minutes, monotonic; tech-decisions TECH-11). */
        const val HELD_LINK_TTL_MILLIS: Long = 5 * 60_000L
    }
}
