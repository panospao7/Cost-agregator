# P-09 — Worker runtime static audit

Date: 2026-09-21
Campaign: CA-2026-09-21
Cell: P-09
Auditor: direct astra session
Pinned commit: 37601232b9778170c57a656a245b199ab6d7d965
Mode: AUDIT, static only; discovery findings require independent verification.

## Provenance and scope

- `git rev-parse --short HEAD`: `37601232`.
- `git diff --stat 37601232..HEAD -- app config scripts`: empty.
- Working-tree and staged diffs for app/config/scripts: empty. Expected documentation/agent drift accepted.
- Read governing spec sections 2 and 4; COVERAGE_MATRIX P-09 row and shared ownership; LEGAL_PATHS Workers / Background Jobs, Diagnostics, Backup / Restore; segment 12 and relevant entries 11/18/29/36; targeted inventory and engine rows.
- Known FRESH-P9-001..009 excluded from new findings; decision register D1–D14 consulted.
- No builds, tests, guards, production edits, or delegated agents.

## Coverage ledger (append as inspected)

Initial primary-read scope: WorkerExecutionGuard, WorkerRegistry, WorkerRunLogger, WorkerRunContext, WorkerSpecScheduler, WorkerSpec, WorkerLeaseRegistryImpl, PrivacyRuntimeWorkerPolicy, WorkerGuardVerifier, terminal diagnostic sink, AppStartupCoordinator, DatabaseWriteBarrier, BackgroundJobRunDao, NotificationIntakeWorker. Additional caller/DAO/event/side-effect branches inspected by targeted excerpts. Full production-file read budget: 20.


### Coverage increment 1
- Full reads 1–7: `domain/workers/WorkerExecutionGuard.kt` (1–680), `WorkerRegistry.kt` (1–144), `WorkerRunLogger.kt` (1–355), `WorkerLeaseRegistryImpl.kt` (1–120), `data/backup/DatabaseWriteBarrier.kt` (1–40), `data/database/dao/BackgroundJobRunDao.kt` (1–151), `startup/AppStartupCoordinator.kt` (1–747). Paths relative to `app/src/main/java/com/yourname/expensetracker/`.
- Guard: inspected both entry APIs, backup-read branch, lease lifecycle, start barrier, privacy/permission gates, timeout-before-CE classification, checkpoint blocked policies, every terminal branch, stale recovery, transient matcher. Context counters now reach all terminal states; NO_WORK includes skips/errors. Known start-row retry defect excluded.
- Logger/DAO: inspected insert and CAS terminal update, snapshot mapping, cancellation/timeout behavior, zero-affected reread, stale CAS, retention query. Missing-row terminal misclassification and exception-message persistence were retained as unpromoted leads after caller/baseline follow-up.
- Startup: read restore recovery as surrounding context; traced normal-mode scheduling, stale recovery, bank/intake hooks and journal imports. Restore asset internals are P-07-owned and are not independently claimed as verified by this runtime audit.
- Lease/barrier: acquire/stop serialized; leases track runs, not mutual exclusion. Drain timeout uses wall-clock TimeProvider; terminal writes need checking against the database interceptor and restore drain before asserting a barrier defect.


### Coverage increment 2
- Full reads 8–16: `domain/workers/WorkerRunContext.kt` (1–72), `WorkerGuardVerifier.kt` (1–126), `PrivacyRuntimeWorkerPolicy.kt` (1–129), `WorkerSpecScheduler.kt` (1–386), `WorkerSpec.kt` (1–130), `worker/NotificationIntakeWorker.kt` (1–488), `domain/workers/FileWorkerTerminalDiagnosticSink.kt` (1–328), `data/location/LocationBackfillWorker.kt` (1–194), `MerchantKeyBackfillWorker.kt` (1–147). Also inspected the complete executable body of `data/backup/MaintenanceOperationRunner.kt` (15–71); conservatively counts as read 17.
- Targeted: `RestoreMaintenanceMode.kt` 95–205 (mode, cancellation, resume order), DatabaseModule provider excerpt, AppDatabase barrier/interceptor symbol search, MainApplication verifier caller, all production CoroutineWorker guard request/checkpoint searches. No production caller enables allowDuringBackupExport=true; its bypassed privacy checks are not promoted as a live leak.
- Intake has two guarded paths, including capability-free cleanup after Denied/FailClosed. Payload loading follows privacy recheck. Traced claim, domain pipeline dispatch, terminal marking, purge, CE and timeout branches. P-01 already owns CA-P-01-003 (new deadline/liveness consequence) and CA-P-01-005 (post-claim failure boundary); no duplicate P-09 findings.
- File sink uses structured fields, file rotation, mutex, and NonCancellable IO; blocking append is not an interruptible syscall just because withTimeout surrounds it. No independent runtime hang claim without stronger evidence.
- Cross-checked archived P9 known-ID list in addition to current registry: NEW-P9-007 already covers version-pref/enqueue atomicity; NEW-P9-012 covers swallowed DailyBriefing reschedule failure. These candidates are excluded from new findings.
- Backfill workers: reviewed loop budgets, checkpoints, CE, transient classification, conditional location writes and merchant-key writes; repository-to-DAO/event edges are recorded in increment 3.


