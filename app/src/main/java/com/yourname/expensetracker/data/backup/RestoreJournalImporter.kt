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
 * Imports the last successful restore journal into the restored DB.
 * Idempotent per event — retries missing events on subsequent startups.
 */
@Singleton
class RestoreJournalImporter @Inject constructor(
    private val restoreJournal: RestoreJournal,
    private val operationRunDao: OperationRunDao,
    private val operationRunEventDao: OperationRunEventDao,
    private val timeProvider: TimeProvider
) {
    /** Call on app startup after the DB is healthy. */
    suspend fun importLastSuccessJournalIfPresent() {
        val entry = restoreJournal.readSuccessJournal() ?: return
        val correlationId = entry.operationCorrelationId
        if (correlationId.isBlank()) return

        // DDL-A8-08: skip if already fully imported
        if (restoreJournal.isSuccessJournalImported(correlationId)) return

        val events = restoreJournal.getSuccessJournalEvents()
        if (events.isEmpty()) return

        try {
            // DDL-A8-07: get-or-insert run — don't skip if run exists (events may be missing)
            val runId = operationRunDao.getByCorrelationId(correlationId)?.id
                ?: operationRunDao.insert(
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

            // DDL-A8-07: idempotent per event — check each eventId before inserting
            var allSucceeded = true
            val existingEventIds = operationRunEventDao.getByRunId(runId)
                .mapNotNull { it.eventId }.toSet()

            for (event in events) {
                if (event.eventId in existingEventIds) continue
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
                            isTerminal = event.isTerminal,
                            eventId = event.eventId
                        )
                    )
                }.onFailure {
                    Timber.w(it, "RestoreJournalImporter: failed to insert event ${event.stage}")
                    allSucceeded = false
                }
            }

            // DDL-A8-08: only mark imported after ALL events inserted
            if (allSucceeded) {
                restoreJournal.markSuccessJournalImported(correlationId)
                Timber.i("RestoreJournalImporter: imported correlationId=$correlationId, ${events.size} events")
            } else {
                Timber.w("RestoreJournalImporter: partial import for $correlationId — will retry on next startup")
            }
        } catch (e: Exception) {
            Timber.w(e, "RestoreJournalImporter: import failed, keeping journal for next attempt")
        }
    }
}
