package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.ReceiptItemCategorizationDao
import com.yourname.expensetracker.data.database.entity.CategorizationStatus
import com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization
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
import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.dto.CategoryRef
import com.yourname.expensetracker.domain.dto.ReceiptItemCategorizationSnapshot
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DR-09 follow-up: a reconciled discount adjustment (negative amount, isAdjustment = true) must
 * pass the use case validation on every route, and the deterministic fallback must carry the
 * flag so the repository's amount check does not reject the save. A negative amount WITHOUT the
 * adjustment flag stays invalid. Synthetic data only.
 */
class CategorizeReceiptItemsUseCaseAdjustmentTest {

    private val receiptId = 7L
    private val statuses = mutableListOf<CategorizationStatus>()
    private val artifacts = mutableListOf<AiArtifactRecord>()
    private val saved = mutableListOf<ReceiptItemCategorizationResult>()
    private val inserted = mutableListOf<ReceiptItemCategorization>()
    private val sentInputs = mutableListOf<ReceiptItemCategorizationInput>()

    private lateinit var mockedRepository: ReceiptItemCategorizationRepository
    private lateinit var router: AiCapabilityRouter

    private val categories = listOf(CategoryRef(id = 1L, name = "Groceries"), CategoryRef(id = 2L, name = "Shopping"))

    private val ten = ReceiptParser.LineItem("Item ten", null, null, 10.0)
    private val five = ReceiptParser.LineItem("Item five", null, null, 5.0)
    private val discount = ReceiptParser.LineItem("Discount", null, null, -3.0, isAdjustment = true)
    private val threeLines = listOf(ten, five, discount)

    private fun routeTo(route: AiRoute) {
        coEvery { router.decide(AiCapability.RECEIPT_ITEM_CATEGORIZATION, any(), any()) } returns AiRouteDecision(
            route = route, reason = route.name, providerName = "gemini", modelName = "m"
        )
    }

    private fun categorized(line: ReceiptParser.LineItem, amountOverride: Double? = null, adjustmentOverride: Boolean? = null) =
        CategorizedReceiptItem(
            itemDescription = line.description,
            amount = amountOverride ?: line.totalPrice,
            suggestedCategory = CategorySuggestion(categoryId = 1L, categoryName = "Groceries", confidence = 0.9f),
            confidence = 0.9f,
            rationale = "matches",
            alternatives = emptyList(),
            needsReview = false,
            isAdjustment = adjustmentOverride ?: line.isAdjustment
        )

    private fun resultOf(items: List<CategorizedReceiptItem>) = ReceiptItemCategorizationResult(
        items = items,
        totalConfidence = 0.9f,
        needsReview = false,
        suggestedNewCategories = emptyList(),
        taxDistribution = emptyMap()
    )

    private fun serviceReturning(result: ReceiptItemCategorizationResult): ReceiptItemCategorizationService {
        val service = mockk<ReceiptItemCategorizationService>()
        coEvery { service.categorizeItems(any()) } returns result
        return service
    }

    /** Answers every SENT line for real, echoing the line's own isAdjustment flag. */
    private fun echoingService(adjustmentOverride: Boolean? = null): ReceiptItemCategorizationService {
        val service = mockk<ReceiptItemCategorizationService>()
        coEvery { service.categorizeItems(any()) } coAnswers {
            val input = firstArg<ReceiptItemCategorizationInput>()
            sentInputs.add(input)
            resultOf(input.lineItems.map { categorized(it, adjustmentOverride = adjustmentOverride) })
        }
        return service
    }

    private fun realRepository(): ReceiptItemCategorizationRepository {
        val maintenance = mockk<RestoreMaintenanceMode>()
        every { maintenance.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenance.isWritesAllowed() } returns true
        val dao = mockk<ReceiptItemCategorizationDao>(relaxed = true)
        coEvery { dao.getByReceiptId(receiptId) } returns emptyList()
        coEvery { dao.insert(capture(inserted)) } returns 1L
        return ReceiptItemCategorizationRepository(DatabaseWriteBarrier(maintenance), dao)
    }

