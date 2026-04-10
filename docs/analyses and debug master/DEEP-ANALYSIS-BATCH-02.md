# Deep Analysis — Batch 02: Budget Engines

## Scope
- domain/budget/BudgetCalculator.kt
- domain/budget/BudgetForecastingEngine.kt
- domain/budget/BudgetAutopilotEngine.kt
- domain/budget/BudgetMonitor.kt
- domain/budget/BudgetRecommendationEngine.kt
- domain/budget/SharedBudgetManager.kt
- domain/budget/BudgetModels.kt (not found in codebase)
- domain/budget/BudgetRecommendationModels.kt

## @reviewer Findings

### Per-File Issues
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | domain/budget/BudgetCalculator.kt:40-49 | CRITICAL | Bug | Rolling weekly/monthly budgets are pinned to the original `startDate` and monthly uses a fixed `+30 days` window. After the first cycle, status/notifications/rollover no longer track the active period correctly. | Compute the anchored recurring window that contains `now` for every rolling period, and use calendar month arithmetic instead of a 30-day approximation. **[RESOLVED BY A.5]** |
| 2 | domain/budget/BudgetCalculator.kt:64-68 | HIGH | API | `calculatePeriodWindow(anchorDate)` is time-dependent because it silently uses `timeProvider.now()`. Callers cannot reliably iterate historical/next periods from an anchor, which already makes rollover logic fragile. | Split this into explicit APIs such as `windowContaining(anchor, evaluationTime)` and `nextWindowFrom(windowEnd)`, and remove the implicit `now`. **[RESOLVED BY A.5]** |
| 3 | domain/budget/BudgetCalculator.kt:77-81 | MEDIUM | Bug | Daily windows ignore the anchor time and always reset at local midnight, despite the class contract saying daily budgets are a 24-hour window starting from the anchor date. | Preserve the anchor time-of-day for rolling daily budgets, or narrow the documented contract/UI so daily budgets are explicitly calendar-day based. |
| 4 | domain/budget/BudgetForecastingEngine.kt:42-60,81-94 | HIGH | Bug | Forecasts always project `forecastPeriodDays` (default 30) instead of the actual remaining time in the active budget period. Weekly, daily, and yearly budgets therefore get materially wrong `predictedRemaining`, risk, and overspend outputs. | Derive the forecast horizon from `periodEnd - now` for “current budget” forecasts, or store a separate target horizon instead of reusing the budget period fields. |
| 5 | domain/budget/BudgetForecastingEngine.kt:264-286 | HIGH | Bug | `overspendProbability` is multiplied by `confidence`, so low-confidence data can make an over-budget projection look safer. A projected overspend can drop well below 100% probability purely because the model is uncertain. | Keep probability and confidence separate; never discount deterministic/projected overspend below its base probability. |
| 6 | domain/budget/BudgetForecastingEngine.kt:124-180 | MEDIUM | Analytics | Historical spending only includes months that had transactions. Zero-spend months disappear, inflating averages/trends/confidence for intermittent categories. | Build a contiguous month series for the full lookback window and fill missing months with `0.0`. |
| 7 | domain/budget/BudgetForecastingEngine.kt:422-435 | HIGH | Bug | `updateForecastAccuracy()` is unfinished and even queries `getForecastsForBudget(forecastId)`, mixing forecast IDs with budget IDs. The public API currently never updates any accuracy data. | Add a DAO lookup by forecast ID, compute accuracy, and persist `actualSpending`/`forecastAccuracy` in a real update path. |
| 8 | domain/budget/BudgetAutopilotEngine.kt:71-75,138-153 | HIGH | Bug | Aggregate totals sum every active budget together. If the user has an overall budget plus category budgets, `totalCurrentBudget`, `totalRecommendedBudget`, and `overallDelta` double-count the same money. | Separate overall budgets from category rollups, or compute portfolio totals only across mutually exclusive budgets. |
| 9 | domain/budget/BudgetAutopilotEngine.kt:193-200 | MEDIUM | Consistency | Month bucketing uses UTC while the rest of the budget stack uses local calendar boundaries. Expenses near local month boundaries can be assigned to the wrong month here. | Reuse the same local-time month boundary helper used by the rest of the budget/analytics pipeline. |
| 10 | domain/budget/BudgetAutopilotEngine.kt:247-257,319-337 | HIGH | Bug | Empty or one-point histories get `volatility = 0`, then `calculateRecommendationConfidence()` rewards that as “very stable,” producing ~0.7 confidence with effectively no evidence. | Gate low-history cases before applying volatility bonuses, and cap confidence aggressively until enough periods exist. |
| 11 | domain/budget/BudgetAutopilotEngine.kt:25-32,67-115 | MEDIUM | Architecture | The class injects `InsightsEngine`, `SpendingPaceCalculator`, and `MonteCarloSpendingSimulator`, and its docs claim to use them, but the implementation ignores all three and runs a separate heuristic pipeline. | Either integrate the promised engines or remove the unused dependencies/docs and centralize recommendation logic in one place. |
| 12 | domain/budget/BudgetMonitor.kt:33-37,63-76,110-120 | HIGH | Concurrency | `lastCheckTime`, `cachedStatuses`, and `cacheTimestamp` are unsynchronized mutable singleton state. Multiple repositories trigger `checkBudgets()` concurrently, and the 30s cache can reuse stale statuses immediately after new writes, delaying or missing alerts. | Guard state with a `Mutex`/atomics and invalidate or bypass the cache for explicit post-write checks. **[RESOLVED BY A.8]** |
| 13 | domain/budget/BudgetRecommendationEngine.kt:60-67 | MEDIUM | Bug | `potentialSavings` can go negative because it uses `forecast.predictedSpending - remaining` without clamping. A “reduce spending” recommendation can therefore report negative savings. | Compute projected overspend explicitly and clamp savings to `>= 0`. |
| 14 | domain/budget/BudgetRecommendationEngine.kt:11-18,146-191 | MEDIUM | Architecture | The domain engine owns `UiText`, hard-coded English descriptions, emoji, hex colors, and a formatted summary string. That mixes domain policy with presentation and makes localization/theming harder. | Return semantic recommendation data only, and move colors/emojis/summary formatting to the presentation layer. |
| 15 | domain/budget/SharedBudgetManager.kt:27-61 | HIGH | Bug | Shared budget progress always uses month-to-date expenses, raw `amount`, and ignores transaction type/member filtering. Results are wrong for weekly/yearly/rolling budgets and inconsistent with the app’s `effectiveAmount` budget semantics. | Reuse `BudgetCalculator` plus the same spend filters as `BudgetRepository`, and apply real member scoping before aggregating. |
| 16 | domain/budget/SharedBudgetManager.kt:67-80 | HIGH | Architecture | `getMemberContributions()` returns fabricated zero-value placeholders for every member. This exposes a public API that looks real but cannot produce truthful data. | Hide/disable the feature until member-linked expenses exist, or implement member attribution and compute actual contributions. |

### Cross-Component Issues
| # | Components | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | BudgetCalculator.kt, BudgetForecastingEngine.kt, BudgetRepository.kt | CRITICAL | Bug | Budget period math exists in multiple places and already disagrees. `BudgetCalculator` freezes rolling weekly/monthly windows, while `BudgetForecastingEngine` carries its own separate range logic, so status, rollover, monitoring, and forecasting can disagree for the same budget. | Make `BudgetCalculator` the single source of truth for period windows and expose explicit APIs for current-window lookup and sequential window iteration. |
| 2 | BudgetForecastingEngine.kt, BudgetRecommendationEngine.kt, BudgetForecastingViewModel.kt | HIGH | Contract | The recommendation engine expects actual `currentSpending`, but the UI passes `budget.amount - forecast.predictedRemaining`, which already includes projected future spend. Recommendation thresholds then treat forecasted spend as if it were already spent. | Add `spentToDate` to the forecast contract or rename/reshape the recommendation input so only actual spend can be supplied. |
| 3 | BudgetForecastingEngine.kt, BudgetForecastDao.kt | HIGH | Lifecycle | New forecasts are inserted as active, but nothing deactivates superseded rows. DAO APIs still query “active” forecasts, so multiple active forecasts can accumulate for one budget/period and later lookups become ambiguous. | Deactivate previous active forecasts for the same budget/target period in the same transaction before inserting the new forecast, and make DAO lookups deterministic. |
| 4 | SharedBudgetManager.kt, BudgetRepository.kt, BudgetMonitor.kt | HIGH | Consistency | Shared-budget calculations use different period and spend semantics than the core budget pipeline. The same budget can therefore show different progress depending on whether the app asks the shared manager or the main repository/monitor path. | Route shared-budget progress through the same period/spend primitives used by the main budget stack. |

### Overlapping Functionality
| # | Files | Description | Recommendation |
|---|-------|-------------|----------------|
| 1 | BudgetCalculator.kt, BudgetForecastingEngine.kt | Budget period/window logic is implemented twice with diverging rules. | Consolidate all budget period math behind `BudgetCalculator` and delete the duplicate forecasting implementation. |
| 2 | BudgetForecastingEngine.kt, BudgetAutopilotEngine.kt | Both engines derive trends/confidence from recent history with separate heuristics and no shared calibration. | Extract shared forecasting primitives or clearly separate “forecast” vs “autopilot” responsibilities with common inputs. |
| 3 | BudgetAutopilotEngine.kt, BudgetRecommendationEngine.kt | Two separate systems generate budget advice using different models, thresholds, and output DTOs. | Define one recommendation policy/model layer and have each feature adapt it for its UI/use case. |
| 4 | BudgetRecommendationEngine.kt, BudgetRecommendationInputs.kt | Recommendation DTOs are split across the engine file and a separately named inputs file, while the planned `BudgetRecommendationModels.kt` file no longer exists. | Consolidate recommendation models into one canonical models file and update the batch plan/tooling to match the real source layout. |

### Summary
- Total issues: 20
- Files with issues: 6/8
