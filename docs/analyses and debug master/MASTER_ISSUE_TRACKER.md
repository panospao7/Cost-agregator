# Cost Aggregator — Master Issue Tracker for Pipelines 1–18

Last synced: 2026-07-02 (PR19 final correctness cleanup applied)  
Source docs commit: `886f5aca4a7738b425f2d0247a8f319f0f8f7412`  
Debug reports primarily audit pinned code around: `83b798e`

> This tracker is the single release-readiness source of truth for all Pipeline 1–18 findings.  
> Before closing any issue, verify against current `main`, because several reports reference older pinned code.

---

## 0. Current Release Verdict

**Overall status: YELLOW-GREEN — PR1–PR21 in progress. MIT-031/041 NEAR-COMPLETE (provenance guard, bank cleanup, item audit tests added). MIT-034/043/075 remain PARTIAL with defined closure paths.**

PR 1–21 (2026-07-01 through 2026-07-02) complete. MIT-031 (state/event atomicity) has TransactionContext provenance guard (6 allowlist entries: 4 canonical production, 2 deprecated writer impls). MIT-041 receipts: bank cancellation terminal policy documented (run-ledger-only), non-cancellation cleanup rethrows CE, skipped-item audit ledger tests added. MIT-034/043/075 remain PARTIAL per closure checklists. MIT-033 schema constraints are the next priority.

- MIT-031 (state/event atomicity): ⚠️ NEAR-COMPLETE — PR13+PR19-1+PR20 (DomainTransactionRunner, transaction token, writer enforcement)
- MIT-034 (cancellation propagation): ⚠️ PARTIAL — PR12a/b (structured allowlist, 19 runCatching replaced)
- MIT-041 (receipt/review atomicity): ⚠️ NEAR-COMPLETE — PR11+PR15+PR19-1+PR20 (all paths transaction-scoped, cancellation safe, failure finalization)
- MIT-043 (recurring atomicity): ⚠️ PARTIAL — PR14 (hidden write split, swallowed events fixed)
- MIT-033 (DB uniqueness constraints): TODO — next priority
- DB migration chain and schema parity: not yet proven.
- Restore/reset/import can leave stale singleton DB/DAO consumers alive.
- Worker drain/lease tracking can miss active work.
- Privacy gates can be bypassed before extraction, persistence, replay, or upload.
- Raw bank/email/import/receipt data can still be persisted or exposed.
- UI/ViewModels can still bypass legal write paths.
- Import exists, but not as a production-safe lifecycle-owned pipeline.
- Export/accounting schema and snapshot semantics are incomplete.
- Financial dashboard/forecast paths still contain money correctness bugs.
- CI does not yet enforce all architecture/security rules.

---

## 1. Pipeline Status Summary

| Pipeline | Area | Status | Release Risk |
|---|---|---:|---|
| P1 | Notification capture | RED | Privacy, restore, deferred payload fidelity, diagnostics |
| P2 | Transaction lifecycle | YELLOW | Delete/update/duplicate/correlation correctness |
| P3 | Receipt/OCR/email receipt links | YELLOW | Receipt atomicity fixed; review atomicity fixed; event guard active |
| P4 | Recurring/bill reminders | YELLOW | Occurrence/reminder atomicity fixed; hidden writes cleaned; projection TODO deferred |
| P5 | Currency/dashboard/analytics | RED | Cross-currency and shared income bugs |
| P6 | Budget/forecast/cashflow | RED | Forecast correctness, recurring direction, date boundaries |
| P7 | Backup/restore | YELLOW/RED-borderline | Stale DB consumers, non-atomic assets |
| P8 | Privacy/AI/redaction | RED | Raw persistence, cloud fail-open, redaction gaps |
| P9 | Workers/background jobs | **GREEN** | All worker issues resolved: lease registry, full guard, terminal logging, scheduling diagnostics, retention partial-failure visibility, static CI guards, privacy/permission enforcement, receiver safety |
| P10 | Bank integration/imports | RED | Demo/stub readiness, privacy, idempotency |
| P11 | Email receipt ingestion | RED/high-YELLOW | False positives, dedupe races, raw body/item privacy |
| P12 | Import/export/accounting | RED-ish | Import architecture mismatch, export/accounting gaps |
| P13 | DB schema/migrations/DAO constraints | RED | Major release blocker |
| P14 | UI/ViewModel action paths | YELLOW/RED-borderline | Direct DAO writes, stale DB UX |
| P15 | Hilt/DI/singleton lifetime | RED | Restore split-brain DB graph |
| P16 | Security/network/secrets | RED/high-YELLOW | Cloud/security/logging issues |
| P17 | CI/static guardrails | RED/high-YELLOW | Guards not fully enforced |
| P18 | Import support | RED | Import provenance/barrier/idempotency/parser safety |

---

## 2. Severity Legend

| Severity | Meaning |
|---|---|
| S0 | Release blocker: data loss, privacy/security leak, migration failure, restore corruption, major money correctness bug |
| S1 | Pipeline blocker: serious correctness/lifecycle/idempotency issue |
| S2 | Important hardening, coverage, UX, or documentation issue |
| S3 | Cleanup/documentation/non-blocking improvement |

---

## 3. Global Definition of Done

An issue is closable only when:

- [ ] Code fix is merged.
- [ ] Regression test fails before fix and passes after.
- [ ] Static/architecture guard exists if the bug class can recur.
- [ ] No raw PII, merchant text, bank details, receipt text, paths, tokens, emails, URLs, API keys, tax IDs, or file contents are logged or surfaced.
- [ ] `CancellationException` is not swallowed in suspend/worker paths.
- [ ] Restore/maintenance/read-write barrier behavior is tested where relevant.
- [ ] DB writes are routed through legal lifecycle owners.
- [ ] State changes and lifecycle/audit events are atomic where required.
- [ ] Dedupe/idempotency is enforced at DB level where race-prone.
- [ ] Docs and this tracker are updated with closing commit SHA.
- [ ] CI proves the fix.

---

## 4. Import Architecture Clarification

P12 and P18 appear contradictory but are both useful:

- P12’s “import missing” finding means the expected production-grade import architecture was not present or not found in expected domain/coordinator locations.
- P18 confirms import utilities exist under `util/ImportCoordinator.kt`, `util/CsvExpenseImporter.kt`, and `util/JsonExpenseImporter.kt`, but they are not production-safe.

**Canonical tracker interpretation:**

> Import exists as a utility-level implementation, but not as a production-safe, lifecycle-owned, barrier-checked, idempotent import pipeline.

Tracked by: **MIT-047, MIT-048, MIT-073**

---

## 5. Recommended Milestone Order

### M0 — CI and Guardrails First

Reason: without CI/static enforcement, later fixes can silently regress.

Includes: MIT-001 to MIT-005, MIT-003, MIT-004

### M1 — DB, Restore, DI, Worker Foundations

Reason: restore and migration bugs can corrupt every other pipeline.

Includes: MIT-010 to MIT-018, MIT-064, MIT-065, MIT-070

### M2 — Privacy/Security Fail-Closed Layer

Reason: raw data must not be read, persisted, replayed, logged, or uploaded incorrectly.

Includes: MIT-020 to MIT-028, MIT-069

### M3 — Lifecycle, Atomicity, Idempotency

Reason: state/event divergence and dedupe races create hard-to-repair data bugs.

Includes: MIT-030 to MIT-036, MIT-066, MIT-067

### M4 — Ingestion Pipelines

Reason: notification, receipt, bank, email, and import are high-risk raw-data entry points.

Includes: MIT-040 to MIT-048, MIT-071, MIT-073

### M5 — Financial Correctness

Reason: dashboards, budgets, forecasts, and exports must not misstate money.

Includes: MIT-050 to MIT-055, MIT-068, MIT-072

### M6 — UI/UX Release Hardening

Reason: UI must not bypass safe paths or allow stale DB usage.

Includes: MIT-060 to MIT-063

---

# 6. Master Issues

---

## M0 — CI and Static Guardrails

---

### MIT-001 — Make CI run full Gradle verification

**Severity:** S0  
**Pipelines:** P17, all  
**Status:** TODO  
**Labels:** `ci`, `release-blocker`, `static-guards`

#### Problem

GitHub Actions does not run the full verification set, especially `:app:check`, so Gradle-wired architecture guards can be skipped.

#### Tasks

- [ ] Add blocking PR job: `./gradlew :app:assembleDebug --stacktrace`.
- [ ] Add blocking PR job: `./gradlew :app:testDebugUnitTest --stacktrace`.
- [ ] Add blocking PR job: `./gradlew :app:lintDebug --stacktrace`.
- [ ] Add blocking PR job: `./gradlew :app:check --stacktrace`.
- [ ] Publish test/lint artifacts on failure.
- [ ] Fail PRs on skipped critical tasks unless explicitly allowlisted.
- [ ] Decide whether instrumented tests are blocking for release candidates or replaced by deterministic JVM tests for each release gate.

#### Acceptance Criteria

- [ ] A PR breaking an architecture guard fails CI.
- [ ] `:app:check` is visible in required GitHub status checks.
- [ ] CI docs explain how to reproduce locally.
- [ ] Release-candidate gate has deterministic coverage for privacy, restore, migration, worker, import, and money correctness.

---

### MIT-002 — Run all existing Python/static guard scripts in CI

**Severity:** S0  
**Pipelines:** P17, all  
**Status:** TODO  
**Labels:** `ci`, `architecture`, `release-blocker`

#### Tasks

- [ ] Run `scripts/verify_privacy_boundaries.py --root .`.
- [ ] Run `scripts/verify_db_access_boundaries.py --fail-on-violation`.
- [ ] Run `scripts/verify_event_writers.py --fail-on-violation`.
- [ ] Run `scripts/verify_money_boundaries.py --root .`.
- [ ] Run `scripts/verify_source_provenance_boundaries.py --root .`.
- [ ] Run `python -m pytest scripts/test_*.py -v`.
- [ ] Add source provenance guard to required CI.
- [ ] Validate workflows with `actionlint` and/or `yamllint`.

#### Acceptance Criteria

