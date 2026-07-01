package com.yourname.expensetracker.domain.transaction

/**
 * Shared transaction runner for domain operations that require atomic state+event writes.
 *
 * Replaces ad-hoc `database.withTransaction { }` calls with a typed contract that
 * provides a [TransactionContext] to all operations within the DB transaction boundary.
 * Rethrows [kotlinx.coroutines.CancellationException] unaltered.
 *
 * Usage:
 * ```kotlin
 * val result = transactionRunner.runInTransaction(
 *     correlationId = UUID.randomUUID().toString(),
 *     source = "ReceiptLifecycleCoordinator.approveReceipt"
 * ) { ctx ->
 *     receiptDao.updateStatus(receiptId, "APPROVED")
 *     eventWriter.write(ReceiptEvent(occurredAt = ctx.occurredAt, ...))
 *     MutationResult(Approved, PostCommitActionBatch.empty())
 * }
 * ```
 *
 * PR 3 — MIT-031: Transaction-scoped mutation model.
 */
interface DomainTransactionRunner {

    /**
     * Executes [block] inside a single Room database transaction.
     *
     * If [block] throws, the entire transaction rolls back. If the throwable is a
     * [kotlinx.coroutines.CancellationException], it is rethrown unaltered.
     *
     * @param correlationId Unique identifier for this operation; a new one MUST be
     *   generated per logical operation (UUID or deterministic key).
     * @param causationId Optional upstream correlation ID for causality tracking.
     * @param source Human-readable source identifier for diagnostics
     *   (e.g. "ReceiptLifecycleCoordinator.approveReceipt").
     * @param metadata Optional key-value pairs for audit context (sanitized only).
     * @param block The transactional work. Receives a [TransactionContext] with
     *   correlation metadata. Mutation results with deferred side-effect batches
     *   should be returned from [block] — they execute AFTER commit.
     */
    suspend fun <T> runInTransaction(
        correlationId: String,
        causationId: String? = null,
        source: String = "unknown",
        metadata: Map<String, String> = emptyMap(),
        block: suspend (TransactionContext) -> T
    ): T
}
