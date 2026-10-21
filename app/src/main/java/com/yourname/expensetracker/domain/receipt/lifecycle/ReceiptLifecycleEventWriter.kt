package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.event.TransactionalEventWriter
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.TimeProvider
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ReceiptLifecycleEvent(
    val receiptId: Long?,
    val sourceType: String,
    val documentType: String,
    val eventType: String,
    val oldStatus: String? = null,
    val newStatus: String? = null,
    val actor: String? = null,
    val message: String? = null,
    val metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    val errorDetails: String? = null
)

interface ReceiptLifecycleEventWriter : TransactionalEventWriter {
    suspend fun write(context: TransactionContext, event: ReceiptLifecycleEvent)

    @Deprecated(
        message = "Use write(context, event) inside DomainTransactionRunner.runInTransaction",
        replaceWith = ReplaceWith(
            "write(TransactionContext(correlationId = java.util.UUID.randomUUID().toString(), " +
                "occurredAt = System.currentTimeMillis()), event)"
        ),
        level = DeprecationLevel.ERROR
    )
    suspend fun write(event: ReceiptLifecycleEvent)
}

@Singleton
class RoomReceiptLifecycleEventWriter @Inject constructor(
    private val dao: ReceiptEventDao,
    private val sanitizer: EventMetadataSanitizer,
    private val timeProvider: TimeProvider,
    private val writeBarrier: DatabaseWriteBarrier
) : ReceiptLifecycleEventWriter {

    override suspend fun write(context: TransactionContext, event: ReceiptLifecycleEvent) {
        writeBarrier.checkWritesAllowed("ReceiptLifecycleEventWriter.write")
        dao.insert(
            ReceiptEvent(
                receiptId = event.receiptId,
                sourceType = event.sourceType,
                documentType = event.documentType,
                eventType = event.eventType,
                occurredAt = context.occurredAt,
                oldStatus = event.oldStatus,
                newStatus = event.newStatus,
                actor = event.actor ?: context.actor,
                message = event.message,
                metadata = buildMetadata(event, context),
                // Free-text error details may carry raw receipt/OCR/path text: never persisted.
                // Only code-shaped tokens (reason codes, exception class names, "code=X, class=Y")
                // are kept; anything else is dropped to null.
                errorDetails = event.errorDetails?.takeIf { it.length <= MAX_ERROR_DETAILS_LENGTH && SAFE_ERROR_DETAILS.matches(it) }
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
    override suspend fun write(event: ReceiptLifecycleEvent) {
        write(
            TransactionContext(
                correlationId = java.util.UUID.randomUUID().toString(),
                occurredAt = timeProvider.now(),
                source = "legacy:ReceiptLifecycleEventWriter"
            ),
            event
        )
    }

    private companion object {
        const val MAX_ERROR_DETAILS_LENGTH = 160

        /**
         * Reason-code shape only: UPPER_SNAKE code with optional "code=" prefix, optionally followed by
         * ", class=<qualified ClassName>". Free text, file names, numbers and IBAN-like values never match.
         * A single uppercase word still passes (accepted; an explicit allowlist would be the next step).
         */
        val SAFE_ERROR_DETAILS =
            Regex("""(?:code=)?[A-Z]+(?:_[A-Z0-9]+)*(?:, class=(?:[a-z][a-z0-9_]*\.)*[A-Z][A-Za-z0-9_$]*)?""")
    }

    private fun buildMetadata(event: ReceiptLifecycleEvent, context: TransactionContext): String? {
        val base = if (event.metadata.isEmpty()) emptyMap() else
            JSONObject(event.metadata.toJson()).let { jo ->
                (0 until jo.length()).associate {
                    jo.names()!!.getString(it) to jo.get(jo.names()!!.getString(it))
                }
            }
        val merged = base.toMutableMap()
        merged["txCorrelationId"] = context.correlationId
        merged["txOperationId"] = context.operationId
        merged["txTransactionId"] = context.transactionId
        context.causationId?.let { merged["txCausationId"] = it }
        return if (merged.isEmpty()) null else JSONObject(merged).toString()
    }
}
