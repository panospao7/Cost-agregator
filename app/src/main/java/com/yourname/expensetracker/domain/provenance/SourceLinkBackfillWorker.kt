package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.EmailReceiptDao
import com.yourname.expensetracker.data.database.dao.EntitySourceLinkDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.EntitySourceLink
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PR8: Backfill worker that migrates legacy source data into entity_source_links.
 *
 * Scans existing expenses and creates source links for:
 * - Expenses with legacy source names (SMS, EMAIL, MANUAL, etc.)
 * - Receipt-expense links that lack provenance entries
 * - Pending reviews with raw notification references
 *
 * Idempotent: uses OnConflictStrategy.IGNORE so re-runs are safe.
 * Reports progress via callback for UI display.
 */
@Singleton
class SourceLinkBackfillWorker @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val sourceLinkDao: EntitySourceLinkDao,
    private val pendingReviewDao: PendingReviewDao,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val rawNotificationDao: RawNotificationDao,
    private val emailReceiptDao: EmailReceiptDao,
    private val receiptExpenseLinkDao: ReceiptExpenseLinkDao,
    private val timeProvider: TimeProvider,
    private val writeBarrier: DatabaseWriteBarrier
) {

    data class BackfillProgress(
        val totalExpenses: Int,
        val processedExpenses: Int,
        val linksCreated: Int,
        val linksSkipped: Int,
        val errors: Int
    )

    /**
     * Runs the full backfill. Returns final progress report.
     */
    suspend fun runBackfill(
        onProgress: (BackfillProgress) -> Unit = {}
    ): BackfillProgress {
        writeBarrier.checkWritesAllowed("SourceLinkBackfillWorker.runBackfill")
        var linksCreated = 0
        var linksSkipped = 0
        var errors = 0

        val allExpenses = expenseDao.getAll()
        val total = allExpenses.size

        for ((index, expense) in allExpenses.withIndex()) {
            try {
                val result = backfillExpense(expense)
                linksCreated += result.created
                linksSkipped += result.skipped
            } catch (e: Exception) {
                Timber.e(e, "Backfill failed for expense ${expense.id}")
                errors++
            }
            if (index % 100 == 0 || index == total - 1) {
                onProgress(BackfillProgress(total, index + 1, linksCreated, linksSkipped, errors))
            }
        }

        return BackfillProgress(total, total, linksCreated, linksSkipped, errors)
    }

    data class BackfillResult(val created: Int, val skipped: Int)

    private suspend fun backfillExpense(expense: Expense): BackfillResult {
        var created = 0
        var skipped = 0

        // Check existing source links once — reuse across all backfill steps
        val existing = sourceLinkDao.getForExpense(expense.id)
        val hasPrimary = existing.any { it.isPrimary }

        // 1. Legacy source name backfill
        if (!hasPrimary && expense.source != null) {
            val result = backfillLegacySource(expense, existing)
            created += result.created
            skipped += result.skipped
        }

        // 2. Receipt-expense link backfill
        val receiptResult = backfillReceiptLinks(expense, existing)
        created += receiptResult.created
        skipped += receiptResult.skipped

        // 3. Notification/pending-review backfill
        val notificationResult = backfillNotificationLinks(expense, existing)
        created += notificationResult.created
        skipped += notificationResult.skipped

        return BackfillResult(created, skipped)
    }

    private suspend fun backfillLegacySource(
        expense: Expense,
        existing: List<EntitySourceLink>
    ): BackfillResult {
        // Skip if already has a LEGACY_BACKFILL or CREATED_FROM link
        if (existing.any {
                it.linkRole == SourceLinkRole.LEGACY_BACKFILL.name ||
                it.linkRole == SourceLinkRole.CREATED_FROM.name
            }) {
            return BackfillResult(0, 1)
        }

        val sourceType = mapLegacySourceToEnum(expense.source)

        val link = EntitySourceLink(
            targetEntityType = "EXPENSE",
            targetEntityId = expense.id,
            sourceType = sourceType,
            sourceEntityType = SourceEntityType.LEGACY_SOURCE_ONLY.name,
            sourceEntityLocalId = null,
            sourceIdentityKey = "legacy:source:$sourceType:unknown",
            externalIdHash = null,
            externalFingerprintHash = null,
            providerId = null,
            accountIdHash = null,
            operationRunId = null,
            importBatchId = null,
            importRowNumber = null,
            linkRole = SourceLinkRole.LEGACY_BACKFILL.name,
            linkStatus = SourceLinkStatus.LEGACY_PARTIAL.name,
            confidence = null,
            isPrimary = true,
            createdAt = timeProvider.now(),
            createdBy = "backfill_worker",
            correlationId = null,
            metadataJson = SafeProvenanceMetadata.fromMap(
                mapOf("originalSource" to (expense.source ?: "unknown"))
            ).takeIf { !it.isEmpty() }?.toJson(),
            metadataSchemaVersion = 1
        )

        val inserted = sourceLinkDao.insert(link)
        return if (inserted > 0) BackfillResult(1, 0) else BackfillResult(0, 1)
    }

    private suspend fun backfillReceiptLinks(
        expense: Expense,
        existing: List<EntitySourceLink>
    ): BackfillResult {
        var created = 0
        var skipped = 0

        // Check if a SCANNED_RECEIPT link already exists for this expense
        if (existing.any { it.sourceEntityType == SourceEntityType.SCANNED_RECEIPT.name }) {
            return BackfillResult(0, 0)
        }

        // Find receipt-expense links for this expense
        val receiptLinks = receiptExpenseLinkDao.getLinksForExpense(expense.id)
        for (receiptLink in receiptLinks) {
            val link = EntitySourceLink(
                targetEntityType = "EXPENSE",
                targetEntityId = expense.id,
                sourceType = ExpenseSource.RECEIPT_SCAN.name,
                sourceEntityType = SourceEntityType.SCANNED_RECEIPT.name,
                sourceEntityLocalId = receiptLink.receiptId,
                sourceIdentityKey = SourceIdentityKeyFactory.localScannedReceipt(receiptLink.receiptId),
                externalIdHash = null,
                externalFingerprintHash = null,
                providerId = null,
                accountIdHash = null,
                operationRunId = null,
                importBatchId = null,
                importRowNumber = null,
                linkRole = SourceLinkRole.LINKED_PROOF.name,
                linkStatus = SourceLinkStatus.ACTIVE.name,
                confidence = receiptLink.confidence,
                isPrimary = receiptLink.isPrimary,
                createdAt = receiptLink.createdAt,
                createdBy = "backfill_worker",
                correlationId = null,
                metadataJson = null,
                metadataSchemaVersion = 1
            )

            val inserted = sourceLinkDao.insert(link)
            if (inserted > 0) created++ else skipped++
        }

        return BackfillResult(created, skipped)
    }

    private suspend fun backfillNotificationLinks(
        expense: Expense,
        existing: List<EntitySourceLink>
    ): BackfillResult {
        var created = 0
        var skipped = 0

        // Check if a RAW_NOTIFICATION link already exists for this expense
        if (existing.any { it.sourceEntityType == SourceEntityType.RAW_NOTIFICATION.name }) {
            return BackfillResult(0, 0)
        }

        // Find pending reviews that reference this expense (via approvedExpenseId or similar)
        // and have a rawNotificationId
        val allReviews = pendingReviewDao.getPendingUncapped()
        for (reviewWithReceipt in allReviews) {
            val review = reviewWithReceipt.review
            // Only process approved reviews that created this expense
            // Since we can't directly link review→expense in legacy data,
            // we rely on the expense.source field being set to notification-related values
            if (expense.source?.uppercase()?.contains("NOTIFICATION") == true ||
                expense.source?.uppercase()?.contains("SMS") == true) {
                review.rawNotificationId?.let { notificationId ->
                    val link = EntitySourceLink(
                        targetEntityType = "EXPENSE",
                        targetEntityId = expense.id,
                        sourceType = ExpenseSource.SMS_NOTIFICATION.name,
                        sourceEntityType = SourceEntityType.RAW_NOTIFICATION.name,
                        sourceEntityLocalId = notificationId,
                        sourceIdentityKey = SourceIdentityKeyFactory.localRawNotification(notificationId),
                        externalIdHash = null,
                        externalFingerprintHash = null,
                        providerId = null,
                        accountIdHash = null,
                        operationRunId = null,
                        importBatchId = null,
                        importRowNumber = null,
                        linkRole = SourceLinkRole.CREATED_FROM.name,
                        linkStatus = SourceLinkStatus.ACTIVE.name,
                        confidence = null,
                        isPrimary = true,
                        createdAt = timeProvider.now(),
                        createdBy = "backfill_worker",
                        correlationId = null,
                        metadataJson = null,
                        metadataSchemaVersion = 1
                    )

                    val inserted = sourceLinkDao.insert(link)
                    if (inserted > 0) created++ else skipped++
                }
            }
        }

        return BackfillResult(created, skipped)
    }

    /**
     * Maps a legacy source string to the canonical ExpenseSource enum name.
     * Handles both abbreviated legacy names and full canonical enum names.
     */
    private fun mapLegacySourceToEnum(rawSource: String?): String {
        val source = rawSource?.uppercase() ?: return ExpenseSource.UNKNOWN.name

        // First try exact enum name match
        runCatching {
            return ExpenseSource.valueOf(source).name
        }

        // Then try abbreviated/legacy mappings
        return when (source) {
            "SMS", "NOTIFICATION", "NOTIFICATION_AUTO_ACCEPT" -> ExpenseSource.SMS_NOTIFICATION.name
            "EMAIL", "EMAIL_RECEIPT" -> ExpenseSource.EMAIL_RECEIPT.name
            "RECEIPT", "RECEIPT_SCAN" -> ExpenseSource.RECEIPT_SCAN.name
            "BANK", "BANK_SYNC" -> ExpenseSource.BANK_SYNC.name
            "CSV", "CSV_IMPORT" -> ExpenseSource.CSV_IMPORT.name
            "JSON", "JSON_IMPORT" -> ExpenseSource.CSV_IMPORT.name
            "MANUAL" -> ExpenseSource.MANUAL.name
            else -> ExpenseSource.UNKNOWN.name
        }
    }
}
