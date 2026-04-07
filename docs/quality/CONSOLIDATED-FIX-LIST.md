# Consolidated Fix List (Batches 01-04)

## Summary Table

### CRITICAL (Fix Immediately)
| # | Issue | Files | Description | Fix Strategy |
|---|-------|-------|-------------|--------------|
| 1 | Budget period/window logic is broken and duplicated | `BudgetCalculator.kt`, `BudgetForecastingEngine.kt`, `BudgetRepository.kt`, `SharedBudgetManager.kt` | Rolling budget windows freeze on the original anchor, monthly windows use fixed 30-day math, and multiple components implement conflicting period logic. | Make `BudgetCalculator` the single source of truth; add explicit APIs for "window containing evaluation time" and sequential next/previous windows; delete duplicate period math elsewhere. |
| 2 | Budget forecasting and recommendation math double-counts spend | `BudgetForecastingEngine.kt`, `BudgetRecommendationEngine.kt`, `BudgetForecastingViewModel.kt` | Forecasts always project a fixed 30-day window, `predictedRemaining` subtracts both `spentToDate` and a full forecast, and overspend probability is incorrectly discounted by confidence. | Forecast only the remaining budget horizon, separate probability from confidence, pass actual `spentToDate` explicitly through contracts, and re-baseline recommendation thresholds/tests. |
| 3 | BudgetMonitor breaks structured concurrency and can serve stale/missed alerts | `BudgetMonitor.kt` | Unsynchronized singleton cache/state; `CancellationException` is swallowed. Stale statuses, missed notifications, and broken coroutine cancellation behavior. | Rethrow `CancellationException`, guard mutable state with `Mutex`/atomics, invalidate cache on writes/post-expense checks, and harden cleanup/lifecycle behavior. |
| 4 | Automated savings rules can mint duplicate money and lose cap state | `AutomatedSavingsRuleEngine.kt` | Weekly no-spend rule fires repeatedly within the same week/batch, monthly cap tracking lives only in memory, invalid percentage inputs (`NaN`/negative/infinite) can emit bad transfers. | Persist rule executions by `ruleId + period`, persist month-to-date cap totals atomically, and validate rule inputs before computing savings amounts. |
| 5 | SplitCalculator money math can overflow or hang | `SplitCalculator.kt` | `toCents()` overflows for large amounts; negative-remainder loop can fail to make progress and spin forever. Corruption and runtime hang risk. | Convert all minor-unit math to `Long`, add loop progress/safety guards, and expand tests around large totals and tiny-percentage edge cases. |
| 6 | Recurring obligations disappear from forecasts and synthesis | `RecurringExpenseEngine.kt`, `FinancialStressForecastEngine.kt`, `SynthesisEngine.kt` | Stale `nextExpectedDate` values are not advanced into the active horizon and downstream engines only count a subset of occurrences. | Centralize recurring-occurrence expansion, always roll patterns forward to the horizon start, and enumerate every in-range occurrence for both synthesis and stress forecasting. |

### HIGH (Fix Before Release)
| # | Issue | Files | Description | Fix Strategy |
|---|-------|-------|-------------|--------------|
| 1 | Analytics/dashboard totals are inconsistent with `effectiveAmount` rules | `AdvancedAnalyticsDashboard.kt`, `ExpenseDao`, `InsightsEngine.kt`, `SpendingThresholdCalculator.kt` | Raw `amount` paths bypass shared-expense and `isNotMine` semantics. Dashboard widgets, merchant stats, anomalies, and thresholds can disagree for the same user data. | Centralize the effective-amount expression/helper and require every analytics query/aggregation path to reuse it. |
| 2 | Merchant analytics use incompatible identities and labels across engines | `AdvancedAnalyticsEngine.kt`, `InsightsEngine.kt`, `MerchantInsightEngine.kt` | Raw merchant text, lowercased merchant text, `merchantKey`, and display names are mixed across engines. Fragments merchants, nulls std-dev lookups, surfaces canonical keys in UI. | Standardize on `merchantKey + displayName`, have DAO outputs carry both, and delegate merchant analytics to one canonical engine. |
| 3 | AdvancedAnalyticsEngine current-period math is wrong for in-progress periods | `AdvancedAnalyticsEngine.kt` | Future days counted as zero-spend days, sparkline/day buckets use DST-unsafe millisecond division, January streak keys are 0-indexed, "recent"/historical calculations inconsistent. | Use calendar-aware helpers (`TimePeriodUtils`/`java.time`), cap calculations at `now`/end-of-today, sort before `.take()`, and exclude current-period rows from historical baselines. |
| 4 | Anomaly detection can miss obvious spikes or keep lower-quality detections | `AnomalyDetector.kt` | Flat-baseline series (`IQR == 0` / `MAD == 0`) produce no anomalies; merge priority is inverted via enum ordinals. Suppresses and mislabels anomaly results. | Add zero-dispersion fallback thresholds and replace ordinal comparisons with explicit priority map. |
| 5 | Dashboard analytics pipeline is expensive and semantically divergent | `AdvancedAnalyticsDashboard.kt`, `DashboardDataProvider.kt`, `DashboardContractsAdapter.kt` | N+1 monthly queries, misses month-end transactions, leaks placeholder category labels/change values, duplicates existing dashboard pipeline with different semantics. | Fetch once per range, use exclusive next-period bounds, resolve real category metadata, consolidate on shared dashboard contracts/use-cases. |
| 6 | Totals and pacing analytics produce distorted timelines and budget scores | `TotalsAggregationEngine.kt`, `SpendingPersonalityClassifier.kt` | Zero-spend months/weeks/days disappear from totals series, budget adherence compares ~3 months of spend against single monthly budget. Charts, drill-downs, personality scoring biased. | Materialize complete zero-filled timelines and normalize spending to same cadence as compared budget window. |
| 7 | Shared-budget calculations bypass core budget semantics | `SharedBudgetManager.kt`, `BudgetCalculator.kt`, `BudgetRepository.kt`, `BudgetMonitor.kt` | Raw-amount aggregation, wrong overall-budget filtering, fixed month-to-date windows, possible query truncation, fabricated member contribution output. | Route shared-budget progress through same period/spend primitives as repository/monitor path, use `effectiveAmount`, fix overall-budget filtering, hide placeholder contribution APIs. |
| 8 | Budget autopilot recommendations are not portfolio-safe | `BudgetAutopilotEngine.kt` | Total budgets double-counted when overall and category budgets coexist; sparse/empty histories rewarded with unjustified confidence. | Separate overall-vs-category portfolio views, require minimum history before confidence bonuses, reuse shared calendar/statistics helpers. |
| 9 | Forecast persistence/contracts are incomplete | `BudgetForecastingEngine.kt`, `BudgetForecastDao.kt`, `BudgetRecommendationEngine.kt` | Superseded forecasts remain active, `updateForecastAccuracy()` never resolves a real forecast row, forecasting/recommendation models use divergent DTOs/enums. | Deactivate prior active forecasts transactionally, add DAO lookup by forecast ID, define one canonical forecast/recommendation contract. |
| 10 | Savings recommendations double-count headroom and over-allocate per goal | `SmartSavingsEngine.kt`, `MonthlySavingsSweepUseCase.kt` | Same available headroom counted across overall + category budgets then returned independently for multiple goals. Same "safe to save" amount promised more than once. | Reuse the same budget-headroom selection policy as the sweep use case and split portfolio-level safe amount from goal-allocation logic. |
| 11 | Financial health has no canonical budget/score model | `FinancialHealthScoreV2.kt`, `FinancialHealthCalculator.kt`, `ComputeDashboardWidgetsUseCase.kt` | Health scoring exists in parallel V1/V2 systems, budget adherence/headroom can double-count overall + category budgets. Users see conflicting "financial health" answers. | Choose one canonical health KPI (or clearly gate legacy output) and reuse shared budget aggregation policy everywhere. |
| 12 | FinancialHealthCalculator misclassifies income as spending | `FinancialHealthCalculator.kt` | Deposits/transfers contaminate spending and volatility calculations; week-boundary inconsistency and zero-budget divide-by-zero paths. | Filter to spending transaction types only, standardize on one week-boundary helper, guard zero-budget cases explicitly. |
| 13 | Stress forecast inputs and confidence are systematically biased | `FinancialStressForecastEngine.kt`, `DataQualityAssessor.kt` | "Current balance"/income inferred from monthly net flow or budget caps, zero-spend days excluded from bootstrap sampling, unusable fits still receive high confidence. | Require real cash/income inputs (or degrade gracefully when absent), include zero-spend frequency in empirical distributions, cap unusable fits to `LOW`/`MODERATE` confidence. |
| 14 | Historical spending distribution uses locale/DST-unsafe bucketing | `HistoricalSpendingDistribution.kt` | Fixed 24h/7d millisecond bucketing around DST; locale-dependent `Calendar` week-start logic shifts entire lookback window. Sampling quality and fitted totals corrupted. | Derive day/week boundaries only through shared calendar-aware helpers (`TimePeriodUtils`/`java.time`) and reuse them for both lookback windows and bucket assignment. |
| 15 | Custom split validation is inconsistent across the group-expense stack | `CustomSplitParser.kt`, `SplitCalculator.kt`, `SharedExpenseBudgetOffsetEngine.kt`, `GroupsRepositoryImpl.kt`, `SharedExpenseManager.kt` | Split data validated against current member list instead of historical participant set, budget-offset logic reparses payloads permissively, invalid data silently falls back to equal split. | Persist participant snapshots/membership versions on expenses, route every parse through `CustomSplitParser`, surface invalid legacy splits explicitly instead of rewriting meaning. |

