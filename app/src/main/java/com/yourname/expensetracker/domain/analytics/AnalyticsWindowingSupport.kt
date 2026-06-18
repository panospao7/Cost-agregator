package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator

private const val UNKNOWN_MERCHANT_KEY = "__unknown_merchant__"

internal fun ExpenseSnapshot.canonicalMerchantKey(): String {
    return merchantKey
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: MerchantKeyGenerator.generate(merchant).takeIf { it.isNotBlank() }
        ?: merchant.lowercase().trim().takeIf { it.isNotEmpty() }
        ?: UNKNOWN_MERCHANT_KEY
}

internal fun resolveMerchantDisplayName(expenses: List<ExpenseSnapshot>): String {
    if (expenses.isEmpty()) return ""

    return expenses.asSequence()
        .map { it.merchant.trim() }
        .filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()
        .maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key.length }
        )
        ?.key
        ?: expenses.first().merchant
}
