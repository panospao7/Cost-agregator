# Deep Source Code Analysis Report — NEW Issues Found

> **Generated:** 2026-05-11  
> **Method:** Direct source code reading of all 12 pipeline implementation files at HEAD (`92d77385`)  
> **Scope:** Issues NOT already documented in existing pipeline debug reports  
> **Total NEW issues found:** 47 (1 P0, 8 P1, 30 P2, 8 P3)

---

## AI Implementation Instructions

This report contains issues discovered by reading actual source code. Each issue includes:
- Exact file path and line context
- Severity classification
- Root cause description
- Concrete fix strategy

**Priority order for fixing:**
1. P0 (1 issue) — App-breaking, feature completely non-functional
2. P1 (8 issues) — Data corruption, crashes, or security leaks
3. P2 (30 issues) — Incorrect behavior, race conditions, performance
4. P3 (8 issues) — Code quality, dead code, minor inconsistencies

---

## P0 — CRITICAL (1 issue)

### P0-01: `scheduleAtMidnight` ignores `workerClass` — midnight workers never run

**File:** `domain/workers/WorkerSpecScheduler.kt:167-171`  
**Context:**
```kotlin
val typedClass = workerClass as Class<ListenableWorker>
val request = OneTimeWorkRequestBuilder<ListenableWorker>()  // ← uses abstract base, not typedClass
```
**Impact:** Daily briefing worker and any other midnight-scheduled worker NEVER actually executes. WorkManager cannot instantiate abstract `ListenableWorker`.  
**Fix:** Replace `OneTimeWorkRequestBuilder<ListenableWorker>()` with `OneTimeWorkRequest.Builder(typedClass)`.

---

## P1 — HIGH (8 issues)

### P1-01: In-memory dedup key includes `timeProvider.now()` — deduplication completely disabled

**File:** `service/NotificationCaptureService.kt:420`  
**Context:**
```kotlin
val coarseDedupeKey = "$dedupeKeyRaw:$now"  // now = millisecond timestamp, always unique
```
**Impact:** Every notification gets a unique key. The in-memory dedup cache provides zero protection against rapid duplicate notifications. Only DB-level fingerprint check works.  
**Fix:** Remove `$now` from key. Use `sbn.key` or `sbn.key:${sbn.postTime}`. The time-window check already handles temporal expiry.

### P1-02: Review stuck in PROCESSING forever on validation failure

**File:** `data/repository/ReviewQueueRepository.kt:165-168`  
**Context:**
```kotlin
is CreateExpenseResult.ValidationFailed -> {
    validationError = result.errors.joinToString(", ")
    txAlreadyProcessed  // review status already transitioned to PROCESSING, never reverted
}
```
**Impact:** Review permanently stuck in PROCESSING state — invisible in pending queue, cannot be approved/rejected again.  
**Fix:** Add `pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.PENDING)` before returning.

### P1-03: `processEmailReceipt` leaks orphan receipt on duplicate detection

**File:** `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:650-668`  
**Context:**
```kotlin
savedId = scannedReceiptDao.insert(receipt)  // inserted
// ...
if (sourceId == -1L) {  // fingerprint conflict
    capturedDuplicate = Duplicate(...)
    return@withTransaction  // commits — orphan receipt persists
}
```
**Impact:** Ghost receipt rows accumulate over time with no EmailReceiptSource and no expense link.  
**Fix:** Delete orphan receipt before returning: `scannedReceiptDao.deleteById(savedId)`.

### P1-04: `SynthesisEngine` uses `runBlocking` inside suspend context — ANR/deadlock

**File:** `domain/logic/SynthesisEngine.kt:290`  
**Context:**
```kotlin
val occurrences = runBlocking { occurrenceDao.getByDateRange(monthStart, monthEnd) }
// Called from suspend fun calculateBlockPartyData
```
**Impact:** Blocks IO dispatcher thread. Can cause ANR or deadlock if dispatcher pool exhausted.  
**Fix:** Make `buildRecurringByDayFromOccurrences` a `suspend fun` and use direct suspend call.

### P1-05: `require()` crashes app on multi-currency planned expenses

**File:** `domain/logic/SynthesisEngine.kt:140,155`  
**Context:**
```kotlin
require(committedPlannedByCurrency.size <= 1) {
    "Multiple currencies in committed planned expenses"
}
```
**Impact:** Dashboard path passes raw planned expenses without normalization → `IllegalArgumentException` crash.  
**Fix:** Replace `require()` with graceful fallback: log warning, skip multi-currency planned, or convert inline.

### P1-06: Budget notification shows wrong currency symbol

