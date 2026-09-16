# Batch 02 — fixtures

Scope: shared test fixtures/harnesses — `testfixtures/` (scenario seeding, in-memory DB, golden-file verifier), `metrics/` golden analytics dataset + engine-consistency tests, `domain/util` fake clocks · Segments 32 (time utilities), 8/10 (analytics/dashboard consistency), 16 (money), cross-cutting test infra · Files: 13 · LOC: 2108

Reviewer notes: Static-only review. Consumer counts measured via grep over `app/src/test` + `app/src/androidTest` (files importing/instantiating the fixture). No file in this batch appears in TEST_FAILURE_LEDGER families F-01…F-21. Stale 2026-05 audit (generated/test-batches/batch-004.md) kept all 13; two verdicts overturned (see findings): GoldenAnalyticsDatasetTest is tautological and GoldenAnalyticsDataset is a dead fixture ecosystem. Four overlapping "golden/fixture" ecosystems exist: (A) `testfixtures/scenario` + `AppDatabaseTestFactory` (alive, 9–23 consumers), (B) `testfixtures/golden/GoldenScenarioVerifier` + 21 checked-in JSON goldens (alive), (C) `metrics/GoldenAnalyticsDataset` (dead, self-consumed), (D) `domain/analytics/fixtures/GoldenDataSets`+`ExpectedResults` via `AnalyticsEngineTestBase` (alive, 42 inheriting tests) plus dead `currency/CanonicalMultiCurrencyFixture` (0 external consumers — other batch). Canonical seeding path should be (A) for DB state + (D) for analytics-engine expectations + (B) for golden JSON; (C) should be folded into (D).

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 8 | 3 | 1 | 1 | 0 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/java/…/domain/util/FakeMonotonicTimeProvider.kt | 28 | 0 | 0 | FIXTURE | MonotonicTimeProvider | KEEP | P2 | none | 2 consumers (SystemMonotonicTimeProviderTest, AssistantViewModelTest); only monotonic fake |
| 2 | test/java/…/domain/util/FakeTimeProvider.kt | 67 | 0 | 0 | FIXTURE | TimeProvider | KEEP | P1 | none | 67 consumer files; canonical wall-clock fake; forDate is default-TZ |
| 3 | test/java/…/metrics/DashboardWidgetConsistencyTest.kt | 333 | 4 | 0 | MOCKED | ComputeDashboardWidgetsUseCase, SynthesisEngine | STRENGTHEN | P1 | complementary: DashboardProjectionSafetyTest, ComputeDashboardWidgetsUseCaseDaysRemainingBoundaryTest | FRAGILE; 14 relaxed mocks; runway test asserts only `>=0` |
| 4 | test/java/…/metrics/EffectiveAmountConsistencyTest.kt | 242 | 10 | 0 | PURE | SpendingPaceCalculator, MonthlyComparisonCalculator, DayOfWeekAnalyzer, Expense.effectiveAmount | KEEP | P0 | complementary: integration/EffectiveAmountPipelineIntegrationTest, SpendingPaceCalculatorDeepTest | Real money math: isNotMine/share precedence across 3 engines |
| 5 | test/java/…/metrics/GoldenAnalyticsDataset.kt | 244 | 0 | 0 | FIXTURE | (fixture: GoldenScenario, expectations) | MERGE | P3 | DUP: domain/analytics/fixtures/GoldenDataSets.kt | Dead fixture — only consumer is its own test; fold into fixtures/GoldenDataSets |
| 6 | test/java/…/metrics/GoldenAnalyticsDatasetTest.kt | 175 | 8 | 0 | PURE | (none — test-local reimplementations) | REWRITE | P2 | DUP: verification/GoldenMasterVerificationTest | Tautology: metrics re-implemented in test, no production engine exercised |
| 7 | test/java/…/metrics/TimePeriodAlignmentTest.kt | 313 | 21 | 0 | PURE | TimePeriodUtils, AdvancedAnalyticsEngine, BudgetCalculator | KEEP | P1 | complementary: consistency/TimePeriodAnalyticsAlignmentTest (weaker) | Strong half-open/contiguity/leap/DST contract matrix |
| 8 | test/java/…/testfixtures/TestFixtures.kt | 104 | 0 | 0 | FIXTURE | (extensions: dateMs, eur/usd/gbp, money) | STRENGTHEN | P2 | none | dateMs 9+ importers; asReadableDate & STANDARD_CATEGORIES 0 consumers (dead) |
| 9 | test/java/…/testfixtures/database/AppDatabaseTestFactory.kt | 18 | 0 | 0 | FIXTURE | AppDatabase (in-memory) | KEEP | P1 | none | 23 consumer scenario files; applies migrations + FRESH_INSTALL_CALLBACK |
| 10 | test/java/…/testfixtures/golden/GoldenScenarioVerifier.kt | 217 | 0 | 0 | FIXTURE | (golden-file comparer) | KEEP | P1 | none | 21 consumer tests; 21 goldens checked in; CI-safe update-mode guard |
| 11 | test/java/…/testfixtures/scenario/ScenarioAssertions.kt | 92 | 0 | 0 | FIXTURE | (AppDatabase assertions) | KEEP | P2 | none | 4 consumer files; assertDashboardTotal sums raw amount not effectiveAmount |
| 12 | test/java/…/testfixtures/scenario/ScenarioSeed.kt | 108 | 0 | 0 | FIXTURE | (seed models) | KEEP | P2 | none | Used with seeder in ~10 scenario tests; NotificationInput dead stub |
| 13 | test/java/…/testfixtures/scenario/ScenarioSeeder.kt | 167 | 0 | 0 | FIXTURE | (seeder for AppDatabase) | STRENGTHEN | P1 | none | 9-10 consumer files; `feedInputs()` dead stub (0 external callers) |

## Findings (noteworthy files only)

### app/src/test/java/com/yourname/expensetracker/metrics/GoldenAnalyticsDatasetTest.kt
- KDoc (lines 12-24) promises step 2 "feed scenario transactions to engine under test through a fake repository" — that step does not exist; the 8 tests instead compare `GoldenAnalyticsDataset` constants against `purchaseMetrics()`/`cashFlowMetrics()`/`categoryBreakdown()` helpers defined inside the test itself (lines 122-175).
- This validates the test's own arithmetic, not production behavior — exactly the tautology MASTER_TESTING_STRATEGY forbids. Only production touchpoint is `Expense.effectiveAmount` (line 50), covered elsewhere.
- Overturns stale 2026-05 verdict (batch-004.md #47 "KEEP P1_HIGH scenario"). Current source confirms anti-pattern.
- Action: REWRITE — drive scenarios through `TotalsAggregationEngine`/`InsightsEngine`/`MultiCurrencyRepository` (or extend `golden/AnalyticsDashboardBudgetParityGoldenTest`), then delete local helpers. Dataset expectations S1–S7 (half-open bounds, share math, type filtering) are good content worth preserving.

### app/src/test/java/com/yourname/expensetracker/metrics/GoldenAnalyticsDataset.kt
- Consumed only by GoldenAnalyticsDatasetTest (grep: no other references). Dead as a shared fixture.
- Duplicates the role of the alive ecosystem `domain/analytics/fixtures/GoldenDataSets.kt` + `ExpectedResults.kt`, which feed `AnalyticsEngineTestBase` (42 test files inherit it) and `verification/GoldenMasterVerificationTest.kt`. Also parallels dead `currency/CanonicalMultiCurrencyFixture.kt` (0 external consumers) — a recurring "canonical fixture nobody adopts" pattern.
- Action: MERGE into `domain/analytics/fixtures/GoldenDataSets.kt` (port S1–S7 expectations there), or delete after #6 is rewritten.

### app/src/test/java/com/yourname/expensetracker/metrics/DashboardWidgetConsistencyTest.kt
- FRAGILE: setup mocks 14 constructor deps of `ComputeDashboardWidgetsUseCase` with relaxed MockK (lines 77-145); any constructor/dependency refactor breaks this file (the user-reported "refactors break ~100 tests" pain).
- Assertion quality is mixed. Real: PeriodSummary monthSpent equals share-adjusted sum (149-164); SafeToSpend unavailable when budget not normalized (214-251); totalSpent from normalized input (254-260, documents CURR-587-05). Weak: runway test's only claim is `committedExpenses + likelyExpenses >= 0` (line 210) — a tautology that cannot fail for meaningful regressions despite its "from SynthesisEngine" name.
- Action: STRENGTHEN — replace the `>=0` claim with exact expected committed/likely values from the stubbed SynthesisEngine inputs; consider shared builder to reduce mock coupling.

### app/src/test/java/com/yourname/expensetracker/metrics/EffectiveAmountConsistencyTest.kt
- High-value money invariant: shared-expense/not-mine share math (`isNotMine` → 0, `myShareAmount` > `mySharePercentage`, full amount otherwise) proven identical across SpendingPaceCalculator, MonthlyComparisonCalculator, DayOfWeekAnalyzer — cross-engine drift protection the deep per-engine tests do not provide.
- P0: protects the effectiveAmount money invariant for shared expenses (Segments 8/9/16).
- Minor: `fixedTime` uses 0-based Calendar months with a "June 15" comment (line 40) — correct but readability-trap; `createdAt = System.currentTimeMillis()` (line 236) is harmless here.

### app/src/test/java/com/yourname/expensetracker/testfixtures/scenario/ScenarioSeeder.kt (+ ScenarioSeed.kt)
- Canonical DB seeding path for Room scenario tests (9-10 consumer files, all in `scenarios/`), paired with `AppDatabaseTestFactory` (23 consumers). Direct DAO inserts are acceptable here: fixtures build background state; mutations under test still go through coordinators (legal-path respected in consumers).
- `feedInputs()` (lines 138-140) is a stub delegating to `seedState` with zero external callers, and `NotificationInput` (ScenarioSeed.kt:83-89) has zero consumers — both mislead readers into thinking a pipeline-driven seeding path exists. Action: remove or implement via `TransactionLifecycleCoordinator`.
- `parseTransactionType`/`parseBudgetPeriod` silently default unknown strings (UNKNOWN/MONTHLY) — can mask typos in seed data; consider failing fast.

### app/src/test/java/com/yourname/expensetracker/testfixtures/golden/GoldenScenarioVerifier.kt
- Solid harness: fail-closed on missing golden (line 88-97), update-mode forbidden under CI (`CI=true` errors, lines 32-38), numeric tolerance + ignored fields + sorted arrays. 21 consumer tests; 21 golden JSONs exist under `app/src/test/resources/golden/`.
- Minor: `goldenResourceDir()` resolves against `user.dir` (lines 40-52) — works under Gradle's app-module working dir but is environment-sensitive for local runs.

### app/src/test/java/com/yourname/expensetracker/domain/util/FakeTimeProvider.kt
- Canonical wall-clock fake (67 consumer files); KEEP. Area note: ~20 other test files still hand-roll inline `object : TimeProvider {...}` or private `FakeTime/TestTime/FixedTime` classes (e.g. domain/core/money/CurrencyNormalizationBehavioralTest.kt:214, integration/CurrencyConversionIntegrationTest.kt:173, data/privacy/DataRetentionWorkerTest.kt:68) — consolidation opportunity, not a defect of this file.
- `forDate()` builds via default-TZ `Calendar`; consumers doing UTC boundary math should prefer `TestFixtures.dateMs` (UTC) to avoid TZ-dependent expectations.

## Area gaps (what is NOT tested in this area)

- No test feeds the GoldenAnalyticsDataset matrix through any production aggregation engine — the intended golden check for TotalsAggregationEngine/InsightsEngine inputs is unimplemented (see GoldenAnalyticsDatasetTest).
- `ScenarioAssertions.assertDashboardTotal` sums raw `Expense.amount`, so no scenario-level assertion exists for effectiveAmount-based dashboard totals (documented caveat at ScenarioAssertions.kt:62-65).
- BudgetCalculator↔AdvancedAnalyticsEngine alignment only checked for MONTHLY/weekly starts; week/quarter/year cross-engine alignment untested (TimePeriodAlignmentTest covers year/quarter contiguity only within TimePeriodUtils).
- Fake-clock fragmentation: ~20 inline TimeProvider fakes duplicate `FakeTimeProvider`; also 3 local `dateMs` re-implementations (AdvancedAnalyticsEngineDeepTest.kt:302, InsightsEngineDeepTest.kt:179, TotalsAggregationEngineDeepTest.kt:203) duplicate `TestFixtures.dateMs`.
- Dead fixture symbols to trim: `TestFixtures.asReadableDate`, `STANDARD_CATEGORIES`, `ScenarioSeed.NotificationInput`, `ScenarioSeeder.feedInputs`.

## Rollup

- Verdicts: KEEP 8 · STRENGTHEN 3 · MERGE 1 · REWRITE 1 · DELETE 0 · NIGHTLY 0 · UNKNOWN 0
- P0 count: 1 (EffectiveAmountConsistencyTest — shared-expense money invariant)
- DUP pairs: GoldenAnalyticsDataset(+Test) vs domain/analytics/fixtures/GoldenDataSets.kt + AnalyticsEngineTestBase/GoldenMasterVerificationTest (survivor: fixtures/GoldenDataSets). Pattern note (not full DUP): currency/CanonicalMultiCurrencyFixture.kt is likewise a zero-consumer "canonical" fixture (other batch).
- Complementary (not DUP): TimePeriodAlignmentTest vs consistency/TimePeriodAnalyticsAlignmentTest; EffectiveAmountConsistencyTest vs integration/EffectiveAmountPipelineIntegrationTest.
- FRAGILE count: 1 (DashboardWidgetConsistencyTest — 14 relaxed-mock constructor coupling).
- Overturned stale verdicts: GoldenAnalyticsDatasetTest KEEP→REWRITE; GoldenAnalyticsDataset KEEP→MERGE; TestFixtures KEEP→STRENGTHEN (dead symbols).
