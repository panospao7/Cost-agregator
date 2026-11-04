package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.ai.provider.CloudReceiptItemCategorizationService
import com.yourname.expensetracker.data.database.entity.CategorizationStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.data.provider.MerchantCategoryProvider
import com.yourname.expensetracker.data.repository.ReceiptItemCategorizationRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.security.SecureKeyStorage
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
import com.yourname.expensetracker.domain.ai.model.ReceiptItemPlaceholder
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReceiptItemCategorizationService
import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.dto.CategoryRef
import com.yourname.expensetracker.domain.dto.ReceiptItemCategorizationSnapshot
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receipt status after a cloud call that sent only part of the items, using the REAL
 * [CloudReceiptItemCategorizationService] (real policy and redactor config, fake transport) and
 * the REAL use case validation. Repositories are mocks; the status writes are captured.
 */
class CategorizeReceiptItemsUseCasePartialResultTest {

    private val receiptId = 42L
    private val statuses = mutableListOf<CategorizationStatus>()
    private val artifacts = mutableListOf<AiArtifactRecord>()
    private val saved = mutableListOf<ReceiptItemCategorizationResult>()
    private val transportCalls = AtomicInteger(0)

    private lateinit var categorizationRepository: ReceiptItemCategorizationRepository
    private lateinit var useCase: CategorizeReceiptItemsUseCase

    private fun name(index: Int) = "Product " + ('a' + index % 26) + ('a' + index / 26)

