# Deep Dive Audit - Implementation Plan

## Goal
Fix critical performance bottlenecks, logic bugs, and architectural inconsistencies identified during the Deep Dive Audit. The focus is on stability and correctness first, then maintainability.

## User Review Required
> [!IMPORTANT]
> **Phase 1 (Critical Fixes)** involves logic changes to `SynthesisEngine` and `FinancialWeatherRepository` that directly affect the "Safe-to-Spend" calculation.
> **Phase 2 (Refactoring)** involves extracting logic from `ReceiptParser` which interacts with the legacy `SmsParser`.

## Proposed Changes

### Phase 1: Critical Fixes (Performance & Logic)

#### [MODIFY] [FinancialWeatherRepository.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/repository/FinancialWeatherRepository.kt)
- **Problem**: `getAllRecurringPatterns` triggers heavy analysis on every DB update, causing UI lag.
- **Fix**:
    - Decouple `recurringExpenseEngine.getPatterns()` from the main flow.
    - Use `stateIn(scope, SharingStarted.Lazily)` to cache the result.
    - Only re-run analysis explicitly or when `expenses` table changes significantly (not every single insert/update if possible, or just debounce).

#### [MODIFY] [SynthesisEngine.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt)
- **Problem**: Double counting of bills paid "today".
- **Fix**:
    - In `calculateFreeToSpend`, filter `committedUpcomingBills` to exclude any bill that matches a transaction in `recentTransactions` (same day, similar amount, matching merchant).

#### [MODIFY] [InsightsEngine.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt)
- **Problem**: N+1 query in `findAnomalies`.
- **Fix**:
    - Batch fetch all necessary history data for the relevant merchants in one query.
    - Remove the loop that calls `getLargestExpenseForMerchant`.

### Phase 2: Normalization & Refactoring

#### [NEW] [MerchantRulesRepository.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/repository/MerchantRulesRepository.kt)
- **Purpose**: Centralize the "Blocklist" and "Cleaning" logic currently trapped in `ReceiptParser`.
- **Logic**: Move `invalidMerchants` and `headerMarkers` lists here.

#### [MODIFY] [ReceiptParser.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt)
- **Change**: Inject `MerchantRulesRepository`. Remove private lists. Use `MerchantRulesRepository.isValidMerchant()`.

#### [MODIFY] [MerchantNormalizer.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/MerchantNormalizer.kt)
- **Change**: Integrate with `MerchantRulesRepository` to improve its "clean name" logic.

#### [NEW] [CurrencyFormatter.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/common/CurrencyFormatter.kt)
- **Purpose**: Centralize `String.format("%.2f", amount)` logic.
- **Usage**: Replace hardcoded formatting in UI files.

## Verification Plan

### Automated Tests
- **Run existing tests**:
    - `SynthesisEngineTest`: Verify no regression in "Safe-to-Spend".
    - `InsightsEngineTest`: Verify anomaly detection still works (and is faster).
- **New Tests**:
    - Create `FinancialWeatherRepositoryTest` to verify flow behavior (mocking the engine).
    - Add test case to `SynthesisEngineTest` specifically for "Bill paid today" scenario.

### Manual Verification
1.  **Performance Check**: Open Dashboard. Verify "Safe-to-Spend" loads instantly without blocking UI.
2.  **Double Count Check**: Mark a recurring bill as "Paid" today. Ensure "Safe-to-Spend" doesn't drop twice (once for the transaction, once for the "upcoming" bill).
3.  **Receipt Parsing**: Scan a receipt. Ensure "VISA" or "TOTAL" is not detected as a merchant (verifying the refactored blocklist).

### Phase 3: Stability & Safety (New)

#### [MODIFY] [MainActivity.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt)
- **Problem**: Duplicate `MainViewModel` and detached `ReviewViewModel` in FAB.
- **Fix**: Remove Activity-level ViewModel. Pass `hiltViewModel()` instance from `MainScreen` down to FAB and sub-screens.

#### [MODIFY] [MainViewModel.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/MainViewModel.kt)
- **Problem**: `replay=1` on navigation events.
- **Fix**: Change to `Channel` or `SharedFlow(replay=0)`.

#### [MODIFY] [NotificationRepository.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt)
- **Problem**: Race condition in duplicate check.
- **Fix**: Use `@Transaction` with `insertIfNotDuplicate` pattern.

#### [MODIFY] [AppDatabase.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt)
- **Problem**: Missing migrations 1-5.
- **Fix**: Add `fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)` to builder.

### Phase 4: Architecture & Cleanup (New)

#### [CREATE] [RecurringExpenseRepository.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt)
- **Problem**: `RecurringExpenseDao` accessed directly by ViewModels.
- **Fix**: Create Repository to wrap DAO and encapsulate rule creation logic.

#### [MODIFY] [AddExpenseViewModel.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModel.kt)
- **Problem**: Injects DAO directly.
- **Fix**: Inject `RecurringExpenseRepository` instead.

#### [REFAC] Consolidate Time Models
- **Problem**: `MonthPeriod` vs `PeriodRange`.
- **Fix**: Refactor `AnalyticsModels.kt` to use `PeriodRange` or simplified shared model in `domain/model`.

#### [REFAC] Budget Logic Unification
- **Problem**: `BudgetMonitor` vs `BudgetRepository` duplication.
- **Fix**: Extract `BudgetCalculator` domain service to handle "spent so far" logic used by both.

