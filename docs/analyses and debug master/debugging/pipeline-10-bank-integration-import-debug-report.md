# Pipeline 10 Debugging Report — Bank Integration / Bank Sync / Bank Statement Imports

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static GitHub code review, not local/device execution.

## 1. Executive summary

Pipeline 10 is intended to be:

```text
BankConnectionsScreen
→ OAuth / bank connection
→ encrypted token storage
→ scheduled/manual bank sync
→ bank transaction normalization
→ dedupe / idempotency
→ TransactionLifecycleCoordinator
→ Expense / TransactionEvent
→ dashboard / analytics / budget

Bank statement screenshot/PDF
→ OCR
→ BankStatementParser
→ AI validation
→ PendingReview
→ review approval
→ TransactionLifecycleCoordinator
→ dashboard / analytics
```

The current implementation is mostly **stubbed and not production-ready**.

Strong pieces:

- `BankConnection` entity exists.
- `BankConnectionDao` exists.
- Android Keystore-backed `BankTokenCipher` exists.
- `BankApiIntegration` routes expense creation through `TransactionLifecycleCoordinator`.
- Bank statement parser is substantial.
- `BankStatementLifecycleProcessor` exists and avoids warranty/receipt side effects for statement imports.
- Some focused tests exist:
  - `BankApiIntegrationTest`
  - `BankConnectionDaoTest`
  - `BankStatementParserTest`
  - `ValidateBankStatementTransactionsUseCaseTest`

Highest-risk findings:

1. **Bank API integration is demo-only stub code.**
2. **BankConnectionsViewModel is disconnected from DAO/repository/integration; sync/disconnect are no-ops.**
3. **Bank API debit transactions are mapped as negative amounts, but `TransactionLifecycleCoordinator` rejects non-positive amounts.**
4. **Bank transfers can fail validation because `transferAccountName` is not provided.**
5. **Bank transaction IDs are not used as idempotency keys, so re-sync safety is weak.**
6. **Sync status, token refresh, last error, and last sync are not persisted.**
7. **Bank statement lifecycle processor duplicate logic is weaker than the older `ReceiptRepository.processStatement()` path.**
8. **Bank statement import is not atomic as a batch.**
9. **Cloud AI validation for bank statements can include raw OCR text and needs the Pipeline 8 redaction/CloudAiGuard fixes.**
10. **There is no bank sync worker/scheduler despite `autoSync` and `syncFrequency` fields.**

Main recommendation:

> Treat bank integration as not yet live. Stabilize it behind a feature flag, then build a real bank connection repository + provider abstraction + idempotent sync ledger before enabling it for production users.

---

# 2. Intended architecture contract

A safe bank integration pipeline should have these layers:

```text
UI
→ BankConnectionRepository
→ BankProviderRegistry
→ OAuth/connection flow
→ BankTokenStore
→ BankSyncCoordinator
→ Provider transaction mapper
→ BankImportLedger / external transaction ID table
→ TransactionLifecycleCoordinator
→ Expense / TransactionEvent
→ BankConnectionDao sync status
→ Dashboard / Analytics / Budget
```

For statement imports:

```text
URI/PDF/image
→ OCR/text extraction
→ BankStatementParser
→ optional privacy-safe AI validation
→ BankStatementLifecycleProcessor
→ PendingReview rows
→ ReviewQueueRepository.approveReview()
→ TransactionLifecycleCoordinator
```

Current code only partially implements this.

---

# 3. Actual code path summary

## 3.1 Bank API config

`BankApiConfig` has:

```kotlin
var isStubMode: Boolean = true
val isProduction: Boolean get() = !isStubMode
```

`BankApiIntegration` methods are marked `@StubForDemo` and call:

```kotlin
requireStubMode()
```

That means:

```text
stub mode true → fake/demo code runs
stub mode false → throws "Bank integration not implemented"
```

So there is no real production bank API implementation yet.

Sources:

- `BankApiConfig.kt`
- `StubForDemo.kt`
- `BankApiIntegration.kt`

---

## 3.2 Bank connections UI

`BankConnectionsViewModel` has no injected dependencies.

It does:

```kotlin
_connections.value = emptyList()
```

and:

```kotlin
syncConnection(connectionId) {
    // Would trigger sync
}

disconnect(connectionId) {
    // Would disconnect
}
```

So the UI screen is currently presentation-only. It cannot load, sync, disconnect, or persist bank connections.

`BankConnectionsScreen` includes UI for sync/disconnect, but those actions do not reach the database.

Sources:

- `BankConnectionsViewModel.kt`
- `BankConnectionsScreen.kt`

---

## 3.3 Bank API sync

`BankApiIntegration.syncTransactions(connection, since)`:

```text
check token expiry
refresh token if expired
generate mock transactions
for each transaction:
  map to CreateExpenseRequest
  coordinator.createExpense(request)
return SyncResult
```

It does not:

- update `BankConnection.lastSync`,
- update `lastSyncStatus`,
- update `lastError`,
- update `consecutiveErrors`,
- persist refreshed tokens,
- record a bank import ledger row,
- use transaction IDs as idempotency keys,
- use a BankConnectionDao,
- check network constraints,
- check restore mode itself,
- check privacy/banking capability,
- schedule auto-sync.

It relies entirely on `TransactionLifecycleCoordinator` for persistence.

---

## 3.4 Bank statement import path

There are two statement-ish paths:

### Older `ReceiptRepository.processStatement()`

This:

```text
OCR
→ BankStatementParser
→ saves ScannedReceipt
→ creates PendingReview rows
→ has expense duplicate checks
→ has transactional pending-review insert section
```

### Newer `BankStatementLifecycleProcessor.processBankStatement()`

This:

```text
OCR via ReceiptRepository.runStatementOcr()
→ exact image-hash duplicate check
→ parse transactions
→ AI validation
→ save BANK_STATEMENT ScannedReceipt
→ ReceiptEvent.RECEIPT_SAVED
→ create PendingReview for each transaction
→ ReceiptEvent.PROCESSING_COMPLETE
```

The newer lifecycle path is cleaner conceptually, but its duplicate and atomicity behavior is weaker in some ways.

---

# 4. Major findings

## Finding P0-1 — Bank API integration is stub-only

`BankApiIntegration` is not real bank integration.

Evidence:

- `initiateConnection()` returns a fake OAuth URL.
- `completeConnection()` returns demo tokens.
- `syncTransactions()` uses `generateMockTransactions()`.
- `refreshToken()` only decrypts the demo refresh token and returns true.
- `requireStubMode()` throws when `BankApiConfig.isProduction == true`.

So if you flip out of stub mode, bank integration stops working. If you leave stub mode on, it can create fake transactions in the user’s real database.

### Why this matters

Symptoms:

```text
Connect bank appears to work but is fake.
Sync appears to work but imports random demo data or fails validation.
Production mode crashes with "Bank integration not implemented".
```

### Recommendation

Make bank integration explicitly unavailable in production UI until real providers exist.

Add:

```kotlin
sealed interface BankIntegrationMode {
    data object Disabled : BankIntegrationMode
    data object DemoSandbox : BankIntegrationMode
    data object Production : BankIntegrationMode
}
```

Then:

```text
Disabled → hide connect/sync or show "coming soon"
DemoSandbox → use isolated demo DB or explicit fake-data confirmation
Production → real provider only
```

Do not let stub sync write fake transactions into a normal user ledger.

Priority: highest.

---

## Finding P0-2 — BankConnectionsViewModel is a no-op

`BankConnectionsViewModel` currently:

- has no `BankConnectionDao`,
- has no repository,
- has no `BankApiIntegration`,
- always loads an empty list,
- does not sync,
- does not disconnect.

So even though `BankConnectionDao` and screen UI exist, the screen is not connected.

### Recommendation

Create:

```kotlin
BankConnectionRepository
```

Responsibilities:

```text
observe connections
insert completed connection
disconnect connection
sync connection
sync all due connections
update sync status
update tokens
record errors
```

Then inject it into `BankConnectionsViewModel`.

ViewModel state should include:

```text
connections
isLoading
syncingConnectionIds
lastActionError
connectFlowUrl
reauthRequiredConnectionId
```

Priority: highest.

---

## Finding P0-3 — Bank API debit imports fail because amounts are negative

`BankApiIntegration.generateMockTransactions()` creates purchases like:

```kotlin
amount = -(10..200).random().toDouble()
movementType = PURCHASE
```

`mapTransactionToExpense()` passes this amount directly into:

```kotlin
CreateExpenseRequest(amount = transaction.amount)
```

But `TransactionLifecycleCoordinator.validate()` requires:

```text
amount > 0
```

Therefore stub bank purchases are rejected as:

```text
ValidationFailed("Amount must be positive and finite")
```

This is a concrete bug.

The existing `BankApiIntegrationTest` actually asserts that debit mapping keeps the negative amount, but it only tests the private mapper through reflection. It does not call `coordinator.createExpense()`, so it misses the runtime failure.

### Recommendation

Normalize bank signs before lifecycle creation.

Preferred contract:

```text
Expense.amount is always positive.
TransactionType expresses semantic direction.
TransferDirection expresses transfer direction.
```

Mapping:

