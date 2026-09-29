package com.yourname.expensetracker.domain.backup

enum class DatabaseStatsFailureReason {
    READ_BLOCKED,
    QUERY_FAILED
}

/** Bounded failure transport; never carries a database message, path, or payload. */
class DatabaseStatsUnavailableException(val reason: DatabaseStatsFailureReason) :
    Exception(reason.name)
