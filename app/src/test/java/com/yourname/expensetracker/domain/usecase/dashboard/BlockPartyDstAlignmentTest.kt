package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.NormalizedForecastInput
import com.yourname.expensetracker.domain.forecasting.StressForecastResult
import com.yourname.expensetracker.domain.forecasting.StressHorizon
import com.yourname.expensetracker.domain.forecasting.StressRiskLevel
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.health.FinancialHealthScoreV2
import com.yourname.expensetracker.domain.health.HealthScoreOutcome
import com.yourname.expensetracker.domain.health.HealthTrend
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.model.dashboard.DomainBlockStatus
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.util.GlobalTimeZoneTestLock
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * P5-011 (RP-07): block-party day keys must be DST-safe.
 *
 * Plan contract (RP-07-runway-trend-polish §P5-011): "A spring-forward and
 * fall-back fixture must prove that each calendar day reads its own value and
 * that no neighboring day is duplicated or skipped."
 *
 * Both fixtures run the real compute path (real SynthesisEngine + real
 * SpendingPaceCalculator, same harness as [FinancialRunwayNoBurnTest]) with
 * the JVM default zone pinned to America/New_York under
 * [GlobalTimeZoneTestLock], fixed timestamps, and purchases placed on the DST
 * day and both neighbors:
 *
 * - Spring forward (2026-03-08, 23-hour day): purchases at 01:30 EST and
 *   03:30 EDT — the two real sides of the skipped 02:00 hour — must BOTH land
 *   on calendar day 8, with day 7 and day 9 reading only their own values.
 * - Fall back (2026-11-01, 25-hour day): purchases at 01:30 EDT and 01:30
 *   EST — the same wall-clock hour occurring twice — must BOTH land on
 *   calendar day 1, with day 2 reading only its own value.
 *
 * Every day's expected value is asserted individually (0 where nothing was
 * spent) plus the total across all days, so a day's value that is shifted to
 * a neighbor, duplicated, or dropped cannot pass.
 *
 * Note on isolation: in the current architecture the engine resolves
 * `actualSpent` from the raw per-calendar-day expense grouping first
 * (FCST-3) and falls back to the normalized dailyHistory list built by
 * `computeBlockParty` only for days with NO raw entries. The first two
 * fixtures prove the end-to-end user-visible DST contract; the third
 * fixture pins the fallback list ITSELF: it leaves the day after the
 * fall-back transition with no expenses at all, so that day's value can
 * only come from `computeBlockParty`'s
 * `getStartOfDay(addDays(monthStart, dayIndex))` keying. Under the retired
 * fixed-millis keying (`monthStart + dayIndex * DAY_IN_MILLIS`, used raw
 * and unsnapped) the generated keys drift an hour off the getStartOfDay
 * keys after the 25-hour fall-back day, so lookups MISS and read silent
 * zeros instead of a neighbor's value: the empty day reads 0.0 under both
 * keyings and this fixture passes either way (it pins the fallback list's
 * own values, not the retired keying's failure mode). The production fix
 * is still the calendar-safe addDays keying, which cannot drift.
 */
class BlockPartyDstAlignmentTest {

    private var fixedNowMs: Long = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = fixedNowMs
    }
    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase
    private lateinit var forecastInputAssembler: ForecastInputAssembler

    @Before
    fun setup() {
        val insightsEngine = mockk<com.yourname.expensetracker.domain.analytics.InsightsEngine>(relaxed = true)
        coEvery { insightsEngine.getSpendingPaceSuspend(any()) } returns SpendingPace(
            currentMonthSpent = 0.0,
            daysElapsed = 1,
            daysInMonth = 31,
            projectedTotal = 0.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 100f,
            paceStatus = PaceStatus.NO_BASELINE,
            displayCurrency = "EUR"
        )
        val monteCarloSimulator = mockk<MonteCarloSpendingSimulator>(relaxed = true)
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } returns null
        val healthCalculator = mockk<com.yourname.expensetracker.domain.health.FinancialHealthCalculator>(relaxed = true)
        val healthScoreV2 = mockk<FinancialHealthScoreV2>(relaxed = true)
        coEvery { healthScoreV2.calculateHealthScore(any(), any()) } returns HealthScoreOutcome.Available(
            FinancialHealthResult(
                overallScore = 50,
            savingsRateScore = 50,
            runwayScore = 50,
            budgetAdherenceScore = 50,
            billReliabilityScore = 50,
            factorContributions = emptyList(),
            trend = HealthTrend.STABLE,
            recommendation = null
        )
        val lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true)
        coEvery { lifestyleSavingsPromptUseCase.evaluateAndPrompt() } returns null
        val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendation() } returns null
        val computeMoneyRadarUseCase = mockk<ComputeMoneyRadarUseCase>(relaxed = true)
        coEvery { computeMoneyRadarUseCase.compute() } returns MoneyRadarData(
            urgencyScore = 0,
            urgencyLevel = UrgencyLevel.GREEN,
            dueBills = emptyList(),
            anomalyAlerts = emptyList(),
            budgetRisk = null,
            topReasons = emptyList(),
            primaryCta = null
        )
        val stressForecastEngine = mockk<FinancialStressForecastEngine>(relaxed = true)
        coEvery { stressForecastEngine.computeStressForecast() } returns StressForecastResult(
            horizons = listOf(
                StressHorizon(30, 0.0, 0.0, 0.0, StressRiskLevel.LOW, 0.0, 0.0, 0.0)
            ),
            overallRiskLevel = StressRiskLevel.LOW,
            earliestCrunchDate = null,
            recommendations = emptyList()
        )
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) } returns
            MoneyAggregate.empty(CurrencyCode("EUR"))

        forecastInputAssembler = mockk(relaxed = true)
        coEvery { forecastInputAssembler.assembleNormalized(any()) } answers {
            val input = arg<NormalizedForecastInput>(0)
            ForecastInputAssembler.ForecastInput(
                pastSumDaily = input.pastSumDaily,
                recurringPatterns = input.recurringPatterns,
                plannedExpenses = input.plannedExpenses,
                savingsGoals = input.savingsGoals,
                budgetStatuses = input.budgetStatuses,
                spendingPace = input.spendingPace,
                displayCurrency = input.homeCurrency.code,
                dataQuality = input.dataQuality
            )
        }

        computeUseCase = ComputeDashboardWidgetsUseCase(
            writeBarrier = mockk(relaxed = true),
            insightsEngine = insightsEngine,
            synthesisEngine = SynthesisEngine(timeProvider, currencyConverter = mockk<CurrencyConverter>(relaxed = true)),
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            healthCalculator = healthCalculator,
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase,
            computeMoneyRadarUseCase = computeMoneyRadarUseCase,
            stressForecastEngine = stressForecastEngine,
            forecastInputAssembler = forecastInputAssembler,
            currencyConverter = mockk<CurrencyConverter>(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            spendingPaceCalculator = com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator(timeProvider)
        )
    }

    @Test
    fun `spring forward dst day reads its own spending and no neighbor day is duplicated or skipped`() = runTest {
        GlobalTimeZoneTestLock.withLockSuspend {
            val originalTz = TimeZone.getDefault()
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
                val zone = ZoneId.of("America/New_York")

                // ── Fixture validity: the host zone must actually spring forward ──
                // US DST spring forward: Sunday 2026-03-08 at 02:00 EST -> 03:00 EDT.
                val beforeTransition = zonedMs(2026, 3, 8, 1, 30, zone) // EST (UTC-5)
                val afterTransition = zonedMs(2026, 3, 8, 3, 30, zone)  // EDT (UTC-4)
                assertEquals(
                    "fixture requires the 23-hour spring-forward day",
                    3_600_000L, afterTransition - beforeTransition
                )
                val mar7Midnight = TimePeriodUtils.getStartOfDay(zonedMs(2026, 3, 7, 12, 0, zone))
                val mar8Midnight = TimePeriodUtils.getStartOfDay(zonedMs(2026, 3, 8, 12, 0, zone))
                val mar9Midnight = TimePeriodUtils.getStartOfDay(zonedMs(2026, 3, 9, 12, 0, zone))
                assertEquals(86_400_000L, mar8Midnight - mar7Midnight)
                assertEquals(
                    "Mar 8 -> Mar 9 must be a 23-hour day under the pinned zone",
                    23 * 3_600_000L, mar9Midnight - mar8Midnight
                )

                // ── Arrangement: "now" mid-month so the DST day and neighbors are past days.
                fixedNowMs = zonedMs(2026, 3, 20, 12, 0, zone)

                val expenses = listOf(
                    purchase(5.0, zonedMs(2026, 3, 1, 12, 0, zone)),    // first day of month
                    purchase(10.0, zonedMs(2026, 3, 7, 12, 0, zone)),   // day before DST
                    purchase(20.0, beforeTransition),                   // DST day, 01:30 EST
                    purchase(22.5, afterTransition),                    // DST day, 03:30 EDT
                    purchase(7.25, zonedMs(2026, 3, 9, 12, 0, zone)),   // day after DST
                    purchase(3.75, zonedMs(2026, 3, 10, 12, 0, zone))   // two days after DST
                )
                val expectedByDay = buildMap {
                    (1..31).forEach { put(it, 0.0) }
                    put(1, 5.0)
                    put(7, 10.0)
                    put(8, 42.5)   // 20.0 + 22.5, both sides of the skipped hour
                    put(9, 7.25)
                    put(10, 3.75)
                }

                val result = computeUseCase.compute(
                    createProcessedData(expenses = expenses, budgetStatuses = listOf(overallBudget(5000.0)))
                )
                val blocks = result.allWidgets
                    .filterIsInstance<DashboardWidget.BudgetBlockParty>()
                    .single()
                    .days

                // ── Assert: the calendar itself is complete, no day skipped in the sequence.
                assertEquals(31, blocks.size)
                assertEquals((1..31).toList(), blocks.map { it.dayOfMonth })

                // ── Assert: each calendar day reads its OWN value (neighbors untouched).
                blocks.forEach { block ->
                    assertEquals(
                        "day ${block.dayOfMonth} must read its own spending",
                        expectedByDay.getValue(block.dayOfMonth), block.actualSpent, 1e-6
                    )
                }

                // ── Assert: no day's value lost or duplicated anywhere in the month.
                assertEquals(68.5, blocks.sumOf { it.actualSpent }, 1e-6)
                assertEquals(1, blocks.count { it.actualSpent == 42.5 })
                assertEquals(1, blocks.count { it.actualSpent == 10.0 })
                assertEquals(1, blocks.count { it.actualSpent == 7.25 })

                // ── Assert: the DST day's block is anchored to the DST calendar day itself.
                val dstBlock = blocks.single { it.dayOfMonth == 8 }
                assertEquals(zonedMs(2026, 3, 8, 12, 0, zone), dstBlock.date)
                assertEquals(DomainBlockStatus.OVER_BUDGET, dstBlock.status)
                assertEquals(false, dstBlock.isToday)
                assertEquals(20, blocks.single { it.isToday }.dayOfMonth)
            } finally {
                TimeZone.setDefault(originalTz)
            }
        }
    }

    @Test
    fun `fall back dst day reads its own spending and no neighbor day is duplicated or skipped`() = runTest {
        GlobalTimeZoneTestLock.withLockSuspend {
            val originalTz = TimeZone.getDefault()
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
                val zone = ZoneId.of("America/New_York")

                // ── Fixture validity: the host zone must actually fall back ──
                // US DST fall back: Sunday 2026-11-01 at 02:00 EDT -> 01:00 EST.
                val nov1Midnight = TimePeriodUtils.getStartOfDay(zonedMs(2026, 11, 1, 12, 0, zone))
                val nov2Midnight = TimePeriodUtils.getStartOfDay(zonedMs(2026, 11, 2, 12, 0, zone))
                assertEquals(
                    "Nov 1 -> Nov 2 must be a 25-hour day under the pinned zone",
                    25 * 3_600_000L, nov2Midnight - nov1Midnight
                )
                // 01:30 occurs twice on Nov 1: once EDT (before 02:00) and once
                // EST (after the rewind). The second occurrence is built from the
                // midnight instant (+2.5h real time = 01:30 EST) because a bare
                // LocalDateTime 01:30 resolves to the earlier (EDT) occurrence.
                val firstOneThirty = zonedMs(2026, 11, 1, 1, 30, zone)      // EDT (UTC-4)
                val secondOneThirty = nov1Midnight + 9_000_000L             // 01:30 EST (UTC-5)
                assertEquals(
                    "the two 01:30 occurrences must be one real hour apart",
                    3_600_000L, secondOneThirty - firstOneThirty
                )
                assertEquals(
                    "the rewound 01:30 EST occurrence must floor to Nov 1",
                    nov1Midnight, TimePeriodUtils.getStartOfDay(secondOneThirty)
                )

                // ── Arrangement: "now" mid-month so the DST day and neighbors are past days.
                fixedNowMs = zonedMs(2026, 11, 20, 12, 0, zone)

                val expenses = listOf(
                    purchase(20.0, firstOneThirty),                        // DST day, 01:30 EDT
                    purchase(22.5, secondOneThirty),                       // DST day, 01:30 EST (rewound hour)
                    purchase(7.25, zonedMs(2026, 11, 2, 12, 0, zone)),     // day after DST
                    purchase(3.75, zonedMs(2026, 11, 3, 12, 0, zone)),     // two days after DST
                    purchase(9.5, zonedMs(2026, 11, 9, 12, 0, zone)),      // later in the shifted region
                    purchase(11.0, zonedMs(2026, 11, 30, 12, 0, zone))     // last day of the month
                )
                val expectedByDay = buildMap {
                    (1..30).forEach { put(it, 0.0) }
                    put(1, 42.5)   // 20.0 + 22.5, both occurrences of 01:30
                    put(2, 7.25)
                    put(3, 3.75)
                    put(9, 9.5)
                    put(30, 11.0)
                }

                val result = computeUseCase.compute(
                    createProcessedData(expenses = expenses, budgetStatuses = listOf(overallBudget(5000.0)))
                )
                val blocks = result.allWidgets
                    .filterIsInstance<DashboardWidget.BudgetBlockParty>()
                    .single()
                    .days

                // ── Assert: the calendar itself is complete, no day skipped in the sequence.
                assertEquals(30, blocks.size)
                assertEquals((1..30).toList(), blocks.map { it.dayOfMonth })

                // ── Assert: each calendar day reads its OWN value (neighbors untouched).
                blocks.forEach { block ->
                    assertEquals(
                        "day ${block.dayOfMonth} must read its own spending",
                        expectedByDay.getValue(block.dayOfMonth), block.actualSpent, 1e-6
                    )
                }

                // ── Assert: no day's value lost or duplicated anywhere in the month.
                assertEquals(74.0, blocks.sumOf { it.actualSpent }, 1e-6)
                assertEquals(1, blocks.count { it.actualSpent == 42.5 })
                assertEquals(1, blocks.count { it.actualSpent == 7.25 })
                assertEquals(1, blocks.count { it.actualSpent == 9.5 })
                assertEquals(1, blocks.count { it.actualSpent == 11.0 })

                // ── Assert: the DST day's block is anchored to the DST calendar day itself.
                val dstBlock = blocks.single { it.dayOfMonth == 1 }
                assertEquals(zonedMs(2026, 11, 1, 12, 0, zone), dstBlock.date)
                assertEquals(DomainBlockStatus.OVER_BUDGET, dstBlock.status)
                assertEquals(false, dstBlock.isToday)
                assertEquals(20, blocks.single { it.isToday }.dayOfMonth)
            } finally {
                TimeZone.setDefault(originalTz)
            }
        }
    }

    @Test
    fun `fall back empty day after transition reads zero not the spilled transition-day value`() = runTest {
        GlobalTimeZoneTestLock.withLockSuspend {
            val originalTz = TimeZone.getDefault()
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
                val zone = ZoneId.of("America/New_York")

                // ── Arrangement: day 1 spends (both 01:30 occurrences), day 2
                // has NO expenses at all, day 3 spends again. Day 2's
                // actualSpent can only come from the normalized dailyHistory
                // fallback (no raw entries → FCST-3 falls through), so this
                // fixture pins computeBlockParty's day-key arithmetic itself.
                fixedNowMs = zonedMs(2026, 11, 20, 12, 0, zone)
                val nov1Midnight = TimePeriodUtils.getStartOfDay(zonedMs(2026, 11, 1, 12, 0, zone))

                val expenses = listOf(
                    purchase(20.0, zonedMs(2026, 11, 1, 1, 30, zone)), // day 1, 01:30 EDT
                    purchase(22.5, nov1Midnight + 9_000_000L),         // day 1, 01:30 EST (rewound hour)
                    purchase(7.25, zonedMs(2026, 11, 3, 12, 0, zone))  // day 3
                )

                val result = computeUseCase.compute(
                    createProcessedData(expenses = expenses, budgetStatuses = listOf(overallBudget(5000.0)))
                )
                val blocks = result.allWidgets
                    .filterIsInstance<DashboardWidget.BudgetBlockParty>()
                    .single()
                    .days

                // ── Assert: the empty day after the transition reads ZERO.
                // Retired fixed-millis keying: monthStart + 1*24h lands at
                // Nov 1 23:00 EST, floors to the Nov 1 key, and spills day 1's
                // 42.5 onto day 2 (and reads 0.0 for day 3). Calendar-safe
                // addDays keying keeps every day on its own value.
                val byDay = blocks.associateBy { it.dayOfMonth }
                assertEquals(42.5, byDay.getValue(1).actualSpent, 1e-6)
                assertEquals(
                    "the empty day after fall-back must not read the transition day's spend",
                    0.0, byDay.getValue(2).actualSpent, 1e-6
                )
                assertEquals(
                    "day 3 must read its own value, not a shifted one",
                    7.25, byDay.getValue(3).actualSpent, 1e-6
                )
                assertEquals(DomainBlockStatus.UNDER_BUDGET, byDay.getValue(2).status)
                assertEquals(49.75, blocks.sumOf { it.actualSpent }, 1e-6)
            } finally {
                TimeZone.setDefault(originalTz)
            }
        }
    }

    // ── Harness helpers (mirrors FinancialRunwayNoBurnTest) ──────────────────

    private fun overallBudget(amount: Double): BudgetStatusSnapshot = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = amount,
        categoryName = null,
        spentAmount = 0.0,
        remainingAmount = amount,
        percentUsed = 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = fixedNowMs,
        periodEnd = fixedNowMs + 86_400_000L
    )

    private fun purchase(amount: Double, date: Long): DashboardExpense = DashboardExpense(
        id = date,
        amount = amount,
        effectiveAmount = amount,
        merchant = "Test",
        transactionType = DashboardTransactionType.PURCHASE,
        date = date,
        categoryId = null,
        isNotMine = false,
        isManualEntry = false
    )

    private fun createProcessedData(
        expenses: List<DashboardExpense>,
        budgetStatuses: List<BudgetStatusSnapshot> = emptyList()
    ): ProcessedDashboardData {
        val data = DashboardData(
            expenses = expenses,
            categories = emptyList(),
            budgetStatuses = budgetStatuses,
            pendingCount = 0,
            weather = FinancialWeather(
                state = WeatherState.UNKNOWN,
                headline = UiText.DynamicString(""),
                summary = UiText.DynamicString(""),
                icon = "",
                riskLevel = 0,
                totalCommitted = 0.0,
                totalLikely = 0.0,
                predictedDiscretionary = 0.0,
                discretionaryBudget = 0.0
            ),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            goals = emptyList()
        )
        val summary = SpendingSummary(
            totalSpent = 0.0,
            previousTotalSpent = null,
            changePercent = null,
            dailyHistory = emptyList(),
            previousDailyHistory = emptyList(),
            transactionCount = expenses.size
        )
        return ProcessedDashboardData(data = data, summary = summary, categoryBreakdown = emptyList())
    }

    /** Fixed-timestamp helper: wall-clock fields resolved in [zone], like the existing DST tests. */
    private fun zonedMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId): Long {
        return LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }
}
