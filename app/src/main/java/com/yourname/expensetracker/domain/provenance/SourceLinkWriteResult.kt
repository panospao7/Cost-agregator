package com.yourname.expensetracker.domain.provenance

/**
 * Typed result for source-link write operations.
 * Replaces the previous silent-swallow pattern with explicit outcome tracking.
 */
sealed interface SourceLinkWriteResult {
    data class Created(val sourceLinkId: Long) : SourceLinkWriteResult
    data class AlreadyExists(val sourceLinkId: Long?) : SourceLinkWriteResult
    data class Failed(
        val errorClass: String,
        val errorMessageHash: String?,
        val retryable: Boolean
    ) : SourceLinkWriteResult
}
