package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.privacy.FakePrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReceiptItemCategorizationInputBuilderTest {

    private lateinit var receiptRepository: ReceiptRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var receiptParser: ReceiptParser
    private lateinit var aiPolicy: AiPolicy
    private lateinit var builder: ReceiptItemCategorizationInputBuilder

    @Before
    fun setup() {
        receiptRepository = mockk(relaxed = true)
        categoryRepository = mockk()
        receiptParser = mockk()
        aiPolicy = mockk()

        builder = ReceiptItemCategorizationInputBuilder(
            receiptRepository = receiptRepository,
            categoryRepository = categoryRepository,
            receiptParser = receiptParser,
            aiPolicy = aiPolicy,
            privacySettingsRepository = FakePrivacySettingsRepository(
                PrivacySettings(redactBeforeCloud = false)
            )
        )
    }

    @Test
    fun `build keeps raw local categories and adds cloud-safe category options when redaction is enabled`() = runTest {
        val categories = listOf(
            Category(id = 10L, name = "Private Category Alpha", icon = "A", color = "#112233"),
            Category(id = 20L, name = "Very Sensitive Category Beta", icon = "B", color = "#445566")
        )
        val receipt = ScannedReceipt(
            id = 9L,
            imagePath = null,
            rawOcrText = "",
            parsedTotal = 12.5,
            parsedMerchant = "Merchant",
            parsedDate = null,
            parsedItems = "[{\"description\":\"Item\",\"totalPrice\":12.5}]",
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.8f
        )

        every { aiPolicy.canUseCloudFor(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns true
        every { aiPolicy.shouldRedact(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns true
        coEvery { categoryRepository.getAll() } returns categories
        every { receiptParser.lineItemsFromJson(any()) } returns listOf(
            ReceiptParser.LineItem(
                description = "Line Item",
                quantity = null,
                unitPrice = null,
                totalPrice = 12.5
            )
        )

        val result = builder.build(receipt, AiSettings())

        assertEquals(categories, result.userCategories)
        assertEquals(categories.map { it.id }, result.cloudCategoryOptions.map { it.categoryId })
        assertTrue(result.cloudCategoryOptions.all { it.cloudName.startsWith("cat_") })
        assertFalse(result.cloudCategoryOptions.any { it.cloudName == categories[0].name })
        assertFalse(result.cloudCategoryOptions.any { it.cloudName == categories[1].name })
    }

    // ── RP-12 12b (P3-007) conditional remainder: ephemeral item source ─────────

    @Test
    fun `build uses permitted ephemeral items over the persisted representation for a fresh insert`() = runTest {
        // Persisted representation under STORE_REDACTED: the redacted projection
        // (prices only) — permitted, but NOT the item source while ephemeral exists.
        val receipt = ScannedReceipt(
            id = 9L,
            imagePath = null,
            rawOcrText = "",
            parsedTotal = 12.5,
            parsedMerchant = "Merchant",
            parsedDate = null,
            parsedItems = "{\"schema\":\"REDACTED_V1\",\"items\":[{\"totalPrice\":12.5,\"currency\":\"EUR\"}]}",
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.8f
        )
        val ephemeral = listOf(
            ReceiptParser.LineItem(description = "Fresh Milk", quantity = 2.0, unitPrice = 6.25, totalPrice = 12.5)
        )
        every { aiPolicy.canUseCloudFor(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns false
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Food", icon = "A", color = "#111111")
        )
        // Mirror of reality: the redacted schema parses to nothing usable.
        coEvery { receiptParser.lineItemsFromJson(any()) } returns emptyList()

        val result = builder.build(receipt, AiSettings(), ephemeral)

        assertEquals(1, result.lineItems.size)
        assertEquals("Fresh Milk", result.lineItems[0].description)
        assertEquals(12.5, result.lineItems[0].totalPrice, 0.0)
        // The persisted representation was never even parsed as the item source.
        coVerify(exactly = 0) { receiptParser.lineItemsFromJson(any()) }
    }

    @Test
    fun `ephemeral items pass through the same redaction as persisted items`() = runTest {
        val receipt = ScannedReceipt(
            id = 9L,
            imagePath = null,
            rawOcrText = "",
            parsedTotal = 5.0,
            parsedMerchant = "Merchant",
            parsedDate = null,
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.8f
        )
        val ephemeral = listOf(
            ReceiptParser.LineItem(
                description = "mail me at evil@example.com",
                quantity = 1.0,
                unitPrice = 5.0,
                totalPrice = 5.0
            )
        )
        every { aiPolicy.canUseCloudFor(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns true
        every { aiPolicy.shouldRedact(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns true
        coEvery { categoryRepository.getAll() } returns emptyList()

        val result = builder.build(receipt, AiSettings(), ephemeral)

        // The ephemeral source never bypasses AI-input hygiene: identical redaction.
        assertEquals(1, result.lineItems.size)
        assertEquals("mail me at [REDACTED_EMAIL]", result.lineItems[0].description)
        assertTrue(result.redactBeforeCloud)
    }
}
