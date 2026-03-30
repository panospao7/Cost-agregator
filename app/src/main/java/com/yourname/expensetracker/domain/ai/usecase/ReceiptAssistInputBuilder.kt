package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject

/**
 * Builds AI input for receipt analysis with image as primary source.
 * 
 * Key changes for image-aware AI:
 * 1. Always includes the receipt image for AI analysis (primary source)
 * 2. OCR text becomes secondary context, not primary input
 * 3. Supports redaction of sensitive text while keeping image
 * 4. Flags indicate vision analysis mode for AI service
 */
class ReceiptAssistInputBuilder @Inject constructor(
    private val aiPolicy: AiPolicy,
    private val timeProvider: TimeProvider
) {

    fun build(receipt: ScannedReceipt, settings: AiSettings): ReceiptAssistInput {
        val shouldRedact = aiPolicy.shouldRedact(settings, AiCapability.RECEIPT_EXTRACTION)
        
        // For image-aware AI, we always include the image path
        // The AI service will decide whether to use vision API based on this
        val hasValidImage = receipt.imagePath.isNotBlank()
        
        // Get image mime type if we have a valid image
        val imageMimeType = if (hasValidImage) {
            receipt.imagePath.toImageMimeType()
        } else null

        // Sanitize OCR text for privacy if needed
        // Even if redacting, we keep the image - AI can still analyze it
        val sanitizedOcrText = sanitizeOcrText(receipt.rawOcrText, shouldRedact)
        
        // Determine analysis mode
        val isImageAnalysisMode = hasValidImage && settings.receiptImageCloudEnabled

        return ReceiptAssistInput(
            receiptId = receipt.id,
            // NEW: Always include image path if available (primary analysis source)
            imagePath = receipt.imagePath.takeIf { it.isNotBlank() },
            imageMimeType = imageMimeType,
            // NEW: Flag to indicate AI should use vision analysis
            isImageAnalysisMode = isImageAnalysisMode,
            // OCR text is now secondary context
            rawOcrText = sanitizedOcrText,
            // Include existing parsed data as hints
            parsedMerchant = receipt.parsedMerchant?.trim()?.take(120),
            parsedTotal = receipt.parsedTotal,
            parsedDate = receipt.parsedDate,
            parsedTaxAmount = receipt.parsedTaxAmount,
            currency = receipt.currency.take(8),
            lineItemsJson = receipt.parsedItems
                ?.takeIf { !shouldRedact }
                ?.take(AppConfig.Ai.MAX_CAPTURE_SUPPORTING_TEXT_CHARS),
            currentTimeMs = timeProvider.now()
        )
    }

    private fun sanitizeOcrText(raw: String, shouldRedact: Boolean): String {
        val trimmed = raw.trim().take(AppConfig.Ai.MAX_RECEIPT_OCR_CHARS_FOR_AI)
        if (!shouldRedact) return trimmed

        return trimmed
            .replace(IBAN_REGEX, "[REDACTED_IBAN]")
            .replace(CARD_REGEX, "[REDACTED_CARD]")
            .replace(LONG_NUMBER_REGEX, "[REDACTED_NUMBER]")
            .take(AppConfig.Ai.MAX_RECEIPT_OCR_CHARS_FOR_AI)
    }

    private companion object {
        private val IBAN_REGEX = Regex("""\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b""")
        private val CARD_REGEX = Regex("""\b(?:\d[ -]?){13,19}\b""")
        private val LONG_NUMBER_REGEX = Regex("""\b\d{10,}\b""")
    }
}

private fun String.toImageMimeType(): String? {
    return when {
        endsWith(".jpg", ignoreCase = true) || endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        endsWith(".png", ignoreCase = true) -> "image/png"
        endsWith(".webp", ignoreCase = true) -> "image/webp"
        else -> null
    }
}
