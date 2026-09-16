# Batch 19 — domain intelligence, investment, location, logic, model, naturallanguage, negotiation

Scope: Segments 3 (notification capture — ConfidenceRouter/TransactionClassifier/dedupe policy), 6 (merchant categorization ML), 15 (investment), 19 (location enrichment engines), 1 (forecast — SynthesisEngine), 7 (recurring), 24 (split/settlement), 10 (dashboard mapper), 26 (NL search), 34 (bill negotiation) · Files: 31 · LOC: 8,728 (341 tests, 0 @Ignore)
Reviewer notes: Static review only. Ledger families touching this batch: F-04 + F-17 (ExpenseCategoryClassifierTest — 7 measured failures, DataStore collision + JSONException; hypothesis in findings). @Ignore re-verification: **zero** `@Ignore` in all 31 files — every prior-audit "Nightly/manual" file in this batch currently runs in PR CI. Prior-audit verdicts re-verified; 4 overturned (see findings). The prior audit's DELETE target `domain/location/TravelDetectionEngineTest.kt` no longer exists — replaced by `TravelDetectionEngineNormalizedTest.kt`. Deprecated-API check against main source: `AreaSpendingEngine.compute()` (AreaSpendingEngine.kt:86) and `TravelDetectionEngine.compute()` (TravelDetectionEngine.kt:105) are `@Deprecated` with **no production callers** (AnalyticsViewModel.kt:689 uses `computeNormalized`); `SpendingHeatmapEngine.compute()` and `LocationInsightsEngine.compute()` are NOT deprecated but their `computeNormalized(LocatedMoneyExpense)` overloads are untested anywhere. Batch-12 boundary confirmed clean from this side: batch 12 only mocks `LocationResolver`; real resolver coverage is here.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 22 | 3 | 4 | 1 | 0 | 1 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/domain/intelligence/ConfidenceRouterEdgeCaseTest.kt | 90 | 7 | 0 | MOCKED | ConfidenceRouter | KEEP | P1 | #2 | Exact 0.85/0.50/0.499 boundaries; NaN/blank rejected |
| 2 | test/…/domain/intelligence/ConfidenceRouterTest.kt | 173 | 11 | 0 | MOCKED | ConfidenceRouter | KEEP | P1 | #1 | Unknown-merchant floor, spam anti-trust, clamp |
| 3 | test/…/domain/intelligence/DuplicateDetectionPolicyDedupeKeyTest.kt | 90 | 6 | 0 | PURE | DuplicateDetectionPolicy | KEEP | P1 | DedupeKeyProducerConsistencyTest (b06) | ISSUE-6 contract; complementary to producer test |
| 4 | test/…/domain/intelligence/TransactionClassifierTest.kt | 180 | 10 | 0 | MOCKED | TransactionClassifier | STRENGTHEN | P2 | NotificationProcessingPipeline* (mock) | 3 tests no-assert/no-crash; temp dir never deleted |
| 5 | test/…/domain/intelligence/ml/ExpenseCategoryClassifierTest.kt | 272 | 11 | 0 | MOCKED | ExpenseCategoryClassifier | STRENGTHEN | P1 | e2e pipeline tests | F-04/F-17: 7 failures; isolation fix needed |
| 6 | test/…/domain/intelligence/ml/HybridExpenseClassifierTest.kt | 357 | 15 | 0 | MOCKED | HybridExpenseClassifier | KEEP | P1 | data/repository pipeline tests | Dict→ML→fallback order; CancellationException rethrow |
| 7 | test/…/domain/intelligence/ml/MerchantNormalizerStressTest.kt | 186 | 12 | 0 | MOCKED | MerchantNormalizer | MERGE | P2 | #8 | Not stress; DUP w/ #8; rename survivor; prior Nightly overturned |
| 8 | test/…/domain/intelligence/ml/MerchantNormalizerTest.kt | 104 | 6 | 0 | MOCKED | MerchantNormalizer | KEEP | P1 | #7 | Survivor; linkAlias tests are stub-echo; no key-derivation pin |
| 9 | test/…/domain/investment/InvestmentTrackerTest.kt | 374 | 19 | 0 | MOCKED | InvestmentTracker | KEEP | P1 | InvestmentGoldenScenarioTest | Epoch-0 all-time regression; fee math; FRAGILE ctor |
| 10 | test/…/domain/location/AreaSpendingEngineNormalizedTest.kt | 166 | 7 | 0 | PURE | AreaSpendingEngine (normalized) | KEEP | P1 | #11 | Live API; conversion filtering + grid grouping |
| 11 | test/…/domain/location/AreaSpendingEngineStressTest.kt | 75 | 4 | 0 | PURE | AreaSpendingEngine.compute | MERGE | P3 | #10 | Deprecated compute(), no prod callers; not stress |
| 12 | test/…/domain/location/LocationInsightsEngineStressTest.kt | 101 | 6 | 0 | PURE | LocationInsightsEngine | KEEP | P2 | ui analytics tests | Misnamed; normalized overload untested |
| 13 | test/…/domain/location/LocationResolverStressTest.kt | 283 | 12 | 0 | MOCKED | LocationResolver | KEEP | P1 | #14, data/location workers | Cascade priority, GPS bias, area-key contract |
| 14 | test/…/domain/location/LocationResolverTest.kt | 201 | 2 | 0 | MOCKED | LocationResolver | KEEP | P1 | #13 | Pins merchantKey-vs-derived cacheKey selection |
| 15 | test/…/domain/location/SpendingHeatmapEngineStressTest.kt | 562 | 32 | 0 | STRESS | SpendingHeatmapEngine | REWRITE | P3 | SpendingMapViewModelStressTest | 32 tests; wall-clock perf asserts; raw path only |
| 16 | test/…/domain/location/TravelDetectionEngineNormalizedTest.kt | 153 | 5 | 0 | PURE | TravelDetectionEngine (normalized) | KEEP | P1 | #17 | Live API; add gap-separation + determinism from #17 |
| 17 | test/…/domain/location/TravelDetectionEngineStressTest.kt | 110 | 7 | 0 | PURE | TravelDetectionEngine.compute | MERGE | P3 | #16 | Deprecated compute(); unique gap/determinism tests |
| 18 | test/…/domain/logic/CustomSplitParserTest.kt | 185 | 12 | 0 | PURE | CustomSplitParser | KEEP | P1 | — | Tolerance boundaries; line 63 name contradicts assertion |
| 19 | test/…/domain/logic/RecurrenceCalculatorTest.kt | 61 | 5 | 0 | PURE | RecurrenceCalculator | KEEP | P1 | RecurringExpenseRepositoryTest | Frequency monthly-normalization semantics |
| 20 | test/…/domain/logic/RecurringExpenseEngineEmptyListTest.kt | 114 | 4 | 0 | MOCKED | RecurringExpenseEngine | KEEP | P2 | domain/analytics Insights* | Regression: empty/single/stale/merchantKey grouping |
| 21 | test/…/domain/logic/SplitCalculatorGoldenTest.kt | 102 | 4 | 0 | PURE | SplitCalculator | MERGE | P2 | #22 | Verbatim 4-scenario subset of #22; prior KEEP overturned |
| 22 | test/…/domain/logic/SplitCalculatorTest.kt | 286 | 12 | 0 | PURE | SplitCalculator | KEEP | P0 | #21, groups tests | Cent-preserving splits, backdated validation, settlements |
| 23 | test/…/domain/logic/SynthesisEngineBlockPartyPaidExclusionTest.kt | 231 | 3 | 0 | MOCKED | SynthesisEngine + occurrence statuses | KEEP | P0 | #24, #26 | PAID/SKIPPED/CANCELLED double-count prevention |
| 24 | test/…/domain/logic/SynthesisEngineGoldenTest.kt | 210 | 3 | 0 | PURE | SynthesisEngine | KEEP | P1 | #26 | Bands, biweekly ±2 tolerance, discretionary rate |
| 25 | test/…/domain/logic/SynthesisEngineStressTest.kt | 1710 | 56 | 0 | STRESS | SynthesisEngine | NIGHTLY | P2 | #24, #26 | Prior Nightly confirmed; many assertNotNull-only |
| 26 | test/…/domain/logic/SynthesisEngineTest.kt | 526 | 10 | 0 | PURE | SynthesisEngine | KEEP | P1 | #24, #25 | Committed/likely, goals, risk, ForecastInput quality |
| 27 | test/…/domain/model/RecurringPatternModelTest.kt | 27 | 2 | 0 | PURE | RecurrenceFrequency | KEEP | P3 | — | Frequency semantics contract, not data-class tautology |
| 28 | test/…/domain/model/dashboard/DashboardExpenseMapperTest.kt | 125 | 4 | 0 | PURE | DashboardExpense.toTransactionSummary | STRENGTHEN | P2 | — | Half the mapping re-implemented as test fixture |
| 29 | test/…/domain/naturallanguage/NaturalLanguageSearchEngineDefaultWindowBoundaryTest.kt | 135 | 3 | 0 | MOCKED | NaturalLanguageSearchEngine | KEEP | P1 | — | Independent java.time oracle; single keyset call |
| 30 | test/…/domain/naturallanguage/NaturalLanguageSearchEngineVoiceInputTest.kt | 193 | 5 | 0 | PURE | NaturalLanguageSearchEngine | KEEP | P3 | — | Fakes not mocks; voice callbacks; location flag |
| 31 | test/…/domain/negotiation/NegotiationEngineTest.kt | 1346 | 46 | 0 | MOCKED | SmartBillNegotiationEngine | KEEP | P1 | CancellationSafetyArchitectureGuardTest | Grown 3→46 tests; barrier/rollback/conversion coverage |

## Findings (noteworthy files only)

### test/…/domain/intelligence/ml/ExpenseCategoryClassifierTest.kt — STRENGTHEN (LEDGER F-04 + F-17)
- Ledger records 7 measured failures: "multiple DataStores active for the same file" + JSONException.
- Hypothesis 1 (DataStore collision): three tests keep two live classifier instances over the same `filesDir` (lines 143–163, 166–190 create `classifier1` + `classifier2`; line 247–270 creates one after writing the file by hand). If `ExpenseCategoryClassifier` scopes its persistence in a DataStore keyed to the directory, a second live instance on the same file throws `IllegalStateException: multiple DataStores active for the same file`. TemporaryFolder gives per-test isolation, so the collision is intra-test, not cross-test.
- Hypothesis 2 (JSONException): `atRestEncryptionService = mockk(relaxed = true)` (lines 35) — a relaxed mock returns `""` for decrypt, and `JSONObject("")` throws; reload assertions reading the model file (lines 71–75, 88–93) would fail/throw.
- Fix: one live instance per test (close/scope the first before creating the second) and a faithful fake (or real) encryption service instead of `relaxed`.
- Content itself is high value: awaited disk write, durable-save interval boundary, restart reload consistency, plain-JSON backward compatibility. Do not delete; repair isolation.

