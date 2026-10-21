# A3 — Kotlin unit tests: domain engines, utils, analytics (+ root helpers)

Auditor: scout (Sonnet), 2026-10-16. Read-only; no build/test run. Persisted by orchestrator.

## 0. Method and confidence
- Scope: ~165 files, ~2,180 `@Test` (util 22 files, analytics 42, usecase 19, budget 14, logic 12, core 9, forecasting 7, intelligence 8, health 6, savings 5, tax 2, ~20 single-file packages).
- Read in full/large part: util `AmountUtilsStressTest`, `AmountUtilsTest`, `MerchantCleanerStressTest` (partial), `MerchantKeyGeneratorStressTest` (partial), `StatisticsUtilsStressTest`, `StringDistanceUtilsStressTest`, `BKTreeTest`, `TimePeriodUtilsValidationTest` (~260/1,010 lines), `MoneyTest` (top 130); analytics `TotalsAggregationEngineDeepTest`, `...ValidationTest` (middle), `SpendingPaceCalculatorValidationTest`/`DeepTest` (tops), `IncludeDepositsForBehaviorCleanupTest`, `BudgetVsActualEngineTest`; logic `SynthesisEngineStressTest` (assertion scan); budget `BudgetCalculatorTest`/`BoundaryTest`/`TimeBoundaryTest` (key cases), `P6BudgetCleanupTest` (320-540); `TaxCalculationTest`, `TaxEstimatorTest` (partial), `SplitCalculationPrecisionTest`, `DashboardProjectionSafetyTest`, `HistoricalSpendingDistributionBoundaryTest` (380-520), `DtoContractTest`, `TransactionClassifierTest`, `ConfidenceRouterTest` (tail), root `TestUtils.kt`.
- Names only "(names)": `NotificationIdGeneratorTest`, `CurrencyFormatterExportSafetyTest`, `CancellationSafeTest`, `SystemMonotonicTimeProviderTest`, TimePeriodUtils batch files; Insights/AdvancedAnalytics/Health/Savings/Forecasting; Negotiation, BusinessExpenseReport, PriceProtection, InvestmentTracker, CarbonFootprint.
- Not reviewed: budget `BudgetForecastingEngineTest`, `SharedBudgetManagerTest`, `BudgetAutopilotEngineTest`, `BudgetRecommendationEngineTest`, `BudgetHistorySeriesBuilderTest`; logic `RecurrenceCalculatorTest`, `CustomSplitParserTest`, other `SynthesisEngine*`; most of `core/money`, `core/time`; `CashFlowCalculatorTest`, `SubscriptionManagerEngineTest`, `MonthlySavingsSweepUseCaseTest`, `ComputeMoneyRadarUseCaseTest`, `GetMonteCarloBudgetImpactUseCaseTest`, `AutoCreateWarrantyFromReceiptUseCaseTest`, `AnomalyAlertOrchestratorTest`, `SpendingChallengeManagerTest`.
- Money / time-boundary / privacy rows need reviewer-strict before acting. Domain tests are fast (slowest ~2.5 s Robolectric warm-up) → savings here are count, LOC and flake reduction.

## 1. Summary
| Group | Files | @Test | Est. removable | Est. mergeable | LOC |
|---|---|---|---|---|---|
| util | 22 | 627 | ~160 | ~90 | ~8,600 |
| analytics | 42 | 338 | ~8 | ~48 | ~6,500 |
| budget | 14 | 160 | ~5 | ~22 | ~3,600 |
| logic | 12 | 144 | ~33 | ~8 | ~4,100 |
| core | 9 | 98 | 0 | ~5 | ~1,900 |
| tax, split, dashboard-usecase, dto, forecasting | 14 | ~140 | ~50 | ~6 | ~2,400 |
| usecase, intelligence, health, savings, others | ~55 | ~670 | ~8 | ~18 | ~11,000 |
| **Total** | **~165** | **~2,180** | **~230 (10.5%)** | **~195 (9%)** | **~38,000** |

Total reduction ~420 tests (~19%), ~5,000 LOC.

Top findings:
- **Re-implementation tautologies** (test copies the production formula and asserts the copy): `DashboardProjectionSafetyTest`, ~20 of `SplitCalculationPrecisionTest`, ~25 of `TaxCalculationTest`, 2 of `P6BudgetCleanupTest`.
- **Non-null smoke tests:** ~40 of 56 in `SynthesisEngineStressTest`.
- SpendingPace, BudgetCalculator, TotalsAggregation, TimePeriodUtils each have 3–8 files re-asserting the same cases.
- **12 flaky wall-clock `System.nanoTime()` assertions:** MerchantCleanerStress (1), MerchantKeyGeneratorStress (3), StatisticsUtilsStress (3), StringDistanceUtilsStress (3), AnalyticsStress (1, 10 s), SynthesisEngineStress (1).
- `AmountUtilsStressTest` mutates `Locale.setDefault` without `GlobalTimeZoneTestLock`; `:520` has no try/finally (leaks locale on failure).

## 2. Non-KEEP files

### 2a. DELETE-CANDIDATE
| File | Evidence | Action | Saved | Risk |
|---|---|---|---|---|
| `usecase/dashboard/DashboardProjectionSafetyTest.kt` | All 6 re-implement formulas inline (:25, :36, :50, :67, :83, :97) | Delete; covered by `FinancialRunwayNoBurnTest` (:171, :340), `ComputeDashboardWidgetsUseCasePaceWiringTest`. Verify `daysElapsed == 0` projection is covered | 6 | low |
| `split/SplitCalculationPrecisionTest.kt` | Tests test-local helpers "Mirrors EnhancedSplitManager" (:323, :347); :291, :308 tautologies | Delete ~20; move exact largest-remainder cases vs real class into `SplitCalculatorTest` | ~20 | low-med (money) |
| `tax/TaxCalculationTest.kt` | Test-local `calculateProgressiveTax`/`calculateVat` (:392-432); constants pinned; :314/:326/:337 `> 0.0`; :74 `@Ignore`; `TaxEstimatorTest` :182/:195 proves exact values | Delete @Ignore + weak + local-formula tests; one config table (GR VAT 24%, GR brackets, **US brackets :226/:237 sole proof**, factory); update TEST_IGNORE_CLASSIFICATION rows 12-13 | ~25/35 | low-med (money) |
| `util/AmountUtilsStressTest.kt` | 65 tests; no-assert/weak :80, :103, :112, :548, :416; unseeded-random fuzz :323/:340/:443/:464; locale tests assert same results; :484-513 restate `AmountUtilsTest` :30-77; :240/:536 dup :233 | Fold unique parse cases (`"1,23,456"`→null, unicode minus, NBSP/NNBSP, `(50)`, `E50`, symbol prefix/suffix, 1,000,000 boundary) into an `AmountUtilsTest` table; delete rest | ~48 | low |
| `util/StatisticsUtilsStressTest.kt` | 35 tests; weak `stdDev > 0` (:38, :46, :81, :94, :116, :124, :205); :136-193 accept either result; perf wall-clock :104/:358/:374 | Keep ~10 exact-value cases as a table | ~25 | low |
| `util/StringDistanceUtilsStressTest.kt` | 28 tests; `> 0` where exact knowable (:14, :46, :71); perf :27/:150/:167; :179 pins an emoji false-positive BUG as expected | One exact table; delete perf; decide emoji pin with owner | ~14 | low |
| `util/MerchantKeyGeneratorStressTest.kt` | Duplicates `MerchantKeyGeneratorTest` (empty, Latin, numbers, special chars, consistency, Greek); weak `isNotEmpty` :95/:134/:165/:171; perf :100/:110/:150 | Fold unique (emoji :123/:128, tab/NUL :144/:159, 10k length) into one table; exact transliteration asserts | ~20 | low |
| `forecasting/HistoricalSpendingDistributionBoundaryTest.kt` | :384/:408/:428/:464/:495 test only `TimePeriodUtils` | Delete 5 (covered by T4CBatch2B :198/:225, `TimePeriodUtilsTest` :467/:527) | 5 | low |
| `budget/P6BudgetCleanupTest.kt` | :326/:390 re-implement weekly logic; :432/:471 regex private vals via relative path; :499 reflection | Delete 5 after adding a real-calculator Sunday-anchor case to `BudgetCalculatorBoundaryTest` (P6-016 regression only covered by replica); BudgetRepository 365+ rollover bound → STRENGTHEN if no behavioural test | 4 net | med |
| `analytics/IncludeDepositsForBehaviorCleanupTest.kt` | `File("app/src/main/java")` walk passes vacuously if cwd ≠ repo root | Delete | 1 | low |
| `dto/DtoContractTest.kt` | Ctor read-back / copy / default-null; "unknown enum" uses valid values (:143) | Delete | 4 | low |
| `util/SystemMonotonicTimeProviderTest.kt` (names) | :26 type tautology; :47 tests the fake | Delete 2, keep :35 | 2 | low |
| `logic/SplitCalculatorTest.kt` vs `SplitCalculatorGoldenTest.kt` | 4 identically named cases | Delete the 4 from `SplitCalculatorTest` (Golden is doc-cited) | 4 | low |

