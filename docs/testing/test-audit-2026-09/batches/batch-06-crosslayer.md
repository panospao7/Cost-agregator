# Batch 06 — crosslayer (consistency / contracts / diagnostics / verification)

Scope: cross-cutting consistency guards (Segments 3/6/7/16/22/24/27/32), static contract tests (9/18/28), durable diagnostics (18/29), verification/golden analytics (1/2/8/10/22/24) · Files: 30 · LOC: 7,753

Reviewer notes:
- All 6 contracts/ tests are SRCTEXT (read production source, assert on strings). Per MASTER_TESTING_STRATEGY §Phase 4 they were a deliberate 2026-05 CI-guard decision. Re-verified 2026-09: every referenced production path still exists (NotificationCaptureService.kt, RawContentSanitizer.kt, RecurringRuleLifecycleCoordinator.kt, all 9 CancellationPropagation entry files) — they compile against current paths. But Segment 39 scripts + architecture/ guards now supersede 3 of them (see findings); PrivacyStorage/SideEffect/RecurringDeactivate have no script equivalent.
- `consistency/ConcurrencyStateRaceTest.kt` (prior DELETE, "tests stdlib") is GONE from app/src — prior verdict already executed; case closed.
- F-14 (DatabaseWriteBarrier typed-exception contract, ExpenseStoreTest/DatabaseBarrierTest/ExportReadBarrierTest): none of those files live in this batch; the only barrier-related file here is LifecycleBarrierContractTest (SRCTEXT).
- Big verification files re-implement ExpenseDao SQL aggregation in MockK answers — they prove cross-engine parity GIVEN a correct DAO; SQL itself is untested here (complemented by DB-backed dao/ tests elsewhere).

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 16 | 7 | 5 | 1 | 1 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/consistency/ConstantsConsistencyTest.kt | 120 | 2 | 0 | PURE | SpendingPaceCalculator, SettlementCalculator, BudgetForecastingEngine | KEEP | P2 | InsightsEngineDeepTest | Reflection on constant names; mildly FRAGILE |
| 2 | test/…/consistency/CrossParserConsistencyTest.kt | 154 | 8 | 0 | MOCKED | Revolut/GreekBank/Generic parsers, MerchantKeyGenerator | KEEP | P1 | MerchantKeyCrossConsumer | Real parse→key assertions |
| 3 | test/…/consistency/CurrencyNormalizerConsistencyTest.kt | 160 | 10 | 0 | MOCKED | CurrencyNormalizer, parsers | KEEP | P1 | CrossParserConsistency | Symbol/code normalization verified |
| 4 | test/…/consistency/DedupeKeyProducerConsistencyTest.kt | 203 | 12 | 0 | PURE | DuplicateDetectionPolicy | MERGE | P2 | DuplicateDetectionPolicyDedupeKeyTest | "All producers" tautology — same fn 6×; see findings |
| 5 | test/…/consistency/DuplicateLogicConsistencyIntegrationTest.kt | 475 | 21 | 0 | MOCKED | CrossSourceDeduplication | KEEP | P0 | DetectDuplicateExpenseUseCaseTest | ISSUE-5 regression, real outputs; 2 stress tests padding |
| 6 | test/…/consistency/EmptyZeroNullResilienceTest.kt | 306 | 2 | 0 | MOCKED | Budget/Pace/Health/Converter/Settlement/Split | STRENGTHEN | P1 | CrossGroupIntegration #9 | Test 2 (StateFlow take 1) tautological; FRAGILE ctor |
| 7 | test/…/consistency/FinancialArithmeticPrecisionTest.kt | 136 | 4 | 0 | PURE | SettlementCalculator, SharedExpenseManager cents math | KEEP | P1 | SettlementCalculatorTest | Money boundary cases; reflection FRAGILE |
| 8 | test/…/consistency/HaversineConsistencyTest.kt | 109 | 5 | 0 | PURE | GeoUtils | KEEP | P2 | — | Formula parity + null-safe variant |
| 9 | test/…/consistency/MerchantKeyConsistencyTest.kt | 72 | 3 | 0 | PURE | MerchantKeyGenerator, MerchantCleaner, MerchantRulesRepository | KEEP | P2 | MerchantKeyCrossConsumer | Parser-vs-rules key parity |
| 10 | test/…/consistency/MerchantKeyCrossConsumerConsistencyTest.kt | 145 | 10 | 0 | PURE | MerchantKeyGenerator, Expense.generateDedupeKey | MERGE | P3 | DedupeKeyTest, SharedUtility, CrossParser | Greek/Latin+apostrophe+dedupe-key dups; keep dedupeKey-containment in DedupeKeyTest |
| 11 | test/…/consistency/SharedUtilityConsistencyTest.kt | 233 | 15 | 0 | PURE | AmountUtils, AmountExtractionUtils, CommonPatterns | STRENGTHEN | P2 | MerchantKeyCrossConsumer | Amount agreement unique; merchant-key section dup |
| 12 | test/…/consistency/TemporalConsistencyTest.kt | 190 | 4 | 0 | PURE | BudgetCalculator, SpendingPaceCalculator, TimePeriodUtils | KEEP | P0 | TimePeriodUtilsTest family | DST + leap-year, FakeTimeProvider, TZ pinned/restored |
| 13 | test/…/consistency/TimePeriodAnalyticsAlignmentTest.kt | 72 | 6 | 0 | PURE | TimePeriodUtils | DELETE | P3 | TimePeriodUtilsTest family | Self-comparison tautology; deprecated getLastNDaysRange |
| 14 | test/…/contracts/CancellationPropagationContractTest.kt | 138 | 2 | 0 | SRCTEXT | 12 catch sites (budget/receipt/recurring/forecast) | MERGE | P2 | CancellationSafetyArchitectureGuardTest | Superseded; test 2 tautological; see findings |
| 15 | test/…/contracts/LifecycleBarrierContractTest.kt | 69 | 2 | 0 | SRCTEXT | DatabaseWriteBarrier usage | MERGE | P2 | WriteBarrierArchitectureGuardTest | Substring-anywhere check weaker than guard; see findings |
| 16 | test/…/contracts/MoneyContractTest.kt | 58 | 1 | 0 | SRCTEXT | effectiveAmount summation sites | MERGE | P2 | check_raw_money_aggregates.kts | `.currency`-anywhere escape hatch too lax; see findings |
| 17 | test/…/contracts/PrivacyStorageContractTest.kt | 67 | 3 | 0 | SRCTEXT | RawStorageMode, RawContentSanitizer | STRENGTHEN | P1 | — | NOT covered by any script; keep as CI guard; test 1 weak |
| 18 | test/…/contracts/RecurringDeactivateContractTest.kt | 90 | 5 | 0 | SRCTEXT | RecurringRuleLifecycleCoordinator.deactivateRule | KEEP | P1 | RecurringArchitectureGuardTest | Token checks only; no script covers this invariant |
| 19 | test/…/contracts/SideEffectContractTest.kt | 75 | 1 | 0 | SRCTEXT | TransactionSideEffectDispatcher call sites | KEEP | P1 | — | Real brace-matching; unique, no script equivalent |
| 20 | test/…/diagnostics/DDL512RegressionTest.kt | 512 | 24 | 0 | PURE | EventMetadataSanitizer; (test doubles) | REWRITE | P2 | DurableDiagnostics* family | Journal/file tests test the test; TrackingHandle self-tests; see findings |
| 21 | test/…/diagnostics/DurableDiagnosticsA8RegressionTest.kt | 242 | 15 | 0 | PURE | RestoreJournal, EventMetadataSanitizer | STRENGTHEN | P1 | Acceptance/Regression/Golden | Journal path-stripping test is gold; tautology blocks |
| 22 | test/…/diagnostics/DurableDiagnosticsAcceptanceTest.kt | 285 | 18 | 0 | PURE | EventMetadataSanitizer, SideEffectDiagnosticRecorder | STRENGTHEN | P1 | Regression (verbatim dups) | Sanitizer/IBAN/path tests real; 3 data-class round-trips |
| 23 | test/…/diagnostics/DurableDiagnosticsRegressionTest.kt | 223 | 15 | 0 | PURE | EventMetadataSanitizer, DiagnosticEvent | STRENGTHEN | P2 | Acceptance | Contains literal `assertTrue(true)` tests; dups #22 |
| 24 | test/…/diagnostics/GlobalDurableDiagnosticsGoldenTest.kt | 222 | 19 | 0 | PURE | EventMetadataSanitizer, CorrelationIds, taxonomies | KEEP | P2 | Acceptance/Regression | Least tautology of the four; enum taxonomy pins vocab |
| 25 | test/…/verification/CarbonFootprintTest.kt | 139 | 7 | 0 | MOCKED | CarbonFootprintCalculator | KEEP | P2 | CrossGroup #3 | A.9 uncapped-query regression; effectiveAmount check |
| 26 | test/…/verification/CrossGroupIntegrationTest.kt | 845 | 9 | 0 | MOCKED | Insights/Advanced/Totals/Carbon/Lifestyle/Shared | KEEP | P1 | CrossSourceVerification | Overturn prior NIGHTLY: deterministic mocks; FRAGILE ctor |
| 27 | test/…/verification/CrossSourceVerificationTest.kt | 414 | 6 | 0 | MOCKED | Insights/Advanced/Dashboard/Totals/Pace | KEEP | P0 | CrossGroupIntegration | Canonical 280% pace formula; cross-engine parity |
| 28 | test/…/verification/GoldenMasterVerificationTest.kt | 1023 | 22 | 0 | MOCKED | All analytics engines + SmartSavings | STRENGTHEN | P0 | CrossSourceVerification | ~6 tautological/dup tests inside; golden constants gold; FRAGILE |
| 29 | test/…/verification/LifestyleAnalysisTest.kt | 169 | 7 | 0 | MOCKED | LifestyleInflationDetector | KEEP | P2 | CrossGroup #4 | Elasticity/bucket math asserted numerically |
| 30 | test/…/verification/SharedExpenseTest.kt | 344 | 15 | 0 | MOCKED | SharedExpenseManager, SettlementCalculator | KEEP | P1 | SharedExpenseManagerTest, SettlementCalculatorTest | Conservation + min-transfer invariants; NaN rejection |

## Findings (noteworthy files only)

### test/java/com/yourname/expensetracker/consistency/DedupeKeyProducerConsistencyTest.kt
- CLAIMS to verify "all producers use the same strategy" but every per-producer helper (`approveReviewKey`, `notificationPipelineKey`, …) calls the identical `DuplicateDetectionPolicy.generateDedupeKeyWithType(...)` — the 6-way `assertEquals` blocks (lines 61-94) compare a function to itself.
- Real value: PURCHASE≠DEPOSIT≠TRANSFER key separation, currency sensitivity — but currency/UNKNOWN/suffix cases already live in `domain/intelligence/DuplicateDetectionPolicyDedupeKeyTest.kt` (6 tests).
- Nothing verifies actual call sites use the type-aware policy (the ISSUE-8 contract is simulated, not enforced).
- Action: MERGE type-differentiation asserts into DuplicateDetectionPolicyDedupeKeyTest; enforce producer call sites via a Segment 39 script guard (none exists today — see gaps).

### test/java/com/yourname/expensetracker/contracts/CancellationPropagationContractTest.kt
- SRCTEXT with brittle heuristics: walks back from a log-marker string to the nearest "catch" keyword; if marker sits in a try body it silently `continue`s (line 87) — can pass vacuously.
- Strictly superseded by `architecture/CancellationSafetyArchitectureGuardTest.kt` (whole-repo broad-catch scan, shrink-only allowlist, same U-PR1 contract) and `scripts/verify_cancellation_boundaries.py` (G-CANCEL-01/02/03, CI, baseline+allowlist).
- Test 2 (`covers at least 10 critical entry points`, line 107) asserts on the test's own list size — pure tautology.
- Action: MERGE into the architecture guard + Python guard; optionally port the 12 named entry points as an explicit allowlist-negative list there.

