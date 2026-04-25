## Technical Plan
### Scope
- In: remaining D.3 standalone-medium issues tied to wall-clock usage, `TimeProvider` gaps, synthetic/sentinel time semantics, week/month boundary drift, and deterministic date parsing/default timestamps.
- In: review-verified open items from D.1 and D.6-D.15, plus registry-only D.16 time items that still need revalidation before execution.
- Out: unrelated D.3 parser, localization, DB-index, currency-formatting, and domain-boundary issues; already-resolved A.3/A.5 epic items unless they regress inside the targeted files.

### Execution Hygiene Note
- Room schema/version artifacts currently present in worktree (`AppDatabase` 81→83 with `MIGRATION_81_82`/`MIGRATION_82_83`, plus schema `82.json`/`83.json`) belong to prior B.4 database closeout work and are **not** part of D3 time-determinism scope.
- D3 commits must exclude Room schema/entity/migration changes.

### Assumptions / Unknowns
- No dedicated `D3-SUBBATCH-D16-REVIEW.md` / `D17` review docs exist; D.16 items below are sourced from `MASTER-ISSUE-REGISTRY.md` only.
- `ComputeMoneyRadarUseCase` may already be partly fixed: current file scan shows one top-level `timeProvider.now()` capture, so the D.16 registry row must be revalidated before any code churn.
- `SourceStats.lastSeen` can likely be fixed without a Room schema migration by removing Kotlin-side implicit defaults and requiring explicit caller-supplied timestamps.
- `UberReceiptParser` needs a deterministic year anchor; safest plan is `receivedAt`-anchored parsing with a future-date clamp for year-boundary receipts.
- `ForecastHorizon` / `RecurrenceFrequency` fixes must preserve enum names and user-facing labels to avoid unnecessary serialization/UI fallout.

### Grouped Issue List
#### Group 1 — Wall-clock usage / `TimeProvider` injection
- **D9-1** — `ConfidenceRouter.ensureSourceStats()` still creates `SourceStats(packageName = ...)`, and `SourceStats.lastSeen` still defaults to `System.currentTimeMillis()`.
- **D16-registry** — `SynthesisEngine` still captures `now` once but seeds a `Calendar` from a second `timeProvider.now()` call, leaving a midnight race.
- **D16-registry / revalidate-first** — `ComputeMoneyRadarUseCase.compute()` was flagged for mixed `now` capture; revalidate before editing because current scan suggests the direct local issue may be stale.

#### Group 2 — Synthetic / sentinel time semantics
- **D6-6** — `ForecastHorizon.REST_OF_MONTH` still encodes calendar-bound behavior as `days = 0`.
- **D6-9 / D12-9** — `RecurrenceFrequency` still models calendar frequencies with approximate fixed-day counts and exposes `IRREGULAR.intervalInMs = 0L`.
- **D10-3** — `RecurringIncomeTracker` still compares millisecond-squared variance against a tiny fixed threshold instead of normalizing to day-scale semantics.

#### Group 3 — Deterministic date parsing / default timestamps
- **D1-7** — `CsvExpenseImporter` still rewrites failed date parses to `System.currentTimeMillis()`.
- **D7-11** — `UberReceiptParser.parseUberDate()` still fills year-less dates with `Calendar.getInstance().get(Calendar.YEAR)` instead of anchoring to `receivedAt`.

#### Group 4 — Week / month / day boundary correctness
- **D15-3** — `ExpenseDao.getWeeklyTotalsForPeriod()` still returns `MIN(date)` / `MAX(date)` transaction timestamps as week boundaries instead of canonical calendar week bounds.
- **D15-1** — `DayOfWeekAnalyzer` still sorts Monday→Sunday output by `totalSpent`, breaking chronological order.
- **D15-7** — `BudgetAutopilotEngine` and `BudgetForecastingEngine` still use separate month-key helpers and different month-history windows/timezone rules.
- **D14-13** — `AdvancedAnalyticsEngine` current-period sparklines still exclude today and still rely on raw millisecond day indexing.

