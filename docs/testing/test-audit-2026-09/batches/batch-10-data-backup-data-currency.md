# Batch 10 — data-backup + data-currency

Scope: Segment 18 (Export & Backup) unit/contract tests + Segment 16 (Currency & Exchange) adapter test · Files: 14 · LOC: 2524
Reviewer notes: Strict-mode P0 area per AGENTS.md. Legal contract verified against current production (`data/backup/`): 11 `RestoreMaintenanceMode.Mode` values, 9 `JournalState` values, typed `DatabaseAccessBlockedException(accessType, operation, mode)` extending `IllegalStateException` (`DatabaseAccessModels.kt:22-28`), writes only in NORMAL, 3 read policies incl. always-denied `RESTORE_INTERNAL_STAGED_DB_READ` — `DatabaseBarrierTest` matches LEGAL_PATHS §Backup/Restore exactly. Note docs drift: `CODEBASE_SEGMENTS.md` says "8-state RestoreJournal / 8-state mode manager" but production has 9 states / 11 modes (LEGAL_PATHS is correct). F-14 (typed-exception) and F-16/F-18 touch this batch — see findings. `CanonicalMultiCurrencyFixture` named in the batch guidance does NOT live in `data/currency/` (actual file: `ExchangeRateStoreAdapterTest.kt`); the fixture is at `test/.../currency/CanonicalMultiCurrencyFixture.kt` (another batch's scope) — its zero-consumer premise is confirmed there.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 8 | 3 | 1 | 1 | 1 | 0 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/data/backup/AppOperationalStateTest.kt | 95 | 7 | 0 | MOCKED | AppOperationalState, RestoreMaintenanceMode (mock) | REWRITE | P1 | (none — only test of AppOperationalState) | Tautology: seeds own flow copy, asserts it back |
| 2 | test/…/data/backup/AssetRestoreAtomicityTest.kt | 232 | 7 | 0 | ROBOLECTRIC | RestoreJournal | KEEP | P0 | #11/#13, golden roundtrip (complementary) | Real journal+fixed clock; privacy strip check |
| 3 | test/…/data/backup/BackupVerifierManifestTest.kt | 155 | 7 | 0 | ROBOLECTRIC | BackupVerifier | KEEP | P0 | DatabaseBackupRepositoryImplTest, BackupRestoreContractTest | Typed exceptions, Tier-1 manifest, aggregates |
| 4 | test/…/data/backup/CostbackupBundleLimitsTest.kt | 255 | 9 | 0 | PURE | CostbackupBundle | KEEP | P0 | P7BugFixesTest (complementary) | Zip-bomb limits, legacy createdAt fallback |
| 5 | test/…/data/backup/DataStoreMaintenanceSafeDiagnosticSinkTimeProviderTest.kt | 215 | 4 | 0 | MOCKED | DataStoreMaintenanceSafeDiagnosticSink | KEEP | P0 | #9 (different impl) | Hostile-payload redaction; DataStore singleton hazard |
| 6 | test/…/data/backup/DatabaseBarrierTest.kt | 195 | 19 | 0 | MOCKED | DatabaseWriteBarrier, DatabaseReadBarrier | KEEP | P0 | golden/#20/#24 (they MERGE here), crosslayer/arch guards | Canonical barrier matrix; F-14 aligned in source |
| 7 | test/…/data/backup/ExportReadBarrierTest.kt | 143 | 9 | 0 | MOCKED | DatabaseReadBarrier, DatabaseReadBarrierFlowExt | MERGE | P1 | #6 (DUP), survivor: DatabaseBarrierTest | Move Flow-helper tests; F-16 nested-runTest hazard |
| 8 | test/…/data/backup/MaintenanceOperationRunnerTest.kt | 167 | 10 | 0 | MOCKED | MaintenanceOperationRunner, WorkerDrainController | STRENGTHEN | P0 | architecture/WriteBarrierArchitectureGuardTest | F-18 root cause: stale default-policy timeout test |
| 9 | test/…/data/backup/MaintenanceSafeDiagnosticSinkTest.kt | 91 | 5 | 0 | MOCKED | TimberMaintenanceSafeDiagnosticSink | DELETE | P3 | #5, #6 | No-crash + mock-echo only; Timber never observed |
| 10 | test/…/data/backup/P7BugFixesTest.kt | 429 | 5 | 0 | ROBOLECTRIC | RestoreMaintenanceMode, RestoreJournal, CostbackupBundle, DatabaseBackupRepositoryImpl | STRENGTHEN | P0 | #4/#11/#13 | 3 solid; 2 tests reimplement prod logic; FRAGILE reflection |
| 11 | test/…/data/backup/RestoreJournalDurabilityTest.kt | 80 | 2 | 0 | ROBOLECTRIC | RestoreJournal | KEEP | P0 | #2/#13 (complementary) | fsync-path roundtrip guard |
| 12 | test/…/data/backup/RestoreJournalImporterFailureTest.kt | 127 | 3 | 0 | ROBOLECTRIC | RestoreJournalImporter | KEEP | P0 | architecture/DirectEventDaoInsertGuardTest | Slot-captured ledger rows; idempotent import |
| 13 | test/…/data/backup/RestoreJournalTimeProviderTest.kt | 145 | 8 | 0 | ROBOLECTRIC | RestoreJournal, TimeProvider | KEEP | P0 | #2/#11 (complementary) | No-wall-clock contract; legacy JSON fallback |
| 14 | test/…/data/currency/ExchangeRateStoreAdapterTest.kt | 195 | 8 | 0 | MOCKED | ExchangeRateStoreAdapter | STRENGTHEN | P1 | e2e/golden use adapter (complementary) | F-21: validDate mapping never asserted |

## Findings (noteworthy files only)

### test/…/data/backup/AppOperationalStateTest.kt
- Every test stubs `operationalStateFlow` with a `MutableStateFlow` it fills using its own local `modeToState` reimplementation (lines 12-28), then asserts the flow equals what it put in — 7 tautologies, zero production verification; `isWritesAllowed`/`reset` tests assert their own stub lambdas (lines 79-94).
- The real mapping (`RestoreMaintenanceMode.toOperationalState`, prod line 215) has NO production-driven coverage anywhere — mode→state drives the app shell UI, so this is a real gap, not dead weight.
- Rewrite: instantiate real `RestoreMaintenanceMode` (Robolectric + real prefs + `FakeTimeProvider`, as `P7BugFixesTest.kt:72` does) and assert `operationalStateFlow` after `enter(...)`.

### test/…/data/backup/DatabaseBarrierTest.kt
- Canonical barrier policy test: write blocked in all non-NORMAL modes (typed `DatabaseAccessBlockedException` with accessType/mode metadata, lines 37-76, 179-194); read policy matrix incl. `RESTORE_INTERNAL_STAGED_DB_READ` denied across all 11 `Mode.entries` (lines 143-153) — matches LEGAL_PATHS §Backup/Restore exactly. Golden batch 01 already ruled `RestoreBlocksAllWritesTest` and `WorkerRestoreBarrierIdempotencyGoldenTest` MERGE into this file — this file is the survivor and must be protected.
- F-14 status: ledger lists 1 failing test here ("expected `DatabaseAccessBlockedException` but was `IllegalStateException`"). Current production throws the typed exception (which IS an `IllegalStateException` subclass) and this file's assertions match current APIs precisely — statically the F-14 drift for this file appears resolved; residue likely lives in `ExpenseStoreTest` (5 of the 7 family tests). Recommend ledger confirm-on-run, not a code change here.
- Minor: nested `runTest` inside `assertThrows` (line 89) is the F-02/F-16 coroutine-harness hazard pattern.

### test/…/data/backup/ExportReadBarrierTest.kt
- 3 tests duplicate `DatabaseBarrierTest` verbatim (`export_read_allowed_in_NORMAL/BACKUP_EXPORTING`, `restore_blocks_all_non_NORMAL_modes_for_normal_reads` vs #6's equivalents). Unique value worth keeping: `guardedDatabaseRead` / `blockedDuringRestore` Flow-helper tests (lines 106-142, covering `DatabaseReadBarrierFlowExt.kt`) and P12 export-pipeline operation naming.
- F-16 (2 `UncompletedCoroutinesError`) maps to the nested `runTest { flow.toList() }` inside `assertThrows` inside `runTest` (lines 119-121) — restructure with `runCatching`/try-catch on `flow.toList()` in the outer scope.
- Action: move the 4 Flow-helper/restart-required tests into `DatabaseBarrierTest`; delete the 3 policy duplicates.

### test/…/data/backup/MaintenanceOperationRunnerTest.kt
- F-18 root cause found statically: `drain_timeout_does_not_prevent_block_from_running` (lines 154-166) stubs drain=false and asserts the block still runs — but production `runExclusive` defaults to `DrainTimeoutPolicy.FAIL_OPERATION` which exits maintenance and throws `WorkerDrainTimeoutException` (`MaintenanceOperationRunner.kt:52-57`). Test is stale against the policy enum; it must pass `drainTimeoutPolicy = PROCEED_WITH_WARNING` (and a new test should pin FAIL_OPERATION: exit(false) + throw). This also explains the ledger's "exit not called" symptom family.
- Otherwise good: real ordering assertion (drain completes before block, lines 44-57), exception-path exit coverage, restart-required variants.
- Note: "exception in block still exits maintenance" (`exit(false)` after a failed RESTORE_PREPARING block) does not violate LEGAL_PATHS' "never exit to NORMAL after failed rollback" — journal rollback/CRITICAL_RECOVERY decisions live in the restore orchestrator, not the runner; a comment pinning that would help.

### test/…/data/backup/MaintenanceSafeDiagnosticSinkTest.kt
- All 5 tests are no-crash or mock-echo assertions: `sink_does_not_throw_in_any_mode` (line 28) is the exact "does not throw" anti-pattern; `sink_interface_is_implemented_by_timber_impl` (line 63) re-checks the compiler; `mock_sink_can_verify_calls` (line 74) verifies a call on a relaxed mock — proves nothing about Timber output (no planted Timber tree).
- `write_barrier_exception_carries_mode_for_sink` duplicates `DatabaseBarrierTest.exception_carries_operation_metadata` (#6:179-194).
- The persisted-sink behavior this file should cover is already properly tested by `DataStoreMaintenanceSafeDiagnosticSinkTimeProviderTest` (#5). DELETE.

### test/…/data/backup/P7BugFixesTest.kt
- Strong: `critical_state_transition_is_atomic` (real prefs + `FakeTimeProvider`, deterministic timestamp, prod:66-108); `restore_journal_append_is_thread_safe` (8x25 concurrent appends, all 200 persisted with unique codes — real concurrency evidence, borderline NIGHTLY but fast enough); `backup_bundle_closes_stream_on_exception` (typed corrupt-input rejections + 50x leak loop).
- Weak: `count_rows_validates_table_name` (lines 267-374) locates `DatabaseBackupRepositoryImpl.countRowsFromSourceTable` via reflection (FRAGILE signature coupling — any refactor breaks it) then admits it cannot invoke it and instead re-implements the quoting logic locally and tests the copy; `count_rows_validates_table_name_escaping` (lines 378-428) is a pure tautology asserting a local `replace()` copy — false confidence on a SQL-injection guard. Rewrite the former to actually invoke the method (mockk instance or internal visibility), delete the latter.
- The production method additionally does `tableExists` pre-check + throws for missing tables (`DatabaseBackupRepositoryImpl.kt:1916-1924`) — none of that behavior is covered by the reimplementation.

### test/…/data/currency/ExchangeRateStoreAdapterTest.kt
- Prior 2026-05 audit (batch-001 #46) said KEEP; re-verified — still correct direction, with gaps. Mapping tests assert captured entity fields but never `validDate` (lines 73-80) despite production requiring/deriving it (`ExchangeRateStoreAdapter.kt:64,70-71,79`) — this is the F-21 "ExchangeRateStoreAdapter validDate" ledger entry; add a validDate-mapping assertion and a zero-validDate rejection test.
- Barrier interplay is good: verify `checkWritesAllowed` before DAO mutation for all 3 mutations, and a real fail-closed test (RESTORE_PREPARING → typed exception, DAO untouched, lines 174-194). The "before" ordering is implied by the blocked test rather than asserted by order — acceptable.

### CanonicalMultiCurrencyFixture (guidance item — NOT in this batch's file list)
- Lives at `test/.../currency/CanonicalMultiCurrencyFixture.kt` (package `currency`, not `data/currency` — belongs to another batch). Grep confirms ZERO external consumers: the only references are inside the file itself (its embedded `CanonicalMultiCurrencyFixtureTest`, ~2 tests, real 142-EUR conversion assertion).
- Prior 2026-05 audit (batch-001 #22) marked it KEEP P0_CRITICAL "GOLDEN" — recommend OVERTURN to MERGE/DELETE for the fixture API: no test adopted it; keep only the embedded 142-EUR-not-150 test by relocating it to the contracts/golden currency-consistency suite. Final call with the owning batch.

## Area gaps (what is NOT tested in this area)

- No unit test drives a REAL `RestoreMaintenanceMode` through `enter`/`exit`/`reset` persistence (SharedPreferences `commit()` durability, pause-7-workers via WorkerRegistry, reschedule on exit, reset-to-NORMAL on clean start) — only `enterCriticalRecoveryRequired` is production-driven (P7BugFixesTest). LEGAL_PATHS' core restore-mode guarantees are enforced structurally by guards (architecture/WriteBarrierArchitectureGuardTest, scripts/verify_db_access_boundaries.py) but not behaviorally at mode-manager level.
- `AppOperationalState` mapping: zero production-driven coverage (see #1).
- `CostbackupBundle` AES-256-GCM: no explicit wrong-password test (P7 accepts any of 3 exception types) and no ciphertext-tamper test proving GCM auth failure fails closed.
- `BackupVerifier`: Tier 2 (VALIDITY) / Tier 3 (OPTIONAL) semantics and the full 57-entity `verify()` flow are untested — only Tier-1 manifest completeness + semantic aggregates.
- `blockedDuringRestore` resumption (re-emission when mode returns to NORMAL via `flatMapLatest`) only shallowly asserted (`isNotEmpty`).
- Docs drift: CODEBASE_SEGMENTS.md "8-state RestoreJournal / 8-state mode manager" vs production 9 states / 11 modes.

## Rollup

- Verdicts: KEEP 8 · STRENGTHEN 3 · MERGE 1 · REWRITE 1 · DELETE 1 · NIGHTLY 0 · UNKNOWN 0
- P0 files: 10 (2,3,4,5,6,8,10,11,12,13) · P1: 3 · P3: 1 · P4: 0
- DUP pairs: ExportReadBarrierTest ↔ DatabaseBarrierTest (in-batch); golden/RestoreBlocksAllWritesTest ↔ DatabaseBarrierTest (cross-batch, survivor confirmed here); golden/WorkerRestoreBarrierIdempotencyGoldenTest ↔ DatabaseBarrierTest (cross-batch)
- FRAGILE: 1 (P7BugFixesTest reflection on private `countRowsFromSourceTable` signature)
- Ledger families touched: F-14 (DatabaseBarrierTest/ExportReadBarrierTest — statically aligned with current typed-exception contract; likely resolved or rooted in ExpenseStoreTest), F-16 (ExportReadBarrierTest nested-runTest pattern identified), F-18 (MaintenanceOperationRunnerTest stale default-policy timeout test — root cause identified), F-21 (ExchangeRateStoreAdapterTest validDate assertion gap)
- @Ignore count: 0 across the batch