### test/java/com/yourname/expensetracker/contracts/LifecycleBarrierContractTest.kt
- Only checks that any file containing "withTransaction" mentions "writeBarrier" ANYWHERE (a comment mention passes).
- `architecture/WriteBarrierArchitectureGuardTest.kt` is far stronger (every DAO write caller must inject DatabaseWriteBarrier or be on a documented exempt list) and `scripts/verify_db_access_boundaries.py` validates `writeBarrier.checkWritesAllowed/runWrite` call forms.
- Action: MERGE (delete; guards own the invariant).

### test/java/com/yourname/expensetracker/contracts/MoneyContractTest.kt
- Pattern is decent (`sumOf { effectiveAmount }`) but the safety escape hatch `content.contains(".currency")` (line 47) lets almost any repository file pass.
- `scripts/guards/check_raw_money_aggregates.kts` flags `sumOf { it.effectiveAmount/amount/normalizedAmount/…Price }` with an allowlist; `scripts/verify_money_boundaries.py` adds G-MONEY-10..21.
- Action: MERGE into script guards.

### test/java/com/yourname/expensetracker/contracts/PrivacyStorageContractTest.kt + RecurringDeactivateContractTest.kt + SideEffectContractTest.kt
- No scripts/ guard covers DO_NOT_STORE branch presence (grep of verify_privacy_boundaries.py for DO_NOT_STORE/RawStorageMode: 0 hits), deactivateRule cleanup, or dispatch-inside-withTransaction. These three remain the only automated holders of their invariants — keep as CI guards (2026-05 decision still valid for them).
- PrivacyStorage test 1 (exhaustive-when heuristic, lines 16-40) is weak (only fires when an if/else chain exists with NO when block; Kotlin exhaustiveness already helps). All read-paths verified to exist.