**File:** `domain/budget/BudgetMonitor.kt:230`  
**Context:**
```kotlin
val currencySymbol = SupportedCurrency.fromCode(budget.currency)?.symbol  // budget's original currency
// But spent/limit amounts are already converted to HOME currency
```
**Impact:** USD budget with EUR home shows "€150" with "$" symbol — wrong currency for the amount.  
**Fix:** Use `status.currency` (home currency after conversion) instead of `budget.currency`.

### P1-07: RestoreJournal writes are non-atomic — crash corrupts recovery

**File:** `data/backup/RestoreJournal.kt:125`  
**Context:**
```kotlin
journalFile.writeText(text)  // truncates then writes — crash mid-write = partial/empty file
```
**Impact:** Journal's purpose is crash safety, but its own write is not crash-safe. Partial journal = unrecoverable state.  
**Fix:** Atomic write: write to temp file, `fd.sync()`, then `renameTo()` over real journal.

### P1-08: RestoreMaintenanceMode uses `apply()` — mode may not persist before crash

**File:** `data/backup/RestoreMaintenanceMode.kt:149`  
**Context:**
```kotlin
prefs.edit().putString(KEY_MAINTENANCE_MODE, mode.name).apply()  // async, not guaranteed on disk
```
**Impact:** If process crashes after `enter(RESTORE_SWAPPING)` but before flush, next startup sees NORMAL mode → writes against corrupt DB.  
**Fix:** Replace `.apply()` with `.commit()` (synchronous).

---

## P2 — MEDIUM (30 issues)

### P2-01: `NeedsReviewCreated` outcome loses rawId and reviewId (always 0L)

**File:** `data/repository/NotificationProcessingPipeline.kt:481`  
**Context:** `ParsedDbOutcome.NeedsReviewCreated` is an `object` with no data fields.  
**Fix:** Change to `data class NeedsReviewCreated(val rawId: Long, val reviewId: Long)`.

### P2-02: Double `linkExpenseToOccurrence` call on create path

**File:** `TransactionLifecycleCoordinator.kt:442` + `TransactionSideEffectDispatcher.kt:100`  
**Context:** `dispatchPostCreationSideEffects` calls `sideEffectDispatcher.dispatchOnCreated()` (which calls `linkExpenseToOccurrence`) AND then calls `linkExpenseToOccurrence` directly again.  
**Fix:** Remove the direct call from `dispatchPostCreationSideEffects`.

### P2-03: Privacy race window — `capturePrivacyDenied` starts `false`

**File:** `service/NotificationCaptureService.kt:283`  
**Context:** Initial value is `false` (capture allowed) until first settings emission arrives.  
**Fix:** Change initial value to `true` (fail-closed until confirmed).

### P2-04: Variable shadowing in `bulkUpdateMerchant`

**File:** `TransactionLifecycleCoordinator.kt:1270-1303`  
**Context:** `val affectedCount` inside transaction shadows outer `var affectedCount`.  
**Fix:** Remove shadowing `val`.

### P2-05: Cache cleanup race condition with `removeIf`

**File:** `service/NotificationCaptureService.kt:470-475`  
**Context:** `processedNotifications.entries.removeIf` on `synchronizedMap` without external sync.  
**Fix:** Wrap in `synchronized(processedNotifications)` or use `ConcurrentHashMap`.

### P2-06: `shouldCapture` receives `combinedBody` as `bigText` — inflated content

**File:** `service/NotificationCaptureService.kt:443-448`  
**Context:** Passes `parts.combinedBody` (all fields joined) where `bigText` is expected.  
**Fix:** Pass `parts.effectiveBigText` or `parts.bigText` instead.

### P2-07: DENY_KEYWORDS not applied to FINANCE_PACKAGES

**File:** `service/NotificationFilter.kt:107-114`  
**Context:** Finance packages return `true` after finding transaction signal without checking deny keywords.  
**Fix:** Add deny-keyword check before `return true`.

### P2-08: `ReceiptLinkService` returns link with `id=0`

**File:** `domain/receipt/lifecycle/ReceiptLinkService.kt:286`  
**Context:** Returns pre-insert object instead of `link.copy(id = linkId)`.  
**Fix:** Return `Result.success(link.copy(id = linkId))`.

### P2-09: `unlinkReceiptFromExpense` doesn't clear matchStatus/matchConfidence

**File:** `domain/receipt/lifecycle/ReceiptLinkService.kt:335-355`  
**Context:** Clears `expenseId` but leaves stale `matchStatus = AUTO_MATCHED`.  
**Fix:** Add `matchStatus = UNMATCHED, matchConfidence = null, suggestedExpenseId = null` to copy.

