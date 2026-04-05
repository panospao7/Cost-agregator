package com.yourname.expensetracker.domain.parser

enum class ParsedTransactionType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT,
    UNKNOWN
}

enum class ParsedTransferDirection {
    INCOMING,
    OUTGOING
}
