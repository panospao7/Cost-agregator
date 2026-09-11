# Batch 18 — domain currency / dashboard / debug / dto / engine / export / forecasting / groups / health / income

Scope: Segments 16 (Currency & Exchange, incl. ★ APPROVED TYPE ★ money-adjacent tests), 10 (dashboard fixes), 29 (debug), 20 (engine/dto), 18 (export security), 1+13 (forecasting), 24+25 (groups), 35 (health), 31 (income) · Files: 35 · LOC: 9,119 · Tests: 313 · @Ignore: 0
Reviewer notes: This is the money-math strict area (Segments 16/17). Currency tests protect the ★ APPROVED TYPE ★ primitives (MoneyAggregate rateBasis/quality/isPartial, MoneyNormalizationEngine, CurrencyConverter staleness 24h) from the write side; the primitive types themselves (MoneyAmount, MoneyAggregateBuilder) are covered in batch-17 (`domain/core/money/`) and batch-08 (`scenarios/` — incl. the unique `MoneyAmount.plus` cross-currency throw at `scenarios/MoneyAggregateConversionScenarioTest.kt:460`). No file in this batch asserts raw Double sums across currencies and none touches the deprecated `getTotalPortfolioValue`/`getTotalSpentBetween` LEGAL_PATHS violations — verified by grep. F-07 (forecast/analytics delta drift, ~20 measured failures in `domain.forecasting.*`) applies to the golden/boundary files below. A same-name class collision exists: `domain/currency/CurrencyNormalizationBehavioralTest` (this batch) vs `domain/core/money/CurrencyNormalizationBehavioralTest` (batch-17) — related but complementary, see findings.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 29 | 4 | 0 | 1 | 1 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/domain/currency/ConversionSemanticsHardeningTest.kt | 332 | 16 | 0 | PURE | CurrencyConverter, ExchangeRateStoreAdapter | KEEP | P0 | ConversionTest, NormalizationBehavioral, batch-17 same-name | RateBasis/stale-policy/composite provenance + storage boundary; excellent |
| 2 | test/…/domain/currency/CurrencyConversionTest.kt | 404 | 28 | 0 | MOCKED | CurrencyConverter, SupportedCurrency | STRENGTHEN | P1 | #1, #3, #4 | Solid core; 1 tautology (local SupportedCurrency list), mock-stubs use System.currentTimeMillis |
| 3 | test/…/domain/currency/CurrencyConverterEdgeCaseTest.kt | 148 | 7 | 0 | MOCKED | CurrencyConverter | KEEP | P1 | #1, #2 | Drift bound, NaN/Inf rate rejection, sign preservation |
| 4 | test/…/domain/currency/CurrencyConverterGoldenTest.kt | 62 | 2 | 0 | GOLDEN | CurrencyConverter | KEEP | P0 | #2 | GBP→JPY 19012.50 cross-rate golden |
| 5 | test/…/domain/currency/CurrencyConverterStressTest.kt | 70 | 2 | 0 | STRESS | CurrencyConverter | KEEP | P2 | #3 | 2×500 iters, sub-second; fine for PR CI (prior NIGHTLY suggestion unnecessary) |
| 6 | test/…/domain/currency/CurrencyNormalizationBehavioralTest.kt | 268 | 10 | 0 | PURE | MoneyNormalizationEngine, MoneyAggregate | KEEP | P0 | batch-17 same-name class, #1 | isPartial/excluded/missingRate counters + rateBasis propagation |
| 7 | test/…/domain/currency/HomeCurrencyResolutionTest.kt | 90 | 6 | 0 | PURE | HomeCurrencyResolution, CurrencySettingsRepository | KEEP | P1 | ForecastInputAssemblerTest fail-closed test | Fail-closed resolution incl. exception→Failed |
| 8 | test/…/domain/currency/MultiCurrencyTestFixture.kt | 151 | 0 | 0 | FIXTURE | (fixture) | DELETE | P2 | — | Orphaned: zero references outside itself (prior 2026-05 "used by dashboard/budget/analytics" no longer true) |
| 9 | test/…/domain/dashboard/P5AnalyticsFixesTest.kt | 284 | 6 | 0 | MOCKED | MultiCurrencyRepository, MoneyAggregateBuilder, ComputeDashboardWidgetsUseCase | REWRITE | P1 | MultiCurrencyRepositoryTest, scenarios MoneyAggregateBuilderTest | 2 real tests; 4 reflection/tautology (asserts own inline filter, java.time stdlib) |
| 10 | test/…/domain/debug/ServiceDiagnosticsTest.kt | 281 | 11 | 0 | ROBOLECTRIC | ServiceDiagnostics | KEEP | P2 | DebugViewModelStressTest | Counter behavior + genuine concurrency snapshot invariants; SharedPreferences cleared in setup |
| 11 | test/…/domain/dto/DtoContractTest.kt | 178 | 4 | 0 | PURE | AiArtifactRecord, ReceiptItemCategorizationSnapshot | KEEP | P3 | — | copy()/defaults contract; mostly compiler-checked but cheap; keep |
| 12 | test/…/domain/engine/DashboardFollowThroughEngineTest.kt | 449 | 20 | 0 | MOCKED | DashboardFollowThroughEngine | KEEP | P1 | NavigationTargetResolverTest, pipeline tests | Real outputs (priority/nav/JSON filters/7-day expiry); setMain without resetMain |
| 13 | test/…/domain/export/AccountingExportPolicyTest.kt | 72 | 3 | 0 | PURE | AccountingExportPolicy | STRENGTHEN | P1 | AccountingExportRepositoryTest, ExportOptionsViewModelTest | Test 1 has NO assertion (silent pass); tests 2-3 assert error messages |
| 14 | test/…/domain/export/CsvCellSanitizerNegativeAmountTest.kt | 100 | 13 | 0 | PURE | CsvCellSanitizer | KEEP | P0 | CsvEscapingTest | Formula-injection incl. "-2+3+cmd" DDE vectors, negative-amount non-corruption |
| 15 | test/…/domain/export/CsvEscapingTest.kt | 475 | 28 | 0 | PURE | XeroCSVExporter, QuickBooksIIFExporter, FreshBooksExporter | KEEP | P0 | #14, golden CsvExportImportRoundtrip | Escaping + delimiter-injection field-count asserts + IIF formula neutralization |
| 16 | test/…/domain/export/ExpenseExportMapperTest.kt | 68 | 2 | 0 | PURE | Expense.toExportTransaction | KEEP | P2 | AccountingExportRepositoryTest | effectiveAmount (shared 25%) + payment-method account labels |
| 17 | test/…/domain/forecasting/FinancialStressForecastEngineTest.kt | 721 | 21 | 0 | MOCKED | FinancialStressForecastEngine | KEEP | P0 | DashboardWidgetConsistency, ForecastSynthesisGolden, e2e | Legal-path assert: projectOccurrences not generateOccurrences; DBG-03/DBG-06; FRAGILE (14 mocks, reflection into private classifyRiskLevel) |
| 18 | test/…/domain/forecasting/ForecastInputAssemblerTest.kt | 1023 | 22 | 0 | MOCKED | ForecastInputAssembler | KEEP | P0 | #20, FinancialWeatherRepositoryTest | Fail-closed home currency; planned-expense conversion + excludedPlannedCount/isPartial; DBG-06 partial flag |
| 19 | test/…/domain/forecasting/HistoricalSpendingDistributionBoundaryTest.kt | 320 | 8 | 0 | MOCKED | HistoricalSpendingDistribution | KEEP | P1 | ForecastSynthesisGolden, e2e | DST-safe week/day math; fixed-total asserts (400.0) are F-07 drift candidates |
| 20 | test/…/domain/forecasting/MergedRecurringPatternsProviderTest.kt | 193 | 5 | 0 | MOCKED | MergedRecurringPatternsProvider | KEEP | P1 | #18 merge tests, CalculateFinancialForecastUseCaseTest | Near-dup of #18 merge semantics but via provider entry point — complementary |
| 21 | test/…/domain/forecasting/MonteCarloSpendingSimulatorGoldenTest.kt | 82 | 1 | 0 | GOLDEN | MonteCarloSpendingSimulator | KEEP | P0 | ComputeMoneyRadar, ForecastSynthesisGolden | Seed-42 p10-p90 golden; prime F-07 drift sensor — keep ±1.0 tolerance |
| 22 | test/…/domain/forecasting/MonteCarloSpendingSimulatorTest.kt | 162 | 5 | 0 | MOCKED | MonteCarloSpendingSimulator | KEEP | P1 | #21 | Degraded no-history path + leap-year daysRemaining |
| 23 | test/…/domain/groups/GroupBalanceCalculatorTest.kt | 144 | 5 | 0 | MOCKED | GroupBalanceCalculator | KEEP | P0 | GroupLifecycleScenarioTest | joinedAt participation, cancelled/foreign-currency settlement exclusion |
| 24 | test/…/domain/groups/SettlementCalculatorStressTest.kt | 88 | 3 | 0 | STRESS | SettlementCalculator | KEEP | P1 | #25, FinancialArithmeticPrecisionTest, e2e GroupSettlementPipeline | 15-member budget, volume invariant; fast |
| 25 | test/…/domain/groups/SettlementCalculatorTest.kt | 149 | 6 | 0 | PURE | SettlementCalculator | KEEP | P1 | #24, consistency/precision tests | Triangle-debt solver, min-amount parity, greedy-fallback marker |
| 26 | test/…/domain/groups/SharedExpenseBudgetOffsetEngineTest.kt | 362 | 8 | 0 | MOCKED | SharedExpenseBudgetOffsetEngine | KEEP | P1 | Budget repo suite, e2e | Linked-expense exclusion, malformed-split fallback, N+1 check, category filter |
| 27 | test/…/domain/groups/SharedExpenseManagerTest.kt | 467 | 14 | 0 | MOCKED | SharedExpenseManager, SplitCalculator | KEEP | P0 | verification/SharedExpenseTest, e2e | All split types + parity with SplitCalculator (B.02), joinedAt, NaN/Inf rejection |
| 28 | test/…/domain/groups/usecase/GroupUseCasesTest.kt | 372 | 12 | 0 | MOCKED | AddGroupExpenseUseCase, DeleteGroup(Member)UseCase | KEEP | P2 | SharedExpenseManagerTest | Delegation/verify-heavy but pins B.4 coordinator-migration contract + atomic path |
| 29 | test/…/domain/health/FinancialHealthCalculatorBoundaryTest.kt | 388 | 11 | 0 | PURE | FinancialHealthCalculator, TimePeriodUtils | STRENGTHEN | P1 | #30, #31 | Half of tests assert TimePeriodUtils directly or only "score in 0..100"; strong: empty-budget bonus=8 |
| 30 | test/…/domain/health/FinancialHealthCalculatorBudgetNormalizationTest.kt | 199 | 3 | 0 | PURE | FinancialHealthCalculator | KEEP | P1 | #29 | Daily/weekly/monthly target normalization incl. overlap de-dup; formula-derived expectations |
| 31 | test/…/domain/health/FinancialHealthCalculatorTransactionTypeTest.kt | 382 | 9 | 0 | PURE | FinancialHealthCalculator | KEEP | P1 | RecurringIncomeTrackerTest (semantics) | Differential with/without non-spend design; canonical isSpending protected |
| 32 | test/…/domain/health/FinancialHealthScoreV2Test.kt | 501 | 11 | 0 | MOCKED | FinancialHealthScoreV2 | KEEP | P0 | #33, #34, metrics/DashboardWidgetConsistency | Weighted-formula golden, history upsert, trend threshold, fake-clock timing diagnostic |
| 33 | test/…/domain/health/HealthScoreEdgeCaseTest.kt | 318 | 5 | 0 | MOCKED | FinancialHealthScoreV2 | KEEP | P0 | #32 | Zero-income/overspend floors, toInt truncation (B.04), deposit-only |
| 34 | test/…/domain/health/HealthScoreGoldenTest.kt | 133 | 2 | 0 | GOLDEN | FinancialHealthScoreV2 | STRENGTHEN | P1 | #32, #33 | Test name says "57 and stable" but asserts 55/IMPROVING — stale golden naming (drift symptom) |
| 35 | test/…/domain/income/RecurringIncomeTrackerTest.kt | 158 | 4 | 0 | MOCKED | RecurringIncomeTracker | KEEP | P1 | TransactionTypeTest | A.10 canonical isSpending: only PURCHASE counts; deposit-only recurring detection |