    private fun setUp(
        itemCount: Int,
        blankDescriptionAt: Set<Int> = emptySet(),
        serviceOverride: ReceiptItemCategorizationService? = null
    ) {
        val categories = MerchantCategoryProvider.categoryBlueprints
            .mapIndexed { i, c -> CategoryRef(id = (i + 1).toLong(), name = c.name) }
        val input = ReceiptItemCategorizationInput(
            receiptId = receiptId,
            merchant = "Test Store",
            lineItems = (0 until itemCount).map {
                ReceiptParser.LineItem(description = name(it), quantity = null, unitPrice = null, totalPrice = 1.0)
            },
            userCategories = categories,
            totalTax = null,
            currency = "EUR"
        )
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

        val aiSettingsRepository = mockk<AiSettingsRepository>()
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, receiptItemCategorizationEnabled = true)
        )
        val router = mockk<AiCapabilityRouter>()
        coEvery { router.decide(AiCapability.RECEIPT_ITEM_CATEGORIZATION, any(), any()) } returns AiRouteDecision(
            route = AiRoute.CLOUD, reason = "cloud", providerName = "gemini", modelName = "m"
        )
        val artifactRepository = mockk<AiArtifactRepository>(relaxed = true)
        coEvery { artifactRepository.upsert(capture(artifacts)) } returns 1L
        val receiptRepository = mockk<ReceiptRepository>(relaxed = true)
        coEvery { receiptRepository.getReceiptById(receiptId) } returns receipt
        coEvery { receiptRepository.updateCategorizationStatus(receiptId, capture(statuses)) } returns Unit
        categorizationRepository = mockk(relaxed = true)
        coEvery { categorizationRepository.getByReceiptIdAsSnapshots(receiptId) } returns emptyList()
        coEvery {
            categorizationRepository.saveCategorizationResult(any(), capture(saved), any())
        } coAnswers { saved.last().items.size }
        val inputBuilder = mockk<ReceiptItemCategorizationInputBuilder>()
        coEvery { inputBuilder.build(receipt, any()) } returns input

        useCase = CategorizeReceiptItemsUseCase(
            aiSettingsRepository = aiSettingsRepository,
            aiCapabilityRouter = router,
            aiArtifactRepository = artifactRepository,
            receiptRepository = receiptRepository,
            receiptItemCategorizationRepository = categorizationRepository,
            inputBuilder = inputBuilder,
            onDeviceService = mockk<ReceiptItemCategorizationService>(),
            cloudService = serviceOverride ?: realCloudService(blankDescriptionAt),
            timeProvider = FakeTimeProvider(1_000L)
        )
    }

    private fun realCloudService(blankDescriptionAt: Set<Int>): CloudReceiptItemCategorizationService {
        val privacy = PrivacySettings(cloudAiEnabled = true, redactBeforeCloud = false)
        val privacyRepo = mockk<PrivacySettingsRepository>(relaxed = true)
        coEvery { privacyRepo.getSettings() } returns privacy
        every { privacyRepo.observeSettings() } returns flowOf(privacy)
        val aiRepo = mockk<AiSettingsRepository>(relaxed = true)
        every { aiRepo.settings() } returns flowOf(AiSettings(allowCloudAi = true, redactBeforeCloud = false))
        val policy = DefaultCloudPayloadPolicy(EffectiveCloudAiPolicyResolver(privacyRepo, aiRepo), DefaultCloudPayloadRedactor())
        val keys = mockk<SecureKeyStorage>(relaxed = true)
        every { keys.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"
        val gate = object : PrivacyGate {
            override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
                PrivacyDecision.Allowed
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                transportCalls.incrementAndGet()
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                val prompt = JSONObject(buffer.readUtf8())
                    .getJSONArray("contents").getJSONObject(0)
                    .getJSONArray("parts").getJSONObject(0).getString("text")
                val indices = Regex("""\[sourceIndex=(\d+)]""").findAll(prompt).map { it.groupValues[1].toInt() }
                val array = JSONArray()
                indices.forEach { index ->
                    array.put(
                        JSONObject().put("sourceIndex", index)
                            .put("description", if (index in blankDescriptionAt) "" else "echo")
                            .put("amount", 1.0).put("categoryId", 1).put("categoryName", "Groceries")
                            .put("confidence", 0.9).put("rationale", "matches")
                            .put("isNewCategorySuggestion", false).put("alternatives", JSONArray())
                    )
                }
                val modelText = JSONObject().put("items", array).put("suggestedNewCategories", JSONArray()).toString()
                val envelope = JSONObject().put(
                    "candidates",
                    JSONArray().put(
                        JSONObject().put(
                            "content",
                            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", modelText)))
                        )
                    )
                ).toString()
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(envelope.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return CloudReceiptItemCategorizationService(keys, client, gate, policy)
    }

    @Test
    fun `all items sent gives READY`() = runTest {
        setUp(itemCount = 10)

        val result = useCase(receiptId)

        assertTrue("was $result", result is CategorizationResult.Success)
        assertEquals(listOf(CategorizationStatus.ANALYZING, CategorizationStatus.READY), statuses)
        assertEquals(10, saved.single().items.size)
        assertTrue(saved.single().items.none { ReceiptItemPlaceholder.isPlaceholder(it) })
    }

    @Test
    fun `partial send gives PENDING not READY with the rows saved`() = runTest {
        setUp(itemCount = 60)

        val result = useCase(receiptId)

        assertTrue("was $result", result is CategorizationResult.Success)
        assertEquals(listOf(CategorizationStatus.ANALYZING, CategorizationStatus.PENDING), statuses)
        assertFalse(statuses.contains(CategorizationStatus.READY))
        coVerify(exactly = 1) { categorizationRepository.saveCategorizationResult(any(), any(), any()) }
        assertEquals(60, saved.single().items.size)
        assertEquals(35, saved.single().items.count { ReceiptItemPlaceholder.isPlaceholder(it) })
        assertEquals(listOf(AiArtifactStatus.RUNNING, AiArtifactStatus.READY), artifacts.map { it.status })
    }

    /**
     * CHARACTERISATION of current behaviour, not a requirement: once rows exist (even for a
     * partial result left PENDING) a later non-forced invoke returns AlreadyAnalyzed and makes no
     * transport call. A follow-up step that lets a partial receipt be completed will change this;
     * update this pin then.
     */
    @Test
    fun `characterisation - a later invoke after a partial run returns AlreadyAnalyzed without a transport call`() = runTest {
        setUp(itemCount = 60)
        useCase(receiptId)
        assertEquals(1, transportCalls.get())

        coEvery { categorizationRepository.getByReceiptIdAsSnapshots(receiptId) } returns listOf(
            ReceiptItemCategorizationSnapshot(
                id = 1L, receiptId = receiptId, expenseId = null, itemDescription = name(0), itemAmount = 1.0,
                suggestedCategoryId = 1L, suggestedCategoryName = "Groceries", confidence = 0.9f,
                aiRationale = "matches", alternativeCategoriesJson = null, userCorrectedCategoryId = null,
                userCorrectedCategoryName = null, userCorrectedAt = null, taxAmount = null,
                isNewCategorySuggestion = false, createdAt = 1L, updatedAt = 1L
            )
        )

        val second = useCase(receiptId)

        assertTrue("was $second", second is CategorizationResult.AlreadyAnalyzed)
        assertEquals(1, transportCalls.get())
    }

    private fun realItem(index: Int) = CategorizedReceiptItem(
        itemDescription = name(index),
        amount = 1.0,
        suggestedCategory = CategorySuggestion(categoryId = 1L, categoryName = "Groceries", confidence = 0.9f),
        confidence = 0.9f,
        rationale = "matches",
        alternatives = emptyList(),
        needsReview = false
    )

    private fun placeholderItem(index: Int) = CategorizedReceiptItem(
        itemDescription = name(index),
        amount = 1.0,
        suggestedCategory = null,
        confidence = 0f,
        rationale = ReceiptItemPlaceholder.PLACEHOLDER_RATIONALE,
        alternatives = emptyList(),
        needsReview = true
    )

    private fun serviceReturning(realCount: Int, total: Int): ReceiptItemCategorizationService {
        val service = mockk<ReceiptItemCategorizationService>()
        coEvery { service.categorizeItems(any()) } returns ReceiptItemCategorizationResult(
            items = (0 until total).map { if (it < realCount) realItem(it) else placeholderItem(it) },
            totalConfidence = 0.9f,
            needsReview = realCount < total,
            suggestedNewCategories = emptyList(),
            taxDistribution = emptyMap()
        )
        return service
    }

    @Test
    fun `summary counts only real results on a partial result - 2 real and 3 placeholders`() = runTest {
        setUp(itemCount = 5, serviceOverride = serviceReturning(realCount = 2, total = 5))

        val result = useCase(receiptId)

        assertTrue("was $result", result is CategorizationResult.Success)
        assertEquals(AiArtifactStatus.READY, artifacts.last().status)
        assertEquals("Categorized 2 of 5 items", artifacts.last().summaryText)
    }

    @Test
    fun `needs-review count excludes placeholders - 25 clean items and 35 placeholders`() = runTest {
        setUp(itemCount = 60, serviceOverride = serviceReturning(realCount = 25, total = 60))

        val result = useCase(receiptId)

        assertTrue("was $result", result is CategorizationResult.Success)
        val explanation = artifacts.last().explanationText.orEmpty()
        assertTrue(explanation, explanation.contains("Categorized 25 of 60 receipt items"))
        assertFalse(explanation, explanation.contains("items need review"))
    }

    @Test
    fun `summary of a full result is Categorized 5 items`() = runTest {
        setUp(itemCount = 5, serviceOverride = serviceReturning(realCount = 5, total = 5))

        val result = useCase(receiptId)

        assertTrue("was $result", result is CategorizationResult.Success)
        assertEquals(AiArtifactStatus.READY, artifacts.last().status)
        assertEquals("Categorized 5 items", artifacts.last().summaryText)
    }

    @Test
    fun `summary of the not persisted variant keeps the counts and the storage mode suffix`() = runTest {
        setUp(itemCount = 5, serviceOverride = serviceReturning(realCount = 2, total = 5))

        val partial = useCase(receiptId, persistItemTextAllowed = false)

        assertTrue("was $partial", partial is CategorizationResult.Success)
        assertEquals(
            "Categorized 2 of 5 items (not persisted for storage mode)",
            artifacts.last().summaryText
        )
    }

    @Test
    fun `summary of a full result in the not persisted variant has no of-total wording`() = runTest {
        setUp(itemCount = 5, serviceOverride = serviceReturning(realCount = 5, total = 5))

        val full = useCase(receiptId, persistItemTextAllowed = false)

        assertTrue("was $full", full is CategorizationResult.Success)
        assertEquals(
            "Categorized 5 items (not persisted for storage mode)",
            artifacts.last().summaryText
        )
    }

    @Test
    fun `one blank echoed description does not fail the run through validateResult`() = runTest {
        setUp(itemCount = 3, blankDescriptionAt = setOf(1))

        val result = useCase(receiptId)

        assertTrue("was $result", result is CategorizationResult.Success)
        assertEquals(listOf(CategorizationStatus.ANALYZING, CategorizationStatus.READY), statuses)
        assertEquals(name(1), saved.single().items[1].itemDescription)
        assertNotEquals(AiArtifactStatus.FAILED, artifacts.last().status)
    }
}
