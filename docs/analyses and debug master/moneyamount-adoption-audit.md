# MoneyAmount/MoneyAggregate Adoption Audit

**Generated**: 2026-05-02
**Scope**: All `.kt` files in `app/src/main/java/com/yourname/expensetracker/`
**Total source files**: 775 `.kt` files across entire project, ~368 in `app/src/main/java/com/yourname/expensetracker/`

---

## 1. Approved Type APIs

### 1.1 MoneyAmount (`domain/core/money/MoneyAmount.kt`)
```kotlin
data class MoneyAmount(
    val amount: Double,          // raw Double internally (field kept for Room/JSON compat)
    val currency: CurrencyCode   // type-safe ISO 4217 currency code
)
```
**Operations**:
- `isZero()`, `isPositive()`, `isNegative()` — predicates
- `formatDisplay()` — format with currency symbol
- `plus(other)` — same-currency add (throws on mismatch)
- `minus(other)` — same-currency subtract (throws on mismatch)
- `times(factor: Double)` — scalar multiply
- `negate()`, `abs()` — sign/absolute
- `companion`: `ZERO_EUR`, `zero(currency)` factories

### 1.2 MoneyAggregate (`domain/core/money/MoneyAggregate.kt`)
```kotlin
data class MoneyAggregate(
    val displayAmount: Double,
    val displayCurrency: CurrencyCode,
    val sourceBuckets: List<MoneyBucket>,
    val conversionFailures: List<ConversionFailure>,
    val isPartial: Boolean,
    val warningMessage: String?
)
```
**Features**:
- `formatDisplay()` — format total with currency symbol
- `totalTransactionCount` — sum across buckets
- `failedTransactionCount` — count of failures
- `isSingleCurrency` — whether no conversion was needed
- `companion`: `singleCurrency()`, `empty()`, `partial()` factories

### 1.3 CurrencyCode (`domain/core/money/CurrencyCode.kt`)
- `@JvmInline value class CurrencyCode(val code: String)`
- 17 predefined constants (EUR, USD, GBP, JPY, CHF, CAD, AUD, SEK, NOK, DKK, PLN, CZK, HUF, RON, BGN, HRK, ISK)
- `ALL_SUPPORTED` set
- `parse(input: String?): CurrencyCode?` — safe parser
- `parseOr(input, fallback)` — parser with fallback
- `symbolFor(code)` — currency symbol lookup
- Extensions: `String?.toCurrencyCodeOrNull()`, `String?.toCurrencyCodeOr(fallback)`

### 1.4 ConvertedMoney (`domain/core/money/ConvertedMoney.kt`)
```kotlin
data class ConvertedMoney(
    val original: MoneyAmount,
    val convertedAmount: Double?,
    val convertedCurrency: CurrencyCode,
    val rateUsed: Double?,
    val rateTimestamp: Long?,
    val conversionStatus: ConversionStatus
)
```
- `converted`: `MoneyAmount?` — nullable converted result
- `isConverted`, `isFailed` — status checks
- `companion`: `success()`, `failed()`, `identity()`

### 1.5 MoneyBucket (`domain/core/money/MoneyBucket.kt`)
```kotlin
data class MoneyBucket(
    val currency: CurrencyCode,
    val amount: Double,
    val transactionCount: Int
)
```

### 1.6 ConversionFailure (`domain/core/money/ConversionFailure.kt`)
```kotlin
data class ConversionFailure(
    val originalAmount: MoneyAmount,
    val targetCurrency: CurrencyCode,
    val reason: FailureReason
)
```

### 1.7 CurrencyAssumption (`domain/core/money/CurrencyAssumption.kt`)
`enum` with values: `UNKNOWN`, `ASSUMED_HOME_CURRENCY`, `ASSUMED_LEGACY_EUR`, `USER_CONFIRMED`, `PARSED_FROM_SOURCE`

### 1.8 MoneyFormatUtils (`domain/core/money/MoneyFormatUtils.kt`)
```kotlin
fun MoneyAmount.formatMoney(showCents: Boolean = true): String
fun MoneyAmount.formatMoneyCompact(): String
fun MoneyAmount.formatMoneyWithSign(): String
```

### 1.9 MoneyMappers (`domain/core/money/MoneyMappers.kt`)
```kotlin
fun String?.toCurrencyCodeOrLegacyEur(): CurrencyCode
fun Expense.toEffectiveMoneyAmount(): MoneyAmount
fun Expense.toGrossMoneyAmount(): MoneyAmount
fun ConversionResult.toConvertedMoney(originalCurrency: CurrencyCode): ConvertedMoney
fun MultiConversionAggregate.toMoneyAggregate(sourceBuckets: List<MoneyBucket> = []): MoneyAggregate
fun FailedConversion.toConversionFailure(): ConversionFailure
```

### 1.10 Legacy Money value class (`domain/util/Money.kt`)
```kotlin
@JvmInline value class Money(val amount: BigDecimal)
```
Used only in `EnhancedSplitManager.kt` (for split precision). Has `toMoney()` extensions and `sum()`.

### 1.11 Legacy CurrencyFormatter (`domain/util/CurrencyFormatter.kt`)
```kotlin
object CurrencyFormatter {
    fun format(amount: Double, currencyCode: String = "EUR", showCents: Boolean = true): String  // DEPRECATED
    fun formatMoney(amount: Double, currencyCode: String, showCents: Boolean = true): String      // safe
    fun formatMoneyCompact(amount: Double, currencyCode: String): String                           // safe
    fun formatMoneyWithSign(amount: Double, currencyCode: String): String                         // safe
}
```

---

## 2. Current Adoption (Already Using MoneyAmount/MoneyAggregate)

Only **9 files** currently use `MoneyAmount` or `MoneyAggregate`:

