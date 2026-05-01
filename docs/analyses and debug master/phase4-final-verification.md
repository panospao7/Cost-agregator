# Phase 4 — Final Verification Audit

**Date:** 2026-05-01
**Scope:** Receipt Lifecycle Foundation implementation across 9 PRs
**Commit:** Schema version 96, migration 95→96

---

## 1. Remaining Direct ScannedReceiptDao Access

### 1a. Approved (in correct files)

| File | Lines | Role |
|------|-------|------|
| `data/repository/ReceiptRepository.kt` | 54, 103, 127, 157, 234, 284, 431, 435, 439, 443, 448, 452, 566, 801, 805, 812, 821, 839, 873, 881, 887, 896, 902, 907, 914, 919, 924, 929, 937, 944 | Legacy repository wrapping DAO |
| `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` | 59, 130, 198, 270, 299, 306, 324, 352 | Central coordinator |
| `domain/receipt/lifecycle/ReceiptDuplicateDetector.kt` | 25, 78, 92, 106, 120 | Duplicate detection queries |
| `domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` | 55, 113 | Bank statement save |
| `domain/receipt/lifecycle/ReceiptLinkService.kt` | 31, 67, 96, 138, 148 | Link management + legacy field |
| `data/database/AppDatabase.kt` | 84 | DAO abstract declaration |

### 1b. UNEXPECTED — Needs Fixing

| File | Line | Issue |
|------|------|-------|
| **`di/DaoModule.kt`** | 53-55 | `provideScannedReceiptDao()` — DI provider, acceptable but note that `ReceiptEventDao` and `ReceiptExpenseLinkDao` are MISSING from this same module (see §3b) |

---

## 2. Receipt-Related Code Not Covered by Phase 4

### Domain models that reference `ScannedReceipt` directly (bypassing coordinator):
- **`WarrantyTrackerRepository.kt`** — takes `ScannedReceipt` params, manages warranty lifecycle outside the coordinator
- **`PriceProtectionTracker.kt`** — takes `ScannedReceipt` params directly
- **`EmailReceiptIngestionService.kt`** — creates `ScannedReceipt` directly (line 223), but uses `coordinator.saveEmailReceipt()` for the final save
- **`ProcessReceiptUseCase.kt`** — separate use case for receipt processing, not routed through coordinator
- **`AutoCreateWarrantyFromReceiptUseCase.kt`** — accesses warranty by receiptId directly

### UI code referencing `ScannedReceipt`:
- **`ReceiptScanViewModel.kt`** — uses `receiptLifecycleCoordinator.processReceiptInput()` ✓ but also reads `receipt.imagePath` directly for `Uri.fromFile()`
- **`ReviewScreen.kt`** — reads `item.receipt?.imagePath` and creates `File(it)` directly
- **`ReviewViewModel.kt`** — calls `receiptRepository.clearAllScannedReceipts()`
- **`ReceiptMatchingScreen.kt`** / **`ReceiptMatchingViewModel.kt`** — receipt matching UI, uses `ReceiptTransactionMatcher` and `ReceiptLinkService` directly
- **`ReceiptScanScreen.kt`** — receipt scan UI, no direct DAO access

### Email receipt duplication:
- **`EmailReceiptIngestionService.kt`** — has its own duplicate detection via `emailReceiptDao.getByMessageId()` and `emailReceiptDao.getByFingerprint()` that bypasses `ReceiptDuplicateDetector`

### Two `EmailReceiptData` classes (naming conflict):
- `domain/receipt/EmailReceiptData.kt` — used by coordinator (7 fields: messageId, from, subject, body, receivedAt, amount, merchant, currency, date, items)
- `data/email/EmailReceiptIngestionService.kt` (line 445) — batch processing data class (4 fields: body, sender, subject, receivedAt, messageId)
- These are DIFFERENT classes with the same name in different packages, which could cause import confusion.

### Legacy DAO operations in ReceiptRepository:
- `ocrService.deleteImage()` at lines 447, 803 — bypasses `ReceiptAssetStore.deleteAsset()`
- `receipt.imagePath?.let { ocrService.deleteImage(it) }` — direct image file operations

---

## 3. Implementation Verification

### 3a. Schema & Migration

