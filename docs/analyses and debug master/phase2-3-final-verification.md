# Phase 2 & 3 — Final Verification Audit

**Date:** 2026-05-01
**Scope:** Full codebase at `app/src/main/java/com/yourname/expensetracker/`
**Schema version:** 95

---

## 1. Remaining System.currentTimeMillis() — Full Inventory

**Total hits: 58** across 25 files. Categorized below.

### 1a. Whitelisted

| File | Line | Usage | Reason |
|------|------|-------|--------|
| `domain/util/SystemTimeProvider.kt` | 12 | `override fun now(): Long = System.currentTimeMillis()` | Official TimeProvider implementation — single source of truth |
| `domain/util/TimeProvider.kt` | 7, 22, 38 | Comments only | Interface documentation |
| `data/database/AppDatabase.kt` | 440 | `val now = System.currentTimeMillis()` inside `MIGRATION_67_68` | Migration code — runs once at upgrade; acceptable |
| `data/database/AppDatabase.kt` | 1256 | `val now = System.currentTimeMillis()` inside `MIGRATION_42_43` | Migration code — runs once at upgrade; acceptable |

### 1b. Acceptable Defaults

These are Kotlin default parameter values that provide a fallback when no explicit timestamp is passed. Most callers DO pass an explicit timestamp. These are standard in DAO interfaces:

| File | Lines | Count |
|------|-------|-------|
| `dao/RecommendationDao.kt` | 36, 56, 76, 106, 125, 139, 148, 184, 190 | **9** |
| `dao/SubscriptionCandidateDao.kt` | 79, 90 | **2** |
| `dao/SplitTemplateDao.kt` | 28 | **1** |
| `dao/SplitItemAssignmentDao.kt` | 31 | **1** |
| `dao/SpendingPersonalityProfileDao.kt` | 70 | **1** |
| `dao/SavingsSweepPlanDao.kt` | 94, 100, 106, 112 | **4** |
| `dao/BudgetAdjustmentDao.kt` | 48, 51, 54 | **3** |
| `dao/AiArtifactDao.kt` | 52, 55 | **2** |
| `domain/debug/AiRuntimeDiagnostics.kt` | 20, 30, 34 | **3** |
| `domain/parser/AppParserRegistry.kt` | 37 | **1** (`validationNowEpochMs` default — annotated `@Deprecated`) |
| `ui/screens/home/HomeScreen.kt` | 1150 | **1** (Composable default param `referenceNowMillis`) |
| `ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt` | 460 | **1** (Composable default param `referenceNowMillis`) |
| **Subtotal** | | **30** |

### 1c. Debug/Diagnostics

| File | Lines | Count | Purpose |
|------|-------|-------|---------|
| `ui/screens/debug/DebugViewerScreen.kt` | 156, 264, 400 | **3** | Clipboard JSON export from debug screen |
| `ui/screens/debug/DebugViewModel.kt` | 453 | **1** | Refresh signal emission |
| `ui/screens/debug/CategorizationDebugScreen.kt` | 70, 321 | **2** | Debug timestamp for "now" line |
| `domain/debug/ServiceDiagnostics.kt` | 35, 44 | **2** | Record restart/kill timestamps |
| `domain/debug/NotificationSeeder.kt` | 33, 88, 110, 134, 147, 160 | **6** | Seed test notification data |
| `domain/health/FinancialHealthScoreV2.kt` | 82, 189 | **2** | Performance timing (startTime/duration) |
| `domain/forecasting/FinancialStressForecastEngine.kt` | 66, 134 | **2** | Performance timing (startTime/duration) |
| **Subtotal** | | **18** | |

### 1d. Acceptable Utility

| File | Line | Usage | Reason |
|------|------|-------|--------|
| `data/repository/DatabaseBackupRepositoryImpl.kt` | 327 | `stagedDbName = "$IMPORT_STAGING_PREFIX${System.currentTimeMillis()}"` | Unique file naming for staged DB import |
| `domain/receipt/ReceiptOcrService.kt` | 591, 608 | `"receipt_${System.currentTimeMillis()}.jpg"` / `"camera_${System.currentTimeMillis()}.jpg"` | Unique temp file naming |
| **Subtotal** | | **3** | |