| File | What it uses |
|---|---|
| `domain/core/money/MoneyAmount.kt` | Definition |
| `domain/core/money/MoneyAggregate.kt` | Definition |
| `domain/core/money/MoneyBucket.kt` | Definition |
| `domain/core/money/ConvertedMoney.kt` | Definition |
| `domain/core/money/ConversionFailure.kt` | Definition |
| `domain/core/money/CurrencyCode.kt` | Definition |
| `domain/core/money/CurrencyAssumption.kt` | Definition |
| `domain/core/money/MoneyFormatUtils.kt` | Extension functions |
| `domain/core/money/MoneyMappers.kt` | Mappers |
| `data/repository/MultiCurrencyRepository.kt` | Produces `MoneyAggregate` from DAO queries |
| `data/repository/BudgetRepository.kt` | Consumes `MoneyAggregate` from `MultiCurrencyRepository` |

**Adoption gap**: 0 domain engines, 0 ViewModels, 0 UI components use these types.

---

## 3. Raw Double Usage — By Layer

### 3a. Entity/DTO Layer — 32 files

Files in `data/database/entity/` and `domain/model/` that store financial amounts as raw `Double`:

#### Room Entities (all need DB migration):
| File | Fields | Has `currency: String`? |
|---|---|---|
| `Expense.kt` | `amount: Double`, `effectiveAmount: Double` | ✅ Yes |
| `Budget.kt` | `amount: Double` | ✅ Yes |
| `PlannedExpense.kt` | `amount: Double` | ✅ Yes |
| `GroupExpense.kt` | `totalAmount: Double` | ✅ Yes |
| `ManualRecurringExpense.kt` | `amount: Double` | ✅ Yes |
| `SplitTemplate.kt` | `amount: Double?` | ❌ No |
| `SubscriptionPriceHistory.kt` | `amount: Double` | ✅ Yes |
| `InvestmentValue.kt` | `price: Double`, `totalValue: Double` | ❌ No |
| `AnomalyAlert.kt` | `amount: Double` | ❌ No |
| `BudgetAdjustmentRecommendation.kt` | `currentBudget: Double`, `recommendedBudget: Double` | ✅ Yes |
| `BudgetForecast.kt` | `predictedSpending: Double` | ✅ Yes |
| `StressForecastSnapshot.kt` | (multiple Double fields) | ✅ Yes |
| `SavingsGoal.kt` | `targetAmount: Double`, `currentAmount: Double` | ✅ Yes |
| `SavingsSweepPlan.kt` | `allocatedAmount: Double` | ✅ Yes |
| `SpendingChallengeEntity.kt` | `targetAmount: Double`, `spentAmount: Double` | ✅ Yes |
| `SubscriptionCandidate.kt` | `amount: Double` | ✅ Yes |
| `ScannedReceipt.kt` | `total: Double?` | ✅ Yes |
| `Investment.kt` | `totalInvested: Double`, `currentValue: Double` | ✅ Yes |
| `ExchangeRate.kt` | `rate: Double` (this is OK — it's a rate not an amount) | ⚠️ N/A |

#### Domain Model DTOs:

| File | Fields | Has `currency: String`? |
|---|---|---|
| `ExpenseSnapshot.kt` | `amount: Double`, `effectiveAmount: Double` | ✅ Yes |
| `PlannedExpense.kt` | `amount: Double` | ❌ No |
| `PeriodTotal.kt` | `totalAmount: Double` | ❌ No |
| `BudgetSnapshot.kt` | `amount: Double` | ✅ Yes |
| `CategoryBreakdown.kt` | `total: Double` | ❌ No |
| `BlockPartyDay.kt` | `amount: Double` | ❌ No |
| `UpcomingItem.kt` | `amount: Double` | ❌ No |
| `SavingsGoal.kt` | `targetAmount: Double`, `currentAmount: Double` | ✅ Yes |
| `RecurringPattern.kt` | `averageAmount: Double` | ✅ Yes |
| `FinancialForecast.kt` | (multiple Double fields) | ❌ No |
| `MonteCarloBudgetImpact.kt` | `expectedOverrun: Double`, `probabilityOfOverrun: Double` | ⚠️ Overrun is Double but probability is % |
| `DashboardExpense` (`DashboardPrimitives.kt`) | `amount: Double`, `effectiveAmount: Double` | ✅ Yes |
| `SpendingSummary` (`SpendingSummary.kt`) | `totalSpent: Double`, `previousTotalSpent: Double`, `changePercent: Double`, `dailyHistory: List<Double>` | ✅ Yes |
| `BudgetStatusSnapshot.kt` | `budgetAmount: Double`, `spentAmount: Double`, `remainingAmount: Double`, `percentUsed: Double` | ❌ No |
| `DomainDayBudgetStatus.kt` | `actualSpent: Double`, `targetBudget: Double`, `baseTarget: Double`, `recurringImpact: Double`, `plannedImpact: Double` | ❌ No |
| `FinancialWeather.kt` | `totalCommitted: Double`, `totalLikely: Double`, `predictedDiscretionary: Double`, `discretionaryBudget: Double`, `pastSpendingPoints: List<Double>`, `projectedSpendingPoints: List<Double>` | ❌ No |
| `DomainTransactionFilter.kt` | `amountFrom: Double?`, `amountTo: Double?` | ❌ No |

**Subtotal**: 32 files, **24 have `currency: String`** alongside the Double amount.

### 3b. DAO/Repository Layer — 10+ files

#### DAO files with SQL SUM queries over amount columns:

| File | SQL query | Returns |
|---|---|---|
| `ExpenseDao.kt` | `SELECT SUM(${EFFECTIVE_AMOUNT_SQL}) ...` | Various `CategoryTotalResult`, `CurrencyTotal` etc. with `total: Double` |
| `ExpenseDao.kt` | `SELECT COALESCE(SUM(${EFFECTIVE_AMOUNT_SQL}), 0.0) ...` (deposits) | `Flow<Double>` |
| `SplitItemAssignmentDao.kt` | `SELECT SUM(assignedAmount) ...` | `Flow<Double?>` |
| `SavingsSweepPlanDao.kt` | `SELECT COALESCE(SUM(allocatedAmount), 0.0) ...` | `Flow<Double>` |
| `MileageTrackingDao.kt` | `SELECT SUM(distanceKm) ...`, `SELECT SUM(calculatedDeduction) ...` | `Flow<Double>` |
| `InvestmentDao.kt` | `SELECT SUM(currentPrice * quantity) ...` | `Flow<Double>` |
| `AppDatabase.kt` (migration) | `SUM(amount) as totalSpent` | Used in migration SQL |
| `BudgetDao.kt` | (likely has budget-related SUM queries) | |
| `SavingsGoalDao.kt` | (likely has savings-goal SUM queries) | |

#### DAO result types returning `total: Double`:
- `ExpenseDao.kt` has ~17 inline data classes with `val total: Double` (lines 1890-2044)
- `CurrencyTotal` — `val total: Double, val currency: String`
- `CategoryCurrencyTotal` — `val total: Double, val currency: String, val categoryId: Long`
- `MerchantCurrencyTotal` — `val total: Double, val currency: String, val merchant: String`
- `MonthlyCurrencyTotal` — `val total: Double, val currency: String, val monthKey: String`

#### Repository files processing Double amounts:

| File | Key patterns |
|---|---|
| `ExpenseRepository.kt` | Processes amounts from DAOs, passes raw Doubles |
| `MultiCurrencyRepository.kt` | ✅ ALREADY CONVERTED — produces `MoneyAggregate` |
| `BudgetRepository.kt` | ✅ ALREADY CONVERTED — consumes `MoneyAggregate` |
| `NotificationProcessingPipeline.kt` | `val amount: Double, val currency: String` (DTOs) |
| `ReviewQueueRepository.kt` | `val amount: Double = finalAmount ?: review.suggestedAmount` |
| `SavingsContributionHistoryRepository.kt` | `val amount: Double` (DTO) |
| `RecurringExpenseRepository.kt` | `currency: String = "EUR"` in params |
| `ManualExpenseRepository.kt` | `currency: String` in params |
| `ReceiptRepository.kt` | `currency: String` in params |
| `SavingsGoalRepository.kt` | Processes Double amounts |
| `GroupsRepositoryImpl.kt` | `currency: String` in params |

### 3c. Domain Engine Layer — 40+ files

Files that perform arithmetic, comparisons, or aggregation on `Double` amounts:

| Engine/Calculator | Key operations | Currency-aware? |
|---|---|---|
| `TotalsAggregationEngine.kt` | `.sumOf { it.effectiveAmount }`, builds `PeriodTotal` | Uses pre-normalized amounts |
| `AdvancedAnalyticsEngine.kt` | `amounts.sum()`, `totalSpent = amounts.sum()`, `values.sum()` | ❌ Raw Double |
| `BudgetForecastingEngine.kt` | `values.sum() / values.size` | ⚠️ Averages |
| `BudgetCalculator.kt` | Budget period calculations | ❌ |
| `BudgetMonitor.kt` | Compares spent vs budget | ⚠️ |
| `BudgetAutopilotEngine.kt` | Adjustments on Doubles | ❌ |
| `BudgetRecommendationEngine.kt` | Suggestion amounts | ❌ |
| `SharedBudgetManager.kt` | `remaining: Double`, offsets | ✅ Has `currency: String` |
| `CashFlowCalculator.kt` | Cash flow totals | ❌ |
| `FinancialHealthCalculator.kt` | `DEFAULT_DAILY_TARGET = 50.0`, score calculations | Normalizes first |
| `FinancialHealthScoreV2.kt` | Health score Double fields | ❌ |
| `SpendingPaceCalculator.kt` | Pace calculations | ❌ |
| `SpendingPaceProjection.kt` | Projection math | ❌ |
| `MonthlyComparisonCalculator.kt` | Month-over-month Double | ❌ |
| `CategoryInsightEngine.kt` | `amounts.sum()` | ❌ |
| `MerchantInsightEngine.kt` | `amounts.sum()` | ❌ |
| `AnomalyDetector.kt` | Statistical anomaly on Doubles | ❌ |
| `InsightsEngine.kt` | `formatCurrency(amount, currency)` | ✅ String currency |
| `SpendingThresholdCalculator.kt` | `p90: Double` threshold | ✅ |
| `LifestyleInflationDetector.kt` | `list1.sum()`, `list2.sum()` | ❌ |
| `SmartBillNegotiationEngine.kt` | Negotiation on amounts | ❌ |
| `SplitCalculator.kt` | `splits.values.sum()` | ❌ |
| `EnhancedSplitManager.kt` | `amounts.map { it.toMoney() }.sum()` | ✅ Uses `Money` value class |
| `GroupTransactionCoordinator.kt` | `currency: String` params | ✅ String currency |
| `SettlementCalculator.kt` | `amount: Double, currency: String` | ✅ String currency |
| `SharedExpenseBudgetOffsetEngine.kt` | Offset calculations | ✅ |
| `SharedExpenseManager.kt` | `paid: Double, currency: String` | ✅ |
| `AutomatedSavingsRuleEngine.kt` | Rule amounts | ❌ |
| `SmartSavingsEngine.kt` | Savings optimization | ❌ |
| `SavingsGamificationEngine.kt` | Milestone thresholds | ❌ |
| `RecurringIncomeTracker.kt` | Income amount Double | ❌ |
| `RecurringOccurrenceExpander.kt` | `amount: Double, currency: String` | ✅ String currency |
| `SubscriptionManagerEngine.kt` | Subscription cost Double | ❌ |
| `TaxEstimator.kt` | Tax Double calculations | ❌ |
| `CarbonFootprintCalculator.kt` | `values.sum()`, `offsetCost` | ❌ |
| `InvestmentTracker.kt` | Investment value Double | ❌ |
| `TransactionClassifier.kt` | `regexCurrencyCode.containsMatchIn` | String currency |
| `DuplicateDetectionPolicy.kt` | `formatAmount(amount: Double)` | ✅ String currency |
| `AreaSpendingEngine.kt` | Spending by location Double | ❌ |
| `SpendingHeatmapEngine.kt` | Heatmap Double | ❌ |
| `LocationInsightsEngine.kt` | Location totals Double | ❌ |
| `TravelDetectionEngine.kt` | Travel cost Double | ❌ |
| `FinancialStressForecastEngine.kt` | Forecast Double math | ❌ |
| `ForecastInputAssembler.kt` | `currency: String` in params | ✅ String currency |
| `MonteCarloSpendingSimulator.kt` | Simulation Double math | ❌ |
| `MonteCarloResult.kt` | Result Double fields | ❌ |
| `HistoricalSpendingDistribution.kt` | `total: Double` in DTO | ❌ |
| `DataQualityAssessor.kt` | Quality assessment | ❌ |
| `MergedRecurringPatternsProvider.kt` | Pattern merging | ❌ |
| `BusinessExpenseReportGenerator.kt` | Report Double | ❌ |
| `ComputeMoneyRadarUseCase.kt` | `amount: Double` in DTOs, `.sumOf { it.effectiveAmount }` | ⚠️ Pre-normalized |
| `ComputeDashboardWidgetsUseCase.kt` | `amount: Double, total: Double, currency: String` | ✅ String currency |
| `ProcessReceiptUseCase.kt` | `amount: Double?` DTOs | ❌ |
| `AutoCreateWarrantyFromReceiptUseCase.kt` | Warranty amount Double | ❌ |
| `MonthlySavingsSweepUseCase.kt` | Sweep amount Double | ❌ |
| `LifestyleSavingsPromptUseCase.kt` | Savings prompt Double | ❌ |
| `ExpenseUseCases.kt` | Amount in expense creation | ❌ |

### 3d. ViewModel/State Layer — 15 files

Files defining UI state data classes with raw `Double` amounts:

| ViewModel/File | State class | Double fields |
|---|---|---|
| `HomeViewModel.kt` | `DashboardState` | `totalSpent: Double` |
| `AnalyticsViewModel.kt` | `AnalyticsState` | Multiple Double fields |
| `AdvancedAnalyticsViewModel.kt` | `AnalyticsUiState` | Double fields |
| `BudgetViewModel.kt` | `BudgetUiState` | Budget Double fields |
| `BudgetForecastingViewModel.kt` | `BudgetForecastUiState` | Forecast Double fields |
| `AddExpenseViewModel.kt` | `AddExpenseState` | `amount: Double` |
| `ReceiptScanViewModel.kt` | `ReceiptScanState` | `amount: Double` |
| `SavingsGoalsViewModel.kt` | `SavingsGoalsState` | Goal Double fields |
| `SpendingChallengesViewModel.kt` | `CreateChallengeUiState` | Challenge Double fields |
| `SpendingMapViewModel.kt` | `SpendingMapState` | `amount: Double` |
| `CashFlowCalendarViewModel.kt` | `CashFlowCalendarState` | Cash flow Double fields |
| `ManualRecurringExpenseViewModel.kt` | `ManualRecurringExpenseUiState` | Amount Double |
| `SubscriptionManagementViewModel.kt` | `SubscriptionManagementUiState` | Cost Double |
| `TaxConfigurationViewModel.kt` | `TaxConfigurationUiState` | Tax bracket Double |
| `WarrantyTrackerViewModel.kt` | `WarrantyTrackerState` | Price Double |
| `SharedExpenseGroupsViewModel.kt` | `GroupsUiState` | `currency: String` |
| `ReviewViewModel.kt` | State | `amount: Double` |
| `ReceiptMatchingViewModel.kt` | State | amount Double |

### 3e. Formatter/Display Layer — 12 files

Files that format `(amount: Double, currency: String)` for display:

| File | Function |
|---|---|
| `CurrencyFormatter.kt` | `format(amount, currencyCode)`, `formatMoney(amount, currencyCode)`, `formatCompact()`, `formatWithSign()`, `formatForExport()` |
| `MoneyFormatUtils.kt` | ✅ Already wraps in `MoneyAmount.formatMoney()` |
| `AmountUtils.kt` | `formatAmount(amount, currency)` |
| `AccountantReportPdfExporter.kt` | `formatAmount(amount, currency, formatters)` |
| `CurrencyConverter.kt` | `formatAmount(amount, currencyCode)` |
| `AnalyticsScreen.kt` | `formatAmount(amount, currency, showCents)` |
| `InsightsEngine.kt` | `formatCurrency(amount, currency)` |
| `DuplicateDetectionPolicy.kt` | `formatAmount(amount)` |
| `ExecuteFinancialQueryUseCase.kt` | `formatAmount(amount, currency)` |
| `DashboardBriefingPromptFormatter.kt` | `formatExactAmount(amount, currencyCode)` |
| `ForecastMetric` UI component | `ForecastMetric(label, amount: Double, currency)` |
| `TotalsDashboardCard.kt` | renders `totalSpent: Double` with `currency: String` |
| Various UI components | `CategoryBreakdownSheet`, `BudgetBlockPartyCard`, `StatisticalVisualizations`, `RetroTopCategoriesCard` — all take `amount: Double, currency: String` |

---

## 4. Migration Complexity Breakdown

### EASY (has explicit currency field alongside amount) — ~24 files
These already carry `currency: String` and can be swapped to `MoneyAmount` directly:

- All Room entities with `currency: String` (Expense, Budget, PlannedExpense, GroupExpense, ManualRecurringExpense, SubscriptionPriceHistory, BudgetAdjustmentRecommendation, BudgetForecast, StressForecastSnapshot, SavingsGoal, SavingsSweepPlan, SpendingChallengeEntity, SubscriptionCandidate, ScannedReceipt, Investment)
- Domain DTOs with `currency: String` (ExpenseSnapshot, BudgetSnapshot, RecurringPattern, DashboardExpense, SpendingSummary)
- Domain models with `currency: String` (SharedExpenseManager, SettlementCalculator, MerchantBreakdown, AnalyticsCategoryBreakdown, RecurringCandidate, etc.)

**Count**: ~24 files — mostly replace `val amount: Double` with `val amount: MoneyAmount` and remove `currency: String`.

### MEDIUM (no currency field but context provides it) — ~20 files
These don't have a local `currency` field, but the currency can be derived from context (e.g., home currency, parent entity's currency, or parameter):

