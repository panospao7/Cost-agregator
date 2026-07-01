package com.yourname.expensetracker.domain.sideeffect

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records durable evidence of post-commit side-effect execution outcomes.
 *
 * PR 8 — MIT-075: Post-commit side-effect evidence.
 *
 * After a [PostCommitActionBatch] completes (success or failure), this service
 * writes a structured diagnostic event with per-action outcome metadata so that
 * downstream tooling (diagnostics UI, retry workers, CI validation) can query:
 *   - Which side effects failed?
 *   - Why did they fail (error class, bounded reason)?
 *   - What was the pipeline and target entity?
 *
 * Privacy: only bounded reason codes and exception class names are persisted.
 * Raw exception messages, stack traces, and PII are never stored.
 */
@Singleton
class PostCommitSideEffectEvidenceService @Inject constructor(
    private val runner: PostCommitActionRunner,
    private val diagnosticEventWriter: DiagnosticEventWriter
) {

    /**
     * Runs [batch] through the runner and records the outcome as a durable
     * diagnostic event.
     *
     * @return The [SideEffectBatchResult], identical to [PostCommitActionRunner.run].
     */
    suspend fun runWithEvidence(batch: PostCommitActionBatch): SideEffectBatchResult {
        val result = runner.run(batch)
        recordOutcome(batch, result)
        return result
    }

    /**
     * Runs [batch] with best-effort semantics (swallows non-cancellation
     * exceptions) AND records evidence regardless of outcome.
     */
    suspend fun runBestEffortWithEvidence(
        batch: PostCommitActionBatch,
        logMessage: String,
        targetId: Long? = null
    ) {
        if (batch.actions.isEmpty()) return

        try {
            val result = runner.run(batch)
            recordOutcome(batch, result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (targetId != null) {
                Timber.w(e, "$logMessage targetId=%d", targetId)
            } else {
                Timber.w(e, logMessage)
            }
            recordBatchExecutionFailure(batch, e)
        }
    }

    // --- internal ---

    private suspend fun recordOutcome(batch: PostCommitActionBatch, result: SideEffectBatchResult) {
        val pipeline = batch.actions.firstOrNull()?.pipeline ?: AppPipeline.WORKER
        val hasFailures = result.failedRetryable > 0 || result.failedFinal > 0
        val outcome = if (hasFailures) EventOutcome.FAILED_RETRYABLE else EventOutcome.SIDE_EFFECT_COMPLETED
        val metadata = buildBatchMetadata(batch, result)
        val entityType = batch.actions.firstOrNull()?.targetEntityType

        diagnosticEventWriter.emit(
            DiagnosticEvent(
                pipeline = pipeline,
                stage = "POST_COMMIT_SIDE_EFFECTS",
                outcome = outcome,
                entityType = entityType,
                entityId = targetId(batch),
                correlationId = batch.correlationId,
                metadata = metadata,
                isTerminal = true
            )
        )
    }

    private suspend fun recordBatchExecutionFailure(batch: PostCommitActionBatch, error: Exception) {
        val pipeline = batch.actions.firstOrNull()?.pipeline ?: AppPipeline.WORKER
        val entityType = batch.actions.firstOrNull()?.targetEntityType
        val metadata = SafeEventMetadata.builder()
            .put("correlationId", batch.correlationId)
            .put("actionCount", batch.actions.size)
            .put("errorClass", error::class.simpleName ?: "Unknown")
            .build()

        diagnosticEventWriter.emit(
            DiagnosticEvent(
                pipeline = pipeline,
                stage = "POST_COMMIT_SIDE_EFFECTS",
                outcome = EventOutcome.FAILED_FINAL,
                entityType = entityType,
                entityId = targetId(batch),
                correlationId = batch.correlationId,
                metadata = metadata,
                isTerminal = true
            )
        )
    }

    private fun buildBatchMetadata(batch: PostCommitActionBatch, result: SideEffectBatchResult): SafeEventMetadata {
        val builder = SafeEventMetadata.builder()
        builder.put("actionCount", batch.actions.size)
        builder.put("completed", result.completed)
        builder.put("skipped", result.skipped)
        builder.put("failedRetryable", result.failedRetryable)
        builder.put("failedFinal", result.failedFinal)

        if (result.outcomes.isNotEmpty()) {
            val summary = result.outcomes.joinToString(", ") { ar ->
                when (ar.outcome) {
                    is SideEffectOutcome.Completed -> "${ar.name}:OK"
                    is SideEffectOutcome.Skipped -> "${ar.name}:SKIPPED"
                    is SideEffectOutcome.FailedRetryable -> "${ar.name}:FAILED_RETRYABLE:${(ar.outcome as SideEffectOutcome.FailedRetryable).errorClass ?: "?"}"
                    is SideEffectOutcome.FailedFinal -> "${ar.name}:FAILED_FINAL:${(ar.outcome as SideEffectOutcome.FailedFinal).errorClass ?: "?"}"
                    is SideEffectOutcome.Cancelled -> "${ar.name}:CANCELLED"
                }
            }
            builder.put("actionSummary", summary)
        }

        return builder.build()
    }

    private fun targetId(batch: PostCommitActionBatch): Long? =
        batch.actions.firstOrNull()?.targetEntityId
}