## Findings (noteworthy files only)

### test/java/com/yourname/expensetracker/domain/currency/MultiCurrencyTestFixture.kt
- Canonical EUR/USD fixture (expected 142.0 vs wrong raw 150.0) documented in KDoc.
- Grep shows zero consumers: only self-reference in `app/src/test` (prior 2026-05 audit claimed dashboard/budget/analytics usage — overturned; those suites now use `currency/CanonicalMultiCurrencyFixture.kt`).
- Dead fixture constants will silently rot as `Expense` schema grows (28-arg constructor already).
- Action: DELETE, or re-adopt by pointing new currency tests at it; do not leave unmaintained.

### test/java/com/yourname/expensetracker/domain/dashboard/P5AnalyticsFixesTest.kt
- Real value: `home_currency_is_cached_not_cold_flow` (verify exactly 1 resolveHomeCurrency across two calls — real caching behavior) and `builder_warns_on_size_mismatch` (real MoneyAggregateBuilder output, deprecation-suppressed API).
- Anti-patterns: `deposit_filter_excludes_not_mine_items` asserts on the `@Query` annotation STRING via reflection (SRCTEXT-style, breaks on SQL reformat); `deposit_entity_filter_excludes_shared_expenses` builds its own local predicate and asserts the test's own filter logic (tautology); `trend_builder_uses_zoned_date_time` asserts reflection signatures + java.time stdlib facts ("June has 30 days").
- Action: REWRITE — keep the 2 real tests, replace structural ones with behavior through `ComputeDashboardWidgetsUseCase.produceDashboardNormalizedInput`.

