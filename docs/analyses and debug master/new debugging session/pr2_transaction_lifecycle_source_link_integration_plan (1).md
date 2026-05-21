# PR 2 — TransactionLifecycleCoordinator Source-Link Integration Plan

## Baseline

Current relevant state at `6fee004aa141878820db9240d751ea22f20c4a52`:

- `CreateExpenseRequest` already accepts source/provenance-ish fields:
  - `rawNotificationId`
  - `pendingReviewId`
  - `scannedReceiptId`
  - `emailReceiptSourceId`
  - `groupId`
  - `csvImportBatchId`
  - `csvRowNumber`
  - `externalFingerprint`
- The request itself notes that most of these are accepted but not persisted by the coordinator.
- `TransactionLifecycleCoordinator.createExpense()` persists:
  - `Expense.rawNotificationId`
  - `Expense.source`
  - `TransactionEvent(CREATE_ATTEMPTED)`
  - `TransactionEvent(CREATED)`
  - validation / duplicate / conflict events
- There is already `LifecycleEventType.SOURCE_LINKED`.
- There is no generic source-link persistence wired into transaction creation yet.

This PR should make `TransactionLifecycleCoordinator` the canonical writer of source links for created expenses.

---

# 1. Hard prerequisite

PR2 assumes PR1 has already added and tested:

```text
EntitySourceLink
EntitySourceLinkDao
SourceLinkWriter
SourceLinkWriterImpl
SourceLinkPayload
SourceTargetRef
TargetEntityType
SourceEntityType
SourceLinkRole
SourceLinkStatus
SafeProvenanceMetadata
ProvenanceHashingService / SensitiveHashingService integration
```

If PR1 is not merged, stop and do PR1 first.

---

# 2. Goal

When an expense is created through `TransactionLifecycleCoordinator`, all source-link data carried by `CreateExpenseRequest` must be persisted as durable `entity_source_links`.

Main invariant:

```text
Expense row + CREATED event + source links + SOURCE_LINKED event commit atomically.
```

If source-link persistence fails for a created expense, the expense creation must fail/rollback rather than creating an orphaned expense with missing provenance.

---

# 3. Non-goals

Do not include these in PR2:

- Pending-review source-link promotion.
- Receipt functional link changes.
- Email ingestion integration.
- Bank API integration changes.
- CSV/JSON export/import source-link schema.
- Backfill worker.
- UI/debug provenance screens.
- Removal of legacy fields.
- Changing `ReceiptExpenseLink`.
- Reworking the full side-effect contract.

PR2 is only the coordinator-level integration.

---

# 4. Files to modify

## Required

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt
```

## New files

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/CreateExpenseSourceLinkMapper.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/SourceLinkEventMetadataBuilder.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/DuplicateSourceLinkPolicy.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/DuplicateSourceLinkPolicyResolver.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/SourceLinkWriteException.kt
```

Optional, only if PR1 did not provide equivalent helpers:

```text
app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkPayloadExtensions.kt
```

---

# 5. Update `CreateExpenseRequest`

## 5.1 Add explicit modern source-link field

Add:

```kotlin
val sourceLinks: List<SourceLinkPayload> = emptyList()
```

Recommended placement:

```kotlin
val correlationId: String? = null,
val sourceLinks: List<SourceLinkPayload> = emptyList()
```

Reason: adding at the end minimizes positional-call breakage.

## 5.2 Keep all legacy fields

Keep:

```kotlin
rawNotificationId
pendingReviewId
scannedReceiptId
emailReceiptSourceId
groupId
csvImportBatchId
csvRowNumber
externalFingerprint
```

They are still needed for compatibility. PR2 maps them into `SourceLinkPayload`.

## 5.3 Do not remove the TODO yet

Replace the current TODO with a migration note:

```text
Legacy source-link fields are mapped to EntitySourceLink by
CreateExpenseSourceLinkMapper. New callsites should prefer sourceLinks.
```

---

# 6. Create `CreateExpenseSourceLinkMapper`

## 6.1 Responsibility

Convert `CreateExpenseRequest` into a normalized list of `SourceLinkPayload`.

Input:

