package com.yourname.expensetracker.domain.transaction

sealed class CreateExpenseResult {
    data class Created(val expenseId: Long) : CreateExpenseResult()
    data class DuplicateSkipped(
        val existingExpenseId: Long,
        val reason: String,
        val eventLogged: Boolean = false
    ) : CreateExpenseResult()
    data class ValidationFailed(val errors: List<String>) : CreateExpenseResult()

    /**
     * P2-002: typed insert conflict — both unique indexes (nullable
     * rawNotificationId and dedupeKey) rejected the insert and no identity
     * could be proven for an existing row.
     *
     * [reasonCode] is a controlled constant from [InsertConflictCodes] —
     * never payload-derived. The default preserves the pre-existing
     * behavior where every surfaced [InsertConflict] was unresolved.
     */
    data class InsertConflict(
        val dedupeKey: String,
        val reasonCode: String = InsertConflictCodes.UNRESOLVED
    ) : CreateExpenseResult()

    data class Error(val exception: Throwable) : CreateExpenseResult()
}

/**
 * P2-002: bounded, controlled reason codes for insert-conflict outcomes.
 * Reason-code fields must contain these constants only — never raw text,
 * SQL messages, or payload-derived values.
 */
object InsertConflictCodes {
    /** Conflict resolved to an existing row via the unique rawNotificationId index. */
    const val RESOLVED_RAW_NOTIFICATION_ID = "CREATE_INSERT_CONFLICT_RESOLVED_RAW_NOTIFICATION_ID"

    /** Conflict resolved to an existing row via the unique dedupeKey index. */
    const val RESOLVED_DEDUPE_KEY = "CREATE_INSERT_CONFLICT_RESOLVED_DEDUPE_KEY"

    /** Conflict resolved via the bounded fuzzy window resolver (non-STRICT modes only). */
    const val RESOLVED_FUZZY_WINDOW = "CREATE_INSERT_CONFLICT_RESOLVED_FUZZY_WINDOW"

    /** No identity could be proven for the conflicting row. */
    const val UNRESOLVED = "CREATE_INSERT_CONFLICT_UNRESOLVED"
}
