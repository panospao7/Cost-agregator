# Backend Domain Layer Map

**Refreshed:** May 2, 2026  
**Scope:** Current domain inventory across business, AI, dashboard, privacy, and shared UI text models

---

## Table of Contents

1. [Directory Structure](#directory-structure)
2. [Architecture Overview](#architecture-overview)
3. [Engines](#engines)
4. [Use Cases](#use-cases)
5. [Models](#models)
6. [Services](#services)
7. [Clean Architecture Violations](#clean-architecture-violations)
8. [Circular Dependencies](#circular-dependencies)

---

## Directory Structure

### Overview by Category

| Directory | Current focus |
|-----------|---------------|
| `backup/` | **NEW — Backup/restore domain contracts**: `DatabaseBackupRepository` (interface), `DatabaseExportResult`, `DatabaseImportResult`, `DatabaseStats`, `DatabaseImportSummary` |
| `common/` | Shared helpers such as hashing and general-purpose utilities |
| `core/money/` | **NEW — Type-safe money primitives**: `CurrencyCode`, `MoneyAmount`, `MoneyAggregate`, `MoneyBucket`, `ConvertedMoney`, `ConversionFailure`, `CurrencyAssumption`, `MoneyMappers`, `MoneyFormatUtils` |
| `core/time/` | **NEW — Typed time period models**: `PeriodRange` (half-open `[start, end)` with kind/zone/label), `PeriodKind` (TODAY, THIS_WEEK, THIS_MONTH, LAST_7_DAYS, etc.) |
| `transaction/` | **NEW — Transaction lifecycle models**: `ExpenseSource`, `LifecycleEventType`, `DeduplicationMode`, `CreateExpenseRequest`, `CreateExpenseResult`, `ExpenseUpdates` |
| `transaction/lifecycle/` | **NEW — Lifecycle coordinator + dispatcher**: `TransactionLifecycleCoordinator` (single entry point for CUD), `TransactionSideEffectDispatcher` (post-creation side effects) |
| `receipt/lifecycle/` | **Receipt lifecycle coordinator + services**: `ReceiptLifecycleCoordinator` (single entry point for receipt processing), `ReceiptLinkService` (centralized receipt-expense linking), `ReceiptAssetStore` (file persistence), `ReceiptInputValidator` (URI/MIME validation), `ReceiptDuplicateDetector` (3-signal dedup), `ReceiptSideEffectDispatcher` (document-type-gated effects), `BankStatementLifecycleProcessor` (statement processing) |
| `recurring/` | **NEW — Recurring occurrence expansion & resolution**: `RecurringOccurrenceExpander` (expand recurrence rule → occurrence candidates), `OccurrenceConflictResolver` (resolve candidates vs actual expenses), `RecurringPlanProjectionService` (materialise PlannedExpense rows from occurrences) |
| `recurring/lifecycle/` | **NEW — Recurring lifecycle coordinator + materializer**: `RecurringLifecycleCoordinator` (primary entry point for occurrence generation + `reconcilePlannedVsActual()`), `RecurringOccurrenceMaterializer` (persist occurrences + create reminder deliveries) |
| `privacy/` | **NEW — Privacy capability gates, audit logging, and PII redaction (Phase 6)**: `PrivacyCapability` (20-value enum), `PrivacyGate` (interface), `PrivacyDecision` (sealed result), `PrivacySettings` (10 toggles + 2 retention settings), `PrivacySettingsRepository`, `PrivacyAuditLogger`, `NotificationPrivacyGate`, `LocationPrivacyGate`, `CloudAiPrivacyGate`, `BackupPrivacyGate`, `CompositePrivacyGate`, `RedactionSanitizer` |
| `dto/` | Cross-layer transfer objects for AI, review, and category references |
| `ai/` | AI capability routing, policy, and model-backed services |
| `bank/` | Bank API integration and connection orchestration |
| `business/` | Business expense reporting and deduction workflows |
| `budget/` | Budget status, history series, and forecasting engines |
| `cashflow/` | Cash-flow calculation and calendar-oriented projections |
| `carbon/` | Carbon-footprint estimation |
| `debug/` | Runtime diagnostics, seeding, and issue inspection |
| `groups/` | Shared expense group use cases and coordination |
| `health/` | Financial health scoring and trend models |
| `lifestyle/` | Lifestyle inflation detection and savings prompts |
| `naturallanguage/` | Speech input and natural-language expense queries |
| `model/` | Dashboard, navigation, recommendation, widget, and AI presentation models |
| `receipt/` | Receipt parsing, lifecycle models, and warranty extraction |
| `savings/` | Savings goals, automation, and sweep logic |
| `usecase/` | Public application use cases for dashboard, budget, receipt, and AI flows |
| `util/` | Shared numeric, date, text, and formatting utilities |
| `widget/` | Widget state and repository contracts |
| `logic/`, `parser/`, `location/`, `currency/`, `export/`, `subscription/`, `tax/`, etc. | Feature-specific domain services retained in the current map |

---

## Architecture Overview

### Layered Architecture (Clean Architecture)

```
Domain Layer (This Document)
├── Models (Pure data classes)
├── Use Cases (Interactors - business logic entry points)
├── Engines (Specialized processors)
├── Services (Contracts/interfaces for external dependencies)
└── Utilities (Pure functions, helpers)
```

### Key Design Patterns

- **Strategy Pattern:** Categorization engines, parsers
- **Composite Pattern:** Analytics engines combining sub-engines
- **Adapter Pattern:** Currency converter, format exporters
- **Observer Pattern:** Implied through Flow<T> in repositories
- **Dependency Injection:** All components use @Inject + Dagger

---

## Engines

### 1. Budget & cash-flow engines
**Files:** `budget/BudgetForecastingEngine.kt`, `budget/BudgetHistorySeriesBuilder.kt`, `cashflow/CashFlowCalculator.kt`

**Purpose:** Produce current budget status, historical series, and cash-flow projections for dashboards and forecasts.

**Key outputs:** budget trends, remaining runway, scenario-based forecast results, and series data for UI widgets.

**Phase 10 currency normalization:** `BudgetForecastingEngine.getSpentAmount()` now routes through `AnalyticsCurrencyNormalizer` instead of raw DAO SQL sums — all multi-currency expenses are converted to home currency before aggregation. Conversion warnings are logged but do not block the computation.

---

### 2. Alerts and anomaly orchestration
**Files:** `alerts/AnomalyAlertOrchestrator.kt`, `debug/DebugIssueDetector.kt`

**Purpose:** Coordinate anomaly detection, alert surfacing, and diagnostics across repository and UI entry points.

---

### 3. Business, carbon, and lifestyle engines
**Files:** `business/BusinessExpenseReportGenerator.kt`, `carbon/CarbonFootprintCalculator.kt`, `lifestyle/LifestyleInflationDetector.kt`

**Purpose:** Generate business reporting, environmental impact, and lifestyle-spend trend signals.

---

### 4. Categorization and intelligence engines
**Files:** `categorization/CategorizationEngine.kt`, `intelligence/TransactionClassifier.kt`, `intelligence/CrossSourceDeduplication.kt`

**Purpose:** Classify, deduplicate, and normalize expense data before it reaches forecasting and dashboard layers.

---

### 5. Intelligence Engines

#### Transaction Classifier
**File:** `intelligence/TransactionClassifier.kt`

**Purpose:** Multi-mode transaction classification (rule-based, ML, hybrid).

#### Hybrid Expense Classifier
**File:** `intelligence/ml/HybridExpenseClassifier.kt`

**Purpose:** Combines rule-based and ML models for expense classification.

#### Cross-Source Deduplication
**File:** `intelligence/CrossSourceDeduplication.kt`

**Purpose:** Detects duplicate transactions across multiple sources.

---

### 6. Location Analytics Engines

#### Spending Heatmap Engine
**File:** `location/SpendingHeatmapEngine.kt`

**Purpose:** Geographic spending patterns visualization.

#### Travel Detection Engine
**File:** `location/TravelDetectionEngine.kt`

**Purpose:** Identifies travel periods based on location spending patterns.

#### Location Insights Engine
**File:** `location/LocationInsightsEngine.kt`

**Purpose:** Geographic insights and recommendations.

#### Area Spending Engine
**File:** `location/AreaSpendingEngine.kt`

**Purpose:** Regional spending aggregation and analysis.

---

### 7. AI Service Engines

#### DashboardBriefingService
**File:** `ai/service/DashboardBriefingService.kt`

**Purpose:** AI-generated daily/weekly dashboard summaries.

#### QueryInterpretationService
**File:** `ai/service/QueryInterpretationService.kt`

**Purpose:** Natural language query understanding.

#### ReceiptAssistService
**File:** `ai/service/ReceiptAssistService.kt`

**Purpose:** AI-assisted receipt processing.

#### SemanticDuplicateDetector
**File:** `ai/service/SemanticDuplicateDetector.kt`

**Purpose:** AI-based duplicate detection.

#### DedupeJudgeService
**File:** `ai/service/DedupeJudgeService.kt`

**Purpose:** Final verdict on transaction duplicates.

---

## Use Cases

### Budget Use Cases
**Directory:** `usecase/budget/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `CalculateBudgetStatusUseCase.kt` | Compute budget vs actual spending | — (no params) | `List<BudgetStatus>` |
| `GetMonteCarloBudgetImpactUseCase.kt` | Forecast budget impact | Budget, scenarios | MonteCarloResult |

---

### Dashboard Use Cases
**Directory:** `usecase/dashboard/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `ComputeDashboardWidgetsUseCase.kt` | Aggregate all dashboard widgets | Period, filters | Widget data |
| `ComputeMoneyRadarUseCase.kt` | Generate money radar visualization | - | MoneyRadarData |
| `DashboardDataProvider.kt` | Interface for dashboard data sources | - | Various |

---

### Expense Use Cases
**Directory:** `usecase/expense/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `CategorizeExpenseUseCase.kt` | Classify expense to category | merchantName: String | CategoryResult (categoryId, confidence, matchType, explanation) |
| `DetectDuplicateExpenseUseCase.kt` | Find duplicate transaction | amount, merchant, date, currency, transactionType, windowMs, source | DuplicateCheckResult |
| `ExpenseUseCases.kt` | Facade for all expense operations | - | Various |

---

### Forecast Use Cases
**Directory:** `usecase/forecast/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `CalculateFinancialForecastUseCase.kt` | Generate financial forecast | — (no params) | `Flow<FinancialForecast>` |

### Forecast Assembler (Phase 5b — domain/forecasting/)

| Service | File | Purpose |
|---------|------|---------|
| `ForecastInputAssembler` | `forecasting/ForecastInputAssembler.kt` | Central forecast-input assembler. Merges manual recurring patterns + planned expenses into a single forecast input. Injects `RecurringLifecycleCoordinator` for future occurrence-based dedup. **Phase 10 double-count fix:** Cross-deduplicates planned expenses against materialized occurrences via `RecurringOccurrenceDao.getByDateRange()` — planned expenses whose `sourceOccurrenceKey` matches a PLANNED/PAID occurrence are excluded. Used by weather/dashboard forecast paths. |

---

### Receipt Use Cases
**Directory:** `usecase/receipt/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `ProcessReceiptUseCase.kt` | End-to-end receipt processing | ReceiptSource | `Result<ProcessedReceipt>` |

---

### Savings Use Cases
**Directory:** `usecase/savings/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `LifestyleSavingsPromptUseCase.kt` | Generate savings recommendations | Spending patterns | LifestyleSavingsRecommendation? |
| `MonthlySavingsSweepUseCase.kt` | Compute end-of-month savings sweep recommendation. Analyzes budget underspend, MC risk buffer, safe sweep amount. **Phase 10 double-count fix:** Occurrence-key dedup in `calculateKnownUpcomingObligations()` — MUST-priority planned expenses with matching `sourceOccurrenceKey` in materialized occurrences are excluded. Returns `SavingsSweepRecommendation` or null. | — (no params) | SavingsSweepRecommendation? |

---

### Warranty Use Cases
**Directory:** `usecase/warranty/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `AutoCreateWarrantyFromReceiptUseCase.kt` | Extract warranty from receipt | Receipt items | WarrantyEntity |

---

### AI Use Cases
**Directory:** `ai/usecase/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `ExecuteFinancialQueryUseCase.kt` | Execute natural language query | FinancialQueryIntent | FinancialQueryResult |
| `GenerateDashboardBriefingUseCase.kt` | Create AI briefing | Period, transactions | Briefing text |
| `DetectSemanticDuplicateUseCase.kt` | Find semantic duplicates | Expenses | List<ExpensePair> |
| `ExplainPendingReviewUseCase.kt` | Explain review item | AiArtifact | Explanation |
| `CategorizeReceiptItemsUseCase.kt` | AI categorization | Receipt items | Categorized items |
| `InterpretFinancialQueryUseCase.kt` | Parse query intent | Query text | FinancialQueryIntent |
| `GetAiRuntimeStatusUseCase.kt` | Check AI availability | - | OnDeviceModelStatus |
| `ValidateBankStatementTransactionsUseCase.kt` | AI bank statement transaction validator. 3-tier pipeline: on-device AI → cloud AI (privacy-gated) → parser-only fallback. Returns per-transaction source tracking. | Raw OCR text + candidate transactions + home currency | List<CleanTransaction> |
| `MapFinancialQueryToNavigationUseCase.kt` | Convert query to navigation | Query result | NavigationTarget |
| `GenerateTransactionInsightUseCase.kt` | AI insight generation | Transaction | Insight text |
| `PrioritizeReviewItemsUseCase.kt` | Rank pending reviews | Reviews | Prioritized list |

**Input Builders (Transform UX data to domain models):**
- `CategorizationAssistInputBuilder.kt`
- `DashboardBriefingInputBuilder.kt`
- `DedupeJudgeInputBuilder.kt`
- `FinancialQueryInterpretationInputBuilder.kt`
- `ReceiptAssistInputBuilder.kt`
- `ReceiptItemCategorizationInputBuilder.kt`
- `ReviewExplanationInputBuilder.kt`

---

### Groups Use Cases
**Directory:** `groups/usecase/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `AddGroupExpenseUseCase.kt` | Add shared expense | Expense, members | GroupTransaction |
| `DeleteGroupMemberUseCase.kt` | Remove group member | Group, member | Result |
| `DeleteGroupUseCase.kt` | Delete group | Group ID | Result |

---

## Models

### Root Models
**Directory:** `model/`

| File | Fields | Purpose | Consumers |
|------|--------|---------|-----------|
| `Result.kt` | Success<T>, Error, Duplicate, Loading | Generic result wrapper | All use cases |
| `UiText.kt` | Lazy string composition | UI-agnostic text rendering | Recommendations, AI responses |
| `PeriodRange.kt` | start, end (Long) | Time window (legacy; use `core/time/PeriodRange.kt` for new code) | Forecasting, analytics |
| `PeriodTotal.kt` | period, total, count | Aggregated spending | Dashboard |
| `CategoryBreakdown.kt` | category, amount, percentage | Category-level analytics | Dashboard, reports |
| `CategoryInfo.kt` | id, name, icon, color | Category metadata | All engines |
| `FinancialForecast.kt` | scenarios, probability distribution | Projected spending | Forecasting use case |
| `PlannedExpense.kt` | description, amount, dueDate | Planned transaction | Budget planning |
| `RecurringPattern.kt` | merchant, frequency, avgAmount | Recurring transaction | Insights, reminders |
| `SavingsGoal.kt` | name, targetAmount, deadline, progress | Savings target | Savings use cases |
| `UpcomingItem.kt` | description, date, amount, type | Upcoming transaction | Reminders, dashboard |
| `BlockPartyDay.kt` | date, totalSpent, transactionCount | Daily summary | Dashboard |

### Core Time Types (New — `core/time/`)
**Directory:** `core/time/`

| File | Purpose | Key Details |
|------|---------|-------------|
| `PeriodRange.kt` | Typed half-open period model | `[startInclusiveMillis, endExclusiveMillis)` with `kind` (PeriodKind), `zoneId`, `label`, `contains()` — replaces raw `Pair<Long, Long>` |
| `PeriodKind.kt` | Semantic period classification | Enum: `TODAY`, `THIS_WEEK`, `LAST_WEEK`, `LAST_7_DAYS`, `THIS_MONTH`, `LAST_MONTH`, `LAST_30_DAYS`, `THIS_QUARTER`, `LAST_QUARTER`, `THIS_YEAR`, `LAST_YEAR`, `CUSTOM`; distinguishes calendar vs rolling semantics |
| | **Extension: `PeriodKind.toPeriodRange()`** | Converts enum to concrete `PeriodRange` anchored at `now`. Delegates to `TimePeriodUtils` for calendar-aware boundary computation. Requires explicit `customStart`/`customEnd` for `CUSTOM`. Added Phase 10. |

### Transaction Lifecycle Models (New — `transaction/`)
**Directory:** `transaction/`

| File | Purpose | Key Details |
|------|---------|-------------|
| `ExpenseSource.kt` | Enum of 14 expense origin sources | MANUAL_ENTRY, NOTIFICATION_AUTO_ACCEPT, REVIEW_APPROVAL, RECEIPT_SCAN, RECEIPT_BATCH_REVIEW, BANK_STATEMENT_REVIEW, CSV_IMPORT, EMAIL_RECEIPT, GROUP_EXPENSE, BANK_API_SYNC, RECURRING_GENERATED, DEBUG_TOOL, MIGRATION, UNKNOWN |
| `LifecycleEventType.kt` | Enum of 11 lifecycle event types | CREATED, UPDATED, DELETED, CREATE_ATTEMPTED, CREATE_VALIDATION_FAILED, CREATE_DUPLICATE_SKIPPED, CREATE_INSERT_CONFLICT, BULK_UPDATED, RESTORED_FROM_DEBUG_SNAPSHOT, SOURCE_LINKED, SIDE_EFFECT_FAILED |
| `DeduplicationMode.kt` | Deduplication strategy enum | STANDARD (default), STRICT_EXTERNAL_ID, BULK_IMPORT, SKIP_FOR_DEBUG_RESTORE |
| `CreateExpenseRequest.kt` | Source-neutral creation request | 40+ fields: required (merchant, amount, currency, date, transactionType, source), optionals (categoryId, notes, paymentMethod, location, business flags, split fields, source link fields), policy fields (deduplicationMode, skipDeduplication, idempotencyKey) |
| `CreateExpenseResult.kt` | Sealed result type | Created(expenseId), DuplicateSkipped(existingExpenseId, reason), ValidationFailed(errors), InsertConflict(dedupeKey), Error(exception) |
| `ExpenseUpdates.kt` | Patch-style update for existing expenses | All mutable fields as nullable, plus `actor` (required) and `reason` (optional) |

### Transaction Lifecycle Coordinator & Dispatcher (New — `transaction/lifecycle/`)
**Directory:** `transaction/lifecycle/`

| File | Purpose | Key Details |
|------|---------|-------------|
| `TransactionLifecycleCoordinator.kt` | **Single entry point for ALL expense CUD** | Pipeline: validate → normalize → dedupe → insert atomic → event log → side effects. Injected by 10+ consumer classes. All migrated paths (Manual, Review, Notification, Receipt, CSV, Email, Group, Bank) route through this coordinator. |
| `TransactionSideEffectDispatcher.kt` | Post-creation side effects | Best-effort dispatch: budget check via `BudgetMonitor`, anomaly alert via `AnomalyAlertOrchestrator`, merchant-category pattern learning via `MerchantCategoryRepository`. Failures are logged but not propagated. |

### Receipt Lifecycle Coordinator & Services (New — `receipt/lifecycle/`)
**Directory:** `receipt/lifecycle/`

| Component | File | Purpose | Key Details |
|-----------|------|---------|-------------|
| `ReceiptLifecycleCoordinator` | `lifecycle/ReceiptLifecycleCoordinator.kt` | **Single entry point for ALL receipt processing** | Pipeline: validate → persist asset → OCR/parse → dedupe → save → event logging → side effects. Handles camera/gallery, email, bank statement paths. |
| `ReceiptLinkService` | `lifecycle/ReceiptLinkService.kt` | Centralized receipt-expense linking | Manages many-to-many link table `receipt_expense_links`. Supports single links (RETAIL_RECEIPT) and multi-links (BANK_STATEMENT). Writes audit events for every link/unlink. |
| `ReceiptMatchLifecycleService` | `lifecycle/ReceiptMatchLifecycleService.kt` | **P3: Lifecycle-aware receipt match mutations.** | `saveMatchSuggestion()` / `approveMatchSuggestion()` / `rejectMatchSuggestion()` / `clearMatch()`. Each emits typed `ReceiptEvent`. Uses `DatabaseWriteBarrier` for restore safety. |
| `ReceiptAssetStore` | `lifecycle/ReceiptAssetStore.kt` | File persistence for receipt assets | Copies images to app-local storage, computes SHA-256 hash, creates camera temp URIs via FileProvider, generates backup manifests (`ReceiptAssetManifestEntry`). |
| `ReceiptInputValidator` | `lifecycle/ReceiptInputValidator.kt` | URI/MIME/size validation | Checks readability, supported MIME types (JPEG, PNG, WebP, PDF, HEIC), file size limit (50 MB), image decode validity. Returns `ValidationResult`. |
| `ReceiptDuplicateDetector` | `lifecycle/ReceiptDuplicateDetector.kt` | 3-signal deduplication | EXACT_HASH (SHA-256, 1.0), TEXT_FINGERPRINT (normalized OCR text, 0.95), SEMANTIC (merchant+amount+date+currency, 0.8), EXTERNAL_ID. Returns `DuplicateResult`. |
| `ReceiptSideEffectDispatcher` | `lifecycle/ReceiptSideEffectDispatcher.kt` | Document-type-gated post-save effects | RETAIL_RECEIPT → warranty + categorization + matching + price protection. EMAIL_RECEIPT → categorization only. BANK_STATEMENT/MANUAL_PLACEHOLDER → no effects. |
| `BankStatementLifecycleProcessor` | `lifecycle/BankStatementLifecycleProcessor.kt` | Statement-specific processing | OCR → parse → **AI validation via ValidateBankStatementTransactionsUseCase** → PendingReview creation → lifecycle events. AI step runs on-device first (privacy-safe), then cloud fallback (privacy-gated), then parser-only fallback. Tracks `validationSources: Map<Int, String>` per transaction (PARSER_ONLY/AI_VALIDATED/AI_CORRECTED). Returns `BankStatementResult(receiptId, transactionsFound, reviewsCreated, duplicatesSkipped, debugData)`. |

### Receipt Lifecycle Models (New — `receipt/`)
**Directory:** `receipt/`

| File | Purpose | Key Details |
|------|---------|-------------|
| `ReceiptSourceType.kt` | Enum of 9 receipt source types | CAMERA, GALLERY, FILE_IMPORT, EMAIL, BANK_STATEMENT, MANUAL_RECORD, BATCH_SCAN, DEBUG_IMPORT, UNKNOWN |
| `ReceiptDocumentType.kt` | Enum of 6 document types | RETAIL_RECEIPT, EMAIL_RECEIPT, BANK_STATEMENT, MANUAL_PLACEHOLDER, PDF_RECEIPT, UNKNOWN |
| `ReceiptProcessingStatus.kt` | Enum of 14 processing states | CAPTURED → VALIDATING → VALIDATION_FAILED → DUPLICATE_DETECTED → OCR_PENDING → OCR_RUNNING → OCR_FAILED → OCR_COMPLETED → PARSE_FAILED → PARSED → REVIEW_CREATED → EXPENSE_CREATED → SIDE_EFFECTS_COMPLETED → DELETED |
| `EmailReceiptData.kt` | Structured email receipt data | Carries messageId, from, subject, body, receivedAt, amount, merchant, currency, date, items (JSON). Used by `ReceiptLifecycleCoordinator` for email receipt processing. |

### Core Money Types (New — `core/money/`)
**Directory:** `core/money/`

| File | Purpose | Key Details |
|------|---------|-------------|
| `CurrencyCode.kt` | Type-safe ISO 4217 currency code (inline value class) | Replaces raw `String` codes; `parse()` returns null for invalid input |
| `MoneyAmount.kt` | **★ APPROVED TYPE ★** Amount + currency pair | KDoc declares this the single approved domain type for all monetary values. Throws on mixed-currency `plus()`/`minus()`; supports `times()`, `abs()`. ZERO_EUR companion. |
| `ConvertedMoney.kt` | Full conversion trace | Original + converted + rate + timestamp + `ConversionStatus` (SUCCESS, FAILED_MISSING_RATE, SAME_CURRENCY, APPROXIMATE_RATE, LEGACY_NOT_CONVERTED) |
| `MoneyBucket.kt` | Per-currency subtotal | Currency, amount, transaction count |
| `MoneyAggregate.kt` | **★ APPROVED TYPE ★ Primary aggregation return type** | KDoc declares this the single approved result type for all financial aggregation. `displayAmount`, `displayCurrency`, `sourceBuckets`, `conversionFailures`, `isPartial`, `warningMessage` — replaces raw `Double` totals. Includes `singleCurrency()`, `empty()`, `partial()` factory methods. |
| `ConversionFailure.kt` | Failed conversion record | `originalAmount` (MoneyAmount), `targetCurrency`, `reason` (MISSING_RATE, INVALID_AMOUNT, RATE_STALE, UNKNOWN) |
| `CurrencyAssumption.kt` | Enum for why a currency was assigned | `UNKNOWN`, `ASSUMED_HOME_CURRENCY`, `ASSUMED_LEGACY_EUR`, `USER_CONFIRMED`, `PARSED_FROM_SOURCE` |
| `MoneyMappers.kt` | Bridge utilities | Maps `ConversionResult` → `ConvertedMoney`, `FailedConversion` → `ConversionFailure`, `Expense` → `MoneyAmount` |
| `MoneyFormatUtils.kt` | Extension formatting | `MoneyAmount.formatMoney()`, `.formatMoneyCompact()`, `.formatMoneyWithSign()` |

### Budget Models
**Directory:** `model/budget/`

| File | Purpose |
|------|---------|
| `MonteCarloBudgetImpact.kt` | Probabilistic budget scenarios |
| `BudgetStatus` | Now includes `effectiveLimit` (rollover-aware) separated from `baseLimit`; `currency` and `conversionFailures` fields |

**Key change:** `effectiveLimit` is the rolled-over spend limit (base limit + any rollover surplus). `Budget.amount` stores the original base limit and is never mutated by rollover calculation.

### Dashboard Models
**Directory:** `model/dashboard/`

| File | Purpose |
|------|---------|
| `DomainBlockStatus.kt` | Dashboard widget status |
| `DomainDayBudgetStatus.kt` | Daily budget tracking |
| `DomainDashboardWidget.kt` | Widget composition and state |
| `DomainDashboardSummary.kt` | Aggregated dashboard snapshot |

### Widget Models
**Directory:** `model/widget/`

| File | Purpose |
|------|---------|
| `WidgetStyle.kt` | Widget theming and layout tokens |
| `WidgetState.kt` | Render-time widget state |

### Navigation Models
**Directory:** `model/navigation/`

| File | Purpose |
|------|---------|
| `DomainTransactionFilter.kt` | Transaction filtering criteria |

### Recommendation Models
**Directory:** `model/recommendation/`

| File | Purpose | Fields |
|------|---------|--------|
| `DashboardFollowThroughRecommendation.kt` | Navigation-oriented recommendation payload | navigationTarget, filterCriteria, priority, sourceArtifactId |
| `RecommendationPriority.kt` | Priority enum | HIGH, MEDIUM, LOW |
| `RecommendationStatus.kt` | Lifecycle enum | PENDING, VIEWED, ACTED |

### DTOs
**Directory:** `dto/`

| File | Purpose |
|------|---------|
| `AiArtifactRecord.kt` | Serialized AI artifact transport |
| `ReceiptItemCategorizationSnapshot.kt` | Receipt item categorization state |
| `ReviewPriorityInput.kt` | Review ranking input payload |
| `CategoryRef.kt` | Lightweight category reference |

**Fields:**
```kotlin
categoryId: Long?
dateRange: Pair<Long, Long>?
minAmount: Double?
maxAmount: Double?
merchantName: String?
transactionType: TransactionType?
```

### AI Models
**Directory:** `ai/model/`

| File | Purpose | Key Entities |
|------|---------|--------------|
| `AiModels.kt` | Core AI types | AiCapability, AiRouteDecision |
| `AiLoadState.kt` | Model loading states | Enum: IDLE, LOADING, READY, ERROR |
| `AiRuntimeStatusModels.kt` | Runtime status tracking | OnDeviceModelStatus |
| `FinancialQueryModels.kt` | Query intent/result | FinancialQueryIntent, FinancialQueryResult |
| `ReceiptItemCategorizationModels.kt` | Receipt categorization | ReceiptItem, CategorizationResult |
| `CaptureAssistModels.kt` | Receipt capture AI | CaptureIntent, CaptureResult |
| `NotificationParsingModels.kt` | Notification extraction | NotificationIntent |
| `ReviewPriorityModels.kt` | Review ranking | ReviewItem, PriorityScore |
| `SemanticDuplicateModels.kt` | Duplicate detection | DuplicatePair, Confidence |
| `AiArtifactPresentation.kt` | AI output packaging | AiArtifact display format |
| `OnDeviceRuntimePresentation.kt` | Runtime UI presentation | Status display |

### AI Policy
**Directory:** `ai/policy/`

| File | Purpose |
|------|---------|
| `AiPolicy.kt` | Capability gating and routing rules |
| `DefaultAiCapabilityRouter.kt` | Default backend selection strategy |

---

## Services

### Interfaces & Repositories

**File:** `service/NotificationService.kt`

**Purpose:** Notification dispatch contract.

**File:** `bank/BankApiIntegration.kt`

**Purpose:** Bank data integration contract used by connection and sync flows.

**File:** `naturallanguage/SpeechInputGateway.kt`

**Purpose:** Speech-to-text input boundary for natural language expense queries.

**File:** `naturallanguage/NaturalLanguageExpenseQueryRepository.kt`

**Purpose:** Repository contract for text/speech expense search and interpretation.

**File:** `ai/service/AiCapabilityRouter.kt`

**Purpose:** Route capability requests to appropriate AI backend.

```kotlin
interface AiCapabilityRouter {
    suspend fun decide(
        capability: AiCapability,
        settings: AiSettings,
        onDeviceStatus: OnDeviceModelStatus?
    ): AiRouteDecision
}
```

### AI Service Implementations
**Directory:** `ai/service/`

| Service | Purpose | Output |
|---------|---------|--------|
| `AiSettingsRepository.kt` | Fetch AI configuration | AiSettings |
| `AiEnvironmentMonitor.kt` | Monitor device capacity | Battery, network status |
| `AiWorkScheduler.kt` | Schedule async AI tasks | WorkRequest |
| `AiEngagementRepository.kt` | Track user engagement with AI | Metrics |
| `AiChatRepository.kt` | Chat history management | Message list |
| `AiArtifactRepository.kt` | Persist AI outputs | AiArtifactEntity |

### Dashboard / feature contracts
**Directory:** `usecase/dashboard/`

| File | Purpose |
|------|---------|
| `DashboardRepositoryContracts.kt` | Dashboard data contracts and adapter boundaries |
| `DashboardDataProvider.kt` | Dashboard data source abstraction |

### Current AI use cases
**Directory:** `ai/usecase/`

| File | Purpose |
|------|---------|
| `ExecuteFinancialQueryUseCase.kt` | Execute natural-language financial queries |
| `GenerateDashboardBriefingUseCase.kt` | Build dashboard briefing output |
| `InterpretFinancialQueryUseCase.kt` | Parse query intent |
| `MapFinancialQueryToNavigationUseCase.kt` | Map query results to navigation targets |
| `PrioritizeReviewItemsUseCase.kt` | Rank review items |
| `GenerateTransactionInsightUseCase.kt` | Produce transaction insight text |

### Categorization Services
**Directory:** `categorization/`

| Service | Purpose |
|---------|---------|
| `CategorizationEngine.kt` | Master categorization | 
| `ContextualInferenceEngine.kt` | Context-aware matching |
| `SemanticKeywordMatcher.kt` | Keyword-based classification |
| `CategoryKeywords.kt` | Category→keywords mapping |
| `GreeklishNormalizer.kt` | Greek text normalization |
| `MerchantCanonicalizer.kt` | Merchant name standardization |

### Recurring Services & Lifecycle (Phase 5 / Phase 5b + P4 Lifecycle Hardening)
**Directory:** `recurring/` and `recurring/lifecycle/`

| Service / Coordinator | File | Purpose |
|------------------------|------|---------|
| `RecurringOccurrenceExpander` | `RecurringOccurrenceExpander.kt` | Pure domain utility that expands a recurrence rule into concrete `OccurrenceCandidate`s within a half-open date range. Uses `TimePeriodUtils` calendar-aware advancement (DST/leap-year safe). Produces deduplication-safe `occurrenceKey`. |
| `OccurrenceConflictResolver` | `OccurrenceConflictResolver.kt` | Resolves occurrence candidates against actual expenses to determine PLANNED/PAID/SKIPPED status. Matching: same calendar day, merchant match (case-insensitive via `MerchantKeyGenerator`), amount ±10% tolerance, same currency. Each expense matched at most once. |
| `RecurringPlanProjectionService` | `RecurringPlanProjectionService.kt` | Bridges recurring lifecycle to forecasting by materialising `PlannedExpense` rows from PLANNED occurrences. Deduplicates via `sourceOccurrenceKey`. Called by forecast pipeline. |
| `RecurringLifecycleCoordinator` | `lifecycle/RecurringLifecycleCoordinator.kt` | **Primary entry point** for generating and managing recurring occurrences. Orchestrates expand → resolve → materialize. Also provides `linkExpenseToOccurrence()` (called by `TransactionLifecycleCoordinator` post-creation), `getOccurrences()`, `updateOccurrenceStatus()`, `getDueReminders()`, and **`reconcilePlannedVsActual()`** (Phase 5b — drift analysis returning `ReconciliationReport`). |
| `RecurringOccurrenceMaterializer` | `lifecycle/RecurringOccurrenceMaterializer.kt` | Persists resolved occurrences (INSERT with IGNORE, UPDATE on status change) and creates `RecurringReminderDelivery` rows for PLANNED occurrences (DUE_DAY, N_DAYS_BEFORE, OVERDUE windows). |
| `RecurringRuleLifecycleCoordinator` | `lifecycle/RecurringRuleLifecycleCoordinator.kt` | **P4: Single writer for rule CRUD.** `createRule()` / `updateRule()` / `activateRule()` / `deactivateRule()` / `deleteRule()`. All rule mutations route through this coordinator with DatabaseWriteBarrier enforcement. |
| `RecurringLifecycleEventWriter` | `lifecycle/RecurringLifecycleEventWriter.kt` | **P4: Dual-channel event writer.** `writeCritical()` → Long (returns eventId), `writeDiagnostic()` for debug-level events. |
| `OccurrenceGenerationOptions` | `lifecycle/OccurrenceGenerationOptions.kt` | **P4:** Controls reminder creation during occurrence generation (generation window, max occurrences per run). |
| `RecurringExpenseReconcileResult` | `lifecycle/RecurringExpenseReconcileResult.kt` | **P4:** Sealed interface for link/unlink operations: `Linked`, `AlreadyLinked`, `NotFound`, `Conflict`, etc. |
| `RecurringOccurrenceStatus` | `lifecycle/RecurringOccurrenceStatus.kt` | **P4:** Typed enum: PLANNED, PAID, SKIPPED, MISSED, CANCELLED, IGNORED + `RecurringOccurrenceTransitionPolicy` for valid state transitions. |

### Receipt Services & Lifecycle
**Directory:** `receipt/` and `receipt/lifecycle/`
| `ReceiptOcrService.kt` | OCR engine invocation | |
| `ReceiptParser.kt` | Receipt format parsing | |
| `BankStatementParser.kt` | Bank statement extraction | |
| `EnhancedMerchantExtractor.kt` | Merchant name extraction | |
| `OcrLanguageProcessor.kt` | Multi-language OCR | |
| `OcrPreprocessingPipeline.kt` | Image preprocessing | |
| `WarrantyTextExtractor.kt` | Warranty clause extraction | |

### Location Services
**Directory:** `location/`

| Service | Purpose | Returns |
|---------|---------|---------|
| `LocationResolver.kt` | Address↔Coordinates conversion | GeocodingResult |
| `GeocodingResult.kt` | Geocoding response | Lat/long, address |
| `LocatedExpense.kt` | Transaction with location | Expense + Location |
| `NearbyPoi.kt` | Point of interest | POI data |
| `LocationModels.kt` | Location data types | Various |

### Privacy Services (NEW — Phase 6)
**Directory:** `privacy/`

| Service | File | Purpose |
|---------|------|---------|
| `PrivacyGate` (interface) | `PrivacyGate.kt` | Contract: `check(capability, context) → PrivacyDecision`. Fail-closed, audit-logged, deterministic. |
| `CompositePrivacyGate` | `CompositePrivacyGate.kt` | Chains Notification, Location, CloudAI, Backup gates; returns first Denied. |
| `NotificationPrivacyGate` | `NotificationPrivacyGate.kt` | Guards NOTIFICATION_CAPTURE and NOTIFICATION_PACKAGE_ALLOWLIST. |
| `LocationPrivacyGate` | `LocationPrivacyGate.kt` | Guards EXTERNAL_GEOCODING, BACKGROUND_LOCATION_BACKFILL, DEVICE_GPS_LOCATION, OVERPASS_API. |
| `CloudAiPrivacyGate` | `CloudAiPrivacyGate.kt` | Guards all CLOUD_AI_* capabilities + RECEIPT_IMAGE_CLOUD_UPLOAD. |
| `BackupPrivacyGate` | `BackupPrivacyGate.kt` | Guards RAWBACKUP_EXPORT and ENCRYPTED_BACKUP. |
| `PrivacySettingsRepository` | `PrivacySettingsRepository.kt` | Interface for reading/writing 10 privacy toggles + 2 retention settings. |
| `PrivacyAuditLogger` | `PrivacyAuditLogger.kt` | Records every gate check to `privacy_audit_events` table. |
| `RedactionSanitizer` | `RedactionSanitizer.kt` | Merchant name hashing/sanitization before cloud AI calls (only `sanitizeMerchant()` method). |

### Budget Services
**Directory:** `budget/`

| Service | Purpose |
|---------|---------|
| `BudgetCalculator.kt` | Period calculation (daily/weekly/monthly/yearly/rolling) |
| `BudgetMonitor.kt` | Track budget vs actual |
| `BudgetRecommendationEngine.kt` | Suggest budget adjustments |
| `SharedBudgetManager.kt` | Group budget coordination |

### Analytics Services
**Directory:** `analytics/`

| Service | Purpose |
|---------|---------|
| `SpendingPaceCalculator.kt` | Daily rate projection |
| `AnomalyDetector.kt` | Statistical outlier detection |
| `MonthlyComparisonCalculator.kt` | Month-over-month analysis |
| `CategoryInsightEngine.kt` | Category trends |
| `MerchantInsightEngine.kt` | Top merchants ranking |
| `DayOfWeekAnalyzer.kt` | Weekly spending patterns |
| `SpendingPersonalityClassifier.kt` | Spending type classification |
| `TotalsAggregationEngine.kt` | Aggregate spending metrics |
| `TransferDirectionAnalytics.kt` | Transfer vs purchase analysis |
| `DataQualityReport.kt` | **NEW (Phase 10)** — Unified data quality report aggregating metrics from analytics, forecasting, currency conversion, and AI pipelines. Fields: totalExpenses, expensesWithCurrency, expensesWithMerchant, expensesWithCategory, conversionConfidence (0.0–1.0), warnings. Factory: `fromNormalization()` pipes `AnalyticsNormalizationResult` into the report. `isReliable` returns true when totalExpenses > 0 and conversionConfidence >= 0.5. |

### Intelligence Services
**Directory:** `intelligence/`

| Service | Purpose |
|---------|---------|
| `TransactionClassifier.kt` | ML classification router |
| `ConfidenceRouter.kt` | Route by confidence threshold |
| `CrossSourceDeduplication.kt` | Multi-source duplicate detection |

### Parser Services
**Directory:** `parser/`

| Service | Purpose |
|---------|---------|
| `AppParserRegistry.kt` | Parser discovery and invocation |
| `GenericTransactionParser.kt` | Fallback parser |
| `TransferDirectionDetector.kt` | In/out classification |

**Parsers (Specialized):**
- `parsers/GoogleWalletParser.kt` - Google Wallet SMS
- `parsers/GreekBankParser.kt` - Greek bank statements
- `parsers/RevolutParser.kt` - Revolut notifications
- `parsers/SmsParser.kt` - Generic SMS parsing

### Other Services
**Directory:** `**/`

| Service | Purpose |
|---------|---------|
| `CurrencyConverter.kt` | FX rate application |
| `CurrencyRatesRepository.kt` | FX data source |
| `CurrencySettingsRepository.kt` | Currency preferences (now includes `emergencyBuffer()`) |
| `AnalyticsCurrencyNormalizer.kt` | Per-expense home-currency normalization for analytics engines |
| `NotificationSubscriptionDetector.kt` | Recurring bill detection |
| `SubscriptionManagerEngine.kt` | Subscription lifecycle |
| `AutomatedSavingsRuleEngine.kt` | Auto-savings setup |
| `SavingsGamificationEngine.kt` | Savings challenges |
| `SmartSavingsEngine.kt` | Savings optimization |
| `BillReminderManager.kt` | Bill notification scheduling (deprecated — use `BillReminderWorker` + settings) |
| `BillReminderSettings.kt` | **P4:** Runtime reminder dispatch config (enabled, quiet hours, dispatch interval) |
| `BillReminderSettingsRepository.kt` | **P4:** Interface for reading/writing reminder settings (bound via `ReminderSettingsModule`) |
| `BillReminderWorker` (data) | `service/reminder/BillReminderWorker.kt` — Periodic WorkManager worker (every 4h) dispatching Android notifications for due/overdue bill reminders via `RecurringLifecycleCoordinator.getDueReminders()`. Checks `BillReminderSettingsRepository` before dispatching. |
| `AreaSpendingEngine.kt` | Geographic analytics |
| `LifestyleInflationDetector.kt` | Spending growth detection |
| `InvestmentTracker.kt` | Investment portfolio |
| `PriceProtectionTracker.kt` | Price match detection |
| `SmartBillNegotiationEngine.kt` | Bill optimization |
| `AnomalyAlertOrchestrator.kt` | Alert triggering |
| `EnhancedSplitManager.kt` | Expense splitting |
| `SpendingChallengeManager.kt` | Challenge creation/tracking |
| `NaturalLanguageSearchEngine.kt` | Voice/text search |
| `DatabaseBackupRepository.kt` | Domain interface for backup/restore. `createCostBackup(password)` → `.costbackup` bundle. `restoreCostBackup(bundleFile, password)` → `DatabaseImportResult`. `getDatabaseStats()` → `DatabaseStats`. |
| `BankApiIntegration.kt` | Bank data sync |

---

## Utilities

### Core Utilities
**Directory:** `util/`

| Utility | Purpose | Key Functions |
|---------|---------|----------------|
| `Money.kt` | Money type wrapper | Format, compare, convert |
| `TimeProvider.kt` | **Single source of "now"** — injected interface, never call `System.currentTimeMillis()` directly | `now()` |
| `TimePeriodUtils.kt` | **Canonical calendar boundary math** — half-open contract `[start, end)`, no system clock calls internally | `getMonthRange()`, `getWeekRange()`, `getQuarterRange()`, `getYearRange()`, `getDayRange()`, `getLastNCalendarDaysRange()`, `getLastNCompleteDaysRange()`, `getTrailingElapsedRange()`, `getDayIndexForSparkline()`, `parseMonthKeyToRange()`, `toPeriodRange()`, `daysBetween()`, `addDays()` — `getLastNDaysRange()` deprecated |
| `DateFormatterUtils.kt` | Date formatting — all 13 methods accept explicit timestamps (no `Instant.now()` internally) | `dateKey()`, `monthDay()`, etc. |
| `AmountUtils.kt` | Numeric amount handling | Parse, format, round |
| `AmountExtractionUtils.kt` | Amount extraction from text | Regex-based parsing |
| `CurrencyFormatter.kt` | Currency display | **New:** `formatMoney(amount, currencyCode)` / `formatMoneyCompact()` / `formatMoneyWithSign()` — require explicit currency. Old `format(amount)` deprecated. |
| `CurrencyNormalizer.kt` | Currency code normalization | Canonical form |
| `StatisticsUtils.kt` | Statistical calculations | StdDev, median, etc. |
| `StringDistanceUtils.kt` | String similarity | Levenshtein, fuzzy match |
| `MerchantCleaner.kt` | Merchant name normalization | Remove special chars |
| `MerchantKeyGenerator.kt` | Canonical merchant ID | Generate from name |
| `GeoUtils.kt` | Geographic calculations | Distance, bounds |
| `NotificationIdGenerator.kt` | Unique ID generation | For notifications |
| `SystemTimeProvider.kt` | Real system time | Concrete implementation |
| `CommonPatterns.kt` | Regex patterns | Email, phone, IBAN, etc. |
| `BKTree.kt` | String distance index | Fast fuzzy search |
| `AppConstants.kt` | Application constants | Magic numbers, thresholds |

---

> **Note:** This document does not yet cover `HybridRouter` (AID-4, `domain/ai/HybridRouter.kt`) and `WorkerSpecScheduler` (`domain/workers/WorkerSpecScheduler.kt`). See `docs/architecture/DEPENDENCY_MAP.md` for current dependency chains.

---

## Clean Architecture Violations

### 🚨 Android Framework Imports in Domain

The following files **violate Clean Architecture** by importing Android/AndroidX classes:

#### Android Framework Imports

| File | Import | Reason | Severity |
|------|--------|--------|----------|
| `ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt` | `android.content.Context` | Domain should not know about Context | **HIGH** |
| `debug/NotificationSeeder.kt` | `android.content.Context` | Debug utility in domain | **MEDIUM** |
| `debug/ServiceDiagnostics.kt` | `android.content.Context`, `SharedPreferences` | Debug in domain, should be in data | **MEDIUM** |
| `intelligence/ml/ExpenseCategoryClassifier.kt` | `android.content.Context` | ML model loading dependency | **HIGH** |
| `intelligence/ml/HybridExpenseClassifier.kt` | `android.content.Context` | ML model loading dependency | **HIGH** |
| `intelligence/ml/MerchantNormalizer.kt` | `android.content.Context` | ML model loading dependency | **HIGH** |
| `intelligence/TransactionClassifier.kt` | `android.content.Context` | ML model loading dependency | **HIGH** |
| `location/LocationResolver.kt` | `android.util.Log` | Should use Timber | **LOW** |
| `model/UiText.kt` | `android.content.Context`, `androidx.annotation.*`, `androidx.compose.runtime.*`, `androidx.compose.ui.res.*` | Domain model is UI-aware | **CRITICAL** |
| `naturallanguage/NaturalLanguageSearchEngine.kt` | `android.content.*`, `android.speech.*`, `android.os.Bundle` | Speech recognition is framework-specific | **CRITICAL** |
| `performance/ImageCache.kt` | `android.content.Context`, `android.graphics.*`, `android.net.Uri` | Image cache should be in data layer | **HIGH** |
| `receipt/ReceiptOcrService.kt` | `androidx.exifinterface.media.ExifInterface` | OK - EXIF is file format library |  **LOW** |

#### Compose Framework Imports

| File | Impact | Suggestion |
|------|--------|-----------|
| `analytics/AdvancedAnalyticsModels.kt` | `@Immutable` from Compose | Remove @Immutable or move to presentation |
| `model/UiText.kt` | Full Compose dependency | Extract `@Composable` functions to presentation layer |

### Remediation Strategy

**Priority 1 (Critical):**
1. **`model/UiText.kt`** - Extract composables to presentation layer
   - Keep domain model as pure data class
   - Move @Composable rendering to UI layer
   - Domain should return raw text/string IDs

2. **`naturallanguage/NaturalLanguageSearchEngine.kt`** - Refactor
   - Move SpeechRecognizer to presentation/feature layer
   - Domain should define interface: `SpeechInput { suspend fun recognize(): String }`
   - Presentation implements with actual framework

3. **ML Classifiers** - Extract Context dependency
   - Add init block to perform model loading in data layer
   - Domain receives pre-loaded classifier interface
   - Example: `interface ExpenseClassifier { suspend fun classify(data): Category }`

**Priority 2 (High):**
4. **`performance/ImageCache.kt`** - Move to data/cache layer
5. **`intelligence/TransactionClassifier.kt`** - Use dependency injection for context
6. **Debug utilities** - Move debug folder or extract domain logic

**Priority 3 (Low):**
7. Replace `android.util.Log` with `timber.log.Timber`
8. Remove `@Immutable` from analytics models

---

## Circular Dependencies

### Analysis

Performed grep search for circular imports. **No direct circular dependencies found** in current implementation.

However, **potential soft circularities exist:**

### Dependency Graph Risks

```
AI Layer ─depends on──→ Domain Engines (analytics, categorization)
         ├────────────→ Models (domain.model.*)
         └────────────→ Services (ai/service/*)
                           │
                           └──depends on─→ Repositories (data layer)

Analytics Engine ─depends on──→ Intelligence (TransactionClassifier)
                   └──────────→ LogicUtils (RecurringExpenseEngine)
```

**Risk:** If any service in `ai/service/` imports from `ai/usecase/` or vice versa, circularity exists.

### Safe Dependencies Direction

✅ **Correct:**
```
UseCase → Engine → Service → Repository → Database
UseCase → Engine → Model
UseCase → Model
Engine → Model
Service → Model
```

❌ **Dangerous:**
```
Service → UseCase (violation - dependency reversal)
Model → UseCase (violation - models should be agnostic)
Engine → UseCase (violation - engines precede use cases)
```

### Recommended Checks

```bash
# Find potential circular imports
grep -r "import.*domain.ai.usecase" app/src/main/java/com/yourname/expensetracker/domain/ai/service/

# Find service→usecase dependencies
grep -r "import.*domain.*usecase" app/src/main/java/com/yourname/expensetracker/domain/*/service/

# Find reverse model dependencies
grep -r "import.*domain.usecase\|import.*domain.engine" app/src/main/java/com/yourname/expensetracker/domain/model/
```

---

## Data Flow Examples

### Scenario 1: User Views Dashboard

```
Dashboard Fragment
    │
    └──→ ComputeDashboardWidgetsUseCase
            │
            ├──→ InsightsEngine.generateInsights()
            │       ├──→ [8 async] SpendingPaceCalculator, AnomalyDetector, ...
            │       └──→ [DB] ExpenseRepository.getTotalForPeriod()
            │
            ├──→ BudgetCalculator.calculatePeriodRange()
            │
            └──→ [DB] ExpenseRepository (fetch transactions)
    
    ┌──Result: DashboardData
    └──Render: DashboardWidgets, Insights, Budget Status
```

### Scenario 2: Transaction Categorization

```
New Transaction Created
    │
    ├──→ CategorizeExpenseUseCase
    │       │
    │       └──→ CategorizationEngine
    │               ├──→ ContextualInferenceEngine (category rules)
    │               ├──→ SemanticKeywordMatcher (merchant keywords)
    │               ├──→ MerchantCanonicalizer (normalize name)
    │               └──→ [ML] HybridExpenseClassifier (if confidence low)
    │                       └──→ [File] Pre-loaded TF Lite model
    │
    └──Result: CategoryId
        │
        └──→ [DB] ExpenseRepository.update(categoryId)
```

### Scenario 3: Receipt Processing (Phase 4)

```
User Selects Receipt Image (Camera/Gallery/File)
    │
    ├──→ ReceiptLifecycleCoordinator.processReceiptInput(uri)
    │       │
    │       ├──→ ReceiptInputValidator.validate(uri)
    │       │       └── [checks readability, MIME, size, image decode]
    │       │
    │       ├──→ ReceiptAssetStore.persistReceiptAsset(uri)
    │       │       └── [copy to app-local storage, compute SHA-256 hash]
    │       │
    │       ├──→ ReceiptDuplicateDetector.checkDuplicate(hash)
    │       │       └── [EXACT_HASH / TEXT_FINGERPRINT / SEMANTIC]
    │       │
    │       ├──→ ReceiptRepository.processReceipt(uri)
    │       │       ├──→ OcrPreprocessingPipeline (image enhancement)
    │       │       ├──→ ReceiptOcrService (OCR invocation)
    │       │       │       └──→ [ML] ML Kit Text Recognition
    │       │       ├──→ ReceiptParser (line item extraction)
    │       │       │       ├──→ AmountExtractionUtils
    │       │       │       ├──→ EnhancedMerchantExtractor
    │       │       │       └──→ WarrantyTextExtractor
    │       │       └──→ CategorizeReceiptItemsUseCase (AI)
    │       │
    │       ├──→ ScannedReceiptDao.update (lifecycle metadata)
    │       │       └── [sourceType, documentType, processingStatus, fingerprints]
    │       │
    │       ├──→ ReceiptEventDao.insert (RECEIPT_SAVED / OCR_FAILED)
    │       │
    │       └──→ ReceiptSideEffectDispatcher.dispatchAfterSave()
    │               ├──→ AutoCreateWarrantyFromReceiptUseCase (RETAIL_RECEIPT)
    │               ├──→ CategorizeReceiptItemsUseCase (RETAIL_ + EMAIL_RECEIPT)
    │               ├──→ ReceiptTransactionMatcher (RETAIL_RECEIPT)
    │               └──→ PriceProtectionTracker (RETAIL_RECEIPT)
    │
    └──→ [DB] ScannedReceipt + ReceiptEvent persisted

=== Bank Statement Path ===
User Selects Statement Image/PDF
    │
    ├──→ ReceiptLifecycleCoordinator.processBankStatement(uri)
    │       │
    │       └──→ BankStatementLifecycleProcessor.processBankStatement(uri)
    │               ├──→ ReceiptRepository.runStatementOcr(uri)
    │               ├──→ BankStatementParser.parse(blocks)
    │               ├──→ ScannedReceiptDao.insert (BANK_STATEMENT type)
    │               ├──→ ReceiptEventDao.insert (RECEIPT_SAVED)
    │               ├──→ [for each transaction] PendingReviewDao.insert
    │               └──→ ReceiptEventDao.insert (PROCESSING_COMPLETE)
    │
    └──→ BankStatementResult(receiptId, transactionsFound, reviewsCreated, duplicatesSkipped)

=== Email Receipt Path ===
Email Ingestion Service (IMAP/POP3)
    │
    ├──→ EmailReceiptParser → EmailReceiptData
    │
    ├──→ ReceiptLifecycleCoordinator.saveEmailReceipt(receipt)
    │       └── [sourceType=EMAIL, documentType=EMAIL_RECEIPT, status=PARSED]
    │
    └──→ ReceiptLinkService.linkReceiptToExpense() (when matched)
```

### Scenario 4: AI Query Execution

```
User: "How much did I spend on groceries this month?"
    │
    ├──→ InterpretFinancialQueryUseCase
    │       │
    │       └──→ QueryInterpretationService (AI)
    │               └──Result: FinancialQueryIntent
    │                   { metric: TOTAL, grouping: null, category: GROCERIES, period: THIS_MONTH }
    │
    ├──→ ExecuteFinancialQueryUseCase
    │       │
    │       ├──→ [DB] ExpenseRepository.getCategoryTotalsForPeriod()
    │       │
    │       └──Result: FinancialQueryResult.Summary
    │           { title: "Total Spending", primaryText: "€234.56" }
    │
    ├──→ MapFinancialQueryToNavigationUseCase
    │       └──Result: NavigationIntent (TRANSACTION_LIST + filter)
    │
    └──Result to User: Summary + Drilldown Link
```

---

## Key Architectural Insights

### 1. Analytics orchestration
- Dashboard analytics are split across focused use cases and domain calculators
- Budget, cash-flow, health, and dashboard widgets are composed from smaller inputs
- Performance is preserved through coroutine-based aggregation

### 2. AI Infrastructure
- Current AI surface spans policy, routing, use cases, and presentation models
- Capability routing is policy-driven via `AiCapabilityRouter` and `AiPolicy`
- Input builders still decouple presentation from AI domain models

### 3. Categorization Pipeline
- **Multi-strategy:** Rules → Keywords → Semantic → ML fallback
- Normalization handles Greek text and merchant variations
- Used by receipt processing and transaction classification

### 4. Location Is Cross-Cutting
- Location and geo services remain cross-cutting across domain and data layers
- Used by dashboard, analytics, travel, and map flows
- Integration is handled through dedicated ports and adapters

### 5. Budget System Complexity
- Budget forecasting is separate from budget status and history-series generation
- Month-end edge cases still matter for calendar-based calculations
- Forecasting flows are now split across focused builders and calculators

### 6. Recurring Transaction Detection
- Recurring detection is shared across savings, reminders, and subscription flows
- Pattern recognition and interval calculation remain the core logic

### 7. Anomaly alerting
- Anomaly alerting is coordinated through dedicated orchestration and review flows
- Results feed dashboard, notification, and review pipelines

---

## Integration Points

### Domain ↔ Data Layer
- Repository interfaces injected into all engines/use cases
- No direct database access within domain
- Async/coroutine-based (Flow<T>, suspend functions)

### Domain ↔ Presentation Layer
- Use cases return domain models (Result<T>)
- Navigation intents for cross-screen routing
- UiText for localization (but violates Clean Architecture)

### Domain ↔ External Libraries
- **Timber** - Logging
- **Kotlin Coroutines** - Async/concurrency
- **Dagger/Hilt** - DI annotations
- **Android ML Kit** - OCR, on-device ML
- **TensorFlow Lite** - Expense classifier models

---

## Testing Considerations

### Unit Testable
- ✅ All utility functions (DateFormatterUtils, AmountUtils, etc.)
- ✅ Pure calculation engines (BudgetCalculator, CurrencyConverter)
- ✅ Logic utilities (SplitCalculator, RecurrenceCalculator)

### Integration Testable (Mock Repositories)
- ✅ Analytics engines (mock ExpenseRepository)
- ✅ Use cases (mock ExpenseRepository + CategorizationEngine)
- ✅ Parsers (mock OCR service)

### System Testable (Full App)
- ✅ End-to-end flows (Receipt → Expense → Dashboard)
- ✅ Concurrent async operations in dashboard and AI use cases

### Difficult to Test
- ⚠️ ML Classifiers (TF Lite model loading with Context)
- ⚠️ NaturalLanguageSearchEngine (framework Speech API)
- ⚠️ LocationResolver (external geocoding)

---

## Summary Statistics

| Metric | Count |
|--------|-------|
| **Total Files** | current inventory |
| **Total Lines of Code** | not restated here |
| **Engines** | current engine set |
| **Use Cases** | current use-case set |
| **Models** | current model set |
| **Services** | current service set |
| **Utilities** | current utility set |
| **Clean Architecture Violations** | review separately |
| **Circular Dependencies** | 0 direct |

---

## Recommendations

### Short Term
1. ✅ Fix `UiText.kt` - Move Composable rendering to presentation
2. ✅ Isolate ML Classifier Context dependency
3. ✅ Move debug utilities to data layer

### Medium Term
4. Extract NaturalLanguageSearchEngine to presentation/feature
5. Consolidate analytics use-case documentation
6. Add integration tests for the main dashboard aggregation flows

### Long Term
7. Implement formal API versioning for domain models
8. Create domain event bus for cross-engine communication
9. Modularize AI layer into separate gradle module
10. Extract location analytics to separate module

---

## Compliance Audit Findings (May 2026)

A full compliance audit of the domain layer identified issues across 6 categories. The following HIGH-severity findings were fixed:

| ID | Finding | Domain File(s) | Fix |
|----|---------|----------------|-----|
| H1 | Direct `scannedReceiptDao.insert()` bypass | `BankStatementLifecycleProcessor.kt:204` | Delegated to `ReceiptRepository` |
| H2 | Raw `sumOf { it.amount }` with no currency field | `SynthesisEngine.kt:168,196,247,254` | Added `currency` field to domain `PlannedExpense`; grouped sums by currency |
| H3 | Hardcoded `currency = "EUR"` in domain models | `DashboardPrimitives.kt`, `SpendingSummary.kt`, `SavingsGoal.kt` | KDoc annotated with migration path |
| H4 | Hardcoded `"EUR"` in domain-adjacent importers | `CsvExpenseImporter.kt:141` | Changed to `CurrencySettingsRepository.homeCurrency()` |
| H6 | `ExpenseDao` deprecated `SUM()` queries | `ExpenseDao.kt` (68+ occurrences) | All `@Deprecated("Use MultiCurrencyRepository")` markers verified |

### MEDIUM Severity: KDoc on EUR defaults (21 files)

| Subsystem | Files | Annotation |
|-----------|-------|------------|
| Analytics Engines | `InsightsEngine`, `SmartSavingsEngine`, `MonthlySavingsSweepUseCase`, `SpendingPaceCalculator` | Each EUR default parameter now carries KDoc stating the home-currency assumption |
| UI Components | `TotalsDashboardCard`, `BudgetBlockPartyCard`, `CategoryBreakdownSheet`, `StatisticalVisualizations`, and 13 more | Same KDoc annotation pattern |

### Infrastructure Audit Results

| Concern | Status |
|---------|--------|
| SimpleDateFormat → DateTimeFormatter | **100% complete** — 38 replacements, 0 remaining |
| REPLACE → IGNORE DAOs | **14 of 14** converted; 3 kept with KDoc |
| Category name uniqueness | NOCASE index (v112→113) + lowercase normalization + `withTransaction` |
| BudgetForecastingEngine currency | Already fully normalized — no changes needed |
| MultiCurrency engine audit | 5 engines verified (1 gap documented in `TotalsAggregationEngine`) |

---

**Generated:** May 4, 2026 | **Version:** 1.1
