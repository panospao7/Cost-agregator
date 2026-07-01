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
import com.yourname.expensetracker.domain.workers.WorkerReasonCodes
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
 *
 * P9-P1-08: Extended with diagnostics-only writers
 * ([recordMatchAttempted], [recordMatchNotFound],
 * [recordMatchSkippedDocumentType], [recordAutoMatchLinkFailed]) so that
 * worker-side match outcomes that were previously silent (only logged via
 * Timber or skipped with `continue`) now emit durable [ReceiptEvent]s.
 * These writers do not mutate match status; they record audit events only.
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

    // ── P9-P1-08: diagnostics-only match-outcome writers ────────────────────
    // These mirror the saveMatchSuggestion pattern (write-barrier check +
    // withTransaction + receiptEventDao.insert + "system:match_lifecycle"
    // actor) but do NOT mutate match status — they record audit events only.

    /**
     * Records that a match attempt is starting for [receiptId].
     *
     * P9-P1-08: Previously the worker invoked the matcher with no durable trace.
     */
    suspend fun recordMatchAttempted(receiptId: Long, lookbackDays: Int) {
        writeBarrier.checkWritesAllowed("ReceiptMatchLifecycleService.recordMatchAttempted")
        val now = timeProvider.now()
        database.withTransaction {
            val receipt = scannedReceiptDao.getById(receiptId) ?: return@withTransaction
            receiptEventDao.insert(ReceiptEvent(
                receiptId = receiptId, sourceType = receipt.sourceType,
                documentType = receipt.documentType,
                eventType = ReceiptLifecycleEventTypes.MATCH_ATTEMPTED, occurredAt = now,
                oldStatus = receipt.processingStatus, newStatus = null,
                actor = "system:match_lifecycle",
                message = "Match attempt started (lookbackDays=$lookbackDays)",
                metadata = null, errorDetails = null
            ))
        }
    }

    /**
     * Records that a match attempt produced no candidate for [receiptId].
     *
     * P9-P1-08: Previously the NoMatch branch did nothing.
     */
    suspend fun recordMatchNotFound(receiptId: Long) {
        writeBarrier.checkWritesAllowed("ReceiptMatchLifecycleService.recordMatchNotFound")
        val now = timeProvider.now()
        database.withTransaction {
            val receipt = scannedReceiptDao.getById(receiptId) ?: return@withTransaction
            receiptEventDao.insert(ReceiptEvent(
                receiptId = receiptId, sourceType = receipt.sourceType,
                documentType = receipt.documentType,
                eventType = ReceiptLifecycleEventTypes.MATCH_NOT_FOUND, occurredAt = now,
                oldStatus = receipt.processingStatus, newStatus = null,
                actor = "system:match_lifecycle",
                message = "No matching expense found for receipt",
                metadata = null, errorDetails = null
            ))
        }
    }

    /**
     * Records that matching was skipped for [receiptId] because its document
     * type (or processing status) is ineligible for matching.
     *
     * P9-P1-08: Previously the worker silently `continue`d past these receipts.
     */
    suspend fun recordMatchSkippedDocumentType(receiptId: Long, documentType: String?) {
        writeBarrier.checkWritesAllowed("ReceiptMatchLifecycleService.recordMatchSkippedDocumentType")
        val now = timeProvider.now()
        database.withTransaction {
            val receipt = scannedReceiptDao.getById(receiptId) ?: return@withTransaction
            val effectiveDocumentType = documentType ?: receipt.documentType
            receiptEventDao.insert(ReceiptEvent(
                receiptId = receiptId, sourceType = receipt.sourceType,
                documentType = effectiveDocumentType,
                eventType = ReceiptLifecycleEventTypes.MATCH_SKIPPED_DOCUMENT_TYPE, occurredAt = now,
                oldStatus = receipt.processingStatus, newStatus = null,
                actor = "system:match_lifecycle",
                message = "Skipped matching: documentType=$effectiveDocumentType, processingStatus=${receipt.processingStatus}",
                metadata = null, errorDetails = null
            ))
        }
    }

    /**
     * Records that an auto-match link attempt failed for [receiptId].
     *
     * P9-P1-08: Previously this path was only logged via Timber.w and produced
     * no durable trace.
     */
    suspend fun recordAutoMatchLinkFailed(receiptId: Long, expenseId: Long?, reason: String?, errorClass: String? = null) {
        val safeReason = WorkerReasonCodes.sanitizeReasonCode(reason)
        val safeErrorClass = errorClass?.take(80)?.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        writeBarrier.checkWritesAllowed("ReceiptMatchLifecycleService.recordAutoMatchLinkFailed")
        val now = timeProvider.now()
        database.withTransaction {
            val receipt = scannedReceiptDao.getById(receiptId) ?: return@withTransaction
            receiptEventDao.insert(ReceiptEvent(
                receiptId = receiptId, sourceType = receipt.sourceType,
                documentType = receipt.documentType,
                eventType = ReceiptLifecycleEventTypes.AUTO_MATCH_LINK_FAILED, occurredAt = now,
                oldStatus = receipt.processingStatus, newStatus = null,
                actor = "system:match_lifecycle",
                message = "Auto-match link failed for expense $expenseId",
                metadata = null, errorDetails = if (safeErrorClass != null) "code=$safeReason, class=$safeErrorClass" else safeReason
            ))
        }
    }

    // ── PR12L-3: durable notification-suppression diagnostics ────────────────

    /**
     * Records that a notification was suppressed for [receiptId]'s auto-match.
     *
     * PR12L-3: Previously suppression was only logged via Timber; this provides
     * a durable trace for auditing, debugging, and gap detection.
     */
    suspend fun recordNotificationSuppressed(receiptId: Long, expenseId: Long?, reasonCode: String, errorClass: String? = null) {
        val safeReason = WorkerReasonCodes.sanitizeReasonCode(reasonCode)
        val safeErrorClass = errorClass?.take(80)?.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        writeBarrier.checkWritesAllowed("ReceiptMatchLifecycleService.recordNotificationSuppressed")
        val now = timeProvider.now()
        database.withTransaction {
            val receipt = scannedReceiptDao.getById(receiptId) ?: return@withTransaction
            receiptEventDao.insert(ReceiptEvent(
                receiptId = receiptId, sourceType = receipt.sourceType,
                documentType = receipt.documentType,
                eventType = ReceiptLifecycleEventTypes.NOTIFICATION_SUPPRESSED, occurredAt = now,
                oldStatus = receipt.processingStatus, newStatus = null,
                actor = "system:match_lifecycle",
                message = "Notification suppressed for expense $expenseId: $safeReason",
                metadata = null, errorDetails = if (safeErrorClass != null) "code=$safeReason, class=$safeErrorClass" else safeReason
            ))
        }
    }
}
