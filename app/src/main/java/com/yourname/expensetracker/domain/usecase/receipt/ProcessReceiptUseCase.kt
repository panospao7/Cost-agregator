package com.yourname.expensetracker.domain.usecase.receipt

import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptSource
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyCreationResult
import javax.inject.Inject

/**
 * Use case for processing receipt images through OCR and extracting structured data.
 * Orchestrates OCR, parsing, merchant normalization, categorization, and warranty extraction.
 */
class ProcessReceiptUseCase @Inject constructor(
    private val ocrService: ReceiptOcrService,
    private val receiptParser: ReceiptParser,
    private val merchantNormalizer: MerchantNormalizer,
    private val categorizationEngine: CategorizationEngine
) {
    suspend operator fun invoke(source: ReceiptSource): Result<ProcessedReceipt> {
        return try {
            val ocrResult = when (source) {
                is ReceiptSource.UriRef -> ocrService.processUri(source.value)
            }
            
            val parsed = receiptParser.parse(ocrResult.fullText)
            
            val normalizedMerchant = merchantNormalizer.normalize(
                parsed.merchantName ?: "Unknown"
            ).canonical.normalizedName
            
            val result = categorizationEngine.categorize(normalizedMerchant)
            
            Result.success(ProcessedReceipt(
                merchant = normalizedMerchant,
                amount = parsed.total ?: 0.0,
                categoryId = result.categoryId,
                date = parsed.date,
                imagePath = ocrResult.savedImagePath,
                // F1: Pass through warranty extraction result if available
                warrantyResult = ocrResult.warrantyExtractionResult
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class ProcessedReceipt(
    val merchant: String,
    val amount: Double,
    val categoryId: Long?,
    val date: Long?,
    val imagePath: String,
    // F1: Warranty extraction result
    val warrantyResult: WarrantyCreationResult? = null
)
