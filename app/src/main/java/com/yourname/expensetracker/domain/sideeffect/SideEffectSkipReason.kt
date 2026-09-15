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
    PERMISSION_DENIED,
    /** RP-12 12b (P3-007): item-dependent work has no permitted structured data (restricted storage mode or process restart). */
    STRUCTURED_RECEIPT_DATA_UNAVAILABLE
}
