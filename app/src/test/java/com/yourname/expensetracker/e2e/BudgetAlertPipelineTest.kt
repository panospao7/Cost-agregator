package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.data.database.dao.PipelineDiagnosticEventDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.service.NotificationService
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test

class BudgetAlertPipelineTest : AnalyticsEngineTestBase() {

    private lateinit var budgetMonitor: BudgetMonitor
    private lateinit var budgetCalculator: BudgetCalculator
    private lateinit var budgetRepo: BudgetRepository
    private lateinit var notificationService: NotificationService

    @Before
    override fun setUp() {
        super.setUp()
        budgetRepo = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        budgetCalculator = BudgetCalculator(timeProvider)
        budgetMonitor = BudgetMonitor(budgetRepo, timeProvider, notificationService, testDispatcher, diagnosticEventDao = mockk<PipelineDiagnosticEventDao>(relaxed = true))
    }

    @Test
    fun `budget at 90 percent triggers warning notification`() = runTest {
        val status = createBudgetStatus(
            budgetId = 1L,
            amount = 500.0,
            spent = 450.0,
            percentUsed = 0.90f,
            warningThreshold = 0.90f,
            criticalThreshold = 0.95f
        )

        every { budgetRepo.getBudgetStatuses() } returns flowOf(listOf(status))

        budgetMonitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 1) {
            notificationService.sendBudgetAlert(1, "Budget Warning", any())
        }
        coVerify(exactly = 1) { budgetRepo.updateWarningNotification(1L, fixedNow) }
    }

    @Test
    fun `budget at 100 percent triggers critical notification`() = runTest {
        val status = createBudgetStatus(
            budgetId = 2L,
            amount = 500.0,
            spent = 500.0,
            percentUsed = 0.999f,
            warningThreshold = 0.80f,
            criticalThreshold = 0.90f
        )

        every { budgetRepo.getBudgetStatuses() } returns flowOf(listOf(status))

        budgetMonitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 1) {
            notificationService.sendBudgetAlert(2, "Critical Budget Warning", any())
        }
        coVerify(exactly = 1) { budgetRepo.updateCriticalNotification(2L, fixedNow) }
    }

    @Test
    fun `budget at 110 percent triggers exceeded notification`() = runTest {
        val status = createBudgetStatus(
            budgetId = 3L,
            amount = 500.0,
            spent = 550.0,
            percentUsed = 1.10f,
            warningThreshold = 0.75f,
            criticalThreshold = 0.90f
        )

        every { budgetRepo.getBudgetStatuses() } returns flowOf(listOf(status))

        budgetMonitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 1) {
            notificationService.sendBudgetAlert(3, "Budget Exceeded!", any())
        }
        coVerify(exactly = 1) { budgetRepo.updateExceededNotification(3L, fixedNow) }
    }

    @Test
    fun `budget monitor cleanup prevents subsequent alert processing`() = runTest {
        budgetMonitor.cleanup()

        budgetMonitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 0) { budgetRepo.getBudgetStatuses() }
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
        coVerify(exactly = 0) { budgetRepo.updateWarningNotification(any(), any()) }
        coVerify(exactly = 0) { budgetRepo.updateCriticalNotification(any(), any()) }
        coVerify(exactly = 0) { budgetRepo.updateExceededNotification(any(), any()) }
    }

    private fun createBudgetStatus(
        budgetId: Long,
        amount: Double,
        spent: Double,
        percentUsed: Float,
        warningThreshold: Float,
        criticalThreshold: Float
    ): BudgetStatus {
        val budget = Budget(
            id = budgetId,
            categoryId = null,
            amount = amount,
            period = BudgetPeriod.MONTHLY,
            startDate = march2026Start,
            notifyAtWarning = warningThreshold,
            notifyAtCritical = criticalThreshold,
            lastWarningNotifiedAt = null,
            lastCriticalNotifiedAt = null,
            lastExceededNotifiedAt = null
        )

        val (periodStart, periodEnd) = budgetCalculator.calculatePeriodRange(budget, fixedNow)

        return BudgetStatus(
            budget = budget,
            category = null,
            spentAmount = spent,
            remainingAmount = (amount - spent).coerceAtLeast(0.0),
            percentUsed = percentUsed,
            healthStatus = healthStatusFor(percentUsed, warningThreshold, criticalThreshold),
            periodStart = periodStart,
            periodEnd = periodEnd,
            effectiveLimit = amount,
        )
    }

    private fun healthStatusFor(
        percentUsed: Float,
        warningThreshold: Float,
        criticalThreshold: Float
    ): BudgetHealthStatus {
        return when {
            percentUsed >= 1.0f -> BudgetHealthStatus.EXCEEDED
            percentUsed >= criticalThreshold -> BudgetHealthStatus.CRITICAL
            percentUsed >= warningThreshold -> BudgetHealthStatus.WARNING
            else -> BudgetHealthStatus.ON_TRACK
        }
    }
}