package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeBuildResult
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.ai.policy.AiPolicyImpl
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.privacy.FakePrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        builder = DedupeJudgeInputBuilder(
            expenseRepository,
            reviewQueueRepository,
            aiPolicy,
            FakePrivacySettingsRepository(PrivacySettings(redactBeforeCloud = false))
        )
    }

    @Test
    fun `build returns Ready when multiple nearby candidates exist`() = runTest {
        val review = makeReview()
        coEvery {
            expenseRepository.getDuplicateCandidatesInWindow(
                amount = any(),
                date = any(),
                currency = any(),
                transactionType = any(),
                windowMs = any()
            )
        } returns listOf(
            Expense(id = 1L, amount = 10.0, merchant = "Lidl", merchantKey = MerchantKeyGenerator.generate("Lidl"), transactionType = TransactionType.PURCHASE, date = 1_000L),
            Expense(id = 2L, amount = 10.5, merchant = "Lidl", merchantKey = MerchantKeyGenerator.generate("Lidl"), transactionType = TransactionType.PURCHASE, date = 1_200L)
        )
        coEvery { reviewQueueRepository.getPendingReviewsByMerchant("Lidl") } returns emptyList()

        val result = builder.build(PendingReviewWithReceipt(review, null), AiSettings())

        assertTrue(result is DedupeJudgeBuildResult.Ready)
    }

    @Test
    fun `build returns Ready when exactly one candidate exists`() = runTest {
        val review = makeReview()
        coEvery {
            expenseRepository.getDuplicateCandidatesInWindow(
                amount = any(),
                date = any(),
                currency = any(),
                transactionType = any(),
                windowMs = any()
            )
        } returns listOf(
            Expense(id = 1L, amount = 10.0, merchant = "Lidl", merchantKey = MerchantKeyGenerator.generate("Lidl"), transactionType = TransactionType.PURCHASE, date = 1_000L)
        )
        coEvery { reviewQueueRepository.getPendingReviewsByMerchant("Lidl") } returns emptyList()

        val result = builder.build(PendingReviewWithReceipt(review, null), AiSettings())

        assertTrue(result is DedupeJudgeBuildResult.Ready)
    }

    /**
     * A.4 regression: the builder must call getDuplicateCandidatesInWindow
     * with the canonical window from DuplicateDetectionPolicy, NOT the old
     * generic getExpensesBetween reporting query.
     */
    @Test
    fun `build uses canonical DuplicateDetectionPolicy window via getDuplicateCandidatesInWindow`() = runTest {
        val review = makeReview()
        coEvery {
            expenseRepository.getDuplicateCandidatesInWindow(
                amount = any(),
                date = any(),
                currency = any(),
                transactionType = any(),
                windowMs = any()
            )
        } returns emptyList()
        coEvery { reviewQueueRepository.getPendingReviewsByMerchant(any()) } returns emptyList()

        builder.build(PendingReviewWithReceipt(review, null), AiSettings())

        coVerify {
            expenseRepository.getDuplicateCandidatesInWindow(
                amount = review.suggestedAmount!!,
                date = review.suggestedDate!!,
                currency = review.suggestedCurrency,
                transactionType = TransactionType.PURCHASE,
                windowMs = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS
            )
        }
    }

    /**
     * A.4 regression: the builder must forward the review's parsed transaction
     * type to the candidate query so that incompatible types (e.g. DEPOSIT vs
     * PURCHASE) are excluded at the query level.
     */
    @Test
    fun `build forwards parsed transaction type to candidate query`() = runTest {
        val review = makeReview(suggestedType = "DEPOSIT")
        coEvery {
            expenseRepository.getDuplicateCandidatesInWindow(
                amount = any(),
                date = any(),
                currency = any(),
                transactionType = any(),
                windowMs = any()
            )
        } returns emptyList()
        coEvery { reviewQueueRepository.getPendingReviewsByMerchant(any()) } returns emptyList()

        builder.build(PendingReviewWithReceipt(review, null), AiSettings())

        coVerify {
            expenseRepository.getDuplicateCandidatesInWindow(
                amount = any(),
                date = any(),
                currency = any(),
                transactionType = TransactionType.DEPOSIT,
                windowMs = any()
            )
        }
    }

    /**
     * A.4 regression: when the review's suggestedType is unparseable, the
     * builder should fall back to UNKNOWN (which is compatible with all types).
     */
    @Test
    fun `build falls back to UNKNOWN type for invalid suggestedType`() = runTest {
        val review = makeReview(suggestedType = "INVALID_GARBAGE_TYPE")
        coEvery {
            expenseRepository.getDuplicateCandidatesInWindow(
                amount = any(),
                date = any(),
                currency = any(),
                transactionType = any(),
                windowMs = any()
            )
        } returns emptyList()
        coEvery { reviewQueueRepository.getPendingReviewsByMerchant(any()) } returns emptyList()

        builder.build(PendingReviewWithReceipt(review, null), AiSettings())

        coVerify {
            expenseRepository.getDuplicateCandidatesInWindow(
                amount = any(),
                date = any(),
                currency = any(),
                transactionType = TransactionType.UNKNOWN,
                windowMs = any()
            )
        }
    }

    /**
     * A.4 regression: pending-review candidates with incompatible types must
     * be excluded from the AI judge input.
     */
    @Test
    fun `build excludes pending review candidates with incompatible transaction types`() = runTest {
        val review = makeReview(suggestedType = "PURCHASE")
        coEvery {
            expenseRepository.getDuplicateCandidatesInWindow(
                amount = any(),
                date = any(),
                currency = any(),
                transactionType = any(),
                windowMs = any()
            )
        } returns emptyList()

        // Return a pending review with DEPOSIT type — should be filtered out
        val incompatibleCandidate = PendingReview(
            id = 99L,
            rawNotificationId = null,
            suggestedAmount = 10.0,
            suggestedCurrency = "EUR",
            suggestedMerchant = "Lidl",
            suggestedType = "DEPOSIT",
            suggestedCategoryId = null,
            suggestedDate = 1_100L,
            confidence = 0.7f,
            packageName = "pkg",
            notificationTitle = null,
            notificationText = "Received Lidl"
        )
        coEvery { reviewQueueRepository.getPendingReviewsByMerchant("Lidl") } returns listOf(incompatibleCandidate)

        val result = builder.build(PendingReviewWithReceipt(review, null), AiSettings())

        // Incompatible type should be excluded → no candidates → NotNeeded
        assertTrue(
            "Expected NotNeeded when all pending candidates have incompatible types, got $result",
            result is DedupeJudgeBuildResult.NotNeeded
        )
    }

    /**
     * A.4 regression: pending-review candidates with currency mismatch
     * must be excluded from the AI judge input.
     */
    @Test
    fun `build excludes pending review candidates with mismatched currency`() = runTest {
        val review = makeReview(suggestedCurrency = "EUR")
        coEvery {
            expenseRepository.getDuplicateCandidatesInWindow(
                amount = any(),
                date = any(),
                currency = any(),
                transactionType = any(),
                windowMs = any()
            )
        } returns emptyList()

        // Return a pending review with USD — should be filtered out
        val usdCandidate = PendingReview(
            id = 99L,
            rawNotificationId = null,
            suggestedAmount = 10.0,
            suggestedCurrency = "USD",
            suggestedMerchant = "Lidl",
            suggestedType = "PURCHASE",
            suggestedCategoryId = null,
            suggestedDate = 1_100L,
            confidence = 0.7f,
            packageName = "pkg",
            notificationTitle = null,
            notificationText = "Paid Lidl"
        )
        coEvery { reviewQueueRepository.getPendingReviewsByMerchant("Lidl") } returns listOf(usdCandidate)

        val result = builder.build(PendingReviewWithReceipt(review, null), AiSettings())

        assertTrue(
            "Expected NotNeeded when all pending candidates have mismatched currency, got $result",
            result is DedupeJudgeBuildResult.NotNeeded
        )
    }

    /**
     * Verify that DEDUPE_WINDOW_MS in the builder aligns with
     * [DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS] (5 min = 300_000 ms).
     */
    @Test
    fun `builder window constant matches DuplicateDetectionPolicy canonical window`() {
        // This is a compile-time/link-time assertion: the builder's DEDUPE_WINDOW_MS
        // is initialized from DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS.
        // We verify the canonical value here to catch accidental drift.
        assertEquals(
            "Canonical dedupe window should be 5 minutes (300_000 ms)",
            300_000L,
            DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS
        )
    }

    /**
     * P8-NEW: PrivacySettings.redactBeforeCloud is authoritative. With cloud usable for
     * DEDUPE_JUDGE and AiSettings.redactBeforeCloud=false, a privacy redact=true must still
     * pseudonymize merchant/source labels in the built input. Uses real AiPolicyImpl.
     */
    @Test
    fun `build redacts labels when privacy requires it even though ai redaction is off`() = runTest {
        val privacyBuilder = DedupeJudgeInputBuilder(
            expenseRepository,
            reviewQueueRepository,
            AiPolicyImpl(),
            FakePrivacySettingsRepository(PrivacySettings(redactBeforeCloud = true))
        )
        val review = makeReview()
        coEvery {
            expenseRepository.getDuplicateCandidatesInWindow(
                amount = any(),
                date = any(),
                currency = any(),
                transactionType = any(),
                windowMs = any()
            )
        } returns listOf(
            Expense(id = 1L, amount = 10.0, merchant = "Lidl", merchantKey = MerchantKeyGenerator.generate("Lidl"), transactionType = TransactionType.PURCHASE, date = 1_000L)
        )
        coEvery { reviewQueueRepository.getPendingReviewsByMerchant("Lidl") } returns emptyList()

        val cloudOnRedactionOff = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            dedupeJudgeEnabled = true,
            redactBeforeCloud = false
        )

        val result = privacyBuilder.build(PendingReviewWithReceipt(review, null), cloudOnRedactionOff)

        assertTrue(result is DedupeJudgeBuildResult.Ready)
        val input = (result as DedupeJudgeBuildResult.Ready).input
        assertTrue(input.subject.merchant.startsWith("merchant_"))
        assertTrue(input.subject.merchant != "Lidl")
        assertTrue(input.candidates.all { it.merchant.startsWith("merchant_") })
        assertTrue(input.candidates.none { it.merchant == "Lidl" })
    }

    private fun makeReview(
        suggestedType: String = "PURCHASE",
        suggestedCurrency: String = "EUR"
    ) = PendingReview(
        id = 9L,
        rawNotificationId = null,
        suggestedAmount = 10.0,
        suggestedCurrency = suggestedCurrency,
        suggestedMerchant = "Lidl",
        suggestedType = suggestedType,
        suggestedCategoryId = null,
        suggestedDate = 1_100L,
        confidence = 0.7f,
        packageName = "pkg",
        notificationTitle = null,
        notificationText = "Paid Lidl"
    )
}
