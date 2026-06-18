package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiChatMessage
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.ai.policy.AiPolicyImpl
import com.yourname.expensetracker.domain.common.sha256Prefix
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.privacy.FakePrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FinancialQueryInterpretationInputBuilderTest {

    private lateinit var categoryRepository: CategoryRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var aiPolicy: AiPolicy
    private lateinit var builder: FinancialQueryInterpretationInputBuilder

    @Before
    fun setup() {
        categoryRepository = mockk()
        expenseRepository = mockk()
        timeProvider = FakeTimeProvider(1_710_000_000_000L)
        aiPolicy = mockk()
        every { aiPolicy.canUseCloudFor(any(), AiCapability.QUERY_INTERPRETATION) } returns true
        every { aiPolicy.shouldRedact(any(), AiCapability.QUERY_INTERPRETATION) } returns false
        builder = FinancialQueryInterpretationInputBuilder(
            categoryRepository = categoryRepository,
            expenseRepository = expenseRepository,
            timeProvider = timeProvider,
            aiPolicy = aiPolicy,
            privacySettingsRepository = FakePrivacySettingsRepository(
                PrivacySettings(redactBeforeCloud = false)
            )
        )
    }

    @Test
    fun `build trims truncates and enriches interpretation input`() = runTest {
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 2L, name = "Transport", icon = "T", color = "#0000FF"),
            Category(id = 1L, name = "Groceries", icon = "G", color = "#00FF00")
        )
        coEvery { expenseRepository.getRecentMerchantNames() } returns listOf(
            "Lidl",
            "Lidl",
            "Spotify"
        )
        val rawQuery = "  ${"x".repeat(AppConfig.Ai.MAX_QUERY_INPUT_CHARS + 12)}  "
        val history = (1..10).map { index ->
            AiChatMessage(
                id = index.toLong(),
                sessionId = 1L,
                role = if (index % 2 == 0) AssistantMessageRole.ASSISTANT else AssistantMessageRole.USER,
                kind = AssistantMessageKind.QUERY,
                text = "message-$index",
                createdAt = index.toLong()
            )
        }

        val result = builder.build(
            rawQuery = rawQuery,
            settings = AiSettings(),
            conversationHistory = history
        )

        assertEquals(AppConfig.Ai.MAX_QUERY_INPUT_CHARS, result.rawQuery.length)
        assertTrue(result.rawQuery.all { it == 'x' })
        assertEquals(timeProvider.now(), result.currentTimeMs)
        assertEquals(listOf("Groceries", "Transport"), result.categoryNames)
        assertEquals(listOf("Lidl", "Spotify"), result.merchantNames)
        assertEquals("Lidl", result.merchantLookupMap["Lidl"])
        assertEquals(1L, result.categoryLookupMap["Groceries"])
        assertTrue(result.merchantAliasMap.isEmpty())
        assertTrue(result.categoryAliasMap.isEmpty())
        assertEquals(AppConfig.Ai.MAX_QUERY_HISTORY_TURNS_FOR_MODEL, result.conversationHistory.size)
        assertEquals(
            history.takeLast(AppConfig.Ai.MAX_QUERY_HISTORY_TURNS_FOR_MODEL),
            result.conversationHistory
        )
    }

    @Test
    fun `build caps merchant context to 100 entries`() = runTest {
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { expenseRepository.getRecentMerchantNames() } returns (1..120).map { "Merchant $it" }

        val result = builder.build(rawQuery = "total this month", settings = AiSettings())

        assertEquals(100, result.merchantNames.size)
        assertEquals("Merchant 1", result.merchantNames.first())
        assertEquals("Merchant 100", result.merchantNames.last())
    }

    @Test
    fun `build creates reversible alias maps when redaction is enabled`() = runTest {
        every { aiPolicy.shouldRedact(any(), AiCapability.QUERY_INTERPRETATION) } returns true
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Groceries", icon = "G", color = "#00FF00"),
            Category(id = 2L, name = "Transport", icon = "T", color = "#0000FF")
        )
        coEvery { expenseRepository.getRecentMerchantNames() } returns listOf("Lidl", "Uber")
        val history = listOf(
            AiChatMessage(
                id = 1L,
                sessionId = 1L,
                role = AssistantMessageRole.USER,
                kind = AssistantMessageKind.QUERY,
                text = "Show Lidl groceries",
                createdAt = 1L
            )
        )

        val result = builder.build(
            rawQuery = "Card 4242 4242 4242 4242 spent at Lidl for Groceries",
            settings = AiSettings(),
            conversationHistory = history
        )

        val lidlAlias = "merchant_${"Lidl".sha256Prefix()}"
        val uberAlias = "merchant_${"Uber".sha256Prefix()}"
        val groceriesAlias = "category_${"Groceries".sha256Prefix()}"
        val transportAlias = "category_${"Transport".sha256Prefix()}"

        assertEquals(listOf(lidlAlias, uberAlias), result.merchantNames)
        assertEquals("Lidl", result.merchantAliasMap[lidlAlias])
        assertEquals("Uber", result.merchantAliasMap[uberAlias])
        assertEquals("Lidl", result.merchantLookupMap[lidlAlias])
        assertTrue(result.merchantLookupMap["Lidl"] == null)

        assertTrue(result.categoryNames.contains(groceriesAlias))
        assertTrue(result.categoryNames.contains(transportAlias))
        assertEquals("Groceries", result.categoryAliasMap[groceriesAlias])
        assertEquals("Transport", result.categoryAliasMap[transportAlias])
        assertEquals(1L, result.categoryLookupMap[groceriesAlias])
        assertEquals(2L, result.categoryLookupMap[transportAlias])
        assertTrue(result.categoryLookupMap["Groceries"] == null)
        assertEquals(1L, result.categoryNameToIdMap["Groceries"])

        assertTrue(result.rawQuery.contains("[REDACTED_CARD]"))
        assertTrue(result.rawQuery.contains(lidlAlias))
        assertTrue(result.rawQuery.contains(groceriesAlias))
        assertTrue(result.conversationHistory.single().text.contains(lidlAlias))
        assertTrue(result.conversationHistory.single().text.contains(groceriesAlias))
    }

    @Test
    fun `build redacts when privacy requires it even though ai redaction is off`() = runTest {
        // Real AiPolicyImpl exercises the actual fix: cloud usable, AI redaction off,
        // but PrivacySettings.redactBeforeCloud authoritative -> labels must be hashed.
        val privacyBuilder = FinancialQueryInterpretationInputBuilder(
            categoryRepository = categoryRepository,
            expenseRepository = expenseRepository,
            timeProvider = timeProvider,
            aiPolicy = AiPolicyImpl(),
            privacySettingsRepository = FakePrivacySettingsRepository(
                PrivacySettings(redactBeforeCloud = true)
            )
        )
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Groceries", icon = "G", color = "#00FF00")
        )
        coEvery { expenseRepository.getRecentMerchantNames() } returns listOf("Lidl")

        val cloudOnRedactionOff = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            queryInterpretationEnabled = true,
            redactBeforeCloud = false
        )

        val result = privacyBuilder.build(
            rawQuery = "spent at Lidl for Groceries",
            settings = cloudOnRedactionOff
        )

        val lidlAlias = "merchant_${"Lidl".sha256Prefix()}"
        val groceriesAlias = "category_${"Groceries".sha256Prefix()}"
        assertEquals(listOf(lidlAlias), result.merchantNames)
        assertTrue(result.categoryNames.contains(groceriesAlias))
        assertTrue(result.merchantLookupMap["Lidl"] == null)
        assertTrue(result.rawQuery.contains(lidlAlias))
        assertTrue(result.rawQuery.contains(groceriesAlias))
    }
}