- [ ] Every existing guard is executed in CI.
- [ ] Script tests are blocking.
- [ ] Workflow syntax errors fail before merge.

---

### MIT-003 — Add missing architecture guards

**Severity:** S0  
**Pipelines:** P1, P3, P4, P7, P8, P9, P10, P13, P14, P15, P16, P17, P18  
**Status:** TODO  
**Labels:** `architecture`, `static-guards`, `release-blocker`

#### Missing Guards

- [ ] Worker full guard/lease/run-ledger guard.
- [ ] Unsafe `runCatching` / broad `catch(Exception)` cancellation guard.
- [ ] UI/ViewModel direct DAO injection/write guard.
- [ ] Receipt direct link/update guard.
- [ ] Recurring reminder state/event atomicity guard.
- [ ] Recurring DAO mutator ownership guard.
- [ ] Cloud fail-closed capability guard.
- [ ] Semantic cloud-redaction test guard.
- [ ] Import lifecycle/provenance guard.
- [ ] DI singleton DB/DAO injection guard.
- [ ] Debug/demo/stub/no-op release binding guard.
- [ ] Raw cross-currency money sum guard.
- [ ] Mutating DAO barrier guard.
- [ ] PII logging/exception message guard.
- [ ] Raw notification fingerprinting helper guard.
- [ ] Direct raw `RequestBody` cloud path guard.

#### Acceptance Criteria

- [ ] New bad code examples are rejected by CI.
- [ ] Allowlist entries are minimal, justified, and owner-reviewed.
- [ ] Guard docs include examples of violations.

---

### MIT-004 — Add real migration execution matrix to CI

**Severity:** S0  
**Pipelines:** P13, P17  
**Status:** TODO  
**Labels:** `database`, `migration`, `ci`, `release-blocker`

#### Tasks

- [ ] Define supported minimum DB version.
- [ ] Test migrations from every supported historical version to latest.
- [ ] Test fresh install schema equals migrated schema.
- [ ] Include indexes, triggers, constraints, FKs, and default values.
- [ ] Run migration matrix in CI.

#### Acceptance Criteria

- [ ] Old supported DBs migrate successfully.
- [ ] Fresh and migrated schema parity is proven.
- [ ] Missing migration registration fails CI.

---

### MIT-005 — Reduce stale/ignored test debt threshold

**Severity:** S1  
**Pipelines:** P1, P17, all  
**Status:** TODO  
**Labels:** `tests`, `quality`

#### Tasks

- [ ] Inventory ignored/skipped tests.
- [ ] Classify as obsolete, flaky, or valid debt.
- [ ] Lower threshold gradually.
- [ ] Convert release-blocking ignored tests into active tests.
- [ ] Specifically revive/replace stale P1 privacy/restore/runtime tests.

#### Acceptance Criteria

- [ ] Threshold is justified and decreasing.
- [ ] Critical privacy/restore/migration tests cannot be ignored silently.

---

## M1 — DB, Restore, DI, Workers

---

### MIT-010 — Register full DB migration chain or define explicit baseline policy

**Severity:** S0  
**Pipelines:** P13  
**Status:** TODO  
**Labels:** `database`, `migration`, `release-blocker`

#### Problem

Runtime Room builder appears to use `DatabaseMigrations.ALL`, but reports indicate this may only contain `145→146` and `146→147`; historical `6→145` inline migrations may not be registered.

#### Tasks

- [ ] Confirm actual runtime migration registration.
- [ ] Register full supported migration chain.
- [ ] Or define explicit destructive/baseline policy for unsupported versions.
- [ ] Add user-safe fallback messaging if old versions are unsupported.
- [ ] Add migration tests from minimum supported version.

#### Acceptance Criteria

- [ ] Supported installed DBs below v145 do not fail migration.
- [ ] Unsupported versions are handled intentionally, not accidentally.

---

### MIT-011 — Prove fresh schema equals migrated schema and declare critical indexes

**Severity:** S0  
**Pipelines:** P13  
**Status:** TODO  
**Labels:** `database`, `schema`, `constraints`

#### Tasks

- [ ] Diff fresh install schema vs migrated schema.
- [ ] Move critical indexes into entity declarations or verified migrations.
- [ ] Verify unique/partial indexes for dedupe/idempotency.
- [ ] Add schema parity test to CI.
- [ ] Map every P13 table/index/constraint issue to a concrete migration or entity declaration.

#### Acceptance Criteria

- [ ] Fresh install and migrated DB have equivalent constraints/indexes.
- [ ] Any intentional difference is documented.

---

### MIT-012 — Update backup verifier table coverage and semantic aggregates

**Severity:** S1  
**Pipelines:** P7, P11, P13  
**Status:** TODO  
**Labels:** `backup`, `database`, `verification`

#### Tasks

- [ ] Update verifier table tiers for all current entities.
- [ ] Add semantic aggregate verification to `.costbackup` manifest.
- [ ] Verify row counts plus semantic financial totals.
- [ ] Include receipt/source-link/import/bank/email tables.
- [ ] Cover `email_receipt_sources`.
- [ ] Promote or semantically verify exchange-rate/currency tables if backup claims financial equivalence.
- [ ] Add snapshot consistency test for fallback drained file-copy while writes are attempted.
- [ ] Remove stale legacy-import KDoc/comment saying no journal/maintenance if outdated.

#### Acceptance Criteria

- [ ] Backup verification catches missing critical tables.
- [ ] Manifest includes semantic aggregate metadata.
- [ ] Backup docs/comments match actual restore/import implementation.

---

### MIT-013 — Choose and enforce restore-safe DB lifetime strategy

**Severity:** S0  
**Pipelines:** P7, P14, P15  
**Status:** TODO  
**Labels:** `restore`, `hilt`, `database`, `release-blocker`

#### Problem

Restore/reset/import can replace the DB file while Hilt singleton `AppDatabase`/DAO consumers keep stale references.

#### Decision Required

Choose one:

##### Option A — Hard restart after restore/reset/import

- [ ] Force process restart after DB file swap.
- [ ] Prevent restart-required dismissal from resuming DB-backed screens.
- [ ] Do not reschedule workers before restart.
- [ ] Block old ViewModels/repos/workers from touching stale DB.

##### Option B — Reopenable database provider

- [ ] Remove direct singleton DAO injection.
- [ ] Repositories fetch current DAO/database per operation.
- [ ] Atomically swap provider DB.
- [ ] Invalidate old flows and app-scope jobs.

#### Extra Requirements

- [ ] No singleton DAO may be injected into long-lived repositories if restore can swap DB without process restart.
- [ ] If hard-restart strategy is chosen, UI must not allow bypass/dismiss into normal DB-backed app state.

#### Acceptance Criteria

- [ ] No split-brain DB state after restore.
- [ ] Tests prove old references cannot write after restore.

---

### MIT-014 — Add maintenance owner/session tokens

**Severity:** S0  
**Pipelines:** P7, P9, P15  
**Status:** TODO  
**Labels:** `maintenance-mode`, `workers`, `restore`

#### Tasks

- [ ] Add maintenance session ID/owner token.
- [ ] Only owner may end its session.
- [ ] Nested/concurrent maintenance operations are deterministic.
- [ ] Workers respect maintenance owner/session state.

#### Acceptance Criteria

- [ ] Concurrent restore/import/backup/repair operations cannot incorrectly clear each other.
- [ ] Tests cover overlapping maintenance sessions.

---

### MIT-015 — Make receipt asset restore crash-safe and resumable

**Severity:** S1  
**Pipelines:** P7, P15  
**Status:** TODO  
**Labels:** `restore`, `receipts`, `assets`

#### Tasks

- [ ] Journal asset restore operations.
- [ ] Use temp/staging paths and atomic rename.
- [ ] Resume or rollback after crash.
- [ ] Redact asset filenames/paths from logs.

#### Acceptance Criteria

- [ ] Crash mid-asset-restore leaves no corrupt committed state.
- [ ] Next app start can recover or resume safely.

---

### MIT-016 — Fix worker lease registry and enforce full worker guard

**Severity:** S0  
**Pipelines:** P1, P4, P7, P9, P15, P17  
**Status:** **DONE** — closure date: 2026-06-30  
**Closed by:** PRs 1–11 + PR12A–PR12F on branch `worker-architecture-prs-1-5` (HEAD `886f5aca`)  
**Labels:** `workers`, `restore`, `release-blocker`

#### Tasks

- [x] Track leases by unique lease ID or `workerName → set`.
- [x] Wrap `NotificationIntakeWorker` in full `WorkerExecutionGuard`.
- [x] Ensure `NotificationIntakeWorker` barrier-checks before first DAO read.
- [x] Ensure every DB-writing worker has lease + barrier + run ledger.
- [x] Add active-worker drain tests.
- [x] Add lease acquire-after-stop gate (PR6A).
- [x] Add blocked/retry policy for restore/write-barrier blocks (PR6A).
- [x] Add static guard for worker guard usage (PR 10).
- [x] Add source-scanning guard for new worker auto-detection (PR12F).

#### Acceptance Criteria

- [x] Restore drain cannot miss active same-name workers.
- [x] Restore drain cannot miss workers acquired after stop request (PR6A).
- [x] Dynamic one-shot blocked by restore/write-barrier returns retry, not success (PR6A).
- [x] Unguarded DB-writing worker fails CI (PR 10 + PR12F).

#### Final Acceptance Checklist (PR12G)

- [x] WorkerRunLogger terminal DB update ordering fixed — DB flush before AtomicBoolean release (PR12B).
- [x] Stale recovery conditional SQL uses proper WHERE clause (PR12B).
- [x] Guard privacy/permission policies honored before worker body execution (PR12C).
- [x] Notification intake privacy cleanup guarded — checkpoint before decrypt, raw payloads purged (PR12D).
- [x] Raw and transient payloads purged on privacy revocation (PR12D).
- [x] Checkpoint written before decrypt (PR12D).
- [x] Receivers do not mutate DB directly — structured scope, CE rethrow, no direct DAO (PR12E).
- [x] Room schema 147/148 cleanup — valid migration path, fresh/migrated parity (PR12A).
- [x] Static guards discover new workers automatically — `SourceScanningArchitectureGuardTest` (PR12F).