```kotlin
val normalizedAmount = abs(transaction.amount)

transactionType:
  negative debit/card payment → PURCHASE
  negative ATM → WITHDRAWAL
  positive salary/refund → DEPOSIT
  transfer → TRANSFER + direction
```

Then:

```kotlin
CreateExpenseRequest(
    amount = normalizedAmount,
    transactionType = transactionType
)
```

Add a test that calls `syncTransactions()` with a fake coordinator or real DB-backed coordinator and proves purchases are created.

Priority: highest.

---

## Finding P0-4 — Bank transfers can fail validation

`TransactionLifecycleCoordinator.validate()` requires for `TRANSFER`:

```text
transferDirection != null
transferAccountName not blank
```

`BankApiIntegration.mapTransactionToExpense()` sets:

```kotlin
transferDirection = transaction.transferDirection.takeIf { transactionType == TRANSFER }
```

but never sets:

```text
transferAccountName
```

Also `inferTransactionType()` can classify description text as transfer even when `transferDirection` is null.

So transfer imports can fail validation.

### Recommendation

For bank transfers:

```kotlin
transferDirection = providerTransferDirection ?: inferDirection(...)
transferAccountName = counterparty/account name/reference-derived label
```

If direction/name cannot be determined:

```text
route to PendingReview
do not auto-create expense
```

Priority: highest.

---

## Finding P0-5 — Bank transaction IDs are not used for idempotency

`BankTransaction` has:

```text
id
reference
```

But `CreateExpenseRequest` is built without:

```text
idempotencyKey
externalFingerprint
deduplicationMode = STRICT_EXTERNAL_ID
```

So bank sync uses standard fuzzy dedupe only.

This is unsafe for bank sync.

### Why this matters

Re-sync cases:

```text
same transaction appears again from API
provider sends same transaction with corrected merchant/date
pending partial sync retry
pagination overlap
manual sync after auto sync
```

Without external ID idempotency, the app can:

- duplicate a bank transaction,
- incorrectly skip a legitimate repeated transaction,
- lose traceability to source bank transaction,
- fail to distinguish "same external transaction" from "same merchant/amount/date".

### Recommendation

Use strict idempotency:

```kotlin
CreateExpenseRequest(
    ...
    source = ExpenseSource.BANK_API_SYNC,
    deduplicationMode = DeduplicationMode.STRICT_EXTERNAL_ID,
    idempotencyKey = "${connection.bankId}:${connection.id}:${transaction.id}",
    externalFingerprint = stableHash(provider, accountId, transaction.id)
)
```

Also add a bank import ledger:

```text
bank_imported_transactions
  id
  bankConnectionId
  providerTransactionId
  accountExternalIdHash
  expenseId
  status
  firstSeenAt
  lastSeenAt
  rawPayloadHash
```

Priority: highest.

---

## Finding P0-6 — Sync status and token refresh are not persisted

`BankConnection` has useful fields:

```text
lastSync
lastSyncStatus
lastError
lastErrorTime
consecutiveErrors
tokenExpiry
accessToken
refreshToken
tokenEncryptionVersion
```

`BankConnectionDao` has:

```text
updateSyncStatus()
updateToken()
disconnect()
```

But `BankApiIntegration` does not inject `BankConnectionDao` and cannot update any of those fields.

`refreshToken()` returns true but does not store a new access token, refresh token, expiry, or encryption version.

### Recommendation

Move sync orchestration to:

```kotlin
BankSyncCoordinator
```

It should:

```text
load connection
check due
refresh token if needed
persist token update
fetch transactions
import transactions
persist lastSyncStatus:
  SUCCESS
  PARTIAL
  FAILED
persist lastError/consecutiveErrors
```

`SyncResult` should map to DAO state.

Priority: highest.

---

## Finding P1-1 — `BankConnection.bankId` unique index prevents multiple accounts per bank

`BankConnection` has a unique index on:

```text
bankId
```

That means a user cannot connect:

```text
Eurobank checking
Eurobank savings
Eurobank credit card
```

as separate connections.

Real open-banking integrations usually distinguish:

```text
provider/bank
institution
account ID
IBAN/account number
card/account type
consent ID
```

### Recommendation

Replace unique `bankId` with:

```text
unique(providerId, externalAccountIdHash)
```

Add fields:

```kotlin
providerId
institutionId
accountDisplayName
accountType
externalAccountIdHash
ibanLast4
consentIdHash
```

Do not store raw IBAN unless explicitly needed; prefer hashed + last4.

Priority: high.

---

## Finding P1-2 — Token encryption is static and hard to test/migrate

`BankTokenCipher` is a static object that directly uses Android Keystore.

