package com.yourname.expensetracker.data.backup

import kotlinx.coroutines.flow.Flow

enum class MaintenanceBlockedReason {
    WRITE_BARRIER_DENIED,
    READ_BARRIER_DENIED,
    RESTORE_IN_PROGRESS,
    RESTART_REQUIRED,
    BACKUP_EXPORTING,
    WORKER_STOP_REQUESTED,
    CRITICAL_RECOVERY,
    UNKNOWN
}

interface MaintenanceSafeDiagnosticSink {
    /**
     * Records a blocked operation. Suspend to allow durable persistence before returning.
     * Implementations must not throw — failures are logged and swallowed.
     */
    suspend fun recordBlockedOperation(
        operation: String,
        mode: RestoreMaintenanceMode.Mode,
        pipeline: String? = null,
        entity: String? = null,
        reason: MaintenanceBlockedReason = MaintenanceBlockedReason.UNKNOWN
    )

    /**
     * Records a full [DiagnosticEvent] that could not be written to Room.
     * Preserves all event fields including correlationId, outcome, reasonCode, isTerminal.
     * Implementations must not throw.
     */
    suspend fun recordDiagnosticEvent(
        event: com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent,
        mode: RestoreMaintenanceMode.Mode,
        writeFailure: Throwable? = null
    )

    /** Observe recent blocked operations (most recent last). */
    fun observeRecent(): Flow<List<MaintenanceDiagnosticRecord>>

    /** Remove records older than [cutoffMs]. */
    suspend fun clearOlderThan(cutoffMs: Long)
}
