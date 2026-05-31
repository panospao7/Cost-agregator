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
        // DDL-F876-12: legacy journals from older builds may have zero events; still import summary
        if (events.isEmpty()) {
            // Insert a minimal run summary row so startup doesn't keep retrying forever
            try {
                val existingRun = operationRunDao.getByCorrelationId(correlationId)
                if (existingRun == null) {
                    operationRunDao.insert(
                        OperationRun(
                            correlationId = correlationId,
                            operationType = "RESTORE_COSTBACKUP",
                            status = "SUCCESS",
                            startedAt = entry.startedAt,
                            finishedAt = timeProvider.now(),
                            actor = "user",
                            errorSummary = null,
                            metadataJson = """{"legacyEmptyEvents":true}"""
                        )
                    )
                }
                restoreJournal.markSuccessJournalImported(correlationId)
                Timber.i("RestoreJournalImporter: legacy zero-event journal marked imported for $correlationId")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "RestoreJournalImporter: failed to handle legacy empty journal")
            }
            return
        }

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
            // DDL-F876-13: use mutable set so duplicate eventIds within same journal are blocked too
            var allSucceeded = true
            val importedIds = operationRunEventDao.getByRunId(runId)
                .mapNotNull { it.eventId }.toMutableSet()

            for (event in events) {
                if (!importedIds.add(event.eventId)) continue
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.w(e, "RestoreJournalImporter: import failed, keeping journal for next attempt")
        }
    }

    /**
     * P7-CURRENT-016: Import the last FAILED restore/reset journal into the restored DB.
     *
     * The restore/reset path bans Room after the DB swap (P7-CURRENT-005), so terminal
     * failure diagnostics (wrong-password, staged/post-migration/verification failure,
     * rollback failure, reset failure) are written only to the on-disk failure journal.
     * This ingests them into the queryable [OperationRun]/[OperationRunEvent] ledger on the
     * next healthy startup. Idempotent per event — retries missing events on later startups.
     *
     * Call on app startup after the DB is healthy.
     */
    suspend fun importLastFailureJournalIfPresent() {
        val entry = restoreJournal.readFailureJournal() ?: return
        val correlationId = entry.operationCorrelationId
        if (correlationId.isBlank()) return

        // Skip if already fully imported.
        if (restoreJournal.isFailureJournalImported(correlationId)) return

        val events = restoreJournal.getFailureJournalEvents()
        val errorSummary = entry.error
        // The failure journal is shared by the .costbackup restore and reset paths; the
        // specific origin lives in the per-event stage names, so a single ledger
        // operationType is sufficient here.
        val operationType = "RESTORE_OR_RESET"

        try {
            // Get-or-insert run — don't skip if run exists (events may be missing).
            val runId = operationRunDao.getByCorrelationId(correlationId)?.id
                ?: operationRunDao.insert(
                    OperationRun(
                        correlationId = correlationId,
                        operationType = operationType,
                        status = "FAILED_FINAL",
                        startedAt = entry.startedAt,
                        finishedAt = timeProvider.now(),
                        actor = "user",
                        errorSummary = errorSummary
                    )
                )

            if (events.isEmpty()) {
                // Legacy / zero-event failure journal: the run row is enough to make the
                // failure queryable. Mark imported so startup doesn't keep retrying.
                restoreJournal.markFailureJournalImported(correlationId)
                Timber.i("RestoreJournalImporter: failure journal (no events) imported for $correlationId")
                return
            }

            // Idempotent per event — check each eventId before inserting.
            var allSucceeded = true
            val importedIds = operationRunEventDao.getByRunId(runId)
                .mapNotNull { it.eventId }.toMutableSet()

            for (event in events) {
                if (!importedIds.add(event.eventId)) continue
                runCatching {
                    operationRunEventDao.insert(
                        OperationRunEvent(
                            operationRunId = runId,
                            correlationId = event.correlationId,
                            operationType = operationType,
                            stage = event.stage,
                            eventType = "RESTORE_FAILURE_${event.stage}",
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
                    Timber.w(it, "RestoreJournalImporter: failed to insert failure event ${event.stage}")
                    allSucceeded = false
                }
            }

            if (allSucceeded) {
                restoreJournal.markFailureJournalImported(correlationId)
                Timber.i("RestoreJournalImporter: imported failure correlationId=$correlationId, ${events.size} events")
            } else {
                Timber.w("RestoreJournalImporter: partial failure import for $correlationId — will retry on next startup")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.w(e, "RestoreJournalImporter: failure import failed, keeping journal for next attempt")
        }
    }
}
