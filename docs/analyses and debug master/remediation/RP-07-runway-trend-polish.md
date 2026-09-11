# RP-07 - Runway, calendar, insight, and historical-status semantics

> **Status:** corrected implementation plan; documentation only.
> **Mode:** strict for the runway model migration, standard for the display-only trend/status fixes.
> **Depends on:** RP-05 dashboard windows and RP-06 normalized pace/synthesis contracts.
> **Owns:** dashboard runway edge behavior, DST-safe block-party lookup, month-over-month insight wording, and completed-history status baselines.
> **Does not own:** currency conversion, pace construction, budget autopilot, or a broad redesign of status thresholds.

The prior draft called this a small use-case change, but `FinancialRunway.daysRemaining` is currently a non-null `Int` consumed by a Compose card and by several dashboard tests. A no-burn state therefore requires an explicit model, adapter, and UI migration. Each `excludeCurrent` caller also needs an individual classification; this plan does not change all callers by search-and-replace.

## Verified source contracts

- `ComputeDashboardWidgetsUseCase.computeRunwayAndForecast` computes `averageDailyBurn = monthSpent / dayOfMonth`, uses `.toInt()` for the runway, and currently subtracts only month spend from the budget/income remainder. `totalCommitted` and `totalLikely` are displayed but not deducted.
- `DashboardWidget.FinancialRunway.daysRemaining` and `FinancialRunwayCard.daysRemaining` are non-null `Int`. The card treats zero as exhausted except for `NO_INCOME`.
- The block-party fallback currently probes `monthStart + dayIndex * DAY_IN_MILLIS`, while dashboard day keys are zone-aware `TimePeriodUtils.getStartOfDay` values.
- `buildNaturalLanguageInsight` compares current MTD directly with the full previous month and receives no projected-total argument. RP-06 supplies the canonical pace projection.
- `TotalsAggregationEngine.getAverageForPeriodType` already has an `excludeCurrent` flag. Several totals and `HomeViewModel` status callers currently pass `false`, including comparison surfaces.

## P5-010 - Correct runway edge cases without inventing a number

### Domain contract

Extend the dashboard runway model deliberately:

```text
FinancialRunway(
    daysRemaining: Int?,
    zeroBurnHorizonDays: Int?,
    ...,
    status: RunwayStatus
)
RunwayStatus = HEALTHY | CAUTION | CRITICAL | NO_INCOME | NO_BURN
```

`zeroBurnHorizonDays` is non-null only for `NO_BURN` and is the honest display cap `min(daysUntilPeriodEnd, 30)`. When the period has no days left it is `0` and the UI says “No remaining period” rather than displaying `0+`. It is not a calculated runway and must not be used in threshold comparisons. If adding a second field is rejected by the existing UI model, use an equivalent typed `RunwayAvailability` sealed value; do not use `Int.MAX_VALUE` or a magic negative sentinel.

### Calculation rules

1. Resolve the spending basis and remaining budget in home currency, preserving RP-05/RP-06 partial/unavailable quality. Compute `remainingBeforeCommitments` from the existing budget-or-income policy.
2. Deduct only `totalCommitted` (certain future outflows):

   `effectiveRemaining = (remainingBeforeCommitments - totalCommitted).coerceAtLeast(0.0)`.

   Keep `totalLikely` informational; it is shown but not deducted because it is probabilistic. If the committed amount exceeds the remainder, keep the remainder at zero and use the critical/exhausted status path.
3. If there is a valid positive remainder and `averageDailyBurn <= 0`, return `daysRemaining = null`, `zeroBurnHorizonDays = min(daysUntilPeriodEnd, 30)`, and `status = NO_BURN`. This state means “no observed burn”; it does not mean infinite money. Preserve `NO_INCOME` when neither a usable income source nor an explicit budget exists.
4. If burn is positive, calculate `(effectiveRemaining / averageDailyBurn).roundToInt().coerceAtLeast(0)`. Keep the existing 14/7 thresholds unchanged. Do not use truncation, which makes 13.99 days look like 13.
5. If the remainder is zero/non-positive, return a non-null zero day count and `CRITICAL` (unless the existing no-income contract applies). Do not label a zero-burn, positive-remainder user critical.

### Complete consumer migration

- Update `DashboardWidget.FinancialRunway`, `RunwayResult.Available` construction, the unavailable fallback, and every `copy`/fixture.
- Update `FinancialRunwayCard` to accept nullable days and the no-burn cap. For `NO_BURN`, show `"{cap}+"` when the cap is positive or “No remaining period” at zero, suppress the exhausted message, and use a bounded progress value based on the cap. For all other statuses, retain the existing numeric/day presentation.
- Update `HomeScreen` mapping and any accessibility/test semantics. The UI must not call `.coerceAtLeast` on a nullable value before handling `NO_BURN`.
- Audit `DashboardWidgetRenderCoverageTest`, `DashboardProjectionSafetyTest`, `ComputeDashboardWidgetsUseCaseDaysRemainingBoundaryTest`, `DashboardWidgetConsistencyTest`, and integration fixtures. `SafeToSpend.daysRemaining` is a separate non-null field and is not part of this migration.

### Tests

- Day one, no purchases, positive budget -> `NO_BURN`, nullable days, cap no greater than 30, never `CRITICAL`.
- Positive burn with a 13.99-day quotient -> 14 and `HEALTHY`/`CAUTION` classification according to the unchanged thresholds.
- Committed obligations greater than remaining -> zero remainder, `CRITICAL`, committed amount still visible, likely amount not deducted.
- No budget and no income -> `NO_INCOME` and no fabricated runway.
- Last day/month boundary and DST fixtures preserve the existing `SafeToSpend` day semantics.

## P5-011 - DST-safe block-party history keys

Replace fixed-millisecond day construction with `TimePeriodUtils.addDays(monthStart, dayIndex)` (or the repository's equivalent calendar-safe helper). Keep the map keyed by `getStartOfDay` and do not change rate or money semantics. A spring-forward and fall-back fixture must prove that each calendar day reads its own value and that no neighboring day is duplicated or skipped.

This is a fallback-path fix even after RP-06 makes normalized daily values authoritative. Do not reintroduce raw daily sums to compensate for a missing key.

## P5-013 - MoM insight must compare like with like

Change `buildNaturalLanguageInsight` to receive the canonical `SpendingPace.projectedTotal` (or an equivalent explicitly supplied projection from RP-06), `previousMonthTotal`, and the existing today/count values.

- If `previousMonthTotal <= 0` or is unavailable/partial in a way that cannot support comparison, skip the MoM branch and retain the existing “today spent” fallback.
- Compare projected current-month spend with the completed previous-month total. Do not compare MTD with a full month; that produces a false “spent less” message early in every month.
- Keep the existing greater-than-20-percent spike threshold and text keys. Do not duplicate a projection formula in this use case.
- If the projection is unavailable, do not coerce it to zero; skip the comparison.

Tests must include: no previous baseline, day-three MTD below last month but projection above it, a genuine under-pace projection, and the >20% spike.

## P5-014 - Completed-history averages, caller by caller

Document `getAverageForPeriodType(periodType, excludeCurrent)` as follows: `true` is required for a historical comparison/status baseline; `false` is allowed only for a surface explicitly labelled as including the current partial period.

Apply and verify the following matrix rather than changing every call blindly:

| Caller | Use | Required policy |
|---|---|---|
| `TotalsAggregationEngine.getMonthlyTotals` | Monthly status badge vs typical history | `excludeCurrent = true` |
| `TotalsAggregationEngine.getWeeklyTotals` | Weekly status badge vs typical history | `true` if current week is in the returned list |
| `HomeViewModel.loadTotalsForYear` | Month status labels in the drill-down | `true` |
| `HomeViewModel.drillDownToPeriod` | New month/week comparison level | `true` for the comparison level |
| `HomeViewModel.drillUp` | Year/month/week status labels | `true` for completed-history comparison; retain `false` only where UI copy explicitly says “including current” |
| Explicit total/summary displays | Include-current total, not a comparison | keep `false` and add a test proving that intent |

The implementation must preserve calendar-safe month keys and partial-data warnings from `TotalsAggregationEngine`; an excluded current month is not the same as a missing rate. Add a KDoc and use named booleans at call sites so future changes are reviewable.

Tests must cover a partial current month, a day-two month, a zero-spend completed month, an incomplete/missing-rate month, and a display intentionally including current data. Update the existing tests that pin the old include-current behavior; do not weaken assertions by deleting status checks.

## Sequencing, ownership, and stop conditions

1. Land the nullable/no-burn runway model and UI migration with focused tests.
2. Land DST-safe block-party keying.
3. Land projected MoM insight using RP-06's pace result.
4. Audit and change completed-history status callers individually.

Stop for architecture review if a caller uses a magic runway sentinel, a no-burn state is rendered as exhausted/critical, likely expenses are silently deducted, a fixed-millis date key remains in the block-party path, a MoM message compares MTD to a full month, or an `excludeCurrent` change alters a surface that explicitly promises current-period totals. Stop for any Room/schema change; none is authorized here.

## Validation (not run for this documentation change)

After implementation and strict review, run sequentially with output captured:

```text
./gradlew :app:testDebugUnitTest --tests "*ComputeDashboardWidgets*" --tests "*DashboardProjectionSafety*" --tests "*ForecastRunwayIntegration*" --tests "*FinancialRunway*" --tests "*TotalsAggregationEngine*" --tests "*HomeViewModel*" --console=plain
./gradlew :app:compileDebugKotlin --console=plain
```

No Gradle command was run while preparing this plan. RP-08 and later remediation documents were intentionally not changed in this continuation.
