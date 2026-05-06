# Ignored Test Classification

**Date:** May 6, 2026
**Total files with @Ignore:** 27

## Classification Rules

| Label | Criteria |
|-------|----------|
| **DELETE** | Permanently ignored, duplicates better tests, source-text assertions, purely implementation-coupled, no unique business contract |
| **REWRITE** | Protects financial correctness (money, tax, split precision, CSV escaping), has real business value but test is outdated/broken |
| **NIGHTLY** | Deterministic stress/load/concurrency tests that are too slow for PRs but have value |

## Summary

| # | File | Classification | Reasoning |
|---|------|---------------|-----------|
| 1 | `consistency/ConcurrencyStateRaceTest.kt` | **DELETE** | Tests stdlib `StateFlow` behavior, not production code; permanently ignored with explicit `@Ignore("Tests stdlib StateFlow behavior, not production code")` |
| 2 | `data/database/entity/ExpenseEntityStressTest.kt` | **NIGHTLY** | Entity stress test with 56 tests; slow but validates domain model constraints |
| 3 | `data/database/TransactionRollbackTest.kt` | **REWRITE** | Critical-2 flag: rollback atomicity is a documented gap (CRITICAL-2 Extension); test logic is simulated (flags/print), needs real Room `@Transaction` verification |
| 4 | `data/location/CompositeGeocodingServiceStressTest.kt` | **NIGHTLY** | Geocoding stress test (42 tests); slow due to I/O-like coroutine scenarios |
| 5 | `data/repository/BudgetRepositoryStressTest.kt` | **NIGHTLY** | Repository stress test (10 tests); exercises concurrent budget operations |
| 6 | `data/repository/CategoryRepositoryStressTest.kt` | **NIGHTLY** | Repository stress test (4 tests); exercises concurrent category operations |
| 7 | `data/repository/ExpenseRepositoryStressTest.kt` | **NIGHTLY** | Repository stress test (12 tests); exercises concurrent expense operations, may hang in CI |
| 8 | `data/repository/NotificationProcessingPipelineStressTest.kt` | **NIGHTLY** | Pipeline stress test (42 tests); high-volume notification processing scenarios |
| 9 | `data/repository/NotificationRepositoryStressTest.kt` | **NIGHTLY** | Repository stress test (21 tests); concurrent notification CRUD |
| 10 | `data/repository/ReceiptRepositoryStressTest.kt` | **NIGHTLY** | Repository stress test (12 tests); concurrent receipt operations |
| 11 | `data/repository/ReviewQueueRepositoryStressTest.kt` | **NIGHTLY** | Repository stress test (8 tests); concurrent review queue operations |
| 12 | `domain/tax/TaxCalculationTest.kt` | **REWRITE** | HIGH-6 flag: tax calculation accuracy (VAT rates, progressive brackets); uses real `TaxConfiguration` classes but the test structure is fragile/simulated |
| 13 | `domain/util/MoneyTest.kt` | **REWRITE** | CRITICAL-2 / HIGH-2 flag: `Money` class precision, rounding, split-sum-to-total; has real business value but test is outdated (uses `Money.fromDouble`, `Money.fromString` which may have changed) |
| 14 | `domain/util/NotificationIdGeneratorTest.kt` | **REWRITE** | HIGH-4 flag: notification ID collision prevention, range enforcement, large-ID overflow; real business logic with 35+ tests but may not compile against current `NotificationIdGenerator` API |
| 15 | `service/NotificationCaptureServiceStressTest.kt` | **NIGHTLY** | Service stress test (2 tests, Robolectric + Hilt); slow due to Android service lifecycle |
| 16 | `ui/MainViewModelStressTest.kt` | **NIGHTLY** | ViewModel stress test (4 tests); concurrent UI state updates |
| 17 | `ui/screens/addexpense/AddExpenseViewModelStressTest.kt` | **NIGHTLY** | ViewModel stress test (8 tests); concurrent add-expense flows |
| 18 | `ui/screens/aisettings/AiSettingsScreenTextTest.kt` | **DELETE** | Source-text assertion test — asserts literal string values for Compose UI guidance text; purely implementation-coupled; `@Ignore("Requires Android instrumentation - move to androidTest")` indicates it was never migrated |
| 19 | `ui/screens/analytics/AnalyticsStateStressTest.kt` | **NIGHTLY** | Analytics state stress test; concurrent state-flow assertions |
| 20 | `ui/screens/analytics/AnalyticsViewModelStressTest.kt` | **NIGHTLY** | ViewModel stress test (8 tests); concurrent analytics viewmodel operations |
| 21 | `ui/screens/budget/BudgetViewModelStressTest.kt` | **NIGHTLY** | ViewModel stress test (15 tests); concurrent budget viewmodel operations |
| 22 | `ui/screens/debug/DebugViewModelStressTest.kt` | **NIGHTLY** | ViewModel stress test; concurrent debug viewmodel operations |
| 23 | `ui/screens/home/HomeViewModelStressTest.kt` | **NIGHTLY** | ViewModel stress test (18 tests); concurrent home viewmodel operations |
| 24 | `ui/screens/map/SpendingMapViewModelStressTest.kt` | **NIGHTLY** | ViewModel stress test (3 tests); concurrent map viewmodel operations |
| 25 | `ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt` | **NIGHTLY** | ViewModel stress test (4 tests, Robolectric); concurrent receipt scan operations |
| 26 | `ui/screens/review/ReviewViewModelStressTest.kt` | **NIGHTLY** | ViewModel stress test (18 tests); concurrent review viewmodel operations |
| 27 | `ui/screens/transactions/TransactionsViewModelStressTest.kt` | **NIGHTLY** | ViewModel stress test (13 tests); concurrent transactions viewmodel operations |

---

## Detailed Sections

### DELETE (2 files)

These files should be removed entirely. They provide no ongoing regression safety and are either testing platform behavior or asserting hardcoded UI strings.

#### 1. `consistency/ConcurrencyStateRaceTest.kt`
- **@Ignore reason:** `"Tests stdlib StateFlow behavior, not production code"`
- **Why DELETE:** This class verifies Kotlin stdlib `MutableStateFlow` semantics (ordered emissions, cancellation cleanup, concurrent updates). These are guarantees of the coroutines library, not of any application code. The test is permanently ignored by design and has zero regression value for the project.
- **Test count:** 4 tests
- **Risk of removal:** None — stdlib concurrency is tested by the Kotlin team.

#### 2. `ui/screens/aisettings/AiSettingsScreenTextTest.kt`
- **@Ignore reason:** `"Requires Android instrumentation - move to androidTest"`
- **Why DELETE:** Asserts exact literal strings composed inside Compose UI (`"On-device AI is unavailable. Cloud routing can still handle advisory features..."`, `"Cloud fallback available"`). These are implementation-coupled source-text assertions that break on any copy change. The `@Ignore` itself acknowledges it belongs in `androidTest` but it was never moved. String content should be validated by screenshot/accessibility tests, not by brittle `assertEquals` on concatenated text.
- **Test count:** 5 tests
- **Risk of removal:** Low — text changes would require updating this file anyway. Prefer snapshot testing or semantics testing for UI text.

---

### REWRITE (4 files)

These files protect genuine business contracts and should be rewritten to compile against the current codebase and use proper testing patterns.

#### 3. `data/database/TransactionRollbackTest.kt`
- **Priority:** CRITICAL-2 Extension
- **Why REWRITE:** The testing assessment explicitly flags transaction rollback as a critical gap. This test simulates rollback scenarios using boolean flags and `simulateTransaction` helpers, but never connects to a real Room database. The rewrite should:
  - Use an in-memory Room database with actual `@Transaction` methods
  - Verify that `GroupTransactionCoordinator` or equivalent rolls back on partial failure
  - Test SQL constraint violations, disk-full, and connection-lost scenarios with real DAO operations
- **Test count:** 15 tests (1 nested @Ignore for concurrency)
- **Value:** High — data integrity across group creation, expense splits, and cascading deletes.

#### 4. `domain/tax/TaxCalculationTest.kt`
- **Priority:** HIGH-6
- **Why REWRITE:** Tests VAT rates and progressive tax brackets using real `TaxConfiguration` classes. The test shows strong understanding of the domain but references classes like `GreeceTaxConfiguration` and `UsTaxConfiguration` that may not exist in the current codebase. The rewrite should:
  - Align with the current `TaxConfiguration` interface/class hierarchy
  - Cover bracket boundaries (€9,999 vs €10,001)
  - Test multiple VAT rates (GR 24%, US 0%, other regions)
  - Validate `Money` precision in tax outputs
- **Test count:** 30+ tests (est.)
- **Value:** High — financial correctness for tax calculation is a compliance risk.

#### 5. `domain/util/MoneyTest.kt`
- **Priority:** CRITICAL-2 / HIGH-2
- **Why REWRITE:** Tests the `Money` class for precision, division, and formatting. The test references `Money.fromDouble()`, `Money.fromString()`, `Money.plus()`, `Money.divide()` which may have been refactored. The rewrite should:
  - Verify `100.0.toMoney().divide(3)` sums exactly to `100.00` (33.33 + 33.33 + 33.34)
  - Test `BigDecimal` rounding modes (`HALF_EVEN`, `HALF_UP`)
  - Cover edge cases: zero, negative, very large amounts
  - Ensure `format()` produces locale-independent output
- **Test count:** 40+ tests (est.)
- **Value:** Critical — floating-point errors in financial calculations cause data corruption.

#### 6. `domain/util/NotificationIdGeneratorTest.kt`
- **Priority:** HIGH-4
- **Why REWRITE:** Tests the `NotificationIdGenerator` utility for range enforcement, collision prevention, and overflow safety. The test is thorough (35+ tests) but may use an outdated API surface. The rewrite should:
  - Match the current `NotificationIdGenerator` method signatures
  - Verify `Long.MAX_VALUE` → `Int` mapping without overflow
  - Confirm range partitioning (budget 1–9999, warranty 10000–19999, receipt 20000–29999, etc.)
  - Test the `toNotificationId()` extension function for each `NotificationType`
- **Test count:** 35 tests
- **Value:** High — notification ID collisions cause wrong notifications to be updated/dismissed, a real user-facing bug.

---

### NIGHTLY (21 files)

These are deterministic stress/load/concurrency tests. They have genuine value for catching race conditions and performance regressions, but they are too slow for pull-request CI. They should run on a nightly schedule (e.g., `schedule` trigger in GitHub Actions).

| # | File | Test Count | Notes |
|---|------|-----------|-------|
| 2 | `data/database/entity/ExpenseEntityStressTest.kt` | 56 | Domain model constraints under load |
| 4 | `data/location/CompositeGeocodingServiceStressTest.kt` | 42 | I/O-like coroutine stress |
| 5 | `data/repository/BudgetRepositoryStressTest.kt` | 10 | Concurrent budget ops |
| 6 | `data/repository/CategoryRepositoryStressTest.kt` | 4 | Concurrent category ops |
| 7 | `data/repository/ExpenseRepositoryStressTest.kt` | 12 | Concurrent expense ops; `@Ignore("may hang in CI")` |
| 8 | `data/repository/NotificationProcessingPipelineStressTest.kt` | 42 | High-volume pipeline scenarios |
| 9 | `data/repository/NotificationRepositoryStressTest.kt` | 21 | Concurrent notification CRUD |
| 10 | `data/repository/ReceiptRepositoryStressTest.kt` | 12 | Concurrent receipt ops |
| 11 | `data/repository/ReviewQueueRepositoryStressTest.kt` | 8 | Concurrent review queue ops |
| 15 | `service/NotificationCaptureServiceStressTest.kt` | 2 | Robolectric + Hilt; service lifecycle overhead |
| 16 | `ui/MainViewModelStressTest.kt` | 4 | Concurrent ViewModel state updates |
| 17 | `ui/screens/addexpense/AddExpenseViewModelStressTest.kt` | 8 | Concurrent add-expense flows |
| 19 | `ui/screens/analytics/AnalyticsStateStressTest.kt` | — | Analytics state-flow stress |
| 20 | `ui/screens/analytics/AnalyticsViewModelStressTest.kt` | 8 | Concurrent analytics VM ops |
| 21 | `ui/screens/budget/BudgetViewModelStressTest.kt` | 15 | Concurrent budget VM ops |
| 22 | `ui/screens/debug/DebugViewModelStressTest.kt` | — | Concurrent debug VM ops |
| 23 | `ui/screens/home/HomeViewModelStressTest.kt` | 18 | Concurrent home VM ops |
| 24 | `ui/screens/map/SpendingMapViewModelStressTest.kt` | 3 | Concurrent map VM ops |
| 25 | `ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt` | 4 | Robolectric; concurrent receipt scan VM ops |
| 26 | `ui/screens/review/ReviewViewModelStressTest.kt` | 18 | Concurrent review VM ops |
| 27 | `ui/screens/transactions/TransactionsViewModelStressTest.kt` | 13 | Concurrent transactions VM ops |

**Total NIGHTLY tests:** ~300+ individual test methods.

**Recommendation:** Create a separate GitHub Actions workflow (`.github/workflows/nightly.yml`) triggered by `schedule` (e.g., daily at 03:00) that runs these 21 files with:

```yaml
on:
  schedule:
    - cron: '0 3 * * *'
jobs:
  nightly-stress:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run nightly stress tests
        run: ./gradlew testDebugUnitTest --tests "*StressTest*" --tests "*RaceTest*"
```

---

## Rollup

| Classification | Count | Action |
|---------------|-------|--------|
| DELETE | 2 | Remove files and their `@Ignore` annotations entirely |
| REWRITE | 4 | Rewrite to compile and run against current codebase; move to PR CI gate |
| NIGHTLY | 21 | Keep `@Ignore` for PR CI; run on nightly schedule |
| **Total** | **27** | |

## Effort Estimate

| Activity | Est. Effort |
|----------|-------------|
| Delete 2 files | 5 minutes |
| Rewrite `TransactionRollbackTest.kt` | 4–6 hours (requires Room in-memory DB, real DAO setup) |
| Rewrite `MoneyTest.kt` | 2–3 hours |
| Rewrite `TaxCalculationTest.kt` | 2–3 hours |
| Rewrite `NotificationIdGeneratorTest.kt` | 1–2 hours |
| Create nightly workflow + un-ignore 21 files | 1 hour |
| **Total** | **~10–15 hours** |
