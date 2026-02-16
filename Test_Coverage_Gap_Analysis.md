# ExpenseTracker Test Coverage Gap Analysis

## Executive Summary

This document identifies critical test coverage gaps in the ExpenseTracker application, focusing on edge cases, component interactions, and scenarios not covered by the existing test suite. The analysis covers logic-heavy areas including the intelligence/categorization system, parsing engines, analytics, and infrastructure components.

---

## 1. Critical Edge Cases Not Yet Tested

### 1.1 ConfidenceRouter Edge Cases

The existing tests cover basic routing decisions, but miss several critical edge cases:

#### Untested Scenarios:

| Scenario | Expected Behavior | Risk Level |
|----------|-------------------|------------|
| **Confidence = exactly 0.85** (AUTO_ACCEPT_THRESHOLD) | Should AUTO_ACCEPT | HIGH |
| **Confidence = exactly 0.50** (REVIEW_THRESHOLD) | Should NEEDS_REVIEW | HIGH |
| **Confidence > 1.0** (invalid input) | Should clamp to 1.0 | MEDIUM |
| **Confidence < 0.0** (invalid input) | Should clamp to 0.0 | MEDIUM |
| **Confidence = NaN** | Should handle gracefully | HIGH |
| **Confidence = Float.POSITIVE_INFINITY** | Should handle gracefully | HIGH |
| **Null merchant name** | Should apply unknown merchant penalty | MEDIUM |
| **Empty merchant name** | Should apply unknown merchant penalty | MEDIUM |
| **Merchant name with only whitespace** | Should apply penalty | MEDIUM |
| **Concurrent routing calls** | Thread safety verification | HIGH |
| **SourceStats with zero totalNotifications** | Division by zero protection | HIGH |
| **UserCorrection overflow** (Integer.MAX_VALUE corrections) | Should not crash | MEDIUM |

#### Recommended Tests:

```kotlin
@Test fun `exact threshold boundary - auto accept at 0.85`()
@Test fun `exact threshold boundary - review at 0.50`()
@Test fun `invalid confidence NaN is handled gracefully`()
@Test fun `invalid confidence infinity is clamped`()
@Test fun `null merchant name applies penalty`()
@Test fun `concurrent routing calls are thread safe`()
@Test fun `division by zero in source stats is prevented`()
@Test fun `overflow in correction counts is handled`()
@Test fun `spam source with zero notifications does not crash`()
@Test fun `confidence adjustment with missing DAO data`()
```

### 1.2 InsightsEngine Edge Cases

The current tests cover happy paths but miss critical edge cases:

#### Date/Time Edge Cases:

| Scenario | Current Coverage | Gap |
|----------|------------------|-----|
| **Leap year (Feb 29)** | Not tested | February calculations |
| **Daylight Saving Time transitions** | Not tested | Hour offset issues |
| **Year boundary (Dec 31 → Jan 1)** | Not tested | Month period calculations |
| **Month with 31 days vs 28/30** | Partially tested | Pace calculation accuracy |
| **Expenses at exactly midnight (00:00:00.000)** | Not tested | Boundary inclusion |
| **Expenses at 23:59:59.999** | Not tested | Boundary inclusion |
| **Negative timestamps** (pre-1970) | Not tested | Data validation |
| **Future timestamps** | Not tested | Data validation |

#### Data Edge Cases:

| Scenario | Expected Behavior | Risk |
|----------|-------------------|------|
| **Empty expenses list** | Return valid snapshot with zeros | HIGH |
| **Single expense ever** | Handle gracefully | MEDIUM |
| **All expenses same merchant** | Recurring detection accuracy | MEDIUM |
| **All expenses same amount** | Anomaly detection | MEDIUM |
| **Expenses with null categories** | Skip or handle gracefully | HIGH |
| **Expenses with negative amounts** | Filter or handle | HIGH |
| **Expenses with zero amounts** | Should they count? | MEDIUM |
| **Extremely large amounts** (Double.MAX_VALUE) | Overflow protection | HIGH |
| **Very small amounts** (0.001) | Rounding behavior | MEDIUM |
| **All deposits (no purchases)** | Zero purchase analytics | HIGH |
| **Duplicate expenses within window** | Deduplication logic | HIGH |

#### Recommended Tests:

```kotlin
@Test fun `leap year february calculations are correct`()
@Test fun `year boundary period calculations work`()
@Test fun `empty expenses returns valid zero snapshot`()
@Test fun `single expense does not crash engine`()
@Test fun `all same merchant detects recurring correctly`()
@Test fun `negative amounts are filtered from analytics`()
@Test fun `double overflow is prevented`()
@Test fun `all deposits produces zero purchase metrics`()
@Test fun `duplicate expenses are deduplicated`()
@Test fun `timezone changes do not affect daily totals`()
@Test fun `very long merchant names are handled`()
@Test fun `unicode merchant names work correctly`()
```

### 1.3 CategorizationEngine Edge Cases

#### Missing Test Scenarios:

| Scenario | Description |
|----------|-------------|
| **Cache expiration under memory pressure** | Does cache invalidate properly? |
| **Concurrent cache access** | Thread safety of cache |
| **Regex injection via merchant name** | Security vulnerability |
| **Very long merchant names (>1000 chars)** | Performance/memory |
| **Merchant names with regex special chars** | Pattern matching accuracy |
| **Greek characters in different normalization forms** | Unicode normalization |
| **Merchant names that are only numbers** | Edge case matching |
| **Merchant names that match multiple categories** | Priority handling |
| **Null or empty category list** | Degraded mode |

#### Recommended Tests:

```kotlin
@Test fun `cache invalidation under memory pressure`()
@Test fun `concurrent cache access is thread safe`()
@Test fun `regex injection does not crash engine`()
@Test fun `very long merchant names are truncated or handled`()
@Test fun `regex special characters are escaped`()
@Test fun `greek unicode normalization is consistent`()
@Test fun `numeric only merchant names are handled`()
@Test fun `multi category match returns highest priority`()
@Test fun `empty category list returns null`()
@Test fun `substring match does not match partial words`()
@Test fun `case insensitive matching works for all inputs`()
```

### 1.4 HybridExpenseClassifier Edge Cases

| Scenario | Gap |
|----------|-----|
| **ML classifier returns null** | Fallback behavior |
| **ML classifier throws exception** | Exception handling |
| **ML classifier returns empty list** | Empty result handling |
| **ML classifier returns duplicate categories** | Deduplication |
| **ML classifier confidence all zeros** | Decision logic |
| **CategoryDao returns empty list** | No categories available |
| **Concurrent classification requests** | Thread safety |
| **Classification during model training** | Race condition |

#### Recommended Tests:

```kotlin
@Test fun `ml classifier returning null uses rule fallback`()
@Test fun `ml classifier throwing exception is caught`()
@Test fun `empty ml results use fallback`()
@Test fun `duplicate category suggestions are deduplicated`()
@Test fun `all zero confidence does not crash`()
@Test fun `no categories available returns fallback`()
@Test fun `concurrent classifications are thread safe`()
@Test fun `classification during model update is consistent`()
```

### 1.5 MerchantNormalizer Edge Cases

| Scenario | Gap |
|----------|-----|
| **Merchant name with emojis** | Unicode handling |
| **Merchant name with zero-width characters** | Invisible char handling |
| **Merchant name with RTL text** | Bi-directional text |
| **SQL injection attempt in merchant name** | Security |
| **DAO returning null for canonical lookup** | Null safety |
| **Alias pointing to non-existent canonical** | FK integrity |
| **Concurrent normalization of same merchant** | Race condition |

---

## 2. Component Interaction Test Gaps

### 2.1 Notification Processing Pipeline

The flow from `NotificationCaptureService` → `NotificationRepository` → `Parser` → `ConfidenceRouter` lacks integration tests:

```
┌─────────────────────────┐
│ NotificationCapture     │
│ Service                 │
└───────────┬─────────────┘
            │ RawNotification
            ▼
┌─────────────────────────┐
│ NotificationRepository  │ ← Missing integration tests
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│ AppParserRegistry       │ ← Missing routing integration tests
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│ ConfidenceRouter        │ ← Missing end-to-end tests
└─────────────────────────┘
```

#### Missing Integration Tests:

| Test | Description |
|------|-------------|
| `notification_to_expense_happy_path` | Full flow from notification to expense |
| `notification_blocked_package_filtering` | Blocked packages are filtered early |
| `notification_duplicate_detection` | Same notification not processed twice |
| `notification_parser_fallback_chain` | If primary parser fails, try next |
| `notification_confidence_below_threshold` | Low confidence goes to pending review |
| `notification_high_confidence_auto_expense` | High confidence creates expense directly |
| `notification_source_stats_tracking` | Source stats updated correctly |
| `notification_user_correction_flow` | Correction improves future accuracy |

### 2.2 Budget Monitoring System

The interaction between `BudgetMonitor`, `BudgetDao`, `ExpenseDao`, and notification system:

```
┌─────────────────────────┐
│ ExpenseDao              │
│ (new expense inserted)  │
└───────────┬─────────────┘
            │ Trigger
            ▼
┌─────────────────────────┐
│ BudgetMonitor           │ ← Missing trigger tests
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│ BudgetDao               │ ← Missing budget recalculation tests
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│ Notification System     │ ← Missing alert timing tests
└─────────────────────────┘
```

#### Missing Tests:

| Test | Description |
|------|-------------|
| `budget_warning_threshold_exact` | Alert at exactly 75% spent |
| `budget_critical_threshold_exact` | Alert at exactly 90% spent |
| `budget_exceeded_alert_once` | Don't spam alerts for same budget |
| `budget_rollover_calculation` | Monthly rollover math |
| `budget_with_no_expenses` | 0% spent reporting |
| `budget_category_vs_overall` | Both types work together |
| `budget_notification_cooldown` | Don't notify repeatedly |
| `budget_timezone_handling` | Period boundaries correct |

### 2.3 Financial Weather Aggregation

`FinancialWeatherRepository` aggregates from multiple sources:

```
┌────────────────────┐  ┌────────────────────┐  ┌────────────────────┐
│ ExpenseDao         │  │ RecurringExpenseDao│  │ PlannedExpenseDao  │
└─────────┬──────────┘  └─────────┬──────────┘  └─────────┬──────────┘
          │                       │                       │
          └───────────────────────┼───────────────────────┘
                                  │
                                  ▼
                    ┌─────────────────────────┐
                    │ FinancialWeather        │
                    │ Repository              │
                    └───────────┬─────────────┘
                                │
                                ▼
                    ┌─────────────────────────┐
                    │ SynthesisEngine         │
                    └─────────────────────────┘
```

#### Missing Integration Tests:

| Test | Description |
|------|-------------|
| `weather_all_empty_sources` | All DAOs return empty |
| `weather_partial_data_sources` | Some DAOs return empty |
| `weather_recurring_in_next_week` | Recurring counted in forecast |
| `weather_planned_vs_recurring_priority` | Priority conflict resolution |
| `weather_currency_mismatch` | Multiple currencies handling |
| `weather_historical_data_incomplete` | Missing months in history |
| `weather_forecast_accuracy_degrades` | Longer forecasts less accurate |
| `weather_update_frequency` | How often to recalculate |

### 2.4 Home Screen Data Aggregation

`HomeViewModel` combines data from:

- `DashboardRepository`
- `FinancialWeatherRepository`
- `NotificationRepository` (pending count)
- `ExpenseDao` (recent transactions)

#### Missing Tests:

| Test | Description |
|------|-------------|
| `home_loading_state` | Shows loading while fetching |
| `home_partial_failure` | Some repos fail, show partial data |
| `home_total_failure` | All repos fail, show error |
| `home_refresh_pull_to_refresh` | Refresh updates all data |
| `home_navigation_state_preservation` | Tab state on rotation |
| `home_concurrent_updates` | Multiple data sources update simultaneously |

---

## 3. Parser Edge Cases Not Yet Tested

### 3.1 GenericTransactionParser

| Scenario | Description |
|----------|-------------|
| **Empty notification text** | Returns null or default |
| **Only whitespace** | Returns null |
| **Amount without currency** | Default currency assignment |
| **Multiple amounts in text** | Which one is the transaction? |
| **Amount in title vs body** | Priority handling |
| **Negative amount patterns** | Refunds handling |
| **Amount with currency symbol in different position** | €10 vs 10€ vs 10 EUR |
| **Date in various formats** | dd/MM/yyyy, MM/dd/yyyy, etc. |
| **Date relative to now** | "today", "yesterday", "2 days ago" |
| **Partial merchant name** | "ST*STARBUCKS" → "Starbucks" |
| **Merchant with location** | "Starbucks Athens" vs "Starbucks" |
| **Merchant with store number** | "McDonald's #1234" |
| **Merchant with transaction ID** | "UBER *TRIP 123456" |

### 3.2 GreekBankParser

| Scenario | Description |
|----------|-------------|
| **Mixed Greek and English** | Bilingual notifications |
| **Greek characters as Latin** | Common OCR errors |
| **Latin characters as Greek** | A→Α, E→Ε confusion |
| **Greek amount formats** | 1.234,56 vs 1,234.56 |
| **Greek bank specific formats** | Alpha, Eurobank, NBG, Piraeus |
| **OTP codes in notification** | Don't parse OTP as amount |
| **Account numbers** | Don't parse as amounts |
| **Balance notifications** | Distinguish from transaction |

### 3.3 RevolutParser

| Scenario | Description |
|----------|-------------|
| **Multi-currency transactions** | EUR → USD conversion |
| **Crypto transactions** | BTC, ETH handling |
| **Split payments** | Multiple merchants |
| **Pending vs completed** | Status tracking |
| **Refund notifications** | Negative amount handling |
| **Top-up notifications** | Distinguish from spending |

### 3.4 GoogleWalletParser

| Scenario | Description |
|----------|-------------|
| **Transit passes** | Metro, bus tickets |
| **Loyalty cards** | Points transactions |
| **Gift cards** | Spending from gift card |
| **Event tickets** | Concert, movie tickets |
| **Boarding passes** | Flight transactions |

---

## 4. Concurrency and Thread Safety Tests

### 4.1 DAO Concurrency

```kotlin
@Test
fun `concurrent expense inserts do not cause duplicate detection failure`() = runBlocking {
    val concurrentInserts = List(100) { 
        async(Dispatchers.IO) { 
            expenseDao.insert(makeExpense(amount = 10.0, merchant = "Test")) 
        }
    }
    val results = concurrentInserts.awaitAll()
    // Verify no duplicates created
    // Verify isDuplicate still works correctly
}
```

### 4.2 Flow Update Ordering

```kotlin
@Test
fun `flow emissions arrive in correct order after rapid updates`() = runBlocking {
    val emissions = mutableListOf<List<Expense>>()
    val job = expenseDao.getAllFlow().onEach { emissions.add(it) }.launchIn(this)
    
    repeat(10) { i ->
        expenseDao.insert(makeExpense(merchant = "Test$i"))
    }
    
    delay(100) // Allow emissions to settle
    job.cancel()
    
    // Verify emissions are in correct order
    // Each emission should have one more item than previous
}
```

### 4.3 ViewModel State Consistency

```kotlin
@Test
fun `viewmodel state remains consistent during rapid navigation`() = runBlocking {
    val viewModel = HomeViewModel(/* mocks */)
    
    // Rapidly switch between data loading scenarios
    repeat(50) {
        viewModel.refresh()
        delay(1)
    }
    
    // Verify state is not corrupted
    // Verify no duplicate emissions
    // Verify loading state is correct
}
```

---

## 5. Database Migration Tests

### 5.1 Migration Path Tests

Currently only in-memory database tests exist. Missing:

| Migration | Test Needed |
|-----------|-------------|
| `MIGRATION_6_7` | Verify new columns added with defaults |
| `MIGRATION_7_8` | Verify budgets table created correctly |
| `MIGRATION_8_9` | Verify scanned_receipts table created |
| `MIGRATION_9_10` | Verify pending_reviews FK changes |
| `MIGRATION_10_11` | Verify suggestedDate column added |
| `MIGRATION_11_12` | Verify manual_recurring_expenses table |
| `MIGRATION_12_13` | Verify planned_expenses and savings_goals tables |
| `MIGRATION_13_14` | Verify source_stats table |
| `MIGRATION_14_15` | Verify index changes |
| `MIGRATION_15_16` | Verify FK additions to user_corrections |
| `MIGRATION_16_17` | Verify merchant canonical/alias tables |
| `MIGRATION_17_18` | Verify date index |
| `MIGRATION_18_19` | Verify duplicates column |

### 5.2 Migration Test Template

```kotlin
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @Test
    fun migration_18_19_adds_duplicates_column() {
        // Create database at version 18
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .createFromFile(File("test_db_v18.db"))
            .build()
        
        // Run migration
        db.close()
        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, "test_db")
            .addMigrations(MIGRATION_18_19)
            .build()
        
        // Verify duplicates column exists
        val cursor = migratedDb.query("SELECT duplicates FROM source_stats LIMIT 1")
        assertNotNull(cursor)
        cursor.close()
    }
}
```

---

## 6. Error Handling Tests

### 6.1 Database Errors

| Scenario | Expected Behavior |
|----------|-------------------|
| **Database full** | Graceful degradation |
| **Corrupt database** | Recovery or reset |
| **Migration failure** | Fallback to fresh DB |
| **Constraint violation** | Proper error message |
| **Disk I/O error** | Retry mechanism |

### 6.2 Parsing Errors

| Scenario | Expected Behavior |
|----------|-------------------|
| **Malformed notification** | Don't crash, log error |
| **Regex catastrophic backtracking** | Timeout protection |
| **Stack overflow from recursive parsing** | Depth limit |
| **Out of memory during parsing** | Memory limit |

### 6.3 Repository Errors

```kotlin
@Test
fun `repository returns cached data when database unavailable`() = runBlocking {
    // Simulate database unavailable
    whenever(expenseDao.getAll()).thenThrow(SQLException("Database unavailable"))
    
    val result = repository.getExpenses()
    
    // Should return cached data or empty list, not crash
    assertTrue(result.isSuccess || result.isEmpty())
}
```

---

## 7. Performance Edge Cases

### 7.1 Large Dataset Tests

| Test | Description |
|------|-------------|
| `analytics_with_10000_expenses` | Performance with large dataset |
| `search_merchants_with_1000_matches` | Search result limiting |
| `monthly_totals_with_3_years_data` | Long date range queries |
| `recurring_detection_with_1000_merchants` | Algorithm scalability |

### 7.2 Memory Tests

| Test | Description |
|------|-------------|
| `notification_cache_memory_limit` | Bounded cache behavior |
| `flow_subscription_cleanup` | No memory leaks from flows |
| `viewmodel_memory_on_config_change` | ViewModel cleanup |
| `bitmap_memory_in_receipt_scanning` | Image handling |

---

## 8. State Management Tests

### 8.1 ViewModel State

| Scenario | Expected Behavior |
|----------|-------------------|
| **Rotation during loading** | Loading continues, state preserved |
| **Process death** | State restoration from SavedStateHandle |
| **Back navigation** | Previous state restoration |
| **Deep link navigation** | Correct state initialization |

### 8.2 PendingReview State Machine

```
PENDING ──approve──→ APPROVED
    │                    
    └──reject──→ REJECTED
    
    ┌── (cannot change from APPROVED/REJECTED)
```

| Test | Description |
|------|-------------|
| `pending_to_approved` | Happy path |
| `pending_to_rejected` | Happy path |
| `approved_cannot_change` | Immutable after resolution |
| `rejected_cannot_change` | Immutable after resolution |
| `batch_approve_all` | All pending become approved |

---

## 9. Test Priority Matrix

### High Priority (Should Add Immediately)

1. **ConfidenceRouter threshold boundary tests** - Critical for routing decisions
2. **InsightsEngine empty/edge data tests** - Prevents crashes
3. **Notification pipeline integration tests** - Core functionality
4. **Database migration tests** - Data integrity
5. **Concurrency tests for DAOs** - Thread safety

### Medium Priority (Add Soon)

1. **Parser edge cases** - Improve parsing accuracy
2. **Budget monitoring integration** - Feature reliability
3. **Error handling tests** - Graceful degradation
4. **State machine tests** - Correct state transitions

### Lower Priority (Nice to Have)

1. **Performance tests** - Optimization
2. **Memory tests** - Resource management
3. **UI state tests** - User experience

---

## 10. Recommended New Test Files

```
app/src/test/java/com/yourname/expensetracker/
├── domain/
│   ├── intelligence/
│   │   ├── ConfidenceRouterEdgeCaseTest.kt
│   │   └── ConfidenceRouterConcurrencyTest.kt
│   ├── analytics/
│   │   ├── InsightsEngineEdgeCaseTest.kt
│   │   └── InsightsEngineDateTest.kt
│   ├── categorization/
│   │   ├── CategorizationEngineSecurityTest.kt
│   │   └── CategorizationEngineConcurrencyTest.kt
│   └── parser/
│       ├── GenericTransactionParserEdgeCaseTest.kt
│       ├── GreekBankParserEdgeCaseTest.kt
│       └── MultiParserIntegrationTest.kt
├── integration/
│   ├── NotificationProcessingPipelineTest.kt
│   ├── BudgetMonitoringIntegrationTest.kt
│   └── FinancialWeatherIntegrationTest.kt
├── concurrency/
│   ├── DaoConcurrencyTest.kt
│   ├── FlowOrderingTest.kt
│   └── ViewModelConcurrencyTest.kt
└── migration/
    ├── MigrationTest.kt
    └── DatabaseRecoveryTest.kt

app/src/androidTest/java/com/yourname/expensetracker/
├── database/
│   ├── MigrationTest.kt
│   └── DatabasePerformanceTest.kt
└── integration/
    └── EndToEndFlowTest.kt
```

---

## 11. Summary Statistics

| Category | Existing Tests | Missing Tests | Coverage Gap |
|----------|---------------|---------------|--------------|
| Unit Tests | ~80 | ~120 | 60% gap |
| Integration Tests | ~5 | ~35 | 87% gap |
| Edge Case Tests | ~20 | ~80 | 80% gap |
| Concurrency Tests | 0 | ~15 | 100% gap |
| Migration Tests | 0 | ~13 | 100% gap |
| Performance Tests | 0 | ~8 | 100% gap |

**Total Estimated Additional Tests Needed: ~271**

---

## 12. Next Steps

1. **Week 1**: Implement high-priority tests (threshold boundaries, empty data, notification pipeline)
2. **Week 2**: Add concurrency tests and migration tests
3. **Week 3**: Complete parser edge case tests
4. **Week 4**: Integration tests and performance tests
5. **Ongoing**: Add regression tests for any bugs discovered in production