### 2b. STRENGTHEN
| File | Evidence | Action | Risk |
|---|---|---|---|
| `logic/SynthesisEngineStressTest.kt` (56) | `assertNotNull(forecast)` at ~40 sites; `>= 0` bounds at :170, :402, :429, :430, :523, :577, :1638; names describe knowable money outcomes (Feb 29, DST, zero budget, NaN) | Delete ~33 smoke; table the 5 frequency tests (:1034-1130); convert 8 to exact `components.*`; keep 10 that already assert | high (money) |
| Root `TestUtils.kt:115-209` `assertApproxEquals` | Default tolerance 0.01 (a full cent) for Double, 0.1f Float; used by 140+ files; mojibake `Â±` | Add exact `assertMoneyEquals`; migrate Totals/Pace/Tax first | med |
| `carbon/CarbonFootprintCalculatorTest.kt` | non-null / `> 0.0` / 0..100 bounds | Exact emission-factor table | low |
| `engine/DashboardFollowThroughEngineTest.kt` | ~24 weak asserts in 20 tests | Assert exact filter fields (doc-cited ai-fixes impact map) | low |
| `analytics/TotalsAggregationEngineDeepTest.kt` | :100 `isNotEmpty`; :170-173 asserts nothing for MONTH | Exact yearly sum / MONTH avg ≈ 133.33 | low (money) |
| `intelligence/TransactionClassifierTest.kt` | :97/:111/:130 no assertions (:111 swallows) | Delete 3, merge 3→1 | low |
| `intelligence/ConfidenceRouterTest.kt` | :170 constants; :161 `<=1 && >=0` | Delete :170; exact clamp | low |
| `budget/BudgetForecastingEngineDiagnosticsTest.kt` (SLOW) | Robolectric only for `SQLiteConstraintException` | Plain exception, drop Robolectric | low |

### 2c. MERGE
| Cluster | Evidence | Target | Saved | Risk |
|---|---|---|---|---|
| **TimePeriodUtils** (Validation 77, Test 57, Stress 40, T4C 1/2A/2B/2C/2D = 25/20/19/28/36 → 302) | Validation repeats Test and Stress (starts/ends, inclusion at 00:00 / 23:59:59.999, `isInRange`, month arithmetic, DST); Validation depends on default JVM zone; T4C pairs exact/oracle per unit and repeats extremes across batches | Keep T4C as canonical; delete ~45 Validation, ~15 Stress; zone-parameterised table; merge T4C pairs (~12) | ~70 | med (time-boundary) |
| `util/CurrencyFormatterExportSafetyTest.kt` (names) | Non-finite throws × 6 functions × 3 values | One table; **sole proof non-finite money fails closed** | 9 | low-med |
| `util/NotificationIdGeneratorTest.kt` (names, 43) | per-kind ranges, wraps, extension delegation, negatives | Tables by kind | ~15 | low |
| `util/CancellationSafeTest.kt` | subclass variants :38/:64 repeat :27/:53 | Table rows | 2 | low |
| `util/MoneyTest.kt` | **PINNED** (denylist :39, gate :934/:981) | Optional table rows in same class only with owner approval | ≤5 | high — skip |
| `util/MerchantCleanerStressTest.kt` (35) | Sole cleaner test; repetitive time/date/card/stop-word cases; perf :241 | Table, drop perf, rename `MerchantCleanerTest` | ~10 | low |
| **TotalsAggregationEngine\*** (62+18+5+7+3) | status ×11 across files; averages; category %; repo-exception ×5; empties | 4 tables; keep fail-closed money (:393-452), cancellation, StatusBaseline | ~25 | med (money) |
| **SpendingPace\*** (14+6+4+2) + Insights pace | pace ratios, projections, month-ends repeated | pace table, projection table, exclusion test | ~12 | med (money) |
| **BudgetCalculator\*** (16+16+11+3) | Jan 31, anchor 30→Feb, Feb 29, rolling/calendar yearly, past/future, DST repeated; Test uses weaker Calendar-field checks | Keep TimeBoundary + Golden; table; delete Calendar-field dups | ~14 | med (time) |
| `budget/BudgetMonitorStressTest` vs `BudgetMonitorTest` | 4 equivalent pairs | Threshold table in Test | ~4 | low-med |
| `core/money/CurrencyNormalizationBehavioralTest` vs `currency/CurrencyNormalizationBehavioralTest` | Same simple name in 2 packages; 5 duplicate cases (also breaks `--tests` filters) | Merge one into the other | 5 | med (money) |
| `negotiation/NegotiationEngineTest.kt` (46, names) | invalid-amount ×13, billing-cycle ×4 | Tables | ~10 | low-med |
| `business/BusinessExpenseReportGeneratorTest.kt` (42, names) | leaked type exclusion ×9 | One table | ~8 | low-med |
| `forecasting/MonteCarloSpendingSimulatorTest.kt` :82-140 | Feb daysRemaining ×4; overlaps `ComputeDashboardWidgetsUseCaseDaysRemainingBoundaryTest` | Table | 3 | low |
| `health/FinancialHealthCalculatorTransactionTypeTest.kt` (names) | same assertion × type | Table | ~4 | low-med |
| `analytics/InsightsEngine*` (names) | empty ×3; cancellation ×7; weak EdgeCase | One empty test, cancellation table, strengthen | ~6 | med |
| `analytics/AdvancedAnalyticsEngine*` (names) | empty ×3, weekend-warrior ×2 | Merge | ~3 | low |
| Plain + NormalizedMoney pairs (DayOfWeek, CategoryInsight, SpendingPersonality) | two files per engine | Check overlap; low priority | 0-4 | — |

