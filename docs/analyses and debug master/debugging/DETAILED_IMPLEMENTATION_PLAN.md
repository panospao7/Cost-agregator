# Detailed Implementation Plan — 47 New Issues

> **Generated:** 2026-05-11  
> **Source:** Deep source code analysis at HEAD `92d77385`  
> **Structure:** 20 atomic fix batches (6 P0/P1 + 14 P2), each independently mergeable  
> **Total effort:** ~6-8 days of focused implementation

---

## How to Use This Document

Each batch specifies:
- **Exact code changes** (before/after)
- **Files modified**
- **Must-group constraints** (what CANNOT be split)
- **Dependencies on other batches** (what must merge first)
- **Risk level and testing requirements**

Batches with no dependencies can be implemented in parallel.

---

## PHASE 1: Critical Fixes (P0 + P1) — 6 Batches

### Batch A: Worker Scheduling Fix [P0-01]

**Severity:** P0 — Daily briefing worker has NEVER run  
**Complexity:** S | **Risk:** None | **Dependencies:** None

**File:** `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpecScheduler.kt`

```kotlin
// BEFORE (line ~171):
val request = OneTimeWorkRequestBuilder<ListenableWorker>()
    .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    .setConstraints(spec.constraints)
    .setBackoffCriteria(spec.backoffPolicy, spec.backoffDelaySeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
    .build()

// AFTER:
val request = OneTimeWorkRequest.Builder(typedClass)
    .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    .setConstraints(spec.constraints)
    .setBackoffCriteria(spec.backoffPolicy, spec.backoffDelaySeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
    .build()
```

**Test:** Verify `scheduleAtMidnight(DailyBriefingWorker::class.java, spec)` enqueues work with correct class name.

---

### Batch B: Notification Dedup Fix [P1-01]

**Severity:** P1 — In-memory dedup completely disabled  
**Complexity:** S | **Risk:** Low | **Dependencies:** None

**File:** `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt`

```kotlin
// BEFORE (line ~420):
val coarseDedupeKey = "$dedupeKeyRaw:$now"

// AFTER:
val coarseDedupeKey = dedupeKeyRaw
```

**Context:** DB-level fingerprint dedup (using `sbn.postTime`) is the real safety net and IS working. This fix restores the in-memory pre-filter that avoids redundant DB calls for rapid re-posts of the same notification.

**Test:** Two `onNotificationPosted` calls with same `sbn.key` within 60s → second is deduplicated in-memory.

---

### Batch C: Review Recovery + SynthesisEngine Safety [P1-02, P1-04, P1-05]

**Severity:** P1 — Stuck reviews + ANR risk + crash on multi-currency  
**Complexity:** S | **Risk:** Low | **Dependencies:** None

**Why grouped:** All three are independent 1-5 line fixes with zero interaction. Grouping reduces PR overhead.

**File 1:** `app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt`
```kotlin
// ADD:
@Query("UPDATE pending_reviews SET status = 'PENDING' WHERE status = 'PROCESSING'")
suspend fun recoverStuckProcessing(): Int
```

**File 2:** `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt`
```kotlin
// ADD in scheduleStartupWork() or init:
runCatching { reviewQueueRepository.recoverStuckReviews() }
```

**File 3:** `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`
```kotlin
// BEFORE (line ~290 in buildRecurringByDayFromOccurrences):
private fun buildRecurringByDayFromOccurrences(...): Map<Int, List<RecurringPattern>> {
    val occurrences = runBlocking { occurrenceDao.getByDateRange(monthStart, monthEnd) }

// AFTER:
private suspend fun buildRecurringByDayFromOccurrences(...): Map<Int, List<RecurringPattern>> {
    val occurrences = occurrenceDao.getByDateRange(monthStart, monthEnd)
```

**File 4:** `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`
```kotlin
// BEFORE (5 occurrences of require):
require(committedPlannedByCurrency.size <= 1) { "Multiple currencies..." }

// AFTER (all 5):
if (committedPlannedByCurrency.size > 1) {
    Timber.w("$TAG: Multiple currencies in planned expenses — results may be inaccurate")
}
```

