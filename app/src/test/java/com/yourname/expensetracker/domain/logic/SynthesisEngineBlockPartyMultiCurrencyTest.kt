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
import com.yourname.expensetracker.domain.model.BlockPartyStatus
import com.yourname.expensetracker.domain.model.TransactionSummary
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * RP-06 6b slice 3 (P5-CURRENT-009 step 1) — block-party actual-spend 转换路径测试。
 *
 * 合同（RP-06-pace-synthesis.md §6b P5-CURRENT-009）：
 * 1. raw 实体只做元数据（topTransactions）；参与算术的金额必须经 typed converter。
 * 2. 转换失败 → 排除该 item（贡献 0）并计数；源币种金额不得进入 bpCurrency 汇总。
 * 3. 无 raw list 但有 normalized daily 值 → 用 normalized 值（?: 语义保留）；
 *    空 raw list 不得覆盖有效的 normalized 值。
 * 4. bpCurrency 为空（blank）→ 行为与旧版逐字节一致（恒等求和，不触发 converter）。
 */
class SynthesisEngineBlockPartyMultiCurrencyTest {

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

    // ── fixture helpers ────────────────────────────────────────────────

    private fun pace() = SpendingPace(
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

    /** 空 forecast：无 recurring / planned，displayCurrency 可指定。 */
    private suspend fun forecast(displayCurrency: String) =
        engine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(budget(2000.0)),
            spendingPace = pace(),
            displayCurrency = displayCurrency
        )

    /** 当月 day 日 12:00（引擎按系统默认时区取 day-of-month）。 */
    private fun dayMs(day: Int): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun tx(amount: Double, day: Int, currency: String = "EUR", merchant: String = "M") =
        TransactionSummary(
            id = 0L,
            amount = amount,
            effectiveAmount = amount,
            merchant = merchant,
            date = dayMs(day),
            categoryId = null,
            currency = currency
        )

    private fun converted(amount: Double, from: String, to: String, rate: Double = 1.0) =
        ConversionOutcome.Converted(
            originalAmount = amount,
            originalCurrency = CurrencyCode(from),
            convertedAmount = amount * rate,
            targetCurrency = CurrencyCode(to),
            rateUsed = rate,
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

    /** USD→EUR 恒等；EUR→EUR 恒等（引擎 identity 短路，不会到达 mock）。 */
    private fun stubUsdConverted() {
        coEvery {
            currencyConverter.convertOutcome(any(), eq("USD"), eq("EUR"), any(), any(), any())
        } answers { converted(firstArg(), "USD", "EUR") }
    }

    /** USD→EUR 一律 Failed；EUR→EUR 恒等（引擎 identity 短路，不会到达 mock）。 */
    private fun stubUsdFailed() {
        coEvery {
            currencyConverter.convertOutcome(any(), eq("USD"), eq("EUR"), any(), any(), any())
        } answers { failed(firstArg(), "USD", "EUR") }
    }

    // ── 1. 多币种 + 单项转换失败：源币金额不得进入当日 actualSpent ──────

    @Test
    fun `转换失败项的源币金额不进当日actualSpent且成功项照常计入`() = runTest {
        stubUsdFailed()

        val forecast = forecast("EUR")
        // day 10：EUR 30（identity，不进 converter）+ USD 50（Failed → 排除）
        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = listOf(
                tx(30.0, day = 10, currency = "EUR"),
                tx(50.0, day = 10, currency = "USD")
            ),
            dailySpending = List(31) { 0f },
            budgetLimit = 2000.0
        )

        val day10 = blockParty.first { it.dayOfMonth == 10 }
        // 失败项 50（USD 源币金额）不得出现；成功项 30 照常计入
        assertEquals(30.0, day10.actualSpent, 0.0001)
        assertFalse(day10.actualSpent == 80.0)
        // 元数据不受影响：topTransactions 仍保留两条（含失败项）
        assertEquals(2, day10.topTransactions.size)
        // 状态：30 <= target(2000/31) → UNDER_BUDGET 而非 NO_DATA
        assertEquals(BlockPartyStatus.UNDER_BUDGET, day10.status)
        // converter 只为 USD 项调用（EUR 项 identity 短路）
        coVerify(exactly = 1) {
            currencyConverter.convertOutcome(any(), "USD", "EUR", any(), any(), any())
        }
    }

