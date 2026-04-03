package com.yourname.expensetracker.ui.mappers

import com.yourname.expensetracker.domain.model.navigation.DomainTransactionFilter
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter

fun DomainTransactionFilter.toUi(): TransactionFilter = TransactionFilter(
    categoryId = categoryId,
    merchantName = merchantName,
    transactionType = transactionType,
    dateRange = dateRange,
    ownership = ownership,
    minAmount = minAmount,
    maxAmount = maxAmount,
    correlationId = correlationId
)

fun TransactionFilter.toDomain(): DomainTransactionFilter = DomainTransactionFilter(
    categoryId = categoryId,
    merchantName = merchantName,
    transactionType = transactionType,
    dateRange = dateRange,
    ownership = ownership,
    minAmount = minAmount,
    maxAmount = maxAmount,
    correlationId = correlationId
)
