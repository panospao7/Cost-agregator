# Commit evaluation — `e18b1063ee923fbc5e6880f5d05eefa600bd1e93`

Commit title: `Universal gap closure: all 8 PRs — raw storage privacy, email lifecycle, diagnostics, export, money quality, barriers`

## Executive verdict

**Do not mark the universal multipipeline issues fully FIXED yet.**

Best status:

```text
Universal multipipeline contracts: PARTIAL+ / close but not clean
Safe to continue some per-pipeline work: yes
Safe to declare universal closure complete: no
```

This commit genuinely improves:

- raw OCR sanitization before normal receipt insert,
- richer export schema,
- email duplicate existing-expense handling,
- email coordinator link failure checks,
- receipt/email diagnostics,
- read barrier existence,
- recurring reminder failure event,
- some forecast/money-quality plumbing.

But it still leaves several cross-pipeline gaps and introduces at least one high-risk schema/migration issue.

---

# High-priority regression / blocker

## P0/P1 — Room schema changed without DB version bump

`AppDatabase` still declares:

```kotlin
const val APP_DATABASE_SCHEMA_VERSION = 122
```

but this commit changes the `pipeline_diagnostic_events` table schema by adding:

```text
entityType
entityId
exceptionClass
exceptionMessage
metadataJson
```

and the schema identity hash changed.

This is dangerous because an existing install already on DB v122 from the previous commit will not run a migration. Room may fail integrity validation or the app may lack the new columns.

## Required fix

Bump DB version:

```text
122 → 123
```

Add migration:

```sql
ALTER TABLE pipeline_diagnostic_events ADD COLUMN entityType TEXT;
ALTER TABLE pipeline_diagnostic_events ADD COLUMN entityId INTEGER;
ALTER TABLE pipeline_diagnostic_events ADD COLUMN exceptionClass TEXT;
ALTER TABLE pipeline_diagnostic_events ADD COLUMN exceptionMessage TEXT;
ALTER TABLE pipeline_diagnostic_events ADD COLUMN metadataJson TEXT;
```

Regenerate schema:

```text
app/schemas/.../123.json
```

Add migration test:

```text
migration_122_123_adds_pipeline_diagnostic_columns
```

Until this is fixed, I would not trust the closure pass as stable.

---

# Updated status table

| Contract | Status after `e18b106` | Notes |
|---|---:|---|
| Restore/write barrier | **PARTIAL+** | Barrier exists but is still thin and not consistently used by core coordinators. |
| Worker guard + run logging | **MOSTLY FIXED** | Not deeply changed here; previous improvements stand. |
| Privacy/redaction/raw storage | **PARTIAL+** | Better for receipt raw OCR, but still leaks via pending review snippets and email source metadata. |
| Money/currency quality | **PARTIAL** | Some exports improved, but forecast and planned-expense conversion are still wrong. |
| Transaction lifecycle | **PARTIAL+** | ReceiptLinkService still directly updates expense category. |
| Receipt lifecycle/link ownership | **PARTIAL+** | Better, but email path still split and conflict handling can create orphan receipts. |
| Recurring planned/actual reconciliation | **MOSTLY FIXED** | Link is atomic; unlink/retry policy still incomplete. |
| Diagnostics/drop reasons/events | **PARTIAL+** | Schema improved, email diagnostics added, but not universal. |
| Import/export schema/roundtrip | **PARTIAL+** | Export schema richer; no true snapshot/encryption UI/receipt links; JSON bug. |
| DAO insert conflict/timestamps | **PARTIAL+** | Some fixes, but insert conflicts still not consistently domain outcomes. |

---

# What this commit genuinely fixed

## 1. Receipt OCR raw text is sanitized before normal insert

`ReceiptRepository.processReceipt()` now calls:

```kotlin
sanitizeOcrBeforeInsert(ocrResult.fullText)
```

before creating `ScannedReceipt`.

This closes much of the previous crash-window problem for successful OCR and parse-failure receipts.

## 2. `RawContentSanitizer` exists

New helper:

```kotlin
RawContentSanitizer.sanitizeRawOcr(...)
sanitizeEmailSubject(...)
sanitizeEmailSender(...)
```

Good direction.

## 3. Email coordinator path now checks link result

`ReceiptLifecycleCoordinator.processEmailReceipt()` checks `linkResult.isFailure` and throws. This is good because link failure should roll back the surrounding transaction.

## 4. Email duplicate expense handling improved

`EmailReceiptIngestionService.createExpenseFromReceipt()` now treats:

```kotlin
CreateExpenseResult.DuplicateSkipped
```

as an existing expense to link, instead of failing immediately.

## 5. Export schema is much richer

Generic CSV/JSON export now uses many `ExportTransaction` fields:

```text
amount
effectiveAmount
transactionType
source
paymentMethod
originalCurrency
originalAmount
homeCurrency
baseAmount
baseCurrency
exchangeRateUsed
business fields
```

This is a major improvement over the old minimal schema.

## 6. Recurring reminder failures are now evented

`markReminderFailed()` writes:

```text
REMINDER_DELIVERY_FAILED
```

and sets:

```text
FAILED_PERMISSION
FAILED_TRANSIENT
```

Good.

---

# Remaining / new issues

## Issue 1 — `DatabaseWriteBarrier` is still not the real global contract

`DatabaseWriteBarrier` is still only:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed()) throw ...
```

It does not model:

```text
operation category
backup export mode
restart-required mode
diagnostic/internal allowlist
```

Also, core classes still mostly use `RestoreMaintenanceMode` directly.

Example: `TransactionLifecycleCoordinator.createExpense()` still does:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed()) ...
```

instead of:

```kotlin
writeBarrier.checkWritesAllowed(...)
```

## Verdict

Functional restore blocking is improved, but the **global barrier contract remains partial**.

---

## Issue 2 — `DatabaseReadBarrier` is too thin

New `DatabaseReadBarrier` only blocks:

```text
RESTORE_COMPLETE_RESTART_REQUIRED
```

It does not block reads during:

```text
RESTORE_PREPARING
RESTORE_STAGING
RESTORE_SWAPPING
RESTORE_VERIFYING
```

`ExportOptionsViewModel` separately checks `restoreMaintenanceMode.isWritesAllowed()`, so export is partly protected, but the read barrier itself is not a robust contract.

---

## Issue 3 — Raw OCR still leaks into `PendingReview.notificationText`

In `ReceiptRepository.processReceipt()`, when `autoCreateReview` is true, the pending review still stores:

```kotlin
notificationText = ocrResult.fullText.take(200)
```

This bypasses `rawOcrStorageMode`.

So even if `RawStorageMode.DO_NOT_STORE` is set, OCR text can still be persisted in the review queue.

## Required fix

Use sanitizer before review insert:

```kotlin
notificationText = RawContentSanitizer.sanitizeRawOcr(
    ocrResult.fullText.take(200),
    privacySettings.rawOcrStorageMode
).takeIf { it.isNotBlank() }
```

or store a generic message:

```text
"Receipt OCR available in memory only; raw storage disabled."
```

---

## Issue 4 — OCR failure path still writes unsanitized error text into `rawOcrText`

In the OCR exception path, code updates:

```kotlin
rawOcrText = "Scan Failed: ${e.message}"
```

This is not raw OCR, but exception messages can include file paths, URI fragments, provider details, or user data.

For strict `DO_NOT_STORE`, even this should be sanitized or moved to diagnostics.

---

## Issue 5 — Email source metadata is still raw

Both email paths still create `EmailReceiptSource` with raw:

```text
emailSender
emailSubject
emailMessageId
```

`RawContentSanitizer` has email subject/sender helpers, but they are not applied in the inspected source inserts.

This means email privacy is still partial.

## Required fix

Before insert:

```kotlin
val mode = privacySettingsRepository.getSettings().emailReceiptStorageMode
emailSender = RawContentSanitizer.sanitizeEmailSender(sender, mode)
emailSubject = RawContentSanitizer.sanitizeEmailSubject(subject, mode)
emailMessageId = hash or null depending policy
```

If you do not want a separate email mode, reuse `rawOcrStorageMode`, but document it.

---

## Issue 6 — Email lifecycle is still split

`EmailReceiptIngestionService` still performs its own orchestration:

```text
detect provider
parse
dedupe
create ScannedReceipt
saveEmailReceipt()
insert EmailReceiptSource
ProcessReceiptUseCase
create expense
link receipt
dispatch side effects
```

It does **not** delegate to:

```kotlin
ReceiptLifecycleCoordinator.processEmailReceipt(...)
```

So there are still two email receipt lifecycle paths.

That means dedupe, source conflict handling, side effects, and diagnostics can still diverge.

## Recommendation

Make the service provider-parser-only:

```text
EmailReceiptIngestionService
→ parse provider
→ call ReceiptLifecycleCoordinator.ingestParsedEmailReceipt(...)
```

