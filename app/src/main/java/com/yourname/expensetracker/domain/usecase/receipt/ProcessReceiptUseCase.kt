package com.yourname.expensetracker.domain.usecase.receipt

import android.net.Uri
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import javax.inject.Inject

/**
 * Use case for processing receipt images through OCR and extracting structured data.
 * Orchestrates OCR, parsing, merchant normalization, and categorization.
 */
class ProcessReceiptUseCase @Inject constructor(
    private val ocrService: ReceiptOcrService,
    private val receiptParser: ReceiptParser,
    private val merchantNormalizer: MerchantNormalizer,
    private val categorizationEngine: CategorizationEngine
) {
    suspend operator fun invoke(imageUri: Uri): Result<ProcessedReceipt> {
        return try {
            val ocrResult = ocrService.processUri(imageUri)
            
            val parsed = receiptParser.parse(ocrResult.fullText)
            
            val normalizedMerchant = merchantNormalizer.normalize(
                parsed.merchantName ?: "Unknown"
            ).canonical.normalizedName
            
            val categoryId = categorizationEngine.categorize(normalizedMerchant)
            
            Result.success(ProcessedReceipt(
                merchant = normalizedMerchant,
                amount = parsed.total ?: 0.0,
                categoryId = categoryId,
                date = parsed.date,
                imagePath = ocrResult.savedImagePath
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
    val imagePath: String
)
