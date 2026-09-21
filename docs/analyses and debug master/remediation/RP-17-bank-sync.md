# RP-17 — Bank sync correctness and safe product boundary

> **Mode:** strict (money, privacy, lifecycle, Room). **Status:** IMPLEMENTED (static edits, worktree `build/worktrees/rp-17`, branch `rp-17-wip`) — **Validation: PARTIAL / PENDING RE-RUN**; first targeted run (vr-20260921-004114-5dbbafe5) 140 PASSED / 20 FAILED — 15 failures are a PRE-EXISTING base `BankStatementParserTest` regression (attribution-verified on base files, unrelated to this lane; flagged P1 for a base debugger lane), the 5 `BankApiIntegrationTest` failures were triaged and fixed in-lane (test-only) and require a validation re-run; strict bank/lifecycle/privacy review + Room review still REQUIRED before approval.

## Implementation status (2026-09-20 — RP-17 lane; decisions D1 + register D12 applied)

- **17-A — IMPLEMENTED (static edits; tests authored, NOT RUN; review pending).** `domain/bank/BankFeatureAvailability` (D1: debug/provider mode permitted, release gated). Applied to: FeatureConfig menu (`FeatureConfig.availableFeatures` + HomeScreen FeaturesMenu filter), MainActivity render branch (gated destination replaces itself via navigateHome), deep-link host parsing (bank hosts recognized and rejected before the `when`), persisted destination restoration (`destinationFromSaveToken(token, bankSyncAvailable)` returns null for `bank_connections` when gated; ProvideNavigationController passes the gate), and coordinator public `initiateConnection` / `completeConnection` / `syncConnection` (typed `FeatureUnavailable` before any DAO read or integration). Deep-link contract future-proofed via `BANK_DEEP_LINK_HOSTS`.
- **17-B — IMPLEMENTED (static edits; tests authored, NOT RUN; review pending).** Sealed `BankSyncOutcome` (`Success`, `Partial`, `ReauthRequired`, `Blocked`, `RetryableFailure`, `PermanentFailure`, plus typed `NotFound`/`FeatureUnavailable`), each carrying counts and controlled `DiagnosticReasonCode` values; flat `SyncResult` removed. Every barrier/token/reauth/disconnect branch assigns an outcome; cancellation propagates and is never an outcome. Coordinator persists terminal status one-to-one (`SUCCESS`/`PARTIAL` advance `lastSync`; FAILED-family uses new `updateSyncStatusOnly` — `lastSync` never advances on failure; no RUNNING ever persisted). VM maps outcomes one-to-one to `BankSyncUserMessage` + per-connection in-flight ids (`syncingConnectionIds`, ViewModel-only per D12). Screen surfaces snackbar/reauth message.
- **17-C — IMPLEMENTED (static edits; tests authored, NOT RUN; review pending).** `evaluateBankTransactionContract` gates BEFORE lifecycle and before the review queue: zero/NaN amounts skip `INVALID_AMOUNT`; refund/reversal/cashback REQUIRES typed provider semantics (`BankMovementType.REFUND/REVERSAL/CASHBACK`) — refund-like description text without them skips `REFUND_UNSUPPORTED`; transfers require provider-supplied `transferDirection` AND a persistable privacy-safe provider `transferAccountRef` (new field; masked-by-contract), else `TRANSFER_METADATA_MISSING`. No REDACTED placeholder is fabricated; the raw description is never the transfer account name; `TransactionValidator` untouched (still strict). Description-based direction/refund inference removed from `inferTransactionType`.
- **17-D — IMPLEMENTED (static edits; tests authored, NOT RUN; review pending).** `pending_reviews.bankReviewIdentity` (UNIQUE index) + `bankConnectionScopeHash` (index), migration `MIGRATION_148_149`, DB version 148→149. Identity = HMAC("<bankId>|<connectionId>|<providerTransactionId>", purpose `bankReviewIdentity`) — cross-run stable; atomic insert-if-absent via `OnConflictStrategy.IGNORE` + unique index (in-process `Mutex` is an optimization only). Complete bank RawPersistencePolicy applied to review fields: `notificationTitle` = null in all modes, `notificationText` sanitized per mode (was raw description/title before). bankId-scope documented in `buildBankPendingReview` KDoc (two same-bank connections unrepresentable under unique `bank_connections.bankId` — documented, not asserted).
- **17-E — IMPLEMENTED (static edits; tests authored, NOT RUN; review pending).** `BankConnectionDao.updateTokenIfConnected` (`WHERE id = :id AND isConnected = 1`, returns rows; unconditional `updateToken` removed); write barrier checked IMMEDIATELY before the token write; zero rows → `RefreshOutcome.Disconnected` → `Blocked(CONNECTION_DISCONNECTED)` outcome, never a token resurrection. Disconnect: barrier → one transaction { delete reviews scoped by `bankConnectionScopeHash` → `disconnect` (credential clear) }; availability-independent (privacy-positive cleanup). New `BankSyncStartupRecovery` recovers stale bank `operation_runs` (→ CANCELLED / STALE_RUNNING_ABORTED) and stale `bank_statement_import_runs` (→ STALE_FAILED) as two separate families, barrier-guarded; wired into `AppStartupCoordinator.initialize` after stale worker-run recovery.
- **Deferred (unchanged):** provider cursor persistence and outer-batch atomicity remain DEFERRED/gated (register D12); current idempotency documented in `BankApiIntegration.kt` header comment (STRICT_EXTERNAL_ID per-item dedupe + bank-review unique identity).

