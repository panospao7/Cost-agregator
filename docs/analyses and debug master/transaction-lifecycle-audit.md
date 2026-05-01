# Transaction Lifecycle Audit — Full Codebase Inventory

> **Generated**: 2026-05-01
> **Scope**: `app/src/main/java/com/yourname/expensetracker`
> **Purpose**: Foundation for `TransactionLifecycleCoordinator` design (Phase 3)

---

## Summary Statistics

| Metric | Count |
|---|---|
| **Total expense creation paths** | **8** (Notification, Pending Review, Receipt, Manual, CSV Import, Email Receipt, Group/Shared, Bank API) |
| **Total expense insertion call sites** | **10** (distinct `expenseDao.insert` / `insertAtomic` / `insertAll` calls) |
| **Direct DAO insert calls (outside `ExpenseRepository`)** | **8** sites |
| **Direct DAO update/delete calls (outside `ExpenseRepository`)** | **25+** sites |
| **Different deduplication strategies** | **3** (dedupeKey unique index, range-based `isDuplicateCurrencyAware`, cross-source fingerprint) |
| **Files with expense creation/modification logic** | **20+** files across data, domain, and UI layers |
| **Hardcoded `"EUR"` defaults** | **30+** locations (entity defaults, parser fallbacks, UI defaults) |
| **Fake/placeholder values** | **6+** patterns (`0.01`, `"Unknown"`, `confidence = 1.0f`, `"Parsing Failed"`, `"Unknown Product"`) |

---

## 1. Expense Creation Paths — Complete Inventory

### 1a. Notification → Expense

**Entry Point**: `NotificationCaptureService.kt` (Android `NotificationListenerService`)
- Raw notification captured → `RawNotification` entity created → `NotificationRepository.processAndSave()` called

**Call Chain**:
```
NotificationCaptureService (line 354-367)
  → NotificationRepository.processAndSave() (line 69)
    → NotificationProcessingPipeline.process() (line 106)
      → processInternal() (line 140)
        → parserRegistry.parseWithAiFallback() → ParsedTransaction | null
        → IF parsed != null:
          → buildPreDbContext() (line 565)
            → confidenceRouter.route() → AUTO_ACCEPT | NEEDS_REVIEW | AUTO_REJECT
            → hybridClassifier.classify()
            → merchantNormalizer.normalize()
            → DuplicateDetectionPolicy.generateDedupeKeyWithType()
          → handleAutoAcceptInTransaction() (line 664)
            → expenseDao.isDuplicateCurrencyAware() (dedup check)
            → pendingReviewDao.hasPendingDuplicateInRangeTypeAware() (pending dedup check)
            → expenseDao.insertAtomic(expense) (line 714) ← INSERT
          → OR handleNeedsReviewInTransaction() (line 732)
            → pendingReviewDao.upsertByRawNotificationId(PendingReview)
        → IF parsed == null:
          → detectOversizedAmountCandidate() / detectTransactionSignalCandidate()
          → expenseDao.isDuplicateCurrencyAware() / pendingReviewDao.hasPendingDuplicateInRangeTypeAware()
          → pendingReviewDao.upsertByRawNotificationId(PendingReview) or markRelevance(false)
```

**Validation**:
- Parser returns null → oversized or signal detection → manual review
- `parsed.amount > 1_000_000` → overrides AUTO_ACCEPT to NEEDS_REVIEW
- Currency assignment: parsed from notification text (`"EUR"`, `"USD"`, `"GBP"` detected via regex)
- Auto-reject for low-confidence/failed parse (unless from financial packages — overridden to NEEDS_REVIEW)
- ConfidenceRouter decides AUTO_ACCEPT vs NEEDS_REVIEW vs AUTO_REJECT based on source stats

**Deduplication**:
- `insertRawNotificationIfNotDuplicate()` — raw notification dedup by package+timestamp+title+text+bigText
- `hasCanonicalExpenseDuplicate()` — uses `expenseDao.isDuplicateCurrencyAware()` (amount±0.01, window 5min, merchantKey, currency, type)
- `pendingReviewDao.hasPendingDuplicateInRangeTypeAware()` — same window against PendingReview table
- `insertAtomic()` (IGNORE-on-conflict) — final race-condition guard on unique `dedupeKey` index

**Currency**: Extracted from notification text via regex (`"EUR"`/`"USD"`/`"GBP"` + symbols). Falls back to `"EUR"`.

**Fake values**: None normally. Parsed data used directly.

---

### 1b. Pending Review → Expense

**Entry Point**: `ReviewViewModel.kt` (UI button tap: approve / approveWithEdits / quick approve / approveAll)
- `approveReview()`, `approveReviewWithEdits()`, `confirmQuickApprove()`, `approveAll()`

**Call Chain**:
```
ReviewViewModel.approveReview(reviewId)
  → ReviewQueueRepository.approveReview(reviewId, ...) (line 84)
    → pendingReviewDao.transitionStatus(PENDING → PROCESSING)
    → hasCanonicalApprovalDuplicate(expense) via isDuplicateCurrencyAware()
    → expenseDao.insertAtomic(expense) (line 196) ← INSERT
    → rawNotificationDao.markRelevance(true)
    → sourceStatsDao.incrementAccepted()
    → scannedReceiptDao.linkToExpense() (if receipt is linked)
    → pendingReviewDao.updateStatus(APPROVED)
    → userCorrectionDao.insert(correction)
    → Post-commit: budgetMonitor.checkBudgets(), anomalyAlertOrchestrator.checkAndAlert(),
      classifier.retrainFromCorrections(), merchantCategoryRepository.learnPattern(), etc.
```

