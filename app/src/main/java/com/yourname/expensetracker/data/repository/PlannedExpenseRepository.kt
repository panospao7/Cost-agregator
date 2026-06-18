package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

/**
 * Repository for planned-expense CRUD.
 *
 * ## Invariants (P2-16)
 * - [addPlannedExpense] sets [PlannedExpense.createdAt] and [updatedAt] if the
 *   incoming entity has them at 0L.
 * - The insert result is checked: a -1 from [OnConflictStrategy.IGNORE] throws
 *   [IllegalStateException] so callers do not silently proceed with a zero ID.
 * - [PlannedExpense.openSourceOccurrenceKey] is populated from
 *   [PlannedExpense.sourceOccurrenceKey] on insert when not already set.
 * - All write methods check [DatabaseWriteBarrier] before mutation.
 */
@Singleton
class PlannedExpenseRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val plannedExpenseDao: PlannedExpenseDao,
    private val timeProvider: TimeProvider,
    /**
     * P6-CURRENT-026: Durable lifecycle diagnostics for planned-expense CRUD. Nullable + defaulted
     * so existing test/construction sites compile unchanged; Hilt injects the real writer in
     * production via the existing DiagnosticsModule binding. Emission is always best-effort.
     */
    private val diagnosticEventWriter: com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter? = null,
    private val diagnosticSink: com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink? = null
) {
    private companion object {
        val VALID_STATUSES = setOf("PLANNED", "FULFILLED", "SKIPPED", "CANCELLED")
    }

    fun getAllPlannedExpenses(): Flow<List<PlannedExpense>> {
        return plannedExpenseDao.getAllPlannedExpenses()
    }

    fun getPlannedExpensesForPeriod(startMs: Long, endMs: Long): Flow<List<PlannedExpense>> {
        return plannedExpenseDao.getPlannedExpensesForPeriod(startMs, endMs)
    }

    /**
     * Inserts a [PlannedExpense] with lifecycle invariants enforced:
     * - Sets [PlannedExpense.createdAt] and [updatedAt] if missing.
     * - Populates [PlannedExpense.openSourceOccurrenceKey] from
     *   [PlannedExpense.sourceOccurrenceKey] if not already set.
     * - Checks the insert result; throws [IllegalStateException] on duplicate
     *   (DAO uses [OnConflictStrategy.IGNORE] → returns -1 on conflict).
     *
     * @return the inserted row ID (always > 0).
     * @throws IllegalStateException if a duplicate was silently skipped.
     */
    suspend fun addPlannedExpense(expense: PlannedExpense): Long {
        writeBarrier.checkWritesAllowed("PlannedExpenseRepository.addPlannedExpense")
        // P6-CURRENT-029: reject invalid money/date/status before it enters forecast math.
        try {
            require(expense.amount.isFinite() && expense.amount > 0.0) {
                "Planned expense amount must be a positive finite number"
            }
            require(expense.currency.isNotBlank()) { "Planned expense currency must not be blank" }
            require(expense.date > 0L) { "Planned expense date must be a positive epoch timestamp" }
            require(expense.status in VALID_STATUSES) {
                "Planned expense status must be one of $VALID_STATUSES"
            }
        } catch (e: IllegalArgumentException) {
            // P6-CURRENT-026: durable record of a rejected planned-expense write. Best-effort —
            // the rejection itself still propagates so the caller's contract is unchanged.
            emitPlannedDiagnostic(
                stage = "PLANNED_ADD_REJECTED",
                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DROPPED,
                entityId = expense.id.takeIf { it != 0L },
                severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.WARNING,
                exception = e,
                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                    .put("reason", "VALIDATION_REJECTED")
                    .build()
            )
            throw e
        }
        val now = timeProvider.now()
        val withTimestamps = expense.copy(
            createdAt = if (expense.createdAt == 0L) now else expense.createdAt,
            updatedAt = now,
            openSourceOccurrenceKey = expense.openSourceOccurrenceKey
                ?: expense.sourceOccurrenceKey
        )
        val id = plannedExpenseDao.insertPlannedExpense(withTimestamps)
        if (id == -1L) {
            Timber.w("PlannedExpense insert conflict — duplicate silently skipped (sourceOccurrenceKey=%s)",
                expense.sourceOccurrenceKey)
            emitPlannedDiagnostic(
                stage = "PLANNED_ADD_DUPLICATE",
                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DUPLICATE,
                entityId = null,
                severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.WARNING,
                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                    .put("reason", "IGNORE_CONFLICT")
                    .build()
            )
            throw IllegalStateException("Duplicate planned expense insert skipped by IGNORE conflict strategy")
        }
        emitPlannedDiagnostic(
            stage = "PLANNED_ADDED",
            outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.CREATED,
            entityId = id,
            metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                .put("amount", expense.amount)
                .put("currency", expense.currency)
                .put("status", expense.status)
                .build()
        )
        return id
    }

    suspend fun deletePlannedExpense(expense: PlannedExpense) {
        writeBarrier.checkWritesAllowed("PlannedExpenseRepository.deletePlannedExpense")
        plannedExpenseDao.deletePlannedExpense(expense)
        emitPlannedDiagnostic(
            stage = "PLANNED_DELETED",
            outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DELETED,
            entityId = expense.id.takeIf { it != 0L }
        )
    }

    suspend fun deletePlannedExpenseById(id: Long) {
        writeBarrier.checkWritesAllowed("PlannedExpenseRepository.deletePlannedExpenseById")
        plannedExpenseDao.deletePlannedExpenseById(id)
        emitPlannedDiagnostic(
            stage = "PLANNED_DELETED",
            outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DELETED,
            entityId = id.takeIf { it != 0L }
        )
    }

    /**
     * P6-CURRENT-026: Best-effort durable lifecycle diagnostic for planned-expense CRUD.
     *
     * Mirrors the [com.yourname.expensetracker.domain.budget.BudgetMonitor] emission pattern:
     * write-barrier-guarded and tolerant of [com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException]
     * (routed to the [diagnosticSink]). A failure to emit NEVER fails or rolls back the mutation —
     * all exceptions except [kotlinx.coroutines.CancellationException] are swallowed.
     */
    private suspend fun emitPlannedDiagnostic(
        stage: String,
        outcome: com.yourname.expensetracker.domain.diagnostics.EventOutcome,
        entityId: Long?,
        severity: com.yourname.expensetracker.domain.diagnostics.EventSeverity =
            com.yourname.expensetracker.domain.diagnostics.EventSeverity.INFO,
        exception: Throwable? = null,
        metadata: com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata =
            com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.empty()
    ) {
        val writer = diagnosticEventWriter ?: return
        try {
            writeBarrier.checkWritesAllowed("PlannedExpenseRepository.diagnostic")
            writer.emit(
                com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.BUDGET,
                    stage = stage,
                    outcome = outcome,
                    severity = severity,
                    entityType = "PlannedExpense",
                    entityId = entityId,
                    exception = exception,
                    metadata = metadata
                )
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
                diagnosticSink?.recordBlockedOperation("PlannedExpenseRepository.diagnostic", e.mode, "P6")
            } else {
                Timber.w(e, "PlannedExpenseRepository: skipping diagnostic insert (stage=%s)", stage)
            }
        }
    }
}
