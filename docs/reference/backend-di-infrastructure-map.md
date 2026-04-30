# ExpenseTracker Dependency Injection & Infrastructure Map

**Refreshed:** May 1, 2026  
**Scope:** Current DI architecture analysis  
**Framework:** Hilt/Dagger2 with SingletonComponent

---

## 1. Directory Structure: `/di/`

```
app/src/main/java/com/yourname/expensetracker/di/
├── ApplicationScope.kt                 (Custom scope annotation)
├── NetworkQualifiers.kt                (Custom qualifiers)
├── AppModule.kt                        (Placeholder module)
├── DispatchersModule.kt                (Threading & coroutines)
├── DatabaseModule.kt                   (Room DB setup)
├── DaoModule.kt                        (DAO provision)
├── NetworkModule.kt                    (OkHttp/Retrofit)
├── SecurityModule.kt                   (Secure storage)
├── ServiceModule.kt                    (Location, notification, utils)
│
├── AiModule.kt                         (AI services & repos)
├── CurrencyModule.kt                   (Currency/exchange rates)
├── TimeModule.kt                       (Time provider)
├── CashFlowModule.kt                   (Cash flow calculator)
├── SavingsModule.kt                    (Savings engines)
├── DashboardContractsModule.kt         (Dashboard contract bindings)
├── DashboardAnomalyModule.kt           (Anomaly orchestration bindings)
├── LocationResolverPortsModule.kt      (Location ports/adapters)
├── NaturalLanguageModule.kt            (Speech + query bindings)
├── ReceiptParsingModule.kt             (Receipt parsing bindings)
├── SavingsRepositoryBindingsModule.kt  (Savings repository bindings)
├── GroupsModule.kt                     (Group expense sharing)
├── InvestmentModule.kt                 (Investment tracking)
├── SubscriptionModule.kt               (Subscription management)
├── TaxModule.kt                        (Tax configuration)
│
├── ParserModule.kt                     (NEW — GreekBankParser with injected homeCurrency)
├── ExportModule.kt                     (Export formatters)
├── BackupRepositoryModule.kt           (Database backups)
├── EmailIngestionModule.kt             (Email receipt parsing)
├── OcrImprovementsModule.kt            (OCR enhancements)
├── EmptyStateModule.kt                 (UI registry)
└── EmptyStateRegistryInitializer.kt    (Registry bootstrap)
```

**Module inventory:** current modules plus feature-specific bindings  
**Note:** stale empty-module assumptions have been removed

---

## 2. Hilt Modules Reference

### 2.1 Core Infrastructure Modules

#### **ApplicationScope.kt**
- **Type:** Qualifier annotation
- **Scope:** RUNTIME
- **Purpose:** Marks CoroutineScope tied to application lifecycle
- **Usage:** Long-running operations, background tasks, app-wide monitoring

**Code:**
```kotlin
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
```

---

#### **NetworkQualifiers.kt**
- **Type:** Qualifier annotation
- **Purpose:** Distinguishes between different OkHttpClient instances
- **Qualifiers:**
  - `@LocationHttpClient` - Dedicated client for location/geocoding services

---

#### **AppModule.kt**
- **File:** `AppModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Placeholder object module
- **Status:** compatibility shell; providers live in specialized modules
- **Purpose:** Backwards compatibility, reserved for future expansion

**Note:** Database and service providers have been refactored to DatabaseModule, DaoModule, and ServiceModule.

---

#### **DispatchersModule.kt**
- **File:** `DispatchersModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods
- **Qualifiers Defined:**
  - `@DefaultDispatcher`
  - `@IoDispatcher`
  - `@MainDispatcher`

**@Provides Methods:**

| Method | Returns | Qualifier | Scope | Notes |
|--------|---------|-----------|-------|-------|
| `providesDefaultDispatcher()` | `CoroutineDispatcher` | `@DefaultDispatcher` | No scope | `Dispatchers.Default` |
| `providesIoDispatcher()` | `CoroutineDispatcher` | `@IoDispatcher` | No scope | `Dispatchers.IO` |
| `providesMainDispatcher()` | `CoroutineDispatcher` | `@MainDispatcher` | No scope | `Dispatchers.Main` |
| `providesApplicationScope()` | `CoroutineScope` | `@ApplicationScope` | `@Singleton` | SupervisorJob + DefaultDispatcher |

**Usage:** Injected into repositories, use cases, and ViewModels for background work.

---

### 2.2 Database Modules

#### **DatabaseModule.kt**
- **File:** `DatabaseModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods

**@Provides Methods:**

| Method | Returns | Scope | Notes |
|--------|---------|-------|-------|
| `provideDatabase()` | `AppDatabase` | `@Singleton` | Room database with WAL journaling, migrations enabled |
| `provideGroupTransactionCoordinator()` | `GroupTransactionCoordinatorInterface` | `@Singleton` | ACID-compliant transaction coordinator (HIGH-06 FIX) |

**Features:**
- ✅ Write-Ahead Logging (WAL) journaling mode
- ✅ All migrations registered via `AppDatabase.ALL_MIGRATIONS`
- ✅ ⚠️ ISSUE-1: Never destructively wipes user data on migration failures

---

#### **DaoModule.kt**
- **File:** `DaoModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module - provides all DAOs

**DAO Provision Methods (37 total):**

| DAO | Method Name | Qualifier |
|-----|------------|-----------|
| `PlannedExpenseDao` | `providePlannedExpenseDao()` | None |
| `SavingsGoalDao` | `provideSavingsGoalDao()` | None |
| `RawNotificationDao` | `provideRawNotificationDao()` | None |
| `BlockedPackageDao` | `provideBlockedPackageDao()` | None |
| `ExpenseDao` | `provideExpenseDao()` | None |
| `BudgetDao` | `provideBudgetDao()` | None |
| `ScannedReceiptDao` | `provideScannedReceiptDao()` | None |
| `CategoryDao` | `provideCategoryDao()` | None |
| `MerchantCategoryDao` | `provideMerchantCategoryDao()` | None |
| `PendingReviewDao` | `providePendingReviewDao()` | None |
| `UserCorrectionDao` | `provideUserCorrectionDao()` | None |
| `SourceStatsDao` | `provideSourceStatsDao()` | None |
| `RecurringExpenseDao` | `provideRecurringExpenseDao()` | None |
| `ManualRecurringExpenseDao` | `provideManualRecurringExpenseDao()` | None |
| `MerchantNormalizationDao` | `provideMerchantNormalizationDao()` | None |
| `MerchantLocationDao` | `provideMerchantLocationDao()` | None |
| `RecommendationDao` | `provideRecommendationDao()` | None |
| `ReceiptItemCategorizationDao` | `provideReceiptItemCategorizationDao()` | None |
| `WarrantyDao` | `provideWarrantyDao()` | None |
| `ReturnWindowDao` | `provideReturnWindowDao()` | None |
| `SubscriptionPriceHistoryDao` | `provideSubscriptionPriceHistoryDao()` | None |
| `SubscriptionUsageDao` | `provideSubscriptionUsageDao()` | None |
| `MileageTrackingDao` | `provideMileageTrackingDao()` | None |
| `ExchangeRateDao` | `provideExchangeRateDao()` | None |
| `ExpenseGroupDao` | `provideExpenseGroupDao()` | None |
| `GroupMemberDao` | `provideGroupMemberDao()` | None |
| `GroupExpenseDao` | `provideGroupExpenseDao()` | None |
| `BudgetForecastDao` | `provideBudgetForecastDao()` | None |
| `InvestmentDao` | `provideInvestmentDao()` | None |
| `InvestmentValueDao` | `provideInvestmentValueDao()` | None |
| `BankConnectionDao` | `provideBankConnectionDao()` | None |
| `SplitTemplateDao` | `provideSplitTemplateDao()` | None |
| `SplitItemAssignmentDao` | `provideSplitItemAssignmentDao()` | None |
| `SubscriptionCandidateDao` | `provideSubscriptionCandidateDao()` | None |
| `BudgetAdjustmentDao` | `provideBudgetAdjustmentDao()` | None |
| `EmailReceiptDao` | `provideEmailReceiptDao()` | None |
| `HealthScoreHistoryDao` | `provideHealthScoreHistoryDao()` | None |

**Scope:** All @Singleton  
**Dependency:** AppDatabase injection from DatabaseModule

---

### 2.3 Network & Security Modules

#### **NetworkModule.kt**
- **File:** `NetworkModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods

**@Provides Methods:**

| Method | Returns | Qualifier | Scope | Configuration |
|--------|---------|-----------|-------|---|
| `provideLocationOkHttpClient()` | `OkHttpClient` | `@LocationHttpClient` | `@Singleton` | See below |

**OkHttpClient Configuration:**
```
Cache:
  - Directory: context.cacheDir/location_http_cache
  - Size: 20 MB
Timeouts:
  - Connect: 10 seconds
  - Read: 20 seconds
  - Write: (default - not specified)
Interceptors: (none specified in provided method)
```

**Note:** No general OkHttpClient provider in this module. Location-specific client only.

---

#### **SecurityModule.kt**
- **File:** `SecurityModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods

**@Provides Methods:**

| Method | Returns | Scope | Notes |
|--------|---------|-------|-------|
| `provideSecureKeyStorage()` | `SecureKeyStorage` | `@Singleton` | See SecureKeyStorage section |

**Purpose:** 
- ✅ CRITICAL FIX: Replaces insecure BuildConfig API keys
- 🔒 Encrypts keys at rest using AES-256-GCM
- 🔒 Stores encryption keys in Android Keystore
- 🔒 Supports hardware-backed encryption

---

### 2.4 Service & Location Modules

#### **ServiceModule.kt**
- **File:** `ServiceModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods

**@Provides Methods:**

| Method | Returns | Scope | Implementation/Notes |
|--------|---------|-------|---|
| `provideGson()` | `Gson` | `@Singleton` | Lenient mode enabled |
| `provideNotificationService()` | `NotificationService` | `@Singleton` | Binds `AndroidNotificationService` |
| `provideGeocodingService()` | `GeocodingService` | `@Singleton` | **Composite (cascade) service** |
| `provideNearbyPoiService()` | `NearbyPoiService` | `@Singleton` | Binds `OverpassNearbyService` |
| `provideForegroundLocationProvider()` | `ForegroundLocationProvider` | `@Singleton` | Binds `AndroidForegroundLocationProvider` |
| `provideNavigationTargetResolver()` | `NavigationTargetResolver` | `@Singleton` | Binds `NavigationTargetResolverImpl` |
| `provideWidgetStyleRepository()` | `WidgetStyleRepository` | `@Singleton` | Binds `WidgetStyleRepositoryImpl` |
| `provideStringDistanceUtils()` | `StringDistanceUtils` | `@Singleton` | Object singleton utility |

**Geocoding Service - Cascade Strategy:**
```
Interactive picker (searchMultiple):
  1. Photon
  2. → Geoapify
  3. → Google Places
  4. → Nominatim

Background resolution (search):
  - Nominatim only (unchanged)
```

---

### 2.5 AI Module

#### **AiModule.kt**
- **File:** `AiModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Abstract class with @Binds (primary) + companion object @Provides (secondary)

**@Binds Methods (Interface → Implementation):**

| Binds Method | Interface → Implementation | Scope |
|--------------|---------------------------|-------|
| `bindAiSettingsRepository()` | `AiSettingsRepository` ← `AiSettingsRepositoryImpl` | `@Singleton` |
| `bindAiArtifactRepository()` | `AiArtifactRepository` ← `AiArtifactRepositoryImpl` | `@Singleton` |
| `bindAiEngagementRepository()` | `AiEngagementRepository` ← `AiEngagementRepositoryImpl` | `@Singleton` |
| `bindAiChatRepository()` | `AiChatRepository` ← `AiChatRepositoryImpl` | `@Singleton` |
| `bindAiPolicy()` | `AiPolicy` ← `AiPolicyImpl` | `@Singleton` |
| `bindAiCapabilityRouter()` | `AiCapabilityRouter` ← `DefaultAiCapabilityRouter` | `@Singleton` |
| `bindAiEnvironmentMonitor()` | `AiEnvironmentMonitor` ← `DefaultAiEnvironmentMonitor` | `@Singleton` |
| `bindAiWorkScheduler()` | `AiWorkScheduler` ← `AiWorkSchedulerImpl` | `@Singleton` |
| `bindDashboardBriefingService()` | `DashboardBriefingService` ← `HybridDashboardBriefingService` | `@Singleton` |
| `bindReviewExplanationService()` | `ReviewExplanationService` ← `HybridReviewExplanationService` | `@Singleton` |
| `bindReceiptAssistService()` | `ReceiptAssistService` ← `SmartReceiptAssistService` | `@Singleton` |
| `bindCategorizationAssistService()` | `CategorizationAssistService` ← `HybridCategorizationAssistService` | `@Singleton` |
| `bindDedupeJudgeService()` | `DedupeJudgeService` ← `HybridDedupeJudgeService` | `@Singleton` |
| `bindQueryInterpretationService()` | `QueryInterpretationService` ← `HybridQueryInterpretationService` | `@Singleton` |
| `bindReceiptItemCategorizationService()` | `ReceiptItemCategorizationService` ← `HybridReceiptItemCategorizationService` | `@Singleton` |
| `bindNotificationFallbackParser()` | `NotificationFallbackParser` ← `OnDeviceNotificationParser` | `@Singleton` |
| `bindReviewPriorityScorer()` | `ReviewPriorityScorer` ← `OnDeviceReviewPriorityScorer` | `@Singleton` |
| `bindSemanticDuplicateDetector()` | `SemanticDuplicateDetector` ← `OnDeviceSemanticDuplicateDetector` | `@Singleton` |

**Companion Object @Provides Methods:**

| Method | Returns | Scope | Notes |
|--------|---------|-------|-------|
| `provideAiArtifactDao()` | `AiArtifactDao` | `@Singleton` | From AppDatabase |
| `provideAiChatSessionDao()` | `AiChatSessionDao` | `@Singleton` | From AppDatabase |
| `provideAiChatMessageDao()` | `AiChatMessageDao` | `@Singleton` | From AppDatabase |
| `provideOnDeviceReceiptItemCategorizationService()` | `OnDeviceReceiptItemCategorizationService` | `@Singleton` | No-arg constructor |
| `provideCloudReceiptItemCategorizationService()` | `CloudReceiptItemCategorizationService` | `@Singleton` | Requires SecureKeyStorage |
| `provideCloudWarrantyExtractionService()` | `CloudWarrantyExtractionService` | `@Singleton` | Requires SecureKeyStorage |

**Key Notes:**
- Uses SmartReceiptAssistService (NEW) - smart retry service with image priority
- Hybrid implementations support both on-device and cloud AI
- DAOs provided via companion object pattern

---

### 2.6 Feature Modules

#### **CurrencyModule.kt**
- **File:** `CurrencyModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Abstract class with @Binds methods

**@Binds Methods:**

| Binds Method | Interface → Implementation | Scope |
|--------------|---------------------------|-------|
| `bindCurrencySettingsRepository()` | `CurrencySettingsRepository` ← `CurrencySettingsRepositoryImpl` | `@Singleton` |
| `bindCurrencyRatesRepository()` | `CurrencyRatesRepository` ← `CurrencyRatesRepositoryImpl` | `@Singleton` |
| `bindExchangeRateStore()` | `ExchangeRateStore` ← `ExchangeRateStoreAdapter` | `@Singleton` |

**Note:** CurrencyConverter, MultiCurrencyRepository, and AnalyticsCurrencyNormalizer use constructor injection (@Inject) — no DI module needed.

---

#### **TimeModule.kt**
- **File:** `TimeModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Abstract class with @Binds methods

**@Binds Methods:**

| Binds Method | Interface → Implementation | Scope |
|--------------|---------------------------|-------|
| `bindTimeProvider()` | `TimeProvider` ← `SystemTimeProvider` | `@Singleton` |

**Rationale:** Abstract class required for @Binds; AppModule cannot mix @Binds with @Provides.

---

#### **GroupsModule.kt**
- **File:** `GroupsModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods

**@Provides Methods:**

| Method | Returns | Scope | Dependencies |
|--------|---------|-------|---|
| `provideGroupsRepository()` | `GroupsRepository` | `@Singleton` | `GroupsRepositoryImpl` |
| `provideDeleteGroupMemberUseCase()` | `DeleteGroupMemberUseCase` | None | `GroupsRepository` |
| `provideDeleteGroupUseCase()` | `DeleteGroupUseCase` | None | `GroupsRepository` |
| `provideAddGroupExpenseUseCase()` | `AddGroupExpenseUseCase` | None | `GroupsRepository` |

**Note:** Use cases are not @Singleton - new instance per injection.

---

#### **CashFlowModule.kt**
- **File:** `CashFlowModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods

**@Provides Methods:**

| Method | Returns | Scope | Dependencies |
|--------|---------|-------|---|
| `provideCashFlowCalculator()` | `CashFlowCalculator` | `@Singleton` | ExpenseRepository, RecurringExpenseEngine, RecurringExpenseRepository, TimeProvider |

---

#### **SavingsModule.kt**
- **File:** `SavingsModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods

**@Provides Methods:**

| Method | Returns | Scope | Dependencies |
|--------|---------|-------|---|
| `provideSmartSavingsEngine()` | `SmartSavingsEngine` | `@Singleton` | ExpenseRepository, BudgetRepository, BudgetCalculator, MonteCarloSpendingSimulator, SavingsGoalRepository, TimeProvider |
| `provideAutomatedSavingsRuleEngine()` | `AutomatedSavingsRuleEngine` | `@Singleton` | ExpenseRepository, SavingsGoalRepository, TimeProvider |
| `provideSavingsGamificationEngine()` | `SavingsGamificationEngine` | `@Singleton` | SavingsGoalRepository, TimeProvider |

---

#### **SubscriptionModule.kt**
- **File:** `SubscriptionModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods
- **Status:** ⚠️ MINIMAL - Single pass-through provider

**@Provides Methods:**

| Method | Returns | Scope | Notes |
|--------|---------|-------|-------|
| `provideSubscriptionManagerEngine()` | `SubscriptionManagerEngine` | `@Singleton` | Constructor injected, then re-provided |

---

#### **TaxModule.kt**
- **File:** `TaxModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods

**@Provides Methods:**

| Method | Returns | Scope | Implementation |
|--------|---------|-------|---|
| `provideTaxConfiguration()` | `TaxConfiguration` | `@Singleton` | `GreeceTaxConfiguration()` |

**Future:** Can load from user preferences or remote config.

---

#### **ExportModule.kt**
- **File:** `ExportModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods

**@Provides Methods:**

| Method | Returns | Scope | Notes |
|--------|---------|-------|-------|
| `provideQuickBooksIIFExporter()` | `QuickBooksIIFExporter` | `@Singleton` | No-arg constructor |
| `provideXeroCSVExporter()` | `XeroCSVExporter` | `@Singleton` | No-arg constructor |
| `provideFreshBooksExporter()` | `FreshBooksExporter` | `@Singleton` | No-arg constructor |

---

#### **BackupRepositoryModule.kt**
- **File:** `BackupRepositoryModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods

**@Provides Methods:**

| Method | Returns | Scope | Dependencies |
|--------|---------|-------|---|
| `provideDatabaseBackupRepository()` | `DatabaseBackupRepository` | `@Singleton` | `DatabaseBackupRepositoryImpl` |

---

#### **EmailIngestionModule.kt**
- **File:** `EmailIngestionModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods

**@Provides Methods:**

| Method | Returns | Scope | Notes |
|--------|---------|-------|-------|
| `provideAmazonReceiptParser()` | `AmazonReceiptParser` | `@Singleton` | No-arg constructor |
| `provideUberReceiptParser()` | `UberReceiptParser` | `@Singleton` | No-arg constructor |
| `provideAppleReceiptParser()` | `AppleReceiptParser` | `@Singleton` | No-arg constructor |

**Purpose:** Feature F14 - Email receipt ingestion from providers.

---

#### **ParserModule.kt**
- **File:** `ParserModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods
- **Purpose:** Provides `GreekBankParser` with injected `homeCurrency` from `CurrencySettingsRepository` (previously hardcoded to EUR).

**@Provides Methods:**

| Method | Returns | Scope | Dependencies |
|--------|---------|-------|---|
| `provideGreekBankParser()` | `GreekBankParser` | `@Singleton` | CurrencyNormalizer, MerchantCleaner, CurrencySettingsRepository |

**Note:** This module was added to remove the hardcoded `homeCurrency = "EUR"` default from GreekBankParser. Other parsers (Revolut, GoogleWallet, SMS) also accept `homeCurrency` parameter.

---

#### **OcrImprovementsModule.kt**
- **File:** `OcrImprovementsModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods

**@Provides Methods:**

| Method | Returns | Scope | Notes |
|--------|---------|-------|-------|
| `provideEnhancedMerchantExtractor()` | `EnhancedMerchantExtractor` | `@Singleton` | Constructor injected, re-provided |
| `provideOcrLanguageProcessor()` | `OcrLanguageProcessor` | `@Singleton` | Constructor injected, re-provided |
| `provideOcrPreprocessingPipeline()` | `OcrPreprocessingPipeline` | `@Singleton` | Constructor injected, re-provided |

---

#### **EmptyStateModule.kt**
- **File:** `EmptyStateModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module with @Provides methods
- **Status:** bound to UI registry initialization

**Violation Details:**
```kotlin
import com.yourname.expensetracker.ui.components.emptystate.ContextualActionRegistry
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateAction
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateActionType
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateScreenKeys
import com.yourname.expensetracker.ui.navigation.NavigationDestination
```

**Purpose:** wires empty-state registry support for UI-facing contextual actions.

**@Provides Methods:**

| Method | Returns | Scope | Notes |
|--------|---------|-------|-------|
| `provideContextualActionRegistry()` | `ContextualActionRegistry` | `@Singleton` | Initializes registry with 6 action groups |

**Related initializer:** `EmptyStateRegistryInitializer.kt`

---

#### **DashboardContractsModule.kt**
- **File:** `DashboardContractsModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object/module bindings
- **Purpose:** binds dashboard contracts and adapter implementations

---

#### **DashboardAnomalyModule.kt**
- **File:** `DashboardAnomalyModule.kt`
- **Install Scope:** `SingletonComponent`
- **Purpose:** binds anomaly alert orchestration and dashboard anomaly dependencies

#### **LocationResolverPortsModule.kt**
- **File:** `LocationResolverPortsModule.kt`
- **Install Scope:** `SingletonComponent`
- **Purpose:** binds location resolver ports and adapters

#### **NaturalLanguageModule.kt**
- **File:** `NaturalLanguageModule.kt`
- **Install Scope:** `SingletonComponent`
- **Purpose:** binds speech input and natural-language query components

#### **ReceiptParsingModule.kt**
- **File:** `ReceiptParsingModule.kt`
- **Install Scope:** `SingletonComponent`
- **Purpose:** binds receipt parsing and parsing helpers

#### **SavingsRepositoryBindingsModule.kt**
- **File:** `SavingsRepositoryBindingsModule.kt`
- **Install Scope:** `SingletonComponent`
- **Purpose:** binds savings repositories to domain contracts

#### **InvestmentModule.kt**
- **File:** `InvestmentModule.kt`
- **Install Scope:** `SingletonComponent`
- **Type:** Object module
- **Status:** active module

**Note:** `InvestmentTracker` and related repositories use constructor injection (@Inject).

---

#### **EmptyStateRegistryInitializer.kt**
- **File:** `EmptyStateRegistryInitializer.kt`
- **Install Scope:** runtime bootstrap
- **Purpose:** initializes empty-state registry wiring

---

## 3. Dependency Graph

### 3.1 Module Dependency Hierarchy

```
┌─────────────────────────────────────────────────────────────┐
│                    SingletonComponent                       │
│                     (Root scope)                            │
└─────────────────────────────────────────────────────────────┘
                              │
                ┌─────────────┼─────────────┐
                │             │             │
        ┌───────▼────┐  ┌────▼─────┐  ┌───▼──────┐
        │  DATABASE   │  │ SECURITY  │  │ NETWORK  │
        │  MODULES    │  │ MODULES   │  │ MODULES  │
        └───────┬────┘  └────┬─────┘  └───┬──────┘
                │             │            │
         ┌──────▼─────────────▼────────────▼──────┐
         │    DATABASE + DAO + SECURITY           │
         │    Provides: AppDatabase, All DAOs,    │
         │              SecureKeyStorage,         │
         │              LocationOkHttpClient      │
         └──────┬──────────────────────────────────┘
                │
    ┌───────────┼───────────┬────────────┬─────────┐
    │           │           │            │         │
┌───▼──┐  ┌────▼───┐  ┌───▼───┐  ┌────▼────┐  ┌─▼──┐
│ AI   │  │CURRENCY│  │ GROUPS │  │SAVINGS  │  │TAX │
│MODULE│  │ MODULE │  │ MODULE │  │ MODULE  │  │MOD │
└──────┘  └────────┘  └────────┘  └─────────┘  └────┘
    │
    ├─ AiChatRepository
    ├─ AiArtifactRepository
    ├─ ReceiptAssistService (SmartReceiptAssistService)
    ├─ CategorizationAssistService (Hybrid)
    ├─ DedupeJudgeService (Hybrid)
    └─ ... 15+ other AI services
```

### 3.2 Critical Dependency Paths

**Path 1: AI Feature Stack**
```
AiModule
  ├─ depends on: DatabaseModule (DAOs), SecurityModule (SecureKeyStorage)
  ├─ provides: AiChatRepository, AiArtifactRepository, ReceiptAssistService
  ├─ binds (Hybrid): Cloud + OnDevice fallback implementations
  └─ uses: SecureKeyStorage for API key management
```

**Path 2: Savings Feature Stack**
```
SavingsModule
  ├─ depends on: ExpenseRepository, BudgetRepository, TimeProvider
  ├─ provides: SmartSavingsEngine, AutomatedSavingsRuleEngine, SavingsGamificationEngine
  └─ uses: Repositories (which use DAOs from DaoModule)
```

**Path 3: Groups Feature Stack**
```
GroupsModule
  ├─ depends on: DatabaseModule (AppDatabase, DAOs)
  ├─ provides: GroupsRepository (use cases delegate to this)
  ├─ provides: DeleteGroupMemberUseCase, DeleteGroupUseCase, AddGroupExpenseUseCase
  └─ uses: ExpenseGroupDao, GroupMemberDao, GroupExpenseDao, GroupTransactionCoordinator
```

**Path 4: Location Services Stack**
```
ServiceModule
  ├─ provides: CompositeGeocodingService (cascade: Photon→Geoapify→Google→Nominatim)
  ├─ provides: ForegroundLocationProvider, NearbyPoiService
  └─ uses: LocationOkHttpClient from NetworkModule (20MB cache, 10s connect, 20s read)
```

### 3.3 Constructor Injection Dependencies

These classes use `@Inject` constructors and are NOT explicitly provided by modules:

**Domain/Business Logic:**
- `BudgetForecastingEngine`
- `BudgetRecommendationEngine`
- `InvestmentTracker`
- `CurrencyConverter`
- `MultiCurrencyRepository` — **wired into 10+ consumers** (Dashboard, Budget, Analytics, Forecast, Health, Savings, Groups, Export, AI/Query, Anomaly)
- `AnalyticsCurrencyNormalizer` — **injected into analytics, forecast, health, and savings engines**

**Data/Services:**
- `AndroidForegroundLocationProvider`
- `AndroidNotificationService`
- `OverpassNearbyService`
- `PhotonGeocodingService`
- `GeoapifyGeocodingService`
- `GooglePlacesGeocodingService`
- `NominatimGeocodingService`
- `NavigationTargetResolverImpl`
- `WidgetStyleRepositoryImpl`

**AI/ML Implementations:**
- `HybridDashboardBriefingService`
- `HybridReviewExplanationService`
- `SmartReceiptAssistService`
- `HybridCategorizationAssistService`
- `HybridDedupeJudgeService`
- `HybridQueryInterpretationService`
- `HybridReceiptItemCategorizationService`

These will be automatically instantiated by Hilt based on their constructor dependencies.

---

## 4. Network Infrastructure

### 4.1 OkHttp Configuration

**Providers:** NetworkModule.provideLocationOkHttpClient()

| Property | Value | Notes |
|----------|-------|-------|
| **Client Type** | `@LocationHttpClient` | Dedicated for geocoding services |
| **Cache Dir** | `context.cacheDir/location_http_cache` | App cache directory |
| **Cache Size** | 20 MB | Reasonable for location queries |
| **Connect Timeout** | 10 seconds | Standard for network setup |
| **Read Timeout** | 20 seconds | Allows time for large responses |
| **Write Timeout** | DEFAULT | Not overridden (≈10 sec default) |
| **Connection Pool** | DEFAULT | Not configured (5 connections, 5 min idle) |
| **Interceptors** | NONE | No logging, auth, or header interceptors specified |
| **Authenticator** | NONE | No automatic retry/auth handling |

**Usage Pattern:**
- Location/Geocoding services receive this client
- Cached responses reduce API calls
- No global OkHttpClient provider in NetworkModule

**Missing Configurations:**
- ⚠️ No request/response logging interceptor (would need okhttp-logging-interceptor)
- ⚠️ No retry logic or exponential backoff
- ⚠️ No network security configuration (e.g., certificate pinning)

---

### 4.2 Retrofit Configuration

**Status:** Not explicitly provided in DI modules reviewed.

**Expected Pattern:**
Services likely create Retrofit instances with:
- Base URLs: Photon, Geoapify, Google Places, Nominatim, Overpass APIs
- Converters: Gson (provided by ServiceModule)
- Client: LocationOkHttpClient from NetworkModule

**Services Using Retrofit:**
- PhotonGeocodingService
- GeoapifyGeocodingService
- GooglePlacesGeocodingService
- NominatimGeocodingService
- OverpassNearbyService
- Cloud AI services (if applicable)

---

### 4.3 API Service Interfaces

**Geocoding Services:** (Interfaces likely in domain/location package)
- `GeocodingService.search()` - Background resolution (Nominatim)
- `GeocodingService.searchMultiple()` - Interactive picker (cascade strategy)

**Location Services:**
- `ForegroundLocationProvider` - Current location access
- `NearbyPoiService` - Nearby points of interest (Overpass API)

**Notification Services:**
- `NotificationService` - Android notification access

---

## 5. Security Infrastructure

### 5.1 SecureKeyStorage

**Location:** `data/security/SecureKeyStorage.kt`  
**Injection:** Via SecurityModule.provideSecureKeyStorage()  
**Scope:** @Singleton

#### Architecture

```
┌────────────────────────────────────────┐
│       Application Layer                 │
│  (Uses: getGeoapifyKey(), etc.)         │
└──────────────┬─────────────────────────┘
               │
┌──────────────▼─────────────────────────┐
│    SecureKeyStorage (Public API)        │
│  ├─ storeKey(keyName, value)            │
│  ├─ getKey(keyName): String?            │
│  ├─ hasKey(keyName): Boolean            │
│  ├─ deleteKey(keyName)                  │
│  ├─ validateSecureStorage(): Boolean    │
│  └─ migrateFromBuildConfigIfNeeded()    │
└──────────────┬─────────────────────────┘
               │
┌──────────────▼─────────────────────────┐
│  EncryptedSharedPreferences             │
│  (androidx.security.crypto)             │
│  ├─ Encryption: AES256_GCM              │
│  ├─ Key Encryption: AES256_SIV          │
│  └─ Value Encryption: AES256_GCM        │
└──────────────┬─────────────────────────┘
               │
┌──────────────▼─────────────────────────┐
│  MasterKey (Android Keystore)           │
│  ├─ KeyScheme: AES256_GCM               │
│  ├─ Hardware-backed (if available)      │
│  └─ Biometric protection: Optional      │
└────────────────────────────────────────┘
```

#### Storage Keys

| Constant Name | Key Identifier | Usage |
|---|---|---|
| `KEY_GEOAPIFY` | `"geoapify_api_key"` | Geoapify geocoding service |
| `KEY_GOOGLE_PLACES` | `"google_places_api_key"` | Google Places geocoding |
| `KEY_GEMINI` | `"gemini_api_key"` | Google Gemini AI API |

#### Methods

| Method | Returns | Purpose |
|--------|---------|---------|
| `storeKey(keyName, value)` | Unit | Encrypt and store API key |
| `getKey(keyName)` | String? | Retrieve and decrypt API key |
| `hasKey(keyName)` | Boolean | Check if key exists |
| `deleteKey(keyName)` | Unit | Remove specific key |
| `clearAll()` | Unit | Remove all stored keys |
| `getStoredKeyNames()` | Set<String> | List key identifiers (not values) |
| `validateSecureStorage()` | Boolean | Test keystore connectivity |
| `migrateFromBuildConfigIfNeeded()` | Unit | One-time migration from legacy |

#### Security Features

✅ **AES-256-GCM Encryption:** All values encrypted  
✅ **Android Keystore:** Encryption keys isolated from app process  
✅ **Hardware-Backed:** Uses hardware TEE if available  
✅ **Biometric Optional:** Can require fingerprint/face (not enabled by default)  
✅ **Automatic Rotation:** Framework supports key rotation  
✅ **Compile-Time Safe:** Keys never embedded in APK  

#### Critical Fixes

**CRITICAL-1: BuildConfig Exposure**
- ❌ OLD: API keys in BuildConfig fields (decompilable from APK)
- ✅ NEW: Runtime-only secure storage via Android Keystore

---

### 5.2 Permission Handling

**Location Permissions:**
- `ACCESS_FINE_LOCATION` (handled by ForegroundLocationProvider)
- `ACCESS_COARSE_LOCATION` (fallback)

**Notification Permissions:**
- `POST_NOTIFICATIONS` (Android 13+, handled by NotificationService)

**Email Access:**
- Requires Gmail API setup for EmailIngestionService

---

## 6. Threading & Dispatchers

### 6.1 Dispatcher Configuration

**Module:** DispatchersModule  
**Scope:** SingletonComponent

#### Custom Qualifiers

```kotlin
@Qualifier @Retention(BINARY) annotation class IoDispatcher
@Qualifier @Retention(BINARY) annotation class DefaultDispatcher
@Qualifier @Retention(BINARY) annotation class MainDispatcher
```

#### Dispatcher Mappings

| Qualifier | Dispatcher Type | Use Cases |
|-----------|---|---|
| `@IoDispatcher` | `Dispatchers.IO` | Network calls, database queries, file I/O |
| `@DefaultDispatcher` | `Dispatchers.Default` | CPU-intensive work (sorting, filtering) |
| `@MainDispatcher` | `Dispatchers.Main` | UI updates, callbacks |

#### Example Usage

```kotlin
class SomeRepository @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    fun loadExpenses(): Flow<List<Expense>> = flow {
        val data = withContext(ioDispatcher) {
            // Database query
            expenseDao.getAllExpenses()
        }
        emit(data)
    }
}
```

---

### 6.2 Application Scope

**Provider:** DispatchersModule.providesApplicationScope()  
**Scope:** @Singleton  
**Qualifier:** @ApplicationScope

#### Creation

```kotlin
CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

#### Purpose

Long-running operations that should **survive ViewModel destruction**:
- Periodic background tasks
- Application-wide monitoring/telemetry
- Long-lived state management

#### Example Usage

```kotlin
class BudgetForecastingEngine @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope
) {
    init {
        appScope.launch {
            // Runs even if activity/ViewModel is destroyed
            periodicBudgetRecalculation()
        }
    }
}
```

#### Lifecycle

- ✅ Survives activity destruction
- ✅ Survives ViewModel destruction
- ❌ Cancelled only on application process death
- ⚠️ Use sparingly for true app-wide concerns

---

### 6.3 Coroutine Scope Management

**Repositories:** Use @IoDispatcher for database/network  
**ViewModels:** Inherit viewModelScope (built-in)  
**Activities/Fragments:** Use lifecycleScope (built-in)  
**Services:** Use @ApplicationScope or lifecycleScope

---

## 7. Architectural Issues & Findings

### 7.1 Violations Found

#### 🚨 **VIOLATION: EmptyStateModule imports UI classes**

**Severity:** HIGH  
**File:** EmptyStateModule.kt

**Problem:**
```kotlin
// WRONG: Infrastructure DI importing UI
import com.yourname.expensetracker.ui.components.emptystate.ContextualActionRegistry
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateAction
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateActionType
```

**Why It's Bad:**
- Violates clean architecture principle (dependency inversion)
- Infrastructure should not depend on UI
- Makes testing harder
- Tight coupling between layers

**Recommended Fix:**
1. Move `ContextualActionRegistry` to `domain/ui` package
2. Create domain interfaces for `EmptyStateAction`, `EmptyStateActionType`
3. Keep NavigationDestination in presentation layer
4. DI module references only domain interfaces

**Example Refactoring:**
```kotlin
// domain/emptystate/EmptyStateRegistry.kt (interface)
interface EmptyStateRegistry {
    fun registerActions(key: String, actions: List<EmptyStateAction>)
}

// data/emptystate/ContextualActionRegistry.kt (implementation)
class ContextualActionRegistry : EmptyStateRegistry { ... }

// DI: References domain interface only
@Provides
fun provideEmptyStateRegistry(
    impl: ContextualActionRegistry
): EmptyStateRegistry = impl
```

---

### 7.2 Legacy placeholder notes

| Module | Status | Issue | Recommendation |
|--------|--------|-------|---|
| **AppModule.kt** | Compatibility shell | Keep documented |
| **BudgetForecastModule.kt** | Legacy placeholder | Replace with current forecasting bindings |
| **InvestmentModule.kt** | Active module | Keep current bindings documented |
| **Phase4FeaturesModule.kt** | Legacy placeholder | Remove only if unused everywhere |

**Recommendation:** Keep legacy placeholders documented where they still exist; avoid reintroducing empty-module assumptions.

---

### 7.3 Missing Bindings or Potential Issues

#### ✅ **Potential Issue: No Global OkHttpClient**

**Status:** INTENTIONAL  
**Details:** Only @LocationHttpClient is provided. Other services likely create their own Retrofit instances.

**Risk:** Inconsistent OkHttpClient configurations across different services.  
**Mitigation:** Consider providing a base OkHttpClient with common interceptors/timeouts, then specialized clients (location, AI, etc.) layer on top.

---

#### ✅ **Potential Issue: Cloud AI Services require SecureKeyStorage**

**Status:** INTENTIONAL & SECURE  
**Details:** CloudReceiptItemCategorizationService, CloudWarrantyExtractionService require SecureKeyStorage for API keys.

**Good:** Ensures API keys are never hardcoded.  
**Risk:** If SecureKeyStorage is not initialized, cloud AI services will fail.

**Mitigation:** Validate SecureKeyStorage initialization on app startup.

---

#### ⚠️ **Issue: Hybrid AI Services Fall Back to OnDevice**

**Status:** GOOD PATTERN  
**Details:** Services like HybridReceiptAssistService try cloud first, fall back to on-device.

**Benefits:** 
- Better UX when network is unavailable
- Reduced API costs for simple cases
- Privacy-preserving fallback

---

### 7.4 Circular Dependency References

**Previously Resolved:**
- ❌ Older provider-method assumptions around investment/phase-4 modules
- ❌ Empty-state registry treated as a UI violation

**Current Status:** ✅ No circular dependencies detected in the current module map.

**Why They Occurred:**
- Some classes had mutual dependencies (A depends on B, B depends on A)
- Attempted to provide via DI instead of using constructor injection
- Resolved by moving to constructor injection (@Inject)

---

### 7.5 Database Migration Safety

**Issue:** ISSUE-1 documented in DatabaseModule.kt

```kotlin
// ISSUE-1: Never destructively wipe user data on migration failures.
// Old schemas must be migrated explicitly or handled through backup/recovery UX.
```

**Status:** ✅ ADDRESSED  
**Implementation:**
- Uses WAL (Write-Ahead Logging) journaling mode
- All migrations registered via `AppDatabase.ALL_MIGRATIONS`
- Room configured to fail rather than destructively reset

**Risk Mitigation:**
- Backup repository available via BackupRepositoryModule
- User data never silently lost during migrations

---

## 8. Complete Module Summary Table

| # | Module | Type | Scope | Providers | Status | Notes |
|---|--------|------|-------|-----------|--------|-------|
| 1 | ApplicationScope.kt | Qualifier | RUNTIME | - | ✅ | App lifecycle scope marker |
| 2 | NetworkQualifiers.kt | Qualifier | BINARY | @LocationHttpClient | ✅ | Location client marker |
| 3 | AppModule.kt | Object | Singleton | - | ⚠️ | EMPTY, backwards compat |
| 4 | DispatchersModule.kt | Object | Singleton | 4 dispatchers, AppScope | ✅ | Coroutine infrastructure |
| 5 | DatabaseModule.kt | Object | Singleton | AppDatabase, Coordinator | ✅ | Room with WAL, migrations |
| 6 | DaoModule.kt | Object | Singleton | 37 DAOs | ✅ | Complete DAO provision |
| 7 | NetworkModule.kt | Object | Singleton | LocationOkHttpClient | ✅ | Location HTTP cache (20MB) |
| 8 | SecurityModule.kt | Object | Singleton | SecureKeyStorage | ✅ | AES-256-GCM key storage |
| 9 | ServiceModule.kt | Object | Singleton | Gson, Location, Geocoding | ✅ | Location cascade, utilities |
| 10 | AiModule.kt | Abstract | Singleton | 18 binds + 6 provides | ✅ | Hybrid AI services |
| 11 | CurrencyModule.kt | Abstract | Singleton | 3 repositories + ExchangeRateStore | ✅ | Currency conversion + multi-currency aggregation |
| 12 | TimeModule.kt | Abstract | Singleton | TimeProvider | ✅ | System time abstraction |
| 13 | GroupsModule.kt | Object | Singleton | Repository, 3 use cases | ✅ | Group expense sharing |
| 14 | CashFlowModule.kt | Object | Singleton | CashFlowCalculator | ✅ | Cash flow analysis |
| 15 | SavingsModule.kt | Object | Singleton | 3 savings engines | ✅ | Savings gamification |
| 16 | SubscriptionModule.kt | Object | Singleton | Manager engine | ⚠️ | MINIMAL pass-through |
| 17 | TaxModule.kt | Object | Singleton | Greece tax config | ✅ | Extensible tax rules |
| 18 | ParserModule.kt | Object | Singleton | GreekBankParser | ✅ | GreekBankParser with injected homeCurrency |
| 19 | ExportModule.kt | Object | Singleton | 3 exporters | ✅ | QB, Xero, FreshBooks |
| 20 | BackupRepositoryModule.kt | Object | Singleton | Backup repository | ✅ | Database backup support |
| 21 | EmailIngestionModule.kt | Object | Singleton | 3 email parsers | ✅ | Amazon, Uber, Apple |
| 22 | OcrImprovementsModule.kt | Object | Singleton | OCR pipeline | ✅ | OCR enhancements |
| 23 | DashboardContractsModule.kt | Object | Singleton | Dashboard contracts | ✅ | Current dashboard bindings |
| 24 | DashboardAnomalyModule.kt | Object | Singleton | Anomaly orchestration | ✅ | Current anomaly bindings |
| 25 | EmptyStateModule.kt | Object | Singleton | ContextualActionRegistry | ✅ | Registry wiring |
| 26 | EmptyStateRegistryInitializer.kt | Object | runtime | Registry init | ✅ | Registry bootstrap |

---

## 9. Recommendations

### 9.1 Immediate Actions

1. **Keep empty-state wiring documented**
   - Ensure registry responsibilities stay clear
   - Prefer domain/data abstractions where practical
   - Priority: HIGH

2. **Document legacy shells**
   - If placeholder modules remain, document why
   - Keep newer feature bindings in the current modules
   - Priority: MEDIUM

3. **Add Request Logging Interceptor**
   - Include OkHttp logging for debugging
   - Mask sensitive headers (Authorization, API keys)
   - Priority: MEDIUM

---

### 9.2 Medium-Term Improvements

1. **Generalize OkHttpClient Configuration**
   - Create base OkHttpClient with common interceptors
   - Provide specialized clients (location, AI, etc.) as variants
   - Add retry/backoff logic for transient failures

2. **AI Service API Key Validation**
   - On app startup, validate SecureKeyStorage connectivity
   - Show user-friendly error if keys not configured
   - Support key configuration via in-app settings UI

3. **Add Retrofit Providers to DI**
   - Make Retrofit instances explicit in DI (currently implicit)
   - Configure base URLs, converters, interceptors consistently
   - Enable easier testing with mock Retrofit instances

4. **Document Dispatcher Usage**
   - Add KDoc examples showing correct dispatcher usage
   - Create lint rules to catch dispatcher misuse
   - Guide developers on when to use which dispatcher

---

### 9.3 Long-Term Architecture

1. **Module Dependency Validation**
   - Add Gradle module dependency rules to prevent architecture violations
   - Use `:data`, `:domain`, `:presentation` module separation
   - Enforce: presentation → domain ← data

2. **Feature Module Support**
   - Consider on-demand feature module loading for large apps
   - Use Hilt's multi-module support for feature-specific DI
   - Leverage `@InstallIn(ActivityComponent.class)` for feature-specific scopes

3. **Testing Improvements**
   - Create @TestDispatcher for testing time-sensitive code
   - Provide test doubles for DispatchersModule
   - Mock SecureKeyStorage for unit tests

---

## 10. Appendix: Key Metrics

| Metric | Count |
|--------|-------|
| Total DI Modules | 25 |
| @Singleton Scope Providers | 80+ |
| @Binds Methods | 18 |
| @Provides Methods | 45+ |
| Custom Qualifiers | 4 (@ApplicationScope, @LocationHttpClient, @IoDispatcher, @DefaultDispatcher, @MainDispatcher) |
| DAOs Provided | 37 |
| Violations Found | 1 (EmptyStateModule UI imports) |
| Empty/Placeholder Modules | 4 |
| Abstract Module Classes | 3 (AiModule, CurrencyModule, TimeModule) |
| Object Module Classes | 22 |
| Average Module Size | ~40 lines |
| Largest Module | AiModule (209 lines) |

---

## 11. References

- **Hilt Documentation:** https://dagger.dev/hilt/
- **Android Security:** https://developer.android.com/topic/security
- **EncryptedSharedPreferences:** https://developer.android.com/training/articles/keystore
- **Room Migrations:** https://developer.android.com/training/data-storage/room/migrating-db-schema
- **Coroutines:** https://kotlinlang.org/docs/coroutines-overview.html

---

**Document Status:** ✅ COMPLETE  
**Last Updated:** 2026-04-04  
**Author:** Scout Agent  
**Reviewed By:** Primary Agent
