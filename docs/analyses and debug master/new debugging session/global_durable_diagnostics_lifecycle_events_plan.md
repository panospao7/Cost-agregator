# Global Durable Diagnostics / Lifecycle Events Implementation Plan

Baseline commit: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`

Universal rule:

```text
Every meaningful pipeline attempt must leave a durable, privacy-safe trail:
RECEIVED / ATTEMPTED -> terminal outcome
CREATED / UPDATED / DELETED / LINKED -> lifecycle event
DROPPED / SKIPPED / DUPLICATE / BLOCKED / FAILED / CANCELLED -> diagnostic event
SIDE_EFFECT_STARTED / SIDE_EFFECT_FAILED / SIDE_EFFECT_COMPLETED -> side-effect event
```

Affected pipelines:

```text
P1 Notification capture
P2 Transaction lifecycle
P3 Receipt/OCR/bank statement
P4 Recurring/reminders
P6 Budget/forecast/cashflow
P7 Backup/restore
P8 Privacy/AI
P9 Workers
P10 Bank integration
P11 Email receipt ingestion
P12 Import/export/accounting
```

---

## 0. Existing event infrastructure

Current code already has several partial logs:

```text
pipeline_diagnostic_events
transaction_events
receipt_events
recurring_lifecycle_events
privacy_audit_events
background_job_runs
source_stats_events
warranty_lifecycle_events
```

Important current entities:

- `PipelineDiagnosticEvent`
  - generic table with `pipeline`, `stage`, `outcome`, `dropReason`, `message`, `entityType`, `entityId`, `metadataJson`, `exceptionClass`, `elapsedMs`, etc.

- `TransactionEvent`
  - immutable expense lifecycle/audit log.
  - `expenseId` is nullable, intentionally no FK, so events survive deletes.

- `ReceiptEvent`
  - immutable receipt lifecycle/audit log.
  - `receiptId` is nullable, intentionally no FK.

- `RecurringLifecycleEvent`
  - occurrence/reminder/planned lifecycle trail.

- `BackgroundJobRun`
  - worker run table, currently weak: limited statuses, no typed skip reason, cancellation can leave `RUNNING`.

This plan does **not** throw these away. It standardizes and extends them.

---

# 1. Target model

## 1.1 Event categories

Use four categories.

### A. Diagnostic events

Used when:

```text
operation was attempted
operation was dropped/skipped/blocked
operation failed before entity exists
operation did not mutate domain state
operation was cancelled
```

Primary table:

```text
pipeline_diagnostic_events
```

Examples:

```text
notification received
notification dropped by privacy
email parse failed before receipt row exists
bank sync transaction duplicate skipped
export blocked by restore mode
worker skipped by privacy
```

---

### B. Domain lifecycle events

Used when a domain entity changes state.

Existing tables stay authoritative:

```text
transaction_events
receipt_events
recurring_lifecycle_events
warranty_lifecycle_events
privacy_audit_events
```

Future domain-specific tables can be added for:

```text
budget_lifecycle_events
bank_sync_events
export_import_events
```

But do not create a new table unless query needs justify it.

---

### C. Operation-run events

Used for batch/multi-step operations.

Initial table to add:

```text
operation_runs
operation_run_events
```

Use for foreground/batch operations that are not WorkManager jobs:

```text
backup
restore
export
import
bank sync
bank statement import
email batch import
bulk receipt import
```

Workers continue to use `background_job_runs`, but can share the same taxonomy.

---

### D. Side-effect events

Used after DB commit for things like:

```text
budget recalculation
recurring matching
notification delivery
AI recommendation generation
receipt matching
dashboard cache invalidation
```

Short-term: record side effects in `pipeline_diagnostic_events`.

Long-term optional table:

```text
side_effect_events
```

Do **not** block the primary transaction because a side effect failed.

---

# 2. Universal event contract

Every emitted event should have these conceptual fields.

```kotlin
data class AppEventEnvelope(
    val eventId: String,
    val correlationId: String,
    val causationId: String?,
    val pipeline: String,
    val stage: String,
    val eventType: String,
    val outcome: EventOutcome,
    val severity: EventSeverity,
    val reasonCode: String?,
    val entityType: String?,
    val entityId: Long?,
    val sourceType: String?,
    val sourceIdHash: String?,
    val actor: String?,
    val occurredAt: Long,
    val elapsedMs: Long?,
    val metadata: SafeEventMetadata,
    val exceptionClass: String?,
    val exceptionMessageSafe: String?
)
```

Do not require every existing table to store every field immediately. The envelope is the **API contract** used by writers.

---

## 2.1 Required enums

Add package:

```text
domain/diagnostics
```

Add:

```kotlin
enum class AppPipeline {
    NOTIFICATION,
    TRANSACTION,
    RECEIPT,
    RECURRING,
    BUDGET,
    FORECAST,
    BACKUP_RESTORE,
    PRIVACY,
    WORKER,
    BANK,
    EMAIL,
    EXPORT_IMPORT
}

