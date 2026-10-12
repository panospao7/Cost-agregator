package com.yourname.expensetracker.domain.usecase.receipt

import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptSource
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyCreationResult
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Use case for processing receipt images through OCR and extracting structured data.
 * Orchestrates OCR, parsing, merchant normalization, categorization, and warranty extraction.
 */
class ProcessReceiptUseCase @Inject constructor(
    private val ocrService: ReceiptOcrService,
    private val receiptParser: ReceiptParser,
    private val merchantNormalizer: MerchantNormalizer,
    private val categorizationEngine: CategorizationEngine,
    private val userCurrencyProvider: com.yourname.expensetracker.domain.currency.UserCurrencyProvider
) {
    suspend operator fun invoke(source: ReceiptSource): Result<ProcessedReceipt> {
        return try {
            val homeCurrency = userCurrencyProvider.getHomeCurrency()
            val processedSource = when (source) {
                is ReceiptSource.UriRef -> {
                    val ocrResult = ocrService.processUri(source.value)
                    val parsed = receiptParser.parse(ocrResult.fullText, homeCurrency = homeCurrency)
                    SourceProcessingResult(
                        merchant = parsed.merchantName,
                        amount = parsed.total,
                        date = parsed.date,
                        imagePath = ocrResult.savedImagePath,
                        warrantyResult = ocrResult.warrantyExtractionResult
                    )
                }

                is ReceiptSource.ParsedContent -> {
                    val parsed = receiptParser.parse(source.rawText, homeCurrency = homeCurrency)
                    SourceProcessingResult(
                        merchant = source.merchant ?: parsed.merchantName,
                        amount = source.amount ?: parsed.total,
                        date = source.date ?: parsed.date,
                        imagePath = source.imagePath,
                        warrantyResult = null
                    )
                }
            }

            val resolvedAmount = processedSource.amount
                ?.takeIf { it.currency in CurrencyCode.ALL_SUPPORTED }
                ?: return Result.failure(ReceiptMoneyUnavailableException())

            val normalizedMerchant = merchantNormalizer.normalize(
                processedSource.merchant ?: "Unknown"
            ).canonical.normalizedName

            val result = categorizationEngine.categorize(normalizedMerchant)

            Result.success(ProcessedReceipt(
                merchant = normalizedMerchant,
                amount = resolvedAmount,
                categoryId = result.categoryId,
                categoryConfidence = result.confidence.toFloat(),
                date = processedSource.date,
                imagePath = processedSource.imagePath,
                // F1: Pass through warranty extraction result if available
                warrantyResult = processedSource.warrantyResult
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class ReceiptMoneyUnavailableException : IllegalStateException("RECEIPT_MONEY_UNAVAILABLE")

private data class SourceProcessingResult(
    val merchant: String?,
    val amount: MoneyAmount?,
    val date: Long?,
    val imagePath: String,
    val warrantyResult: WarrantyCreationResult?
)

data class ProcessedReceipt(
    val merchant: String,
    val amount: MoneyAmount,
    val categoryId: Long?,
    val categoryConfidence: Float,
    val date: Long?,
    val imagePath: String,
    // F1: Warranty extraction result
    val warrantyResult: WarrantyCreationResult? = null
)
