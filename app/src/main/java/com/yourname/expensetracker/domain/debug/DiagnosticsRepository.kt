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

interface DiagnosticsRepository {
    suspend fun getTraceByCorrelationId(correlationId: String): DiagnosticTrace
    suspend fun getRecentFailures(limit: Int = 50): List<PipelineDiagnosticEvent>
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
        // DDL-016-16: use .first() to avoid endless Flow collection
        val safeSinkEvents = runCatching {
            safeSink.observeRecent().first().filter { it.correlationId == correlationId }
        }.getOrDefault(emptyList())
        // DDL-016-17: include restore journal events in trace
        val journalEvents = runCatching {
            restoreJournal.getEventsByCorrelationId(correlationId)
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

    override suspend fun getRecentFailures(limit: Int): List<PipelineDiagnosticEvent> =
        runCatching { pipelineEventDao.getRecentFailures(limit) }.getOrDefault(emptyList())
}
