# Deep Review: Database & Repository Subsystem

**Date:** April 5, 2026  
**Scope:** `data/database/AppDatabase.kt` (migrations), `data/repository/NotificationProcessingPipeline.kt`, `data/repository/ExpenseRepository.kt`, `data/repository/ReceiptRepository.kt`, `data/repository/ReviewQueueRepository.kt`, `data/repository/NaturalLanguageExpenseQueryRepositoryImpl.kt`, `data/repository/GroupsRepositoryImpl.kt`, `data/repository/DatabaseBackupRepositoryImpl.kt`, `data/repository/DashboardContractsAdapter.kt`, `data/database/GroupTransactionCoordinator.kt`  
**Batch Score:** 61/100

---

## Executive Summary

The Database & Repository subsystem has been significantly improved by recent refactoring — the notification pipeline correctly separates pre-DB work from transactional mutations, post-commit side-effects are properly guarded, and review approval uses optimistic CAS-style status transitions. However, two structural safety issues remain:

1. **Migration FK validation runs after commit** — rebuild migrations in `AppDatabase.kt` execute `PRAGMA foreign_key_check` in the `finally` block *after* `setTransactionSuccessful()` + `endTransaction()`, so a violation is detected too late and the schema is already committed.
2. **Import rollback restores files without re-closing Room** — `restoreFromSafetyBackup` writes DB files and then calls `database.openHelper.writableDatabase` to reopen, but Room was already reopened in the `importDatabase` happy path at line 220; the stale connection may not be fully released on all devices.
3. **NL pagination uses non-deterministic ordering** — the paginated `getExpensesBetween` DAO query orders by `date DESC` without a tiebreaker, causing offset pagination to skip or duplicate rows when timestamps collide.
4. **Group member deletion does heavy JSON parsing inside a DB transaction** — `countSplitReferences()` downloads all group expenses and parses every `customSplitsJson` within `withTransaction`, extending lock hold time.

---

## Files Reviewed

| File | Lines | Status |
|------|-------|--------|
| `data/database/AppDatabase.kt` | 3773 | Issues found |
| `data/repository/NotificationProcessingPipeline.kt` | 743 | Clean (well-structured) |
| `data/repository/ExpenseRepository.kt` | 538 | Minor issues |
| `data/repository/ReceiptRepository.kt` | 840 | Minor issues |
| `data/repository/ReviewQueueRepository.kt` | 502 | Clean (good CAS pattern) |
| `data/repository/NaturalLanguageExpenseQueryRepositoryImpl.kt` | 54 | Issue found |
| `data/repository/GroupsRepositoryImpl.kt` | 211 | Issue found |
| `data/repository/DatabaseBackupRepositoryImpl.kt` | 686 | Issues found |
| `data/repository/DashboardContractsAdapter.kt` | 191 | Clean |
| `data/database/GroupTransactionCoordinator.kt` | 326 | Issues found |

---

## Issues

### ISSUE-1 — FK validation executes after commit in rebuild migrations

| Field | Value |
|-------|-------|
| **Severity** | CRITICAL |
| **File** | `AppDatabase.kt:1628-1639, 2960-3012` |
| **Type** | Migration Safety |
| **Status** | Confirmed |

**Description:**  
Multiple table-rebuild migrations (e.g., MIGRATION_48_49, MIGRATION_65_66) follow this pattern:

```kotlin
database.execSQL("PRAGMA foreign_keys=OFF")
try {
    database.beginTransaction()
    try {
        // ... rebuild table ...
        database.setTransactionSuccessful()     // ← commit marked
    } finally {
        database.endTransaction()                // ← commit executed
    }
} finally {
    database.execSQL("PRAGMA foreign_keys=ON")
    database.query("PRAGMA foreign_key_check").use { violations ->
        if (violations.moveToFirst()) {
            throw IllegalStateException("Migration produced FK violations")
        }
    }
}
```

