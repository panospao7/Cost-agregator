# Shared Components and Consistency

This document lists shared utilities used by multiple components and the tests that verify cross-consumer consistency.

## Shared Utilities

### MerchantKeyGenerator
**Purpose:** Canonical merchant identity key (Greek→Latin, lowercase, alphanumeric only).

**Consumers:**
- `ExpenseRepository` — when creating/updating expenses
- `ReviewQueueRepository` — when approving reviews
- `ManualExpenseRepository` — manual entry
- `ReceiptRepository` — OCR/statement imports
- `NotificationProcessingPipeline` — notification parsing
- `TransactionsViewModel` — filter by merchant
- `LocationResolver` — cache key for geocoding
- `MerchantLocationRepository` — normalizeKey()
- `MerchantKeyBackfillWorker` — backfill legacy rows
- `MerchantNormalizer` — createSearchKey()
- `Expense.generateDedupeKey()` — deduplication

**Consistency tests:** `SharedUtilityConsistencyTest`, `MerchantKeyCrossConsumerConsistencyTest`, `CrossParserConsistencyTest`

---

### MerchantCleaner
**Purpose:** Clean raw merchant strings (remove time, date, card info, stop words).

**Consumers:**
- `GenericTransactionParser`, `RevolutParser`, `GreekBankParser`, `SmsParser`, `GoogleWalletParser`
- `BankStatementParser`
- `LocationResolver` — before MerchantKeyGenerator

**Consistency tests:** `CrossParserConsistencyTest`, `CategorizationPipelineIntegrationTest`

---

### AmountUtils / AmountExtractionUtils / CommonPatterns
**Purpose:** Parse monetary amounts from text.

**Consumers:**
- `AmountUtils.parseAmount()` — used by parsers, validation
- `AmountExtractionUtils.extractFirstAmount()` — used by AmountExtractionUtils.extractAmount()
- `CommonPatterns.AMOUNT_REGEX` — used by GenericTransactionParser, BankStatementParser

**Consistency tests:** `SharedUtilityConsistencyTest`

---

### CrossSourceDeduplication
**Purpose:** Detect duplicate transactions across sources (notification, statement, manual, OCR).

**Consumers:**
- `DetectDuplicateExpenseUseCase` — manual entry
- `ReceiptRepository` — OCR/statement imports (Expense + PendingReview checks)
- `ReviewQueueRepository` — approve flow (indirect)

**Consistency tests:** `DuplicateLogicConsistencyIntegrationTest`

---

### CurrencyNormalizer
**Purpose:** Map currency symbols/codes to ISO 4217 (e.g. "E" → "EUR").

**Consumers:**
- All parsers (GenericTransactionParser, RevolutParser, GreekBankParser, SmsParser, GoogleWalletParser)
- BankStatementParser

**Consistency tests:** `CurrencyNormalizerConsistencyTest`

---

### GeoUtils (haversineKm)
**Purpose:** Great-circle distance between WGS-84 coordinates (km or meters).

**Consumers:**
- `TravelDetectionEngine`, `CrossSourceDeduplication` — use GeoUtils
- `CompositeGeocodingService`, `OverpassNearbyService`, `MerchantLocationRepository` — have local haversine (should migrate to GeoUtils)

**Consistency tests:** `HaversineConsistencyTest`

---

### MerchantCleaner vs MerchantRulesRepository
**Purpose:** Both feed into MerchantKeyGenerator; parser path uses MerchantCleaner, categorization path uses MerchantRulesRepository.

**Consistency tests:** `MerchantKeyConsistencyTest`

---

### TimePeriodUtils
**Purpose:** Standardize date range calculations (getMonthRange, getLastNDaysRange, etc.).

**Consumers:**
- `AnalyticsViewModel`, `TransactionsViewModel`, `FinancialWeatherRepository`, `InsightsEngine`, `BudgetCalculator`, `AdvancedAnalyticsEngine`

**Consistency tests:** `TimePeriodAnalyticsAlignmentTest`, `TimePeriodAlignmentTest`

---

## Running Consistency Tests

```bash
./gradlew :app:testDebugUnitTest --tests "com.yourname.expensetracker.consistency.*"
```

## effectiveAmount (Expense property)

**Purpose:** The amount that counts toward the user's own spending. Excludes isNotMine (0), uses myShareAmount or mySharePercentage for shared expenses.

**Consumers:** All engines that aggregate spending:
- SpendingPaceCalculator, MonthlyComparisonCalculator, DayOfWeekAnalyzer
- InsightsEngine, AdvancedAnalyticsEngine, CategoryInsightEngine, MerchantInsightEngine
- TravelDetectionEngine, AreaSpendingEngine
- ComputeDashboardWidgetsUseCase, CalculateFinancialForecastUseCase

**Consistency tests:** `EffectiveAmountConsistencyTest`, `EffectiveAmountConsistencyStressTest`

---

## Adding New Consumers

When adding a new consumer of a shared utility:
1. Use the shared utility directly — do not reimplement logic
2. Add a consistency test that verifies the new consumer produces the same output as existing consumers for the same input
3. Update this document with the new consumer
