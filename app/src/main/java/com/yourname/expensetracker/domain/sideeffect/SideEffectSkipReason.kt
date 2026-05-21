package com.yourname.expensetracker.domain.sideeffect

enum class SideEffectSkipReason {
    NOT_APPLICABLE,
    PRIVACY_DENIED,
    RESTORE_BLOCKED,
    MISSING_ENTITY,
    ALREADY_PROCESSED,
    DISABLED_BY_SETTINGS,
    LOW_CONFIDENCE,
    NO_WORK,
    DUPLICATE,
    PERMISSION_DENIED
}