### Tests authored (serialized validation queue — first run done, triage below)

Unit (`app/src/test`):
- `BankFeatureAvailabilityTest` — release gate typed reason; D1 debug-permitted; bank deep-link host recognition/rejection; restore gate for `bank_connections` token (available/unavailable/non-bank unaffected).
- `BankConnectionLifecycleCoordinatorOutcomeTest` — 17-A gate before DAO/integration for sync/initiate/complete; outcome→terminal-status mapping table (SUCCESS/PARTIAL advance lastSync, FAILED-family via updateSyncStatusOnly, NotFound/FeatureUnavailable touch nothing, no RUNNING); integration exception → RetryableFailure; cancellation propagates; 17-E disconnect ordering/scoped delete/barrier-block/NotFound.
- `BankTransactionContractTest` — INVALID_AMOUNT; REFUND_UNSUPPORTED (text vs typed); typed refund/reversal/cashback → DEPOSIT; TRANSFER_METADATA_MISSING (no direction / no ref / DO_NOT_STORE / description-only "sent to"); valid masked-ref transfer under STORE_REDACTED; ordinary purchase/deposit/withdrawal pass; no direction inference; review identity stability/scoping; review title/text sanitization in all modes; scope hash.
- `BankSyncStartupRecoveryTest` — stale bank operation runs → CANCELLED+STALE_RUNNING_ABORTED; non-bank runs untouched; stale statement-import runs → STALE_FAILED separately; barrier denied → zero writes.
- `BankPendingReviewIdentityDaoTest` (Robolectric, in-memory Room) — duplicate identity insert IGNOREd (-1, one review per identity under concurrency); NULL identities never conflict; `deleteByBankConnectionScope` deletes only scoped rows.
- Updated `BankApiIntegrationTest` (token write via `updateTokenIfConnected`; new zero-rows → Blocked(CONNECTION_DISCONNECTED) test) and `BankConnectionsViewModelTest` (outcome→message mapping incl. ReauthRequired; in-flight ids; consumeUserMessage).

AndroidTest (`app/src/androidTest`):
- `Migration148to149ContractTest` — v148-shaped table + real migration: new columns/indices exist, legacy rows preserved with NULL identity, duplicate identity rejected by unique index, NULLs allowed.
- `DatabaseMigrationMatrixTest` — `CURRENT_VERSION` → 149 + `migrate_148_to_149_adds_bank_review_identity` (assumeTrue-gated on schema JSON).

### Validation triage (2026-09-21 — first targeted run vr-20260921-004114-5dbbafe5: 140 PASSED / 20 FAILED)

