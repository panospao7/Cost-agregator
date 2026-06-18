# Test Suite Quality Audit

_Sample-based audit from the file inventory + representative file reads. Counts are high-confidence estimates, not a full AST pass._

## Summary
- 🟢 Meaningful: ~190 files
- 🟡 Marginal: ~180 files
- 🔴 Trivial: ~15 files
- ⚫ Dead: 35 files
- ⚪ Infrastructure: 15 files

## Detailed Classification

### 🟢 Meaningful Tests (keep & fix)
| # | File | @Tests | What It Validates |
|---|------|--------|-------------------|
| 1 | data/ai/provider/CloudCategorizationAssistServiceTest.kt | 10 | Retry behavior, prompt redaction, alias mapping, API failure handling |
| 2 | data/repository/BudgetRepositoryHistoricalStatusTest.kt | 2 | Historical evaluation time vs current time, category/non-category budget derivation |
| 3 | data/repository/BudgetRepositorySuggestionsBatchTest.kt | 1 | Batched category spend query, avoids per-category N+1 calls |
| 4 | domain/analytics/TotalsAggregationEngineTest.kt | 12 | Month/week/day aggregation, zero-fill behavior, category breakdown rules |
| 5 | domain/parser/TransferDirectionDetectorTest.kt | 52 | Incoming/outgoing direction parsing, Greek/English patterns, account extraction |
| 6 | e2e/AnalyticsPipelineTest.kt | 5 | End-to-end analytics totals, pacing, anomaly detection on real golden data |
| 7 | verification/GoldenMasterVerificationTest.kt | 22 | Cross-engine parity on canonical datasets and divergence checks |
| 8 | consistency/CrossParserConsistencyTest.kt | 8 | Parser output consistency and merchant-key alignment across parsers |

### 🟡 Marginal Tests (keep, low priority)
| # | File | @Tests | Why Marginal |
|---|------|--------|--------------|
| 1 | integration/CategorizationPipelineIntegrationTest.kt | 19 | Mostly asserts non-empty outputs / determinism; shallow end-to-end coverage |
| 2 | ui/screens/home/HomeViewModelRecommendationTest.kt | 10 | Several tests only verify mocked calls or state plumbing, not real behavior |
| 3 | domain/analytics/AdvancedAnalyticsEngineStressTest.kt | 18+ | Pure math done inside the test; duplicates production-style calculations |
| 4 | domain/analytics/RecurringIntervalLogicTest.kt | 1 | Tests rounding/range math in the test body, not app code |
| 5 | ui/screens/transactions/TransactionsScreenTest.kt | 1 | Reads source text and asserts strings; brittle implementation check |
| 6 | domain/logic/RecurringExpenseEngineTest.kt | 14 | Likely useful but heavily implementation-coupled (based on naming/pattern) |
| 7 | domain/parser/GenericTransactionParserTest.kt | 21 | Real parser coverage, but likely high overlap with other parser suites |

### 🔴 Trivial Tests (candidate for removal)
| # | File | @Tests | Why Trivial |
|---|------|--------|-------------|
| 1 | domain/analytics/AdvancedAnalyticsEngineStressTest.kt | 18+ | Reimplements mean/median/stddev/percentile logic directly in the test |
| 2 | domain/analytics/RecurringIntervalLogicTest.kt | 1 | Only checks rounding buckets and truncation in test code |
| 3 | ui/screens/transactions/TransactionsScreenTest.kt | 1 | Source-string assertions against implementation text |
| 4 | domain/location/AreaSpendingEngineTest.kt | 1 | Single-test files of unknown depth; likely thin without edge-case checks |
| 5 | domain/model/FinancialForecastModelTest.kt | 2 | Model-level shape checks are often low-value unless invariants are exercised |

### ⚫ Dead Tests (candidate for removal)
| # | File | @Tests | Why Dead |
|---|------|--------|----------|
| 1 | consistency/ConcurrencyStateRaceTest.kt | 4 | @Ignore'd stress suite |
| 2 | consistency/CrossParserConsistencyStressTest.kt | 4 | @Ignore'd stress suite |
| 3 | consistency/SharedUtilityConsistencyStressTest.kt | 6 | @Ignore'd stress suite |
| 4 | data/database/entity/ExpenseEntityStressTest.kt | 42 | @Ignore'd stress suite |
| 5 | data/repository/BudgetRepositoryStressTest.kt | 18 | @Ignore'd stress suite |
| 6 | data/repository/ExpenseRepositoryStressTest.kt | 13 | @Ignore'd stress suite |
| 7 | data/repository/NotificationProcessingPipelineStressTest.kt | 28 | @Ignore'd stress suite |
| 8 | data/repository/NotificationRepositoryStressTest.kt | 21 | @Ignore'd stress suite |
| 9 | data/repository/ReceiptRepositoryStressTest.kt | 12 | @Ignore'd stress suite |
| 10 | data/repository/ReviewQueueRepositoryStressTest.kt | 8 | @Ignore'd stress suite |
| 11 | data/location/CompositeGeocodingServiceStressTest.kt | 8 | @Ignore'd stress suite |
| 12 | domain/export/CsvEscapingTest.kt | 25 | @Ignore'd |
| 13 | domain/location/TravelDetectionEngineStressTest.kt | 7 | @Ignore'd stress suite |
| 14 | domain/logic/CustomSplitParserTest.kt | 12 | @Ignore'd |
| 15 | domain/split/SplitCalculationPrecisionTest.kt | 22 | @Ignore'd |
| 16 | domain/tax/TaxCalculationTest.kt | 35 | @Ignore'd |
| 17 | domain/util/MoneyTest.kt | 31 | @Ignore'd |
| 18 | ui/screens/analytics/AnalyticsStateStressTest.kt | 20 | @Ignore'd stress suite |
| 19 | ui/screens/analytics/AnalyticsViewModelStressTest.kt | 8 | @Ignore'd stress suite |
| 20 | ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt | 19 | @Ignore'd stress suite |
| 21 | ui/screens/review/ReviewViewModelStressTest.kt | 37 | @Ignore'd stress suite |
| 22 | ui/screens/transactions/TransactionsViewModelStressTest.kt | 16 | @Ignore'd stress suite |
| 23 | ui/screens/map/SpendingMapViewModelStressTest.kt | 10 | @Ignore'd stress suite |

### ⚪ Infrastructure Files
| # | File | Purpose |
|---|------|---------|
| 1 | AnalyticsEngineTestBase.kt | Shared analytics test base/mocks |
| 2 | TestUtils.kt | Shared test data + assertions |
| 3 | e2e/FlowPipelineTestHarness.kt | End-to-end pipeline builder/harness |
| 4 | domain/budget/BudgetForecastingEngineStubTest.kt | Stub implementation fixture |
| 5 | domain/util/AmountUtilsTest.kt | Test utility fixture |
| 6 | domain/util/AmountUtilsStressTest.kt | Test utility fixture |
| 7 | domain/util/StatisticsUtilsStressTest.kt | Test utility fixture |
| 8 | domain/util/StringDistanceUtilsStressTest.kt | Test utility fixture |
| 9 | domain/util/TimePeriodUtilsStressTest.kt | Test utility fixture |
| 10 | domain/util/TimePeriodUtilsTest.kt | Test utility fixture |
| 11 | domain/util/TimePeriodUtilsValidationTest.kt | Test utility fixture |
| 12 | util/FlowTestUtils.kt | Shared test utilities |
| 13 | util/HiltTestUtils.kt | Shared test utilities |
| 14 | util/ViewModelTestUtils.kt | Shared test utilities |
| 15 | e2e/FlowPipelineTestHarness.kt | Pipeline harness |

## Red Flags Found
- 0 files with empty test bodies observed in sampled review
- 4+ files with tautological mock assertions or “verify the mock I just invoked” patterns
- 3 files that mostly test constants/language math/source text rather than app behavior
- Many duplicate-coverage clusters: analytics totals/pace, parser direction detection, recommendation/navigation plumbing, and stress-suite math duplicates

## Notes
- The best investment targets are: repository tests, parser tests, analytics verification/golden-master suites, and end-to-end pipeline tests.
- The worst noise is concentrated in ignored stress suites and tests that re-implement logic inside the test body.
