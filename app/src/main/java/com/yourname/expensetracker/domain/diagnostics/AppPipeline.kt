package com.yourname.expensetracker.domain.diagnostics

enum class AppPipeline {
    NOTIFICATION,
    TRANSACTION,
    RECEIPT,
    RECURRING,
    BUDGET,
    FORECAST,
    BACKUP_RESTORE,
    PRIVACY,
    WORKER,
    BANK,
    EMAIL,
    EXPORT_IMPORT
}
