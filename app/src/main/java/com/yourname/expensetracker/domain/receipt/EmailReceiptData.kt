package com.yourname.expensetracker.domain.receipt

/**
 * Structured data extracted from an email receipt.
 *
 * This is a richer representation compared to the batch-processing data class
 * in [com.yourname.expensetracker.data.email.EmailReceiptData]; it carries
 * parsed financial fields so the [ReceiptLifecycleCoordinator] can work with
 * fully-extracted receipt information without re-parsing.
 *
 * @property messageId   Unique email message ID (for deduplication).
 * @property from        Sender email address.
 * @property subject     Email subject line.
 * @property body        Raw email body (HTML or plain text).
 * @property receivedAt  Timestamp when the email was received (epoch millis).
 * @property amount      Extracted total amount, if available.
 * @property merchant    Extracted merchant name, if available.
 * @property currency    Extracted currency code (e.g. "EUR", "USD"), if available.
 * @property date        Extracted transaction date (epoch millis), if available.
 * @property items       JSON string of extracted line items, if available.
 */
data class EmailReceiptData(
    val messageId: String,
    val from: String,
    val subject: String,
    val body: String,
    val receivedAt: Long,
    val amount: Double?,
    val merchant: String?,
    val currency: String?,
    val date: Long?,
    val items: String? // JSON
)
