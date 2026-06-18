package com.yourname.expensetracker.domain.privacy

/**
 * Sanitized payload for persisting email receipt data according to the current
 * email receipt [RawStorageMode].
 *
 * Key invariants:
 * - [messageIdStored] is the plaintext messageId — only present when STORE_RAW.
 * - [messageIdHash] is an HMAC hash for deduplication — present in all modes.
 * - [contentFingerprintHash] is a hash of merchant+amount+date — never plaintext fingerprint.
 * - [subject]/[sender]/[bodyText] are null unless STORE_RAW.
 */
data class EmailReceiptPersistencePayload(
    /** Plaintext subject — null unless STORE_RAW. */
    val subject: String?,
    /** Plaintext sender — null unless STORE_RAW. */
    val sender: String?,
    /** Plaintext email body — null unless STORE_RAW. */
    val bodyText: String?,
    /** Plaintext messageId — null unless STORE_RAW. */
    val messageIdStored: String?,
    /** HMAC hash of messageId — always present for deduplication. */
    val messageIdHash: String?,
    /** SHA-256 hash of content fingerprint (merchant+amount+date) — never raw merchant/amount. */
    val contentFingerprintHash: String?,
    /** HMAC hash of provider order ID — null if not available or DO_NOT_STORE. */
    val providerOrderIdHash: String?,
    /** Parsed items JSON — null unless STORE_RAW or STORE_REDACTED. */
    val parsedItemsJson: String?,
    val mode: RawStorageMode
) {
    companion object {
        fun build(
            mode: RawStorageMode,
            subject: String?,
            sender: String?,
            bodyText: String?,
            messageId: String?,
            messageIdHash: String?,
            contentFingerprintHash: String?,
            providerOrderIdHash: String?,
            parsedItemsJson: String?
        ): EmailReceiptPersistencePayload = when (mode) {
            RawStorageMode.STORE_RAW -> EmailReceiptPersistencePayload(
                subject = subject,
                sender = sender,
                bodyText = bodyText,
                messageIdStored = messageId,
                messageIdHash = messageIdHash,
                contentFingerprintHash = contentFingerprintHash,
                providerOrderIdHash = providerOrderIdHash,
                parsedItemsJson = parsedItemsJson,
                mode = mode
            )
            RawStorageMode.STORE_REDACTED -> EmailReceiptPersistencePayload(
                subject = "[REDACTED]",
                sender = "[REDACTED]",
                bodyText = null,
                messageIdStored = null,
                messageIdHash = messageIdHash,
                contentFingerprintHash = contentFingerprintHash,
                providerOrderIdHash = providerOrderIdHash,
                parsedItemsJson = parsedItemsJson,
                mode = mode
            )
            RawStorageMode.STORE_METADATA_ONLY -> EmailReceiptPersistencePayload(
                subject = null,
                sender = null,
                bodyText = null,
                messageIdStored = null,
                messageIdHash = messageIdHash,      // hash for dedup
                contentFingerprintHash = contentFingerprintHash,
                providerOrderIdHash = providerOrderIdHash,
                parsedItemsJson = null,
                mode = mode
            )
            RawStorageMode.DO_NOT_STORE -> EmailReceiptPersistencePayload(
                subject = null,
                sender = null,
                bodyText = null,
                messageIdStored = null,
                messageIdHash = messageIdHash,      // hash for dedup even in DO_NOT_STORE
                contentFingerprintHash = contentFingerprintHash,
                providerOrderIdHash = null,
                parsedItemsJson = null,
                mode = mode
            )
        }
    }
}