```text
request.sourceLinks + legacy fields
```

Output:

```text
List<SourceLinkPayload>
```

Rules:

- Explicit `request.sourceLinks` are preserved.
- Legacy fields are converted into equivalent payloads.
- Duplicate payloads are de-duplicated by semantic identity where possible.
- No raw external identifiers are written to metadata.
- `externalFingerprint` is passed as `externalFingerprint`, not metadata.

---

## 6.2 Legacy mapping table

### `rawNotificationId`

```text
sourceEntityType = RAW_NOTIFICATION
sourceEntityLocalId = rawNotificationId
role = CREATED_FROM
status = ACTIVE
isPrimary = request.source == NOTIFICATION_AUTO_ACCEPT
```

### `pendingReviewId`

```text
sourceEntityType = PENDING_REVIEW
sourceEntityLocalId = pendingReviewId
role = APPROVED_FROM
status = ACTIVE
isPrimary = request.source == REVIEW_APPROVAL
```

### `scannedReceiptId`

For receipt-originated create:

```text
sourceEntityType = SCANNED_RECEIPT
sourceEntityLocalId = scannedReceiptId
role = CREATED_FROM
status = ACTIVE
isPrimary = request.source in [RECEIPT_SCAN, RECEIPT_BATCH_REVIEW, BANK_STATEMENT_REVIEW]
```

For non-receipt-originated create:

```text
role = LINKED_PROOF
isPrimary = false
```

### `emailReceiptSourceId`

```text
sourceEntityType = EMAIL_RECEIPT_SOURCE
sourceEntityLocalId = emailReceiptSourceId
role = CREATED_FROM
status = ACTIVE
isPrimary = request.source == EMAIL_RECEIPT
```

### `groupId`

```text
sourceEntityType = GROUP
sourceEntityLocalId = groupId
role = GENERATED_FROM
status = ACTIVE
isPrimary = request.source == GROUP_EXPENSE
```

### `csvImportBatchId + csvRowNumber`

Only create this link if both values exist.

```text
sourceEntityType = CSV_IMPORT_ROW
importBatchId = csvImportBatchId
importRowNumber = csvRowNumber
role = IMPORTED_FROM
status = ACTIVE
isPrimary = request.source == CSV_IMPORT
```

The source identity key should later resolve to:

```text
import:csv:<batchId>:row:<rowNumber>
```

### `externalFingerprint`

If `externalFingerprint` exists, attach it to the strongest available payload.

Preferred order:

```text
1. CSV_IMPORT_ROW
2. EMAIL_RECEIPT_SOURCE
3. SCANNED_RECEIPT
4. BANK_TRANSACTION / BANK_STATEMENT_IMPORT_ITEM
5. UNKNOWN external source fallback
```

If no other source field exists, create a fallback payload:

```text
sourceEntityType = UNKNOWN
sourceEntityLocalId = null
externalFingerprint = request.externalFingerprint
role = CREATED_FROM
status = ACTIVE
isPrimary = request.source != MANUAL_ENTRY
```

Important:

```text
Never put raw externalFingerprint into TransactionEvent.metadata.
Only SourceLinkWriter / hashing layer may persist its hash.
```

---

# 7. Add duplicate source-link policy

## 7.1 Enum

```kotlin
enum class DuplicateSourceLinkPolicy {
    LINK_SOURCE_TO_EXISTING,
    RECORD_ATTEMPT_ONLY,
    DO_NOT_LINK
}
```

## 7.2 Resolver

Create:

```kotlin
object DuplicateSourceLinkPolicyResolver {
    fun resolve(
        request: CreateExpenseRequest,
        payloads: List<SourceLinkPayload>,
        existingExpenseId: Long?
    ): DuplicateSourceLinkPolicy
}
```

Recommended behavior:

```text
MANUAL_ENTRY -> DO_NOT_LINK
DEBUG_TOOL -> DO_NOT_LINK
MIGRATION -> DO_NOT_LINK unless explicit sourceLinks exist

RECEIPT_SCAN -> LINK_SOURCE_TO_EXISTING
RECEIPT_BATCH_REVIEW -> LINK_SOURCE_TO_EXISTING
EMAIL_RECEIPT -> LINK_SOURCE_TO_EXISTING
REVIEW_APPROVAL -> LINK_SOURCE_TO_EXISTING
BANK_API_SYNC -> LINK_SOURCE_TO_EXISTING only if externalFingerprint/idempotencyKey exists
BANK_STATEMENT_REVIEW -> LINK_SOURCE_TO_EXISTING if scannedReceiptId or externalFingerprint exists
CSV_IMPORT -> LINK_SOURCE_TO_EXISTING if csvImportBatchId + csvRowNumber or externalFingerprint exists
GROUP_EXPENSE -> RECORD_ATTEMPT_ONLY by default
NOTIFICATION_AUTO_ACCEPT -> RECORD_ATTEMPT_ONLY by default
UNKNOWN -> RECORD_ATTEMPT_ONLY if payloads exist, otherwise DO_NOT_LINK
```

Rationale:

- Exact receipt/email/review/import/bank identities can safely attach duplicate provenance to an existing expense.
- Manual duplicates should not create misleading provenance.
- Notification duplicates can be noisy, so record the attempt but do not automatically claim the notification created the existing expense unless future PRs add stronger notification identity policy.

---

# 8. Modify `TransactionLifecycleCoordinator` constructor

Inject PR1 writer:

```kotlin
private val sourceLinkWriter: SourceLinkWriter
```

Update all construction sites/tests/Hilt bindings.

If `SourceLinkWriter` has only interface binding from PR1, no additional module work should be needed.

---

# 9. Create flow integration

## 9.1 Compute correlation ID before source mapping

Current coordinator already creates a correlation ID. Keep that.

Then compute source links once:

```kotlin
val sourceLinkPayloads = CreateExpenseSourceLinkMapper.fromRequest(request)
```

This should happen after correlation ID creation and before events.

## 9.2 Add safe source-link metadata to attempt events

Current `CREATE_ATTEMPTED` metadata is null. Change to safe summary metadata:

```json
{
  "sourceLinkCount": 2,
  "sourceLinks": [
    {
      "sourceType": "REVIEW_APPROVAL",
      "sourceEntityType": "PENDING_REVIEW",
      "sourceEntityLocalId": 123,
      "role": "APPROVED_FROM",
      "status": "ACTIVE",
      "isPrimary": true
    }
  ]
}
```

Do not include raw:

```text
externalFingerprint
email message ID
bank transaction ID
bank account ID
notification key
raw body/title/text
```

If no source links exist, metadata may remain null.

---

## 9.3 Validation failure event

When validation fails, include the same safe source-link summary plus validation errors.

Current metadata only contains errors. Extend it:

```json
{
  "errors": "...",
  "sourceLinkCount": 1,
  "sourceLinks": [...]
}
```

---

## 9.4 Atomic create transaction

Current transaction:

```kotlin
database.withTransaction {
    val id = expenseDao.insertAtomic(expense)
    transactionEventDao.insert(CREATED)
    id
}
```

Change to:

```kotlin
database.withTransaction {
    val id = expenseDao.insertAtomic(expense)
    if (id <= 0L) return@withTransaction CreateInsertOutcome.InsertConflict

    val createdMetadata =
        SourceLinkEventMetadataBuilder.createdMetadata(sourceLinkPayloads)

    transactionEventDao.insert(
        TransactionEvent(
            expenseId = id,
            eventType = LifecycleEventType.CREATED.name,
            metadata = createdMetadata,
            correlationId = correlationId,
            ...
        )
    )

    val linkResults = sourceLinkPayloads.map { payload ->
        sourceLinkWriter.linkExpense(
            expenseId = id,
            payload = payload,
            correlationId = correlationId
        )
    }

    if (linkResults.anyFatalFailure()) {
        throw SourceLinkWriteException(linkResults)
    }

    if (sourceLinkPayloads.isNotEmpty()) {
        transactionEventDao.insert(
            TransactionEvent(
                expenseId = id,
                eventType = LifecycleEventType.SOURCE_LINKED.name,
                source = request.source.name,
                occurredAt = now,
                metadata = SourceLinkEventMetadataBuilder.sourceLinkedMetadata(
                    payloads = sourceLinkPayloads,
                    results = linkResults
                ),
                correlationId = correlationId,
                ...
            )
        )
    }

    CreateInsertOutcome.Created(id)
}
```