**Tests:** 
- Review in PROCESSING state recovered to PENDING on startup
- SynthesisEngine with multi-currency planned expenses returns forecast without crash
- `buildRecurringByDayFromOccurrences` works as suspend function

---

### Batch D: Email Receipt Orphan Fix [P1-03]

**Severity:** P1 — Ghost receipt rows accumulate  
**Complexity:** S | **Risk:** Low | **Dependencies:** None

**File:** `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt`

```kotlin
// BEFORE (inside processEmailReceipt, after insertOrIgnore returns -1):
if (sourceId == -1L) {
    val existing = emailReceiptDao.getByFingerprint(fingerprint)
    if (existing != null) {
        capturedDuplicate = EmailReceiptProcessResult.Duplicate(existing.receiptId)
        return@withTransaction
    }
}

// AFTER:
if (sourceId == -1L) {
    val existing = emailReceiptDao.getByFingerprint(fingerprint)
    if (existing != null) {
        scannedReceiptDao.deleteById(savedId)  // Clean up orphan
        capturedDuplicate = EmailReceiptProcessResult.Duplicate(existing.receiptId)
        return@withTransaction
    }
}
```

**Note:** If `deleteById` doesn't exist, add to `ScannedReceiptDao`:
```kotlin
@Query("DELETE FROM scanned_receipts WHERE id = :id")
suspend fun deleteById(id: Long)
```

**Test:** Email receipt with duplicate fingerprint → no orphan `ScannedReceipt` row remains.

---

### Batch E: Budget Notification Currency [P1-06]

**Severity:** P1 — Wrong currency symbol in notifications  
**Complexity:** S | **Risk:** Low | **Dependencies:** None

**File:** `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetMonitor.kt`

```kotlin
// BEFORE (in sendNotification):
val currencySymbol = SupportedCurrency.fromCode(budget.currency)?.symbol ?: budget.currency

// AFTER (use the status currency which reflects home currency after conversion):
val displayCurrency = status?.currency ?: budget.currency
val currencySymbol = SupportedCurrency.fromCode(displayCurrency)?.symbol ?: displayCurrency
```

**Note:** This is the minimal fix. The proper fix (converting budget amounts to home currency in BudgetRepository) is tracked separately in the existing master tracker as P6-P1-06.

**Test:** Budget in USD with home currency EUR → notification shows "€" not "$".

---

### Batch F: Restore Crash Safety [P1-07 + P1-08]

**Severity:** P1 — Crash during restore can corrupt recovery state  
**Complexity:** M | **Risk:** Low | **Dependencies:** None  
**Must-group:** P1-07 and P1-08 MUST be fixed together

**File 1:** `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt`
```kotlin
// BEFORE:
fun writeJournal(entry: JournalEntry) {
    try {
        journalFile.parentFile?.mkdirs()
        val text = entry.toJson().toString(2)
        journalFile.writeText(text)
    } catch (e: Exception) {
        Timber.e(e, "Failed to write restore journal")
    }
}

// AFTER (atomic write via temp+rename):
fun writeJournal(entry: JournalEntry) {
    try {
        journalFile.parentFile?.mkdirs()
        val text = entry.toJson().toString(2)
        val tmpFile = File(journalFile.parentFile, "${JOURNAL_FILENAME}.tmp")
        tmpFile.writeText(text)
        tmpFile.renameTo(journalFile)
    } catch (e: Exception) {
        Timber.e(e, "Failed to write restore journal")
    }
}
```

**File 2:** `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt`
```kotlin
// BEFORE:
private fun writeMode(mode: Mode) {
    prefs.edit().putString(KEY_MAINTENANCE_MODE, mode.name).apply()
}

// AFTER:
private fun writeMode(mode: Mode) {
    prefs.edit().putString(KEY_MAINTENANCE_MODE, mode.name).commit()
}
```

**File 3:** `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt`
```kotlin
// ADD in startup (auto-recover stuck BACKUP_EXPORTING):
if (restoreMaintenanceMode.currentMode() == RestoreMaintenanceMode.Mode.BACKUP_EXPORTING) {
    Timber.w("Recovering from stuck BACKUP_EXPORTING mode")
    restoreMaintenanceMode.reset()
}
```

