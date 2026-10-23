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

    /** Typed-id caller form of [sendAnomalyAlertForKind]. */
    suspend fun postAnomalyAlertForKind(
        kind: NotificationKind,
        notificationId: NotificationId,
        title: String,
        message: String,
        expenseId: Long
    ): DeliveryResult = sendAnomalyAlertForKind(kind, notificationId.value, title, message, expenseId)
}