Important:

```text
Do not let SourceLinkWriter open its own transaction.
It must participate in the coordinator's Room transaction.
```

---

# 10. Source-link failure behavior

## 10.1 Created expense path

For created expenses:

```text
Source-link failure is fatal.
Rollback the expense insert and CREATED event.
Return CreateExpenseResult.Error.
```

Reason:

```text
For non-manual source-created expenses, provenance is part of correctness.
```

## 10.2 Duplicate path

For duplicate skips:

```text
Duplicate event should still be written even if duplicate source-link attachment fails.
```

Reason:

```text
The expense already exists. We should not hide the duplicate decision because provenance enrichment failed.
```

However, the event metadata should record source-link write failure safely.

---

# 11. Duplicate flow integration

Current duplicate handling calls:

```kotlin
writeDuplicateEvent(...)
return DuplicateSkipped(...)
```

Replace with:

```kotlin
recordDuplicateOutcome(
    attemptedExpense = expense,
    request = request,
    sourceLinkPayloads = sourceLinkPayloads,
    duplicateExpenseId = duplicateId,
    reason = "Standard duplicate",
    occurredAt = now,
    correlationId = correlationId
)
```

## 11.1 New `recordDuplicateOutcome`

Responsibilities:

1. Resolve duplicate source-link policy.
2. Build duplicate event metadata.
3. Optionally write duplicate source links to existing expense.
4. Write `CREATE_DUPLICATE_SKIPPED`.
5. Return whether the event was logged.

Pseudo-flow:

```kotlin
private suspend fun recordDuplicateOutcome(...): Boolean {
    val policy = DuplicateSourceLinkPolicyResolver.resolve(...)

    val duplicatePayloads = when (policy) {
        LINK_SOURCE_TO_EXISTING -> sourceLinkPayloads.map {
            it.copy(
                role = SourceLinkRole.DUPLICATE_MATCHED,
                status = SourceLinkStatus.DUPLICATE,
                isPrimary = false,
                metadata = it.metadata.plusSafe(
                    "dedupeReason" to reason,
                    "existingExpenseId" to duplicateExpenseId
                )
            )
        }
        RECORD_ATTEMPT_ONLY,
        DO_NOT_LINK -> emptyList()
    }

    return try {
        database.withTransaction {
            val linkResults = duplicatePayloads.map {
                sourceLinkWriter.linkExpense(
                    expenseId = duplicateExpenseId,
                    payload = it,
                    correlationId = correlationId
                )
            }

            transactionEventDao.insert(
                TransactionEvent(
                    expenseId = duplicateExpenseId,
                    eventType = LifecycleEventType.CREATE_DUPLICATE_SKIPPED.name,
                    metadata = SourceLinkEventMetadataBuilder.duplicateMetadata(
                        policy = policy,
                        attemptedExpense = attemptedExpense,
                        sourceLinkPayloads = sourceLinkPayloads,
                        duplicateLinkResults = linkResults
                    ),
                    ...
                )
            )
        }
        true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Timber.w(e, "Failed to write duplicate outcome")
        false
    }
}
```

## 11.2 Manual duplicate safety

If duplicate source is `MANUAL_ENTRY`, do not write any `EntitySourceLink`.

Only write the duplicate event.

---

# 12. Insert conflict flow

Current insert conflict behavior:

1. Write `CREATE_INSERT_CONFLICT`.
2. For `STRICT_EXTERNAL_ID`, try to resolve existing ID.
3. If resolved, write duplicate event.

Improve sequence:

```text
1. If insertAtomic returns <= 0, first attempt to resolve existing expense.
2. If existing expense is found, record duplicate outcome with source-link policy.
3. Only write CREATE_INSERT_CONFLICT if existing expense cannot be resolved.
```

Resolution order:

```kotlin
val existingId =
    expense.dedupeKey?.let { expenseDao.findIdByDedupeKey(it) }
        ?: expenseDao.findDuplicateIdCurrencyAware(...)
```

