# Multipipeline Gap Double-Check + Enriched Fix Plan

Baseline checked: `e18b1063ee923fbc5e6880f5d05eefa600bd1e93`  
Mode: static GitHub/code inspection, not local Gradle execution.

## Executive verdict

Do **not** mark the universal multipipeline contracts fully fixed yet.

Current best status:

```text
Universal contracts: PARTIAL+ / close but not clean
Safe to continue some per-pipeline work: yes, after hotfixes
Safe to declare universal pass complete: no
```

The latest commit made real improvements, especially:

- receipt OCR sanitization before normal insert;
- richer export schema;
- email `DuplicateSkipped` handling;
- email coordinator link failure check;
- recurring reminder failure events;
- `DatabaseReadBarrier` scaffold;
- forecast data-quality model.

But I found additional remaining gaps and a few possible regressions.

---

# 0. Must-fix hotfixes before more refactor work

These are the highest priority because they can cause crashes, invalid output, privacy leakage, or wrong forecasts.

---

## 0.1 DB schema changed without version bump

### Severity

P0 / Critical

### Evidence

`AppDatabase` still has:

```kotlin
APP_DATABASE_SCHEMA_VERSION = 122
```

But the schema for `pipeline_diagnostic_events` changed inside the same `122.json` identity hash. New fields exist:

```text
entityType
entityId
exceptionClass
exceptionMessage
metadataJson
```

The migration `121_122` now creates the new table shape, but devices/dev databases already migrated to the earlier v122 will not receive those new columns.

### Risk

Existing v122 installs can fail Room schema validation or crash when inserting diagnostics.

### Implementation

1. Bump schema:

```kotlin
const val APP_DATABASE_SCHEMA_VERSION = 123
```

2. Add migration:

```kotlin
val MIGRATION_122_123 = object : Migration(122, 123) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN entityType TEXT")
        db.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN entityId INTEGER")
        db.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN exceptionClass TEXT")
        db.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN exceptionMessage TEXT")
        db.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN metadataJson TEXT")
    }
}
```

3. Register in `ALL_MIGRATIONS`.

4. Regenerate schema `123.json`.

### Tests

```text
migration_122_123_adds_pipeline_diagnostic_columns
migration_122_123_preserves_existing_rows
fresh_123_schema_equals_migrated_122_123_schema
```

---

## 0.2 JSON export is invalid when `source == null`

### Severity

P1 / High

### Evidence

In `ExportOptionsViewModel.writeJsonPageRows()`:

```kotlin
append("\"source\":")
if (tx.source == null) append("null") else append("\"...\",")

append("\"paymentMethod\":")
```

If `tx.source == null`, there is no comma before `paymentMethod`.

Invalid output:

```json
"source":null"paymentMethod":"CARD"
```

### Implementation

Create helper:

```kotlin
private fun StringBuilder.appendJsonNullableStringField(
    name: String,
    value: String?,
    trailingComma: Boolean = true
) {
    append('"').append(name).append("\":")
    if (value == null) append("null")
    else append('"').append(escapeJson(value)).append('"')
    if (trailingComma) append(',')
}
```

Use for all nullable fields.

### Tests

```text
json_export_valid_when_source_null
json_export_valid_when_notes_null
json_export_valid_when_businessPurpose_null
json_export_parses_with_json_parser
```

---

## 0.3 OCR still leaks into pending-review snippet

### Severity

P1 / High privacy

### Evidence

`ReceiptRepository.processReceipt()` sanitizes `rawOcrText`, but for `autoCreateReview` it still stores:

```kotlin
notificationText = ocrResult.fullText.take(200)
```

That bypasses `rawOcrStorageMode`.

### Risk

Even with `DO_NOT_STORE`, raw OCR can persist in `PendingReview.notificationText`.

### Implementation

Add:

```kotlin
private suspend fun sanitizedOcrReviewSnippet(raw: String): String {
    val mode = privacySettingsRepository.getSettings().rawOcrStorageMode
    return when (mode) {
        RawStorageMode.STORE_RAW -> raw.take(200)
        RawStorageMode.STORE_REDACTED ->
            RawContentSanitizer.sanitizeRawOcr(raw.take(200), mode)
        RawStorageMode.STORE_METADATA_ONLY ->
            "Receipt OCR captured; raw text storage disabled."
        RawStorageMode.DO_NOT_STORE ->
            "Receipt OCR captured; raw text not stored."
    }
}
```

Use this for every receipt-origin `PendingReview.notificationText`.

### Tests

```text
receipt_review_DO_NOT_STORE_contains_no_ocr_text
receipt_review_METADATA_ONLY_contains_no_ocr_text
receipt_review_STORE_REDACTED_redacts_pii
```

---

## 0.4 OCR failure path stores exception message

### Severity

P2 / Medium privacy

### Evidence

OCR failure path writes:

```kotlin
rawOcrText = "Scan Failed: ${e.message}"
```

Exception messages can include paths, URI fragments, provider details, or other sensitive data.

### Implementation

Store generic DB text:

```kotlin
rawOcrText = when (mode) {
    STORE_RAW, STORE_REDACTED -> "Scan failed"
    METADATA_ONLY, DO_NOT_STORE -> ""
}
```

Put exception details only in diagnostics, sanitized/truncated.

### Tests

```text
ocr_failure_DO_NOT_STORE_persists_no_exception_message
ocr_failure_writes_diagnostic_exception_class
ocr_failure_diagnostic_message_redacted
```

---

## 0.5 `ScannedReceiptDao.insert()` conflicts are still not consistently handled

### Severity

P1 / High data integrity

### Evidence

`ScannedReceiptDao.insert()` uses `OnConflictStrategy.IGNORE` and KDoc says callers should check returned `0`.

But `ReceiptRepository.processReceipt()` and `saveManualReceiptRecord()` still use the inserted ID without checking `<= 0`.

### Risk

Possible rows with:

```text
PendingReview.scannedReceiptId = 0
ReceiptEvent.receiptId = 0
fake success result with receiptId = 0
```

### Implementation

Create helper:

```kotlin
sealed interface ReceiptInsertResult {
    data class Inserted(val id: Long) : ReceiptInsertResult
    data class Duplicate(val existing: ScannedReceipt?) : ReceiptInsertResult
    data class Conflict(val reason: String) : ReceiptInsertResult
}
```

At minimum:

```kotlin
val id = scannedReceiptDao.insert(receipt)
if (id <= 0) {
    throw ReceiptInsertConflictException(...)
}
```

Better: resolve by `imageHash`, `textFingerprint`, `semanticFingerprint`, or `sourceFingerprint`.

### Tests

```text
scanned_receipt_insert_conflict_does_not_create_review_with_id_zero
manual_receipt_insert_conflict_returns_duplicate
parse_failure_insert_conflict_is_diagnostic_not_success
```

---

## 0.6 Forecast planned-expense conversion is computed but not applied

### Severity

P1 / High financial correctness

### Evidence

`ForecastInputAssembler` computes `normalizedAmount`, but returns:

```kotlin
pe
```

So planned expenses remain in original currency.

### Risk

Forecast can still raw-sum:

```text
100 EUR + 100 USD = 200 homeCurrency
```

### Implementation

Create domain type if needed:

```kotlin
data class ForecastPlannedExpense(
    val id: Long,
    val description: String,
    val amount: Double,
    val currency: String,
    val date: Long,
    val priority: PlannedExpensePriority,
    val status: String,
    val sourceOccurrenceKey: String?
)
```

Or copy existing domain object:

```kotlin
val normalizedPlannedExpenses = deduplicatedPlannedExpenses.mapNotNull { pe ->
    val normalizedAmount = ...
    if (normalizedAmount == null) {
        plannedConversionFailures++
        null
    } else {
        pe.copy(
            amount = normalizedAmount,
            currency = resolvedHomeCurrency
        )
    }
}
```

### Tests

```text
planned_usd_expense_converted_to_home_eur_before_forecast
planned_conversion_failure_excluded_from_forecast
forecast_does_not_raw_sum_mixed_planned_currencies
```

---

## 0.7 `SynthesisEngine` ignores `ForecastDataQuality.confidencePenalty`

### Severity

P1 / High confidence correctness

### Evidence

`ForecastInput` carries `dataQuality`, but `SynthesisEngine.synthesize(input)` drops it and delegates to a function that has no `dataQuality` parameter.

### Implementation

Change:

```kotlin
fun synthesize(input: ForecastInput): FinancialForecast {
    return synthesizeInternal(..., dataQuality = input.dataQuality)
}
```

Then:

```kotlin
val finalConfidence = (baseConfidence - dataQuality.confidencePenalty)
    .coerceIn(0.0, 1.0)
```

Add warning insight:

```text
Forecast excludes N items due to conversion issues.
```

### Tests

