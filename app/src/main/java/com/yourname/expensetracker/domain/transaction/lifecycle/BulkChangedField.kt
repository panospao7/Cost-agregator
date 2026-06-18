package com.yourname.expensetracker.domain.transaction.lifecycle

enum class BulkChangedField {
    AMOUNT,
    AMOUNT_EFFECTIVE,
    CATEGORY,
    MERCHANT,
    MERCHANT_KEY,
    TRANSACTION_TYPE,
    DATE,
    CURRENCY,
    OWNERSHIP,
    TRANSFER,
    LOCATION,
    BUSINESS_FLAGS,
    UNKNOWN
}

fun Set<BulkChangedField>.affectsBudget(): Boolean =
    isEmpty() ||
    any {
        it in setOf(
            BulkChangedField.AMOUNT,
            BulkChangedField.AMOUNT_EFFECTIVE,
            BulkChangedField.CATEGORY,
            BulkChangedField.TRANSACTION_TYPE,
            BulkChangedField.DATE,
            BulkChangedField.CURRENCY,
            BulkChangedField.OWNERSHIP,
            BulkChangedField.TRANSFER,
            BulkChangedField.UNKNOWN
        )
    }

fun Set<BulkChangedField>.affectsAnomaly(): Boolean =
    isEmpty() ||
    any {
        it in setOf(
            BulkChangedField.AMOUNT,
            BulkChangedField.AMOUNT_EFFECTIVE,
            BulkChangedField.CATEGORY,
            BulkChangedField.MERCHANT,
            BulkChangedField.MERCHANT_KEY,
            BulkChangedField.TRANSACTION_TYPE,
            BulkChangedField.DATE,
            BulkChangedField.CURRENCY,
            BulkChangedField.OWNERSHIP,
            BulkChangedField.UNKNOWN
        )
    }

fun Set<BulkChangedField>.affectsMerchantLearning(): Boolean =
    any {
        it in setOf(
            BulkChangedField.CATEGORY,
            BulkChangedField.MERCHANT,
            BulkChangedField.MERCHANT_KEY,
            BulkChangedField.UNKNOWN
        )
    }

fun Set<BulkChangedField>.affectsRecurring(): Boolean =
    any {
        it in setOf(
            BulkChangedField.AMOUNT,
            BulkChangedField.MERCHANT,
            BulkChangedField.MERCHANT_KEY,
            BulkChangedField.TRANSACTION_TYPE,
            BulkChangedField.DATE,
            BulkChangedField.CURRENCY,
            BulkChangedField.UNKNOWN
        )
    }

fun Set<BulkChangedField>.affectsAnalyticsCache(): Boolean =
    isEmpty() || any { it != BulkChangedField.LOCATION }