### MEDIUM (Fix in Next Sprint)
| # | Issue | Files | Description | Fix Strategy |
|---|-------|-------|-------------|--------------|
| 1 | Secondary analytics state is not concurrency-safe or fully accurate | `SpendingThresholdCalculator.kt`, `TransferDirectionAnalytics.kt` | Non-thread-safe cache in threshold calculation; transfer correction bookkeeping assumes initial correctness and pruning is nondeterministic. | Use concurrent data structures/atomic update semantics, persist initial transfer correctness, prune deterministically with ordered/LRU metadata. |
| 2 | Analytics engines are still duplicated and inefficient in places | `InsightsEngine.kt`, `MonthlyComparisonCalculator.kt`, `CategoryInsightEngine.kt`, `DayOfWeekAnalyzer.kt` | Extracted engines still coexist with parallel implementations, previous-period category scans are O(categories × expenses), weekday ordering inconsistent. | Make `InsightsEngine` a coordinator only, pre-group previous-period data once, return stable Monday→Sunday ordering from shared analyzer. |
| 3 | Savings gamification is built on placeholders and unstable timestamps | `SavingsGamificationEngine.kt` | Streaks and monthly contributions inferred from goal metadata instead of contribution history, `unlockedAt` changes on each read, some progress formulas emit `NaN`. | Persist contribution and unlock events, switch to calendar-aware date math, guard zero-target goals explicitly. |
| 4 | Smart savings horizon math is internally inconsistent | `SmartSavingsEngine.kt`, `MonteCarloSpendingSimulator.kt` | Week/quarter horizons derived by scaling month-end Monte Carlo forecast; discretionary history divided by fixed 3 months; malformed negative effective amounts create negative projections. | Restrict Monte Carlo path to monthly mode or add horizon-specific forecasting, divide by actual historical months, clamp malformed negative aggregate inputs. |
| 5 | FinancialHealthScoreV2 trend/error handling is misleading | `FinancialHealthScoreV2.kt`, `HealthScoreHistoryDao.kt` | Recalculations can compare a score against the same period's latest row, broad exception handling returns synthetic `50/STABLE` result that looks real to callers. | Compare only against prior periods and add explicit error metadata to the result contract. |
| 6 | Precision/tolerance edge cases still need hardening | `CustomSplitParser.kt`, `MonteCarloSpendingSimulator.kt`, `SpendingPaceCalculator.kt` | Boundary-valid split percentages can fail because of `Double` math, qualifying-week logic diverges between simulator/distribution fit, conservative pace heuristics poorly calibrated. | Move split validation to minor-unit/basis-point math and share same qualification/statistics rules across simulation paths before tuning heuristics. |

### LOW / FALSE POSITIVES (Defer or Ignore)
| # | Issue | Files | Description | Reason |
|---|-------|-------|-------------|--------|
| 1 | Extra dispatcher hop in analytics category loading | `AdvancedAnalyticsEngine.kt` | `async(ioDispatcher)` inside `withContext(defaultDispatcher)`. | False positive: functionally correct, optional micro-optimization only. |
| 2 | Missing planned `*Models.kt` files / stale batch file map | Multiple docs/model locations | Missing planned files such as `SpendingPaceModels.kt`, `SavingsModels.kt`, `ForecastModels.kt`. | Low priority documentation/structure drift, not a direct runtime defect. |
| 3 | Miscellaneous heuristic/UI nits | `AdvancedAnalyticsDashboard.kt`, `SpendingPaceCalculator.kt`, `SplitCalculator.kt`, `CustomSplitParser.kt` | Noisy weekend insight thresholds, arbitrary conservative multipliers, hardcoded currency formatting, non-impactful collection type choices. | Defer until after correctness issues above; limited-scope polish items. |

---

## Detailed Plans

### CRITICAL-1: Budget period/window logic is broken and duplicated

#### Root Cause Analysis
**Bug 1: Rolling windows freeze on original anchor** (`BudgetCalculator.kt` lines 42-49): The ROLLING branch uses `budget.startDate` as the literal start, which means on day 31+ the window never advances. A rolling monthly budget created on Jan 15 will forever use Jan 15 → Feb 14, even in June. Additionally, the monthly rolling period uses `addDays(start, 30)` which is wrong for months with 28, 29, or 31 days.

**Bug 2: Duplicate period math in BudgetForecastingEngine** (lines 322-409): `calculateCurrentBudgetPeriodRange()` is an ~87-line private method that re-implements the exact same period logic as `BudgetCalculator.calculatePeriodRange()`. Any fix to `BudgetCalculator` won't automatically propagate.

**Bug 3: SharedBudgetManager hardcodes calendar-month window** (lines 33, 83-92): `getSharedBudgetProgress()` calls its own `getStartOfMonth(now)` — ignoring the budget's actual period, periodMode, and anchor date.

#### Dependency Map
```
BudgetCalculator (period source of truth)
  ├── BudgetRepository.getBudgetStatuses()         ← calls calculatePeriodRange() ✅
  ├── BudgetRepository rollover logic               ← calls calculatePeriodWindow() ✅
  ├── SmartSavingsEngine                            ← consumes BudgetCalculator ✅
  ├── BudgetForecastingEngine.generateForecast()    ← has DUPLICATE calculateCurrentBudgetPeriodRange() ❌
  ├── SharedBudgetManager.getSharedBudgetProgress() ← has DUPLICATE getStartOfMonth() ❌
  └── BudgetMonitor                                 ← consumes BudgetRepository statuses (indirect) ✅
```

#### Cross-Component Risks
1. **BudgetRepository rollover logic** (lines 86-100) iterates windows using `calculatePeriodWindow(budget.period, currentWindow.end)`. After fixing rolling window logic, rollover iteration MUST still correctly walk sequential windows.
2. **BudgetForecastingEngine** computes `spentToDate` using its own period range. After replacing with `BudgetCalculator`, the `periodStart`/`periodEnd` values will change for rolling budgets — meaning `spentToDate` values will change too.
3. **BudgetMonitor** consumes `BudgetStatus` from `BudgetRepository`. Period changes propagate automatically, but the `shouldNotify` logic uses `periodStart`. If period boundaries shift, notification cooldowns reset.

#### Step-by-Step Fix Plan
1. **Fix BudgetCalculator rolling window logic**: Make the ROLLING branch use `calculatePeriodWindowForTime(budget.period, budget.startDate, now)` instead of hardcoded 30-day math.
2. **Add sequential window navigation APIs**: Add `nextWindow(period, currentWindow)` and `previousWindow(period, currentWindow)` to `BudgetCalculator` for safe rollover iteration.
3. **Delete BudgetForecastingEngine duplicate**: Inject `BudgetCalculator` into `BudgetForecastingEngine`, replace `calculateCurrentBudgetPeriodRange()` call with `budgetCalculator.calculatePeriodRange(budget, now)`, delete the ~87-line duplicate method.
4. **Delete SharedBudgetManager duplicate**: Inject `BudgetCalculator` into `SharedBudgetManager`, replace `getStartOfMonth(now)` with `budgetCalculator.calculatePeriodRange(budget, now)`, delete the `getStartOfMonth()` helper.

#### Testing Strategy
- Unit test: Rolling MONTHLY budget created Jan 15, evaluated June 20 → window is Jun 15 – Jul 15
- Unit test: Rolling MONTHLY budget created Jan 31, evaluated Feb 15 → window is Jan 31 – Feb 28/29 (coerced)
- Unit test: Rolling WEEKLY budget created on Wednesday, evaluated 3 weeks later → correct week boundaries
- Unit test: `nextWindow`/`previousWindow` chain from anchor produces contiguous non-overlapping windows
- All existing `BudgetCalculatorTest`, `BudgetCalculatorBoundaryTest`, `BudgetCalculatorGoldenTest`, `BudgetCalculatorStressTest` must pass

#### Rollback Plan
All changes are pure refactors (delete duplicates, fix calculation). No DB schema changes. Each batch is independently revertible via git. The `calculatePeriodWindowForTime` method (which is the correct implementation) is NOT being changed — only the code paths that bypass it are being fixed.

---

### CRITICAL-2: Budget forecasting and recommendation math double-counts spend

#### Root Cause Analysis
**Bug 1: `predictedRemaining` double-counts spend** (line 82): `predictedRemaining = budget.amount - spentToDate - predictedSpending`. But `predictedSpending` is computed as `historicalData.averageMonthly * months` where `months = forecastPeriodDays / 30.0`. This predicts the ENTIRE period's spending, then subtracting `spentToDate` on top means spend is counted twice.

**Bug 2: Fixed 30-day window** (lines 44, 192): `forecastPeriodDays` defaults to 30 and is passed directly to `calculatePredictedSpending`. But if we're 20 days into a 31-day period, only 11 days remain. The forecast should project 11 days of spending, not 30.

**Bug 3: Overspend probability incorrectly discounted by confidence** (line 286): `return probability * confidence`. Low confidence (0.5) means uncertainty, not that overspend is less likely. A low-confidence budget that looks like it's overspending should still report high probability.

**Bug 4: ViewModel derives `currentSpending` incorrectly** (line 53): `val currentSpending = budget.amount - forecast.predictedRemaining`. Since `predictedRemaining` is already double-counted, this back-derives a nonsensical value.

#### Dependency Map
```
BudgetForecastingEngine.generateForecast()
  ├── produces BudgetForecast entity → stored in BudgetForecastDao
  ├── consumed by BudgetForecastingViewModel
  │     ├── derives currentSpending (BUGGY derivation)
  │     └── calls BudgetRecommendationEngine.generateRecommendations()
  └── consumed by BudgetAutopilotEngine (out of scope)
```

#### Cross-Component Risks
1. **`BudgetForecast` entity schema**: The `predictedRemaining` field semantics will change. Existing persisted forecasts will have wrong values — old forecasts should be deactivated or treated as historical only.
2. **`BudgetAutopilotEngine`**: May consume `predictedRemaining` from `BudgetForecastDao.getLatestActiveForecast()`. After fix, these values change semantics.
3. **Recommendation thresholds**: `BudgetRecommendationEngine` checks `forecast.predictedRemaining < budget.amount * 0.5`. With corrected math, thresholds may need re-tuning.

#### Step-by-Step Fix Plan
1. **Fix forecast period**: In `generateForecast()`, compute remaining days instead of fixed 30: `val remainingDays = (totalPeriodDays - elapsedDays).coerceAtLeast(0)`. Pass `remainingDays` to `calculatePredictedSpending()`.
2. **Fix overspend probability**: Remove `probability * confidence` multiplication. Return `probability` directly. Confidence should be reported separately.
3. **Fix ViewModel derivation**: Add `spentToDate` field to `BudgetRecommendationForecast` data class. In ViewModel, derive `currentSpending` from `budget.amount - forecast.predictedRemaining - forecast.predictedSpending` or pass `spentToDate` through directly.
4. **Deprecate `forecastPeriodDays` parameter**: Mark as `@Deprecated` since period is now computed from the budget.

#### Testing Strategy
- Unit test: Budget €1000, 30-day period, day 20, spent €600. Average monthly = €900. Remaining days = 10. `predictedSpending` = 900 * (10/30) = €300. `predictedRemaining` = 1000 - 600 - 300 = €100.
- Unit test: Already overspent → probability = 1.0 regardless of confidence
- Unit test: Very comfortable budget → probability stays low (~0.05)

