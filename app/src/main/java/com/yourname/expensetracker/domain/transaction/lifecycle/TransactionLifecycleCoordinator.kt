package com.yourname.expensetracker.domain.transaction.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.provenance.CreateExpenseSourceLinkMapper
import com.yourname.expensetracker.domain.provenance.CreateExpenseSourceLinkRequirements
import com.yourname.expensetracker.domain.provenance.DuplicateSourceLinkPolicy
import com.yourname.expensetracker.domain.provenance.SourceLinkEventMetadataBuilder
import com.yourname.expensetracker.domain.provenance.SourceLinkFallbackPolicy
import com.yourname.expensetracker.domain.provenance.SourceLinkPayload
import com.yourname.expensetracker.domain.provenance.SourceLinkWriteResult
import com.yourname.expensetracker.domain.provenance.SourceLinkWriteException
import com.yourname.expensetracker.domain.provenance.SourceLinkWriter
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.sideeffect.MutationResult
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.runBestEffortAfterCommit
import kotlinx.coroutines.CancellationException
import com.yourname.expensetracker.domain.transaction.BusinessExpensePatch
import com.yourname.expensetracker.domain.transaction.BusinessExpenseUpdateResult
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.InsertConflictCodes
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.transaction.SideEffectMode
import com.yourname.expensetracker.domain.transaction.validation.TransactionValidationError
import com.yourname.expensetracker.domain.transaction.validation.TransactionValidationException
import com.yourname.expensetracker.domain.transaction.validation.TransactionValidator
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
 * Validation is delegated to TransactionValidator for shared create/update rules.
 * Write guards use DatabaseWriteBarrier for centralized blocking.
 * Restore-blocked creates emit durable diagnostics via DiagnosticEventWriter.
 *
 * @constructor Inject dependencies needed for validation, persistence, and event logging.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
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
    private val writeBarrier: DatabaseWriteBarrier,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val sourceLinkWriter: SourceLinkWriter,
    private val transactionValidator: TransactionValidator,
    private val diagnosticEventWriter: DiagnosticEventWriter
) {
    // ---- Write-barrier guard ----

    // ---- Canonical dedupe key helpers ----

    private fun strictExternalIdentityKey(request: CreateExpenseRequest): String? {
        return request.idempotencyKey
            ?.takeIf { it.isNotBlank() }
            ?: request.externalFingerprint?.takeIf { it.isNotBlank() }
    }

    private fun strictExternalDedupeKey(request: CreateExpenseRequest): String? {
        val key = strictExternalIdentityKey(request) ?: return null
        return "idem:${request.source.name}:$key"
    }

    private fun standardCreateDedupeKey(request: CreateExpenseRequest): String {
        return DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount = request.amount,
            merchant = request.merchant,
            date = request.date,
            currency = request.currency,
            transactionType = request.transactionType
        )
    }

    private fun createAttemptDedupeKey(request: CreateExpenseRequest): String? {
        return when (request.deduplicationMode) {
            DeduplicationMode.STRICT_EXTERNAL_ID -> strictExternalDedupeKey(request)
            else -> standardCreateDedupeKey(request)
        }
    }

    // ---- Duplicate/conflict resolution helpers ----

    private suspend fun findDuplicateIdForExpense(expense: Expense): Long? {
        val byPolicy = expenseDao.findDuplicateIdCurrencyAware(
            amount = expense.amount,
            merchant = expense.merchant,
            date = expense.date,
            currency = expense.currency,
            transactionType = expense.transactionType.name,
            merchantKey = expense.merchantKey,
            dedupeKey = expense.dedupeKey
        )

        if (byPolicy != null) return byPolicy

        return expense.dedupeKey
            ?.takeIf { it.isNotBlank() }
            ?.let { expenseDao.findIdByDedupeKey(it) }
    }

    /**
     * P2-002: resolves the existing expense ID after an insert conflict, in the
     * order of the identity that most plausibly rejected the insert:
     * rawNotificationId (unique index) → dedupeKey (unique index) →
     * bounded fuzzy window (STANDARD/BULK_IMPORT only; STRICT modes never
     * fuzzy-match). Returns the resolved ID (or null) paired with a controlled
     * [InsertConflictCodes] constant describing how the ID was proven.
     */
    private suspend fun resolveExistingIdAfterInsertConflict(
        expense: Expense,
        dedupMode: DeduplicationMode
    ): Pair<Long?, String> {
        // 1. Exact source-identity lookup first — the unique rawNotificationId
        //    index may be the index that actually rejected the insert.
        //    P2-008 (11c): exact identity lookups intentionally include
        //    isNotMine rows; the not-mine exclusion applies to the fuzzy
        //    resolver's candidate SQL only (ExpenseDao.getDuplicateCandidateBy*).
        if (expense.rawNotificationId != null) {
            val byRawNotificationId = expenseDao.findIdByRawNotificationId(expense.rawNotificationId)
            if (byRawNotificationId != null) {
                return Pair(byRawNotificationId, InsertConflictCodes.RESOLVED_RAW_NOTIFICATION_ID)
            }
        }

        // 2. Exact dedupe-key lookup.
        val byDedupeKey = expense.dedupeKey
            ?.takeIf { it.isNotBlank() }
            ?.let { expenseDao.findIdByDedupeKey(it) }

        if (byDedupeKey != null) {
            return Pair(byDedupeKey, InsertConflictCodes.RESOLVED_DEDUPE_KEY)
        }

        // 3. STRICT_EXTERNAL_ID should not fuzzy-match.
        if (dedupMode == DeduplicationMode.STRICT_EXTERNAL_ID) {
            return Pair(null, InsertConflictCodes.UNRESOLVED)
        }

        // 4. Debug restore intentionally skips dedupe.
        if (dedupMode == DeduplicationMode.SKIP_FOR_DEBUG_RESTORE) {
            return Pair(null, InsertConflictCodes.UNRESOLVED)
        }

        // 5. STANDARD/BULK fallback: resolve by same policy as duplicate precheck.
        //    P2-008 (11c): the fuzzy resolver never returns a not-mine row —
        //    the guard lives in the resolution/suggestion candidate SQL
        //    (ExpenseDao.getDuplicateCandidateBy*CurrencyAware, isNotMine = 0);
        //    the blocking precheck family intentionally still includes not-mine rows.
        val byFuzzyWindow = expenseDao.findDuplicateIdCurrencyAware(
            amount = expense.amount,
            merchant = expense.merchant,
            date = expense.date,
            currency = expense.currency,
            transactionType = expense.transactionType.name,
            merchantKey = expense.merchantKey,
            dedupeKey = expense.dedupeKey
        )
        return if (byFuzzyWindow != null) {
            Pair(byFuzzyWindow, InsertConflictCodes.RESOLVED_FUZZY_WINDOW)
        } else {
            Pair(null, InsertConflictCodes.UNRESOLVED)
        }
    }

    // ---- Diagnostics helpers ----

    private fun createBlockedDiagnosticMetadata(
        request: CreateExpenseRequest,
        operation: String,
        blocked: Throwable
    ): SafeEventMetadata {
        return SafeEventMetadata.builder()
            .put("operation", operation)
            .put("source", request.source.name)
            .put("deduplicationMode", request.deduplicationMode.name)
            .put("transactionType", request.transactionType.name)
            .put("currency", request.currency)
            .put("hasIdempotencyKey", request.idempotencyKey != null)
            .put("hasExternalFingerprint", request.externalFingerprint != null)
            .put("exceptionClass", blocked.javaClass.simpleName)
            .build()
    }

    private suspend fun emitCreateBlockedDiagnosticBestEffort(
        request: CreateExpenseRequest,
        correlationId: String,
        blocked: Throwable
    ): Boolean {
        return try {
            diagnosticEventWriter.emit(
                DiagnosticEvent(
                    pipeline = AppPipeline.TRANSACTION,
                    stage = "CREATE_EXPENSE",
                    outcome = EventOutcome.BLOCKED,
                    severity = EventSeverity.WARNING,
                    reasonCode = when (blocked) {
                        is DatabaseAccessBlockedException -> DiagnosticReasonCode.RESTORE_BLOCKED
                        else -> DiagnosticReasonCode.WRITE_BARRIER_DENIED
                    },
                    entityType = "Expense",
                    entityId = null,
                    sourceType = request.source.name,
                    correlationId = correlationId,
                    metadata = createBlockedDiagnosticMetadata(
                        request = request,
                        operation = "createExpense",
                        blocked = blocked
                    ),
                    exception = blocked,
                    isTerminal = true
                )
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to emit restore-blocked create diagnostic")
            false
        }
    }

    /**
     * NEW-P2-016: bounded best-effort diagnostic for an unresolvable home
     * currency (DataStore error/timeout). Controlled constant reason code
     * only — never raw settings/exception text. Preserves caller cancellation.
     */
    private suspend fun emitHomeCurrencyUnavailableDiagnosticBestEffort(
        operation: String,
        entityType: String,
        entityId: Long?
    ): Boolean {
        return try {
            diagnosticEventWriter.emit(
                DiagnosticEvent(
                    pipeline = AppPipeline.TRANSACTION,
                    stage = operation,
                    outcome = EventOutcome.FAILED_FINAL,
                    severity = EventSeverity.WARNING,
                    reasonCode = DiagnosticReasonCode.HOME_CURRENCY_UNAVAILABLE,
                    entityType = entityType,
                    entityId = entityId,
                    metadata = SafeEventMetadata.builder()
                        .put("operation", operation)
                        .put("reason", HomeCurrencyFailureReason.HOME_CURRENCY_UNAVAILABLE)
                        .build(),
                    isTerminal = false
                )
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to emit home-currency-unavailable diagnostic")
            false
        }
    }

    /**
     * Best-effort event write that preserves caller cancellation (U-001).
     *
     * [transactionEventDao.insert] is suspend, so a raw [runCatching] would
     * trap a [CancellationException] in a discarded result and let the
     * mutation continue after the caller is gone. Returns null on
     * non-cancellation failure; callers keep their existing bounded
     * diagnostics for that case.
     */
    private suspend fun <T> bestEffortEvent(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Internal DB-only create mutation. Validates, normalizes, dedupes, inserts
     * atomically (expense + CREATED event + source links), and returns the planned
     * side-effect batch. Side effects are NEVER executed here — callers must dispatch.
     */
    private suspend fun createExpenseMutation(
        request: CreateExpenseRequest
    ): Pair<CreateExpenseResult, PostCommitActionBatch> {
        val correlationId = request.correlationId
            ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()

        // Guard: block writes during restore maintenance mode
        try {
            writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.createExpense")
        } catch (blocked: DatabaseAccessBlockedException) {
            emitCreateBlockedDiagnosticBestEffort(
                request = request,
                correlationId = correlationId,
                blocked = blocked
            )
            return Pair(CreateExpenseResult.Error(blocked), PostCommitActionBatch.empty(correlationId))
        } catch (blocked: RuntimeException) {
            if (blocked is CancellationException) throw blocked
            emitCreateBlockedDiagnosticBestEffort(
                request = request,
                correlationId = correlationId,
                blocked = blocked
            )
            return Pair(CreateExpenseResult.Error(blocked), PostCommitActionBatch.empty(correlationId))
        }

        val now = timeProvider.now()

        // DDL-512-06: correlationId generated before barrier check, reused for all events

        // Compute source-link payloads once — used across all events
        val sourceLinkPayloads = CreateExpenseSourceLinkMapper.fromRequest(request)

        // 1. Write CREATE_ATTEMPTED event before validation
        // Canonical attempt dedupe key: matches the persisted key for STRICT_EXTERNAL_ID
        val attemptDedupeKey = createAttemptDedupeKey(request)
        bestEffortEvent {
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
        val validationErrors = validate(request)
        if (validationErrors.isNotEmpty()) {
            bestEffortEvent {
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
                            errors = validationErrors,
                            payloads = sourceLinkPayloads
                        ),
                        reason = "Validation failed: ${validationErrors.first()}",
                        correlationId = correlationId  // DDL-512-06
                    )
                )
            }
            return Pair(CreateExpenseResult.ValidationFailed(validationErrors), PostCommitActionBatch.empty(correlationId))
        }

        // 2b. Provenance validation — fail if source-specific fields are missing
        if (request.sourceLinkFallbackPolicy != SourceLinkFallbackPolicy.LEGACY_BACKFILL_ONLY) {
            val missingSourceFields = CreateExpenseSourceLinkRequirements.missingRequirements(request)
            if (missingSourceFields.isNotEmpty()) {
                val provenanceErrors = listOf("Missing source provenance fields for ${request.source}: ${missingSourceFields.joinToString(",")}")
                try {
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
                                errors = provenanceErrors,
                                payloads = sourceLinkPayloads
                            ),
                            reason = "Provenance validation failed: ${provenanceErrors.first()}",
                            correlationId = correlationId
                        )
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.w(e, "Failed to write CREATE_VALIDATION_FAILED for provenance failure")
                }
                return Pair(CreateExpenseResult.ValidationFailed(provenanceErrors), PostCommitActionBatch.empty(correlationId))
            }
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
        // NEW-P2-016: resolveHomeCurrency() is the typed contract — Resolved and
        // FirstRunDefault proceed as before; Failed (including DataStore read
        // timeout) fails CLOSED: no silent DEFAULT_BASE_CURRENCY substitution
        // and no fabricated base snapshot. Conversion fields are marked with the
        // existing sentinels (baseAmount <= 0.0 → downstream fallback to raw
        // effectiveAmount) and a bounded controlled diagnostic is recorded.
        val homeCurrency = when (val resolution = currencySettingsRepository.resolveHomeCurrency()) {
            is HomeCurrencyResolution.Resolved -> resolution.currency.code
            is HomeCurrencyResolution.FirstRunDefault -> resolution.currency.code
            is HomeCurrencyResolution.Failed -> null
        }
        if (homeCurrency == null) {
            expense = expense.copy(
                baseAmount = 0.0,
                baseCurrency = "",
                exchangeRateUsed = 0.0
            )
            emitHomeCurrencyUnavailableDiagnosticBestEffort(
                operation = "createExpense",
                entityType = "Expense",
                entityId = null
            )
        } else if (expense.currency != homeCurrency) {
            val conversion = try {
                currencyConverter.convertAsOf(
                    amount = expense.amount,
                    fromCurrency = expense.currency,
                    toCurrency = homeCurrency,
                    atMillis = expense.date
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
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
        // P2-004: preflight-only bypass. The nullable rawNotificationId and unique
        // dedupeKey DB constraints are ALWAYS enforced — the database still returns
        // a typed duplicate/conflict when an identity collides.
        val skipDedup = request.skipPreflightDeduplication

        if (!skipDedup) {
            when (dedupMode) {
                DeduplicationMode.STRICT_EXTERNAL_ID -> {
                    val strictKey = strictExternalDedupeKey(request)
                    if (strictKey == null) {
                        // P2-CURRENT-010: Emit validation event for missing key
                        bestEffortEvent {
                            transactionEventDao.insert(
                                TransactionEvent(
                                    expenseId = null,
                                    eventType = LifecycleEventType.CREATE_VALIDATION_FAILED.name,
                                    source = request.source.name,
                                    actor = null,
                                    occurredAt = now,
                                    dedupeKey = null,
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
                        return Pair(CreateExpenseResult.ValidationFailed(
                            listOf("STRICT_EXTERNAL_ID mode requires idempotencyKey or externalFingerprint")
                        ), PostCommitActionBatch.empty(correlationId))
                    }
                    // Use canonical strict key
                    expense = expense.copy(dedupeKey = strictKey)
                    // Don't run range check, rely on unique dedupeKey index
                }

                DeduplicationMode.BULK_IMPORT -> {
                    // NEW-P2-004: Moved inside transaction to prevent TOCTOU race
                }

                DeduplicationMode.SKIP_FOR_DEBUG_RESTORE -> {
                    // Skip all deduplication entirely
                }

                DeduplicationMode.STANDARD -> {
                    // NEW-P2-004: Moved inside transaction to prevent TOCTOU race
                }
            }
        }

        // 5. Insert + event inside a single database transaction
        //    Dedup check (STANDARD/BULK_IMPORT) is inside the transaction to prevent TOCTOU race.
        //    Side effects (step 7, 8) remain outside the transaction (post-commit).
        // GR-14p-a: canonical direct scope — the mutations' proof is local
        // to the legal writer, independent of caller context.
        val insertedId = writeBarrier.runWrite(
            DatabaseAccessOperation(
                "TransactionLifecycleCoordinator.createExpenseMutation"
            )
        ) {
            database.withTransaction {
            // NEW-P2-004: Dedup check inside transaction to prevent race condition
            if (!skipDedup) {
                when (dedupMode) {
                    DeduplicationMode.BULK_IMPORT, DeduplicationMode.STANDARD -> {
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
                            val duplicateId = findDuplicateIdForExpense(expense)
                            val label = if (dedupMode == DeduplicationMode.BULK_IMPORT) "Bulk import duplicate" else "Standard duplicate"
                            writeDuplicateEvent(expense, request, now, duplicateId, label, correlationId)
                            return@withTransaction -(duplicateId ?: 1L)
                        }
                    }
                    else -> { /* STRICT_EXTERNAL_ID handled above, SKIP_FOR_DEBUG_RESTORE = no-op */ }
                }
            }

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
        }

        if (insertedId <= 0L) {
            // P2-002: Try to resolve the existing expense ID before declaring
            // an unresolved conflict. [resolutionCode] records which identity
            // actually proved the conflicting row (controlled constant only).
            val (existingId, resolutionCode) =
                resolveExistingIdAfterInsertConflict(expense, dedupMode)

            if (existingId != null) {
                // Resolved — treat as duplicate
                val duplicateEventOutcome = bestEffortEvent {
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
                            reason = when (dedupMode) {
                                DeduplicationMode.STRICT_EXTERNAL_ID ->
                                    "STRICT_EXTERNAL_ID idempotent retry resolved to existing expense"
                                DeduplicationMode.BULK_IMPORT ->
                                    "BULK_IMPORT insert conflict resolved to existing expense"
                                else ->
                                    "Insert conflict resolved to existing duplicate expense"
                            },
                            correlationId = correlationId
                        )
                    )
                    true
                }
                if (duplicateEventOutcome == null) {
                    Timber.w(
                        "CREATE_DUPLICATE_SKIPPED event write failed for expense %d",
                        existingId
                    )
                }
                val eventLogged = duplicateEventOutcome != null

                return Pair(CreateExpenseResult.DuplicateSkipped(
                    existingExpenseId = existingId,
                    reason = "Insert conflict resolved to existing expense $existingId",
                    eventLogged = eventLogged
                ), PostCommitActionBatch.empty(correlationId))
            }

            // Unresolved — write INSERT_CONFLICT
            bestEffortEvent {
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
                        reason = "Unresolved insert conflict for dedupeKey=${expense.dedupeKey}",
                        correlationId = correlationId
                    )
                )
            }
            return Pair(
                CreateExpenseResult.InsertConflict(
                    dedupeKey = expense.dedupeKey ?: "unknown",
                    reasonCode = resolutionCode
                ),
                PostCommitActionBatch.empty(correlationId)
            )
        }

        // 6. Plan side effects — always deferred to caller
        val plannedBatch = if (insertedId > 0L) {
            planner.planCreated(insertedId, request.source, correlationId)
        } else {
            PostCommitActionBatch.empty(correlationId)
        }

        return Pair(CreateExpenseResult.Created(insertedId), plannedBatch)
    }

    // ── V2 create API ────────────────────────────────────────────────────────

    /**
     * @deprecated Use [createExpenseStandaloneV2] or [createExpenseDbOnlyV2].
     */
    @Deprecated(
        "Prefer createExpenseStandaloneV2() or createExpenseDbOnlyV2().",
        level = DeprecationLevel.ERROR
    )
    suspend fun createExpense(request: CreateExpenseRequest): CreateExpenseResult {
        return createExpenseStandaloneV2(request)
    }

    /**
     * Legacy overload with SideEffectMode for backward compatibility.
     * @deprecated Use [createExpenseStandaloneV2] or [createExpenseDbOnlyV2].
     */
    @Deprecated(
        "Prefer createExpenseStandaloneV2() or createExpenseDbOnlyV2(). SideEffectMode is obsolete.",
        level = DeprecationLevel.ERROR
    )
    suspend fun createExpense(
        request: CreateExpenseRequest,
        @Suppress("UNUSED_PARAMETER") sideEffectMode: SideEffectMode
    ): CreateExpenseResult {
        return createExpenseStandaloneV2(request)
    }

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
        val (result, batch) = createExpenseMutation(request)
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
        val (result, batch) = createExpenseMutation(request)
        if (result is CreateExpenseResult.Created) {
            runner.run(batch)
        }
        return result
    }

    // ── Compatibility wrappers ───────────────────────────────────────────────

    /**
     * Dispatch post-creation side effects for an already-committed expense.
     *
     * Call this after your outer `database.withTransaction` has committed.
     * Best-effort: failures are logged but do not propagate.
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
        writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.updateExpense")

        val now = timeProvider.now()

        // ── Currency conversion snapshot (may do network I/O — stays outside txn) ──
        // NEW-P2-016: typed home-currency resolution — Resolved and FirstRunDefault
        // proceed as before; Failed (including DataStore read timeout) fails CLOSED:
        // no silent DEFAULT_BASE_CURRENCY substitution, no fabricated base snapshot.
        // Conversion fields are marked with the existing sentinels (baseAmount <= 0.0
        // → downstream fallback to raw effectiveAmount) and a bounded controlled
        // diagnostic is recorded.
        val homeCurrencyUpdate = when (val resolution = currencySettingsRepository.resolveHomeCurrency()) {
            is HomeCurrencyResolution.Resolved -> resolution.currency.code
            is HomeCurrencyResolution.FirstRunDefault -> resolution.currency.code
            is HomeCurrencyResolution.Failed -> {
                emitHomeCurrencyUnavailableDiagnosticBestEffort(
                    operation = "updateExpense",
                    entityType = "Expense",
                    entityId = expense.id
                )
                null
            }
        }
        val preComputedConversion = if (homeCurrencyUpdate != null && expense.currency != homeCurrencyUpdate) {
            try {
                currencyConverter.convertAsOf(
                    amount = expense.amount,
                    fromCurrency = expense.currency,
                    toCurrency = homeCurrencyUpdate,
                    atMillis = expense.date
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        } else null

        // 3. Persist inside a single transaction (TOCTOU-safe: read + write atomic)
        // GR-14p-a: canonical direct scope — the mutations' proof is local
        // to the legal writer, independent of caller context.
        writeBarrier.runWrite(
            DatabaseAccessOperation(
                "TransactionLifecycleCoordinator.updateExpense"
            )
        ) {
            database.withTransaction {
            val existing = expenseDao.getById(expense.id)
                ?: throw IllegalArgumentException("Expense not found: ${expense.id}")
            val beforeSnapshot = expenseToSnapshot(existing)

            // Recompute dedupeKey if key fields changed
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

                // Duplicate check inside transaction
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
                    // RP-11 FIX 2: blocking-consistent (not-mine-INCLUSIVE) lookup —
                    // the fuzzy resolver would miss a not-mine row occupying the
                    // target identity and the write would die on the raw unique index.
                    val dupId = expenseDao.findBlockingDuplicateIdCurrencyAware(
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

            // Apply currency conversion (pre-computed outside txn).
            // NEW-P2-016: homeCurrencyUpdate == null (Failed resolution) falls
            // through to the sentinel-clearing branch below — no invented base.
            val finalExpense = if (homeCurrencyUpdate != null &&
                expense.currency != homeCurrencyUpdate && preComputedConversion != null
            ) {
                updatedExpense.copy(
                    baseAmount = preComputedConversion.convertedAmount,
                    baseCurrency = homeCurrencyUpdate,
                    exchangeRateUsed = preComputedConversion.rateUsed
                )
            } else if (expense.currency == homeCurrencyUpdate) {
                updatedExpense.copy(
                    baseAmount = updatedExpense.amount,
                    baseCurrency = updatedExpense.currency,
                    exchangeRateUsed = 1.0
                )
            } else {
                // P2-PR1 (NEW-P2-007): Conversion failed and currency differs from home.
                // Clear stale baseAmount/baseCurrency/exchangeRateUsed to sentinel values
                // so downstream consumers fall back to raw effectiveAmount (baseAmount <= 0.0
                // triggers fallback — see Expense.normalizedAmount).
                updatedExpense.copy(
                    baseAmount = 0.0,
                    baseCurrency = "",
                    exchangeRateUsed = 0.0
                )
            }

            // Validate final expense state
            val finalValidationErrors = transactionValidator.validateFinalExpenseState(
                amount = finalExpense.amount,
                merchant = finalExpense.merchant,
                currency = finalExpense.currency,
                date = finalExpense.date,
                transactionType = finalExpense.transactionType,
                transferDirectionPresent = finalExpense.transferDirection != null,
                transferAccountName = finalExpense.transferAccountName,
                isNotMine = finalExpense.isNotMine,
                isSharedExpense = finalExpense.isSharedExpense,
                latitude = finalExpense.latitude,
                longitude = finalExpense.longitude
            )
            if (finalValidationErrors.isNotEmpty()) {
                throw TransactionValidationException(finalValidationErrors)
            }

            expenseDao.update(finalExpense)

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
                    correlationId = correlationId  // DDL-C67-10
                )
            )
            }
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
        writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.updateCategory")

        val now = timeProvider.now()

        // GR-14p-a: canonical direct scope — the mutations' proof is local
        // to the legal writer, independent of caller context.
        //
        // P2-005: typed outcome from the transaction — only a real update
        // dispatches; unchanged values are idempotent no-ops; missing rows
        // are typed failures. No event or dispatch for NoChange/NotFound.
        var outcome = ExpenseUpdateOutcome.NotFound
        writeBarrier.runWrite(
            DatabaseAccessOperation(
                "TransactionLifecycleCoordinator.updateCategory"
            )
        ) {
            database.withTransaction {
            val existing = expenseDao.getById(expenseId)
            if (existing == null) {
                outcome = ExpenseUpdateOutcome.NotFound
                return@withTransaction
            }
            if (existing.categoryId == newCategoryId) {
                outcome = ExpenseUpdateOutcome.NoChange
                return@withTransaction
            }

            val beforeSnapshot = expenseToSnapshot(existing)
            val updated = existing.copy(categoryId = newCategoryId)

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
            outcome = ExpenseUpdateOutcome.Updated
            }
        }

        when (outcome) {
            ExpenseUpdateOutcome.Updated -> {
                // Post-update side effects via planner + runner (best-effort)
                val batch = planner.planUpdated(expenseId, source, correlationId, TransactionUpdateKind.CATEGORY_ONLY)
                runner.runBestEffortAfterCommit(
                    batch = batch,
                    logMessage = "Non-critical: side effects failed after updating category for expense",
                    targetId = expenseId
                )
            }
            ExpenseUpdateOutcome.NoChange -> Unit
            ExpenseUpdateOutcome.NotFound -> throw IllegalArgumentException("Expense not found: $expenseId")
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
        reason: String? = null,
        correlationId: String? = null
    ) {
        writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.updateLocation")
        require(latitude in -90.0..90.0) { "Latitude out of range" }
        require(longitude in -180.0..180.0) { "Longitude out of range" }

        val now = timeProvider.now()

        // P2-005: typed outcome from the transaction — only a real update writes
        // the event; unchanged values are idempotent no-ops; missing rows are
        // typed failures. Side effects remain intentionally skipped for
        // location-only updates — location does not affect budget/anomaly/
        // merchant/recurring matching logic.
        var outcome = ExpenseUpdateOutcome.NotFound
        database.withTransaction {
            val existing = expenseDao.getById(expenseId)
            if (existing == null) {
                outcome = ExpenseUpdateOutcome.NotFound
                return@withTransaction
            }
            if (existing.latitude == latitude && existing.longitude == longitude &&
                existing.placeId == placeId && existing.resolvedAddress == resolvedAddress) {
                outcome = ExpenseUpdateOutcome.NoChange
                return@withTransaction
            }

            val beforeSnapshot = expenseToSnapshot(existing)
            val updated = existing.copy(
                latitude = latitude, longitude = longitude,
                locationSource = source, placeId = placeId, resolvedAddress = resolvedAddress,
                backfillAttempts = 0
            )
            expenseDao.updateLocation(expenseId, latitude, longitude, source, placeId, resolvedAddress)
            transactionEventDao.insert(TransactionEvent(
                expenseId = expenseId,
                eventType = LifecycleEventType.UPDATED.name,
                source = source, actor = null, occurredAt = now,
                dedupeKey = existing.dedupeKey, duplicateExpenseId = null,
                beforeSnapshot = beforeSnapshot,
                afterSnapshot = expenseToSnapshot(expenseId, updated),
                metadata = null, reason = reason,
                correlationId = correlationId  // DDL-C67-10
            ))
            outcome = ExpenseUpdateOutcome.Updated
        }

        when (outcome) {
            ExpenseUpdateOutcome.Updated, ExpenseUpdateOutcome.NoChange -> Unit
            ExpenseUpdateOutcome.NotFound -> throw IllegalArgumentException("Expense not found: $expenseId")
        }
    }

    /**
     * Updates business/tax fields on an expense via explicit patch contract.
     *
     * Supported fields: isBusinessExpense, requiresReceipt, businessPurpose,
     * businessCategory, businessProject.
     * Unsupported legacy fields (businessUsePercent, taxCategory, vatEligible)
     * are explicitly rejected instead of silently ignored.
     *
     * @param expenseId The ID of the expense to update.
     * @param patch     The business fields patch.
     * @param source    The source triggering the update.
     * @param reason    Optional reason for the update.
     * @param correlationId Optional correlation ID for traceability.
     * @return [BusinessExpenseUpdateResult] indicating the outcome.
     */
    suspend fun updateBusinessExpensePatch(
        expenseId: Long,
        patch: BusinessExpensePatch,
        source: String = "BUSINESS_TAX_UPDATE",
        reason: String? = null,
        correlationId: String? = null
    ): BusinessExpenseUpdateResult {
        writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.updateBusinessExpensePatch")

        if (patch.isEmpty()) {
            return BusinessExpenseUpdateResult.NoChange
        }

        val unsupported = patch.unsupportedFields()
        if (unsupported.isNotEmpty()) {
            try {
                transactionEventDao.insert(
                    TransactionEvent(
                        expenseId = expenseId,
                        eventType = LifecycleEventType.UPDATE_VALIDATION_FAILED.name,
                        source = source,
                        actor = null,
                        occurredAt = timeProvider.now(),
                        dedupeKey = null,
                        duplicateExpenseId = null,
                        beforeSnapshot = null,
                        afterSnapshot = null,
                        metadata = JSONObject().apply {
                            put("operation", "updateBusinessExpensePatch")
                            put("unsupportedFields", unsupported.joinToString(","))
                        }.toString(),
                        reason = "Unsupported business/tax fields: ${unsupported.joinToString(",")}",
                        correlationId = correlationId
                    )
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w(e, "Failed to write UPDATE_VALIDATION_FAILED for business patch")
            }

            return BusinessExpenseUpdateResult.UnsupportedFields(unsupported)
        }

        val now = timeProvider.now()
        var updateResult: BusinessExpenseUpdateResult = BusinessExpenseUpdateResult.NoChange

        database.withTransaction {
            val existing = expenseDao.getById(expenseId)
            if (existing == null) {
                updateResult = BusinessExpenseUpdateResult.NotFound
                return@withTransaction
            }

            val updated = existing.copy(
                isBusinessExpense = patch.isBusinessExpense ?: existing.isBusinessExpense,
                requiresReceipt = patch.requiresReceipt ?: existing.requiresReceipt,
                businessPurpose = patch.businessPurpose ?: existing.businessPurpose,
                businessCategory = patch.businessCategory ?: existing.businessCategory,
                businessProject = patch.businessProject ?: existing.businessProject
            )

            val changedFields = buildSet {
                if (updated.isBusinessExpense != existing.isBusinessExpense) add("isBusinessExpense")
                if (updated.requiresReceipt != existing.requiresReceipt) add("requiresReceipt")
                if (updated.businessPurpose != existing.businessPurpose) add("businessPurpose")
                if (updated.businessCategory != existing.businessCategory) add("businessCategory")
                if (updated.businessProject != existing.businessProject) add("businessProject")
            }

            if (changedFields.isEmpty()) {
                updateResult = BusinessExpenseUpdateResult.NoChange
                return@withTransaction
            }

            val beforeSnapshot = expenseToSnapshot(existing)

            expenseDao.update(updated)
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
                    metadata = JSONObject().apply {
                        put("operation", "updateBusinessExpensePatch")
                        put("changedFields", changedFields.joinToString(","))
                    }.toString(),
                    reason = reason,
                    correlationId = correlationId
                )
            )

            updateResult = BusinessExpenseUpdateResult.Updated(
                expenseId = expenseId,
                changedFields = changedFields
            )
        }

        if (updateResult !is BusinessExpenseUpdateResult.Updated) {
            return updateResult
        }

        val batch = planner.planUpdated(
            expenseId,
            source,
            correlationId,
            TransactionUpdateKind.BUSINESS_FLAGS_ONLY
        )

        runner.runBestEffortAfterCommit(
            batch = batch,
            logMessage = "Non-critical: side effects failed after updating business/tax fields for expense",
            targetId = expenseId
        )

        return updateResult
    }

    /**
     * Legacy business flags update — delegates to [updateBusinessExpensePatch].
     * Unsupported legacy tax fields are now rejected instead of silently ignored.
     */
    @Deprecated(
        "Use updateBusinessExpensePatch(). Legacy tax fields are rejected instead of ignored.",
        level = DeprecationLevel.WARNING
    )
    suspend fun updateBusinessFlags(
        expenseId: Long,
        isBusinessExpense: Boolean? = null,
        businessUsePercent: Double? = null,
        taxCategory: String? = null,
        vatEligible: Boolean? = null,
        receiptRequired: Boolean? = null,
        source: String = "BUSINESS_TAX_UPDATE"
    ): BusinessExpenseUpdateResult {
        writeBarrier.checkWritesAllowed(
            "TransactionLifecycleCoordinator.updateBusinessFlags"
        )
        return updateBusinessExpensePatch(
            expenseId = expenseId,
            patch = BusinessExpensePatch(
                isBusinessExpense = isBusinessExpense,
                requiresReceipt = receiptRequired,
                businessUsePercent = businessUsePercent,
                taxCategory = taxCategory,
                vatEligible = vatEligible
            ),
            source = source
        )
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
        writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.updateMerchant")

        val now = timeProvider.now()
        val newMerchantKey = MerchantKeyGenerator.generate(newMerchant)

        // P2-005: typed outcome from the transaction — only a real update writes
        // the event and dispatches; unchanged values are idempotent no-ops;
        // missing rows are typed failures. Collision still throws
        // DuplicateUpdateException (rolls back inside the transaction).
        var outcome = ExpenseUpdateOutcome.NotFound
        database.withTransaction {
            val existing = expenseDao.getById(expenseId)
            if (existing == null) {
                outcome = ExpenseUpdateOutcome.NotFound
                return@withTransaction
            }
            if (existing.merchant == newMerchant) {
                outcome = ExpenseUpdateOutcome.NoChange
                return@withTransaction
            }

            val beforeSnapshot = expenseToSnapshot(existing)
            val newDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                existing.amount, newMerchant, existing.date, existing.currency, existing.transactionType
            )

            // Collision check inside transaction for TOCTOU safety.
            // RP-11 FIX 2: blocking-consistent (not-mine-INCLUSIVE) lookup —
            // a not-mine row occupying the target identity must still abort
            // the rename with a typed DuplicateUpdateException.
            val collidingId = expenseDao.findBlockingDuplicateIdCurrencyAware(
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
                    reason = reason,
                    correlationId = correlationId  // DDL-C67-10
                )
            )
            outcome = ExpenseUpdateOutcome.Updated
        }

        when (outcome) {
            ExpenseUpdateOutcome.Updated -> {
                // Post-update side effects via planner + runner (best-effort)
                val batch = planner.planUpdated(expenseId, source, correlationId, TransactionUpdateKind.MERCHANT)
                runner.runBestEffortAfterCommit(
                    batch = batch,
                    logMessage = "Non-critical: side effects failed after updating merchant for expense",
                    targetId = expenseId
                )
            }
            ExpenseUpdateOutcome.NoChange -> Unit
            ExpenseUpdateOutcome.NotFound -> throw IllegalArgumentException("Expense not found: $expenseId")
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
        source: String = "USER_EDIT",
        correlationId: String? = null
    ) {
        writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.updateType")

        val now = timeProvider.now()

        // GR-14j: canonical direct scope — the mutation's proof must be
        // local to the legal writer, independent of caller context.
        //
        // P2-005: typed outcome from the transaction — only a real update
        // dispatches; unchanged values are idempotent no-ops; missing rows
        // are typed failures. No event or dispatch for NoChange/NotFound.
        var outcome = ExpenseUpdateOutcome.NotFound
        writeBarrier.runWrite(
            DatabaseAccessOperation("TransactionLifecycleCoordinator.updateType")
        ) {
            database.withTransaction {
                val existing = expenseDao.getById(expenseId)
                if (existing == null) {
                    outcome = ExpenseUpdateOutcome.NotFound
                    return@withTransaction
                }
                if (existing.transactionType == newType) {
                    outcome = ExpenseUpdateOutcome.NoChange
                    return@withTransaction
                }

                val beforeSnapshot = expenseToSnapshot(existing)
                val newDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                    existing.amount, existing.merchant, existing.date, existing.currency, newType
                )

                // Collision check inside transaction for TOCTOU safety.
                // RP-11 FIX 2: blocking-consistent (not-mine-INCLUSIVE) lookup —
                // a not-mine row occupying the target identity must still abort
                // the update with a typed DuplicateUpdateException.
                val collidingId = expenseDao.findBlockingDuplicateIdCurrencyAware(
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
                        reason = reason,
                        correlationId = correlationId  // DDL-C67-10
                    )
                )
                outcome = ExpenseUpdateOutcome.Updated
            }
        }

        when (outcome) {
            ExpenseUpdateOutcome.Updated -> {
                // Post-update side effects via planner + runner (best-effort)
                val batch = planner.planUpdated(expenseId, source, correlationId, TransactionUpdateKind.TYPE)
                runner.runBestEffortAfterCommit(
                    batch = batch,
                    logMessage = "Non-critical: side effects failed after updating type for expense",
                    targetId = expenseId
                )
            }
            ExpenseUpdateOutcome.NoChange -> Unit
            ExpenseUpdateOutcome.NotFound -> throw IllegalArgumentException("Expense not found: $expenseId")
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
     * @param correlationId       Optional caller correlation ID (P2-007) carried into
     *                            both the UPDATED event and the planned side-effect batch.
     */
    suspend fun updateTransferDetails(
        expenseId: Long,
        transferDirection: com.yourname.expensetracker.data.database.entity.TransferDirection?,
        transferAccountName: String?,
        reason: String? = null,
        source: String = "USER_EDIT",
        correlationId: String? = null
    ) {
        writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.updateTransferDetails")

        val now = timeProvider.now()

        // GR-14k: canonical direct scope — the mutations' proof is local
        // to the legal writer, independent of caller context.
        //
        // P2-005: typed outcome from the transaction — only a real update
        // writes the event and dispatches; unchanged values are idempotent
        // no-ops; missing rows are typed failures. No event/dispatch otherwise.
        var outcome = ExpenseUpdateOutcome.NotFound
        writeBarrier.runWrite(
            DatabaseAccessOperation(
                "TransactionLifecycleCoordinator.updateTransferDetails"
            )
        ) {
            database.withTransaction {
            val existing = expenseDao.getById(expenseId)
            if (existing == null) {
                outcome = ExpenseUpdateOutcome.NotFound
                return@withTransaction
            }
            if (existing.transferDirection == transferDirection && existing.transferAccountName == transferAccountName) {
                outcome = ExpenseUpdateOutcome.NoChange
                return@withTransaction
            }

            val beforeSnapshot = expenseToSnapshot(existing)
            val updated = existing.copy(
                transferDirection = transferDirection,
                transferAccountName = transferAccountName
            )

            // Validate final expense state — prevent clearing transfer metadata on TRANSFER
            val transferErrors = transactionValidator.validateFinalExpenseState(
                amount = updated.amount,
                merchant = updated.merchant,
                currency = updated.currency,
                date = updated.date,
                transactionType = updated.transactionType,
                transferDirectionPresent = updated.transferDirection != null,
                transferAccountName = updated.transferAccountName,
                isNotMine = updated.isNotMine,
                isSharedExpense = updated.isSharedExpense,
                latitude = updated.latitude,
                longitude = updated.longitude
            )
            if (transferErrors.isNotEmpty()) {
                throw TransactionValidationException(transferErrors)
            }

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
                    reason = reason,
                    correlationId = correlationId  // P2-007
                )
            )
            outcome = ExpenseUpdateOutcome.Updated
            }
        }

        when (outcome) {
            ExpenseUpdateOutcome.Updated -> {
                // Post-update side effects via planner + runner (best-effort).
                // P2-007: caller correlation propagates into the planned batch.
                val batch = planner.planUpdated(expenseId, source, correlationId, TransactionUpdateKind.TRANSFER_DETAILS)
                runner.runBestEffortAfterCommit(
                    batch = batch,
                    logMessage = "Non-critical: side effects failed after updating transfer details for expense",
                    targetId = expenseId
                )
            }
            ExpenseUpdateOutcome.NoChange -> Unit
            ExpenseUpdateOutcome.NotFound -> throw IllegalArgumentException("Expense not found: $expenseId")
        }
    }

    /**
     * S5-010R: Atomically updates transaction type AND transfer metadata in one
     * DB transaction with one lifecycle event and one side-effect dispatch.
     * Replaces the two-call pattern (updateType + updateTransferDetails) that
     * could leave the row inconsistent if the second call failed.
     *
     * P2-005: typed outcome from the transaction — only a real update writes
     * the event and dispatches; unchanged values are idempotent no-ops; missing
     * rows are typed failures. The dedupe-key collision check runs whenever the
     * recomputed key differs from the stored key, even if the type is unchanged.
     */
    suspend fun updateTypeAndTransferDetails(
        expenseId: Long,
        newType: TransactionType,
        transferDirection: com.yourname.expensetracker.data.database.entity.TransferDirection?,
        transferAccountName: String?,
        source: String = "USER_EDIT",
        correlationId: String? = null
    ) {
        writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.updateTypeAndTransferDetails")

        val now = timeProvider.now()

        var outcome = ExpenseUpdateOutcome.NotFound
        database.withTransaction {
            val existing = expenseDao.getById(expenseId)
            if (existing == null) {
                outcome = ExpenseUpdateOutcome.NotFound
                return@withTransaction
            }

            val beforeSnapshot = expenseToSnapshot(existing)

            val newDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                existing.amount, existing.merchant, existing.date, existing.currency, newType
            )
            val dedupeKeyChanged = existing.dedupeKey != newDedupeKey

            // No-op detection: recomputed key unchanged AND transfer metadata unchanged
            if (!dedupeKeyChanged &&
                existing.transferDirection == transferDirection &&
                existing.transferAccountName == transferAccountName
            ) {
                outcome = ExpenseUpdateOutcome.NoChange
                return@withTransaction
            }

            // Collision check inside transaction for TOCTOU safety —
            // key-differs check (not type-differs): whenever the recomputed key
            // differs from the stored key, the new key must not collide with
            // another expense's dedupeKey unique index.
            // RP-11 FIX 2: blocking-consistent (not-mine-INCLUSIVE) lookup.
            if (dedupeKeyChanged) {
                val collidingId = expenseDao.findBlockingDuplicateIdCurrencyAware(
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

            // Validate final expense state (shared create/update rules)
            val typeTransferErrors = transactionValidator.validateFinalExpenseState(
                amount = updated.amount,
                merchant = updated.merchant,
                currency = updated.currency,
                date = updated.date,
                transactionType = updated.transactionType,
                transferDirectionPresent = updated.transferDirection != null,
                transferAccountName = updated.transferAccountName,
                isNotMine = updated.isNotMine,
                isSharedExpense = updated.isSharedExpense,
                latitude = updated.latitude,
                longitude = updated.longitude
            )
            if (typeTransferErrors.isNotEmpty()) {
                throw TransactionValidationException(typeTransferErrors)
            }

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
                    reason = null,
                    correlationId = correlationId  // P2-007
                )
            )
            outcome = ExpenseUpdateOutcome.Updated
        }

        when (outcome) {
            ExpenseUpdateOutcome.Updated -> {
                // One side-effect dispatch after commit via planner + runner.
                // P2-007: caller correlation propagates into the planned batch.
                val batch = planner.planUpdated(expenseId, source, correlationId, TransactionUpdateKind.FULL)
                runner.runBestEffortAfterCommit(
                    batch = batch,
                    logMessage = "Non-critical: side effects failed after updateTypeAndTransferDetails for expense",
                    targetId = expenseId
                )
            }
            ExpenseUpdateOutcome.NoChange -> Unit
            ExpenseUpdateOutcome.NotFound -> throw IllegalArgumentException("Expense not found: $expenseId")
        }
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
        writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.updateOwnershipDbOnlyV2")

        val corrId = correlationId ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()
        val now = timeProvider.now()

        var result: MutationResult<OwnershipUpdateResult>? = null

        // GR-14p-a: canonical direct scope — the mutations' proof is local
        // to the legal writer, independent of caller context.
        writeBarrier.runWrite(
            DatabaseAccessOperation(
                "TransactionLifecycleCoordinator.updateOwnershipDbOnlyV2"
            )
        ) {
            database.withTransaction {
            val existing = expenseDao.getById(expenseId)
            if (existing == null) {
                result = MutationResult(
                    OwnershipUpdateResult.NotFound,
                    PostCommitActionBatch.empty(corrId)
                )
                return@withTransaction
            }

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
                result = MutationResult(
                    OwnershipUpdateResult.NoOp,
                    PostCommitActionBatch.empty(corrId)
                )
                return@withTransaction
            }

            val beforeSnapshot = expenseToSnapshot(existing)

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
        }

        if (result != null) return result!!

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
        writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.bulkUpdateCategory")
        val merchantKey = MerchantKeyGenerator.generate(merchant)
        val now = timeProvider.now()
        var affectedCount = 0

        database.withTransaction {
            affectedCount = expenseDao.updateCategoryForMerchant(merchantKey, newCategoryId)
            // P2-PR1 (NEW-P2-010): Only write event if rows were actually affected,
            // consistent with the categoryId-based overload.
            if (affectedCount > 0) {
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
        }

        // P2-07: Dispatch single aggregate post-commit recalculation for bulk updates.
        if (affectedCount > 0) {
            dispatchBulkPostCommitSideEffects(source, affectedCount, setOf(BulkChangedField.CATEGORY))
        }
    }

    /**
     * NEW-P2-005: coordinator-owned post-commit side effects for an already
     * committed `EXPENSE_CATEGORY_ASSIGNED` lifecycle step (receipt item
     * majority category propagation via [ExpenseCategoryAssignmentPort]).
     *
     * The port keeps its atomic update + distinct `EXPENSE_CATEGORY_ASSIGNED`
     * event (type and payload preserved — no listener compatibility risk);
     * this hook adds the previously missing post-commit dispatch (budget
     * recheck + anomaly alert for a category change), following the same
     * CATEGORY_ONLY plan as [updateCategory]. Best-effort: failures are
     * logged and never propagate into receipt linking.
     */
    suspend fun dispatchCategoryAssignmentSideEffects(
        expenseId: Long,
        source: String,
        correlationId: String? = null
    ) {
        val batch = planner.planUpdated(
            expenseId,
            source,
            correlationId,
            TransactionUpdateKind.CATEGORY_ONLY
        )
        runner.runBestEffortAfterCommit(
            batch = batch,
            logMessage = "Non-critical: side effects failed after category assignment for expense",
            targetId = expenseId
        )
    }

    /**
     * P2-07: Aggregate post-commit recalculation for bulk operations.
     * Instead of per-row side effects flooded across N expenses, dispatch a
     * single budget recheck and cache invalidation. This prevents storms while
     * still ensuring holistic state freshness.
     */
    private suspend fun dispatchBulkPostCommitSideEffects(
        source: String,
        affectedCount: Int,
        changedFields: Set<BulkChangedField> = setOf(BulkChangedField.UNKNOWN)
    ) {
        val batch = planner.planBulkUpdated(source, affectedCount, null, changedFields)
        try {
            runner.run(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Non-critical: aggregate bulk side effects failed (affectedCount=%d)", affectedCount)
        }
    }

    /**
     * P2-003: controlled failure reason for an atomic bulk merchant rename that
     * was aborted because at least one target key would collide with an
     * existing expense outside the renamed set.
     */
    object BulkMerchantRenameFailure {
        const val MERCHANT_RENAME_DUPLICATE = "MERCHANT_RENAME_DUPLICATE"
    }

    /**
     * NEW-P2-016: bounded, controlled reason constants for a lifecycle mutation
     * whose home-currency settings could not be resolved (DataStore error or
     * read timeout). Reason-code fields must contain these constants only —
     * never raw settings text, exception text, or payload-derived values.
     */
    object HomeCurrencyFailureReason {
        const val HOME_CURRENCY_UNAVAILABLE = "HOME_CURRENCY_UNAVAILABLE"
    }

    /**
     * Bulk-updates the merchant for all expenses matching the old merchant key.
     * Writes a single BULK_UPDATED TransactionEvent (not per-row) with
     * JSON metadata describing the operation.
     *
     * P2-003: atomic all-or-nothing contract. Every target (merchant, dedupeKey)
     * pair is preflighted inside the transaction BEFORE any row is written; a
     * single collision aborts all changes, writes no lifecycle event, dispatches
     * no side effects, and returns [Result.failure] with the controlled
     * [BulkMerchantRenameFailure.MERCHANT_RENAME_DUPLICATE] reason. A successful
     * operation returns [Result.success] after emitting the existing one
     * aggregate BULK_UPDATED event and dispatching the one aggregate
     * post-commit batch. Zero matching rows is a successful no-op (no event,
     * no dispatch). Per-row partial success is intentionally not supported.
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
    ): Result<Unit> {
        writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.bulkUpdateMerchant")
        if (oldMerchant == newMerchant) return Result.success(Unit)
        val oldMerchantKey = MerchantKeyGenerator.generate(oldMerchant)
        val newMerchantKey = MerchantKeyGenerator.generate(newMerchant)
        val now = timeProvider.now()
        var affectedCount = 0

        return try {
            writeBarrier.runWrite(
                DatabaseAccessOperation(
                    "TransactionLifecycleCoordinator.bulkUpdateMerchant"
                )
            ) {
            database.withTransaction {
            // Fetch affected rows inside transaction to prevent TOCTOU race
            val affectedExpenses = expenseDao.getExpensesByMerchantKey(oldMerchantKey)
            if (affectedExpenses.isEmpty()) return@withTransaction
            affectedCount = affectedExpenses.size

            // P2-003: preflight every target key INSIDE the transaction before
            // writing anything. A collision is detected by the recomputed dedupe
            // key landing on an expense that is not part of this rename set.
            // RP-11 FIX 2: blocking-consistent (not-mine-INCLUSIVE) lookup —
            // a not-mine row occupying a target key must abort the whole rename
            // with the controlled MERCHANT_RENAME_DUPLICATE reason instead of
            // letting a row write die on the raw dedupeKey unique index.
            val targetIds = affectedExpenses.map { it.id }.toHashSet()
            for (expense in affectedExpenses) {
                val newDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                    expense.amount, newMerchant, expense.date, expense.currency, expense.transactionType
                )
                val collidingId = expenseDao.findBlockingDuplicateIdCurrencyAware(
                    amount = expense.amount,
                    merchant = newMerchant,
                    date = expense.date,
                    currency = expense.currency,
                    transactionType = expense.transactionType.name,
                    merchantKey = newMerchantKey,
                    dedupeKey = newDedupeKey
                )
                if (collidingId != null && collidingId !in targetIds) {
                    // Abort everything: no row written, no event, no dispatch.
                    throw DuplicateUpdateException(
                        BulkMerchantRenameFailure.MERCHANT_RENAME_DUPLICATE
                    )
                }
            }

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
            }

            // P2-07: Dispatch single aggregate post-commit recalculation for bulk updates.
            // Metrics/dispatch only after actual commit success (all-or-nothing),
            // and only when rows were actually changed — zero matching rows is a
            // successful no-op with no event and no dispatch (FIX 3).
            if (affectedCount > 0) {
                dispatchBulkPostCommitSideEffects(source, affectedCount, setOf(BulkChangedField.MERCHANT, BulkChangedField.MERCHANT_KEY))
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DuplicateUpdateException) {
            // Controlled duplicate-reason failure — no partial state, no event.
            Result.failure(e)
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
        try {
            writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.deleteExpense")
        } catch (blocked: DatabaseAccessBlockedException) {
            return Result.failure(blocked)
        } catch (blocked: RuntimeException) {
            if (blocked is CancellationException) throw blocked
            return Result.failure(blocked)
        }
        return try {
            val now = timeProvider.now()

            // P2-001: typed outcome from the transaction — only an actual delete
            // writes the DELETED event and dispatches post-commit work.
            // P2-08: Re-read inside transaction for TOCTOU-safe snapshot.
            var outcome = ExpenseDeleteOutcome.NotFound
            database.withTransaction {
                val fresh = expenseDao.getById(expense.id)
                if (fresh == null) {
                    outcome = ExpenseDeleteOutcome.NotFound
                    return@withTransaction
                }
                val snapshot = expenseToSnapshot(fresh)

                transactionEventDao.insert(
                    TransactionEvent(
                        expenseId = expense.id,
                        eventType = LifecycleEventType.DELETED.name,
                        source = source,
                        actor = actor,
                        occurredAt = now,
                        dedupeKey = fresh.dedupeKey,
                        duplicateExpenseId = null,
                        beforeSnapshot = snapshot,
                        afterSnapshot = null,
                        metadata = null,
                        reason = reason,
                        correlationId = correlationId
                    )
                )

                expenseDao.delete(fresh)
                outcome = ExpenseDeleteOutcome.Deleted
            }

            if (outcome == ExpenseDeleteOutcome.NotFound) {
                return Result.failure(
                    IllegalArgumentException("Expense not found: ${expense.id}")
                )
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Validates a [CreateExpenseRequest] and returns a list of error messages.
     * Delegates to [TransactionValidator] for shared create/update validation rules.
     * Returns an empty list if the request is valid.
     */
    private fun validate(request: CreateExpenseRequest): List<String> {
        return transactionValidator.validateCreate(request).map { it.message }
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
        reason: String,
        correlationId: String
    ): Boolean {
        val sourceLinkPayloads = CreateExpenseSourceLinkMapper.fromRequest(request)
        val metadata = SourceLinkEventMetadataBuilder.duplicateMetadata(
            policy = getDuplicateSourceLinkPolicy(request.source),
            attemptedExpense = expense,
            sourceLinkPayloads = sourceLinkPayloads
        )

        return try {
            // GR-14p-a: canonical direct scope — the mutation's proof is
            // local to the legal writer, independent of caller context.
            writeBarrier.runWrite(
                DatabaseAccessOperation(
                    "TransactionLifecycleCoordinator.writeDuplicateEvent"
                )
            ) {
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
            }
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

}

/**
 * Exception thrown when an update would create a duplicate expense.
 */
class DuplicateUpdateException(message: String) : IllegalStateException(message)

/**
 * P2-005: Internal typed outcome for targeted expense updates.
 * Returned from the database transaction; only [Updated] writes a lifecycle
 * event and dispatches post-commit work. [NoChange] is a successful idempotent
 * no-op; [NotFound] is a typed missing-row failure.
 */
private enum class ExpenseUpdateOutcome {
    Updated,
    NoChange,
    NotFound
}

/**
 * P2-001: Internal typed outcome for expense delete.
 * Only [Deleted] writes the DELETED event, plans side effects, and returns
 * [Result.success]; [NotFound] is a typed missing-row failure with no
 * planner/dispatcher call.
 */
private enum class ExpenseDeleteOutcome {
    Deleted,
    NotFound
}

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
