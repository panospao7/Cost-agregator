package com.yourname.expensetracker.data.service

import android.content.Context
import androidx.annotation.StringRes
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A title and text pair for one notification version. Constants only; carries no caller data. */
data class NotificationTextPair(val title: String, val text: String)

/**
 * Maps a [NotificationKind] to its discreet constants and the app-wide generic public pair via string
 * resources (INV-5, INV-7, INV-12, TECH-2). It takes the kind only: there is no parameter through which
 * caller text, an amount or a merchant could reach a discreet or public string (INV-8).
 *
 * The domain layer carries the kind and never copy; this data-layer class owns the resource mapping.
 * Strings resolve against the application context locale, like every other service-built notification.
 */
@Singleton
class NotificationPresentationTexts @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** The kind's discreet title and text. Each of the six kinds has its own pair; none reuses BUDGET. */
    fun discreet(kind: NotificationKind): NotificationTextPair =
        NotificationTextPair(
            title = context.getString(discreetTitleRes(kind)),
            text = context.getString(discreetTextRes(kind))
        )

    /** The generic public-version pair, identical for all six kinds and naming no kind (OD-21a). */
    fun genericPublic(): NotificationTextPair =
        NotificationTextPair(
            title = context.getString(R.string.notif_public_title),
            text = context.getString(R.string.notif_public_text)
        )

    @StringRes
    private fun discreetTitleRes(kind: NotificationKind): Int =
        when (kind) {
            NotificationKind.BILL -> R.string.notif_discreet_bill_title
            NotificationKind.ANOMALY -> R.string.notif_discreet_anomaly_title
            NotificationKind.BUDGET -> R.string.notif_discreet_budget_title
            NotificationKind.WARRANTY -> R.string.notif_discreet_warranty_title
            NotificationKind.RECEIPT_MATCH -> R.string.notif_discreet_receipt_match_title
            // Same constant as the Normal briefing title (ux-flows 9.4): content-free in both modes.
            NotificationKind.BRIEFING -> R.string.notification_briefing_ready_title
        }

    @StringRes
    private fun discreetTextRes(kind: NotificationKind): Int =
        when (kind) {
            NotificationKind.BILL -> R.string.notif_discreet_bill_text
            NotificationKind.ANOMALY -> R.string.notif_discreet_anomaly_text
            NotificationKind.BUDGET -> R.string.notif_discreet_budget_text
            NotificationKind.WARRANTY -> R.string.notif_discreet_warranty_text
            NotificationKind.RECEIPT_MATCH -> R.string.notif_discreet_receipt_match_text
            NotificationKind.BRIEFING -> R.string.notif_discreet_briefing_text
        }
}