- `PeriodTotal.kt` — no currency, but used in context of a `displayCurrency`
- `CategoryBreakdown.kt` — no currency, used in dashboard context
- `BlockPartyDay.kt` — no currency
- `UpcomingItem.kt` — no currency
- `DomainDayBudgetStatus.kt` — no currency, derived from home currency
- `FinancialWeather.kt` — no currency
- `FinancialForecast.kt` — no currency
- `BudgetStatusSnapshot.kt` — no currency
- `PlannedExpense.kt` (domain model) — no currency
- Various analytics model DTOs in `AnalyticsModels.kt` that lack currency fields

**Count**: ~20 files — need a currency field added or wired from context.

### HARD (aggregation across multiple currencies) — ~10 files
These perform aggregation/sum over expenses that may be in different currencies:

- `TotalsAggregationEngine.kt` — aggregates period totals (but uses pre-normalized data)
- `AdvancedAnalyticsEngine.kt` — `.sum()` on amounts
- `MultiCurrencyRepository.kt` — ✅ Already converted to `MoneyAggregate`
- `BudgetRepository.kt` — ✅ Already uses `MoneyAggregate`
- `SpendingPaceCalculator.kt` — aggregation over time
- `MonthlyComparisonCalculator.kt` — month-to-month comparison
- `InsightsEngine.kt` — insight generation from aggregated data
- `ComputeMoneyRadarUseCase.kt` — `.sumOf { it.effectiveAmount }`
- `LifestyleInflationDetector.kt` — `list1.sum()`, trend calculations
- `CashFlowCalculator.kt` — cash flow aggregation

