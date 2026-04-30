# Core Expense Lifecycle / Pending Review / Approval Pipeline Deep Analysis

Branch: `master-refactor`

Scope reviewed:

- raw notification → parsed transaction
- pending review creation
- approval/rejection
- auto-accept
- debug/manual recovery via `markAsRelevant`
- expense entity invariants
- duplicate prevention
- source stats
- receipt linkage
- delete/reset behavior

This is a static review; I did not run the app or tests.

---

## Executive verdict

The core lifecycle is better than many other areas because the main approval path has a real compare-and-set status transition:

```text
PENDING → PROCESSING → APPROVED / DUPLICATE
```

inside a Room transaction.

That is good.

But there are still several dangerous alternate paths and schema footguns.

The biggest issue is this:

> The app has one strong approval path, but not every path that creates or mutates an expense goes through the same lifecycle coordinator.

High-risk paths include:

- `markAsRelevant()`
- raw DAO methods like `approveAllPending()`
- direct `ExpenseDao.insertAtomic()`
- debug/bulk reset methods
- duplicate handling that marks something duplicate but does not link to the duplicate target

The most important fix is to create one authoritative **TransactionLifecycleCoordinator** and force every expense-creating path through it.

---

# Core observed flow

## Notification normal path

```text
NotificationRepository.processAndSave()
→ NotificationProcessingPipeline.process()
→ parseWithAiFallback()
→ ConfidenceRouter / routing decision
→ DB transaction:
   - insert raw notification
   - auto-accept expense OR create pending review OR reject
→ post-commit actions:
   - budget check
   - anomaly alert
   - classifier training
   - recommendation enrichment
   - subscription detection
```

## Pending approval path

```text
ReviewQueueRepository.approveReview()
→ read PendingReview
→ build Expense
→ DB transaction:
   - transition PENDING → PROCESSING
   - canonical duplicate check
   - insert expense atomically
   - link receipt if present
   - mark raw notification relevant
   - update source stats
   - write correction
   - mark review APPROVED / DUPLICATE
→ post-commit actions
```

## Rejection path

```text
ReviewQueueRepository.rejectReview()
→ transition PENDING → REJECTED
→ mark raw notification not relevant
→ update source stats
→ write correction
→ post-commit classifier/cache actions
```

---

# Strong parts

## 1. Approval is race-resistant

`approveReview()` transitions the review status from `PENDING` to `PROCESSING` inside a transaction before inserting the expense.

That prevents double-approval from two UI taps or two coroutines.

Good.

## 2. Duplicate checks are type-aware and currency-aware in the main paths

The main pipeline and approval path use `isDuplicateCurrencyAware()` and type-aware dedupe keys.

That is much better than plain amount/merchant/date matching.

## 3. Auto-accept and pending-review creation are transactionally isolated

`NotificationProcessingPipeline` does expensive parse/classification work before the transaction, then performs DB writes inside a transaction.

Good separation.

## 4. Post-commit side effects are best-effort

Budget checks, anomaly alerts, classifier retraining, and recommendation enrichment are mostly outside the DB transaction.

Good. Financial writes should not be rolled back because a notification or AI enrichment failed.

## 5. Receipt approval links the scanned receipt to the inserted expense

When approval succeeds, `scannedReceiptDao.linkToExpense()` is called inside the transaction.

Good.

---

# Critical / high-priority findings

## 1. `markAsRelevant()` can bypass the canonical duplicate gate

### Where

`ReviewQueueRepository.markAsRelevant()`

When a raw notification is manually marked relevant and the parser succeeds, it creates an `Expense` and calls:

```text
expenseDao.insertAtomic(expense)
```

But unlike `approveReview()` and `NotificationProcessingPipeline`, this path does not first call the canonical range/window duplicate check.

It relies mainly on `dedupeKey` conflict.

### Why this matters

The canonical duplicate policy catches more cases than exact dedupe-key conflict:

- merchant-key prefix matches
- amount tolerance
- date window
- legacy dedupe key formats
- type compatibility
- currency-aware range matching

`insertAtomic()` only catches DB uniqueness conflicts, mostly `dedupeKey`.

### Impact

A duplicate transaction could be inserted through the debug/manual recovery path even if the normal pipeline would have suppressed it.

### Severity

**Critical**

### Fix

Make `markAsRelevant()` use the same lifecycle method as auto-accept:

```text
build candidate → canonical duplicate check → insert or duplicate outcome
```

