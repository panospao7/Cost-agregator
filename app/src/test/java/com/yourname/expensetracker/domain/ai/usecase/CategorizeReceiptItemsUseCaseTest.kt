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
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
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
}