**Validation**:
- `amount > 1_000_000` → `AMOUNT_EXCEEDS_LIMIT` error
- No explicit `amount <= 0` guard (assumed `suggestedAmount > 0` per DB `CHECK` constraint)
- Review already processed check via `transitionStatus()` (PENDING→PROCESSING)
- Review not found check

**Deduplication**:
- `hasCanonicalApprovalDuplicate()` via `expenseDao.isDuplicateCurrencyAware()` (same window/tolerance as notification path)
- `insertAtomic()` IGNORE-on-conflict as race guard

**Currency**: From `review.suggestedCurrency` (parsed from original notification)

**Fake values**:
- `FALLBACK_SUGGESTED_AMOUNT = 0.01` when parser fails to extract total
- `"Unknown"` merchant when parser fails (`ReviewQueueRepository` line 442)
- `confidence = 1.0f` for fallback pending reviews
- `"EUR"` hardcoded for fallback pending reviews

---

### 1c. Receipt → Expense

**Entry Points**: 
1. `ReceiptScanViewModel.saveExpense()` (user taps Save after reviewing scan)
2. `ReceiptRepository.processReceipt()` (OCR + parse, auto-create PendingReview for batch/statement)
3. `ReceiptRepository.processStatement()` (bank statement OCR → multiple PendingReviews)

**Path 1: ReceiptScanViewModel.saveExpense()**
```
ReceiptScanViewModel.saveExpense() (line 815)
  → buildManualSaveRequest() → ReceiptSaveRequest
  → saveExpenseInternal() (line 968)
    → receiptRepository.createExpenseFromReceipt() (line 984)
      → merchantNormalizer.normalize()
      → hybridClassifier.classify() (auto-categorize)
      → expenseDao.isDuplicateCurrencyAware() (dedup check)
      → expenseDao.insertAtomic(expense) (line 347) ← INSERT
      → scannedReceiptDao.linkToExpense(receiptId, expenseId)
      → Post-commit: budgetMonitor.checkBudgets(), anomalyAlert.checkAndAlert(),
        hybridClassifier.learnFromCorrection(), merchantCategoryRepository.learnPattern()
```

**Path 2: ReceiptRepository.processReceipt()** (batch scan)
```
ReceiptRepository.processReceipt(uri, autoCreateReview=true) (line 98)
  → ocrService.processUri() → OcrResult
  → receiptParser.parse() → ParsedReceipt
  → scannedReceiptDao.insert(ScannedReceipt) (line 144)
  → IF autoCreateReview:
    → pendingReviewDao.insert(PendingReview) (line 167) ← NOT an expense yet
  → warrantyUseCase.execute() (F1 warranty extraction)
  → User later approves in ReviewScreen → path 1b
```

**Path 3: ReceiptRepository.processStatement()** (bank statement)
```
ReceiptRepository.processStatement(uri) (line 497)
  → ocrService.processUri() → OcrResult
  → statementParser.parse() → List<ParsedTransaction>
  → scannedReceiptDao.insert(ScannedReceipt)
  → For each transaction:
    → crossSourceDeduplication.resolvePendingReviewDuplicate()
    → hasExpenseDuplicateInRangeCurrencyAware() (dedup check)
    → pendingReviewDao.insert(PendingReview) (line 646) ← NOT an expense yet
    → User later approves in ReviewScreen → path 1b
```

**Validation**:
- Merchant name is required (ReceiptScanViewModel)
- Amount is parsed and must be > 0
- No explicit `amount > 1_000_000` check in `createExpenseFromReceipt()`
- Currency resolved as: parsed receipt currency → home currency → `"EUR"`

**Deduplication**:
- `expenseDao.isDuplicateCurrencyAware()` (same policy as other paths)
- `insertAtomic()` IGNORE-on-conflict race guard
- For statements: custom `hasExpenseDuplicateInRangeCurrencyAware()` + `CrossSourceDeduplication` with `DuplicateResolution` strategy (KeepExisting / ReplaceExisting / DiscardNew)

**Currency**:
- From `parsedReceipt.currency` → fallback `homeCurrency` → `"EUR"`
- Hardcoded `"EUR"` for failed parse (`ReceiptRepository` line 211)

**Fake values**:
- `FALLBACK_SUGGESTED_AMOUNT = 0.01` when parser fails
- `"EUR"` hardcoded for failed parse receipts
- `"Parsing Failed"` merchant for failed OCR parse
- `"Unknown Merchant"` for batch scan when merchant not found
- `confidence = 0f` for failed parse

---

### 1d. Manual Entry → Expense

**Entry Point**: `AddExpenseSheet` → `AddExpenseViewModel.save()` (user taps Save in bottom sheet)

**Call Chain**:
```
AddExpenseSheet (UI)
  → AddExpenseViewModel.save() (line 252)
    → AddExpenseViewModel validation:
      - merchant non-blank
      - amount parsed, > 0, <= 1_000_000
      - date not in future
      - transfer validation (direction + account name required for TRANSFER)
      - ownership validation (not both isNotMine and isSharedExpense)
    → manualExpenseRepository.addManualExpense() (line 363)
      → merchantNormalizer.normalize() (autoCreate = true)
      → hybridClassifier.classify() (auto-categorize if no category)
      → expenseDao.isDuplicateCurrencyAware() (dedup check)
      → expenseDao.insertAtomic(expense) (line 171) ← INSERT
      → IF recurring: RecurringExpenseRepository.createRecurringExpenseEntity() + dao.insert()
      → budgetMonitor.checkBudgets()
      → merchantCategoryRepository.learnPattern()
      → Post-commit: anomalyAlertOrchestrator.checkAndAlert()
      → Post-commit: generateTransactionInsightUseCase() + recommendation enrichment (fire-and-forget)
```

