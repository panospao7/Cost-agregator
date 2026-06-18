package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.model.TransactionInsightAmountBucket
import com.yourname.expensetracker.domain.ai.model.TransactionInsightPromptInput
import com.yourname.expensetracker.domain.model.UiText
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.absoluteValue

class TransactionInsightInputBuilder @Inject constructor() {

    fun build(transaction: Expense, shouldRedact: Boolean): DashboardBriefingInput {
        val amountProfile = amountProfile(
            amount = transaction.amount,
            shouldRedact = shouldRedact
        )
        val merchantName = normalizeMerchantFact(transaction.merchant)
        val currencyCode = normalizeCurrencyCode(transaction.currency)

        return DashboardBriefingInput(
            dateKey = Instant.ofEpochMilli(transaction.date)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString(),
            weatherHeadline = UiText.from(""),
            weatherSummary = UiText.from(""),
            discretionaryBudget = 0.0,
            totalCommitted = amountProfile.promptAmount,
            totalLikely = 0.0,
            pendingReviewCount = 0,
            currentMonthSpent = amountProfile.promptAmount,
            topCategories = emptyList(),
            budgetWarnings = emptyList(),
            upcomingItems = emptyList(),
            transactionInsight = TransactionInsightPromptInput(
                merchantName = merchantName,
                promptAmount = amountProfile.promptAmount,
                currencyCode = currencyCode,
                redactForPrompt = shouldRedact,
                amountBucket = amountProfile.amountBucket,
                isHighValue = amountProfile.isHighValue
            )
        )
    }

    private fun normalizeMerchantFact(rawMerchant: String): String =
        rawMerchant.trim().take(80).ifBlank { "Unknown" }

    private fun normalizeCurrencyCode(currency: String): String =
        currency.trim().take(8).ifBlank { "CUR" }

    private fun amountProfile(amount: Double, shouldRedact: Boolean): AmountProfile {
        val bucket = amountBucket(amount)
        if (!shouldRedact) {
            return AmountProfile(
                promptAmount = amount,
                amountBucket = bucket,
                isHighValue = amount.absoluteValue > HIGH_VALUE_THRESHOLD
            )
        }

        val absAmount = amount.absoluteValue
        val representativeAmount = when (bucket) {
            TransactionInsightAmountBucket.UNDER_20 -> 10.0
            TransactionInsightAmountBucket.RANGE_20_49 -> 35.0
            TransactionInsightAmountBucket.RANGE_50_99 -> 75.0
            TransactionInsightAmountBucket.RANGE_100_249 -> 175.0
            TransactionInsightAmountBucket.RANGE_250_499 -> 375.0
            TransactionInsightAmountBucket.RANGE_500_999 -> 750.0
            TransactionInsightAmountBucket.RANGE_1000_PLUS -> 1000.0
        }

        return AmountProfile(
            promptAmount = if (amount < 0) -representativeAmount else representativeAmount,
            amountBucket = bucket,
            isHighValue = absAmount > HIGH_VALUE_THRESHOLD
        )
    }

    private fun amountBucket(amount: Double): TransactionInsightAmountBucket = when {
        amount.absoluteValue < 20.0 -> TransactionInsightAmountBucket.UNDER_20
        amount.absoluteValue < 50.0 -> TransactionInsightAmountBucket.RANGE_20_49
        amount.absoluteValue < 100.0 -> TransactionInsightAmountBucket.RANGE_50_99
        amount.absoluteValue < 250.0 -> TransactionInsightAmountBucket.RANGE_100_249
        amount.absoluteValue < 500.0 -> TransactionInsightAmountBucket.RANGE_250_499
        amount.absoluteValue < 1000.0 -> TransactionInsightAmountBucket.RANGE_500_999
        else -> TransactionInsightAmountBucket.RANGE_1000_PLUS
    }

    private data class AmountProfile(
        val promptAmount: Double,
        val amountBucket: TransactionInsightAmountBucket,
        val isHighValue: Boolean
    )

    private companion object {
        const val HIGH_VALUE_THRESHOLD = 100.0
    }
}
