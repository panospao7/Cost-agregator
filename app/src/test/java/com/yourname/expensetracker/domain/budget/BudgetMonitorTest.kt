package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.dateToMillis
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetMonitorTest {

    private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var monitor: BudgetMonitor

    @Before
    fun setUp() {
        monitor = BudgetMonitor(
            budgetRepository = budgetRepository,
            timeProvider = timeProvider,
            notificationService = notificationService,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun `check budgets sends warning notification and updates warning timestamp`() = runTest(testDispatcher) {
        val now = atDateTime(2026, 4, 5, 12, 0)
        every { timeProvider.now() } returns now
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(
                budgetStatus(
                    budget = budget(id = 11L, period = BudgetPeriod.MONTHLY),
                    spentAmount = 80.0,
                    percentUsed = 0.80f,
                    periodStart = dateToMillis("2026-04-01")
                )
            )
        )

        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { budgetRepository.updateWarningNotification(11L, now) }
        verify(exactly = 1) {
            notificationService.sendBudgetAlert(
                11,
                "Budget Warning",
                "You've spent €80.00 (80%) of your Groceries budget."
            )
        }
    }

    @Test
    fun `check budgets sends critical notification and updates critical timestamp`() = runTest(testDispatcher) {
        val now = atDateTime(2026, 4, 6, 9, 0)
        every { timeProvider.now() } returns now
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(
                budgetStatus(
                    budget = budget(id = 22L, period = BudgetPeriod.MONTHLY),
                    spentAmount = 95.0,
                    percentUsed = 0.95f,
                    periodStart = dateToMillis("2026-04-01")
                )
            )
        )

        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { budgetRepository.updateCriticalNotification(22L, now) }
        verify(exactly = 1) {
            notificationService.sendBudgetAlert(
                22,
                "Critical Budget Warning",
                "You've spent €95.00 (95%) of your Groceries budget."
            )
        }
    }

    @Test
    fun `check budgets sends exceeded notification and updates exceeded timestamp`() = runTest(testDispatcher) {
        val now = atDateTime(2026, 4, 7, 8, 0)
        every { timeProvider.now() } returns now
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(
                budgetStatus(
                    budget = budget(id = 33L, period = BudgetPeriod.WEEKLY),
                    spentAmount = 130.0,
                    percentUsed = 1.30f,
                    periodStart = dateToMillis("2026-04-07")
                )
            )
        )

        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { budgetRepository.updateExceededNotification(33L, now) }
        verify(exactly = 1) {
            notificationService.sendBudgetAlert(
                33,
                "Budget Exceeded!",
                "You've spent €130.00 (130%) of your Groceries budget."
            )
        }
    }

    @Test
    fun `onBackground clears transient state and next foreground check still runs`() = runTest(testDispatcher) {
        val firstNow = atDateTime(2026, 4, 8, 10, 0)
        val secondNow = firstNow + 5_000L
        every { timeProvider.now() } returnsMany listOf(firstNow, secondNow)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        monitor.onBackground()
        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 2) { budgetRepository.getBudgetStatuses() }
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    private fun budget(id: Long, period: BudgetPeriod): Budget {
        return Budget(
            id = id,
            categoryId = 2L,
            amount = 100.0,
            period = period,
            periodMode = "ROLLING",
            startDate = dateToMillis("2026-04-01"),
            notifyAtWarning = 0.75f,
            notifyAtCritical = 0.90f,
            lastWarningNotifiedAt = null,
            lastCriticalNotifiedAt = null,
            lastExceededNotifiedAt = null
        )
    }

    private fun budgetStatus(
        budget: Budget,
        spentAmount: Double,
        percentUsed: Float,
        periodStart: Long
    ): BudgetStatus {
        val remainingAmount = (budget.amount - spentAmount).coerceAtLeast(0.0)
        return BudgetStatus(
            budget = budget,
            category = Category(id = 2L, name = "Groceries", icon = "🛒", color = "#33FF57"),
            spentAmount = spentAmount,
            remainingAmount = remainingAmount,
            percentUsed = percentUsed,
            healthStatus = BudgetHealthStatus.WARNING,
            periodStart = periodStart,
            periodEnd = periodStart + (7L * 24L * 60L * 60L * 1000L),
            effectiveLimit = 0.0
        )
    }

    private fun atDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.YEAR, year)
            set(java.util.Calendar.MONTH, month - 1)
            set(java.util.Calendar.DAY_OF_MONTH, day)
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}