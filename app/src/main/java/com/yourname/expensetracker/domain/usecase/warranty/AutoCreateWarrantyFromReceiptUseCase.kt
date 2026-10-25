package com.yourname.expensetracker.domain.usecase.warranty

import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.data.database.entity.WarrantyType
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.domain.receipt.WarrantyExtractionData
import com.yourname.expensetracker.domain.receipt.WarrantyTextExtractor
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result types for warranty creation from receipt.
 */
sealed class WarrantyCreationResult {
    data class Success(val warrantyId: Long, val confidence: Double) : WarrantyCreationResult()
    data class LowConfidence(val extractedData: WarrantyExtractionData) : WarrantyCreationResult()
    data class AlreadyExists(val existingWarrantyId: Long) : WarrantyCreationResult()
    data class Failure(val error: String) : WarrantyCreationResult()
}

/**
 * Use case for automatically creating a warranty from OCR receipt text.
 * 
 * This use case:
 * 1. Extracts warranty information from OCR text using regex patterns
 * 2. Checks if a warranty already exists for this receipt
 * 3. Creates a new warranty if confidence is high enough
 * 4. Flags for review if confidence is low
 *
 * ## Local-only extraction (ai-fixes INV-1, INV-5, INV-8, INV-9)
 * Extraction is local-only: regex patterns via [WarrantyTextExtractor]. There is no cloud or
 * model step anywhere in this path, and no cloud `PrivacyCapability` is consulted on any band.
 * Confidence bands are independent of cloud/privacy settings:
 *
 * - c >= [HIGH_CONFIDENCE_THRESHOLD] (0.70): ACTIVE warranty (`needsReview=false`).
 * - [MINIMUM_CONFIDENCE_THRESHOLD] (0.40) <= c < 0.70: PENDING_REVIEW draft (`needsReview=true`,
 *   `extractionSource="ocr"`) via [createReviewDraftWarranty].
 * - c < 0.40: nothing is persisted.
 *
 * The receipt-text guard is upstream: OCR text reaches [execute] only as the ephemeral raw text
 * passed by `ReceiptSideEffectPlanner` (Policy A). When it is unavailable the side effect is
 * skipped before this use case runs; this class never reads persisted `rawOcrText`. Any future
 * cloud or model step would need `PrivacyGate.check(CLOUD_AI_*)` before data leaves the device
 * (a reviewed increment; see `CloudWarrantyExtractionService`, which has no production caller).
 */
@Singleton
class AutoCreateWarrantyFromReceiptUseCase @Inject constructor(
    private val warrantyTrackerRepository: WarrantyTrackerRepository,
    private val receiptRepository: ReceiptRepository,
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.70
        private const val MINIMUM_CONFIDENCE_THRESHOLD = 0.40
        private const val RETURN_WINDOW_PERSIST_FAILED = "RETURN_WINDOW_PERSIST_FAILED"
        private const val REVIEW_CONFIRM_FAILED = "REVIEW_CONFIRM_FAILED"
        private const val TAG = "AutoCreateWarranty"
    }

    private val warrantyTextExtractor = WarrantyTextExtractor(timeProvider)

    /**
     * Execute the warranty creation from receipt.
     * 
     * @param receiptId The ID of the scanned receipt
     * @param receiptText The OCR text extracted from the receipt
     * @return WarrantyCreationResult indicating success, low confidence, already exists, or failure
     */
    suspend fun execute(receiptId: Long, receiptText: String): WarrantyCreationResult {
        try {
            // Step 0: Document-type gating — skip warranty extraction for non-retail document types
            val receipt = receiptRepository.getReceiptById(receiptId)
            if (receipt != null) {
                if (receipt.documentType == "BANK_STATEMENT" || receipt.documentType == "MANUAL_PLACEHOLDER" ||
                    receipt.processingStatus == "OCR_FAILED") {
                    Timber.tag(TAG).d(
                        "Skipping warranty extraction for receipt $receiptId: " +
                        "documentType=%s, processingStatus=%s",
                        receipt.documentType, receipt.processingStatus
                    )
                    return WarrantyCreationResult.Failure(
                        "Skipped: document type ${receipt.documentType} or status ${receipt.processingStatus} not eligible"
                    )
                }
            }

            // Step 1: Check if warranty already exists for this receipt
            val existingWarranty = warrantyTrackerRepository.getWarrantyByReceiptId(receiptId)
            if (existingWarranty != null) {
                Timber.tag(TAG).d("Warranty already exists for receipt $receiptId")
                return if (persistReturnWindow(receiptId, existingWarranty)) {
                    WarrantyCreationResult.AlreadyExists(existingWarranty.id)
                } else {
                    WarrantyCreationResult.Failure(RETURN_WINDOW_PERSIST_FAILED)
                }
            }

            // Step 2: Extract warranty data from OCR text
            val extractionData = warrantyTextExtractor.extract(receiptText)
            
            // INV-3: bounded diagnostics only (id + confidence); never product or merchant text.
            Timber.tag(TAG).d(
                "Extracted warranty data for receipt $receiptId: confidence=${extractionData.confidence}"
            )

            // Step 3: Determine if extraction quality is sufficient
            return when {
                extractionData.confidence >= HIGH_CONFIDENCE_THRESHOLD -> {
                    // High confidence - create warranty automatically
                    createWarranty(receiptId, extractionData, autoDetect = true)
                }
                extractionData.confidence >= MINIMUM_CONFIDENCE_THRESHOLD -> {
                    // Medium confidence (0.40 <= c < 0.70) - local PENDING_REVIEW draft.
                    // INV-8: independent of any cloud/privacy setting.
                    Timber.tag(TAG).d("Low confidence extraction (${extractionData.confidence}) for receipt $receiptId, creating review draft")
                    val persistResult = createReviewDraftWarranty(receiptId, extractionData)
                    when (persistResult) {
                        is WarrantyCreationResult.AlreadyExists,
                        is WarrantyCreationResult.Failure -> persistResult
                        else -> WarrantyCreationResult.LowConfidence(extractionData)
                    }
                }
                else -> {
                    // Too low confidence - don't create
                    Timber.tag(TAG).d("Confidence too low (${extractionData.confidence}%) for receipt $receiptId, skipping")
                    WarrantyCreationResult.Failure("Confidence too low: ${extractionData.confidence}%")
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // INV-3: class name only; never the throwable (its message/stack may carry receipt text or SQL).
            Timber.tag(TAG).e(
                "Failed to create warranty from receipt %d (%s)",
                receiptId,
                e::class.simpleName ?: "UnknownException"
            )
            return WarrantyCreationResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Create a warranty with low confidence data for review.
     * This is typically called when user confirms a low-confidence extraction.
     */
    suspend fun createWarrantyForReview(
        receiptId: Long, 
        extractionData: WarrantyExtractionData,
        userModifiedData: WarrantyExtractionData? = null
    ): WarrantyCreationResult {
        val finalData = userModifiedData ?: extractionData
        val existingWarranty = warrantyTrackerRepository.getWarrantyByReceiptId(receiptId)
        return if (existingWarranty != null) {
            if (existingWarranty.needsReview || existingWarranty.status == WarrantyStatus.PENDING_REVIEW) {
                promoteReviewDraft(existingWarranty, finalData, autoDetect = true)
            } else {
                if (persistReturnWindow(receiptId, existingWarranty)) {
                    WarrantyCreationResult.AlreadyExists(existingWarranty.id)
                } else {
                    WarrantyCreationResult.Failure(RETURN_WINDOW_PERSIST_FAILED)
                }
            }
        } else {
            createWarranty(receiptId, finalData, autoDetect = true, needsReview = false)
        }
    }

    /**
     * Internal method to create the warranty entity and persist it.
     */
    private suspend fun createWarranty(
        receiptId: Long,
        data: WarrantyExtractionData,
        autoDetect: Boolean,
        needsReview: Boolean = false
    ): WarrantyCreationResult {
        
        // Validate required fields
        if (data.productName == null || data.merchantName == null) {
            return WarrantyCreationResult.Failure("Missing required fields: productName or merchantName")
        }

        if (data.purchaseDate == null) {
            return WarrantyCreationResult.Failure("Missing required field: purchaseDate")
        }

        if (data.warrantyDurationMonths == null || data.warrantyEndDate == null) {
            return WarrantyCreationResult.Failure("Missing required field: warranty duration or end date")
        }

        // Map warranty type string to enum
        val warrantyType = mapWarrantyType(data.warrantyType)

        val now = timeProvider.now()

        // W20: Compute the display-friendly inclusive end date from the stored
        // exclusive (half-open) warrantyEndDate. The warranty is valid through the
        // last covered day; displayEndDate is the last millisecond of that day.
        val warrantyEndDate = data.warrantyEndDate
        val displayEndDate = getDisplayEndDate(warrantyEndDate)

        val warranty = Warranty(
            id = 0, // Will be auto-generated
            receiptId = receiptId,
            expenseId = null, // Will be linked later if expense is created
            productName = data.productName,
            merchantName = data.merchantName,
            purchaseDate = data.purchaseDate,
            warrantyDurationMonths = data.warrantyDurationMonths,
            // W20: warrantyEndDate is exclusive (half-open). The warranty is valid
            // through the end of the last covered day. Display as warrantyEndDate - 1 day.
            warrantyEndDate = warrantyEndDate,
            warrantyType = warrantyType,
            supportPhone = data.supportPhone,
            supportEmail = data.supportEmail,
            warrantyDocumentUrl = null, // Could be extracted from receipt images in future
            // W20: Include the display-friendly end date in notes for UI consumption.
            notes = if (autoDetect) "Auto-detected from receipt. Coverage ends: ${java.time.Instant.ofEpochMilli(displayEndDate).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}" else null,
            status = if (needsReview) WarrantyStatus.PENDING_REVIEW else WarrantyStatus.ACTIVE,
            claimedAt = null,
            createdAt = now,
            updatedAt = now,
            // F1 Pipeline fields
            autoDetected = autoDetect,
            extractionConfidence = data.confidence,
            extractionSource = "ocr",
            needsReview = needsReview
        )

        val warrantyId = warrantyTrackerRepository.addWarrantyIgnoreConflicts(warranty)

        if (warrantyId <= 0) {
            val existing = warrantyTrackerRepository.getWarrantyByReceiptId(receiptId)
            if (existing != null) {
                Timber.tag(TAG).d("Warranty insert ignored (duplicate) for receipt $receiptId")
                return WarrantyCreationResult.AlreadyExists(existing.id)
            }
            return WarrantyCreationResult.Failure("Failed to persist warranty")
        }

        if (!persistReturnWindow(receiptId, warranty.copy(id = warrantyId))) {
            return WarrantyCreationResult.Failure(RETURN_WINDOW_PERSIST_FAILED)
        }
        
        Timber.tag(TAG).i(
            "Created warranty $warrantyId for receipt $receiptId " +
            "(autoDetect=$autoDetect, confidence=${data.confidence}%)"
        )

        return WarrantyCreationResult.Success(warrantyId, data.confidence)
    }

    /**
     * Get the warranty text extractor for external use (e.g., preview before creation).
     */
    fun getExtractor(): WarrantyTextExtractor = warrantyTextExtractor

    /**
     * Persist a low-confidence draft so the review workflow is populated.
     */
    private suspend fun createReviewDraftWarranty(
        receiptId: Long,
        data: WarrantyExtractionData
    ): WarrantyCreationResult {
        val now = timeProvider.now()

        val purchaseDate = data.purchaseDate ?: now
        val durationMonths = data.warrantyDurationMonths ?: 12
        val warrantyEndDate = data.warrantyEndDate ?: calculateWarrantyEndDate(purchaseDate, durationMonths)

        // WRN-18: Use descriptive fallback instead of "Unknown Product" / "Unknown Merchant"
        // so low-confidence drafts don't pollute the list with fake defaults.
        // The user is prompted to fill in actual values during review.
        val fallbackProduct = data.productName?.takeIf { it.isNotBlank() }
            ?: "Pending product name — edit in review"
        val fallbackMerchant = data.merchantName?.takeIf { it.isNotBlank() }
            ?: "Pending merchant — edit in review"

        val draftWarranty = Warranty(
            id = 0,
            receiptId = receiptId,
            expenseId = null,
            productName = fallbackProduct,
            merchantName = fallbackMerchant,
            purchaseDate = purchaseDate,
            warrantyDurationMonths = durationMonths,
            warrantyEndDate = warrantyEndDate,
            warrantyType = mapWarrantyType(data.warrantyType),
            supportPhone = data.supportPhone,
            supportEmail = data.supportEmail,
            warrantyDocumentUrl = null,
            notes = "Auto-detected from receipt (needs review)",
            status = WarrantyStatus.PENDING_REVIEW,
            claimedAt = null,
            createdAt = now,
            updatedAt = now,
            autoDetected = true,
            extractionConfidence = data.confidence,
            extractionSource = "ocr",
            needsReview = true
        )

        val insertedId = warrantyTrackerRepository.addWarrantyIgnoreConflicts(draftWarranty)
        if (insertedId <= 0) {
            val existing = warrantyTrackerRepository.getWarrantyByReceiptId(receiptId)
            return if (existing != null) {
                WarrantyCreationResult.AlreadyExists(existing.id)
            } else {
                WarrantyCreationResult.Failure("Failed to persist review draft")
            }
        }

        if (!persistReturnWindow(receiptId, draftWarranty.copy(id = insertedId))) {
            return WarrantyCreationResult.Failure(RETURN_WINDOW_PERSIST_FAILED)
        }

        Timber.tag(TAG).i(
            "Created low-confidence review draft $insertedId for receipt $receiptId " +
            "(confidence=${data.confidence}%)"
        )

        return WarrantyCreationResult.Success(insertedId, data.confidence)
    }

    private suspend fun promoteReviewDraft(
        existingWarranty: Warranty,
        data: WarrantyExtractionData,
        autoDetect: Boolean
    ): WarrantyCreationResult {
        // F-AI-1: ONE atomic repository operation (fresh in-transaction read -> content-only edit ->
        // confirm on the same row). The edit lambda runs against the freshly read row, so a concurrent
        // expiry sweep or claim is never undone; status, the review flag and claimedAt are never
        // supplied from here. Leaving review is owned solely by WarrantyTrackerRepository.confirmWarranty
        // (past-end rule, CONFIRMED event); an EXPIRED row is never moved back to ACTIVE.
        val promoteResult = warrantyTrackerRepository.promoteReviewDraft(existingWarranty.id) { fresh ->
            val purchaseDate = data.purchaseDate ?: fresh.purchaseDate
            val durationMonths = data.warrantyDurationMonths ?: fresh.warrantyDurationMonths
            val warrantyEndDate = data.warrantyEndDate ?: calculateWarrantyEndDate(purchaseDate, durationMonths)
            fresh.copy(
                productName = data.productName?.takeIf { it.isNotBlank() } ?: fresh.productName,
                merchantName = data.merchantName?.takeIf { it.isNotBlank() } ?: fresh.merchantName,
                purchaseDate = purchaseDate,
                warrantyDurationMonths = durationMonths,
                warrantyEndDate = warrantyEndDate,
                warrantyType = mapWarrantyType(data.warrantyType),
                supportPhone = data.supportPhone ?: fresh.supportPhone,
                supportEmail = data.supportEmail ?: fresh.supportEmail,
                notes = if (autoDetect) "Auto-detected from receipt" else fresh.notes,
                autoDetected = autoDetect,
                extractionConfidence = data.confidence,
                extractionSource = "ocr"
            )
        }
        val confirmed = when (promoteResult) {
            is WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed -> true
            WarrantyTrackerRepository.ConfirmWarrantyResult.AlreadyConfirmed -> false
            WarrantyTrackerRepository.ConfirmWarrantyResult.NotFound ->
                return WarrantyCreationResult.Failure(REVIEW_CONFIRM_FAILED)
        }

        val receiptId = existingWarranty.receiptId
        if (receiptId != null) {
            // Re-read so the return window is derived from the persisted (edited) content rather than
            // the pre-edit copy held by this caller.
            val current = warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) ?: existingWarranty
            if (!persistReturnWindow(receiptId, current)) {
                return WarrantyCreationResult.Failure(RETURN_WINDOW_PERSIST_FAILED)
            }
        }
        Timber.tag(TAG).i(
            "Promoted review draft ${existingWarranty.id} for receipt ${existingWarranty.receiptId} " +
                "(confidence=${data.confidence}%, confirmed=$confirmed)"
        )
        return if (confirmed) {
            WarrantyCreationResult.Success(existingWarranty.id, data.confidence)
        } else {
            WarrantyCreationResult.AlreadyExists(existingWarranty.id)
        }
    }

    private suspend fun persistReturnWindow(receiptId: Long, warranty: Warranty): Boolean {
        try {
            warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, warranty)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(
                "Failed to persist return window for receipt %d (%s)",
                receiptId,
                e::class.simpleName ?: "UnknownException"
            )
            return false
        }
    }

    /**
     * Converts a stored warranty end-date (exclusive upper bound) to a display
     * date (inclusive last covered day).
     *
     * W20: Half-open semantics — the warranty is valid through the end of the last
     * covered day. The stored endDate is the first millisecond AFTER the last
     * covered day (exclusive upper bound). Display shows (endDate - 1 day) to the user.
     *
     * @param warrantyEndMs The stored exclusive end-boundary (ms since epoch).
     * @return The last millisecond of the last covered day (inclusive).
     */
    fun getDisplayEndDate(warrantyEndMs: Long): Long =
        warrantyEndMs - TimePeriodUtils.DAY_IN_MILLIS

    /**
     * Calculates the exclusive end-boundary for a warranty's coverage period.
     *
     * W20: Half-open semantics — the warranty is valid through the end of the last
     * covered day. The stored endDate is the first millisecond AFTER the last
     * covered day (exclusive upper bound). Display shows (endDate - 1 day) to the user.
     *
     * All warranty creation paths must converge on this same half-open contract:
     * - AutoCreateWarrantyFromReceiptUseCase.calculateWarrantyEndDate (this method)
     * - Manual warranty entry (future UI)
     *
     * (The former cloud-extraction end-date path in WarrantyTrackerRepository was retired by
     * ai-fixes; this is now the single production end-date computation.)
     *
     * W20-VERIFIED: All paths use identical half-open semantics.
     * This method uses getEndOfDay(dayStart) which produces an exclusive
     * upper bound. The Warranty entity stores this as warrantyEndDate. Expiry checks
     * use `warrantyEndDate < now()` which correctly evaluates the half-open interval.
     */
    private fun calculateWarrantyEndDate(purchaseDate: Long, durationMonths: Int): Long {
        val zoneId = ZoneId.systemDefault()
        val endDate = Instant.ofEpochMilli(purchaseDate)
            .atZone(zoneId)
            .toLocalDate()
            .plusMonths(durationMonths.toLong())

        // Use half-open end-of-day semantics so the warranty survives
        // through its entire expiration day (matching WarrantyTrackerRepository).
        val dayStart = endDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        return com.yourname.expensetracker.domain.util.TimePeriodUtils.getEndOfDay(dayStart)
    }

    private fun mapWarrantyType(rawType: String?): WarrantyType {
        return when (rawType?.uppercase()) {
            "EXTENDED" -> WarrantyType.EXTENDED
            "STORE" -> WarrantyType.STORE
            "THIRD_PARTY" -> WarrantyType.THIRD_PARTY
            else -> WarrantyType.MANUFACTURER
        }
    }
}
