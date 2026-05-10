package com.yourname.expensetracker.domain.transaction.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import kotlinx.coroutines.flow.first
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.transaction.SideEffectMode
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
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val writeBarrier: DatabaseWriteBarrier,
    private val currencySettingsRepository: CurrencySettingsRepository
) {
    /**
     * Creates an expense with full lifecycle handling:
     * validate → normalize → dedupe → insert atomic → event.
     *
     * All database writes (insert + event) happen inside a Room transaction.
     * Side effects are dispatched POST-COMMIT (outside the transaction) when
     * [sideEffectMode] is [SideEffectMode.IMMEDIATE] (the default).
     *
     * When called inside a caller-managed `database.withTransaction`, pass
     * [SideEffectMode.DEFER] and call [dispatchPostCreationSideEffects] after
     * the outer transaction commits to avoid running side effects for data
     * that may later roll back.
     *
     * @param request The creation request containing all expense fields and policy controls.
     * @param sideEffectMode Controls whether side effects run immediately or are deferred.
     * @return A [CreateExpenseResult] indicating the outcome (created, duplicate, error, etc.).
     */
    suspend fun createExpense(
        request: CreateExpenseRequest,
        sideEffectMode: SideEffectMode = SideEffectMode.IMMEDIATE
    ): CreateExpenseResult {
        // Guard: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            return CreateExpenseResult.Error(IllegalStateException("Database writes blocked during restore"))
        }

        val now = timeProvider.now()

        // 1. Write CREATE_ATTEMPTED event before validation
        val attemptDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount = request.amount,
            merchant = request.merchant,
            date = request.date,
            currency = request.currency,
            transactionType = request.transactionType
        )
        runCatching {
            transactionEventDao.insert(
                TransactionEvent(
                    expenseId = null,
                    eventType = LifecycleEventType.CREATE_ATTEMPTED.name,
                    source = request.source.name,
                    actor = null,
                    occurredAt = now,
                    dedupeKey = attemptDedupeKey,
                    duplicateExpenseId = null,
                    beforeSnapshot = null,
                    afterSnapshot = null,
                    metadata = null,
                    reason = "Attempting create for ${request.merchant} ${request.amount} ${request.currency}"
                )
            )
        }

        // 2. Validate
        val errors = validate(request)
        if (errors.isNotEmpty()) {
            runCatching {
                transactionEventDao.insert(
                    TransactionEvent(
                        expenseId = null,
                        eventType = LifecycleEventType.CREATE_VALIDATION_FAILED.name,
                        source = request.source.name,
                        actor = null,
                        occurredAt = now,
                        dedupeKey = attemptDedupeKey,
                        duplicateExpenseId = null,
                        beforeSnapshot = null,
                        afterSnapshot = null,
                        metadata = org.json.JSONObject().apply {
                            put("errors", errors.joinToString("; "))
                        }.toString(),
                        reason = "Validation failed: ${errors.first()}"
                    )
                )
            }
            return CreateExpenseResult.ValidationFailed(errors)
        }

        // 3. Generate merchant key and dedupe key
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
        // Populate baseAmount/baseCurrency/exchangeRateUsed so reports can
        // reconstruct the home-currency value without re-converting.
        val homeCurrency = try {
            currencySettingsRepository.homeCurrency().first()
        } catch (_: Exception) {
            CurrencyConverter.DEFAULT_BASE_CURRENCY
        }
        if (expense.currency != homeCurrency) {
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
        } else {
            expense = expense.copy(
                baseAmount = expense.amount,
                baseCurrency = homeCurrency,
                exchangeRateUsed = 1.0
            )
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
                        val duplicateId = expenseDao.findDuplicateIdCurrencyAware(
                            amount = expense.amount,
                            merchant = expense.merchant,
                            date = expense.date,
                            currency = expense.currency,
                            transactionType = expense.transactionType.name,
                            merchantKey = expense.merchantKey,
                            dedupeKey = expense.dedupeKey
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
                        val duplicateId = expenseDao.findDuplicateIdCurrencyAware(
                            amount = expense.amount,
                            merchant = expense.merchant,
                            date = expense.date,
                            currency = expense.currency,
                            transactionType = expense.transactionType.name,
                            merchantKey = expense.merchantKey,
                            dedupeKey = expense.dedupeKey
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
            // Write CREATE_INSERT_CONFLICT event for observability
            runCatching {
                transactionEventDao.insert(
                    TransactionEvent(
                        expenseId = null,
                        eventType = LifecycleEventType.CREATE_INSERT_CONFLICT.name,
                        source = request.source.name,
                        actor = null,
                        occurredAt = now,
                        dedupeKey = expense.dedupeKey,
                        duplicateExpenseId = null,
                        beforeSnapshot = null,
                        afterSnapshot = null,
                        metadata = org.json.JSONObject().apply {
                            put("dedupMode", dedupMode.name)
                            put("dedupeKey", expense.dedupeKey ?: "unknown")
                        }.toString(),
                        reason = "Insert conflict for dedupeKey=${expense.dedupeKey}"
                    )
                )
            }
            // For STRICT_EXTERNAL_ID mode, resolve the conflict by looking up existing ID
            if (dedupMode == DeduplicationMode.STRICT_EXTERNAL_ID && expense.dedupeKey != null) {
                val existingId = expenseDao.findIdByDedupeKey(expense.dedupeKey!!)
                if (existingId != null) {
                    runCatching {
                        transactionEventDao.insert(
                            TransactionEvent(
                                expenseId = existingId,
                                eventType = LifecycleEventType.CREATE_DUPLICATE_SKIPPED.name,
                                source = request.source.name,
                                actor = null,
                                occurredAt = now,
                                dedupeKey = expense.dedupeKey,
                                duplicateExpenseId = existingId,
                                beforeSnapshot = null,
                                afterSnapshot = null,
                                metadata = org.json.JSONObject().apply {
                                    put("reason", "Idempotent STRICT_EXTERNAL_ID duplicate resolved")
                                    put("existingExpenseId", existingId)
                                }.toString(),
                                reason = "STRICT_EXTERNAL_ID idempotent retry resolved to existing expense"
                            )
                        )
                    }
                    return CreateExpenseResult.DuplicateSkipped(
                        existingExpenseId = existingId,
                        reason = "Idempotent STRICT_EXTERNAL_ID duplicate resolved"
                    )
                }
            }
            return CreateExpenseResult.InsertConflict(expense.dedupeKey ?: "unknown")
        }

        // 6. Side effects — only if IMMEDIATE; DEFER shifts responsibility to caller
        if (sideEffectMode == SideEffectMode.IMMEDIATE) {
            dispatchPostCreationSideEffects(insertedId, request.source)
        }

        return CreateExpenseResult.Created(insertedId)
    }

    /**
     * Dispatch post-creation side effects for an already-committed expense.
     *
     * Call this after your outer `database.withTransaction` has committed when
     * you used [SideEffectMode.DEFER] in [createExpense]. Best-effort: failures
     * are logged but do not propagate.
     *
     * @param expenseId The ID of the just-committed expense.
     * @param source    The [ExpenseSource] that created the expense.
     */
    suspend fun dispatchPostCreationSideEffects(expenseId: Long, source: ExpenseSource) {
        sideEffectDispatcher.dispatchOnCreated(expenseId, source)

        try {
            recurringLifecycleCoordinator.linkExpenseToOccurrence(expenseId)
        } catch (_: Exception) {
            Timber.w("Non-critical: failed to link expense $expenseId to recurring occurrence")
        }
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
            val newMerchantKey = if (existing.merchant != expense.merchant) {
                MerchantKeyGenerator.generate(expense.merchant)
            } else {
                expense.merchantKey
            }
            val expenseWithNewKey = expense.copy(
                dedupeKey = newDedupeKey,
                merchantKey = newMerchantKey
            )

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
                val dupId = expenseDao.findDuplicateIdCurrencyAware(
                    amount = expenseWithNewKey.amount,
                    merchant = expenseWithNewKey.merchant,
                    date = expenseWithNewKey.date,
                    currency = expenseWithNewKey.currency,
                    transactionType = expenseWithNewKey.transactionType.name,
                    merchantKey = expenseWithNewKey.merchantKey,
                    dedupeKey = expenseWithNewKey.dedupeKey
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
        val homeCurrencyUpdate = try {
            currencySettingsRepository.homeCurrency().first()
        } catch (_: Exception) {
            CurrencyConverter.DEFAULT_BASE_CURRENCY
        }
        val finalExpense = if (updatedExpense.currency != homeCurrencyUpdate) {
            val conversion = runCatching {
                currencyConverter.convertAsOf(
                    amount = updatedExpense.amount,
                    fromCurrency = updatedExpense.currency,
                    toCurrency = homeCurrencyUpdate,
                    atMillis = updatedExpense.date
                )
            }.getOrNull()
            if (conversion != null) {
                updatedExpense.copy(
                    baseAmount = conversion.convertedAmount,
                    baseCurrency = homeCurrencyUpdate,
                    exchangeRateUsed = conversion.rateUsed
                )
            } else {
                Timber.w(
                    "Cannot convert %s %.2f to %s for expense update (as of %d); " +
                    "baseAmount/baseCurrency/exchangeRateUsed left at defaults",
                    updatedExpense.currency, updatedExpense.amount, homeCurrencyUpdate,
                    updatedExpense.date
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

        // Post-update side effects (best-effort, fire-and-forget)
        try {
            sideEffectDispatcher.dispatchOnUpdated(expense.id, source)
        } catch (e: Exception) {
            Timber.w(e, "Non-critical: side effects failed after updating expense %d", expense.id)
        }

        // Reconcile recurring occurrence link if expense fields changed
        try {
            if (existing.amount != expense.amount || existing.date != expense.date ||
                existing.merchant != expense.merchant || existing.currency != expense.currency ||
                existing.transactionType != expense.transactionType
            ) {
                recurringLifecycleCoordinator.unlinkExpenseFromOccurrence(expense.id)
                recurringLifecycleCoordinator.linkExpenseToOccurrence(expense.id)
            }
        } catch (e: Exception) {
            Timber.w(e, "Non-critical: recurring reconciliation failed for expense %d", expense.id)
        }
    }

    /**
     * Updates only the category on an expense, with full lifecycle tracking.
     * Writes a UPDATED TransactionEvent with before/after snapshots.
     *
     * @param expenseId   The ID of the expense to update.
     * @param newCategoryId The new category ID to set (null is treated as no-op
     *                      since the DAO requires a non-null category value).
     * @param reason      Optional human-readable explanation for the update.
     * @param source      The source system/component that triggered the update.
     */
    suspend fun updateCategory(
        expenseId: Long,
        newCategoryId: Long?,
        reason: String? = null,
        source: String = "USER_EDIT"
    ) {
        // Guard: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }

        val existing = expenseDao.getById(expenseId) ?: return
        if (existing.categoryId == newCategoryId) return  // handles null==null correctly

        val now = timeProvider.now()
        val beforeSnapshot = expenseToSnapshot(existing)
        val updated = existing.copy(categoryId = newCategoryId)

        database.withTransaction {
            expenseDao.updateCategoryNullable(expenseId, newCategoryId)
            transactionEventDao.insert(
                TransactionEvent(
                    expenseId = expenseId,
                    eventType = LifecycleEventType.UPDATED.name,
                    source = source,
                    actor = null,
                    occurredAt = now,
                    dedupeKey = existing.dedupeKey,
                    duplicateExpenseId = null,
                    beforeSnapshot = beforeSnapshot,
                    afterSnapshot = expenseToSnapshot(expenseId, updated),
                    metadata = null,
                    reason = reason
                )
            )
        }

        // Post-update side effects (best-effort)
        try {
            sideEffectDispatcher.dispatchOnUpdated(expenseId, source)
        } catch (e: Exception) {
            Timber.w(e, "Non-critical: side effects failed after updating category for expense %d", expenseId)
        }
    }

    /**
     * Updates only the location on an expense, with full lifecycle tracking.
     * Writes a UPDATED TransactionEvent with before/after snapshots.
     * This is for USER edits (not backfill worker).
     *
     * @param expenseId       The ID of the expense to update.
     * @param latitude        The new latitude value.
     * @param longitude       The new longitude value.
     * @param source          The source system/component that triggered the update.
     * @param placeId         The place ID (nullable).
     * @param resolvedAddress The resolved address string (nullable).
     * @param reason          Optional human-readable explanation for the update.
     */
    suspend fun updateLocation(
        expenseId: Long,
        latitude: Double,
        longitude: Double,
        source: String = "USER_EDIT",
        placeId: String? = null,
        resolvedAddress: String? = null,
        reason: String? = null
    ) {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }
        require(latitude in -90.0..90.0) { "Latitude out of range" }
        require(longitude in -180.0..180.0) { "Longitude out of range" }

        val existing = expenseDao.getById(expenseId) ?: return
        if (existing.latitude == latitude && existing.longitude == longitude &&
            existing.placeId == placeId && existing.resolvedAddress == resolvedAddress) return

        val now = timeProvider.now()
        val beforeSnapshot = expenseToSnapshot(existing)
        val updated = existing.copy(
            latitude = latitude, longitude = longitude,
            locationSource = source, placeId = placeId, resolvedAddress = resolvedAddress,
            backfillAttempts = 0
        )
        database.withTransaction {
            expenseDao.updateLocation(expenseId, latitude, longitude, source, placeId, resolvedAddress)
            transactionEventDao.insert(TransactionEvent(
                expenseId = expenseId,
                eventType = LifecycleEventType.UPDATED.name,
                source = source, actor = null, occurredAt = now,
                dedupeKey = existing.dedupeKey, duplicateExpenseId = null,
                beforeSnapshot = beforeSnapshot,
                afterSnapshot = expenseToSnapshot(expenseId, updated),
                metadata = null, reason = reason
            ))
        }

        // Side effects intentionally skipped for location-only updates — location
        // does not affect budget/anomaly/merchant/recurring matching logic.
    }

    /**
     * T10-FIXED: Updates business/tax fields on an expense through the lifecycle coordinator.
     * Side effects are deferred until after the DB transaction commits.
     *
     * PR-T1: Real implementation — loads expense from DB, applies field copies,
     * persists via expenseDao.update(), and writes a UPDATED TransactionEvent.
     * Fields that do not exist on the Expense entity (businessUsePercent, taxCategory, vatEligible)
     * are accepted as no-op parameters for API compatibility.
     */
    suspend fun updateBusinessTaxFields(
        expenseId: Long,
        isBusinessExpense: Boolean? = null,
        businessUsePercent: Double? = null,
        taxCategory: String? = null,
        vatEligible: Boolean? = null,
        receiptRequired: Boolean? = null,
        source: String = "BUSINESS_TAX_UPDATE"
    ) {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }
        val existing = expenseDao.getById(expenseId) ?: return
        val now = timeProvider.now()
        val beforeSnapshot = expenseToSnapshot(existing)

        // Map receiptRequired → requiresReceipt (the actual Expense field name)
        val updated = existing.copy(
            isBusinessExpense = isBusinessExpense ?: existing.isBusinessExpense,
            requiresReceipt = receiptRequired ?: existing.requiresReceipt
        )

        database.withTransaction {
            expenseDao.update(updated)
            val afterSnapshot = expenseToSnapshot(expenseId, updated)
            transactionEventDao.insert(
                TransactionEvent(
                    expenseId = expenseId,
                    eventType = LifecycleEventType.UPDATED.name,
                    source = source,
                    actor = null,
                    occurredAt = now,
                    dedupeKey = existing.dedupeKey,
                    duplicateExpenseId = null,
                    beforeSnapshot = beforeSnapshot,
                    afterSnapshot = afterSnapshot,
                    metadata = null,
                    reason = null
                )
            )
        }

        // Post-update side effects (best-effort)
        try {
            sideEffectDispatcher.dispatchOnUpdated(expenseId, source)
        } catch (e: Exception) {
            Timber.w(e, "Non-critical: side effects failed after updating business/tax fields for expense %d", expenseId)
        }

        Timber.d("T10: Business/tax fields updated for expense %d", expenseId)
    }

    /**
     * Updates only the merchant on an expense, with full lifecycle tracking.
     * Recomputes [merchantKey] and [dedupeKey] since the merchant changed.
     * Writes a UPDATED TransactionEvent with before/after snapshots.
     *
     * @param expenseId   The ID of the expense to update.
     * @param newMerchant The new merchant name.
     * @param reason      Optional human-readable explanation for the update.
     * @param source      The source system/component that triggered the update.
     */
    suspend fun updateMerchant(
        expenseId: Long,
        newMerchant: String,
        reason: String? = null,
        source: String = "USER_EDIT"
    ) {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }

        val existing = expenseDao.getById(expenseId) ?: return
        if (existing.merchant == newMerchant) return

        val now = timeProvider.now()
        val beforeSnapshot = expenseToSnapshot(existing)
        val newMerchantKey = MerchantKeyGenerator.generate(newMerchant)
        val newDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            existing.amount, newMerchant, existing.date, existing.currency, existing.transactionType
        )

        // Pre-check: ensure the new dedupeKey doesn't collide with another expense
        val collidingId = expenseDao.findDuplicateIdCurrencyAware(
            amount = existing.amount,
            merchant = newMerchant,
            date = existing.date,
            currency = existing.currency,
            transactionType = existing.transactionType.name,
            merchantKey = newMerchantKey,
            dedupeKey = newDedupeKey
        )
        if (collidingId != null && collidingId != expenseId) {
            throw DuplicateUpdateException(
                "Cannot update merchant: would create duplicate of expense $collidingId"
            )
        }

        val updated = existing.copy(
            merchant = newMerchant,
            merchantKey = newMerchantKey,
            dedupeKey = newDedupeKey
        )

        database.withTransaction {
            expenseDao.updateMerchantAndKey(expenseId, newMerchant, newMerchantKey, newDedupeKey)
            transactionEventDao.insert(
                TransactionEvent(
                    expenseId = expenseId,
                    eventType = LifecycleEventType.UPDATED.name,
                    source = source,
                    actor = null,
                    occurredAt = now,
                    dedupeKey = newDedupeKey,
                    duplicateExpenseId = null,
                    beforeSnapshot = beforeSnapshot,
                    afterSnapshot = expenseToSnapshot(expenseId, updated),
                    metadata = null,
                    reason = reason
                )
            )
        }

        // Post-update side effects (best-effort)
        try {
            sideEffectDispatcher.dispatchOnUpdated(expenseId, source)
        } catch (e: Exception) {
            Timber.w(e, "Non-critical: side effects failed after updating merchant for expense %d", expenseId)
        }

        // Reconcile recurring link — merchant changed
        try {
            recurringLifecycleCoordinator.unlinkExpenseFromOccurrence(expenseId)
            recurringLifecycleCoordinator.linkExpenseToOccurrence(expenseId)
        } catch (e: Exception) {
            Timber.w(e, "Non-critical: recurring reconciliation failed for expense %d", expenseId)
        }
    }

    /**
     * Updates only the transaction type on an expense, with full lifecycle tracking.
     * Recomputes [dedupeKey] since the type is a key field used for deduplication.
     * Writes a UPDATED TransactionEvent with before/after snapshots.
     *
     * @param expenseId The ID of the expense to update.
     * @param newType   The new [TransactionType].
     * @param reason    Optional human-readable explanation for the update.
     * @param source    The source system/component that triggered the update.
     */
    suspend fun updateType(
        expenseId: Long,
        newType: TransactionType,
        reason: String? = null,
        source: String = "USER_EDIT"
    ) {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }

        val existing = expenseDao.getById(expenseId) ?: return
        if (existing.transactionType == newType) return

        val now = timeProvider.now()
        val beforeSnapshot = expenseToSnapshot(existing)
        val newDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            existing.amount, existing.merchant, existing.date, existing.currency, newType
        )

        // Pre-check: ensure the new dedupeKey doesn't collide with another expense
        val collidingId = expenseDao.findDuplicateIdCurrencyAware(
            amount = existing.amount,
            merchant = existing.merchant,
            date = existing.date,
            currency = existing.currency,
            transactionType = newType.name,
            merchantKey = existing.merchantKey,
            dedupeKey = newDedupeKey
        )
        if (collidingId != null && collidingId != expenseId) {
            throw DuplicateUpdateException(
                "Cannot update type: would create duplicate of expense $collidingId"
            )
        }

        val updated = existing.copy(
            transactionType = newType,
            dedupeKey = newDedupeKey
        )

        database.withTransaction {
            expenseDao.updateTransactionType(expenseId, newType.name, newDedupeKey)
            transactionEventDao.insert(
                TransactionEvent(
                    expenseId = expenseId,
                    eventType = LifecycleEventType.UPDATED.name,
                    source = source,
                    actor = null,
                    occurredAt = now,
                    dedupeKey = newDedupeKey,
                    duplicateExpenseId = null,
                    beforeSnapshot = beforeSnapshot,
                    afterSnapshot = expenseToSnapshot(expenseId, updated),
                    metadata = null,
                    reason = reason
                )
            )
        }

        // Post-update side effects (best-effort)
        try {
            sideEffectDispatcher.dispatchOnUpdated(expenseId, source)
        } catch (e: Exception) {
            Timber.w(e, "Non-critical: side effects failed after updating type for expense %d", expenseId)
        }

        // Reconcile recurring link — transaction type changed
        try {
            recurringLifecycleCoordinator.unlinkExpenseFromOccurrence(expenseId)
            recurringLifecycleCoordinator.linkExpenseToOccurrence(expenseId)
        } catch (e: Exception) {
            Timber.w(e, "Non-critical: recurring reconciliation failed for expense %d", expenseId)
        }
    }

    /**
     * Updates transfer direction and transfer account name atomically,
     * with full lifecycle tracking. Writes a UPDATED TransactionEvent
     * with before/after snapshots.
     *
     * @param expenseId           The ID of the expense to update.
     * @param transferDirection   The new transfer direction (nullable).
     * @param transferAccountName The new transfer account name (nullable).
     * @param reason              Optional human-readable explanation.
     * @param source              The source system/component that triggered the update.
     */
    suspend fun updateTransferDetails(
        expenseId: Long,
        transferDirection: com.yourname.expensetracker.data.database.entity.TransferDirection?,
        transferAccountName: String?,
        reason: String? = null,
        source: String = "USER_EDIT"
    ) {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }

        val existing = expenseDao.getById(expenseId) ?: return
        if (existing.transferDirection == transferDirection && existing.transferAccountName == transferAccountName) return

        val now = timeProvider.now()
        val beforeSnapshot = expenseToSnapshot(existing)
        val updated = existing.copy(
            transferDirection = transferDirection,
            transferAccountName = transferAccountName
        )

        database.withTransaction {
            expenseDao.updateTransferDirection(expenseId, transferDirection?.name)
            expenseDao.updateTransferAccountName(expenseId, transferAccountName)
            transactionEventDao.insert(
                TransactionEvent(
                    expenseId = expenseId,
                    eventType = LifecycleEventType.UPDATED.name,
                    source = source,
                    actor = null,
                    occurredAt = now,
                    dedupeKey = existing.dedupeKey,
                    duplicateExpenseId = null,
                    beforeSnapshot = beforeSnapshot,
                    afterSnapshot = expenseToSnapshot(expenseId, updated),
                    metadata = null,
                    reason = reason
                )
            )
        }

        // Post-update side effects (best-effort)
        try {
            sideEffectDispatcher.dispatchOnUpdated(expenseId, source)
        } catch (e: Exception) {
            Timber.w(e, "Non-critical: side effects failed after updating transfer details for expense %d", expenseId)
        }
    }

    /**
     * Updates all six ownership fields atomically with [Expense.normalizeOwnership]
     * enforcement, with full lifecycle tracking. Writes a UPDATED TransactionEvent
     * with before/after snapshots.
     *
     * @param expenseId       The ID of the expense to update.
     * @param isNotMine       Whether the expense is not mine.
     * @param ownerName       The owner name (nullable).
     * @param isSharedExpense Whether the expense is shared.
     * @param sharedWithName  The shared-with name (nullable).
     * @param mySharePercentage The user's share percentage (nullable).
     * @param myShareAmount   The user's share amount (nullable).
     * @param reason          Optional human-readable explanation.
     * @param source          The source system/component that triggered the update.
     */
    suspend fun updateOwnership(
        expenseId: Long,
        isNotMine: Boolean,
        ownerName: String?,
        isSharedExpense: Boolean,
        sharedWithName: String?,
        mySharePercentage: Int?,
        myShareAmount: Double?,
        reason: String? = null,
        source: String = "USER_EDIT"
    ) {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }

        val existing = expenseDao.getById(expenseId) ?: return

        // Apply normalizeOwnership to enforce mutual exclusivity
        val normalized = existing.copy(
            isNotMine = isNotMine,
            ownerName = ownerName,
            isSharedExpense = isSharedExpense,
            sharedWithName = sharedWithName,
            mySharePercentage = mySharePercentage,
            myShareAmount = myShareAmount
        ).normalizeOwnership()

        // Check if anything actually changed
        if (existing.isNotMine == normalized.isNotMine &&
            existing.ownerName == normalized.ownerName &&
            existing.isSharedExpense == normalized.isSharedExpense &&
            existing.sharedWithName == normalized.sharedWithName &&
            existing.mySharePercentage == normalized.mySharePercentage &&
            existing.myShareAmount == normalized.myShareAmount
        ) return

        val now = timeProvider.now()
        val beforeSnapshot = expenseToSnapshot(existing)
        val updated = normalized  // for snapshot

        database.withTransaction {
            expenseDao.updateIsNotMine(expenseId, normalized.isNotMine)
            expenseDao.updateOwnerName(expenseId, normalized.ownerName)
            expenseDao.updateIsSharedExpense(expenseId, normalized.isSharedExpense)
            expenseDao.updateSharedWithName(expenseId, normalized.sharedWithName)
            expenseDao.updateMySharePercentage(expenseId, normalized.mySharePercentage)
            expenseDao.updateMyShareAmount(expenseId, normalized.myShareAmount)
            transactionEventDao.insert(
                TransactionEvent(
                    expenseId = expenseId,
                    eventType = LifecycleEventType.UPDATED.name,
                    source = source,
                    actor = null,
                    occurredAt = now,
                    dedupeKey = existing.dedupeKey,
                    duplicateExpenseId = null,
                    beforeSnapshot = beforeSnapshot,
                    afterSnapshot = expenseToSnapshot(expenseId, updated),
                    metadata = null,
                    reason = reason
                )
            )
        }

        // Post-update side effects (best-effort)
        try {
            sideEffectDispatcher.dispatchOnUpdated(expenseId, source)
        } catch (e: Exception) {
            Timber.w(e, "Non-critical: side effects failed after updating ownership for expense %d", expenseId)
        }
    }

    /**
     * Bulk-updates the category for all expenses matching a merchant key.
     * Writes a single BULK_UPDATED TransactionEvent (not per-row) with
     * JSON metadata describing the operation.
     *
     * @param merchant      The merchant name to derive the merchant key from.
     * @param newCategoryId The new category ID to apply.
     * @param source        The source system/component that triggered the update.
     * @param reason        Optional human-readable explanation for the update.
     */
    suspend fun bulkUpdateCategory(
        merchant: String,
        newCategoryId: Long,
        source: String = "USER_EDIT",
        reason: String? = null
    ) {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }
        val merchantKey = MerchantKeyGenerator.generate(merchant)
        val now = timeProvider.now()

        database.withTransaction {
            expenseDao.updateCategoryForMerchant(merchantKey, newCategoryId)
            transactionEventDao.insert(TransactionEvent(
                expenseId = null,
                eventType = LifecycleEventType.BULK_UPDATED.name,
                source = source, actor = null, occurredAt = now,
                dedupeKey = null, duplicateExpenseId = null,
                beforeSnapshot = null, afterSnapshot = null,
                metadata = JSONObject().apply {
                    put("merchant", merchant)
                    put("merchantKey", merchantKey)
                    put("newCategoryId", newCategoryId)
                }.toString(),
                reason = reason
            ))
        }

        // Side effects intentionally skipped for bulk update — touching many rows
        // would flood the system. Budget/anomaly/merchant state should be
        // re-evaluated holistically, not per-row.
    }

    /**
     * C11-FIXED: Basic bulk category update with lifecycle events.
     * Moves all expenses from one category to another, writing per-expense
     * UPDATED events via [updateCategory] so that side effects are dispatched.
     *
     * @param categoryId    The source category whose expenses should be reassigned.
     * @param newCategoryId The target category to assign.
     * @param source        The source system/component that triggered the update.
     */
    suspend fun bulkUpdateCategory(
        categoryId: Long,
        newCategoryId: Long,
        source: String = "CATEGORY_CORRECTION"
    ) {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }
        val expenses = expenseDao.getExpensesByCategory(categoryId, 0L, Long.MAX_VALUE)
        for (expense in expenses) {
            updateCategory(expense.id, newCategoryId, source = source)
        }
        Timber.d("Bulk category update: %d expenses moved from %d to %d", expenses.size, categoryId, newCategoryId)
    }

    /**
     * Bulk-updates the merchant for all expenses matching the old merchant key.
     * Writes a single BULK_UPDATED TransactionEvent (not per-row) with
     * JSON metadata describing the operation.
     *
     * @param oldMerchant The current merchant name (used to derive old merchant key).
     * @param newMerchant The new merchant name to apply.
     * @param source      The source system/component that triggered the update.
     * @param reason      Optional human-readable explanation for the update.
     */
    suspend fun bulkUpdateMerchant(
        oldMerchant: String,
        newMerchant: String,
        source: String = "USER_EDIT",
        reason: String? = null
    ) {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }
        if (oldMerchant == newMerchant) return
        val oldMerchantKey = MerchantKeyGenerator.generate(oldMerchant)
        val newMerchantKey = MerchantKeyGenerator.generate(newMerchant)
        val now = timeProvider.now()

        database.withTransaction {
            // Fetch affected rows inside transaction to prevent TOCTOU race
            val affectedExpenses = expenseDao.getExpensesByMerchantKey(oldMerchantKey)
            if (affectedExpenses.isEmpty()) return@withTransaction

            for (expense in affectedExpenses) {
                val newDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                    expense.amount, newMerchant, expense.date, expense.currency, expense.transactionType
                )
                expenseDao.updateMerchantAndKey(expense.id, newMerchant, newMerchantKey, newDedupeKey)
            }
            val metadata = JSONObject().apply {
                put("oldMerchant", oldMerchant)
                put("newMerchant", newMerchant)
                put("oldMerchantKey", oldMerchantKey)
                put("newMerchantKey", newMerchantKey)
                put("affectedCount", affectedExpenses.size)
            }.toString()
            transactionEventDao.insert(TransactionEvent(
                expenseId = null,
                eventType = LifecycleEventType.BULK_UPDATED.name,
                source = source, actor = null, occurredAt = now,
                dedupeKey = null, duplicateExpenseId = null,
                beforeSnapshot = null, afterSnapshot = null,
                metadata = metadata,
                reason = reason
            ))
        }

        // Side effects intentionally skipped for bulk update — touching many rows
        // would flood the system. Budget/anomaly/merchant state should be
        // re-evaluated holistically, not per-row.
    }

    /**
     * Deletes an expense by its ID with full lifecycle handling:
     * load → write DELETED event → delete.
     *
     * @param expenseId The ID of the expense to delete.
     * @param source    The origin of the deletion (e.g. "USER_ACTION", "GROUP_DELETE", "RESTORE").
     * @param reason    Optional human-readable explanation for the deletion.
     * @param actor     Optional actor identifier (user ID, worker name, etc.).
     * @return [Result.success] if the expense was found and deleted,
     *         [Result.failure] if the expense was not found or an error occurred.
     */
    suspend fun deleteExpense(
        expenseId: Long,
        source: String = "USER_ACTION",
        reason: String? = null,
        actor: String? = null
    ): Result<Unit> {
        // Guard: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            return Result.failure(IllegalStateException("Database writes blocked during restore"))
        }
        val expense = expenseDao.getById(expenseId)
            ?: return Result.failure(IllegalArgumentException("Expense not found: $expenseId"))
        return deleteExpense(expense, source, reason, actor)
    }

    /**
     * Deletes an expense with full lifecycle handling:
     * write DELETED event → delete.
     *
     * @param expense The expense entity to delete.
     * @param source  The origin of the deletion (e.g. "USER_ACTION", "GROUP_DELETE", "RESTORE").
     * @param reason  Optional human-readable explanation for the deletion.
     * @param actor   Optional actor identifier (user ID, worker name, etc.).
     * @return [Result.success] on success, [Result.failure] on error.
     */
    suspend fun deleteExpense(
        expense: Expense,
        source: String = "USER_ACTION",
        reason: String? = null,
        actor: String? = null
    ): Result<Unit> {
        // Guard: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            return Result.failure(IllegalStateException("Database writes blocked during restore"))
        }
        return try {
            val now = timeProvider.now()
            val snapshot = expenseToSnapshot(expense)

            // Write lifecycle event + delete inside a single transaction
            database.withTransaction {
                transactionEventDao.insert(
                    TransactionEvent(
                        expenseId = expense.id,
                        eventType = LifecycleEventType.DELETED.name,
                        source = source,
                        actor = actor,
                        occurredAt = now,
                        dedupeKey = expense.dedupeKey,
                        duplicateExpenseId = null,
                        beforeSnapshot = snapshot,
                        afterSnapshot = null,
                        metadata = null,
                        reason = reason
                    )
                )

                expenseDao.delete(expense)
            }

            // Post-delete side effects (best-effort)
            try {
                sideEffectDispatcher.dispatchOnDeleted(expense.id, source)
            } catch (e: Exception) {
                Timber.w(e, "Non-critical: side effects failed after deleting expense %d", expense.id)
            }

            // Unlink any recurring occurrence that was linked to this expense
            try {
                recurringLifecycleCoordinator.unlinkExpenseFromOccurrence(expense.id)
            } catch (e: Exception) {
                Timber.w(e, "Non-critical: failed to unlink expense %d from recurring occurrence", expense.id)
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

    private fun expenseToSnapshot(e: Expense): String = expenseToSnapshot(e.id, e)

    private fun expenseToSnapshot(id: Long, e: Expense): String {
        return org.json.JSONObject().apply {
            put("id", id)
            put("amount", e.amount)
            put("currency", e.currency)
            put("merchant", e.merchant)
            put("merchantKey", e.merchantKey)
            put("date", e.date)
            put("type", e.transactionType.name)
            put("categoryId", e.categoryId ?: JSONObject.NULL)
            put("dedupeKey", e.dedupeKey ?: JSONObject.NULL)
            put("isNotMine", e.isNotMine)
            put("isSharedExpense", e.isSharedExpense)
            put("mySharePercentage", e.mySharePercentage ?: JSONObject.NULL)
            put("myShareAmount", e.myShareAmount ?: JSONObject.NULL)
            put("transferDirection", e.transferDirection?.name ?: JSONObject.NULL)
            put("notes", e.notes ?: JSONObject.NULL)
            put("baseAmount", e.baseAmount ?: JSONObject.NULL)
            put("baseCurrency", e.baseCurrency ?: JSONObject.NULL)
            put("exchangeRateUsed", e.exchangeRateUsed ?: JSONObject.NULL)
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
