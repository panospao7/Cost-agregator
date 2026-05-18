package com.yourname.expensetracker.domain.transaction.lifecycle

import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
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

interface TransactionLifecycleEventWriter {
    suspend fun write(event: TransactionLifecycleEvent)
}

@Singleton
class RoomTransactionLifecycleEventWriter @Inject constructor(
    private val dao: TransactionEventDao,
    private val sanitizer: EventMetadataSanitizer,
    private val timeProvider: TimeProvider
) : TransactionLifecycleEventWriter {

    override suspend fun write(event: TransactionLifecycleEvent) {
        val metaJson = buildMetaJson(event)
        dao.insert(
            TransactionEvent(
                expenseId = event.expenseId,
                eventType = event.eventType,
                source = event.source,
                actor = event.actor,
                occurredAt = timeProvider.now(),
                dedupeKey = event.dedupeKey,
                duplicateExpenseId = event.duplicateExpenseId,
                beforeSnapshot = null,
                afterSnapshot = null,
                metadata = metaJson,
                reason = event.reason
            )
        )
    }

    private fun buildMetaJson(event: TransactionLifecycleEvent): String? {
        val base = if (event.metadata.isEmpty()) emptyMap() else
            org.json.JSONObject(event.metadata.toJson()).let { jo ->
                (0 until jo.length()).associate { jo.names()!!.getString(it) to jo.get(jo.names()!!.getString(it)) }
            }
        val merged = base.toMutableMap()
        event.correlationId?.let { merged["correlationId"] = it }
        return if (merged.isEmpty()) null else JSONObject(merged).toString()
    }
}
