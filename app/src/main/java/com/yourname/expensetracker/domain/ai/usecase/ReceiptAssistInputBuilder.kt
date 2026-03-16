package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject

class ReceiptAssistInputBuilder @Inject constructor(
    private val aiPolicy: AiPolicy,
    private val timeProvider: TimeProvider
) {

    fun build(receipt: ScannedReceipt, settings: AiSettings): ReceiptAssistInput {
        val shouldRedact = aiPolicy.shouldRedact(settings, AiCapability.RECEIPT_EXTRACTION)

        return ReceiptAssistInput(
            receiptId = receipt.id,
            rawOcrText = sanitizeOcrText(receipt.rawOcrText, shouldRedact),
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
