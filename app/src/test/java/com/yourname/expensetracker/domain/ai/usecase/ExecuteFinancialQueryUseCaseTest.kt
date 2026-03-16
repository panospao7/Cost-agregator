package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.dao.CategoryTotal
import com.yourname.expensetracker.data.database.dao.MerchantStats
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.FinancialQueryResult
import com.yourname.expensetracker.domain.ai.model.QueryComparison
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.model.QueryOwnershipScope
import com.yourname.expensetracker.domain.model.PeriodRange
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
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
        useCase = ExecuteFinancialQueryUseCase(expenseRepository, categoryRepository)
    }

    @Test
    fun `invoke returns summary total for simple purchase total`() = runTest {
        coEvery { expenseRepository.getTotalForPeriod(100L, 200L) } returns 42.5

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
        coEvery { expenseRepository.getTotalForPeriod(100L, 200L) } returns 80.0
        coEvery { expenseRepository.getTotalForPeriod(0L, 100L) } returns 60.0

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
        coEvery { expenseRepository.getTopMerchantsForPeriod(100L, 200L, 8) } returns listOf(
            MerchantStats("lidl", "Lidl", 50.0, 3, 16.6, 10.0, 30.0, 101L, 199L)
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
        assertEquals(50.0, result.rows.first().amount)
    }

    @Test
    fun `invoke returns category breakdown`() = runTest {
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(1L, "Groceries", "G", "#00FF00")
        )
        coEvery { expenseRepository.getCategoryTotalsForPeriod(100L, 200L) } returns listOf(
            CategoryTotal(1L, 99.0, 4)
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
    }

    @Test
    fun `invoke returns transaction list for list metric`() = runTest {
        coEvery {
            expenseRepository.getExpensesPagedDynamic(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns listOf(
            ExpenseWithCategory(
                expense = Expense(
                    id = 1L,
                    amount = 12.0,
                    merchant = "Lidl",
                    transactionType = TransactionType.PURCHASE,
                    date = 150L
                ),
                category = null
            )
        )

        val intent = FinancialQueryIntent(
            rawQuery = "show groceries this month",
            normalizedQuery = "show groceries this month",
            filters = ExpenseQueryFilters(period = PeriodRange(100L, 200L)),
            metric = QueryMetric.LIST
        )

        val result = useCase(intent)

        assertTrue(result is FinancialQueryResult.TransactionList)
        result as FinancialQueryResult.TransactionList
        assertEquals(1, result.previewCount)
    }

    @Test
    fun `invoke returns largest purchase summary`() = runTest {
        coEvery { expenseRepository.getLargestExpenseForPeriod(100L, 200L) } returns Expense(
            id = 1L,
            amount = 120.0,
            merchant = "Amazon",
            transactionType = TransactionType.PURCHASE,
            date = 150L
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
}
