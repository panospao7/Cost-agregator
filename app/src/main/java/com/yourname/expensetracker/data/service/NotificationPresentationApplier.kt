package com.yourname.expensetracker.data.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.yourname.expensetracker.domain.notification.presentation.NotificationActionPolicy
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationDecision
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One action button supplied by a caller (only BILL supplies actions today). The applier decides, from
 * the decision's [NotificationActionPolicy], whether and how it is attached. The title is a string
 * resource, resolved by the applier, so no caller-supplied free text can become an action title.
 */
data class NotificationActionSpec(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    val pendingIntent: PendingIntent
)

/**
 * The single place a [NotificationPresentationDecision] is applied to a notification builder
 * (INV-4). Both builder owners, `AndroidNotificationService` and `BillReminderWorker`, call it, so there
 * is one rule for visibility, the public version, the visible text, the expanded style, actions and the
 * kind marker.
 *
 * What [apply] does to the private builder:
 * - visibility is always `VISIBILITY_PRIVATE`; `VISIBILITY_PUBLIC` is never used (spec 4.3);
 * - a generic public version is always attached, in both modes (INV-7, OD-21a): the app-wide pair, no
 *   actions, no style, no marker, no content intent;
 * - FULL shows the caller's detailed title and text; DISCREET shows only the kind's constants and the
 *   detailed text is never read (INV-5, INV-8);
 * - BigText is set only when the decision allows an expanded style (FULL, ANOMALY or BRIEFING); DISCREET
 *   never sets a style, so the expanded view cannot leak;
 * - actions follow the policy: UNCHANGED attaches the specs as supplied, AUTH_REQUIRED attaches each
 *   with `authenticationRequired = true` (effective on API 31+, the compat builder ignores it below),
 *   NONE attaches none (INV-10);
 * - the TECH-12 kind marker is stamped on the private builder only, via [NotificationKindMarker].
 *
 * The caller still owns the channel, small icon, priority, category, auto-cancel and tap target, and must
 * not set title, text, style or actions itself. [apply] never touches the notify id or tag.
 */
@Singleton
class NotificationPresentationApplier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val texts: NotificationPresentationTexts
) {

    /**
     * Applies [decision] to [builder] and returns the same builder. [channelId] and [smallIcon] are only
     * used to build the public version (a public version needs both). [detailedTitle] and [detailedText]
     * are used in FULL only.
     */
    fun apply(
        builder: NotificationCompat.Builder,
        decision: NotificationPresentationDecision,
        channelId: String,
        @DrawableRes smallIcon: Int,
        detailedTitle: String,
        detailedText: String,
        actions: List<NotificationActionSpec> = emptyList()
    ): NotificationCompat.Builder {
        val visibleTitle: String
        val visibleText: String
        if (decision.mode == NotificationPresentationMode.FULL) {
            visibleTitle = detailedTitle
            visibleText = detailedText
        } else {
            val discreet = texts.discreet(decision.kind)
            visibleTitle = discreet.title
            visibleText = discreet.text
        }

        builder
            .setContentTitle(visibleTitle)
            .setContentText(visibleText)
        applyPrivateFrame(builder, channelId, smallIcon)

        if (decision.expandedStyleAllowed && decision.mode == NotificationPresentationMode.FULL) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(detailedText))
        }

        applyActions(builder, decision.actionPolicy, actions)

        // Presentation-mode token (G1b-0): private builder only, next to the kind marker. The public
        // version is built separately in buildPublicVersion and never receives it.
        builder.addExtras(
            Bundle().apply {
                putString(
                    MODE_EXTRA_KEY,
                    if (decision.mode == NotificationPresentationMode.FULL) MODE_TOKEN_FULL else MODE_TOKEN_DISCREET
                )
            }
        )

        return NotificationKindMarker.stamp(builder, decision.kind)
    }

    companion object {
        /**
         * App-namespaced extras key for the content-free presentation-mode token (G1b-0). Never rendered:
         * not a platform text extra. Pinned by test; changing it orphans tokens in active notifications.
         */
        const val MODE_EXTRA_KEY: String = "com.yourname.expensetracker.extra.PRESENTATION_MODE"

        /** Token for a DISCREET notification. A literal, never derived from content. */
        const val MODE_TOKEN_DISCREET: String = "D"

        /** Token for a FULL notification. A literal, never derived from content. */
        const val MODE_TOKEN_FULL: String = "F"

        /**
         * The raw presentation-mode token of [notification], or null when absent, not a string or the
         * notification is null. Never throws. The caller treats anything other than [MODE_TOKEN_DISCREET]
         * (null included) as not confirmed discreet.
         */
        fun readModeToken(notification: Notification?): String? =
            notification?.extras?.getString(MODE_EXTRA_KEY) // getString returns null on a type mismatch
    }

    /** PRIVATE visibility and the generic public version. */
    private fun applyPrivateFrame(
        builder: NotificationCompat.Builder,
        channelId: String,
        @DrawableRes smallIcon: Int
    ) {
        builder
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildPublicVersion(channelId, smallIcon))
    }

    private fun applyActions(
        builder: NotificationCompat.Builder,
        policy: NotificationActionPolicy,
        actions: List<NotificationActionSpec>
    ) {
        when (policy) {
            NotificationActionPolicy.NONE -> Unit
            NotificationActionPolicy.UNCHANGED ->
                actions.forEach { builder.addAction(it.iconRes, context.getString(it.titleRes), it.pendingIntent) }
            NotificationActionPolicy.AUTH_REQUIRED ->
                actions.forEach { spec ->
                    builder.addAction(
                        NotificationCompat.Action.Builder(spec.iconRes, context.getString(spec.titleRes), spec.pendingIntent)
                            .setAuthenticationRequired(true)
                            .build()
                    )
                }
        }
    }

    /** The generic lock-screen version: constants only, no actions, no style, no extras, no marker. */
    private fun buildPublicVersion(channelId: String, @DrawableRes smallIcon: Int) =
        texts.genericPublic().let { pair ->
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(smallIcon)
                .setContentTitle(pair.title)
                .setContentText(pair.text)
                .build()
        }
}
