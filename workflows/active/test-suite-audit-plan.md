# test-suite-audit — slice plan

Status: APPROVED 2026-10-16 (user: "do what you recommend"; work separately). Executing in worktree `Cost-agregator-tests`
(branch `chore/test-suite-audit`, base 8671b844). **B1 is superseded by the release work**; see handoff "Coordination with release work".
Execution order: B4-P1 -> B2 -> B3 -> B3b -> B4 -> B5 -> B6 -> B7 -> B8 -> B9 -> B1R (leftovers, after release lands) -> final.
Sources: `docs/audits/test-suite-audit/PROPOSAL.md`, A1–A7 reports, handoff "Human decisions".
Planners: P1 (B1 + B2, below), P2 (B3–B9, appended when it returns). Read-only plans; no validation run.
Tier: T3 overall (guard allowlists, worker/privacy/lifecycle proofs). Root abbreviation `…\` = `app\src\{test|main}\java\com\yourname\expensetracker\`.
Every validation is serialized via validation-runner and needs a quiet worktree (run vr-20261016-172459-e17bfca7 went STALE because of a concurrent session).

---

## Part 1 — B1 per-entry fix/renew table (shown at the plan gate)

### 1.1 `WriteBarrierArchitectureGuardTest` — 16 exemptions, `EXEMPT_CLASSES` :143-158, all `owner=UNASSIGNED issue=U-004 expiry=2026-10-31`
Hygiene test :666 uses `LocalDate.now()` (:635) → red 2026-11-01. A class passes the guard by declaring a `DatabaseWriteBarrier` property or calling `checkWritesAllowed(` (:369-375); that check refuses writes outside NORMAL mode, so every code "fix" changes runtime behaviour (restore, audit, worker, privacy-purge and coordinator-transaction paths).
**Recommendation: RENEW all 16 to 2027-03-31 with named owners** (owners from the "proposed owners" table :121-142), giving B7 time to unify with `config/guards/db_ownership_policy.yml`. Reason template: `owner=<RP> issue=U-004 expiry=2027-03-31 renewed 2026-10-xx (test-suite-audit B1): <write>; <why barrier not applied>`.

| # | Class | Covers | Recommendation / owner | Conf. |
|---|---|---|---|---|
| 1 | LegacyDataMigrationService | `categoryDao.insert` | RENEW → RP-20 (startup/legacy migration; barrier throw needs design) | high |
| 2 | CsvExpenseImporter | `categoryDao.insert` | RENEW → RP-19 (later FIX candidate: ctor + fixtures + T3 import review) | med |
| 3 | JsonExpenseImporter | `categoryDao.insert` | RENEW → RP-19 | med |
| 4 | RestoreJournalImporter | operationRun/Event inserts on restore | RENEW → RP-03 (runs in maintenance by design; standing-exemption candidate) | high |
| 5 | ReceiptInsertResolver | `scannedReceiptDao.insert` | RENEW → RP-12 | med |
| 6 | DebugExpenseAuditWriter | `transactionEventDao.insert` | RENEW → RP-11 (inside coordinator txn) | med |
| 7 | NotificationIntakeWorker | intake claim/mark/purge | RENEW → RP-10 (blocking privacy purge conflicts with fail-closed retention) | high |
| 8 | NotificationIntakePayloadRepairer | payload purge/encrypt | RENEW → RP-10 | high |
| 9 | NotificationIntakeRecoveryScheduler | `releaseStaleProcessing` | RENEW → RP-10 | med |
| 10 | WarrantyExpirationWorker | delivery lifecycle writes | RENEW → RP-16 | med |
| 11 | WorkerRunLogger | backgroundJobRun insert/terminal | RENEW → RP-16 (observability during maintenance; standing candidate) | high |
| 12 | DiagnosticEventWriter | `pipelineDiagnosticEventDao.insert` | RENEW → RP-14 (standing candidate) | high |
| 13 | OperationRunRecorder | operation-run run/event | RENEW → RP-16 (records restore itself; standing candidate) | high |
| 14 | PrivacyAuditLoggerImpl | `privacyAuditDao.insert` | RENEW → RP-14 (audit must not drop) | high |
| 15 | ReceiptLifecycleEventWriter | `receiptEventDao.insert` in coordinator txn | RENEW → RP-12 | med |
| 16 | TransactionLifecycleEventWriter | `transactionEventDao.insert` in coordinator txn | RENEW → RP-11 | med |

Probe in slice: remove all 16, run guard once (~98 s), restore only the classes it reports; unreported classes are stale and dropped.

### 1.2 `config/validation/known-hanging-tests.json`
- HANG-003 (`data.backup.ExportReadBarrierTest`, suspected, legacy; evidence F-16 historical). Persisted timings: 10 cases in 0.015 s (`kotlin-unit-class-times.tsv:547`). **Recommendation: resolve** — run `legacy-tests` before 2026-10-31; pass → `status: resolved`, evidence = run ID; hang/fail → `confirmed`, owner, `review_by 2027-01-31`.
- HANG-001 (`domain.receipt.lifecycle.*`) and HANG-002 (`domain.transaction.lifecycle.*`) share `review_by 2026-10-31`; `scripts/validation-runner.ps1:391-392` throws `E_HANGING_LEDGER_REVIEW_OVERDUE`, so `legacy-tests` fails from 2026-11-01. Same treatment recommended (decision D3).

### 1.3 `DirectEventDaoInsertGuardTest` — 33 entries (7 LEGACY → 2026-11-20; 26 → 2026-12-31). LEGACY max = now + 75 days (:390-400).
- **REMOVE, stale (17)** — no guarded insert in file (grep superset of guard patterns):
  LEGACY: ReceiptRepository, RecurringExpenseRepository, ManualRecurringExpenseRepository.
  Others: GroupTransactionCoordinator, TransactionSideEffectFailureEventWriter, TransactionSideEffectPlanner, TransactionLifecycleEventWriter, ReceiptLinkService, ReceiptLifecycleEventWriter, RecurringLifecycleEventWriter, WorkerRunLogger, WorkerExecutionGuard, DiagnosticEventWriter, DiagnosticSideEffectEventWriter, CompositeSideEffectEventWriter, CompositeDiagnosticEventWriter, CompositeOperationRunRecorder. Update `docs/atomicity/TRANSACTIONAL_EVENT_POLICY.md` §6.1 in the same slice.
- **RENEW live LEGACY (4) → 2026-12-18:** WarrantyTrackerRepository (8× `warrantyLifecycleEventDao.insert`), ReviewQueueRepository (`transactionEventDao.insert` :281), BankApiIntegration (`pendingReviewDao.insert` :377), NotificationRepository (`transactionEventDao.insert` :203).
- **RENEW live owners/services (12) → 2027-06-30:** TransactionLifecycleCoordinator, ReceiptLifecycleCoordinator, ReceiptSideEffectPlanner, RecurringLifecycleCoordinator, RecurringRuleLifecycleCoordinator (legal path, permanent by design); DebugExpenseAuditWriter, ReceiptMatchLifecycleService, BankStatementLifecycleProcessor, OperationRunRecorder, RestoreJournalImporter, DataRetentionWorker. DefaultExpenseCategoryAssignmentService (:46) → 2027-03-31 (later FIX candidate via TransactionLifecycleEventWriter).

### 1.4 `TransactionContextProvenanceGuardTest` — 6 entries → 2026-12-31 (:29-66)
| Entry | Finding | Recommendation | Conf. |
|---|---|---|---|
| DomainTransactionRunner.kt | no `TransactionContext(` construction (KDoc only) | REMOVE | med-high |
| RoomDomainTransactionRunner.kt | canonical factory | RENEW 2027-12-31 | high |
| TransactionContext.kt | `copy()` :68 | RENEW 2027-12-31 | high |
| ReceiptLifecycleEventWriter.kt | deprecated `write(event)` :77 | RENEW 2027-06-30 | med |
| TransactionLifecycleEventWriter.kt | same :77 | RENEW 2027-06-30 | med |
| TransactionSideEffectFailureEventWriter.kt | constructs context :37 | RENEW 2027-06-30 | med |

### 1.5 `SourceScanningArchitectureGuardTest`
- **DIRECT_DAO_IN_WORKER (6, → 2026-12-31, :45-94):** DismissReminderActionWorker, SnoozeReminderActionWorker, SourceLinkBackfillWorker, DataRetentionWorker, WarrantyExpirationWorker, NotificationIntakeWorker → RENEW 2027-06-30, owner WorkerArchitecture; per-entry probe drops entries the guard no longer reports.
- **BROAD_CATCH_NO_CANCELLATION_RETHROW (5, expired 2026-09-30, never enforced, :548-609):**

| Entry | Finding | Recommendation | Conf. |
|---|---|---|---|
| LocationBackfillWorker | has rethrow idiom (:70, :79, :110) | REMOVE | high |
| ReceiptMatchingWorker | has idiom (:209) | REMOVE | high |
| BillReminderWorker | has idiom (:127, :160, :173, :270) | REMOVE | high |
| MerchantKeyBackfillWorker | `catch (e: Exception)` :74, :110, no rethrow | FIX: add `if (e is kotlinx.coroutines.CancellationException) throw e` first in both | med-high |
| WarrantyExpirationWorker | `catch (e: Exception)` :147, no rethrow | FIX: same | med-high |

Then the list is empty → delete it and its filename filter (rule becomes strict).

### 1.6 `CancellationSafetyArchitectureGuardTest` — real clock
Fixed `GUARD_TODAY_VALID = 2026-09-15` (:230, used :416, :442). Planner counted **12** past-due `RAW_RUN_CATCHING_ALLOWLIST` entries (:198-209, all 2026-10-01), not 13 — recount in slice. Also ~97 `KNOWN_VIOLATIONS` + 2 RAW expire 2026-12-31 (red 2027-01-01 on the real clock).
Clock change: valid branch uses `LocalDate.now()` via the existing `guardToday()` seam; negative controls derive dates from the lists (`minOf{expires}.plusDays(1)` etc.) or use synthetic fixtures (as WriteBarrier :818-845).

| File (owner) | Raw `runCatching` | Recommendation | Conf. |
|---|---|---|---|
| ValidateBankStatementTransactionsUseCase | 0 | REMOVE | high |
| AdvancedAnalyticsEngine | 0 | REMOVE | high |
| SubscriptionManagerEngine | 1 | FIX → `CancellationSafe.runCatchingCancellable {` (if in suspend fn) | med |
| NetCashflowBalanceProvider | 2 | FIX, same | med |
| DefaultCloudPayloadPolicy (Privacy) | 1 | FIX, same; privacy review | med |
| RestoreJournalImporter | 2 | RENEW 2027-03-31, RP-03 | high |
| DatabaseBackupRepositoryImpl | 23 | RENEW 2027-03-31, RP-03 | high |
| DiagnosticsRepository | 11 | RENEW 2027-03-31 | high |
| FinancialStressForecastEngine | 5 | RENEW 2027-03-31, RP-06 | high |
| AnalyticsRepository | 4 | RENEW 2027-03-31 | high |
| InvestmentTracker | 4 | RENEW 2027-03-31 | high |
| CarbonFootprintCalculator | 3 | RENEW 2027-03-31 | high |

2026-12-31 entries: RAW HistoricalSpendingDistribution (0) → REMOVE; RAW RestoreDiagnosticsSink (4) → RENEW 2027-03-31 RP-03; ~97 KNOWN_VIOLATIONS → probe, drop unreported, RENEW rest to 2027-06-30 (~9 carry `owner="UNASSIGNED"`).

---

## Part 2 — Slices B1 and B2

Binding invariants (AGENTS.md): workers never swallow `CancellationException`; privacy fail-closed; guards not weakened; no allowlist grows.

| Slice | Goal | Write set | Agent | Tier / review | Validation (serialized) | Deadline | Status |
|---|---|---|---|---|---|---|---|
| B1-1 | WriteBarrier renewal (§1.1, probe) | `…\test\…\architecture\WriteBarrierArchitectureGuardTest.kt` (+ `docs/ci/CI_GUARDRAILS_BASELINE.md:220`, `docs/architecture/ARCHITECTURE.md:112` only if they quote U-004 dates) | test-author | T3: reviewer-strict + architecture-guardian | targeted-unit-test `com.yourname.expensetracker.architecture.WriteBarrierArchitectureGuardTest` (probe expected red, then green) | 2026-10-31 | planned |
| B1-2 | Hanging ledger HANG-003 (+001/002 per D3) | `config/validation/known-hanging-tests.json`, F-16 row in TEST_FAILURE_LEDGER.md | test-author | T2 cheap review | `legacy-tests` before edit (evidence); re-run after only if an entry stays suspected/confirmed (see R2) | run before 2026-10-31 | planned |
| B1-3 | Event-DAO + context-provenance allowlists (§1.3, §1.4) | `…\architecture\DirectEventDaoInsertGuardTest.kt`, `…\architecture\TransactionContextProvenanceGuardTest.kt`, `docs/atomicity/TRANSACTIONAL_EVENT_POLICY.md` (§6.1, :490) | test-author | T3: reviewer-strict + architecture-guardian | targeted `…architecture.DirectEventDaoInsertGuardTest`, then `…architecture.TransactionContextProvenanceGuardTest`; `static-guards` if §6.1 pinned by `ci/test_verify_guard_docs_truth.py` | 2026-11-20 | planned |
| B1-4 | SourceScanning worker allowlists (§1.5) + 2 worker rethrow fixes | `…\main\…\data\location\MerchantKeyBackfillWorker.kt`, `…\main\…\service\warranty\WarrantyExpirationWorker.kt`, `…\architecture\SourceScanningArchitectureGuardTest.kt` (`SENSITIVE_DIAGNOSTICS_POLICY.md:100` if affected) | feature-implementer | T3: reviewer-strict + architecture-guardian | targeted `…architecture.SourceScanningArchitectureGuardTest`, `com.yourname.expensetracker.*MerchantKeyBackfillWorker*`, `com.yourname.expensetracker.*WarrantyExpirationWorker*`, `…architecture.CancellationSafetyArchitectureGuardTest`; then `static-guards` | 2026-12-31 (scheduled in B1) | planned |
| B1-5 | CancellationSafety real clock (§1.6) + 3 runCatching fixes | `…\main\…\domain\subscription\SubscriptionManagerEngine.kt`, `…\main\…\domain\forecasting\NetCashflowBalanceProvider.kt`, `…\main\…\data\privacy\DefaultCloudPayloadPolicy.kt`, `…\architecture\CancellationSafetyArchitectureGuardTest.kt` (CANCELLATION_POLICY.md / CANCELLATION_ATOMICITY_BASELINE.md if they describe the fixed clock) | feature-implementer | T3: reviewer-strict + architecture-guardian + privacy-security-guardian | targeted `…architecture.CancellationSafetyArchitectureGuardTest` (probe, then green), `*SubscriptionManagerEngine*`, `*NetCashflowBalanceProvider*`, `*CloudPayload*`; then `static-guards` | with decision 2; after B1-4 | planned |
| B2-1 | Privacy settings corruption → real repo fail-closed | `…\test\…\data\privacy\PrivacySettingsRepositoryImplCorruptionTest.kt` (rewrite). Stop if `PrivacySettingsRepositoryImpl` has no test DataStore seam → T3 production change | test-author | T3: reviewer-strict + privacy-security-guardian | targeted `com.yourname.expensetracker.data.privacy.PrivacySettingsRepositoryImpl*` | — | planned |
| B2-2 | PRIV-441-05/06/07 via real NotificationCaptureGate; PrivacyGuardTest silent pass | new `…\domain\privacy\NotificationCaptureGatePrivacyContractTest.kt`; delete `…\domain\privacy\NotificationPrivacyHardeningTest.kt`; `…\domain\privacy\PrivacyGuardTest.kt` (:95, :114); citing docs | test-author | T3: reviewer-strict + privacy-security-guardian | targeted `com.yourname.expensetracker.domain.privacy.*`, then `com.yourname.expensetracker.*NotificationCapture*` | — | planned |
| B2-3 | Group transaction rollback with injected failure | `…\test\…\data\database\GroupTransactionCoordinatorTest.kt` (check `docs/ci/db-mediation/GR-14u36.yml:18`) | test-author | T3: reviewer-strict + architecture-guardian | targeted `com.yourname.expensetracker.data.database.GroupTransactionCoordinatorTest` | — | planned |
| B2-4 | SafeSinkOperationRunHandle terminal-once; DDL512 tautologies | new `…\domain\diagnostics\SafeSinkOperationRunHandleTest.kt`; `…\diagnostics\DDL512RegressionTest.kt`; citing docs | test-author | T3: reviewer-strict + privacy-security-guardian | targeted `com.yourname.expensetracker.domain.diagnostics.*`, then `com.yourname.expensetracker.diagnostics.DDL512RegressionTest` | — | planned |
| B2-5 | Real worker concurrency, Windows-effective sink failure, Recommendation cancellation | `…\domain\workers\WorkerRunLoggerTest.kt`, `…\domain\workers\WorkerLeaseRegistryTest.kt`, `…\domain\workers\FileWorkerTerminalDiagnosticSinkTest.kt`, `…\service\RecommendationLifecycleManagerTest.kt` (sink seam → feature-implementer T3) | test-author | T3: reviewer-strict + architecture-guardian (+ privacy-security-guardian for sink) | targeted `com.yourname.expensetracker.domain.workers.*`, then `com.yourname.expensetracker.service.Recommendation*` | — | planned |
| B2-6 | Silent-pass scanners → failures | `…\architecture\RecurringArchitectureGuardTest.kt` (16× `if (!x.exists()) return`), `…\domain\analytics\IncludeDepositsForBehaviorCleanupTest.kt`, `…\contracts\CancellationPropagationContractTest.kt` (:107-113) + same pattern in other contracts tests | test-author | T3: reviewer-strict + architecture-guardian | targeted `…architecture.RecurringArchitectureGuardTest`, `…domain.analytics.IncludeDepositsForBehaviorCleanupTest`, `com.yourname.expensetracker.contracts.*` | — | planned |

Per-slice details:
- **B1-1** done when no `owner=UNASSIGNED` remains and hygiene passes on the real clock.
- **B1-2** done when no legacy entry has a past `review_by` and evidence cites a run ID.
- **B1-3** done when every remaining entry is live and nothing expires before 2026-12-18.
- **B1-5** done when green on `LocalDate.now()`, negative controls fail on past-expiry data, nothing expires before 2027-03-31.
- **B2-1** add 2 tests writing corrupt bytes into the real repository's DataStore, asserting `CorruptedFailClosed` + fail-closed defaults; delete the self-built DataStore tests (A2:101; `"CORRUPTED" != null` :176).
- **B2-2** test the `NotificationCaptureGate` production actually wires (two files exist: `domain\notification\capture\`, `domain\privacy\`); check `service/NotificationCaptureService*Test` first; add 3–4 real, delete 10 vacuous only after new ones pass (A4:140).
- **B2-3** add fault-injecting rollback (spy DAO, member insert fails, no partial group); delete :389, :524; real assertion at :512; rename or make :414 truly concurrent; keep :255 (sole rollback proof). Use the `data\database` coordinator.
- **B2-4** second terminal call is no-op, failing sink doesn't throw; drive via `CompositeOperationRunRecorder` (:98) if private; delete ~18 tautologies (A5:51); keep scans :132, :381 and sanitizer tests.
- **B2-5** WorkerRunLoggerTest :207 real multi-threaded with start latch (must fail without the Mutex); check WorkerLeaseRegistryTest; sink :281 injected append failure, :478/:546 assert outcomes; add Recommendation cancellation-rethrow; OOM test stays.
- **B2-6** done when none of the tests can pass with their scanned paths missing.

Full `unit-tests` once at the end of the programme (PROPOSAL §4), not per slice.

## Part 3 — Decisions and risks from P1
- **D1 Owners:** RP-xx release-1.0 IDs as owners after 1.0, or a single owner (e.g. `test-suite-audit`) until B7?
- **D2 Standing exemptions:** RestoreJournalImporter, OperationRunRecorder, WorkerRunLogger, DiagnosticEventWriter, PrivacyAuditLoggerImpl write during maintenance by design. Standing entry (removes ratchet = guard change) or RENEW (default)?
- **D3 HANG-001/002:** include in B1-2? (same date, same runner failure)
- **D4 CancellationSafety group:** renew ~97 to 2027-06-30 (default) or keep 2026-12-31 and finish B7 first?
- **D5 75-day LEGACY rule:** 4 live LEGACY entries need renewal again by 2026-12-18 — migrate them, or renew quarterly?
- R1 12 vs 13 past-due count — recount in B1-5.
- R2 If all HANG entries resolve, `Get-LegacyFilters` may return empty; `New-TestArguments`/`Assert-SafeTestFilters` behaviour unverified (could run whole suite or throw) → runner change = stop and ask.
- R3 Python YAML allowlists (G-CANCEL, G-WORKER, verify_event_writers) may flag now-stale entries or carry their own expiries → B1-4/B1-5 end with `static-guards`.
- R4 Real tests may expose real bugs (Recommendation swallowing CE, privacy corruption path, guards that passed on missing paths) → debugger + feature-implementer fix, never a weaker test.
- R5 B2-1/B2-5 may need production seams (DataStore file, sink append injection) → production change, approved per slice.
- R6 Quiet worktree needed for every probe and B1-2 evidence run before 10-31.

---

## B3–B9 (planner P2, persisted verbatim; headings demoted one level)

Feature plan: test-suite-audit, batches B3–B9 (B1/B2 are planned separately and run first)

- **Risk tier:** T3 overall. B3, B3b and B5 are mostly T1–T2. B4 guard infrastructure and the crypto seam, B6, B7 and B9 are T3. B8 is T3 because it is next to Room.
- **Schema changes: none.** No slice touches the Room schema, the DB version, migrations or schema snapshots. B8-2 only deletes skipped test methods from the device test `DatabaseMigrationTest`.
- **Preconditions:**
  - B1 and B2 are merged and validated.
  - Execution happens in a quiet worktree or a separate `git worktree`. Concurrent sessions caused E_WORKTREE_CHANGED last time.
- **Path abbreviations:**
  - `T/` = `app/src/test/java/com/yourname/expensetracker/`
  - `AT/` = `app/src/androidTest/java/com/yourname/expensetracker/`
  - `S/` = `scripts/`
- **Filters:** every `targeted-unit-test` filter matches `^[A-Za-z0-9_.$*?\-]+$`. Each filter targets a class that survives the slice. Gradle fails with "no tests found" only when the WHOLE run matches nothing; in a comma-separated multi-filter run (B4-P1c) an item that matches nothing is silently skipped, so per-item coverage must be confirmed from JUnit XML newer than the run start (0 matches = not validated) until B4-P1d adds a runner-side check. Deleted classes are proven by the test compile that every targeted run performs.

### 0. What B1 and B2 own (this plan does not touch these files)

**B1 owns:**
- the exemptions and clock in `WriteBarrierArchitectureGuardTest`
- the expiries in `DirectEventDaoInsertGuardTest`
- the real clock and the 13 past-due entries in `CancellationSafetyArchitectureGuardTest`
- the 2026-12-31 entries in `SourceScanningArchitectureGuardTest` and `TransactionContextProvenanceGuardTest`
- HANG-003 in `config/validation/known-hanging-tests.json`

**B2 owns:**
- `PrivacySettingsRepositoryImplCorruptionTest`
- `NotificationPrivacyHardeningTest`
- the vacuous parts of `PrivacyBehavioralRegressionTest`
- the real `SafeSinkOperationRunHandle` terminal-once test
- `GroupTransactionCoordinatorTest` :389/:524
- `WorkerRunLoggerTest` :207, `WorkerRestoreRegressionTest` :417, `FileWorkerTerminalDiagnosticSinkTest` :281
- the Recommendation cancellation-rethrow test
- the silent-pass fixes in `RecurringArchitectureGuardTest`, `PrivacyGuardTest`, `IncludeDepositsForBehaviorCleanupTest` and the `continue` in `CancellationPropagationContractTest`

**Slices here that must wait for B1:** B4-K1, B6-22, B7-2, B7-5, B7-6.

**Slices here that must wait for B2:** B5-13, B6-9, B6-11, B6-12, B6-13, B6-14, B6-19, B7-5.

**Overlaps resolved:**
- A3 wanted to delete `IncludeDepositsForBehaviorCleanupTest`. It is left entirely to B2: fix it or delete it there, but not both.

### 1. Order and why

Order: **B4-P1 → B3 → B3b (ends with B3b-R) → B4 → B5 → B6 → B7 → B8 → B9 → final.**

Changes from the default order:
1. **B4-P1 (a `pytest-subset` runner profile) runs first.** Without it, the only way to validate a Python change is a full `static-guards` run (17.6 min). B3-9, B3b-R, B5-19/20, B7-3/4 and all of B9 need cheap Python runs. B4-P1 also enables the `--durations` measurement that decision 7 asks for before the B4 Python speed work. It touches no file that B1, B2 or B3 touch.
2. **Ignored ViewModel stress classes that are half valuable, half trivial are handled once, in B3b.** Each file is touched once: the valuable tests are revived and the trivial ones deleted in the same slice. B3 deletes only classes that are fully dead.
3. **One test-file update for the ignored-test budget.** The G-IGNORE-01 baseline is lowered once, in B3b-R, after all B3/B3b `@Ignore` changes. The guard fails only when the count goes up, so the slices before it stay green.
4. **Pairs that must run in order because they share a file:**
   - B5-19 before B9-3 (`test_migrate_db_policy_seed_rows.py`)
   - B4-K2 before B5-12 (the Cloud service tests)
   - B4-K5 before B6-23 (`BackupEncryptionServiceTest`)
   - B3-2 before B8-4 (the half-open boundary test that replaces the deleted vacuous one)
5. **B8 is independent of B5–B7.** It can be pulled forward whenever a device is available.

All slices run one after another, because there is a single validation owner. Slices with disjoint write sets may be authored in parallel but are validated serially.

**Files that more than one slice edits (always serialized):**

| File | Slices |
|---|---|
| `docs/testing/TEST_IGNORE_CLASSIFICATION.md` | B3-1, B3-4, B3-7, B3-8, B3b-*, B3b-R, B5-5 |
| `T/TestUtils.kt` | B3-5, B6-2 |
| `TimePeriodUtilsTest` | B3-2, B6-1 |
| `BudgetCalculatorBoundaryTest` | B3-4, B6-4 |
| `CostbackupBundleLimitsTest` | B3-3, B4-K5, B6-21 |
| `P7BugFixesTest` | B4-K4, B6-21 |
| `SynthesisEngineStressTest` | B3-6, B6-5 |
| `S/ci/test_validation_runner_protocol.py` | B4-P1, B4-P2 |
| `docs/ci/CI_GUARDRAILS_BASELINE.md` | B3-9, B7-1, B7-3, B7-5, B7-6 |

### 2. Slices

Agents: TA = test-author, FI = feature-implementer. RS = reviewer-strict. The "−/+" column gives approximate tests removed and added; merges count as removals.

### B4-P1 (runs first)

| ID | Area · goal | Write set | Agent | −/+ | Proof kept | Docs updated in the same slice | Validation | Tier · review |
|---|---|---|---|---|---|---|---|---|
| B4-P1 | Validation runner · new `pytest-subset` profile: `python -m pytest <Filter> -q -p no:cacheprovider --durations=40`. The Filter must be a path under `scripts/` (allowlisted regex, no `..`, absolute paths or spaces; `scripts` itself is allowed). Timeout 2400 s. | `S/validation-runner.ps1`, `S/ci/test_validation_runner_protocol.py` (accept and reject cases), `docs/development/VALIDATION_RUNNER.md` | FI | +4 | Existing unsafe-filter and tampered-request fail-closed cases unchanged | VALIDATION_RUNNER.md; AGENTS.md only if it lists profiles | `runner-protocol-tests`, then **measurement only**: `pytest-subset -Filter scripts` (per-file durations) | T2 · RS |

### B3: dead-code deletions (approved)

| ID | Area · goal | Write set | Agent | −/+ | Proof kept | Docs updated in the same slice | Validation | Tier · review |
|---|---|---|---|---|---|---|---|---|
| B3-1 | data, ignored stress classes · delete 7 `@Ignore` StressTest classes and `SecureKeyStorageTest` | delete `T/data/database/entity/ExpenseEntityStressTest.kt`, `T/data/repository/{NotificationProcessingPipeline,NotificationRepository,ExpenseRepository,ReceiptRepository,ReviewQueueRepository,CategoryRepository}StressTest.kt`, `T/data/security/SecureKeyStorageTest.kt`; edit `DedupeKeyTest.kt` | TA | −145 / +2 | 2 unique cases move into `DedupeKeyTest`: the locale-ROOT key under de/el, and 1.255→"1.26". Before deleting `ReceiptRepositoryStressTest`, confirm it has no unique OCR-fallback assertion beyond `ReceiptRepositoryBatchDuplicateTest`, `StatementDuplicateTest` and `ReceiptItemCategorizationMoneyPersistenceTest`. | TEST_IGNORE_CLASSIFICATION.md rows | `targeted-unit-test` `*DedupeKeyTest` | T1 · cheap |
| B3-2 | data, vacuous and delegation-only tests (not backup) | delete `T/data/database/dao/ExpenseDaoBoundaryConsistencyTest.kt`, `T/data/store/ExpenseStoreTest.kt`, `T/data/location/AndroidForegroundLocationProviderTest.kt`, `T/data/ai/provider/HybridReceiptItemCategorizationServiceTest.kt`, `T/data/database/entity/MileageTrackingValidationTest.kt`; trim `T/data/repository/RecommendationRepositoryTest.kt` (−8), `AiArtifactRepositoryImplTest.kt` (−6; keep :171, :227), `T/data/currency/ExchangeRateStoreAdapterTest.kt` (−3; barrier tests as a table); move :451 into `T/domain/util/TimePeriodUtilsTest.kt` | TA | −35 / +1 | Only the :451 case was real, and it moves. `HybridRouterIntegrationTest` covers the routing. **`CloudRetryPolicyTest` :13 is NOT deleted**: it is the retry-delay pin that B4-K2 depends on. | `TEST_FAILURE_LEDGER.md` F-09/F-14 note for `ExpenseStoreTest` | `com.yourname.expensetracker.data.*`, then `*TimePeriodUtilsTest` | T1 · cheap |
| B3-3 | data/backup and the transaction runner · vacuous tests | `T/data/backup/AppOperationalStateTest.kt` (delete; add one table test against the real `RestoreMaintenanceMode.operationalStateFlow` if nothing else covers it), `MaintenanceSafeDiagnosticSinkTest.kt` (−4, keep :48), `CostbackupBundleLimitsTest.kt` (−:313), `T/data/database/DomainTransactionRunnerTest.kt` (−7 fake-runner tests; `TransactionContext` and `CancellationSafe` become one table) | TA | −19 / +1 | Restore-state mapping is proven against the real flow | — | `com.yourname.expensetracker.data.backup.*`, then `*DomainTransactionRunnerTest` | T2 · RS (restore) |
| B3-4 | domain, money tests that copy the production formula | delete `T/domain/usecase/dashboard/DashboardProjectionSafetyTest.kt`; `T/domain/split/SplitCalculationPrecisionTest.kt` (−~20); `T/domain/logic/SplitCalculatorTest.kt` (−4 duplicates of Golden; + exact largest-remainder cases against the real class); `T/domain/tax/TaxCalculationTest.kt` (delete the `@Ignore`d, weak and local-formula tests; keep one config table); `T/domain/budget/P6BudgetCleanupTest.kt` (−5); `T/domain/budget/BudgetCalculatorBoundaryTest.kt` (+ a real P6-016 Sunday-anchor case) | TA | −55 / +5 | US brackets :226/:237 (the only proof), GR VAT 24 % and GR brackets stay as table rows. `TaxEstimatorTest` :182/:195/:330/:418 untouched. Check that a `daysElapsed==0` projection test exists in `FinancialRunwayNoBurnTest` or `ComputeDashboardWidgetsUseCasePaceWiringTest`; add one if not. | TEST_IGNORE_CLASSIFICATION rows 12–13; `GUARDRAIL_STATE_REPORT` §5.3.1 citation | `com.yourname.expensetracker.domain.*` | T2 · RS (money) |
| B3-5 | domain, root and golden · non-money tautologies and orphan helpers | `T/domain/dto/DtoContractTest.kt` (delete), `T/domain/util/SystemMonotonicTimeProviderTest.kt` (−2, keep :35), `T/domain/forecasting/HistoricalSpendingDistributionBoundaryTest.kt` (−5), `T/domain/intelligence/TransactionClassifierTest.kt` (−3, 3 merged into 1), `T/domain/intelligence/ConfidenceRouterTest.kt` (−:170), `T/TestUtils.kt` (dead helpers only), delete `T/util/HiltTestUtils.kt` and `T/golden/HiltGraphSmokeTest.kt` | TA | −18 | The T4C batches and `TimePeriodUtilsTest` :467/:527 cover the removed boundary cases | Note in `GOLDEN_TESTS_CI_GATE.md` (golden count changes) | `com.yourname.expensetracker.domain.*`, then `com.yourname.expensetracker.golden.*` | T1 · cheap |
| B3-6 | 12+ flaky wall-clock performance asserts | `T/domain/util/{MerchantCleaner,MerchantKeyGenerator,StatisticsUtils,StringDistanceUtils}StressTest.kt`, `T/domain/analytics/AnalyticsStressTest.kt` (keep the exact total, drop the time budget), `T/domain/logic/SynthesisEngineStressTest.kt` (1 site), `T/domain/location/SpendingHeatmapEngineStressTest.kt` (3, with fixed seeds), `T/domain/categorization/{ContextualInferenceEngine,CategorizationEngine}StressTest.kt` | TA | −~15 | Only timing asserts are removed; behavioural asserts stay | — | `*StressTest` | T1 · cheap |
| B3-7 | domain receipt, recurring and bank · dead tests | delete `T/domain/receipt/BitmapConcurrencyTest.kt`, the empty shells `T/domain/receipt/lifecycle/{ReceiptLifecycleBugFixes,ReceiptLifecycleHardening}Test.kt` and `T/domain/recurring/RecurringLifecycleFixesTest.kt`; `ReceiptLifecycleCoordinatorTest.kt` (−:1363); `T/domain/bank/BankApiIntegrationTest.kt` (−:278/:315) | TA | −17 | No production class is exercised by the deleted tests. Receipt cancellation proofs untouched. | TEST_IGNORE_CLASSIFICATION rows for the shells | `com.yourname.expensetracker.domain.*` | T1 · cheap |
| B3-8 | app-level ui and worker · dead tests | delete `T/ui/screens/budget/BudgetViewModelStressTest.kt`; `T/worker/NotificationIntakeWorkerTimeoutTest.kt` (−3 tests that only check kotlinx facts; keep :198, :408-747) | TA | −18 | Live coverage stays in `BudgetViewModelCurrencyProvenanceTest` and `AutopilotQualityTest` | TEST_IGNORE rows 21/133 | `*NotificationIntakeWorkerTimeoutTest` | T1 · cheap |
| B3-9 | orphan `.kts` scripts (decision 4) | delete `S/guards/check_direct_time_calls.kts`, `check_lifecycle_bypasses.kts`, `check_raw_money_aggregates.kts`, `T/guards/GuardSeededViolationTest.kt`; edit docs | FI | −4 | Nothing references the scripts (checked: no script or config hits). G-TIME-01 and db_access are the canonical guards. | `CI_GUARDRAILS_BASELINE.md:231`; `ARCHITECTURE.md` :102, :110, :292-293, :336, :1821; `CODEBASE_SEGMENTS.md` :247, :737-739, :746; `CODEBASE_INVENTORY.md:23`; `DEPENDENCY_MAP.md:1331`. Historical ledgers and plans are left alone. | `com.yourname.expensetracker.architecture.*`; `registered-guard guard_docs_truth` | T2 · cheap |
| B3-10 | integration and consistency suites | delete `T/integration/{ExpenseCreationPipeline,CategorizationPipeline}IntegrationTest.kt` (move 3–4 meaningful cases into util unit tests first); `T/integration/Curr587BehavioralTest.kt` (−11; move 3 `StaleRatePolicy` cases into a `domain/core/money` test); delete `T/consistency/TimePeriodAnalyticsAlignmentTest.kt` | TA | −50 / +7 | `StaleRatePolicy` cases are moved, not dropped | — | `com.yourname.expensetracker.integration.*`, then `*StaleRate*` | T2 · cheap |
| B3-11 | scenarios that make money claims | delete `T/scenarios/CsvExportImportRoundtripTest.kt` and `TransactionLifecycleDbContractTest.kt`; `CurrencyRateStalenessScenarioTest.kt` (drop T1/T3, move T2/T4 to `domain/currency`); `MulticurrencyPartialRateScenarioTest.kt` (−4 helper duplicates; T4 deleted or inverted, see decisions); `BackupRestoreMoneyIntegrityScenarioTest.kt` (delete T2/T3; delete T1 only if it is strictly covered by `MigrationRegistrationTest`, otherwise leave T1 in place, because `MigrationRegistrationTest` is pinned and not edited) | TA | −14 / +2 | Real contracts stay in `TransactionLifecycleCoordinatorDbContractTest`, golden CSV, `MoneyAggregateConversionScenarioTest`:464 | `docs/prompts/release-1.0-assurance/evidence-map.md:20` | `com.yourname.expensetracker.scenarios.*`, then `*Currency*` | T3 · RS (money) |

### B3b: revive valuable `@Ignore`d tests (time-boxed)

Rule for every B3b slice: at most 1 debug iteration on the hang. If it cannot be fixed, record DELETE plus the reason in TEST_IGNORE_CLASSIFICATION (fallback already approved).

| ID | Area · goal | Write set | Agent | −/+ | Proof kept | Docs updated in the same slice | Validation | Tier · review |
|---|---|---|---|---|---|---|---|---|
| B3b-1 | ui/home | `T/ui/screens/home/HomeViewModelStressTest.kt` → new `HomeViewModelWidgetsTest.kt` (widget reorder/visibility, planned expenses as a table, cloud-dashboard warning) | TA | −10 / +9 live | These are the only tests of widget reorder and planned expenses | TEST_IGNORE rows | `*HomeViewModel*` | T2 · cheap |
| B3b-2 | ui/review | `ReviewViewModelStressTest.kt` → `ReviewViewModelAiAssistTest.kt` (~16 tests; cloud stays DISABLED) | TA | −21 / +16 | Only AI-assist coverage | TEST_IGNORE | `*ReviewViewModel*` | T2 · cheap |
| B3b-3 | ui/transactions and addexpense | `TransactionsViewModelStressTest.kt` → `TransactionsViewModelPagingTest.kt` (5); `AddExpenseViewModelStressTest.kt` → 4 validation tests into `AddExpenseViewModelTest.kt` | TA | −15 / +9 | Only add-expense validation and paging coverage | TEST_IGNORE | `com.yourname.expensetracker.ui.*` | T2 · cheap |
| B3b-4 | ui, small view models | `AnalyticsViewModelStressTest` (2 year-over-year tests with `FakeTimeProvider`), `MainViewModelStressTest` (2), `DebugViewModelStressTest` (delete :213/:241; revive the rest or delete them as verify-only), `SpendingMapViewModelStressTest` (−3 ignored; 4 → 1 table; rename `SpendingMapViewModelHeatmapTest`) | TA | −17 / +6 | Year-over-year exact values | TEST_IGNORE | `com.yourname.expensetracker.ui.*` | T1 · cheap |
| B3b-5 | data/location | `CompositeGeocodingServiceStressTest.kt` → 8 tests merged into `CompositeGeocodingServiceTest.kt` | TA | +7 live | Only routing, dedupe and cancel coverage | TEST_IGNORE | `*CompositeGeocodingService*` | T1 · cheap |
| B3b-6 | notification intake | move :207 (retry row surfaces after `nextAttemptAt`) into `NotificationIntakeEnqueueFailureRoomTest.kt`; delete `T/domain/notification/capture/NotificationIntakeEnqueueFailureTest.kt` | TA | −3 / +1 | :207 is the only unique case | `release-1.0-verification-ledger.md:492` (RG17B-1) and R13 note; TEST_IGNORE | `*NotificationIntakeEnqueueFailure*` | T2 · RS |
| B3b-R | G-IGNORE-01 baseline lowered (tightening only) | `S/ci/guard_registry.py:315`, `S/ci/test_gradle_registered_guard_contract.py:190`, `S/ci/test_run_static_guard_suite.py:1585`, `.github/workflows/ci.yml:235` (comment), `docs/ci/GUARD_COMMANDS.generated.md:151`, `docs/testing/TEST_IGNORE_CLASSIFICATION.md` (remove stale `NotificationCaptureServiceStressTest`, `AnalyticsStateStressTest` and the "NIGHTLY" label; `ReceiptScanViewModelStressTest` is active) | FI | 0 | Denylist unchanged | as listed | `registered-guard ignored_test_budget` before the edit (take the exact "Current total" from its output) → edit → `registered-guard ignored_test_budget`, `registered-guard guard_docs_truth`, `pytest-subset` on each of the 2 Python files | T2 · RS (guard config) |

### B4: speed fixes, same proof

| ID | Area · goal | Write set | Agent | −/+ | Proof kept | Docs updated in the same slice | Validation | Tier · review |
|---|---|---|---|---|---|---|---|---|
| B4-P2 | Restructure the runner-protocol tests | `S/ci/test_validation_runner_protocol.py`: batch the argv matrices (473-666, 1038-1070) into one PowerShell call per matrix that returns per-case JSON; make the runner-copy fixture session-scoped. Each fail-closed case (malformed settings/lock, tampered request, unsafe filter) is still asserted on its own, by case id. | TA | 0 items | All fail-closed cases stay as separate assertions | — | `runner-protocol-tests` (baseline 373 s; target < 150 s) | T2 · RS |
| B4-P3 | **Conditional, needs your confirmation:** drop the runner-protocol file from the `guard_tests` leg (it is already `skipif(os.name!="nt")`, so Linux CI never runs it) | `S/ci/run_static_guard_suite.py:179` (add `--ignore=`), `S/ci/test_run_static_guard_suite.py:1716`, and if needed `S/ci/capture_db_guard_evidence.py` plus `test_capture_db_guard_evidence.py:4272`; VALIDATION_RUNNER.md rule: "runner changes require `runner-protocol-tests`" | FI | 0 | `REQUIRED_RECURSIVE_GUARD_TESTS` still collected | VALIDATION_RUNNER.md | `pytest-subset` on the touched test files; `static-guards` at the end of B4 | T3 · RS. Only if the file still takes > 120 s after B4-P2 |
| B4-P4 | `test_migrate_db_policy_signatures.py`: cache repeated identical CLI runs | `S/test_migrate_db_policy_signatures.py` only: session fixtures for byte-identical `_run_cli` argv; keep the 2 runs in `test_report_has_deterministic_ordering`; never merge tests whose argv or exit code differ | TA | 0 | File and test ids unchanged (pinned by `guard_registry` db_artifact_sync and by `REQUIRED_RECURSIVE_GUARD_TESTS`); read-only config assertions (1399-1412, 3286-3306) kept | — | `pytest-subset` on the file (compare with the B4-P1 measurement) | T2 · RS |
| B4-P5 | `known_good_state` reuses the db_access result | `S/ci/verify_known_good_state.py` (rows 1, 3, 5 reuse the stored db_access/migrate result only when the effective-inputs hash **and** git revision match; a missing, stale or unparseable result means a fresh run, never a pass from cache; keep the "21 × DB_SIGNATURE_UNRESOLVED" assertion; fix the docstring at 101-109), a new `S/ci/gate_result_cache.py` and its test, `S/ci/test_verify_known_good_state.py`, and `run_static_guard_suite.py` plus its test only if leg ordering must be declared | FI | +~6 | Fail-closed when the cache misses | `GUARD_COMMANDS.generated.md` if argv changes | `pytest-subset` on the 2 tests; `registered-guard known_good_state` | T3 · RS (guard-infra behaviour) |
| B4-P6 | `test_verify_db_access_v2` real-tree scan reuses the cache | `S/test_verify_db_access_v2.py` `_real_tree_scan` (1395-1416) uses the B4-P5 cache, otherwise scans once per session | TA | 0 | Registry-, capture- and `GUARD_EVIDENCE_INDEX`-pinned: names and tests unchanged | — | `pytest-subset` on the file | T3 · RS. Depends on B4-P5 |
| B4-K1 | `WriteBarrierArchitectureGuardTest` (after B1) | skip a file unless it contains the literal `".$method"` before running the regexes; read the file list and texts once (`by lazy`) | TA | 0 | Results identical (every regex requires that literal) | — | `*WriteBarrierArchitectureGuardTest` (97.6 s → < 10 s) | T2 · RS (guard) |
| B4-K2 | Cloud retry delay seam (production code) | `data/ai/provider/internal/CloudRetryPolicy.kt` plus the 4 Cloud*Service.kt files get an injectable delay function whose default is the current production delays (Hilt-compatible, no new public binding); tests in `T/data/ai/provider/Cloud{ReceiptAssist,QueryInterpretation,DashboardBriefing,CategorizationAssist}ServiceTest.kt` use a no-op delay and keep asserting retry counts; turn the 500/429/408 retries into tables | FI | −5 | Delay values stay pinned by `CloudRetryPolicyTest` :13 | — (cited classes keep their names: TEST_FAILURE_LEDGER:58/71, GR-14u57a.yml:126) | `*Cloud*`; `registered-guard cloud_payload`; `registered-guard di_release` | T2 · RS |
| B4-K3 | Geocoding virtual time | `T/data/location/GeocodingRetryHttpSemanticsTest.kt` (`runTest`; the 4 identical 429 tests become a table; assert `currentTime`), `OverpassNearbyServiceTest.kt` | TA | −3 | Stronger: backoff is now asserted | — | `com.yourname.expensetracker.data.location.*` | T1 · cheap |
| B4-K4 | P7 key-derivation loop | `T/data/backup/P7BugFixesTest.kt`: input that fails before key derivation ("Truncated input"), or 50 iterations → 3; add `verify { close() }` | TA | 0 | Stronger: stream closing is now actually asserted | — | `*P7BugFixesTest` | T2 · RS (backup) |
| B4-K5 | PBKDF2 iteration seam (crypto, production code) | `BackupEncryptionService.kt`: internal iteration count, default 600k, `@Inject` constructor unchanged, not reachable from DI; decryption must **not** take the iteration count from the bundle. Tests in `CostbackupBundleLimitsTest`, `BackupVerifierSavingsLedgerTest`, `BackupRestoreContractTest`, `BackupRestoreIntegrityE2ETest` use a low count; `BackupEncryptionServiceTest` keeps the 600k pin and the wrong-password test, and adds "the production instance uses 600k" | FI | +1 | Key-derivation pin kept | — | `com.yourname.expensetracker.data.backup.*`, then `com.yourname.expensetracker.data.privacy.*`, then `*BackupRestore*`; `registered-guard di_release` | T3 · RS + privacy guardian |
| B4-K6 | Remove Robolectric from pure-logic tests | `DefaultAiEnvironmentMonitorTest`, `T/domain/budget/BudgetForecastingEngineDiagnosticsTest.kt` (plain exception), `T/workers/WorkerContractTest.kt` (drop Robolectric; −:45 always true, −:67 duplicate; keep :75 parity), `NotificationCaptureServiceFgsTimeoutTest` (only if it needs no Android), `T/domain/debug/ServiceDiagnosticsTest.kt` (fake prefs; assert the result of `await` at :136; tables). No Room, restore or privacy classes. | TA | −8 | `WorkerContractTest` :75 kept | `ARCHITECTURE.md:1822` unchanged | `*WorkerContractTest`, `*ServiceDiagnosticsTest`, `*BudgetForecastingEngineDiagnosticsTest`, `*DefaultAiEnvironmentMonitorTest`, `*FgsTimeout*` | T1 · cheap |

### B5: table merges in low-risk areas

Rule for every table conversion: each input/expected pair stays a row, with the old test name as the row id.

| ID | Area · goal | Write set | Agent | −/+ | Proof kept | Docs updated in the same slice | Validation | Tier · review |
|---|---|---|---|---|---|---|---|---|
| B5-1 | domain/parser | `TransferDirectionDetectorTest` (4 tables; −4 at :386-445), `GenericTransactionParserStressTest` → `...Test`, `GreekBankParserStressTest` and `NBGReproTest` → `GreekBankParserTest` | TA | −47 | Every row kept | — | `com.yourname.expensetracker.domain.parser.*` | T1 · cheap |
| B5-2 | domain/receipt parsers | `ReceiptParserOcrPatternsTest` (4 tables; the `assertNotNull` at :90 becomes an exact assert), `ReceiptParserTest` (keep :25, :192, :254, :270), `ReceiptOcrTempNameTest` (4 → 2) | TA | −47 | Money parsing rows | — | `com.yourname.expensetracker.domain.receipt.*` | T2 · cheap |
| B5-3 | domain/categorization | `CategorizationEngineStressTest`, `ContextualInferenceEngineStressTest`, `SemanticKeywordMatcherStressTest` and `MerchantCanonicalizerStressTest` → `CategorizationComponentsTest` (tables) | TA | −41 | — | — | `com.yourname.expensetracker.domain.categorization.*` | T1 · cheap |
| B5-4 | domain/util A | `AmountUtilsStressTest` → table in `AmountUtilsTest` (and fix the Locale leak), `StatisticsUtilsStressTest` (~10 exact rows), `StringDistanceUtilsStressTest` (exact table; the emoji case at :179 kept as-is) | TA | −87 | Only proofs kept: malformed grouping, unicode minus, NBSP/NNBSP, `(50)`, `E50`, prefix/suffix symbols, 1,000,000; `AmountUtilsTest` :9 | `COVERAGE_REPORT.md`, `TESTING_ASSESSMENT.md` | `com.yourname.expensetracker.domain.util.*` | T2 · cheap |
| B5-5 | domain/util B | `MerchantKeyGeneratorStressTest` → `MerchantKeyGeneratorTest` (keep :52, emoji/tab/NUL/10k), `MerchantCleanerStressTest` → rename `MerchantCleanerTest` (tables), `NotificationIdGeneratorTest` (tables), `CancellationSafeTest` (2 rows) | TA | −45 | `MerchantCleaner` has no other test: rows kept | TEST_IGNORE row 14; `CI_GUARDRAILS_BASELINE.md:216` | `com.yourname.expensetracker.domain.util.*` | T1 · cheap |
| B5-6 | consistency, merchant-key family | `T/consistency/{MerchantKeyConsistency,CrossConsumer,SharedUtility,CrossParser,DuplicateLogic}*Test` → `MerchantKeyContractTest` | TA | −15 | `DedupeKeyProducerConsistencyTest` and `ConstantsConsistencyTest` untouched | — | `com.yourname.expensetracker.consistency.*` | T1 · cheap |
| B5-7 | ui/navigation | `NavigationRouteContractTest` (−16; keep :74, :80, :112, :190, :364, :373, :383), `FeatureConfigNavigationContractTest` (7 → 3), `NavigationControllerBehaviorTest` (3 → 1), `DeepLinkParserTest` (table), `DashboardWidgetRenderCoverageTest` (compare against `sealedSubclasses`), `T/service/NavigationTargetResolverTest` (2 tables) | TA | −38 | The two "all destinations" tests | `NAVIGATION_ARCHITECTURE.md:80-82` | `com.yourname.expensetracker.ui.*`, then `*NavigationTargetResolverTest` | T1 · cheap |
| B5-8 | service filters and serializers | `NotificationFilterTest` (keep :52/:106), `NotificationCaptureServiceFallbackTest`, `TransactionFilterSerializerTest`, `DataSyncFgsTimeoutGuardTest` | TA | −17 | Positive and negative filter rows kept | — | `com.yourname.expensetracker.service.*` | T2 · cheap |
| B5-9 | reminder workers and receivers | `T/service/reminder/{Snooze,Dismiss}*ReminderActionWorkerTest` → one parameterized class; `T/receiver/{Boot,ServiceRestart}ReceiverStressTest` → merged, renamed, with real assertions | TA | −4 | — | — | `com.yourname.expensetracker.service.reminder.*`, then `com.yourname.expensetracker.receiver.*` | T2 · RS + worker guardian |
| B5-10 | import and export | `T/util/{JsonExpenseImporter,CsvExpenseImporter,RoundtripContract,ImportCoordinator}Test`, `T/domain/export/{CsvEscaping,CsvCellSanitizerNegativeAmount}Test` (sanitizer table, IIF table, one format test) | TA | −28 | Every formula-injection prefix stays a row; `ExportImportRoundtripTest` (denylisted) untouched | — | `com.yourname.expensetracker.util.*`, then `com.yourname.expensetracker.domain.export.*` | T2 · RS (security) |
| B5-11 | AI routing and parsing | `HybridServiceDelegationTest`, `HybridDedupeJudgeServiceTest`, `SmartReceiptAssistServiceTest` → `HybridRoutingTest`; `OnDeviceCategorizationAssistServiceTest` (tables); `T/domain/ai/policy/AiPolicyTest`; `T/domain/ai/model/*` (5 files → 2) | TA | −30 | Cloud DISABLED stays: no cloud calls | — | `com.yourname.expensetracker.data.ai.*`, then `com.yourname.expensetracker.domain.ai.*` | T2 · cheap |
| B5-12 | AI error contract and payload (after B4-K2 and B5-11) | 8 OnDevice*/Cloud*ServiceTest files → a parameterized `AiServiceErrorContractTest` with one row per service; `CloudQueryPreparedPayloadTest` → an Entry in `CloudProviderTransportPayloadTest` | TA | −19 | Each service keeps its "no sentinel leak" and cancellation-propagation rows | — | `com.yourname.expensetracker.data.ai.*` | T3 · RS + privacy guardian |
| B5-13 | Recommendation services (after B2) | `RecommendationLifecycleManagerTest` (failure table includes B2's cancellation case; delete the OOM-swallow test), `RecommendationDismissalHandlerTest`, `RecommendationStateManagerTest` (gate the race at :189), `RecommendationCacheServiceTest` merged with `RecommendationDeduplicatorTest` | TA | −33 | Cancellation-rethrow row | — | `*Recommendation*` | T2 · cheap |
| B5-14 | data, duplicate DAO CRUD tests | 8 DAO tests (Investment, EmailReceipt, RawNotification, RecurringOccurrence, PrivacyAudit, ReceiptEvent, TransactionEvent, BackgroundJobRun) → one insert-and-read-back test per entity; `BankConnectionDaoTest` (fold into :153) | TA | −20 | Every purge-cutoff, ordering and unique-index case kept | — | `com.yourname.expensetracker.data.database.dao.*` | T2 · RS + privacy guardian (retention SQL) |
| B5-15 | data, small tables | `CategoryTest`, `ConvertersTest`, `ExpenseWithCategoryFormatted{Amount,Time}Test`, `MerchantNormalizationRepositoryTest`, `RecurringExpenseRepositoryTest`, `LogSanitizerTest`, `CategoryRepositoryTest` (assert the cache is invalidated), `AiChatRepositoryImplTest` and `SubscriptionManagementRepositoryTest` (assert outcomes) | TA | −25 | — | — | `com.yourname.expensetracker.data.*` | T1 · cheap |
| B5-16 | domain sideeffect, nlp, bank and location | `DiagnosticSideEffectEventWriterTest`, `SideEffectMetadataFactoryTest`, `PostCommitActionBatchTest` (→ 3), naturallanguage `DefaultWindowBoundaryTest`, `BankConnectionLifecycleCoordinatorOutcomeTest` (table), `SpendingHeatmapEngineStressTest` (tables) plus location Stress/Normalized renames | TA | −30 | Atomic-write rows in the bank coordinator test kept | — | `com.yourname.expensetracker.domain.*` | T2 · cheap |
| B5-17 | domain engines, low risk | `NegotiationEngineTest`, `BusinessExpenseReportGeneratorTest`, `MonteCarloSpendingSimulatorTest`, `FinancialHealthCalculatorTransactionTypeTest`, `CarbonFootprintCalculatorTest` (exact emission-factor table), `DashboardFollowThroughEngineTest` (exact fields) | TA | −35 | — | ai-fixes impact map citation | `com.yourname.expensetracker.domain.*` | T2 · cheap |
| B5-18 | ui, small view-model fixes | `ReceiptScanViewModelStressTest` → rename `ReceiptScanViewModelAiAssistTest` (table; reflection pokes kept), `PriceProtectionViewModelTest`, `CarbonFootprintViewModelTest` :102 | TA | −6 | — | ledger name note | `com.yourname.expensetracker.ui.*` | T1 · cheap |
| B5-19 | Python seed rows | `S/test_migrate_db_policy_seed_rows.py`: one parametrized `match_mutation` test (9 fields × mutate one, plus exact identity); −~135 per-batch copies; the ~29 real-tracked row-count tests become one parametrized table with an id per seed file | TA | −140 | Each row-count pin stays as an id; `test_candidate_signatures_rejected_by_v2_loader` and the closure/advisory tests kept | — | `pytest-subset S/test_migrate_db_policy_seed_rows.py` | T2 · RS |
| B5-20 | Python parametrize and trim | `S/test_verify_time_boundaries.py` (3 parametrize functions; `*_exit_2` ids kept), `S/test_verify_db_access_boundaries.py` (−~6 source-text tests at :5871, :5905, 6239-6310; the reviewer confirms each is not a protective gate), `S/ci/test_guard_findings.py` (param ids only), `S/db_guard/structural_analysis/test_tokenizer.py` (optional; never across fail-closed boundaries) | TA | −6 items | Hostile-echo, exit-2 and fail-closed cases kept | — | `pytest-subset` on each file (4 runs) | T2 · RS |
| B5-21 | scenarios, DAO CRUD without money (after B5-14) | `T/scenarios/{BankSync,LocationMap,EmailReceipt,InvestmentPortfolio,ReceiptPreOcrDedupe}*Test` → folded into per-DAO tests | TA | −15 | Non-CRUD assertions kept | — | `com.yourname.expensetracker.scenarios.*`, then `com.yourname.expensetracker.data.database.dao.*` | T2 · cheap |

### B6: money, time, privacy, workers, backup

Every B6 slice is T3 with reviewer-strict. The guardian for each slice is in its row.

| ID | Area · goal | Write set | Agent | −/+ | Proof kept | Docs updated in the same slice | Validation |
|---|---|---|---|---|---|---|---|
| B6-1 | TimePeriodUtils family · time guardian | `TimePeriodUtilsValidationTest` (−45; zone-parameterized, no reliance on the default zone), `TimePeriodUtilsStressTest` (−15), `TimePeriodUtilsTest`, the T4C batches 1/2A/2B/2C/2D (merge pairs), `T/metrics/TimePeriodAlignmentTest` | TA, −77 | T4C DST 23/25 h, last-ms-exclusive, cutover, extremes; `TimePeriodUtilsTest` :376/:393 | GR00-GR04 findings (T4C names) | `*TimePeriod*` |
| B6-2 | Exact money assert and Totals · money guardian | `T/TestUtils.kt` (+ an exact `assertMoneyEquals`; fix the mojibake), the 5 `TotalsAggregationEngine*` files → 4 tables; Deep test made exact (MONTH average 133.33) | TA, −25 / +1 | :393-452 fail-closed, cancellation, StatusBaseline | — | `*TotalsAggregation*` |
| B6-3 | SpendingPace, Insights, AdvancedAnalytics · money | the 4 `SpendingPace*` files, `InsightsEngine*`, `AdvancedAnalyticsEngine*` | TA, −21 | Pace Deep :26; unavailable-not-zero | — | `com.yourname.expensetracker.domain.analytics.*` |
| B6-4 | Budget windows · time and money | `BudgetCalculator{,Boundary,TimeBoundary,Golden}Test`, `BudgetMonitorStressTest` → `BudgetMonitorTest` | TA, −18 | TimeBoundary :224/:244; Golden | `TIME_SEMANTICS.md:503`; TEST_FAILURE_LEDGER:32 | `com.yourname.expensetracker.domain.budget.*` |
| B6-5 | `SynthesisEngineStressTest` · money | 1 file: −33 smoke tests; frequency tests become a table; 8 tests converted to exact `components.*` asserts | TA, −33 | The 10 tests that already assert stay | — | `*SynthesisEngine*` |
| B6-6 | Currency · money | `T/domain/core/money/` and `T/domain/currency/CurrencyNormalizationBehavioralTest` merged (also fixes the ambiguous `--tests` match); `CurrencyConversionTest` (−5 at :340-447, merged with Golden); delete `CurrencyConverterStressTest`; `ConversionSemanticsHardeningTest` and `T/domain/util/CurrencyFormatterExportSafetyTest` as tables | TA, −30 | Every non-finite case (6 functions × 3 values) is a row; `MoneyTest` untouched | — | `*Currency*` |
| B6-7 | Groups and metrics · money | `GroupBalanceCalculatorTest` and `GroupSettlementBalancePolicyTest` (the policy test owns the rules), `SharedExpenseManagerTest` and `GroupUseCasesTest`, `T/metrics/{EffectiveAmountConsistency,GoldenAnalyticsDataset,DashboardWidgetConsistency}Test` | TA, −11 | `DashboardWidgetConsistency` kept | — | `com.yourname.expensetracker.domain.groups.*`, then `com.yourname.expensetracker.metrics.*` |
| B6-8 | Raw-storage payload matrix · privacy | new `RawStoragePayloadMatrixTest`; delete `RawStoragePolicyAuditTest` (move the email case at :147); shrink `ReceiptOcrEmailStorageHardeningTest`, `RawStorageEndToEndTest`, the payload part of `BankPrivacyHardeningTest`, the sanitize part of `PR5PrivacyContractTest` | TA, −32 / +1 | Static-guard half of `RawPersistencePolicyConsolidationTest` untouched; every source × mode is a sentinel row | `raw-storage-policy.md:135` | `com.yourname.expensetracker.domain.privacy.*` |
| B6-9 | Privacy defaults and policy duplicates (after B2) · privacy | `RawPersistencePolicyTest` (−6), `PR5PrivacyContractTest`, `UPR5CompletionTest` and `PrivacyGuardTest` defaults and policy tables; delete `PrivacyCapabilityHandlingPolicyTest`; `P8PrivacyFixesTest` (−3); `T/data/privacy/KeystoreInstallationSecretHashingTest` (move the PII corpus to `CloudPayloadRedactorTest`; −:353) | TA, −20 | `...PolicyProductionTest`, `PrivacyCapabilityPolicyTest`, fail-closed hasher tests | `SENSITIVE_DIAGNOSTICS_POLICY.md:106`, `TEST_INDICATED_PRODUCTION_BUGS.md:134`, `PRIVACY_UI_ARCHITECTURE.md:104-105` | `com.yourname.expensetracker.domain.privacy.*`, then `com.yourname.expensetracker.data.privacy.*` |
| B6-10 | Retention · privacy and worker | `RetentionPerimeterTargetTest`, `RetentionTargetPurgeTest`, `DataRetentionWorkerTest` | TA, −11 | At least one cutoff test per target; GR-14u51b.yml:38 statement stays true | GR-14u51b.yml note if needed | `*Retention*` |
| B6-11 | Capture privacy (after B2) · privacy | `T/ui/screens/{backup,export,review,map}/*PrivacyDenialTest`, `NotificationCaptureServiceDeferredPolicyTest`, `NotificationCaptureServiceCleanupTest` (delete the reflection tests only once DeferredPolicy is shown to prove the NonCancellable hand-off) | TA, −14 | "Permitted leaves null" and malformed-reason rows; DeferredPolicy :260, :304-368, :382-407, :449-522, :558-568; Cleanup :115 | Ledger :376 / WAVE-1-STATUS | `*PrivacyDenialTest`, then `*NotificationCaptureService*` |
| B6-12 | Diagnostics sanitizer (after B2's real SafeSink test) · privacy | `T/diagnostics/{DDL512Regression,DurableDiagnosticsA8Regression,GlobalDurableDiagnosticsGolden,DurableDiagnosticsAcceptance,DurableDiagnosticsRegression}Test` → new `EventMetadataSanitizerTest` | TA, −42 / +1 | DDL512 :132/:381, A8 :98/:205/:215, Acceptance :135-176, Regression :38/:46 | `SENSITIVE_DIAGNOSTICS_POLICY.md:109` | `com.yourname.expensetracker.diagnostics.*` |
| B6-13 | Worker core A (after B2) · worker | `WorkerExecutionGuardTest` (outcome × policy × entry point; all 24 combinations stay rows; class name kept because it is denylisted, and no `@Ignore`), `WorkerRunLoggerTest`, `P9RemainingWorkerFixesTest`, `WorkerIdempotencyTest` | TA, −45 | WEG :471/:1034/:1157; P9 :200; B2's :207 fix | evidence-map :18 | `com.yourname.expensetracker.domain.workers.*` |
| B6-14 | Worker core B (after B2) · worker | `FileWorkerTerminalDiagnosticSinkTest` (one JSON key allow-list test; virtual-time timeout), `WorkerLeaseRegistryTest`, `WorkerRestoreRegressionTest` (−4 at :289-365), `WorkerGuardVerifierTest` | TA, −14 | "Cancelling drain propagates"; `WorkerBarrierIntegrationTest` untouched | `SENSITIVE_DIAGNOSTICS_POLICY.md:111` | `com.yourname.expensetracker.domain.workers.*` |
| B6-15 | App-level workers · worker | `DailyBriefingWorkerTest`, `ReceiptMatchingWorkerTest` (denylisted, class kept; −:184), JVM `MerchantKeyBackfillWorkerTest` vs `ExpenseRepositoryMerchantKeyBackfillTest` | TA, −15 | RMW :437/:269/:295/:320; repository CAS tests | `WORKER_BASELINE_INVENTORY.md:12` | `*DailyBriefingWorkerTest`, `*ReceiptMatchingWorkerTest`, `*MerchantKeyBackfill*` |
| B6-16 | Coordinator matrices · lifecycle | `TransactionLifecycleCoordinatorUpdateTest`, `TransactionSideEffectPlannerTest`, `ReceiptSideEffectPlanner*` (5 files → 1–2, every test kept), `BankApiIntegrationTest` :291 (assert the review path or rename) | TA, −24 | Collisions and CA-P-02-003; `TransactionLifecycleCoordinatorTest` untouched | — | `com.yourname.expensetracker.domain.transaction.*`, then `com.yourname.expensetracker.domain.receipt.*` |
| B6-17 | Notification pipeline · lifecycle | `T/data/repository/NotificationProcessingPipeline{,Reliability,OversizedAmount,Atomicity,SourceLink}Test` | TA, −7 | All atomicity cases kept | — | `*NotificationProcessingPipeline*` |
| B6-18 | Repository money and atomicity tests · money and lifecycle | `BudgetRepository{Stress,Truncation,HistoricalStatus,Validation}Test`, `ExpenseRepositoryTruncationTest`, `ReviewQueueRepositoryNestedRollbackTest` (entrypoint × scenario; keep :563/:592), `SavingsSweepAcceptanceCoordinatorTest` | TA, −36 | Every nested-rollback case is a row | evidence-map (SavingsSweep) | `com.yourname.expensetracker.data.repository.*` |
| B6-19 | Group coordinator leftovers (after B2) · lifecycle | `GroupTransactionCoordinatorTest` (fold :477 into :296; give :512 an assertion; RG04A-1 once), `GroupsRepositoryImplTest` | TA, −4 | :255 rollback; B2's fault-injection test | GR-14u36.yml:18 (class kept) | `*GroupTransactionCoordinatorTest`, then `*GroupsRepositoryImplTest` |
| B6-20 | Backup split A · backup | `DatabaseBackupRepositoryImplTest` → 6 files by concern (moved, not reduced; −7 pairs) | TA, −7 | The source scan at :2798 is kept, in its new location | `docs/time/time-boundary-guard.md:250`, `docs/development/TIME_SEMANTICS.md:291`, ledger note | `com.yourname.expensetracker.data.repository.*` |
| B6-21 | Backup split B · backup | `P7BugFixesTest` → `RestoreMaintenanceModePersistenceTest` + `CostbackupBundleExtractFailureTest`; `CostbackupBundleLimitsTest` → `CostbackupBundleTest` | TA, −8 | The stream-close test from B4-K4 | ledger :1138-1266, evidence-map | `com.yourname.expensetracker.data.backup.*` |
| B6-22 | Barriers (after B1) · backup plus validation config | `DatabaseBarrierTest` absorbs `ExportReadBarrierTest` as a (mode, policy) table; the Robolectric case at :89 isolated; `config/validation/known-hanging-tests.json` HANG-003 retargeted or retired, with evidence | TA, −14 | — | TEST_FAILURE_LEDGER F-14/F-16 | `*Barrier*` |
| B6-23 | Verifier, journal, encryption (after B4-K5) · backup | `BackupVerifier{Manifest,RequiredSemanticQuery,SavingsLedger}Test` → `BackupVerifierTest`; `RestoreJournalTimeProviderTest` → `RestoreJournalTest`; `BackupEncryptionServiceTest` merges | TA, −7 | Key-derivation pin :96 and wrong-password test | evidence-map | `com.yourname.expensetracker.data.backup.*`, then `*BackupEncryptionServiceTest` |
| B6-24 | Move `BackupRestoreContractTest` · backup | `T/scenarios/BackupRestoreContractTest` → `T/data/backup/` (move only; "59 table names" becomes a check against the real table list) | TA, 0 | Moved, not reduced | ledger path note | `*BackupRestoreContractTest` |
| B6-25 | Verification and e2e parity · money | verification `CrossSource`/`GoldenMaster`/`CrossGroupIntegration` → one parity matrix (GoldenMaster duplicates of itself removed; numeric oracles kept); the 5 e2e Flow tests → `PipelineWiringParityTest` (fix the self-equality at :41/:47); relabel `NotificationExpenseDashboardPipelineTest` | TA, −35 | Numeric oracles | — | `com.yourname.expensetracker.verification.*`, then `com.yourname.expensetracker.e2e.*` |
| B6-26 | Recurring trio and lifecycle scenarios · lifecycle | golden `RecurringBillPaymentMatchTest`, `RecurringPlannedActualNoDoubleCountGoldenTest`, e2e `RecurringPaymentMatchE2ETest` (reviewer picks the tests with pinned numbers); `T/scenarios/{GroupSettlementLifecycle,SharedExpenseGroup,RecurringNoDoubleCount,ReceiptLifecycleDbContract}*` → per-DAO tests | TA, −20 | `ReceiptMatchingNoDoubleCountGolden`, `RuleDeactivationCleanup` and `ConcurrentOccurrenceClaim` untouched; event-log and link-orphan cases kept | evidence-map.md:17; `GOLDEN_TESTS_CI_GATE.md` | `com.yourname.expensetracker.golden.*`, then `com.yourname.expensetracker.scenarios.*` |

### B7: remove duplicates between Kotlin scanners and Python guards (guard change)

Every B7 slice is T3 with reviewer-strict. Before deleting anything, the slice attaches a parity table that maps every removed rule to its Python fixture.

| ID | Area · goal | Write set | Agent | −/+ | Proof kept | Docs updated in the same slice | Validation |
|---|---|---|---|---|---|---|---|
| B7-1 | Contract scanners | delete `T/contracts/MoneyContractTest.kt` (G-MONEY-RAW-02) and `RecurringDeactivateContractTest.kt`; `PrivacyStorageContractTest` (−T3 only); `LifecycleBarrierContractTest` only if the parity table is complete, otherwise kept | TA, −9 | `SideEffectContractTest`, PrivacyStorage T1/T2, golden `RuleDeactivationCleanupTest` | `CI_GUARDRAILS_BASELINE.md:215-232` | `com.yourname.expensetracker.contracts.*` |
| B7-2 | Architecture scanners (after B1) | `Engine5PrimitiveGuardTest` (−wall-clock rule; keep :57 and :127); `RawDaoArchitectureGuardTest` (only with per-method `db_ownership_policy` parity); `SourceScanningArchitectureGuardTest`: inline negatives routed through `scanSource`, −2020 test, −:267 duplicate, **add** non-empty asserts at :292, :367, :392, :438, :463, and handle the dead 2026-09-30 dates at :555-587 if B1 did not | TA, −8 / +5 asserts | SourceScanning's unique worker and privacy capability rules | `SENSITIVE_DIAGNOSTICS_POLICY.md:100` | `com.yourname.expensetracker.architecture.*` |
| B7-3 | `MoneyBoundaryGuardTest` | port any missing fixture into `S/test_verify_money_boundaries.py`, then delete `T/guard/MoneyBoundaryGuardTest.kt` | TA, −11 | The money guard leg and its pytest | CI_GUARDRAILS_BASELINE | `pytest-subset S/test_verify_money_boundaries.py`; `registered-guard money`; `com.yourname.expensetracker.guard.*` |
| B7-4 | Shrink `DbGuardPolicyFixtureTest` | `T/guard/DbGuardPolicyFixtureTest.kt` (map all 73 cases; port missing structural checks to `S/test_verify_db_access_v2.py` or `S/ci/test_gradle_db_guard_contract.py`; retire the cases that only check pinned counts); **`config/guards/db_ownership_policy*.yml` is not edited** | TA, −45 / +~5 | Structural checks, now in Python | `GR00-GR04_validation_checklist.md:99/105` | `*DbGuardPolicyFixtureTest`; `pytest-subset` on each touched Python file |
| B7-5 | Single CancellationSafety allowlist (after B1 and B2) | `CancellationSafetyArchitectureGuardTest` reads the Python cancellation YAML (read-only) for the rules both guards share, and keeps its own list only for rules that exist only in Kotlin (sanitizer evidence forms, the `launch` rule); `CancellationPropagationContractTest` entry points merged in, its size test deleted. **No entries are added to the YAML.** | TA, −3 | Kotlin-only rules | `CANCELLATION_POLICY.md`, `CANCELLATION_ATOMICITY_BASELINE.md`, `CI_GUARDRAILS_BASELINE.md:217`, ledger notes | `*CancellationSafetyArchitectureGuardTest`, then `com.yourname.expensetracker.contracts.*`; `registered-guard cancellation` |
| B7-6 | WriteBarrier exemptions read from YAML (after B1 and B4-K1) | `WriteBarrierArchitectureGuardTest` reads exemptions from `db_ownership_policy.yml` `barrierMode` entries (read-only); exemptions with no YAML entry stay inline with owner and expiry | TA, 0 | — | `CI_GUARDRAILS_BASELINE.md:220`, `ARCHITECTURE.md:112` | `*WriteBarrierArchitectureGuardTest`; `registered-guard db_access` |

### B8: androidTest

Every B8 slice is reviewer-strict with the Room guardian.

| ID | Area · goal | Write set | Agent | −/+ | Proof kept | Validation | Tier |
|---|---|---|---|---|---|---|---|
| B8-1 | Device tests that duplicate JVM tests | delete `AT/data/database/dao/{ExchangeRate,Recommendation}DaoTest.kt` and `AT/data/location/MerchantKeyBackfillWorkerTest.kt`, after comparing case by case with the JVM twins (any device-only case is ported to the twin first) | TA | −11 | JVM twins; `ARCHITECTURE.md:134` checked against the JVM path | `com.yourname.expensetracker.data.database.dao.*`, then `*MerchantKeyBackfillWorkerTest` | T2 |
| B8-2 | `DatabaseMigrationTest` (decision 3) | `AT/data/database/DatabaseMigrationTest.kt`: delete the 9 skipped tests (assumeTrue at :211, :504, :527, :552, :567, :590, :621, :649, :852) and helpers used only by them; fix the stale KDoc at :219. The class is kept (release_block_denylist :14). | TA | −9 (never ran) | All other device migration tests untouched; JVM `*Migration*` tests unchanged | `connected-test-class com.yourname.expensetracker.data.database.DatabaseMigrationTest`; `migration-tests`; `registered-guard migration_matrix`; `registered-guard ignored_test_budget` | T3 |
| B8-3 | `DaoStressTest` | wall-clock thresholds replaced by correctness checks (counts, ordering, no lost writes); concurrency kept | TA | 0 | Concurrency semantics | `connected-test-class com.yourname.expensetracker.data.database.dao.DaoStressTest` | T2 |
| B8-4 | Move expense-core DAO tests to the JVM, plus a device smoke set | `ExpenseDaoTest`, `ComplexQueryTest`, `DedupeKeyUniquenessRegressionTest` move from `AT/…/dao/` to `T/…/dao/` (Robolectric sdk 28, `ApplicationProvider`, in-memory Room); + a real half-open date-boundary test against the in-memory `ExpenseDao`; new `AT/…/dao/DaoDeviceSmokeTest.kt` with ~5 cases | TA | 0 / +6 | Dedupe unique index; `insertAtomic` | `com.yourname.expensetracker.data.database.dao.*`; `connected-test-class com.yourname.expensetracker.data.database.dao.DaoDeviceSmokeTest` | T3 |
| B8-5 | Move budget, savings, recurring and category DAO tests | `Budget`, `SavingsGoal`, `RecurringExpense`, `Category`, `MerchantCategory` DaoTests (51 tests) | TA | 0 | — | `com.yourname.expensetracker.data.database.dao.*` | T3 |
| B8-6a | Move groups and review DAO tests | `GroupMember`, `ExpenseGroup`, `UserCorrection`, `PendingReview` DaoTests | TA | 0 | — | same filter | T2 |
| B8-6b | Move merchant, receipt and warranty DAO tests | `MerchantNormalization`, `MerchantLocation`, `ScannedReceipt`, `Warranty` DaoTests | TA | 0 | — | same filter | T2 |
| B8-7 | Move AI DAO tests | `AiArtifact`, `AiChatMessage`, `AiChatSession` DaoTests | TA | 0 | — | same filter | T2 |

### B9: retire unwired Python tooling (decision 5)

The whole B9 diff gets reviewer-strict.

| ID | Area · goal | Write set | Agent | −/+ | Proof kept | Docs updated in the same slice | Validation | Tier |
|---|---|---|---|---|---|---|---|---|
| B9-1 | Policy v3 loader | delete `S/db_guard/policy_v3_loader.py`, `policy_v3_model.py`, `S/test_policy_v3_loader.py` | FI | −15 | The v2 loader is untouched | PR-GR-15 note | `pytest-subset S/db_guard`, then `pytest-subset S/test_db_guard_policy_v2.py` | T2 |
| B9-2 | Triage tool | delete `S/ci/build_db_finding_triage.py`, `S/ci/test_db_finding_triage.py` | FI | −105 | No config or registry consumer (checked) | Retirement banner on `docs/ci/DB_TRIAGE_PROCEDURE.md` and `DB_ACCESS_V2_TRIAGE_SCHEMA.md` | `registered-guard guard_docs_truth` | T2 |
| B9-3 | Promote and v2-evidence CLIs (after B5-19) | delete `S/ci/promote_db_policy_v2.py`, `verify_db_policy_v2_evidence.py` and their 2 tests; move `_collect_accounting_mutation_keys` into `S/test_migrate_db_policy_seed_rows.py` | FI | −39 | Seed-row tests still run | Historical ledgers left alone | `pytest-subset S/test_migrate_db_policy_seed_rows.py` | T2 |
| B9-4 | Subsumption proof | delete `S/test_lifecycle_scanner_subsumption.py` | FI | −6 | Status stays SUBSUMED_AND_RETIRED | `docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md` :32/:69/:264/:274 marked "historical proof, retired" | `registered-guard guard_docs_truth` | T2 |
| B9-5 | Mediation prover A | new `S/db_guard/test_transparent_inline_methods_pin.py` (the `PRODUCTION_TRANSPARENT_INLINE_METHODS` closed-set pin, taken from `test_gr14f` :30-75); delete `S/ci/test_gr14{f,j,l,r,u5,u6,u7,u12,u15,u18}_*.py` | FI | −~65 / +2 | The constant used by `direct_barrier_bridge.py:49` stays pinned | — | `pytest-subset S/db_guard` | T3 |
| B9-6 | Mediation prover B | delete `S/ci/test_gr14{u20,u21,u23,u24,u25,u26,u27,u45,u52,u57b}_*.py` and `test_gr15_acceptance_gate.py` | FI | −~75 | — | — | `pytest-subset S/db_guard` | T3 |
| B9-7 | Mediation prover C | delete `S/ci/inspect_db_mediation_proof.py`, `S/ci/test_inspect_db_mediation_proof.py`, `S/ci/generate_gr13_unproven_writers.py`, and `S/db_guard/mediation_analysis/{proof,worker_recognition,fixture_runner,test_fixtures,test_worker_dispositions}.py`. **Kept:** `mediation_analysis/__init__.py`, `callgraph.py` (with `models.py`, which it imports), `test_models.py`, `test_parser_spans.py` | FI | −~60 | db_access still imports `callgraph` | Retirement notes in `GUARDRAIL_STATE_REPORT.md`, `GUARDRAIL_ISSUES_LEDGER.md`, `DEFERRED_ISSUES_AND_DEBUG_HANDOFF.md` | `pytest-subset S/db_guard`; `registered-guard db_access` | T3 |

**Kept out of B9:**
- `test_gr14u16_barrier_scope_form.py` and `test_gr14u28_bound_scope.py` import nothing from the mediation stack, so they probably test the active barrier proof.
- `test_inspect_db_structural_model.py` and `test_inspect_direct_write_barrier_proof.py` are active and cited.

### 3. Rough totals (estimates)

| Batch | Effect |
|---|---|
| B3 | about −410 tests, ~+20 |
| B3b | about −66 dead, +~55 live |
| B4 | static-guards about −6 to −9 min; unit CPU about −150 s |
| B5 | about −750 |
| B6 | about −590 |
| B7 | about −76 |
| B8 | −20 device tests; ~200 tests moved to the JVM |
| B9 | about −365 Python items |

### 4. Validation schedule

Everything goes through the validation-runner, one run at a time. RUNNING is not failure. STALE, missing or errored results are never PASS: rerun in a quiet tree.

1. **B4-P1:** `runner-protocol-tests`, then `pytest-subset scripts` (measurement only).
2. **B3-1 … B3-11:** the targeted runs listed in each row; B3-9 also `registered-guard guard_docs_truth`.
3. **B3b-1 … B3b-6:** targeted runs.
4. **B3b-R:**
   - `registered-guard ignored_test_budget` (read the count)
   - edit
   - `registered-guard ignored_test_budget`
   - `registered-guard guard_docs_truth`
   - `pytest-subset` ×2
5. **B4:**
   - P2: `runner-protocol-tests`
   - P4: `pytest-subset`
   - P5: `pytest-subset` ×2, then `registered-guard known_good_state`
   - P6: `pytest-subset`
   - K1–K6: targeted runs plus the listed registered guards
   - P3, if confirmed: `pytest-subset` on its test files
   - Gate: **`static-guards`**
6. **B5:** targeted runs; `pytest-subset` for B5-19 and B5-20.
7. **B6:** targeted runs, in table order.
8. **B7:**
   - B7-1, B7-2: targeted runs
   - B7-3: `pytest-subset`, `registered-guard money`, targeted run
   - B7-4: targeted run, `pytest-subset`
   - B7-5: targeted runs, `registered-guard cancellation`
   - B7-6: targeted run, `registered-guard db_access`
   - Gate: **`static-guards`**
9. **B8:**
   - B8-1, B8-3: targeted / connected-test-class
   - B8-2: connected-test-class, `migration-tests`, `registered-guard migration_matrix`, `registered-guard ignored_test_budget`
   - B8-4 … B8-7: targeted runs (B8-4 also its connected-test-class)
   - Gate: **`connected-tests`** (record the time vs 16.3 min), **`migration-tests`**, `registered-guard ignored_test_budget`
10. **B9:** `pytest-subset` and `registered-guard` per slice.
11. **Final:** **`unit-tests`**, then **`static-guards`**.

### 5. Real risks and open questions

**Human decisions required at the plan gate:**
1. **B4-P3:** drop the runner-protocol file from `guard_tests`? This changes what static-guards runs locally (CI is unaffected because the file only runs on Windows). The default is to do it only if B4-P2 leaves the file above 120 s.
2. **B4-P5/P6:** approve reusing db_access results across processes, keyed on the inputs hash plus git revision and fail-closed on any miss. This changes how the guard infrastructure behaves.
3. **B7-5/B7-6:** how the Kotlin tests read the YAML.
   - Is a YAML library already on the test classpath? A new test dependency is a build change and needs your OK.
   - The alternative is a minimal parser, which is the very pattern B7-4 removes.
   - Confirm the direction: Kotlin reads the shared rules from the YAML, and nothing is ever added to the Python YAML, because that would weaken G-CANCEL and db_access.
   - The per-entry diff will be shown at this gate.
4. **B8 device smoke set:** which ~5 cases stay on device. Proposed: `insertAtomic` round trip, a dedupe unique-index conflict, one ComplexQuery aggregate, one query that depends on collation or date functions.
5. **B9 scope:**
   - Include `generate_gr13_unproven_writers.py`? Decision 5 does not name it, but it reads the shadow report of the CLI being retired.
   - Confirm that `test_gr14u16` and `test_gr14u28` stay.
6. **Owner calls:**
   - `MulticurrencyPartialRateScenarioTest` T4 currently pins a forbidden raw mixed-currency sum. Delete it (default) or invert it against real code?
   - The `StringDistanceUtilsStressTest` :179 emoji false-positive pin: kept as-is.
   - `HomeViewModelCurrencyReloadTest`: left untouched.
7. **B2 boundaries:** confirm that B2 deletes or fixes `IncludeDepositsForBehaviorCleanupTest`, the vacuous parts of `PrivacyBehavioralRegressionTest`, and `NotificationPrivacyHardeningTest`. B6-12 stays blocked until B2 lands the real `SafeSinkOperationRunHandle` test.

**Risks:**
- **Audit conflict, resolved.** A2 wanted to delete `CloudRetryPolicyTest` :13, but it pins the retry delays that B4-K2 relies on. It is kept.
- **B4-K5 crypto.** A downgrade attack is possible if decryption ever trusts a bundle-supplied iteration count. The reviewer must also check the Hilt constructor shape.
- **B4-K2.** The DI change can trip `di_release` or `cloud_payload`; both registered guards are scheduled.
- **B8 SQLite differences.** Robolectric uses host SQLite, which can behave differently from device SQLite (functions, collation). That matters most for `ComplexQueryTest`.
- **B8 costs.**
  - ~200 Robolectric+Room tests move into the JVM run, adding roughly 40–80 s of unit CPU. That eats part of B4's savings.
  - The connected-tests saving is unmeasured.
  - There is no profile that only compiles androidTest, so a device is required.
- **B3b-R.** The baseline must equal the exact current count read from the guard's output; a number that is too low goes red. Five files carry the number.
- **B7-4.** `db_ownership_policy*.yml` is sync- and hash-pinned, so its stale comments (:969/:1003) are deliberately left alone.
- **B9 lost regression coverage.** `callgraph.py` (~2.7k lines) stays only because `direct_barrier_bridge` imports its constant. It loses its GR-14 regression tests, and only the constant pin remains. Moving the constant out of it is deferred.
- **Release-block denylisted classes.** `WorkerExecutionGuardTest`, `ReceiptMatchingWorkerTest` and `DatabaseMigrationTest` are edited in place: names kept, no `@Ignore`. `TransactionLifecycleCoordinatorTest`, `MoneyTest`, `ExportImportRoundtripTest` and `RecurringExpenseEngineTest` are not touched.
- **Worktree.** Concurrent sessions in the same worktree will make runs STALE.

**Deferred to later increments:**
- Deduplicating the migration-chain tests (`MigrationRegistrationTest`, `Migration148to149Test`, `DatabaseMigrationProofTest`). They are pinned, and PROPOSAL says migration tests stay untouched.
- Standardising Robolectric SDK levels; sharing one Room DB per class.
- Running guard legs concurrently, or xdist (a new package).
- Reflection-free test hooks for the AssistantViewModel and ReceiptScan view models (production change).
- Deleting the `RecurringArchitectureGuardTest` change-detector tests (not in the approved B7 list).
- Moving the verification Carbon/Lifestyle/SharedExpense tests.
- Reviewing `MultiCurrency*` and `WarrantyTrackerRepositoryTest`.
- Moving `PRODUCTION_TRANSPARENT_INLINE_METHODS` out of `callgraph.py`.

Key files:
- `C:\Users\panos\Desktop\Cost-agregator\docs\audits\test-suite-audit\PROPOSAL.md`
- `C:\Users\panos\Desktop\Cost-agregator\workflows\active\test-suite-audit-handoff.md`
- `C:\Users\panos\Desktop\Cost-agregator\scripts\validation-runner.ps1` (profiles at lines 541–622)
- `C:\Users\panos\Desktop\Cost-agregator\scripts\ci\guard_registry.py:315` (G-IGNORE-01 `--baseline 29`)
- `C:\Users\panos\Desktop\Cost-agregator\scripts\ci\capture_db_guard_evidence.py:2915`
- `C:\Users\panos\Desktop\Cost-agregator\config\release_block_denylist.yml`
## B2 re-verification on 8671b844 (scout, 2026-10-16) — corrections applied to the plan

No-touch refresh (git status, orchestrator): release worktree clean; release-b edits only PrivacyAuditLoggerImpl + its coalescing test. No B2 write-set file is touched by release work. Release commits changed CompositePrivacyGate/PrivacyAudit* only — no B2 target already fixed.

- **B2-1** READY. No production seam needed: DataStore file is `<filesDir>/datastore/privacy_settings.preferences_pb` (`PrivacySettingsRepositoryImpl.kt:43-48`); write garbage before first access, fresh context/file per test; mock WorkManager as in `PrivacySettingsRepositoryImplWorkerGatingTest.kt:48-55`. Assert `getLoadState()` (:101) / `observeLoadState()` (:85) → `CorruptedFailClosed` + `FAIL_CLOSED_DEFAULTS`. Also delete constant-only tests :62, :67, :72, :122 and :176.
- **B2-2** CORRECTED: production wires `domain.notification.capture.NotificationCaptureGate` (`NotificationCaptureService.kt:151`; ctor `NotificationCaptureGate.kt:37-42`, `warmUp()` :73). New test goes to `…\test\…\domain\notification\capture\NotificationCaptureGatePrivacyContractTest.kt`; filter `com.yourname.expensetracker.domain.notification.capture.*` then `*NotificationCapture*`. No real gate test exists today (service tests mock it). PrivacyGuardTest silent-pass sites: :34, :95, :114, :270, :295. `domain\privacy\NotificationCaptureGate.kt` looks dead (docs-only refs) — do NOT delete here; flag. Do not edit no-touch docs (ARCHITECTURE.md, DEPENDENCY_MAP.md).
- **B2-3** CORRECTED: :255 is NOT a real rollback proof (mock DAOs, relaxed runner) → keep only as a propagation test. New test: real in-memory AppDatabase (setup :109), member DAO wrapper delegating to the real DAO that throws on the Nth insert, assert zero rows in groupDao and memberDao. GR-14u36.yml:18 is a historical note, no edit.
- **B2-4** CORRECTED: `SafeSinkOperationRunHandle` is public, constructible directly (`CompositeOperationRunRecorder.kt:98-105`). Delete TrackingHandle/TerminalTrackingHandle tautologies in DDL512 (:191-275, :399-460) and the same pattern in `DurableDiagnosticsA8RegressionTest.kt:116-137`. **Finding:** `private suspend fun emitSafeEvent` (:150) has `catch (_: Exception) {}` at :170 — swallows `CancellationException` (AGENTS.md invariant). Recommended: fix in this slice (rethrow CE, keep swallowing sink failures) with a test; slice becomes feature-implementer, T3 + privacy-security-guardian.
- **B2-5** READY. Sink seam exists (`open` class, `internal open suspend appendLineSafely` :111-112); real IOException by making the target path a directory. WorkerRunLogger real test: Dispatchers.Default + latch, DAO suspends inside completeTerminal; mutation check (must fail without `terminalMutex`). WorkerLeaseRegistryTest is sequential only (:186, :245 asserts `in 0..2`) → real multi-thread test. Recommendation production already rethrows CE (:42, :55, :73) → add the missing test.
- **B2-6** READY. 16 sites in RecurringArchitectureGuardTest (:168, :212, :224, :310, :322, :347, :383, :395, :407, :417, :429, :439, :451, :462, :476, :487). IncludeDeposits: assert root exists and ≥1 file scanned. CancellationPropagationContractTest :108-110 and :113 → violations. No other contracts files have the pattern.
