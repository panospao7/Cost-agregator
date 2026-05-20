# Remaining Implementation Plan — post-`c62de2be3f117f14f0a1dc9053ebc97ae21d883c`

## Baseline
Latest reviewed commit:
`c62de2be3f117f14f0a1dc9053ebc97ae21d883c`

### Keep as-is
Do not rework these unless a test proves they are broken:
- `getLatestRateForPair(...)` exists
- `ConversionOutcome` / `RateBasis` / `ConversionPath` exist
- `MoneyNormalizationEngine` exists
- `HomeCurrencyResolution` exists
- dashboard trend raw fallback was removed
- budget `ON_TRACK` → `UNKNOWN` fix exists

---

## Remaining issue map
- **CURR-C62-01**: new exchange-rate inserts can still have `validDate = 0`
- **CURR-C62-02**: migration backfill uses `lastUpdated`, not start-of-day
- **CURR-C62-03**: historical `convertOutcome(...)` can silently downgrade to latest
- **CURR-C62-04**: staleness comparison still uses the wrong timestamp basis
- **CURR-C62-05**: composite EUR conversion loses per-leg provenance
- **CURR-C62-06**: `MoneyAggregate.rateBasis` defaults are wrong in many paths
- **CURR-C62-07**: `MoneyAggregate` lacks quality/metadata fields
- **CURR-C62-08**: `MoneyNormalizationEngine` is not provenance-complete
- **CURR-C62-09**: `BucketDatePolicy.RequireBucketDate` is not enforced strictly
- **CURR-C62-10**: silent EUR fallback still exists in consumers
- **CURR-C62-11**: `MultiCurrencyRepository` is still ambiguous/latest-rate centric
- **CURR-C62-12**: dashboard/analytics still not fully canonical-normalized
- **CURR-C62-13**: forecast/cashflow paths still use legacy/raw currency logic
- **CURR-C62-14**: budget remaining/rollover can still mix currencies on limit failure
- **CURR-C62-15**: `BudgetForecastingEngine` still falls back to raw budget amount
- **CURR-C62-16**: cashflow still uses latest-rate conversions and lacks provenance
- **CURR-C62-17**: no static money-boundary guard
- **CURR-C62-18**: behavioral tests are still missing

---

# PR 1 — Exchange-rate correctness hotfix
## Goal
Make rate storage and latest/as-of lookup semantically correct.

## Files
- `ExchangeRateDao.kt`
- `ExchangeRateStoreAdapter.kt`
- `CurrencyConverter.kt`
- `ExchangeRate.kt`
- migration `131` or new `132`
- exchange-rate tests

## Tasks
- [ ] Ensure every newly stored rate gets a non-zero `validDate`.
  - Manual rates: use start-of-day for “today”.
  - Provider/API rates: use provider date if available, otherwise explicit current-day valid date.
- [ ] Fix the migration strategy for old rows:
  - if `131` has not shipped, backfill `validDate = startOfDay(lastUpdated)`.
  - if `131` is already shipped, add `132` to correct zero-dated rows and normalize semantics.
- [ ] Define a policy for `validDate = 0`:
  - best option: do not allow it for dated production rates.
- [ ] Keep `getLatestRateForPair(...)` ordered by `validDate DESC, lastUpdated DESC`.
- [ ] Keep `getRateAsOf(...)` using `validDate <= atMillis`.
- [ ] Add comments documenting that latest lookup is date-based, not update-time-based.

## Acceptance tests
- [ ] `manual_rate_insert_sets_validDate`
- [ ] `latest_rate_prefers_fresh_manual_rate_over_historical_row`
- [ ] `migration_backfills_validDate_to_start_of_day`
- [ ] `same_day_historical_rate_applies_to_morning_expense`
- [ ] `validDate_zero_rows_are_not_used_as_latest_when_dated_rows_exist`

## Done when
Fresh user/API rates cannot be ignored by historical rows, and as-of lookup behaves like a true historical lookup.

---

# PR 2 — ConversionOutcome / rate-basis hardening
## Goal
Historical requests must not silently turn into latest-rate requests.