### 2d. SLOW / other
- Remove the 12 wall-clock `nanoTime` assertions (flaky); `AnalyticsStressTest` asserts exact total → keep, relax budget.
- Robolectric removal: `BudgetForecastingEngineDiagnosticsTest`; `ComputeMoneyRadarUseCaseTest`, `DashboardBudgetProvenanceChainTest` need Context — check if only for strings.
- Dead helpers in root `TestUtils.kt` (`assertWithinPercent`, `assertTotalEffective`, `assertEffectiveAmount`, `endOfMonthInclusive`, `getTestCategory`; probably `TEST_CATEGORIES`): ~60 LOC, no external callers. `AnalyticsTestCompat.kt` is active — keep.

## 3. Consolidation clusters
1. Time boundaries → T4C + one `TimePeriodUtilsTest` (~70).
2. Formula-copy tests → delete (~55).
3. Smoke-only stress classes → delete/table (~170).
4. Budget windows → TimeBoundary/Golden table (~14).
5. Totals aggregation → 4 tables (~25).
6. Spending pace → 3 tests (~12).
7. FooTest/FooDeepTest/FooValidationTest/FooEdgeCaseTest/FooStressTest pattern → FooTest (behaviour) + FooGoldenTest (pinned numbers) + FooMoneyTest (typed money, keep).
8. Cross-package `CurrencyNormalizationBehavioralTest` duplicate (5).

## 4. PINNED and sole-proof
- **PINNED:** `util/MoneyTest.kt` (`config/release_block_denylist.yml:39`, `FINAL_CI_GUARD_ACCEPTANCE_GATE.md:934/:981`, `scripts/verify_ignored_test_budget.py`). `TaxCalculationTest`, `NotificationIdGeneratorTest` in `docs/testing/TEST_IGNORE_CLASSIFICATION.md` rows 12-14 (stale).
- Doc-cited (update docs on change): `CancellationSafeTest` (CI_GUARDRAILS_BASELINE.md:216), `BudgetCalculatorTimeBoundaryTest` (TIME_SEMANTICS.md:503), Golden classes (GOLDEN_SCENARIO_IMPLEMENTATION_PLAN.md), T4C batches (GR00-GR04 findings), util stress classes (COVERAGE_REPORT.md, TESTING_ASSESSMENT.md), `DashboardFollowThroughEngineTest`, `SplitCalculationPrecisionTest` (GUARDRAIL_STATE_REPORT §5.3.1), `BudgetMonitorTest` (TEST_FAILURE_LEDGER:32).
- **Sole proofs:** `AmountUtilsTest :9` (diagnostics never contain input text); AmountUtils malformed-grouping/unicode-minus/NBSP/E50/1,000,000 cases; `MerchantCleanerStressTest`; `MerchantKeyGeneratorTest :52`; `CurrencyFormatterExportSafetyTest` non-finite + raw-code; T4C DST 23/25 h, last-ms-exclusive, cutover, extremes; `TimePeriodUtilsTest :376/:393` (SQLite week keys); `BudgetCalculatorTimeBoundaryTest :224/:244`; `TaxEstimatorTest :182/:195/:330/:418`; `TaxCalculationTest` US brackets; `SpendingPaceCalculatorDeepTest :26`; `TotalsAggregationEngineTest :393-452`; all `*Money*`/`*Provenance*` unavailable-not-zero & cancellation tests; `FinancialRunwayNoBurnTest`, `ComputeDashboardWidgetsUseCasePaceWiringTest`.

## 5. Recommended order
1. Dead/tautological deletes (~60).
2. Stress files → tables; remove 12 wall-clock asserts (~120).
3. Formula copies `SplitCalculationPrecisionTest`, `TaxCalculationTest` (~45).
4. `SynthesisEngineStressTest` (~33 delete, 8 strengthen) — reviewer-strict.
5. TimePeriodUtils family (~70) — reviewer-strict.
6. Totals/Pace/BudgetCalculator/BudgetMonitor/Insights (~60) — reviewer-strict.
7. Small merges.
8. Optional: Robolectric removal, P6 replacement, `assertMoneyEquals`, MoneyTest rows (pinned).
