# Batch 16 — domain/alerts · domain/analytics · domain/bank

Scope: Segment 8 (Analytics & Insights: InsightsEngine, AnomalyDetector, AdvancedAnalyticsEngine, TotalsAggregationEngine, pace/insight calculators, currency normalization, input assembler, golden fixtures), Segment 11 boundary (AnomalyAlertOrchestrator), Segment 14 (Bank Integration: BankApiIntegration, statement audit ledger) · Files: 33 · LOC: 9,499 (268 @Test, 0 @Ignore)
Reviewer notes: Static-only review. Analytics is the strongest domain cluster in the suite: most files are PURE/MOCKED hybrid with real math asserted against hand-computed oracles. No file in this batch is named in TEST_FAILURE_LEDGER families; batch-wide relevance: **F-07** ("domain.forecasting.* + analytics.* delta drift, ~20, `Expected x ± d but was y`, likely P5 normalization") — all tolerance-asserting analytics files below are candidates; nearly all use EUR-only fixtures, so the drift (if real) points at engine rate-basis (historical vs latest) rather than test data. LEGAL_PATHS Analytics section: the deprecated self-fetching `AdvancedAnalyticsEngine` overloads (merchant=ERROR, category/patterns/stats=WARNING) are still exercised by 3 test files (suppressed with `@Suppress("DEPRECATION_ERROR")`); production usage is guarded by `architecture/DeprecatedApiArchitectureGuardTest.kt` but test-side usage is not. Money rule respected: no test asserts raw sums of mixed currencies; conversion is asserted via `AnalyticsCurrencyNormalizer`/`AnalyticsInputAssembler` (fail-closed exclusion + warnings). Stale 2026-05 verdicts re-verified: 4 of this batch's files did not exist in the prior audit; two prior claims overturned (see findings).

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 20 | 6 | 2 | 2 | 2 | 1 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/domain/alerts/AnomalyAlertOrchestratorTest.kt | 494 | 12 | 0 | MOCKED | AnomalyAlertOrchestrator | KEEP | P1 | TransactionSideEffectPlannerTest; e2e/BudgetAlertPipelineTest | cooldown/dedup/single-flight/cancellation asserted |
| 2 | test/…/domain/analytics/AdvancedAnalyticsDashboardTest.kt | 504 | 14 | 0 | MOCKED | AdvancedAnalyticsDashboard | KEEP | P0 | golden/AnalyticsDashboardBudgetParityGoldenTest | half-open bounds, DST, leap; best in batch |
| 3 | test/…/domain/analytics/AdvancedAnalyticsEngineDeepTest.kt | 345 | 10 | 0 | MOCKED | AdvancedAnalyticsEngine | STRENGTHEN | P1 | #4 #5, verification/CrossSourceVerificationTest | calls deprecated self-fetching overloads (incl. ERROR merchant) |
| 4 | test/…/domain/analytics/AdvancedAnalyticsEngineNormalizedTest.kt | 183 | 3 | 0 | MOCKED | AdvancedAnalyticsEngine | KEEP | P1 | #3 #5 | legal NormalizedAnalyticsInput path; only 3 tests |
| 5 | test/…/domain/analytics/AdvancedAnalyticsEngineTest.kt | 133 | 3 | 0 | MOCKED | AdvancedAnalyticsEngine | MERGE | P2 | #3 (survivor), #4 | deprecated self-fetch; weaker dup of DeepTest |
| 6 | test/…/domain/analytics/AnalyticsCurrencyNormalizerTest.kt | 195 | 7 | 0 | PURE | AnalyticsCurrencyNormalizer | KEEP | P0 | integration/MultiCurrencyAnalyticsTest, batch-10 currency | stale-by-validDate (P5) semantics locked |
| 7 | test/…/domain/analytics/AnalyticsInputAssemblerProvenanceTest.kt | 281 | 5 | 0 | MOCKED | AnalyticsInputAssembler | KEEP | P0 | #6 | legal assembler path; exclusion + rate provenance |
| 8 | test/…/domain/analytics/AnalyticsStressTest.kt | 119 | 1 | 0 | STRESS | AdvancedAnalyticsEngine | NIGHTLY | P2 | #3 | 10k tx; wall-clock `<10s` flaky in PR CI |
| 9 | test/…/domain/analytics/AnalyticsWindowingSupportTest.kt | 88 | 5 | 0 | PURE | AnalyticsWindowingSupport ext.fns | KEEP | P2 | metrics/TimePeriodAlignmentTest | merchant-key normalization, unicode |
| 10 | test/…/domain/analytics/AnomalyDetectorTest.kt | 208 | 4 | 0 | PURE | AnomalyDetector | KEEP | P1 | #1 (complementary: detector vs orchestrator) | effective-amount guard, FP/FN guards |
| 11 | test/…/domain/analytics/CategoryInsightEngineTest.kt | 474 | 11 | 0 | PURE | CategoryInsightEngine | KEEP | P1 | InsightsEngineValidationTest | golden totals; 4th inline copy of March dataset |
| 12 | test/…/domain/analytics/DayOfWeekAnalyzerTest.kt | 238 | 6 | 0 | PURE | DayOfWeekAnalyzer | KEEP | P2 | InsightsEngineValidationTest (DoW section) | DST + monday-zero indexing (bug b17) |
| 13 | test/…/domain/analytics/IncludeDepositsForBehaviorCleanupTest.kt | 25 | 1 | 0 | SRCTEXT | (none — source string scan) | DELETE | P3 | architecture/* guard suite | one-off cleanup guard; string-scan anti-pattern |
| 14 | test/…/domain/analytics/InsightsEngineDeepTest.kt | 211 | 7 | 0 | MOCKED | InsightsEngine | KEEP | P1 | #16 #17 | real sub-engines; effective-amount math |
| 15 | test/…/domain/analytics/InsightsEngineEdgeCaseTest.kt | 178 | 6 | 0 | MOCKED | InsightsEngine | STRENGTHEN | P2 | #14 #16 #17 | 2 "does not crash" only; runBlocking + real clock |
| 16 | test/…/domain/analytics/InsightsEngineTest.kt | 120 | 4 | 0 | MOCKED | InsightsEngine | KEEP | P2 | #14 #15 #17 | buildDailyTotals real sums; real clock |
| 17 | test/…/domain/analytics/InsightsEngineValidationTest.kt | 524 | 13 | 0 | MOCKED | InsightsEngine | KEEP | P1 | #14 (complementary) | real calculators; MoM/median/DoW math |
| 18 | test/…/domain/analytics/MerchantInsightEngineTest.kt | 147 | 4 | 0 | PURE | MerchantInsightEngine | KEEP | P2 | #3 merchant tests | alias canonicalization, recurring variance |
| 19 | test/…/domain/analytics/MonthlyComparisonCalculatorTest.kt | 181 | 3 | 0 | PURE | MonthlyComparisonCalculator | KEEP | P1 | InsightsEngineValidationTest | F-07 candidate (1283.59 ± d) |
| 20 | test/…/domain/analytics/SpendingPaceBoundaryTest.kt | 181 | 4 | 0 | PURE | SpendingPaceCalculator | KEEP | P1 | #21 #22 | exact 90/110 threshold boundaries |
| 21 | test/…/domain/analytics/SpendingPaceCalculatorDeepTest.kt | 188 | 6 | 0 | PURE | SpendingPaceCalculator | KEEP | P1 | #20 #22 (survivor for merge) | blended smoothing formulas asserted |
| 22 | test/…/domain/analytics/SpendingPaceCalculatorValidationTest.kt | 559 | 14 | 0 | PURE | SpendingPaceCalculator | MERGE | P2 | #20 #21 (survivors) | day-4/Feb cases duplicate DeepTest |
| 23 | test/…/domain/analytics/SpendingPaceGoldenTest.kt | 109 | 2 | 0 | PURE | SpendingPaceCalculator | KEEP | P1 | #19 #21 | F-07 candidate; oracle 991.79/2049.70/175% |
| 24 | test/…/domain/analytics/SpendingPersonalityClassifierTest.kt | 564 | 17 | 0 | MOCKED | SpendingPersonalityClassifier | STRENGTHEN | P2 | verification/LifestyleAnalysisTest | reflection into privates; some range-only asserts |
| 25 | test/…/domain/analytics/SpendingThresholdCalculatorTest.kt | 213 | 11 | 0 | MOCKED | SpendingThresholdCalculator | STRENGTHEN | P2 | — | P90 math good; cache "tests" are weak tolerances |
| 26 | test/…/domain/analytics/TotalsAggregationEngineDeepTest.kt | 205 | 5 | 0 | MOCKED | TotalsAggregationEngine | STRENGTHEN | P1 | #27 #28 | monthAvg computed but NEVER asserted |
| 27 | test/…/domain/analytics/TotalsAggregationEngineTest.kt | 961 | 48 | 0 | MOCKED | TotalsAggregationEngine | KEEP | P0 | #26 #28, metrics/DashboardWidgetConsistencyTest | legal MCR path; isPartial propagation; real clock at :49 |
| 28 | test/…/domain/analytics/TotalsAggregationEngineValidationTest.kt | 561 | 18 | 0 | MOCKED | TotalsAggregationEngine | KEEP | P1 | #26 #27 | status trio triplicated across #26/#27/#28 |
| 29 | test/…/domain/analytics/TransferDirectionAnalyticsTest.kt | 234 | 5 | 0 | PURE | TransferDirectionAnalytics | KEEP | P2 | — | prune/cap via private-field reflection (fragile) |
| 30 | test/…/domain/analytics/fixtures/ExpectedResults.kt | 226 | 0 | 0 | FIXTURE | (oracle constants) | KEEP | P2 | metrics/GoldenAnalyticsDataset (batch-02: fold) | sole consumer is verification/GoldenMasterVerificationTest |
| 31 | test/…/domain/analytics/fixtures/GoldenDataSets.kt | 321 | 0 | 0 | FIXTURE | (synthetic datasets) | KEEP | P3 | #30, metrics/GoldenAnalyticsDataset (batch-02) | UTC dates vs base's systemDefault TZ mismatch |
| 32 | test/…/domain/bank/BankApiIntegrationTest.kt | 382 | 12 | 0 | MOCKED | BankApiIntegration | STRENGTHEN | P1 | golden/BankSyncFailureRecoveryGoldenTest, scenarios/BankSyncScenarioTest | P10 contracts strong; 2 tautologies; refresh unverified |
| 33 | test/…/domain/bank/BankStatementItemAuditTest.kt | 147 | 7 | 0 | PURE | (none — constructs entity directly) | REWRITE | P3 | — | asserts its own hand-written strings (tautology) |

## Findings (noteworthy files only)

### app/src/test/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngineDeepTest.kt (+ AdvancedAnalyticsEngineTest.kt, AnalyticsStressTest.kt)
- All engine calls use the deprecated self-fetching overloads: `getCategoryAnalytics(period, currency)` (DeepTest:81,109,136,162; StressTest:102), `getStatisticalInsights(period, currency)` (DeepTest:180,204), `getSpendingPatterns(period, currency)` (DeepTest:224,294), and `getMerchantAnalytics(period, "EUR", limit)` (DeepTest:248,281,283) — the merchant one is `DeprecationLevel.ERROR` per LEGAL_PATHS, compiled only via `@Suppress("DEPRECATION_ERROR")` at DeepTest:234,255.
- LEGAL_PATHS FORBIDDEN list names exactly this usage; the legal path (`AnalyticsInputAssembler.build()` → `NormalizedAnalyticsInput`) is only exercised by AdvancedAnalyticsEngineNormalizedTest for patterns/stats — merchant and category analytics have NO legal-path unit test.
- Content itself is high value (percentile interpolation DeepTest:86-87, sample-stddev :183-191, sparkline windowing :111-166, merchant alias+period-cap :257-286), so migrate, don't delete.
- Action: STRENGTHEN #3 — port its scenarios onto the normalized-input overloads; MERGE #5 into it (weekend-warrior and stats cases are weaker duplicates).

### app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineDeepTest.kt
- `average monthly weekly and daily formulas are correct` builds a 12-month stub, computes `monthAvg` (line 164) and then never asserts it — lines 170-173 comment says "The test expectation was 200.0, so we accept whatever value is computed". Named "...formulas are correct" while one of four formulas is unchecked.
- F-07 candidate (tolerance asserts, `assertApproxEquals`). Also duplicates `dateMs` (line 203) noted by batch-02.
- Action: STRENGTHEN — restore a real monthAvg expectation or drop it from the test name.

### app/src/test/java/com/yourname/expensetracker/domain/bank/BankStatementItemAuditTest.kt
- Tautological: each test constructs `BankStatementImportItem` with a hand-written `errorReason` string and then asserts the same string contains that substring (e.g., lines 26-35, 96-106); line 83 asserts the string literal `"SKIPPED" != "CREATED_REVIEW"`. Zero production code exercised.
- The underlying policy (sanitized reason codes only — no `java.lang.`, no stack frames) matches AGENTS privacy rules and deserves coverage, but must be tested against the producer (statement import audit writer / lifecycle processor), not its own inputs.
- Action: REWRITE to drive the real writer and assert produced reason codes; until then near-zero value.

### app/src/test/java/com/yourname/expensetracker/domain/bank/BankApiIntegrationTest.kt
- Strong P10 contract coverage: STRICT_EXTERNAL_ID + hashed provider identity with stable re-sync key (lines 173-233, privacy-correct — raw provider id never persisted), abs() amount normalization, cancellation propagation (369-381). Overturned stale 2026-05 verdict "reflection-based test of private mapTransactionToExpense" — the method is now called directly.
- Weak spots: `refreshToken persists new tokens on success` (333-367) comments that the mock DAO "will capture the updateToken call" but never `coVerify`ies it — asserts only `assertNotNull(syncResult)`; `low confidence ... triggers pending review on sync` (250-265) admits in comments it does not exercise the sync-loop confidence gate; `bank transaction defaults to high confidence` / `high confidence ... above threshold` (237-277) assert data-class defaults — tautologies.
- Action: STRENGTHEN — verify updateToken, drive the low-confidence review route through syncTransactions, drop the two default-value tests.

### app/src/test/java/com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestratorTest.kt
- Good behavioral coverage: merchant 24h / category 12h cooldowns, per-expense dedup, `looks_normal` feedback suppression, single-flight concurrency via CompletableDeferred (234-266), CancellationException propagation (475-493) — matches worker/privacy rules on not swallowing cancellation.
- Mostly `coVerify`-based, but captured slots assert real payload content (severity line 177, message contains merchant + formatted amount lines 179-180), so not mock-verification-only. FakeTimeProvider used throughout — deterministic.
- Notification permission gating (Segment 11 rule) is not observable here (NotificationService is mocked) — acceptable at unit level, but no other test covers the anomaly-alert permission path (gap).

### domain/analytics/fixtures/GoldenDataSets.kt + ExpectedResults.kt
- Overturned stale 2026-05 claim (#81/#82: "used by SpendingPaceGoldenTest, CategoryInsightEngineTest, etc."): grep shows the ONLY consumer is `verification/GoldenMasterVerificationTest.kt`. The 42-file `AnalyticsEngineTestBase` ecosystem uses `AnalyticsTestCompat.kt` helpers, not these fixtures. Batch-02's MERGE target (fold `metrics/GoldenAnalyticsDataset` into `GoldenDataSets`) stands — this file is the survivor, but it is a one-consumer fixture today.
- Meanwhile the "golden March 2026" dataset is hand-copied inline in 4 test files (SpendingPaceGoldenTest:65, DayOfWeekAnalyzerTest:191, CategoryInsightEngineTest:404, MonthlyComparisonCalculatorTest:122) with identical amounts — the real consolidation should fold those copies into GoldenDataSets.
- TZ hazard: GoldenDataSets/ExpectedResults use `ZoneOffset.UTC` (GoldenDataSets:23, ExpectedResults:183-195) while `AnalyticsEngineTestBase.fixedNow` uses `ZoneId.systemDefault()` (base:101-104) and every consumer computes dates in system TZ — boundary tests only line up in UTC-size zones; use one pinned zone.

### F-07 (ledger) exposure in this batch
- Files asserting exact analytics totals with ±delta: SpendingPaceGoldenTest:37-41 (991.79/2049.70/175%), MonthlyComparisonCalculatorTest:40-44 (1283.59/1058.00/21.32%), DayOfWeekAnalyzerTest:66-68 (953.09/330.50), CategoryInsightEngineTest:60-73, TotalsAggregationEngineDeepTest, InsightsEngineDeepTest, AdvancedAnalyticsEngineDeepTest, SpendingPace*. All fixtures are single-currency EUR, so a measured drift would indicate engine-side rate-basis/normalization change (P5), not test-data error. Triage F-07 starting from SpendingPaceGoldenTest + MonthlyComparisonCalculatorTest (same 1283.59 oracle appears in both).

## Area gaps (what is NOT tested in this area)

- No unit test drives `AdvancedAnalyticsEngine.getCategoryAnalytics` / `getMerchantAnalytics` through the legal `NormalizedAnalyticsInput` path (only patterns/stats in AdvancedAnalyticsEngineNormalizedTest); the deprecated self-fetching forms are the only unit coverage for merchant/category analytics.
- `DataQualityReport.kt` has zero test references. `AnalyticsDataQuality` confidencePenalty/confidenceMultiplier math only incidentally touched (AdvancedAnalyticsEngineNormalizedTest passes warnings through).
- `DailyBucketEngine` and `BudgetVsActualEngine` have no dedicated domain tests in this cluster — they are only reached indirectly via golden/e2e/ui tests (golden/AnalyticsDashboardBudgetParityGoldenTest, ui BudgetVsActualFxBasisTest).
- Bank low-confidence → PendingReview route (P10-P1-04) is not exercised end-to-end; token refresh persistence not verified (see findings).
- Anomaly alert notification permission gating / SecurityException handling untested anywhere.
- `AnomalyAlertOrchestrator` CancellationException is covered, but no test covers DAO failure during `anomalyAlertRepository.insert` (partial-write behavior: alert row without notification or vice versa).
- No mixed-currency analytics test at unit level: every fixture is EUR-only; multi-currency analytics only via integration/MultiCurrencyAnalyticsTest (other batch). Given F-07, a normalized-input test with USD/EUR mixes asserting converted (not raw) totals is the missing guard.

## Rollup

- Verdicts: KEEP 20, STRENGTHEN 6, MERGE 2, REWRITE 2, DELETE 2, NIGHTLY 1, UNKNOWN 0 (33 files).
- P0 count: 4 (AdvancedAnalyticsDashboardTest, AnalyticsCurrencyNormalizerTest, AnalyticsInputAssemblerProvenanceTest, TotalsAggregationEngineTest). P1: 14. P2: 10. P3: 4. P4: 0. P5-family (F-07 exposure): 8 files.
- DUP pairs: AdvancedAnalyticsEngineTest ⊂ AdvancedAnalyticsEngineDeepTest/NormalizedTest (survivor DeepTest); SpendingPaceCalculatorValidationTest day-4/day-2/blended cases ≈ SpendingPaceCalculatorDeepTest (survivors DeepTest + BoundaryTest). Partial: period-status UNDER/OVER/NO_DATA trio in TotalsAggregationEngineTest/DeepTest/ValidationTest (keep once); inline golden-March dataset copied in 4 files (fold into fixtures/GoldenDataSets).
- FRAGILE count: 5 (AdvancedAnalyticsEngineDeepTest deprecated-API coupling; SpendingPersonalityClassifierTest + TransferDirectionAnalyticsTest private reflection; AnalyticsEngineTestBase TZ coupling affecting its 3 batch consumers; BankApiIntegrationTest 9-arg constructor wiring).
- Stale prior-audit verdicts overturned: BankApiIntegrationTest "reflection-based" → now direct, richer (STRENGTHEN); ExpectedResults/GoldenDataSets "used by pace/category tests" → sole consumer is GoldenMasterVerificationTest; all 2026-05 KEEP verdicts for files 1-12, 14-21, 23, 26-29 re-verified and upheld.
