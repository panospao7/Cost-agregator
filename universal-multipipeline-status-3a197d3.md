# Universal multipipeline status check

Evaluated head: `3a197d3`  
Commit title: `Closure pass round 2: camera OCR storage, email ingest fixes, reminder lifecycle, deferred design marks`

## Executive verdict

You are **close**, but the universal multipipeline issues are **not fully clean/stable yet**.

I would classify the state as:

```text
Universal contracts: PARTIAL+ / near closure
Safe to start some per-pipeline work: YES, but only after documenting/accepting remaining deferred items
Safe to mark all universal issues FIXED: NO
```

The latest round fixed several important blockers, but there are still cross-pipeline gaps in:

- receipt/email lifecycle ownership,
- write-time raw OCR privacy,
- transaction lifecycle bypass via receipt linking,
- diagnostics beyond notification,
- import/export snapshot/encryption/receipt-link completeness,
- worker stale-run/retry semantics,
- money/currency quality propagation.

---

# Current status table

| Universal contract | Status | Verdict |
|---|---:|---|
| Restore/write barrier | **PARTIAL+** | Guards exist broadly, but `DatabaseWriteBarrier` is thin and not consistently used by core coordinators. |
| Worker guard + run logging | **MOSTLY FIXED** | Guard/logger exist; `startedAt` fixed; still no stale-run recovery and reminder retry metadata is thin. |
| Privacy/redaction/raw storage | **PARTIAL+** | Raw modes exist and are applied in more places, but camera OCR is still initially persisted raw before later sanitization. Email source metadata remains raw. |
| Money/currency quality | **PARTIAL** | Not fully resolved by this round. Export fields improved, but dashboard/weekly/daily/historical basis concerns remain. |
| Transaction lifecycle | **PARTIAL+** | Much better; strict idempotency and events improved. But receipt category propagation still bypasses lifecycle and is now explicitly deferred. |
| Receipt lifecycle/link ownership | **PARTIAL** | Email/camera paths improved, but `EmailReceiptIngestionService` still has separate orchestration and link-result/insert-conflict caveats. |
| Recurring planned/actual reconciliation | **MOSTLY FIXED** | Atomic paid reconciliation, planned-occurrence reminder query, claim, and failure event exist. Unlink/reopen and retry policy still weak. |
| Diagnostics/drop reasons/events | **PARTIAL** | Notification diagnostics improved; not universal across receipt/email/backup/import/budget/etc. |
| Import/export schema/roundtrip | **PARTIAL+** | Export schema and importer/test exist, but no true snapshot, no export encryption wiring, no receipt-link export. |
| DAO insert conflict/timestamps | **PARTIAL+** | Many fixes landed, but some `insertOrIgnore` paths still only log conflicts; camera OCR raw insert-before-sanitize remains. |

---

# What is genuinely fixed now

## 1. Recurring reminder safety is much better

The latest commits added:

```text
RecurringReminderDeliveryDao.getPendingDeliveriesForPlannedOccurrences()
RecurringLifecycleCoordinator.getDueReminders() uses planned-only query
BillReminderWorker claims before notifying
markReminderFailed()
REMINDER_DELIVERY_FAILED event
```

This closes the previous major issue where paid/skipped/cancelled occurrences could still produce reminders.

## 2. Recurring payment reconciliation is now atomic

`linkExpenseToOccurrence()` now uses `database.withTransaction` for:

```text
occurrence → PAID
RecurringLifecycleEvent.OCCURRENCE_PAID
plannedExpenseDao.linkToActualExpense(...)
reminderDeliveryDao.suppressOpenDeliveriesForOccurrence(...)
```

This is a real improvement.

## 3. Worker run logger corruption appears fixed

`WorkerRunLoggerImpl.Handle` now preserves `startedAt` when finalizing `SUCCESS`, `SKIPPED`, `RETRY`, and `FAILED`.

Previous blocker resolved.

## 4. Generic export schema is much richer

Generic CSV/JSON now use `ExportTransaction` fields including:

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

This is a major step toward roundtrip/export correctness.

## 5. Email duplicate expense handling improved

`EmailReceiptIngestionService.createExpenseFromReceipt()` now treats:

```kotlin
CreateExpenseResult.DuplicateSkipped
```

as link-existing success instead of failure.

Good.

## 6. Email content fingerprint no longer includes message ID

The service fingerprint is now content-based:

```text
merchant + rounded amount + date bucket
```

This fixes the forwarded/re-sent-email duplicate problem better than before.

---

# Remaining issues / regressions

## Issue 1 — `DatabaseWriteBarrier` is injected but often unused

Example: `TransactionLifecycleCoordinator` injects:

```kotlin
private val writeBarrier: DatabaseWriteBarrier
```

but `createExpense()` still checks:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed()) ...
```

Similarly, `ReceiptLifecycleCoordinator`, `RecurringLifecycleCoordinator`, and `EmailReceiptIngestionService` inject the barrier but still mostly use `restoreMaintenanceMode` directly.

Functional impact is smaller because restore checks still exist, but architecturally this is **not a clean global barrier**.

Current `DatabaseWriteBarrier` is also very thin:

```kotlin
fun checkWritesAllowed(operation: String) {
    if (!restoreMaintenanceMode.isWritesAllowed()) throw ...
}
```

It does not model:

```text
operation category
backup export mode
restart-required mode
diagnostic/internal allowlist
read/export barrier
```

### Recommendation

Do not call this contract “fully implemented.”  
Call it:

```text
Restore write blocking: mostly implemented
Global DatabaseWriteBarrier contract: partial
```

---

## Issue 2 — Camera/gallery OCR raw storage is still not true write-time privacy

`ReceiptLifecycleCoordinator.processReceiptInput()` now sanitizes `rawOcrText` before updating the receipt, which is good.

But the flow is still:

```text
ReceiptRepository.processReceipt()
→ inserts ScannedReceipt with raw OCR
→ coordinator later updates rawOcrText to redacted/empty
```

So if the app crashes between repository insert and coordinator update, raw OCR can remain stored even when policy is:

```text
STORE_METADATA_ONLY
DO_NOT_STORE
```

That means raw OCR privacy is **not strictly write-time enforced** for camera/gallery receipts.

### Required fix

Move raw storage policy into the first persistence boundary:

```text
ReceiptRepository.processReceipt()
```

or refactor so repository returns a draft and only `ReceiptLifecycleCoordinator` inserts the final sanitized row.

### Needed tests

```text
camera_receipt_DO_NOT_STORE_never_persists_raw_ocr_even_if_coordinator_crashes
camera_receipt_METADATA_ONLY_insert_has_empty_rawOcrText
parse_failure_receipt_respects_rawOcrStorageMode
```

---

## Issue 3 — Email source metadata is still raw

Even after `rawOcrText` sanitization, `EmailReceiptSource` still stores:

```text
emailSender
emailSubject
emailMessageId
```

There is no `EmailReceiptStorageMode` or redaction/hashing policy for these fields.

This means the privacy/raw storage contract is only partially solved.

### Recommendation

Add policy for:

```text
email sender
email subject
message id
raw body
```

At minimum:

```text
STORE_RAW
STORE_REDACTED
STORE_METADATA_ONLY
DO_NOT_STORE
```

or hash `messageId` and sender while dropping/redacting subject.

---

## Issue 4 — Email lifecycle is still split

Despite the coordinator path, `EmailReceiptIngestionService.processEmailReceipt()` still does its own orchestration:

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
dispatch transaction side effects
```

It does **not** delegate to:

```kotlin
receiptLifecycleCoordinator.processEmailReceipt(...)
```

So there are still two lifecycle paths.

### Why this matters

The coordinator path and service path can diverge in:

```text
dedupe
EmailReceiptSource conflict handling
side effects
raw metadata storage
receipt events
expense creation/linking behavior
```

### Recommendation

Either:

1. make `EmailReceiptIngestionService` only parse provider-specific emails and then call the coordinator, or
2. delete/disable the coordinator email method and declare service as the single lifecycle owner.

