# Pipeline 2 finalization plan — P2-NEW-13 to P2-NEW-20

Baseline: post-`1e24ddd` review state.

Issues:

| Issue | Status |
|---|---:|
| P2-NEW-13 group lifecycle coordinator | Open |
| P2-NEW-14 side-effect failed transaction event | Open/unproven |
| P2-NEW-15 manual persisted hook | Fixed, needs regression tests |
| P2-NEW-16 receipt legacy path | Open/blocker |
| P2-NEW-17 source-link fallback | Mostly fixed |
| P2-NEW-18 debug snapshot diagnostic | Mostly fixed |
| P2-NEW-19 bulk changed fields | Partial |
| P2-NEW-20 static guard coverage | Partial |

Recommended PR order:

1. PR 1 — Remove/guard legacy receipt create path.
2. PR 2 — Group lifecycle coordinator.
3. PR 3 — Side-effect failure transaction-event mirror.
4. PR 4 — Source-link fallback finalization + callsite audit.
5. PR 5 — Debug snapshot/delete/restore audit closeout.
6. PR 6 — Bulk changed-field side-effect completion.
7. PR 7 — Static guard hardening.
8. PR 8 — Manual persisted-hook regression + final golden pass.

---

# PR 1 — Remove/guard legacy receipt create path

## Fixes

- P2-NEW-16
- also protects P2-NEW-17 from weak receipt provenance regressions

## Problem

`ReceiptRepository.createExpenseFromReceipt()` still exists. It is deprecated, but the method body still represents a risky legacy path:

```text
create expense -> link receipt -> item categorization
```

If this path is reachable, it can leave non-atomic receipt/expense state or bypass source-link requirements.

## Goal

No production code can create receipt expenses through this legacy method.

Preferred: delete the method.

Fallback: replace body with atomic `ReceiptLifecycleCoordinator` delegation.

## Files

Inspect/modify:

```text
app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
```

Add test:

```text
app/src/test/java/com/yourname/expensetracker/architecture/ReceiptLegacyCreatePathGuardTest.kt
```

## Step 1 — Audit callers

Run:

```bash
grep -R "createExpenseFromReceipt" app/src/main/java app/src/test/java app/src/androidTest/java
```

Classify:

```text
method declaration only -> delete method
production caller -> migrate caller first
test caller -> update/remove test
```

## Step 2 — Preferred implementation: delete method

If no production caller exists:

1. Delete `ReceiptRepository.createExpenseFromReceipt(...)`.
2. Remove any `@Suppress("DEPRECATION_ERROR")` used only by this path.
3. Remove unused imports:
   - `CreateExpenseRequest`
   - `CreateExpenseResult`
   - `ExpenseSource`
   - `TransactionType`
   - `PaymentMethod`
   - anything now unused.

## Step 3 — Fallback implementation if deletion is too disruptive

If a production caller still exists and cannot be migrated in the same PR, keep the signature temporarily but make it a hard forbidden wrapper:

```kotlin
@Deprecated(
    message = "Use ReceiptLifecycleCoordinator. Legacy receipt expense creation is forbidden.",
    level = DeprecationLevel.ERROR
)
suspend fun createExpenseFromReceipt(...): ResultType {
    return receiptLifecycleCoordinator.createExpenseFromReviewedReceipt(...)
}
```

The delegated coordinator must be atomic:

```kotlin
database.withTransaction {
    1. validate receipt exists
    2. create expense DB-only via createExpenseDbOnlyV2()
    3. require CreateExpenseResult.Created, otherwise throw rollback exception
    4. link receipt to expense
    5. link/categorize receipt items
    6. write source link / lifecycle events
}
after commit:
    run post-commit actions
    run classifier learning best-effort
```

Important:

```text
Inside withTransaction, rollback-required failures must throw, not return error.
```

## Step 4 — Add architecture guard

Create `ReceiptLegacyCreatePathGuardTest.kt`:

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

If method must temporarily remain, allow only declaration file:

```kotlin
file.fileName.toString() != "ReceiptRepository.kt"
```

but prefer full deletion.

## Required tests

```text
grep_createExpenseFromReceipt_has_no_production_callers
legacy_receipt_create_path_architecture_guard_blocks_new_callsite
receipt_lifecycle_create_link_is_atomic
receipt_link_failure_rolls_back_expense
receipt_item_link_failure_rolls_back_expense_or_has_explicit_policy
receipt_created_expense_has_scannedReceipt_source_link
receipt_source_link_failure_rolls_back_expense
```

## Acceptance

- No production `createExpenseFromReceipt`.
- No deprecation suppression for this path.
- Receipt expense creation uses lifecycle coordinator only.
- Receipt source link is concrete and atomic.
- Static guard prevents reintroduction.

