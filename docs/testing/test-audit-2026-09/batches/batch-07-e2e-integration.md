# Batch 07 — e2e-integration

Scope: `e2e/` (Segments 2, 3, 4, 7, 8, 10, 16, 18, 24, 38) and `integration/` (Segments 1, 6, 10, 16) test packages · Files: 28 (27 test classes + 1 fixture) · LOC: 5,159

Reviewer notes (global):
- **Partial overturn of the 2026-05 audit.** The five `GoldenTestBase`-derived e2e files (BackupRestoreIntegrity, BudgetThresholdAlert, NotificationExpenseDashboard, ReceiptMatching, RecurringPaymentMatch) DO exercise real pipelines: Robolectric in-memory Room (`golden/GoldenTestBase.kt:57-66`), real `MultiCurrencyRepository`, real `ReceiptTransactionMatcher`+`ReceiptLinkService`, real `RecurringLifecycleCoordinator`, real `AppParserRegistry`. Each also has a committed golden fixture in `app/src/test/resources/golden/e2e_*.json`. These are mis-packaged golden tests, not fake tests.
- **Deprecated-DAO finding CONFIRMED still true** for the mock-DAO half: `FlowPipelineTestHarness`, `AnalyticsPipelineTest`, and `EffectiveAmountPipelineIntegrationTest` call/stub `getTotalForPeriod`, `getCategoryTotalsForPeriod`, `getDailyTotalsWithDatesForPeriod`, `getCategoryBreakdown`, `getAverageDailySpend` — all `@Deprecated(level = ERROR)` ("raw SUM across mixed currencies, use MultiCurrencyRepository", `ExpenseDao.kt:1475-2165`) under `@Suppress("DEPRECATION_ERROR")`. These tests simulate SQL in Kotlin and verify their own simulation.
- **No coordinator coverage in this batch.** `TransactionLifecycleCoordinator` is `mockk(relaxed=true)` in every mock-DAO file, and NotificationExpenseDashboardE2ETest inserts via `expenseDao().insertAtomic` directly ("simulating what coordinator does", line 122) — a lifecycle bypass inside an "e2e" test.
- TEST_FAILURE_LEDGER: no e2e/integration family applies (ledger line 20: packages NOT yet run). Adjacency only: `BudgetAlertPipelineTest` stubs the exact `getBudgetStatuses()` seam flagged in F-03.
- Consolidation signal: notification→expense→dashboard now exists in FOUR places (e2e E2ETest, e2e PipelineTest, golden NotificationReviewDashboardBudgetGoldenTest, scenarios NotificationPipelineScenarioTest); recurring-payment-match in three; receipt-match in three; dashboard-total-parity in four. Recommended target per MASTER_TESTING_STRATEGY: golden = fixture-driven DB-backed cross-pipeline; fold everything below accordingly.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 0 | 3 | 18 | 3 | 4 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/java/…/e2e/AnalyticsPipelineTest.kt | 301 | 5 | 0 | MOCKED | InsightsEngine (S8) | STRENGTHEN | P1 | AnalyticsEngineTestBase users; domain/analytics | Golden-march fixture, real numbers; ERROR-deprecated DAO stubs |
| 2 | test/java/…/e2e/BackupRestoreIntegrityE2ETest.kt | 184 | 1 | 0 | ROOM | MultiCurrencyRepository, DatabaseWriteBarrier (S18/S16) | MERGE | P2 | golden/BackupRestoreRoundtripGoldenTest; scenarios/BackupRestore* | "restore" is mock-flip; before==after tautology |
| 3 | test/java/…/e2e/BudgetAlertPipelineTest.kt | 162 | 4 | 0 | MOCKED | BudgetMonitor (S2/S11) | MERGE | P2 | domain/budget/BudgetMonitorTest(+Stress) | verify-only dispatch; F-03 seam adjacency |
| 4 | test/java/…/e2e/BudgetThresholdAlertE2ETest.kt | 151 | 1 | 0 | ROOM | BudgetCalculator, MultiCurrencyRepository (S2/S16) | MERGE | P2 | golden/AnalyticsDashboardBudgetParityGoldenTest | Real DB+pipeline; severity re-implemented in test |
| 5 | test/java/…/e2e/CategoryBreakdownFlowTest.kt | 65 | 1 | 0 | MOCKED | ExpenseRepository→AnalyticsViewModel (S8/S10) | MERGE | P2 | golden/HomeDashboardFinancialInvariantTest; metrics/DashboardWidgetConsistencyTest | % sum=100 invariant good; mock DAO |
| 6 | test/java/…/e2e/DailyAverageFlowTest.kt | 54 | 1 | 0 | MOCKED | AdvancedAnalyticsEngine (S8) | MERGE | P3 | golden analytics parity family | periodDays semantics; fold into golden |
| 7 | test/java/…/e2e/DateBoundaryFlowTest.kt | 49 | 1 | 0 | MOCKED | ExpenseRepository, TimePeriodUtils (S32/S10) | MERGE | P2 | TimePeriodUtils tests; golden parity | Half-open interval; Kotlin-simulated SQL |
| 8 | test/java/…/e2e/EmptyDataFlowTest.kt | 46 | 1 | 0 | MOCKED | AnalyticsRepository, AnalyticsViewModel (S8/S10) | MERGE | P3 | golden parity family | Empty-state defaults; fold into golden |
| 9 | test/java/…/e2e/FlowPipelineTestHarness.kt | 258 | 0 | 0 | FIXTURE | builds mock DAO pipeline (S8/S10) | DELETE | P3 | golden/GoldenTestBase (superseder) | ERROR-deprecated stubs; reimplements SQL in Kotlin |
| 10 | test/java/…/e2e/GroupSettlementPipelineTest.kt | 198 | 4 | 0 | MOCKED | SplitCalculator, SettlementCalculator, SharedExpenseManager (S24) | MERGE | P2 | domain/groups/SettlementCalculatorTest(+Stress); golden/GroupSettlementBudgetOffset | Unit tests mislabeled pipeline; good zero-sum asserts |
| 11 | test/java/…/e2e/MonthlyTotalFlowTest.kt | 53 | 1 | 0 | MOCKED | ExpenseRepository→ViewModel (S10) | MERGE | P2 | golden/HomeDashboardFinancialInvariantTest | Same totals-parity invariant, DB-backed there |
| 12 | test/java/…/e2e/NotificationExpenseDashboardE2ETest.kt | 200 | 1 | 0 | ROOM | AppParserRegistry, MultiCurrencyRepository (S3/S10/S16) | STRENGTHEN | P1 | golden/NotificationReviewDashboardBudgetGoldenTest | Real parsers+DB; insertAtomic bypasses coordinator |
| 13 | test/java/…/e2e/NotificationExpenseDashboardPipelineTest.kt | 636 | 3 | 0 | MOCKED | ComputeDashboardWidgetsUseCase, HybridExpenseClassifier (S10/S6/S3) | MERGE | P2 | file #12; golden/NotificationReviewDashboard; scenarios/NotificationPipelineScenarioTest | FRAGILE 330-line manual graph; unique classifier case |
| 14 | test/java/…/e2e/ReceiptMatchingE2ETest.kt | 193 | 1 | 0 | ROOM | ReceiptTransactionMatcher, ReceiptLinkService (S38/S4) | MERGE | P1 | golden/ReceiptMatchingNoDoubleCountGoldenTest | Real matcher/link/DB; same flow as golden |
| 15 | test/java/…/e2e/ReceiptProcessingPipelineTest.kt | 180 | 4 | 0 | MOCKED | ReceiptParser, CategorizationEngine (S4/S6) | REWRITE | P1 | domain ReceiptParser/CategorizationEngine tests | No ReceiptLifecycleCoordinator; OCR mocked; parse asserts real |
| 16 | test/java/…/e2e/RecurringPaymentMatchE2ETest.kt | 184 | 1 | 0 | ROOM | RecurringLifecycleCoordinator (S7) | MERGE | P0 | golden/RecurringBillPaymentMatchTest; golden/RecurringPlannedActualNoDoubleCountGoldenTest | Real coordinator+DB; coverage must survive merge |
| 17 | test/java/…/e2e/SharedExpenseFlowTest.kt | 77 | 1 | 0 | MOCKED | ExpenseRepository/InsightsEngine shared semantics (S9/S24) | MERGE | P2 | scenarios/SharedExpenseGroupScenarioTest | effectiveAmount+notMine across layers |
| 18 | test/java/…/integration/BudgetCashflowCurrencyBehavioralTest.kt | 146 | 6 | 0 | PURE | CurrencyConverter, MoneyNormalizationEngine (S16) | MERGE | P1 | domain/currency/ConversionSemanticsHardeningTest | 2 tautologies (enum checks); FORECAST_DATE case unique |
| 19 | test/java/…/integration/CategorizationPipelineIntegrationTest.kt | 282 | 19 | 0 | PURE | MerchantCleaner, MerchantKeyGenerator, AmountUtils (S6/S32) | DELETE | P3 | domain/util unit+stress tests (all of them) | isNotEmpty asserts; nanoTime flake; mislabeled |
| 20 | test/java/…/integration/Curr587BehavioralTest.kt | 173 | 14 | 0 | PURE | result data classes, StaleRatePolicy (S16) | DELETE | P4 | domain/currency family | Tautology-heavy; salvage 3 forBasis mapping asserts |
| 21 | test/java/…/integration/CurrencyConversionIntegrationTest.kt | 210 | 10 | 0 | PURE | CurrencyConverter, MoneyNormalizationEngine (S16) | MERGE | P1 | domain/currency/ConversionSemanticsHardeningTest | Strong sentinel rates; keep aggregate-provenance cases |
| 22 | test/java/…/integration/CurrencyNormalizationPost9a6Test.kt | 116 | 5 | 0 | PURE | MoneyNormalizationEngine, StaleRatePolicy (S16) | MERGE | P1 | ConversionSemanticsHardeningTest stalePolicy tests | 3 real stale-policy tests; 2 tautologies |
| 23 | test/java/…/integration/DashboardCurrencyIntegrationTest.kt | 319 | 7 | 0 | MOCKED | ComputeDashboardWidgetsUseCase (S10/S16) | REWRITE | P2 | metrics/DashboardWidgetConsistencyTest | Monte-carlo test has EMPTY assert block; keep widget-unavailable |
| 24 | test/java/…/integration/EffectiveAmountPipelineIntegrationTest.kt | 183 | 1 | 0 | MOCKED | TotalsAggregationEngine, AdvancedAnalyticsEngine (S10/S8) | MERGE | P2 | golden parity family; file #11 | Stubbed ERROR-deprecated DAO = tests own stub |
| 25 | test/java/…/integration/ExpenseCreationPipelineIntegrationTest.kt | 228 | 16 | 0 | PURE | MerchantCleaner, AmountUtils (S32) | DELETE | P3 | AmountUtilsTest, MerchantCleanerStressTest | No coordinator despite name; prior audit confirmed |
| 26 | test/java/…/integration/ForecastRunwayIntegrationTest.kt | 193 | 4 | 0 | MOCKED | ForecastInputAssembler(mocked!), SynthesisEngine (S1) | REWRITE | P2 | domain/forecasting/ForecastInputAssemblerTest | Tests 1&4 stub+verify their own mock; use real assembler |
| 27 | test/java/…/integration/MultiCurrencyAnalyticsTest.kt | 184 | 4 | 0 | MOCKED | MultiCurrencyRepository (S16) | STRENGTHEN | P1 | currency/CanonicalMultiCurrencyFixture users; scenarios mixed-currency | Real repo semantics, >2000 regression, no-uncapped-scan guard |
| 28 | test/java/…/integration/MultiCurrencyRepositoryBehavioralTest.kt | 134 | 4 | 0 | PURE | MoneyNormalizationEngine (S16) — never the repository | MERGE | P1 | domain/currency/CurrencyNormalizationBehavioralTest | Name lies; engine-level duplicates |

