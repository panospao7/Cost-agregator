package com.yourname.expensetracker.domain.notification.presentation

/** Presentation mode of one post (domain-spec 2.5). */
enum class NotificationPresentationMode {
    /** The caller's own detailed text (today's behaviour). */
    FULL,

    /** The kind's discreet constants only; nothing derived from the detailed text (INV-5, INV-8). */
    DISCREET
}

/** Visibility level of the private version. `VISIBILITY_PUBLIC` is never an option (spec 4.3). */
enum class NotificationDecisionVisibility {
    PRIVATE
}

/**
 * What the lock-screen public version shows. OD-21 (a), decided at Gate 2: the app-wide generic pair,
 * identical for all six kinds, naming no kind, with no actions and no expanded style (INV-7).
 */
enum class NotificationPublicVersion {
    GENERIC
}

/** How the builder treats the supplied action specs (spec 4.4, INV-10). Only BILL supplies actions. */
enum class NotificationActionPolicy {
    /** Actions exactly as the caller supplied them today. */
    UNCHANGED,

    /** Actions are kept and each is marked `authenticationRequired = true` (API 31+, OD-5). */
    AUTH_REQUIRED,

    /** No actions are posted. */
    NONE
}

/**
 * Why a decision was forced to DISCREET by a failure. Bounded; never carries text, a cause message or
 * a stack trace. The matching durable diagnostic is emitted by the resolver (spec 4.2, 10).
 */
enum class NotificationFallbackReason {
    /** The settings were `Unreadable` with an IO, corruption or invalid-value cause. */
    SETTINGS_UNREADABLE,

    /** The settings read exceeded `T_read` (or reported the TIMEOUT cause). */
    READ_TIMEOUT,

    /** Resolving the settings threw a non-cancellation exception. */
    POLICY_EXCEPTION
}

/**
 * The presentation decision for one notification post (domain-spec 2.5, INV-4).
 *
 * It carries the [kind] so a builder owner can pick the kind's discreet string resources and stamp
 * the TECH-12 marker, but it never carries literal copy. The builder owners only apply it.
 */
data class NotificationPresentationDecision(
    val kind: NotificationKind,
    val mode: NotificationPresentationMode,
    /** True only in FULL mode for kinds that have an expanded style today (ANOMALY, BRIEFING). */
    val expandedStyleAllowed: Boolean,
    val actionPolicy: NotificationActionPolicy,
    /** Null when the mode came from readable settings; set when a failure forced DISCREET. */
    val fallbackReason: NotificationFallbackReason? = null
) {
    val visibility: NotificationDecisionVisibility get() = NotificationDecisionVisibility.PRIVATE

    val publicVersion: NotificationPublicVersion get() = NotificationPublicVersion.GENERIC

    /** True when the visible title and text are the caller's detailed text (FULL), false for the kind's constants. */
    val usesCallerText: Boolean get() = mode == NotificationPresentationMode.FULL
}
