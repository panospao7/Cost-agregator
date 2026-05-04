package com.yourname.expensetracker.domain.usecase.budget

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.model.Result
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CalculateBudgetStatusUseCaseTest : AnalyticsEngineTestBase() {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var useCase: CalculateBudgetStatusUseCase

    @Before
    fun initUseCase() {
        budgetRepository = mockk(relaxed = true)
        useCase = CalculateBudgetStatusUseCase(budgetRepository)
    }

    @Test
    fun `budget under spent status correct`() = runTest {
        val status = budgetStatus(
            id = 1L,
            budgetAmount = 500.0,
            spent = 125.0,
            remaining = 375.0,
            percentUsed = 0.25f,
            health = BudgetHealthStatus.ON_TRACK
        )
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(status))

        val result = useCase()

        assertTrue(result is Result.Success)
        val payload = (result as Result.Success).data
        assertEquals(1, payload.size)
        assertEquals(BudgetHealthStatus.ON_TRACK, payload.first().healthStatus)
        assertApproxEquals(125.0, payload.first().spentAmount, 0.0001)
    }

    @Test
    fun `budget exceeded status correct`() = runTest {
        val status = budgetStatus(
            id = 2L,
            budgetAmount = 300.0,
            spent = 360.0,
            remaining = 0.0,
            percentUsed = 1.2f,
            health = BudgetHealthStatus.EXCEEDED
        )
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(status))

        val result = useCase()

        assertTrue(result is Result.Success)
        val payload = (result as Result.Success).data
        assertEquals(BudgetHealthStatus.EXCEEDED, payload.first().healthStatus)
        assertApproxEquals(360.0, payload.first().spentAmount, 0.0001)
        assertApproxEquals(0.0, payload.first().remainingAmount, 0.0001)
    }

    @Test
    fun `no budget null status`() = runTest {
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        val result = useCase()

        assertTrue(result is Result.Success)
        val payload = (result as Result.Success).data
        assertEquals(0, payload.size)
        assertNull(payload.firstOrNull())
    }

    @Test
    fun `multiple budgets all calculated`() = runTest {
        val first = budgetStatus(1L, 1000.0, 250.0, 750.0, 0.25f, BudgetHealthStatus.ON_TRACK)
        val second = budgetStatus(2L, 200.0, 190.0, 10.0, 0.95f, BudgetHealthStatus.WARNING)
        val third = budgetStatus(3L, 300.0, 330.0, 0.0, 1.1f, BudgetHealthStatus.EXCEEDED)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(first, second, third))

        val result = useCase()

        assertTrue(result is Result.Success)
        val payload = (result as Result.Success).data
        assertEquals(3, payload.size)
        assertEquals(BudgetHealthStatus.ON_TRACK, payload[0].healthStatus)
        assertEquals(BudgetHealthStatus.WARNING, payload[1].healthStatus)
        assertEquals(BudgetHealthStatus.EXCEEDED, payload[2].healthStatus)
        assertApproxEquals(190.0, payload[1].spentAmount, 0.0001)
    }

    private fun budgetStatus(
        id: Long,
        budgetAmount: Double,
        spent: Double,
        remaining: Double,
        percentUsed: Float,
        health: BudgetHealthStatus
    ): BudgetStatus {
        return BudgetStatus(
            budget = Budget(
                id = id,
                categoryId = null,
                amount = budgetAmount,
                period = BudgetPeriod.MONTHLY,
                startDate = 1_700_000_000_000L
            ),
            category = null,
            spentAmount = spent,
            remainingAmount = remaining,
            percentUsed = percentUsed,
            healthStatus = health,
            periodStart = 1_700_000_000_000L,
            periodEnd = 1_702_592_000_000L,
            effectiveLimit = 0.0,
        )
    }
}