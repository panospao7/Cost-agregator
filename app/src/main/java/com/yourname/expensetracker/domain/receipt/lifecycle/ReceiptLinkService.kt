package com.yourname.expensetracker.domain.receipt.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.transaction.ExpenseCategoryAssignmentPort
import com.yourname.expensetracker.domain.transaction.CategoryAssignmentOutcome
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ReceiptItemCategorizationDao
import com.yourname.expensetracker.data.database.dao.ReturnWindowDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.dao.WarrantyDao
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ReceiptExpenseLink
import com.yourname.expensetracker.domain.provenance.ReceiptSourceLinkPayloadFactory
import com.yourname.expensetracker.domain.provenance.SourceLinkWriter
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S6 (P9-P1-07 / NEW-07): Signals that an auto-match link was abandoned because
 * a concurrent matching run had already claimed (resolved) the receipt by the
 * time the atomic compare-and-set ran. This is NOT an error — the receipt was
 * legitimately handled elsewhere — so callers should skip notifications/side
 * effects rather than recording an AUTO_MATCH_LINK_FAILED diagnostics event.
 *
 * Only produced when [ReceiptLinkService.linkReceiptToExpense] is called with
 * `requireUnmatchedClaim = true` and the conditional claim affects 0 rows.
 */
class ReceiptAlreadyClaimedException(receiptId: Long) : IllegalStateException(
    "Receipt $receiptId was already claimed/resolved by a concurrent matching run"
)

/**
 * Central service responsible for linking receipts to expenses and managing
 * the lifecycle of receipt-expense associations.
 *
 * This service is the single owner of receipt-expense linking. It handles:
 * - Creating links between receipts and expenses
 * - Removing links (unlinking)
 * - Querying links by receipt or expense
 * - Maintaining backward compatibility with legacy [ScannedReceipt.expenseId]
 * - Writing audit trail events for every link/unlink operation
 * - Propagating expenseId to receipt item categorizations (RCP-6)
 *
 * All receipt-expense link mutations in the application MUST go through this
 * service to ensure consistency between the join table, legacy fields, and
 * event history.
 *
 * ## RCP-30: Item categorization → budget/expense pipeline
 *
 * ### Current gap
 * Receipt item categorizations are siloed in the receipt module. Although
 * [ReceiptItemCategorization.expenseId] is populated during linking (RCP-6),
 * the categorized data does **not** propagate to the expense's `categoryId`
 * or to budget calculations. This means:
 * - An expense created from a receipt with categorised items still uses
 *   whatever `categoryId` was set at creation time (often null or generic).
 * - Budget rollups that sum by `expense.categoryId` miss the item-level
 *   categorizations.
 * - The user must manually correct the expense category even after the AI
 *   has already categorised individual line items.
 *
 * ### Integration path
 * 1. **When receipt items are categorised** (via `CategorizeReceiptItemsUseCase`),
 *    each [ReceiptItemCategorization] is persisted with a `suggestedCategoryId`.
 *    The receipt's own `suggestedCategoryId` may be set to the most frequent
 *    or highest-confidence category across its items.
 * 2. **When the receipt is linked to an expense** (here, in [linkReceiptToExpense]),
 *    link-time code should query the receipt's categorisations (via
 *    [ReceiptItemCategorizationDao.getByReceiptId]) and determine the
 *    majority / highest-confidence category. The derived category can then
 *    be propagated:
 *    - *Option A:* Set `expense.categoryId` to the majority item category.
 *      This is simplest but loses multi-category granularity.
 *    - *Option B:* Create sub-allocations (split the expense amount across
 *      multiple budget categories proportionally to item amounts). This
 *      requires a new sub-allocation table and budget engine support.
 * 3. **Budget calculation updates:** The budget engine should aggregate
 *    item-level categorizations rather than (or in addition to) the top-level
 *    `expense.categoryId`. This may involve joining
 *    `receipt_item_categorizations` in budget queries or writing item-level
 *    budget allocations at link time.
 *
 * ### Current status
 * Step 1 is fully implemented (categorizations persisted with `suggestedCategoryId`).
 * Step 2 currently only propagates `expenseId` (RCP-6) but does **not** update
 * `expense.categoryId`. A `Timber.d` log at link time shows what categories
 * *would* be propagated. Full propagation (Option A or B) is deferred to a
 * future change.
 */
