package com.yourname.expensetracker.domain.transaction.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import org.json.JSONObject
import timber.log.Timber
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
    private val database: AppDatabase,
    private val expenseDao: ExpenseDao,
    private val transactionEventDao: TransactionEventDao,
    private val timeProvider: TimeProvider,
    private val currencyConverter: CurrencyConverter,
    private val sideEffectDispatcher: TransactionSideEffectDispatcher,
    private val recurringLifecycleCoordinator: RecurringLifecycleCoordinator,
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {
    /**
     * Creates an expense with full lifecycle handling:
     * validate → normalize → dedupe → insert atomic → event.
     *
     * All database writes (insert + event) happen inside a Room transaction.
     * Side effects are dispatched POST-COMMIT (outside the transaction).
     *
     * @param request The creation request containing all expense fields and policy controls.
     * @return A [CreateExpenseResult] indicating the outcome (created, duplicate, error, etc.).
     */
    suspend fun createExpense(request: CreateExpenseRequest): CreateExpenseResult {
        // Guard: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            return CreateExpenseResult.Error(IllegalStateException("Database writes blocked during restore"))
        }

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
        var expense = Expense(
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

        // ── Currency conversion snapshot ──────────────────────────────────
        // If the expense currency differs from the home currency (EUR),
        // populate baseAmount/baseCurrency/exchangeRateUsed so that reports
        // can reconstruct the original home-currency value without re-converting.
        val homeCurrency = CurrencyConverter.DEFAULT_BASE_CURRENCY
        if (expense.currency != homeCurrency) {
            // CURR-2: Use convertAsOf with the expense date to capture the
            // historical exchange rate valid at the purchase time, rather than
            // the latest rate which may differ from the rate on the purchase date.
            val conversion = runCatching {
                currencyConverter.convertAsOf(
                    amount = expense.amount,
                    fromCurrency = expense.currency,
                    toCurrency = homeCurrency,
                    atMillis = expense.date
                )
            }.getOrNull()
            if (conversion != null) {
                expense = expense.copy(
                    baseAmount = conversion.convertedAmount,
                    baseCurrency = homeCurrency,
                    exchangeRateUsed = conversion.rateUsed
                )
            } else {
                Timber.w(
                    "Cannot convert %s %.2f to %s for expense creation (as of %d); " +
                    "baseAmount/baseCurrency/exchangeRateUsed left at defaults",
                    expense.currency, expense.amount, homeCurrency, expense.date
                )
            }
        }

        // 4. Deduplication — behaviour depends on mode
        val dedupMode = request.deduplicationMode
        val skipDedup = request.skipDeduplication

        if (!skipDedup) {
            when (dedupMode) {
                DeduplicationMode.STRICT_EXTERNAL_ID -> {
                    val key = request.idempotencyKey ?: request.externalFingerprint
                    if (key == null) {
                        return CreateExpenseResult.ValidationFailed(
                            listOf("STRICT_EXTERNAL_ID mode requires idempotencyKey or externalFingerprint")
                        )
                    }
                    // Use source namespace for the dedup key
                    expense = expense.copy(dedupeKey = "idem:${request.source.name}:$key")
                    // Don't run range check, rely on unique dedupeKey index
                }

                DeduplicationMode.BULK_IMPORT -> {
                    // Run standard range-based dedup but mark result as bulk duplicate
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
                        val duplicateId = expenseDao.findDuplicateId(
                            merchantKey = expense.merchantKey,
                            amount = expense.amount,
                            date = expense.date,
                            currency = expense.currency,
                            transactionType = expense.transactionType
                        )
                        // Write duplicate resolution event
                        writeDuplicateEvent(expense, request, now, duplicateId, "Bulk import duplicate")
                        return CreateExpenseResult.DuplicateSkipped(
                            existingExpenseId = duplicateId ?: -1L,
                            reason = "Bulk import duplicate: amount=${expense.amount}, merchant=${expense.merchant}, date=${expense.date}"
                        )
                    }
                }

                DeduplicationMode.SKIP_FOR_DEBUG_RESTORE -> {
                    // Skip all deduplication entirely
                }

                DeduplicationMode.STANDARD -> {
                    // Current behaviour: range check + insertAtomic
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
                        val duplicateId = expenseDao.findDuplicateId(
                            merchantKey = expense.merchantKey,
                            amount = expense.amount,
                            date = expense.date,
                            currency = expense.currency,
                            transactionType = expense.transactionType
                        )
                        // Write duplicate resolution event with metadata
                        writeDuplicateEvent(expense, request, now, duplicateId, "Standard duplicate")
                        return CreateExpenseResult.DuplicateSkipped(
                            existingExpenseId = duplicateId ?: -1L,
                            reason = "Duplicate expense detected: amount=${expense.amount}, merchant=${expense.merchant}, date=${expense.date}"
                        )
                    }
                }
            }
        }

        // 5. Insert + event inside a single database transaction
        //    Side effects (step 7, 8) remain outside the transaction (post-commit).
        val insertedId = database.withTransaction {
            // Insert atomic — IGNORE-on-conflict provides race-condition guard
            val id = expenseDao.insertAtomic(expense)
            if (id <= 0L) {
                return@withTransaction -1L
            }

            // Write lifecycle event
            transactionEventDao.insert(
                TransactionEvent(
                    expenseId = id,
                    eventType = LifecycleEventType.CREATED.name,
                    source = request.source.name,
                    actor = null,
                    occurredAt = now,
                    dedupeKey = expense.dedupeKey,
                    duplicateExpenseId = null,
                    beforeSnapshot = null,
                    afterSnapshot = expenseToSnapshot(id, expense),
                    metadata = null,
                    reason = null
                )
            )
            id
        }

        if (insertedId <= 0L) {
            return CreateExpenseResult.InsertConflict(expense.dedupeKey ?: "unknown")
        }

        // 6. Dispatch post-creation side effects (best-effort, post-commit)
        sideEffectDispatcher.dispatchOnCreated(insertedId, request.source)

        // 7. Try to link this expense to a PLANNED recurring occurrence (best-effort)
        try {
            recurringLifecycleCoordinator.linkExpenseToOccurrence(insertedId)
        } catch (_: Exception) {
            // Non-critical — best effort; do not block the caller
        }

        return CreateExpenseResult.Created(insertedId)
    }

    /**
     * Updates an existing expense with full lifecycle handling:
     * capture beforeSnapshot → persist (with dedupeKey recomputation if key fields changed)
     * → write UPDATED event with before/after snapshots.
     *
     * This is the single entry point for all expense updates that should
     * be recorded in the lifecycle audit log.
     *
     * @param expense The expense to persist (matched by [Expense.id]).
     * @param reason  Optional human-readable explanation for the update.
     * @param source  The source system/component that triggered the update (default "USER_EDIT").
     */
    suspend fun updateExpense(
        expense: Expense,
        reason: String? = null,
        source: String = "USER_EDIT"
    ) {
        // Guard: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }

        val now = timeProvider.now()

        // 1. Load existing expense for beforeSnapshot
        val existing = expenseDao.getById(expense.id)
            ?: throw IllegalArgumentException("Expense not found: ${expense.id}")
        val beforeSnapshot = expenseToSnapshot(existing)

        // 2. Recompute dedupeKey if merchant/date/amount/currency/transactionType changed
        val keyFieldsChanged = existing.merchant != expense.merchant ||
            existing.date != expense.date ||
            kotlin.math.abs(existing.amount - expense.amount) > 0.001 ||
            existing.currency != expense.currency ||
            existing.transactionType != expense.transactionType

        val updatedExpense = if (keyFieldsChanged) {
            val newDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                amount = expense.amount,
                merchant = expense.merchant,
                date = expense.date,
                currency = expense.currency,
                transactionType = expense.transactionType
            )
            val expenseWithNewKey = expense.copy(dedupeKey = newDedupeKey)

            // Check for duplicate excluding the current expense
            val isDuplicate = expenseDao.isDuplicateCurrencyAware(
                amount = expenseWithNewKey.amount,
                merchant = expenseWithNewKey.merchant,
                date = expenseWithNewKey.date,
                currency = expenseWithNewKey.currency,
                transactionType = expenseWithNewKey.transactionType.name,
                merchantKey = expenseWithNewKey.merchantKey,
                dedupeKey = expenseWithNewKey.dedupeKey
            )
            if (isDuplicate) {
                // Verify the duplicate is not the current expense being updated
                val dupId = expenseDao.findDuplicateId(
                    merchantKey = expenseWithNewKey.merchantKey,
                    amount = expenseWithNewKey.amount,
                    date = expenseWithNewKey.date,
                    currency = expenseWithNewKey.currency,
                    transactionType = expenseWithNewKey.transactionType
                )
                if (dupId != null && dupId != expense.id) {
                    throw DuplicateUpdateException(
                        "Update would create duplicate with expense $dupId"
                    )
                }
            }

            expenseWithNewKey
        } else {
            expense
        }

        // ── Currency conversion snapshot ──────────────────────────────────
        // If the expense currency differs from the home currency (EUR),
        // populate baseAmount/baseCurrency/exchangeRateUsed so that reports
        // can reconstruct the original home-currency value without re-converting.
        val homeCurrency = CurrencyConverter.DEFAULT_BASE_CURRENCY
        val finalExpense = if (updatedExpense.currency != homeCurrency) {
            val conversion = runCatching {
                currencyConverter.convert(updatedExpense.amount, updatedExpense.currency, homeCurrency)
            }.getOrNull()
            if (conversion != null) {
                updatedExpense.copy(
                    baseAmount = conversion.convertedAmount,
                    baseCurrency = homeCurrency,
                    exchangeRateUsed = conversion.rateUsed
                )
            } else {
                Timber.w(
                    "Cannot convert %s %.2f to %s for expense update; " +
                    "baseAmount/baseCurrency/exchangeRateUsed left at defaults",
                    updatedExpense.currency, updatedExpense.amount, homeCurrency
                )
                updatedExpense
            }
        } else {
            // Identity values when expense currency matches home currency
            updatedExpense.copy(
                baseAmount = updatedExpense.amount,
                baseCurrency = updatedExpense.currency,
                exchangeRateUsed = 1.0
            )
        }

        // 3. Persist the updated row + write event inside a single transaction
        database.withTransaction {
            expenseDao.update(finalExpense)

            // 4. Write lifecycle event with before/after snapshots
            val afterSnapshot = expenseToSnapshot(finalExpense)
            transactionEventDao.insert(
                TransactionEvent(
                    expenseId = expense.id,
                    eventType = LifecycleEventType.UPDATED.name,
                    source = source,
                    actor = null,
                    occurredAt = now,
                    dedupeKey = finalExpense.dedupeKey,
                    duplicateExpenseId = null,
                    beforeSnapshot = beforeSnapshot,
                    afterSnapshot = afterSnapshot,
                    metadata = null,
                    reason = reason
                )
            )
        }
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
        // Guard: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            return Result.failure(IllegalStateException("Database writes blocked during restore"))
        }
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
        // Guard: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            return Result.failure(IllegalStateException("Database writes blocked during restore"))
        }
        return try {
            val now = timeProvider.now()
            val snapshot = expenseToSnapshot(expense)

            // Write lifecycle event + delete inside a single transaction
            database.withTransaction {
                // 1. Write lifecycle event with structured JSON beforeSnapshot
                transactionEventDao.insert(
                    TransactionEvent(
                        expenseId = expense.id,
                        eventType = LifecycleEventType.DELETED.name,
                        source = "USER_ACTION",
                        actor = null,
                        occurredAt = now,
                        dedupeKey = expense.dedupeKey,
                        duplicateExpenseId = null,
                        beforeSnapshot = snapshot,
                        afterSnapshot = null,
                        metadata = null,
                        reason = null
                    )
                )

                // 2. Delete the expense
                expenseDao.delete(expense)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Validates a [CreateExpenseRequest] and returns a list of error messages.
     * Returns an empty list if the request is valid.
     *
     * Validation rules:
     * - Amount must be positive, finite, and ≤ 1,000,000
     * - Merchant must not be blank or a placeholder
     * - Currency must be a valid 3-letter ISO code (uppercase)
     * - Date must be positive and not in the future (beyond now + 1 day)
     * - Transfer transactions require transferDirection and transferAccountName
     * - Ownership conflict: cannot be both isNotMine and isSharedExpense
     */
    private fun validate(request: CreateExpenseRequest): List<String> {
        val errors = mutableListOf<String>()
        val now = timeProvider.now()

        // Amount validation
        if (!request.amount.isFinite() || request.amount <= 0) {
            errors.add("Amount must be positive and finite")
        }
        if (request.amount > 1_000_000) {
            errors.add("Amount exceeds maximum (1,000,000)")
        }

        // Merchant validation
        if (request.merchant.isBlank()) {
            errors.add("Merchant cannot be blank")
        }
        if (request.merchant == "Unknown" || request.merchant == "Parsing Failed") {
            errors.add("Merchant placeholder not allowed for real expenses")
        }

        // Currency ISO validation
        if (request.currency.isBlank()) {
            errors.add("Currency is required")
        } else if (!CURRENCY_ISO_PATTERN.matches(request.currency)) {
            errors.add("Currency must be a 3-letter ISO code (e.g. EUR, USD)")
        }

        // Date validation
        if (request.date <= 0) {
            errors.add("Date must be positive")
        }
        if (request.date > TimePeriodUtils.addDays(now, 1)) { // now + 1 day
            errors.add("Date cannot be in the future")
        }
        // TODO: Make future-date tolerance configurable (e.g. via policy object).
        // Currently uses a hardcoded 1-day tolerance. Some users may want
        // stricter (reject any future date) or looser (allow scheduling weeks ahead).

        // Transfer validation
        if (request.transactionType == TransactionType.TRANSFER) {
            if (request.transferDirection == null) {
                errors.add("Transfer direction is required for TRANSFER transactions")
            }
            if (request.transferAccountName.isNullOrBlank()) {
                errors.add("Transfer account name is required for TRANSFER transactions")
            }
        }

        // Ownership conflict: can't be both isNotMine and isSharedExpense
        if (request.isNotMine && request.isSharedExpense) {
            errors.add("Cannot be both not-mine and shared")
        }

        // Location pair validation: both or neither must be set
        if (request.latitude != null && request.longitude == null) {
            errors.add("Latitude requires longitude")
        }
        if (request.longitude != null && request.latitude == null) {
            errors.add("Longitude requires latitude")
        }

        return errors
    }

    private fun expenseToSnapshot(e: Expense): String {
        return org.json.JSONObject().apply {
            put("id", e.id)
            put("amount", e.amount)
            put("currency", e.currency)
            put("merchant", e.merchant)
            put("date", e.date)
            put("type", e.transactionType)
        }.toString()
    }

    private fun expenseToSnapshot(id: Long, e: Expense): String {
        return org.json.JSONObject().apply {
            put("id", id)
            put("amount", e.amount)
            put("currency", e.currency)
            put("merchant", e.merchant)
            put("date", e.date)
            put("type", e.transactionType)
        }.toString()
    }

    /**
     * Writes a [TransactionEvent] with eventType = [LifecycleEventType.CREATE_DUPLICATE_SKIPPED]
     * to the audit log when a duplicate expense is detected and skipped during creation.
     *
     * The event includes structured metadata (JSON) with the existing expense ID, the
     * duplicate reason, and the deduplication key so that the full duplicate-resolution
     * history can be reconstructed for auditing or debugging.
     */
    private suspend fun writeDuplicateEvent(
        expense: Expense,
        request: CreateExpenseRequest,
        occurredAt: Long,
        duplicateExpenseId: Long?,
        reason: String
    ) {
        val metadata = JSONObject().apply {
            put("reason", reason)
            put("existingExpenseId", duplicateExpenseId ?: -1L)
            put("attemptedAmount", expense.amount)
            put("attemptedMerchant", expense.merchant)
            put("attemptedDate", expense.date)
            put("attemptedCurrency", expense.currency)
            put("attemptedMerchantKey", expense.merchantKey)
        }.toString()

        try {
            transactionEventDao.insert(
                TransactionEvent(
                    expenseId = duplicateExpenseId,  // Reference the existing expense
                    eventType = LifecycleEventType.CREATE_DUPLICATE_SKIPPED.name,
                    source = request.source.name,
                    actor = null,
                    occurredAt = occurredAt,
                    dedupeKey = expense.dedupeKey,
                    duplicateExpenseId = duplicateExpenseId,
                    beforeSnapshot = null,
                    afterSnapshot = null,
                    metadata = metadata,
                    reason = reason
                )
            )
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Timber.w(error, "Failed to write duplicate-skip event for expense")
        }
    }

    companion object {
        private val CURRENCY_ISO_PATTERN = Regex("^[A-Z]{3}$")
    }
}

/**
 * Exception thrown when an update would create a duplicate expense.
 */
class DuplicateUpdateException(message: String) : IllegalStateException(message)