- **15 × `BankStatementParserTest` FAILED — PRE-EXISTING BASE DEBT, NOT THIS LANE.** Attribution run on the base files reproduces all 15 failures without any lane diff: the base parser doubled transaction rows (amount/direction classification regression). Flagged **P1 for a dedicated base debugger lane** — the RP-17 lane deliberately does NOT touch `BankStatementParser*` (RP-13 lane ownership).
- **5 × `BankApiIntegrationTest` FAILED — triaged, fixed in-lane, TEST-ONLY (no production change).** All five were test-harness defects in the newly authored tests; production 17-B/17-C/17-D/17-E behavior is correct per the documented contracts. Fixes in `BankApiIntegrationTest.kt`, pending validation re-run:
  1. `completeConnection returns persisted connection with id`, `refreshToken persists new tokens on success`, `token write to disconnected connection yields typed Blocked outcome` — `java.security.KeyStoreException: AndroidKeyStore not found`: the tests drove the real `BankTokenCipher` (Android Keystore object, no constructor seam) on the plain JVM. Fix: `mockkObject(BankTokenCipher)` with a value-preserving round-trip stub (`encryptIfNeeded` → plaintext, `decryptWithResult` → `Success`), installed in `setUp`, released via `@After unmockkAll` (repo-standard static seam, cf. `WorkerExecutionGuardTest`). Assertions on outcomes and `updateTokenIfConnected` writes unchanged. Additionally, both token tests now build `FakeTimeProvider(fixedTime = 1_000L)` — with the default clock of 0 the refresh branch condition `tokenExpiry(1) < now(0)` never fired, so the refresh/persist and Blocked paths were unreachable (the tests would have failed again at `coVerify`/`Blocked` after the cipher fix alone); the refresh test also stubs the coordinator to `CreateExpenseResult.Created` (a bare relaxed mock fabricates a base sealed instance matching no outcome branch) and stubs `withTransaction` on its own database mock (same pattern as setUp).
  2. `low confidence bank transaction triggers pending review on sync` — asserted the RAW provider id (`"low-conf-1"`) as `idempotencyKey`; stale pre-17-C expectation. Production correctly sets `idempotencyKey = HMAC(providerTransactionId)` (P10-CURRENT-006 hash, never the raw id). Fix: assert `idempotencyKey == bankProviderTransactionIdHash` and `!= "low-conf-1"` — pins the documented hash contract; not an assertion weakening.
  3. `same provider transaction id yields stable strict dedupe identity` — `expected:<1> but was:<2>` at the `bankSyncRunId` equality line: the test itself maps the same transaction under `syncRunId = 1L` and `2L`, and `bankSyncRunId` is per-run provenance by design (carried through unchanged). The identity-stability assertions (idempotencyKey + providerTxHash equality) — the actual point of the test — pass. Fix: assert each request carries its own run id (`1L` / `2L`); accountId/bankConnectionId stability assertions unchanged. Production dedupe identity (HMAC `bankId|connectionId|providerTxId`, same-connection scope, unique index) was verified correct — no production bug; the test never seeds pending reviews.

### Blockers / follow-ups for validation (must be resolved at gate time)

- **Schema snapshot `149.json` not yet generated** (KSP export requires a compile; static-only lane). First validation compile will emit it — commit it, then the matrix/identity tests un-gate.
- **DB ownership policy regeneration REQUIRED:** new exact DAO mutation identities in `config/guards/db_ownership_policy.yml`: `BankApiIntegration.refreshToken` → `updateTokenIfConnected` (replaces `updateToken` entry), `BankConnectionLifecycleCoordinator.syncConnection` → `updateSyncStatus` + `updateSyncStatusOnly`, `disconnectConnection` → `pendingReviewDao.deleteByBankConnectionScope`, `BankSyncStartupRecovery.recoverStaleRuns` → `operationRunDao.finalizeIfRunning` + `bankStatementImportRunDao.markStaleFailed`. Hand-editing the policy was out of scope (no-weakening FG-06/07; exact-signature promotion pipeline PR-GR) — regenerate candidate + promote during the validation gate.
- Locale `values-el`/`values-es` translations for the new bank strings not added (default fallback applies).
- RP-13 lane ownership respected: `BankStatementLifecycleProcessor.kt` untouched; 17-E recovery only uses `BankStatementImportRunDao` (whose stale methods were previously uncalled).

