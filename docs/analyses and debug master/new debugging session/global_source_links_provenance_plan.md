# Global Source Links / Provenance Implementation Plan

Baseline commit: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`

Universal rule:

```text
Every created expense, review, receipt, import item, and sync item must be traceable back to its origin.
Source links must be durable, queryable, privacy-safe, exportable, and preserved through review/approval flows.
```

Affected pipelines:

```text
P1 Notification capture
P2 Transaction lifecycle
P3 Receipt/OCR/bank statement
P10 Bank integration/imports
P11 Email receipt ingestion
P12 Import/export/accounting
Indirectly:
P4 Recurring
P6 Forecast/budget planned rows
P7 Backup/restore
P8 Privacy/redaction
P9 Workers/batch jobs
```

---

## 0. Current state summary

Current code already has partial source/provenance fields:

```text
Expense.rawNotificationId
Expense.source
PendingReview.rawNotificationId
PendingReview.scannedReceiptId
ScannedReceipt.expenseId
ScannedReceipt.sourceType
ScannedReceipt.sourceFingerprint
ReceiptExpenseLink
EmailReceiptSource
TransactionEvent.source
TransactionEvent.metadata
CreateExpenseRequest source-link fields
LifecycleEventType.SOURCE_LINKED
```

But the source-link contract is incomplete.

Most important current gap:

`CreateExpenseRequest` accepts:

```text
rawNotificationId
pendingReviewId
scannedReceiptId
emailReceiptSourceId
groupId
csvImportBatchId
csvRowNumber
externalFingerprint
```

but the code comment says most of these are **accepted but not persisted by the coordinator**.

Current `Expense` only has a durable local FK for:

```text
rawNotificationId
```

Receipt-specific linking exists through:

```text
receipt_expense_links
```

but there is no universal provenance model for:

```text
notification -> review -> expense
receipt -> expense
email source -> receipt -> expense
bank transaction -> review/expense
CSV row -> expense
group operation -> expense
recurring occurrence -> expense
import/export roundtrip -> expense
```

---

# 1. Target architecture

## 1.1 Definitions

### Source link

A durable relation between a target domain entity and the thing that caused or justified it.

Example:

```text
Expense 123 was created from RawNotification 45.
Expense 200 was approved from PendingReview 77.
Expense 201 came from bank transaction hash abc via sync run 9.
Expense 202 came from CSV import batch b1 row 42.
Expense 203 was linked to ScannedReceipt 88.
```

### Provenance

The full trace of origin and processing path.

Example:

```text
email message hash -> EmailReceiptSource -> ScannedReceipt -> PendingReview -> Expense -> TransactionEvent
```

### Lifecycle event

Immutable history of a state transition.

Example:

```text
CREATED, SOURCE_LINKED, REVIEW_APPROVED, DUPLICATE_SKIPPED
```

### Functional link

A domain-specific relation used by app features.

Example:

```text
ReceiptExpenseLink
```

Important:

```text
ReceiptExpenseLink is not replaced by source links.
It remains the functional receipt↔expense relation.
Source links add generic audit/provenance.
```

---

## 1.2 Universal source-link table

Add one generic table:

```text
entity_source_links
```

This table supports expense links first, but can also link pending reviews, receipts, imports, and other entities later.

---

# 2. Schema design

## 2.1 EntitySourceLink

```kotlin
@Entity(
    tableName = "entity_source_links",
    indices = [
        Index(value = ["targetEntityType", "targetEntityId"]),
        Index(value = ["sourceType"]),
        Index(value = ["sourceEntityType", "sourceEntityLocalId"]),
        Index(value = ["sourceIdentityKey"]),
        Index(value = ["operationRunId"]),
        Index(value = ["correlationId"]),
        Index(
            value = ["targetEntityType", "targetEntityId", "sourceIdentityKey"],
            unique = true
        )
    ]
)
data class EntitySourceLink(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Target
    val targetEntityType: String,
    val targetEntityId: Long,

    // High-level source
    val sourceType: String,

    // Concrete source object
    val sourceEntityType: String,
    val sourceEntityLocalId: Long?,

    // Non-null canonical identity key for uniqueness.
    // Examples:
    // local:raw_notification:45
    // local:pending_review:77
    // external:bank_transaction:providerA:abcHash
    // import:csv:batch123:row42
    val sourceIdentityKey: String,

    // External/privacy-safe identities
    val externalIdHash: String?,
    val externalFingerprintHash: String?,

    // Provider/run context
    val providerId: String?,
    val accountIdHash: String?,
    val operationRunId: Long?,
    val importBatchId: String?,
    val importRowNumber: Int?,

    // Link semantics
    val linkRole: String,
    val linkStatus: String,
    val confidence: Float?,
    val isPrimary: Boolean,

    // Audit
    val createdAt: Long,
    val createdBy: String?,
    val correlationId: String?,
    val metadataJson: String?,
    val metadataSchemaVersion: Int = 1
)
```

---

## 2.2 Enums

```kotlin
enum class TargetEntityType {
    EXPENSE,
    PENDING_REVIEW,
    SCANNED_RECEIPT,
    RAW_NOTIFICATION,
    OPERATION_RUN
}
```

```kotlin
enum class SourceEntityType {
    RAW_NOTIFICATION,
    PENDING_REVIEW,
    SCANNED_RECEIPT,
    EMAIL_RECEIPT_SOURCE,
    RECEIPT_EXPENSE_LINK,

    BANK_CONNECTION,
    BANK_ACCOUNT,
    BANK_TRANSACTION,
    BANK_SYNC_RUN,
    BANK_STATEMENT_IMPORT_RUN,
    BANK_STATEMENT_IMPORT_ITEM,

    CSV_IMPORT_RUN,
    CSV_IMPORT_ROW,
    JSON_IMPORT_RUN,
    JSON_IMPORT_ROW,

    GROUP,
    RECURRING_RULE,
    RECURRING_OCCURRENCE,
    PLANNED_EXPENSE,

    MANUAL_ENTRY,
    DEBUG_TOOL,
    MIGRATION,
    LEGACY_SOURCE_ONLY,
    UNKNOWN
}
```

```kotlin
enum class SourceLinkRole {
    CREATED_FROM,
    APPROVED_FROM,
    REVIEWED_FROM,
    LINKED_PROOF,
    DUPLICATE_MATCHED,
    IMPORTED_FROM,
    GENERATED_FROM,
    ENRICHED_BY,
    LEGACY_BACKFILL
}
```

```kotlin
enum class SourceLinkStatus {
    ACTIVE,
    SUPERSEDED,
    DUPLICATE,
    FAILED,
    REDACTED,
    LEGACY_PARTIAL
}
```

---

## 2.3 Source identity key rules

`sourceIdentityKey` must be non-null and deterministic.

Examples:

```kotlin
local:raw_notification:123
local:pending_review:77
local:scanned_receipt:88
local:email_receipt_source:44
import:csv:batch-abc:row:42
external:bank_transaction:provider:txHash
external:email_message:messageHash
legacy:source:EMAIL_RECEIPT:expense:123
```

Rules:

```text
Local Room IDs can be raw.
External IDs must be hashed.
Do not store raw email message IDs, bank transaction IDs, bank account IDs, or notification keys.
```

---

# 3. DAO and writer

## 3.1 DAO

```kotlin
@Dao
interface EntitySourceLinkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(link: EntitySourceLink): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(links: List<EntitySourceLink>): List<Long>

    @Query("""
        SELECT * FROM entity_source_links
        WHERE targetEntityType = :targetType
          AND targetEntityId = :targetId
        ORDER BY isPrimary DESC, createdAt ASC
    """)
    suspend fun getForTarget(
        targetType: String,
        targetId: Long
    ): List<EntitySourceLink>

    @Query("""
        SELECT * FROM entity_source_links
        WHERE sourceIdentityKey = :sourceIdentityKey
        ORDER BY createdAt DESC
    """)
    suspend fun getBySourceIdentityKey(
        sourceIdentityKey: String
    ): List<EntitySourceLink>

    @Query("""
        SELECT * FROM entity_source_links
        WHERE targetEntityType = 'EXPENSE'
          AND targetEntityId = :expenseId
        ORDER BY isPrimary DESC, createdAt ASC
    """)
    suspend fun getForExpense(expenseId: Long): List<EntitySourceLink>

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM entity_source_links
            WHERE targetEntityType = :targetType
              AND targetEntityId = :targetId
              AND sourceIdentityKey = :sourceIdentityKey
        )
    """)
    suspend fun exists(
        targetType: String,
        targetId: Long,
        sourceIdentityKey: String
    ): Boolean
}
```

---

## 3.2 SourceLinkWriter

```kotlin
interface SourceLinkWriter {
    suspend fun linkExpense(
        expenseId: Long,
        payload: SourceLinkPayload,
        correlationId: String? = null
    ): SourceLinkWriteResult

    suspend fun linkTarget(
        target: SourceTargetRef,
        payload: SourceLinkPayload,
        correlationId: String? = null
    ): SourceLinkWriteResult

    suspend fun linkExpenseSourcesFromRequest(
        expenseId: Long,
        request: CreateExpenseRequest,
        correlationId: String? = null
    ): List<SourceLinkWriteResult>
}
```

```kotlin
data class SourceTargetRef(
    val entityType: TargetEntityType,
    val entityId: Long
)
```

```kotlin
data class SourceLinkPayload(
    val sourceType: ExpenseSource,
    val sourceEntityType: SourceEntityType,
    val sourceEntityLocalId: Long? = null,
    val externalId: String? = null,
    val externalFingerprint: String? = null,
    val providerId: String? = null,
    val accountId: String? = null,
    val operationRunId: Long? = null,
    val importBatchId: String? = null,
    val importRowNumber: Int? = null,
    val role: SourceLinkRole,
    val status: SourceLinkStatus = SourceLinkStatus.ACTIVE,
    val confidence: Float? = null,
    val isPrimary: Boolean = false,
    val createdBy: String? = null,
    val metadata: SafeProvenanceMetadata = SafeProvenanceMetadata.empty()
)
```

```kotlin
sealed interface SourceLinkWriteResult {
    data class Inserted(val id: Long) : SourceLinkWriteResult
    data object AlreadyExists : SourceLinkWriteResult
    data class Failed(val reason: String, val error: Throwable? = null) : SourceLinkWriteResult
}
```

---

## 3.3 Privacy-safe metadata

Use or align with the privacy/diagnostics plan.

```kotlin
class SafeProvenanceMetadata private constructor(
    private val values: Map<String, Any?>
) {
    fun toJson(): String
}
```

Blocked fields:

```text
rawText
rawBody
emailBody
emailSubjectRaw
emailSenderRaw
bankDescription
bankReference
accessToken
refreshToken
prompt
fullPath
iban
accountNumber
cardNumber
```

External IDs:

```text
messageId
providerTransactionId
bankAccountId
notificationKey
orderId
```

must be stored as HMAC hashes, not plaintext.

---

# 4. CreateExpenseRequest integration

## 4.1 Short-term compatibility

Keep current fields:

```text
rawNotificationId
pendingReviewId
scannedReceiptId
emailReceiptSourceId
groupId
csvImportBatchId
csvRowNumber
externalFingerprint
```

Add one new field:

```kotlin
val sourceLinks: List<SourceLinkPayload> = emptyList()
```

Migration path:

```text
1. Coordinator maps legacy fields into SourceLinkPayload.
2. New callsites use sourceLinks directly.
3. Later deprecate individual legacy fields.
```

---

## 4.2 Helper mapper

Add:

```kotlin
object CreateExpenseSourceLinkMapper {
    fun fromRequest(request: CreateExpenseRequest): List<SourceLinkPayload>
}
```

Mapping:

```text
rawNotificationId
  -> RAW_NOTIFICATION / CREATED_FROM

pendingReviewId
  -> PENDING_REVIEW / APPROVED_FROM

scannedReceiptId
  -> SCANNED_RECEIPT / CREATED_FROM or LINKED_PROOF

emailReceiptSourceId
  -> EMAIL_RECEIPT_SOURCE / CREATED_FROM

groupId
  -> GROUP / GENERATED_FROM

csvImportBatchId + csvRowNumber
  -> CSV_IMPORT_ROW / IMPORTED_FROM

externalFingerprint
  -> source-specific external fingerprint hash

request.source
  -> high-level sourceType for all generated links
```

---

# 5. TransactionLifecycleCoordinator integration

## 5.1 Create flow

Inside the same transaction that inserts expense and `TransactionEvent(CREATED)`:

```kotlin
database.withTransaction {
    val expenseId = expenseDao.insertAtomic(expense)

    transactionEventDao.insert(CREATED)

    val sourceLinks = CreateExpenseSourceLinkMapper.fromRequest(request)
    sourceLinkDao.insertAll(
        sourceLinks.map { it.toEntity(target = Expense(expenseId)) }
    )

    if (sourceLinks.isNotEmpty()) {
        transactionEventDao.insert(
            TransactionEvent(
                expenseId = expenseId,
                eventType = LifecycleEventType.SOURCE_LINKED.name,
                source = request.source.name,
                metadata = sourceLinks.safeSummaryJson(),
                ...
            )
        )
    }
}
```

Rule:

```text
Expense row, CREATED event, and source links commit atomically.
```

---

## 5.2 Duplicate flow

When create returns duplicate:

```text
Do not blindly create source link.
Apply source-specific duplicate policy.
```

Add:

```kotlin
enum class DuplicateSourceLinkPolicy {
    LINK_SOURCE_TO_EXISTING,
    RECORD_ATTEMPT_ONLY,
    DO_NOT_LINK
}
```

Recommended defaults:

```text
RECEIPT_SCAN              -> LINK_SOURCE_TO_EXISTING
EMAIL_RECEIPT             -> LINK_SOURCE_TO_EXISTING
BANK_API_SYNC exact ID    -> LINK_SOURCE_TO_EXISTING
BANK_STATEMENT_REVIEW     -> LINK_SOURCE_TO_EXISTING if same statement item/run
REVIEW_APPROVAL           -> LINK_SOURCE_TO_EXISTING
NOTIFICATION_AUTO_ACCEPT  -> RECORD_ATTEMPT_ONLY unless same rawNotificationId
CSV_IMPORT                -> LINK_SOURCE_TO_EXISTING if external ID/fingerprint exact
MANUAL_ENTRY              -> DO_NOT_LINK
```

If linking duplicate to existing expense:

```text
linkStatus = DUPLICATE
role = DUPLICATE_MATCHED
metadata.matchedExpenseId = existing ID
```

---

## 5.3 Review approval flow

When approving a pending review:

```text
PendingReview -> Expense
```

Required links on created expense:

```text
PENDING_REVIEW / APPROVED_FROM
RAW_NOTIFICATION / CREATED_FROM, if review.rawNotificationId exists
SCANNED_RECEIPT / CREATED_FROM, if review.scannedReceiptId exists
```

Also copy any source links attached to the pending review target.

Add method:

```kotlin
suspend fun promotePendingReviewLinksToExpense(
    pendingReviewId: Long,
    expenseId: Long,
    correlationId: String?
)
```

---

# 6. Existing relation compatibility

## 6.1 ReceiptExpenseLink

Keep `receipt_expense_links`.

When creating receipt-expense link:

```text
1. Insert ReceiptExpenseLink.
2. Insert EntitySourceLink target=EXPENSE source=SCANNED_RECEIPT.
3. Write ReceiptEvent(RECEIPT_LINKED_TO_EXPENSE).
4. Write TransactionEvent(SOURCE_LINKED) if appropriate.
```

Do not use source link as the functional receipt proof relation.

---

## 6.2 Expense.rawNotificationId

Keep initially for compatibility and indexes.

New writes:

```text
Expense.rawNotificationId remains set for notification-created rows.
EntitySourceLink is also written.
```

Long-term:

```text
rawNotificationId can become legacy/denormalized.
```

---

## 6.3 ScannedReceipt.expenseId

Keep initially.

New writes:

```text
ScannedReceipt.expenseId can remain the primary/simple link.
ReceiptExpenseLink + EntitySourceLink provide richer many-to-many/audit.
```

---

# 7. Pipeline-specific implementation

## Pipeline 1 — Notification

### Links to write

For auto-accepted expense:

```text
target = EXPENSE
sourceEntityType = RAW_NOTIFICATION
sourceEntityLocalId = rawNotificationId
sourceType = NOTIFICATION_AUTO_ACCEPT
role = CREATED_FROM
isPrimary = true
```

For parser-failed pending review:

```text
target = PENDING_REVIEW
sourceEntityType = RAW_NOTIFICATION
sourceEntityLocalId = rawNotificationId
sourceType = NOTIFICATION_AUTO_ACCEPT or REVIEW_APPROVAL precursor
role = REVIEWED_FROM
isPrimary = true
```

On review approval:

```text
copy pending review source links to expense.
add PENDING_REVIEW / APPROVED_FROM.
```

### Acceptance tests

```text
notification_auto_accept_creates_expense_source_link
notification_pending_review_creates_review_source_link
review_approval_promotes_raw_notification_link_to_expense
notification_duplicate_records_duplicate_source_attempt
```

---

## Pipeline 2 — Transaction lifecycle

### Required work

```text
TransactionLifecycleCoordinator is canonical source-link writer for expenses.
CreateExpenseRequest legacy fields are mapped.
TransactionEvent.SOURCE_LINKED is written.
Duplicate source-link policy is applied.
```

### Acceptance tests

```text
create_with_pendingReviewId_persists_source_link
create_with_scannedReceiptId_persists_source_link
create_with_csv_row_persists_import_source_link
created_transaction_event_contains_source_link_summary
duplicate_receipt_create_links_existing_expense_by_policy
manual_duplicate_does_not_create_false_source_link
```

---

## Pipeline 3 — Receipt/OCR/bank statement

### Receipt scan

When receipt creates expense:

```text
SCANNED_RECEIPT / CREATED_FROM
```

When receipt only links to existing expense:

```text
SCANNED_RECEIPT / LINKED_PROOF
```

When matching suggests but does not link:

```text
target = SCANNED_RECEIPT
source = candidate expense? optional
event only may be enough
```

### Bank statement

For each statement transaction review/expense:

```text
BANK_STATEMENT_IMPORT_RUN
BANK_STATEMENT_IMPORT_ITEM
SCANNED_RECEIPT statement receipt
```

Need future:

```text
BankStatementImportRun
BankStatementImportItem
```

Until then:

```text
operationRunId + statement receipt ID + transaction fingerprint hash
```

### Acceptance tests

```text
receipt_created_expense_has_scanned_receipt_source_link
receipt_link_existing_expense_has_linked_proof_source_link
bank_statement_review_has_statement_receipt_source_link
bank_statement_duplicate_item_records_duplicate_source_link_or_import_item
```

---

## Pipeline 10 — Bank API sync

### Required future source entities

Add or align with Pipeline 10 plan:

```text
BankSyncRun
BankTransactionImport
BankAccount
```

Source links for auto-imported expense:

```text
BANK_TRANSACTION / CREATED_FROM
BANK_SYNC_RUN / IMPORTED_FROM
BANK_CONNECTION / ENRICHED_BY or CREATED_FROM
```

Payload fields:

```text
providerId
accountIdHash
providerTransactionIdHash
operationRunId
transactionStatus
bookingDate
valueDate
```

### Low-confidence review

Pending review created from bank transaction:

```text
target = PENDING_REVIEW
sourceEntityType = BANK_TRANSACTION
sourceIdentityKey = external:bank_transaction:{provider}:{hash}
role = REVIEWED_FROM
```

On approval:

```text
promote bank source link from review to expense.
add PENDING_REVIEW / APPROVED_FROM.
```

### Acceptance tests

```text
bank_api_expense_has_provider_transaction_hash_source_link
bank_review_promotes_bank_transaction_link_on_approval
bank_duplicate_exact_provider_id_links_existing_or_records_duplicate
bank_sync_run_id_in_source_link_metadata
```

---

## Pipeline 11 — Email receipt

### Required source chain

```text
EMAIL_RECEIPT_SOURCE -> SCANNED_RECEIPT -> EXPENSE
```

When email receipt creates expense:

```text
target = EXPENSE
source = EMAIL_RECEIPT_SOURCE / CREATED_FROM

target = EXPENSE
source = SCANNED_RECEIPT / LINKED_PROOF
```

When duplicate existing expense is linked:

```text
target = EXPENSE
source = EMAIL_RECEIPT_SOURCE / DUPLICATE_MATCHED
target = EXPENSE
source = SCANNED_RECEIPT / LINKED_PROOF
```

Message ID:

```text
Use messageIdHash, not plaintext emailMessageId.
```

### Acceptance tests

```text
email_created_expense_has_email_source_link
email_created_expense_has_scanned_receipt_link
email_duplicate_existing_expense_has_duplicate_source_link_not_created_source
email_message_id_not_plaintext_in_source_metadata
```

---

## Pipeline 12 — Import/export/accounting

### CSV/JSON import

For every imported row:

```text
target = EXPENSE
source = CSV_IMPORT_ROW or JSON_IMPORT_ROW
sourceIdentityKey = import:csv:{batchId}:row:{rowNumber}
role = IMPORTED_FROM
operationRunId = import run ID
```

### Export

Export schema v3 should include source links.

Export fields:

```json
"sourceLinks": [
  {
    "sourceType": "EMAIL_RECEIPT",
    "sourceEntityType": "EMAIL_RECEIPT_SOURCE",
    "sourceIdentityKey": "local:email_receipt_source:44",
    "externalIdHash": null,
    "role": "CREATED_FROM",
    "status": "ACTIVE",
    "providerId": "amazon",
    "metadata": {}
  }
]
```

For privacy/redacted export:

```text
include hashes and source types
omit local raw source IDs if exporting without source artifacts
include manifest warning for omitted source artifacts
```

### Import roundtrip

On import into fresh DB:

```text
preserve source link metadata where possible
map old local IDs to new IDs if source artifacts are included
otherwise create LEGACY_IMPORTED_SOURCE links
```

### Acceptance tests

```text
csv_import_creates_source_link_for_each_imported_expense
json_export_includes_source_links
json_import_preserves_source_link_metadata
roundtrip_preserves_receipt_source_link_when_receipt_exported
redacted_export_omits_sensitive_source_metadata_but_keeps_hashes
```

---

## Pipeline 4 / 6 — Recurring and planned expenses

### Recurring generated expense

When a recurring rule creates an actual expense:

```text
RECURRING_RULE / GENERATED_FROM
RECURRING_OCCURRENCE / CREATED_FROM
PLANNED_EXPENSE / CREATED_FROM or FULFILLED_FROM
```

### Planned fulfillment

When actual expense fulfills planned row:

```text
target = EXPENSE
source = PLANNED_EXPENSE
role = FULFILLED_FROM
```

If an existing actual expense is linked to occurrence:

```text
target = EXPENSE
source = RECURRING_OCCURRENCE
role = LINKED_PROOF
```

### Acceptance tests

```text
recurring_generated_expense_has_rule_and_occurrence_source_links
planned_fulfilled_expense_has_planned_source_link
manual_expense_linked_to_occurrence_gets_occurrence_source_link
```

---

# 8. Backfill and migration plan

## PR 1 migration

Add table:

```text
entity_source_links
```

Add DAO to `AppDatabase`.

No destructive changes.

---

## PR 2 backfill worker / migration task

Do not do heavy JSON parsing in Room migration. Prefer an idempotent startup/backfill worker:

```text
SourceLinkBackfillWorker
```

Guarded by:

```text
DatabaseWriteBarrier
WorkerExecutionGuard
```

### Backfill sources

#### Expenses with rawNotificationId

```text
Expense.rawNotificationId != null
-> EXPENSE source RAW_NOTIFICATION CREATED_FROM
```

#### Expenses with source only

If no richer local source exists:

```text
Expense.source != null
-> LEGACY_SOURCE_ONLY LEGACY_BACKFILL
sourceIdentityKey = legacy:source:{source}:expense:{expenseId}
status = LEGACY_PARTIAL
```

#### Receipt links

From `receipt_expense_links`:

```text
target EXPENSE
source SCANNED_RECEIPT
role LINKED_PROOF
metadata: receiptLinkId, linkType, confidence
```

From `scanned_receipts.expenseId` if no receipt link exists:

```text
target EXPENSE
source SCANNED_RECEIPT
role LINKED_PROOF
status LEGACY_PARTIAL
```

#### Email receipt sources

If email receipt source -> scanned receipt -> linked expense:

```text
target EXPENSE
source EMAIL_RECEIPT_SOURCE
role CREATED_FROM or LINKED_PROOF
```

#### Pending reviews

For pending reviews:

```text
target PENDING_REVIEW
source RAW_NOTIFICATION or SCANNED_RECEIPT
```

Approved/rejected historical reviews may not map to expense unless events/metadata contain IDs.

#### CSV/bank/group/recurring

If only `Expense.source` is available:

```text
LEGACY_SOURCE_ONLY
status = LEGACY_PARTIAL
```

Later, richer backfills can parse events/import rows once those tables exist.

---

## Backfill safety

Backfill should be:

```text
idempotent
chunked
checkpointed
restartable
```

Use unique index:

```text
targetEntityType + targetEntityId + sourceIdentityKey
```

so repeated runs are safe.

---

# 9. Privacy and redaction rules

## 9.1 Do not store raw external IDs

Never persist plaintext:

```text
emailMessageId
bank provider transaction ID
bank account ID
notification key
order/reference ID
CSV row raw body
bank description/reference
```

Use:

```text
externalIdHash
externalFingerprintHash
accountIdHash
sourceIdentityKey with hash
```

## 9.2 Metadata allowlist

Allowed:

```text
parserId
parserVersion
providerId
confidence
importFormat
importSchemaVersion
statementPageNumber
transactionStatus
bookingDate
valueDate
receiptLinkType
dedupeReason
```

Blocked:

```text
raw text
raw email subject/body/sender
raw bank description/reference
raw notification body/title
token
password
full file path
```

## 9.3 Export

Redacted export can include:

```text
sourceType
sourceEntityType
role
status
external hash
operation run reference
```

but not raw source payload.

---

# 10. Source-link query use cases

Implement repository APIs:

```kotlin
interface SourceProvenanceRepository {
    suspend fun getExpenseProvenance(expenseId: Long): ExpenseProvenance
    suspend fun getTargetLinks(target: SourceTargetRef): List<EntitySourceLink>
    suspend fun findExpensesBySourceIdentity(sourceIdentityKey: String): List<Long>
    suspend fun getSourceChainForExpense(expenseId: Long): SourceChain
}
```

UI/debug use cases:

```text
"Why does this expense exist?"
"Which notification created this?"
"Which receipt/email is linked?"
"Which bank sync run imported this?"
"Which CSV row created this?"
"Was this a duplicate of another source?"
```

---

# 11. Interaction with lifecycle events

## Rule

When source link changes, write both:

```text
EntitySourceLink row
domain lifecycle event
```

Examples:

```text
Expense source link added
-> TransactionEvent(SOURCE_LINKED)

Receipt linked to expense
-> ReceiptEvent(RECEIPT_LINKED_TO_EXPENSE)
-> TransactionEvent(SOURCE_LINKED)
-> EntitySourceLink

Bank transaction duplicate skipped
-> BankTransactionImport outcome
-> PipelineDiagnosticEvent or OperationRunEvent
-> optional EntitySourceLink to matched expense with status DUPLICATE
```

---

# 12. Interaction with dedupe

Source links are not dedupe keys, but they can strengthen dedupe.

Add helper:

```kotlin
interface SourceIdentityDeduper {
    suspend fun findExistingExpenseBySource(payload: SourceLinkPayload): Long?
}
```

Examples:

```text
exact bank provider transaction hash -> existing expense
exact email message hash -> existing receipt/expense
exact CSV import batch+row -> existing expense for idempotent import
```

Do not use weak semantic fingerprints alone as authoritative source identity.

---

# 13. Implementation PR order

## PR 1 — Schema + core source-link infrastructure

Files:

```text
EntitySourceLink.kt
EntitySourceLinkDao.kt
SourceLinkEnums.kt
SourceLinkPayload.kt
SourceLinkWriter.kt
SourceLinkWriterImpl.kt
SafeProvenanceMetadata.kt
SensitiveHashingService integration
AppDatabase migration
```

Acceptance:

```text
can_insert_source_link
duplicate_source_link_insert_is_idempotent
external_id_is_hashed
metadata_rejects_raw_sensitive_keys
```

---

## PR 2 — TransactionLifecycleCoordinator integration

Tasks:

```text
add sourceLinks to CreateExpenseRequest
map legacy fields to SourceLinkPayload
insert source links in create transaction
write SOURCE_LINKED TransactionEvent
apply duplicate source-link policy
```

Acceptance:

```text
create_expense_with_all_legacy_source_fields_writes_links
created_event_and_source_links_commit_atomically
duplicate_receipt_source_links_existing_by_policy
manual_duplicate_does_not_create_false_source_link
```

---

## PR 3 — Pending review promotion

Tasks:

```text
target=PENDING_REVIEW links on review creation
promote links on approval
add PENDING_REVIEW/APPROVED_FROM link to expense
review duplicate path records source outcome
```

Acceptance:

```text
notification_review_has_raw_notification_source_link
review_approval_promotes_source_links_to_expense
receipt_review_approval_promotes_scanned_receipt_link
duplicate_review_approval_records_duplicate_source_outcome
```

---

## PR 4 — Receipt/email integration

Tasks:

```text
ReceiptLinkService writes EntitySourceLink
ReceiptLifecycleCoordinator writes receipt-created source links
Email receipt processing writes email source links
split created vs linked-existing result
```

Acceptance:

```text
receipt_scan_expense_has_scanned_receipt_source_link
receipt_link_existing_has_linked_proof_source_link
email_receipt_created_expense_has_email_source_link
email_duplicate_existing_has_duplicate_matched_source_link
```

---

## PR 5 — Notification integration

Tasks:

```text
RawNotification -> Expense source link
RawNotification -> PendingReview source link
parser failure/review path keeps source link
dedupe duplicate source attempt recorded
```

Acceptance:

```text
notification_auto_accept_source_link
notification_needs_review_source_link
notification_review_approval_chain_raw_notification_to_expense
```

---

## PR 6 — Bank integration source model

Tasks:

```text
add BankSyncRun / BankTransactionImport first if not present
source links for bank-created expense/review
provider transaction ID hash
account hash
sync run ID
duplicate policy
```

Acceptance:

```text
bank_import_expense_has_bank_transaction_source_link
bank_review_approval_promotes_bank_source_link
bank_duplicate_provider_transaction_records_duplicate_source_link
```

---

## PR 7 — CSV/JSON import/export source links

Tasks:

```text
ImportCoordinator writes source links for imported rows
Export schema includes sourceLinks
Import preserves source links
Unsupported source artifacts reported in manifest
```

Acceptance:

```text
csv_import_row_source_link
json_export_contains_source_links
json_import_preserves_source_links
roundtrip_preserves_receipt_source_when_artifact_exported
```

---

## PR 8 — Backfill worker

Tasks:

```text
backfill rawNotificationId
backfill receipt_expense_links
backfill scannedReceipt.expenseId
backfill email receipt source chains
backfill legacy source-only links
idempotent chunks
```

Acceptance:

```text
backfill_expense_rawNotificationId
backfill_receipt_expense_links
backfill_email_receipt_chain
backfill_is_idempotent
backfill_legacy_source_partial
```

---

## PR 9 — Query/UI/debug support

Tasks:

```text
SourceProvenanceRepository
ExpenseProvenance DTO
debug/source chain UI
support diagnostics by correlationId/sourceIdentityKey
```

Acceptance:

```text
expense_provenance_returns_notification_chain
expense_provenance_returns_email_receipt_chain
expense_provenance_returns_bank_sync_chain
```

---

## PR 10 — Static guards

Add script:

```text
scripts/verify_source_provenance_boundaries.py
```

Rules:

```text
CreateExpenseRequest source-link fields must be mapped in CreateExpenseSourceLinkMapper.
No new ExpenseSource enum value without source-link mapping test.
No direct TransactionEvent(SOURCE_LINKED) outside SourceLinkWriter/TransactionLifecycleCoordinator.
No raw external IDs in EntitySourceLink.metadataJson.
No bank/email/notification import path creates expense without SourceLinkPayload.
```

Acceptance:

```text
guard_fails_when_new_ExpenseSource_not_mapped
guard_fails_when_bank_create_request_has_no_source_link
guard_fails_on_plain_emailMessageId_metadata
```

---

# 14. Golden tests

Add or verify:

```text
create_expense_with_rawNotificationId_writes_source_link
create_expense_with_pendingReviewId_writes_source_link
create_expense_with_scannedReceiptId_writes_source_link
create_expense_with_emailReceiptSourceId_writes_source_link
create_expense_with_csv_batch_row_writes_source_link
create_expense_with_externalFingerprint_stores_hash_not_plaintext

expense_created_event_and_source_link_atomic
source_link_duplicate_insert_idempotent
source_link_metadata_rejects_raw_sensitive_keys

pending_review_creation_writes_source_link
review_approval_promotes_source_links
receipt_link_existing_writes_source_link
email_duplicate_existing_writes_duplicate_source_link
bank_transaction_review_promotes_source_link_on_approval

backfill_raw_notification_links
backfill_receipt_links
backfill_email_receipt_links
backfill_legacy_source_only_links
backfill_idempotent

json_export_includes_source_links
json_import_preserves_source_links
redacted_export_keeps_hashes_not_raw_external_ids
```

---

# 15. Agent implementation checklist

Before coding, run:

```bash
rg "rawNotificationId" app/src/main/java
rg "pendingReviewId" app/src/main/java
rg "scannedReceiptId" app/src/main/java
rg "emailReceiptSourceId" app/src/main/java
rg "csvImportBatchId" app/src/main/java
rg "csvRowNumber" app/src/main/java
rg "externalFingerprint" app/src/main/java
rg "SOURCE_LINKED" app/src/main/java
rg "ReceiptExpenseLink" app/src/main/java
rg "sourceFingerprint" app/src/main/java
rg "EmailReceiptSource" app/src/main/java
rg "BANK_API_SYNC" app/src/main/java
rg "CSV_IMPORT" app/src/main/java
rg "RECURRING_GENERATED" app/src/main/java
rg "GROUP_EXPENSE" app/src/main/java
rg "TransactionEvent\\(" app/src/main/java
```

---

# 16. Definition of done

```text
1. EntitySourceLink table exists with privacy-safe identity model.

2. Every CreateExpenseRequest source-link field is either persisted as EntitySourceLink or removed/deprecated.

3. Expense creation writes source links atomically with CREATED event.

4. Pending review approval promotes original source links to the expense.

5. Receipt/email/notification/bank/import paths create source links.

6. Duplicate decisions have source-link policy:
   link existing, record duplicate, or do not link.

7. ReceiptExpenseLink remains functional receipt relation, but generic source links are also written.

8. External IDs are hashed, never stored plaintext.

9. Export schema includes source links.

10. Import recreates source links where possible.

11. Backfill worker creates legacy source links idempotently.

12. Debug/support query can answer:
    "Why does this expense exist?"
```

---

# 17. Sources used

- Baseline commit:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- `CreateExpenseRequest.kt` — current request already accepts multiple source-link fields but comments say most are not persisted:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt

- `ExpenseSource.kt` — current high-level source enum:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/ExpenseSource.kt

- `TransactionLifecycleCoordinator.kt` — current creation flow maps source to `Expense.source` and only persists `rawNotificationId` directly:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `TransactionEvent.kt` — current transaction lifecycle event log supports metadata/source but not universal source-link rows:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt

- `LifecycleEventType.kt` — already has `SOURCE_LINKED`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt

- `Expense.kt` — current expense has `rawNotificationId` and `source`, but no generic source-link model:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt

- `PendingReview.kt` — current review links raw notification/scanned receipt, but not generic provenance:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/PendingReview.kt

- `ReceiptExpenseLink.kt` — current receipt↔expense functional link table:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptExpenseLink.kt

- `EmailReceiptSource.kt` — current email receipt source entity:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt

- `BankConnection.kt` — current bank connection entity lacks account/transaction-level provenance links:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/BankConnection.kt