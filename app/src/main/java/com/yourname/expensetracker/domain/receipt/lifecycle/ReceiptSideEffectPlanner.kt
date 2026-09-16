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
import com.yourname.expensetracker.domain.privacy.RawStorageMode
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

    /**
     * P3-994-09: Privacy-aware overload — the ONLY supported entry point.
     * Raw-dependent side effects use [ReceiptSideEffectInput.ephemeralRawOcrText]
     * instead of silently falling back to persisted/sanitized text.
     */
    fun planAfterReceiptSaved(
        input: ReceiptSideEffectInput,
        causationId: String? = null,
        linkedExpenseIds: Set<Long> = emptySet()
    ): PostCommitActionBatch {
        val corrId = input.correlationId ?: CorrelationIds.newId()
        val docType = parseDocType(input.receipt.documentType)
        val status = parseStatus(input.receipt.processingStatus)

        // Skip for failed / duplicate statuses
        if (status in SKIPPED_STATUSES) {
            Timber.d("planAfterReceiptSaved: skipping side effects for receipt %d (status=%s)", input.receipt.id, status)
            return PostCommitActionBatch.empty(corrId)
        }

        // PR5: When the receipt is already linked to an expense in the same flow,
        // skip transaction matching to prevent double-dispatch.
        val alreadyLinked = linkedExpenseIds.isNotEmpty()

        val actions = when (docType) {
            ReceiptDocumentType.RETAIL_RECEIPT -> listOfNotNull(
                makeWarrantyExtractionAction(input, corrId, causationId),
                makeItemCategorizationAction(input, corrId, causationId),
                // RP-12 12a / P3-001: autoMatchExistingExpense=false omits ONLY the
                // matching action — unrelated actions are not suppressed.
                if (alreadyLinked || !input.autoMatchExistingExpense) null
                else makeTransactionMatchAction(input.receipt, corrId, causationId),
                makePriceProtectionAction(input, corrId, causationId)
            )

            ReceiptDocumentType.EMAIL_RECEIPT -> listOfNotNull(
                makeItemCategorizationAction(input, corrId, causationId)
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
        input: ReceiptSideEffectInput,
        correlationId: String,
        causationId: String?
    ): PostCommitAction {
        val receipt = input.receipt
        val receiptId = receipt.id
        // P3-EB0-06: Policy A — only use ephemeral raw; skip when unavailable.
        val receiptText = if (input.hasEphemeralRaw) {
            input.ephemeralRawOcrText!!
        } else {
            null // no silent fallback to persisted/sanitized text
        }
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
                if (receiptText == null) {
                    writeMatchEvent(receipt, "SIDE_EFFECT_SKIPPED_PRIVACY",
                        "Warranty extraction skipped: ephemeral raw unavailable")
                    return@PostCommitAction SideEffectOutcome.Skipped(
                        com.yourname.expensetracker.domain.sideeffect.SideEffectSkipReason.MISSING_ENTITY)
                }
                writeMatchEvent(receipt, "RAW_USED_EPHEMERALLY",
                    "Warranty extraction used ephemeral raw OCR text")
                autoCreateWarrantyUseCase.execute(receiptId, receiptText)
                SideEffectOutcome.Completed
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Warranty extraction failed for receipt %d", receiptId)
                SideEffectOutcome.FailedRetryable(
                    "warranty_extraction_failed",
                    e::class.simpleName
                )
            }
        }
    }

    private fun makeItemCategorizationAction(
        input: ReceiptSideEffectInput,
        correlationId: String,
        causationId: String?
    ): PostCommitAction {
        val receipt = input.receipt
        val receiptId = receipt.id
        // RP-12 12b (P3-007): item categorization is name-dependent. Only
        // STORE_RAW persists nameable items — redacted items carry prices only,
        // and metadata-only/DO_NOT_STORE persist none. In every other mode the
        // action runs as a CONTROLLED skip (never a silent read of disallowed
        // persisted data and never a retryable failure). The ephemeral
        // pass-through into the use case is the documented remaining P3-007
        // step; until then a fresh insert under a restricted mode skips too.
        val categorizationPermitted = input.rawStorageMode == RawStorageMode.STORE_RAW
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
                if (!categorizationPermitted) {
                    writeMatchEvent(receipt, "SIDE_EFFECT_SKIPPED_PRIVACY",
                        "Item categorization skipped: structured receipt data unavailable for storage mode")
                    return@PostCommitAction SideEffectOutcome.Skipped(
                        com.yourname.expensetracker.domain.sideeffect.SideEffectSkipReason.STRUCTURED_RECEIPT_DATA_UNAVAILABLE)
                }
                categorizeReceiptItemsUseCase(receiptId)
                SideEffectOutcome.Completed
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Item categorization failed for receipt %d", receiptId)
                SideEffectOutcome.FailedRetryable(
                    "receipt_item_categorization_failed",
                    e::class.simpleName
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
                    // P3-P1-03: Write MATCH_SKIPPED_ALREADY_LINKED before returning
                    writeMatchEvent(freshReceipt, "MATCH_SKIPPED_ALREADY_LINKED",
                        "Receipt already linked to expense $linkedExpenseId",
                        expenseId = linkedExpenseId)
                    return@PostCommitAction SideEffectOutcome.Skipped(SideEffectSkipReason.ALREADY_PROCESSED)
                }

                // P3-P1-03: Write MATCH_ATTEMPTED before running the matcher
                writeMatchEvent(freshReceipt, "MATCH_ATTEMPTED",
                    "Starting transaction match for receipt")

                val matchResult = receiptTransactionMatcher.findBestMatch(freshReceipt)
                processMatchResult(matchResult, freshReceipt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Transaction matching failed for receipt %d", receiptId)
                // P3-P1-03: Write MATCH_FAILED before returning retryable failure
                val freshReceipt = scannedReceiptDao.getById(receiptId)
                if (freshReceipt != null) {
                    writeMatchEvent(freshReceipt, "MATCH_FAILED",
                        "Transaction matching threw exception",
                        errorDetails = "transaction_matching_exception")
                }
                SideEffectOutcome.FailedRetryable(
                    "receipt_transaction_match_failed",
                    e::class.simpleName
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
                    writeSourceLink = true,
                    // RP-12 12a / P3-009: idempotent auto-match — use the link
                    // service's atomic unmatched-state CAS (same gate as
                    // ReceiptMatchingWorker) so an already linked, suggested,
                    // rejected, or concurrently claimed receipt is never
                    // overwritten by a stale full-row update.
                    requireUnmatchedClaim = true
                )
                if (linkResult.isFailure) {
                    val linkError = linkResult.exceptionOrNull()
                    if (linkError is ReceiptAlreadyClaimedException) {
                        // P3-009: a concurrent matching run already claimed this
                        // receipt. Controlled skipped outcome — never an error,
                        // never an overwrite (mirrors ReceiptMatchingWorker).
                        Timber.d("Auto-match skipped for receipt %d: already claimed by a concurrent matching run", receipt.id)
                        SideEffectOutcome.Skipped(SideEffectSkipReason.ALREADY_PROCESSED)
                    } else {
                        Timber.w("Auto-match link failed for receipt %d: %s",
                            receipt.id, linkError?.message)
                        writeMatchEvent(receipt, "MATCH_FAILED",
                            "Auto-match link failed",
                            expenseId = matchResult.transaction.id, score = matchResult.score.toFloat(),
                            errorDetails = "auto_match_link_failed")
                        SideEffectOutcome.FailedRetryable(
                            "auto_match_link_failed",
                            linkError?.let { it::class.simpleName }
                        )
                    }
                } else {
                    Timber.d("Auto-matched receipt %d to expense %d (score=%.3f)",
                        receipt.id, matchResult.transaction.id, matchResult.score)
                    writeMatchEvent(receipt, "AUTO_MATCHED",
                        "Auto-matched receipt to expense ${matchResult.transaction.id} (score=${
                            "%.3f".format(matchResult.score)})",
                        expenseId = matchResult.transaction.id, score = matchResult.score.toFloat())
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
        input: ReceiptSideEffectInput,
        correlationId: String,
        causationId: String?
    ): PostCommitAction {
        val capturedReceipt = input.receipt
        // RP-12 12b (P3-007): price protection reads persisted line items. Only
        // STORE_RAW may feed it; restricted modes produce the controlled skip.
        val priceCheckPermitted = input.rawStorageMode == RawStorageMode.STORE_RAW
        return PostCommitAction(
            pipeline = AppPipeline.RECEIPT,
            name = "price_protection_check",
            category = SideEffectCategory.PRICE_PROTECTION,
            triggerType = SideEffectTriggerType.RECEIPT_SAVED,
            targetEntityType = "RECEIPT",
            targetEntityId = capturedReceipt.id,
            source = capturedReceipt.sourceType,
            correlationId = correlationId,
            causationId = causationId,
            idempotencyKey = "receipt:${capturedReceipt.id}:saved:price_protection_check",
            priority = SideEffectPriority.LOW,
            metadata = SafeEventMetadata.builder()
                .put("receiptId", capturedReceipt.id.toString())
                .build()
        ) {
            try {
                if (!priceCheckPermitted) {
                    SideEffectOutcome.Skipped(
                        com.yourname.expensetracker.domain.sideeffect.SideEffectSkipReason.STRUCTURED_RECEIPT_DATA_UNAVAILABLE)
                } else {
                    priceProtectionTracker.findBetterDeals(capturedReceipt)
                    SideEffectOutcome.Completed
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Price protection check failed for receipt %d", capturedReceipt.id)
                SideEffectOutcome.FailedRetryable(
                    "price_protection_check_failed",
                    e::class.simpleName
                )
            }
        }
    }

    /**
     * Writes a receipt matching lifecycle event.
     *
     * Metadata never includes raw OCR text — only IDs, scores, and safe reasons.
     * P3-P1-03: Matching event taxonomy.
     */
    private suspend fun writeMatchEvent(
        receipt: ScannedReceipt,
        eventType: String,
        message: String,
        expenseId: Long? = null,
        score: Float? = null,
        errorDetails: String? = null
    ) {
        try {
            writeBarrier.checkWritesAllowed("ReceiptSideEffectPlanner.writeMatchEvent")
            val now = timeProvider.now()
            val metadataFields = mutableListOf<String>()
            expenseId?.let { metadataFields.add("\"expenseId\":$it") }
            score?.let { metadataFields.add("\"score\":${"%.3f".format(it)}") }
            receiptEventDao.insert(
                ReceiptEvent(
                    receiptId = receipt.id,
                    sourceType = receipt.sourceType,
                    documentType = receipt.documentType,
                    eventType = eventType,
                    occurredAt = now,
                    oldStatus = receipt.processingStatus,
                    newStatus = null,
                    actor = "system:receipt_matcher",
                    message = message.take(500),
                    metadata = if (metadataFields.isNotEmpty()) "{${metadataFields.joinToString(",")}}" else null,
                    errorDetails = errorDetails?.take(500)
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to write match event %s for receipt %d", eventType, receipt.id)
        }
    }
}
