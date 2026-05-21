package com.yourname.expensetracker.domain.receipt.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.ai.usecase.CategorizeReceiptItemsUseCase
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.price.PriceProtectionTracker
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.receiptmatching.MatchResult
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import com.yourname.expensetracker.domain.sideeffect.PostCommitAction
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.SideEffectCategory
import com.yourname.expensetracker.domain.sideeffect.SideEffectOutcome
import com.yourname.expensetracker.domain.sideeffect.SideEffectPriority
import com.yourname.expensetracker.domain.sideeffect.SideEffectSkipReason
import com.yourname.expensetracker.domain.sideeffect.SideEffectTriggerType
import com.yourname.expensetracker.domain.usecase.warranty.AutoCreateWarrantyFromReceiptUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plans receipt post-save side effects as typed [PostCommitActionBatch] instances.
 *
 * This planner replaces the imperative dispatch in [ReceiptSideEffectDispatcher] with
 * a declarative plan that the [PostCommitActionRunner] executes after the database
 * transaction commits. Each action carries an idempotency key so that the runner can
 * safely retry or deduplicate.
 *
 * ## Mapping rules
 * - **RETAIL_RECEIPT** + healthy status: warranty extraction, item categorization,
 *   transaction matching, price protection.
 * - **EMAIL_RECEIPT**: item categorization only.
 * - **BANK_STATEMENT** / **MANUAL_PLACEHOLDER** / **UNKNOWN**: no automatic side effects.
 * - **OCR_FAILED** / **PARSE_FAILED** / **DUPLICATE_DETECTED**: empty batch (skip).
 */
