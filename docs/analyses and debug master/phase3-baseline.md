# Phase 3 Baseline — Starting State

> **Date**: 2026-05-01
> **Branch**: master-refactor
> **Compile**: ✅ BUILD SUCCESSFUL
> **Tests**: ⚠️ Pre-existing Phase 1 currency failures (not Phase 3 related)

## Current Direct DAO Insert Sites (to be migrated)

| # | File | Method | Line |
|---|------|--------|------|
| 1 | `NotificationProcessingPipeline.kt` | `handleAutoAcceptInTransaction()` → `expenseDao.insertAtomic()` | ~714 |
| 2 | `ReviewQueueRepository.kt` | `approveReview()` → `expenseDao.insertAtomic()` | ~196 |
| 3 | `ReceiptRepository.kt` | `createExpenseFromReceipt()` → `expenseDao.insertAtomic()` | ~120 |
| 4 | `ManualExpenseRepository.kt` | `addManualExpense()` → `expenseDao.insertAtomic()` | ~80 |
| 5 | `CsvExpenseImporter.kt` | `importRow()` → `expenseDao.insert()` (bare, no dedup) | ~85 |
| 6 | `EmailReceiptIngestionService.kt` | `ingest()` → `expenseDao.insertAtomic()` | ~120 |
| 7 | `GroupTransactionCoordinator.kt` | `addGroupExpense()` → `expenseDao.insertAtomic()` | ~180 |
| 8 | `BankApiService.kt` / stub | Future path | — |

## Current Direct DAO Update/Delete Sites (to be audited)

| # | File | Method |
|---|------|--------|
| 1 | `MainActivity.kt` | `applyVisualSplitToExpense()` → `expenseDao.insertAll()` (REPLACE) |
| 2 | `GroupTransactionCoordinator.kt` | `normalizeLinkedSystemExpense()` → direct update |
| 3 | `ExpenseRepository.kt` | Various update/delete methods (acceptable — will delegate to coordinator) |
| 4 | `CategorizationEngine.kt` | Bulk category reassignment → `expenseDao.update()` |
| 5 | `MerchantNormalization.kt` | Bulk merchant update → `expenseDao.update()` |

## Current Dedup Gaps

| Path | Range Dedup | DedupeKey | Status |
|------|-------------|-----------|--------|
| Notification | ✅ | ✅ | OK |
| Pending Review | ✅ | ✅ | OK |
| Receipt | ❌ | ❌ | GAP |
| Manual Entry | ❌ | ❌ | GAP |
| CSV Import | ❌ | ❌ | GAP (most dangerous) |
| Email Receipt | ❌ | ❌ | GAP |
| Group/Shared | ❌ | ❌ | GAP |
| Bank API | ❌ | ❌ | GAP (future) |

## Current Fake/Placeholder Values

| Value | Where | Risk |
|-------|-------|------|
| `0.01` amount | `ReviewQueueRepository` fallback | Silently wrong totals |
| `"Unknown"` merchant | `ReviewQueueRepository` fallback | Breaks merchant analytics |
| `"Parsing Failed"` merchant | `ReceiptRepository` | Creates fake expenses |
| `confidence = 1.0f` | Pending review fallback | Lies about data quality |
| Hardcoded `"EUR"` | 30+ locations | Wrong currency assignment |

## Expenses Created by Source (approximate, no exact tracking exists)

| Source | Estimated % | Tracked? |
|--------|-------------|----------|
| Notification auto-accept | ~40% | Via rawNotificationId |
| Pending review approval | ~25% | Via review id |
| Manual entry | ~20% | Via isManualEntry flag |
| Receipt scan | ~10% | Via scannedReceipt link |
| CSV import | ~3% | ❌ Not tracked |
| Email receipt | ~1% | ❌ Not tracked |
| Group/shared | ~1% | Via group link |
