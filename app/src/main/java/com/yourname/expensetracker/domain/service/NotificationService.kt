package com.yourname.expensetracker.domain.service

import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import com.yourname.expensetracker.domain.util.NotificationId

interface NotificationService {
    enum class DeliveryResult {
        DELIVERED,
        NOT_DELIVERED,

        /**
         * The platform refused the post with a `SecurityException` (notification permission revoked after
         * the caller's own check). Returned only by the kind-aware `*ForKind` methods (TECH-9, owner
         * decision C6 = fold in). It is never a delivery: callers treat it like [NOT_DELIVERED] unless they
         * map it to their own permission diagnostic (receipt matching keeps `..._PERMISSION_REVOKED`).
         */
        PERMISSION_DENIED
    }

    // ── Kind-aware methods (TECH-2 option a, INV-4, INV-12) ──────────────
    // These are the only posting API (INV-12: no kind-less method exists; S9a removed the legacy
    // send*/post* forms). The typed-id forms accept [NotificationId], whose value can only originate
    // from NotificationIdGenerator (RP-16 16-A), so raw Int ids cannot be posted through that path.
    // The caller declares exactly one NotificationKind plus its detailed text. The implementation
    // resolves the presentation decision once per post (bounded, fail-closed to DISCREET), applies it
    // through the shared presentation applier, and posts. The caller still decides whether and when to
    // post; none of these methods changes ids, dedupe or cooldowns. Each method takes the id type its
    // caller uses today (raw Int, or the typed NotificationId). `kind` selects presentation only; the
    // channel is fixed by the method. A `SecurityException` from the platform post is returned as
    // [DeliveryResult.PERMISSION_DENIED] (TECH-9); `CancellationException` is always rethrown.

    /** Budget-channel alert (kinds BUDGET, WARRANTY, RECEIPT_MATCH). Raw-id caller form. */
    suspend fun sendBudgetAlertForKind(
        kind: NotificationKind,
        notificationId: Int,
        title: String,
        message: String
    ): DeliveryResult

    /** Typed-id caller form of [sendBudgetAlertForKind]. */
    suspend fun postBudgetAlertForKind(
        kind: NotificationKind,
        notificationId: NotificationId,
        title: String,
        message: String
    ): DeliveryResult = sendBudgetAlertForKind(kind, notificationId.value, title, message)

    /** AI-briefing-channel notification (kind BRIEFING). Raw-id caller form. */
    suspend fun sendAiBriefingReadyForKind(
        kind: NotificationKind,
        notificationId: Int,
        title: String,
        message: String,
        targetKey: String
    ): DeliveryResult

    /** Anomaly-channel alert (kind ANOMALY). Raw-id caller form. */
    suspend fun sendAnomalyAlertForKind(
        kind: NotificationKind,
        notificationId: Int,
        title: String,
        message: String,
        expenseId: Long
    ): DeliveryResult

    /**
     * Re-posts every currently active notification of the six kinds in its discreet form, in place (spec 4.6,
     * INV-13, AMD-7). Queries the active notifications once, detects each kind from channel and id range (and
     * the hidden marker), and rebuilds a silent notification from the DISCREET decision; no detailed content
     * is copied. It is not a delivery: no sent counters, claims, marks or cooldowns change. The sweep is
     * total per notification: any non-cancellation `Exception` while reading the marker, classifying,
     * rebuilding or posting one notification skips only that notification, counts it as failed and records
     * `NOTIF_REPOST_FAILED` (class name only); the rest are still re-posted. `CancellationException`
     * propagates. Callers invoke it only after a committed write that turned discreet on (INV-46).
     */
    suspend fun repostActiveAsDiscreet(): RepostResult

    /**
     * Bounded outcome of [repostActiveAsDiscreet]: counts only. [failed] counts each notification that could
     * not be re-posted, and counts a failure of the active-notifications query itself as 1 (with
     * [reposted] 0 and one `NOTIF_REPOST_FAILED`).
     */
    data class RepostResult(val reposted: Int, val failed: Int) {
        companion object {
            val NONE = RepostResult(reposted = 0, failed = 0)
        }
    }

    /** Typed-id caller form of [sendAnomalyAlertForKind]. */
    suspend fun postAnomalyAlertForKind(
        kind: NotificationKind,
        notificationId: NotificationId,
        title: String,
        message: String,
        expenseId: Long
    ): DeliveryResult = sendAnomalyAlertForKind(kind, notificationId.value, title, message, expenseId)
}
