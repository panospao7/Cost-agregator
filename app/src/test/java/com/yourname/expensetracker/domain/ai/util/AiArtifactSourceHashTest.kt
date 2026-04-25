package com.yourname.expensetracker.domain.ai.util

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.CategoryOption
import com.yourname.expensetracker.domain.ai.model.CloudCategoryOption
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.model.DashboardBudgetWarningInput
import com.yourname.expensetracker.domain.ai.model.DashboardUpcomingItemInput
import com.yourname.expensetracker.domain.ai.model.DedupeCandidateSummary
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.dto.CategoryRef
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AiArtifactSourceHashTest {

    @Test
    fun `forDedupeJudge is stable for equivalent inputs`() {
        val input = DedupeJudgeInput(
            subject = DedupeCandidateSummary(
                targetType = AiTargetType.PENDING_REVIEW,
                targetId = 1L,
                merchant = "Store",
                amount = 12.34,
                currency = "EUR",
                date = 1234L,
                sourceLabel = "pkg"
            ),
            candidates = listOf(
                DedupeCandidateSummary(AiTargetType.EXPENSE, 2L, "Store", 12.34, "EUR", 1235L, "expense")
            )
        )

        val hashA = AiArtifactSourceHash.forDedupeJudge(input)
        val hashB = AiArtifactSourceHash.forDedupeJudge(input.copy())

        assertEquals(hashA, hashB)
    }

    @Test
    fun `forReviewExplanation changes when business input changes`() {
        val base = ReviewExplanationInput(
            reviewId = 9L,
            merchant = "Coffee",
            amount = 4.5,
            currency = "EUR",
            suggestedType = "PURCHASE",
            suggestedCategoryId = null,
            confidence = 0.8f,
            matchType = null,
            explanation = null,
            packageName = "pkg",
            notificationTitle = "title",
            notificationText = "text"
        )

        val hashA = AiArtifactSourceHash.forReviewExplanation(base)
        val hashB = AiArtifactSourceHash.forReviewExplanation(base.copy(notificationText = "changed"))

        assertNotEquals(hashA, hashB)
    }

    @Test
    fun `forDashboardBriefing is stable and locale invariant`() {
        val input = DashboardBriefingInput(
            dateKey = "2026-04-19",
            weatherHeadline = UiText.from("Sunny"),
            weatherSummary = UiText.from("Clear"),
            discretionaryBudget = 120.0,
            totalCommitted = 80.0,
            totalLikely = 110.0,
            pendingReviewCount = 2,
            currentMonthSpent = 300.0,
            topCategories = listOf("Food", "Transport"),
            budgetWarnings = listOf(DashboardBudgetWarningInput(UiText.from("Food"), 90)),
            upcomingItems = listOf(DashboardUpcomingItemInput("Rent", 500.0, 123L, "EUR"))
        )

        val hashA = AiArtifactSourceHash.forDashboardBriefing(input)
        val hashB = AiArtifactSourceHash.forDashboardBriefing(input.copy())

        assertEquals(hashA, hashB)
    }

    @Test
    fun `forTransactionInsight changes when merchant changes`() {
        val base = Expense(
            id = 3L,
            amount = 20.0,
            currency = "EUR",
            merchant = "Alpha",
            transactionType = TransactionType.PURCHASE,
            date = 111L
        )

        val hashA = AiArtifactSourceHash.forTransactionInsight(base)
        val hashB = AiArtifactSourceHash.forTransactionInsight(base.copy(merchant = "Beta"))
        assertNotEquals(hashA, hashB)
    }

    @Test
    fun `forReceiptItemCategorization changes when item amount changes`() {
        val input = ReceiptItemCategorizationInput(
            receiptId = 7L,
            merchant = "Store",
            lineItems = listOf(
                ReceiptParser.LineItem("Milk", 1.0, 2.0, 2.0)
            ),
            userCategories = listOf(CategoryRef(1L, "Food")),
            cloudCategoryOptions = listOf(CloudCategoryOption(1L, "Food")),
            totalTax = 0.2,
            currency = "EUR",
            redactBeforeCloud = true
        )

        val hashA = AiArtifactSourceHash.forReceiptItemCategorization(input)
        val hashB = AiArtifactSourceHash.forReceiptItemCategorization(
            input.copy(lineItems = listOf(ReceiptParser.LineItem("Milk", 1.0, 2.5, 2.5)))
        )

        assertNotEquals(hashA, hashB)
    }

    @Test
    fun `forReviewCategorizationFallback is stable for equivalent inputs`() {
        val input = com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput(
            targetType = AiTargetType.PENDING_REVIEW,
            targetId = 1L,
            merchant = "Store",
            amount = 10.0,
            currency = "EUR",
            transactionType = DomainTransactionType.PURCHASE,
            date = 123L,
            currentCategoryId = null,
            deterministicMatchType = "FALLBACK",
            deterministicExplanation = "weak",
            candidateCategories = listOf(CategoryOption(2L, "Groceries"))
        )

        val hashA = AiArtifactSourceHash.forReviewCategorizationFallback(input)
        val hashB = AiArtifactSourceHash.forReviewCategorizationFallback(input.copy())

        assertEquals(hashA, hashB)
    }
}