**Validation** (in `AddExpenseViewModel`):
- `merchant` must be non-blank
- `amount` must be parseable, > 0, <= 1_000_000
- `date` must not be in the future
- TRANSFER type requires direction + account name
- Ownership flags cannot conflict
- Shared expense requires name + either percentage or amount
- `amount <= 0` → error (in `ManualExpenseRepository`)
- `amount > 1_000_000` → error (in `ManualExpenseRepository`)

**Deduplication**:
- `expenseDao.isDuplicateCurrencyAware()` before insert
- `insertAtomic()` IGNORE-on-conflict race guard
- `generateDedupeKeyWithType()` for dedupeKey

**Currency**: From `_state.value.homeCurrency` (from `CurrencySettingsRepository.homeCurrency()`)

**Fake values**: None — user provides all data.

---

### 1e. Import → Expense

**Entry Point**: `DebugScreen` UI → `CsvExpenseImporter.importFromContent()`

**Call Chain**:
```
DebugScreen (UI)
  → DebugViewModel (csvExpenseImporter)
    → CsvExpenseImporter.importFromContent(csvContent) (line 32)
      → parseAndImportLine(line) (line 75)
        → CSV parsing → date, amount, merchant, categoryName, description
        → getOrCreateCategory(categoryName) → categoryId
        → Expense(...) creation with MINIMAL fields
        → expenseDao.insert(expense) (line 112) ← DIRECT DAO INSERT
```

**Validation**:
- Minimum 4 CSV columns required
- Date must be parseable (`yyyy-MM-dd`)
- Amount must be parseable (EURO sign stripped)
- Category auto-created if not found (with emoji icon)

**⚠️ DANGER: NO DEDUPLICATION WHATSOEVER**
- No `dedupeKey` computed
- No `isDuplicateCurrencyAware` check
- No `merchantKey` set
- No `merchantNormalizer.normalize()` call
- No `merchantKey` normalization
- No `currency` field set (uses `Expense` default `"EUR"`)
- No `isManualEntry` flag set
- Uses `expenseDao.insert()` (not `insertAtomic`), but since dedupeKey is null, unique index won't block

**Currency**: **HARDCODED `"EUR"`** — `currency` field not set at all, relying on Room column default.

**Fake values**: None specific, but many fields are missing (currency default, dedupeKey null, no merchantKey).

---

### 1f. AI/Suggestion → Expense

**Entry Points**: 
1. **AI Assistant** (`AssistantViewModel`): `ExecuteFinancialQueryUseCase` — currently **READ-ONLY**, does NOT create expenses. Only queries/analyzes existing data.
2. **AI Quick Approve** (ReviewScreen): `confirmQuickApprove()` → path 1b — uses AI-suggested category but follows the standard approval path.
3. **AI Suggestion (receipt assist)**: AI pre-fills merchant/amount/date in `ReceiptScanViewModel` → user confirms → path 1c.

**No path exists where AI directly creates an expense without user intervention.** The AI assistant is query-only (no write support yet).

---

### 1g. Recurring → Expense

**Entry Points**:
1. **Manual recurring expense creation**: Created at same time as a manual expense when user checks "isRecurring" → path 1d (`ManualExpenseRepository.addManualExpense()` creates a `ManualRecurringExpense` entity)
2. **Auto-detected recurring patterns**: `RecurringExpenseEngine.detectPatternsFromSnapshots()` analyzes existing expenses but does NOT create new expense instances. It only detects patterns.
3. **RecurringExpensesViewModel**: User can manually add recurring rules (`ManualRecurringExpense`) via `RecurringExpensesScreen`.

**Recurring rules do NOT automatically generate Expense instances.** The recurring system only:
- Stores recurrence rules in `manual_recurring_expenses` table
- Analyzes past expenses to detect patterns
- Forecasts future expenses in the synthesis engine (`SynthesisEngine`)
- Does NOT auto-create Expense rows from recurring rules

**This is a gap**: Recurring expenses are forecasted but never actually created as Expense entities.

---

### 1h. Group/Shared → Expense

**Entry Point**: `SharedExpenseGroupsViewModel.addExpense()` (user taps Add in group detail UI)

**Call Chain**:
```
SharedExpenseGroupsViewModel.addExpense() (line 213)
  → addGroupExpenseUseCase.invokeAtomic() (line 245)
    → AddGroupExpenseUseCase → GroupTransactionCoordinator.createSystemExpenseAndLinkToGroup() (line 412)
      → validateGroup(), validatePayer(), validateSplit()
      → resolveCurrentUserShare()
      → Expense(..., isSharedExpense = true, myShareAmount = currentUserShare, ...)
      → expenseDao.insertAtomic(systemExpense) (line 506) ← INSERT
      → groupExpenseDao.insert(GroupExpense) (line 530)
      → Result with expenseId
```

**Validation**:
- Group must exist and be active
- Payer must be a member
- Split validation (participant validation, custom split JSON format)
- Deduplication: NONE (no `isDuplicateCurrencyAware` check — relies on `insertAtomic` dedupeKey collision only)

