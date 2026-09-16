# Batch 17 — domain/budget, domain/business, domain/carbon, domain/cashflow, domain/categorization, domain/challenge, domain/common, domain/consistency, domain/core (money/time)

Scope: Segments 2 (Budget), 6 (Merchant Categorization), 13 (Cash Flow), 16 (Currency/money core), 17 (Business reports), 27 (Carbon), 29 (consistency checker), 32 (time core), 37 (Challenges) · Files: 34 · LOC: ~10,550 · ~430 tests · 0 @Ignore
Reviewer notes: Zero `@Ignore` in the whole batch — the 2026-05 "move categorization stress to nightly" recommendation was never applied; both stress files still run in PR CI. F-03 root-caused statically (see BudgetMonitorTest). Money tests are the strongest area; P6BudgetCleanupTest is the weakest (reflection/tautology). Prior-audit verdict for BudgetForecastingEngineStubTest (DELETE) is already executed — file no longer exists in `domain/budget/`.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 25 | 0 | 3 | 4 | 0 | 2 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/domain/budget/BudgetAutopilotEngineTest.kt | 444 | 13 | 0 | MOCKED | BudgetAutopilotEngine (+real MultiCurrencyRepository, BudgetForecastingEngine) | KEEP | P1 | BudgetForecastingEngineTest, ui BudgetViewModelStressTest | Real trend/cap/volatility math; parity test w/ forecast; FRAGILE-lite ctor |
| 2 | test/…/domain/budget/BudgetCalculatorBoundaryTest.kt | 380 | 16 | 0 | PURE | BudgetCalculator | KEEP | P1 | BudgetCalculatorTest, TimeBoundaryTest, GoldenTest | Leap/DST/anchor coercion; TZ restored in finally; 2 cases near-DUP w/ #4 |
| 3 | test/…/domain/budget/BudgetCalculatorGoldenTest.kt | 83 | 3 | 0 | PURE | BudgetCalculator | KEEP | P1 | BudgetCalculatorBoundaryTest | Golden calendar/rolling/anniversary; different anchors than #2 |
| 4 | test/…/domain/budget/BudgetCalculatorTest.kt | 442 | 16 | 0 | PURE | BudgetCalculator | KEEP | P1 | BoundaryTest (2 dup cases), TimeBoundaryTest | Exact windows; drop duplicated calendar-yearly cases |
| 5 | test/…/domain/budget/BudgetCalculatorTimeBoundaryTest.kt | 291 | 11 | 0 | PURE | BudgetCalculator | KEEP | P1 | #2/#4 | java.time migration lock-in; invalid-mode throws; half-open contains; GlobalTimeZoneTestLock |
| 6 | test/…/domain/budget/BudgetForecastingEngineDiagnosticsTest.kt | 195 | 4 | 0 | MOCKED | BudgetForecastingEngine diagnostics | KEEP | P2 | BudgetForecastingEngineTest | FORECAST_GENERATED/UNAVAILABLE events; writer failure tolerated |
| 7 | test/…/domain/budget/BudgetForecastingEngineTest.kt | 807 | 26 | 0 | MOCKED | BudgetForecastingEngine | KEEP | P1 | #1, #6, BudgetTrendBoundaryTest | Trend/zero-fill/confidence; ABORT-vs-FK conflict mapping; FRAGILE 10-dep ctor |
| 8 | test/…/domain/budget/BudgetHistorySeriesBuilderTest.kt | 59 | 2 | 0 | PURE | BudgetHistorySeriesBuilder | KEEP | P2 | #1 (uses builder) | Half-open window + zero-fill counts |
| 9 | test/…/domain/budget/BudgetMonitorStressTest.kt | 366 | 11 | 0 | MOCKED | BudgetMonitor | REWRITE | P2 | BudgetMonitorTest | F-03 (5 fail); stub DeliveryResult; concurrency cases worth keeping |
| 10 | test/…/domain/budget/BudgetMonitorTest.kt | 266 | 6 | 0 | MOCKED | BudgetMonitor | REWRITE | P1 | BudgetMonitorStressTest | F-03 (3 fail); stale DeliveryResult stubs; diagnostics tests good |
| 11 | test/…/domain/budget/BudgetRecommendationEngineTest.kt | 121 | 5 | 0 | PURE | BudgetRecommendationEngine | KEEP | P2 | ui BudgetForecastingViewModelTest | Ordered recommendations, savings clamp, health summary format |
| 12 | test/…/domain/budget/BudgetTrendBoundaryTest.kt | 120 | 1 | 0 | MOCKED | BudgetForecastingEngine | REWRITE | P2 | BudgetForecastingEngineTest | Stale: drives dead DAO path; engine reads snapshots (BudgetForecastingEngine.kt:389) |
| 13 | test/…/domain/budget/P6BudgetCleanupTest.kt | 501 | 9 | 0 | MIXED/REFLECTION | BudgetRepository, SpendingPaceCalculator, CashFlowCalculator, constants | REWRITE | P3 | CashFlowCalculatorTest | 2 real tests; 7 reflection/self-arithmetic tautologies |
| 14 | test/…/domain/budget/SharedBudgetManagerTest.kt | 435 | 14 | 0 | MOCKED | SharedBudgetManager (+real BudgetCalculator) | KEEP | P2 | data/repository BudgetRepositoryStressTest | Progress math, rolling vs calendar window, purchase-only semantics |
| 15 | test/…/domain/business/BusinessExpenseReportGeneratorTest.kt | 803 | 40 | 0 | MOCKED | BusinessExpenseReportGenerator, BusinessExpenseRepository | KEEP | P1 | (none) | Purchase-only defense-in-depth; CSV injection; mixed-currency partial flag |
| 16 | test/…/domain/carbon/CarbonFootprintCalculatorTest.kt | 347 | 23 | 0 | MOCKED | CarbonFootprintCalculator | KEEP | P2 | ui CarbonFootprintViewModelTest, verification CarbonFootprintTest | Numeric factor math; uncapped DAO regression; ~5 isNotNull-only tests; mixed real/fixed clock |
| 17 | test/…/domain/cashflow/CashFlowCalculatorTest.kt | 1024 | 29 | 0 | MOCKED | CashFlowCalculator | KEEP | P1 | P6BudgetCleanupTest, ui CashFlowCalendarViewModelTest | Movement-aware classification; read-path purity; barrier partial flags; DST; fail-closed currency precondition |
| 18 | test/…/domain/categorization/CategorizationComponentsTest.kt | 340 | 41 | 0 | PURE | MerchantCanonicalizer, GreeklishNormalizer, SemanticKeywordMatcher, ContextualInferenceEngine | KEEP | P1 | #24, #25 (dup sources) | Merge survivor for canonicalizer/semantic stress files |
| 19 | test/…/domain/categorization/CategorizationEngineDebugTest.kt | 221 | 7 | 0 | MOCKED | CategorizationEngine | KEEP | P2 | #20, #21 | Overturns prior P3: asserts cascade layer order + legal learn path + hashed-key privacy |
| 20 | test/…/domain/categorization/CategorizationEngineStressTest.kt | 651 | 33 | 0 | MOCKED/STRESS | CategorizationEngine | NIGHTLY | P2 | #19, #21 | NOT @Ignored; 10k-thread test; prune 4 no-assertion tests |
| 21 | test/…/domain/categorization/CategorizationEngineTest.kt | 120 | 6 | 0 | MOCKED | CategorizationEngine | KEEP | P1 | #19, #20 | Exact/canonical/unknown + cache; layers 3-6 mocked out |
| 22 | test/…/domain/categorization/CategoryKeywordsTest.kt | 69 | 7 | 0 | PURE | CategoryKeywords | KEEP | P3 | #18 | Dictionary integrity + deterministic dedup guard |
| 23 | test/…/domain/categorization/ContextualInferenceEngineStressTest.kt | 660 | 27 | 0 | PURE/STRESS | ContextualInferenceEngine | NIGHTLY | P3 | #18 (Contextual class) | Brackets/time/day/source asserted; wall-clock perf tests are only CI risk |
| 24 | test/…/domain/categorization/MerchantCanonicalizerStressTest.kt | 65 | 8 | 0 | PURE | MerchantCanonicalizer | MERGE | P3 | #18 (survivor) | Mostly dup of #18; carry Greek-script/iterative cases over |
| 25 | test/…/domain/categorization/SemanticKeywordMatcherStressTest.kt | 50 | 6 | 0 | PURE | SemanticKeywordMatcher | MERGE | P3 | #18 (survivor) | Dup of #18 semantics class |
| 26 | test/…/domain/challenge/SpendingChallengeManagerTest.kt | 195 | 5 | 0 | MOCKED | SpendingChallengeManager | KEEP | P2 | ui SpendingChallengesViewModelTest | Streak w/ grouped query (no day-by-day reads); baseline; DST range |
| 27 | test/…/domain/common/HashingTest.kt | 36 | 4 | 0 | PURE | Hashing.kt sha256/sha256Fingerprint | KEEP | P3 | (none) | Known SHA-256 vector pins real behavior |
| 28 | test/…/domain/consistency/LegacyDataConsistencyCheckerTest.kt | 377 | 5 | 0 | MOCKED (fakes) | LegacyDataConsistencyChecker | KEEP | P2 | none in consistency/ (batch 06) | Event-log completeness invariants; weak `>= 1` assertions |
| 29 | test/…/domain/core/money/CurrencyNormalizationBehavioralTest.kt | 249 | 12 | 0 | PURE (fakes) | CurrencyConverter, MoneyNormalizationEngine | KEEP | P0 | #30, #31 | validDate selection, fail-closed conversion, invalid-currency exclusion |
| 30 | test/…/domain/core/money/InvalidCurrencyBehavioralTest.kt | 273 | 13 | 0 | PURE (fakes) | MoneyNormalizationEngine, MoneyAggregateBuilder, MoneyMappers | KEEP | P0 | #29 | Invalid currency never crashes, degrades to partial/UNAVAILABLE |
| 31 | test/…/domain/core/money/MoneyAggregateBuilderRestrictionTest.kt | 112 | 5 | 0 | PURE | MoneyAggregateBuilder | KEEP | P1 | scenarios MoneyAggregateBuilderTest; 1 case dup of #29 | API restriction contract (basis rejection, RequireBucketDate) |
| 32 | test/…/domain/core/money/NormalizationProvenanceTest.kt | 178 | 9 | 0 | PURE | MoneyNormalizationEngine | KEEP | P1 | #29 | Rate provenance + quality enum COMPLETE/PARTIAL/UNAVAILABLE/ESTIMATED |
| 33 | test/…/domain/core/time/PeriodKindContractTest.kt | 70 | 4 | 0 | PURE | PeriodKind | MERGE | P3 | #34 (survivor) | Keep TimePeriodUtils-parity tests; rest dup of #34 |
| 34 | test/…/domain/core/time/PeriodRangeTest.kt | 200 | 9 | 0 | PURE | PeriodRange, PeriodKind | KEEP | P1 | #33 | Half-open contract, DST 23-25h, leap Feb, custom bounds |

## Findings (noteworthy files only)

### test/…/domain/budget/BudgetMonitorTest.kt (F-03, 3 failures)
- Statically root-caused. BudgetMonitor.kt:433: `sendNotification` returns `notificationService.sendBudgetAlert(...) == DeliveryResult.DELIVERED`; `updateXNotification` fires only `if (delivered)` (BudgetMonitor.kt:322-345). This is the intentional BUD-3 "only mark notified if actually delivered" gate.
- The tests use `mockk(relaxed = true)` for `NotificationService` and never stub `sendBudgetAlert`'s new `DeliveryResult` return, so `delivered` is false and both the `updateXNotification` and `sendBudgetAlert` verifications fail — exactly the 3 threshold tests (warning/critical/exceeded); the skip/diagnostic tests don't hit the gate.
- Ledger's "prod uses cached getBudgetStatuses()" note is only half right: tests already stub the cached `getBudgetStatuses()` flow (BudgetMonitorTest.kt:58); the break is the delivered-gate contract, not the cache.
- Fix: in both files stub `every { notificationService.sendBudgetAlert(any(), any(), any()) } returns NotificationService.DeliveryResult.DELIVERED`. Same fix explains all 5 stress failures (sections EXCEEDED, CRITICAL, concurrent, multi-budget, period-boundary — the only ones verifying update/send).
- REWRITE P1: tests otherwise assert real behavior (throttle via MIN_CHECK_INTERVAL, onBackground state reset, SKIPPED/grossFallback diagnostics).

