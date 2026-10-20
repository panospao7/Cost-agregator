# Ignored Test Classification

**Original date:** May 6, 2026 (27 files with `@Ignore`)
**Reconciled:** October 17, 2026 (test-suite-audit, B3 / B3b)
**Current files with a real `@Ignore` annotation under `app/src/test`:** 0

## Current state

Verified by grepping `app/src/test` and `app/src/androidTest` for `@Ignore` and
`@Disabled` annotations. No test class carries either annotation. The three
classes that were still ignored at the 2026-10-17 reconciliation
(`ReviewQueueRepositoryStressTest`, `NotificationRepositoryStressTest`,
`NotificationIntakeEnqueueFailureTest`) have been deleted; their replacements
are listed under "Resolved by test-suite-audit" below. The G-IGNORE-01 budget
baseline is 0.

`BudgetRepositoryStressTest.kt` (15 tests) is NOT ignored any more (the
`@Ignore` was removed in A.9 Batch 2). `ReceiptScanViewModelStressTest.kt` is
present and active.

## Classification Rules (historical)

| Label | Criteria |
|-------|----------|
| **DELETE** | Permanently ignored, duplicates better tests, source-text assertions, purely implementation-coupled, no unique business contract |
| **REWRITE** | Protects financial correctness (money, tax, split precision, CSV escaping), has real business value but test is outdated/broken |
| **NIGHTLY** | Deterministic stress/load/concurrency tests that are too slow for PRs but have value. No nightly workflow consumes these labels; the label is retained only for historical reference. |

## Resolved by test-suite-audit (2026-10-17)

Status of the original 27 rows. "Absent" means the file does not exist in the
tree at reconciliation time (deleted or superseded in B3 / B3b); it is not an
`@Ignore`.

| # | Original file | Original label | Status now |
|---|---------------|----------------|------------|
| 1 | `consistency/ConcurrencyStateRaceTest.kt` | DELETE | DELETED (absent; tested stdlib `StateFlow`, no app code) |
| 2 | `data/database/entity/ExpenseEntityStressTest.kt` | NIGHTLY | DELETED (absent) |
| 3 | `data/database/TransactionRollbackTest.kt` | REWRITE | DELETED (absent); simulated-flag test replaced by real-Room `data/database/GroupTransactionCoordinatorRollbackTest.kt` |
| 4 | `data/location/CompositeGeocodingServiceStressTest.kt` | NIGHTLY | REVIVED in B3b-5 into `data/location/CompositeGeocodingServiceTest.kt` |
| 5 | `data/repository/BudgetRepositoryStressTest.kt` | NIGHTLY | REVIVED in place (`@Ignore` removed, 15 tests run in CI) |
| 6 | `data/repository/CategoryRepositoryStressTest.kt` | NIGHTLY | DELETED (absent); live coverage in `CategoryRepositoryTest` |
| 7 | `data/repository/ExpenseRepositoryStressTest.kt` | NIGHTLY | DELETED (absent); live coverage in `ExpenseRepositoryTest` |
| 8 | `data/repository/NotificationProcessingPipelineStressTest.kt` | NIGHTLY | DELETED (absent); live coverage in `NotificationProcessingPipeline{Atomicity,OversizedAmount,Reliability,SourceLink}Test` |
| 9 | `data/repository/NotificationRepositoryStressTest.kt` | NIGHTLY | DELETED (absent); replaced by `data/repository/NotificationRepositoryDeleteAllNotificationsClockTest.kt` |
| 10 | `data/repository/ReceiptRepositoryStressTest.kt` | NIGHTLY | REVIVED (original file absent) as `data/repository/ReceiptRepositoryOcrFallbackTest.kt` plus 3 receipt coordinator tests |
| 11 | `data/repository/ReviewQueueRepositoryStressTest.kt` | NIGHTLY | DELETED (absent); replaced by `data/repository/ReviewQueueRepositoryTest.kt` |
| 12 | `domain/tax/TaxCalculationTest.kt` | REWRITE | REVIVED (no `@Ignore`; class doc notes the old local VAT-extraction helper test stays removed) |
| 13 | `domain/util/MoneyTest.kt` | REWRITE | REVIVED (no `@Ignore`) |
| 14 | `domain/util/NotificationIdGeneratorTest.kt` | REWRITE | REVIVED (no `@Ignore`) |
| 15 | `service/NotificationCaptureServiceStressTest.kt` | NIGHTLY | DELETED (absent); live coverage in `NotificationCaptureService{Cleanup,DeferredPolicy,Fallback,FgsTimeout,BlockedDecision}Test` |
| 16 | `ui/MainViewModelStressTest.kt` | NIGHTLY | REVIVED in B3b-4 as `ui/MainViewModelEventsTest.kt` |
| 17 | `ui/screens/addexpense/AddExpenseViewModelStressTest.kt` | NIGHTLY | REVIVED in B3b-3 into `AddExpenseViewModelTest.kt` |
| 18 | `ui/screens/aisettings/AiSettingsScreenTextTest.kt` | DELETE | DELETED (absent; source-text assertions) |
| 19 | `ui/screens/analytics/AnalyticsStateStressTest.kt` | NIGHTLY | DELETED (absent); live coverage in `AnalyticsStateMoneySafetyTest` |
| 20 | `ui/screens/analytics/AnalyticsViewModelStressTest.kt` | NIGHTLY | REVIVED in B3b-4 as `AnalyticsViewModelYearOverYearTest.kt` |
| 21 | `ui/screens/budget/BudgetViewModelStressTest.kt` | NIGHTLY | DELETED in B3-8 (absent); live coverage in `BudgetViewModelCurrencyProvenanceTest` and `BudgetViewModelAutopilotQualityTest` |
| 22 | `ui/screens/debug/DebugViewModelStressTest.kt` | NIGHTLY | REVIVED in B3b-4 as `ui/screens/debug/DebugViewModelTest.kt` |
| 23 | `ui/screens/home/HomeViewModelStressTest.kt` | NIGHTLY | REVIVED in B3b-1 as `HomeViewModelWidgetsTest.kt` |
| 24 | `ui/screens/map/SpendingMapViewModelStressTest.kt` | NIGHTLY | REVIVED in B3b-4 as `SpendingMapViewModelHeatmapTest.kt` |
| 25 | `ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt` | NIGHTLY | ACTIVE (file present, no `@Ignore`) |
| 26 | `ui/screens/review/ReviewViewModelStressTest.kt` | NIGHTLY | REVIVED in B3b-2 as `ReviewViewModelAiAssistTest.kt` (cloud stays DISABLED) |
| 27 | `ui/screens/transactions/TransactionsViewModelStressTest.kt` | NIGHTLY | REVIVED in B3b-3 as `TransactionsViewModelPagingTest.kt` |

Also revived in place (not in the original 27, previously ignored):
`ui/screens/home/HomeViewModelCurrencyReloadTest.kt`.

Also deleted (not in the original 27; ignored class added after the original
audit): `domain/notification/capture/NotificationIntakeEnqueueFailureTest.kt`,
replaced by `domain/notification/capture/NotificationIntakeEnqueueFailureRoomTest.kt`.

## Rollup

| Status | Count (of original 27) |
|--------|-------|
| Revived (active, no `@Ignore`; includes ReceiptScanViewModelStressTest and BudgetRepositoryStressTest) | 15 |
| Deleted / absent from tree | 12 |
| Still ignored | 0 |
| **Total** | **27** |

The tree-wide total of real `@Ignore` annotations is **0**; this includes the
class added after the original audit (`NotificationIntakeEnqueueFailureTest`),
which has also been deleted.

The original DELETE / REWRITE / NIGHTLY effort estimates and the nightly
workflow recommendation (`--tests "*StressTest*"`) are superseded: no
nightly workflow was created.
