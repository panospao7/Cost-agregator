package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.OwnershipFilter
import com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.FinancialQueryResult
import com.yourname.expensetracker.domain.ai.model.QueryComparison
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.model.PeriodRange
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.MultiConversionAggregate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.test.runTest

class ExecuteFinancialQueryUseCaseTest {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var useCase: ExecuteFinancialQueryUseCase

    @Before
    fun setup() {
        expenseRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        useCase = ExecuteFinancialQueryUseCase(expenseRepository, categoryRepository, currencyConverter = mockk<CurrencyConverter>(relaxed = true).also {
            coEvery { it.convertMultiple(any(), any()) } returns MultiConversionAggregate(
                total = 0.0, targetCurrency = "EUR", failedConversions = emptyList()
            )
            coEvery { it.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>()) } returns ConversionResult(
                originalAmount = 0.0, originalCurrency = "EUR", convertedAmount = 0.0,
                targetCurrency = "EUR", rateUsed = 1.0, timestamp = 0L
            )
        }, currencySettingsRepository = mockk())
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