Right now it is still mixed.

---

## Issue 5 — `EmailReceiptSource.insertOrIgnore()` conflict handling is still weak

The service now checks `sourceId == -1L`, but only logs:

```kotlin
Timber.w("EmailReceiptSource insert conflict...")
```

It does not convert conflict into a domain outcome.

The coordinator path still appears to call:

```kotlin
emailReceiptDao.insertOrIgnore(emailSource)
```

without checking the result.

### Risk

You can still get:

```text
receipt saved
expense created/linked
EmailReceiptSource not inserted due conflict
```

That weakens dedupe/audit.

### Fix

Return:

```text
Duplicate(existingReceiptId)
Conflict(existingSource)
Inserted(sourceId)
```

not just log.

---

## Issue 6 — Receipt-link category update remains a lifecycle bypass

`ReceiptLinkService` still directly calls:

```kotlin
expenseDao.updateCategory(expenseId, bestCategoryId)
```

It is now marked:

```text
DEFERRED_DESIGN
```

That is honest, but it means the universal transaction lifecycle contract is **not fully fixed**.

This bypass skips:

```text
TransactionEvent.UPDATED
budget side effects
merchant/category learning
analytics/cache invalidation
```

### Recommendation

It is acceptable to proceed only if tracker says:

```text
Receipt item → expense category propagation: DEFERRED_DESIGN
```

Not `FIXED`.

---

## Issue 7 — Email coordinator link result appears unchecked

In `ReceiptLifecycleCoordinator.processEmailReceipt()`, link calls happen inside the transaction:

```kotlin
receiptLinkService.linkReceiptToExpense(...)
```

but the returned `Result` is not visibly checked in the compact source view.

If link fails, the method may still return success with an expense ID.

### Fix

Inside coordinator:

```kotlin
val linkResult = receiptLinkService.linkReceiptToExpense(...)
if (linkResult.isFailure) throw linkResult.exceptionOrNull() ?: ...
```

so the transaction rolls back.

---

## Issue 8 — Recurring unlink/reopen is still incomplete

`linkExpenseToOccurrence()` is much better.

But `unlinkExpenseFromOccurrence()` still does:

```text
occurrence → PLANNED
insert OCCURRENCE_UNLINKED
```

It does not visibly:

```text
reopen linked PlannedExpense
recreate/reschedule reminder deliveries
run in database.withTransaction
```

This affects delete/undo paths from transaction lifecycle.

### Recommendation

Can be pipeline-specific if delete/undo is not current priority, but it is still cross-pipeline with transaction delete.

---

## Issue 9 — Reminder failure state is better but retry policy is incomplete

`markReminderFailed()` sets:

```text
FAILED_PERMISSION
FAILED_TRANSIENT
```

and writes `REMINDER_DELIVERY_FAILED`.

Good.

But there is no clear retry path for `FAILED_TRANSIENT`, because due query only returns:

```text
SCHEDULED
SNOOZED
```

No `attemptCount`, `lastAttemptAt`, `failureReason`, or retryAt field is visible.

### Recommendation

Either document as terminal failure, or implement:

```text
FAILED_TRANSIENT → reschedule/retry
FAILED_PERMISSION → terminal until permission changes
```

---

## Issue 10 — Diagnostics are still not universal

You have `PipelineDiagnosticEvent`, but from the commits I can only verify strong notification diagnostics.

Still missing universal durable diagnostics for:

```text
receipt OCR parse/link failure
email ingestion conflict/failure
recurring reminder failure/suppression
budget monitor decisions
backup/restore stages
bank sync run outcomes
export/import run outcomes
privacy denials
```

So “diagnostics/drop reasons/events across almost all” remains **partial**.

---

## Issue 11 — Import/export roundtrip improved but not production-clean

Good:

```text
CSV/JSON schema improved
JsonExpenseImporter/ImportCoordinator exist per PR summary
ExportImportRoundtripTest exists
```

Still partial:

```text
Export still uses keyset pagination, not true snapshot table
Export encryption helper still not wired into generateExport()
No receipt links in generic export
No visible import preview / row-level UI error report
Export is blocked using restoreMaintenanceMode.isWritesAllowed(), not a formal read barrier
```

### Recommendation

This can move to pipeline 12 work now, but tracker should say:

```text
Import/export roundtrip: PARTIAL, pipeline follow-up
```

not universal fixed.

---

## Issue 12 — Money/currency quality not fully closed

This last round did not appear to address the remaining money/currency issues:

```text
weekly/daily drilldown safe totals
historical-vs-latest conversion basis
dashboard warning propagation
forecast confidence propagation
category percentages with partial conversion
budget-vs-actual normalization
```

Export fields improved, but pipeline 5/6 currency quality still needs per-pipeline verification.

---

# Regression risks from latest fixes

## Regression risk A — Coordinator injects `DatabaseWriteBarrier` but does not use it

This can create a false sense of safety. Static checks may think the barrier is present, while code still uses old restore checks.

## Regression risk B — Email source conflict is only logged

A conflicting `EmailReceiptSource` can still leave partial semantic state.

## Regression risk C — Raw OCR privacy still has crash window

Camera/gallery OCR raw text can be inserted before later redaction.

## Regression risk D — Receipt link result ignored in coordinator path

If link fails but success is returned, you get unlinked expenses/receipts.

## Regression risk E — Export row count can mismatch rows under concurrent writes

Because pagination is still not a true snapshot.

---

# Final answer

## Are the multipipeline priority issues fixed in all pipelines?

**No, not fully.**

But the state is now good enough to say:

```text
Universal foundation mostly installed.
A few items are explicitly deferred or need pipeline-specific hardening.
```

## Are they stable and clean?

```text
Stable enough to continue refactor: mostly yes.
Clean enough to mark all universal issues FIXED: no.
```

## Should you move to per-pipeline issues?

Yes, with two conditions:

1. Update tracker honestly:
   - mark some universal contracts as `MOSTLY_FIXED` / `PARTIAL+`;
   - mark `ReceiptLinkService` category lifecycle bypass as `DEFERRED_DESIGN`;
   - mark import/export snapshot/encryption/receipt links as Pipeline 12 follow-up.

2. Do a tiny final universal guard PR or test pass for:
   - camera OCR raw text never persisted under `DO_NOT_STORE`;
   - email source insert conflict returns duplicate/conflict outcome;
   - receipt link failure rolls back email-created expense;
   - `./gradlew check` includes lifecycle/raw-money/restore/privacy guards.

After that, move to per-pipeline debugging/fixes.

---

# Recommended tracker status now

```text
Restore/write barrier                         PARTIAL+
Worker guard + run logging                    MOSTLY_FIXED
Privacy/redaction/raw storage                 PARTIAL+
Money/currency quality                        PARTIAL
Transaction lifecycle                         PARTIAL+ / one DEFERRED_DESIGN
Receipt lifecycle/link ownership              PARTIAL+
Recurring planned/actual reconciliation       MOSTLY_FIXED
Diagnostics/drop reasons/events               PARTIAL
Import/export schema/roundtrip                PARTIAL+
DAO insert conflict/timestamps                PARTIAL+
```

---

# Sources inspected

- PR #4 commit list / latest commit summary:  
  https://github.com/panospao7/Cost-agregator/pull/4

- Latest reviewed commit:  
  https://github.com/panospao7/Cost-agregator/commit/3a197d3

- `ReceiptLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/3a197d3/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt

- `EmailReceiptIngestionService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/3a197d3/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

- `ReceiptLinkService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/3a197d3/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt

- `RecurringLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/3a197d3/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

- `DatabaseWriteBarrier.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/3a197d3/app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt

- `WorkerRunLogger.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/3a197d3/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt

- `WorkerExecutionGuard.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/3a197d3/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt

- `BillReminderWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/3a197d3/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt

- `ExportOptionsViewModel.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/3a197d3/app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt