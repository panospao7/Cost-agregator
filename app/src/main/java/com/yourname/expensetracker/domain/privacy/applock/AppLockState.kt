package com.yourname.expensetracker.domain.privacy.applock

/** Why the app is `LOCKED` (domain-spec 2.4). */
enum class LockCause {
    COLD_START,
    TIMEOUT,
    SETTINGS_UNREADABLE
}

/** Why the lock is `INERT` (domain-spec 2.4, INV-32). Bounded: it carries no platform codes or text. */
enum class InertReason {
    NO_CREDENTIAL,
    HW_UNAVAILABLE,
    UNKNOWN,
    API_UNSUPPORTED,
    SETTINGS_UNREADABLE_NO_AUTH
}

/**
 * The state an attempt returns to on every non-success outcome (domain-spec 2.4, AMD-22). It is the
 * kind of state only; for [AuthPurpose.UNLOCK] the controller also keeps the [LockCause] it came from.
 */
enum class AppLockOrigin {
    LOCKED,
    DISABLED,
    UNLOCKED
}

/**
 * What an authentication attempt is for (domain-spec 2.4, INV-54). The purpose fixes the [origin], so
 * an attempt can only be entered from, and only return to, one kind of state (spec 3.3).
 *
 * [ENABLE] and [CONFIRM_CHANGE] are the open-origin prompts: they render as their origin and never
 * remove the open composition (INV-53). The controller starts [UNLOCK] attempts itself (Unlock tap) and
 * the other two through its open-origin start API, which the settings operations (S18) bind.
 */
enum class AuthPurpose(val origin: AppLockOrigin) {
    UNLOCK(AppLockOrigin.LOCKED),
    ENABLE(AppLockOrigin.DISABLED),
    CONFIRM_CHANGE(AppLockOrigin.UNLOCKED);

    /** True for the prompts that start from an open state (`ENABLE`, `CONFIRM_CHANGE`; AMD-22). */
    val isOpenOrigin: Boolean get() = this != UNLOCK
}

/**
 * The closed selector for an open-origin prompt (INV-53, INV-54). It fixes the [AuthPurpose] (and so the
 * origin the attempt may start from and return to) and the constant [AuthPromptCopy] together, so a caller
 * can neither pair a purpose with the wrong text nor pass free text. `UNLOCK` is not in this set: the
 * Unlock tap is the controller's own entry.
 */
enum class OpenOriginPrompt(val purpose: AuthPurpose, val copy: AuthPromptCopy) {
    /** Turning the lock on, from `DISABLED` (T17). */
    ENABLE(AuthPurpose.ENABLE, AuthPromptCopy.ENABLE),

    /** Turning the lock off, from `UNLOCKED` (T18). */
    DISABLE(AuthPurpose.CONFIRM_CHANGE, AuthPromptCopy.DISABLE),

    /** Confirming another change from `UNLOCKED`, such as a weakening write or a review-save (T27, T31). */
    CHANGE(AuthPurpose.CONFIRM_CHANGE, AuthPromptCopy.CHANGE)
}

/**
 * The process-level app-lock state (domain-spec 2.4, INV-24). Process memory only: nothing here is
 * persisted, and every process starts in [Resolving]. Written only by `AppLockController`.
 */
sealed interface AppLockState {
    /** The settings are not resolved yet. Neutral surface, `FLAG_SECURE` set, no financial UI. */
    data object Resolving : AppLockState

    /** The lock is off. */
    data object Disabled : AppLockState

    /** The lock is engaged and the person has not authenticated. */
    data class Locked(val cause: LockCause) : AppLockState

    /**
     * One system prompt is in flight for [attemptId] (INV-34). [purpose] fixes the origin it returns
     * to (AMD-22). For [AuthPurpose.UNLOCK] this renders as the lock surface, not as an open state.
     */
    data class Authenticating(val attemptId: Long, val purpose: AuthPurpose) : AppLockState

    /**
     * Authenticated in this process, or the lock was enabled in this session. Also reached from [Inert]
     * when the person returns within the timeout and the lock can protect again (T15).
     */
    data object Unlocked : AppLockState

    /** The lock is enabled but cannot authenticate, so the UI is open with a banner (INV-32). */
    data class Inert(val reason: InertReason) : AppLockState
}
