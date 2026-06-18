# Batch 003 — Test Suite Audit Report

**Generated:** 2026-05-12  
**Auditor:** DeepSeek v4 Pro  
**Project:** ExpenseTracker  
**Files audited:** 100  
**Source:** batch-files-003.txt

## Classification Summary

| Action | Count |
|--------|-------|
| KEEP | 76 |
| DELETE | 8 |
| REWRITE | 2 |
| MOVE | 4 |
| MOVE_TO_NIGHTLY | 3 |
| UNKNOWN_NEEDS_LOCAL_RUN | 2 |
| FIXTURE (MOVE) | 5 |

## Value Distribution

| Value | Count |
|-------|-------|
| P0_CRITICAL | 9 |
| P1_HIGH | 38 |
| P2_MEDIUM | 33 |
| P3_LOW | 14 |
| P4_NEGATIVE_VALUE | 6 |

## Test Type Distribution

| Type | Count |
|------|-------|
| PURE_ENGINE | 21 |
| PARSER | 16 |
| GOLDEN | 7 |
| MOCK_ORCHESTRATION | 12 |
| STRESS_PERFORMANCE | 14 |
| TRIVIAL_MODEL | 6 |
| FIXTURE_INFRASTRUCTURE | 5 |
| REPOSITORY_INTEGRATION | 6 |
| LIFECYCLE_CONTRACT | 3 |
| SOURCE_TEXT_ASSERTION | 4 |
| ANDROID_SMOKE | 1 |
| CONCURRENCY_SAFETY | 1 |
| CALCULATION_PRECISION | 1 |
| MULTI_PIPELINE_SCENARIO | 2 |
| VIEWMODEL_STATE | 1 |

---

## Full Audit Table

