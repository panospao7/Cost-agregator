package com.yourname.expensetracker.domain.transaction

enum class ExpenseSource {
    MANUAL_ENTRY,
    MANUAL,
    NOTIFICATION_AUTO_ACCEPT,
    SMS_NOTIFICATION,
    REVIEW_APPROVAL,
    RECEIPT_SCAN,
    RECEIPT_BATCH_REVIEW,
    BANK_STATEMENT_REVIEW,
    CSV_IMPORT,
    EMAIL_RECEIPT,
    GROUP_EXPENSE,
    BANK_SYNC,
    BANK_API_SYNC,
    RECURRING_GENERATED,
    DEBUG_TOOL,
    MIGRATION,
    UNKNOWN,

    /**
     * Expense created by the installment plan feature (auto-record, confirm, payoff).
     * Persisted by [name] (TEXT), never by ordinal, so it is appended after the existing values.
     */
    INSTALLMENT_PLAN
}