### test/java/com/yourname/expensetracker/domain/currency/ConversionSemanticsHardeningTest.kt
- Strongest currency file: CURR-70F-01..04 — PERIOD_MIDPOINT_ESTIMATE uses as-of rate and NEVER falls back to latest (MISSING_HISTORICAL_RATE), StaleRatePolicy reference semantics (NOW / TRANSACTION_DATE / RATE_VALID_DATE, incl. "missing reference must not pass as fresh"), weakest-leg provenance for EUR-bridge composites, storage adapter rejecting null/0 validDate.
- Legal-path alignment: exercises `ExchangeRateStoreAdapter` (write barrier mocked relaxed) and the 24h-staleness model named in CODEBASE_SEGMENTS Segment 16.
- Minor: FakeStore.getRateAsOf embeds lookup semantics that could diverge from `data/currency` implementation; the adapter tests mitigate this.

### test/java/com/yourname/expensetracker/domain/currency/CurrencyConversionTest.kt
- Unique coverage: storeRate/storeRates capture-slot checks, hasRate, getLastUpdateTime, cleanupOldRates delegation, formatAmount formatting, convertMultiple sums/failures, negative/zero amounts.
- Gaps: `supported currencies includes major currencies` constructs a LOCAL list and asserts on it (tautology, tests nothing); several stubs use `System.currentTimeMillis()` as rate data (harmless but nondeterministic); overlaps #1/#3 on basic convert paths.
- Action: STRENGTHEN — drop the tautology, keep the rest.