or delete the coordinator email method and declare the service the single lifecycle owner.

Right now it remains mixed.

---

## Issue 7 — Email source insert conflict can create orphan receipts

In `ReceiptLifecycleCoordinator.processEmailReceipt()`:

1. insert `ScannedReceipt`;
2. insert `EmailReceiptSource`;
3. if source insert returns `-1`, set duplicate result and `return@withTransaction`.

Because this exits normally, the transaction can commit the newly inserted `ScannedReceipt`, even though the final result is `Duplicate`.

So a duplicate email can still create an orphan receipt row.

## Required fix

Check dedupe before inserting `ScannedReceipt`, or throw a rollback exception on source conflict.

Better:

```kotlin
val sourceResult = emailReceiptDao.insertOrResolve(...)
when (sourceResult) {
  Duplicate -> throw DuplicateRollback(existingReceiptId)
}
```

Catch outside transaction and return `Duplicate`.

---

## Issue 8 — `EmailReceiptIngestionService` still logs source insert conflict and continues

In the service path:

```kotlin
val sourceId = emailReceiptDao.insertOrIgnore(emailSource)
if (sourceId == -1L) Timber.w(...)
```

Then processing continues.

That means:

```text
receipt/expense may be created
source metadata missing
dedupe/audit weakened
```

## Required fix

`insertOrIgnore == -1` must become a domain outcome:

```text
Duplicate(existingReceiptId)
Conflict(reason)
Rollback
```

not only a log.

---

## Issue 9 — ReceiptLinkService still bypasses transaction lifecycle

`ReceiptLinkService` still directly calls:

```kotlin
expenseDao.updateCategory(expenseId, bestCategoryId)
```

The comment now says `DEFERRED_DESIGN`, which is honest, but the bypass still exists.

This skips:

```text
TransactionEvent.UPDATED
budget side effects
analytics/cache invalidation
merchant/category learning
```

## Tracker status

Do not mark transaction lifecycle fully fixed unless this is explicitly:

```text
DEFERRED_DESIGN
```

with guard allowlist.

---

## Issue 10 — Recurring unlink is still incomplete

`linkExpenseToOccurrence()` is now much better and atomic.

But `unlinkExpenseFromOccurrence()` still:

```text
occurrence → PLANNED
insert OCCURRENCE_UNLINKED
```

without visibly:

```text
database.withTransaction
reopening PlannedExpense
restoring openSourceOccurrenceKey
rescheduling/unsuppressing reminders
```

This affects transaction delete/undo flows.

---

## Issue 11 — Reminder retry policy remains incomplete

`markReminderFailed()` sets:

```text
FAILED_PERMISSION
FAILED_TRANSIENT
```

but there is no visible:

```text
attemptCount
lastAttemptAt
retryAt
max attempts
```

and due query only returns planned occurrence deliveries, but likely still only scheduled/snoozed/failed depending DAO implementation. The retry policy is not yet explicit.

Acceptable if documented terminal, but not a complete retry contract.

---

## Issue 12 — Forecast money quality is still broken

`ForecastInputAssembler` computes `normalizedAmount` for planned expenses, but returns:

```kotlin
pe // keep the original planned expense
```

So planned expenses are still not actually converted before forecast arithmetic.

Even worse, the comment says failed conversion is “excluded,” but code still includes the original planned expense in the forecast.

This can still raw-sum mixed currencies downstream.

## Also

`SynthesisEngine` still contains a note saying `ForecastDataQuality.confidencePenalty` should be applied, but it is not applied.

So money/currency quality is **not fixed** for forecast.

---

## Issue 13 — Planned/cancelled statuses still weak

`ForecastInputAssembler.mapPlannedExpenses()` filters only:

```kotlin
status != "FULFILLED"
```

So cancelled/skipped planned expenses can still enter forecast.

`ConfirmedOccurrence` mapping also does not carry occurrence status, so paid/skipped/cancelled occurrence semantics are still weak in forecast.

---

## Issue 14 — JSON export has a likely invalid JSON bug when `source == null`

In `writeJsonPageRows()`:

```kotlin
append("\"source\":")
if (tx.source == null) append("null") else append("\"...\",")
append("\"paymentMethod\":")
```

If `tx.source == null`, there is **no comma** before `"paymentMethod"`.

Result:

```json
"source":null"paymentMethod":"CARD"
```

invalid JSON.

## Required fix

Always append comma after source field:

```kotlin
append("\"source\":")
if (tx.source == null) append("null") else append("\"...\"")
append(',')
```

