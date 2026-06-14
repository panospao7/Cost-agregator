# Master Debug/Review Prompt — Pipeline 2: Transaction Lifecycle

You are an expert Kotlin/Android architecture debugger and reviewer. Your task is to perform an extensive debug/review of **Pipeline 2 — Transaction Lifecycle** in this repository.

## 0. Target repository and version

Repository:

```text
https://github.com/panospao7/Cost-agregator
```

Pinned commit:

```text
83b798e849b4408b2bf683f52cb2746d37f7af16
```

Do not review a different branch/commit unless explicitly told. If the local checkout differs, stop and report the mismatch.

## 1. Mission

Review **Pipeline 2 — Transaction Lifecycle** end-to-end.

Primary goals:

1. Understand the actual runtime flow for expense create/update/delete/bulk mutation.
2. Verify that every transaction mutation follows the legal architecture path.
3. Reconcile P2 issue docs, master tracker claims, architecture docs, and actual code.
4. Find remaining data-integrity, lifecycle-bypass, restore-safety, side-effect, source-link, currency, dedupe, audit, concurrency, or test gaps.
5. Produce a structured issue report with exact evidence and recommended fixes.

This is a **deep code audit**, not a docs summary.

## 2. Important warning about docs/status drift

Read both:

```text
docs/analyses and debug master/PIPELINE_2_CONSOLIDATED_ISSUES.md
docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md
```

The P2 consolidated file may mention older names such as `ExpenseWriteStore.kt`. At pinned commit, the current legal owner appears to be:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
```

If `ExpenseWriteStore.kt` exists in the checkout, inspect it. If it does not exist, report the issue doc as stale and map each issue to the current owner, usually `TransactionLifecycleCoordinator`, `ExpenseRepository`, or DAO methods.

Do **not** trust either tracker blindly. Reconcile:

- pipeline issue doc,
- master tracker,
- architecture docs,
- actual source code,
- actual tests.

If tracker and code disagree, report **doc/code drift** or **tracker/status drift**.

## 3. Docs to read first

Read these pipeline/debug docs:

```text
docs/analyses and debug master/PIPELINE_2_CONSOLIDATED_ISSUES.md
docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md
docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md
```

Read these architecture docs:

```text
docs/architecture/ARCHITECTURE.md
docs/architecture/CODEBASE_SEGMENTS.md
docs/architecture/DEPENDENCY_MAP.md
docs/architecture/LEGAL_PATHS.md
docs/architecture/ENGINE_INTERACTION_MAP.md
docs/architecture/COMPLETE-BACKEND-MAP.md
docs/architecture/BACKEND-MAP-INDEX.md
docs/architecture/CODEBASE_INVENTORY.md
docs/architecture/dao-map.md
docs/architecture/hilt-bindings-map.md
docs/architecture/import-graph.json
```

Read these cross-cutting docs:

```text
docs/DB_WRITE_OWNERSHIP.md
docs/expense-mutation-inventory.md
docs/DATABASE_BASELINE_POLICY.md
docs/backup-restore-barrier-contract.md
docs/SENSITIVE_DIAGNOSTICS_POLICY.md
docs/PRIVACY_UI_ARCHITECTURE.md
```

If any listed doc does not exist, note it and continue.

## 4. Pipeline definition

Pipeline 2 covers **Transaction Lifecycle**:

```text
Expense create/update/delete request
→ validation
→ restore/write barrier
→ normalization
→ merchantKey/dedupeKey calculation
→ duplicate/idempotency check
→ Room transaction
→ ExpenseDao mutation
→ TransactionEvent audit write
→ source-link/provenance write if applicable
→ post-commit side-effect planning
→ PostCommitActionRunner execution
→ diagnostics / lifecycle result
```

Expected operations include:

- manual expense create,
- notification auto-accept create,
- review approval create,
- receipt/email/bank/import/group/recurring create,
- full expense update,
- category update,
- merchant update,
- transaction type update,
- transfer metadata update,
- ownership/shared/not-mine update,
- location update,
- business/tax field update,
- bulk category update,
- bulk merchant update,
- delete expense,
- debug-only delete/restore/snapshot paths.

Expected terminal outcomes include:

- created,
- duplicate skipped,
- validation failed,
- insert conflict,
- updated,
- bulk updated,
- deleted,
- blocked by restore/write barrier,
- side-effect failed after commit,
- source-link/provenance failure,
- error.

## 5. Initial source file inventory

Start with these production files, then expand using `rg`, imports, callers, and tests.

### Core lifecycle owner

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleEventWriter.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/DebugExpenseAuditWriter.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionUpdateKind.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/BulkChangedField.kt
```

### Transaction domain models

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseResult.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/DeduplicationMode.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/ExpenseSource.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/SideEffectMode.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/ExpenseUpdates.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/BusinessExpensePatch.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/BusinessExpenseUpdateResult.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/DefaultExpenseCategoryAssignmentService.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/ExpenseCategoryAssignmentPort.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/SourceLearningPolicy.kt
```

### Validation

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/validation/TransactionValidator.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/validation/TransactionValidationError.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/validation/TransactionDatePolicy.kt
```

### Core repositories / callsite adapters

```text
app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt
app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt
app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt
app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt
app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt
app/src/main/java/com/yourname/expensetracker/data/repository/BusinessExpenseRepository.kt
```

Also search for/import-review:

```text
CsvExpenseImporter
JsonExpenseImporter
EmailReceiptIngestionService
BankApiIntegration
GroupTransactionCoordinator
GroupLifecycleCoordinator
RecurringLifecycleCoordinator
ReceiptLifecycleCoordinator
ReceiptLinkService
FinancialRescueCoordinator
```

### DAOs

```text
app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/RestrictedExpenseDaoMutation.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/TransactionEventDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/EntitySourceLinkDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/PipelineDiagnosticEventDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/UserCorrectionDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt
```

### Entities

```text
app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionType.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/PaymentMethod.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/TransferDirection.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/EntitySourceLink.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/PipelineDiagnosticEvent.kt
```

### Database/migrations/schema

```text
app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt
app/src/main/java/com/yourname/expensetracker/data/database/DatabaseMigrations.kt
app/schemas/com.yourname.expensetracker.data.database.AppDatabase/
```

Confirm current DB schema baseline/version and whether `expenses`, `transaction_events`, and `entity_source_links` have required indexes/constraints.

### Source-link / provenance

```text
app/src/main/java/com/yourname/expensetracker/domain/provenance/CreateExpenseSourceLinkMapper.kt
app/src/main/java/com/yourname/expensetracker/domain/provenance/CreateExpenseSourceLinkRequirements.kt
app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkWriter.kt
app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkWriteResult.kt
app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkWriteException.kt
app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkPayload.kt
app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkEventMetadataBuilder.kt
app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkFallbackPolicy.kt
app/src/main/java/com/yourname/expensetracker/domain/provenance/DuplicateSourceLinkPolicy.kt
```

### Side-effect framework

```text
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/PostCommitActionRunner.kt
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/PostCommitActionRunnerImpl.kt
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/PostCommitActionBatch.kt
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/MutationResult.kt
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/SideEffectTriggerType.kt
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/SideEffectPriority.kt
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/SideEffectCategory.kt
```

### Currency / dedupe / merchant normalization

```text
app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt
app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencySettingsRepository.kt
app/src/main/java/com/yourname/expensetracker/data/repository/CurrencySettingsRepositoryImpl.kt
app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt
app/src/main/java/com/yourname/expensetracker/domain/intelligence/DuplicateDetectionPolicy.kt
app/src/main/java/com/yourname/expensetracker/domain/util/MerchantKeyGenerator.kt
```

### Restore/write barriers

```text
app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt
app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseReadBarrier.kt
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt
app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseAccessBlockedException.kt
```

### UI/ViewModel entry points to trace

Search and inspect relevant methods in:

```text
app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/
app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/
app/src/main/java/com/yourname/expensetracker/ui/screens/review/
app/src/main/java/com/yourname/expensetracker/ui/screens/receipt/
app/src/main/java/com/yourname/expensetracker/ui/screens/groups/
app/src/main/java/com/yourname/expensetracker/ui/screens/bank/
```

Do not assume this list is complete. Build the final inventory by tracing all callers/callees.

## 6. Tests to inspect

Start with:

```text
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorUpdateTest.kt
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlannerTest.kt
app/src/test/java/com/yourname/expensetracker/domain/transaction/category/CategoryAssignmentServiceBarrierTest.kt
app/src/test/java/com/yourname/expensetracker/architecture/ExpenseDaoMutationAccessTest.kt
app/src/test/java/com/yourname/expensetracker/architecture/WriteBarrierArchitectureGuardTest.kt
app/src/test/java/com/yourname/expensetracker/architecture/DeprecatedApiArchitectureGuardTest.kt
app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt
app/src/test/java/com/yourname/expensetracker/contracts/LifecycleBarrierContractTest.kt
app/src/test/java/com/yourname/expensetracker/contracts/MoneyContractTest.kt
app/src/test/java/com/yourname/expensetracker/contracts/SideEffectContractTest.kt
```

Then search for additional tests:

```bash
rg -n "TransactionLifecycleCoordinator|CreateExpenseRequest|CreateExpenseResult|TransactionEvent|LifecycleEventType|DuplicateSkipped|InsertConflict|STRICT_EXTERNAL_ID|DeduplicationMode|RestrictedExpenseDaoMutation|ExpenseDaoMutation|updateMerchant|bulkUpdateMerchant|bulkUpdateCategory|updateBusiness|baseAmount|correlationId|SourceLink|PostCommitAction|DatabaseWriteBarrier" app/src/test app/src/androidTest
```

## 7. Required search commands

Run broad searches before finalizing conclusions:

```bash
rg -n "TransactionLifecycleCoordinator|createExpenseStandalone|createExpenseDbOnly|createExpenseStandaloneV2|createExpenseDbOnlyV2|updateExpense|updateCategory|updateMerchant|updateType|updateTransfer|updateOwnership|updateLocation|bulkUpdate|deleteExpense" app/src/main app/src/test app/src/androidTest

rg -n "ExpenseDao\\.|expenseDao\\.(insert|insertAtomic|insertAll|update|delete|deleteAll|updateCategory|updateMerchant|updateMerchantForMerchant|updateMerchantAndKey|updateTransactionType|updateDedupeKey|updateTransfer|updateIsNotMine|updateOwnerName|updateIsSharedExpense|updateSharedWithName|updateMyShare|clearSharedExpenseFlags)" app/src/main app/src/test app/src/androidTest

rg -n "RestrictedExpenseDaoMutation|EXPENSE_DAO_MUTATION_ALLOWLIST|BuildConfig.DEBUG|SKIP_FOR_DEBUG_RESTORE|DEBUG_DELETE_ALL_EXPENSES|RESTORED_FROM_DEBUG_SNAPSHOT" app/src/main app/src/test config scripts

rg -n "DatabaseWriteBarrier|DatabaseReadBarrier|RestoreMaintenanceMode|checkWritesAllowed|checkReadsAllowed|DatabaseAccessBlockedException|restore|maintenance" app/src/main app/src/test

rg -n "TransactionEvent|LifecycleEventType|CREATE_ATTEMPTED|CREATED|CREATE_VALIDATION_FAILED|CREATE_DUPLICATE_SKIPPED|CREATE_INSERT_CONFLICT|UPDATED|BULK_UPDATED|DELETED|UPDATE_VALIDATION_FAILED|SOURCE_LINKED|SIDE_EFFECT_FAILED" app/src/main app/src/test

rg -n "withTransaction|beforeSnapshot|afterSnapshot|snapshot|TOCTOU|DuplicateUpdateException|isDuplicateCurrencyAware|findDuplicateIdCurrencyAware|findIdByDedupeKey|dedupeKey|merchantKey|STRICT_EXTERNAL_ID|idempotencyKey|externalFingerprint" app/src/main app/src/test

rg -n "baseAmount|baseCurrency|exchangeRateUsed|CurrencyConverter|homeCurrency\\(\\)|Flow.first\\(|withTimeout|CurrencySettingsRepository" app/src/main app/src/test

rg -n "SourceLinkWriter|CreateExpenseSourceLinkMapper|CreateExpenseSourceLinkRequirements|SourceLinkPayload|EntitySourceLinkDao|sourceLinkFallbackPolicy|SOURCE_LINKED|SourceLinkWriteException" app/src/main app/src/test

rg -n "TransactionSideEffectPlanner|TransactionSideEffectDispatcher|PostCommitActionRunner|runBestEffortAfterCommit|planCreated|planUpdated|planDeleted|SideEffectTriggerType|MutationResult" app/src/main app/src/test

rg -n "CancellationException|catch \\(e: Exception\\)|runCatching|NonCancellable|SupervisorJob|launch|async" app/src/main/java/com/yourname/expensetracker/domain/transaction app/src/main/java/com/yourname/expensetracker/data/repository app/src/main/java/com/yourname/expensetracker/domain/sideeffect app/src/test
```

## 8. Previous P2 issues to verify

Verify each issue from the P2 issue doc and master tracker against actual code.

At minimum check:

### Old issues

```text
P2-P1-01 — updateBusinessTaxFields() misses restore guard
P2-P1-02 — Failed creates invisible in transaction_events
P2-P1-03 — STRICT_EXTERNAL_ID returns weak InsertConflict
P2-P1-04 — Debug/restore methods bypass lifecycle
P2-P1-05 — Public DAO mutation surface enables lifecycle bypass
```

### New issues from deep audit

```text
NEW-P2-001 — TOCTOU race in updateExpense — beforeSnapshot outside transaction
NEW-P2-002 — Same TOCTOU in 6 other update methods
NEW-P2-003 — deleteExpense(Expense) uses stale caller entity for snapshot
NEW-P2-004 — Non-atomic duplicate check in updateExpense
NEW-P2-005 — DefaultExpenseCategoryAssignmentService bypasses lifecycle
NEW-P2-006 — NotificationRepository.deleteAll() bypasses audit trail
NEW-P2-007 — Currency conversion failure leaves stale baseAmount
NEW-P2-008 — DAO exposes updateMerchantForMerchant that nulls or stales dedupeKey
NEW-P2-009 — Planner hardcodes EXPENSE_CREATED trigger for update paths
NEW-P2-010 — Inconsistent event-write guard between bulkUpdateCategory overloads
NEW-P2-011 — updateLocation missing correlationId
NEW-P2-012 — updateMerchant missing correlationId in event
NEW-P2-013 — updateType missing correlationId in event
NEW-P2-014 — updateMerchant doesn't update merchantKey/dedupeKey
NEW-P2-015 — Bulk idempotency keys non-unique across time
NEW-P2-016 — Flow.first() could hang indefinitely for currency settings
```

For each, report:

```text
ID
claimed status in P2 doc
claimed status in master tracker
actual status in code
evidence: file + function + line range
test coverage
remaining gap, if any
```

## 9. Legal-path invariants to enforce

The required architecture path is:

### Create expense

```text
Any source
→ TransactionLifecycleCoordinator.createExpense*()
→ validation
→ DatabaseWriteBarrier
→ dedupe/idempotency
→ database.withTransaction
→ ExpenseDao.insertAtomic()
→ TransactionEvent CREATED / validation / duplicate / conflict event
→ source links if applicable
→ post-commit side effects via planner/runner
```

Forbidden:

```text
ExpenseDao.insert() from repositories directly
ExpenseDao.insertAll() outside debug/migration/import owner
Expense creation without TransactionEvent
Expense creation without restore/write barrier
Expense creation without dedupe/idempotency policy
Source-linked creation without EntitySourceLink/provenance when required
```

### Update expense

```text
→ TransactionLifecycleCoordinator.updateCategory/updateMerchant/updateType/updateExpense/etc.
→ DatabaseWriteBarrier
→ load beforeSnapshot inside transaction
→ mutate ExpenseDao
→ TransactionEvent UPDATED/BULK_UPDATED
→ post-update side effects where appropriate
```

Forbidden:

```text
ExpenseDao.update() from repositories directly
ExpenseDao.updateCategory()/updateMerchant()/updateType() outside coordinator unless explicitly allowlisted
beforeSnapshot read outside same write transaction
key-field update without merchantKey/dedupeKey recomputation
update without TransactionEvent unless documented maintenance/backfill exception
```

### Delete expense

```text
→ TransactionLifecycleCoordinator.deleteExpense()
→ DatabaseWriteBarrier
→ load snapshot inside transaction
→ TransactionEvent DELETED/BULK_DELETED
→ delete row
→ post-delete side effects
```

Forbidden:

```text
ExpenseDao.delete() from repositories directly
delete using stale caller-supplied entity as authoritative snapshot
delete without lifecycle event
delete during restore/maintenance mode
```

## 10. Universal contracts to audit for P2

### Restore/write barrier

Verify:

- every mutating public method in `TransactionLifecycleCoordinator` checks `DatabaseWriteBarrier`,
- repository wrappers do not bypass the coordinator,
- debug-only operations are both `BuildConfig.DEBUG` guarded and barrier-guarded,
- bulk updates are guarded,
- category assignment service is guarded,
- source-link/provenance writes are guarded via the owning transaction or writer,
- blocked writes emit diagnostic/lifecycle evidence where expected,
- no DB write occurs during non-NORMAL maintenance mode.

