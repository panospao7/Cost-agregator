package com.yourname.expensetracker.domain.transaction.lifecycle

enum class TransactionUpdateKind {
    FULL,
    CATEGORY_ONLY,
    LOCATION_ONLY,
    BUSINESS_FLAGS_ONLY,
    MERCHANT,
    TYPE,
    TRANSFER_DETAILS
}