#### Rollback Plan
No schema changes needed if we derive `spentToDate` in the ViewModel from the corrected formula. Each batch can be reverted independently via git.

---

### CRITICAL-3: BudgetMonitor breaks structured concurrency

#### Root Cause Analysis
**Bug 1: `CancellationException` is swallowed** (lines 82-93): `catch (e: Exception)` catches `CancellationException`. The `isTransientError()` returns `false` for it, hitting the `else` branch and doing `return@launch` — but it does NOT rethrow. This violates Kotlin structured concurrency.

**Bug 2: Unsynchronized mutable state** (lines 33-36): `lastCheckTime`, `cachedStatuses`, `cacheTimestamp` are plain `var` fields accessed from various threads/coroutines with no synchronization.

**Bug 3: Cache never invalidated on budget writes**: The 30-second cache is only refreshed when it expires. When a user adds/edits/deletes a budget or adds an expense, the cache isn't cleared.

**Bug 4: `serviceScope` creates unmanaged coroutine scope**: Created at construction but `cleanup()` must be explicitly called. After `cleanup()`, `serviceJob` is cancelled permanently — calling `checkBudgets()` again silently fails.

#### Cross-Component Risks
1. Adding `Mutex` to `checkBudgets()`: If multiple callers fire simultaneously, `Mutex` will serialize them. This is desired behavior.
2. Cache invalidation API: Callers that trigger `checkBudgets()` should also be able to invalidate the cache.

#### Step-by-Step Fix Plan
1. **Fix CancellationException handling**: Add explicit `catch (e: CancellationException) { throw e }` BEFORE the `catch (e: Exception)` block.
2. **Guard mutable state**: Use `AtomicLong` for `lastCheckTime` with `compareAndSet`. Use `Mutex` for `cachedStatuses`/`cacheTimestamp` access inside the launched coroutine.
3. **Add cache invalidation**: Add `cacheInvalidated = AtomicBoolean(false)`. Set to true when `checkBudgets()` is called. Check in `getCachedBudgetStatuses()`.
4. **Harden lifecycle**: Make `serviceScope` recreatable. Replace `serviceJob.cancel()` with `serviceJob.cancelChildren()` in `cleanup()`.

#### Testing Strategy
- Unit test: launch `checkBudgets()`, cancel the scope → coroutine terminates promptly
- Stress test: 100 concurrent `checkBudgets()` calls → no data races, exactly 1 actually executes
- Unit test: Add expense → checkBudgets() → cache invalidated → fresh data used
- `BudgetMonitorTest` and `BudgetMonitorStressTest` pass

#### Rollback Plan
All changes are in a single file (`BudgetMonitor.kt`) + minor change in `ExpenseTrackerApp.kt`. No DB schema changes. Atomic/Mutex additions are additive — they don't change behavior under single-threaded execution.

---

### CRITICAL-4: Automated savings rules duplicate execution & lose cap state

#### Root Cause Analysis
1. **Weekly no-spend rule fires repeatedly per batch** (lines 214-248): `evaluateWeeklyNoSpendRule()` is stateless — no memory of whether it already fired for the current calendar week. Every invocation triggers independently.
2. **Monthly cap tracking lives only in memory** (line 53): `monthToDateRuleTotals` is a `mutableMapOf` on a `@Singleton`. Process restart = lost state.
3. **Missing input validation for percentage rules** (lines 85-109): `evaluatePercentageRule()` never validates that `rule.percentage` is finite, non-negative, and ≤ 100.

#### Step-by-Step Fix Plan
1. **Input validation**: Add guard in `evaluatePercentageRule()`: `if (!percentage.isFinite() || percentage <= 0.0 || percentage > 100.0) return null`.
2. **Weekly no-spend idempotency**: Add `private val executedRulePeriods = mutableSetOf<String>()`. Use calendar-week boundary (`TimePeriodUtils.getStartOfWeek(now)`) instead of rolling 7 days. Build dedup key = `"${ruleStableKey(rule)}-week-${buildWeekKey(now)}"`.
3. **Harden monthly cap**: Add validation for non-finite execution amounts. Add `@Suppress("DEPRECATION")` comment documenting the persistence gap.

#### Testing Strategy
- New test: percentage rule rejects NaN/negative/>100 percentage
- New test: weekly no-spend rule fires once per calendar week
- New test: weekly no-spend rule fires again in new week
- New test: monthly cap rejects non-finite execution amount

#### Rollback Plan
All changes are additive guards (return `null` earlier) — revert is a single commit revert with no schema impact.

---

### CRITICAL-5: SplitCalculator money math can overflow or hang

#### Root Cause Analysis
1. **`toCents()` overflow** (lines 229-234): Returns `Int` via `BigDecimal.toInt()`. For amounts > ~€21,474,836.47, cent value exceeds `Int.MAX_VALUE`, causing silent overflow.
2. **Negative-remainder loop can hang** (lines 208-221): When `remainder < 0`, the while-loop only decrements `remainingToRemove` when `current > 0`. If all member cents are already 0, the loop spins forever.

#### Step-by-Step Fix Plan
1. **Convert to Long**: Change `toCents()` return type from `Int` to `Long`. Change `.toInt()` to `.toLong()`. Change `fromCents(cents: Int)` parameter to `Long`. Change `PercentageShare.baseCents` from `Int` to `Long`.
2. **Add safety guards**: In positive remainder loop, add max-iterations guard. In negative remainder loop, track whether any progress was made per full cycle; if a full cycle completes with no progress, break.
3. **Update stress test**: Fix existing test that documents broken behavior — assert correct behavior instead.

#### Testing Strategy
- Test: `calculateEqualSplit(25_000_000.00, 2 members)` returns 12,500,000.00 each
- Test: `split of Int MAX boundary amount 21474836.47 is correct`
- Test: `percentage split with tiny percentages does not hang`
- Test: `percentage split where all members get 0 cents gracefully handles negative remainder`

#### Rollback Plan
Pure internal refactor (Int→Long, loop guard); API surface unchanged (`Map<Long, Double>`). If any downstream behavior changes, revert is a single commit.

---

### CRITICAL-6: Recurring obligations disappear from forecasts and synthesis

#### Root Cause Analysis
1. **`RecurringExpenseEngine.nextExpectedDate` is stale** (lines 95-102): Computed as one period after the last observed expense date. Never advanced forward. If a monthly bill was last seen Jan 15, `nextExpectedDate` = Feb 15. It is now April — the `nextExpectedDate` is still Feb 15.
2. **`SynthesisEngine` only counts patterns whose `nextExpectedDate` is in `[startOfToday, endOfMonth)`** (lines 110-111, 122-123): If `nextExpectedDate` is in the past, the pattern is filtered out entirely.
3. **`FinancialStressForecastEngine.calculateRecurringOutflows` starts iteration from stale `nextExpectedDate`** (lines 183-218): If `nextDate` (stale) is before `startDate`, the while loop never executes.