### P2-10: `ReceiptOcrService.close()` uses `runBlocking` — deadlock risk

**File:** `domain/receipt/ReceiptOcrService.kt:713`  
**Context:** Non-suspend `close()` uses `runBlocking` to acquire mutex held by OCR operations.  
**Fix:** Make `close()` suspend, or use `tryLock()` with timeout.

### P2-11: PendingIntent request code collision for Snooze/Dismiss

**File:** `service/reminder/BillReminderWorker.kt:148,159`  
**Context:** `delivery.id + 10000` can collide with another delivery's snooze code.  
**Fix:** Use hash-based approach or distinct action strings.

### P2-12: OVERDUE reminder scheduled same time as DUE_DAY

**File:** `domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt:194`  
**Context:** Both windows map to `dueDate` — no actual overdue delay.  
**Fix:** Schedule OVERDUE at `dueDate + 1 day`.

### P2-13: `reconcilePlannedVsActual` has write side-effect in read method

**File:** `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt:441`  
**Context:** Calls `generateOccurrences()` which writes to DB.  
**Fix:** Separate ensure-occurrences from report, or rename to `ensureAndReconcile`.

### P2-14: Bank statement `processingStatus` not updated to REVIEW_CREATED

**File:** `domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt:200-210`  
**Context:** Event says PARSED→REVIEW_CREATED but receipt entity stays PARSED.  
**Fix:** Add `scannedReceiptDao.updateProcessingStatus(receiptId, REVIEW_CREATED, now)`.

### P2-15: Nested transaction — `createExpenseFromReceipt` link failure not propagated

**File:** `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:870`  
**Context:** Inner `linkReceiptToExpense` failure returns `Result.failure` but outer transaction commits.  
**Fix:** Check link result and throw on failure to trigger rollback.

### P2-16: `TotalsAggregationEngine.reactiveFlow` infinite busy-loop

**File:** `domain/analytics/TotalsAggregationEngine.kt:310-325`  
**Context:** `while(true) { .first() }` on Room Flow re-subscribes immediately — never waits for changes.  
**Fix:** Use `collect { emit(block()) }` or `flatMapLatest`.

### P2-17: `monthlyIncome` uses type-agnostic total (includes expenses)

**File:** `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`  
**Context:** `getHomeCurrencyTotal` sums ALL types for runway income calculation.  
**Fix:** Use `getHomeCurrencyDepositTotal` which filters to DEPOSIT only.

### P2-18: CashFlowCalculator `startingBalance` has no currency context

**File:** `domain/cashflow/CashFlowCalculator.kt:75`  
**Context:** Raw `Double` parameter with no currency — can mix currencies with normalized daily deltas.  
**Fix:** Add `startingBalanceCurrency` parameter with conversion or `require()` guard.

### P2-19: `resolveStartingBalanceBaseline` allows negative balance (docs say floored)

**File:** `domain/forecasting/FinancialStressForecastEngine.kt:350`  
**Context:** Returns `netCashflow` without `.coerceAtLeast(0.0)` despite documentation.  
**Fix:** Apply `.coerceAtLeast(0.0)` or change mode to ESTIMATED_INDEX when negative.

### P2-20: ForecastInputAssembler doesn't normalize recurring pattern amounts

**File:** `domain/forecasting/ForecastInputAssembler.kt:300`  
**Context:** `mergeRecurringPatterns` returns patterns with original currency amounts — SynthesisEngine sums them raw.  
**Fix:** Normalize each pattern's `averageAmount` to home currency after merge.

### P2-21: CashFlowCalculator silently drops failed currency conversions

**File:** `domain/cashflow/CashFlowCalculator.kt:175-185`  
**Context:** `if (converted != null) dayIncome += ...` — else silently dropped, no warning.  
**Fix:** Track failures per day, add `isPartial` flag to `DailyCashFlow`.

### P2-22: `observeCategoryBreakdown` N+1 Flow subscriptions

**File:** `data/repository/DashboardContractsAdapter.kt:115-135`  
**Context:** Inside `.map {}`, calls `.first()` on separate previous-period Flow per emission.  
**Fix:** Use `combine()` to merge both period Flows reactively.

### P2-23: Spending trend raw-sums `effectiveAmount` without normalization

**File:** `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` (computeSpendingTrend)  
**Context:** `daily[dayIdx] += exp.effectiveAmount` — mixes currencies.  
**Fix:** Normalize amounts to home currency before accumulation.

### P2-24: `suggestFromText()` sends raw prompt without redaction

**File:** `data/ai/provider/CloudReceiptAssistService.kt:238`  
**Context:** Sends caller-provided prompt directly to Gemini API without `redactor.redactText()`.  
**Fix:** Apply `redactor.redactText(prompt, BANK_STATEMENT)` when `redactBeforeCloud = true`.

### P2-25: `suggestFromText()` response not closed on retry — connection leak

**File:** `data/ai/provider/CloudReceiptAssistService.kt:275-285`  
**Context:** `client.newCall(request).execute()` without `.use {}` on retry path.  
**Fix:** Wrap response in `.use { }`.

### P2-26: BackupVerifier tier assignments contradict comments — 7 tables misplaced

**File:** `data/backup/BackupVerifier.kt:70-92`  
**Context:** 6 event tables + `privacy_audit_events` assigned TIER_1_EXACT but placed in Tier 2/3 sections.  
**Fix:** Move to correct tier or change assignment to TIER_2_VALIDITY/TIER_3_OPTIONAL.

### P2-27: ExportAnonymizer incomplete — misses AI chat, artifacts, group members

**File:** `data/privacy/ExportAnonymizer.kt`  
**Context:** Only strips raw OCR and notification content. AI/group/bank data untouched.  
**Fix:** Extend to null out `ai_chat_messages.content`, `ai_artifacts.content`, redact group/bank fields.

### P2-28: DataRetentionWorker loads all candidates into memory — OOM risk

**File:** `data/privacy/DataRetentionWorker.kt:122,148`  
**Context:** `dao.getUnpurgedRawNotificationsOlderThan(cutoff)` loads all qualifying rows at once.  
**Fix:** Use paginated query (`LIMIT 500` with repeated queries).

### P2-29: WAL race — DB not closed before backup copy

**File:** `data/repository/DatabaseBackupRepositoryImpl.kt:467-489`  
**Context:** After `checkpointWal()`, DB connection stays open. Writes can create new WAL during copy.  
**Fix:** Close connection before copy, or use `PRAGMA wal_checkpoint(TRUNCATE)`.

### P2-30: DailyBriefingWorker infinite reschedule when privacy-denied

**File:** `data/ai/worker/DailyBriefingWorker.kt:100-108`  
**Context:** On guard-denied path, `shouldScheduleNext = true` → reschedules → denied again → loop.  
**Fix:** Only reschedule on Success or Skipped-fresh-artifact, not on privacy-denied.

---

## P3 — LOW (8 issues)

### P3-01: Unbounded advance loop for far-past anchorDate

**File:** `domain/recurring/RecurringLifecycleCoordinator.kt` (generateOccurrences while-loop)  
**Context:** If `rule.nextDate = 0` and frequency is WEEKLY, loop iterates ~2800 times for 50 years.  
**Fix:** Add max iteration guard (1000) or clamp anchorDate.

### P3-02: Transaction matching failure not written as lifecycle event

**File:** `domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt:118-120`  
**Context:** Catch block only logs, unlike other side effects that write SIDE_EFFECT_FAILED events.  
**Fix:** Add `writeReceiptEvent(receipt, "SIDE_EFFECT_FAILED", ...)` in catch.

### P3-03: `AutoAccepted` outcome uses fragile `insertedExpense.rawNotificationId`

**File:** `data/repository/NotificationProcessingPipeline.kt:476`  
**Context:** Uses pre-insert template object field instead of `rawId` variable directly.  
**Fix:** Use `rawId = rawId` directly.

### P3-04: Forecast confidence misaligned — 3 months fetched but 4 expected

**File:** `domain/budget/BudgetForecastingEngine.kt:155,210`  
**Context:** Fetches 3 months history but `DESIRED_HISTORY_MONTHS = 4.0` — max confidence 75%.  
**Fix:** Change `DESIRED_HISTORY_MONTHS` to 3.0 or extend query to -4 months.

### P3-05: N+1 category budget query

**File:** `data/repository/BudgetRepository.kt:195`  
**Context:** `getHomeCurrencyPurchaseCategoryTotals` fetches ALL categories to extract one.  
**Fix:** Add single-category query method.

### P3-06: Dead code — PAID branch in stress forecast never reached

**File:** `domain/forecasting/FinancialStressForecastEngine.kt:235-240`  
**Context:** `when (occ.status) { "PAID" -> ... }` unreachable because PAID filtered above.  
**Fix:** Remove dead branch.

### P3-07: CompositePrivacyGate fail-open for unhandled capabilities

