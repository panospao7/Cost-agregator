package com.yourname.expensetracker.domain.provenance

/**
 * PR3: Safe context metadata for pending-review source links.
 *
 * Only privacy-safe summary fields are included here.
 * Sensitive fields (e.g. raw packageName) must NOT be stored.
 */
data class PendingReviewSourceContext(
    val stage: String? = null,
    val reason: String? = null,
    val parserId: String? = null,
    val parserVersion: String? = null,
    val routingDecision: String? = null,
    val confidence: Float? = null,
    val extractionState: String? = null
) {
    companion object {
        fun empty() = PendingReviewSourceContext()
    }
}
