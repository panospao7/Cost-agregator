package com.yourname.expensetracker.domain.transaction.lifecycle

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central coordinator for the expense transaction lifecycle.
 *
 * This coordinator is the single entry point for creating expenses through any path
 * (manual entry, notification auto-accept, CSV import, bank API sync, etc.). It
 * orchestrates the full lifecycle:
 *
 *   validate → normalize → dedupe → insert atomic → event logging → side effects
 *
 * As each existing creation path is migrated (PRs 2-10), its logic is moved into
 * this class and the old path is removed.
 *
 * @constructor Inject dependencies needed for validation, persistence, and event logging.
 */
@Singleton
class TransactionLifecycleCoordinator @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val transactionEventDao: TransactionEventDao,
    private val timeProvider: TimeProvider,
    private val sideEffectDispatcher: TransactionSideEffectDispatcher,
    private val recurringLifecycleCoordinator: RecurringLifecycleCoordinator
) {
    /**
     * Creates an expense with full lifecycle handling:
     * validate → normalize → dedupe → insert atomic → event.
     *
     * @param request The creation request containing all expense fields and policy controls.
     * @return A [CreateExpenseResult] indicating the outcome (created, duplicate, error, etc.).
     */
    suspend fun createExpense(request: CreateExpenseRequest): CreateExpenseResult {
        val now = timeProvider.now()

        // 1. Validate
        val errors = validate(request)
        if (errors.isNotEmpty()) {
            return CreateExpenseResult.ValidationFailed(errors)
        }

        // 2. Generate merchant key and dedupe key
        val merchantKey = MerchantKeyGenerator.generate(request.merchant)
        val dedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount = request.amount,
            merchant = request.merchant,
            date = request.date,
            currency = request.currency,
            transactionType = request.transactionType
        )

        // 3. Build Expense entity
        val expense = Expense(
            amount = request.amount,
            currency = request.currency,
            merchant = request.merchant,
            merchantKey = merchantKey,
            transactionType = request.transactionType,
            date = request.date,
            categoryId = request.categoryId,
            notes = request.notes,
            paymentMethod = request.paymentMethod ?: PaymentMethod.UNKNOWN,
            isManualEntry = request.isManualEntry,
            createdAt = now,
            dedupeKey = dedupeKey,
            transferDirection = request.transferDirection,
            transferAccountName = request.transferAccountName,
            isNotMine = request.isNotMine,
            ownerName = request.ownerName,
            isSharedExpense = request.isSharedExpense,
            sharedWithName = request.sharedWithName,
            mySharePercentage = request.mySharePercentage,
            myShareAmount = request.myShareAmount,
            latitude = request.latitude,
            longitude = request.longitude,
            locationSource = request.locationSource,
            placeId = request.placeId,
            resolvedAddress = request.resolvedAddress,
            isBusinessExpense = request.isBusinessExpense,
            businessPurpose = request.businessPurpose,
            businessCategory = request.businessCategory,
            businessProject = request.businessProject,
            requiresReceipt = request.requiresReceipt,
            splitTemplateId = request.splitTemplateId,
            splitVisualization = request.splitVisualization,
            rawNotificationId = request.rawNotificationId,
            source = when (request.source) {
                ExpenseSource.MANUAL_ENTRY -> ExpenseSource.MANUAL_ENTRY.name
                ExpenseSource.NOTIFICATION_AUTO_ACCEPT -> ExpenseSource.NOTIFICATION_AUTO_ACCEPT.name
                ExpenseSource.REVIEW_APPROVAL -> ExpenseSource.REVIEW_APPROVAL.name
                ExpenseSource.RECEIPT_SCAN -> ExpenseSource.RECEIPT_SCAN.name
                ExpenseSource.RECEIPT_BATCH_REVIEW -> ExpenseSource.RECEIPT_BATCH_REVIEW.name
                ExpenseSource.BANK_STATEMENT_REVIEW -> ExpenseSource.BANK_STATEMENT_REVIEW.name
                ExpenseSource.CSV_IMPORT -> ExpenseSource.CSV_IMPORT.name
                ExpenseSource.EMAIL_RECEIPT -> ExpenseSource.EMAIL_RECEIPT.name
                ExpenseSource.GROUP_EXPENSE -> ExpenseSource.GROUP_EXPENSE.name
                ExpenseSource.BANK_API_SYNC -> ExpenseSource.BANK_API_SYNC.name
                ExpenseSource.RECURRING_GENERATED -> ExpenseSource.RECURRING_GENERATED.name
                ExpenseSource.DEBUG_TOOL -> ExpenseSource.DEBUG_TOOL.name
                ExpenseSource.MIGRATION -> ExpenseSource.MIGRATION.name
                ExpenseSource.UNKNOWN -> ExpenseSource.UNKNOWN.name
            }
        ).normalizeOwnership()

        // 4. Check for duplicates (unless skipped)
        if (!request.skipDeduplication) {
            val isDuplicate = expenseDao.isDuplicateCurrencyAware(
                amount = expense.amount,
                merchant = expense.merchant,
                date = expense.date,
                currency = expense.currency,
                transactionType = expense.transactionType.name,
                merchantKey = expense.merchantKey,
                dedupeKey = expense.dedupeKey
            )
            if (isDuplicate) {
                return CreateExpenseResult.DuplicateSkipped(
                    existingExpenseId = -1L,
                    reason = "Duplicate expense detected: amount=${expense.amount}, merchant=${expense.merchant}, date=${expense.date}"
                )
            }
        }

        // 5. Insert atomic — IGNORE-on-conflict provides race-condition guard
        val insertedId = expenseDao.insertAtomic(expense)
        if (insertedId <= 0) {
            return CreateExpenseResult.InsertConflict(expense.dedupeKey ?: "unknown")
        }

        // 6. Write lifecycle event
        transactionEventDao.insert(
            TransactionEvent(
                expenseId = insertedId,
                eventType = LifecycleEventType.CREATED.name,
                source = request.source.name,
                actor = null,
                occurredAt = now,
                dedupeKey = expense.dedupeKey,
                duplicateExpenseId = null,
                beforeSnapshot = null,
                afterSnapshot = null,
                metadata = null,
                reason = null
            )
        )

        // 7. Dispatch post-creation side effects (best-effort, post-commit)
        sideEffectDispatcher.dispatchOnCreated(insertedId, request.source)

        // 8. Try to link this expense to a PLANNED recurring occurrence (best-effort)
        try {
            recurringLifecycleCoordinator.linkExpenseToOccurrence(insertedId)
        } catch (_: Exception) {
            // Non-critical — best effort; do not block the caller
        }

        return CreateExpenseResult.Created(insertedId)
    }

    /**
     * Updates an existing expense with full lifecycle handling:
     * write UPDATED event → persist.
     *
     * This is the single entry point for all expense updates that should
     * be recorded in the lifecycle audit log.
     *
     * @param expense The expense to persist (matched by [Expense.id]).
     */
    suspend fun updateExpense(expense: Expense) {
        val now = timeProvider.now()

        // 1. Write lifecycle event
        transactionEventDao.insert(
            TransactionEvent(
                expenseId = expense.id,
                eventType = LifecycleEventType.UPDATED.name,
                source = "USER_EDIT",
                actor = null,
                occurredAt = now,
                dedupeKey = expense.dedupeKey,
                duplicateExpenseId = null,
                beforeSnapshot = null,
                afterSnapshot = null,
                metadata = null,
                reason = null
            )
        )

        // 2. Persist the updated row
        expenseDao.update(expense)
    }

    /**
     * Deletes an expense by its ID with full lifecycle handling:
     * load → write DELETED event → delete.
     *
     * @param expenseId The ID of the expense to delete.
     * @return [Result.success] if the expense was found and deleted,
     *         [Result.failure] if the expense was not found or an error occurred.
     */
    suspend fun deleteExpense(expenseId: Long): Result<Unit> {
        val expense = expenseDao.getById(expenseId)
            ?: return Result.failure(IllegalArgumentException("Expense not found: $expenseId"))
        return deleteExpense(expense)
    }

    /**
     * Deletes an expense with full lifecycle handling:
     * write DELETED event → delete.
     *
     * @param expense The expense entity to delete.
     * @return [Result.success] on success, [Result.failure] on error.
     */
    suspend fun deleteExpense(expense: Expense): Result<Unit> {
        return try {
            val now = timeProvider.now()

            // 1. Write lifecycle event with beforeSnapshot
            transactionEventDao.insert(
                TransactionEvent(
                    expenseId = expense.id,
                    eventType = LifecycleEventType.DELETED.name,
                    source = "USER_ACTION",
                    actor = null,
                    occurredAt = now,
                    dedupeKey = expense.dedupeKey,
                    duplicateExpenseId = null,
                    beforeSnapshot = expense.toString(),
                    afterSnapshot = null,
                    metadata = null,
                    reason = null
                )
            )

            // 2. Delete the expense
            expenseDao.delete(expense)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Validates a [CreateExpenseRequest] and returns a list of error messages.
     * Returns an empty list if the request is valid.
     */
    private fun validate(request: CreateExpenseRequest): List<String> {
        val errors = mutableListOf<String>()
        if (!request.amount.isFinite() || request.amount <= 0) {
            errors.add("Amount must be positive and finite")
        }
        if (request.amount > 1_000_000) {
            errors.add("Amount exceeds maximum (1,000,000)")
        }
        if (request.merchant.isBlank()) {
            errors.add("Merchant cannot be blank")
        }
        if (request.merchant == "Unknown" || request.merchant == "Parsing Failed") {
            errors.add("Merchant placeholder not allowed for real expenses")
        }
        if (request.currency.isBlank()) {
            errors.add("Currency is required")
        }
        if (request.date <= 0) {
            errors.add("Date must be positive")
        }
        return errors
    }
}
