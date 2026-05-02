package com.yourname.expensetracker.domain.notification

import java.security.MessageDigest

/**
 * Computes a deterministic SHA-256 fingerprint for a raw notification.
 *
 * The input format matches the SQL backfill in MIGRATION_104_105:
 *   packageName|timestamp|title|text|bigText
 * (with nulls replaced by empty string).
 *
 * The fingerprint is stored in [RawNotification.dedupeFingerprint] and backed by
 * a unique index so that the database layer rejects true duplicates without
 * relying on in-memory caching alone.
 */
object RawNotificationFingerprint {

    fun compute(
        packageName: String,
        title: String?,
        text: String?,
        bigText: String?,
        timestamp: Long
    ): String {
        val input = "$packageName|$timestamp|${title.orEmpty()}|${text.orEmpty()}|${bigText.orEmpty()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
