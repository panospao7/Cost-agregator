package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.ai.provider.internal.CloudPiiSanitizer
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlin.math.absoluteValue

class TransactionInsightInputBuilder @Inject constructor() {

    fun build(transaction: Expense, shouldRedact: Boolean): DashboardBriefingInput {
        val amountProfile = amountProfile(
            amount = transaction.amount,
            currency = transaction.currency,
            shouldRedact = shouldRedact
        )
        val merchantLabel = sanitizeMerchant(transaction.merchant, shouldRedact)

        return DashboardBriefingInput(
            dateKey = Instant.ofEpochMilli(transaction.date)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString(),
            weatherHeadline = if (shouldRedact) {
                "New ${amountProfile.headlineDescriptor} transaction recorded"
            } else {
                "New transaction recorded"
            },
            weatherSummary = "$merchantLabel - ${amountProfile.displayLabel}",
            discretionaryBudget = 0.0,
            totalCommitted = amountProfile.numericValue,
            totalLikely = 0.0,
            pendingReviewCount = 0,
            currentMonthSpent = amountProfile.numericValue,
            topCategories = emptyList(),
            budgetWarnings = buildBudgetWarnings(transaction, amountProfile, shouldRedact),
            upcomingItems = listOf(
                if (shouldRedact) {
                    "Transaction from: $merchantLabel (${amountProfile.displayLabel})"
                } else {
                    "Transaction from: $merchantLabel"
                }
            )
        )
    }

    private fun buildBudgetWarnings(
        transaction: Expense,
        amountProfile: AmountProfile,
        shouldRedact: Boolean
    ): List<String> {
        if (!amountProfile.isHighValue) return emptyList()

        return listOf(
            if (shouldRedact) {
                "Higher-value transaction bucket: ${amountProfile.displayLabel}"
            } else {
                "High-value transaction: ${formatExactAmount(transaction.amount, transaction.currency)}"
            }
        )
    }

    private fun sanitizeMerchant(rawMerchant: String, shouldRedact: Boolean): String {
        val trimmed = rawMerchant.trim().take(80).ifBlank { "Unknown" }
        return if (shouldRedact) {
            CloudPiiSanitizer.sanitizeMerchant(trimmed, shouldRedact = true)
        } else {
            trimmed
        }
    }

    private fun amountProfile(amount: Double, currency: String, shouldRedact: Boolean): AmountProfile {
        if (!shouldRedact) {
            return AmountProfile(
                numericValue = amount,
                displayLabel = formatExactAmount(amount, currency),
                headlineDescriptor = "transaction",
                isHighValue = amount.absoluteValue > HIGH_VALUE_THRESHOLD
            )
        }

        val absAmount = amount.absoluteValue
        val currencyLabel = currency.trim().take(8).ifBlank { "CUR" }
        val (headlineDescriptor, displayLabel, representativeAmount) = when {
            absAmount < 20.0 -> Triple("small", "under 20 $currencyLabel", 10.0)
            absAmount < 50.0 -> Triple("everyday", "20-49 $currencyLabel", 35.0)
            absAmount < 100.0 -> Triple("moderate", "50-99 $currencyLabel", 75.0)
            absAmount < 250.0 -> Triple("mid-range", "100-249 $currencyLabel", 175.0)
            absAmount < 500.0 -> Triple("larger", "250-499 $currencyLabel", 375.0)
            absAmount < 1000.0 -> Triple("large", "500-999 $currencyLabel", 750.0)
            else -> Triple("very large", "1000+ $currencyLabel", 1000.0)
        }

        return AmountProfile(
            numericValue = if (amount < 0) -representativeAmount else representativeAmount,
            displayLabel = displayLabel,
            headlineDescriptor = headlineDescriptor,
            isHighValue = absAmount > HIGH_VALUE_THRESHOLD
        )
    }

    private fun formatExactAmount(amount: Double, currency: String): String {
        val currencyLabel = currency.trim().take(8).ifBlank { "CUR" }
        return String.format(Locale.US, "%.2f %s", amount, currencyLabel)
    }

    private data class AmountProfile(
        val numericValue: Double,
        val displayLabel: String,
        val headlineDescriptor: String,
        val isHighValue: Boolean
    )

    private companion object {
        const val HIGH_VALUE_THRESHOLD = 100.0
    }
}
