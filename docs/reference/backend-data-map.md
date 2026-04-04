# Data Layer Architecture Map
**Expense Tracker | Comprehensive Data Layer Reference**  
*Last Updated: April 2026 | Version: 69 (Database Schema)*

---

## Executive Summary

The Data Layer follows a **Repository Pattern** layered over Room ORM and Android-native storage. The architecture supports:

- **46 entities** spanning expenses, budgets, investments, subscriptions, AI, and more
- **45 DAOs** with sophisticated query patterns (dynamic filtering, aggregations, raw queries)
- **36+ repositories** handling business logic, aggregations, and external integrations
- **4 geocoding providers** (Nominatim, Photon, Google Places, Geoapify) with intelligent gating
- **Multi-layered security**: EncryptedSharedPreferences for API keys, biometric protection ready
- **46 database migrations** (v6→v69) tracking feature evolution

---

## Directory Structure

```
data/
├── database/               # Room ORM + migrations (46 migrations, 46 entities)
│   ├── AppDatabase.kt      # RoomDatabase (69 version, 46 entity references)
│   ├── converter/          # Type converters
│   │   └── Converters.kt   # @TypeConverter for complex types
│   ├── dao/                # 45 DAOs
│   │   ├── ExpenseDao.kt
│   │   ├── CategoryDao.kt
│   │   ├── BudgetDao.kt
│   │   ├── MerchantNormalizationDao.kt
│   │   ├── MerchantLocationDao.kt
│   │   ├── ReceiptItemCategorizationDao.kt
│   │   ├── AiArtifactDao.kt
│   │   ├── AiChatSessionDao.kt
│   │   ├── AiChatMessageDao.kt
│   │   ├── [40+ more DAOs...]
│   │   └── [See DAO Registry below]
│   ├── entity/             # 46 Room entities
│   │   ├── Expense.kt      # Core expense with transfer/shared/business fields
│   │   ├── Category.kt     # Categories with icons & colors
│   │   ├── Budget.kt       # Period-based budgets with warning thresholds
│   │   ├── ScannedReceipt.kt  # OCR results + matching status
│   │   ├── AiArtifactEntity.kt # AI briefings, explanations (phase 1)
│   │   ├── MerchantCanonical.kt # Normalized merchant master
│   │   ├── MerchantAlias.kt     # Raw merchant name → canonical FK
│   │   ├── [40+ more entities...]
│   │   └── [See Entity Registry below]
│   └── model/              # Room query result POJOs
│       ├── ExpenseWithCategory.kt      # @Transaction join result
│       ├── ExpenseWithCategoryName.kt  # Name-based variant
│       ├── DashboardWidgetConfig.kt    # Widget state POJO
│       ├── PendingReviewWithReceipt.kt # Joins pending_reviews → scanned_receipts
│       └── ExpenseWithCategory_Extensions.kt
│
├── repository/             # 36+ business logic repositories
│   ├── ExpenseRepository.kt            # Core expense CRUD + analytics (merchants, trends)
│   ├── CategoryRepository.kt           # Category CRUD
│   ├── BudgetRepository.kt            # Budget alerts & forecasting
│   ├── ReceiptRepository.kt           # Receipt matching & item categorization
│   ├── MerchantNormalizationRepository.kt  # Merchant canonicalization
│   ├── MerchantLocationRepository.kt   # Geocoding cache & corrections
│   ├── MerchantCategoryRepository.kt   # Merchant → category mappings
│   ├── AiArtifactRepositoryImpl.kt     # AI briefing storage (v34)
│   ├── AiChatRepositoryImpl.kt         # Chat session persistence (v35)
│   ├── RecurringExpenseRepository.kt   # Subscriptions & recurring expenses
│   ├── FinancialWeatherRepository.kt   # Budget forecasting
│   ├── PlannedExpenseRepository.kt     # Future expense planning
│   ├── SavingsGoalRepository.kt        # Savings targets & progress
│   ├── WarrantyTrackerRepository.kt    # Warranty tracking (v38)
│   ├── InvestmentDao.kt                # Portfolio tracking (v45)
│   ├── MultiCurrencyRepository.kt      # Exchange rates (v42)
│   ├── GroupsRepositoryImpl.kt          # Shared expenses (v43)
│   ├── DatabaseBackupRepositoryImpl.kt  # Export/restore pipeline
│   ├── AnalyticsRepository.kt          # Aggregations (daily, weekly, monthly, by merchant)
│   ├── DashboardRepository.kt          # Widget data aggregation
│   ├── NotificationRepository.kt       # Raw notification CRUD
│   ├── [16+ more repositories...]
│   └── [See Repository Registry below]
│
├── ai/                    # AI services (local + cloud)
│   ├── provider/           # 33 AI service providers
│   │   ├── CloudCategorizationAssistService.kt
│   │   ├── OnDeviceCategorizationAssistService.kt
│   │   ├── HybridCategorizationAssistService.kt
│   │   ├── NoOpCategorizationAssistService.kt
│   │   ├── CloudReceiptAssistService.kt
│   │   ├── OnDeviceReceiptAssistService.kt
│   │   ├── CloudReceiptItemCategorizationService.kt
│   │   ├── OnDeviceReceiptItemCategorizationService.kt
│   │   ├── CloudDashboardBriefingService.kt
│   │   ├── OnDeviceDashboardBriefingService.kt
│   │   ├── CloudDedupeJudgeService.kt
│   │   ├── OnDeviceSemanticDuplicateDetector.kt
│   │   ├── DefaultAiEnvironmentMonitor.kt
│   │   ├── OnDeviceReviewPriorityScorer.kt
│   │   ├── OnDeviceNotificationParser.kt
│   │   ├── [18+ more providers...]
│   │   └── SmartReceiptAssistService.kt
│   └── worker/           # Async AI job scheduling
│       ├── AiWorkSchedulerImpl.kt
│       └── DailyBriefingWorker.kt
│
├── location/             # Geocoding & geospatial services
│   ├── CompositeGeocodingService.kt    # Multi-provider orchestrator
│   ├── NominatimGeocodingService.kt    # OSM reverse geocoding
│   ├── PhotonGeocodingService.kt       # Free photo-based search
│   ├── GeoapifyGeocodingService.kt     # Commercial API
│   ├── GooglePlacesGeocodingService.kt # Google Places API (opt-in quota)
│   ├── OverpassNearbyService.kt        # OSM POI finder (bars, shops, etc.)
│   ├── AndroidForegroundLocationProvider.kt  # Device GPS provider
│   ├── LocationBackfillWorker.kt       # Async geocode backfill
│   └── MerchantKeyBackfillWorker.kt    # Async merchant key generation (v32)
│
├── email/               # Email receipt ingestion
│   ├── EmailReceiptIngestionService.kt  # IMAP/POP3 client
│   └── provider/        # Email parser implementations
│       ├── EmailReceiptParser.kt
│       ├── AmazonReceiptParser.kt
│       ├── AppleReceiptParser.kt
│       └── UberReceiptParser.kt
│
├── provider/            # Data providers
│   └── MerchantCategoryProvider.kt  # Bulk merchant → category lookup
│
├── security/            # Secure storage
│   └── SecureKeyStorage.kt  # EncryptedSharedPreferences for API keys
│
└── service/             # Platform services
    └── AndroidNotificationService.kt  # Notification publishing
```

---

## Database Schema Summary

| Aspect | Details |
|--------|---------|
| **Version** | 69 |
| **Total Entities** | 46 |
| **Total DAOs** | 45 |
| **Total Migrations** | 46 (MIGRATION_6_7 → MIGRATION_69_70+) |
| **Type Converters** | Custom: Enums, Lists, Dates |
| **Export Schema** | ✓ Enabled (for migrations verification) |

---

## Entities Registry (46 Total)

### Core Financial (7)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **Expense** | `expenses` | Core transaction record with location, business, share fields | `rawNotificationId` → raw_notifications, `categoryId` → categories | 12: rawNotificationId, transactionType+date, categoryId+date, merchant+date, dedupeKey (unique), latitude+longitude, merchantKey, isBusinessExpense |
| **Category** | `categories` | User-defined or system expense categories | None | isDefault |
| **Budget** | `budgets` | Period-based spend limits with warnings | `categoryId` → categories | categoryId, isActive |
| **PlannedExpense** | `planned_expenses` | Future planned transactions | `categoryId` → categories | date, categoryId |
| **RecurringExpense** | `manual_recurring_expenses` | Subscriptions & repeating expenses (v12) | None | None (added v40: isActive, isSubscription) |
| **SavingsGoal** | `savings_goals` | Savings targets with progress | None | None |
| **Investment** | `investments` | Portfolio holdings with price tracking (v45) | None | type, symbol, isActive |
| **InvestmentValue** | `investment_values` | Historical price snapshots (v45) | `investmentId` → investments | investmentId+timestamp, timestamp |

### Receipts & Items (7)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **ScannedReceipt** | `scanned_receipts` | OCR-extracted receipt data with match status (v9) | `expenseId` → expenses | expenseId, createdAt, matchStatus |
| **ReceiptItemCategorization** | `receipt_item_categorizations` | AI-suggested categories per receipt line item (v37) | `receiptId` → scanned_receipts, `expenseId` → expenses | receiptId, expenseId, suggestedCategoryId, userCorrectedCategoryId |
| **Warranty** | `warranties` | Product warranties extracted from receipts (v38) | `receiptId` → scanned_receipts, `expenseId` → expenses | receiptId, expenseId, warrantyEndDate, status |
| **ReturnWindow** | `return_windows` | Product return periods from receipts (v38) | `receiptId` → scanned_receipts, `expenseId` → expenses | receiptId, expenseId, returnDeadline, status |
| **EmailReceiptSource** | `email_receipts` | Email sources for receipt ingestion | None | None |
| **RawNotification** | `raw_notifications` | Intercepted payment notifications before processing | None | isRelevant, packageName+timestamp+title+text (unique, v22) |
| **PendingReview** | `pending_reviews` | AI-suggested expenses awaiting user review | `rawNotificationId` → raw_notifications, `scannedReceiptId` → scanned_receipts | rawNotificationId, scannedReceiptId, status, status+createdAt |

### Merchants (6)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **MerchantCanonical** | `merchant_canonicals` | Normalized merchant master (v17) | `categoryId` → categories | normalizedName (unique), searchKey, categoryId |
| **MerchantAlias** | `merchant_aliases` | Raw name → canonical mapping (v17) | `canonicalId` → merchant_canonicals | rawName (unique), normalizedKey, canonicalId |
| **MerchantCategory** | `merchant_categories` | Merchant → category associations | None | normalizedCanonicalName (v26) |
| **MerchantLocation** | `merchant_locations` | Geocoded merchant coordinates cache (v28) | None | normalizedMerchantName+areaKey (unique, v30), lastResolvedAt |
| **MerchantLocationCorrection** | `merchant_location_corrections` | User-corrected merchant locations (v28) | None | normalizedMerchantName+areaKey (unique), createdAt |
| **UserCorrection** | `user_corrections` | User edits to auto-suggested fields (v16) | `originalCategoryId` → categories, `correctedCategoryId` → categories | originalCategoryId, correctedCategoryId, packageName, wasApproved, wasRejected |

### AI & Chat (6)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **AiArtifactEntity** | `ai_artifacts` | AI-generated briefings, explanations, summaries (v34) | None | targetKey+capability+promptVersion+sourceHash (unique), targetKey+capability+updatedAt, status+updatedAt, expiresAt |
| **AiChatSessionEntity** | `ai_chat_sessions` | Chat conversation sessions (v35) | None | updatedAt, createdAt |
| **AiChatMessageEntity** | `ai_chat_messages` | Individual chat messages (v35) | `sessionId` → ai_chat_sessions (CASCADE) | sessionId, sessionId+createdAt, createdAt |
| **RecommendationEntity** | `recommendations` | AI-generated action recommendations (v36) | `sourceArtifactId` (text) | userId+status+expiresAt, sourceArtifactId, createdAt, expiresAt |
| **PromptStateEntity** | `prompt_states` | LLM prompt versioning & A/B testing | None | None |
| **SpendingPersonalityProfileEntity** | `spending_personality_profiles` | User spending behavior analysis results | None | None |

### Budgeting & Forecasting (6)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **BudgetForecast** | `budget_forecasts` | AI-predicted budget outcomes (v44) | `budgetId` → budgets | budgetId, forecastDate, isActive |
| **BudgetAdjustmentRecommendation** | `budget_adjustments` | Suggested budget changes | None | None |
| **StressForecastSnapshot** | `stress_forecast_snapshots` | Financial stress scoring snapshots | None | None |
| **HealthScoreHistory** | `health_score_history` | Financial health metric evolution | None | None |
| **SavingsSweepPlan** | `savings_sweep_plans` | Automatic savings routing rules | None | None |
| **SubscriptionCandidate** | `subscription_candidates` | Detected recurring charges for user confirmation | None | None |

### Shared Expenses (4)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **ExpenseGroup** | `expense_groups` | Shared expense pool header (v43) | None | isActive, createdAt |
| **GroupMember** | `group_members` | Pool participant definition (v43) | `groupId` → expense_groups | groupId, groupId+name (unique) |
| **GroupExpense** | `group_expenses` | Expense linked to a pool with split (v43) | `groupId` → expense_groups, `expenseId` → expenses, `paidById` → group_members | groupId, expenseId, paidById, groupId+date |
| **SplitTemplate** | `split_templates` | Saved split patterns for reuse (v47) | None | isDefault |

### Subscriptions (3)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **SubscriptionPriceHistory** | `subscription_price_history` | Price change tracking for subscriptions (v40) | `subscriptionId` → manual_recurring_expenses | subscriptionId, subscriptionId+recordedAt |
| **SubscriptionUsage** | `subscription_usage` | Usage metrics for subscription optimization (v40) | `subscriptionId` → manual_recurring_expenses | subscriptionId, subscriptionId+usedAt |
| **SplitItemAssignment** | `split_item_assignments` | Receipt item → participant allocation (v47) | `expenseId` → expenses | expenseId, receiptItemId |

### Bank & Multi-Currency (3)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **BankConnection** | `bank_connections` | Open Banking API credentials (v46) | `defaultCategoryId` → categories | bankId, isActive, lastSync |
| **ExchangeRate** | `exchange_rates` | Currency pair conversion rates (v42) | None | fromCurrency+toCurrency (unique), lastUpdated |
| **SourceStats** | `source_stats` | Notification source statistics (v14) | None | None |

### Misc. Business (3)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **MileageTracking** | `mileage_tracking` | Business trip mileage deductions (v41) | `linkedExpenseId` → expenses | linkedExpenseId, date, isBusinessTrip |
| **AnomalyAlert** | `anomaly_alerts` | Fraud/unusual transaction flags | None | None |
| **BlockedPackage** | `blocked_packages` | Notification sources to ignore | None | None |

---

## DAOs Registry (45 Total)

### Core CRUD DAOs (8)

| DAO | Table | Key Methods | Custom Queries |
|-----|-------|-------------|-----------------|
| **ExpenseDao** | expenses | getById, insert, insertAll, delete, getPage, getAllFlow, getAllWithCategoryFlow | getExpensesDynamic (RawQuery), getExpensesWithCategoryFiltered, getExpensesWithCategoryInPeriod, getExpensesSince, getRecentExpensesForMerchant, getTotalSpentFlow, updateCategory, updateMerchant, updateTransactionType, checkDuplicate |
| **CategoryDao** | categories | getAll, getById, insert, insertAll, update, delete, getDefaultCategories | None |
| **BudgetDao** | budgets | getById, getAll, insert, update, delete, insert(List), updateAmount, updateNotifyAtWarning, updateNotifyAtCritical, resetNotifyDates | getActiveBudgetForCategory, getTotalBudgetedAmount, getBudgetUtilization |
| **RecurringExpenseDao** | manual_recurring_expenses | getAll, getById, insert, update, delete, getActive, getUpcoming, getTotalRecurringExpense | getRecurringExpensesForMerchant |
| **PlannedExpenseDao** | planned_expenses | getAll, getById, insert, update, delete, getPlannedExpensesBetween | None |
| **SavingsGoalDao** | savings_goals | getAll, getById, insert, update, delete, updateCurrentAmount, updateProgress | None |
| **UserCorrectionDao** | user_corrections | insert, getAll, getForPackage, getApprovedCorrections, getRejectedCorrections | None (has indices on packageName, wasApproved, wasRejected) |
| **RawNotificationDao** | raw_notifications | insert, getAll, getById, delete, getUnprocessed, markAsRelevant, deleteOldNotifications | getByPackageNameAndTime |

### Receipt & Item Categorization (4)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **ScannedReceiptDao** | scanned_receipts | getAll, getById, insert, update, delete, getByExpenseId, getUnmatchedReceipts, updateMatchStatus, updateMatchConfidence | getReceiptsByStatus |
| **ReceiptItemCategorizationDao** | receipt_item_categorizations | insert, getById, getByReceiptId, getByExpenseId, updateUserCorrectedCategory | getUncorrectedItems, getConfidenceStats |
| **WarrantyDao** | warranties | insert, getAll, getById, getByReceiptId, getByExpenseId, getActiveWarranties, getExpiringWarranties | getWarrantiesByStatus |
| **ReturnWindowDao** | return_windows | insert, getAll, getById, getByReceiptId, getByExpenseId, getReturnableItems, getExpiredReturns | getReturnsByStatus |

### Merchant Management (5)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **MerchantNormalizationDao** | merchant_canonicals, merchant_aliases | getCanonicalByNormalizedName, getCanonicalBySearchKey, getAliasesByCanonicalId, createCanonical, createAlias, updateCanonicalStats | getMostUsedMerchants, getFuzzyMatches |
| **MerchantLocationDao** | merchant_locations | getByMerchantName, getCachedLocation, upsertLocation, deleteOldCaches, getAllCaches | getLocationsByAreaKey, getLocationsNeedingBackfill |
| **MerchantCategoryDao** | merchant_categories | insert, getAll, getById, getByMerchantName, getByNormalizedName, updateCategory, deleteByMerchantName | None |
| **MerchantCategoryRepository** | (cross-table logic) | getMerchantCategorySuggestions, autoAssignCategories, recordMerchantCategoryAssociation | None |
| **LocationBackfillWorker** | (worker service) | backfillMissingLocations, prioritizeUnresolvedExpenses | None |

### AI & Chat (4)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **AiArtifactDao** | ai_artifacts | insert, getById, getByTargetKey, getLatestByCapability, getByStatus, upsert, deleteOldArtifacts | getArtifactsForCleanup, getExpiringArtifacts |
| **AiChatSessionDao** | ai_chat_sessions | insert, getAll, getById, delete, updateTitle, getRecentSessions, deleteOldSessions | None |
| **AiChatMessageDao** | ai_chat_messages | insert, getById, getBySessionId, deleteBySessionId, getMessagesSince | getSessionMessages |
| **RecommendationDao** | recommendations | insert, getAll, getById, getActiveRecommendations, markDismissed, deleteExpired | getUserRecommendations, getByStatus |

### Budgeting & Analytics (5)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **BudgetForecastDao** | budget_forecasts | insert, getById, getByBudgetId, getActiveForecasts, updateActualSpending, updateAccuracy | getForecastsNeedingRecalc |
| **HealthScoreHistoryDao** | health_score_history | insert, getAll, getById, getRecentScores | getScoresTrend |
| **SourceStatsDao** | source_stats | insert, getById, getAll, update, getTopSources, recordNotification, recordAccepted, recordRejected | None |
| **AnalyticsRepository** | (aggregation queries) | getDailyTotals, getWeeklyTotals, getMonthlyTotals, getCategoryTotals, getMerchantStats, getLocationClusters | Complex SQL aggregations with date ranges |
| **DashboardRepository** | (widget aggregations) | getExpenseStats, getCategoryBreakdown, getBudgetStatus, getTopMerchants, getTrendingCategories | Custom queries for dashboard |

### Shared Expenses (4)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **ExpenseGroupDao** | expense_groups | getAll, getById, insert, update, delete, getActiveGroups | None |
| **GroupMemberDao** | group_members | getByGroupId, insert, delete, updateMember, getGroupMembersCount | None |
| **GroupExpenseDao** | group_expenses | insert, getByGroupId, getByExpenseId, delete, getGroupBalance, calculateSplits | getNeedsSettlement |
| **SplitTemplateDao** | split_templates | getAll, getById, insert, update, delete, getDefault, incrementUseCount | None |

### Subscriptions (3)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **SubscriptionPriceHistoryDao** | subscription_price_history | insert, getBySubscriptionId, getPriceChanges, getLatestPrice | getPriceHistory |
| **SubscriptionUsageDao** | subscription_usage | insert, getBySubscriptionId, getUsageStats, calculateMonthlyUsage | getUsageMetrics |
| **SplitItemAssignmentDao** | split_item_assignments | insert, getByExpenseId, getByReceiptItemId, delete, updatePaymentStatus | None |

### Bank & Multi-Currency (3)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **BankConnectionDao** | bank_connections | insert, getAll, getById, update, delete, getActive, updateLastSync, recordError | None |
| **ExchangeRateDao** | exchange_rates | insert, getRate, updateRate, getAllRates, getStaleRates, deleteOldRates | getRatesBySourceCurrency |
| **InvestmentDao** | investments | insert, getAll, getById, update, delete, getActive, updateCurrentPrice | getInvestmentsByType, getPortfolioValue |

### Misc (2)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **MileageTrackingDao** | mileage_tracking | insert, getAll, getById, delete, getBusinessTrips, calculateTotalDeduction | getTripsForPeriod |
| **AnomalyAlertDao** | anomaly_alerts | insert, getAll, getById, markAsReviewed, deleteOldAlerts | getActiveAlerts |

---

## Repositories Registry (36+ Total)

### Core Business Logic (10)

| Repository | Purpose | Key Methods | DAO Dependencies |
|------------|---------|------------|------------------|
| **ExpenseRepository** | Expense CRUD + analytics + deduplication | insertExpense, updateExpense, deleteExpense, getExpenses, getExpensesByDate, getExpensesByMerchant, getDailyTotals, getMonthlyTotals, getMerchantStats, checkDuplicate, calculateMerchantKey | ExpenseDao, UserCorrectionDao, PendingReviewDao, MerchantCategoryRepository |
| **CategoryRepository** | Category CRUD + defaults | getCategories, getCategoryById, createCategory, updateCategory, deleteCategory, getDefaultCategories | CategoryDao |
| **BudgetRepository** | Budget management + alerts | getBudgets, createBudget, updateBudget, deleteBudget, calculateUtilization, checkBudgetExceeded, getAlertConfig | BudgetDao, ExpenseDao |
| **ReceiptRepository** | Receipt OCR + matching + item categorization | insertReceipt, matchReceiptToExpense, getReceiptsByStatus, categorizeItems, getItemCategories, updateItemCategory | ScannedReceiptDao, ReceiptItemCategorizationDao, ExpenseDao |
| **MerchantNormalizationRepository** | Merchant deduplication & canonicalization | normalizeAndStoreAlias, getMerchantCanonical, getCanonicalStats, recordMerchantUsage, fuzzyFindMerchant | MerchantNormalizationDao, MerchantCategoryRepository |
| **MerchantLocationRepository** | Geocoding cache + corrections | geocodeMerchant, getLocationCache, recordLocationCorrection, backfillLocations, clearOldCache | MerchantLocationDao, CompositeGeocodingService |
| **RecurringExpenseRepository** | Subscription detection & management | detectSubscriptions, getRecurringExpenses, createRecurring, updateRecurring, deleteRecurring, forecastNextBilling | RecurringExpenseDao, SubscriptionPriceHistoryDao, SubscriptionUsageDao |
| **PlannedExpenseRepository** | Future expense planning | createPlannedExpense, getPlannedForPeriod, updatePlanned, deletePlanned, convertToActual | PlannedExpenseDao |
| **SavingsGoalRepository** | Savings target tracking | createGoal, updateProgress, getGoalsByName, deleteGoal, calculateDaysToTarget | SavingsGoalDao |
| **WarrantyTrackerRepository** | Warranty extraction & alerts | insertWarranty, getActiveWarranties, getExpiringWarranties, markWarrantyClaimed, getWarrantiesByStatus | WarrantyDao, ScannedReceiptDao |

### AI & Insights (6)

| Repository | Purpose | Key Methods | DAO Dependencies |
|------------|---------|------------|------------------|
| **AiArtifactRepositoryImpl** | AI-generated content storage & retrieval (v34) | upsertArtifact, getArtifactByKey, getLatestByCapability, deleteExpiredArtifacts, getArtifactsByStatus | AiArtifactDao |
| **AiChatRepositoryImpl** | Chat session persistence (v35) | createSession, getSession, deleteSession, addMessage, getMessages, getSessions | AiChatSessionDao, AiChatMessageDao |
| **FinancialWeatherRepository** | Budget forecasting & stress scoring | generateForecast, updateActuals, calculateStressScore, getPredictionAccuracy, recordBudgetEvent | BudgetForecastDao, HealthScoreHistoryDao, StressForecastSnapshotDao |
| **AiEngagementRepositoryImpl** | User engagement tracking for AI | recordInteraction, getEngagementMetrics, trackPromptVersion | (custom persistence) |
| **AiSettingsRepositoryImpl** | AI feature toggle & mode selection | getAiMode, setAiMode, getFeatureConfig, updateProviderSettings | (SharedPreferences) |
| **RecommendationRepository** | AI-generated action recommendations (v36) | createRecommendation, getRecommendations, dismissRecommendation, deleteExpired, getByStatus | RecommendationDao |

### Analytics & Dashboards (5)

| Repository | Purpose | Key Methods |
|------------|---------|------------|
| **AnalyticsRepository** | Complex aggregation queries | getDailyTotals, getWeeklyTotals, getMonthlyTotals, getCategoryDistribution, getMerchantRanking, getLocationClusters, getDayOfWeekAnalysis |
| **DashboardRepository** | Dashboard widget data aggregation | getExpenseStats, getCategoryBreakdown, getBudgetStatus, getTopMerchants, getTrendingCategories, getRecentExpenses |
| **SourceStatsRepository** | Notification source analytics | getSourceStats, recordNotification, recordAccepted, recordRejected, getDuplicateRate, getTopSources |
| **ReviewQueueRepository** | Pending review prioritization | getPendingReviews, prioritizeByConfidence, prioritizeBySource, recordReview, getReviewStatus |
| **NotificationProcessingPipeline** | Notification ingestion & processing | processNotification, validateAmount, suggestCategory, checkDuplicate, flagAnomalies |

### Business & Financial Features (8)

| Repository | Purpose | Key Methods |
|------------|---------|------------|
| **BusinessExpenseRepository** | Business vs. personal separation & deductions | markAsBusinessExpense, calculateDeductions, getTaxDeductibleTotal, assignToProject, getBusinessExpensesByCategory, generateDeductionReport |
| **MultiCurrencyRepository** | Currency conversion & rates (v42) | convertAmount, getExchangeRate, updateRate, getHistoricalRate, bulkConvert, setBaseCurrency |
| **GroupsRepositoryImpl** | Shared expense groups & splits (v43) | createGroup, addMember, createGroupExpense, calculateSplits, settleDebts, getGroupBalance |
| **InvestmentRepository** | Portfolio tracking (v45) | insertInvestment, getPortfolio, updateCurrentPrice, calculateGainLoss, getPerformanceStats, calculateYield |
| **MerchantCategoryRepository** | Merchant → category auto-assignment | suggestCategory, recordAssociation, getBulkSuggestions, updateMapping, getAccuracy |
| **CurrencySettingsRepositoryImpl** | Default currency & conversion settings | setBaseCurrency, getBaseCurrency, setDisplayCurrency, getCurrencyFormat |
| **PromptStateRepository** | LLM prompt versioning & A/B testing | recordPromptVersion, getActiveVersion, logPromptUsage, measureAccuracy |
| **MerchantRulesRepository** | Merchant normalization rules engine | applyRules, recordRule, getRulesByMerchant, evaluateMatchConfidence |

### Data Management (3)

| Repository | Purpose | Key Methods |
|------------|---------|------------|
| **DatabaseBackupRepositoryImpl** | Export/restore & versioning | exportDatabase, importDatabase, getBackupList, deleteBackup, restoreFromBackup |
| **AccountingExportRepository** | Tax/accounting report generation | exportForTaxSeason, generateP&L, generateCashFlow, categorizeForTaxes |
| **NotificationRepository** | Raw notification CRUD & filtering | insertNotification, getById, getAll, delete, markAsProcessed, getByPackageAndTime |

### Infrastructure & Integration (4)

| Repository | Purpose | Key Methods |
|------------|---------|------------|
| **BankConnectionRepository** | Open Banking API (v46) | createConnection, deleteConnection, getConnections, updateLastSync, recordError, getConnectionStatus |
| **WidgetStyleRepositoryImpl** | Widget appearance customization | getWidgetConfig, updateWidgetConfig, saveTheme, getThemeList |
| **EmailReceiptRepository** | Email receipt ingestion config | getEmailAccounts, addAccount, removeAccount, syncReceipts, getLastSync |
| **UserCorrectionRepository** | Track user edits for ML training | recordCorrection, getCorrections, getApprovedCorrections, getRejectionReasons, calculateAccuracy |

---

## Network & API Integrations

### Geocoding Services (5 Providers)

| Service | Purpose | API | Quota Model | Status |
|---------|---------|-----|-------------|--------|
| **Nominatim (OSM)** | Free reverse geocoding | HTTPS REST | Unlimited (1 req/sec rate limit) | Primary background resolution |
| **Photon** | Photo-based address search | HTTPS REST | Free tier: 50 req/min | Interactive picker (low-value queries) |
| **Geoapify** | Commercial geocoding | HTTPS REST | Key-based quota | Interactive picker (premium results) |
| **GooglePlaces** | Google Places API | HTTPS REST | Key-based quota | Interactive picker (user opt-in, quota gating) |
| **Overpass** | OSM POI finder (bars, shops, etc.) | HTTPS REST | Unlimited | Merchant nearby search |

### Composite Service Logic (CompositeGeocodingService)
- **Interactive Picker** (`searchMultiple`): Fires all providers in parallel, merges, re-ranks by qualifier match, deduplicates by 50m proximity
- **Smart Gating**: 
  - Single-word query → Photon + Nominatim only
  - Multi-word query → All 4 providers
- **Background Resolution** (`search`): Nominatim only (preserves existing biasing logic)
- **Reverse Geocode** (`reverseGeocode`): Nominatim (address lookup)

### Email Ingestion (EmailReceiptIngestionService)
- **Protocols**: IMAP, POP3 (configurable per account)
- **Parsers**: Amazon, Apple, Uber (extensible)
- **Frequency**: User-configured polling

### AI Providers (33 Services)

**Categorization** (5 variants):
- CloudCategorizationAssistService
- OnDeviceCategorizationAssistService
- HybridCategorizationAssistService
- NoOpCategorizationAssistService
- SmartReceiptAssistService

**Receipt Assist** (4 variants):
- CloudReceiptAssistService
- OnDeviceReceiptAssistService
- HybridReceiptAssistService
- SmartReceiptAssistService

**Receipt Item Categorization** (3 variants):
- CloudReceiptItemCategorizationService
- OnDeviceReceiptItemCategorizationService
- HybridReceiptItemCategorizationService

**Dashboard Briefing** (3 variants):
- CloudDashboardBriefingService
- OnDeviceDashboardBriefingService
- HybridDashboardBriefingService

**Duplicate Detection** (3 variants):
- CloudDedupeJudgeService
- OnDeviceSemanticDuplicateDetector
- HybridDedupeJudgeService

**Query Interpretation** (3 variants):
- CloudQueryInterpretationService
- OnDeviceQueryInterpretationService
- HybridQueryInterpretationService

**Other AI Services** (12):
- CloudReviewExplanationService, OnDeviceReviewExplanationService, HybridReviewExplanationService
- CloudWarrantyExtractionService
- DefaultAiEnvironmentMonitor
- OnDeviceNotificationParser
- OnDeviceReviewPriorityScorer
- OnDeviceSemanticDuplicateDetector
- NoOpDedupeJudgeService, NoOpDashboardBriefingService, NoOpQueryInterpretationService, NoOpReceiptAssistService, NoOpReviewExplanationService

---

## Local Storage (Non-Room)

### Encrypted SharedPreferences (SecureKeyStorage)
| Key Constant | Purpose | Value Type | Default |
|--------------|---------|-----------|---------|
| `KEY_GEOAPIFY` | Geoapify API key | String (encrypted) | Null |
| `KEY_GOOGLE_PLACES` | Google Places API key | String (encrypted) | Null |
| `KEY_GEMINI` | Gemini AI API key | String (encrypted) | Null |

**Security**:
- AES-256-GCM encryption
- Android Keystore backend
- Hardware-backed when available (v31+)
- Biometric protection-ready

### Android SharedPreferences (Implicit)
| Use Case | Details |
|----------|---------|
| **AI Settings** | Feature toggles, mode selection (cloud/on-device/hybrid) |
| **User Preferences** | Default currency, language, notification settings |
| **Widget Configuration** | Theme, style, refresh frequency |
| **Email Accounts** | OAuth tokens, endpoint configs (encrypted fields) |

---

## Type Converters (Converters.kt)

| Converter | From ↔ To | Purpose |
|-----------|-----------|---------|
| TransactionType | String ↔ Enum | PURCHASE, WITHDRAWAL, TRANSFER, DEPOSIT |
| PaymentMethod | String ↔ Enum | CARD, CASH, BANK_TRANSFER, etc. |
| TransferDirection | String ↔ Enum | IN, OUT |
| OwnershipFilter | String ↔ Enum | ALL, MINE, NOT_MINE, SHARED, TRANSFER |
| **Custom Lists** | JSON String ↔ List<T> | Receipt items, alternative categories, split details |
| **Date/Time** | Long ↔ Timestamps | Unix milliseconds |

---

## Key Architectural Patterns & Patterns

### 1. **Repository Pattern Over DAOs**
- Repositories wrap DAOs
- Add business logic (deduplication, aggregation, external API calls)
- Expose Flow<T> for reactive updates

### 2. **Room @Transaction**
```kotlin
@Transaction
@Query("SELECT * FROM expenses ...")
fun getExpensesWithCategoryFlow(...): Flow<List<ExpenseWithCategory>>
```
- Joins via POJO `ExpenseWithCategory`
- Automatic FK resolution
- Used extensively for analytics queries

### 3. **RawQuery for Dynamic Filtering**
```kotlin
@RawQuery
suspend fun getExpensesDynamic(query: SupportSQLiteQuery): List<ExpenseWithCategory>
```
- Supports dynamic WHERE, ORDER BY, LIMIT
- Used by `ExpenseRepository.getExpensesDynamic()`

### 4. **Composite Pattern (CompositeGeocodingService)**
- Aggregates 4 geocoding providers
- Parallel execution via Kotlin coroutines
- Smart gating (single-word vs. multi-word queries)

### 5. **Multi-Implementation Pattern (AI Services)**
- Cloud / On-Device / Hybrid / NoOp variants
- Selected via dependency injection + settings
- Enables offline + online + testing scenarios

### 6. **Atomic Operations**
- `dedupeKey` UNIQUE index (v21) prevents duplicate inserts
- `insertAtomic(expense)` uses IGNORE conflict strategy
- Checked via `SELECT changes()`

### 7. **Backfill Workers (Background Async)**
- `LocationBackfillWorker`: Async geocoding (v28+)
- `MerchantKeyBackfillWorker`: Async merchant key generation (v32+)
- `DailyBriefingWorker`: Scheduled AI briefing generation

### 8. **Event-Driven Updates**
- `Flow<List<T>>` for reactive UI updates
- DAOs return Flow for subscriptions
- Repositories transform and aggregate

---

## Database Migrations Summary (46 Total, v6→v69)

| Range | Feature Area | Count | Notes |
|-------|--------------|-------|-------|
| v6–8 | Core schema | 3 | Expenses, Categories, Budgets |
| v9–11 | Receipts + Reviews | 3 | Scanned receipts, Pending reviews |
| v12–13 | Recurring + Planning | 2 | Recurring expenses, Planned expenses, Savings goals |
| v14–16 | User Corrections + Indices | 3 | User correction table, FK/Index cleanup |
| v17–20 | Merchant Dedup + Dedupe | 4 | Merchant canonicalization (v17), dedupe keys (v21) |
| v21–22 | Atomic Safety | 2 | Dedupe key unique index, raw notification dedup |
| v23–27 | Transfers + Locations | 5 | Transfer direction, location fields, merchant locations, geolocation FK |
| v28–30 | Geolocation | 3 | Merchant locations cache, corrections, backfill attempt tracking |
| v31–33 | Merchant Keys | 3 | Unified merchant key (v32), location re-keying wipe (v33) |
| v34–36 | AI Phase 1 | 3 | AI artifacts (v34), Chat (v35), Recommendations (v36) |
| v37–39 | Receipts v2 | 3 | Item categorization (v37), Warranty (v38), Receipt matching (v39) |
| v40–42 | Subscriptions + Currency | 3 | Subscription tables (v40), Business/Personal (v41), Currency (v42) |
| v43–46 | Groups + Bank + Splits | 4 | Shared groups (v43), Budget forecasting (v44), Investments (v45), Bank (v46) |
| v47–50 | Schema Fixes | 4 | Enhanced splits, schema alignment, DEFAULT constraints normalization |
| v51–69 | [TBD - check live file] | 19 | Latest migrations pending full review |

---

## Clean Architecture Compliance

### ✅ Adherence
- **No UI imports** in data layer entities/DAOs
- **No business logic** in entities (data classes)
- **Clear separation**: Domain ↔ Data ↔ UI

### ⚠️ Potential Violations Flagged
Check these files for UI class imports:

```sql
grep -r "import.*\.ui\." app/src/main/java/com/yourname/expensetracker/data/
```

**Expected Result**: None (Clean Architecture intact)

---

## Performance Optimizations

### Indices (60+ across all tables)
- **Compound indices** on high-cardinality queries: `(transactionType, categoryId, date)`
- **UNIQUE indices** for deduplication: `(dedupeKey)`, `(normalizedMerchantName)`, `(merchantAliases.rawName)`
- **NULLABLE indices** for filtering: `(isRelevant)`, `(isActive)`, `(status)`

### Query Patterns
| Pattern | Example | Optimization |
|---------|---------|---------------|
| **Date Range** | expenses WHERE date BETWEEN ... | index: (date) |
| **Category Summary** | GROUP BY categoryId | index: (categoryId) |
| **Merchant Dedup** | WHERE merchantKey = ... | UNIQUE index: (merchantKey) |
| **Location Queries** | WHERE latitude BETWEEN ... AND longitude BETWEEN ... | index: (latitude, longitude) |

### Flow<T> for UI Subscription
- DAOs return `Flow<List<Expense>>` instead of `suspend` for reactive updates
- Pagination via `getPage(limit, offset)` to prevent OOM on large datasets
- Deprecated `getAll()` marked for removal (replaced by `getAllFlow(500)`)

---

## Cross-References & Dependencies

### Repository Dependency Graph (Top-Level)

```
ExpenseRepository
├── ExpenseDao
├── UserCorrectionDao
├── PendingReviewDao
└── MerchantCategoryRepository
    ├── MerchantCategoryDao
    └── MerchantNormalizationRepository
        ├── MerchantNormalizationDao
        └── MerchantLocationRepository
            ├── MerchantLocationDao
            ├── CompositeGeocodingService
            └── MerchantKeyGenerator

FinancialWeatherRepository
├── BudgetForecastDao
├── HealthScoreHistoryDao
├── StressForecastSnapshotDao
└── ExpenseRepository (for historical data)

ReceiptRepository
├── ScannedReceiptDao
├── ReceiptItemCategorizationDao
├── ExpenseRepository (for matching)
└── AiArtifactRepositoryImpl (for item suggestions)

GroupsRepositoryImpl
├── ExpenseGroupDao
├── GroupMemberDao
├── GroupExpenseDao
└── ExpenseRepository (for linking)

AnalyticsRepository
├── ExpenseDao
├── CategoryDao
├── SourceStatsDao
└── MerchantLocationDao

NotificationProcessingPipeline
├── RawNotificationDao
├── PendingReviewDao
├── ExpenseRepository
├── MerchantNormalizationRepository
└── AiArtifactRepositoryImpl
```

### Indirect Dependencies (External)
- **CompositeGeocodingService** → 4 geocoding HTTP clients (Nominatim, Photon, Geoapify, Google Places)
- **EmailReceiptIngestionService** → IMAP/POP3 + 4 email parsers
- **AiChatRepositoryImpl** → AI cloud API (Gemini, etc.)
- **MultiCurrencyRepository** → ExchangeRate API (external source)

---

## Overlapping & Redundant Queries

### ⚠️ Potential Query Redundancy (Review Needed)

| Area | Overlap | Impact | Resolution |
|------|---------|--------|-----------|
| **Expense Filtering** | ExpenseRepository + AnalyticsRepository both query expenses with date ranges | Duplicate WHERE logic | Consolidate filter builder |
| **Merchant Dedup** | MerchantNormalizationRepository + MerchantCategoryRepository both normalize merchant names | Merchant key generated twice | Use single MerchantKeyGenerator |
| **AI Suggestions** | AiChatRepositoryImpl + AiArtifactRepositoryImpl both store AI outputs | Separate storage vs. cache | Define artifact vs. chat distinction |
| **Location Backfill** | LocationBackfillWorker + ExpenseRepository both have geocoding logic | Async vs. sync | Consolidate in LocationRepository |

---

## File Count Summary

```
database/
├── converter/       1 file
├── dao/            45 files (one DAO per entity, most)
├── entity/         46 files (one entity per table)
├── model/           5 files (result POJOs)
├── AppDatabase.kt   1 file
└── GroupTransactionCoordinator.kt  1 file
Total: 99 files

repository/         36 files

ai/
├── provider/       33 files
├── worker/          2 files
Total: 35 files

location/            9 files
email/
├── provider/        4 files
├── EmailReceiptIngestionService.kt  1 file
Total: 5 files

provider/            1 file
security/            1 file
service/             1 file

GRAND TOTAL: 188 files in data layer
```

---

## Recommendations

### 1. **Code Generation Candidate**
- 45 DAOs are largely boilerplate → Consider Android Room code generation plugins or templates

### 2. **Query Consolidation**
- Merge overlapping expense/merchant/location queries into shared builders
- Example: `ExpenseQueryBuilder` for reusable filter + sort logic

### 3. **Migration Testing**
- 46 migrations are complex (schema rewrites, table renames)
- Add migration smoke tests for each version upgrade path

### 4. **AI Provider Testing**
- 33 AI service implementations with shared interface
- Add mock/stub implementations for unit testing without network calls

### 5. **Geocoding Caching**
- Location backfill worker clears old cache (v32-33 wipe)
- Implement LRU eviction instead of full clear to preserve user pins

### 6. **Documentation**
- Add `@Deprecated` markers to obsolete DAOs (e.g., `ExpenseDao.getAll()`)
- Document migration reasoning in code comments (currently sparse)

---

## Related Docs
- [`ARCHITECTURE.md`](../../ARCHITECTURE.md) — High-level architecture
- [`app/build.gradle.kts`](../../app/build.gradle.kts) — Room dependency versions
- [`schemas/`](../../app/schemas/) — Room exported schemas for migration validation

---

**Last Updated**: April 2026 | **Schema Version**: 69 | **Total Entities**: 46 | **Total DAOs**: 45 | **Total Repositories**: 36+
