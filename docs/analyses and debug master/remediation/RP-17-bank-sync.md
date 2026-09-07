# RP-17 — Bank sync correctness (Pipeline 10, debug-stub-gated)

> **Scope class:** pipeline-isolated; **entire surface is `BuildConfig.DEBUG`-gated** (`requireStubMode()` throws in release; coordinator maps it to `RetryableFailure`; VM drops it silently — REVAL-10). **Mode:** strict.
> **Gated by:** RP-00/D1 (release-hide decision). This plan fixes the code regardless; D1-A additionally hides the release surface.
> **Files:** `BankConnectionLifecycleCoordinator` ⟂, `BankConnectionsViewModel` + `BankConnectionsScreen` (ui/ ⟂), `BankApiIntegration` ⟂, `BankConnectionDao` (data/database/dao/), `BankTokenCipher` ⟂, `PendingReviewDao` (data/database/dao/), `OperationRunRecorder` ⟂, `BankStatementImportRunDao` ⟂, `AppStartupCoordinator` (startup/), `MainActivity` (D1-A gating only).
> **PR shape:** 4 PRs — (17a) result surfacing = REVAL-10 + P10-001 + P10-003 + P10-004-adjacent; (17b) sync integrity = P10-005/006/007/008/009; (17c) classification = P10-002 + P10-010; (17d) recovery wiring = P10-012. P10-004 (dead `refresh()`) → RP-20/D4 deletion; P10-011 minimal.

---

## 17a — Result surfacing & state honesty

### REVAL-10 / P10-001 — Sync results discarded; failures reported as Success (HIGH as a pair)

**Problem.** `BankConnectionLifecycleCoordinator.syncConnection` (`:43-45`) ignores `syncTransactions`'s `SyncResult` — reauth, barrier-block, per-tx failures all → `ConnectionSyncResult.Success`; in release the `requireStubMode()` throw maps to `RetryableFailure`, and `BankConnectionsViewModel.syncConnection` (`:45-49`) maps **all three** branches to `{}` — zero user feedback anywhere.

**Fix design.**
1. Map `SyncResult` honestly in the coordinator: `success=true` → `Success`; `reauthRequired` → `ReauthRequired`; barrier-blocked/cancelled → `Blocked`; else → `RetryableFailure(reason code)`.
2. ViewModel: `Success` → snackbar "Synced" (+refresh icon state via P10-003); `ReauthRequired` → distinct message + connection row shows a re-auth badge; `RetryableFailure`/`Blocked` → error snackbar with bounded, non-technical text (string resources).
3. REVAL-10/D1-A: gate the entire bank-sync nav/settings entry on `BuildConfig.DEBUG` (mirror the Debug destination gating at `MainActivity:844-853`) so release users never reach the silently-failing surface until a real provider ships.

**What it solves.** Every sync outcome is visible; release no longer ships a dead-end feature surface.

**Guardrails.** No capability/permission text leaks in snackbars (controlled strings). **Tests:** coordinator mapping table; VM snackbar state per branch; release-gating compile check.

### P10-003 — `lastSync`/`lastSyncStatus` never written; icon spins forever (HIGH-debug)

**Problem.** `BankConnectionDao.updateSyncStatus` (`:42-43`) has zero callers → `lastSync` null, `lastSyncStatus` "NEVER" forever → `ConnectionStatusIcon` shows the "syncing" spinner permanently (`BankConnectionsScreen.kt:265-287`), "Last synced" never renders (`:230`).

**Fix design.** Call `updateSyncStatus(id, now, status)` in the coordinator around every sync: `RUNNING` on start (drives an honest spinner), then `SUCCESS` / `PARTIAL` (from `SyncResult` partial flags) / `FAILED` on completion — inside the existing correlation flow so a crashed sync leaves `RUNNING` (P10-012's stale sweep then marks it failed). `shouldSync()` (`:461-474`) becomes meaningful automatically.

**Tests.** Icon-state mapping per `lastSyncStatus`; update calls asserted per outcome.

## 17b — Sync integrity

### P10-007 — STRICT dedupe key omits account scope (MED, constructible now)

**Problem.** `strictExternalDedupeKey` = `idem:BANK_API_SYNC:hmac(txn.id)` (`TransactionLifecycleCoordinator.kt:107-116`); `bankAccountIdHash` is computed and persisted as provenance but excluded from the key. Mock provider IDs are **bank-scoped** (`"${bankId}_tx_..."`, `:581`) → two connections to the same bank produce identical hashes → the second silently imports nothing (`InsertConflict`).

**Fix design.** Include the account scope: `idem:BANK_API_SYNC:${bankAccountIdHash}:hmac(txn.id)` at the coordinator (the request carries `bankAccountIdHash`, `CreateExpenseRequest:111-119`). Migration note: existing idem-prefixed `dedupeKey` rows from prior debug syncs will not match new keys → one-time re-import of already-synced mock rows possible in debug builds only — acceptable; wipe/re-sync is the debug norm (note in PR).

**Tests.** Two connections, same bank, same tx id → both import; same connection, same tx re-synced → duplicate skipped.

### P10-005 — `abs(amount)` sign loss: refunds become positive DEPOSIT expenses (MED-HIGH, latent)

**Problem.** `:270` (review path) and `:512` (direct path) take `abs()`; validator rejects non-positive amounts (`TransactionValidator.kt:79`) so the sign can't be preserved; a refund (+credit or description "refund") maps to `TransactionType.DEPOSIT` **with a positive amount stored on an expense row**; `amount == 0.0 → UNKNOWN` fails validation unconditionally (`:548`). Mock generator emits only negatives → latent until a real provider.

**Fix design.**
1. Keep DEPOSIT typing for credits but record direction properly: `CreateExpenseRequest` already carries `transactionType`; deposits are legitimate (income events) — verify how DEPOSIT-typed expenses are treated by totals (P5 verified `PURCHASE_ONLY` filters exist; deposits excluded from spend). The **bug** is refunds-as-income inflating income (symmetric to the P5-004 deposit problem). Minimal correct fix: detect refund semantics at mapping — description containing refund markers (`:543` already inspects text) → type `REFUND` if the enum supports it (check `TransactionType`) else **skip with a structured `REFUND_UNSUPPORTED` diagnostic** (honest non-import) rather than booking phantom income. Do not guess sign conventions for real banks before a provider exists.
2. `amount == 0.0`: skip as `INVALID_AMOUNT` per-item failure with diagnostic instead of UNKNOWN-type validation error (same treatment as other invalid rows).

**Guardrails.** Money rule: no sign/rounding changes for the normal path. This is a stub — keep the fix conservative (skip-and-diagnose) over speculative booking. **Tests:** refund-marker row → skipped + diagnostic; zero-amount → INVALID_AMOUNT; normal negative → PURCHASE (unchanged).

### P10-006 — No in-flight guard; duplicate PendingReviews (MED)

**Problem.** Sync button always enabled (`BankConnectionsScreen.kt:243-248`); no mutex (`BankConnectionsViewModel.kt:41-56`); low-confidence path is check-then-insert without a unique index (`:244-287`) → concurrent syncs duplicate reviews. Spinner-while-loading narrows but doesn't close the window.

**Fix design.**
1. ViewModel: per-connection in-flight set (`MutableStateFlow<Set<Long>>`); `syncConnection` guards with it; button `enabled = connectionId !in syncing`.
2. Belt: move the duplicate-candidate check **inside** the review-insert transaction (same `withTransaction` at `:265-287` — the check currently runs outside at `:244`).

**Tests.** Double-tap fixture → second call ignored; concurrent-path fixture → single review.

### P10-008 / P10-009 — Token-write races & disconnect cleanliness (MED)

**Problem.** `updateToken` (`BankConnectionDao.kt:45-52`) is `WHERE id = :id` only; refresh writes from the start-of-sync snapshot (`:428-434`). Disconnect (`:39-40`) nulls tokens but nothing rechecks — a mid-sync refresh can resurrect credentials onto a disconnected row; bank-source PendingReviews (`packageName = "bank.sync.<id>"`, `:280`) are never cleaned on disconnect.

**Fix design.**
1. `updateToken`: add `AND isConnected = 1` + an expected-token-version predicate if a version column exists — check schema; if none, the isConnected guard alone closes the resurrect-on-disconnect hole (the rotation-lockout scenario needs a real provider's rotating refresh tokens; document that the optimistic-lock half is provider-gated).
2. Sync loop: re-read the connection row before the import section; `isConnected == false` → abort with `CONNECTION_DISCONNECTED` terminal event.
3. Disconnect (coordinator `:58-70`): after nulling tokens, delete pending reviews for `packageName = "bank.sync.<id>"` (new DAO query, barrier-checked) — pending bank suggestions must not outlive the connection.

**Guardrails.** Barrier checks on the new delete; no raw payloads in events. **Tests:** disconnect mid-sync fixture → no token write, sync aborts, reviews purged.

### P10-011 — Cross-source pending-review suppression (LOW)

**Fix design (minimal, decision-consistent with RP-11/P2-008):** keep the fuzzy cross-source suppression (defensible UX) but **record the suppressing review id** in the skip event (`suppressedByReviewId`) so the behavior is auditable and reversible. Full source-scoping is a product call — flag, don't guess. **Tests:** skip event carries the id.

## 17c — Classification fixes

### P10-002 — TRANSFER rows can never import (HIGH as coded, latent)

**Problem.** Default `STORE_REDACTED` nulls `transferAccountName` (`:496-500`); validator requires direction **and** non-blank account name for TRANSFER (`TransactionValidator.kt:141-156`) → guaranteed failure for every real transfer. Latent only because the mock emits none.

**Fix design.**
1. Synthesize direction when absent: infer from sign/movement (`movementType` or amount sign, `:544` already infers type from description) — `transferDirection = provided ?: inferred`.
2. Account name under redaction: use a redaction-safe placeholder `"REDACTED"` **for the bank source only**, or better: relax the validator for `source == BANK_API_SYNC` transfers (account name optional when the raw mode is redacted) — pick the validator relaxation (keeps user-entered transfers strict). Implement as a `TransferValidationMode` parameter or a source-aware branch in `TransactionValidator`.
3. **Tests:** redacted-mode transfer with inferred direction → imports; user path unchanged (still strict).

### P10-010 — Keystore error taxonomy too coarse (LOW, not constructible today)

**Problem.** `BankTokenCipher.decryptWithResult` (`:77-81`): only `KeyPermanentlyInvalidatedException` is classified; `UserNotAuthenticatedException`/`KeyExpiredException` would collapse into permanent `Failed` → misleading `FAILED_FINAL "Token expired"`. Re-validation confirmed the current key spec sets neither auth-gating nor validity → not constructible today.

**Fix design (cheap insurance):** add the two catches mapping to a new `DecryptResult.Transient` → `RefreshOutcome` treats it as retryable (`FAILED_RETRYABLE` reason `KEYSTORE_TRANSIENT`). **Tests:** fake keystore throwing each → correct classification.

## 17d — Recovery wiring (P10-012)

**Problem.** `OperationRunRecorder.recoverStaleRunningOperationRuns` (`:145-197`) and `BankStatementImportRunDao.getStaleRunningRuns`/`markStaleFailed` (`:44, :53`) have zero production callers → process death leaves BANK_SYNC/statement runs RUNNING forever.

**Fix design.** Invoke both from `AppStartupCoordinator.initialize` (next to the existing `recoverStaleRunningJobs` at `:349-352`, same fire-and-forget scope, `runCatchingCancellable` per RP-01's pattern): `operationRunRecorder.recoverStaleRunningOperationRuns(staleThreshold = 15min — match the worker-run convention)` + a small loop marking stale import runs failed. Stale marks emit their existing terminal events (the recorder already does). **Tests:** startup invokes both (fakes); stale run finalized.

## 17e — Tracked partials: explicit dispositions

The registry re-confirmed `P10-P1-03/07/08/09` as still-partial. With the pipeline DEBUG-stub-gated (D1), each gets an explicit disposition rather than speculative implementation:

| ID | Finding | Disposition |
|---|---|---|
| **P10-P1-03** | No persisted incremental sync cursor (`since` caller-supplied, never stored) | **Deferred to provider work.** The dedupe layer is the current (verified-adequate) protection; a cursor is an optimization that only pays off with real volumes. Interim: 17a's `lastSync` write gives the natural cursor home when the time comes — note it in `BankConnectionDao.updateSyncStatus` KDoc. |
| **P10-P1-07** | `refreshToken`'s `updateToken` write not barrier re-checked mid-sync | **Covered by 17b's P10-008/P10-009 fix** (the `isConnected` guard + entry checks + mid-loop re-read); additionally pass the write through the barrier at the `updateToken` call site (`writeBarrier.checkWritesAllowed("bank.token.refresh")` — one line, closes the tracked item verbatim). |
| **P10-P1-08** | Dedupe logic duplicated (sync vs statement import; shared `BankTransactionDeduper` "planned") | **Consolidate in remediation**: extract `BankTransactionDeduper` (domain/) encapsulating the window/tolerance/type-aware checks both sites hand-roll (`BankApiIntegration.kt:235-253`, `BankStatementLifecycleProcessor.kt:521-591` — both already share `DuplicateDetectionPolicy` constants, so the extraction is mechanical). Include in 17b. |
| **P10-P1-09** | Per-transaction commits; no outer sync transaction | **Accepted-for-stub, re-evaluated at provider time.** Retry-safety is provided by idempotent keys + run ledger (verified); true atomicity across a provider batch needs cursor+resume (P10-P1-03) to be meaningful. Document the acceptance in `syncTransactions` KDoc; do not build speculative outer transactions against mock data. |

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*BankApiIntegration*" --tests "*BankConnections*" --tests "*BankSync*" --tests "*BankTokenCipher*" --tests "*OperationRunRecorder*" --tests "*AppStartupCoordinator*" --tests "*BankTransactionDeduper*"
```

## Sequencing & risk
- 17a first (user-visible honesty + D1-A gating), then 17b → 17c → 17d. All debug-build-relevant except D1-A's release gating and REVAL-10's silent-swallow fix (release-relevant). Risk: low — stub surface, good existing test family; schema untouched.
