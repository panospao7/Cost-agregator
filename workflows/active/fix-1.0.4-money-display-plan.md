# Fix plan: fix-1.0.4-money-display (batch B1)

Author: orchestrator. Status: DRAFT - awaiting Gate 1 before any `app/` edit.
Lane: `build/fix2-wt`, branch `feature/fix-1.0.4-money-display`, base `da5310d8` (= current origin/feature/ai-evolution tip).
Evidence base: `build/review-wt/build/full-review-2026-11/` (70 artifacts) + the B1 re-anchor done at this commit.

- Risk tier: **T2** (display layer; no schema, no lifecycle, no aggregates, no workers).
- Schema: **NO**.
- Conventions: `A` = `app/src/main/java/com/yourname/expensetracker`, `T` = `app/src/test/java/com/yourname/expensetracker`.
- Validation ONLY by `validation-runner`, serially. Never `./gradlew`.
- Concurrency: 2 agents max, **one writer per lane**; second slot for the independent test-author.

## Re-anchor result at da5310d8 (all 10 findings re-verified)

| ID | Status | Anchor |
|---|---|---|
| E-01-4 | STILL PRESENT | `TotalsAggregationEngine.kt:106,434,561,566,612` - 5 default-locale `.format()` calls |
| N-08-1 / J-07-1 | STILL PRESENT | `RecurringExpensesScreen.kt:396-398` (+ :449,:460 raw `%.2f`) |
| P-05-4 | STILL PRESENT | `FinancialWeatherCard.kt:271,343,413` |
| E-05-1 | STILL PRESENT | `WarrantyTrackerViewModel.kt:120` + `WarrantyTrackerScreen.kt:166` |
| J-08-1 | STILL PRESENT | `BudgetScreen.kt:615,626,730,739` + `strings.xml:2312-2315,2336-2337` |
| N-08-5 | STILL PRESENT | `ComputeDashboardWidgetsUseCase.kt:1371-1375` + `TotalsAggregationEngine.kt:280-296` |
| FL-06 / P-05-7 | STILL PRESENT | `ComputeDashboardWidgetsUseCase.kt:1307-1312` |
| P-12-1 | STILL PRESENT | `ExpenseExportMapper.kt:49-52` + `ExportOptionsViewModel.kt:734-741,799-805` |
| N-08-7 | STILL PRESENT | `AnalyticsViewModel.kt:564-566` (+ :87-89) |
| P-06-1 | STILL PRESENT | `BudgetForecastingViewModel.kt:96-97` + `BudgetRecommendationEngine.kt:53-54,157-162` |

**None were fixed by the 26 newer commits.** Two NEW instances of the same defect class were introduced by
`stress-forecast-actions` and are added to this batch as B1-11 and B1-12.

## Slice table

| ID | Title | Tier | Prod files | Depends on | Reviewers | Issues closed |
|---|---|---|---|---|---|---|
| S0 | Re-anchor + baseline targeted run | - | 0 | lane created | - | - |
| S1 | Locale-safe keys + percent formatting | T2 | 2 | S0 | cheap reviewer | E-01-4, B1-11 |
| S2 | Currency-correct formatting (4 surfaces) | T2 | 5 | S1 | AG + test-author | N-08-1, P-05-4, E-05-1, J-08-1 |
| S3 | Honest "unavailable" instead of 0.0 | T2 | 4 | S2 | AG + test-author | N-08-5, FL-06, N-08-7 |
| S4 | Export sentinel + budget forecast currency | T2 | 4 | S3 | AG + test-author | P-12-1, P-06-1, B1-12 |
| Z | Candidate gate | - | 0 | S4 | reviewer-strict (fresh) | - |

Order rationale: S1 is the cheapest and highest-value (one-line locale fix makes every monthly total correct for
Arabic/Persian/Urdu users). S2 is the largest cluster and shares one fix pattern. S3 changes a data shape
(`PeriodSummary`/`PeriodTotal` gain an unavailable state) so it must come after S2 to avoid churn. S4 is the export
contract, which is the most sensitive to get wrong.

## Slices

### S0 - Re-anchor + baseline (no `app/` edits)
- Record `git rev-parse HEAD` == `da5310d8`.
- Queue ONE baseline `targeted-unit-test` over the classes S1-S4 will touch, so a later failure is attributable.

### S1 - Locale-safe keys and percent formatting (E-01-4, B1-11)
- `A/domain/analytics/TotalsAggregationEngine.kt`: all 5 `.format(...)` calls must use `Locale.ROOT`. These build LOOKUP KEYS
  that are compared against ASCII keys produced elsewhere - a locale-dependent key is a correctness bug, not a display choice.
- `A/ui/components/FinancialStressForecastCard.kt:278`: the `%.0f` percent must not be locale-dependent in a way that breaks
  the surrounding string; use the project's existing formatting convention (check how other percent strings are built).
- Tests: assert that under `Locale("ar","EG")` the month key equals the ASCII key, and that a percent renders as expected.
- NOT in scope: changing any user-facing number FORMAT (that is a display decision); only the KEY construction and the
  percent interpolation.

### S2 - Currency-correct formatting (N-08-1, P-05-4, E-05-1, J-08-1)
- `A/ui/screens/recurring/RecurringExpensesScreen.kt`: format with `expense.currency`, not `homeCurrency`. Also fix the two raw
  `String.format("%.2f", ...)` sites (:449, :460) to use `CurrencyFormatter`.
- `A/ui/components/FinancialWeatherCard.kt` + `A/domain/model/UpcomingItem.kt`: carry the item's own currency through to the
  formatter. If `UpcomingItem` cannot express currency, add it (this is a domain model change, not a schema change).
- `A/ui/screens/warranty/WarrantyTrackerViewModel.kt` + `WarrantyTrackerScreen.kt`: carry currency/isPartial/availability in the
  state and render with `CurrencyFormatter`; show a partial warning; hide or mark unavailable.
- `A/ui/screens/budget/BudgetScreen.kt` + `A/res/values/strings.xml`: replace the four hardcoded `-` format strings with
  currency-aware formatting. **`strings.xml` is a HOT FILE - this slice owns it exclusively.**
- Tests: one per surface, asserting a non-EUR currency renders with its own symbol and that a partial/unavailable state is
  visible rather than silently 0.

### S3 - Honest "unavailable" (N-08-5, FL-06, N-08-7)
- `A/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`: `PeriodSummary` gains an unavailable state instead of emitting
  `0.0`; `SafeToSpend` must be computed when data is complete rather than hardcoded unavailable.
- `A/domain/analytics/TotalsAggregationEngine.kt`: `PeriodTotal` must distinguish "no data" from "unavailable".
- `A/ui/screens/home/HomeScreen.kt` + `A/ui/components/TotalsDashboardCard.kt`: render the unavailable state.
- `A/ui/screens/analytics/AnalyticsViewModel.kt`: attribute exclusions per category instead of dropping every category when one
  row fails conversion.
- Tests: a period with one unconvertible row must still show the other categories; a fully-unavailable period must render
  "unavailable", not 0.00.

### S4 - Export sentinel + budget forecast currency (P-12-1, P-06-1, B1-12)
- `A/domain/export/ExpenseExportMapper.kt` + `A/ui/screens/export/ExportOptionsViewModel.kt`: emit blank/null for the no-rate
  sentinel instead of `0.0`/`""`/`"0"`. **This changes an export contract** - the CSV/JSON shape must stay parseable.
- `A/ui/screens/budget/BudgetForecastingViewModel.kt` + `A/domain/budget/BudgetRecommendationEngine.kt`: pass the normalised
  budget amount and format with the real currency instead of a hardcoded `-`.
- `A/domain/forecasting/FinancialStressForecastEngine.kt`: the emergency buffer is a HOME-currency scalar; do not label it with
  `displayCurrency` unless it was converted. Either convert it or label it with the home currency.
- Tests: export of a no-rate row produces blank/null (not 0.0); a foreign budget forecast compares like with like.

## Hot files (one writer program-wide)
| File | Slice |
|---|---|
| `app/src/main/res/values/strings.xml` (+ el/es if keys change) | **S2 only** |
| `app/src/main/res/values-el/strings.xml`, `values-es/strings.xml` | S2 only, and only if a key is added/changed |

## Validation plan
- After S1+S2: one combined `targeted-unit-test` filter, then the `runtime-ui` shard.
- After S3+S4: targeted, then `app-check` (lint will catch any MissingTranslation from strings.xml changes).
- Z: full ladder once + fresh `reviewer-strict`.

## Explicitly OUT of scope
- The neutralisation-propagation family (N-02-1/2/3, S19-1, S26-1) - that is batch B2, a cross-cutting refactor.
- The domain-model family (DR-01..DR-08) - batch B3, some need schema.
- Engine semantics (BL-04..BL-11) - batch B4.
- `I-01-1` barrier dominance - deferred, documented.
- Any refactor, rename, or reformat. Minimal diffs only.

## Stop and ask before
- Adding a Room entity/column/version (S2 may need `UpcomingItem` to carry currency - that is a DOMAIN model change, allowed;
  a Room change is NOT).
- Changing the export CSV/JSON column set (S4 changes VALUES, not the column list - if a column must be added, stop).
- Touching `TransactionLifecycleCoordinator`.