**File:** `domain/privacy/CompositePrivacyGate.kt:30`  
**Context:** All-NotApplicable returns Allowed — new capabilities silently pass.  
**Fix:** Return FailClosed when no gate handles the capability.

### P3-08: Unquoted table name in verification SQL

**File:** `data/repository/DatabaseBackupRepositoryImpl.kt:276`  
**Context:** `"SELECT COUNT(*) FROM $tableName"` — no quotes around table name.  
**Fix:** Use `"SELECT COUNT(*) FROM \"$tableName\""`.

---

## Cross-Pipeline Pattern Analysis

### Pattern A: Nested Transaction Misuse (3 instances)
- P2-15: `createExpenseFromReceipt` inner link failure not propagated
- P2-04/Issue 7: `ManualExpenseRepository` wraps coordinator that opens own transaction
- P1-03: `processEmailReceipt` orphan on duplicate within transaction

**Root cause:** Room's `withTransaction` commits on normal return. Inner failures that return `Result.failure` don't trigger rollback.  
**Systemic fix:** Establish pattern: throw exceptions for rollback, catch outside transaction for error handling.

### Pattern B: Currency Mixing in Aggregation (5 instances)
- P2-17: monthlyIncome type-agnostic
- P2-20: recurring patterns not normalized
- P2-21: cashflow drops conversions silently
- P2-23: spending trend raw-sums
- P1-05: require() crash on multi-currency planned

**Root cause:** Multiple paths bypass `AnalyticsCurrencyNormalizer` and sum raw `effectiveAmount`.  
**Systemic fix:** Create `NormalizedDashboardInput` that all widgets consume. Ban raw `effectiveAmount` summation.

### Pattern C: Crash-Safety Gaps in Restore (3 instances)
- P1-07: RestoreJournal non-atomic write
- P1-08: RestoreMaintenanceMode async apply
- P2-29: WAL race during backup

**Root cause:** File/preference operations use non-atomic patterns in crash-critical paths.  
**Systemic fix:** Atomic write helper: temp file → sync → rename. Use `commit()` for all restore-critical prefs.

### Pattern D: Missing Cancellation/Cleanup (4 instances)
- P2-05: cache cleanup race
- P2-30: infinite reschedule loop
- P0-01: midnight workers never run
- P2-07 (export): no ensureActive() in streaming loop

**Root cause:** Coroutine lifecycle and WorkManager scheduling not properly managed.  
**Systemic fix:** Add `ensureActive()` checkpoints in all loops. Fix `scheduleAtMidnight` immediately (P0).

---

## Recommended Fix Order

### Immediate (P0 + critical P1):
1. **P0-01** — `scheduleAtMidnight` fix (1 line change, unblocks all midnight workers)
2. **P1-01** — Dedup key fix (1 line change, restores notification dedup)
3. **P1-02** — Review PROCESSING revert (3 lines, prevents stuck reviews)
4. **P1-05** — Remove `require()` crash (replace with graceful handling)
5. **P1-06** — Budget notification currency symbol (1 line fix)
6. **P1-07 + P1-08** — Restore journal/mode atomic writes (small focused PR)

### High Priority (remaining P1 + critical P2):
7. **P1-03** — Email receipt orphan cleanup
8. **P1-04** — SynthesisEngine runBlocking removal
9. **P2-16** — TotalsAggregationEngine busy-loop fix
10. **P2-02** — Double linkExpenseToOccurrence removal
11. **P2-15** — Nested transaction link failure propagation

### Medium Priority (P2 batch):
12. Currency normalization batch (P2-17, P2-20, P2-21, P2-23)
13. Privacy/security batch (P2-03, P2-24, P2-25, P2-27)
14. Worker/scheduling batch (P2-30, P2-11, P2-12)
15. Receipt/lifecycle batch (P2-08, P2-09, P2-14)

---

## Verification Commands

```bash
# Find all runBlocking usage in suspend contexts
grep -rn "runBlocking" app/src/main/java --include="*.kt" | grep -v "test"

# Find all require() that could crash from data issues
grep -rn "require(" app/src/main/java/com/yourname/expensetracker/domain --include="*.kt"

# Find all .apply() in backup/restore code
grep -rn "\.apply()" app/src/main/java/com/yourname/expensetracker/data/backup --include="*.kt"

# Find all raw effectiveAmount summation
grep -rn "effectiveAmount" app/src/main/java/com/yourname/expensetracker/domain --include="*.kt" | grep -i "sum\|+="

# Find nested withTransaction patterns
grep -rn "withTransaction" app/src/main/java --include="*.kt" -A5 | grep -B5 "withTransaction"
```
