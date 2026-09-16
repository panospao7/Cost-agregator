# Batch 24 — domain/widget, domain/workers, misc root/currency/guard/guards/receiver

Scope: Segment 12 (Startup & Background Runtime — worker guard/logger/lease/scheduler/policy), Segment 10 (widget style), Segment 7 (recurring detection), Segment 16 (canonical multi-currency golden), Segment 39 (guard-config contracts), Segment 8/32 shared test fixtures, receiver dispatch · Files: 23 · LOC: 9,767 (~338 @Test)

Reviewer notes: Workers are STRICT P0 per AGENTS.md. Key structural fact: domain/workers tests drive the **REAL** `WorkerExecutionGuard`, `WorkerRunLoggerImpl`, `WorkerLeaseRegistryImpl`, `WorkerSpecScheduler`, `PrivacyRuntimeWorkerPolicy` with only their dependencies mocked — no batch-12-style stubbed re-implementation of guard classification exists here. All three AGENTS.md targeted commands resolve: `*WorkerExecutionGuard*` → WorkerExecutionGuardTest.kt, `*WorkerRunLogger*` → WorkerRunLoggerTest.kt, `*WorkerTerminalDiagnostic*` → FileWorkerTerminalDiagnosticSinkTest.kt (pattern matches). Ledger: F-18 (MaintenanceOperationRunnerTest) and F-20 (MerchantKeyBackfillWorkerTest) are NOT in this batch — confirmed absent; no batch file appears in any F-family. Prior 2026-05 audit verdicts re-verified: GuardSeededViolationTest KEEP→overturned to DELETE; receiver "stress" tests "environment-dependent"→overturned to deterministic KEEP. A shared guarded-worker test fixture does NOT exist (see Findings).

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 19 | 2 | 0 | 1 | 1 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/java/…/domain/widget/WidgetStyleRepositoryTest.kt | 73 | 2 | 0 | MOCKED | WidgetStyleRepository (default toggleWidgetStyle) | REWRITE | P2 | HomeViewModelStressTest (touching) | TAUTOLOGY: fakes re-declare prod logic |
| 2 | test/java/…/domain/workers/FileWorkerTerminalDiagnosticSinkTest.kt | 652 | 22 | 0 | MOCKED | FileWorkerTerminalDiagnosticSink, WorkerTerminalDiagnosticReader | KEEP | P0 | — | real file IO; privacy-negative asserts |
| 3 | test/java/…/domain/workers/P9RemainingWorkerFixesTest.kt | 272 | 12 | 0 | MOCKED | WorkerSpec, WorkerRunLoggerImpl, WorkerExecutionGuard | STRENGTHEN | P1 | 12, 8, 13 | 2 stdlib `maxOf` tautologies; dup spec asserts |
| 4 | test/java/…/domain/workers/PrivacyRuntimeWorkerPolicyTest.kt | 144 | 9 | 0 | ROBOLECTRIC | PrivacyRuntimeWorkerPolicy | KEEP | P0 | — | pins data_retention cancel-exemption |
| 5 | test/java/…/domain/workers/WorkerBarrierIntegrationTest.kt | 473 | 23 | 0 | MOCKED | WorkerExecutionGuard + barriers | KEEP | P0 | 6 (partial DUP) | 5 restore modes; fixture dup of #6 |
| 6 | test/java/…/domain/workers/WorkerExecutionGuardTest.kt | 1140 | 48 | 0 | MOCKED | WorkerExecutionGuard (REAL) | KEEP | P0 | 5, 3 | anchor test; FRAGILE 11-mock ctor |
| 7 | test/java/…/domain/workers/WorkerGuardVerifierTest.kt | 101 | 3 | 0 | PURE | WorkerGuardVerifier, WorkerSpecScheduler | KEEP | P2 | 10, 23 | pins exact 10 worker names |
| 8 | test/java/…/domain/workers/WorkerIdempotencyTest.kt | 116 | 5 | 0 | PURE | WorkerSpec.DEFAULTS | KEEP | P2 | 3 | misleading name (spec config, not runtime) |
| 9 | test/java/…/domain/workers/WorkerLeaseRegistryTest.kt | 325 | 18 | 0 | MOCKED | WorkerLeaseRegistryImpl (real) | KEEP | P0 | 10 | real-clock drain waits (minor flake) |
| 10 | test/java/…/domain/workers/WorkerRestoreRegressionTest.kt | 404 | 15 | 0 | MOCKED | WorkerLeaseRegistryImpl, WorkerRunLoggerImpl, WorkerRegistry | STRENGTHEN | P1 | 9, 12 | lease+CAS sections dup 9/12 |
| 11 | test/java/…/domain/workers/WorkerRunContextThreadSafetyTest.kt | 43 | 2 | 0 | STRESS | WorkerRunContext | KEEP | P2 | — | 1000 concurrent increments |
| 12 | test/java/…/domain/workers/WorkerRunLoggerTest.kt | 980 | 58 | 0 | MOCKED | WorkerRunLoggerImpl, WorkerReasonCodes | KEEP | P0 | 3, 10 | terminal CAS + reason-code sanitization |
| 13 | test/java/…/domain/workers/WorkerSpecSchedulerTest.kt | 411 | 9 | 0 | ROBOLECTRIC | WorkerSpecScheduler | KEEP | P1 | 8 | FRAGILE: mockkObject + static WorkManager |
| 14 | test/java/…/AnalyticsEngineTestBase.kt | 404 | 0 | 0 | FIXTURE | (42 analytics/budget/e2e subclasses) | KEEP | P1 | 15 | mock layer re-implements SQL math |
| 15 | test/java/…/AnalyticsTestCompat.kt | 293 | 0 | 0 | FIXTURE | analytics engine compat shims (20+ users) | KEEP | P1 | 18 | duplicate currency fakes vs #18 |
| 16 | test/java/…/TestUtils.kt | 302 | 0 | 0 | FIXTURE | createExpense/assertApproxEquals (128 users) | KEEP | P1 | — | systemDefault TZ; deprecated helper kept |
| 17 | test/kotlin/…/domain/logic/RecurringExpenseEngineTest.kt | 316 | 12 | 0 | MOCKED | RecurringExpenseEngine | KEEP | P1 | RecurringExpenseEngineEmptyListTest | Calendar default-TZ (noon mitigates) |
| 18 | test/java/…/currency/CanonicalMultiCurrencyFixture.kt | 592 | 7 | 0 | FIXTURE | MultiCurrencyRepository, CurrencyConverter | KEEP | P0 | 15 | 142-EUR golden; fakes unused elsewhere |
| 19 | test/java/…/guard/DbGuardPolicyFixtureTest.kt | 2298 | 72 | 0 | SRCTEXT | db_ownership_policy.yml + 4 prod sources | KEEP | P2 | batch-05 arch guards | FRAGILE pinned counts 99/62/58/4 |
| 20 | test/java/…/guard/MoneyBoundaryGuardTest.kt | 214 | 10 | 0 | STRESS | scripts/verify_money_boundaries.py | KEEP | P2 | batch-05, scripts/ | requires Python on PATH |
| 21 | test/java/…/guards/GuardSeededViolationTest.kt | 102 | 4 | 0 | SRCTEXT | scripts/guards/*.kts (existence only) | DELETE | P4 | #20, scripts/ CI | tautology: never runs the scripts |
| 22 | test/java/…/receiver/BootReceiverStressTest.kt | 61 | 4 | 0 | ROBOLECTRIC | BootReceiver | KEEP | P2 | — | prior "environment-dependent" OVERTURNED |
| 23 | test/java/…/receiver/ServiceRestartReceiverStressTest.kt | 51 | 3 | 0 | ROBOLECTRIC | ServiceRestartReceiver | KEEP | P2 | — | misnamed "stress"; deterministic |

## Findings (noteworthy files only)

### test/java/com/yourname/expensetracker/domain/widget/WidgetStyleRepositoryTest.kt
- Both tests build anonymous `object : WidgetStyleRepository` fakes that **re-declare `toggleWidgetStyle` verbatim**, overriding the interface's default method (`domain/widget/service/WidgetStyleRepository.kt:20-36`); assertions therefore test the test's copy of the logic, never production code — pure tautology (lines 22-44, 52-68).
- The real fix under test (force-unwrap crash + MODERN fallback) lives in the interface default and `WidgetStyleRepositoryImpl` (DataStore JSON, `data/repository/WidgetStyleRepositoryImpl.kt:47-58` corrupt-JSON fallback) — neither is exercised; `WidgetStyleRepositoryImpl` has zero test coverage anywhere.
- Dead mockk imports (`coEvery/coVerify/mockk`, lines 6-8).
- Action: REWRITE — minimal fake implementing only `config()`/`update()` (leaving the default `toggleWidgetStyle` intact) plus a DataStore-backed impl test for parse/serialize/corrupt-JSON.

### test/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuardTest.kt
- Anchor P0 file: drives the real guard. Verifies the full strict-mode contract: `CancellationException` rethrown with highest precedence and logged `WORKER_CANCELLED` (lines 469-483, 1125-1139); `RetryableWorkerException`→Retry even with non-matching message (375-387); invalid/path-like retryable codes sanitized to `WORKER_UNHANDLED_EXCEPTION` (394-426); non-transient→Failed vs transient-keyword→Retry (429-467); barrier TOCTOU before run-logging with zero DAO writes (217-246); blockedPolicy RETRY/SKIP_SUCCESS/FAIL matrix (252-344); terminal write survives cancellation via NonCancellable and 5s timeout can't hang the worker (519-568); CAS stale recovery never overwrites SUCCESS/FAILED and fails closed with controlled codes only (574-730); privacy Denied/FailClosed and notification-permission SKIP/RETRY/FAIL matrices gate only the gated block (736-971); disabled-spec skip reason pinned to `DailyBriefingWorker.DISABLED_BY_SPEC_REASON` end-to-end (188-210).
- This directly proves AGENTS.md invariants: cancellation never swallowed, metrics/reasons sanitized, permission gate scopes to notification-only, restore barrier respected.
- FRAGILE: 11-constructor-arg mock block + sealed-interface stub lists (84-106) duplicated with #5 and #3 — a `WorkerExecutionGuard` constructor change breaks 3 files (~80 tests). Confirms batch 12's recommendation: extract a shared `GuardedWorkerTestHarness` fixture (guard builder + `FakeNotificationPermissionChecker` + `runHandle` stubs). `FakeNotificationPermissionChecker` is defined twice (#5 line 60, #6 line 54).

### test/java/com/yourname/expensetracker/domain/workers/P9RemainingWorkerFixesTest.kt
- Two tests assert on **Kotlin stdlib `maxOf`** (lines 263-271: `assertEquals(60_000L, maxOf(1L, 60_000L))`) — tests nothing production; the real 60s-floor behavior is already covered in #13 (`near-midnight schedule floors initial delay at 60 seconds`). DELETE these two.
- Spec assertions (default KEEP, merchant_key REPLACE) duplicate #8 (`oneShotPolicy defaults…`, lines 96-115); the 6 Handle-idempotency tests duplicate #12's `duplicate_success_is_noop`/`duplicate_failure_is_noop`/`concurrent_terminal_calls_result_in_exactly_one_write` (only cancelled/staleAborted/skipped sequential no-ops are additive → move into #12).
- Unique value worth keeping: merchant_key battery-not-low constraint (36-45) and the unexpected-`RuntimeException`-at-checkpoint → `WorkerCheckpointBlockedException` + `diagnosticSink.recordBlockedOperation` fail-safe path (199-253), which #5/#6 do not cover.

### test/java/com/yourname/expensetracker/domain/workers/WorkerRestoreRegressionTest.kt
- Restore-lifecycle sweep over ALL worker names is unique (barrier blocks every scheduler-known name, 176-194; re-acquire after `resetStopFlag`, 225-243; `WorkerRegistry`↔`WorkerSpec`↔scheduler cross-check, 73-93).
- But: lease stop/reject/reset cases substantially duplicate #9 (`concurrent stop request and acquire is safe` is near-identical to WorkerLeaseRegistryTest:238), and the 4 CAS-terminal tests (288-395) duplicate #12's idempotency/race coverage (survivor: WorkerRunLoggerTest). Keep file as the restore regression, merge those sections.

### test/java/com/yourname/expensetracker/domain/workers/WorkerRunLoggerTest.kt and FileWorkerTerminalDiagnosticSinkTest.kt
- #12: 58 tests over real `WorkerRunLoggerImpl` — terminal state machine incl. timeout/SQL-failure leaving handle retryable (283-328), affected-0 CAS reconciliation via `getById` (330-366), `TerminalWriteOutcome` Durable/NotDurable/AlreadyTerminal (372-525), and PR12J-1/PR12K-4 privacy: path-like/PII reason strings never persisted as `terminalReasonCode`/`terminalDiagnosticCode` (725-959), `classifyDiagnostic` never echoes raw message (862-875). Exemplary.
- #2: 22 tests over real JSONL file sink in temp dirs — rotation at 512KB with backup replacement (218-278, 449-475), bounded-suspend 500ms timeout doesn't throw to worker (522-562), mutex serialization under 20 concurrent writers with no interleaving (565-607), and explicit privacy-negative assertions (no errorMessage/stacktrace/notification/bank/OCR fields, 177-352). This is the `*WorkerTerminalDiagnostic*` AGENTS.md target and it delivers.

### test/java/com/yourname/expensetracker/guards/GuardSeededViolationTest.kt
- Never executes the guard scripts. It writes violations to its own temp files and matches them with regexes **re-implemented inside the test** (`rawSumPattern` defined at line 26), then asserts the match — tautology. The only external claim is "script exists / contains the string `sumOf`" (SRCTEXT, lines 16-24) plus relative-path `../scripts/guards` fragility.
- Prior 2026-05 audit verdict KEEP P2 is OVERTURNED: real enforcement belongs to Segment 39 CI (`scripts/ci/run_static_guard_suite.py`) and #20, which actually runs `verify_money_boundaries.py`. DELETE (the existence-canary value is already provided by CI workflow).

### test/java/com/yourname/expensetracker/receiver/BootReceiverStressTest.kt / ServiceRestartReceiverStressTest.kt
- Prior "environment-dependent / NIGHTLY" verdict OVERTURNED: both are small deterministic Robolectric dispatch tests (mocked Context, no sleeps, no real broadcasts). They correctly pin boot/package-replaced/restart-action → `startForegroundService(NotificationCaptureService)` and ignore unrelated actions.
- Minor: name says "Stress" but nothing is stressed — rename; the "does not crash" tests assert only non-throwing (acceptable negative path).

### test/java/com/yourname/expensetracker/guard/DbGuardPolicyFixtureTest.kt
- 72-test pinned contract over `config/guards/db_ownership_policy.yml` (99 entries), `db_structural_exceptions.yml` (62), and the structural manifest (58 expected + 4 fixtures), with fail-closed parser tests and per-class source-evidence checks that the write barrier is called before the DAO mutation (`WorkerExecutionGuard.kt`, `WorkerRunLogger.kt`, `ExchangeRateStoreAdapter.kt`, `PromptStateRepository.kt`). Real value: any silent weakening of the DB ownership allowlist fails.
- Flags: FRAGILE by design (adding one legitimate policy entry breaks 4+ pinned count/union assertions); source-evidence tests are SRCTEXT and shift with formatting; triple redundancy with batch-05 architecture guards and scripts/ CI — acceptable as defense-in-depth, note in guard-policy docs.

### test/java/com/yourname/expensetracker/guard/MoneyBoundaryGuardTest.kt
- Genuinely executes the real money guard (`py -3/python3/python scripts/verify_money_boundaries.py`) against seeded failing fixtures (G-MONEY-10/11/17/21) and passing fixtures, plus a full current-source pass (208-213). This is what #21 pretends to do.
- Flag: environment-dependent on a Python interpreter being on PATH; on machines without it every test fails misleadingly. Consider a skip-with-warning guard or CI-only tagging.

### Root fixtures (AnalyticsEngineTestBase / AnalyticsTestCompat / TestUtils)
- `AnalyticsEngineTestBase` is load-bearing (42 subclasses: golden, budget, tax, e2e). Risk: its `mockExpenses()` re-implements DAO aggregation math in Kotlin (lines 230-297) — if DAO SQL semantics drift, mocks keep passing (mock-drift). Fixed dates use `ZoneId.systemDefault()` (TZ-sensitive, non-UTC CI hazard). `MainDispatcherRule` is only defined here — fine, but should move to a shared util.
- `AnalyticsTestCompat` bridges Expense→ExpenseSnapshot for 20+ files; its `TestExchangeRateStore`/`TestCurrencySettingsRepository` duplicate the `FakeExchangeRateStore`/`FakeCurrencySettingsRepository` in #18 — two parallel currency-fake ecosystems to consolidate.
- `TestUtils` used by 44 (`createExpense`) + 84 (assert helpers) files — keep; still carries deprecated `endOfMonthInclusive`.

## Area gaps (what is NOT tested in this area)

- `WidgetStyleRepositoryImpl` (DataStore JSON serialize/parse, corrupt-JSON fallback) has zero coverage; the only widget test is tautological (#1).
- `WorkerDrainController` / `NoOpWorkerDrainController` have no dedicated test; drain behavior is only touched indirectly via MaintenanceOperationRunnerTest (F-18, batch 10) — drain timeout semantics currently measured-FAILING with no green in-domain test.
- `WorkerReasonCodes` has no dedicated test file; sanitization is exercised only inside WorkerRunLoggerTest (3 asserts, lines 794-810).
- `WorkerRunContext` checkpoint-hook invocation (the `Checkpoint` callback path beyond counters) untested.
- No test covers `WorkerSpecScheduler` periodic (`repeatIntervalHours != null`) enqueue path / `ExistingPeriodicWorkPolicy.UPDATE` on version bump — only one-shot midnight paths are tested (#13).
- Lease registry drain uses real-clock spin waits (#9/#10) — no test of drain under a slow-releasing lease with the virtual-time provider (only the late-acquire case uses virtual time).
- Analytics fixtures: no consolidation of the two currency-fake families; mock-drift risk in AnalyticsEngineTestBase aggregation mirrors.

## Rollup

- Verdicts: KEEP 19 · STRENGTHEN 2 · MERGE 0 · REWRITE 1 · DELETE 1 · NIGHTLY 0 · UNKNOWN 0
- P0 count: 7 (files 2, 4, 5, 6, 9, 12, 18)
- DUP pairs found: (a) P9RemainingWorkerFixesTest ⇄ WorkerRunLoggerTest (handle idempotency; survivor WorkerRunLoggerTest); (b) P9RemainingWorkerFixesTest ⇄ WorkerIdempotencyTest (spec oneShotPolicy defaults); (c) WorkerRestoreRegressionTest ⇄ WorkerLeaseRegistryTest (stop/reject/reset + checkpoint-barrier); (d) WorkerRestoreRegressionTest CAS section ⇄ WorkerRunLoggerTest; (e) WorkerBarrierIntegrationTest ⇄ WorkerExecutionGuardTest (BlockedPolicy matrix — partial DUP, otherwise complementary: #5 owns restore-mode breadth, #6 owns policy/cancellation depth); (f) AnalyticsTestCompat ⇄ CanonicalMultiCurrencyFixture (duplicate currency fakes, infrastructure only).
- FRAGILE count: 4 (WorkerExecutionGuardTest, WorkerSpecSchedulerTest, P9RemainingWorkerFixesTest 15-arg `any()` chains, DbGuardPolicyFixtureTest pinned counts) + shared-fixture duplication spanning 3 worker files.
- Negative-value files: guards/GuardSeededViolationTest.kt (P4, DELETE); domain/widget/WidgetStyleRepositoryTest.kt (tautological but REWRITE target, P2).
- Strict-mode conclusions: real guard used everywhere in domain/workers; retry-vs-failure, cancellation propagation, idempotency, sanitized reason codes, and metrics-after-success semantics all positively verified; privacy cleanup (data_retention) cancel-exemption pinned. Recommended action: create shared `GuardedWorkerTestHarness` fixture in domain/workers (per batch-12 finding, confirmed applicable here).