enum class EventOutcome {
    RECEIVED,
    ATTEMPTED,
    COMPLETED,
    CREATED,
    UPDATED,
    DELETED,
    LINKED,
    UNLINKED,
    DUPLICATE,
    NEEDS_REVIEW,
    DROPPED,
    SKIPPED,
    BLOCKED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    CANCELLED,
    SIDE_EFFECT_STARTED,
    SIDE_EFFECT_COMPLETED,
    SIDE_EFFECT_FAILED
}

enum class EventSeverity {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}
```

Add common reason codes:

```kotlin
enum class DiagnosticReasonCode {
    PRIVACY_DENIED,
    PRIVACY_FAIL_CLOSED,
    RESTORE_BLOCKED,
    WRITE_BARRIER_DENIED,
    READ_BARRIER_DENIED,
    FILTER_REJECTED,
    BLOCKED_PACKAGE,
    DUPLICATE,
    VALIDATION_FAILED,
    PARSER_FAILED,
    OCR_FAILED,
    MISSING_RATE,
    STALE_RATE,
    PERMISSION_DENIED,
    NETWORK_UNAVAILABLE,
    PROVIDER_DISABLED,
    TOKEN_INVALID,
    CANCELLED_BY_SYSTEM,
    CANCELLED_BY_USER,
    SIDE_EFFECT_EXCEPTION,
    UNKNOWN_ERROR
}
```

Use enum names in DB, not free-form status strings.

---

# 3. Event boundary rules

## 3.1 State transition rule

If an event describes a committed domain state transition, write it in the **same DB transaction** as the state change.

Examples:

```text
Expense created -> insert expense + TransactionEvent(CREATED) in same transaction
Receipt saved -> insert/update receipt + ReceiptEvent(RECEIPT_SAVED) in same transaction
Occurrence paid -> update occurrence + RecurringLifecycleEvent(OCCURRENCE_PAID) in same transaction
```

If the transaction rolls back, the lifecycle event must roll back too.

---

## 3.2 Attempt/failure rule

If an event describes an attempt or a failure before domain state exists, use a diagnostic or operation event outside the domain transaction.

Examples:

```text
CREATE_ATTEMPTED before expense insert
email PARSE_FAILED before receipt row exists
notification DROPPED_PRIVACY before raw row exists
export BLOCKED_RESTORE before export file exists
```

---

## 3.3 Duplicate rule

Duplicates are terminal outcomes and must be durable.

For duplicates with existing domain entity:

```text
outcome = DUPLICATE
entityType = attempted entity type
duplicateEntityId / matchedEntityId in metadata
reasonCode = DUPLICATE
```

Transaction duplicate can also be a `TransactionEvent.CREATE_DUPLICATE_SKIPPED`.

Receipt/email/bank duplicates can be `PipelineDiagnosticEvent` plus domain-specific event if a receipt/source row exists.

---

## 3.4 Side-effect rule

Side effects run after DB commit and must record their own outcome.

```text
primary transaction commits
side effect starts
side effect succeeds/fails
failure is durable but does not roll back primary transaction
```

Example metadata:

```json
{
  "sideEffect": "recurring_match",
  "expenseId": 123,
  "source": "manual_create",
  "retryable": false
}
```

---

## 3.5 Restore-blocked rule

When DB writes are blocked by restore/restart-required mode, do **not** attempt normal Room event inserts.

Use:

```text
MaintenanceSafeDiagnosticSink
```

This can be:

```text
Timber short-term
DataStore ring buffer medium-term
RestoreJournal / backup_restore_events for backup-specific failures
```

---

# 4. Schema plan

## PR 1 — Enhance `pipeline_diagnostic_events`

Current table is useful but too free-form. Add columns:

```kotlin
val eventId: String?
val correlationId: String?
val causationId: String?
val severity: String?
val reasonCode: String?
val sourceType: String?
val sourceIdHash: String?
val isTerminal: Boolean?
val metadataSchemaVersion: Int?
```

Recommended migration:

```sql
ALTER TABLE pipeline_diagnostic_events ADD COLUMN eventId TEXT;
ALTER TABLE pipeline_diagnostic_events ADD COLUMN correlationId TEXT;
ALTER TABLE pipeline_diagnostic_events ADD COLUMN causationId TEXT;
ALTER TABLE pipeline_diagnostic_events ADD COLUMN severity TEXT;
ALTER TABLE pipeline_diagnostic_events ADD COLUMN reasonCode TEXT;
ALTER TABLE pipeline_diagnostic_events ADD COLUMN sourceType TEXT;
ALTER TABLE pipeline_diagnostic_events ADD COLUMN sourceIdHash TEXT;
ALTER TABLE pipeline_diagnostic_events ADD COLUMN isTerminal INTEGER;
ALTER TABLE pipeline_diagnostic_events ADD COLUMN metadataSchemaVersion INTEGER NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS index_pipeline_diagnostic_events_correlationId
ON pipeline_diagnostic_events(correlationId);