| Check | Status |
|-------|--------|
| `APP_DATABASE_SCHEMA_VERSION` = 96 | ✅ |
| Schema 96 JSON exists | ✅ `app/schemas/.../96.json` |
| Schema 95 JSON exists | ✅ (Phase 3 baseline) |
| `MIGRATION_94_95` defined | ✅ Phase 3 |
| `MIGRATION_95_96` defined | ✅ Phase 4 |
| Both in `ALL_MIGRATIONS` array | ✅ Lines 5901-5902 |
| Migration 95→96 columns match `ScannedReceipt` entity | ✅ |
| New tables `receipt_events` + `receipt_expense_links` in schema 96 | ✅ |
| Entity `ReceiptEvent` in AppDatabase.entities | ✅ Line 66 |
| Entity `ReceiptExpenseLink` in AppDatabase.entities | ✅ Line 67 |
| DAO `receiptEventDao()` in AppDatabase | ✅ Line 122 |
| DAO `receiptExpenseLinkDao()` in AppDatabase | ✅ Line 123 |

### 3b. Coordinator Method Inventory

**`ReceiptLifecycleCoordinator` methods:**

| Method | Status | Notes |
|--------|--------|-------|
| `processReceiptInput(uri)` | ✅ **FULLY IMPLEMENTED** | Validates, persists asset, computes hash, OCR/parse, saves, writes event, dispatches side effects |
| `processEmailReceipt(emailData)` | ❌ **STUB** | Throws `UnsupportedOperationException("will be implemented in PR 5")` |
| `processBankStatement(uri)` | ✅ **FULLY IMPLEMENTED** | Delegates to `BankStatementLifecycleProcessor` |
| `saveEmailReceipt(receipt)` | ✅ **FULLY IMPLEMENTED** | Overrides sourceType/documentType, writes events |
| `getRecentReceipts(since)` | ✅ **IMPLEMENTED** | Delegates to DAO |
| `getReceiptById(id)` | ✅ **IMPLEMENTED** | Delegates to DAO |
| `deleteReceipt(receiptId)` | ✅ **FULLY IMPLEMENTED** | Writes event, deletes links, deletes asset, deletes row |

**Missing from implementation:**
- `ReceiptDuplicateDetector` is **NOT injected** into the coordinator constructor
- `ReceiptDuplicateDetector.checkDuplicate()` is **NOT called** in `processReceiptInput()` despite being mentioned in KDoc
- `ReceiptInputValidator.validate()` is called correctly ✓

### 3c. ReceiptLinkService Consumers

**7 classes inject `ReceiptLinkService`:**

| # | File | Usage |
|---|------|-------|
| 1 | `ReceiptLifecycleCoordinator` | Link management in save paths |
| 2 | `ReceiptTransactionMatcher` | Auto-match high-confidence receipts |
| 3 | `ReceiptMatchingWorker` | Background matching worker |
| 4 | `EmailReceiptIngestionService` | Links email receipt to created expense |
| 5 | `BankStatementLifecycleProcessor` | Links bank statement transactions |
| 6 | `ReceiptRepository` | Legacy link operations (deprecated path) |
| 7 | `ReviewQueueRepository` | Links during review approval |

All consumers route through `ReceiptLinkService.linkReceiptToExpense()` ✓

### 3d. Side Effect Dispatcher Status

| Check | Status |
|-------|--------|
| Wired into coordinator constructor | ✅ Line 64 |
| Called in `processReceiptInput()` | ✅ Line 169 |
| Document-type gating for `RETAIL_RECEIPT` | ✅ Warranty, categorization, matching, price protection |
| Document-type gating for `EMAIL_RECEIPT` | ✅ Categorization only |
| Document-type gating for `BANK_STATEMENT` | ✅ None (handled by processor) |
| Document-type gating for `MANUAL_PLACEHOLDER` | ✅ None |
| Status gating (skip OCR_FAILED/PARSE_FAILED) | ✅ Status check at lines 68-69 |
| Individual try/catch per side effect | ✅ Each wrapped in try/catch |

### 3e. Duplicate Detection Status