#### Dependency Map
- **Source of truth**: `RecurringExpenseEngine.getPatterns()` → produces `RecurringPattern` list
- **Direct consumers** (all affected): `SynthesisEngine`, `FinancialStressForecastEngine`, `FinancialWeatherRepository`, `ComputeMoneyRadarUseCase`, `CashFlowCalculator`, `InsightsEngine`, `FinancialHealthScoreV2`, `AnalyticsViewModel`

#### Step-by-Step Fix Plan
1. **Create centralized occurrence expansion utility**: Add `expandOccurrences(pattern, rangeStart, rangeEnd): List<Long>` that rolls `nextExpectedDate` forward if stale, then enumerates all occurrences in range.
2. **Roll `nextExpectedDate` forward in `getPatterns()`**: After computing `nextDate`, advance it forward until it reaches present/future.
3. **Fix `FinancialStressForecastEngine.calculateRecurringOutflows`**: Replace manual while-loop with call to `expandOccurrences()`.
4. **Fix `SynthesisEngine` to count all in-range occurrences**: Replace single `nextExpectedDate` filter with occurrence expansion.

#### Testing Strategy
- New test: `stale nextExpectedDate is rolled forward to future`
- New test: `expandOccurrences(monthlyPattern, apr1, jun30)` returns 3 occurrences
- New test: `recurring outflows count all occurrences in 90-day horizon` — weekly $50 pattern → ~12-13 × $50
- Existing tests: Verify all existing `RecurringExpenseEngineTest`, `SynthesisEngineTest`, `FinancialStressForecastEngineTest` pass

#### Rollback Plan
Batch 1 (utility function) is additive — can be deleted without breaking anything. Batch 2 (roll-forward in `getPatterns`) is the key behavioral change; revert restores stale dates. All changes are in-memory logic; no database migration.

---

### HIGH-1: Analytics/dashboard totals are inconsistent with `effectiveAmount` rules

#### Root Cause Analysis
Four distinct raw-amount bypass paths:
1. **`AdvancedAnalyticsDashboard.kt`** — ALL aggregation uses `expense.amount` (lines 93, 121, 147, 191, 231, 268, 275)
2. **`ExpenseDao.getAmountsForPercentileCalc()`** — uses raw `amount` (line 391-397)
3. **`ExpenseDao.getLargestExpenseForPeriod()`** — `ORDER BY amount DESC` (lines 583-590)
4. **`ExpenseDao.getMerchantStats()` / `getAllMerchantStats()`** — `AVG(amount)`, `MIN(amount)`, `MAX(amount)` (lines 519-522, 545-547)

#### Step-by-Step Fix Plan
1. **Fix DAO queries**: Replace raw `amount` references in analytics queries with the standard effectiveAmount CASE expression.
2. **Fix `AdvancedAnalyticsDashboard`**: Replace every `expense.amount` with `expense.effectiveAmount` and add `!expense.isNotMine` filtering.
3. **Fix `InsightsEngine`**: Change `merchant = ms.merchantName` to `merchant = ms.displayName`. Use `effectiveAmount` for largest transaction threshold and display.

#### Testing Strategy
- Unit test: Create expenses list with mix of shared, isNotMine, and normal — assert totals use effective amounts.
- New DAO instrumented tests: one with shared expense at 50% share, one with `isNotMine=true` — verify they're reflected correctly.

#### Rollback Plan
All changes are backward-compatible in schema (no migration needed). If the CASE expressions cause performance issues, add a computed column or index later.

---

### HIGH-2: Merchant analytics use incompatible identities and labels

#### Root Cause Analysis
Four incompatible merchant identity schemes in active use:
| Engine | Grouping Key | Display Label | Problem |
|--------|-------------|---------------|---------|
| `MerchantInsightEngine` (line 23) | `expense.merchant.lowercase()` | `expenses.first().merchant` | Lowercase ≠ merchantKey |
| `InsightsEngine.buildMerchantInsights` (line 378,381,388) | `expense.merchantKey` | `ms.merchantName` (=merchantKey!) | Surfaces canonical key in UI |
| `AdvancedAnalyticsEngine.getMerchantAnalytics` (line 251) | `expense.merchant` (raw text) | `merchant` (raw text) | Groups by raw text |
| `AdvancedAnalyticsDashboard.getTopMerchants` (line 146) | `expense.merchant` (raw text) | raw text | Same as above |

#### Step-by-Step Fix Plan
1. **Fix `MerchantInsightEngine`**: Change grouping to `expense.merchantKey ?: expense.merchant.lowercase()`. Keep display name from first expense.
2. **Fix `InsightsEngine.buildMerchantInsights`**: Change `merchant = ms.merchantName` to `merchant = ms.displayName`. Add fallback: `ms.displayName.ifBlank { ms.merchantName }`.
3. **Fix `AdvancedAnalyticsEngine.getMerchantAnalytics`**: Change `.groupBy { it.merchant }` to `.groupBy { it.merchantKey ?: it.merchant }`. Extract display name from first expense. Historical matching: use `it.merchantKey == merchantKey || it.merchant.equals(displayName, ignoreCase = true)`.
4. **Fix `AdvancedAnalyticsDashboard.getTopMerchants`**: Group by `expense.merchantKey ?: expense.merchant`. Track displayName alongside accumulation.

#### Testing Strategy
- Unit test: Two expenses having different `merchant` strings but same `merchantKey` — assert they merge into one `MerchantInsight`.
- Unit test: `recentTransactions` are sorted by date descending before `.take(5)`.

#### Rollback Plan
No schema changes, no migrations. All changes are in-memory grouping logic — easy to revert.

---

### HIGH-3: AdvancedAnalyticsEngine current-period math is wrong

#### Root Cause Analysis
1. **Future days counted as zero-spend days** (line 468): `periodDays` uses full period range, not elapsed days.
2. **DST-unsafe millisecond division** (lines 612, 619, 627): Sparkline/day buckets use `DAY_IN_MILLIS` division.
3. **January streak keys are 0-indexed** (line 803): Produces `"2024-00"` for January.
4. **`recentTransactions` are not sorted** (line 306-308): `.take(5)` on unsorted list.
5. **Historical overlap** (lines 240-242): Historical set includes current period's expenses.

#### Step-by-Step Fix Plan
1. **Cap period days**: Use `effectiveEnd = if (now in period.startMs until period.endMs) TimePeriodUtils.getEndOfDay(now) else period.endMs`. Use `TimePeriodUtils.daysBetween(period.startMs, effectiveEnd)`.
2. **Replace millisecond division**: Use `TimePeriodUtils.daysBetween()` for all day bucketing.
3. **Normalize streak keys**: Use `getMonth() + 1` (1-indexed). Update Dec→Jan boundary check.
4. **Sort recentTransactions**: `transactions.sortedByDescending { it.date }.take(5)`.
5. **Exclude current period from historical**: Change `getExpensesSince(historicalStart)` to `getExpensesBetween(historicalStart, period.startMs)`.

#### Testing Strategy
- Unit test: Period is current month (Jan 1 – Feb 1), now = Jan 15. Assert `periodDays == 15`, not 31.
- Unit test: Create expenses spanning a DST transition date. Assert sparkline day indices are correct.
- Unit test: Expenses in December 2024 and January 2025. Assert streak = 2.

#### Rollback Plan
All changes are in `AdvancedAnalyticsEngine.kt` — single file revert if needed.

---

### HIGH-4: Anomaly detection can miss obvious spikes

#### Root Cause Analysis
1. **Flat-baseline series produce no anomalies** (lines 187, 225, 271): When `IQR == 0` or `MAD == 0`, the detector returns `emptyList()`. A series like `[10, 10, 10, 10, 10, 100]` misses the obvious spike.
2. **Merge priority is inverted** (lines 129, 140): Enum ordinal comparison makes CONTEXTUAL (ordinal 3) the highest priority when it should be the lowest.

#### Step-by-Step Fix Plan
1. **Add zero-dispersion fallback**: When `IQR == 0` or `MAD == 0`, use a median/multiplier rule: flag amounts > `median * 2.0` or `median + 1.0`.
2. **Replace ordinal comparisons with explicit priority map**: `METHOD_PRIORITY = mapOf(MAD to 3, IQR to 2, CONTEXTUAL to 1, MULTIPLIER to 0)`.

#### Testing Strategy
- New test: flat baseline with one spike is detected
- New test: MAD takes priority over IQR and CONTEXTUAL in merged results
- Existing test: `anomaly_detector_false_positive_guard_on_tight_distribution` still passes

#### Rollback Plan
Pure computation changes — easy to revert.

---

### HIGH-5: Dashboard analytics pipeline is expensive and semantically divergent

#### Root Cause Analysis
1. **N+1 monthly queries** (lines 160-206): `getMonthlyTrend()` calls `getExpensesBetween()` inside a loop — 12 separate DB queries for 12-month range.
2. **Month-end boundary miss** (line 181): Uses `23:59:59` as inclusive end — misses transactions at 23:59:59.001+.
3. **Placeholder category labels** (line 133): `categoryName = "Category $catId"` — actual name never fetched.
4. **`changeFromLastPeriod` always 0.0** (line 136): Never calculated from historical data.
5. **Raw `amount` usage** throughout (lines 92-98, 118-138, 141-158, etc.).

#### Step-by-Step Fix Plan
1. **Fix raw amount → effectiveAmount**: Replace every `expense.amount` with `expense.effectiveAmount` and add `!expense.isNotMine` filtering.
2. **Eliminate N+1 queries**: Change `getMonthlyTrend` to accept the already-fetched expenses list. Group by month in-memory.
3. **Fix month-end boundary**: Use exclusive next-month start instead of `23:59:59`.
4. **Resolve placeholder category names**: Inject `CategoryRepository`, fetch categories once, look up real names.
5. **Fix `changeFromLastPeriod`**: In `DashboardContractsAdapter.observeCategoryBreakdown()`, compute previous period breakdown for comparison.

#### Testing Strategy
- Unit test: Create expenses list with mix of shared, isNotMine, and normal — assert totals use effective amounts.
- Unit test: No N+1 queries — verify `getExpensesBetween` called exactly once for the full range.
- Unit test: Month-end transaction at 23:59:59.500 is included in the correct month.

#### Rollback Plan
All changes are backward-compatible in schema. If the in-memory grouping causes performance issues, revert to per-month queries.

---

### HIGH-6: Totals and pacing analytics produce distorted timelines

#### Root Cause Analysis
1. **Zero-spend periods disappear** (lines 37-54, 74-101, 114-158): `GROUP BY` inherently excludes periods with no transactions. Charts have gaps, averages are inflated.
2. **Budget adherence compares ~3 months of spend against single monthly budget** (lines 245-278): `categorySpending / budget.amount` compares 3 months of spend to 1 month budget.
3. **Spending variance excludes zero-spend days** (lines 223-239): `groupBy` only produces groups with at least one purchase.

#### Step-by-Step Fix Plan
1. **Zero-fill monthly totals**: After fetching DAO results, materialize all 12 months, filling missing ones with `totalAmount = 0.0`.
2. **Zero-fill weekly totals**: Enumerate all ISO weeks within the month range, emit zero for missing weeks.
3. **Zero-fill daily totals**: Enumerate all days in range, emit zero for missing days.
4. **Fix budget adherence**: Normalize spending to monthly cadence: `monthlySpending = totalSpending / spanMonths`.
5. **Fix spending variance**: Include zero-spend days: `allDailyAmounts = daysWithSpend + List(zeroDayCount) { 0.0 }`.

#### Testing Strategy
- Unit test: `getMonthlyTotals(2024)` returns exactly 12 items, including zero-spend months.
- Unit test: `getDailyTotalsForRange(weekStart, weekEnd)` returns exactly 7 items.
- Unit test: Budget adherence for 3 months of category spend at 1× monthly budget = ~1.0 adherence.

#### Rollback Plan
Pure computation changes on top of DAO results — no persistence changes. Easy to revert.

---

### HIGH-7: Shared-budget calculations bypass core budget semantics

#### Root Cause Analysis
1. **Raw `expense.amount` used instead of `expense.effectiveAmount`** (line 45).
2. **Fixed month-to-date window instead of budget's actual period** (lines 33, 36).
3. **Overall budget (categoryId == null) filters only null-category expenses** (line 37).
4. **No filtering for PURCHASE transactions or `isNotMine`** (lines 37-41).
5. **`getMemberContributions` returns fabricated zero-value placeholders** (lines 67-81).

#### Step-by-Step Fix Plan
1. **Inject `BudgetCalculator`** into `SharedBudgetManager`.
2. **Replace `getStartOfMonth(now)`** with `budgetCalculator.calculatePeriodRange(budget, now)`.
3. **Replace in-memory filter+sum** with DAO aggregate queries: `expenseDao.getCategorySpentInPeriod()` for category budgets, `expenseDao.getTotalSpentBetween()` for overall budgets.
4. **Mark `getMemberContributions` as `@Deprecated`** with clear message.
5. **Delete `getStartOfMonth()`** private helper.

#### Testing Strategy
- Unit test: Shared budget with weekly period uses correct 7-day window.
- Unit test: Shared budget with yearly period uses correct annual window.
- Unit test: Overall budget counts ALL categories' spend.

#### Rollback Plan
All changes are in 2 production files + 1 test file. No DB schema changes. Git revert is straightforward.

---

### HIGH-8: Budget autopilot recommendations are not portfolio-safe

#### Root Cause Analysis
1. **Total budgets double-counted** (lines 138-139): When overall and category budgets coexist, both are summed into `totalCurrentBudget`.
2. **Sparse/empty histories rewarded with unjustified confidence** (lines 319-337): `historicalSpend.size / 100.0` with 0-3 data points gives 0.0-0.03 — negligible. But volatility bonus adds +0.2 for CV < 0.1, which is always true for empty history (CV = 0.0). Result: empty history → confidence 0.7.

