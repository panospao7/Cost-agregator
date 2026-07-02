package com.yourname.expensetracker.domain.transaction

/**
 * Context metadata passed to every operation inside a [DomainTransactionRunner] transaction.
 *
 * All fields are bounded and safe for diagnostic/audit use — no raw PII, exception messages,
 * or stack traces.
 *
 * PR 3 — MIT-031: Transaction-scoped mutation model.
 */
data class TransactionContext(
    /** Unique correlation ID for this logical operation (UUID or deterministic key). */
    val correlationId: String,

    /** Optional upstream correlation ID for causality chain tracking. */
    val causationId: String? = null,

    /** Stable operation type identifier (e.g. "receipt.saveWithReview", "bank_statement.import"). */
    val operationId: String = "unknown",

    /** Human-readable source (e.g. "ReceiptLifecycleCoordinator.approveReceipt"). */
    val source: String = "unknown",

    /** Who/what triggered this operation (e.g. "User", "system:bank_statement_processor"). */
    val actor: String? = null,

    /** Epoch-millis timestamp from [com.yourname.expensetracker.domain.util.TimeProvider]. */
    val occurredAt: Long,

    /** Epoch-millis timestamp when the transaction started (same as occurredAt by default). */
    val startedAtMs: Long = occurredAt,

    /** Unique transaction instance ID for idempotency/deduplication. */
    val transactionId: String = java.util.UUID.randomUUID().toString(),

    /** Bounded key-value metadata for audit/diagnostic context. Must be sanitized. */
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(occurredAt > 0) { "occurredAt must be a positive epoch-millis value" }
        require(startedAtMs > 0) { "startedAtMs must be a positive epoch-millis value" }
        require(transactionId.isNotBlank()) { "transactionId must not be blank" }
    }
}