## Findings (noteworthy files only)

### test/java/com/yourname/expensetracker/e2e/FlowPipelineTestHarness.kt
- Fixture powering files #5-8, #11, #17. Mocks `ExpenseDao` and re-implements range/PURCHASE/notMine/effectiveAmount filtering in Kotlin (`stubDao`, lines 197-258), stubbing `getTotalForPeriod`/`getCategoryTotalsForPeriod` which are `DeprecationLevel.ERROR` ("raw SUM across mixed currencies", ExpenseDao.kt:1475,1533). So the "cross-layer flow" tests verify the harness's own simulation, never SQL.
- Superseded by `golden/GoldenTestBase` (real in-memory Room, fixed clock, real write barrier). Recommended action: fold the six flow tests into one DB-backed `golden` analytics-flow test on GoldenTestBase and delete the harness.

### test/java/com/yourname/expensetracker/e2e/RecurringPaymentMatchE2ETest.kt
- The only P0-relevant file here: wires real `RecurringLifecycleCoordinator` + materializer + `RoomDomainTransactionRunner` against real Room, generates occurrences, links a payment (PAID transition), and asserts dashboard counts it once via real `MultiCurrencyRepository` (lines 114-174). Golden fixture committed (`e2e_recurring_payment_match.json`).
- But `golden/RecurringBillPaymentMatchTest` (atomic claim, fulfilment, reminder suppression) + `golden/RecurringPlannedActualNoDoubleCountGoldenTest` already cover the same flow DB-backed. MERGE content into those survivors; do not lose the generate→link→dashboard-total-once chain. P0 because recurring lifecycle + double-count is money math.

