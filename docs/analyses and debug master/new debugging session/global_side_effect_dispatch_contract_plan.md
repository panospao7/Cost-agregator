# Global Side-Effect Dispatch Contract Implementation Plan

Baseline commit: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`

Universal rule:

```text
Database state commits first.
Side effects dispatch after commit.
Side effects dispatch exactly once.
Nested coordinator flows must defer side effects to the outer owner.
Every side effect has a typed outcome: completed, skipped, failed retryable, failed final, cancelled.
```

Affected pipelines:

```text
P2 Transaction lifecycle
P3 Receipt/OCR/email/bank statement
P4 Recurring/reminders
P6 Budget/forecast/cashflow
P9 Workers/background jobs
P11 Email receipt ingestion
Indirectly:
P1 Notification
P7 Backup/restore
P10 Bank integration
P12 Import/export/accounting
```

---

## 0. Current state summary

Current code already has partial infrastructure:

```text
TransactionLifecycleCoordinator
TransactionSideEffectDispatcher
SideEffectMode.IMMEDIATE / DEFER
ReceiptLifecycleCoordinator
ReceiptSideEffectDispatcher
RecurringLifecycleCoordinator
WorkerExecutionGuard
```

Important current observations:

1. `TransactionLifecycleCoordinator.createExpense()` writes the expense and `CREATED` event inside a Room transaction, then dispatches side effects post-commit when `SideEffectMode.IMMEDIATE` is used.

2. The code already warns that callers inside an outer `database.withTransaction` must pass `SideEffectMode.DEFER`, but this is caller discipline and error-prone.

3. `TransactionSideEffectDispatcher` catches and logs side-effect failures, but does not durably record `SIDE_EFFECT_FAILED`.

4. `ReceiptSideEffectDispatcher` records some `ReceiptEvent(SIDE_EFFECT_FAILED)` events but not a standardized side-effect lifecycle.

5. Some pipelines can double-dispatch side effects. Example: email receipt processing can return expense IDs from a coordinator that already dispatched, then the service dispatches transaction post-creation effects again.

6. Some flows dispatch side effects from inside or effectively before an outer transaction commits, especially group/receipt/composite flows.

7. Side effects are often not cancellable/checkpointed and do not return structured results.

---

# 1. Target model

## 1.1 Side-effect definition

A side effect is any operation triggered by a domain state change that is **not** required to commit the primary state transition.

Examples:

```text
budget recheck
anomaly alert calculation
merchant-category learning
merchant canonical stat update
recurring occurrence matching
receipt matching
warranty extraction
item categorization
price protection
notification delivery
forecast/cache invalidation
AI recommendation generation
worker reschedule
```

---

## 1.2 Side-effect categories

```kotlin
enum class SideEffectCategory {
    BUDGET,
    ANALYTICS,
    ANOMALY,
    MERCHANT_LEARNING,
    RECURRING,
    RECEIPT_MATCHING,
    RECEIPT_ITEM_CATEGORIZATION,
    WARRANTY,
    PRICE_PROTECTION,
    NOTIFICATION_DELIVERY,
    AI_RECOMMENDATION,
    CACHE_INVALIDATION,
    WORKER_SCHEDULING,
    EXPORT_IMPORT,
    BANK_SYNC
}
```

---

## 1.3 Trigger types

```kotlin
enum class SideEffectTriggerType {
    EXPENSE_CREATED,
    EXPENSE_UPDATED,
    EXPENSE_DELETED,
    EXPENSE_BULK_UPDATED,

    RECEIPT_SAVED,
    RECEIPT_LINKED,
    RECEIPT_UNLINKED,

    RECURRING_OCCURRENCE_PAID,
    RECURRING_RULE_UPDATED,
    REMINDER_CLAIMED,

    BUDGET_UPDATED,
    FORECAST_GENERATED,

