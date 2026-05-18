package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.OperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import timber.log.Timber

/**
 * DDL-016-01: Restore-phase diagnostic sink that prevents Room operation event writes
 * after the live DB has been swapped.
 *
 * Before swap: writes to both Room OperationRunHandle AND restore journal.
 * After [markLiveDbSwapStarted]: Room writes are disabled; journal + safe sink only.
 *
 * This ensures a failed diagnostic insert after a successful restore cannot enter
 * the rollback path or otherwise corrupt the restore result.
 */
class RestoreDiagnosticsSink(
    private val operationRunHandle: OperationRunHandle?,
    private val safeSink: MaintenanceSafeDiagnosticSink,
    private val maintenanceMode: RestoreMaintenanceMode,
    val correlationId: String,
    private val operationType: String
) {
    @Volatile
    private var roomAllowed: Boolean = true

    /** Call immediately after closeLiveDatabaseForFileSwap(). */
    fun markLiveDbSwapStarted() {
        roomAllowed = false
    }

    suspend fun event(
        stage: String,
        outcome: EventOutcome,
        severity: EventSeverity = EventSeverity.INFO,
        reasonCode: DiagnosticReasonCode? = null,
        metadata: SafeEventMetadata = SafeEventMetadata.empty(),
        exception: Throwable? = null,
        isTerminal: Boolean = false
    ) {
        // Always record to safe sink
        runCatching {
            safeSink.recordDiagnosticEvent(
                event = DiagnosticEvent(
                    pipeline = AppPipeline.BACKUP_RESTORE,
                    stage = stage,
                    outcome = outcome,
                    severity = severity,
                    reasonCode = reasonCode,
                    correlationId = correlationId,
                    metadata = metadata,
                    exception = exception,
                    isTerminal = isTerminal
                ),
                mode = maintenanceMode.currentMode()
            )
        }

        // Room operation events only before DB swap
        val handle = operationRunHandle
        if (roomAllowed && handle != null) {
            runCatching {
                handle.event(
                    stage = stage,
                    outcome = outcome,
                    severity = severity,
                    reasonCode = reasonCode,
                    metadata = metadata,
                    exception = exception,
                    isTerminal = isTerminal
                )
            }.onFailure { error ->
                Timber.w(error, "RestoreDiagnosticsSink: operation event write failed (stage=$stage) — already recorded to safe sink")
            }
        }
    }

    /** Best-effort Room finalization — only before swap. */
    suspend fun finalizeRunFailed(reason: String, error: Throwable? = null) {
        val handle = operationRunHandle
        if (roomAllowed && handle != null) {
            runCatching { handle.failedFinal(reason, error) }
                .onFailure { Timber.w(it, "RestoreDiagnosticsSink: failedFinal write failed") }
        }
    }
}
