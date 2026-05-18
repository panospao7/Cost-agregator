package com.yourname.expensetracker.domain.diagnostics

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maintenance-safe diagnostic writer.
 *
 * - Normal mode: writes to Room via [RoomDiagnosticEventWriter].
 * - Maintenance/restore mode or write-barrier denied: falls back to [MaintenanceSafeDiagnosticSink].
 * - Room insert failure: falls back to safe sink.
 * - Safe sink preserves full event details (correlationId, outcome, reasonCode, isTerminal, etc.).
 * - Never throws (except CancellationException which is rethrown).
 */
@Singleton
class CompositeDiagnosticEventWriter @Inject constructor(
    private val roomWriter: RoomDiagnosticEventWriter,
    private val safeSink: MaintenanceSafeDiagnosticSink,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val writeBarrier: DatabaseWriteBarrier
) : DiagnosticEventWriter {

    override suspend fun emit(event: DiagnosticEvent) {
        val mode = restoreMaintenanceMode.currentMode()
        if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
            recordToSafeSink(event, null)
            return
        }
        try {
            writeBarrier.checkWritesAllowed("DiagnosticEventWriter.emit")
            roomWriter.emit(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DatabaseAccessBlockedException) {
            recordToSafeSink(event, e)
        } catch (e: Exception) {
            recordToSafeSink(event, e)
        }
    }

    private suspend fun recordToSafeSink(event: DiagnosticEvent, cause: Throwable?) {
        try {
            safeSink.recordDiagnosticEvent(
                event = event,
                mode = restoreMaintenanceMode.currentMode(),
                writeFailure = cause
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "DiagnosticEventWriter: safe sink also failed for ${event.pipeline}.${event.stage}")
        }
    }
}
