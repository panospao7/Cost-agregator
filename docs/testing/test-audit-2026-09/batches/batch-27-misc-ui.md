# Batch 27 — misc-ui

Scope: ui/ ViewModel + navigation + screen-helper tests (Segments 1/2/8/9/10/13/14/16/18/19/20/22/24/27/28/29/37 UI layer). Files: 38 · LOC: 8,883
Reviewer notes: All 7 ViewModel stress files re-verified `@Ignore` (6 class-level; SpendingMap has 3 method-level `@Ignore` + a second active class `SpendingMapHeatmapFilterTest` in the same file). The three prior-audit SRCTEXT offenders (MainActivityDeepLinkTest, HomeScreenWidgetTest, TransactionsScreenTest) are DELETED from the tree — prior P4 verdicts executed; also AnalyticsStateStressTest (prior DELETE) is gone. No SRCTEXT anti-pattern remains in this batch (DashboardWidgetRenderCoverageTest uses sealed-class reflection, not source-text). BankConnectionsViewModelTest does NOT set the Main dispatcher and its VM uses `viewModelScope` — likely red at runtime (ui.* not yet measured in TEST_FAILURE_LEDGER). FRAGILE constructor coupling is heavy: AnalyticsViewModel (18 ctor args in 3 files), HomeViewModel (20), DebugViewModel (16), SpendingMapViewModel (12) — direct evidence of the "refactor breaks ~100 tests" pain. No file appears in failure families F-01…F-21 (those are domain/data; ui.* not yet run).

## Verdict summary

| KEEP | STRENGTHEN | MERGE | REWRITE | DELETE | NIGHTLY | UNKNOWN |
|---|---|---|---|---|---|---|
| 28 | 2 | 1 | 1 | 0 | 6 | 0 |

## Per-file table

