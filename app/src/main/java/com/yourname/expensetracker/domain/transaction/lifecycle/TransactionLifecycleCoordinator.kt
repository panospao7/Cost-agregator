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
import com.yourname.expensetracker.domain.provenance.CreateExpenseSourceLinkMapper
import com.yourname.expensetracker.domain.provenance.DuplicateSourceLinkPolicy
import com.yourname.expensetracker.domain.provenance.SourceLinkEventMetadataBuilder
import com.yourname.expensetracker.domain.provenance.SourceLinkPayload
import com.yourname.expensetracker.domain.provenance.SourceLinkWriteResult
import com.yourname.expensetracker.domain.provenance.SourceLinkWriteException
import com.yourname.expensetracker.domain.provenance.SourceLinkWriter
import kotlinx.coroutines.flow.first
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.sideeffect.MutationResult
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.runBestEffortAfterCommit
import kotlinx.coroutines.CancellationException
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
// TODO P2-CURRENT-007: This class uses restoreMaintenanceMode.isWritesAllowed() directly
// instead of writeBarrier.checkWritesAllowed(). The writeBarrier is injected but unused.
// Migrate all guards to writeBarrier for consistent error reporting and centralized control.
@Singleton
class TransactionLifecycleCoordinator @Inject constructor(
    private val database: AppDatabase,
    private val expenseDao: ExpenseDao,
    private val transactionEventDao: TransactionEventDao,
    private val timeProvider: TimeProvider,
    private val currencyConverter: CurrencyConverter,
    private val sideEffectDispatcher: TransactionSideEffectDispatcher,
    private val planner: TransactionSideEffectPlanner,
    private val runner: PostCommitActionRunner,
    private val recurringLifecycleCoordinator: RecurringLifecycleCoordinator,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val writeBarrier: DatabaseWriteBarrier,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val sourceLinkWriter: SourceLinkWriter
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
     * TODO (P2-10): [SideEffectMode.IMMEDIATE] inside an outer caller-managed
     * `database.withTransaction` should be prevented by a static guard in
     * a future release. Currently callers must remember to pass DEFER manually,
     * which is error-prone.
     *
     * @param request The creation request containing all expense fields and policy controls.
     * @param sideEffectMode Controls whether side effects run immediately or are deferred.
     * @return A [CreateExpenseResult] indicating the outcome (created, duplicate, error, etc.).
     */
    @Deprecated(
        "Prefer createExpenseStandalone() for immediate side effects or " +
        "createExpenseDbOnly() for deferred side effects. The SideEffectMode " +
        "parameter will be removed in a future release.",
        level = DeprecationLevel.ERROR
    )
    suspend fun createExpense(
        request: CreateExpenseRequest,
        sideEffectMode: SideEffectMode = SideEffectMode.IMMEDIATE
    ): CreateExpenseResult {
        // Guard: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            // P2-CURRENT-011: Diagnostic for restore-blocked create
            Timber.w("CreateExpense blocked: restore maintenance mode active (source=%s, merchant=%s)", request.source.name, request.merchant)
            return CreateExpenseResult.Error(IllegalStateException("Database writes blocked during restore"))
        }

        val now = timeProvider.now()

        // DDL-512-06: use a single correlationId for every event in this create attempt
        val correlationId = request.correlationId ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()

        // Compute source-link payloads once — used across all events
        val sourceLinkPayloads = CreateExpenseSourceLinkMapper.fromRequest(request)

        // 1. Write CREATE_ATTEMPTED event before validation
        // TODO P2-CURRENT-012: For STRICT_EXTERNAL_ID mode, attemptDedupeKey should be
        // "idem:${source}:${idempotencyKey}" to match the actual key used at insert time.
        // Currently uses the standard formula which diverges from the persisted dedupeKey.
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
                    metadata = SourceLinkEventMetadataBuilder.createAttemptMetadata(sourceLinkPayloads),
                    reason = "Attempting create for ${request.merchant} ${request.amount} ${request.currency}",
                    correlationId = correlationId
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
                        metadata = SourceLinkEventMetadataBuilder.validationFailedMetadata(
                            errors = errors,
                            payloads = sourceLinkPayloads
                        ),
                        reason = "Validation failed: ${errors.first()}",
                        correlationId = correlationId  // DDL-512-06
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
                ExpenseSource.MANUAL -> ExpenseSource.MANUAL.name
                ExpenseSource.NOTIFICATION_AUTO_ACCEPT -> ExpenseSource.NOTIFICATION_AUTO_ACCEPT.name
                ExpenseSource.SMS_NOTIFICATION -> ExpenseSource.SMS_NOTIFICATION.name
                ExpenseSource.REVIEW_APPROVAL -> ExpenseSource.REVIEW_APPROVAL.name
                ExpenseSource.RECEIPT_SCAN -> ExpenseSource.RECEIPT_SCAN.name
                ExpenseSource.RECEIPT_BATCH_REVIEW -> ExpenseSource.RECEIPT_BATCH_REVIEW.name
                ExpenseSource.BANK_STATEMENT_REVIEW -> ExpenseSource.BANK_STATEMENT_REVIEW.name
                ExpenseSource.CSV_IMPORT -> ExpenseSource.CSV_IMPORT.name
                ExpenseSource.EMAIL_RECEIPT -> ExpenseSource.EMAIL_RECEIPT.name
                ExpenseSource.GROUP_EXPENSE -> ExpenseSource.GROUP_EXPENSE.name
                ExpenseSource.BANK_SYNC -> ExpenseSource.BANK_SYNC.name
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
                        // P2-CURRENT-010: Emit validation event for missing key
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
                                    metadata = JSONObject().apply {
                                        put("errors", "STRICT_EXTERNAL_ID mode requires idempotencyKey or externalFingerprint")
                                    }.toString(),
                                    reason = "STRICT_EXTERNAL_ID missing key",
                                    correlationId = correlationId  // DDL-512-06
                                )
                            )
                        }
                        return CreateExpenseResult.ValidationFailed(
                            listOf("STRICT_EXTERNAL_ID mode requires idempotencyKey or externalFingerprint")
                        )
                    }
                    // Use source namespace for the dedup key
                    expense = expense.copy(dedupeKey = "idem:${request.source.name}:$key")
                    // Don't run range check, rely on unique dedupeKey index
                }

                DeduplicationMode.BULK_IMPORT -> {
                    // TODO P2-CURRENT-005: If a STANDARD create and a BULK_IMPORT create race
                    // for the same logical transaction, the second one silently wins via
                    // insertAtomic IGNORE. The losing path gets no identity (expenseId).
                    // Consider using INSERT OR REPLACE or returning the existing ID on conflict.
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
                        val eventLogged = writeDuplicateEvent(expense, request, now, duplicateId, "Bulk import duplicate")
                        return CreateExpenseResult.DuplicateSkipped(
                            existingExpenseId = duplicateId ?: -1L,
                            reason = "Bulk import duplicate: amount=${expense.amount}, merchant=${expense.merchant}, date=${expense.date}",
                            eventLogged = eventLogged
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
                        val eventLogged = writeDuplicateEvent(expense, request, now, duplicateId, "Standard duplicate")
                        return CreateExpenseResult.DuplicateSkipped(
                            existingExpenseId = duplicateId ?: -1L,
                            reason = "Duplicate expense detected: amount=${expense.amount}, merchant=${expense.merchant}, date=${expense.date}",
                            eventLogged = eventLogged
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
                    metadata = SourceLinkEventMetadataBuilder.createdMetadata(sourceLinkPayloads),
                    reason = null,
                    correlationId = correlationId  // DDL-512-06
                )
            )

            // Write source links atomically with expense creation
            // Source-link failure is fatal — throws to rollback the entire transaction
            if (sourceLinkPayloads.isNotEmpty()) {
                val linkResults = mutableListOf<SourceLinkWriteResult>()
                for (payload in sourceLinkPayloads) {
                    val result = sourceLinkWriter.linkExpense(id, payload, correlationId)
                    linkResults.add(result)
                    if (result is SourceLinkWriteResult.Failed) {
                        throw SourceLinkWriteException("Source link failed: ${result.errorClass}")
                    }
                }
                // Write SOURCE_LINKED event
                transactionEventDao.insert(
                    TransactionEvent(
                        expenseId = id,
                        eventType = LifecycleEventType.SOURCE_LINKED.name,
                        source = request.source.name,
                        actor = null,
                        occurredAt = now,
                        dedupeKey = expense.dedupeKey,
                        duplicateExpenseId = null,
                        beforeSnapshot = null,
                        afterSnapshot = null,
                        metadata = SourceLinkEventMetadataBuilder.sourceLinkedMetadata(
                            payloads = sourceLinkPayloads,
                            results = linkResults
                        ),
                        reason = "Source links established for expense",
                        correlationId = correlationId  // DDL-512-06
                    )
                )
            }

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
                        metadata = SourceLinkEventMetadataBuilder.insertConflictMetadata(
                            dedupMode = dedupMode,
                            dedupeKey = expense.dedupeKey,
                            payloads = sourceLinkPayloads
                        ),
                        reason = "Insert conflict for dedupeKey=${expense.dedupeKey}",
                        correlationId = correlationId  // DDL-512-06
                    )
                )
            }
            // For STRICT_EXTERNAL_ID mode, resolve the conflict by looking up existing ID
            if (dedupMode == DeduplicationMode.STRICT_EXTERNAL_ID && expense.dedupeKey != null) {
                val existingId = expenseDao.findIdByDedupeKey(expense.dedupeKey!!)
                if (existingId != null) {
                    val eventLogged = runCatching {
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
                                metadata = SourceLinkEventMetadataBuilder.duplicateMetadata(
                                    policy = getDuplicateSourceLinkPolicy(request.source),
                                    attemptedExpense = expense,
                                    sourceLinkPayloads = sourceLinkPayloads
                                ),
                                reason = "STRICT_EXTERNAL_ID idempotent retry resolved to existing expense",
                                correlationId = correlationId  // DDL-512-06
                            )
                        )
                        true
                    }.getOrDefault(false)
                    return CreateExpenseResult.DuplicateSkipped(
                        existingExpenseId = existingId,
                        reason = "Idempotent STRICT_EXTERNAL_ID duplicate resolved",
                        eventLogged = eventLogged
                    )
                }
            }
            return CreateExpenseResult.InsertConflict(expense.dedupeKey ?: "unknown")
        }

        // 6. Plan side effects
        val plannedBatch = if (insertedId > 0L) {
            planner.planCreated(insertedId, request.source, correlationId)
        } else {
            PostCommitActionBatch.empty(correlationId)
        }

        // 7. Side effects — only if IMMEDIATE; DEFER shifts responsibility to caller
        if (sideEffectMode == SideEffectMode.IMMEDIATE) {
            runner.run(plannedBatch)
        }

        return CreateExpenseResult.Created(insertedId)
    }

    // ── V2 create API ────────────────────────────────────────────────────────

    /**
     * Creates an expense with full lifecycle handling but returns the planned
     * side effects as a [MutationResult] instead of executing them.
     *
     * The caller can inspect [MutationResult.postCommitActions] and either run
     * them via [PostCommitActionRunner] or merge them into an outer batch.
     *
     * @param request The creation request containing all expense fields.
     * @return A [MutationResult] containing the [CreateExpenseResult] and planned actions.
     */
    suspend fun createExpenseDbOnlyV2(
        request: CreateExpenseRequest
    ): MutationResult<CreateExpenseResult> {
        @Suppress("DEPRECATION_ERROR")
        val result = createExpense(request, SideEffectMode.DEFER)
        val batch = when (result) {
            is CreateExpenseResult.Created -> planner.planCreated(result.expenseId, request.source, request.correlationId)
            else -> PostCommitActionBatch.empty(request.correlationId ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId())
        }
        return MutationResult(result, batch)
    }

    /**
     * Creates an expense with full lifecycle handling and dispatches all
     * post-creation side effects immediately after the database transaction
     * commits, using the new planner+runner pipeline.
     *
     * This is the preferred method for standalone create operations where
     * no outer database transaction is being managed by the caller.
     *
     * @param request The creation request containing all expense fields.
     * @return A [CreateExpenseResult] indicating the outcome.
     */
    suspend fun createExpenseStandaloneV2(request: CreateExpenseRequest): CreateExpenseResult {
        val dbOnly = createExpenseDbOnlyV2(request)
        if (dbOnly.value is CreateExpenseResult.Created) {
            runner.run(dbOnly.postCommitActions)
        }
        return dbOnly.value
    }

    // ── Compatibility wrappers ───────────────────────────────────────────────

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
    suspend fun dispatchPostCreationSideEffects(
        expenseId: Long,
        source: ExpenseSource,
        correlationId: String = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId(),
        causationId: String? = null
    ) {
        // DDL-F876-11: propagate create-boundary correlation into side effects
        val batch = planner.planCreated(expenseId, source, correlationId)
        runner.run(batch)
    }

    /**
     * Creates an expense with full lifecycle handling and dispatches all
     * post-creation side effects (budget recheck, recurring linking, anomaly
     * detection) immediately after the database transaction commits.
     *
     * Delegates to [createExpenseStandaloneV2].
     *
     * @param request The creation request containing all expense fields.
     * @return A [CreateExpenseResult] indicating the outcome.
     */
    suspend fun createExpenseStandalone(request: CreateExpenseRequest): CreateExpenseResult {
        return createExpenseStandaloneV2(request)
    }

    /**
     * Creates an expense without dispatching any post-creation side effects.
     *
     * Use this when inserting inside an outer database transaction and you
     * need to defer side effects until after the outer transaction commits.
     * Call [dispatchPostCreationSideEffects] with the returned expense ID
     * after the outer transaction succeeds.
     *
     * Delegates to [createExpenseDbOnlyV2] and returns only the value.
     *
     * @param request The creation request containing all expense fields.
     * @return A [CreateExpenseResult] indicating the outcome.
     */
    suspend fun createExpenseDbOnly(request: CreateExpenseRequest): CreateExpenseResult {
        return createExpenseDbOnlyV2(request).value
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
        source: String = "USER_EDIT",
        correlationId: String? = null
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
                    reason = reason,
                    correlationId = correlationId  // DDL-F876-10
                )
            )
        }

        // Post-update side effects via planner + runner (best-effort, fire-and-forget)
        val batch = planner.planUpdated(expense.id, source, correlationId, TransactionUpdateKind.FULL)
        runner.runBestEffortAfterCommit(
            batch = batch,
            logMessage = "Non-critical: side effects failed after updating expense",
            targetId = expense.id
        )
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
        source: String = "USER_EDIT",
        correlationId: String? = null
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
                    reason = reason,
                    correlationId = correlationId
                )
            )
        }

        // Post-update side effects via planner + runner (best-effort)
        val batch = planner.planUpdated(expenseId, source, correlationId, TransactionUpdateKind.CATEGORY_ONLY)
        runner.runBestEffortAfterCommit(
            batch = batch,
            logMessage = "Non-critical: side effects failed after updating category for expense",
            targetId = expenseId
        )
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
     * Updates business flags (`isBusinessExpense` and `requiresReceipt`) on an
     * expense through the lifecycle coordinator.
     *
     * `businessUsePercent`, `taxCategory`, and `vatEligible` are accepted for
     * API compatibility but are **no-ops** pending entity schema changes. When
     * any of these no-op parameters are non-null, a warning is logged via Timber.w.
     *
     * TODO P2-CURRENT-014: businessUsePercent, taxCategory, vatEligible are semantically
     * partial — callers believe they are persisted but they are silently dropped.
     * Either add columns to the Expense entity or return a result indicating which
     * fields were actually persisted vs ignored.
     *
     * @param expenseId The ID of the expense to update.
     * @param isBusinessExpense Whether this expense is a business expense (persisted).
     * @param businessUsePercent No-op — accepted for API compatibility only.
     * @param taxCategory No-op — accepted for API compatibility only.
     * @param vatEligible No-op — accepted for API compatibility only.
     * @param receiptRequired Whether a receipt is required, mapped to `requiresReceipt` (persisted).
     * @param source The source system/component that triggered the update.
     */
    suspend fun updateBusinessFlags(
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

        if (businessUsePercent != null) {
            Timber.w("businessUsePercent=%.2f ignored — not persisted (pending entity schema changes)", businessUsePercent)
        }
        if (taxCategory != null) {
            Timber.w("taxCategory=%s ignored — not persisted (pending entity schema changes)", taxCategory)
        }
        if (vatEligible != null) {
            Timber.w("vatEligible=%s ignored — not persisted (pending entity schema changes)", vatEligible)
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

        // Post-update side effects via planner + runner (best-effort)
        val batch = planner.planUpdated(expenseId, source, null, TransactionUpdateKind.BUSINESS_FLAGS_ONLY)
        runner.runBestEffortAfterCommit(
            batch = batch,
            logMessage = "Non-critical: side effects failed after updating business/tax fields for expense",
            targetId = expenseId
        )

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
        source: String = "USER_EDIT",
        correlationId: String? = null
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

        // Post-update side effects via planner + runner (best-effort)
        val batch = planner.planUpdated(expenseId, source, correlationId, TransactionUpdateKind.MERCHANT)
        runner.runBestEffortAfterCommit(
            batch = batch,
            logMessage = "Non-critical: side effects failed after updating merchant for expense",
            targetId = expenseId
        )
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
        source: String = "USER_EDIT",
        correlationId: String? = null
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

        // Post-update side effects via planner + runner (best-effort)
        val batch = planner.planUpdated(expenseId, source, correlationId, TransactionUpdateKind.TYPE)
        runner.runBestEffortAfterCommit(
            batch = batch,
            logMessage = "Non-critical: side effects failed after updating type for expense",
            targetId = expenseId
        )
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

        // Post-update side effects via planner + runner (best-effort)
        val batch = planner.planUpdated(expenseId, source, null, TransactionUpdateKind.TRANSFER_DETAILS)
        runner.runBestEffortAfterCommit(
            batch = batch,
            logMessage = "Non-critical: side effects failed after updating transfer details for expense",
            targetId = expenseId
        )
    }

    /**
     * S5-010R: Atomically updates transaction type AND transfer metadata in one
     * DB transaction with one lifecycle event and one side-effect dispatch.
     * Replaces the two-call pattern (updateType + updateTransferDetails) that
     * could leave the row inconsistent if the second call failed.
     */
    suspend fun updateTypeAndTransferDetails(
        expenseId: Long,
        newType: TransactionType,
        transferDirection: com.yourname.expensetracker.data.database.entity.TransferDirection?,
        transferAccountName: String?,
        source: String = "USER_EDIT"
    ) {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }

        val existing = expenseDao.getById(expenseId) ?: return
        val now = timeProvider.now()
        val beforeSnapshot = expenseToSnapshot(existing)

        val newDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            existing.amount, existing.merchant, existing.date, existing.currency, newType
        )

        // Pre-check for duplicate collision only when type changes
        if (existing.transactionType != newType) {
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
        }

        val updated = existing.copy(
            transactionType = newType,
            dedupeKey = newDedupeKey,
            transferDirection = transferDirection,
            transferAccountName = transferAccountName
        )

        database.withTransaction {
            expenseDao.updateTransactionType(expenseId, newType.name, newDedupeKey)
            expenseDao.updateTransferDirection(expenseId, transferDirection?.name)
            expenseDao.updateTransferAccountName(expenseId, transferAccountName)
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
                    reason = null
                )
            )
        }

        // One side-effect dispatch after commit via planner + runner
        val batch = planner.planUpdated(expenseId, source, null, TransactionUpdateKind.FULL)
        runner.runBestEffortAfterCommit(
            batch = batch,
            logMessage = "Non-critical: side effects failed after updateTypeAndTransferDetails for expense",
            targetId = expenseId
        )
    }

    /**
     * Updates all six ownership fields atomically with [Expense.normalizeOwnership]
     * enforcement, with full lifecycle tracking. Writes a UPDATED TransactionEvent
     * with before/after snapshots.
     *
     * This is a compatibility wrapper that calls [updateOwnershipDbOnlyV2] and then
     * runs the returned post-commit action batch. It keeps existing callers working
     * while giving group code a safe DB-only method (the DbOnlyV2 variant) that
     * does NOT run side effects.
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
        val mutation = updateOwnershipDbOnlyV2(
            expenseId = expenseId,
            isNotMine = isNotMine,
            ownerName = ownerName,
            isSharedExpense = isSharedExpense,
            sharedWithName = sharedWithName,
            mySharePercentage = mySharePercentage,
            myShareAmount = myShareAmount,
            reason = reason,
            source = source,
            correlationId = null
        )
        if (mutation.value is OwnershipUpdateResult.Updated) {
            try {
                runner.run(mutation.postCommitActions)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Non-critical: side effects failed after updating ownership for expense %d", expenseId)
            }
        }
    }

    /**
     * DB-only ownership update: writes ownership fields + UPDATED lifecycle event
     * atomically inside a database transaction, then returns the post-update action
     * batch WITHOUT running it.
     *
     * Use this when calling from within an outer database transaction (e.g. group
     * coordinator). Run the returned actions via [PostCommitActionRunner] after the
     * outer transaction commits.
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
     * @param correlationId   Correlation ID for traceability (generated if null).
     * @return [MutationResult] containing [OwnershipUpdateResult] and post-commit actions.
     */
    suspend fun updateOwnershipDbOnlyV2(
        expenseId: Long,
        isNotMine: Boolean,
        ownerName: String?,
        isSharedExpense: Boolean,
        sharedWithName: String?,
        mySharePercentage: Int?,
        myShareAmount: Double?,
        reason: String? = null,
        source: String = "USER_EDIT",
        correlationId: String? = null
    ): MutationResult<OwnershipUpdateResult> {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }

        val corrId = correlationId ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()

        val existing = expenseDao.getById(expenseId)
            ?: return MutationResult(
                OwnershipUpdateResult.NotFound,
                PostCommitActionBatch.empty(corrId)
            )

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
        ) {
            return MutationResult(
                OwnershipUpdateResult.NoOp,
                PostCommitActionBatch.empty(corrId)
            )
        }

        val now = timeProvider.now()
        val beforeSnapshot = expenseToSnapshot(existing)

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
                    afterSnapshot = expenseToSnapshot(expenseId, normalized),
                    metadata = null,
                    reason = reason,
                    correlationId = corrId
                )
            )
        }

        val batch = planner.planUpdated(expenseId, source, corrId, TransactionUpdateKind.FULL)
        return MutationResult(OwnershipUpdateResult.Updated(expenseId), batch)
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
        reason: String? = null,
        correlationId: String? = null
    ) {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }
        val merchantKey = MerchantKeyGenerator.generate(merchant)
        val now = timeProvider.now()
        var affectedCount = 0

        database.withTransaction {
            affectedCount = expenseDao.updateCategoryForMerchant(merchantKey, newCategoryId)
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
                    put("affectedCount", affectedCount)
                }.toString(),
                reason = reason,
                correlationId = correlationId  // DDL-C67-10
            ))
        }

        // P2-07: Dispatch single aggregate post-commit recalculation for bulk updates.
        if (affectedCount > 0) {
            dispatchBulkPostCommitSideEffects(source, affectedCount)
        }
    }

    /**
     * P2-07: Aggregate post-commit recalculation for bulk operations.
     * Instead of per-row side effects flooded across N expenses, dispatch a
     * single budget recheck and cache invalidation. This prevents storms while
     * still ensuring holistic state freshness.
     */
    private suspend fun dispatchBulkPostCommitSideEffects(source: String, affectedCount: Int) {
        val batch = planner.planBulkUpdated(source, affectedCount, null)
        try {
            runner.run(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Non-critical: aggregate bulk side effects failed (affectedCount=%d)", affectedCount)
        }
    }

    /**
     * C11-FIXED: Basic bulk category update with lifecycle events.
     * Moves all expenses from one category to another, writing per-expense
     * UPDATED events via [updateCategory] so that side effects are dispatched.
     *
     * TODO P2-CURRENT-015: This is non-atomic — a crash mid-loop leaves partial
     * reassignment. Wrap in database.withTransaction or use a single DAO UPDATE
     * with a BULK_UPDATED event (like the merchant-key overload above).
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
        reason: String? = null,
        correlationId: String? = null
    ) {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            throw IllegalStateException("Database writes blocked during restore")
        }
        if (oldMerchant == newMerchant) return
        val oldMerchantKey = MerchantKeyGenerator.generate(oldMerchant)
        val newMerchantKey = MerchantKeyGenerator.generate(newMerchant)
        val now = timeProvider.now()
        var affectedCount = 0

        database.withTransaction {
            // Fetch affected rows inside transaction to prevent TOCTOU race
            val affectedExpenses = expenseDao.getExpensesByMerchantKey(oldMerchantKey)
            if (affectedExpenses.isEmpty()) return@withTransaction
            affectedCount = affectedExpenses.size

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
                put("affectedCount", affectedCount)
            }.toString()
            transactionEventDao.insert(TransactionEvent(
                expenseId = null,
                eventType = LifecycleEventType.BULK_UPDATED.name,
                source = source, actor = null, occurredAt = now,
                dedupeKey = null, duplicateExpenseId = null,
                beforeSnapshot = null, afterSnapshot = null,
                metadata = metadata,
                reason = reason,
                correlationId = correlationId  // DDL-C67-10
            ))
        }

        // P2-07: Dispatch single aggregate post-commit recalculation for bulk updates.
        dispatchBulkPostCommitSideEffects(source, affectedCount)
    }

    /**
     * Deletes an expense by its ID with full lifecycle handling:
     * load → write DELETED event → delete.
     *
     * ## Delete Semantics
     * - **Hard delete** is the chosen strategy. The row is permanently removed
     *   from the `expenses` table. There is no undo or trash folder at the DB
     *   level — callers must implement their own confirmation UI.
     * - **Audit trail** is preserved via [TransactionEvent.beforeSnapshot]:
     *   the full expense snapshot (amount, merchant, currency, category, etc.)
     *   is written to the `transaction_events` table with eventType = DELETED
     *   BEFORE the row is removed. This snapshot is the authoritative record
     *   of what was deleted.
     * - **Receipt/group/recurring links** are NOT cleaned up by the delete
     *   path itself. They are managed by post-delete side effects:
     *   [TransactionSideEffectDispatcher.dispatchOnDeleted] handles budget
     *   re-check and anomaly clearing, while
     *   [RecurringLifecycleCoordinator.unlinkExpenseFromOccurrence] detaches
     *   the expense from any recurring rule. Receipt links and group settlement
     *   references must be handled by their respective domains.
     * - **No soft-delete** (`deletedAt` column) is planned. The chosen approach
     *   relies on the event audit trail for recovery and avoids the complexity
     *   of filtering soft-deleted rows from every query.
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
        actor: String? = null,
        correlationId: String? = null
    ): Result<Unit> {
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            return Result.failure(IllegalStateException("Database writes blocked during restore"))
        }
        // P2-08: Load snapshot inside the transaction to prevent TOCTOU stale snapshots.
        val now = timeProvider.now()
        return try {
            var loadedExpense: Expense? = null
            database.withTransaction {
                loadedExpense = expenseDao.getById(expenseId)
                    ?: return@withTransaction
                val snapshot = expenseToSnapshot(loadedExpense!!)
                transactionEventDao.insert(
                    TransactionEvent(
                        expenseId = expenseId,
                        eventType = LifecycleEventType.DELETED.name,
                        source = source,
                        actor = actor,
                        occurredAt = now,
                        dedupeKey = loadedExpense!!.dedupeKey,
                        duplicateExpenseId = null,
                        beforeSnapshot = snapshot,
                        afterSnapshot = null,
                        metadata = null,
                        reason = reason,
                        correlationId = correlationId  // DDL-F876-10
                    )
                )
                expenseDao.delete(loadedExpense!!)
            }
            if (loadedExpense == null) {
                return Result.failure(IllegalArgumentException("Expense not found: $expenseId"))
            }
            // Post-delete side effects via planner + runner (best-effort)
            val batch = planner.planDeleted(expenseId, source, correlationId)
            runner.runBestEffortAfterCommit(
                batch = batch,
                logMessage = "Non-critical: side effects failed after deleting expense",
                targetId = expenseId
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
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
        actor: String? = null,
        correlationId: String? = null
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
                        reason = reason,
                        correlationId = correlationId  // DDL-C67-10
                    )
                )

                expenseDao.delete(expense)
            }

            // Post-delete side effects via planner + runner (best-effort)
            val batch = planner.planDeleted(expense.id, source, correlationId)
            runner.runBestEffortAfterCommit(
                batch = batch,
                logMessage = "Non-critical: side effects failed after deleting expense",
                targetId = expense.id
            )

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
        // TODO P2-CURRENT-020: Make future-date tolerance configurable (e.g. via policy object).
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
     *
     * ## Event Insert Failure Policy
     *
     * For **attempt-only** events (CREATE_ATTEMPTED, CREATE_VALIDATION_FAILED,
     * CREATE_DUPLICATE_SKIPPED, CREATE_INSERT_CONFLICT), insert failure is
     * **best-effort** — the failure is logged and the return value is `false`,
     * but the primary operation (expense create, duplicate skip) continues.
     * These events are diagnostic aids and losing one does not corrupt state.
     *
     * For **business mutation** events (CREATED, UPDATED, DELETED), insert
     * failure is **REQUIRED** — the insert happens inside the same
     * `database.withTransaction` block as the primary mutation, so a failure
     * rolls back the entire transaction. This ensures that every state-changing
     * operation has a durable audit record.
     *
     * @return `true` if the event was written successfully, `false` if the
     *         insert failed (best-effort — the caller may continue safely).
     */
    private suspend fun writeDuplicateEvent(
        expense: Expense,
        request: CreateExpenseRequest,
        occurredAt: Long,
        duplicateExpenseId: Long?,
        reason: String
    ): Boolean {
        val sourceLinkPayloads = CreateExpenseSourceLinkMapper.fromRequest(request)
        val metadata = SourceLinkEventMetadataBuilder.duplicateMetadata(
            policy = getDuplicateSourceLinkPolicy(request.source),
            attemptedExpense = expense,
            sourceLinkPayloads = sourceLinkPayloads
        )

        // DDL-512-06: propagate correlationId so duplicate skips are traceable
        val correlationId = request.correlationId ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()

        return try {
            transactionEventDao.insert(
                TransactionEvent(
                    expenseId = duplicateExpenseId,
                    eventType = LifecycleEventType.CREATE_DUPLICATE_SKIPPED.name,
                    source = request.source.name,
                    actor = null,
                    occurredAt = occurredAt,
                    dedupeKey = expense.dedupeKey,
                    duplicateExpenseId = duplicateExpenseId,
                    beforeSnapshot = null,
                    afterSnapshot = null,
                    metadata = metadata,
                    reason = reason,
                    correlationId = correlationId
                )
            )
            true
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Timber.w(error, "Failed to write duplicate-skip event for expense")
            false
        }
    }

    /**
     * Returns the [DuplicateSourceLinkPolicy] for a given [ExpenseSource],
     * determining how source links should be handled when a duplicate expense
     * is detected.
     *
     * - [DuplicateSourceLinkPolicy.LINK_SOURCE_TO_EXISTING]: The source should be
     *   linked to the existing expense with a [com.yourname.expensetracker.domain.provenance.SourceLinkRole.DUPLICATE_MATCHED] role.
     *   Currently deferred — the policy decision is recorded in the duplicate event metadata.
     * - [DuplicateSourceLinkPolicy.RECORD_ATTEMPT_ONLY]: No source link is created;
     *   the existing duplicate event is sufficient.
     * - [DuplicateSourceLinkPolicy.DO_NOT_LINK]: No linking at all.
     */
    private fun getDuplicateSourceLinkPolicy(source: ExpenseSource): DuplicateSourceLinkPolicy {
        return when (source) {
            ExpenseSource.RECEIPT_SCAN -> DuplicateSourceLinkPolicy.LINK_SOURCE_TO_EXISTING
            ExpenseSource.EMAIL_RECEIPT -> DuplicateSourceLinkPolicy.LINK_SOURCE_TO_EXISTING
            ExpenseSource.BANK_SYNC -> DuplicateSourceLinkPolicy.LINK_SOURCE_TO_EXISTING
            ExpenseSource.BANK_API_SYNC -> DuplicateSourceLinkPolicy.LINK_SOURCE_TO_EXISTING
            ExpenseSource.BANK_STATEMENT_REVIEW -> DuplicateSourceLinkPolicy.LINK_SOURCE_TO_EXISTING
            ExpenseSource.REVIEW_APPROVAL -> DuplicateSourceLinkPolicy.LINK_SOURCE_TO_EXISTING
            ExpenseSource.CSV_IMPORT -> DuplicateSourceLinkPolicy.LINK_SOURCE_TO_EXISTING
            ExpenseSource.NOTIFICATION_AUTO_ACCEPT -> DuplicateSourceLinkPolicy.RECORD_ATTEMPT_ONLY
            ExpenseSource.MANUAL_ENTRY -> DuplicateSourceLinkPolicy.DO_NOT_LINK
            ExpenseSource.MANUAL -> DuplicateSourceLinkPolicy.DO_NOT_LINK
            else -> DuplicateSourceLinkPolicy.RECORD_ATTEMPT_ONLY
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

/**
 * Result of a DB-only ownership update operation.
 * Returned by [TransactionLifecycleCoordinator.updateOwnershipDbOnlyV2].
 */
sealed interface OwnershipUpdateResult {
    /** The expense was found and its ownership fields were updated. */
    data class Updated(val expenseId: Long) : OwnershipUpdateResult

    /** The expense was found but no ownership fields changed (no-op). */
    data object NoOp : OwnershipUpdateResult

    /** The expense was not found in the database. */
    data object NotFound : OwnershipUpdateResult
}
