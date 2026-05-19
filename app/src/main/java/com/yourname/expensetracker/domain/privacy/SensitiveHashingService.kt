package com.yourname.expensetracker.domain.privacy

/**
 * Hashing service for privacy-sensitive external identifiers.
 *
 * Rules:
 * - Use [hmacSha256Prefix] for linkable IDs (messageId, providerTransactionId, accountId,
 *   orderId, notification key) so that the same input always produces the same hash
 *   under the same key, enabling deduplication without storing the raw value.
 * - Use [sha256Prefix] only for content that is already non-linkable or where HMAC is
 *   not possible (e.g., payload hash for audit records, where the key is immaterial).
 * - Do NOT use String.hashCode() for any privacy-sensitive identifier.
 */
interface SensitiveHashingService {
    /**
     * HMAC-SHA-256 of [value] keyed by [purpose], truncated to [length] hex chars.
     * Returns null if [value] is null.
     */
    fun hmacSha256Prefix(value: String?, purpose: String, length: Int = 24): String?

    /**
     * Plain SHA-256 of [value], truncated to [length] hex chars.
     * Use only for non-linkable content.
     * Returns null if [value] is null.
     */
    fun sha256Prefix(value: String?, length: Int = 24): String?
}
