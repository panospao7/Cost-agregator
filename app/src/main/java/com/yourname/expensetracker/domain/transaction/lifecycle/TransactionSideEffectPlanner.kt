package com.yourname.expensetracker.domain.transaction.lifecycle

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestrator
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.CorrelationIds
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentReconcileReport
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.sideeffect.*
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.SourceLearningPolicy
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.Lazy
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Bounded failure reasons of the installment reaction (never a message, id or text).
private const val INSTALLMENT_REASON_NOT_DONE = "installment_reaction_not_done"
private const val INSTALLMENT_REASON_RECONCILE_INCOMPLETE = "installment_reaction_reconcile_incomplete"
private const val INSTALLMENT_REASON_DISPATCH_FAILED = "installment_reaction_dispatch_failed"
private const val INSTALLMENT_REASON_EXCEPTION = "installment_reaction_exception"

@Singleton
class TransactionSideEffectPlanner @Inject constructor(
    private val budgetMonitor: Lazy<BudgetMonitor>,
    private val anomalyAlertOrchestrator: AnomalyAlertOrchestrator,
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val merchantNormalizationRepository: MerchantNormalizationRepository,
    private val recurringLifecycleCoordinator: Lazy<RecurringLifecycleCoordinator>,
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao,
    private val timeProvider: TimeProvider,
    // Loan-installments S6c-1: Lazy because the plan coordinator depends on the transaction
    // lifecycle coordinator, which depends on this planner.
    private val installmentPlanLifecycleCoordinator: Lazy<InstallmentPlanLifecycleCoordinator>,
    // The same executor TLC uses for its own batches; the installment reaction runs the batches
    // the plan coordinator returns in Done.postCommit through it and never drops them.
    private val postCommitActionRunner: Lazy<PostCommitActionRunner>
) {

    fun planCreated(
        expenseId: Long,
        source: ExpenseSource,
        correlationId: String?
    ): PostCommitActionBatch {
        val corrId = correlationId ?: CorrelationIds.newId()
        val actions = mutableListOf(
            makeBudgetCheckAction(expenseId, source, corrId, SideEffectTriggerType.EXPENSE_CREATED),
            makeAnomalyAlertAction(expenseId, source.name, corrId, SideEffectTriggerType.EXPENSE_CREATED),
            makeMerchantCanonicalStatsAction(expenseId, source.name, corrId, SideEffectTriggerType.EXPENSE_CREATED),
            makeRecurringMatchingAction(expenseId, source, corrId)
        )
        // C10 / E3-NOW-004: Only learn from trusted sources
        if (SourceLearningPolicy.isTrustedForLearning(source)) {
            actions.add(makeMerchantCategoryLearningAction(expenseId, source.name, corrId, SideEffectTriggerType.EXPENSE_CREATED))
        }
        return PostCommitActionBatch(corrId, actions)
    }

    fun planUpdated(
        expenseId: Long,
        source: String,
        correlationId: String?,
        kind: TransactionUpdateKind
    ): PostCommitActionBatch {
        val corrId = correlationId ?: CorrelationIds.newId()
        val actions = mutableListOf<PostCommitAction>()

        when (kind) {
            TransactionUpdateKind.LOCATION_ONLY -> {
                return PostCommitActionBatch.empty(corrId)
            }
            TransactionUpdateKind.SPENDING_INCLUSION -> {
                // The flag changes what counts toward budgets; the row itself (merchant,
                // amount, category) is unchanged, so only the budget recheck is needed.
                actions.add(makeBudgetCheckAction(expenseId, ExpenseSource.UNKNOWN, corrId, SideEffectTriggerType.EXPENSE_UPDATED))
            }
            TransactionUpdateKind.CATEGORY_ONLY, TransactionUpdateKind.BUSINESS_FLAGS_ONLY -> {
                actions.add(makeBudgetCheckAction(expenseId, ExpenseSource.UNKNOWN, corrId, SideEffectTriggerType.EXPENSE_UPDATED))
                actions.add(makeAnomalyAlertAction(expenseId, source, corrId, SideEffectTriggerType.EXPENSE_UPDATED))
            }
            TransactionUpdateKind.FULL, TransactionUpdateKind.MERCHANT, TransactionUpdateKind.TYPE, TransactionUpdateKind.TRANSFER_DETAILS,
            TransactionUpdateKind.AMOUNT, TransactionUpdateKind.DATE, TransactionUpdateKind.CURRENCY,
            TransactionUpdateKind.OWNERSHIP, TransactionUpdateKind.PAYMENT_CORE -> {
                actions.add(makeBudgetCheckAction(expenseId, ExpenseSource.UNKNOWN, corrId, SideEffectTriggerType.EXPENSE_UPDATED))
                actions.add(makeAnomalyAlertAction(expenseId, source, corrId, SideEffectTriggerType.EXPENSE_UPDATED))
                // C08 / E3-NOW-005: Do NOT increment merchant stats on update.
                // Updates can double-count if the expense was already counted at creation.
                // True delta tracking requires schema change; deferred until baseline stable.
                if (kind.affectsRecurringMatch()) {
                    actions.add(makeRecurringReconcileAction(expenseId, source, corrId))
                }
            }
        }

        // C10 / E3-NOW-004: Only learn from trusted sources, and only when category may have changed
        if (kind.involvesCategoryChange() && SourceLearningPolicy.isTrustedForLearning(source)) {
            actions.add(makeMerchantCategoryLearningAction(expenseId, source, corrId, SideEffectTriggerType.EXPENSE_UPDATED))
        }

        // Loan-installments S6c-1: re-check the plans the expense belongs to (R1, R3, R4, R6).
        // Appended last so the relative order of the existing actions is unchanged.
        if (installmentReactionRequired(kind)) {
            actions.add(makeInstallmentChangedAction(expenseId, source, corrId))
        }

        return PostCommitActionBatch(corrId, actions)
    }

    /**
     * Whether an update kind can change what the installment reconcile rules read (money and type
     * facts of a linked expense or plan purchase: currency, spending type). Exhaustive on purpose:
     * a new [TransactionUpdateKind] must be classified here. A superset would be safe because the
     * reaction is idempotent; a missed kind is only repaired by the next reconcile.
     */
    private fun installmentReactionRequired(kind: TransactionUpdateKind): Boolean = when (kind) {
        // May change any field, including type and currency.
        TransactionUpdateKind.FULL -> true
        // R4: a type change away from a spending type unlinks a PAID entry.
        TransactionUpdateKind.TYPE -> true
        // R4: a currency change away from the plan currency unlinks a PAID entry.
        TransactionUpdateKind.CURRENCY -> true
        // Transfer direction and account name only; it changes neither type nor currency. Kept in
        // the reacting set as a deliberate superset (A13): the reaction is idempotent.
        TransactionUpdateKind.TRANSFER_DETAILS -> true
        // Payment core edits can move amount, currency and type together.
        TransactionUpdateKind.PAYMENT_CORE -> true
        // Loop breaker: the plan coordinator's own spending-flag writes plan this kind;
        // reacting would re-enter the coordinator on its own writes.
        TransactionUpdateKind.SPENDING_INCLUSION -> false
        // A category edit leaves plan rows and events untouched (AE-20).
        TransactionUpdateKind.CATEGORY_ONLY -> false
        // Location is neither money nor type (and the batch is empty anyway).
        TransactionUpdateKind.LOCATION_ONLY -> false
        // Business flags are neither money nor type.
        TransactionUpdateKind.BUSINESS_FLAGS_ONLY -> false
        // Merchant text is not read by R1 to R6.
        TransactionUpdateKind.MERCHANT -> false
        // Amount-only edits are not part of R1 to R6 (drift is currency and type only); a flagged
        // purchase's amount is locked by INV-22. Reconcile is the backstop.
        TransactionUpdateKind.AMOUNT -> false
        // The date is not read by R1 to R6.
        TransactionUpdateKind.DATE -> false
        // Ownership (not mine, share) is not read by R1 to R6.
        TransactionUpdateKind.OWNERSHIP -> false
    }

    fun planDeleted(
        expenseId: Long,
        source: String,
        correlationId: String?
    ): PostCommitActionBatch {
        val corrId = correlationId ?: CorrelationIds.newId()
        val actions = listOf(
            makeBudgetCheckAction(expenseId, ExpenseSource.UNKNOWN, corrId, SideEffectTriggerType.EXPENSE_DELETED),
            makeRecurringUnlinkAction(expenseId, source, corrId),
            makeInstallmentDeletedAction(expenseId, source, corrId)
        )
        return PostCommitActionBatch(corrId, actions)
    }

    fun planBulkUpdated(
        source: String,
        affectedCount: Int,
        correlationId: String?,
        changedFields: Set<BulkChangedField> = setOf(BulkChangedField.UNKNOWN)
    ): PostCommitActionBatch {
        val corrId = correlationId ?: CorrelationIds.newId()
        // Capture "now" once per batch so every bulk action in this batch shares
        // the same deterministic idempotency timestamp (G-TIME-01).
        val now = timeProvider.now()
        if (affectedCount <= 0) {
            return PostCommitActionBatch(corrId, listOf(
                PostCommitAction(
                    pipeline = AppPipeline.TRANSACTION,
                    name = "bulk_budget_check",
                    category = SideEffectCategory.BUDGET,
                    triggerType = SideEffectTriggerType.EXPENSE_BULK_UPDATED,
                    targetEntityType = "EXPENSE",
                    targetEntityId = null,
                    source = source,
                    correlationId = corrId,
                    causationId = null,
                    idempotencyKey = "bulk:$source:0:budget_check:$now",
                    priority = SideEffectPriority.LOW,
                    metadata = SafeEventMetadata.builder().put("affectedCount", "0").build()
                ) { SideEffectOutcome.Skipped(SideEffectSkipReason.NO_WORK) }
            ))
        }

        val normalizedFields = changedFields.ifEmpty { setOf(BulkChangedField.UNKNOWN) }
        val actions = mutableListOf<PostCommitAction>()

        if (normalizedFields.affectsBudget()) {
            actions += makeBulkBudgetCheckAction(source, affectedCount, normalizedFields, corrId, now)
        }
        if (normalizedFields.affectsAnomaly()) {
            actions += makeBulkAnomalyInvalidationAction(source, affectedCount, normalizedFields, corrId, now)
        }
        if (normalizedFields.affectsMerchantLearning()) {
            actions += makeBulkMerchantCategoryDirtyAction(source, affectedCount, normalizedFields, corrId, now)
            actions += makeBulkMerchantCanonicalStatsDirtyAction(source, affectedCount, normalizedFields, corrId, now)
        }
        if (normalizedFields.affectsAnalyticsCache()) {
            actions += makeBulkAnalyticsCacheInvalidationAction(source, affectedCount, normalizedFields, corrId, now)
        }
        if (normalizedFields.affectsRecurring()) {
            actions += makeBulkRecurringReconciliationAction(source, affectedCount, normalizedFields, corrId, now)
        }

        return PostCommitActionBatch(corrId, actions)
    }

    // --- Private action factory methods ---

    private fun makeBudgetCheckAction(
        expenseId: Long,
        source: ExpenseSource,
        correlationId: String,
        triggerType: SideEffectTriggerType
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "budget_check",
            category = SideEffectCategory.BUDGET,
            triggerType = triggerType,
            targetEntityType = "EXPENSE",
            targetEntityId = expenseId,
            source = source.name,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "expense:$expenseId:${triggerType.name.lowercase()}:budget_check",
            metadata = SafeEventMetadata.builder()
                .put("expenseId", expenseId.toString())
                .put("source", source.name)
                .build()
        ) {
            try {
                budgetMonitor.get().checkBudgets()
                SideEffectOutcome.Completed
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SideEffectOutcome.FailedRetryable(e.message ?: "Budget check failed", e.javaClass.name)
            }
        }
    }

    private fun makeAnomalyAlertAction(
        expenseId: Long,
        source: String,
        correlationId: String,
        triggerType: SideEffectTriggerType
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "anomaly_alert_check",
            category = SideEffectCategory.ANOMALY,
            triggerType = triggerType,
            targetEntityType = "EXPENSE",
            targetEntityId = expenseId,
            source = source,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "expense:$expenseId:${triggerType.name.lowercase()}:anomaly_alert_check",
            metadata = SafeEventMetadata.builder()
                .put("expenseId", expenseId.toString())
                .build()
        ) {
            val expense = expenseDao.getById(expenseId)
            if (expense == null) {
                SideEffectOutcome.Skipped(SideEffectSkipReason.MISSING_ENTITY)
            } else {
                try {
                    val category = expense.categoryId?.let { categoryDao.getById(it) }
                    anomalyAlertOrchestrator.checkAndAlert(ExpenseWithCategory(expense = expense, category = category))
                    SideEffectOutcome.Completed
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    SideEffectOutcome.FailedRetryable(e.message ?: "Anomaly check failed", e.javaClass.name)
                }
            }
        }
    }

    private fun makeMerchantCategoryLearningAction(
        expenseId: Long,
        source: String,
        correlationId: String,
        triggerType: SideEffectTriggerType
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "merchant_category_pattern_learning",
            category = SideEffectCategory.MERCHANT_LEARNING,
            triggerType = triggerType,
            targetEntityType = "EXPENSE",
            targetEntityId = expenseId,
            source = source,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "expense:$expenseId:${triggerType.name.lowercase()}:merchant_category_learning",
            metadata = SafeEventMetadata.builder()
                .put("expenseId", expenseId.toString())
                .build()
        ) {
            val expense = expenseDao.getById(expenseId)
            if (expense == null || expense.categoryId == null) {
                SideEffectOutcome.Skipped(SideEffectSkipReason.MISSING_ENTITY)
            } else {
                try {
                    merchantCategoryRepository.learnPattern(expense.merchant, expense.categoryId!!)
                    SideEffectOutcome.Completed
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    SideEffectOutcome.FailedRetryable(e.message ?: "Merchant category learning failed", e.javaClass.name)
                }
            }
        }
    }

    private fun makeMerchantCanonicalStatsAction(
        expenseId: Long,
        source: String,
        correlationId: String,
        triggerType: SideEffectTriggerType
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "merchant_canonical_stats_update",
            category = SideEffectCategory.MERCHANT_LEARNING,
            triggerType = triggerType,
            targetEntityType = "EXPENSE",
            targetEntityId = expenseId,
            source = source,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "expense:$expenseId:${triggerType.name.lowercase()}:merchant_stats",
            metadata = SafeEventMetadata.builder()
                .put("expenseId", expenseId.toString())
                .build()
        ) {
            val expense = expenseDao.getById(expenseId)
            if (expense == null) {
                SideEffectOutcome.Skipped(SideEffectSkipReason.MISSING_ENTITY)
            } else {
                val searchKey = expense.merchantKey ?: expense.merchant
                val canonical = merchantNormalizationRepository.getCanonicalBySearchKey(searchKey)
                if (canonical == null) {
                    SideEffectOutcome.Skipped(SideEffectSkipReason.NOT_APPLICABLE)
                } else {
                    try {
                        merchantNormalizationRepository.incrementMerchantStats(
                            id = canonical.id,
                            amount = expense.amount,
                            timestamp = expense.date
                        )
                        SideEffectOutcome.Completed
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        SideEffectOutcome.FailedRetryable(e.message ?: "Merchant stats update failed", e.javaClass.name)
                    }
                }
            }
        }
    }

    private fun makeRecurringMatchingAction(
        expenseId: Long,
        source: ExpenseSource,
        correlationId: String
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "recurring_occurrence_matching",
            category = SideEffectCategory.RECURRING,
            triggerType = SideEffectTriggerType.EXPENSE_CREATED,
            targetEntityType = "EXPENSE",
            targetEntityId = expenseId,
            source = source.name,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "expense:$expenseId:created:recurring_matching",
            metadata = SafeEventMetadata.builder()
                .put("expenseId", expenseId.toString())
                .build()
        ) {
            try {
                val result = recurringLifecycleCoordinator.get()
                    .linkExpenseToOccurrenceDetailed(expenseId, "transaction_created:$source")
                when (result) {
                    is com.yourname.expensetracker.domain.recurring.lifecycle.RecurringExpenseReconcileResult.Linked ->
                        SideEffectOutcome.Completed
                    else -> SideEffectOutcome.Skipped(SideEffectSkipReason.NOT_APPLICABLE)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SideEffectOutcome.FailedRetryable(e.message ?: "Recurring matching failed", e.javaClass.name)
            }
        }
    }

    private fun makeRecurringReconcileAction(
        expenseId: Long,
        source: String,
        correlationId: String
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "recurring_reconcile",
            category = SideEffectCategory.RECURRING,
            triggerType = SideEffectTriggerType.EXPENSE_UPDATED,
            targetEntityType = "EXPENSE",
            targetEntityId = expenseId,
            source = source,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "expense:$expenseId:updated:recurring_reconcile",
            metadata = SafeEventMetadata.builder()
                .put("expenseId", expenseId.toString())
                .build()
        ) {
            try {
                val result = recurringLifecycleCoordinator.get()
                    .reconcileExpenseLinkAfterUpdate(expenseId, "transaction_update:$source")
                when (result) {
                    is com.yourname.expensetracker.domain.recurring.lifecycle.RecurringExpenseReconcileResult.Linked,
                    is com.yourname.expensetracker.domain.recurring.lifecycle.RecurringExpenseReconcileResult.Unlinked,
                    is com.yourname.expensetracker.domain.recurring.lifecycle.RecurringExpenseReconcileResult.Relinked,
                    is com.yourname.expensetracker.domain.recurring.lifecycle.RecurringExpenseReconcileResult.UpdatedLinkedSnapshot ->
                        SideEffectOutcome.Completed
                    is com.yourname.expensetracker.domain.recurring.lifecycle.RecurringExpenseReconcileResult.NoMatch,
                    is com.yourname.expensetracker.domain.recurring.lifecycle.RecurringExpenseReconcileResult.Skipped,
                    is com.yourname.expensetracker.domain.recurring.lifecycle.RecurringExpenseReconcileResult.Error ->
                        SideEffectOutcome.Skipped(SideEffectSkipReason.NOT_APPLICABLE)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SideEffectOutcome.FailedRetryable(e.message ?: "Recurring reconcile failed", e.javaClass.name)
            }
        }
    }

    private fun makeRecurringUnlinkAction(
        expenseId: Long,
        source: String,
        correlationId: String
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "recurring_unlink",
            category = SideEffectCategory.RECURRING,
            triggerType = SideEffectTriggerType.EXPENSE_DELETED,
            targetEntityType = "EXPENSE",
            targetEntityId = expenseId,
            source = source,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "expense:$expenseId:deleted:recurring_unlink",
            metadata = SafeEventMetadata.builder()
                .put("expenseId", expenseId.toString())
                .build()
        ) {
            try {
                val result = recurringLifecycleCoordinator.get()
                    .unlinkExpenseFromOccurrenceDetailed(expenseId, "transaction_deleted:$source")
                when (result) {
                    is com.yourname.expensetracker.domain.recurring.lifecycle.RecurringExpenseReconcileResult.Unlinked ->
                        SideEffectOutcome.Completed
                    is com.yourname.expensetracker.domain.recurring.lifecycle.RecurringExpenseReconcileResult.Skipped ->
                        SideEffectOutcome.Skipped(SideEffectSkipReason.NOT_APPLICABLE)
                    else -> SideEffectOutcome.Completed
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SideEffectOutcome.FailedRetryable(e.message ?: "Recurring unlink failed", e.javaClass.name)
            }
        }
    }

    // --- Installment plan reaction (loan-installments S6c-1) ---

    private fun makeInstallmentChangedAction(
        expenseId: Long,
        source: String,
        correlationId: String
    ): PostCommitAction = makeInstallmentReactionAction(
        name = "installment_expense_changed",
        triggerType = SideEffectTriggerType.EXPENSE_UPDATED,
        idempotencyKey = "expense:$expenseId:updated:installment_changed",
        expenseId = expenseId,
        source = source,
        correlationId = correlationId
    ) { coordinator -> coordinator.onExpenseChanged(expenseId) }

    private fun makeInstallmentDeletedAction(
        expenseId: Long,
        source: String,
        correlationId: String
    ): PostCommitAction = makeInstallmentReactionAction(
        name = "installment_expense_deleted",
        triggerType = SideEffectTriggerType.EXPENSE_DELETED,
        idempotencyKey = "expense:$expenseId:deleted:installment_deleted",
        expenseId = expenseId,
        source = source,
        correlationId = correlationId
    ) { coordinator -> coordinator.onExpenseDeleted(expenseId) }

    /**
     * Calls the plan coordinator, dispatches every returned `Done.postCommit` batch through the
     * runner TLC uses (even when the report counts failures: those batches are already committed),
     * then maps the result. FailedRetryable on a non-Done result, plansFailed or orphanFlagsFailed
     * above zero, a batch dispatch failure, or any exception (a closed write barrier included).
     * CancellationException is rethrown. Diagnostics carry bounded constants and class simple
     * names only.
     */
    private fun makeInstallmentReactionAction(
        name: String,
        triggerType: SideEffectTriggerType,
        idempotencyKey: String,
        expenseId: Long,
        source: String,
        correlationId: String,
        react: suspend (InstallmentPlanLifecycleCoordinator) -> InstallmentOpResult<InstallmentReconcileReport>
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = name,
            category = SideEffectCategory.INSTALLMENT_PLAN,
            triggerType = triggerType,
            targetEntityType = "EXPENSE",
            targetEntityId = expenseId,
            source = source,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = idempotencyKey,
            metadata = SafeEventMetadata.builder()
                .put("expenseId", expenseId.toString())
                .build()
        ) {
            try {
                settleInstallmentResult(react(installmentPlanLifecycleCoordinator.get()))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w("Installment reaction failed: %s", e.javaClass.simpleName)
                SideEffectOutcome.FailedRetryable(INSTALLMENT_REASON_EXCEPTION, e.javaClass.simpleName)
            }
        }
    }

    private suspend fun settleInstallmentResult(
        result: InstallmentOpResult<InstallmentReconcileReport>
    ): SideEffectOutcome {
        if (result !is InstallmentOpResult.Done) {
            return SideEffectOutcome.FailedRetryable(INSTALLMENT_REASON_NOT_DONE, result.javaClass.simpleName)
        }
        // Dispatch first: the batches belong to transactions that already committed.
        val dispatchFailureClass = dispatchInstallmentBatches(result.postCommit)
        val report = result.value
        return when {
            report.plansFailed > 0 || report.orphanFlagsFailed > 0 ->
                SideEffectOutcome.FailedRetryable(INSTALLMENT_REASON_RECONCILE_INCOMPLETE, "InstallmentReconcileReport")
            dispatchFailureClass != null ->
                SideEffectOutcome.FailedRetryable(INSTALLMENT_REASON_DISPATCH_FAILED, dispatchFailureClass)
            report.changedRowCount() > 0 -> SideEffectOutcome.Completed
            else -> SideEffectOutcome.Skipped(SideEffectSkipReason.NO_WORK)
        }
    }

    /**
     * Runs each batch in order; one failing batch does not stop the rest. Returns the simple class
     * name of the first non-cancellation failure, or null. Cancellation is rethrown.
     */
    private suspend fun dispatchInstallmentBatches(batches: List<PostCommitActionBatch>): String? {
        if (batches.isEmpty()) return null
        val runner = postCommitActionRunner.get()
        var failureClass: String? = null
        for (batch in batches) {
            if (batch.actions.isEmpty()) continue
            try {
                runner.run(batch)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (failureClass == null) failureClass = e.javaClass.simpleName
            }
        }
        return failureClass
    }

    private fun InstallmentReconcileReport.changedRowCount(): Int =
        r1Reflagged + r2Reopened + r3Reflagged + r4Unlinked + r5Detached + r6Completed + orphanFlagsCleared

    // --- Bulk action factory methods ---

    private fun makeBulkBudgetCheckAction(
        source: String,
        affectedCount: Int,
        changedFields: Set<BulkChangedField>,
        correlationId: String,
        now: Long
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "bulk_budget_check",
            category = SideEffectCategory.BUDGET,
            triggerType = SideEffectTriggerType.EXPENSE_BULK_UPDATED,
            targetEntityType = "EXPENSE",
            targetEntityId = null,
            source = source,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "bulk:$source:$affectedCount:budget_check:$now",
            priority = SideEffectPriority.LOW,
            metadata = SafeEventMetadata.builder()
                .put("affectedCount", affectedCount.toString())
                .put("source", source)
                .put("changedFields", changedFields.joinToString(",") { it.name })
                .build()
        ) {
            try {
                budgetMonitor.get().checkBudgets()
                SideEffectOutcome.Completed
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SideEffectOutcome.FailedRetryable(e.message ?: "Bulk budget check failed", e.javaClass.name)
            }
        }
    }

    private fun makeBulkAnomalyInvalidationAction(
        source: String,
        affectedCount: Int,
        changedFields: Set<BulkChangedField>,
        correlationId: String,
        now: Long
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "bulk_anomaly_invalidation",
            category = SideEffectCategory.ANOMALY,
            triggerType = SideEffectTriggerType.EXPENSE_BULK_UPDATED,
            targetEntityType = "EXPENSE",
            targetEntityId = null,
            source = source,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "bulk:$source:$affectedCount:anomaly_invalidation:$now",
            priority = SideEffectPriority.LOW,
            metadata = SafeEventMetadata.builder()
                .put("affectedCount", affectedCount.toString())
                .put("changedFields", changedFields.joinToString(",") { it.name })
                .build()
        ) {
            try {
                anomalyAlertOrchestrator.invalidateCache()
                SideEffectOutcome.Completed
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SideEffectOutcome.FailedRetryable(e.message ?: "Bulk anomaly invalidation failed", e.javaClass.name)
            }
        }
    }

    private fun makeBulkMerchantCategoryDirtyAction(
        source: String,
        affectedCount: Int,
        changedFields: Set<BulkChangedField>,
        correlationId: String,
        now: Long
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "bulk_merchant_category_dirty",
            category = SideEffectCategory.MERCHANT_LEARNING,
            triggerType = SideEffectTriggerType.EXPENSE_BULK_UPDATED,
            targetEntityType = "EXPENSE",
            targetEntityId = null,
            source = source,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "bulk:$source:$affectedCount:merchant_category_dirty:$now",
            priority = SideEffectPriority.LOW,
            metadata = SafeEventMetadata.builder()
                .put("affectedCount", affectedCount.toString())
                .put("changedFields", changedFields.joinToString(",") { it.name })
                .build()
        ) {
            // Bulk merchant category dirty marker: no single markDirty API exists.
            // Per-expense learning happens via makeMerchantCategoryLearningAction on create.
            SideEffectOutcome.Skipped(SideEffectSkipReason.DISABLED_BY_SETTINGS)
        }
    }

    private fun makeBulkMerchantCanonicalStatsDirtyAction(
        source: String,
        affectedCount: Int,
        changedFields: Set<BulkChangedField>,
        correlationId: String,
        now: Long
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "bulk_merchant_canonical_stats_dirty",
            category = SideEffectCategory.MERCHANT_LEARNING,
            triggerType = SideEffectTriggerType.EXPENSE_BULK_UPDATED,
            targetEntityType = "EXPENSE",
            targetEntityId = null,
            source = source,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "bulk:$source:$affectedCount:merchant_stats_dirty:$now",
            priority = SideEffectPriority.LOW,
            metadata = SafeEventMetadata.builder()
                .put("affectedCount", affectedCount.toString())
                .put("changedFields", changedFields.joinToString(",") { it.name })
                .build()
        ) {
            // Bulk canonical stats dirty: stats are recomputed per-expense on create/update.
            // No bulk invalidation API exists; per-expense path is sufficient.
            SideEffectOutcome.Skipped(SideEffectSkipReason.DISABLED_BY_SETTINGS)
        }
    }

    private fun makeBulkAnalyticsCacheInvalidationAction(
        source: String,
        affectedCount: Int,
        changedFields: Set<BulkChangedField>,
        correlationId: String,
        now: Long
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "bulk_analytics_cache_invalidation",
            category = SideEffectCategory.CACHE_INVALIDATION,
            triggerType = SideEffectTriggerType.EXPENSE_BULK_UPDATED,
            targetEntityType = "EXPENSE",
            targetEntityId = null,
            source = source,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "bulk:$source:$affectedCount:analytics_cache:$now",
            priority = SideEffectPriority.LOW,
            metadata = SafeEventMetadata.builder()
                .put("affectedCount", affectedCount.toString())
                .put("changedFields", changedFields.joinToString(",") { it.name })
                .build()
        ) {
            // No AnalyticsCacheInvalidator interface exists. Analytics are
            // recomputed on-demand from raw expense data via repository queries.
            SideEffectOutcome.Skipped(SideEffectSkipReason.DISABLED_BY_SETTINGS)
        }
    }

    private fun makeBulkRecurringReconciliationAction(
        source: String,
        affectedCount: Int,
        changedFields: Set<BulkChangedField>,
        correlationId: String,
        now: Long
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "bulk_recurring_reconciliation",
            category = SideEffectCategory.RECURRING,
            triggerType = SideEffectTriggerType.EXPENSE_BULK_UPDATED,
            targetEntityType = "EXPENSE",
            targetEntityId = null,
            source = source,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "bulk:$source:$affectedCount:recurring_reconciliation:$now",
            priority = SideEffectPriority.LOW,
            metadata = SafeEventMetadata.builder()
                .put("affectedCount", affectedCount.toString())
                .put("changedFields", changedFields.joinToString(",") { it.name })
                .build()
        ) {
            try {
                val result = recurringLifecycleCoordinator.get()
                    .reconcileAllLinkedExpensesAfterBulkUpdate(source)
                if (result.hasFailures) SideEffectOutcome.FailedRetryable(
                    "Bulk reconcile: ${result.failed} of ${result.total} failed", "RecurringLifecycle"
                )
                else if (result.meaningfulMutations > 0) SideEffectOutcome.Completed
                else SideEffectOutcome.Skipped(SideEffectSkipReason.NO_WORK)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SideEffectOutcome.FailedRetryable(e.message ?: "Bulk recurring reconcile failed", e.javaClass.name)
            }
        }
    }
}
