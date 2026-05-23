# Pipeline 2 implementation plan — P2-NEW-16, P2-NEW-17, P2-NEW-18, P2-NEW-19, P2-NEW-20

Target baseline: `ad91767a9f30db77b6d4b6d8410d788eeaa610c0`

Issues covered:

| ID | Severity | Issue |
|---|---:|---|
| P2-NEW-16 | P1/P2 | Remove/guard legacy `ReceiptRepository.createExpenseFromReceipt()` |
| P2-NEW-17 | P2 | Source-link mapper creates weak `LEGACY_SOURCE_ONLY` fallback |
| P2-NEW-18 | P2/P3 | Debug snapshot generation lacks diagnostic/audit event |
| P2-NEW-19 | P2 | Bulk merchant/category side effects lack changed-field semantics |
| P2-NEW-20 | P2 | Static guard coverage missing for direct `ExpenseDao` mutations |

Recommended PR slicing:

1. **PR A — Remove legacy receipt expense path**
2. **PR B — Source-link fallback policy + callsite provenance audit**
3. **PR C — Debug snapshot/delete/restore audit**
4. **PR D — Bulk changed-field side-effect semantics**
5. **PR E — Restricted `ExpenseDao` mutation guard + architecture tests**

---

# PR A — Remove/guard legacy receipt expense path

## Fixes

- P2-NEW-16

## Current problem

`ReceiptRepository.createExpenseFromReceipt()` still exists. It is deprecated with `DeprecationLevel.ERROR`, but the method body still performs:

```text
create expense -> link receipt -> link receipt items -> classifier learning
```

The expense create and receipt-specific links are not one guaranteed atomic lifecycle unit. The method also suppresses a deprecation error to call an older create API.

## Goal

No production code path can create an expense from a receipt through this legacy method.

Preferred fix: **delete the method** after verifying no production callers exist.

Fallback fix: replace method body with a safe coordinator path that is atomic.

## Files to inspect

```text
app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
```

## Step A1 — Audit callers

Run:

```bash
grep -R "createExpenseFromReceipt" app/src/main/java app/src/test/java app/src/androidTest/java
```

Classify results:

- production caller: must migrate;
- test caller: update/remove;
- method declaration itself: delete or guard.

## Step A2 — Preferred implementation: delete method

If no production callers exist:

1. Delete `ReceiptRepository.createExpenseFromReceipt(...)`.
2. Remove now-unused imports from `ReceiptRepository.kt`, likely including:
   - `CreateExpenseRequest`
   - `CreateExpenseResult`
   - `ExpenseSource` if only used by this method
   - `TransactionType` if only used by this method
   - `PaymentMethod` if only used by this method
3. Remove `runPostCommitSafely(...)` if it becomes unused.
4. Remove `@Suppress("DEPRECATION_ERROR")` associated with the method.

## Step A3 — Fallback implementation if method cannot be deleted yet

If a production caller still exists and cannot be migrated immediately, replace the method with a safe delegation:

```kotlin
@Deprecated(
    message = "Use ReceiptLifecycleCoordinator. Legacy path is forbidden.",
    level = DeprecationLevel.ERROR
)
suspend fun createExpenseFromReceipt(...): Result {
    return receiptLifecycleCoordinator.get().createExpenseFromReviewedReceipt(...)
}
```

Rules for the replacement coordinator method:

```text
database.withTransaction {
  1. validate receipt exists
  2. create expense DB-only via TransactionLifecycleCoordinator.createExpenseDbOnlyV2()
  3. require CreateExpenseResult.Created, otherwise throw rollback signal
  4. link receipt to expense
  5. link receipt item categorizations
  6. write source link / lifecycle event atomically
}
after transaction:
  - run coordinator post-commit actions
  - run classifier-learning side effect best-effort
```

Important: inside `withTransaction`, any failure that must rollback must **throw**, not return an error result.

## Step A4 — Add static guard

Create:

```text
app/src/test/java/com/yourname/expensetracker/architecture/ReceiptLegacyCreatePathGuardTest.kt
```

Test:

```kotlin
class ReceiptLegacyCreatePathGuardTest {
    @Test
    fun no_production_createExpenseFromReceipt_usage() {
        val root = Path.of("src/main/java")
        val offenders = Files.walk(root)
            .filter { it.toString().endsWith(".kt") }
            .filter { file ->
                val text = file.readText()
                text.contains("createExpenseFromReceipt")
            }
            .toList()

        if (offenders.isNotEmpty()) {
            fail(
                "Legacy receipt create path is forbidden. Use ReceiptLifecycleCoordinator:\n" +
                    offenders.joinToString("\n")
            )
        }
    }
}
```

If the method is retained temporarily, allow only `ReceiptRepository.kt` but fail on all callers:

```kotlin
file.fileName.toString() != "ReceiptRepository.kt"
```

## Tests

Required:

```text
grep_createExpenseFromReceipt_has_no_production_callers
legacy_receipt_create_path_architecture_guard_fails_on_new_callsite
receipt_lifecycle_create_link_is_atomic
receipt_link_failure_rolls_back_expense
receipt_item_link_failure_rolls_back_expense_or_has_explicit_policy
receipt_created_expense_has_scannedReceipt_source_link
```

## Acceptance criteria

- No production caller uses `createExpenseFromReceipt`.
- Preferably, method is deleted.
- If temporarily retained, it delegates to atomic receipt lifecycle coordinator only.
- No `@Suppress("DEPRECATION_ERROR")` remains for this path.
- Static guard prevents reintroduction.

---

# PR B — Source-link fallback policy + callsite provenance audit

## Fixes

- P2-NEW-17

## Current problem

`CreateExpenseSourceLinkMapper.fromRequest()` creates a `LEGACY_SOURCE_ONLY` link whenever no explicit/source-specific fields exist. That is acceptable for migrations/backfills, but weak for real runtime flows.

Runtime callsites should pass concrete provenance, e.g.:

```text
review -> pendingReviewId
receipt -> scannedReceiptId
email -> emailReceiptSourceId
group -> groupId
bank -> bankSyncRunId/provider hash/account hash
CSV/import -> fileImportRunId or csvImportBatchId + csvRowNumber
notification -> rawNotificationId
```

## Goal

Make legacy fallback explicit, not automatic.

Runtime source-specific creates should either:

1. provide concrete source link fields, or
2. intentionally create no source link for manual/no-external-source flows.

## Files to modify

```text
domain/transaction/CreateExpenseRequest.kt
domain/provenance/CreateExpenseSourceLinkMapper.kt
domain/provenance/SourceEntityType.kt
domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
```

Callsites to audit:

```bash
grep -R "CreateExpenseRequest(" app/src/main/java
```

## Step B1 — Add fallback policy enum

Create:

```text
app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkFallbackPolicy.kt
```

```kotlin
package com.yourname.expensetracker.domain.provenance

enum class SourceLinkFallbackPolicy {
    NONE,
    LEGACY_BACKFILL_ONLY
}
```

## Step B2 — Add field to `CreateExpenseRequest`

In `CreateExpenseRequest.kt`:

```kotlin
val sourceLinkFallbackPolicy: SourceLinkFallbackPolicy = SourceLinkFallbackPolicy.NONE
```

Default must be `NONE`.

Add import:

```kotlin
import com.yourname.expensetracker.domain.provenance.SourceLinkFallbackPolicy
```

## Step B3 — Change mapper fallback

In `CreateExpenseSourceLinkMapper.fromRequest()` replace unconditional fallback:

```kotlin
if (payloads.isEmpty()) {
    payloads.add(
        SourceLinkPayload(
            sourceType = request.source.name,
            sourceEntityType = SourceEntityType.LEGACY_SOURCE_ONLY,
            role = SourceLinkRole.LEGACY_BACKFILL,
            status = SourceLinkStatus.LEGACY_PARTIAL
        )
    )
}
```

with:

```kotlin
if (
    payloads.isEmpty() &&
    request.sourceLinkFallbackPolicy == SourceLinkFallbackPolicy.LEGACY_BACKFILL_ONLY
) {
    payloads.add(
        SourceLinkPayload(
            sourceType = request.source.name,
            sourceEntityType = SourceEntityType.LEGACY_SOURCE_ONLY,
            role = SourceLinkRole.LEGACY_BACKFILL,
            status = SourceLinkStatus.LEGACY_PARTIAL
        )
    )
}
```

Do not create legacy fallback for normal runtime creates.

## Step B4 — Add provenance validation for source-specific flows

Create:

```text
domain/provenance/CreateExpenseSourceLinkRequirements.kt
```

Suggested implementation:

```kotlin
object CreateExpenseSourceLinkRequirements {
    fun missingRequirements(request: CreateExpenseRequest): List<String> {
        if (request.sourceLinks.isNotEmpty()) return emptyList()

        return when (request.source) {
            ExpenseSource.REVIEW_APPROVAL ->
                if (request.pendingReviewId == null) listOf("pendingReviewId") else emptyList()

            ExpenseSource.RECEIPT_SCAN ->
                if (request.scannedReceiptId == null) listOf("scannedReceiptId") else emptyList()

            ExpenseSource.GROUP_EXPENSE ->
                if (request.groupId == null) listOf("groupId") else emptyList()

            ExpenseSource.BANK_SYNC ->
                if (request.bankSyncRunId == null) listOf("bankSyncRunId") else emptyList()

            ExpenseSource.CSV_IMPORT ->
                if (request.csvImportBatchId == null || request.csvRowNumber == null)
                    listOf("csvImportBatchId", "csvRowNumber")
                else emptyList()

            ExpenseSource.EMAIL_RECEIPT ->
                if (request.emailReceiptSourceId == null) listOf("emailReceiptSourceId") else emptyList()

            ExpenseSource.NOTIFICATION ->
                if (request.rawNotificationId == null) listOf("rawNotificationId") else emptyList()

            else -> emptyList()
        }
    }
}
```

Adjust enum names to the real `ExpenseSource` values.

## Step B5 — Enforce in coordinator create validation

In create validation path:

```kotlin
val missingSourceFields =
    CreateExpenseSourceLinkRequirements.missingRequirements(request)

if (missingSourceFields.isNotEmpty()) {
    errors += "Missing source provenance fields for ${request.source}: ${missingSourceFields.joinToString(",")}"
}
```

Exception:

- if `request.sourceLinkFallbackPolicy == LEGACY_BACKFILL_ONLY`, do not fail. This is explicitly legacy/migration.

## Step B6 — Audit and fix callsites

Run:

```bash
grep -R "CreateExpenseRequest(" app/src/main/java
```

For each callsite:

| Source | Required fix |
|---|---|
| `REVIEW_APPROVAL` | pass `pendingReviewId`; also pass `scannedReceiptId`/`rawNotificationId` if known |
| `RECEIPT_SCAN` | pass `scannedReceiptId` |
| `GROUP_EXPENSE` | pass `groupId` |
| `BANK_SYNC` | pass `bankSyncRunId`, provider/account hashes where available |
| `CSV_IMPORT` | pass `csvImportBatchId` + `csvRowNumber` |
| `EMAIL_RECEIPT` | pass `emailReceiptSourceId` |
| `NOTIFICATION` | pass `rawNotificationId` |
| manual UI create | no source link required; no legacy fallback |

## Step B7 — Static guard for legacy fallback

Create architecture test:

```text
SourceLinkFallbackPolicyGuardTest.kt
```

Rules:

```text
- SourceLinkFallbackPolicy.LEGACY_BACKFILL_ONLY may appear only in:
  - migration/backfill code
  - debug data import
  - explicit provenance tests
- Normal repositories/workers/services must not set it.
```

## Tests

Required:

```text
mapper_no_payloads_default_policy_returns_empty_list
mapper_no_payloads_legacy_policy_returns_LEGACY_SOURCE_ONLY
review_approval_missing_pendingReviewId_validation_fails
receipt_scan_missing_scannedReceiptId_validation_fails
group_expense_missing_groupId_validation_fails
bank_sync_missing_bankSyncRunId_validation_fails
csv_import_missing_batch_or_row_validation_fails
manual_entry_without_source_link_is_allowed
runtime_create_does_not_write_LEGACY_SOURCE_ONLY
legacy_backfill_policy_writes_LEGACY_SOURCE_ONLY
```

## Acceptance criteria

- `LEGACY_SOURCE_ONLY` is never created by default.
- Legacy fallback is explicit via policy.
- Runtime source-specific creates must provide concrete provenance.
- All callsites are audited.
- Static guard prevents accidental fallback use.

---

# PR C — Debug snapshot/delete/restore audit

## Fixes

- P2-NEW-18

## Current problem

`ExpenseRepository` currently has debug methods:

```text
deleteAllExpenses()
createDebugSnapshot()
restoreDebugSnapshot()
```

They are `BuildConfig.DEBUG` guarded, and mutating methods use `DatabaseWriteBarrier`, but:

- `deleteAllExpenses()` writes no aggregate event,
- `restoreDebugSnapshot()` writes no aggregate event,
- `createDebugSnapshot()` emits no diagnostic.

## Goal

Debug actions are visible without leaking raw expense data.

Policy:

```text
transaction_events:
  - aggregate mutating debug actions only

diagnostic_events:
  - read-only debug snapshot generation
```

## Files to modify

```text
data/repository/ExpenseRepository.kt
data/database/dao/ExpenseDao.kt
domain/transaction/LifecycleEventType.kt
```

New file:

```text
domain/transaction/lifecycle/DebugExpenseAuditWriter.kt
```

## Step C1 — Add event type

In `LifecycleEventType.kt` add:

```kotlin
DEBUG_DELETE_ALL_EXPENSES
RESTORED_FROM_DEBUG_SNAPSHOT // already exists; keep/use it
```

If `RESTORED_FROM_DEBUG_SNAPSHOT` already exists, do not duplicate.

## Step C2 — Add DAO count helper

In `ExpenseDao.kt`:

```kotlin
@Query("SELECT COUNT(*) FROM expenses")
suspend fun countAllExpenses(): Int
```

## Step C3 — Create audit writer

Create `DebugExpenseAuditWriter.kt`.

Responsibilities:

```kotlin
@Singleton
class DebugExpenseAuditWriter @Inject constructor(
    private val transactionEventDao: TransactionEventDao,
    private val diagnosticEventWriter: DiagnosticEventWriter,
    private val timeProvider: TimeProvider
) {
    suspend fun writeDeleteAllEvent(affectedCount: Int, correlationId: String)
    suspend fun writeRestoreSnapshotEvent(beforeCount: Int, restoredCount: Int, correlationId: String)
    suspend fun emitSnapshotCreatedDiagnosticBestEffort(snapshotCount: Int, correlationId: String)
}
```

Metadata rules:

Allowed:

```text
operation
affectedCount
beforeCount
restoredCount
debugOnly = true
aggregate = true
```

Forbidden:

```text
merchant
notes
raw receipt text
full snapshot rows
addresses
provider payloads
external fingerprints
```

## Step C4 — Update `deleteAllExpenses()`

Replace direct delete with:

```kotlin
suspend fun deleteAllExpenses() {
    requireDebugExpenseOperation("deleteAllExpenses")
    writeBarrier.checkWritesAllowed("ExpenseRepository.deleteAllExpenses")

    val correlationId = CorrelationIds.newId()

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

- event inside same transaction;
- no per-expense event spam;
- audit failure rolls back delete.

## Step C5 — Update `restoreDebugSnapshot()`

```kotlin
suspend fun restoreDebugSnapshot(snapshot: DebugExpenseSnapshot) {
    requireDebugExpenseOperation("restoreDebugSnapshot")
    writeBarrier.checkWritesAllowed("ExpenseRepository.restoreDebugSnapshot")

    val correlationId = CorrelationIds.newId()

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

## Step C6 — Update `createDebugSnapshot()`

```kotlin
suspend fun createDebugSnapshot(): DebugExpenseSnapshot {
    requireDebugExpenseOperation("createDebugSnapshot")

    val correlationId = CorrelationIds.newId()
    val snapshot = DebugExpenseSnapshot(expenses = expenseDao.getAllUncapped())

    debugExpenseAuditWriter.emitSnapshotCreatedDiagnosticBestEffort(
        snapshotCount = snapshot.expenses.size,
        correlationId = correlationId
    )

    return snapshot
}
```

Diagnostic failure must not prevent snapshot creation, except cancellation.

## Tests

Required:

```text
create_debug_snapshot_emits_diagnostic
create_debug_snapshot_diagnostic_has_snapshotCount
create_debug_snapshot_diagnostic_contains_no_raw_expense_data
create_debug_snapshot_returns_snapshot_when_diagnostic_writer_fails

delete_all_debug_writes_DEBUG_DELETE_ALL_EXPENSES_event
delete_all_debug_event_has_expenseId_null
delete_all_debug_event_has_affectedCount
delete_all_debug_event_is_atomic_with_delete

restore_debug_snapshot_writes_RESTORED_FROM_DEBUG_SNAPSHOT_event
restore_debug_snapshot_event_has_beforeCount_and_restoredCount
restore_debug_snapshot_event_is_atomic_with_restore

debug_delete_all_blocked_by_writeBarrier
debug_restore_snapshot_blocked_by_writeBarrier
```

## Acceptance criteria

- Snapshot generation emits diagnostic.
- Delete-all writes aggregate transaction event.
- Restore writes aggregate transaction event.
- Mutating debug audit is atomic with mutation.
- No raw snapshot data is persisted in audit/diagnostics.

---

# PR D — Bulk changed-field side-effect semantics

## Fixes

- P2-NEW-19

## Current problem

`TransactionSideEffectPlanner.planBulkUpdated()` only receives:

```text
source
affectedCount
correlationId
```

and currently plans only a bulk budget check.

Without changed fields, planner cannot know whether to invalidate:

- anomaly signals,
- analytics/dashboard caches,
- merchant/category learning,
- recurring matching.

## Goal

Bulk updates should declare what changed.

Planner should choose targeted aggregate post-commit actions.

## Files to modify

```text
domain/transaction/lifecycle/TransactionSideEffectPlanner.kt
domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt
domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
data/database/GroupTransactionCoordinator.kt
```

New file:

```text
domain/transaction/lifecycle/BulkChangedField.kt
```

## Step D1 — Add changed-field enum

```kotlin
enum class BulkChangedField {
    AMOUNT,
    AMOUNT_EFFECTIVE,
    CATEGORY,
    MERCHANT,
    MERCHANT_KEY,
    TRANSACTION_TYPE,
    DATE,
    CURRENCY,
    OWNERSHIP,
    TRANSFER,
    LOCATION,
    BUSINESS_FLAGS,
    UNKNOWN
}
```

Add helpers:

```kotlin
fun Set<BulkChangedField>.affectsBudget(): Boolean =
    isEmpty() || any {
        it in setOf(
            AMOUNT, AMOUNT_EFFECTIVE, CATEGORY, TRANSACTION_TYPE,
            DATE, CURRENCY, OWNERSHIP, TRANSFER, UNKNOWN
        )
    }

fun Set<BulkChangedField>.affectsAnomaly(): Boolean =
    isEmpty() || any { it != LOCATION }

fun Set<BulkChangedField>.affectsMerchantLearning(): Boolean =
    any { it in setOf(CATEGORY, MERCHANT, MERCHANT_KEY, UNKNOWN) }

fun Set<BulkChangedField>.affectsRecurring(): Boolean =
    any { it in setOf(AMOUNT, MERCHANT, MERCHANT_KEY, TRANSACTION_TYPE, DATE, CURRENCY, UNKNOWN) }

fun Set<BulkChangedField>.affectsAnalyticsCache(): Boolean =
    isEmpty() || any { it != LOCATION }
```

## Step D2 — Update planner signature

Change:

```kotlin
fun planBulkUpdated(source: String, affectedCount: Int, correlationId: String?)
```

to:

```kotlin
fun planBulkUpdated(
    source: String,
    affectedCount: Int,
    correlationId: String?,
    changedFields: Set<BulkChangedField> = setOf(BulkChangedField.UNKNOWN)
): PostCommitActionBatch
```

## Step D3 — Add targeted bulk actions

Required actions:

```text
bulk_budget_check
bulk_anomaly_invalidation
bulk_analytics_cache_invalidation
bulk_merchant_category_dirty
bulk_merchant_canonical_stats_dirty
bulk_recurring_reconciliation
```

If real APIs do not exist yet, add small interfaces:

```kotlin
interface AnalyticsCacheInvalidator {
    suspend fun invalidateForExpenseBulkMutation(
        source: String,
        affectedCount: Int,
        changedFields: Set<String>
    )
}
```

Provide no-op implementation if no cache exists.

For merchant learning, prefer repository methods:

```kotlin
suspend fun markPatternsDirty(reason: String)
suspend fun markCanonicalStatsDirty(reason: String)
```

If not possible, create skipped action with TODO, but planner must expose the changed-field decision.

## Step D4 — Build action list conditionally

Pseudo:

```kotlin
val fields = changedFields.ifEmpty { setOf(BulkChangedField.UNKNOWN) }
val actions = mutableListOf<PostCommitAction>()

if (fields.affectsBudget()) actions += makeBulkBudgetCheckAction(...)
if (fields.affectsAnomaly()) actions += makeBulkAnomalyInvalidationAction(...)
if (fields.affectsMerchantLearning()) {
    actions += makeBulkMerchantCategoryDirtyAction(...)
    actions += makeBulkMerchantCanonicalStatsDirtyAction(...)
}
if (fields.affectsAnalyticsCache()) actions += makeBulkAnalyticsCacheInvalidationAction(...)
if (fields.affectsRecurring()) actions += makeBulkRecurringReconciliationAction(...)

return PostCommitActionBatch(corrId, actions)
```

Every action metadata must include:

```text
source
affectedCount
changedFields
```

## Step D5 — Update callsites

Examples:

Category update:

```kotlin
changedFields = setOf(BulkChangedField.CATEGORY)
```

Merchant update:

```kotlin
changedFields = setOf(BulkChangedField.MERCHANT, BulkChangedField.MERCHANT_KEY)
```

Group hard-delete shared flag cleanup:

```kotlin
changedFields = setOf(BulkChangedField.OWNERSHIP, BulkChangedField.AMOUNT_EFFECTIVE)
```

Unknown legacy callsites:

```kotlin
changedFields = setOf(BulkChangedField.UNKNOWN)
```

## Tests

Required:

```text
planBulkUpdated_zero_count_returns_empty_or_skipped_batch
planBulkUpdated_category_includes_budget_anomaly_cache_merchant_learning
planBulkUpdated_merchant_includes_anomaly_cache_merchant_learning_recurring
planBulkUpdated_ownership_includes_budget_anomaly_cache
planBulkUpdated_location_only_skips_budget_and_recurring
planBulkUpdated_unknown_includes_all_global_invalidations
bulk_action_metadata_contains_changedFields
bulk_action_metadata_contains_no_raw_merchant_or_payload
bulk_category_update_passes_CATEGORY
bulk_merchant_update_passes_MERCHANT_and_MERCHANT_KEY
group_cleanup_passes_OWNERSHIP_and_AMOUNT_EFFECTIVE
```

## Acceptance criteria

- `planBulkUpdated()` accepts changed fields.
- Bulk callsites pass meaningful fields.
- Planner no longer only does budget check.
- Actions are aggregate, not N-per-expense.
- Metadata is privacy-safe.

---

# PR E — Restricted `ExpenseDao` mutation guard + architecture tests

## Fixes

- P2-NEW-20

## Current problem

`ExpenseDao` mutation methods are public Room interface methods. Existing comments say writes should route through lifecycle coordinator, but there is no compile/static guard.

## Goal

Direct expense mutations are compile-restricted and architecture-tested.

## Files to add

```text
data/database/dao/RestrictedExpenseDaoMutation.kt
app/src/test/java/com/yourname/expensetracker/architecture/ExpenseDaoMutationAccessTest.kt
```

## Step E1 — Add restricted opt-in annotation

```kotlin
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Direct ExpenseDao mutation is restricted. Route through TransactionLifecycleCoordinator or add a reviewed write-barrier-protected allowlist exception."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class RestrictedExpenseDaoMutation
```

## Step E2 — Annotate all mutating DAO methods

In `ExpenseDao.kt`, annotate:

```text
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

Also annotate any new bulk mutation method, e.g.:

```text
updateCategoryForCategory
```

Do not annotate read methods.

## Step E3 — Opt in approved owner

In `TransactionLifecycleCoordinator.kt`:

```kotlin
@OptIn(RestrictedExpenseDaoMutation::class)
class TransactionLifecycleCoordinator ...
```

This is the only acceptable class-level opt-in.

## Step E4 — Opt in bypasses narrowly

Do not add class-level opt-in to repositories.

Use function-level opt-in for approved methods only:

```kotlin
@OptIn(RestrictedExpenseDaoMutation::class)
suspend fun deleteAllExpenses() { ... }
```

Every bypass must have a comment:

```kotlin
// EXPENSE_DAO_MUTATION_ALLOWLIST:
// Reason: debug-only aggregate destructive operation.
// Guard: BuildConfig.DEBUG + DatabaseWriteBarrier.
// Audit: aggregate TransactionEvent in same transaction.
```

For maintenance writes:

```kotlin
// EXPENSE_DAO_MUTATION_ALLOWLIST:
// Reason: maintenance/backfill low-risk column update.
// Guard: DatabaseWriteBarrier.
// Audit: no lifecycle event by design.
```

Likely allowlisted files:

```text
TransactionLifecycleCoordinator.kt
ExpenseRepository.kt
GroupTransactionCoordinator.kt
ReceiptLinkService.kt // only if still unavoidable
migration/backfill classes
```

## Step E5 — Add architecture test

Create `ExpenseDaoMutationAccessTest.kt`.

Rules:

1. No file-level opt-in.
2. No suppression of opt-in errors.
3. `RestrictedExpenseDaoMutation::class` only appears in allowlisted files.
4. Raw `expenseDao.<mutator>(` calls outside approved files fail.
5. Every mutating DAO method has `@RestrictedExpenseDaoMutation`.

Skeleton:

```kotlin
class ExpenseDaoMutationAccessTest {
    private val sourceRoot = Path.of("src/main/java")

    private val allowedOptInFiles = setOf(
        "TransactionLifecycleCoordinator.kt",
        "ExpenseRepository.kt",
        "GroupTransactionCoordinator.kt",
        "ReceiptLinkService.kt"
    )

    private val mutationMethods = setOf(
        "insert", "insertAtomic", "insertAll", "update", "delete", "deleteAll",
        "updateCategory", "updateCategoryNullable", "updateCategoryForMerchant",
        "updateMerchantForMerchant", "updateMerchant", "updateMerchantAndKey",
        "updateTransactionType", "updateDedupeKey", "updateTransferDirection",
        "updateTransferAccountName", "updateIsNotMine", "updateOwnerName",
        "updateIsSharedExpense", "updateSharedWithName",
        "updateMySharePercentage", "updateMyShareAmount",
        "clearSharedExpenseFlags", "incrementBackfillAttempts",
        "updateLocation", "conditionallySetLocation", "clearLocation",
        "updateMerchantKey", "updateCategoryForCategory"
    )

    @Test
    fun no_file_level_opt_in() { ... }

    @Test
    fun opt_in_only_in_allowlisted_files() { ... }

    @Test
    fun no_raw_expenseDao_mutation_calls_outside_approved_files() { ... }

    @Test
    fun every_mutating_expense_dao_method_is_restricted() { ... }
}
```

## Step E6 — Fix compile errors intentionally

After annotating DAO methods, compile.

For each failure:

1. Prefer routing through `TransactionLifecycleCoordinator`.
2. If bypass is legitimate, add function-level opt-in + allowlist comment + write barrier if mutating expenses during normal runtime.
3. If debug-only, ensure `BuildConfig.DEBUG` and aggregate audit.
4. If migration-only, keep under migration package and document.

## Tests

Required:

```text
compile_fails_without_opt_in_for_expenseDao_mutation
architecture_test_blocks_file_level_opt_in
architecture_test_blocks_unapproved_raw_expenseDao_update
architecture_test_allows_transaction_lifecycle_coordinator
architecture_test_allows_documented_debug_methods
architecture_test_requires_mutating_dao_methods_to_be_annotated
```

## Acceptance criteria

- Mutating DAO methods require explicit opt-in.
- Only approved files/functions opt in.
- Architecture test blocks future unapproved direct mutations.
- Approved bypasses are documented and guarded.
- New DAO mutation methods must be annotated.

---

# Recommended execution order

1. **PR A — Remove receipt legacy create path**
2. **PR B — Source-link fallback policy**
3. **PR C — Debug snapshot/delete/restore audit**
4. **PR D — Bulk changed-field side effects**
5. **PR E — DAO mutation static guard**

Rationale:

- PR A removes a high-risk non-atomic path.
- PR B prevents weak provenance from continuing.
- PR C makes debug operations auditable before static guards tighten.
- PR D improves side-effect correctness.
- PR E should land last because it may require allowlisting changes from PR C/D.

---

# Global validation commands

Run after every PR:

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
```

Targeted:

```bash
./gradlew testDebugUnitTest --tests '*ReceiptLegacyCreatePathGuardTest*'
./gradlew testDebugUnitTest --tests '*SourceLink*'
./gradlew testDebugUnitTest --tests '*DebugExpenseAudit*'
./gradlew testDebugUnitTest --tests '*TransactionSideEffectPlanner*'
./gradlew testDebugUnitTest --tests '*ExpenseDaoMutationAccessTest*'
```

Manual grep checks:

```bash
grep -R "createExpenseFromReceipt" app/src/main/java
grep -R "LEGACY_SOURCE_ONLY" app/src/main/java
grep -R "sourceLinkFallbackPolicy = SourceLinkFallbackPolicy.LEGACY_BACKFILL_ONLY" app/src/main/java
grep -R "createDebugSnapshot" app/src/main/java
grep -R "planBulkUpdated(" app/src/main/java
grep -R "RestrictedExpenseDaoMutation" app/src/main/java
grep -R "@file:OptIn(RestrictedExpenseDaoMutation::class)" app/src/main/java
```

Expected final state:

- no production legacy receipt create path,
- legacy source fallback only in migration/debug/backfill,
- debug snapshot generation emits diagnostic,
- debug delete/restore write aggregate audit events,
- bulk side effects use changed-field semantics,
- ExpenseDao mutations are compile/static guarded.

---

# Sources checked

- Commit: https://github.com/panospao7/Cost-agregator/commit/ad91767a9f30db77b6d4b6d8410d788eeaa610c0
- `ReceiptRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt
- `CreateExpenseSourceLinkMapper.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/provenance/CreateExpenseSourceLinkMapper.kt
- `ExpenseRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt
- `TransactionSideEffectPlanner.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt
- `ExpenseDao.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt