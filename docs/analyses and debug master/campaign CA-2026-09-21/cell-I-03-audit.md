# CA-2026-09-21 Cell I-03 Audit

## Provenance
- Cell: I-03 — Dependency injection breadth
- Mode: AUDIT (static only)
- Auditor: direct astra session
- Date: 2026-09-22
- Pinned commit: `37601232b9778170c57a656a245b199ab6d7d965` (`git rev-parse --short HEAD`: `37601232`)
- Production pin check: `git diff --stat 37601232..HEAD -- app config scripts` empty
- Known issue baseline: `NONE-IDENTIFIED-BASELINE-RECONCILIATION` from Coverage Matrix; known-debt/intended-behavior sources checked before findings

## Scope and coverage
- Governing prompt §2 and §4
- `docs/architecture/COVERAGE_MATRIX.md` I-03 row
- Legal-path sections named by the I-03 row (privacy/cloud AI, workers/background, backup/restore, group/shared-expense facade, and related lifecycle paths)
- Segment 12, 20, 24, 28, and 30 entries in `CODEBASE_SEGMENTS.md`
- `app/src/main/java/com/yourname/expensetracker/di/` modules and `MainApplication.kt`
- `docs/architecture/VIEWMODEL_INJECTION_MAP.md` cross-check
- Relevant known-debt and decision records

## Findings

## Coverage ledger

- Read governing prompt §2 (known-debt/intended-behavior) and §4 (15 defect classes, schema, severity).
- Read I-03 row in `COVERAGE_MATRIX.md`; baseline marker is `NONE-IDENTIFIED-BASELINE-RECONCILIATION`.
- Read Legal Paths: Privacy / Cloud AI, Workers / Background Jobs, Backup / Restore, Group Mutations, Shared Expense Management (Groups — Domain Facade).
- Read Segment 12 (Startup & Background Runtime), Segment 20 (AI Platform), Segment 24 (Shared Expense Groups), Segment 28 (Security), Segment 30 (Dependency Injection).
- Read DI inventory section in `CODEBASE_INVENTORY.md` and all of `VIEWMODEL_INJECTION_MAP.md` for constructor cross-check.
- Read known-debt/intended-behavior records: `REVALIDATED_ISSUE_REGISTRY_2026-09-07.md`, `WAVE-1-STATUS.md`, `RP-00-DECISIONS-RECORDED.md` (DI/wiring-related entries).

### CA-I-03-001 | Segment and inventory assign `WorkerRunLogger` to the wrong Hilt module | defect class 13 (wiring / dead code) | severity P3

- **Evidence at pin:** `app/src/main/java/com/yourname/expensetracker/di/WorkerModule.kt`, `WorkerModule`, lines 18-42, binds only `WorkerLeaseRegistry`, `WorkerDrainController`, `NotificationPermissionChecker`, and provides `WorkManager`; there is no `WorkerRunLogger` binding. `app/src/main/java/com/yourname/expensetracker/di/DiagnosticsModule.kt`, `DiagnosticsModule.bindWorkerRunLogger`, lines 35-58, binds `WorkerRunLoggerImpl` to `WorkerRunLogger` at lines 54-55. The architecture records contradict this source: `docs/architecture/CODEBASE_SEGMENTS.md` lines 311 and 613 state that `WorkerModule` binds `WorkerRunLogger`; `docs/architecture/CODEBASE_INVENTORY.md` lines 540-545 labels DiagnosticsModule as the logger wiring, so the inventory and segment docs disagree. The detailed `docs/architecture/hilt-bindings-map.md` lines 98-111 is consistent with code and explicitly says DiagnosticsModule owns the binding.
- **Impact path:** a maintainer/auditor follows Segment 12 or Segment 30 to `WorkerModule` when tracing `WorkerExecutionGuard`'s logger dependency -> the wrong module is inspected and the actual diagnostics binding is missed. This can misdirect future DI edits or guard coverage; no runtime injection failure is asserted because Hilt's actual binding is present.
- **Caller trace:** `MainApplication.onCreate` -> `AppStartupDelegate.initialize` -> `AppStartupCoordinator`; every guarded worker -> `WorkerExecutionGuard` -> `WorkerRunLogger`; Hilt resolves the interface through `DiagnosticsModule.bindWorkerRunLogger`, not `WorkerModule`.
- **Existing tests/guards:** `hilt-bindings-map.md` is a correct documentation cross-check. Worker runtime tests/guards exercise logger behavior but do not assert module ownership. No build, test, or guard was run.
- **Cross-cell impact:** I-01/P-09 worker and diagnostics wiring; future DI audits relying on `CODEBASE_SEGMENTS.md` may mis-scope the logger binding.
- **Old-ID cross-refs:** none. `CA-I-01-001`, `CA-P-09-001..003`, and existing worker findings concern runtime behavior, not this module-documentation mismatch.