    @Test
    fun `同一fixture下USD成功时两币种金额换算后合并`() = runTest {
        stubUsdConverted()

        val forecast = forecast("EUR")
        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = listOf(
                tx(30.0, day = 10, currency = "EUR"),
                tx(50.0, day = 10, currency = "USD")
            ),
            dailySpending = List(31) { 0f },
            budgetLimit = 2000.0
        )

        val day10 = blockParty.first { it.dayOfMonth == 10 }
        // 与失败测试对称：转换成功 → 30 + 50（rate 1.0）
        assertEquals(80.0, day10.actualSpent, 0.0001)
    }

    // ── 2. 无 raw list 但有 normalized daily 值：用 normalized 值 ──────

    @Test
    fun `无raw list时当日actualSpent采用normalized daily值`() = runTest {
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } throws AssertionError("no expenses: converter must not be called")

        val forecast = forecast("EUR")
        val daily = MutableList(31) { 0f }
        daily[9] = 42.5f                              // day 10（index 9）

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),                   // 无 raw list
            dailySpending = daily,
            budgetLimit = 2000.0
        )

        val day10 = blockParty.first { it.dayOfMonth == 10 }
        assertEquals(42.5, day10.actualSpent, 0.0001)
        assertEquals(BlockPartyStatus.UNDER_BUDGET, day10.status)
        // 空 expenses：无 converter 调用
        coVerify(exactly = 0) {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        }
    }

    // ── 3. 空 raw list / 零 normalized 值不得凭空制造数值 ───────────────

    @Test
    fun `空raw list且零normalized值时actualSpent保持0不伪造非零`() = runTest {
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } throws AssertionError("converter must not be called on empty inputs")

        val forecast = forecast("EUR")
        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = List(31) { 0f },          // 全 0（历史里该天没有花销）
            budgetLimit = 2000.0
        )

        // day 10 在 dayOfMonth(15) 之前：actual = 0.0（非 null）→ 非 NO_DATA 语义
        // 但注意：0.0 来自 history 的 0f，属真实数据点，引擎按 UNDER_BUDGET 处理
        val day10 = blockParty.first { it.dayOfMonth == 10 }
        assertEquals(0.0, day10.actualSpent, 0.0001)
        // 关键：不 fabricated——未传入任何数据的天不会被放大成非零
        val day20 = blockParty.first { it.dayOfMonth == 20 }
        assertEquals(0.0, day20.actualSpent, 0.0001)
    }

    @Test
    fun `某天无raw也无history时该天为NO_DATA不伪造数值`() = runTest {
        val forecast = forecast("EUR")
        // dailySpending 传空列表：所有天 actualFromHistory = null，且无 raw
        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = emptyList(),
            dailySpending = emptyList(),
            budgetLimit = 2000.0
        )

        // 过去的天（< dayOfMonth 15）：actual == null → NO_DATA，actualSpent = 0.0
        val pastDays = blockParty.filter { it.dayOfMonth < 15 }
        assertTrue(pastDays.isNotEmpty())
        assertTrue(pastDays.all { it.status == BlockPartyStatus.NO_DATA })
        assertTrue(pastDays.all { it.actualSpent == 0.0 })
    }

    // ── 4. blank bpCurrency：与旧版逐字节一致（恒等求和，不触发 converter）──

    @Test
    fun `blank displayCurrency时保留恒等求和且不触发converter`() = runTest {
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } throws AssertionError("blank displayCurrency must not call convertOutcome")

        // displayCurrency = ""（旧版 golden 场景）
        val forecast = forecast("")
        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = listOf(
                tx(30.0, day = 10, currency = "EUR"),
                tx(50.0, day = 10, currency = "USD")   // 混币种也不转换——legacy 行为
            ),
            dailySpending = List(31) { 0f },
            budgetLimit = 2000.0
        )

        val day10 = blockParty.first { it.dayOfMonth == 10 }
        // 旧版行为：直接 sumOf effectiveAmount，不做转换
        assertEquals(80.0, day10.actualSpent, 0.0001)
        coVerify(exactly = 0) {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        }
    }

    // ── 5. 空 raw list 不得覆盖 normalized 值（?: 语义双向验证）─────────

    @Test
    fun `有raw list时当日不回退到history`() = runTest {
        stubUsdConverted()

        val forecast = forecast("EUR")
        val dailySpending = List(31) { 0f }.toMutableList()
        dailySpending[9] = 99.0f                      // history day 10 = 99

        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = listOf(tx(30.0, day = 10, currency = "EUR")), // raw 存在
            dailySpending = dailySpending,
            budgetLimit = 2000.0
        )

        // FCST-3：raw list 存在 → 用 raw（转换后 30），不回退 history 99
        val day10 = blockParty.first { it.dayOfMonth == 10 }
        assertEquals(30.0, day10.actualSpent, 0.0001)
        assertFalse(day10.actualSpent == 99.0)
    }

    // ── 6. RP-06 D6: per-day conversion-failure count on BlockPartyDay ──

    @Test
    fun `转换失败的当日携带conversionFailureCount其余天为0`() = runTest {
        stubUsdFailed()

        val forecast = forecast("EUR")
        // day 10: EUR 30 (identity) + USD 50 (Failed → excluded) → 1 failure
        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = listOf(
                tx(30.0, day = 10, currency = "EUR"),
                tx(50.0, day = 10, currency = "USD")
            ),
            dailySpending = List(31) { 0f },
            budgetLimit = 2000.0
        )

        val day10 = blockParty.first { it.dayOfMonth == 10 }
        assertEquals(
            "Failed-conversion day must carry the failure count",
            1, day10.conversionFailureCount
        )
        // 所有其他天：无失败 → 计数为 0
        val otherDays = blockParty.filter { it.dayOfMonth != 10 }
        assertTrue(otherDays.isNotEmpty())
        assertTrue(
            "Days without conversion failures must carry 0",
            otherDays.all { it.conversionFailureCount == 0 }
        )
        // 汇总守恒：单日失败数之和 = 引擎日志计数（此处仅 day10 一项失败）
        assertEquals(1, blockParty.sumOf { it.conversionFailureCount })
    }

    @Test
    fun `恒等路径天conversionFailureCount全为0且不触发converter`() = runTest {
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } throws AssertionError("blank displayCurrency must not call convertOutcome")

        // blank bpCurrency → identity sum（旧版 golden 场景），永不失败
        val forecast = forecast("")
        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = listOf(
                tx(30.0, day = 10, currency = "EUR"),
                tx(50.0, day = 12, currency = "USD")   // 混币种也不转换——identity 路径
            ),
            dailySpending = List(31) { 0f },
            budgetLimit = 2000.0
        )

        assertTrue(
            "Identity-path days must carry 0 conversion failures",
            blockParty.all { it.conversionFailureCount == 0 }
        )
        coVerify(exactly = 0) {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `全转换成功的天conversionFailureCount为0`() = runTest {
        stubUsdConverted()

        val forecast = forecast("EUR")
        val blockParty = engine.calculateBlockPartyData(
            forecast = forecast,
            expenses = listOf(
                tx(30.0, day = 10, currency = "EUR"),
                tx(50.0, day = 12, currency = "USD")   // Converted → 计入，不失败
            ),
            dailySpending = List(31) { 0f },
            budgetLimit = 2000.0
        )

        assertTrue(
            "Days whose conversions all succeed must carry 0",
            blockParty.all { it.conversionFailureCount == 0 }
        )
        val day12 = blockParty.first { it.dayOfMonth == 12 }
        assertEquals(50.0, day12.actualSpent, 0.0001)
    }
}