@Singleton
class ReceiptSideEffectPlanner @Inject constructor(
    private val autoCreateWarrantyUseCase: AutoCreateWarrantyFromReceiptUseCase,
    private val categorizeReceiptItemsUseCase: CategorizeReceiptItemsUseCase,
    private val receiptTransactionMatcher: ReceiptTransactionMatcher,
    private val priceProtectionTracker: PriceProtectionTracker,
    private val receiptLinkService: ReceiptLinkService,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val receiptEventDao: ReceiptEventDao,
    private val timeProvider: TimeProvider,
    private val writeBarrier: DatabaseWriteBarrier,
    private val database: AppDatabase
) {

    companion object {
        /** Statuses that should never trigger receipt side effects. */
        private val SKIPPED_STATUSES = setOf(
            ReceiptProcessingStatus.OCR_FAILED,
            ReceiptProcessingStatus.PARSE_FAILED,
            ReceiptProcessingStatus.DUPLICATE_DETECTED
        )
    }

    // ─── Public API ──────────────────────────────────────────────────────────────

    /**
     * Plans side effects that should run after a receipt is saved to the database.
     *
     * @param receipt          The newly-saved receipt.
     * @param correlationId    Correlation ID for event tracing (auto-generated if null).
     * @param causationId      Optional causation ID linking to a parent operation.
     * @param linkedExpenseIds Expense IDs that this receipt was already linked to
     *                         in the same flow. When non-empty, transaction matching
     *                         is skipped to prevent double-dispatch.
     * @return A [PostCommitActionBatch] with zero or more actions.
     */
    fun planAfterReceiptSaved(
        receipt: ScannedReceipt,
        correlationId: String?,
        causationId: String? = null,
        linkedExpenseIds: Set<Long> = emptySet()
    ): PostCommitActionBatch {
        val corrId = correlationId ?: CorrelationIds.newId()
        val docType = parseDocType(receipt.documentType)
        val status = parseStatus(receipt.processingStatus)

        // Skip for failed / duplicate statuses
        if (status in SKIPPED_STATUSES) {
            Timber.d("planAfterReceiptSaved: skipping side effects for receipt %d (status=%s)", receipt.id, status)
            return PostCommitActionBatch.empty(corrId)
        }

        // PR5: When the receipt is already linked to an expense in the same flow,
        // skip transaction matching to prevent double-dispatch.
        val alreadyLinked = linkedExpenseIds.isNotEmpty()

        val actions = when (docType) {
            ReceiptDocumentType.RETAIL_RECEIPT -> listOfNotNull(
                makeWarrantyExtractionAction(receipt, corrId, causationId),
                makeItemCategorizationAction(receipt, corrId, causationId),
                // Skip transaction matching if already linked in the same flow
                if (alreadyLinked) null else makeTransactionMatchAction(receipt, corrId, causationId),
                makePriceProtectionAction(receipt, corrId, causationId)
            )

            ReceiptDocumentType.EMAIL_RECEIPT -> listOfNotNull(
                makeItemCategorizationAction(receipt, corrId, causationId)
            )

            // BANK_STATEMENT, MANUAL_PLACEHOLDER, PDF_RECEIPT, UNKNOWN → no automatic side effects
            ReceiptDocumentType.BANK_STATEMENT,
            ReceiptDocumentType.MANUAL_PLACEHOLDER,
            ReceiptDocumentType.PDF_RECEIPT,
            ReceiptDocumentType.UNKNOWN -> emptyList()
        }

        return PostCommitActionBatch(corrId, actions)
    }

    /**
     * Plans side effects that should run after a receipt is linked to an expense.
     *
     * Currently returns an empty batch.  Future extensions may include
     * warranty re-check, categorization propagation, etc.
     */
    fun planAfterReceiptLinked(
        receiptId: Long,
        expenseId: Long,
        linkType: String,
        correlationId: String?,
        causationId: String? = null
    ): PostCommitActionBatch {
        // Reserved for future use
        return PostCommitActionBatch.empty(correlationId ?: CorrelationIds.newId())
    }

    /**
     * Plans side effects that should run after a receipt is unlinked from an expense.
     *
     * Currently returns an empty batch.
     */
    fun planAfterReceiptUnlinked(
        receiptId: Long,
        expenseId: Long,
        correlationId: String?,
        causationId: String? = null
    ): PostCommitActionBatch {
        // Reserved for future use
        return PostCommitActionBatch.empty(correlationId ?: CorrelationIds.newId())
    }

    // ─── Private helpers ─────────────────────────────────────────────────────────

    private fun parseDocType(docTypeStr: String): ReceiptDocumentType {
        return try {
            ReceiptDocumentType.valueOf(docTypeStr)
        } catch (_: Exception) {
            ReceiptDocumentType.UNKNOWN
        }
    }

    private fun parseStatus(statusStr: String): ReceiptProcessingStatus {
        return try {
            ReceiptProcessingStatus.valueOf(statusStr)
        } catch (_: Exception) {
            ReceiptProcessingStatus.CAPTURED
        }
    }

    // ─── Action factories ────────────────────────────────────────────────────────

    private fun makeWarrantyExtractionAction(
        receipt: ScannedReceipt,
        correlationId: String,
        causationId: String?
    ): PostCommitAction {
        val receiptId = receipt.id
        val receiptText = receipt.rawOcrText
        return PostCommitAction(
            pipeline = AppPipeline.RECEIPT,
            name = "warranty_extraction",
            category = SideEffectCategory.WARRANTY,
            triggerType = SideEffectTriggerType.RECEIPT_SAVED,
            targetEntityType = "RECEIPT",
            targetEntityId = receiptId,
            source = receipt.sourceType,
            correlationId = correlationId,
            causationId = causationId,
            idempotencyKey = "receipt:$receiptId:saved:warranty_extraction",
            priority = SideEffectPriority.NORMAL,
            metadata = SafeEventMetadata.builder()
                .put("receiptId", receiptId.toString())
                .build()
        ) {
            try {
                autoCreateWarrantyUseCase.execute(receiptId, receiptText)
                SideEffectOutcome.Completed
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Warranty extraction failed for receipt %d", receiptId)
                SideEffectOutcome.FailedRetryable(
                    e.message ?: "Warranty extraction failed",
                    e.javaClass.name
                )
            }
        }
    }

    private fun makeItemCategorizationAction(
        receipt: ScannedReceipt,
        correlationId: String,
        causationId: String?
    ): PostCommitAction {
        val receiptId = receipt.id
        return PostCommitAction(
            pipeline = AppPipeline.RECEIPT,
            name = "receipt_item_categorization",
            category = SideEffectCategory.RECEIPT_ITEM_CATEGORIZATION,
            triggerType = SideEffectTriggerType.RECEIPT_SAVED,
            targetEntityType = "RECEIPT",
            targetEntityId = receiptId,
            source = receipt.sourceType,
            correlationId = correlationId,
            causationId = causationId,
            idempotencyKey = "receipt:$receiptId:saved:receipt_item_categorization",
            priority = SideEffectPriority.NORMAL,
            metadata = SafeEventMetadata.builder()
                .put("receiptId", receiptId.toString())
                .build()
        ) {
            try {
                categorizeReceiptItemsUseCase(receiptId)
                SideEffectOutcome.Completed
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Item categorization failed for receipt %d", receiptId)
                SideEffectOutcome.FailedRetryable(
                    e.message ?: "Item categorization failed",
                    e.javaClass.name
                )
            }
        }
    }

    private fun makeTransactionMatchAction(
        receipt: ScannedReceipt,
        correlationId: String,
        causationId: String?
    ): PostCommitAction {
        val receiptId = receipt.id
        return PostCommitAction(
            pipeline = AppPipeline.RECEIPT,
            name = "receipt_transaction_match",
            category = SideEffectCategory.RECEIPT_MATCHING,
            triggerType = SideEffectTriggerType.RECEIPT_SAVED,
            targetEntityType = "RECEIPT",
            targetEntityId = receiptId,
            source = receipt.sourceType,
            correlationId = correlationId,
            causationId = causationId,
            idempotencyKey = "receipt:$receiptId:saved:receipt_transaction_match",
            priority = SideEffectPriority.NORMAL,
            metadata = SafeEventMetadata.builder()
                .put("receiptId", receiptId.toString())
                .build()
        ) {
            try {
                // Load fresh receipt in case it was modified between planning and execution
                val freshReceipt = scannedReceiptDao.getById(receiptId)
                if (freshReceipt == null) {
                    return@PostCommitAction SideEffectOutcome.Skipped(SideEffectSkipReason.MISSING_ENTITY)
                }

                // PR5: Double-dispatch guard — if the receipt is already explicitly
                // linked to an expense, skip transaction matching to prevent a second
                // link attempt and replay of side effects.
                val linkedExpenseId = freshReceipt.expenseId
                if (linkedExpenseId != null) {
                    Timber.d(
                        "receipt_transaction_match: skipping — receipt %d is already linked to expense %d",
                        receiptId, linkedExpenseId
                    )
                    return@PostCommitAction SideEffectOutcome.Skipped(SideEffectSkipReason.ALREADY_PROCESSED)
                }

                val matchResult = receiptTransactionMatcher.findBestMatch(freshReceipt)
                processMatchResult(matchResult, freshReceipt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Transaction matching failed for receipt %d", receiptId)
                SideEffectOutcome.FailedRetryable(
                    e.message ?: "Transaction matching failed",
                    e.javaClass.name
                )
            }
        }
    }

    /**
     * Processes the [MatchResult] from receipt transaction matching and performs
     * the appropriate action.
     *
     * - **AutoMatch**: Auto-links the receipt to the matched expense.
     * - **Suggested**: Writes a suggestion (updates receipt + MATCH_SUGGESTED event).
     * - **NoMatch**: Writes a durable MATCH_NOT_FOUND event (no longer silent).
     */
    private suspend fun processMatchResult(
        matchResult: MatchResult,
        receipt: ScannedReceipt
    ): SideEffectOutcome {
        return when (matchResult) {
            is MatchResult.AutoMatch -> {
                val linkResult = receiptLinkService.linkReceiptToExpense(
                    receiptId = receipt.id,
                    expenseId = matchResult.transaction.id,
                    linkType = "AUTO_MATCH",
                    source = "RECEIPT_MATCHER",
                    confidence = matchResult.score.toFloat(),
                    matchStatus = MatchStatus.AUTO_MATCHED,
                    writeSourceLink = true
                )
                if (linkResult.isFailure) {
                    Timber.w("Auto-match link failed for receipt %d: %s",
                        receipt.id, linkResult.exceptionOrNull()?.message)
                    SideEffectOutcome.FailedRetryable(
                        linkResult.exceptionOrNull()?.message ?: "Auto-match link failed",
                        linkResult.exceptionOrNull()?.javaClass?.name
                    )
                } else {
                    Timber.d("Auto-matched receipt %d to expense %d (score=%.3f)",
                        receipt.id, matchResult.transaction.id, matchResult.score)
                    SideEffectOutcome.Completed
                }
            }

            is MatchResult.Suggested -> {
                writeBarrier.checkWritesAllowed("ReceiptSideEffectPlanner.suggestedMatch")
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
                Timber.d("Suggested match for receipt %d → expense %d (score=%.3f)",
                    receipt.id, matchResult.transaction.id, matchResult.score)
                SideEffectOutcome.Completed
            }

            is MatchResult.NoMatch -> {
                // PR4: NoMatch is no longer silent — write a durable MATCH_NOT_FOUND event
                writeBarrier.checkWritesAllowed("ReceiptSideEffectPlanner.noMatch")
                val now = timeProvider.now()
                database.withTransaction {
                    receiptEventDao.insert(
                        ReceiptEvent(
                            receiptId = receipt.id,
                            sourceType = receipt.sourceType,
                            documentType = receipt.documentType,
                            eventType = "MATCH_NOT_FOUND",
                            occurredAt = now,
                            oldStatus = receipt.processingStatus,
                            newStatus = null,
                            actor = "system:receipt_matcher",
                            message = "No matching expense found for receipt",
                            metadata = null,
                            errorDetails = null
                        )
                    )
                }
                SideEffectOutcome.Completed
            }
        }
    }

    private fun makePriceProtectionAction(
        receipt: ScannedReceipt,
        correlationId: String,
        causationId: String?
    ): PostCommitAction {
        val capturedReceipt = receipt
        return PostCommitAction(
            pipeline = AppPipeline.RECEIPT,
            name = "price_protection_check",
            category = SideEffectCategory.PRICE_PROTECTION,
            triggerType = SideEffectTriggerType.RECEIPT_SAVED,
            targetEntityType = "RECEIPT",
            targetEntityId = receipt.id,
            source = receipt.sourceType,
            correlationId = correlationId,
            causationId = causationId,
            idempotencyKey = "receipt:${receipt.id}:saved:price_protection_check",
            priority = SideEffectPriority.LOW,
            metadata = SafeEventMetadata.builder()
                .put("receiptId", receipt.id.toString())
                .build()
        ) {
            try {
                priceProtectionTracker.findBetterDeals(capturedReceipt)
                SideEffectOutcome.Completed
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Price protection check failed for receipt %d", receipt.id)
                SideEffectOutcome.FailedRetryable(
                    e.message ?: "Price protection check failed",
                    e.javaClass.name
                )
            }
        }
    }
}
