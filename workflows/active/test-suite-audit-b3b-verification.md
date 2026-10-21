# B3b re-verification (scout, 2026-10-17, worktree Cost-agregator-tests, read-only)

T = app/src/test/java/com/yourname/expensetracker, M = app/src/main/java/com/yourname/expensetracker.
All cited files exist. No B3b file is on the no-touch list (NotificationRepository/ReviewQueueRepository/NotificationIntake* appear only as mocks/comments). B3b-6 and B3-1b stay blocked.

## Cross-cutting causes
- Home hang root cause: M/ui/screens/home/HomeViewModel.kt:205-220 init runs `while(true){ delay(nextMidnight-now); reloadDashboard() }`; tests stub timeProvider.now()=0 so `advanceUntilIdle()` never idles → use runCurrent()/advanceTimeBy.
- Non-relaxed `currencySettingsRepository = mockk()` throws at construction (homeCurrency() called in init): Home (HomeViewModel.kt:161), Transactions (TransactionsViewModel.kt:202), AddExpense (AddExpenseViewModel.kt:92-98) → relaxed + `homeCurrency()` returns flowOf("EUR").
- Transactions and map VMs hop to Dispatchers.IO → wait with `isLoading.first{!it}` / turbine awaits, not advanceUntilIdle.

## B3b-1 HomeViewModelStressTest → HomeViewModelWidgetsTest
- @Ignore :52; 19 tests (:196 :206 :215 :227 :298 :315 :323 :331 :339 :350 :357 :368 :381 :394 :407 :420 :438 :444 :491).
- REVIVE-WITH-FIX (widgets, planned-expense, cloud): relaxed currency mock; `saveDashboardConfigSync` stubs → coEvery/coVerify suspend `saveDashboardConfig` (HomeViewModel.kt:458,:470; DashboardRepository.kt:81) with configFlow updated in answers; moveWidget (:436-461) reorders RENDERED widgets (`dashboard.value.widgets` :442) → stub compute with real widgets + collect `dashboard` (WhileSubscribed :408) first — only proof of widget reorder; cloud test :227 still matches (AiArtifactPresentation.kt:25-32; HomeViewModel.kt:288).
- DELETE trivial: :196 :206 :298 :491 (assertNotNull only); getWidgetId :438/:444 → one table test (HomeViewModel.kt:892 → DashboardWidgetRegistry.idFor).
- HomeViewModelCurrencyReloadTest.kt:56 @Ignore is NOT in scope; stays.
- Rule: if hang persists after 1 debug iteration → DELETE and record reason.

## B3b-2 ReviewViewModelStressTest → ReviewViewModelAiAssistTest
- @Ignore :53; 36 tests.
- REVIVE-AS-IS: :375 :384 :443 :501 :537 :574 (loadAiExplanation :318-387), :634 :705 :768 :838 (assist diagnostics :579-754), :1268 :1304 (dismissArtifact :1199).
- REVIVE-WITH-FIX: :901 applyCategorySuggestion → assert uiEvent OpenEditWithPrefill and markApplied(10L) exactly 0 (ReviewViewModel.kt:756-768), stub allCategories id 7 and collect categories; quick-approve :958 :1011 :1104 :1197 → dedupe Ready LIKELY_DISTINCT (S6-010), suggestedCurrency "EUR", allCategories with id 4 collected, keep advanceUntilIdle (Eagerly :211-213); :193 drop `coVerify(exactly=0) getReviewById(90L)` (prod fetches original first :467), keep short-circuit asserts.
- DELETE trivial: :136 :142 :148 :154 :164 :282 :295 :304 :328 :334 :344 :350 :360 :369 :1344. Keep :173/:183, :230.

## B3b-3 Transactions + AddExpense
- TransactionsViewModelStressTest @Ignore :35; revive paging :158 :182 :204 :264 :304 into TransactionsViewModelPagingTest; relaxed currency mock (pattern TransactionsViewModelTest.kt:69); waits via CompletableDeferred + `isLoading.first{!it}` (TransactionsViewModelTest.kt:241-257); turbine isLoading sequences → `first{}`. Behaviours: loadMore :443-474, PAGE_SIZE=50 :55, applyFilter :816-829, updateMerchant refresh :517-531/:833-848, clearFilter :375. Rest trivial → delete.
- AddExpenseViewModelStressTest @Ignore :22; revive :76 (merchantError :287), :85 (amountError :295), :95 (valid save → Success; 21-arg addManualExpense ManualExpenseRepository.kt:86-108), :137 (reset :457-464) into AddExpenseViewModelTest (EUR fixture :40-55); pin Locale.US. :130 trivial.

## B3b-4 Analytics / Main / Debug / SpendingMap
- AnalyticsViewModelStressTest @Ignore :29; ONE yoy test :213 (plan said two): stub `analyticsInputAssembler.build(any<List<Expense>>(), any<String>(), any(), any())` to echo real NormalizedAnalyticsInput (AnalyticsViewModelInsightsTest.kt:443-445; e2e/FlowPipelineTestHarness.kt:190); fixed clock (FakeTimeProvider). Fix stale stubs (getBudgetStatuses→allBudgets, getPatterns→getPatternsFromSnapshots, compute→computeNormalized). :204 keep; rest trivial.
- MainViewModelStressTest @Ignore :20: revive :41 :51 :65 (MainViewModel.kt:46-82); :35 optional; :76 delete.
- DebugViewModelStressTest @Ignore :46: delete :213 :241 (plan); revive :153 :170 :183; :224 needs context.getString stubs else delete.
  **:192 clearAll — DECISION (orchestrator, standing approval): DELETE.** It verifies `NotificationRepository.deleteAll()` which is `@Deprecated(level=ERROR)` "Dangerous … deletes ALL expenses" (NotificationRepository.kt:249-253); reviving would make a test depend on a deprecated unsafe API (CLAUDE.md stop-list) and it is verify-only.
- SpendingMapViewModelStressTest: method @Ignore :85/:98/:106 → revive (:85 via `awaitUntil{!it.isLoading}` helper :326); merge PURCHASE-only heatmap duplicates (:117 :162 :203 :245 :286 vs SpendingMapHeatmapFilterTest) into one table.

## B3b-5 CompositeGeocodingServiceStressTest
- @Ignore :26; REVIVE-AS-IS all 8 (:58 :72 :89 :104 :116 :130 :141 :151) into CompositeGeocodingServiceTest (same setup; isReturnDefaultValues=true app/build.gradle.kts:203). Only tests of: Nominatim search :63-117, simple/complex gating :147-185/:247, qualifier ranking :213-228, 50 m dedupe :231/:336, min window 10 :234, safeSearch :401-412, CE rethrow :406.

## B3b-7 ReceiptRepositoryStressTest → ReceiptRepositoryOcrFallbackTest
- @Ignore :52, Robolectric sdk 28; 12 tests.
- MOVE (fixed): :165 OCR failure → manual record (ReceiptRepository.kt:217-238; rawOcrText now "[OCR Failed or Skipped]" :228, OCR_FAILED :232; draft-first so scannedReceiptDao insert/update exactly=0; stub `assetStore.computeUriHash` → Result.failure, timeProvider.now, ioDispatcher Unconfined as ReceiptRepositoryBatchDuplicateTest.kt:72-97); :204 saveManualReceiptRecord (:323-338); :182 :365 PARSE_FAILED draft (:247-277; stub `parse(any(), any())`, resolveHomeCurrency :437-440, privacy rawOcrStorageMode :126); :121 success draft (:280-314).
- ADD: OcrRecognitionFailedException(savedImagePath) must not call persistImageCopy (:224; ReceiptOcrService.kt:52); persistImageCopy throws → imagePath null (:225,:324); CancellationException propagates (:218).
- DEAD: :265 processBatch (delegates to coordinator :620; covered by ReceiptRepositoryBatchDuplicateTest); :334 createExpenseFromReceipt (disabled, DeprecationLevel.ERROR :409-431); :220 old test proved nothing (relaxed coordinator) — known gap: processBatch onProgress count + URI de-dup (ReceiptRepository.kt:594, ~:651-653) asserted nowhere → B6 candidate; :303 :309 :316 trivial pass-throughs; :388 deleteReceipt is deprecated (WARNING) with zero callers — real path ReceiptLifecycleCoordinator.deleteReceipt covered at ReceiptLifecycleCoordinatorTest :1329-1420.
- **DECISION:** also add one ReceiptLifecycleCoordinatorTest case for `isOcrFailure` (ReceiptLifecycleCoordinator.kt:463-464, :480-481 — no test anywhere; this marker was the reason the stress class was kept). Test-only, small.
- Setup: 24-arg ctor from ReceiptRepositoryBatchDuplicateTest.kt:72-97, Robolectric (Uri.parse), Lazy<ReceiptLifecycleCoordinator> mock, no Room. Review: reviewer-strict (receipt lifecycle).

## B3b-R baseline
- G-IGNORE-01 scans app/src/test + androidTest for @Ignore/@Disabled (scripts/verify_ignored_test_budget.py:33,:234).
- Current: 16 @Ignore in 14 files (list in scout report: TransactionsVMStress :35, NotificationIntakeEnqueueFailure :51 (no-touch), ReviewQueueRepositoryStress :27 (no-touch), CompositeGeocodingStress :26, ReceiptRepositoryStress :52, NotificationRepositoryStress :18 (no-touch), ReviewVMStress :53, AddExpenseVMStress :22, MainVMStress :20, AnalyticsVMStress :29, SpendingMapVMStress :85/:98/:106, DebugVMStress :46, HomeVMCurrencyReload :56, HomeVMStress :52).
- Expected floor after B3b-1..5,7: 4. Read the exact number from `registered-guard ignored_test_budget` before editing.
- Baseline 29 carriers: scripts/ci/guard_registry.py:315; scripts/ci/test_gradle_registered_guard_contract.py:190; scripts/ci/test_run_static_guard_suite.py:1585; .github/workflows/ci.yml:235 (comment); docs/ci/GUARD_COMMANDS.generated.md:151. **Missed by plan:** scripts/verify_ignored_test_budget.py:434-435 (argparse default + help); .github/workflows/ci.yml:298 (`-PmaxIgnoredTests=29`, ignored by gradle task); docs/ci/CI_GUARDRAILS_BASELINE.md:112,:159,:310,:330.
- TEST_IGNORE_CLASSIFICATION.md stale: :32, :36/:131, :12/:113/:166, :42/:137; B3b rows :19-44.

## Execution (batching decision + API-timeout mitigation)
Small sequential writers (one at a time, file-disjoint), ONE validation round for all B3b (multi-filter run after B4-P1c):
B3b-5 → B3b-4 → B3b-3 → B3b-2 → B3b-1 (hang risk) → B3b-7 → TEST_IGNORE docs pass → B3b-R (guard run before/after; guard_docs_truth; pytest-subset on the two contract tests).
