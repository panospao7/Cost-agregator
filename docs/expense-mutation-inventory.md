# Expense Mutation Inventory

> All known expense mutation callsites, classified by ownership and purpose.
> Baseline: HEAD codebase as of 2026-05-11.

## Classification Key

| Classification | Meaning |
|----------------|---------|
| **LIFECYCLE-OWNED** | Routed through `TransactionLifecycleCoordinator` with full lifecycle events |
| **MAINTENANCE/BACKFILL** | Background worker column updates (intentional bypass — low-value noise) |
| **DEBUG** | Debug-only methods guarded by `BuildConfig.DEBUG` |
| **UNRESOLVED** | Direct DAO calls not yet classified or migrated |

---

## LIFECYCLE-OWNED (TransactionLifecycleCoordinator)

All callsites that route through `TransactionLifecycleCoordinator`:

| # | Method | Caller / Path | Notes |
|---|--------|---------------|-------|
| 1 | `createExpense()` | `NotificationProcessingPipeline.handleAutoAcceptInTransaction()` | Auto-accept from notification |
| 2 | `createExpense()` | `EmailReceiptIngestionService.createExpenseFromReceipt()` | Email receipt → expense |
| 3 | `createExpense()` | `ReviewQueueRepository.approveReview()` | Manual review approval |
| 4 | `createExpense()` | `CsvExpenseImporter.importRow()` | CSV import |
| 5 | `createExpense()` | `JsonExpenseImporter.importRow()` | JSON import |
| 6 | `createExpense()` | `RecurringLifecycleCoordinator.materializeOccurrence()` | Recurring bill → expense |
| 7 | `createExpense()` | `TransactionLifecycleCoordinator` itself | Any direct caller |
| 8 | `updateExpense()` | `ExpenseRepository.updateExpense()` | Full-row update |
| 9 | `updateCategory()` | `ExpenseRepository.updateExpenseCategory()` | Category-only update |
| 10 | `updateLocation()` | `ExpenseRepository.updateExpenseLocation()` | User location edit |
| 11 | `updateMerchant()` | `ExpenseRepository.updateExpenseMerchant()` | Single merchant rename |
| 12 | `updateType()` | `ExpenseRepository.updateExpenseType()` | Transaction type change |
| 13 | `updateTransferDetails()` | `ExpenseRepository.updateTransferDetails()` | Transfer direction/account |
| 14 | `updateOwnership()` | `ExpenseRepository.updateNotMineDetails()` / `updateSharedExpenseDetails()` / `updateOwnership()` | Ownership field updates |
| 15 | `bulkUpdateCategory()` | `ExpenseRepository.updateExpenseCategoryBulk()` | Bulk category reassign |
| 16 | `bulkUpdateMerchant()` | `ExpenseRepository.updateExpenseMerchantBulk()` | Bulk merchant rename |
| 17 | `updateBusinessTaxFields()` | *(no current caller — API surface only)* | Business/tax field updates |
| 18 | `deleteExpense()` | `ExpenseRepository.deleteExpense()` | Single expense deletion |
| 19 | `deleteExpense(Expense)` | `ExpenseRepository.deleteExpense()` | By-entity deletion |

---

## MAINTENANCE/BACKFILL (intentional bypass)

Background workers that update 1-2 columns per row. Writing `TransactionEvent.UPDATED` per row from these workers would flood `transaction_events` with low-value noise.

| # | Method | Worker | Columns touched |
|---|--------|--------|-----------------|
| 1 | `ExpenseRepository.conditionallySetLocation()` | `LocationBackfillWorker` | `latitude`, `longitude`, `locationSource`, `placeId`, `resolvedAddress` |
| 2 | `ExpenseRepository.clearExpenseLocation()` | *(location reset path)* | `latitude`, `longitude` |
| 3 | `ExpenseRepository.incrementBackfillAttempts()` | `LocationBackfillWorker` | `backfillAttempts` (dead-letter counter) |
| 4 | `ExpenseRepository.updateMerchantKey()` | `MerchantKeyBackfillWorker` | `merchantKey` |

---

## DEBUG (BuildConfig.DEBUG guarded)

| # | Method | Caller | Guard |
|---|--------|--------|-------|
| 1 | `ExpenseRepository.deleteAllExpenses()` | Debug tools | `BuildConfig.DEBUG` |
| 2 | `ExpenseRepository.createDebugSnapshot()` | Debug tools | `BuildConfig.DEBUG` |
| 3 | `ExpenseRepository.restoreDebugSnapshot()` | Debug tools | `BuildConfig.DEBUG` |

---

## UNRESOLVED / Intentional Non-Routing

Callsites that bypass the lifecycle coordinator by design (documented in `ExpenseRepository` KDoc):

| # | Method | Caller | Rationale |
|---|--------|--------|-----------|
| 1 | `ExpenseDao.update()` | `ReceiptLinkService.linkReceiptToExpense()` (RCP-30) | Circular dependency: `ReceiptLinkService` → coordinator would create cycle |
| 2 | `GroupTransactionCoordinator.clearSharedExpenseFlags()` | Group deletion flow | Post-commit cleanup, not user-initiated edit |
| 3 | `GroupTransactionCoordinator.normalizeLinkedSystemExpense()` | Group expense creation | Atomic part of group creation tx; lifecycle event via `createExpense()` |

---

## Summary

| Classification | Count | Via Coordinator? |
|----------------|-------|------------------|
| LIFECYCLE-OWNED | 19 | Yes |
| MAINTENANCE/BACKFILL | 4 | No (intentional) |
| DEBUG | 3 | No (debug-only) |
| UNRESOLVED | 3 | No (design constraints) |
| **TOTAL** | **29** | |

### TODO for future auditing

- Verify that `ReceiptLinkService.linkReceiptToExpense()`'s category propagation still works after coordinator migration (RCP-30)
- Evaluate whether `LocationBackfillWorker` should batch-write events for backfill operations
- Track whether new mutation paths are added outside the coordinator over time
