Here is the dedicated agent-ready implementation plan for **P2-P1-04** and **P2-P1-05**.

<pipeline2_p1_04_05_implementation_plan.md>
# Pipeline 2 implementation plan — P2-P1-04 + P2-P1-05

Target baseline: commit `ad91767a9f30db77b6d4b6d8410d788eeaa610c0`

Scope:

- `P2-P1-04`: Debug/restore expense methods are guarded but weakly audited.
- `P2-P1-05`: `ExpenseDao` mutation surface is still public, and no static/compile guard proves direct mutations are approved.

Do **not** mix this PR with update validation, dedupe, group, receipt, or source-link fixes.

---

# Current evidence

## P2-P1-04

`ExpenseRepository` currently has debug methods:

```kotlin
deleteAllExpenses()
createDebugSnapshot()
restoreDebugSnapshot(snapshot)
```

Current state:

- `deleteAllExpenses()` checks `writeBarrier`.
- `deleteAllExpenses()` is disabled outside `BuildConfig.DEBUG`.
- `restoreDebugSnapshot()` checks `writeBarrier`.
- `restoreDebugSnapshot()` is disabled outside `BuildConfig.DEBUG`.
- `createDebugSnapshot()` is disabled outside `BuildConfig.DEBUG`.

But:

- `deleteAllExpenses()` directly calls `expenseDao.deleteAll()` and writes no aggregate lifecycle/debug audit event.
- `restoreDebugSnapshot()` directly does `deleteAll()` + `insertAll()` and writes no aggregate restore event.
- `createDebugSnapshot()` writes no durable diagnostic.

`LifecycleEventType` already has:

```kotlin
RESTORED_FROM_DEBUG_SNAPSHOT
```

but it is not used by the debug restore path.

## P2-P1-05

`ExpenseDao` has a TODO saying mutation methods are public and should be access-controlled.

Mutating methods include:

```kotlin
insert
insertAtomic
insertAll
update
delete
deleteAll
updateCategory
updateCategoryNullable
updateCategoryForMerchant
updateMerchantForMerchant
updateMerchant
updateMerchantAndKey
updateTransactionType
updateDedupeKey
updateTransferDirection
updateTransferAccountName
updateIsNotMine
updateOwnerName
updateIsSharedExpense
updateSharedWithName
updateMySharePercentage
updateMyShareAmount
clearSharedExpenseFlags
incrementBackfillAttempts
updateLocation
conditionallySetLocation
clearLocation
updateMerchantKey
```

Some repository maintenance writes now have `writeBarrier`, but there is no compile-time or CI guard preventing future direct DAO mutations.

---

# Desired end state

## P2-P1-04 done when

- `deleteAllExpenses()` writes one aggregate debug audit event.
- `restoreDebugSnapshot()` writes one aggregate restore audit event.
- Mutating debug audit events are written in the same DB transaction as the mutation.
- `createDebugSnapshot()` emits a privacy-safe diagnostic event.
- No per-expense event spam is created for bulk debug operations.
- No raw expense data, merchants, notes, receipt data, or snapshots are written into debug audit metadata.
- Debug methods remain impossible in release builds.

## P2-P1-05 done when

- Every `ExpenseDao` mutation method is marked as restricted.
- Only approved classes/functions can call restricted mutation methods.
- Any new direct `ExpenseDao` mutation outside approved paths fails compile or architecture tests.
- Approved bypasses are explicitly documented with reason.
- Repository maintenance/debug writes still use `DatabaseWriteBarrier`.

---

# PR structure

Implement as two PRs.

## PR A — Debug/restore aggregate audit

Fixes:

- `P2-P1-04`

## PR B — Restricted `ExpenseDao` mutation access + architecture guard

Fixes:

- `P2-P1-05`

Recommended order:

1. PR A first, because it changes debug mutation methods.
2. PR B second, because it adds compile/static enforcement and will force approved annotations around the PR A debug paths.

It is acceptable to combine both into one PR if the agent keeps commits separated.

---

# PR A — Debug/restore aggregate audit

## Files to modify

Primary:

```text
app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt
```

New file:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/DebugExpenseAuditWriter.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/DebugExpenseAuditWriterTest.kt
app/src/test/java/com/yourname/expensetracker/data/repository/ExpenseRepositoryDebugAuditTest.kt
```

Use existing test conventions if different.

---

## Step A1 — Add lifecycle event type

Modify:

```text
LifecycleEventType.kt
```

Add:

```kotlin
DEBUG_DELETE_ALL_EXPENSES
```

Keep existing:

```kotlin
RESTORED_FROM_DEBUG_SNAPSHOT
```

Expected enum after change should include at least:

```kotlin
enum class LifecycleEventType {
    CREATE_ATTEMPTED,
    CREATED,
    CREATE_VALIDATION_FAILED,
    CREATE_DUPLICATE_SKIPPED,
    CREATE_INSERT_CONFLICT,
    UPDATED,
    BULK_UPDATED,
    DELETED,
    DEBUG_DELETE_ALL_EXPENSES,
    RESTORED_FROM_DEBUG_SNAPSHOT,
    SOURCE_LINKED,
    SIDE_EFFECT_FAILED
}
```

No migration should be required because `eventType` is stored as `String`.

---

## Step A2 — Add count helper to `ExpenseDao`

Add:

```kotlin
@Query("SELECT COUNT(*) FROM expenses")
suspend fun countAllExpenses(): Int
```

Do not reuse `observeExpenseMutationClock()` because that is a `Flow`.

---

## Step A3 — Create `DebugExpenseAuditWriter`

Create:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/DebugExpenseAuditWriter.kt
```

Recommended implementation shape:

```kotlin
package com.yourname.expensetracker.domain.transaction.lifecycle

import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugExpenseAuditWriter @Inject constructor(
    private val transactionEventDao: TransactionEventDao,
    private val diagnosticEventWriter: DiagnosticEventWriter,
    private val timeProvider: TimeProvider
) {
    suspend fun writeDeleteAllEvent(
        affectedCount: Int,
        correlationId: String?
    ) {
        transactionEventDao.insert(
            TransactionEvent(
                expenseId = null,
                eventType = LifecycleEventType.DEBUG_DELETE_ALL_EXPENSES.name,
                source = DEBUG_SOURCE,
                actor = DEBUG_ACTOR,
                occurredAt = timeProvider.now(),
                dedupeKey = null,
                duplicateExpenseId = null,
                beforeSnapshot = null,
                afterSnapshot = null,
                metadata = JSONObject()
                    .put("operation", "deleteAllExpenses")
                    .put("aggregate", true)
                    .put("affectedCount", affectedCount)
                    .put("debugOnly", true)
                    .toString(),
                reason = "Debug delete all expenses",
                correlationId = correlationId,
                causationId = null
            )
        )
    }

    suspend fun writeRestoreSnapshotEvent(
        beforeCount: Int,
        restoredCount: Int,
        correlationId: String?
    ) {
        transactionEventDao.insert(
            TransactionEvent(
                expenseId = null,
                eventType = LifecycleEventType.RESTORED_FROM_DEBUG_SNAPSHOT.name,
                source = DEBUG_SOURCE,
                actor = DEBUG_ACTOR,
                occurredAt = timeProvider.now(),
                dedupeKey = null,
                duplicateExpenseId = null,
                beforeSnapshot = null,
                afterSnapshot = null,
                metadata = JSONObject()
                    .put("operation", "restoreDebugSnapshot")
                    .put("aggregate", true)
                    .put("beforeCount", beforeCount)
                    .put("restoredCount", restoredCount)
                    .put("debugOnly", true)
                    .toString(),
                reason = "Restored expenses from debug snapshot",
                correlationId = correlationId,
                causationId = null
            )
        )
    }

    suspend fun emitSnapshotCreatedDiagnosticBestEffort(
        snapshotCount: Int,
        correlationId: String?
    ) {
        try {
            diagnosticEventWriter.emit(
                DiagnosticEvent(
                    pipeline = AppPipeline.TRANSACTION,
                    stage = "DEBUG_EXPENSE_SNAPSHOT_CREATED",
                    outcome = EventOutcome.COMPLETED,
                    severity = EventSeverity.DEBUG,
                    entityType = "Expense",
                    entityId = null,
                    sourceType = DEBUG_SOURCE,
                    correlationId = correlationId
                        ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId(),
                    metadata = SafeEventMetadata.builder()
                        .put("operation", "createDebugSnapshot")
                        .put("snapshotCount", snapshotCount)
                        .put("aggregate", true)
                        .put("debugOnly", true)
                        .build(),
                    isTerminal = true
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to emit debug snapshot diagnostic")
        }
    }

    companion object {
        const val DEBUG_SOURCE = "DEBUG_EXPENSE_MAINTENANCE"
        const val DEBUG_ACTOR = "system:debug"
    }
}
```

Important rules:

- `writeDeleteAllEvent()` and `writeRestoreSnapshotEvent()` are strict.
  - If they fail, the enclosing debug mutation should roll back.
- `emitSnapshotCreatedDiagnosticBestEffort()` is best-effort.
  - Snapshot generation should not fail only because diagnostics failed.
- Metadata must be aggregate only.
  - Do not include raw expense rows.
  - Do not include merchant names.
  - Do not include notes.
  - Do not include raw receipt/source payloads.
  - Do not include full snapshots.

---

## Step A4 — Inject writer into `ExpenseRepository`

Modify constructor:

```kotlin
class ExpenseRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val database: AppDatabase,
    private val expenseDao: ExpenseDao,
    ...
    private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator,
    private val debugExpenseAuditWriter: DebugExpenseAuditWriter
)
```

Use the project’s formatting and dependency injection style.

---

## Step A5 — Add debug guard helper

Inside `ExpenseRepository`:

```kotlin
private fun requireDebugExpenseOperation(operation: String) {
    if (!com.yourname.expensetracker.BuildConfig.DEBUG) {
        throw UnsupportedOperationException("$operation disabled in release")
    }
}
```

Important:

- Call this before `writeBarrier.checkWritesAllowed(...)`.
- Release builds should fail before attempting DB writes or diagnostics.

---

## Step A6 — Update `deleteAllExpenses()`

Current behavior:

```kotlin
writeBarrier.checkWritesAllowed(...)
if (!BuildConfig.DEBUG) throw ...
expenseDao.deleteAll()
```

Replace with:

```kotlin
suspend fun deleteAllExpenses() {
    requireDebugExpenseOperation("deleteAllExpenses")
    writeBarrier.checkWritesAllowed("ExpenseRepository.deleteAllExpenses")

    val correlationId = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()

    database.withTransaction {
        val affectedCount = expenseDao.countAllExpenses()
        expenseDao.deleteAll()

        debugExpenseAuditWriter.writeDeleteAllEvent(
            affectedCount = affectedCount,
            correlationId = correlationId
        )
    }
}
```

Rules:

- Do not write one event per deleted expense.
- The aggregate event must be in the same transaction as `deleteAll()`.
- If audit insertion fails, the delete-all operation must roll back.

---

## Step A7 — Update `restoreDebugSnapshot()`

Current behavior:

```kotlin
writeBarrier.checkWritesAllowed(...)
if (!BuildConfig.DEBUG) throw ...
database.withTransaction {
    expenseDao.deleteAll()
    if (snapshot.expenses.isNotEmpty()) {
        expenseDao.insertAll(snapshot.expenses)
    }
}
```

Replace with:

```kotlin
suspend fun restoreDebugSnapshot(snapshot: DebugExpenseSnapshot) {
    requireDebugExpenseOperation("restoreDebugSnapshot")
    writeBarrier.checkWritesAllowed("ExpenseRepository.restoreDebugSnapshot")

    val correlationId = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()

    database.withTransaction {
        val beforeCount = expenseDao.countAllExpenses()

        expenseDao.deleteAll()

        if (snapshot.expenses.isNotEmpty()) {
            expenseDao.insertAll(snapshot.expenses)
        }

        debugExpenseAuditWriter.writeRestoreSnapshotEvent(
            beforeCount = beforeCount,
            restoredCount = snapshot.expenses.size,
            correlationId = correlationId
        )
    }
}
```

Rules:

- Restore audit event must be atomic with delete + insert.
- If `insertAll()` fails, event rolls back.
- If event insert fails, restore rolls back.
- Do not store full snapshot in event metadata.

---

## Step A8 — Update `createDebugSnapshot()`

Current behavior:

```kotlin
if (!BuildConfig.DEBUG) throw ...
return DebugExpenseSnapshot(expenses = expenseDao.getAllUncapped())
```

Replace with:

```kotlin
suspend fun createDebugSnapshot(): DebugExpenseSnapshot {
    requireDebugExpenseOperation("createDebugSnapshot")

    val correlationId = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()
    val snapshot = DebugExpenseSnapshot(expenses = expenseDao.getAllUncapped())

    debugExpenseAuditWriter.emitSnapshotCreatedDiagnosticBestEffort(
        snapshotCount = snapshot.expenses.size,
        correlationId = correlationId
    )

    return snapshot
}
```

Do **not** write a `TransactionEvent` for snapshot creation unless the project explicitly wants read operations in the transaction lifecycle table.

Recommended policy:

- `transaction_events`: mutating expense lifecycle / aggregate mutation events.
- `pipeline_diagnostic_events`: non-mutating debug snapshot creation.

---

## Step A9 — Tests for PR A

Create/update tests.

Required tests:

```text
delete_all_debug_writes_DEBUG_DELETE_ALL_EXPENSES_event
delete_all_debug_event_has_expenseId_null
delete_all_debug_event_has_affectedCount
delete_all_debug_event_contains_no_raw_expense_data
delete_all_debug_event_is_atomic_with_delete

restore_debug_snapshot_writes_RESTORED_FROM_DEBUG_SNAPSHOT_event
restore_debug_snapshot_event_has_beforeCount_and_restoredCount
restore_debug_snapshot_event_has_expenseId_null
restore_debug_snapshot_event_contains_no_raw_expense_data
restore_debug_snapshot_event_is_atomic_with_delete_insert

create_debug_snapshot_emits_diagnostic_event
create_debug_snapshot_diagnostic_has_snapshotCount
create_debug_snapshot_diagnostic_contains_no_raw_expense_data
create_debug_snapshot_still_returns_snapshot_if_diagnostic_writer_fails

delete_all_blocked_by_writeBarrier
restore_snapshot_blocked_by_writeBarrier
```

If release-build testing is not available, do not fake `BuildConfig.DEBUG` with brittle hacks. Leave release behavior to static/code review or create a small injectable `BuildInfo` abstraction only if the project already has one.

---

# PR B — Restricted `ExpenseDao` mutation access + architecture guard

## Problem

`ExpenseDao` must stay injectable/public for Room, but mutating methods should not be freely callable from arbitrary repositories, workers, ViewModels, or services.

A static grep alone is fragile.

Recommended solution:

1. Add a compile-time restricted opt-in annotation.
2. Annotate all mutating DAO methods.
3. Opt in only approved lifecycle/maintenance/debug methods.
4. Add an architecture test that prevents broad/unauthorized opt-ins.

---

## Files to modify

Primary:

```text
app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
```

New file:

```text
app/src/main/java/com/yourname/expensetracker/data/database/dao/RestrictedExpenseDaoMutation.kt
```

Likely callsites to update:

```text
TransactionLifecycleCoordinator.kt
ExpenseRepository.kt
GroupTransactionCoordinator.kt
ReceiptLinkService.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/architecture/ExpenseDaoMutationAccessTest.kt
```

---

## Step B1 — Create restricted annotation

Create:

```text
app/src/main/java/com/yourname/expensetracker/data/database/dao/RestrictedExpenseDaoMutation.kt
```

Content:

```kotlin
package com.yourname.expensetracker.data.database.dao

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Direct ExpenseDao mutation is restricted. Route through TransactionLifecycleCoordinator or add an explicitly reviewed, write-barrier-protected allowlist exception."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class RestrictedExpenseDaoMutation
```

---

## Step B2 — Annotate every mutating `ExpenseDao` method

In `ExpenseDao.kt`, add `@RestrictedExpenseDaoMutation` to all methods that mutate `expenses`.

Required list:

```kotlin
@RestrictedExpenseDaoMutation
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insert(expense: Expense): Long

@RestrictedExpenseDaoMutation
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertAtomic(expense: Expense): Long

@RestrictedExpenseDaoMutation
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertAll(expenses: List<Expense>)

@RestrictedExpenseDaoMutation
@Update
suspend fun update(expense: Expense)

@RestrictedExpenseDaoMutation
@Query("DELETE FROM expenses")
suspend fun deleteAll()

@RestrictedExpenseDaoMutation
@Delete
suspend fun delete(expense: Expense)
```

Also annotate all `UPDATE` query methods, including:

```text
updateCategory
updateCategoryNullable
updateCategoryForMerchant
updateMerchantForMerchant
updateMerchant
updateMerchantAndKey
updateTransactionType
updateDedupeKey
updateTransferDirection
updateTransferAccountName
updateIsNotMine
updateOwnerName
updateIsSharedExpense
updateSharedWithName
updateMySharePercentage
updateMyShareAmount
clearSharedExpenseFlags
incrementBackfillAttempts
updateLocation
conditionallySetLocation
clearLocation
updateMerchantKey
```

Do not annotate read methods.

Do not annotate `@Query("SELECT changes()")`.

---

## Step B3 — Opt in approved lifecycle owner

In `TransactionLifecycleCoordinator.kt`, add class-level opt-in:

```kotlin
@OptIn(RestrictedExpenseDaoMutation::class)
@Singleton
class TransactionLifecycleCoordinator @Inject constructor(...)
```

Import:

```kotlin
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
```

Rationale:

- This class is the approved lifecycle mutation owner.
- Class-level opt-in is acceptable here.

---

## Step B4 — Opt in approved repository bypasses narrowly

Do **not** add class-level opt-in to `ExpenseRepository`.

Add function-level opt-in only to approved bypass methods.

Approved methods:

```kotlin
@OptIn(RestrictedExpenseDaoMutation::class)
suspend fun deleteAllExpenses() { ... }

@OptIn(RestrictedExpenseDaoMutation::class)
suspend fun restoreDebugSnapshot(snapshot: DebugExpenseSnapshot) { ... }

@OptIn(RestrictedExpenseDaoMutation::class)
suspend fun incrementBackfillAttempts(expenseId: Long) { ... }

@OptIn(RestrictedExpenseDaoMutation::class)
suspend fun conditionallySetLocation(...) { ... }

@OptIn(RestrictedExpenseDaoMutation::class)
suspend fun clearExpenseLocation(expenseId: Long) { ... }

@OptIn(RestrictedExpenseDaoMutation::class)
suspend fun updateMerchantKey(expenseId: Long, merchantKey: String) { ... }
```

Each approved bypass must have a short comment immediately above it:

```kotlin
// EXPENSE_DAO_MUTATION_ALLOWLIST:
// Reason: maintenance/backfill low-value column update.
// Guard: DatabaseWriteBarrier.
// Audit: no transaction event by design to avoid lifecycle noise.
```

For debug methods:

```kotlin
// EXPENSE_DAO_MUTATION_ALLOWLIST:
// Reason: debug-only aggregate destructive operation.
// Guard: BuildConfig.DEBUG + DatabaseWriteBarrier.
// Audit: aggregate TransactionEvent written in same DB transaction.
```

---

## Step B5 — Opt in other approved non-repository bypasses narrowly

Search for compile errors after annotating DAO methods.

For each compile error:

1. Do **not** blindly add `@OptIn`.
2. Classify the callsite:
   - Should it route through `TransactionLifecycleCoordinator`?
   - Is it a maintenance/backfill write?
   - Is it part of an outer atomic coordinator transaction?
   - Is it a debug-only operation?
   - Is it a migration-only path?
3. If not clearly approved, refactor it.

Likely approved callsites from prior review:

### `GroupTransactionCoordinator`

Direct mutation:

```text
expenseDao.clearSharedExpenseFlags(...)
```

Allowed only if:

- it happens inside group delete/cleanup transaction,
- group cleanup writes aggregate event,
- side effects dispatch post-commit.

Use function-level opt-in, not class-level unless necessary.

Comment:

```kotlin
// EXPENSE_DAO_MUTATION_ALLOWLIST:
// Reason: group deletion cleanup of ownership/share flags inside outer group transaction.
// Guard: caller transaction + lifecycle aggregate event.
// Audit: BULK_UPDATED/group cleanup event.
```

### `ReceiptLinkService`

Prior inventory mentioned receipt category propagation may directly update expense category due to dependency-cycle constraints.

If still present:

- keep narrow function-level opt-in,
- ensure comment says why coordinator cannot be used,
- ensure mutation is best-effort and not user-visible lifecycle edit,
- ensure future TODO exists to remove cycle.

Comment:

```kotlin
// EXPENSE_DAO_MUTATION_ALLOWLIST:
// Reason: receipt link category propagation cannot call TransactionLifecycleCoordinator due dependency cycle.
// Guard: caller-controlled receipt/link flow.
// Audit: receipt lifecycle event; no transaction UPDATED event by design.
```

If this call can be routed through coordinator now, route it instead and avoid opt-in.

---

## Step B6 — Forbid broad opt-ins

Do not allow:

```kotlin
@file:OptIn(RestrictedExpenseDaoMutation::class)
```

Do not allow class-level opt-in except for:

```text
TransactionLifecycleCoordinator
```

Possibly allow `DebugExpenseAuditWriter` only if it ever directly mutates `ExpenseDao`; preferred design does not require that.

---

## Step B7 — Add architecture/static guard test

Create:

```text
app/src/test/java/com/yourname/expensetracker/architecture/ExpenseDaoMutationAccessTest.kt
```

Recommended test strategy:

1. Scan `src/main/java`.
2. Fail on file-level opt-in.
3. Fail on broad suppressions.
4. Fail on `@OptIn(RestrictedExpenseDaoMutation::class)` outside allowlisted files.
5. Optionally scan for raw `expenseDao.<mutator>(` calls outside approved files.

Example skeleton:

```kotlin
package com.yourname.expensetracker.architecture

import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

class ExpenseDaoMutationAccessTest {

    private val sourceRoot: Path = Path.of("src/main/java")

    private val allowedOptInFiles = setOf(
        "TransactionLifecycleCoordinator.kt",
        "ExpenseRepository.kt",
        "GroupTransactionCoordinator.kt",
        "ReceiptLinkService.kt"
    )

    private val mutationMethods = setOf(
        "insert",
        "insertAtomic",
        "insertAll",
        "update",
        "delete",
        "deleteAll",
        "updateCategory",
        "updateCategoryNullable",
        "updateCategoryForMerchant",
        "updateMerchantForMerchant",
        "updateMerchant",
        "updateMerchantAndKey",
        "updateTransactionType",
        "updateDedupeKey",
        "updateTransferDirection",
        "updateTransferAccountName",
        "updateIsNotMine",
        "updateOwnerName",
        "updateIsSharedExpense",
        "updateSharedWithName",
        "updateMySharePercentage",
        "updateMyShareAmount",
        "clearSharedExpenseFlags",
        "incrementBackfillAttempts",
        "updateLocation",
        "conditionallySetLocation",
        "clearLocation",
        "updateMerchantKey"
    )

    @Test
    fun no_file_level_restricted_expense_dao_mutation_opt_in() {
        val offenders = kotlinFiles()
            .filter { it.readText().contains("@file:OptIn(RestrictedExpenseDaoMutation::class)") }
            .toList()

        if (offenders.isNotEmpty()) {
            fail("Do not use file-level RestrictedExpenseDaoMutation opt-in:\n${offenders.joinToString("\n")}")
        }
    }

    @Test
    fun no_suppression_of_restricted_expense_dao_mutation_errors() {
        val badSuppressions = listOf(
            "OPT_IN_USAGE_ERROR",
            "OPT_IN_USAGE",
            "EXPERIMENTAL_API_USAGE_ERROR"
        )

        val offenders = kotlinFiles()
            .filter { file ->
                val text = file.readText()
                badSuppressions.any { text.contains(it) } &&
                    text.contains("RestrictedExpenseDaoMutation")
            }
            .toList()

        if (offenders.isNotEmpty()) {
            fail("Do not suppress RestrictedExpenseDaoMutation opt-in errors:\n${offenders.joinToString("\n")}")
        }
    }

    @Test
    fun restricted_expense_dao_mutation_opt_in_only_in_allowlisted_files() {
        val offenders = kotlinFiles()
            .filter { file ->
                val text = file.readText()
                text.contains("RestrictedExpenseDaoMutation::class") &&
                    file.name !in allowedOptInFiles &&
                    file.name != "RestrictedExpenseDaoMutation.kt" &&
                    file.name != "ExpenseDao.kt"
            }
            .toList()

        if (offenders.isNotEmpty()) {
            fail(
                "RestrictedExpenseDaoMutation opt-in found outside allowlisted files. " +
                "Route through TransactionLifecycleCoordinator or update the allowlist with review:\n" +
                offenders.joinToString("\n")
            )
        }
    }

    @Test
    fun no_raw_expenseDao_mutation_calls_outside_approved_files() {
        val methodRegex = mutationMethods.joinToString("|") { Regex.escape(it) }
        val callRegex = Regex("""\bexpenseDao\s*\.\s*($methodRegex)\s*\(""")

        val approvedFiles = allowedOptInFiles + setOf("ExpenseDao.kt")

        val offenders = kotlinFiles()
            .filter { file ->
                file.name !in approvedFiles &&
                    callRegex.containsMatchIn(file.readText())
            }
            .toList()

        if (offenders.isNotEmpty()) {
            fail(
                "Raw ExpenseDao mutation call outside approved files:\n" +
                offenders.joinToString("\n")
            )
        }
    }

    private fun kotlinFiles(): Sequence<Path> =
        Files.walk(sourceRoot)
            .filter { it.extension == "kt" }
}
```

Adjust path handling if project test working directory differs.

Important:

- This test is intentionally conservative.
- If it fails because the source root is different, fix the root path, not the rule.
- If it fails

:warning: The provider stream ended early, so this response may be incomplete.