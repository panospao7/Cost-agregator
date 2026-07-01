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

    /** Human-readable source (e.g. "ReceiptLifecycleCoordinator.approveReceipt"). */
    val source: String = "unknown",

    /** Epoch-millis timestamp from [com.yourname.expensetracker.domain.util.TimeProvider]. */
    val occurredAt: Long,

    /** Bounded key-value metadata for audit/diagnostic context. Must be sanitized. */
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(occurredAt > 0) { "occurredAt must be a positive epoch-millis value" }
    }
}