---

# PR 2 — Group lifecycle coordinator

## Fixes

- P2-NEW-13

## Problem

Permanent group delete still lives in low-level `GroupTransactionCoordinator`. It is policy-heavy but currently too close to DAO mechanics.

## Goal

Create `GroupLifecycleCoordinator` that owns group lifecycle policy:

```text
archive
permanent delete confirmation
active group blocking
outstanding balance blocking
current-user membership blocking
lifecycle audit events
```

Low-level `GroupTransactionCoordinator` should remain an atomic transaction helper.

## Files

Add:

```text
app/src/main/java/com/yourname/expensetracker/domain/groups/lifecycle/GroupLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/groups/lifecycle/PermanentGroupDeleteResult.kt
app/src/main/java/com/yourname/expensetracker/domain/groups/lifecycle/DefaultGroupLifecycleCoordinator.kt
```

Modify:

```text
app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupExpenseDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupMemberDao.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt
```

## Step 1 — Add lifecycle result

```kotlin
sealed interface PermanentGroupDeleteResult {
    data class Deleted(
        val groupId: Long,
        val linkedExpenseCount: Int
    ) : PermanentGroupDeleteResult

    data object ConfirmationRequired : PermanentGroupDeleteResult
    data object GroupNotFound : PermanentGroupDeleteResult
    data object GroupStillActive : PermanentGroupDeleteResult

    data class OutstandingBalancesExist(
        val groupId: Long,
        val outstandingCount: Int
    ) : PermanentGroupDeleteResult

    data class CurrentUserMembershipExists(
        val groupId: Long,
        val currentUserCount: Int
    ) : PermanentGroupDeleteResult

    data class Error(
        val message: String,
        val causeClass: String? = null
    ) : PermanentGroupDeleteResult
}
```

If Kotlin does not support `data object`, use plain `object`.

## Step 2 — Add interface

```kotlin
interface GroupLifecycleCoordinator {
    suspend fun archiveGroup(groupId: Long): Boolean

    suspend fun permanentlyDeleteGroup(
        groupId: Long,
        confirmPermanentDelete: Boolean
    ): PermanentGroupDeleteResult
}
```

## Step 3 — Add event types

In `LifecycleEventType.kt`:

```kotlin
GROUP_ARCHIVED
GROUP_PERMANENT_DELETE_ATTEMPTED
GROUP_PERMANENT_DELETE_BLOCKED
GROUP_PERMANENTLY_DELETED
```

## Step 4 — Add DAO validation helpers

In `GroupExpenseDao.kt`, using actual schema fields:

```kotlin
@Query("""
    SELECT COUNT(*)
    FROM group_expenses
    WHERE groupId = :groupId
      AND isReimbursable = 1
      AND settledAt IS NULL
      AND reimbursedAmount < totalAmount
""")
suspend fun countOutstandingReimbursableExpenses(groupId: Long): Int
```

If these columns differ, adapt to real `GroupExpense` entity.

In `GroupMemberDao.kt`:

```kotlin
@Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId AND isCurrentUser = 1")
suspend fun countCurrentUsers(groupId: Long): Int
```

## Step 5 — Implement `DefaultGroupLifecycleCoordinator`

Dependencies:

```kotlin
class DefaultGroupLifecycleCoordinator @Inject constructor(
    private val groupDao: ExpenseGroupDao,
    private val groupExpenseDao: GroupExpenseDao,
    private val memberDao: GroupMemberDao,
    private val lowLevelGroupCoordinator: GroupTransactionCoordinator,
    private val eventWriter: TransactionLifecycleEventWriter,
    private val writeBarrier: DatabaseWriteBarrier,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : GroupLifecycleCoordinator
```

Permanent delete flow:

```text
1. writeBarrier.checkWritesAllowed(...)
2. write GROUP_PERMANENT_DELETE_ATTEMPTED
3. if !confirm -> write blocked event, return ConfirmationRequired
4. load group
5. if missing -> GroupNotFound
6. if active -> blocked event, return GroupStillActive
7. if outstanding balances -> blocked event, return OutstandingBalancesExist
8. if current-user membership -> blocked event, return CurrentUserMembershipExists
9. call lowLevelGroupCoordinator.deleteGroupAtomic(groupId, correlationId)
10. return Deleted
```

Always rethrow `CancellationException`.

## Step 6 — Refactor low-level delete

Change low-level method to:

```kotlin
suspend fun deleteGroupAtomic(
    groupId: Long,
    correlationId: String
): Int
```

Inside same DB transaction:

