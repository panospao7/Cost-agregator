# PR 6 — Bank integration source model

## Baseline checked
Current code at `6fee004aa141878820db9240d751ea22f20c4a52` still treats bank sync as a demo stub:
- `BankApiIntegration.syncTransactions()` creates expenses directly through `TransactionLifecycleCoordinator`.
- It already hashes `providerTransactionId` for diagnostics, but not as a durable provenance model.
- `CreateExpenseRequest` still has no bank-specific provenance bridge in the current branch.
- `TransactionEvent.metadata` exists, but bank source context is ad hoc.
- `BankConnection` has only connection-level data; no account/transaction provenance model.
- PR1–PR5 are assumed merged conceptually, including generic source-link infrastructure and bank review routing.

## Goal
Make every bank-created expense, duplicate skip, and bank-review path traceable back to:
- provider
- bank connection
- account identity
- sync run
- provider transaction hash
- booking/value date
- transaction status

All of that must be persisted **without raw provider IDs or raw bank text** in durable provenance.

## Non-goals
- No real-provider registry work.
- No OAuth/session work.
- No sync-run ledger redesign.
- No shared bank dedupe implementation.
- No token refresh or backup/restore work.
- No UI changes.
- No new bank-only provenance table if generic source links already exist.

## Key design decision
Use the generic source-link model as the canonical persistence layer.

Do **not** add bank-specific columns to `Expense`.
Do **not** store bank provenance only in `TransactionEvent.metadata`.
Instead:
1. write bank source links through the generic source-link pipeline
2. mirror a safe summary into `TransactionEvent.metadata`

---

## Files to modify

### Core
- `app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt`

### New helpers
- `app/src/main/java/com/yourname/expensetracker/domain/bank/provenance/BankTransactionSourceContext.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/bank/provenance/BankSourceIdentityFactory.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/bank/provenance/BankSourceLinkPayloadFactory.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/bank/provenance/BankSourceEventMetadataBuilder.kt`

### Tests
- `app/src/test/java/com/yourname/expensetracker/domain/bank/provenance/BankSourceIdentityFactoryTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/bank/provenance/BankSourceLinkPayloadFactoryTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/bank/provenance/BankSourceEventMetadataBuilderTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/bank/BankApiIntegrationTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt`

---

## Data model

### `BankTransactionSourceContext`
A transient, safe-only bridge object.

Recommended fields:
- `providerId`
- `bankId`
- `connectionId`
- `accountIdHash?`
- `syncRunId?`
- `providerTransactionIdHash`
- `bookingDate`
- `valueDate?`
- `transactionStatus?`
- `route` = `AUTO_ACCEPT` / `REVIEW` / `DUPLICATE` / `FAILED`
- `classificationConfidence?`
- `correlationId?`

### Source identity rules
Use deterministic identity keys:
- `external:bank_transaction:<providerId>:<accountIdHash?>:<providerTransactionIdHash>`
- `local:bank_connection:<connectionId>`
- `local:bank_sync_run:<syncRunId>`
- `external:bank_account:<providerId>:<accountIdHash>` if account hash exists

Rules:
- `syncRunId` must **not** be part of the primary transaction identity key.
- account hash should be included when available.
- raw provider transaction IDs must never be persisted.

---

## Source-link mapping

For a bank-created expense:
1. `BANK_TRANSACTION / CREATED_FROM` as the primary link
2. `BANK_SYNC_RUN / IMPORTED_FROM`
3. `BANK_CONNECTION / ENRICHED_BY`
4. `BANK_ACCOUNT / ENRICHED_BY` if available

For low-confidence bank review:
1. target `PENDING_REVIEW`
2. `BANK_TRANSACTION / REVIEWED_FROM`
3. optional sync-run / connection enrichment links

For duplicate resolution:
- keep the same bank identity payload
- let the existing duplicate policy decide whether to attach to an existing expense
- do not invent a second raw identity

---

## Request integration

### `CreateExpenseRequest`
If your branch already has generic `sourceLinks`, reuse them.
If not, add them now rather than introducing bank-only persistence fields.

Recommended:
- `sourceLinks: List<SourceLinkPayload> = emptyList()`

Bank sync should feed bank provenance through that generic field.

---

## `BankApiIntegration` changes

### 1) Build bank provenance once per transaction
In `mapTransactionToExpense(...)`:
- compute `providerTransactionIdHash`
- compute `accountIdHash` if available
- derive `bookingDate` / `valueDate`
- derive `transactionStatus` or `UNKNOWN`
- build `BankTransactionSourceContext`
- convert it to `sourceLinks`

### 2) Never use raw provider IDs in persistence
Current code falls back to raw `transaction.id` for `idempotencyKey`.
That should be removed for the bank provenance path.

Preferred rule:
- use the hashed provider transaction id
- if hashing is unavailable, fail closed or route to a safe failure path

### 3) Thread safe bank metadata into coordinator calls
Pass the bank source links into `CreateExpenseRequest`.
Also keep the sync-run correlation id.

### 4) Replace ad hoc event metadata
Current `TRANSACTION_IMPORTED` / `TRANSACTION_DUPLICATE_SKIPPED` / `TRANSACTION_FAILED`
events should use the shared bank metadata builder.

---

## `TransactionLifecycleCoordinator` changes

### 1) Persist bank source links atomically
If request contains bank source links, they must be written in the same transaction as:
- expense insert
- `CREATED` event
- `SOURCE_LINKED` event

### 2) Preserve safe bank summary in lifecycle events
Add bank-safe metadata to:
- `CREATE_ATTEMPTED`
- `CREATE_VALIDATION_FAILED`
- `CREATED`
- `CREATE_DUPLICATE_SKIPPED`
- `CREATE_INSERT_CONFLICT`

### 3) Keep metadata privacy-safe
Allowed:
- providerId
- bankId
- connectionId
- accountIdHash
- syncRunId
- providerTransactionIdHash
- bookingDate
- valueDate
- transactionStatus
- route
- confidence

Forbidden:
- raw provider transaction ID
- raw account ID
- raw merchant/description/reference
- raw bank text
- tokens

---

## `TransactionEvent` changes
No schema change is required if the existing `metadata` field is used well.

Update:
- KDoc
- helper creation paths if needed
- bank event metadata expectations

Do not add raw bank identifiers as new columns.

---

## Review route integration
Because PR5 routes low-confidence bank transactions to review:
- the bank source model must also support `PENDING_REVIEW`
- approval later should promote the bank source links to the expense
- this should reuse the existing source-link promotion flow from earlier PRs

---

## Test plan

### Factory tests
- deterministic `providerTransactionIdHash`
- deterministic `sourceIdentityKey`
- account hash included when present
- syncRunId not part of primary transaction key

### Payload tests
- expense gets primary `BANK_TRANSACTION` link
- sync run / connection / account enrichment links are added
- review target gets `REVIEWED_FROM`
- duplicate policy does not invent new raw identities

### Metadata tests
- contains connectionId, providerId, syncRunId, providerTransactionIdHash
- contains booking/value dates and transaction status
- does **not** contain raw transaction id
- does **not** contain raw description/reference/account id

### Integration tests
- `bank_import_persists_connection_id`
- `bank_import_persists_account_id_hash`
- `bank_import_persists_provider_id`
- `bank_import_event_contains_sync_run_id`
- `bank_import_event_contains_provider_transaction_hash`
- `bank_import_persists_booking_and_value_dates`
- `bank_low_confidence_review_creates_pending_review_source_link`
- `approved_bank_review_promotes_source_links`
- `bank_duplicate_records_safe_duplicate_source_metadata`
- `bank_metadata_has_no_raw_ids_or_bank_text`

---

## Recommended implementation order
1. Add bank provenance context and identity factory.
2. Add bank source-link payload factory.
3. Add bank event metadata builder.
4. Wire `BankApiIntegration` to emit bank source links through `CreateExpenseRequest`.
5. Update `TransactionLifecycleCoordinator` to persist and summarize bank provenance.
6. Add review-path coverage for low-confidence bank transactions.
7. Add privacy and idempotency tests.
8. Verify no raw provider IDs leak into metadata or identity keys.

---

## Acceptance criteria
PR6 is done when:
- every bank-created expense has durable bank provenance
- bank review paths preserve provenance through approval
- duplicate and failure paths still carry safe bank metadata
- provider/account/run identity is traceable
- no raw provider IDs or raw bank text are persisted in source metadata
- existing demo-only/release-blocked behavior remains unchanged

---

## Sources checked
- Current commit:  
  https://github.com/panospao7/Cost-agregator/commit/6fee004aa141878820db9240d751ea22f20c4a52
- Pipeline 10 bank report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline10_static_debug_report_b6abe0a.md
- `BankApiIntegration.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt
- `CreateExpenseRequest.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt
- `TransactionLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- `TransactionEvent.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt
- `BankConnection.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/database/entity/BankConnection.kt
- `ExpenseSource.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/ExpenseSource.kt