This prevents noisy conflict events when the actual result is an idempotent duplicate.

If unresolved, include safe source-link summary in `CREATE_INSERT_CONFLICT.metadata`.

---

# 13. Event metadata builder

Create `SourceLinkEventMetadataBuilder`.

## 13.1 Required methods

```kotlin
object SourceLinkEventMetadataBuilder {
    fun createAttemptMetadata(payloads: List<SourceLinkPayload>): String?

    fun validationFailedMetadata(
        errors: List<String>,
        payloads: List<SourceLinkPayload>
    ): String

    fun createdMetadata(payloads: List<SourceLinkPayload>): String?

    fun sourceLinkedMetadata(
        payloads: List<SourceLinkPayload>,
        results: List<SourceLinkWriteResult>
    ): String

    fun duplicateMetadata(
        policy: DuplicateSourceLinkPolicy,
        attemptedExpense: Expense,
        sourceLinkPayloads: List<SourceLinkPayload>,
        duplicateLinkResults: List<SourceLinkWriteResult>
    ): String

    fun insertConflictMetadata(
        dedupMode: DeduplicationMode,
        dedupeKey: String?,
        payloads: List<SourceLinkPayload>
    ): String
}
```

## 13.2 Safe summary shape

Use a compact JSON structure:

```json
{
  "sourceLinkCount": 2,
  "sourceLinks": [
    {
      "sourceType": "CSV_IMPORT",
      "sourceEntityType": "CSV_IMPORT_ROW",
      "sourceEntityLocalId": null,
      "importBatchId": "batch-abc",
      "importRowNumber": 42,
      "role": "IMPORTED_FROM",
      "status": "ACTIVE",
      "isPrimary": true,
      "hasExternalFingerprint": false
    }
  ]
}
```

For external fingerprints:

```json
{
  "hasExternalFingerprint": true
}
```

If a hash is available from PR1 helper, include only:

```json
{
  "externalFingerprintHash": "..."
}
```

Never include the raw value.

---

# 14. `SOURCE_LINKED` event semantics

Write `SOURCE_LINKED` only when at least one source-link payload exists.

For created expenses:

```text
CREATED
SOURCE_LINKED
```

Both inside same DB transaction.

For manual expenses with no source links:

```text
CREATED only
```

For duplicate skips:

```text
CREATE_DUPLICATE_SKIPPED only
```

If policy links source to existing duplicate, the duplicate event metadata should say that duplicate source links were attached. Do not write a separate `SOURCE_LINKED` event for duplicate in PR2 unless you want stricter event symmetry; keep PR2 smaller.

---

# 15. Idempotency rules

## 15.1 Source-link inserts

`SourceLinkWriter` should already use `OnConflictStrategy.IGNORE`.

PR2 should treat this as success:

```text
Inserted -> success
AlreadyExists -> success
Failed -> fatal for created expense, non-fatal for duplicate enrichment
```

## 15.2 Duplicate explicit + legacy same source

If both explicit `request.sourceLinks` and legacy fields describe the same source, avoid duplicate payloads before writing.

Example:

```text
request.scannedReceiptId = 10
request.sourceLinks contains SCANNED_RECEIPT local id 10
```

Expected:

```text
one source link row
one source summary item
```

---

# 16. Correlation ID propagation

Use the same correlation ID for:

```text
CREATE_ATTEMPTED
CREATE_VALIDATION_FAILED
CREATED
SOURCE_LINKED
CREATE_DUPLICATE_SKIPPED
CREATE_INSERT_CONFLICT
EntitySourceLink.correlationId
```

Current coordinator already derives a correlation ID near the start. Reuse it everywhere.

---

# 17. Privacy requirements

PR2 must ensure:

```text
No raw externalFingerprint in TransactionEvent.metadata.
No raw externalFingerprint in EntitySourceLink.
No raw bank/email/notification external IDs in metadata.
No raw source text/body/title/description in metadata.
```

Allowed in event metadata:

```text
sourceType
sourceEntityType
sourceEntityLocalId for local Room IDs
importBatchId
importRowNumber
role
status
isPrimary
hasExternalFingerprint
externalFingerprintHash if already hashed by PR1 helper
```

Caution:

Current `STRICT_EXTERNAL_ID` dedupe key may still use `idempotencyKey` or `externalFingerprint`. If those can contain raw external IDs, that is a separate privacy issue. Do not make source-link metadata repeat the leak. If feasible, add a follow-up TODO or fix dedupe hashing in a dedicated privacy PR.

---

# 18. Test plan

## 18.1 Mapper unit tests

Create tests for `CreateExpenseSourceLinkMapper`.

Required:

```text
maps_rawNotificationId_to_raw_notification_created_from
maps_pendingReviewId_to_pending_review_approved_from
maps_scannedReceiptId_receipt_source_to_created_from
maps_scannedReceiptId_non_receipt_source_to_linked_proof
maps_emailReceiptSourceId_to_email_receipt_source
maps_groupId_to_group_generated_from
maps_csv_batch_and_row_to_csv_import_row
maps_externalFingerprint_without_other_source_to_unknown_external_payload
deduplicates_explicit_and_legacy_same_source
manual_entry_without_source_fields_returns_empty
```

## 18.2 Metadata builder tests

Required:

```text
created_metadata_contains_source_link_summary
validation_metadata_contains_errors_and_source_summary
duplicate_metadata_contains_policy
metadata_does_not_contain_raw_externalFingerprint
metadata_does_not_contain_blocked_sensitive_keys
```

## 18.3 Coordinator create tests

Update existing `TransactionLifecycleCoordinatorTest` constructor setup to include mocked `SourceLinkWriter`.

Required coordinator tests:

```text
create_with_rawNotificationId_persists_source_link
create_with_pendingReviewId_persists_source_link
create_with_scannedReceiptId_persists_source_link
create_with_emailReceiptSourceId_persists_source_link
create_with_groupId_persists_source_link
create_with_csv_row_persists_import_source_link
create_with_externalFingerprint_stores_hash_not_plaintext
create_with_explicit_sourceLinks_persists_all_links
create_without_source_links_does_not_write_SOURCE_LINKED
created_transaction_event_contains_source_link_summary
source_linked_event_written_after_created
correlationId_propagates_to_source_links_and_events
```

## 18.4 Atomicity tests

Use in-memory Room if possible, not only mocks.

Required:

```text
created_event_and_source_links_commit_atomically
source_link_failure_rolls_back_expense_insert
source_link_failure_rolls_back_CREATED_event
source_link_failure_returns_CreateExpenseResult_Error
```

## 18.5 Duplicate policy tests

Required:

```text
duplicate_receipt_create_links_existing_by_policy
duplicate_email_create_links_existing_by_policy
duplicate_review_approval_links_existing_by_policy
duplicate_csv_import_links_existing_by_policy
manual_duplicate_does_not_create_false_source_link
notification_duplicate_records_attempt_only
duplicate_event_contains_source_link_policy
duplicate_source_link_already_exists_is_success
```

## 18.6 Insert conflict tests

Required if insert-conflict logic is improved in this PR:

```text
strict_external_insert_conflict_resolves_existing_and_records_duplicate
standard_insert_conflict_resolves_existing_when_possible
unresolved_insert_conflict_writes_conflict_event_with_source_summary
insert_conflict_metadata_has_no_raw_externalFingerprint
```

---

# 19. Acceptance criteria

PR2 is done when:

```text
1. CreateExpenseRequest supports explicit sourceLinks.

2. All legacy source-link fields are mapped:
   rawNotificationId
   pendingReviewId
   scannedReceiptId
   emailReceiptSourceId
   groupId
   csvImportBatchId
   csvRowNumber
   externalFingerprint

3. TransactionLifecycleCoordinator writes EntitySourceLink rows for created expenses.

4. CREATED event, source links, and SOURCE_LINKED event commit atomically.

5. SOURCE_LINKED event is written only when source links exist.

6. CREATED event metadata contains a safe source-link summary.

7. CREATE_ATTEMPTED, validation failure, duplicate, and conflict events include safe source-link context where applicable.

8. Duplicate source-link policy is applied:
   exact receipt/email/review/import/bank sources can link to existing duplicate;
   manual duplicates do not create source links.

9. Raw externalFingerprint is not stored in source-link metadata or event metadata.

10. Existing manual creation behavior remains unchanged except for safe metadata additions.

11. Existing rawNotificationId persistence remains intact.

12. Existing side-effect timing remains unchanged:
   source links are DB/audit work before post-commit side effects.
```

