package com.yourname.expensetracker.domain.consistency

import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.util.CancellationSafe
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

/**
 * Detects data inconsistencies that may have accumulated before PR 4–8 atomicity
 * fixes were applied.
 *
 * PR 9 — MIT-031, MIT-041, MIT-043: Legacy inconsistency repair.
 *
 * This checker is a diagnostic tool — it does NOT automatically repair data.
 * Results are emitted as structured [DiagnosticEvent] entries so they can be:
 * - Viewed in the diagnostics dashboard
 * - Used to trigger manual repair via existing coordinator APIs
 * - Monitored over time to confirm no new inconsistencies appear
 *
 * ## Detected inconsistency types:
 *
 * | Type | Description | Likely cause (pre-PR) |
 * |------|-------------|----------------------|
 * | RECEIPT_WITHOUT_EVENT | ScannedReceipt exists but RECEIPT_SAVED event is missing | PR 4 gap: receipt saved without event |
 * | PENDING_REVIEW_WITHOUT_RECEIPT | PendingReview references a receipt that doesn't exist | Race condition or cascading delete |
 * | OCCURRENCE_WITHOUT_EVENT | RecurringOccurrence has status change but no lifecycle event | PR 6 gap: status update without event |
 *
 * ## Performance note
 *
 * This checker scans ALL rows and may be expensive on large databases.
 * Call it from a background worker or diagnostic entry point, never from
 * the UI thread or hot paths.
 */