**Count**: ~10 files — need `MoneyAggregate` with conversion logic.

### VERY HARD (database column) — ~19 files (Room entities)
These require Room database schema migration (adding/changing columns):

- `Expense.kt` — `amount: Double` → would need type converter for `MoneyAmount` or keep Double+currency as-is
- `Budget.kt`, `PlannedExpense.kt`, `GroupExpense.kt`, `ManualRecurringExpense.kt`, `SubscriptionPriceHistory.kt`, `InvestmentValue.kt`, `AnomalyAlert.kt`, `BudgetAdjustmentRecommendation.kt`, `BudgetForecast.kt`, `SavingsGoal.kt`, `SavingsSweepPlan.kt`, `SpendingChallengeEntity.kt`, `SubscriptionCandidate.kt`, `ScannedReceipt.kt`, `Investment.kt`, `StressForecastSnapshot.kt`, `SplitTemplate.kt`, `ExchangeRate.kt` (rate, not amount)

**Best strategy**: Keep Room columns as `Double` + `String` (currency) and add a `MoneyAmount` computed property or use a Room `@TypeConverter` to serialize `MoneyAmount` as a JSON string. The latter is cleaner but requires migration.

---

## 5. Recommended Migration Order (4 Tiers)

### Tier 1: Dashboard/Analytics Totals (HIGHEST USER IMPACT) — ~15 files
Replace raw Double + currency String pairs in dashboard/analytics outputs with `MoneyAmount`/`MoneyAggregate`:

| File | Current | Target |
|---|---|---|
| `HomeViewModel.kt` — `DashboardState.totalSpent` | `Double` | `MoneyAmount` or `MoneyAggregate` |
| `SpendingSummary.kt` | `totalSpent: Double, currency: String` | `totalSpent: MoneyAmount` |
| `BudgetStatusSnapshot.kt` | `budgetAmount: Double, spentAmount: Double` | `budgetAmount: MoneyAmount, spentAmount: MoneyAmount` |
| `FinancialWeather.kt` | Multiple `Double` + implicit currency | `MoneyAmount` |
| `TotalsDashboardCard.kt` UI component | `amount: Double, currency: String` | `MoneyAmount` |
| `FinancialWeatherCard.kt`, `FinancialStressForecastCard.kt` | `amount: Double, currency: String` | `MoneyAmount` |
| `AnalyticsScreen.kt` formatting | `formatAmount(amount: Double, currency: String)` | `MoneyAmount.formatMoney()` |
| `TotalsAggregationEngine.kt` | `totalAmount: Double` in `PeriodTotal` | `MoneyAmount` |

### Tier 2: Engine Calculations (Forecasts, Budgets, Health, Savings) — ~30 files
Core business logic that computes / aggregates amounts:

| Priority | Engine | Migration Notes |
|---|---|---|
| 1 | `BudgetForecastingEngine.kt` | Replace Double arithmetic with Money |
| 2 | `BudgetMonitor.kt`, `BudgetAutopilotEngine.kt` | Budget tracking with MoneyAmount |
| 3 | `FinancialHealthCalculator.kt` | Health score Double → MoneyAmount for targets |
| 4 | `FinancialHealthScoreV2.kt` | Score calculation with MoneyAmount |
| 5 | `SpendingPaceCalculator.kt` | Pace calculation Double → MoneyAmount |
| 6 | `MonthlyComparisonCalculator.kt` | Month comparison |
| 7 | `MergeRecurringPatternsProvider.kt` | Pattern merge |
| 8 | `CashFlowCalculator.kt` | Cash flow totals |
| 9 | `AutomatedSavingsRuleEngine.kt` | Savings rules |
| 10 | `SmartSavingsEngine.kt` | Savings optimization |
| 11 | `LifestyleInflationDetector.kt` | Trend detection |
| 12 | `SmartBillNegotiationEngine.kt` | Negotiation amounts |
| 13 | `ComputeMoneyRadarUseCase.kt` | Money Radar aggregation |
| 14 | `ComputeDashboardWidgetsUseCase.kt` | Dashboard widgets |
| 15 | `SplitCalculator.kt`, `EnhancedSplitManager.kt` | Split calculations (already uses `Money` value class) |

