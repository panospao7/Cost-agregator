# Universal Gap Closure Implementation Plan

Target head assumed: `3a197d3` / latest closure-pass state.

Goal: close or explicitly defer remaining universal multipipeline gaps so the tracker can move from `PARTIAL+` to either:

```text
FIXED
```

or

```text
DEFERRED_DESIGN with explicit rationale
```

---

# Summary recommendation

Do **not** make one giant PR.

Use this sequence:

```text
PR 1 — Strict raw storage privacy for receipt/email
PR 2 — Email lifecycle unification + conflict outcomes
PR 3 — Receipt link lifecycle-safe category assignment
PR 4 — Recurring unlink/retry hardening
PR 5 — Universal diagnostics expansion
PR 6 — Export/import hardening
PR 7 — Money/currency quality propagation
PR 8 — Barrier/read guard/static guard cleanup
```

If you want to move to per-pipeline work faster, PRs 1–4 should be done first. PRs 5–7 can become pipeline-specific follow-ups if tracker marks them honestly as `PARTIAL`.

---

# PR 1 — Strict raw storage privacy for receipt/email

## Gap addressed

Current issue:

```text
Camera/gallery OCR raw text can be inserted first, then sanitized later.
If app crashes between insert and coordinator update, raw OCR remains.
```

Also:

```text
Email source metadata still stores raw sender/subject/messageId.
```

## Target contract

```text
Raw sensitive text must be sanitized before first DB persistence.
No raw OCR/email body/subject is ever written when mode is METADATA_ONLY or DO_NOT_STORE.
```

## Files to touch

```text
ReceiptRepository.kt
ReceiptLifecycleCoordinator.kt
PrivacySettings.kt
PrivacySettingsRepositoryImpl.kt
EmailReceiptSource.kt
EmailReceiptDao.kt
EmailReceiptIngestionService.kt
ExportAnonymizer.kt
DataRetentionWorker.kt
```

## Implementation steps

### Step 1 — Add email metadata storage policy

Add:

```kotlin
enum class EmailReceiptStorageMode {
    STORE_RAW,
    STORE_REDACTED,
    STORE_METADATA_ONLY,
    DO_NOT_STORE
}
```

Add to `PrivacySettings`:

```kotlin
val emailReceiptStorageMode: EmailReceiptStorageMode = EmailReceiptStorageMode.STORE_REDACTED
```

Recommended default:

```text
STORE_REDACTED
```

not `STORE_RAW`.

### Step 2 — Centralize sanitizers

Create:

```text
domain/privacy/RawContentSanitizer.kt
```

Functions:

```kotlin
fun sanitizeRawOcr(text: String?, mode: RawStorageMode): String?
fun sanitizeNotificationText(text: String?, mode: RawStorageMode): String?
fun sanitizeEmailSubject(subject: String?, mode: EmailReceiptStorageMode): String?
fun sanitizeEmailSender(sender: String?, mode: EmailReceiptStorageMode): String?
fun sanitizeEmailMessageId(messageId: String?, mode: EmailReceiptStorageMode): String?
```

Policy:

```text
STORE_RAW → original
STORE_REDACTED → redacted text / hashed sender/messageId if needed
STORE_METADATA_ONLY → null or stable hash only
DO_NOT_STORE → null
```

### Step 3 — Sanitize before first receipt insert

In `ReceiptRepository.processReceipt()`:

Current pattern:

```text
OCR
parse
insert ScannedReceipt(rawOcrText = ocrResult.fullText)
```

Change to:

```text
resolve rawOcrStorageMode
sanitize ocrResult.fullText before ScannedReceipt(...)
insert sanitized value only
```

Do this for:

```text
successful OCR/parse
parse failure
OCR failure placeholder
manual fallback receipt
bank statement receipt if it stores raw text
```

### Step 4 — Keep coordinator sanitizer as defense-in-depth

Keep `ReceiptLifecycleCoordinator` sanitization, but treat it as repair layer, not primary guarantee.

### Step 5 — Sanitize EmailReceiptSource before insert

Before constructing `EmailReceiptSource`, apply:

```text
sender policy
subject policy
messageId policy
fingerprint policy
```

For dedupe, do not rely on raw `messageId`; use:

```text
canonicalMessageIdHash
contentFingerprint
```

### Step 6 — Expand anonymizer/retention

`ExportAnonymizer` should clear/hash:

```text
email_receipt_sources.emailSubject
email_receipt_sources.emailSender
email_receipt_sources.emailMessageId
```

`DataRetentionWorker` should purge email metadata after configured retention if policy requires.

## Tests

```text
camera_receipt_DO_NOT_STORE_never_persists_raw_ocr
camera_receipt_METADATA_ONLY_inserts_empty_or_null_rawOcrText
camera_receipt_parse_failure_respects_rawOcrStorageMode
email_STORE_REDACTED_redacts_subject_and_sender
email_METADATA_ONLY_hashes_or_nulls_messageId
email_DO_NOT_STORE_persists_no_sender_subject_messageId
redacted_backup_removes_email_source_metadata
data_retention_purges_email_source_metadata_after_cutoff
```

## Definition of done

```text
- No first-write raw OCR when mode is not STORE_RAW.
- Email source metadata follows privacy policy.
- Retention/export anonymizer covers email source fields.
```

---

# PR 2 — Email lifecycle unification + conflict outcomes

## Gaps addressed

Current issues:

```text
EmailReceiptIngestionService still owns a separate lifecycle path.
EmailReceiptSource insert conflict is logged, not returned as outcome.
Duplicate existing expense now improved, but link/conflict behavior still needs hardening.
Coordinator email link result may be unchecked.
```

## Target contract

```text
There is exactly one email receipt lifecycle owner.
Provider parser may parse, but coordinator owns:
dedupe → receipt save → source save → review/create/link → events → side effects.
```

## Files to touch

```text
EmailReceiptIngestionService.kt
ReceiptLifecycleCoordinator.kt
EmailReceiptDao.kt
EmailReceiptSource.kt
ReceiptLinkService.kt
TransactionLifecycleCoordinator.kt
PendingReviewDao.kt
ReceiptEvent.kt
```

## Implementation steps

### Step 1 — Define unified result

Create:

```kotlin
sealed interface EmailReceiptLifecycleResult {
    data class CreatedExpense(
        val receiptId: Long,
        val expenseId: Long
    ) : EmailReceiptLifecycleResult

    data class LinkedExistingExpense(
        val receiptId: Long,
        val expenseId: Long
    ) : EmailReceiptLifecycleResult

    data class DuplicateReceipt(
        val existingReceiptId: Long,
        val reason: String
    ) : EmailReceiptLifecycleResult

    data class NeedsReview(
        val receiptId: Long,
        val reviewId: Long,
        val reason: String
    ) : EmailReceiptLifecycleResult

    data class Rejected(
        val reason: String
    ) : EmailReceiptLifecycleResult
}
```

### Step 2 — Add `EmailReceiptSourceInsertResult`

DAO helper:

```kotlin
sealed interface EmailReceiptSourceInsertResult {
    data class Inserted(val sourceId: Long) : EmailReceiptSourceInsertResult
    data class DuplicateMessage(val existingSource: EmailReceiptSource) : EmailReceiptSourceInsertResult
    data class DuplicateFingerprint(val existingSource: EmailReceiptSource) : EmailReceiptSourceInsertResult
    data class Conflict(val reason: String) : EmailReceiptSourceInsertResult
}
```

DAO methods:

```kotlin
getByMessageIdHash(...)
getByContentFingerprint(...)
insertOrIgnore(...)
```

Service helper:

```kotlin
suspend fun insertOrResolve(source: EmailReceiptSource): EmailReceiptSourceInsertResult
```

### Step 3 — Split message identity from content identity

Fields:

```text
messageIdHash
contentFingerprint
providerOrderId nullable
```

Dedupe order:

```text
messageIdHash
providerOrderId
contentFingerprint
semantic receipt duplicate
existing expense duplicate
```

### Step 4 — Thin `EmailReceiptIngestionService`

Make it only:

```text
detect provider
parse provider body into ParsedEmailReceipt
call ReceiptLifecycleCoordinator.ingestParsedEmailReceipt(...)
map coordinator result to UI result
```

No direct:

```text
ScannedReceipt insert
EmailReceiptSource insert
Expense creation
Receipt link
```

unless the service becomes the explicit single lifecycle owner. Prefer coordinator ownership.

### Step 5 — Coordinator owns transaction

Inside coordinator:

```kotlin
database.withTransaction {
    writeBarrier.check(...)
    dedupe
    insert ScannedReceipt
    insert EmailReceiptSource or return Duplicate
    if high confidence:
        create expense with SideEffectMode.DEFER
        handle Created or DuplicateSkipped
        link receipt to expense
        if link fails throw
    else:
        create PendingReview
    write ReceiptEvent / diagnostics
}
dispatch side effects after commit
```

