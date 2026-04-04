package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeBuildResult
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DedupeJudgeInputBuilderTest {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var reviewQueueRepository: ReviewQueueRepository
    private lateinit var aiPolicy: AiPolicy
    private lateinit var builder: DedupeJudgeInputBuilder

    @Before
    fun setup() {
        expenseRepository = mockk()
        reviewQueueRepository = mockk()
        aiPolicy = mockk()
        every { aiPolicy.canUseCloudFor(any(), AiCapability.DEDUPE_JUDGE) } returns true
        every { aiPolicy.shouldRedact(any(), AiCapability.DEDUPE_JUDGE) } returns false
        builder = DedupeJudgeInputBuilder(expenseRepository, reviewQueueRepository, aiPolicy)
    }

    @Test
    fun `build returns Ready when multiple nearby candidates exist`() = runTest {
        val review = makeReview()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            Expense(id = 1L, amount = 10.0, merchant = "Lidl", merchantKey = MerchantKeyGenerator.generate("Lidl"), transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE, date = 1_000L),
            Expense(id = 2L, amount = 10.5, merchant = "Lidl", merchantKey = MerchantKeyGenerator.generate("Lidl"), transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE, date = 1_200L)
        )
        coEvery { reviewQueueRepository.getPendingReviewsByMerchant("Lidl") } returns emptyList()

        val result = builder.build(PendingReviewWithReceipt(review, null), AiSettings())

        assertTrue(result is DedupeJudgeBuildResult.Ready)
    }

    private fun makeReview() = PendingReview(
        id = 9L,
        rawNotificationId = null,
        suggestedAmount = 10.0,
        suggestedCurrency = "EUR",
        suggestedMerchant = "Lidl",
        suggestedType = "PURCHASE",
        suggestedCategoryId = null,
        suggestedDate = 1_100L,
        confidence = 0.7f,
        packageName = "pkg",
        notificationTitle = null,
        notificationText = "Paid Lidl"
    )
}
