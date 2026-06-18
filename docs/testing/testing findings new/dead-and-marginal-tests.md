# Dead tests
*(23 explicitly named; the audit estimates ~35 dead total overall)*

- `consistency/ConcurrencyStateRaceTest.kt`
- `consistency/CrossParserConsistencyStressTest.kt`
- `consistency/SharedUtilityConsistencyStressTest.kt`
- `data/database/entity/ExpenseEntityStressTest.kt`
- `data/repository/BudgetRepositoryStressTest.kt`
- `data/repository/ExpenseRepositoryStressTest.kt`
- `data/repository/NotificationProcessingPipelineStressTest.kt`
- `data/repository/NotificationRepositoryStressTest.kt`
- `data/repository/ReceiptRepositoryStressTest.kt`
- `data/repository/ReviewQueueRepositoryStressTest.kt`
- `data/location/CompositeGeocodingServiceStressTest.kt`
- `domain/export/CsvEscapingTest.kt`
- `domain/location/TravelDetectionEngineStressTest.kt`
- `domain/logic/CustomSplitParserTest.kt`
- `domain/split/SplitCalculationPrecisionTest.kt`
- `domain/tax/TaxCalculationTest.kt`
- `domain/util/MoneyTest.kt`
- `ui/screens/analytics/AnalyticsStateStressTest.kt`
- `ui/screens/analytics/AnalyticsViewModelStressTest.kt`
- `ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt`
- `ui/screens/review/ReviewViewModelStressTest.kt`
- `ui/screens/transactions/TransactionsViewModelStressTest.kt`
- `ui/screens/map/SpendingMapViewModelStressTest.kt`

# Marginal tests

- `integration/CategorizationPipelineIntegrationTest.kt` — mostly shallow end-to-end checks
- `ui/screens/home/HomeViewModelRecommendationTest.kt` — lots of mocked plumbing
- `domain/analytics/AdvancedAnalyticsEngineStressTest.kt` — logic duplicated in the test body
- `domain/analytics/RecurringIntervalLogicTest.kt` — rounding/range math in the test itself
- `ui/screens/transactions/TransactionsScreenTest.kt` — source-text / brittle implementation assertions
- `domain/logic/RecurringExpenseEngineTest.kt` — useful, but implementation-coupled
- `domain/parser/GenericTransactionParserTest.kt` — real coverage, but overlaps heavily

## Overlap note
These 3 are also listed in the **trivial** bucket:
- `domain/analytics/AdvancedAnalyticsEngineStressTest.kt`
- `domain/analytics/RecurringIntervalLogicTest.kt`
- `ui/screens/transactions/TransactionsScreenTest.kt`

## Source
- https://github.com/panospao7/Cost-agregator/blob/31d9e1bbb10976b648788b91fd1922aa3564759a/docsplans/test-suite-quality-audit.md