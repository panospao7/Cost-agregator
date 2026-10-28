package com.yourname.expensetracker.data.service

import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import com.yourname.expensetracker.service.reminder.BillReminderWorker

/**
 * Detects which of the six notification kinds an active notification is, for the discreet re-post sweep
 * (domain-spec 4.6, AMD-21, AMD-22, INV-13, TECH-12 option a).
 *
 * Pure and total: a function of the channel id, the notification id and the raw kind-marker token. It
 * reads no notification text, no extras other than the marker token the caller passes in, and never throws.
 *
 * Rules, first match wins:
 * 1. Marker first, but only when it is present, is a known [NotificationKind.stableToken], and names a kind
 *    allowed on the notification's channel (budget_alerts: BUDGET, WARRANTY, RECEIPT_MATCH; bill_reminders:
 *    BILL; anomaly_alerts: ANOMALY; ai_briefings: BRIEFING). A marker never brings another channel in.
 * 2. Otherwise the channel and id-range table: bill_reminders is BILL, anomaly_alerts is ANOMALY,
 *    ai_briefings is BRIEFING; budget_alerts ids 10000 to 19999 are WARRANTY, 20000 to 29999 are
 *    RECEIPT_MATCH, any other id is BUDGET (budget ids are the raw budget id, so there is no 1 to 9999
 *    assumption).
 * 3. Any other channel, including the capture foreground-service channel, is not a sweep kind (null).
 *
 * Residual (F23, accepted in 4.6): a marker-less budget alert whose raw budget id falls in 10000 to 29999 is
 * labelled WARRANTY or RECEIPT_MATCH. The re-post is still content-free; only the kind label is wrong.
 *
 * Architecture ruling (S8): the only member imported from [BillReminderWorker] is its `internal const val
 * CHANNEL_ID`, which the compiler inlines. No other member of the worker may be referenced here.
 */
object NotificationKindClassifier {

    private const val WARRANTY_ID_START = 10_000
    private const val RECEIPT_MATCH_ID_START = 20_000
    private const val RECEIPT_MATCH_ID_END_EXCLUSIVE = 30_000

    private val BUDGET_CHANNEL_KINDS =
        setOf(NotificationKind.BUDGET, NotificationKind.WARRANTY, NotificationKind.RECEIPT_MATCH)

    /**
     * The kind of the notification, or null when it is not one of the six sweep kinds (other channel, or a
     * null channel). [rawMarker] is the value read from the kind marker extra, untrusted and possibly null.
     */
    fun classify(channelId: String?, notificationId: Int, rawMarker: String?): NotificationKind? {
        val allowed = allowedKinds(channelId) ?: return null
        val markerKind = knownKind(rawMarker)
        if (markerKind != null && markerKind in allowed) return markerKind
        return fromTable(channelId, notificationId)
    }

    private fun allowedKinds(channelId: String?): Set<NotificationKind>? =
        when (channelId) {
            AndroidNotificationService.BUDGET_CHANNEL_ID -> BUDGET_CHANNEL_KINDS
            BillReminderWorker.CHANNEL_ID -> setOf(NotificationKind.BILL)
            AndroidNotificationService.ANOMALY_CHANNEL_ID -> setOf(NotificationKind.ANOMALY)
            AndroidNotificationService.AI_CHANNEL_ID -> setOf(NotificationKind.BRIEFING)
            else -> null
        }

    private fun knownKind(rawMarker: String?): NotificationKind? =
        if (rawMarker == null) null else NotificationKind.values().firstOrNull { it.stableToken == rawMarker }

    private fun fromTable(channelId: String?, notificationId: Int): NotificationKind? =
        when (channelId) {
            BillReminderWorker.CHANNEL_ID -> NotificationKind.BILL
            AndroidNotificationService.ANOMALY_CHANNEL_ID -> NotificationKind.ANOMALY
            AndroidNotificationService.AI_CHANNEL_ID -> NotificationKind.BRIEFING
            AndroidNotificationService.BUDGET_CHANNEL_ID -> when (notificationId) {
                in WARRANTY_ID_START until RECEIPT_MATCH_ID_START -> NotificationKind.WARRANTY
                in RECEIPT_MATCH_ID_START until RECEIPT_MATCH_ID_END_EXCLUSIVE -> NotificationKind.RECEIPT_MATCH
                else -> NotificationKind.BUDGET
            }
            else -> null
        }
}