**⚠️ MISSING DEDUPLICATION**: Unlike ManualExpenseRepository, `createSystemExpenseAndLinkToGroup()` does NOT call `expenseDao.isDuplicateCurrencyAware()` before insert. It only relies on `insertAtomic`'s IGNORE-on-conflict for the dedupeKey.

**Currency**: From group's `defaultCurrency` (defaults to `"EUR"`)

**Fake values**: `"Group expense via ${payer.name}"` in notes, `"Unknown"` for payer name fallback.

---

## 2. Expense Modification Paths

### 2a. Edit Paths

All edits go through `ExpenseRepository` methods:

| Method | Location | What it does |
|---|---|---|
| `updateExpenseCategory(expense, newCategoryId)` | ExpenseRepository:334 | Category + learning + UserCorrection |
| `updateExpenseCategory(expenseId, categoryId)` | ExpenseRepository:364 | Category only |
| `updateExpenseCategoryBulk(merchant, newCategoryId)` | ExpenseRepository:371 | Bulk category + learning |
| `updateExpenseMerchantBulk(oldMerchant, newMerchant)` | ExpenseRepository:397 | Bulk merchant rename |
| `updateExpenseMerchant(expense, newMerchant, applyToAll)` | ExpenseRepository:406 | Single or bulk merchant rename + dedupeKey recomputation |
| `updateExpenseType(expense, newType)` | ExpenseRepository:441 | Type change + dedupeKey recomputation |
| `updateTransferDetails(...)` | ExpenseRepository:449 | Transfer direction + account |
| `updateNotMineDetails(...)` | ExpenseRepository:468 | isNotMine ownership |
| `updateSharedExpenseDetails(...)` | ExpenseRepository:483 | Shared expense ownership |
| `updateOwnership(...)` | ExpenseRepository:517 | Atomic ownership update (preferred) |
| `updateExpenseLocation(...)` | ExpenseRepository:714 | Location fields |
| `updateMerchantKey(expenseId, merchantKey)` | ExpenseRepository:750 | Backfill merchantKey |

**Direct DAO updates outside ExpenseRepository**:
- `GroupTransactionCoordinator.normalizeLinkedSystemExpense()` (line 585-592): Updates isNotMine, isSharedExpense, mySharePercentage, myShareAmount via individual `expenseDao.update*()` calls
- `MainActivity.applyVisualSplitToExpense()` (line 227-272): Uses `expenseDao.insertAll()` to REPLACE expense with split data — **very dangerous pattern** (loads, modifies, replaces via insertAll with REPLACE strategy)

### 2b. Delete Paths

| Caller | Target | Method |
|---|---|---|
| `ExpenseRepository.deleteExpense(expense)` | Direct DAO | `expenseDao.delete(expense)` |
| `ExpenseRepository.deleteAllExpenses()` | Direct DAO | `expenseDao.deleteAll()` |
| `NotificationRepository.deleteAll()` | Direct DAO | `expenseDao.deleteAll()` |
| `ExpenseRepository.restoreDebugSnapshot()` | Direct DAO | `expenseDao.deleteAll()` then `insertAll()` |

**No soft delete or archive mechanism exists** — expenses are permanently deleted.

### 2c. Batch Operations

| Operation | Location | DAO calls |
|---|---|---|
| Bulk category update by merchant | ExpenseRepository:371 | `expenseDao.updateCategoryForMerchant()` |
| Bulk merchant rename | ExpenseRepository:397 | `expenseDao.updateMerchantForMerchant()` |
| Debug snapshot restore | ExpenseRepository:568 | `expenseDao.deleteAll()` + `insertAll()` |
| Pending review bulk approve | ReviewQueueRepository:359 | Individual `approveReview()` per review |
| Pending review bulk reject | ReviewQueueRepository:370 | Individual `rejectReview()` per review |
| Pending review batch category update | ReviewQueueRepository:564 | `pendingReviewDao.bulkUpdateCategoryByMerchant()` |
| Pending review batch merchant rename | ReviewQueueRepository:569 | `pendingReviewDao.bulkRenameMerchant()` |

---

## 3. Deduplication Logic — Complete Inventory

### Strategy 1: Unique `dedupeKey` Index (Primary Defense)

**Schema**: `@Index(value = ["dedupeKey"], unique = true)` on `Expense` entity

**Generation**: `DuplicateDetectionPolicy.generateDedupeKeyWithType(amount, merchant, date, currency, transactionType)`
- Format when type known: `{amount}_{merchantKey}_{dateBucket}_{currency}_{type}`
- Format when UNKNOWN: `{amount}_{merchantKey}_{dateBucket}_{currency}`
- `dateBucket = date / 300_000` (5-minute buckets)
- Currency is a required parameter

**Used by**: All paths EXCEPT CSV import and Bank API.

### Strategy 2: Range-based Window Check (`isDuplicateCurrencyAware`)

**DAO Query** (in `ExpenseDao`): Checks for existing expenses within 5-minute window, ±0.01 amount tolerance, matching currency + transaction type + merchantKey/dedupeKey.

**Used by** (pre-insert check):
- `ManualExpenseRepository.addManualExpense()` — YES
- `ReviewQueueRepository.approveReview()` — YES
- `NotificationProcessingPipeline.handleAutoAcceptInTransaction()` — YES
- `NotificationProcessingPipeline.handleNeedsReviewInTransaction()` — YES
- `ReceiptRepository.createExpenseFromReceipt()` — YES
- `GroupTransactionCoordinator.createSystemExpenseAndLinkToGroup()` — **NO** (misses this check)
- `CsvExpenseImporter` — **NO** (no dedup at all)
- `EmailReceiptIngestionService.createExpenseFromReceipt()` — **NO** (relies only on fingerprint + insertAtomic)
- `BankApiIntegration` — **NO** (stub, no real insertion)

### Strategy 3: Cross-Source Deduplication

**Class**: `CrossSourceDeduplication`

**Used by**:
- `ReceiptRepository.processStatement()` — resolves between statement transactions and existing pending reviews
- `DetectDuplicateExpenseUseCase` — general-purpose duplicate detection
- `OnDeviceReviewPriorityScorer` — dedup risk scoring for review queue ordering

**Resolution strategies**: `KeepExisting`, `ReplaceExisting`, `DiscardNew`

### Strategy 4: Email Receipt Fingerprint

**In**: `EmailReceiptIngestionService`
- Fingerprint = `merchant_lowercase_amount_dateBucket` (5-minute buckets)
- Checked against `email_receipt_sources` table and `scanned_receipts` table
- Also checks `messageId` (UNIQUE index) for global dedup

### Strategy 5: Raw Notification Dedup

**In**: `NotificationProcessingPipeline.insertRawNotificationIfNotDuplicate()`
- Checks `dao.exists(packageName, timestamp, title, text, bigText)`
- Prevents duplicate raw notifications from the same source

### Summary of Dedup Gaps

| Path | Pre-insert `isDuplicateCurrencyAware` | `insertAtomic` (IGNORE) | Other dedup |
|---|---|---|---|
| Notification → Expense | ✅ | ✅ | Raw notification dedup + pending dedup |
| Pending Review → Expense | ✅ | ✅ | — |
| Receipt → Expense | ✅ | ✅ | — |
| Manual Entry → Expense | ✅ | ✅ | — |
| CSV Import → Expense | ❌ **MISSING** | ❌ uses `insert()` not `insertAtomic` | ❌ **NONE** |
| Email Receipt → Expense | ❌ **MISSING** | ✅ `insertAtomic` | Fingerprint + messageId |
| Group/Shared → Expense | ❌ **MISSING** | ✅ `insertAtomic` | — |
| Bank API → Expense | ❌ (stub) | ❌ (stub) | ❌ (stub) |

---

## 4. Validation Logic — Complete Inventory

### 4a. Amount Validation

| Path | amount > 0 | amount <= 1_000_000 | amount finite |
|---|---|---|---|
| Manual Entry (ViewModel) | ✅ | ✅ | ✅ (via BigDecimal scaling) |
| Manual Entry (Repository) | ✅ `amount <= 0` | ✅ `amount > 1_000_000` | ❌ |
| Pending Review → Expense | ❌ (DB CHECK only) | ✅ `amount > 1_000_000` | ❌ |
| Notification → Expense | ❌ (parser may return 0) | ✅ `parsed.amount > 1_000_000` downgrades to review | ❌ |
| Receipt → Expense | ❌ (trusts user input) | ❌ | ❌ |
| CSV Import | ❌ (`toDoubleOrNull` may be 0) | ❌ | ❌ |
| Email Receipt | ✅ `amount > 0` | ❌ | ❌ |
| Group/Shared | ❌ | ❌ | ❌ |

**Inconsistency**: Different paths check different limits. Manual entry is most thorough. Receipt/Group paths have the least validation.

### 4b. Currency Assignment

| Path | Currency source | EUR fallback |
|---|---|---|
| Manual Entry | `CurrencySettingsRepository.homeCurrency()` flow | `"EUR"` (in state default) |
| Notification | Parsed from text (€/$/£/EUR/USD/GBP regex) | `"EUR"` (when no currency hint) |
| Pending Review | From `review.suggestedCurrency` | `"EUR"` (for fallback reviews) |
| Receipt Scan | Parsed from OCR → homeCurrency → `"EUR"` | `"EUR"` (hardcoded in ReceiptRepository) |
| CSV Import | **NOT SET** → Room default | `"EUR"` (via entity default) |
| Email Receipt | From parsed receipt.currency | `"EUR"` (from ScannedReceipt entity default) |
| Group/Shared | Group's `defaultCurrency` | `"EUR"` (entity default) |
| Bank API | `transaction.currency` | `"EUR"` (from mock data) |

**30+ hardcoded `"EUR"` references found** across entities, parsers, UI components, and view models.

### 4c. Date Validation

| Path | Future date check | Null/sanity check |
|---|---|---|
| Manual Entry | ✅ `date > endOfToday` → error | ❌ |
| Pending Review | ❌ | ✅ Falls back to `notification.timestamp` → `review.createdAt` |
| Notification | ❌ | ✅ Uses `notification.timestamp` |
| Receipt | ❌ | ✅ Falls back to `timeProvider.now()` |
| CSV Import | ❌ | ❌ (throws on parse failure) |
| Email Receipt | ❌ | ✅ Falls back to `receivedAt` |
| Group/Shared | ❌ | ❌ |

### 4d. Fake/Placeholder Values

| File | Location | Fake Value | Reason |
|---|---|---|---|
| `ReviewQueueRepository.kt` | Line 53, 440-446 | `FALLBACK_SUGGESTED_AMOUNT = 0.01`, `"Unknown"` merchant, `confidence = 1.0f`, `"EUR"` | Parser returned null during markAsRelevant |
| `ReceiptRepository.kt` | Line 78, 203-234 | `FALLBACK_SUGGESTED_AMOUNT = 0.01`, `"Parsing Failed"` merchant, `"EUR"`, `confidence = 0f` | OCR/parsing failed |
| `NotificationProcessingPipeline.kt` | Line 176, 231 | `"Unknown"` merchant | Parser couldn't extract merchant hint |
| `ReceiptRepository.kt` | Line 148 | `"Unknown Merchant"` | Batch scan missing merchant |
| `WarrantyTrackerRepository.kt` | Line 267 | `"Unknown Product"` | Warranty extraction failed |
| `Expense.kt` (entity) | Line 55 | `currency: String = "EUR"` | Room column default |
| `DuplicateDetectionPolicy.kt` | Line 32 | `DEFAULT_CURRENCY = "EUR"` | Deprecated but still used |
| `CrossSourceDeduplication.kt` | Various | `(deprecated) DEFAULT_CURRENCY` | Still referenced |
| `ProcessReceiptUseCase.kt` | Line 49 | `merchant ?: "Unknown"` | Normalization fallback |

---

## 5. Direct DAO Access — Complete Inventory

### Calls to `expenseDao.insert*` OUTSIDE `ExpenseRepository.kt`

| File | Line | Method | Context |
|---|---|---|---|
| **`ManualExpenseRepository.kt`** | 171 | `expenseDao.insertAtomic(expense)` | Manual expense creation (repository class) |
| **`ReviewQueueRepository.kt`** | 196 | `expenseDao.insertAtomic(expense)` | Pending review approval (repository class) |
| **`ReviewQueueRepository.kt`** | 482 | `expenseDao.insertAtomic(expense)` | markAsRelevant (repository class) |
| **`NotificationProcessingPipeline.kt`** | 714 | `expenseDao.insertAtomic(expense)` | Auto-accept notification (pipeline class) |
| **`ReceiptRepository.kt`** | 347 | `expenseDao.insertAtomic(expense)` | Receipt → expense creation (repository class) |
| **`GroupTransactionCoordinator.kt`** | 506 | `expenseDao.insertAtomic(systemExpense)` | Group expense creation (coordinator class) |
| **`EmailReceiptIngestionService.kt`** | 392 | `expenseDao.insertAtomic(expense)` | Email receipt → expense creation ⚠️ DIRECT |
| **`CsvExpenseImporter.kt`** | 112 | `expenseDao.insert(expense)` | CSV import ⚠️ DIRECT (no dedupKey, no dedup) |
| **`MainActivity.kt`** | 248 | `expenseDao.insertAll(listOf(expense))` | Split visualization update ⚠️ DIRECT (UI layer!) |
| **`ExpenseRepository.kt`** | 572 | `expenseDao.insertAll(snapshot.expenses)` | Debug snapshot restore (self, OK) |

### Calls to `expenseDao.update*` / `expenseDao.delete*` OUTSIDE `ExpenseRepository.kt`

| File | Lines | Methods | Context |
|---|---|---|---|
| `GroupTransactionCoordinator.kt` | 589-592 | `updateIsNotMine`, `updateIsSharedExpense`, `updateMySharePercentage`, `updateMyShareAmount` | Group expense linking |
| `NotificationRepository.kt` | 128 | `expenseDao.deleteAll()` | Bulk delete (debug) |
| `MainActivity.kt` | 248 | `insertAll` (REPLACE strategy) | Split visualization update |

---

## 6. Source Tracking

### `ExpenseSource` Enum

**No `ExpenseSource` enum exists**. Instead, the app uses an implicit source tracking system:

1. **`isManualEntry`** (Boolean on `Expense`): Differentiates manual vs automatic
2. **`rawNotificationId`** (FK to `RawNotification`): Links to originating notification
3. **`packageName`** on `PendingReview` / `SourceStats`: Identifies which app the notification came from
4. **`SourceStats` entity**: Tracks per-package statistics
5. **Scanned receipt → expense link**: `scanned_receipts.expenseId` links back
6. **`groupId` + `GroupExpense`**: Links to group context

### SourceStats Schema
```kotlin
data class SourceStats(
    val packageName: String,          // PK: e.g., "gr.nbg.mobilebanking"
    val totalNotifications: Long,
    val acceptedAsExpense: Long,
    val rejectedByUser: Long,
    val autoRejected: Long,
    val pendingReview: Long,
    val duplicates: Long,
    val lastSeen: Long
)
```

### Source Stats Updates By Path

| Path | SourceStats calls |
|---|---|
| Notification AUTO_ACCEPT | `incrementTotalAndAccepted()` |
| Notification AUTO_REJECT | `incrementTotalAndAutoRejected()` |
| Notification NEEDS_REVIEW | `incrementTotalAndPending()` |
| Notification DUPLICATE | `incrementTotalAndDuplicate()` |
| Review APPROVED | `incrementAccepted()`, `decrementPending()` |
| Review REJECTED | `incrementRejected()`, `decrementPending()` |
| Review DUPLICATE | `incrementDuplicate()`, `decrementPending()` |
| markAsRelevant (expense) | `incrementAccepted()` or `incrementDuplicate()` |
| markAsRelevant (pending) | `incrementPending()` |

### Event/Audit Ledger

**`UserCorrection`** entity acts as a limited audit log:
- Records original vs corrected values (merchant, amount, category, type)
- Tracks package name, approval/rejection status
- Used by `TransactionClassifier` for retraining

**No comprehensive event ledger exists** for expense lifecycle events (created, modified, deleted).

---

## 7. Post-Creation Side Effects

After an expense is created, these side effects may fire depending on the path:

| Side Effect | Manual | Notification | Review Approve | Receipt | Email | Group |
|---|---|---|---|---|---|---|
| `budgetMonitor.checkBudgets()` | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| `anomalyAlertOrchestrator.checkAndAlert()` | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| `merchantCategoryRepository.learnPattern()` | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ |
| `classifier.train()` / `retrainFromCorrections()` | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ |
| `hybridClassifier.learnFromCorrection()` | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| `confidenceRouter.invalidateSourceStatsCache()` | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ |
| `merchantNormalizer.learnMerchantAlias()` | ❌ | ❌ | ✅ (if merchant changed) | ❌ | ❌ | ❌ |
| AI recommendation generation (fire-and-forget) | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| Subscription detection (fire-and-forget) | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| Transfer direction analytics | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| Warranty extraction | ❌ | ❌ | ❌ | ✅ (F1) | ❌ | ❌ |
| `sourceStatsDao.incrementAccepted()` | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ |
| `scannedReceiptDao.linkToExpense()` | ❌ | ❌ | ✅ (if linked) | ✅ | ✅ | ❌ |
| `rawNotificationDao.markRelevance()` | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ |
| Recurring rule creation | ✅ (if recurring) | ❌ | ❌ | ❌ | ❌ | ❌ |
| `userCorrectionDao.insert()` | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |

**Inconsistency**: The side effect profile varies significantly by path. For example:
- Manual entry gets AI recommendations but NOT classifier training
- Notification auto-accept gets subscription detection but NOT merchant-category learning
- Receipt → expense gets warranty extraction but NOT source stats updates
- Group → expense gets NO side effects at all (no budget check, no anomaly check)
- Email receipt → expense gets NO standard side effects

---

## 8. ExpenseRepository Method Inventory

### Methods that CREATE or MODIFY expenses in `ExpenseRepository.kt`

| Method | Type | Description |
|---|---|---|
| `deleteExpense(expense)` | DELETE | Deletes a single expense |
| `updateExpenseCategory(expense, newCategoryId)` | UPDATE | Category + learning + UserCorrection |
| `updateExpenseCategory(expenseId, categoryId)` | UPDATE | Category only |
| `updateExpenseCategoryBulk(merchant, newCategoryId)` | UPDATE | Bulk category + learning |
| `updateExpenseMerchantBulk(oldMerchant, newMerchant)` | UPDATE | Bulk merchant rename |
| `updateExpenseMerchant(expense, newMerchant, applyToAll)` | UPDATE | Single/bulk merchant rename + dedupeKey recompute |
| `updateExpenseType(expense, newType)` | UPDATE | Type + dedupeKey recompute |
| `updateTransferDetails(...)` | UPDATE | Transfer metadata |
| `updateNotMineDetails(...)` | UPDATE | isNotMine ownership |
| `updateSharedExpenseDetails(...)` | UPDATE | Shared expense ownership |
| `updateOwnership(...)` | UPDATE | Atomic ownership update |
| `deleteAllExpenses()` | DELETE | Delete all expenses |
| `restoreDebugSnapshot(snapshot)` | CREATE/DELETE | Replace all expenses with snapshot |
| `updateExpenseLocation(...)` | UPDATE | Location fields |
| `clearExpenseLocation(expenseId)` | UPDATE | Clear location |
| `updateMerchantKey(expenseId, merchantKey)` | UPDATE | Merchant key backfill |

**⚠️ `ExpenseRepository` has NO `insertExpense()` / `createExpense()` method.** Creation is delegated to:
- `ManualExpenseRepository.addManualExpense()`
- `ReviewQueueRepository.approveReview()`
- `NotificationProcessingPipeline.handleAutoAcceptInTransaction()`
- `ReceiptRepository.createExpenseFromReceipt()`
- `GroupTransactionCoordinator.createSystemExpenseAndLinkToGroup()`
- `EmailReceiptIngestionService.createExpenseFromReceipt()`
- `CsvExpenseImporter` (direct DAO)
- `MainActivity` (direct DAO)

---

## 9. Anti-Patterns & Risks Found

### 🔴 CRITICAL

1. **No central expense creation method**: 8 different paths with 6 different repository/service classes all calling `expenseDao.insert*()` independently. Each path has its own validation, deduplication, and side-effect logic.

2. **Missing deduplication in critical paths**:
   - `CsvExpenseImporter` — no dedup check, no dedupeKey, uses `insert()` not `insertAtomic()`
   - `GroupTransactionCoordinator.createSystemExpenseAndLinkToGroup()` — no `isDuplicateCurrencyAware()` check
   - `EmailReceiptIngestionService.createExpenseFromReceipt()` — no `isDuplicateCurrencyAware()` check

3. **UI layer directly accesses DAO**: `MainActivity.applyVisualSplitToExpense()` calls `expenseDao.insertAll()` directly — bypassing all repositories and validation.

4. **`MainActivity` insertAll with REPLACE strategy**: Loads an expense, modifies share fields, then re-inserts it. This could silently overwrite concurrent changes.

5. **CSV importer bypasses ALL lifecycle logic**: No dedup, no validation, no side effects. Uses bare `expenseDao.insert()`.

6. **No ExpenseRepository.createExpense() method**: Despite being the central repository, it has no method to create new expenses from scratch.

### 🟡 HIGH

7. **Inconsistent side-effect profiles**: Post-creation side effects differ across paths (e.g., group expenses get NO budget checking).

8. **Hardcoded "EUR" everywhere**: Entity defaults, parser fallbacks, UI defaults — 30+ locations. No single source of truth for home currency.

9. **Duplicate dedup strategies**: Three different strategies (unique index, range check, cross-source) that may not be consistent.

