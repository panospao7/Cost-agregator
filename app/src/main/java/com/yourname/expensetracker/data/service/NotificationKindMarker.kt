package com.yourname.expensetracker.data.service

import android.app.Notification
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind

/**
 * The hidden kind marker stamped on every private notification the app builds through
 * [NotificationPresentationApplier] (TECH-12 option a, INV-13 post side, AE-112, AE-121).
 *
 * - One app-namespaced extras key ([EXTRA_KEY]). The value is the kind's literal
 *   [NotificationKind.stableToken] and nothing else (no id, no text, no timestamp).
 * - Stamped on the private version only. The generic public version never carries it.
 * - The key is not one of the platform text extras (title, text, big text, sub text, info text, summary
 *   text, ticker, text lines), so it never renders and the platform never treats it as content.
 * - Stamping never changes the id or tag passed to `notify`.
 * - [read] returns the raw stored token or null. It validates nothing: the re-post sweep (S9) treats the
 *   value as untrusted and classifies it itself.
 *
 * The marker is readable by notification listeners and companion devices, so it holds the same
 * information as the discreet title (the kind) and no more (OD-10a, RP-20).
 */
object NotificationKindMarker {

    /** App-namespaced extras key. Pinned by test; changing it orphans markers in active notifications. */
    const val EXTRA_KEY: String = "com.yourname.expensetracker.extra.NOTIFICATION_KIND"

    /** Stamps the marker for [kind] on the private builder and returns the same builder. */
    fun stamp(builder: NotificationCompat.Builder, kind: NotificationKind): NotificationCompat.Builder =
        builder.addExtras(Bundle().apply { putString(EXTRA_KEY, kind.stableToken) })

    /** The raw marker token of [notification], or null when absent or not a string. Never throws. */
    fun read(notification: Notification?): String? = read(notification?.extras)

    /** The raw marker token in [extras], or null when absent or not a string. Never throws. */
    fun read(extras: Bundle?): String? = extras?.getString(EXTRA_KEY) // Bundle.getString returns null on a type mismatch
}
