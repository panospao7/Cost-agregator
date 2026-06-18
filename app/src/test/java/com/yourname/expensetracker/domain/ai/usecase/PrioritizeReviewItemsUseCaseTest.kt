package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.domain.ai.model.ReviewPriorityFactors
import com.yourname.expensetracker.domain.ai.model.ReviewPriorityScore
import com.yourname.expensetracker.domain.ai.service.ReviewPriorityScorer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PrioritizeReviewItemsUseCaseTest : AnalyticsEngineTestBase() {

    private lateinit var scorer: ReviewPriorityScorer
    private lateinit var useCase: PrioritizeReviewItemsUseCase

    @Before
    fun initUseCase() {
        scorer = mockk(relaxed = true)
        useCase = PrioritizeReviewItemsUseCase(scorer)
    }

    @Test
    fun `high confidence items prioritized first`() = runTest {
        val low = review(id = 1L, createdAt = 2_000L)
        val high = review(id = 2L, createdAt = 3_000L)
        val reviews = listOf(low, high)

        coEvery { scorer.scoreReviews(reviews) } returns listOf(
            score(reviewId = 1L, priority = 0.40f),
            score(reviewId = 2L, priority = 0.90f)
        )

        val result = useCase.execute(reviews)

        assertEquals(listOf(2L, 1L), result.map { it.id })
    }

    @Test
    fun `empty list empty result`() = runTest {
        val result = useCase.execute(emptyList())

        assertEquals(emptyList<PendingReview>(), result)
        coVerify(exactly = 0) { scorer.scoreReviews(any()) }
    }

    @Test
    fun `score calculation correct`() = runTest {
        val review = review(id = 10L, createdAt = 1_000L)
        every { scorer.calculateBaseScore(review) } returns 0.73f

        val score = useCase.quickScore(review)

        assertApproxEquals(0.73f, score, 0.0001f)
    }

    @Test
    fun `ties broken by date`() = runTest {
        val older = review(id = 100L, createdAt = 1_000L)
        val newer = review(id = 200L, createdAt = 5_000L)
        val reviews = listOf(newer, older)

        coEvery { scorer.scoreReviews(reviews) } returns listOf(
            score(reviewId = newer.id, priority = 0.80f),
            score(reviewId = older.id, priority = 0.80f)
        )

        val result = useCase.execute(reviews)

        assertEquals(listOf(older.id, newer.id), result.map { it.id })
    }

    private fun review(id: Long, createdAt: Long): PendingReview {
        return PendingReview(
            id = id,
            rawNotificationId = null,
            scannedReceiptId = null,
            suggestedAmount = 20.0,
            suggestedCurrency = "EUR",
            suggestedMerchant = "Store $id",
            suggestedType = "PURCHASE",
            suggestedCategoryId = null,
            confidence = 0.5f,
            packageName = "pkg",
            notificationTitle = null,
            notificationText = null,
            createdAt = createdAt
        )
    }

    private fun score(reviewId: Long, priority: Float): ReviewPriorityScore {
        return ReviewPriorityScore(
            reviewId = reviewId,
            priorityScore = priority,
            urgencyReason = null,
            estimatedApprovalTime = null,
            factors = ReviewPriorityFactors(
                confidenceLevel = 0.5f,
                duplicateRisk = 0.5f,
                merchantClarity = 0.5f,
                timeSensitivity = 0.5f,
                categoryClarity = 0.5f,
                amountSignificance = 0.5f,
                historicalPattern = 0.5f
            )
        )
    }
}
