package com.yourname.expensetracker.domain.transaction.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
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
import kotlinx.coroutines.flow.first
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

    /**
     * Centralized write permission check. All mutating methods must call this
     * instead of querying RestoreMaintenanceMode directly.
     */
    private fun checkWritesAllowed(operation: String) {
        writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.$operation")
    }

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

    private suspend fun resolveExistingIdAfterInsertConflict(
        expense: Expense,
        dedupMode: DeduplicationMode
    ): Long? {
        // 1. Exact dedupe-key lookup first.
        val byDedupeKey = expense.dedupeKey
            ?.takeIf { it.isNotBlank() }
            ?.let { expenseDao.findIdByDedupeKey(it) }

        if (byDedupeKey != null) return byDedupeKey

        // 2. STRICT_EXTERNAL_ID should not fuzzy-match.
        if (dedupMode == DeduplicationMode.STRICT_EXTERNAL_ID) {
            return null
        }

        // 3. Debug restore intentionally skips dedupe.
        if (dedupMode == DeduplicationMode.SKIP_FOR_DEBUG_RESTORE) {
            return null
        }

        // 4. STANDARD/BULK fallback: resolve by same policy as duplicate precheck.
        return expenseDao.findDuplicateIdCurrencyAware(
            amount = expense.amount,
            merchant = expense.merchant,
            date = expense.date,
            currency = expense.currency,
            transactionType = expense.transactionType.name,
            merchantKey = expense.merchantKey,
            dedupeKey = expense.dedupeKey
        )
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

    private suspend fun writeUpdateValidationFailedEventBestEffort(
        expenseId: Long,
        source: String,
        reason: String?,
        correlationId: String?,
        errors: List<TransactionValidationError>
    ) {
        runCatching {
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
                        put("operation", "updateExpense")
                        put("errorCount", errors.size)
                        put("errorCodes", errors.joinToString(",") { it.code })
                        put("fields", errors.mapNotNull { it.field }.distinct().joinToString(","))
                    }.toString(),
                    reason = reason ?: "Update validation failed: ${errors.firstOrNull()?.message}",
                    correlationId = correlationId
                )
            )
        }.onFailure {
            if (it is CancellationException) throw it
            Timber.w(it, "Failed to write UPDATE_VALIDATION_FAILED for expense %d", expenseId)
        }
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
            checkWritesAllowed("createExpense")
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
        val validationErrors = validate(request)
        if (validationErrors.isNotEmpty()) {
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
                                errors = provenanceErrors,
                                payloads = sourceLinkPayloads
                            ),
                            reason = "Provenance validation failed: ${provenanceErrors.first()}",
                            correlationId = correlationId
                        )
                    )
                }.onFailure {
                    if (it is CancellationException) throw it
                    Timber.w(it, "Failed to write CREATE_VALIDATION_FAILED for provenance failure")
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
        val homeCurrency = try {
            currencySettingsRepository.homeCurrency().first()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
                    val strictKey = strictExternalDedupeKey(request)
                    if (strictKey == null) {
                        // P2-CURRENT-010: Emit validation event for missing key
                        runCatching {
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
                        val eventLogged = writeDuplicateEvent(expense, request, now, duplicateId, "Bulk import duplicate", correlationId)
                        return Pair(CreateExpenseResult.DuplicateSkipped(
                            existingExpenseId = duplicateId ?: -1L,
                            reason = if (duplicateId != null) {
                                "Bulk import duplicate: existingExpenseId=$duplicateId"
                            } else {
                                "Bulk import duplicate: amount=${expense.amount}, merchant=${expense.merchant}"
                            },
                            eventLogged = eventLogged
                        ), PostCommitActionBatch.empty(correlationId))
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
                        val duplicateId = findDuplicateIdForExpense(expense)
                        // Write duplicate resolution event with metadata
                        val eventLogged = writeDuplicateEvent(expense, request, now, duplicateId, "Standard duplicate", correlationId)
                        return Pair(CreateExpenseResult.DuplicateSkipped(
                            existingExpenseId = duplicateId ?: -1L,
                            reason = if (duplicateId != null) {
                                "Duplicate expense detected: existingExpenseId=$duplicateId"
                            } else {
                                "Duplicate expense detected but existing ID could not be resolved"
                            },
                            eventLogged = eventLogged
                        ), PostCommitActionBatch.empty(correlationId))
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
            // Try to resolve the existing expense ID before declaring unresolved conflict
            val existingId = resolveExistingIdAfterInsertConflict(expense, dedupMode)

            if (existingId != null) {
                // Resolved — treat as duplicate
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
                }.getOrDefault(false)

                return Pair(CreateExpenseResult.DuplicateSkipped(
                    existingExpenseId = existingId,
                    reason = "Insert conflict resolved to existing expense $existingId",
                    eventLogged = eventLogged
                ), PostCommitActionBatch.empty(correlationId))
            }

            // Unresolved — write INSERT_CONFLICT
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
                        reason = "Unresolved insert conflict for dedupeKey=${expense.dedupeKey}",
                        correlationId = correlationId
                    )
                )
            }
            return Pair(CreateExpenseResult.InsertConflict(expense.dedupeKey ?: "unknown"), PostCommitActionBatch.empty(correlationId))
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
        checkWritesAllowed("updateExpense")

        val now = timeProvider.now()

        // ── Currency conversion snapshot (may do network I/O — stays outside txn) ──
        val homeCurrencyUpdate = try {
            currencySettingsRepository.homeCurrency().first()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            CurrencyConverter.DEFAULT_BASE_CURRENCY
        }
        val preComputedConversion = if (expense.currency != homeCurrencyUpdate) {
            runCatching {
                currencyConverter.convertAsOf(
                    amount = expense.amount,
                    fromCurrency = expense.currency,
                    toCurrency = homeCurrencyUpdate,
                    atMillis = expense.date
                )
            }.getOrNull()
        } else null

        // 3. Persist inside a single transaction (TOCTOU-safe: read + write atomic)
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

            // Apply currency conversion (pre-computed outside txn)
            val finalExpense = if (expense.currency != homeCurrencyUpdate && preComputedConversion != null) {
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
                updatedExpense
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
                    correlationId = correlationId
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
        checkWritesAllowed("updateCategory")

        val now = timeProvider.now()

        database.withTransaction {
            val existing = expenseDao.getById(expenseId) ?: return@withTransaction
            if (existing.categoryId == newCategoryId) return@withTransaction

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
        checkWritesAllowed("updateLocation")
        require(latitude in -90.0..90.0) { "Latitude out of range" }
        require(longitude in -180.0..180.0) { "Longitude out of range" }

        val now = timeProvider.now()

        database.withTransaction {
            val existing = expenseDao.getById(expenseId) ?: return@withTransaction
            if (existing.latitude == latitude && existing.longitude == longitude &&
                existing.placeId == placeId && existing.resolvedAddress == resolvedAddress) return@withTransaction

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
                metadata = null, reason = reason
            ))
        }

        // Side effects intentionally skipped for location-only updates — location
        // does not affect budget/anomaly/merchant/recurring matching logic.
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
        checkWritesAllowed("updateBusinessExpensePatch")

        if (patch.isEmpty()) {
            return BusinessExpenseUpdateResult.NoChange
        }

        val unsupported = patch.unsupportedFields()
        if (unsupported.isNotEmpty()) {
            runCatching {
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
            }.onFailure {
                if (it is CancellationException) throw it
                Timber.w(it, "Failed to write UPDATE_VALIDATION_FAILED for business patch")
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
        checkWritesAllowed("updateMerchant")

        val now = timeProvider.now()
        val newMerchantKey = MerchantKeyGenerator.generate(newMerchant)

        database.withTransaction {
            val existing = expenseDao.getById(expenseId) ?: return@withTransaction
            if (existing.merchant == newMerchant) return@withTransaction

            val beforeSnapshot = expenseToSnapshot(existing)
            val newDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                existing.amount, newMerchant, existing.date, existing.currency, existing.transactionType
            )

            // Collision check inside transaction for TOCTOU safety
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
        checkWritesAllowed("updateType")

        val now = timeProvider.now()

        database.withTransaction {
            val existing = expenseDao.getById(expenseId) ?: return@withTransaction
            if (existing.transactionType == newType) return@withTransaction

            val beforeSnapshot = expenseToSnapshot(existing)
            val newDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                existing.amount, existing.merchant, existing.date, existing.currency, newType
            )

            // Collision check inside transaction for TOCTOU safety
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
        checkWritesAllowed("updateTransferDetails")

        val now = timeProvider.now()

        database.withTransaction {
            val existing = expenseDao.getById(expenseId) ?: return@withTransaction
            if (existing.transferDirection == transferDirection && existing.transferAccountName == transferAccountName) return@withTransaction

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
        checkWritesAllowed("updateTypeAndTransferDetails")

        val now = timeProvider.now()

        database.withTransaction {
            val existing = expenseDao.getById(expenseId) ?: return@withTransaction
            val beforeSnapshot = expenseToSnapshot(existing)

            val newDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                existing.amount, existing.merchant, existing.date, existing.currency, newType
            )

            // Collision check inside transaction for TOCTOU safety
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
        checkWritesAllowed("updateOwnershipDbOnlyV2")

        val corrId = correlationId ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()
        val now = timeProvider.now()

        var result: MutationResult<OwnershipUpdateResult>? = null

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
        checkWritesAllowed("bulkUpdateCategory")
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
            dispatchBulkPostCommitSideEffects(source, affectedCount, setOf(BulkChangedField.CATEGORY))
        }
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
     * Atomic category-to-category bulk reassignment.
     * Uses a single SQL UPDATE + one BULK_UPDATED event.
     * No partial migration possible — crash/event failure rolls back.
     */
    suspend fun bulkUpdateCategory(
        categoryId: Long,
        newCategoryId: Long,
        source: String = "CATEGORY_CORRECTION"
    ) {
        checkWritesAllowed("bulkUpdateCategoryByCategory")

        if (categoryId == newCategoryId) {
            Timber.d("Bulk category update skipped: source and target category are identical (%d)", categoryId)
            return
        }

        val now = timeProvider.now()
        val correlationId = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()
        var affectedCount = 0

        database.withTransaction {
            affectedCount = expenseDao.updateCategoryForCategory(
                oldCategoryId = categoryId,
                newCategoryId = newCategoryId
            )

            if (affectedCount > 0) {
                transactionEventDao.insert(
                    TransactionEvent(
                        expenseId = null,
                        eventType = LifecycleEventType.BULK_UPDATED.name,
                        source = source,
                        actor = null,
                        occurredAt = now,
                        dedupeKey = null,
                        duplicateExpenseId = null,
                        beforeSnapshot = null,
                        afterSnapshot = null,
                        metadata = JSONObject().apply {
                            put("operation", "bulkUpdateCategoryByCategory")
                            put("oldCategoryId", categoryId)
                            put("newCategoryId", newCategoryId)
                            put("affectedCount", affectedCount)
                            put("changedFields", "categoryId")
                            put("atomic", true)
                        }.toString(),
                        reason = "Bulk reassigned category $categoryId to $newCategoryId",
                        correlationId = correlationId
                    )
                )
            }
        }

        if (affectedCount > 0) {
            dispatchBulkPostCommitSideEffects(source, affectedCount, setOf(BulkChangedField.CATEGORY))
        }

        Timber.d(
            "Bulk category update: %d expenses moved from category %d to %d",
            affectedCount, categoryId, newCategoryId
        )
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
        checkWritesAllowed("bulkUpdateMerchant")
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
        dispatchBulkPostCommitSideEffects(source, affectedCount, setOf(BulkChangedField.MERCHANT, BulkChangedField.MERCHANT_KEY))
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
        try {
            checkWritesAllowed("deleteExpense")
        } catch (blocked: DatabaseAccessBlockedException) {
            return Result.failure(blocked)
        } catch (blocked: RuntimeException) {
            if (blocked is CancellationException) throw blocked
            return Result.failure(blocked)
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
            if (e is kotlinx.coroutines.CancellationException) throw e
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
            checkWritesAllowed("deleteExpense")
        } catch (blocked: DatabaseAccessBlockedException) {
            return Result.failure(blocked)
        } catch (blocked: RuntimeException) {
            if (blocked is CancellationException) throw blocked
            return Result.failure(blocked)
        }
        return try {
            val now = timeProvider.now()

            // Write lifecycle event + delete inside a single transaction
            // P2-08: Re-read inside transaction for TOCTOU-safe snapshot
            database.withTransaction {
                val fresh = expenseDao.getById(expense.id) ?: return@withTransaction
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