**Tests:**
- Simulate process kill during `writeJournal` → journal is either old state or new state, never partial
- App startup with mode=BACKUP_EXPORTING → auto-resets to NORMAL

---

## PHASE 2: P2 Fixes — 14 Batches

### Batch 1: Notification Capture Concurrency [P2-03, P2-05, P2-06]

**Files:** `service/NotificationCaptureService.kt`  
**Complexity:** S | **Risk:** Low | **Dependencies:** None

| Issue | Change |
|-------|--------|
| P2-03 | Change `private var capturePrivacyDenied = false` → `= true` |
| P2-05 | Wrap `removeIf` in `synchronized(processedNotifications) { ... }` |
| P2-06 | Change `parts.combinedBody` → `parts.bigText` in `shouldCapture` call |

---

### Batch 2: Notification Filter Policy [P2-07]

**Files:** `service/NotificationFilter.kt`  
**Complexity:** S | **Risk:** Low | **Dependencies:** None

```kotlin
// BEFORE (finance package path):
if (!hasTransactionSignal) return false
return true

// AFTER:
if (!hasTransactionSignal) return false
if (DENY_KEYWORDS.any { combined.contains(it) }) return false
return true
```

---

### Batch 3: Pipeline Outcome Data [P2-01]

**Files:** `data/repository/NotificationProcessingPipeline.kt`  
**Complexity:** S | **Risk:** Low | **Dependencies:** None

Change `ParsedDbOutcome.NeedsReviewCreated` from `object` to `data class(val rawId: Long, val reviewId: Long)` and propagate IDs from `handleNeedsReviewInTransaction`.

---

### Batch 4: Transaction Lifecycle Cleanup [P2-02, P2-04]

**Files:** `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`, `TransactionSideEffectDispatcher.kt`  
**Complexity:** S | **Risk:** Medium | **Dependencies:** None

| Issue | Change |
|-------|--------|
| P2-02 | Remove direct `recurringLifecycleCoordinator.linkExpenseToOccurrence(expenseId)` from `dispatchPostCreationSideEffects` (already called inside `dispatchOnCreated`) |
| P2-04 | Remove shadowing `val affectedCount = affectedExpenses.size` inside `bulkUpdateMerchant` transaction |

---

### Batch 5: Receipt Link State [P2-08, P2-09]

**Files:** `domain/receipt/lifecycle/ReceiptLinkService.kt`  
**Complexity:** S | **Risk:** Low | **Dependencies:** None

| Issue | Change |
|-------|--------|
| P2-08 | Return `Result.success(link.copy(id = linkId))` instead of `Result.success(link)` |
| P2-09 | In `unlinkReceiptFromExpense`, add `matchStatus = "UNMATCHED", matchConfidence = null, suggestedExpenseId = null` to receipt copy |

---

### Batch 6: OCR Service Lifecycle [P2-10]

**Files:** `domain/receipt/ReceiptOcrService.kt`  
**Complexity:** S | **Risk:** Low | **Dependencies:** None

```kotlin
// BEFORE:
fun close() {
    runBlocking { recognizerMutex.withLock { recognizer?.close(); recognizer = null } }
}

// AFTER:
suspend fun close() {
    recognizerMutex.withLock { recognizer?.close(); recognizer = null }
}
```

Update callers to use coroutine scope.

---

### Batch 7: Receipt Lifecycle Atomicity [P2-15]

**Files:** `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt`  
**Complexity:** S | **Risk:** Low | **Dependencies:** None

```kotlin
// BEFORE (in createExpenseFromReceipt, inside withTransaction):
receiptLinkService.linkReceiptToExpense(...)

// AFTER:
val linkResult = receiptLinkService.linkReceiptToExpense(...)
if (linkResult.isFailure) {
    throw IllegalStateException("Receipt link failed: ${linkResult.exceptionOrNull()?.message}")
}
```

---

### Batch 8: Recurring Scheduling [P2-11, P2-12, P2-13]

**Files:** `service/reminder/BillReminderWorker.kt`, `domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt`, `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`  
**Complexity:** M | **Risk:** Medium | **Dependencies:** None

| Issue | Change |
|-------|--------|
| P2-11 | Use `(delivery.id.hashCode() and 0x7FFFFFFF)` for snooze, `xor 0x40000000` for dismiss |
| P2-12 | Change `"OVERDUE" -> dueDate` to `"OVERDUE" -> TimePeriodUtils.addDays(dueDate, 1)` |
| P2-13 | Rename `reconcilePlannedVsActual` → `ensureOccurrencesAndReconcile` or split into read-only report method |

---

### Batch 9: Bank Statement Status [P2-14]

**Files:** `domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt`  
**Complexity:** S | **Risk:** Low | **Dependencies:** None

Add `scannedReceiptDao.updateProcessingStatus(receiptId, "REVIEW_CREATED", timeProvider.now())` before writing the PROCESSING_COMPLETE event.

---

### Batch 10: Dashboard Aggregation [P2-16, P2-17, P2-22, P2-23]

**Files:** `domain/analytics/TotalsAggregationEngine.kt`, `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, `data/repository/DashboardContractsAdapter.kt`  
**Complexity:** M | **Risk:** Medium | **Dependencies:** None

| Issue | Change |
|-------|--------|
| P2-16 | Replace `while(true) { .first() }` with `expenseRepository.getTotalSpent().collect { emit(block()) }` |
| P2-17 | Replace `getHomeCurrencyTotal` with `getHomeCurrencyDepositTotal` for `monthlyIncome` |
| P2-22 | Replace `.first()` inside `.map{}` with `combine(currentFlow, previousFlow)` |
| P2-23 | Normalize `DashboardExpense.effectiveAmount` to home currency before trend accumulation |

---

### Batch 11: Forecast Currency Normalization [P2-18, P2-19, P2-20, P2-21]

**Files:** `domain/cashflow/CashFlowCalculator.kt`, `domain/forecasting/FinancialStressForecastEngine.kt`, `domain/forecasting/ForecastInputAssembler.kt`  
**Complexity:** M | **Risk:** Medium | **Dependencies:** None

| Issue | Change |
|-------|--------|
| P2-18 | Add `require(startingBalanceCurrency == homeCurrency)` or convert |
| P2-19 | Add `.coerceAtLeast(0.0)` to `resolveStartingBalanceBaseline` return |
| P2-20 | After `mergeRecurringPatterns`, normalize each pattern's `averageAmount` to home currency |
| P2-21 | Track conversion failures per day; add `isPartial` to `DailyCashFlow` |

**Must-group constraint:** P2-20 MUST be deployed with the P1-05 fix (Batch C) already in place, otherwise SynthesisEngine will crash on the now-mixed-but-not-yet-normalized patterns. Since Batch C converts `require()` to warnings, this is safe regardless of order.

---

### Batch 12: Cloud AI Privacy [P2-24, P2-25]

**Files:** `data/ai/provider/CloudReceiptAssistService.kt`  
**Complexity:** S | **Risk:** Medium | **Dependencies:** None

```kotlin
// BEFORE (suggestFromText):
val parts = JSONArray().put(JSONObject().put("text", prompt))

// AFTER:
val settings = aiSettingsRepository.settings().first()
val textToSend = if (settings.redactBeforeCloud) {
    redactor.redactText(prompt, CloudPayloadPurpose.BANK_STATEMENT)
} else prompt
val parts = JSONArray().put(JSONObject().put("text", textToSend))
```

Also wrap response in `.use { }` on retry path.

---

### Batch 13: Backup Data Integrity [P2-26, P2-27, P2-28, P2-29]

**Files:** `data/backup/BackupVerifier.kt`, `data/privacy/ExportAnonymizer.kt`, `data/privacy/DataRetentionWorker.kt`, `data/repository/DatabaseBackupRepositoryImpl.kt`  
**Complexity:** L | **Risk:** High | **Dependencies:** None

| Issue | Change |
|-------|--------|
| P2-26 | Move `privacy_audit_events` to TIER_3_OPTIONAL; move 6 event tables to correct section |
| P2-27 | Add `ai_chat_messages.content`, `ai_artifacts.content` nulling to `sanitizeExport()` |
| P2-28 | Replace `getUnpurgedRawNotificationsOlderThan(cutoff)` with paginated `LIMIT 500` loop |
| P2-29 | Use `PRAGMA wal_checkpoint(TRUNCATE)` before copy, or close DB connection |

**Testing:** Requires large-database test fixture (10k+ rows) to verify OOM fix and backup consistency.

---

### Batch 14: Worker Reschedule Loop [P2-30]

**Files:** `data/ai/worker/DailyBriefingWorker.kt`  
**Complexity:** S | **Risk:** Low | **Dependencies:** Batch A (P0-01) should be merged first so the worker actually runs

```kotlin
// BEFORE (after runGuarded block):
if (shouldScheduleNext) {
    runCatching { aiWorkScheduler.scheduleDailyBriefing() }
}

// AFTER:
if (shouldScheduleNext && guardResult !is WorkerGuardResult.Denied) {
    runCatching { aiWorkScheduler.scheduleDailyBriefing() }
}
```

---

## PHASE 3: P3 Fixes — 8 Issues (Optional)

These are low-priority cleanup items. Can be done opportunistically:

| ID | Fix | File |
|----|-----|------|
| P3-01 | Add `maxIterations = 1000` guard to advance loop | RecurringLifecycleCoordinator.kt |
| P3-02 | Add `writeReceiptEvent("SIDE_EFFECT_FAILED")` in matching catch | ReceiptSideEffectDispatcher.kt |
| P3-03 | Use `rawId` directly instead of `insertedExpense.rawNotificationId` | NotificationProcessingPipeline.kt |
| P3-04 | Change `DESIRED_HISTORY_MONTHS = 3.0` | BudgetForecastingEngine.kt |
| P3-05 | Add single-category query to MultiCurrencyRepository | BudgetRepository.kt |
| P3-06 | Remove dead `"PAID"` branch | FinancialStressForecastEngine.kt |
| P3-07 | Return FailClosed when no gate handles capability | CompositePrivacyGate.kt |
| P3-08 | Quote table name in verification SQL | DatabaseBackupRepositoryImpl.kt |

---

## Execution Schedule

```
Day 1: Batch A (P0-01) + Batch B (P1-01) + Batch C (P1-02/04/05)
        → Unblocks workers, restores dedup, prevents crashes
        
Day 2: Batch D (P1-03) + Batch E (P1-06) + Batch F (P1-07/08)
        → Fixes data leaks, notification UX, crash safety

Day 3: P2 Batches 1-5 (notification + receipt link fixes)
        → Low-risk, high-confidence fixes

Day 4: P2 Batches 6-9 (OCR + receipt atomicity + recurring + bank)
        → Medium complexity, isolated domains

Day 5: P2 Batch 10 (dashboard aggregation)
        → User-visible, needs careful testing

Day 6: P2 Batch 11 (forecast currency)
        → Financial accuracy, needs thorough verification

Day 7: P2 Batch 12 + 14 (cloud AI + worker loop)
        → Privacy + scheduling fixes

Day 8: P2 Batch 13 (backup integrity)
        → Highest risk, dedicated QA pass
```

---

## Verification Strategy

After each batch:
1. Run `./gradlew testDebugUnitTest`
2. Run affected integration tests
3. Verify no new `require()` crashes in logcat
4. For currency fixes: compare dashboard totals before/after with multi-currency test data
5. For backup fixes: full backup→restore→verify cycle

---

## Files Most Frequently Modified (Conflict Risk)

| File | Batches | Strategy |
|------|---------|----------|
| `SynthesisEngine.kt` | C, 11 | Batch C first (removes require), then 11 (normalizes input) |
| `NotificationCaptureService.kt` | B, 1 | Batch B first (dedup), then 1 (concurrency) |
| `ReceiptLifecycleCoordinator.kt` | D, 7 | Either order (different methods) |
| `TransactionLifecycleCoordinator.kt` | 4 only | No conflict |
| `ComputeDashboardWidgetsUseCase.kt` | 10 only | No conflict |
