package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.ai.provider.CloudReceiptItemCategorizationService
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
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
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.CancellationException
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
    private val prompts = mutableListOf<String>()

    private lateinit var categorizationRepository: ReceiptItemCategorizationRepository
    private lateinit var useCase: CategorizeReceiptItemsUseCase
    private lateinit var router: AiCapabilityRouter

    private fun routeTo(route: AiRoute) {
        coEvery { router.decide(AiCapability.RECEIPT_ITEM_CATEGORIZATION, any(), any()) } returns AiRouteDecision(
            route = route, reason = route.name, providerName = "gemini", modelName = "m"
        )
    }

    private fun name(index: Int) = "Product " + ('a' + index % 26) + ('a' + index / 26)

    private fun setUp(
        itemCount: Int,
        blankDescriptionAt: Set<Int> = emptySet(),
        serviceOverride: ReceiptItemCategorizationService? = null,
        totalTax: Double? = null,
        route: AiRoute = AiRoute.CLOUD
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
            totalTax = totalTax,
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
        router = mockk<AiCapabilityRouter>()
        routeTo(route)
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
                prompts.add(prompt)
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

    // ---- N-05-3 step 2: retryUncategorized completes a partial result ----

    private var stored: List<ReceiptItemCategorizationSnapshot> = emptyList()
    private val retryWrites = mutableListOf<List<Long>>()
    private val retryWrittenItems = mutableListOf<List<CategorizedReceiptItem>>()
    private val sentInputs = mutableListOf<ReceiptItemCategorizationInput>()

    private fun storedRow(
        index: Int,
        real: Boolean,
        id: Long = index + 1L,
        userCorrected: Boolean = false,
        amount: Double = 1.0
    ) = ReceiptItemCategorizationSnapshot(
        id = id, receiptId = receiptId, expenseId = null, itemDescription = name(index), itemAmount = amount,
        suggestedCategoryId = if (real) 1L else null,
        suggestedCategoryName = if (real) "Groceries" else null,
        confidence = if (real) 0.9f else 0f,
        aiRationale = if (real) "matches" else "",
        alternativeCategoriesJson = "[]",
        userCorrectedCategoryId = if (userCorrected) 9L else null,
        userCorrectedCategoryName = if (userCorrected) "Mine" else null,
        userCorrectedAt = if (userCorrected) 77L else null,
        taxAmount = null, isNewCategorySuggestion = false, createdAt = 1L, updatedAt = 1L
    )

    /** Stateful stand-in for the repository: stored rows are read back after a retry write. */
    private fun installStored(rows: List<ReceiptItemCategorizationSnapshot>) {
        stored = rows
        coEvery { categorizationRepository.getByReceiptIdAsSnapshots(receiptId) } answers { stored }
        coEvery {
            categorizationRepository.updatePlaceholderRowsWithRetryResult(receiptId, any(), any(), any())
        } coAnswers {
            val ids = arg<List<Long>>(1)
            val items = arg<List<CategorizedReceiptItem>>(2)
            retryWrites.add(ids)
            retryWrittenItems.add(items)
            var updated = 0
            stored = stored.map { row ->
                val i = ids.indexOf(row.id)
                val untouched = row.suggestedCategoryId == null && row.userCorrectedCategoryId == null
                if (i >= 0 && untouched) {
                    updated++
                    row.copy(
                        suggestedCategoryId = items[i].suggestedCategory?.categoryId,
                        suggestedCategoryName = items[i].suggestedCategory?.categoryName,
                        confidence = items[i].confidence,
                        aiRationale = items[i].rationale
                    )
                } else {
                    row
                }
            }
            updated
        }
    }

    /** Answers the first [answerCount] sent items for real (all when null), placeholders for the rest. */
    private fun answeringService(answerCount: Int? = null): ReceiptItemCategorizationService {
        val service = mockk<ReceiptItemCategorizationService>()
        coEvery { service.categorizeItems(any()) } coAnswers {
            val input = firstArg<ReceiptItemCategorizationInput>()
            sentInputs.add(input)
            ReceiptItemCategorizationResult(
                items = input.lineItems.mapIndexed { i, line ->
                    val real = answerCount == null || i < answerCount
                    CategorizedReceiptItem(
                        itemDescription = line.description,
                        amount = line.totalPrice,
                        suggestedCategory = if (real) {
                            CategorySuggestion(categoryId = 1L, categoryName = "Groceries", confidence = 0.9f)
                        } else {
                            null
                        },
                        confidence = if (real) 0.9f else 0f,
                        rationale = if (real) "matches" else ReceiptItemPlaceholder.PLACEHOLDER_RATIONALE,
                        alternatives = emptyList(),
                        needsReview = !real
                    )
                },
                totalConfidence = 0.9f,
                needsReview = answerCount != null,
                suggestedNewCategories = emptyList(),
                taxDistribution = emptyMap()
            )
        }
        return service
    }

    private fun verifyNothingWritten() {
        coVerify(exactly = 0) { categorizationRepository.updatePlaceholderRowsWithRetryResult(any(), any(), any(), any()) }
        coVerify(exactly = 0) { categorizationRepository.saveCategorizationResult(any(), any(), any()) }
        coVerify(exactly = 0) { categorizationRepository.deleteByReceiptId(any()) }
        assertTrue(artifacts.isEmpty())
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `retry off by default returns AlreadyAnalyzed with no cloud call for an analysed receipt`() = runTest {
        setUp(itemCount = 5)
        val rows = listOf(storedRow(0, real = true), storedRow(1, real = false), storedRow(2, real = false),
            storedRow(3, real = true), storedRow(4, real = true))
        installStored(rows)

        val result = useCase(receiptId)

        assertTrue("was $result", result is CategorizationResult.AlreadyAnalyzed)
        assertEquals(rows, (result as CategorizationResult.AlreadyAnalyzed).items)
        assertEquals(0, transportCalls.get())
        verifyNothingWritten()
    }

    @Test
    fun `retry on 60 items with 25 real sends only placeholders in one call and a second retry reaches READY`() = runTest {
        setUp(itemCount = 60)
        val initial = (0 until 60).map { storedRow(it, real = it < 25) }
        installStored(initial)

        val first = useCase(receiptId, retryUncategorized = true)

        assertTrue("was $first", first is CategorizationResult.Success)
        assertEquals(1, transportCalls.get())
        val firstPrompt = prompts.single()
        (0 until 25).forEach { assertFalse("real item $it was sent", firstPrompt.contains(name(it))) }
        assertTrue(firstPrompt.contains(name(25)))
        val firstWrite = retryWrites.single()
        assertTrue("wrote ${firstWrite.size}", firstWrite.size in 1..25)
        assertTrue(firstWrite.all { it in 26L..60L })
        assertEquals(initial.take(25), stored.take(25))
        coVerify(exactly = 0) { categorizationRepository.saveCategorizationResult(any(), any(), any()) }
        assertEquals(listOf(CategorizationStatus.PENDING), statuses)
        assertEquals(
            "Categorized ${25 + firstWrite.size} of 60 items",
            artifacts.last().summaryText
        )

        val second = useCase(receiptId, retryUncategorized = true)

        assertTrue("was $second", second is CategorizationResult.Success)
        assertEquals(2, transportCalls.get())
        assertEquals(35, firstWrite.size + retryWrites[1].size)
        assertEquals(listOf(CategorizationStatus.PENDING, CategorizationStatus.READY), statuses)
        assertEquals(AiArtifactStatus.READY, artifacts.last().status)
        assertEquals("Categorized 60 items", artifacts.last().summaryText)
        assertTrue(stored.none { ReceiptItemPlaceholder.isPlaceholder(it) })
    }

    @Test
    fun `retry sends no tax figure and no ANALYZING status`() = runTest {
        setUp(itemCount = 5, serviceOverride = answeringService(), totalTax = 3.5)
        installStored((0 until 5).map { storedRow(it, real = it == 0) })

        val result = useCase(receiptId, retryUncategorized = true)

        assertTrue("was $result", result is CategorizationResult.Success)
        assertEquals(1, sentInputs.size)
        assertEquals(null, sentInputs.single().totalTax)
        assertFalse(statuses.contains(CategorizationStatus.ANALYZING))
        assertEquals(listOf(CategorizationStatus.READY), statuses)
    }

    @Test
    fun `retry with an index gap writes each answer to the right row id`() = runTest {
        setUp(itemCount = 5, serviceOverride = answeringService())
        // row ids deliberately differ from item positions; index 2 is a real row among placeholders
        val rows = listOf(
            storedRow(0, real = false, id = 100L), storedRow(1, real = false, id = 101L),
            storedRow(2, real = true, id = 102L),
            storedRow(3, real = false, id = 103L), storedRow(4, real = false, id = 104L)
        )
        installStored(rows)

        val result = useCase(receiptId, retryUncategorized = true)

        assertTrue("was $result", result is CategorizationResult.Success)
        assertEquals(listOf(name(0), name(1), name(3), name(4)), sentInputs.single().lineItems.map { it.description })
        assertEquals(listOf(100L, 101L, 103L, 104L), retryWrites.single())
        assertEquals(
            listOf(name(0), name(1), name(3), name(4)),
            retryWrittenItems.single().map { it.itemDescription }
        )
        assertEquals(rows[2], stored[2])
        assertEquals(listOf(CategorizationStatus.READY), statuses)
    }

    @Test
    fun `retry leaving placeholders gives PENDING with Categorized X of Y`() = runTest {
        setUp(itemCount = 5, serviceOverride = answeringService(answerCount = 2))
        installStored((0 until 5).map { storedRow(it, real = it == 0) })

        val result = useCase(receiptId, retryUncategorized = true)

        assertTrue("was $result", result is CategorizationResult.Success)
        assertEquals(listOf(CategorizationStatus.PENDING), statuses)
        assertFalse(statuses.contains(CategorizationStatus.READY))
        assertEquals(AiArtifactStatus.READY, artifacts.last().status)
        assertEquals("Categorized 3 of 5 items", artifacts.last().summaryText)
        assertEquals(listOf(2L, 3L), retryWrites.single())
    }

    @Test
    fun `failed retry by exception writes nothing and keeps earlier results`() = runTest {
        val failing = mockk<ReceiptItemCategorizationService>()
        coEvery { failing.categorizeItems(any()) } throws java.io.IOException("synthetic transport failure")
        setUp(itemCount = 5, serviceOverride = failing)
        val rows = (0 until 5).map { storedRow(it, real = it < 2) }
        installStored(rows)

        val result = useCase(receiptId, retryUncategorized = true)

        assertEquals(CategorizationResult.Error, result)
        verifyNothingWritten()
        assertEquals(rows, stored)
    }

    @Test
    fun `failed retry where the service answers nothing real writes nothing`() = runTest {
        setUp(itemCount = 5, serviceOverride = answeringService(answerCount = 0))
        val rows = (0 until 5).map { storedRow(it, real = it < 2) }
        installStored(rows)

        val result = useCase(receiptId, retryUncategorized = true)

        assertEquals(CategorizationResult.Error, result)
        verifyNothingWritten()
        assertEquals(rows, stored)
    }

    @Test
    fun `retry rethrows CancellationException and writes nothing`() = runTest {
        val cancelling = mockk<ReceiptItemCategorizationService>()
        coEvery { cancelling.categorizeItems(any()) } throws CancellationException("synthetic cancel")
        setUp(itemCount = 5, serviceOverride = cancelling)
        val rows = (0 until 5).map { storedRow(it, real = it < 2) }
        installStored(rows)

        try {
            useCase(receiptId, retryUncategorized = true)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // expected: cancellation is never swallowed
        }

        verifyNothingWritten()
        assertEquals(rows, stored)
    }

    @Test
    fun `stored rows with a different count give AlreadyAnalyzed and no cloud call`() = runTest {
        setUp(itemCount = 4)
        val rows = (0 until 5).map { storedRow(it, real = it < 2) }
        installStored(rows)

        val result = useCase(receiptId, retryUncategorized = true)

        assertTrue("was $result", result is CategorizationResult.AlreadyAnalyzed)
        assertEquals(rows, (result as CategorizationResult.AlreadyAnalyzed).items)
        assertEquals(0, transportCalls.get())
        verifyNothingWritten()
    }

    @Test
    fun `stored rows with a different amount give AlreadyAnalyzed and no cloud call`() = runTest {
        setUp(itemCount = 5)
        val rows = (0 until 5).map { storedRow(it, real = it < 2, amount = if (it == 3) 2.0 else 1.0) }
        installStored(rows)

        val result = useCase(receiptId, retryUncategorized = true)

        assertTrue("was $result", result is CategorizationResult.AlreadyAnalyzed)
        assertEquals(rows, (result as CategorizationResult.AlreadyAnalyzed).items)
        assertEquals(0, transportCalls.get())
        verifyNothingWritten()
    }

    @Test
    fun `a user corrected placeholder is not sent`() = runTest {
        setUp(itemCount = 5, serviceOverride = answeringService())
        installStored(
            listOf(
                storedRow(0, real = true), storedRow(1, real = false),
                storedRow(2, real = false, userCorrected = true),
                storedRow(3, real = false), storedRow(4, real = true)
            )
        )

        val result = useCase(receiptId, retryUncategorized = true)

        assertTrue("was $result", result is CategorizationResult.Success)
        assertEquals(listOf(name(1), name(3)), sentInputs.single().lineItems.map { it.description })
        assertEquals(listOf(2L, 4L), retryWrites.single())
        assertEquals(null, stored[2].suggestedCategoryId)
        assertEquals(9L, stored[2].userCorrectedCategoryId)
        // only a user corrected placeholder remains: nothing is left to categorise
        assertEquals(listOf(CategorizationStatus.READY), statuses)
    }

    @Test
    fun `only user corrected placeholders left gives AlreadyAnalyzed and no cloud call`() = runTest {
        setUp(itemCount = 3)
        val rows = listOf(storedRow(0, real = true), storedRow(1, real = false, userCorrected = true),
            storedRow(2, real = true))
        installStored(rows)

        val result = useCase(receiptId, retryUncategorized = true)

        assertTrue("was $result", result is CategorizationResult.AlreadyAnalyzed)
        assertEquals(0, transportCalls.get())
        verifyNothingWritten()
    }

    // ---- Retry route gating, storage mode, deleted receipt, write barrier ----

    @Test
    fun `retry on DETERMINISTIC_FALLBACK is an Error with no write and a later CLOUD retry works`() = runTest {
        setUp(itemCount = 60, serviceOverride = answeringService(), route = AiRoute.DETERMINISTIC_FALLBACK)
        val rows = (0 until 60).map { storedRow(it, real = it < 25) }
        installStored(rows)

        val result = useCase(receiptId, retryUncategorized = true)

        assertEquals(CategorizationResult.Error, result)
        assertTrue(sentInputs.isEmpty())
        verifyNothingWritten()
        assertEquals(rows, stored)
        assertTrue(stored.drop(25).all { ReceiptItemPlaceholder.isRetryable(it) })

        routeTo(AiRoute.CLOUD)
        val later = useCase(receiptId, retryUncategorized = true)

        assertTrue("was $later", later is CategorizationResult.Success)
        assertEquals(35, sentInputs.single().lineItems.size)
        assertEquals(listOf(CategorizationStatus.READY), statuses)
        assertTrue(stored.none { ReceiptItemPlaceholder.isPlaceholder(it) })
    }

    @Test
    fun `retry on DISABLED route is Disabled with no write and a later CLOUD retry works`() = runTest {
        setUp(itemCount = 60, serviceOverride = answeringService(), route = AiRoute.DISABLED)
        val rows = (0 until 60).map { storedRow(it, real = it < 25) }
        installStored(rows)

        val result = useCase(receiptId, retryUncategorized = true)

        assertEquals(CategorizationResult.Disabled, result)
        assertTrue(sentInputs.isEmpty())
        verifyNothingWritten()
        assertEquals(rows, stored)

        routeTo(AiRoute.CLOUD)
        val later = useCase(receiptId, retryUncategorized = true)

        assertTrue("was $later", later is CategorizationResult.Success)
        assertEquals(listOf(CategorizationStatus.READY), statuses)
        assertTrue(stored.none { ReceiptItemPlaceholder.isPlaceholder(it) })
    }

    @Test
    fun `retry with persistItemTextAllowed false stores no text and sends nothing`() = runTest {
        setUp(itemCount = 5, serviceOverride = answeringService())
        val rows = (0 until 5).map { storedRow(it, real = it < 2) }
        installStored(rows)

        val result = useCase(receiptId, retryUncategorized = true, persistItemTextAllowed = false)

        assertTrue("was $result", result is CategorizationResult.AlreadyAnalyzed)
        assertTrue(sentInputs.isEmpty())
        assertTrue(retryWrittenItems.isEmpty())
        verifyNothingWritten()
        assertEquals(rows, stored)
    }

    @Test
    fun `retry where the receipt rows vanish during the call is an Error with no artifact write`() = runTest {
        setUp(itemCount = 5, serviceOverride = answeringService())
        val rows = (0 until 5).map { storedRow(it, real = it < 2) }
        // first read (before the call) sees the rows, the re-read after the write sees none
        coEvery { categorizationRepository.getByReceiptIdAsSnapshots(receiptId) } returnsMany
            listOf(rows, emptyList())
        coEvery {
            categorizationRepository.updatePlaceholderRowsWithRetryResult(receiptId, any(), any(), any())
        } returns 0

        val result = useCase(receiptId, retryUncategorized = true)

        assertEquals(CategorizationResult.Error, result)
        assertTrue(artifacts.isEmpty())
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `retry blocked by the database write barrier is an Error and writes no status or artifact`() = runTest {
        setUp(itemCount = 5, serviceOverride = answeringService())
        installStored((0 until 5).map { storedRow(it, real = it < 2) })
        coEvery {
            categorizationRepository.updatePlaceholderRowsWithRetryResult(receiptId, any(), any(), any())
        } throws DatabaseAccessBlockedException(
            DatabaseAccessType.WRITE,
            DatabaseAccessOperation("ReceiptItemCategorizationRepository.updatePlaceholderRowsWithRetryResult"),
            RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        )

        val result = useCase(receiptId, retryUncategorized = true)

        assertEquals(CategorizationResult.Error, result)
        assertTrue(artifacts.isEmpty())
        assertTrue(statuses.isEmpty())
        coVerify(exactly = 0) { categorizationRepository.saveCategorizationResult(any(), any(), any()) }
    }
}
