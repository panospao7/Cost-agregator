package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.data.database.dao.OperationRunDao
import com.yourname.expensetracker.data.database.dao.OperationRunEventDao
import com.yourname.expensetracker.data.database.entity.OperationRun
import com.yourname.expensetracker.data.database.entity.OperationRunEvent
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DDL-016-07: Imports the last successful restore journal into the restored DB
 * so the restore operation trail is queryable after app restart.
 */
@Singleton
class RestoreJournalImporter @Inject constructor(
    private val restoreJournal: RestoreJournal,
    private val operationRunDao: OperationRunDao,
    private val operationRunEventDao: OperationRunEventDao,
    private val timeProvider: TimeProvider
) {
    /** Call on app startup after the DB is healthy. Idempotent. */
    suspend fun importLastSuccessJournalIfPresent() {
        val entry = restoreJournal.readSuccessJournal() ?: return
        val correlationId = entry.operationCorrelationId
        if (correlationId.isBlank()) return

        try {
            // Idempotent: skip if already imported
            val existing = operationRunDao.getByCorrelationId(correlationId)
            if (existing != null) {
                Timber.d("RestoreJournalImporter: correlationId $correlationId already imported")
                return
            }

            val runId = operationRunDao.insert(
                OperationRun(
                    correlationId = correlationId,
                    operationType = "RESTORE_COSTBACKUP",
                    status = "SUCCESS",
                    startedAt = entry.startedAt,
                    finishedAt = timeProvider.now(),
                    actor = "user",
                    errorSummary = null
                )
            )

            val events = restoreJournal.getSuccessJournalEvents()
            events.forEach { event ->
                runCatching {
                    operationRunEventDao.insert(
                        OperationRunEvent(
                            operationRunId = runId,
                            correlationId = event.correlationId,
                            operationType = "RESTORE_COSTBACKUP",
                            stage = event.stage,
                            eventType = "RESTORE_COSTBACKUP_${event.stage}",
                            outcome = event.outcome,
                            severity = event.severity,
                            reasonCode = event.reasonCode,
                            occurredAt = event.occurredAt,
                            exceptionClass = event.exceptionClass,
                            exceptionMessage = event.exceptionMessageSafe,
                            isTerminal = event.isTerminal
                        )
                    )
                }.onFailure { Timber.w(it, "RestoreJournalImporter: failed to insert event ${event.stage}") }
            }

            Timber.i("RestoreJournalImporter: imported restore operation run correlationId=$correlationId, ${events.size} events")
        } catch (e: Exception) {
            Timber.w(e, "RestoreJournalImporter: import failed, keeping journal for next attempt")
            return // Do not delete journal on failure
        }
    }
}
