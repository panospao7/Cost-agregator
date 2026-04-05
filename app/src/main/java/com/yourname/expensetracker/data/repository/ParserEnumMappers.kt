package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection

internal fun ParsedTransactionType.toDbTransactionType(): TransactionType {
    return when (this) {
        ParsedTransactionType.PURCHASE -> TransactionType.PURCHASE
        ParsedTransactionType.WITHDRAWAL -> TransactionType.WITHDRAWAL
        ParsedTransactionType.TRANSFER -> TransactionType.TRANSFER
        ParsedTransactionType.DEPOSIT -> TransactionType.DEPOSIT
        ParsedTransactionType.UNKNOWN -> TransactionType.UNKNOWN
    }
}

internal fun ParsedTransferDirection.toDbTransferDirection(): TransferDirection {
    return when (this) {
        ParsedTransferDirection.INCOMING -> TransferDirection.INCOMING
        ParsedTransferDirection.OUTGOING -> TransferDirection.OUTGOING
    }
}