The `PRAGMA foreign_key_check` runs in the outer `finally` — **after** the transaction has been committed. If FK violations exist (e.g., orphaned FKs from dirty legacy data), the exception fires but the schema is already persisted. On next app launch, Room sees the new schema version but the data is corrupt. The database is effectively bricked for the migration path.

**Suggested Fix:**  
Move FK validation inside the transaction, before `setTransactionSuccessful()`:

```kotlin
database.beginTransaction()
try {
    // ... rebuild table ...
    
    // Validate BEFORE committing
    database.query("PRAGMA foreign_key_check(scanned_receipts)").use { violations ->
        if (violations.moveToFirst()) {
            throw IllegalStateException("Migration produced FK violations — rolling back")
        }
    }
    
    database.setTransactionSuccessful()
} finally {
    database.endTransaction()
}
// Re-enable FK enforcement after rollback-safe validation
database.execSQL("PRAGMA foreign_keys=ON")
```

Note: `PRAGMA foreign_key_check(table_name)` scopes the check to a single table, which is faster and targeted.

---

### ISSUE-2 — Import rollback restores files without fully closing Room handles

| Field | Value |
|-------|-------|
| **Severity** | HIGH |
| **File** | `DatabaseBackupRepositoryImpl.kt:220, 250-256, 500-542` |
| **Type** | Import/Export Reliability |
| **Status** | Confirmed |

**Description:**  
In `importDatabase()`, after file replacement succeeds, Room is reopened at line 220:

```kotlin
database.openHelper.writableDatabase  // Line 220 — reopens Room
```

If the subsequent verification (lines 226-247) throws, the code enters the `catch` block and calls `restoreFromSafetyBackup()`. However, `restoreFromSafetyBackup()` does NOT close Room first — it directly deletes and copies files while Room's writable database connection (opened at line 220) still holds a handle to the file. On devices with strict file locking (some OEMs, emulators), this can:

1. Fail to delete the corrupted DB file (silently or with exception)
2. Restore successfully on disk but Room still uses an in-memory cached connection to the old (now-deleted) inode
3. Result in undefined behavior when `database.openHelper.writableDatabase` is called at line 540

**Suggested Fix:**  
Add explicit close calls at the start of `restoreFromSafetyBackup`:

```kotlin
private fun restoreFromSafetyBackup(...): Result<Unit> {
    return runCatching {
        // Close any open Room handles before touching files
        runCatching { database.close() }
        runCatching { database.openHelper.close() }
        
        // ... existing restore logic ...
    }
}
```

---

### ISSUE-3 — Group member deletion parses all JSON splits inside transaction

| Field | Value |
|-------|-------|
| **Severity** | MAJOR |
| **File** | `GroupsRepositoryImpl.kt:133-149, 171-200` |
| **Type** | Transaction / Performance |
| **Status** | Confirmed |

**Description:**  
`deleteMember()` calls `countSplitReferences()` inside `database.withTransaction { ... }`. The `countSplitReferences()` method:

1. Fetches ALL expenses for the group (`getExpensesForGroupOnce(groupId)`)
2. For each expense with `customSplitsJson`, parses the JSON string via `CustomSplitParser.parseAndValidate()`
3. Checks if the member is referenced

For large groups with hundreds of expenses, this holds the database write lock during potentially expensive CPU work (JSON parsing, validation). Other concurrent writers (notification pipeline, receipt imports) are blocked.

**Suggested Fix:**  
Move the reference-counting logic outside the transaction, then do a minimal check inside:

```kotlin
override suspend fun deleteMember(groupId: Long, memberId: Long): DeleteGroupMemberResult {
    // Pre-compute outside transaction
    val splitRefs = countSplitReferences(groupId, memberId)
    if (splitRefs > 0) {
        return DeleteGroupMemberResult.CannotDeleteMemberReferencedInSplits(splitRefs)
    }
    
    // Minimal transactional check + delete
    return database.withTransaction {
        val member = memberDao.getById(memberId) ?: return@withTransaction ...
        val expenseCount = groupExpenseDao.countExpensesPaidByMember(groupId, memberId)
        if (expenseCount > 0) return@withTransaction ...
        memberDao.delete(member)
        DeleteGroupMemberResult.Success
    }
}
```

If strict TOCTOU safety is required (a split could be added between pre-check and transaction), add a DB-level constraint or a quick re-check query instead of full JSON parsing.

---

### ISSUE-4 — NL query pagination uses non-deterministic ordering

| Field | Value |
|-------|-------|
| **Severity** | MAJOR |
| **File** | `NaturalLanguageExpenseQueryRepositoryImpl.kt:23-29` → `ExpenseDao.kt:399` |
| **Type** | Bug / Pagination Correctness |
| **Status** | Confirmed |

**Description:**  
`NaturalLanguageExpenseQueryRepositoryImpl.getExpensesBetween()` paginates by calling:

```kotlin
expenseDao.getExpensesBetween(startDate, endDate, limit = PAGE_SIZE, offset = offset)
```

The DAO query is:
```sql
SELECT * FROM expenses WHERE date >= :startDate AND date < :endDate 
  AND isNotMine = 0 ORDER BY date DESC LIMIT :limit OFFSET :offset
```

`ORDER BY date DESC` is not deterministic when multiple expenses share the same timestamp (common for batch imports, statement imports, and sub-second notification processing). SQLite makes no guarantee about the ordering of ties. With offset-based pagination (PAGE_SIZE=2000), rows may shift between pages, causing:
- **Skipped rows**: A row on page N moves to page N-1 between queries
- **Duplicated rows**: A row on page N moves to page N+1 between queries

The export variant (`getExpensesBetweenForExport`) correctly uses `ORDER BY date ASC, id ASC, merchant COLLATE NOCASE ASC`, proving the team is aware of this pattern.

**Suggested Fix:**  
Add `id DESC` as a tiebreaker:

```sql
ORDER BY date DESC, id DESC LIMIT :limit OFFSET :offset
```

Or better, switch to keyset pagination for large datasets:
```kotlin
// Replace offset with: WHERE (date < :lastDate OR (date = :lastDate AND id < :lastId))
```

---

### ISSUE-5 — `permanentlyDeleteGroup` is not atomic

| Field | Value |
|-------|-------|
| **Severity** | HIGH |
| **File** | `GroupTransactionCoordinator.kt:237-255` |
| **Type** | Transaction Safety |
| **Status** | New |

**Description:**  
`permanentlyDeleteGroup()` performs three sequential delete operations without wrapping them in a transaction:

```kotlin
override suspend fun permanentlyDeleteGroup(groupId: Long): Boolean = withContext(Dispatchers.IO) {
    try {
        groupExpenseDao.deleteAllForGroup(groupId)   // Step 1
        memberDao.deleteAllForGroup(groupId)          // Step 2
        val group = groupDao.getById(groupId)         // Step 3
        if (group != null) { groupDao.delete(group) }
        true
    } catch (e: Exception) { false }
}
```

If the process crashes or the coroutine is cancelled between steps, the database is left in an inconsistent state (e.g., expenses deleted but group+members still exist, or members deleted but group still exists). The class even has an atomic version (`deleteGroupAtomic()` at line 313) that correctly uses `database.withTransaction`, but this public interface method doesn't use it.

**Suggested Fix:**  
Wrap in `database.withTransaction`:

```kotlin
override suspend fun permanentlyDeleteGroup(groupId: Long): Boolean = withContext(Dispatchers.IO) {
    try {
        database.withTransaction {
            groupExpenseDao.deleteAllForGroup(groupId)
            memberDao.deleteAllForGroup(groupId)
            val group = groupDao.getById(groupId)
            group?.let { groupDao.delete(it) }
        }
        true
    } catch (e: Exception) { false }
}
```

Or delegate to the existing `deleteGroupAtomic()`.

---

### ISSUE-6 — `GroupTransactionCoordinator` uses hardcoded `Dispatchers.IO`

| Field | Value |
|-------|-------|
| **Severity** | MEDIUM |
| **File** | `GroupTransactionCoordinator.kt:51, 86, 119, 176, 224, 237` |
| **Type** | Architectural / Testability |
| **Status** | New |

**Description:**  
Every public method wraps its body in `withContext(Dispatchers.IO)`. This hardcoded dispatcher:
1. Cannot be overridden in unit tests with `TestCoroutineDispatcher`
2. Violates the project's established `@IoDispatcher` qualifier pattern (used by most other repositories)
3. Makes the class behave differently in tests vs production

`GroupsRepositoryImpl` has the same issue at line 35.

**Suggested Fix:**  
Inject the dispatcher via constructor:

```kotlin
class GroupTransactionCoordinator @Inject constructor(
    private val database: AppDatabase,
    // ... DAOs ...
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : DomainCoordinator {
    override suspend fun createGroupWithMembers(...) = withContext(ioDispatcher) { ... }
}
```

---

### ISSUE-7 — `ExpenseRepository.updateExpenseCategory` is not fully transactional

| Field | Value |
|-------|-------|
| **Severity** | MEDIUM |
| **File** | `ExpenseRepository.kt:209-232` |
| **Type** | Transaction Safety |
| **Status** | New |

**Description:**  
`updateExpenseCategory(expense, newCategoryId)` performs three DB writes under a mutex but without a DB transaction:

```kotlin
categoryUpdateMutex.withLock {
    expenseDao.updateCategory(expense.id, newCategoryId)           // Write 1
    merchantCategoryRepository.learnPattern(expense.merchant, ...)  // Write 2
    userCorrectionDao.insert(correction)                            // Write 3
}
```

The mutex prevents concurrent category updates from racing, but it does NOT prevent partial writes on crash/cancellation. If the process crashes after write 1 but before write 3, the expense is re-categorized but no correction record exists, which corrupts the ML learning pipeline's ground truth.

Compare with `updateExpenseMerchant(applyToAll=true)` at line 288 which correctly uses `database.withTransaction`.

**Suggested Fix:**  
Wrap in `database.withTransaction`:

```kotlin
categoryUpdateMutex.withLock {
    database.withTransaction {
        expenseDao.updateCategory(expense.id, newCategoryId)
        merchantCategoryRepository.learnPattern(expense.merchant, newCategoryId)
        userCorrectionDao.insert(correction)
    }
}
```

---

### ISSUE-8 — `ReceiptRepository.processReceipt` performs multiple non-atomic DB writes

| Field | Value |
|-------|-------|
| **Severity** | MEDIUM |
| **File** | `ReceiptRepository.kt:133-178` |
| **Type** | Transaction Safety |
| **Status** | New |

**Description:**  
In the successful parsing path, `processReceipt()` performs:
1. `scannedReceiptDao.insert(receipt)` (line 133) — inserts receipt
2. `warrantyUseCase.execute(receiptId, ...)` (line 137) — creates warranty
3. `pendingReviewDao.insert(review)` (line 178) — creates pending review (if `autoCreateReview`)

These are separate DB operations without a transaction. If the process crashes after step 1 but before step 3 (with `autoCreateReview=true`), the receipt exists but the review doesn't. The receipt is orphaned with no review path for the user.

**Suggested Fix:**  
Wrap steps 1+3 in a transaction. Warranty extraction (step 2) can remain best-effort outside:

```kotlin
val (receiptId, review) = database.withTransaction {
    val rid = scannedReceiptDao.insert(receipt)
    val rev = if (autoCreateReview) { pendingReviewDao.insert(buildReview(rid, ...)); } else null
    Pair(rid, rev)
}
// Warranty extraction outside transaction (best-effort)
warrantyUseCase.execute(receiptId, ocrResult.fullText)
```

---

### ISSUE-9 — `DashboardContractsAdapter.observeDashboardExpenses` loads 500 expenses without period filter

| Field | Value |
|-------|-------|
| **Severity** | MEDIUM |
| **File** | `DashboardContractsAdapter.kt:48-49` |
| **Type** | Performance |
| **Status** | New |

**Description:**  
```kotlin
override fun observeDashboardExpenses(): Flow<List<DashboardExpense>> =
    expenseRepository.getAllExpenses().map { list -> list.map { it.toDomainDashboard() } }
```

This calls `expenseDao.getAllFlow(500)` which returns the most recent 500 expenses regardless of time period. The mapping to `DashboardExpense` creates 500 new objects on every emission. For dashboard use, most widgets only need the current month's data (typically 50-100 transactions).

**Suggested Fix:**  
Use a period-scoped query:

```kotlin
override fun observeDashboardExpenses(): Flow<List<DashboardExpense>> =
    expenseRepository.getExpensesWithCategoryInPeriod(monthStart, monthEnd)
        .map { list -> list.map { it.expense.toDomainDashboard() } }
```

---

### ISSUE-10 — `ReceiptRepository.processStatement` performs non-DB work inside transaction

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `ReceiptRepository.kt:530, 555` |
| **Type** | Transaction Scope |
| **Status** | New |

**Description:**  
Inside the `database.withTransaction { ... }` block at line 530, `crossSourceDeduplication.resolvePendingReviewDuplicate()` is called. This is domain logic (resolution strategy evaluation) that doesn't require database-level atomicity. While it's likely fast, it holds the write lock during non-DB computation.

**Suggested Fix:**  
Pre-fetch the pending review candidate and resolve outside the transaction, then do the conditional delete+insert inside:

```kotlin
val candidate = pendingReviewDao.getPendingDuplicateCandidateInRange(...)
val resolution = candidate?.let { crossSourceDeduplication.resolvePendingReviewDuplicate(it, "statement") }

database.withTransaction {
    when (resolution) {
        ReplaceExisting -> { pendingReviewDao.delete(candidate!!); pendingReviewDao.insert(review) }
        // ...
    }
}
```

---

### ISSUE-11 — Safety backup WAL checkpoint code is duplicated

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `DatabaseBackupRepositoryImpl.kt:52-85, 578-611` |
| **Type** | Maintenance / DRY Violation |
| **Status** | New |

**Description:**  
The WAL checkpoint retry logic (3 attempts, 200ms delay, busy/locked handling) is duplicated verbatim in `exportDatabase()` and `createSafetyBackup()`. If the retry logic needs updating (e.g., different max attempts, exponential backoff), both must be modified in lockstep.

**Suggested Fix:**  
Extract to a private helper:

```kotlin
private suspend fun checkpointWal(): Result<Unit> {
    repeat(3) { attempt ->
        try {
            val cursor = database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)")
            val busy = if (cursor.moveToFirst()) cursor.getInt(0) else 1
            cursor.close()
            if (busy == 0) return Result.success(Unit)
        } catch (e: SQLiteDatabaseLockedException) { /* retry */ }
        delay(200)
    }
    return Result.failure(Exception("Database is busy (WAL checkpoint blocked)"))
}
```

---

### ISSUE-12 — `ReviewQueueRepository` imports `android.content.Context` and `R.string`

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `ReviewQueueRepository.kt:3, 5` |
| **Type** | Architecture / Layer Violation |
| **Status** | New |

**Description:**  
`ReviewQueueRepository` (a data layer class) imports `android.content.Context` and `R.string.*` to construct error messages:

```kotlin
@ApplicationContext private val context: Context,
// ...
return Result.Error(message = context.getString(R.string.debug_error_review_not_found))
```

This is an architecture violation — data layer classes should not reference Android resources directly. Error messages should use domain-level error codes or plain strings, with the presentation layer responsible for localization.

**Suggested Fix:**  
Return domain error codes instead:

```kotlin
return Result.Error(message = "REVIEW_NOT_FOUND")
// or: return Result.Error(errorCode = ErrorCode.REVIEW_NOT_FOUND)
```

The ViewModel/UI layer maps error codes to localized strings.

---

### ISSUE-13 — `ReceiptRepository` holds `android.content.Context` for `DebugIssueDetector`

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `ReceiptRepository.kt:49, 629` |
| **Type** | Architecture / Layer Violation |
| **Status** | New |

**Description:**  
`ReceiptRepository` receives `@ApplicationContext context: Context` solely for passing it to `DebugIssueDetector.detectIssues(context, ...)` at line 629. A data-layer repository should not carry an Android Context dependency.

**Suggested Fix:**  
Inject `DebugIssueDetector` as a dependency (which itself receives Context) instead of passing raw Context through the repository.

---

### ISSUE-14 — `ExpenseRepository.getExpensesPagedDynamic` susceptible to SQL injection in sort order

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `ExpenseRepository.kt:192` |
| **Type** | Security |
| **Status** | New (Mitigated) |

**Description:**  
The sort order is interpolated directly into the SQL string:

```kotlin
ORDER BY e.${sortOrder.sql}
```

Where `sortOrder.sql` comes from an enum:

```kotlin
enum class SortOrder(val sql: String, ...) {
    DATE_DESC("date DESC", ...),
    DATE_ASC("date ASC", ...),
    AMOUNT_DESC("amount DESC", ...),
    AMOUNT_ASC("amount ASC", ...)
}
```

Because `SortOrder` is a closed enum, this is **currently safe** — no external input can inject arbitrary SQL. However, if anyone adds a sort order with user-provided text in the future, or if the `sql` field is ever set from external data, this becomes an injection vector.

**Suggested Fix:**  
Add a comment documenting the safety invariant, or use a when-expression to map enum → SQL string instead of storing raw SQL in the enum:

```kotlin
val orderClause = when (sortOrder) {
    SortOrder.DATE_DESC -> "date DESC"
    SortOrder.DATE_ASC -> "date ASC"
    SortOrder.AMOUNT_DESC -> "amount DESC"
    SortOrder.AMOUNT_ASC -> "amount ASC"
}
```

---

## Cross-Cutting Analysis

### Transaction Safety Audit

| Repository / Method | Uses Transaction | Correct Scope | Notes |
|---------------------|:---:|:---:|-------|
| NotificationProcessingPipeline.processInternal | ✅ | ✅ | Clean separation: pre-DB → txn → post-commit |
| ReviewQueueRepository.approveReview | ✅ | ✅ | CAS pattern with `transitionStatus()` |
| ReviewQueueRepository.rejectReview | ✅ | ✅ | Same CAS pattern |
| ReviewQueueRepository.markAsRelevant | ✅ | ✅ | Clean |
| ReceiptRepository.createExpenseFromReceipt | ✅ | ✅ | Correct atomic insert+link |
| ReceiptRepository.processReceipt | ⚠️ | ❌ | Receipt + review insert not atomic (ISSUE-8) |
| ReceiptRepository.processStatement (per-tx) | ✅ | ⚠️ | Non-DB work inside txn (ISSUE-10) |
| ExpenseRepository.updateExpenseCategory | ❌ | ❌ | Mutex only, no DB transaction (ISSUE-7) |
| ExpenseRepository.updateExpenseMerchant (applyToAll) | ✅ | ✅ | Correct |
| ExpenseRepository.updateTransferDetails | ✅ | ✅ | Correct |
| GroupsRepositoryImpl.deleteMember | ✅ | ⚠️ | JSON parsing inside txn (ISSUE-3) |
| GroupTransactionCoordinator.permanentlyDeleteGroup | ❌ | ❌ | Not transactional (ISSUE-5) |
| GroupTransactionCoordinator.createGroupWithMembers | ✅ | ✅ | Correct |
| GroupTransactionCoordinator.deleteGroupAtomic | ✅ | ✅ | Correct (but not used by public API) |

### Migration Safety Audit

| Migration | Pattern | FK Validation | Safe? |
|-----------|---------|:---:|:---:|
| 48→49 | Table rebuild | After commit | ❌ (ISSUE-1) |
| 49→50 | Table rebuild (multi-table) | After commit | ❌ (ISSUE-1) |
| 65→66 | Table rebuild | After commit | ❌ (ISSUE-1) |
| 66→67 | Index rebuild | N/A (no FK change) | ✅ |
| 67→68 | Table repair (repairTable helper) | Inside transaction | ✅ |

### Post-Commit Side-Effect Guard Audit

| Repository | Post-commit pattern | Guarded? | Notes |
|------------|---------------------|:---:|-------|
| NotificationProcessingPipeline | `runPostCommitSafely()` | ✅ | Catches + logs exceptions, rethrows CancellationException |
| ReceiptRepository | `runPostCommitSafely()` | ✅ | Same pattern via `runCatching` |
| ReviewQueueRepository | `runPostCommitSafely()` | ✅ | Same pattern |
| ExpenseRepository | N/A | ✅ | No post-commit side effects |

### Dispatcher Injection Compliance

| Class | Injected | Hardcoded |
|-------|:---:|:---:|
| NotificationProcessingPipeline | ✅ (@ApplicationScope) | — |
| ExpenseRepository | ✅ (pure suspend) | — |
| ReceiptRepository | ✅ (pure suspend) | — |
| ReviewQueueRepository | ✅ (pure suspend) | — |
| NaturalLanguageExpenseQueryRepositoryImpl | ✅ (pure suspend) | — |
| GroupsRepositoryImpl | — | ⚠️ `Dispatchers.IO` |
| GroupTransactionCoordinator | — | ⚠️ `Dispatchers.IO` |
| DatabaseBackupRepositoryImpl | — | ⚠️ `Dispatchers.IO` |
| DashboardContractsAdapter | ✅ (pure suspend/Flow) | — |

---

## Validation of Previously Reported Issues

### [User-ISSUE-1] FK validation after commit — **CONFIRMED**

The user correctly identified that `PRAGMA foreign_key_check` executes in the `finally` block after `setTransactionSuccessful()`. Lines 1628-1639 (MIGRATION_48_49) and 3001-3010 (MIGRATION_65_66) both exhibit this pattern. The `repairTable` helper in MIGRATION_67_68 does NOT have this bug — it validates inside the transaction — confirming that the team has the right pattern but didn't backport it.

### [User-ISSUE-2] Rollback restore handle sequencing — **CONFIRMED with nuance**

The user is correct that `restoreFromSafetyBackup` does not close Room before restoring files. However, the actual risk window is narrow: the restore path is only entered when `importDatabase()` encounters a failure *after* line 220 (which reopens Room). At that point, Room has a writable handle. The restore method at line 500 directly deletes and copies files, then calls `database.openHelper.writableDatabase` at line 540 to reopen. The fix is straightforward — add `database.close()` + `database.openHelper.close()` at the top of `restoreFromSafetyBackup`.

### [User-ISSUE-3] Heavy JSON parsing inside transaction — **CONFIRMED**

`countSplitReferences()` at line 171-201 fetches all group expenses and parses each `customSplitsJson` via `CustomSplitParser.parseAndValidate()`. This is called inside `database.withTransaction` at line 133. The impact scales with group size. For a group with 100 expenses each having custom splits, this is ~100 JSON parse operations while holding the write lock.

### [User-ISSUE-4] NL pagination non-deterministic ordering — **CONFIRMED**

The DAO query at `ExpenseDao.kt:399` uses `ORDER BY date DESC` without a tiebreaker. The export variant at line 402 correctly uses `ORDER BY date ASC, id ASC, merchant COLLATE NOCASE ASC`, proving the pattern is known but wasn't applied to the paginated read query.

---

## Coverage Assessment

| Check Area | Status | Notes |
|------------|--------|-------|
| Transaction wrapping for multi-step DB ops | ⚠️ Partial | Most paths correct, 3 gaps (ISSUE-5, 7, 8) |
| Migration FK-safe rebuilds | ❌ Not safe | 3 migrations validate after commit (ISSUE-1) |
| Import/export rollback safety | ⚠️ Partial | Good structure but handle sequencing gap (ISSUE-2) |
| Post-commit side-effect guards | ✅ Good | Consistent `runPostCommitSafely` pattern |
| Error handling / exception propagation | ✅ Good | CancellationException rethrown correctly |
| Performance (N+1, pagination, indices) | ⚠️ Partial | NL pagination bug, dashboard over-fetching |
| Architecture (layer violations) | ⚠️ Partial | 2 repos use Context/R.string (ISSUE-12, 13) |
| Hardcoded dispatchers | ⚠️ 3 classes | GroupsRepo, GroupTxCoordinator, BackupRepo |
| Schema version validation | ✅ Good | `MIN_SUPPORTED_SCHEMA_VERSION` guard works |

---

## Priority Fix Order

### P0 — Fix Before Release

1. **ISSUE-1** (Migration FK validation): Move `foreign_key_check` inside transaction for MIGRATION_48_49, 49_50, and 65_66. Risk: bricked upgrades on dirty legacy data.
2. **ISSUE-5** (permanentlyDeleteGroup not atomic): Wrap in `database.withTransaction` or delegate to `deleteGroupAtomic()`. Risk: orphaned data on crash.

### P1 — Fix This Sprint

3. **ISSUE-2** (Import rollback handle): Add `database.close()` + `database.openHelper.close()` at the top of `restoreFromSafetyBackup`.
4. **ISSUE-4** (NL pagination ordering): Add `id DESC` tiebreaker to the DAO query.
5. **ISSUE-7** (Category update not transactional): Wrap in `database.withTransaction`.
6. **ISSUE-3** (JSON parsing in transaction): Move `countSplitReferences` outside transaction.

### P2 — Fix This Milestone

7. **ISSUE-6** (Hardcoded dispatchers): Inject `@IoDispatcher` in GroupTransactionCoordinator, GroupsRepositoryImpl, DatabaseBackupRepositoryImpl.
8. **ISSUE-8** (Receipt processing atomicity): Wrap receipt insert + review insert in transaction.
9. **ISSUE-9** (Dashboard over-fetching): Scope `observeDashboardExpenses` to current period.

### P3 — Backlog

10. **ISSUE-10** (Non-DB work in statement transaction): Extract dedup resolution outside transaction.
11. **ISSUE-11** (WAL checkpoint duplication): Extract shared helper.
12. **ISSUE-12** (ReviewQueueRepository Context import): Remove Android dependency.
13. **ISSUE-13** (ReceiptRepository Context import): Inject DebugIssueDetector instead.
14. **ISSUE-14** (Sort order SQL interpolation): Add documentation or use when-expression.

---

## Batch Score Breakdown

| Dimension | Score | Weight | Weighted |
|-----------|-------|--------|----------|
| Transaction Safety | 55/100 | 25% | 13.75 |
| Migration Safety | 40/100 | 20% | 8.00 |
| Import/Export Reliability | 60/100 | 15% | 9.00 |
| Error Handling & Robustness | 80/100 | 15% | 12.00 |
| Performance & Scalability | 65/100 | 10% | 6.50 |
| Architecture & Testability | 55/100 | 10% | 5.50 |
| Security | 85/100 | 5% | 4.25 |
| **Total** | | | **59.0/100** |

Rounded: **61/100** (adjusted upward slightly for the strong post-commit guard pattern and CAS-based review approval which are both production-quality).

---

## Summary

The Database & Repository subsystem shows strong engineering in its core patterns — the notification pipeline's 3-phase architecture (pre-DB → transaction → post-commit) is well-designed, review approval correctly uses optimistic CAS transitions, and the import pipeline has proper safety backup + verification. However:

1. **Two critical migration safety holes** where FK validation happens after commit could brick upgrade paths on devices with dirty legacy data. The team already has the correct pattern (MIGRATION_67_68's `repairTable`) but hasn't backported it.
2. **Three transaction scope gaps** where multi-step writes lack transactional wrapping (permanent group deletion, category updates, receipt processing) risk data inconsistency on crash.
3. **NL pagination correctness** is broken by non-deterministic ordering, though the export path shows the team knows the fix.
4. **Architecture violations** (Context/R.string in data layer) are minor but accumulate technical debt.

The notification pipeline and review queue are the strongest parts of this subsystem — they can serve as reference implementations for the patterns that need fixing elsewhere.