## Non-negotiable rules

Expense mutations use the transaction lifecycle. Never infer transfer direction from `abs(amount)`, sign, or description. Never weaken TransactionValidator for bank/redacted data. Outcomes and diagnostics use typed controlled codes, not strings. Read-then-insert is not duplicate atomicity. All Room work includes version, migration, snapshots, and migration tests.

## Decision gates

RP-00 must record D1 release/provider behavior, the database identity contract for bank pending reviews, and whether sync status is terminal-only plus ViewModel in-flight state or gains durable running/attempt fields through a migration.

## 17-A — Central availability and release gating

Create one BankFeatureAvailability policy and apply it to FeatureConfig/menu, MainActivity/navigation, deep-link parsing, persisted destination restoration, and public coordinator `initiateConnection`, `completeConnection`, and `syncConnection`. Unavailable release behavior returns typed `FeatureUnavailable`; it cannot silently retry, render empty, or enter integration. Current deep links have no bank route: test rejection and future-proof the contract.

Tests: release menu/route/direct API blocked; deep-link rejected; restored destination replaced/rejected; D1-permitted debug/provider mode works.

## 17-B — Typed result and status boundary

Replace flat SyncResult/string parsing with sealed `BankSyncOutcome`: `Success`, `Partial`, `ReauthRequired`, `Blocked`, `RetryableFailure`, `PermanentFailure`, each carrying counts and controlled codes. Cancellation propagates unless an existing operation contract deliberately models it. Every barrier/token/reauth branch assigns outcome.

Coordinator persists/maps outcomes one-to-one and returns them; VM maps codes to resources/reauth UI. Define partial/failed and last-sync semantics; do not claim persisted RUNNING without the approved migration.

Tests: integration→coordinator→VM mapping table, all outcome branches, cancellation, transitions, operation-run recovery and statement-run recovery separately.

## 17-C — Transfer/refund/amount contract

Provider mapping must supply `TransferDirection` independently and an approved privacy-safe account reference. Missing metadata skips/quarantines before lifecycle with `TRANSFER_METADATA_MISSING`. Do not use a REDACTED placeholder; validator stays strict. Refund/reversal/cashback requires typed provider semantics or skips with `REFUND_UNSUPPORTED`; zero amounts skip with `INVALID_AMOUNT` before mapping.

Tests: missing direction/reference, valid direction with privacy-safe reference under redaction, debit/credit ambiguity, refund/reversal, ordinary purchase/deposit/withdrawal, and zero amount.

## 17-D — Atomic low-confidence reviews and privacy

Add a privacy-safe stable bank-review identity based on provider transaction identity and approved connection/account scope, a unique index, and atomic DAO insert-if-absent. A mutex may optimize but cannot replace the constraint. Add migration/snapshot tests. Apply complete bank RawPersistencePolicy to pending review fields and events; no forbidden title/description/raw payload storage.

Current bankId uniqueness makes two same-bank connections unrepresentable; document account scope/provider namespace before asserting that scenario. Tests: two concurrent syncs for one connection yield one review; conflict/migration behavior; redaction modes.

## 17-E — Token races and stale recovery

Use `updateTokenIfConnected` SQL (`WHERE id = :id AND isConnected = 1`) returning affected rows. Check the write barrier immediately before it; zero rows become typed disconnected/blocked outcome. Do not claim a version/CAS field without approved schema.

Disconnect follows a defined barrier/transaction order, clears credentials, and deletes only reviews for the stable connection identity. Startup independently recovers stale operation runs and stale statement-import runs. Keystore invalidation/transient errors map to controlled outcomes and cancellation propagates.

Tests: refresh/disconnect race; barrier/zero-row update; review cleanup; Keystore taxonomy; each recovery family.

## Deferred provider work and gates

Cursor persistence and outer-batch atomicity are deferred pending a real provider cursor/identity contract; document remaining idempotency. Order: decisions → 17-A → 17-B → 17-C → 17-D → 17-E. Require strict bank/lifecycle/privacy reviews and Room review for schema batches. RP-17 is not approved until all gates and targeted tests pass.
