package com.yourname.expensetracker.domain.usecase.warranty

import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.data.database.entity.WarrantyType
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.domain.receipt.WarrantyExtractionData
import com.yourname.expensetracker.domain.receipt.WarrantyTextExtractor
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
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
 */
@Singleton
class AutoCreateWarrantyFromReceiptUseCase @Inject constructor(
    private val warrantyTrackerRepository: WarrantyTrackerRepository,
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val HIGH_CONFIDENCE_THRESHOLD = 70.0
        private const val MINIMUM_CONFIDENCE_THRESHOLD = 40.0
        private const val TAG = "AutoCreateWarranty"
    }

    private val warrantyTextExtractor = WarrantyTextExtractor()

    /**
     * Execute the warranty creation from receipt.
     * 
     * @param receiptId The ID of the scanned receipt
     * @param receiptText The OCR text extracted from the receipt
     * @return WarrantyCreationResult indicating success, low confidence, already exists, or failure
     */
    suspend fun execute(receiptId: Long, receiptText: String): WarrantyCreationResult {
        try {
            // Step 1: Check if warranty already exists for this receipt
            val existingWarranty = warrantyTrackerRepository.getWarrantyByReceiptId(receiptId)
            if (existingWarranty != null) {
                Timber.tag(TAG).d("Warranty already exists for receipt $receiptId")
                return WarrantyCreationResult.AlreadyExists(existingWarranty.id)
            }

            // Step 2: Extract warranty data from OCR text
            val extractionData = warrantyTextExtractor.extract(receiptText)
            
            Timber.tag(TAG).d(
                "Extracted warranty data for receipt $receiptId: " +
                "product=${extractionData.productName}, " +
                "merchant=${extractionData.merchantName}, " +
                "confidence=${extractionData.confidence}"
            )

            // Step 3: Determine if extraction quality is sufficient
            return when {
                extractionData.confidence >= HIGH_CONFIDENCE_THRESHOLD -> {
                    // High confidence - create warranty automatically
                    createWarranty(receiptId, extractionData, autoDetect = true)
                }
                extractionData.confidence >= MINIMUM_CONFIDENCE_THRESHOLD -> {
                    // Medium confidence - flag for review
                    Timber.tag(TAG).d("Low confidence extraction (${extractionData.confidence}%) for receipt $receiptId, flagging for review")
                    WarrantyCreationResult.LowConfidence(extractionData)
                }
                else -> {
                    // Too low confidence - don't create
                    Timber.tag(TAG).d("Confidence too low (${extractionData.confidence}%) for receipt $receiptId, skipping")
                    WarrantyCreationResult.Failure("Confidence too low: ${extractionData.confidence}%")
                }
            }

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create warranty from receipt $receiptId")
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
        return createWarranty(receiptId, finalData, autoDetect = true, needsReview = false)
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
        val warrantyType = when (data.warrantyType?.uppercase()) {
            "EXTENDED" -> WarrantyType.EXTENDED
            "STORE" -> WarrantyType.STORE
            "THIRD_PARTY" -> WarrantyType.THIRD_PARTY
            else -> WarrantyType.MANUFACTURER
        }

        val now = timeProvider.now()

        val warranty = Warranty(
            id = 0, // Will be auto-generated
            receiptId = receiptId,
            expenseId = null, // Will be linked later if expense is created
            productName = data.productName,
            merchantName = data.merchantName,
            purchaseDate = data.purchaseDate,
            warrantyDurationMonths = data.warrantyDurationMonths,
            warrantyEndDate = data.warrantyEndDate,
            warrantyType = warrantyType,
            supportPhone = data.supportPhone,
            supportEmail = data.supportEmail,
            warrantyDocumentUrl = null, // Could be extracted from receipt images in future
            notes = if (autoDetect) "Auto-detected from receipt" else null,
            status = WarrantyStatus.ACTIVE,
            claimedAt = null,
            createdAt = now,
            updatedAt = now,
            // F1 Pipeline fields
            autoDetected = autoDetect,
            extractionConfidence = data.confidence,
            extractionSource = "ocr",
            needsReview = needsReview
        )

        val warrantyId = warrantyTrackerRepository.addWarranty(warranty)
        
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
}
