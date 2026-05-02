# TRN Issue Reconciliation Report

> Generated: 2026-05-02
> Source: MASTER-ISSUE-REGISTRY.md (Transaction Lifecycle section, issues TRN-1 through TRN-18)
> Method: Each issue checked against actual source code.

---

## TRN-1: Parse failure → incomplete review (fake 0.01 EUR)

**Status: RESOLVED**

- `PendingReview.suggestedAmount` is now `Double?` (nullable) — see `PendingReview.kt` line 62.
- `ReviewQueueRepository.markAsRelevant()` creates fallback reviews with `suggestedAmount = null` (not 0.01 EUR) — line 488.
- `ReviewQueueRepository.approveReview()` blocks approval when `suggestedAmount == null && finalAmount == null` — line 102.
- An `ExtractionState.SYNTHETIC_PLACEHOLDER` flag explicitly labels these reviews — line 499, `PendingReview.kt` line 85-86.

---

## TRN-2: Fake 0.01 EUR / confidence=1.0 fallback

**Status: PARTIALLY**

- **Fixed:** The fake `0.01 EUR` amount is gone — replaced with `suggestedAmount = null` in the `markAsRelevant()` path (`ReviewQueueRepository.kt` line 488).
- **Remaining:** `confidence = 1.0f` is still set on synthetic placeholder reviews (line 494). While the `ExtractionState.SYNTHETIC_PLACEHOLDER` label mitigates the severity (line 499), the confidence value is semantically misleading.
- The `NotificationProcessingPipeline` paths (`detectOversizedAmountCandidate`, `detectTransactionSignalCandidate`) now provide real parsed amounts with `confidence = 0.5f`.

---

## TRN-3: approveAllPending footgun bypasses expense creation

**Status: RESOLVED**

- `PendingReviewDao.approveAllPending()` is **deprecated** with `@Deprecated(level = DeprecationLevel.WARNING)` — lines 104-115.
- KDoc explicitly warns: "Calling this directly marks all pending reviews as APPROVED without creating expense entries — the expense data is silently lost."
- `ReviewQueueRepository.approveAllReview()` (lines 398-409) now calls the individual `approveReview()` method for each review, which goes through `TransactionLifecycleCoordinator.createExpense()`.

---

## TRN-4: PendingReviewDao.insert() uses REPLACE → silently loses data

**Status: RESOLVED**

- `PendingReviewDao.insert()` now uses `OnConflictStrategy.IGNORE` — line 28.
- KDoc (lines 12-27) explains the preferred paths and warns that this is a raw DAO operation bypassing the coordinator pipeline.
- The IGNORE strategy silently skips duplicates instead of silently overwriting existing data.

---

## TRN-5: Validator missing location-pair validation

**Status: RESOLVED**

- `TransactionLifecycleCoordinator.validate()` now includes location-pair validation — lines 516-521:
  ```kotlin
  if (request.latitude != null && request.longitude == null) {
      errors.add("Latitude requires longitude")
  }
  if (request.longitude != null && request.latitude == null) {
      errors.add("Longitude requires latitude")
  }
  ```

---

## TRN-6: Approval cannot correct currency — no finalCurrency param

**Status: RESOLVED**

- `ReviewQueueRepository.approveReview()` accepts `finalCurrency: String? = null` — line 86.
- Used at line 145: `currency = finalCurrency ?: review.suggestedCurrency`.

---

## TRN-7: Duplicate outcomes carry existingExpenseId but not persisted

**Status: PARTIALLY**

- **Fixed:** `TransactionLifecycleCoordinator.writeDuplicateEvent()` (lines 556-593) persists `TransactionEvent` rows with `eventType = LifecycleEventType.CREATE_DUPLICATE_SKIPPED` — including structured JSON metadata with existingExpenseId, reason, and dedupeKey.
- The `LifecycleEventType` enum now includes `CREATE_DUPLICATE_SKIPPED` — `LifecycleEventType.kt` line 7.
- This covers standard dedup paths and bulk import paths through the coordinator.
- **Gap:** In `ReviewQueueRepository.approveReview()`, the review approval path uses `skipDeduplication = true` (line 230), so the coordinator's own dedup check is skipped. If the coordinator returns `DuplicateSkipped` or `InsertConflict` due to a race condition (insertAtomic conflict), no duplicate event is written — lines 271-282 only update stats and mark the review as DUPLICATE.
- The `CreateExpenseResult.DuplicateSkipped` now carries `existingExpenseId` — `CreateExpenseResult.kt` line 5.

---

## TRN-8: Raw duplicate check after parse/AI fallback (wasted work)

**Status: STILL PRESENT**

- In `NotificationProcessingPipeline.processInternal()`, parsing via `parserRegistry.parseWithAiFallback()` happens at line 161.
- The raw notification duplicate check (`insertRawNotificationIfNotDuplicate()`) happens later at line 330.
- No fingerprint-based pre-check exists before parsing. The parse and AI fallback work is wasted on duplicate notifications.
- The pipeline still follows: parse → check duplicate → insert. It should be: check fingerprint → skip parse if duplicate → parse → insert.

---

## TRN-9: Raw notification dedup depends on fragile fields

**Status: PARTIALLY**

- **Fixed:** `RawNotification` entity now has `dedupeFingerprint: String?` field — `RawNotification.kt` line 73.
- A `UNIQUE` index on `dedupeFingerprint` exists — `RawNotification.kt` line 39.
- The KDoc (lines 7-27) documents that `dedupeFingerprint` is a "deterministic SHA-256 hash of package+title+text+timestamp."
- **Remaining:** The `RawNotificationDao.exists()` method (lines 46-56) still uses fragile field-by-field comparison (`packageName`, `timestamp`, `title`, `text`, `bigText`).
- The `insertRawNotificationIfNotDuplicate()` method in `NotificationProcessingPipeline` calls `dao.exists()` (fragile) rather than computing and using the `dedupeFingerprint`.
- Since `dedupeFingerprint` is nullable, the UNIQUE index treats NULL rows as non-equal (SQLite behavior), so it does not enforce dedup unless callers compute and set the fingerprint.

---

## TRN-10: markAsRelevant() misses recommendation/subscription/transfer effects

**Status: PARTIALLY**

- **Fixed:** `ReviewQueueRepository.markAsRelevant()` now routes through `TransactionLifecycleCoordinator.createExpense()` for parsed notifications — line 544.
- `budgetMonitor.checkBudgets()` is called post-commit for created expenses — lines 586-592.
- Classifier training and retraining are triggered — lines 594-612.
- **Remaining:** The method does **not** trigger:
  - Recommendation enrichment (`launchRecommendationEnrichment()`)
  - Subscription detection (`launchSubscriptionDetection()`)
  - Transfer analytics (`runTransferAnalyticsPostCommit()`)
- Compare with `NotificationProcessingPipeline.runParsedPostCommitActions()` (lines 887-934) which triggers all three for auto-accepted expenses.

---

## TRN-11: deleteAll() wipes expenses

**Status: RESOLVED**

- `NotificationRepository.deleteAll()` is **deprecated** with `@Deprecated(level = DeprecationLevel.ERROR)` — lines 155-167.
- `NotificationRepository.deleteAllNotifications()` is the new safe alternative — lines 133-139. It deletes notifications, pending reviews, user corrections, and resets pending counts **without** touching the expenses table.
- Deprecation message: "Dangerous: use targeted cleanup instead — this deletes ALL expenses."

---

## TRN-12: FK ON DELETE SET NULL detaches source audit

**Status: PARTIALLY**

- **Fixed:** KDoc on `Expense.rawNotificationId` (lines 66-73) now explicitly documents that ON DELETE SET NULL clears the specific notification reference, and that the `source` column preserves the fact that it came from a notification but not the specific notification ID.
- **Remaining:** The fix pattern calls for "Add immutable source metadata." The `source` column exists but does not preserve the specific notification ID after deletion. No immutable metadata (e.g., a snapshot of the originating notification ID + timestamp) is stored at the time the FK is cleared.
- The fundamental design is unchanged: deleting a `RawNotification` permanently loses the link to which specific notification produced the expense.

---

## TRN-13: Nullable dedupeKey — paths bypassing coordinator leave null

**Status: PARTIALLY**

- `Expense.dedupeKey` remains `String? = null` — `Expense.kt` line 88.
- **Fixed:** The `TransactionLifecycleCoordinator.createExpense()` always generates and sets a `dedupeKey` (lines 79-85).
- The unique index on `dedupeKey` (`Expense.kt` line 44) prevents duplicate key collisions for coordinator-created rows.
- **Remaining:** Legacy rows and any paths not using the coordinator can still have null `dedupeKey`. SQLite's UNIQUE constraint treats NULL values as non-equal, so multiple null-key rows can coexist.
- The DB-level CHECK non-null constraint suggested in the fix pattern has not been added.

---

## TRN-14: rawNotificationId not unique on Expense — race creates duplicates

**Status: RESOLVED**

- `Expense` entity now has `Index(value = ["rawNotificationId"], unique = true)` — `Expense.kt` line 36.
- This prevents a single raw notification from producing more than one expense, eliminating the race condition for duplicate expense creation.

---

## TRN-15: Resolved reviews' suggested fields mutated by upsert

**Status: PARTIALLY**

