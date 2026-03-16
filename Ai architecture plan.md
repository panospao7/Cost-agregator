# AI Architecture Plan

## Purpose

This document defines the architecture and rollout plan for adding a hybrid AI layer to ExpenseTracker.

The goal is to augment the existing deterministic system with AI assistance while preserving the authority of the current financial, parsing, categorization, forecasting, and geolocation logic.

This is not a single-screen feature. It is a new cross-cutting capability that touches multiple layers and segments of the app.

## Planning Strategy: Phase by Trust Boundary

Because this feature spans many parts of the app, planning should be phase-based and targeted.

Recommended planning rule:

- Start with read-only AI output.
- Then add interpreted query intent.
- Then add post-hoc capture assistance.
- Only later consider proactive or semi-automated behavior.

This is the safest fit for the current Clean Architecture.

### Why this phasing model fits ExpenseTracker

- `NotificationProcessingPipeline.kt` is latency-sensitive and mutex-serialized, so cloud AI cannot sit inline there.
- Your existing deterministic engines already produce high-quality structured signals.
- The highest-value first use of AI is explanation and synthesis, not core decision-making.
- Trust matters more than novelty in a finance app.

### Suggested roadmap by phase

| Phase | Theme | Trust Level | Allowed Writes | Primary Segments |
|------|-------|-------------|----------------|------------------|
| 0 | Foundation | None | None | 12, 14, 16 |
| 1 | Advisory Only | Low risk | None | 3, 7, 9, 12, 14, 16 |
| 2 | Query Layer | Medium | Navigation/filter only | 7, 8, 9, 12, 14, 16 |
| 3 | Capture Assist | Higher | Suggestions only | 3, 4, 5, 12, 14, 16 |
| 4 | Proactive / Selective Automation | Highest | Guarded user-confirmed actions only | 1, 2, 3, 7, 10, 17 |

## Architectural Principles

- Deterministic engines remain authoritative.
- AI produces suggestions, briefings, explanations, or interpreted intents.
- AI output is stored separately from core finance tables.
- Cloud AI is always optional and never part of the synchronous capture path.
- Any final financial write must still pass through existing repositories and validation.
- AI failure must degrade gracefully back to existing deterministic UX.
- Privacy defaults should be conservative: opt-in, redact before cloud, and store minimal AI data.

## Existing Architecture Hooks

These are the strongest existing extension points for AI:

- `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/FinancialWeatherRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt`
- `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt`

Important constraint:

- Do not put cloud AI inline in `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`.

## Layers and Segments Touched

### Layers

| Layer | What changes |
|------|---------------|
| UI | New advisory surfaces in Home and Review first, then Transactions/Assistant later |
| Domain | New AI models, policies, service interfaces, and orchestration use cases |
| Data | Provider adapters, artifact persistence, settings persistence, and workers |
| Database | New AI artifact tables, isolated from core finance records |
| DI | New `AiModule.kt`, worker bindings, provider qualifiers |
| Configuration | AI thresholds, TTLs, redaction and rollout flags |

### Segment impact across the full roadmap

| Segment | Impact |
|--------|--------|
| 3 Notification Parsing | Review explanations now, capture assist later |
| 4 Receipt Scanning | Later for multimodal receipt fallback |
| 5 Categorization | Later for Layer 6 AI fallback |
| 7 Analytics | Structured input for briefings and query interpretation |
| 8 Core Expense / Transactions | Phase 2 for natural-language finance queries |
| 9 Dashboard | Phase 1 primary surface for AI briefing |
| 10 Notifications | Later for proactive coaching notifications |
| 11 Debug | Optional AI diagnostics surface |
| 12 DI | New AI bindings and worker setup |
| 14 Use Cases | Main orchestration layer for AI workflows |
| 16 Configuration | AI feature flags, TTLs, privacy defaults |
| 17 Location | Later for place-aware narratives and queries |

### Phase 1 touched segments only

- Segment 3: review explanations only
- Segment 7: analytics and financial-weather data as AI input
- Segment 9: dashboard briefing surface
- Segment 12: AI DI bindings
- Segment 14: AI use cases
- Segment 16: AI config and flags
- Optional Segment 11: internal AI diagnostics

## Recommended Package Structure

To stay consistent with the current codebase, the new AI feature should align with the existing patterns instead of inventing a totally separate architecture.

### Domain

- `domain/ai/model/`
- `domain/ai/policy/`
- `domain/ai/service/`
- `domain/ai/usecase/`

### Data

- `data/ai/provider/`
- `data/ai/worker/`
- `data/repository/AiArtifactRepositoryImpl.kt`
- `data/repository/AiSettingsRepositoryImpl.kt`

### Database

- `data/database/entity/AiArtifact.kt`
- `data/database/dao/AiArtifactDao.kt`

### UI

- `ui/components/ai/`
- `ui/screens/assistant/` (Phase 2+)

## Core Models and Interfaces

### Shared AI enums and models

```kotlin
enum class AiCapability {
    DASHBOARD_BRIEFING,
    REVIEW_EXPLANATION,
    QUERY_INTERPRETATION,
    RECEIPT_EXTRACTION,
    CATEGORIZATION_FALLBACK,
    DEDUPE_JUDGE,
    LOCATION_SUMMARY
}

enum class AiMode {
    ON_DEVICE,
    CLOUD,
    AUTO
}

enum class AiTargetType {
    DASHBOARD,
    PENDING_REVIEW,
    SCANNED_RECEIPT,
    EXPENSE,
    ANALYTICS,
    QUERY_SESSION
}

enum class AiArtifactStatus {
    QUEUED,
    RUNNING,
    READY,
    FAILED,
    DISMISSED,
    APPLIED
}

data class AiTargetRef(
    val type: AiTargetType,
    val id: Long? = null,
    val key: String
)

data class AiSettings(
    val aiEnabled: Boolean = false,
    val allowCloudAi: Boolean = false,
    val allowOnDeviceAi: Boolean = true,
    val proactiveBriefingsEnabled: Boolean = false,
    val redactBeforeCloud: Boolean = true,
    val wifiOnlyForCloud: Boolean = false,
    val storeConversationHistory: Boolean = false,
    val preferredMode: AiMode = AiMode.AUTO
)

data class AiArtifact(
    val id: Long = 0,
    val targetType: AiTargetType,
    val targetId: Long? = null,
    val targetKey: String,
    val capability: AiCapability,
    val status: AiArtifactStatus,
    val mode: AiMode,
    val provider: String? = null,
    val modelName: String? = null,
    val promptVersion: String,
    val summaryText: String? = null,
    val explanationText: String? = null,
    val payloadJson: String? = null,
    val confidence: Float? = null,
    val sourceHash: String,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long? = null
)
```

### Persistence and policy interfaces

```kotlin
interface AiSettingsRepository {
    fun settings(): Flow<AiSettings>
    suspend fun update(transform: (AiSettings) -> AiSettings)
}

interface AiArtifactRepository {
    fun observeLatest(targetKey: String, capability: AiCapability): Flow<AiArtifact?>
    suspend fun getLatest(targetKey: String, capability: AiCapability): AiArtifact?
    suspend fun upsert(artifact: AiArtifact): Long
    suspend fun markDismissed(id: Long)
    suspend fun deleteExpired(now: Long)
}

interface AiPolicy {
    fun canUseCloud(settings: AiSettings): Boolean
    fun shouldRedact(settings: AiSettings, capability: AiCapability): Boolean
}

interface AiWorkScheduler {
    fun scheduleDailyBriefing()
    fun cancelDailyBriefing()
}
```

### Phase 1 service interfaces

```kotlin
interface DashboardBriefingService {
    suspend fun generate(input: DashboardBriefingInput): DashboardBriefing
}

interface ReviewExplanationService {
    suspend fun explain(input: ReviewExplanationInput): ReviewExplanation
}
```

### Future interfaces, but not needed in Phase 1

- `QueryInterpretationService`
- `ReceiptAssistService`
- `CategorizationAssistService`
- `DeduplicationAssistService`

## Use Cases

### Phase 1 use cases

- `GenerateDashboardBriefingUseCase`
- `ExplainPendingReviewUseCase`
- `GetCachedAiArtifactUseCase`
- `RecordAiArtifactDismissalUseCase`