Add test:

```text
json_export_valid_when_source_null
```

---

## Issue 15 — Export snapshot is still not real

`ExportDataRepository` claims “stable ID-based snapshot consistency,” but `DeterministicExpenseExportPager` still explicitly says:

```text
this is NOT a true atomic snapshot
rows inserted with higher cursor can be seen
count is NOT snapshot-anchored
```

So import/export roundtrip is not fully stable under concurrent writes.

---

## Issue 16 — Export encryption still not wired to UI/settings

`generateExport(encryptExport: Boolean = false)` supports a parameter, but comments say encryption is pending a UI toggle.

So the export privacy/encryption contract is still partial.

---

## Issue 17 — Receipt links still not exported

No `receiptLinks` field appears in `ExportOptionsViewModel` generic CSV/JSON export.

So roundtrip still cannot preserve receipt-expense links.

---

## Issue 18 — No evidence of actual import pipeline in this commit

This commit changed 16 files. I do not see new importer files such as:

```text
CsvExpenseImporter
JsonExpenseImporter
ExpenseImportCoordinator
ImportPreview
```

So the “import/export roundtrip” contract is not fully closed by this commit unless those were added earlier outside the inspected diff.

---

## Issue 19 — Diagnostics expanded, but still not universal

`PipelineDiagnosticEvent` has more fields now. Email receipt emits some diagnostics.

But I still do not see universal diagnostics for:

```text
budget monitor decisions
backup/restore stages
bank sync runs
import/export runs
privacy denied final decisions
receipt side-effect failures
worker final outcome bridge to pipeline diagnostics
```

So diagnostics are improved, not universal.

---

# Regression summary

## Must fix before declaring closure

```text
1. DB schema changed without version bump/migration.
2. JSON export invalid when source == null.
3. Raw OCR still leaks into PendingReview.notificationText.
4. Email source insert conflict can commit orphan receipt.
5. Forecast planned-expense conversion computes normalizedAmount but returns original.
6. SynthesisEngine still ignores ForecastDataQuality.confidencePenalty.
```

These are not cosmetic; they affect stability/correctness.

---

# Can you move to per-pipeline work now?

## My recommendation

You can move to per-pipeline work **after a small hotfix commit** for the must-fix regressions above.

Minimum hotfix:

```text
A. Bump DB version to 123 + migration for PipelineDiagnosticEvent columns.
B. Fix JSON source-null comma bug.
C. Sanitize PendingReview.notificationText from OCR.
D. Make EmailReceiptSource conflict rollback/return Duplicate.
E. Either normalize planned expenses or exclude failed planned conversions.
F. Apply ForecastDataQuality.confidencePenalty in SynthesisEngine.
```

After that:

```text
Universal contracts: MOSTLY_FIXED
Per-pipeline work: safe to resume
```

But right now:

```text
Universal contracts: PARTIAL+
Per-pipeline work: possible, but risky to call universal work done
```

---

# Recommended final tracker status

```text
Restore/write barrier                         PARTIAL+
Worker guard + run logging                    MOSTLY_FIXED
Privacy/redaction/raw storage                 PARTIAL+
Money/currency quality                        PARTIAL
Transaction lifecycle                         PARTIAL+ / DEFERRED_DESIGN for receipt category propagation
Receipt lifecycle/link ownership              PARTIAL+
Recurring planned/actual reconciliation       MOSTLY_FIXED
Diagnostics/drop reasons/events               PARTIAL+
Import/export schema/roundtrip                PARTIAL+
DAO insert conflict/timestamps                PARTIAL+
```

After the hotfix list, I would change several to `MOSTLY_FIXED`, but not all to `FIXED` unless import/export snapshot, receipt links, and lifecycle category propagation are either completed or explicitly deferred.

---

# Sources inspected

- Commit page:  
  https://github.com/panospao7/Cost-agregator/commit/e18b1063ee923fbc5e6880f5d05eefa600bd1e93

- `AppDatabase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

- `DatabaseWriteBarrier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt

- `DatabaseReadBarrier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseReadBarrier.kt

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

- `ForecastInputAssembler.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt

- `SynthesisEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt

- `ExportOptionsViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt

- `ExportDataRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/data/repository/ExportDataRepository.kt

- `DeterministicExpenseExportPager.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/e18b1063ee923fbc5e6880f5d05eefa600bd1e93/app/src/main/java/com/yourname/expensetracker/data/repository/DeterministicExpenseExportPager.kt