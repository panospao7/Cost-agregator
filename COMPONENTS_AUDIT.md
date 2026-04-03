# Components Audit for Semantic Contract Map

**Audit Date:** April 3, 2026  
**Scope:** Domain layer financial calculation components  
**Total Components Found:** 42  
**Repository:** ExpenseTracker

---

## Summary Statistics

- **Total Calculation Components Found:** 42
- **Already in Semantic Contract:** Multiple (Major engines documented)
- **Missing from Map (Critical):** ~15-20 components
- **Correctly Excluded:** ~0 (all are legitimate calculation/analytics components)
- **High-Impact Components:** 15+ (used by multiple other systems)

---

## Components Breakdown by Category

### 1. CORE ANALYTICS ENGINES (HIGH PRIORITY)

| Component | File | Lines | What It Calculates | Critical? |
|-----------|------|-------|-------------------|-----------|
| **InsightsEngine** | `domain/analytics/InsightsEngine.kt` | 751 | Monthly comparisons, category insights, merchant analytics, spending pace, anomalies, recurring expenses, day-of-week patterns, transaction size stats | YES |
| **AdvancedAnalyticsEngine** | `domain/analytics/AdvancedAnalyticsEngine.kt` | 925 | Category analytics with budget context, merchant analytics with price trends, spending patterns by day/time, statistical insights (percentiles, histogram, volatility) | YES |
| **SpendingPaceCalculator** | `domain/analytics/SpendingPaceCalculator.kt` | 100+ | Daily/monthly spending rate projections, pace percentages vs. baseline | YES |
| **TotalsAggregationEngine** | `domain/analytics/TotalsAggregationEngine.kt` | 317 | Period totals (daily, weekly, monthly, yearly), category breakdown with percentages | YES |
| **AnomalyDetector** | `domain/analytics/AnomalyDetector.kt` | 312 | IQR outlier detection, MAD (Median Absolute Deviation) detection, contextual anomalies (day/time-based) | YES |
| **SpendingThresholdCalculator** | `domain/analytics/SpendingThresholdCalculator.kt` | 120+ | High-amount thresholds per category, percentiles calculation | YES |

### 2. FORECASTING & PREDICTION (HIGH PRIORITY)

| Component | File | Lines | What It Calculates | Critical? |
|-----------|------|-------|-------------------|-----------|
| **BudgetForecastingEngine** | `domain/budget/BudgetForecastingEngine.kt` | 329 | Predicted spending, confidence scores, overspend probability, seasonal adjustments, risk levels | YES |
| **MonteCarloSpendingSimulator** | `domain/forecasting/MonteCarloSpendingSimulator.kt` | 251 | Probabilistic spending forecasts (percentiles 10-90), budget adherence probability, 1000 iterations | YES |
| **HistoricalSpendingDistribution** | `domain/forecasting/HistoricalSpendingDistribution.kt` | TBD | Log-normal distribution fitting for weekly spending patterns | YES |
| **CashFlowCalculator** | `domain/cashflow/CashFlowCalculator.kt` | 171 | Daily cash flow, income/expense splits, risk levels, recurring predictions | YES |

### 3. BUDGET & FINANCIAL HEALTH (MEDIUM-HIGH PRIORITY)

| Component | File | Lines | What It Calculates | Critical? |
|-----------|------|-------|-------------------|-----------|
| **BudgetCalculator** | `domain/budget/BudgetCalculator.kt` | 127 | Budget period windows (daily, weekly, monthly, yearly), period boundaries with anchor dates | YES |
| **FinancialHealthCalculator** | `domain/health/FinancialHealthCalculator.kt` | 478 | Health scores (today, week, month, composite), budget health metrics, spending volatility, bonus points | YES |
| **BudgetRecommendationEngine** | `domain/budget/BudgetRecommendationEngine.kt` | TBD | Recommended budget amounts based on spending patterns | MEDIUM |

### 4. SAVINGS & INVESTMENT (MEDIUM PRIORITY)

| Component | File | Lines | What It Calculates | Critical? |
|-----------|------|-------|-------------------|-----------|
| **SmartSavingsEngine** | `domain/savings/SmartSavingsEngine.kt` | 218 | Safe-to-save amounts, budget surplus, spending pace analysis, weighted recommendations | MEDIUM |
| **SavingsGamificationEngine** | `domain/savings/SavingsGamificationEngine.kt` | TBD | Savings streaks, experience levels, gamification scoring | MEDIUM |
| **AutomatedSavingsRuleEngine** | `domain/savings/AutomatedSavingsRuleEngine.kt` | TBD | Automatic savings rule calculations | LOW-MEDIUM |

### 5. SPENDING ANALYSIS & PATTERNS (MEDIUM PRIORITY)

| Component | File | Lines | What It Calculates | Critical? |
|-----------|------|-------|-------------------|-----------|
| **MerchantInsightEngine** | `domain/analytics/MerchantInsightEngine.kt` | TBD | Merchant-level totals, frequency, standard deviation | MEDIUM |
| **CategoryInsightEngine** | `domain/analytics/CategoryInsightEngine.kt` | TBD | Category-level insights and trends | MEDIUM |
| **DayOfWeekAnalyzer** | `domain/analytics/DayOfWeekAnalyzer.kt` | TBD | Day-of-week spending distribution | LOW-MEDIUM |
| **MonthlyComparisonCalculator** | `domain/analytics/MonthlyComparisonCalculator.kt` | TBD | Month-over-month comparisons | MEDIUM |

### 6. ENVIRONMENTAL & LIFESTYLE (SPECIALIZED)

| Component | File | Lines | What It Calculates | Critical? |
|-----------|------|-------|-------------------|-----------|
| **CarbonFootprintCalculator** | `domain/carbon/CarbonFootprintCalculator.kt` | 498 | CO2 emissions by category/merchant, sustainability scores, monthly trends, offset calculations | SPECIALIZED |
| **LifestyleInflationDetector** | `domain/lifestyle/LifestyleInflationDetector.kt` | 429 | Income elasticity, lifestyle creep detection, hedonic adaptation, correlation analysis | SPECIALIZED |

### 7. LOCATION & MERCHANT (MEDIUM PRIORITY)

| Component | File | Lines | What It Calculates | Critical? |
|-----------|------|-------|-------------------|-----------|
| **TravelDetectionEngine** | `domain/location/TravelDetectionEngine.kt` | TBD | Travel-based spending detection | LOW-MEDIUM |
| **SpendingHeatmapEngine** | `domain/location/SpendingHeatmapEngine.kt` | TBD | Location-based spending heatmap | LOW-MEDIUM |
| **LocationInsightsEngine** | `domain/location/LocationInsightsEngine.kt` | TBD | Location analytics | LOW-MEDIUM |
| **AreaSpendingEngine** | `domain/location/AreaSpendingEngine.kt` | TBD | Area-based spending analysis | LOW-MEDIUM |

### 8. SHARED EXPENSES & GROUPS (MEDIUM PRIORITY)

| Component | File | Lines | What It Calculates | Critical? |
|-----------|------|-------|-------------------|-----------|
| **SharedExpenseManager** | `domain/groups/SharedExpenseManager.kt` | TBD | Settlement calculations, balance tracking, split computations | MEDIUM |
| **SettlementCalculator** | `domain/groups/SettlementCalculator.kt` | TBD | Who owes whom calculations, settlement amounts | MEDIUM |
| **EnhancedSplitManager** | `domain/split/EnhancedSplitManager.kt` | TBD | Equal/percentage/custom split calculations | MEDIUM |

### 9. SUBSCRIPTIONS & RECURRING (MEDIUM PRIORITY)

| Component | File | Lines | What It Calculates | Critical? |
|-----------|------|-------|-------------------|-----------|
| **SubscriptionManagerEngine** | `domain/subscription/SubscriptionManagerEngine.kt` | TBD | Subscription analysis, health scores, savings calculations, price change detection | MEDIUM |
| **RecurringExpenseEngine** | `domain/logic/RecurringExpenseEngine.kt` | TBD | Recurring pattern detection, frequency intervals, standard deviation | MEDIUM |

### 10. TAXES & FINANCIAL PLANNING (LOW-MEDIUM PRIORITY)

| Component | File | Lines | What It Calculates | Critical? |
|-----------|------|-------|-------------------|-----------|
| **TaxEstimator** | `domain/tax/TaxEstimator.kt` | TBD | Tax rate calculations, estimated tax amounts | LOW-MEDIUM |
| **SmartBillNegotiationEngine** | `domain/negotiation/SmartBillNegotiationEngine.kt` | TBD | Bill negotiation analysis | LOW |

### 11. CHALLENGES & GAMIFICATION (LOW PRIORITY)

| Component | File | Lines | What It Calculates | Critical? |
|-----------|------|-------|-------------------|-----------|
| **SpendingChallengeManager** | `domain/challenge/SpendingChallengeManager.kt` | TBD | Challenge progress tracking, goal calculations | LOW |

### 12. AI/ML & DETECTION (SPECIALIZED)

| Component | File | Lines | What It Calculates | Critical? |
|-----------|------|-------|-------------------|-----------|
| **CategorizationEngine** | `domain/categorization/CategorizationEngine.kt` | TBD | ML-based expense categorization | SPECIALIZED |
| **ContextualInferenceEngine** | `domain/categorization/ContextualInferenceEngine.kt` | TBD | Context-aware category inference | SPECIALIZED |
| **SemanticDuplicateDetector** | `domain/ai/service/SemanticDuplicateDetector.kt` | TBD | Duplicate transaction detection using ML | SPECIALIZED |
| **TransferDirectionDetector** | `domain/parser/TransferDirectionDetector.kt` | TBD | Determines if transfer is income/expense | SPECIALIZED |
| **TransactionClassifier** | `domain/intelligence/TransactionClassifier.kt` | TBD | Transaction classification logic | SPECIALIZED |

### 13. INFRASTRUCTURE & COMPOSITION (SYSTEM-LEVEL)

| Component | File | Lines | What It Calculates | Critical? |
|-----------|------|-------|-------------------|-----------|
| **SynthesisEngine** | `domain/logic/SynthesisEngine.kt` | TBD | Multi-source data synthesis, block party calculations | MEDIUM |
| **DashboardFollowThroughEngine** | `domain/engine/DashboardFollowThroughEngine.kt` | TBD | Dashboard widget computation orchestration | MEDIUM |
| **ComputeDashboardWidgetsUseCase** | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | TBD | Dashboard streak calculations, widget composition | MEDIUM |
| **LocationResolver** | `domain/location/LocationResolver.kt` | TBD | Location data resolution | LOW-MEDIUM |
| **NaturalLanguageSearchEngine** | `domain/naturallanguage/NaturalLanguageSearchEngine.kt` | TBD | Query interpretation for search | SPECIALIZED |

---

## Missing from Semantic Contract Map (SHOULD BE ADDED)

### Tier 1: CRITICAL (Must Document)

1. **MonteCarloSpendingSimulator** - Probabilistic forecasting is core value
2. **AdvancedAnalyticsEngine** - Enhanced analytics with multiple methods
3. **CashFlowCalculator** - Cash flow modeling is a unique feature
4. **AnomalyDetector** - Statistical anomaly detection methodology
5. **SpendingPaceCalculator** - Key insight for pace monitoring
6. **LifestyleInflationDetector** - Unique lifestyle analysis feature
7. **CarbonFootprintCalculator** - Specialized sustainability feature
8. **FinancialHealthCalculator** - Health score computation is important
9. **BudgetForecastingEngine** - AI-powered forecasting

### Tier 2: HIGH PRIORITY (Should Document)

10. **SharedExpenseManager** - Split expense tracking feature
11. **SubscriptionManagerEngine** - Subscription analysis is a key feature
12. **SavingsGamificationEngine** - Gamification scoring system
13. **SettlementCalculator** - Shared expense settlements
14. **TotalAggregationEngine** - Period aggregation is foundational
15. **SpendingThresholdCalculator** - Dynamic threshold calculation
16. **MerchantInsightEngine** - Merchant-level analytics
17. **CategoryInsightEngine** - Category-level analytics

### Tier 3: MEDIUM PRIORITY (Should Consider)

18. **SmartSavingsEngine** - Safe-to-save recommendations
19. **LocationInsightsEngine** - Location-based analytics
20. **RecurringExpenseEngine** - Recurring pattern detection

---

## Already Well-Documented in Semantic Contract

The following components appear to already be documented:

1. **InsightsEngine** - Main insights generation ✓
2. **BudgetCalculator** - Period calculations ✓
3. **BudgetRecommendationEngine** - Budget recommendations ✓
4. **CategorizationEngine** - Categorization system ✓

---

## Not Analytics (Correctly Excluded)

The following are NOT analytics/calculation components and should NOT be in the map:

1. **NavigationTargetResolver** - UI navigation only
2. **OcrLanguageProcessor** - Text processing (not calculation)
3. **ReceiptOcrService** - Document processing (not calculation)
4. **DebugViewModel/DebugIssueDetector** - Debug utilities only

---

## High-Impact Components (Used by Multiple Subsystems)

These components are called by numerous other components and should be prioritized:

1. **InsightsEngine** - Used by: Dashboard, Analytics screens, Insights
2. **BudgetCalculator** - Used by: Multiple budget features
3. **CashFlowCalculator** - Used by: Forecasting, Dashboard
4. **MonteCarloSpendingSimulator** - Used by: SmartSavingsEngine, Dashboard
5. **AdvancedAnalyticsEngine** - Used by: Analytics screens
6. **FinancialHealthCalculator** - Used by: Dashboard health metrics
7. **TotalsAggregationEngine** - Used by: Dashboard drilldown
8. **SharedExpenseManager** - Used by: Group expense features

---

## Key Calculation Methodologies Found

### Statistical Methods
- **IQR (Interquartile Range)** - Outlier detection
- **MAD (Median Absolute Deviation)** - Robust outlier detection
- **Z-Score** - Standardized deviation
- **Percentiles** - Distribution analysis (p10, p25, p50, p75, p90, p95, p99)
- **Standard Deviation** - Volatility measurement
- **Coefficient of Variation** - Normalized volatility

### Forecasting Methods
- **Log-Normal Distribution** - Spending pattern fitting
- **Monte Carlo Simulation** - Probabilistic forecasts (1000 iterations)
- **Linear Trend Analysis** - Trend detection
- **Seasonal Adjustment** - Seasonal factor incorporation
- **Confidence Scoring** - Data quality assessment

### Analysis Methods
- **Category Breakdown** - Spending by category with percentages
- **Merchant Analysis** - Frequency, consistency, loyalty scoring
- **Correlation Analysis** - Income-spending relationship
- **Income Elasticity** - Spending sensitivity to income
- **Velocity Calculation** - Spending acceleration over time
- **Streak Counting** - Consecutive period tracking

### Emission & Sustainability
- **CO2 Factor Tables** - Category and merchant-based emissions
- **Carbon Footprint** - Total emissions calculation
- **Sustainability Scoring** - Environmental impact scoring
- **Offset Cost Calculation** - Carbon offset pricing

### Health Scoring
- **Composite Score** - Weighted multi-factor scoring
- **Budget Health** - Budget utilization levels
- **Spending Control** - Daily/weekly/monthly spending control
- **Cleanliness Score** - Data quality and review percentage
- **Bonus Points** - Achievement-based points

---

## Data Flow & Dependencies

### Key Input Sources
- **ExpenseRepository** - All expense data
- **BudgetRepository** - Budget constraints
- **CategoryRepository** - Category definitions
- **TimeProvider** - Current time reference
- **HistoricalSpendingDistribution** - Historical patterns

### Key Output Destinations
- **Dashboard Widgets** - Insights, health scores, forecasts
- **Analytics Screens** - Detailed analytics views
- **Notifications** - Alerts and recommendations
- **Reports** - Export and reporting
- **Recommendations** - AI-powered suggestions

---

## Recommendations for Semantic Contract

### 1. CREATE NEW CONTRACT ENTRIES FOR:
- `AnalyticsEngine` grouping (Insights, Advanced, Anomaly)
- `ForecastingEngine` grouping (Monte Carlo, Budget, Cash Flow)
- `HealthScoringEngine` (Financial Health Calculator)
- `EnvironmentalImpact` (Carbon Footprint, Lifestyle Inflation)
- `SharedExpenseEngine` (Settlements, Splits)
- `LocationIntelligence` (Heatmap, Travel, Area spending)

### 2. DOCUMENT KEY FORMULAS:
- Spending pace formula: `(currentDailyRate / baselineDailyRate) * 100`
- Budget health: `spent / budget * 100`
- Projected spending: `currentSpent * (daysInMonth / daysElapsed)`
- Income elasticity: `% change spending / % change income`
- Lifestyle inflation: `spendingTrend - incomeTrend`

### 3. ADD VALIDATION RULES:
- Minimum data points required for each analysis (e.g., 3 months for forecasting)
- Confidence thresholds for recommendations
- Edge case handling (month-end, leap years, DST)

### 4. CROSS-REFERENCE:
- Input datasets required
- Output formats and units
- Confidence/accuracy metrics
- Data freshness requirements

---

## Summary Statistics

```
Total Kotlin Files in Domain:        204
Calculation/Analytics Components:    42
Lines of Code (Major Engines):       ~5000+

By Category:
- Analytics Engines:                 6
- Forecasting:                        4
- Budget/Health:                      3
- Savings/Investment:                 3
- Spending Analysis:                  4
- Environmental:                      2
- Location:                           4
- Groups/Shared:                      3
- Subscriptions:                      2
- Tax/Planning:                       2
- Challenges:                         1
- AI/ML:                              5
- Infrastructure:                     6
```

---

## Conclusion

This codebase contains a **sophisticated financial analytics and forecasting system** with 42+ calculation components. The core engines demonstrate:

1. **Statistical Rigor** - Multiple outlier detection methods, percentile analysis, volatility measurement
2. **Probabilistic Forecasting** - Monte Carlo simulations with confidence scoring
3. **Multi-dimensional Analysis** - Time-based, category-based, merchant-based, location-based
4. **Specialized Domains** - Carbon footprint, lifestyle inflation, shared expenses
5. **Real-time Health Scoring** - Composite health metrics with bonus systems

**Immediate Action:** Document the 15-20 missing high-priority components to complete the semantic contract map and enable better system understanding and maintenance.