### Future use cases

- `InterpretFinancialQueryUseCase`
- `ExecuteFinancialQueryUseCase`
- `EnrichReceiptWithAiUseCase`
- `SuggestCategorizationFallbackUseCase`
- `JudgeDuplicateCandidateUseCase`
- `ApplyAiSuggestionUseCase`
- `RecordAiFeedbackUseCase`

## Query Boundary

When Phase 2 begins, the model should not directly emit `TransactionFilter` as the core domain contract.

Keep `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilter.kt` as a UI navigation filter.

Introduce a richer domain query model first, then map it down to `TransactionFilter` only when the result is a navigation action.

```kotlin
data class FinancialQueryIntent(
    val filters: ExpenseQueryFilters,
    val aggregation: Aggregation,
    val grouping: Grouping? = null,
    val comparison: Comparison? = null,
    val answerMode: AnswerMode
)
```

## Database Schema Plan

### Important rule

Do not add AI-specific columns directly to `Expense`, `PendingReview`, or `ScannedReceipt` in Phase 1.

Reason:

- `PendingReview` already contains deterministic explanation inputs such as confidence, match type, and raw notification text.
- `ScannedReceipt` already contains OCR input and parsed fields.
- AI output should be stored separately so it can be expired, regenerated, or disabled without altering financial ground truth.

### Phase 1 Room table: `ai_artifacts`

```sql
CREATE TABLE ai_artifacts (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    targetType TEXT NOT NULL,
    targetId INTEGER,
    targetKey TEXT NOT NULL,
    capability TEXT NOT NULL,
    status TEXT NOT NULL,
    mode TEXT NOT NULL,
    provider TEXT,
    modelName TEXT,
    promptVersion TEXT NOT NULL,
    summaryText TEXT,
    explanationText TEXT,
    payloadJson TEXT,
    confidence REAL,
    sourceHash TEXT NOT NULL,
    errorMessage TEXT,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    expiresAt INTEGER
)
```

### Recommended indices

- Unique index on `(targetKey, capability, promptVersion, sourceHash)`
- Index on `(targetKey, capability, updatedAt DESC)`
- Index on `(status, updatedAt)`
- Index on `(expiresAt)`

### Why `targetKey` is needed

`targetId` alone is not enough.

Examples:

- Dashboard briefing target: `dashboard_home:2026-03-16`
- Review explanation target: `pending_review:123`

This lets us cache AI output for both row-backed and logical screen targets.

### Migration path

- Current DB version: 33 in `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`
- Phase 1 migration: `33 -> 34` add `ai_artifacts`
- Phase 2 migration: `34 -> 35` add `ai_chat_sessions` and `ai_chat_messages`

### AI settings persistence

Use DataStore, not Room, for user preferences.

Suggested keys:

- `ai_enabled`
- `ai_allow_cloud`
- `ai_allow_on_device`
- `ai_proactive_briefings`
- `ai_redact_before_cloud`
- `ai_wifi_only_for_cloud`
- `ai_store_conversation_history`
- `ai_preferred_mode`

## State Management Pattern

AI state should stay separate from deterministic screen state.

Use a shared UI state wrapper:

```kotlin
sealed interface AiLoadState<out T> {
    data object Disabled : AiLoadState<Nothing>
    data object Idle : AiLoadState<Nothing>
    data object Loading : AiLoadState<Nothing>
    data class Ready<T>(val value: T, val stale: Boolean = false) : AiLoadState<T>
    data class Error(val message: String) : AiLoadState<Nothing>
}
```

### Recommended ViewModel integration

- `HomeViewModel`: combine deterministic dashboard data with latest dashboard briefing artifact and AI settings.
- `ReviewViewModel`: keep a map keyed by review id for explanation state.
- `TransactionsViewModel`: unchanged in Phase 1.
- `AnalyticsViewModel`: unchanged in Phase 1 UI, but remains an important data source.
- `ReceiptScanViewModel`: unchanged in Phase 1.
- `MainActivity.kt`: no assistant overlay yet in Phase 1.

## Execution Model

Not every AI action needs WorkManager.

Use the existing WorkManager pattern only where it adds value.

### Background jobs

- `DailyBriefingWorker`: yes, for proactive dashboard briefing generation
- `AiCleanupWorker`: optional later, to delete expired artifacts

### Direct user-triggered execution

- Review explanation can run directly from `ReviewViewModel` in a cancellable coroutine
- The result should still be cached in `ai_artifacts`

### Non-negotiable rule

- AI must never block `NotificationProcessingPipeline.kt`

## DI Blueprint

Add a dedicated `di/AiModule.kt`.

Recommended bindings:

- `AiArtifactRepository`
- `AiSettingsRepository`
- `AiPolicy`
- `AiWorkScheduler`
- `DashboardBriefingService`
- `ReviewExplanationService`

Recommended qualifiers:

- `@CloudAi`
- `@OnDeviceAi`

The existing `DispatchersModule.kt` is sufficient for Phase 1.

## Phase Breakdown Summary

## Phase 0: Foundation

### Goal

Create the AI infrastructure without changing user-facing behavior yet.

### Scope

- Core enums and models
- `AiArtifact` Room table and DAO
- AI settings DataStore
- `AiModule.kt`
- provider abstraction and fake implementation
- prompt versioning and source hashing
- privacy/redaction policy
- WorkManager scheduler skeleton

### Non-goals

- No AI UI yet
- No query chat
- No parsing fallback

## Phase 1: Advisory Only

### Goal

Introduce visible AI value while keeping the app fully deterministic and read-only.

### Phase 1 scope

1. Dashboard AI briefing on Home
2. Review explanation assistant in Review queue

### Phase 1 non-goals

- No assistant chat screen
- No natural-language transaction querying
- No receipt multimodal extraction
- No categorization fallback
- No automatic review approval
- No budget mutation or financial writes from AI

### Why this is the right first release

- It gives immediate value with minimal trust risk.
- It uses your strongest existing structured data.
- It avoids changes to the capture path.
- It lets users build confidence in AI before the app asks for more responsibility.

## Detailed Phase 1 Plan

### Product scope

#### A. Home Dashboard Briefing

AI turns structured dashboard state into a short daily briefing.

Recommended behavior:

- Reuse the existing `NaturalLanguageInsight` slot rather than adding a new dashboard card.
- If AI is enabled and a fresh artifact exists, show the AI briefing.
- If AI is disabled, unavailable, stale, or failed, fall back to the current deterministic insight.

Why this default is best:

- It avoids dashboard clutter.
- It minimizes widget-config churn.
- It preserves a deterministic fallback in the same place.

#### B. Review Explanation Assistant

AI explains why a transaction is in review and what signals likely led to the suggestion.

Recommended behavior:

- Add AI explanation inside the existing explanation/evidence area of `ReviewScreen.kt`.
- Trigger generation on demand when the user expands or taps the explanation area.
- Cache the result in `ai_artifacts`.
- Never auto-approve from this flow.

Why this default is best:

- It improves trust exactly where uncertainty already exists.
- It fits the current `PendingReview` model and screen layout.
- It adds explanation without changing approval logic.

### Phase 1 touched files and classes

#### New files

- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiLoadState.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/service/DashboardBriefingService.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/service/ReviewExplanationService.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/AiArtifact.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/AiArtifactDao.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/AiArtifactRepositoryImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt`
- `app/src/main/java/com/yourname/expensetracker/di/AiModule.kt`

#### Updated files

- `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/config/AppConfig.kt`
- `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt`

#### Files that should stay untouched in Phase 1 unless absolutely necessary

- `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt`

### Phase 1 domain contracts

#### Dashboard briefing input/output

```kotlin
data class DashboardBriefingInput(
    val dateKey: String,
    val weatherHeadline: String,
    val weatherSummary: String,
    val discretionaryBudget: Double,
    val totalCommitted: Double,
    val totalLikely: Double,
    val pendingReviewCount: Int,
    val currentMonthSpent: Double,
    val topCategories: List<String>,
    val budgetWarnings: List<String>,
    val upcomingItems: List<String>
)

data class DashboardBriefing(
    val title: String,
    val text: String,
    val tone: String,
    val confidence: Float?
)
```

#### Review explanation input/output

```kotlin
data class ReviewExplanationInput(
    val reviewId: Long,
    val merchant: String,
    val amount: Double,
    val currency: String,
    val suggestedType: String,
    val suggestedCategoryId: Long?,
    val confidence: Float,
    val matchType: String?,
    val explanation: String?,
    val packageName: String,
    val notificationTitle: String?,
    val notificationText: String?
)

data class ReviewExplanation(
    val headline: String,
    val body: String,
    val caution: String? = null,
    val confidence: Float? = null
)
```

### Phase 1 integration flows

#### Dashboard briefing flow

1. `GenerateDashboardBriefingUseCase` collects deterministic data from existing dashboard sources.
2. A target key is built, for example `dashboard_home:2026-03-16`.
3. The use case checks for a fresh artifact in `AiArtifactRepository`.
4. If missing or stale, it calls `DashboardBriefingService`.
5. The result is stored in `ai_artifacts`.
6. `HomeViewModel` combines dashboard data + AI settings + latest artifact.
7. The Home UI renders AI text if present, otherwise existing deterministic insight.

#### Review explanation flow

1. User expands or taps the explanation area for a pending review.
2. `ReviewViewModel` calls `ExplainPendingReviewUseCase(reviewId)`.
3. The use case loads `PendingReview` and derives `ReviewExplanationInput`.
4. Optional redaction runs before any cloud call.
5. `ReviewExplanationService` returns explanation text.
6. The result is cached in `ai_artifacts` with target key `pending_review:<id>`.
7. `ReviewViewModel` updates `AiLoadState` for that review row.
8. The UI shows explanation, loading, disabled, or error state.

### Phase 1 state model additions

#### Home

Recommended addition inside `DashboardState`:

```kotlin
val aiBriefing: AiLoadState<DashboardBriefingUi> = AiLoadState.Disabled
```

Recommended default implementation:

- Do not create a separate new dashboard widget in Phase 1.
- Replace the content of the existing `NaturalLanguageInsight` slot when AI data is ready.

#### Review

Recommended addition inside `ReviewViewModel` only:

```kotlin
private val _aiExplanationStates = MutableStateFlow<Map<Long, AiLoadState<ReviewExplanationUi>>>(emptyMap())
val aiExplanationStates: StateFlow<Map<Long, AiLoadState<ReviewExplanationUi>>> = _aiExplanationStates
```

This keeps deterministic review data separate from AI advisory state.

### Phase 1 background strategy

#### Use WorkManager for

- Daily proactive briefing generation

#### Do not use WorkManager for

- Review explanation on user tap

Reason:

- Daily briefing is proactive and cacheable.
- Review explanation is interactive and benefits from direct coroutine execution.

### Phase 1 feature flags

Recommended flags:

- `ai_enabled`
- `ai_dashboard_briefing_enabled`
- `ai_review_explanation_enabled`
- `ai_cloud_enabled`
- `ai_debug_prompt_storage_enabled`

### Phase 1 AppConfig additions

Add `AppConfig.Ai` with at least:

- `DASHBOARD_BRIEFING_TTL_MS = 24h`
- `REVIEW_EXPLANATION_TTL_MS = 30d`
- `MAX_REVIEW_TEXT_CHARS_FOR_CLOUD`
- `MAX_BRIEFING_LENGTH_CHARS`
- `PROMPT_VERSION_DASHBOARD`
- `PROMPT_VERSION_REVIEW`

### Phase 1 privacy defaults

Recommended defaults:

- AI disabled by default until user opts in
- Cloud disabled unless explicitly enabled
- Redaction enabled by default
- Conversation history disabled by default
- Prompt storage disabled outside debug mode

## Phase 1 Testing Plan

### Unit tests

- `GenerateDashboardBriefingUseCaseTest`
- `ExplainPendingReviewUseCaseTest`
- `AiPolicyTest`

### Repository and DAO tests

- `AiArtifactDaoTest`
- `AiArtifactRepositoryImplTest`
- DataStore settings repository test

### Migration tests

- `33 -> 34` migration for `ai_artifacts`

### ViewModel tests

- `HomeViewModel` fallback behavior when AI disabled or artifact missing
- `ReviewViewModel` loading, ready, and error transitions for explanations

### Integration checks

- Home still renders when AI provider fails
- Review screen still renders when AI provider fails
- Notification capture behavior unchanged
- No direct AI-triggered writes to `Expense` or `PendingReview`

## Phase 1 Success Criteria

- App works normally with AI fully disabled
- No regression in notification capture path
- Home dashboard can show AI briefing with deterministic fallback
- Review queue can show AI explanation with deterministic fallback
- AI output is persisted separately and can be invalidated or regenerated
- Migration is stable
- Failures are non-blocking and visible only in the AI surface

## Recommended PR Slicing Inside Phase 1

Even Phase 1 should be split into smaller targeted changes.

### PR 1: Foundation slice

- Add `AiArtifact` entity and DAO
- Add migration `33 -> 34`
- Add DataStore settings
- Add `AiModule.kt`
- Add fake provider implementations

### PR 2: Home briefing slice

- Add dashboard briefing service and use case
- Add `DailyBriefingWorker`
- Update `HomeViewModel`
- Reuse `NaturalLanguageInsight` slot for AI output

### PR 3: Review explanation slice

- Add review explanation service and use case
- Update `ReviewViewModel`
- Update `ReviewScreen.kt`

### PR 4: Hardening slice

- Add tests
- Add cleanup policy
- Add logging and debug diagnostics if needed

## Phase 1 Task-by-Task Execution Checklist

This is the execution order for Phase 1.

Do these in sequence. Do not start UI work before the storage, DI, and provider seams exist.

### Milestone 1: Foundation and persistence

#### 1.1 Domain models

- [ ] Create `app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiModels.kt`
- [ ] Add `AiCapability`, `AiMode`, `AiTargetType`, `AiArtifactStatus`, `AiSettings`, `AiArtifact`
- [ ] Add `DashboardBriefing`, `DashboardBriefingInput`, `ReviewExplanation`, `ReviewExplanationInput`
- [ ] Keep these models UI-agnostic

Done when:

- Domain AI models compile without depending on Compose, Room, or Android framework types

#### 1.2 Shared AI UI state

- [ ] Create `app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiLoadState.kt`
- [ ] Add `Disabled`, `Idle`, `Loading`, `Ready`, `Error`
- [ ] Keep it generic so Home and Review can both reuse it

Done when:

- Both Home and Review can reference the same state wrapper without custom variants

#### 1.3 Room entity and DAO

- [ ] Create `app/src/main/java/com/yourname/expensetracker/data/database/entity/AiArtifact.kt`
- [ ] Create `app/src/main/java/com/yourname/expensetracker/data/database/dao/AiArtifactDao.kt`
- [ ] Add queries for `observeLatest`, `getLatest`, `upsert/insert`, and `deleteExpired`
- [ ] Add a uniqueness strategy based on `targetKey + capability + promptVersion + sourceHash`

Done when:

- The app can persist and re-read a cached briefing or review explanation without touching financial tables

#### 1.4 Database registration and migration

- [ ] Add `AiArtifact::class` to `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`
- [ ] Add `abstract fun aiArtifactDao(): AiArtifactDao`
- [ ] Bump DB version from `33` to `34`
- [ ] Add `MIGRATION_33_34` for the new `ai_artifacts` table and indices
- [ ] Register `MIGRATION_33_34` in `app/src/main/java/com/yourname/expensetracker/di/DatabaseModule.kt`

Done when:

- Existing installs migrate cleanly and new installs create the table correctly

#### 1.5 AI settings persistence

- [ ] Add AI settings repository implementation, preferably `AiSettingsRepositoryImpl`
- [ ] Use DataStore or preferences-backed storage for `ai_enabled`, `ai_allow_cloud`, `ai_dashboard_briefing_enabled`, `ai_review_explanation_enabled`, and redaction flags
- [ ] Expose a `Flow<AiSettings>`

Done when:

- The app can fully disable all AI behavior without removing code paths

#### 1.6 Config constants

- [ ] Add `AppConfig.Ai` to `app/src/main/java/com/yourname/expensetracker/domain/config/AppConfig.kt`
- [ ] Add TTLs, prompt versions, and max text limits
- [ ] Add constants for Phase 1 only; do not add future-phase clutter yet

Done when:

- Phase 1 logic does not hardcode TTLs or prompt versions inside repositories or ViewModels

#### 1.7 Repository abstractions and DI

- [ ] Create `AiArtifactRepository` interface and implementation
- [ ] Create `AiSettingsRepository` interface and implementation if not colocated yet
- [ ] Create `AiPolicy` interface for cloud/redaction decisions
- [ ] Add `app/src/main/java/com/yourname/expensetracker/di/AiModule.kt`
- [ ] Bind fake or no-op provider implementations first so the feature can compile before real provider work

Done when:

- Home and Review can depend on AI abstractions without knowing provider details

#### 1.8 Worker and scheduler skeleton

- [ ] Create `DailyBriefingWorker` skeleton
- [ ] Create `AiWorkScheduler` abstraction and initial implementation
- [ ] Do not schedule anything from UI code directly
- [ ] Delay actual scheduling hookup until the dashboard briefing use case exists

Done when:

- Background AI generation has a clear seam but does not run yet unless explicitly wired

### Milestone 2: Home dashboard briefing

#### 2.1 Service contract and provider

- [ ] Create `DashboardBriefingService`
- [ ] Add a fake/local implementation first for UI integration
- [ ] Add the cloud-backed implementation behind the same interface
- [ ] Make the service return short structured output, not raw markdown blobs

Done when:

- Home can render briefing content from a provider without caring whether it came from fake or cloud logic

#### 2.2 Input builder

- [ ] Build a mapper that converts deterministic dashboard data into `DashboardBriefingInput`
- [ ] Source inputs from existing dashboard/weather/pending data instead of querying new raw tables
- [ ] Keep financial numbers deterministic and pass them into AI only for summarization

Done when:

- The input object contains enough context for a useful briefing without duplicating business logic

#### 2.3 Use case

- [ ] Create `GenerateDashboardBriefingUseCase`
- [ ] Build a daily `targetKey` such as `dashboard_home:yyyy-mm-dd`
- [ ] Check settings before any provider call
- [ ] Check cache freshness before generating
- [ ] Save successful results into `ai_artifacts`
- [ ] Fall back cleanly if provider fails

Done when:

- A single use case can decide whether to reuse cache, generate new text, or stay disabled

#### 2.4 Background generation hook

- [ ] Implement `DailyBriefingWorker` with the use case
- [ ] Add scheduling hook in `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt` only after settings and use case exist
- [ ] Ensure worker respects opt-in and cloud settings

Done when:

- The app can proactively refresh a dashboard briefing without blocking startup or any user flow

#### 2.5 Home ViewModel integration

- [ ] Update `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`
- [ ] Combine deterministic dashboard data with AI settings and cached/latest briefing artifact
- [ ] Replace the existing `NaturalLanguageInsight` widget content when AI briefing is ready
- [ ] If deterministic `NaturalLanguageInsight` is absent, inject one in a stable position rather than creating a brand-new widget type

Done when:

- The Home screen can show AI insight through the existing widget surface with zero regression when AI is off

#### 2.6 Home UI validation

- [ ] Review `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
- [ ] Confirm no UI changes are needed if the existing `NaturalLanguageInsight` card is reused as-is
- [ ] Only add visual AI labeling if it is low-noise and clearly beneficial