### test/java/com/yourname/expensetracker/diagnostics/DDL512RegressionTest.kt
- DDL-512-01/02/03/10 "journal ordering" tests (lines 44-116, 352-361) never touch a production class — they write/rename/read JSON with the test's own helpers, proving java.io.File + org.json semantics.
- DDL-F876-04/DDL-C67-01 block (lines 176-258) asserts the terminal-once policy of `TrackingHandle` — a test double that itself IMPLEMENTS the policy at lines 399-440. False confidence.
- DDL-512-04/05/06/11/F876-11 are reflection/shape assertions the compiler largely guarantees.
- Real value: 6 EventMetadataSanitizer redaction tests (lines 156-172, 317-348) — privacy fail-closed. Action: REWRITE — keep sanitizer tests, delete double/stdlib tests, test real `SafeSinkOperationRunHandle` + `RestoreJournal` directly.

### test/java/com/yourname/expensetracker/diagnostics/DurableDiagnostics{A8Regression,Acceptance,Regression}Test.kt
- Strong shared core: RestoreJournal.toDiagnosticsJson strips `_sourceBackupPath`/paths while toJson keeps them (A8 lines 98-112); IBAN/long-digit/path sanitization; `putHashed` provider-TXN hashing; isDangerousKey vocab.
- Negative value: `DurableDiagnosticsRegressionTest` lines 38-51 contain two literal `assertTrue(true)` "documented contract" tests; data-class construct→read-back "tests" (correlationId, DiagnosticFailureSummary, RestoreJournalEvent) in all files; TerminalTrackingHandle/TrackingHandle self-tests.
- Duplication: side-effect terminal tests verbatim in Acceptance (135-176) and Regression (195-222); isDangerousKey lists in 4 files; sanitizeExceptionMessage in Acceptance + Golden; UUID format in Regression + Golden.
- Action: STRENGTHEN each; consolidate sanitizer coverage into one dedicated `EventMetadataSanitizerTest` (does not exist today).

### test/java/com/yourname/expensetracker/verification/GoldenMasterVerificationTest.kt
- Gold: 15+ tests assert exact golden constants (738.49 effective vs 1228.49 raw, 92.72% pace, category map, PARITY/DIVERGENCE/EDGE groups) — the strongest money-semantics file in the batch.
- Dead weight: `PARITY - synthesis projection…` (516) and `VERIFICATION - financial runway…` (627) compute test-local arithmetic without calling production; `DIVERGENCE - Monte Carlo…` (533) calls only its own mock answers; `VERIFICATION - spending threshold…` (721) never calls a threshold calculator; `EDGE CASE - no baseline…` (768) and `DIVERGENCE - trend-adjusted…` (606) duplicate in-file siblings (452, 317).
- FRAGILE: full constructor arg lists for 6 engines (refactor pain).

### test/java/com/yourname/expensetracker/verification/CrossGroupIntegrationTest.kt — verdict change
- Prior audit: MOVE_TO_NIGHTLY. Overturned: no sleeps/loops/large data; deterministic MockK harness; ~9 fast tests. High cross-boundary value (half-open interval enforcement at 4 entry points, line 575).
- Partial DUP: `complete month analysis…` (443) vs CrossSourceVerificationTest `monthly total is consistent…` (135) — same insights==advanced==totals invariant; keep both (different harness breadth) but note.

### consistency/ConcurrencyStateRaceTest.kt (batch-guidance item)
- File does not exist under app/src/test — the prior DELETE verdict was already carried out. No action.

## Area gaps (what is NOT tested in this area)

- No dedicated `EventMetadataSanitizerTest`; sanitizer behavior is scattered across 4 diagnostics files with duplicated key lists — consolidate and add nested-JSON + boundary cases in one place.
- No enforcement that expense-insert producers actually call `DuplicateDetectionPolicy.generateDedupeKeyWithType` (ISSUE-8) — candidate new Segment 39 script guard (dedupe-producer call-site check), mirroring verify_event_writers.py.
- `scripts/verify_privacy_boundaries.py` does not cover RawStorageMode/DO_NOT_STORE persistence behavior; PrivacyStorageContractTest is source-scan only — a behavioral DO_NOT_STORE golden test (expense parses, raw fields null) is the real gap.
- deactivateRule cleanup has no DB-backed behavioral test in this batch (SRCTEXT tokens only here; verify one exists in domain/recurring or add).
- All analytics parity tests mock the DAO with Kotlin re-implementations of the SQL aggregates; the SQL itself is only covered by separate dao/ DB tests — keep both legs.
- GlobalDurableDiagnosticsGoldenTest pins enum vocabularies but no test here pins that worker terminal diagnostics use bounded/controlled reason codes end-to-end (WorkerTerminalDiagnostic* tests exist in other batches).

## Rollup

- Verdicts: KEEP 16 · STRENGTHEN 7 · MERGE 5 · REWRITE 1 · DELETE 1 · NIGHTLY 0 · UNKNOWN 0
- P0 count: 4 (DuplicateLogicConsistencyIntegrationTest, TemporalConsistencyTest, CrossSourceVerificationTest, GoldenMasterVerificationTest)
- DUP pairs: MerchantKeyCrossConsumer↔SharedUtility/CrossParser/DedupeKeyTest; DedupeKeyProducer↔DuplicateDetectionPolicyDedupeKeyTest; CrossGroupIntegration#6↔CrossSourceVerification#1; Acceptance↔Regression (side-effect tests verbatim; sanitizer key lists across 4 files); DDL512 TrackingHandle↔A8 TerminalTrackingHandle; GoldenMaster internal dups (no-baseline ×2, trend-divergence ×2)
- FRAGILE count: 6 (ConstantsConsistency, FinancialArithmeticPrecision, EmptyZeroNullResilience, CrossGroupIntegration, CrossSourceVerification, GoldenMasterVerification)
- P4 (negative value) whole files: none; negative-value blocks inside DDL512RegressionTest, DurableDiagnosticsRegressionTest (assertTrue(true)), CancellationPropagationContractTest (self-count test), GoldenMasterVerificationTest (4 no-production tests)
- Prior-audit overturns: CrossGroupIntegrationTest NIGHTLY→KEEP; TimePeriodAnalyticsAlignmentTest KEEP→DELETE; contracts verdicts revised against Segment 39 guard suite