Important:

```text
If expense create succeeds but link fails, rollback expense.
If DuplicateSkipped(existingExpenseId), link to existing expense and return LinkedExistingExpense.
```

### Step 6 — Confidence routing

Policy:

```text
confidence >= 0.90 → auto-create/link
0.50 <= confidence < 0.90 → pending review
< 0.50 → save receipt only or rejected parse
```

## Tests

```text
email_service_delegates_to_coordinator
same_message_id_returns_DuplicateReceipt
same_content_different_message_id_returns_DuplicateReceipt
email_source_insert_conflict_returns_domain_duplicate
existing_manual_expense_links_receipt_not_duplicate
existing_notification_expense_links_receipt_not_duplicate
expense_created_but_link_fails_rolls_back_expense
low_confidence_email_creates_pending_review
coordinator_email_link_result_failure_rolls_back
```

## Definition of done

```text
- One email lifecycle owner.
- No ignored insertOrIgnore result.
- Existing expense duplicate is success/link-existing.
- Link failure cannot leave orphan expense.
```

---

# PR 3 — ReceiptLinkService lifecycle-safe category assignment

## Gap addressed

Current issue:

```text
ReceiptLinkService directly calls expenseDao.updateCategory(...)
```

This bypasses transaction lifecycle.

## Target contract

```text
Receipt linking may detect a category suggestion.
Only transaction lifecycle may mutate expense category.
```

## Files to touch

```text
ReceiptLinkService.kt
TransactionLifecycleCoordinator.kt
TransactionSideEffectDispatcher.kt
ExpenseCategoryAssignmentPort.kt new
ReceiptLifecycleCoordinator.kt
```

## Implementation options

### Preferred option — port

Create:

```kotlin
interface ExpenseCategoryAssignmentPort {
    suspend fun assignCategoryFromReceiptItems(
        expenseId: Long,
        categoryId: Long,
        source: String,
        actor: String? = null
    ): Result<Unit>
}
```

Implementation:

```kotlin
class TransactionLifecycleCategoryAssignmentPort @Inject constructor(
    private val coordinator: TransactionLifecycleCoordinator
) : ExpenseCategoryAssignmentPort {
    override suspend fun assignCategoryFromReceiptItems(...) =
        coordinator.updateCategory(
            expenseId = expenseId,
            newCategoryId = categoryId,
            source = "RECEIPT_ITEM_CATEGORY",
            actor = actor
        )
}
```

Inject port into `ReceiptLinkService`.

### Circular dependency concern

If Hilt cycle occurs:

```text
ReceiptLinkService → ExpenseCategoryAssignmentPort → TransactionLifecycleCoordinator
TransactionLifecycleCoordinator → receipt dependencies?
```

Then use async command table:

```text
expense_category_assignment_commands
```

ReceiptLinkService inserts command after link; worker/coordinator processes commands through transaction lifecycle.

But port is simpler if graph allows it.

## Tests

```text
receipt_item_category_assignment_writes_transaction_UPDATED_event
receipt_item_category_assignment_triggers_budget_side_effect
receipt_link_service_no_longer_calls_expenseDao_updateCategory
receipt_link_still_succeeds_if_category_assignment_fails_noncritical
```

## Definition of done

```text
- No direct expenseDao.updateCategory in ReceiptLinkService.
- Category assignment has TransactionEvent.UPDATED.
- Static lifecycle bypass guard passes.
```

---

# PR 4 — Recurring unlink/retry hardening

## Gaps addressed

Current issues:

```text
unlinkExpenseFromOccurrence does not reopen PlannedExpense or reminders.
FAILED_TRANSIENT has no retry path.
Failure metadata is thin.
```

## Target contract

```text
Link and unlink are inverse lifecycle operations.
Reminder failures have explicit terminal/retry policy.
```

## Files to touch

```text
RecurringLifecycleCoordinator.kt
RecurringReminderDelivery.kt
RecurringReminderDeliveryDao.kt
PlannedExpenseDao.kt
BillReminderWorker.kt
RecurringLifecycleEvent.kt
```

## Implementation steps

### Step 1 — Add delivery metadata

Add columns:

```text
attemptCount INTEGER NOT NULL DEFAULT 0
lastAttemptAt INTEGER
failureReason TEXT
retryAt INTEGER
```