Good:

- AES/GCM,
- Android Keystore,
- random IV,
- encrypted marker prefix.

Risks:

- not injected,
- hard to test with fake keystore,
- no token rotation policy,
- no migration path beyond `tokenEncryptionVersion`,
- decryption failure returns null silently,
- domain `BankApiIntegration` directly calls data-security object,
- not integrated with `SecureKeyStorage`.

### Recommendation

Create injectable:

```kotlin
interface BankTokenStore {
    suspend fun encryptToken(raw: String): EncryptedToken
    suspend fun decryptToken(payload: String): TokenDecryptResult
    suspend fun rotateIfNeeded(connection: BankConnection): BankConnection
}
```

Return explicit failures:

```text
Missing
Malformed
KeystoreUnavailable
AuthenticationRequired
DecryptionFailed
```

Then bank sync can set:

```text
lastSyncStatus = FAILED
lastError = "Token decryption failed; reconnect required"
```

Priority: high.

Sources for standard practice:

- Android Keystore / `KeyGenParameterSpec` docs show AES/GCM support.
- Android cryptography docs recommend Android Keystore for stronger key security.
- RFC 8252 and RFC 9700 define OAuth native app / PKCE and refresh-token security expectations.

---

## Finding P1-3 — OAuth placeholder lacks native-app security requirements

`initiateConnection()` returns a fake URL:

```text
https://oauth.{bank}.example.com/auth?client_id=demo&response_type=code
```

For real implementation, this is missing:

```text
PKCE code_challenge
state
nonce if OIDC
redirect URI
scope
consent/account selection
external user agent / browser
exact redirect validation
code_verifier storage
state validation
error callback handling
```

### Recommendation

Real flow should be:

```text
generate state + code_verifier
store pending OAuth session
open external browser/custom tab
receive app link/deep link callback
validate state
exchange auth code + code_verifier
store encrypted tokens
fetch account metadata
insert BankConnection
```

Use PKCE and do not embed client secrets in the app.

Priority: high before production.

Sources:

- RFC 8252 — OAuth 2.0 for Native Apps
- RFC 9700 — OAuth 2.0 Security Best Current Practice

---

## Finding P1-4 — `SyncResult.success` is too coarse

`SyncResult` has:

```kotlin
success = errors.isEmpty()
importedCount
skippedCount
errorCount
errors
```

This loses states like:

```text
auth failed
reauth required
network retryable
provider throttled
partial page imported
no new transactions
idempotent replay
restore blocked
privacy blocked
```

### Recommendation

Use sealed outcomes:

```kotlin
sealed interface BankSyncOutcome {
    data class Success(imported, skipped, unchanged)
    data class Partial(imported, skipped, errors)
    data class ReauthRequired(reason)
    data class RetryableFailure(reason, retryAfter?)
    data class PermanentFailure(reason)
    data class BlockedRestoreMode
    data class Disabled
}
```

Then persist this to `BankConnection.lastSyncStatus` and diagnostics.

Priority: high.

---

## Finding P1-5 — No restore/write guard in bank sync

`TransactionLifecycleCoordinator` blocks final expense writes during restore, which is good.

But bank sync itself can still:

- refresh tokens,
- update bank connection rows once implemented,
- write import ledger rows once implemented,
- emit diagnostics,
- call providers.

Currently it does not check `RestoreMaintenanceMode`.

### Recommendation

Bank sync coordinator should start with:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed()) {
    return BankSyncOutcome.BlockedRestoreMode
}
```

Also avoid external calls during restore.

Priority: high.

---

## Finding P1-6 — No bank auto-sync worker despite `autoSync` and `syncFrequency`

`BankConnection` has:

```text
autoSync
syncFrequency
lastSync
shouldSync(connection)
```

But there is no visible:

```text
BankSyncWorker
WorkerSpec for bank sync
startup scheduler for bank sync
BackgroundJobRun for bank sync
```

So auto-sync is currently just a field, not behavior.

### Recommendation

Add:

```text
BankSyncWorker
WorkerSpec.DEFAULTS["bank_sync"]
BankSyncCoordinator.syncAllDueConnections()
BackgroundJobRun logging
network constraints
battery policy
restore guard
auth failure handling
```

Priority: high.

---

## Finding P1-7 — Bank statement lifecycle processor duplicate logic is weak

`BankStatementLifecycleProcessor` duplicate handling:

```text
exact image hash duplicate check
pending review duplicate by merchant + amount + currency
```

It does not clearly check:

```text
existing expense duplicate
date window
transaction type
merchant key range
external source fingerprint
same statement imported twice with slightly different image hash
same transaction already approved from notification/bank sync
```

The older `ReceiptRepository.processStatement()` path has stronger range-based duplicate checks against expenses and pending reviews.

### Symptoms

- statement import can create pending reviews for transactions already imported from notifications,
- statement import can skip legitimate repeated same-merchant/same-amount transactions because date is ignored in pending duplicate check,
- duplicate handling differs depending on which statement path is used.

### Recommendation

Move statement transaction dedupe into a shared service:

```kotlin
BankTransactionCandidateDeduper
```

Use same policy for:

- bank API sync,
- bank statement import,
- notification import,
- review queue.

It should check:

```text
external id / statement fingerprint if available
existing expenses by merchant/date/amount/currency/type
pending reviews by merchant/date/amount/currency/type
source priority rules
```

Priority: high.

---

## Finding P1-8 — Bank statement import is not atomic as a batch

`BankStatementLifecycleProcessor.processBankStatement()`:

```text
insert ScannedReceipt
insert RECEIPT_SAVED event
loop transactions:
  insert PendingReview