Done when:

- The Home screen renders correctly on both AI and fallback paths

### Milestone 3: Review explanation assistant

#### 3.1 Service contract and provider

- [ ] Create `ReviewExplanationService`
- [ ] Add a fake/local implementation first for integration testing
- [ ] Add the cloud-backed implementation behind the same contract
- [ ] Keep output structured as `headline`, `body`, optional `caution`, optional `confidence`

Done when:

- Review explanation content can be generated independently of the screen implementation

#### 3.2 Review input builder and redaction

- [ ] Build a mapper from `PendingReview` to `ReviewExplanationInput`
- [ ] Redact raw notification text before cloud calls when settings require it
- [ ] Clamp input text length using `AppConfig.Ai`

Done when:

- The explanation provider never receives more raw review text than allowed by policy

#### 3.3 Use case

- [ ] Create `ExplainPendingReviewUseCase`
- [ ] Load the review record from existing repository APIs
- [ ] Build target key `pending_review:<id>`
- [ ] Reuse cached explanation when valid
- [ ] Store generated explanation in `ai_artifacts`
- [ ] Return deterministic fallback states on failure instead of throwing into the UI

Done when:

- A single call can power the full “explain this review” interaction safely

#### 3.4 Review ViewModel integration

- [ ] Update `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt`
- [ ] Add `Map<Long, AiLoadState<ReviewExplanationUi>>`
- [ ] Add a method like `loadAiExplanation(reviewId: Long)`
- [ ] Prevent duplicate concurrent requests for the same review id
- [ ] Leave approve/reject/edit flows untouched

Done when:

- Review AI state is isolated per row and cannot interfere with approval logic

#### 3.5 Review UI integration

- [ ] Update `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt`
- [ ] Pass explanation state and load callback into each `ReviewCard`
- [ ] Add a dedicated AI explanation section separate from the existing debug/evidence tap area
- [ ] Show loading, ready, disabled, and error states
- [ ] Keep raw evidence/debug affordance intact

Done when:

- Users can request an explanation without losing the current evidence/debug workflow

### Milestone 4: Hardening and verification

#### 4.1 DAO and repository tests

- [ ] Add `AiArtifactDaoTest`
- [ ] Add `AiArtifactRepositoryImplTest`
- [ ] Verify unique key behavior and latest-artifact queries

Done when:

- Cache correctness is covered by automated tests

#### 4.2 Migration test

- [ ] Extend database migration tests for `33 -> 34`
- [ ] Verify the new table and indices exist after migration

Done when:

- The migration is validated in test rather than trusted manually

#### 4.3 Use case tests

- [ ] Add `GenerateDashboardBriefingUseCaseTest`
- [ ] Add `ExplainPendingReviewUseCaseTest`
- [ ] Add `AiPolicyTest`
- [ ] Cover disabled settings, stale cache, provider failure, and success paths

Done when:

- Core orchestration logic is test-covered without needing UI tests

#### 4.4 ViewModel tests

- [ ] Add Home ViewModel tests for AI enabled, AI disabled, cached artifact, and fallback behavior
- [ ] Add Review ViewModel tests for per-row loading, ready, and error states

Done when:

- Phase 1 screen behavior is stable before manual QA

#### 4.5 Manual QA checklist

- [ ] Verify app behavior with AI fully disabled
- [ ] Verify Home shows deterministic fallback when AI fails
- [ ] Verify Review explanations do not change approve/reject behavior
- [ ] Verify notification capture flow is unchanged
- [ ] Verify no new crashes on app startup or DB migration

Done when:

- Phase 1 is safe to release behind a feature flag

## Suggested Execution Order Across PRs

Use this order exactly unless a blocker appears:

1. PR 1 tasks 1.1 through 1.8
2. PR 2 tasks 2.1 through 2.6
3. PR 3 tasks 3.1 through 3.5
4. PR 4 tasks 4.1 through 4.5

## What Should Not Happen During Phase 1

- Do not modify `NotificationProcessingPipeline.kt` to call AI directly
- Do not modify `ReceiptRepository.kt` for AI fallback yet
- Do not add chat/session tables yet
- Do not add automatic apply/approve actions
- Do not let AI become the source of truth for amounts, categories, or budget status

## Phase 2: Query Layer

### Goal

Let users ask natural-language questions about their expense history and receive either:

- a deterministic answer computed from existing repositories, or
- a navigation action into an existing filtered screen

The AI layer should interpret user intent, not replace financial math, filtering rules, or repository access.

### Phase 2 scope

1. Global assistant host launched from the main app shell
2. Natural-language interpretation for approved expense queries
3. Deterministic execution of supported queries over existing expense data
4. Navigation from assistant results into filtered `TransactionsScreen`
5. Optional persisted session history behind explicit opt-in

### Phase 2 non-goals