@Singleton
class ReceiptLinkService @Inject constructor(
    private val database: AppDatabase,
    private val receiptExpenseLinkDao: ReceiptExpenseLinkDao,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val receiptLifecycleEventWriter: ReceiptLifecycleEventWriter,
    private val receiptItemCategorizationDao: ReceiptItemCategorizationDao,
    private val warrantyDao: WarrantyDao,
    private val returnWindowDao: ReturnWindowDao,
    private val expenseDao: ExpenseDao,
    private val timeProvider: TimeProvider,
    private val writeBarrier: DatabaseWriteBarrier,
    private val sourceLinkWriter: SourceLinkWriter,
    private val categoryAssignmentPort: ExpenseCategoryAssignmentPort
) {

    /**
     * Creates a link between a receipt and an expense.
     *
     * Logic:
     * 1. Loads the receipt via [ScannedReceiptDao.getById] — returns error if not found.
     * 2. For BANK_STATEMENT receipts, multiple links per receipt are allowed.
     *    For all other document types, checks existing links and prevents relinking
     *    unless [allowRelink] is explicitly set to true.
     * 3. Inserts a [ReceiptExpenseLink] row inside a database transaction.
     * 4. For non-BANK_STATEMENT receipts, updates the legacy [ScannedReceipt.expenseId]
     *    field for backward compatibility.
     * 5. Writes a [ReceiptEvent] with eventType "RECEIPT_LINKED_TO_EXPENSE".
     * 6. Returns the created [ReceiptExpenseLink].
     *
     * @param receiptId The ID of the receipt to link.
     * @param expenseId The ID of the expense to link.
     * @param linkType  The type of link (e.g. "DIRECT_SAVE", "REVIEW_APPROVAL", "AUTO_MATCH").
     * @param source    The source system/component that created the link (ExpenseSource name).
     * @param createdBy Optional identifier of who/what created the link.
     * @param confidence Optional confidence score of the match (0.0 to 1.0).
     * @param allowRelink If true, allows relinking an already-linked non-BANK_STATEMENT receipt.
     * @param requireUnmatchedClaim S6 (P9-P1-07 / NEW-07): when true, the receipt's
     *   match-status transition is performed as an atomic compare-and-set
     *   ([ScannedReceiptDao.claimForAutoMatch]) that only succeeds while the
     *   receipt is still UNMATCHED or SUGGESTED. If a concurrent matching run has
     *   already resolved the receipt, the claim affects 0 rows and the whole
     *   operation is rolled back and returned as
     *   [Result.failure] wrapping a [ReceiptAlreadyClaimedException]. Intended for
     *   the AUTO_MATCH path of the matching worker so two overlapping runs cannot
     *   both auto-link the same receipt. Ignored for BANK_STATEMENT receipts
     *   (which do not transition match status here).
     * @return [Result.success] with the link on success, [Result.failure] on error.
     */
    suspend fun linkReceiptToExpense(
        receiptId: Long,
        expenseId: Long,
        linkType: String,
        source: String,
        createdBy: String? = null,
        confidence: Float? = null,
        allowRelink: Boolean = false,
        matchStatus: MatchStatus? = null,
        writeSourceLink: Boolean = true,
        requireUnmatchedClaim: Boolean = false
    ): Result<ReceiptExpenseLink> {
        // Guard: block writes during restore maintenance mode
        try {
            writeBarrier.checkWritesAllowed("ReceiptLinkService.linkReceiptToExpense")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return Result.failure(e)
        }

        // 1. Load receipt — fail fast if not found
        val receipt = scannedReceiptDao.getById(receiptId)
            ?: return Result.failure(
                IllegalArgumentException("Receipt not found: $receiptId")
            )

        // 1b. Validate expense exists — fail fast if not found
        val expense = expenseDao.getById(expenseId)
            ?: return Result.failure(
                IllegalArgumentException("Expense not found: $expenseId")
            )

        val isBankStatement =
            receipt.documentType == ReceiptDocumentType.BANK_STATEMENT.name

        val now = timeProvider.now()

        // 2. Insert + legacy update + event inside a single database transaction
        //    The existing-links check is inside the transaction to prevent race conditions.
        //    S6: when requireUnmatchedClaim is set, the receipt status transition is an
        //    atomic compare-and-set; if a concurrent run already claimed the receipt the
        //    claim affects 0 rows and we throw ReceiptAlreadyClaimedException to roll back
        //    the just-inserted link, then convert it to a Result.failure below.
        return try {
            database.withTransaction {
            // For non-BANK_STATEMENT receipts: check if already linked (inside transaction)
            if (!isBankStatement && !allowRelink) {
                val existingLinks = receiptExpenseLinkDao.getLinksForReceipt(receiptId)
                if (existingLinks.isNotEmpty()) {
                    return@withTransaction Result.failure(
                        IllegalStateException(
                            "Receipt $receiptId is already linked to expense(s). " +
                            "Set allowRelink=true to force a new link."
                        )
                    )
                }
            }
            val link = ReceiptExpenseLink(
                receiptId = receiptId,
                expenseId = expenseId,
                linkType = linkType,
                confidence = confidence,
                source = source,
                createdAt = now,
                createdBy = createdBy,
                isPrimary = true,
                metadata = null
            )
            val linkId = receiptExpenseLinkDao.insert(link)
            if (linkId <= 0) {
                return@withTransaction Result.failure(
                    IllegalStateException(
                        "Duplicate link: receipt $receiptId is already linked to expense $expenseId"
                    )
                )
            }

            // 4. For non-BANK_STATEMENT receipts: update legacy ScannedReceipt.expenseId
            // RCP-8: Ensure updatedAt is set on every ScannedReceipt update.
            // RCP-22: Clear suggestedExpenseId to prevent stale suggestion reuse.
            val resolvedMatchStatus = matchStatus ?: when (linkType) {
                "AUTO_MATCH" -> MatchStatus.AUTO_MATCHED
                "DIRECT_SAVE", "REVIEW_APPROVAL" -> MatchStatus.MANUALLY_MATCHED
                else -> MatchStatus.MANUALLY_MATCHED
            }
            if (!isBankStatement) {
                if (requireUnmatchedClaim) {
                    // S6 (P9-P1-07 / NEW-07): atomic compare-and-set. Only transition
                    // the receipt while it is still UNMATCHED/SUGGESTED. If a concurrent
                    // matching run already resolved it, claimed == 0 → throw to roll back
                    // the link we just inserted (no double auto-link, no stale link row).
                    val claimed = scannedReceiptDao.claimForAutoMatch(
                        receiptId = receiptId,
                        expenseId = expenseId,
                        confidence = confidence,
                        now = now
                    )
                    if (claimed == 0) {
                        throw ReceiptAlreadyClaimedException(receiptId)
                    }
                } else {
                    scannedReceiptDao.update(
                        receipt.copy(
                            expenseId = expenseId,
                            suggestedExpenseId = null,
                            matchStatus = resolvedMatchStatus,
                            matchConfidence = confidence,
                            updatedAt = now
                        )
                    )
                }
            }

            // I1: Propagate expenseId to warranty and return window for this receipt
            warrantyDao.updateExpenseIdByReceiptId(
                receiptId = receiptId,
                expenseId = expenseId,
                updatedAt = now
            )
            returnWindowDao.updateExpenseIdByReceiptId(
                receiptId = receiptId,
                expenseId = expenseId,
                updatedAt = now
            )

            // RCP-6: Propagate expenseId to receipt item categorizations so
            // queries by expenseId (e.g. category totals per expense) work.
            receiptItemCategorizationDao.linkToExpense(
                receiptId = receiptId,
                expenseId = expenseId,
                timestamp = now
            )

            // RCP-30: Propagate item majority category to expense if the expense
            // currently has no categoryId. Failures are logged but do not break linking.
            runCatching {
                val categorizations = receiptItemCategorizationDao.getByReceiptId(receiptId)
                if (categorizations.isNotEmpty()) {
                    val bestCategoryId = categorizations
                        .groupBy { it.userCorrectedCategoryId ?: it.suggestedCategoryId }
                        .maxByOrNull { (_, items) -> items.size }
                        ?.key
                    if (bestCategoryId != null) {
                        val existingExpense = expenseDao.getById(expenseId)
                        if (existingExpense != null && existingExpense.categoryId == null) {
                            categoryAssignmentPort.assignCategoryIfUnset(
                                expenseId = expenseId,
                                categoryId = bestCategoryId,
                                source = "RECEIPT_ITEM_MAJORITY"
                            )
                            Timber.d("RCP-30: Category %d propagated to expense %d via port", bestCategoryId, expenseId)
                        }
                    }
                    val categoryFrequencies = categorizations
                        .groupBy { it.userCorrectedCategoryId ?: it.suggestedCategoryId }
                        .mapValues { (_, items) -> items.size }
                        .entries
                        .sortedByDescending { it.value }
                        .joinToString(", ") { (catId, count) ->
                            "categoryId=$catId ($count item(s))"
                        }
                    Timber.d(
                        "RCP-30: Receipt %d linked to expense %d. Item categorizations: [%s].",
                        receiptId, expenseId, categoryFrequencies
                    )
                }
            }.onFailure { error ->
                Timber.w(error, "RCP-30: Failed to propagate item category for receipt %d", receiptId)
            }

            // 5. Write lifecycle event
            receiptLifecycleEventWriter.write(
                TransactionContext(
                    correlationId = java.util.UUID.randomUUID().toString(),
                    occurredAt = System.currentTimeMillis()
                ),
                ReceiptLifecycleEvent(
                    receiptId = receiptId,
                    sourceType = receipt.sourceType,
                    documentType = receipt.documentType,
                    eventType = "RECEIPT_LINKED_TO_EXPENSE",
                    actor = createdBy ?: "system",
                    message = "Receipt linked to expense $expenseId (type=$linkType, source=$source). Warranty/return expenseId propagated."
                )
            )

            // PR4: Write source link for provenance when enabled
            if (writeSourceLink) {
                val payload = ReceiptSourceLinkPayloadFactory.forScannedReceiptToExpense(receiptId, linkType)
                sourceLinkWriter.linkExpense(expenseId, payload, null)
            }

            // 6. Return the link with actual DB-generated ID
            Result.success(link.copy(id = linkId))
            }
        } catch (e: ReceiptAlreadyClaimedException) {
            // S6: concurrent run already resolved the receipt; the inserted link
            // was rolled back with the transaction. Surface as a failure the worker
            // can recognise (and treat as a no-op) rather than a false success.
            Result.failure(e)
        }
    }

    /**
     * Removes a link between a receipt and an expense.
     *
     * Logic:
     * 1. Deletes the link row via [ReceiptExpenseLinkDao.unlink] inside a transaction.
     * 2. Checks remaining links. For non-BANK_STATEMENT receipts:
     *    - If other primary non-BANK_STATEMENT links remain, sets [ScannedReceipt.expenseId]
     *      to the first remaining link's expenseId.
     *    - If no primary links remain, clears [ScannedReceipt.expenseId] to null.
     * 3. Writes a [ReceiptEvent] with eventType "RECEIPT_UNLINKED_FROM_EXPENSE".
     *
     * @param receiptId The ID of the receipt to unlink.
     * @param expenseId The ID of the expense to unlink.
     * @return [Result.success] on success, [Result.failure] on error.
     */
    suspend fun unlinkReceiptFromExpense(
        receiptId: Long,
        expenseId: Long
    ): Result<Unit> {
        // Guard: block writes during restore maintenance mode
        try {
            writeBarrier.checkWritesAllowed("ReceiptLinkService.unlinkReceiptFromExpense")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return Result.failure(e)
        }

        return try {
            // Load receipt for metadata (may be null if already deleted)
            val receipt = scannedReceiptDao.getById(receiptId)
            val now = timeProvider.now()

            val isBankStatement =
                receipt?.documentType == ReceiptDocumentType.BANK_STATEMENT.name

            var affectedRows = 0
            // All operations inside a single database transaction
            database.withTransaction {
                // 1. Delete link row and capture affected row count
                affectedRows = receiptExpenseLinkDao.unlink(receiptId, expenseId)

                // P3-NEW-09 / follow-up: No-op guard. If no link existed, do NOT
                // clear receipt/warranty/return/item state — those mutations would
                // be misleading because no link was actually removed.
                if (affectedRows == 0) {
                    return@withTransaction
                }

                // 2. Determine correct ScannedReceipt.expenseId after unlinking
                if (!isBankStatement && receipt != null) {
                    val remainingLinks = receiptExpenseLinkDao.getLinksForReceipt(receiptId)
                    val primaryLinks = remainingLinks.filter { it.isPrimary }

                    if (primaryLinks.isEmpty()) {
                        scannedReceiptDao.update(receipt.copy(
                            expenseId = null,
                            matchStatus = MatchStatus.UNMATCHED,
                            matchConfidence = null,
                            suggestedExpenseId = null,
                            updatedAt = now
                        ))
                    } else {
                        scannedReceiptDao.update(
                            receipt.copy(expenseId = primaryLinks.first().expenseId, updatedAt = now)
                        )
                    }
                }

                // Clear expenseId on warranties and return windows
                warrantyDao.updateExpenseIdByReceiptId(
                    receiptId = receiptId,
                    expenseId = null,
                    updatedAt = now
                )
                returnWindowDao.updateExpenseIdByReceiptId(
                    receiptId = receiptId,
                    expenseId = null,
                    updatedAt = now
                )

                receiptItemCategorizationDao.clearExpenseId(
                    receiptId = receiptId,
                    timestamp = now
                )

                // P3-NEW-09: Only write success event when a row was actually deleted.
                // A no-op unlink should NOT produce a misleading success event.
                if (affectedRows > 0) {
                    val sourceType = receipt?.sourceType ?: "UNKNOWN"
                    val documentType = receipt?.documentType ?: "UNKNOWN"
                    receiptLifecycleEventWriter.write(
                        TransactionContext(
                            correlationId = java.util.UUID.randomUUID().toString(),
                            occurredAt = System.currentTimeMillis()
                        ),
                        ReceiptLifecycleEvent(
                            receiptId = receiptId,
                            sourceType = sourceType,
                            documentType = documentType,
                            eventType = "RECEIPT_UNLINKED_FROM_EXPENSE",
                            actor = "system",
                            message = "Receipt unlinked from expense $expenseId. Warranty/return expenseId cleared."
                        )
                    )
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Returns all links associated with the given receipt.
     *
     * @param receiptId The ID of the receipt.
     * @return A list of [ReceiptExpenseLink] entries (empty if none).
     */
    suspend fun getLinksForReceipt(receiptId: Long): List<ReceiptExpenseLink> {
        return receiptExpenseLinkDao.getLinksForReceipt(receiptId)
    }

    /**
     * Returns all links associated with the given expense.
     *
     * @param expenseId The ID of the expense.
     * @return A list of [ReceiptExpenseLink] entries (empty if none).
     */
    suspend fun getLinksForExpense(expenseId: Long): List<ReceiptExpenseLink> {
        return receiptExpenseLinkDao.getLinksForExpense(expenseId)
    }

    /**
     * S7-66F-006: Non-mutating linkability check — performs no writes.
     *
     * Checks both the join table and the legacy [ScannedReceipt.expenseId] field.
     * Use this before creating an expense to avoid orphan expenses.
     *
     * @return true if the receipt can be linked (not already linked), false otherwise.
     *         Returns false if the receipt does not exist.
     */
    suspend fun checkCanLinkReceipt(receiptId: Long, allowRelink: Boolean = false): Boolean {
        val receipt = scannedReceiptDao.getById(receiptId) ?: return false
        if (!allowRelink) {
            // Check join table
            val existingLinks = receiptExpenseLinkDao.getLinksForReceipt(receiptId)
            if (existingLinks.isNotEmpty()) return false
            // Check legacy expenseId
            val isBankStatement = receipt.documentType == com.yourname.expensetracker.domain.receipt.ReceiptDocumentType.BANK_STATEMENT.name
            if (!isBankStatement && receipt.expenseId != null) return false
        }
        return true
    }
}
