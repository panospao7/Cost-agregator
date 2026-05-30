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
}
