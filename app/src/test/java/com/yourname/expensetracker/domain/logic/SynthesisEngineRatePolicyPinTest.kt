package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.StaleRatePolicy
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.model.ConfirmedOccurrence
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * RP-06 6b slice 2 (P5-CURRENT-009 / U-MONEY-01) — 政策钉 + 兜底移除测试。
 *
 * 1. 政策钉（slice 1 评审 NOTE）：跨币种转换必须以
 *    RateBasis.LATEST_AVAILABLE + StaleRatePolicy.LatestDefault（7 天）调用
 *    CurrencyConverter.convertOutcome —— 用参数捕获真实断言，非标记断言。
 * 2. 兜底移除：convertOutcome 返回 Failed 时，源币种金额不得进入任何
 *    home-currency 汇总；受影响项被排除并计数，isPartial = true。
 * 3. 单币种恒等路径：同币种 fixture 不触发 convertOutcome，输出语义不变。
 *
 * 计数口径说明（与引擎实现对齐）：
 * 同一笔 USD recurring pattern 会在「committed/likely 选段」与
 * 「monthlyRecurringTotal（synthesizeInternal 内）」两处各计入一次失败，
 * calculateBlockPartyData 内的月度/每日重算再各计一次（bp 计数仅日志，
 * 不入 excludedCount）。因此 excludedCount 的期望值按调用点累加。
 */
class SynthesisEngineRatePolicyPinTest {

    private lateinit var timeProvider: TimeProvider
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var engine: SynthesisEngine

    /** 固定 now = 2026-03-15 12:00（3 月，31 天，dayOfMonth=15）。 */
    private val now: Long by lazy {
        Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Before
    fun setUp() {
        timeProvider = mockk()
        every { timeProvider.now() } returns now
        currencyConverter = mockk()
        engine = SynthesisEngine(timeProvider, currencyConverter = currencyConverter)
    }

    // ── 公共 fixture ────────────────────────────────────────────────────

    private fun pace(displayCurrency: String = "EUR", averageMonthlyTotal: Double = 1200.0) = SpendingPace(
        currentMonthSpent = 0.0,
        daysElapsed = 15,
        daysInMonth = 31,
        projectedTotal = 0.0,
        previousMonthTotal = 1200.0,
        averageMonthlyTotal = averageMonthlyTotal,
        pacePercentage = 100.0f,
        paceStatus = PaceStatus.ON_PACE,
        displayCurrency = displayCurrency,
    )

    private fun budget(amount: Double) = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = amount,
        categoryName = null,
        spentAmount = 0.0,
        remainingAmount = amount,
        percentUsed = 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = 0L,
        periodEnd = 0L
    )

    private fun pattern(amount: Double, currency: String, day: Int, confidence: Float = 0.95f) =
        RecurringPattern(
            merchantName = "Recurring",
            averageAmount = amount,
            currency = currency,
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = dayMs(day),
            confidence = confidence,
            previousDates = emptyList()
        )

    private fun planned(amount: Double, currency: String, day: Int, priority: PlannedExpensePriority) =
        PlannedExpense(
            id = 0L,
            description = "Planned",
            amount = amount,
            currency = currency,
            date = dayMs(day),
            categoryId = null,
            isRecurring = false,
            priority = priority
        )

    /** 当月 day 日 12:00（引擎按系统默认时区取 day-of-month，取当日任意时刻即可）。 */
    private fun dayMs(day: Int): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun converted(amount: Double, from: String, to: String) = ConversionOutcome.Converted(
        originalAmount = amount,
        originalCurrency = CurrencyCode(from),
        convertedAmount = amount,
        targetCurrency = CurrencyCode(to),
        rateUsed = 1.0,
        rateBasis = RateBasis.LATEST_AVAILABLE,
        rateValidDate = null,
        rateLastUpdated = now,
        rateSource = "test",
        conversionPath = ConversionPath.DIRECT
    )

    private fun failed(amount: Double, from: String, to: String) = ConversionOutcome.Failed(
        originalAmount = amount,
        originalCurrency = from,
        targetCurrency = to,
        rateBasis = RateBasis.LATEST_AVAILABLE,
        failureType = ConversionFailureType.MISSING_RATE,
        message = "test failure"
    )

    /** USD→EUR 一律 Failed 的桩（引擎内所有跨币种调用均命中）。 */
    private fun stubAllFailed() {
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } answers { failed(firstArg(), secondArg(), thirdArg()) }
    }

    /** 跨币种一律 Converted（rate 1.0）的桩。 */
    private fun stubAllConverted() {
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } answers { converted(firstArg(), secondArg(), thirdArg()) }
    }

    // ── 1. 政策钉 ──────────────────────────────────────────────────────

    @Test
    fun `跨币种 synthesize 以 LATEST_AVAILABLE 加 LatestDefault 调用 convertOutcome`() = runTest {
        val basisSlot = slot<RateBasis>()
        val policySlot = slot<StaleRatePolicy>()
        coEvery {
            currencyConverter.convertOutcome(
                any(), any(), any(),
                capture(basisSlot), any(), capture(policySlot)
            )
        } answers { converted(firstArg(), secondArg(), thirdArg()) }

        engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(pattern(100.0, "USD", 20)),
            plannedExpenses = listOf(planned(50.0, "USD", 25, PlannedExpensePriority.MUST)),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(budget(2000.0)),
            spendingPace = pace(),
            displayCurrency = "EUR"
        )

        assertTrue("convertOutcome 必须至少被调用一次", basisSlot.isCaptured && policySlot.isCaptured)
        assertEquals(RateBasis.LATEST_AVAILABLE, basisSlot.captured)
        assertEquals(StaleRatePolicy.LatestDefault, policySlot.captured)
    }

    @Test
    fun `跨币种 calculateBlockPartyData 同样钉死 rateBasis 与 stalePolicy`() = runTest {
        stubAllConverted()

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(pattern(100.0, "USD", 20)),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(budget(2000.0)),
            spendingPace = pace(),
            displayCurrency = "EUR"
        )

        val basisSlot = slot<RateBasis>()
        val policySlot = slot<StaleRatePolicy>()
        coEvery {
            currencyConverter.convertOutcome(
                any(), any(), any(),
                capture(basisSlot), any(), capture(policySlot)
            )
        } answers { converted(firstArg(), secondArg(), thirdArg()) }

        engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = List(31) { 0f },
            budgetLimit = 2000.0
        )

        assertTrue(basisSlot.isCaptured && policySlot.isCaptured)
        assertEquals(RateBasis.LATEST_AVAILABLE, basisSlot.captured)
        assertEquals(StaleRatePolicy.LatestDefault, policySlot.captured)
    }

    // ── 2. 兜底移除（多币种 Failed → 排除 + 计数 + isPartial）──────────

    @Test
    fun `convertOutcome Failed 时源币种金额不得进入 totalCommitted 或 totalLikely`() = runTest {
        stubAllFailed()

        // USD recurring（committed 段 1 次 + monthlyRecurringTotal 1 次 = 2），
        // USD MUST planned（committed 段 1 次）与 USD LIKELY planned（likely 段 1 次）。
        // committed/likely 段的 mapNotNull 已排除，故金额不进汇总。
        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(pattern(100.0, "USD", 20)),
            plannedExpenses = listOf(
                planned(50.0, "USD", 25, PlannedExpensePriority.MUST),
                planned(40.0, "USD", 26, PlannedExpensePriority.LIKELY)
            ),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(budget(2000.0)),
            spendingPace = pace(),
            displayCurrency = "EUR"
        )

        // 全部转换失败 → 任何 home(EUR) 币汇总中不得出现源币(USD)金额
        assertEquals(0.0, forecast.components.totalCommitted, 0.0001)
        assertEquals(0.0, forecast.components.totalLikely, 0.0001)
        // 排除计数（引擎按调用点累加）：
        // recurring = committed 段 1 + monthlyRecurringTotal 段 1 = 2；
        // MUST planned = committedPlanned 段 1 + mustExpensesByDay 1 = 2；
        // LIKELY planned = likelyPlanned 段 1 + likelyExpensesByDay 1 = 2。合计 6。
        assertEquals(6, forecast.excludedCount)
        assertTrue("转换失败必须置 isPartial", forecast.isPartial)
    }

    @Test
    fun `convertOutcome Failed 时投影日图排除该项而非回退源币金额`() = runTest {
        stubAllFailed()

        // 零基线 pace：discretionary 累计为 0，投影终点只含 lastKnownTotal，
        // 使「raw fallback 未移除则会混入源币金额」的断言不受 pace 变量干扰。
        val zeroPace = SpendingPace(
            currentMonthSpent = 0.0,
            daysElapsed = 15,
            daysInMonth = 31,
            projectedTotal = 0.0,
            previousMonthTotal = 0.0,
            averageMonthlyTotal = 0.0,
            pacePercentage = 0.0f,
            paceStatus = PaceStatus.ON_PACE,
            displayCurrency = "EUR",
        )

        val forecast = engine.synthesize(
            pastSumDaily = listOf(10.0),
            recurringPatterns = emptyList(),
            plannedExpenses = listOf(
                planned(50.0, "USD", 20, PlannedExpensePriority.MUST),
                planned(40.0, "USD", 20, PlannedExpensePriority.LIKELY)
            ),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(budget(2000.0)),
            spendingPace = zeroPace,
            displayCurrency = "EUR"
        )

        // 投影终点（3月31日）只含 lastKnownTotal 10.0；若 raw fallback 未移除，会混入 50 + 40*0.7 = 78
        val lastPoint = forecast.components.projectedSpendingPoints.last()
        assertEquals(10.0, lastPoint, 0.0001)
        // 失败计数（引擎按调用点累加）：MUST planned = committedPlanned 段 1 + mustExpensesByDay 1 = 2；
        // LIKELY planned = likelyPlanned 段 1 + likelyExpensesByDay 1 = 2。合计 4。
        assertEquals(4, forecast.excludedCount)
        assertTrue(forecast.isPartial)
    }

    @Test
    fun `block-party 跨币种 Failed 时月度与每日汇总排除该项且不回退源币金额`() = runTest {
        stubAllFailed()

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(pattern(100.0, "USD", 20)),
            plannedExpenses = listOf(planned(50.0, "USD", 25, PlannedExpensePriority.MUST)),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(budget(2000.0)),
            spendingPace = pace(),
            displayCurrency = "EUR"
        )

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = List(31) { 0f },
            budgetLimit = 2000.0
        )

        val day20 = blockParty.first { it.dayOfMonth == 20 }
        val day25 = blockParty.first { it.dayOfMonth == 25 }

        // 源币(USD)金额 100/50 不得出现在 EUR 每日汇总
        assertEquals(0.0, day20.recurringImpact, 0.0001)
        assertEquals(0.0, day25.plannedImpact, 0.0001)
        // baseTarget：budgetLimit 2000 全部保留（recurring/planned 月度合计均被排除）→ 2000/31
        assertEquals(2000.0 / 31, day20.baseTarget, 0.01)
    }

    @Test
    fun `部分失败时成功项照常计入且 isPartial 为真`() = runTest {
        stubAllFailed()

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(
                pattern(100.0, "EUR", 20),   // 恒等，不进 converter，不受影响
                pattern(200.0, "USD", 20)    // 失败：committed 段 + monthlyRecurringTotal 段 = 2 次
            ),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(budget(2000.0)),
            spendingPace = pace(),
            displayCurrency = "EUR"
        )

        assertEquals(100.0, forecast.components.totalCommitted, 0.0001)
        assertEquals(2, forecast.excludedCount)
        assertTrue(forecast.isPartial)
        // 源币 200 不得混入
        assertFalse(forecast.components.totalCommitted > 100.0)
    }

    @Test
    fun `confirmedOccurrence 转换 Failed 时源币种金额不得进入 totalCommitted`() = runTest {
        stubAllFailed()

        // ConfirmedOccurrence 路径（I1）：USD 到期日落在当月窗口内。
        // 转换失败 → 该笔从 committed 汇总排除（不回退源币金额）并计数。
        val usdOccurrence = ConfirmedOccurrence(
            dueDate = dayMs(20),
            expectedAmount = 100.0,
            expectedCurrency = "USD",
            merchant = "Recurring",
            categoryId = null
        )

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(budget(2000.0)),
            spendingPace = pace(),
            confirmedOccurrences = listOf(usdOccurrence),
            displayCurrency = "EUR"
        )

        // USD 100 不得以任何形式进入 EUR committed 汇总（无源币回退）
        assertEquals(0.0, forecast.components.totalCommitted, 0.0001)
        // committed 段 1 次失败 → excludedCount >= 1 且 isPartial = true
        assertTrue("至少一次排除计数", forecast.excludedCount >= 1)
        assertTrue("转换失败必须置 isPartial", forecast.isPartial)
    }

    // ── 3. 单币种恒等路径不变 ──────────────────────────────────────────

    @Test
    fun `单币种 fixture 不触发 convertOutcome 且语义不变`() = runTest {
        // 先钉死：若引擎意外发起跨币种调用（即恒等短路失效），测试立即失败
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } throws AssertionError("single-currency fixture must not call convertOutcome")

        val forecast = engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = listOf(pattern(100.0, "EUR", 20)),
            plannedExpenses = listOf(planned(200.0, "EUR", 25, PlannedExpensePriority.MUST)),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(budget(2000.0)),
            spendingPace = pace(),
            displayCurrency = "EUR"
        )

        assertEquals(300.0, forecast.components.totalCommitted, 0.01)
        assertEquals(0, forecast.excludedCount)
        assertFalse(forecast.isPartial)
        coVerify(exactly = 0) {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        }
    }
}
