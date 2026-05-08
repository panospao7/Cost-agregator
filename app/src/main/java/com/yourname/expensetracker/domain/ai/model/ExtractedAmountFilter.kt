package com.yourname.expensetracker.domain.ai.model

data class ExtractedAmountFilter(
    val amount: Double,
    val currency: String? = null,
    val operator: AmountOperator = AmountOperator.GREATER_THAN
)

enum class AmountOperator { GREATER_THAN, LESS_THAN, EQUALS }