    private fun buildUseCase(
        lines: List<ReceiptParser.LineItem>,
        route: AiRoute,
        cloud: ReceiptItemCategorizationService = mockk(),
        repository: ReceiptItemCategorizationRepository? = null
    ): CategorizeReceiptItemsUseCase {
        val input = ReceiptItemCategorizationInput(
            receiptId = receiptId,
            merchant = "Test Store",
            lineItems = lines,
            userCategories = categories,
            totalTax = null,
            currency = "EUR"
        )
        val receipt = ScannedReceipt(
            id = receiptId,
            imagePath = null,
            rawOcrText = "synthetic",
            parsedTotal = 12.0,
            parsedMerchant = "Store",
            parsedDate = null,
            parsedItems = "[{\"description\":\"x\",\"totalPrice\":1.0}]",
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.9f
        )
        val aiSettingsRepository = mockk<AiSettingsRepository>()
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, receiptItemCategorizationEnabled = true)
        )
        router = mockk()
        routeTo(route)
        val artifactRepository = mockk<AiArtifactRepository>(relaxed = true)
        coEvery { artifactRepository.upsert(capture(artifacts)) } returns 1L
        val receiptRepository = mockk<ReceiptRepository>(relaxed = true)
        coEvery { receiptRepository.getReceiptById(receiptId) } returns receipt
        coEvery { receiptRepository.updateCategorizationStatus(receiptId, capture(statuses)) } returns Unit
        mockedRepository = mockk(relaxed = true)
        coEvery { mockedRepository.getByReceiptIdAsSnapshots(receiptId) } returns emptyList()
        coEvery {
            mockedRepository.saveCategorizationResult(any(), capture(saved), any())
        } coAnswers { saved.last().items.size }
        val inputBuilder = mockk<ReceiptItemCategorizationInputBuilder>()
        coEvery { inputBuilder.build(receipt, any()) } returns input

        return CategorizeReceiptItemsUseCase(
            aiSettingsRepository = aiSettingsRepository,
            aiCapabilityRouter = router,
            aiArtifactRepository = artifactRepository,
            receiptRepository = receiptRepository,
            receiptItemCategorizationRepository = repository ?: mockedRepository,
            inputBuilder = inputBuilder,
            onDeviceService = mockk(),
            cloudService = cloud,
            timeProvider = FakeTimeProvider(1_000L)
        )
    }

    // ---- (a) provider route accepts the adjustment ----

    @Test
    fun `adjustment item with negative amount passes validation and the result is saved`() = runTest {
        val result = resultOf(threeLines.map { categorized(it) })
        val useCase = buildUseCase(threeLines, AiRoute.CLOUD, cloud = serviceReturning(result))

        val outcome = useCase(receiptId)

        assertTrue("was $outcome", outcome is CategorizationResult.Success)
        assertEquals(result, (outcome as CategorizationResult.Success).result)
        coVerify(exactly = 1) { mockedRepository.saveCategorizationResult(receiptId, any(), any()) }
        assertEquals(listOf(10.0, 5.0, -3.0), saved.single().items.map { it.amount })
        assertEquals(listOf(CategorizationStatus.ANALYZING, CategorizationStatus.READY), statuses)
        assertEquals(listOf(AiArtifactStatus.RUNNING, AiArtifactStatus.READY), artifacts.map { it.status })
    }

    // ---- (b) deterministic fallback keeps the flag ----

    @Test
    fun `fallback route keeps isAdjustment on the discount row and only there`() = runTest {
        val useCase = buildUseCase(threeLines, AiRoute.DETERMINISTIC_FALLBACK)

        val outcome = useCase(receiptId)

        assertTrue("was $outcome", outcome is CategorizationResult.Success)
        val items = saved.single().items
        assertEquals(listOf(10.0, 5.0, -3.0), items.map { it.amount })
        assertEquals(listOf(false, false, true), items.map { it.isAdjustment })
        assertEquals(listOf(CategorizationStatus.ANALYZING, CategorizationStatus.READY), statuses)
    }

    @Test
    fun `fallback route saves through the real repository without RECEIPT_ITEM_ADJUSTMENT_UNCONFIRMED`() = runTest {
        val useCase = buildUseCase(threeLines, AiRoute.DETERMINISTIC_FALLBACK, repository = realRepository())

        val outcome = useCase(receiptId)

        assertTrue("was $outcome", outcome is CategorizationResult.Success)
        assertEquals(listOf(10.0, 5.0, -3.0), inserted.map { it.itemAmount })
        assertEquals(listOf(CategorizationStatus.ANALYZING, CategorizationStatus.READY), statuses)
        assertFalse(artifacts.any { it.status == AiArtifactStatus.FAILED })
    }

    @Test
    fun `real repository still rejects a negative amount that is not an adjustment`() = runTest {
        val repository = realRepository()
        val bad = resultOf(listOf(categorized(ten), categorized(discount, adjustmentOverride = false)))

        val failure = runCatching { repository.saveCategorizationResult(receiptId, bad, 1_000L) }.exceptionOrNull()

        assertTrue("was $failure", failure is IllegalArgumentException)
        assertEquals("RECEIPT_ITEM_ADJUSTMENT_UNCONFIRMED", failure!!.message)
        assertTrue(inserted.isEmpty())
    }

    // ---- (c) a non-adjustment negative amount is still rejected ----

    @Test
    fun `negative amount without the adjustment flag fails categorization and saves nothing`() = runTest {
        val result = resultOf(listOf(categorized(ten), categorized(five), categorized(discount, adjustmentOverride = false)))
        val useCase = buildUseCase(threeLines, AiRoute.CLOUD, cloud = serviceReturning(result))

        val outcome = useCase(receiptId)

        assertEquals(CategorizationResult.Error, outcome)
        coVerify(exactly = 0) { mockedRepository.saveCategorizationResult(any(), any(), any()) }
        assertEquals(listOf(CategorizationStatus.ANALYZING, CategorizationStatus.PENDING), statuses)
        assertEquals(AiArtifactStatus.FAILED, artifacts.last().status)
        assertEquals("Item 2 has invalid amount: -3.0", artifacts.last().errorMessage)
    }

    @Test
    fun `zero amount without the adjustment flag is still rejected`() = runTest {
        val result = resultOf(listOf(categorized(ten), categorized(five, amountOverride = 0.0)))
        val useCase = buildUseCase(listOf(ten, five), AiRoute.CLOUD, cloud = serviceReturning(result))

        val outcome = useCase(receiptId)

        assertEquals(CategorizationResult.Error, outcome)
        coVerify(exactly = 0) { mockedRepository.saveCategorizationResult(any(), any(), any()) }
        assertEquals(AiArtifactStatus.FAILED, artifacts.last().status)
    }

    @Test
    fun `adjustment flag does not excuse a non finite amount`() = runTest {
        val result = resultOf(
            listOf(categorized(ten), categorized(five), categorized(discount, amountOverride = Double.NaN))
        )
        val useCase = buildUseCase(threeLines, AiRoute.CLOUD, cloud = serviceReturning(result))

        val outcome = useCase(receiptId)

        assertEquals(CategorizationResult.Error, outcome)
        coVerify(exactly = 0) { mockedRepository.saveCategorizationResult(any(), any(), any()) }
        assertEquals(AiArtifactStatus.FAILED, artifacts.last().status)
    }

    // ---- (d) retry path through mergedResultFromStoredRows ----

    private fun row(
        id: Long,
        description: String,
        amount: Double,
        real: Boolean
    ) = ReceiptItemCategorizationSnapshot(
        id = id, receiptId = receiptId, expenseId = null, itemDescription = description, itemAmount = amount,
        suggestedCategoryId = if (real) 1L else null,
        suggestedCategoryName = if (real) "Groceries" else null,
        confidence = if (real) 0.9f else 0f,
        aiRationale = if (real) "matches" else "",
        alternativeCategoriesJson = "[]",
        userCorrectedCategoryId = null, userCorrectedCategoryName = null, userCorrectedAt = null,
        taxAmount = null, isNewCategorySuggestion = false, createdAt = 1L, updatedAt = 1L
    )

    @Test
    fun `retry with a stored negative discount row returns Success and merges it without throwing`() = runTest {
        val before = listOf(
            row(1L, "Item ten", 10.0, real = true),
            row(2L, "Item five", 5.0, real = false),
            row(3L, "Discount", -3.0, real = false)
        )
        val after = before.map { row(it.id, it.itemDescription, it.itemAmount, real = true) }
        val useCase = buildUseCase(threeLines, AiRoute.CLOUD, cloud = echoingService())
        coEvery { mockedRepository.getByReceiptIdAsSnapshots(receiptId) } returnsMany listOf(before, after)
        coEvery {
            mockedRepository.updatePlaceholderRowsWithRetryResult(receiptId, any(), any(), any())
        } returns 2

        val outcome = useCase(receiptId, retryUncategorized = true)

        assertTrue("was $outcome", outcome is CategorizationResult.Success)
        assertEquals(listOf("Item five", "Discount"), sentInputs.single().lineItems.map { it.description })
        assertEquals(
            listOf(10.0, 5.0, -3.0),
            (outcome as CategorizationResult.Success).result.items.map { it.amount }
        )
        coVerify(exactly = 1) {
            mockedRepository.updatePlaceholderRowsWithRetryResult(receiptId, listOf(2L, 3L), any(), any())
        }
        assertEquals(listOf(CategorizationStatus.READY), statuses)
        assertEquals(AiArtifactStatus.READY, artifacts.last().status)
    }

    @Test
    fun `retry whose answer drops the adjustment flag on a negative row is an Error and writes nothing`() = runTest {
        val before = listOf(
            row(1L, "Item ten", 10.0, real = true),
            row(2L, "Item five", 5.0, real = false),
            row(3L, "Discount", -3.0, real = false)
        )
        val useCase = buildUseCase(threeLines, AiRoute.CLOUD, cloud = echoingService(adjustmentOverride = false))
        coEvery { mockedRepository.getByReceiptIdAsSnapshots(receiptId) } returns before

        val outcome = useCase(receiptId, retryUncategorized = true)

        assertEquals(CategorizationResult.Error, outcome)
        coVerify(exactly = 0) { mockedRepository.updatePlaceholderRowsWithRetryResult(any(), any(), any(), any()) }
        assertTrue(statuses.isEmpty())
        assertTrue(artifacts.isEmpty())
    }
}