- No receipt AI fallback
- No categorization fallback
- No automatic review approval or bulk apply actions
- No AI-created or AI-edited expenses, budgets, or planned expenses
- No freeform “financial advice” that bypasses deterministic calculations
- No model-generated SQL or direct Room-query strings
- No assistant ownership of the main navigation stack
- No changes to the synchronous notification capture pipeline

### Why this is the right second release

- It stays read-only while expanding AI from explanation into retrieval.
- It keeps AI responsible only for interpretation, not numeric truth.
- It reuses your strongest existing data surfaces: `Expense`, `ExpenseWithCategory`, `AnalyticsRepository`, and `TransactionsScreen` drilldown.
- It forces the app to tighten its transaction filter pipeline before any higher-risk AI capture work begins.

## Detailed Phase 2 Plan

### Product scope

#### A. Global Assistant Host

Add a single assistant entry point that can be opened from anywhere in the app.

Recommended behavior:

- Host the assistant in `MainActivity.kt` / `MainScreen` as a modal bottom sheet or full-height overlay.
- Do not add a new bottom-nav tab in Phase 2.
- Keep launch and dismissal local to the main shell so the assistant can open above any current tab.
- If AI is disabled, show a clear disabled / consent state inside the assistant surface rather than blocking Phase 2 on a separate settings screen.

Why this default is best:

- The app already manages overlays directly in `MainActivity.kt`.
- The bottom nav is already dense and should not gain a seventh tab.
- A shell-level host can trigger navigation into `TransactionsScreen` without introducing a new route graph.

#### B. Natural-Language Expense Query

Phase 2 should support only a narrow, high-confidence set of expense queries.

Recommended first-release query families:

- “Show me ...” list queries that map to filtered transaction drilldown
- total / count / average queries over filtered expenses
- top merchant / top category breakdown queries within a time period
- largest transaction queries within a time period
- previous-equivalent-period comparison for totals only

Recommended boundaries:

- Query only approved `Expense` data in Phase 2.
- Do not query `PendingReview`, receipts, or budget mutations through the assistant.
- Treat ambiguous queries as clarification opportunities, not as a license to guess.

Why this default is best:

- It provides obvious user value with a medium trust boundary.
- It maps well onto the repositories and filters that already exist.
- It avoids turning the assistant into an unbounded chat product too early.

#### C. Deterministic Answer and Navigation Layer

The model should never be the final executor of a financial query.

Recommended behavior:

- AI parses raw text into a structured domain intent.
- App code executes that intent deterministically using repositories and existing period utilities.
- UI results are rendered from deterministic outputs.
- When the result is a list/drilldown, offer a CTA that opens `TransactionsScreen` with a matching `TransactionFilter`.

Important repo reality to plan around:

- `TransactionsViewModel` currently splits filtering logic between tab/date flows, local search, ownership filtering, and a paged `ALL` tab path.
- The `ALL` tab path does not yet fully honor structured drilldown filters such as merchant/category/date/type.
- Phase 2 must include a deterministic filter-alignment slice before assistant-driven navigation is treated as complete.

#### D. Session and History Policy

The assistant can maintain short-lived conversational context, but persistence must stay optional.

Recommended behavior:

- Keep current-session context in `AssistantViewModel`.
- Persist sessions and messages only when `storeConversationHistory` is enabled.
- If history storage is disabled, keep the session ephemeral in memory only.
- Do not store provider raw prompts or full redacted/unredacted payloads by default.

Why this default is best:

- It supports lightweight follow-up refinement without forcing transcript storage.
- It respects the conservative privacy posture already established in Phase 1.

### Phase 2 touched files and classes

#### New files

- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/service/QueryInterpretationService.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/service/AiChatRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/AiChatSessionEntity.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/AiChatMessageEntity.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/AiChatSessionDao.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/AiChatMessageDao.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/AiChatRepositoryImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/assistant/AssistantViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/assistant/AssistantSheet.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/ai/AssistantResultCard.kt`

#### Updated files

- `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`
- `app/src/main/java/com/yourname/expensetracker/di/DatabaseModule.kt`
- `app/src/main/java/com/yourname/expensetracker/di/AiModule.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/config/AppConfig.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/MainViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilter.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt`

#### Files that should stay untouched in Phase 2 unless absolutely necessary

- `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt`

### Phase 2 domain contracts

#### Query intent model

```kotlin
enum class QueryMetric {
    LIST,
    TOTAL,
    COUNT,
    AVERAGE,
    MAX,
    MIN
}

enum class QueryGrouping {
    NONE,
    CATEGORY,
    MERCHANT,
    DAY,
    WEEK,
    MONTH
}

enum class QueryComparison {
    NONE,
    PREVIOUS_EQUIVALENT_PERIOD
}

enum class AnswerMode {
    INLINE_ANSWER,
    NAVIGATE,
    BOTH
}

enum class QueryOwnershipScope {
    ALL,
    MINE,
    NOT_MINE,
    SHARED,
    TRANSFER
}

data class ExpenseQueryFilters(
    val period: PeriodRange? = null,
    val merchants: Set<String> = emptySet(),
    val categoryIds: Set<Long> = emptySet(),
    val transactionTypes: Set<TransactionType> = emptySet(),
    val ownership: QueryOwnershipScope = QueryOwnershipScope.ALL,
    val minAmount: Double? = null,
    val maxAmount: Double? = null
)

data class FinancialQueryIntent(
    val rawQuery: String,
    val normalizedQuery: String,
    val filters: ExpenseQueryFilters,
    val metric: QueryMetric,
    val grouping: QueryGrouping = QueryGrouping.NONE,
    val comparison: QueryComparison = QueryComparison.NONE,
    val answerMode: AnswerMode = AnswerMode.BOTH
)
```

Important rule:

- Keep `TransactionFilter.kt` as a UI navigation filter.
- Do not make it the primary domain contract for interpretation.
- Map `FinancialQueryIntent` down to `TransactionFilter` only at the navigation boundary.

#### Query execution result model

```kotlin
sealed interface FinancialQueryResult {
    data class Summary(
        val title: String,
        val primaryText: String,
        val supportingText: String? = null,
        val navigationFilter: TransactionFilter? = null
    ) : FinancialQueryResult

    data class Breakdown(
        val title: String,
        val rows: List<Row>,
        val navigationFilter: TransactionFilter? = null
    ) : FinancialQueryResult {
        data class Row(
            val label: String,
            val amount: Double,
            val count: Int? = null
        )
    }

    data class TransactionList(
        val title: String,
        val navigationFilter: TransactionFilter,
        val previewCount: Int
    ) : FinancialQueryResult

    data class Clarification(
        val prompt: String,
        val options: List<String>
    ) : FinancialQueryResult

    data class Unsupported(
        val reason: String
    ) : FinancialQueryResult
}
```

Important rule:

- Numeric values in `FinancialQueryResult` must come from deterministic repository execution.
- AI may help interpret the question, but it must not fabricate or directly format authoritative totals.

#### Assistant session model

```kotlin
enum class AssistantMessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

enum class AssistantMessageKind {
    QUERY,
    RESULT,
    CLARIFICATION,
    ERROR
}

data class AiChatSession(
    val id: Long = 0,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long
)

data class AiChatMessage(
    val id: Long = 0,
    val sessionId: Long,
    val role: AssistantMessageRole,
    val kind: AssistantMessageKind,
    val text: String,
    val payloadJson: String? = null,
    val createdAt: Long
)
```

### Phase 2 integration flows

#### Assistant query flow

1. User opens the assistant host from the main shell.
2. `AssistantViewModel` checks AI settings and current session state.
3. `InterpretFinancialQueryUseCase` builds an interpretation input using the raw query plus grounded app context such as category names, recent merchants, and current date.
4. `QueryInterpretationService` returns either a structured `FinancialQueryIntent` or a clarification / unsupported response.
5. `ExecuteFinancialQueryUseCase` runs the supported intent deterministically through repositories and utilities.
6. The assistant renders a result card, clarification chips, or an unsupported state.
7. If history is enabled, the turn is persisted in `ai_chat_sessions` / `ai_chat_messages`.

#### Transactions drilldown flow

1. A query result includes a valid drilldown target.
2. `MapFinancialQueryToNavigationUseCase` converts the structured filters into `TransactionFilter`.
3. `MainViewModel` emits a richer navigation event than the current tab-only request.
4. `MainActivity.kt` sets `activeTransactionFilter` and moves to the Activity / Transactions tab.
5. `TransactionsScreen` applies the filter and shows the same banner / chip affordance used by existing drilldowns.

#### Session persistence flow

1. When `storeConversationHistory` is false, the assistant session remains in memory only.
2. When `storeConversationHistory` is true, the session and user-visible messages are stored in separate AI tables.
3. Clearing session history deletes only assistant tables, not `ai_artifacts`, `Expense`, `PendingReview`, or any other financial tables.

### Phase 2 state model additions

#### Main shell

Recommended navigation evolution inside `MainViewModel`:

```kotlin
sealed interface MainNavigationRequest {
    data class Tab(val index: Int) : MainNavigationRequest
    data class Transactions(val filter: TransactionFilter) : MainNavigationRequest
}
```

Reason:

- The current tab-only channel is not rich enough for assistant-driven drilldown.
- This keeps navigation state in the shell instead of pushing assistant logic into `TransactionsViewModel`.

#### Assistant

Recommended addition in `AssistantViewModel` only:

```kotlin
data class AssistantUiState(
    val messages: List<AiChatMessage> = emptyList(),
    val input: String = "",
    val isLoading: Boolean = false,
    val isDisabled: Boolean = false,
    val errorMessage: String? = null
)
```

This keeps assistant interaction state separate from transaction screen state.

#### Transactions

Recommended Phase 2 updates:

- Keep `TransactionFilter` as the UI drilldown model.
- Extend it only as needed for navigation fidelity, for example ownership or amount range.
- Ensure the `ALL` tab path and paged query path honor the same structured filter fields as non-ALL drilldowns.

### Phase 2 background strategy

#### Use direct coroutines for

- interactive assistant queries
- clarification turns
- deterministic drilldown execution

#### Do not use WorkManager for

- user-submitted assistant turns
- assistant session summarization
- background interpretation of speculative queries

Reason:

- Phase 2 is interactive and latency-sensitive.
- Query answers should be computed on demand from current data, not from stale background cache.

### Phase 2 feature flags

Recommended flags:

- `ai_enabled`
- `ai_assistant_enabled`
- `ai_query_interpretation_enabled`
- `ai_allow_cloud`
- `ai_store_conversation_history`

### Phase 2 AppConfig additions

Add to `AppConfig.Ai` at minimum:

- `PROMPT_VERSION_QUERY`
- `MAX_QUERY_INPUT_CHARS`
- `MAX_QUERY_HISTORY_TURNS_FOR_MODEL`
- `MAX_QUERY_RESULT_ROWS`
- `MAX_QUERY_GROUP_BUCKETS`
- `MAX_QUERY_CLARIFICATION_OPTIONS`

### Phase 2 privacy defaults

Recommended defaults:

- assistant disabled until AI is explicitly enabled
- conversation history disabled by default
- raw query text not persisted unless history storage is enabled
- redaction remains enabled before cloud interpretation when feasible
- no provider raw prompt / response storage outside debug mode
- deterministic result payloads should be minimized when persisted

## Phase 2 Testing Plan

### Unit tests

- `InterpretFinancialQueryUseCaseTest`
- `ExecuteFinancialQueryUseCaseTest`
- `MapFinancialQueryToNavigationUseCaseTest`
- `QueryInterpretationInputBuilderTest`

### Repository and DAO tests

- `AiChatSessionDaoTest`
- `AiChatMessageDaoTest`
- `AiChatRepositoryImplTest`
- transaction filter / repository tests covering the `ALL` tab filtered path

### Migration tests

- `34 -> 35` migration for `ai_chat_sessions` and `ai_chat_messages`

### ViewModel tests

- `AssistantViewModelTest` for disabled, loading, clarification, success, error, and history-off behavior
- `MainViewModel` navigation-event tests for assistant-driven transaction drilldown
- `TransactionsViewModel` tests confirming assistant-applied filters work across non-ALL and ALL flows

### Integration checks

- assistant opens and closes without disturbing existing overlays
- assistant-disabled behavior does not regress baseline app navigation
- query answers use deterministic totals derived from repositories
- navigation results open the expected filtered transaction list
- no assistant query writes to `Expense`, `PendingReview`, or budget tables

## Phase 2 Success Criteria

- app behavior is unchanged when AI is fully disabled
- users can ask supported expense-history questions and receive deterministic answers
- users can open matching filtered transaction lists from assistant results
- assistant history remains optional and clearable
- migration is stable on upgrade and clean install paths
- no regressions appear in notification capture, review approval, or receipt scanning flows

## Recommended PR Slicing Inside Phase 2

Even Phase 2 should be split into smaller targeted changes.

### PR 1: Query foundation slice

- add query domain models
- add chat/session tables and DAOs
- add migration `34 -> 35`
- add repository and DI seams for query interpretation and session storage
- add config constants for query limits and prompt versioning

### PR 2: Deterministic execution slice

- add interpretation input builder and use case
- add deterministic query execution use case
- add mapping from domain intent to navigation result
- align transaction filtering so `ALL` tab drilldown matches non-ALL behavior

### PR 3: Assistant UI slice

- add `AssistantViewModel`
- add assistant sheet / overlay UI
- integrate with `MainActivity.kt` and `MainViewModel.kt`
- add navigation from assistant results into transactions

### PR 4: Hardening slice

- add tests
- add privacy / history safeguards
- add manual QA checklist and debug diagnostics if needed

## Phase 2 Task-by-Task Execution Checklist

This is the execution order for Phase 2.

Do these in sequence. Do not start assistant UI work before the deterministic query contract, storage seams, and migration exist.

### Milestone 1: Query foundation and persistence

#### 1.1 Query domain models

- [ ] Create `app/src/main/java/com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt`
- [ ] Add `ExpenseQueryFilters`, `FinancialQueryIntent`, `FinancialQueryResult`, and query enums
- [ ] Keep the contract UI-agnostic and separate from `TransactionFilter.kt`

Done when:

- AI interpretation and deterministic execution can talk through a shared domain contract without importing Compose or screen types

#### 1.2 Assistant session entities and DAO

- [ ] Create `AiChatSessionEntity` and `AiChatMessageEntity`
- [ ] Create DAOs for create session, append message, load session messages, and clear history
- [ ] Keep structured payload optional via `payloadJson`
- [ ] Do not store raw provider prompt internals by default

Done when:

- the assistant can optionally persist a user-visible transcript in tables isolated from financial data

#### 1.3 Database registration and migration

- [ ] Add new chat entities and DAOs to `AppDatabase.kt`
- [ ] Bump DB version from `34` to `35`
- [ ] Add `MIGRATION_34_35`
- [ ] Register `MIGRATION_34_35` in `DatabaseModule.kt`

Done when:

- existing installs migrate cleanly and clean installs create the new assistant tables correctly

#### 1.4 Assistant repository and history gate

- [ ] Create `AiChatRepository` interface and implementation
- [ ] Respect `storeConversationHistory` when deciding whether to persist messages
- [ ] Keep persistence optional so the assistant still works when history is off

Done when:

- session storage is available without making transcript persistence mandatory

#### 1.5 Query service contract and DI

- [ ] Create `QueryInterpretationService`
- [ ] Add a fake / no-op implementation first for integration
- [ ] Add the cloud-backed implementation behind the same interface
- [ ] Register bindings in `AiModule.kt`

Done when:

- the app can compile and run the assistant shell without hard-wiring a provider implementation

#### 1.6 Config constants

- [ ] Add Phase 2 constants to `AppConfig.Ai`
- [ ] Add prompt version, input-length, history-window, and result-size limits
- [ ] Do not add Phase 3 or Phase 4 constants early

Done when:

- Phase 2 interpretation and execution logic do not hardcode limits inside ViewModels or repositories

#### 1.7 Main-shell navigation scaffolding

- [ ] Evolve `MainViewModel` navigation from tab-only events to richer request types
- [ ] Preserve the existing tab-switch behavior for non-assistant flows
- [ ] Keep `MainActivity.kt` as the shell-level owner of `activeTransactionFilter`

Done when:

- assistant-driven drilldown has a shell-level navigation seam without rewriting the app into a NavHost

### Milestone 2: Deterministic interpretation and execution

#### 2.1 Interpretation input builder

- [ ] Build a mapper that converts raw user text plus app context into a grounded interpretation input
- [ ] Include current date/time, known categories, and recent merchants
- [ ] Do not expose raw database schema or SQL fragments to the model

Done when:

- the interpreter sees enough domain context to resolve common merchants and periods without guessing blindly

#### 2.2 Interpretation use case

- [ ] Create `InterpretFinancialQueryUseCase`
- [ ] Check AI settings before any provider call
- [ ] Return structured intent, clarification, or unsupported output
- [ ] Keep `TransactionFilter.kt` out of this layer

Done when:

- a single use case can safely convert a raw question into an execution-ready domain intent or a clarification request

#### 2.3 Deterministic execution use case

- [ ] Create `ExecuteFinancialQueryUseCase`
- [ ] Run supported queries through repositories and utilities only
- [ ] Use `Expense.effectiveAmount` for all spending totals
- [ ] Support only the approved Phase 2 query families

Done when:

- numeric answers are reproducible from app code alone, independent of provider wording

#### 2.4 Transaction filter alignment

- [ ] Review `TransactionsViewModel.kt`, `TransactionFilter.kt`, and `ExpenseRepository.kt`
- [ ] Extend the paged `ALL` tab query path so it can honor structured drilldown filters
- [ ] Keep manual tab/search/ownership behavior stable
- [ ] Add ownership or amount filter fields to `TransactionFilter.kt` only if needed for navigation fidelity

Done when:

- assistant-driven drilldown and existing drilldown behavior use the same effective filtering rules

#### 2.5 Navigation mapping use case

- [ ] Create `MapFinancialQueryToNavigationUseCase`
- [ ] Convert supported list-style intents into `TransactionFilter`
- [ ] Keep unsupported or ambiguous intents out of navigation

Done when:

- the assistant can open the transactions screen with a faithful deterministic filter instead of ad hoc screen logic

#### 2.6 Clarification and fallback policy

- [ ] Define a standard clarification result for missing or ambiguous periods / merchants / metrics
- [ ] Keep unsupported results explicit and user-readable
- [ ] Do not silently reinterpret unsupported questions into nearby but different intents

Done when:

- Phase 2 fails safely when the user asks something outside the supported query set

### Milestone 3: Assistant UI integration

#### 3.1 Assistant ViewModel

- [ ] Create `AssistantViewModel`
- [ ] Manage input, message list, loading state, disabled state, and retry behavior
- [ ] Keep session state separate from `TransactionsViewModel`

Done when:

- assistant interaction logic is isolated and testable without screen-level Compose plumbing

#### 3.2 Assistant host UI

- [ ] Create `AssistantSheet.kt`
- [ ] Show input field, starter prompts, result cards, clarification chips, and error states
- [ ] Reuse existing app visual patterns instead of introducing a separate design system

Done when:

- users can submit and refine a Phase 2 query from a single global surface

#### 3.3 Main shell integration

- [ ] Update `MainActivity.kt` / `MainScreen` to host the assistant overlay
- [ ] Add a global assistant launcher without adding a new bottom-nav tab
- [ ] Ensure assistant visibility coexists with current add/scan/recurring overlays

Done when:

- the assistant can open from anywhere and close cleanly without destabilizing the app shell

#### 3.4 Transactions navigation hookup

- [ ] Connect assistant result actions to `MainViewModel` navigation events
- [ ] Reuse the existing `activeTransactionFilter` pattern or refine it at the shell boundary
- [ ] Verify the transactions screen applies assistant-provided filters on arrival

Done when:

- assistant drilldown lands users in the same transaction UI they already know, with no duplicate screen implementation

#### 3.5 History policy hookup

- [ ] Persist sessions/messages only when history storage is enabled
- [ ] Add clear-history / clear-session affordances inside the assistant surface
- [ ] Do not block Phase 2 on a full settings screen if consent can be handled inline

Done when:

- privacy-sensitive users can use the assistant without mandatory transcript persistence

### Milestone 4: Hardening and verification

#### 4.1 DAO and repository tests

- [ ] Add `AiChatSessionDaoTest`
- [ ] Add `AiChatMessageDaoTest`
- [ ] Add `AiChatRepositoryImplTest`
- [ ] Add deterministic filter-path tests for the repository / transactions layer

Done when:

- assistant persistence and drilldown correctness are covered by automated tests

#### 4.2 Migration test

- [ ] Extend database migration tests for `34 -> 35`
- [ ] Verify both new assistant tables and any indices / foreign keys exist after migration

Done when:

- the migration is validated by tests rather than assumed from SQL alone

#### 4.3 Use case tests

- [ ] Add `InterpretFinancialQueryUseCaseTest`
- [ ] Add `ExecuteFinancialQueryUseCaseTest`
- [ ] Add `MapFinancialQueryToNavigationUseCaseTest`
- [ ] Cover disabled settings, clarification, unsupported, success, and deterministic comparison paths

Done when:

- Phase 2 orchestration logic is test-covered without needing end-to-end UI tests for core trust boundaries

#### 4.4 ViewModel tests

- [ ] Add `AssistantViewModel` tests for disabled, loading, clarification, success, error, and history-off behavior
- [ ] Add `MainViewModel` navigation tests for assistant-driven drilldown
- [ ] Add `TransactionsViewModel` tests for assistant-applied filters, especially the `ALL` tab path

Done when:

- assistant state transitions and drilldown behavior are stable before manual QA

#### 4.5 Manual QA checklist

- [ ] Verify the assistant is fully non-blocking when AI is disabled
- [ ] Verify numeric answers match deterministic screen data for the same period/filter
- [ ] Verify assistant-driven drilldown lands on the expected transaction list
- [ ] Verify history is not persisted when conversation storage is off
- [ ] Verify no assistant action creates, edits, approves, or deletes financial records

Done when:

- Phase 2 is safe to release behind feature flags and internal opt-in

## Suggested Execution Order Across Phase 2 PRs

Use this order exactly unless a blocker appears:

1. PR 1 tasks 1.1 through 1.7
2. PR 2 tasks 2.1 through 2.6
3. PR 3 tasks 3.1 through 3.5
4. PR 4 tasks 4.1 through 4.5

## What Should Not Happen During Phase 2

- Do not modify `NotificationProcessingPipeline.kt` to call AI directly
- Do not modify `ReceiptRepository.kt` for AI fallback or query routing
- Do not let the model emit SQL, Room queries, or raw repository method names as executable output
- Do not let AI become the source of truth for totals, comparisons, or counts
- Do not widen Phase 2 into budget editing, review approval, or capture assistance
- Do not add automatic apply / mutate actions from assistant results

## Deferred to Later Phases

### Phase 3

- Receipt AI fallback
- Categorization Layer 6 fallback
- Deduplication judge

### Phase 4

- Proactive coaching notifications
- Selective one-tap apply flows
- Carefully guarded semi-automation

## Phase Gates and Entry Criteria

These gates define when it is appropriate to move from one phase to the next.

Recommended planning rule:

- Fully detail the current phase only.
- Keep later phases at outline level until the current phase passes its exit gate.
- After each phase, do a short retro, review technical learnings, then expand the next phase.

This avoids over-planning features that will likely change after real usage.

### Gate 0: Ready to start Phase 1

Phase 1 can begin implementation when all of the following are true:

- Phase 1 scope is frozen to `Home AI briefing + Review AI explanation`
- `ai_artifacts` is confirmed as the only new Room table for Phase 1
- AI settings strategy is frozen
- privacy defaults are frozen: opt-in, redact before cloud, no prompt storage outside debug
- provider abstraction is frozen, even if the first implementation uses fake or no-op providers
- migration and test plan for `33 -> 34` is defined
- explicit agreement exists that `NotificationProcessingPipeline.kt` remains untouched by AI logic in Phase 1

If any of these are still moving, Phase 1 is not ready to implement.

### Gate 1: Ready to plan and enter Phase 2

Phase 2 should not be detailed or started until Phase 1 proves the AI foundation is safe and useful.

Phase 2 can begin detailed planning when all of the following are true:

- Phase 1 is implemented behind feature flags
- AI-disabled behavior is verified to match baseline app behavior
- `ai_artifacts` migration is stable on upgrade paths and clean installs
- Home briefing and Review explanation both fail gracefully with deterministic fallback
- no regressions exist in notification capture or review approval flows
- provider reliability, latency, and cost are understood well enough to support a broader query feature
- opt-in and privacy UX are validated in internal testing
- prompt versioning and artifact invalidation strategy are working in practice
- at least one internal test cycle or beta cycle has been completed and reviewed

Recommended evidence before opening Phase 2:

- zero blocking migration issues
- zero capture-path regressions
- review explanation latency is acceptable for on-demand use
- cached AI artifacts load fast enough to feel local

Why this gate matters:

- Phase 2 expands AI from explanation into interpretation and navigation behavior, so it depends on trust, caching, privacy, and fallback behavior already being proven.

### Gate 2: Ready to plan and enter Phase 3

Phase 3 touches receipt parsing, categorization fallback, and deduplication judgment, so it should only begin after Phase 2 proves the interpretation layer is dependable.

Phase 3 can begin detailed planning when all of the following are true:

- Phase 2 query interpretation is reliably mapping to deterministic filters or result flows
- the app has a stable pattern for showing AI confidence, provenance, and fallback behavior
- worker-based AI execution and cache refresh behavior are stable in production-like conditions
- privacy and redaction policy are proven for larger or messier user inputs
- the team is still aligned that AI remains advisory only for capture-related flows
- human-in-the-loop review surfaces are ready to display capture suggestions without silently applying them
- acceptance criteria for receipt fallback, categorization fallback, and dedupe suggestions are defined before implementation starts

Recommended evidence before opening Phase 3:

- no unresolved UX confusion around what AI is doing vs what deterministic logic is doing
- clear per-capability feature flags and kill switches exist
- no architectural pressure to put AI inline in the synchronous capture path

Why this gate matters:

- Phase 3 is the first phase that touches sensitive parsing and review pipelines, so a weak trust model here would create support and data-quality problems quickly.

### Gate 3: Ready to plan and enter Phase 4

Phase 4 should only begin after AI suggestions have already proven accurate, understandable, and easy to reject.

Phase 4 can begin detailed planning when all of the following are true:

- Phase 3 suggestion quality is measurably strong
- users can clearly apply, dismiss, or ignore AI suggestions without confusion
- feedback loops exist for accepted vs rejected AI output
- every candidate automation flow has deterministic validation after AI output
- per-capability rollback switches exist
- consent and settings UX are strong enough for proactive or semi-automated behavior
- the team has defined exactly which actions remain advisory and which may become one-tap assisted

Recommended evidence before opening Phase 4:

- high-confidence suggestions are rarely overridden by users
- no unresolved privacy objections remain for proactive behavior
- rollback and disable paths are tested end-to-end

Why this gate matters:

- Phase 4 changes the product from “AI explains and suggests” to “AI initiates and influences action,” which is a much higher trust boundary.

## Phase Review Cadence

At the end of each phase, do a short structured review before expanding the next one.

Recommended review checklist:

- What changed in the architecture because of real implementation?
- What failed in provider behavior, caching, privacy, or latency?
- Which assumptions in the next phase are now wrong?
- Which interfaces held up well and which need reshaping?
- What new guardrails are needed before the next trust boundary?

Recommended rule:

- Do not write a detailed task-by-task plan for the next phase until this review is complete.

## Stop Conditions

Pause phase expansion and reassess if any of these occur:

- notification capture performance regresses
- review approval flows become less trustworthy or harder to understand
- provider cost or latency becomes unstable
- migration issues appear in real upgrade scenarios
- privacy or consent behavior is unclear to users
- AI output starts sounding authoritative about numeric facts it does not actually control

## Implementation Workflow

Use this workflow for every implementation slice.

The goal is to keep work grounded in the real codebase, reduce scope drift, and avoid speculative changes or hallucinated architecture.

### Operating unit of work

Always work in this order:

- Phase
- PR slice
- Task IDs

Do not implement an entire phase in one pass unless the phase is explicitly split into only one PR.

### Standard workflow for each slice

#### 1. Freeze the slice

Before implementation starts, define:

- current phase
- current PR
- exact tasks being executed
- in-scope items
- out-of-scope items
- files that must not be touched
- verification requirements
- stop boundary

#### 2. Ground the work in the repo

Before changing code:

- re-read the relevant section in this document
- inspect the actual files that the slice is expected to touch
- confirm existing patterns before introducing new structure

Rule:

- codebase reality wins over planning assumptions

If the repo differs from the plan, implementation should follow the repo and then update the plan if needed.

#### 3. Restate scope before coding

Before making edits, restate:

- what will be changed
- what will not be changed
- which files are expected to change
- what would count as an unexpected dependency or blocker

This acts as a final guard against scope drift.

#### 4. Implement only the approved slice

While coding:

- do not pull future-phase work into the current slice
- do not add speculative abstractions unless they are required by the current slice
- do not widen DB or UI scope without a clear current-phase need
- do not silently change deterministic behavior

#### 5. Verify the slice

After implementation:

- run only the relevant tests or build checks for that slice
- verify migrations if the slice touches Room
- verify fallback behavior if the slice touches AI surfaces
- verify untouched critical paths still behave the same

#### 6. Report and stop at the boundary

When the slice is done:

- list changed files
- state what passed and what was not run
- call out blockers, risks, or unexpected repo realities
- stop at the approved boundary instead of automatically continuing

### Anti-hallucination rules

These rules apply to all AI feature implementation work:

- Do not touch files outside the approved slice unless required for compilation or wiring.
- Do not add future-phase schema changes early.
- Do not invent new architecture if an existing repository/use-case/worker pattern already fits.
- Do not put AI inside `NotificationProcessingPipeline.kt`.
- Do not change approval, capture, or financial write behavior unless the current task explicitly requires it.
- Do not treat AI output as authoritative numeric truth.
- If implementation reality conflicts with the document, report the conflict explicitly.

### Recommended prompt format for future implementation requests

Use this structure when requesting implementation work for a slice:

```text
Implement slice:
Phase: <phase>
PR: <pr>
Tasks: <task ids>

Goal:
<short goal>

In scope:
- ...

Out of scope:
- ...

Must not touch:
- ...

Verification:
- ...

Stop after:
- ...
```

### Example

```text
Implement slice:
Phase: 1
PR: 1
Tasks: 1.1-1.4

Goal:
Add AI foundation only

In scope:
- domain AI models
- AiArtifact entity/dao
- DB migration 33->34
- DI registration

Out of scope:
- Home UI
- Review UI
- workers
- provider networking

Must not touch:
- NotificationProcessingPipeline.kt
- ReceiptRepository.kt
- ReviewScreen.kt

Verification:
- compile
- migration test
- DAO test

Stop after:
- report changed files
- report anything that blocks next tasks
```

### Default rule for this project

The preferred implementation rhythm is:

- plan Phase 1 in detail
- implement one PR slice at a time
- verify the slice
- review learnings
- then move to the next slice

## Non-Negotiables

- AI must never replace authoritative financial math.
- AI must never directly mutate expense records without deterministic validation and explicit user confirmation.
- Cloud AI must never be inserted into the synchronous notification pipeline.
- AI should be removable without corrupting financial history.

## Recommended Defaults for Open Decisions

If we proceed with Phase 1, these are the recommended defaults:

1. Provider strategy: cloud-backed for Phase 1, but behind a provider abstraction and feature flag.
2. Home UI placement: reuse the existing `NaturalLanguageInsight` slot instead of adding a new widget.
3. Review explanation trigger: on-demand and cached, not precomputed for every row.
4. Storage model: `ai_artifacts` only in Phase 1, no chat/session tables yet.
5. Privacy mode: opt-in, redact before cloud, no prompt persistence outside debug.

## Immediate Next Planning Step

Freeze the Phase 1 scope before coding:

- Home AI briefing
- Review AI explanation
- Foundation infrastructure required to support those two only

Once that is approved, implementation should start with PR 1 from the Phase 1 PR slicing plan.
