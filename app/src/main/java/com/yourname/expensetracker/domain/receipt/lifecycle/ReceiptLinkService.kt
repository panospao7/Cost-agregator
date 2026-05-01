package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.ReceiptExpenseLink
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

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
 *
 * All receipt-expense link mutations in the application MUST go through this
 * service to ensure consistency between the join table, legacy fields, and
 * event history.
 */
@Singleton
class ReceiptLinkService @Inject constructor(
    private val receiptExpenseLinkDao: ReceiptExpenseLinkDao,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val receiptEventDao: ReceiptEventDao,
    private val timeProvider: TimeProvider
) {

    /**
     * Creates a link between a receipt and an expense.
     *
     * Logic:
     * 1. Loads the receipt via [ScannedReceiptDao.getById] — returns error if not found.
     * 2. For BANK_STATEMENT receipts, multiple links per receipt are allowed.
     *    For all other document types, the UNIQUE constraint on (receiptId, expenseId)
     *    with REPLACE strategy ensures a single link per receipt-expense pair.
     * 3. Inserts a [ReceiptExpenseLink] row.
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
     * @return [Result.success] with the link on success, [Result.failure] on error.
     */
    suspend fun linkReceiptToExpense(
        receiptId: Long,
        expenseId: Long,
        linkType: String,
        source: String,
        createdBy: String? = null,
        confidence: Float? = null
    ): Result<ReceiptExpenseLink> {
        // 1. Load receipt — fail fast if not found
        val receipt = scannedReceiptDao.getById(receiptId)
            ?: return Result.failure(
                IllegalArgumentException("Receipt not found: $receiptId")
            )

        val isBankStatement =
            receipt.documentType == ReceiptDocumentType.BANK_STATEMENT.name

        val now = timeProvider.now()

        // 3. Insert ReceiptExpenseLink with REPLACE strategy
        //    For non-BANK_STATEMENT receipts, the UNIQUE constraint on
        //    (receiptId, expenseId) combined with OnConflictStrategy.REPLACE
        //    ensures only one link per pair exists.
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
        receiptExpenseLinkDao.insert(link)

        // 4. For non-BANK_STATEMENT receipts: update legacy ScannedReceipt.expenseId
        if (!isBankStatement) {
            scannedReceiptDao.update(receipt.copy(expenseId = expenseId))
        }

        // 5. Write lifecycle event
        receiptEventDao.insert(
            ReceiptEvent(
                receiptId = receiptId,
                sourceType = receipt.sourceType,
                documentType = receipt.documentType,
                eventType = "RECEIPT_LINKED_TO_EXPENSE",
                occurredAt = now,
                oldStatus = null,
                newStatus = null,
                actor = createdBy ?: "system",
                message = "Receipt linked to expense $expenseId (type=$linkType, source=$source)",
                metadata = null,
                errorDetails = null
            )
        )

        // 6. Return the link
        return Result.success(link)
    }

    /**
     * Removes a link between a receipt and an expense.
     *
     * Logic:
     * 1. Deletes the link row via [ReceiptExpenseLinkDao.unlink].
     * 2. For non-BANK_STATEMENT receipts, clears the legacy [ScannedReceipt.expenseId] field.
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
        return try {
            // Load receipt for metadata (may be null if already deleted)
            val receipt = scannedReceiptDao.getById(receiptId)

            // 1. Delete link row
            receiptExpenseLinkDao.unlink(receiptId, expenseId)

            val isBankStatement =
                receipt?.documentType == ReceiptDocumentType.BANK_STATEMENT.name

            // 2. For non-BANK_STATEMENT: clear legacy ScannedReceipt.expenseId
            if (!isBankStatement && receipt != null) {
                scannedReceiptDao.update(receipt.copy(expenseId = null))
            }

            // 3. Write lifecycle event
            val sourceType = receipt?.sourceType ?: "UNKNOWN"
            val documentType = receipt?.documentType ?: "UNKNOWN"
            receiptEventDao.insert(
                ReceiptEvent(
                    receiptId = receiptId,
                    sourceType = sourceType,
                    documentType = documentType,
                    eventType = "RECEIPT_UNLINKED_FROM_EXPENSE",
                    occurredAt = timeProvider.now(),
                    oldStatus = null,
                    newStatus = null,
                    actor = "system",
                    message = "Receipt unlinked from expense $expenseId",
                    metadata = null,
                    errorDetails = null
                )
            )

            Result.success(Unit)
        } catch (e: Exception) {
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
}