| # | File (app/src/…) | LOC | Tests | @Ign | Style | Class(es) under test | Verdict | Pri | Overlap | Flags/Note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | test/…/ui/MainViewModelStressTest.kt | 79 | 5 | 5 | VIEWMODEL | MainViewModel | NIGHTLY | P3 | — | class @Ignore; 1 tautology test |
| 2 | test/…/ui/components/emptystate/ContextualActionRegistryTest.kt | 137 | 7 | 0 | PURE | ContextualActionRegistry | KEEP | P2 | EmptyStateRegistryCompletenessTest | real state/Flow assertions |
| 3 | test/…/ui/components/emptystate/EmptyStateRegistryCompletenessTest.kt | 70 | 2 | 0 | PURE | DefaultEmptyStateRegistryInitializer | KEEP | P2 | ContextualActionRegistryTest | completeness contract |
| 4 | test/…/ui/navigation/DeepLinkParserTest.kt | 96 | 12 | 0 | ROBOLECTRIC | parseDeepLink/DeepLinkDecision | KEEP | P1 | — | real parser; confirmation gating |
| 5 | test/…/ui/navigation/DestinationPersistencePolicyTest.kt | 91 | 4 | 0 | PURE | NavigationDestination.persistencePolicy | KEEP | P2 | NavigationRouteContractTest | samples only some destinations |
| 6 | test/…/ui/navigation/FeatureConfigNavigationContractTest.kt | 94 | 7 | 0 | PURE | FeatureConfig | KEEP | P2 | NavigationRouteContractTest | uniqueness + roundtrip |
| 7 | test/…/ui/navigation/NavigationControllerBehaviorTest.kt | 161 | 12 | 0 | PURE | NavigationController | KEEP | P1 | — | back-stack semantics, no Android |
| 8 | test/…/ui/navigation/NavigationRouteContractTest.kt | 420 | 23 | 0 | PURE | NavigationDestination toSaveToken | KEEP | P0 | DestinationPersistencePolicyTest | full roundtrip + legacy format |
| 9 | test/…/ui/screens/addexpense/AddExpenseViewModelStressTest.kt | 146 | 8 | 8 | VIEWMODEL | AddExpenseViewModel | NIGHTLY | P3 | AddExpenseViewModelTest (partial DUP) | class @Ignore; weaker duplicate |
| 10 | test/…/ui/screens/addexpense/AddExpenseViewModelTest.kt | 129 | 4 | 0 | VIEWMODEL | AddExpenseViewModel | KEEP | P1 | #9 | debounce-cancel, prefill-once |
| 11 | test/…/ui/screens/aisettings/AiSettingsViewModelTest.kt | 197 | 7 | 0 | VIEWMODEL | AiSettingsViewModel | KEEP | P0 | — | API key stored only after test OK |
| 12 | test/…/ui/screens/analytics/AdvancedAnalyticsViewModelTest.kt | 79 | 2 | 0 | VIEWMODEL | AdvancedAnalyticsViewModel | KEEP | P1 | #14/#15/#16 | currency-change reload |
| 13 | test/…/ui/screens/analytics/AnalyticsStateMoneySafetyTest.kt | 60 | 5 | 0 | PURE | AnalyticsState money fallback | KEEP | P1 | — | null/invalid currency fail-safe |
| 14 | test/…/ui/screens/analytics/AnalyticsViewModelInsightsTest.kt | 632 | 6 | 0 | VIEWMODEL | AnalyticsViewModel, InsightsEngine | KEEP | P1 | #12,#15,#16; TimePeriodAnalyticsAlignmentTest | FRAGILE 18 ctor args |
| 15 | test/…/ui/screens/analytics/AnalyticsViewModelStressTest.kt | 282 | 8 | 8 | VIEWMODEL | AnalyticsViewModel | NIGHTLY | P3 | #12,#14,#16 | class @Ignore; mostly no-crash |
| 16 | test/…/ui/screens/analytics/BudgetVsActualFxBasisTest.kt | 199 | 1 | 0 | VIEWMODEL | AnalyticsViewModel, CurrencyConverter | KEEP | P0 | #14 | FRAGILE 18 args; convertAsOf contract |
| 17 | test/…/ui/screens/assistant/AssistantViewModelTest.kt | 806 | 26 | 0 | VIEWMODEL | AssistantViewModel | KEEP | P0 | — | FRAGILE: reflection into privates |
| 18 | test/…/ui/screens/backup/BackupRestoreViewModelTest.kt | 315 | 12 | 0 | VIEWMODEL | BackupRestoreViewModel | KEEP | P0 | — | preflight, maintenance-mode exit |
| 19 | test/…/ui/screens/bank/BankConnectionsViewModelTest.kt | 69 | 4 | 0 | MOCKED | BankConnectionsViewModel | STRENGTHEN | P3 | — | likely red: no Main dispatcher |
| 20 | test/…/ui/screens/budget/BudgetForecastingViewModelTest.kt | 289 | 6 | 0 | VIEWMODEL | BudgetForecastingViewModel | KEEP | P1 | — | Turbine state machine |
| 21 | test/…/ui/screens/budget/BudgetViewModelStressTest.kt | 303 | 15 | 15 | VIEWMODEL | BudgetViewModel | NIGHTLY | P3 | — | class @Ignore; many no-assert tests |
| 22 | test/…/ui/screens/carbon/CarbonFootprintScreenTest.kt | 40 | 3 | 0 | PURE | resolveCarbonFootprintContentState | KEEP | P2 | CarbonFootprintViewModelTest | content-state resolver |
| 23 | test/…/ui/screens/carbon/CarbonFootprintViewModelTest.kt | 253 | 8 | 0 | VIEWMODEL | CarbonFootprintViewModel | KEEP | P1 | #22 | stale-result-wins race |
| 24 | test/…/ui/screens/cashflow/CashFlowCalendarViewModelTest.kt | 419 | 10 | 0 | VIEWMODEL | CashFlowCalendarViewModel | KEEP | P0 | — | DBG-01 currency race; partial flag |
| 25 | test/…/ui/screens/challenge/SpendingChallengesViewModelTest.kt | 128 | 3 | 0 | VIEWMODEL | SpendingChallengesViewModel | KEEP | P2 | — | canonical-source availability |
| 26 | test/…/ui/screens/currency/CurrencyManagementScreenValidationTest.kt | 44 | 4 | 0 | PURE | isAmountParseableAndPositive etc | KEEP | P2 | — | input validation helpers |
| 27 | test/…/ui/screens/currency/CurrencyManagementViewModelTest.kt | 185 | 4 | 0 | VIEWMODEL | CurrencyManagementViewModel | KEEP | P1 | — | conversion + refresh states |
| 28 | test/…/ui/screens/debug/DebugViewModelStressTest.kt | 254 | 7 | 7 | VIEWMODEL | DebugViewModel | NIGHTLY | P3 | — | class @Ignore; FRAGILE 16 deps |
| 29 | test/…/ui/screens/export/ExportOptionsViewModelTest.kt | 465 | 15 | 0 | VIEWMODEL | ExportOptionsViewModel | KEEP | P0 | ExportReadBarrierTest (complementary) | real privacy gate; fail-closed enc |
| 30 | test/…/ui/screens/groups/SharedExpenseGroupsScreenStateTest.kt | 26 | 2 | 0 | PURE | isSettledBalance/rounding helpers | KEEP | P2 | — | money rounding semantics |
| 31 | test/…/ui/screens/groups/SharedExpenseGroupsViewModelTest.kt | 463 | 7 | 0 | VIEWMODEL | SharedExpenseGroupsViewModel | KEEP | P1 | — | asserts atomic use case path |
| 32 | test/…/ui/screens/home/DashboardWidgetMetaContractTest.kt | 41 | 2 | 0 | PURE | HomeViewModel.getWidgetId | STRENGTHEN | P3 | #35; metrics/DashboardWidgetConsistencyTest | hardcoded ID list, 4 widgets only |
| 33 | test/…/ui/screens/home/DashboardWidgetRenderCoverageTest.kt | 79 | 3 | 0 | PURE | DashboardWidget sealed subclasses | KEEP | P2 | metrics/DashboardWidgetConsistencyTest | reflection coverage, not SRCTEXT |
| 34 | test/…/ui/screens/home/HomeViewModelRecommendationTest.kt | 501 | 24 | 0 | MOCKED | (none — mocks only) | REWRITE | P3 | #35 | still never instantiates HomeViewModel |
| 35 | test/…/ui/screens/home/HomeViewModelStressTest.kt | 522 | 19 | 19 | VIEWMODEL | HomeViewModel | NIGHTLY | P3 | #32,#34 | class @Ignore; FRAGILE 20 args |
| 36 | test/…/ui/screens/lifestyle/LifestyleInflationScreenTest.kt | 107 | 6 | 0 | PURE | trend weights/content state | KEEP | P2 | #37 | bar-weight math incl. clamping |
| 37 | test/…/ui/screens/lifestyle/LifestyleInflationViewModelTest.kt | 231 | 7 | 0 | VIEWMODEL | LifestyleInflationViewModel | KEEP | P2 | #36 | stale-result-wins race |
| 38 | test/…/ui/screens/map/SpendingMapViewModelStressTest.kt | 471 | 10 | 3 | VIEWMODEL | SpendingMapViewModel | MERGE | P2 | — | intra-file DUP; unbounded awaitUntil |

