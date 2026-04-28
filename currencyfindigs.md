Complete Report: How Money/Currency Is Handled Across the ExpenseTracker Codebase
Table of Contents
1. Entity-Level Currency Fields
2. The effectiveAmount Concept
3. The EFFECTIVE_AMOUNT_SQL SQL Constant and Its Usage
4. Currency Infrastructure (Converter, Settings, Rates, Store)
5. The Money Value Class
6. MultiCurrencyRepository -- The Only Currency-Aware Aggregation Path
7. Dashboard Pipeline -- Raw Double Summation
8. Budget Pipeline -- Raw Double Summation, No Currency
9. Analytics Pipeline -- Raw Double Summation
10. Forecasting/Stress Pipeline -- Raw Double Summation
11. Health Score Pipeline -- Raw Double Summation
12. Savings/Investment Pipeline -- No Currency Conversion
13. Groups/Shared Expenses -- Currency Per-Entity, No Conversion on Aggregation
14. Export Pipeline -- Per-Currency Grouping in PDF, Raw Elsewhere
15. UI Layer -- CurrencyFormatter Defaults to EUR
16. Hardcoded "EUR" Locations
17. Critical Gap Summary
18. Full File Reference Index
---
1. Entity-Level Currency Fields
Entities WITH a currency field:
Entity	File	Field	Default
Expense	data/database/entity/Expense.kt:55	currency: String = "EUR"	EUR (with @ColumnInfo(defaultValue = "EUR"))
ExchangeRate	data/database/entity/ExchangeRate.kt:23-24	fromCurrency: String, toCurrency: String	N/A (required)
Investment	data/database/entity/Investment.kt:25	currency: String = "EUR"	EUR
ManualRecurringExpense	data/database/entity/ManualRecurringExpense.kt:22	currency: String = "EUR"	EUR
SubscriptionCandidate	data/database/entity/SubscriptionCandidate.kt:34	currency: String = "EUR"	EUR
GroupExpense	data/database/entity/GroupExpense.kt:51	currency: String = "EUR"	EUR
ExpenseGroup	data/database/entity/ExpenseGroup.kt:24	defaultCurrency: String = "EUR"	EUR
Entities WITHOUT any currency field (raw amount: Double only):
Entity	File	Amount Fields
Budget	data/database/entity/Budget.kt:42	amount: Double
PlannedExpense	data/database/entity/PlannedExpense.kt:28	amount: Double
SavingsGoal	data/database/entity/SavingsGoal.kt:12-13	targetAmount: Double, currentAmount: Double
SavingsSweepPlan	data/database/entity/SavingsSweepPlan.kt:45-54	totalUnderspend, riskBuffer, safeSweepAmount, allocatedAmount
SpendingChallengeEntity	data/database/entity/SpendingChallengeEntity.kt:33-37	targetAmount: Double?, baselineAmount: Double?
BudgetForecast	data/database/entity/BudgetForecast.kt:39-40	predictedSpending, predictedRemaining
BudgetAdjustmentRecommendation	data/database/entity/BudgetAdjustmentRecommendation.kt	suggestedAmount: Double
AnomalyAlert	data/database/entity/AnomalyAlert.kt:44	amount: Double
StressForecastSnapshot	data/database/entity/StressForecastSnapshot.kt	Multiple Double fields (balances, obligations, income, buffers)
HealthScoreHistory	data/database/entity/HealthScoreHistory.kt	Weight Doubles only (no monetary amounts)
ReturnWindow	data/database/entity/ReturnWindow.kt	No amount field
Warranty	data/database/entity/Warranty.kt	No amount field
Key finding: Budget, PlannedExpense, and SavingsGoal -- the three main "target/limit" entities -- have no currency field at all. Their amounts are bare Doubles.
---
2. The effectiveAmount Concept
File: data/database/entity/Expense.kt:125-131
val effectiveAmount: Double
    get() = when {
        isNotMine -> 0.0
        isSharedExpense && myShareAmount != null -> myShareAmount
        isSharedExpense && mySharePercentage != null -> amount * mySharePercentage / 100.0
        else -> amount
    }
This is an ownership-adjusted amount. It handles:
- isNotMine expenses (excluded entirely)
- Shared expense splits (myShareAmount or proportional percentage)
- Regular full-ownership expenses
CRITICAL: It does NOT consider currency. An expense of 100 USD and an expense of 100 EUR both have effectiveAmount = 100.0, but they represent very different real-world values.
This same concept is mirrored in SQL as EFFECTIVE_AMOUNT_SQL and used in 39+ DAO query methods for SUM(...) aggregations.
---
3. The EFFECTIVE_AMOUNT_SQL SQL Constant
File: data/database/dao/ExpenseDao.kt:68-79
const val EFFECTIVE_AMOUNT_SQL: String =
    "CASE WHEN isNotMine = 1 THEN 0.0 " +
    "WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount " +
    "WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0 " +
    "ELSE amount END"
And its aliased variant:
const val EFFECTIVE_AMOUNT_E_SQL: String =
    "CASE WHEN e.isNotMine = 1 THEN 0.0 " +
    "WHEN e.isSharedExpense = 1 AND e.myShareAmount IS NOT NULL THEN e.myShareAmount " +
    "WHEN e.isSharedExpense = 1 AND e.mySharePercentage IS NOT NULL THEN e.amount * e.mySharePercentage / 100.0 " +
    "ELSE e.amount END"
