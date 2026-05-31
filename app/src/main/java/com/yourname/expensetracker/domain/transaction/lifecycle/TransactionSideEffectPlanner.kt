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
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.sideeffect.*
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionSideEffectPlanner @Inject constructor(
    private val budgetMonitor: Lazy<BudgetMonitor>,
    private val anomalyAlertOrchestrator: AnomalyAlertOrchestrator,
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val merchantNormalizationRepository: MerchantNormalizationRepository,
    private val recurringLifecycleCoordinator: Lazy<RecurringLifecycleCoordinator>,
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao
) {

    fun planCreated(
        expenseId: Long,
        source: ExpenseSource,
        correlationId: String?
    ): PostCommitActionBatch {
        val corrId = correlationId ?: CorrelationIds.newId()
        val actions = listOf(
            makeBudgetCheckAction(expenseId, source, corrId, SideEffectTriggerType.EXPENSE_CREATED),
            makeAnomalyAlertAction(expenseId, source.name, corrId, SideEffectTriggerType.EXPENSE_CREATED),
            makeMerchantCategoryLearningAction(expenseId, source.name, corrId),
            makeMerchantCanonicalStatsAction(expenseId, source.name, corrId),
            makeRecurringMatchingAction(expenseId, source, corrId)
        )
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
            TransactionUpdateKind.CATEGORY_ONLY, TransactionUpdateKind.BUSINESS_FLAGS_ONLY -> {
                actions.add(makeBudgetCheckAction(expenseId, ExpenseSource.UNKNOWN, corrId, SideEffectTriggerType.EXPENSE_UPDATED))
                actions.add(makeAnomalyAlertAction(expenseId, source, corrId, SideEffectTriggerType.EXPENSE_UPDATED))
            }
            TransactionUpdateKind.FULL, TransactionUpdateKind.MERCHANT, TransactionUpdateKind.TYPE, TransactionUpdateKind.TRANSFER_DETAILS,
            TransactionUpdateKind.AMOUNT, TransactionUpdateKind.DATE, TransactionUpdateKind.CURRENCY,
            TransactionUpdateKind.OWNERSHIP, TransactionUpdateKind.PAYMENT_CORE -> {
                actions.add(makeBudgetCheckAction(expenseId, ExpenseSource.UNKNOWN, corrId, SideEffectTriggerType.EXPENSE_UPDATED))
                actions.add(makeAnomalyAlertAction(expenseId, source, corrId, SideEffectTriggerType.EXPENSE_UPDATED))
                actions.add(makeMerchantCategoryLearningAction(expenseId, source, corrId))
                actions.add(makeMerchantCanonicalStatsAction(expenseId, source, corrId))
                if (kind.affectsRecurringMatch()) {
                    actions.add(makeRecurringReconcileAction(expenseId, source, corrId))
                }
            }
        }

        return PostCommitActionBatch(corrId, actions)
    }

    fun planDeleted(
        expenseId: Long,
        source: String,
        correlationId: String?
    ): PostCommitActionBatch {
        val corrId = correlationId ?: CorrelationIds.newId()
        val actions = listOf(
            makeBudgetCheckAction(expenseId, ExpenseSource.UNKNOWN, corrId, SideEffectTriggerType.EXPENSE_DELETED),
            makeRecurringUnlinkAction(expenseId, source, corrId)
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
                    idempotencyKey = "bulk:$source:0:budget_check",
                    priority = SideEffectPriority.LOW,
                    metadata = SafeEventMetadata.builder().put("affectedCount", "0").build()
                ) { SideEffectOutcome.Skipped(SideEffectSkipReason.NO_WORK) }
            ))
        }

        val normalizedFields = changedFields.ifEmpty { setOf(BulkChangedField.UNKNOWN) }
        val actions = mutableListOf<PostCommitAction>()

        if (normalizedFields.affectsBudget()) {
            actions += makeBulkBudgetCheckAction(source, affectedCount, normalizedFields, corrId)
        }
        if (normalizedFields.affectsAnomaly()) {
            actions += makeBulkAnomalyInvalidationAction(source, affectedCount, normalizedFields, corrId)
        }
        if (normalizedFields.affectsMerchantLearning()) {
            actions += makeBulkMerchantCategoryDirtyAction(source, affectedCount, normalizedFields, corrId)
            actions += makeBulkMerchantCanonicalStatsDirtyAction(source, affectedCount, normalizedFields, corrId)
        }
        if (normalizedFields.affectsAnalyticsCache()) {
            actions += makeBulkAnalyticsCacheInvalidationAction(source, affectedCount, normalizedFields, corrId)
        }
        if (normalizedFields.affectsRecurring()) {
            actions += makeBulkRecurringReconciliationAction(source, affectedCount, normalizedFields, corrId)
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
        correlationId: String
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "merchant_category_pattern_learning",
            category = SideEffectCategory.MERCHANT_LEARNING,
            triggerType = SideEffectTriggerType.EXPENSE_CREATED,
            targetEntityType = "EXPENSE",
            targetEntityId = expenseId,
            source = source,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "expense:$expenseId:created:merchant_category_learning",
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
        correlationId: String
    ): PostCommitAction {
        return PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "merchant_canonical_stats_update",
            category = SideEffectCategory.MERCHANT_LEARNING,
            triggerType = SideEffectTriggerType.EXPENSE_CREATED,
            targetEntityType = "EXPENSE",
            targetEntityId = expenseId,
            source = source,
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "expense:$expenseId:created:merchant_stats",
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
                    is com.yourname.expensetracker.domain.recurring.lifecycle.RecurringExpenseReconcileResult.Skipped ->
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

    // --- Bulk action factory methods ---

    private fun makeBulkBudgetCheckAction(
        source: String,
        affectedCount: Int,
        changedFields: Set<BulkChangedField>,
        correlationId: String
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
            idempotencyKey = "bulk:$source:$affectedCount:budget_check",
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
        correlationId: String
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
            idempotencyKey = "bulk:$source:$affectedCount:anomaly_invalidation",
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
        correlationId: String
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
            idempotencyKey = "bulk:$source:$affectedCount:merchant_category_dirty",
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
        correlationId: String
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
            idempotencyKey = "bulk:$source:$affectedCount:merchant_stats_dirty",
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
        correlationId: String
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
            idempotencyKey = "bulk:$source:$affectedCount:analytics_cache",
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
        correlationId: String
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
            idempotencyKey = "bulk:$source:$affectedCount:recurring_reconciliation",
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
