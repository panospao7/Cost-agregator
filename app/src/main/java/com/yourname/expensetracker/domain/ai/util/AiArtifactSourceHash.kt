package com.yourname.expensetracker.domain.ai.util

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.ai.model.TransactionInsightPromptInput
import com.yourname.expensetracker.domain.model.UiText
import java.security.MessageDigest
import java.util.Locale

object AiArtifactSourceHash {

    fun forDedupeJudge(input: DedupeJudgeInput): String {
        val builder = CanonicalHashBuilder()
            .put("subject.targetType", input.subject.targetType.name)
            .put("subject.targetId", input.subject.targetId)
            .put("subject.merchant", input.subject.merchant)
            .put("subject.amount", input.subject.amount)
            .put("subject.currency", input.subject.currency)
            .put("subject.date", input.subject.date)
            .put("subject.sourceLabel", input.subject.sourceLabel)
            .put("subject.textPreview", input.subject.textPreview)
            .put("subject.transactionType", input.subject.transactionType)
            .put("candidates.size", input.candidates.size)

        input.candidates.forEachIndexed { index, candidate ->
            builder
                .put("candidate.$index.targetType", candidate.targetType.name)
                .put("candidate.$index.targetId", candidate.targetId)
                .put("candidate.$index.merchant", candidate.merchant)
                .put("candidate.$index.amount", candidate.amount)
                .put("candidate.$index.currency", candidate.currency)
                .put("candidate.$index.date", candidate.date)
                .put("candidate.$index.sourceLabel", candidate.sourceLabel)
                .put("candidate.$index.textPreview", candidate.textPreview)
                .put("candidate.$index.transactionType", candidate.transactionType)
        }

        return builder.sha256()
    }

    fun forReviewExplanation(input: ReviewExplanationInput): String {
        return CanonicalHashBuilder()
            .put("reviewId", input.reviewId)
            .put("merchant", input.merchant)
            .put("amount", input.amount)
            .put("currency", input.currency)
            .put("suggestedType", input.suggestedType)
            .put("suggestedCategoryId", input.suggestedCategoryId)
            .put("confidence", input.confidence)
            .put("matchType", input.matchType)
            .put("explanation", input.explanation)
            .put("packageName", input.packageName)
            .put("notificationTitle", input.notificationTitle)
            .put("notificationText", input.notificationText)
            .sha256()
    }

    fun forDashboardBriefing(input: DashboardBriefingInput): String {
        val builder = CanonicalHashBuilder()
            .put("dateKey", input.dateKey)
            .putUiText("weatherHeadline", input.weatherHeadline)
            .putUiText("weatherSummary", input.weatherSummary)
            .put("discretionaryBudget", input.discretionaryBudget)
            .put("totalCommitted", input.totalCommitted)
            .put("totalLikely", input.totalLikely)
            .put("pendingReviewCount", input.pendingReviewCount)
            .put("currentMonthSpent", input.currentMonthSpent)
            .put("topCategories.size", input.topCategories.size)

        input.topCategories.forEachIndexed { index, category ->
            builder.put("topCategories.$index", category)
        }

        builder.put("budgetWarnings.size", input.budgetWarnings.size)
        input.budgetWarnings.forEachIndexed { index, warning ->
            builder
                .putUiText("budgetWarnings.$index.categoryLabel", warning.categoryLabel)
                .put("budgetWarnings.$index.percentUsed", warning.percentUsed)
        }

        builder.put("upcomingItems.size", input.upcomingItems.size)
        input.upcomingItems.forEachIndexed { index, item ->
            builder
                .put("upcomingItems.$index.description", item.description)
                .put("upcomingItems.$index.amount", item.amount)
                .put("upcomingItems.$index.dateMillis", item.dateMillis)
                .put("upcomingItems.$index.currencyCode", item.currencyCode)
        }

        builder.putTransactionInsight("transactionInsight", input.transactionInsight)
        return builder.sha256()
    }

    fun forTransactionInsight(transaction: Expense): String {
        return CanonicalHashBuilder()
            .put("id", transaction.id)
            .put("amount", transaction.amount)
            .put("currency", transaction.currency)
            .put("merchant", transaction.merchant)
            .put("transactionType", transaction.transactionType.name)
            .put("date", transaction.date)
            .put("categoryId", transaction.categoryId)
            .put("notes", transaction.notes)
            .sha256()
    }

    fun forReceiptItemCategorization(input: ReceiptItemCategorizationInput): String {
        val builder = CanonicalHashBuilder()
            .put("receiptId", input.receiptId)
            .put("merchant", input.merchant)
            .put("totalTax", input.totalTax)
            .put("currency", input.currency)
            .put("redactBeforeCloud", input.redactBeforeCloud)
            .put("lineItems.size", input.lineItems.size)

        input.lineItems.forEachIndexed { index, item ->
            builder
                .put("lineItems.$index.description", item.description)
                .put("lineItems.$index.quantity", item.quantity)
                .put("lineItems.$index.unitPrice", item.unitPrice)
                .put("lineItems.$index.totalPrice", item.totalPrice)
        }

        builder.put("userCategories.size", input.userCategories.size)
        input.userCategories.forEachIndexed { index, category ->
            builder
                .put("userCategories.$index.id", category.id)
                .put("userCategories.$index.name", category.name)
        }

        builder.put("cloudCategoryOptions.size", input.cloudCategoryOptions.size)
        input.cloudCategoryOptions.forEachIndexed { index, option ->
            builder
                .put("cloudCategoryOptions.$index.categoryId", option.categoryId)
                .put("cloudCategoryOptions.$index.cloudName", option.cloudName)
        }

        return builder.sha256()
    }

