package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.data.database.dao.EntitySourceLinkDao
import com.yourname.expensetracker.data.database.entity.EntitySourceLink
import com.yourname.expensetracker.domain.privacy.SensitiveHashingService
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CURR-SL-01: Default implementation of SourceLinkWriter.
 *
 * Does NOT open its own transaction — callers must wrap in a Room transaction
 * for atomicity with expense/event insertion.
 */
@Singleton
class SourceLinkWriterImpl @Inject constructor(
    private val sourceLinkDao: EntitySourceLinkDao,
    private val hashingService: SensitiveHashingService,
    private val timeProvider: TimeProvider
) : SourceLinkWriter {

    override suspend fun linkExpense(
        expenseId: Long,
        payload: SourceLinkPayload,
        correlationId: String?
    ): SourceLinkWriteResult {
        return linkTarget(TargetEntityType.EXPENSE, expenseId, payload, correlationId)
    }

    override suspend fun linkTarget(
        targetType: TargetEntityType,
        targetId: Long,
        payload: SourceLinkPayload,
        correlationId: String?
    ): SourceLinkWriteResult {
        // Validate
        if (payload.sourceIdentityKey().isBlank()) {
            return SourceLinkWriteResult.Rejected("sourceIdentityKey is required")
        }

        val identityKey = payload.sourceIdentityKey()

        // Check if already exists
        if (sourceLinkDao.exists(targetType.name, targetId, identityKey)) {
            return SourceLinkWriteResult.AlreadyExists
        }

        // Hash external IDs
        val externalIdHash = payload.externalId?.let {
            hashingService.hmacSha256Prefix(it, "source_link_external_id")
        }
        val externalFingerprintHash = payload.externalFingerprint?.let {
            hashingService.hmacSha256Prefix(it, "source_link_fingerprint")
        }
        val accountIdHash = payload.accountId?.let {
            hashingService.hmacSha256Prefix(it, "source_link_account_id")
        }

        val link = EntitySourceLink(
            targetEntityType = targetType.name,
            targetEntityId = targetId,
            sourceType = payload.sourceType,
            sourceEntityType = payload.sourceEntityType.name,
            sourceEntityLocalId = payload.sourceEntityLocalId,
            sourceIdentityKey = identityKey,
            externalIdHash = externalIdHash,
            externalFingerprintHash = externalFingerprintHash,
            providerId = payload.providerId,
            accountIdHash = accountIdHash,
            operationRunId = payload.operationRunId,
            importBatchId = payload.importBatchId,
            importRowNumber = payload.importRowNumber,
            linkRole = payload.role.name,
            linkStatus = payload.status.name,
            confidence = payload.confidence,
            isPrimary = payload.isPrimary,
            createdAt = timeProvider.now(),
            createdBy = payload.createdBy,
            correlationId = correlationId,
            metadataJson = payload.metadata.takeIf { !it.isEmpty() }?.toJson(),
            metadataSchemaVersion = 1
        )

        val insertedId = sourceLinkDao.insert(link)
        return if (insertedId > 0) {
            SourceLinkWriteResult.Inserted(insertedId)
        } else {
            SourceLinkWriteResult.AlreadyExists
        }
    }

    override suspend fun linkExpenseSourcesFromRequest(
        expenseId: Long,
        requestSourceFields: ExpenseSourceFields,
        correlationId: String?
    ): List<SourceLinkWriteResult> {
        val results = mutableListOf<SourceLinkWriteResult>()

        // rawNotificationId -> RAW_NOTIFICATION / CREATED_FROM
        requestSourceFields.rawNotificationId?.let { id ->
            results.add(linkExpense(expenseId, SourceLinkPayload(
                sourceType = requestSourceFields.sourceName ?: "NOTIFICATION_AUTO_ACCEPT",
                sourceEntityType = SourceEntityType.RAW_NOTIFICATION,
                sourceEntityLocalId = id,
                role = SourceLinkRole.CREATED_FROM,
                isPrimary = true,
                providerId = requestSourceFields.providerId,
                operationRunId = requestSourceFields.operationRunId
            ), correlationId))
        }

        // pendingReviewId -> PENDING_REVIEW / APPROVED_FROM
        requestSourceFields.pendingReviewId?.let { id ->
            results.add(linkExpense(expenseId, SourceLinkPayload(
                sourceType = requestSourceFields.sourceName ?: "REVIEW_APPROVAL",
                sourceEntityType = SourceEntityType.PENDING_REVIEW,
                sourceEntityLocalId = id,
                role = SourceLinkRole.APPROVED_FROM,
                isPrimary = true,
                operationRunId = requestSourceFields.operationRunId
            ), correlationId))
        }

        // scannedReceiptId -> SCANNED_RECEIPT / CREATED_FROM
        requestSourceFields.scannedReceiptId?.let { id ->
            results.add(linkExpense(expenseId, SourceLinkPayload(
                sourceType = requestSourceFields.sourceName ?: "RECEIPT_SCAN",
                sourceEntityType = SourceEntityType.SCANNED_RECEIPT,
                sourceEntityLocalId = id,
                role = SourceLinkRole.CREATED_FROM,
                isPrimary = true,
                operationRunId = requestSourceFields.operationRunId
            ), correlationId))
        }

        // emailReceiptSourceId -> EMAIL_RECEIPT_SOURCE / CREATED_FROM
        requestSourceFields.emailReceiptSourceId?.let { id ->
            results.add(linkExpense(expenseId, SourceLinkPayload(
                sourceType = requestSourceFields.sourceName ?: "EMAIL_RECEIPT",
                sourceEntityType = SourceEntityType.EMAIL_RECEIPT_SOURCE,
                sourceEntityLocalId = id,
                role = SourceLinkRole.CREATED_FROM,
                isPrimary = true,
                operationRunId = requestSourceFields.operationRunId
            ), correlationId))
        }

        // groupId -> GROUP / GENERATED_FROM
        requestSourceFields.groupId?.let { id ->
            results.add(linkExpense(expenseId, SourceLinkPayload(
                sourceType = requestSourceFields.sourceName ?: "GROUP_EXPENSE",
                sourceEntityType = SourceEntityType.GROUP,
                sourceEntityLocalId = id,
                role = SourceLinkRole.GENERATED_FROM,
                isPrimary = true,
                operationRunId = requestSourceFields.operationRunId
            ), correlationId))
        }

        // csvImportBatchId + csvRowNumber -> CSV_IMPORT_ROW / IMPORTED_FROM
        if (requestSourceFields.csvImportBatchId != null && requestSourceFields.csvRowNumber != null) {
            results.add(linkExpense(expenseId, SourceLinkPayload(
                sourceType = requestSourceFields.sourceName ?: "CSV_IMPORT",
                sourceEntityType = SourceEntityType.CSV_IMPORT_ROW,
                role = SourceLinkRole.IMPORTED_FROM,
                isPrimary = true,
                importBatchId = requestSourceFields.csvImportBatchId,
                importRowNumber = requestSourceFields.csvRowNumber,
                operationRunId = requestSourceFields.operationRunId,
                externalFingerprint = requestSourceFields.externalFingerprint
            ), correlationId))
        }

        // externalFingerprint alone -> source-specific
        if (requestSourceFields.externalFingerprint != null &&
            requestSourceFields.rawNotificationId == null &&
            requestSourceFields.pendingReviewId == null &&
            requestSourceFields.scannedReceiptId == null &&
            requestSourceFields.emailReceiptSourceId == null &&
            requestSourceFields.groupId == null &&
            (requestSourceFields.csvImportBatchId == null || requestSourceFields.csvRowNumber == null)) {
            // Generic external fingerprint link
            results.add(linkExpense(expenseId, SourceLinkPayload(
                sourceType = requestSourceFields.sourceName ?: "UNKNOWN",
                sourceEntityType = SourceEntityType.UNKNOWN,
                role = SourceLinkRole.CREATED_FROM,
                externalFingerprint = requestSourceFields.externalFingerprint,
                providerId = requestSourceFields.providerId,
                operationRunId = requestSourceFields.operationRunId
            ), correlationId))
        }

        // If no specific source fields but we have a source name, create a legacy link
        if (results.isEmpty() && requestSourceFields.sourceName != null) {
            results.add(linkExpense(expenseId, SourceLinkPayload(
                sourceType = requestSourceFields.sourceName,
                sourceEntityType = SourceEntityType.LEGACY_SOURCE_ONLY,
                role = SourceLinkRole.LEGACY_BACKFILL,
                status = SourceLinkStatus.LEGACY_PARTIAL,
                operationRunId = requestSourceFields.operationRunId
            ), correlationId))
        }

        return results
    }

    // Helper: derive sourceIdentityKey from payload
    private fun SourceLinkPayload.sourceIdentityKey(): String {
        // If caller provided an explicit key via metadata, use it
        // Otherwise derive from source entity type and local ID
        return when (sourceEntityType) {
            SourceEntityType.RAW_NOTIFICATION ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.localRawNotification(it) }
                    ?: "local:raw_notification:unknown"
            SourceEntityType.PENDING_REVIEW ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.localPendingReview(it) }
                    ?: "local:pending_review:unknown"
            SourceEntityType.SCANNED_RECEIPT ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.localScannedReceipt(it) }
                    ?: "local:scanned_receipt:unknown"
            SourceEntityType.EMAIL_RECEIPT_SOURCE ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.localEmailReceiptSource(it) }
                    ?: "local:email_receipt_source:unknown"
            SourceEntityType.RECEIPT_EXPENSE_LINK ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.localReceiptExpenseLink(it) }
                    ?: "local:receipt_expense_link:unknown"
            SourceEntityType.CSV_IMPORT_ROW ->
                importBatchId?.let { batch ->
                    importRowNumber?.let { row -> SourceIdentityKeyFactory.csvImportRow(batch, row) }
                } ?: "import:csv:unknown:row:0"
            SourceEntityType.JSON_IMPORT_ROW ->
                importBatchId?.let { batch ->
                    importRowNumber?.let { row -> SourceIdentityKeyFactory.jsonImportRow(batch, row) }
                } ?: "import:json:unknown:row:0"
            SourceEntityType.BANK_TRANSACTION ->
                externalIdHash?.let { hash ->
                    SourceIdentityKeyFactory.externalBankTransaction(providerId ?: "unknown", hash)
                } ?: "external:bank_transaction:unknown:unknown"
            SourceEntityType.GROUP ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.groupExpense(it) }
                    ?: "local:group:unknown"
            SourceEntityType.RECURRING_RULE ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.recurringRule(it) }
                    ?: "local:recurring_rule:unknown"
            SourceEntityType.RECURRING_OCCURRENCE ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.recurringOccurrence(it) }
                    ?: "local:recurring_occurrence:unknown"
            SourceEntityType.PLANNED_EXPENSE ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.plannedExpense(it) }
                    ?: "local:planned_expense:unknown"
            SourceEntityType.MANUAL_ENTRY ->
                "manual:${createdBy ?: "unknown"}:expense:unknown"
            SourceEntityType.LEGACY_SOURCE_ONLY ->
                "legacy:source:${sourceType}:unknown"
            SourceEntityType.BANK_SYNC_RUN ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.bankSyncRun(it) }
                    ?: "local:bank_sync_run:unknown"
            SourceEntityType.FILE_IMPORT ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.fileImport(it) }
                    ?: "local:file_import:unknown"
            SourceEntityType.BANK_CONNECTION ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.bankConnection(it) }
                    ?: "local:bank_connection:unknown"
            SourceEntityType.BANK_ACCOUNT ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.bankAccount(it) }
                    ?: "local:bank_account:unknown"
            SourceEntityType.BANK_STATEMENT_IMPORT_RUN ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.bankStatementImportRun(it) }
                    ?: "local:bank_statement_import_run:unknown"
            SourceEntityType.BANK_STATEMENT_IMPORT_ITEM ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.bankStatementImportItem(it) }
                    ?: "local:bank_statement_import_item:unknown"
            SourceEntityType.CSV_IMPORT_RUN ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.csvImportRun(it) }
                    ?: "local:csv_import_run:unknown"
            SourceEntityType.JSON_IMPORT_RUN ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.jsonImportRun(it) }
                    ?: "local:json_import_run:unknown"
            SourceEntityType.DEBUG_TOOL ->
                sourceEntityLocalId?.let { SourceIdentityKeyFactory.debugTool(it) }
                    ?: "local:debug_tool:unknown"
            SourceEntityType.MIGRATION ->
                // P2: Derive identity from operation run ID for traceability,
                // falling back to a versioned default only when no run context exists.
                operationRunId?.let { runId ->
                    SourceIdentityKeyFactory.migrationFromRun(runId)
                } ?: SourceIdentityKeyFactory.migration(1)
            else -> "unknown:unknown"
        }
    }
}