## Files
- `CurrencyConverter.kt`
- `ConversionOutcome.kt`
- `ConversionFailureType.kt`
- `StaleRatePolicy.kt`
- `ConversionPath.kt`
- tests

## Tasks
- [ ] Fail closed if a historical rate basis is requested without `atMillis`.
  - `TRANSACTION_DATE`, `PERIOD_START`, `PERIOD_END`, `FORECAST_DATE` must require a date.
- [ ] Do not silently fall back to latest rates for historical basis requests.
- [ ] Fix staleness evaluation:
  - historical conversions should compare against `validDate`
  - latest conversions may compare against `lastUpdated`
- [ ] For composite EUR conversions, preserve weakest-leg provenance:
  - carry oldest `validDate`
  - carry latest `lastUpdated`
  - mark path as `VIA_BASE_CURRENCY`
- [ ] Make sure `ConversionOutcome.Failed` is used whenever the basis cannot be honored.
- [ ] Keep identity conversion explicit as `IDENTITY`.

### Suggested rule
```kotlin
if (rateBasis is historical && atMillis == null) fail(...)
```

## Acceptance tests
- [ ] `convertOutcome_transaction_date_without_date_fails`
- [ ] `convertOutcome_period_end_without_date_fails`
- [ ] `convertOutcome_forecast_date_without_date_fails`
- [ ] `convertOutcome_never_silently_uses_latest_for_historical_basis`
- [ ] `stale_historical_rate_uses_validDate_not_lastUpdated`
- [ ] `composite_rate_uses_oldest_validDate_for_staleness`

## Done when
A caller must explicitly choose latest vs historical behavior, and missing date context can no longer be hidden.

---

# PR 3 — MoneyAggregate v2 + normalization provenance
## Goal
Every aggregate must carry enough metadata to explain how it was produced.

## Files
- `MoneyAggregate.kt`
- `MoneyAggregateBuilder.kt`
- `MoneyNormalizationEngine.kt`
- `MoneyBucketInput.kt`
- `NormalizationResult.kt`
- `BucketDatePolicy.kt`
- domain money tests

## Tasks
- [ ] Expand `MoneyAggregate` with:
  - `requestedRateBasis`
  - `actualRateBasis`
  - `conversionQuality`
  - `metadata`
- [ ] Add metadata counters:
  - included count
  - excluded count
  - stale-rate count
  - missing-rate count
  - invalid-currency count
  - latest/oldest valid-date timestamps
- [ ] Remove ambiguous defaults:
  - `MoneyAggregate.empty(...)` should accept basis
  - `MoneyAggregate.singleCurrency(...)` should accept basis
  - `MoneyAggregate.partial(...)` should accept basis
- [ ] Make `MoneyNormalizationEngine` emit provenance-rich normalized rows:
  - original amount
  - original currency
  - normalized amount
  - normalized currency
  - rate basis
  - rate used
  - rate valid date
  - rate last updated
  - conversion path
- [ ] Enforce `BucketDatePolicy.RequireBucketDate`:
  - if date is required and missing, fail immediately
  - do not pass null into conversion
- [ ] Treat “latest fallback” as explicit estimated behavior only when the caller requested it.

## Acceptance tests
- [ ] `aggregateExpenses_transaction_date_sets_requested_rateBasis`
- [ ] `aggregateBuckets_period_end_sets_rateBasis_period_end`
- [ ] `empty_aggregate_preserves_explicit_rateBasis`
- [ ] `single_currency_identity_does_not_default_to_latest`
- [ ] `aggregate_metadata_counts_missing_and_excluded_rows`
- [ ] `bucket_policy_require_date_missing_date_fails`

## Done when
Aggregates no longer look like opaque totals; they explain basis, quality, and failure counts.

---

# PR 4 — Home currency resolution without silent EUR fallback
## Goal
Financial calculations must not silently become EUR when settings are unavailable.

## Files
- `CurrencySettingsRepositoryImpl.kt`
- `CurrencySettingsRepository.kt`
- `BudgetRepository.kt`
- `ComputeDashboardWidgetsUseCase.kt`
- `BudgetForecastingEngine.kt`
- `CashFlowCalculator.kt`
- export/import callers
- UI consumers

