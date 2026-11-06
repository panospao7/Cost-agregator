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
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `build keeps local category identities and adds cloud-safe category options when redaction is enabled`() = runTest {
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

        assertEquals(categories.map { it.id }, result.userCategories.map { it.id })
        assertEquals(categories.map { it.name }, result.userCategories.map { it.name })
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

    @Test
    fun `persisted item inputs preserve receipt currency and tax without a home currency fallback`() = runTest {
        every { aiPolicy.canUseCloudFor(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns false
        coEvery { categoryRepository.getAll() } returns emptyList()
        every { receiptParser.lineItemsFromJson(any()) } returns listOf(
            ReceiptParser.LineItem(description = "Food", quantity = null, unitPrice = null, totalPrice = 12.5)
        )

        for (currency in listOf("CAD", "JPY", " cad ")) {
            val result = builder.build(receiptWithTax(currency), AiSettings())

            assertEquals(currency, result.currency)
            assertEquals(1.25, requireNotNull(result.totalTax), 0.0)
            assertEquals(12.5, result.lineItems.single().totalPrice, 0.0)
            assertFalse(result.redactBeforeCloud)
        }
    }

    @Test
    fun `missing and unsupported receipt currencies remain unresolved at the input boundary`() = runTest {
        every { aiPolicy.canUseCloudFor(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns false
        coEvery { categoryRepository.getAll() } returns emptyList()
        every { receiptParser.lineItemsFromJson(any()) } returns emptyList()

        for (currency in listOf("", "XXX", "ZZZ")) {
            val result = builder.build(receiptWithTax(currency), AiSettings())

            assertEquals(currency, result.currency)
            assertEquals(1.25, requireNotNull(result.totalTax), 0.0)
        }
    }

    @Test
    fun `ephemeral item inputs retain the same receipt currency and tax contract`() = runTest {
        every { aiPolicy.canUseCloudFor(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns false
        coEvery { categoryRepository.getAll() } returns emptyList()
        val ephemeral = listOf(
            ReceiptParser.LineItem(description = "Food", quantity = null, unitPrice = null, totalPrice = 12.5)
        )

        val result = builder.build(receiptWithTax("JPY"), AiSettings(), ephemeral)

        assertEquals("JPY", result.currency)
        assertEquals(1.25, requireNotNull(result.totalTax), 0.0)
        assertEquals(ephemeral, result.lineItems)
        verify(exactly = 0) { receiptParser.lineItemsFromJson(any()) }
    }

    @Test
    fun `redaction preserves explicit adjustment provenance without guessing from a negative amount`() = runTest {
        every { aiPolicy.canUseCloudFor(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns true
        every { aiPolicy.shouldRedact(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns true
        coEvery { categoryRepository.getAll() } returns emptyList()
        val ephemeral = listOf(
            ReceiptParser.LineItem("discount", null, null, -2.0, isAdjustment = true),
            ReceiptParser.LineItem("invalid price", null, null, -1.0)
        )

        val result = builder.build(receiptWithTax("EUR"), AiSettings(), ephemeral)

        assertTrue(result.redactBeforeCloud)
        assertTrue(result.lineItems.first().isAdjustment)
        assertFalse(result.lineItems.last().isAdjustment)
        assertEquals(listOf(-2.0, -1.0), result.lineItems.map { it.totalPrice })
        verify(exactly = 0) { receiptParser.lineItemsFromJson(any()) }
    }

    // ── P-03-5: tax-inclusive receipts must not be asked to allocate tax again ──

    @Test
    fun `P-03-5 tax-inclusive receipt by line-item sum yields null totalTax`() = runTest {
        every { aiPolicy.canUseCloudFor(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns false
        coEvery { categoryRepository.getAll() } returns emptyList()
        // Items 10 + 2.5 = 12.5 == total, so the 1.25 tax is already inside the total.
        val items = listOf(
            ReceiptParser.LineItem("A", null, null, 10.0),
            ReceiptParser.LineItem("B", null, null, 2.5)
        )

        val result = builder.build(receiptWithTax("EUR", parsedTotal = 12.5), AiSettings(), items)

        assertNull(result.totalTax)
        assertEquals(listOf(10.0, 2.5), result.lineItems.map { it.totalPrice })
    }

    @Test
    fun `P-03-5 tax-exclusive receipt keeps the parsed tax`() = runTest {
        every { aiPolicy.canUseCloudFor(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns false
        coEvery { categoryRepository.getAll() } returns emptyList()
        // Items 10 + 2.5 = 12.5 + tax 1.25 = total 13.75: tax sits on top of the items.
        val items = listOf(
            ReceiptParser.LineItem("A", null, null, 10.0),
            ReceiptParser.LineItem("B", null, null, 2.5)
        )

        val result = builder.build(receiptWithTax("EUR", parsedTotal = 13.75), AiSettings(), items)

        assertEquals(1.25, requireNotNull(result.totalTax), 0.0)
    }

    @Test
    fun `P-03-5 line-item sum outside the tolerance keeps the parsed tax`() = runTest {
        every { aiPolicy.canUseCloudFor(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns false
        coEvery { categoryRepository.getAll() } returns emptyList()
        // Sum 11.0 vs total 12.5: difference 1.5 exceeds 5% of the total (0.625).
        val items = listOf(ReceiptParser.LineItem("A", null, null, 11.0))

        val result = builder.build(receiptWithTax("EUR", parsedTotal = 12.5), AiSettings(), items)

        assertEquals(1.25, requireNotNull(result.totalTax), 0.0)
    }

    @Test
    fun `P-03-5 receipt taxInclusive flag alone yields null totalTax`() = runTest {
        every { aiPolicy.canUseCloudFor(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns false
        coEvery { categoryRepository.getAll() } returns emptyList()
        // Numbers say exclusive (12.5 + 1.25 = 13.75) but the transient flag set
        // during receipt processing says inclusive: the flag wins.
        val receipt = receiptWithTax("EUR", parsedTotal = 13.75).apply { taxInclusive = true }
        val items = listOf(ReceiptParser.LineItem("A", null, null, 12.5))

        val result = builder.build(receipt, AiSettings(), items)

        assertNull(result.totalTax)
    }

    @Test
    fun `P-03-5 inclusive check uses unredacted ephemeral items when redaction is active`() = runTest {
        every { aiPolicy.canUseCloudFor(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns true
        every { aiPolicy.shouldRedact(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns true
        coEvery { categoryRepository.getAll() } returns emptyList()
        val items = listOf(
            ReceiptParser.LineItem("mail evil@example.com", null, null, 10.0),
            ReceiptParser.LineItem("1234567890123456", null, null, 2.5)
        )

        val result = builder.build(receiptWithTax("EUR", parsedTotal = 12.5), AiSettings(), items)

        assertTrue(result.redactBeforeCloud)
        assertNull(result.totalTax)
        assertEquals("mail [REDACTED_EMAIL]", result.lineItems.first().description)
        verify(exactly = 0) { receiptParser.lineItemsFromJson(any()) }
    }

    @Test
    fun `P-03-5 inclusive check uses unredacted persisted items when redaction is active`() = runTest {
        every { aiPolicy.canUseCloudFor(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns true
        every { aiPolicy.shouldRedact(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns true
        coEvery { categoryRepository.getAll() } returns emptyList()
        every { receiptParser.lineItemsFromJson(any()) } returns listOf(
            ReceiptParser.LineItem("secret evil@example.com", null, null, 12.5)
        )

        val result = builder.build(receiptWithTax("EUR", parsedTotal = 12.5), AiSettings())

        assertTrue(result.redactBeforeCloud)
        assertNull(result.totalTax)
    }

    @Test
    fun `P-03-5 redacted exclusive receipt keeps the parsed tax`() = runTest {
        every { aiPolicy.canUseCloudFor(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns true
        every { aiPolicy.shouldRedact(any(), AiCapability.RECEIPT_ITEM_CATEGORIZATION) } returns true
        coEvery { categoryRepository.getAll() } returns emptyList()
        val items = listOf(ReceiptParser.LineItem("secret evil@example.com", null, null, 12.5))

        val result = builder.build(receiptWithTax("EUR", parsedTotal = 13.75), AiSettings(), items)

        assertTrue(result.redactBeforeCloud)
        assertEquals(1.25, requireNotNull(result.totalTax), 0.0)
    }

    // The default total is 13.75 (items 12.5 + tax 1.25): an EXCLUSIVE receipt. A
    // total of 12.5 with a 12.5 line item and 1.25 tax is now (correctly) detected
    // as tax-inclusive by P-03-5 and would null out totalTax, which is not what the
    // currency/tax-contract tests above mean to exercise.
    private fun receiptWithTax(currency: String, parsedTotal: Double = 13.75) = ScannedReceipt(
        id = 9L,
        imagePath = null,
        rawOcrText = "",
        parsedTotal = parsedTotal,
        parsedMerchant = "Merchant",
        parsedDate = null,
        parsedItems = "items",
        parsedTaxAmount = 1.25,
        currency = currency,
        confidence = 0.8f
    )
}