```text
forecast_confidence_reduced_when_actual_conversion_partial
forecast_confidence_reduced_when_planned_conversion_partial
forecast_contains_conversion_warning
```

---

# 1. Privacy/raw storage closure

---

## 1.1 Email metadata still stores raw sender/subject/messageId

### Severity

P1 / High privacy

### Evidence

`RawContentSanitizer` has email sanitizer methods, but `EmailReceiptSource` inserts still pass raw:

```text
emailSender = sender
emailSubject = subject
emailMessageId = messageId
```

### Implementation

Add dedicated setting:

```kotlin
enum class EmailReceiptStorageMode {
    STORE_RAW,
    STORE_REDACTED,
    METADATA_ONLY,
    DO_NOT_STORE
}
```

Add to `PrivacySettings`:

```kotlin
val emailReceiptStorageMode: EmailReceiptStorageMode = EmailReceiptStorageMode.STORE_REDACTED
```

Add sanitizer:

```kotlin
fun sanitizeEmailSender(sender: String?, mode: EmailReceiptStorageMode): String?
fun sanitizeEmailSubject(subject: String?, mode: EmailReceiptStorageMode): String?
fun sanitizeEmailMessageId(messageId: String?, mode: EmailReceiptStorageMode): String?
```

Recommended policy:

```text
STORE_RAW       raw
STORE_REDACTED  sender domain/hash, subject "[REDACTED]", messageId hash
METADATA_ONLY   sender domain/hash, subject null, messageId hash
DO_NOT_STORE    sender null, subject null, messageId hash only if needed for dedupe
```

Important: keep dedupe on internal hash fields, not raw message ID.

### Tests

```text
email_source_STORE_REDACTED_does_not_store_raw_subject
email_source_METADATA_ONLY_has_null_subject
email_source_DO_NOT_STORE_has_no_raw_sender_subject_messageId
email_dedupe_still_works_with_hashed_message_id
```

---

## 1.2 DataRetentionWorker scope still incomplete

### Severity

P2 / Medium privacy

### Evidence

`DataRetentionWorker` still has TODO for:

```text
AI artifacts
chat messages
debug diagnostics
email receipt sources
```

### Implementation

Add retention target registry:

```kotlin
interface RetentionTarget {
    val name: String
    suspend fun purge(cutoff: Long, now: Long): RetentionPurgeResult
}
```

Targets:

```text
RawNotificationRetentionTarget
ScannedReceiptOcrRetentionTarget
EmailReceiptSourceRetentionTarget
AiArtifactRetentionTarget
AiChatMessageRetentionTarget
ServiceDiagnosticsRetentionTarget
```

### Tests

```text
retention_purges_email_subject_sender_when_policy_requires
retention_purges_ai_artifact_prompts
retention_purges_ai_chat_messages
retention_records_per_target_counts
```

---

## 1.3 ExportAnonymizer misses email/AI/location/bank metadata

### Severity

P2 / Medium privacy

### Evidence

`ExportAnonymizer` only strips:

```text
scanned_receipts.rawOcrText
raw_notifications raw fields
```

### Implementation

Add registry:

```kotlin
interface ExportRedactionTarget {
    val tableName: String
    fun redact(db: SQLiteDatabase): RedactionResult
}
```

Targets:

```text
EmailReceiptSourceRedactor
AiArtifactRedactor
AiChatRedactor
LocationRedactor
BankTokenRedactor
DebugDiagnosticsRedactor
BusinessNotesRedactor optional
```

### Tests

```text
redacted_export_removes_email_subject
redacted_export_removes_ai_prompts
redacted_export_removes_location_addresses_when_policy_requires
redacted_export_manifest_lists_redacted_tables
```

---

# 2. Email/receipt lifecycle closure

---

## 2.1 Email lifecycle is still split

### Severity

P1 / High architecture

### Evidence

`EmailReceiptIngestionService` still does the full orchestration:

```text
parse provider
dedupe
create ScannedReceipt
saveEmailReceipt
insert EmailReceiptSource
process receipt use case
create expense
link receipt
dispatch side effects
```

It does not delegate fully to `ReceiptLifecycleCoordinator.processEmailReceipt()`.

### Risk

Two different email paths can diverge in:

```text
dedupe
privacy
diagnostics
receipt events
source conflict handling
side effects
link rollback
```

### Implementation

Make `EmailReceiptIngestionService` parser-only:

```kotlin
val parsed = parserRegistry.parse(...)
return receiptLifecycleCoordinator.ingestParsedEmailReceipt(
    raw = EmailReceiptRawInput(...),
    parsed = parsed,
    options = EmailReceiptIngestionOptions(...)
)
```

Coordinator owns:

```text
privacy sanitization
dedupe
ScannedReceipt insert
EmailReceiptSource insert
review/create/link
events
diagnostics
side effects
```

### Tests

```text
email_service_does_not_directly_insert_scanned_receipt
email_service_does_not_directly_create_expense
email_service_delegates_to_coordinator
coordinator_handles_high_confidence_email_auto_create
coordinator_handles_low_confidence_email_review
```

---

## 2.2 Email source conflict can still leave partial state

### Severity

P1 / High data integrity

### Evidence

In service path, `insertOrIgnore()` conflict logs warning and continues.

In coordinator path, if receipt is inserted first and source conflict is discovered later, the transaction may return duplicate normally and commit an orphan receipt.

### Implementation

Create:

```kotlin
sealed interface EmailSourceInsertResult {
    data class Inserted(val id: Long) : EmailSourceInsertResult
    data class Duplicate(val existingReceiptId: Long, val reason: String) : EmailSourceInsertResult
    data class Conflict(val reason: String) : EmailSourceInsertResult
}
```

Rules:

1. Check message/content duplicates before inserting receipt.
2. If conflict detected after insert, throw rollback exception:

```kotlin
class EmailDuplicateRollback(val existingReceiptId: Long) : RuntimeException()
```

Catch outside transaction and return `Duplicate`.

### Tests

```text
email_message_duplicate_does_not_insert_new_receipt
email_fingerprint_duplicate_does_not_insert_new_receipt
email_source_conflict_rolls_back_receipt_insert
email_source_conflict_returns_Duplicate_result
```

---

## 2.3 Email content fingerprint is too collision-prone

### Severity

P2 / Medium

### Evidence

Current fingerprint:

```text
merchant + amount + 5-minute date bucket
```

This can collide for:

```text
same merchant same amount same 5-minute window
multiple Amazon orders
split shipments
subscription renewals
```

### Implementation

Use richer fingerprint:

```text
provider
merchantKey
amount rounded
currency
local date bucket or transaction date
providerOrderId if available
normalized item names hash if available
```

Keep separate:

```text
messageIdHash
contentFingerprint
providerOrderId
```

### Tests

```text
same_content_different_message_id_dedupes
same_merchant_amount_different_order_id_not_deduped
same_merchant_amount_same_day_different_items_not_deduped
```

---

## 2.4 ReceiptLinkService still bypasses transaction lifecycle

### Severity

P1 / High architecture, unless explicitly deferred

### Evidence

`ReceiptLinkService` directly calls:

```kotlin
expenseDao.updateCategory(expenseId, bestCategoryId)
```

Marked `DEFERRED_DESIGN`, but it is still a lifecycle bypass.

### Option A — fix now

Add port:

```kotlin
interface ExpenseCategoryAssignmentPort {
    suspend fun assignFromReceiptItems(
        expenseId: Long,
        categoryId: Long,
        receiptId: Long
    ): Result<Unit>
}
```

Implementation delegates to:

```kotlin
TransactionLifecycleCoordinator.updateCategory(
    source = "RECEIPT_ITEM_CATEGORY"
)
```

If Hilt cycle occurs, use command table:

```text
expense_category_assignment_commands
```

and process post-commit.

### Option B — defer honestly

If not fixing now:

```text
Status: DEFERRED_DESIGN
Reason: requires lifecycle port or item-level budget allocation design
Guard allowlist: ReceiptLinkService.updateCategory only
```

### Tests if fixed

```text
receipt_item_category_assignment_writes_TRANSACTION_UPDATED_event
receipt_item_category_assignment_triggers_budget_side_effect
lifecycle_bypass_guard_blocks_direct_updateCategory_elsewhere
```

---

# 3. Forecast/money quality closure

---

## 3.1 Planned status filter only excludes `FULFILLED`

### Severity

P1 / High forecast correctness

### Evidence

`ForecastInputAssembler` filters:

```kotlin
status != "FULFILLED"
```

`SynthesisEngine` also only excludes `FULFILLED`.

So `SKIPPED` and `CANCELLED` planned expenses can enter forecast.

### Implementation

Use active set:

```kotlin
private val ACTIVE_PLANNED_STATUSES = setOf("PLANNED")
```

Filter both assembler and engine:

```kotlin
it.status in ACTIVE_PLANNED_STATUSES
```

### Tests

```text
cancelled_planned_expense_excluded_from_forecast
skipped_planned_expense_excluded_from_forecast
fulfilled_planned_expense_excluded_from_forecast
planned_expense_included
```

---

## 3.2 ConfirmedOccurrence lacks status/link info

### Severity

P1 / High double-count risk

### Evidence

Assembler maps occurrences to `ConfirmedOccurrence` with:

```text
dueDate
expectedAmount
expectedCurrency
merchant
categoryId
```

No:

```text
status
linkedExpenseId
paidAt
```

`SynthesisEngine` sums all confirmed occurrences in the month as upcoming bills.

### Implementation

Extend domain model:

```kotlin
data class ConfirmedOccurrence(
    val occurrenceId: Long,
    val dueDate: Long,
    val expectedAmount: Double,
    val expectedCurrency: String,
    val merchant: String?,
    val categoryId: Long?,
    val status: String,
    val linkedExpenseId: Long?,
    val paidAt: Long?
)
```

In forecast:

```kotlin
confirmedOccurrences.filter {
    it.status == "PLANNED" &&
    it.dueDate >= startOfToday &&
    it.dueDate < endOfMonthExclusive
}
```

### Tests

```text
paid_occurrence_not_counted_as_future_commitment
skipped_occurrence_not_counted
cancelled_occurrence_not_counted
planned_occurrence_counted
```

---

## 3.3 Same-currency expense creation does not populate identity base snapshot

### Severity

P1/P2 export/currency audit

### Evidence

`Expense` KDoc says identity values are set when expense currency matches home currency. But `TransactionLifecycleCoordinator.createExpense()` only sets `baseAmount/baseCurrency/exchangeRateUsed` when currency differs from home.

### Risk

For same-currency expenses, export can show:

```text
baseAmount = 0.0
baseCurrency = EUR default
exchangeRateUsed = 0.0
```

even if actual amount is non-zero and home currency is USD/GBP/etc.

### Implementation

In create path:

```kotlin
if (expense.currency == homeCurrency) {
    expense = expense.copy(
        baseAmount = expense.amount,
        baseCurrency = homeCurrency,
        exchangeRateUsed = 1.0
    )
} else {
    convertAsOf(...)
}
```

Also ensure historical/legacy rows are backfilled by worker/migration if needed.

### Tests

```text
same_currency_create_sets_baseAmount_to_amount
same_currency_create_sets_exchangeRateUsed_1
same_currency_home_USD_does_not_default_baseCurrency_EUR
export_same_currency_has_correct_base_fields
```

---

## 3.4 Raw mixed-currency grouping still exists in SynthesisEngine

### Severity

P1

### Evidence

`SynthesisEngine` groups planned expenses by currency, logs if multiple, then sums bucket values:

```kotlin
committedPlannedByCurrency.values.sum()
```

Once assembler normalizes planned expenses, this is okay only if all planned expenses are guaranteed home currency. Add assertion.

### Implementation

After normalization, enforce:

```kotlin
require(filteredPlannedExpenses.map { it.currency }.toSet().size <= 1)
```

or if mixed:

```text
exclude mixed rows and add forecast warning
```

### Tests

```text
synthesis_engine_rejects_mixed_planned_currency_input
normalized_forecast_input_has_single_currency
```

---

# 4. Restore/read/write barrier cleanup

---

## 4.1 `DatabaseWriteBarrier` is too thin

### Severity

P1 architecture

### Evidence

Current barrier only wraps `restoreMaintenanceMode.isWritesAllowed()`.

It lacks:

```text
operation category
backup-export policy
restart-required policy
diagnostic/internal allowlist
```

Also many core coordinators inject the barrier but still call `restoreMaintenanceMode` directly.

### Implementation

Add:

```kotlin
enum class DatabaseOperationCategory {
    USER_DATA,
    BACKGROUND_WORK,
    DIAGNOSTIC,
    BACKUP_RESTORE_INTERNAL,
    DEBUG_TOOL
}

data class DatabaseWriteOperation(
    val name: String,
    val category: DatabaseOperationCategory,
    val allowDuringBackupExport: Boolean = false,
    val allowDuringRestore: Boolean = false,
    val allowDuringRestartRequired: Boolean = false
)
```

Barrier:

```kotlin
fun check(operation: DatabaseWriteOperation)
```

Migrate core coordinators:

```text
TransactionLifecycleCoordinator
ReceiptLifecycleCoordinator
ReceiptLinkService
RecurringLifecycleCoordinator
EmailReceiptIngestionService
BudgetRepository
Forecast/Planned repositories
Export/import coordinators
```

### Tests

```text
write_barrier_blocks_user_data_during_restore
write_barrier_blocks_user_data_during_restart_required
backup_restore_internal_write_allowed
core_coordinators_use_write_barrier
```

---

## 4.2 `DatabaseReadBarrier` only blocks restart-required

### Severity

P1/P2

### Evidence

Current read barrier blocks only:

```text
RESTORE_COMPLETE_RESTART_REQUIRED
```

It does not block:

```text
RESTORE_PREPARING
RESTORE_STAGING
RESTORE_SWAPPING
RESTORE_VERIFYING
```

### Implementation

Block all unsafe restore states:

```kotlin
when (mode) {
    NORMAL, BACKUP_EXPORTING -> allowed
    RESTORE_PREPARING,
    RESTORE_STAGING,
    RESTORE_SWAPPING,
    RESTORE_VERIFYING,
    RESTORE_ROLLING_BACK,
    RESTORE_COMPLETE_RESTART_REQUIRED -> blocked unless explicitly allowed
}
```

Use in:

```text
ExportDataRepository
ExportOptionsViewModel
Import preview
Debug snapshot reads
Backup-sensitive screens
```

### Tests

```text
read_barrier_blocks_export_during_restore_swapping
read_barrier_blocks_export_during_restart_required
read_barrier_allows_normal_export
```

---

# 5. Recurring reminder/unlink closure

---

## 5.1 Unlink is not symmetric with link

### Severity

P1 for delete/undo

### Evidence

`linkExpenseToOccurrence()` is atomic and updates occurrence, planned expense, and reminders.

`unlinkExpenseFromOccurrence()` only resets the occurrence and writes an event.

### Implementation

Wrap in transaction:

```text
occurrence → PLANNED
linkedExpenseId/paid fields cleared
plannedExpense.status → PLANNED
plannedExpense.linkedActualExpenseId → null
openSourceOccurrenceKey restored
suppressed/cancelled reminders restored or recreated
OCCURRENCE_UNLINKED event
PLANNED_REOPENED event
```

### Tests

```text
delete_actual_expense_reopens_occurrence
delete_actual_expense_reopens_planned_expense
delete_actual_expense_reschedules_or_unsuppresses_reminder
unlink_is_atomic
```

---

## 5.2 Reminder failure has no retry metadata

### Severity

P2

### Evidence

`RecurringReminderDelivery` lacks:

```text
attemptCount
lastAttemptAt
retryAt
failureReason
```

`markReminderFailed()` sets status only.

### Implementation

Add columns:

```text
attemptCount INTEGER NOT NULL DEFAULT 0
lastAttemptAt INTEGER
retryAt INTEGER
failureReason TEXT
```

Policy:

```text
FAILED_PERMISSION → terminal until permission changes
FAILED_TRANSIENT → retry when retryAt <= now and attemptCount < max
```

Update DAO pending query to include due `FAILED_TRANSIENT`.

### Tests

```text
failed_permission_not_due
failed_transient_due_after_retryAt
failed_transient_not_due_before_retryAt
max_attempts_stops_retry
```

---

# 6. Export/import closure

---

## 6.1 Export snapshot is still not real

### Severity

P1 audit/roundtrip correctness

### Evidence

`ExportDataRepository` claims stable snapshot, but `DeterministicExpenseExportPager` explicitly says it is not a true atomic snapshot.

### Implementation

Add table:

```kotlin
ExportSnapshotRow(
    operationId: String,
    ordinal: Int,
    expenseId: Long
)
```

At export start:

```sql
INSERT INTO export_snapshot_rows(operationId, ordinal, expenseId)
SELECT :op, ROW_NUMBER..., id
FROM expenses
WHERE date >= :start AND date < :end
ORDER BY date, id
```

Stream by joining snapshot rows.

Cleanup in `finally`.

### Tests

```text
export_snapshot_excludes_insert_during_export
export_snapshot_rowCount_matches_rows
export_snapshot_cleanup_after_success
export_snapshot_cleanup_after_failure
```

---

## 6.2 Export encryption exists but is not wired

### Severity

P2/P1 privacy depending UX

### Evidence

`generateExport(encryptExport: Boolean = false)` exists, but comment says encryption is not wired to settings/UI.

### Implementation

Add UI/settings path:

```text
Encrypt export toggle
password/passphrase dialog
call exportDataRepository.encryptExportFile()
```

Flow:

```text
write plaintext temp
encrypt temp → final .enc
delete plaintext
```

### Tests

```text
encrypted_export_deletes_plaintext_temp
encrypted_export_can_decrypt_with_password
wrong_password_fails
```

---

## 6.3 Receipt links are still not exported

### Severity

P1 roundtrip

### Implementation

Extend `ExportTransaction`:

```kotlin
val receiptLinks: List<ReceiptLinkExportRef> = emptyList()
```

DTO:

```kotlin
data class ReceiptLinkExportRef(
    val receiptId: Long,
    val linkType: String,
    val confidence: Double?,
    val source: String?
)
```

JSON field:

```json
"receiptLinks": [...]
```

CSV fields:

```text
ReceiptIds,ReceiptLinkTypes
```

Import recreates links via `ReceiptLinkService`.

### Tests

```text
json_export_includes_receipt_links
json_import_recreates_receipt_links
roundtrip_preserves_receipt_link_count
```

---

## 6.4 Import pipeline still not visible in inspected commit

### Severity

P1 if tracker says roundtrip fixed

### Required components

```text
CsvExpenseImporter
JsonExpenseImporter
ExpenseImportCoordinator
ImportPreview
ImportRowError
ImportSummary
```

Contract:

```text
parse → preview → validate → confirm → import through TransactionLifecycleCoordinator
```

### Tests

```text
json_export_then_import_fresh_db_preserves_totals
csv_export_then_import_fresh_db_preserves_totals
import_preview_reports_invalid_currency
import_duplicate_rows_skipped_with_summary
```

---

# 7. Diagnostics and observability closure

---

## 7.1 Diagnostics still notification/email-heavy, not universal

### Severity

P2/P1 debugging

### Required minimum diagnostics

Add `PipelineDiagnosticEvent` writes for:

```text
receipt OCR failed
receipt parse failed
receipt link failed
email duplicate/source conflict
reminder delivery failed
budget alert sent/skipped/failed
backup restore started/succeeded/failed
export started/succeeded/failed
import row failed
privacy denied final decision
bank sync run started/partial/failed
```

### Tests

```text
receipt_link_failure_writes_diagnostic
email_source_conflict_writes_diagnostic
export_json_failure_writes_diagnostic
privacy_denied_writes_one_final_event
backup_restore_failure_writes_diagnostic
```

---

## 7.2 Notification batch still does not write diagnostics per outcome

### Evidence

`process()` writes diagnostics. `processBatch()` collects outcomes but does not call `writePipelineDiagnosticEvent()` for each item.

### Fix

Inside batch loop:

```kotlin
val outcome = processInternal(...)
writePipelineDiagnosticEvent(outcome, notification.packageName)
```

### Test

```text
notification_batch_writes_diagnostic_for_each_notification
```

---

# 8. Worker guard/logging improvements

---

## 8.1 Worker run logger cannot record row counts from worker body

### Severity

P2 observability

### Evidence

`WorkerExecutionGuard.runGuarded()` calls:

```kotlin
run.success()
```

with no way for block to return counts.

### Implementation

Let block return:

```kotlin
data class WorkerRunSummary(
    val rowsScanned: Int = 0,
    val rowsUpdated: Int = 0,
    val notificationsSent: Int = 0,
    val message: String? = null
)
```

Or pass `WorkerRunContext`:

```kotlin
executionGuard.runGuarded(request) { ctx ->
    ctx.addRowsScanned(n)
    ...
}
```

### Tests

```text
data_retention_records_purge_counts
receipt_matching_records_matched_count
bill_reminder_records_notifications_sent
```

---

## 8.2 Stale running-job recovery still missing

### Implementation

At startup:

```kotlin
val stale = backgroundJobRunDao.getStaleRunningRuns(now - threshold)
stale.forEach { mark STALE_ABORTED }
```

Add DAO partial update:

```sql
UPDATE background_job_runs
SET status='STALE_ABORTED', finishedAt=:now, errorMessage='Process died or worker abandoned'
WHERE id=:id
```

### Tests

```text
startup_marks_old_running_jobs_stale
startup_does_not_mark_recent_running_jobs_stale
```

---

# 9. Static guards to add

Add guard tasks to `check`:

```text
schema_version_guard
raw_storage_guard
email_lifecycle_guard
transaction_lifecycle_bypass_guard
read_write_barrier_guard
raw_money_guard
json_export_validity_test
```

