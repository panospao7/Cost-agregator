package com.yourname.expensetracker.domain.diagnostics

import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Safe diagnostic emitter for notification capture/processing events.
 *
 * Routes writes through [DiagnosticEventWriter] during normal operation and
 * falls back to [MaintenanceSafeDiagnosticSink] when:
 *   - The app is in restore/maintenance/backup mode (non-NORMAL), or
 *   - The normal Room writer throws.
 *
 * Diagnostic failures must never crash notification processing or silently
 * disappear — they are always recorded in the fallback sink or, as a last
 * resort, logged as a safe warning.
 */
@Singleton
class NotificationDiagnosticEmitter @Inject constructor(
    private val writer: DiagnosticEventWriter,
    private val maintenanceSink: MaintenanceSafeDiagnosticSink,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    @com.yourname.expensetracker.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /**
     * Emit a single diagnostic event through the safest available path.
     */
    suspend fun emit(event: DiagnosticEvent) {
        withContext(ioDispatcher) {
            val mode = restoreMaintenanceMode.currentMode()

            if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
                emitToSink(event, mode, writeFailure = null)
                return@withContext
            }

            try {
                writer.emit(event)
            } catch (t: Throwable) {
                emitToSink(event, mode, writeFailure = t)
            }
        }
    }

    /**
     * Emit two events in order (received then terminal), ensuring both are
     * routed through the safe path.
     */
    suspend fun emitOrdered(received: DiagnosticEvent, terminal: DiagnosticEvent) {
        emit(received)
        emit(terminal)
    }

    /**
     * Best-effort emission that survives parent coroutine cancellation.
     * Use for terminal events that MUST be recorded (e.g. cancellation diagnostics).
     */
    suspend fun emitNonCancellable(event: DiagnosticEvent) {
        withContext(NonCancellable + ioDispatcher) {
            emit(event)
        }
    }

    /**
     * Ordered emission that survives parent coroutine cancellation.
     */
    suspend fun emitOrderedNonCancellable(received: DiagnosticEvent, terminal: DiagnosticEvent) {
        withContext(NonCancellable + ioDispatcher) {
            emit(received)
            emit(terminal)
        }
    }

    private suspend fun emitToSink(
        event: DiagnosticEvent,
        mode: RestoreMaintenanceMode.Mode,
        writeFailure: Throwable?
    ) {
        try {
            maintenanceSink.recordDiagnosticEvent(
                event = event,
                mode = mode,
                writeFailure = writeFailure
            )
        } catch (sinkFailure: Throwable) {
            Timber.w(
                sinkFailure,
                "Diagnostic fallback sink failed: pipeline=%s stage=%s outcome=%s",
                event.pipeline,
                event.stage,
                event.outcome
            )
        }
    }
}
