package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.OwnershipFilter
import com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.model.QueryOwnershipScope
import com.yourname.expensetracker.domain.model.PeriodRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MapFinancialQueryToNavigationUseCaseTest {

    private lateinit var useCase: MapFinancialQueryToNavigationUseCase

    @Before
    fun setup() {
        useCase = MapFinancialQueryToNavigationUseCase()
    }

    @Test
    fun `invoke maps supported list intent to transaction filter`() {
        val intent = FinancialQueryIntent(
            rawQuery = "show groceries this month",
            normalizedQuery = "show groceries this month",
            filters = ExpenseQueryFilters(
                period = PeriodRange(100L, 200L),
                categoryIds = setOf(2L),
                merchants = setOf("Lidl"),
                transactionTypes = setOf(TransactionType.PURCHASE),
                ownership = QueryOwnershipScope.SHARED,
                minAmount = 10.0,
                maxAmount = 30.0
            ),
            metric = QueryMetric.LIST
        )

        val result = useCase(intent)

        requireNotNull(result)
        assertEquals(2L, result.categoryId)
        assertEquals("Lidl", result.merchantName)
        assertEquals(TransactionType.PURCHASE, result.transactionType)
        assertEquals(Pair(100L, 200L), result.dateRange)
        assertEquals(OwnershipFilter.SHARED, result.ownership)
        assertEquals(10.0, result.minAmount)
        assertEquals(30.0, result.maxAmount)
    }

    @Test
    fun `invoke maps all ownership to null ownership filter`() {
        val intent = FinancialQueryIntent(
            rawQuery = "total this month",
            normalizedQuery = "total this month",
            filters = ExpenseQueryFilters(
                period = PeriodRange(1L, 2L),
                ownership = QueryOwnershipScope.ALL
            ),
            metric = QueryMetric.TOTAL
        )

        val result = useCase(intent)

        requireNotNull(result)
        assertNull(result.ownership)
    }
}