### Transaction lifecycle ownership

Verify:

- all expense create/update/delete/bulk flows route through coordinator,
- every direct DAO mutation is either impossible, test-only, debug-only, or explicitly allowlisted,
- `@RestrictedExpenseDaoMutation` is meaningful and enforced by architecture tests,
- `config/db_access_allowlist.yml` does not allow dangerous production bypasses,
- intentional bypasses are still safe and documented:
  - location backfill,
  - merchant key backfill,
  - receipt link category propagation,
  - group cleanup/normalization,
  - debug-only flows.

### Atomicity / transaction boundaries

Verify:

- beforeSnapshot and afterSnapshot are produced from transaction-consistent state,
- duplicate checks happen inside the same transaction as insert/update when needed,
- insert + CREATED event are atomic,
- update + UPDATED event are atomic,
- delete + DELETED event are atomic,
- source-link writes are either atomic with creation or safely post-commit by design,
- side effects never run before commit,
- no external/network I/O occurs inside Room transactions except where explicitly justified.

### Dedupe/idempotency

Verify:

- `DeduplicationMode.STANDARD`,
- `BULK_IMPORT`,
- `STRICT_EXTERNAL_ID`,
- `SKIP_FOR_DEBUG_RESTORE`,
- `idempotencyKey`,
- `externalFingerprint`,
- `dedupeKey`,
- `merchantKey`.

Questions:

- Can one source retry create duplicate expenses?
- Can two distinct expenses collapse to one?
- Does strict external ID resolve to existing ID on retry?
- Is `insertAtomic` conflict handled deterministically?
- Are bulk idempotency keys stable and unique enough?
- Are merchant/type/date/amount/currency updates recomputing dedupe keys?

### Currency/base amount

Verify:

- create populates `baseAmount`, `baseCurrency`, `exchangeRateUsed`,
- update refreshes or clears stale base conversion snapshot,
- failed conversion does not preserve stale converted values,
- same-currency conversion uses rate 1.0,
- `Flow.first()` / settings reads cannot hang indefinitely,
- cancellation is propagated,
- downstream `Expense.normalizedAmount`/analytics fallback behavior is safe.

### Source-link/provenance

Verify:

- every source-specific create provides required source-link fields or explicitly uses safe fallback policy,
- missing required provenance fields fail validation if policy requires,
- source links are not orphaned,
- source-link failure behavior is correct:
  - rollback if required,
  - durable diagnostic if deferred/best-effort,
  - no partially-created untraceable expenses.
- duplicate create from same source does not create duplicate links incorrectly.

### Side effects

Verify:

- create uses `planCreated`,
- update uses `planUpdated`,
- delete uses `planDeleted`,
- update paths do not use create trigger types,
- side effects are post-commit only,
- side-effect failure is best-effort and recorded/logged if required,
- recurring reconciliation is triggered only for update kinds that affect matching,
- side effects do not mutate DB through illegal paths.

### Diagnostics/lifecycle events

Verify every terminal lifecycle result has durable evidence:

- create attempted,
- validation failed,
- duplicate skipped,
- insert conflict,
- created,
- source linked,
- updated,
- bulk updated,
- update validation failed,
- deleted,
- bulk deleted,
- restore/write blocked,
- side-effect failed.

### Privacy/security

P2 is not primarily privacy, but still verify:

- transaction metadata does not store raw PII unnecessarily,
- diagnostic/lifecycle metadata is safe,
- source fields from notification/email/bank/import are not logged raw,
- stack traces/exceptions in diagnostics are redacted by policy.

## 11. Deep review checklist

### Create path

Check:

- validation before insert,
- date policy,
- amount and currency validation,
- transaction type and transfer field consistency,
- ownership normalization,
- business/tax flags,
- source mapping,
- dedupe mode,
- strict external ID,
- conflict resolution,
- source-link requirements,
- lifecycle events,
- side effects.

Questions:

- Can invalid creates still write expenses?
- Can validation failure be invisible?
- Can insert conflict return only weak information?
- Can source links fail after expense commit?
- Can retries create duplicates?
- Can debug restore skip too much validation in production?

### Update path

Check:

- `updateExpense`,
- `updateCategory`,
- `updateLocation`,
- `updateBusinessExpensePatch`,
- `updateBusinessFlags`,
- `updateMerchant`,
- `updateType`,
- `updateTransferDetails`,
- `updateTypeAndTransferDetails`,
- `updateOwnership`,
- bulk methods.

Questions:

- Is the old row loaded inside the transaction?
- Is the event snapshot accurate?
- Are no-op updates correctly handled?
- Are key-field changes recomputing merchant/dedupe keys?
- Are duplicate collisions rejected?
- Are correlation IDs propagated?
- Are side effects appropriate per update kind?
- Are validation failures durable?

### Delete path

Check:

- delete by ID,
- delete by entity,
- debug delete all,
- bulk delete if any,
- group/receipt/import deletion interactions.

Questions:

- Is snapshot loaded inside transaction?
- Does the event persist before/with delete?
- Are cascade/FK effects safe?
- Are side effects after commit?
- Are debug operations release-safe?

### Direct DAO mutation audit

Inspect every `ExpenseDao` mutating method and caller.

Classify each as:

```text
LEGAL — called only by TransactionLifecycleCoordinator
DEBUG — BuildConfig.DEBUG + barrier + audit
MAINTENANCE/BACKFILL — allowlisted + low-value columns only + barrier
TEST-ONLY — src/test or fake only
BUG — production bypass
UNKNOWN — needs investigation
```

Special attention:

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
updateCategoryForCategory
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
```

### Bulk mutation audit

Check:

- bulk category updates,
- bulk merchant updates,
- affected row loading,
- per-row snapshots,
- aggregate event metadata,
- idempotency keys,
- recurring reconciliation,
- merchant/category learning side effects,
- crash/partial failure behavior.

### Cross-pipeline create callers

For every source, confirm it calls the coordinator legally:

```text
P1 notification auto-accept
P3 receipt scan / receipt-created expense
P4 recurring generated expense
P10 bank sync / bank statement review
P11 email receipt ingestion
P12 CSV/JSON import
groups/shared expense flows
manual UI add expense
review approval
financial rescue/import
```

For each, verify:

- source enum is correct,
- idempotency/fingerprint is passed,
- source link fields are present,
- side-effect mode / DB-only mode is correct,
- outer transaction does not execute side effects before commit.

## 12. Code-reading rules

Follow these rules strictly:

1. Do not trust docs over code.
2. Do not trust tracker statuses over code.
3. If docs and code disagree, report the mismatch.
4. Do not review only filenames; open implementation and tests.
5. Trace real runtime flow, not package structure.
6. Search direct and indirect callers.
7. Include cross-pipeline dependencies.
8. Mark uncertainty clearly.
9. Every finding must have file/function evidence.
10. Do not invent bugs without evidence.
11. If something is safe by design, explain why.
12. If a bypass is intentional, verify the documented guard actually exists.
13. If a TODO is harmless, classify as P3/design.
14. If a TODO can cause data loss/corruption/duplicate money records, classify by impact.
15. Prefer minimal, architecture-consistent fixes.

## 13. Required output format

Produce the final report in this structure:

```markdown
# Pipeline 2 — Transaction Lifecycle Debug/Review Report

## 1. Executive verdict

Verdict: GREEN / YELLOW / RED

One-paragraph summary.

Highest-risk remaining issue:
Production safety assessment:

## 2. Pipeline flow summary

Describe actual runtime flow.

Include compact text or Mermaid flow diagram.

## 3. Files reviewed

### Production files reviewed

| File | Role | Notes |
|---|---|---|

### Test files reviewed

| File | Coverage area | Notes |
|---|---|---|

### Files intentionally skipped

| File | Reason |
|---|---|

## 4. Architecture/doc comparison

| Area | Architecture expectation | Actual code | Status |
|---|---|---|---|

Include:
- doc/code drift,
- tracker/status drift,
- stale filenames,
- missing docs,
- source-code truth.

## 5. Previous issue reconciliation

| Issue ID | P2 doc status | Master status | Actual code status | Evidence | Test coverage | Notes |
|---|---|---|---|---|---|---|

## 6. Direct DAO mutation inventory

| DAO method | Caller | Classification | Guard | Lifecycle event? | Safe? | Evidence |
|---|---|---|---|---|---|---|

## 7. Cross-pipeline create/update caller inventory

| Source/caller | Operation | Coordinator API used? | Dedupe/idempotency | Source link/provenance | Side-effect mode | Status |
|---|---|---|---|---|---|---|

## 8. New findings

| ID | Severity | Type | Title | File(s) | Evidence | Impact | Reproduction path | Recommended fix | Required tests | Cross-pipeline impact |
|---|---|---|---|---|---|---|---|---|---|---|

## 9. Universal contract audit

### Restore/write barrier
Status:
Evidence:
Gaps:

### Transaction lifecycle ownership
Status:
Evidence:
Gaps:

### Atomicity / transaction boundaries
Status:
Evidence:
Gaps:

### Dedupe/idempotency
Status:
Evidence:
Gaps:

### Currency/base amount
Status:
Evidence:
Gaps:

### Source-link/provenance
Status:
Evidence:
Gaps:

### Side effects
Status:
Evidence:
Gaps:

### Diagnostics/lifecycle events
Status:
Evidence:
Gaps:

### Privacy/security
Status:
Evidence:
Gaps:

## 10. Test coverage assessment

| Behavior | Existing test? | Missing test? | Recommended test |
|---|---|---|---|

## 11. Recommended fix plan

Split fixes into safe PRs:

### PR 1 — Critical data-integrity/lifecycle ownership
### PR 2 — Atomicity/dedupe/currency correctness
### PR 3 — Source-link/provenance and side effects
### PR 4 — Architecture guards/tests/docs drift

## 12. Final production-readiness decision

GREEN/YELLOW/RED with justification.
```

## 14. Severity rubric

Use this rubric:

```text
P0 — data loss, data corruption, duplicate money records, privacy leak, restore/write bypass, irreversible wrong write.
P1 — lifecycle bypass, missing TransactionEvent on critical mutation, race causing duplicate/corrupt record, missing barrier on production write, source-link orphan risk.
P2 — edge-case duplicate/idempotency weakness, stale baseAmount, poor diagnostics, partial side-effect inconsistency, non-critical race.
P3 — cleanup, docs drift, stale TODO, minor maintainability issue.
```

## 15. Validation commands

Run as much as possible locally:

```bash
git rev-parse HEAD
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Focused tests:

```bash
./gradlew testDebugUnitTest --tests "*TransactionLifecycleCoordinatorTest*"
./gradlew testDebugUnitTest --tests "*TransactionLifecycleCoordinatorUpdateTest*"
./gradlew testDebugUnitTest --tests "*TransactionSideEffectPlannerTest*"
./gradlew testDebugUnitTest --tests "*CategoryAssignmentServiceBarrierTest*"
./gradlew testDebugUnitTest --tests "*ExpenseDaoMutationAccessTest*"
./gradlew testDebugUnitTest --tests "*WriteBarrierArchitectureGuardTest*"
./gradlew testDebugUnitTest --tests "*LifecycleBarrierContractTest*"
./gradlew testDebugUnitTest --tests "*MoneyContractTest*"
./gradlew testDebugUnitTest --tests "*SideEffectContractTest*"
```

If build/test cannot run, report why and still perform static review.

## 16. Completion criteria

The review is not complete until:

- P2 consolidated issue doc was read.
- Master tracker was read.
- Universal tracker was read.
- Architecture legal paths were checked.
- DB write ownership map was checked.
- Expense mutation inventory was checked.
- P2 production source files were inventoried.
- P2 tests were inventoried.
- All create/update/delete/bulk terminal paths were traced.
- All direct `ExpenseDao` mutation callers were classified.
- Restore/write barrier was audited.
- TransactionEvent coverage was audited.
- Snapshot atomicity was audited.
- Dedupe/idempotency was audited.
- Currency/baseAmount behavior was audited.
- Source-link/provenance behavior was audited.
- Side-effect timing was audited.
- Previous P2 issues were reconciled against code.
- New findings have evidence and fix strategy.
- Missing tests are explicitly listed.
- Final verdict is given.

## 17. Source links for context

Commit:

```text
https://github.com/panospao7/Cost-agregator/commit/83b798e849b4408b2bf683f52cb2746d37f7af16
```

P2 issue registry:

```text
https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_2_CONSOLIDATED_ISSUES.md
```

Master tracker:

```text
https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
```

Architecture folder:

```text
https://github.com/panospao7/Cost-agregator/tree/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture
```

Legal paths:

```text
https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/LEGAL_PATHS.md
```

Dependency map:

```text
https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/DEPENDENCY_MAP.md
```

DB write ownership:

```text
https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/DB_WRITE_OWNERSHIP.md
```

Expense mutation inventory:

```text
https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/expense-mutation-inventory.md
```