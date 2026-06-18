package com.yourname.expensetracker.data.backup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Timber-only fallback — no persistence. Used in tests. */
@Singleton
class TimberMaintenanceSafeDiagnosticSink @Inject constructor() : MaintenanceSafeDiagnosticSink {
    override suspend fun recordBlockedOperation(
        operation: String,
        mode: RestoreMaintenanceMode.Mode,
        pipeline: String?,
        entity: String?,
        reason: MaintenanceBlockedReason
    ) {
        Timber.w("BLOCKED[%s/%s] op=%s pipeline=%s entity=%s",
            mode.label, reason, operation, pipeline ?: "-", entity ?: "-")
    }

    override suspend fun recordDiagnosticEvent(
        event: com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent,
        mode: RestoreMaintenanceMode.Mode,
        writeFailure: Throwable?
    ) {
        Timber.w("SAFE_SINK[%s] pipeline=%s stage=%s outcome=%s corr=%s terminal=%b",
            mode.label, event.pipeline.name, event.stage, event.outcome.name,
            event.correlationId.take(8), event.isTerminal)
    }

    override fun observeRecent(): Flow<List<MaintenanceDiagnosticRecord>> = flowOf(emptyList())
    override suspend fun clearOlderThan(cutoffMs: Long) { /* no-op */ }
}
