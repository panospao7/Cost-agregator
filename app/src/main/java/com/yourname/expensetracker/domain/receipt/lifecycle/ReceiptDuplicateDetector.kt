package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects duplicate receipts by comparing multiple fingerprint strategies.
 *
 * Supports four match types, ordered by descending confidence:
 * 1. **EXACT_HASH** — Exact image file SHA-256 hash match (highest confidence).
 * 2. **TEXT_FINGERPRINT** — Normalized OCR text fingerprint (SHA-256 of cleaned text).
 * 3. **SEMANTIC** — Semantic fingerprint (merchant + amount + date bucket + currency).
 * 4. **EXTERNAL_ID** — External source identifier match (e.g. email message ID stored
 *    in [ScannedReceipt.sourceFingerprint]).
 *
 * ## RCP-5: Perceptual image hash for receipt dedup (planned)
 * Currently only SHA-256 exact matching exists, which misses near-duplicate images
 * (resized, re-compressed, cropped). The plan is to add pHash/dHash perceptual hashing:
 *
 * 1. Decode the receipt bitmap from the image file.
 * 2. Resize to a small fixed size (8×8 for dHash, 32×32 for pHash).
 * 3. Convert to grayscale if needed.
 * 4. Compute the perceptual hash:
 *    - **dHash (difference hash):** Compare adjacent horizontal pixel pairs;
 *      set bit = 1 if left pixel > right pixel, else 0. Produces a 64-bit hash.
 *    - **pHash (perceptual hash):** Apply DCT on the 32×32 grayscale, keep the
 *      top-left 8×8 low-frequency components, compute the median, and set each
 *      bit based on whether the component exceeds the median.
 * 5. Compare hashes using Hamming distance (number of differing bits).
 *    A distance <= 10 typically indicates a near-duplicate.
 *
 * A new match type `PERCEPTUAL_HASH` (confidence ~0.9) would sit between EXACT_HASH
 * and TEXT_FINGERPRINT in priority. The [checkDuplicate] method would be extended to
 * accept a [Bitmap] parameter alongside [imageHash] and run perceptual comparison when
 * exact hash misses but the image is available.
 *
 * @property scannedReceiptDao DAO for querying existing receipts by fingerprint.
 * @property receiptExpenseLinkDao DAO for checking existing receipt-expense links
 *            (present for future use; not currently queried during duplicate detection).
 */
