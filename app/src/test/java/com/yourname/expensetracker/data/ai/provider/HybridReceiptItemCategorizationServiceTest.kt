package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.dto.CategoryRef
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HybridReceiptItemCategorizationServiceTest {

    // TODO: Tautological mock test — consider adding real behavior assertion
    @Test
    fun `categorizeItems uses on-device and never calls cloud when router selects on-device`() = runTest {
        val onDeviceService = mockk<OnDeviceReceiptItemCategorizationService>()
        val cloudService = mockk<CloudReceiptItemCategorizationService>()
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        val aiCapabilityRouter = mockk<AiCapabilityRouter>()

        val settings = AiSettings(
            aiEnabled = true,
            receiptItemCategorizationEnabled = true,
            allowCloudAi = true,
            allowOnDeviceAi = true
        )
        every { aiSettingsRepository.settings() } returns flowOf(settings)

        coEvery {
            aiCapabilityRouter.decide(AiCapability.RECEIPT_ITEM_CATEGORIZATION, settings, any())
        } returns AiRouteDecision(
            route = AiRoute.ON_DEVICE,
            reason = "On-device forced for current context"
        )

        val expected = ReceiptItemCategorizationResult(
            items = emptyList(),
            totalConfidence = 0.8f,
            needsReview = false,
            suggestedNewCategories = emptyList(),
            taxDistribution = emptyMap()
        )
        coEvery { onDeviceService.categorizeItems(any()) } returns expected

        val service = HybridReceiptItemCategorizationService(
            onDeviceService = onDeviceService,
            cloudService = cloudService,
            aiSettingsRepository = aiSettingsRepository,
            aiCapabilityRouter = aiCapabilityRouter
        )

        val input = ReceiptItemCategorizationInput(
            receiptId = 42L,
            merchant = "LIDL",
            lineItems = listOf(
                ReceiptParser.LineItem(
                    description = "Milk",
                    quantity = 1.0,
                    unitPrice = 1.20,
                    totalPrice = 1.20
                )
            ),
            userCategories = listOf(
                CategoryRef(
                    id = 1L,
                    name = "Groceries"
                )
            ),
            totalTax = 0.24,
            currency = "EUR"
        )

        val result = service.categorizeItems(input)

        assertEquals(expected, result)
        coVerify(exactly = 1) { onDeviceService.categorizeItems(input) }
        coVerify(exactly = 0) { cloudService.categorizeItems(any()) }
    }
}
