package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * BudgetMonitor behaviour. B6-4: also holds the former BudgetMonitorStressTest scenarios.
 *
 * Tables use a FRESH [Fixture] (own mocks and monitor) per row so `verify(exactly = n)`
 * counts never leak between rows; every row is named in its failure message and each table
 * has a row-count guard. Only [AssertionError] is collected per row; no exception (in particular
 * no CancellationException) is swallowed.
 *
 * The JVM default zone is pinned to UTC for the class ([PinnedUtcTimeZoneRule]) and all
 * fixture instants are built in an explicit UTC zone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetMonitorTest {

    companion object {
        // Zone pinned to UTC for the whole class as defence in depth: BudgetMonitor and the
        // Budget fixtures read the system-default zone, while every fixture instant here is
        // built through at(...) in an explicit UTC zone.
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()

        private val UTC: ZoneId = ZoneId.of("UTC")
        private const val HOUR_MS = 60L * 60L * 1000L
        private const val DAY_MS = 24L * HOUR_MS

        /** MONTHLY cooldown in BudgetMonitor: 48 hours. */
        private const val MONTHLY_COOLDOWN_MS = 48L * HOUR_MS

        /** BudgetMonitor throttle: a check within 60 seconds of the previous one is skipped. */
        private const val MIN_CHECK_INTERVAL_MS = 60_000L
    }

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

    @After
    fun tearDown() {
        monitor.destroy()
    }

    // ============================================================================
    // Fixtures and table plumbing
    // ============================================================================

    /** An independent repository/clock/notifier/monitor set so per-row verify counts are exact. */
    private inner class Fixture {
        val repo = mockk<BudgetRepository>(relaxed = true)
        val time = mockk<TimeProvider>(relaxed = true)
        val notifications = mockk<NotificationService>(relaxed = true)
        val writer = mockk<DiagnosticEventWriter>(relaxed = true)
        val events = mutableListOf<DiagnosticEvent>()
        val monitor: BudgetMonitor

        init {
            coEvery { writer.emit(capture(events)) } returns Unit
            every { notifications.sendBudgetAlert(any(), any(), any()) } returns
                NotificationService.DeliveryResult.DELIVERED
            monitor = BudgetMonitor(
                budgetRepository = repo,
                timeProvider = time,
                notificationService = notifications,
                ioDispatcher = testDispatcher,
                diagnosticEventWriter = writer,
                writeBarrier = mockk(relaxed = true),
                diagnosticSink = mockk(relaxed = true)
            )
        }

        fun settle() = testDispatcher.scheduler.advanceUntilIdle()
    }

    /**
     * Runs [check] for every row against a fresh [Fixture] and reports ALL failing rows (named)
     * in a single failure, so one bad row never masks the others.
     */
    private fun <R> forEachRow(
        table: String,
        expectedRowCount: Int,
        rows: List<R>,
        nameOf: (R) -> String,
        check: (R, Fixture) -> Unit
    ) {
        assertEquals("$table: row count guard", expectedRowCount, rows.size)
        assertEquals("$table: row names must be unique", rows.size, rows.map(nameOf).toSet().size)
        val failures = mutableListOf<String>()
        for (row in rows) {
            val fixture = Fixture()
            try {
                check(row, fixture)
            } catch (e: AssertionError) {
                failures += "[$table/${nameOf(row)}] ${e.message}"
            } finally {
                fixture.monitor.destroy()
            }
        }
        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n"))
        }
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        LocalDate.of(year, month, day).atTime(hour, minute).atZone(UTC).toInstant().toEpochMilli()

    private fun atDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        at(year, month, day, hour, minute)

    // ============================================================================
    // Threshold alerts (warning / critical / exceeded) with exact content
    // ============================================================================

    private enum class AlertKind { WARNING, CRITICAL, EXCEEDED }

    private data class ThresholdRow(
        val name: String,
        val id: Long,
        val period: BudgetPeriod,
        val amount: Double,
        val spent: Double,
        val percentUsed: Float,
        val now: Long,
        val kind: AlertKind,
        val title: String,
        val content: String
    )

    @Test
    fun `check budgets sends the threshold alert and updates only its own timestamp table`() = runTest(testDispatcher) {
        val rows = listOf(
            ThresholdRow("warning at 80 percent", 11L, BudgetPeriod.MONTHLY, 100.0, 80.0, 0.80f,
                atDateTime(2026, 4, 5, 12, 0), AlertKind.WARNING, "Budget Warning",
                "You've spent €80.00 (80%) of your Groceries budget (€100.00)."),
            ThresholdRow("critical at 95 percent", 22L, BudgetPeriod.MONTHLY, 100.0, 95.0, 0.95f,
                atDateTime(2026, 4, 6, 9, 0), AlertKind.CRITICAL, "Critical Budget Warning",
                "You've spent €95.00 (95%) of your Groceries budget (€100.00)."),
            ThresholdRow("exceeded at 130 percent weekly", 33L, BudgetPeriod.WEEKLY, 100.0, 130.0, 1.30f,
                atDateTime(2026, 4, 7, 8, 0), AlertKind.EXCEEDED, "Budget Exceeded!",
                "You've spent €130.00 (130%) of your Groceries budget (€100.00)."),
            // Stress: the 100% boundary is inclusive for EXCEEDED (>= 1.0).
            ThresholdRow("exceeded at exactly 100 percent", 1L, BudgetPeriod.MONTHLY, 100.0, 100.0, 1.0f,
                atDateTime(2026, 4, 8, 8, 0), AlertKind.EXCEEDED, "Budget Exceeded!",
                "You've spent €100.00 (100%) of your Groceries budget (€100.00)."),
            // Stress: 95% of a 200 budget is critical.
            ThresholdRow("critical at 95 percent of a 200 budget", 2L, BudgetPeriod.MONTHLY, 200.0, 190.0, 0.95f,
                atDateTime(2026, 4, 9, 8, 0), AlertKind.CRITICAL, "Critical Budget Warning",
                "You've spent €190.00 (95%) of your Groceries budget (€200.00).")
        )

        forEachRow("threshold", 5, rows, { it.name }) { row, f ->
            every { f.time.now() } returns row.now
            every { f.repo.getBudgetStatuses() } returns flowOf(
                listOf(
                    budgetStatus(
                        budget = budget(id = row.id, period = row.period, amount = row.amount),
                        spentAmount = row.spent,
                        percentUsed = row.percentUsed,
                        periodStart = at(2026, 4, 1)
                    )
                )
            )

            f.monitor.checkBudgets()
            f.settle()

            when (row.kind) {
                AlertKind.WARNING -> {
                    coVerify(exactly = 1) { f.repo.updateWarningNotification(row.id, row.now) }
                    coVerify(exactly = 0) { f.repo.updateCriticalNotification(any(), any()) }
                    coVerify(exactly = 0) { f.repo.updateExceededNotification(any(), any()) }
                }
                AlertKind.CRITICAL -> {
                    coVerify(exactly = 1) { f.repo.updateCriticalNotification(row.id, row.now) }
                    coVerify(exactly = 0) { f.repo.updateWarningNotification(any(), any()) }
                    coVerify(exactly = 0) { f.repo.updateExceededNotification(any(), any()) }
                }
                AlertKind.EXCEEDED -> {
                    coVerify(exactly = 1) { f.repo.updateExceededNotification(row.id, row.now) }
                    coVerify(exactly = 0) { f.repo.updateWarningNotification(any(), any()) }
                    coVerify(exactly = 0) { f.repo.updateCriticalNotification(any(), any()) }
                }
            }
            verify(exactly = 1) { f.notifications.sendBudgetAlert(row.id.toInt(), row.title, row.content) }
            verify(exactly = 1) { f.notifications.sendBudgetAlert(any(), any(), any()) }
        }
    }

    // ============================================================================
    // No-op statuses: zero spend / zero limit emit a SKIPPED record and no alert
    // ============================================================================

    private data class SkipRow(
        val name: String,
        val id: Long,
        val amount: Double,
        val spent: Double,
        val percentUsed: Float,
        val effectiveLimitOverride: Double? = null
    )

    @Test
    fun `zero spend or zero limit emits a skip event and never notifies table`() = runTest(testDispatcher) {
        val rows = listOf(
            // P6-CURRENT-027(a): the no-op early return must leave a durable SKIPPED record.
            SkipRow("spent zero with zero percent", 44L, amount = 100.0, spent = 0.0, percentUsed = 0.0f),
            // Stress: an inconsistent 60% percent with zero spend and a zero effective limit still no-ops.
            SkipRow("spent zero despite 60 percent and zero effective limit", 3L,
                amount = 100.0, spent = 0.0, percentUsed = 0.6f, effectiveLimitOverride = 0.0),
            // Stress: budget amount zero (spend derived as 0) no-ops.
            SkipRow("budget amount zero", 4L, amount = 0.0, spent = 0.0, percentUsed = 1.0f),
            // The other half of the OR gate - positive spend against a zero limit.
            SkipRow("zero limit with positive spend", 5L, amount = 0.0, spent = 50.0, percentUsed = 1.0f)
        )

        forEachRow("skip", 4, rows, { it.name }) { row, f ->
            val now = atDateTime(2026, 4, 9, 11, 0)
            every { f.time.now() } returns now
            val status = budgetStatus(
                budget = budget(id = row.id, period = BudgetPeriod.MONTHLY, amount = row.amount),
                spentAmount = row.spent,
                percentUsed = row.percentUsed,
                periodStart = at(2026, 4, 1)
            ).let { s -> row.effectiveLimitOverride?.let { s.copy(effectiveLimit = it) } ?: s }
            every { f.repo.getBudgetStatuses() } returns flowOf(listOf(status))

            f.monitor.checkBudgets()
            f.settle()

            val skipped = f.events.filter { it.stage == "STATUS_SKIPPED" }
            assertEquals("exactly one STATUS_SKIPPED diagnostic event", 1, skipped.size)
            val skip = skipped.single()
            assertTrue("Skip event must use SKIPPED outcome", skip.outcome == EventOutcome.SKIPPED)
            assertTrue("Skip event must target the budget", skip.entityId == row.id)
            assertTrue(
                "Skip event must record the no-op reason",
                skip.metadata.toJson().contains("NO_SPEND_OR_LIMIT")
            )
            assertEquals("no STATUS_COMPUTED for a no-op status", 0, f.events.count { it.stage == "STATUS_COMPUTED" })
            // No alert and no timestamp update should ever happen for a no-op status.
            verify(exactly = 0) { f.notifications.sendBudgetAlert(any(), any(), any()) }
            coVerify(exactly = 0) { f.repo.updateWarningNotification(any(), any()) }
            coVerify(exactly = 0) { f.repo.updateCriticalNotification(any(), any()) }
            coVerify(exactly = 0) { f.repo.updateExceededNotification(any(), any()) }
        }
    }

    // ============================================================================
    // Throttle: per-period cooldown and period-start reset (strict boundaries)
    // ============================================================================

    private data class CooldownRow(
        val name: String,
        val periodStart: Long,
        val lastWarningNotifiedAt: Long?,
        val expectNotify: Boolean
    )

    @Test
    fun `warning notification honors period start reset and the strict monthly cooldown table`() = runTest(testDispatcher) {
        val now = atDateTime(2026, 4, 20, 12, 0)
        val earlyPeriodStart = at(2026, 4, 1)
        val rows = listOf(
            CooldownRow("never notified", earlyPeriodStart, null, expectNotify = true),
            // Stress: lastNotified one day before periodStart notifies.
            CooldownRow("last notified before period start",
                now - 2 * DAY_MS, now - 3 * DAY_MS, expectNotify = true),
            // Sharper variant - still INSIDE the 48h cooldown, but the period has since reset.
            CooldownRow("last notified before period start but inside cooldown",
                now - HOUR_MS, now - 2 * HOUR_MS, expectNotify = true),
            CooldownRow("notified one hour ago in the current period", earlyPeriodStart, now - HOUR_MS,
                expectNotify = false),
            CooldownRow("last notified exactly at the cooldown length", earlyPeriodStart,
                now - MONTHLY_COOLDOWN_MS, expectNotify = false),
            CooldownRow("last notified one millisecond past the cooldown", earlyPeriodStart,
                now - MONTHLY_COOLDOWN_MS - 1L, expectNotify = true),
            CooldownRow("last notified exactly at period start is not before it", now - HOUR_MS,
                now - HOUR_MS, expectNotify = false)
        )

        forEachRow("cooldown", 7, rows, { it.name }) { row, f ->
            val id = 7L
            every { f.time.now() } returns now
            val withHistory = budget(id = id, period = BudgetPeriod.MONTHLY)
                .copy(lastWarningNotifiedAt = row.lastWarningNotifiedAt)
            every { f.repo.getBudgetStatuses() } returns flowOf(
                listOf(budgetStatus(withHistory, spentAmount = 80.0, percentUsed = 0.80f, periodStart = row.periodStart))
            )

            f.monitor.checkBudgets()
            f.settle()

            if (row.expectNotify) {
                coVerify(exactly = 1) { f.repo.updateWarningNotification(id, now) }
                verify(exactly = 1) { f.notifications.sendBudgetAlert(any(), any(), any()) }
                assertEquals("WARNING decision event", 1, f.events.count { it.stage == "WARNING" })
                assertEquals("no throttled event", 0, f.events.count { it.stage == "WARNING_THROTTLED" })
            } else {
                coVerify(exactly = 0) { f.repo.updateWarningNotification(any(), any()) }
                verify(exactly = 0) { f.notifications.sendBudgetAlert(any(), any(), any()) }
                assertEquals("WARNING_THROTTLED decision event", 1, f.events.count { it.stage == "WARNING_THROTTLED" })
                assertEquals("no sent event", 0, f.events.count { it.stage == "WARNING" })
            }
        }
    }

    // ============================================================================
    // MIN_CHECK_INTERVAL
    // ============================================================================

    private data class IntervalRow(val name: String, val secondCheckOffsetMs: Long, val expectSecondRead: Boolean)

    @Test
    fun `checkBudgets is skipped until MIN_CHECK_INTERVAL has elapsed table`() = runTest(testDispatcher) {
        val rows = listOf(
            IntervalRow("30 seconds later is skipped", 30_000L, expectSecondRead = false),
            IntervalRow("59.999 seconds later is still skipped", MIN_CHECK_INTERVAL_MS - 1L, expectSecondRead = false),
            IntervalRow("exactly 60 seconds later runs", MIN_CHECK_INTERVAL_MS, expectSecondRead = true)
        )

        forEachRow("interval", 3, rows, { it.name }) { row, f ->
            val now = atDateTime(2026, 4, 10, 9, 0)
            every { f.time.now() } returns now
            val warningBudget = budget(id = 5L, period = BudgetPeriod.MONTHLY).copy(notifyAtWarning = 0.5f)
            every { f.repo.getBudgetStatuses() } returns flowOf(
                listOf(budgetStatus(warningBudget, spentAmount = 60.0, percentUsed = 0.6f, periodStart = now - DAY_MS))
            )
            f.monitor.checkBudgets()
            f.settle()
            coVerify(exactly = 1) { f.repo.getBudgetStatuses() }

            every { f.time.now() } returns now + row.secondCheckOffsetMs
            f.monitor.checkBudgets()
            f.settle()
            coVerify(exactly = if (row.expectSecondRead) 2 else 1) { f.repo.getBudgetStatuses() }

            // The repository keeps returning a status with no recorded warning notification, so
            // every check that actually runs raises (and records) exactly one more warning alert;
            // a skipped check raises none.
            val expectedAlerts = if (row.expectSecondRead) 2 else 1
            verify(exactly = expectedAlerts) { f.notifications.sendBudgetAlert(any(), any(), any()) }
            assertEquals("WARNING decision events", expectedAlerts, f.events.count { it.stage == "WARNING" })
            coVerify(exactly = 1) { f.repo.updateWarningNotification(5L, now) }
            coVerify(exactly = if (row.expectSecondRead) 1 else 0) {
                f.repo.updateWarningNotification(5L, now + row.secondCheckOffsetMs)
            }
        }
    }

    // ============================================================================
    // Lifecycle + concurrency regressions
    // ============================================================================

    @Test
    fun `concurrent checks preserve throttle coherence and read repository once`() = runTest(testDispatcher) {
        val now = atDateTime(2026, 4, 10, 9, 0)
        every { timeProvider.now() } returns now
        val warningBudget = budget(id = 6L, period = BudgetPeriod.MONTHLY).copy(notifyAtWarning = 0.5f)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(warningBudget, spentAmount = 60.0, percentUsed = 0.6f, periodStart = 0L))
        )

        repeat(10) {
            launch { monitor.checkBudgets() }
        }

        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { budgetRepository.getBudgetStatuses() }
        coVerify(exactly = 1) { budgetRepository.updateWarningNotification(6L, now) }
        verify(exactly = 1) {
            notificationService.sendBudgetAlert(
                6,
                "Budget Warning",
                "You've spent €60.00 (60%) of your Groceries budget (€100.00)."
            )
        }
        verify(exactly = 1) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    private data class BackgroundRow(
        val name: String,
        val firstFlow: () -> Flow<List<BudgetStatus>>,
        val cancelInFlight: Boolean
    )

    @Test
    fun `onBackground clears transient state and next foreground check fetches fresh state table`() = runTest(testDispatcher) {
        val rows = listOf(
            // Idle: the first check already completed before the app is backgrounded.
            BackgroundRow("idle monitor", { flowOf(emptyList()) }, cancelInFlight = false),
            // In flight: the first fetch is suspended in delay() when the app is backgrounded.
            BackgroundRow("in-flight work is cancelled", {
                flow {
                    delay(1_000L)
                    emit(
                        listOf(
                            budgetStatus(
                                budget(id = 61L, period = BudgetPeriod.MONTHLY).copy(notifyAtWarning = 0.5f),
                                spentAmount = 60.0, percentUsed = 0.6f, periodStart = 0L
                            )
                        )
                    )
                }
            }, cancelInFlight = true)
        )

        forEachRow("background", 2, rows, { it.name }) { row, f ->
            val firstNow = atDateTime(2026, 4, 8, 10, 0)
            val secondNow = firstNow + 5_000L
            every { f.time.now() } returnsMany listOf(firstNow, secondNow)
            every { f.repo.getBudgetStatuses() } returnsMany listOf(row.firstFlow(), flowOf(emptyList()))

            f.monitor.checkBudgets()
            if (row.cancelInFlight) {
                testDispatcher.scheduler.runCurrent()
            } else {
                f.settle()
            }

            f.monitor.onBackground()
            f.monitor.checkBudgets()
            f.settle()

            // The second check ran only 5s after the first: it is allowed because onBackground reset the throttle.
            coVerify(exactly = 2) { f.repo.getBudgetStatuses() }
            verify(exactly = 0) { f.notifications.sendBudgetAlert(any(), any(), any()) }
            coVerify(exactly = 0) { f.repo.updateWarningNotification(any(), any()) }
            coVerify(exactly = 0) { f.repo.updateCriticalNotification(any(), any()) }
            coVerify(exactly = 0) { f.repo.updateExceededNotification(any(), any()) }
        }
    }

    @Test
    fun `destroy permanently cancels monitor scope`() = runTest(testDispatcher) {
        monitor.destroy()

        val now = atDateTime(2026, 4, 10, 9, 0)
        every { timeProvider.now() } returns now
        val warningBudget = budget(id = 62L, period = BudgetPeriod.MONTHLY).copy(notifyAtWarning = 0.5f)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(budgetStatus(warningBudget, spentAmount = 60.0, percentUsed = 0.6f, periodStart = 0L))
        )

        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { budgetRepository.getBudgetStatuses() }
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    @Test
    fun `empty budget statuses does not crash and sends nothing`() = runTest(testDispatcher) {
        every { timeProvider.now() } returns atDateTime(2026, 4, 10, 9, 0)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())

        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { budgetRepository.getBudgetStatuses() }
        verify(exactly = 0) { notificationService.sendBudgetAlert(any(), any(), any()) }
        coVerify(exactly = 0) { budgetRepository.updateCriticalNotification(any(), any()) }
    }

    @Test
    fun `multiple budgets at critical all get notified`() = runTest(testDispatcher) {
        val now = atDateTime(2026, 4, 10, 9, 0)
        every { timeProvider.now() } returns now
        // id -> spent: 95% of amount 100 * id (hard-coded).
        val spentById = mapOf(1L to 95.0, 2L to 190.0, 3L to 285.0, 4L to 380.0, 5L to 475.0)
        assertEquals("multi-critical: row count guard", 5, spentById.size)
        val statuses = spentById.map { (id, spent) ->
            budgetStatus(
                budget(id = id, period = BudgetPeriod.MONTHLY, amount = 100.0 * id),
                spentAmount = spent, percentUsed = 0.95f, periodStart = at(2026, 4, 1)
            )
        }
        every { budgetRepository.getBudgetStatuses() } returns flowOf(statuses)

        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 5) { budgetRepository.updateCriticalNotification(any(), any()) }
        for (id in spentById.keys) {
            coVerify(exactly = 1) { budgetRepository.updateCriticalNotification(id, now) }
            verify(exactly = 1) { notificationService.sendBudgetAlert(id.toInt(), "Critical Budget Warning", any()) }
        }
        verify(exactly = 5) { notificationService.sendBudgetAlert(any(), any(), any()) }
    }

    // ============================================================================
    // Diagnostics and data-quality qualifiers
    // ============================================================================

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
                    periodStart = at(2026, 4, 1)
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
        // RP-09 (P6-005): percentKnown=false (limit conversion failed) - the placeholder
        // 0f percent must never drive a threshold comparison or an alert.
        val now = atDateTime(2026, 4, 11, 9, 0)
        every { timeProvider.now() } returns now
        val status = budgetStatus(
            budget = budget(id = 66L, period = BudgetPeriod.MONTHLY),
            spentAmount = 80.0,
            percentUsed = 0.95f,
            periodStart = at(2026, 4, 1)
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
            periodStart = at(2026, 4, 1)
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
            periodStart = at(2026, 4, 1)
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

    // ============================================================================
    // Builders
    // ============================================================================

    private fun budget(id: Long, period: BudgetPeriod, amount: Double = 100.0): Budget {
        return Budget(
            id = id,
            categoryId = 2L,
            amount = amount,
            period = period,
            periodMode = "ROLLING",
            startDate = at(2026, 4, 1),
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
}
