package com.yourname.expensetracker.domain.sideeffect

import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PostCommitActionRunnerImpl @Inject constructor(
    private val eventWriter: SideEffectEventWriter
) : PostCommitActionRunner {

    override suspend fun run(batch: PostCommitActionBatch): SideEffectBatchResult {
        val outcomes = mutableListOf<SideEffectActionResult>()
        var completed = 0
        var skipped = 0
        var failedRetryable = 0
        var failedFinal = 0
        var cancelled = 0

        for (action in batch.actions) {
            val context = SideEffectContextImpl(
                correlationId = batch.correlationId,
                action = action
            )

            try {
                eventWriter.started(action)
            } catch (e: Exception) {
                Timber.w(e, "Failed to emit started event for side effect '${action.name}'")
            }

            val outcome = try {
                action.execute(context)
            } catch (e: CancellationException) {
                try {
                    eventWriter.cancelled(action, "cancelled")
                } catch (emitError: Exception) {
                    Timber.w(emitError, "Failed to emit cancelled event for side effect '${action.name}'")
                }
                cancelled++
                outcomes.add(
                    SideEffectActionResult(
                        idempotencyKey = action.idempotencyKey,
                        name = action.name,
                        outcome = SideEffectOutcome.Cancelled("cancelled")
                    )
                )
                // Rethrow cancellation
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Side effect '${action.name}' threw unexpected exception")
                val failureOutcome = SideEffectOutcome.FailedRetryable(
                    reason = "side_effect_unexpected_exception",
                    errorClass = e::class.simpleName
                )
                try {
                    eventWriter.failed(action, retryable = true, reason = "side_effect_unexpected_exception", error = e)
                } catch (emitError: Exception) {
                    Timber.w(emitError, "Failed to emit failed event for side effect '${action.name}'")
                }
                failedRetryable++
                outcomes.add(
                    SideEffectActionResult(
                        idempotencyKey = action.idempotencyKey,
                        name = action.name,
                        outcome = failureOutcome
                    )
                )
                // Continue with next action - one failure must not stop the batch
                continue
            }

            // Handle the returned outcome
            when (outcome) {
                is SideEffectOutcome.Completed -> {
                    try {
                        eventWriter.completed(action)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to emit completed event for side effect '${action.name}'")
                    }
                    completed++
                }
                is SideEffectOutcome.Skipped -> {
                    try {
                        eventWriter.skipped(action, outcome.reason)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to emit skipped event for side effect '${action.name}'")
                    }
                    skipped++
                }
                is SideEffectOutcome.FailedRetryable -> {
                    try {
                        eventWriter.failed(action, retryable = true, reason = outcome.reason, error = null)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to emit failed event for side effect '${action.name}'")
                    }
                    failedRetryable++
                }
                is SideEffectOutcome.FailedFinal -> {
                    try {
                        eventWriter.failed(action, retryable = false, reason = outcome.reason, error = null)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to emit failed event for side effect '${action.name}'")
                    }
                    failedFinal++
                }
                is SideEffectOutcome.Cancelled -> {
                    try {
                        eventWriter.cancelled(action, outcome.reason)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to emit cancelled event for side effect '${action.name}'")
                    }
                    cancelled++
                }
            }

            outcomes.add(
                SideEffectActionResult(
                    idempotencyKey = action.idempotencyKey,
                    name = action.name,
                    outcome = outcome
                )
            )
        }

        return SideEffectBatchResult(
            correlationId = batch.correlationId,
            completed = completed,
            skipped = skipped,
            failedRetryable = failedRetryable,
            failedFinal = failedFinal,
            cancelled = cancelled,
            outcomes = outcomes
        )
    }

    private class SideEffectContextImpl(
        override val correlationId: String,
        override val action: PostCommitAction
    ) : SideEffectExecutionContext {

        private val accumulatedMetadata = mutableMapOf<String, Any?>()

        override suspend fun checkpoint(label: String) {
            Timber.d("SideEffect checkpoint [%s]: %s - %s", correlationId, action.name, label)
        }

        override suspend fun recordMetadata(metadata: SafeEventMetadata) {
            if (!metadata.isEmpty()) {
                accumulatedMetadata.putAll(mapOf("recordedMetadata" to metadata.toJson()))
                Timber.d("SideEffect metadata recorded [%s]: %s", correlationId, action.name)
            }
        }
    }
}
