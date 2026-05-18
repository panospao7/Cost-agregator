package com.yourname.expensetracker.domain.receipt.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
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
    private val database: AppDatabase,
    private val writeBarrier: DatabaseWriteBarrier,
    private val autoCreateWarrantyUseCase: AutoCreateWarrantyFromReceiptUseCase,
    private val categorizeReceiptItemsUseCase: CategorizeReceiptItemsUseCase,
    private val receiptTransactionMatcher: ReceiptTransactionMatcher,
    private val priceProtectionTracker: PriceProtectionTracker,
    private val receiptLinkService: ReceiptLinkService,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val receiptEventDao: ReceiptEventDao,
    private val timeProvider: TimeProvider,
    private val diagnosticEventWriter: com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter,
    private val sideEffectRecorder: com.yourname.expensetracker.domain.diagnostics.SideEffectDiagnosticRecorder
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
    suspend fun dispatchAfterSave(
        receipt: ScannedReceipt,
        correlationId: String = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId(),
        causationId: String? = null
    ) {
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

        Timber.d("dispatchAfterSave: receiptId=%d, docType=%s, status=%s", receipt.id, docType, status)

        val ctx = com.yourname.expensetracker.domain.diagnostics.SideEffectContext(
            pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.RECEIPT,
            correlationId = correlationId,
            causationId = causationId,
            entityType = "receipt",
            entityId = receipt.id,
            source = receipt.sourceType
        )

        writeReceiptEvent(receipt, "RECEIVED", "Receipt received for side-effect dispatch",
            oldStatus = null, newStatus = status.name)

        if (docType == ReceiptDocumentType.UNKNOWN) {
            writeReceiptEvent(receipt, "VALIDATION_FAILED",
                "Unknown document type: ${receipt.documentType}",
                oldStatus = null, newStatus = status.name)
        }
        // future work. OCR starts inside ReceiptRepository.processReceipt() and
        // parsing is owned by ReceiptParser — neither currently emits lifecycle
        // events. When instrumentation is added, these events should be written
        // at the point of OCR/parse initiation, not here in the dispatcher.

        when (docType) {
            ReceiptDocumentType.RETAIL_RECEIPT -> {
                if (status != ReceiptProcessingStatus.OCR_FAILED &&
                    status != ReceiptProcessingStatus.PARSE_FAILED
                ) {
                    sideEffectRecorder.runSideEffect(ctx, "warranty_extraction") {
                        autoCreateWarrantyUseCase.execute(receipt.id, receipt.rawOcrText)
                    }

                    sideEffectRecorder.runSideEffect(ctx, "receipt_item_categorization") {
                        categorizeReceiptItemsUseCase(receipt.id)
                    }

                    // P3-P1-03: Transaction matching against recent expenses — persist results
                    try {
                        val matchResult = receiptTransactionMatcher.findBestMatch(receipt)
                        when (matchResult) {
                            is MatchResult.AutoMatch -> {
                                val linkResult = receiptLinkService.linkReceiptToExpense(
                                    receiptId = receipt.id,
                                    expenseId = matchResult.transaction.id,
                                    linkType = "AUTO_MATCH",
                                    source = "RECEIPT_MATCHER",
                                    confidence = matchResult.score.toFloat(),
                                    matchStatus = MatchStatus.AUTO_MATCHED
                                )
                                if (linkResult.isFailure) {
                                    Timber.w("P3-P1-03: Auto-match link failed for receipt %d: %s",
                                        receipt.id, linkResult.exceptionOrNull()?.message)
                                    writeReceiptEvent(receipt, "SIDE_EFFECT_FAILED",
                                        "Auto-match link to expense ${matchResult.transaction.id} failed",
                                        errorDetails = linkResult.exceptionOrNull()?.message)
                                } else {
                                    Timber.d("P3-P1-03: Auto-matched receipt %d to expense %d (score=%.3f)",
                                        receipt.id, matchResult.transaction.id, matchResult.score)
                                }
                            }
                            is MatchResult.Suggested -> {
                                writeBarrier.checkWritesAllowed("ReceiptSideEffectDispatcher.suggestedMatch")
                                val now = timeProvider.now()
                                database.withTransaction {
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
                                }
                                Timber.d("P3-P1-03: Suggested match for receipt %d → expense %d (score=%.3f)",
                                    receipt.id, matchResult.transaction.id, matchResult.score)
                            }
                            is MatchResult.NoMatch -> {
                                // No match found — nothing to do
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Transaction matching failed for receipt %d", receipt.id)
                        writeReceiptEvent(receipt, "SIDE_EFFECT_FAILED",
                            "Transaction matching failed", errorDetails = e.message)
                    }

                    // Price-protection / deal-hunting checks
                    try {
                        priceProtectionTracker.findBetterDeals(receipt)
                    } catch (e: Exception) {
                        Timber.w(e, "Price protection check failed for receipt %d", receipt.id)
                        writeReceiptEvent(receipt, "SIDE_EFFECT_FAILED",
                            "Price protection check failed", errorDetails = e.message)
                    }
                }
            }

            ReceiptDocumentType.EMAIL_RECEIPT -> {
                if (status != ReceiptProcessingStatus.OCR_FAILED) {
                    sideEffectRecorder.runSideEffect(ctx, "receipt_item_categorization") {
                        categorizeReceiptItemsUseCase(receipt.id)
                    }
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

    /**
     * P2-17/P2-20: Writes a [ReceiptEvent] for side-effect tracking.
     * Failures are best-effort — event write failures are logged but not propagated.
     */
    private suspend fun writeReceiptEvent(
        receipt: ScannedReceipt,
        eventType: String,
        message: String,
        oldStatus: String? = null,
        newStatus: String? = null,
        errorDetails: String? = null
    ) {
        try {
            receiptEventDao.insert(
                ReceiptEvent(
                    receiptId = receipt.id,
                    sourceType = receipt.sourceType,
                    documentType = receipt.documentType,
                    eventType = eventType,
                    occurredAt = timeProvider.now(),
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                    actor = "system:side_effect_dispatcher",
                    message = message,
                    metadata = null,
                    errorDetails = errorDetails
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to write $eventType event for receipt %d", receipt.id)
        }
        if (eventType == "SIDE_EFFECT_FAILED") {
            try {
                diagnosticEventWriter.emit(
                    com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.RECEIPT,
                        stage = "side_effect",
                        outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.SIDE_EFFECT_FAILED,
                        severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.WARNING,
                        reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.SIDE_EFFECT_EXCEPTION,
                        entityType = "receipt",
                        entityId = receipt.id,
                        metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                            .put("sideEffect", message.take(128))
                            .build()
                    )
                )
            } catch (_: Exception) {}
        }
    }

    /** Wraps a side-effect call with STARTED/COMPLETED/FAILED diagnostic events. */
    private suspend fun runSideEffect(receipt: ScannedReceipt, name: String, block: suspend () -> Unit) {
        // Delegated to SideEffectDiagnosticRecorder — kept for binary compatibility
    }
}
