# Cell P-10 Audit — Bank sync and statement import

## Provenance
- Date: 2026-09-21
- Pinned commit: 37601232b9778170c57a656a245b199ab6d7d965 (HEAD short: 37601232)
- Auditor: direct astra session
- Cell: P-10
- Mode: AUDIT, static only
- Known issue IDs excluded from new findings: FRESH-P10-001..012

## Coverage / progress
- Spec: §2 known-debt and intended-behavior rules; §4 defect classes, finding schema, severity.
- Coverage matrix: P-10 row read.
- Legal paths read: Bank Statement Mutations; Receipt Mutations; Expense Mutations; Privacy / Cloud AI; Workers / Background Jobs.
- Segment entry read: Segment 14 Bank Integration.
- Primary files and package mates to inspect:
  - BankConnectionLifecycleCoordinator.kt
  - BankApiIntegration.kt
  - BankStatementLifecycleProcessor.kt
  - ReceiptLifecycleCoordinator.kt (bank statement entry)
  - PendingReviewDao.kt
  - TransactionLifecycleCoordinator.kt (bank sync expense path)
  - relevant entities/DAOs, token/security, worker and call sites

## Findings

(Findings will be appended as verified at the pinned commit.)

## Covered files

- Initial docs and pin verification complete; source coverage pending.

### Source coverage checkpoint (2026-09-22)
- BankConnectionLifecycleCoordinator.kt: full 1–243; availability, sync outcome persistence, disconnect transaction.
- BankApiIntegration.kt: full 1–882 in chunks; stub entry, token refresh, both confidence routes, mapping, contract skips, review identity, mock generation.
- PendingReviewDao.kt: 1–130, 235–696 read; insert IGNORE, status claims, dedup queries, scoped cleanup. 131–234 still to read.
- BankConnectionDao.kt and BankConnection.kt: full; conditional token update, unique bankId, status update SQL.
- BankTokenCipher.kt: full; AES/GCM Keystore, encrypted format, failure taxonomy; no token plaintext logging found in this file.
- SensitiveHashingService.kt and DefaultSensitiveHashingService.kt: full; actual implementation returns non-null for non-null input (no speculative null-identity finding).
- PendingReview.kt: full; unique nullable bank identity and connection scope, transfer fields.
- BankSyncOutcome.kt, BankFeatureAvailability.kt, BankApiConfig.kt, BankSyncStartupRecovery.kt: full.
- BankConnectionsViewModel.kt and BankConnectionsScreen.kt: full; UI caller trace, typed messages, disconnect and in-flight state.
- OperationRunRecorder.kt: full; start/event/finalizer inspected. Injected composite routing still to trace.
- BankStatementLifecycleProcessor.kt: 100–1061 read in bounded chunks; statement facade and review/run/event flow traced. Deep receipt/parser audit remains P-03.
- ReceiptLifecycleCoordinator.kt: bank facade 756–773 extracted; TransactionLifecycleCoordinator.kt: create path 328–710, V2 853–876, identity helpers 101–109, validator delegation 2416–2418 extracted.
- DatabaseWriteBarrier.kt: full; runWrite is a point-in-time check, not a held lock.
- BankStatementImportRunDao.kt and BankStatementImportItemDao.kt: full.
- Tests extracted: BankConnectionLifecycleCoordinatorOutcomeTest (outcome table/barrier/disconnect), BankApiIntegrationTest (mapper/routing/token tests), BankTransactionContractTest (identity references).
- Known/deferred screen: RP-17-bank-sync.md and D1/D12; cursor and outer-batch atomicity intentionally deferred. Guard-policy regeneration and schema snapshot follow-ups already recorded in RP-17, not new findings.

### Candidate checkpoint (not yet findings)
- New terminal-status writer appears to write after RESTORE_BLOCKED with no barrier check; inspect pin history and tests before assigning ID.
- Disconnect/sync review recreation overlaps FRESH-P10-009; do not restate unless a distinct regression is proved.
- Demo timestamps changing identity are pre-existing deferred provider-contract territory; not a new finding.

## New discovery finding (independent verification pending)

### CA-P-10-001 | Terminal sync-status persistence bypasses an already-denied restore write barrier
- ID: CA-P-10-001
- Title: Terminal sync-status persistence bypasses an already-denied restore write barrier.
- Defect class: 2 (Barrier violation); related 15 (Fix-regression) and 14 (Test correctness).
- Severity: P1. This is a reachable database-write boundary violation, limited to the D1-permitted debug bank surface and connection status metadata. No expense corruption or release-bank reachability is asserted; P0 would overstate the demonstrated impact.
- Evidence at pinned commit:
  - `app/src/main/java/com/yourname/expensetracker/domain/bank/BankConnectionLifecycleCoordinator.kt`, `syncConnection`, 140–165: reads the connection, awaits integration, and unconditionally calls `persistOutcome`, including on the exception fallback.
  - Same file, `persistOutcome`, 169–187: calls `updateSyncStatus` / `updateSyncStatusOnly` directly with no write-barrier check or scope.
  - `app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt`, `syncTransactions`, 220–237: a denied barrier produces `Blocked(RESTORE_BLOCKED)` and returns normally to the coordinator.
  - `app/src/main/java/com/yourname/expensetracker/domain/bank/BankSyncOutcome.kt`, `toTerminalSyncStatus`, 125–133: Blocked maps to FAILED, so it is not one of the no-write outcomes.
  - `app/src/main/java/com/yourname/expensetracker/data/database/dao/BankConnectionDao.kt`, 42–50: ordinary unconditional Room UPDATEs by id; no maintenance predicate.
  - `app/src/main/java/com/yourname/expensetracker/domain/diagnostics/CompositeOperationRunRecorder.kt`, `start` / `runOperation`, 30–61: when maintenance is active it supplies a safe handle and still invokes the business block. Thus the integration's blocked branch is reachable; operation recording does not protect the later status write. `di/DiagnosticsModule.kt:52` binds this implementation.
  - `app/src/main/java/com/yourname/expensetracker/di/DaoModule.kt:188–191` supplies the direct Room BankConnectionDao.
- Impact path: an existing debug connection starts sync; restore enters maintenance while sync is suspended (or before the integration check); integration correctly declines business work with RESTORE_BLOCKED; coordinator then issues `UPDATE bank_connections SET lastSyncStatus='FAILED'`. A still-open database can be mutated during maintenance, and the UI subsequently presents the changed failure status. The violation does not depend on an interleaving between a valid check and a write: the integration has already reported denial before the unguarded write is attempted.
- Caller trace: `ui/MainActivity.kt:734–740` debug-gated BankConnectionsScreen → `ui/screens/bank/BankConnectionsScreen.kt:97–102,261–268` Sync Now → `BankConnectionsViewModel.kt:64–75` → coordinator → integration → coordinator.persistOutcome → BankConnectionDao. Existing stored connection required; the fresh add-connection UI is currently a placeholder.
- Existing tests/guards: `app/src/test/java/com/yourname/expensetracker/domain/bank/BankConnectionLifecycleCoordinatorOutcomeTest.kt:180–197` explicitly expects updateSyncStatusOnly for Blocked(RESTORE_BLOCKED), with mocked integration and a relaxed barrier; it pins status mapping but never denies the actual barrier during persistence. Its disconnect barrier-denial test (287 onward) covers a different function. `config/guards/db_ownership_policy.yml:3792–3809` contains disconnect ownership only; the new status-writer signatures are absent. The missing policy regeneration is already a recorded RP-17 follow-up, not a second finding. No test/guard executed.
- Cross-cell impact: P-07 restore maintenance; I-02 persistence; I-04 guard ownership; class-14 follow-up should be routed to RP-21 only after independent finding verification.
- Old-ID cross-refs: FRESH-P10-001 / FRESH-P10-003 are related remediation history (lost outcomes / unwritten sync status), not this failure. This is the new barrier regression introduced by `7afaf1acc78815a7eefc113b4a3c50efaa03b693` (RP-17 17-B): the reviewed git diff adds both persistOutcome calls and the unguarded DAO writes. No matching pre-existing barrier-status item found in the registry or RP-17 debt/deferred notes. D12 approves terminal-only state, not writes during restore denial.

## Final coverage and limits

### Final source-state verification
- Re-ran from the repository root (so app/config/scripts pathspecs are unambiguous): `git rev-parse --short HEAD` = `37601232`; full HEAD = `37601232b9778170c57a656a245b199ab6d7d965`.
- `git diff --stat 37601232..HEAD -- app config scripts`: EMPTY.
- Additional working-tree check, `git diff --stat 37601232 -- app config scripts`: EMPTY. Source excerpts therefore match the pin, including their line numbers.
- Docs and agent-config drift was observed and accepted as instructed. Only this report and the journal were written by this session.

### Coverage completed after the checkpoint
- PendingReviewDao.kt remaining 131–234 read: full DAO coverage now complete (1–696), including category/merchant bulk operations. These are not called by bank intake.
- BankStatementLifecycleProcessor.kt remaining 1–99 read: full file coverage now complete (1–1061).
- ReviewQueueRepository.kt approveReview 111–424: traced pending status claim, request construction, DB-only expense create, source promotion, APPROVED/DUPLICATE outcomes, rollback, correction persistence, and post-commit effects. This is a targeted bank-review consumer trace, not a full repository audit.
- ReviewViewModel.kt: caller search for approveReview; statement entry 1051–1084 read. Bank statement import reaches ReceiptLifecycleCoordinator.processBankStatement; no BankApiIntegration crossover.
- TransactionLifecycleCoordinator.kt additionally 774–813: post-commit action planning. Expense insertion + CREATED/source-link writes are inside the transaction (623–710); standalone dispatch follows mutation return (871–876). Review approval uses the DB-only variant inside its outer transaction and dispatches at ReviewQueueRepository 397–420.
- CompositeOperationRunRecorder.kt 22–86 and DiagnosticsModule binding: maintenance-safe operation handle permits a blocked business outcome; initial operation-run-before-barrier ordering is therefore not independently reported as a bug.
- DatabaseModule.kt 31–37, DaoModule.kt 188–191, AppDatabase.kt fileBuilder/configureBuilder 8724–8748: direct Room database/DAO, no implicit maintenance guard on status UPDATE.
- Extracted only relevant inventory entries and engine table rows: ReceiptParser (17), PrivacyGate/CloudPayloadPolicy (20), WorkerExecutionGuard (36), HybridRouter (40). These are ownership/impact signposts, not evidence that their internals were audited.
- Reviewed RP-17 commit diff `7afaf1a` for class 15; it introduces the unguarded status writer. Reviewed scoped current campaign reports for cross-cell overlap; P-03 owns statement OCR/page-loss findings and deep receipt behavior.

### All 15 defect classes considered