Used in 39 distinct SUM(...) SQL queries in ExpenseDao (lines 247, 778, 787, 796, 920, 939, 961, 990, 1011, 1031, 1054, 1072, 1093, 1110, 1127, 1144, 1162, 1176, 1188, 1210, 1226, 1244, 1261, 1284, 1306, 1367, 1384, 1407, 1427, 1432, 1442, 1536, 1630, 1645, 1660, 1673, 1685, 1719, 1728, 1742).
NONE of these queries include any currency conversion logic. They all produce raw Double sums across mixed currencies.
Additionally, there are 107+ Kotlin-side .sumOf { it.effectiveAmount } calls across production code and tests, all performing raw Double addition without currency awareness.
---
4. Currency Infrastructure
The codebase has a complete and well-designed currency conversion infrastructure that is barely used by the main aggregation pipelines.
4.1 CurrencyConverter (Singleton)
File: domain/currency/CurrencyConverter.kt (254 lines)
- SupportedCurrency enum: 18 currencies (EUR, USD, GBP, JPY, CHF, CAD, AUD, SEK, NOK, DKK, PLN, CZK, HUF, RON, BGN, HRK, ISK)
- convert(amount, fromCurrency, toCurrency): Tries direct rate, falls back to EUR-as-intermediate. Returns ConversionResult? (null if no rate).
- convertMultiple(amounts: List<Pair<Double, String>>, targetCurrency): Converts a list of (amount, currency) pairs to a single target. Failed conversions are NOT added to the total -- strict semantics.
- storeRate() / storeRates(): Persists rates via ExchangeRateStore.
- formatAmount(amount, currencyCode): Basic symbol + amount formatting.
- DEFAULT_BASE_CURRENCY = "EUR"
4.2 CurrencySettingsRepository (DataStore-backed)
File: domain/currency/CurrencySettingsRepository.kt (41 lines, interface)
Impl: data/repository/CurrencySettingsRepositoryImpl.kt (69 lines)
- homeCurrency(): Flow<String> -- persisted in DataStore, defaults to "EUR"
- setHomeCurrency(currencyCode) -- user can change
- lastRateUpdate(), setLastRateUpdate() -- tracks freshness
- areRatesStale(thresholdMs = 24h) -- staleness check
- clear() -- reset all settings
4.3 CurrencyRatesRepository (ECB XML feed)
File: domain/currency/CurrencyRatesRepository.kt (14 lines, interface)
Impl: data/repository/CurrencyRatesRepositoryImpl.kt (113 lines)
- refresh(homeCurrency: String): Int -- fetches ECB daily XML, computes all cross-rates for 20 priority currencies, stores via CurrencyConverter.storeRates()
- URL: https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml
- Secure XML parsing (XXE prevention)
4.4 ExchangeRateStore (Domain port)
File: domain/currency/ExchangeRateContracts.kt (26 lines)
Adapter: data/currency/ExchangeRateStoreAdapter.kt (62 lines)
- Port interface: getRate(), insertOrUpdate(), insertOrUpdateAll(), getAllRatesForBase(), getLatestRate(), deleteOldRates()
- Adapter bridges to ExchangeRateDao (Room DAO, 39 lines)
4.5 DomainExchangeRate
File: domain/currency/ExchangeRateContracts.kt:8-14
data class DomainExchangeRate(
    val fromCurrency: String,
    val toCurrency: String,
    val rate: Double,
    val lastUpdated: Long = System.currentTimeMillis(),
    val source: String = "manual"
)
4.6 DI Wiring
File: di/CurrencyModule.kt (39 lines)
All three interfaces are bound via Hilt @Binds:
- CurrencySettingsRepository <- CurrencySettingsRepositoryImpl
- CurrencyRatesRepository <- CurrencyRatesRepositoryImpl
- ExchangeRateStore <- ExchangeRateStoreAdapter
CurrencyConverter and MultiCurrencyRepository are @Inject constructor-based, no explicit provider needed.
4.7 CurrencyManagementScreen / ViewModel
File: ui/screens/currency/CurrencyManagementScreen.kt (793 lines)
File: ui/screens/currency/CurrencyManagementViewModel.kt (254 lines)
- Full UI for viewing rates, setting home currency, refreshing rates, and doing manual conversions
- Properly reads homeCurrency from CurrencySettingsRepository
- Calls currencyRatesRepository.refresh() for rate updates
- Uses CurrencyConverter.convert() for manual test conversions
This screen is the only place in the app that actually uses the currency infrastructure end-to-end.
---
5. The Money Value Class
File: domain/util/Money.kt (165 lines)
@JvmInline
value class Money(val amount: BigDecimal) { ... }
- Currency-unaware: wraps BigDecimal only, no currency code
- Provides arithmetic: +, -, *, divide(), percentage()
- 2 decimal places, HALF_UP rounding
- Extension functions: Double.toMoney(), String.toMoney(), Iterable<Money>.sum()
- Not used anywhere in the main aggregation pipelines (dashboard, budget, analytics, forecasting all use raw Double)
---
6. MultiCurrencyRepository -- The Only Currency-Aware Aggregation Path
File: data/repository/MultiCurrencyRepository.kt (413 lines)
This is the only repository that properly converts between currencies when aggregating. It provides:
Method	What It Does
getTotalExpensesInHomeCurrency(start, end, homeCurrency)	Groups expenses by currency, sums per-currency totals via SQL, then converts each to home currency
getExpensesByCurrency(start, end)	Returns Map<String, Double> of per-currency totals (no conversion)
getExpensesWithConversion(start, end, homeCurrency)	Per-row conversion of effectiveAmount to home currency, returns List<ConvertedExpense>
getCategoryTotalsInHomeCurrency(start, end, homeCurrency)	Per-category, per-currency grouping, then conversion
getMerchantTotalsInHomeCurrency(start, end, homeCurrency)	Per-merchant, per-currency grouping, then conversion
getMonthlyTotalsInHomeCurrency(start, end, homeCurrency)	Per-month, per-currency grouping, then conversion
Supporting types defined in the same file:
data class ConvertedExpense(
    val expense: Expense,
    val homeCurrencyAmount: Double?,
    val conversionRate: Double?,
    val homeCurrency: String,
    val conversionWarning: String?
)
data class MonthTotal(
    val monthKey: String,
    val total: Double,
    val homeCurrency: String,
    val failedConversions: List<FailedConversion>
)
CRITICAL FINDING: MultiCurrencyRepository is NOT imported or used by ANY other production code. A grep for import.*MultiCurrencyRepository or multiCurrencyRepo across the entire codebase returns zero results (other than its own file and the DI module comment). The dashboard, budget, analytics, and forecast pipelines all bypass it entirely and use raw SUM(EFFECTIVE_AMOUNT_SQL) instead.
The DAO helpers that MultiCurrencyRepository depends on (getAllSpentBetweenByCurrency, getAllCategoryTotalsBetweenByCurrency, etc.) do exist in ExpenseDao (lines 1042-1940) and properly group by UPPER(currency), but they are only called from this unused repository.
---
7. Dashboard Pipeline -- Raw Double Summation
ComputeDashboardWidgetsUseCase
File: domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt (863 lines)
- Line 315: todaySpent = todayPurchases.sumOf { it.effectiveAmount } -- raw sum
- Line 317: weekSpent = purchases.filter { it.date >= weekStart }.sumOf { it.effectiveAmount } -- raw sum
- Line 376: monthlyIncome = ctx.deposits.filter { it.date >= ctx.monthStart }.sumOf { it.effectiveAmount } -- raw sum
- Line 342: currency = "EUR" -- HARDCODED in ExpenseSnapshot creation for forecast input
- Line 469: val spentToDate = runwayResult.purchasesThisMonth.sumOf { it.effectiveAmount } -- raw sum
DashboardExpense (domain model)
File: domain/model/dashboard/DashboardPrimitives.kt:3-13
data class DashboardExpense(
    val id: Long,
    val amount: Double,
    val effectiveAmount: Double,
    val merchant: String,
    val transactionType: DashboardTransactionType,
    val date: Long,
    val categoryId: Long?,
    val isNotMine: Boolean,
    val isManualEntry: Boolean
)
No currency field. Currency information is lost when Expense entities are mapped to DashboardExpense.
SpendingSummary (domain model)
File: domain/model/dashboard/SpendingSummary.kt
data class SpendingSummary(
    val totalSpent: Double,     // no currency field
    val previousTotalSpent: Double?,
    val changePercent: Double?,
    val dailyHistory: List<Double>,
    val previousDailyHistory: List<Double>,
    val transactionCount: Int
)
ExpenseSnapshot (domain model)
File: domain/model/ExpenseSnapshot.kt
data class ExpenseSnapshot(
    val id: Long,
    val amount: Double,
    val effectiveAmount: Double,
    val currency: String,       // <-- HAS a currency field!
    val merchant: String,
    ...)
Has a currency field, but it is hardcoded to "EUR" in ComputeDashboardWidgetsUseCase:342. The field exists but is never populated from the actual expense's currency.
AnalyticsRepository.getSpendingSummary
File: data/repository/AnalyticsRepository.kt:52-93
- totalSpent = expenseDao.getTotalSpentBetween(start, end) -- raw SUM(EFFECTIVE_AMOUNT_SQL), no currency conversion
- previousTotal = expenseDao.getTotalSpentBetween(previousStart, previousEnd) -- same
- Daily history also from raw sums
- SpendingSummary.totalSpent is a bare Double
Dashboard UI widgets
File: ui/screens/home/HomeScreen.kt:487-489
StatLabel(stringResource(R.string.widget_today), CurrencyFormatter.format(widget.todaySpent), ...)
StatLabel(stringResource(R.string.widget_week), CurrencyFormatter.format(widget.weekSpent), ...)
StatLabel(stringResource(R.string.widget_month), CurrencyFormatter.format(widget.monthSpent), ...)
CurrencyFormatter.format() is called without a currencyCode argument, defaulting to EUR.
---
8. Budget Pipeline -- Raw Double Summation, No Currency
BudgetRepository
File: data/repository/BudgetRepository.kt:105-179
- createBudgetStatus(): spent = getAggregateSpent(budget.categoryId, window.start, window.end)
- getAggregateSpent() (line 173-178): delegates to expenseDao.getCategorySpentInPeriod() or expenseDao.getTotalForPeriod() -- both use SUM(EFFECTIVE_AMOUNT_SQL) with no currency conversion
- budget.amount is a bare Double -- the Budget entity has no currency field
- spentAmount, remainingAmount, percentUsed in BudgetStatus are all bare Doubles
BudgetStatus
File: domain/budget/BudgetModels.kt:6-16
data class BudgetStatus(
    val budget: Budget,
    val category: Category?,
    val spentAmount: Double,       // raw sum across currencies
    val remainingAmount: Double,
    val percentUsed: Float,
    val healthStatus: BudgetHealthStatus,
    val periodStart: Long,
    val periodEnd: Long,
    val adjustedSpendBreakdown: AdjustedSpendBreakdown? = null
)
No currency field anywhere in the budget pipeline.
Rollover calculation
File: data/repository/BudgetRepository.kt:120-141
var effectiveLimit = budget.amount
for (period in periods) {
    val spentInPeriod = getAggregateSpent(budget.categoryId, period.start, period.end)
    val surplus = (effectiveLimit - spentInPeriod).coerceAtLeast(0.0)
    effectiveLimit = budget.amount + surplus
}
All raw Double arithmetic -- cross-currency expenses would silently produce wrong surplus/deficit figures.
---
9. Analytics Pipeline -- Raw Double Summation
AdvancedAnalyticsDashboard
File: domain/analytics/AdvancedAnalyticsDashboard.kt:101-111
for (expense in expenses) {
    when (expense.transactionType) {
        DomainTransactionType.PURCHASE, DomainTransactionType.WITHDRAWAL -> totalSpent += expense.effectiveAmount
        DomainTransactionType.DEPOSIT -> totalIncome += expense.effectiveAmount
        else -> Unit
    }
}
Raw effectiveAmount addition across all currencies. AnalyticsDashboardData.totalSpent, totalIncome, netCashflow are bare Doubles.
AdvancedAnalyticsEngine
File: domain/analytics/AdvancedAnalyticsEngine.kt
- Line 184: val previousTotal = previousByCategory[categoryId]?.sumOf { it.effectiveAmount } -- raw sum
- Line 348: val totalSpent = purchases.sumOf { it.effectiveAmount } -- raw sum
- Line 476: category-by-day sums: .mapValues { it.value.sumOf { e -> e.effectiveAmount } } -- raw sum
- Line 480: val totalAmount = purchases.sumOf { it.effectiveAmount } -- raw sum
- Lines 683-684: half-of-month comparisons -- raw sums
SpendingPersonalityClassifier
File: domain/analytics/SpendingPersonalityClassifier.kt
- Line 211: .sumOf { it.effectiveAmount } -- raw sum
- Line 213: val totalSpending = purchases.sumOf { it.effectiveAmount } -- raw sum
- Line 242: .mapValues { it.value.sumOf { e -> e.effectiveAmount } } -- raw sum
- Line 273: val categorySpending = categoryPurchases.sumOf { it.effectiveAmount } -- raw sum
CategoryInsightEngine
File: domain/analytics/CategoryInsightEngine.kt
- Line 48: val totalCurrent = currentExpenses.sumOf { it.effectiveAmount } -- raw sum
- Line 83: Pair(expenses.sumOf { it.effectiveAmount }, expenses.size) -- raw sum
- Line 89: val currentTotal = expenses.sumOf { it.effectiveAmount } -- raw sum
DayOfWeekAnalyzer, MonthlyComparisonCalculator, SpendingPaceCalculator
All use .sumOf { it.effectiveAmount } -- raw Double addition.
TotalsAggregationEngine
File: domain/analytics/TotalsAggregationEngine.kt
Delegates to ExpenseRepository.getMonthlyTotalsForPeriod() / getWeeklyTotalsForPeriod(), which call DAO methods using SUM(EFFECTIVE_AMOUNT_SQL). No currency conversion.
---
10. Forecasting/Stress Pipeline -- Raw Double Summation
FinancialStressForecastEngine
File: domain/forecasting/FinancialStressForecastEngine.kt
- Line 44: private const val DEFAULT_EMERGENCY_BUFFER = 500.0 // EUR -- hardcoded EUR amount
- Line 226: val totalDeposits = deposits.sumOf { it.effectiveAmount } -- raw sum
- Line 289: .mapValues { (_, txns) -> txns.sumOf { it.effectiveAmount } } -- raw sum
- StressForecastSnapshot entity stores all balances/obligations/income as bare Doubles with no currency
ForecastInputAssembler
File: domain/forecasting/ForecastInputAssembler.kt
- Line 235: .map { monthExpenses -> monthExpenses.sumOf { it.effectiveAmount } } -- raw sum
- Line 330: .sumOf { it.effectiveAmount } -- raw sum
HistoricalSpendingDistribution
File: domain/forecasting/HistoricalSpendingDistribution.kt
- Line 143: val total = weekExpenses.sumOf { it.effectiveAmount } -- raw sum
MonteCarloSpendingSimulator
Works on Doubles passed in from the assembler. No currency awareness.
---
11. Health Score Pipeline -- Raw Double Summation
FinancialHealthCalculator
File: domain/health/FinancialHealthCalculator.kt
- Line 98: val spentToday = todaySpending.sumOf { it.effectiveAmount } -- raw sum
- Line 146: val spentThisWeek = weekSpending.sumOf { it.effectiveAmount } -- raw sum
- Line 199: val spentThisMonth = monthSpending.sumOf { it.effectiveAmount } -- raw sum
FinancialHealthScoreV2
File: domain/health/FinancialHealthScoreV2.kt
- Line 212: val totalIncome = deposits.sumOf { it.effectiveAmount } -- raw sum
- Line 213: val totalExpenses = purchases.sumOf { it.effectiveAmount } -- raw sum
- Line 258: .sumOf { it.effectiveAmount } -- raw sum
- Line 331: .map { monthRows -> monthRows.sumOf { it.effectiveAmount } } -- raw sum
---
12. Savings/Investment Pipeline -- No Currency Conversion
SmartSavingsEngine
File: domain/savings/SmartSavingsEngine.kt
- Line 222: .sumOf { it.effectiveAmount } -- raw sum
- Line 248: val totalHistorySpending = historyExpenses.sumOf { it.effectiveAmount } -- raw sum
- Line 277: val monthSpentToDate = monthPurchases.sumOf { it.effectiveAmount } -- raw sum
- Line 303: historicalPurchases.sumOf { it.effectiveAmount } / ... -- raw sum
- Line 520: val spentToDate = purchases.sumOf { it.effectiveAmount } -- raw sum
SavingsGoal entity
No currency field. targetAmount and currentAmount are bare Doubles.
Investment entity
Has a currency field (defaults to "EUR"), but InvestmentTracker likely does raw sums of purchasePrice, currentPrice etc. without conversion.
MonthlySavingsSweepUseCase
File: domain/usecase/savings/MonthlySavingsSweepUseCase.kt
- Line 215: .sumOf { it.effectiveAmount } -- raw sum
CarbonFootprintCalculator
File: domain/carbon/CarbonFootprintCalculator.kt
- Line 433: val total = monthExpenses.sumOf { it.effectiveAmount * getEmissionFactor(it) } -- raw sum (currency does not affect emission factors, but the amount magnitude is currency-dependent)
---
13. Groups/Shared Expenses -- Currency Per-Entity, No Conversion on Aggregation
SharedExpenseBudgetOffsetEngine
File: domain/groups/SharedExpenseBudgetOffsetEngine.kt
- Line 76: val totalPersonalSpend = personalExpenses.sumOf { it.effectiveAmount } -- raw sum
SharedExpenseManager
File: domain/groups/SharedExpenseManager.kt
- Line 44: defaultCurrency: String = "EUR" -- hardcoded default
- Line 128: currency: String = "EUR" -- hardcoded default
SharedExpensePort (domain models)
File: domain/groups/SharedExpensePort.kt
- SharedExpenseGroup.defaultCurrency: String = "EUR"
- SharedGroupExpense.currency: String = "EUR"
GroupExpense entity
Has currency: String = "EUR" and totalAmount: Double. But settlement calculations in SettlementCalculator likely sum raw amounts without conversion.
---
14. Export Pipeline -- Per-Currency Grouping in PDF, Raw Elsewhere
AccountantReportPdfExporter
File: domain/export/AccountantReportExporter.kt
- Line 30: val expensesByCurrency = expenses.groupBy { it.reportCurrencyCode() } -- groups by currency
- Line 42: Per-currency totals are displayed correctly in the PDF
- Line 102-104: reportCurrencyCode() extracts the expense's actual currency code
- This is the ONLY place in the codebase that groups amounts by currency before displaying, avoiding cross-currency addition
ExportTransaction
File: domain/export/ExportTransaction.kt:15
val currency: String = "EUR"
Defaults to EUR but carries the field.
---
15. UI Layer -- CurrencyFormatter Defaults to EUR
File: domain/util/CurrencyFormatter.kt (61 lines)
object CurrencyFormatter {
    private const val DEFAULT_CURRENCY = "EUR"
    