### 1e. UNEXPECTED — Needs Fixing

| File | Line | Code | Issue |
|------|------|------|-------|
| `data/ai/provider/DefaultAiEnvironmentMonitor.kt` | 62 | `val now = System.currentTimeMillis()` | Direct wall-clock call. Should use injected `TimeProvider` for determinism in tests. |

**Verdict:** Only 1 unexpected `System.currentTimeMillis()` remains. Low severity — `DefaultAiEnvironmentMonitor` is AI infrastructure, not expense-creation logic.

---

## 2. Anti-Patterns Scan

### 2a. Instant.now() / LocalDate.now() / LocalDateTime.now()

| Occurrences | Location | Status |
|-------------|----------|--------|
| 0 actual code | — | ✅ Clean |
| 3 comment-only | `TimeProvider.kt` lines 8, 8, 8 | ✅ Documentation only |

**Verdict: PASS** — zero violations.

### 2b. Raw Millis Day Math

Pattern searched: `/(1000 * 60 * 60 * 24)`, `/86400000`, `/86_400_000`

| Occurrences | Status |
|-------------|--------|
| 0 | ✅ Clean |

**Verdict: PASS** — no raw millis day division.

### 2c. Inclusive End (23:59:59)

| File | Line | Context | Status |
|------|------|---------|--------|
| `domain/util/TimePeriodUtils.kt` | 36 | Comment: "not 23:59:59.999" | ✅ Comment only |
| `ui/screens/transactions/TransactionFilterSheet.kt` | 262 | Comment: "no 23:59:59 clamping" | ✅ Comment only |

**Verdict: PASS** — zero occurrences in actual code logic.

### 2d. Hard-coded Period Math

Pattern searched: `365L * 24 * 60 * 60 * 1000`, `30L * 24 * 60 * 60 * 1000`, `90L * 24 * 60 * 60 * 1000`

| File | Line | Expression | Notes |
|------|------|-----------|-------|
| `domain/config/AppConfig.kt` | 72, 107, 110, 113, 116 | `30L * 24 * 60 * 60 * 1000L` | **Acceptable**: Cache TTL constants, not date calculations |
| `data/email/EmailReceiptIngestionService.kt` | 354 | `timeProvider.now() - (30L * 24 * 60 * 60 * 1000)` | Rolling 30-day window. Uses `timeProvider.now()` ✅ but could use `PeriodKind.LAST_30_DAYS` |
| `domain/subscription/SubscriptionManagerEngine.kt` | 286 | `timeProvider.now() - (90L * 24 * 60 * 60 * 1000)` | Rolling 90-day check. Uses `timeProvider.now()` ✅ |
| `domain/lifestyle/LifestyleInflationDetector.kt` | 27 | `monthsToAnalyze * 30L * 24 * 60 * 60 * 1000` | ⚠️ Approximate month math. Should use calendar-aware period. |
| `domain/health/FinancialHealthScoreV2.kt` | 625 | `timeProvider.now() - (90L * 24 * 60 * 60 * 1000)` | Uses `timeProvider.now()` ✅ but hard-coded period |
| `domain/carbon/CarbonFootprintCalculator.kt` | 125 | `resolvedEndDate - (30L * 24 * 60 * 60 * 1000)` | Uses timestamp math |

**Verdict:** **5 files** still use hard-coded period math. While 3 use `timeProvider.now()` (good), they should ideally use `PeriodKind` + `TimePeriodUtils` helpers for calendar-accuracy. Low-to-medium severity.

### 2e. Entity = System.currentTimeMillis() Defaults

Searched all files in `data/database/entity/`:

| Result | Status |
|--------|--------|
| 0 entity files with `= System.currentTimeMillis()` default | ✅ Clean |

**Verdict: PASS**

### 2f. getLastNDaysRange Callers

| Location | Type | Status |
|----------|------|--------|
| `domain/util/TimePeriodUtils.kt` line 526 | Definition (deprecated) | ✅ Only definition |
| `domain/core/time/PeriodKind.kt` line 37 | Comment warning against use | ✅ Documentation only |
| External callers | **0** | ✅ Clean |

**Verdict: PASS** — 0 external callers of the deprecated function.

---

## 3. Phase 3 Implementation Verification

### 3a. Direct DAO Inserts Outside Coordinator

Files searched for `expenseDao.insert(` and `expenseDao.insertAtomic(`:

| File | Line | Call | Status |
|------|------|------|--------|
| `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt` | 138 | `expenseDao.insertAtomic(expense)` | ✅ LEGITIMATE — coordinator's own insert |
| `data/repository/ReviewQueueRepository.kt` | 534 | `expenseDao.insertAtomic(expense)` | ❌ **VIOLATION** — `markAsRelevant()` bypasses coordinator |
| `data/repository/ExpenseRepository.kt` | 600 | `expenseDao.insertAll(snapshot.expenses)` | ✅ Debug-only snapshot restore (not a creation path) |

**Details on the violation:**
- **Method:** `ReviewQueueRepository.markAsRelevant()` (line 426-602)
- **Context:** When a raw notification is marked as relevant AND the parser produces a valid `Expense` object, the method inserts directly via `expenseDao.insertAtomic(expense)` instead of going through `TransactionLifecycleCoordinator.createExpense()`.
- **Contrast:** The sibling method `approveReview()` (line 178-259) correctly uses the coordinator.
- **Impact:** Expenses created via `markAsRelevant()` skip: validation, deduplication, lifecycle event logging, and post-creation side effects.

**Verdict: 1 violation found that needs fixing.**

### 3b. MainActivity Cleanup

Searched `MainActivity.kt` for `expenseDao`:

| Result | Status |
|--------|--------|
| 0 references to `expenseDao` | ✅ Clean |

**Verdict: PASS**

### 3c. CSV Import

**File:** `util/CsvExpenseImporter.kt`

| Check | Result | Status |
|-------|--------|--------|
| Uses `TransactionLifecycleCoordinator`? | Yes — `coordinator.createExpense(request)` at line 146 | ✅ |
| Uses `CreateExpenseRequest`? | Yes — line 135 | ✅ |
| Sets `ExpenseSource.CSV_IMPORT`? | Yes — line 141 | ✅ |
| Sets `currency` field? | Yes — but **hardcoded `"EUR"`** at line 138 | ⚠️ Should ideally use parsed currency |

**Verdict:** Coordinator usage is correct. Minor concern: hardcoded EUR currency.

### 3d. TransactionEvent Coverage

TransactionEvent records are written in exactly **3 places**, all in `TransactionLifecycleCoordinator.kt`:

| Event Type | Line | Triggered By | Status |
|-----------|------|-------------|--------|
| `LifecycleEventType.CREATED` | 145 | `createExpense()` success | ✅ |
| `LifecycleEventType.UPDATED` | 180 | `updateExpense()` | ✅ |
| `LifecycleEventType.DELETED` | 226 | `deleteExpense()` | ✅ |

**Missing events** (defined in `LifecycleEventType` but never written):
- `CREATE_ATTEMPTED` — not written (minor)
- `CREATE_VALIDATION_FAILED` — not written (could be added for audit)
- `CREATE_DUPLICATE_SKIPPED` — not written (could be added)
- `CREATE_INSERT_CONFLICT` — not written (could be added)
- `BULK_UPDATED` — no bulk update path yet
- `RESTORED_FROM_DEBUG_SNAPSHOT` — debug-only
- `SOURCE_LINKED` — not implemented
- `SIDE_EFFECT_FAILED` — not written

**Verdict:** Core create/update/delete events are covered. Optional audit events (validation failures, duplicate skips, conflicts) are not yet written but are low severity.

### 3e. Coordinator Adoption Count

Files that inject `TransactionLifecycleCoordinator`:

| # | File | Line | Role |
|---|------|------|------|
| 1 | `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt` | 33 | **Definition** |
| 2 | `data/repository/NotificationProcessingPipeline.kt` | 90 | Consumer |
| 3 | `data/repository/ReceiptRepository.kt` | 67 | Consumer |
| 4 | `data/repository/ReviewQueueRepository.kt` | 46 | Consumer |
| 5 | `data/repository/ManualExpenseRepository.kt` | 51 | Consumer |
| 6 | `domain/bank/BankApiIntegration.kt` | 57 | Consumer |
| 7 | `di/DatabaseModule.kt` | 45 | Provider (wires into GroupTransactionCoordinator) |
| 8 | `data/database/GroupTransactionCoordinator.kt` | 55 | Consumer |
| 9 | `data/email/EmailReceiptIngestionService.kt` | 64 | Consumer |
| 10 | `util/CsvExpenseImporter.kt` | 31 | Consumer |
| 11 | `data/repository/ExpenseRepository.kt` | 68 | Consumer |

**Total: 11 files** (1 definition + 10 consumers).

**Verdict:** Strong adoption. All major creation paths use the coordinator.

### 3f. ExpenseSource Usage

Files that set `ExpenseSource` on a `CreateExpenseRequest`:

| # | File | Source Value | Status |
|---|------|-------------|--------|
| 1 | `data/repository/ManualExpenseRepository.kt` | `MANUAL_ENTRY` | ✅ |
| 2 | `data/repository/ReviewQueueRepository.kt` (approveReview) | `REVIEW_APPROVAL` | ✅ |
| 3 | `data/repository/NotificationProcessingPipeline.kt` | `NOTIFICATION_AUTO_ACCEPT` | ✅ |
| 4 | `data/repository/ReceiptRepository.kt` | `RECEIPT_SCAN` | ✅ |
| 5 | `domain/bank/BankApiIntegration.kt` | `BANK_API_SYNC` | ✅ |
| 6 | `data/database/GroupTransactionCoordinator.kt` | `GROUP_EXPENSE` | ✅ |
| 7 | `data/email/EmailReceiptIngestionService.kt` | `EMAIL_RECEIPT` | ✅ |
| 8 | `util/CsvExpenseImporter.kt` | `CSV_IMPORT` | ✅ |

**Sources NOT yet set on CreateExpenseRequest (enum values defined but unused):**
- `RECEIPT_BATCH_REVIEW`
- `BANK_STATEMENT_REVIEW`
- `RECURRING_GENERATED`
- `DEBUG_TOOL`
- `MIGRATION`
- `UNKNOWN`

**Verdict:** 8 of 13 defined sources are in active use. The unsed ones represent feature paths not yet migrated to the coordinator (future work).

---

## 4. Cross-Phase Consistency

### 4a. TimeProvider in Phase 3 Code

| File | Uses `TimeProvider.now()`? | Notes |
|------|---------------------------|-------|
| `TransactionLifecycleCoordinator.kt` | ✅ Yes (line 47, 176, 222) | 3 usages — all correct |
| `TransactionSideEffectDispatcher.kt` | N/A | No time-dependent logic |
| `CsvExpenseImporter.kt` | N/A | Uses parsed CSV dates; no current-time needed |
| `ReviewQueueRepository.kt` (approveReview) | ✅ Via coordinator | Coordinator provides time |
| `ReviewQueueRepository.kt` (markAsRelevant) | ✅ Yes (line 470) | Uses `timeProvider.now()` for `createdAt` |
| `CreateExpenseRequest.kt` | N/A | Pure data class |

**Verdict:** All Phase 3 new code uses `TimeProvider` correctly. No direct `System.currentTimeMillis()` in any Phase 3 file.

### 4b. Currency Handling

| Check | Result | Status |
|-------|--------|--------|
| `CreateExpenseRequest.currency` is required non-nullable `String` | ✅ Yes, line 61 | ✅ |
| Coordinator validates currency not blank | ✅ Yes, line 268-269 | ✅ |
| Coordinator passes currency to `DuplicateDetectionPolicy.generateDedupeKeyWithType` | ✅ Yes, line 61 | ✅ |
| Coordinator passes currency to `Expense` entity construction | ✅ Yes, line 68 | ✅ |
| Coordinator passes currency to `isDuplicateCurrencyAware` | ✅ Yes, line 124 | ✅ |
| Hardcoded `"EUR"` in Phase 3 code? | CsvExpenseImporter line 138 | ⚠️ Hardcoded fallback for CSV import |
| Hardcoded `"EUR"` in ReviewQueueRepository? | Line 493 (`suggestedCurrency = "EUR"`) | ⚠️ Fallback for notification placeholder |
| Hardcoded `"EUR"` in coordinator? | **0** | ✅ Clean |

**Verdict:** Currency is properly handled as a first-class field through the coordinator. The two hardcoded `"EUR"` occurrences are in legacy fallback paths (CSV import assumes EUR base; notification fallback uses EUR placeholder) — not ideal but acceptable given those features' limited scope.

### 4c. Schema Version

| Check | Value | Status |
|-------|-------|--------|
| `APP_DATABASE_SCHEMA_VERSION` | **95** | ✅ |
| `MIGRATION_94_95` exists? | Yes | ✅ |
| Migration creates `transaction_events` table? | Yes (line 5636-5649) | ✅ |
| Migration adds `source` column to `expenses`? | Yes (line 5633) | ✅ |
| Migration adds indexes on `transaction_events`? | Yes (expenseId, source, occurredAt, eventType — lines 5652-5655) | ✅ |
| Migration registered in `ALL_MIGRATIONS`? | Yes (line 5792) | ✅ |

**Verdict: PASS** — Schema version 95 is correct with proper migration.

---

## 5. New Files Inventory (Phase 2 + Phase 3)

### Phase 3 — Transaction Lifecycle

| File | Package | Purpose |
|------|---------|---------|
| `domain/transaction/CreateExpenseRequest.kt` | `domain.transaction` | Request DTO for expense creation through coordinator |
| `domain/transaction/CreateExpenseResult.kt` | `domain.transaction` | Sealed result type (Created, DuplicateSkipped, ValidationFailed, InsertConflict, Error) |
| `domain/transaction/DeduplicationMode.kt` | `domain.transaction` | Dedup policy enum (STANDARD, STRICT_EXTERNAL_ID, BULK_IMPORT, SKIP_FOR_DEBUG_RESTORE) |
| `domain/transaction/ExpenseSource.kt` | `domain.transaction` | Source enum (13 values from MANUAL_ENTRY to UNKNOWN) |
| `domain/transaction/ExpenseUpdates.kt` | `domain.transaction` | Patch-style update request DTO |
| `domain/transaction/LifecycleEventType.kt` | `domain.transaction` | Lifecycle event type enum (15 event types) |
| `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt` | `domain.transaction.lifecycle` | Central coordinator (validate → normalize → dedupe → insert → event → side effects) |
| `domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt` | `domain.transaction.lifecycle` | Post-creation side effect dispatcher |
| `data/database/entity/TransactionEvent.kt` | `data.database.entity` | Room entity for lifecycle events |
| `data/database/dao/TransactionEventDao.kt` | `data.database.dao` | DAO for transaction events |

### Phase 2 — Time/Period Semantics

| File | Package | Purpose |
|------|---------|---------|
| `domain/core/time/PeriodKind.kt` | `domain.core.time` | Semantic period classification enum (TODAY, THIS_WEEK, LAST_7_DAYS, THIS_MONTH, etc.) |
| `domain/core/time/PeriodRange.kt` | `domain.core.time` | Typed half-open range [startInclusive, endExclusive) with period metadata |

### Phase 2 — Currency/Money Foundation

| File | Package | Purpose |
|------|---------|---------|
| `domain/core/money/CurrencyCode.kt` | `domain.core.money` | Currency code value object |
| `domain/core/money/CurrencyAssumption.kt` | `domain.core.money` | Currency assumption tracking |
| `domain/core/money/MoneyAmount.kt` | `domain.core.money` | Money amount value object |
| `domain/core/money/MoneyBucket.kt` | `domain.core.money` | Money aggregation bucket |
| `domain/core/money/MoneyAggregate.kt` | `domain.core.money` | Aggregate money calculations |
| `domain/core/money/ConvertedMoney.kt` | `domain.core.money` | Converted money representation |
| `domain/core/money/ConversionFailure.kt` | `domain.core.money` | Conversion error types |
| `domain/core/money/MoneyMappers.kt` | `domain.core.money` | Mapping utilities |
| `domain/core/money/MoneyFormatUtils.kt` | `domain.core.money` | Formatting utilities |