| Check | Status |
|-------|--------|
| `ReceiptDuplicateDetector` class implemented | ✅ 233 lines, fully featured |
| 4 fingerprint strategies | ✅ EXACT_HASH, TEXT_FINGERPRINT, SEMANTIC, EXTERNAL_ID |
| Public helper methods | ✅ `computeTextFingerprintPublic()`, `computeSemanticFingerprintPublic()` |
| **Wired into processReceiptInput()** | ❌ **NOT WIRED** — detector is not even injected into coordinator |
| **Wired into EmailReceiptIngestionService** | ❌ Has its own fingerprinting logic that bypasses the detector |
| **Wired into BankStatementLifecycleProcessor** | ❌ Has its own deduplication via `pendingReviewDao.getPendingByMerchant()` |

**⚠️ CRITICAL: The `ReceiptDuplicateDetector` exists as a standalone class but is NOT integrated anywhere in the processing pipeline.**

---

## 4. Cross-Phase Consistency

### 4a. TimeProvider Usage

| File | Uses `TimeProvider.now()` | Uses `System.currentTimeMillis()` |
|------|--------------------------|-----------------------------------|
| `ReceiptLifecycleCoordinator` | ✅ Lines 128, 139, 156, 195, 208, 262, 267, 339 | ❌ None |
| `BankStatementLifecycleProcessor` | ✅ Lines 75, 101, 109, 110, 127, 153, 208 | ❌ None |
| `ReceiptLinkService` | ✅ Lines 75, 100, 160 | ❌ None |
| `ReceiptDuplicateDetector` | N/A (fingerprint computation only) | ❌ None |
| `ReceiptSideEffectDispatcher` | N/A (dispatches only) | ❌ None |
| `ReceiptAssetStore` | ❌ None | ⚠️ **Lines 47, 69** — uses `System.currentTimeMillis()` for temp filename |
| `ReceiptOcrService` | ❌ None | ⚠️ **Lines 591, 608** — uses `System.currentTimeMillis()` for filename |

**Verdict:** All new Phase 4 lifecycle code correctly uses `TimeProvider.now()`. The `System.currentTimeMillis()` usages are in `ReceiptAssetStore` and `ReceiptOcrService` which are pre-existing files (not new Phase 4 code), but they ARE in the receipt lifecycle domain and should ideally be migrated.

### 4b. TransactionCoordinator Integration

| Consumer | Uses `TransactionLifecycleCoordinator.createExpense()` | Notes |
|----------|--------------------------------------------------------|-------|
| `ReceiptRepository` | ✅ Line 348 | Legacy path for createExpenseFromReceipt |
| `EmailReceiptIngestionService` | ✅ Line 400 | Uses `coordinator.createExpense(request)` |
| `ReviewQueueRepository` | ✅ Line 224 | Routes through coordinator |
| `BankStatementLifecycleProcessor` | ❌ Not needed | Creates PendingReview, not expenses directly |

All receipt→expense creation paths use `TransactionLifecycleCoordinator` ✓

### 4c. Schema Version Chain

```
Phase 3                  Phase 4
[MIGRATION_93_94] → [MIGRATION_94_95] → [MIGRATION_95_96]
   Schema 94               Schema 95           Schema 96
   (pre-Phase 3)     (TransactionEvents)   (Receipt lifecycle cols,
                                             receipt_events,
                                             receipt_expense_links)
```

- Migration 94→95 adds: `expenses.source` column, `transaction_events` table ✓
- Migration 95→96 adds: 10 new columns to `scanned_receipts`, `receipt_events` table, `receipt_expense_links` table, backfill heuristics ✓
- Both migrations registered in `ALL_MIGRATIONS` array ✓
- Both schema JSONs exist on disk ✓

---

## 5. New Files Inventory (Phase 4)

| File | Lines | Purpose | Status |
|------|-------|---------|--------|
| `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` | 361 | Central coordinator | ✅ |
| `domain/receipt/lifecycle/ReceiptInputValidator.kt` | 140 | URI validation | ✅ |
| `domain/receipt/lifecycle/ReceiptAssetStore.kt` | 233 | File operations | ✅ |
| `domain/receipt/lifecycle/ReceiptDuplicateDetector.kt` | 233 | Duplicate detection | ✅ (but unused) |
| `domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt` | 127 | Post-save side effects | ✅ |
| `domain/receipt/lifecycle/ReceiptLinkService.kt` | 195 | Link management | ✅ |
| `domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` | 233 | Bank statement processing | ✅ |
| `data/database/entity/ReceiptEvent.kt` | 50 | Event entity | ✅ |
| `data/database/entity/ReceiptExpenseLink.kt` | 46 | Link entity | ✅ |
| `data/database/dao/ReceiptEventDao.kt` | 16 | Event DAO | ✅ |
| `data/database/dao/ReceiptExpenseLinkDao.kt` | 26 | Link DAO | ✅ |
| `domain/receipt/ReceiptProcessingStatus.kt` | 18 | Status enum | ✅ |
| `domain/receipt/ReceiptDocumentType.kt` | 10 | Document type enum | ✅ |
| `domain/receipt/ReceiptSourceType.kt` | 13 | Source type enum | ✅ |
| `domain/receipt/EmailReceiptData.kt` | 33 | Email receipt data | ✅ |

**Total: 15 new files**

---

## 6. Overall Verdict

### Phase 4 completion: **78%**

### Remaining issues: **8**

| # | Severity | Issue | Location |
|---|----------|-------|----------|
| 1 | 🔴 **CRITICAL** | `ReceiptDuplicateDetector` not wired into `processReceiptInput()` — no duplicate checking in the main save path | `ReceiptLifecycleCoordinator.kt` (constructor + lines 84-218) |
| 2 | 🔴 **CRITICAL** | `ReceiptEventDao` and `ReceiptExpenseLinkDao` not registered in `DaoModule.kt` — will cause Hilt injection failure for `ReceiptLifecycleCoordinator` and `ReceiptLinkService` | `di/DaoModule.kt` (missing methods) |
| 3 | 🟡 **HIGH** | `processEmailReceipt()` is a STUB throwing `UnsupportedOperationException` — email receipt processing falls back to direct code in `EmailReceiptIngestionService` | `ReceiptLifecycleCoordinator.kt` line 229-233 |
| 4 | 🟡 **HIGH** | `ReceiptDuplicateDetector` has its own fingerprint computation logic that duplicates logic in `ReceiptOcrService` — no single source of truth for hashing | `ReceiptDuplicateDetector.kt` vs `ReceiptOcrService.kt` |
| 5 | 🟡 **HIGH** | `ReceiptRepository` still has direct `ocrService.deleteImage()` calls that bypass `ReceiptAssetStore` | `ReceiptRepository.kt` lines 447, 803 |
| 6 | 🟢 **MEDIUM** | `System.currentTimeMillis()` used in `ReceiptAssetStore` (lines 47, 69) and `ReceiptOcrService` (lines 591, 608) instead of `TimeProvider.now()` | Both files in receipt domain |
| 7 | 🟢 **MEDIUM** | Two `EmailReceiptData` classes with same name in different packages (`domain/receipt/` and `data/email/`) — potential import confusion | `EmailReceiptData.kt` and `EmailReceiptIngestionService.kt` |
| 8 | 🔵 **LOW** | Hardcoded sentinel strings `"Scan Failed:"` and `"[OCR Failed or Skipped]"` used for status detection instead of enum-based checks | `ReceiptRepository.kt`, `ReceiptLifecycleCoordinator.kt`, `SuggestReceiptExtractionUseCase.kt`, `CategorizationAssistInputBuilder.kt` |

### Recommended actions:

1. **IMMEDIATE**: Add `ReceiptDuplicateDetector` to the `ReceiptLifecycleCoordinator` constructor and call `checkDuplicate()` at the start of `processReceiptInput()` (before OCR/parse). If a duplicate is found, return early with the existing receipt.

2. **IMMEDIATE**: Add `provideReceiptEventDao()` and `provideReceiptExpenseLinkDao()` to `DaoModule.kt` following the same pattern as `provideTransactionEventDao()`.

3. **PR 5**: Implement `processEmailReceipt()` in the coordinator and have `EmailReceiptIngestionService` delegate to it instead of calling `saveEmailReceipt()` directly.

4. **MEDIUM**: Migrate `ReceiptAssetStore` and `ReceiptOcrService` to use `TimeProvider.now()` for timestamp-based filenames.

5. **MEDIUM**: Consolidate the two `EmailReceiptData` classes into a single canonical definition.

6. **LOW**: Replace hardcoded sentinel string checks with `ReceiptProcessingStatus` enum comparisons.
