package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.ForecastDataQuality
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.NormalizedForecastInput
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.BlockPartyStatus
import com.yourname.expensetracker.domain.model.CategoryInfo
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.dashboard.DomainBlockStatus
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategoryBreakdown
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.DomainDayBudgetStatus
import com.yourname.expensetracker.domain.model.dashboard.DomainExpenseSummary
import com.yourname.expensetracker.domain.model.dashboard.toTransactionSummary
import com.yourname.expensetracker.domain.model.TransactionSummary
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DashboardTextKeys
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ─── Domain models exposed to the UI layer ───────────────────────────────────

sealed class DashboardWidget {
    /**
     * DSH-6: Safe-to-spend widget showing how much the user can safely spend
     * for the remainder of the budget period.
     *
     * ## Fallback behavior
     * When no budget is configured (`totalBudget == null`), the widget falls back
     * to displaying the **month-to-date total spend** (not a remaining budget).
     * This can be confusing because the amount shown is money *already spent*
     * rather than money *available to spend*.
     *
     * ## Recommended CTA
     * UI should display a call-to-action encouraging users to set a budget when
     * `totalBudget == null`:
     * > "Set a monthly budget to see how much you can safely spend."
     *
     * @param amount       Safe-to-spend amount when a budget exists, or
     *                     month-to-date total spent as a fallback.
     * @param totalBudget  The overall budget amount, or `null` if no budget is set.
     * @param daysRemaining Days left in the current budget period.
     */
    data class SafeToSpend(
        val amount: Double?,
        val totalBudget: Double?,
        val daysRemaining: Int,
        /** S4-006: true when conversion failures made this amount partial */
        val isPartial: Boolean = false,
        /** PR3: true when budget remaining is not yet normalized */
        val isUnavailable: Boolean = false,
        /** PR3: currency quality metadata when available */
        val currencyQuality: CurrencyQualityUi? = null,
        val conversionWarningCount: Int = 0
    ) : DashboardWidget()

    data class BudgetBlockParty(
        val days: List<DomainDayBudgetStatus>
    ) : DashboardWidget()

    data class SpendingPaceWidget(
        val pace: SpendingPace
    ) : DashboardWidget()

    data class PendingReviewAlert(
        val count: Int
    ) : DashboardWidget()

    data class PeriodSummary(
        val todaySpent: Double,
        val weekSpent: Double,
        val monthSpent: Double,
        /** S4-006: true when any conversion failed for these totals */
        val isPartial: Boolean = false,
        val currencyQuality: CurrencyQualityUi? = null
    ) : DashboardWidget()

    data class TopCategories(
        val categories: List<CategorySpending>,
        val currencyQuality: CurrencyQualityUi? = null
    ) : DashboardWidget()

    data class BudgetHealthWidget(
        val statuses: List<BudgetStatusSnapshot>,
        val summary: UiText?
    ) : DashboardWidget()

    data class RecentTransactions(
        val expenses: List<DashboardExpense>
    ) : DashboardWidget()

    data class NaturalLanguageInsight(
        val text: UiText,
        val icon: String
    ) : DashboardWidget()

    data class SpendingTrend(
        val series: List<SpendingTrendSeries>,
        val currencyQuality: CurrencyQualityUi? = null
    ) : DashboardWidget()

    data class FinancialWeatherWidget(
        val weather: FinancialWeather
    ) : DashboardWidget()

    data class FinancialRunway(
        val daysRemaining: Int,
        val totalBudget: Double,
        val discretionaryRemaining: Double,
        val averageDailyDiscretionarySpend: Double,
        val monthlyIncome: Double,
        val committedExpenses: Double,
        val likelyExpenses: Double,
        val status: RunwayStatus,
        val isUnavailable: Boolean = false,
        val currencyQuality: CurrencyQualityUi? = null
    ) : DashboardWidget()

    enum class RunwayStatus {
        HEALTHY,   // 14+ days
        CAUTION,   // 7–13 days
        CRITICAL,  // < 7 days
        NO_INCOME  // No deposits detected
    }

    data class MonteCarloForecast(
        val result: MonteCarloResult,
        val currencyQuality: CurrencyQualityUi? = null
    ) : DashboardWidget()

    data object TotalsDashboard : DashboardWidget()

    data class NoSpendStreak(
        val currentStreak: Int,
        val personalBest: Int,
        val daysWithoutSpendingThisMonth: Int
    ) : DashboardWidget()

    data class FinancialHealthScoreWidget(
        val healthScore: com.yourname.expensetracker.domain.health.HealthScoreResult
    ) : DashboardWidget()

    /**
     * Financial Health Score V2 widget (F5) - Enhanced health score calculation.
     */
    data class FinancialHealthScoreV2Widget(
        val healthScore: com.yourname.expensetracker.domain.health.FinancialHealthResult
    ) : DashboardWidget()
    
    data class LifestyleSavingsPrompt(
        val inflationRate: Double,
        val suggestedUplift: Double,
        val reason: String,
        val hasExistingGoals: Boolean
    ) : DashboardWidget()
    
    /**
     * Monthly Savings Sweep widget - shown at month-end when there is
     * underspend that can be safely swept to savings goals.
     */
    data class SavingsSweepPrompt(
        val sweepAmount: Double,
        val underspend: Double,
        val riskBuffer: Double,
        val goalAllocations: List<SweepGoalAllocation>,
        val confidence: Double,
        val daysUntilMonthEnd: Int
    ) : DashboardWidget()
    
    data class SweepGoalAllocation(
        val goalId: Long,
        val goalName: String,
        val suggestedAmount: Double,
        val currentProgress: Double,
        val targetAmount: Double
    )
    
    data class MoneyRadar(
        val data: MoneyRadarData
    ) : DashboardWidget()

    /**
     * Financial Stress Forecast widget (F8) - Shows 30/60/90 day cash crunch prediction.
     */
    data class FinancialStressForecast(
        val result: com.yourname.expensetracker.domain.forecasting.StressForecastResult
    ) : DashboardWidget()
}

data class CategorySpending(
    val category: CategoryInfo,
    val total: Double,
    val percentage: Float,
    val currency: String  // S4-023: no default — callers must pass home currency explicitly
) {
    val moneyTotal: MoneyAmount get() = MoneyAmount(total, CurrencyCode(currency))
}

data class SpendingTrendSeries(
    val label: String,
    val data: List<Float>,
    val isCurrentMonth: Boolean
)

data class CompiledDashboardData(
    val allWidgets: List<DashboardWidget>,
    val totalSpent: Double,
    val txCount: Int,
    /** S4-006: true when any currency conversion failed for dashboard totals */
    val isPartial: Boolean = false,
    /** CURR-587-04: Canonical normalized input — always populated, never null */
    val normalizedInput: DashboardNormalizedInputResult
)

// ─── Use Case ─────────────────────────────────────────────────────────────────

@Singleton
class ComputeDashboardWidgetsUseCase @Inject constructor(
    private val insightsEngine: InsightsEngine,
    private val synthesisEngine: SynthesisEngine,
    private val monteCarloSimulator: MonteCarloSpendingSimulator,
    private val timeProvider: TimeProvider,
    private val multiCurrencyRepository: MultiCurrencyRepository,
    private val healthCalculator: com.yourname.expensetracker.domain.health.FinancialHealthCalculator,
    private val healthScoreV2: com.yourname.expensetracker.domain.health.FinancialHealthScoreV2,
    private val lifestyleSavingsPromptUseCase: com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase,
    private val monthlySavingsSweepUseCase: com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase,
    private val computeMoneyRadarUseCase: ComputeMoneyRadarUseCase,
    private val stressForecastEngine: com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine,
    private val forecastInputAssembler: ForecastInputAssembler,
    private val currencyConverter: com.yourname.expensetracker.domain.currency.CurrencyConverter,
    private val currencySettingsRepository: com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
) {

    /**
     * Intermediate holder for pre-computed time/expense context shared across sub-methods.
     * Keeps the top-level `compute()` method small enough to avoid DEX register overflow.
     */
    private class ComputeContext(
        val data: ProcessedDashboardData,
        val now: Long,
        val todayStart: Long,
        val weekStart: Long,
        val monthStart: Long,
        val calendar: java.util.Calendar,
        val daysInMonth: Int,
        val dayOfMonth: Int,
        val daysRemaining: Int,
        val purchases: List<DashboardExpense>,
        val deposits: List<DashboardExpense>,
        val expenseEntities: List<TransactionSummary>,
        val totalSpent: Double,
        val monthSpent: Double,
        val txCount: Int,
        val previousMonthTotal: Double,
        val todaySpent: Double,
        val todayTxCount: Int,
        val weekSpent: Double,
        /** S4-006R: true when today/week aggregates had conversion failures */
        val periodIsPartial: Boolean,
        val overallBudget: BudgetStatusSnapshot?,
        val totalBudgetAmount: Double,
        /** CURR-587-04: Canonical normalized input result for all money widgets */
        val normalizedInputResult: DashboardNormalizedInputResult
    )

    /** Pure computation: maps raw dashboard data → a list of ordered [DashboardWidget]s. */
    suspend fun compute(processedData: ProcessedDashboardData): CompiledDashboardData {
        val ctx = buildContext(processedData)

        val runwayResult = computeRunwayAndForecast(ctx)
        val blockPartyDays = computeBlockParty(ctx, runwayResult)
        val monteCarloWidget = computeMonteCarlo(ctx, runwayResult)
        val categoryTotals = computeCategoryTotals(ctx)
        val trend = computeSpendingTrend(ctx)
        val insightText = buildNaturalLanguageInsight(
            ctx.monthSpent, ctx.previousMonthTotal, ctx.todaySpent, ctx.todayTxCount
        )
        val budgetSummary = computeBudgetSummary(ctx)
        val streakData = calculateStreakData(ctx.calendar, ctx.data.data.expenses, ctx.monthStart)
        val healthScore = computeHealthScore(ctx, streakData.first)
        val healthScoreV2Result = computeHealthScoreV2(ctx)
        val lifestyleWidget = computeLifestyleWidget()
        val savingsSweepWidget = computeSavingsSweepWidget()
        val moneyRadarData = computeMoneyRadarUseCase.compute()
        val stressForecastResult = computeStressForecast()

        val widgets = assembleWidgets(
            ctx, runwayResult, blockPartyDays, monteCarloWidget,
            categoryTotals, trend, insightText, budgetSummary,
            streakData, healthScore, healthScoreV2Result,
            lifestyleWidget, savingsSweepWidget, moneyRadarData, stressForecastResult
        )

        val normalizedInputPartial = ctx.normalizedInputResult.let {
            it is DashboardNormalizedInputResult.Unavailable ||
                (it is DashboardNormalizedInputResult.Available && it.input.dataQuality.isPartial)
        }

        return CompiledDashboardData(
            allWidgets = widgets,
            totalSpent = ctx.totalSpent,
            txCount = ctx.txCount,
            isPartial = ctx.data.summary.isPartial || ctx.periodIsPartial || normalizedInputPartial,
            normalizedInput = ctx.normalizedInputResult
        )
    }

    /**
     * CURR-70F-13: Produce the canonical normalized input for dashboard widgets.
     * All widgets that display money totals should consume from this single source.
     */
    suspend fun produceDashboardNormalizedInput(
        expenses: List<com.yourname.expensetracker.data.database.entity.Expense>,
        periodStart: Long,
        periodEnd: Long
    ): DashboardNormalizedInputResult {
        val resolution = currencySettingsRepository.resolveHomeCurrency()
        val homeCurrency = resolution.currencyOrNull
            ?: return DashboardNormalizedInputResult.Unavailable(
                reason = "Home currency unavailable",
                periodStart = periodStart,
                periodEnd = periodEnd
            )

        val engine = com.yourname.expensetracker.domain.core.money.MoneyNormalizationEngine(currencyConverter)
        val rateBasis = com.yourname.expensetracker.domain.core.money.RateBasis.TRANSACTION_DATE

        val todayStart = TimePeriodUtils.getStartOfDay(periodEnd)
        val weekStart = TimePeriodUtils.getStartOfWeek(periodEnd)

        val purchases = expenses.filter {
            it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE && !it.isNotMine
        }
        // NEW-P5-003: Exclude shared-expense deposits (isSharedExpense=true) so deposit
        // totals reflect only the user's own income, not shared-expense repayments.
        val deposits = expenses.filter {
            it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT
                && !it.isNotMine
                && !it.isSharedExpense
        }

        val periodAggregate = engine.aggregateExpenses(purchases, homeCurrency, rateBasis, com.yourname.expensetracker.domain.core.money.TransactionTypeFilter.PURCHASE_ONLY)
        val todayAggregate = engine.aggregateExpenses(purchases.filter { it.date >= todayStart }, homeCurrency, rateBasis, com.yourname.expensetracker.domain.core.money.TransactionTypeFilter.PURCHASE_ONLY)
        val weekAggregate = engine.aggregateExpenses(purchases.filter { it.date >= weekStart }, homeCurrency, rateBasis, com.yourname.expensetracker.domain.core.money.TransactionTypeFilter.PURCHASE_ONLY)
        val monthAggregate = engine.aggregateExpenses(purchases.filter { it.date >= periodStart }, homeCurrency, rateBasis, com.yourname.expensetracker.domain.core.money.TransactionTypeFilter.PURCHASE_ONLY)
        val depositAggregate = engine.aggregateExpenses(deposits, homeCurrency, rateBasis, com.yourname.expensetracker.domain.core.money.TransactionTypeFilter.INCOME_ONLY)

        // Compute previous month aggregate
        val previousMonthEnd = periodStart - 1L
        val previousMonthStart = TimePeriodUtils.getStartOfMonth(previousMonthEnd)
        val previousMonthPurchases = purchases.filter { it.date >= previousMonthStart && it.date < periodStart }
        val previousMonthAggregate = if (previousMonthPurchases.isEmpty()) {
            null
        } else {
            engine.aggregateExpenses(previousMonthPurchases, homeCurrency, rateBasis, com.yourname.expensetracker.domain.core.money.TransactionTypeFilter.PURCHASE_ONLY)
        }

        // Compute per-day aggregates for the current period
        val dailyAggregates = purchases
            .groupBy { TimePeriodUtils.getStartOfDay(it.date) }
            .mapValues { (_, group) ->
                engine.aggregateExpenses(group, homeCurrency, rateBasis, com.yourname.expensetracker.domain.core.money.TransactionTypeFilter.PURCHASE_ONLY)
            }

        // NEW-P5-008: Category breakdown uses PURCHASE_ONLY filter (matching dashboard totals),
        // not ALL_TYPES. Since 'purchases' is already PURCHASE-only, this is a semantic fix.
        val categoryAggregates = purchases
            .groupBy { it.categoryId }
            .mapValues { (_, group) ->
                engine.aggregateExpenses(group, homeCurrency, rateBasis, com.yourname.expensetracker.domain.core.money.TransactionTypeFilter.PURCHASE_ONLY)
            }

        val normalizedExpenses = expenses.mapNotNull { expense ->
            when (val result = engine.normalizeExpense(expense, homeCurrency, rateBasis)) {
                is com.yourname.expensetracker.domain.core.money.NormalizationResult.Included -> result.value
                is com.yourname.expensetracker.domain.core.money.NormalizationResult.Excluded -> null
            }
        }

        val dataQuality = CurrencyDataQuality.fromAggregates(
            listOf(periodAggregate, todayAggregate, weekAggregate, monthAggregate, depositAggregate) +
                categoryAggregates.values,
            rateBasis
        )

        return DashboardNormalizedInputResult.Available(DashboardNormalizedInput(
            homeCurrency = homeCurrency,
            periodStart = periodStart,
            periodEnd = periodEnd,
            normalizedExpenses = normalizedExpenses,
            periodAggregate = periodAggregate,
            todayAggregate = todayAggregate,
            weekAggregate = weekAggregate,
            monthAggregate = monthAggregate,
            previousMonthAggregate = previousMonthAggregate,
            categoryAggregates = categoryAggregates,
            depositAggregate = depositAggregate,
            dataQuality = dataQuality
        ))
    }

    // ── Sub-methods to keep each function under the DEX 256-register limit ───

    private suspend fun buildContext(processedData: ProcessedDashboardData): ComputeContext {
        val data = processedData.data
        val summary = processedData.summary
        val expenses = data.expenses
        val now = timeProvider.now()
        val todayStart = TimePeriodUtils.getStartOfDay(now)
        val weekStart = TimePeriodUtils.getStartOfWeek(now)
        val monthStart = TimePeriodUtils.getStartOfMonth(now)

        // CURR-587-04/05: Produce canonical normalized input once per compute run
        val expenseEntities = expenses.map { it.toExpenseEntity() }
        val normalizedInputResult = produceDashboardNormalizedInput(expenseEntities, monthStart, now)

        // Derive today/week/month from normalized input; 0.0 if unavailable — no raw fallback
        val normalizedIn = (normalizedInputResult as? DashboardNormalizedInputResult.Available)?.input
        val todaySpent = normalizedIn?.todayAggregate?.displayAmount ?: 0.0
        val weekSpent = normalizedIn?.weekAggregate?.displayAmount ?: 0.0
        val monthSpent = normalizedIn?.monthAggregate?.displayAmount ?: 0.0
        val previousMonthTotal = normalizedIn?.previousMonthAggregate?.displayAmount ?: 0.0
        val periodIsPartial = normalizedIn?.dataQuality?.isPartial ?: true

        val purchases = expenses.filter {
            it.transactionType == DashboardTransactionType.PURCHASE && !it.isNotMine
        }
        // P5-PR2 (NEW-P5-003): Exclude not-mine shared expenses from deposit totals
        val deposits = expenses.filter { it.transactionType == DashboardTransactionType.DEPOSIT && !it.isNotMine }

        val todayPurchases = purchases.filter { it.date >= todayStart }
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val daysInMonth = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(java.util.Calendar.DAY_OF_MONTH)

        val budgetStatuses = data.budgetStatuses
        val overallBudget = budgetStatuses.find { it.budgetCategoryId == null }

        return ComputeContext(
            data = processedData,
            now = now,
            todayStart = todayStart,
            weekStart = weekStart,
            monthStart = monthStart,
            calendar = calendar,
            daysInMonth = daysInMonth,
            dayOfMonth = dayOfMonth,
            daysRemaining = daysInMonth - dayOfMonth,
            purchases = purchases,
            deposits = deposits,
            expenseEntities = expenses.map { it.toTransactionSummary() },
            totalSpent = monthSpent,
            monthSpent = monthSpent,
            txCount = summary.transactionCount,
            previousMonthTotal = previousMonthTotal,
            todaySpent = todaySpent,
            todayTxCount = todayPurchases.size,
            weekSpent = weekSpent,
            periodIsPartial = periodIsPartial,
            overallBudget = overallBudget,
            totalBudgetAmount = overallBudget?.budgetAmount ?: 0.0, // G-MONEY-ALLOW[CURR-587-05][G-MONEY-15]: legacy budget path until budget normalization is implemented
            normalizedInputResult = normalizedInputResult
        )
    }

    /** Maps a [DashboardExpense] to a minimal [Expense] entity for normalization.
     * Uses effectiveAmount as amount so the normalization engine sees the ownership-adjusted value. */
    private fun DashboardExpense.toExpenseEntity(): com.yourname.expensetracker.data.database.entity.Expense {
        return com.yourname.expensetracker.data.database.entity.Expense(
            id = id,
            amount = effectiveAmount, // use ownership-adjusted amount for normalization
            currency = currency,
            merchant = merchant,
            transactionType = when (transactionType) {
                DashboardTransactionType.PURCHASE -> com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE
                DashboardTransactionType.WITHDRAWAL -> com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL
                DashboardTransactionType.TRANSFER -> com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER
                DashboardTransactionType.DEPOSIT -> com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT
                DashboardTransactionType.UNKNOWN -> com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN
            },
            date = date,
            categoryId = categoryId,
            isNotMine = isNotMine,
            isManualEntry = isManualEntry
        )
    }

    private sealed interface RunwayResult {
        data class Available(
            val currentPace: SpendingPace,
            val forecast: com.yourname.expensetracker.domain.model.FinancialForecast,
            val financialRunway: DashboardWidget.FinancialRunway,
            val totalRemaining: Double,
            val totalCommitted: Double,
            val totalLikely: Double,
            val purchasesThisMonth: List<DashboardExpense>
        ) : RunwayResult

        data class Unavailable(
            val reason: String,
            val purchasesThisMonth: List<DashboardExpense>,
            val currencyQuality: CurrencyQualityUi
        ) : RunwayResult
    }

    private suspend fun computeRunwayAndForecast(ctx: ComputeContext): RunwayResult {
        val purchasesThisMonth = ctx.purchases.filter { it.date >= ctx.monthStart }
        val data = ctx.data.data

        // CURR-587-06: Use pre-normalized input directly, bypass ExpenseSnapshot bridge
        val normalized = when (val n = ctx.normalizedInputResult) {
            is DashboardNormalizedInputResult.Available -> n.input
            is DashboardNormalizedInputResult.Unavailable -> {
                return RunwayResult.Unavailable(
                    reason = n.reason,
                    purchasesThisMonth = purchasesThisMonth,
                    currencyQuality = CurrencyQualityUi(
                        isPartial = true,
                        quality = com.yourname.expensetracker.domain.core.money.ConversionQuality.UNAVAILABLE,
                        warningMessage = n.reason
                    )
                )
            }
        }

        // Build pastSumDaily from normalized expenses (already in home currency)
        val now = timeProvider.now()
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        val currentDayIndex = TimePeriodUtils.daysBetween(monthStart, now).coerceAtLeast(0)
        val amountByDay = DoubleArray(currentDayIndex + 1)
        normalized.normalizedExpenses.forEach { ne ->
            if (ne.transactionType == "PURCHASE" && !ne.isNotMine && ne.date in monthStart..now) {
                val dayIndex = TimePeriodUtils.daysBetween(monthStart, ne.date)
                if (dayIndex in amountByDay.indices) {
                    amountByDay[dayIndex] += ne.normalizedAmount
                }
            }
        }
        var runningTotal = 0.0
        val pastSumDaily = amountByDay.map { amount -> runningTotal += amount; runningTotal }

        // Build spending pace from normalized aggregates
        val daysInMonth = ctx.calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val daysElapsed = ctx.dayOfMonth
        val spendingPace = SpendingPace(
            currentMonthSpent = normalized.monthAggregate.displayAmount,
            daysElapsed = daysElapsed,
            daysInMonth = daysInMonth,
            projectedTotal = if (daysElapsed > 0) normalized.monthAggregate.displayAmount / daysElapsed * daysInMonth else normalized.monthAggregate.displayAmount,
            previousMonthTotal = normalized.previousMonthAggregate?.displayAmount,
            averageMonthlyTotal = normalized.monthAggregate.displayAmount.takeIf { it > 0 },
            pacePercentage = 100f,
            paceStatus = PaceStatus.NO_BASELINE, // Will be refined by synthesis
            displayCurrency = normalized.homeCurrency.code
        )

        val normalizedForecastInput = NormalizedForecastInput(
            homeCurrency = normalized.homeCurrency,
            normalizedExpenses = normalized.normalizedExpenses,
            pastSumDaily = pastSumDaily,
            recurringPatterns = data.recurringPatterns,
            plannedExpenses = data.plannedExpenses,
            savingsGoals = data.goals,
            budgetStatuses = data.budgetStatuses,
            spendingPace = spendingPace,
            dataQuality = ForecastDataQuality(
                isPartial = normalized.dataQuality.isPartial,
                excludedActualCount = normalized.dataQuality.excludedTransactionCount,
                conversionWarnings = listOfNotNull(normalized.dataQuality.warningMessage)
            )
        )

        val assembledInput = forecastInputAssembler.assembleNormalized(normalizedForecastInput)
        val forecast = synthesisEngine.synthesize(assembledInput)
        val currentPace = assembledInput.spendingPace

        val totalCommitted = forecast.components?.totalCommitted ?: 0.0
        val totalLikely = forecast.components?.totalLikely ?: 0.0
        val averageDailyBurn = if (ctx.dayOfMonth > 0) ctx.monthSpent / ctx.dayOfMonth else 0.0
        // CURR-587-05: Use normalized deposit aggregate only — no latest-rate fallback
        val monthlyIncome = normalized.depositAggregate?.displayAmount ?: 0.0
        // P5-PR1 (NEW-P5-011): Compute totalRemaining from budget or income minus spent.
        // G-MONEY-ALLOW[CURR-587-05][G-MONEY-15]: legacy budget path until budget normalization
        // Uses monthly income as proxy when no explicit budget exists.
        val totalRemaining = if (ctx.totalBudgetAmount > 0) { // G-MONEY-ALLOW[CURR-587-05][G-MONEY-15]: budget is home-currency user value
            (ctx.totalBudgetAmount - ctx.monthSpent).coerceAtLeast(0.0) // G-MONEY-ALLOW[CURR-587-05][G-MONEY-15]: budget is home-currency user value
        } else if (monthlyIncome > 0) {
            (monthlyIncome - ctx.monthSpent).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val runwayDays = if (averageDailyBurn > 0 && totalRemaining > 0) {
            (totalRemaining / averageDailyBurn).toInt().coerceAtLeast(0)
        } else {
            0
        }

        val runwayStatus = when {
            monthlyIncome == 0.0 -> DashboardWidget.RunwayStatus.NO_INCOME
            runwayDays >= 14     -> DashboardWidget.RunwayStatus.HEALTHY
            runwayDays >= 7      -> DashboardWidget.RunwayStatus.CAUTION
            else                 -> DashboardWidget.RunwayStatus.CRITICAL
        }

        return RunwayResult.Available(
            currentPace = currentPace,
            forecast = forecast,
            financialRunway = DashboardWidget.FinancialRunway(
                daysRemaining = runwayDays,
                totalBudget = ctx.totalBudgetAmount, // G-MONEY-ALLOW[CURR-587-05][G-MONEY-15]: legacy budget path until budget normalization
                discretionaryRemaining = totalRemaining,
                averageDailyDiscretionarySpend = averageDailyBurn,
                monthlyIncome = monthlyIncome,
                committedExpenses = totalCommitted,
                likelyExpenses = totalLikely,
                status = runwayStatus
            ),
            totalRemaining = totalRemaining,
            totalCommitted = totalCommitted,
            totalLikely = totalLikely,
            purchasesThisMonth = purchasesThisMonth
        )
    }

    private suspend fun computeBlockParty(
        ctx: ComputeContext,
        runwayResult: RunwayResult
    ): List<DomainDayBudgetStatus> {
        // When runway is unavailable, do not use raw expenseEntities or dailyHistory
        if (runwayResult is RunwayResult.Unavailable) return emptyList()

        val normalized = ctx.normalizedInputResult as? DashboardNormalizedInputResult.Available
            ?: return emptyList()

        val availableResult = runwayResult as RunwayResult.Available

        // Use normalized daily aggregates instead of raw dailyHistory
        val dailySpending = normalized.input.normalizedExpenses
            .filter { it.transactionType == "PURCHASE" && !it.isNotMine }
            .groupBy { TimePeriodUtils.getStartOfDay(it.date) }
            .mapValues { (_, expenses) -> expenses.sumOf { it.normalizedAmount }.toFloat() }

        // Build daily history list aligned with calendar days
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = ctx.now }
        val daysInMonth = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val monthStart = ctx.monthStart
        val dailyHistory = (0 until daysInMonth).map { dayIndex ->
            val dayStart = monthStart + dayIndex * TimePeriodUtils.DAY_IN_MILLIS
            dailySpending[dayStart] ?: 0f
        }

        val domainBlocks = synthesisEngine.calculateBlockPartyData(
            forecast = availableResult.forecast,
            expenses = ctx.expenseEntities, // G-MONEY-ALLOW[CURR-587-05][G-MONEY-15]: display-only transaction list, not money math
            dailySpending = dailyHistory,
            budgetLimit = normalized.input.monthAggregate.displayAmount // Use normalized month total
        )

        // Build a lookup map from categoryId → categoryName so we can populate
        // DomainExpenseSummary.categoryName with a real label rather than an ID string.
        val categoryNameById: Map<Long, String> = ctx.data.data.categories
            .associate { it.id to it.name }

        return domainBlocks.map { domain ->
            DomainDayBudgetStatus(
                dayOfMonth = domain.dayOfMonth,
                date = domain.date,
                actualSpent = domain.actualSpent,
                targetBudget = domain.targetBudget,
                isToday = domain.isToday,
                status = when (domain.status) {
                    BlockPartyStatus.UNDER_BUDGET -> DomainBlockStatus.UNDER_BUDGET
                    BlockPartyStatus.OVER_BUDGET  -> DomainBlockStatus.OVER_BUDGET
                    BlockPartyStatus.FUTURE       -> DomainBlockStatus.FUTURE
                    BlockPartyStatus.TODAY        -> DomainBlockStatus.TODAY
                    BlockPartyStatus.BILL_DAY     -> DomainBlockStatus.BILL_DAY
                    BlockPartyStatus.NO_DATA      -> DomainBlockStatus.NO_DATA
                },
                baseTarget = domain.baseTarget,
                recurringImpact = domain.recurringImpact,
                plannedImpact = domain.plannedImpact,
                recurringItems = domain.recurringItems,
                plannedItems = domain.plannedItems,
                topTransactions = domain.topTransactions.map { expense ->
                    DomainExpenseSummary(
                        id = expense.id,
                        amount = expense.effectiveAmount,
                        description = expense.merchant,
                        // Resolve a human-readable category label from the pre-loaded category
                        // list; keep null if the expense has no category or the ID is unknown.
                        // Never encode a numeric ID into a name field.
                        categoryName = expense.categoryId?.let { categoryNameById[it] },
                        date = expense.date
                    )
                }
            )
        }
    }

    private suspend fun computeMonteCarlo(
        ctx: ComputeContext,
        runwayResult: RunwayResult
    ): DashboardWidget.MonteCarloForecast? {
        if (runwayResult is RunwayResult.Unavailable) {
            return DashboardWidget.MonteCarloForecast(
                result = MonteCarloResult.unavailable(runwayResult.reason),
                currencyQuality = runwayResult.currencyQuality
            )
        }
        return try {
            // CURR-587-05: Use normalized month aggregate instead of raw repository call
            val spentToDate = when (val normalized = ctx.normalizedInputResult) {
                is DashboardNormalizedInputResult.Available -> normalized.input.periodAggregate.displayAmount
                is DashboardNormalizedInputResult.Unavailable -> {
                    return DashboardWidget.MonteCarloForecast(
                        result = MonteCarloResult.unavailable(normalized.reason),
                        currencyQuality = CurrencyQualityUi(
                            isPartial = true,
                            quality = com.yourname.expensetracker.domain.core.money.ConversionQuality.UNAVAILABLE,
                            warningMessage = normalized.reason
                        )
                    )
                }
            }
            val availableResult = runwayResult as RunwayResult.Available
            val knownUpcoming = availableResult.totalCommitted + availableResult.totalLikely
            val budgetForMC = if (ctx.totalBudgetAmount > 0) ctx.totalBudgetAmount else null // G-MONEY-ALLOW[CURR-587-05][G-MONEY-15]: legacy budget path until budget normalization

            val mcResult = monteCarloSimulator.simulate(
                spentToDate = spentToDate,
                knownUpcoming = knownUpcoming,
                budgetAmount = budgetForMC
            )

            mcResult?.let { DashboardWidget.MonteCarloForecast(it) }
        } catch (e: Exception) {
            Timber.e(e, "Monte Carlo simulation failed")
            null
        }
    }

    private fun computeCategoryTotals(ctx: ComputeContext): List<CategorySpending> {
        val normalized = ctx.normalizedInputResult as? DashboardNormalizedInputResult.Available
            ?: return emptyList() // unavailable — no raw fallback

        val input = normalized.input
        val total = input.categoryAggregates.values.sumOf { it.displayAmount }
            .takeIf { it > 0.0 } ?: input.periodAggregate.displayAmount.coerceAtLeast(0.0)

        val categoryNameById = ctx.data.data.categories.associate { it.id to it }
        return input.categoryAggregates.mapNotNull { (categoryId, aggregate) ->
            val cat = categoryNameById[categoryId] ?: return@mapNotNull null
            CategorySpending(
                category = CategoryInfo(
                    id = cat.id,
                    name = cat.name,
                    icon = cat.icon,
                    color = cat.color,
                    isIncome = false
                ),
                total = aggregate.displayAmount,
                percentage = if (total > 0.0) ((aggregate.displayAmount / total) * 100).toFloat() else 0f,
                currency = input.homeCurrency.code
            )
        }.sortedByDescending { it.total }
    }

    // CURR-587-08: SpendingTrend now consumes DashboardNormalizedInput — no direct conversion.
    private fun computeSpendingTrend(ctx: ComputeContext): DashboardWidget.SpendingTrend {
        return when (val normalized = ctx.normalizedInputResult) {
            is DashboardNormalizedInputResult.Unavailable -> {
                DashboardWidget.SpendingTrend(
                    series = emptyList(),
                    currencyQuality = CurrencyQualityUi(
                        isPartial = true,
                        quality = com.yourname.expensetracker.domain.core.money.ConversionQuality.UNAVAILABLE,
                        warningMessage = normalized.reason
                    )
                )
            }
            is DashboardNormalizedInputResult.Available -> buildTrendFromNormalizedInput(normalized.input, ctx)
        }
    }

    /**
     * NEW-P5-014: Uses java.time.ZonedDateTime for all date arithmetic,
     * avoiding java.util.Calendar DST transition edge cases.
     */
    private fun buildTrendFromNormalizedInput(
        input: DashboardNormalizedInput,
        ctx: ComputeContext
    ): DashboardWidget.SpendingTrend {
        val systemZone = java.time.ZoneId.systemDefault()
        val trendSeries = mutableListOf<SpendingTrendSeries>()

        // Group normalized purchase expenses by (year, month) using ZonedDateTime
        val purchasesByMonth = input.normalizedExpenses
            .filter { it.transactionType == "PURCHASE" && !it.isNotMine }
            .distinctBy { it.id }
            .groupBy { ne ->
                val zdt = java.time.Instant.ofEpochMilli(ne.date).atZone(systemZone)
                Pair(zdt.year, zdt.monthValue - 1) // month 0-based for label array
            }

        // Build last 6 month keys using ZonedDateTime
        val monthKeys = mutableListOf<Pair<Int, Int>>()
        val nowZdt = java.time.Instant.ofEpochMilli(ctx.now).atZone(systemZone)
        repeat(6) {
            val m = nowZdt.minusMonths(it.toLong())
            monthKeys.add(0, Pair(m.year, m.monthValue - 1))
        }

        val monthLabels = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
        monthKeys.forEach { (yr, mo) ->
            val monthExpenses = purchasesByMonth[Pair(yr, mo)] ?: emptyList()

            // Start of month using ZonedDateTime (DST-safe)
            val mStartZdt = java.time.LocalDate.of(yr, mo + 1, 1)
                .atStartOfDay(systemZone)
                .toInstant()
                .toEpochMilli()
            val daysInThisMonth = java.time.YearMonth.of(yr, mo + 1).lengthOfMonth()

            // DSH-N1: Zero-filled series for empty months
            val daily = DoubleArray(daysInThisMonth)
            monthExpenses.forEach { ne ->
                val dayIdx = TimePeriodUtils.daysBetween(mStartZdt, ne.date).coerceIn(0, daysInThisMonth - 1)
                // Use normalizedAmount — already in home currency, no conversion needed
                daily[dayIdx] += ne.normalizedAmount
            }
            var running = 0.0
            val cumulative = daily.map { d -> running += d; running.toFloat() }

            // Compare with ctx.now via ZonedDateTime
            val currentZdt = java.time.Instant.ofEpochMilli(ctx.now).atZone(systemZone)
            val isCurrentMonth = (yr == currentZdt.year && mo == currentZdt.monthValue - 1)

            trendSeries.add(SpendingTrendSeries(
                label = monthLabels[mo],
                data = cumulative,
                isCurrentMonth = isCurrentMonth
            ))
        }

        val quality = CurrencyQualityUi.from(input.dataQuality)
        return DashboardWidget.SpendingTrend(series = trendSeries, currencyQuality = quality)
    }

    private fun computeBudgetSummary(ctx: ComputeContext): UiText? {
        val budgetStatuses = ctx.data.data.budgetStatuses
        if (budgetStatuses.isEmpty()) return null
        val exceeded = budgetStatuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
        return if (exceeded > 0) UiText.fromKey(DashboardTextKeys.WIDGET_BUDGET_EXCEEDED_FORMAT, exceeded)
        else UiText.fromKey(DashboardTextKeys.WIDGET_ALL_BUDGETS_ON_TRACK)
    }

    private fun computeHealthScore(
        ctx: ComputeContext,
        currentStreak: Int
    ): com.yourname.expensetracker.domain.health.HealthScoreResult {
        val dashboardExpenses = ctx.data.data.expenses
        return healthCalculator.calculateHealthScores(
            // TODO ISSUE-3: FinancialHealthCalculator.calculateHealthScores still expects List<Expense>;
            //  pass empty list until that API is migrated to TransactionSummary / DashboardExpense.
            expenses = emptyList(),
            budgetStatuses = ctx.data.data.budgetStatuses,
            pendingReviews = ctx.data.data.pendingCount,
            todayStreak = calculateStreakForPeriod(dashboardExpenses, ctx.todayStart, ctx.now),
            weekStreak = calculateStreakForPeriod(dashboardExpenses, ctx.weekStart, ctx.now),
            monthStreak = calculateStreakForPeriod(dashboardExpenses, ctx.monthStart, ctx.now),
            noSpendStreak = currentStreak
        )
    }

    private suspend fun computeHealthScoreV2(
        ctx: ComputeContext
    ): FinancialHealthResult? {
        return try {
            healthScoreV2.calculateHealthScore(
                periodStart = ctx.monthStart,
                periodEnd = TimePeriodUtils.getEndOfMonth(ctx.now)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to calculate financial health score v2")
            null
        }
    }

    private suspend fun computeLifestyleWidget(): DashboardWidget.LifestyleSavingsPrompt? {
        val recommendation = runCatching {
            withTimeout(3000L) {
                lifestyleSavingsPromptUseCase.evaluateAndPrompt()
            }
        }
            .onFailure { Timber.e(it, "Failed to evaluate lifestyle savings prompt") }
            .getOrNull()
        return recommendation?.let {
            DashboardWidget.LifestyleSavingsPrompt(
                inflationRate = it.inflationRate,
                suggestedUplift = it.suggestedMonthlyUplift,
                reason = it.reason,
                hasExistingGoals = it.goals.isNotEmpty()
            )
        }
    }

    private suspend fun computeSavingsSweepWidget(): DashboardWidget.SavingsSweepPrompt? {
        return try {
            withTimeout(3000L) {
                monthlySavingsSweepUseCase.computeSweepRecommendation()
            }?.let { recommendation ->
                DashboardWidget.SavingsSweepPrompt(
                    sweepAmount = recommendation.safeSweepAmount,
                    underspend = recommendation.totalUnderspend,
                    riskBuffer = recommendation.riskBuffer,
                    goalAllocations = recommendation.goalAllocations.map { allocation ->
                        DashboardWidget.SweepGoalAllocation(
                            goalId = allocation.goalId,
                            goalName = allocation.goalName,
                            suggestedAmount = allocation.suggestedAllocation,
                            currentProgress = allocation.currentProgress,
                            targetAmount = allocation.targetAmount
                        )
                    },
                    confidence = recommendation.confidence,
                    daysUntilMonthEnd = TimePeriodUtils.daysBetween(recommendation.computedAt, recommendation.monthEnd)
                        .coerceAtLeast(0)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to compute savings sweep widget")
            null
        }
    }

    private suspend fun computeStressForecast(): com.yourname.expensetracker.domain.forecasting.StressForecastResult? {
        return try {
            stressForecastEngine.computeStressForecast()
        } catch (e: Exception) {
            Timber.e(e, "Failed to compute financial stress forecast")
            null
        }
    }

    private fun assembleWidgets(
        ctx: ComputeContext,
        runwayResult: RunwayResult,
        blockPartyDays: List<DomainDayBudgetStatus>,
        monteCarloWidget: DashboardWidget.MonteCarloForecast?,
        categoryTotals: List<CategorySpending>,
        trend: DashboardWidget.SpendingTrend,
        insightText: Pair<UiText, String>?,
        budgetSummary: UiText?,
        streakData: Triple<Int, Int, Int>,
        healthScore: com.yourname.expensetracker.domain.health.HealthScoreResult,
        healthScoreV2Result: com.yourname.expensetracker.domain.health.FinancialHealthResult?,
        lifestyleWidget: DashboardWidget.LifestyleSavingsPrompt?,
        savingsSweepWidget: DashboardWidget.SavingsSweepPrompt?,
        moneyRadarData: MoneyRadarData,
        stressForecastResult: com.yourname.expensetracker.domain.forecasting.StressForecastResult?
    ): List<DashboardWidget> {
        val weather = ctx.data.data.weather
        val budgetStatuses = ctx.data.data.budgetStatuses
        val pendingCount = ctx.data.data.pendingCount
        val (currentStreak, personalBest, daysWithoutSpendingThisMonth) = streakData

        return buildList {
            add(DashboardWidget.FinancialWeatherWidget(weather))

            // Money Radar Widget (F4) - Today's unified alerts
            add(DashboardWidget.MoneyRadar(moneyRadarData))

            // Financial Stress Forecast Widget (F8) - 30/60/90 day cash crunch prediction
            if (stressForecastResult != null) {
                add(DashboardWidget.FinancialStressForecast(stressForecastResult))
            }

            // Lifestyle Savings Prompt (if applicable)
            if (lifestyleWidget != null) {
                add(lifestyleWidget)
            }

            if (savingsSweepWidget != null) {
                add(savingsSweepWidget)
            }

            // Emit a single authoritative health KPI.
            if (healthScoreV2Result != null) {
                add(DashboardWidget.FinancialHealthScoreV2Widget(healthScoreV2Result))
            } else {
                add(DashboardWidget.FinancialHealthScoreWidget(healthScore))
            }

            add(DashboardWidget.TotalsDashboard)

            // No-Spend Streak Widget (gamification) - always shown
            add(DashboardWidget.NoSpendStreak(
                currentStreak = currentStreak,
                personalBest = personalBest,
                daysWithoutSpendingThisMonth = daysWithoutSpendingThisMonth
            ))

            add(
                when (val normalized = ctx.normalizedInputResult) {
                    is DashboardNormalizedInputResult.Available -> {
                        // Budget remaining not yet normalized — show unavailable with partial quality
                        DashboardWidget.SafeToSpend(
                            amount = null,
                            totalBudget = ctx.overallBudget?.budgetAmount, // G-MONEY-ALLOW[CURR-587-05][G-MONEY-15]: legacy budget display until budget normalization
                            daysRemaining = ctx.daysRemaining,
                            isPartial = normalized.input.dataQuality.isPartial,
                            isUnavailable = true,
                            currencyQuality = CurrencyQualityUi(
                                isPartial = true,
                                quality = com.yourname.expensetracker.domain.core.money.ConversionQuality.PARTIAL,
                                warningMessage = "Budget remaining not yet normalized"
                            ),
                            conversionWarningCount = normalized.input.dataQuality.excludedTransactionCount
                        )
                    }
                    is DashboardNormalizedInputResult.Unavailable -> DashboardWidget.SafeToSpend(
                        amount = null,
                        totalBudget = null,
                        daysRemaining = ctx.daysRemaining,
                        isPartial = true,
                        isUnavailable = true,
                        currencyQuality = CurrencyQualityUi.unavailable(normalized.reason),
                        conversionWarningCount = 0
                    )
                }
            )
            when (runwayResult) {
                is RunwayResult.Available -> {
                    if (runwayResult.totalRemaining > 0 || ctx.totalBudgetAmount > 0) add(runwayResult.financialRunway) // G-MONEY-ALLOW[CURR-587-05][G-MONEY-15]: legacy budget check until budget normalization
                }
                is RunwayResult.Unavailable -> {
                    add(DashboardWidget.FinancialRunway(
                        daysRemaining = 0,
                        totalBudget = 0.0,
                        discretionaryRemaining = 0.0,
                        averageDailyDiscretionarySpend = 0.0,
                        monthlyIncome = 0.0,
                        committedExpenses = 0.0,
                        likelyExpenses = 0.0,
                        status = DashboardWidget.RunwayStatus.NO_INCOME,
                        isUnavailable = true,
                        currencyQuality = runwayResult.currencyQuality
                    ))
                }
            }
            if (monteCarloWidget != null) add(monteCarloWidget)
            if (blockPartyDays.isNotEmpty()) add(DashboardWidget.BudgetBlockParty(blockPartyDays))
            if (runwayResult is RunwayResult.Available && runwayResult.currentPace.paceStatus != PaceStatus.NO_BASELINE) {
                add(DashboardWidget.SpendingPaceWidget(runwayResult.currentPace))
            }
            add(trend)
            if (pendingCount > 0) add(DashboardWidget.PendingReviewAlert(pendingCount))
            if (insightText != null) add(DashboardWidget.NaturalLanguageInsight(insightText.first, insightText.second))
            add(
                when (val normalized = ctx.normalizedInputResult) {
                    is DashboardNormalizedInputResult.Available -> DashboardWidget.PeriodSummary(
                        todaySpent = normalized.input.todayAggregate.displayAmount,
                        weekSpent = normalized.input.weekAggregate.displayAmount,
                        monthSpent = normalized.input.monthAggregate.displayAmount,
                        isPartial = normalized.input.dataQuality.isPartial,
                        currencyQuality = CurrencyQualityUi.from(normalized.input.dataQuality)
                    )
                    is DashboardNormalizedInputResult.Unavailable -> DashboardWidget.PeriodSummary(
                        todaySpent = 0.0, weekSpent = 0.0, monthSpent = 0.0,
                        isPartial = true,
                        currencyQuality = CurrencyQualityUi.unavailable(normalized.reason)
                    )
                }
            )
            if (budgetStatuses.isNotEmpty()) add(DashboardWidget.BudgetHealthWidget(budgetStatuses, budgetSummary))
            if (categoryTotals.isNotEmpty()) add(DashboardWidget.TopCategories(categoryTotals.take(5)))
            if (ctx.purchases.isNotEmpty()) add(DashboardWidget.RecentTransactions(ctx.purchases.take(5)))
        }
    }

    private fun buildNaturalLanguageInsight(
        monthSpent: Double,
        previousMonthTotal: Double,
        todaySpent: Double,
        txCount: Int
    ): Pair<UiText, String>? {
        if (previousMonthTotal > 0) {
            val diff = monthSpent - previousMonthTotal
            return when {
                diff < 0 -> Pair(
                    UiText.fromKey(DashboardTextKeys.WIDGET_INSIGHT_SPENT_LESS_FORMAT, -diff),
                    "📉"
                )
                diff > previousMonthTotal * 0.2 -> Pair(
                    UiText.fromKey(DashboardTextKeys.WIDGET_INSIGHT_SPENT_HIGHER_FORMAT, diff),
                    "📈"
                )
                else -> null
            }
        }
        if (txCount > 0 && todaySpent > 0) {
            return Pair(
                UiText.fromKey(DashboardTextKeys.WIDGET_INSIGHT_TODAY_SPENT_FORMAT, todaySpent, txCount),
                "💡"
            )
        }
        return null
    }

    /**
     * Calculates no-spend streak data for gamification.
     * @return Triple of (currentStreak, personalBest, daysWithoutSpendingThisMonth)
     */
    private fun calculateStreakData(
        calendar: java.util.Calendar,
        expenses: List<DashboardExpense>,
        startOfMonth: Long
    ): Triple<Int, Int, Int> {
        val now = calendar.timeInMillis
        val oneDayMs = TimePeriodUtils.DAY_IN_MILLIS
        val todayStart = TimePeriodUtils.getStartOfDay(now)
        val dayOfMonth = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        
        // Track unique PURCHASE days owned by the user.
        val purchaseDays = expenses
            .filter {
                it.transactionType == DashboardTransactionType.PURCHASE &&
                    !it.isNotMine
            }
            .map { TimePeriodUtils.getStartOfDay(it.date) }
            .filter { it <= todayStart }
            .distinct()
            .sorted()

        // Explicit empty-history handling: no purchases yet => streak spans elapsed month days.
        if (purchaseDays.isEmpty()) {
            val elapsedDaysThisMonth = dayOfMonth.coerceAtLeast(0)
            return Triple(elapsedDaysThisMonth, elapsedDaysThisMonth, elapsedDaysThisMonth)
        }
        
        // Calculate current streak (from today backward), bounded by app-history start.
        val oldestPurchaseDay = purchaseDays.first()
        var currentStreak = 0
        var checkDate = todayStart
        val purchaseDaySet = purchaseDays.toHashSet()
        
        while (checkDate >= oldestPurchaseDay) {
            if (!purchaseDaySet.contains(checkDate)) {
                currentStreak++
                checkDate -= oneDayMs
            } else {
                break
            }
        }
        
        // Calculate personal best (max no-spend gap between purchase days).
        var personalBest = currentStreak
        for (index in 1 until purchaseDays.size) {
            val gapDays = TimePeriodUtils.daysBetween(purchaseDays[index - 1], purchaseDays[index]) - 1
            if (gapDays > personalBest) {
                personalBest = gapDays
            }
        }
        
        // Month metric should only include elapsed days (not future days).
        val expenseDaysThisMonth = purchaseDays.count { it >= startOfMonth && it <= todayStart }
        val elapsedDaysThisMonth = dayOfMonth.coerceAtLeast(0)
        val daysWithoutSpendingThisMonth = (elapsedDaysThisMonth - expenseDaysThisMonth).coerceAtLeast(0)
        
        return Triple(currentStreak, personalBest.coerceAtLeast(currentStreak), daysWithoutSpendingThisMonth)
    }
    
    /**
     * Calculates consecutive days without spending for a specific period.
     */
    private fun calculateStreakForPeriod(
        expenses: List<DashboardExpense>,
        periodStart: Long,
        periodEnd: Long
    ): Int {
        val periodStartDay = TimePeriodUtils.getStartOfDay(periodStart)
        val today = TimePeriodUtils.getStartOfDay(periodEnd)
        if (today < periodStartDay) return 0

        val elapsedDays = TimePeriodUtils.daysBetween(periodStartDay, today).coerceAtLeast(0) + 1

        val expenseDays = expenses
            .asSequence()
            .filter {
                it.transactionType == DashboardTransactionType.PURCHASE &&
                    !it.isNotMine &&
                    it.date >= periodStart &&
                    it.date < periodEnd
            }
            .map { TimePeriodUtils.getStartOfDay(it.date) }
            .toSet()

        // No expenses in elapsed period => max possible no-spend streak.
        if (expenseDays.isEmpty()) return elapsedDays

        var streak = 0
        var checkDay = today
        val oneDayMs = TimePeriodUtils.DAY_IN_MILLIS
        while (checkDay >= periodStartDay && !expenseDays.contains(checkDay)) {
            streak++
            checkDay -= oneDayMs
        }
        
        return streak
    }



}