### Coverage increment 3
- Full reads 18-20: `domain/workers/WorkerReasonCodes.kt` (1-46), `WorkerTerminalDiagnosticSink.kt` (1-73), `RetryableWorkerException.kt` (1-21). Full production-file budget reached: 20; no subsequent full-file reads.
- Signature/implementation searches for remaining package mates: WorkerLease, WorkerLeaseRegistry, WorkerDrainController, NoOpWorkerDrainController, NotificationPermissionChecker. Pure ScheduleResult/WorkerRunCounters containers were not full-file reads. NoOp drain appears in test-only/internal constructors; DI binds the real registry. Reason helper methods without callers were not promoted as runtime failures.
- Followed both backfill repository paths through `ExpenseRepository.kt` 904-1024 to `ExpenseDao.kt` 2095-2117, 2146-2189, 2289-2323. Confirmed explicit maintenance/no-event exceptions. Followed the competing rename through `TransactionsViewModel.kt` 506-520, `ExpenseRepository.kt` 533-558, and `TransactionLifecycleCoordinator.kt` 1484-1557; mutation and UPDATED event share the transaction, planner dispatch follows commit. Also inspected full-edit key/event excerpts at 944-973, 1000-1030, 1090-1125.
- Read consumer execution excerpts: DailyBriefingWorker 68-190 (freshness, timeout, generation/delivery, re-arm); ReceiptMatchingWorker 41-150 (claim/link, optional posting, actual delivery counter); BillReminderWorker 44-174 (claim/revalidate/post/mark and CE), plus typed notify/SecurityException lines; WarrantyExpirationWorker 72-105 and claim/recovery/post symbol hits; DataRetentionWorker 96-124 and target/audit/terminal mutation hits; snooze/dismiss workers 32-53 (coordinator mutation, checkpoints, no posting-permission requirement).
- All 10 CoroutineWorker classes have guard calls. Validated dynamic intake path by code, not registration alone. `WorkerGuardArchitectureGuardTest.kt` 50-104 scans source for guard invocation with empty allowlist; this is a structural gate, not proof of each branch. WorkerGuardStaticVerificationTest/WorkerGuardVerifier mapping checks and MainApplication debug invocation also inspected.
- Tests read by targeted excerpts/search: WorkerRunLoggerTest 445-535 (especially 462-490), WorkerLeaseRegistryTest 27 and 123-156; MerchantKeyBackfillWorkerTest mutation stubs/cases 88-167; WorkerExecutionGuardTest classification/timeout/permission/checkpoint cases, WorkerSpecSchedulerTest enqueue mocks and spec-version cases. No test or guard was executed. Existing test-audit batch 24 debt consulted to exclude already-recorded coverage weaknesses.
- RP-16 commit `1bca7e5c`: read guard diff completely and logger/backfill diff chunks; counter snapshot plumbing and spec metadata changes are present. No new finding is claimed to have been introduced by that commit; a full wave-wide regression audit is not implied.
- `EventMetadataSanitizer.kt` 159-167, 204-206 shows error messages remain regex-sanitized, not structurally excluded. This is not a privacy certification: no new concrete sensitive-payload exception witness was established in this bounded cell. Sink fallback contains structured fields only; production DI uses the file sink.

## Findings

3 new discovery findings: P0=0, P1=0, P2=3, P3=0. Independent campaign verification is pending. Main-source evidence paths below are relative to `app/src/main/java/com/yourname/expensetracker/`; test paths are relative to `app/src/test/java/com/yourname/expensetracker/`. All line ranges refer to the pinned source.

### CA-P-09-001
ID: CA-P-09-001
Title: Merchant-key backfill overwrites a newer merchant edit with a key from its stale snapshot
Defect class: 3 (Atomicity / TOCTOU)
Severity: P2
Evidence: At the pin, `data/location/MerchantKeyBackfillWorker.kt`, `doWork`, 70–99, reads a batch, derives each key from that saved merchant, then writes by ID. `data/repository/ExpenseRepository.kt`, `updateMerchantKey`, 1013–1023, checks only the maintenance barrier. `data/database/dao/ExpenseDao.kt`, `getExpensesWithNullMerchantKey` / `updateMerchantKey`, 2303–2323, selects NULL keys but updates unconditionally with `WHERE id = :expenseId`; no merchant snapshot or still-NULL predicate. `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`, `updateExpense`, 1001–1023, concurrently recomputes the key on a merchant edit.
Impact path: startup backfill reads merchant A with NULL key -> normal expense edit commits merchant B/key B -> backfill writes key A over key B -> row keeps merchant B but wrong merchantKey; NULL-only future backfills cannot repair it. Merchant location clustering and key-based bulk rename membership are then wrong (`ExpenseDao.kt` 2289–2297, 2311–2316). No monetary duplication or amount corruption is claimed.
Caller trace: `AppStartupCoordinator.initialize` -> `scheduleStartupWork` (668–670) -> `WorkerRegistry.entries` (77) -> `MerchantKeyBackfillWorker.schedule` (143–144) -> WorkManager `doWork` -> repository -> DAO. Competing production path: `ui/screens/transactions/TransactionsViewModel.kt`, `updateMerchant` (506-520) -> `ExpenseRepository.updateExpenseMerchant` (533-558) -> `TransactionLifecycleCoordinator.updateMerchant` (1484-1552), which atomically writes the new merchant/key and UPDATED event. The backfill can overwrite that committed key afterwards.
Existing tests/guards: `data/location/MerchantKeyBackfillWorkerTest.kt` covers happy path, already-keyed/empty input, and repeated failures; repository mocked, no concurrent edit/CAS SQL assertion found. Maintenance barrier is not a lock on user edits. The repository marks this backfill as an intentional low-risk no-event exception; absence of lifecycle events alone is not reported.
Cross-cell impact: P-02 expense edits; location enrichment / merchant-key consumers (E-03 where applicable).
Old-ID cross-refs: none. Distinct from backfill cancellation, budget and counter issues; no matching stale-key race found in the consulted current registry, worker debt list or remediation records.

### CA-P-09-002
ID: CA-P-09-002
Title: Terminal persistence failure diagnostics report the worker exception instead of the database failure
Defect class: 12 (Error handling)
Severity: P2
Evidence: `domain/workers/WorkerRunLogger.kt`, `Handle.terminal`, 225–253 captures the DAO exception in `TerminalResult.NotDurableFailure(e)`. `TerminalResult.toOutcome`, 168–184, then fills errorClass from its separate `error` argument, not the failure stored in that result. `success`, 280–292, passes null; `retry` / `failure`, 306–329, pass the original worker exception. `WorkerExecutionGuard.recordTerminalOutcome`, 478–495, forwards the resulting wrong/null class to the durable fallback sink; `FileWorkerTerminalDiagnosticSink.buildJsonLine`, 158–170, persists it.
Impact path: a worker completes business work successfully -> `completeTerminal` throws (e.g. SQLiteFullException) -> fallback records TERMINAL_WRITE_FAILED with errorClass=null; on a retry caused by IOException, a different terminal DB failure is instead reported as IOException. The diagnostic meant to explain the undurable terminal hides/misattributes its actual failure.
Caller trace: every context-guarded WorkManager worker -> `WorkerExecutionGuard.guardTerminal` -> `WorkerRunHandle.success/retry/failure` -> `BackgroundJobRunDao.completeTerminal` -> NotDurable -> file diagnostic. Production binding: `di/DiagnosticsModule.kt` 55, 77.
Existing tests/guards: `domain/workers/WorkerRunLoggerTest.kt`, `terminal retry db exception returns NotDurable` (462–474), throws SQLException from the DAO, supplies RuntimeException as the worker error, and explicitly expects RuntimeException in the fallback: this test codifies the misattribution (class 14 also applies). The success-write failure test (478-490) checks non-durability/retryability but not the persistence error class. Tests were read only, not executed.
Cross-cell impact: I-01 worker/backup diagnostics; P-03, P-04, P-08 and E-05 worker consumers.
Old-ID cross-refs: none. Not FRESH-P9-002 counters and not FRESH-P9-003 start-row retry classification.

