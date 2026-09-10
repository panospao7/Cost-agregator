package com.yourname.expensetracker.domain.transaction.lifecycle

import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.event.TransactionalEventWriter
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.TimeProvider
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class TransactionLifecycleEvent(
    val expenseId: Long?,
    val eventType: String,
    val source: String,
    val actor: String? = null,
    val correlationId: String? = null,
    val dedupeKey: String? = null,
    val duplicateExpenseId: Long? = null,
    val metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    val reason: String? = null
)

interface TransactionLifecycleEventWriter : TransactionalEventWriter {
    suspend fun write(context: TransactionContext, event: TransactionLifecycleEvent)

    @Deprecated(
        message = "Use write(context, event) inside DomainTransactionRunner.runInTransaction",
        replaceWith = ReplaceWith(
            "write(TransactionContext(correlationId = java.util.UUID.randomUUID().toString(), " +
                "occurredAt = System.currentTimeMillis()), event)"
        ),
        level = DeprecationLevel.ERROR
    )
    suspend fun write(event: TransactionLifecycleEvent)
}

@Singleton
class RoomTransactionLifecycleEventWriter @Inject constructor(
    private val dao: TransactionEventDao,
    private val sanitizer: EventMetadataSanitizer,
    private val timeProvider: TimeProvider
) : TransactionLifecycleEventWriter {

    override suspend fun write(context: TransactionContext, event: TransactionLifecycleEvent) {
        dao.insert(
            TransactionEvent(
                expenseId = event.expenseId,
                eventType = event.eventType,
                source = event.source,
                actor = event.actor ?: context.actor,
                occurredAt = context.occurredAt,
                dedupeKey = event.dedupeKey,
                duplicateExpenseId = event.duplicateExpenseId,
                beforeSnapshot = null,
                afterSnapshot = null,
                metadata = buildMetaJson(event, context),
                reason = event.reason,
                correlationId = event.correlationId ?: context.correlationId
            )
        )
    }

    @Suppress("DEPRECATION_ERROR")
    @Deprecated(
        message = "Use write(context, event) inside DomainTransactionRunner.runInTransaction",
        replaceWith = ReplaceWith(
            "write(TransactionContext(correlationId = java.util.UUID.randomUUID().toString(), " +
                "occurredAt = System.currentTimeMillis()), event)"
        ),
        level = DeprecationLevel.ERROR
    )
    override suspend fun write(event: TransactionLifecycleEvent) {
        write(
            TransactionContext(
                correlationId = java.util.UUID.randomUUID().toString(),
                occurredAt = timeProvider.now(),
                source = "legacy:TransactionLifecycleEventWriter"
            ),
            event
        )
    }

    private fun buildMetaJson(event: TransactionLifecycleEvent, context: TransactionContext): String? {
        val base = if (event.metadata.isEmpty()) emptyMap() else
            JSONObject(event.metadata.toJson()).let { jo ->
                (0 until jo.length()).associate { jo.names()!!.getString(it) to jo.get(jo.names()!!.getString(it)) }
            }
        val merged = base.toMutableMap()
        event.correlationId?.let { merged["correlationId"] = it }
        merged["txCorrelationId"] = context.correlationId
        merged["txOperationId"] = context.operationId
        merged["txTransactionId"] = context.transactionId
        context.causationId?.let { merged["txCausationId"] = it }
        return if (merged.isEmpty()) null else JSONObject(merged).toString()
    }
}
