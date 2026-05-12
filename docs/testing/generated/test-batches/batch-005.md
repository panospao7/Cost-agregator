# Test Suite Audit — Batch 005

**Audit Date:** 2026-05-12
**Files audited:** 89
**Batch file:** `batch-files-005.txt`

## Classification Legend
| Field | Options |
|-------|---------|
| Action | KEEP, DELETE, REWRITE, MOVE, MOVE_TO_NIGHTLY, UNKNOWN_NEEDS_LOCAL_RUN |
| Value | P0_CRITICAL, P1_HIGH, P2_MEDIUM, P3_LOW, P4_NEGATIVE_VALUE |
| Test Type | unit, integration, end_to_end, contract, snapshot, performance_stress, source_analysis, test_utility, property_based, fake_dao_in_memory, migration |
| Confidence | HIGH, MEDIUM, LOW |

## Audit Results

| # | File | Tests | @Ignore? | Action | Value | Test Type | Confidence |
|---|------|-------|----------|--------|-------|-----------|------------|
| 1 | ScenarioSeed.kt | 0 | No | KEEP | P1_HIGH | test_utility | HIGH |
| 2 | ScenarioSeeder.kt | 0 | No | KEEP | P1_HIGH | test_utility | HIGH |
| 3 | MainActivityDeepLinkTest.kt | 2 | No | DELETE | P4_NEGATIVE_VALUE | source_analysis | HIGH |
| 4 | MainViewModelStressTest.kt | 5 | Yes (class) | MOVE_TO_NIGHTLY | P2_MEDIUM | performance_stress | HIGH |
| 5 | ContextualActionRegistryTest.kt | 7 | No | KEEP | P1_HIGH | unit | HIGH |
| 6 | NavigationRouteContractTest.kt | 13+ | No | KEEP | P0_CRITICAL | contract | HIGH |
| 7 | AddExpenseViewModelStressTest.kt | 9 | Yes (class) | MOVE_TO_NIGHTLY | P2_MEDIUM | performance_stress | HIGH |
| 8 | AddExpenseViewModelTest.kt | 4 | No | KEEP | P1_HIGH | unit | HIGH |
| 9 | AiSettingsViewModelTest.kt | 6+ | No | KEEP | P1_HIGH | unit | HIGH |
| 10 | AdvancedAnalyticsViewModelTest.kt | 2 | No | KEEP | P2_MEDIUM | unit | MEDIUM |
| 11 | AnalyticsStateStressTest.kt | 18 | Yes (class) | DELETE | P4_NEGATIVE_VALUE | performance_stress | HIGH |
| 12 | AnalyticsViewModelStressTest.kt | 17+ | Yes (class) | MOVE_TO_NIGHTLY | P1_HIGH | performance_stress | MEDIUM |
| 13 | AssistantViewModelTest.kt | 17+ | No | KEEP | P1_HIGH | unit | HIGH |
| 14 | BackupRestoreViewModelTest.kt | 6+ | No | KEEP | P1_HIGH | unit | HIGH |
| 15 | BankConnectionsViewModelTest.kt | 4 | No | DELETE | P4_NEGATIVE_VALUE | unit | HIGH |
| 16 | BudgetForecastingViewModelTest.kt | 4+ | No | KEEP | P1_HIGH | unit | HIGH |
| 17 | BudgetViewModelStressTest.kt | 21+ | Yes (class) | MOVE_TO_NIGHTLY | P1_HIGH | performance_stress | HIGH |
| 18 | CarbonFootprintScreenTest.kt | 3 | No | KEEP | P2_MEDIUM | unit | HIGH |
| 19 | CarbonFootprintViewModelTest.kt | 7+ | No | KEEP | P1_HIGH | unit | HIGH |
| 20 | CashFlowCalendarViewModelTest.kt | 15+ | No | KEEP | P1_HIGH | unit | HIGH |
| 21 | SpendingChallengesViewModelTest.kt | 3 | No | KEEP | P2_MEDIUM | unit | HIGH |
| 22 | CurrencyManagementScreenValidationTest.kt | 4 | No | KEEP | P2_MEDIUM | unit | HIGH |
| 23 | CurrencyManagementViewModelTest.kt | 5+ | No | KEEP | P1_HIGH | unit | HIGH |
| 24 | DebugScreenTextTest.kt | 5 | No | KEEP | P2_MEDIUM | unit | HIGH |
| 25 | DebugViewModelStressTest.kt | 14+ | Yes (class) | MOVE_TO_NIGHTLY | P1_HIGH | performance_stress | HIGH |
| 26 | ExportOptionsViewModelTest.kt | 7+ | No | KEEP | P1_HIGH | unit | HIGH |
| 27 | SharedExpenseGroupsScreenStateTest.kt | 2 | No | KEEP | P2_MEDIUM | unit | HIGH |
| 28 | SharedExpenseGroupsViewModelTest.kt | 15+ | No | KEEP | P1_HIGH | unit | HIGH |
| 29 | HomeScreenWidgetTest.kt | 4 | No | DELETE | P4_NEGATIVE_VALUE | source_analysis | HIGH |
| 30 | HomeViewModelRecommendationTest.kt | 23+ | No | REWRITE | P3_LOW | unit | MEDIUM |
| 31 | HomeViewModelStressTest.kt | 20+ | Yes (class) | MOVE_TO_NIGHTLY | P1_HIGH | performance_stress | HIGH |
| 32 | LifestyleInflationScreenTest.kt | 6 | No | KEEP | P2_MEDIUM | unit | HIGH |
| 33 | LifestyleInflationViewModelTest.kt | 7+ | No | KEEP | P1_HIGH | unit | HIGH |
| 34 | SpendingMapViewModelStressTest.kt | 25+ | Partial (3) | MOVE_TO_NIGHTLY | P1_HIGH | performance_stress | HIGH |
| 35 | PriceProtectionViewModelTest.kt | 8 | No | KEEP | P2_MEDIUM | unit | HIGH |
| 36 | ReceiptMatchingViewModelTest.kt | 5+ | No | KEEP | P1_HIGH | unit | HIGH |
| 37 | ReceiptScanViewModelStressTest.kt | 35+ | Yes (class) | MOVE_TO_NIGHTLY | P2_MEDIUM | performance_stress | MEDIUM |
| 38 | ManualRecurringExpenseViewModelTest.kt | 5+ | No | KEEP | P1_HIGH | unit | HIGH |
| 39 | BillRemindersViewModelTest.kt | 4 | No | KEEP | P1_HIGH | unit | HIGH |
| 40 | ReviewScreenTransactionTypeParserTest.kt | 3 | No | KEEP | P2_MEDIUM | unit | HIGH |
| 41 | ReviewScreenTransferDirectionParserTest.kt | 3 | No | KEEP | P2_MEDIUM | unit | HIGH |
| 42 | ReviewViewModelStressTest.kt | 40+ | Yes (class) | MOVE_TO_NIGHTLY | P1_HIGH | performance_stress | HIGH |
| 43 | SavingsGoalsViewModelTest.kt | 8+ | No | KEEP | P1_HIGH | unit | HIGH |
| 44 | VisualSplitEditorScreenStateTest.kt | 11 | No | KEEP | P1_HIGH | unit | HIGH |
| 45 | VisualSplitViewModelTest.kt | 4+ | No | KEEP | P1_HIGH | unit | HIGH |
| 46 | SubscriptionManagementViewModelTest.kt | 4 | No | KEEP | P1_HIGH | unit | HIGH |
| 47 | TransactionsScreenTest.kt | 1 | No | DELETE | P4_NEGATIVE_VALUE | source_analysis | HIGH |
| 48 | TransactionsViewModelStressTest.kt | 18+ | Yes (class) | MOVE_TO_NIGHTLY | P1_HIGH | performance_stress | HIGH |
| 49 | WarrantyTrackerViewModelTest.kt | 8+ | No | KEEP | P1_HIGH | unit | HIGH |
| 50 | ClipboardAmountParserTest.kt | 3 | No | KEEP | P2_MEDIUM | unit | HIGH |
| 51 | CsvExpenseImporterTest.kt | 9+ | No | KEEP | P1_HIGH | unit | HIGH |
| 52 | ExportImportRoundtripTest.kt | 5+ | No | KEEP | P1_HIGH | unit | HIGH |
| 53 | FlowTestUtils.kt | 0 | No | DELETE | P4_NEGATIVE_VALUE | test_utility | HIGH |
| 54 | HiltTestUtils.kt | 0 | No | KEEP | P1_HIGH | test_utility | HIGH |
| 55 | ViewModelTestUtils.kt | 0 | No | KEEP | P0_CRITICAL | test_utility | HIGH |
| 56 | CarbonFootprintTest.kt | 7 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 57 | CrossGroupIntegrationTest.kt | 9 | No | MOVE_TO_NIGHTLY | P1_HIGH | integration | MEDIUM |
| 58 | CrossSourceVerificationTest.kt | 6 | No | KEEP | P0_CRITICAL | contract | HIGH |
| 59 | GoldenMasterVerificationTest.kt | 22 | No | KEEP | P0_CRITICAL | snapshot | HIGH |
| 60 | LifestyleAnalysisTest.kt | 7 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 61 | SharedExpenseTest.kt | 15 | No | KEEP | P1_HIGH | unit | HIGH |
| 62 | WorkerContractTest.kt | 4 | No | KEEP | P1_HIGH | contract | HIGH |
| 63 | RecurringExpenseEngineTest.kt | 12+ | No | KEEP | P1_HIGH | unit | HIGH |
| 64 | DatabaseMigrationTest.kt | 71 | No | KEEP | P0_CRITICAL | migration | HIGH |
| 65 | MigrationContractTest.kt | 13 | No | KEEP | P1_HIGH | migration | HIGH |
| 66 | AiArtifactDaoTest.kt | 14 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 67 | AiChatMessageDaoTest.kt | 5 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 68 | AiChatSessionDaoTest.kt | 5 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 69 | BudgetDaoTest.kt | 18 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 70 | CategoryDaoTest.kt | 4 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 71 | ComplexQueryTest.kt | 18 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 72 | DaoStressTest.kt | 20 | No | KEEP | P2_MEDIUM | performance_stress | MEDIUM |
| 73 | DedupeKeyUniquenessRegressionTest.kt | 3 | No | KEEP | P0_CRITICAL | fake_dao_in_memory | HIGH |
| 74 | ExchangeRateDaoTest.kt | 4 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 75 | ExpenseDaoTest.kt | 40 | No | KEEP | P0_CRITICAL | fake_dao_in_memory | HIGH |
| 76 | ExpenseGroupDaoTest.kt | 5 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 77 | FreshInstallBatch8ParityTest.kt | 23 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 78 | FreshInstallIndexParityTest.kt | 5 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 79 | GroupMemberDaoTest.kt | 18 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 80 | MerchantLocationDaoTest.kt | 14 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 81 | MerchantNormalizationDaoTest.kt | 12 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 82 | PendingReviewDaoTest.kt | 8 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 83 | RecommendationDaoTest.kt | 4 | No | KEEP | P2_MEDIUM | fake_dao_in_memory | HIGH |
| 84 | RecurringExpenseDaoTest.kt | 7 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 85 | SavingsGoalDaoTest.kt | 16 | No | KEEP | P0_CRITICAL | fake_dao_in_memory | HIGH |
| 86 | ScannedReceiptDaoTest.kt | 7 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 87 | UserCorrectionDaoTest.kt | 16 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 88 | WarrantyDaoTest.kt | 4 | No | KEEP | P1_HIGH | fake_dao_in_memory | HIGH |
| 89 | MerchantKeyBackfillWorkerTest.kt | 3 | No | KEEP | P2_MEDIUM | unit | HIGH |

---

## Summary

| Metric | Count |
|--------|-------|
| Total files | 89 |
| KEEP | 68 |
| DELETE | 6 |
| REWRITE | 1 |
| MOVE_TO_NIGHTLY | 12 |
| UNKNOWN_NEEDS_LOCAL_RUN | 0 |
| P0_CRITICAL | 9 |
| P1_HIGH | 55 |
| P2_MEDIUM | 15 |
| P3_LOW | 1 |
| P4_NEGATIVE_VALUE | 6 |

### Test Type Distribution
| Type | Count |
|------|-------|
| unit | 44 |
| fake_dao_in_memory | 22 |
| performance_stress | 12 |
| test_utility | 5 |
| contract | 3 |
| migration | 2 |
| source_analysis | 3 |
| snapshot | 1 |
| integration | 1 |

---

## Detailed Notes for Non-KEEP Items

### DELETE (6 files)

#### 1. MainActivityDeepLinkTest.kt — P4_NEGATIVE_VALUE
- **Why:** Reads 2 production source files from disk (`AndroidNotificationService.kt`, `MainActivity.kt`) and asserts they contain specific string literals (e.g., `"expensetracker://activity?expenseId="`). Breaks on ANY refactoring, variable rename, or formatting change. Tests source code string content, not behavior.
- **Impact of removal:** Zero risk. Deep link/dispatch contracts should be validated via Compose node testing or integration tests.

#### 2. AnalyticsStateStressTest.kt — P4_NEGATIVE_VALUE
- **Why:** 18 tests that assert default values of a data class (`AnalyticsState`). No ViewModel, no async, no computation — `assertEquals(0.0, state.currentTotal, 0.0)` type assertions. Effectively testing the Kotlin compiler.
- **Impact of removal:** None. The data class is implicitly tested whenever any other test creates it.

#### 3. BankConnectionsViewModelTest.kt — P4_NEGATIVE_VALUE
- **Why:** `BankConnectionsViewModel` has NO injected dependencies (no repository, no use case). All methods are empty stubs. Tests verify that empty state stays empty — testing a class that does nothing.
- **Impact of removal:** None. When the ViewModel is actually implemented, new proper tests should be written.

#### 4. HomeScreenWidgetTest.kt — P4_NEGATIVE_VALUE
- **Why:** Reads `HomeScreen.kt` from disk and asserts 20+ widget dispatch `is DashboardWidget.X ->` branches exist as string literals. Fragile source-code grep masquerading as a test.
- **Impact of removal:** Replace with proper Compose UI tests or compile-time checks.

#### 5. TransactionsScreenTest.kt — P4_NEGATIVE_VALUE
- **Why:** Single test reading `TransactionsScreen.kt` and checking for 3 string patterns. Same fragility.
- **Impact of removal:** None.

#### 6. FlowTestUtils.kt — P4_NEGATIVE_VALUE
- **Why:** Empty object with just KDoc comments. 8 lines. Dead documentation placeholder code.
- **Impact of removal:** None.

### REWRITE (1 file)

#### 1. HomeViewModelRecommendationTest.kt — P3_LOW
- **Why:** 501 lines, but NEVER instantiates `HomeViewModel`. Tests `RecommendationStateManager` StateFlow emissions and `RecommendationDismissalHandler` mock invocations. Tests mockk behavior more than production code. No HomeViewModel at all despite class name.
- **Rewriting approach:** (a) Integrate into a proper `HomeViewModel` integration test with end-to-end recommendation pipeline, or (b) rewrite as focused tests on `RecommendationStateManager` directly without mocks.

### MOVE_TO_NIGHTLY (12 files)

These are all currently `@Ignore("Stress test: may hang in CI, run manually")` annotated classes or methods. They should move to a dedicated nightly suite with relaxed timeouts:

1. **MainViewModelStressTest.kt** — 5 tests, MainViewModel navigation with Turbine
2. **AddExpenseViewModelStressTest.kt** — 9 tests, expense form flow, validation, save
3. **AnalyticsViewModelStressTest.kt** — 17+ tests, full analytics pipeline (18 mock deps)
4. **BudgetViewModelStressTest.kt** — 21+ tests, budget CRUD validation with autopilot
5. **DebugViewModelStressTest.kt** — 14+ tests, debug dashboard with 20+ mock deps
6. **HomeViewModelStressTest.kt** — 20+ tests, dashboard widgets and AI briefing
7. **SpendingMapViewModelStressTest.kt** — 3 of 25+ tests @Ignore; keep active tests in CI, move ignored ones
8. **ReceiptScanViewModelStressTest.kt** — 35+ tests, receipt scanning flow (Robolectric, 851 lines)
9. **ReviewViewModelStressTest.kt** — 40+ tests, review queue approval/rejection (1323 lines)
10. **TransactionsViewModelStressTest.kt** — 18+ tests, transaction browsing and filtering
11. **CrossGroupIntegrationTest.kt** — 9 tests, cross-domain analytics integration (806 lines)


---

## P0_CRITICAL Files (9) — Expanded Notes

| # | File | Why Critical |
|---|------|-------------|
| 6 | NavigationRouteContractTest.kt | Serialisation contract for ALL NavigationDestination variants; round-trip edge cases |
| 55 | ViewModelTestUtils.kt | Foundation base class for 40+ ViewModel tests; sets Main dispatcher to StandardTestDispatcher |
| 58 | CrossSourceVerificationTest.kt | Proves analytics totals are consistent across repository, InsightsEngine, AdvancedAnalyticsEngine, and Dashboard engine |
| 59 | GoldenMasterVerificationTest.kt | Deterministic snapshot testing with known datasets (1001 lines, 22 tests); validates exact numerical outputs |
| 64 | DatabaseMigrationTest.kt | Covers ALL database migrations v1->v115 (71 tests, 3822 lines); Room MigrationTestHelper with data integrity |
| 73 | DedupeKeyUniquenessRegressionTest.kt | Only test exercising the real SQLite dedupeKey unique index; proves PURCHASE/DEPOSIT type-safe keys and same-type race rejection |
| 75 | ExpenseDaoTest.kt | Comprehensive DAO test (40 tests, 970 lines): insert/delete, duplicate detection, uncapped queries, atomic insert, paging |
| 85 | SavingsGoalDaoTest.kt | Atomic increment validation (`SET currentAmount = currentAmount + :delta`) via real Room SQL; stacked/concurrent/negative |
| 61 | SharedExpenseTest.kt | Split calculation and settlement optimization with deterministic balance assertions; equal/percentage/custom/empty/rounding |

---

## Most Underrated / Hidden Gems

| File | Tests | Why Valuable |
|------|-------|-------------|
| VisualSplitEditorScreenStateTest.kt | 11 | Excellent state machine edge-case testing (trailing dots, NaN, Infinity, locale comma, re-typing) |
| DedupeKeyUniquenessRegressionTest.kt | 3 | Only test using real SQLite unique index; catches type-level dedup regression |
| FreshInstallBatch8ParityTest.kt | 23 | Validates CHECK constraints and FK semantics on fresh-install databases |
| FreshInstallIndexParityTest.kt | 5 | Catches schema drift between fresh and migrated databases (index parity) |
| ScenarioSeeder.kt | N/A | Utility enabling scenario-based DB testing (seedState + feedInputs) |

---

## Risk Assessment

| Risk | Files Affected | Mitigation |
|------|---------------|------------|
| DELETE removes coverage | 3 source-analysis files, 1 stub VM test, 1 dead utility, 1 data-class test | Zero production risk; all test source code string content or trivialities |
| REWRITE needed before refactor | HomeViewModelRecommendationTest.kt | Tests mock behavior, not production; HomeViewModel not instantiated |
| MOVE_TO_NIGHTLY reduces CI | 12 files currently @Ignore in CI | Nightly suite recovers coverage at lower CI cost |
| androidTest separation | 26 files in app/src/androidTest/ | Must remain; requires real Android framework (Room in-memory, workers) |

---

## File Count by Directory

| Directory | Files | Actions |
|-----------|-------|---------|
| testfixtures/scenario | 2 | Both KEEP (utilities) |
| ui/ | 2 | 1 DELETE, 1 MOVE_TO_NIGHTLY |
| ui/components/emptystate | 1 | KEEP |
| ui/navigation | 1 | KEEP (P0) |
| ui/screens/addexpense | 2 | 1 MOVE_TO_NIGHTLY, 1 KEEP |
| ui/screens/aisettings | 1 | KEEP |
| ui/screens/analytics | 3 | 2 MOVE_TO_NIGHTLY, 1 DELETE |
| ui/screens/assistant | 1 | KEEP |
| ui/screens/backup | 1 | KEEP |
| ui/screens/bank | 1 | DELETE |
| ui/screens/budget | 2 | 1 MOVE_TO_NIGHTLY, 1 KEEP |
| ui/screens/carbon | 2 | Both KEEP |
| ui/screens/cashflow | 1 | KEEP |
| ui/screens/challenge | 1 | KEEP |
| ui/screens/currency | 2 | Both KEEP |
| ui/screens/debug | 2 | 1 MOVE_TO_NIGHTLY, 1 KEEP |
| ui/screens/export | 1 | KEEP |
| ui/screens/groups | 2 | Both KEEP |
| ui/screens/home | 3 | 1 MOVE_TO_NIGHTLY, 1 DELETE, 1 REWRITE |
| ui/screens/lifestyle | 2 | Both KEEP |
| ui/screens/map | 1 | MOVE_TO_NIGHTLY (partial) |
| ui/screens/price | 1 | KEEP |
| ui/screens/receiptmatching | 1 | KEEP |
| ui/screens/receiptscan | 1 | MOVE_TO_NIGHTLY |
| ui/screens/recurringmanual | 1 | KEEP |
| ui/screens/reminder | 1 | KEEP |
| ui/screens/review | 3 | 1 MOVE_TO_NIGHTLY, 2 KEEP |
| ui/screens/savings | 1 | KEEP |
| ui/screens/split | 2 | Both KEEP |
| ui/screens/subscription | 1 | KEEP |
| ui/screens/transactions | 2 | 1 MOVE_TO_NIGHTLY, 1 DELETE |
| ui/screens/warranty | 1 | KEEP |
| ui/util | 1 | KEEP |
| util | 5 | 1 DELETE, 4 KEEP |
| verification | 6 | 1 MOVE_TO_NIGHTLY, 5 KEEP |
| workers | 1 | KEEP |
| domain/logic | 1 | KEEP |
| data/database | 2 | Both KEEP (androidTest) |
| data/database/dao | 22 | All KEEP (androidTest) |
| data/location | 1 | KEEP (androidTest) |
