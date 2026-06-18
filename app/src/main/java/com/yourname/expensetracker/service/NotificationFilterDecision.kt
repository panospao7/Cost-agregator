package com.yourname.expensetracker.service

/**
 * P2-09: Structured filter decision model for notification capture filtering.
 * Currently the filter returns Boolean via shouldCapture(). This model enables
 * future diagnostics and structured reason tracking.
 */
data class NotificationFilterDecision(
    val capture: Boolean,
    val reason: NotificationFilterReason,
    val confidence: Float,
    val direction: TransactionDirection,
    val hasMoneySignal: Boolean
)

enum class NotificationFilterReason {
    ALLOW_STRONG_EXPENSE,
    ALLOW_OUTGOING_TRANSFER,
    ALLOW_REVIEWABLE_FINANCIAL_SIGNAL,
    BALANCE_ONLY,
    ACCOUNT_INFO_ONLY,
    CURRENCY_ONLY,
    SECURITY_OR_AUTH,
    PROMOTION,
    INCOMING_ONLY,
    PAYMENT_FAILED_OR_DECLINED,
    NO_AMOUNT,
    NO_TRANSACTION_SIGNAL,
    IGNORED_PACKAGE
}

enum class TransactionDirection {
    DEBIT,
    CREDIT,
    TRANSFER_OUT,
    TRANSFER_IN,
    UNKNOWN
}
