package com.yourname.expensetracker.domain.transaction

enum class DeduplicationMode {
    STANDARD,
    STRICT_EXTERNAL_ID,
    BULK_IMPORT,
    SKIP_FOR_DEBUG_RESTORE
}