#### Step-by-Step Fix Plan
1. **Separate overall-vs-category portfolio views**: Partition budgets into `overallBudgets` and `categoryBudgets`. Only sum `categoryBudgets` into totals. Track overall budgets separately.
2. **Require minimum history before confidence bonuses**: If `historicalSpend.size < MIN_HISTORY_MONTHS` (3), cap confidence at 0.3 and skip volatility bonuses. If `historicalSpend.size < 2`, return 0.15.
3. **Change `calculateVolatility()`**: When `historicalSpend.size < 2`, return 1.0 (maximum uncertainty) instead of 0.0.

#### Testing Strategy
- Unit test: When overall budget + category budgets coexist, `totalCurrentBudget` and `totalRecommendedBudget` only reflect category budgets.
- Unit test: Empty spend history applies bounded decrease — confidence ≤ 0.3.
- Unit test: 3+ months of stable history gets confidence ≥ 0.7.

#### Rollback Plan
Pure internal refactor. API surface unchanged. Revert is a single commit.

---

### HIGH-9: Forecast persistence/contracts are incomplete

#### Root Cause Analysis
1. **Superseded forecasts remain active** (lines 96-97): `budgetForecastDao.insert(forecast)` inserts without deactivating previous active forecast.
2. **`updateForecastAccuracy()` never resolves a real forecast row** (lines 422-435): Calls `getForecastsForBudget(forecastId)` which takes a budgetId, not forecastId. The Flow is never collected — the lambda returns `null` unconditionally.
3. **Divergent DTOs/enums**: `BudgetForecast` uses `ForecastRiskLevel`; `BudgetRecommendationEngine` uses `BudgetRecommendationRiskLevel`. No mapping code visible.

#### Step-by-Step Fix Plan
1. **Add missing DAO queries**: `getById(forecastId)` and `deactivateAllForBudget(budgetId)`.
2. **Deactivate prior active forecasts**: Before `budgetForecastDao.insert(forecast)`, call `budgetForecastDao.deactivateAllForBudget(budget.id)`.
3. **Implement `updateForecastAccuracy()` properly**: Use `getById()` to resolve the forecast row, compute accuracy = `1 - abs(predicted - actual) / max(predicted, 1.0)`, call `update()`.
4. **Unify risk level enums**: Use `ForecastRiskLevel` as the single canonical enum. Delete `BudgetRecommendationRiskLevel`. Update all references.

#### Testing Strategy
- Unit test: When `generateForecast` is called twice for the same budget, `deactivateAllForBudget` is called before the second insert.
- Unit test: `updateForecastAccuracy` with actual data — predicted=100, actual=90 → accuracy=0.9.
- Unit test: Forecast not found path — logs warning, returns early.

#### Rollback Plan
DAO changes are additive only (new queries). Engine changes are pure refactors. Enum unification is the highest-risk step — keep the old enum as a `typealias` during transition if needed.

---

### HIGH-10: Savings recommendations double-count headroom

#### Root Cause Analysis
1. **Same headroom counted across overall + category budgets**: `calculateBudgetSurplus()` sums every positive remaining budget. If both an overall budget and category budgets are present, the same available headroom is counted multiple times.
2. **Recommendation returned per-goal**: The computed recommendation is effectively portfolio-wide, but returned independently for each goal. Multiple goals can each receive the same full "safe to save" amount.

#### Step-by-Step Fix Plan
1. **Reuse the same budget-headroom selection policy** as `MonthlySavingsSweepUseCase`: prefer the overall budget when present, otherwise sum category budgets only.
2. **Split the API** into "safe amount available" plus a separate goal-allocation step, or cap/allocate by goal remaining amount and priority.

#### Testing Strategy
- Unit test: When overall + category budgets coexist, headroom is not double-counted.
- Unit test: Multiple goals share the same portfolio-level safe amount, not each receiving the full amount.

#### Rollback Plan
Pure internal refactor. Revert is a single commit.

---

### HIGH-11: Financial health has no canonical budget/score model

#### Root Cause Analysis
1. **Two independent health score calculators** with different methodologies, different score components, different time period handling.
2. **Budget adherence/headroom can double-count overall + category budgets** in both systems.

#### Step-by-Step Fix Plan
1. **Choose one canonical health KPI** (or clearly gate legacy output).
2. **Reuse a shared budget aggregation policy** everywhere budget headroom is consumed.

#### Testing Strategy
- Unit test: Both health score systems produce consistent results for the same input data.
- Integration test: Dashboard shows only one canonical health score.

#### Rollback Plan
Deprecate one calculator or feature-flag the legacy score.

---

### HIGH-12: FinancialHealthCalculator misclassifies income as spending

#### Root Cause Analysis
1. **Deposits/transfers contaminate spending and volatility calculations**.
2. **Week-boundary inconsistency**: `getStartOfWeek` uses locale-dependent `firstDayOfWeek` while `TimePeriodUtils.getStartOfWeek` always uses Monday.
3. **Zero-budget divide-by-zero paths**.

#### Step-by-Step Fix Plan
1. **Filter to spending transaction types only** before summing.
2. **Standardize on one week-boundary helper** (`TimePeriodUtils.getStartOfWeek`).
3. **Guard zero-budget cases explicitly**: `if (dailyBudget <= 0.0) return 25`.

#### Testing Strategy
- Unit test: Large income deposit does not tank the health score for the day.
- Unit test: All budgets set to amount=0.0 → dailyBudget=0 → handled gracefully.

#### Rollback Plan
Pure computation changes. Easy to revert.

---

### HIGH-13: Stress forecast inputs and confidence are systematically biased

#### Root Cause Analysis
1. **"Current balance"/income inferred from monthly net flow or budget caps** — not real cash/income.
2. **Zero-spend days excluded from bootstrap sampling**.
3. **Unusable fits can still receive high confidence**.

#### Step-by-Step Fix Plan
1. **Require real cash/income inputs** (or degrade gracefully when absent).
2. **Include zero-spend frequency in empirical distributions**.
3. **Cap unusable fits to `LOW`/`MODERATE` confidence**.

#### Testing Strategy
- Unit test: Stress forecast with no real cash/income inputs degrades gracefully.
- Unit test: Zero-spend days are included in bootstrap sampling.

#### Rollback Plan
Pure computation changes. Easy to revert.

---

### HIGH-14: Historical spending distribution uses locale/DST-unsafe bucketing

#### Root Cause Analysis
1. **Fixed 24h/7d millisecond bucketing around DST**.
2. **Locale-dependent `Calendar` week-start logic** shifts the entire lookback window.

#### Step-by-Step Fix Plan
1. **Derive day/week boundaries only through shared calendar-aware helpers** (`TimePeriodUtils`/`java.time`).
2. **Reuse them for both lookback windows and bucket assignment**.