    IMPORT_COMPLETED,
    BANK_SYNC_COMPLETED,
    WORKER_COMPLETED
}
```

---

## 1.4 Outcome types

```kotlin
sealed interface SideEffectOutcome {
    data object Completed : SideEffectOutcome
    data class Skipped(val reason: SideEffectSkipReason) : SideEffectOutcome
    data class FailedRetryable(
        val reason: String,
        val errorClass: String? = null
    ) : SideEffectOutcome
    data class FailedFinal(
        val reason: String,
        val errorClass: String? = null
    ) : SideEffectOutcome
    data class Cancelled(val reason: String? = null) : SideEffectOutcome
}
```

```kotlin
enum class SideEffectSkipReason {
    NOT_APPLICABLE,
    PRIVACY_DENIED,
    RESTORE_BLOCKED,
    MISSING_ENTITY,
    ALREADY_PROCESSED,
    DISABLED_BY_SETTINGS,
    LOW_CONFIDENCE,
    NO_WORK,
    DUPLICATE,
    PERMISSION_DENIED
}
```

---

# 2. Core contract

## 2.1 Transaction boundary rule

```text
All primary DB mutations and lifecycle events happen inside the DB transaction.
Side effects run only after the transaction commits successfully.
If the transaction rolls back, side effects must not run.
```

Correct:

```text
database.withTransaction {
  insert expense
  insert TransactionEvent(CREATED)
}
sideEffectDispatcher.dispatch(...)
```

Incorrect:

```text
database.withTransaction {
  insert expense
  sideEffectDispatcher.dispatch(...) // forbidden
}
```

---

## 2.2 Ownership rule

```text
The lifecycle coordinator that owns the primary mutation owns the side-effect plan.
Callers may execute returned post-commit actions, but must not independently redispatch the same semantic side effects.
```

Example:

```text
ReceiptLifecycleCoordinator creates an expense from email.
It owns both:
  - transaction create side-effect decision
  - receipt save side-effect decision

EmailReceiptIngestionService must not dispatch transaction side effects again.
```

---

## 2.3 Single-dispatch rule

Each logical side effect must have an idempotency key:

```text
sideEffectName + triggerType + targetEntityType + targetEntityId + sourceEventId/correlationId
```

This prevents:

```text
double budget check storms
double recurring linking
double receipt matching
double notification send
double AI recommendation generation
```

Not every side effect must persist idempotency initially, but the dispatcher should compute and log the key.

---

## 2.4 Nested coordinator rule

If a coordinator is called from inside an outer transaction:

```text
inner coordinator must return DB-only result + post-commit actions
outer owner dispatches actions after outer transaction commits
```

No `SideEffectMode.IMMEDIATE` inside caller-managed transactions.

Current `SideEffectMode.DEFER` is conceptually correct but should be replaced by a safer typed result.

---

## 2.5 Failure rule

Side-effect failures do not roll back the primary transaction.

But failures must be durable:

```text
SIDE_EFFECT_STARTED
SIDE_EFFECT_COMPLETED
SIDE_EFFECT_FAILED
SIDE_EFFECT_SKIPPED
SIDE_EFFECT_CANCELLED
```

Use:

```text
pipeline_diagnostic_events
receipt_events for receipt-specific effects
transaction_events for transaction-specific effect summary if needed
operation_run_events for batch/worker effects
```

---

# 3. New abstractions

## 3.1 PostCommitAction

```kotlin
data class PostCommitAction(
    val idempotencyKey: String,
    val name: String,
    val category: SideEffectCategory,
    val triggerType: SideEffectTriggerType,
    val targetEntityType: String,
    val targetEntityId: Long?,
    val source: String,
    val correlationId: String?,
    val metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    val priority: SideEffectPriority = SideEffectPriority.NORMAL,
    val execute: suspend SideEffectExecutionContext.() -> SideEffectOutcome
)
```

```kotlin
enum class SideEffectPriority {
    HIGH,
    NORMAL,
    LOW,
    BACKGROUND
}
```

---

## 3.2 PostCommitActionBatch

```kotlin
data class PostCommitActionBatch(
    val correlationId: String,
    val actions: List<PostCommitAction>
) {
    companion object {
        fun empty(correlationId: String): PostCommitActionBatch =
            PostCommitActionBatch(correlationId, emptyList())
    }

    operator fun plus(other: PostCommitActionBatch): PostCommitActionBatch =
        copy(actions = actions + other.actions)
}
```

---

## 3.3 PostCommitActionRunner

```kotlin
interface PostCommitActionRunner {
    suspend fun run(batch: PostCommitActionBatch): SideEffectBatchResult
}
```

```kotlin
data class SideEffectBatchResult(
    val correlationId: String,
    val completed: Int,
    val skipped: Int,
    val failedRetryable: Int,
    val failedFinal: Int,
    val cancelled: Int,
    val outcomes: List<SideEffectActionResult>
)
```

```kotlin
data class SideEffectActionResult(
    val idempotencyKey: String,
    val name: String,
    val outcome: SideEffectOutcome
)
```

Runner behavior:

```text
for each action:
  emit SIDE_EFFECT_STARTED
  execute action
  emit terminal side-effect event
  CancellationException -> emit CANCELLED then rethrow
  unexpected exception -> classify retryable/final and emit FAILED
```

---

## 3.4 SideEffectExecutionContext

```kotlin
interface SideEffectExecutionContext {
    val correlationId: String
    val action: PostCommitAction

    suspend fun checkpoint(label: String)
    suspend fun recordMetadata(metadata: SafeEventMetadata)
}
```

Use `checkpoint()` to integrate with worker/restore cancellation later.

---

## 3.5 SideEffectEventWriter

```kotlin
interface SideEffectEventWriter {
    suspend fun started(action: PostCommitAction)
    suspend fun completed(action: PostCommitAction)
    suspend fun skipped(action: PostCommitAction, reason: SideEffectSkipReason)
    suspend fun failed(
        action: PostCommitAction,
        retryable: Boolean,
        reason: String,
        error: Throwable?
    )
    suspend fun cancelled(action: PostCommitAction, reason: String?)
}
```

Short-term implementation:

```text
PipelineDiagnosticEventWriter
```

Long-term optional:

```text
side_effect_events table
```

---

# 4. Result model for lifecycle coordinators

## 4.1 Mutation result with post-commit actions

```kotlin
data class MutationResult<out T>(
    val value: T,
    val postCommitActions: PostCommitActionBatch
)
```

For existing result sealed classes, add action batch:

```kotlin
sealed interface CreateExpenseResult {
    data class Created(
        val expenseId: Long,
        val postCommitActions: PostCommitActionBatch = PostCommitActionBatch.empty(...)
    ) : CreateExpenseResult

    data class DuplicateSkipped(...)
    data class ValidationFailed(...)
    data class Error(...)
}
```

But to avoid huge churn, first add wrapper APIs:

```kotlin
suspend fun createExpenseDbOnlyV2(
    request: CreateExpenseRequest
): MutationResult<CreateExpenseResult>

suspend fun createExpenseStandaloneV2(
    request: CreateExpenseRequest
): CreateExpenseResult
```

---

## 4.2 Replace `SideEffectMode`

Current:

```kotlin
createExpense(request, SideEffectMode.IMMEDIATE)
createExpenseDbOnly(request) // DEFER
```

Target:

```kotlin
val result = transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)
database.withTransaction { ... } // if outer owner
postCommitActionRunner.run(result.postCommitActions)
```

Standalone:

```kotlin
suspend fun createExpenseStandaloneV2(request: CreateExpenseRequest): CreateExpenseResult {
    val result = createExpenseDbOnlyV2(request)
    postCommitActionRunner.run(result.postCommitActions)
    return result.value
}
```

Deprecation path:

```text
Phase 1: keep SideEffectMode but implement through action batches.
Phase 2: mark SideEffectMode deprecated ERROR.
Phase 3: remove.
```

---

# 5. Transaction side-effect plan

## 5.1 TransactionSideEffectPlanner

Add:

```kotlin
class TransactionSideEffectPlanner @Inject constructor(
    private val budgetMonitor: Lazy<BudgetMonitor>,
    private val anomalyAlertOrchestrator: AnomalyAlertOrchestrator,
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val merchantNormalizationRepository: MerchantNormalizationRepository,
    private val recurringLifecycleCoordinator: Lazy<RecurringLifecycleCoordinator>,
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao
) {
    fun onCreated(expenseId: Long, source: ExpenseSource, correlationId: String?): PostCommitActionBatch
    fun onUpdated(expenseId: Long, source: String, beforeChangedFields: Set<String>, correlationId: String?): PostCommitActionBatch
    fun onDeleted(expenseId: Long, source: String, correlationId: String?): PostCommitActionBatch
    fun onBulkUpdated(source: String, affectedCount: Int, correlationId: String?): PostCommitActionBatch
}
```

Current `TransactionSideEffectDispatcher` can become:

```text
TransactionSideEffectPlanner + PostCommitActionRunner
```

or can be kept as a facade temporarily.

---

## 5.2 Actions for expense created

Actions:

```text
budget_check
anomaly_check
merchant_category_learning
merchant_canonical_stats_update
recurring_occurrence_match
```

Idempotency keys:

```text
expense:{id}:created:budget_check
expense:{id}:created:anomaly_check
expense:{id}:created:merchant_category_learning
expense:{id}:created:merchant_stats
expense:{id}:created:recurring_match
```

Important:

```text
Recurring link action must re-read current expense and current occurrence state.
If already linked, return Skipped(ALREADY_PROCESSED).
```

---

## 5.3 Actions for expense updated

Actions:

```text
budget_recheck
anomaly_recheck
merchant_category_learning_if_category
recurring_reconcile
merchant_stats_recompute_or_delta
```

Current code calls:

```text
sideEffectDispatcher.dispatchOnUpdated()
then separately unlink/link recurring in coordinator
```

Target:

```text
recurring_reconcile is one action owned by the planner.
```

No direct recurring reconciliation outside the action batch.

---

## 5.4 Actions for expense deleted

Actions:

```text
budget_recheck
recurring_unlink
anomaly_clear_if_supported
```

Current code can unlink recurring twice in some flows. Target: one action.

---

# 6. Receipt side-effect plan

## 6.1 ReceiptSideEffectPlanner

Add:

```kotlin
class ReceiptSideEffectPlanner @Inject constructor(...) {
    fun afterReceiptSaved(
        receiptId: Long,
        documentType: ReceiptDocumentType,
        processingStatus: ReceiptProcessingStatus,
        correlationId: String?
    ): PostCommitActionBatch

    fun afterReceiptLinked(
        receiptId: Long,
        expenseId: Long,
        linkType: String,
        correlationId: String?
    ): PostCommitActionBatch
}
```

---

## 6.2 Receipt saved actions

For retail receipt:

```text
warranty_extract
receipt_item_categorization
receipt_transaction_match
price_protection_check
```

For email receipt:

```text
receipt_item_categorization
```

For bank statement:

```text
skip all normal receipt side effects
bank statement import owns its own operation actions
```

For parse/OCR failed:

```text
skip with reason NOT_APPLICABLE
```

---

## 6.3 Receipt matching action

Current `ReceiptSideEffectDispatcher` directly performs matching and writes suggestion/link.

Target:

```text
receipt_transaction_match action
  -> calls ReceiptMatchingCoordinator
  -> returns completed/skipped/failed
  -> writes domain events for MATCH_SUGGESTED/AUTO_MATCHED/MATCH_NOT_FOUND
  -> side-effect runner writes side-effect outcome
```

Important:

```text
NoMatch must be a durable domain/diagnostic event, not silent.
```

---

## 6.4 Receipt-created expense actions

When receipt/email coordinator creates an expense by calling transaction coordinator DB-only:

```text
receipt coordinator receives transaction postCommitActions
receipt coordinator adds receipt postCommitActions
outer caller runs combined batch once
```

This avoids:

```text
coordinator dispatch + service dispatch
created existing duplicate expense treated as new
```

---

# 7. Recurring/reminder side-effect plan

## 7.1 Recurring side effects

Recurring operations can be both primary mutations and side effects.

Examples:

```text
expense created -> side effect: match occurrence
occurrence paid -> primary recurring mutation
occurrence paid -> side effect: suppress reminders, fulfill planned row
rule updated -> primary mutation
rule updated -> side effect: regenerate open occurrences/reminders
```

Use action batches for the latter.

---

## 7.2 Payment/reminder race

Side effect action:

```text
reminder_suppress_paid
```

Must handle:

```text
SCHEDULED
SNOOZED
CLAIMED
FAILED_TRANSIENT
```

Action should re-read current occurrence and delivery state.

Outcome:

```text
Completed if rows suppressed
Skipped(ALREADY_PROCESSED) if none open
FailedRetryable if DB locked
```

---

# 8. Worker integration

## 8.1 Workers as outer owners

Workers that perform primary mutations should:

```text
call DB-only coordinator method
commit
run action batch
record worker counts
```

For long workers:

```text
run action batch per item or per chunk
checkpoint before each action batch
```

Examples:

```text
ReceiptMatchingWorker
DataRetentionWorker
BillReminderWorker
WarrantyExpirationWorker
BankSyncWorker future
ImportWorker future
```

---

## 8.2 Worker cancellation

`PostCommitActionRunner` must treat cancellation specially:

```kotlin
catch (e: CancellationException) {
    eventWriter.cancelled(action, "coroutine_cancelled")
    throw e
}
```

Do not swallow cancellation.

---

# 9. Operation/batch flows

## 9.1 Group transaction flow

Current risk:

```text
GroupTransactionCoordinator performs an outer transaction.
Inside it, normalizing linked expense may call update lifecycle and dispatch side effects before outer commit.
```

Target:

```text
val allActions = mutableListOf<PostCommitActionBatch>()

database.withTransaction {
    val result = transactionLifecycleCoordinator.updateOwnershipDbOnlyV2(...)
    allActions += result.postCommitActions
    // group mutations
}

postCommitActionRunner.run(allActions.combine())
```

---

## 9.2 Email receipt flow

Current risk:

```text
ReceiptLifecycleCoordinator dispatches.
EmailReceiptIngestionService also dispatches.
```

Target:

```text
EmailReceiptIngestionService:
  val result = receiptLifecycleCoordinator.processEmailReceiptStandalone(...)
  return result

ReceiptLifecycleCoordinator:
  DB-only method returns actions
  standalone method runs actions once
```

No service-level side-effect dispatch.

---

## 9.3 Bank sync/import flow

Future bank sync:

```text
for each bank transaction:
  database transaction creates expense/review/import outcome
  collect post-commit actions
  run actions after each item or chunk
```

Do not run side effects for:

```text
duplicate skipped
pending review created, unless review notification is a side effect
failed item
```

---

## 9.4 Export/import flow

Import:

```text
row create -> transaction post-commit actions
batch import may run actions per chunk
```

Export:

```text
no transaction side effects
may have operation events only
```

---

# 10. Durable side-effect events

## 10.1 Event strategy

Use diagnostic plan’s event writer.

For each action:

```text
SIDE_EFFECT_STARTED
SIDE_EFFECT_COMPLETED
SIDE_EFFECT_SKIPPED
SIDE_EFFECT_FAILED
SIDE_EFFECT_CANCELLED
```

Required metadata:

```json
{
  "sideEffectName": "budget_check",
  "category": "BUDGET",
  "triggerType": "EXPENSE_CREATED",
  "targetEntityType": "EXPENSE",
  "targetEntityId": 123,
  "idempotencyKeyHash": "...",
  "source": "EMAIL_RECEIPT"
}
```

Do not store raw merchant/notes/body.

---

## 10.2 Event writer fallback during restore

If writes are blocked:

```text
PostCommitActionRunner should normally not run because primary writes are blocked.
```

If somehow called during restore:

```text
return Skipped(RESTORE_BLOCKED)
write to MaintenanceSafeDiagnosticSink, not Room.
```

---

# 11. Idempotency strategy

## 11.1 Short-term idempotency

Compute idempotency key and log it.

Use action implementations that are naturally idempotent:

```text
budgetMonitor.checkBudgets() recalculates
recurring link checks existing link before insert
receipt link uses unique link constraints
merchant learning can upsert/increment carefully
notification delivery must claim before send
```

---

## 11.2 Long-term idempotency table

Optional later:

```kotlin
@Entity(
    tableName = "side_effect_executions",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["status"]),
        Index(value = ["targetEntityType", "targetEntityId"])
    ]
)
data class SideEffectExecution(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val idempotencyKey: String,
    val name: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val errorClass: String?,
    val errorMessage: String?,
    val metadataJson: String?
)
```

Do not add this in PR 1 unless needed. Start with diagnostic events.

---

# 12. Static guards

Add script:

```text
scripts/verify_side_effect_boundaries.py
```

Rules:

```text
No dispatchOnCreated/Updated/Deleted call inside database.withTransaction block.
No SideEffectMode.IMMEDIATE from classes known to run outer transactions.
No EmailReceiptIngestionService dispatchPostCreationSideEffects.
No direct calls to TransactionSideEffectDispatcher except:
  TransactionLifecycleCoordinator compatibility facade
  PostCommitActionRunner/planner tests
No ReceiptSideEffectDispatcher.dispatchAfterSave except:
  ReceiptLifecycleCoordinator standalone method or runner
No recurring unlink+link pair in TransactionLifecycleCoordinator outside planner.
```

Search patterns:

```bash
rg "dispatchOnCreated|dispatchOnUpdated|dispatchOnDeleted|dispatchAfterSave|dispatchPostCreationSideEffects" app/src/main/java
rg "SideEffectMode.IMMEDIATE" app/src/main/java
rg "database.withTransaction" app/src/main/java
```

Acceptance:

```text
guard_fails_on_dispatch_inside_withTransaction
guard_fails_on_email_service_transaction_dispatch
guard_fails_on_direct_recurring_unlink_link_in_transaction_update
```

---

# 13. PR implementation order

## PR 1 — Core side-effect model

Files:

```text
SideEffectCategory.kt
SideEffectTriggerType.kt
SideEffectOutcome.kt
SideEffectSkipReason.kt
PostCommitAction.kt
PostCommitActionBatch.kt
PostCommitActionRunner.kt
SideEffectEventWriter.kt
```

Acceptance:

```text
runner_emits_started_and_completed
runner_emits_failed_on_exception
runner_rethrows_cancellation_after_cancelled_event
batch_combines_actions
metadata_is_sanitized
```

---

## PR 2 — Transaction side-effect planner

Tasks:

```text
Add TransactionSideEffectPlanner.
Refactor TransactionSideEffectDispatcher into planner + runner facade.
Create createExpenseDbOnlyV2 and createExpenseStandaloneV2.
Post-create actions returned, not dispatched directly in DB-only path.
Update creates/updates/deletes use action batches.
```

Acceptance:

```text
standalone_create_dispatches_actions_once_after_commit
db_only_create_returns_actions_without_dispatch
update_returns_recurring_reconcile_action
delete_returns_recurring_unlink_action
side_effect_failure_writes_durable_event
```

---

## PR 3 — Replace `SideEffectMode` callsites

Tasks:

```text
Find createExpense(... SideEffectMode.DEFER)
Migrate to createExpenseDbOnlyV2.
Find createExpense(... IMMEDIATE)
Migrate to standalone method.
For outer transactions, collect returned action batches and run after commit.
```

Acceptance:

```text
no production SideEffectMode.IMMEDIATE in outer transaction owners
no deprecated createExpense direct calls except compatibility tests
```

---

## PR 4 — Receipt side-effect planner

Tasks:

```text
Add ReceiptSideEffectPlanner.
Make ReceiptLifecycleCoordinator DB-only methods return actions.
Standalone receipt methods run actions once.
Receipt matching action returns durable MATCH_NOT_FOUND/SUGGESTED/AUTO_MATCHED.
```

Acceptance:

```text
receipt_save_dispatches_side_effects_once
receipt_parse_failed_skips_side_effects
receipt_no_match_writes_match_not_found
receipt_match_suggestion_event_and_side_effect_outcome_written
```

---

## PR 5 — Email double-dispatch fix

Tasks:

```text
Remove service-level transaction side-effect dispatch.
Split coordinator result:
  createdExpenseIds
  linkedExistingExpenseIds
  reviewIds
Only createdExpenseIds get transaction post-create actions.
Existing links get receipt/source-link actions only.
```

Acceptance:

```text
email_created_expense_transaction_side_effect_once
email_duplicate_existing_no_create_side_effect
email_link_existing_has_receipt_link_effect_only
```

---

## PR 6 — Group/nested transaction flows

Tasks:

```text
Refactor group existing-expense link/update flows to DB-only transaction lifecycle APIs.
Collect post-commit actions.
Run after outer database.withTransaction returns success.
```

Acceptance:

```text
group_link_rollback_does_not_run_side_effects
group_link_commit_runs_side_effects_once_after_commit
cancellation_during_post_commit_not_swallowed
```

---

## PR 7 — Recurring/reminder side-effect integration

Tasks:

```text
Payment creates reminder suppression action.
Expense update creates recurring reconcile action.
Rule update creates regenerate-open-occurrences action after commit.
Bill reminder notification delivery is a side-effect action with claim state.
```

Acceptance:

```text
payment_suppresses_claimed_delivery_after_commit
expense_update_recurring_reconcile_runs_once
rule_update_regeneration_runs_after_rule_commit
bill_reminder_notification_failure_records_side_effect_failed
```

---

## PR 8 — Worker and batch integration

Tasks:

```text
Workers run action batches with checkpoints.
Import/bank/email batch operation collects actions per item/chunk.
Worker run counts include side-effect failures/skips.
```

Acceptance:

```text
worker_cancellation_records_cancelled_side_effect
bank_sync_item_created_expense_runs_actions_after_item_commit
import_chunk_runs_actions_after_chunk_commit
worker_counts_side_effect_failures
```

---

## PR 9 — Static guard

Tasks:

```text
Add verify_side_effect_boundaries.py.
Add allowlist with reasons.
Wire into Gradle check.
```

Acceptance:

```text
CI fails on direct dispatcher call inside outer transaction.
CI fails on email service duplicate dispatch.
```

---

# 14. Pipeline-specific checklist

## P2 — Transaction lifecycle

Definition of done:

```text
Expense create/update/delete/bulk update all return post-commit actions.
Transaction side effects are planned, then run once after commit.
Recurring reconcile is one planned action, not ad-hoc code.
Side-effect failures are durable.
```

Tests:

```text
manual_create_side_effects_after_commit
validation_failed_no_side_effects
insert_conflict_no_side_effects
update_rollback_no_side_effects
delete_runs_unlink_once
```

---

## P3 — Receipt/OCR

Definition of done:

```text
Receipt save creates receipt action batch.
Receipt matching/warranty/item categorization/price protection are actions.
Receipt-created expense actions are combined with receipt actions.
Duplicate receipt does not run save side effects for ghost receipt.
```

Tests:

```text
duplicate_receipt_no_side_effects_for_deleted_ghost
receipt_saved_actions_once
receipt_side_effect_failure_durable
```

---

## P4 — Recurring/reminders

Definition of done:

```text
Recurring link/unlink/reminder suppression are idempotent actions.
Payment/reminder race rechecks state at execution time.
No notification after paid state.
```

Tests:

```text
payment_after_claim_before_notify_does_not_send
paid_occurrence_suppression_action_idempotent
```

---

## P6 — Budget/forecast

Definition of done:

```text
Budget check is a side effect with durable failure outcome.
Forecast/cache recompute actions do not run before source transaction commits.
Budget alert send is a notification-delivery action.
```

Tests:

```text
budget_check_failure_writes_side_effect_failed
budget_alert_permission_denied_writes_skipped_or_failed
```

---

## P9 — Workers

Definition of done:

```text
Workers do not perform untracked post-commit side effects.
Runner checkpoints before each long action.
Cancellation records cancelled action.
```

Tests:

```text
worker_cancel_during_side_effect_records_cancelled
worker_side_effect_counts_persisted
```

---

## P11 — Email

Definition of done:

```text
Email service never dispatches transaction side effects.
Coordinator returns created vs linked-existing.
Side effects run exactly once.
```

Tests:

```text
email_created_expense_side_effect_once
email_duplicate_existing_no_transaction_create_side_effect
```

---

# 15. Golden test matrix

Add global tests:

```text
standalone_transaction_create_runs_side_effects_after_commit
db_only_transaction_create_returns_actions_without_running
outer_transaction_rollback_does_not_run_inner_actions
outer_transaction_commit_runs_collected_actions_once
side_effect_exception_does_not_rollback_primary_transaction
side_effect_exception_writes_durable_failed_event
side_effect_cancellation_writes_cancelled_and_rethrows
email_receipt_no_double_dispatch
receipt_created_expense_combines_receipt_and_transaction_actions
group_link_side_effects_after_outer_commit
payment_suppresses_claimed_reminder_before_notify
static_guard_blocks_dispatch_inside_transaction
```

---

# 16. Agent implementation checklist

Before coding, run:

```bash
rg "SideEffectMode" app/src/main/java
rg "dispatchOnCreated|dispatchOnUpdated|dispatchOnDeleted" app/src/main/java
rg "dispatchPostCreationSideEffects" app/src/main/java
rg "dispatchAfterSave" app/src/main/java
rg "database.withTransaction" app/src/main/java
rg "unlinkExpenseFromOccurrence|linkExpenseToOccurrence" app/src/main/java
rg "runSafely\\(" app/src/main/java
rg "SIDE_EFFECT_FAILED" app/src/main/java
rg "sendBudgetAlert|NotificationManagerCompat" app/src/main/java
```

Manual audit questions:

```text
Is this side effect inside a DB transaction?
Can this code be called from inside an outer transaction?
Can this side effect run twice?
Does failure have durable event?
Does cancellation propagate?
Does the action re-read current state before acting?
```

---

# 17. Definition of done

```text
1. No side effect runs inside a DB transaction.

2. Every DB-only coordinator method returns PostCommitActionBatch.

3. Standalone coordinator methods run returned actions exactly once after commit.

4. Nested/outer transaction flows collect actions and run them after the outer commit.

5. SideEffectMode is deprecated or removed from production callsites.

6. Transaction, receipt, recurring, budget, worker, and email side effects use the same runner contract.

7. Side-effect failure/cancel/skip outcomes are durable.

8. Email receipt processing no longer double-dispatches transaction side effects.

9. Existing-expense duplicate links do not run "created expense" side effects.

10. Recurring link/unlink/reconcile actions are idempotent and state-rechecking.

11. Worker cancellation during side effects is not swallowed.

12. Static guard prevents new direct dispatcher calls in unsafe places.
```

---

# 18. Sources used

- Baseline commit:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- `TransactionLifecycleCoordinator.kt` — currently uses `SideEffectMode.IMMEDIATE/DEFER`, dispatches post-commit for standalone creation, and contains TODO warning about caller-managed transactions:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `TransactionSideEffectDispatcher.kt` — current best-effort dispatcher for budget/anomaly/merchant/recurring side effects; logs failures but does not durably standardize outcomes:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt

- `ReceiptLifecycleCoordinator.kt` — current receipt lifecycle coordinator owns receipt save/event and calls receipt/transaction side effects in several paths:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt

- `ReceiptSideEffectDispatcher.kt` — current dispatcher for warranty, item categorization, receipt matching, and price protection, with partial `SIDE_EFFECT_FAILED` receipt events:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt

- `SideEffectMode.kt` — current mode enum used to manually choose immediate vs deferred transaction side effects:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/SideEffectMode.kt