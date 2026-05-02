# Review: Core Expense Lifecycle — Cross-Check Against Current Codebase

**Review date:** 2026-05-02  
**Source analysis:** `docs/analyses and debug master/core-expense-lifecycle-analysis.md`  
**Target branch:** current worktree  
**Status of referenced branch:** `master-refactor` (analysis), now merged into current codebase

---

## VERDICT: FAIL

Substantial progress has been made — the `TransactionLifecycleCoordinator` now exists and is actively used by all main paths. However, 11 out of 18 originally flagged issues remain **STILL PRESENT** and 6 are only **PARTIALLY RESOLVED**. Only 1 issue is fully resolved.

---

## Executive Summary

The biggest improvement is the creation of `TransactionLifecycleCoordinator` (PR 1 recommendation). It now serves as the single entry point for expense creation across:

- `ReviewQueueRepository.approveReview()`
- `ReviewQueueRepository.markAsRelevant()`
- `NotificationProcessingPipeline.handleAutoAcceptInTransaction()`
- `ExpenseRepository.deleteExpense()` / `updateExpense()`

The coordinator includes:
- A canonical `validate()` method (covers amount, merchant, currency ISO, date plausibility, transfer metadata)
- Full deduplication with the `DuplicateDetectionPolicy` policy
- Atomic insert + `TransactionEvent` logging inside one Room transaction
- A `TransactionSideEffectDispatcher` for post-commit budget checks and anomaly alerts

**However**, the analysis recommended 8 PRs, and the codebase has implemented only PR 1 (transaction lifecycle coordinator) and parts of PRs 2–5. The remaining DAO footguns, incomplete review model, duplicate resolution records, and raw dedup improvements are still outstanding.

---

## Issue-by-Issue Cross-Check

### [ISSUE-1] `markAsRelevant()` can bypass the canonical duplicate gate
**Status: RESOLVED** ✅

- **Analysis claim:** `markAsRelevant()` called `expenseDao.insertAtomic()` directly without canonical duplicate check.
- **Current code:** `ReviewQueueRepository.markAsRelevant()` (line 542) now constructs a `CreateExpenseRequest` and calls `transactionLifecycleCoordinator.createExpense(request)`. The coordinator internally runs `isDuplicateCurrencyAware` with STANDARD dedup mode. No direct DAO insert bypass remains.
- **File:** `ReviewQueueRepository.kt`, lines 541–554

---

### [ISSUE-2] Fallback pending reviews use fake money: `0.01 EUR`
**Status: PARTIALLY RESOLVED** ⚠️

- **Analysis claim:** Pending review with `amount=0.01, currency=EUR, merchant=Unknown, confidence=1.0` when parser fails.
- **Current code:**
  - `ReviewQueueRepository.markAsRelevant()` (lines 497–511): **Still** creates `PendingReview` with `suggestedAmount = FALLBACK_SUGGESTED_AMOUNT (0.01)` and `confidence = 1.0f`.
  - `ReviewQueueRepository.approveReview()` (lines 117–128): **NEW** blocking gates — returns error if `suggestedAmount == 0.01 && finalAmount == null` or `suggestedMerchant == "Unknown" && finalMerchant == null`.
  - `TransactionLifecycleCoordinator.validate()` (lines 439–452): **NEW** validator rejects `merchant == "Unknown"` and `amount <= 0` or non-finite.
- **Assessment:** The fake values are now effectively **guarded from becoming real expenses**. However:
  - The `confidence = 1.0f` is still misleading (parser actually failed).
  - The analysis recommended adding `extractionState`, `missingFields`, `requiresManualAmount`, `requiresManualCurrency` to the `PendingReview` model — not implemented.
  - The fake placeholder values still pollute the `pending_reviews` table and could confuse future consumers.
- **Files:** `ReviewQueueRepository.kt` lines 497–511, 117–128; `TransactionLifecycleCoordinator.kt` lines 434–485

---

### [ISSUE-3] DAO method `approveAllPending()` marks reviews approved without creating expenses
**Status: STILL PRESENT** ❌

