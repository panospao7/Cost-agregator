# Batch 05 — architecture (guardrail tests, LIGHT-TOUCH)

Scope: `app/src/test/java/com/yourname/expensetracker/architecture/` — JVM static architecture guardrail tests (source-scanning + registry-verification). Per user instruction these are CI-enforced guardrails, excluded from deep review and from pruning; one row per file, no per-test deep dive.
Files: 14 · LOC: 3,901 · Tests: 107 · @Ignore: 0

Wiring note (applies to every row): none of the 14 class names appear in `.github/workflows/ci.yml` or `scripts/ci/guard_registry.py`. All are executed **implicitly** by the blanket `./gradlew :app:testDebugUnitTest` step in the ci.yml `unit-tests` job (ci.yml:123) → recorded as "gradle-only (implicit)". The Python guard registry (`guard_registry.py`, 18 guards) is a separate enforcement plane run by the `static-guards` job via `scripts/ci/run_static_guard_suite.py`.

TEST_FAILURE_LEDGER: `architecture.*` measured **GREEN** (ledger header; BackupRestoreArchitectureGuardTest named explicitly). No batch file belongs to a failing family (F-14 concerns data-layer barrier tests, not these). Prior 2026-05 audit (TEST_PRUNING_CANDIDATES / batch-00*) contains no entries for these files — no stale verdicts to overturn.

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 14 | 0 | 0 | 0 | 0 | 0 | 0 |

## Per-file table