#### Testing Strategy
- Unit test: Create expenses spanning a DST transition date. Assert day indices are correct.
- Unit test: Run on US-locale device. Assert lookback window is not shifted.

#### Rollback Plan
Pure computation changes. Easy to revert.

---

### HIGH-15: Custom split validation is inconsistent across the group-expense stack

#### Root Cause Analysis
1. **Split data validated against current member list** instead of historical participant set.
2. **Budget-offset logic reparses payloads permissively**.
3. **Invalid data silently falls back to equal split**.

#### Step-by-Step Fix Plan
1. **Persist participant snapshots/membership versions** on expenses.
2. **Route every parse through `CustomSplitParser`**.
3. **Surface invalid legacy splits explicitly** instead of rewriting meaning.

#### Testing Strategy
- Unit test: Split validated against historical participant set, not current members.
- Unit test: Invalid legacy split surfaces error instead of falling back to equal split.

#### Rollback Plan
Pure internal refactor. Revert is a single commit.

---

### MEDIUM-1: Secondary analytics state is not concurrency-safe

#### Root Cause Analysis
1. **Non-thread-safe cache** in `SpendingThresholdCalculator`.
2. **Transfer correction bookkeeping assumes initial correctness** and pruning is nondeterministic.

#### Step-by-Step Fix Plan
1. **Use `ConcurrentHashMap`** for threshold cache.
3. **Persist initial transfer correctness** and prune deterministically with ordered/LRU metadata.

#### Testing Strategy
- Stress test: Multiple concurrent calls to `calculatePercentiles()` for different users.
- Unit test: Transfer pruning removes oldest entries deterministically.

#### Rollback Plan
Pure internal refactor. Easy to revert.

---

### MEDIUM-2: Analytics engines are still duplicated and inefficient

#### Root Cause Analysis
1. **Extracted engines still coexist with parallel implementations**.
2. **Previous-period category scans are O(categories × expenses)**.
3. **Weekday ordering is inconsistent**.

#### Step-by-Step Fix Plan
1. **Make `InsightsEngine` a coordinator only** — delegate to focused engines.
2. **Pre-group previous-period data once** instead of scanning per category.
3. **Return stable Monday→Sunday ordering** from the shared analyzer.

#### Testing Strategy
- Unit test: `InsightsEngine` delegates to `MonthlyComparisonCalculator`, `CategoryInsightEngine`, `DayOfWeekAnalyzer`.
- Unit test: Weekday ordering is Monday→Sunday.

#### Rollback Plan
Pure internal refactor. Easy to revert.

---

### MEDIUM-3: Savings gamification is built on placeholders

#### Root Cause Analysis
1. **Streaks and monthly contributions inferred from goal metadata** instead of contribution history.
2. **`unlockedAt` changes on each read**.
3. **Some progress formulas can emit `NaN`**.

#### Step-by-Step Fix Plan
1. **Persist contribution and unlock events**.
2. **Switch to calendar-aware date math**.
3. **Guard zero-target goals explicitly**: `if (it.targetAmount > 0) it.currentAmount / it.targetAmount else 0.0`.

#### Testing Strategy
- Unit test: `targetAmount = 0.0` goal does not produce NaN progress.
- Unit test: `unlockedAt` is stable across multiple calls.

#### Rollback Plan
Pure internal refactor. Easy to revert.

---

### MEDIUM-4: Smart savings horizon math is internally inconsistent

#### Root Cause Analysis
1. **Week/quarter horizons derived by scaling month-end Monte Carlo forecast**.
2. **Discretionary history divided by fixed 3 months**.
3. **Malformed negative effective amounts create negative projections**.

#### Step-by-Step Fix Plan
1. **Restrict Monte Carlo path to monthly mode** or add horizon-specific forecasting.
2. **Divide by actual historical months** instead of hardcoded 3.0.
3. **Clamp malformed negative aggregate inputs**: `.coerceAtLeast(0.0)`.

#### Testing Strategy
- Unit test: New user with 2 weeks of history — `monthlyDiscretionary` is correctly projected.
- Unit test: Negative effectiveAmount does not produce negative projections.

#### Rollback Plan
Pure internal refactor. Easy to revert.

---

### MEDIUM-5: FinancialHealthScoreV2 trend/error handling is misleading

#### Root Cause Analysis
1. **Recalculations compare a score against the same period's latest row**.
2. **Broad exception handling returns synthetic `50/STABLE` result**.

#### Step-by-Step Fix Plan
1. **Compare only against prior periods**: Filter by `periodStart < currentPeriodStart` in `determineTrend`.
2. **Add explicit error metadata** to the result contract (`isError: Boolean` or `errorMessage: String?`).

#### Testing Strategy
- Unit test: Calculate health score twice in quick succession for same period. Assert trend compares against previous period, not same period.
- Unit test: Force DB error during calculation. Assert result has `isError = true`.

#### Rollback Plan
Pure internal refactor. Easy to revert.

---

### MEDIUM-6: Precision/tolerance edge cases still need hardening

#### Root Cause Analysis
1. **Boundary-valid split percentages can fail because of `Double` math**.
2. **Qualifying-week logic diverges between simulator/distribution fit**.
3. **Conservative pace heuristics remain poorly calibrated**.

#### Step-by-Step Fix Plan
1. **Move split validation to minor-unit/basis-point math**.
2. **Share the same qualification/statistics rules** across simulation paths.
3. **Tune conservative pace heuristics** based on real-world data.

#### Testing Strategy
- Unit test: Split percentages at tolerance boundary (e.g., 99.99% + 0.01%) validate correctly.
- Unit test: Simulator and distribution fit use same qualifying-week logic.

#### Rollback Plan
Pure internal refactor. Easy to revert.

---

## Execution Order

```
CRITICAL-1 (Budget period logic) ──→ CRITICAL-2 (Forecast math) ──→ CRITICAL-3 (BudgetMonitor)
        ↑                                      ↑                            ↑
   Foundation:                            Depends on C1:                Independent but
   Period ranges are                      correct periods               verify after C1
   used by all                            for remaining-days
                                          calculation

CRITICAL-4 (Savings rules) ── Independent
CRITICAL-5 (SplitCalculator) ── Independent
CRITICAL-6 (Recurring obligations) ── Independent

HIGH-1 (effectiveAmount) ──→ HIGH-2 (Merchant identity) ──→ HIGH-3 (Analytics time math)
        ↑                                      ↑                            ↑
   Foundation:                            Depends on H1:                  Independent but
   DAO queries use                        merchant analytics              verify after H1
   effectiveAmount                        uses DAO outputs
```

**Recommended execution order**:
1. CRITICAL-1 → CRITICAL-2 → CRITICAL-3 (budget stack)
2. CRITICAL-4, CRITICAL-5, CRITICAL-6 (independent criticals)
3. HIGH-1 → HIGH-2 → HIGH-3 (analytics stack)
4. HIGH-4 through HIGH-15 (remaining highs, can be parallelized where independent)
5. MEDIUM-1 through MEDIUM-6 (medium priority items)