### test/…/domain/budget/BudgetTrendBoundaryTest.kt (stale data path)
- The only test stubs `expenseDao.getMonthlySpendingTotalsByCategoryBetween(...)`, but production history now comes from `expenseRepository.getExpenseSnapshotsBetween` (BudgetForecastingEngine.kt:389); the relaxed `expenseRepository` mock returns empty, so predicted spending would be 0 and the ±10% assertions cannot hold. `currencySettingsRepository = mockk()` (strict) would also throw on unstubbed `resolveHomeCurrency()`.
- Real value: exact-at-boundary (1.10/0.90 ratio) trend semantics. REWRITE P2 — restub via `getExpenseSnapshotsBetween` snapshots like BudgetForecastingEngineTest does.

### test/…/domain/budget/P6BudgetCleanupTest.kt (tautology cluster)
- Zero-behavior tests: `compute_adjusted_spend_rethrows_cancellation` (asserts a method exists via reflection), `cashflow_amounts_are_normalized` (field-type reflection), `estimate_income_uses_actual_month_count` (computes expected math inside the test and asserts its own arithmetic — never calls production), `week_number_iso_consistent` / `weekly_period_sunday_anchor` (re-implement BudgetCalculator weekly logic locally instead of calling it), `bug - cache mutex` style `assertTrue(true)` pattern.
- Real tests worth keeping: `pace_percentage_minus_one_when_no_baseline` (SpendingPaceCalculator sentinel) and `income_recurring_shows_as_positive` (real CashFlowCalculator day balances). The reflection invariant pins (`PAID` excluded from ACTIVE_OCCURRENCE_STATUSES, MAX_ROLLOVER_PERIODS ≥ 365) are brittle but pin genuine invariants — move to an architecture guard or keep knowingly.
- REWRITE P3.