### CA-I-03-002 | Retention DI inventory and Hilt map omit five production targets | defect class 13 (wiring / dead code) | severity P3

- **Evidence at pin:** `app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt`, `provideRetentionTargets`, lines 34-41, constructs the multibound set; in addition to the ten legacy targets (names at lines 44, 96, 138, 180, 215, 252, 298, 336, 373, 410), it registers `10_transaction_events.snapshots` (lines 451-455), `20_transaction_events.rows` (489-492), `30_operation_runs` (527-530), `40_receipt_events` (578-580), and `50_privacy_audit_events` (614-616), then exposes the set through `provideRetentionRegistry` at lines 653-656. The pinned source therefore has 15 targets. `docs/architecture/CODEBASE_SEGMENTS.md` lines 590-592 and 610, `docs/architecture/CODEBASE_INVENTORY.md` line 544, and `docs/architecture/hilt-bindings-map.md` lines 439-444 all state that the registry has 10 targets and list only the first ten.
- **Impact path:** privacy/retention review -> inventory or Hilt map -> reviewer concludes only ten targets exist -> the five transaction-event, operation-run, receipt-event, and privacy-audit retention surfaces are omitted from dependency and ownership analysis. The production registry still constructs them, so this is an audit/discoverability defect rather than a missing runtime purge.
- **Caller trace:** `MainApplication.onCreate` -> `AppStartupDelegate.initialize` -> `AppStartupCoordinator.scheduleStartupWork` -> `WorkerRegistry` `data_retention` entry -> `DataRetentionWorker` -> injected `RetentionRegistry` -> Hilt `RetentionModule.provideRetentionTargets`; all 15 targets are reachable through this path.
- **Existing tests/guards:** `RetentionRegistryCoverageTest` reads the real `RetentionModule.provideRetentionTargets` and compares it with `RetentionPolicyContract`; its source comments and assertions cover the 15-name contract. Architecture docs and inventory do not consume that independent count, so they can remain stale while tests pass. No build, test, or guard was run.
- **Cross-cell impact:** P-08 privacy/retention, I-01 worker integration, I-02 persistence/diagnostics; stale counts can cause future coverage matrices or ownership scans to omit newly retained rows.
- **Old-ID cross-refs:** none. Existing P8 retention findings are about retention behavior and are not restated; this finding is the documentation/inventory divergence after the five targets were added.

## All-class disposition

### Covered-file ledger

