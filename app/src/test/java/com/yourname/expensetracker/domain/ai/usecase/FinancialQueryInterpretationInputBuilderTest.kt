package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.ai.model.AiChatMessage
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
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
    private lateinit var builder: FinancialQueryInterpretationInputBuilder

    @Before
    fun setup() {
        categoryRepository = mockk()
        expenseRepository = mockk()
        timeProvider = FakeTimeProvider(1_710_000_000_000L)
        builder = FinancialQueryInterpretationInputBuilder(
            categoryRepository = categoryRepository,
            expenseRepository = expenseRepository,
            timeProvider = timeProvider
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

        val result = builder.build(rawQuery = rawQuery, conversationHistory = history)

        assertEquals(AppConfig.Ai.MAX_QUERY_INPUT_CHARS, result.rawQuery.length)
        assertTrue(result.rawQuery.all { it == 'x' })
        assertEquals(timeProvider.now(), result.currentTimeMs)
        assertEquals(listOf("Groceries", "Transport"), result.categoryNames)
        assertEquals(listOf("Lidl", "Spotify"), result.merchantNames)
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

        val result = builder.build(rawQuery = "total this month")

        assertEquals(100, result.merchantNames.size)
        assertEquals("Merchant 1", result.merchantNames.first())
        assertEquals("Merchant 100", result.merchantNames.last())
    }
}
