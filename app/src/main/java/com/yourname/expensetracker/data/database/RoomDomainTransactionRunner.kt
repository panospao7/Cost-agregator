package com.yourname.expensetracker.data.database

import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.TimeProvider
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-based implementation of [DomainTransactionRunner].
 *
 * Wraps [AppDatabase.withTransaction], provides a typed [TransactionContext] to the
 * transactional block, and guarantees CancellationException propagation.
 *
 * PR 3 — MIT-031: Transaction-scoped mutation model.
 */
@Singleton
class RoomDomainTransactionRunner @Inject constructor(
    private val database: AppDatabase,
    private val timeProvider: TimeProvider
) : DomainTransactionRunner {

    override suspend fun <T> runInTransaction(
        correlationId: String,
        causationId: String?,
        source: String,
        metadata: Map<String, String>,
        block: suspend (TransactionContext) -> T
    ): T {
        val context = TransactionContext(
            correlationId = correlationId,
            causationId = causationId,
            source = source,
            occurredAt = timeProvider.now(),
            metadata = metadata
        )
        return database.withTransaction {
            block(context)
        }
    }
}