### Tier 3: Entity Fields (Requires DB Migration) — ~19 files
Room entity columns storing `Double` amounts. Strategy varies:

**Option A** (recommended): Keep Room columns as `Double` + `String(currency)` for backward compat. Add `@Ignore val amountMoney: MoneyAmount` computed property. Future migration can add a JSON column.

**Option B**: Use `@TypeConverter` to convert `MoneyAmount` ↔ JSON string stored in a single column. This is cleaner but breaks existing data without migration.

**Migration order**:
1. Entities already having `amount + currency` → add `MoneyAmount` computed property (safe, non-breaking)
2. Entities missing `currency` → add `currency` column with default='EUR' migration
3. Consider a combined `amountData` JSON column for the future

### Tier 4: UI/ViewModel Display (Lowest Risk) — ~20 files
Surface-level changes — replace `(amount: Double, currency: String)` pairs with `MoneyAmount` in composable params:

| Area | Files |
|---|---|
| Analytics screens | `AnalyticsScreen.kt`, `AdvancedAnalyticsScreen.kt` |
| Dashboard cards | `TotalsDashboardCard.kt`, `FinancialWeatherCard.kt`, `FinancialRunwayCard.kt`, `BudgetBlockPartyCard.kt`, etc. |
| Budget screens | `BudgetScreen.kt`, `BudgetForecastingScreen.kt` |
| Analytics components | `StatisticalVisualizations.kt`, `CategoryDonutChart.kt`, `SpendingTrendChart.kt` |
| ViewModel states | All 15 ViewModel states listed in §3d |
| Formatting utils | `CurrencyFormatter.kt` — add MoneyAmount overloads, deprecate raw Double versions |
| UI mappers | `DashboardWidgetUiMapper.kt`, `MonteCarloBudgetImpactUiMapper.kt` |

---

## 6. File Inventory — Complete List

### Already Migrated (use MoneyAmount/MoneyAggregate)

```
domain/core/money/MoneyAmount.kt           ★ DEFINITION
domain/core/money/MoneyAggregate.kt        ★ DEFINITION
domain/core/money/CurrencyCode.kt          ★ DEFINITION
domain/core/money/ConvertedMoney.kt        ★ DEFINITION
domain/core/money/MoneyBucket.kt           ★ DEFINITION
domain/core/money/ConversionFailure.kt     ★ DEFINITION
domain/core/money/CurrencyAssumption.kt    ★ DEFINITION
domain/core/money/MoneyFormatUtils.kt      ★ Extensions
domain/core/money/MoneyMappers.kt          ★ Mappers
data/repository/MultiCurrencyRepository.kt ★ Producer
data/repository/BudgetRepository.kt        ★ Consumer
```

### All Files Needing Migration (raw Double for amounts)

#### ENTITY LAYER (Room) — requires DB schema consideration
```
data/database/entity/Expense.kt                    EASY (has currency)
data/database/entity/Budget.kt                     EASY (has currency)
data/database/entity/PlannedExpense.kt             EASY (has currency)
data/database/entity/GroupExpense.kt               EASY (has currency)
data/database/entity/ManualRecurringExpense.kt     EASY (has currency)
data/database/entity/SubscriptionPriceHistory.kt   EASY (has currency)
data/database/entity/InvestmentValue.kt            MEDIUM (no currency)
data/database/entity/AnomalyAlert.kt               MEDIUM (no currency)
data/database/entity/BudgetAdjustmentRecommendation.kt  EASY (has currency)
data/database/entity/BudgetForecast.kt             EASY (has currency)
data/database/entity/StressForecastSnapshot.kt     EASY (has currency)
data/database/entity/SavingsGoal.kt                EASY (has currency)
data/database/entity/SavingsSweepPlan.kt           EASY (has currency)
data/database/entity/SpendingChallengeEntity.kt    EASY (has currency)
data/database/entity/SubscriptionCandidate.kt      EASY (has currency)
data/database/entity/ScannedReceipt.kt             EASY (has currency)
data/database/entity/Investment.kt                 EASY (has currency)
data/database/entity/SplitTemplate.kt              MEDIUM (amount is nullable, no currency)
data/database/entity/ExchangeRate.kt               ⚠️ rate is a multiplier, not an amount
```

#### DOMAIN MODEL LAYER
```
domain/model/ExpenseSnapshot.kt                    EASY (has currency)
domain/model/PlannedExpense.kt                     MEDIUM (no currency)
domain/model/PeriodTotal.kt                        MEDIUM (no currency)
domain/model/BudgetSnapshot.kt                     EASY (has currency)
domain/model/CategoryBreakdown.kt                  MEDIUM (no currency)
domain/model/BlockPartyDay.kt                      MEDIUM (no currency)
domain/model/UpcomingItem.kt                       MEDIUM (no currency)
domain/model/SavingsGoal.kt                        EASY (has currency)
domain/model/RecurringPattern.kt                   EASY (has currency)
domain/model/FinancialForecast.kt                  MEDIUM (no currency)
domain/model/budget/MonteCarloBudgetImpact.kt      MEDIUM (overrun is amount, probability is %)
domain/model/dashboard/DashboardPrimitives.kt      EASY (has currency)
domain/model/dashboard/SpendingSummary.kt          EASY (has currency)
domain/model/dashboard/BudgetStatusSnapshot.kt     MEDIUM (no currency)
domain/model/dashboard/DomainDayBudgetStatus.kt    MEDIUM (no currency)
domain/model/dashboard/FinancialWeather.kt         MEDIUM (no currency)
domain/model/dashboard/DashboardCategoryBreakdown.kt  MEDIUM (no currency)
domain/model/navigation/DomainTransactionFilter.kt  MEDIUM (filter range, no currency)
```