- **Analysis claim:** `PendingReviewDao.approveAllPending()` bypasses expense creation, duplicate check, etc.
- **Current code:** `PendingReviewDao.kt` lines 88–89: **Both footguns still exist:**
  ```kotlin
  @Query("UPDATE pending_reviews SET status = 'APPROVED' WHERE status = 'PENDING'")
  suspend fun approveAllPending()

  @Query("UPDATE pending_reviews SET status = 'REJECTED' WHERE status = 'PENDING'")
  suspend fun rejectAllPending()
  ```
- **Assessment:** While the repository methods `approveAllReview()` and `rejectAllReviews()` correctly loop through individual `approveReview()`/`rejectReview()`, these raw DAO methods remain a dangerous footgun. Any future caller using them directly will cause silent financial data loss.
- **File:** `PendingReviewDao.kt` lines 88–92

---

### [ISSUE-4] `PendingReviewDao.insert()` uses `REPLACE`
**Status: STILL PRESENT** ❌

- **Analysis claim:** `@Insert(onConflict = OnConflictStrategy.REPLACE)` can reset review status and replace audit fields.
- **Current code:** `PendingReviewDao.kt` line 12: **Still:**
  ```kotlin
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(review: PendingReview): Long
  ```
- **Assessment:** The `upsertByRawNotificationId()` method (lines 56–68) has been improved to preserve `id`, `scannedReceiptId`, `createdAt`, and `status`. But the raw `insert()` with REPLACE remains available and dangerous. If called directly with a new `PendingReview` that has a conflicting `rawNotificationId` (unique index), the existing row is silently deleted and replaced — losing status, createdAt, and receipt linkage.
- **File:** `PendingReviewDao.kt` line 12

---

### [ISSUE-5] Approval validates only upper amount, not complete money correctness
**Status: PARTIALLY RESOLVED** ⚠️

- **Analysis claim:** Approval only rejects amount > 1,000,000 but not amount ≤ 0, NaN, blank merchant, invalid currency, etc.
- **Current code:** `TransactionLifecycleCoordinator.validate()` (lines 434–485) now validates:
  - ✅ Amount must be positive, finite, and ≤ 1,000,000
  - ✅ Merchant must not be blank or placeholder ("Unknown", "Parsing Failed")
  - ✅ Currency must be a valid 3-letter uppercase ISO code (`^[A-Z]{3}$`)
  - ✅ Date must be positive and not in the future (beyond now + 1 day)
  - ✅ Transfer direction + accountName required for TRANSFER type
  - ✅ Ownership conflict protection (cannot be both isNotMine and isSharedExpense)
- **Assessment:** The validator is comprehensive and used by all coordinator paths. However:
  - The location-pair validation (issue 18) is still missing.
  - The validator is embedded in the coordinator as a private method, not a standalone `ExpenseDraftValidator` class as the analysis recommended — but this is a minor structural preference.
- **Files:** `TransactionLifecycleCoordinator.kt` lines 434–485

---

### [ISSUE-6] Approval UI/path cannot correct currency
**Status: STILL PRESENT** ❌

- **Analysis claim:** `approveReview()` accepts overrides for amount, merchant, category, date, type, location — but not currency.
- **Current code:** `ReviewQueueRepository.approveReview()` signature (lines 99–110): **Still no `finalCurrency` parameter.** The expense is always created with `review.suggestedCurrency` (line 164).
- **Assessment:** If the parser/AI detects wrong currency, the user cannot fix it during approval. The analysis correctly categorized this as "High / Critical with multi-currency."
- **Files:** `ReviewQueueRepository.kt` lines 99–110, 164

---

### [ISSUE-7] Duplicate outcomes do not link to the duplicate target
**Status: PARTIALLY RESOLVED** ⚠️

- **Analysis claim:** Duplicate detection doesn't persist matched expense ID, reason, matched fields, confidence.
- **Current code:**
  - `CreateExpenseResult.DuplicateSkipped` (CreateExpenseResult.kt lines 4–5): **NEW** — now carries `existingExpenseId: Long` and `reason: String`.
  - `TransactionLifecycleCoordinator.createExpense()` (lines 196–208): Returns `DuplicateSkipped` with the matched expense ID.
  - However, `ReviewQueueRepository.approveReview()` (lines 290–301): When coordinator returns `DuplicateSkipped`, it only updates the review status to `DUPLICATE` and adjusts source stats. **It does NOT persist** the `existingExpenseId` or `reason` anywhere.
- **Assessment:** The infrastructure to carry the duplicate target exists, but callers don't persist it. No `DuplicateResolution` audit record has been created. Receipt attachment to existing duplicate expense is still not supported.
- **Files:** `CreateExpenseResult.kt` lines 4–5; `ReviewQueueRepository.kt` lines 290–301; `NotificationProcessingPipeline.kt` lines 786–789

---

### [ISSUE-8] Raw duplicate check happens after parse/AI fallback
**Status: STILL PRESENT** ❌

- **Analysis claim:** Duplicate raw notification triggers parse + AI before being rejected as duplicate.
- **Current code:** `NotificationProcessingPipeline.processInternal()` (lines 141–154): Still `parseWithAiFallback()` first, then `insertRawNotificationIfNotDuplicate()`. No fingerprint pre-check exists.
- **Assessment:** Cost/privacy/performance risk remains. A duplicate notification still calls the AI fallback path before the DB says "already seen."
- **File:** `NotificationProcessingPipeline.kt` lines 148–154

---

### [ISSUE-9] Raw notification dedupe still depends on fragile fields
**Status: STILL PRESENT** ❌

- **Analysis claim:** Dedupe uses package/timestamp/title/text — breaks on slight timestamp/text variations.
- **Current code:** `RawNotificationDao.exists()` (RawNotificationDao.kt lines 46–56): **Same query** — `packageName + timestamp + title + text + bigText`. No content fingerprint-based deduplication has been added.
- **Assessment:** The two-layer approach (notification fingerprint + transaction candidate fingerprint) recommended by the analysis is not implemented.
- **File:** `RawNotificationDao.kt` lines 46–56

---

### [ISSUE-10] Debug/manual recovery path has inconsistent side effects
**Status: PARTIALLY RESOLVED** ⚠️

- **Analysis claim:** `markAsRelevant()` runs budget check and classifier training but not anomaly alert, recommendation enrichment, subscription detection, transfer analytics.
- **Current code:**
  - `markAsRelevant()` now goes through `transactionLifecycleCoordinator.createExpense()`, which triggers `TransactionSideEffectDispatcher.dispatchOnCreated()` — this covers **budget check** and **anomaly alert**.
  - Additional post-commit actions in `markAsRelevant()` (lines 597–623) cover **classifier training**, **classifier retraining**, and **source stats cache invalidation**.
  - Compared to normal `NotificationProcessingPipeline` auto-accept path (lines 894–916), the following are still missing from `markAsRelevant()`:
    - ❌ **Recommendation enrichment** (`launchRecommendationEnrichment`)
    - ❌ **Subscription detection** (`launchSubscriptionDetection`)
    - ❌ **Transfer analytics** (`runTransferAnalyticsPostCommit`)
- **Assessment:** Much improved, but not identical to the normal auto-accept path. The analysis's recommendation to have one `onExpenseCreated(expense, source)` method is partially achieved via the dispatcher, but source-specific side effects are still duplicated.
- **Files:** `TransactionSideEffectDispatcher.kt`; `ReviewQueueRepository.kt` lines 597–623; `NotificationProcessingPipeline.kt` lines 894–916

---

### [ISSUE-11] `NotificationRepository.deleteAll()` deletes all expenses
**Status: STILL PRESENT** ❌

- **Analysis claim:** Method named `deleteAll()` in `NotificationRepository` also wipes `expenses`, `pending_reviews`, `user_corrections`.
- **Current code:** `NotificationRepository.deleteAll()` (lines 125–133): **Unchanged** — still deletes:
  ```kotlin
  dao.deleteAll()        // raw_notifications
  expenseDao.deleteAll() // ⚠️ expenses
  pendingReviewDao.deleteAll()
  userCorrectionDao.deleteAll()
  sourceStatsDao.resetAllPendingCounts()
  ```
- **Assessment:** The method has not been renamed, split, or restricted. It is still a single call away from wiping all financial history.
- **File:** `NotificationRepository.kt` lines 125–133

---

### [ISSUE-12] Deleting a raw notification can detach source audit from approved expenses
**Status: STILL PRESENT** ❌

- **Analysis claim:** FK `ON DELETE SET NULL` removes source link from approved expenses.
- **Current code:** `Expense.kt` line 20: `onDelete = ForeignKey.SET_NULL` **unchanged**.
- **Assessment:**
  - A `source` field (`String?`) has been added to `Expense` (line 75) — this captures the source type (e.g., "NOTIFICATION_AUTO_ACCEPT") but does NOT contain the source identity details (package name, timestamp, notification ID).
  - No immutable source metadata fields (`sourcePackage`, `sourceCreatedAt`, `sourceFingerprint`, `sourceConfidence`, `sourceReviewId`) have been added.
- **Files:** `Expense.kt` lines 15–21, 75

---

### [ISSUE-13] Nullable `dedupeKey` weakens DB-level duplicate prevention
**Status: PARTIALLY RESOLVED** ⚠️

- **Analysis claim:** SQLite allows multiple `NULL` in unique index — any path that forgets dedupeKey bypasses atomic guard.
- **Current code:** `Expense.kt` line 81: `dedupeKey` is **still nullable** (`String?`).
- **Assessment:** All current paths through `TransactionLifecycleCoordinator` generate a non-null dedupeKey via `DuplicateDetectionPolicy.generateDedupeKeyWithType()`. However:
  - Manual entries, CSV imports, and any future path that calls `expenseDao.insert()` directly (not `insertAtomic()`, but the plain `insert()`) could leave it null.
  - The column schema itself doesn't enforce non-null — no DB CHECK constraint added.
- **Files:** `Expense.kt` line 81; `ExpenseDao.kt` lines 86–90

---

### [ISSUE-14] `rawNotificationId` is not unique on `Expense`
**Status: STILL PRESENT** ❌

- **Analysis claim:** One raw notification can link to multiple expense rows at DB level.
- **Current code:** `Expense.kt` line 36: `Index(value = ["rawNotificationId"])` — **not unique**, unchanged.
- **Assessment:** Still no DB-level enforcement. The current pipeline may prevent this in practice (coordinator, dedup checks), but a concurrent race or a future bypass path could create duplicates.
- **File:** `Expense.kt` line 36

---

### [ISSUE-15] Resolved reviews can be mutated by upsert logic
**Status: PARTIALLY RESOLVED** ⚠️

- **Analysis claim:** `upsertByRawNotificationId()` can alter suggested fields for already resolved reviews.
- **Current code:** `PendingReviewDao.upsertByRawNotificationId()` (lines 56–68): Now preserves `existing.status`, `existing.id`, `existing.scannedReceiptId`, `existing.createdAt`. However, it **still updates** suggested fields (`suggestedAmount`, `suggestedMerchant`, `suggestedCurrency`, `suggestedType`, etc.) via the `review.copy()` on lines 59–65.
- **Assessment:** Status is preserved (can't accidentally re-open a resolved review), but the **audit drift** concern remains: if the same raw notification is reprocessed later with different parser output, the suggested amount/merchant/category on the APPROVED/DULICATE row can change while status stays APPROVED. The analysis recommended restricting upsert to only `WHERE status = 'PENDING'`.
- **File:** `PendingReviewDao.kt` lines 56–68

---

### [ISSUE-16] Source stats are mutable counters, not event-derived
**Status: STILL PRESENT** ❌

- **Analysis claim:** Increment/decrement counters can drift across many paths.
- **Current code:** `SourceStatsDao.kt` — still purely counter-based with no event ledger. No stats consistency checker has been added.
- **Assessment:** While the coordinator with `TransactionEvent` provides some event logging for expenses, source stats themselves remain mutable counters that can drift.
- **File:** `SourceStatsDao.kt` (entire file)

---

### [ISSUE-17] Manual/bulk approval is partial and not clearly reported
**Status: STILL PRESENT** ❌

- **Analysis claim:** `approveAllReview()` catches failures but only logs; no structured result.
- **Current code:** `ReviewQueueRepository.approveAllReview()` (lines 412–421): Still catches `Exception` and logs via `Timber.e`. No `BulkReviewResult` with per-item success/duplicate/failure counts.
- **Assessment:** UI callers of bulk approval cannot programmatically determine which items succeeded, which were duplicates, and which failed.
- **File:** `ReviewQueueRepository.kt` lines 412–421

---

### [ISSUE-18] Location approval can create partial location state
**Status: STILL PRESENT** ❌

- **Analysis claim:** `locationSource` set to USER_MANUAL when `finalLatitude != null`, but longitude may be null.
- **Current code:** `ReviewQueueRepository.approveReview()` (lines 185–193):
  ```kotlin
  latitude = finalLatitude ?: review.suggestedLatitude,
  longitude = finalLongitude ?: review.suggestedLongitude,
  locationSource = when {
      finalLatitude != null -> AppConfig.Location.SOURCE_USER_MANUAL
      review.suggestedLatitude != null -> AppConfig.Location.SOURCE_DEVICE_GPS
      else -> null
  }
  ```
  If caller passes `finalLatitude = 42.0` and `finalLongitude = null`, the expense gets `latitude=42.0, longitude=suggestedLongitude` with `locationSource=USER_MANUAL`.
- **Assessment:** No pair-validation exists. The analysis recommended "both latitude and longitude, or neither."
- **File:** `ReviewQueueRepository.kt` lines 185–193

---

## Summary Table

| # | Issue | Status |
|---|-------|--------|
| 1 | `markAsRelevant()` bypasses canonical duplicate gate | ✅ RESOLVED |
| 2 | Fallback pending reviews use fake money `0.01 EUR` | ⚠️ PARTIALLY RESOLVED |
| 3 | `PendingReviewDao.approveAllPending()` footgun | ❌ STILL PRESENT |
| 4 | `PendingReviewDao.insert()` uses `REPLACE` | ❌ STILL PRESENT |
| 5 | Approval validates only upper amount | ⚠️ PARTIALLY RESOLVED |
| 6 | Approval cannot correct currency | ❌ STILL PRESENT |
| 7 | Duplicate outcomes don't link to target | ⚠️ PARTIALLY RESOLVED |
| 8 | Raw duplicate check after AI/parse | ❌ STILL PRESENT |
| 9 | Fragile raw notification dedup fields | ❌ STILL PRESENT |
| 10 | `markAsRelevant()` inconsistent side effects | ⚠️ PARTIALLY RESOLVED |
| 11 | `NotificationRepository.deleteAll()` wipes expenses | ❌ STILL PRESENT |
| 12 | FK SET NULL detaches source audit | ❌ STILL PRESENT |
| 13 | Nullable `dedupeKey` weakens DB guard | ⚠️ PARTIALLY RESOLVED |
| 14 | `rawNotificationId` not unique on Expense | ❌ STILL PRESENT |
| 15 | Resolved reviews mutable by upsert | ⚠️ PARTIALLY RESOLVED |
| 16 | Mutable source stats counters | ❌ STILL PRESENT |
| 17 | Bulk approval no structured result | ❌ STILL PRESENT |
| 18 | Location pair not validated | ❌ STILL PRESENT |

---

## Additional Observations (Not in Original Analysis)

### A. `TransactionLifecycleCoordinator` is a strong foundation

The coordinator now provides:
- A single `createExpense()` entry point
- Comprehensive validation (amount, merchant, currency ISO, date, transfer metadata)
- Type-aware dedupe key generation via `DuplicateDetectionPolicy.generateDedupeKeyWithType()`
- Atomic insert + `TransactionEvent` logging in one Room transaction
- `TransactionSideEffectDispatcher` for post-commit budget checks and anomaly alerts

### B. `TransactionSideEffectDispatcher` covers budget + anomaly, but not all source-specific effects

The dispatcher covers:
- ✅ Budget check
- ✅ Anomaly alert
- ✅ Merchant-category pattern learning

But source-specific effects (recommendation enrichment, subscription detection, transfer analytics, receipt linking, notification relevance marking) are still handled ad-hoc in each caller. This means `markAsRelevant()` still misses recommendation enrichment and subscription detection compared to the normal pipeline path.

### C. Coordinator `validate()` rejects `"Unknown"` merchant — good

The validator (line 450) explicitly rejects `merchant == "Unknown"` and `"Parsing Failed"` as placeholders. Combined with the `approveReview()` gate (line 123), the `0.01 EUR` sentinel cannot become a real expense — **regardless of code path**.

### D. `dedupeKey` generation now type-aware

The `DuplicateDetectionPolicy.generateDedupeKeyWithType()` appends `_PURCHASE` / `_TRANSFER` / `_DEPOSIT` to the key. This properly prevents PURCHASE and DEPOSIT rows from colliding on the same unique index. `UNKNOWN` type falls back to the type-blind key for backward compatibility.

### E. Coordinator `updateExpense()` recomputes `dedupeKey` when key fields change

When merchant, date, amount, currency, or transactionType change, the coordinator generates a new dedupeKey and checks for duplicates (excluding the current expense). This addresses regression test #17 from the analysis.

### F. `NotificationRepository.delete()` properly handles pending counts

When deleting a raw notification that has a PENDING review, it decrements the pending count (lines 110–112). Good.

---

## Recommended Fix Order (Prioritized)

### Immediate (Critical)
1. **[ISSUE-3]** Remove or restrict `PendingReviewDao.approveAllPending()` and `rejectAllPending()` DAO methods.
2. **[ISSUE-11]** Rename/split `NotificationRepository.deleteAll()` with clear danger warnings.
3. **[ISSUE-6]** Add `finalCurrency` parameter to `approveReview()`.

### Short-term (High)
4. **[ISSUE-2]** Replace `confidence = 1.0f` on parser-failed reviews with a sentinel (e.g., `-1.0f` or add `extractionState`).
5. **[ISSUE-8]** Add raw notification fingerprint pre-check before `parseWithAiFallback()`.
6. **[ISSUE-4]** Change `PendingReviewDao.insert()` conflict strategy to `IGNORE`.
7. **[ISSUE-18]** Validate location pair completeness in the coordinator.

### Medium-term
8. **[ISSUE-7]** Persist `DuplicateSkipped.existingExpenseId` to an audit record.
9. **[ISSUE-10]** Add recommendation enrichment and subscription detection to `markAsRelevant()` post-commit.
10. **[ISSUE-9]** Implement content fingerprint for raw notification dedup.
11. **[ISSUE-15]** Restrict `upsertByRawNotificationId()` to only update PENDING rows.

### Long-term
12. **[ISSUE-16]** Event-ledger for source stats.
13. **[ISSUE-12]** Immutable source metadata on `Expense`.
14. **[ISSUE-13]** Make `dedupeKey` non-null with DB CHECK.
15. **[ISSUE-14]** Unique index on `rawNotificationId`.
16. **[ISSUE-17]** Structured `BulkReviewResult` return.

---

## Coverage

- **Requirements met:** Partially. PR 1 (TransactionLifecycleCoordinator) is implemented and working. But PRs 2–8 from the analysis's recommended fix order are largely outstanding. The most critical financial-data-integrity guardrails (coordinator, validator, approval gates) are in place, but the "lower-hanging fruit" DAO footguns and incomplete models persist.

- **Testing adequate:** The analysis listed 18 regression tests. While the coordinator provides a testable boundary for many of them (e.g., double-tap approval is protected by `transitionStatus()` + `insertAtomic()`), no dedicated lifecycle integration tests were observed in the reviewed files. The analysis was clear that tests were not verified at runtime, and neither was this review.

---

*Review generated by automated cross-check of `core-expense-lifecycle-analysis.md` against the current `app/src/main/java/com/yourname/expensetracker` codebase.*