insert PROCESSING_COMPLETE event
```

There is no clear `database.withTransaction` around the whole batch or around receipt + events + reviews.

If transaction 7 of 20 fails, the app can leave:

```text
receipt saved
some pending reviews inserted
some not inserted
PROCESSING_COMPLETE maybe still inserted
```

That may be acceptable if reported as partial, but current result mainly returns counts/logs.

### Recommendation

Choose a contract:

### Option A — all-or-nothing import

One transaction:

```text
receipt + events + all reviews commit together
```

If any required row fails, rollback.

### Option B — explicit partial import

Use per-row outcomes and a batch status:

```text
PROCESSING_PARTIAL
PROCESSING_COMPLETE
PROCESSING_FAILED
```

Store metadata:

```text
transactionsFound
reviewsCreated
duplicatesSkipped
errors
```

Also write one event per candidate:

```text
STATEMENT_TRANSACTION_REVIEW_CREATED
STATEMENT_TRANSACTION_DUPLICATE_SKIPPED
STATEMENT_TRANSACTION_FAILED
```

Priority: high.

---

## Finding P1-9 — AI statement validation builds prompt with raw OCR before privacy/redaction policy

`ValidateBankStatementTransactionsUseCase` builds a prompt containing raw OCR text and candidate transactions.

It tries on-device first, then checks:

```text
PrivacyCapability.CLOUD_AI_BANK_STATEMENT
```

before cloud fallback.

Good:

- cloud is gated.

Risks:

- prompt is built before the gate,
- cloud path uses `SmartReceiptAssistService.suggestFromText(prompt)`,
- raw OCR text can include IBAN/account/card details,
- redaction is not clearly applied before cloud call,
- on-device AI service receives prompt with full raw statement; that is local, but still may persist artifacts depending implementation.

### Recommendation

Use Pipeline 8 `CloudAiGuard` and `CloudPayloadRedactor`.

For cloud:

```text
redact IBAN/account/card numbers
redact names/addresses
hash merchant/counterparty if configured
include payload hash, not raw payload, in audit
```

For on-device:

```text
do not persist raw prompt in AI artifacts unless user allows raw retention
```

Priority: high.

---

## Finding P2-1 — Demo sync is nondeterministic

`generateMockTransactions()` uses random:

```text
count
merchant
amount
reference
```

That makes demo behavior and tests non-repeatable.

### Recommendation

Use deterministic fixture data:

```kotlin
DemoBankFixture.generate(bankId, since, seed = connection.id)
```

Or disable demo importing into real DB entirely.

Priority: medium.

---

## Finding P2-2 — BankConnection `createdAt` defaults to 0

`BankConnection` says:

```text
createdAt must be set to timeProvider.now()
0L = unset sentinel
```

`completeConnection()` returns a `BankConnection` without setting `createdAt`.

`BankConnectionDao.getAllConnections()` orders by `createdAt DESC`, so rows with `0L` sort poorly.

### Recommendation

Set:

```kotlin
createdAt = timeProvider.now()
```

Priority: medium.

---

## Finding P2-3 — BankConnectionDao cannot persist detailed error state

`BankConnectionDao.updateSyncStatus()` updates only:

```text
lastSync
lastSyncStatus
```

It does not update:

```text
lastError
lastErrorTime
consecutiveErrors
```

### Recommendation

Add:

```kotlin
markSyncSuccess(id, timestamp)
markSyncPartial(id, timestamp, errorSummary)
markSyncFailed(id, timestamp, error, incrementConsecutiveErrors)
markReauthRequired(id, timestamp, reason)
```

Priority: medium-high.

---

# 5. Debugging checklist for Pipeline 10

## Bank connection UI

Check:

- [ ] screen loads real connections,
- [ ] connect button starts real/sandbox flow,
- [ ] sync button calls repository,
- [ ] disconnect wipes tokens,
- [ ] remove deletes row if disconnected,
- [ ] auth failure shown,
- [ ] re-auth required shown,
- [ ] last sync status/error visible,
- [ ] multiple accounts per bank supported or explicitly not supported.

## OAuth / token storage

Check:

- [ ] PKCE code verifier/challenge,
- [ ] state stored and validated,
- [ ] external browser/custom tab,
- [ ] exact redirect URI validation,
- [ ] code exchange errors handled,
- [ ] tokens encrypted,
- [ ] refresh token rotation supported,
- [ ] decryption failure means reconnect required,
- [ ] token expiry persisted,
- [ ] token refresh persists updated token.

## Bank sync

Check:

- [ ] restore mode blocks sync,
- [ ] network constraints applied,
- [ ] autoSync works,
- [ ] manual sync works,
- [ ] expired token refresh works,
- [ ] refresh failure marks reauth required,
- [ ] partial API failure is partial, not silent success,
- [ ] provider pagination does not duplicate,
- [ ] provider transaction ID used for idempotency,
- [ ] repeated sync is idempotent,
- [ ] no fake data in production DB.

## Transaction mapping

Check:

- [ ] debits normalized to positive amount + PURCHASE/WITHDRAWAL,
- [ ] credits normalized to positive amount + DEPOSIT,
- [ ] refunds represented according to app policy,
- [ ] transfers have direction and account name,
- [ ] currency uppercase/valid,
- [ ] merchant nonblank,
- [ ] date not future,
- [ ] reference/external ID stored,
- [ ] category default applied,
- [ ] source is `BANK_API_SYNC`.

## Bank statement import

Check:

- [ ] OCR/PDF extraction works,
- [ ] bank statement document type set,
- [ ] no warranty/price/item side effects,
- [ ] duplicate statement hash detected,
- [ ] duplicate transaction vs expenses checked,
- [ ] duplicate transaction vs pending reviews checked,
- [ ] date/type/currency included in duplicate check,
- [ ] batch atomicity/partial contract explicit,
- [ ] per-candidate events/logs written,
- [ ] review approval creates expenses via lifecycle,
- [ ] dashboard/analytics include only approved non-duplicates.

---

# 6. Recommended fix plan

## PR 1 — Disable or isolate bank demo mode

- Hide real bank connection UI unless feature flag enabled.
- Demo mode must not write fake data into the real ledger.
- Make `BankApiConfig` build-variant or DI-driven, not mutable global state.

Acceptance:

```text
Production users cannot accidentally import random demo bank transactions.
```

---

## PR 2 — Build BankConnectionRepository

Add:

```kotlin
BankConnectionRepository
```

Methods:

```text
observeConnections()
startConnection(bankId)
completeConnection(callback)
disconnect(id)
syncNow(id)
syncAllDue()
```

Wire `BankConnectionsViewModel` to it.

Acceptance:

```text
BankConnectionsScreen displays real rows and sync/disconnect actions persist.
```

---

## PR 3 — Fix bank transaction amount/type normalization

Normalize signed provider amounts into the app contract:

```text
Expense.amount > 0
TransactionType expresses semantic type
TransferDirection expresses transfer direction
```

Acceptance:

```text
negative bank card debit imports as positive PURCHASE expense.
```

---

## PR 4 — Add strict external idempotency

Use:

```text
DeduplicationMode.STRICT_EXTERNAL_ID
idempotencyKey = provider/account/transactionId
```

Add import ledger.

Acceptance:

```text
same bank transaction synced twice creates one expense and returns AlreadyImported/DuplicateSkipped with existing ID.
```

---

## PR 5 — Persist sync status/token refresh

Bank sync coordinator must update:

```text
lastSync
lastSyncStatus
lastError
lastErrorTime
consecutiveErrors
tokenExpiry
accessToken
refreshToken
```

Acceptance:

```text
auth failure and partial sync are visible in BankConnectionsScreen.
```

---

## PR 6 — Add BankSyncWorker

Use:

```text
WorkerSpec bank_sync
network constraints
restore guard
BackgroundJobRun
privacy/banking capability if added
```

Acceptance:

```text
autoSync + syncFrequency actually run.
```

---

## PR 7 — Unify bank statement dedupe

Create shared deduper for:

```text
bank statement
bank API
notification
review queue
```

Acceptance:

```text
statement import skips transactions already imported from notifications/bank API.
```

---

## PR 8 — Make statement processing atomic or explicitly partial

Use one of:

```text
all-or-nothing transaction
explicit partial status + per-row outcome events
```

Acceptance:

```text
mid-batch failure does not leave misleading PROCESSING_COMPLETE state.
```

---

# 7. Tests to add

## `BankApiSyncDbContractTest`

Seed:

```text
BankConnection connected
mock provider transactions:
  debit card purchase -24.50
  salary credit +1250
  transfer out -80
```

Assert:

```text
purchase imports as amount 24.50, type PURCHASE
salary imports as amount 1250, type DEPOSIT
transfer has direction + account name or goes to review
TransactionEvent.CREATED exists
BankConnection.lastSyncStatus updated
```

---

## `BankApiIdempotencyTest`

Run sync twice with same provider transaction IDs.

Assert:

```text
expense count unchanged after second sync
existing expense ID returned
duplicate/idempotent event logged
BankImportLedger records same provider transaction once
```

---

## `BankApiAuthFailureTest`

Cases:

```text
expired token + refresh success → token updated + sync proceeds
expired token + refresh failure → REAUTH_REQUIRED + no import
token decrypt failure → REAUTH_REQUIRED
network timeout → RETRYABLE_FAILURE
```

---

## `BankConnectionsViewModelContractTest`

Assert:

```text
loads DAO/repository connections
syncConnection updates per-row loading state
disconnect wipes credentials
errors surface to UI
```

---

## `BankStatementLifecycleDbContractTest`

Seed:

```text
existing expense from notification
statement OCR transaction same merchant/amount/date/currency
```

Assert:

```text
statement receipt saved
duplicate transaction skipped
no duplicate pending review
receipt events include duplicate/complete metadata
```

---

## `BankStatementImportPartialFailureTest`

Simulate:

```text
10 parsed rows
row 5 insert fails
```

Assert chosen contract:

```text
all rollback
```

or:

```text
partial state + PROCESSING_PARTIAL + error metadata
```

---

## `BankStatementAiPrivacyRedactionTest`

Set:

```text
cloud bank statement AI enabled
redactBeforeCloud = true
```

Input raw OCR containing:

```text
IBAN
card/account numbers
names
```

Assert:

```text
cloud prompt contains no raw IBAN/card/account values
audit records redactionApplied
```

---

# 8. Suggested canonical scenario

## `bank_sync_failure_recovery_lifecycle`

Seed:

```text
BankConnection:
  provider = revolut
  account = main EUR account
  connected = true
  token valid
  defaultCategory = groceries

Existing expense:
  merchant = SKLAVENITIS
  amount = 45.50 EUR
  date = 2026-05-01
  source = NOTIFICATION_AUTO_ACCEPT
```

Provider returns:

```text
tx_1: SKLAVENITIS -45.50 EUR same date as existing notification
tx_2: AMAZON -29.99 EUR new purchase
tx_3: SALARY +1250 EUR deposit
tx_4: TRANSFER TO SAVINGS -100 EUR transfer
```

Expected:

```text
tx_1 skipped as duplicate of existing expense
tx_2 creates PURCHASE expense via TransactionLifecycleCoordinator
tx_3 creates DEPOSIT transaction or income record according to app policy
tx_4 creates TRANSFER only if direction/account name resolved, otherwise pending review
TransactionEvent.CREATED for created rows
duplicate/idempotent event for skipped tx_1
BankConnection.lastSyncStatus = PARTIAL or SUCCESS according to transfer outcome
lastSync updated
dashboard monthly spend includes only approved purchases
analytics source includes BANK_API_SYNC
budget includes purchase only
second sync imports zero new rows
```

---

# 9. Most likely real instability sources

Ranked:

1. **Bank API is stub-only but UI suggests a real feature.**
2. **Negative debit amounts fail lifecycle validation.**
3. **Transfers fail because required transfer fields are missing.**
4. **No external transaction ID idempotency.**
5. **Sync status/token refresh not persisted.**
6. **BankConnectionsViewModel is no-op.**
7. **No BankSyncWorker despite autoSync/syncFrequency fields.**
8. **Statement duplicate logic weaker than needed.**
9. **Statement import not clearly atomic/partial.**
10. **Raw bank statement OCR can flow into AI prompt without central redaction guarantee.**

---

# 10. Final recommendation

Stabilize Pipeline 10 in this order:

```text
1. Disable/isolate demo bank sync from real user ledger.
2. Wire BankConnectionsViewModel to a real BankConnectionRepository.
3. Fix amount/type/transfer mapping before any sync reaches lifecycle.
4. Use STRICT_EXTERNAL_ID idempotency and add a bank import ledger.
5. Persist token refresh and sync status.
6. Add BankSyncWorker with restore/network/job logging.
7. Strengthen bank statement duplicate detection.
8. Make bank statement import atomic or explicitly partial.
9. Add bank_sync_failure_recovery_lifecycle scenario test.
```

Guiding rule:

> No bank-origin transaction should become an expense unless it has a stable source identity, normalized positive amount, correct transaction type, and lifecycle event.

Second guiding rule:

> Bank integration should be either clearly disabled, clearly demo-only, or genuinely production-safe — never half-real.

---

# 11. Verification & Fix Log (2026-05-06)

## Finding P0-1 — Bank API integration is demo-only stub code
**STATUS: CONFIRMED — NOT FIXED (by design — stub behind feature flag)**

## Finding P0-2 — BankConnectionsViewModel sync/disconnect are no-ops
**STATUS: CONFIRMED — NOT FIXED (stub mode)**

## Finding P0-3 — Bank debit transactions mapped as negative amounts
**STATUS: CONFIRMED — FIXED**
- `BankApiIntegration.mapTransactionToExpense()` now uses `kotlin.math.abs(transaction.amount)` when creating the `CreateExpenseRequest`.
- Bank APIs typically represent debits as negative amounts, but `TransactionLifecycleCoordinator` requires positive amounts. The transaction type (PURCHASE/DEPOSIT/etc.) already carries the debit/credit semantics.

## Finding P0-4 — Bank transfers fail validation for missing transferAccountName
**STATUS: PARTIALLY FIXED (2026-05-06)**: Added TODO for BankTransaction.transferAccountName
field. As fallback, transaction.description is passed as transferAccountName since bank
descriptions often contain the target account name for transfers.
Full fix requires BankTransaction data model extension.

## Finding P0-5 — Bank transaction IDs not used as idempotency keys
**STATUS: FIXED (2026-05-06)**: BankApiIntegration.mapTransactionToExpense() now passes
transaction.id as idempotencyKey in CreateExpenseRequest. Bank transaction external IDs
now prevent duplicates on re-sync via STRICT_EXTERNAL_ID dedup mode.

## Finding P1-1 through P1-5
**STATUS: CONFIRMED — NOT FIXED (stub-mode features, not production-ready)**

---

# 12. New issues discovered

**NEW-1 — BankStatementLifecycleProcessor missing RestoreMaintenanceMode guard**
- `processBankStatement()` had no restore guard, meaning bank statement imports could write to DB during restore.
- **FIXED:** Added `restoreMaintenanceMode.isWritesAllowed()` check at the start of `processBankStatement()`.

---

# 13. Applied fixes summary

| Fix | File(s) | Finding |
|-----|---------|---------|
| Use abs(amount) for bank transaction amounts | `BankApiIntegration.kt` | P0-3 |
| Add restore guard to BankStatementLifecycleProcessor | `BankStatementLifecycleProcessor.kt` | NEW-1 |

---

# 14. Remaining work priority

1. **P0-4**: Support transferAccountName in bank transfer mapping
2. ~~**P0-5**: Use bank transaction external IDs as dedup keys~~ ✅ DONE
3. All remaining P1+ issues deferred until bank API exits stub mode

---

# Sources

Repository sources:

- Dependency map:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- `BankApiIntegration.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt

- `BankApiConfig.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiConfig.kt

- `StubForDemo.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/bank/StubForDemo.kt

- `BankConnection.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/BankConnection.kt

- `BankConnectionDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/BankConnectionDao.kt

- `BankConnectionsViewModel.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/bank/BankConnectionsViewModel.kt

- `BankConnectionsScreen.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/bank/BankConnectionsScreen.kt

- `BankTokenCipher.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/security/BankTokenCipher.kt

- `CreateExpenseRequest.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt

- `TransactionLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `BankStatementParser.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/BankStatementParser.kt

- `BankStatementLifecycleProcessor.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt

- `ReceiptRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt

- `ValidateBankStatementTransactionsUseCase.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ValidateBankStatementTransactionsUseCase.kt

- Existing tests:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/domain/bank/BankApiIntegrationTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/data/database/dao/BankConnectionDaoTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/ValidateBankStatementTransactionsUseCaseTest.kt

External architecture/security references:

- RFC 8252 — OAuth 2.0 for Native Apps:  
  https://www.rfc-editor.org/rfc/rfc8252

- RFC 9700 — OAuth 2.0 Security Best Current Practice:  
  https://www.rfc-editor.org/rfc/rfc9700

- Android `KeyGenParameterSpec` / Android Keystore AES-GCM reference:  
  https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec

- Android cryptography guidance:  
  https://developer.android.com/privacy-and-security/cryptography