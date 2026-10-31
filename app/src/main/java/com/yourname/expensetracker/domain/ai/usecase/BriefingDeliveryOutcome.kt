package com.yourname.expensetracker.domain.ai.usecase

/**
 * Closed outcome of one [DeliverProactiveBriefingNotificationUseCase] invocation (INV-27).
 *
 * Only [DELIVERED] means a notification was actually posted. Every other value is a
 * non-delivery and must never be counted as a sent notification by callers.
 *
 * Values are bounded reason codes: they carry no artifact text, keys or paths.
 */
enum class BriefingDeliveryOutcome {
    /** The notification service posted the briefing notification. */
    DELIVERED,

    /** AI, the dashboard briefing or proactive briefings are switched off in settings. */
    SKIPPED_SETTINGS_OFF,

    /** The same briefing was already delivered or already opened. */
    SKIPPED_DUPLICATE,

    /** No fresh, READY artifact with usable summary text exists for this run. */
    SKIPPED_NO_FRESH_ARTIFACT,

    /**
     * Notification permission blocked delivery. Covers both "not attempted because the
     * local permission check failed" (reported only after the settings, dedupe and artifact
     * checks pass) and, after the app-lock merge, DeliveryResult.PERMISSION_DENIED (a post
     * attempt refused by a SecurityException).
     * Distinct from [NOT_DELIVERED], which means the platform refused a posting attempt
     * for any other reason.
     */
    NOTIFICATION_PERMISSION_DENIED,

    /** The platform refused to post the notification. */
    NOT_DELIVERED
}