## Guard examples

### Schema version guard

Fail if:

```text
app/schemas/.../122.json identity hash changed compared to previous committed 122
but AppDatabase version still 122
```

### Raw storage guard

Fail if production code contains:

```text
rawOcrText = ocrResult.fullText
notificationText = ocrResult.fullText
rawOcrText = emailBody
emailSubject = subject
emailSender = sender
```

without sanitizer/hash.

### Lifecycle bypass guard

Fail direct calls:

```text
expenseDao.updateCategory
expenseDao.update
expenseDao.insert
expenseDao.delete
```

outside allowlist.

### Export JSON test

Generate rows with all nullable fields null and parse with JSON parser.

---

# Recommended implementation order

## Hotfix PR — required immediately

```text
1. DB v123 migration.
2. JSON export comma bug.
3. OCR review snippet sanitizer.
4. OCR failure generic text.
5. ScannedReceipt insert result checks.
6. Forecast planned conversion actually applied.
7. Forecast confidence penalty applied.
```

## PR 2 — privacy/email closure

```text
1. EmailReceiptStorageMode.
2. Sanitize EmailReceiptSource fields.
3. Email source conflict domain outcomes.
4. Email service delegates lifecycle to coordinator.
```

## PR 3 — transaction/receipt lifecycle closure

```text
1. ReceiptLinkService category assignment port or explicit DEFERRED_DESIGN.
2. Link failure rollback tests.
3. Static lifecycle bypass guard.
```

## PR 4 — forecast/currency closure

```text
1. Active planned statuses only.
2. ConfirmedOccurrence status/link fields.
3. Same-currency identity base snapshot in TransactionLifecycleCoordinator.
4. Dashboard/forecast warning propagation.
```

## PR 5 — barrier closure

```text
1. Rich DatabaseWriteOperation model.
2. Strong DatabaseReadBarrier.
3. Migrate core coordinators.
4. Add barrier guards.
```

## PR 6 — recurring closure

```text
1. Atomic unlink/reopen.
2. Reminder retry metadata.
3. Retry/terminal policy.
```

## PR 7 — export/import closure

```text
1. True export snapshot table.
2. Wire encrypted export.
3. Export/import receipt links.
4. Import preview and row-level errors.
```

## PR 8 — diagnostics/worker observability

```text
1. Diagnostics for receipt/email/recurring/budget/backup/export/privacy/bank.
2. Worker row-count summaries.
3. Stale running-job recovery.
```

---

# Minimum criteria before moving confidently to per-pipeline issues

You can move to per-pipeline work when these are done:

```text
- DB migration fixed.
- JSON export valid.
- OCR raw text cannot leak into PendingReview.
- ScannedReceipt insert conflicts cannot create id=0 references.
- Email source metadata sanitized.
- Email source conflict returns duplicate/conflict, not log-only.
- Forecast planned expenses are normalized or excluded.
- Forecast confidence penalty is applied.
- Same-currency transactions get identity baseAmount/baseCurrency/rate.
```

Everything else can be tracked as pipeline follow-up or `DEFERRED_DESIGN`.

---

# Suggested tracker status after hotfix PR

```text
Restore/write barrier                         PARTIAL+
Worker guard + run logging                    MOSTLY_FIXED
Privacy/redaction/raw storage                 MOSTLY_FIXED
Money/currency quality                        PARTIAL+
Transaction lifecycle                         PARTIAL+ / DEFERRED_DESIGN if receipt category remains
Receipt lifecycle/link ownership              MOSTLY_FIXED
Recurring planned/actual reconciliation       MOSTLY_FIXED
Diagnostics/drop reasons/events               PARTIAL+
Import/export schema/roundtrip                PARTIAL+
DAO insert conflict/timestamps                MOSTLY_FIXED
```

Only mark `FIXED` once tests and guards prove it.

---

# Source files checked

- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/e18b1063ee923fbc5e6880f5d05eefa600bd1e93

- `AppDatabase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

- `ReceiptRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt

- `ReceiptLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt

- `EmailReceiptIngestionService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

- `ReceiptLinkService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt

- `RecurringLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

- `RecurringReminderDeliveryDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringReminderDeliveryDao.kt

- `ForecastInputAssembler.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt

- `SynthesisEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt

- `ExportOptionsViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt

- `ExportDataRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/data/repository/ExportDataRepository.kt

- `DatabaseWriteBarrier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt

- `DatabaseReadBarrier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseReadBarrier.kt