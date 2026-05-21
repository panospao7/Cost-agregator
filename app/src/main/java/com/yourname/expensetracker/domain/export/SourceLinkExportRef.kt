package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.EntitySourceLink

/**
 * PR7: Privacy-safe representation of a source link for export output.
 *
 * Contains only safe, non-sensitive fields that can be included in
 * exported CSV/JSON data.
 */
data class SourceLinkExportRef(
    /** High-level source identifier (e.g. "CSV_IMPORT", "RECEIPT_SCAN"). */
    val sourceType: String,
    /** The type of source entity (e.g. "FILE_IMPORT", "RAW_NOTIFICATION"). */
    val sourceEntityType: String,
    /** Local database ID of the source entity (may be null for external-only sources). */
    val sourceEntityLocalId: Long?,
    /** Role of the link (e.g. "CREATED_FROM", "DUPLICATE_MATCHED"). */
    val linkRole: String,
    /** Status of the link (e.g. "ACTIVE", "DUPLICATE"). */
    val linkStatus: String,
    /** Whether this is the primary link for the target entity. */
    val isPrimary: Boolean,
    /** Optional JSON metadata (safe fields only). */
    val metadataJson: String?
) {
    companion object {
        /**
         * Converts an [EntitySourceLink] to its export-safe representation.
         */
        fun fromEntitySourceLink(link: EntitySourceLink): SourceLinkExportRef {
            return SourceLinkExportRef(
                sourceType = link.sourceType,
                sourceEntityType = link.sourceEntityType,
                sourceEntityLocalId = link.sourceEntityLocalId,
                linkRole = link.linkRole,
                linkStatus = link.linkStatus,
                isPrimary = link.isPrimary,
                metadataJson = link.metadataJson
            )
        }
    }
}
