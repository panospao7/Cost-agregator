# Batch 23 — domain-util

Scope: Segment 32 "Utilities & Shared Helpers" (`domain/util/`) — TimePeriodUtils (canonical calendar boundary math used by all pipelines), Money/Amount/Currency formatting, MerchantCleaner/MerchantKeyGenerator (merchant identity), StatisticsUtils, StringDistanceUtils, BKTree, CancellationSafe, monotonic time provider, notification ID ranges, TZ-lock fixture · Files: 22 · LOC: 10,069 (~614 real @Test)
Reviewer notes: All files are PURE (no mocks, no Android, no Room) — zero mock-fragility in this batch. The TimePeriodUtils family is the deepest-tested class in the suite: legacy unit (Test 57 + Validation 77), stress (40), and five T4C migration-lock suites (1/2A/2B/2C/2D, 127 tests) that pin the java.time migration to hardcoded UTC instants, dual oracles (legacy Calendar + explicit java.time), the pre-Gregorian cutover seam at 1582-10-15, DST 23h/25h days and 167h/169h weeks, and Long/Int extremes. Prior-audit re-verification: the 2026-05 "bulk stress / prune" flags (#13–18 in TEST_PRUNING_CANDIDATES.md) are OVERTURNED — the *StressTest files are fast behavioral suites with real expected-value assertions, not bulk loops; only TimePeriodUtilsStressTest needs hardening (does not adopt GlobalTimeZoneTestLock). MoneyTest prior "2 ignored" is now 0 @Ignore. Failure ledger: no file in this batch is in F-01…F-21 (F-20 MerchantKeyBackfillWorker concerns the worker, not these unit files). FakeTimeProvider.kt / FakeMonotonicTimeProvider.kt fixtures also live in this dir but are not in the plan (owned by batch 02, which confirmed FakeTimeProvider as canonical).

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 17 | 3 | 1 | 1 | 0 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/domain/util/AmountUtilsStressTest.kt | 595 | 65 | 0 | PURE | AmountUtils | STRENGTHEN | P1 | AmountUtilsTest, SharedUtilityConsistencyTest, CategorizationPipelineIntegrationTest | Real locale/negative/boundary parsing; 1 test leaks Locale on failure (no try/finally); ~3 no-assertion "document behavior" tests |
| 2 | test/…/domain/util/AmountUtilsTest.kt | 58 | 6 | 0 | PURE | AmountUtils | KEEP | P1 | AmountUtilsStressTest (subset) | Core parse/validate cases; duplicated verbatim in #1 regression section |
| 3 | test/…/domain/util/BKTreeTest.kt | 27 | 1 | 0 | PURE | StringBKTree | REWRITE | P3 | (none — search untested anywhere) | Still exactly 1 trivial insert/size/clear test; searchByDistance never directly tested |
| 4 | test/…/domain/util/CancellationSafeTest.kt | 119 | 9 | 0 | PURE | CancellationSafe | KEEP | P1 | CancellationSafetyArchitectureGuardTest | Pins CE-propagation contract behind AGENTS.md worker rule |
| 5 | test/…/domain/util/CurrencyFormatterExportSafetyTest.kt | 209 | 20 | 0 | PURE | CurrencyFormatter | KEEP | P1 | MoneyContractTest | Fail-closed on NaN/Inf, raw-code fallback for invalid currency |
| 6 | test/…/domain/util/GlobalTimeZoneTestLock.kt | 72 | 0 | 0 | FIXTURE | (shared TZ lock) | KEEP | P1 | Used by 11 test files | Process-wide TZ serialization harness; adoption incomplete (see gaps) |
| 7 | test/…/domain/util/MerchantCleanerStressTest.kt | 290 | 35 | 0 | PURE | MerchantCleaner | KEEP | P1 | GenericTransactionParserTest, MerchantKeyConsistencyTest, LocationResolverTest | "stress" name is misleading — real cleaning rules incl. documented-bug regression tests |
| 8 | test/…/domain/util/MerchantKeyGeneratorStressTest.kt | 183 | 29 | 0 | PURE | MerchantKeyGenerator | MERGE | P3 | MerchantKeyGeneratorTest | ~Half duplicate w/ weaker asserts (isNotEmpty); port emoji/null-char/perf cases then delete |
| 9 | test/…/domain/util/MerchantKeyGeneratorTest.kt | 157 | 16 | 0 | PURE | MerchantKeyGenerator | KEEP | P0 | CrossParserConsistencyTest, MerchantKeyConsistencyTest | Canonical merchant identity: transliteration, diphthongs, idempotency |
| 10 | test/…/domain/util/MoneyTest.kt | 418 | 31 | 0 | PURE | Money | KEEP | P0 | TaxCalculationTest, MoneyContractTest | BigDecimal exactness, split-sum invariants, HALF_UP rounding — money core |
| 11 | test/…/domain/util/NotificationIdGeneratorTest.kt | 390 | 36 | 1 | PURE | NotificationIdGenerator | KEEP | P2 | DailyBriefingWorkerTest | Range non-overlap + overflow; 1 reasoned @Ignore (negative IDs) |
| 12 | test/…/domain/util/StatisticsUtilsStressTest.kt | 416 | 35 | 0 | PURE | StatisticsUtils | STRENGTHEN | P2 | (none) | Known-value/sample-vs-population tests solid; 3 tautological "handle gracefully" tests |
| 13 | test/…/domain/util/StringDistanceUtilsStressTest.kt | 198 | 28 | 0 | PURE | StringDistanceUtils | KEEP | P2 | ReceiptTransactionMatcherTest, ReceiptMatchingE2ETest | Exact distances + fuzzy-match semantics; fast |
| 14 | test/…/domain/util/SystemMonotonicTimeProviderTest.kt | 61 | 3 | 0 | PURE | SystemMonotonicTimeProvider, FakeMonotonicTimeProvider | KEEP | P3 | (none) | Correctly asserts only monotonic contract, not platform timing |
| 15 | test/…/domain/util/TimePeriodUtilsStressTest.kt | 824 | 40 | 0 | PURE | TimePeriodUtils | STRENGTHEN | P1 | T4CBatch2A/2B, TimePeriodUtilsTest, ValidationTest | 14x TimeZone.setDefault WITHOUT GlobalTimeZoneTestLock; DST contiguity unique but unlocked |
| 16 | test/…/domain/util/TimePeriodUtilsT4CBatch1Test.kt | 480 | 24 | 0 | PURE | TimePeriodUtils | KEEP | P0 | TimePeriodUtilsTest | Field accessors + month keys vs java.time oracle across 3 zones under TZ lock |
| 17 | test/…/domain/util/TimePeriodUtilsT4CBatch2ATest.kt | 622 | 20 | 0 | PURE | TimePeriodUtils | KEEP | P0 | TimePeriodUtilsStressTest, ValidationTest | Day boundaries: hardcoded instants, DST gap/overlap, Long extremes, cutover seam |
| 18 | test/…/domain/util/TimePeriodUtilsT4CBatch2BTest.kt | 680 | 19 | 0 | PURE | TimePeriodUtils | KEEP | P0 | TimePeriodUtilsTest, TimePeriodUtilsStressTest | Week helpers: 167h/169h DST weeks, year rollover, dual oracles |
| 19 | test/…/domain/util/TimePeriodUtilsT4CBatch2CTest.kt | 1081 | 28 | 0 | PURE | TimePeriodUtils | KEEP | P0 | ValidationTest | Month helpers + parseMonthKeyToRange; exact cutover-boundary matrix, Int extremes |
| 20 | test/…/domain/util/TimePeriodUtilsT4CBatch2DTest.kt | 1306 | 36 | 0 | PURE | TimePeriodUtils | KEEP | P0 | ValidationTest | Year/quarter helpers; southern-hemisphere DST year, Int-extreme year overload |
| 21 | test/…/domain/util/TimePeriodUtilsTest.kt | 873 | 57 | 0 | PURE | TimePeriodUtils | KEEP | P0 | ValidationTest, T4C batches, TimePeriodAnalyticsAlignmentTest | Half-open contract, Monday weeks, week-key rollover, ISO vs app calendar |
| 22 | test/…/domain/util/TimePeriodUtilsValidationTest.kt | 1010 | 77 | 0 | PURE | TimePeriodUtils | KEEP | P0 | TimePeriodUtilsTest (partial DUP) | Full expected-value validation of every helper; substantial overlap with #21 |

## Findings (noteworthy files only)

### test/java/com/yourname/expensetracker/domain/util/TimePeriodUtilsT4CBatch2ATest.kt (and 2B/2C/2D/1)
- Exemplary migration-lock suites for the java.time rewrite of Segment 32's canonical boundary math (used by every analytics/dashboard/cashflow pipeline).
- Every expectation is a hardcoded UTC instant or computed by an independent oracle (legacy `Calendar` or explicit `ZonedDateTime.of`); the suites explicitly refuse to use the production helper as its own oracle (2A:83-87).
- Locks the intentional pre-Gregorian compatibility seam at 1582-10-15T00:00Z including ms-adjacent boundary pairs and contiguity (2C:588-745), DST 23h/25h days and 167h/169h weeks with `assertNotEquals(DAY_IN_MILLIS…)` guards (2A:210, 2B:330), documented `ArithmeticException` at Long.MAX (2A:432) and lenient Int-extreme wrap (2C:1000-1058).
- All TZ mutation goes through `GlobalTimeZoneTestLock.withLock` (2A:67-77). No action needed; protect in CI.

### test/java/com/yourname/expensetracker/domain/util/TimePeriodUtilsStressTest.kt
- Real DST/timezone value: US/EU spring-forward week contiguity (721-761), multi-zone getStartOfDay sweep (133-164), 12-month end-of-month loop, half-open fuzz (785-823) — all real assertions, fast (~ms), so prior MOVE_TO_NIGHTLY/prune flags are overturned.
- Hazard: 14 `TimeZone.setDefault` calls with try/finally restore but NOT under `GlobalTimeZoneTestLock` (107, 134, 172, 331, 489, 724, 746) — under parallel Gradle test execution other classes reading the default TZ can observe a foreign zone; the T4C suites in the same package already model the fix.
- `stress - week starting on Sunday` (307-327) mutates `firstDayOfWeek` on a throwaway `Calendar` instance — no-op setup; test passes for the right reason (production hardcodes MONDAY) but the mutation is dead code.
- Action: wrap TZ mutations in GlobalTimeZoneTestLock; then KEEP in PR CI (not NIGHTLY).

### test/java/com/yourname/expensetracker/domain/util/AmountUtilsStressTest.kt
- 65 real parsing tests: locale matrix (US/DE/EL/FR, 23-78), unicode minus/parens negatives, currency symbols, 1M boundary, fuzz-no-crash loops. Supersedes AmountUtilsTest (whose 6 tests are copied verbatim in the regression section, 485-513).
- Isolation bug: `regression - formatAmount is locale-stable across locales` (520-534) mutates `Locale.setDefault(Locale.GERMANY)` with NO try/finally — an assertion failure leaks German locale JVM-wide to concurrently running tests.
- Low-value tests that assert nothing or near-nothing: `stress - ambiguous thousands with only dots` (104-110, only assertNotNull), `stress - scientific notation` (417-421, zero assertions).
- Money rule check: parsing uses Double with 0.001 deltas — acceptable for a parser (domain math stays in BigDecimal Money); no float drift found in assertions.

### test/java/com/yourname/expensetracker/domain/util/MoneyTest.kt
- 31 tests, 0 @Ignore (prior audit's "2 ignored" is stale). Classic 0.1+0.2 exactness (66-78), split-sum-equals-total invariant (133-145), remainder-adjusted 33.33/33.33/33.34 split (160-185), HALF_UP boundaries (362-381), VAT round-trip within 0.01 (384-404).
- Correctly compares underlying `BigDecimal` (`sum.amount`) to dodge value-class boxing artifacts (115, 144, 184). KEEP P0 as money-core protection.

### test/java/com/yourname/expensetracker/domain/util/BKTreeTest.kt
- Re-verified: still exactly 1 test covering isEmpty/size/insert/clear (BKTreeTest.kt:10-26). No test anywhere exercises `searchByDistance`/fuzzy search on the tree directly (grep: only reference in tests is this file); coverage is indirect via ReceiptTransactionMatcher/CategorizationEngine tests. Prior REWRITE verdict confirmed: add 6-8 search-semantics tests (order independence, threshold edges, duplicates, Greek/Latin keys) or fold into a matcher-level test and delete.

### test/java/com/yourname/expensetracker/domain/util/StatisticsUtilsStressTest.kt
- Strong core: known stddev values (256-270), sample-vs-population N-1 pin (273-281), shift-invariance/scale-linearity (228-249), analytic progression formula (335-343).
- Tautologies to remove: `stress - max double values` (147: `stdDev >= 0.0 || stdDev == -1.0` — always true), `stress - infinity values` (178-179: `isFinite || isNaN || isInfinite` — true for every Double), `stress - NaN values` (193: `isNaN || >= 0.0`). Decide real NaN/Inf policy and assert it.

### test/java/com/yourname/expensetracker/domain/util/MerchantKeyGeneratorStressTest.kt
- ~15 of 29 tests duplicate MerchantKeyGeneratorTest with weaker assertions (Greek inputs asserted only `isNotEmpty`: 36-66, 96-98, 135-137, 165-173).
- Unique value worth porting before merge: emoji stripping incl. ZWJ sequences (124-130), null char (159-161), tab/newline (144-146), 10k-char idempotence/perf (149-157), determinism (176-181). Merge into MerchantKeyGeneratorTest, then delete.

## Area gaps (what is NOT tested in this area)

- No direct tests exist in this dir (or in the batch plan) for: DateFormatterUtils (13 methods), CommonPatterns, CurrencyNormalizer (only indirect consistency tests), SystemTimeProvider, TimeBoundaryTicker, GeoUtils, AppConstants, AmountExtractionUtils (covered only by SharedUtilityConsistencyTest), MoneyDisplay/rounding-format parity between CurrencyFormatter locales.
- StringBKTree.searchByDistance: zero direct coverage (see BKTreeTest finding) — fuzzy-merchant matching rests entirely on consumer-level tests.
- TZ-lock adoption is incomplete: 11 test files use GlobalTimeZoneTestLock, but TimePeriodUtilsStressTest and TimePeriodUtilsTest (`contract - getStartOfWeek is locale-independent`, 488-507) still mutate the default zone unlocked, and ~8 files outside this batch call TimeZone.setDefault without it.
- AmountUtilsStressTest locale tests mutate default Locale with no equivalent of GlobalTimeZoneTestLock; one test leaks locale on failure.
- TimePeriodUtils `getLastNDaysRange`/`getLastNCalendarDaysRange`/`getTrailingElapsedRange`/`getDayIndexForSparkline`/`toPeriodRange` (Phase-2 helpers per CODEBASE_SEGMENTS 641) have no dedicated DST/zone-matrix tests in this batch (T4C suites cover day/week/month/year/quarter but not these rolling windows); TimePeriodAlignmentTest (batch 02) partially covers the alignment side.
- Overlap note: TimePeriodUtilsTest vs TimePeriodUtilsValidationTest are partial DUP (isInRange/addMonths/quarter blocks near-identical) — candidates to consolidate, but both also hold unique content (week-key/ISO helpers vs rolling-window/accessor validation), so consolidation is optional, not urgent.

## Rollup

- Verdicts: KEEP 17, STRENGTHEN 3, MERGE 1, REWRITE 1, DELETE 0, NIGHTLY 0, UNKNOWN 0 (22 files)
- P0 count: 9 (MoneyTest, MerchantKeyGeneratorTest, TimePeriodUtilsTest, TimePeriodUtilsValidationTest, T4C Batch1/2A/2B/2C/2D)
- DUP pairs found: 1 clean (MerchantKeyGeneratorStressTest → MerchantKeyGeneratorTest), 1 partial (TimePeriodUtilsTest ↔ TimePeriodUtilsValidationTest), 1 containment (AmountUtilsTest ⊂ AmountUtilsStressTest regression section)
- FRAGILE count: 0 (all PURE; no mock/constructor coupling). Prior-audit overturned: 6 "stress/prune" flags re-verified as fast real-assertion suites; TimePeriodUtilsStressTest DST-dependence is real but fixable via the already-present GlobalTimeZoneTestLock.
