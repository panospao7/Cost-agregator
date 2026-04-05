# Testing Implementation Review

> **Date:** April 5, 2026  
> **Scope:** Full codebase — all backend (data, domain) and frontend (UI) test coverage  
> **Production files:** 638 Kotlin files  
> **Test files:** 260 unit + 10 instrumented = **270 total**

---

## Executive Summary

The project has a **substantial test suite** (270 files, estimated 1,500+ individual test methods) with good infrastructure and patterns. However, coverage is **heavily skewed**: the analytics and AI subsystems are well-tested, while large parts of the UI layer, location services, groups/splits, and several domain engines have little or no test coverage.

**Key metrics:**
- Test-to-production file ratio: **42%** (270 / 638)
- ViewModel test coverage: **22 of 37** (59%) have dedicated tests
- UI screen coverage: **14 of 33** (42%) feature directories have test mirrors
- Instrumented test coverage: **10 files** (extremely thin for a 46-entity database)
- Compose UI tests: **0** (no screenshot, snapshot, or composable tests)
- E2E / integration tests: **22 files** across `e2e/`, `integration/`, `verification/`, `metrics/`

**Overall testing score: 55/100**

---

## 1. Test Infrastructure & Patterns

### Frameworks & Libraries

| Tool | Usage | Assessment |
|------|-------|------------|
| **MockK** | Primary mocking framework | Used consistently across all tests |
| **JUnit 4** | Test runner + assertions | Standard, no JUnit 5 migration yet |
| **Robolectric** | Android framework mocking | Used in ViewModel tests (`@Config(sdk=[28])`) |
| **Turbine** | Flow testing | Used in ViewModel stress tests for StateFlow assertions |
| **kotlinx-coroutines-test** | Coroutine testing | `runTest`, `advanceUntilIdle`, `StandardTestDispatcher` |
| **AndroidX Test** | Instrumented tests | `MigrationTestHelper`, `ApplicationProvider` |

### Test Utilities (5 shared helpers)

| Utility | Location | Purpose |
|---------|----------|---------|
| `TestUtils.kt` | `test/` root | Expense test data DSL, float-tolerant assertions |
| `ViewModelTestUtils.kt` | `test/util/` | Abstract base for ViewModel tests (dispatcher setup) |
| `HiltTestUtils.kt` | `test/util/` | Base for Hilt-instrumented unit tests |
| `FlowTestUtils.kt` | `test/util/` | Flow/coroutine testing helpers |
| `FakeTimeProvider.kt` | `test/domain/util/` | Deterministic clock for time-dependent tests |
| `AnalyticsEngineTestBase.kt` | `test/` root | MockK + coroutine base for analytics engines |
| `FlowPipelineTestHarness.kt` | `test/e2e/` | Full mock pipeline wiring for E2E-style tests |

**Assessment:** Good foundation. The test infrastructure supports deterministic time, dispatcher injection, and Flow testing. Missing: no shared `FakeRepository` implementations, no `TestDatabaseFactory`, no snapshot testing infrastructure.

### Naming Conventions

Tests follow two naming patterns:
- `*Test.kt` — standard unit tests
- `*StressTest.kt` — stress/load tests with edge cases and rapid-fire scenarios
- `*ValidationTest.kt` / `*DeepTest.kt` — focused mathematical/behavioral validation
- `*VerificationTest.kt` — cross-component verification

This is a **well-organized** naming scheme.

---

## 2. Test Distribution by Layer

### 2.1 Data Layer Tests (51 files)

| Subsystem | Test Files | Production Files | Coverage |
|-----------|-----------|-----------------|----------|
| `data/ai/provider/` | 16 | ~33 | Good — cloud services, hybrid, smart retry |
| `data/ai/provider/internal/` | 2 | ~3 | Good — JSON parser, retry policy |
| `data/database/` | 2 (unit) + 2 (androidTest) | ~100 | **Weak** — only GroupTransactionCoordinator + converter |
| `data/database/dao/` | 2 (unit) + 7 (androidTest) | 45 | **Weak** — 9 tests for 45 DAOs |
| `data/database/entity/` | 3 | 46 | **Very weak** |
| `data/email/` | 1 | 5 | **Minimal** |
| `data/location/` | 3 (unit) + 1 (androidTest) | 9 | Moderate |
| `data/repository/` | 18 | 36 | **Moderate** — 50% file coverage |
| `data/security/` | 1 | 1 | Complete |
| `data/currency/` | **0** | ~3 | **NONE** |
| `data/service/` | **0** | ~2 | **NONE** |
| `data/speech/` | **0** | ~1 | **NONE** |

**Key gaps:**
- **36 of 45 DAOs have no dedicated tests** — only `ExpenseDao`, `PendingReviewDao`, `AiChatSessionDao`, `AiChatMessageDao`, `AiArtifactDao`, `ComplexQuery`, and stress tests exist
- **No currency/exchange rate tests**
- **Email ingestion barely tested** (1 file for 5 production files)

### 2.2 Domain Layer Tests (130 files)

| Subsystem | Test Files | Production Files | Coverage |
|-----------|-----------|-----------------|----------|
| `domain/analytics/` | 19 + 2 fixtures | ~15 | **Excellent** — multiple validation/deep/stress tests |
| `domain/ai/usecase/` | 17 | ~17 | **Excellent** — input builders, use cases |
| `domain/ai/policy/` | 2 | ~3 | Good |
| `domain/ai/model/` | 3 | ~11 | Moderate |
| `domain/parser/` | 13 + 1 (parsers/) | ~9 | **Excellent** — well-tested parsing |
| `domain/categorization/` | 7 | 6 | Good file count, **weak quality** (see below) |
| `domain/logic/` | 5 | 5 | Good |
| `domain/location/` | 6 | 8 | Moderate file count, **weak depth** (2 real tests) |
| `domain/util/` | 13 | ~24 | Moderate |
| `domain/budget/` | 2 | 6 | **Weak** — BudgetCalculator, BudgetMonitor untested |
| `domain/receipt/` | 4 | 6 | Moderate |
| `domain/savings/` | 2 | 3 | Moderate |
| `domain/forecasting/` | 2 | 4 | Moderate |
| `domain/health/` | 1 | 2 | Minimal |
| `domain/groups/` | 1 | 5 | **Very weak** — SettlementCalculator, SharedExpenseManager untested |
| `domain/intelligence/` | 2 + 5 (ml/) | ~6 | Good |
| `domain/usecase/` | 6 across 5 subdirs | ~10 | Moderate |
| `domain/currency/` | 1 | 3 | Minimal |
| `domain/export/` | 1 | 1 | Complete |
| `domain/carbon/` | 1 | 1 | Complete |
| `domain/alerts/` | 1 | 1 | Complete |
| `domain/cashflow/` | 1 | 1 | Complete |
| `domain/price/` | 1 | 1 | Complete |
| `domain/split/` | 1 | 1 | Complete |
| `domain/tax/` | 1 | 2 | Moderate |
| `domain/widget/` | 1 | 2 | Moderate |
| `domain/engine/` | 1 | 1 | Complete |
| `domain/model/` | 2 | 17 | **Weak** — most models untested |
| `domain/bank/` | **0** | 1 | **NONE** |
| `domain/subscription/` | **0** | 2 | **NONE** |
| `domain/naturallanguage/` | **0** | 1 | **NONE** |
| `domain/performance/` | **0** | 1 | **NONE** |
| `domain/challenge/` | **0** | 1 | **NONE** |
| `domain/negotiation/` | **0** | 1 | **NONE** |
| `domain/reminder/` | **0** | 1 | **NONE** |
| `domain/investment/` | **0** | 1 | **NONE** |
| `domain/lifestyle/` | **0** | 1 | **NONE** |
| `domain/income/` | **0** | 1 | **NONE** |

**Key gaps:**
- **9 entire domain subsystems have zero tests** (bank, subscription, natural language, performance, challenge, negotiation, reminder, investment, lifestyle)
- Groups/splits **critically under-tested** given recent review findings (duplicated logic, DFS solver)
- `BudgetCalculator` — a core financial component with 5 period modes — has **no dedicated test**

### 2.3 UI Layer Tests (21 files)

| Screen / Component | Test Files | Assessment |
|---|---|---|
| `ui/screens/home/` | 3 (HomeViewModelStressTest, HomeViewModelRecommendation, HomeViewModelPeriodNavigation) | **Good** |
| `ui/screens/receiptscan/` | 1 (StressTest) | Moderate — 19 tests but missing stale-state |
| `ui/screens/review/` | 1 (StressTest) | Moderate |
| `ui/screens/transactions/` | 1 (StressTest) | Moderate |
| `ui/screens/budget/` | 1 (StressTest) | Moderate |
| `ui/screens/analytics/` | 2 (StressTest + Validation) | Good |
| `ui/screens/map/` | 1 (StressTest) | Moderate |
| `ui/screens/addexpense/` | 1 (StressTest) | Moderate |
| `ui/screens/debug/` | 2 (StressTest + DataStorage) | Good |
| `ui/screens/export/` | 1 (ViewModelTest) | Moderate |
| `ui/screens/aisettings/` | 2 (ViewModelTest + StressTest) | Good |
| `ui/screens/price/` | 1 (ViewModelTest) | Moderate |
| `ui/screens/cashflow/` | 1 (ViewModelTest) | Moderate |
| `ui/screens/assistant/` | 1 (ViewModelTest) | Moderate |
| `ui/components/emptystate/` | 1 | Minimal |
| `ui/` root | 1 (MainViewModelStressTest) | Good |

**19 screen directories have NO tests:**

| Untested Screen | Risk Level |
|---|---|
| `groups/` (SharedExpenseGroupsViewModel) | **HIGH** — known issues in split logic |
| `currency/` (CurrencyManagementViewModel) | **MEDIUM** — financial operations |
| `savings/` (SavingsGoalsViewModel) | **MEDIUM** — savings goal tracking |
| `subscription/` (SubscriptionManagementViewModel) | **MEDIUM** |
| `warranty/` (WarrantyTrackerViewModel) | **MEDIUM** |
| `bank/` (BankConnectionsViewModel) | **MEDIUM** — external API integration |
| `investment/` (InvestmentViewModel) | **MEDIUM** |
| `tax/` (TaxConfigurationViewModel) | **LOW** |
| `categories/` (CategoryViewModel) | **LOW** |
| `recurring/` | **LOW** |
| `recurringmanual/` | **LOW** |
| `naturallanguage/` | **MEDIUM** — speech integration |
| `lifestyle/` | **LOW** |
| `carbon/` | **LOW** |
| `challenge/` | **LOW** |
| `split/` | **LOW** |
| `reminder/` | **LOW** |
| `negotiation/` | **LOW** |
| `receiptmatching/` | **LOW** |

### 2.4 Cross-Cutting / Integration Tests (30 files)

| Category | Files | Purpose |
|----------|-------|---------|
| `e2e/` | 7 | Flow pipeline harness + end-to-end scenarios |
| `integration/` | 9 | Pipeline integration, cross-component flows |
| `verification/` | 6 | Golden master, cross-source, cross-group, shared expense |
| `metrics/` | 8 | TimePeriod alignment, golden analytics dataset |
| `consistency/` | 10 | Currency normalizer, parser consistency, cross-subsystem |
| `service/` | 8 | Notification service, background processing |
| `receiver/` | 2 | Broadcast receiver tests |

**Assessment:** Solid — these provide valuable cross-component coverage. The golden master pattern in `verification/` is particularly good for regression detection.

### 2.5 Instrumented Tests (10 files)

| File | Tests | What it covers |
|------|-------|----------------|
| `DatabaseMigrationTest.kt` | 24 | Migration chains (v1→v51), schema/data/FK validation |
| `MigrationContractTest.kt` | 4 | Isolated single-step migrations (v6→10) |
| `ExpenseDaoTest.kt` | 13 | Core DAO CRUD, dedup, category update |
| `DaoStressTest.kt` | 20 | Bulk insert, concurrency, memory |
| `PendingReviewDaoTest.kt` | ? | Pending review DAO |
| `AiChatSessionDaoTest.kt` | ? | AI chat session DAO |
| `AiChatMessageDaoTest.kt` | ? | AI chat message DAO |
| `AiArtifactDaoTest.kt` | ? | AI artifact DAO |
| `ComplexQueryTest.kt` | ? | Complex SQL queries |
| `MerchantKeyBackfillWorkerTest.kt` | ? | Location backfill worker |

**Assessment:** Migration tests are **good** (24 tests covering chains and FK validation). DAO coverage is **very thin** — only 9 tests for 45 DAOs. No instrumented tests for:
- Budget, category, recurring, savings, group, investment, subscription DAOs
- Repository integration (with real DB)
- Worker tests (only 1 backfill worker tested)

---

## 3. Test Quality Assessment

### 3.1 Quality Distribution (sampled files)

| File | Tests | Quality | Notes |
|------|-------|---------|-------|
| `BudgetForecastingEngineTest` | 8 | **4/5** | Strong mathematical coverage, missing error paths |
| `GreekBankParserTest` | 10 | **4/5** | Good positive + negative, missing edge cases |
| `HomeViewModelStressTest` | 19 | **4/5** | Comprehensive stress + error resilience |
| `CategorizationEngineTest` | 6 | **2/5** | Sparse for a core engine, assertion-less test |
| `AnalyticsPipelineIntegrationTest` | 21 | **2/5** | 20 of 21 test stdlib arithmetic, not production code |
| `LocationResolverTest` | 2 | **2/5** | 2 tests for a 266-line 8-step resolver |
| `DatabaseMigrationTest` | 24 | **4/5** | Broad, validates schema/data/FK |
| `DaoStressTest` | 20 | **3/5** | Good ideas, flaky assertions (timing, memory) |

### 3.2 Common Strengths

1. **Deterministic time injection** — `FakeTimeProvider` used consistently
2. **MockK usage** — clean `coEvery`/`coVerify` patterns
3. **Stress testing pattern** — rapid-fire, boundary, concurrent scenarios
4. **Flow testing** — Turbine for StateFlow assertions in ViewModels
5. **CancellationException preservation** — verified in coroutine-heavy tests
6. **Golden master pattern** — `verification/` and `metrics/` for regression detection

### 3.3 Common Weaknesses

1. **Error path neglect** — Most tests only cover happy paths. Very few test exception handling, network failures, or database errors
2. **Misleading integration tests** — `AnalyticsPipelineIntegrationTest` inflates count with 20 pure arithmetic tests that test Kotlin stdlib, not production code
3. **Assertion-less tests** — `CategorizationEngineTest` has a test with no assertion (only verifies "no crash")
4. **Missing negative tests** — Parsers and engines rarely test malformed input, empty strings, or null values
5. **Time-based assertions** — `DaoStressTest` uses timing thresholds that are device-dependent and flaky in CI
6. **No parameterized tests** — Heavy copy-paste for similar test cases instead of `@ParameterizedTest`

---

## 4. Coverage Gaps — Critical Areas

### 4.1 CRITICAL: No Compose UI Tests

The app uses Jetpack Compose exclusively (77 screen files, pure Compose, no XML). There are **zero** Compose UI tests:
- No `ComposeTestRule` usage
- No screenshot/snapshot tests
- No accessibility tests
- No composable interaction tests

All UI testing is done at the ViewModel level via MockK + Turbine. This means **the actual rendering, user interactions, navigation, and visual regressions are completely untested**.

### 4.2 HIGH: Groups/Splits Domain — 1 test for 5 production files

The groups subsystem (`domain/groups/`) has known critical issues (duplicated split logic, DFS complexity, Int overflow) but only **1 test file** (`SharedExpenseTest.kt` in `verification/`). Missing:
- `SettlementCalculator` tests (DFS solver — exponential complexity risk)
- `SharedExpenseManager` tests (domain service with 15+ methods)
- `SplitCalculator` parity tests (vs `SharedExpenseManager`)
- Rounding/remainder edge cases

### 4.3 HIGH: Budget Core — No BudgetCalculator Test

`BudgetCalculator` handles 5 period modes (daily, weekly, monthly, yearly, rolling) with month-end edge cases. It has **no dedicated test**. `BudgetForecastingEngineTest` (8 tests) covers forecasting, not the calculator itself.

### 4.4 HIGH: 36 of 45 DAOs Untested

Only 9 DAOs have any test coverage (7 instrumented + 2 unit). Missing DAO tests for:
- BudgetDao, CategoryDao, RecurringExpenseDao, SavingsGoalDao
- GroupExpenseDao, GroupMemberDao, ExpenseGroupDao
- MerchantNormalizationDao, MerchantLocationDao, MerchantCategoryDao
- InvestmentDao, BankConnectionDao, SubscriptionPriceHistoryDao
- WarrantyDao, ReturnWindowDao
- 21 more DAOs

### 4.5 MEDIUM: 9 Domain Engines Completely Untested

| Untested Engine | Production File | Risk |
|---|---|---|
| `BankApiIntegration` | `domain/bank/` | MEDIUM |
| `SubscriptionManagerEngine` | `domain/subscription/` | MEDIUM |
| `NaturalLanguageSearchEngine` | `domain/naturallanguage/` | MEDIUM |
| `ImageCache` | `domain/performance/` | LOW |
| `SpendingChallengeManager` | `domain/challenge/` | LOW |
| `SmartBillNegotiationEngine` | `domain/negotiation/` | LOW |
| `BillReminderManager` | `domain/reminder/` | LOW |
| `InvestmentTracker` | `domain/investment/` | LOW |
| `LifestyleInflationDetector` | `domain/lifestyle/` | LOW |

### 4.6 MEDIUM: DI Module Tests — Zero

25 Hilt modules with no test coverage. While DI modules are typically tested transitively, the `EmptyStateModule` UI import violation would be caught by a simple import check test.

---

## 5. Test Type Distribution

| Test Type | Files | % of Total | Assessment |
|-----------|-------|-----------|------------|
| **Unit tests** (domain) | 130 | 48% | **Strong** — core business logic well-covered |
| **Unit tests** (data) | 51 | 19% | **Moderate** — AI providers well-tested, DAOs weak |
| **Unit tests** (UI/ViewModel) | 21 | 8% | **Moderate** — main screens covered, features gaps |
| **Integration / E2E** | 30 | 11% | **Good** — verification, pipeline, consistency |
| **Instrumented (androidTest)** | 10 | 4% | **Weak** — migration tests good, DAO coverage thin |
| **Test utilities** | ~8 | 3% | **Good** — well-structured helpers |
| **Compose UI tests** | 0 | 0% | **NONE** |
| **Snapshot / screenshot** | 0 | 0% | **NONE** |
| **Performance / benchmark** | 0 | 0% | **NONE** |

---

## 6. ViewModel Coverage Detail

### Tested ViewModels (22 of 37)

| ViewModel | Test File | Tests | Pattern |
|-----------|-----------|-------|---------|
| `MainViewModel` | `MainViewModelStressTest` | ~15 | StressTest |
| `HomeViewModel` | `HomeViewModelStressTest` + 2 more | ~30 | StressTest + focused |
| `TransactionsViewModel` | `TransactionsViewModelStressTest` | ~15 | StressTest |
| `ReviewViewModel` | `ReviewViewModelStressTest` | ~15 | StressTest |
| `BudgetViewModel` | `BudgetViewModelStressTest` | ~15 | StressTest |
| `AnalyticsViewModel` | `AnalyticsViewModelStressTest` + Validation | ~20 | StressTest |
| `SpendingMapViewModel` | `SpendingMapViewModelStressTest` | ~15 | StressTest |
| `AddExpenseViewModel` | `AddExpenseViewModelStressTest` | ~15 | StressTest |
| `ReceiptScanViewModel` | `ReceiptScanViewModelStressTest` | 19 | StressTest |
| `DebugViewModel` | `DebugViewModelStressTest` + DataStorage | ~15 | StressTest |
| `ExportOptionsViewModel` | `ExportOptionsViewModelTest` | ~10 | Standard |
| `AiSettingsViewModel` | `AiSettingsViewModelTest` + StressTest | ~15 | Both |
| `AssistantViewModel` | `AssistantViewModelTest` | ~10 | Standard |
| `PriceProtectionViewModel` | `PriceProtectionViewModelTest` | ~10 | Standard |
| `CashFlowCalendarViewModel` | `CashFlowCalendarViewModelTest` | ~10 | Standard |

### Untested ViewModels (15 of 37)

| ViewModel | Risk | Notes |
|-----------|------|-------|
| `SharedExpenseGroupsViewModel` | **HIGH** | Known non-atomic expense creation bug |
| `CurrencyManagementViewModel` | **HIGH** | Financial currency operations |
| `BudgetForecastingViewModel` | **MEDIUM** | Complex Monte Carlo visualization |
| `SavingsGoalsViewModel` | **MEDIUM** | Goal tracking / progress |
| `SubscriptionManagementViewModel` | **MEDIUM** | Recurring payment management |
| `WarrantyTrackerViewModel` | **MEDIUM** | Warranty expiry tracking |
| `BankConnectionsViewModel` | **MEDIUM** | External API integration |
| `InvestmentViewModel` | **MEDIUM** | Portfolio tracking |
| `NaturalLanguageSearchViewModel` | **MEDIUM** | Speech/NLP integration |
| `AdvancedAnalyticsViewModel` | **MEDIUM** | Complex visualizations |
| `TaxConfigurationViewModel` | **LOW** | Config screen |
| `CarbonFootprintViewModel` | **LOW** | Feature screen |
| `LifestyleInflationViewModel` | **LOW** | Feature screen |
| `CategoryViewModel` | **LOW** | CRUD screen |
| `SpendingChallengesViewModel` | **LOW** | Feature screen |

---

## 7. Recommendations

### Priority 1 — Critical (blocks quality confidence)

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 1 | **Add Compose UI tests** for main tabs (Home, Transactions, Review, Budget) using `ComposeTestRule` | HIGH | Catches rendering bugs, accessibility issues, interaction regressions |
| 2 | **Test SettlementCalculator DFS** — add timeout, 15-member stress test, greedy fallback validation | MEDIUM | Prevents ANR in group settlement |
| 3 | **Test BudgetCalculator** — all 5 period modes, month-end boundaries, leap year | MEDIUM | Core financial correctness |
| 4 | **Test SharedExpenseGroupsViewModel** — non-atomic creation, state management | MEDIUM | Known data integrity bug |

### Priority 2 — High (significant coverage gaps)

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 5 | **Expand DAO instrumented tests** — at least BudgetDao, CategoryDao, GroupExpenseDao, MerchantLocationDao | HIGH | DB contract validation |
| 6 | **Fix AnalyticsPipelineIntegrationTest** — remove 20 arithmetic tests, add real pipeline coverage | LOW | Reduces misleading coverage |
| 7 | **Expand CategorizationEngineTest** — test all strategies, add assertion to cache test, test edge inputs | MEDIUM | Core categorization quality |
| 8 | **Test CurrencyManagementViewModel** — conversion, rate staleness, multi-currency display | MEDIUM | Financial correctness |
| 9 | **Expand LocationResolverTest** — test full 8-step cascade, cache hits, error fallback, cluster bias | MEDIUM | Geocoding reliability |

### Priority 3 — Medium (robustness improvements)

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 10 | **Add parameterized tests** for parsers (many similar test cases currently copy-pasted) | LOW | Maintainability |
| 11 | **Add error-path tests** — DB failures, network errors, timeout handling across repositories | MEDIUM | Resilience confidence |
| 12 | **Remove flaky timing assertions** in `DaoStressTest` — replace with logical correctness checks | LOW | CI stability |
| 13 | **Add snapshot tests** for key Compose screens using Paparazzi or Roborazzi | MEDIUM | Visual regression detection |
| 14 | **Test SplitCalculator/SharedExpenseManager parity** — same inputs produce same outputs | LOW | Catch logic divergence |
| 15 | **Create FakeRepository implementations** for common repositories to reduce MockK boilerplate | MEDIUM | Test maintainability |

### Priority 4 — Low (nice to have)

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 16 | Test remaining 9 untested domain engines | MEDIUM | Feature stability |
| 17 | Add benchmark tests for analytics engines | LOW | Performance regression detection |
| 18 | Test DI modules (basic compilation/wiring tests) | LOW | DI configuration safety |
| 19 | Migrate to JUnit 5 for `@ParameterizedTest`, `@Nested`, better lifecycle | MEDIUM | Developer experience |
| 20 | Add code coverage reporting (JaCoCo) | LOW | Visibility into actual line/branch coverage |

---

## 8. Coverage Heatmap

```
                        Test Coverage Heatmap
                        
    EXCELLENT (>80%)    GOOD (50-80%)    WEAK (20-50%)    NONE (0%)
    ████████████████    ░░░░░░░░░░░░░    ────────────     ............

Data Layer:
  AI Providers      ████████████████
  AI Internal       ████████████████
  Repositories      ░░░░░░░░░░░░░
  Security          ████████████████
  Location          ░░░░░░░░░░░░░
  Database/DAO      ────────────
  Email             ────────────
  Currency          ............
  Service           ............

Domain Layer:
  Analytics         ████████████████
  AI Use Cases      ████████████████
  AI Policy         ████████████████
  Parsers           ████████████████
  Logic/Utils       ░░░░░░░░░░░░░
  Categorization    ░░░░░░░░░░░░░    (quality issue)
  Forecasting       ░░░░░░░░░░░░░
  Budget            ────────────     (missing BudgetCalculator)
  Receipt           ░░░░░░░░░░░░░
  Savings           ░░░░░░░░░░░░░
  Health            ────────────
  Groups            ────────────     (critical gap)
  Location          ────────────     (depth issue)
  Subscription      ............
  Bank              ............
  NLP               ............
  9 more engines    ............

UI Layer:
  Home              ████████████████
  Main Tabs (6)     ░░░░░░░░░░░░░
  Feature Screens   ────────────     (19 of 33 untested)
  Compose Tests     ............     (zero)
  
Instrumented:
  Migrations        ████████████████
  DAOs              ────────────     (9 of 45)
  Workers           ────────────
```

---

## 9. Summary

| Dimension | Score | Notes |
|-----------|-------|-------|
| **Infrastructure** | 8/10 | Good utilities, MockK, Turbine, deterministic time |
| **Domain coverage** | 7/10 | Strong analytics/AI, weak groups/budget/9 engines |
| **Data coverage** | 5/10 | AI providers good, DAOs very thin |
| **UI coverage** | 4/10 | Main screens tested at ViewModel level, no Compose tests |
| **Integration/E2E** | 7/10 | Golden master, pipeline, verification patterns |
| **Instrumented** | 4/10 | Migration tests good, DAO coverage thin |
| **Quality** | 5/10 | Mixed — some 4/5, some misleading 2/5 |
| **Error path coverage** | 3/10 | Consistently neglected across all layers |
| **Edge case coverage** | 5/10 | Good in stress tests, weak elsewhere |
| **Visual/UI testing** | 0/10 | No Compose, snapshot, or accessibility tests |

**Overall: 55/100**

The test suite has a strong foundation and good patterns in the analytics and AI subsystems. The critical improvement areas are: (1) adding Compose UI tests, (2) testing the groups/splits domain, (3) expanding DAO instrumented coverage, (4) testing BudgetCalculator, and (5) adding error-path tests systematically. The existing stress test pattern is excellent and should be extended to untested ViewModels.
