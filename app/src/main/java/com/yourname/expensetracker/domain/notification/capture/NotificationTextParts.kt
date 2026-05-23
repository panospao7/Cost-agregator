package com.yourname.expensetracker.domain.notification.capture

import android.os.Bundle

/**
 * Holds all text fields extracted from a notification's [Bundle].
 *
 * A single extraction pass produces this struct; every downstream consumer
 * (filter, content hash, fingerprint, raw-notification entity, parser) uses the
 * same instance so that fallback resolution is consistent everywhere.
 */
data class NotificationTextParts(
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val infoText: String?,
    val summaryText: String?,
    /** Resolved bigText with infoText/summaryText fallback. */
    val effectiveBigText: String?,
    /** Lines from `android.app.Notification.EXTRA_TEXT_LINES` — many bank/SMS notifications place transaction details here. */
    val textLines: List<String>,
    /** Messages from `android.app.Notification.EXTRA_MESSAGES` — messaging-style extras. */
    val messages: List<String>,
    /** All unique non-blank text joined into a single body for filter/hash/parser. */
    val combinedBody: String
) {
    companion object {
        /**
         * Extract all text parts from a notification's extras bundle.
         * P1-P1-03: Uses proper MessagingStyle.Message APIs for EXTRA_MESSAGES
         * instead of treating message payloads as plain CharSequence.
         */
        fun extract(extras: Bundle): NotificationTextParts {
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
            val bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
            val subText = extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString()
            val infoText = extras.getCharSequence(android.app.Notification.EXTRA_INFO_TEXT)?.toString()
            val summaryText = extras.getCharSequence(android.app.Notification.EXTRA_SUMMARY_TEXT)?.toString()
            val effectiveBigText = bigText?.takeIf { it.isNotBlank() }
                ?: infoText?.takeIf { it.isNotBlank() }
                ?: summaryText?.takeIf { it.isNotBlank() }

            val textLines = try {
                extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)
                    ?.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
                    ?: emptyList()
            } catch (_: Exception) { emptyList() }

            // P1-P1-03: Correct EXTRA_MESSAGES extraction using MessagingStyle API.
            val messages = try {
                @Suppress("DEPRECATION")
                val parcelables = extras.getParcelableArray(android.app.Notification.EXTRA_MESSAGES)
                if (parcelables != null) {
                    val messageObjects = android.app.Notification.MessagingStyle.Message
                        .getMessagesFromBundleArray(parcelables)
                    messageObjects
                        .mapNotNull { msg -> msg.text?.toString()?.takeIf { it.isNotBlank() } }
                } else {
                    emptyList()
                }
            } catch (_: Exception) {
                try {
                    @Suppress("DEPRECATION")
                    extras.getParcelableArray(android.app.Notification.EXTRA_MESSAGES)
                        ?.mapNotNull { item ->
                            when (item) {
                                is CharSequence -> item.toString().takeIf { it.isNotBlank() }
                                is Bundle -> {
                                    item.getString("text")
                                        ?: item.getCharSequence("text")?.toString()
                                }
                                else -> null // P1-NEW-16: avoid object dumps polluting parser body
                            }
                        }
                        ?: emptyList()
                } catch (_: Exception) { emptyList() }
            }

            // Deterministic combinedBody: title/top-level fields first, then textLines,
            // then messages. linkedSetOf preserves insertion order and deduplicates blanks.
            val uniqueParts = linkedSetOf<String>()
            listOfNotNull(title, text, bigText, subText, infoText, summaryText).forEach { uniqueParts += it }
            textLines.forEach { uniqueParts += it }
            messages.forEach { uniqueParts += it }
            val combinedBody = uniqueParts.joinToString(" ")

            return NotificationTextParts(
                title = title,
                text = text,
                bigText = bigText,
                subText = subText,
                infoText = infoText,
                summaryText = summaryText,
                effectiveBigText = effectiveBigText,
                textLines = textLines,
                messages = messages,
                combinedBody = combinedBody
            )
        }
    }
}


