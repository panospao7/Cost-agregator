# Pipeline 10 Debug Report — Bank Integration / Imports

Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`  
Mode: static GitHub/code review, not local Gradle/device execution.

## Verdict

Pipeline 10 is **not clean/stable yet**.

The core transaction insertion side is improved because bank sync maps imported transactions into `CreateExpenseRequest` and sends them through `TransactionLifecycleCoordinator`.

But the actual bank integration pipeline is still mostly **demo/stub infrastructure**, not production-ready:

```text
bank connection UI → mostly no-op
OAuth/connect → placeholder
token refresh → placeholder
sync → mock random transactions
bank connection persistence → missing lifecycle/repository orchestration
bank sync audit → missing
low-confidence review path → missing
partial sync checkpointing → missing
statement import dedupe → partial
```

Best current label: **prototype shell / demo-only, with a good lifecycle insertion direction**.

---

# Severity scale

- **P0 / Critical:** feature cannot work, creates wrong financial data, or unsafe production behavior.
- **P1 / High:** sync/import lifecycle gap, duplicate risk, token/auth state not durable, restore/privacy hole.
- **P2 / Medium:** diagnostics, UX, metadata, edge correctness.
- **P3 / Low:** cleanup/maintainability.

---

# Pipeline checklist status

| Checklist item | Status |
|---|---|
| Bank connection created | Not fully. `completeConnection()` returns a `BankConnection`, but no production repository/lifecycle persists it. UI ViewModel is no-op. |
| Auth/token failure surfaced | Partial. Expired token returns a `SyncResult` error, but connection state is not updated with durable error details. |
| Expired token does not corrupt sync | Partially safe. It returns before import if refresh fails, but refresh is stub-only and does not persist new tokens. |
| Partial sync safe | Not clean. Expenses are inserted one by one with no sync-run ledger/checkpoint/watermark. |
| Duplicate bank transaction skipped | Partial. `idempotencyKey = transaction.id` is passed to lifecycle coordinator, but strict conflict result handling remains weak from Pipeline 2. |
| Low-confidence transaction goes to review | Missing. Bank API sync imports directly as expenses; statement import creates `PendingReview`, but confidence policy is incomplete. |
| Approved transaction uses lifecycle coordinator | Mostly yes for approved review paths from Pipeline 2, and direct bank API sync uses coordinator. |
| Source/origin preserved | Partial. Source is `BANK_API_SYNC`; bank connection/account/provider metadata is not fully persisted on expense. |
| Dashboard only includes approved non-duplicates | Partial. Direct bank sync creates approved expenses immediately; statement import goes to review. Duplicate/approval contract is split. |

---

# Positive findings to preserve

## PF-01 — Bank-created expenses go through transaction lifecycle

`BankApiIntegration.mapTransactionToExpense()` creates a `CreateExpenseRequest` with:

```text
source = ExpenseSource.BANK_API_SYNC
idempotencyKey = transaction.id
transactionType
transferDirection
currency
date
merchant
notes
```

Then `syncTransactions()` calls:

```kotlin
coordinator.createExpense(request)
```

This is the right insertion boundary.

## PF-02 — External bank transaction ID is used for idempotency

The code explicitly sets:

```kotlin
idempotencyKey = transaction.id
```

This is the correct foundation for safe re-sync.

## PF-03 — Bank tokens are encrypted at rest

`BankTokenCipher` uses Android Keystore-backed AES/GCM and stores payloads as:

```text
enc:v1:<iv>:<ciphertext>
```

This is good and should remain mandatory for real tokens.

## PF-04 — Bank connection model has useful operational fields

`BankConnection` already contains:

```text
isActive
isConnected
lastSync
lastSyncStatus
autoSync
syncFrequency
lastError
lastErrorTime
consecutiveErrors
tokenExpiry
tokenEncryptionVersion
```

Good schema foundation.

## PF-05 — Statement import/review infrastructure exists elsewhere

`BankStatementLifecycleProcessor` exists and routes parsed statement transactions into `PendingReview`, which is safer than direct auto-insert for uncertain imported data.

---

# Issue P0-01 — Bank API integration is demo-only stub behavior

## Severity

P0 / Critical

## Evidence

`BankApiIntegration` is annotated with `@StubForDemo` on core methods:

```text
initiateConnection()
completeConnection()
syncTransactions()
refreshToken()
generateMockTransactions()
```

`requireStubMode()` throws when `BankApiConfig.isProduction` is true:

```kotlin
require(!BankApiConfig.isProduction) { "Bank integration not implemented" }
```

`syncTransactions()` generates random mock transactions.

## Impact

Real bank integration cannot work in production.

Worse, if stub mode is accidentally enabled in release, “sync” imports fake random expenses.

## Fixing strategy

Hard-separate demo providers from production providers.

## Implementation plan

1. Introduce provider port:

```kotlin
interface BankProvider {
    val providerId: String
    suspend fun startConnection(command: StartBankConnectionCommand): BankConnectionStartResult
    suspend fun completeConnection(callback: BankAuthCallback): BankConnectionResult
    suspend fun refreshToken(connection: BankConnection): TokenRefreshResult
    suspend fun fetchTransactions(connection: BankConnection, cursor: BankSyncCursor): BankFetchResult
}
```

2. Move current implementation to:

```text
DemoBankProvider
```

3. Add release guard:

```kotlin
if (!BuildConfig.DEBUG && BankApiConfig.isStubMode) {
    error("Demo bank provider disabled in release")
}
```

4. Hide/disable bank connection UI when no real provider is registered.

5. Tests:

```text
release_build_cannot_use_demo_bank_provider
demo_provider_never_registered_in_release
production_without_provider_shows_feature_unavailable
sync_does_not_generate_mock_transactions_in_release
```

---

# Issue P0-02 — Bank connection UI ViewModel is no-op

## Severity

P0 / Critical for feature functionality

## Evidence

`BankConnectionsViewModel` injects no repository/service.

It does:

```kotlin
_connections.value = emptyList()
```

`syncConnection()` and `disconnect()` contain only comments.

`BankConnectionsScreen` briefly hides a disconnected item locally, then calls the no-op ViewModel method.

## Impact

The Bank Connections screen cannot:

```text
load connections
sync
disconnect
persist removal
show real errors
```

User-visible feature is non-functional.

## Fixing strategy

Wire ViewModel to a real `BankConnectionRepository` / lifecycle coordinator.

## Implementation plan

1. Add repository:

```kotlin
interface BankConnectionRepository {
    fun observeConnections(): Flow<List<BankConnection>>
    suspend fun startConnection(bankId: String): BankConnectionStartResult
    suspend fun completeConnection(callback: BankAuthCallback): Result<Long>
    suspend fun syncConnection(connectionId: Long): BankSyncResult
    suspend fun disconnect(connectionId: Long): Result<Unit>
}
```

2. Inject into ViewModel.

3. Replace local hiding with real optimistic update only after repository success or rollback on failure.

4. Tests:

```text
viewmodel_loads_bank_connections_from_dao
sync_button_calls_repository_sync
disconnect_button_persists_disconnect
disconnect_failure_restores_visible_connection_and_shows_error
```

---

# Issue P1-03 — `completeConnection()` returns an entity but does not persist it and leaves `createdAt = 0`

## Severity

P1 / High

## Evidence

`completeConnection()` returns a constructed `BankConnection`.

It does not call `BankConnectionDao.insert()`.

`BankConnection.createdAt` defaults to `0L`, and the returned object does not set it.

`BankConnectionDao.insert()` is plain `@Insert`; unique `bankId` conflicts are not handled as structured outcomes.

## Impact

Connection completion is not a durable lifecycle operation.

If a caller inserts the returned object as-is, it can create:

```text
createdAt = 0
weak duplicate-bank handling
no audit event
no restore guard
```

## Fixing strategy

Connection creation must be owned by a lifecycle coordinator.

## Implementation plan

1. Add:

```kotlin
class BankConnectionLifecycleCoordinator
```

2. `completeConnection()` should return provider token/account data, not a Room entity.

3. Coordinator transaction:

```kotlin
database.withTransaction {
    check restore/write barrier
    validate bank/account/provider identity
    encrypt tokens
    upsert BankConnection
    set createdAt/updatedAt
    write BankConnectionEvent.CONNECTED
}
```

4. Add `updatedAt` to entity.

5. Tests:

```text
complete_connection_persists_connection
complete_connection_sets_createdAt_updatedAt
duplicate_bank_connection_returns_existing_or_updates_tokens
restore_mode_blocks_complete_connection
connect_writes_BANK_CONNECTED_event
```

---

# Issue P1-04 — No OAuth state/PKCE/callback validation contract

## Severity

P1 / High

## Evidence

`initiateConnection()` returns a placeholder URL:

```text
https://oauth.<bank>.example.com/auth?client_id=demo&response_type=code
```

Comments mention generating OAuth state but no durable state entity exists.

`completeConnection(bankId, authCode)` accepts only bank ID and code.

## Impact

A real OAuth integration would need protection against:

```text
CSRF
callback spoofing
state replay
wrong-bank callback
missing PKCE verifier
expired auth session
```

Current API shape cannot validate those.

## Fixing strategy

Model bank auth session explicitly.

## Implementation plan

1. Add entity:

```kotlin
BankAuthSession(
    id,
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

2. `startConnection()` returns:

```text
authorizationUrl
state
sessionId
```

3. `completeConnection(callback)` validates:

```text
state exists
not expired
not consumed
bankId/provider matches
PKCE verifier present
```

4. Mark session consumed atomically with connection creation.

5. Tests:

```text
callback_with_wrong_state_rejected
callback_replay_rejected
expired_auth_session_rejected
wrong_bank_callback_rejected
successful_callback_consumes_session_and_creates_connection
```

---

# Issue P1-05 — Sync has no durable run ledger, checkpoint, or per-transaction import state

## Severity

P1 / High

## Evidence

`syncTransactions()` returns in-memory `SyncResult`.

It does not persist:

```text
sync run started/completed
provider cursor
page token
imported/skipped/error counts
per-bank transaction import status
last successful cursor
partial sync checkpoint
```

`BankConnectionDao.updateSyncStatus()` exists, but `BankApiIntegration.syncTransactions()` does not call it.

## Impact

If sync crashes halfway:

```text
some expenses may be created
connection lastSync may not reflect partial state
retry cannot resume from provider cursor
debug cannot show which transaction failed
```

## Fixing strategy

Add a bank sync lifecycle ledger.

## Implementation plan

1. Add entities:

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
    providerTransactionId,
    fingerprint,
    status,
    expenseId,
    reviewId,
    errorCode,
    errorMessage,
    rawMetadataRedactedJson
)
```

2. Sync flow:

```text
create RUNNING sync run
fetch page
for each tx → import/review/skip with row status
after each page → persist checkpoint cursor
finish SUCCESS/PARTIAL/FAILED
update BankConnection.lastSync/lastSyncStatus only after run finalization
```

3. Tests:

```text
sync_crash_after_page_can_resume_from_checkpoint
partial_sync_records_imported_skipped_failed_counts
connection_lastSyncStatus_PARTIAL_when_some_transactions_fail
sync_run_records_each_transaction_outcome
```

---

# Issue P1-06 — Partial sync imports approved expenses directly; no low-confidence review route

## Severity

P1 / High

## Evidence

`syncTransactions()` maps every fetched transaction directly to `CreateExpenseRequest` and calls `coordinator.createExpense()`.

There is no confidence score, no categorization certainty, and no pending-review path for uncertain bank transactions.

## Impact

Ambiguous bank records can become approved dashboard expenses immediately.

Examples:

```text
unknown merchant
transfer vs purchase ambiguity
refund/reversal ambiguity
missing currency
zero amount
unsupported transaction type
duplicate uncertainty
```

## Fixing strategy

Introduce a bank import classifier/router.

## Implementation plan

1. Add:

```kotlin
data class BankTransactionClassification(
    val transactionType: TransactionType,
    val confidence: Float,
    val reasons: List<String>,
    val categoryId: Long?,
    val requiresReview: Boolean
)
```

2. Route:

```text
confidence >= autoAcceptThreshold
→ TransactionLifecycleCoordinator.createExpense()

confidence < threshold
→ PendingReview(source = BANK_API_SYNC)
```

3. Pending review metadata should include:

```text
providerTransactionId
connectionId
accountId
classificationReasons
raw description redacted
```

4. Tests:

```text
low_confidence_bank_transaction_creates_pending_review
high_confidence_bank_purchase_auto_imports
ambiguous_transfer_goes_to_review
missing_currency_goes_to_review
approved_bank_review_uses_transaction_lifecycle
```

---

# Issue P1-07 — Bank connection/account metadata is not preserved on imported expenses

## Severity

P1 / High

## Evidence

`CreateExpenseRequest` receives `source = BANK_API_SYNC` and `idempotencyKey = transaction.id`.

But it does not carry:

```text
bankConnectionId
providerId
bankId
accountId
accountName
providerTransactionId
syncRunId
raw booking date vs value date
```

Notes include description/reference, but that is not a structured origin contract.

## Impact

Debugging and dedupe are weaker:

```text
Which bank/account created this expense?
Which sync run?
Which provider transaction?
Can user disconnect one bank and identify imported rows?
Can re-sync by account be audited?
```

## Fixing strategy

Add structured source metadata to transaction lifecycle.

## Implementation plan

1. Extend `CreateExpenseRequest`:

```kotlin
val sourceExternalId: String?
val sourceAccountId: String?
val sourceConnectionId: Long?
val sourceRunId: Long?
val sourceMetadata: Map<String, String>
```

2. Persist to either:
   - expense columns, or
   - `TransactionEvent.metadata`, or
   - new `expense_source_links` table.

3. Tests:

```text
bank_import_persists_connection_id
bank_import_persists_provider_transaction_id
bank_import_transaction_event_contains_sync_run_id
```

---

# Issue P1-08 — Token refresh does not persist refreshed tokens or update connection state

## Severity

P1 / High

## Evidence

`refreshToken()` only decrypts the refresh token and returns `true`.

It does not:

```text
call provider
persist new access token
persist new refresh token
update tokenExpiry
update lastError
mark re-auth required
```

`BankConnectionDao.updateToken()` exists but is not used in the integration.

## Impact

Expired tokens cannot be truly recovered.

A sync can report success in stub mode while production behavior is undefined.

## Fixing strategy

Make token refresh a first-class provider operation and lifecycle event.

## Implementation plan

1. Provider returns:

```kotlin
sealed interface TokenRefreshResult {
    data class Refreshed(val accessToken: String, val refreshToken: String?, val expiresAt: Long)
    data class ReauthRequired(val reason: String)
    data class Failed(val retryable: Boolean, val reason: String)
}
```

2. Coordinator transaction:

```text
encrypt tokens
update BankConnection.tokenExpiry/tokenEncryptionVersion
reset consecutiveErrors on success
write TOKEN_REFRESHED event
```

3. On reauth required:

```text
isConnected = false or status = REAUTH_REQUIRED
lastError = ...
```

4. Tests:

```text
expired_token_refresh_persists_new_token
refresh_failure_does_not_import_transactions
reauth_required_marks_connection_attention_needed
refresh_failure_updates_lastError_and_consecutiveErrors
```

---

# Issue P1-09 — No restore/write barrier around bank connection and sync writes

## Severity

P1 / High

## Evidence

`BankApiIntegration` injects only:

```text
TimeProvider
TransactionLifecycleCoordinator
```

It does not inject `RestoreMaintenanceMode` or a write barrier.

`BankConnectionDao` write methods have no guard.

Expense creation itself may be guarded inside transaction lifecycle, but connection rows, token rows, and sync status rows are not.

## Impact

Bank sync/connect/disconnect can mutate state during backup/restore once real callers are wired.

## Fixing strategy

All bank write paths must route through a restore-guarded coordinator.

## Implementation plan

1. Inject `DatabaseWriteBarrier` into `BankConnectionLifecycleCoordinator`.

2. Guard:

```text
connect
complete callback
refresh token
sync run insert/update
transaction import status
disconnect
update settings
```

3. Tests:

```text
restore_blocks_bank_connect
restore_blocks_bank_token_refresh
restore_blocks_bank_sync_run
restore_blocks_bank_disconnect
```

---

# Issue P1-10 — Bank statement import duplicate policy is weaker than approved-expense dedupe

## Severity

P1 / High

## Evidence

From Pipeline 3 review: `BankStatementLifecycleProcessor` creates `PendingReview` rows and checks existing pending reviews, but does not clearly use a shared statement/bank transaction deduper against approved `Expense` rows with:

```text
merchant key
date window
amount tolerance
currency
transaction type
provider/source
```

## Impact

Importing a statement can create pending reviews for transactions already imported by bank API, notification, receipt, or manual entry.

If user approves them, duplicates can enter dashboard.

## Fixing strategy

Use one bank/statement transaction dedupe service for both API sync and statement import.

## Implementation plan

1. Add:

```kotlin
BankTransactionDeduper
```

2. Check:

```text
existing expenses
existing pending reviews
existing bank_transaction_import rows
```

3. Match by:

```text
providerTransactionId when available
merchantKey/raw merchant
amount
currency
booking/value date window
transactionType
accountId
```

4. Tests:

```text
statement_import_skips_existing_approved_expense
statement_import_skips_existing_pending_review
api_sync_skips_statement_pending_review_duplicate
notification_expense_prevents_bank_statement_duplicate_review
```

---

# Issue P1-11 — Bank import creates expenses one-by-one without sync transaction semantics

## Severity

P1 / High

## Evidence

Inside `syncTransactions()`, each transaction independently calls:

```kotlin
coordinator.createExpense(request)
```

There is no outer sync transaction, no import-row state, and no post-run reconciliation.

This avoids one bad transaction rolling back all others, but there is no durable record of partial progress.

## Impact

A partial run can be hard to reason about:

```text
transaction A imported
transaction B failed
transaction C not reached due crash
connection status unchanged
```

## Fixing strategy

Use per-transaction import rows, not one giant DB transaction.

## Implementation plan

1. Insert `BankTransactionImport(status = RECEIVED)` before creating/reviewing.

2. Update each row atomically:

```text
IMPORTED_EXPENSE
DUPLICATE_SKIPPED
PENDING_REVIEW
FAILED_VALIDATION
FAILED_DB
```

3. Sync run finalizer computes counts from import rows.

4. Tests:

```text
failed_middle_transaction_does_not_hide_successful_imports
sync_finalizer_marks_PARTIAL_when_any_import_failed
retry_only_processes_unfinished_or_retryable_import_rows
```

---

# Issue P2-12 — `BankConnection` unique index on `bankId` is too coarse

## Severity

P2 / Medium, P1 for multi-account users

## Evidence

`BankConnection` has:

```kotlin
Index(value = ["bankId"], unique = true)
```

## Impact

A user cannot link:

```text
two accounts at same bank
same bank through different provider
personal + business accounts
joint + individual accounts
```

Real open-banking integrations typically require item/account-level uniqueness.

## Fixing strategy

Model institution connection and accounts separately.

## Implementation plan

1. Split:

```text
BankInstitutionConnection
BankAccountConnection
```

2. Unique constraints:

```text
providerId + providerItemId
providerId + providerAccountId
```

3. Expenses should link to account-level source.

4. Tests:

```text
same_bank_multiple_accounts_allowed
same_provider_account_duplicate_rejected
disconnect_one_account_does_not_disconnect_whole_bank_item_unless_requested
```

---

# Issue P2-13 — Transaction type inference is fragile and locale/text dependent

## Severity

P2 / Medium

## Evidence

`inferTransactionType()` uses description substrings:

```text
refund/reversal/cashback → DEPOSIT
transfer/sent to/received from → TRANSFER
withdraw/atm → WITHDRAWAL
amount sign fallback
```

## Impact

Provider-specific fields are more reliable than text. Current inference can misclassify:

```text
card refunds
internal transfers
ATM fees
chargebacks
salary deposits
card authorizations
negative deposits from corrections
```

## Fixing strategy

Provider adapters should map native transaction codes to canonical transaction type.

## Implementation plan

1. Extend `BankTransaction`:

```kotlin
val providerTransactionType: String?
val providerTransactionCode: String?
val status: Posted/Pending
val direction: Debit/Credit
```

2. Add `BankTransactionTypeMapper` per provider.

3. Only fallback to text heuristics for CSV/statement imports without structured codes.

4. Tests:

```text
provider_card_purchase_maps_purchase
provider_refund_maps_deposit_or_refund_policy
internal_transfer_maps_transfer_with_direction
atm_withdrawal_maps_withdrawal
```

---

# Issue P2-14 — Transfer account name uses raw description fallback

## Severity

P2 / Medium

## Evidence

`mapTransactionToExpense()` has TODO:

```text
BankTransaction needs transferAccountName field
```

Current fallback:

```kotlin
transferAccountName = transaction.description.takeIf { it.isNotBlank() }
```

## Impact

A transfer can expose full raw bank description as account name and pollute UI/search/export.

It may also contain PII such as counterparty name, IBAN fragment, or reference.

## Fixing strategy

Separate transfer counterparty/account metadata from raw description.

## Implementation plan

1. Add:

```kotlin
val transferAccountName: String?
val counterpartyName: String?
val counterpartyAccountMasked: String?
```

2. Redact/store raw description according to privacy policy.

3. Tests:

```text
transfer_account_name_uses_structured_field
raw_description_not_used_as_account_name_when_privacy_redaction_enabled
```

---

# Issue P2-15 — Bank token backup/restore behavior is not defined

## Severity

P2 / Medium, P1 if backups include unusable encrypted tokens

## Evidence

Tokens are Android Keystore encrypted. Backups export/restore Room DB, but Android Keystore keys generally do not automatically travel with copied DB files.

After restore to another install/device, encrypted token payloads may be undecryptable.

## Impact

Restored bank connections can look connected but cannot refresh/sync.

## Fixing strategy

Define token restore policy.

## Implementation plan

1. On restore verification/startup, check token decryptability:

```text
if token cannot decrypt → mark connection REAUTH_REQUIRED
```

2. Add field:

```text
authStatus = CONNECTED / REAUTH_REQUIRED / DISCONNECTED
```

3. Redacted backup should exclude tokens entirely.

4. Tests:

```text
restored_undecryptable_token_marks_reauth_required
redacted_backup_removes_bank_tokens
bank_sync_blocked_when_reauth_required
```

---

# Issue P2-16 — Sync result errors may expose raw bank transaction IDs/descriptions

## Severity

P2 / Medium

## Evidence

`SyncResult.errors` includes transaction IDs and exception messages.

Future provider errors may include raw descriptions or provider response messages.

## Impact

UI/logs/debug exports can leak sensitive banking metadata.

## Fixing strategy

Use sanitized error codes and internal metadata.

## Implementation plan

1. Replace free-form strings:

```kotlin
data class BankSyncError(
    val code: BankSyncErrorCode,
    val providerTransactionIdHash: String?,
    val userMessage: String,
    val debugMessageRedacted: String?
)
```

2. Do not expose raw provider transaction IDs unless user opts into debug data.

3. Tests:

```text
sync_error_does_not_include_raw_description
sync_error_hashes_provider_transaction_id
```

---

# Recommended fixing order

## PR 1 — Disable demo bank provider in release

Files:

```text
BankApiIntegration.kt
BankApiConfig.kt
BankConnectionsScreen.kt
BuildConfig feature flag / DI module
```

Fix:

```text
- current mock sync cannot run in release
- UI shows “Bank integration unavailable” unless real provider registered
```

## PR 2 — Bank connection repository/lifecycle

Files:

```text
BankConnectionLifecycleCoordinator.kt
BankConnectionRepository.kt
BankConnectionDao.kt
BankConnection.kt
BankConnectionsViewModel.kt
```

Fix:

```text
- load connections
- connect/complete/disconnect persistently
- createdAt/updatedAt
- restore guard
- connection events
```

## PR 3 — OAuth session model

Files:

```text
BankAuthSession.kt
BankAuthSessionDao.kt
BankProvider.kt
BankConnectionLifecycleCoordinator.kt
```

Fix:

```text
- state/PKCE/session expiry/replay protection
```

## PR 4 — Sync run ledger and checkpointing

Files:

```text
BankSyncRun.kt
BankTransactionImport.kt
BankSyncCoordinator.kt
BankConnectionDao.kt
```

Fix:

```text
- durable sync status
- partial sync recovery
- per-transaction outcome rows
```

## PR 5 — Review routing and dedupe service

Files:

```text
BankTransactionClassifier.kt
BankTransactionDeduper.kt
PendingReviewDao.kt
TransactionLifecycleCoordinator.kt
BankStatementLifecycleProcessor.kt
```

Fix:

```text
- low-confidence bank tx → PendingReview
- API sync and statement import share dedupe
```

## PR 6 — Provider/account model

Files:

```text
BankConnection.kt or new BankInstitutionConnection.kt/BankAccountConnection.kt
Room migration
Expense source metadata model
```

Fix:

```text
- multiple accounts per bank
- account/provider metadata preserved
```

## PR 7 — Token refresh + restore policy

Files:

```text
BankTokenCipher.kt
BankConnectionLifecycleCoordinator.kt
Backup/restore startup checks
```

Fix:

```text
- refresh persists new tokens
- restored undecryptable tokens → reauth required
- redacted backup strips tokens
```

---

# Golden tests to add

```text
release_build_cannot_import_mock_bank_transactions
bank_viewmodel_loads_real_connections
complete_connection_sets_createdAt_and_persists_tokens_encrypted
oauth_callback_wrong_state_rejected
oauth_callback_replay_rejected
expired_token_refresh_failure_imports_zero_transactions
expired_token_refresh_success_persists_new_token
sync_run_records_started_and_completed
sync_partial_failure_records_PARTIAL_status
bank_transaction_external_id_retry_skips_duplicate
same_bank_multiple_accounts_allowed
low_confidence_bank_transaction_creates_pending_review
approved_bank_review_uses_transaction_lifecycle
statement_import_skips_existing_bank_api_expense
api_sync_skips_existing_statement_pending_review
restore_mode_blocks_connect_sync_disconnect
restored_undecryptable_bank_token_marks_reauth_required
redacted_backup_excludes_bank_tokens
```

---

# AI implementation checklist

Before coding, run:

```bash
grep -R "BankApiIntegration" app/src/main/java
grep -R "BankConnectionDao" app/src/main/java
grep -R "BankConnection(" app/src/main/java
grep -R "BANK_API_SYNC" app/src/main/java
grep -R "BankStatementLifecycleProcessor" app/src/main/java
grep -R "idempotencyKey = transaction.id" app/src/main/java
grep -R "@StubForDemo" app/src/main/java
```

Definition of done:

```text
- Demo bank sync cannot run in release.
- Bank connections are loaded/synced/disconnected through a real repository.
- OAuth state/PKCE/session replay protection exists.
- BankConnection createdAt/updatedAt are never 0.
- Every sync has a durable BankSyncRun.
- Every imported provider transaction has a durable outcome.
- Token refresh persists new encrypted tokens or marks reauth required.
- Low-confidence/ambiguous bank transactions go to PendingReview.
- API sync and statement import share a dedupe service.
- Imported expenses preserve structured bank/account/source metadata.
- Restore mode blocks all bank writes.
- Backup/restore has a clear bank-token policy.
```

---

# Source files inspected

- Commit baseline:  
  https://github.com/panospao7/Cost-agregator/commit/71fbbf9aed221a7446f99967b49b6e9ebeb51946

- `BankApiIntegration.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt

- `BankApiConfig.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiConfig.kt

- `StubForDemo.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/bank/StubForDemo.kt

- `BankConnection.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/BankConnection.kt

- `BankConnectionDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/BankConnectionDao.kt

- `BankTokenCipher.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/security/BankTokenCipher.kt

- `BankConnectionsViewModel.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/ui/screens/bank/BankConnectionsViewModel.kt

- `BankConnectionsScreen.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/ui/screens/bank/BankConnectionsScreen.kt

- `BankStatementLifecycleProcessor.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt