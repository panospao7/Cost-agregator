# Test Suite Audit

**Date:** 2026-05-04
**Repo:** ExpenseTracker
**Scope:** `app/src/test/java/com/yourname/expensetracker/`

---

## 1. Inventory

### Total Count: 402 active `.kt` test files + 4 `.bak` dead files = 406 files total

### By Package / Directory

| Package | Count | Description |
|---------|-------|-------------|
| **Root** | 2 | `AnalyticsEngineTestBase.kt`, `AnalyticsTestCompat.kt` |
| **consistency/** | 16 | Cross-parser, arithmetic precision, temporal, merchant key, etc. |
| **concurrency/** | 0 | Only `DaoConcurrencyTest.kt.bak` (dead) |
| **currency/** | 1 | `CanonicalMultiCurrencyFixture.kt` (fixture, not a test) |
| **data/** | — | (see sub-packages below) |
| ├─ **ai/provider/** | 21 | Cloud + on-device AI service unit tests |
| ├─ **ai/worker/** | 1 | `DailyBriefingWorkerTest.kt` |
| ├─ **currency/** | 1 | `ExchangeRateStoreAdapterTest.kt` |
| ├─ **database/converter/** | 1 | `ConvertersTest.kt` |
| ├─ **database/dao/** | 3 | BankConnectionDao, ExpenseDaoBoundaryConsistency, RecommendationDao |
| ├─ **database/entity/** | 4 | Category, DedupeKey, ExpenseEntityStress, MileageTrackingValidation |
| ├─ **database/model/** | 2 | FormattedAmount, FormattedTime |
| ├─ **email/** | 2 | EmailReceiptIngestionService test + transaction test |
| ├─ **email/provider/** | 4 | Amazon/Apple/Email/Uber receipt parsers |
| ├─ **location/** | 10 | Geocoding, backfill workers, Overpass, etc. |
| ├─ **repository/** | 34 | Expense, Budget, Category, Notification, Receipt, etc. |
| ├─ **security/** | 1 | `SecureKeyStorageTest.kt` |
| ├─ **service/** | 1 | `AndroidNotificationServiceTest.kt` |
| ├─ **speech/** | 1 | `AndroidSpeechInputGatewayTest.kt` |
| ├─ **database/** | 2 | GroupTransactionCoordinator, TransactionRollback |
| **domain/** | — | (see sub-packages below) |
| ├─ **ai/model/** | 6 | AI model/presentation tests |
| ├─ **ai/policy/** | 2 | AiPolicy, DefaultAiCapabilityRouter |
| ├─ **ai/usecase/** | 21 | Comprehensive use case tests |
| ├─ **ai/util/** | 1 | `AiArtifactSourceHashTest.kt` |
| ├─ **alerts/** | 1 | `AnomalyAlertOrchestratorTest.kt` |
| ├─ **analytics/** | 30 | Heavy coverage: engines, calculators, classifiers, etc. |
| ├─ **bank/** | 1 | `BankApiIntegrationTest.kt` |
| ├─ **budget/** | 13 | Budget calculators, monitors, forecasting, etc. |
| ├─ **business/** | 1 | `BusinessExpenseReportGeneratorTest.kt` |
| ├─ **carbon/** | 1 | `CarbonFootprintCalculatorTest.kt` |
| ├─ **cashflow/** | 1 | `CashFlowCalculatorTest.kt` |
| ├─ **categorization/** | 8 | Categorization engines + stress tests |
| ├─ **challenge/** | 1 | `SpendingChallengeManagerTest.kt` |
| ├─ **currency/** | 5 | Conversion tests + fixtures |
| ├─ **debug/** | 1 | `ServiceDiagnosticsTest.kt` |
| ├─ **engine/** | 1 | `DashboardFollowThroughEngineTest.kt` |
| ├─ **export/** | 3 | Accounting, CSV escaping, expense export mapper |
| ├─ **forecasting/** | 6 | Simulation, distribution, assembled input tests |
| ├─ **groups/** | 4 | Settlement, shared expense, budget offset |
| ├─ **groups/usecase/** | 1 | `GroupUseCasesTest.kt` |
| ├─ **health/** | 6 | Financial health calculators |
| ├─ **income/** | 1 | `RecurringIncomeTrackerTest.kt` |
| ├─ **intelligence/** | 4 | ConfidenceRouter, DuplicateDetection, TransactionClassifier |
| ├─ **intelligence/ml/** | 6 | ML classifiers, feature extractor, merchant normalizer |
| ├─ **investment/** | 1 | `InvestmentTrackerTest.kt` |
| ├─ **location/** | 7 | Location resolvers, heatmap, travel detection |
| ├─ **logic/** | 10 | Split, recurring, synthesis engines |
| ├─ **model/** | 4 | CategoryBreakdown, PeriodTotal, RecurringPattern |
| ├─ **naturallanguage/** | 1 | Voice input test |
| ├─ **parser/** | 14 | App parsers, bank parsers, SMS, Google Wallet, etc. |
| ├─ **price/** | 1 | `PriceProtectionTrackerTest.kt` |
| ├─ **receipt/** | 8 | Receipt parsing, OCR, bank statements, warranty |
| ├─ **receiptmatching/** | 1 | `ReceiptTransactionMatcherTest.kt` |
| ├─ **reminder/** | 1 | `BillReminderManagerTest.kt` |
| ├─ **savings/** | 4 | Automated savings rules, gamification, smart savings |
| ├─ **split/** | 1 | `SplitCalculationPrecisionTest.kt` |
| ├─ **tax/** capacity | 2 | Tax calculation, estimator |
| ├─ **usecase/budget/** | 2 | CalculateBudgetStatus, GetMonteCarloBudgetImpact |
| ├─ **usecase/dashboard/** | 1 | ComputeMoneyRadar |
| ├─ **usecase/expense/** | 2 | CategorizeExpense, DetectDuplicateExpense |
| ├─ **usecase/forecast/** | 1 | CalculateFinancialForecast |
| ├─ **usecase/savings/** | 2 | LifestyleSavingsPrompt, MonthlySavingsSweep |
| ├─ **usecase/warranty/** | 1 | AutoCreateWarrantyFromReceipt |
| ├─ **util/** | 14 | Amount, TimePeriod, Money, BKTree, MerchantKey, etc. |
| ├─ **widget/** | 1 | `WidgetStyleRepositoryTest.kt` |
| **e2e/** | 12 | Pipeline tests: analytics, budget, receipt, groups, etc. |
| **integration/** | 4 | Integration tests for pipelines |
| **metrics/** | 8 | Dashboard widget, effective amount, golden analytics |
| **receiver/** | 2 | BootReceiver, ServiceRestartReceiver stress tests |
| **service/** | 12 | Notification, recommendation, transaction filter, warranty, receipt matching |
| **ui/** | — | (see sub-packages below) |
| ├─ **components/emptystate/** | 1 | `ContextualActionRegistryTest.kt` |
| ├─ **screens/addexpense/** | 2 | ViewModel test + stress |
| ├─ **screens/aisettings/** | 2 | Screen text + ViewModel |
| ├─ **screens/analytics/** | 3 | ViewModel, state stress, advanced |
| ├─ **screens/assistant/** | 1 | `AssistantViewModelTest.kt` |
| ├─ **screens/budget/** | 2 | Forecasting VM + stress |
| ├─ **screens/carbon/** | 2 | Screen + ViewModel |
| ├─ **screens/cashflow/** | 1 | Calendar ViewModel |
| ├─ **screens/challenge/** | 1 | SpendingChallengesViewModel |
| ├─ **screens/currency/** | 2 | Management screen + ViewModel |
| ├─ **screens/debug/** | 2 | Screen text + VM stress |
| ├─ **screens/export/** | 1 | ExportOptionsViewModel |
| ├─ **screens/groups/** | 2 | Screen state + ViewModel |
| ├─ **screens/home/** | 3 | Widget, recommendation, VM stress |
| ├─ **screens/lifestyle/** | 2 | Screen + ViewModel |
| ├─ **screens/map/** | 1 | SpendingMap VM stress |
| ├─ **screens/price/** | 1 | PriceProtection VM |
| ├─ **screens/receiptmatching/** | 1 | ReceiptMatching VM |
| ├─ **screens/receiptscan/** | 1 | ReceiptScan VM stress |
| ├─ **screens/recurringmanual/** | 1 | ManualRecurringExpense VM |
| ├─ **screens/review/** | 3 | Transaction type parser, transfer direction parser, VM stress |
| ├─ **screens/savings/** | 1 | SavingsGoals VM |
| ├─ **screens/split/** | 2 | VisualSplitEditor + VM |
| ├─ **screens/subscription/** | 1 | SubscriptionManagement VM |
| ├─ **screens/transactions/** | 2 | Screen + VM stress |
| ├─ **screens/warranty/** | 1 | WarrantyTracker VM |
| ├─ **ui/** | 2 | MainActivityDeepLink, MainViewModelStress |
| ├─ **ui/util/** | 1 | ClipboardAmountParser |
| **util/** | 4 | CsvExpenseImporter, FlowTestUtils, HiltTestUtils, ViewModelTestUtils |
| **verification/** | 6 | GoldenMaster, CrossSource, CrossGroup, Lifestyle, Carbon, SharedExpense |

### Compilation Status

Based on static analysis of cross-references between test files and production source code:

- **Definite compilation failures:** ~14 test files
- **Likely compilation failures (untested dependencies):** 0 additional
- **Compiles cleanly (estimated):** ~388 files

---

## 2. Dead Tests

### 2.1 Cannot Compile (references to deleted/changed APIs)

#### CRITICAL: `ExpenseRepository` constructor now requires 8 parameters

The production `ExpenseRepository` constructor was updated to require `TransactionLifecycleCoordinator` as the 8th parameter. The following **11 test files** pass only 7 parameters and **will fail to compile**:

| # | File | Line |
|---|------|------|
| 1 | `data/repository/ExpenseRepositoryTest.kt` | 53-61 |
| 2 | `data/repository/ExpenseRepositoryStressTest.kt` | 53-61 |
| 3 | `data/repository/ExpenseRepositoryTruncationTest.kt` | 65-73 |
| 4 | `e2e/FlowPipelineTestHarness.kt` | 115-123 |
| 5 | `verification/GoldenMasterVerificationTest.kt` | 148-156 |
| 6 | `verification/CrossSourceVerificationTest.kt` | 54-62 |
| 7 | `verification/CrossGroupIntegrationTest.kt` | 108-116 |
| 8 | `domain/analytics/AnalyticsStressTest.kt` | 67-75 |
| 9 | `e2e/AnalyticsPipelineTest.kt` | 53-61 |
| 10 | `e2e/NotificationExpenseDashboardPipelineTest.kt` | 207-215 |
| 11 | `integration/EffectiveAmountPipelineIntegrationTest.kt` | 111-119 |

**Fix:** Add `transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)` to each constructor call.

#### CRITICAL: `RecurringExpenseRepository` constructor now requires `ManualRecurringExpenseDao`

The production constructor uses `ManualRecurringExpenseDao` (the non-deprecated DAO). The following **3 test files** pass `RecurringExpenseDao` (deprecated) and **will fail to compile**:

| # | File | Line |
|---|------|------|
| 1 | `data/repository/RecurringExpenseRepositoryTest.kt` | 18, 24 |
| 2 | `e2e/AnalyticsPipelineTest.kt` | 50, 63 |
| 3 | `e2e/NotificationExpenseDashboardPipelineTest.kt` | 217, 223 |

**Fix:** Change `mockk<RecurringExpenseDao>()` → `mockk<ManualRecurringExpenseDao>()` and update import.

#### CRITICAL: `TransactionLifecycleCoordinator` constructor requires 8 parameters

`GroupTransactionCoordinatorTest.kt` line 85-87 constructs `TransactionLifecycleCoordinator` with only 3 parameters (`expenseDao`, `transactionEventDao`, `timeProvider`) — but the real constructor requires 8 (`database`, `expenseDao`, `transactionEventDao`, `timeProvider`, `currencyConverter`, `sideEffectDispatcher`, `recurringLifecycleCoordinator`, `restoreMaintenanceMode`):

| # | File | Line |
|---|------|------|
| 1 | `data/database/GroupTransactionCoordinatorTest.kt` | 85-87 |

**This is the only test that actually instantiates a real `TransactionLifecycleCoordinator`** rather than mocking it.

### 2.2 `.bak` Files (Dead, Not Compiled)

| # | File | Paired active file |
|---|------|--------------------|
| 1 | `concurrency/DaoConcurrencyTest.kt.bak` | No active counterpart |
| 2 | `domain/analytics/InsightsEngineEdgeCaseTest.kt.bak` | `InsightsEngineEdgeCaseTest.kt` exists |
| 3 | `domain/intelligence/ConfidenceRouterEdgeCaseTest.kt.bak` | `ConfidenceRouterEdgeCaseTest.kt` exists |
| 4 | `domain/intelligence/TransactionClassifierTest.kt.bak` | `TransactionClassifierTest.kt` exists |

**Recommendation:** Delete all `.bak` files. They are stale backups.

### 2.3 @Ignore Tests (Not Running in CI)

**36 `@Ignore` annotations found across 24 files.** Most are stress tests marked "may hang in CI, run manually." Additionally:

| File | Ignore Reason | Severity |
|------|---------------|----------|
| `ui/screens/aisettings/AiSettingsScreenTextTest.kt` | "Requires Android instrumentation - move to androidTest" | MAJOR |
| `consistency/ConcurrencyStateRaceTest.kt` | "Tests stdlib StateFlow behavior, not production code" | MINOR |
| `domain/tax/TaxCalculationTest.kt` (1 method) | "VAT calculation logic differs from test expectation" | MAJOR |
| `domain/util/MoneyTest.kt` (3 methods) | "Truth assertThat incompatible with Kotlin value class boxing" | MAJOR |
| `data/database/TransactionRollbackTest.kt` (1 method) | "Concurrent transaction simulation requires multi-threading support" | MINOR |

### 2.4 Deprecated API References (Still Compile but Produce Warnings)

The following files reference `RecurringExpenseDao` which is marked `@Deprecated` but still exists:

| # | File | Usage |
|---|------|-------|
| 1 | `e2e/NotificationExpenseDashboardPipelineTest.kt` | `import` + `mockk<RecurringExpenseDao>` |
| 2 | `e2e/AnalyticsPipelineTest.kt` | `import` + `mockk<RecurringExpenseDao>` |
| 3 | `data/repository/RecurringExpenseRepositoryTest.kt` | `import` + `mockk<RecurringExpenseDao>` + passed to constructor |

**Note:** Even though this DAO is deprecated, it still exists as a valid type. The compilation failure in #3 is because the _repository_ constructor now takes `ManualRecurringExpenseDao`, not because `RecurringExpenseDao` is deprecated.

---

## 3. Overlapping Tests

### 3.1 Same Engine Tested in 3-5 Files

The test suite shows a pattern of testing the same production class in 3-5 distinct test files (unit + deep + stress + validation + edge case + golden):

| Production Class | Test Files |
|------------------|------------|
| `InsightsEngine` | `InsightsEngineTest`, `InsightsEngineDeepTest`, `InsightsEngineEdgeCaseTest`, `InsightsEngineStressTest`, `InsightsEngineValidationTest` (5) |
| `AdvancedAnalyticsEngine` | `AdvancedAnalyticsEngineTest`, `AdvancedAnalyticsEngineDeepTest`, `AdvancedAnalyticsEngineStressTest` (3) |
| `TotalsAggregationEngine` | `TotalsAggregationEngineTest`, `TotalsAggregationEngineDeepTest`, `TotalsAggregationEngineValidationTest` (3) |
| `SpendingPaceCalculator` | `SpendingPaceCalculatorValidationTest`, `SpendingPaceCalculatorDeepTest`, `SpendingPaceGoldenTest`, `SpendingPaceBoundaryTest` (4) |
| `BudgetCalculator` | `BudgetCalculatorTest`, `BudgetCalculatorBoundaryTest`, `BudgetCalculatorGoldenTest`, `BudgetCalculatorStressTest` (4) |
| `CurrencyConverter` | `CurrencyConversionTest`, `CurrencyConverterEdgeCaseTest`, `CurrencyConverterGoldenTest`, `CurrencyConverterStressTest` (4) |
| `TransactionClassifier` | `TransactionClassifierTest` (active) + `TransactionClassifierTest.kt.bak` (dead) + multiple ML tests |
| `ConfidenceRouter` | `ConfidenceRouterTest`, `ConfidenceRouterEdgeCaseTest` (active) + `.bak` (dead) |

### 3.2 Cross-Package Duplication

- **Analytics verification** appears in both `verification/GoldenMasterVerificationTest.kt` and `e2e/AnalyticsPipelineTest.kt` — both run golden datasets through `InsightsEngine`.
- **Effective amount** tested in both `integration/EffectiveAmountPipelineIntegrationTest.kt` and `metrics/EffectiveAmountConsistencyTest.kt`.
- **Time period alignment** tested in both `consistency/TimePeriodAnalyticsAlignmentTest.kt` and `metrics/TimePeriodAlignmentTest.kt`.

### 3.3 Stress Tests That Duplicate Unit Tests

Many `*StressTest.kt` files repeat the same assertions as their non-stress counterparts but with larger data volumes. Examples:
- `ExpenseRepositoryStressTest.kt` duplicates `ExpenseRepositoryTest.kt`
- `BudgetRepositoryStressTest.kt` duplicates `BudgetRepositoryTest` (implied)
- `ReviewQueueRepositoryStressTest.kt` duplicates `ReviewQueueRepositoryTest.kt`

### 3.4 `AnalyticsEngineTestBase` Shared Setup

72+ test files extend or reference `AnalyticsEngineTestBase`, which provides shared mock DAOs (`expenseDao`, `categoryDao`, etc.), time providers, and golden data builders. This is a well-factored pattern — **not** problematic overlap.

---

## 4. Missing Coverage

### 4.1 Coordinators with ZERO Dedicated Tests

| Coordinator | Production File | Test Status |
|-------------|----------------|-------------|
| `TransactionLifecycleCoordinator` | `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt` (606 lines) | **NO dedicated test.** Only tested indirectly via `GroupTransactionCoordinatorTest` (which uses wrong constructor params) and mocked in `ReviewQueueRepositoryTest`. |
| `ReceiptLifecycleCoordinator` | `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` (643 lines) | **NO dedicated test.** Only mocked in `ReviewViewModelStressTest`. |
| `RecurringLifecycleCoordinator` | `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt` (357 lines) | **ZERO references in test directory.** No tests whatsoever. |

### 4.2 PrivacyGate Implementations

| Implementation | Production File | Test Status |
|----------------|----------------|-------------|
| `PrivacyGate` (interface) | `domain/privacy/PrivacyGate.kt` | Only mocked, no contract tests |
| `NotificationPrivacyGate` | `domain/privacy/NotificationPrivacyGate.kt` | **NO tests** |
| `LocationPrivacyGate` | `domain/privacy/LocationPrivacyGate.kt` | **NO tests** |
| `CloudAiPrivacyGate` | `domain/privacy/CloudAiPrivacyGate.kt` | **NO tests** |
| `BackupPrivacyGate` | `domain/privacy/BackupPrivacyGate.kt` | **NO tests** |
| `CompositePrivacyGate` | `domain/privacy/CompositePrivacyGate.kt` | **NO tests** |

### 4.3 Other Missing Test Areas

| Component | Production File | Test Status |
|-----------|----------------|-------------|
| `BackupEncryptionService` | `data/privacy/BackupEncryptionService.kt` (114 lines) | **NO dedicated test.** Only mocked in `DatabaseBackupRepositoryImplTest`. Critical: this handles AES-256-GCM encryption for backup files. |
| `ValidateBankStatementTransactionsUseCase` | `domain/ai/usecase/ValidateBankStatementTransactionsUseCase.kt` | **HAS tests** ✅ (`domain/ai/usecase/ValidateBankStatementTransactionsUseCaseTest.kt`) |
| `ManualRecurringExpenseDao` | `data/database/dao/ManualRecurringExpenseDao.kt` | **NO tests.** All recurring DAO tests use deprecated `RecurringExpenseDao`. |
| `RecurringLifecycleEventDao` | DAO used by `RecurringLifecycleCoordinator` | **NO tests** |
| `RecurringOccurrenceDao` | DAO used by `RecurringLifecycleCoordinator` | **NO tests** |
| `RecurringReminderDeliveryDao` | DAO used by `RecurringLifecycleCoordinator` | **NO tests** |
| `TransactionSideEffectDispatcher` | dependency of `TransactionLifecycleCoordinator` | **NO tests** |
| `ReceiptSideEffectDispatcher` | dependency of `ReceiptLifecycleCoordinator` | **NO tests** |
| `ReceiptDuplicateDetector` | dependency of `ReceiptLifecycleCoordinator` | **NO tests** |
| `ReceiptInputValidator` | dependency of `ReceiptLifecycleCoordinator` | **NO tests** |
| `BankStatementLifecycleProcessor` | dependency of `ReceiptLifecycleCoordinator` | **NO tests** |

### 4.4 Migration Tests (v113 / schema)

- **No migration tests found.** The test directory contains no files in `data/database/migration/` or any test class that verifies Room schema migrations.

### 4.5 UI Screen Test Coverage Gaps

Screens with no dedicated ViewModel test:
- `recurringmanual/` — has `ManualRecurringExpenseViewModelTest.kt` ✅
- `reminder/` — no tests directory found under `ui/screens/reminder/`
- No `BillRemindersScreen` or `BillReminderViewModel` tests

---

## 5. Recommended Actions

### 5.1 Delete (7 files)

| File | Reason |
|------|--------|
| `concurrency/DaoConcurrencyTest.kt.bak` | Dead `.bak` file, no active counterpart |
| `domain/analytics/InsightsEngineEdgeCaseTest.kt.bak` | Stale backup — `InsightsEngineEdgeCaseTest.kt` exists |
| `domain/intelligence/ConfidenceRouterEdgeCaseTest.kt.bak` | Stale backup — active file exists |
| `domain/intelligence/TransactionClassifierTest.kt.bak` | Stale backup — active file exists |
| `consistency/ConcurrencyStateRaceTest.kt` | Tests stdlib behavior, not production code (already `@Ignore`d) |
| `ui/screens/aisettings/AiSettingsScreenTextTest.kt` | Requires Android instrumentation — move to `androidTest/` or delete |

### 5.2 Fix (14 files — CRITICAL compilation blockers)

All 11 `ExpenseRepository` constructor call sites must add:
```kotlin
transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
```

All 3 `RecurringExpenseDao` → `ManualRecurringExpenseDao` updates:
- `data/repository/RecurringExpenseRepositoryTest.kt`
- `e2e/AnalyticsPipelineTest.kt`
- `e2e/NotificationExpenseDashboardPipelineTest.kt`

`GroupTransactionCoordinatorTest.kt` — update `TransactionLifecycleCoordinator` construction to match 8-param constructor.

### 5.3 Create (estimated 15-25 new test files)

**Priority 1 — Coordinators (high risk, zero coverage):**
- `TransactionLifecycleCoordinatorTest.kt` — test `createExpense()` validation, normalization, dedup, event logging, side effects
- `ReceiptLifecycleCoordinatorTest.kt` — test `processReceiptInput()` for camera, gallery, email, bank statement paths
- `RecurringLifecycleCoordinatorTest.kt` — test `generateOccurrences()`, `getDueReminders()`, materialization

**Priority 2 — Privacy Gates (security risk):**
- `NotificationPrivacyGateTest.kt`
- `LocationPrivacyGateTest.kt`
- `CloudAiPrivacyGateTest.kt`
- `BackupPrivacyGateTest.kt`
- `CompositePrivacyGateTest.kt`

**Priority 3 — Encryption (data integrity risk):**
- `BackupEncryptionServiceTest.kt` — test encrypt/decrypt roundtrip, wrong password, corrupted data, edge cases

**Priority 4 — Supporting components:**
- `TransactionSideEffectDispatcherTest.kt`
- `ReceiptSideEffectDispatcherTest.kt`
- `ReceiptDuplicateDetectorTest.kt`
- `ReceiptInputValidatorTest.kt`
- `BankStatementLifecycleProcessorTest.kt`
- `ManualRecurringExpenseDao` integration tests

### 5.4 Consolidate (optional, reduces maintenance)

- Merge `InsightsEngineTest` + `InsightsEngineDeepTest` + `InsightsEngineEdgeCaseTest` + `InsightsEngineValidationTest` → single `InsightsEngineTest.kt` (same pattern applies to other over-split engines)
- Merge stress tests into their parent test files with `@Tag("stress")` instead of separate files + `@Ignore`

---

## Summary

| Metric | Value |
|--------|-------|
| Total test files (excl. `.bak`) | **402** |
| `.bak` dead files | **4** |
| Files that WILL NOT COMPILE | **14** |
| Missing coordinator tests | **3 major coordinators (0 tests)** |
| Missing privacy gate tests | **6 gate implementations (0 tests)** |
| Missing encryption tests | **1 (BackupEncryptionService)** |
| @Ignore'd tests (not running) | **36 methods in 24 files** |
| Overlapping test groups | **8+ groups** |

**Overall Assessment:** The test suite is impressively large and covers the domain layer extensively. However, there are **14 compilation-blocking defects** and **critical gaps in coordinator, privacy gate, and encryption testing** that represent real risk in terms of regression safety and security verification.
