package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.OwnershipFilter
import com.yourname.expensetracker.domain.ai.model.AssistantUnsupportedReason
import com.yourname.expensetracker.domain.ai.model.ClarificationChoice
import com.yourname.expensetracker.domain.ai.model.ClarificationSlot
import com.yourname.expensetracker.domain.ai.model.PeriodChoice
import com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.FinancialQueryResult
import com.yourname.expensetracker.domain.ai.model.PeriodGranularity
import com.yourname.expensetracker.domain.ai.model.QueryComparison
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.model.PeriodRange
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.MultiConversionAggregate
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

class ExecuteFinancialQueryUseCaseTest {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var currencyConverterMock: CurrencyConverter
    private lateinit var useCase: ExecuteFinancialQueryUseCase

    @Before
    fun setup() {
        expenseRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        currencyConverterMock = mockk<CurrencyConverter>(relaxed = true).also {
            coEvery { it.convertMultiple(any(), any()) } returns MultiConversionAggregate(
                total = 0.0, targetCurrency = "EUR", failedConversions = emptyList()
            )
            coEvery { it.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>()) } returns ConversionResult(
                originalAmount = 0.0, originalCurrency = "EUR", convertedAmount = 0.0,
                targetCurrency = "EUR", rateUsed = 1.0, timestamp = 0L
            )
        }
        useCase = ExecuteFinancialQueryUseCase(expenseRepository, categoryRepository, currencyConverter = currencyConverterMock, currencySettingsRepository = mockk<CurrencySettingsRepository>().also {
            every { it.homeCurrency() } returns flowOf("EUR")
            coEvery { it.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        })
    }

    @Test
    fun `invoke returns summary total for simple purchase total`() = runTest {
        coEvery {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = 100L,
                endDate = 200L,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null,
                sortOrder = any()
            )
        } returns listOf(expenseWithCategory(id = 1L, amount = 42.5, currency = "EUR"))

        val result = useCase(
            FinancialQueryIntent(
                rawQuery = "total this month",
                normalizedQuery = "total this month",
                filters = ExpenseQueryFilters(period = PeriodRange(100L, 200L)),
                metric = QueryMetric.TOTAL
            )
        )

        assertTrue(result is FinancialQueryResult.Summary)
        result as FinancialQueryResult.Summary
        assertEquals("42.50 EUR", result.primaryText)
    }

    @Test
    fun `invoke returns previous period supporting text for comparison total`() = runTest {
        coEvery {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = 100L,
                endDate = 200L,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null,
                sortOrder = any()
            )
        } returns listOf(expenseWithCategory(id = 1L, amount = 80.0, currency = "EUR"))
        coEvery {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = 0L,
                endDate = 100L,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null,
                sortOrder = any()
            )
        } returns listOf(expenseWithCategory(id = 2L, amount = 60.0, currency = "EUR"))

        val result = useCase(
            FinancialQueryIntent(
                rawQuery = "compare this month",
                normalizedQuery = "compare this month",
                filters = ExpenseQueryFilters(period = PeriodRange(100L, 200L)),
                metric = QueryMetric.TOTAL,
                comparison = QueryComparison.PREVIOUS_EQUIVALENT_PERIOD
            )
        )

        assertTrue(result is FinancialQueryResult.Summary)
        result as FinancialQueryResult.Summary
        assertEquals("Previous period: 60.00 EUR", result.supportingText)
    }

    @Test
    fun `invoke returns merchant breakdown`() = runTest {
        coEvery {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = 100L,
                endDate = 200L,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null,
                sortOrder = any()
            )
        } returns listOf(
            expenseWithCategory(id = 1L, amount = 20.0, merchant = "Lidl", merchantKey = "lidl"),
            expenseWithCategory(id = 2L, amount = 30.0, merchant = "Lidl", merchantKey = "lidl")
        )

        val result = useCase(
            FinancialQueryIntent(
                rawQuery = "top merchants this month",
                normalizedQuery = "top merchants this month",
                filters = ExpenseQueryFilters(period = PeriodRange(100L, 200L)),
                metric = QueryMetric.TOTAL,
                grouping = QueryGrouping.MERCHANT
            )
        )

        assertTrue(result is FinancialQueryResult.Breakdown)
        result as FinancialQueryResult.Breakdown
        assertEquals("Lidl", result.rows.first().label)
        assertEquals("50.00 EUR", result.rows.first().valueText)
    }

    @Test
    fun `invoke returns category breakdown`() = runTest {
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(1L, "Groceries", "G", "#00FF00")
        )
        coEvery {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = 100L,
                endDate = 200L,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null,
                sortOrder = any()
            )
        } returns listOf(
            expenseWithCategory(id = 1L, amount = 40.0, categoryId = 1L),
            expenseWithCategory(id = 2L, amount = 59.0, categoryId = 1L)
        )

        val result = useCase(
            FinancialQueryIntent(
                rawQuery = "top categories this month",
                normalizedQuery = "top categories this month",
                filters = ExpenseQueryFilters(period = PeriodRange(100L, 200L)),
                metric = QueryMetric.TOTAL,
                grouping = QueryGrouping.CATEGORY
            )
        )

        assertTrue(result is FinancialQueryResult.Breakdown)
        result as FinancialQueryResult.Breakdown
        assertEquals("Groceries", result.rows.first().label)
        assertEquals("99.00 EUR", result.rows.first().valueText)
    }

    @Test
    fun `invoke returns transaction list for list metric`() = runTest {
        coEvery {
            expenseRepository.getAssistantExpenseCountFiltered(
                startDate = 100L,
                endDate = 200L,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null
            )
        } returns 1200

        val intent = FinancialQueryIntent(
            rawQuery = "show groceries this month",
            normalizedQuery = "show groceries this month",
            filters = ExpenseQueryFilters(period = PeriodRange(100L, 200L)),
            metric = QueryMetric.LIST
        )

        val result = useCase(intent)

        assertTrue(result is FinancialQueryResult.TransactionList)
        result as FinancialQueryResult.TransactionList
        assertEquals(1200, result.previewCount)
    }

    @Test
    fun `invoke returns largest purchase summary`() = runTest {
        coEvery {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = 100L,
                endDate = 200L,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null,
                sortOrder = any()
            )
        } returns listOf(
            expenseWithCategory(id = 1L, amount = 120.0, merchant = "Amazon", currency = "EUR"),
            expenseWithCategory(id = 2L, amount = 20.0, merchant = "Bakery", currency = "EUR")
        )

        val result = useCase(
            FinancialQueryIntent(
                rawQuery = "largest purchase this month",
                normalizedQuery = "largest purchase this month",
                filters = ExpenseQueryFilters(period = PeriodRange(100L, 200L)),
                metric = QueryMetric.MAX
            )
        )

        assertTrue(result is FinancialQueryResult.Summary)
        result as FinancialQueryResult.Summary
        assertEquals("Amazon: 120.00 EUR", result.primaryText)
    }

    @Test
    fun `AE-C9 largest metric with zero matching rows returns NO_MATCHING_TRANSACTIONS code`() = runTest(timeout = 30.seconds) {
        coEvery {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = 100L,
                endDate = 200L,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null,
                sortOrder = any()
            )
        } returns emptyList()

        val result = useCase(
            FinancialQueryIntent(
                rawQuery = "largest purchase this month",
                normalizedQuery = "largest purchase this month",
                filters = ExpenseQueryFilters(period = PeriodRange(100L, 200L)),
                metric = QueryMetric.MAX
            )
        )

        assertEquals(
            FinancialQueryResult.Unsupported(AssistantUnsupportedReason.NO_MATCHING_TRANSACTIONS),
            result
        )
    }

    // ai-fixes C5 (INV-C17): this test replaces the C2 test `INV-C5 metric without an executor returns
    // METRIC_NOT_SUPPORTED code`, which used QueryMetric.MIN as the metric lacking an executor.
    // C5 gave MIN an executor, so the old expectation no longer holds; the surviving invariant is
    // that no QueryMetric entry reaches METRIC_NOT_SUPPORTED.
    @Test
    fun `INV-C17 every QueryMetric has an executor and none returns METRIC_NOT_SUPPORTED`() = runTest(timeout = 30.seconds) {
        stubRange(
            100L,
            200L,
            listOf(
                expenseWithCategory(id = 1L, amount = 12.0, currency = "EUR", merchant = "Lidl"),
                expenseWithCategory(id = 2L, amount = 3.5, currency = "EUR", merchant = "Kiosk")
            )
        )

        assertTrue("QueryMetric must have entries to iterate", QueryMetric.entries.isNotEmpty())
        QueryMetric.entries.forEach { metric ->
            val result = useCase(
                FinancialQueryIntent(
                    rawQuery = "query for $metric",
                    normalizedQuery = "query for $metric",
                    filters = ExpenseQueryFilters(period = PeriodRange(100L, 200L)),
                    metric = metric,
                    grouping = QueryGrouping.NONE
                )
            )

            assertTrue(
                "metric $metric must not return METRIC_NOT_SUPPORTED but was $result",
                result != FinancialQueryResult.Unsupported(AssistantUnsupportedReason.METRIC_NOT_SUPPORTED)
            )
        }
    }

    // ---- ai-fixes C5: MIN is answered through the shared extreme executor (INV-C17, AE-C27) ----------

    private fun minIntent(grouping: QueryGrouping = QueryGrouping.NONE) = FinancialQueryIntent(
        rawQuery = "smallest purchase this month",
        normalizedQuery = "smallest purchase this month",
        filters = ExpenseQueryFilters(period = PeriodRange(100L, 200L)),
        metric = QueryMetric.MIN,
        grouping = grouping
    )

    @Test
    fun `AE-C27 INV-C17 single currency smallest purchase wins with the smallest purchase title`() = runTest(timeout = 30.seconds) {
        stubRange(
            100L,
            200L,
            listOf(
                expenseWithCategory(id = 1L, amount = 5.0, currency = "EUR", merchant = "Lidl"),
                expenseWithCategory(id = 2L, amount = 1.2, currency = "EUR", merchant = "Bakery")
            )
        )

        val result = useCase(minIntent())

        assertTrue("expected Summary but was $result", result is FinancialQueryResult.Summary)
        result as FinancialQueryResult.Summary
        assertEquals("Bakery: 1.20 EUR", result.primaryText)
        assertEquals(UiText.fromKey("domain_ai_smallest_purchase"), result.title)
        assertFalse(result.dataQuality.isPartial)
        assertEquals(0, result.dataQuality.excludedCount)
    }

    @Test
    fun `AE-C27 INV-C17 control MAX on the same single currency rows still picks the largest with its own title`() = runTest(timeout = 30.seconds) {
        stubRange(
            100L,
            200L,
            listOf(
                expenseWithCategory(id = 1L, amount = 5.0, currency = "EUR", merchant = "Lidl"),
                expenseWithCategory(id = 2L, amount = 1.2, currency = "EUR", merchant = "Bakery")
            )
        )

        val result = useCase(minIntent().copy(metric = QueryMetric.MAX))

        assertTrue("expected Summary but was $result", result is FinancialQueryResult.Summary)
        result as FinancialQueryResult.Summary
        assertEquals("Lidl: 5.00 EUR", result.primaryText)
        assertEquals(UiText.fromKey("domain_ai_largest_purchase"), result.title)
    }

    @Test
    fun `AE-C27 INV-C17 mixed currencies exclude the no rate row and report a partial result`() = runTest(timeout = 30.seconds) {
        val currencyConverter = currencyConverterMock
        coEvery { currencyConverter.convertAsOf(2.0, "USD", "EUR", any()) } returns null
        stubRange(
            100L,
            200L,
            listOf(
                expenseWithCategory(id = 1L, amount = 12.0, currency = "EUR", merchant = "Lidl"),
                expenseWithCategory(id = 2L, amount = 3.5, currency = "EUR", merchant = "Kiosk"),
                expenseWithCategory(id = 3L, amount = 2.0, currency = "USD", merchant = "Bakery")
            )
        )

        val result = useCase(minIntent())

        assertTrue("expected Summary but was $result", result is FinancialQueryResult.Summary)
        result as FinancialQueryResult.Summary
        assertEquals("Kiosk: 3.50 EUR", result.primaryText)
        assertEquals(UiText.fromKey("domain_ai_smallest_purchase"), result.title)
        assertTrue(result.dataQuality.isPartial)
        assertEquals(1, result.dataQuality.excludedCount)
        val drilldown = result.drilldownIntent
        assertNotNull(drilldown)
        assertEquals(setOf("Kiosk"), drilldown!!.filters.merchants)
        assertEquals(
            setOf(com.yourname.expensetracker.domain.model.DomainTransactionType.PURCHASE),
            drilldown.filters.transactionTypes
        )
        assertEquals(QueryMetric.MIN, drilldown.metric)
    }

    @Test
    fun `AE-C27 INV-C17 mixed currencies compare converted amounts not raw amounts`() = runTest(timeout = 30.seconds) {
        // Raw numbers say Lidl (4.00) is smaller than Kiosk (5.00), but 5.00 USD converts to 2.50 EUR.
        val currencyConverter = currencyConverterMock
        coEvery { currencyConverter.convertAsOf(5.0, "USD", "EUR", any()) } returns ConversionResult(
            originalAmount = 5.0, originalCurrency = "USD", convertedAmount = 2.5,
            targetCurrency = "EUR", rateUsed = 0.5, timestamp = 150L
        )
        stubRange(
            100L,
            200L,
            listOf(
                expenseWithCategory(id = 1L, amount = 4.0, currency = "EUR", merchant = "Lidl"),
                expenseWithCategory(id = 2L, amount = 5.0, currency = "USD", merchant = "Kiosk")
            )
        )

        val result = useCase(minIntent())

        assertTrue("expected Summary but was $result", result is FinancialQueryResult.Summary)
        result as FinancialQueryResult.Summary
        // The winner is shown in its own original currency, not the converted amount.
        assertEquals("Kiosk: 5.00 USD", result.primaryText)
        assertEquals(setOf("Kiosk"), result.drilldownIntent!!.filters.merchants)
        assertFalse(result.dataQuality.isPartial)
        assertEquals(0, result.dataQuality.excludedCount)
    }

    @Test
    fun `AE-C27 INV-C17 mixed currencies failing boundary when conversion makes the foreign row larger the home row wins`() = runTest(timeout = 30.seconds) {
        // Raw numbers say Kiosk (3.00 USD) is smaller than Lidl (4.00 EUR), but 3.00 USD converts to 6.00 EUR.
        val currencyConverter = currencyConverterMock
        // The stub is pinned to the expense's own date (150L, set by expenseWithCategory) instead of any():
        // conversion must be as-of the expense date. A wrong atMillis falls through to the default stub
        // (converts to 0.0 EUR), which would make Kiosk the minimum and flip the winner.
        coEvery { currencyConverter.convertAsOf(3.0, "USD", "EUR", 150L) } returns ConversionResult(
            originalAmount = 3.0, originalCurrency = "USD", convertedAmount = 6.0,
            targetCurrency = "EUR", rateUsed = 2.0, timestamp = 150L
        )
        stubRange(
            100L,
            200L,
            listOf(
                expenseWithCategory(id = 1L, amount = 4.0, currency = "EUR", merchant = "Lidl"),
                expenseWithCategory(id = 2L, amount = 3.0, currency = "USD", merchant = "Kiosk")
            )
        )

        val result = useCase(minIntent())

        assertTrue("expected Summary but was $result", result is FinancialQueryResult.Summary)
        result as FinancialQueryResult.Summary
        assertEquals("Lidl: 4.00 EUR", result.primaryText)
    }

    @Test
    fun `AE-C27 INV-C17 smallest purchase with zero matching rows returns NO_MATCHING_TRANSACTIONS code`() = runTest(timeout = 30.seconds) {
        stubRange(100L, 200L, emptyList())

        val result = useCase(minIntent())

        assertEquals(
            FinancialQueryResult.Unsupported(AssistantUnsupportedReason.NO_MATCHING_TRANSACTIONS),
            result
        )
    }

    @Test
    fun `AE-C27 INV-C17 MIN with every row lacking a rate returns NO_MATCHING_TRANSACTIONS code`() = runTest(timeout = 30.seconds) {
        // Mixed currencies, none convertible and none in the home currency: nothing can be compared.
        val currencyConverter = currencyConverterMock
        coEvery { currencyConverter.convertAsOf(any<Double>(), "USD", "EUR", any<Long>()) } returns null
        coEvery { currencyConverter.convertAsOf(any<Double>(), "GBP", "EUR", any<Long>()) } returns null
        stubRange(
            100L,
            200L,
            listOf(
                expenseWithCategory(id = 1L, amount = 2.0, currency = "USD", merchant = "Bakery"),
                expenseWithCategory(id = 2L, amount = 9.0, currency = "GBP", merchant = "Pub")
            )
        )

        val result = useCase(minIntent())

        assertEquals(
            FinancialQueryResult.Unsupported(AssistantUnsupportedReason.NO_MATCHING_TRANSACTIONS),
            result
        )
    }

    @Test
    fun `AE-C27 INV-C17 MIN with category grouping still returns the category breakdown`() = runTest(timeout = 30.seconds) {
        coEvery { categoryRepository.getAll() } returns listOf(Category(1L, "Groceries", "G", "#00FF00"))
        stubRange(
            100L,
            200L,
            listOf(
                expenseWithCategory(id = 1L, amount = 5.0, currency = "EUR", categoryId = 1L),
                expenseWithCategory(id = 2L, amount = 1.2, currency = "EUR", categoryId = 1L)
            )
        )

        val result = useCase(minIntent(QueryGrouping.CATEGORY))

        assertTrue("expected Breakdown but was $result", result is FinancialQueryResult.Breakdown)
        result as FinancialQueryResult.Breakdown
        assertEquals(UiText.fromKey("domain_ai_top_categories"), result.title)
        assertEquals("Groceries", result.rows.first().label)
    }

    @Test
    fun `AE-C27 INV-C17 MIN with merchant grouping still returns the merchant breakdown`() = runTest(timeout = 30.seconds) {
        stubRange(
            100L,
            200L,
            listOf(
                expenseWithCategory(id = 1L, amount = 5.0, currency = "EUR", merchant = "Lidl", merchantKey = "lidl"),
                expenseWithCategory(id = 2L, amount = 1.2, currency = "EUR", merchant = "Bakery", merchantKey = "bakery")
            )
        )

        val result = useCase(minIntent(QueryGrouping.MERCHANT))

        assertTrue("expected Breakdown but was $result", result is FinancialQueryResult.Breakdown)
        result as FinancialQueryResult.Breakdown
        assertEquals(UiText.fromKey("domain_ai_top_merchants"), result.title)
        assertEquals("Lidl", result.rows.first().label)
    }

    @Test
    fun `AE-C27 INV-C17 LIST with a grouping still returns the transaction list`() = runTest(timeout = 30.seconds) {
        coEvery {
            expenseRepository.getAssistantExpenseCountFiltered(
                startDate = 100L,
                endDate = 200L,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null
            )
        } returns 7

        val result = useCase(minIntent(QueryGrouping.MERCHANT).copy(metric = QueryMetric.LIST))

        assertTrue("expected TransactionList but was $result", result is FinancialQueryResult.TransactionList)
        result as FinancialQueryResult.TransactionList
        assertEquals(7, result.previewCount)
    }

    @Test
    fun `invoke preserves multi value filters for assistant queries`() = runTest {
        val intent = FinancialQueryIntent(
            rawQuery = "show grocery or fuel purchases",
            normalizedQuery = "show grocery or fuel purchases",
            filters = ExpenseQueryFilters(
                period = PeriodRange(100L, 200L),
                merchants = setOf("Lidl", "Shell"),
                categoryIds = setOf(1L, 2L),
                transactionTypes = setOf(
                    com.yourname.expensetracker.domain.model.DomainTransactionType.PURCHASE,
                    com.yourname.expensetracker.domain.model.DomainTransactionType.WITHDRAWAL
                )
            ),
            metric = QueryMetric.COUNT
        )
        coEvery {
            expenseRepository.getAssistantExpensesFiltered(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns listOf(expenseWithCategory(id = 1L, amount = 10.0))

        useCase(intent)

        coVerify {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = 100L,
                endDate = 200L,
                transactionTypes = setOf(TransactionType.PURCHASE, TransactionType.WITHDRAWAL),
                categoryIds = setOf(1L, 2L),
                merchantNames = setOf("Lidl", "Shell"),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null
            )
        }
    }

    @Test
    fun `invoke renders mixed currency totals without fake EUR label`() = runTest {
        coEvery {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = 100L,
                endDate = 200L,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null,
                sortOrder = any()
            )
        } returns listOf(
            expenseWithCategory(id = 1L, amount = 12.0, currency = "EUR"),
            expenseWithCategory(id = 2L, amount = 5.0, currency = "USD")
        )

        val result = useCase(
            FinancialQueryIntent(
                rawQuery = "total this month",
                normalizedQuery = "total this month",
                filters = ExpenseQueryFilters(period = PeriodRange(100L, 200L)),
                metric = QueryMetric.TOTAL
            )
        )

        assertTrue(result is FinancialQueryResult.Summary)
        result as FinancialQueryResult.Summary
        assertEquals("12.00 EUR + 5.00 USD", result.primaryText)
    }

    @Test
    fun `invoke renders mixed currency breakdown rows with valueText and no eur fallback amount`() = runTest {
        coEvery { categoryRepository.getAll() } returns listOf(Category(1L, "Groceries", "G", "#00FF00"))
        coEvery {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = 100L,
                endDate = 200L,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null,
                sortOrder = any()
            )
        } returns listOf(
            expenseWithCategory(id = 1L, amount = 10.0, currency = "EUR", categoryId = 1L),
            expenseWithCategory(id = 2L, amount = 5.0, currency = "USD", categoryId = 1L)
        )

        val result = useCase(
            FinancialQueryIntent(
                rawQuery = "top categories this month",
                normalizedQuery = "top categories this month",
                filters = ExpenseQueryFilters(period = PeriodRange(100L, 200L)),
                metric = QueryMetric.TOTAL,
                grouping = QueryGrouping.CATEGORY
            )
        )

        assertTrue(result is FinancialQueryResult.Breakdown)
        result as FinancialQueryResult.Breakdown
        assertEquals("10.00 EUR + 5.00 USD", result.rows.first().valueText)
        assertNull(result.rows.first().amount)
    }

    // ---- ai-fixes C3: calendar-aware previous period (INV-C11, INV-C12, AE-C12, AE-C18) ----------
    //
    // The use case resolves the previous period in ZoneId.systemDefault(); the JVM default zone is
    // pinned to Europe/Athens (October 2026 is a 745 h month) and restored afterwards.

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

    private fun athensMs(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 0, 0, 0, 0, athens).toInstant().toEpochMilli()

    private fun stubRange(start: Long, end: Long, rows: List<ExpenseWithCategory>) {
        coEvery {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = start,
                endDate = end,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null,
                sortOrder = any()
            )
        } returns rows
    }

    // ---- ai-fixes C4: structured period clarification (INV-C13, AE-C20) ----------------------------

    private fun periodlessFoodIntent() = FinancialQueryIntent(
        rawQuery = "how much on food",
        normalizedQuery = "how much on food",
        filters = ExpenseQueryFilters(categoryIds = setOf(1L), merchants = setOf("Bakery")),
        metric = QueryMetric.TOTAL,
        grouping = QueryGrouping.NONE,
        comparison = QueryComparison.NONE
    )

    @Test
    fun `INV-C13 period clarification carries a PERIOD completion with the three choices in order`() = runTest(timeout = 30.seconds) {
        val intent = periodlessFoodIntent()

        val result = useCase(intent)

        assertTrue("expected Clarification but was $result", result is FinancialQueryResult.Clarification)
        result as FinancialQueryResult.Clarification
        val completion = result.completion
        assertNotNull("a period clarification must carry a completion", completion)
        assertEquals(ClarificationSlot.PERIOD, completion!!.slot)
        assertEquals(
            listOf(
                ClarificationChoice.Period(PeriodChoice.THIS_MONTH),
                ClarificationChoice.Period(PeriodChoice.THIS_WEEK),
                ClarificationChoice.Period(PeriodChoice.LAST_MONTH)
            ),
            completion.choices
        )
    }

    @Test
    fun `INV-C13 period clarification base intent is exactly the incoming intent`() = runTest(timeout = 30.seconds) {
        val intent = periodlessFoodIntent()

        val result = useCase(intent) as FinancialQueryResult.Clarification

        assertEquals(intent, result.completion!!.baseIntent)
        assertNull("the base intent must still lack a period", result.completion!!.baseIntent.filters.period)
        assertEquals(setOf(1L), result.completion!!.baseIntent.filters.categoryIds)
        assertEquals(setOf("Bakery"), result.completion!!.baseIntent.filters.merchants)
    }

    @Test
    fun `INV-C13 period clarification keeps its legacy prompt and option labels`() = runTest(timeout = 30.seconds) {
        val result = useCase(periodlessFoodIntent()) as FinancialQueryResult.Clarification

        assertEquals("Which time period should I use?", result.prompt)
        assertEquals(listOf("This month", "This week", "Last month"), result.options)
        // The typed choices line up one to one with the legacy labels (tap index maps to the choice).
        assertEquals(result.options.size, result.completion!!.choices.size)
    }

    @Test
    fun `INV-C13 control an intent that already has a period yields no clarification`() = runTest(timeout = 30.seconds) {
        val result = useCase(
            FinancialQueryIntent(
                rawQuery = "total this month",
                normalizedQuery = "total this month",
                filters = ExpenseQueryFilters(
                    period = PeriodRange(athensMs(2026, 10, 1), athensMs(2026, 11, 1)),
                    periodGranularity = PeriodGranularity.MONTH
                ),
                metric = QueryMetric.TOTAL
            )
        )

        assertTrue("expected no Clarification but was $result", result !is FinancialQueryResult.Clarification)
    }

    private fun octoberComparisonIntent(granularity: PeriodGranularity?) = FinancialQueryIntent(
        rawQuery = "compare food with previous month",
        normalizedQuery = "compare food with previous month",
        filters = ExpenseQueryFilters(
            period = PeriodRange(athensMs(2026, 10, 1), athensMs(2026, 11, 1)),
            periodGranularity = granularity
        ),
        metric = QueryMetric.TOTAL,
        comparison = QueryComparison.PREVIOUS_EQUIVALENT_PERIOD
    )

    @Test
    fun `AE-C12 comparison on a full October MONTH period uses exactly September 1 to October 1 as previous`() = runTest(timeout = 30.seconds) {
        val oct1 = athensMs(2026, 10, 1)
        val nov1 = athensMs(2026, 11, 1)
        val sep1 = athensMs(2026, 9, 1)
        stubRange(oct1, nov1, listOf(expenseWithCategory(id = 1L, amount = 30.0, currency = "EUR")))
        stubRange(sep1, oct1, listOf(expenseWithCategory(id = 2L, amount = 20.0, currency = "EUR")))

        val result = useCase(octoberComparisonIntent(PeriodGranularity.MONTH))

        assertTrue(result is FinancialQueryResult.Summary)
        result as FinancialQueryResult.Summary
        assertEquals("30.00 EUR", result.primaryText)
        assertEquals("Previous period: 20.00 EUR", result.supportingText)
        // The previous range is exactly [Sep 1, Oct 1): it ends where the base starts (no gap, no overlap).
        coVerify(exactly = 1) {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = sep1,
                endDate = oct1,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null,
                sortOrder = any()
            )
        }
        coVerify(exactly = 1) {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = oct1,
                endDate = nov1,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null,
                sortOrder = any()
            )
        }
    }

    @Test
    fun `AE-C12 previous range is not the 31 day duration shifted back from October`() = runTest(timeout = 30.seconds) {
        val oct1 = athensMs(2026, 10, 1)
        val nov1 = athensMs(2026, 11, 1)
        val sep1 = athensMs(2026, 9, 1)
        // Failing boundary: the old duration rule would query [Oct 1 - 745 h, Oct 1) = [Aug 30 23:00 EEST, Oct 1),
        // because October 2026 is 745 h long (DST fall-back), not 31 * 24 h.
        val durationStart = oct1 - (nov1 - oct1)
        stubRange(oct1, nov1, listOf(expenseWithCategory(id = 1L, amount = 30.0, currency = "EUR")))
        stubRange(sep1, oct1, listOf(expenseWithCategory(id = 2L, amount = 20.0, currency = "EUR")))

        useCase(octoberComparisonIntent(PeriodGranularity.MONTH))

        coVerify(exactly = 0) {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = durationStart,
                endDate = oct1,
                transactionTypes = any(),
                categoryIds = any(),
                merchantNames = any(),
                ownershipFilter = any(),
                minAmount = any(),
                maxAmount = any(),
                sortOrder = any()
            )
        }
    }

    @Test
    fun `AE-C18 comparison on an October range with null granularity is classified as a full month`() = runTest(timeout = 30.seconds) {
        val oct1 = athensMs(2026, 10, 1)
        val nov1 = athensMs(2026, 11, 1)
        val sep1 = athensMs(2026, 9, 1)
        stubRange(oct1, nov1, listOf(expenseWithCategory(id = 1L, amount = 30.0, currency = "EUR")))
        stubRange(sep1, oct1, listOf(expenseWithCategory(id = 2L, amount = 20.0, currency = "EUR")))

        val result = useCase(octoberComparisonIntent(granularity = null))

        assertTrue(result is FinancialQueryResult.Summary)
        result as FinancialQueryResult.Summary
        assertEquals("Previous period: 20.00 EUR", result.supportingText)
        coVerify(exactly = 1) {
            expenseRepository.getAssistantExpensesFiltered(
                startDate = sep1,
                endDate = oct1,
                transactionTypes = emptySet(),
                categoryIds = emptySet(),
                merchantNames = emptySet(),
                ownershipFilter = OwnershipFilter.ALL,
                minAmount = null,
                maxAmount = null,
                sortOrder = any()
            )
        }
    }

    private fun expenseWithCategory(
        id: Long,
        amount: Double,
        currency: String = "EUR",
        merchant: String = "Lidl",
        merchantKey: String? = merchant.lowercase(),
        categoryId: Long? = null
    ) = ExpenseWithCategory(
        expense = Expense(
            id = id,
            amount = amount,
            currency = currency,
            merchant = merchant,
            merchantKey = merchantKey,
            categoryId = categoryId,
            transactionType = TransactionType.PURCHASE,
            date = 150L
        ),
        category = null
    )
}