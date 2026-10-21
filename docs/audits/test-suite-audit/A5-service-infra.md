# A5 — Kotlin unit tests: service, receiver, worker(s), startup, diagnostics, metrics, ui, util

Auditor: scout (Sonnet), 2026-10-16. Read-only; no build/test run. Persisted by orchestrator.

## 0. Method and confidence
- Scope: 109 files, 1,261 `@Test`, ~34k LOC (service 19/307, ui 70/660, diagnostics 5/102, metrics 4/43, util 6/72, startup 1/44, worker 1/17, workers 1/5, receiver 2/11).
- Read fully/largely: diagnostics `GlobalDurableDiagnosticsGoldenTest`, `DDL512RegressionTest` (40-485), `DurableDiagnosticsA8RegressionTest` (55-235), `DurableDiagnosticsAcceptanceTest` (128-280); receivers; `WorkerContractTest`; `NotificationCaptureServiceCleanupTest`; `RecommendationStateManagerTest` (1-330), `RecommendationLifecycleManagerTest` (100-175); `NavigationRouteContractTest` (258-426), `FeatureConfigNavigationContractTest`; `BudgetViewModelStressTest` (28-290), `ReviewViewModelStressTest` (1-260 + assertion scan), `HomeViewModelCurrencyReloadTest` header; `AdvancedAnalyticsViewModelTest`, `AddExpenseViewModelTest` (40-128), `DashboardWidgetRenderCoverageTest`, `CarbonFootprintScreenTest`, `PriceProtectionViewModelTest` (30-160), `CarbonFootprintViewModelTest` (40-130); `ReceiptMatchingWorkerTest` (134-204, 495-555), `NotificationIntakeWorkerTimeoutTest` (95-135).
- Names only: `AppStartupCoordinatorRecoveryTest`; service DeferredPolicy/Fallback/FgsTimeout/NotificationFilter/NavigationTargetResolver/TransactionFilterSerializer/DataSyncFgsTimeoutGuard/RecommendationDismissalHandler/Deduplicator/CacheService, warranty/reminder/receiptmatching workers; util; metrics; remaining ui.
- Not opened (treat as KEEP, unreviewed): `HomeViewModelRecommendationTest`, `AssistantViewModelTest`, `ExportOptionsViewModelTest`, `BackupRestoreViewModelTest`, `SharedExpenseGroupsViewModelTest`, `CashFlowCalendarViewModelTest`, `BudgetAutopilotUiContractTest`, `AnalyticsViewModelInsightsTest`.
- needs-confirm: diagnostics rows, money-adjacent VM tests, worker/privacy rows.
- Runtime: fast; ~9 cases > 1 s, nearly all Robolectric first-test warm-up (`WorkerContractTest` 1.24 s needs no Android).

## 1. Summary
| Group | Files | @Test | Est. removable | Est. mergeable | LOC |
|---|---|---|---|---|---|
| service | 19 | 307 | ~32 | ~55 | ~6,600 |
| ui | 70 | 660 | ~99 (74 in dead `@Ignore` classes) | ~25 | ~19,800 |
| diagnostics | 5 | 102 | ~40 | ~14 | ~1,500 |
| metrics | 4 | 43 | ~5 | ~9 | ~1,300 |
| util | 6 | 72 | ~2 | ~14 | ~1,800 |
| startup | 1 | 44 | 0 | ~5 | ~1,800 |
| worker/workers/receiver | 4 | 33 | ~6 | ~5 | ~1,300 |
| **Total** | **109** | **1,261** | **~184 (14.6%)** | **~127 (10%)** | **~34,000** |

~310 fewer tests (~25%); a further ~45 tests in ignored classes are worth **reviving**.

Top findings:
1. **Permanently dead tests:** 10 `@Ignore`d ViewModel classes = 119 tests that never run (Review 37, Home 19, Transactions 16, Budget 15, Analytics 8, AddExpense 8, Debug 7, Main 5, SpendingMap 3/10, `HomeViewModelCurrencyReloadTest` 1) — still mechanically edited on signature changes (`release-1.0-remediation-plan.md:258`, ledger :1108). `docs/testing/TEST_IGNORE_CLASSIFICATION.md` calls them "NIGHTLY" (they never run) and is stale (lists non-existent `NotificationCaptureServiceStressTest`, `AnalyticsStateStressTest`; lists active `ReceiptScanViewModelStressTest` as ignored).
2. **Diagnostics tests prove less than claimed:** production `SafeSinkOperationRunHandle` (`domain/diagnostics/CompositeOperationRunRecorder.kt:98`) has **no test**; "terminal-once" tests run against test-local doubles (`TrackingHandle`, `TerminalTrackingHandle`). Many others are ctor read-back / "model exists".
3. **Reflection/structural non-behaviour tests:** `NotificationCaptureServiceCleanupTest` (private-method param counts), `WorkerContractTest` (`spec.enabled || !spec.enabled`), `NotificationIntakeWorkerTimeoutTest` (3 tests of kotlinx exception hierarchy).
4. **Copy-paste families:** Recommendation lifecycle/dismissal; `NavigationRouteContractTest` (~15 per-destination roundtrips subsumed by its two "all destinations" tests); `NavigationTargetResolverTest`; `NotificationFilterTest`; Snooze/Dismiss reminder workers (line-for-line mirrors); JSON importer date fallback.
5. **Dead helper:** `util/HiltTestUtils.kt` (34 LOC, no subclass).
6. **Resources clean:** all 22 `test/resources/golden/*.json` referenced; `OCR_TEST_DOCUMENT.txt` used; `app/src/test/kotlin/` holds only `RecurringExpenseEngineTest.kt` (denylist-pinned).
7. **Robolectric on pure logic:** `WorkerContractTest`, `DeepLinkParserTest` (maybe needs `Uri`), `NotificationCaptureServiceFgsTimeoutTest`.

## 2. Non-KEEP files

### 2a. DELETE-CANDIDATE
| File | Evidence | Action | Saved | Risk |
|---|---|---|---|---|
| `ui/screens/budget/BudgetViewModelStressTest.kt` (15, `@Ignore` :32) | `assertNotNull` :78/:83; no-assert :141, :162, :175, :189, :198, :217, :236, :266; live coverage in `BudgetViewModelCurrencyProvenanceTest`, `...AutopilotQualityTest` | Delete; update TEST_IGNORE_CLASSIFICATION rows 21/133; lower G-IGNORE-01 baseline | 15 | low |
| `ui/screens/map/SpendingMapViewModelStressTest.kt` (3 ignored + 7 active) | 3 ignored trivial; 4 active overlap on PURCHASE-only heatmap (:117, :203, :245, :391) | Delete 3; merge 4 → 1 table; rename | 5 | low |
| `ui/screens/review/ReviewViewModelStressTest.kt` (37, `@Ignore` :53) | ~21 trivial (initial state, no-assert); **~16 valuable AI-assist tests dead** (:384-570, :634-894, :958-1250, :1268, :1304, :193, :230) | Revive ~16 into active `ReviewViewModelAiAssistTest` (fix hang — `ReviewViewModelBatchDuplicateMessageTest` constructs the VM fine); delete rest | ~21 (+16 revived) | low |
| `ui/screens/home/HomeViewModelStressTest.kt` (19, `@Ignore` :52) | **Only tests** of widget reorder/visibility (:315-360), planned expense (:368-420), cloud-dashboard warning (:227) | Revive ~9 as `HomeViewModelWidgetsTest` (planned-expense ×5 → table); delete ~10 | ~10 (+9) | low |
| `ui/screens/transactions/TransactionsViewModelStressTest.kt` (16, `@Ignore` :35) | Trivial :82-147, :235, :251; real loadMore/paging/filter :158, :182, :205, :264, :304 | Revive ~5; delete ~11 | ~11 | low |
| `ui/screens/addexpense/AddExpenseViewModelStressTest.kt` (8, `@Ignore` :22) | **Only validation tests** (:76, :85, :95, :137); active `AddExpenseViewModelTest` has none | Revive 4; delete 4 | 4 (+4) | low |
| `ui/screens/analytics/AnalyticsViewModelStressTest.kt` (8, `@Ignore` :29) | Trivial :154-196; keep :204, :213 (YoY exact) | Revive 2 with `FakeTimeProvider`; delete 6 | 6 | low |
| `ui/screens/debug/DebugViewModelStressTest.kt` (7, `@Ignore` :46) | verify-only debug tooling | Delete :213, :241; optionally revive rest | 3 | low |
| `ui/MainViewModelStressTest.kt` (5, `@Ignore` :20) | trivial :35, :51, :76 | Revive :41, :65; delete 3 | 3 | low |
| `ui/screens/home/HomeViewModelCurrencyReloadTest.kt` (1, `@Ignore` :56) | Honest P5-006 spec (ledger :493) | Owner decision: keep if RP-21 harness planned, else use-case-level test | 0–1 | med |
| `diagnostics/DDL512RegressionTest.kt` (27) | ~18 tautologies: file helpers never touching `RestoreJournal` (:56, :71, :88, :101, :116, :368); test-local `TrackingHandle` (:192–:265); local-list read-backs (:278, :296, :400); reflection/ctor (:319, :150, :162); `if (result != null)` :175 | Delete ~18; keep source scans :132, :381 and sanitizer tests; **add real `SafeSinkOperationRunHandle` terminal-once test first** | ~18 | med (privacy docs cite class) |
| `diagnostics/DurableDiagnosticsA8RegressionTest.kt` (15) | local double :117/:126/:134; read-back :59, :78, :142, :161, :227; hash cases dup | Delete ~8; fold 4 into sanitizer table; keep :98 (paths stripped — real), :205, :215 | ~8 | med |
| `diagnostics/GlobalDurableDiagnosticsGoldenTest.kt` (19) | enum pins :160/:174/:187; ctor read-back :104-155; :83 dup :29 | Delete ~8; sanitizer cases → table | ~8 | low-med |
| `diagnostics/DurableDiagnosticsAcceptanceTest.kt` (24) | CapturingWriter read-back :181/:220/:247; :200 asserts own ctor; keep `SideEffectDiagnosticRecorder` :135-176 | Delete 4; sanitizer → table | 4 | low |
| `diagnostics/DurableDiagnosticsRegressionTest.kt` (17) | id construction read-back :77, :88, :95, :155, :161, :179 | Delete ~4; sanitizer → table; keep :38, :46 | ~4 | low |
| `workers/WorkerContractTest.kt` (5) | :45 always true; :67 dup :28; Robolectric unneeded (1.2 s) | Delete 2; drop Robolectric; keep :75 parity (ARCHITECTURE.md:1822) | 2 | low |
| `worker/NotificationIntakeWorkerTimeoutTest.kt` (17, PINNED) | :102/:120 identical kotlinx facts; :112 | Delete 3 | 3 | low |
| `util/HiltTestUtils.kt` | No subclass | Delete file | 0 | low |

### 2b. MERGE
| File | Evidence | Action | Saved | Risk |
|---|---|---|---|---|
| `ui/navigation/NavigationRouteContractTest.kt` (26, PINNED NAVIGATION_ARCHITECTURE.md:82) | 16 per-destination roundtrips subsumed by :262, :305; :418 ⊂ :383 | Delete ~16; keep :74, :80, :112, :190, :364, :373, :383 | ~16 | low |
| `ui/navigation/FeatureConfigNavigationContractTest.kt` (7) | :25 ⊂ :36; :36≈:57; :87 weak | 7 → 3 | 4 | low |
| `ui/navigation/NavigationControllerBehaviorTest.kt` (13, PINNED) | canNavigateBack trio repeats :28-60 | 3 → 1 | 2 | low |
| `ui/navigation/DeepLinkParserTest.kt` (12, Robolectric) | host/scheme cases | Table; check Robolectric need | ~4 | low |
| `ui/screens/home/DashboardWidgetRenderCoverageTest.kt` (3) | hard-coded 22; manual list | `assertEquals(RENDERED_WIDGETS, sealedSubclasses)` | 2 | low |
| `ui/screens/{backup,export,review,map}/*PrivacyDenialTest.kt` (PINNED) | denied/fail-closed pairs | Table per file; keep "permitted leaves null" + malformed-reason (:227) | ~4 | med (privacy) |
| `ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt` (20, active) | Misnamed; trivial :168/:181; paired assist cases; 17 reflection pokes on `_state` | Rename `ReceiptScanViewModelAiAssistTest`; table; test hook | ~4 | low-med |
| `ui/screens/price/PriceProtectionViewModelTest.kt` | coVerify-only :82/:93; :124 weak | Merge init pair; strengthen | 2 | low |
| `service/RecommendationLifecycleManagerTest.kt` (32) | error variants, user-id variants, OOM swallow test; **no CancellationException case in any `Recommendation*Test`** | Failure table incl. cancellation-rethrow; user-id table; delete OOM test | ~14 | low-med |
| `service/RecommendationDismissalHandlerTest.kt` (23) | id/priority variants; error variants | 2 tables | ~12 | low |
| `service/RecommendationStateManagerTest.kt` (30) | :189 ungated/weak; :249/:273/:290 coVerify-only | Gate :189; fold into one state-asserting test | 2 | low |
| `service/RecommendationCacheServiceTest` + `RecommendationDeduplicatorTest` | near-identical put/putAll; table-able dedup | Merge | 5 | low |
| `service/NavigationTargetResolverTest.kt` (32) | canHandle ×10; ANALYTICS window cases | 2 tables | ~10 | low |
| `service/NotificationFilterTest.kt` (25) | Gmail/SMS/Viber pairs; discovery ×6 | Table | ~8 | low-med |
| `service/TransactionFilterSerializerTest.kt` (16) | overlap with roundtrip | ~12 | 4 | low |
| `service/NotificationCaptureServiceCleanupTest.kt` (9, PINNED) | 4 pins on one constant; :85 not production filter; reflection :183/:232 | Merge pins; delete reflection only after confirming DeferredPolicy proves NonCancellable hand-off; keep :115 | ~4 | med (privacy) |
| `service/NotificationCaptureServiceDeferredPolicyTest.kt` (24, PINNED) | gate × failure-mode grid | Table; keep :260 and storage-policy :449-522 | ~6 | med |
| `service/NotificationCaptureServiceFallbackTest.kt` (10) | extras-source shapes | Table | 3 | low |
| `service/receiptmatching/ReceiptMatchingWorkerTest.kt` (27, PINNED denylist :29) | :184 ⊂ :138; exception-type trio; permission cluster ×8 | Delete :184; tables; keep :437, :269, :295, :320 | ~7 | med |
| `service/reminder/Snooze*` + `Dismiss*ReminderActionWorkerTest` | line-for-line mirrors | One parameterized class | 3 | low |
| `service/DataSyncFgsTimeoutGuardTest.kt` | 24 h boundary points | Table | 2 | low |
| `receiver/BootReceiverStressTest`, `ServiceRestartReceiverStressTest` | misnamed; :67≈:101; no-assert "does not crash" | Merge; add assertions; rename | 1 | low |
| `util/JsonExpenseImporterTest.kt` (14) | date/timestamp fallback ×9 | Table | ~6 | low |
| `util/CsvExpenseImporterTest`, `RoundtripContractTest`, `ImportCoordinatorTest` | :61 "can be instantiated"; locale pair; parity/facade dup | Delete :61; tables; keep `ExportImportRoundtripTest` (PINNED denylist :24) | ~8 | low |
| `metrics/TimePeriodAlignmentTest.kt` (21) | contiguity re-asserted; overlaps A3 TimePeriodUtils | Table (confirm vs A3) | ~7 | low-med |
| `metrics/EffectiveAmountConsistencyTest`, `GoldenAnalyticsDatasetTest`, `DashboardWidgetConsistencyTest` | isNotMine ×3; :33 tests the fixture | Merge 3; delete :33; keep DashboardWidgetConsistency | 4 | med (money) |
| `startup/AppStartupCoordinatorRecoveryTest.kt` (44, PINNED) | small fixture variants | Optional table; otherwise KEEP | ~4 | high |

### 2c. STRENGTHEN
| File | Evidence | Action |
|---|---|---|
| `diagnostics/DDL512RegressionTest.kt` :175 | `if (result != null)` cannot fail | Assert non-null first |
| `service/RecommendationStateManagerTest.kt` :189 | ungated race | `CompletableDeferred` gate |
| `ui/screens/carbon/CarbonFootprintViewModelTest.kt` :102 | asserts mock pass-through | Assert VM mapping or delete |
| `ui/screens/price/PriceProtectionViewModelTest.kt` | coVerify-only | Assert state |
| `ui/screens/assistant/AssistantViewModelTest.kt` :759-773 | reflection on private state | Test hook |

### 2d. SLOW
Nothing material beyond `WorkerContractTest` Robolectric removal.

## 3. Consolidation clusters
- **C1 dead ViewModel stress classes:** delete ~74, revive ~45 under honest names; update TEST_IGNORE_CLASSIFICATION (rows 15-27, 128-139 + stale entries); lower G-IGNORE-01 baseline. End state: no ViewModel `*StressTest`.
- **C2 Navigation contracts:** ~20 tests into the two "all destinations" tables.
- **C3 shared sanitizer table:** one `EventMetadataSanitizerTest` (~45 assertions across 5 files → ~20 rows); keep SENSITIVE_DIAGNOSTICS_POLICY.md:109 class names resolvable or update doc.
- **C4 Recommendation services:** ~33 tests → tables; add cancellation-rethrow case.
- **C5 service parsers/resolvers:** ~31 → tables.
- **C6 reminder action workers:** merge.
- **C7 importers:** ~14.
- **C8 capture privacy tables:** last, reviewer-strict.

## 4. PINNED and sole-proof
PINNED: `ReceiptMatchingWorkerTest` (denylist :29), `ExportImportRoundtripTest` (denylist :24), `BillReminderWorkerTest`, `WarrantyExpirationWorkerTest`, `NotificationIntakeWorkerTimeoutTest` (WORKER_BASELINE_INVENTORY), `NavigationControllerBehaviorTest`, `NavigationRouteContractTest` (NAVIGATION_ARCHITECTURE.md:80-82), `WorkerContractTest` (ARCHITECTURE.md:1822), 5 diagnostics classes (SENSITIVE_DIAGNOSTICS_POLICY.md:109), `NotificationCaptureServiceDeferredPolicyTest` (WAVE-1-STATUS, ledger :376), `AppStartupCoordinatorRecoveryTest`, Backup/Review PrivacyDenial tests, FgsTimeout/Fallback/receivers (ledger :1491-1500), metrics consistency classes (docs/testing).

Sole proofs: `NotificationCaptureServiceCleanupTest` :115; DeferredPolicy :449-522, :304-368, :382-407, :558-568, :260; `NotificationFilterTest` :52/:106; `ReceiptMatchingWorkerTest` :437/:269; `WarrantyExpirationWorkerTest` :361; `BillReminderWorkerTest` :315/:325/:341; `NotificationIntakeWorkerTimeoutTest` :198, :408-747; `WorkerContractTest` :75; `DurableDiagnosticsA8RegressionTest` :98; sanitizer redaction tests; `DurableDiagnosticsAcceptanceTest` :135-176; PrivacyDenial "permitted leaves null" + malformed-reason; all of `AppStartupCoordinatorRecoveryTest`; ignored Home/Review/AddExpense behaviours (only tests of those behaviours — revive, don't delete). **Gap:** no test of production `SafeSinkOperationRunHandle` terminal-once.

## 5. Recommended order
1. Dead code (HiltTestUtils, BudgetViewModelStressTest, trivial halves of stress classes; doc + baseline update) ~74 tests, ~3k LOC.
2. Revive ~45 ignored-but-valuable tests (time-boxed).
3. Pure-logic cleanups (C2, C5, C6, C7, WorkerContract, metrics).
4. Recommendation tables (C4) + cancellation case.
5. Diagnostics (C3) after adding real `SafeSinkOperationRunHandle` test — reviewer-strict.
6. Privacy/worker merges (C8) — reviewer-strict.
7. Leave `AppStartupCoordinatorRecoveryTest`.
Validate with targeted-unit-test per step + `verify_ignored_test_budget.py` after ignore changes.
