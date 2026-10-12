# Money M-2c1 Handoff - Advanced Analytics Producers

Date: 2026-10-04. Branch: `bug-fixes`. Base checkpoint: `a1c66173`.

## Status

Source and regression tests are authored. Independent strict money/architecture
review and all live validation remain **pending**. The user explicitly asked
to continue implementation and validate the combined tree later. No subagents,
Gradle commands, validation profiles, registered guards, staging, commits, or
pushes were used for this continuation. The money track is not complete.

## Scope

- Category totals, previous-period values, and within-period velocity expose
  `MoneyAggregateResult` rather than relying on an unqualified scalar total.
- Merchant totals and weekday amounts use the canonical aggregate boundary.
  Historical price trends are suppressed when their normalized currencies or
  exclusion provenance cannot support a comparable merchant slice.
- Spending patterns expose typed whole-period, weekday, time-slot, and
  weekend/weekday results. Date-known exclusions are attributed only to the
  corresponding temporal slice; unknown-date exclusions remain unavailable.
- Statistics, daily totals, and histogram-bin totals use the same boundary.
  Histogram coordinates, bin membership, sample counts, percentiles, and
  variance formulas remain statistical projections, not a second FX step.
- Normalized row selection obeys the input's half-open period. Original
  currencies, rate-valid dates, and included/excluded counts remain observable.
- Category/merchant/amount-bin exclusions cannot be assigned from date alone.
  Those dimension totals fail closed instead of pretending the missing money
  belongs to one particular category, merchant, or histogram bin.
- Valid empty inputs remain distinct from all-excluded, invalid-amount, or
  currency-mismatched inputs. Unavailable current/previous values do not produce
  a fabricated change percentage or budget-status improvement.

## Files

- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsModels.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngineMoneyTest.kt`

This continuation also repairs authored M-2b source/test syntax so the deferred
combined queue can compile: the `DailyBucket` class closing brace and the
`PeriodKind.CUSTOM` argument required by the canonical `PeriodRange` constructor.
Affected paths:

- `app/src/main/java/com/yourname/expensetracker/domain/analytics/DailyBucketEngine.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/DailyBucketEngineMoneyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineNormalizedMoneyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/NormalizedPeriodComparisonMoneyTest.kt`

## Authored regression coverage

Nine focused cases cover source/rate provenance and no second conversion,
category exclusion ownership, typed velocity, merchant weekday totals,
slice-local temporal quality, histogram counts and equal values, valid-empty
versus unavailable results, invalid amounts/currencies, period scoping, and
incompatible historical price trends. They have **not been executed**.

## Deferred validation

After independent strict review, use the durable validation runner serially:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile compile
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*AdvancedAnalyticsEngine*'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*AdvancedAnalyticsDashboardTest*'
```

Retain the M-1/M-2a/M-2b filters, including daily buckets, totals, normalized
period comparisons, and dashboard parity/consistency. No PASS result or current
worktree fingerprint is claimed here. `git diff --check` is a whitespace check,
not compilation, a strict-review gate, or runtime validation.

## Remaining boundaries

Legacy self-fetch APIs and scalar chart/statistical compatibility projections
still require caller review. Existing raw sparkline accumulation and downstream
scalar DTO/UI consumers are not declared money-guard-complete by this slice.
The normalized inputs keep their row provenance; legacy snapshots cannot
recover rate provenance that was already discarded by their old adapters.

Continue with `money-m2c2-20261004-handoff.md` for the category/weekday integration
slice. M-2d, M-3, M-4, and M-5 remain open. No finding-count reduction, guard
baseline/allowlist change, schema change, or final closure is asserted.