### Files
- modify: `app/src/main/java/com/yourname/expensetracker/data/database/entity/SourceStats.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/intelligence/ConfidenceRouter.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/email/provider/UberReceiptParser.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/model/FinancialForecast.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/model/RecurringPattern.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/logic/RecurrenceCalculator.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/income/RecurringIncomeTracker.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzer.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngine.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/util/TimePeriodUtils.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt` *(revalidate first; touch only if still open)*
- modify: `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ConfidenceRouterTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ConfidenceRouterEdgeCaseTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/util/CsvExpenseImporterTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/email/provider/UberReceiptParserTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/logic/SynthesisEngineTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/logic/SynthesisEngineGoldenTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/logic/SynthesisEngineStressTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/income/RecurringIncomeTrackerTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzerTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngineTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngineDeepTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngineStressTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngineTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngineTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/util/TimePeriodUtilsTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/util/TimePeriodUtilsValidationTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/util/TimePeriodUtilsStressTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/database/dao/ExpenseDaoBoundaryConsistencyTest.kt`
- modify: `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/ExpenseDaoTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt` *(only if Batch 5 touches production code)*
- modify: `app/src/test/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCaseTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/model/FinancialForecastModelTest.kt` *(if no focused sentinel-horizon test already exists)*
- create: `app/src/test/java/com/yourname/expensetracker/domain/model/RecurringPatternModelTest.kt` *(if no focused recurrence-sentinel test already exists)*

### File-Level Fix Plan
| File | Issue coverage | Planned remediation | Dependency notes |
|---|---|---|---|
| `SourceStats.kt` | D9-1 | Remove implicit wall-clock constructor default for `lastSeen`; require explicit timestamp at creation boundaries or route through a deterministic factory/helper. | Must avoid schema changes if possible. |
| `ConfidenceRouter.kt` | D9-1 | Capture one `now = timeProvider.now()` in `ensureSourceStats()` and use it for insert + cache update; stop relying on entity-side defaults. | Depends on `SourceStats` signature/factory change. |
| `NotificationProcessingPipeline.kt` | D9-1 | Update all `SourceStats(...)` creation sites to pass explicit timestamps from the same operation clock used by subsequent counter updates. | Same batch as `SourceStats` to avoid half-migration. |
| `CsvExpenseImporter.kt` | D1-7 | Replace `parse failure -> now` with explicit row failure handling; preserve import counts and never rewrite historical rows to “today”. | Independent low-risk batch. |
| `UberReceiptParser.kt` | D7-11 | Thread `receivedAt` into year-less date parsing; anchor to received year and clamp obviously future dates back one year when needed. | Keep public parse API stable if possible. |
| `FinancialForecast.kt` | D6-6 | Replace `REST_OF_MONTH(0)` sentinel with explicit calendar-bound metadata (`kind`, nullable fixed-days, or equivalent non-sentinel representation). | Requires callsite compile-neighbor updates. |
| `RecurringPattern.kt` | D6-9 / D12-9 | Remove misleading calendar-frequency `days` / `intervalInMs` semantics from production use; introduce explicit fixed-interval vs calendar-bound helpers. | Keep enum constant names stable. |
| `RecurrenceCalculator.kt` | D6-9 / D12-9 | Become the single owner for calendar-aware recurrence advancement; stop any remaining reliance on approximate fixed-day values for month/quarter/year logic. | Must land with `RecurringPattern` changes. |
| `RecurringExpenseEngine.kt` | D6-9 / D12-9 | Update roll-forward / grouping logic to use new recurrence helpers and only use fixed-day semantics for weekly/biweekly frequencies. | Same PR as recurrence-model change. |
| `SynthesisEngine.kt` | D16-registry, D6-6 | Reuse a single captured `now` within each method, seed `Calendar` from that same value, and adapt to the non-sentinel `ForecastHorizon` API. | Safe to combine with Batch 3 or 5. |
| `RecurringIncomeTracker.kt` | D10-3, D8-15 | Normalize interval variance to days before confidence scoring; capture `now` once per method; skip blank merchant keys after canonicalization. | Independent after recurrence-model batch. |
| `TimePeriodUtils.kt` | D15-3, D15-7, D14-13 | Add/reuse canonical helpers for week starts, month keys, date-diff/day-index calculations, and any missing bucket normalization helpers. | Shared dependency for boundary batches. |
| `ExpenseDao.kt` | D15-3 | Stop exposing `MIN/MAX` transaction times as week bounds; either return canonical bounds directly or project enough data for repository-side normalization. | Prefer repository normalization if SQLite week math is brittle. |
| `ExpenseRepository.kt` | D15-3 | If DAO remains aggregate-only, normalize `weekKey -> [start,end)` using `TimePeriodUtils` before returning `WeeklyTotal`. | Land with DAO change. |
| `DayOfWeekAnalyzer.kt` | D15-1 | Keep Monday→Sunday order stable by sorting on `dayIndex` or not resorting at all. | Independent low-risk batch. |
| `BudgetAutopilotEngine.kt` | D15-7 | Replace local month-key parse/format/range logic with a shared helper; align lookback fill policy to the same month-bucketing contract as forecasting. | Must pair with `BudgetForecastingEngine`. |
| `BudgetForecastingEngine.kt` | D15-7 | Use the same shared month-key helper and month-window semantics as autopilot; keep forecasting heuristics unchanged outside bucketing. | Must pair with `BudgetAutopilotEngine`. |
| `AdvancedAnalyticsEngine.kt` | D14-13 | Replace raw millisecond day indexing with day-safe indexing from `TimePeriodUtils`; include current day in current-period sparkline output. | Prefer after shared day-index helper is settled. |
| `ComputeMoneyRadarUseCase.kt` | D16-registry | Revalidate registry claim; only fix if a second `now` capture or helper-level drift still exists. If stale, close via registry/docs update instead of code churn. | Audit-first, not automatic edit. |

