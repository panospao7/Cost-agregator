# Pipeline 10 Static Debug Report — Bank Integration / Bank Statement Imports

Commit reviewed: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`  
Mode: static GitHub/code-doc review only. I did **not** run Gradle/tests locally.

## Executive verdict

Pipeline 10 is **still mostly a demo/prototype shell**, not a production-ready bank integration.

There are some meaningful improvements:

```text
BankApiIntegration now injects DatabaseWriteBarrier
demo bank sync is release-blocked by BuildConfig.DEBUG
BankApiConfig.isStubMode guard exists
completeConnection() now sets createdAt
tokens are encrypted through BankTokenCipher.encryptIfNeeded()
BankStatementLifecycleProcessor now checks existing approved expenses and pending reviews
bank statement pending-review dedupe is currency/type/date/amount aware
```

But the critical production workflow is still absent:

```text
real provider registry
real OAuth state/PKCE session
persistent connection lifecycle
account-level model
durable sync run ledger
per-transaction import state
token refresh persistence
low-confidence review route
bank/account metadata source links
bank-token backup/restore policy
```

Highest remaining user-impact risks:

1. **Bank connection UI is still demo/no-op** and does not load or mutate real DB connections.
2. **`completeConnection()` returns a connected entity but does not persist it.**
3. **OAuth callback/session security is not modeled at all.**
4. **Sync has no durable run/checkpoint/per-transaction ledger.**
5. **All bank API sync transactions are auto-imported as approved expenses.**
6. **Imported expenses do not preserve bank connection/account/provider/sync-run metadata.**
7. **Token refresh does not persist refreshed tokens or update auth state.**
8. **Bank statement import dedupe is improved but not shared with API sync.**
9. **Bank statement import is still not atomic as a statement-level import run.**
10. **Bank token backup/restore behavior is undefined.**

Current status: **orange**. Safe enough as a release-disabled demo stub, but not safe or functional as a real bank integration.

---

# Sources checked

- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- Master tracker:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md

- Previous Pipeline 10 report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline-10-bank-integration-imports-debug-report.md

- Current code:
  - `BankApiIntegration.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt
  - `BankApiConfig.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiConfig.kt
  - `StubForDemo.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/bank/StubForDemo.kt
  - `BankConnection.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/BankConnection.kt
  - `BankConnectionDao.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/BankConnectionDao.kt
  - `BankTokenCipher.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/security/BankTokenCipher.kt
  - `BankConnectionsViewModel.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/ui/screens/bank/BankConnectionsViewModel.kt
  - `BankConnectionsScreen.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/ui/screens/bank/BankConnectionsScreen.kt
  - `BankStatementLifecycleProcessor.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt
  - `CreateExpenseRequest.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt
  - `ExpenseSource.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/ExpenseSource.kt
  - `AppDatabase.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt
  - `PendingReview.kt` / `PendingReviewDao.kt`

---

# 1. Tracker reconciliation

Master tracker currently says:

| ID | Tracker status |
|---|---|
| P10-P0-01 | fixed |
| P10-P0-02 | TODO |
| P10-P1-01 | TODO |
| P10-P1-02 | TODO |
| P10-P1-03 | TODO |
| P10-P1-04 | TODO |
| P10-P1-05 | TODO |
| P10-P1-06 | TODO |
| P10-P1-07 | TODO |
| P10-P1-08 | TODO |
| P10-P1-09 | TODO |

My current status:

| ID | My status | Reason |
|---|---:|---|
| P10-P0-01 | **Fixed for release safety / feature still demo-only** | `requireStubMode()` blocks non-debug builds and requires `BankApiConfig.isStubMode`. Fake sync should not run in release, but there is still no production provider. |
| P10-P0-02 | **Open** | `BankConnectionsViewModel` is still demo/no-op and injects no repository. |
| P10-P1-01 | **Partial** | `completeConnection()` now sets `createdAt` and checks write barrier, but it still does not persist or upsert through DAO/coordinator. |
| P10-P1-02 | **Open** | No OAuth session entity, state, PKCE verifier, expiry, replay protection, or callback validation. |
| P10-P1-03 | **Open** | No `BankSyncRun` / `BankTransactionImport` entity in `AppDatabase`; sync result is in-memory only. |
| P10-P1-04 | **Open** | Bank API sync still auto-creates approved expenses; TODO comment admits classifier/review route missing. |
| P10-P1-05 | **Open / partial source infra only** | `CreateExpenseRequest` has generic source fields, but not bank-specific fields, and comments say accepted fields are not persisted. |
| P10-P1-06 | **Open** | `refreshToken()` only decrypts refresh token and returns Boolean. No provider call or DAO update. |
| P10-P1-07 | **Partial** | `BankApiIntegration` now has write-barrier checks, but raw DAO writes remain public and no bank lifecycle coordinator exists. |
| P10-P1-08 | **Partial / improved** | Statement import now checks approved expenses + pending reviews. Still no shared deduper with API sync/import ledger. |
| P10-P1-09 | **Open** | Bank API sync still imports one by one with no durable sync transaction semantics. |

Older P2 issues:

| Old issue | My status |
|---|---:|
| P2-12 unique bank index too coarse | **Open** — `Index(value=["bankId"], unique=true)` remains. |
| P2-13 transaction type inference fragile | **Partial** — `movementType` is now preferred, but text/amount fallback remains. |
| P2-14 transfer account name uses raw description | **Open** — still uses `transaction.description` fallback. |
| P2-15 token backup/restore policy undefined | **Open**. |
| P2-16 sync errors may expose raw IDs/descriptions | **Open** — errors include raw transaction ID and exception message. |

---

# 2. Original issue evaluation

## P10-P0-01 — Bank API integration is demo-only stub behavior

### Current state

Release-safety aspect is fixed.

`BankApiIntegration.requireStubMode()` now does:

```kotlin
if (!BuildConfig.DEBUG) error("Bank integration is demo-only and disabled in release builds")
require(BankApiConfig.isStubMode)
```

This is good: mock random transactions should not be imported in release builds.

Still true:

- `BankApiIntegration` is still the only bank provider.
- Core methods are still annotated `@StubForDemo`.
- `syncTransactions()` still calls `generateMockTransactions()`.
- `BankApiConfig.isStubMode` defaults to `true`.
- There is no production `BankProvider` interface/registry.

### Classification

- **Original release data-corruption risk:** fixed.
- **Feature functionality:** still not production-ready.

### Fix strategy

Keep the release guard. Then split the architecture:

```kotlin
interface BankProvider {
    val providerId: String
    suspend fun startConnection(...)
    suspend fun completeConnection(...)
    suspend fun refreshToken(...)
    suspend fun fetchTransactions(...)
}
```

Move current code to:

```text
DemoBankProvider
```

Production DI should register no demo provider unless debug build.

---

## P10-P0-02 — Bank connection UI ViewModel is no-op

### Current state

Still open.

`BankConnectionsViewModel`:

- injects no repository,
- sets `isDemoMode = true`,
- fills a local list from `BankApiIntegration.SUPPORTED_BANKS`,
- all rows have `id = 0`,
- `syncConnection()` returns immediately,
- `disconnect()` returns immediately,
- `refresh()` is no-op.

`BankConnectionsScreen` also maintains local hidden IDs. Because demo rows have `id = 0`, hiding/removing one demo row can hide all demo rows.

### User impact

User cannot:

```text
load actual bank connections
start real sync
disconnect or delete connection
see durable auth/sync error
recover failed connection
```

### Fix strategy

Add:

```kotlin
interface BankConnectionRepository {
    fun observeConnections(): Flow<List<BankConnectionUi>>
    suspend fun startConnection(bankId: String): BankConnectionStartResult
    suspend fun completeConnection(callback: BankAuthCallback): BankConnectionResult
    suspend fun syncConnection(connectionId: Long): BankSyncResult
    suspend fun disconnect(connectionId: Long): BankDisconnectResult
}
```

ViewModel should use real repository state. Demo supported-bank list should be separate from persisted connection rows and should not use `BankConnection(id=0)` as fake connected entities.

---

## P10-P1-01 — `completeConnection()` does not persist entity

### Current state

Partial.

Good:

- `completeConnection()` now checks `writeBarrier`.
- It sets `createdAt = timeProvider.now()`.
- It encrypts demo access/refresh tokens.

Still missing:

- no `dao.insert()`,
- no upsert,
- no transaction,
- no connection event,
- no duplicate-bank conflict handling,
- no `updatedAt`,
- no `lastError` reset,
- no token/account/provider metadata validation.

`BankConnectionDao.insert()` is plain `@Insert`, so unique conflict on `bankId` would throw rather than return structured duplicate/update result.

### Classification

Actual feature bug.

### Fix strategy

Add `BankConnectionLifecycleCoordinator`.

Flow:

```text
provider.completeConnection(callback) -> token/account result
coordinator.withTransaction {
    writeBarrier.checkWritesAllowed()
    validate callback/session/account
    encrypt tokens
    upsert connection/account rows
    write BANK_CONNECTED event
}
```

---

## P10-P1-02 — No OAuth state/PKCE/callback validation

### Current state

Open.

`initiateConnection()` returns a placeholder URL with no durable session:

```text
https://oauth.<bank>.example.com/auth?client_id=demo&response_type=code
```

`completeConnection(bankId, authCode)` accepts only bank ID and auth code. The `authCode` is not meaningfully validated.

### User/security impact

A real integration would be vulnerable or impossible to validate:

```text
wrong-bank callback
CSRF callback spoofing
state replay
expired auth session
missing PKCE verifier
session consumed twice
```

### Fix strategy

Add:

```kotlin
BankAuthSession(
    id,
    providerId,
    bankId,
    stateHash,
    pkceVerifierEncrypted,
    redirectUri,
    createdAt,
    expiresAt,
    consumedAt,
    status
)
```

`completeConnection()` must validate and consume the session atomically.

---

## P10-P1-03 — Sync has no durable run ledger/checkpoint

### Current state

Open.

`AppDatabase` includes `BankConnection`, but no `BankSyncRun` or `BankTransactionImport`.

`syncTransactions()` returns an in-memory `SyncResult`.

It does not persist:

```text
run started/finished
cursor before/after
page checkpoints
provider page token
per-transaction status
imported/skipped/review/error counts
last successful cursor
partial failure state
```

`BankConnectionDao.updateSyncStatus()` exists but is not called by `BankApiIntegration.syncTransactions()`.

### User impact

If sync crashes midway:

```text
some expenses may exist
connection lastSync may remain stale
retry cannot resume precisely
support cannot know which bank transaction failed
```

### Fix strategy

Add:

```kotlin
BankSyncRun(
    id,
    connectionId,
    providerId,
    startedAt,
    finishedAt,
    status,
    cursorBefore,
    cursorAfter,
    importedCount,
    skippedCount,
    reviewCount,
    errorCount,
    errorSummary
)

