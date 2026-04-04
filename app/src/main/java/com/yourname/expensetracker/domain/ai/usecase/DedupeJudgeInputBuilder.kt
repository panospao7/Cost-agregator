package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.DedupeCandidateSummary
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeBuildResult
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.math.abs

class DedupeJudgeInputBuilder @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val reviewQueueRepository: ReviewQueueRepository,
    private val aiPolicy: AiPolicy
) {

    suspend fun build(item: PendingReviewWithReceipt, settings: AiSettings): DedupeJudgeBuildResult {
        val shouldRedact =
            aiPolicy.canUseCloudFor(settings, AiCapability.DEDUPE_JUDGE) &&
                aiPolicy.shouldRedact(settings, AiCapability.DEDUPE_JUDGE)
        val review = item.review
        val reviewDate = review.suggestedDate
            ?: return DedupeJudgeBuildResult.NotNeeded(
                "No review date is available for duplicate comparison."
            )

        val startDate = reviewDate - DEDUPE_WINDOW_MS
        val endDate = reviewDate + DEDUPE_WINDOW_MS
        val merchantKey = MerchantKeyGenerator.generate(review.suggestedMerchant)
        val expenses = expenseRepository.getExpensesBetween(startDate, endDate)
            .filter { expense ->
                expense.merchantKey == merchantKey && abs(expense.amount - review.suggestedAmount) <= 3.0
            }
            .sortedBy { expense -> abs(expense.date - reviewDate) + (abs(expense.amount - review.suggestedAmount) * 1000).toLong() }
            .take(AppConfig.Ai.MAX_DEDUPE_CANDIDATES_FOR_AI)

        val pendingReviewCandidates = reviewQueueRepository.getPendingReviewsByMerchant(review.suggestedMerchant)
            .filter { candidate ->
                candidate.id != review.id &&
                    candidate.suggestedDate != null &&
                    abs(candidate.suggestedDate - reviewDate) <= DEDUPE_WINDOW_MS &&
                    abs(candidate.suggestedAmount - review.suggestedAmount) <= 3.0
            }
            .sortedBy { candidate ->
                abs((candidate.suggestedDate ?: reviewDate) - reviewDate) +
                    (abs(candidate.suggestedAmount - review.suggestedAmount) * 1000).toLong()
            }

        if (expenses.isEmpty() && pendingReviewCandidates.isEmpty()) {
            return DedupeJudgeBuildResult.NotNeeded(
                "No nearby deterministic duplicate candidates were found."
            )
        }

        val subject = DedupeCandidateSummary(
            targetType = AiTargetType.PENDING_REVIEW,
            targetId = review.id,
            merchant = sanitizeLabel(review.suggestedMerchant, shouldRedact, "merchant"),
            amount = review.suggestedAmount,
            currency = review.suggestedCurrency,
            date = reviewDate,
            sourceLabel = sanitizeLabel(review.packageName, shouldRedact, "source"),
            textPreview = sanitizePreview(
                review.notificationText,
                shouldRedact,
                AppConfig.Ai.MAX_CAPTURE_SUPPORTING_TEXT_CHARS
            )
        )

        val candidates = buildList {
            expenses.forEach { expense ->
                add(
                    DedupeCandidateSummary(
                        targetType = AiTargetType.EXPENSE,
                        targetId = expense.id,
                        merchant = sanitizeLabel(expense.merchant, shouldRedact, "merchant"),
                        amount = expense.amount,
                        currency = expense.currency,
                        date = expense.date,
                        sourceLabel = sanitizeLabel(
                            if (expense.rawNotificationId != null) "notification" else "expense",
                            shouldRedact,
                            "source"
                        ),
                        textPreview = sanitizePreview(expense.notes, shouldRedact, 120)
                    )
                )
            }
            pendingReviewCandidates.forEach { candidate ->
                add(
                    DedupeCandidateSummary(
                        targetType = AiTargetType.PENDING_REVIEW,
                        targetId = candidate.id,
                        merchant = sanitizeLabel(candidate.suggestedMerchant, shouldRedact, "merchant"),
                        amount = candidate.suggestedAmount,
                        currency = candidate.suggestedCurrency,
                        date = candidate.suggestedDate ?: reviewDate,
                        sourceLabel = sanitizeLabel(candidate.packageName, shouldRedact, "source"),
                        textPreview = sanitizePreview(candidate.notificationText, shouldRedact, 120)
                    )
                )
            }
        }.take(AppConfig.Ai.MAX_DEDUPE_CANDIDATES_FOR_AI)

        if (candidates.size < 2) {
            return DedupeJudgeBuildResult.NotNeeded(
                "Deterministic duplicate matching already narrowed this to a single candidate."
            )
        }

        return DedupeJudgeBuildResult.Ready(
            DedupeJudgeInput(subject = subject, candidates = candidates)
        )
    }

    private companion object {
        private const val DEDUPE_WINDOW_MS = 24L * 60 * 60 * 1000L
        private val EMAIL_REGEX = Regex("""\b[\w._%+-]+@[\w.-]+\.[A-Za-z]{2,}\b""")
        private val IBAN_REGEX = Regex("""\b[A-Z]{2}\d{2}[A-Z0-9]{10,30}\b""")
        private val CARD_REGEX = Regex("""\b(?:\d[ -]?){13,19}\b""")
        private val PHONE_REGEX = Regex("""\+?\d[\d\s().-]{6,}\d""")
        private val LONG_NUMBER_REGEX = Regex("""\b\d{10,}\b""")
    }

    private fun sanitizeLabel(value: String?, shouldRedact: Boolean, prefix: String): String {
        val trimmed = value?.trim().orEmpty().take(80)
        if (!shouldRedact) return trimmed
        if (trimmed.isBlank()) return ""
        return "${prefix}_${trimmed.sha256Prefix()}"
    }

    private fun sanitizePreview(value: String?, shouldRedact: Boolean, maxChars: Int): String? {
        val trimmed = value?.trim()?.take(maxChars)?.takeIf { it.isNotBlank() } ?: return null
        if (!shouldRedact) return trimmed
        val redacted = trimmed
            .replace(EMAIL_REGEX, "[REDACTED_EMAIL]")
            .replace(IBAN_REGEX, "[REDACTED_IBAN]")
            .replace(CARD_REGEX, "[REDACTED_CARD]")
            .replace(PHONE_REGEX, "[REDACTED_PHONE]")
            .replace(LONG_NUMBER_REGEX, "[REDACTED_NUMBER]")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)
        return if (redacted.isBlank()) null else redacted
    }

    private fun String.sha256Prefix(length: Int = 12): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString(separator = "") { "%02x".format(it) }.take(length)
    }
}