- `PendingReviewDao.upsertByRawNotificationId()` (lines 72-84) **preserves** `existing.status`, `existing.scannedReceiptId`, and `existing.createdAt`.
- **Remaining:** The suggested fields (`suggestedAmount`, `suggestedMerchant`, `suggestedType`, `suggestedCategoryId`, etc.) are **still overwritten** by the new review values even when the existing review has a non-PENDING status (APPROVED, REJECTED, DUPLICATE).
- The fix pattern calls for restricting upsert to PENDING status only. The current implementation preserves status but still mutates other fields.

---

## TRN-16: Source stats mutable counters, not event-derived

**Status: PARTIALLY**

- **Fixed:** `SourceStatsDao` KDoc (lines 7-29) explicitly documents the limitation and describes the future migration path to event-derived statistics: "A future refactoring should derive source statistics exclusively from the TransactionEvent audit log."
- **Remaining:** The actual statistics are still maintained via inline DAO increment/decrement calls (`incrementTotal`, `incrementAccepted`, `decrementPending`, etc.).
- No `TransactionEvent`-derived statistics implementation exists.

---

## TRN-17: Bulk approval no structured result

**Status: PARTIALLY**

- `ReviewQueueRepository.approveAllReview()` now returns `List<Pair<Long, Result<Long>>>` — lines 398-409.
- Each result includes the review ID and either `Result.Success(expenseId)`, `Result.Duplicate`, or `Result.Error`.
- **Remaining:** No dedicated `BulkReviewResult` data class as suggested in the fix pattern. The return type is a raw `Pair` rather than a structured result type.

---

## TRN-18: Location approval partial state (lat+null lon=USER_MANUAL)

**Status: PARTIALLY**

- **Fixed:** `TransactionLifecycleCoordinator.validate()` catches partial coordinates — lines 516-521. An expense with latitude but no longitude (or vice versa) will fail validation with a clear error message.
- This prevents partial-coordinate expenses from being created through any path using the coordinator.
- **Remaining:** In `ReviewQueueRepository.approveReview()`, if the coordinator returns `ValidationFailed` due to partial coordinates, the review is left in PROCESSING state (the `transitionStatus` to PROCESSING on line 182 was already committed before the coordinator call). The review state is not rolled back to PENDING.
- The `locationSource` logic (lines 168-172) still only checks `finalLatitude` (not the pair) to determine the source type, though this is now less critical since partial coordinates cannot reach the Expense creation step.

---

## Summary Table

| Issue | Description | Registry Status | Current Status | 
|-------|-------------|----------------|----------------|
| TRN-1 | Parse failure → incomplete review (fake 0.01) | STILL PRESENT | **RESOLVED** |
| TRN-2 | Fake 0.01 EUR / confidence=1.0 fallback | PARTIALLY | **PARTIALLY** |
| TRN-3 | approveAllPending footgun | STILL PRESENT | **RESOLVED** |
| TRN-4 | REPLACE → IGNORE | STILL PRESENT | **RESOLVED** |
| TRN-5 | Location-pair validation | PARTIALLY | **RESOLVED** |
| TRN-6 | finalCurrency param | STILL PRESENT | **RESOLVED** |
| TRN-7 | Duplicate resolution persisted | PARTIALLY | **PARTIALLY** |
| TRN-8 | Raw duplicate check waste | STILL PRESENT | **STILL PRESENT** |
| TRN-9 | Raw notification dedup fragile | STILL PRESENT | **PARTIALLY** |
| TRN-10 | markAsRelevant() side effects | PARTIALLY | **PARTIALLY** |
| TRN-11 | deleteAll() wipes expenses | STILL PRESENT | **RESOLVED** |
| TRN-12 | FK SET NULL detaches source | STILL PRESENT | **PARTIALLY** |
| TRN-13 | Nullable dedupeKey | PARTIALLY | **PARTIALLY** |
| TRN-14 | rawNotificationId unique | STILL PRESENT | **RESOLVED** |
| TRN-15 | Resolved reviews' fields mutated | PARTIALLY | **PARTIALLY** |
| TRN-16 | Source stats mutable counters | STILL PRESENT | **PARTIALLY** |
| TRN-17 | Bulk approval no structured result | STILL PRESENT | **PARTIALLY** |
| TRN-18 | Location approval partial state | STILL PRESENT | **PARTIALLY** |

### Statistics

| Status | Count |
|--------|-------|
| **RESOLVED** | 8 |
| **PARTIALLY** | 9 |
| **STILL PRESENT** | 1 |

**Improvements from registry baseline:**
- 5 issues upgraded from STILL PRESENT → RESOLVED (TRN-1, TRN-3, TRN-4, TRN-6, TRN-11, TRN-14)
- 3 issues upgraded from STILL PRESENT → PARTIALLY (TRN-9, TRN-12, TRN-16, TRN-18)
- 2 issues upgraded from PARTIALLY → RESOLVED (TRN-5, TRN-14 was STILL PRESENT)
- 1 issue remains STILL PRESENT (TRN-8)
- Remaining PARTIALLY issues show meaningful progress but retain gaps
