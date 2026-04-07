# Deep Analysis — Batch 40: Forecasting & Groups (@reviewer)

## Scope
- `domain/forecasting/DataQualityAssessor.kt`
- `domain/forecasting/FinancialStressForecastEngine.kt`
- `domain/forecasting/HistoricalSpendingDistribution.kt`
- `domain/forecasting/MonteCarloResult.kt`
- `domain/forecasting/MonteCarloSpendingSimulator.kt`
- `domain/groups/GroupTransactionCoordinator.kt`
- `domain/groups/SettlementCalculator.kt`
- `domain/groups/SharedExpenseBudgetOffsetEngine.kt`
- `domain/groups/SharedExpenseManager.kt`
- `domain/groups/SharedExpensePort.kt`
- `domain/groups/usecase/AddGroupExpenseUseCase.kt`
- `domain/groups/usecase/DeleteGroupMemberUseCase.kt`
- `domain/groups/usecase/DeleteGroupUseCase.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `DataQualityAssessor.kt:72-96` | HIGH | Confidence / logic | `!fit.isUsable` only zeros the fitness component, so a fit with `sigma == 0` can still score `HIGH` confidence from volume+density+recency alone. `MonteCarloSpendingSimulator` then surfaces a deterministic fallback forecast with an overstated trust label. | Short-circuit unusable fits to `LOW` confidence (or hard-cap at `MODERATE`) before computing the weighted score. |
| 2 | `HistoricalSpendingDistribution.kt:135-156` | HIGH | Incorrect calculation | Week/day bucketing uses fixed `24h` / `7*24h` millisecond division. Around DST transitions, transactions can fall into the wrong day/week bucket, which changes qualifying-week counts and the fitted distribution. | Build week buckets from calendar boundaries (`java.time`, `Calendar`, or `TimePeriodUtils.getStartOfWeek`) instead of raw millisecond arithmetic. |
| 3 | `MonteCarloSpendingSimulator.kt:235-249` | MEDIUM | Confidence / logic | `countRecentQualifyingWeeks()` treats any recent week with `total > 0` as “qualifying”, but the actual distribution filter requires at least 3 distinct transaction-days. Sparse recent weeks therefore inflate confidence. | Carry forward real week-quality metadata from `HistoricalSpendingDistribution` and compute recency from the same qualification rule used for fitting. |
| 4 | `FinancialStressForecastEngine.kt:195-214` | HIGH | Forecasting bug | Recurring obligations are counted only if `nextExpectedDate` is already inside `[startDate, endDate]`. Stale-but-still-active patterns (for example, a monthly bill whose next date is already in the past) are never advanced into the horizon and disappear from the forecast. | Roll each pattern forward until it reaches the horizon start (or exceeds the horizon end), then count all in-range occurrences. |
| 5 | `FinancialStressForecastEngine.kt:223-239,443-459` | HIGH | Incorrect business model | The engine presents `P(balance < 0)` but computes “current balance” as *this month’s deposits minus this month’s purchases*, and even falls back to `totalBudget` as a proxy for income. That is not account balance or cash-on-hand, so crunch probability can be materially wrong. | Feed the engine an actual balance / income source, or explicitly degrade / withhold the balance-based forecast when those inputs are unavailable. |
| 6 | `FinancialStressForecastEngine.kt:273-279,315-325` | HIGH | Incorrect formula | The discretionary bootstrap drops zero-spend days and samples only from positive-spend days for every simulated future day. Users who do not spend daily get systematically inflated discretionary projections and crunch probability. | Build the empirical daily distribution over the full lookback window including zero-spend days, or model spend frequency separately from spend amount. |
| 7 | `FinancialStressForecastEngine.kt:66-88,223-239,245-339` | MEDIUM | Performance | `computeStressForecast()` calculates 30/60/90-day horizons separately, but each horizon re-queries the same 90-day deposit history and the same 60-day expense history, then rebuilds the same Monte Carlo input from scratch. | Load deposit history and the empirical discretionary distribution once per forecast run and reuse the prepared inputs across all horizons. |
| 8 | `SharedExpenseManager.kt:115-171` | HIGH | Data integrity | `addExpense()` never validates that `groupId` exists or that `paidById` belongs to that group before inserting. Because the DB foreign key on `paidById` only checks that the member exists somewhere, callers can create cross-group payer links and corrupt downstream balance calculations. | Validate group existence and payer membership in the manager before insert, or route writes through a coordinator/repository method that enforces the invariant transactionally. |
| 9 | `SharedExpenseBudgetOffsetEngine.kt:83-90,195-200` | HIGH | Incorrect calculation | `totalSharedSpend` tracks *my own liability*, while `totalReimbursed` tracks money repaid to me when I was the payer. `getPendingReimbursement()` subtracts these unrelated quantities, so the sign/amount is wrong (for example, a payer who is still owed money can show a negative “pending reimbursement”). | Track “I owe” and “owed to me” separately; compute pending reimbursement from payer receivables, not from `myShare - reimbursedAmount`. |
| 10 | `SharedExpenseBudgetOffsetEngine.kt:106-115` | HIGH | Error handling | Any exception during shared-expense budget offset calculation is swallowed and replaced with an all-zero breakdown. The budget UI cannot distinguish failure from genuine zero shared spend, so it may silently underreport spend and show a healthier budget than reality. | Propagate a typed error / failure state, or return `null`/partial fallback so callers can fall back to raw spend instead of zeroing the adjustment. |
| 11 | `SharedExpenseBudgetOffsetEngine.kt:170-178` | MEDIUM | Logic / settlement state | `isExpenseFullySettled()` derives `myShare` from `myShareAmount` or simple equal split, ignoring `CUSTOM_AMOUNT`, `CUSTOM_PERCENT`, and `UNEQUAL` payloads. Custom-split expenses can therefore be marked settled/unsettled incorrectly. | Reuse the same split-resolution logic used elsewhere (or persist a participant/share snapshot) when computing expected reimbursement. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | `FinancialStressForecastEngine` ↔ `MonteCarloSpendingSimulator` ↔ `DataQualityAssessor` | HIGH | Duplication / inconsistent forecasting | The stress engine injects `MonteCarloSpendingSimulator` and `SynthesisEngine` but re-implements its own spending Monte Carlo, income model, and recurring-occurrence logic instead of reusing shared forecasting primitives. The app can therefore show inconsistent risk results across month-end forecast vs stress forecast surfaces. | Extract one shared forecasting pipeline for obligation expansion, discretionary-history preparation, confidence scoring, and percentile generation, and make both consumers call it. |
| 2 | `DeleteGroupMemberUseCase` / `SharedExpenseManager.removeMember()` ↔ `SharedExpenseManager.calculateSplits()` ↔ `SharedExpenseBudgetOffsetEngine.calculateMyShare()` | HIGH | Historical data drift | Group expenses do not persist a participant snapshot. After member deletion, historical equal-split expenses are recomputed against the *current* member list, so old balances and budget liability can change retroactively even though the expense itself did not change. | Persist participant/member-count snapshots per group expense, or block deletion of members who participated in any historical expense whose split depends on current membership. |
| 3 | `SharedExpenseManager` / `SettlementCalculator` ↔ `SplitCalculator` / `SharedExpenseGroupsViewModel` | MEDIUM | Duplicate logic / authority split | The batch files introduce domain-side split/balance/settlement logic, but the groups UI still computes balances through `SplitCalculator` directly instead of using the domain services. Fixes in `SharedExpenseManager` / `SettlementCalculator` therefore do not automatically reach the user-facing groups pipeline. | Make the domain services the single source of truth and remove or fully delegate the legacy `SplitCalculator` path. |

## Summary
- Total issues: 11
- Critical: 0, High: 8, Medium: 3, Low: 0
- Files with issues: 6/13

## Key Patterns
- Forecasting logic is split across multiple engines with inconsistent assumptions (shared Monte Carlo concepts are duplicated instead of centralized).
- Confidence messaging is more optimistic than the underlying data quality actually supports.
- Group-expense calculations still rely on mutable current membership rather than immutable historical split context.
- Error handling in budget-offset paths prefers silent zero fallbacks, which hides failures as “healthy” budget states.