@Singleton
class ReceiptDuplicateDetector @Inject constructor(
    private val scannedReceiptDao: ScannedReceiptDao,
    private val receiptExpenseLinkDao: ReceiptExpenseLinkDao
) {

    /**
     * Result of a duplicate check operation.
     *
     * @property isDuplicate Whether the input appears to be a duplicate.
     * @property confidence How confident the detector is (0.0..1.0).
     * @property existingReceiptId The ID of the matched receipt, if any.
     * @property reason Human-readable explanation of the match.
     * @property matchType The strategy that produced the match:
     *            "EXACT_HASH", "TEXT_FINGERPRINT", "SEMANTIC", "EXTERNAL_ID", or "NONE".
     */
    data class DuplicateResult(
        val isDuplicate: Boolean,
        val confidence: Float,
        val existingReceiptId: Long?,
        val reason: String?,
        val matchType: String // "EXACT_HASH" | "TEXT_FINGERPRINT" | "SEMANTIC" | "EXTERNAL_ID" | "NONE"
    )

    /**
     * Checks whether an incoming receipt is a duplicate of an existing one.
     *
     * Evaluation order:
     * 1. **EXACT_HASH** — If [imageHash] is non-null and matches an existing row,
     *    returns immediately with 1.0 confidence.
     * 2. **TEXT_FINGERPRINT** — If [textFingerprint] is non-null and matches,
     *    returns with 0.95 confidence.
     * 3. **SEMANTIC** — If [semanticFingerprint] is non-null and matches,
     *    returns with 0.8 confidence.
     * 4. **EXTERNAL_ID** — If [externalSourceId] is non-null and matches the
     *    [ScannedReceipt.sourceFingerprint] column, returns with 1.0 confidence.
     * 5. **NONE** — No match found.
     *
     * The first match wins (highest confidence takes priority).
     *
     * @param imageHash SHA-256 hash of the receipt image file (may be null).
     * @param textFingerprint Text fingerprint of the normalized OCR text (may be null).
     * @param semanticFingerprint Semantic fingerprint of merchant/amount/date/currency (may be null).
     * @param externalSourceId External source identifier (e.g. email messageId)
     *            stored in [ScannedReceipt.sourceFingerprint] (may be null).
     * @return A [DuplicateResult] indicating whether a duplicate was found.
     */
    suspend fun checkDuplicate(
        imageHash: String?,
        textFingerprint: String?,
        semanticFingerprint: String?,
        externalSourceId: String?
    ): DuplicateResult {
        // 1. Exact image hash match — highest confidence
        if (imageHash != null) {
            val existing = scannedReceiptDao.getByImageHash(imageHash)
            if (existing != null) {
                return DuplicateResult(
                    isDuplicate = true,
                    confidence = 1.0f,
                    existingReceiptId = existing.id,
                    reason = "Exact image hash match: $imageHash",
                    matchType = "EXACT_HASH"
                )
            }
        }

        // 2. Text fingerprint match — high confidence
        if (textFingerprint != null) {
            val existing = scannedReceiptDao.getByTextFingerprint(textFingerprint)
            if (existing != null) {
                return DuplicateResult(
                    isDuplicate = true,
                    confidence = 0.95f,
                    existingReceiptId = existing.id,
                    reason = "Text fingerprint match",
                    matchType = "TEXT_FINGERPRINT"
                )
            }
        }

        // 3. Semantic fingerprint match — medium confidence
        if (semanticFingerprint != null) {
            val existing = scannedReceiptDao.getBySemanticFingerprint(semanticFingerprint)
            if (existing != null) {
                return DuplicateResult(
                    isDuplicate = true,
                    confidence = 0.8f,
                    existingReceiptId = existing.id,
                    reason = "Semantic fingerprint match (merchant/amount/date/currency)",
                    matchType = "SEMANTIC"
                )
            }
        }

        // 4. External source ID match (e.g. email messageId in sourceFingerprint)
        if (externalSourceId != null) {
            val existing = scannedReceiptDao.getBySourceFingerprint(externalSourceId)
            if (existing != null) {
                return DuplicateResult(
                    isDuplicate = true,
                    confidence = 1.0f,
                    existingReceiptId = existing.id,
                    reason = "External source ID match: $externalSourceId",
                    matchType = "EXTERNAL_ID"
                )
            }
        }

        // 5. No match
        return DuplicateResult(
            isDuplicate = false,
            confidence = 0.0f,
            existingReceiptId = null,
            reason = null,
            matchType = "NONE"
        )
    }

    /**
     * Computes a normalized text fingerprint from raw OCR text.
     *
     * Normalization steps:
     * 1. Convert to lowercase.
     * 2. Collapse all whitespace (including newlines) into single spaces.
     * 3. Strip common amount patterns (e.g. "12.34", "€5.00", "$1,234.56").
     * 4. Strip common date patterns (e.g. "2024-01-15", "01/15/2024").
     * 5. Trim leading/trailing whitespace.
     * 6. Compute SHA-256 digest of the cleaned string.
     *
     * @param rawText The raw OCR text to fingerprint.
     * @return Hex-encoded SHA-256 hash of the normalized text.
     */
    private fun computeTextFingerprint(rawText: String): String {
        val cleaned = rawText
            .lowercase()
            .replace(Regex("\\s+"), " ")
            // Strip amount patterns: optional currency symbol, digits with optional decimals
            .replace(Regex("""[€$£¥]?\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?"""), "")
            // Strip date patterns: yyyy-MM-dd, dd/MM/yyyy, MM/dd/yyyy, etc.
            .replace(Regex("""\d{4}[-/]\d{1,2}[-/]\d{1,2}"""), "")
            .replace(Regex("""\d{1,2}[-/]\d{1,2}[-/]\d{2,4}"""), "")
            .trim()

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(cleaned.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes a semantic fingerprint for matching receipts by content.
     *
     * Concatenates:
     * - Normalized merchant (lowercase, trimmed)
     * - Amount rounded to 2 decimal places
     * - Date bucket (epoch day = date / 86400000, effectively grouping by UTC day)
     * - Currency (uppercase, trimmed)
     *
     * Then computes SHA-256 of the concatenated string.
     *
     * @param merchant Normalized merchant name (may be null).
     * @param amount Receipt total amount (may be null).
     * @param date Receipt date in epoch milliseconds (may be null).
     * @param currency Currency code (may be null).
     * @return Hex-encoded SHA-256 hash, or empty string if all inputs are null/empty.
     */
    private fun computeSemanticFingerprint(
        merchant: String?,
        amount: Double?,
        date: Long?,
        currency: String?
    ): String {
        val merchantPart = merchant?.lowercase()?.trim()?.take(100) ?: ""
        val amountPart = amount?.let { "%.2f".format(it) } ?: ""
        // Use local calendar day key for consistent day boundaries
        val dateBucket = date?.let { TimePeriodUtils.getStartOfDay(it) }?.toString() ?: ""
        val currencyPart = currency?.uppercase()?.trim() ?: ""

        val raw = "$merchantPart|$amountPart|$dateBucket|$currencyPart"
        if (raw.all { it == '|' }) return ""

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Public helper that delegates to [computeTextFingerprint].
     *
     * Useful for callers that need to pre-compute a fingerprint before calling
     * [checkDuplicate].
     */
    fun computeTextFingerprintPublic(rawText: String): String = computeTextFingerprint(rawText)

    /**
     * Public helper that delegates to [computeSemanticFingerprint].
     *
     * Useful for callers that need to pre-compute a fingerprint before calling
     * [checkDuplicate].
     */
    fun computeSemanticFingerprintPublic(
        merchant: String?,
        amount: Double?,
        date: Long?,
        currency: String?
    ): String = computeSemanticFingerprint(merchant, amount, date, currency)

    private companion object {
        // Date bucketing now uses TimePeriodUtils.getStartOfDay — no raw millis constant needed.
    }
}