10. **Missing date validation**: Most paths don't validate for future dates (only manual entry checks).

11. **No event/audit ledger**: `UserCorrection` is a partial solution but there's no comprehensive audit trail for expense lifecycle events.

### 🟡 MEDIUM

12. **PendingReview entity tightly coupled**: Used by notifications, receipts, statements, and markAsRelevant — with slightly different fields populated in each case.

13. **`RecurringExpenseEngine` detects patterns but doesn't create expenses**: No auto-generation of recurring expense instances.

14. **`PlannedExpense` is a separate entity**, not linked to the `Expense` table. Planned expenses never become real expenses.

15. **Source tracking is implicit**: No `ExpenseSource` enum; relies on nullable foreign keys and boolean flags.

16. **`confidence = 1.0f`** used as a fallback for "Unknown" pending reviews — may skew confidence-based routing.

17. **`0.01` sentinel amounts** used when parser fails — could cause false-positive dedup with real €0.01 transactions.

---

## 10. Recommended Coordinator Design

Based on the audit findings, `TransactionLifecycleCoordinator` should be a **single entry point** that:

### Required Methods

```kotlin
class TransactionLifecycleCoordinator @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val merchantNormalizer: MerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val budgetMonitor: BudgetMonitor,
    private val anomalyAlertOrchestrator: AnomalyAlertOrchestrator,
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val sourceStatsDao: SourceStatsDao,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val timeProvider: TimeProvider,
    private val appScope: CoroutineScope
) {

    /**
     * THE single entry point for ALL expense creation.
     * Replaces: ManualExpenseRepository.addManualExpense(),
     *           ReviewQueueRepository.approveReview(),
     *           NotificationProcessingPipeline.handleAutoAcceptInTransaction(),
     *           ReceiptRepository.createExpenseFromReceipt(),
     *           GroupTransactionCoordinator.createSystemExpenseAndLinkToGroup(),
     *           EmailReceiptIngestionService.createExpenseFromReceipt(),
     *           CsvExpenseImporter (direct DAO),
     *           MainActivity (direct DAO)
     */
    suspend fun createExpense(
        request: CreateExpenseRequest,
        source: ExpenseSource,        // ← NEW enum: MANUAL, NOTIFICATION, REVIEW_APPROVAL, RECEIPT, CSV_IMPORT, EMAIL_IMPORT, GROUP, BANK_API, DEBUG_TOOL
        sideEffects: SideEffectConfig = SideEffectConfig.ALL
    ): Result<Long>
    
    /**
     * Standardized update method with audit trail.
     */
    suspend fun updateExpense(
        expenseId: Long,
        updates: ExpenseUpdates,
        reason: String? = null
    ): Result<Unit>
    
    /**
     * Standardized delete with archive support.
     */
    suspend fun deleteExpense(
        expenseId: Long,
        reason: String? = null
    )
    
    /**
     * Pre-creation duplicate check for callers that need to check before creating.
     */
    suspend fun checkDuplicate(
        amount: Double, 
        merchant: String, 
        date: Long, 
        currency: String,
        transactionType: TransactionType
    ): Boolean
}
```

### Unified `CreateExpenseRequest` Data Class

Should consolidate all fields used across the 8 paths:
- `merchant`, `amount`, `currency`, `date`, `transactionType`
- `categoryId` (nullable → auto-classify if null)
- `notes`, `paymentMethod`, `isManualEntry`
- Transfer fields: `transferDirection`, `transferAccountName`
- Ownership fields: `isNotMine`, `ownerName`, `isSharedExpense`, `sharedWithName`, `mySharePercentage`, `myShareAmount`
- Location fields: `latitude`, `longitude`, `locationSource`, `placeId`, `address`
- Source tracking: `rawNotificationId`, `scannedReceiptId`, `source`
- Dedup override: `skipDeduplication` (for debug/restore operations)

### Non-negotiable invariants the coordinator MUST enforce

1. **Deduplication FIRST**: Always check `isDuplicateCurrencyAware()` before insert
2. **DedupeKey generation**: Always compute via `DuplicateDetectionPolicy.generateDedupeKeyWithType()`
3. **Merchant normalization**: Always normalize via `MerchantNormalizer.normalize()`
4. **Currency**: Always require explicit currency; never default silently
5. **Side-effect consistency**: Run uniform set of post-creation actions for ALL paths
6. **Audit trail**: Write a `TransactionEvent` record for every create/update/delete

### `ExpenseSource` Enum (NEW — currently missing)

```kotlin
enum class ExpenseSource {
    MANUAL_ENTRY,
    NOTIFICATION_AUTO_ACCEPT,
    REVIEW_APPROVAL,
    RECEIPT_SCAN,
    CSV_IMPORT,
    EMAIL_RECEIPT,
    GROUP_EXPENSE,
    BANK_API_SYNC,
    DEBUG_TOOL,
    UNKNOWN
}
```

### Migration Strategy

1. Create `TransactionLifecycleCoordinator` with all methods
2. Create `ExpenseSource` enum and add column to `Expense` table (nullable for back-compat)
3. Add `TransactionEvent` table for audit logging
4. One-by-one, refactor each creation path to call the coordinator
5. After all paths consolidated, deprecate direct `expenseDao.insert*()` calls outside coordinator
6. Remove `CsvExpenseImporter`'s direct DAO dependency (inject coordinator)
7. Remove `MainActivity`'s `expenseDao` dependency (use coordinator)
8. Consolidate/remove redundant repositories and pipeline classes

---

*End of Audit Report*