CREATE INDEX IF NOT EXISTS index_pipeline_diagnostic_events_reasonCode
ON pipeline_diagnostic_events(reasonCode);

CREATE INDEX IF NOT EXISTS index_pipeline_diagnostic_events_entity
ON pipeline_diagnostic_events(entityType, entityId);
```

Do **not** make `eventId` non-null in first migration. Backfill later.

---

## PR 2 — Add `operation_runs`

Use for backup/restore/export/import/bank/email batch operations.

```kotlin
@Entity(
    tableName = "operation_runs",
    indices = [
        Index(value = ["operationType", "startedAt"]),
        Index(value = ["status"]),
        Index(value = ["correlationId"], unique = true)
    ]
)
data class OperationRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val correlationId: String,
    val operationType: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val actor: String?,
    val rowsTotal: Int?,
    val rowsProcessed: Int,
    val rowsSucceeded: Int,
    val rowsFailed: Int,
    val rowsSkipped: Int,
    val warningCount: Int,
    val errorCount: Int,
    val metadataJson: String?,
    val errorSummary: String?
)
```

Statuses:

```text
RUNNING
SUCCESS
PARTIAL_SUCCESS
SKIPPED
FAILED_RETRYABLE
FAILED_FINAL
CANCELLED
STALE_ABORTED
```

---

## PR 3 — Add `operation_run_events`

```kotlin
@Entity(
    tableName = "operation_run_events",
    indices = [
        Index(value = ["operationRunId"]),
        Index(value = ["correlationId"]),
        Index(value = ["eventType"]),
        Index(value = ["occurredAt"])
    ]
)
data class OperationRunEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationRunId: Long?,
    val correlationId: String,
    val causationId: String?,
    val operationType: String,
    val stage: String,
    val eventType: String,
    val outcome: String,
    val severity: String,
    val reasonCode: String?,
    val occurredAt: Long,
    val entityType: String?,
    val entityId: Long?,
    val metadataJson: String?,
    val exceptionClass: String?,
    val exceptionMessage: String?
)
```

Do not add hard FK initially. Audit rows should survive cleanup.

---

## PR 4 — Enhance `background_job_runs`

Current `BackgroundJobRun` status is too weak.

Add:

```kotlin
val correlationId: String?
val statusReason: String?
val cancellationReason: String?
val metadataJson: String?
val errorClass: String?
```

Also allow status:

```text
SKIPPED
CANCELLED
STALE_ABORTED
```

Stop encoding reason inside status like:

```text
SKIPPED_PRIVACY_FOO
```

Use:

```text
status = SKIPPED
statusReason = PRIVACY_DENIED
```

---

# 5. Writers and APIs

## 5.1 Diagnostic event writer

Add:

```kotlin
interface DiagnosticEventWriter {
    suspend fun emit(event: DiagnosticEvent)
}
```

```kotlin
data class DiagnosticEvent(
    val pipeline: AppPipeline,
    val stage: String,
    val outcome: EventOutcome,
    val severity: EventSeverity = EventSeverity.INFO,
    val reasonCode: DiagnosticReasonCode? = null,
    val entityType: String? = null,
    val entityId: Long? = null,
    val sourceType: String? = null,
    val sourceIdHash: String? = null,
    val correlationId: String = CorrelationIds.newId(),
    val causationId: String? = null,
    val metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    val exception: Throwable? = null,
    val elapsedMs: Long? = null,
    val isTerminal: Boolean = false
)
```

Implementation maps to `PipelineDiagnosticEvent`.

Important:

```text
DiagnosticEventWriter must sanitize metadata.
It must never store raw notification text, raw email body, raw OCR, raw bank description, API token, or full file path.
```

---

## 5.2 Lifecycle event writers

Add small focused writers instead of every coordinator manually building entities.

```kotlin
interface TransactionLifecycleEventWriter {
    suspend fun write(event: TransactionLifecycleEvent)
}

interface ReceiptLifecycleEventWriter {
    suspend fun write(event: ReceiptLifecycleEvent)
}

interface RecurringLifecycleEventWriter {
    suspend fun write(event: RecurringLifecycleEventModel)
}
```

These writers are thin wrappers around existing DAOs.

They should:

```text
take typed event model
sanitize metadata
serialize consistently
use TimeProvider
```

---

## 5.3 Operation run recorder

```kotlin
interface OperationRunRecorder {
    suspend fun start(
        operationType: String,
        actor: String?,
        metadata: SafeEventMetadata = SafeEventMetadata.empty()
    ): OperationRunHandle
}

interface OperationRunHandle {
    val runId: Long
    val correlationId: String

    suspend fun event(
        stage: String,
        outcome: EventOutcome,
        reasonCode: DiagnosticReasonCode? = null,
        severity: EventSeverity = EventSeverity.INFO,
        metadata: SafeEventMetadata = SafeEventMetadata.empty()
    )

    suspend fun increment(
        processed: Int = 0,
        succeeded: Int = 0,
        failed: Int = 0,
        skipped: Int = 0,
        warnings: Int = 0,
        errors: Int = 0
    )

    suspend fun success()
    suspend fun partialSuccess(summary: String?)
    suspend fun failedFinal(reason: String, error: Throwable?)
    suspend fun failedRetryable(reason: String, error: Throwable?)
    suspend fun cancelled(reason: String?)
}
```

Use it for:

```text
backup/restore
export
import
bank sync
bank statement import
email batch import
bulk receipt import
```

---

## 5.4 Safe metadata builder

Add:

```kotlin
class SafeEventMetadata private constructor(
    private val values: Map<String, Any?>
) {
    fun toJson(): String
}
```

Allowed value policy:

```text
String values max length 256
no raw body fields
no token fields
no full filesystem paths
IDs should be raw only if app-internal DB IDs
external IDs must be hashed
```

Add helpers:

```kotlin
SafeEventMetadata.builder()
    .put("expenseId", expenseId)
    .putHashed("notificationKey", notificationKey)
    .putHashed("emailMessageId", messageId)
    .putRedacted("rawText")
```

Add static blocked keys:

```text
body
rawBody
rawText
rawOcrText
prompt
token
accessToken
refreshToken
authorization
password
fullPath
iban
accountNumber
```

---

# 6. Correlation ID propagation

## Goal

A single user/input operation should be traceable across tables.

Examples:

```text
notification -> raw_notification -> pending_review -> approved expense -> dashboard side effects
receipt scan -> OCR -> receipt row -> pending review -> expense link
email receipt -> email source -> receipt -> expense/review
bank sync run -> transaction item -> expense/review
export run -> snapshot -> file write
```

## Add

```kotlin
object CorrelationIds {
    fun newId(): String = UUID.randomUUID().toString()
}
```

## Pass through request models

Add optional `correlationId` to important requests:

```kotlin
CreateExpenseRequest
ReceiptProcessOptions / EmailReceiptData
NotificationProcessingRequest
BankSyncRequest
ExportRequest
ImportRequest
WorkerGuardRequest
```

If missing, generate at boundary.

---

# 7. Pipeline instrumentation plan

## Pipeline 1 — Notification capture

### Required events

At listener entry:

```text
pipeline=NOTIFICATION
stage=listener
outcome=RECEIVED
metadata: packageName, notificationKeyHash, postTime
```

Early exits:

```text
DROPPED / PRIVACY_DENIED
DROPPED / RESTORE_BLOCKED
DROPPED / BLOCKED_PACKAGE
DROPPED / FILTER_REJECTED
DROPPED / DUPLICATE
CANCELLED / CANCELLED_BY_SYSTEM
```

Parser outcomes:

```text
parser_deterministic_success
parser_ai_fallback_used
parser_failed
needs_review_created
expense_created
duplicate_skipped
```

### Implementation tasks

```text
NotificationCaptureService:
  generate correlationId at onNotificationPosted
  record RECEIVED before extraction if writes allowed
  use safe sink if restore blocks DB writes
  record all early drops

NotificationProcessingPipeline:
  return sealed outcome with correlationId
  emit terminal diagnostic for every outcome
```

### Acceptance tests

```text
notification_privacy_drop_writes_diagnostic_or_safe_sink
notification_filter_drop_writes_diagnostic
notification_parser_failed_writes_terminal_event
notification_cancelled_writes_cancelled_event
each_notification_received_has_terminal_outcome_or_safe_sink_record
```

---

## Pipeline 2 — Transaction lifecycle

### Required events

Already has `TransactionEvent`, but enforce:

```text
CREATE_ATTEMPTED
CREATE_VALIDATION_FAILED
CREATE_DUPLICATE_SKIPPED
CREATE_INSERT_CONFLICT
CREATED
UPDATED
DELETED
SOURCE_LINKED
SIDE_EFFECT_FAILED
```

Add missing:

```text
CREATE_BLOCKED_RESTORE
UPDATE_VALIDATION_FAILED
UPDATE_BLOCKED_RESTORE
DELETE_BLOCKED_RESTORE
SIDE_EFFECT_STARTED
SIDE_EFFECT_COMPLETED
```

### Boundary rule

```text
CREATED/UPDATED/DELETED in same transaction as DB mutation.
CREATE_ATTEMPTED/VALIDATION_FAILED/DUPLICATE can be outside main mutation transaction.
```

### Known fixes

```text
Review duplicate precheck must write CREATE_DUPLICATE_SKIPPED.
Restore-blocked create should produce safe diagnostic.
Direct maintenance writes should emit diagnostic or lifecycle event if user-visible.
```

### Acceptance tests

```text
manual_create_writes_attempted_and_created
validation_failed_create_writes_durable_failure_event
review_duplicate_approval_writes_duplicate_event
update_validation_failed_writes_event
side_effect_failure_writes_SIDE_EFFECT_FAILED
```

---

## Pipeline 3 — Receipt/OCR/bank statement

### Required events

Front door:

```text
INPUT_RECEIVED
VALIDATION_PASSED
VALIDATION_FAILED
OCR_STARTED
OCR_COMPLETED
OCR_FAILED
PARSE_STARTED
PARSED
PARSE_FAILED
DUPLICATE_DETECTED
RECEIPT_SAVED
REVIEW_CREATED
EXPENSE_CREATED
RECEIPT_LINKED_TO_EXPENSE
MATCH_ATTEMPTED
MATCH_NOT_FOUND
MATCH_SUGGESTED
AUTO_MATCHED
ASSET_DELETE_FAILED
```

### Implementation tasks

```text
ReceiptLifecycleCoordinator:
  insert receipt state event in same transaction as receipt insert/update
  emit parse failure even when no structured fields found
  move pending review creation after duplicate detection and emit REVIEW_CREATED

ReceiptSideEffectDispatcher:
  emit MATCH_ATTEMPTED and terminal match outcome

BankStatementLifecycleProcessor:
  use OperationRunRecorder
  one event per parsed transaction
```

### Acceptance tests

```text
ocr_failure_insert_and_event_are_atomic
parse_failure_writes_PARSE_FAILED_event
duplicate_receipt_writes_duplicate_event
review_created_event_after_dedupe
match_not_found_writes_event
```

---

## Pipeline 4 — Recurring/reminders

### Required events

Add/standardize:

```text
RULE_CREATED
RULE_UPDATED
RULE_DEACTIVATED
RULE_DELETED
OCCURRENCE_GENERATED
OCCURRENCE_STATUS_CHANGED
OCCURRENCE_PAID
OCCURRENCE_REOPENED
PLANNED_GENERATED
PLANNED_FULFILLED
REMINDER_SCHEDULED
REMINDER_CLAIMED
REMINDER_SENT
REMINDER_SUPPRESSED_PAID
REMINDER_FAILED_PERMISSION
REMINDER_FAILED_TRANSIENT
REMINDER_DISMISSED
REMINDER_SNOOZED
```

### Implementation tasks

```text
RecurringRuleLifecycleCoordinator owns rule events.
RecurringLifecycleCoordinator owns occurrence/reminder events.
BillReminderWorker records delivery outcome through coordinator.
Payment suppression emits REMINDER_SUPPRESSED_PAID.
```

### Acceptance tests

```text
rule_update_writes_event
payment_writes_occurrence_paid_and_planned_fulfilled
reminder_claim_writes_event
payment_suppresses_claimed_delivery_and_writes_event
reminder_permission_failure_writes_event
```

---

## Pipeline 6 — Budget/forecast/cashflow

### Missing table decision

Either:

Option A:

```text
Use pipeline_diagnostic_events for budget/forecast events.
```

Option B:

```text
Add budget_lifecycle_events and forecast_lifecycle_events.
```

Recommended short-term: Option A.

### Required events

```text
BUDGET_CREATED
BUDGET_UPDATED
BUDGET_DELETED_OR_ARCHIVED
BUDGET_STATUS_COMPUTED
BUDGET_CONVERSION_FAILED
BUDGET_ALERT_EVALUATED
BUDGET_ALERT_SENT
BUDGET_ALERT_SKIPPED
FORECAST_GENERATED
FORECAST_PARTIAL
FORECAST_CONVERSION_FAILED
PLANNED_EXPENSE_CREATED
PLANNED_EXPENSE_FULFILLED
```

### Acceptance tests

```text
budget_conversion_failure_writes_event
budget_alert_skipped_due_unknown_conversion_writes_event
forecast_partial_due_missing_rate_writes_event
planned_expense_created_writes_event
```

---

## Pipeline 7 — Backup/restore

### Use `OperationRunRecorder`

Operation types:

```text
BACKUP_EXPORT
RESTORE_COSTBACKUP
RESTORE_LEGACY_DB
RESET_DATABASE
STARTUP_RESTORE_RECOVERY
ASSET_RESTORE
```

### Required stages

Backup:

```text
STARTED
MAINTENANCE_ENTERED
WORKERS_DRAINED
SNAPSHOT_CREATED
MANIFEST_WRITTEN
ENCRYPTED
COMPLETED
FAILED
```

Restore:

```text
STARTED
BUNDLE_VALIDATED
STAGED_DB_CREATED
STAGED_DB_VERIFIED
SAFETY_BACKUP_CREATED
LIVE_DB_SWAPPED
LIVE_DB_VERIFIED
ASSETS_RESTORING
ASSET_RESTORED
ASSET_FAILED
RESTART_REQUIRED
ROLLBACK_STARTED
ROLLBACK_COMPLETED
ROLLBACK_FAILED
```

### Important

If live DB is unsafe, events may need to be written to:

```text
RestoreJournal
DataStore safe sink
external operation log file
```

Do not depend only on Room after DB swap.

### Acceptance tests

```text
restore_wrong_password_writes_failed_operation_event
restore_rollback_failure_writes_critical_event_or_journal
startup_recovery_failure_keeps_operation_record
asset_restore_failure_writes_asset_event
backup_success_writes_completed_operation_run
```

---

## Pipeline 8 — Privacy/AI

### Existing

`PrivacyAuditEvent` exists.

### Required enhancements

Every cloud call should record:

```text
capability checked
decision
provider
model
purpose
redactionApplied
payloadHash
rawTextIncluded=false/true
rawImageUploaded=false/true
correlationId
```

### New event type

Optionally add:

```text
cloud_ai_call_events
```

Short-term: store in `PrivacyAuditEvent.context` with typed safe JSON.

### Acceptance tests

```text
privacy_denied_cloud_call_writes_audit
cloud_call_audit_has_provider_model_payload_hash
redacted_call_records_redactionApplied_true
audit_context_rejects_raw_prompt
```

---

## Pipeline 9 — Workers

### Required changes

Enhance `BackgroundJobRun`.

Worker statuses:

```text
RUNNING
SUCCESS
SKIPPED
RETRY
FAILED
CANCELLED
STALE_ABORTED
```

Skip reasons:

```text
NO_WORK
PRIVACY_DENIED
RESTORE_BLOCKED
SPEC_DISABLED
RUNTIME_DISABLED
PERMISSION_DENIED
FRESH_ARTIFACT
```

### Implementation tasks

```text
WorkerExecutionGuard:
  creates run row at start when DB writes allowed
  finalizes CANCELLED before rethrowing CancellationException
  receives WorkerRunContext counters
  records typed skip reason
```

For restore-blocked runs where DB writes are blocked:

```text
record to MaintenanceSafeDiagnosticSink
```

### Acceptance tests

```text
cancelled_worker_updates_run_CANCELLED
location_no_work_logs_SKIPPED_NO_WORK
bill_worker_records_notificationsSent
receipt_matching_records_autoMatched_suggested
stale_running_recovery_marks_STALE_ABORTED
```

---

## Pipeline 10 — Bank

### Required operation model

Use:

```text
operation_runs: BANK_SYNC
operation_run_events: per page / per transaction summary
bank_transaction_import table: per provider transaction durable outcome
```

### Required events

Connection:

```text
BANK_CONNECTION_STARTED
BANK_CONNECTION_COMPLETED
BANK_CONNECTION_FAILED
TOKEN_REFRESH_STARTED
TOKEN_REFRESHED
TOKEN_REFRESH_FAILED
REAUTH_REQUIRED
DISCONNECTED
```

Sync:

```text
SYNC_STARTED
PAGE_FETCHED
TRANSACTION_RECEIVED
TRANSACTION_CLASSIFIED
TRANSACTION_IMPORTED
TRANSACTION_REVIEW_CREATED
TRANSACTION_DUPLICATE_SKIPPED
TRANSACTION_FAILED
SYNC_COMPLETED
SYNC_PARTIAL
SYNC_FAILED
```

### Acceptance tests

```text
sync_run_records_started_completed_counts
each_bank_transaction_has_import_outcome
token_refresh_failure_writes_event
low_confidence_bank_transaction_writes_review_event
duplicate_bank_transaction_writes_duplicate_event
```

---

## Pipeline 11 — Email

### Required events

```text
EMAIL_RECEIVED
PROVIDER_DETECTED
PARSER_SELECTED
PARSE_FAILED
VALIDATION_FAILED
DUPLICATE_MESSAGE_ID
DUPLICATE_CONTENT
RECEIPT_SAVED
EMAIL_SOURCE_LINKED
REVIEW_CREATED
EXPENSE_CREATED
LINKED_EXISTING_EXPENSE
SIDE_EFFECT_FAILED
```

### Implementation tasks

```text
EmailReceiptIngestionService:
  emits pre-save diagnostics for parse/detection
  delegates all lifecycle state events to coordinator
  no duplicate side-effect dispatch