### test/…/domain/categorization/CategorizationEngineStressTest.kt (re-verified, prior NIGHTLY not applied)
- Zero `@Ignore` in file (re-verified across batch). `stress - 10000 concurrent categorization requests` spins a real 10-thread pool with 60s latch; two wall-clock perf tests. These belong out of PR CI.
- Also contains dead tests to prune when moving: `cache expiry at exactly 300s boundary` (advances no time, no assertion), `all Greek diphthong combinations` (loops with a comment only, no assertion), `bug - cache mutex may block` (asserts `true`).
- Valuable parts: layer priority/confidence regressions (0.98 exact, canonical penalty), fuzzy edit-distance thresholds incl. <4-char disable, empty/whitespace merchant, unicode/emoji robustness. NIGHTLY P2.

### test/…/domain/categorization/CategorizationEngineDebugTest.kt (overturns prior verdict)
- Prior 2026-05 audit: P3 "fully mocked debug engine tests; low signal". Overturned: `debugCategorize returns trace…` asserts the LEGAL_PATHS 6-layer cascade order on a real engine (Exact layer missed, Canonical layer hit, CANONICAL match type); `learnMerchantCategory` tests exercise the single legal write path (`engine.learnMerchantCategory` → repository.insert → `invalidateAllCaches`) and the conflict branch; `traceDecision stores hashed merchant key not raw name` is a real privacy assertion (no raw merchant name in trace). KEEP P2.

### test/…/domain/cashflow/CashFlowCalculatorTest.kt
- Best-in-batch: asserts transfer-direction inflow/outflow, UNKNOWN/null-direction ignored, negative-amount purchase stays an expense (type-based, not sign-based), read path uses `projectOccurrences` and never `generateOccurrences` (recurring legal path), restore-barrier block degrades to `isPartial` instead of throwing, typed currency-mismatch precondition rejects USD starting balance (fail-closed money), DST 23h/25h day keys under GlobalTimeZoneTestLock, half-open end exclusivity. KEEP P1.

