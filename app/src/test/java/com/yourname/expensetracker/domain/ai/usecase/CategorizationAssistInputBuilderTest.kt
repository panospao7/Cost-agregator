package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CategorizationAssistInputBuilderTest {

    private lateinit var categoryRepository: CategoryRepository
    private lateinit var aiPolicy: AiPolicy
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var merchantNormalizer: com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
    private lateinit var builder: CategorizationAssistInputBuilder

    @Before
    fun setup() {
        categoryRepository = mockk()
        aiPolicy = mockk()
        expenseRepository = mockk()
        merchantNormalizer = mockk()
        builder = CategorizationAssistInputBuilder(categoryRepository, aiPolicy, expenseRepository, merchantNormalizer)
    }

    @Test
    fun `build includes sorted category options and supporting text when not redacted`() = runTest {
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 2L, name = "Transport", icon = "T", color = "#00FF00"),
            Category(id = 1L, name = "Groceries", icon = "G", color = "#0000FF")
        )
        every { aiPolicy.shouldRedact(any(), AiCapability.CATEGORIZATION_FALLBACK) } returns false
        coEvery { merchantNormalizer.normalize(any()) } returns MerchantLookupResult(
            canonical = MerchantCanonical(id = 1L, normalizedName = "Lidl", searchKey = "lidl"),
            alias = null,
            confidence = 0.9f,
            matchType = MatchType.EXACT_MATCH
        )
        coEvery { expenseRepository.getRecentTransactionsForMerchant(any(), any()) } returns emptyList()

        val result = builder.build(makeItem(), AiSettings())

        assertEquals(listOf("Groceries", "Transport"), result.candidateCategories.map { it.name })
        assertEquals("Lidl", result.merchant)
        assertNotNull(result.supportingText)
        assertEquals("fallback", result.deterministicMatchType)
        assertEquals("weak deterministic match", result.deterministicExplanation)
    }

    @Test
    fun `build removes supporting text when redacted`() = runTest {
        coEvery { categoryRepository.getAll() } returns emptyList()
        every { aiPolicy.shouldRedact(any(), AiCapability.CATEGORIZATION_FALLBACK) } returns true
        coEvery { merchantNormalizer.normalize(any()) } returns MerchantLookupResult(
            canonical = MerchantCanonical(id = 1L, normalizedName = "Lidl", searchKey = "lidl"),
            alias = null,
            confidence = 0.9f,
            matchType = MatchType.EXACT_MATCH
        )
        coEvery { expenseRepository.getRecentTransactionsForMerchant(any(), any()) } returns emptyList()

        val result = builder.build(makeItem(), AiSettings(redactBeforeCloud = true))

        assertNull(result.supportingText)
        assertNull(result.deterministicExplanation)
        assertTrue(result.merchant.startsWith("merchant_"))
        assertTrue(result.merchant != "Lidl")
    }

    @Test
    fun `build receipt input uses scanned receipt target and parsed support text`() = runTest {
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Groceries", icon = "G", color = "#0000FF")
        )
        every { aiPolicy.shouldRedact(any(), AiCapability.CATEGORIZATION_FALLBACK) } returns false
        coEvery { merchantNormalizer.normalize(any()) } returns MerchantLookupResult(
            canonical = MerchantCanonical(id = 1L, normalizedName = "Lidl", searchKey = "lidl"),
            alias = null,
            confidence = 0.9f,
            matchType = MatchType.EXACT_MATCH
        )
        coEvery { expenseRepository.getRecentTransactionsForMerchant(any(), any()) } returns emptyList()

        val receipt = makeItem().receipt!!
        val result = builder.build(
            receipt = receipt,
            draftMerchant = " Lidl ",
            draftAmount = 22.5,
            draftDate = 1234L,
            currentCategoryId = null,
            settings = AiSettings()
        )

        assertEquals(AiTargetType.SCANNED_RECEIPT, result.targetType)
        assertEquals(receipt.id, result.targetId)
        assertEquals("Lidl", result.merchant)
        assertEquals(22.5, result.amount, 0.0)
        assertEquals("Groceries", result.candidateCategories.single().name)
        assertNotNull(result.supportingText)
        assertEquals(null, result.deterministicMatchType)
    }

    @Test
    fun `build receipt input redacts merchant when redaction enabled`() = runTest {
        coEvery { categoryRepository.getAll() } returns emptyList()
        every { aiPolicy.shouldRedact(any(), AiCapability.CATEGORIZATION_FALLBACK) } returns true
        coEvery { merchantNormalizer.normalize(any()) } returns MerchantLookupResult(
            canonical = MerchantCanonical(id = 1L, normalizedName = "Lidl", searchKey = "lidl"),
            alias = null,
            confidence = 0.9f,
            matchType = MatchType.EXACT_MATCH
        )
        coEvery { expenseRepository.getRecentTransactionsForMerchant(any(), any()) } returns emptyList()

        val receipt = makeItem().receipt!!
        val result = builder.build(
            receipt = receipt,
            draftMerchant = "Lidl",
            draftAmount = 22.5,
            draftDate = 1234L,
            currentCategoryId = null,
            settings = AiSettings(redactBeforeCloud = true)
        )

        assertTrue(result.merchant.startsWith("merchant_"))
        assertNull(result.supportingText)
    }

    @Test
    fun `build propagates CancellationException from repository instead of returning empty hints`() = runTest {
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Groceries", icon = "G", color = "#0000FF")
        )
        every { aiPolicy.shouldRedact(any(), AiCapability.CATEGORIZATION_FALLBACK) } returns false
        coEvery { merchantNormalizer.normalize(any()) } returns MerchantLookupResult(
            canonical = MerchantCanonical(id = 1L, normalizedName = "Lidl", searchKey = "lidl"),
            alias = null,
            confidence = 0.9f,
            matchType = MatchType.EXACT_MATCH
        )
        coEvery { expenseRepository.getRecentTransactionsForMerchant(any(), any()) } throws CancellationException("cancelled")

        try {
            builder.build(makeItem(), AiSettings())
            throw AssertionError("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected: cancellation propagates instead of being swallowed
        }
    }

    private fun makeItem(): PendingReviewWithReceipt {
        val review = PendingReview(
            id = 5L,
            rawNotificationId = null,
            scannedReceiptId = 9L,
            suggestedAmount = 22.5,
            suggestedCurrency = "EUR",
            suggestedMerchant = "Lidl",
            suggestedType = "PURCHASE",
            suggestedCategoryId = null,
            suggestedDate = 1234L,
            confidence = 0.4f,
            matchType = "fallback",
            explanation = "weak deterministic match",
            packageName = "receipt.scan",
            notificationTitle = "Receipt",
            notificationText = "Imported receipt"
        )
        val receipt = ScannedReceipt(
            id = 9L,
            imagePath = "receipt.jpg",
            rawOcrText = "LIDL HELLAS TOTAL 22.50",
            parsedTotal = 22.5,
            parsedMerchant = "Lidl",
            parsedDate = 1234L,
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.4f
        )
        return PendingReviewWithReceipt(review, receipt)
    }
}
