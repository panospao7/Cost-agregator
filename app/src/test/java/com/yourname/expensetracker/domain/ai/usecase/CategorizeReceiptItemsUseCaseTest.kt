package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.CategorizationStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptItemCategorizationRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.CategorizationResult
import com.yourname.expensetracker.domain.ai.model.CategorizedReceiptItem
import com.yourname.expensetracker.domain.ai.model.CategorySuggestion
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReceiptItemCategorizationService
import com.yourname.expensetracker.domain.dto.CategoryRef
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CategorizeReceiptItemsUseCaseTest {

    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var aiCapabilityRouter: AiCapabilityRouter
    private lateinit var aiArtifactRepository: AiArtifactRepository
    private lateinit var receiptRepository: ReceiptRepository
    private lateinit var receiptItemCategorizationRepository: ReceiptItemCategorizationRepository
    private lateinit var inputBuilder: ReceiptItemCategorizationInputBuilder
    private lateinit var onDeviceService: ReceiptItemCategorizationService
    private lateinit var cloudService: ReceiptItemCategorizationService
    private lateinit var useCase: CategorizeReceiptItemsUseCase

    private val timeProvider = FakeTimeProvider(1_000L)

    @Before
    fun setup() {
        aiSettingsRepository = mockk()
        aiCapabilityRouter = mockk()
        aiArtifactRepository = mockk(relaxed = true)
        receiptRepository = mockk(relaxed = true)
        receiptItemCategorizationRepository = mockk(relaxed = true)
        inputBuilder = mockk()
        onDeviceService = mockk()
        cloudService = mockk(relaxed = true)

        useCase = CategorizeReceiptItemsUseCase(
            aiSettingsRepository = aiSettingsRepository,
            aiCapabilityRouter = aiCapabilityRouter,
            aiArtifactRepository = aiArtifactRepository,
            receiptRepository = receiptRepository,
            receiptItemCategorizationRepository = receiptItemCategorizationRepository,
            inputBuilder = inputBuilder,
            onDeviceService = onDeviceService,
            cloudService = cloudService,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `invoke restores receipt status to pending when service returns null after analyzing`() = runTest {
        val receiptId = 42L
        val statuses = mutableListOf<CategorizationStatus>()
        val artifacts = mutableListOf<com.yourname.expensetracker.domain.dto.AiArtifactRecord>()
        val receipt = ScannedReceipt(
            id = receiptId,
            imagePath = null,
            rawOcrText = "milk",
            parsedTotal = 3.5,
            parsedMerchant = "Store",
            parsedDate = null,
            parsedItems = "[{\"description\":\"milk\",\"totalPrice\":3.5}]",
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.9f
        )
        val input = ReceiptItemCategorizationInput(
            receiptId = receiptId,
            merchant = "Store",
            lineItems = listOf(
                ReceiptParser.LineItem(
                    description = "milk",
                    quantity = 1.0,
                    unitPrice = 3.5,
                    totalPrice = 3.5
                )
            ),
            userCategories = listOf(CategoryRef(id = 1L, name = "Food")),
            totalTax = null,
            currency = "EUR"
        )

        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, receiptItemCategorizationEnabled = true)
        )
        coEvery { receiptItemCategorizationRepository.getByReceiptIdAsSnapshots(receiptId) } returns emptyList()
        coEvery { receiptRepository.getReceiptById(receiptId) } returns receipt
        coEvery { inputBuilder.build(receipt, any()) } returns input
        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_ITEM_CATEGORIZATION, any(), any())
        } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "local model available",
            providerName = "on-device",
            modelName = "nano"
        )
        coEvery { receiptRepository.updateCategorizationStatus(receiptId, capture(statuses)) } returns Unit
        coEvery { aiArtifactRepository.upsert(capture(artifacts)) } returns 1L
        coEvery { onDeviceService.categorizeItems(input) } returns null

        val result = useCase(receiptId)

        assertEquals(CategorizationResult.Error, result)
        assertEquals(
            listOf(CategorizationStatus.ANALYZING, CategorizationStatus.PENDING),
            statuses
        )
        assertEquals(listOf(AiArtifactStatus.RUNNING, AiArtifactStatus.FAILED), artifacts.map { it.status })
        assertTrue(artifacts.last().errorMessage?.contains("Service returned null") == true)
        coVerify(exactly = 0) { cloudService.categorizeItems(any()) }
    }

    // ── RP-12 12b (P3-007) conditional remainder: ephemeral item pass-through ──

    @Test
    fun `ephemeral items are passed to the input builder instead of the persisted representation`() = runTest {
        val receiptId = 42L
        val ephemeralItems = listOf(
            ReceiptParser.LineItem(
                description = "fresh milk",
                quantity = 1.0,
                unitPrice = 3.5,
                totalPrice = 3.5
            )
        )
        // Persisted representation under STORE_REDACTED: the redacted projection
        // (prices only) — permitted, but NOT the item source while ephemeral exists.
        val receipt = ScannedReceipt(
            id = receiptId,
            imagePath = null,
            rawOcrText = "milk",
            parsedTotal = 3.5,
            parsedMerchant = "Store",
            parsedDate = null,
            parsedItems = "{\"schema\":\"REDACTED_V1\",\"items\":[{\"totalPrice\":3.5,\"currency\":\"EUR\"}]}",
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.9f
        )
        val input = ReceiptItemCategorizationInput(
            receiptId = receiptId,
            merchant = "Store",
            lineItems = ephemeralItems,
            userCategories = listOf(CategoryRef(id = 1L, name = "Food")),
            totalTax = null,
            currency = "EUR"
        )
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, receiptItemCategorizationEnabled = true)
        )
        coEvery { receiptItemCategorizationRepository.getByReceiptIdAsSnapshots(receiptId) } returns emptyList()
        coEvery { receiptRepository.getReceiptById(receiptId) } returns receipt
        coEvery { inputBuilder.build(receipt, any(), ephemeralItems) } returns input
        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_ITEM_CATEGORIZATION, any(), any())
        } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "local model available",
            providerName = "on-device",
            modelName = "nano"
        )
        coEvery { onDeviceService.categorizeItems(input) } returns null

        val result = useCase(receiptId, ephemeralItems = ephemeralItems)

        // Service returned null → Error, but the item-source seam above is the pin.
        assertEquals(CategorizationResult.Error, result)
        coVerify(exactly = 1) { inputBuilder.build(receipt, any(), ephemeralItems) }
        coVerify(exactly = 0) { inputBuilder.build(receipt, any(), null) }
    }

    @Test
    fun `without ephemeral items the persisted representation remains the item source`() = runTest {
        val receiptId = 42L
        val receipt = ScannedReceipt(
            id = receiptId,
            imagePath = null,
            rawOcrText = "milk",
            parsedTotal = 3.5,
            parsedMerchant = "Store",
            parsedDate = null,
            parsedItems = "[{\"description\":\"milk\",\"totalPrice\":3.5}]",
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.9f
        )
        val input = ReceiptItemCategorizationInput(
            receiptId = receiptId,
            merchant = "Store",
            lineItems = listOf(
                ReceiptParser.LineItem(description = "milk", quantity = 1.0, unitPrice = 3.5, totalPrice = 3.5)
            ),
            userCategories = listOf(CategoryRef(id = 1L, name = "Food")),
            totalTax = null,
            currency = "EUR"
        )
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, receiptItemCategorizationEnabled = true)
        )
        coEvery { receiptItemCategorizationRepository.getByReceiptIdAsSnapshots(receiptId) } returns emptyList()
        coEvery { receiptRepository.getReceiptById(receiptId) } returns receipt
        coEvery { inputBuilder.build(receipt, any(), null) } returns input
        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_ITEM_CATEGORIZATION, any(), any())
        } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "local model available",
            providerName = "on-device",
            modelName = "nano"
        )
        coEvery { onDeviceService.categorizeItems(input) } returns null

        val result = useCase(receiptId)

        assertEquals(CategorizationResult.Error, result)
        // Plain call → the persisted representation (third argument defaults to null).
        coVerify(exactly = 1) { inputBuilder.build(receipt, any(), null) }
    }

    @Test
    fun `neither ephemeral nor persisted items short-circuits before the AI route`() = runTest {
        val receiptId = 42L
        val receipt = ScannedReceipt(
            id = receiptId,
            imagePath = null,
            rawOcrText = "milk",
            parsedTotal = 3.5,
            parsedMerchant = "Store",
            parsedDate = null,
            parsedItems = null, // STORE_METADATA_ONLY / DO_NOT_STORE persist nothing
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.9f
        )
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, receiptItemCategorizationEnabled = true)
        )
        coEvery { receiptItemCategorizationRepository.getByReceiptIdAsSnapshots(receiptId) } returns emptyList()
        coEvery { receiptRepository.getReceiptById(receiptId) } returns receipt

        val result = useCase(receiptId, ephemeralItems = null)

        assertEquals(CategorizationResult.Error, result)
        coVerify(exactly = 0) { inputBuilder.build(any(), any(), any()) }
        coVerify(exactly = 0) { aiCapabilityRouter.decide(any(), any(), any()) }
    }

    // ── RP-12 12b (P3-007): mode-gated OUTPUT persistence ───────────────────────
    // The ephemeral INPUT may feed categorization, but under a restricted storage
    // mode the OUTPUT must not re-persist item-name-derived content (rows,
    // rationale, artifact payload). The marker string below must appear in NO
    // persisted artifact under a restricted mode.

    /** Full AI output whose content derives from [marker] (services echo the name verbatim). */
    private fun fullResult(marker: String) = ReceiptItemCategorizationResult(
        items = listOf(
            CategorizedReceiptItem(
                itemDescription = marker,
                amount = 42.5,
                suggestedCategory = CategorySuggestion(categoryId = 1L, categoryName = "Food", confidence = 0.9f),
                confidence = 0.9f,
                rationale = "Matched to 'Food' based on keywords in '$marker'",
                alternatives = emptyList(),
                needsReview = false
            )
        ),
        totalConfidence = 0.9f,
        needsReview = false,
        suggestedNewCategories = listOf("SUGGESTED-FROM-$marker"),
        taxDistribution = mapOf(1L to 1.5)
    )

    @Test
    fun `restricted storage mode persists no item name derived content`() = runTest {
        val receiptId = 7L
        val marker = "SECRET-MILK-Zq7-42"
        val statuses = mutableListOf<CategorizationStatus>()
        val artifacts = mutableListOf<com.yourname.expensetracker.domain.dto.AiArtifactRecord>()
        val ephemeralItems = listOf(
            ReceiptParser.LineItem(description = marker, quantity = 1.0, unitPrice = 42.5, totalPrice = 42.5)
        )
        // Persisted representation under STORE_REDACTED: the redacted projection
        // (prices only) — never the item source while ephemeral exists.
        val receipt = ScannedReceipt(
            id = receiptId,
            imagePath = null,
            rawOcrText = "milk",
            parsedTotal = 42.5,
            parsedMerchant = "Store",
            parsedDate = null,
            parsedItems = "{\"schema\":\"REDACTED_V1\",\"items\":[{\"totalPrice\":42.5,\"currency\":\"EUR\"}]}",
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.9f
        )
        val input = ReceiptItemCategorizationInput(
            receiptId = receiptId,
            merchant = "Store",
            lineItems = ephemeralItems,
            userCategories = listOf(CategoryRef(id = 1L, name = "Food")),
            totalTax = null,
            currency = "EUR"
        )
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, receiptItemCategorizationEnabled = true)
        )
        coEvery { receiptItemCategorizationRepository.getByReceiptIdAsSnapshots(receiptId) } returns emptyList()
        coEvery { receiptRepository.getReceiptById(receiptId) } returns receipt
        coEvery { inputBuilder.build(receipt, any(), ephemeralItems) } returns input
        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_ITEM_CATEGORIZATION, any(), any())
        } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "local model available",
            providerName = "on-device",
            modelName = "nano"
        )
        coEvery { receiptRepository.updateCategorizationStatus(receiptId, capture(statuses)) } returns Unit
        coEvery { aiArtifactRepository.upsert(capture(artifacts)) } returns 1L
        coEvery { onDeviceService.categorizeItems(input) } returns fullResult(marker)

        val result = useCase(receiptId, ephemeralItems = ephemeralItems, persistItemTextAllowed = false)

        // The in-session return value (category assignments) stays intact.
        assertEquals(CategorizationResult.Success(fullResult(marker)), result)
        // Schema-forced no-rows path: ReceiptItemCategorization.itemDescription is
        // NOT NULL, so a restricted mode writes NO categorization rows at all.
        coVerify(exactly = 0) {
            receiptItemCategorizationRepository.saveCategorizationResult(any(), any(), any())
        }
        // Nothing was persisted — the receipt stays truthfully PENDING.
        assertEquals(
            listOf(CategorizationStatus.ANALYZING, CategorizationStatus.PENDING),
            statuses
        )
        // The READY artifact carries only the permitted projection: category
        // ids/names, confidences, counts — no description/amount/rationale, no
        // suggested new category names, no tax amounts.
        val ready = artifacts.last()
        assertEquals(AiArtifactStatus.READY, ready.status)
        val payload = ready.payloadJson.orEmpty()
        assertTrue("payloadJson leaked the item name: $payload", payload.contains(marker).not())
        assertTrue(payload.contains("\"description\"").not())
        assertTrue(payload.contains("\"amount\"").not())
        assertTrue(payload.contains("\"rationale\"").not())
        assertTrue(payload.contains("SUGGESTED-FROM-").not())
        assertTrue(payload.contains("\"taxDistribution\"").not())
        assertTrue("Permitted category projection missing: $payload", payload.contains("\"categoryName\""))
        assertTrue(ready.summaryText.orEmpty().contains(marker).not())
        assertTrue(ready.explanationText.orEmpty().contains(marker).not())
        assertTrue(ready.explanationText.orEmpty().contains("SUGGESTED-FROM-").not())
    }

    @Test
    fun `unrestricted storage mode keeps full output persistence unchanged`() = runTest {
        val receiptId = 8L
        val marker = "SECRET-MILK-Zq7-42"
        val statuses = mutableListOf<CategorizationStatus>()
        val artifacts = mutableListOf<com.yourname.expensetracker.domain.dto.AiArtifactRecord>()
        // STORE_RAW persisted representation: full parser JSON with item names.
        val receipt = ScannedReceipt(
            id = receiptId,
            imagePath = null,
            rawOcrText = "milk",
            parsedTotal = 42.5,
            parsedMerchant = "Store",
            parsedDate = null,
            parsedItems = "[{\"description\":\"$marker\",\"totalPrice\":42.5}]",
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.9f
        )
        val input = ReceiptItemCategorizationInput(
            receiptId = receiptId,
            merchant = "Store",
            lineItems = listOf(
                ReceiptParser.LineItem(description = marker, quantity = 1.0, unitPrice = 42.5, totalPrice = 42.5)
            ),
            userCategories = listOf(CategoryRef(id = 1L, name = "Food")),
            totalTax = null,
            currency = "EUR"
        )
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, receiptItemCategorizationEnabled = true)
        )
        coEvery { receiptItemCategorizationRepository.getByReceiptIdAsSnapshots(receiptId) } returns emptyList()
        coEvery { receiptRepository.getReceiptById(receiptId) } returns receipt
        coEvery { inputBuilder.build(receipt, any(), null) } returns input
        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_ITEM_CATEGORIZATION, any(), any())
        } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "local model available",
            providerName = "on-device",
            modelName = "nano"
        )
        coEvery { receiptRepository.updateCategorizationStatus(receiptId, capture(statuses)) } returns Unit
        coEvery { aiArtifactRepository.upsert(capture(artifacts)) } returns 1L
        coEvery { onDeviceService.categorizeItems(input) } returns fullResult(marker)
        val saved = slot<ReceiptItemCategorizationResult>()
        coEvery {
            receiptItemCategorizationRepository.saveCategorizationResult(receiptId, capture(saved), any())
        } returns 1

        val result = useCase(receiptId)

        assertEquals(CategorizationResult.Success(fullResult(marker)), result)
        // Full row persistence: description and rationale reach the repository.
        coVerify(exactly = 1) {
            receiptItemCategorizationRepository.saveCategorizationResult(receiptId, any(), any())
        }
        assertEquals(marker, saved.captured.items[0].itemDescription)
        assertEquals("Matched to 'Food' based on keywords in '$marker'", saved.captured.items[0].rationale)
        // READY status and the full artifact payload.
        assertEquals(
            listOf(CategorizationStatus.ANALYZING, CategorizationStatus.READY),
            statuses
        )
        val ready = artifacts.last()
        assertEquals(AiArtifactStatus.READY, ready.status)
        val payload = ready.payloadJson.orEmpty()
        assertTrue(payload.contains(marker))
        assertTrue(payload.contains("\"rationale\""))
        assertTrue(payload.contains("\"taxDistribution\""))
        assertTrue(payload.contains("SUGGESTED-FROM-$marker"))
    }
}