BankTransactionImport(
    id,
    syncRunId,
    connectionId,
    providerTransactionIdHash,
    fingerprint,
    status,
    expenseId,
    reviewId,
    errorCode,
    errorMessageRedacted,
    rawMetadataRedactedJson
)
```

---

## P10-P1-04 — No low-confidence review route

### Current state

Open.

`syncTransactions()` maps every fetched transaction to `CreateExpenseRequest` and calls:

```kotlin
coordinator.createExpense(request)
```

A TODO explicitly says a `BankTransactionClassifier` should route low-confidence transactions to `PendingReview`.

### User impact

Ambiguous bank records become approved expenses immediately:

```text
unknown merchant
possible transfer
refund/reversal
zero/invalid amount
missing currency
pending authorization
duplicate uncertainty
unsupported transaction type
```

### Fix strategy

Add:

```kotlin
BankTransactionClassification(
    transactionType,
    confidence,
    reasons,
    categoryId,
    requiresReview
)
```

Route:

```text
high confidence -> TransactionLifecycleCoordinator.createExpense()
low confidence -> PendingReview(source=BANK_API_SYNC)
```

---

## P10-P1-05 — Bank metadata not preserved on imported expenses

### Current state

Open / source-link infrastructure partial.

`CreateExpenseRequest` contains generic source link fields, but current comment says many are accepted but not persisted by coordinator.

There are no bank-specific fields:

```text
bankConnectionId
providerId
bankId
accountId
providerTransactionId
syncRunId
bookingDate
valueDate
status posted/pending
raw provider metadata hash
```

`BankApiIntegration.mapTransactionToExpense()` only sets:

```text
source = BANK_API_SYNC
idempotencyKey = transaction.id
notes = description + reference
categoryId = connection.defaultCategoryId
```

### User impact

The app cannot answer:

```text
Which bank/account created this expense?
Which sync run imported it?
Which provider transaction was it?
Can one bank/account be disconnected and its imported rows found?
Was it booking date or value date?
```

### Fix strategy

Short-term:

- Add bank source metadata to `TransactionEvent.metadata`.

Long-term:

```text
expense_source_links(
  expenseId,
  sourceType,
  connectionId,
  accountId,
  providerId,
  providerTransactionIdHash,
  syncRunId,
  metadataJson
)
```

---

## P10-P1-06 — Token refresh does not persist tokens

### Current state

Open.

`refreshToken(connection)`:

- calls `BankTokenCipher.decryptIfNeeded(connection.refreshToken)`,
- returns `false` if missing/invalid,
- otherwise returns `true`.

It does not:

```text
call provider refresh endpoint
persist new access token
persist rotated refresh token
update tokenExpiry
update tokenEncryptionVersion
reset consecutiveErrors
mark reauth required
update lastError
write TOKEN_REFRESHED event
```

### User impact

Expired bank connections cannot really recover. A real provider path would fail or behave undefined.

### Fix strategy

Provider result:

```kotlin
sealed interface TokenRefreshResult {
    data class Refreshed(...)
    data class ReauthRequired(...)
    data class Failed(val retryable: Boolean, ...)
}
```

Coordinator persists the result in one guarded transaction.

---

## P10-P1-07 — No restore/write barrier around bank writes

### Current state

Partial.

Good:

`BankApiIntegration` now injects `DatabaseWriteBarrier` and checks it in:

```text
initiateConnection()
completeConnection()
syncTransactions()
```

Limitations:

- `BankConnectionDao` write methods remain public and unguarded.
- There is no repository/coordinator single writer.
- `refreshToken()` itself has no direct guard, though currently called inside guarded `syncTransactions()`.
- future UI/repository code can call `BankConnectionDao.disconnect/updateToken/updateSyncStatus` directly.
- `BankStatementLifecycleProcessor` uses `RestoreMaintenanceMode` directly, not `DatabaseWriteBarrier`.

### Classification

Partial restore-safety fix, not a global guarantee.

### Fix strategy

All bank writes must go through:

```text
BankConnectionLifecycleCoordinator
BankSyncCoordinator
BankStatementImportCoordinator
```

Each uses `DatabaseWriteBarrier`.

Add static guard for direct `BankConnectionDao` mutations.

---

## P10-P1-08 — Bank statement import dedupe weaker than expense dedupe

### Current state

Improved but still partial.

Good:

`BankStatementLifecycleProcessor` now checks:

```text
existing approved expenses
existing pending reviews
merchant key/name
date window
amount tolerance
currency
transaction type
```

This is a significant improvement.

Still missing:

1. No shared `BankTransactionDeduper` used by both API sync and statement import.
2. No dedupe against `BankTransactionImport` rows because those rows do not exist.
3. API sync does not check pending statement reviews before auto-importing.
4. Dedupe result is only parsing log/debug data, not durable per-transaction import outcome.
5. Statement import has no provider/account/source metadata to strengthen matching.

### Classification

Statement-path bug mostly fixed; cross-source import architecture still open.

### Fix strategy

Create `BankTransactionDeduper`.

Inputs:

```text
providerTransactionId
connectionId/accountId
merchantKey
raw merchant
amount
currency
booking/value date
transactionType
source
```

Checks:

```text
expenses
pending reviews
bank_transaction_import rows
statement import items
```

---

## P10-P1-09 — Bank import creates expenses one-by-one without sync transaction semantics

### Current state

Open.

`syncTransactions()` loops mock transactions and independently calls:

```kotlin
coordinator.createExpense(request)
```

No outer sync run or per-item state exists.

### User impact

Partial runs are opaque:

```text
A imported
B failed validation
C not reached due crash
connection status unchanged
retry behavior unclear
```

### Fix strategy

Do not wrap all transactions in one giant transaction. Instead, use per-transaction durable state:

```text
RECEIVED
CLASSIFIED
IMPORTED_EXPENSE
PENDING_REVIEW
DUPLICATE_SKIPPED
FAILED_VALIDATION
FAILED_RETRYABLE
FAILED_FINAL
```

Run finalizer computes status from child rows.

---

# 3. New/current issues found

## P10-NEW-01 — Demo UI rows all have `id = 0`, causing local hide/remove bugs

### Severity

P2, but visible in current demo UI.

### Evidence

`BankConnectionsViewModel` builds fake `BankConnection` objects without IDs. Room default is `id = 0`.

`BankConnectionsScreen` stores hidden rows by:

```kotlin
hiddenConnectionIds + connection.id
```

Since every demo row has `id = 0`, hiding/removing one can hide all demo rows.

### Fix

Demo supported-bank rows should use a separate UI model:

```kotlin
BankConnectionUi(
    stableKey = "supported:${bankId}",
    connectionId = null,
    bankId = ...
)
```

Do not fake Room entities for available banks.

---

## P10-NEW-02 — UI exposes “connect bank” even though real provider is unavailable

### Severity

P2/P1 UX.

### Evidence

`BankConnectionsScreen` has an Add button and empty-state connect button. ViewModel is demo/no-op.

### User impact

User can enter a flow that cannot complete, or see banks that cannot really connect.

### Fix

Add feature availability state:

```kotlin
BankIntegrationAvailability(
    available = false,
    reason = DEMO_ONLY_NO_PROVIDER
)
```

Hide/disable connect action in release unless real provider is registered.

---

## P10-NEW-03 — `BankTokenCipher.decryptIfNeeded()` accepts plaintext tokens

### Severity

P1/P2 security hardening.

### Evidence

If the stored token does not start with `enc:v1:`, `decryptIfNeeded()` returns the input as plaintext.

### Impact

Direct DAO insertion or migration bugs can leave plaintext tokens usable and silently accepted.

### Fix

For bank tokens, use strict decrypt:

```kotlin
fun decryptRequiredEncrypted(value: String?): TokenDecryptResult
```

Rules:

```text
null -> missing
enc:v1 -> decrypt
anything else -> invalid_plaintext_token
```

Then mark connection `REAUTH_REQUIRED` and wipe plaintext.

---

## P10-NEW-04 — Sync status and error state are never updated

### Severity

P1/P2.

### Evidence

`BankConnectionDao.updateSyncStatus()` exists, and `BankConnection` has status/error fields, but `BankApiIntegration.syncTransactions()` does not update them.

### User impact

UI can show stale `lastSync` / `lastSyncStatus` even after a sync attempt.

### Fix

Sync finalizer updates connection:

```text
lastSync
lastSyncStatus = SUCCESS/PARTIAL/FAILED
lastError
lastErrorTime
consecutiveErrors
```

Only after `BankSyncRun` finalization.

---

## P10-NEW-05 — Demo sync idempotency is weak because mock IDs are random/date-derived

### Severity

P2 debug; would be P1 if any stub path reached users.

### Evidence

`generateMockTransactions()` uses random count, random merchants, random amounts, and IDs:

```text
"${bankId}_tx_${i}_${date}"
```

Date depends on random count and `since`.

### Impact

Repeated demo sync can import different fake expenses each time, defeating idempotency tests.

### Fix

Demo provider should use deterministic fixtures seeded by bank ID + cursor window, or never call transaction lifecycle insert.

---

## P10-NEW-06 — Bank statement import is not atomic as a statement import run

### Severity

P1/P2.

### Evidence

`BankStatementLifecycleProcessor` does:

```text
insert statement receipt
insert RECEIPT_SAVED event
loop: insert PendingReview rows
update receipt status
insert PROCESSING_COMPLETE event
```

No single import-run transaction/ledger wraps the whole statement. A crash can leave:

```text
receipt saved
some reviews inserted
status not updated
no final event
```

### Fix

Add `BankStatementImportRun` / item rows, or reuse `BankSyncRun` abstraction for all bank import sources.

---

## P10-NEW-07 — Bank statement debug data returns raw OCR text

### Severity

P1/P2 privacy.

### Evidence

Stored `ScannedReceipt.rawOcrText` is sanitized through `RawContentSanitizer`, but returned `DebugData` uses:

```kotlin
rawText = ocrResult.fullText
```

### Impact

Even if storage policy redacts raw OCR, debug UI/export can still receive raw bank statement text.

### Fix

Debug data should be built from a debug/export privacy policy:

```text
STORE_RAW + debug consent -> raw
STORE_REDACTED -> redacted
METADATA_ONLY/DO_NOT_STORE -> no raw body
```

---

## P10-NEW-08 — Raw bank descriptions are stored in expense notes and transfer account name

### Severity

P1/P2 privacy/data quality.

### Evidence

`mapTransactionToExpense()` uses:

```kotlin
transferAccountName = transaction.description.takeIf { it.isNotBlank() }
notes = transaction.description + reference
```

### Impact

Raw bank descriptions may contain:

```text
counterparty name
IBAN/account fragments
payment references
personal notes
```

These enter expenses and exports/search.

### Fix

Separate structured fields:

```text
counterpartyName
counterpartyAccountMasked
providerReferenceHash
redactedDescription
```

Apply raw-storage/privacy policy before persisting notes.

---

## P10-NEW-09 — Bank import has no posted/pending transaction status

### Severity

P1/P2.

### Evidence

`BankTransaction` lacks provider status:

```text
POSTED
PENDING
BOOKED
AUTHORIZED
CANCELLED
REVERSED
```

### Impact

Pending card authorizations can become approved expenses, then later posted transactions can create duplicates.

### Fix

Add `BankTransactionStatus` and policy:

```text
POSTED/BOOKED -> eligible
PENDING/AUTHORIZED -> pending review or ignored until posted
CANCELLED/REVERSED -> skip or reversal handling
```

---

## P10-NEW-10 — Bank connection model has no auth status

### Severity

P1/P2.

### Evidence

`BankConnection` only has:

```text
isConnected
isActive
lastSyncStatus
lastError
```

No explicit:

```text
CONNECTED
REAUTH_REQUIRED
TOKEN_REFRESH_FAILED
DISCONNECTED_BY_USER
PROVIDER_UNAVAILABLE
```

### Impact

A restored undecryptable token or revoked consent can look generically disconnected/failed.

### Fix

Add:

```kotlin
enum class BankAuthStatus {
    CONNECTED,
    REAUTH_REQUIRED,
    DISCONNECTED,
    TOKEN_INVALID,
    PROVIDER_ERROR
}
```

---

# 4. Actual bugs vs architectural work

## Actual user-affecting bugs

Prioritize:

1. **Bank UI is demo/no-op and can hide all demo rows because all IDs are 0.**
2. **Bank connection completion is not persisted.**
3. **OAuth callback/session validation is absent.**
4. **Token refresh cannot actually refresh or persist tokens.**
5. **Sync status/error is not written to connection rows.**
6. **Bank API sync auto-approves all transactions with no review route.**
7. **Bank source/account metadata is lost on expenses.**
8. **Bank statement import can partially complete without run ledger.**
9. **Raw bank descriptions can be persisted into notes/account names.**
10. **Bank-token restore policy is undefined.**

## Architectural / hardening work

Important but lower immediate urgency:

1. Real provider registry.
2. Account-level bank connection model.
3. OAuth session table.
4. Bank sync run and import item tables.
5. Shared bank transaction deduper.
6. Bank transaction classifier.
7. Bank source-link table.
8. Token lifecycle coordinator.
9. Bank connection lifecycle events.
10. Strict encrypted-token storage contract.

---

# 5. Recommended implementation plan

## PR 1 — Keep demo safe and fix UI availability

### Goal

Bank feature cannot pretend to work when no real provider exists.

### Files

- `BankApiIntegration.kt`
- `BankApiConfig.kt`
- `BankConnectionsViewModel.kt`
- `BankConnectionsScreen.kt`
- DI provider registration

### Tasks

1. Keep `BuildConfig.DEBUG` release guard.
2. Add bank integration availability state.
3. Hide/disable connect/sync in release when no provider exists.
4. Replace fake `BankConnection` rows with `BankConnectionUi`.
5. Fix stable key bug: use `bankId`, not entity `id=0`.

### Acceptance tests

```text
release_build_cannot_run_demo_bank_sync
bank_ui_release_no_provider_shows_unavailable
demo_supported_banks_use_unique_stable_keys
removing_one_demo_bank_does_not_hide_all
```

---

## PR 2 — Bank connection lifecycle coordinator/repository

### Goal

Connections are durable, guarded, audited, and UI-backed.

### Files

- new `BankConnectionLifecycleCoordinator.kt`
- new `BankConnectionRepository.kt`
- `BankConnectionDao.kt`
- `BankConnection.kt`
- `BankConnectionsViewModel.kt`

### Tasks

1. Add coordinator single writer.
2. Add `updatedAt`.
3. Add `authStatus`.
4. Complete connection persists/upserts row.
5. Disconnect wipes tokens and writes event.
6. ViewModel observes real repository.
7. All writes use `DatabaseWriteBarrier`.

### Acceptance tests

```text
complete_connection_persists_connection
complete_connection_sets_createdAt_updatedAt
duplicate_connection_returns_structured_result
disconnect_wipes_tokens
restore_mode_blocks_connection_writes
viewmodel_loads_connections_from_repository
```

---

## PR 3 — OAuth session + PKCE

### Goal

Real callback validation is possible.

### Files

- `BankAuthSession.kt`
- `BankAuthSessionDao.kt`
- `BankProvider.kt`
- `BankConnectionLifecycleCoordinator.kt`

### Tasks

1. Create auth session entity.
2. Store state hash and encrypted PKCE verifier.
3. Expire sessions.
4. Mark consumed atomically.
5. Reject replay/wrong-state/wrong-bank callbacks.

### Acceptance tests

```text
oauth_callback_wrong_state_rejected
oauth_callback_replay_rejected
oauth_callback_expired_session_rejected
oauth_callback_wrong_bank_rejected
successful_callback_consumes_session_and_creates_connection
```

---

## PR 4 — Sync run ledger and per-transaction import state

### Goal

Bank sync is resumable and debuggable.

### Files

- `BankSyncRun.kt`
- `BankTransactionImport.kt`
- DAOs
- `BankSyncCoordinator.kt`
- `AppDatabase.kt`
- migrations

### Tasks

1. Add sync run entity.
2. Add import item entity.
3. Persist cursor/page checkpoint.
4. Record each transaction outcome.
5. Finalize run from item counts.
6. Update `BankConnection.lastSyncStatus` from run status.

### Acceptance tests

```text
sync_run_records_started_and_completed
sync_partial_failure_records_PARTIAL
sync_crash_after_page_can_resume
retry_only_processes_unfinished_items
connection_sync_status_updated_after_run_finalization
```

---

## PR 5 — Bank transaction classifier and review route

### Goal

Ambiguous bank records do not become approved expenses automatically.

### Files

- `BankTransactionClassifier.kt`
- `BankSyncCoordinator.kt`
- `PendingReviewDao.kt`
- review approval path

### Tasks

1. Add classification model.
2. Define auto-accept threshold.
3. Route low-confidence to `PendingReview`.
4. Include classification reasons.
5. Add pending-review source metadata.

### Acceptance tests

```text
high_confidence_purchase_auto_imports
low_confidence_bank_transaction_creates_pending_review
pending_authorization_goes_to_review_or_skip
ambiguous_transfer_goes_to_review
approved_bank_review_uses_transaction_lifecycle
```

---

## PR 6 — Bank source metadata persistence

### Goal

Every bank-created expense is traceable.

### Files

- `CreateExpenseRequest.kt`
- `TransactionLifecycleCoordinator.kt`
- `TransactionEvent.kt`
- optional `ExpenseSourceLink.kt`

### Tasks

1. Add bank source metadata fields or source-link table.
2. Persist:
   - connection ID,
   - account ID,
   - provider ID,
   - provider transaction ID hash,
   - sync run ID,
   - booking/value date,
   - transaction status.
3. Include metadata in created/duplicate/failure events.

### Acceptance tests

```text
bank_import_persists_connection_id
bank_import_persists_account_id
bank_import_event_contains_sync_run_id
bank_import_event_contains_provider_transaction_hash
```

---

## PR 7 — Shared bank dedupe for API sync and statements

### Goal

Bank API sync, statements, notifications, receipts, and manual rows do not duplicate.

### Files

- `BankTransactionDeduper.kt`
- `BankSyncCoordinator.kt`
- `BankStatementLifecycleProcessor.kt`
- DAOs

### Tasks

1. Centralize dedupe.
2. Check:
   - expenses,
   - pending reviews,
   - bank import rows.
3. Match by provider transaction ID when available.
4. Fallback to merchant/date/amount/currency/type/account.
5. Persist duplicate outcome.

### Acceptance tests

```text
statement_import_skips_existing_approved_expense
statement_import_skips_existing_pending_review
api_sync_skips_existing_statement_pending_review
api_sync_skips_duplicate_provider_transaction_id
notification_expense_prevents_bank_statement_duplicate_review
```

---

## PR 8 — Token refresh and backup/restore policy

### Goal

Token lifecycle is secure and predictable.

### Files

- `BankTokenCipher.kt`
- `BankConnectionLifecycleCoordinator.kt`
- `BankSyncCoordinator.kt`
- backup/restore startup check

### Tasks

1. Strictly reject plaintext tokens.
2. Provider refresh returns structured result.
3. Persist new encrypted token/expiry.
4. Mark `REAUTH_REQUIRED` on revoked/undecryptable token.
5. On restore, validate decryptability or wipe tokens.
6. Redacted backups exclude tokens.

### Acceptance tests

```text
plaintext_token_marked_invalid_and_wiped
expired_token_refresh_persists_new_tokens
refresh_failure_does_not_import_transactions
restored_undecryptable_token_marks_reauth_required
redacted_backup_excludes_bank_tokens
```

---

## PR 9 — Bank statement import run atomicity

### Goal

Statement import has durable per-item outcomes.

### Files

- `BankStatementLifecycleProcessor.kt`
- new `BankStatementImportRun.kt` / reuse `BankSyncRun`
- `PendingReviewDao.kt`
- `ReceiptEventDao.kt`

### Tasks

1. Create statement import run.
2. Insert receipt/event/review/item outcome atomically per item.
3. Finalize status/counts.
4. Resume or mark partial after crash.
5. Privacy-sanitize debug data.

### Acceptance tests

```text
statement_import_partial_failure_records_failed_item
statement_import_status_complete_only_after_final_event
statement_import_crash_midway_can_resume_or_marks_partial
statement_debug_data_respects_raw_ocr_storage_mode
```

---

## PR 10 — Bank privacy/error sanitization

### Goal

Raw bank text and IDs do not leak through notes/errors/debug.

### Files

- `BankApiIntegration.kt`
- `BankSyncError.kt`
- `BankStatementLifecycleProcessor.kt`
- privacy sanitizer/redactor

### Tasks

1. Replace free-form `String` errors with typed `BankSyncError`.
2. Hash provider transaction IDs in UI/debug.
3. Redact descriptions/references before notes.
4. Do not use raw description as transfer account name.
5. Add bank-specific raw storage policy.

### Acceptance tests

```text
sync_error_does_not_include_raw_description
sync_error_hashes_provider_transaction_id
transfer_account_name_does_not_use_raw_description_when_redaction_enabled
bank_notes_are_redacted_by_policy
```

---

# 6. Suggested tracker updates

Update Pipeline 10 tracker:

| ID | Suggested status |
|---|---|
| P10-P0-01 | Fixed for release safety; demo-only feature caveat |
| P10-P0-02 | TODO / open |
| P10-P1-01 | Partial |
| P10-P1-02 | TODO / open |
| P10-P1-03 | TODO / open |
| P10-P1-04 | TODO / open |
| P10-P1-05 | TODO / open |
| P10-P1-06 | TODO / open |
| P10-P1-07 | Partial |
| P10-P1-08 | Partial / improved |
| P10-P1-09 | TODO / open |

Add new items:

| New ID | Severity | Title |
|---|---:|---|
| P10-NEW-01 | P2 | Demo UI rows all have `id = 0`, causing hide/remove bugs |
| P10-NEW-02 | P2/P1 | UI exposes connect action although real provider is unavailable |
| P10-NEW-03 | P1/P2 | `BankTokenCipher.decryptIfNeeded()` accepts plaintext tokens |
| P10-NEW-04 | P1/P2 | Sync status and error state are never updated |
| P10-NEW-05 | P2 | Demo sync idempotency is weak due random/date-derived mock IDs |
| P10-NEW-06 | P1/P2 | Bank statement import is not atomic as an import run |
| P10-NEW-07 | P1/P2 | Bank statement debug data returns raw OCR text |
| P10-NEW-08 | P1/P2 | Raw bank descriptions are stored in notes/account name |
| P10-NEW-09 | P1/P2 | Bank import has no posted/pending transaction status |
| P10-NEW-10 | P1/P2 | Bank connection model has no explicit auth status |

---

# 7. Golden tests for Pipeline 10

Add or verify:

```text
release_build_cannot_run_demo_bank_sync
debug_demo_provider_generates_no_expenses_unless_explicitly_enabled
bank_ui_release_no_provider_shows_unavailable
demo_supported_banks_use_unique_stable_keys
removing_one_demo_bank_does_not_hide_all
viewmodel_loads_real_connections_from_repository
complete_connection_persists_connection
complete_connection_sets_createdAt_updatedAt
complete_connection_encrypts_tokens
complete_connection_rejects_duplicate_or_updates_existing
restore_mode_blocks_bank_connect
restore_mode_blocks_bank_sync
restore_mode_blocks_bank_disconnect
oauth_callback_wrong_state_rejected
oauth_callback_replay_rejected
oauth_callback_expired_session_rejected
successful_callback_consumes_session_and_creates_connection
expired_token_refresh_success_persists_new_token
expired_token_refresh_failure_imports_zero_transactions
reauth_required_marks_connection_attention_needed
plaintext_token_marked_invalid_and_wiped
restored_undecryptable_bank_token_marks_reauth_required
sync_run_records_started_completed_and_counts
sync_partial_failure_records_PARTIAL
sync_crash_after_page_can_resume_from_checkpoint
bank_transaction_import_records_each_outcome
high_confidence_bank_purchase_auto_imports
low_confidence_bank_transaction_creates_pending_review
pending_authorization_not_auto_imported
bank_import_persists_connection_account_provider_metadata
statement_import_skips_existing_bank_api_expense
api_sync_skips_existing_statement_pending_review
statement_import_has_durable_per_transaction_outcomes
bank_notes_redacted_by_privacy_policy
sync_error_does_not_include_raw_bank_description
redacted_backup_excludes_bank_tokens
```

---

# 8. AI implementation checklist

Before coding, run:

```bash
grep -R "BankApiIntegration" app/src/main/java
grep -R "@StubForDemo" app/src/main/java
grep -R "BankApiConfig" app/src/main/java
grep -R "SUPPORTED_BANKS" app/src/main/java
grep -R "BankConnectionsViewModel" app/src/main/java
grep -R "BankConnection(" app/src/main/java
grep -R "BankConnectionDao" app/src/main/java
grep -R "bankConnectionDao\." app/src/main/java
grep -R "BankTokenCipher" app/src/main/java
grep -R "decryptIfNeeded" app/src/main/java
grep -R "BANK_API_SYNC" app/src/main/java
grep -R "idempotencyKey = transaction.id" app/src/main/java
grep -R "generateMockTransactions" app/src/main/java
grep -R "BankStatementLifecycleProcessor" app/src/main/java
grep -R "rawText = ocrResult.fullText" app/src/main/java
grep -R "transferAccountName = transaction.description" app/src/main/java
grep -R "notes = transaction.description" app/src/main/java
grep -R "BankSyncRun" app/src/main/java
grep -R "BankTransactionImport" app/src/main/java
```

Allowed bank DAO mutation list should eventually be:

```text
BankConnectionLifecycleCoordinator
BankSyncCoordinator
Room migrations
debug-only repair tools
```

Definition of done:

```text
- Demo bank provider cannot run in release and UI does not imply real support.
- Bank connections are real repository-backed data, not fake Room entities.
- OAuth state/PKCE/session replay protection exists.
- BankConnection createdAt/updatedAt are never 0.
- Every sync has BankSyncRun.
- Every provider transaction has durable import outcome.
- Token refresh persists encrypted tokens or marks reauth required.
- Low-confidence/ambiguous bank transactions go to PendingReview.
- API sync and statement import share dedupe.
- Imported expenses preserve bank/account/source metadata.
- Restore mode blocks all bank writes.
- Bank token backup/restore policy is explicit.
- Raw bank descriptions/errors/debug data are privacy-sanitized.
```

---

# 9. Agent-ready priority order

Do this order:

1. **Keep demo safe + fix bank UI availability/stable-key bug.**
2. **Bank connection lifecycle coordinator/repository.**
3. **OAuth session + PKCE model.**
4. **Sync run ledger + per-transaction import item state.**
5. **Token refresh persistence + auth status.**
6. **Low-confidence review route.**
7. **Source metadata persistence for bank-created expenses.**
8. **Shared API/statement bank deduper.**
9. **Statement import run atomicity + item ledger.**
10. **Bank token backup/restore and privacy sanitization.**