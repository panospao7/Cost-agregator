package com.yourname.expensetracker.ui.mappers

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.OwnershipFilter
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.navigation.DomainOwnershipFilter
import com.yourname.expensetracker.domain.model.navigation.DomainTransactionFilter
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter

fun DomainTransactionFilter.toUi(): TransactionFilter = TransactionFilter(
    categoryId = categoryId,
    merchantName = merchantName,
    transactionType = transactionType?.toEntity(),
    dateRange = dateRange,
    ownership = ownership?.toEntity(),
    minAmount = minAmount,
    maxAmount = maxAmount,
    correlationId = correlationId
)

fun TransactionFilter.toDomain(): DomainTransactionFilter = DomainTransactionFilter(
    categoryId = categoryId,
    merchantName = merchantName,
    transactionType = transactionType?.toDomain(),
    dateRange = dateRange,
    ownership = ownership?.toDomain(),
    minAmount = minAmount,
    maxAmount = maxAmount,
    correlationId = correlationId
)

// --- Boundary mappers: domain enum <-> data-layer enum ---

private fun DomainTransactionType.toEntity(): TransactionType = when (this) {
    DomainTransactionType.PURCHASE -> TransactionType.PURCHASE
    DomainTransactionType.WITHDRAWAL -> TransactionType.WITHDRAWAL
    DomainTransactionType.TRANSFER -> TransactionType.TRANSFER
    DomainTransactionType.DEPOSIT -> TransactionType.DEPOSIT
    DomainTransactionType.UNKNOWN -> TransactionType.UNKNOWN
}

private fun TransactionType.toDomain(): DomainTransactionType = when (this) {
    TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
    TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
    TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
    TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
    TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
}

private fun DomainOwnershipFilter.toEntity(): OwnershipFilter = when (this) {
    DomainOwnershipFilter.ALL -> OwnershipFilter.ALL
    DomainOwnershipFilter.MINE -> OwnershipFilter.MINE
    DomainOwnershipFilter.NOT_MINE -> OwnershipFilter.NOT_MINE
    DomainOwnershipFilter.SHARED -> OwnershipFilter.SHARED
    DomainOwnershipFilter.TRANSFER -> OwnershipFilter.TRANSFER
}

private fun OwnershipFilter.toDomain(): DomainOwnershipFilter = when (this) {
    OwnershipFilter.ALL -> DomainOwnershipFilter.ALL
    OwnershipFilter.MINE -> DomainOwnershipFilter.MINE
    OwnershipFilter.NOT_MINE -> DomainOwnershipFilter.NOT_MINE
    OwnershipFilter.SHARED -> DomainOwnershipFilter.SHARED
    OwnershipFilter.TRANSFER -> DomainOwnershipFilter.TRANSFER
}
