package com.yourname.expensetracker.domain.diagnostics

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class SideEffectContext(
    val pipeline: AppPipeline,
    val correlationId: String,
    val causationId: String? = null,
    val entityType: String,
    val entityId: Long?,
    val source: String? = null,
    val actor: String = "system"
)

@Singleton
class SideEffectDiagnosticRecorder @Inject constructor(
    private val writer: DiagnosticEventWriter
) {
    /**
     * Runs [block] wrapped with SIDE_EFFECT_STARTED / SIDE_EFFECT_COMPLETED / SIDE_EFFECT_FAILED.
     * Cancellation is finalized NonCancellable before rethrowing.
     * Non-cancellation exceptions are swallowed (best-effort side effects).
     * Returns null on non-cancellation failure.
     */
    suspend fun <T> runSideEffect(
        context: SideEffectContext,
        name: String,
        metadata: SafeEventMetadata = SafeEventMetadata.empty(),
        block: suspend () -> T
    ): T? {
        emit(context, name, EventOutcome.SIDE_EFFECT_STARTED, EventSeverity.DEBUG, metadata, isTerminal = false)
        return try {
            val result = block()
            emit(context, name, EventOutcome.SIDE_EFFECT_COMPLETED, EventSeverity.DEBUG, metadata, isTerminal = true)
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                emit(context, name, EventOutcome.CANCELLED, EventSeverity.WARNING,
                    metadata, reasonCode = DiagnosticReasonCode.CANCELLED_BY_SYSTEM, isTerminal = true)
            }
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Side effect '$name' failed for ${context.entityType}:${context.entityId}")
            withContext(NonCancellable) {
                emit(context, name, EventOutcome.SIDE_EFFECT_FAILED, EventSeverity.WARNING,
                    metadata, reasonCode = DiagnosticReasonCode.SIDE_EFFECT_EXCEPTION,
                    exception = e, isTerminal = true)  // DDL-81-15: FAILED is terminal
            }
            null
        }
    }

    private suspend fun emit(
        context: SideEffectContext,
        name: String,
        outcome: EventOutcome,
        severity: EventSeverity,
        callerMetadata: SafeEventMetadata,
        reasonCode: DiagnosticReasonCode? = null,
        exception: Throwable? = null,
        isTerminal: Boolean = false
    ) {
        try {
            // DDL-81-14: merge caller metadata with recorder metadata
            val combined = callerMetadata.merge(
                SafeEventMetadata.builder()
                    .put("sideEffect", name)
                    .put("source", context.source ?: "")
                    .build()
            )
            writer.emit(DiagnosticEvent(
                pipeline = context.pipeline,
                stage = "side_effect",
                outcome = outcome,
                severity = severity,
                reasonCode = reasonCode,
                entityType = context.entityType,
                entityId = context.entityId,
                correlationId = context.correlationId,
                causationId = context.causationId,
                metadata = combined,
                exception = exception,
                isTerminal = isTerminal
            ))
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
    }
}
