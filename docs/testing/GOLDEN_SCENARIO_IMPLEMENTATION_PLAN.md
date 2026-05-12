# Golden Scenario Tests — Implementation Plan

> **Goal:** 10-15 end-to-end tests that verify complete pipeline flows using REAL coordinators (not mocks).  
> **Approach:** Room in-memory DB + real coordinators + real DAOs + minimal mocks (only for external services like cloud AI, geocoding).

---

## Existing Tests Assessment

### KEEP (already good golden/scenario tests)
| File | Why Keep |
|------|----------|
| `SynthesisEngineGoldenTest.kt` | Tests real engine with deterministic input |
| `BudgetCalculatorGoldenTest.kt` | Tests real calculator with boundary cases |
| `SpendingPaceGoldenTest.kt` | Tests real pace calculator |
| `CurrencyConverterGoldenTest.kt` | Tests real converter with known rates |
| `MonteCarloSpendingSimulatorGoldenTest.kt` | Deterministic seed, verifiable output |
| `SplitCalculatorGoldenTest.kt` | Real split math |
| `HealthScoreGoldenTest.kt` | Real health calculation |
| `NotificationProcessingPipelineReliabilityTest.kt` | Tests real pipeline behavior |
| `CsvExportImportRoundtripTest.kt` | Real roundtrip verification |
| `RecurringNoDoubleCountScenarioTest.kt` | Important business rule (needs rewrite to use coordinator) |
| `MixedCurrencyCoreFinancialScenarioTest.kt` | Important multi-currency verification |

### REWRITE (good intent, bad implementation)
| File | Problem | Rewrite To |
|------|---------|-----------|
| `NotificationExpenseDashboardPipelineTest.kt` | 500 lines of wiring, 5 assertions | Real pipeline with Room DB |
| `ReceiptProcessingPipelineTest.kt` | Mock-heavy, no real receipt processing | Real ReceiptLifecycleCoordinator |
| `BudgetAlertPipelineTest.kt` | Mock-verified alert dispatch | Real budget monitor with DB |
| `GroupSettlementPipelineTest.kt` | Mixed mock/real | Real group coordinator |
| `AnalyticsPipelineTest.kt` | Uses deprecated DAO methods | Real analytics with NormalizedInput |
| `EmailReceiptPipelineScenarioTest.kt` | DAO-only, no real pipeline | Real email ingestion service |
| `BankSyncScenarioTest.kt` | DAO-only, no real sync | Real bank statement processor |
| `RecurringNoDoubleCountScenarioTest.kt` | Uses ScenarioSeeder, not coordinator | Real recurring lifecycle |

### DELETE (replaced by new golden tests)
| File | Why |
|------|-----|
| `GoldenScenarioSmokeTest.kt` | Only tests ScenarioSeeder infrastructure |
| `TransactionLifecycleDbContractTest.kt` | Tests seeder, not lifecycle |
| `FlowPipelineTestHarness.kt` | Dead infrastructure with deprecated calls |

---

## New Golden Scenario Tests to Write

### Test Infrastructure (shared)

```kotlin
// File: app/src/test/java/com/yourname/expensetracker/golden/GoldenTestBase.kt
// Provides: Room in-memory DB, real DAOs, real coordinators, TimeProvider, CurrencyConverter with fixed rates
```

### Test 1: Notification → Expense → Dashboard
```
Input: Greek bank notification "Αγορά €45.30 LIDL"
Flow: NotificationProcessingPipeline → TransactionLifecycleCoordinator → ExpenseDao
Verify:
  - RawNotification persisted with fingerprint
  - Expense created with correct amount/merchant/currency
  - TransactionEvent.CREATED written
  - Dashboard total increases by €45.30
```

### Test 2: Receipt Scan → Expense → Link
```
Input: Mock OCR result with total $23.50, merchant "Walmart"
Flow: ReceiptLifecycleCoordinator → ReceiptLinkService → TransactionLifecycleCoordinator
Verify:
  - ScannedReceipt with createdAt != 0
  - ReceiptEvent.RECEIPT_SAVED written
  - Expense created and linked atomically
  - ReceiptExpenseLink row exists
```

### Test 3: Recurring Bill Payment Match
```
Input: Monthly Netflix rule + matching expense
Flow: TransactionLifecycleCoordinator.createExpense → TransactionSideEffectDispatcher → RecurringLifecycleCoordinator.linkExpenseToOccurrence
Verify:
  - Occurrence status = PAID (atomic claim)
  - PlannedExpense status = FULFILLED
  - Open reminders suppressed
  - No double-count in dashboard
```

### Test 4: Multi-Currency Dashboard Consistency
```
Input: €100 + $100 expenses, EUR home, rate 1.1
Flow: AnalyticsRepository.getSpendingSummary + getCategoryBreakdown
Verify:
  - Summary total ≈ €190.91
  - Category breakdown sums to same total
  - Daily history sums to same total
  - isPartial = false
```

### Test 5: Privacy DO_NOT_STORE
```
Input: Notification with RawStorageMode.DO_NOT_STORE
Flow: NotificationCaptureService processing path
Verify:
  - Expense created correctly (parser got real text)
  - RawNotification title/text/bigText all null
  - Fingerprint still computed from real text
```

### Test 6: Backup → Restore Integrity
```
Input: DB with expenses, receipts, recurring rules
Flow: createCostBackup → restoreCostBackup
Verify:
  - Dashboard total unchanged
  - Receipt links preserved
  - Recurring state preserved
```

### Test 7: Rule Deactivation Cleanup
```
Input: Active rule with 3 PLANNED occurrences + reminders + planned expenses
Flow: RecurringRuleLifecycleCoordinator.deactivateRule
Verify:
  - Rule.isActive = false
  - All occurrences cancelled
  - All reminders suppressed
  - All planned expenses cancelled
  - generateOccurrences returns empty for this rule
```

### Test 8: Email Receipt Duplicate Links Existing
```
Input: Existing expense + email receipt for same transaction
Flow: EmailReceiptIngestionService.processEmailReceipt
Verify:
  - No new expense created
  - Receipt linked to existing expense
  - Result is LinkedExisting
```

### Test 9: Restore Blocks All Writes
```
Input: RestoreMaintenanceMode entered
Flow: Attempt all write operations
Verify:
  - Expense create blocked
  - Receipt save blocked
  - Recurring generate blocked
  - Budget add blocked
  - Group create blocked
  - Investment add blocked
```

### Test 10: Concurrent Occurrence Claim
```
Input: PLANNED occurrence + two matching expenses
Flow: Two concurrent linkExpenseToOccurrence calls
Verify:
  - Only one succeeds (atomic claim)
  - Occurrence has exactly one linkedExpenseId
```

---

## Implementation Approach

1. Create `GoldenTestBase` with Room in-memory DB + real coordinator wiring
2. Write tests 1-10 using real coordinators (not mocks)
3. Each test should be <100 lines (setup + action + assertions)
4. Use `@get:Rule val instantTaskExecutorRule` for Room
5. Use `runTest` for coroutines
6. Fixed `TimeProvider` returning deterministic timestamps
7. `CurrencyConverter` with hardcoded rates (no network)

---

## Execution Order

1. Write `GoldenTestBase` infrastructure
2. Write Test 4 (multi-currency) — validates our biggest refactoring area
3. Write Test 1 (notification pipeline) — most critical user flow
4. Write Test 3 (recurring match) — complex cross-pipeline
5. Write Test 7 (rule deactivation) — validates new coordinator
6. Write remaining tests 2, 5, 6, 8, 9, 10
7. Rewrite existing tests that have good intent but bad implementation