ReceiptLifecycleCoordinator.processEmailReceipt:
  records source insert conflict outcome
  records review route
```

### Acceptance tests

```text
parse_failure_writes_safe_diagnostic
email_source_conflict_unresolved_writes_failure_and_rolls_back
low_confidence_email_writes_review_created_event
existing_expense_link_writes_link_event_not_create_event
```

---

## Pipeline 12 — Export/import/accounting

### Use operation runs

Operation types:

```text
EXPENSE_EXPORT
EXPENSE_IMPORT
ACCOUNTING_EXPORT
PDF_ACCOUNTANT_REPORT
```

### Required events

Export:

```text
EXPORT_STARTED
PRIVACY_CHECK_PASSED
PRIVACY_DENIED
SNAPSHOT_CREATED
VALIDATION_FAILED
FILE_WRITE_STARTED
FILE_WRITE_COMPLETED
ENCRYPTION_STARTED
ENCRYPTION_COMPLETED
EXPORT_COMPLETED
EXPORT_FAILED
```

Import:

```text
IMPORT_STARTED
FILE_PARSED
ROW_VALIDATED
ROW_IMPORTED
ROW_DUPLICATE_SKIPPED
ROW_FAILED
IMPORT_COMPLETED
IMPORT_PARTIAL
IMPORT_FAILED
```

### Acceptance tests

```text
export_denied_by_privacy_writes_event
export_snapshot_created_writes_event_with_row_count
export_failed_writes_failed_run
import_row_validation_failed_writes_row_event
import_completed_has_counts_checksum
```

---

# 8. Privacy and metadata safety

## Non-negotiable rule

No diagnostic/lifecycle metadata may contain:

```text
raw notification title/text/body
raw OCR text
raw email body/subject/sender unless policy allows
raw bank description/reference
AI prompt
API tokens
authorization headers
full local file paths
account numbers / IBAN
```

## Implementation

Add:

```kotlin
class EventMetadataSanitizer
```

Responsibilities:

```text
allowlisted keys
max string length
hash external IDs
drop dangerous keys
redact exception messages when needed
```

## Tests

```text
metadata_sanitizer_drops_rawText
metadata_sanitizer_drops_prompt
metadata_sanitizer_hashes_externalId
diagnostic_writer_never_persists_disallowed_keys
```

---

# 9. Migration / compatibility plan

## Phase 1

Add columns/tables nullable-compatible.

```text
pipeline_diagnostic_events: add new nullable columns
background_job_runs: add nullable columns
add operation_runs
add operation_run_events
```

No old code breaks.

## Phase 2

Introduce writers but keep direct DAO calls temporarily.

## Phase 3

Replace manual event construction in each coordinator.

## Phase 4

Add static guard.

---

# 10. Static guard plan

Add script:

```text
scripts/verify_event_writers.py
```

Rules:

```text
No direct PipelineDiagnosticEvent(...) outside DiagnosticEventWriter except tests/migrations.
No direct TransactionEvent(...) outside TransactionLifecycleEventWriter/TransactionLifecycleCoordinator except tests/migrations.
No direct ReceiptEvent(...) outside ReceiptLifecycleEventWriter/ReceiptLifecycleCoordinator except tests/migrations.
No direct BackgroundJobRun(...) outside WorkerRunLogger except tests/migrations.
```

This prevents future inconsistent event rows.

---

# 11. Debug/support UI plan

Add a simple diagnostics screen or debug repository.

Views:

```text
Recent pipeline diagnostics
Recent operation runs
Recent failed events
Events by correlationId
Events by entity
Worker runs
Privacy audit decisions
```

Queries:

```text
getDiagnosticsByCorrelationId(correlationId)
getOperationRunWithEvents(operationRunId)
getEventsForEntity(entityType, entityId)
getRecentFailures(limit)
```

This is not required for correctness, but makes the new event system useful.

---

# 12. Recommended PR order

## PR 1 — Taxonomy and safe metadata

Files:

```text
domain/diagnostics/EventOutcome.kt
domain/diagnostics/EventSeverity.kt
domain/diagnostics/DiagnosticReasonCode.kt
domain/diagnostics/SafeEventMetadata.kt
domain/diagnostics/EventMetadataSanitizer.kt
```

Acceptance:

```text
metadata sanitizer tests pass
enum names stable
```

---

## PR 2 — Enhance schema

Files:

```text
PipelineDiagnosticEvent.kt
BackgroundJobRun.kt
new OperationRun.kt
new OperationRunEvent.kt
AppDatabase.kt
migration
DAOs
```

Acceptance:

```text
migration test passes
old rows readable
new indexes exist
```

---

## PR 3 — Writers

Files:

```text
DiagnosticEventWriter.kt
TransactionLifecycleEventWriter.kt
ReceiptLifecycleEventWriter.kt
RecurringLifecycleEventWriter.kt
OperationRunRecorder.kt
WorkerRunLogger.kt
```

Acceptance:

```text
writers sanitize metadata
writers use TimeProvider
writers generate correlationId/eventId
```

---

## PR 4 — Worker run logging fix

Because it is foundational for pipeline 9 and backup/restore.

Tasks:

```text
typed statuses
typed skip reasons
cancellation finalization
run context counters
stale running recovery
```

Acceptance:

```text
cancelled worker is CANCELLED
no-work is SKIPPED/NO_WORK
counts are persisted
```

---

## PR 5 — Pipeline 1/11 front-door diagnostics

Highest missing “input vanished” risk.

Tasks:

```text
notification received/drop terminal events
email received/parser/validation events
safe sink during restore blocked mode
```

Acceptance:

```text
notification privacy drop durable
email parse failure durable
```

---

## PR 6 — Transaction/receipt lifecycle event consistency

Tasks:

```text
review duplicate event
validation failure event
receipt review created event
receipt duplicate cleanup event
side-effect failure event
```

Acceptance:

```text
create/update/delete/review/duplicate all leave events
```

---

## PR 7 — OperationRunRecorder for backup/export/import/bank

Tasks:

```text
backup/restore operation runs
export operation runs
bank sync operation runs
email batch operation runs
statement import operation runs
```

Acceptance:

```text
every batch operation has started + terminal run
per-item failures counted
```

---

## PR 8 — Side-effect event contract

Tasks:

```text
side effect dispatcher records started/completed/failed
transaction/receipt/recurring side effects use it
```

Acceptance:

```text
budget side-effect failure durable
recurring side-effect failure durable
notification delivery failure durable
```

---

## PR 9 — Static guard

Tasks:

```text
block direct event entity construction outside writers/coordinators
require reason for allowlist
```

Acceptance:

```text
CI fails on new direct PipelineDiagnosticEvent(...)
```

---

# 13. Global acceptance criteria

Definition of done:

```text
1. Every pipeline input has either:
   - a domain row + lifecycle event, or
   - a terminal diagnostic event, or
   - a maintenance-safe blocked-operation record.

