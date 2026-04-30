# Test Suite Scout Report

**Workflow:** `wf-20260423-0824-analyze-debug-optimize-test-suite`
**Date:** 2026-04-23
**Scout Passes:** 2 (sequential deep sweep)

---

## 1. Overview

| Metric | Value |
|---|---|
| Total test files | 421+ |
| Unit test files | 403 |
| Instrumented test files | 26 |
| Total test methods | ~4,347 |
| Unit test methods | ~4,004 |
| Instrumented test methods | ~343 |
| Helper/fixture files | 8 |
| Files with `@Ignore` | 35 |
| Stress test files (all ignored) | 10+ |
| Room schema snapshots | 33–92 |

---

## 2. Framework Breakdown

| Framework | Files Using It |
|---|---|
| JUnit4 | 423 |
| MockK | 238 |
| AndroidX Test | 40 |
| Truth | 28 |
| Robolectric | 22 |
| Room Testing | 7 |
| Turbine | 3 |
| Hilt Testing | 2 |
| Mockito | 1 |
| Espresso | 1 |

**Notable:** Only 1 file uses Mockito (`SpendingThresholdCalculatorTest.kt`) — possible migration leftover. Espresso appears in a unit test (`ExpenseCategoryClassifierTest.kt`) — likely a misclassification or will fail on JVM.

---

## 3. Test Directory Structure

All tests live under `app/src/`:

```
app/src/test/java/com/yourname/expensetracker/     ← unit tests (403 files)
app/src/test/kotlin/com/yourname/expensetracker/    ← small number of Kotlin-source unit tests
app/src/androidTest/java/com/yourname/expensetracker/ ← instrumented tests (26 files)
```

### Major test areas
- `data/database/**` — biggest instrumentation-heavy area (DAOs, migrations)
- `data/repository/**` — repository unit tests
- `domain/analytics/**` — analytics engine tests
- `domain/ai/**` — AI/ML provider and use-case tests
- `domain/budget/**` — budget logic tests
- `ui/screens/**` — ViewModel and Compose UI tests
- `e2e/**` — end-to-end pipeline tests (unit-level)
- `integration/**` — cross-module integration tests
- `consistency/**` — cross-parser consistency tests
- `verification/**` — group integration verification

---

## 4. Helper / Fixture Files

| File | Location |
|---|---|
| `AnalyticsEngineTestBase.kt` | `app/src/test/.../domain/analytics/` |
| `TestUtils.kt` | `app/src/test/.../` |
| `FlowTestUtils.kt` | `app/src/test/.../` |
| `HiltTestUtils.kt` | `app/src/test/.../` |
| `FakeTimeProvider.kt` | `app/src/test/.../` |
| `GoldenAnalyticsDataset.kt` | `app/src/test/.../` |
| `ExpectedResults.kt` | `app/src/test/.../` |
| `GoldenDataSets.kt` | `app/src/test/.../` |
| `FlowPipelineTestHarness.kt` | `app/src/test/.../` |

---

## 5. Disabled / Ignored Tests — Deep Dive

35 files contain `@Ignore` or disabling patterns. Categories:

### 5A. Assertion / Contract Drift (fixable)

These tests are ignored because the production code changed but the tests weren't updated:

| File | Ignored Methods | Root Cause | Fixable? |
|---|---|---|---|
| `WarrantyTextExtractorTest.kt` | `extract applies merchant based default warranty` (L54), `extract returns empty extraction data for non-warranty text` (L74) | Parsing order changed; non-warranty text still extracts TOTAL field | ✅ Yes |
| `SuggestReceiptExtractionUseCaseTest.kt` | `invoke returns NotNeeded when receipt already looks complete` (L95), `invoke marks image-aware receipt assist` (L183) | Missing mock for `ReceiptAssistInputBuilder.build`; artifact explanation assertion mismatch | ✅ Yes |
| `ReceiptAssistInputBuilderTest.kt` | `build keeps contextual receipt fields when redaction off` (L32), `build redacts long sensitive numeric values when redaction on` (L66) | `imagePath` field mismatch | ✅ Yes |
| `CsvEscapingTest.kt` | `iif field with tab is replaced with space` (L141), `iif field with all special chars is properly escaped` (L182), `csv escaping prevents delimiter injection attack` (L293) | IIF/CSV escaping contract changed | ✅ Yes |
| `CustomSplitParserTest.kt` | — | Parser contract drift | ✅ Yes |
| `PriceProtectionTrackerTest.kt` | — | Assertion mismatch | ✅ Yes |

### 5B. Test Tooling Issues (fixable)

| File | Ignored Methods | Root Cause | Fixable? |
|---|---|---|---|
| `SplitCalculationPrecisionTest.kt` | Multiple precision cases (L22, 40, 54, 105, 121, 161, 174, 238, 265) | `Truth assertThat` incompatible with Kotlin value class boxing | ✅ Yes — use direct equality or different assertion lib |

### 5C. Stress Tests (all class-level `@Ignore`)

Every stress test is disabled at the class level with `@Ignore("Stress test: may hang in CI, run manually")`. These **never run in CI**:

| File | What It Stresses | Timing Risk |
|---|---|---|
| `AnalyticsViewModelStressTest.kt` | Period switching (WEEK/TODAY/ALL), rapid changes, empty expenses, YoY | Low |
| `DebugViewModelStressTest.kt` | AI runtime statuses, engagement state, diagnostics/reset | Medium — launches collectors in `backgroundScope` |
| `HomeViewModelStressTest.kt` | Dashboard load, categories, reload-after-error, config mutation | Medium — mutable `configFlow` reused |
| `NotificationCaptureServiceStressTest.kt` | Service creation, null `StatusBarNotification`, drain with timeout | **High** — `delay(5)`, `stopAcceptingAndDrain(timeoutMs = 1000)` |
| `BudgetViewModelStressTest.kt` | CRUD budget ops, refresh suggestions, error clearing | Medium — manipulates `Dispatchers.Main` |
| `ConcurrencyStateRaceTest.kt` | Concurrent state mutation | High — race-sensitive by design |
| `CrossParserConsistencyStressTest.kt` | Cross-parser output consistency | Low |
| `SharedUtilityConsistencyStressTest.kt` | Shared utility consistency | Low |
| `ExpenseEntityStressTest.kt` | Entity stress | Low |
| `TransactionRollbackTest.kt` | DB transaction rollback | Medium |
| `CompositeGeocodingServiceStressTest.kt` | Geocoding service under load | Medium |
| `BudgetRepositoryStressTest.kt` | Repository CRUD under load | Medium |
| `CategoryRepositoryStressTest.kt` | Repository CRUD under load | Medium |
| `ExpenseRepositoryStressTest.kt` | Repository CRUD under load | Medium |
| `NotificationProcessingPipelineStressTest.kt` | Pipeline processing | Medium |
| `NotificationRepositoryStressTest.kt` | Repository ops | Low |
| `ReceiptRepositoryStressTest.kt` | Repository ops | Low |
| `ReviewQueueRepositoryStressTest.kt` | Repository ops | Low |

### 5D. Database Migration Test

`DatabaseMigrationTest.kt` uses `assume` statements and has TODOs — some cases may silently skip.

---

## 6. Flakiness Risk Assessment

### 6A. Time-Dependent Assertions

Files using `System.currentTimeMillis()`, `Date(...)`, `Calendar.getInstance()`, `Instant.now`:
- `AnalyticsViewModelStressTest.kt`
- `BudgetViewModelStressTest.kt`
- `GroupTransactionCoordinatorTest.kt`
- `DatabaseMigrationTest.kt`
- `FreshInstallIndexParityTest.kt`
- `WarrantyTextExtractorTest.kt`

**Recommendation:** Inject a `FakeTimeProvider` (already exists in test utils) or use `Instant.fixed` in tests.

### 6B. Coroutine / Timing Risks

- `NotificationCaptureServiceStressTest.kt` — uses `delay(5)` and `stopAcceptingAndDrain(timeoutMs = 1000)`
- `DebugViewModelStressTest.kt` — launches collectors in `backgroundScope`
- Many stress tests rely on `StandardTestDispatcher` + `advanceUntilIdle()`
- Several tests use `runBlocking` which can hide async issues

### 6C. File I/O in Tests

- `DatabaseBackupRepositoryImplTest.kt` — temp files and DB copies
- `NotificationExpenseDashboardPipelineTest.kt` — temp filesystem usage

### 6D. Shared Mutable State

- `HomeViewModelStressTest.kt` — `configFlow` is mutable and reused across tests
- `BudgetViewModelStressTest.kt` — manipulates `Dispatchers.Main` in setup/teardown
- Relaxed mocks in many stress tests can hide missing assertions

### 6E. Framework Misuse

- **Espresso in unit test:** `domain/intelligence/ml/ExpenseCategoryClassifierTest.kt` uses Espresso — will fail on JVM without Robolectric
- **Mockito single file:** `domain/analytics/SpendingThresholdCalculatorTest.kt` — should likely be migrated to MockK for consistency

---

## 7. Build Configuration Issues

### Missing test options in `app/build.gradle.kts`
- ❌ No `testOptions {}` block
- ❌ No `maxParallelForks` — tests run single-threaded by default
- ❌ No `forkEvery` — test processes may accumulate memory
- ❌ No test logging configuration
- ❌ No include/exclude test filters for CI vs local runs

### Stale Room schema verification
- `verifyRoomSchemaSnapshots` hardcodes `maxVersion = 35` (line 185)
- But `app/schemas/` contains schema snapshots from **33 through 92**
- This verification task is **completely out of sync** and likely always passes trivially or is never run

### Test dependencies (confirmed)
- `junit:junit:4.13.2`
- `mockk`
- `kotlin("test")`
- `kotlinx-coroutines-test`
- `robolectric`
- `core-testing`
- `turbine`
- `truth`
- `work-testing`
- `hilt-android-testing`
- Compose UI test j_unit

---

## 8. Potential Duplicates / Overlap

These areas have parallel test families that may duplicate coverage:

| Area | Files |
|---|---|
| DAO parity/migration | Multiple DAO test suites |
| Notification pipeline | `NotificationProcessingPipeline*` + `NotificationRepositoryStressTest` |
| Receipt pipeline | `ReceiptProcessingPipelineTest` + `ReceiptRepositoryStressTest` |
| Analytics pipeline | `AnalyticsPipelineTest` + `AnalyticsViewModelStressTest` |
| Group settlement | `GroupSettlementPipelineTest` + `SharedExpenseFlowTest` |
| AI providers | On-device and cloud variants of same use-case tests |

Many "StressTest" classes mirror regular test classes but are ignored — potential dead test code.

---

## 9. Pass 2 Delta — Files Found That Pass 1 Might Have Missed

| File | Notes |
|---|---|
| `app/src/test/kotlin/.../domain/logic/RecurringExpenseEngineTest.kt` | In `src/test/kotlin` not `src/test/java` |
| `app/src/test/.../verification/CrossGroupIntegrationTest.kt` | Non-standard directory |
| `app/src/test/.../e2e/NotificationExpenseDashboardPipelineTest.kt` | E2E category |
| `app/src/test/.../consistency/DuplicateLogicConsistencyIntegrationTest.kt` | Consistency category |
| `app/src/test/.../integration/*IntegrationTest.kt` | Integration category |
| `app/src/test/.../domain/forecasting/FinancialStressForecastEngineTest.kt` | Forecasting subdomain |
| `app/src/androidTest/.../data/database/dao/DaoStressTest.kt` | Instrumented stress test |

---

## 10. Summary of Issues by Priority

### 🔴 High Priority (affects CI reliability or correctness)
1. **35 files with `@Ignore`** — many are fixable assertion drift, not real instabilities
2. **No `testOptions` in build config** — no parallelism, no logging, no filtering
3. **Stale `verifyRoomSchemaSnapshots`** — hardcoded `maxVersion=35` but schemas go to 92
4. **Espresso in JVM unit test** — `ExpenseCategoryClassifierTest.kt` will fail

### 🟡 Medium Priority (flakiness risk)
5. **Time-dependent assertions** across 6+ files — should use `FakeTimeProvider`
6. **Coroutine timing risks** in stress tests — `delay()`, `backgroundScope`, `runBlocking`
7. **Shared mutable state** — `configFlow`, `Dispatchers.Main` manipulation
8. **All stress tests permanently disabled** — 10+ stress test files never run

### 🟢 Low Priority (cleanup / consistency)
9. **Mockito in 1 file** — migrate to MockK for consistency
10. **Relaxed mocks hiding missing assertions** in stress tests
11. **Potential duplicate test coverage** across parallel pipeline families
12. **Some stress tests are just smoke tests** — misnamed, not actually stressing anything

---

## 11. Recommended Next Steps

1. **Fix assertion-drift ignored tests** (Section 5A) — quick wins, ~15+ test methods re-enabled
2. **Fix `SplitCalculationPrecisionTest`** — value class boxing issue with Truth
3. **Add `testOptions` to `build.gradle.kts`** — parallelism, logging, filtering
4. **Fix `verifyRoomSchemaSnapshots`** — update `maxVersion` to 92
5. **Move Espresso test to androidTest** or add Robolectric
6. **Migrate Mockito test to MockK**
7. **Audit stress tests** — decide which to fix & enable, which to delete
8. **Inject `FakeTimeProvider`** in time-dependent tests
9. **Standardize coroutine test patterns** — eliminate `runBlocking`, use `StandardTestDispatcher` consistently
10. **Remove dead/obsolete test code** — duplicated pipeline families, permanently disabled stress tests
