package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.common.sha256Prefix
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.privacy.FakePrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ReviewExplanationInputBuilderTest {

    private lateinit var aiPolicy: AiPolicy
    private lateinit var builder: ReviewExplanationInputBuilder

    @Before
    fun setup() {
        aiPolicy = mockk()
        builder = ReviewExplanationInputBuilder(
            aiPolicy,
            FakePrivacySettingsRepository(PrivacySettings(redactBeforeCloud = false))
        )
    }

    @Test
    fun `build pseudonymizes merchant and packageName and removes explanation when redaction enabled`() = runTest {
        every { aiPolicy.shouldRedact(any(), AiCapability.REVIEW_EXPLANATION) } returns true
        val review = makeReview()

        val result = builder.build(review, AiSettings(redactBeforeCloud = true))

        assertEquals("merchant_${review.suggestedMerchant.sha256Prefix()}", result.merchant)
        assertEquals("app_${review.packageName.sha256Prefix()}", result.packageName)
        assertNull(result.explanation)
        assertNull(result.notificationTitle)
        assertNull(result.notificationText)
    }

    @Test
    fun `build keeps raw fields and clamps notification text when redaction disabled`() = runTest {
        every { aiPolicy.shouldRedact(any(), AiCapability.REVIEW_EXPLANATION) } returns false
        val longNotificationText = "x".repeat(AppConfig.Ai.MAX_REVIEW_TEXT_CHARS_FOR_CLOUD + 25)
        val review = makeReview(notificationText = longNotificationText)

        val result = builder.build(review, AiSettings(redactBeforeCloud = false))

        assertEquals(review.suggestedMerchant, result.merchant)
        assertEquals(review.packageName, result.packageName)
        assertEquals(review.explanation, result.explanation)
        assertEquals(review.notificationTitle, result.notificationTitle)
        assertEquals(
            longNotificationText.take(AppConfig.Ai.MAX_REVIEW_TEXT_CHARS_FOR_CLOUD),
            result.notificationText
        )
    }

    @Test
    fun `build pseudonymizes when privacy requires it even though ai redaction is off`() = runTest {
        every { aiPolicy.shouldRedact(any(), AiCapability.REVIEW_EXPLANATION) } returns false
        builder = ReviewExplanationInputBuilder(
            aiPolicy,
            FakePrivacySettingsRepository(PrivacySettings(redactBeforeCloud = true))
        )
        val review = makeReview()

        val result = builder.build(review, AiSettings(redactBeforeCloud = false))

        assertEquals("merchant_${review.suggestedMerchant.sha256Prefix()}", result.merchant)
        assertEquals("app_${review.packageName.sha256Prefix()}", result.packageName)
        assertNull(result.explanation)
        assertNull(result.notificationTitle)
        assertNull(result.notificationText)
    }

    private fun makeReview(notificationText: String = "Paid 42 EUR to Test Merchant"): PendingReview {
        return PendingReview(
            id = 17L,
            rawNotificationId = 44L,
            scannedReceiptId = null,
            suggestedAmount = 42.0,
            suggestedCurrency = "EUR",
            suggestedMerchant = "Test Merchant",
            suggestedType = "PURCHASE",
            suggestedCategoryId = 3L,
            suggestedDate = 123456789L,
            confidence = 0.87f,
            matchType = "fallback",
            explanation = "Matched merchant with known spending pattern",
            packageName = "com.bank.app",
            notificationTitle = "Card payment",
            notificationText = notificationText
        )
    }
}
