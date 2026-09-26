package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.dao.PipelineDiagnosticEventDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.dateToMillis
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetMonitorTest {

    private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val diagnosticEventWriter = mockk<DiagnosticEventWriter>(relaxed = true)
    private val emittedEvents = mutableListOf<DiagnosticEvent>()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var monitor: BudgetMonitor

    @Before
    fun setUp() {
        emittedEvents.clear()
        coEvery { diagnosticEventWriter.emit(capture(emittedEvents)) } returns Unit
        every { notificationService.sendBudgetAlert(any(), any(), any()) } returns
            NotificationService.DeliveryResult.DELIVERED
        monitor = BudgetMonitor(
            budgetRepository = budgetRepository,
            timeProvider = timeProvider,
            notificationService = notificationService,
            ioDispatcher = testDispatcher,
            diagnosticEventWriter = diagnosticEventWriter,
            writeBarrier = mockk(relaxed = true),
            diagnosticSink = mockk(relaxed = true)
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
                "You've spent €80.00 (80%) of your Groceries budget (€100.00)."
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
                "You've spent €95.00 (95%) of your Groceries budget (€100.00)."
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
                "You've spent €130.00 (130%) of your Groceries budget (€100.00)."
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

    @Test
    fun monitor_emits_skip_event_when_spent_zero() = runTest(testDispatcher) {
        val now = atDateTime(2026, 4, 9, 11, 0)
        every { timeProvider.now() } returns now
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(
                budgetStatus(
                    budget = budget(id = 44L, period = BudgetPeriod.MONTHLY),
                    spentAmount = 0.0,
                    percentUsed = 0.0f,
                    periodStart = dateToMillis("2026-04-01")
                )
            )
        )

        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        // P6-CURRENT-027(a): the no-op early return must now leave a durable SKIPPED record.
        val skip = emittedEvents.singleOrNull { it.stage == "STATUS_SKIPPED" }
        assertTrue("Expected a STATUS_SKIPPED diagnostic event", skip != null)
        assertTrue("Skip event must use SKIPPED outcome", skip!!.outcome == EventOutcome.SKIPPED)
        assertTrue("Skip event must target the budget", skip.entityId == 44L)
        assertTrue(
            "Skip event must record the no-op reason",
            skip.metadata.toJson().contains("NO_SPEND_OR_LIMIT")
        )
        // No alert should ever be sent for a zero-spend budget.
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    @Test
    fun monitor_flags_gross_fallback_when_adjusted_null() = runTest(testDispatcher) {
        val now = atDateTime(2026, 4, 10, 12, 0)
        every { timeProvider.now() } returns now
        // adjustedSpendBreakdown defaults to null in budgetStatus(), forcing the gross-spend fallback.
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(
                budgetStatus(
                    budget = budget(id = 55L, period = BudgetPeriod.MONTHLY),
                    spentAmount = 80.0,
                    percentUsed = 0.80f,
                    periodStart = dateToMillis("2026-04-01")
                )
            )
        )

        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        // P6-CURRENT-027(b): STATUS_COMPUTED must carry a grossFallback=true flag when the
        // shared-expense breakdown was unavailable.
        val computed = emittedEvents.singleOrNull { it.stage == "STATUS_COMPUTED" }
        assertTrue("Expected a STATUS_COMPUTED diagnostic event", computed != null)
        val parsed = org.json.JSONObject(computed!!.metadata.toJson())
        assertTrue("STATUS_COMPUTED must include grossFallback flag", parsed.has("grossFallback"))
        assertTrue(
            "grossFallback must be true when adjusted breakdown is null",
            parsed.getBoolean("grossFallback")
        )
    }

    @Test
    fun `monitor skips alerts when percent is unknown`() = runTest(testDispatcher) {
        // RP-09 (P6-005): percentKnown=false (limit conversion failed) — the placeholder
        // 0f percent must never drive a threshold comparison or an alert.
        val now = atDateTime(2026, 4, 11, 9, 0)
        every { timeProvider.now() } returns now
        val status = budgetStatus(
            budget = budget(id = 66L, period = BudgetPeriod.MONTHLY),
            spentAmount = 80.0,
            percentUsed = 0.95f,
            periodStart = dateToMillis("2026-04-01")
        ).copy(percentKnown = false)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(status))

        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
        coVerify(exactly = 0) { budgetRepository.updateWarningNotification(any(), any()) }
        coVerify(exactly = 0) { budgetRepository.updateCriticalNotification(any(), any()) }
        coVerify(exactly = 0) { budgetRepository.updateExceededNotification(any(), any()) }
        val skip = emittedEvents.singleOrNull { it.stage == "STATUS_SKIPPED" }
        assertTrue("Expected a STATUS_SKIPPED diagnostic for unknown percent", skip != null)
        assertTrue("Skip must record the PERCENT_UNKNOWN reason", skip!!.metadata.toJson().contains("PERCENT_UNKNOWN"))
    }

    @Test
    fun `monitor appends partial-data qualifier when spend conversion was partial`() = runTest(testDispatcher) {
        // RP-09 (P6-005): with a known limit but partially converted spend, the alert
        // fires normally AND carries the bounded "some transactions excluded" qualifier.
        val now = atDateTime(2026, 4, 12, 9, 0)
        every { timeProvider.now() } returns now
        val status = budgetStatus(
            budget = budget(id = 77L, period = BudgetPeriod.MONTHLY),
            spentAmount = 80.0,
            percentUsed = 0.80f,
            periodStart = dateToMillis("2026-04-01")
        ).copy(isPartial = true)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(status))
        // Delivery must be pinned explicitly: MockK's relaxed default for an enum
        // return type is not DeliveryResult.DELIVERED, and the monitor only records
        // the notification timestamp after a real delivered result (BUD-3).
        every { notificationService.sendBudgetAlert(any(), any(), any()) } returns
            NotificationService.DeliveryResult.DELIVERED

        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { budgetRepository.updateWarningNotification(77L, now) }
        verify(exactly = 1) {
            notificationService.sendBudgetAlert(
                77,
                "Budget Warning",
                "You've spent €80.00 (80%) of your Groceries budget (€100.00)." +
                    " Some transactions were excluded (missing exchange rates)."
            )
        }
    }

    @Test
    fun `monitor records partialData flag in status computed diagnostic`() = runTest(testDispatcher) {
        // RP-09 (P6-005): STATUS_COMPUTED must carry the partialData diagnostic field.
        val now = atDateTime(2026, 4, 13, 9, 0)
        every { timeProvider.now() } returns now
        val status = budgetStatus(
            budget = budget(id = 88L, period = BudgetPeriod.MONTHLY),
            spentAmount = 80.0,
            percentUsed = 0.80f,
            periodStart = dateToMillis("2026-04-01")
        ).copy(isPartial = true, percentKnown = true)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(status))

        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        val computed = emittedEvents.singleOrNull { it.stage == "STATUS_COMPUTED" }
        assertTrue("Expected a STATUS_COMPUTED diagnostic event", computed != null)
        val parsed = org.json.JSONObject(computed!!.metadata.toJson())
        assertTrue("STATUS_COMPUTED must include partialData flag", parsed.has("partialData"))
        assertTrue("partialData must be true for a partial status", parsed.getBoolean("partialData"))
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
            effectiveLimit = budget.amount
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