2. Every mutation of Expense/Receipt/Recurring/Budget/Bank/Import state has a lifecycle event in the same transaction.

3. Every duplicate decision is durable.

4. Every validation failure is durable.

5. Every privacy/restore/read/write-blocked decision is durable or recorded in maintenance-safe sink.

6. Every worker run has a final status: SUCCESS, SKIPPED, RETRY, FAILED, CANCELLED, or STALE_ABORTED.

7. Every batch/long operation has an OperationRun with terminal status and counts.

8. Every side-effect failure is durable.

9. Event metadata is privacy-safe by construction.

10. Correlation ID can trace:
    notification/email/receipt/bank/import/export input
    through review/expense/link/side-effect outcomes.
```

---

# 14. Golden test matrix

Add these global tests:

```text
diagnostic_metadata_never_contains_raw_sensitive_keys
diagnostic_writer_generates_eventId_and_correlationId
operation_run_success_has_terminal_status
operation_run_failure_has_terminal_status
worker_cancellation_finalizes_run
stale_worker_recovery_marks_stale_aborted
transaction_create_attempted_created_correlation_match
transaction_validation_failed_durable
receipt_parse_failed_durable
notification_filter_drop_durable
email_parse_failed_durable
bank_sync_transaction_duplicate_durable
export_privacy_denied_durable
restore_blocked_operation_uses_safe_sink_not_room
side_effect_failure_durable
```

Pipeline-specific tests remain in each pipeline report.

---

# 15. Sources checked

- Commit baseline:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- `PipelineDiagnosticEvent.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/PipelineDiagnosticEvent.kt

- `TransactionEvent.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt

- `ReceiptEvent.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptEvent.kt

- `RecurringLifecycleEvent.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringLifecycleEvent.kt

- `BackgroundJobRun.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/BackgroundJobRun.kt

- `WorkerRunLogger.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt

- `WorkerExecutionGuard.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt

- `AppDatabase.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt