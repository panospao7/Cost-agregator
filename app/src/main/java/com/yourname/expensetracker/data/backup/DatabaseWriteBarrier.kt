package com.yourname.expensetracker.data.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Marks a coroutine as already counted as an in-flight write (nested [DatabaseWriteBarrier.runWrite]). */
private object InFlightWriteKey : CoroutineContext.Key<InFlightWriteElement>

private object InFlightWriteElement : AbstractCoroutineContextElement(InFlightWriteKey)

@Singleton
class DatabaseWriteBarrier @Inject constructor(
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {
    private val inFlight = MutableStateFlow(0)

    /** Compatibility overload — kept for existing callers. */
    fun checkWritesAllowed(operation: String) {
        checkWritesAllowed(DatabaseAccessOperation(operation))
    }

    fun checkWritesAllowed(operation: DatabaseAccessOperation) {
        val mode = restoreMaintenanceMode.currentMode()
        if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
            throw DatabaseAccessBlockedException(
                accessType = DatabaseAccessType.WRITE,
                operation = operation,
                mode = mode
            )
        }
    }

    /**
     * Returns true if writes are currently allowed (NORMAL mode).
     */
    fun writesAllowed(): Boolean {
        return restoreMaintenanceMode.isWritesAllowed()
    }

    /**
     * Runs [block] as a counted in-flight write. The count is incremented BEFORE the mode
     * check so a writer that passed the check is always visible to [awaitNoInFlightWrites];
     * a writer whose check fails is uncounted again in `finally`. Nested calls (the context
     * already carries [InFlightWriteElement]) only re-check the mode and are not counted again.
     */
    suspend fun <T> runWrite(
        operation: DatabaseAccessOperation,
        block: suspend () -> T
    ): T {
        if (coroutineContext[InFlightWriteKey] != null) {
            checkWritesAllowed(operation)
            return block()
        }
        inFlight.update { it + 1 }
        try {
            checkWritesAllowed(operation)
            return withContext(InFlightWriteElement) { block() }
        } finally {
            inFlight.update { it - 1 }
        }
    }

    /**
     * Suspends until no counted write is in flight, or [timeoutMs] elapses.
     * Returns false on timeout (callers must fail closed). Must be called AFTER entering a
     * non-NORMAL maintenance mode so no new writer can pass the mode check.
     *
     * @throws IllegalStateException `MAINTENANCE_DRAIN_REENTRANT` if invoked from inside a
     * counted write (the drain would wait on itself).
     */
    suspend fun awaitNoInFlightWrites(timeoutMs: Long): Boolean {
        checkNotInsideCountedWrite(coroutineContext)
        return withTimeoutOrNull(timeoutMs) { inFlight.first { it == 0 } } != null
    }

    /**
     * Fails fast when [context] already carries a counted write. Maintenance entry points call
     * this BEFORE entering a maintenance mode so a re-entrant call can never leave the mode
     * entered (the later drain would wait on itself).
     *
     * @throws IllegalStateException `MAINTENANCE_DRAIN_REENTRANT` if inside a counted write.
     */
    fun checkNotInsideCountedWrite(context: CoroutineContext) {
        check(context[InFlightWriteKey] == null) { "MAINTENANCE_DRAIN_REENTRANT" }
    }

    /** Current number of counted in-flight writes (tests/diagnostics). */
    fun inFlightWriteCount(): Int = inFlight.value

    companion object {
        /** Bounded wait for in-flight writes after maintenance entry; timeout always fails closed. */
        const val WRITE_DRAIN_TIMEOUT_MS = 10_000L
    }
}
