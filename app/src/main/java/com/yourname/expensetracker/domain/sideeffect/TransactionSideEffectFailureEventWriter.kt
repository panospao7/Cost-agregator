package com.yourname.expensetracker.domain.sideeffect

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleEvent
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleEventWriter
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionSideEffectFailureEventWriter @Inject constructor(
    private val transactionEventWriter: TransactionLifecycleEventWriter,
    private val timeProvider: TimeProvider
) : SideEffectEventWriter {

    override suspend fun started(action: PostCommitAction) = Unit
    override suspend fun completed(action: PostCommitAction) = Unit
    override suspend fun skipped(action: PostCommitAction, reason: SideEffectSkipReason) = Unit
    override suspend fun cancelled(action: PostCommitAction, reason: String?) = Unit

    override suspend fun failed(
        action: PostCommitAction,
        retryable: Boolean,
        reason: String,
        error: Throwable?
    ) {
        if (!shouldMirrorToTransactionEvents(action)) return

        // Capture "now" once so the mirror event uses the same deterministic
        // timestamp for occurredAt (G-TIME-01). startedAtMs defaults to occurredAt,
        // so a single explicit read keeps the event internally consistent.
        val now = timeProvider.now()
        transactionEventWriter.write(
            TransactionContext(
                correlationId = action.correlationId ?: java.util.UUID.randomUUID().toString(),
                occurredAt = now,
                source = "post_commit_action_runner"
            ),
            TransactionLifecycleEvent(
                expenseId = action.targetEntityId.takeIf {
                    action.targetEntityType.equals("Expense", ignoreCase = true)
                },
                eventType = LifecycleEventType.SIDE_EFFECT_FAILED.name,
                source = action.source,
                actor = "system:post_commit_action_runner",
                correlationId = action.correlationId,
                metadata = SafeEventMetadata.builder()
                    .put("actionName", action.name)
                    .put("category", action.category.name)
                    .put("triggerType", action.triggerType.name)
                    .put("targetEntityType", action.targetEntityType)
                    .put("targetEntityId", action.targetEntityId?.toString())
                    .put("retryable", retryable.toString())
                    .put("reasonClass", error?.javaClass?.name ?: "returned_outcome")
                    .build(),
                reason = reason.take(200)
            )
        )
    }

    private fun shouldMirrorToTransactionEvents(action: PostCommitAction): Boolean {
        return action.pipeline == AppPipeline.TRANSACTION ||
            action.targetEntityType.equals("Expense", ignoreCase = true)
    }
}
