package com.yourname.expensetracker.domain.receipt

enum class ReceiptProcessingStatus {
    CAPTURED,
    VALIDATING,
    VALIDATION_FAILED,
    DUPLICATE_DETECTED,
    OCR_PENDING,
    OCR_RUNNING,
    OCR_FAILED,
    OCR_COMPLETED,
    PARSE_FAILED,
    PARSED,
    REVIEW_CREATED,
    EXPENSE_CREATED,
    SIDE_EFFECTS_COMPLETED,
    DELETED
}