### test/…/domain/core/money/ (4 files)
- These four files are the JVM-side guard for the Segment 16 approved types: validDate-over-lastUpdated rate selection, TRANSACTION_DATE/PERIOD_END without a date fail (not silently latest), invalid currency excluded with typed `INVALID_CURRENCY` failure and metadata counters, legacy `fromBuckets` rejects non-LATEST basis with IllegalArgumentException, RequireBucketDate policy enforced before conversion, provenance fields (rateValidDate/rateLastUpdated/conversionPath) propagated, and quality COMPLETE/PARTIAL/UNAVAILABLE/ESTIMATED. No raw cross-currency sums asserted anywhere — money rule respected. All KEEP; the two behavioral files are P0.

## Area gaps (what is NOT tested in this area)

- No DB-backed test of `BudgetRepository.getBudgetStatuses()` feeding BudgetMonitor — the alert pipeline (status → threshold → notification → timestamp) is only tested with mocked repository; prior audit found `e2e/BudgetAlertPipelineTest` is mock-verify-only.
- BudgetMonitor notification-permission / SecurityException path and `diagnosticSink.recordBlockedOperation` branch untested.
- CategorizationEngine layers 3-6 (Greeklish → Fuzzy → Semantic → Context) have no fast, real-component end-to-end cascade test in PR CI (unit file mocks them out; the real-behavior coverage sits in the NIGHTLY stress file and per-component tests).
- Rollover accumulation loop itself (MAX_ROLLOVER_PERIODS behavior) is only pinned by reflection, not behavior — `BudgetRolloverTest` (data/repository, batch 13) covers the repo path.
- BudgetForecastingEngineTest self-documents gaps: mixed-currency history normalization and non-home-currency budget forecast (conversion of the limit) — the latter is exercised only for the failure branch in the Diagnostics test.
- SharedBudgetManager `getMemberContributions` returns zero placeholders; real per-member attribution (if ever implemented) has no test.
- Carbon calculator: emission factors assume EUR-denominated amounts; no non-EUR currency handling test.
- `BudgetMonitor.invalidateCache()` (BUD-16 change-driven invalidation) has no test in this batch.

## Rollup

- Verdict counts: KEEP 25, MERGE 3, REWRITE 4, NIGHTLY 2, DELETE 0, STRENGTHEN 0, UNKNOWN 0 (34 files).
- P0 count: 2 (CurrencyNormalizationBehavioralTest, InvalidCurrencyBehavioralTest).
- DUP pairs: MerchantCanonicalizerStressTest ↔ CategorizationComponentsTest.MerchantCanonicalizerTest; SemanticKeywordMatcherStressTest ↔ CategorizationComponentsTest.SemanticKeywordMatcherTest; PeriodKindContractTest ↔ PeriodRangeTest; BudgetCalculatorTest calendar-yearly cases ↔ BudgetCalculatorBoundaryTest calendar-yearly cases (2 tests, near-DUP); BudgetMonitorStressTest threshold sections ↔ BudgetMonitorTest (post-fix partial dup); MoneyAggregateBuilderRestrictionTest RequireBucketDate-success case ↔ CurrencyNormalizationBehavioralTest bucket-policy case (single case). No cross-batch DUP with top-level `consistency/` (batch 06) — `domain/consistency/LegacyDataConsistencyCheckerTest` tests a different class.
- FRAGILE count: 4 (BudgetForecastingEngineTest, BudgetAutopilotEngineTest — 10-dep ctors; CategorizationEngineDebugTest/CategorizationEngineStressTest — 8-dep ctor + Provider; P6BudgetCleanupTest — reflection on private fields).
- F-03: statically root-caused to the BUD-3 `DeliveryResult.DELIVERED` delivered-gate; fix is a test-side stub in BudgetMonitorTest + BudgetMonitorStressTest (8 failures, both files REWRITE).
- Prior-audit updates: BudgetForecastingEngineStubTest (DELETE) confirmed already removed; categorization stress files confirmed NOT moved to nightly (no @Ignore); CategorizationEngineDebugTest prior "low signal" overturned to KEEP P2; BudgetMonitorStressTest prior MOVE_TO_NIGHTLY superseded by REWRITE (F-03 fix first, then decide placement).
