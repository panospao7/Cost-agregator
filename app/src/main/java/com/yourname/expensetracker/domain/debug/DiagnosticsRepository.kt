package com.yourname.expensetracker.domain.debug

import com.yourname.expensetracker.data.backup.MaintenanceDiagnosticRecord
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao
import com.yourname.expensetracker.data.database.dao.OperationRunDao
import com.yourname.expensetracker.data.database.dao.OperationRunEventDao
import com.yourname.expensetracker.data.database.dao.PipelineDiagnosticEventDao
import com.yourname.expensetracker.data.database.entity.BackgroundJobRun
import com.yourname.expensetracker.data.database.entity.OperationRun
import com.yourname.expensetracker.data.database.entity.OperationRunEvent
import com.yourname.expensetracker.data.database.entity.PipelineDiagnosticEvent
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class DiagnosticTrace(
    val correlationId: String,
    val pipelineEvents: List<PipelineDiagnosticEvent>,
    val operationRuns: List<OperationRun>,
    val operationRunEvents: List<OperationRunEvent>,
    val workerRuns: List<BackgroundJobRun>,
    val safeSinkEvents: List<MaintenanceDiagnosticRecord>,
    val restoreJournalEvents: List<RestoreJournal.RestoreJournalEvent> = emptyList()
)

/** DDL-A8-20: unified failure summary from all diagnostic sources. */
data class DiagnosticFailureSummary(
    val source: String,
    val correlationId: String?,
    val pipelineOrOperation: String?,
    val stage: String,
    val outcome: String,
    val severity: String,
    val reasonCode: String?,
    val occurredAt: Long,
    val messageSafe: String?
)

interface DiagnosticsRepository {
    suspend fun getTraceByCorrelationId(correlationId: String): DiagnosticTrace
    suspend fun getRecentFailures(limit: Int = 50): List<DiagnosticFailureSummary>
}

@Singleton
class DiagnosticsRepositoryImpl @Inject constructor(
    private val pipelineEventDao: PipelineDiagnosticEventDao,
    private val operationRunDao: OperationRunDao,
    private val operationRunEventDao: OperationRunEventDao,
    private val backgroundJobRunDao: BackgroundJobRunDao,
    private val safeSink: MaintenanceSafeDiagnosticSink,
    private val restoreJournal: RestoreJournal
) : DiagnosticsRepository {

    override suspend fun getTraceByCorrelationId(correlationId: String): DiagnosticTrace {
        val pipelineEvents = runCatching { pipelineEventDao.getByCorrelationId(correlationId) }.getOrDefault(emptyList())
        val operationRun = runCatching { operationRunDao.getByCorrelationId(correlationId) }.getOrNull()
        val operationRuns = if (operationRun != null) listOf(operationRun) else emptyList()
        val operationRunEvents = runCatching { operationRunEventDao.getByCorrelationId(correlationId) }.getOrDefault(emptyList())
        val workerRuns = runCatching { backgroundJobRunDao.getByCorrelationId(correlationId) }.getOrDefault(emptyList())
        val safeSinkEvents = runCatching {
            safeSink.observeRecent().first().filter { it.correlationId == correlationId }
        }.getOrDefault(emptyList())
        // DDL-A8-19: read events from active, success, and failure journals
        val journalEvents = runCatching {
            restoreJournal.getAllDiagnosticEventsByCorrelationId(correlationId)
        }.getOrDefault(emptyList())

        return DiagnosticTrace(
            correlationId = correlationId,
            pipelineEvents = pipelineEvents,
            operationRuns = operationRuns,
            operationRunEvents = operationRunEvents,
            workerRuns = workerRuns,
            safeSinkEvents = safeSinkEvents,
            restoreJournalEvents = journalEvents
        )
    }

    override suspend fun getRecentFailures(limit: Int): List<DiagnosticFailureSummary> {
        val all = mutableListOf<DiagnosticFailureSummary>()

        // Pipeline diagnostics
        runCatching {
            pipelineEventDao.getRecentFailures(limit).forEach { e ->
                all.add(DiagnosticFailureSummary(
                    source = "pipeline", correlationId = e.correlationId,
                    pipelineOrOperation = e.pipeline, stage = e.stage,
                    outcome = e.outcome, severity = e.severity ?: "WARNING",
                    reasonCode = e.reasonCode, occurredAt = e.timestamp,
                    messageSafe = e.exceptionMessage
                ))
            }
        }

        // Operation run events
        runCatching {
            operationRunEventDao.getRecentFailures(limit).forEach { e ->
                all.add(DiagnosticFailureSummary(
                    source = "operation_event", correlationId = e.correlationId,
                    pipelineOrOperation = e.operationType, stage = e.stage,
                    outcome = e.outcome, severity = e.severity,
                    reasonCode = e.reasonCode, occurredAt = e.occurredAt,
                    messageSafe = e.exceptionMessage
                ))
            }
        }

        // Worker runs
        runCatching {
            backgroundJobRunDao.getRecentFailedRuns(limit).forEach { r ->
                all.add(DiagnosticFailureSummary(
                    source = "worker", correlationId = r.correlationId,
                    pipelineOrOperation = r.workerName, stage = "worker_run",
                    outcome = r.status, severity = "WARNING",
                    reasonCode = r.statusReason, occurredAt = r.startedAt,
                    messageSafe = r.errorMessage
                ))
            }
        }

        // Safe sink records
        runCatching {
            val failureOutcomes = setOf("FAILED_RETRYABLE", "FAILED_FINAL", "BLOCKED", "DROPPED", "CANCELLED", "SIDE_EFFECT_FAILED")
            safeSink.observeRecent().first()
                .filter { r -> r.outcome != null && r.outcome in failureOutcomes }
                .forEach { r ->
                    all.add(DiagnosticFailureSummary(
                        source = "safe_sink", correlationId = r.correlationId,
                        pipelineOrOperation = r.pipeline, stage = r.operation,
                        outcome = r.outcome ?: r.reason, severity = r.severity ?: "WARNING",
                        reasonCode = r.reasonCode, occurredAt = r.timestamp,
                        messageSafe = r.exceptionMessageSafe
                    ))
                }
        }

        // Restore journal (active + success + failure)
        runCatching {
            // DDL-512-10: read all three journal files instead of only success + blank-corrId active
            val failureLikeOutcomes = setOf(
                "FAILED_FINAL", "FAILED_RETRYABLE", "BLOCKED", "DROPPED",
                "CANCELLED", "SIDE_EFFECT_FAILED"
            )
            val failureLikeSeverities = setOf("WARNING", "ERROR", "CRITICAL")
            restoreJournal.getAllDiagnosticEvents()
                .filter { e ->
                    e.outcome in failureLikeOutcomes || e.severity in failureLikeSeverities
                }
                .forEach { e ->
                    all.add(DiagnosticFailureSummary(
                        source = "restore_journal", correlationId = e.correlationId.takeIf { it.isNotBlank() },
                        pipelineOrOperation = "RESTORE_COSTBACKUP", stage = e.stage,
                        outcome = e.outcome, severity = e.severity,
                        reasonCode = e.reasonCode, occurredAt = e.occurredAt,
                        messageSafe = e.exceptionMessageSafe
                    ))
                }
        }

        return all.sortedByDescending { it.occurredAt }.take(limit)
    }
}
