package com.yourname.expensetracker.domain.transaction.lifecycle

import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugExpenseAuditWriter @Inject constructor(
    private val transactionEventDao: TransactionEventDao,
    private val diagnosticEventWriter: DiagnosticEventWriter,
    private val timeProvider: TimeProvider
) {
    suspend fun writeDeleteAllEvent(
        affectedCount: Int,
        correlationId: String?
    ) {
        transactionEventDao.insert(
            TransactionEvent(
                expenseId = null,
                eventType = LifecycleEventType.DEBUG_DELETE_ALL_EXPENSES.name,
                source = DEBUG_SOURCE,
                actor = DEBUG_ACTOR,
                occurredAt = timeProvider.now(),
                dedupeKey = null,
                duplicateExpenseId = null,
                beforeSnapshot = null,
                afterSnapshot = null,
                metadata = JSONObject()
                    .put("operation", "deleteAllExpenses")
                    .put("aggregate", true)
                    .put("affectedCount", affectedCount)
                    .put("debugOnly", true)
                    .toString(),
                reason = "Debug delete all expenses",
                correlationId = correlationId,
                causationId = null
            )
        )
    }

    suspend fun writeRestoreSnapshotEvent(
        beforeCount: Int,
        restoredCount: Int,
        correlationId: String?
    ) {
        transactionEventDao.insert(
            TransactionEvent(
                expenseId = null,
                eventType = LifecycleEventType.RESTORED_FROM_DEBUG_SNAPSHOT.name,
                source = DEBUG_SOURCE,
                actor = DEBUG_ACTOR,
                occurredAt = timeProvider.now(),
                dedupeKey = null,
                duplicateExpenseId = null,
                beforeSnapshot = null,
                afterSnapshot = null,
                metadata = JSONObject()
                    .put("operation", "restoreDebugSnapshot")
                    .put("aggregate", true)
                    .put("beforeCount", beforeCount)
                    .put("restoredCount", restoredCount)
                    .put("debugOnly", true)
                    .toString(),
                reason = "Restored expenses from debug snapshot",
                correlationId = correlationId,
                causationId = null
            )
        )
    }

    suspend fun emitSnapshotCreatedDiagnosticBestEffort(
        snapshotCount: Int,
        correlationId: String?
    ) {
        try {
            diagnosticEventWriter.emit(
                DiagnosticEvent(
                    pipeline = AppPipeline.TRANSACTION,
                    stage = "DEBUG_EXPENSE_SNAPSHOT_CREATED",
                    outcome = EventOutcome.COMPLETED,
                    severity = EventSeverity.DEBUG,
                    entityType = "Expense",
                    entityId = null,
                    sourceType = DEBUG_SOURCE,
                    correlationId = correlationId
                        ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId(),
                    metadata = SafeEventMetadata.builder()
                        .put("operation", "createDebugSnapshot")
                        .put("snapshotCount", snapshotCount)
                        .put("aggregate", true)
                        .put("debugOnly", true)
                        .build(),
                    isTerminal = true
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to emit debug snapshot diagnostic")
        }
    }

    companion object {
        const val DEBUG_SOURCE = "DEBUG_EXPENSE_MAINTENANCE"
        const val DEBUG_ACTOR = "system:debug"
    }
}