Migration required.

### Step 2 — Define statuses

```text
SCHEDULED
CLAIMED
SENT
FAILED_PERMISSION
FAILED_TRANSIENT
DISMISSED
SNOOZED
SUPPRESSED_PAID
```

Policy:

```text
FAILED_PERMISSION → terminal until permission changes
FAILED_TRANSIENT → retry if retryAt <= now and attemptCount < max
```

### Step 3 — Update pending query

Pending query should include:

```text
SCHEDULED due
SNOOZED due
FAILED_TRANSIENT due for retry
```

and join:

```text
occurrence.status = PLANNED
```

### Step 4 — Improve `markReminderFailed`

```kotlin
markReminderFailed(id, reason, retryable)
```

If retryable:

```text
status = FAILED_TRANSIENT
retryAt = now + backoff
attemptCount += 1
```

If permission:

```text
status = FAILED_PERMISSION
retryAt = null
```

Write `REMINDER_DELIVERY_FAILED`.

### Step 5 — Make unlink transactional

`unlinkExpenseFromOccurrence(expenseId)` should transactionally:

```text
find paid occurrence by linkedExpenseId
occurrence → PLANNED
plannedExpense linkedActualExpenseId → null
plannedExpense status → PLANNED
openSourceOccurrenceKey restored
reminder deliveries recreated or unsuppressed according to policy
write OCCURRENCE_UNLINKED
write PLANNED_REOPENED
```

### Tests

```text
delete_actual_expense_reopens_occurrence
delete_actual_expense_reopens_planned_expense
delete_actual_expense_reschedules_or_unsuppresses_reminder
failed_permission_reminder_not_returned_due
failed_transient_reminder_returned_after_retryAt
failed_transient_stops_after_max_attempts
unlink_operation_is_atomic
```

## Definition of done

```text
- Link/unlink lifecycle is symmetric.
- Reminder retry/terminal states are explicit.
- Due query only returns active planned obligations.
```

---

# PR 5 — Universal diagnostics expansion

## Gap addressed

Current issue:

```text
PipelineDiagnosticEvent exists but is mostly notification-focused.
```

## Target contract

```text
Every important pipeline has durable outcome events for success, drop, skip, failure.
```

## Files to touch

```text
PipelineDiagnosticEvent.kt
PipelineDiagnosticEventDao.kt
PipelineDiagnosticLogger.kt new
NotificationProcessingPipeline.kt
ReceiptLifecycleCoordinator.kt
EmailReceiptIngestionService.kt
RecurringLifecycleCoordinator.kt
BillReminderWorker.kt
BudgetMonitor.kt
DatabaseBackupRepositoryImpl.kt
ExportOptionsViewModel.kt
Bank sync coordinator if present
PrivacyGate / CompositePrivacyGate
```

## Implementation steps

### Step 1 — Extend schema

Add nullable fields:

```text
entityType
entityId
secondaryEntityId
exceptionClass
exceptionMessage
restoreMode
privacyCapability
privacyDecision
dbWriteAttempted
dbWriteSucceeded
metadataJson
```

### Step 2 — Create typed logger

```kotlin
interface PipelineDiagnosticLogger {
    suspend fun record(event: PipelineDiagnosticEventInput)
}
```

Use enum-like constants:

```text
PIPELINE_NOTIFICATION
PIPELINE_RECEIPT
PIPELINE_EMAIL_RECEIPT
PIPELINE_RECURRING
PIPELINE_BUDGET
PIPELINE_BACKUP_RESTORE
PIPELINE_EXPORT_IMPORT
PIPELINE_PRIVACY
PIPELINE_BANK_SYNC
```

### Step 3 — Add diagnostics to high-value exits

Minimum events:

```text
receipt validation failed
receipt OCR failed
receipt parse failed
receipt saved
receipt link failed
email duplicate
email source conflict
email linked existing
email review created
reminder claimed
reminder sent
reminder failed
backup restore started/succeeded/failed
export started/succeeded/failed
privacy denied final decision
```

## Tests

```text
receipt_parse_failure_writes_diagnostic
email_source_conflict_writes_diagnostic
reminder_failure_writes_diagnostic
export_failure_writes_diagnostic
privacy_denied_writes_one_final_diagnostic_or_audit
```

## Definition of done

```text
- User-visible failures have durable diagnostics.
- Logs are no longer the only debugging source.
```

---

# PR 6 — Export/import hardening

## Gaps addressed

Current issues:

```text
No true snapshot table.
Encryption helper not wired.
Receipt links missing.
Import preview/row errors incomplete.
```

## Files to touch

```text
ExportOptionsViewModel.kt
ExportDataRepository.kt
DeterministicExpenseExportPager.kt
ExpenseExportMapper.kt
ExportTransaction.kt
ReceiptExpenseLinkDao.kt
JsonExpenseImporter.kt
CsvExpenseImporter.kt
ExpenseImportCoordinator.kt
ImportPreview models
```

## Implementation steps

### Step 1 — Add export snapshot table

Entity:

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
SELECT :operationId, row_number, id
FROM expenses
WHERE date >= :start AND date < :end
ORDER BY date ASC, id ASC
```

Stream by joining snapshot rows.

Cleanup in `finally`.

### Step 2 — Wire encryption

UI option:

```text
Encrypt export
```

Flow:

```text
write plaintext temp
encrypt to final
delete plaintext temp
```

If encryption fails:

```text
delete both temp/final partials
diagnostic failed
```

### Step 3 — Add receipt links

Add to JSON:

```kotlin
receiptLinks: List<ReceiptLinkExportRef>
```

CSV can include:

```text
receiptIds
receiptLinkTypes
primaryReceiptId
```

Import should recreate links with `ReceiptLinkService`.

### Step 4 — Import preview

Add:

```kotlin
ImportPreview(
    totalRows,
    validRows,
    duplicateRows,
    errorRows,
    unsupportedColumns,
    warnings
)
```

Do not import until user confirms.

### Step 5 — Row-level errors

```kotlin
ImportRowError(rowNumber, code, field, message)
```

## Tests

```text
export_snapshot_excludes_insert_during_export
export_rowCount_matches_snapshot_rows
encrypted_export_deletes_plaintext_temp
json_export_includes_receipt_links
json_import_recreates_receipt_links
csv_import_preview_reports_unsupported_columns
roundtrip_json_preserves_totals_and_receipt_links
```

## Definition of done

```text
- Export is snapshot-stable.
- Encrypted export path is real.
- Receipt links are represented.
- Import has preview and row-level errors.
```

---

# PR 7 — Money/currency quality propagation

## Gaps addressed

Current issues:

```text
weekly/daily safe totals incomplete
historical vs latest basis unclear
dashboard warnings incomplete
forecast confidence not fully reduced
category percentages hide partial data
budget-vs-actual normalization needs verification
```

## Files to touch

```text
ExchangeRateDao.kt
CurrencyConverter.kt
MultiCurrencyRepository.kt
TotalsAggregationEngine.kt
AnalyticsRepository.kt
DashboardContractsAdapter.kt
DashboardDataProvider.kt
ComputeDashboardWidgetsUseCase.kt
ForecastInputAssembler.kt
SynthesisEngine.kt
BudgetVsActualEngine.kt
```

## Implementation steps

### Step 1 — Separate rate APIs

DAO:

```kotlin
getLatestRateByValidDate(from, to)
getMostRecentlyUpdatedRate(from, to)
getRateAsOf(from, to, atMillis)
```

Make callers explicit.

### Step 2 — Safe weekly/daily totals

Replace empty deprecated methods with:

```text
normalized per-row historical conversion
or MoneyAggregate bucket conversion with explicit basis
```

Return quality:

```text
MoneyAggregate
AnalyticsDataQuality
```

### Step 3 — Dashboard DTO quality

Carry:

```text
isPartial
warningMessage
failedTransactionCount
sourceBuckets
```

into:

```text
SpendingSummary
CategoryBreakdown
PeriodTotal
Forecast widget
Budget widget
```

### Step 4 — Forecast confidence

In `SynthesisEngine`:

```text
confidence -= input.dataQuality.confidencePenalty
warnings += conversion warnings
```

### Step 5 — Category percentages

If parent/category aggregate partial:

```text
percentageBasedOnConvertibleOnly = true
warning shown
```

### Tests

```text
weekly_totals_mixed_currency_safe_non_empty
daily_totals_mixed_currency_safe_non_empty
historical_total_uses_expense_date_rate
dashboard_summary_preserves_partial_warning
forecast_confidence_reduced_by_missing_rate
category_breakdown_warns_when_partial
budget_vs_actual_uses_normalized_budget_and_spend
```

## Definition of done

```text
- No dashboard/analytics raw mixed-currency sums.
- Partial conversion state reaches UI models.
- Historical/current conversion basis is explicit.
```

---

# PR 8 — Barrier/read guard/static guard cleanup

## Gaps addressed

Current issues:

```text
DatabaseWriteBarrier injected but not always used.
No DatabaseReadBarrier.
Static guards may give false confidence.
```

## Files to touch

```text
DatabaseWriteBarrier.kt
DatabaseReadBarrier.kt new
TransactionLifecycleCoordinator.kt
ReceiptLifecycleCoordinator.kt
RecurringLifecycleCoordinator.kt
EmailReceiptIngestionService.kt
ExportDataRepository.kt
ExportOptionsViewModel.kt
scripts/guards/*
build.gradle.kts
```

## Implementation steps

### Step 1 — Expand barrier

Current:

```kotlin
checkWritesAllowed(operation: String)
```

Add:

```kotlin
check(operation: DatabaseWriteOperation)
```

with:

```text
name
category
allowDuringBackupExport
allowDuringRestore
allowDuringRestartRequired
```

Keep old method temporarily deprecated.

### Step 2 — Migrate core coordinators

Replace direct:

```kotlin
restoreMaintenanceMode.isWritesAllowed()
```

with:

```kotlin
writeBarrier.check(DatabaseWriteOperation(...))
```

in:

```text
TransactionLifecycleCoordinator
ReceiptLifecycleCoordinator
ReceiptLinkService
RecurringLifecycleCoordinator
EmailReceiptIngestionService
Budget/forecast/planned repositories
```

### Step 3 — Add read barrier

```kotlin
DatabaseReadBarrier.check(operation)
```

Use in:

```text
Export
Import preview
Backup UI reads
Debug snapshots
```

### Step 4 — Static guards

Add/verify:

```text
restore_write_barrier_guard
transaction_lifecycle_bypass_guard
raw_money_guard
privacy_cloud_guard
raw_storage_guard
```

Guard examples:

```text
No direct expenseDao.updateCategory outside TransactionLifecycleCoordinator or approved DEFERRED_DESIGN allowlist.
No ScannedReceipt(rawOcrText = ocrResult.fullText) unless sanitized.
No cloud provider HTTP call without EffectiveCloudAiPolicy / PreparedCloudPayload.
No deprecated raw SUM aggregate calls in dashboard/analytics.
```

## Tests

```text
barrier_blocks_write_during_restore
read_barrier_blocks_export_during_restart_required
core_coordinators_call_write_barrier
guard_fails_on_fake_direct_expenseDao_updateCategory
guard_fails_on_fake_raw_ocr_insert
```

## Definition of done

```text
- Barrier is actual contract, not only injected.
- Reads/exports blocked during restore/restart-required.
- Static guards catch regressions.
```

---

# Final closure criteria

You can mark universal multipipeline issues closed only when:

```text
1. Raw OCR/email/notification storage is sanitized before first DB write.
2. Email receipt ingestion has one lifecycle owner.
3. Email source conflicts are domain outcomes.
4. Receipt link category assignment no longer bypasses transaction lifecycle, or is explicitly DEFERRED_DESIGN.
5. Recurring link/unlink and reminder failure/retry policies are explicit.
6. Diagnostics exist beyond notification for major failure paths.
7. Export/import has snapshot/encryption/receipt-link/preview coverage, or remaining parts are explicitly Pipeline 12.
8. Money quality reaches dashboard/forecast/category/budget models.
9. DatabaseWriteBarrier/ReadBarrier are used by core coordinators and export/import.
10. Guard tests prevent regressions.
```

---

# Recommended tracker updates while implementing

Until PRs land, mark:

```text
Restore/write barrier                   PARTIAL+
Worker guard + run logging              MOSTLY_FIXED
Privacy/redaction/raw storage           PARTIAL+
Money/currency quality                  PARTIAL
Transaction lifecycle                   PARTIAL+ / DEFERRED_DESIGN if receipt category remains
Receipt lifecycle/link ownership        PARTIAL+
Recurring planned/actual reconciliation MOSTLY_FIXED
Diagnostics/drop reasons/events         PARTIAL
Import/export schema/roundtrip          PARTIAL+
DAO insert conflict/timestamps          PARTIAL+
```

After PRs 1–4:

```text
Safe to move mostly to per-pipeline issues.
```

After PRs 5–8:

```text
Safe to mark universal contracts FIXED, except explicitly deferred design items.
```