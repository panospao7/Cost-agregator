package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.policy.AiPolicyImpl
import com.yourname.expensetracker.domain.privacy.FakePrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract: PrivacySettings.redactBeforeCloud is authoritative for build-time redaction.
 *
 * Both [ReceiptItemCategorizationInputBuilder] and [ReceiptAssistInputBuilder] must redact
 * when EITHER AiSettings.redactBeforeCloud OR PrivacySettings.redactBeforeCloud is true.
 * Previously these builders consulted only AiSettings (via AiPolicy.shouldRedact), so
 * PrivacySettings.redactBeforeCloud=true with AiSettings.redactBeforeCloud=false leaked
 * unhashed merchant / category labels to cloud prompts (P8-NEW-01).
 */
class InputBuilderRedactionPolicyTest {

    private val rawMerchant = "Famous Local Bakery"
    private val rawCategoryNameA = "Private Category Alpha"
    private val rawCategoryNameB = "Very Sensitive Category Beta"

    // AiSettings with cloud usable for categorization but redaction OFF at the AI layer.
    private fun aiSettingsCloudOnRedactionOff() = AiSettings(
        aiEnabled = true,
        allowCloudAi = true,
        receiptItemCategorizationEnabled = true,
        receiptAssistEnabled = true,
        redactBeforeCloud = false
    )

    // region ReceiptItemCategorizationInputBuilder

    @Test
    fun `categorization builder redacts when privacy requires it even though ai redaction is off`() = runTest {
        val builder = buildCategorizationBuilder(privacyRedact = true)

        val result = builder.build(makeReceipt(), aiSettingsCloudOnRedactionOff())

        assertTrue("redactBeforeCloud must reflect privacy authority", result.redactBeforeCloud)
        // Merchant must be hashed, not raw.
        assertTrue(result.merchant.startsWith("merchant_"))
        assertFalse(result.merchant.contains(rawMerchant))
        // Category names must be hashed cloud-safe options, not raw labels.
        assertTrue(result.cloudCategoryOptions.isNotEmpty())
        assertTrue(result.cloudCategoryOptions.all { it.cloudName.startsWith("cat_") })
        assertFalse(result.cloudCategoryOptions.any { it.cloudName.contains(rawCategoryNameA) })
        assertFalse(result.cloudCategoryOptions.any { it.cloudName.contains(rawCategoryNameB) })
    }

    @Test
    fun `categorization builder leaves data raw when both privacy and ai redaction are off`() = runTest {
        val builder = buildCategorizationBuilder(privacyRedact = false)

        val result = builder.build(makeReceipt(), aiSettingsCloudOnRedactionOff())

        assertFalse(result.redactBeforeCloud)
        // Merchant kept raw (not hashed).
        assertFalse(result.merchant.startsWith("merchant_"))
        assertTrue(result.merchant.contains(rawMerchant))
        // No cloud-safe category options are emitted when redaction is off.
        assertTrue(result.cloudCategoryOptions.isEmpty())
    }

    // endregion

    // region ReceiptAssistInputBuilder

    @Test
    fun `assist builder redacts when privacy requires it even though ai redaction is off`() = runTest {
        val builder = buildAssistBuilder(privacyRedact = true)

        val result = builder.build(makeReceipt(), aiSettingsCloudOnRedactionOff())

        assertTrue("redactBeforeCloud must reflect privacy authority", result.redactBeforeCloud)
    }

    @Test
    fun `assist builder leaves data raw when both privacy and ai redaction are off`() = runTest {
        val builder = buildAssistBuilder(privacyRedact = false)

        val result = builder.build(makeReceipt(), aiSettingsCloudOnRedactionOff())

        assertFalse(result.redactBeforeCloud)
    }

    // endregion

    private fun buildCategorizationBuilder(privacyRedact: Boolean): ReceiptItemCategorizationInputBuilder {
        val receiptRepository = mockk<ReceiptRepository>(relaxed = true)
        val categoryRepository = mockk<CategoryRepository>()
        val receiptParser = mockk<ReceiptParser>()

        val categories = listOf(
            Category(id = 10L, name = rawCategoryNameA, icon = "A", color = "#112233"),
            Category(id = 20L, name = rawCategoryNameB, icon = "B", color = "#445566")
        )
        coEvery { categoryRepository.getAll() } returns categories
        every { receiptParser.lineItemsFromJson(any()) } returns listOf(
            ReceiptParser.LineItem(
                description = "Sourdough Loaf",
                quantity = null,
                unitPrice = null,
                totalPrice = 4.5
            )
        )

        return ReceiptItemCategorizationInputBuilder(
            receiptRepository = receiptRepository,
            categoryRepository = categoryRepository,
            receiptParser = receiptParser,
            aiPolicy = AiPolicyImpl(),
            privacySettingsRepository = FakePrivacySettingsRepository(
                PrivacySettings(redactBeforeCloud = privacyRedact)
            )
        )
    }

    private fun buildAssistBuilder(privacyRedact: Boolean): ReceiptAssistInputBuilder {
        return ReceiptAssistInputBuilder(
            aiPolicy = AiPolicyImpl(),
            timeProvider = FakeTimeProvider(1_710_000_000_000L),
            privacySettingsRepository = FakePrivacySettingsRepository(
                PrivacySettings(redactBeforeCloud = privacyRedact)
            )
        )
    }

    private fun makeReceipt() = ScannedReceipt(
        id = 42L,
        imagePath = "receipt.jpg",
        rawOcrText = "Famous Local Bakery\nSourdough Loaf 4.50\nTOTAL 4.50",
        parsedTotal = 4.5,
        parsedMerchant = rawMerchant,
        parsedDate = 1234L,
        parsedItems = "[{\"description\":\"Sourdough Loaf\",\"totalPrice\":4.5}]",
        parsedTaxAmount = null,
        currency = "EUR",
        confidence = 0.9f
    )
}
