package com.yourname.expensetracker.data.ai.provider

import android.content.Context
import com.yourname.expensetracker.data.ai.provider.internal.CloudPiiSanitizer
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.model.DashboardBudgetWarningInput
import com.yourname.expensetracker.domain.ai.model.DashboardUpcomingItemInput
import com.yourname.expensetracker.domain.ai.model.TransactionInsightAmountBucket
import com.yourname.expensetracker.domain.ai.model.TransactionInsightPromptInput
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.components.asString
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds prompts for the AI dashboard briefing feature.
 *
 * ## M6: AI prompt schema lacks minAmount/maxAmount
 * The JSON schema in the prompt (line ~74) includes only `title`, `text`,
 * `tone`, and `confidence`. There is no mechanism to request filtering by
 * minimum or maximum transaction amount — the AI cannot be asked "show me
 * transactions over $500" via the briefing prompt. A future enhancement
 * should add optional `minAmount`/`maxAmount` fields to the prompt schema
 * and pass them through from the [DashboardBriefingInput].
 */
@Singleton
class DashboardBriefingPromptFormatter private constructor(
    private val textResolver: (UiText) -> String
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(textResolver = { text -> text.asString(context) })

    constructor() : this(textResolver = ::fallbackResolve)

    private val dateKeyFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun buildPrompt(input: DashboardBriefingInput, shouldRedact: Boolean = false): String {
        input.transactionInsight?.let { return buildTransactionInsightPrompt(input, it, shouldRedact) }

        return buildString {
            appendLine("Write a short daily finance dashboard briefing.")
            appendLine("Be concise, practical, and advisory only.")
            appendLine("Do not invent facts. Return ONLY one JSON object.")
            appendLine()
            appendLine("Date: ${input.dateKey}")
            appendLine("Weather headline: ${resolve(input.weatherHeadline)}")
            appendLine("Weather summary: ${resolve(input.weatherSummary)}")
            appendLine("Discretionary budget: ${input.discretionaryBudget}")
            appendLine("Total committed: ${input.totalCommitted}")
            appendLine("Total likely: ${input.totalLikely}")
            appendLine("Pending reviews: ${input.pendingReviewCount}")
            appendLine("Current month spent: ${input.currentMonthSpent}")
            // PRIVACY FIX: Redact top categories names when redaction is enabled
            val safeTopCategories = if (shouldRedact) {
                input.topCategories.take(3).map { CloudPiiSanitizer.sanitizeMerchant(it, shouldRedact = true) }
                    .joinToString(", ").ifBlank { "none" }
            } else {
                input.topCategories.joinToString(", ").ifBlank { "none" }
            }
            appendLine("Top categories: $safeTopCategories")
            appendLine("Budget warnings: ${input.budgetWarnings.joinToString(", ") { formatBudgetWarning(it) }.ifBlank { "none" }}")
            // PRIVACY FIX: Redact upcoming item descriptions when redaction is enabled
            val safeUpcomingItems = if (shouldRedact) {
                input.upcomingItems.take(3).map { item ->
                    val dateLabel = Instant.ofEpochMilli(item.dateMillis)
                        .atZone(ZoneId.systemDefault())
                        .format(dateKeyFormat)
                    val amountLabel = item.currencyCode?.takeIf { it.isNotBlank() }?.let { currencyCode ->
                        CurrencyFormatter.format(item.amount, currencyCode = currencyCode, showCents = false)
                    } ?: String.format(Locale.getDefault(), "%.0f", item.amount)
                    "${CloudPiiSanitizer.sanitizeMerchant(item.description, shouldRedact = true)} $amountLabel on $dateLabel"
                }.joinToString(", ").ifBlank { "none" }
            } else {
                input.upcomingItems.joinToString(", ") { formatUpcomingItem(it) }.ifBlank { "none" }
            }
            appendLine("Upcoming items: $safeUpcomingItems")
            appendLine()
            appendLine("JSON schema: {\"title\":\"short title\",\"text\":\"brief message\",\"tone\":\"calm|neutral|cautious\",\"confidence\":0.0}")
        }
    }

    private fun buildTransactionInsightPrompt(
        input: DashboardBriefingInput,
        insight: TransactionInsightPromptInput,
        shouldRedactFromSettings: Boolean = false
    ): String {
        // Redact if either the per-call flag or the global settings say so
        val shouldRedact = insight.redactForPrompt || shouldRedactFromSettings
        val merchantLabel = CloudPiiSanitizer.sanitizeMerchant(
            raw = insight.merchantName,
            shouldRedact = insight.redactForPrompt
        )
        val amountLabel = if (insight.redactForPrompt) {
            formatAmountBucket(insight.amountBucket, insight.currencyCode)
        } else {
            formatExactAmount(insight.promptAmount, insight.currencyCode)
        }
        val headline = if (insight.redactForPrompt) {
            "New ${bucketHeadlineDescriptor(insight.amountBucket)} transaction recorded"
        } else {
            "New transaction recorded"
        }
        val summary = "$merchantLabel - $amountLabel"

        return buildString {
            appendLine("Write a short finance insight about a single recorded transaction.")
            appendLine("Be concise, practical, and advisory only.")
            appendLine("Do not invent facts. Return ONLY one JSON object.")
            appendLine()
            appendLine("Date: ${input.dateKey}")
            appendLine("Transaction headline: $headline")
            appendLine("Transaction summary: $summary")
            appendLine("Transaction amount signal: ${insight.promptAmount}")
            appendLine("High value transaction: ${if (insight.isHighValue) "yes" else "no"}")
            if (insight.redactForPrompt) {
                appendLine("Privacy mode: merchant and exact amount were redacted before prompt assembly.")
            }
            appendLine()
            appendLine("JSON schema: {\"title\":\"short title\",\"text\":\"brief message\",\"tone\":\"calm|neutral|cautious\",\"confidence\":0.0}")
        }
    }

    private fun resolve(text: UiText): String = textResolver(text)

    private fun formatExactAmount(amount: Double, currencyCode: String): String {
        return String.format(Locale.getDefault(), "%.2f %s", amount, currencyCode)
    }

    private fun formatBudgetWarning(warning: DashboardBudgetWarningInput): String {
        return "${resolve(warning.categoryLabel)} at ${warning.percentUsed}%"
    }

    private fun formatUpcomingItem(item: DashboardUpcomingItemInput): String {
        val dateLabel = Instant.ofEpochMilli(item.dateMillis)
            .atZone(ZoneId.systemDefault())
            .format(dateKeyFormat)
        val amountLabel = item.currencyCode?.takeIf { it.isNotBlank() }?.let { currencyCode ->
            CurrencyFormatter.format(item.amount, currencyCode = currencyCode, showCents = false)
        } ?: String.format(Locale.getDefault(), "%.0f", item.amount)

        return "${item.description} $amountLabel on $dateLabel"
    }

    private fun formatAmountBucket(bucket: TransactionInsightAmountBucket, currencyCode: String): String = when (bucket) {
        TransactionInsightAmountBucket.UNDER_20 -> "under 20 $currencyCode"
        TransactionInsightAmountBucket.RANGE_20_49 -> "20-49 $currencyCode"
        TransactionInsightAmountBucket.RANGE_50_99 -> "50-99 $currencyCode"
        TransactionInsightAmountBucket.RANGE_100_249 -> "100-249 $currencyCode"
        TransactionInsightAmountBucket.RANGE_250_499 -> "250-499 $currencyCode"
        TransactionInsightAmountBucket.RANGE_500_999 -> "500-999 $currencyCode"
        TransactionInsightAmountBucket.RANGE_1000_PLUS -> "1000+ $currencyCode"
    }

    private fun bucketHeadlineDescriptor(bucket: TransactionInsightAmountBucket): String = when (bucket) {
        TransactionInsightAmountBucket.UNDER_20 -> "small"
        TransactionInsightAmountBucket.RANGE_20_49 -> "everyday"
        TransactionInsightAmountBucket.RANGE_50_99 -> "moderate"
        TransactionInsightAmountBucket.RANGE_100_249 -> "mid-range"
        TransactionInsightAmountBucket.RANGE_250_499 -> "larger"
        TransactionInsightAmountBucket.RANGE_500_999 -> "large"
        TransactionInsightAmountBucket.RANGE_1000_PLUS -> "very large"
    }

    private companion object {
        private fun fallbackResolve(text: UiText): String = when (text) {
            is UiText.DynamicString -> text.value
            is UiText.MessageKey -> if (text.args.isEmpty()) text.key else "${text.key} ${text.args.joinToString(", ")}"
            is UiText.StringResource -> text.resId.toString()
            is UiText.PluralResource -> text.resId.toString()
        }
    }
}