**Total new files: 21** (10 Phase 3 + 2 Phase 2 time + 9 Phase 2 money/currency)

---

## 6. Overall Verdict

### Phase 2 Completion: **95%**

| Criteria | Status |
|----------|--------|
| No `Instant.now()`/`LocalDate.now()`/`LocalDateTime.now()` direct calls | ✅ PASS |
| No raw millis day math (`/86400000`) | ✅ PASS |
| No inclusive-end anti-pattern (`23:59:59`) in logic | ✅ PASS |
| `PeriodKind` and `PeriodRange` defined and used | ✅ PASS |
| `getLastNDaysRange` deprecated with 0 external callers | ✅ PASS |
| Hard-coded period math still present in 5 files | ⚠️ Minor (functions mostly use `timeProvider.now()` already) |
| `System.currentTimeMillis()` in `DefaultAiEnvironmentMonitor` | ⚠️ 1 unexpected, low severity |

### Phase 3 Completion: **85%**

| Criteria | Status |
|----------|--------|
| TransactionLifecycleCoordinator created | ✅ PASS |
| TransactionEvent DAO and entity created | ✅ PASS |
| MIGRATION_94_95 creates transaction_events table | ✅ PASS |
| Coordinator uses `TimeProvider.now()` everywhere | ✅ PASS |
| Currency is a first-class required field in CreateExpenseRequest | ✅ PASS |
| Coordinator adoption in 10 consumer classes | ✅ PASS |
| ExpenseSource set on 8 creation paths | ✅ PASS |
| Create/Update/Delete lifecycle events all written | ✅ PASS |
| CsvExpenseImporter uses coordinator correctly | ✅ PASS |
| MainActivity has no expenseDao references | ✅ PASS |
| **Direct DAO insert in ReviewQueueRepository.markAsRelevant()** | ❌ **1 VIOLATION** |
| `markAsRelevant()` bypasses coordinator entirely (no validation, no dedup, no events) | ❌ Needs fix |
| Several LifecycleEventTypes never written (validation fails, duplicates, conflicts) | ⚠️ Enhancement |

### Critical Issues

| # | Severity | Description | Location |
|---|----------|-------------|----------|
| 1 | **HIGH** | `markAsRelevant()` bypasses TransactionLifecycleCoordinator — direct `expenseDao.insertAtomic()` without validation, dedup check, or lifecycle event logging | `ReviewQueueRepository.kt:534` |
| 2 | **LOW** | `DefaultAiEnvironmentMonitor` uses `System.currentTimeMillis()` directly instead of injected `TimeProvider` | `DefaultAiEnvironmentMonitor.kt:62` |
| 3 | **LOW** | Hardcoded `currency = "EUR"` in CSV import and notification placeholder paths | `CsvExpenseImporter.kt:138`, `ReviewQueueRepository.kt:493` |
| 4 | **LOW** | Hard-coded period math in 5 files (not calendar-aware) | See Section 2d |

### Recommended Actions

1. **Fix HIGH issue:** Migrate `ReviewQueueRepository.markAsRelevant()` to use `TransactionLifecycleCoordinator.createExpense()` instead of directly calling `expenseDao.insertAtomic()`. The `Expense` entity parsed in that method should be converted to a `CreateExpenseRequest`.

2. **Fix LOW issue:** Inject `TimeProvider` into `DefaultAiEnvironmentMonitor` and replace the direct `System.currentTimeMillis()` call.

3. **Enhancement:** Add TransactionEvent logging for `CREATE_VALIDATION_FAILED`, `CREATE_DUPLICATE_SKIPPED`, and `CREATE_INSERT_CONFLICT` events in the coordinator for complete audit trail.

4. **Enhancement:** Migrate hard-coded period math in the 5 identified files to use `PeriodKind` + `TimePeriodUtils` calendar-aware helpers.

5. **Enhancement:** Consider parsing currency from CSV headers or providing a configurable default rather than hardcoding `"EUR"` in `CsvExpenseImporter`.

---

*Audit completed by codebase scout — exhaustive search across all 100+ `.kt` files in `app/src/main/java/`.*
