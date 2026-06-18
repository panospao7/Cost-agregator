package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.domain.transaction.ExpenseSource

/**
 * PR7: Builds source-link payloads for CSV/JSON import provenance.
 *
 * Safe fields only: importFormat, importSchemaVersion, importRowCount,
 * statementPageNumber, parserId, parserVersion.
 *
 * Never include: raw file path, raw file content, raw column headers.
 *
 * TODO: This factory is currently dead code — CSV/JSON importers use
 *   `CreateExpenseRequest.fileImportRunId` which routes through
 *   `CreateExpenseSourceLinkMapper` instead. This factory's richer metadata
 *   support (parserId, parserVersion, statementPageNumber) is not yet wired
 *   into the import pipeline. Integrate when import coordinators need to
 *   attach detailed parser/statement provenance.
 */
object ImportSourceLinkPayloadFactory {

    /**
     * Creates a payload linking a FILE_IMPORT to an EXPENSE.
     */
    fun forImportToExpense(
        importRunId: Long,
        format: String,
        schemaVersion: Int? = null,
        rowCount: Int? = null,
        statementPageNumber: Int? = null,
        parserId: String? = null,
        parserVersion: String? = null
    ): SourceLinkPayload {
        val metadataMap = mutableMapOf<String, Any?>()
        metadataMap["importFormat"] = format
        schemaVersion?.let { metadataMap["importSchemaVersion"] = it }
        rowCount?.let { metadataMap["importRowCount"] = it }
        statementPageNumber?.let { metadataMap["statementPageNumber"] = it }
        parserId?.let { metadataMap["parserId"] = it }
        parserVersion?.let { metadataMap["parserVersion"] = it }
        return SourceLinkPayload(
            sourceType = ExpenseSource.CSV_IMPORT.name,
            sourceEntityType = SourceEntityType.FILE_IMPORT,
            sourceEntityLocalId = importRunId,
            role = SourceLinkRole.CREATED_FROM,
            status = SourceLinkStatus.ACTIVE,
            isPrimary = true,
            metadata = SafeProvenanceMetadata.fromMap(metadataMap)
        )
    }

    /**
     * Creates a payload linking a FILE_IMPORT to a PENDING_REVIEW.
     */
    fun forImportToReview(
        importRunId: Long,
        format: String,
        schemaVersion: Int? = null,
        rowCount: Int? = null,
        statementPageNumber: Int? = null,
        parserId: String? = null,
        parserVersion: String? = null
    ): SourceLinkPayload {
        val metadataMap = mutableMapOf<String, Any?>()
        metadataMap["importFormat"] = format
        schemaVersion?.let { metadataMap["importSchemaVersion"] = it }
        rowCount?.let { metadataMap["importRowCount"] = it }
        statementPageNumber?.let { metadataMap["statementPageNumber"] = it }
        parserId?.let { metadataMap["parserId"] = it }
        parserVersion?.let { metadataMap["parserVersion"] = it }
        return SourceLinkPayload(
            sourceType = ExpenseSource.CSV_IMPORT.name,
            sourceEntityType = SourceEntityType.FILE_IMPORT,
            sourceEntityLocalId = importRunId,
            role = SourceLinkRole.REVIEWED_FROM,
            status = SourceLinkStatus.ACTIVE,
            isPrimary = true,
            metadata = SafeProvenanceMetadata.fromMap(metadataMap)
        )
    }

    /**
     * Creates a payload for an import dedupe match.
     */
    fun forDedupeMatch(
        importRunId: Long,
        matchedExpenseId: Long,
        matchType: String
    ): SourceLinkPayload {
        val metadataMap = mutableMapOf<String, Any?>()
        metadataMap["matchedExpenseId"] = matchedExpenseId
        metadataMap["matchType"] = matchType
        return SourceLinkPayload(
            sourceType = ExpenseSource.CSV_IMPORT.name,
            sourceEntityType = SourceEntityType.FILE_IMPORT,
            sourceEntityLocalId = importRunId,
            role = SourceLinkRole.DUPLICATE_MATCHED,
            status = SourceLinkStatus.DUPLICATE,
            isPrimary = false,
            metadata = SafeProvenanceMetadata.fromMap(metadataMap)
        )
    }
}
