# Cross-Verification Report: Phase 2-4 Review Claims vs. Actual Codebase

**Date**: 2026-05-01  
**Codebase**: `app/src/main/java/com/yourname/expensetracker`  
**Schema Version**: 100  

---

## Phase 3 Issues

### Issue 1: "Create flow is not DB-transactional"

**Verdict**: ✅ VALID — needs fixing

**Evidence**:  
`TransactionLifecycleCoordinator.createExpense()` (lines 139-160) performs two separate DAO calls:
- Line 140: `val insertedId = expenseDao.insertAtomic(expense)` — a single `@Insert(onConflict = OnConflictStrategy.IGNORE)` (ExpenseDao.kt line 88-89), NOT wrapped in `@Transaction`
- Lines 146-160: `transactionEventDao.insert(TransactionEvent(...))` — a separate DAO call

There is no `database.withTransaction {}` wrapper around these two calls. If `insertAtomic` succeeds but `transactionEventDao.insert` fails (e.g., disk full, constraint violation), the expense row is persisted without a corresponding lifecycle event. This is an **orphan event risk**.

Note: `insertAtomic` is just an alias for `insert` (same `@Insert(onConflict = OnConflictStrategy.IGNORE)`), so the "atomic" naming is misleading — it provides dedupe-key-based IGNORE, not transactional atomicity for the full lifecycle.

**Recommendation**: Wrap steps 5-6 in `database.withTransaction { ... }`. Extract the transaction to a helper or use `@Transaction` on `createExpense()` (requires making the class a Room DAO or injecting `AppDatabase`).

---

### Issue 2: "Duplicate result loses existing ID"

**Verdict**: ✅ VALID — needs fixing

**Evidence**:  
- `ExpenseDao.isDuplicateCurrencyAware()` signature (ExpenseDao.kt line 658-667): returns `Boolean`, not `Long` or the existing expense ID.
- `TransactionLifecycleCoordinator.kt` lines 131-136:
```kotlin
if (isDuplicate) {
    return CreateExpenseResult.DuplicateSkipped(
        existingExpenseId = -1L,
        reason = "..."
    )
}
```
- `CreateExpenseResult.DuplicateSkipped` (CreateExpenseResult.kt line 5): `existingExpenseId: Long` — designed to carry the duplicate ID, but always gets `-1L`.

The DAO runs 3-4 existence queries (existsByMerchantKey, existsByMerchantKeyPrefix, etc.) and ORs them — even knowing "something is a duplicate" but not which row matched.

**Recommendation**: Either (a) change `isDuplicateCurrencyAware` to return `Long?` (the matched expense ID, null if none), or (b) add a new method `getDuplicateIdCurrencyAware()` that returns the ID. The coordinator should populate `existingExpenseId` with the actual ID so the UI/API can show the user which expense is duplicated.

---

### Issue 3: "Validation too shallow"

**Verdict**: ✅ VALID — needs fixing (with nuance — some checks may be intentionally deferred)

**Evidence**:  
`TransactionLifecycleCoordinator.validate()` (lines 263-284) checks only:
1. Amount: positive, finite, < 1,000,000
2. Merchant: not blank, not "Unknown" / "Parsing Failed"
3. Currency: not blank
4. Date: > 0

Missing validations:
- **Future date check**: No check that `request.date <= timeProvider.now()` — expenses can be created with future timestamps
- **Transfer validation**: If `transactionType == TRANSFER`, `transferDirection` and `transferAccountName` should be validated (currently unchecked)
- **Currency ISO 4217 format**: Only checks `currency.isBlank()`, doesn't validate it's a valid ISO code like "EUR", "USD", etc.
- **Ownership coherence**: If `isNotMine == true`, `ownerName` should probably not be blank; if `isSharedExpense == true`, `sharedWithName` or share percentages should be validated
- **Payment method validity**: `request.paymentMethod` is passed through unchecked

**Recommendation**: Add checks incrementally. Priority: (a) future date guard, (b) currency ISO format via a whitelist or regex, (c) transfer coherence rules.

---

### Issue 4: "deduplicationMode and idempotencyKey unused"

**Verdict**: ✅ VALID — dead fields

**Evidence**:  
- `CreateExpenseRequest` defines `deduplicationMode: DeduplicationMode = DeduplicationMode.STANDARD` (line 103) and `idempotencyKey: String? = null` (line 105)
- Searching the entire `domain/transaction/lifecycle/` package for `deduplicationMode` and `idempotencyKey` returns **zero results** — they are never read by `TransactionLifecycleCoordinator.createExpense()`

The coordinator uses only `request.skipDeduplication` (line 121) for dedup control. The `deduplicationMode` enum and `idempotencyKey` are accepted in the request but ignored.

**Recommendation**: Either implement the logic or remove the fields from the request class until they are implemented. If `deduplicationMode` is planned, implement `AGGRESSIVE` (stricter matching) and `LENIENT` (wider window) modes. For `idempotencyKey`, store it and return existing result on retry.

---

### Issue 5: "Update lifecycle too generic"

**Verdict**: ✅ VALID — needs fixing

**Evidence**:  
`TransactionLifecycleCoordinator.updateExpense()` (lines 184-206):
```kotlin
transactionEventDao.insert(
    TransactionEvent(
        ...
        beforeSnapshot = null,   // ← missing
        afterSnapshot = null,    // ← missing
        ...
    )
)
expenseDao.update(expense)
```

Missing:
- **Before snapshot**: The event should capture the old expense state before the update (load by ID, serialize)
- **After snapshot**: The new expense state should be captured
- **Dedupe recomputation**: If amount/merchant/date changed, the `dedupeKey` should be recomputed to prevent future duplicates from bypassing detection
- **Event/update atomicity**: Same as Issue 1 — the event write and DAO update are not in a transaction

**Recommendation**: Load the existing expense before the event, pass `beforeSnapshot`, set `afterSnapshot = expense.toString()` (or better JSON), and recompute `dedupeKey` if key fields changed.

---

### Issue 6: "Delete snapshot uses toString()"

**Verdict**: ✅ VALID — weak implementation

**Evidence**:  
`TransactionLifecycleCoordinator.deleteExpense()` (line 243):
```kotlin
beforeSnapshot = expense.toString(),
```

Kotlin's default `data class` `toString()` produces output like:
```
Expense(id=42, amount=25.0, currency=EUR, merchant=Supermarket, ...)
```

This is:
1. **Unstructured** — cannot be programmatically parsed back
2. **Potentially truncated** — if the expense has many fields, it may be cut off
3. **Version-dependent** — field order/format may change across code changes, breaking audit trail parsers

**Recommendation**: Use a structured format like JSON (e.g., via kotlinx.serialization or Gson). The `TransactionEvent.beforeSnapshot` field is `TEXT` and intended for human/automated audit — it should be parseable. Alternatively, store a minimal summary (id, amount, merchant, date, category).

---

### Issue 7: "Side effects only partially centralized"

**Verdict**: ❌ FALSE POSITIVE — this is by design, not a bug

**Evidence**:  
`TransactionSideEffectDispatcher.kt` lines 14-25 explicitly documents:
```
 * This consolidates the common side effects that previously were duplicated
 * across every repository that creates expenses:
 *   - Budget monitoring
 *   - Anomaly alert checking
 *   - Merchant → category pattern learning
 *
 * Source-specific side effects (e.g. scanned receipt linking, raw notification
 * relevance, recommendation generation, recurring rule creation) remain in the
 * calling repository and are NOT handled here.
```

This is a deliberate architectural decision: common cross-cutting concerns are centralized in the dispatcher; source-specific concerns (which need context only available in the caller) remain at the call site. The dispatcher handles budget checks, anomaly detection, and pattern learning — which previously existed as duplicated code in `ExpenseRepository`, `NotificationProcessingPipeline`, `ReviewQueueRepository`, etc.

The pattern is equivalent to an event-driven architecture where handlers subscribe to specific events. The dispatcher acts as a "fanout" for standard listeners.

**Recommendation**: None. This is acceptable architecture. Consider adding a KDoc reference in each source-specific caller pointing to their remaining responsibilities for discoverability.

---

## Phase 4 Issues

### Issue 8: "processEmailReceipt() is TODO"

**Verdict**: ✅ VALID — unimplemented

**Evidence**:  
`ReceiptLifecycleCoordinator.processEmailReceipt()` (lines 247-251):
```kotlin
suspend fun processEmailReceipt(emailData: EmailReceiptData): Result<ScannedReceipt> {
    // TODO: Will be implemented in PR 5
    Timber.w("processEmailReceipt is not yet implemented (PR 5)")
    return Result.failure(UnsupportedOperationException("processEmailReceipt will be implemented in PR 5"))
}
```

Returns `UnsupportedOperationException` — a runtime exception, not even a domain-specific error. The method is documented as "Will be fully implemented in PR 5."

**Recommendation**: Implement the method or at minimum return a `Result.failure(NotImplementedError(...))` that can be handled gracefully by callers instead of crashing.

---

### Issue 9: "Receipt asset double-persistence/orphan risk"

**Verdict**: ❌ FALSE POSITIVE — misunderstanding of "asset" vs "database row"

**Evidence**:  
`ReceiptLifecycleCoordinator.processReceiptInput()` flow:
1. **Line 95**: `assetStore.persistReceiptAsset(uri)` — copies the **image file** to app-local storage (file system, NOT database). Returns a path string.
2. **Lines 125-128**: `receiptRepository.processReceipt(uri, false)` — this runs OCR and calls `scannedReceiptDao.insert(receipt)` inside `database.withTransaction` (ReceiptRepository.kt lines 156-191)
3. **Lines 148**: `scannedReceiptDao.update(updated)` — adds lifecycle metadata (sourceType, documentType, processingStatus, imageHash, updatedAt) to the already-inserted row

The "asset" (file) is persisted once by `assetStore`. The database row is INSERTed by `ReceiptRepository` and then UPDATEd by the coordinator. This is **not double-persistence** — it's an insert-then-enrich pattern.

**Minor concern**: The update at line 148 is outside the original `database.withTransaction`, so if the update fails, the receipt row exists without lifecycle metadata. This is a data inconsistency risk, but not an "orphan" — the receipt row is valid, just missing metadata.

**Recommendation**: Consider having `ReceiptRepository.processReceipt()` return a partially-populated receipt and let the coordinator do the insert after enrichment, or pass the enrichment metadata into `processReceipt()`. But the claim of "double persistence creating orphans" is incorrect.

---

### Issue 10: "Relink prevention weak"

**Verdict**: ✅ VALID — non-bank receipts can be linked to multiple expenses

**Evidence**:  
`ReceiptLinkService.linkReceiptToExpense()` (lines 58-118):
- Line 92: `receiptExpenseLinkDao.insert(link)` — inserts with REPLACE on `(receiptId, expenseId)` unique constraint
- Lines 95-97: For non-BANK_STATEMENT receipts, updates `receipt.copy(expenseId = expenseId)`

The UNIQUE constraint on `(receiptId, expenseId)` prevents duplicate pairs but does NOT prevent linking receipt #1 to expense A and then also to expense B. The legacy `expenseId` field on `ScannedReceipt` gets overwritten to the **last** linked expense (line 96), but the old link row (receipt #1 → expense A) still exists.

The doc at lines 42-43 claims:
> "For all other document types, the UNIQUE constraint on (receiptId, expenseId) with REPLACE strategy ensures a single link per receipt-expense pair."

This is misleading — it ensures at most one link per (receipt, expense) pair, NOT at most one link per receipt.

**Recommendation**: For non-BANK_STATEMENT receipts, add a pre-check: if any links already exist for this receipt and this expense is not among them, either (a) reject with error, or (b) delete existing links first, or (c) use a UNIQUE constraint on `receiptId` alone (not just the pair).

---

### Issue 11: "Link/unlink not transactional"

**Verdict**: ✅ VALID — no DB transaction wrapping

**Evidence**:  
`ReceiptLinkService.linkReceiptToExpense()`:
- Line 92: `receiptExpenseLinkDao.insert(link)` — DAO call 1
- Line 96: `scannedReceiptDao.update(receipt.copy(expenseId = expenseId))` — DAO call 2
- Lines 100-114: `receiptEventDao.insert(...)` — DAO call 3

`ReceiptLinkService.unlinkReceiptFromExpense()`:
- Line 141: `receiptExpenseLinkDao.unlink(receiptId, expenseId)` — DAO call 1
- Line 148: `scannedReceiptDao.update(receipt.copy(expenseId = null))` — DAO call 2
- Lines 154-168: `receiptEventDao.insert(...)` — DAO call 3

Neither method uses `@Transaction` or `database.withTransaction`. If any step fails after previous steps succeeded, the database is left in an inconsistent state (e.g., link row inserted but legacy expenseId not updated, or vice versa).

**Recommendation**: Wrap all steps in `database.withTransaction { ... }` or annotate the class methods with `@Transaction` (requires Room DAO pattern).

---

### Issue 12: "Unlink can clear legacy incorrectly"

**Verdict**: ✅ VALID — clears legacy expenseId even if other links remain

**Evidence**:  
`ReceiptLinkService.unlinkReceiptFromExpense()` lines 146-149:
```kotlin
// 2. For non-BANK_STATEMENT: clear legacy ScannedReceipt.expenseId
if (!isBankStatement && receipt != null) {
    scannedReceiptDao.update(receipt.copy(expenseId = null))
}
```

It clears `expenseId` unconditionally for non-bank receipts, without checking whether other links to this receipt still exist. If a retail receipt is linked to expense #42 AND expense #99 (possible due to Issue 10), unlinking expense #42 would incorrectly clear the `expenseId`, even though expense #99 is still linked.

**Recommendation**: After unlinking, query remaining links via `receiptExpenseLinkDao.getLinksForReceipt(receiptId)`. Only clear `expenseId` if no remaining links exist. If remaining links exist, update to the most recent (or primary) link's expenseId.

---

### Issue 13: "Migration FK constraints"

**Verdict**: ✅ VALID — FK constraints missing from CREATE TABLE

**Evidence**:  
`AppDatabase.kt` MIGRATION_95_96 (lines 5675-5770):

`receipt_events` table (lines 5695-5710):
```sql
CREATE TABLE IF NOT EXISTS receipt_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    receiptId INTEGER,                          -- no FK
    sourceType TEXT NOT NULL,
    ...
)
```
No `FOREIGN KEY(receiptId) REFERENCES scanned_receipts(id)`.

`receipt_expense_links` table (lines 5719-5731):
```sql
CREATE TABLE IF NOT EXISTS receipt_expense_links (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    receiptId INTEGER NOT NULL,                 -- no FK
    expenseId INTEGER NOT NULL,                 -- no FK
    ...
)
```
No `FOREIGN KEY(receiptId) REFERENCES scanned_receipts(id)` or `FOREIGN KEY(expenseId) REFERENCES expenses(id)`.

Compare with earlier migrations that DO include FKs (e.g., MIGRATION_7_8 for budgets, MIGRATION_12_13 for planned_expenses). The `transaction_events` table (MIGRATION_94_95, line 5644) also lacks an FK on `expenseId`.

**Recommendation**: In a future migration, add FK constraints. Since SQLite does not support `ALTER TABLE ADD CONSTRAINT`, this requires recreating the tables. For `receipt_expense_links`, add:
```sql
FOREIGN KEY(receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE,
FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE CASCADE
```

---

### Issue 14: "Manual fallback hardcoded EUR"

**Verdict**: ✅ VALID — hardcoded currency

**Evidence**:  
`ReceiptLifecycleCoordinator.processReceiptInput()` lines 199-207 (fallback path when OCR fails catastrophically):
```kotlin
val manualReceipt = ScannedReceipt(
    ...
    currency = "EUR",   // ← hardcoded
    ...
)
```

If the user's locale/account currency is not EUR, this produces incorrect data. The currency field is not derived from any input — it's simply `"EUR"`.

**Recommendation**: Use a configurable default currency (e.g., from user preferences/settings). Fall back to `java.util.Currency.getInstance(Locale.getDefault()).currencyCode` for a locale-appropriate default.

---

### Issue 15: "Duplicate detection partially integrated"

**Verdict**: ✅ VALID — dedup only runs pre-OCR, not post-OCR

**Evidence**:  
`ReceiptLifecycleCoordinator.processReceiptInput()`:
- **Lines 106-121**: Duplicate check runs BEFORE OCR, using only `fileHash` (exact image hash). No text fingerprint or semantic fingerprint is computed at this stage.
- **Lines 123-194**: OCR runs via `receiptRepository.processReceipt()`, which does NOT do its own dedup check. The coordinator receives back the parsed receipt but does NOT run `duplicateDetector.checkDuplicate()` again with `textFingerprint` or `semanticFingerprint`.
- **`ReceiptDuplicateDetector.checkDuplicate()`** (lines 70-140) supports all four match types (EXACT_HASH, TEXT_FINGERPRINT, SEMANTIC, EXTERNAL_ID) but only EXACT_HASH is ever invoked in the coordinator flow.

The text fingerprint and semantic fingerprint computation methods exist (`computeTextFingerprint`, `computeSemanticFingerprint`) but are never called in the main processing flow.

**Recommendation**: After OCR/parse (after `receiptRepository.processReceipt` returns), compute `textFingerprint` from `receipt.rawOcrText` and `semanticFingerprint` from `receipt.parsedMerchant`, `receipt.parsedTotal`, `receipt.parsedDate`, `receipt.currency`, then call `duplicateDetector.checkDuplicate()` with these values before saving.

---

## Phase 2 Note

### Issue 16: "Natural-language last month is rolling"

**Verdict**: ✅ VALID — "last month" means "last 30 days", not "previous calendar month"

**Evidence**:  
`NaturalLanguageSearchEngine.kt` lines 26-38 (`PatternWithExtractor` for "last week|month|year"):
```kotlin
"month" -> end.minusMonths(1)
```

Where `end` is:
```kotlin
val end = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
```

So `end` = today's date. Therefore "last month" = [today minus 1 month, today] — a rolling 30/31-day window.

Compare with "this month" at lines 46-51:
```kotlin
"month" -> today.withDayOfMonth(1)  // first day of current month
```
"this month" correctly uses calendar boundaries.

For a calendar-month interpretation, "last month" should resolve to:
```kotlin
val firstOfThisMonth = today.withDayOfMonth(1)
val start = firstOfThisMonth.minusMonths(1)
val end = firstOfThisMonth.minusDays(1)
```

**Recommendation**: Fix "last month" to use calendar-month boundaries, consistent with "this month" behavior. "last week" likely has the same rolling-vs-calendar ambiguity (current implementation gives `today - 7 days` — rolling).

---

## Summary

| Issue | Phase | Verdict | Criticality |
|-------|-------|---------|-------------|
| 1 | P3 | ✅ VALID | MAJOR |
| 2 | P3 | ✅ VALID | MAJOR |
| 3 | P3 | ✅ VALID | MAJOR |
| 4 | P3 | ✅ VALID | MINOR |
| 5 | P3 | ✅ VALID | MAJOR |
| 6 | P3 | ✅ VALID | MINOR |
| 7 | P3 | ❌ FALSE POSITIVE | — |
| 8 | P4 | ✅ VALID | MAJOR |
| 9 | P4 | ❌ FALSE POSITIVE | — |
| 10 | P4 | ✅ VALID | MAJOR |
| 11 | P4 | ✅ VALID | CRITICAL |
| 12 | P4 | ✅ VALID | MAJOR |
| 13 | P4 | ✅ VALID | MAJOR |
| 14 | P4 | ✅ VALID | MINOR |
| 15 | P4 | ✅ VALID | MAJOR |
| 16 | P2 | ✅ VALID | MAJOR |

**Total**: 14 VALID, 2 FALSE POSITIVES (issues 7, 9)

**Final Verdict**: FAIL — 14 valid issues remain, including 2 CRITICAL (missing transaction boundaries that risk data corruption) and multiple MAJOR issues.