---

# 20. Recommended implementation sequence

## Step 1 — Add request field

- Add `sourceLinks` to `CreateExpenseRequest`.
- Update imports.
- Compile to find impacted tests/calls.

## Step 2 — Add mapper

- Implement `CreateExpenseSourceLinkMapper`.
- Add mapper unit tests.
- Keep it pure; no DB, no hashing side effects.

## Step 3 — Add metadata builder

- Implement safe JSON summaries.
- Add metadata privacy tests.

## Step 4 — Add duplicate policy resolver

- Implement `DuplicateSourceLinkPolicy`.
- Implement resolver.
- Add tests for every `ExpenseSource`.

## Step 5 — Inject `SourceLinkWriter`

- Modify coordinator constructor.
- Update test setup.
- Update DI if needed.

## Step 6 — Wire created-expense transaction

- Build payloads once.
- Add metadata to `CREATE_ATTEMPTED`.
- Add metadata to validation failure.
- In DB transaction:
  - insert expense
  - insert `CREATED`
  - write source links
  - insert `SOURCE_LINKED`
- Make source-link failure fatal for created expenses.

## Step 7 — Wire duplicate path

- Replace `writeDuplicateEvent()` with `recordDuplicateOutcome()`.
- Apply duplicate source-link policy.
- Add safe duplicate metadata.
- Ensure manual duplicates do not link.

## Step 8 — Improve insert-conflict resolution

- Resolve existing ID before writing conflict event.
- If resolved, use duplicate outcome path.
- If unresolved, write conflict event with safe source summary.

## Step 9 — Add atomicity tests

- Prefer in-memory Room for true rollback tests.
- Keep mock tests for simple control flow.

## Step 10 — Cleanup

- Remove obsolete TODO saying fields are not persisted.
- Add new TODO only for future deprecation of legacy fields.
- Run full test suite.

---

# 21. Risks and mitigations

## Risk: SourceLinkWriter opens its own transaction

Mitigation:

```text
PR1 writer must not call database.withTransaction internally.
PR2 should document that coordinator owns the transaction.
```

## Risk: Event metadata duplicates source-link privacy logic

Mitigation:

```text
Use shared sanitizer/hash summary helper from PR1 if available.
If not available, add a small safe-summary helper in PR2 and forbid raw external values.
```

## Risk: Constructor changes break many tests

Mitigation:

```text
Add relaxed mock SourceLinkWriter in tests.
Consider default fake helper in testfixtures.
```

## Risk: Duplicate policy links weak sources to existing expenses

Mitigation:

```text
Default weak/noisy sources to RECORD_ATTEMPT_ONLY.
Manual/debug sources default to DO_NOT_LINK.
```

## Risk: Existing strict external dedupe persists raw externalFingerprint in dedupeKey

Mitigation:

```text
Do not repeat that value in source links or event metadata.
Add explicit follow-up issue if dedupeKey privacy is not fixed here.
```

---

# 22. Definition of done

```text
A developer can answer, for any newly created expense:
"Which request/source object created this expense?"

The answer is durable in entity_source_links, visible in lifecycle events,
privacy-safe, and atomically committed with the expense creation.
```

---

# Sources checked

- Latest commit `6fee004`:  
  https://github.com/panospao7/Cost-agregator/commit/6fee004aa141878820db9240d751ea22f20c4a52

- `TransactionLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `CreateExpenseRequest.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt

- `Expense.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt

- `TransactionEvent.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt

- `LifecycleEventType.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt

- `ExpenseSource.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/ExpenseSource.kt

- Pipeline 2 static debug report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline2_static_debug_report_b6abe0a.md

- Global source links/provenance plan:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/docs/analyses%20and%20debug%20master/new%20debugging%20session/global_source_links_provenance_plan.md