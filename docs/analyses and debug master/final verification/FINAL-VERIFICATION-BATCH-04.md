# Final Verification — Batch 04: Forecasting & Synthesis

## Scope
- `com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt`
- `com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulator.kt`
- `com/yourname/expensetracker/domain/forecasting/DataQualityAssessor.kt`
- `com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt`
- `com/yourname/expensetracker/domain/forecasting/ForecastModels.kt` **(not present in codebase)**
- `com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`
- `com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt`
- `com/yourname/expensetracker/domain/logic/CustomSplitParser.kt`
- `com/yourname/expensetracker/domain/logic/SplitCalculator.kt`
- `com/yourname/expensetracker/domain/logic/SynthesisModels.kt` **(not present in codebase)**
- `com/yourname/expensetracker/domain/model/FinancialForecast.kt`
- `com/yourname/expensetracker/domain/model/BlockPartyDay.kt`
- `com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt`
- `com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt`
- `com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt`
- `com/yourname/expensetracker/domain/util/TimePeriodUtils.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/domain/logic/SplitCalculator.kt:229-239` | Medium | Data integrity | `toCents()` uses `Int`, so values above ~21.47M overflow and produce negative splits; the stress test reproduces this. | B | DOWNGRADED | Convert split math to `Long`/minor units end-to-end. |
| 2 | `com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt:183-214` | High | Logic bug | `calculateRecurringOutflows()` skips still-active patterns whose `nextExpectedDate` is before `startDate`, so future in-horizon occurrences are never counted. | B | CONFIRMED | Roll each pattern forward until it reaches the horizon start or passes the end. |
| 3 | `com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt:223-239,443-459` | High | Modeling bug | The engine calls current-month net cashflow “current balance” and can use budget caps as income fallback, which materially distorts crunch probability. | R | CONFIRMED | Use real balance/income inputs or explicitly degrade confidence when they are unavailable. |
| 4 | `com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt:273-279` | High | Modeling bug | Daily discretionary sampling excludes zero-spend days, biasing every future day toward spending and overstating discretionary totals. | R | CONFIRMED | Sample from the full daily series including zero days, or model spend frequency separately. |
| 5 | `com/yourname/expensetracker/domain/forecasting/DataQualityAssessor.kt:72-96` | High | Confidence scoring | An unusable distribution (`isUsable == false`) can still receive `HIGH` confidence if the other weighted factors are strong. | R | CONFIRMED | Cap unusable fits at `LOW`/`MODERATE` before computing the final level. |
| 6 | `com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt:130-155` | Medium | Time/DST bug | Week grouping and distinct-day counting use raw millisecond division, so DST transitions can move transactions into the wrong day/week bucket. | R | DOWNGRADED | Group by calendar day/week boundaries via `TimePeriodUtils`/`java.time`, not `24h` and `7*24h` millis. **[RESOLVED BY A.5]** |
| 7 | `com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:109-147` | High | Logic bug | `totalCommitted` and `totalLikely` sum each recurring pattern once if its next date is in range, undercounting weekly/biweekly occurrences before month-end. | R | CONFIRMED | Expand recurring occurrences through month end and sum every in-range occurrence. |
| 8 | `com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:429-437` | Medium | Logic/UI bug | Biweekly matching treats any date within ±2 days of the cycle as a bill day, so one bill can appear on up to 5 days of a 14-day cycle. | R | DOWNGRADED | Generate concrete occurrence dates and map each one to a single target day. |
| 9 | `com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt:94-102` | High | Logic bug | Detected `nextExpectedDate` is only one interval past the last observation and is not rolled forward to the next future occurrence. | R | CONFIRMED | Keep advancing until the next occurrence is at/after `timeProvider.now()`. |
| 10 | `com/yourname/expensetracker/domain/logic/SplitCalculator.kt:67-83,94-105,117-128,153-165` | High | Data integrity | Invalid persisted custom splits silently fall back to equal splitting, rewriting historical meaning and corrupting balances/reimbursements. | R | CONFIRMED | Return an explicit invalid result for legacy data, or preserve stored allocations read-only instead of recalculating equal shares. |
| 11 | `com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulator.kt:235-249` | Medium | Logic bug | `countRecentQualifyingWeeks()` uses `total > 0` instead of the production qualification rule (`>= 3` distinct transaction-days), overstating recency/confidence. | R | CONFIRMED | Reuse week-quality metadata from `HistoricalSpendingDistribution` for recency scoring. |
| 12 | `com/yourname/expensetracker/domain/logic/CustomSplitParser.kt:121-143` | Medium | Precision bug | Tolerance checks compare raw `Double` sums, so boundary-valid custom percent/amount payloads can fail because of floating-point noise. | R | CONFIRMED | Validate with rounded minor units / basis points using integer or `BigDecimal` arithmetic. |
| 13 | `com/yourname/expensetracker/domain/forecasting/ForecastModels.kt:N/A` | Low | Architecture | The batch plan references `ForecastModels.kt`, but the file does not exist in the source tree. | R | CONFIRMED | Update the plan/file map or restore a dedicated models file. |
| 14 | `com/yourname/expensetracker/domain/logic/SynthesisModels.kt:N/A` | Low | Architecture | The batch plan references `SynthesisModels.kt`, but synthesis models live elsewhere (`domain/model/*`). | R | CONFIRMED | Update the plan/file map or restore a dedicated models file. |
| 15 | `com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt:50-66` | Medium | Locale/time bug | `Calendar.set(DAY_OF_WEEK, MONDAY)` is locale-sensitive; on Sunday/US-locale paths it can move the boundary forward instead of back to week start. | D | DOWNGRADED | Use `TimePeriodUtils.getStartOfWeek()` or `java.time` week-start helpers for both boundaries. **[RESOLVED BY A.5]** |
| 16 | `com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:98-103` | Medium | Correctness bug | `now` is captured once, but the `Calendar` is seeded with a second `timeProvider.now()` call, reintroducing a midnight race. | D | CONFIRMED | Reuse the already-captured `now` when initializing the calendar. |
| 17 | `com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt:139` | Low | Time/DST bug | `horizonEnd = now + daysAhead * DAY_IN_MILLIS` can drift by ~1 hour across DST boundaries and slightly misplace the forecast cutoff. | D | DOWNGRADED | Use calendar-aware day addition (`TimePeriodUtils.addDays`) instead of fixed milliseconds. |
| 18 | `com/yourname/expensetracker/domain/logic/SplitCalculator.kt:357-361` | Low | UI/I18N bug | `formatBalance()` hardcodes `$`, so non-USD users can see incorrect currency labels. | D | CONFIRMED | Use a currency-aware formatter or pass the display currency into the formatter. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt:127-164` | Medium | Data consistency | The budget-offset path recomputes equal/percent/custom shares with naive `Double` math instead of the canonical cent-based split engine, so budget spend can disagree with balances by rounding/tie-break cents even for valid payloads. | Delegate share calculation to the canonical split calculator / shared-expense service instead of duplicating math. |
| 2 | `com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:69-86,253-256` | Low | Testability/clock consistency | `FinancialForecast.generatedAt` uses `Instant.now()` instead of the injected `TimeProvider`, so timestamps can diverge from the clock used for all other synthesis calculations. | Use `Instant.ofEpochMilli(now)` (and the same injected clock in fallback paths). |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | Debugger #3 | `com/yourname/expensetracker/domain/logic/SplitCalculator.kt:208-221` | The claimed infinite loop cannot occur under the implemented arithmetic: if `remainder < 0`, there are necessarily enough positive base cents to remove, so the loop terminates. |
| 2 | Debugger #6 | `com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt:407` | The expression is redundant, but `horizons.first()` is always the fixed 30-day horizon, so it does not currently compute an incorrect value. |
| 3 | Debugger #7 | `com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt:319-323` | This is dead/redundant code, not a behavioral bug. |
| 4 | Debugger #8 | `com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt:344-356` | `calculateCrunchProbability()` is private and every production call site passes a 1000-element simulation result, so the empty-list case is unreachable today. |
| 5 | Debugger #9 | `com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt:312` | Reusing a fixed seed is deterministic but not incorrect for the engine’s current independent per-horizon percentile calculations. |
| 6 | Debugger #10 | `com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt:117-124` | The “single value after trimming” scenario cannot occur after the existing `>= 4 qualifying weeks` gate; very small positive totals are noisy data, not a functional fit failure by themselves. |
| 7 | Debugger #11 | `com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt:92` | Redundant `dates.isEmpty()` is a readability issue only; `dates` cannot be empty on this path. |
| 8 | Debugger #12 | `com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:186-191` | Multiplying the per-day sum by `0.7` is mathematically equivalent because the map contains only `LIKELY` expenses. |
| 9 | Debugger #13 | `com/yourname/expensetracker/domain/logic/CustomSplitParser.kt:51` | Using `linkedMapOf()` is a negligible implementation choice, not a correctness issue. |
| 10 | Debugger Cross #2 | `com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`, `com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt` | The reports’ “0.50-0.69 patterns vanish entirely” claim is incorrect: stress forecasting treats them as recurring and excludes them from discretionary sampling, so they are inconsistent across features, not dropped within the stress pipeline. |
| 11 | Debugger Cross #4 | `com/yourname/expensetracker/domain/logic/SplitCalculator.kt` | `SplitCalculator` never routes `SplitType.EQUAL` through `CustomSplitParser`, so the claimed spurious warning path does not exist there. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Recurring detection → synthesis/stress forecasting | High | Logic bug | `RecurringExpenseEngine` emits stale `nextExpectedDate` values, and both `SynthesisEngine` and `FinancialStressForecastEngine` consume them as if they were already horizon-aligned. Active recurring obligations can therefore disappear or be undercounted across both forecast surfaces. | `domain/logic/RecurringExpenseEngine.kt`; `domain/logic/SynthesisEngine.kt`; `domain/forecasting/FinancialStressForecastEngine.kt` | Centralize recurring occurrence expansion and always roll patterns into the active horizon before downstream use. |
| 2 | Historical custom splits → membership changes | High | Data integrity | Historical custom splits are validated against the **current** group membership instead of the participant snapshot at expense creation time. After membership changes, old expenses become “invalid” and can fall back to equal splitting or block member deletion incorrectly. | `domain/logic/CustomSplitParser.kt`; `domain/logic/SplitCalculator.kt`; `domain/groups/SharedExpenseManager.kt`; `data/repository/GroupsRepositoryImpl.kt` | Persist participant snapshots (or membership versioning) on each expense and validate historical splits against that snapshot. |
| 3 | Shared-expense balances ↔ budget-offset accrual | High | Consistency bug | `SharedExpenseBudgetOffsetEngine` bypasses the canonical parser/calculator: it permissively reparses payloads and recomputes shares with naive doubles, so malformed payloads and valid rounded splits can yield budget numbers that disagree with group balances. | `domain/groups/SharedExpenseBudgetOffsetEngine.kt`; `domain/groups/SharedExpenseManager.kt`; `domain/logic/SplitCalculator.kt`; `domain/logic/CustomSplitParser.kt` | Route every shared-expense read through one canonical split decoder/calculator. |
| 4 | Stress forecast Monte Carlo ↔ month-end Monte Carlo | Medium | Architecture | The codebase ships two separate Monte Carlo implementations with different assumptions, and `FinancialStressForecastEngine` even injects but does not use `MonteCarloSpendingSimulator`. Similar forecast features can therefore disagree. | `domain/forecasting/FinancialStressForecastEngine.kt`; `domain/forecasting/MonteCarloSpendingSimulator.kt` | Consolidate Monte Carlo behavior behind one shared service or clearly separate contracts and remove unused injection paths. |

## Summary
- Total verified issues: 22
- Confirmed: 22 (Critical: 0, High: 10, Medium: 8, Low: 4)
- False positives: 11
- Missed issues found: 2
- Files affected: 11/13 analyzed source files (+2 planned files missing from scope)

## Key Patterns
- Date/time handling is inconsistent: some paths use `TimePeriodUtils`, while others still use locale-sensitive `Calendar` mutations or raw millisecond arithmetic.
- Recurring-expense logic is duplicated instead of centralized, which is why stale `nextExpectedDate` handling breaks multiple forecast surfaces differently.
- Shared-expense flows still do not have one canonical split pipeline; parser, validation, rounding, and historical-member semantics diverge across balances, deletion checks, and budget-offset accounting.
- Forecasting logic mixes deterministic accounting shortcuts with probabilistic models, so several outputs look precise while being built on proxy inputs or degraded data.
