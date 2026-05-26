package com.yourname.expensetracker.domain.receipt.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lifecycle-aware service for receipt matching operations.
 *
 * Replaces direct [ReceiptRepository] match mutation methods.
 * Every operation checks [DatabaseWriteBarrier], runs inside
 * a transaction, and writes a durable lifecycle event.
 *
 * PR 1: Created to migrate ViewModel/Worker callers off deprecated
 * ReceiptRepository match mutation APIs.
 */
@Singleton
class ReceiptMatchLifecycleService @Inject constructor(
    private val database: AppDatabase,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val receiptEventDao: ReceiptEventDao,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider
) {

    suspend fun saveMatchSuggestion(
        receiptId: Long,
        suggestedExpenseId: Long,
        confidence: Double
    ) {
        writeBarrier.checkWritesAllowed("ReceiptMatchLifecycleService.saveMatchSuggestion")
        val now = timeProvider.now()
        database.withTransaction {
            val receipt = scannedReceiptDao.getById(receiptId) ?: return@withTransaction
            scannedReceiptDao.update(receipt.copy(
                suggestedExpenseId = suggestedExpenseId,
                matchStatus = MatchStatus.SUGGESTED,
                matchConfidence = confidence.toFloat(),
                updatedAt = now
            ))
            receiptEventDao.insert(ReceiptEvent(
                receiptId = receiptId, sourceType = receipt.sourceType,
                documentType = receipt.documentType,
                eventType = "MATCH_SUGGESTED", occurredAt = now,
                oldStatus = receipt.processingStatus, newStatus = null,
                actor = "system:match_lifecycle",
                message = "Suggested match to expense $suggestedExpenseId (confidence=$confidence)",
                metadata = null, errorDetails = null
            ))
        }
    }

    suspend fun approveMatchSuggestion(receiptId: Long) {
        writeBarrier.checkWritesAllowed("ReceiptMatchLifecycleService.approveMatchSuggestion")
        val now = timeProvider.now()
        database.withTransaction {
            val receipt = scannedReceiptDao.getById(receiptId) ?: return@withTransaction
            val suggestedId = receipt.suggestedExpenseId ?: return@withTransaction
            scannedReceiptDao.update(receipt.copy(
                expenseId = suggestedId,
                suggestedExpenseId = null,
                matchConfidence = null,
                matchStatus = MatchStatus.MANUALLY_MATCHED,
                updatedAt = now
            ))
            receiptEventDao.insert(ReceiptEvent(
                receiptId = receiptId, sourceType = receipt.sourceType,
                documentType = receipt.documentType,
                eventType = "MATCH_APPROVED", occurredAt = now,
                oldStatus = receipt.processingStatus, newStatus = null,
                actor = "system:match_lifecycle",
                message = "Match suggestion approved for expense $suggestedId",
                metadata = null, errorDetails = null
            ))
        }
    }

    suspend fun rejectAllSuggestions(receiptId: Long) {
        writeBarrier.checkWritesAllowed("ReceiptMatchLifecycleService.rejectAllSuggestions")
        val now = timeProvider.now()
        database.withTransaction {
            val receipt = scannedReceiptDao.getById(receiptId) ?: return@withTransaction
            scannedReceiptDao.update(receipt.copy(
                matchStatus = MatchStatus.REJECTED,
                suggestedExpenseId = null,
                updatedAt = now
            ))
            receiptEventDao.insert(ReceiptEvent(
                receiptId = receiptId, sourceType = receipt.sourceType,
                documentType = receipt.documentType,
                eventType = "MATCH_REJECTED", occurredAt = now,
                oldStatus = receipt.processingStatus, newStatus = null,
                actor = "system:match_lifecycle",
                message = "All match suggestions rejected",
                metadata = null, errorDetails = null
            ))
        }
    }

    suspend fun clearMatchForReceipt(receiptId: Long) {
        writeBarrier.checkWritesAllowed("ReceiptMatchLifecycleService.clearMatchForReceipt")
        val now = timeProvider.now()
        database.withTransaction {
            val receipt = scannedReceiptDao.getById(receiptId) ?: return@withTransaction
            scannedReceiptDao.update(receipt.copy(
                expenseId = null,
                matchStatus = MatchStatus.UNMATCHED,
                suggestedExpenseId = null,
                matchConfidence = null,
                updatedAt = now
            ))
            receiptEventDao.insert(ReceiptEvent(
                receiptId = receiptId, sourceType = receipt.sourceType,
                documentType = receipt.documentType,
                eventType = "MATCH_CLEARED", occurredAt = now,
                oldStatus = receipt.processingStatus, newStatus = null,
                actor = "system:match_lifecycle",
                message = "Match cleared for receipt",
                metadata = null, errorDetails = null
            ))
        }
    }
}