| Class | Static checks and disposition |
|---|---|
| 1 Legal path | BankApiIntegration 405–409 reaches TransactionLifecycleCoordinator.createExpenseStandaloneV2. Low confidence uses PendingReviewDao.insert (367–381); human approval reaches createExpenseDbOnlyV2 inside ReviewQueueRepository's transaction (219–259). Statement facade 767–773 delegates to its lifecycle processor. No new coordinator-bypass finding established on those paths. |
| 2 Barrier | Checked entry, per-review transaction, token refresh, disconnect, final status, statement writes and cancellation cleanup. CA-P-10-001 is the new status-writer violation. Statement/shared barrier weaknesses are not declared safe or independently completed here. |
| 3 Atomicity / TOCTOU | High-confidence expense/event/source-link commit traced; low-confidence IGNORE + identity unique index inspected; disconnect deletes scoped reviews and clears tokens in one transaction. Statement receipt/run/event and review/item/event transactions read. Its dedupe reads precede later write transactions; this is already identified in the P-03 coverage notes and is not a fresh P-10 claim. |
| 4 Idempotency | STRICT_EXTERNAL_ID mapping (660–687), canonical key (coordinator 101–109), unique review identity and IGNORE conflict handling checked. Fuzzy cross-source suppression is FRESH-P10-011. Cursor/outer-batch guarantees remain D12-deferred; mock time-derived IDs are not promoted as a new provider-contract issue. |
| 5 Cancellation | Integration per-item catch rethrows (466–471), coordinator rethrows, token cipher is synchronous, review approval rethrows (364–365), statement bounded NonCancellable finalization (947–976) rethrows original cancellation. UI statement catch (1078–1080) does not explicitly rethrow: shared UI follow-up, not independently screened/promoted as new debt within this budget. |
| 6 Side-effect timing | Expense mutation returns a planned batch; standalone runner executes after mutation commit. Review queue uses DB-only create and runs actions after outer transaction. Bank review operation events are post-insert best-effort diagnostics, not the CREATED expense event. Durable replay internals not audited. |
| 7 Money / currency | Bank amount abs mapping, movement contract, zero/NaN rejection, transfer metadata contract and currency passthrough read. Stub produces finite negative EUR purchases only (851–861); latent arbitrary-provider inputs are not treated as live release bugs. Statement candidate merge uses explicit source currency or typed unknown (281–334); parser/converter internals belong to P-03/E-01. |
| 8 Time | TimeProvider used in bank expiry, last-sync, review timestamps and generation. Dedup date window is half-open in DAO. Timestamp-dependent mock identity acknowledged; cursor work deferred. Stale-recovery cutoff and ownership traced at caller, not validated with runtime clocks. |
| 9 Privacy | Keystore AES/GCM token storage, exception-class decryption result, raw-description sanitizer call sites, null review title, HMAC scope and summary-only connection flow inspected. Actual hashing implementation is deterministic public-purpose-derived HMAC; no assertion of secret-key protection is made. Sanitizer, Timber sinks and cloud policy internals are not exhaustively covered. No new privacy finding promoted without those checks. |
| 10 Worker hygiene | Production caller search found manual ViewModel sync, not a bank CoroutineWorker. Do not invent a missing guard on a nonexistent worker. Startup recovery calls found at AppStartupCoordinator 70–80; coordinator/worker semantics are separate. Shared worker internals remain P-09/I-01. |
| 11 Data integrity | BankId uniqueness, review identity/scope, null FK-source review fields, bank import run/item DAO contracts and review approval claim/rollback inspected. Source-link consumer traced to promotion but promoter internals/migration snapshots not audited. No unsupported migration safety claim. |
| 12 Error handling | Typed sync outcome aggregation, persisted status mapping, null/not-found, token invalidation, validation failure, insert conflict and statement early returns read. FRESH-P10-001/003 and FRESH-P3-005/006 are excluded known cases. CA-P-10-001 shows the new outcome/status boundary defect. |
| 13 Wiring / dead code | Exact production searches reproduced screen → VM → sync coordinator → integration. initiate/complete connection have no UI call; MainActivity add callback navigates back. D1/provider rollout remains intended. refresh()/shouldSync dead-path observations are existing debt, not new runtime findings. Statement import caller reproduced in ReviewViewModel. |
| 14 Test correctness | Outcome tests were inspected statically; the RESTORE_BLOCKED status-write assertion is part of CA-P-10-001. The test named low-confidence routing only invokes the mapper (BankApiIntegrationTest 289–311); this coverage limitation is recorded, not counted as an independently verified test-debt finding. No tests executed or claimed passing. |
| 15 Fix-regression | RP-17 source diff establishes new terminal status writes, conditional token SQL, identity fields and startup recovery. Only the new status/barrier regression is promoted. Recorded schema/policy gate follow-ups remain existing debt. |

### Exclusions and outstanding scope
- FRESH-P10-001..012 were screened against their registry descriptions and RP-17/D1/D12. None is restated as a new finding. Related old IDs on CA-P-10-001 identify remediation history only.
- FRESH-P3-005/-006 (retry blocked by statement hash; skips counted as failures) and current P-03 findings are not duplicated here.
- No claim that disconnect/sync interleavings, bank-review promotion identity, low-confidence transfer metadata, or shared logging/privacy behavior are fully safe: deeper verification and old-ID reconciliation remain necessary before any separate promotion.
- **Coverage status: PARTIAL at the broader cell/dependency level.** Twenty distinct production files were read fully (including small contracts/entities); the named bank coordinator, integration, PendingReviewDao and statement processor were covered end-to-end. ReceiptLifecycleCoordinator and TransactionLifecycleCoordinator were read at the bank entry/create boundaries, not in full. Full ExpenseDao/source-link implementation, migrations/schema snapshots, parser/OCR, AI validator/HybridRouter/privacy-provider internals, side-effect runner internals, complete tests and package mates remain outside this bounded pass. No exhaustive cell-completion or zero-other-defects claim is made.
- Report contains **1 new discovery finding: P0 0 / P1 1 / P2 0 / P3 0**. Independent Phase-2 verification is pending; this report does not self-confirm a registry entry.
- Validation: NOT RUN (static-only user instruction). No builds, tests, lint, guards, source changes, commits or pushes.

## Closing provenance
- Auditor: astra-cell-auditor (direct session); direct astra session.
- Agents invoked: none (user explicitly assigned direct cell auditing).
- Pinned source: 37601232b9778170c57a656a245b199ab6d7d965.
- Session date: started 2026-09-21; closing environment date 2026-09-22. Machine clock returned 2026-09-22T00:10:33+03:00; journal records an explicit UTC timestamp.