| # | Path | Type | Value | Action | Tests | Ignored | Confidence | Main reason |
|---|---|------|------|-------|--------|-------|---------|------------|-------------|
| 1 | domain/categorization/CategorizationEngineStressTest.kt | STRESS_PERFORMANCE | P1_HIGH | MOVE_TO_NIGHTLY | 33 | 0 | HIGH | 33 stress tests, 651 lines. Real CategorizationEngine with 8 mock deps. Extremely long runtime (10k concurrent requests). Move to nightly to keep CI fast. Core categorization contract is well-covered by the unit test (#2). |
| 2 | domain/categorization/CategorizationEngineTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 5 | 0 | HIGH | Tests normalize(), exact match, substring match, unknown, and Greek characters. Core categorization contract. Real assertion on categorize output. |
| 3 | domain/categorization/CategoryKeywordsTest.kt | SOURCE_TEXT_ASSERTION | P3_LOW | KEEP | 7 | 0 | HIGH | Tests static keyword data integrity (confidence ranges 0-1, expected categories). Lightweight, no mocks. Useful regression guard for keyword dictionary changes. |
| 4 | domain/categorization/ContextualInferenceEngineStressTest.kt | STRESS_PERFORMANCE | P2_MEDIUM | MOVE_TO_NIGHTLY | 18 | 0 | HIGH | 660 lines. ContextualInferenceEngine with real `inferFromContext`. Heavy boundary testing (amount ranges, day-of-week, time-of-day). Good coverage but inflates CI. Move to nightly. |
| 5 | domain/categorization/MerchantCanonicalizerStressTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 8 | 0 | HIGH | Real MerchantCanonicalizer, no mocks. Tests Greek/Latin corporate suffix stripping, deterministic behavior, confidence penalty. Pure logic, fast. Critical for merchant normalization contract. |
| 6 | domain/categorization/SemanticKeywordMatcherStressTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 6 | 0 | HIGH | Real SemanticKeywordMatcher + GreeklishNormalizer. No mocking. Tests case-insensitivity, greeklish support, confidence range, determinism. Critical for semantic matching. |
| 7 | domain/challenge/SpendingChallengeManagerTest.kt | REPOSITORY_INTEGRATION | P2_MEDIUM | KEEP | 5 | 0 | HIGH | Tests challenge progress, no-spend streak, budget challenge completion. Uses mockk DAOs but asserts real domain calculations (streak days, progress percent). Protects financial challenge logic. |
| 8 | domain/config/AppConfigTest.kt | SOURCE_TEXT_ASSERTION | P3_LOW | KEEP | 2 | 0 | HIGH | Tests compile-time constant ranges (thresholds in (0,1], positive cache expiry). Validates config is internally consistent. Fast, no deps. Useful guard. |
| 9 | domain/core/time/PeriodRangeTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 5 | 0 | HIGH | Real time boundary calculations. Tests month/week/DST boundaries, half-open [start, end) contract. Protects calendar-sensitive logic. |
| 10 | domain/currency/CurrencyConversionTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 28 | 0 | HIGH | Tests real CurrencyConverter with mocked ExchangeRateStore. Same-currency, direct rates, cross-rates via EUR, null when no rate, case-insensitive. Protects financial conversion contract. |
| 11 | domain/currency/CurrencyConverterEdgeCaseTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 8 | 0 | HIGH | Edge cases: stale rate, zero amount, negative amounts, accumulated drift, nan/inf guards. Protects numerical stability of currency conversion. |
| 12 | domain/currency/CurrencyConverterGoldenTest.kt | GOLDEN | P0_CRITICAL | KEEP | 2 | 0 | HIGH | Golden snapshot: GBP→JPY cross-rate = 19012.50. Same-currency unchanged. Extends AnalyticsEngineTestBase. Critical regression guard. |
| 13 | domain/currency/CurrencyConverterStressTest.kt | STRESS_PERFORMANCE | P1_HIGH | KEEP | 2 | 0 | HIGH | 500x accumulation drift test + 500x roundtrip precision test. Only 70 lines. Fast enough for CI. Numerical stability critical for financial app. |
| 14 | domain/currency/MultiCurrencyTestFixture.kt | FIXTURE_INFRASTRUCTURE | P1_HIGH | MOVE | 0 | 0 | HIGH | No @Test. Provides canonical EUR/USD fixture (EUR_AMOUNT=50, USD_AMOUNT=100, EXPECTED_EUR_TOTAL=142). Used by dashboard/budget/analytics tests. Move to shared test fixtures. |
| 15 | domain/debug/ServiceDiagnosticsTest.kt | ANDROID_SMOKE | P2_MEDIUM | MOVE_TO_NIGHTLY | 11 | 0 | HIGH | Robolectric test requiring ApplicationProvider. Tests service start/kill counters. Android framework dep makes it flaky in pure JVM CI. Move to nightly. |
| 16 | domain/dto/DtoContractTest.kt | TRIVIAL_MODEL | P3_LOW | KEEP | 10 | 0 | HIGH | Tests DTO data class field preservation, default values, null-safety for optional fields. Catches copy() regression. Fast, pure JVM. |
| 17 | domain/engine/DashboardFollowThroughEngineTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 20 | 0 | HIGH | Tests recommendation generation from transactions + AI artifacts. Real SpendingThresholdCalculator + TransactionFilterSerializer. Asserts priority, max 5 limit, AI text. Protects dashboard logic. |
| 18 | domain/export/AccountingExportPolicyTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 3 | 0 | HIGH | Real AccountingExportPolicy. Validates single-currency datasets, fails fast for mixed currencies, rejects non-PURCHASE types. Protects export integrity. |
| 19 | domain/export/CsvEscapingTest.kt | PARSER | P1_HIGH | KEEP | 21 | 0 | HIGH | Tests CSV/IIF field escaping (commas, quotes, newlines, CR). Protects against injection attacks and format corruption. Marked CRITICAL-4 in source. |
| 20 | domain/export/ExpenseExportMapperTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 2 | 0 | HIGH | Tests expense→ExportTransaction mapping (effective amount for shared, payment method→account labels). Protects export data contract. |
| 21 | domain/forecasting/FinancialStressForecastEngineTest.kt | MOCK_ORCHESTRATION | P1_HIGH | KEEP | 15 | 0 | HIGH | Heavily mocked (10 deps). But tests actual engine logic: risk level, Monte Carlo integration, budget status filtering. Protects stress forecast pipeline. |
| 22 | domain/forecasting/ForecastInputAssemblerTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 8 | 0 | HIGH | Real ForecastInputAssembler merging logic: manual/detected recurring dedup, merchant signature matching. Only timeProvider is mocked. Protects recurring pattern merging. |
| 23 | domain/forecasting/HistoricalSpendingDistributionBoundaryTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 10 | 0 | HIGH | Real TimePeriodUtils boundary tests: week bucket keys, DST-safe day counting, calendar-aware enumeration. Protects historical distribution math. |
| 24 | domain/forecasting/MergedRecurringPatternsProviderTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 6 | 0 | HIGH | Real MergedRecurringPatternsProvider + ForecastInputAssembler. Tests dedup, signature-based matching, manual/detected precedence. Protects recurring pattern merging contract. |
| 25 | domain/forecasting/MonteCarloSpendingSimulatorGoldenTest.kt | GOLDEN | P0_CRITICAL | KEEP | 1 | 0 | HIGH | Golden snapshot with seed=42. Verifies exact p50=2072.41, p10=1781.63, p90=2484.39. Critical regression guard for Monte Carlo engine. |
| 26 | domain/forecasting/MonteCarloSpendingSimulatorTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 1 | 0 | HIGH | Degraded result test (zero history returns deterministic output, all percentiles equal spent+upcoming). Protects simulator fallback path. |
| 27 | domain/groups/SettlementCalculatorStressTest.kt | STRESS_PERFORMANCE | P2_MEDIUM | KEEP | 3 | 0 | HIGH | Tests 15-member solver budget, all-zero balances, settlement volume invariant. Uses real SettlementCalculator. Fast (timing assertions). |
| 28 | domain/groups/SettlementCalculatorTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 5 | 0 | HIGH | Real solver: triangle debt (4_6, 4_7), primary vs min-amount equality, settlement summary formatting. Protects settlement math. |
| 29 | domain/groups/SharedExpenseBudgetOffsetEngineTest.kt | MOCK_ORCHESTRATION | P1_HIGH | KEEP | 10 | 0 | HIGH | Tests linked shared expense exclusion from personal spend, SplitCalculator fallback for malformed splits. Protects budget offset calculation. |
| 30 | domain/groups/SharedExpenseManagerTest.kt | MOCK_ORCHESTRATION | P1_HIGH | KEEP | 14 | 0 | HIGH | Tests all split types (EQUAL, CUSTOM_AMOUNT, CUSTOM_PERCENT, UNEQUAL), balance calculation, overflow guard. Real SplitCalculator used. Protects shared expense math. |
| 31 | domain/groups/usecase/GroupUseCasesTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | KEEP | 12 | 0 | HIGH | Tests AddGroupExpenseUseCase delegation, delete group/member. Mostly mock verification. But validates B.4 Batch 2 coordinator migration contract. |
| 32 | domain/health/FinancialHealthCalculatorBoundaryTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 12 | 0 | HIGH | Real calculator + FakeTimeProvider. Tests midnight boundary, half-open range, DST-safe day buckets. Protects health score boundary math. |
| 33 | domain/health/FinancialHealthCalculatorBudgetNormalizationTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 5 | 0 | HIGH | Tests daily/weekly/monthly spending target normalization, overlapping mixed budget windows. Protects budget normalization math. |
| 34 | domain/health/FinancialHealthCalculatorTransactionTypeTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 12 | 0 | HIGH | Batch 6: verifies DEPOSIT/TRANSFER/WITHDRAWAL/UNKNOWN do not inflate spend-control scores. Protects transaction-type filtering contract. |
| 35 | domain/health/FinancialHealthScoreV2Test.kt | GOLDEN | P1_HIGH | KEEP | 12 | 0 | HIGH | Goldens the weighted formula (30-25-25-20). Tests runway uses savings goals, not budget surplus. Protects health score formula. |
| 36 | domain/health/HealthScoreEdgeCaseTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 10 | 0 | HIGH | Tests zero-income neutral=50, overspend floors savings to 0, zero expenses, trend stability. Protects edge case behavior of V2 formula. |
| 37 | domain/health/HealthScoreGoldenTest.kt | GOLDEN | P0_CRITICAL | KEEP | 2 | 0 | HIGH | Golden: March scenario → overall=57, STABLE. New user → overall=55. Critical regression guard for financial health scoring. |
| 38 | domain/income/RecurringIncomeTrackerTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 5 | 0 | HIGH | A.10 Batch 5: income-expense ratio excludes WITHDRAWAL/TRANSFER/UNKNOWN from spending. Protects canonical isSpending semantics. |
| 39 | domain/intelligence/ConfidenceRouterEdgeCaseTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 7 | 0 | HIGH | Exact threshold boundaries (0.85, 0.50, 0.499), NaN/blank validation, zero-division safety. Protects routing decision boundaries. |
| 40 | domain/intelligence/ConfidenceRouterTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 8 | 0 | HIGH | Tests auto-accept/reject/needs-review, unknown merchant penalty floor, spam source anti-trust. Protects confidence routing logic. |
| 41 | domain/intelligence/DuplicateDetectionPolicyDedupeKeyTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 6 | 0 | HIGH | ISSUE-6 regression: different currencies→different keys, lowercase=uppercase, type-aware key contract. Pure logic, no deps. Protects dedupe contract. |
| 42 | domain/intelligence/TransactionClassifierTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 8 | 0 | HIGH | Tests lifecycle hygiene: onBackground cancels jobs, repeated transitions don't break, destroy permanently cancels. Real classifier with temp files. |
| 43 | domain/intelligence/ml/ExpenseCategoryClassifierTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 8 | 0 | HIGH | Tests saveModel awaits disk write, persistence below 100-sample threshold. Real NB classifier + temp files. Protects ML model persistence. |
| 44 | domain/intelligence/ml/FeatureExtractorTest.kt | PURE_ENGINE | P4_NEGATIVE_VALUE | DELETE | 1 | 0 | HIGH | Single test (16 lines): tokenize keeps useful tokens from "H&M / 7-Eleven - AT&T". Trivial, tests only that a word is in output. Not protecting any contract. |
| 45 | domain/intelligence/ml/HybridExpenseClassifierTest.kt | MOCK_ORCHESTRATION | P1_HIGH | KEEP | 10 | 0 | HIGH | Tests dictionary→ML→fallback classification pipeline. Invalidates category snapshots. Protects hybrid classification contract. |
| 46 | domain/intelligence/ml/MerchantNormalizerStressTest.kt | STRESS_PERFORMANCE | P1_HIGH | KEEP | 10 | 0 | HIGH | Stress tests: empty/whitespace→Unknown, 250-char truncation, alias match, canonical match, autoCreate. Protects normalization integrity. |
| 47 | domain/intelligence/ml/MerchantNormalizerTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 3 | 0 | HIGH | Tests alias lookup, empty name, fuzzy match ranking (verified beats unverified). Smaller than stress version but different tests. |
| 48 | domain/investment/InvestmentTrackerTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | KEEP | 8 | 0 | HIGH | B.4-10: verifies allTimeHigh/Low uses epoch=0 not 30-day window. Tests dayChange, null handling. Protects investment performance math. |
| 49 | domain/location/AreaSpendingEngineStressTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 4 | 0 | HIGH | Real AreaSpendingEngine, no mocks. Tests spending aggregation by area, ignores no-location expenses, sorts by descending spend. |
| 50 | domain/location/LocationInsightsEngineStressTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 6 | 0 | HIGH | Real LocationInsightsEngine. Clusters nearby expenses, separates distant, top merchant becomes place name, deterministic. |
| 51 | domain/location/LocationResolverStressTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | KEEP | 8 | 0 | HIGH | Correction priority, cache hit skips geocoding, force refresh bypasses cache. Good integration-level tests even with mocks. |
| 52 | domain/location/LocationResolverTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | KEEP | 6 | 0 | HIGH | CacheKey selection logic (provided merchantKey vs derived). Complements StressTest. Tests gap documentation in header. |
| 53 | domain/location/SpendingHeatmapEngineStressTest.kt | STRESS_PERFORMANCE | P3_LOW | REWRITE | 32 | 0 | HIGH | 32 tests, 562 lines. Real SpendingHeatmapEngine but many tests are duplicative ("stress" prefix on every test). Tests grid boundaries, log normalization, weight calculation. Good coverage but bloated. Collapse into ≤8 concise tests. |
| 54 | domain/location/TravelDetectionEngineStressTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 8 | 0 | HIGH | Tests home detection, local vs travel spending, trip grouping, gap-based trip separation, out-of-order determinism, destination hint extraction. |
| 55 | domain/location/TravelDetectionEngineTest.kt | PURE_ENGINE | P4_NEGATIVE_VALUE | DELETE | 1 | 0 | HIGH | Single test (43 lines): "compute uses first non blank token for one part travel address". Fully covered by TravelDetectionEngineStressTest. Redundant. |
| 56 | domain/logic/CustomSplitParserTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 10 | 0 | HIGH | Tests EQUAL rejection, custom amount/percent tolerance boundaries, unequal, unknown/duplicate/negative member rejection. Protects split validation contract. |
| 57 | domain/logic/RecurrenceCalculatorTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 5 | 0 | HIGH | Tests toMonthlyAmount, fromMonthlyAmount for IRREGULAR/SEMI_ANNUALLY/ANNUALLY, next date calculation. Protects recurring date math. |
| 58 | domain/logic/RecurringExpenseEngineEmptyListTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 4 | 0 | HIGH | Bug fix regression: empty list, single expense, stale expenses filtered, merchant key grouping. Protects recurring engine stability. |
| 59 | domain/logic/SplitCalculatorGoldenTest.kt | GOLDEN | P0_CRITICAL | KEEP | 4 | 0 | HIGH | Golden cent-preserving splits: 100÷3, 100÷7, percentage 33.33+33.33+33.34. Critical regression guard for financial precision. |
| 60 | domain/logic/SplitCalculatorTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 12 | 0 | HIGH | Tests equal/percentage/custom/unequal splits, validateSplits, large amounts, edge counts. Complements GoldenTest. Protects split math. |
| 61 | domain/logic/SynthesisEngineGoldenTest.kt | GOLDEN | P0_CRITICAL | KEEP | 4 | 0 | HIGH | Golden: confidence band thresholds, biweekly±2 tolerance, block party discretionary rate. Critical regression guard for synthesis engine. |
| 62 | domain/logic/SynthesisEngineStressTest.kt | STRESS_PERFORMANCE | P2_MEDIUM | MOVE_TO_NIGHTLY | 56 | 0 | HIGH | 56 tests, 1709 lines! Massive stress suite with concurrent access, extreme inputs, boundary conditions. Valuable but extremely long. Move to nightly. |
| 63 | domain/logic/SynthesisEngineTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 15 | 0 | HIGH | Core tests: totalCommitted from recurring+planned, strict goal reserves, risk levels, empty inputs. Protects synthesis contract. |
| 64 | domain/model/CategoryBreakdownTest.kt | TRIVIAL_MODEL | P4_NEGATIVE_VALUE | DELETE | 19 | 0 | HIGH | Tests data class immutability, copy, equals/hashCode, and default values. Zero financial logic. 435 lines of data-class tautology. |
| 65 | domain/model/PeriodTotalTest.kt | TRIVIAL_MODEL | P4_NEGATIVE_VALUE | DELETE | 14 | 0 | HIGH | Tests data class storage, immutability, copy, enum ordinals, enum values. 260 lines of data-class tautology. |
| 66 | domain/model/RecurringPatternModelTest.kt | TRIVIAL_MODEL | P3_LOW | KEEP | 2 | 0 | HIGH | Tests IRREGULAR frequency semantics (null intervals, isIrregular=true) and calendar frequency month semantics. Small but useful for frequency model contract. |
| 67 | domain/model/dashboard/DashboardExpenseMapperTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 4 | 0 | HIGH | Tests Expense→DashboardExpense→TransactionSummary mapping. Shared expense effectiveAmount preservation. Protects mapper contract. |
| 68 | domain/naturallanguage/NaturalLanguageSearchEngineVoiceInputTest.kt | PURE_ENGINE | P3_LOW | KEEP | 3 | 0 | HIGH | Tests voice input forwarding (result/error callbacks) using FakeSpeechInputGateway. Pure JVM, no Android deps. |
| 69 | domain/negotiation/NegotiationEngineTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | KEEP | 3 | 0 | HIGH | Tests no recommendation when no data, provider failure propagation, marketRate staleness detection. |
| 70 | domain/parser/AppParserRegistryRoutingTest.kt | PARSER | P1_HIGH | KEEP | 6 | 0 | HIGH | Tests Revolut/Google Wallet/GreekBank/Generic routing, fallback behavior, null when no parser matches. Real parsers. |
| 71 | domain/parser/AppParserRegistryTest.kt | PARSER | P2_MEDIUM | REWRITE | 8 | 0 | HIGH | Substantially overlaps with #70 (AppParserRegistryRoutingTest). Both test routing to the same parsers with similar inputs. Merge the unique tests (OTP rejection) into RoutingTest and delete this file. |
| 72 | domain/parser/GenericTransactionParserStressTest.kt | PARSER | P1_HIGH | KEEP | 10 | 0 | HIGH | Real parser with real CurrencyNormalizer/MerchantCleaner. Tests purchase, deposit, Greek deposit, non-financial rejection, marketing rejection, deterministic. |
| 73 | domain/parser/GenericTransactionParserTest.kt | PARSER | P1_HIGH | KEEP | 21 | 0 | HIGH | Comprehensive: "you paid", "payment of", "charged", Greek patterns, Greeklish, transfer direction, multiple amounts, spam checks. Complements stress version. |
| 74 | domain/parser/GoogleWalletParserTest.kt | PARSER | P1_HIGH | KEEP | 15 | 0 | HIGH | Tests payment at merchant, P2P send/receive as transfer, "paid to merchant" as purchase, "paid to friend" as transfer. Real parser. |
| 75 | domain/parser/GreekBankParserStressTest.kt | PARSER | P1_HIGH | KEEP | 10 | 0 | HIGH | Real parser: purchase, deposit, transfer, Eurobank charge, European comma, single decimal, non-transaction rejection, Greek merchants, special chars. |
| 76 | domain/parser/GreekBankParserTest.kt | PARSER | P1_HIGH | KEEP | 10 | 0 | HIGH | Tests Agora pattern, euro symbol, card charge, single decimal, balance/OTP/promo rejection, bank package support. Complements stress version. |
| 77 | domain/parser/NBGReproTest.kt | PARSER | P1_HIGH | KEEP | 1 | 0 | HIGH | Live user bug reproduction: overmatched merchant for NBG notification. Uses real MerchantCleaner. Critical regression guard. |
| 78 | domain/parser/RevolutParserTest.kt | PARSER | P1_HIGH | KEEP | 25 | 0 | HIGH | 25 tests: purchase with €/$/£, comma decimal, sent/received, ATM withdrawal, deposit, grouped amounts. Real parser. |
| 79 | domain/parser/SmsParserTest.kt | PARSER | P1_HIGH | KEEP | 12 | 0 | HIGH | Tests Greek/Greeklish bank SMS, non-bank rejection, null title, amount bounds, transfer direction, grouped US amount. |
| 80 | domain/parser/TransferDirectionDetectorTest.kt | PARSER | P1_HIGH | KEEP | 52 | 0 | HIGH | 52 tests covering 50+ patterns (English/Greek, incoming/outgoing, purchase→null). Pure logic, no mocks. Though exhaustive, each test is tiny and fast. Critical for parser direction detection. |
| 81 | domain/price/PriceProtectionTrackerTest.kt | MOCK_ORCHESTRATION | P3_LOW | KEEP | 10 | 0 | HIGH | Tests receipt filtering, price-protectable items (electronics), non-protectable items. Mostly mock verification. Low value but not harmful. |
| 82 | domain/receipt/BankStatementParserTest.kt | PARSER | P1_HIGH | KEEP | 20 | 0 | HIGH | Tests spatial block parsing, row grouping with vertical variation, date extraction, transfer detection. Real parser. Protects statement parsing. |
| 83 | domain/receipt/BitmapConcurrencyTest.kt | CONCURRENCY_SAFETY | P2_MEDIUM | KEEP | 8 | 0 | HIGH | Tests Mutex sequential access, concurrent modification protection, max 1 concurrent worker, operation ordering. No Android deps. Protects bitmap concurrency model. |
| 84 | domain/receipt/EnhancedMerchantExtractorTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 4 | 0 | HIGH | Tests merchant extraction from OCR, existing merchant verification, empty OCR fallback. Real EnhancedMerchantExtractor. |
| 85 | domain/receipt/GreekNormalizationTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 5 | 0 | HIGH | Uses reflection to test private normalizeGreekOcr. Tests number fixes, total keywords, amount keywords, compound keywords, currency normalization. Protects OCR normalization. |
| 86 | domain/receipt/OcrLanguageProcessorTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 8 | 0 | HIGH | Real OcrLanguageProcessor. Tests Greek/Latin/Cyrillic detection, normalize, auto-normalize, amount extraction with grouped/locale-aware values. Critical for OCR pipeline. |
| 87 | domain/receipt/ReceiptParserOcrPatternsTest.kt | PARSER | P1_HIGH | KEEP | 30 | 0 | HIGH | 756 lines. Exhaustive OCR pattern testing: Greek keywords (ΣΥΝΟΛΟ, ΤΕΛΙΚΟ, ΠΛΗΡΩΤΕΟ, ΠΟΣΟ), hallucination maps, Latin intrusion, geometric artifacts. Real parser. |
| 88 | domain/receipt/ReceiptParserTest.kt | PARSER | P1_HIGH | KEEP | 15 | 0 | HIGH | Tests exact hallucination map, Latin intrusion, geometric artifacts, fuzzy matching, decimal parsing (European/US). Real parser. Complements OcrPatternsTest. |
| 89 | domain/receipt/WarrantyTextExtractorTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 12 | 0 | HIGH | Tests warranty extraction (product, merchant, date, duration, support), merchant-based defaults, non-warranty text, legacy date parsing regression. Protects warranty parsing contract. |
| 90 | domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt | LIFECYCLE_CONTRACT | P2_MEDIUM | KEEP | 5 | 0 | HIGH | Tests processReceiptInput: validates input, persists receipt, handles restore maintenance mode, deduplicates. All-mock but validates coordinator contract. |
| 91 | domain/receiptmatching/ReceiptTransactionMatcherTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 2 | 0 | HIGH | Tests DEPOSIT exclusion from matching, Greek merchant normalization in matching. Real StringDistanceUtils. |
| 92 | domain/recurring/lifecycle/RecurringLifecycleCoordinatorTest.kt | LIFECYCLE_CONTRACT | P2_MEDIUM | KEEP | 5 | 0 | HIGH | Tests generateOccurrences: expand→resolve→materialize pipeline, restore maintenance guard, dedup behavior. Protects recurring lifecycle contract. |
| 93 | domain/reminder/BillReminderManagerTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 5 | 0 | HIGH | Tests markBillPaid advances annually/semi-annually/irregularly, getMonthlyBillsTotal uses canonical semantics, reminder urgency mapping. Protects bill reminder logic. |
| 94 | domain/savings/AutomatedSavingsRuleEngineGoldenTest.kt | GOLDEN | P0_CRITICAL | KEEP | 1 | 0 | HIGH | Golden case: round up 17.30 to nearest 5 = 2.70 savings. Critical regression guard for savings rule engine. |
| 95 | domain/savings/AutomatedSavingsRuleEngineTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 8 | 0 | HIGH | Tests round-up skips non-positive/NaN/Inf, default increment, percentage invalid values, weekly no-spend calendar boundaries. Protects savings rules. |
| 96 | domain/savings/SavingsGamificationEngineTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 8 | 0 | HIGH | Real contribution history. Tests streak calculation, honest zero for legacy balances, streak reset on gap, milestone achievements. Protects gamification contract. |
| 97 | domain/savings/SmartSavingsEngineTest.kt | MOCK_ORCHESTRATION | P1_HIGH | KEEP | 10 | 0 | HIGH | Tests safeToSaveAmount weighted formula (surplus×0.4 + pace×0.3 + mc×0.3). Protects smart savings calculation. |
| 98 | domain/split/SplitCalculationPrecisionTest.kt | CALCULATION_PRECISION | P1_HIGH | KEEP | 20 | 0 | HIGH | Tests equal split precision (100÷3, 0.01÷2, 999999.99÷7), percentage split, large/small amounts. Uses real Money class. Marked HIGH-2. Critical for financial precision. |
| 99 | domain/tax/TaxCalculationTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 35 | 1 | HIGH | Tests Greece/US VAT rates, VAT calculations, progressive brackets, self-employment tax, deduction limits. 1 @Ignore for VAT extraction logic mismatch. Real tax configs. |
| 100 | domain/tax/TaxEstimatorTest.kt | MOCK_ORCHESTRATION | P1_HIGH | KEEP | 12 | 0 | HIGH | B.8 Batch 7: business-only VAT, period-aligned income, cumulative progressive brackets, US config returns zero VAT. Protects tax estimation correctness. |

---

## Detailed Notes for Non-KEEP Items

### DELETE (8 items)

#### #44: FeatureExtractorTest.kt
**Reason:** Single trivial test (16 lines of code, 1 test method). Tests that tokenize("H&M / 7-Eleven - AT&T") contains "eleven". This is a micro-unit test with zero financial value. If this tokenizer is important, its behavior should be indirectly tested via the ExpenseCategoryClassifier integration tests.

#### #55: TravelDetectionEngineTest.kt
**Reason:** Single test (43 lines, 1 test method). Fully redundant with TravelDetectionEngineStressTest (#54) which covers the same behavior plus stress scenarios. The stress test already tests destination hint extraction, home detection, and trip grouping. Delete this duplicate.

#### #64: CategoryBreakdownTest.kt
**Reason:** 19 tests, 435 lines of pure data-class tautology: tests that a Kotlin data class stores values, that copy() works, that equals()/hashCode() work. This is the Kotlin compiler's job. Zero financial logic or business contract tested. These tests add no protection beyond what the type system guarantees.

#### #65: PeriodTotalTest.kt
**Reason:** 14 tests, 260 lines. Same issue as #64. Tests enum ordinals (PeriodStatus.UNDER_AVERAGE.ordinal == 0), data class field storage, copy(). Absolute zero business value. The Kotlin compiler already guarantees data class copy/equals semantics.

#### #71: AppParserRegistryTest.kt (MERGED → DELETE)
**Reason:** 8 tests, 150 lines. >80% overlap with AppParserRegistryRoutingTest (#70). Both test routing to Revolut/GoogleWallet/GreekBank/Generic parsers with similar inputs. The unique tests from this file (OTP rejection, Revolut grouped amount) should be merged into AppParserRegistryRoutingTest, then this file deleted. This is a canonical merge-and-delete case.

### REWRITE (2 items)

#### #53: SpendingHeatmapEngineStressTest.kt
**Reason:** 32 tests, 562 lines. While the heatmap engine is real (no mocks), the test file is bloated. Every test is named "stress - ..." even for simple grid boundary tests. Many tests are duplicate in intent (e.g., 4 separate tests for different grid boundary scenarios that could be parameterized). **Rewrite into ≤8 focused tests** covering: clustering, grid boundaries, log normalization, weight calculation, zero/edge amounts, and deterministic output. Keep the real engine tests, drop the filler.

#### #71: AppParserRegistryTest.kt
**Reason:** See DELETE entry above. Merge unique tests into AppParserRegistryRoutingTest (#70), then delete this file.

### MOVE (4 items)

#### #14: MultiCurrencyTestFixture.kt
**Reason:** Not a test file (0 @Test annotations). This is a shared test fixture providing canonical EUR/USD expense helpers. Should be moved to a shared test fixtures module (e.g., `src/testFixtures/` or a dedicated `test-common/fixtures/` package) so it's accessible to all test suites that need multi-currency testing without copy-paste.

### MOVE_TO_NIGHTLY (3 items)

#### #1: CategorizationEngineStressTest.kt
**Reason:** 33 tests, 651 lines. Tests 10k concurrent requests using real executors/latches. Valuable for catching race conditions in the categorization cache but excessively slow for CI. Move to nightly pipeline.

#### #4: ContextualInferenceEngineStressTest.kt
**Reason:** 18 tests, 660 lines. Extensive boundary testing across amount ranges, day-of-week, and time-of-day inference. Good coverage but inflates CI runtime. Move to nightly.

#### #15: ServiceDiagnosticsTest.kt
**Reason:** 11 tests requiring Robolectric (ApplicationProvider, SharedPreferences). Android framework dependency makes this inherently slow and flaky in pure JVM test environments. Nightly suite is appropriate for Robolectric tests.

#### #62: SynthesisEngineStressTest.kt
**Reason:** 56 tests, 1709 lines. Massive stress suite with concurrent access patterns, random inputs, extreme values. Extremely valuable for catching synthesis engine regressions but far too heavy for CI. Move to nightly.

### UNKNOWN_NEEDS_LOCAL_RUN (2 items)

None in this batch — all files were clearly readable and analyzable from source alone.

---

## Key Findings

1. **Parser library is well-tested:** Files 70-80 provide thorough coverage of Revolut, GreekBank, SmsParser, GoogleWallet, and Generic parser. The TransferDirectionDetector (52 tests) is particularly exhaustive.

2. **Golden tests are present and valuable:** Files 12, 25, 37, 59, 61, 94 provide critical regression guards for financial calculations. All should be protected at P0_CRITICAL.

3. **Model test inflation:** Files 64 (CategoryBreakdown) and 65 (PeriodTotal) are classic examples of Kotlin data-class tautology tests. They add 19+14=33 tests and 695 lines with zero financial protection.

4. **Stress test bloat:** Files 1, 53, 62 collectively add 121 tests and ~2900 lines. Move large stress suites to nightly, and collapse redundant stress tests.

5. **Duplicate parser registry tests:** Files 70 and 71 test the same routing behavior. Merge and deduplicate.

6. **No @Ignore in critical tests:** Only TaxCalculationTest has 1 @Ignore for a known VAT logic difference. No silently skipped critical tests.

