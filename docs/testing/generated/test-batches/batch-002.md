# Test Suite Audit Report — Batch 002

**Audit date:** 2026-05-12
**Total files audited:** 100
**Scope:** data/repository (1–20), data/security (21), data/service (22), data/speech (23), domain/ai/model (24–29), domain/ai/policy (30–31), domain/ai/usecase (32–52), domain/ai/util (53), domain/alerts (54), domain/analytics (55–82), domain/bank (83), domain/budget (84–95), domain/business (96), domain/carbon (97), domain/cashflow (98), domain/categorization (99–100)

---

## Summary Table

| # | Path | Type | Value | Action | Tests | Ignored | Confidence | Main reason |
|---|---|---|---|---|---|---|---|---|
| 1 | data/repository/DeterministicExpenseExportPagerTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 2 | 0 | HIGH | Tests deterministic pagination exhaust + arg validation with real assertions |
| 2 | data/repository/ExpenseRepositoryStressTest.kt | MOCK_ORCHESTRATION | P4_NEGATIVE_VALUE | MOVE_TO_NIGHTLY | 13 | 1 | HIGH | Fully mocked, class-level @Ignore, shallow stress; real contract already covered by ExpenseRepositoryTest |
| 3 | data/repository/ExpenseRepositoryTest.kt | REPOSITORY_INTEGRATION | P1_HIGH | KEEP | 9 | 0 | HIGH | Tests updateExpenseCategory→correction record, merchant alias via slot capture; core write path |
| 4 | data/repository/ExpenseRepositoryTruncationTest.kt | DAO_ROOM_CONTRACT | P1_HIGH | KEEP | 9 | 0 | HIGH | Regression suite proving uncapped getAllExpenses / getExpensesBetween; prevents silent data loss |
| 5 | data/repository/FinancialWeatherRepositoryTest.kt | MULTI_PIPELINE_SCENARIO | P2_MEDIUM | KEEP | 9 | 0 | MEDIUM | Tests past daily cumulative spend, forecast→weather mapping; slot captures verify input assembly |
| 6 | data/repository/GroupsRepositoryImplTest.kt | REPOSITORY_INTEGRATION | P2_MEDIUM | KEEP | 6 | 0 | MEDIUM | Group CRUD with member delete error surfacing, split-reference guard verified |
| 7 | data/repository/MerchantRulesRepositoryTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 4 | 0 | HIGH | No mocks — exercises real cleanMerchantName string sanitization directly |
| 8 | data/repository/MultiCurrencyRepositoryTest.kt | DAO_ROOM_CONTRACT | P1_HIGH | KEEP | 27 | 0 | HIGH | 27 tests covering conversion paths, missing rates, aggregate helpers; extensive currency contract |
| 9 | data/repository/NotificationProcessingPipelineOversizedAmountTest.kt | PARSER | P1_HIGH | KEEP | 8 | 0 | HIGH | No mocks — pure parser function tests for oversized amount detection, PAN masking, keyword proximity |
| 10 | data/repository/NotificationProcessingPipelineReliabilityTest.kt | MULTI_PIPELINE_SCENARIO | P1_HIGH | KEEP | 17 | 0 | MEDIUM | End-to-end pipeline with parser→classifier→router→subscription chain; key integration contract |
| 11 | data/repository/NotificationProcessingPipelineStressTest.kt | MOCK_ORCHESTRATION | P4_NEGATIVE_VALUE | MOVE_TO_NIGHTLY | 28 | 1 | HIGH | @Ignore on class, stress tests that reimplement pipeline logic outside real pipeline; shallow |
| 12 | data/repository/NotificationRepositoryStressTest.kt | MOCK_ORCHESTRATION | P4_NEGATIVE_VALUE | MOVE_TO_NIGHTLY | 21 | 1 | HIGH | @Ignore on class, thin flow/DAO delegation tests (assertNotNull on flow returns) |
| 13 | data/repository/ReceiptRepositoryStatementDuplicateTest.kt | REPOSITORY_INTEGRATION | P2_MEDIUM | KEEP | 1 | 0 | MEDIUM | Single test but uses Robolectric + real deduplication logic; tests statement duplicate detection |
| 14 | data/repository/ReceiptRepositoryStressTest.kt | MOCK_ORCHESTRATION | P4_NEGATIVE_VALUE | MOVE_TO_NIGHTLY | 12 | 1 | HIGH | @Ignore on class, Robolectric-dependent, fully mocked stress on receipt processing |
| 15 | data/repository/RecommendationRepositoryTest.kt | REPOSITORY_INTEGRATION | P2_MEDIUM | KEEP | 17 | 0 | MEDIUM | DAO delegation tests with real deduplicator; entity↔domain mapping verified |
| 16 | data/repository/RecurringExpenseRepositoryTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 3 | 0 | HIGH | Slot-capture verifies RecurrenceCalculator.calculateNextDate semantics; real calculator integration |
| 17 | data/repository/ReviewQueueRepositoryStressTest.kt | MOCK_ORCHESTRATION | P4_NEGATIVE_VALUE | MOVE_TO_NIGHTLY | 8 | 1 | HIGH | @Ignore on class, thin DAO-flow delegation; approve path already tested in ReviewQueueRepositoryTest |
| 18 | data/repository/ReviewQueueRepositoryTest.kt | REPOSITORY_INTEGRATION | P1_HIGH | KEEP | 11 | 0 | HIGH | Approve/reject flows with status transitions, correction recording, classifier retraining |
| 19 | data/repository/SavingsContributionHistoryRepositoryTest.kt | BACKUP_RESTORE | P1_HIGH | KEEP | 3 | 0 | HIGH | Uses real temp-file DataStore; tests persistence across recreation, pruning, invalid-input rejection |
| 20 | data/repository/WarrantyTrackerRepositoryTest.kt | REPOSITORY_INTEGRATION | P2_MEDIUM | KEEP | 11 | 0 | MEDIUM | DAO delegation + cloud extraction routing; warranty creation path verified |
| 21 | data/security/SecureKeyStorageTest.kt | PRIVACY_SECURITY | P2_MEDIUM | KEEP | 17 | 0 | HIGH | Robolectric-based key storage roundtrip tests; TODO acknowledges mock tautology but contract is valuable |
| 22 | data/service/AndroidNotificationServiceTest.kt | ANDROID_SMOKE | P2_MEDIUM | KEEP | 2 | 0 | HIGH | Robolectric with real ContextWrapper override; tests notification delivery decision based on system state |
| 23 | data/speech/AndroidSpeechInputGatewayTest.kt | ANDROID_SMOKE | P2_MEDIUM | KEEP | 3 | 0 | HIGH | Robolectric; tests permission denial, startup failure, and listener error forwarding — real surface contracts |
| 24 | domain/ai/model/AiArtifactPresentationTest.kt | TRIVIAL_MODEL | P3_LOW | KEEP | 3 | 0 | HIGH | No mocks — pure data-class presentation toDisplayText tests |
| 25 | domain/ai/model/AiRuntimeStatusModelsTest.kt | TRIVIAL_MODEL | P3_LOW | KEEP | 2 | 0 | HIGH | No mocks — pure routeDisplayText and null-handling tests |
| 26 | domain/ai/model/CategorizationAssistInputTest.kt | PARSER | P3_LOW | KEEP | 1 | 0 | HIGH | No mocks — input validation: rejects NaN amount |
| 27 | domain/ai/model/NotificationParsingModelsTest.kt | PARSER | P2_MEDIUM | KEEP | 2 | 0 | HIGH | No mocks — input validation: rejects zero amount, out-of-range confidence |
| 28 | domain/ai/model/OnDeviceRuntimePresentationTest.kt | TRIVIAL_MODEL | P3_LOW | KEEP | 4 | 0 | HIGH | No mocks — user-facing runtime status messages tested |
| 29 | domain/ai/model/WarrantyExtractionModelsTest.kt | PARSER | P3_LOW | KEEP | 2 | 0 | HIGH | No mocks — input validation: invalid confidence, non-positive fields |
| 30 | domain/ai/policy/AiPolicyTest.kt | PRIVACY_SECURITY | P1_HIGH | KEEP | 15 | 0 | HIGH | No external mocks — real AiPolicyImpl; tests ALL AiSettings toggle combinations; privacy contract gate |
| 31 | domain/ai/policy/DefaultAiCapabilityRouterTest.kt | MULTI_PIPELINE_SCENARIO | P1_HIGH | KEEP | 18 | 0 | HIGH | Mixes real AiPolicyImpl + mocked environment; tests CLOUD/ON_DEVICE/DISABLED/DETERMINISTIC routing |
| 32 | domain/ai/usecase/CategorizationAssistInputBuilderTest.kt | MULTI_PIPELINE_SCENARIO | P2_MEDIUM | KEEP | 6 | 0 | MEDIUM | Tests input building with real DefaultRedactionSanitizer; category sorting + redaction verified |
| 33 | domain/ai/usecase/CategorizeReceiptItemsUseCaseTest.kt | MOCK_ORCHESTRATION | P3_LOW | KEEP | 1 | 0 | LOW | Only 1 test (status restore on null result); thin but tests a real use-case callback path |
| 34 | domain/ai/usecase/DedupeJudgeInputBuilderTest.kt | MULTI_PIPELINE_SCENARIO | P2_MEDIUM | KEEP | 8 | 0 | MEDIUM | Tests Ready/NotNeeded/Disabled/NoCandidates decision paths |
| 35 | domain/ai/usecase/DeliverProactiveBriefingNotificationUseCaseTest.kt | WORKER_RUNTIME | P2_MEDIUM | KEEP | 6 | 0 | MEDIUM | Fully mocked but tests notification delivery decision + engagement tracking |
| 36 | domain/ai/usecase/ExecuteFinancialQueryUseCaseTest.kt | MULTI_PIPELINE_SCENARIO | P2_MEDIUM | KEEP | 9 | 0 | MEDIUM | Tests summary/breakdown/comparison query execution with real assertions on output text |
| 37 | domain/ai/usecase/ExplainPendingReviewUseCaseTest.kt | MULTI_PIPELINE_SCENARIO | P2_MEDIUM | KEEP | 12 | 0 | MEDIUM | Full use-case lifecycle: routing→artifact cache→service→explanation text |
| 38 | domain/ai/usecase/FinancialQueryInterpretationInputBuilderTest.kt | MULTI_PIPELINE_SCENARIO | P2_MEDIUM | KEEP | 3 | 0 | MEDIUM | Input truncation, enrichment, category/merchant context verified |
| 39 | domain/ai/usecase/GenerateDashboardBriefingUseCaseTest.kt | MULTI_PIPELINE_SCENARIO | P2_MEDIUM | KEEP | 10 | 0 | MEDIUM | Full briefing generation lifecycle with artifact caching and routing |
| 40 | domain/ai/usecase/GenerateTransactionInsightUseCaseTest.kt | PRIVACY_SECURITY | P3_LOW | KEEP | 1 | 0 | MEDIUM | Single test but tests redaction of merchant+amount for cloud mode; privacy contract |
| 41 | domain/ai/usecase/GetAiRuntimeStatusUseCaseTest.kt | MULTI_PIPELINE_SCENARIO | P2_MEDIUM | KEEP | 4 | 0 | MEDIUM | Tests runtime status aggregation across capabilities; priority message selection |
| 42 | domain/ai/usecase/InterpretFinancialQueryUseCaseTest.kt | MULTI_PIPELINE_SCENARIO | P2_MEDIUM | KEEP | 7 | 0 | MEDIUM | Query interpretation pipeline: disabled→unsupported, service→structured, cancellation handling |
| 43 | domain/ai/usecase/JudgePendingReviewDuplicateUseCaseTest.kt | MULTI_PIPELINE_SCENARIO | P2_MEDIUM | KEEP | 7 | 0 | MEDIUM | Duplicate detection AI decision: NotNeeded/Duplicate/NotDuplicate verdict paths |
| 44 | domain/ai/usecase/MapFinancialQueryToNavigationUseCaseTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 2 | 0 | HIGH | No mocks — pure query→navigation mapping; real assertions on filter conversion |
| 45 | domain/ai/usecase/PrioritizeReviewItemsUseCaseTest.kt | MOCK_ORCHESTRATION | P3_LOW | KEEP | 4 | 0 | MEDIUM | Thin scorer delegation; priority sorting logic verified |
| 46 | domain/ai/usecase/ReceiptAssistInputBuilderTest.kt | PRIVACY_SECURITY | P3_LOW | KEEP | 3 | 0 | MEDIUM | Tests redaction toggle: contextual fields preserved/removed |
| 47 | domain/ai/usecase/ReceiptItemCategorizationInputBuilderTest.kt | PRIVACY_SECURITY | P3_LOW | KEEP | 1 | 0 | LOW | Single test; tests redaction in receipt categorization input building |
| 48 | domain/ai/usecase/ReviewExplanationInputBuilderTest.kt | PRIVACY_SECURITY | P2_MEDIUM | KEEP | 2 | 0 | HIGH | Tests pseudonymization + explanation removal when redaction enabled; privacy gate |
| 49 | domain/ai/usecase/SuggestCategoryFallbackUseCaseTest.kt | MULTI_PIPELINE_SCENARIO | P2_MEDIUM | KEEP | 10 | 0 | MEDIUM | Full categorization fallback lifecycle: router→service→cached artifact |
| 50 | domain/ai/usecase/SuggestReceiptExtractionUseCaseTest.kt | MULTI_PIPELINE_SCENARIO | P2_MEDIUM | KEEP | 9 | 0 | MEDIUM | Receipt extraction lifecycle with artifact caching and payment-method guard |
| 51 | domain/ai/usecase/SyncProactiveBriefingWorkUseCaseTest.kt | WORKER_RUNTIME | P3_LOW | KEEP | 3 | 0 | MEDIUM | Pure mock verification of scheduler calls; tests scheduling contract |
| 52 | domain/ai/usecase/ValidateBankStatementTransactionsUseCaseTest.kt | PRIVACY_SECURITY | P1_HIGH | KEEP | 9 | 0 | HIGH | On-device→cloud fallback with privacy gate check; OCR parsing edge cases; source attribution |
| 53 | domain/ai/util/AiArtifactSourceHashTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 6 | 0 | HIGH | No mocks — pure hashing stability + change detection across 3 input types |
| 54 | domain/alerts/AnomalyAlertOrchestratorTest.kt | MULTI_PIPELINE_SCENARIO | P1_HIGH | KEEP | 10 | 0 | HIGH | Anomaly detection→alert→notification pipeline with dedup, cooldown, look-normal learning |
| 55 | domain/analytics/AdvancedAnalyticsDashboardTest.kt | REPOSITORY_INTEGRATION | P2_MEDIUM | KEEP | 5 | 0 | HIGH | Uses AnalyticsEngineTestBase + stubbed repository filter; tests totals/categories/merchants/trends |
| 56 | domain/analytics/AdvancedAnalyticsEngineDeepTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 10 | 0 | HIGH | Tests median, percentile interpolation, period boundaries with real category data |
| 57 | domain/analytics/AdvancedAnalyticsEngineTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 3 | 0 | MEDIUM | Spending pattern classification (weekend warrior, subscription detector) |
| 58 | domain/analytics/AnalyticsCurrencyNormalizerTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 5 | 0 | HIGH | Real CurrencyConverter + FakeExchangeRateStore; tests conversion, missing rate, invalid currency |
| 59 | domain/analytics/AnalyticsStressTest.kt | STRESS_PERFORMANCE | P2_MEDIUM | KEEP | 1 | 0 | MEDIUM | Single 10k-transaction stress test; verifies analytics engine scalability |
| 60 | domain/analytics/AnalyticsWindowingSupportTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 5 | 0 | HIGH | No mocks — pure merchant key normalization, unicode, punctuation edge cases |
| 61 | domain/analytics/AnomalyDetectorTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 4 | 0 | HIGH | Real AnomalyDetector instantiation; shared-expense effective amount guard, tight distribution, contextual outlier |
| 62 | domain/analytics/CategoryInsightEngineTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 11 | 0 | HIGH | No mocks — pure engine with golden March data; tests category totals, MoM, top categories |
| 63 | domain/analytics/DayOfWeekAnalyzerTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 4 | 0 | HIGH | No mocks — pure analyzer; calendar→monday-zero indexing, golden weekend vs weekday |
| 64 | domain/analytics/InsightsEngineDeepTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 7 | 0 | HIGH | Real CategoryInsightEngine, MerchantInsightEngine, DayOfWeekAnalyzer used; period boundary tests |
| 65 | domain/analytics/InsightsEngineEdgeCaseTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | KEEP | 6 | 0 | MEDIUM | Sub-engines mocked; tests total/count/empty edge case behavior |
| 66 | domain/analytics/InsightsEngineTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | KEEP | 4 | 0 | MEDIUM | Sub-engines mocked; daily totals + cancellation testing |
| 67 | domain/analytics/InsightsEngineValidationTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 13 | 0 | HIGH | Real calculators used (MonthlyComparison, CategoryInsight, etc); extensive validation of calculations |
| 68 | domain/analytics/MerchantInsightEngineTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 4 | 0 | HIGH | No mocks — pure engine; top merchant ranking, frequency, not-mine exclusion |
| 69 | domain/analytics/MonthlyComparisonCalculatorTest.kt | GOLDEN | P1_HIGH | KEEP | 3 | 0 | HIGH | No mocks — pure calculator with golden March vs February data; MoM% verified |
| 70 | domain/analytics/RecurringIntervalLogicTest.kt | PURE_ENGINE | P3_LOW | KEEP | 1 | 0 | MEDIUM | Single test but exercises rounding edge case that old truncation logic would miss |
| 71 | domain/analytics/SpendingPaceBoundaryTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 4 | 0 | HIGH | Only TimeProvider mocked; pace boundaries at exactly 90/110, day-1, zero-spending edge cases |
| 72 | domain/analytics/SpendingPaceCalculatorDeepTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 6 | 0 | HIGH | Only TimeProvider mocked; canonical pace formula, blended smoothing, day-1 projection |
| 73 | domain/analytics/SpendingPaceCalculatorValidationTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 14 | 0 | HIGH | Only TimeProvider mocked; 14 validation scenarios covering daily rate comparisons, projections |
| 74 | domain/analytics/SpendingPaceGoldenTest.kt | GOLDEN | P1_HIGH | KEEP | 2 | 0 | HIGH | Uses golden March+February fixture data; verifies expected totals, pace%, projection at day 15 and 31 |
| 75 | domain/analytics/SpendingPersonalityClassifierTest.kt | MULTI_PIPELINE_SCENARIO | P2_MEDIUM | KEEP | 14 | 0 | MEDIUM | Comprehensive classifier with mocked engines; tests all personality types + edge cases |
| 76 | domain/analytics/SpendingThresholdCalculatorTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 11 | 0 | HIGH | Mocked DAO but real P90 percentile math + cache keying verified |
| 77 | domain/analytics/TotalsAggregationEngineDeepTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 5 | 0 | HIGH | Category percentage-of-total verified against grand total; period boundary completeness |
| 78 | domain/analytics/TotalsAggregationEngineTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 43 | 0 | HIGH | 43 tests covering monthly/weekly/daily/yearly totals + period status; biggest engine test |
| 79 | domain/analytics/TotalsAggregationEngineValidationTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 18 | 0 | HIGH | Manual sum verification, boundary transaction inclusion, category percentage math |
| 80 | domain/analytics/TransferDirectionAnalyticsTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 5 | 0 | HIGH | No mocks — real TransferDirectionAnalytics state machine; correction idempotency verified |
| 81 | domain/analytics/fixtures/ExpectedResults.kt | FIXTURE_INFRASTRUCTURE | P2_MEDIUM | KEEP | 0 | 0 | HIGH | Oracle values for golden tests; used by verification tests — not a test file itself |
| 82 | domain/analytics/fixtures/GoldenDataSets.kt | FIXTURE_INFRASTRUCTURE | P2_MEDIUM | KEEP | 0 | 0 | HIGH | Synthetic data fixtures used by SpendingPaceGoldenTest, CategoryInsightEngineTest, etc. |
| 83 | domain/bank/BankApiIntegrationTest.kt | PARSER | P2_MEDIUM | KEEP | 2 | 0 | HIGH | Reflection-based test of private mapTransactionToExpense; verifies debit→Purchase, credit→Deposit mapping |
| 84 | domain/budget/BudgetAutopilotEngineTest.kt | MULTI_PIPELINE_SCENARIO | P2_MEDIUM | KEEP | 13 | 0 | MEDIUM | Autopilot recommendation generation with mocked sub-engines |
| 85 | domain/budget/BudgetCalculatorBoundaryTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 16 | 0 | HIGH | Only TimeProvider mocked; tests anchor-day boundary coercion (31→28/29 for Feb), DST safety |
| 86 | domain/budget/BudgetCalculatorGoldenTest.kt | GOLDEN | P1_HIGH | KEEP | 3 | 0 | HIGH | Calendar-mode, rolling monthly, yearly anniversary; precise date math verification |
| 87 | domain/budget/BudgetCalculatorTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 16 | 0 | HIGH | Only TimeProvider mocked; DAILY/WEEKLY/MONTHLY/YEARLY period windows, anchor alignment |
| 88 | domain/budget/BudgetForecastingEngineStubTest.kt | MOCK_ORCHESTRATION | P4_NEGATIVE_VALUE | DELETE | 2 | 0 | HIGH | Tests a no-op stub that performs zero writes; verifies only that nothing happens — zero value |
| 89 | domain/budget/BudgetForecastingEngineTest.kt | MULTI_PIPELINE_SCENARIO | P1_HIGH | KEEP | 21 | 0 | HIGH | Forecasting engine with trend computation, risk assessment, Monte Carlo simulation |
| 90 | domain/budget/BudgetHistorySeriesBuilderTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 2 | 0 | HIGH | No mocks — pure series builder; half-open window + zero-fill logic verified |
| 91 | domain/budget/BudgetMonitorStressTest.kt | MOCK_ORCHESTRATION | P3_LOW | MOVE_TO_NIGHTLY | 11 | 0 | MEDIUM | Not @Ignored but adds marginal value over BudgetMonitorTest; stress scenarios of monitor |
| 92 | domain/budget/BudgetMonitorTest.kt | WORKER_RUNTIME | P2_MEDIUM | KEEP | 4 | 0 | HIGH | Tests budget warning notification dispatch + throttle + timestamp update |
| 93 | domain/budget/BudgetRecommendationEngineTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 5 | 0 | HIGH | No mocks — real engine; CRITICAL/MEDIUM/LOW risk recommendation generation verified |
| 94 | domain/budget/BudgetTrendBoundaryTest.kt | MOCK_ORCHESTRATION | P3_LOW | KEEP | 1 | 0 | LOW | Single test of trend computation boundary; thin |
| 95 | domain/budget/SharedBudgetManagerTest.kt | REPOSITORY_INTEGRATION | P2_MEDIUM | KEEP | 14 | 0 | HIGH | Uses real BudgetCalculator; tests shared-expense splits, contributions, settlement calculations |
| 96 | domain/business/BusinessExpenseReportGeneratorTest.kt | MULTI_PIPELINE_SCENARIO | P1_HIGH | KEEP | 38 | 0 | HIGH | 38 tests enforcing purchase-only semantics; totals, rankings, receipt gaps, CSV export all verified |
| 97 | domain/carbon/CarbonFootprintCalculatorTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 23 | 0 | HIGH | Mocked DAO but real emission factor math; emission totals, daily averages, category factors |
| 98 | domain/cashflow/CashFlowCalculatorTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 9 | 0 | MEDIUM | Real Calculator with mocked data; daily cashflow projection + recurring bill accumulation |
| 99 | domain/categorization/CategorizationComponentsTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 40 | 0 | HIGH | Multiple test classes in one file; MerchantCanonicalizer tested with real instantiation + greek characters |
| 100 | domain/categorization/CategorizationEngineDebugTest.kt | MOCK_ORCHESTRATION | P3_LOW | KEEP | 2 | 0 | LOW | Fully mocked debug engine tests; low signal |
| **TOTALS** | — | — | — | — | **698** | **5** (class-level) | — | **KEEP: 90, MOVE_TO_NIGHTLY: 7, DELETE: 1, REWRITE: 0, MOVE: 0, UNKNOWN: 0** |

---

## Action Summary

| Action | Count | Files |
|---|---|---|
| **KEEP** | 90 | All others |
| **MOVE_TO_NIGHTLY** | 7 | #2, #11, #12, #14, #17, #91 |
| **DELETE** | 1 | #88 |
| **REWRITE** | 0 | — |
| **MOVE** | 0 | — |

---

## Detailed Notes

### MOVE_TO_NIGHTLY (7 files)

These tests are stress/smoke tests that are either `@Ignore`-annotated (not run in CI) or add minimal value over existing companion test classes. They should be moved to a nightly/long-running suite where execution time is less critical.

---

**#2 — ExpenseRepositoryStressTest.kt**
- **Current:** 13 tests, `@Ignore("Stress test: may hang in CI, run manually")` on class
- **Why:** Entirely mock-based with relaxed mocks; delegates to ExpenseRepository which is already covered by ExpenseRepositoryTest (#3, 9 tests) and ExpenseRepositoryTruncationTest (#4, 9 tests). The "stress" label is misleading — this is just additional edge-case testing on mocks without real resource contention.
- **Recommendation:** Move to `testNightly/` or keep `@Ignore`-d. If it genuinely exercises timeout/hang scenarios, it needs a real database or coroutine timeout, not mocks.
- **Contingent on:** ExpenseRepositoryTest + ExpenseRepositoryTruncationTest staying in CI.

---

**#11 — NotificationProcessingPipelineStressTest.kt**
- **Current:** 28 tests, `@Ignore("Stress test: may hang in CI, run manually")` on class
- **Why:** Reimplements pipeline simulation logic outside the real `NotificationProcessingPipeline` (calls `runPipeline()` / `makeRoutingDecision()` helpers that bypass the production codepath). Shallow assertions ("should process through pipeline"). Real pipeline coverage lives in NotificationProcessingPipelineReliabilityTest (#10, 17 tests) and NotificationProcessingPipelineOversizedAmountTest (#9, 8 tests).
- **Recommendation:** Move to nightly or delete the simulated helpers and rewrite against the real pipeline.

---

**#12 — NotificationRepositoryStressTest.kt**
- **Current:** 21 tests, `@Ignore("Stress test: may hang in CI, run manually")` on class
- **Why:** Thin flow-delegation tests (e.g., `assertNotNull(result)` on Flow returns). No real stress (no large-data, no concurrency, no timeout). Duplicates basic DAO smoke that should exist in an instrumentation test.
- **Recommendation:** Move to nightly. Replace with a single instrumented Room smoke test that exercises the real DAO with 1000+ rows.

---

**#14 — ReceiptRepositoryStressTest.kt**
- **Current:** 12 tests, `@Ignore("Stress test: may hang in CI, run manually")` on class, depends on `RobolectricTestRunner`
- **Why:** Fully mocked with relaxed mocks. The non-stress variant (#13 ReceiptRepositoryStatementDuplicateTest) uses actual Robolectric and tests real logic. This stress variant adds no additional contract protection.
- **Recommendation:** Move to nightly. The heavy receipt processing path should be a real Android instrumentation test if stress is the goal.

---

**#17 — ReviewQueueRepositoryStressTest.kt**
- **Current:** 8 tests, `@Ignore("Stress test: may hang in CI, run manually")` on class
- **Why:** Thin DAO delegation tests (getPendingReviews returns flow, getReviewById returns null, etc.) with assertNotNull/assertNull. The real review queue contract is tested in ReviewQueueRepositoryTest (#18, 11 tests) with meaningful state transitions.
- **Recommendation:** Move to nightly or delete entirely if the companion test (#18) provides sufficient coverage.

---

**#91 — BudgetMonitorStressTest.kt**
- **Current:** 11 tests, **NOT** `@Ignore`-d (runs in CI currently)
- **Why:** Tests budget monitoring under concurrent operations (CancellationException, rapid checkBudgets calls, notification flood dedup). While not @Ignored, this adds marginal coverage over BudgetMonitorTest (#92, 4 tests) that tests the core warning dispatch path. The stress scenarios here are well-structured (concurrent checks, exception resilience) and should be preserved but moved.
- **Recommendation:** Move to nightly to keep CI fast. The parent BudgetMonitorTest covers the happy path + warning threshold.

---

### DELETE (1 file)

---

**#88 — BudgetForecastingEngineStubTest.kt**
- **Current:** 2 tests
- **Why:** This test verifies that `updateForecastAccuracy` is a no-op stub that performs no writes (`coVerify(exactly = 0)` on DAO methods). It tests the **absence** of behavior — literally verifying that nothing happens. This is dead-code documentation disguised as a test.
- **Evidence:**
  ```kotlin
  engine.updateForecastAccuracy(forecastId = 123L, actualSpending = 456.78)
  verify(exactly = 1) { budgetForecastDao.getForecastsForBudget(123L) }
  coVerify(exactly = 0) { budgetForecastDao.update(any()) }
  coVerify(exactly = 0) { budgetForecastDao.insert(any()) }
  ```
  The test's own comment says `"update forecast accuracy is currently a no op stub"`.
- **Recommendation:** DELETE. If the method is intentionally a stub, it should be documented in code comments, not guarded by a test that proves it does nothing. When real accuracy-update logic is added, write a proper test.

---

### Noteworthy KEEP decisions

**#21 — SecureKeyStorageTest.kt:** Flagged as having a tautological mock (line 77 TODO: "Tautological mock test — consider adding real behavior assertion"). KEPT because:
- It tests the full SecureKeyStorage API surface (store, get, has, delete, clear, keys, migration)
- The contract being tested (encryption roundtrip, null handling, missing key) is security-critical
- The improvement would be using real EncryptedSharedPreferences or an integration test, not deletion

**#19 — SavingsContributionHistoryRepositoryTest.kt:** Uses real temp-file-backed DataStore with real persistence across repository recreation. This is the gold standard for what a repository test should look like — no mocks on the persistence layer.

**#7 — MerchantRulesRepositoryTest.kt:** Only 4 tests, no mocks, tests real string sanitization. Exemplary small test.

**#74 — SpendingPaceGoldenTest.kt:** Only 2 tests but uses golden fixture data with pre-calculated expected values (991.79 spent, 2049.70 projected, 175% pace). High-confidence oracle test.

**#88 (DELETE) vs #51 (KEEP):** `BudgetForecastingEngineStubTest` tests the absence of side effects (DELETE-worthy). `SyncProactiveBriefingWorkUseCaseTest` also uses pure mock verification but tests a real scheduling contract — the test confirms `scheduleDailyBriefing()` vs `cancelDailyBriefing()` is called under different settings. That IS a real contract even if mock-verified.

---

## Integrity Score

| Metric | Value |
|---|---|
| Total `@Test` methods | 698 |
| Class-level `@Ignore` | 5 (all stress tests) |
| Files with real assertions (not just `assertNotNull`) | 87 / 100 |
| No-mock pure-engine tests | ~35 |
| Golden/oracle tests | 4 (#69, #74, #86, #82 fixtures) |
| Files recommended for removal/move | 8 (7 MOVE_TO_NIGHTLY + 1 DELETE) |
| Files recommended KEEP | 90 |
| Net CI savings | ~115 test methods removed from CI critical path |
| CI critical P1_HIGH tests retained | 31 |