---

### MIT-017 — Fix one-shot worker version bump and terminal run logging

**Severity:** S1  
**Pipelines:** P9  
**Status:** **DONE** — closure date: 2026-06-30  
**Closed by:** PRs 1–11 + PR12A–PR12F on branch `worker-architecture-prs-1-5` (HEAD `886f5aca`)  
**Labels:** `workers`, `diagnostics`

#### Tasks

- [x] Use `REPLACE` or cancel+enqueue when one-shot worker version changes.
- [x] Make `WorkerRunLogger.Handle` terminal writes atomic with compare-and-set.
- [x] Sync worker comments/docs after implementation.
- [x] Add blocked policy for restore/write-barrier blocked runs (PR6A).
- [x] Daily briefing reschedule failure should be recoverable (PR 7).
- [x] Data retention partial failures should not soft-success silently (PR 6E).
- [x] `WorkerRegistry.scheduleAll()` must log/write sanitized diagnostic for each failed schedule entry (PR 7).
- [x] Fix terminal DB update ordering for shutdown races (PR12B).

#### Acceptance Criteria

- [x] Stale work does not survive incompatible version bumps.
- [x] Worker terminal state cannot be double-written under race.
- [x] Partial failures are visible/durable (PR 6).
- [x] Per-entry schedule failures are visible (PR 7).
- [x] Terminal DB update is correctly ordered before AtomicBoolean release (PR12B).

#### Final Acceptance Checklist (PR12G)

- [x] WorkerRunLogger terminal DB update ordering fixed — DB flush before AtomicBoolean release (PR12B).
- [x] Stale recovery conditional SQL uses proper WHERE clause (PR12B).
- [x] Guard privacy/permission policies honored before worker body execution (PR12C).
- [x] Notification intake privacy cleanup guarded — checkpoint before decrypt, raw payloads purged (PR12D).
- [x] Raw and transient payloads purged on privacy revocation (PR12D).
- [x] Checkpoint written before decrypt (PR12D).
- [x] Receivers do not mutate DB directly — structured scope, CE rethrow, no direct DAO (PR12E).
- [x] Room schema 147/148 cleanup — valid migration path, fresh/migrated parity (PR12A).
- [x] Static guards discover new workers automatically — `SourceScanningArchitectureGuardTest` (PR12F).

---

### MIT-018 — Stop app-scope stale coroutine/database usage after maintenance

**Severity:** S1  
**Pipelines:** P15  
**Status:** TODO  
**Labels:** `coroutines`, `restore`, `hilt`

#### Tasks

- [ ] Inventory app-scope coroutines and long-lived flows.
- [ ] Cancel or invalidate them during restore/reset/import.
- [ ] Prove no stale DB/DAO reference survives post-restore.

#### Acceptance Criteria

- [ ] Post-restore app-scope jobs cannot write to old DB.

---

## M2 — Privacy and Security

---

### MIT-020 — Prevent notification text/extras read before capture gate allows

**Severity:** S0  
**Pipelines:** P1, P8  
**Status:** TODO  
**Labels:** `privacy`, `notifications`, `release-blocker`

#### Tasks

- [ ] Ensure no extras/text/body is read on `TemporarilyUnavailable`.
- [ ] Gate package privacy and blocked-package state before extraction.
- [ ] Add regression tests for denied/temporary privacy states.
- [ ] Add static guard if possible.
- [ ] Preserve full notification payload fields after legal gate: `combinedBody`, `textLines`, messages.
- [ ] Move raw notification fingerprinting to shared hashing helper.

#### Acceptance Criteria

- [ ] Disallowed notification payloads are never inspected, decrypted, persisted, logged, or queued.

---

### MIT-021 — Re-check privacy before decrypting/replaying queued notification payloads

**Severity:** S0  
**Pipelines:** P1, P8, P9  
**Status:** TODO  
**Labels:** `privacy`, `workers`, `notifications`

#### Tasks

- [ ] Worker must re-check privacy and blocked-package state before decrypt/process.
- [ ] If denied, payload is not decrypted.
- [ ] Terminal intake state includes durable sanitized diagnostic.
- [ ] Tests cover privacy revoked after queueing.
- [ ] Deferred fingerprint must distinguish same key with different content/post time.

#### Acceptance Criteria

- [ ] Revoked privacy prevents queued payload replay.
- [ ] Same-key/different-content notifications are not collapsed incorrectly.

---

### MIT-022 — Make CloudPayloadPolicy fail closed

**Severity:** S0  
**Pipelines:** P8, P16, P17  
**Status:** TODO  
**Labels:** `privacy`, `cloud`, `security`

#### Tasks

- [ ] Require explicit cloud capability/permission inside payload policy.
- [ ] Return hard failure when cloud is disabled.
- [ ] Add tests for every cloud provider path.
- [ ] Add CI guard against direct cloud payload creation.
- [ ] Expand cloud capability coverage and enforce inside payload policy.

#### Acceptance Criteria

- [ ] Caller mistakes cannot prepare/send cloud payloads when cloud is disabled.
- [ ] `EffectiveCloudAiPolicy.requireAllowed()` or equivalent covers all relevant capabilities.

---

### MIT-023 — Add semantic redaction for merchant/item/category/amount data

**Severity:** S0  
**Pipelines:** P8, P16  
**Status:** TODO  
**Labels:** `privacy`, `redaction`, `ai`

#### Tasks

- [ ] Add purpose-specific semantic redaction.
- [ ] Add golden tests for merchants, receipt line items, categories, amounts, emails, URLs, tax IDs, Greek AFM, IBAN/card-like numbers, addresses, API keys.
- [ ] Make redaction fail closed when uncertain.
- [ ] Ensure cloud assistant, receipt AI, bank import, email receipt, and analytics use the same policy.

#### Acceptance Criteria

- [ ] Golden tests prove sensitive semantics are removed or generalized.

---

### MIT-024 — Replace raw privacy audit maps with typed safe metadata

**Severity:** S1  
**Pipelines:** P8, P16  
**Status:** TODO  
**Labels:** `privacy`, `audit`, `logging`

#### Tasks

- [ ] Replace raw `Map` audit contexts with typed metadata.
- [ ] Each field must be value-safe by construction.
- [ ] Remove length-only allowlist checks as primary defense.
- [ ] Add compile-time/static guard.
- [ ] Typed audit metadata must be preserved safely, not silently dropped.
- [ ] Audit allowlisted values must be value-scanned.

#### Acceptance Criteria

- [ ] Raw arbitrary audit key/value maps cannot be submitted.
- [ ] Safe metadata survives audit serialization.

---

### MIT-025 — Move sensitive hashing to install-specific Keystore secret

**Severity:** S1  
**Pipelines:** P8, P16  
**Status:** TODO  
**Labels:** `security`, `hashing`, `keystore`

#### Tasks

- [ ] Generate install-specific Keystore-backed secret.
- [ ] Version hash format.
- [ ] Add migration/compatibility behavior for old hashes.
- [ ] Document backup/restore implications.

#### Acceptance Criteria

- [ ] Hashes are not linkable across installs unless explicitly intended.

---

### MIT-026 — Sanitize all logs, exceptions, paths, tokens, and UI-visible errors

**Severity:** S0  
**Pipelines:** P3, P8, P10, P11, P14, P16, P18  
**Status:** TODO  
**Labels:** `privacy`, `logging`, `security`

#### Tasks

- [ ] Remove production logs containing category IDs/frequency details if sensitive.
- [ ] Redact bank token blobs and override/redact `BankConnection.toString()`.
- [ ] Redact backup asset filenames/paths.
- [ ] Sanitize exception messages for URL/email/API-key/tax-ID/path/token/merchant/amount/file-content data.
- [ ] Replace direct Android `Log` in sensitive providers with sanitized logger.
- [ ] Ensure snackbar/error messages are sanitized.
- [ ] Release log scan must cover email provider/parser logs.
- [ ] Cloud provider raw `e.message` must not reach UI.

#### Acceptance Criteria

- [ ] No known sensitive value appears in release logs, diagnostics, exceptions, or UI errors.

---

### MIT-027 — Restrict receipt cloud upload to safe asset IDs/URIs

**Severity:** S0  
**Pipelines:** P8, P16  
**Status:** TODO  
**Labels:** `privacy`, `receipts`, `cloud`

#### Tasks

- [ ] Replace raw `imagePath` input with receipt asset ID or allowlisted URI.
- [ ] MIME sniff before upload.
- [ ] Verify file belongs to app-managed receipt asset store.
- [ ] Add negative tests for arbitrary file paths.

#### Acceptance Criteria

- [ ] Compromised caller cannot upload arbitrary local files.

---

### MIT-028 — Enable release security hardening

**Severity:** S1  
**Pipelines:** P10, P14, P15, P16, P17  
**Status:** TODO  
**Labels:** `security`, `release`

#### Tasks

- [ ] Enable R8/minify for release or document/test explicit waiver.
- [ ] Add release CI check for no API keys/secrets.
- [ ] Add check for no BODY-level HTTP logging in release.
- [ ] Verify no cleartext endpoints in release.
- [ ] Verify debug/demo/raw routes are absent or blocked in release.
- [ ] Verify fake/stub/no-op bindings cannot ship accidentally.
- [ ] Encode backup KDF parameters in encrypted header/manifest.
- [ ] Verify restored bank-token blobs on another device trigger clean reauth.
- [ ] Full OkHttp/RequestBody inventory.
- [ ] Release build must fail if any direct raw `RequestBody` cloud path bypasses `PreparedCloudPayload`.

#### Acceptance Criteria

- [ ] Release APK/AAB passes secret/log/stub/network scan.

---

## M3 — Lifecycle, Atomicity, Idempotency

---

### MIT-030 — Enforce write barrier for all mutating DB paths

**Severity:** S0  
**Pipelines:** P1, P4, P7, P10, P12, P13, P14, P15, P18  
**Status:** TODO  
**Labels:** `database`, `write-barrier`, `architecture`