| # | File (test/java/…/architecture/) | LOC | Tests | Invariant(s) enforced | Wired | Verdict | Script guard overlap (duplication pair?) |
|---|---|---|---|---|---|---|---|
| 1 | BackupRestoreArchitectureGuardTest.kt | 131 | 3 | P7-020/021: `resetDatabase()` enters RESETTING_DATABASE maintenance + RestoreJournal; raw `exportDatabase` is debug-UI-only | gradle-only (implicit) | KEEP | None direct (no reset/export-specific script guard) |
| 2 | BankPrivacyModeArchitectureGuardTest.kt | 62 | 2 | BANK-PRIVACY-01: BankApiIntegration uses `rawBankStatementStorageMode`, never `rawOcrStorageMode` | gradle-only (implicit) | KEEP | None (privacy/pii_logging scripts cover different rules) |
| 3 | CancellationSafetyArchitectureGuardTest.kt | 708 | 16 | CANCEL-01: broad catch in suspend fns must handle CE; no raw `runCatching` in suspend paths; structured allowlist w/ expiry + burn-down | gradle-only (implicit) | KEEP | **DUP pair**: scripts/verify_cancellation_boundaries.py (`cancellation`, ratchet) — same rule, TWO parallel allowlists (inline Kotlin vs cancellation_allowlist.yml) |
| 4 | DeprecatedApiArchitectureGuardTest.kt | 330 | 13 | W01/W06/W29/W30/W31 + PR8: deprecated raw-Double/self-fetching analytics APIs have no new production call sites | gradle-only (implicit) | KEEP | Partial/conceptual only: verify_money_boundaries.py + currency_guardrails.ps1 cover different money rules |
| 5 | DirectEventDaoInsertGuardTest.kt | 410 | 6 | EVENT-GUARD-01: critical event-DAO `.insert()` only from approved files (structured allowlist, owner/expiry) | gradle-only (implicit) | KEEP (flag: expired allowlist, see Observations) | **DUP pair**: scripts/verify_event_writers.py (`event_writers`, ratchet) |
| 6 | Engine5PrimitiveGuardTest.kt | 149 | 3 | E5-001/002/003: no new deprecated `domain.model.PeriodRange` imports; no `System.currentTimeMillis` in domain/core\|budget\|analytics; no raw `CurrencyCode(` ctor in domain/core | gradle-only (implicit) | KEEP | **DUP pair (E5-002)**: verify_time_boundaries.py (`time_boundaries`) + orphan scripts/guards/check_direct_time_calls.kts; **partial (E5-003)**: verify_money_boundaries.py |
| 7 | ExpenseDaoMutationAccessTest.kt | 129 | 5 | `RestrictedExpenseDaoMutation` @OptIn discipline: no file/class-level opt-in outside coordinators; mutating ExpenseDao methods annotated | gradle-only (implicit) | KEEP | **DUP pair**: verify_db_access_boundaries.py (`db_access` ownership policy) + orphan scripts/guardrails/dao-access-check.kts |
| 8 | RawDaoArchitectureGuardTest.kt | 94 | 1 | DAO-01 (Engine 3 scope): merchant/category normalization DAO mutators only from repository layer | gradle-only (implicit) | KEEP | Same family as `db_access` guard + dao-access-check.kts (different DAO scope — complementary) |
| 9 | RecurringArchitectureGuardTest.kt | 324 | 19 | Recurring legal paths: recurring DAO mutation only via coordinators; receivers use WorkManager (no runBlocking/DAO); no legacy `markBillPaid`; eventWriter for critical events; MIGRATION_139_140 content; rule create/activate/deactivate semantics | gradle-only (implicit) | KEEP (flag: 1 silently-skipped test, see Observations) | Partial: `db_access` ownership policy + orphan scripts/guards/check_lifecycle_bypasses.kts (different scope) |
| 10 | SourceScanningArchitectureGuardTest.kt | 686 | 21 | Worker-layer rules: CoroutineWorker guard calls, no DAO/@Inject in workers (allowlist), notification-permission flag + local check (PR12K-3), privacy capabilities, `blockedPolicy=RETRY`, schema version == latest JSON, worker broad-catch CE rule, DataRetention raw-capability ban; comment-stripping + negative fixtures | gradle-only (implicit) | KEEP | **DUP pairs**: verify_worker_boundaries.py (`worker`), verify_cancellation_boundaries.py (`cancellation`, broad-catch rule), verify_privacy_boundaries.py (`privacy`, partial) |
| 11 | TransactionContextProvenanceGuardTest.kt | 192 | 5 | PR21-1: `TransactionContext(` constructed only in RoomDomainTransactionRunner / allowlisted files | gradle-only (implicit) | KEEP | None (no script guard covers TransactionContext provenance) |
| 12 | WorkerGuardArchitectureGuardTest.kt | 138 | 3 | Every CoroutineWorker calls `runGuarded(/WithContext)` (WorkerExecutionGuard) or documented exemption (allowlist currently empty, anti-creep tests) | gradle-only (implicit) | KEEP | **DUP pair**: verify_worker_boundaries.py (`worker`, blocking) |
| 13 | WorkerGuardStaticVerificationTest.kt | 163 | 4 | WorkerGuardVerifier registry completeness: all 10 known workers registered; KNOWN_WORKER_FQNS ↔ findWorkerClasses sync (reflection); WorkerSpec.DEFAULTS ⊆ listAllWorkerNames; count == 10 | gradle-only (implicit) | KEEP | Complementary to `worker` script guard (registry-based vs scan-based) |
| 14 | WriteBarrierArchitectureGuardTest.kt | 385 | 6 | All DAO write-method callers inject `DatabaseWriteBarrier` or are exempt (parses @Insert/@Update/@Delete + @Query UPDATE/DELETE/INSERT + @Transaction promotion); non-vacuousness checks | gradle-only (implicit) | KEEP | **DUP pair**: verify_db_access_boundaries.py (`db_access`, ratchet — "global write/read/restore barrier") |

## Observations

### Overall health
Good. The guard layer is unusually disciplined for SRCTEXT-style tests: every scanner has an explicit non-vacuousness assertion (zero-file checks), allowlists are structured with owner/reason/issue/expiry, `SourceScanningArchitectureGuardTest` strips comments before scanning (blocks commented-out bypasses) and proves its detectors with negative fixtures. All 80+ production classes/paths referenced by these guards were spot-checked and exist — no stale imports or dead references. Main systemic risks are (a) allowlist expiry time-bombs and (b) JVM↔script duplication with split-brain allowlists.