## Tasks
- [ ] Restrict `homeCurrency(): Flow<String>` to settings/UI only.
- [ ] Migrate financial callers to `resolveHomeCurrency()` or a resolution-aware flow.
- [ ] On `HomeCurrencyResolution.Failed`, return partial/unavailable instead of EUR.
- [ ] Preserve `FirstRunDefault(EUR)` only as an explicit first-run state.
- [ ] Remove `getOrDefault("EUR")` from financial math paths.
- [ ] Remove local helper methods that catch and replace failures with EUR.
- [ ] Make failures visible in metadata/UI.

## Acceptance tests
- [ ] `budget_home_currency_failure_status_unknown`
- [ ] `dashboard_home_currency_failure_shows_unavailable`
- [ ] `forecast_home_currency_failure_returns_partial_state`
- [ ] `cashflow_home_currency_failure_no_silent_eur`
- [ ] `first_run_default_is_explicit_not_failure`

## Done when
No numeric report path can quietly default to EUR on settings failure.

---

# PR 5 — Split `MultiCurrencyRepository` into explicit latest vs historical APIs
## Goal
Callers must choose a rate basis explicitly.

## Files
- `MultiCurrencyRepository.kt`
- callers in dashboard/budget/analytics/export
- deprecation annotations/tests

## Tasks
- [ ] Rename ambiguous methods into explicit families:
  - historical methods
  - latest-rate methods
- [ ] Keep latest-rate methods only for current valuation.
- [ ] Historical methods must use transaction-date normalization per row, not bucket-level hidden latest conversion.
- [ ] Deprecate old `Result<Double>` / `Map<..., Double>` APIs.
- [ ] Make return types use `MoneyAggregate` or typed wrappers.
- [ ] Stop pretending historical totals are latest-rate totals.

### Suggested naming
- `getHomeCurrencyTotalLatestRate(...)`
- `getHomeCurrencyTotalHistorical(...)`
- `getCategoryTotalsHistorical(...)`
- `getCategoryTotalsLatestRate(...)`

## Acceptance tests
- [ ] `historical_api_uses_per_row_transaction_date_rates`
- [ ] `latest_api_uses_latest_rate_only`
- [ ] `legacy_double_aggregate_apis_are_deprecated_or_internal`
- [ ] `monthly_totals_no_longer_share_ambiguous_basis`

## Done when
Every consumer knows whether it asked for historical accuracy or latest valuation.

---

# PR 6 — Dashboard / analytics migration to canonical normalized input
## Goal
Dashboard widgets must use one normalized pipeline and surface quality information.

## Files
- `ComputeDashboardWidgetsUseCase.kt`
- analytics/dashboard adapters
- `AnalyticsCurrencyNormalizer.kt`
- dashboard UI models
- `BudgetScreen.kt`
- `NarrativeGenerator.kt`

## Tasks
- [ ] Replace ad-hoc dashboard conversions with normalized money input.
- [ ] Make the trend use `TRANSACTION_DATE` basis.
- [ ] Make category/summary widgets share the same basis as the trend.
- [ ] Remove any remaining silent raw foreign fallback.
- [ ] Attach quality metadata to dashboard widgets:
  - partial
  - missing rate count
  - stale rate count
  - invalid currency count
  - warning message
- [ ] Propagate `UNKNOWN` / partial signals to UI text and narratives.
- [ ] Keep trend/summary/category consistent with each other.

## Acceptance tests
- [ ] `dashboard_summary_and_category_use_same_rate_basis`
- [ ] `spending_trend_uses_transaction_date_rates`
- [ ] `spending_trend_missing_rate_marks_partial`
- [ ] `dashboard_home_currency_failure_no_silent_eur`
- [ ] `budget_unknown_status_is_rendered_explicitly`

## Done when
The dashboard is based on one canonical normalized currency model and never hides quality loss.

---

# PR 7 — Budget / forecast / cashflow normalization migration
## Goal
Budget, forecast, and cashflow cannot mix raw foreign values with home-currency math.

## Files
- `BudgetRepository.kt`
- `BudgetForecastingEngine.kt`
- `CashFlowCalculator.kt`
- budget/forecast models
- budget UI
- forecast tests