#### Known Risk Paths

- Notification intake coordinator.
- Notification recovery scheduler.
- Notification repairer.
- `getDueReminders()` stale-claim recovery.
- Bank disconnect ViewModel path.
- Import category creation.
- Import coordinator.
- Category import paths.
- Accounting category/source reads after initial barrier.
- Direct DAO calls in UI/import/bank paths.

#### Tasks

- [ ] Inject/check write barrier in every mutating path.
- [ ] Recheck read barrier before all accounting category/source reads.
- [ ] Remove or justify all `requires_write_barrier:false` allowlist entries.
- [ ] Add static guard for mutating DAO calls.
- [ ] Add restore-mode tests for each path.

#### Acceptance Criteria

- [ ] No DB write can occur during restore/maintenance unless explicitly owned and allowed.

---

### MIT-031 — Make state changes and lifecycle events atomic

**Severity:** S0  
**Pipelines:** P3, P4, P9, P17  
**Status:** ⚠️ **NEAR-COMPLETE** — PR 1–21 (2026-07-02). TransactionContext provenance guard (TransactionContextProvenanceGuardTest) bans manual construction. 4 callers (GroupTransactionCoordinator, NotificationProcessingPipeline, WarrantyTrackerRepository, ReceiptLinkService) on 45-day DomainTransactionRunner migration expiry. Direct event DAO guard structured with rule classification.  
Residual: 4 callers must migrate to DomainTransactionRunner before 2026-08-15 or guard fails.
**Labels:** `transactions`, `events`, `atomicity`

#### Implemented

- **PR 3:** `DomainTransactionRunner`, `TransactionContext`, `TransactionalEventWriter` infrastructure.
- **PR 4:** Receipt save + PendingReview insert + RECEIPT_SAVED event now atomic in `ReceiptLifecycleCoordinator.processReceiptInput` and `processEmailReceipt`.
- **PR 5:** Bank-statement receipt insert + run attachment + RECEIPT_SAVED event wrapped in single `database.withTransaction` in `BankStatementLifecycleProcessor`.
- **PR 6:** Six recurring lifecycle methods (`updateOccurrenceStatus`, `cancelClaimedReminderDelivery`, `markReminderSent`, `markReminderFailed`, `generateOccurrences`, `reconcileExpenseLinkAfterUpdate`) wrapped in `database.withTransaction`.
- **PR 3:** `DirectEventDaoInsertGuardTest` static guard blocks direct event DAO inserts outside approved files.
- **PR 8:** `PostCommitSideEffectEvidenceService` records durable evidence of post-commit side-effect outcomes.
- **PR 9:** `LegacyDataConsistencyChecker` scans for orphaned state (receipts without events, occurrences without events, PendingReviews without receipts).

#### Acceptance Criteria

- [x] No state/event divergence after exception, cancellation, or crash.
- [x] Static guard prevents future split writes.
- [x] Orphan-detection diagnostics available for pre-PR legacy data.

#### Remaining

- `TransactionLifecycleCoordinator` still uses direct `database.withTransaction` rather than `DomainTransactionRunner` (low-priority migration deferred).
- `RecurringOccurrenceMaterializer` still injects `RecurringLifecycleEventDao` directly (known LEGAL_PATHS deviation, deferred).

---

### MIT-032 — Fix transaction lifecycle delete/update/duplicate behavior

**Severity:** S1  
**Pipelines:** P2  
**Status:** TODO  
**Labels:** `expenses`, `transactions`

#### Tasks

- [ ] `deleteExpense(expense)` must not return success if row is missing.
- [ ] Durably record update validation failures.
- [ ] Replace negative-ID duplicate sentinel with typed outcome.
- [ ] Avoid double-logging duplicate/conflict events.
- [ ] Implement duplicate-source link-to-existing behavior.
- [ ] Add timeout/fallback for `homeCurrency().first()`.
- [ ] Add durable restore-blocked evidence for update/delete paths.

#### Acceptance Criteria

- [ ] Stale entity delete returns clear failure.
- [ ] Duplicate creates are deterministic and auditable.
- [ ] Source links point to existing duplicate when policy says so.

---

### MIT-033 — Add DB-level uniqueness/idempotency constraints

**Severity:** S0  
**Pipelines:** P4, P10, P11, P13, P18  
**Status:** TODO  
**Labels:** `database`, `idempotency`, `dedupe`

#### Needed Constraints

- [ ] Email message hash/fingerprint uniqueness.
- [ ] Recurring `linkedExpenseId` uniqueness across rules/occurrences.
- [ ] Bank transaction uniqueness scoped by provider + account/connection + transaction ID.
- [ ] Bank connection uniqueness scoped by provider/account as needed.
- [ ] Group expense/member/payment dedupe constraints.
- [ ] Operation event idempotency where applicable.
- [ ] Import file/row/batch/fingerprint uniqueness.
- [ ] Category/import path safety constraints.

#### Acceptance Criteria

- [ ] Duplicate races are prevented by DB constraints, not only app logic.

---

### MIT-034 — Fix cancellation propagation everywhere

**Severity:** S0  
**Pipelines:** P3, P4, P8, P9, P12, P16, P17, P18  
**Status:** ⚠️ PARTIAL — PR12a/b (19 runCatching replaced, structured allowlist), PR19-1 (bank cancellation never masks CE). Core paths fixed. Remaining: ~65 UI ViewModels in allowlist (non-critical), global cancellation closure not proven.  
**Labels:** `coroutines`, `workers`, `correctness`

#### Implemented

- **PR 1:** `CANCELLATION_POLICY.md` defines allowed/forbidden CE patterns.
- **PR 2:** `CancellationSafe` helper (`rethrowIfCancellation`, `runCatchingCancellable`).
- **PR 2:** 20 CE-gap sites fixed across 6 high-risk files (RecommendationInvalidator, RecommendationDismissalHandler, RecommendationLifecycleManager, RecommendationStateManager, NotificationCaptureGate, OnDeviceCategorizationAssistService).
- **PR 2:** Gradated 6 files from `CancellationSafetyArchitectureGuardTest` KNOWN_VIOLATIONS (now scanned and must remain CE-compliant).
- **PR 2:** Guard regex updated to recognize `rethrowIfCancellation` pattern.
- **Pre-existing (U-PR1):** 146 CE guards across 38 files already landed. `WorkerExecutionGuard` already rethrows CE.

#### Acceptance Criteria

- [x] Cancellation never becomes success/failure state incorrectly.
- [x] Static guard detects new CE-swallowing violations in CI.

#### Remaining

- ~65 files in KNOWN_VIOLATIONS (UI ViewModels, AI providers, utilities) — lower priority, can be addressed incrementally.
- Detekt custom rule deferred (architecture guard test provides equivalent CI enforcement).

---

### MIT-035 — Add durable operation run ledgers/checkpoints

**Severity:** S1  
**Pipelines:** P1, P9, P10, P12, P18  
**Status:** TODO  
**Labels:** `diagnostics`, `operation-ledger`, `resumability`

#### Needed Ledgers

- [ ] Notification worker terminal diagnostics.
- [ ] Pre-launch/drop/retry notification diagnostics.
- [ ] Bank sync run/page checkpoint/cursor.
- [ ] Export operation run.
- [ ] Import operation run.
- [ ] Import row ledger.
- [ ] Retention partial-failure reporting.

#### Acceptance Criteria

- [ ] Long-running operations are resumable or durably diagnosable.

---

### MIT-036 — Strengthen DAO ownership policy

**Severity:** S0  
**Pipelines:** P2, P4, P10, P13, P14, P15, P18  
**Status:** TODO  
**Labels:** `database`, `architecture`

#### Tasks

- [ ] Align source code, allowlist, and docs.
- [ ] Remove direct mutating DAO access from UI/ViewModels.
- [ ] Remove direct mutating DAO access from importers unless through legal owner.
- [ ] Restrict `ManualRecurringExpenseDao` and recurring mutators to approved owner.
- [ ] Narrow DAO visibility if possible.
- [ ] Add compile/static check.

#### Acceptance Criteria

- [ ] Mutating DAOs are only reachable from approved lifecycle owners.

---

## M4 — Ingestion Pipelines

---

### MIT-040 — Fix receipt link ownership

**Severity:** S0  
**Pipelines:** P3  
**Status:** TODO  
**Labels:** `receipts`, `links`, `atomicity`

#### Tasks

- [ ] Route manual approve through `ReceiptLinkService`.
- [ ] Route clear/unlink through `ReceiptLinkService`.
- [ ] Add static guard against direct `expenseId` link mutation.
- [ ] Add tests for link, relink, unlink, rollback.
- [ ] Test nested receipt-link call inside parent receipt transaction; no side effects may escape rollback.

#### Acceptance Criteria

- [ ] Receipt link state cannot diverge from lifecycle events/source links.

---

### MIT-041 — Make receipt/OCR/bank-statement review writes atomic

**Severity:** S0  
**Pipelines:** P3, P10  
**Status:** ⚠️ **NEAR-COMPLETE** — PR 1–21 (2026-07-02). Bank: cancellation never masks CE (addSuppressed), non-cancellation cleanup rethrows CE (PR21-3), unexpected failure writes receipt event atomically (PR20-1), cancellation terminal policy documented as run-ledger-only (PR21). Skipped/failed item audit tests added (BankStatementItemAuditTest, 7 tests).  
Residual: CI must be green. BankApiIntegration.kt stub only.
**Labels:** `receipts`, `ocr`, `pending-review`

#### Implemented

- **PR 4:** `ReceiptLifecycleCoordinator.processReceiptInput` — PendingReview insert moved inside `database.withTransaction` block alongside receipt insert and RECEIPT_SAVED event.
- **PR 4:** `ReceiptLifecycleCoordinator.processEmailReceipt` — PendingReview now created inside the transaction when `needsReviewReason` is set (low_confidence, validation_failed, insert_conflict, create_error, incomplete_parse).
- **PR 5:** `BankStatementLifecycleProcessor.processBankStatement` — Receipt insert, run attachment, and initial RECEIPT_SAVED + PDF_PARTIAL events wrapped in single `database.withTransaction`.
- **PR 3:** `DirectEventDaoInsertGuardTest` enforces that event DAO inserts only come from approved coordinator files.

#### Acceptance Criteria

- [x] No saved receipt requiring review can exist without its review row.
- [x] Bank-statement receipt + events cannot partially commit.

#### Remaining

- `PendingReviewDao.insert()` in `BankApiIntegration.kt` is in APPROVED_FILES but not routed through a receipt coordinator (low priority — bank integration is stub/demo).
- Per-item review creation in bank statement `for` loop uses individual inner transactions (acceptable — per-item failure doesn't roll back receipt).

---

### MIT-042 — Fix bill reminder permission and delivery state handling

**Severity:** S0  
**Pipelines:** P4, P9  
**Status:** TODO  
**Labels:** `reminders`, `notifications`, `workers`

#### Tasks

- [ ] Set `requiresNotificationPermission=true` before claim.
- [ ] Ensure worker guard enforces permission.
- [ ] Denied permission should not permanently lose due reminders.
- [ ] Add retry/recovery path for `FAILED_PERMISSION`.

#### Acceptance Criteria

- [ ] Permission denial does not silently lose reminders.

---

### MIT-043 — Fix recurring/bill reminder duplicate fulfillment and hidden writes

**Severity:** S0  
**Pipelines:** P4  
**Status:** ⚠️ **PARTIAL by design** — PR 1–23 (2026-07-02). Hidden writes split (PR14), swallowed events fixed (PR14), stale recovery transactional with event attempt (PR19-2), deprecated reconcile at DeprecationLevel.ERROR. Remaining: DB uniqueness (MIT-033), best-effort regeneration accepted as product policy, stale recovery designated as operational diagnostic (non-critical lifecycle). Full closure depends on MIT-033. See TRANSACTIONAL_EVENT_POLICY.md §15.  
**Labels:** `recurring`, `reminders`, `database`

#### Implemented

- **PR 6:** Six methods wrapped in `database.withTransaction` for atomic state+event:
  - `updateOccurrenceStatus`, `cancelClaimedReminderDelivery`, `markReminderSent`, `markReminderFailed` (HIGH — non-atomic write pairs fixed)
  - `generateOccurrences` (MEDIUM — read-write skew fixed)
  - `reconcileExpenseLinkAfterUpdate` snapshot branch (MEDIUM — fixed)
- **PR 7:** Hidden write cleanup:
  - `getDueReminders()` split from `recoverStaleClaimedDeliveries()` — pure read + explicit `recoverAndGetDueReminders()` + public `recoverStaleClaimedDeliveries()`.
  - `reconcilePlannedVsActual()` wrapped in `database.withTransaction` (generate + read atomic).
  - `BillReminderWorker` updated to call `recoverAndGetDueReminders()`.
- **PR 7:** `FinancialHealthScoreV2.calculateHealthScore()` documented with side-effect note.

#### Acceptance Criteria

- [x] State updates and lifecycle events are atomic for recurring operations.
- [x] Query methods (`getDueReminders`) are read-only; recovery is explicit.
- [x] Reminder content respects privacy settings (pre-existing).

#### Remaining

- DB-level `linkedExpenseId` uniqueness (MIT-033) is separate and not yet implemented.
- `RecurringPlanProjectionService.projectFromRule` TODO for full atomicity (lower priority).

---

### MIT-075: Post-Commit Side-Effect Failure Evidence

**Priority:** P1 (High)
**Domain:** Side Effect Pipeline
**Source:** CANCELLATION_ATOMICITY_BASELINE.md §4.2, MASTER_ISSUE_TRACKER_SUPPLEMENT_2.md

**Issue:** Post-commit side effects run in-memory only, with no durability guarantee.
If the app crashes between DB commit and side-effect execution, effects are silently lost.

**Resolution summary:**
- PR 8: Implemented PostCommitSideEffectEvidenceService with DiagnosticEvent emission.
- PR17: Added Volatile diagnostic counters (actionsDispatched, actionsCompleted, actionsFailed).
- PR17: Documented architectural decision — no durable outbox at this time
  (justified in TRANSACTIONAL_EVENT_POLICY.md §12).
- Side effects are non-critical (budget checks, merchant learning) or re-triggerable
  (receipt matching via idempotency keys).

**Status:** ⚠️ **PARTIAL by design** — PR 1–24 (2026-07-02). PostCommitSideEffectEvidenceService operational (PR8), 11 bounded reason codes (PR17), Volatile diagnostic counters (PR17). No durable outbox: explicit architectural decision per TRANSACTIONAL_EVENT_POLICY.md §12 and §16. Non-critical effects tolerate loss on crash; critical effects are idempotent-key-guarded.

---

### PR19 Final Correctness Cleanup (2026-07-02)

PR19-1: BankStatement importRunId non-null, run finalization atomic with receipt status/event, cancellation finalization bounded/safe.
PR19-2: Recurring stale recovery evented/transactional/write-barrier-gated; deprecated reconcile deactivated (ERROR level); per-window reminder regeneration logged.
PR19-5: Consistency checker summary diagnostic includes failedChecks metadata; failed subchecks emit FAILED_RETRYABLE not COMPLETED.
PR19-6: Retention purge errors sanitized — only class name stored, never raw Throwable.message.
PR19-7: Bank statement per-item lifecycle policy documented (§13).
PR19-8: Tracker corrected to reflect actual test-proven state.

---

### MIT-044 — Make bank integration production-safe before enabling

**Severity:** S0  
**Pipelines:** P10, P16  
**Status:** TODO  
**Labels:** `banking`, `security`, `privacy`

#### Tasks

- [ ] Keep stub/demo bank API release-disabled until real OAuth exists.
- [ ] Implement OAuth session/state/PKCE/callback validation.
- [ ] Add provider sync cursor/page checkpoint.
- [ ] Update cursor only after page commit.
- [ ] Scope idempotency by provider + account/connection + transaction ID.
- [ ] Route disconnect through repository/lifecycle coordinator with barrier.
- [ ] Redact token blobs and `toString()` output.
- [ ] Verify backup/export/token redaction.
- [ ] Define statement import partial-failure rollback/continue semantics.
- [ ] Validate finite amount/currency before any review/import row.
- [ ] Add provider/account-scoped unique constraints for bank connections.
- [ ] Add parser locale/date/currency/sign tests.
- [ ] UI connect must route to real OAuth or explicit demo-disabled state.

#### Acceptance Criteria

- [ ] Production bank integration cannot ship as demo/stub.
- [ ] Bank sync is idempotent and resumable.
- [ ] Invalid amounts/currencies cannot create review/import rows.

---

### MIT-045 — Sanitize bank/email/import raw persistence before DB writes

**Severity:** S0  
**Pipelines:** P8, P10, P11, P18  
**Status:** TODO  
**Labels:** `privacy`, `ingestion`, `raw-data`

#### Tasks

- [ ] Apply bank persistence payload before DB write.
- [ ] Apply email receipt persistence payload in real write path.
- [ ] Apply import persistence payload/error sanitizer.
- [ ] Do not store raw merchant/description unless policy explicitly allows encrypted local-only storage.
- [ ] Use nullable OCR sanitizer in bank statement path; distinguish null, empty, and dropped.
- [ ] Redacted receipt/email modes must not persist raw item names in `parsedItemsJson`.
- [ ] Add tests for low-confidence bank/email/import rows.

#### Acceptance Criteria

- [ ] Pending review/import rows contain privacy-safe text only.

---

### MIT-046 — Fix email receipt provider proof, dedupe, and review queue

**Severity:** S0  
**Pipelines:** P11  
**Status:** TODO  
**Labels:** `email`, `receipts`, `dedupe`

#### Tasks

- [ ] Remove broad sender/body substring auto-parse fallback.
- [ ] Require strong parser/provider proof.
- [ ] Body-only/weak matches go to review, not auto-expense.
- [ ] Add unique partial indexes or claim table for message hash/fingerprint.
- [ ] Make `processBatch()` actually bounded-concurrent.
- [ ] Create `PendingReview` transactionally for low-confidence emails.
- [ ] Sanitize exception logging.
- [ ] Remove or quality-mark provider parser hardcoded currency fallbacks.
- [ ] Document/test email body storage as receipt raw text under email storage mode.

#### Acceptance Criteria

- [ ] Weak email matches cannot auto-create expenses.
- [ ] Concurrent ingestion cannot duplicate receipts/expenses.
- [ ] Email storage mode behavior is explicit and tested.

---

### MIT-047 — Rebuild import support as a safe lifecycle-owned pipeline

**Severity:** S0  
**Pipelines:** P12, P18  
**Status:** TODO  
**Labels:** `import`, `lifecycle`, `release-blocker`

#### Canonical Problem

Import exists as utility-level code, but not as a production-safe lifecycle-owned import pipeline.

#### Tasks

- [ ] Add top-level import coordinator with read/write barrier.
- [ ] Add durable `OperationRun`.
- [ ] Add file hash, batch ID, row ledger, checkpoint.
- [ ] Populate `fileImportRunId`, `csvImportBatchId`, and `csvRowNumber`.
- [ ] Add row fingerprint/external source link.
- [ ] Use `BULK_IMPORT` or `STRICT_EXTERNAL_ID` dedupe mode.
- [ ] Route category creation through `CategoryRepository` or import-safe legal owner.
- [ ] Import coordinator must own category creation transactionally with expense row result.
- [ ] Ensure failed row cannot leave stray category behind.
- [ ] Block import mutations during restore.
- [ ] Re-throw `CancellationException`.
- [ ] Import utility path must be moved/owned by production import coordinator or hidden/de-scoped.

#### Acceptance Criteria

- [ ] Import is idempotent, resumable or clearly restartable, and barrier-safe.
- [ ] Valid CSV rows pass provenance validation.
- [ ] Failed rows do not leave unrelated mutations behind.

---

### MIT-048 — Fix CSV/JSON import semantics and parser safety

**Severity:** S1  
**Pipelines:** P18  
**Status:** TODO  
**Labels:** `import`, `csv`, `json`, `security`

#### Tasks

- [ ] Treat exported original `source` as metadata unless full source entity restore exists.
- [ ] Import or define supported `sourceLinks`.
- [ ] Remove hardcoded EUR fallback.
- [ ] Parse dates in UTC or declared import timezone.
- [ ] Use streaming RFC-4180 CSV parser.
- [ ] Add file size and row count caps.
- [ ] Sanitize import errors.
- [ ] Escape or reject formula-leading values before later export.
- [ ] Preserve numeric timestamp/date-only semantics without drift.

#### Acceptance Criteria

- [ ] CSV/JSON import cannot create invalid provenance, timezone drift, formula injection, or memory blowups.

---

## M5 — Financial Correctness

---

### MIT-050 — Fix dashboard normalized money calculations

**Severity:** S0  
**Pipelines:** P5  
**Status:** TODO  
**Labels:** `money`, `dashboard`, `currency`

#### Tasks

- [ ] Block Party actuals must use normalized `dailySpending`.
- [ ] Add static guard against raw cross-currency sums.
- [ ] Add tests with mixed-currency data.
- [ ] Document allowed money aggregation APIs.

#### Acceptance Criteria

- [ ] Dashboard never sums raw mixed-currency `effectiveAmount`.

---

### MIT-051 — Propagate shared-expense flags into dashboard income logic

**Severity:** S0  
**Pipelines:** P5  
**Status:** TODO  
**Labels:** `dashboard`, `shared-expenses`, `income`

#### Tasks

- [ ] Add `isSharedExpense` to dashboard model.
- [ ] Propagate through mappers.
- [ ] Exclude shared repayments from income where appropriate.
- [ ] Add tests.

#### Acceptance Criteria

- [ ] Shared repayment deposits do not inflate income.

---

### MIT-052 — Fix runway and aggregate count correctness

**Severity:** S1  
**Pipelines:** P5  
**Status:** TODO  
**Labels:** `dashboard`, `analytics`

#### Tasks

- [ ] Fix runway branch order so budget-backed runway does not show `NO_INCOME`.
- [ ] Harden `MoneyAggregateBuilder` count mismatch handling.
- [ ] Failed transaction counts must not be undercounted.

#### Acceptance Criteria

- [ ] Runway status and aggregate counts match expected scenarios.

---

### MIT-053 — Fix recurring forecast/cashflow currency and direction

**Severity:** S0  
**Pipelines:** P6  
**Status:** TODO  
**Labels:** `forecast`, `cashflow`, `currency`

#### Tasks

- [ ] Normalize recurring patterns/occurrences before synthesis.
- [ ] Add income/expense direction to recurring cashflow.
- [ ] Fix `CashFlowCalculator.isIncomePattern()`.
- [ ] If unsupported, surface explicit quality state rather than treating income as expense.
- [ ] Add mixed-currency recurring tests.

#### Acceptance Criteria

- [ ] Recurring income is handled correctly or clearly marked unsupported.
- [ ] Forecast does not mix currencies.

---

### MIT-054 — Fix budget/stress forecast quality states and rate basis

**Severity:** S1  
**Pipelines:** P6  
**Status:** TODO  
**Labels:** `budget`, `forecast`, `quality-state`

#### Tasks

- [ ] Surface stale stress patterns to result/UI without PII.
- [ ] Label stress mode as net-cashflow estimate unless real balance provider exists.
- [ ] Clarify budget snapshot rate basis.
- [ ] Improve rollover iteration/performance.
- [ ] Make no-baseline pace nullable/`N/A` instead of real `0f`.
- [ ] Move stress risk thresholds to config/settings or document as fixed product constants.
- [ ] Replace cashflow day iteration with `LocalDate`/`ZoneId` day loop.
- [ ] Run `rg WEEK_OF_YEAR` and fix week-boundary logic if found.
- [ ] Add P6 table backup/export/restore roundtrip test.

#### Acceptance Criteria

- [ ] UI does not present estimated/unknown forecast values as exact values.
- [ ] Date-boundary behavior is stable across DST/week/year edges.

---

### MIT-055 — Harden export/accounting correctness

**Severity:** S1  
**Pipelines:** P12  
**Status:** TODO  
**Labels:** `export`, `accounting`, `timezone`

#### Tasks

- [ ] Decide/export snapshot semantics.
- [ ] If not snapshot, document non-snapshot guarantee clearly.
- [ ] Add maintenance/snapshot export mode if required.
- [ ] Stream accounting exports instead of materializing all rows.
- [ ] Validate accounting globally, not sampled subset only.
- [ ] Rework broad exception catches.
- [ ] Use explicit UTC or configured timezone for JSON/CSV/date output.
- [ ] Add durable export operation ledger.
- [ ] Add `conversionStatus` / stale-rate / missing-rate export fields.
- [ ] Export receipt links.
- [ ] Export shared/not-mine ownership flags.
- [ ] Add accounting encryption/redaction parity or document unsupported.
- [ ] Parse full JSON output in tests, including nulls, pages, and source links.
- [ ] Resolve mapper/test semantics: `amount` original vs `effectiveAmount` share.

#### Acceptance Criteria

- [ ] Exported accounting data is deterministic under documented conditions.
- [ ] Export schema preserves required financial/accounting meaning.

---

## M6 — UI/UX Action Path Hardening

---

### MIT-060 — Ban DAOs from ViewModels and route bank disconnect legally

**Severity:** S0  
**Pipelines:** P10, P14, P15  
**Status:** TODO  
**Labels:** `ui`, `viewmodel`, `database`

#### Tasks

- [ ] Remove DAO injection from ViewModel.
- [ ] Add `BankConnectionLifecycleCoordinator` or repository method.
- [ ] Enforce write barrier and operation lifecycle.
- [ ] Add static guard banning DAOs in `ui/**`/ViewModels.

#### Acceptance Criteria

- [ ] UI cannot directly mutate bank connection rows.

---

### MIT-061 — Restore UI must force restart or block stale DB usage

**Severity:** S0  
**Pipelines:** P7, P14, P15  
**Status:** TODO  
**Labels:** `ui`, `restore`, `database`

#### Tasks

- [ ] Restart-required dismissal must not resume normal DB-backed screens.
- [ ] Disable DB-backed actions after restore until restart/reopen is complete.
- [ ] Block worker reschedule before restart if using hard-restart model.
- [ ] Add UI tests.

#### Acceptance Criteria

- [ ] User cannot continue using stale singleton DB after restore.

---

### MIT-062 — Verify privacy-blocked UX and sanitized errors

**Severity:** S1  
**Pipelines:** P8, P14, P16  
**Status:** TODO  
**Labels:** `ui`, `privacy`, `errors`

#### Tasks

- [ ] Add typed `PrivacyBlocked` UI states.
- [ ] Test assistant/export/backup/location/bank/privacy-gated screens.
- [ ] Sanitize snackbar/error messages.
- [ ] Ensure cloud provider raw `e.message` is not surfaced.

#### Acceptance Criteria

- [ ] Privacy-denied states are clear, non-leaky, and recoverable.

---

### MIT-063 — Harden backup/export/import duplicate actions and cancellation UX

**Severity:** S1  
**Pipelines:** P12, P14, P18  
**Status:** TODO  
**Labels:** `ui`, `import`, `export`, `backup`

#### Tasks

- [ ] Prevent duplicate import/export/backup button actions.
- [ ] Show cancellable progress where supported.
- [ ] Ensure cancellation maps to correct operation state.
- [ ] Resume/retry messaging must not expose raw file content.

#### Acceptance Criteria

- [ ] Duplicate taps/cancellation cannot corrupt operation state.

---

## M7 — Added Cross-Reference Issues

---

### MIT-064 — Notification deferred payload fidelity

**Severity:** S1  
**Pipelines:** P1  
**Status:** TODO  
**Labels:** `notifications`, `privacy`, `dedupe`

#### Problem

Deferred notification retry can drop payload fields and use an over-broad fingerprint.

#### Tasks

- [ ] Do not drop `textLines`, messages, or combined body in deferred notification path after legal gate.
- [ ] Deferred fingerprint must include content/post-time hash, not only notification key.
- [ ] Add same-key/different-content deferred tests.

#### Acceptance Criteria

- [ ] Deferred retry preserves all legally captured payload content.
- [ ] Same notification key with changed content is not deduped incorrectly.

---

### MIT-065 — Durable terminal diagnostics cannot be cancellable

**Severity:** S1  
**Pipelines:** P1, P9  
**Status:** TODO  
**Labels:** `diagnostics`, `workers`, `notifications`

#### Problem

Pre-launch or terminal diagnostics may be launched in cancellable service/worker scope and lost during shutdown/restore.

#### Tasks

- [ ] Pre-launch/terminal diagnostics must use non-cancellable durable path where appropriate.
- [ ] Worker terminal/drop/retry paths must emit diagnostic or run-ledger entry.
- [ ] Add service-destroy/restore-shutdown diagnostic durability tests.

#### Acceptance Criteria

- [ ] Restore/shutdown cannot erase critical terminal diagnostics.

---

### MIT-066 — Correlation and idempotent side-effect keys

**Severity:** S1  
**Pipelines:** P2  
**Status:** TODO  
**Labels:** `transactions`, `idempotency`, `events`

#### Tasks

- [ ] Propagate `correlationId` through transfer/type/ownership updates.
- [ ] Replace wall-clock bulk side-effect idempotency keys with mutation/run/correlation-derived stable keys.
- [ ] Route category assignment through coordinator or emit canonical `UPDATED` event with before/after snapshots.
- [ ] Add exact idempotency tests for retried bulk operations.

#### Acceptance Criteria

- [ ] Retried bulk operations do not create duplicate side effects.
- [ ] Transaction lifecycle events are traceable by stable correlation.

---

### MIT-067 — Recurring projection, receiver, and notification-ID hardening

**Severity:** S1  
**Pipelines:** P4  
**Status:** TODO  
**Labels:** `recurring`, `notifications`, `coroutines`

#### Tasks

- [ ] Make recurring projection generation + planned row insert atomic.
- [ ] Fix snooze/dismiss receivers to use structured app scope.
- [ ] Re-throw `CancellationException` in receivers.
- [ ] Replace modulo notification/request IDs with collision-free persisted IDs or allocator.
- [ ] Restrict recurring DAO mutators.
- [ ] Add private/lock-screen reminder content policy.

#### Acceptance Criteria

- [ ] Reminder receiver actions cannot silently fail, leak private content, or collide IDs.

---

### MIT-068 — Forecast calendar/risk-threshold hardening

**Severity:** S2  
**Pipelines:** P6  
**Status:** TODO  
**Labels:** `forecast`, `calendar`, `quality-state`

#### Tasks

- [ ] Move stress thresholds to config/settings or document product constants.
- [ ] Replace `Calendar` day iteration with `LocalDate`.
- [ ] Verify/fix `WEEK_OF_YEAR`.
- [ ] Add DST boundary tests.
- [ ] Add week/year-boundary tests.

#### Acceptance Criteria

- [ ] Forecast/cashflow day and week calculations are stable across DST and year boundaries.

---

### MIT-069 — Privacy edge-case hardening

**Severity:** S1  
**Pipelines:** P8, P11  
**Status:** TODO  
**Labels:** `privacy`, `redaction`, `audit`

#### Tasks

- [ ] AI settings corruption must fail closed with typed load state.
- [ ] Preserve safe typed audit metadata.
- [ ] Redact/value-scan audit allowlisted values.
- [ ] Preserve null-vs-empty raw OCR semantics.
- [ ] Redact parsed receipt/email item names in redacted mode.
- [ ] Expand sanitizer patterns for Greek AFM, URLs, API keys, addresses, emails, tax IDs.

#### Acceptance Criteria

- [ ] Privacy behavior remains safe under corrupted settings, partial OCR, and redacted receipt/email modes.

---

### MIT-070 — Worker scheduling diagnostics

**Severity:** S2  
**Pipelines:** P9  
**Status:** TODO  
**Labels:** `workers`, `diagnostics`, `ci`

#### Tasks

- [ ] `WorkerRegistry.scheduleAll()` must emit sanitized diagnostic on per-entry schedule failure.
- [ ] Worker comments/spec docs must match implementation.
- [ ] Add test for schedule failure visibility.
- [ ] Add test for one-shot policy comment/implementation parity if guardable.

#### Acceptance Criteria

- [ ] Worker schedule failures are visible and actionable.

---

### MIT-071 — Bank review ownership and validation

**Severity:** S1  
**Pipelines:** P10  
**Status:** TODO  
**Labels:** `banking`, `pending-review`, `validation`

#### Tasks

- [ ] Low-confidence bank review writes must go through legal review owner/coordinator.
- [ ] Validate finite amount/currency before review/import rows.
- [ ] Add provider/account-scoped bank connection uniqueness.
- [ ] Add parser locale/date/currency/sign tests.
- [ ] Make connect UI real OAuth or explicit demo-disabled.

#### Acceptance Criteria

- [ ] Bank review/import rows cannot be created with invalid money or illegal ownership path.
- [ ] Bank connection identity is correctly scoped.

---

### MIT-072 — Export schema completeness

**Severity:** S1  
**Pipelines:** P12  
**Status:** TODO  
**Labels:** `export`, `accounting`, `schema`

#### Tasks

- [ ] Add conversion status/stale-rate/missing-rate export fields.
- [ ] Export receipt links.
- [ ] Export shared/not-mine ownership flags.
- [ ] Add full JSON parse tests for source links, nulls, and multipage output.
- [ ] Resolve `amount` vs `effectiveAmount` schema semantics.
- [ ] Verify `ExpenseExportMapperTest` matches intended mapper semantics.

#### Acceptance Criteria

- [ ] Exported data can roundtrip or be consumed without losing required accounting meaning.

---

### MIT-073 — Import architecture contradiction resolution

**Severity:** S0  
**Pipelines:** P12, P18  
**Status:** TODO  
**Labels:** `import`, `docs`, `release-blocker`

#### Problem

Docs can be read as contradictory: P12 says import is absent/incomplete; P18 says import exists but is unsafe.

#### Tasks

- [ ] Document that util import exists but is not production-safe.
- [ ] Decide: promote util import into lifecycle-owned import pipeline, or remove/hide it.
- [ ] Update P12/P18 docs so future reviewers do not treat import as both absent and present without context.
- [ ] Add tracker link to MIT-047 and MIT-048.

#### Acceptance Criteria

- [ ] Import status is unambiguous in docs, code ownership, and release notes.

---

# 7. Pipeline Traceability Matrix

---

## P1 — Notification Capture

| Finding | Master Issue |
|---|---|
| Text/extras read before gate allowed | MIT-020 |
| Worker decrypt/replay without privacy recheck | MIT-021 |
| Intake/recovery/repair writes bypass barrier | MIT-030 |
| Worker terminal state lacks durable diagnostics | MIT-035, MIT-065 |
| Deferred retry drops fields / broad fingerprint | MIT-064 |
| Early terminal diagnostics cancellable | MIT-065 |
| SHA-256 consolidation incomplete | MIT-020, MIT-003 |
| Stale tests | MIT-005 |

---

## P2 — Transaction Lifecycle

| Finding | Master Issue |
|---|---|
| Delete can succeed on missing row | MIT-032 |
| Update validation failures not durable | MIT-032 |
| Duplicate create double-logs conflict | MIT-032 |
| Duplicate source-link policy not linked | MIT-032 |
| DAO mutation surfaces risky | MIT-036 |
| `homeCurrency().first()` can hang | MIT-032 |
| CorrelationId gaps | MIT-066 |
| Wall-clock bulk idempotency keys | MIT-066 |
| Category assignment direct DAO/noncanonical events | MIT-066 |
| Restore-blocked updates lack evidence | MIT-032 |

---

## P3 — Receipt/OCR/Email Receipt Links

| Finding | Master Issue |
|---|---|
| Manual match bypasses `ReceiptLinkService` | MIT-040 |
| Receipt/status/event not atomic | MIT-041, MIT-031 |
| PendingReview can fail after receipt save | MIT-041 |
| `runCatching` can swallow cancellation | MIT-034 |
| Sensitive logs | MIT-026 |
| Nested transaction behavior untested | MIT-040 |

---

## P4 — Recurring/Bill Reminders

| Finding | Master Issue |
|---|---|
| Notification permission guard missing | MIT-042 |
| Permission denial can lose reminders | MIT-042 |
| Due query hidden stale-claim writes | MIT-043 |
| Same actual expense can fulfill multiple rules | MIT-043, MIT-033 |
| State/event not atomic | MIT-031, MIT-043 |
| Reconcile hidden writes | MIT-043 |
| Projection full atomicity TODO | MIT-067, MIT-043 |
| Snooze/dismiss receiver scope/cancellation | MIT-067, MIT-034 |
| Notification ID modulo collision | MIT-067 |
| Recurring DAO mutator surface | MIT-036, MIT-067 |
| Lock-screen privacy setting missing | MIT-067, MIT-043 |
| Direct lifecycle event insert bypass | MIT-031, MIT-043 |

---

## P5 — Currency/Dashboard/Analytics

| Finding | Master Issue |
|---|---|
| Raw `effectiveAmount` sums | MIT-050 |
| Shared repayment deposits enter income | MIT-051 |
| Runway `NO_INCOME` branch bug | MIT-052 |
| Aggregate count mismatch | MIT-052 |
| Need raw money sum guard | MIT-003, MIT-050 |

---

## P6 — Budget/Forecast/Cashflow

| Finding | Master Issue |
|---|---|
| Mixed-currency recurring forecast paths | MIT-053 |
| Recurring income unsupported/misclassified | MIT-053 |
| Stale stress patterns only logged | MIT-054 |
| Stress forecast mislabeled as balance | MIT-054 |
| Budget rate basis partial | MIT-054 |
| Rollover performance partial | MIT-054 |
| No-baseline pace uses `0f` | MIT-054 |
| Risk thresholds constants/TODO | MIT-068, MIT-054 |
| Calendar/DST date bucket risk | MIT-068 |
| `WEEK_OF_YEAR` unverified | MIT-068 |
| P6 roundtrip not inspected | MIT-054 |

---

## P7 — Backup/Restore

| Finding | Master Issue |
|---|---|
| Stale Hilt DB consumers | MIT-013 |
| Asset restore not atomic/resumable | MIT-015 |
| Barriers caller-enforced | MIT-030 |
| Semantic aggregate missing from manifest | MIT-012 |
| Maintenance owner token missing | MIT-014 |
| Exchange rates optional in verifier | MIT-012 |
| Fallback file-copy snapshot safety | MIT-012, MIT-030 |
| Stale KDoc/comment | MIT-012 |

---

## P8 — Privacy/AI/Redaction

| Finding | Master Issue |
|---|---|
| Raw bank statement text stored | MIT-045 |
| Cloud redaction not semantic | MIT-023 |
| Retention CE swallowed | MIT-034 |
| Raw audit map | MIT-024 |
| Cloud payload not fail-closed | MIT-022 |
| Deterministic sensitive hashing | MIT-025 |
| AI settings corruption fallback | MIT-069 |
| Audit metadata dropped | MIT-024, MIT-069 |
| OCR null-vs-empty semantics | MIT-045, MIT-069 |
| `parsedItemsJson` item names in redacted mode | MIT-045, MIT-069 |
| Sanitizer coverage gaps | MIT-023, MIT-026, MIT-069 |
| Cloud capability coverage too narrow | MIT-022 |

---

## P9 — Workers/Background Jobs

| Finding | Master Issue |
|---|---|
| Lease registry keyed by worker name | MIT-016 |
| `NotificationIntakeWorker` no full guard | MIT-016, MIT-021 |
| Reads before barrier | MIT-016 |
| One-shot version bump uses `KEEP` | MIT-017 |
| Terminal logger race | MIT-017 |
| Retention partial failures soft-success | MIT-017, MIT-035 |
| Daily briefing reschedule failure | MIT-017 |
| Bill reminder permission missing | MIT-042 |
| `scheduleAll()` swallows failures | MIT-070, MIT-017 |
| Worker comments drift | MIT-070 |

---

## P10 — Bank Integration/Imports

| Finding | Master Issue |
|---|---|
| API demo/stub/release-disabled | MIT-044 |
| Bank disconnect direct DAO write | MIT-060 |
| Raw merchant/description persisted | MIT-045 |
| Transaction idempotency not scoped | MIT-033, MIT-044 |
| No durable sync cursor/checkpoint | MIT-035, MIT-044 |
| `BankConnection.toString()` leaks token blobs | MIT-026 |
| Backup/export/token redaction unverified | MIT-028, MIT-044 |
| Low-confidence review ownership | MIT-041, MIT-071 |
| Statement partial failure semantics | MIT-044 |
| Amount/currency validation missing | MIT-044, MIT-071 |
| Bank connection unique scope weak | MIT-033, MIT-071 |
| Parser locale/date/sign tests missing | MIT-071 |
| Connect UI demo placeholders | MIT-044, MIT-071 |

---

## P11 — Email Receipt Ingestion

| Finding | Master Issue |
|---|---|
| Broad provider fallback | MIT-046 |
| Batch sequential despite semaphore | MIT-046 |
| Dedupe indexes not unique | MIT-033, MIT-046 |
| Low-confidence PendingReview unclear | MIT-046 |
| Persistence payload bypass | MIT-045 |
| Exception logging may leak | MIT-026, MIT-046 |
| Parser hardcoded currency fallback | MIT-046 |
| Body as raw OCR text needs contract | MIT-046 |
| Parsed item privacy tests missing | MIT-045, MIT-069 |
| `email_receipt_sources` backup/export unknown | MIT-012 |
| Provider/parser logs | MIT-026 |

---

## P12 — Import/Export/Accounting

| Finding | Master Issue |
|---|---|
| Import architecture missing/incomplete | MIT-047, MIT-073 |
| Export not point-in-time snapshot | MIT-055 |
| Accounting materializes rows | MIT-055 |
| Accounting validation samples subset | MIT-055 |
| System timezone output | MIT-055 |
| Operation diagnostics unclear | MIT-035, MIT-055 |
| Import lifecycle/idempotency not validated | MIT-047 |
| Export lacks conversion status | MIT-055, MIT-072 |
| Receipt links TODO/not exported | MIT-055, MIT-072 |
| Shared/not-mine flags missing | MIT-055, MIT-072 |
| Accounting encryption/redaction parity | MIT-055 |
| Accounting read barrier gaps | MIT-030, MIT-055 |
| Export ViewModel cancellation | MIT-034, MIT-063 |
| JSON/sourceLinks parser tests missing | MIT-055, MIT-072 |
| Mapper/test semantic mismatch | MIT-055, MIT-072 |

---

## P13 — DB Schema/Migrations/DAO Constraints

| Finding | Master Issue |
|---|---|
| Historical migrations not registered | MIT-010 |
| Fresh schema may not match migrated schema | MIT-011 |
| Backup verifier stale | MIT-012 |
| DAO ownership mismatch | MIT-036 |
| Direct writes in import/category/bank/UI | MIT-030, MIT-036 |
| Dedupe/idempotency constraints incomplete | MIT-033 |

---

## P14 — UI/ViewModel Action Paths

| Finding | Master Issue |
|---|---|
| Bank VM direct DAO write | MIT-060 |
| Restore dismissal stale DB usage | MIT-061 |
| UI/click-handler inventory incomplete | MIT-003, MIT-060 |
| Privacy-blocked UX unverified | MIT-062 |
| Snackbar/errors may expose PII | MIT-026, MIT-062 |
| Duplicate action/cancellation unproven | MIT-063 |
| Debug/raw screens release visibility | MIT-028 |

---

## P15 — Hilt/DI/Singleton Lifetime

| Finding | Master Issue |
|---|---|
| Singleton DB/DAO unsafe after restore | MIT-013 |
| Backup repo hot-swap split brain | MIT-013 |
| Direct DAO injection reaches UI/importers | MIT-036, MIT-060 |
| Workers not forced through guard | MIT-016 |
| Worker leases keyed by name | MIT-016 |
| App-scope coroutines survive maintenance | MIT-018 |
| Retention cancellation issue | MIT-034 |
| Debug/release fake binding safety | MIT-028 |
| Security/network graph unproven | MIT-028 |

---

## P16 — Security/Network/Secrets

| Finding | Master Issue |
|---|---|
| Cloud policy not fail-closed | MIT-022 |
| Redaction not semantic | MIT-023 |
| Direct receipt `imagePath` upload | MIT-027 |
| Deterministic sensitive hashing | MIT-025 |
| Raw audit map | MIT-024 |
| Release minification disabled | MIT-028 |
| Direct Android Log | MIT-026 |
| BankConnection token `toString` | MIT-026 |
| Asset filenames/paths logged | MIT-026 |
| Sanitizer coverage gaps | MIT-026, MIT-069 |
| Raw cloud provider errors | MIT-062 |
| KDF params not encoded | MIT-028 |
| Restored bank tokens need reauth | MIT-028 |
| OkHttp/RequestBody inventory missing | MIT-028 |

---

## P17 — CI/Static Guardrails

| Finding | Master Issue |
|---|---|
| `:app:check` not run | MIT-001 |
| Source provenance guard not run | MIT-002 |
| Script tests not run | MIT-002 |
| Instrumented tests non-blocking | MIT-001, MIT-005 |
| No release/security CI | MIT-028 |
| Schema snapshots insufficient | MIT-004 |
| DB allowlist too broad | MIT-030, MIT-036 |
| Event writer guard not atomic | MIT-031 |
| Missing cancellation guard | MIT-034 |
| Missing worker guard | MIT-016 |
| Missing UI DAO guard | MIT-060 |
| Missing receipt-link guard | MIT-040 |
| Missing recurring atomicity guard | MIT-043, MIT-067 |
| Missing cloud guard | MIT-022, MIT-023 |
| Missing import lifecycle guard | MIT-047 |
| Missing DI/release binding guard | MIT-013, MIT-028 |
| Too many ignored tests | MIT-005 |
| Workflow validation missing | MIT-002 |

---

## P18 — Import Support

| Finding | Master Issue |
|---|---|
| CSV provenance fields missing | MIT-047 |
| Importers create categories directly | MIT-047 |
| Category creation bypasses barrier/repository/cache | MIT-047 |
| Failed row can leave category behind | MIT-047 |
| Import mutates during restore | MIT-047, MIT-030 |
| JSON reuses exported source incorrectly | MIT-048 |
| Exported sourceLinks ignored | MIT-048 |
| JSON idempotency ineffective | MIT-047 |
| CSV lacks stable row key | MIT-047 |
| Broad catch swallows cancellation | MIT-034 |
| No import barrier | MIT-047 |
| No import run/ledger/checkpoint | MIT-035, MIT-047 |
| Roundtrip loses many fields | MIT-048, MIT-072 |
| Missing currency fallback to EUR | MIT-048 |
| CSV date uses system timezone | MIT-048 |
| Naive/non-streaming CSV | MIT-048 |
| Raw file content in errors | MIT-026, MIT-048 |
| Formula-leading values stored | MIT-048 |
| JSON date-only drift | MIT-048 |
| Import present/absent doc contradiction | MIT-073 |

---

# 8. Final Release Gate Checklist

Do not mark the app release-ready until all are checked:

- [ ] All S0 issues are closed.
- [ ] All RED pipelines are re-reviewed against current code.
- [ ] Full Gradle CI passes.
- [ ] Static guard CI passes.
- [ ] Migration matrix passes.
- [ ] Fresh-vs-migrated schema parity passes.
- [ ] Restore/reset/import stale-DB tests pass.
- [ ] Worker drain/lease tests pass.
- [ ] Worker scheduling diagnostics tests pass.
- [ ] Privacy gate and cloud fail-closed tests pass.
- [ ] Semantic redaction golden tests pass.
- [ ] Import lifecycle/provenance/idempotency tests pass.
- [ ] Export/accounting schema tests pass.
- [ ] Dashboard/forecast mixed-currency tests pass.
- [ ] DST/week/year-boundary forecast tests pass.
- [ ] Release APK/AAB security scan passes.
- [ ] Debug/demo/stub routes are blocked or absent in release.
- [ ] Docs and this tracker are synced to final release commit SHA.

---

# 9. Suggested GitHub Labels

- `severity:S0`
- `severity:S1`
- `severity:S2`
- `pipeline:P1` through `pipeline:P18`
- `area:database`
- `area:restore`
- `area:privacy`
- `area:security`
- `area:workers`
- `area:ui`
- `area:import`
- `area:export`
- `area:banking`
- `area:email`
- `area:receipts`
- `area:money`
- `area:forecast`
- `area:ci`
- `type:architecture-guard`
- `type:regression-test`
- `release-blocker`

---

# 10. Suggested GitHub Milestones

1. `M0 — CI and Guardrails`
2. `M1 — DB Restore DI Workers`
3. `M2 — Privacy Security`
4. `M3 — Lifecycle Atomicity Idempotency`
5. `M4 — Ingestion Pipelines`
6. `M5 — Financial Correctness`
7. `M6 — UI Release Hardening`
8. `Release Candidate`

---

# 11. First PR Recommendation

Start with:

## PR 1 — CI Guardrails Bootstrap

Include:

- `:app:check` in CI.
- Existing Python/static guards in CI.
- Script tests in CI.
- Workflow validation.
- Migration execution scaffold.
- Release-security scan scaffold.
- Optional ignored-test budget reduction if safe.

Why first:

> It prevents every later fix from regressing silently.

Then proceed:

1. DB migration/baseline fix.
2. Restore DB lifetime fix.
3. Worker lease/full-guard fix.
4. Privacy fail-closed/cloud/redaction fix.
5. Import coordinator/provenance fix.
6. Dashboard/forecast/export money correctness fix.
7. UI action-path hardening.