## Findings (noteworthy files only)

### test/…/ui/screens/home/HomeViewModelRecommendationTest.kt
- Prior REWRITE verdict re-confirmed against current source: 501 lines / 24 tests and `HomeViewModel` is still never constructed (grep: only `HomeViewModelStressTest` and `DashboardWidgetMetaContractTest` touch it).
- Tests are mock tautologies: stubs a mock, calls the mock, verifies the call (e.g. `init loads recommendations for default user` at lines 354-360 calls `recommendationStateManager.refreshForUser(...)` then `verify` it); others assert stdlib `MutableStateFlow` semantics on a test-local flow (lines 63-140, 383-404) — the MASTER_TESTING_STRATEGY "mock addiction" archetype.
- The navigation resolver "tests" (lines 219-349) stub `navigationTargetResolver.resolve(...)` and assert the stub's return — production `NavigationTargetResolver` never executes.
- Action: REWRITE against real `RecommendationStateManager`/`NavigationTargetResolver`/`HomeViewModel`, or DELETE and rely on a future active HomeViewModel test. P3.

### test/…/ui/screens/bank/BankConnectionsViewModelTest.kt
- Prior-audit DELETE (P4 "stub VM") is OVERTURNED: production `BankConnectionsViewModel` now takes `BankConnectionLifecycleCoordinator` and holds real state (`app/src/main/.../bank/BankConnectionsViewModel.kt:17-34`), and tests 1-2 assert real `connections` state.
- Likely red statically: the test neither extends `ViewModelTestUtils` nor calls `Dispatchers.setMain`, but the VM uses `viewModelScope` (Main dispatcher) in `init` — first construction should throw `IllegalStateException` on JVM. Test 1 also asserts state without advancing the dispatcher.
- sync/disconnect tests are verify-only and `isLoading`/error paths (`catch` → empty list) are untested.
- Action: add dispatcher setup, advance scheduler, assert state after sync failure; P3.

### test/…/ui/screens/map/SpendingMapViewModelStressTest.kt
- Two test classes in one file: `SpendingMapViewModelStressTest` (8 tests, 3 `@Ignore`d) and `SpendingMapHeatmapFilterTest` (2 active tests). The heatmap PURCHASE-only tests are duplicated nearly verbatim across the two classes (lines 111-153 vs 381-413; lines 156-194 vs 416-442) → MERGE into the focused class, move the remainder NIGHTLY.
- Isolation hazard in ACTIVE tests: init work runs on real `Dispatchers.IO` with an unbounded `awaitUntil` Turbine loop (lines 316-323, 446-453) — no virtual time, potential hang (the same reason 3 siblings are `@Ignore`d).
- Positive: real `SpendingHeatmapEngine` money check — deposits/transfers excluded from heatmap spend total (80.0 vs raw 1,600+, lines 197-236).
- 12 ctor-arg coupling → FRAGILE.

### test/…/ui/screens/home/DashboardWidgetMetaContractTest.kt
- Contract drift risk: `defaultConfigIds` (lines 13-21) hardcodes the expected widget IDs instead of reading `DashboardRepository.getDefaultConfig()`, so it cannot catch drift between `getWidgetId()` and the real config; only 4 of 21 widget types are sampled.
- Partial DUP with `HomeViewModelStressTest` getWidgetId tests (lines 438-484).
- Action: STRENGTHEN — assert against the production default config list.

### test/…/ui/screens/assistant/AssistantViewModelTest.kt (positive highlight)
- Strong privacy coverage per AGENTS.md rules: diagnostics must not contain user query/card fragments (lines 247-283), failure path surfaces fixed message + controlled reason code with no raw exception text (lines 382-413), history `payloadJson` stripped when `PrivacySettings.redactBeforeCloud=true` even when AI setting is off (lines 485-544) — privacy fail-closed verified.
- FRAGILE: reflection into `_currentQueryJob`, `_isSubmitting`, `_uiState` private fields (lines 758-777) — renames break these silently at runtime.

### test/…/ui/screens/export/ExportOptionsViewModelTest.kt (positive highlight)
- P0-grade: real `CompositePrivacyGate`/`ExportPrivacyGate` routing proves EXPENSE_EXPORT capability (P12-REG-01, lines 328-361); encrypted export fails closed on blank passphrase and never leaves plaintext (lines 366-434); CSV formula-injection neutralization (lines 141-156); JSON escaping + schemaVersion (lines 165-191). Complements `ExportReadBarrierTest` (data/backup).

### test/…/ui/screens/cashflow/CashFlowCalendarViewModelTest.kt (positive highlight)
- DBG-01 tests cover the cold-start home-currency race (guard, then recovery reload) and runtime currency change without stale-currency mismatch (lines 266-345) — money/currency correctness; plus occurrence-generation-failure `isPartial` surfacing (lines 197-223).

### AnalyticsViewModel trio (files 14, 15, 16)
- All three construct `AnalyticsViewModel` with 18 positional constructor args (InsightsTest lines 96-115, FxBasis lines 107-126, Stress lines 127-146) — the single biggest FRAGILE cluster in the batch; any ctor change breaks 3 files.
- `BudgetVsActualFxBasisTest` is the only test pinning `convertAsOf` (period-end rate) instead of latest-rate `convert` for budget limits — P0 money invariant, keep.
- `AnalyticsViewModelStressTest` (class `@Ignore`) is mostly assertNotNull/no-crash; its one valuable test (month totals + YoY, lines 213-272) should be promoted to an active file.

### Stress @Ignore re-verification (files 1, 9, 15, 21, 28, 35, 38)
- Confirmed current form: 6 files class-level `@Ignore("Stress test: may hang in CI, run manually")`; SpendingMap has 3 method-level `@Ignore`s. Matches TEST_IGNORE_CLASSIFICATION (NIGHTLY class) — still accurate; nightly workflow still missing.
- Coverage consequence: BudgetViewModel (#21) and HomeViewModel (#35) have NO other instantiating tests, so these ViewModels currently have zero CI coverage. MainViewModel likewise only covered by its ignored stress file.

### Prior-audit SRCTEXT offenders (batch guidance)
- `ui/MainActivityDeepLinkTest.kt`, `ui/screens/home/HomeScreenWidgetTest.kt`, `ui/screens/transactions/TransactionsScreenTest.kt`: all three files NO LONGER EXIST under app/src — prior DELETE/P4 verdicts were executed; deep-link behavior is now covered behaviorally by `DeepLinkParserTest` + `NavigationRouteContractTest`. No replacement SRCTEXT offenders found in this batch.

## Area gaps (what is NOT tested in this area)

- BudgetViewModel: no active (non-`@Ignore`) test at all — CRUD/validation logic unverified in CI.
- HomeViewModel: no active test instantiates it — widget reorder/visibility, planned-expense add, and briefing wiring only covered inside the ignored stress file.
- AddExpenseViewModel: no active test asserts the save path routes through the legal expense-creation path (lifecycle coordinator); the success-path assertion exists only in the ignored stress file via `ManualExpenseRepository` mock.
- BankConnectionsViewModel: `isLoading`, sync/disconnect failure (`RetryableFailure`, exception) and the `catch` → empty-list fallback untested; file likely failing (missing Main dispatcher).
- DestinationPersistencePolicyTest/FeatureConfigNavigationContractTest sample subsets; no exhaustive per-destination persistence-policy enumeration (NavigationRouteContractTest covers roundtrip but not policy per variant).
- NavigationController edge cases untested: state-restoration token → destination application, deep-link `RequireConfirmation` flow into the controller.
- Widget config persistence roundtrip (configFlow → saveDashboardConfigSync → reload ordering/dedup) only partially exercised in ignored Home stress tests.
- DebugViewModel destructive actions (`clearAll`, `resetExpenses`) verified only by delegation mocks in an ignored file; no barrier/maintenance-mode interaction test at UI level.

## Rollup

- Verdicts: KEEP 28 · STRENGTHEN 2 · MERGE 1 · REWRITE 1 · DELETE 0 · NIGHTLY 6 · UNKNOWN 0
- Priorities: P0 7 (files 8, 11, 16, 17, 18, 24, 29) · P1 10 · P2 12 · P3 9 · P4 0
- DUP pairs: 1 full (SpendingMapHeatmapFilterTest vs stress-class heatmap tests, same file); 2 partial (AddExpenseViewModelStressTest vs AddExpenseViewModelTest; HomeViewModelStressTest getWidgetId vs DashboardWidgetMetaContractTest)
- FRAGILE count: 9 files (14, 15, 16, 17, 21, 28, 31, 35, 38) — ctor-arg peaks: AnalyticsViewModel 18, HomeViewModel 20, DebugViewModel 16, SpendingMapViewModel 12; reflection coupling in 17 and 31
- @Ignored tests: 65 methods across 7 files (62 in class-ignored files + 3 method-level in SpendingMap)
- Prior-verdict changes: 3 SRCTEXT DELETEs confirmed executed (files gone); BankConnectionsViewModelTest DELETE overturned → STRENGTHEN; HomeViewModelRecommendationTest REWRITE re-confirmed
- Not decidable statically: actual pass/fail of BankConnectionsViewModelTest and the 7 stress files (ui.* batch not yet measured per TEST_FAILURE_LEDGER); recommend a focused `:app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.*"` run when Gradle ownership allows
