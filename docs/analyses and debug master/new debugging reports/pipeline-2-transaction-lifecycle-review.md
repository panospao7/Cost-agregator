# Pipeline 2 — Transaction Lifecycle Debug/Review Report

## 1. Executive verdict

Verdict: **YELLOW**

The pinned target is commit `83b798e849b4408b2bf683f52cb2746d37f7af16`; GitHub shows the short commit `83b798e` and the same full commit context. ([github.com](https://github.com/panospao7/Cost-agregator/commit/83b798e849b4408b2bf683f52cb2746d37f7af16)) Static review shows the central lifecycle path is now mostly implemented in `TransactionLifecycleCoordinator`: create/update/delete operations generally pass through write barrier checks, Room transactions, `ExpenseDao` mutation, `TransactionEvent` audit writes, source-link handling, and post-commit side-effect planning. However, several audit/diagnostic and lifecycle-consistency gaps remain.

Highest-risk remaining issue: `deleteExpense(expense: Expense)` rereads the row inside the transaction, but if the row is missing it still returns success and runs post-delete side effects.

Production safety assessment: **mostly safe for normal create/update/delete**, but not GREEN because of remaining lifecycle evidence gaps, duplicate-event ambiguity, source-link duplicate policy drift, unannotated DAO mutation surfaces, and incomplete verification of all cross-pipeline callers/tests without local `rg`/Gradle.

## 2. Pipeline flow summary

Actual create flow:

```text
CreateExpenseRequest
→ TransactionLifecycleCoordinator.createExpense*()
→ write barrier
→ CREATE_ATTEMPTED event
→ validation
→ provenance requirements validation
→ merchantKey/dedupeKey generation
→ currency base snapshot
→ Room transaction
   → duplicate check for STANDARD/BULK_IMPORT
   → ExpenseDao.insertAtomic()
   → CREATED event
   → source-link writes
   → SOURCE_LINKED event
→ post-commit planCreated()
→ PostCommitActionRunner
```

Update flow:

```text
update API
→ write barrier
→ optional precomputed currency conversion
→ Room transaction
   → load before snapshot
   → validate/recompute keys if needed
   → DAO update
   → UPDATED/BULK_UPDATED event
→ post-commit planUpdated()/bulk plan
```

Delete flow:

```text
delete API
→ write barrier
→ Room transaction
   → load current row
   → DELETED event
   → ExpenseDao.delete()
→ post-commit planDeleted()
```

## 3. Files reviewed

### Production files reviewed

| File | Role | Notes |
|---|---|---|
| `TransactionLifecycleCoordinator.kt` | Core lifecycle owner | Main evidence for create/update/delete/bulk paths. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) |
| `ExpenseDao.kt` | DAO mutation surface | Restricted mutation annotations exist; some public mutation methods remain risky. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt)) |
| `RestrictedExpenseDaoMutation.kt` | DAO opt-in marker | Warning-level opt-in; CI architecture test is claimed as hard enforcement. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/database/dao/RestrictedExpenseDaoMutation.kt)) |
| `TransactionEventDao.kt` | Audit event DAO | Insert/query/delete event methods. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/database/dao/TransactionEventDao.kt)) |
| `EntitySourceLinkDao.kt` | Provenance DAO | Insert-ignore and lookup methods. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/database/dao/EntitySourceLinkDao.kt)) |
| `SourceLinkWriter.kt` / `SourceLinkWriterImpl.kt` | Source-link write port/impl | Writer does not open its own transaction; caller must wrap. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkWriter.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkWriterImpl.kt)) |
| `CreateExpenseSourceLinkMapper.kt` | Create provenance mapping | Maps request fields to source-link payloads. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/provenance/CreateExpenseSourceLinkMapper.kt)) |
| `CreateExpenseSourceLinkRequirements.kt` | Required provenance rules | Enforces required fields per source unless explicit links exist. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/provenance/CreateExpenseSourceLinkRequirements.kt)) |
| `SourceLinkEventMetadataBuilder.kt` | Safe metadata | Privacy-trimmed source-link event metadata. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkEventMetadataBuilder.kt)) |
| `TransactionSideEffectPlanner.kt` | Post-commit action planner | Correctly uses create/update/delete trigger types; bulk keys still use wall clock. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt)) |
| `PostCommitActionRunner*` | Side-effect runner | Best-effort runner rethrows cancellation and continues on action failure. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/sideeffect/PostCommitActionRunnerImpl.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/sideeffect/PostCommitActionRunnerExtensions.kt)) |
| `TransactionValidator.kt` | Validation | Validates amount, merchant, currency, future date, transfer fields, ownership, location. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/validation/TransactionValidator.kt)) |
| `CreateExpenseRequest/Result`, `DeduplicationMode`, `LifecycleEventType` | Domain API | Result set lacks explicit restore-blocked/side-effect-failed typed outcomes. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseResult.kt)) |
| `DefaultExpenseCategoryAssignmentService.kt` | Category assignment port impl | Still performs direct DAO update with non-enum event type. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/DefaultExpenseCategoryAssignmentService.kt)) |
| `ExpenseRepository.kt` | Primary wrapper/callsite adapter | Most mutations delegate to coordinator; backfill/debug exceptions remain. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt)) |
| `ManualExpenseRepository.kt` | Manual create | Uses coordinator DB-only create and runs actions post-commit. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt)) |
| `ReviewQueueRepository.kt` | Review approval | Coordinator is injected; full call body only partially rendered. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt)) |
| `NotificationProcessingPipeline.kt` | Notification auto-accept/review | Docs/source imports indicate expense creation via coordinator inside DB transaction. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt)) |

### Test files reviewed

Could not fetch/execute the listed tests within this environment. Test execution commands were **not run**.

### Files intentionally skipped / incomplete

| File/area | Reason |
|---|---|
| Full `rg` inventories | No shell/local checkout available. |
| Gradle tests/assemble | No build runtime available. |
| Some repository/cross-pipeline files | Web API call budget prevented full expansion of every file. |
| DB schema JSON/migrations | Not fully fetched; architecture docs indicate AppDatabase v147 but schema constraints were not independently verified. ([github.com](https://github.com/panospao7/Cost-agregator/commit/83b798e849b4408b2bf683f52cb2746d37f7af16)) |

## 4. Architecture/doc comparison

| Area | Architecture expectation | Actual code | Status |
|---|---|---|---|
| Commit pin | Review pinned commit only | Reviewed raw files at `83b798e...`; no local checkout to compare. | OK with limitation |
| Legal owner | `TransactionLifecycleCoordinator` owns `expenses` + `transaction_events` | DB ownership doc says same. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/DB_WRITE_OWNERSHIP.md)) Code mostly matches. | Mostly OK |
| Stale owner name | P2 issue doc references `ExpenseWriteStore.kt` | Current owner is `TransactionLifecycleCoordinator.kt`; no evidence `ExpenseWriteStore.kt` in opened files. | **Doc drift** |
| Tracker status | Master says all 12 pipelines audited/fixed, remaining mostly design/TODO. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md)) | P2 doc still says several P2 issues open and internally contradicts counts. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_2_CONSOLIDATED_ISSUES.md)) | **Tracker drift** |
| Business/tax update docs | ExpenseRepository comment says business/tax updates not implemented. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt)) | Coordinator has `updateBusinessExpensePatch()`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | **Doc/code drift** |
| Side-effect trigger issue | Universal tracker says fixed by U-PR8. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/UNIVERSAL_ISSUE_TRACKER.md)) | Planner uses `EXPENSE_UPDATED` on update paths. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt)) | Fixed |
| DB write owner rule | One owner + barrier required. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/DB_WRITE_OWNERSHIP.md)) | Main paths comply; backfill/debug exceptions exist; category assignment is a semi-bypass. | YELLOW |

## 5. Previous issue reconciliation

| Issue ID | P2 doc status | Master status | Actual code status | Evidence | Test coverage | Notes |
|---|---|---|---|---|---|---|
| P2-P1-01 | Fixed | Fixed | Fixed | `updateBusinessExpensePatch()` calls `checkWritesAllowed`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Not run | Old name stale. |
| P2-P1-02 | Fixed | Fixed | Fixed | Create writes `CREATE_ATTEMPTED`, validation failed, duplicate, conflict events. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Not run | Mutation events are atomic for created rows. |
| P2-P1-03 | Fixed | Fixed | Fixed | Strict mode requires idempotency/fingerprint and resolves conflict by dedupe key. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Not run | Good. |
| P2-P1-04 | Fixed | Fixed | Fixed | Debug delete/restore require `BuildConfig.DEBUG`, barrier, and audit writer. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt)) | Not run | Static OK. |
| P2-P1-05 | Fixed | Fixed | Mitigated, not impossible | DAO methods remain public; annotated warning-level; CI guard claimed. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/database/dao/RestrictedExpenseDaoMutation.kt)) | Not run | Still relies on tests/allowlist. |
| NEW-P2-001 | Fixed | Fixed | Fixed | `updateExpense()` loads existing row inside transaction. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Not run | OK. |
| NEW-P2-002 | Fixed | Fixed | Mostly fixed | Category/location/business/merchant/type/transfer/ownership load inside transaction. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Not run | Good. |
| NEW-P2-003 | Fixed | Fixed | Fixed + new not-found bug | Entity delete rereads fresh row. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Missing | But returns success if missing. |
| NEW-P2-004 | Fixed | Fixed? | Fixed for create/update | Duplicate checks moved into transaction. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Not run | Duplicate-event sentinel bug remains. |
| NEW-P2-005 | Open | Design/TODO | Partial/open | Category assignment has barrier+event but bypasses coordinator and uses event type `"EXPENSE_CATEGORY_ASSIGNED"`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/DefaultExpenseCategoryAssignmentService.kt)) | Not run | Should standardize. |
| NEW-P2-006 | Open | Design/TODO | Not fully verified | `NotificationRepository.kt` did not render enough content. | Missing | Keep open until full `rg`. |
| NEW-P2-007 | Fixed | Fixed | Fixed | Failed update conversion clears base fields to sentinel. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Not run | Create failure leaves defaults only. |
| NEW-P2-008 | Open | Design/TODO | Open/mitigated | `updateMerchantForMerchant()` still exists and preserves stale dedupeKey by design warning. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt)) | Missing | Keep restricted or remove. |
| NEW-P2-009 | Fixed | Fixed | Fixed | Planner update paths use `EXPENSE_UPDATED`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt)) | Not run | OK. |
| NEW-P2-010 | Fixed | Fixed | Fixed | Both bulk category paths write event only when affected rows > 0. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Not run | OK. |
| NEW-P2-011 | Open in P2 doc | Design/TODO | Fixed | `updateLocation()` accepts and writes `correlationId`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Not run | P2 doc stale. |
| NEW-P2-012 | Open in P2 doc | Design/TODO | Fixed | `updateMerchant()` writes `correlationId`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Not run | P2 doc stale. |
| NEW-P2-013 | Open in P2 doc | Design/TODO | Fixed | `updateType()` writes `correlationId`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Not run | P2 doc stale. |
| NEW-P2-014 | Open in P2 doc | Design/TODO | Fixed | `updateMerchant()` recomputes merchantKey and dedupeKey. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Not run | P2 doc stale. |
| NEW-P2-015 | Open | Design/TODO | Partially changed | Bulk action keys use `System.currentTimeMillis()`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt)) | Missing | More unique, less idempotent/stable. |
| NEW-P2-016 | Open | Do-not-fix/local | Open | `homeCurrency().first()` still used without timeout in create/update. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Missing | Needs timeout/default policy. |

## 6. Direct DAO mutation inventory

| DAO method | Caller seen | Classification | Guard | Lifecycle event? | Safe? | Evidence |
|---|---|---|---|---|---|---|
| `insertAtomic` | Coordinator create | LEGAL | Coordinator barrier | CREATED | Yes | ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) |
| `insertAll` | Debug restore | DEBUG | BuildConfig + barrier | Aggregate restore event | Mostly | ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt)) |
| `update` | Coordinator full/business update | LEGAL | Coordinator barrier | UPDATED | Yes | ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) |
| `delete` | Coordinator delete | LEGAL | Coordinator barrier | DELETED | Mostly | Entity overload missing-row bug. |
| `deleteAll` | Debug delete/restore | DEBUG | BuildConfig + barrier | Aggregate event | Mostly | ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt)) |
| `updateCategoryNullable` | Coordinator | LEGAL | Coordinator barrier | UPDATED | Yes | ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) |
| `updateCategory` | Category assignment service | PARTIAL BYPASS | Service barrier | Non-standard event | Questionable | ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/DefaultExpenseCategoryAssignmentService.kt)) |
| `updateCategoryForMerchant/Category` | Coordinator bulk | LEGAL | Coordinator barrier | BULK_UPDATED | Yes | ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) |
| `updateMerchantAndKey` | Coordinator | LEGAL | Coordinator barrier | UPDATED/BULK_UPDATED | Yes | ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) |
| `updateMerchantForMerchant` | Not seen | DANGEROUS SURFACE | Annotation only | No | No if used | Stales dedupeKey. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt)) |
| `updateTransactionType` | Coordinator | LEGAL | Coordinator barrier | UPDATED | Yes | ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) |
| Transfer/ownership field updates | Coordinator | LEGAL | Coordinator barrier | UPDATED | Mostly | Some correlation gaps. |
| `conditionallySetLocation` | ExpenseRepository backfill | MAINTENANCE | Repository barrier | No by design | Mostly | DAO method itself appears unannotated. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt)) |
| `incrementBackfillAttempts` | ExpenseRepository backfill | MAINTENANCE | Repository barrier | No by design | Mostly | DAO method itself appears unannotated. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt)) |
| `clearLocation` | ExpenseRepository | MAINTENANCE | Repository barrier | No by design | OK | ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt)) |
| `updateMerchantKey` | ExpenseRepository backfill | MAINTENANCE | Repository barrier | No by design | OK | ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt)) |

## 7. Cross-pipeline create/update caller inventory

| Source/caller | Operation | Coordinator API used? | Dedupe/idempotency | Source link/provenance | Side-effect mode | Status |
|---|---|---|---|---|---|---|
| Manual UI / `ManualExpenseRepository` | Create | Yes, `createExpenseDbOnlyV2()` | STANDARD default | None required for manual | Runs returned batch after outer commit | OK. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt)) |
| Notification auto-accept | Create | Source docs/imports indicate coordinator | Needs full body verification | `rawNotificationId` required by requirements | Post-commit actions | Partial verified. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt)) |
| Review approval | Create | Coordinator injected; body partial | Needs full body verification | `pendingReviewId` required | Post-commit actions | Partial verified. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt)) |
| Receipt/email/bank/import/group/recurring | Create/update | Not fully opened | Unknown | Requirements exist | Unknown | Requires local `rg`. |
| ExpenseRepository updates | Update/delete/bulk | Yes for user-facing paths | Key recompute for merchant/type/full | N/A | Post-commit planner | Mostly OK. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt)) |
| Backfill/debug | Maintenance/debug | Direct DAO via repository | N/A | N/A | No standard side effects | Allowlisted but should stay guarded. |

## 8. New findings

| ID | Severity | Type | Title | Evidence | Impact | Reproduction path | Recommended fix | Required tests | Cross-pipeline impact |
|---|---|---|---|---|---|---|---|---|---|
| P2-NEW-A | P2 | Delete correctness | `deleteExpense(Expense)` returns success and runs side effects when row missing | Entity overload returns from transaction on missing row, then plans delete and returns success. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | False success, unnecessary side effects, misleading UI/audit | Call `deleteExpense(staleExpense)` after row deleted | Track `deleted=false`; return failure and skip side effects if missing | Missing entity delete test | Any caller passing stale entity |
| P2-NEW-B | P2 | Audit/diagnostics | Update validation failures are not durably recorded | Helper exists but update methods throw `TransactionValidationException` inside txn without calling it. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Terminal `UPDATE_VALIDATION_FAILED` can be invisible | Update transfer/type to invalid state | Catch validation before mutation or emit best-effort event outside failed txn | Invalid update writes event | All update callers |
| P2-NEW-C | P2 | Dedupe/audit | Duplicate precheck path uses negative ID sentinel and can double-log duplicate events | Duplicate branch writes event then returns negative; outer `insertedId <= 0` path writes duplicate/conflict again. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Duplicate audit rows; possible wrong conflict classification | Create standard duplicate | Replace `Long` sentinel with sealed `InsertOutcome` | Exact one duplicate event | All create sources |
| P2-NEW-D | P2 | Provenance | Duplicate source-link policy is recorded but not applied | Comment says link-to-existing is deferred; duplicate metadata records policy only. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Receipt/email/bank/import duplicates may lack durable source link to existing expense | Retry receipt/bank create matching existing expense | If policy is `LINK_SOURCE_TO_EXISTING`, link duplicate source to existing inside transaction or record durable attempt entity | Duplicate source creates one existing link | P3/P10/P11/P12 |
| P2-NEW-E | P2 | DAO ownership | Some DAO mutation methods are unannotated public bypass surfaces | `incrementBackfillAttempts` and `conditionallySetLocation` appear as public `@Query UPDATE` without `@RestrictedExpenseDaoMutation`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt)) | Direct injection can bypass barrier/owner rule | Any prod class injects `ExpenseDao` and calls directly | Annotate all mutating queries; architecture test should detect SQL mutation regardless name | Guard test for all UPDATE/DELETE/INSERT | Location backfill |
| P2-NEW-F | P3 | Correlation | Transfer/type+transfer/ownership public APIs do not propagate correlationId | `updateTransferDetails` and `updateTypeAndTransferDetails` events omit correlationId; public ownership passes null. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) | Trace gaps | Cross-pipeline transfer updates | Add correlationId parameters and pass through | Event correlation tests | Groups/bank/UI |
| P2-NEW-G | P3 | Idempotency | Bulk side-effect idempotency keys use wall-clock | Bulk planner uses `System.currentTimeMillis()`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt)) | Non-deterministic retry semantics; possible same-ms collision | Repeated bulk updates | Use mutation correlation/run id + changed fields + affected IDs hash | Stable-key test | Bulk category/merchant |
| P2-NEW-H | P3 | Category lifecycle | Category assignment uses non-standard event type/no snapshots | Service writes `"EXPENSE_CATEGORY_ASSIGNED"` and direct DAO update. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/DefaultExpenseCategoryAssignmentService.kt)) | Audit consumers expecting `LifecycleEventType.UPDATED` miss it | Receipt auto-category propagation | Delegate to coordinator or write canonical UPDATED with before/after snapshot | Port contract test | Receipt pipeline |

## 9. Universal contract audit

### Restore/write barrier
Status: **YELLOW**  
Evidence: Coordinator has centralized `checkWritesAllowed`; create catches restore blocks and emits diagnostic. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) ExpenseRepository debug/backfill methods use barrier. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt))  
Gaps: Updates/deletes generally throw/fail without durable blocked event; some DAO mutation methods remain public/unannotated.

### Transaction lifecycle ownership
Status: **YELLOW**  
Evidence: DB ownership doc names coordinator for expenses/events. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/DB_WRITE_OWNERSHIP.md)) ExpenseRepository delegates user mutations. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt))  
Gaps: Category assignment service and backfill methods are exceptions; config allowlist not verified.

### Atomicity / transaction boundaries
Status: **Mostly GREEN**  
Evidence: create insert+CREATED+source links are in one transaction; updates load before snapshot inside transaction; delete event+delete atomic. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt))  
Gaps: entity delete missing-row false success; duplicate sentinel ambiguity.

### Dedupe/idempotency
Status: **YELLOW**  
Evidence: strict external ID canonical dedupe; standard/bulk check inside transaction; insert conflict resolves existing ID. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt))  
Gaps: duplicate path can double-log; bulk side-effect idempotency uses time.

### Currency/base amount
Status: **YELLOW**  
Evidence: create/update populate base snapshot; update clears stale base on conversion failure. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt))  
Gaps: `homeCurrency().first()` can hang indefinitely.

### Source-link/provenance
Status: **YELLOW**  
Evidence: requirements validate source fields; source links are written inside create transaction and source-link failures rollback. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/provenance/CreateExpenseSourceLinkRequirements.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt))  
Gaps: duplicate-source link policy deferred.

### Side effects
Status: **Mostly GREEN**  
Evidence: created/updated/deleted paths call corresponding planner methods; runner is post-commit best effort. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/sideeffect/PostCommitActionRunnerExtensions.kt))  
Gaps: no explicit `TransactionEvent.SIDE_EFFECT_FAILED` observed; relies on side-effect event writer.

### Diagnostics/lifecycle events
Status: **YELLOW**  
Evidence: create terminal events are strong; mutation events atomic.  
Gaps: update validation failed not emitted; restore-blocked updates/deletes lack durable lifecycle evidence.

### Privacy/security
Status: **Mostly GREEN**  
Evidence: source-link event metadata avoids raw external IDs/fingerprints and truncates merchant in duplicate metadata. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkEventMetadataBuilder.kt))  
Gaps: full diagnostic redaction policy not verified.

## 10. Test coverage assessment

| Behavior | Existing test? | Missing test? | Recommended test |
|---|---|---|---|
| Create validation writes event | Not verified | Maybe | `create_invalid_writes_CREATE_VALIDATION_FAILED` |
| Standard duplicate writes exactly one duplicate event | Not verified | Yes | `standard_duplicate_precheck_single_event_no_conflict` |
| Strict external retry resolves existing ID | Not verified | Maybe | `strict_external_retry_returns_existing_id` |
| Update validation failure durable | Not verified | Yes | `invalid_transfer_update_writes_UPDATE_VALIDATION_FAILED` |
| Delete stale entity missing row | Not verified | Yes | `deleteExpense_entity_missing_returns_failure_no_side_effects` |
| Source-link required failure rolls back | Not verified | Maybe | `source_link_required_failure_no_expense` |
| Duplicate source link-to-existing | Not verified | Yes | `receipt_duplicate_links_source_to_existing` |
| All DAO mutations restricted/allowlisted | Not run | Yes | Extend architecture guard to parse all `@Query UPDATE/DELETE/INSERT` |
| `Flow.first()` timeout | Not verified | Yes | `home_currency_flow_timeout_uses_safe_resolution` |

## 11. Recommended fix plan

### PR 1 — Critical data-integrity/lifecycle ownership
1. Fix `deleteExpense(Expense)` missing-row behavior.
2. Replace create duplicate negative-ID sentinel with sealed transaction outcome.
3. Ensure update validation failures emit `UPDATE_VALIDATION_FAILED`.

### PR 2 — Atomicity/dedupe/currency correctness
1. Add timeout/safe resolver around `homeCurrency().first()`.
2. Stabilize bulk side-effect idempotency keys.
3. Add exact duplicate/concurrency tests.

### PR 3 — Source-link/provenance and side effects
1. Implement `LINK_SOURCE_TO_EXISTING` duplicate source-link policy.
2. Decide whether side-effect failures require `TransactionEvent.SIDE_EFFECT_FAILED` or side-effect event table is sufficient.
3. Add source-link duplicate tests.

### PR 4 — Architecture guards/tests/docs drift
1. Annotate or allowlist every DAO mutation method, including backfill updates.
2. Bring P2 consolidated doc, master tracker, ExpenseRepository comments, and actual owner names into sync.
3. Run full `rg`, Gradle, schema, and architecture test suite locally.

## 12. Final production-readiness decision

**YELLOW.** The main transaction lifecycle is substantially architecture-compliant and much safer than the stale P2 issue docs imply. Normal create/update/delete paths are barrier-guarded and audited. Do not mark GREEN until the delete missing-row bug, update validation diagnostics, duplicate-event sentinel behavior, duplicate source-link policy, DAO mutation guard completeness, and local test/schema verification are resolved.