package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.dao.PipelineDiagnosticEventDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetMonitorStressTest {

    private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var monitor: BudgetMonitor

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { timeProvider.now() } returns System.currentTimeMillis()
        monitor = BudgetMonitor(budgetRepository, timeProvider, notificationService, testDispatcher, diagnosticEventDao = mockk<PipelineDiagnosticEventDao>(relaxed = true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun status(budget: Budget, percentUsed: Float, periodStart: Long, periodEnd: Long) =
        BudgetStatus(
            budget = budget,
            category = Category(id = 1, name = "Groceries", icon = "", color = "#FFFFFF"),
            spentAmount = budget.amount * percentUsed,
            remainingAmount = budget.amount * (1 - percentUsed),
            percentUsed = percentUsed,
            healthStatus = when {
                percentUsed >= 1f -> BudgetHealthStatus.EXCEEDED
                percentUsed >= budget.notifyAtCritical -> BudgetHealthStatus.CRITICAL
                percentUsed >= budget.notifyAtWarning -> BudgetHealthStatus.WARNING
                else -> BudgetHealthStatus.ON_TRACK
            },
            periodStart = periodStart,
            periodEnd = periodEnd,
            effectiveLimit = 0.0,
        )

    // ============================================================================
    // SECTION 1: EXCEEDED NOTIFICATION
    // ============================================================================

    @Test
    fun `stress - percent at or above 100 triggers exceeded notification`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        val budget = Budget(
            id = 1,
            amount = 100.0,
            categoryId = 1L,
            period = BudgetPeriod.MONTHLY,
            startDate = now,
            notifyAtWarning = 0.5f,
            notifyAtCritical = 0.9f,
            lastExceededNotifiedAt = null
        )
        coEvery { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(status(budget, 1.0f, now - 86400000L, now + 86400000L))
        )
        monitor.checkBudgets()
        advanceUntilIdle()
        coVerify { budgetRepository.updateExceededNotification(1L, any()) }
        verify { notificationService.sendBudgetAlert(1, "Budget Exceeded!", any()) }
    }

    // ============================================================================
    // SECTION 2: CRITICAL NOTIFICATION
    // ============================================================================

    @Test
    fun `stress - percent at or above critical triggers critical notification`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        val budget = Budget(
            id = 2,
            amount = 200.0,
            categoryId = 1L,
            period = BudgetPeriod.MONTHLY,
            startDate = now,
            notifyAtWarning = 0.5f,
            notifyAtCritical = 0.9f,
            lastCriticalNotifiedAt = null
        )
        coEvery { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(status(budget, 0.95f, now - 86400000L, now + 86400000L))
        )
        monitor.checkBudgets()
        advanceUntilIdle()
        coVerify { budgetRepository.updateCriticalNotification(2L, any()) }
        verify { notificationService.sendBudgetAlert(2, "Critical Budget Warning", any()) }
    }

    // ============================================================================
    // SECTION 3: NO NOTIFICATION WHEN SPENT OR BUDGET ZERO
    // ============================================================================

    @Test
    fun `stress - spent zero does not notify`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        val budget = Budget(
            id = 3,
            amount = 100.0,
            categoryId = 1L,
            period = BudgetPeriod.MONTHLY,
            startDate = now,
            notifyAtWarning = 0.5f,
            notifyAtCritical = 0.9f
        )
        val statusWithZeroSpent = BudgetStatus(
            budget = budget,
            category = Category(id = 1, name = "Groceries", icon = "", color = "#FFFFFF"),
            spentAmount = 0.0,
            remainingAmount = 100.0,
            percentUsed = 0.6f,
            healthStatus = BudgetHealthStatus.WARNING,
            periodStart = now - 86400000L,
            periodEnd = now + 86400000L,
            effectiveLimit = 0.0,
        )
        coEvery { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(statusWithZeroSpent))
        monitor.checkBudgets()
        advanceUntilIdle()
        coVerify(exactly = 0) { budgetRepository.updateWarningNotification(any(), any()) }
        coVerify(exactly = 0) { budgetRepository.updateCriticalNotification(any(), any()) }
        coVerify(exactly = 0) { budgetRepository.updateExceededNotification(any(), any()) }
    }

    @Test
    fun `stress - budget amount zero does not notify`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        val budget = Budget(
            id = 4,
            amount = 0.0,
            categoryId = 1L,
            period = BudgetPeriod.MONTHLY,
            startDate = now,
            notifyAtWarning = 0.5f,
            notifyAtCritical = 0.9f
        )
        coEvery { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(status(budget, 1.0f, now - 86400000L, now + 86400000L))
        )
        monitor.checkBudgets()
        advanceUntilIdle()
        coVerify(exactly = 0) { budgetRepository.updateExceededNotification(any(), any()) }
    }

    // ============================================================================
    // SECTION 4: MIN_CHECK_INTERVAL
    // ============================================================================

    @Test
    fun `stress - checkBudgets skipped when MIN_CHECK_INTERVAL not elapsed`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        every { timeProvider.now() } returns now
        val budget = Budget(
            id = 5,
            amount = 100.0,
            categoryId = 1L,
            period = BudgetPeriod.MONTHLY,
            startDate = now,
            notifyAtWarning = 0.5f,
            notifyAtCritical = 0.9f
        )
        coEvery { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(status(budget, 0.6f, now - 86400000L, now + 86400000L))
        )
        monitor.checkBudgets()
        advanceUntilIdle()
        coVerify(exactly = 1) { budgetRepository.getBudgetStatuses() }

        every { timeProvider.now() } returns now + 30_000
        monitor.checkBudgets()
        advanceUntilIdle()
        coVerify(exactly = 1) { budgetRepository.getBudgetStatuses() }
    }

    // ============================================================================
    // SECTION 5: LIFECYCLE + CONCURRENCY REGRESSIONS
    // ============================================================================

    @Test
    fun `stress - concurrent checks preserve throttle coherence and read repository once`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        every { timeProvider.now() } returns now
        val budget = Budget(
            id = 6,
            amount = 100.0,
            categoryId = 1L,
            period = BudgetPeriod.MONTHLY,
            startDate = System.currentTimeMillis(),
            notifyAtWarning = 0.5f,
            notifyAtCritical = 0.9f
        )
        coEvery { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(status(budget, 0.6f, 0L, Long.MAX_VALUE))
        )

        repeat(10) {
            launch { monitor.checkBudgets() }
        }

        advanceUntilIdle()

        coVerify(exactly = 1) { budgetRepository.getBudgetStatuses() }
        coVerify(exactly = 1) { budgetRepository.updateWarningNotification(6L, any()) }
        verify(exactly = 1) { notificationService.sendBudgetAlert(6, "Budget Warning", any()) }
    }

    @Test
    fun `stress - onBackground cancels in flight work and next foreground check fetches fresh state`() = runTest(testDispatcher) {
        val firstNow = System.currentTimeMillis()
        val secondNow = firstNow + 5_000L
        every { timeProvider.now() } returnsMany listOf(firstNow, secondNow)

        val budget = Budget(
            id = 61,
            amount = 100.0,
            categoryId = 1L,
            period = BudgetPeriod.MONTHLY,
            startDate = firstNow,
            notifyAtWarning = 0.5f,
            notifyAtCritical = 0.9f
        )

        coEvery { budgetRepository.getBudgetStatuses() } returnsMany listOf(
            flow {
                delay(1_000L)
                emit(listOf(status(budget, 0.6f, 0L, Long.MAX_VALUE)))
            },
            flowOf(emptyList())
        )

        monitor.checkBudgets()
        testDispatcher.scheduler.runCurrent()

        monitor.onBackground()
        monitor.checkBudgets()

        advanceUntilIdle()

        coVerify(exactly = 2) { budgetRepository.getBudgetStatuses() }
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
        coVerify(exactly = 0) { budgetRepository.updateWarningNotification(any(), any()) }
        coVerify(exactly = 0) { budgetRepository.updateCriticalNotification(any(), any()) }
        coVerify(exactly = 0) { budgetRepository.updateExceededNotification(any(), any()) }
    }

    @Test
    fun `stress - destroy permanently cancels monitor scope`() = runTest(testDispatcher) {
        monitor.destroy()

        every { timeProvider.now() } returns System.currentTimeMillis()

        val budget = Budget(
            id = 62,
            amount = 100.0,
            categoryId = 1L,
            period = BudgetPeriod.MONTHLY,
            startDate = System.currentTimeMillis(),
            notifyAtWarning = 0.5f,
            notifyAtCritical = 0.9f
        )
        coEvery { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(status(budget, 0.6f, 0L, Long.MAX_VALUE))
        )

        monitor.checkBudgets()
        advanceUntilIdle()

        coVerify(exactly = 0) { budgetRepository.getBudgetStatuses() }
    }

    // ============================================================================
    // SECTION 6: EMPTY STATUSES
    // ============================================================================

    @Test
    fun `stress - empty budget statuses does not crash`() = runTest(testDispatcher) {
        coEvery { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        monitor.checkBudgets()
        advanceUntilIdle()
        coVerify(exactly = 1) { budgetRepository.getBudgetStatuses() }
    }

    // ============================================================================
    // SECTION 7: ALERT FLOODING - MULTIPLE BUDGETS
    // ============================================================================

    @Test
    fun `stress - multiple budgets at critical all get notified`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        val statuses = (1..5).map { id ->
            val budget = Budget(
                id = id.toLong(),
                amount = 100.0 * id,
                categoryId = id.toLong(),
                period = BudgetPeriod.MONTHLY,
                startDate = now,
                notifyAtWarning = 0.5f,
                notifyAtCritical = 0.9f,
                lastCriticalNotifiedAt = null
            )
            status(budget, 0.95f, now - 86400000L, now + 86400000L)
        }
        coEvery { budgetRepository.getBudgetStatuses() } returns flowOf(statuses)
        monitor.checkBudgets()
        advanceUntilIdle()
        coVerify(exactly = 5) { budgetRepository.updateCriticalNotification(any(), any()) }
    }

    // ============================================================================
    // SECTION 8: PERIOD START BOUNDARY - lastNotified < periodStart
    // ============================================================================

    @Test
    fun `stress - lastNotified before periodStart should notify`() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        val periodStart = now - 86400000L * 2
        val lastNotified = periodStart - 86400000L
        val budget = Budget(
            id = 7,
            amount = 100.0,
            categoryId = 1L,
            period = BudgetPeriod.MONTHLY,
            startDate = now,
            notifyAtWarning = 0.5f,
            notifyAtCritical = 0.9f,
            lastWarningNotifiedAt = lastNotified
        )
        coEvery { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(status(budget, 0.6f, periodStart, now + 86400000L))
        )
        monitor.checkBudgets()
        advanceUntilIdle()
        coVerify { budgetRepository.updateWarningNotification(7L, any()) }
    }
}