### Dependency Order
1. **Batch 1** must land before any other code that creates `SourceStats`, otherwise wall-clock defaults persist through untouched callsites.
2. **Batch 2** is independent once Batch 1 is stable.
3. **Batch 3** should land before any wider recurrence cleanup because it changes the meaning of public temporal model fields.
4. **Batch 4A** (weekly boundaries / weekday ordering) should precede **Batch 4B** (month-bucket alignment / sparkline indexing) if shared `TimePeriodUtils` helpers are added.
5. **Batch 5** should run after Batch 3/4 helper work settles, so residual time-unit normalization can reuse the finalized utility contract.
6. **Batch 6** is last and updates registry/docs only after code + tests are green.

### Recommended Batching Strategy
| Batch | Theme | Risk | Ship independently? | Notes |
|---|---|---:|---|---|
| 1 | `SourceStats` wall-clock default removal | Low | Yes | Small blast radius; highest determinism payoff per LOC. |
| 2 | Deterministic parse defaults (`CsvExpenseImporter`, `UberReceiptParser`) | Low | Yes | Safe parser/import batch with focused regressions. |
| 3 | Sentinel model cleanup (`ForecastHorizon`, `RecurrenceFrequency`) | Medium | Prefer one PR | Fan-out across recurrence/forecast callsites. |
| 4A | Weekly boundary normalization + weekday order | Medium | Yes | DAO/repository/analyzer only. |
| 4B | Month-bucket alignment + sparkline inclusion | Medium-High | Prefer after 4A | Budget/analytics logic needs shared helper stability. |
| 5 | Residual now-capture + unit normalization (`RecurringIncomeTracker`, `SynthesisEngine`, D16 revalidation) | Low-Medium | Yes | Keep `ComputeMoneyRadarUseCase` audit-only unless confirmed open. |
| 6 | Registry/report sync | Low | Yes | No production code; close only verified issues. |

### Implementation Steps
1. **Batch 1 — Remove implicit wall-clock defaults from source-stats creation**
   - Scope: `SourceStats.kt`, `ConfidenceRouter.kt`, `NotificationProcessingPipeline.kt`, `ConfidenceRouterTest.kt`, `ConfidenceRouterEdgeCaseTest.kt`.
   - Actions:
     - Remove `System.currentTimeMillis()` as the implicit constructor default for `SourceStats.lastSeen`.
     - Require explicit timestamps at each creation site and reuse one captured timestamp per operation.
     - Keep cache TTL logic and DB schema unchanged.
   - Validation:
     - Targeted unit tests for `ensureSourceStats()` and cache/update consistency.
     - Grep validation: no `System.currentTimeMillis()` remains in the three production files.
   - Done when:
     - No `SourceStats(` creation path relies on a wall-clock default.
     - `ConfidenceRouter.ensureSourceStats()` and notification-pipeline inserts use explicit timestamps only.
   - Failure / rollback note:
     - If removing the default causes broad compile fallout, use an internal factory/helper rather than reintroducing an implicit wall-clock default.

2. **Batch 2 — Make date parsing and import fallbacks deterministic**
   - Scope: `CsvExpenseImporter.kt`, `CsvExpenseImporterTest.kt`, `UberReceiptParser.kt`, `UberReceiptParserTest.kt`.
   - Actions:
     - Replace `CsvExpenseImporter`’s `parse failure -> now` behavior with explicit per-row failure handling.
     - Thread `receivedAt` into `UberReceiptParser` year-less date parsing.
     - Add a year-boundary heuristic so receipts near New Year do not drift into the future.
   - Validation:
     - Import test proving invalid CSV dates increment error count and do not become “today”.
     - Parser test proving a year-less Uber date derives from `receivedAt`, including a Dec/Jan boundary case.
   - Done when:
     - Invalid dates are rejected or surfaced, never silently synthesized from current wall-clock time.
     - Uber year-less parsing is deterministic for the same `(body, receivedAt)` input.
   - Failure / rollback note:
     - If parser signatures would ripple too broadly, add internal helper overloads first and keep public entrypoints stable.

3. **Batch 3 — Eliminate sentinel temporal values from domain models**
   - Scope: `FinancialForecast.kt`, `RecurringPattern.kt`, `RecurrenceCalculator.kt`, `RecurringExpenseEngine.kt`, `SynthesisEngine.kt`, `CalculateFinancialForecastUseCaseTest.kt`, `SynthesisEngine*Test.kt`, plus focused model tests if missing.
   - Actions:
     - Replace `ForecastHorizon.REST_OF_MONTH(days = 0)` with an explicit calendar-bound representation.
     - Replace or deprecate `RecurrenceFrequency.intervalInMs` for calendar frequencies and `IRREGULAR`; route production logic through explicit recurrence helpers instead.
     - Keep enum names and UI labels stable.
   - Validation:
     - Unit tests proving no production logic relies on `0` as a temporal sentinel.
     - Compile validation across recurrence, synthesis, and forecast callsites.
   - Done when:
     - `REST_OF_MONTH` and `IRREGULAR` semantics are represented explicitly, not via magic zero values.
     - No remaining production callsite interprets calendar month/quarter/year semantics from approximate fixed-day millisecond intervals.
   - Failure / rollback note:
     - If the model fan-out is too large, introduce additive helpers first, migrate all production callsites in the same PR, then deprecate/remove the sentinel fields.

4. **Batch 4A — Canonicalize weekly aggregate boundaries and weekday ordering**
   - Scope: `TimePeriodUtils.kt`, `ExpenseDao.kt`, `ExpenseRepository.kt`, `DayOfWeekAnalyzer.kt`, `TimePeriodUtils*Test.kt`, `ExpenseDaoBoundaryConsistencyTest.kt`, `ExpenseDaoTest.kt`, `DayOfWeekAnalyzerTest.kt`.
   - Actions:
     - Add any missing helper needed to derive canonical Monday-start week ranges from aggregate keys.
     - Stop using weekly `MIN/MAX(date)` as semantic week bounds.
     - Keep `DayOfWeekAnalyzer` output ordered Monday→Sunday.
   - Validation:
     - DAO/repository tests covering year rollover, empty weeks, and Monday-start boundaries.
     - Analyzer regression proving output order is chronological, not spend-ranked.
   - Done when:
     - Weekly totals expose canonical `[start,end)` week ranges.
     - Day-of-week analytics remain stable in chronological order.
   - Failure / rollback note:
     - If SQL week-date derivation is unreliable, keep DAO aggregation minimal and normalize boundaries in the repository layer.

5. **Batch 4B — Align month bucketing and current-day indexing across budget/analytics engines**
   - Scope: `BudgetAutopilotEngine.kt`, `BudgetForecastingEngine.kt`, `AdvancedAnalyticsEngine.kt`, `TimePeriodUtils.kt`, related budget/analytics tests.
   - Actions:
     - Extract one shared month-key parse/format/range-fill helper.
     - Make autopilot and forecasting use the same lookback window and month bucket semantics.
     - Replace raw millisecond sparkline day indexing with day-safe indexing and include today in current-period output.
   - Validation:
     - Budget engine tests proving identical month-bucket outputs for the same synthetic history.
     - Analytics tests proving current-day sparkline inclusion and no empty first-day result.
   - Done when:
     - Autopilot and forecasting no longer diverge on month bucketing/timezone behavior.
     - Current-period sparklines include today and do not depend on raw `DAY_IN_MILLIS` indexing.
   - Failure / rollback note:
     - Keep trend/confidence heuristics unchanged; only bucket construction and day indexing should move in this batch.