### Stale / time-bomb guards (static findings)
- **DirectEventDaoInsertGuardTest — will FAIL on next run.** Eight `APPROVED_ENTRIES` expire `2026-08-15` (WarrantyTrackerRepository, ExpenseRepository, ReceiptRepository, ReviewQueueRepository, BankApiIntegration, RecurringExpenseRepository, ManualRecurringExpenseRepository, NotificationRepository). `expired direct event allowlist entries fail` asserts none expired vs `LocalDate.now()`; any run after 2026-08-15 (incl. today) fails. Ledger "GREEN" predates expiry. Needs allowlist renewal or migration of those legacy writers — not pruning.
- **RecurringArchitectureGuardTest — 1 silent no-op.** `golden lifecycle tests do not bypass coordinator with direct DAO insert` scans `app/src/main/java/…/golden`, which does not exist (golden tests live under `app/src/test/.../golden`); the test self-skips via `if (!goldenDir.exists()) return`. Dead as wired.
- **Expiry-governance holes (drift, not failures):** `SourceScanningArchitectureGuardTest.workers_with_broad_catch_must_rethrow_cancellation` has a nested allowlist expiring 2026-09-30 that is NOT checked by `expired_allowlist_entries_fail` (which validates only the class-level allowlist). Same pattern in `CancellationSafetyArchitectureGuardTest`: `RAW_RUN_CATCHING_ALLOWLIST` (expires 2026-10-01) is unchecked by its `expired allowlist entries fail` test.

### JVM-test-vs-script duplication list (same invariant, two enforcers)
1. CancellationSafetyArchitectureGuardTest ↔ `scripts/verify_cancellation_boundaries.py` (guard `cancellation`) — plus two sibling JVM overlaps: SourceScanningArchitectureGuardTest worker broad-catch rule; two allowlists to maintain in lockstep.
2. WorkerGuardArchitectureGuardTest + SourceScanningArchitectureGuardTest (worker rules) + WorkerGuardStaticVerificationTest ↔ `scripts/verify_worker_boundaries.py` (guard `worker`).
3. DirectEventDaoInsertGuardTest ↔ `scripts/verify_event_writers.py` (guard `event_writers`).
4. WriteBarrierArchitectureGuardTest ↔ `scripts/verify_db_access_boundaries.py` (guard `db_access`).
5. ExpenseDaoMutationAccessTest + RawDaoArchitectureGuardTest ↔ `verify_db_access_boundaries.py` ownership policy + `scripts/guardrails/dao-access-check.kts` (the kts script is wired nowhere — orphan).
6. Engine5PrimitiveGuardTest (E5-GUARD-002) ↔ `scripts/verify_time_boundaries.py` (guard `time_boundaries`) + `scripts/guards/check_direct_time_calls.kts` (also orphan).
7. Engine5PrimitiveGuardTest (E5-GUARD-003) / DeprecatedApiArchitectureGuardTest ↔ `scripts/verify_money_boundaries.py` + `scripts/currency_guardrails.ps1` — partial/conceptual only; the script rules differ from the JVM call-site rules (complementary, not true dups).
8. SourceScanningArchitectureGuardTest (capability/notification rules) ↔ `scripts/verify_privacy_boundaries.py` — partial.

None of the `scripts/guards/*.kts` or `scripts/guardrails/dao-access-check.kts` are referenced by ci.yml, run_static_guard_suite.py, guard_registry.py, or Gradle — they are manual-run orphans outside this batch's scope but are the third copy of invariants 5 and 6.

## Rollup

- Verdicts: 14 KEEP, 0 otherwise. P0-equivalent guardrails (money/lifecycle/privacy/worker/backup invariants): all 14.
- Dead/stale: 1 failing-time-bomb (DirectEventDaoInsertGuardTest allowlist expiry), 1 silent no-op test (Recurring golden-dir scan), 2 expiry-governance holes (SourceScanning nested allowlist, CancellationSafety RAW_RUN_CATCHING_ALLOWLIST).
- True duplication pairs (JVM↔script): 6; partial/complementary overlaps: 2; intra-JVM sibling overlaps: 2; orphan kts guards covering same invariants: 2.
- Nothing recommended for deletion: all guards are live, referenced by the CI unit-test run, and enforce invariants not fully covered by the script plane.