    fun forReviewCategorizationFallback(input: CategorizationAssistInput): String {
        val builder = CanonicalHashBuilder()
            .put("targetType", input.targetType.name)
            .put("targetId", input.targetId)
            .put("merchant", input.merchant)
            .put("amount", input.amount)
            .put("currency", input.currency)
            .put("transactionType", input.transactionType.name)
            .put("date", input.date)
            .put("currentCategoryId", input.currentCategoryId)
            .put("deterministicMatchType", input.deterministicMatchType)
            .put("deterministicExplanation", input.deterministicExplanation)
            .put("supportingText", input.supportingText)
            .put("candidateCategories.size", input.candidateCategories.size)

        input.candidateCategories.forEachIndexed { index, category ->
            builder
                .put("candidateCategories.$index.id", category.id)
                .put("candidateCategories.$index.name", category.name)
                .put("candidateCategories.$index.cloudLabel", category.cloudLabel)
        }

        builder.put("recentTransactions.size", input.recentTransactionsWithSameMerchant.size)
        input.recentTransactionsWithSameMerchant.forEachIndexed { index, hint ->
            builder
                .put("recentTransactions.$index.merchant", hint.merchant)
                .put("recentTransactions.$index.categoryName", hint.categoryName)
                .put("recentTransactions.$index.cloudMerchant", hint.cloudMerchant)
                .put("recentTransactions.$index.cloudCategoryName", hint.cloudCategoryName)
        }
        return builder.sha256()
    }

    private class CanonicalHashBuilder {
        private val parts = mutableListOf<String>()

        fun put(key: String, value: String?): CanonicalHashBuilder = apply {
            parts += "$key=${value?.trim() ?: NULL_MARKER}"
        }

        fun put(key: String, value: Long?): CanonicalHashBuilder = apply {
            parts += "$key=${value?.toString() ?: NULL_MARKER}"
        }

        fun put(key: String, value: Int?): CanonicalHashBuilder = apply {
            parts += "$key=${value?.toString() ?: NULL_MARKER}"
        }

        fun put(key: String, value: Boolean?): CanonicalHashBuilder = apply {
            parts += "$key=${value?.toString() ?: NULL_MARKER}"
        }

        fun put(key: String, value: Double?): CanonicalHashBuilder = apply {
            parts += "$key=${value?.let(::formatDouble) ?: NULL_MARKER}"
        }

        fun put(key: String, value: Float?): CanonicalHashBuilder = apply {
            parts += "$key=${value?.let(::formatFloat) ?: NULL_MARKER}"
        }

        fun putUiText(key: String, value: UiText?): CanonicalHashBuilder = apply {
            when (value) {
                null -> put(key, null as String?)
                is UiText.DynamicString -> {
                    put("$key.type", "dynamic")
                    put("$key.value", value.value)
                }
                is UiText.MessageKey -> {
                    put("$key.type", "message_key")
                    put("$key.key", value.key)
                    put("$key.args.size", value.args.size)
                    value.args.forEachIndexed { index, arg ->
                        put("$key.args.$index", arg?.toString())
                    }
                }
                is UiText.StringResource -> {
                    put("$key.type", "string_res")
                    put("$key.resId", value.resId)
                    put("$key.args.size", value.args.size)
                    value.args.forEachIndexed { index, arg ->
                        put("$key.args.$index", arg?.toString())
                    }
                }
                is UiText.PluralResource -> {
                    put("$key.type", "plural_res")
                    put("$key.resId", value.resId)
                    put("$key.quantity", value.quantity)
                    put("$key.args.size", value.args.size)
                    value.args.forEachIndexed { index, arg ->
                        put("$key.args.$index", arg?.toString())
                    }
                }
            }
        }

        fun putTransactionInsight(key: String, value: TransactionInsightPromptInput?): CanonicalHashBuilder = apply {
            if (value == null) {
                put(key, null as String?)
                return@apply
            }
            put("$key.merchantName", value.merchantName)
            put("$key.promptAmount", value.promptAmount)
            put("$key.currencyCode", value.currencyCode)
            put("$key.redactForPrompt", value.redactForPrompt)
            put("$key.amountBucket", value.amountBucket.name)
            put("$key.isHighValue", value.isHighValue)
        }

        fun sha256(): String {
            val canonical = parts.joinToString(separator = FIELD_SEPARATOR)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { byte ->
                String.format(Locale.ROOT, "%02x", byte)
            }
        }

        private fun formatDouble(value: Double): String =
            if (value.isFinite()) {
                String.format(Locale.ROOT, "%.12f", value)
            } else {
                value.toString()
            }

        private fun formatFloat(value: Float): String =
            if (value.isFinite()) {
                String.format(Locale.ROOT, "%.9f", value)
            } else {
                value.toString()
            }
    }

    private const val NULL_MARKER = "<null>"
    private const val FIELD_SEPARATOR = "\u001F"
}
