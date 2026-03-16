package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.DedupeCandidateSummary
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeBuildResult
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import javax.inject.Inject
import kotlin.math.abs

class DedupeJudgeInputBuilder @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val reviewQueueRepository: ReviewQueueRepository
) {

    suspend fun build(item: PendingReviewWithReceipt): DedupeJudgeBuildResult {
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
            merchant = review.suggestedMerchant,
            amount = review.suggestedAmount,
            currency = review.suggestedCurrency,
            date = reviewDate,
            sourceLabel = review.packageName,
            textPreview = review.notificationText?.take(AppConfig.Ai.MAX_CAPTURE_SUPPORTING_TEXT_CHARS)
        )

        val candidates = buildList {
            expenses.forEach { expense ->
                add(
                    DedupeCandidateSummary(
                        targetType = AiTargetType.EXPENSE,
                        targetId = expense.id,
                        merchant = expense.merchant,
                        amount = expense.amount,
                        currency = expense.currency,
                        date = expense.date,
                        sourceLabel = if (expense.rawNotificationId != null) "notification" else "expense",
                        textPreview = expense.notes?.take(120)
                    )
                )
            }
            pendingReviewCandidates.forEach { candidate ->
                add(
                    DedupeCandidateSummary(
                        targetType = AiTargetType.PENDING_REVIEW,
                        targetId = candidate.id,
                        merchant = candidate.suggestedMerchant,
                        amount = candidate.suggestedAmount,
                        currency = candidate.suggestedCurrency,
                        date = candidate.suggestedDate ?: reviewDate,
                        sourceLabel = candidate.packageName,
                        textPreview = candidate.notificationText?.take(120)
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
    }
}