### test/…/domain/intelligence/ml/MerchantNormalizerStressTest.kt + MerchantNormalizerTest.kt — MERGE
- "Stress" is a misnomer: 12 fast mocked unit tests, only one concurrency test (lines 151–164). Prior audit's "Move to Nightly — heavy ML stress" is OVERTURNED; PR-CI safe after merge.
- DUP with MerchantNormalizerTest: empty-name→Unknown (stress:40 vs unit:49), user-defined alias (stress:69 vs unit:34), fuzzy match (stress:136 vs unit:90). Unique in stress file: 200-char truncation (54), ALIAS_MATCH vs USER_DEFINED distinction (83), EXACT (97), autoCreate insert/no-insert verify (108/123), Greek input (167), cleanMerchantName delegation (181). Unique in unit file: linkAliasToCanonical routing (56–87), fuzzy best-rank verified-beats-unverified (90).
- 🔴 CRITICAL-engine depth check (guidance): neither file pins the `searchKey`/merchantKey derivation consumed by 8 downstream pipelines — the Greek test only asserts `searchKey.isNotEmpty()` (177); alias-lookup keys appear only as stub parameters. No property-based or golden coverage exists. Depth = unit + one concurrency test only.
- Action: merge into `MerchantNormalizerTest` (survivor), carry the union (~12 tests), add explicit `searchKey` derivation assertions, rename/delete the stress file.

### test/…/domain/location/AreaSpendingEngineStressTest.kt + TravelDetectionEngineStressTest.kt — MERGE (deprecated path)
- Both test `compute(...)`, which is `@Deprecated` in production (AreaSpendingEngine.kt:86–89, TravelDetectionEngine.kt:105–107) and has zero main-source callers (`AnalyticsViewModel.kt:689` uses `computeNormalized`). Prior KEEP verdicts OVERTURNED.
- TravelDetection stress file holds the only coverage for trip gap-separation (>3 days, line 58) and out-of-order determinism (line 68) — carry both into `TravelDetectionEngineNormalizedTest` before deleting. Normalized file additionally covers null-normalizedAmount exclusion.
- AreaSpending normalized file already covers the deprecated file's four behaviors (grouping, no-location skip analog, sort, centroid/averaging) — straight delete once `compute()` is removed. `DeprecatedApiArchitectureGuardTest` (architecture batch) tracks the deprecated surface.

### test/…/domain/logic/SplitCalculatorGoldenTest.kt — MERGE (prior KEEP overturned)
- All 4 tests are verbatim duplicates of SplitCalculatorTest tests 1–4 (same names, same expected values: 100÷3 remainder to first, 100÷7, 33.33/33.33/33.34, tie-break). Zero marginal coverage. Keep the golden intent by labelling inside SplitCalculatorTest, or make Golden the survivor and move validation/settlement tests — either way one file must go.

### test/…/domain/logic/SynthesisEngineStressTest.kt — NIGHTLY (prior MOVE_TO_NIGHTLY confirmed)
- 56 tests, 1710 LOC, no @Ignore → currently in PR CI. Dominant assertion is `assertNotNull(forecast)` "should handle gracefully" (Sections 1–5, 8) — exactly the no-crash pattern MASTER_TESTING_STRATEGY deprecates; real assertions exist in Sections 9–12 (risk matrix, isToday/day counts, confidence bounds) and Section 14 regressions.
- `regression - discretionary pool formula` (line 1629) computes expected 165 in comments but only asserts `>= 0` — missed assertion; `maximum chaos` (1654) is unseeded-random. Move to nightly, then convert no-crash tests to value assertions.

### test/…/domain/negotiation/NegotiationEngineTest.kt — KEEP (prior P2/3-tests picture stale)
- Now 46 tests (prior audit recorded 3): write-barrier checked before any read (1031–1054, verifies no DAO touch when blocked), transaction rollback on price-history failure (577), monthly↔billing-cycle conversion for ANNUAL/WEEKLY/BIWEEKLY (273–386), finite/negative validation (820–955), CancellationException rethrow from both entry points (984, 1001), currency normalization (1078), invalid-subscription/quote skipping PR2–PR5 (1112–1345), Greek provider matching (632–741).
- Weak spot: `provider failure handled gracefully` (72) passes even if no exception is thrown (no `fail()` on the happy path) — convert to assertFailsWith. FRAGILE: `database.withTransaction` coAnswers coupling to the Room extension will break on transaction-harness refactors.

### test/…/domain/model/dashboard/DashboardExpenseMapperTest.kt — STRENGTHEN
- `Expense → DashboardExpense` is re-implemented inside the test as `toDashboardExpenseFixture()` (102–124); only `DashboardExpense.toTransactionSummary()` is production code under test. If a production Expense→DashboardExpense mapper exists, assert on it; effectiveAmount-preservation (58–80) is the valuable part — keep.

