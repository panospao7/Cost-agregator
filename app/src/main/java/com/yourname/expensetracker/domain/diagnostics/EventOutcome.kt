package com.yourname.expensetracker.domain.diagnostics

enum class EventOutcome {
    RECEIVED,
    ATTEMPTED,
    COMPLETED,
    CREATED,
    UPDATED,
    DELETED,
    LINKED,
    UNLINKED,
    DUPLICATE,
    NEEDS_REVIEW,
    DROPPED,
    SKIPPED,
    BLOCKED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    CANCELLED,
    SIDE_EFFECT_STARTED,
    SIDE_EFFECT_COMPLETED,
    SIDE_EFFECT_FAILED
}