@Singleton
class LegacyDataConsistencyChecker @Inject constructor(
    private val diagnosticEventWriter: DiagnosticEventWriter,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val receiptEventDao: ReceiptEventDao,
    private val pendingReviewDao: PendingReviewDao,
    private val occurrenceDao: RecurringOccurrenceDao,
    private val lifecycleEventDao: RecurringLifecycleEventDao
) {

    data class ConsistencyReport(
        val receiptsWithoutEvent: Int = 0,
        val pendingReviewsWithoutReceipt: Int = 0,
        val occurrencesWithoutEvent: Int = 0,
        val totalItemsChecked: Int = 0,
        val elapsedMs: Long = 0L
    )

    /**
     * Run all consistency checks and emit a summary diagnostic event.
     *
     * Individual finding details are emitted as separate diagnostic events
     * (one per inconsistency type) to avoid unbounded metadata.
     */
    suspend fun runConsistencyCheck(): ConsistencyReport {
        var computedReport: ConsistencyReport? = null
        val elapsed = measureTimeMillis {
            var totalChecked = 0

            // --- Check 1: Receipts without lifecycle events ---
            val receiptsWithoutEvent = try {
                val receipts = scannedReceiptDao.getAll()
                totalChecked += receipts.size
                checkReceiptsWithoutEvents(receipts)
            } catch (e: Exception) {
                CancellationSafe.rethrowIfCancellation(e)
                Timber.w(e, "Receipt consistency check failed")
                0
            }

            // --- Check 2: PendingReviews referencing deleted receipts ---
            val reviewsWithoutReceipt = try {
                val reviews = checkPendingReviewsWithoutReceipts()
                totalChecked += reviews
                reviews
            } catch (e: Exception) {
                CancellationSafe.rethrowIfCancellation(e)
                Timber.w(e, "PendingReview consistency check failed")
                0
            }

            // --- Check 3: Occurrences without lifecycle events ---
            val occurrencesWithoutEvent = try {
                val count = checkOccurrencesWithoutEvents()
                totalChecked += count
                count
            } catch (e: Exception) {
                CancellationSafe.rethrowIfCancellation(e)
                Timber.w(e, "Occurrence consistency check failed")
                0
            }

            val report = ConsistencyReport(
                receiptsWithoutEvent = receiptsWithoutEvent,
                pendingReviewsWithoutReceipt = reviewsWithoutReceipt,
                occurrencesWithoutEvent = occurrencesWithoutEvent,
                totalItemsChecked = totalChecked,
                elapsedMs = 0L // filled below
            )

            emitSummaryDiagnostic(report)
            computedReport = report
        }

        return requireNotNull(computedReport) {
            "ConsistencyReport was not computed"
        }.copy(elapsedMs = elapsed)
    }

    // --- internal check methods ---

    private suspend fun checkReceiptsWithoutEvents(receipts: List<ScannedReceipt>): Int {
        var count = 0
        for (receipt in receipts) {
            try {
                val events = receiptEventDao.getEventsForReceipt(receipt.id)
                if (events.isEmpty()) {
                    count++
                    emitOrphanDiagnostic(
                        entityType = "ScannedReceipt",
                        entityId = receipt.id,
                        issueType = "RECEIPT_WITHOUT_EVENT",
                        pipeline = AppPipeline.RECEIPT
                    )
                }
            } catch (e: Exception) {
                CancellationSafe.rethrowIfCancellation(e)
            }
        }
        return count
    }

    private suspend fun checkPendingReviewsWithoutReceipts(): Int {
        var count = 0
        try {
            // Query all pending reviews using a wide date range
            val allReviews = pendingReviewDao.getPendingReviewsInDateRange(0L, Long.MAX_VALUE)

            for (review in allReviews) {
                val rid = review.scannedReceiptId ?: continue
                try {
                    val receipt = scannedReceiptDao.getById(rid)
                    if (receipt == null) {
                        count++
                        emitOrphanDiagnostic(
                            entityType = "PendingReview",
                            entityId = review.id,
                            issueType = "PENDING_REVIEW_WITHOUT_RECEIPT",
                            pipeline = AppPipeline.RECEIPT,
                            details = "referencedReceiptId=$rid"
                        )
                    }
                } catch (e: Exception) {
                    CancellationSafe.rethrowIfCancellation(e)
                }
            }
        } catch (e: Exception) {
            CancellationSafe.rethrowIfCancellation(e)
            Timber.w(e, "Failed to enumerate PendingReviews")
        }
        return count
    }

    private suspend fun checkOccurrencesWithoutEvents(): Int {
        var count = 0
        try {
            // Use getByStatus for known statuses since occurrenceDao has no getAll()
            val statuses = listOf("PLANNED", "PAID", "SKIPPED", "CANCELLED", "MISSED", "IGNORED")
            val seenIds = mutableSetOf<Long>()

            for (status in statuses) {
                try {
                    val occurrences = occurrenceDao.getByStatus(status)
                    for (occ in occurrences) {
                        if (occ.id in seenIds) continue
                        seenIds.add(occ.id)

                        try {
                            val events = lifecycleEventDao.getEventsForOccurrence(occ.id)
                            if (events.isEmpty()) {
                                count++
                                emitOrphanDiagnostic(
                                    entityType = "RecurringOccurrence",
                                    entityId = occ.id,
                                    issueType = "OCCURRENCE_WITHOUT_EVENT",
                                    pipeline = AppPipeline.RECURRING,
                                    details = "status=$status"
                                )
                            }
                        } catch (e: Exception) {
                            CancellationSafe.rethrowIfCancellation(e)
                        }
                    }
                } catch (e: Exception) {
                    CancellationSafe.rethrowIfCancellation(e)
                }
            }
        } catch (e: Exception) {
            CancellationSafe.rethrowIfCancellation(e)
            Timber.w(e, "Failed to enumerate occurrences")
        }
        return count
    }

    // --- diagnostic emission ---

    private suspend fun emitOrphanDiagnostic(
        entityType: String,
        entityId: Long,
        issueType: String,
        pipeline: AppPipeline,
        details: String? = null
    ) {
        val metadataBuilder = SafeEventMetadata.builder()
            .put("entityId", entityId)
            .put("issueType", issueType)
        if (details != null) {
            metadataBuilder.put("details", details)
        }

        diagnosticEventWriter.emit(
            DiagnosticEvent(
                pipeline = pipeline,
                stage = "LEGACY_CONSISTENCY_CHECK",
                outcome = EventOutcome.NEEDS_REVIEW,
                entityType = entityType,
                entityId = entityId,
                metadata = metadataBuilder.build(),
                isTerminal = false
            )
        )
    }

    private suspend fun emitSummaryDiagnostic(report: ConsistencyReport) {
        val metadata = SafeEventMetadata.builder()
            .put("receiptsWithoutEvent", report.receiptsWithoutEvent)
            .put("pendingReviewsWithoutReceipt", report.pendingReviewsWithoutReceipt)
            .put("occurrencesWithoutEvent", report.occurrencesWithoutEvent)
            .put("totalItemsChecked", report.totalItemsChecked)
            .build()

        val hasIssues = report.receiptsWithoutEvent > 0 ||
            report.pendingReviewsWithoutReceipt > 0 ||
            report.occurrencesWithoutEvent > 0

        diagnosticEventWriter.emit(
            DiagnosticEvent(
                pipeline = AppPipeline.WORKER,
                stage = "LEGACY_CONSISTENCY_CHECK",
                outcome = if (hasIssues) EventOutcome.NEEDS_REVIEW else EventOutcome.COMPLETED,
                metadata = metadata,
                isTerminal = true
            )
        )
    }
}