## Tasks
- [ ] Remove raw budget amount fallback from forecasting.
- [ ] Use `convertOutcome(...)` instead of raw `convert(...)` in critical math.
- [ ] If budget limit conversion fails:
  - do not compute misleading remaining/rollover numbers
  - set health/reliability to unknown/failed
- [ ] Use explicit bases:
  - actual expenses → `TRANSACTION_DATE`
  - budget limit → `PERIOD_END` or explicit policy
  - recurring forecasts → `FORECAST_DATE`
- [ ] Make cashflow output carry partial/failed conversion metadata.
- [ ] Do not drop conversion failures silently without marking the day partial.
- [ ] Keep limit/spend comparisons in the same currency basis only.

## Acceptance tests
- [ ] `budget_limit_conversion_failure_does_not_use_raw_amount`
- [ ] `budget_remaining_not_mixed_currency_when_limit_conversion_fails`
- [ ] `budget_forecast_missing_rate_status_unknown_or_unavailable`
- [ ] `cashflow_actual_uses_expense_date_rate`
- [ ] `cashflow_forecast_uses_forecast_date_basis`
- [ ] `cashflow_missing_rate_marks_day_partial`

## Done when
Budget, forecast, and cashflow math never silently mix incompatible currencies.

---

# PR 8 — Transaction/source conversion provenance + import/export correctness
## Goal
Preserve original and normalized values at source boundaries.

## Files
- create-expense request/models
- bank import models
- receipt/email/import models
- export models/manifests
- accounting/export code
- repository/entity mappers

## Tasks
- [ ] Add a `ConversionSnapshot` or equivalent to source-boundary models.
- [ ] Preserve:
  - original amount
  - original currency
  - normalized amount
  - normalized currency
  - rate basis
  - rate used
  - rate valid date
  - rate last updated
  - conversion status
- [ ] Do not overwrite original source currency with home-currency values.
- [ ] Make bank imports preserve source currency and conversion quality.
- [ ] Make exports include conversion basis/quality in the manifest.
- [ ] Accounting exports must not silently convert mixed currencies.
- [ ] If conversion is unavailable, export should warn or fail explicitly.

## Acceptance tests
- [ ] `foreign_currency_expense_preserves_original_amount_and_currency`
- [ ] `bank_import_preserves_original_currency`
- [ ] `json_export_includes_rate_basis_and_conversion_status`
- [ ] `accounting_export_rejects_mixed_currency_without_explicit_policy`
- [ ] `export_manifest_records_conversion_quality`

## Done when
Original money survives intact, and normalized money is stored explicitly rather than implied.

---

# PR 9 — Static money-boundary guard
## Goal
Prevent regression back to mixed-currency arithmetic.

## Files
- `scripts/verify_money_boundaries.py`
- CI workflow
- guard fixtures/tests

## Tasks
- [ ] Add a money-boundary guard script.
- [ ] Fail on raw mixed-currency sums in production paths:
  - `sumOf { it.amount }`
  - `sumOf { it.effectiveAmount }`
  - `?: effectiveAmount`
- [ ] Fail on silent EUR fallback in financial math:
  - `getOrDefault("EUR")`
  - hardcoded `"EUR"` fallback in repositories/use cases
- [ ] Fail on ambiguous legacy APIs:
  - `Result<Double>`
  - `Map<..., Double>`
  - ambiguous `getRate()` calls
- [ ] Fail on raw conversion fallback in dashboards/budgets/forecasting.
- [ ] Allowlist only:
  - row display code
  - source-bucket construction
  - tests
  - debug fixtures
- [ ] Wire the script into CI.

## Acceptance tests
- [ ] `money_guard_fails_on_sumOf_effectiveAmount_in_dashboard`
- [ ] `money_guard_fails_on_raw_conversion_fallback`
- [ ] `money_guard_fails_on_silent_eur_default`
- [ ] `money_guard_fails_on_ambiguous_exchange_rate_getRate`
- [ ] `money_guard_allows_row_display_original_amount`

## Done when
The repository cannot easily drift back to implicit mixed-currency math.

---

# PR 10 — Behavioral regression tests
## Goal
Prove the live paths, not just model contracts.

