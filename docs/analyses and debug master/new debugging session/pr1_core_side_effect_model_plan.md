# PR 1 — Core Side-Effect Model

## Baseline checked
Current `fc002a583674d9e1734412c9df232e41d621549b` state shows:
- `TransactionSideEffectDispatcher` and `ReceiptSideEffectDispatcher` are still ad hoc dispatchers.
- `SideEffectDiagnosticRecorder` exists, but it swallows non-cancellation failures and returns `null`, so it is not a typed contract.
- `SideEffectMode` still forces caller discipline for deferred dispatch.
- `EventOutcome` already includes `SIDE_EFFECT_STARTED`, `SKIPPED`, `FAILED_RETRYABLE`, `FAILED_FINAL`, `CANCELLED`, so no new DB event schema is needed.
- `DiagnosticReasonCode` already has most skip/failure reasons we need.
- Existing pipeline diagnostics already persist through `DiagnosticEventWriter` / `PipelineDiagnosticEvent`.

## Goal
Introduce a universal, typed side-effect contract that represents:
- exactly-once intent
- post-commit execution
- structured outcomes
- safe metadata
- batch ownership for later outer/inner coordinator flows

This PR should **not** migrate existing coordinators yet.

## Non-goals
- No callsite migration in coordinators/services
- No removal of `SideEffectMode`
- No new database tables
- No planner refactor of transaction/receipt/recurring logic
- No worker integration yet
- No static guards yet

---

## New package
Use a neutral package:

- `app/src/main/java/com/yourname/expensetracker/domain/sideeffect/`

---

## Files to add

### Core enums/value objects
- `SideEffectCategory.kt`
- `SideEffectTriggerType.kt`
- `SideEffectOutcome.kt`
- `SideEffectSkipReason.kt`
- `SideEffectPriority.kt`
- `PostCommitAction.kt`
- `PostCommitActionBatch.kt`
- `SideEffectActionResult.kt`
- `SideEffectBatchResult.kt`
- `SideEffectExecutionContext.kt`

### Runtime
- `PostCommitActionRunner.kt`
- `PostCommitActionRunnerImpl.kt`
- `SideEffectEventWriter.kt`
- `DiagnosticSideEffectEventWriter.kt`
- `SideEffectMetadataFactory.kt`

### Tests
- `PostCommitActionRunnerTest.kt`
- `PostCommitActionBatchTest.kt`
- `SideEffectMetadataFactoryTest.kt`
- `DiagnosticSideEffectEventWriterTest.kt`

---

## Contract design

### `SideEffectOutcome`
Use the doc’s sealed model:
- `Completed`
- `Skipped(reason)`
- `FailedRetryable(reason, errorClass?)`
- `FailedFinal(reason, errorClass?)`
- `Cancelled(reason?)`

### `SideEffectSkipReason`
Include:
- `NOT_APPLICABLE`
- `PRIVACY_DENIED`
- `RESTORE_BLOCKED`
- `MISSING_ENTITY`
- `ALREADY_PROCESSED`
- `DISABLED_BY_SETTINGS`
- `LOW_CONFIDENCE`
- `NO_WORK`
- `DUPLICATE`
- `PERMISSION_DENIED`

### `SideEffectCategory`
Keep broad, future-facing categories:
- `BUDGET`
- `ANALYTICS`
- `ANOMALY`
- `MERCHANT_LEARNING`
- `RECURRING`
- `RECEIPT_MATCHING`
- `RECEIPT_ITEM_CATEGORIZATION`
- `WARRANTY`
- `PRICE_PROTECTION`
- `NOTIFICATION_DELIVERY`
- `AI_RECOMMENDATION`
- `CACHE_INVALIDATION`
- `WORKER_SCHEDULING`
- `EXPORT_IMPORT`
- `BANK_SYNC`

### `SideEffectTriggerType`
Use the doc’s trigger list:
- `EXPENSE_CREATED`, `EXPENSE_UPDATED`, `EXPENSE_DELETED`, `EXPENSE_BULK_UPDATED`
- `RECEIPT_SAVED`, `RECEIPT_LINKED`, `RECEIPT_UNLINKED`
- `RECURRING_OCCURRENCE_PAID`, `RECURRING_RULE_UPDATED`, `REMINDER_CLAIMED`
- `BUDGET_UPDATED`, `FORECAST_GENERATED`
- `IMPORT_COMPLETED`, `BANK_SYNC_COMPLETED`, `WORKER_COMPLETED`

---

## `PostCommitAction`
This is the executable description of one side effect.

Recommended fields:
- `pipeline: AppPipeline`
- `name: String`
- `category: SideEffectCategory`
- `triggerType: SideEffectTriggerType`
- `targetEntityType: String`
- `targetEntityId: Long?`
- `source: String`
- `correlationId: String?`
- `causationId: String?`
- `idempotencyKey: String`
- `priority: SideEffectPriority`
- `metadata: SafeEventMetadata`
- `execute: suspend SideEffectExecutionContext.() -> SideEffectOutcome`

Important rules:
- `execute` runs only post-commit.
- `idempotencyKey` is required.
- no raw payloads in `metadata`.
- `priority` is informational in PR1; ordering stays sequential.

---

## `PostCommitActionBatch`
Batch semantics:
- holds one `correlationId`
- preserves action order
- supports `plus`
- supports `empty(correlationId)`
- should offer `normalized()` / dedupe by `idempotencyKey`

Normalization rule:
- duplicate keys in the same batch become `Skipped(DUPLICATE)` or are removed before execution.

---

## `SideEffectExecutionContext`
Should carry:
- `correlationId`
- `action`
- safe mutable metadata accumulation
- `checkpoint(label)`
- `recordMetadata(metadata)`

PR1 behavior:
- `checkpoint()` is a safe breadcrumb hook, not a DB migration feature yet.
- `recordMetadata()` only enriches the current action’s safe metadata.

---

## Runtime contract

### `PostCommitActionRunner`
Responsibilities:
1. execute actions sequentially
2. emit `SIDE_EFFECT_STARTED`
3. map typed outcomes to diagnostic events
4. emit terminal event for every action
5. continue after non-cancellation failures
6. rethrow `CancellationException` after logging cancellation
7. return `SideEffectBatchResult`

### `SideEffectBatchResult`
Counts:
- `completed`
- `skipped`
- `failedRetryable`
- `failedFinal`
- `cancelled`

Also return:
- ordered `SideEffectActionResult` list

### Exception policy
- `CancellationException` → emit cancelled, rethrow
- any other thrown exception → convert to `FailedRetryable` unless the action already returned a typed failure
- one failed action must not stop the rest of the batch

---

## Event writing

### `SideEffectEventWriter`
Adapter over existing `DiagnosticEventWriter`.

It should emit:
- start events with `EventOutcome.SIDE_EFFECT_STARTED`
- terminal events with:
  - `COMPLETED`
  - `SKIPPED`
  - `FAILED_RETRYABLE`
  - `FAILED_FINAL`
  - `CANCELLED`

### `DiagnosticSideEffectEventWriter`
Must write safe metadata only:
- action name
- category
- trigger type
- target entity type/id
- source
- correlationId
- causationId
- hashed idempotency key
- skip/failure reason
- priority

It must not persist raw payloads, merchant text, notes, OCR bodies, or external IDs.

---

## Mapping to existing diagnostics
Use existing `DiagnosticReasonCode` where possible:
- `PRIVACY_DENIED`
- `RESTORE_BLOCKED`
- `DUPLICATE`
- `PERMISSION_DENIED`
- `FILTER_REJECTED`
- `BLOCKED_PACKAGE`
- `UNKNOWN_ERROR`
- `SIDE_EFFECT_EXCEPTION`
- `CANCELLED_BY_SYSTEM`

This keeps PR1 schema-free.

---

## Compatibility rule
Do **not** remove or rewrite:
- `SideEffectDiagnosticRecorder`
- `TransactionSideEffectDispatcher`
- `ReceiptSideEffectDispatcher`
- `SideEffectMode`

They remain legacy compatibility paths until PR2+.

PR1 only introduces the typed contract and runner.

---

## Recommended implementation order
1. Add enums/value objects.
2. Add `PostCommitAction` / batch / result types.
3. Add metadata factory.
4. Add `DiagnosticSideEffectEventWriter`.
5. Add `PostCommitActionRunnerImpl`.
6. Add unit tests for all terminal outcomes and batch behavior.
7. Add DI binding if your Hilt graph needs one for the new writer interface.

---

## Tests

### Runner behavior
- emits started/completed
- emits skipped with reason
- emits failed retryable
- emits failed final
- emits cancelled and rethrows
- continues after a non-cancellation failure
- preserves action order

### Batch behavior
- empty batch works
- plus/merge works
- duplicate idempotency keys are normalized

### Metadata safety
- no raw external IDs
- no raw merchant/body/OCR text
- hashed idempotency key only
- safe reason codes only

### Mapping tests
- `SideEffectOutcome` → diagnostics outcome mapping
- skip reasons map to allowed diagnostic reason codes

---

## Acceptance criteria
PR1 is done when:
- the app has a typed, reusable side-effect contract
- side effects can be described as post-commit actions with idempotency keys
- terminal outcomes are typed and observable
- diagnostic events for side effects are standardized
- no schema migration or callsite migration is required yet

---

## Sources used
- Current commit: https://github.com/panospao7/Cost-agregator/commit/fc002a583674d9e1734412c9df232e41d621549b
- `TransactionLifecycleCoordinator.kt`
- `TransactionSideEffectDispatcher.kt`
- `ReceiptLifecycleCoordinator.kt`
- `ReceiptSideEffectDispatcher.kt`
- `SideEffectMode.kt`
- `SideEffectDiagnosticRecorder.kt`
- `DiagnosticEventWriter.kt`
- `EventOutcome.kt`
- `DiagnosticReasonCode.kt`
- `AppPipeline.kt`
- Global contract doc: `global_side_effect_dispatch_contract_plan.md`