#### ANALYTICS MODEL LAYER
```
domain/analytics/AnalyticsModels.kt                Varies (some with currency, some without)
  - SpendingPeriod: total: Double, currency implied
  - AnalyticsCategoryBreakdown: total: Double + displayCurrency: String → EASY
  - MerchantBreakdown: totalSpent + displayCurrency → EASY
  - RecurringCandidate: amount: Double + displayCurrency → EASY
  - InsightsSnapshot: averageTransactionSize, medianTransactionSize + displayCurrency → EASY
  - MonthlyYearTotal: total + displayCurrency → EASY
  - YearOverYearComparison: currentYearTotal, priorYearTotal + displayCurrency → EASY
  - VelocityAnomaly: dayTotal, monthDailyAvg + displayCurrency → EASY
  - PostSalaryPattern: avgSalaryAmount, avgTotalSpentIn7Days + displayCurrency → EASY
  - SuspectTransaction: amount + currency → EASY
domain/analytics/AdvancedAnalyticsModels.kt        Similar to above
domain/analytics/AnalyticsCurrencyNormalizer.kt    EASY (has currency fields)
domain/analytics/AnomalyDetector.kt                HARD (statistical, no currency)
```

#### DAO/DATABASE QUERY RESULT TYPES
```
data/database/dao/ExpenseDao.kt                    HARD (17 inline data classes with total: Double)
  - Plus: CurrencyTotal, CategoryCurrencyTotal, MerchantCurrencyTotal, MonthlyCurrencyTotal
```

#### ENGINE/CALCULATOR LAYER (HARD — aggregation logic)
```
domain/analytics/TotalsAggregationEngine.kt        HARD (sums across periods)
domain/analytics/AdvancedAnalyticsEngine.kt        HARD (sum/aggregate)
domain/analytics/MonthlyComparisonCalculator.kt    HARD
domain/analytics/SpendingPaceCalculator.kt         HARD
domain/analytics/SpendingPaceProjection.kt         HARD
domain/analytics/MerchantInsightEngine.kt          HARD
domain/analytics/CategoryInsightEngine.kt          HARD
domain/analytics/InsightsEngine.kt                 HARD
domain/analytics/SpendingThresholdCalculator.kt    HARD
domain/analytics/SpendingPersonalityClassifier.kt  MEDIUM
domain/analytics/DayOfWeekAnalyzer.kt              MEDIUM
domain/analytics/AdvancedAnalyticsDashboard.kt     HARD
domain/health/FinancialHealthCalculator.kt         HARD (health score logic)
domain/health/FinancialHealthScoreV2.kt            MEDIUM
domain/budget/BudgetForecastingEngine.kt           HARD
domain/budget/BudgetMonitor.kt                     MEDIUM
domain/budget/BudgetAutopilotEngine.kt             HARD
domain/budget/BudgetCalculator.kt                  MEDIUM
domain/budget/BudgetRecommendationEngine.kt        MEDIUM
domain/budget/BudgetRecommendationInputs.kt        MEDIUM
domain/budget/SharedBudgetManager.kt               EASY (has currency)
domain/budget/BudgetHistorySeriesBuilder.kt        MEDIUM
domain/cashflow/CashFlowCalculator.kt              HARD
domain/forecasting/FinancialStressForecastEngine.kt  HARD
domain/forecasting/ForecastInputAssembler.kt        MEDIUM
domain/forecasting/MonteCarloSpendingSimulator.kt   HARD
domain/forecasting/MonteCarloResult.kt              MEDIUM
domain/forecasting/HistoricalSpendingDistribution.kt MEDIUM
domain/forecasting/DataQualityAssessor.kt           MEDIUM
domain/savings/AutomatedSavingsRuleEngine.kt        MEDIUM
domain/savings/SmartSavingsEngine.kt                MEDIUM
domain/savings/SavingsGamificationEngine.kt         MEDIUM
domain/income/RecurringIncomeTracker.kt             MEDIUM
domain/lifestyle/LifestyleInflationDetector.kt      HARD
domain/split/EnhancedSplitManager.kt                ✅ Already uses Money value class
domain/split/SplitCalculator.kt                     MEDIUM
domain/groups/SettlementCalculator.kt               EASY (has currency)
domain/groups/SharedExpenseManager.kt               EASY (has currency)
domain/groups/SharedExpenseBudgetOffsetEngine.kt    MEDIUM
domain/groups/GroupTransactionCoordinator.kt        MEDIUM (currency params)
domain/carbon/CarbonFootprintCalculator.kt          HARD
domain/tax/TaxEstimator.kt                          MEDIUM
domain/investment/InvestmentTracker.kt              MEDIUM
domain/negotiation/SmartBillNegotiationEngine.kt    MEDIUM
domain/subscription/SubscriptionManagerEngine.kt    MEDIUM
domain/recurring/RecurringOccurrenceExpander.kt     EASY (has currency)
domain/reminder/BillReminderManager.kt              EASY (has currency)
domain/challenge/SpendingChallengeManager.kt        MEDIUM
domain/price/PriceProtectionTracker.kt              MEDIUM
domain/business/BusinessExpenseReportGenerator.kt   MEDIUM
domain/location/AreaSpendingEngine.kt               MEDIUM
domain/location/SpendingHeatmapEngine.kt            MEDIUM
domain/location/LocationInsightsEngine.kt           MEDIUM
domain/location/LocatedExpense.kt                   MEDIUM
```

#### USE CASES
```
domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt   MEDIUM (has currency)
domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt         MEDIUM (pre-normalized)
domain/usecase/dashboard/AnomalyAlertRepository.kt           MEDIUM
domain/usecase/expense/ExpenseUseCases.kt                    MEDIUM
domain/usecase/receipt/ProcessReceiptUseCase.kt              MEDIUM
domain/usecase/savings/LifestyleSavingsPromptUseCase.kt      MEDIUM
domain/usecase/savings/MonthlySavingsSweepUseCase.kt         MEDIUM
domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt MEDIUM
```

#### VIEWMODEL / UI STATE LAYER
```
ui/screens/home/HomeViewModel.kt                          EASY (can be MoneyAmount)
ui/screens/analytics/AnalyticsViewModel.kt                EASY
ui/screens/analytics/AdvancedAnalyticsViewModel.kt        EASY
ui/screens/budget/BudgetViewModel.kt                      EASY
ui/screens/budget/BudgetForecastingViewModel.kt           EASY
ui/screens/addexpense/AddExpenseViewModel.kt              MEDIUM (user input)
ui/screens/receiptscan/ReceiptScanViewModel.kt            MEDIUM
ui/screens/savings/SavingsGoalsViewModel.kt               EASY
ui/screens/challenge/SpendingChallengesViewModel.kt       EASY
ui/screens/map/SpendingMapViewModel.kt                    MEDIUM
ui/screens/cashflow/CashFlowCalendarViewModel.kt          EASY
ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt EASY
ui/screens/subscription/SubscriptionManagementViewModel.kt  EASY
ui/screens/tax/TaxConfigurationViewModel.kt                EASY
ui/screens/warranty/WarrantyTrackerViewModel.kt           EASY
ui/screens/groups/SharedExpenseGroupsViewModel.kt         EASY (has currency)
ui/screens/review/ReviewViewModel.kt                      MEDIUM
ui/screens/receiptmatching/ReceiptMatchingViewModel.kt    MEDIUM
```

#### UI COMPONENTS (formatting & display)
```
ui/components/TotalsDashboardCard.kt                      EASY (swap Double+currency → MoneyAmount)
ui/components/CategoryBreakdownSheet.kt                   EASY
ui/components/BudgetBlockPartyCard.kt                     EASY
ui/components/FinancialWeatherCard.kt                     EASY
ui/components/FinancialStressForecastCard.kt              EASY
ui/components/FinancialRunwayCard.kt                      EASY
ui/components/RetroTotalsDashboardCard.kt                 EASY
ui/components/RetroCategoryBreakdownSheet.kt              EASY
ui/components/RetroTopCategoriesCard.kt                   EASY
ui/components/RetroBudgetBlockPartyCard.kt                EASY
ui/components/SpendingPaceGauge.kt                        EASY
ui/components/SpendingTrendChart.kt                       EASY
ui/components/ForecastTimeline.kt                         EASY
ui/components/MonteCarloForecastCard.kt                   EASY
ui/components/PlaceInsightCard.kt                         EASY
ui/components/PeriodBlock.kt                              MEDIUM
ui/components/PeriodGridView.kt                           MEDIUM
ui/components/dashboard/MoneyRadarWidget.kt               MEDIUM (still uses raw Double DTOs)
ui/components/analytics/StatisticalVisualizations.kt      EASY
ui/components/analytics/NoSpendStreakWidget.kt            MEDIUM
ui/components/analytics/PersonalityProfileCard.kt         MEDIUM
ui/components/feature/MetricComponents.kt                 EASY
```

#### FORMATTER / UTILITY LAYER
```
domain/util/CurrencyFormatter.kt                          Add MoneyAmount overloads, deprecate Double
domain/util/AmountUtils.kt                                Migrate to MoneyAmount
domain/util/Money.kt                                      LEGACY — keep for precision, migrate callers
ui/util/ClipboardAmountParser.kt                          Parser — not amount storage
domain/export/AccountantReportPdfExporter.kt               MEDIUM
domain/currency/CurrencyConverter.kt                       HARD (conversion infrastructure)
data/ai/provider/DashboardBriefingPromptFormatter.kt       MEDIUM
domain/ai/usecase/ExecuteFinancialQueryUseCase.kt          MEDIUM
domain/intelligence/DuplicateDetectionPolicy.kt            EASY (has currency)
domain/text/UiTextArg.kt                                   MEDIUM (format arg types)
```

#### PARSER / INGESTION LAYER
```
domain/parser/AppParserRegistry.kt                        MEDIUM (parsed amount + currency)
domain/parser/GenericTransactionParser.kt                 MEDIUM
domain/receipt/ReceiptParser.kt                           MEDIUM
domain/receipt/EmailReceiptData.kt                        MEDIUM
domain/receipt/ReceiptSource.kt                           MEDIUM
data/email/provider/EmailReceiptParser.kt                 MEDIUM
data/repository/NotificationProcessingPipeline.kt         MEDIUM (DTOs with amount + currency)
data/repository/ReviewQueueRepository.kt                  MEDIUM
data/repository/SavingsContributionHistoryRepository.kt   MEDIUM
domain/bank/BankApiIntegration.kt                         MEDIUM
domain/transaction/CreateExpenseRequest.kt                 MEDIUM (has currency)
domain/export/ExportTransaction.kt                        EASY (has currency)
```

---

## Summary Statistics

| Metric | Count |
|---|---|
| **Total source files** | ~775 across project, ~368 in main source |
| **Files using MoneyAmount/MoneyAggregate** | 9 |
| **Files using raw Double for amounts** | 71+ |
| **Room entities needing migration** | 19 |
| **Domain engines needing migration** | 40+ |
| **ViewModel states needing migration** | 18 |
| **UI components needing migration** | 25+ |
| **Formatter utilities needing migration** | 8+ |

### Complexity distribution:
| Complexity | Count | Characteristics |
|---|---|---|
| **Easy** (~24 files) | Has explicit `currency: String` → simple type swap | Entity models, DTOs with currency |
| **Medium** (~30 files) | No currency field, but derivable from context | Domain models, use cases, UI states |
| **Hard** (~20 files) | Aggregation/arithmetic across currencies | Analytics engines, forecasting, calculators |
| **Very Hard** (~19 files) | Room DB columns + SQL | Room entities, DAOs |

### Recommended approach for Room entities:
1. Keep `Double amount` + `String currency` columns as-is
2. Add `@Ignore val moneyAmount: MoneyAmount` computed getter
3. Add a Room `@TypeConverter` for `CurrencyCode ↔ String`
4. For new entities, use a single `@ColumnInfo(typeAffinity = ColumnInfo.TEXT)` + TypeConverter for `MoneyAmount`
5. Future migration: combine into single JSON column

---

*End of Audit — 2,263 lines of source analyzed across 368+ files.*