6. **Batch 5 — Residual time-unit normalization and registry-only cleanup**
   - Scope: `RecurringIncomeTracker.kt`, `RecurringIncomeTrackerTest.kt`, `SynthesisEngine.kt` (if second-now capture still remains), `ComputeMoneyRadarUseCase.kt` *(audit-first)*, `ComputeMoneyRadarUseCaseTest.kt` *(if touched)*.
   - Actions:
     - Normalize recurring-income variance/confidence to day-scale intervals.
     - Skip blank merchant keys instead of surfacing synthetic empty-source recurring income.
     - Ensure each targeted method captures `now` once and reuses it.
     - Revalidate the D.16 `ComputeMoneyRadarUseCase` registry row; either fix a confirmed issue or mark the row stale with evidence.
   - Validation:
     - `RecurringIncomeTrackerTest` regressions for confidence normalization and blank-merchant filtering.
     - `SynthesisEngine` tests proving no multi-capture midnight drift.
     - `ComputeMoneyRadarUseCaseTest` only if production code changes.
   - Done when:
     - No targeted method mixes separate `now` captures for one logical calculation.
     - Recurring-income confidence is based on day-scale variance, not millisecond-squared thresholds.
   - Failure / rollback note:
     - Treat `ComputeMoneyRadarUseCase` as audit-only unless the issue is reconfirmed; do not force a speculative edit.

7. **Batch 6 — Registry and review-artifact synchronization**
   - Scope: `MASTER-ISSUE-REGISTRY.md` and only the matching D.3 review/report rows that correspond to verified fixes.
   - Actions:
     - Update only the resolved/partially-resolved time-related D.3 rows.
     - If D.16 `ComputeMoneyRadarUseCase` is stale, document the revalidation outcome instead of inventing a code fix.
   - Validation:
     - Diff audit proving only targeted D.3 time rows changed.
   - Done when:
     - Registry/report status matches the implemented code and test evidence.
   - Failure / rollback note:
     - Do not close any row that was only “planned”; close only code-verified items.

### Risks
- `SourceStats` constructor changes may ripple into debug/restore and notification pipeline callsites if any hidden instantiations exist.
- Sentinel-removal in `ForecastHorizon` / `RecurrenceFrequency` can create broad compile churn if done destructively instead of additively.
- DAO week-boundary fixes are vulnerable to ISO-week/year rollover edge cases; repository-side normalization may be safer than complex SQLite date math.
- Shared month-bucket alignment can shift historical averages if lookback-fill rules change accidentally; keep heuristics stable and change only bucket construction.
- `UberReceiptParser` year anchoring can still misdate cross-year receipts unless the `receivedAt` clamp is explicitly covered by tests.
- D.16 registry-only items may be partially stale; execution must revalidate before editing to avoid unnecessary churn.

### Acceptance Criteria
- [ ] No targeted production file in this plan relies on `System.currentTimeMillis()` for default timestamps or parse fallbacks.
- [ ] `SourceStats` creation uses explicit timestamps only, and `ConfidenceRouter` / notification pipeline reuse a single operation timestamp.
- [ ] Invalid CSV dates are surfaced as import failures, not silently rewritten to the current date.
- [ ] Year-less Uber receipt dates resolve deterministically from `receivedAt`, including year-boundary regression coverage.
- [ ] `ForecastHorizon.REST_OF_MONTH` and `RecurrenceFrequency.IRREGULAR` no longer encode temporal meaning via `0` sentinels in production logic.
- [ ] `RecurringIncomeTracker` confidence uses day-scale interval math and blank merchant keys are excluded from recurring-income synthesis.
- [ ] Weekly aggregate boundaries are canonical Monday-start / next-Monday-exclusive ranges.
- [ ] `DayOfWeekAnalyzer` returns Monday→Sunday order.
- [ ] `BudgetAutopilotEngine` and `BudgetForecastingEngine` share one month-bucketing contract.
- [ ] `AdvancedAnalyticsEngine` current-period sparklines include today and use day-safe indexing.
- [ ] D.16 registry-only items are explicitly revalidated, with stale rows documented instead of left ambiguous.
- [ ] Targeted tests pass before any registry row is marked resolved.