Do not call `expenseDao.insertAtomic()` directly from this path.

---

## 2. Fallback pending reviews use fake money: `0.01 EUR`

### Where

`ReviewQueueRepository.markAsRelevant()`

If a notification is marked relevant but parsing fails, the code creates a pending review with:

```text
amount = 0.01
currency = EUR
merchant = Unknown
type = PURCHASE
confidence = 1.0
```

### Why this is dangerous

This creates a financially plausible but fake transaction.

If the user taps approve without editing everything, the app records a real €0.01 purchase.

Also, confidence `1.0` is misleading because the parser actually failed.

### Impact

Possible corrupted financial history:

```text
Unknown — €0.01 — Purchase
```

This can pollute:

- dashboard totals
- category learning
- merchant learning
- recurring detection
- exports
- classifier training

### Severity

**Critical**

### Fix

Represent parser failure explicitly.

Add pending-review fields like:

```text
needsAmount = true
needsMerchant = true
needsCurrency = true
extractionState = PARSE_FAILED
```

Do not use fake monetary values.

If DB requires positive amount, change the schema to allow null suggested amount for incomplete reviews.

---

## 3. DAO method `approveAllPending()` marks reviews approved without creating expenses

### Where

`PendingReviewDao.approveAllPending()`

The DAO has:

```text
UPDATE pending_reviews SET status = APPROVED WHERE status = PENDING
```

This bypasses:

- expense creation
- duplicate check
- raw notification relevance update
- source stats
- receipt linking
- user correction
- budget checks
- classifier learning

The repository uses `approveAllReview()` correctly by looping through `approveReview()`, but the DAO method remains a dangerous footgun.

### Impact

If any future/debug caller uses the DAO method, pending reviews disappear as “approved” but no `Expense` rows are created.

That is silent financial data loss.

### Severity

**Critical**

### Fix

Delete or restrict this DAO method.

If bulk approval is needed, expose only:

```kotlin
ReviewQueueRepository.approveAllReview()
```

and make it return per-review results.

Same concern applies to raw DAO bulk rejection if it bypasses source stats/corrections.

---

## 4. `PendingReviewDao.insert()` uses `REPLACE`

### Where

`PendingReviewDao`

```kotlin
@Insert(onConflict = OnConflictStrategy.REPLACE)
```

### Problem

SQLite `REPLACE` is delete + insert. With unique `rawNotificationId`, this can replace an existing pending/rejected/approved row.

The custom `upsertByRawNotificationId()` is safer because it preserves:

- id
- scannedReceiptId
- createdAt
- status

But the raw `insert()` method remains available.

### Impact

A direct insert conflict can:

- reset review status
- replace audit fields
- break receipt linkage
- lose original createdAt
- mutate resolved review history

### Severity

**High**

### Fix

Change insert to `IGNORE` or plain insert, and force all upsert behavior through explicit methods:

```kotlin
insertNewPending()
upsertPendingOnly()
```

Resolved reviews should be immutable unless a deliberate reopen action exists.

---

## 5. Approval validates only upper amount, not complete money correctness

### Where

`ReviewQueueRepository.approveReview()`

Approval rejects amount > 1,000,000, but does not clearly reject:

- amount <= 0
- NaN / infinite amount
- blank merchant
- invalid currency
- implausible date
- missing currency
- malformed transaction type

### Impact

Invalid financial rows can enter `expenses`.

Examples:

- `-10` purchase
- `NaN`
- empty merchant
- currency `EURO`
- future timestamp from bad parser

### Severity

**High**

### Fix

Add one canonical validator:

```kotlin
ExpenseDraftValidator.validateForApproval()
```

Rules:

- amount finite and positive
- currency ISO/supported
- merchant non-blank unless explicit unknown state
- date plausible
- transaction type valid
- transfer metadata only on transfer/deposit
- location pair complete if location is provided

Use it in:

- notification auto-accept
- pending approval
- manual expense entry
- receipt approval
- debug recovery
- imports

---

## 6. Approval UI/path cannot correct currency

### Where

`ReviewQueueRepository.approveReview()`

Approval accepts overrides for:

- amount
- merchant
- category
- date
- type
- location

But not currency.

The inserted expense always uses:

```text
review.suggestedCurrency
```

### Impact

If the parser or AI detects the wrong currency, the user cannot fix it in this approval method.

Example:

- notification says `$12.99`
- parser suggests `EUR`
- user can edit amount but not currency
- approved row becomes `12.99 EUR`

### Severity

**High / Critical with multi-currency**

### Fix

Add `finalCurrency`.

Approval should generate dedupe key using the final currency, not the suggested currency.

---

## 7. Duplicate outcomes do not link to the duplicate target

### Where

- `ReviewQueueRepository.approveReview()`
- `NotificationProcessingPipeline`
- `PendingReviewDao` duplicate helpers

When a duplicate is found, the review/raw notification is marked duplicate or not relevant. But the code does not persist:

- duplicate target expense id
- duplicate target pending review id
- duplicate reason
- matched fields
- confidence
- whether receipt should attach to existing expense

### Impact

Duplicate decisions become non-auditable.

User/dev cannot answer:

> “What did this duplicate match?”

Also, receipt-derived reviews that duplicate an existing expense may not attach the receipt to that existing expense.

### Severity

**High**

### Fix

Add:

```kotlin
DuplicateResolution(
    sourceType,
    sourceId,
    matchedExpenseId,
    matchedPendingReviewId,
    reason,
    confidence,
    createdAt
)
```

When approving a receipt review that duplicates an existing transaction, offer:

```text
Attach receipt to existing expense
```

instead of only marking duplicate.

---

## 8. Raw duplicate check happens after parse/AI fallback

### Where

`NotificationProcessingPipeline.processInternal()`

The pipeline does:

```text
parseWithAiFallback()
then insertRawNotificationIfNotDuplicate()
```

### Problem

Duplicate raw notifications can still trigger parser and AI fallback work before being rejected as raw duplicates.

### Impact

Cost/privacy/performance risk:

- duplicate notification can call AI
- duplicate notification can process sensitive text
- batch replay can be expensive
- duplicate input can trigger cloud fallback before DB says “already seen”

### Severity

**High / privacy + cost**

### Fix

Do a cheap fingerprint pre-check first:

```text
normalized raw notification fingerprint
→ if seen, stop before AI/parsing
→ else parse/classify
```

Best schema fix:

```text
raw_notifications.dedupeFingerprint UNIQUE
```

---

## 9. Raw notification dedupe still depends on fragile fields

### Where

`NotificationProcessingPipeline.insertRawNotificationIfNotDuplicate()`
`RawNotificationDao.exists()`

The duplicate pre-check uses package/timestamp/title/text style matching.

### Problem

Repeated bank notifications can have:

- slightly different timestamp
- changed title
- collapsed text
- changed whitespace
- same transaction but different notification id

### Impact

Rejected or approved notifications can reappear as new pending reviews.

### Severity

**High**

### Fix

Use two layers:

1. raw notification fingerprint for exact duplicate capture
2. transaction candidate fingerprint for financial duplicate detection

Store both.

---

## 10. Debug/manual recovery path has inconsistent side effects

### Where

`ReviewQueueRepository.markAsRelevant()`

When it inserts a parsed expense directly, it runs:

- budget check
- classifier training

But does not appear to run the same post-commit actions as normal auto-accept:

- anomaly alert
- recommendation enrichment
- subscription detection
- transfer analytics

### Impact

Expenses created via manual relevance recovery behave differently from normal auto-accepted expenses.

### Severity

**High**

### Fix

Do not have separate side-effect logic.

Use one lifecycle coordinator:

```text
onExpenseCreated(expense, source)
```

for all paths.

---

## 11. `NotificationRepository.deleteAll()` deletes all expenses

### Where

`NotificationRepository.deleteAll()`

The method deletes:

- raw notifications
- expenses
- pending reviews
- user corrections
- source stats

### Problem

A method named `deleteAll()` in `NotificationRepository` is easy to interpret as “delete all notifications.”

But it also wipes the entire `expenses` table.

### Impact

If wired to a debug or cleanup UI incorrectly, the user can lose all financial history.

### Severity

**Critical if reachable outside debug/test**

### Fix

Rename and restrict:

```kotlin
dangerouslyDeleteAllNotificationDebugDataAndExpenses()
```

Better split:

```kotlin
deleteRawNotificationsOnly()
deleteNotificationIngestionState()
factoryResetAllFinancialData()
```

Require explicit confirmation for expense deletion.

---

## 12. Deleting a raw notification can detach source audit from approved expenses

### Where

`NotificationRepository.delete(notification)`

`Expense.rawNotificationId` has FK `ON DELETE SET NULL`.

Deleting raw notification removes the source link from approved expenses.

### Impact

The expense survives, but loses:

- original package
- original notification text/title
- traceability
- parser source
- audit connection

This may be intentional for privacy retention, but then the expense should keep a non-sensitive source snapshot.

### Severity

**Medium / High**

### Fix

Add immutable source metadata on `Expense`:

```text
sourceType
sourcePackage
sourceCreatedAt
sourceFingerprint
sourceConfidence
sourceReviewId
```

Then raw text can be purged without destroying auditability.

---

## 13. Nullable `dedupeKey` weakens DB-level duplicate prevention

### Where

`Expense.kt`

`dedupeKey` is nullable but has a unique index.

SQLite allows multiple `NULL` values in a unique index.

### Impact

Any insertion path that forgets to generate a dedupe key bypasses the DB atomic duplicate guard.

This is especially risky for:

- manual entries
- imports
- receipts
- debug inserts
- migration/backfill rows

### Severity

**High**

### Fix

For transaction-like rows, require non-null dedupe identity.

Options:

- make `dedupeKey` non-null for all `Expense` rows
- add separate source-specific unique keys
- add validation before every insert
- use DB CHECK where possible

---

## 14. `rawNotificationId` is not unique on `Expense`

### Where

`Expense.kt`

There is a normal index on `rawNotificationId`, not a unique one.

### Impact

At schema level, one raw notification can be linked to multiple expense rows.

The current pipeline probably prevents this in normal flow, but the DB does not enforce it.

### Severity

**Medium / High**

### Fix

If one notification should produce at most one expense, add a unique index:

```kotlin
Index(value = ["rawNotificationId"], unique = true)
```

SQLite allows multiple nulls, so manual/receipt expenses are unaffected.

If one notification can legitimately contain multiple transactions, model that explicitly with child source records.

---

## 15. Resolved reviews can be mutated by upsert logic

### Where

`PendingReviewDao.upsertByRawNotificationId()`

This preserves existing status, but still updates suggested fields for the existing row.

### Problem

If a review has already been approved/rejected/duplicated, later reprocessing the same raw notification could alter its suggested amount/merchant/category while keeping the old status.

### Impact

Audit drift:

```text
User approved what looked like €20 Starbucks
Later row says €40 Amazon but status still APPROVED
```

Normal raw duplicate checks reduce this risk, but the DAO itself permits it.

### Severity

**Medium / High**

### Fix

Upsert should update only pending rows.

Resolved rows should be immutable unless explicitly reopened:

```sql
UPDATE ... WHERE rawNotificationId = ? AND status = 'PENDING'
```

---

## 16. Source stats are mutable counters, not event-derived

### Where

- `SourceStatsDao`
- `ReviewQueueRepository`
- `NotificationProcessingPipeline`

The code increments/decrements source stats across many paths.

### Problem

Counters can drift if:

- a direct DAO method is used
- a migration repairs rows
- a transaction path changes
- a debug method deletes rows
- a duplicate/rejection path skips a decrement

### Impact

Confidence routing may become biased by wrong stats.

### Severity

**Medium / High**

### Fix

Prefer an event ledger:

```text
SourceProcessingEvent(
  packageName,
  rawNotificationId,
  reviewId,
  expenseId,
  outcome,
  timestamp
)
```

Then derive source stats from events or periodically rebuild them.

At minimum, add a stats consistency checker.

---

## 17. Manual/bulk approval is partial and not clearly reported

### Where

`ReviewQueueRepository.approveAllReview()`

It loops through pending reviews and catches failures per item.

### Impact

A bulk approval can partially succeed with only logs for failures.

The UI may report success while some reviews failed.

### Severity

**Medium**

### Fix

Return structured result:

```kotlin
BulkReviewResult(
  approved,
  duplicates,
  failed,
  skippedAlreadyProcessed
)
```

---

## 18. Location approval can create partial location state

### Where

`ReviewQueueRepository.approveReview()`

`locationSource` becomes manual if `finalLatitude != null`, but longitude is checked separately.

### Impact

A row could get:

```text
latitude set
longitude null
locationSource USER_MANUAL
```

depending on caller behavior.

### Severity

**Medium**

### Fix

Validate location as a pair:

```text
both latitude and longitude, or neither
```

Also treat address/placeId consistency explicitly.

---

# Cross-pipeline risks

## Receipt matching

If approving a receipt review hits a duplicate, the receipt is not obviously linked to the existing expense.

Fix: duplicate resolution should support “attach receipt to matched expense.”

## AI quick actions

Any future quick-save/quick-approve path must call the same lifecycle coordinator and validator.

No AI result should directly insert an expense.

## Dashboard/budget

If fake fallback reviews or duplicate debug-recovered expenses enter the table, dashboard/budget totals become wrong even if dashboard logic is fixed.

## Exports

If `NotificationRepository.deleteAll()` is reachable or pending rows can be marked approved without expenses, exports can silently miss history.

## Privacy

Pending reviews and corrections store notification text. Raw retention fixes should preserve source audit without keeping sensitive text forever.

---

# Recommended fix order

## PR 1 — Create `TransactionLifecycleCoordinator`

All transaction creation paths should call one component:

```text
Notification auto-accept
Pending approval
Receipt approval
Manual entry
markAsRelevant recovery
Bank import
AI quick-save
```

Responsibilities:

- validate draft
- normalize money/currency
- generate merchant key
- generate dedupe key
- canonical duplicate check
- insert expense
- update source/review state
- link receipt
- emit lifecycle event
- trigger post-commit actions

## PR 2 — Remove DAO footguns

Remove/restrict:

- `PendingReviewDao.approveAllPending()`
- `PendingReviewDao.rejectAllPending()` if it bypasses repository logic
- raw `insert(REPLACE)` for pending reviews
- ambiguous `NotificationRepository.deleteAll()`

## PR 3 — Fix incomplete review model

Allow pending reviews with missing amount/currency/merchant.

Do not use `0.01 EUR`.

Add:

```text
extractionState
missingFields
requiresManualAmount
requiresManualCurrency
```

## PR 4 — Add approval currency override and validation

Add `finalCurrency`.

Run all final approvals through `ExpenseDraftValidator`.

## PR 5 — Duplicate resolution records

Persist duplicate target and reason.

Support receipt attachment to existing duplicate transaction.

## PR 6 — Raw dedupe before AI/parse

Add fingerprint and early duplicate gate before `parseWithAiFallback()`.

## PR 7 — Expense source/audit metadata

Add non-sensitive immutable source fields to `Expense` so raw notification text can be purged.

## PR 8 — Edit/delete lifecycle hardening

For expense edits:

- recompute dedupe key if amount/merchant/date/currency/type changes
- validate ownership/shared fields
- emit audit event

For deletes:

- decide soft delete vs hard delete
- clean/link receipt/group/warranty/planned relationships intentionally

---

# Regression tests to add

1. Double-tapping approve creates one expense only.
2. Approving already processed review returns already-processed error.
3. `markAsRelevant()` cannot insert a duplicate that canonical policy would reject.
4. Parser-failed relevant notification creates incomplete review, not `0.01 EUR`.
5. DAO bulk approve cannot mark approved without creating expenses.
6. Pending insert conflict does not reset approved/rejected status.
7. Approval rejects negative, zero, NaN, infinite, blank merchant, invalid currency.
8. Approval can correct currency.
9. Duplicate approval stores matched expense/review target.
10. Receipt review duplicate can attach receipt to existing expense.
11. Raw duplicate notification does not call AI fallback.
12. Deleting raw notification preserves non-sensitive expense source audit.
13. `NotificationRepository.deleteAll()` cannot be called from non-debug UI or is split safely.
14. Nullable dedupeKey insert path is rejected for financial transactions.
15. One raw notification cannot link to two expenses unless explicitly modeled.
16. Bulk approve returns per-item success/duplicate/failure counts.
17. Updating expense amount/merchant/date/currency/type recomputes dedupe key.
18. Deleting expense handles receipt/group/warranty/planned links intentionally.

---

# Top three fixes

If you only fix three things first:

1. **Force `markAsRelevant()` and every other insert path through the same lifecycle coordinator as normal approval.**
2. **Remove fake fallback money (`0.01 EUR`) and model incomplete pending reviews properly.**
3. **Remove DAO/bulk footguns that can mark reviews approved without creating expenses.**

These protect the financial record fastest.

---

# Sources reviewed

- `CODEBASE_SEGMENTS.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/docs/architecture/CODEBASE_SEGMENTS.md

- `PendingReview.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/PendingReview.kt

- `PendingReviewDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt

- `ReviewQueueRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt

- `NotificationRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt

- `NotificationProcessingPipeline.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt

- `Expense.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt

- `ExpenseDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

- `ExpenseRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt

- `ReviewScreen.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt

- `ReviewViewModel.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt