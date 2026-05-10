package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.ai.usecase.CategorizeReceiptItemsUseCase
import com.yourname.expensetracker.domain.price.PriceProtectionTracker
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.receiptmatching.MatchResult
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import com.yourname.expensetracker.domain.usecase.warranty.AutoCreateWarrantyFromReceiptUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes post-save side effects based on the receipt's document type and
 * processing status.
 *
 * After a receipt is saved to the database, certain downstream operations
 * should be triggered automatically depending on what kind of document it
 * is and whether processing succeeded:
 *
 * - **RETAIL_RECEIPT** (OCR/parse succeeded): warrant y extraction,
 *   item categorization, transaction matching, price protection checks.
 * - **EMAIL_RECEIPT**: item categorization (warranty and price protection
 *   are skipped unless the receipt content supports them).
 * - **BANK_STATEMENT**: no automatic side effects — individual transactions
 *   are handled by [BankStatementLifecycleProcessor].
 * - **MANUAL_PLACEHOLDER**: no automatic effects.
 *
 * Each side effect is wrapped in its own try/catch so that a failure in one
 * does not prevent others from running.
 */
@Singleton
class ReceiptSideEffectDispatcher @Inject constructor(
    private val autoCreateWarrantyUseCase: AutoCreateWarrantyFromReceiptUseCase,
    private val categorizeReceiptItemsUseCase: CategorizeReceiptItemsUseCase,
    private val receiptTransactionMatcher: ReceiptTransactionMatcher,
    private val priceProtectionTracker: PriceProtectionTracker,
    private val receiptLinkService: ReceiptLinkService,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val receiptEventDao: ReceiptEventDao,
    private val timeProvider: TimeProvider
) {

    /**
     * Dispatch all applicable post-save side effects for the given [receipt].
     *
     * Side effects are selected by [ReceiptDocumentType] and gated by
     * [ReceiptProcessingStatus] so that failed receipts do not trigger
     * downstream processing.
     *
     * @param receipt The newly-saved receipt whose side effects should run.
     */
    suspend fun dispatchAfterSave(receipt: ScannedReceipt) {
        val docType = try {
            ReceiptDocumentType.valueOf(receipt.documentType)
        } catch (_: Exception) {
            ReceiptDocumentType.UNKNOWN
        }

        val status = try {
            ReceiptProcessingStatus.valueOf(receipt.processingStatus)
        } catch (_: Exception) {
            ReceiptProcessingStatus.CAPTURED
        }

        Timber.d("dispatchAfterSave: receiptId=%d, docType=%s, status=%s",
            receipt.id, docType, status)

        when (docType) {
            ReceiptDocumentType.RETAIL_RECEIPT -> {
                if (status != ReceiptProcessingStatus.OCR_FAILED &&
                    status != ReceiptProcessingStatus.PARSE_FAILED
                ) {
                    // Warranty extraction from OCR text
                    try {
                        autoCreateWarrantyUseCase.execute(receipt.id, receipt.rawOcrText)
                    } catch (_: Exception) {
                        Timber.w("Warranty extraction failed for receipt %d", receipt.id)
                    }

                    // Item categorization via AI
                    try {
                        categorizeReceiptItemsUseCase(receipt.id)
                    } catch (_: Exception) {
                        Timber.w("Item categorization failed for receipt %d", receipt.id)
                    }

                    // P3-P1-03: Transaction matching against recent expenses — persist results
                    try {
                        val matchResult = receiptTransactionMatcher.findBestMatch(receipt)
                        when (matchResult) {
                            is MatchResult.AutoMatch -> {
                                // Strong match: auto-link receipt to matched expense
                                receiptLinkService.linkReceiptToExpense(
                                    receiptId = receipt.id,
                                    expenseId = matchResult.transaction.id,
                                    linkType = "AUTO_MATCH",
                                    source = "RECEIPT_MATCHER",
                                    confidence = matchResult.score.toFloat(),
                                    matchStatus = MatchStatus.AUTO_MATCHED
                                )
                                Timber.d("P3-P1-03: Auto-matched receipt %d to expense %d (score=%.3f)",
                                    receipt.id, matchResult.transaction.id, matchResult.score)
                            }
                            is MatchResult.Suggested -> {
                                // Medium match: save suggestion without automatic linking
                                val now = timeProvider.now()
                                scannedReceiptDao.update(
                                    receipt.copy(
                                        suggestedExpenseId = matchResult.transaction.id,
                                        matchStatus = MatchStatus.SUGGESTED,
                                        matchConfidence = matchResult.score.toFloat(),
                                        updatedAt = now
                                    )
                                )
                                receiptEventDao.insert(
                                    ReceiptEvent(
                                        receiptId = receipt.id,
                                        sourceType = receipt.sourceType,
                                        documentType = receipt.documentType,
                                        eventType = "MATCH_SUGGESTED",
                                        occurredAt = now,
                                        oldStatus = receipt.processingStatus,
                                        newStatus = null,
                                        actor = "system:receipt_matcher",
                                        message = "Suggested match to expense ${matchResult.transaction.id} (score=${"%.3f".format(matchResult.score)})",
                                        metadata = "{\"suggestedExpenseId\":${matchResult.transaction.id},\"score\":${matchResult.score}}",
                                        errorDetails = null
                                    )
                                )
                                Timber.d("P3-P1-03: Suggested match for receipt %d → expense %d (score=%.3f)",
                                    receipt.id, matchResult.transaction.id, matchResult.score)
                            }
                            is MatchResult.NoMatch -> {
                                // No match found — nothing to do
                            }
                        }
                    } catch (_: Exception) {
                        Timber.w("Transaction matching failed for receipt %d", receipt.id)
                    }

                    // Price-protection / deal-hunting checks
                    try {
                        priceProtectionTracker.findBetterDeals(receipt)
                    } catch (_: Exception) {
                        Timber.w("Price protection check failed for receipt %d", receipt.id)
                    }
                }
            }

            ReceiptDocumentType.EMAIL_RECEIPT -> {
                if (status != ReceiptProcessingStatus.OCR_FAILED) {
                    // Item categorization
                    try {
                        categorizeReceiptItemsUseCase(receipt.id)
                    } catch (_: Exception) {
                        Timber.w("Item categorization failed for email receipt %d", receipt.id)
                    }
                    // Skip price protection and warranty unless content supports it
                }
            }

            ReceiptDocumentType.BANK_STATEMENT -> {
                // No downstream effects for bank statements —
                // transactions are handled by BankStatementLifecycleProcessor.
            }

            ReceiptDocumentType.MANUAL_PLACEHOLDER -> {
                // No automatic effects for manual placeholders.
            }

            else -> {
                // No effects for unknown document types.
            }
        }
    }
}
