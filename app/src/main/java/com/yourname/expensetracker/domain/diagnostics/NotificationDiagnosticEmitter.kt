package com.yourname.expensetracker.domain.diagnostics

import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * resort, logged as a safe warning. [CancellationException] is never
 * swallowed.
 *
 * ## Volume coalescing (R26-V)
 *
 * [emit] is a coalescing front door. Routine, high-volume per-notification
 * INFO events with no exception are collapsed into one summary row per
 * (pipeline, stage, outcome, reasonCode) per hour ([COALESCE_WINDOW_MS]).
 * Coalescible events are exactly:
 *   - RECEIVED, non-terminal, no reasonCode, stage "listener" or "refresh";
 *   - terminal DROPPED / [DiagnosticReasonCode.FILTER_REJECTED] at stage "filter";
 *   - terminal DUPLICATE / [DiagnosticReasonCode.DUPLICATE] at stage "dedupe".
 *
 * The first such event of a key opens a window and is written normally.
 * Further events inside the window are suppressed and counted. The first event
 * after the window expires first writes a summary row (same pipeline / stage /
 * outcome / reasonCode / severity / isTerminal, metadata `count` = suppressed
 * events, no correlation/entity/source identifiers of the suppressed events)
 * and is then written itself, opening a new window.
 *
 * A suppressed RECEIVED event is remembered (bounded LRU, [MAX_REMEMBERED_RECEIVED],
 * keyed by correlationId). When a later non-coalescible event (a problem,
 * failure, other stage, ...) arrives for the same correlationId, the remembered
 * RECEIVED row is written first so problem notifications keep their RECEIVED
 * row, and the window count is decremented (only if that window is still open).
 *
 * Never coalesced: WARNING or higher severity, events carrying an exception,
 * captures and every other stage/outcome, and anything while the app is in
 * non-NORMAL maintenance mode (sink path unchanged). [emitOrdered],
 * [emitNonCancellable] and [emitOrderedNonCancellable] bypass coalescing
 * entirely.
 *
 * Timestamps: recovered RECEIVED rows and summary rows are stamped when they are
 * written, not when the original event occurred. Received-to-problem latency is
 * therefore only available via the notification `postTime` metadata, and a summary
 * appears when it is flushed (first event of the key after the window expires), not
 * at the end of its window. Planned rows (summary / recovered RECEIVED plus the
 * triggering event) are written under [NonCancellable] because the bookkeeping has
 * already been committed under the lock.
 *
 * Process-death lower bound: counts are held in memory only, so a process
 * death loses the pending (not yet summarised) count of the open window — at
 * most one window (< 1 h) of routine counts. Reported counts are therefore a
 * lower bound.
 *
 * Decisions are taken under a [Mutex]; all writes happen outside the lock.
 */
@Singleton
class NotificationDiagnosticEmitter @Inject constructor(
    private val writer: DiagnosticEventWriter,
    private val maintenanceSink: MaintenanceSafeDiagnosticSink,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    @com.yourname.expensetracker.di.IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val timeProvider: TimeProvider
) {

    private data class CoalesceKey(
        val pipeline: AppPipeline,
        val stage: String,
        val outcome: EventOutcome,
        val reasonCode: DiagnosticReasonCode?
    )

    private class Window(val startMs: Long, var pendingCount: Int)

    private class RememberedReceived(val key: CoalesceKey, val window: Window, val event: DiagnosticEvent)

    private val lock = Mutex()
    private val windows = HashMap<CoalesceKey, Window>()

    /** Insertion-ordered: the first entry is the eldest. Guarded by [lock]. */
    private val rememberedReceived = LinkedHashMap<String, RememberedReceived>()

    /**
     * Emit a single diagnostic event through the safest available path,
     * coalescing routine per-notification INFO events (see class KDoc).
     */
    suspend fun emit(event: DiagnosticEvent) {
        withContext(ioDispatcher) {
            val mode = restoreMaintenanceMode.currentMode()
            if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
                emitToSink(event, mode, writeFailure = null)
                return@withContext
            }

            val toWrite = lock.withLock { planWrites(event) }
            if (toWrite.isEmpty()) return@withContext
            // The plan has already mutated window / remembered-RECEIVED state under the lock
            // (window replaced, pending count handed to a summary, remembered row removed and
            // its count decremented). The planned rows must therefore be written even if the
            // caller is cancelled right after planning, otherwise a whole window's count (or a
            // problem notification's RECEIVED row) would be lost. The batch is at most two small
            // rows; the writer's own cancellation is not masked: a CancellationException thrown
            // by the writer still propagates.
            withContext(NonCancellable) {
                for (planned in toWrite) {
                    emitRaw(planned)
                }
            }
        }
    }

    /**
     * Emit two events in order (received then terminal), ensuring both are
     * routed through the safe path. Bypasses coalescing.
     */
    suspend fun emitOrdered(received: DiagnosticEvent, terminal: DiagnosticEvent) {
        emitRaw(received)
        emitRaw(terminal)
    }

    /**
     * Best-effort emission that survives parent coroutine cancellation.
     * Use for terminal events that MUST be recorded (e.g. cancellation diagnostics).
     * Bypasses coalescing.
     */
    suspend fun emitNonCancellable(event: DiagnosticEvent) {
        withContext(NonCancellable + ioDispatcher) {
            emitRaw(event)
        }
    }

    /**
     * Ordered emission that survives parent coroutine cancellation.
     * Bypasses coalescing.
     */
    suspend fun emitOrderedNonCancellable(received: DiagnosticEvent, terminal: DiagnosticEvent) {
        withContext(NonCancellable + ioDispatcher) {
            emitRaw(received)
            emitRaw(terminal)
        }
    }

    /** Uncoalesced write: Room writer in NORMAL mode, safe sink otherwise or on writer failure. */
    private suspend fun emitRaw(event: DiagnosticEvent) {
        withContext(ioDispatcher) {
            val mode = restoreMaintenanceMode.currentMode()

            if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
                emitToSink(event, mode, writeFailure = null)
                return@withContext
            }

            try {
                writer.emit(event)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                emitToSink(event, mode, writeFailure = t)
            }
        }
    }

    /**
     * Decides, under [lock], which events to write (in order) for [event].
     * Must not suspend or perform I/O.
     */
    private fun planWrites(event: DiagnosticEvent): List<DiagnosticEvent> {
        val now = timeProvider.now()
        val key = coalesceKeyOrNull(event)

        // A coalescible terminal event (filter / dedupe drop) means the notification finished
        // normally: release its remembered-RECEIVED slot so the LRU keeps slots for in-flight
        // notifications. Its RECEIVED count stays in the window (no recovery, no decrement).
        if (key != null && event.isTerminal && event.correlationId.isNotBlank()) {
            rememberedReceived.remove(event.correlationId)
        }

        if (key == null) {
            val remembered = rememberedReceived.remove(event.correlationId)
                ?: return listOf(event)
            // Only decrement while the remembered event's own window is still the open one.
            if (windows[remembered.key] === remembered.window && remembered.window.pendingCount > 0) {
                remembered.window.pendingCount--
            }
            return listOf(remembered.event, event)
        }

        val window = windows[key]
        // now < startMs (clock moved backwards) is treated as expired so counting never stalls.
        if (window == null || now >= window.startMs + COALESCE_WINDOW_MS || now < window.startMs) {
            val planned = ArrayList<DiagnosticEvent>(2)
            if (window != null && window.pendingCount > 0) {
                planned += summaryEvent(key, event, window.pendingCount)
            }
            planned += event
            windows[key] = Window(startMs = now, pendingCount = 0)
            return planned
        }

        window.pendingCount++
        if (event.outcome == EventOutcome.RECEIVED) {
            rememberSuppressedReceived(key, window, event)
        }
        return emptyList()
    }

    private fun rememberSuppressedReceived(key: CoalesceKey, window: Window, event: DiagnosticEvent) {
        val correlationId = event.correlationId
        if (correlationId.isBlank()) return
        rememberedReceived.remove(correlationId)
        rememberedReceived[correlationId] = RememberedReceived(key, window, event)
        while (rememberedReceived.size > MAX_REMEMBERED_RECEIVED) {
            val eldest = rememberedReceived.keys.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    private fun summaryEvent(key: CoalesceKey, template: DiagnosticEvent, count: Int) = DiagnosticEvent(
        pipeline = key.pipeline,
        stage = key.stage,
        outcome = key.outcome,
        severity = template.severity,
        reasonCode = key.reasonCode,
        metadata = SafeEventMetadata.builder().put("count", count).build(),
        isTerminal = template.isTerminal
    )

    private fun coalesceKeyOrNull(event: DiagnosticEvent): CoalesceKey? {
        if (event.severity != EventSeverity.INFO || event.exception != null) return null
        val coalescible = when {
            event.outcome == EventOutcome.RECEIVED ->
                !event.isTerminal && event.reasonCode == null &&
                    (event.stage == STAGE_LISTENER || event.stage == STAGE_REFRESH)
            event.outcome == EventOutcome.DROPPED ->
                event.isTerminal && event.stage == STAGE_FILTER &&
                    event.reasonCode == DiagnosticReasonCode.FILTER_REJECTED
            event.outcome == EventOutcome.DUPLICATE ->
                event.isTerminal && event.stage == STAGE_DEDUPE &&
                    event.reasonCode == DiagnosticReasonCode.DUPLICATE
            else -> false
        }
        return if (coalescible) CoalesceKey(event.pipeline, event.stage, event.outcome, event.reasonCode) else null
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
        } catch (e: CancellationException) {
            throw e
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

    companion object {
        /** Coalescing window per key: one hour. */
        const val COALESCE_WINDOW_MS: Long = 60L * 60L * 1000L

        /** Max suppressed RECEIVED events remembered for problem-notification recovery. */
        const val MAX_REMEMBERED_RECEIVED: Int = 64

        private const val STAGE_LISTENER = "listener"
        private const val STAGE_REFRESH = "refresh"
        private const val STAGE_FILTER = "filter"
        private const val STAGE_DEDUPE = "dedupe"
    }
}