### Minor strengthens
- TransactionClassifierTest (4): `destroy` test swallows all exceptions (120–125), `cleanup` (130) and `retrainFromCorrections` (97) have no assertions; also leaks a temp dir per run (`createTempDir`, line 26). Value lives in the stats-asserting background-cycle tests.
- CustomSplitParserTest (18): test named "accepts custom percent split at PERCENT_TOLERANCE boundary 0_1" (63) asserts `Invalid` — name contradicts body (3×33.3 = 99.9 is outside tolerance); rename or fix expectation.
- InvestmentTrackerTest (9): `getPortfolioAllocation` test (225) covers only the empty case (comment admits structural check); 9-dependency relaxed-mock constructor is FRAGILE to DI changes.

## Area gaps (what is NOT tested in this area)

- MerchantNormalizer (🔴 CRITICAL, 8 pipelines): no test asserts the derived `searchKey`/merchantKey value (only non-emptiness); no property tests; no golden; no test that normalization matches the `MerchantKeyGenerator` keys the location/backfill paths rely on (related drift is ledger F-20, batch 12).
- ExpenseCategoryClassifier: no test for corrupted/truncated model file recovery, and none for encryption round-trip with a real (non-mock) at-rest service.
- LocationInsightsEngine + SpendingHeatmapEngine: `computeNormalized(LocatedMoneyExpense)` overloads (the multi-currency-safe paths) have no tests anywhere; only raw-amount `compute()` is covered.
- TransactionClassifier: no test that model save/load round-trips content (persistence tested only for ExpenseCategoryClassifier); no CancellationException-specific test here (guard test covers it statically).
- SynthesisEngine: documented-in-KDoc gaps remain — `calculateBlockPartyData` dailySpending grid mapping path, legacy fallback when merged patterns unavailable, unconfirmed-pattern exclusion from committed sum.
- NaturalLanguageSearchEngine: engine-side query parsing (amount/date/merchant extraction into `QueryInterpretation`) has no direct test in domain/naturallanguage; coverage is indirect via `InterpretFinancialQueryUseCaseTest` / AI provider tests (batch 15 territory).
- SmartBillNegotiationEngine: negotiation scoring logic itself is acknowledged (header, line 26) as covered "by integration tests" — none found in this batch; savings-history aggregation over outcomes untested.
- LocationResolver: resolver's own KDoc lists GPS-bias-with-corrections interplay, multi-merchant cluster match, cache eviction, and geocoder error paths beyond RateLimited as untested.

## Rollup

- Verdicts: KEEP 22 · STRENGTHEN 3 · MERGE 4 · REWRITE 1 · DELETE 0 · NIGHTLY 1 · UNKNOWN 0 (31 files, 341 tests, 0 @Ignore)
- P0 count: 2 (SplitCalculatorTest, SynthesisEngineBlockPartyPaidExclusionTest)
- DUP pairs (4): MerchantNormalizerTest ↔ MerchantNormalizerStressTest; SplitCalculatorGoldenTest ↔ SplitCalculatorTest; AreaSpendingEngineStressTest ↔ AreaSpendingEngineNormalizedTest; TravelDetectionEngineStressTest ↔ TravelDetectionEngineNormalizedTest
- FRAGILE count: 2 (InvestmentTrackerTest 9-dep relaxed ctor; NegotiationEngineTest withTransaction coAnswers coupling)
- Prior-audit verdicts overturned: 4 (MerchantNormalizerStressTest Nightly→merge/rename; AreaSpending + Travel stress KEEP→merge-on-deprecated; SplitCalculatorGoldenTest KEEP→MERGE). Prior DELETE #14 TravelDetectionEngineTest confirmed already executed. Prior REWRITE SpendingHeatmapEngineStressTest confirmed. Prior MOVE_TO_NIGHTLY SynthesisEngineStressTest confirmed.
- Ledger: F-04/F-17 (7 failures) owned here — isolation hypothesis and fix path documented above; no other measured failure family lands in this batch.