    fun format(amount: Double, currencyCode: String = DEFAULT_CURRENCY, ...): String
    fun formatCompact(amount: Double, currencyCode: String = DEFAULT_CURRENCY): String
    fun formatWithSign(amount: Double, currencyCode: String = DEFAULT_CURRENCY): String
}
fun Double.toCurrency(currencyCode: String = "EUR"): String = CurrencyFormatter.format(this, currencyCode)
Most UI call sites invoke CurrencyFormatter.format(amount) without specifying a currencyCode, silently defaulting to EUR:
- HomeScreen.kt -- dashboard widgets (today/week/month spent)
- FinancialStressForecastCard.kt -- stress horizon balances
- FinancialWeatherCard.kt -- weather amounts
- SavingsGoalsScreen.kt -- goal amounts, sweep amounts
- SpendingChallengesScreen.kt -- challenge amounts
- AnalyticsScreen.kt -- many analytics amounts
- CashFlowCalendarScreen.kt -- balance/income/expenses
- MoneyRadarWidget.kt -- radar amounts
Few call sites do pass a currency code:
- ReceiptScanScreen.kt -- uses parsed?.currency ?: "EUR"
- AnalyticsViewModel.kt -- passes currency from state
- UiTextExtensions.kt -- uses arg.currency from UiTextArg.Money
- TransactionsScreen.kt -- uses CurrencyConverter.DEFAULT_BASE_CURRENCY
UiTextArg.Money
File: domain/text/UiTextArg.kt:4-8
data class Money(
    val amount: Double,
    val currency: String? = null,    // nullable! defaults to null
    val showCents: Boolean = true
) : UiTextArg
When currency is null, UiTextExtensions.kt:72-74 falls back to CurrencyFormatter.format(arg.amount, showCents = arg.showCents) -- which defaults to EUR.
---
16. Hardcoded "EUR" Locations (Domain Layer)
File	Line	Code	Context
ComputeDashboardWidgetsUseCase.kt	342	currency = "EUR"	ExpenseSnapshot creation
FinancialStressForecastEngine.kt	44	DEFAULT_EMERGENCY_BUFFER = 500.0 // EUR	Emergency buffer constant
CurrencyConverter.kt	70	DEFAULT_BASE_CURRENCY = "EUR"	Converter default
CurrencyFormatter.kt	12	DEFAULT_CURRENCY = "EUR"	Formatter default
CurrencyFormatter.kt	60	fun Double.toCurrency(currencyCode: String = "EUR")	Extension default
CurrencyNormalizer.kt	16	return "EUR"	Unknown currency fallback
DuplicateDetectionPolicy.kt	31	DEFAULT_CURRENCY = "EUR"	Dedupe default
AmountExtractionUtils.kt	26	?: "EUR"	Amount extraction fallback
ExportTransaction.kt	15	currency: String = "EUR"	Export DTO default
SharedExpensePort.kt	12	defaultCurrency: String = "EUR"	Group default
SharedExpensePort.kt	51	currency: String = "EUR"	Group expense default
SharedExpenseManager.kt	44, 128	"EUR"	Manager defaults
GreekBankParser.kt	155, 186, 249	var currency = "EUR"	Parser defaults
BankStatementParser.kt	309, 454, 607	currency = "EUR"	Parser defaults
ReceiptParser.kt	692-702	"EUR"	Receipt currency detection default
TaxConfiguration.kt	46	getCurrency() = "EUR"	Tax config override
BankApiIntegration.kt	299	currency = "EUR"	Bank API stub
CurrencySettingsRepositoryImpl.kt	31	DEFAULT_CURRENCY = "EUR"	DataStore default
MultiCurrencyRepository.kt	33	DEFAULT_HOME_CURRENCY = "EUR"	Repository default
AddExpenseViewModel.kt	353	currency = "EUR"	New expense default
SharedExpenseGroupsViewModel.kt	216	?: "EUR"	Group fallback
---
17. Critical Gap Summary
The Core Problem
The codebase has a complete currency infrastructure (exchange rate storage, ECB rate fetching, a CurrencyConverter with proper cross-rate resolution, a CurrencySettingsRepository for the user's home currency, and a MultiCurrencyRepository that does proper currency-aware aggregation) -- but almost none of it is wired into the main data pipelines.
Pipeline	Uses Currency Conversion?	What It Does Instead
Dashboard totals (today/week/month spent)	NO	Raw SUM(EFFECTIVE_AMOUNT_SQL) across all currencies
Budget spent tracking	NO	Raw SUM(EFFECTIVE_AMOUNT_SQL) compared to bare-Double budget amount
Budget rollover	NO	Raw surplus/deficit calculations across mixed currencies
Analytics (all engines)	NO	sumOf { it.effectiveAmount } -- raw Double addition
Spending personality	NO	Raw sums
Forecast / stress	NO	Raw sums; hardcoded EUR buffer constant
Health scores	NO	Raw sums
Savings sweep	NO	Raw sums
Block party / safe-to-spend	NO	Raw sums
AI financial queries	NO	Raw sums (with per-currency grouping in output only)
Export (PDF)	PARTIAL	Groups by currency for display, but no conversion
Currency Management screen	YES	Full conversion, but isolated to this screen
MultiCurrencyRepository	YES	Proper conversion, but unused by any other code
What This Means for a User with Multi-Currency Expenses
If a user has:
- 50 EUR grocery expense
- 100 USD electronics purchase (approx 92 EUR)
The dashboard shows "142 EUR" of spending (50 + 92), which is correct only if the USD expense was converted. But currently it shows 150 (50 + 100 raw), because 100 USD effectiveAmount is 100.0 and it is summed directly with the 50.0 EUR effectiveAmount without any conversion.
The budget pipeline compares this raw 150 against a budget of (say) 200 EUR, which would show 75% used. The correct figure is 142/200 = 71%. The error grows with the number and magnitude of foreign-currency transactions.
Entity Currency Field Gaps
Entity	Has Currency?	Should Have Currency?
Budget	NO	YES -- budget limits need a currency
PlannedExpense	NO	YES -- planned amounts need a currency
SavingsGoal	NO	YES -- target/current amounts need a currency
SpendingChallengeEntity	NO	PROBABLY -- target amounts should be currency-qualified
BudgetForecast	NO	PROBABLY -- predicted spending should be currency-qualified
DashboardExpense	NO	YES -- needs to carry currency for downstream aggregation
SpendingSummary	NO	YES -- needs a currency field to indicate what currency totalSpent is in
PeriodTotal	NO	PROBABLY
CategoryBreakdown	NO	PROBABLY
BudgetStatus	NO	YES -- spentAmount and remainingAmount should be currency-qualified
Money Value Class Gap
The Money value class exists but is:
1. Currency-unaware (just wraps BigDecimal)
2. Not used in any aggregation pipeline (everything uses raw Double)
3. Not integrated with CurrencyConverter
A truly currency-safe design would either:
- Make Money carry a currency code and prevent cross-currency arithmetic, or
- Create a CurrencyAwareMoney that requires conversion before addition
---
18. Full File Reference Index
Entity files (with currency status)
- app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt -- HAS currency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/Budget.kt -- NO currency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/PlannedExpense.kt -- NO currency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/ExchangeRate.kt -- HAS fromCurrency/toCurrency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/Investment.kt -- HAS currency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/ManualRecurringExpense.kt -- HAS currency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt -- HAS currency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/GroupExpense.kt -- HAS currency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/ExpenseGroup.kt -- HAS defaultCurrency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/SavingsGoal.kt -- NO currency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/SavingsSweepPlan.kt -- NO currency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/SpendingChallengeEntity.kt -- NO currency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/BudgetForecast.kt -- NO currency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/AnomalyAlert.kt -- NO currency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/StressForecastSnapshot.kt -- NO currency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/HealthScoreHistory.kt -- NO currency
- app/src/main/java/com/yourname/expensetracker/data/database/entity/BudgetAdjustmentRecommendation.kt -- NO currency
Database / DAO
- app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt -- schema v93, migrations
- app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt -- EFFECTIVE_AMOUNT_SQL, 39 SUM queries
- app/src/main/java/com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt -- rate CRUD
Currency infrastructure
- app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt -- converter + SupportedCurrency enum
- app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencySettingsRepository.kt -- interface
- app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyRatesRepository.kt -- interface
- app/src/main/java/com/yourname/expensetracker/domain/currency/ExchangeRateContracts.kt -- DomainExchangeRate + ExchangeRateStore
- app/src/main/java/com/yourname/expensetracker/data/repository/CurrencySettingsRepositoryImpl.kt -- DataStore impl
- app/src/main/java/com/yourname/expensetracker/data/repository/CurrencyRatesRepositoryImpl.kt -- ECB XML impl
- app/src/main/java/com/yourname/expensetracker/data/currency/ExchangeRateStoreAdapter.kt -- Room adapter
- app/src/main/java/com/yourname/expensetracker/data/repository/CurrencyDataRepository.kt -- thin DAO wrapper
- app/src/main/java/com/yourname/expensetracker/di/CurrencyModule.kt -- Hilt bindings
Multi-currency repository
- app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt -- THE ONLY currency-aware aggregation (UNUSED)
Money type
- app/src/main/java/com/yourname/expensetracker/domain/util/Money.kt -- currency-unaware BigDecimal wrapper
- app/src/main/java/com/yourname/expensetracker/domain/util/CurrencyFormatter.kt -- formatting utility, defaults to EUR
- app/src/main/java/com/yourname/expensetracker/domain/util/CurrencyNormalizer.kt -- symbol-to-code normalization, defaults to EUR
- app/src/main/java/com/yourname/expensetracker/domain/util/AmountExtractionUtils.kt -- regex extraction, defaults to EUR
Dashboard pipeline
- app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt -- raw sums, hardcoded "EUR" in ExpenseSnapshot
- app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt -- data assembly
- app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/SpendingSummary.kt -- no currency field
- app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/DashboardPrimitives.kt -- DashboardExpense has no currency
- app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/DashboardExpenseMapper.kt -- loses currency in mapping
- app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt -- raw SQL sums
Budget pipeline
- app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt -- raw sums, no currency
- app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetModels.kt -- BudgetStatus has no currency
- app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetCalculator.kt -- period math
- app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetMonitor.kt -- notification thresholds
- app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngine.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetRecommendationEngine.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt -- raw sums
Analytics pipeline
- app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt -- raw sums (delegates to sub-engines)
- app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngine.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzer.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/analytics/MonthlyComparisonCalculator.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt -- raw SQL sums
- app/src/main/java/com/yourname/expensetracker/domain/analytics/AnomalyDetector.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/analytics/TransferDirectionAnalytics.kt -- raw sums
Forecasting pipeline
- app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt -- raw sums, hardcoded EUR buffer
- app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulator.kt -- operates on raw Doubles
Health pipeline
- app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculator.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt -- raw sums
Savings pipeline
- app/src/main/java/com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/savings/SavingsGamificationEngine.kt -- raw sums
- app/src/main/java/com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngine.kt -- raw sums
Groups/shared expenses
- app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpensePort.kt -- domain models with EUR defaults
- app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt -- EUR defaults
- app/src/main/java/com/yourname/expensetracker/domain/groups/SettlementCalculator.kt -- likely raw sums
- app/src/main/java/com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt -- group operations
Export
- app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt -- PARTIAL: groups by currency, no conversion
- app/src/main/java/com/yourname/expensetracker/domain/export/ExportTransaction.kt -- has currency field, defaults EUR
AI / Natural language
- app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt -- raw sums, per-currency grouping in output
UI text/display
- app/src/main/java/com/yourname/expensetracker/domain/text/UiTextArg.kt -- UiTextArg.Money with optional currency
- app/src/main/java/com/yourname/expensetracker/ui/components/UiTextExtensions.kt -- resolves UiTextArg.Money, falls back to EUR
UI screens (currency formatter usage)
- app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt -- format without currency
- app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt -- format without currency
- app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt -- format without currency
- app/src/main/java/com/yourname/expensetracker/ui/screens/cashflow/CashFlowCalendarScreen.kt -- format without currency
- app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsScreen.kt -- format without currency
- app/src/main/java/com/yourname/expensetracker/ui/screens/challenge/SpendingChallengesScreen.kt -- format without currency
- app/src/main/java/com/yourname/expensetracker/ui/screens/currency/CurrencyManagementScreen.kt -- FULL currency UI
- app/src/main/java/com/yourname/expensetracker/ui/screens/currency/CurrencyManagementViewModel.kt -- proper currency handling
- app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt -- passes parsed currency
- app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModel.kt -- defaults to EUR
- app/src/main/java/com/yourname/expensetracker/ui/components/FinancialStressForecastCard.kt -- format without currency
- app/src/main/java/com/yourname/expensetracker/ui/components/FinancialWeatherCard.kt -- format without currency
- app/src/main/java/com/yourname/expensetracker/ui/components/dashboard/MoneyRadarWidget.kt -- format without currency
Domain models (no currency field)
- app/src/main/java/com/yourname/expensetracker/domain/model/ExpenseSnapshot.kt -- HAS currency field (but hardcoded "EUR" in usage)
- app/src/main/java/com/yourname/expensetracker/domain/model/PeriodTotal.kt -- no currency
- app/src/main/java/com/yourname/expensetracker/domain/model/CategoryBreakdown.kt -- no currency
- app/src/main/java/com/yourname/expensetracker/domain/model/FinancialForecast.kt -- no currency in ForecastComponents
- app/src/main/java/com/yourname/expensetracker/domain/model/BudgetSnapshot.kt -- no currency
- app/src/main/java/com/yourname/expensetracker/domain/model/SavingsGoal.kt -- no currency
Parsers (EUR defaults)
- app/src/main/java/com/yourname/expensetracker/domain/parser/parsers/GreekBankParser.kt -- defaults to EUR
- app/src/main/java/com/yourname/expensetracker/domain/receipt/BankStatementParser.kt -- defaults to EUR
- app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt -- defaults to EUR for Greek receipts
▣  Explore · z-ai/glm-5.1 · 37m 44s
--- ## Goal The user wants a thorough research-only exploration of how money/currency is currently handled across the ExpenseTracker codebase at C:\Users\panos\Desktop\cost agregator\ExpenseTracker\. This is RESEARCH ONLY — no code should be written. The goal is to understand the current state before likely implementing currency-aware aggregation improvements. ## Instructions - RESEARCH ONLY — do not write any code - Document every entity, model, pipeline, and utility that touches money/currency - Identify where currency conversion is properly handled vs. where raw Double summation across mixed currencies produces incorrect results - Map the gap between the existing currency infrastructure and where it needs to be wired in - Provide line numbers and code snippets for every finding ## Discoveries ### Entity-Level Currency Fields Entities WITH a currency field: Expense (line 55, default "EUR"), ExchangeRate (fromCurrency/toCurrency), Investment (line 25, default "EUR"), ManualRecurringExpense (line 22, default "EUR"), SubscriptionCandidate (line 34, default "EUR"), GroupExpense (line 51, default "EUR"), ExpenseGroup (line 24, defaultCurrency "EUR") Entities WITHOUT any currency field (raw amount: Double only): Budget (line 42), PlannedExpense (line 28), SavingsGoal (lines 12-13: targetAmount/currentAmount), SavingsSweepPlan (lines 45-54), SpendingChallengeEntity (lines 33-37), BudgetForecast (lines 39-40), BudgetAdjustmentRecommendation, AnomalyAlert (line 44), StressForecastSnapshot (multiple Double balance fields) ### The effectiveAmount Concept Expense.kt lines 125-131: ownership-adjusted amount that handles isNotMine, shared splits (myShareAmount/mySharePercentage), and regular expenses. Does NOT consider currency. A 100 USD expense and 100 EUR expense both produce effectiveAmount = 100.0. ### EFFECTIVE_AMOUNT_SQL — The Core Problem ExpenseDao.kt lines 68-79: SQL constant mirroring the Kotlin effectiveAmount getter. Used in 39 distinct SUM(...) SQL queries in ExpenseDao (lines 247, 778, 787, 796, 920, 939, 961, 990, 1011, 1031, 1054, 1072, 1093, 1110, 1127, 1144, 1162, 1176, 1188, 1210, 1226, 1244, 1261, 1284, 1306, 1367, 1384, 1407, 1427, 1432, 1442, 1536, 1630, 1645, 1660, 1673, 1685, 1719, 1728, 1742). NONE include currency conversion. Additionally, 107+ Kotlin-side .sumOf { it.effectiveAmount } calls across production code and tests perform raw Double addition without currency awareness. ### Currency Infrastructure (Complete but Barely Used) - CurrencyConverter (domain/currency/CurrencyConverter.kt, 254 lines): Singleton with SupportedCurrency enum (18 currencies), convert() (direct rate then EUR-intermediate fallback), convertMultiple() (strict — failed conversions excluded from total), storeRate()/storeRates(). DEFAULT_BASE_CURRENCY = "EUR". - CurrencySettingsRepository (domain/currency/CurrencySettingsRepository.kt, 41 lines): DataStore-backed. homeCurrency(): Flow<String>, setHomeCurrency(), lastRateUpdate(), areRatesStale(). Default "EUR". - CurrencyRatesRepositoryImpl (data/repository/CurrencyRatesRepositoryImpl.kt, 113 lines): Fetches ECB daily XML, computes cross-rates for 20 priority currencies, stores via CurrencyConverter. - ExchangeRateStore (domain/currency/ExchangeRateContracts.kt, 26 lines): Domain port, implemented by ExchangeRateStoreAdapter (data/currency/ExchangeRateStoreAdapter.kt, 62 lines) bridging to Room DAO. - CurrencyModule (di/CurrencyModule.kt, 39 lines): Hilt bindings for all interfaces. - CurrencyManagementScreen/ViewModel (ui/screens/currency/): Full UI for viewing rates, setting home currency, refreshing, manual conversions. Only place in app using currency infrastructure end-to-end. ### Money Value Class — Currency-Unaware domain/util/Money.kt (165 lines): @JvmInline value class Money(val amount: BigDecimal) — wraps BigDecimal only, no currency code. Not used in any aggregation pipeline (everything uses raw Double). Not integrated with CurrencyConverter. ### MultiCurrencyRepository — The Only Currency-Aware Aggregation (UNUSED) data/repository/MultiCurrencyRepository.kt (413 lines): Provides getTotalExpensesInHomeCurrency(), getCategoryTotalsInHomeCurrency(), getMerchantTotalsInHomeCurrency(), getMonthlyTotalsInHomeCurrency(), getExpensesWithConversion(). Uses DAO helpers that group by UPPER(currency) then converts per-currency totals via CurrencyConverter.convertMultiple(). CRITICAL: A grep for import.*MultiCurrencyRepository across the entire codebase returns ZERO results (other than its own file). It is not used by dashboard, budget, analytics, forecast, or any other pipeline. ### Pipeline-by-Pipeline Findings | Pipeline | Currency Conversion? | What It Does | |---|---|---| | Dashboard (today/week/month) | NO | Raw sumOf { it.effectiveAmount } | | Budget spent tracking | NO | Raw SUM(EFFECTIVE_AMOUNT_SQL) vs bare-Double budget.amount | | Budget rollover | NO | Raw surplus/deficit across mixed currencies | | All analytics engines | NO | sumOf { it.effectiveAmount } — 20+ call sites | | Spending personality | NO | Raw sums | | Forecast/stress | NO | Raw sums + hardcoded DEFAULT_EMERGENCY_BUFFER = 500.0 // EUR | | Health scores | NO | Raw sums | | Savings sweep | NO | Raw sums | | Block party / safe-to-spend | NO | Raw sums | | AI financial queries | NO | Raw sums (per-currency grouping in output only) | | Export (PDF) | PARTIAL | Groups by currency for display, no conversion | | Currency Management screen | YES | Full conversion, isolated | | MultiCurrencyRepository | YES | Proper conversion, unused | ### Key Hardcoded "EUR" Locations in Domain - ComputeDashboardWidgetsUseCase.kt:342 — currency = "EUR" hardcoded in ExpenseSnapshot creation - FinancialStressForecastEngine.kt:44 — DEFAULT_EMERGENCY_BUFFER = 500.0 // EUR - CurrencyConverter.kt:70 — DEFAULT_BASE_CURRENCY = "EUR" - CurrencyFormatter.kt:12 — DEFAULT_CURRENCY = "EUR" - CurrencyNormalizer.kt:16 — returns "EUR" for unknown - DuplicateDetectionPolicy.kt:31 — DEFAULT_CURRENCY = "EUR" - AmountExtractionUtils.kt:26 — falls back to "EUR" - ExportTransaction.kt:15 — currency: String = "EUR" - SharedExpensePort.kt:12,51 — group/expense defaults "EUR" - SharedExpenseManager.kt:44,128 — "EUR" defaults - GreekBankParser.kt:155,186,249 — var currency = "EUR" - BankStatementParser.kt:309,454,607 — currency = "EUR" - TaxConfiguration.kt:46 — getCurrency() = "EUR" - AddExpenseViewModel.kt:353 — currency = "EUR" - 20+ more locations across parsers, formatters, and UI ### UI Layer: CurrencyFormatter Defaults to EUR CurrencyFormatter.format(amount) is called without a currencyCode in HomeScreen, AnalyticsScreen, BudgetScreen, CashFlowCalendarScreen, SavingsGoalsScreen, SpendingChallengesScreen, FinancialStressForecastCard, FinancialWeatherCard, MoneyRadarWidget — all silently default to EUR symbol. ### Domain Models Missing Currency Fields DashboardExpense (no currency), SpendingSummary (no currency), BudgetStatus (no currency on spentAmount/remainingAmount), PeriodTotal (no currency), CategoryBreakdown (no currency), DashboardCategoryBreakdown (no currency). ExpenseSnapshot HAS a currency: String field but it is hardcoded to "EUR" at ComputeDashboardWidgetsUseCase.kt:342. ### Concrete Bug Example A user with 50 EUR grocery + 100 USD electronics (~92 EUR) sees dashboard "150 EUR" instead of correct "142 EUR". Budget shows 75% used instead of 71%. Error grows with more foreign-currency transactions. ## Accomplished - ✅ Complete entity-by-entity currency field audit (all 20 entities) - ✅ EFFECTIVE_AMOUNT_SQL usage mapped: 39 SQL SUM queries + 107+ Kotlin sumOf calls - ✅ All 5 currency infrastructure components fully documented (Converter, Settings, Rates, Store, Module) - ✅ MultiCurrencyRepository identified as the only currency-aware aggregation path — confirmed UNUSED by any other code - ✅ Money value class confirmed as currency-unaware BigDecimal wrapper, not used in pipelines - ✅ Dashboard pipeline: raw sums at lines 315, 317, 376, 469; hardcoded "EUR" at line 342 - ✅ Budget pipeline: raw sums at BudgetRepository lines 173-178; Budget entity has no currency field - ✅ Analytics pipeline: 20+ raw sumOf sites across AdvancedAnalyticsEngine, SpendingPersonalityClassifier, CategoryInsightEngine, DayOfWeekAnalyzer, MonthlyComparisonCalculator, SpendingPaceCalculator, TotalsAggregationEngine - ✅ Forecasting pipeline: raw sums in FinancialStressForecastEngine (line 226, 289), ForecastInputAssembler (235, 330), HistoricalSpendingDistribution (143); hardcoded EUR buffer (line 44) - ✅ Health pipeline: raw sums in FinancialHealthCalculator (98, 146, 199), FinancialHealthScoreV2 (212, 213, 258, 331) - ✅ Savings pipeline: raw sums in SmartSavingsEngine (222, 248, 277, 303, 520), MonthlySavingsSweepUseCase (215) - ✅ Groups pipeline: SharedExpenseBudgetOffsetEngine (76), SharedExpenseManager defaults EUR - ✅ Export pipeline: AccountantReportPdfExporter PARTIALLY correct (groups by currency, no conversion) - ✅ UI layer: CurrencyFormatter defaults to EUR in 15 screens; UiTextArg.Money has nullable currency - ✅ 20+ hardcoded "EUR" locations catalogued with line numbers - ✅ No code changes made (research only, as instructed) - ❌ No implementation plan created yet for wiring currency conversion into pipelines ## Relevant files / directories ### Entity files - app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt (HAS currency, effectiveAmount ignores it) - app/src/main/java/com/yourname/expensetracker/data/database/entity/Budget.kt (NO currency) - app/src/main/java/com/yourname/expensetracker/data/database/entity/PlannedExpense.kt (NO currency) - app/src/main/java/com/yourname/expensetracker/data/database/entity/ExchangeRate.kt (HAS fromCurrency/toCurrency) - app/src/main/java/com/yourname/expensetracker/data/database/entity/Investment.kt (HAS currency) - app/src/main/java/com/yourname/expensetracker/data/database/entity/ManualRecurringExpense.kt (HAS currency) - app/src/main/java/com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt (HAS currency) - app/src/main/java/com/yourname/expensetracker/data/database/entity/GroupExpense.kt (HAS currency) - app/src/main/java/com/yourname/expensetracker/data/database/entity/ExpenseGroup.kt (HAS defaultCurrency) - app/src/main/java/com/yourname/expensetracker/data/database/entity/SavingsGoal.kt (NO currency) - app/src/main/java/com/yourname/expensetracker/data/database/entity/SavingsSweepPlan.kt (NO currency) - app/src/main/java/com/yourname/expensetracker/data/database/entity/SpendingChallengeEntity.kt (NO currency) - app/src/main/java/com/yourname/expensetracker/data/database/entity/BudgetForecast.kt (NO currency) - app/src/main/java/com/yourname/expensetracker/data/database/entity/AnomalyAlert.kt (NO currency) - app/src/main/java/com/yourname/expensetracker/data/database/entity/StressForecastSnapshot.kt (NO currency) ### Database / DAO - app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt (schema v93) - app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt (EFFECTIVE_AMOUNT_SQL, 39 SUM queries, type-agnostic aggregate helpers for MultiCurrencyRepository at lines 1042+) - app/src/main/java/com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt ### Currency infrastructure - app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt - app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencySettingsRepository.kt - app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyRatesRepository.kt - app/src/main/java/com/yourname/expensetracker/domain/currency/ExchangeRateContracts.kt - app/src/main/java/com/yourname/expensetracker/data/repository/CurrencySettingsRepositoryImpl.kt - app/src/main/java/com/yourname/expensetracker/data/repository/CurrencyRatesRepositoryImpl.kt - app/src/main/java/com/yourname/expensetracker/data/currency/ExchangeRateStoreAdapter.kt - app/src/main/java/com/yourname/expensetracker/data/repository/CurrencyDataRepository.kt - app/src/main/java/com/yourname/expensetracker/di/CurrencyModule.kt ### Multi-currency repository (UNUSED) - app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt ### Money type / formatters - app/src/main/java/com/yourname/expensetracker/domain/util/Money.kt - app/src/main/java/com/yourname/expensetracker/domain/util/CurrencyFormatter.kt - app/src/main/java/com/yourname/expensetracker/domain/util/CurrencyNormalizer.kt - app/src/main/java/com/yourname/expensetracker/domain/util/AmountExtractionUtils.kt - app/src/main/java/com/yourname/expensetracker/domain/text/UiTextArg.kt ### Dashboard pipeline - app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt (raw sums, hardcoded "EUR" line 342) - app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt - app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/SpendingSummary.kt (no currency) - app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/DashboardPrimitives.kt (DashboardExpense no currency) - app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/DashboardExpenseMapper.kt (loses currency) - app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt (raw SQL sums) ### Budget pipeline - app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt (raw sums, no currency) - app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetModels.kt (BudgetStatus no currency) - app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetCalculator.kt - app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt (raw sums) ### Analytics pipeline - app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt - app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt - app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt - app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt - app/src/main/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngine.kt - app/src/main/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzer.kt - app/src/main/java/com/yourname/expensetracker/domain/analytics/MonthlyComparisonCalculator.kt - app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt - app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt ### Forecasting pipeline - app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt - app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt - app/src/main/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt - app/src/main/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulator.kt ### Health pipeline - app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculator.kt - app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt ### Savings pipeline - app/src/main/java/com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt - app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt ### Groups/shared expenses - app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpensePort.kt - app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt ### Export - app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt - app/src/main/java/com/yourname/expensetracker/domain/export/ExportTransaction.kt ### Domain models - app/src/main/java/com/yourname/expensetracker/domain/model/ExpenseSnapshot.kt (HAS currency but hardcoded "EUR" in usage) - app/src/main/java/com/yourname/expensetracker/domain/model/PeriodTotal.kt (no currency) - app/src/main/java/com/yourname/expensetracker/domain/model/CategoryBreakdown.kt (no currency) - app/src/main/java/com/yourname/expensetracker/domain/model/FinancialForecast.kt (no currency in components) - app/src/main/java/com/yourname/expensetracker/domain/model/SavingsGoal.kt (no currency) ### UI screens - app/src/main/java/com/yourname/expensetracker/ui/screens/currency/CurrencyManagementScreen.kt - app/src/main/java/com/yourname/expensetracker/ui/screens/currency/CurrencyManagementViewModel.kt - app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt - app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt - app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt - app/src/main/java/com/yourname/expensetracker/ui/components/UiTextExtensions.kt - app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModel.kt ---