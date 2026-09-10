package com.yourname.expensetracker.domain.transaction

/**
 * Internal token created only by [DomainTransactionRunner].
 * Prevents manual construction of [TransactionContext] outside the transaction infrastructure.
 */
class TransactionToken internal constructor()

/**
 * Context metadata passed to every operation inside a [DomainTransactionRunner] transaction.
 *
 * All fields are bounded and safe for diagnostic/audit use — no raw PII, exception messages,
 * or stack traces.
 *
 * PR 3 — MIT-031: Transaction-scoped mutation model.
 */
class TransactionContext internal constructor(
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
    val metadata: Map<String, String> = emptyMap(),

    /** Internal guard token — only [DomainTransactionRunner] can create contexts. */
    internal val token: TransactionToken = TransactionToken()
) {
    init {
        require(occurredAt > 0) { "occurredAt must be a positive epoch-millis value" }
        require(startedAtMs > 0) { "startedAtMs must be a positive epoch-millis value" }
        require(transactionId.isNotBlank()) { "transactionId must not be blank" }
    }

    /**
     * Creates a copy of this context with optional field overrides.
     * Preserves the original [token] to maintain provenance.
     */
    fun copy(
        correlationId: String = this.correlationId,
        causationId: String? = this.causationId,
        operationId: String = this.operationId,
        source: String = this.source,
        actor: String? = this.actor,
        occurredAt: Long = this.occurredAt,
        startedAtMs: Long = this.startedAtMs,
        transactionId: String = this.transactionId,
        metadata: Map<String, String> = this.metadata
    ): TransactionContext = TransactionContext(
        correlationId, causationId, operationId, source, actor,
        occurredAt, startedAtMs, transactionId, metadata,
        token = this.token
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransactionContext) return false
        return correlationId == other.correlationId &&
            causationId == other.causationId &&
            operationId == other.operationId &&
            source == other.source &&
            actor == other.actor &&
            occurredAt == other.occurredAt &&
            startedAtMs == other.startedAtMs &&
            transactionId == other.transactionId &&
            metadata == other.metadata
    }

    override fun hashCode(): Int {
        var result = correlationId.hashCode()
        result = 31 * result + (causationId?.hashCode() ?: 0)
        result = 31 * result + operationId.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + (actor?.hashCode() ?: 0)
        result = 31 * result + occurredAt.hashCode()
        result = 31 * result + startedAtMs.hashCode()
        result = 31 * result + transactionId.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }

    override fun toString(): String {
        return "TransactionContext(correlationId=$correlationId, causationId=$causationId, " +
            "operationId=$operationId, source=$source, actor=$actor, " +
            "occurredAt=$occurredAt, startedAtMs=$startedAtMs, " +
            "transactionId=$transactionId, metadata=$metadata)"
    }
}