### test/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngineTest.kt + ForecastInputAssemblerTest.kt
- Both pin the read-path legal path: `projectOccurrences` used, `generateOccurrences` verified never called on read (matches LEGAL_PATHS "Recurring Plan Projection" + Segment 13 boundary); DatabaseReadBarrier checked; DBG-03 paused-rule occurrence leak excluded; DBG-06 barrier-blocked read flags `recurringObligationsPartial` / `RECURRING_OCCURRENCES_UNAVAILABLE` instead of silently dropping.
- Both are heavily mock-coupled (14 and 7 deps) — refactor of engine constructors will break them (known user pain), but assertions are on outputs, not interactions, so value survives.
- F-07 exposure: relative-invariant asserts (determinism, ordering, bounds) are drift-resistant; the fixed-value goldens are the sensors (below).

### test/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulatorGoldenTest.kt + HistoricalSpendingDistributionBoundaryTest.kt
- Golden (seed 42, p50=2072.41 etc., ±1.0) is the intended F-07 tripwire for `domain.forecasting.*` delta drift (TEST_FAILURE_LEDGER F-07: ~20 measured failures, "Expected x ± d, but was y"). If it fails, treat as P5-normalization/prod change, do not loosen tolerance.
- HistoricalSpendingDistributionBoundaryTest asserts exact weekly totals (400.0) — second F-07 sensor; its TimePeriodUtils boundary tests are solid.

### test/java/com/yourname/expensetracker/domain/health/HealthScoreGoldenTest.kt
- Test name says "overall score 57 and stable trend" but body asserts 55.0 and IMPROVING (`HealthScoreGoldenTest.kt:68-79`); second test also 55. Golden was evidently updated after a formula change without renaming — an F-07-style drift symptom in the health area; rename/refresh expected values with an explicit golden-regeneration note.
- Both HealthScore files stub `healthScoreHistoryDao.getMostRecent()` while production/trend path uses `getMostRecentBefore` (both exist in the DAO) — harmless relaxed stub, tidy up.

### test/java/com/yourname/expensetracker/domain/export/CsvCellSanitizerNegativeAmountTest.kt + CsvEscapingTest.kt
- Security P0: cover `=`/`+`/`@`/`-` formula prefixes, DDE payloads with digit/letter second char (NEW-P12-003), tab/newline stripping, IIF variants, and delimiter-injection field-count assertions (10 CSV fields / 7 IIF tabs). Matches LEGAL_PATHS "CsvCellSanitizer neutralizes formula injection for every CSV/IIF cell".
- AccountingExportPolicyTest first test (`validateAccountingDataset accepts...`) has no assertion — add explicit `assertDoesNotThrow`-style guard or drop; the two fail-fast tests are good.

### Cross-batch: duplicate simple class name CurrencyNormalizationBehavioralTest
- `domain/currency/CurrencyNormalizationBehavioralTest` (this batch, engine/aggregate level: isPartial, metadata counters, rateBasis) vs `domain/core/money/CurrencyNormalizationBehavioralTest` (batch-17 plan, converter level: latest-vs-historical, validDate semantics). Complementary, not DUP, but identical simple names in two packages invite wrong-file edits and confusing Gradle `--tests "*CurrencyNormalizationBehavioralTest*"` runs (matches both). Recommend renaming one (batch-17 owner).

## Area gaps (what is NOT tested in this area)

- No test here exercises CurrencyConverter's 24h staleness against the REAL `ExchangeRateStoreAdapter` + Room DAO (adapter validated only for insert boundary; staleness lookup semantics rest on fakes).
- `SupportedCurrency` symbol/formatting table only smoke-tested (and one tautology); no exhaustive fromCode/symbol parity test.
- AccountingExportPolicy: `validateGlobalDataset()` (named in Segment 18) has no direct test in this file; only requireSingleCurrency/requirePurchaseTransactions.
- MonteCarloSpendingSimulator: no test for RNG-consumption stability across JVM versions beyond ±1.0 tolerance; no property test that percentiles are monotonic with randomized inputs (only degraded path).
- SettlementCalculator: no adversarial asymmetric/irreducible cycle beyond 15 members; no test of solver budget exhaustion actually triggering `usedGreedyFallback` (only summary rendering of the flag).
- FinancialHealthScoreV2: currency conversion inside score computation is mocked pass-through everywhere — no test that foreign-currency expenses change the score via real normalizer (deferred to integration batch, but absent here).
- Debug: NotificationSeeder (Segment 29) has no test in this batch.
- HomeCurrencyResolution: no test for invalid non-blank currency code resolution path (only valid/blank/exception).

## Rollup

- Verdicts: KEEP 29 · STRENGTHEN 4 · MERGE 0 · REWRITE 1 · DELETE 1 · NIGHTLY 0 · UNKNOWN 0 (35 files)
- P0 count: 12 (ConversionSemanticsHardening, CurrencyConverterGolden, CurrencyNormalizationBehavioral, CsvCellSanitizerNegativeAmount, CsvEscaping, FinancialStressForecastEngine, ForecastInputAssembler, MonteCarloSpendingSimulatorGolden, GroupBalanceCalculator, SharedExpenseManager, FinancialHealthScoreV2, HealthScoreEdgeCase)
- DUP pairs: 1 near-DUP (ForecastInputAssemblerTest merge tests vs MergedRecurringPatternsProviderTest — complementary entry points, keep both); 1 cross-batch name collision (domain/currency vs domain/core/money CurrencyNormalizationBehavioralTest — batch-17); CurrencyConversionTest overlaps #1/#3 but retains unique coverage.
- P4 (negative value): none. (P5AnalyticsFixesTest closest at REWRITE-P1 due to tautologies; MultiCurrencyTestFixture DELETE-P2 as orphaned fixture.)
- FRAGILE count: 3 — FinancialStressForecastEngineTest (reflection into private method + 14 mocks), P5AnalyticsFixesTest (annotation-string reflection), DashboardFollowThroughEngineTest (Dispatchers.setMain without resetMain).
- Prior-audit overturns: MultiCurrencyTestFixture MOVE→DELETE (orphaned); ServiceDiagnosticsTest MOVE_TO_NIGHTLY→KEEP (fast, bounded); CsvEscapingTest P1→P0 (security).
- F-07 citations: MonteCarloSpendingSimulatorGoldenTest and HistoricalSpendingDistributionBoundaryTest are the batch's drift sensors; HealthScoreGoldenTest shows the same stale-golden symptom (name 57 vs assert 55).
