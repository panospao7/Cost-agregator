package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AssistantUnsupportedReason
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.PeriodGranularity
import com.yourname.expensetracker.domain.ai.model.QueryComparison
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.QueryInterpretationService
import com.yourname.expensetracker.domain.model.PeriodRange
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

class InterpretFinancialQueryUseCaseTest {

    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var queryInterpretationService: QueryInterpretationService
    private lateinit var inputBuilder: FinancialQueryInterpretationInputBuilder
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var useCase: InterpretFinancialQueryUseCase

    private val logs = mutableListOf<Pair<Throwable?, String>>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += t to message
        }
    }

    @After
    fun removeLogTree() {
        Timber.uproot(logTree)
    }

    @Before
    fun setup() {
        logs.clear()
        Timber.plant(logTree)
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
            FinancialQueryInterpretationResult.Unsupported(AssistantUnsupportedReason.PROVIDER_UNAVAILABLE)

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
            FinancialQueryInterpretationResult.Unsupported(AssistantUnsupportedReason.PROVIDER_UNAVAILABLE)

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

    private fun enabledSettings() =
        AiSettings(aiEnabled = true, assistantEnabled = true, queryInterpretationEnabled = true)

    private fun stubInput(rawQuery: String): com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput {
        val built = com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput(
            rawQuery = rawQuery,
            currentTimeMs = 1_700_000_000_000L
        )
        coEvery { inputBuilder.build(any(), any(), any()) } returns built
        return built
    }

    @Test
    fun `AE-C7 provider exception is logged as code and class only and falls back locally`() = runTest(timeout = 30.seconds) {
        val sentinelPath = "/data/user/0/x/db"
        val sentinelToken = "abc123"
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        val built = stubInput("food this month")
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 5L, name = "Food", icon = "F", color = "#FF0000")
        )
        coEvery { queryInterpretationService.interpret(built) } throws
            IllegalStateException("$sentinelPath token=$sentinelToken")

        val result = useCase("food this month")

        // INV-C5 + P5: the provider failure drives the local fallback; nothing from the
        // exception reaches the returned result.
        assertTrue("expected local-fallback Structured but was $result", result is FinancialQueryInterpretationResult.Structured)
        val structured = result as FinancialQueryInterpretationResult.Structured
        assertEquals(setOf(5L), structured.intent.filters.categoryIds)
        val period = structured.intent.filters.period
        assertNotNull(period)
        assertTrue(period!!.end > period.start)
        assertTrue(!result.toString().contains(sentinelToken))
        assertTrue(!result.toString().contains("/data"))

        // INV-C7: code + class name only, no throwable, no message text.
        assertTrue(logs.isNotEmpty())
        assertTrue(
            "a log line must carry the code and the exception class",
            logs.any {
                it.second.contains(AssistantUnsupportedReason.INTERPRETATION_FAILED.name) &&
                    it.second.contains("IllegalStateException")
            }
        )
        assertTrue("no throwable may be passed to Timber", logs.all { it.first == null })
        assertTrue(logs.none { it.second.contains(sentinelToken) })
        assertTrue(logs.none { it.second.contains("/data") })
        assertTrue(logs.none { it.second.contains(sentinelPath) })
    }

    @Test
    fun `AE-C7 provider exception with no local fallback match still never leaks text`() = runTest(timeout = 30.seconds) {
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        val built = stubInput("total this month")
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { queryInterpretationService.interpret(built) } throws
            IllegalStateException("/data/user/0/x/db token=abc123")

        val result = useCase("total this month")

        assertTrue(!result.toString().contains("abc123"))
        assertTrue(!result.toString().contains("/data/user"))
        assertTrue(logs.none { it.second.contains("abc123") || it.second.contains("/data/user") })
        assertTrue(logs.all { it.first == null })
    }

    @Test
    fun `INV-C5 disabled interpretation returns INTERPRETATION_DISABLED code`() = runTest(timeout = 30.seconds) {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true))

        val result = useCase("total this month")

        assertEquals(
            FinancialQueryInterpretationResult.Unsupported(AssistantUnsupportedReason.INTERPRETATION_DISABLED),
            result
        )
    }

    @Test
    fun `INV-C5 each disabled flag alone returns INTERPRETATION_DISABLED`() = runTest(timeout = 30.seconds) {
        val disabledVariants = listOf(
            AiSettings(aiEnabled = false, assistantEnabled = true, queryInterpretationEnabled = true),
            AiSettings(aiEnabled = true, assistantEnabled = false, queryInterpretationEnabled = true),
            AiSettings(aiEnabled = true, assistantEnabled = true, queryInterpretationEnabled = false)
        )
        disabledVariants.forEach { settings ->
            every { aiSettingsRepository.settings() } returns flowOf(settings)

            val result = useCase("total this month")

            assertEquals(
                FinancialQueryInterpretationResult.Unsupported(AssistantUnsupportedReason.INTERPRETATION_DISABLED),
                result
            )
        }
        coVerify(exactly = 0) { queryInterpretationService.interpret(any()) }
    }

    @Test
    fun `PC-11 validateAmountBound logs no amount digits for excessive bounds`() = runTest(timeout = 30.seconds) {
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        val built = stubInput("total this month")
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { queryInterpretationService.interpret(built) } returns FinancialQueryInterpretationResult.Structured(
            FinancialQueryIntent(
                rawQuery = "total this month",
                normalizedQuery = "total this month",
                filters = ExpenseQueryFilters(minAmount = 1_987_654_321.0, maxAmount = 3_456_789_012.0),
                metric = QueryMetric.TOTAL
            )
        )

        val result = useCase("total this month")

        // Behaviour control: both bounds are clamped to the 1e9 ceiling.
        val filters = (result as FinancialQueryInterpretationResult.Structured).intent.filters
        assertEquals(1_000_000_000.0, filters.minAmount!!, 0.0)
        assertEquals(1_000_000_000.0, filters.maxAmount!!, 0.0)
        assertTrue(logs.all { it.first == null })
        assertTrue(logs.none { it.second.contains("987654321") })
        assertTrue(logs.none { it.second.contains("456789012") })
        assertTrue(logs.none { it.second.contains("1987654321") })
    }

    @Test
    fun `PC-11 validateAmountBound logs no amount digits for negative bounds`() = runTest(timeout = 30.seconds) {
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        val built = stubInput("total this month")
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { queryInterpretationService.interpret(built) } returns FinancialQueryInterpretationResult.Structured(
            FinancialQueryIntent(
                rawQuery = "total this month",
                normalizedQuery = "total this month",
                filters = ExpenseQueryFilters(minAmount = -7_654.32, maxAmount = Double.NaN),
                metric = QueryMetric.TOTAL
            )
        )

        val result = useCase("total this month")

        val filters = (result as FinancialQueryInterpretationResult.Structured).intent.filters
        assertEquals(0.0, filters.minAmount!!, 0.0)
        assertEquals(null, filters.maxAmount)
        assertTrue(logs.none { it.second.contains("7654") || it.second.contains("7,654") })
        assertTrue(logs.none { it.second.contains("NaN") })
    }

    // ---- ai-fixes C3: calendar period vocabulary (INV-C8..C10, AE-C11, C13, C14, C19) -----------
    //
    // The use case resolves periods in ZoneId.systemDefault(); the JVM default zone is pinned to
    // Europe/Athens for these tests and restored afterwards. NOW = Fri 2026-10-16 10:00 EEST.

    private var originalZone: TimeZone? = null
    private val athens: ZoneId = ZoneId.of("Europe/Athens")

    @Before
    fun pinDefaultZoneToAthens() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Athens"))
    }

    @After
    fun restoreDefaultZone() {
        originalZone?.let { TimeZone.setDefault(it) }
    }

    private fun athensMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, athens).toInstant().toEpochMilli()

    private fun nowOct16(): Long = athensMs(2026, 10, 16, 10, 0)

    private fun stubInputAtNow(rawQuery: String): com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput {
        val built = com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput(
            rawQuery = rawQuery,
            currentTimeMs = nowOct16()
        )
        coEvery { inputBuilder.build(any(), any(), any()) } returns built
        return built
    }

    private val foodCategory = Category(id = 1L, name = "Food", icon = "F", color = "#FF0000")

    /** Runs [query] through the local fallback (provider unsupported) and returns the structured intent. */
    private suspend fun interpretViaFallback(
        query: String,
        categories: List<Category> = emptyList()
    ): FinancialQueryIntent {
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        val built = stubInputAtNow(query)
        coEvery { categoryRepository.getAll() } returns categories
        coEvery { queryInterpretationService.interpret(built) } returns
            FinancialQueryInterpretationResult.Unsupported(AssistantUnsupportedReason.PROVIDER_UNAVAILABLE)

        val result = useCase(query)

        assertTrue("expected Structured but was $result", result is FinancialQueryInterpretationResult.Structured)
        return (result as FinancialQueryInterpretationResult.Structured).intent
    }

    /** Runs [query] with a provider Structured result so that the enrich path fills the gaps. */
    private suspend fun interpretViaEnrich(
        query: String,
        providerFilters: ExpenseQueryFilters = ExpenseQueryFilters(),
        providerComparison: QueryComparison = QueryComparison.NONE,
        categories: List<Category> = emptyList()
    ): FinancialQueryIntent {
        every { aiSettingsRepository.settings() } returns flowOf(enabledSettings())
        val built = stubInputAtNow(query)
        coEvery { categoryRepository.getAll() } returns categories
        coEvery { queryInterpretationService.interpret(built) } returns
            FinancialQueryInterpretationResult.Structured(
                FinancialQueryIntent(
                    rawQuery = query,
                    normalizedQuery = query,
                    filters = providerFilters,
                    metric = QueryMetric.TOTAL,
                    comparison = providerComparison
                )
            )

        val result = useCase(query)

        assertTrue("expected Structured but was $result", result is FinancialQueryInterpretationResult.Structured)
        return (result as FinancialQueryInterpretationResult.Structured).intent
    }

    private fun assertPeriod(
        filters: ExpenseQueryFilters,
        expectedStart: Long,
        expectedEnd: Long,
        expectedGranularity: PeriodGranularity?
    ) {
        val period = filters.period
        assertNotNull("period must be resolved", period)
        assertEquals("period start", expectedStart, period!!.start)
        assertEquals("period end", expectedEnd, period.end)
        assertEquals("period granularity", expectedGranularity, filters.periodGranularity)
    }

    @Test
    fun `AE-C11 food last month resolves previous calendar month with MONTH granularity and no comparison`() = runTest(timeout = 30.seconds) {
        val intent = interpretViaFallback("food last month", listOf(foodCategory))

        assertEquals(setOf(1L), intent.filters.categoryIds)
        assertPeriod(intent.filters, athensMs(2026, 9, 1), athensMs(2026, 10, 1), PeriodGranularity.MONTH)
        assertEquals(QueryComparison.NONE, intent.comparison)
    }

    @Test
    fun `AE-C11 last week last quarter and last year resolve previous full calendar units`() = runTest(timeout = 30.seconds) {
        val week = interpretViaFallback("last week")
        assertPeriod(week.filters, athensMs(2026, 10, 5), athensMs(2026, 10, 12), PeriodGranularity.WEEK)
        assertEquals(QueryComparison.NONE, week.comparison)

        val quarter = interpretViaFallback("last quarter")
        assertPeriod(quarter.filters, athensMs(2026, 7, 1), athensMs(2026, 10, 1), PeriodGranularity.QUARTER)
        assertEquals(QueryComparison.NONE, quarter.comparison)

        val year = interpretViaFallback("last year")
        assertPeriod(year.filters, athensMs(2025, 1, 1), athensMs(2026, 1, 1), PeriodGranularity.YEAR)
        assertEquals(QueryComparison.NONE, year.comparison)
    }

    @Test
    fun `AE-C11 spending previous month is September with no comparison`() = runTest(timeout = 30.seconds) {
        val intent = interpretViaFallback("spending previous month")

        assertPeriod(intent.filters, athensMs(2026, 9, 1), athensMs(2026, 10, 1), PeriodGranularity.MONTH)
        assertEquals(QueryComparison.NONE, intent.comparison)
    }

    @Test
    fun `AE-C11 bare last month shares the vocabulary and stays a non comparison`() = runTest(timeout = 30.seconds) {
        val intent = interpretViaFallback("last month")

        assertPeriod(intent.filters, athensMs(2026, 9, 1), athensMs(2026, 10, 1), PeriodGranularity.MONTH)
        assertEquals(QueryComparison.NONE, intent.comparison)
    }

    @Test
    fun `AE-C11 control this month stays the current full month with MONTH granularity`() = runTest(timeout = 30.seconds) {
        val intent = interpretViaFallback("food this month", listOf(foodCategory))

        assertPeriod(intent.filters, athensMs(2026, 10, 1), athensMs(2026, 11, 1), PeriodGranularity.MONTH)
        assertEquals(QueryComparison.NONE, intent.comparison)
    }

    @Test
    fun `AE-C13 compare last month with previous month has base September and a comparison`() = runTest(timeout = 30.seconds) {
        val intent = interpretViaFallback("compare last month with previous month")

        // "previous month" after the connector is the comparison target, not the base period.
        assertPeriod(intent.filters, athensMs(2026, 9, 1), athensMs(2026, 10, 1), PeriodGranularity.MONTH)
        assertEquals(QueryComparison.PREVIOUS_EQUIVALENT_PERIOD, intent.comparison)
        // The calendar-aware previous period of that base is August.
        val previous = AssistantPeriodResolver.previousEquivalent(
            startMs = intent.filters.period!!.start,
            endMs = intent.filters.period!!.end,
            granularity = intent.filters.periodGranularity
        )
        assertEquals(athensMs(2026, 8, 1), previous.startMs)
        assertEquals(athensMs(2026, 9, 1), previous.endMs)
    }

    @Test
    fun `AE-C14 food this month vs last month has base October keeps Food and requests comparison`() = runTest(timeout = 30.seconds) {
        val intent = interpretViaFallback("food this month vs last month", listOf(foodCategory))

        assertPeriod(intent.filters, athensMs(2026, 10, 1), athensMs(2026, 11, 1), PeriodGranularity.MONTH)
        assertEquals(setOf(1L), intent.filters.categoryIds)
        assertEquals(QueryComparison.PREVIOUS_EQUIVALENT_PERIOD, intent.comparison)
    }

    @Test
    fun `AE-C19 food previous month is not a comparison`() = runTest(timeout = 30.seconds) {
        val intent = interpretViaFallback("food previous month", listOf(foodCategory))

        assertEquals(QueryComparison.NONE, intent.comparison)
        assertPeriod(intent.filters, athensMs(2026, 9, 1), athensMs(2026, 10, 1), PeriodGranularity.MONTH)
        assertEquals(setOf(1L), intent.filters.categoryIds)
    }

    @Test
    fun `AE-C19 food this month compared to last month is a comparison on base October`() = runTest(timeout = 30.seconds) {
        val intent = interpretViaFallback("food this month compared to last month", listOf(foodCategory))

        assertEquals(QueryComparison.PREVIOUS_EQUIVALENT_PERIOD, intent.comparison)
        assertPeriod(intent.filters, athensMs(2026, 10, 1), athensMs(2026, 11, 1), PeriodGranularity.MONTH)
    }

    @Test
    fun `AE-C19 enrich food previous month leaves comparison NONE`() = runTest(timeout = 30.seconds) {
        val intent = interpretViaEnrich("food previous month", categories = listOf(foodCategory))

        assertEquals(QueryComparison.NONE, intent.comparison)
        assertPeriod(intent.filters, athensMs(2026, 9, 1), athensMs(2026, 10, 1), PeriodGranularity.MONTH)
    }

    @Test
    fun `AE-C13 enrich fills base September and the comparison cue for compare last month with previous month`() = runTest(timeout = 30.seconds) {
        val intent = interpretViaEnrich("compare last month with previous month")

        assertPeriod(intent.filters, athensMs(2026, 9, 1), athensMs(2026, 10, 1), PeriodGranularity.MONTH)
        assertEquals(QueryComparison.PREVIOUS_EQUIVALENT_PERIOD, intent.comparison)
    }

    @Test
    fun `AE-C14 enrich fills October base and comparison for food this month vs last month`() = runTest(timeout = 30.seconds) {
        val intent = interpretViaEnrich("food this month vs last month", categories = listOf(foodCategory))

        assertPeriod(intent.filters, athensMs(2026, 10, 1), athensMs(2026, 11, 1), PeriodGranularity.MONTH)
        assertEquals(setOf(1L), intent.filters.categoryIds)
        assertEquals(QueryComparison.PREVIOUS_EQUIVALENT_PERIOD, intent.comparison)
    }

    @Test
    fun `INV-C8 enrich fills a null provider period together with its granularity`() = runTest(timeout = 30.seconds) {
        val intent = interpretViaEnrich("spending last quarter")

        assertPeriod(intent.filters, athensMs(2026, 7, 1), athensMs(2026, 10, 1), PeriodGranularity.QUARTER)
    }

    @Test
    fun `INV-C8 enrich keeps a provider explicit range and its null granularity`() = runTest(timeout = 30.seconds) {
        // Boundary: the query text says "last month" but the provider range wins; granularity stays null (custom).
        val intent = interpretViaEnrich(
            query = "food last month",
            providerFilters = ExpenseQueryFilters(period = PeriodRange(100L, 200L)),
            categories = listOf(foodCategory)
        )

        assertEquals(100L, intent.filters.period!!.start)
        assertEquals(200L, intent.filters.period!!.end)
        assertNull(intent.filters.periodGranularity)
    }

    @Test
    fun `INV-C8 enrich keeps a provider range together with the provider granularity`() = runTest(timeout = 30.seconds) {
        val providerStart = athensMs(2026, 8, 1)
        val providerEnd = athensMs(2026, 9, 1)
        val intent = interpretViaEnrich(
            query = "food this month",
            providerFilters = ExpenseQueryFilters(
                period = PeriodRange(providerStart, providerEnd),
                periodGranularity = PeriodGranularity.MONTH
            ),
            categories = listOf(foodCategory)
        )

        assertPeriod(intent.filters, providerStart, providerEnd, PeriodGranularity.MONTH)
    }
}