```text
- collect linked expense IDs
- delete group expenses
- delete members
- delete group
- clear linked expense shared flags
- write GROUP_PERMANENTLY_DELETED
- write BULK_UPDATED if linked expense flags changed
```

Return linked expense count.

Post-commit:

```text
dispatch one bulk side-effect batch
```

## Step 7 — Deprecate direct permanent delete

In domain group transaction interface:

```kotlin
@Deprecated(
    message = "Use GroupLifecycleCoordinator.permanentlyDeleteGroup(groupId, confirmPermanentDelete).",
    level = DeprecationLevel.ERROR
)
suspend fun permanentlyDeleteGroup(groupId: Long): Boolean
```

Add static test blocking new direct use outside lifecycle coordinator/tests.

## Required tests

```text
permanent_delete_without_confirmation_returns_ConfirmationRequired
permanent_delete_without_confirmation_writes_GROUP_PERMANENT_DELETE_BLOCKED
permanent_delete_active_group_returns_GroupStillActive
permanent_delete_outstanding_balances_returns_OutstandingBalancesExist
permanent_delete_current_user_membership_returns_CurrentUserMembershipExists
permanent_delete_missing_group_returns_GroupNotFound
permanent_delete_archived_confirmed_deletes_group_members_and_expenses
permanent_delete_archived_confirmed_clears_linked_expense_shared_flags
permanent_delete_archived_confirmed_writes_GROUP_PERMANENTLY_DELETED
permanent_delete_archived_confirmed_writes_BULK_UPDATED_for_shared_flag_cleanup
permanent_delete_audit_failure_rolls_back_delete
archive_group_writes_GROUP_ARCHIVED
archive_group_preserves_members_and_group_expenses
direct_low_level_permanent_delete_forbidden_by_static_guard
```

## Acceptance

- Group lifecycle policy is no longer hidden in low-level coordinator.
- Hard delete requires explicit confirmation.
- Hard delete validates active/outstanding/current-user constraints.
- Hard delete is audited.
- Shared flag cleanup remains atomic.

---

# PR 3 — Side-effect failure transaction-event mirror

## Fixes

- P2-NEW-14

## Problem

Side-effect failures are durable in generic diagnostics, but `LifecycleEventType.SIDE_EFFECT_FAILED` exists and is not clearly written to `transaction_events`.

## Decision

Adopt this contract:

```text
Generic diagnostics remain canonical for side-effect observability.
Transaction/expense-related side-effect failures are also mirrored to transaction_events as SIDE_EFFECT_FAILED.
```

## Files

Add:

```text
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/TransactionSideEffectFailureEventWriter.kt
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/CompositeSideEffectEventWriter.kt
```

Modify DI binding:

```text
SideEffectEventWriter binding module
```

Possibly modify:

```text
PostCommitActionRunnerImpl.kt
```

only if needed.

## Step 1 — Keep event enum

Verify `LifecycleEventType.kt` includes:

```kotlin
SIDE_EFFECT_FAILED
```

## Step 2 — Add transaction mirror writer

```kotlin
@Singleton
class TransactionSideEffectFailureEventWriter @Inject constructor(
    private val transactionEventWriter: TransactionLifecycleEventWriter
) : SideEffectEventWriter {

    override suspend fun started(action: PostCommitAction) = Unit
    override suspend fun completed(action: PostCommitAction) = Unit
    override suspend fun skipped(action: PostCommitAction, reason: SideEffectSkipReason) = Unit
    override suspend fun cancelled(action: PostCommitAction, reason: String?) = Unit

    override suspend fun failed(
        action: PostCommitAction,
        retryable: Boolean,
        reason: String,
        error: Throwable?
    ) {
        if (!shouldMirror(action)) return

        transactionEventWriter.write(
            TransactionLifecycleEvent(
                expenseId = action.targetEntityId.takeIf {
                    action.targetEntityType.equals("Expense", ignoreCase = true)
                },
                eventType = LifecycleEventType.SIDE_EFFECT_FAILED.name,
                source = action.source,
                actor = "system:post_commit_action_runner",
                correlationId = action.correlationId,
                metadata = SafeEventMetadata.builder()
                    .put("actionName", action.name)
                    .put("category", action.category.name)
                    .put("triggerType", action.triggerType.name)
                    .put("targetEntityType", action.targetEntityType)
                    .put("targetEntityId", action.targetEntityId?.toString())
                    .put("retryable", retryable.toString())
                    .put("errorClass", error?.javaClass?.name ?: "returned_failure")
                    .build(),
                reason = reason.take(200)
            )
        )
    }

    private fun shouldMirror(action: PostCommitAction): Boolean {
        return action.pipeline == AppPipeline.TRANSACTION ||
            action.targetEntityType.equals("Expense", ignoreCase = true)
    }
}
```

Adjust property names to actual `PostCommitAction` model.

Privacy rule:

```text
Do not include raw merchant, notes, receipt text, email payloads, stack traces, or full exception message if uncontrolled.
```

If `reason` can contain raw data, replace with a reason code instead of `reason.take(200)`.

## Step 3 — Add composite writer

```kotlin
@Singleton
class CompositeSideEffectEventWriter @Inject constructor(
    private val diagnosticWriter: DiagnosticSideEffectEventWriter,
    private val transactionFailureWriter: TransactionSideEffectFailureEventWriter
) : SideEffectEventWriter {

    override suspend fun started(action: PostCommitAction) {
        emitBestEffort { diagnosticWriter.started(action) }
    }

    override suspend fun completed(action: PostCommitAction) {
        emitBestEffort { diagnosticWriter.completed(action) }
    }

    override suspend fun skipped(action: PostCommitAction, reason: SideEffectSkipReason) {
        emitBestEffort { diagnosticWriter.skipped(action, reason) }
    }

    override suspend fun failed(
        action: PostCommitAction,
        retryable: Boolean,
        reason: String,
        error: Throwable?
    ) {
        emitBestEffort { diagnosticWriter.failed(action, retryable, reason, error) }
        emitBestEffort { transactionFailureWriter.failed(action, retryable, reason, error) }
    }

    override suspend fun cancelled(action: PostCommitAction, reason: String?) {
        emitBestEffort { diagnosticWriter.cancelled(action, reason) }
    }

    private suspend fun emitBestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Side-effect event writer child failed")
        }
    }
}
```

## Step 4 — Update DI

Change binding from:

```kotlin
@Binds
abstract fun bindSideEffectEventWriter(
    impl: DiagnosticSideEffectEventWriter
): SideEffectEventWriter
```

to:

```kotlin
@Binds
abstract fun bindSideEffectEventWriter(
    impl: CompositeSideEffectEventWriter
): SideEffectEventWriter
```

## Required tests

```text
side_effect_thrown_exception_writes_diagnostic_and_SIDE_EFFECT_FAILED
side_effect_failedFinal_writes_SIDE_EFFECT_FAILED
side_effect_failedRetryable_writes_SIDE_EFFECT_FAILED
side_effect_completed_does_not_write_SIDE_EFFECT_FAILED
side_effect_skipped_does_not_write_SIDE_EFFECT_FAILED
non_transaction_non_expense_failure_does_not_write_transaction_event
expense_target_failure_event_has_expenseId
bulk_transaction_failure_event_has_null_expenseId
side_effect_failure_event_metadata_is_privacy_safe
transaction_failure_writer_failure_does_not_prevent_diagnostic_writer
cancellation_from_event_writer_is_rethrown
```

## Acceptance

- `SIDE_EFFECT_FAILED` is used intentionally.
- Generic diagnostics remain.
- Transaction/expense side-effect failures appear in `transaction_events`.
- Writer is best-effort and does not endanger committed DB mutations.

---

# PR 4 — Source-link fallback finalization + callsite audit

## Fixes

- P2-NEW-17

## Problem

`LEGACY_SOURCE_ONLY` fallback is mostly fixed, but closure requires:

1. explicit fallback policy,
2. source-specific runtime callsite audit,
3. static guard.

## Goal

Runtime source-specific creates must pass concrete provenance. Legacy fallback is only for migration/backfill/debug.

## Files

Modify:

```text
CreateExpenseRequest.kt
CreateExpenseSourceLinkMapper.kt
TransactionLifecycleCoordinator.kt
```

Add/verify:

```text
SourceLinkFallbackPolicy.kt
CreateExpenseSourceLinkRequirements.kt
SourceLinkFallbackPolicyGuardTest.kt
```

## Step 1 — Verify fallback policy

Expected request field:

```kotlin
val sourceLinkFallbackPolicy: SourceLinkFallbackPolicy = SourceLinkFallbackPolicy.NONE
```

Expected mapper logic:

```kotlin
if (
    payloads.isEmpty() &&
    request.sourceLinkFallbackPolicy == SourceLinkFallbackPolicy.LEGACY_BACKFILL_ONLY
) {
    // create LEGACY_SOURCE_ONLY
}
```

No default runtime fallback.

## Step 2 — Add source-link requirements

Create/verify object:

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

            ExpenseSource.CSV_IMPORT ->
                if (request.csvImportBatchId == null || request.csvRowNumber == null)
                    listOf("csvImportBatchId", "csvRowNumber")
                else emptyList()

            ExpenseSource.EMAIL_RECEIPT ->
                if (request.emailReceiptSourceId == null) listOf("emailReceiptSourceId") else emptyList()

            ExpenseSource.NOTIFICATION ->
                if (request.rawNotificationId == null) listOf("rawNotificationId") else emptyList()

            ExpenseSource.BANK_SYNC ->
                if (request.bankSyncRunId == null) listOf("bankSyncRunId") else emptyList()

            else -> emptyList()
        }
    }
}
```

Adapt enum/field names to actual code.

## Step 3 — Enforce in create validation

In create validation:

```kotlin
if (request.sourceLinkFallbackPolicy != SourceLinkFallbackPolicy.LEGACY_BACKFILL_ONLY) {
    val missing = CreateExpenseSourceLinkRequirements.missingRequirements(request)
    if (missing.isNotEmpty()) {
        errors += "Missing source provenance for ${request.source}: ${missing.joinToString(",")}"
    }
}
```

## Step 4 — Audit all callsites

Run:

```bash
grep -R "CreateExpenseRequest(" app/src/main/java
```

Required provenance:

| Source | Required fields |
|---|---|
| review | `pendingReviewId` |
| receipt | `scannedReceiptId` |
| group | `groupId` |
| CSV/import | `csvImportBatchId` + `csvRowNumber` |
| email | `emailReceiptSourceId` |
| notification | `rawNotificationId` |
| bank | `bankSyncRunId` / provider/account identifiers if available |
| manual | no external source link required |

## Step 5 — Add static guard

`SourceLinkFallbackPolicyGuardTest`:

Rules:

```text
SourceLinkFallbackPolicy.LEGACY_BACKFILL_ONLY may appear only in:
- migration/backfill code
- debug/test fixture code
- provenance tests
```

Fail if normal repositories/workers set it.

## Required tests

```text
mapper_no_payloads_default_policy_returns_empty_list
mapper_no_payloads_legacy_policy_returns_LEGACY_SOURCE_ONLY
review_approval_missing_pendingReviewId_validation_fails
receipt_scan_missing_scannedReceiptId_validation_fails
group_expense_missing_groupId_validation_fails
csv_import_missing_batch_or_row_validation_fails
manual_entry_without_source_link_is_allowed
runtime_create_does_not_write_LEGACY_SOURCE_ONLY
legacy_backfill_policy_writes_LEGACY_SOURCE_ONLY
all_runtime_CreateExpenseRequest_sources_have_required_provenance
```

## Acceptance

- `LEGACY_SOURCE_ONLY` is explicit only.
- Runtime source creates have concrete provenance.
- Static guard prevents accidental fallback.

---

# PR 5 — Debug snapshot/delete/restore audit closeout

## Fixes

- P2-NEW-18

## Current status

Mostly fixed, but finalization requires regression tests and metadata privacy checks.

## Required contract

```text
createDebugSnapshot -> diagnostic event, best-effort
deleteAllExpenses -> DEBUG_DELETE_ALL_EXPENSES transaction event in same transaction
restoreDebugSnapshot -> RESTORED_FROM_DEBUG_SNAPSHOT transaction event in same transaction
```

## Files

Modify/verify:

```text
ExpenseRepository.kt
DebugExpenseAuditWriter.kt
ExpenseDao.kt
LifecycleEventType.kt
```

Tests:

```text
DebugExpenseAuditWriterTest.kt
ExpenseRepositoryDebugAuditTest.kt
```

## Implementation checks

`ExpenseDao` should have:

```kotlin
@Query("SELECT COUNT(*) FROM expenses")
suspend fun countAllExpenses(): Int
```

`deleteAllExpenses()`:

```kotlin
requireDebugExpenseOperation("deleteAllExpenses")
writeBarrier.checkWritesAllowed("ExpenseRepository.deleteAllExpenses")

database.withTransaction {
    val affectedCount = expenseDao.countAllExpenses()
    expenseDao.deleteAll()
    debugExpenseAuditWriter.writeDeleteAllEvent(affectedCount, correlationId)
}
```

`restoreDebugSnapshot()`:

```kotlin
requireDebugExpenseOperation("restoreDebugSnapshot")
writeBarrier.checkWritesAllowed("ExpenseRepository.restoreDebugSnapshot")

database.withTransaction {
    val beforeCount = expenseDao.countAllExpenses()
    expenseDao.deleteAll()
    expenseDao.insertAll(snapshot.expenses)
    debugExpenseAuditWriter.writeRestoreSnapshotEvent(beforeCount, snapshot.expenses.size, correlationId)
}
```

`createDebugSnapshot()`:

```kotlin
requireDebugExpenseOperation("createDebugSnapshot")

val snapshot = DebugExpenseSnapshot(expenseDao.getAllUncapped())
debugExpenseAuditWriter.emitSnapshotCreatedDiagnosticBestEffort(snapshot.expenses.size, correlationId)
return snapshot
```

Allowed metadata:

```text
operation
affectedCount
beforeCount
restoredCount
snapshotCount
debugOnly
aggregate
```

Forbidden metadata:

```text
merchant
notes
raw expense rows
receipt text
addresses
external fingerprints
provider payloads
```

## Required tests

```text
create_debug_snapshot_emits_diagnostic
create_debug_snapshot_diagnostic_has_snapshotCount
create_debug_snapshot_returns_snapshot_when_diagnostic_writer_fails
create_debug_snapshot_rethrows_cancellation

delete_all_debug_writes_DEBUG_DELETE_ALL_EXPENSES_event
delete_all_debug_event_has_expenseId_null
delete_all_debug_event_has_affectedCount
delete_all_debug_audit_failure_rolls_back_delete

restore_debug_snapshot_writes_RESTORED_FROM_DEBUG_SNAPSHOT_event
restore_debug_snapshot_event_has_beforeCount_and_restoredCount
restore_debug_snapshot_audit_failure_rolls_back_restore

debug_audit_metadata_contains_no_raw_expense_data
debug_delete_all_blocked_by_writeBarrier
debug_restore_snapshot_blocked_by_writeBarrier
```

## Acceptance

- Debug read/write actions are visible.
- Mutating audit is atomic.
- Diagnostics are best-effort.
- No raw expense data leaks.

---

# PR 6 — Bulk changed-field side-effect completion

## Fixes

- P2-NEW-19

## Problem

`BulkChangedField` and `changedFields` exist, but planner still has placeholder skipped actions for anomaly/cache/merchant/recurring in some paths.

## Goal

Turn placeholder semantics into either:

1. real invalidation/dirty-marker actions, or
2. explicit tested no-op capability contracts.

No vague `NOT_APPLICABLE` TODOs.

## Files

Modify:

```text
TransactionSideEffectPlanner.kt
TransactionSideEffectDispatcher.kt
TransactionLifecycleCoordinator.kt
GroupTransactionCoordinator.kt
```

Possibly add interfaces:

```text
AnalyticsCacheInvalidator.kt
ExpenseDerivedStateInvalidator.kt
MerchantLearningDirtyMarker.kt
RecurringBulkReconciler.kt
```

## Step 1 — Verify enum

`BulkChangedField` should include:

```kotlin
AMOUNT
AMOUNT_EFFECTIVE
CATEGORY
MERCHANT
MERCHANT_KEY
TRANSACTION_TYPE
DATE
CURRENCY
OWNERSHIP
TRANSFER
LOCATION
BUSINESS_FLAGS
UNKNOWN
```

## Step 2 — Ensure callsites pass fields

Category bulk:

```kotlin
changedFields = setOf(BulkChangedField.CATEGORY)
```

Merchant bulk:

```kotlin
changedFields = setOf(BulkChangedField.MERCHANT, BulkChangedField.MERCHANT_KEY)
```

Group cleanup:

```kotlin
changedFields = setOf(BulkChangedField.OWNERSHIP, BulkChangedField.AMOUNT_EFFECTIVE)
```

Unknown:

```kotlin
changedFields = setOf(BulkChangedField.UNKNOWN)
```

## Step 3 — Replace placeholders

Required action names:

```text
bulk_budget_check
bulk_anomaly_invalidation
bulk_analytics_cache_invalidation
bulk_merchant_category_dirty
bulk_merchant_canonical_stats_dirty
bulk_recurring_reconciliation
```

If no actual subsystem exists, add explicit no-op implementations such as:

```kotlin
interface AnalyticsCacheInvalidator {
    suspend fun invalidateForExpenseBulkMutation(
        source: String,
        affectedCount: Int,
        changedFields: Set<String>
    )
}

@Singleton
class NoOpAnalyticsCacheInvalidator @Inject constructor() : AnalyticsCacheInvalidator {
    override suspend fun invalidateForExpenseBulkMutation(...) = Unit
}
```

Then action can complete successfully. If you prefer skipped outcome, use explicit reason:

```text
NOT_CONFIGURED
```

not ambiguous `NOT_APPLICABLE`.

## Step 4 — Planner decision rules

Expected behavior:

```text
CATEGORY -> budget, anomaly, analytics/cache, merchant learning
MERCHANT/MERCHANT_KEY -> anomaly, analytics/cache, merchant learning, recurring
OWNERSHIP/AMOUNT_EFFECTIVE -> budget, anomaly, analytics/cache
LOCATION -> analytics/location cache only, not budget/recurring
UNKNOWN -> all global invalidations
```

## Required tests

```text
planBulkUpdated_zero_count_returns_empty_or_explicit_skipped_batch
planBulkUpdated_category_includes_budget_anomaly_cache_merchant_learning
planBulkUpdated_merchant_includes_anomaly_cache_merchant_learning_recurring
planBulkUpdated_ownership_includes_budget_anomaly_cache
planBulkUpdated_location_only_skips_budget_and_recurring
planBulkUpdated_unknown_includes_global_invalidations
bulk_category_update_passes_CATEGORY
bulk_merchant_update_passes_MERCHANT_and_MERCHANT_KEY
group_cleanup_passes_OWNERSHIP_and_AMOUNT_EFFECTIVE
bulk_action_metadata_contains_changedFields
bulk_action_metadata_is_privacy_safe
no_bulk_action_has_vague_NOT_APPLICABLE_placeholder
```

## Acceptance

- Bulk planner is field-aware.
- No vague placeholder TODOs remain.
- Actions are aggregate, not per-expense loops.
- Metadata is privacy-safe.

---

# PR 7 — Static guard hardening

## Fixes

- P2-NEW-20
- also supports P2-P1-05

## Problem

`RestrictedExpenseDaoMutation` exists but was previously only warning-level. Static guard coverage is incomplete.

## Goal

Unapproved direct `ExpenseDao` mutations fail compile or architecture tests.

## Files

Modify:

```text
RestrictedExpenseDaoMutation.kt
ExpenseDao.kt
```

Add:

```text
app/src/test/java/com/yourname/expensetracker/architecture/ExpenseDaoMutationAccessTest.kt
```

## Step 1 — Make opt-in hard error

```kotlin
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Direct ExpenseDao mutation is restricted. Route through TransactionLifecycleCoordinator or add a reviewed write-barrier-protected allowlist exception."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class RestrictedExpenseDaoMutation
```

## Step 2 — Annotate all mutating DAO methods

Must annotate:

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
updateCategoryForCategory
```

Also annotate any new mutation added later.

Do not annotate read methods.

## Step 3 — Allowlist approved owners

Allowed class-level opt-in:

```text
TransactionLifecycleCoordinator.kt
```

Allowed function-level opt-in only:

```text
ExpenseRepository.kt debug/maintenance methods
GroupTransactionCoordinator.kt group cleanup paths
ReceiptLinkService.kt only if unavoidable
migration/backfill classes
```

Each function-level bypass must have:

```kotlin
// EXPENSE_DAO_MUTATION_ALLOWLIST:
// Reason: ...
// Guard: DatabaseWriteBarrier / BuildConfig.DEBUG / migration-only.
// Audit: ...
```

No file-level opt-in.

## Step 4 — Architecture tests

Create `ExpenseDaoMutationAccessTest.kt`.

Required rules:

```text
no_file_level_restricted_expense_dao_opt_in
no_suppression_of_restricted_expense_dao_opt_in_errors
restricted_opt_in_only_in_allowlisted_files
no_raw_expenseDao_mutation_calls_outside_approved_files
every_mutating_expense_dao_method_is_annotated
approved_bypasses_have_ALLOWLIST_comment
restricted_annotation_uses_ERROR_level
```

## Required tests

Architecture tests above.

Also compile must pass:

```bash
./gradlew compileDebugKotlin
```

## Acceptance

- Restriction is `ERROR`, not `WARNING`.
- New unapproved direct DAO mutations fail.
- Bypasses are documented and guarded.
- Static guard protects future work.

---

# PR 8 — Manual persisted hook regression + final golden pass

## Fixes / confirms

- P2-NEW-15
- final proof for P2-NEW-13..20

## Problem

Manual persisted hook is fixed, but needs regression coverage so it does not return to synthetic `Expense`.

## Files

Test:

```text
ManualExpenseRepositoryRecommendationHookTest.kt
Pipeline2New13To20GoldenTest.kt
```

## Required tests for P2-NEW-15

```text
manual_create_fetches_persisted_expense_for_recommendation_hook
manual_recommendation_uses_persisted_baseAmount
manual_recommendation_uses_persisted_exchangeRateUsed
manual_recommendation_uses_persisted_merchantKey
manual_recommendation_uses_persisted_dedupeKey
manual_duplicate_does_not_generate_recommendations
manual_validation_failed_does_not_generate_recommendations
manual_recommendation_generation_runs_post_commit_only
```

Implementation check:

```bash
grep -R "P2-CURRENT-019" app/src/main/java
grep -R "DuplicateDetectionPolicy.generateDedupeKeyWithType" app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt
grep -R "MerchantKeyGenerator.generate" app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt
```

Expected:

```text
ManualExpenseRepository does not synthesize hook Expense keys manually.
It fetches persisted row by ID.
```

## Final golden tests

Create/verify:

```text
receipt_legacy_create_path_absent_or_guarded
group_lifecycle_permanent_delete_validated_and_audited
side_effect_failure_writes_SIDE_EFFECT_FAILED_transaction_event
runtime_source_specific_create_without_provenance_fails
debug_snapshot_delete_restore_audited
bulk_side_effects_are_changed_field_aware
expenseDao_mutation_static_guard_passes
manual_recommendation_uses_persisted_expense
```

## Acceptance

- P2-NEW-15 remains fixed with tests.
- Final golden tests prove P2-NEW-13..20 are closed.

---

# Global final grep checklist

Run after all PRs:

```bash
grep -R "createExpenseFromReceipt" app/src/main/java
grep -R "P2-CURRENT-019" app/src/main/java
grep -R "TODO P2-NEW-13\|TODO P2-NEW-14\|TODO P2-NEW-16\|TODO P2-NEW-17\|TODO P2-NEW-18\|TODO P2-NEW-19\|TODO P2-NEW-20" app/src/main/java
grep -R "LEGACY_SOURCE_ONLY" app/src/main/java
grep -R "SourceLinkFallbackPolicy.LEGACY_BACKFILL_ONLY" app/src/main/java
grep -R "SIDE_EFFECT_FAILED" app/src/main/java
grep -R "RequiresOptIn.Level.WARNING" app/src/main/java/com/yourname/expensetracker/data/database/dao/RestrictedExpenseDaoMutation.kt
grep -R "@file:OptIn(RestrictedExpenseDaoMutation::class)" app/src/main/java
grep -R "NOT_APPLICABLE" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt
grep -R "permanentlyDeleteGroup(groupId: Long): Boolean" app/src/main/java
```

Expected:

```text
no production receipt legacy path
no manual synthetic hook TODO
legacy source fallback only in migration/backfill/debug/tests
SIDE_EFFECT_FAILED writer exists and is bound
RestrictedExpenseDaoMutation uses ERROR
no file-level restricted DAO opt-in
no vague bulk NOT_APPLICABLE placeholders
direct Boolean permanent delete deprecated/removed
```

---

# Build/test commands

Run after each PR:

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
```

Targeted:

```bash
./gradlew testDebugUnitTest --tests '*ReceiptLegacyCreatePathGuardTest*'
./gradlew testDebugUnitTest --tests '*GroupLifecycleCoordinator*'
./gradlew testDebugUnitTest --tests '*SideEffect*'
./gradlew testDebugUnitTest --tests '*SourceLink*'
./gradlew testDebugUnitTest --tests '*DebugExpenseAudit*'
./gradlew testDebugUnitTest --tests '*TransactionSideEffectPlanner*'
./gradlew testDebugUnitTest --tests '*ExpenseDaoMutationAccessTest*'
./gradlew testDebugUnitTest --tests '*ManualExpenseRepository*'
./gradlew testDebugUnitTest --tests '*Pipeline2New13To20GoldenTest*'
```

---

# Final definition of done

## P2-NEW-13 done when

- `GroupLifecycleCoordinator` exists.
- Permanent delete requires explicit confirmation.
- Active/outstanding/current-user cases are blocked.
- Group lifecycle events are written.
- Direct low-level hard delete is deprecated/guarded.

## P2-NEW-14 done when

- Generic side-effect diagnostics still exist.
- Transaction/expense side-effect failures also write `SIDE_EFFECT_FAILED`.
- Failure writer is best-effort and privacy-safe.

## P2-NEW-15 done when

- Manual recommendation hook uses persisted DAO row.
- Regression tests prove persisted conversion/key fields are used.

## P2-NEW-16 done when

- Legacy receipt create method is deleted or impossible to call.
- Receipt lifecycle create/link is atomic.
- Static guard prevents reintroduction.

## P2-NEW-17 done when

- `LEGACY_SOURCE_ONLY` is explicit backfill-only.
- Runtime source creates require concrete provenance.
- All callsites are audited.

## P2-NEW-18 done when

- Debug snapshot create emits diagnostic.
- Debug delete/restore write aggregate transaction events.
- Mutating debug audit is atomic.
- Metadata is privacy-safe.

## P2-NEW-19 done when

- Bulk changed fields drive targeted aggregate actions.
- No vague placeholder side-effect actions remain.
- Callsites pass meaningful changed fields.

## P2-NEW-20 done when

- `RestrictedExpenseDaoMutation` is `ERROR`.
- Architecture tests block unapproved direct DAO mutations.
- Approved bypasses are documented and guarded.