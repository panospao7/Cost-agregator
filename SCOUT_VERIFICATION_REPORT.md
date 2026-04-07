# Scout Verification Report: Domain Package Coverage Analysis

## Executive Summary

**Verification Status:** ❌ **INCOMPLETE** — Only **26.2%** of domain/ package files have been covered by batch analysis reports.

- **Total `.kt` files in `domain/` package:** 244
- **Files covered by batch analyses:** 64 (26.2%)
- **Files MISSED (not analyzed):** 180 (73.8%)
- **Dead references (in scopes but not in codebase):** 37
- **Batches analyzed for domain/ package:** 1-10 (partial; batches 11-24 cover data/ layer)

---

## Scope vs. Reality

### Batch Plan Intention
According to `DEEP-ANALYSIS-BATCH-PLAN.md`:
- **Total Backend Files:** 477
- **Total Batches:** 25
- Batches 1-10 were supposed to cover domain/ package files
- Batches 11-25 cover data/ layer, di/, and other infrastructure

### What Was Actually Executed
- **Batches 1-5:** ✅ Have proper `## Scope` sections with domain files
- **Batch 6:** ⚠️ NO `## Scope` section (references files in findings but no formal scope list)
- **Batches 7-9:** ✅ Have `## Scope` sections with domain files
- **Batch 10:** ✅ Has `## Batch Scope` section with domain files (different format than others)
- **Batches 11+:** ✅ Have `## Scope` sections but cover **data/** layer, not domain/

---

## Files Covered (64 total)

**AI Use Cases (18 files):**
CategorizationAssistInputBuilder, DedupeJudgeInputBuilder, FinancialQueryInterpretationInputBuilder, ReceiptAssistInputBuilder, ReceiptItemCategorizationInputBuilder, ReviewExplanationInputBuilder, DeliverProactiveBriefingNotificationUseCase, ExecuteFinancialQueryUseCase, ExplainPendingReviewUseCase, GenerateDashboardBriefingUseCase, GetAiRuntimeStatusUseCase, InterpretFinancialQueryUseCase, JudgePendingReviewDuplicateUseCase, MapFinancialQueryToNavigationUseCase, PrioritizeReviewItemsUseCase, SuggestCategoryFallbackUseCase, SuggestReceiptExtractionUseCase, SyncProactiveBriefingWorkUseCase

**Analytics (15 files):**
AdvancedAnalyticsEngine, AdvancedAnalyticsDashboard, AnomalyDetector, CategoryInsightEngine, DayOfWeekAnalyzer, InsightsEngine, MerchantInsightEngine, MonthlyComparisonCalculator, SpendingPaceCalculator, SpendingPersonalityClassifier, SpendingThresholdCalculator, TotalsAggregationEngine, TransferDirectionAnalytics, AdvancedAnalyticsModels, AnalyticsModels

**Budget (7 files):**
BudgetCalculator, BudgetForecastingEngine, BudgetAutopilotEngine, BudgetMonitor, BudgetRecommendationEngine, SharedBudgetManager, BudgetModels

**Forecasting & Logic (9 files):**
FinancialStressForecastEngine, MonteCarloSpendingSimulator, DataQualityAssessor, HistoricalSpendingDistribution, SynthesisEngine, RecurringExpenseEngine, CustomSplitParser, SplitCalculator

**Health & Savings (4 files):**
FinancialHealthScoreV2, FinancialHealthCalculator, AutomatedSavingsRuleEngine, SmartSavingsEngine, SavingsGamificationEngine (Note: missing SavingsGoalRepository)

**Use Cases (11 files):**
ComputeDashboardWidgetsUseCase, ComputeMoneyRadarUseCase, DashboardDataProvider, CategorizeExpenseUseCase, DetectDuplicateExpenseUseCase, CalculateFinancialForecastUseCase, LifestyleSavingsPromptUseCase, MonthlySavingsSweepUseCase, AutoCreateWarrantyFromReceiptUseCase, CalculateBudgetStatusUseCase, GetMonteCarloBudgetImpactUseCase

---

## Major Gaps — Files NOT Analyzed (180 files)

### AI Models & Policies (27 files) — CRITICAL
Core AI infrastructure not analyzed:
- **AI Models:** AiModels, AiLoadState, AiRuntimeStatusModels, OnDeviceRuntimePresentation, CaptureAssistModels, FinancialQueryModels, NotificationParsingModels, ReceiptItemCategorizationModels, ReviewPriorityModels, SemanticDuplicateModels, WarrantyExtractionModels, AiArtifactPresentation (12 files)
- **AI Policy/Routing:** AiPolicy, AiPolicyImpl, DefaultAiCapabilityRouter (3 files)
- **AI Foundation Services:** AiArtifactRepository, AiCapabilityRouter, AiChatRepository, AiEngagementRepository, AiEnvironmentMonitor, AiSettingsRepository, AiWorkScheduler, CategorizationAssistService, DashboardBriefingService, DedupeJudgeService, NotificationFallbackParser, QueryInterpretationService, ReceiptAssistService, ReceiptItemCategorizationService, ReviewExplanationService, ReviewPriorityScorer, SemanticDuplicateDetector (17 files, but many only have stubs or are missing implementations)

### Entire Feature Domains (145 files) — NOT ANALYZED
- **Alerts (1):** AnomalyAlertOrchestrator
- **Backup/Banking (3):** DatabaseBackupRepository, DatabaseOperationResults, BankApiIntegration
- **Business/Categorization (8):** BusinessExpenseReportGenerator, CategorizationEngine, CategoryKeywords, ContextualInferenceEngine, GreeklishNormalizer, MerchantCanonicalizer, SemanticKeywordMatcher
- **Challenge/Config (2):** SpendingChallengeManager, AppConfig
- **Currency (4):** CurrencyConverter, CurrencyRatesRepository, CurrencySettingsRepository, ExchangeRateContracts
- **Debug (6):** AiRuntimeDiagnostics, DebugData, DebugIssue, DebugIssueDetector, NotificationSeeder, ServiceDiagnostics
- **Engine (1):** DashboardFollowThroughEngine
- **Export (2):** AccountingExporters, ExportTransaction
- **Groups (8):** SharedExpenseManager, SettlementCalculator, SharedExpenseBudgetOffsetEngine, GroupTransactionCoordinator, SharedExpensePort, AddGroupExpenseUseCase, DeleteGroupMemberUseCase, DeleteGroupUseCase
- **Income (1):** RecurringIncomeTracker
- **Intelligence (8):** TransactionClassifier, ConfidenceRouter, CrossSourceDeduplication, HybridExpenseClassifier, FeatureExtractor, ExpenseClassifier, ExpenseCategoryClassifier, MerchantNormalizer
- **Investment (1):** InvestmentTracker
- **Lifestyle (1):** LifestyleInflationDetector
- **Location (10):** LocationResolver, LocationResolverPorts, LocationInsightsEngine, TravelDetectionEngine, SpendingHeatmapEngine, AreaSpendingEngine, LocationModels, NearbyPoi, GeocodingResult, LocatedExpense
- **Logic (2):** NarrativeGenerator, RecurrenceCalculator
- **Natural Language (3):** NaturalLanguageExpenseQueryRepository, NaturalLanguageSearchEngine, SpeechInputGateway
- **Negotiation (1):** SmartBillNegotiationEngine
- **Parser (9):** AppParserRegistry, GenericTransactionParser, ParsedTransactionEnums, TransferDirectionDetector, GreekBankParser, RevolutParser, GoogleWalletParser, SmsParser
- **Performance (1):** ImageCache
- **Price/Receipt (13):** PriceProtectionTracker, ReceiptOcrService, ReceiptParser, OcrPreprocessingPipeline, BankStatementParser, EnhancedMerchantExtractor, MerchantRulesPolicy, OcrLanguageProcessor, WarrantyTextExtractor, ReceiptSource, ReceiptTransactionMatcher
- **Reminders/Savings (3):** BillReminderManager, SavingsGoalRepository, NotificationService
- **Split (1):** EnhancedSplitManager
- **Subscription (2):** SubscriptionManagerEngine, NotificationSubscriptionDetector
- **Tax (2):** TaxConfiguration, TaxEstimator
- **Text/UI (2):** DashboardTextKeys, DomainTextKeys
- **Utilities (25):** Money, AmountUtils, TimeProvider, TimePeriodUtils, CurrencyNormalizer, CurrencyFormatter, DateFormatterUtils, MerchantCleaner, MerchantKeyGenerator, StringDistanceUtils, StatisticsUtils, GeoUtils, CommonPatterns, BKTree, NotificationIdGenerator, AppConstants, AmountExtractionUtils, SystemTimeProvider, and others
- **Domain Models (30):** BlockPartyDay, CategoryBreakdown, CategoryInfo, FinancialForecast, PeriodRange, PeriodTotal, PlannedExpense, RecurringPattern, Result, SavingsGoal, UiText, UpcomingItem, and all dashboard/budget/navigation/recommendation submodels
- **Widget (2):** WidgetStyle, WidgetStyleRepository
- **Use Cases (3):** DashboardRepositoryContracts, ExpenseUseCases, ProcessReceiptUseCase

---

## Dead References in Batch Scopes (37 files)

These files appear in batch scope sections but **DO NOT EXIST** in the actual codebase:

### AI Services (27 files) — Planned but never implemented
**Cloud variants (8):** CloudCategorizationAssistService, CloudDashboardBriefingService, CloudDedupeJudgeService, CloudQueryInterpretationService, CloudReceiptAssistService, CloudReceiptItemCategorizationService, CloudReviewExplanationService, CloudWarrantyExtractionService

**Hybrid variants (8):** HybridCategorizationAssistService, HybridDashboardBriefingService, HybridDedupeJudgeService, HybridQueryInterpretationService, HybridReceiptAssistService, HybridReceiptItemCategorizationService, HybridReviewExplanationService, HybridServiceDelegationModels

**OnDevice variants (5):** OnDeviceCategorizationAssistService, OnDeviceDashboardBriefingService, OnDeviceDedupeJudgeService, OnDeviceQueryInterpretationService, OnDeviceReceiptAssistService, OnDeviceReviewExplanationService

**Other AI (2):** AiUseCaseModels, SmartReceiptAssistService

**AI Providers (4):** CloudCorrelation, CloudJsonParser, CloudPiiSanitizer, CloudRetryPolicy

### Model/Structure Files (10 files) — Never created consolidations
- **SpendingPaceModels** (Analytics)
- **BudgetRecommendationModels** (Budget)
- **ForecastModels** (Forecasting)
- **FinancialHealthModels** (Health)
- **HealthScoreModels** (Health)
- **SynthesisModels** (Logic)
- **SavingsModels** (Savings)
- **DashboardContractsAdapter** (Use Cases)
- **DashboardTextKeys** (Use Cases — exists but in wrong package)

**Implication:** The batch plan references 37 files that were either never implemented OR were consolidated into other files, breaking plan-to-code traceability.

---

## Detailed Coverage by Module

| Module | Total | Covered | % | Status |
|--------|-------|---------|---|--------|
| AI (models/policy/foundation) | 27 | 0 | 0% | ❌ **CRITICAL** |
| AI (usecases/services) | 19 | 18 | 95% | ⚠️ Nearly complete |
| Analytics | 15 | 15 | 100% | ✅ |
| Budget | 8 | 7 | 88% | ⚠️ Missing BudgetModels, BudgetRecommendationInputs |
| Forecasting & Logic | 9 | 9 | 100% | ✅ |
| Health & Savings | 5 | 4 | 80% | ⚠️ Missing SavingsGoalRepository |
| Use Cases | 13 | 10 | 77% | ⚠️ Missing 3 files |
| Alerts | 1 | 0 | 0% | ❌ |
| Backup/Bank | 3 | 0 | 0% | ❌ |
| Categorization | 8 | 0 | 0% | ❌ |
| Challenge/Config | 2 | 0 | 0% | ❌ |
| Currency | 4 | 0 | 0% | ❌ |
| Debug | 6 | 0 | 0% | ❌ |
| Groups | 8 | 0 | 0% | ❌ |
| Income | 1 | 0 | 0% | ❌ |
| Intelligence | 8 | 0 | 0% | ❌ |
| Investment | 1 | 0 | 0% | ❌ |
| Lifestyle | 1 | 0 | 0% | ❌ |
| Location | 10 | 0 | 0% | ❌ |
| Natural Language | 3 | 0 | 0% | ❌ |
| Negotiation | 1 | 0 | 0% | ❌ |
| Parser | 9 | 0 | 0% | ❌ |
| Performance | 1 | 0 | 0% | ❌ |
| Price/Receipt | 13 | 0 | 0% | ❌ |
| Reminders/Service | 3 | 0 | 0% | ❌ |
| Split/Subscription | 3 | 0 | 0% | ❌ |
| Tax/Text | 4 | 0 | 0% | ❌ |
| Utilities | 25 | 0 | 0% | ❌ |
| Domain Models | 30 | 0 | 0% | ❌ |
| Widget | 2 | 0 | 0% | ❌ |
| **TOTAL** | **244** | **64** | **26.2%** | ⚠️ Incomplete |

---

## Key Findings

### ✅ Strengths
1. **Core analytical engines covered:** All 15 analytics files analyzed
2. **Budget subsystem near-complete:** 7 of 8 budget files reviewed
3. **Forecasting logic complete:** All 9 forecasting/logic files analyzed
4. **Primary use cases covered:** 10 of 13 critical use cases analyzed

### ❌ Critical Gaps
1. **AI foundation layer invisible:** 27 AI model/policy files not analyzed — this is the architectural foundation for all AI features
2. **Entire feature domains unmapped:** 145 files across 22+ feature packages never analyzed
3. **Infrastructure/utility layer missing:** 25+ utility files and 30 model files have no formal review
4. **Model consolidations incomplete:** 6 planned model files were never created
5. **Dead references abundant:** 37 files in batch scopes don't exist in codebase

### ⚠️ Architectural Concerns
1. **AI contract undefined:** Without analyzing AI models/policies, no foundational AI contract can be verified
2. **Coverage skewed to core:** Budget/analytics/forecasting heavily analyzed; location/groups/intelligence/receipt completely ignored
3. **Inconsistent documentation:** Mixed use of `## Scope` vs `## Batch Scope`; batch 6 has no scope section
4. **Plan drift significant:** DEEP-ANALYSIS-BATCH-PLAN.md lists 477 backend files; only 64 domain/ files actually covered

---

## Recommendations

### Immediate (Week 1)
1. **Formalize Batch 6:** Add proper `## Scope` section documenting the 12 AI model/policy files it reviewed (AiModels, AiLoadState, AiRuntimeStatusModels, AiArtifactPresentation, DefaultAiCapabilityRouter, etc.)
2. **Remove dead references:** Audit and remove the 37 non-existent files from batch scopes, or implement them
3. **Verify batch 10 scope:** Confirm all 10 files in `## Batch Scope` exist and are actually analyzed

### Short-Term (Week 2-3)
1. **Schedule Batches 26-30** to cover the 180 missed domain/ files:
   - **Batch 26:** AI Models & Policies (12 files) — AiModels, AiPolicy, DefaultAiCapabilityRouter, models
   - **Batch 27:** AI Foundation Services (15 files) — AiSettingsRepository, AiCapabilityRouter, base services
   - **Batch 28:** Location & Groups (18 files) — Location*, Groups*, SettlementCalculator
   - **Batch 29:** Intelligence & Categorization (16 files) — TransactionClassifier, CategorizationEngine, MerchantNormalizer
   - **Batch 30:** Receipt & Parser (22 files) — ReceiptParser, OcrPreprocessingPipeline, all parsers
   - **Batch 31:** Domain Models (30 files) — All model/ files
   - **Batch 32:** Utilities (25 files) — All util/*.kt files
   - **Batch 33:** Miscellaneous (20 files) — Debug, service, tax, export, etc.

2. **Update DEEP-ANALYSIS-BATCH-PLAN.md** to match actual structure and remove stale references

3. **Add CI enforcement:** Git hook to flag any new domain/ files not in an upcoming batch

### Medium-Term (Month 2-3)
1. **Consolidate model files:** Actually create SynthesisModels.kt, ForecastModels.kt, etc.
2. **Audit AI implementation:** Decide whether Cloud*/Hybrid*/OnDevice* services need implementation or should be removed from scope
3. **Establish domain module boundaries:** Document which packages are feature-complete vs experimental
4. **Create coverage dashboard:** Automated report showing % of each module covered by batches

### Long-Term (Ongoing)
1. **Continuous coverage tracking:** Maintain manifest of all domain/ files vs batch assignments
2. **100% target:** Plan and execute remaining batches to reach full domain/ coverage
3. **Regular audits:** Every 10 batches, verify plan-to-code drift hasn't exceeded 5%
4. **Update as needed:** When new domain features added, assign to upcoming batch before merge

---

## Conclusion

**The batch analysis program has achieved only ~26% coverage of the domain/ package.** While core analytical engines, budget logic, and forecasting are well-analyzed, foundational AI infrastructure, entire feature domains, and all utility/model layers remain unreviewed. The presence of 37 dead references and missing model consolidations further indicates that the batch plan has drifted significantly from the actual codebase.

**To achieve complete domain/ coverage, approximately 8 additional batches (26-33) are needed,** each covering 15-30 files across the remaining feature domains, utilities, and models. Priority should be given to AI models/policies (CRITICAL), location/groups/intelligence (HIGH), and all utility/model files (MEDIUM).

**Immediate actions required:**
1. Formalize Batch 6 scope documentation
2. Remove 37 dead references from batch scopes
3. Begin scheduling Batches 26-33 immediately
4. Update batch plan to match actual codebase structure