### test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt
- 636 LOC, ~330 lines of manual DI graph (lines 137-468): 15+ MockK mocks, hand-rolled anonymous `GroupsRepository`/`SharedExpenseDataPort`. `TransactionLifecycleCoordinator` and `MultiCurrencyRepository` mocked; dashboard "total" asserted from `InsightsEngine` over in-memory lists, not the DB. Extreme compile fragility (the stated user pain: refactors break ~100 tests).
- Unique value worth salvaging: classifier RULE_MATCH case (`classify` → categoryId 2, lines 483-491) → move to CategorizationEngine/HybridExpenseClassifier unit tests; parse-fail/empty-notification baselines → covered by golden/NotificationReviewDashboardBudgetGoldenTest after strengthening file #12.

### test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardE2ETest.kt
- Real `AppParserRegistry` (NBG Greek text + Revolut), real dedupe via `insertAtomic` re-insert, real dashboard total. Strongest e2e in the package. Gap: expense creation bypasses `TransactionLifecycleCoordinator` (`insertAtomic`, lines 133-136 — direct DAO write violates the Segment 9 legal path even in tests); duplicate-detection asserted on legacy `insertAtomic` return semantics rather than `CreateExpenseResult.DuplicateSkipped`. STRENGTHEN then fold into the golden notification scenario.

### test/java/com/yourname/expensetracker/integration/Curr587BehavioralTest.kt
- Mostly tautologies: constructs `Unavailable` then asserts `is Unavailable`, `assertFalse(result is Available)`, "no XXX currency" checks that the compiler already guarantees. One test's body is a comment-only `if` (monte-carlo sibling pattern also in #23). Negative value (false confidence, suite time) → DELETE; salvage the three `StaleRatePolicy.forBasis` mapping asserts into the domain/currency family. P4.

### test/java/com/yourname/expensetracker/integration/CategorizationPipelineIntegrationTest.kt + ExpenseCreationPipelineIntegrationTest.kt
- 2026-05 verdicts re-verified and upheld: zero DB/DAO/coordinator; assertions are `isNotEmpty`/`assertNotNull` on utilities directly unit- and stress-tested in `domain/util` (AmountUtilsTest, AmountUtilsStressTest, MerchantCleanerStressTest, MerchantKeyGeneratorTest, StringDistanceUtilsStressTest). Both include `System.nanoTime() < 1s` "performance" tests (wall-clock flake risk). Neither exercises `TransactionLifecycleCoordinator` despite file #25's name.

### test/java/com/yourname/expensetracker/integration/CurrencyConversionIntegrationTest.kt / CurrencyNormalizationPost9a6Test.kt / MultiCurrencyRepositoryBehavioralTest.kt / BudgetCashflowCurrencyBehavioralTest.kt
- Good sentinel-rate money-math design, but the same invariants (as-of vs latest, PERIOD_MIDPOINT, stale 7-day LatestDefault, composite VIA_BASE_CURRENCY, PARTIAL/UNAVAILABLE) are already DB of record in `domain/currency/ConversionSemanticsHardeningTest` and `CurrencyNormalizationBehavioralTest` (same private fake `ExchangeRateStore` technique). Note #28 never touches `MultiCurrencyRepository` — it tests `MoneyNormalizationEngine` directly (file comment admits it). Consolidate into `domain/currency/`; keep unique cases: FORECAST_DATE basis (#18), aggregate provenance fields (#21), >2000-row aggregate-path + no-uncapped-scan guard (#27 — strongest file in integration/, STRENGTHEN with a ROOM variant).

### test/java/com/yourname/expensetracker/integration/ForecastRunwayIntegrationTest.kt
- Tests 1 and 4 mock `ForecastInputAssembler` and stub it with a copy of its own mapping, then `coVerify` the mock was called (lines 73-92, 172-192) — they verify the stub, a master-strategy anti-pattern. Tests 2-3 (real `SynthesisEngine`) are the only value; move to SynthesisEngine tests and rewrite the pair against the real assembler.

## Area gaps (what is NOT tested in this area)

- No test in either package routes expense creation through `TransactionLifecycleCoordinator` (mocked everywhere; direct `insertAtomic` in #12). The MASTER_TESTING_STRATEGY P1 golden "notification → TransactionLifecycleCoordinator → TransactionEvent → budget → dashboard" chain is not covered here (check batch 01 golden coverage).
- No DB-backed analytics flow test: category-percentage-sum-100, daily-average period semantics, half-open boundaries are all currently asserted against Kotlin-simulated SQL (harness), so real DAO SQL semantics for these are unverified — while the underlying DAO methods are ERROR-deprecated pending removal to MultiCurrencyRepository. A DB-backed parity test would also de-risk that removal.
- No e2e from `ReceiptLifecycleCoordinator.processReceiptInput` (strategy golden #2) — only matcher+link fragments (#14/#15).
- Backup/restore "e2e" (#2) never performs an actual backup/restore; only barrier flips — real roundtrip coverage must come from golden/scenarios files (batches 01/08).
- Budget alert chain untested beyond mocked dispatch: no test that BudgetMonitor alert fires from DB-seeded spend crossing a threshold (threshold computation #4 is real, dispatch #3 is mock-verify, and the two never meet).

## Rollup

- Verdict counts: KEEP 0 · STRENGTHEN 3 · MERGE 18 · REWRITE 3 · DELETE 4 · NIGHTLY 0 · UNKNOWN 0 (28 files)
- Priority: P0 ×1 (RecurringPaymentMatchE2ETest — merge, content must survive), P1 ×9, P2 ×12, P3 ×5, P4 ×1
- DUP clusters (survivor ← duplicates): golden/HomeDashboardFinancialInvariantTest + golden/AnalyticsDashboardBudgetParityGoldenTest ← #4,#5,#7,#8,#11,#24; golden/RecurringBillPaymentMatchTest + RecurringPlannedActualNoDoubleCountGoldenTest ← #16; golden/ReceiptMatchingNoDoubleCountGoldenTest ← #14; golden/NotificationReviewDashboardBudgetGoldenTest (+ #12 strengthened) ← #13; golden/BackupRestoreRoundtripGoldenTest ← #2; domain/groups/SettlementCalculatorTest ← #10; scenarios/SharedExpenseGroupScenarioTest ← #17; domain/budget/BudgetMonitorTest ← #3; domain/currency/ConversionSemanticsHardeningTest + CurrencyNormalizationBehavioralTest ← #18,#21,#22,#28; domain/util unit/stress tests ← #19,#25; golden/GoldenTestBase ← #9
- FRAGILE: 1 severe (#13, 636-LOC manual graph), moderate constructor coupling in #1 and harness-based flow tests
- Ledger families: none apply (e2e/integration not yet run per TEST_FAILURE_LEDGER.md:20); F-03 seam adjacency noted for #3
- Prior-audit (2026-05) deltas: "e2e never touches a real pipeline" OVERTURNED for 5 GoldenTestBase files; "deprecated DAO calls in harness + AnalyticsPipelineTest" CONFIRMED (now DeprecationLevel.ERROR); "Categorization/ExpenseCreation mislabeled integration" CONFIRMED; "ReceiptProcessingPipelineTest mock-heavy, no real lifecycle" CONFIRMED; "NotificationExpenseDashboardPipelineTest fragile" CONFIRMED (worse: 636 LOC).