### CA-P-09-003
ID: CA-P-09-003
Title: Worker drain timeout follows adjustable wall time instead of elapsed time
Defect class: 8 (Time correctness)
Severity: P2
Evidence: `domain/workers/WorkerLeaseRegistryImpl.kt`, `awaitNoActiveWorkers`, 60–68, computes deadline and checks elapsed budget using `timeProvider.now()` around `delay(50)`. `domain/util/SystemTimeProvider.kt` 11–12 implements it with System.currentTimeMillis. `requestStopAndAwaitDrain` (75–77) adds no coroutine timeout. `data/backup/MaintenanceOperationRunner.kt`, `enterAndDrain`, 27–36, enters maintenance before waiting; `domain/workers/WorkerDrainController.kt` default timeout is 5,000ms.
Impact path: user starts backup/restore while a worker lease is held -> maintenance blocks writes -> clock moves backwards during drain -> nominal five-second failure deadline can stretch by the clock adjustment while the worker remains stuck; a forward jump can abort a drain before its budget elapses. This is a boundedness/availability defect, not evidence that restore proceeds with active workers.
Caller trace: `DatabaseBackupRepositoryImpl.createCostBackup` (618–621) -> `MaintenanceOperationRunner.enterAndDrain` -> injected `WorkerDrainController` = `WorkerLeaseRegistryImpl` (`di/WorkerModule.kt` 23–27) -> `awaitNoActiveWorkers`. Restore/reset callers also use enterAndDrain.
Existing tests/guards: `domain/workers/WorkerLeaseRegistryTest.kt` uses real System.currentTimeMillis (27), tests empty drain and held/released leases (123–156), but does not jump the clock. Existing TimeProvider injection/source checks establish clock centralization, not monotonic timeout arithmetic.
Cross-cell impact: P-07 and I-01 backup/restore availability; E-01 shared time provider consumer.
Old-ID cross-refs: none. FRESH-P1-006 is a different, fixed deduper TTL consumer; P9-P1-03 concerns worker draining existence, not its elapsed-time clock.

## All-class disposition

| Class | Static audit result and evidence |
|---|---|
| 1 Legal path | Traced guard -> run logger -> BackgroundJobRunDao; backfill -> repository -> explicitly exempt maintenance DAO; normal merchant edit -> lifecycle coordinator -> mutation + event -> post-commit dispatch. No new coordinator bypass asserted. |
| 2 Barrier | Checked entry/start/checkpoint ordering, NORMAL-only write barrier, lease stop/acquire serialization, maintenance drain before destructive work and real DI bindings. Terminal logger has no own barrier; do not equate that with a demonstrated DB-swap bypass because maintenance waits for its enclosing lease. Stale startup recovery is not lease-protected; no new concrete race promoted without persistence-owner analysis. |
| 3 Atomicity/TOCTOU | Logger terminal CAS and local completion-after-write verified; stale-recovery CAS cannot overwrite a terminal row. Backfill stale snapshot is CA-P-09-001. |
| 4 Idempotency | Checked unique scheduling, version handling, registry/lease distinction, intake claim/deadline transitions, receipt claim-before-link and terminal CAS. Known scheduling/intake issues excluded. Backfill wrong key persists because subsequent runs select only NULL keys (001). |
| 5 Cancellation | Walked ordinary CE propagation, timeout-specific policy, terminal NonCancellable/timeout ordering, lease finally, startup suspend catches, intake rethrow, and backfill catches. File/composite sink deliberately suppresses exceptions during fallback; no fabricated CE test-pass claim. |
| 6 Side-effect timing | Logger marks local complete after durable CAS; file fallback follows failed terminal persistence. Receipt worker posts after successful link; reminder posts after claim/revalidation and marks delivery afterwards. Scheduling acknowledgement debt is pre-existing. Runtime infrastructure does not make Android posting exactly-once across process death. |
| 7 Money/currency | No money computation in the runtime engine. Backfill mutates keys/coordinates/counters; amount/currency unchanged. Checked money-affecting caller stays behind its owning coordinator. Full calculation validation belongs to E-01/P-02/P-04. |
| 8 Time | Inspected TimeProvider timestamps, midnight zone/calendar delay and floor, stale thresholds, intake backoff, default intervals. Adjustable drain deadline is CA-P-09-003. |
| 9 Privacy | Guard Denied/FailClosed branches prevent payload work; intake cleanup remains capability-free; retention has no retention-capability gate; fallback file fields are structured. Regex-sanitation of exception messages is explicitly not a complete privacy proof; downstream payload/error construction remains a cross-cell boundary. No new concrete payload leak claimed. |
| 10 Worker hygiene | All 10 worker guard call sites inspected, including dynamic intake cleanup. Retry/Failed/BlockedRetry mappings, timeout policy, checkpoints, spec metadata and counter terminal forwarding read. Receipt posting permission is local and counters require DELIVERED. Known mixed warranty reconciliation/posting concern is excluded below. |
| 11 Data integrity | Read all BackgroundJobRunDao SQL and terminal state handling; backfill stale merchant/key mismatch is 001. Restore assets are P-07-owned; no migration/schema audit or runtime verification implied. |
| 12 Error handling | Walked run insertion, checkpoint denial, terminal persistence and fallback paths, scheduler returned failures, missing rows and startup catches. CA-P-09-002 misattributes the terminal-write failure. Other promoted-looking scheduler/start-row cases matched known debt. |
| 13 Wiring/dead code | Traced startup/restore scheduling, MainApplication debug verifier, DI logger/file sink/drain bindings and dynamic intake callers. No production allowDuringBackupExport=true caller found. NoOp constructors are test/internal paths, not a shown live drain bypass. |
| 14 Test correctness | Real logger test at 462-474 explicitly asserts the wrong exception class (002). Merchant-key mocks do not exercise concurrent SQL mutation (001); real-clock drain tests do not cover clock jumps (003). Existing batch-24 coverage debt not restated as new. |
| 15 Fix-regression | Inspected RP-16 guard diff and logger/backfill diff excerpts against parent 1bca7e5c^. Counter snapshots and metadata changes present. Three findings are new discoveries, not demonstrated wave-introduced regressions. |

## Exclusions and unpromoted leads

- FRESH-P9-001..009 remain baseline items, not new findings here. Current source shows the typed bill ID, all-terminal counter plumbing, expanded NO_WORK predicate and four specVersion additions. This is static source reconciliation evidence, not a claim their tests passed. Full retention checkpoint reconciliation belongs to P-08.
- Additional known debt excluded: NEW-P9-007 (enqueue/version persistence), NEW-P9-012 (briefing reschedule errors), NEW-P9-013 (backup-read exception handling), MIT-070/MIT-017 (scheduleAll failure diagnostics). No re-labelled findings for these.
- `docs/development/FUTURE-WORK.md:385` already tracks WRK-16, mixed warranty reconciliation and posting. The guard permission gate at WarrantyExpirationWorker 76 precedes reconciliation at 87; the effect on non-posting work is noted for that existing item, not published as a new finding. DailyBriefing's successful-use-case-call notification count is explicitly scoped as best effort in its source; not promoted without delivery-owner reconciliation.
- A missing BackgroundJobRun row is treated as AlreadyTerminal by the logger. No live deletion/restore interleaving was established within this audit; do not promote a hypothetical data-loss finding.
- File append timeout is cooperative and does not interrupt a blocking filesystem syscall; no reproduced runtime hang. No tests ran.

## Completion and limitations

P-09's bounded static discovery pass is complete: primary runtime files were read end-to-end, all 15 defect classes considered, 20 full production-file reads used, and three new P2 findings recorded. No claim is made that the wider startup/location/privacy/receipt/recurring segments are exhaustively audited. Cross-cell business coordinator internals, retention target/checkpoint machinery, complete migration histories, and platform scheduling behavior beyond the inspected callers were not fully audited. The coverage ledger distinguishes full reads from excerpts and searches.

Independent verification, severity confirmation and any remediation remain pending. Validation: NOT RUN (user required static only). No builds/tests/guards, commits or production edits. Source HEAD and app/config/scripts pin diff rechecked unchanged at close. Writes limited to this report and the campaign JOURNAL.

## Closing provenance

- Auditor: astra-cell-auditor (direct session); direct astra session.
- Agents invoked: none, as requested.
- Cell: P-09; campaign CA-2026-09-21; mode AUDIT, static only.
- Pinned source: 37601232b9778170c57a656a245b199ab6d7d965; verified HEAD identical.
- Timestamp: 2026-09-21 20:33:24 UTC (close-out clock reading).
- Findings: CA-P-09-001, CA-P-09-002, CA-P-09-003; 3 total, all P2; unverified discovery status.

