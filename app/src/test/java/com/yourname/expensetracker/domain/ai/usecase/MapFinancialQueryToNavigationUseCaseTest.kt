package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.navigation.DomainOwnershipFilter
import com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.model.QueryOwnershipScope
import com.yourname.expensetracker.domain.model.PeriodRange
import kotlinx.coroutines.test.runTest
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MapFinancialQueryToNavigationUseCaseTest {

    private lateinit var useCase: MapFinancialQueryToNavigationUseCase

    @Before
    fun setup() {
        useCase = MapFinancialQueryToNavigationUseCase(mockk(relaxed = true))
    }

    @Test
    fun `invoke maps supported list intent to transaction filter`() = runTest {
        val intent = FinancialQueryIntent(
            rawQuery = "show groceries this month",
            normalizedQuery = "show groceries this month",
            filters = ExpenseQueryFilters(
                period = PeriodRange(100L, 200L),
                categoryIds = setOf(2L),
                merchants = setOf("Lidl"),
                transactionTypes = setOf(DomainTransactionType.PURCHASE),
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
        assertEquals(DomainTransactionType.PURCHASE, result.transactionType)
        assertEquals(Pair(100L, 200L), result.dateRange)
        assertEquals(DomainOwnershipFilter.SHARED, result.ownership)
        assertEquals(10.0, result.minAmount)
        assertEquals(30.0, result.maxAmount)
    }

    @Test
    fun `invoke maps all ownership to null ownership filter`() = runTest {
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