## Files
- new integration tests
- existing currency/dashboard/budget/forecast/cashflow tests
- in-memory DB test fixtures

## Must cover
- [ ] Exchange-rate latest vs historical semantics
- [ ] validDate migration behavior
- [ ] `convertOutcome(...)` missing-date failure
- [ ] stale-rate comparison correctness
- [ ] `MoneyNormalizationEngine` provenance
- [ ] `MoneyAggregate` basis/metadata
- [ ] dashboard trend/summary/category consistency
- [ ] budget failure handling
- [ ] forecast raw-fallback removal
- [ ] cashflow partial behavior
- [ ] silent EUR fallback removal
- [ ] import/export provenance

## Suggested tests
- `latest_rate_prefers_fresh_manual_rate`
- `historical_rate_uses_as_of_date`
- `convertOutcome_transaction_date_without_date_fails`
- `aggregateExpenses_transaction_date_sets_rateBasis`
- `dashboard_summary_and_category_use_same_rate_basis`
- `budget_limit_conversion_failure_health_unknown`
- `budget_forecast_missing_rate_does_not_use_raw_amount`
- `cashflow_missing_rate_marks_day_partial`
- `home_currency_failure_does_not_silent_eur`

## Done when
The exact bugs identified in review would fail before the fix and pass after it.

---

# PR 11 — Docs / cleanup / drift removal
## Goal
Make the new currency rules understandable and maintainable.

## Tasks
- [ ] Add a clear `docs/privacy-or-currency-boundaries.md` or similar for future agents.
- [ ] Document:
  - rate basis rules
  - latest vs historical semantics
  - home currency failure handling
  - aggregate quality metadata
  - allowed raw-money exceptions
- [ ] Remove stale comments that imply latest-rate fallback is acceptable for historical paths.
- [ ] Remove dead or legacy helper methods once callers are migrated.
- [ ] Update debugging/master tracker with the final status of each currency issue.

## Done when
The docs and the code tell the same story.

---

# Recommended execution order
1. PR 1 — Exchange-rate correctness hotfix
2. PR 2 — ConversionOutcome / rate-basis hardening
3. PR 3 — MoneyAggregate v2 + normalization provenance
4. PR 4 — Home currency resolution without silent EUR fallback
5. PR 5 — Split `MultiCurrencyRepository`
6. PR 6 — Dashboard / analytics migration
7. PR 7 — Budget / forecast / cashflow migration
8. PR 8 — Transaction/source provenance + import/export correctness
9. PR 9 — Static money-boundary guard
10. PR 10 — Behavioral regression tests
11. PR 11 — Docs / cleanup / drift removal

---

# Final definition of done
Global currency issue #4 is complete only when:

1. New exchange-rate inserts never leave `validDate = 0`.
2. Historical lookups are truly historical.
3. Historical requests cannot silently become latest-rate requests.
4. Rate staleness uses the right timestamp basis.
5. `MoneyAggregate` carries explicit quality/basis metadata.
6. `MoneyNormalizationEngine` is the canonical path for normalized totals.
7. No financial math silently defaults to EUR.
8. Latest-rate and historical APIs are explicitly separated.
9. Dashboard, budget, forecast, and cashflow all use normalized money consistently.
10. Limit/forecast/cashflow failures return partial/unknown instead of misleading numbers.
11. Source/import/export preserve original and normalized money explicitly.
12. Static guards prevent raw mixed-currency arithmetic regressions.
13. Behavioral tests prove the live paths.

## Sources reviewed
- Commit: https://github.com/panospao7/Cost-agregator/commit/c62de2be3f117f14f0a1dc9053ebc97ae21d883c
- `CurrencyConverter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt
- `ExchangeRateDao.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt
- `CurrencySettingsRepositoryImpl.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/data/repository/CurrencySettingsRepositoryImpl.kt
- `MoneyAggregate.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregate.kt
- `MoneyNormalizationEngine.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyNormalizationEngine.kt
- `MultiCurrencyRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt
- `ComputeDashboardWidgetsUseCase.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
- `BudgetRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt
- `BudgetForecastingEngine.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt
- `CashFlowCalculator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt
- `global_currency_normalization_moneyaggregate_plan.md`