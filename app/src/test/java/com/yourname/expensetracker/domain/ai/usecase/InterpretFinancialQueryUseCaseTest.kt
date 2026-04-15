package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.QueryInterpretationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InterpretFinancialQueryUseCaseTest {

    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var queryInterpretationService: QueryInterpretationService
    private lateinit var inputBuilder: FinancialQueryInterpretationInputBuilder
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var useCase: InterpretFinancialQueryUseCase

    @Before
    fun setup() {
        aiSettingsRepository = mockk()
        queryInterpretationService = mockk()
        inputBuilder = mockk()
        categoryRepository = mockk()

        useCase = InterpretFinancialQueryUseCase(
            aiSettingsRepository = aiSettingsRepository,
            queryInterpretationService = queryInterpretationService,
            inputBuilder = inputBuilder,
            categoryRepository = categoryRepository
        )
    }

    @Test
    fun `invoke returns unsupported when assistant query interpretation disabled`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true))

        val result = useCase("total this month")

        assertTrue(result is FinancialQueryInterpretationResult.Unsupported)
        coVerify(exactly = 0) { queryInterpretationService.interpret(any()) }
    }

    @Test
    fun `invoke returns provider structured result when available`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, assistantEnabled = true, queryInterpretationEnabled = true)
        )
        coEvery { categoryRepository.getAll() } returns emptyList()
        val built = com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput(
            rawQuery = "total this month",
            currentTimeMs = 1000L
        )
        coEvery { inputBuilder.build(any(), any(), any()) } returns built
        val structured = FinancialQueryInterpretationResult.Structured(
            com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent(
                rawQuery = "total this month",
                normalizedQuery = "total this month",
                filters = com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters(),
                metric = QueryMetric.TOTAL
            )
        )
        coEvery { queryInterpretationService.interpret(built) } returns structured

        val result = useCase("total this month")

        assertTrue(result is FinancialQueryInterpretationResult.Structured)
        val structuredResult = result as FinancialQueryInterpretationResult.Structured
        assertEquals(QueryMetric.TOTAL, structuredResult.intent.metric)
        assertTrue(structuredResult.intent.filters.period != null)
    }

    @Test
    fun `invoke falls back locally when provider returns unsupported`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, assistantEnabled = true, queryInterpretationEnabled = true)
        )
        val built = com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput(
            rawQuery = "top merchants this month",
            currentTimeMs = 1000L
        )
        coEvery { inputBuilder.build(any(), any(), any()) } returns built
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Groceries", icon = "G", color = "#00FF00")
        )
        coEvery { queryInterpretationService.interpret(built) } returns
            FinancialQueryInterpretationResult.Unsupported("no provider")

        val result = useCase("top merchants this month")

        assertTrue(result is FinancialQueryInterpretationResult.Structured)
        val structured = result as FinancialQueryInterpretationResult.Structured
        assertEquals(QueryGrouping.MERCHANT, structured.intent.grouping)
        assertEquals(QueryMetric.TOTAL, structured.intent.metric)
    }

    @Test
    fun `invoke local fallback matches category by name`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, assistantEnabled = true, queryInterpretationEnabled = true)
        )
        val built = com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput(
            rawQuery = "show groceries this month",
            currentTimeMs = 1000L
        )
        coEvery { inputBuilder.build(any(), any(), any()) } returns built
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 7L, name = "Groceries", icon = "G", color = "#00FF00")
        )
        coEvery { queryInterpretationService.interpret(built) } returns
            FinancialQueryInterpretationResult.Unsupported("no provider")

        val result = useCase("show groceries this month")

        assertTrue(result is FinancialQueryInterpretationResult.Structured)
        val structured = result as FinancialQueryInterpretationResult.Structured
        assertEquals(QueryMetric.LIST, structured.intent.metric)
        assertEquals(setOf(7L), structured.intent.filters.categoryIds)
    }

    @Test
    fun `invoke propagates CancellationException from provider instead of returning Unsupported`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, assistantEnabled = true, queryInterpretationEnabled = true)
        )
        val built = com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput(
            rawQuery = "total this month",
            currentTimeMs = 1000L
        )
        coEvery { inputBuilder.build(any(), any(), any()) } returns built
        coEvery { queryInterpretationService.interpret(built) } throws CancellationException("cancelled")

        try {
            useCase("total this month")
            throw AssertionError("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected: cancellation propagates instead of being mapped to Unsupported
        }
    }

    @Test
    fun `invoke keeps provider grouping and fills missing period for top merchants this month`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, assistantEnabled = true, queryInterpretationEnabled = true)
        )
        val built = com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput(
            rawQuery = "top merchants this month",
            currentTimeMs = 1000L
        )
        coEvery { inputBuilder.build(any(), any(), any()) } returns built
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { queryInterpretationService.interpret(built) } returns FinancialQueryInterpretationResult.Structured(
            FinancialQueryIntent(
                rawQuery = "top merchants this month",
                normalizedQuery = "top merchants this month",
                filters = ExpenseQueryFilters(),
                metric = QueryMetric.TOTAL,
                grouping = QueryGrouping.MERCHANT
            )
        )

        val result = useCase("top merchants this month")

        assertTrue(result is FinancialQueryInterpretationResult.Structured)
        val structured = result as FinancialQueryInterpretationResult.Structured
        assertEquals(QueryGrouping.MERCHANT, structured.intent.grouping)
        assertEquals(QueryMetric.TOTAL, structured.intent.metric)
        assertTrue(structured.intent.filters.period != null)
    }

    @Test
    fun `invoke keeps provider max metric and fills category for largest groceries this week`() = runTest {
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, assistantEnabled = true, queryInterpretationEnabled = true)
        )
        val built = com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput(
            rawQuery = "largest groceries this week",
            currentTimeMs = 1000L
        )
        coEvery { inputBuilder.build(any(), any(), any()) } returns built
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 3L, name = "Groceries", icon = "G", color = "#00FF00")
        )
        coEvery { queryInterpretationService.interpret(built) } returns FinancialQueryInterpretationResult.Structured(
            FinancialQueryIntent(
                rawQuery = "largest groceries this week",
                normalizedQuery = "largest groceries this week",
                filters = ExpenseQueryFilters(),
                metric = QueryMetric.MAX,
                grouping = QueryGrouping.NONE
            )
        )

        val result = useCase("largest groceries this week")

        assertTrue(result is FinancialQueryInterpretationResult.Structured)
        val structured = result as FinancialQueryInterpretationResult.Structured
        assertEquals(QueryMetric.MAX, structured.intent.metric)
        assertEquals(setOf(3L), structured.intent.filters.categoryIds)
        assertTrue(structured.intent.filters.period != null)
    }
}