- Production DI: `di/AiModule.kt`, `BackupRepositoryModule.kt`, `DatabaseModule.kt`, `DiagnosticsModule.kt`, `DispatchersModule.kt`, `GroupsModule.kt`, `PrivacyModule.kt`, `RetentionModule.kt`, `ServiceModule.kt`, `TimeModule.kt`, `WorkerModule.kt`.
- Production wiring/services: `MainApplication.kt`, `startup/AppStartupDelegate.kt`, `startup/AppStartupCoordinator.kt`, `domain/ai/HybridRouter.kt`, `domain/workers/WorkerRegistry.kt`, `domain/workers/WorkerGuardVerifier.kt`, `domain/workers/WorkerLeaseRegistryImpl.kt`, `domain/groups/SharedExpenseManager.kt`, `data/repository/SharedExpenseDataPortAdapter.kt`, `data/repository/GroupsRepositoryImpl.kt`, `data/backup/CostbackupBundle.kt`, `data/repository/DatabaseBackupRepositoryImpl.kt`, `domain/privacy/CompositePrivacyGate.kt`, `domain/privacy/CloudAiPrivacyGate.kt`, `domain/privacy/BackupPrivacyGate.kt`.
- Architecture and test cross-checks: `CODEBASE_SEGMENTS.md`, `CODEBASE_INVENTORY.md`, `LEGAL_PATHS.md`, `COVERAGE_MATRIX.md`, `VIEWMODEL_INJECTION_MAP.md`, `hilt-bindings-map.md`, `RetentionPolicyContract.kt`, `RetentionRegistryCoverageTest.kt`, worker/privacy/group/backup tests by targeted search.

1. Legal path: DI entry points and the named coordinator/facade paths were traced; no new production mutation bypass found.
2. Barrier: `DatabaseModule`, `SharedExpenseDataPortAdapter`, `RetentionModule`, and worker bindings preserve write-barrier injection; no new bypass promoted.
3. Atomicity/TOCTOU: group coordinator and backup/restore dependencies were traced to their transaction/barrier providers; no new DI-induced race found.
4. Idempotency/duplicates: worker registry and retention registry construction checked; no new duplicate key/path found.
5. Cancellation: retention targets use `CancellationSafe.runCatchingCancellable`; no new swallowed cancellation in inspected DI code.
6. Side-effect timing: diagnostics and post-commit bindings resolve to singleton implementations; no new timing defect found.
7. Money/currency: currency/time interfaces bind to injected providers; no new arithmetic defect in DI surfaces.
8. Time: `TimeModule` binds `TimeProvider` and `MonotonicTimeProvider`; no new scope/time defect found.
9. Privacy: `PrivacyModule` composes gates and binds `CloudPayloadPolicy`; no new fail-open binding found.
10. Worker hygiene: `WorkerModule` binds leases/drain/permission; `DiagnosticsModule` binds logger. The ownership-doc drift is CA-I-03-001.
11. Data integrity: retention target set includes all 15 policy targets in source; stale inventory is CA-I-03-002.
12. Error handling: no new DI-level error swallowing beyond inspected, controlled retention handling.
13. Wiring/dead code: CA-I-03-001 and CA-I-03-002; `HybridRouter` is intentionally a plain helper with no production caller (already reported as CA-P-08-004 and excluded here).
14. Test correctness: retention coverage test uses the real module; no new tautology asserted. Missing direct `HybridRouter` test is known from the P-08 audit and excluded.
15. Fix-regression: `git diff --stat 37601232..HEAD -- app config scripts` is empty; no wave-introduced production regression can be claimed.

## Completion and limitations

Static I-03 audit completed within the bounded read budget. Primary DI modules, `MainApplication`, named cross-cell services, inventory/segment/Hilt maps, ViewModel injection map, relevant callers, tests/guards, and known-debt records were inspected. No production code was edited; no builds, tests, or guards were run. Findings are discovery findings pending independent verification. Pure data classes and unrelated UI implementations were not read end-to-end.

## Closing provenance

- Agents invoked: none (direct astra session).
- Pinned source: `37601232b9778170c57a656a245b199ab6d7d965`; verified `HEAD` short `37601232` and empty production pin diff.
- Timestamp: 2026-09-22 Europe/Bucharest.
- Findings: CA-I-03-001, CA-I-03-002; 2 total, both P3; unverified discovery status.
