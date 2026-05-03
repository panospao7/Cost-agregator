package com.yourname.expensetracker.domain.receipt.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.ReturnWindowDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.dao.WarrantyDao
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
    private val database: AppDatabase,
    private val receiptExpenseLinkDao: ReceiptExpenseLinkDao,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val receiptEventDao: ReceiptEventDao,
    private val warrantyDao: WarrantyDao,
    private val returnWindowDao: ReturnWindowDao,
    private val timeProvider: TimeProvider
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
     * @return [Result.success] with the link on success, [Result.failure] on error.
     */
    suspend fun linkReceiptToExpense(
        receiptId: Long,
        expenseId: Long,
        linkType: String,
        source: String,
        createdBy: String? = null,
        confidence: Float? = null,
        allowRelink: Boolean = false
    ): Result<ReceiptExpenseLink> {
        // 1. Load receipt — fail fast if not found
        val receipt = scannedReceiptDao.getById(receiptId)
            ?: return Result.failure(
                IllegalArgumentException("Receipt not found: $receiptId")
            )

        val isBankStatement =
            receipt.documentType == ReceiptDocumentType.BANK_STATEMENT.name

        val now = timeProvider.now()

        // 2. Insert + legacy update + event inside a single database transaction
        //    The existing-links check is inside the transaction to prevent race conditions.
        return database.withTransaction {
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
            receiptExpenseLinkDao.insert(link)

            // 4. For non-BANK_STATEMENT receipts: update legacy ScannedReceipt.expenseId
            // RCP-8: Ensure updatedAt is set on every ScannedReceipt update.
            if (!isBankStatement) {
                scannedReceiptDao.update(receipt.copy(expenseId = expenseId, updatedAt = now))
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
                    message = "Receipt linked to expense $expenseId (type=$linkType, source=$source). Warranty/return expenseId propagated.",
                    metadata = null,
                    errorDetails = null
                )
            )

            // 6. Return the link
            Result.success(link)
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
        return try {
            // Load receipt for metadata (may be null if already deleted)
            val receipt = scannedReceiptDao.getById(receiptId)
            val now = timeProvider.now()

            val isBankStatement =
                receipt?.documentType == ReceiptDocumentType.BANK_STATEMENT.name

            // All operations inside a single database transaction
            database.withTransaction {
                // 1. Delete link row
                receiptExpenseLinkDao.unlink(receiptId, expenseId)

                // 2. Determine correct ScannedReceipt.expenseId after unlinking
                // RCP-8: Ensure updatedAt is set on every ScannedReceipt update.
                if (!isBankStatement && receipt != null) {
                    val remainingLinks = receiptExpenseLinkDao.getLinksForReceipt(receiptId)
                    val primaryLinks = remainingLinks.filter { it.isPrimary }

                    if (primaryLinks.isEmpty()) {
                        // No remaining primary links — clear legacy field
                        scannedReceiptDao.update(receipt.copy(expenseId = null, updatedAt = now))
                    } else {
                        // Another primary link exists — point to its expenseId
                        scannedReceiptDao.update(
                            receipt.copy(expenseId = primaryLinks.first().expenseId, updatedAt = now)
                        )
                    }
                }

                // WRN-N1: After unlinking the receipt from the expense, also clear
                // the expenseId on any associated Warranty and ReturnWindow records
                // so they don't retain stale references to the now-unlinked expense.
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

                // 3. Write lifecycle event
                val sourceType = receipt?.sourceType ?: "UNKNOWN"
                val documentType = receipt?.documentType ?: "UNKNOWN"
                receiptEventDao.insert(
                    ReceiptEvent(
                        receiptId = receiptId,
                        sourceType = sourceType,
                        documentType = documentType,
                        eventType = "RECEIPT_UNLINKED_FROM_EXPENSE",
                        occurredAt = now,
                        oldStatus = null,
                        newStatus = null,
                        actor = "system",
                        message = "Receipt unlinked from expense $expenseId. Warranty/return expenseId cleared.",
                        metadata = null,
                        errorDetails = null
                    )
                )
            }

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
