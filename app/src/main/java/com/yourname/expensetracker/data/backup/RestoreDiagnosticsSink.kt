package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.OperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import timber.log.Timber

/**
 * DDL-016-01 / DDL-A8-01: Restore-phase diagnostic sink that:
 *  - Appends every event to RestoreJournal (always, best-effort)
 *  - Writes to safe sink (always, best-effort)
 *  - Writes to Room OperationRunHandle only BEFORE DB swap
 *
 * After [markLiveDbSwapStarted]: Room writes disabled; journal + safe sink only.
 */
class RestoreDiagnosticsSink(
    private val operationRunHandle: OperationRunHandle?,
    private val restoreJournal: RestoreJournal,
    private val safeSink: MaintenanceSafeDiagnosticSink,
    private val maintenanceMode: RestoreMaintenanceMode,
    val correlationId: String,
    private val operationType: String,
    private val sanitizer: EventMetadataSanitizer = EventMetadataSanitizer()
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
        // DDL-512-03: serialize metadata for the journal
        val safeMetadataJson = if (metadata.isEmpty()) null else
            sanitizer.sanitizeJsonString(metadata.toJson())

        // DDL-512-01: append to the right journal file (active if present, else failure journal)
        runCatching {
            if (restoreJournal.hasJournal()) {
                restoreJournal.appendEvent(
                    correlationId = correlationId,
                    stage = stage,
                    outcome = outcome.name,
                    severity = severity.name,
                    reasonCode = reasonCode?.name,
                    metadataJson = safeMetadataJson,
                    exceptionClass = exception?.javaClass?.simpleName,
                    exceptionMessageSafe = sanitizer.sanitizeExceptionMessage(exception?.message),
                    isTerminal = isTerminal
                )
            } else {
                // Active journal was already renamed to failure journal by failJournal()
                restoreJournal.appendEventToFailureJournal(
                    correlationId = correlationId,
                    stage = stage,
                    outcome = outcome.name,
                    severity = severity.name,
                    reasonCode = reasonCode?.name,
                    metadataJson = safeMetadataJson,
                    exceptionClass = exception?.javaClass?.simpleName,
                    exceptionMessageSafe = sanitizer.sanitizeExceptionMessage(exception?.message),
                    isTerminal = isTerminal
                )
            }
        }.onFailure { journalError ->
            Timber.w(journalError, "RestoreDiagnosticsSink: journal append failed (stage=$stage)")
        }

        // Always write to safe sink
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
                Timber.w(error, "RestoreDiagnosticsSink: operation event write failed (stage=$stage)")
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
