# U-PR2-TOCTOU-RACE Implementation Plan

> **Issue:** U-TOCTOU-01 — beforeSnapshot captured outside DB transaction in all update methods  
> **Severity:** P1  
> **PR:** U-PR2  
> **Branch:** master-refactor @ f49188e2  
> **Date:** 2026-05-31  
> **File:** `app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`  
> **Status:** ✅ IMPLEMENTED (2026-05-31)  
> **Build:** compileDebugKotlin PASSES

---

## 1. Issue Summary

The `TransactionLifecycleCoordinator` has 8+ update/delete methods that read the existing row via `expenseDao.getById()` **before** entering `database.withTransaction {}`. This creates a Time-Of-Check-to-Time-Of-Use (TOCTOU) race condition where:

1. Method reads existing row (snapshot A)
2. Another coroutine/thread modifies the same row (state becomes B)
3. Method enters transaction, persists its update, and writes `beforeSnapshot = A` to the audit log

The audit trail now contains a stale `beforeSnapshot` that never existed at the time of the actual write, corrupting the lifecycle event history and making rollback/reconciliation impossible.

---

## 2. Root Cause Analysis

**Pattern:** Every affected method follows this structure:

```kotlin
suspend fun updateXxx(expenseId: Long, ...) {
    val existing = expenseDao.getById(expenseId)       // ← READ outside transaction
    val beforeSnapshot = expenseToSnapshot(existing)    // ← STALE if concurrent write
    // ... compute updated entity ...
    database.withTransaction {
        expenseDao.update(updatedExpense)               // ← WRITE inside transaction
        transactionEventDao.insert(... beforeSnapshot ...) // ← STALE snapshot persisted
    }
}
```

**Why this exists:** The original implementation prioritized keeping the transaction block small (only writes). However, Room's `withTransaction` uses SQLite's `BEGIN IMMEDIATE` which provides a serializable isolation level — reads inside the transaction see a consistent snapshot and block concurrent writers.

**Proof the fix works:** The `deleteExpense(Long)` overload already has the correct pattern (comment: "P2-08: Load snapshot inside the transaction to prevent TOCTOU stale snapshots") — it reads inside `database.withTransaction`. This proves the pattern is viable and already validated in the codebase.

**Concurrency vectors:**
- UI thread updates expense while background worker (recurring linking, bank sync) modifies the same row
- Group expense coordinator updates ownership while user edits merchant
- Bulk merchant rename races with individual expense edit

---

## 3. Affected Methods Inventory

| # | Method | Line (approx) | Read Location | TOCTOU? | Notes |
|---|--------|---------------|---------------|---------|-------|
| 1 | `updateExpense` | ~350 | Before txn | **YES** | Full-row update, most complex |
| 2 | `updateMerchant` | ~530 | Before txn | **YES** | Recomputes dedupeKey |
| 3 | `updateType` | ~580 | Before txn | **YES** | Recomputes dedupeKey |
| 4 | `updateTransferDetails` | ~630 | Before txn | **YES** | Validates final state |
| 5 | `updateTypeAndTransferDetails` | ~680 | Before txn | **YES** | Atomic type+transfer |
| 6 | `updateBusinessExpensePatch` | ~450 | Before txn | **YES** | Business flags |
| 7 | `updateOwnershipDbOnlyV2` | ~750 | Before txn | **YES** | Ownership fields |
| 8 | `deleteExpense(Expense)` | ~870 | Caller-provided entity | **YES** | Stale entity from caller |
| 9 | `updateCategory` | ~420 | Before txn | **YES** | Category-only |
| 10 | `updateLocation` | ~470 | Before txn | **YES** | Location-only |

**Already fixed:**
| # | Method | Notes |
|---|--------|-------|
| ✅ | `deleteExpense(Long)` | Reads inside txn (P2-08 comment) |

---

## 4. Current Behavior (Broken)

```
T=0  Thread A: val existing = getById(42)  → sees {amount=50, merchant="Lidl"}
T=1  Thread B: updateMerchant(42, "LIDL")  → commits {merchant="LIDL"} inside its own txn
T=2  Thread A: database.withTransaction {
       expenseDao.update(expense.copy(amount=60))
       transactionEventDao.insert(beforeSnapshot = {amount=50, merchant="Lidl"})  // STALE!
     }
```

**Result:** The UPDATED event claims `beforeSnapshot.merchant = "Lidl"` but at the time of the actual write, the row already had `merchant = "LIDL"`. The audit trail is corrupted — any replay/undo logic would produce incorrect state.

---

## 5. Desired Behavior (Fixed)

```
T=0  Thread A: database.withTransaction {
       val existing = getById(42)  → sees {amount=50, merchant="LIDL"} (Thread B already committed)
       val beforeSnapshot = expenseToSnapshot(existing)  // ACCURATE
       expenseDao.update(expense.copy(amount=60))
       transactionEventDao.insert(beforeSnapshot = {amount=50, merchant="LIDL"})  // CORRECT
     }
```

**Guarantees:**
- `beforeSnapshot` always reflects the actual row state at the instant of mutation
- No concurrent writer can interleave between read and write (SQLite serialization)
- Audit trail is always reconstructable: `beforeSnapshot[N+1] == afterSnapshot[N]`

---

## 6. Implementation Strategy

### 6.1 Shared Helper: `atomicReadModifyWrite`

Extract a reusable inline function that encapsulates the correct pattern:

```kotlin
/**
 * Reads an expense inside a database transaction, captures the before-snapshot,
 * and executes the mutation block atomically. Prevents TOCTOU races by ensuring
 * the read and write happen in the same serializable transaction.
 *
 * @param expenseId The expense to load.
 * @param block Lambda receiving the existing expense and its JSON snapshot.
 *              Must perform all DAO writes inside this block.
 * @return The value returned by [block], or null if the expense was not found.
 */
private suspend inline fun <T> atomicReadModifyWrite(
    expenseId: Long,
    crossinline block: suspend (existing: Expense, beforeSnapshot: String) -> T
): T? = database.withTransaction {
    val existing = expenseDao.getById(expenseId) ?: return@withTransaction null
    val beforeSnapshot = expenseToSnapshot(existing)
    block(existing, beforeSnapshot)
}
```

### 6.2 Migration Strategy

Each method is refactored to move its `getById` + snapshot capture inside the transaction. Methods that perform pre-transaction validation (duplicate checks, currency conversion) will split into:

1. **Pre-transaction computation** (currency conversion, validation that doesn't need row state)
2. **Atomic read-modify-write** (read existing, validate against existing, write, log event)

This ensures validation that depends on the current row state (e.g., "did the merchant actually change?") uses the transactionally-consistent read.

### 6.3 `deleteExpense(Expense)` Special Case

This method accepts a caller-provided `Expense` entity that may be stale. The fix is to re-read the row inside the transaction (like `deleteExpense(Long)` already does) and use the fresh read for the snapshot, while still using the caller's entity for the `expenseDao.delete()` call (Room matches by primary key).

---

## 7. Code Changes

### 7.1 Add `atomicReadModifyWrite` helper

```kotlin
private suspend inline fun <T> atomicReadModifyWrite(
    expenseId: Long,
    crossinline block: suspend (existing: Expense, beforeSnapshot: String) -> T
): T? = database.withTransaction {
    val existing = expenseDao.getById(expenseId) ?: return@withTransaction null
    val beforeSnapshot = expenseToSnapshot(existing)
    block(existing, beforeSnapshot)
}
```

### 7.2 `updateExpense` — Move read + validation inside transaction

**Before:**
```kotlin
suspend fun updateExpense(expense: Expense, ...) {
    checkWritesAllowed("updateExpense")
    val now = timeProvider.now()
    val existing = expenseDao.getById(expense.id) ?: throw ...
    val beforeSnapshot = expenseToSnapshot(existing)
    // ... compute finalExpense ...
    database.withTransaction {
        expenseDao.update(finalExpense)
        transactionEventDao.insert(... beforeSnapshot ...)
    }
}
```

**After:**
```kotlin
suspend fun updateExpense(expense: Expense, ...) {
    checkWritesAllowed("updateExpense")
    val now = timeProvider.now()
    // Pre-transaction: currency conversion (doesn't depend on existing row)
    val homeCurrencyUpdate = try {
        currencySettingsRepository.homeCurrency().first()
    } catch (_: Exception) { CurrencyConverter.DEFAULT_BASE_CURRENCY }

    database.withTransaction {
        val existing = expenseDao.getById(expense.id)
            ?: throw IllegalArgumentException("Expense not found: ${expense.id}")
        val beforeSnapshot = expenseToSnapshot(existing)

        // Recompute dedupeKey, validate, currency-convert (all using fresh `existing`)
        // ... same logic, now inside transaction ...

        expenseDao.update(finalExpense)
        transactionEventDao.insert(... beforeSnapshot ...)
    }
    // Post-commit side effects unchanged
}
```

### 7.3 `updateMerchant` — Move read inside transaction

**After:**
```kotlin
suspend fun updateMerchant(expenseId: Long, newMerchant: String, ...) {
    checkWritesAllowed("updateMerchant")
    val now = timeProvider.now()
    val newMerchantKey = MerchantKeyGenerator.generate(newMerchant)

    database.withTransaction {
        val existing = expenseDao.getById(expenseId) ?: return@withTransaction
        if (existing.merchant == newMerchant) return@withTransaction
        val beforeSnapshot = expenseToSnapshot(existing)

        val newDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            existing.amount, newMerchant, existing.date, existing.currency, existing.transactionType
        )
        // Collision check inside transaction
        val collidingId = expenseDao.findDuplicateIdCurrencyAware(...)
        if (collidingId != null && collidingId != expenseId) {
            throw DuplicateUpdateException(...)
        }

        expenseDao.updateMerchantAndKey(expenseId, newMerchant, newMerchantKey, newDedupeKey)
        transactionEventDao.insert(... beforeSnapshot ...)
    }
    // Post-commit side effects
}
```

### 7.4 `updateType` — Move read inside transaction

Same pattern as `updateMerchant`: move `getById`, no-op check, dedupeKey recomputation, collision check, and event insert all inside `database.withTransaction`.

### 7.5 `updateTransferDetails` — Move read + validation inside transaction

Move `getById`, no-op check, `validateFinalExpenseState`, and event insert inside transaction. The validation failure event (`writeUpdateValidationFailedEventBestEffort`) can remain best-effort outside if validation fails (transaction rolls back).

### 7.6 `updateTypeAndTransferDetails` — Move read inside transaction

Move `getById`, collision check, validation, all DAO writes, and event insert inside transaction.

### 7.7 `updateBusinessExpensePatch` — Move read inside transaction

Move `getById`, no-change detection, and event insert inside transaction. The unsupported-fields check (which doesn't need the row) stays outside.

### 7.8 `updateOwnershipDbOnlyV2` — Move read inside transaction

Move `getById`, `normalizeOwnership`, no-change detection, and event insert inside transaction.

### 7.9 `updateCategory` — Move read inside transaction

Move `getById`, no-op check, and event insert inside transaction.

### 7.10 `updateLocation` — Move read inside transaction

Move `getById`, no-op check, and event insert inside transaction. Input validation (`require(latitude in ...)`) stays outside since it doesn't depend on row state.

### 7.11 `deleteExpense(Expense)` — Re-read inside transaction

**Before:**
```kotlin
suspend fun deleteExpense(expense: Expense, ...) {
    val snapshot = expenseToSnapshot(expense)  // ← stale caller entity
    database.withTransaction {
        transactionEventDao.insert(... snapshot ...)
        expenseDao.delete(expense)
    }
}
```

**After:**
```kotlin
suspend fun deleteExpense(expense: Expense, ...) {
    val now = timeProvider.now()
    database.withTransaction {
        // Re-read for accurate snapshot (caller entity may be stale)
        val fresh = expenseDao.getById(expense.id) ?: return@withTransaction
        val snapshot = expenseToSnapshot(fresh)
        transactionEventDao.insert(... snapshot ...)
        expenseDao.delete(fresh)
    }
}
```

---

## 8. Migration / Compatibility

| Concern | Impact | Mitigation |
|---------|--------|------------|
| API signature changes | **None** — all public method signatures remain identical | N/A |
| Transaction scope increase | Transactions now hold read + write (slightly longer) | SQLite WAL mode handles this; see §11 |
| `DuplicateUpdateException` thrown inside txn | Transaction auto-rolls back on exception | Callers already handle this exception |
| `TransactionValidationException` inside txn | Same — auto-rollback | Callers already handle this |
| `deleteExpense(Expense)` behavior change | Now re-reads row; if row was already deleted, returns failure | Matches `deleteExpense(Long)` behavior |
| Side effects still post-commit | No change — side effects remain outside transaction | N/A |

**Breaking changes:** None. All public APIs maintain identical signatures and semantics.

---

## 9. Testing Plan

### 9.1 Unit Tests (New)

| Test | Validates |
|------|-----------|
| `updateExpense_concurrentModification_snapshotReflectsActualState` | beforeSnapshot matches row state at write time, not stale read |
| `updateMerchant_concurrentCategoryChange_snapshotIncludesCategoryChange` | Cross-field consistency |
| `updateType_rowDeletedBetweenCheckAndWrite_handlesGracefully` | Row-not-found inside txn |
| `deleteExpense_entity_staleEntity_snapshotReflectsCurrentRow` | Re-read produces accurate snapshot |
| `updateOwnership_concurrentOwnershipChange_noCorruptSnapshot` | Ownership race |
| `updateBusinessPatch_concurrentAmountChange_snapshotAccurate` | Business patch race |

### 9.2 Integration Tests (New)

| Test | Validates |
|------|-----------|
| `toctou_twoCoroutines_updateSameExpense_auditTrailConsistent` | Launch two coroutines updating same expense; verify `beforeSnapshot[N+1] == afterSnapshot[N]` chain |
| `toctou_bulkMerchantRename_duringIndividualEdit_noCorruption` | Bulk + individual race |
| `toctou_deleteWhileUpdate_eitherSucceedsCleanly` | Delete/update race produces valid audit |

### 9.3 Regression Tests

| Test | Validates |
|------|-----------|
| `existingUpdateExpenseTests_stillPass` | No behavioral regression |
| `existingDeleteExpenseTests_stillPass` | No behavioral regression |
| `transactionEventChain_beforeAfterSnapshots_formValidChain` | Audit chain integrity |

### 9.4 Test Implementation Pattern

```kotlin
@Test
fun `updateExpense - concurrent modification - beforeSnapshot reflects actual state at write time`() = runTest {
    // 1. Create expense with merchant="Original"
    val id = createTestExpense(merchant = "Original")

    // 2. Simulate race: update merchant to "Modified" between read and write
    //    Use a custom DAO wrapper that delays inside withTransaction
    val latch = CompletableDeferred<Unit>()

    launch {
        // This will read "Original", then wait
        coordinator.updateExpense(
            expense = getExpense(id).copy(amount = 99.0),
            reason = "amount change"
        )
    }

    // Interleave: change merchant while first update is "thinking"
    expenseDao.updateMerchantAndKey(id, "Modified", "modified", "new-key")
    latch.complete(Unit)

    // 3. Verify: the UPDATED event's beforeSnapshot has merchant="Modified"
    val events = transactionEventDao.getEventsForExpense(id)
    val lastUpdate = events.last { it.eventType == "UPDATED" }
    val snapshot = JSONObject(lastUpdate.beforeSnapshot!!)
    assertEquals("Modified", snapshot.getString("merchant"))
}
```

---

## 10. Rollback Plan

| Step | Action |
|------|--------|
| 1 | Revert the single commit (all changes are in `TransactionLifecycleCoordinator.kt`) |
| 2 | No database migration involved — purely code-level change |
| 3 | No API changes to revert — signatures unchanged |
| 4 | Feature flag alternative: wrap new behavior in `if (BuildConfig.TOCTOU_FIX_ENABLED)` (not recommended — adds complexity for a correctness fix) |

**Risk of rollback:** Returns to the current broken state. Acceptable only if the fix introduces a regression worse than stale snapshots.

---

## 11. Performance Impact

| Metric | Before | After | Delta |
|--------|--------|-------|-------|
| Transaction duration | ~2ms (write-only) | ~3-4ms (read+write) | +1-2ms |
| Lock contention | Low (short txn) | Slightly higher | Negligible for single-user app |
| WAL checkpoint pressure | Baseline | Same | No change |
| Memory | Baseline | Same (one extra entity in scope) | Negligible |

**Analysis:** SQLite in WAL mode allows concurrent readers during a write transaction. The additional read inside the transaction adds ~1ms (single row by PK, indexed). For a single-user Android app with no concurrent write pressure beyond background workers, this is imperceptible.

**Worst case:** Bulk merchant rename holds a longer transaction (iterates N rows). This is already the case — the bulk methods already read inside the transaction. No regression.

---

## 12. Dependencies

| Dependency | Type | Status |
|------------|------|--------|
| Room `withTransaction` | Library | Already used ✅ |
| `ExpenseDao.getById` | Internal | Already exists ✅ |
| `expenseToSnapshot` | Internal | Already exists ✅ |
| `TransactionValidator` | Internal | Already exists ✅ |
| `CurrencyConverter` | Internal | Used pre-transaction (safe) ✅ |
| U-CANCEL-01 (U-PR1) | Sibling PR | Independent — no ordering dependency |
| U-SIDEEFFECT-02 (U-PR8) | Sibling PR | Independent — side effects remain post-commit |

**No external dependencies or new libraries required.**

---

## 13. Acceptance Criteria

| # | Criterion | Verification |
|---|-----------|--------------|
| AC-1 | All 10 affected methods read the existing row INSIDE `database.withTransaction` | Code review + grep for `getById` calls |
| AC-2 | `beforeSnapshot` is computed from the transactionally-consistent read | Code review |
| AC-3 | `deleteExpense(Expense)` re-reads the row inside the transaction | Code review |
| AC-4 | No public API signature changes | Compilation + existing test pass |
| AC-5 | All existing tests pass without modification | CI green |
| AC-6 | New TOCTOU race test passes (concurrent modification produces accurate snapshot) | New test green |
| AC-7 | Audit trail chain integrity: `beforeSnapshot[N+1] == afterSnapshot[N]` for sequential updates | Integration test |
| AC-8 | No-op detection (e.g., "merchant unchanged") still works correctly inside transaction | Existing + new tests |
| AC-9 | `DuplicateUpdateException` still thrown correctly when collision detected | Existing tests |
| AC-10 | Side effects still execute AFTER transaction commit (not inside) | Code review + existing tests |

**CI Guard (post-merge):**
Add a lint/detekt custom rule or code comment convention:
```kotlin
// TOCTOU-SAFE: All reads for beforeSnapshot MUST be inside database.withTransaction
```
Flag any new `expenseDao.getById` call that is followed by `database.withTransaction` without being inside it.

---

## 14. Timeline & Effort Estimate

| Phase | Task | Effort | Notes |
|-------|------|--------|-------|
| 1 | Add `atomicReadModifyWrite` helper | 15 min | Single inline function |
| 2 | Refactor `updateExpense` | 30 min | Most complex (currency conversion, validation) |
| 3 | Refactor `updateMerchant` | 15 min | Straightforward move |
| 4 | Refactor `updateType` | 15 min | Same pattern as merchant |
| 5 | Refactor `updateTransferDetails` | 15 min | Includes validation move |
| 6 | Refactor `updateTypeAndTransferDetails` | 20 min | Combined method |
| 7 | Refactor `updateBusinessExpensePatch` | 15 min | Partial move (unsupported check stays out) |
| 8 | Refactor `updateOwnershipDbOnlyV2` | 15 min | Straightforward move |
| 9 | Refactor `updateCategory` | 10 min | Simple |
| 10 | Refactor `updateLocation` | 10 min | Simple |
| 11 | Fix `deleteExpense(Expense)` | 10 min | Re-read pattern |
| 12 | Write unit tests (6 tests) | 45 min | |
| 13 | Write integration tests (3 tests) | 45 min | Concurrent coroutine tests |
| 14 | Run full test suite + verify | 20 min | |
| 15 | Code review prep + PR description | 15 min | |
| **Total** | | **~4.5 hours** | Single developer, single file |

**Priority:** Should be implemented before any new update methods are added to the coordinator. The `atomicReadModifyWrite` helper establishes the pattern for all future methods.

---

## Appendix A: Methods Already Correct

| Method | Why Safe |
|--------|----------|
| `deleteExpense(Long)` | Reads inside `database.withTransaction` (P2-08 fix) |
| `createExpenseMutation` | No "before" row exists — insert-only |
| `bulkUpdateCategory` (both overloads) | Reads inside transaction OR doesn't need per-row snapshot |
| `bulkUpdateMerchant` | Reads `getExpensesByMerchantKey` inside transaction |

## Appendix B: Decision Log

| Decision | Rationale |
|----------|-----------|
| Move reads inside transaction (not optimistic locking) | SQLite serializable isolation is sufficient; no need for version columns |
| Keep currency conversion outside transaction for `updateExpense` | `currencyConverter.convertAsOf` may do network I/O; holding a DB lock during network calls is unacceptable. Recompute inside txn only if amount/currency changed vs fresh `existing`. |
| Don't add `@Version` column | Over-engineering for a single-user Android app with limited concurrency |
| Keep `atomicReadModifyWrite` as private helper | Only this class needs it; no need to expose |
| Fix `updateCategory` and `updateLocation` too | Same pattern, same risk — fix all at once for consistency |
