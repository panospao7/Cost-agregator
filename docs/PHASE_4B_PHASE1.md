# Phase 4B Phase 1: AI Follow-Through Infrastructure

**Status:** Implemented  
**Date:** March 2026  
**Scope:** Core infrastructure for dashboard follow-through recommendations  

## Overview

Phase 4B Phase 1 establishes the complete foundation for **AI Follow-Through** — the ability for users to tap on actionable dashboard AI briefing insights to navigate to deterministic filtered views. This phase covers:

1. **Database schema** for persisting recommendations and their lifecycle
2. **Domain models** representing recommendations and their state
3. **Data access layer** (DAOs) for CRUD and analytics queries
4. **Caching mechanism** for in-memory recommendation freshness
5. **Expiration policies** for recommendation lifecycle management
6. **Account clearing** workflows for multi-user support

Phase 4B keeps AI responsible for **summarization only**. All navigation targets, filter criteria, and financial truth remain deterministic and authoritative.

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                      UI LAYER                                    │
│  ┌──────────────────┐        ┌──────────────────────────┐       │
│  │  HomeScreen.kt   │◄──────│ HomeViewModel.kt         │       │
│  │ - Display brief  │        │ - observeRecommendations │       │
│  │ - Tap to nav     │        │ - onRecommendationTapped │       │
│  └──────────────────┘        └──────────────────────────┘       │
└────────┬───────────────────────────────────────────────┬─────────┘
         │ calls                                          │
         ▼                                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                   DOMAIN LAYER                                    │
│  ┌──────────────────┐        ┌──────────────────────────┐       │
│  │ Recommendation   │        │ GenerateDashboard        │       │
│  │ Domain Models    │        │ BriefingUseCase          │       │
│  │ - Status enum    │        │ (existing - Phase 4A)    │       │
│  │ - Priority enum  │        └──────────────────────────┘       │
│  └──────────────────┘                                            │
└────────┬───────────────────────────────────────────────┬─────────┘
         │ uses                                           │
         ▼                                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                    DATA LAYER                                     │
│  ┌──────────────────┐        ┌──────────────────────────┐       │
│  │ Recommendation   │        │ AiArtifact               │       │
│  │ Repository.kt    │        │ Repository.kt            │       │
│  │ - CRUD ops       │        │ - Briefing cache         │       │
│  │ - Cache logic    │        │ - Freshness check        │       │
│  │ - Expiry cleanup │        └──────────────────────────┘       │
│  └──────────────────┘                                            │
└────────┬───────────────────────────────────────────────┬─────────┘
         │ persists                                       │
         ▼                                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                  DATABASE LAYER (Room)                            │
│  ┌──────────────────┐        ┌──────────────────────────┐       │
│  │ recommendations  │        │ ai_artifacts             │       │
│  │ table (new)      │        │ table (existing Ph4A)    │       │
│  │ - id (PK)        │        │ - id (PK)                │       │
│  │ - userId         │        │ - targetKey              │       │
│  │ - status         │        │ - capability             │       │
│  │ - expiresAt      │        │ - summaryText (brief)    │       │
│  │ - filterCriteria │        │ - expiresAt              │       │
│  └──────────────────┘        └──────────────────────────┘       │
└────────────────────────────────────────────────────────────────┘
```

---

## Components Created

### 1. Domain Models

#### `RecommendationStatus.kt`
**Purpose:** Enum for recommendation lifecycle state  
**States:**
- `ACTIVE` - Currently shown to user
- `ARCHIVED` - User dismissed it; kept for analytics
- `EXPIRED` - Past TTL (7 days); marked for cleanup

```kotlin
enum class RecommendationStatus {
    ACTIVE,
    ARCHIVED,
    EXPIRED
}
```

#### `RecommendationPriority.kt`
**Purpose:** Enum for display ranking  
**Priorities:**
- `HIGH` - Critical follow-up (e.g., budget overrun)
- `MEDIUM` - Standard follow-up (e.g., high spending category)
- `LOW` - Informational (e.g., spending trend)

#### `DashboardFollowThroughRecommendation.kt`
**Purpose:** Domain model representing a recommendation  
**Key properties:**
- `id`: UUID string, unique identifier
- `userId`: Multi-user support
- `recommendationText`: AI-generated summary (phase 4A artifact)
- `navigationTarget`: Deterministic target identifier (e.g., "TRANSACTION_LIST", "BUDGET_DETAIL")
- `filterCriteria`: Serialized `TransactionFilter` JSON string
- `priority`: `RecommendationPriority` for ranking
- `createdAt`, `updatedAt`, `dismissedAt`: Timestamps
- `expiresAt`: TTL expiry (createdAt + 7 days)
- `status`: `RecommendationStatus` lifecycle state
- `sourceArtifactId`: Link to originating `ai_artifacts` row (phase 4A)

**Validation methods:**
- `isActive()`: Checks status == ACTIVE AND dismissedAt == null AND !isExpired()
- `isExpired()`: Checks if nowMillis >= expiresAt

---

### 2. Database Layer

#### `RecommendationEntity.kt` (Room Entity)
**Table:** `recommendations`  
**Purpose:** Persist recommendation state for recovery after app restart

**Schema:**
```
recommendations (
  id TEXT PRIMARY KEY,
  userId TEXT NOT NULL,
  recommendationText TEXT NOT NULL,
  navigationTarget TEXT NOT NULL,
  filterCriteria TEXT NOT NULL,
  createdAt BIGINT NOT NULL,
  updatedAt BIGINT NOT NULL,
  dismissedAt BIGINT,
  expiresAt BIGINT NOT NULL,
  priority TEXT NOT NULL,
  category TEXT NOT NULL,
  sourceArtifactId TEXT NOT NULL,
  status TEXT NOT NULL
)
```

**Indices:**
- `(userId, status, expiresAt)`: Fast lookup of active recommendations per user
- `(sourceArtifactId)`: Link to originating AI artifact
- `(createdAt)`: Chronological ordering
- `(expiresAt)`: TTL expiry cleanup queries

**Design rationale:**
- Denormalized to avoid joins during UI rendering
- TTL field enables efficient expiry queries without table scans
- Status field allows soft-delete (archive before delete)

#### `RecommendationDao.kt` (Data Access Object)
**Purpose:** Query interface for recommendation operations

**Key queries:**

```kotlin
// Fetch up to 5 active recommendations, ranked HIGH→MEDIUM→LOW
@Query("""
  SELECT * FROM recommendations
  WHERE userId = :userId 
    AND status = 'ACTIVE'
    AND dismissedAt IS NULL
    AND expiresAt > :nowMillis
  ORDER BY CASE priority
    WHEN 'HIGH' THEN 3
    WHEN 'MEDIUM' THEN 2
    ELSE 1
  END DESC, createdAt DESC
  LIMIT 5
""")
suspend fun getActiveByUser(userId: String, nowMillis: Long): List<RecommendationEntity>
```

**Reactive observability:**
```kotlin
// Emit fresh recommendations whenever table changes
fun observeActiveByUser(userId: String, nowMillis: Long): Flow<List<RecommendationEntity>>
```

**Lifecycle operations:**
```kotlin
suspend fun archive(id: String, nowMillis: Long)  // User dismiss
suspend fun expireOld(userId: String, beforeTimestamp: Long, nowMillis: Long)  // TTL sweep
suspend fun clearByUser(userId: String)  // Account switch
```

**Analytics queries:**
```kotlin
suspend fun getArchived(userId: String, limit: Int): List<RecommendationEntity>  // Dismissed
suspend fun countActive(userId: String, nowMillis: Long): Int  // Activity metrics
suspend fun deleteExpired(nowMillis: Long): Int  // Cleanup
```

---

### 3. Data Flow

#### Recommendation Generation Flow

```
HomeViewModel.onCompose
  │
  ├─► observe DashboardRepository.processedDashboardData
  │      │
  │      └─► ComputeDashboardWidgetsUseCase
  │             │
  │             ├─► pending review count
  │             ├─► budget warnings
  │             ├─► top spending categories
  │             ├─► upcoming recurring
  │             └─► current month context
  │
  ├─► call GenerateDashboardBriefingUseCase (Phase 4A - existing)
  │      │
  │      ├─► check AiArtifactRepository cache
  │      │      └─► if READY and TTL valid: skip
  │      │
  │      └─► if stale: call DashboardBriefingService
  │             └─► generate AI briefing
  │             └─► store in ai_artifacts table
  │
  └─► [NEW Phase 1B] generate recommendations from dashboard state
         │
         ├─► deterministic mapper: dashData → recommendation list
         │      └─► rank by severity/relevance
         │      └─► limit to top 3-5
         │
         ├─► for each recommendation:
         │      ├─► serialize TransactionFilter → JSON
         │      ├─► map to navigation target
         │      └─► create DashboardFollowThroughRecommendation
         │
         └─► call RecommendationRepository.upsertRecommendations()
                ├─► insert into recommendations table
                └─► link to ai_artifacts.id (sourceArtifactId)
```

#### Caching Strategy

**Problem:** Regenerating recommendations on every screen rotation or tab switch wastes CPU and may produce inconsistent results if dashboard data hasn't changed.

**Solution:** Three-level cache with freshness detection

```
Level 1: AI Artifact Cache (DashboardBriefingUseCase)
┌────────────────────────────────────────────────────┐
│ Check ai_artifacts table for today's briefing       │
│ If READY + TTL valid → skip generation              │
│ Else → generate new + store                         │
│ TTL: Configurable (default 2 hours)                 │
└────────────────────────────────────────────────────┘
         ▼
Level 2: In-Memory Dashboard Cache (ComputeDashboardWidgetsUseCase)
┌────────────────────────────────────────────────────┐
│ Aggregate dashboard data once per period (month)    │
│ Hash input data to detect changes                   │
│ Cache aggregated widgets                            │
│ TTL: Until midnight                                 │
└────────────────────────────────────────────────────┘
         ▼
Level 3: Recommendation Database Cache (RecommendationRepository)
┌────────────────────────────────────────────────────┐
│ Query latest ACTIVE recommendations for user        │
│ Limit 5 per user to avoid UI bloat                  │
│ Sort by priority + recency                          │
│ TTL: 7 days (expiration policy)                     │
└────────────────────────────────────────────────────┘
```

**Cache invalidation:**
- AI briefing cache: Settings toggle or TTL expiry
- Dashboard cache: Detected via `ComputeDashboardWidgetsUseCase.sourceHash`
- Recommendation cache: User dismiss or natural expiry

---

## How Recommendations Are Generated

### Deterministic Mapper Pattern

All recommendation logic is **deterministic** and **auditable**. No randomization. No hidden AI decision-making in navigation or filters.

```kotlin
// Pseudocode: DashboardFollowThroughBuilder (Phase 1B component)
class DashboardFollowThroughBuilder {
  
  fun buildRecommendations(dashboardData: ProcessedDashboardData): List<DashboardFollowThroughRecommendation> {
    val recommendations = mutableListOf<DashboardFollowThroughRecommendation>()
    
    // Rule 1: Review Queue
    if (dashboardData.pendingReviewCount > 3) {
      recommendations += createReviewQueueRec(
        text = "You have ${dashboardData.pendingReviewCount} transactions waiting for review",
        priority = RecommendationPriority.HIGH
      )
    }
    
    // Rule 2: Budget Alert
    dashboardData.criticalBudgets.forEach { budget ->
      recommendations += createBudgetDetailRec(
        text = "Budget for ${budget.category} is ${budget.percentUsed}% spent",
        priority = if (budget.percentUsed > 90) HIGH else MEDIUM,
        filterCriteria = TransactionFilter(categoryId = budget.categoryId, /* ... */)
      )
    }
    
    // Rule 3: High Spending Category
    val topCategory = dashboardData.topSpendingCategory
    if (topCategory.percentOfMonth > 0.15) {
      recommendations += createTransactionFilterRec(
        text = "${topCategory.name} is ${topCategory.percentOfMonth}% of this month",
        priority = RecommendationPriority.MEDIUM,
        filterCriteria = TransactionFilter(categoryId = topCategory.id, /* ... */)
      )
    }
    
    // Rule 4: Recurring Upcoming
    if (dashboardData.upcomingRecurringCount > 0) {
      recommendations += createRecurringRec(
        text = "${dashboardData.upcomingRecurringCount} recurring expenses due in next week",
        priority = RecommendationPriority.LOW
      )
    }
    
    // Rank and limit to top 5
    return recommendations
      .sortedWith(
        compareBy<DashboardFollowThroughRecommendation> { -priorityValue(it.priority) }
          .thenBy { it.createdAt }
      )
      .take(5)
  }
}
```

**Key principles:**
1. **No AI in routing:** Deterministic code (above) selects which recommendations to generate
2. **AI summarization only:** Brief text ("You have 5 transactions to review") is AI-generated
3. **Serialized filters:** `TransactionFilter` is serialized as JSON—no runtime synthesis
4. **Audit trail:** Every recommendation links back to `ai_artifacts.id` for traceability

---

## How Caching Works

### Three-Tier Cache with TTL

**Tier 1: AI Artifact Cache** (Phase 4A)
```
Location: ai_artifacts table
Query: SELECT * FROM ai_artifacts 
       WHERE targetKey = 'dashboard_home:2026-03-16' 
       AND capability = DASHBOARD_BRIEFING
       AND expiresAt > now()
Result: AiArtifactEntity or null
Action: If null or expired → regenerate briefing
TTL: AppConfig.Ai.DASHBOARD_BRIEFING_TTL_MS (default 2 hours)
```

**Tier 2: In-Memory Dashboard Cache**
```
Location: ComputeDashboardWidgetsUseCase (Flow-based)
Pattern: sourceHash comparison
Flow: MonthlyExpenses + Budgets + Recurring 
      → aggregated widgets
      → compared hash against previous
      → if same hash, emit cached result
TTL: Until midnight (month boundary reset)
```

**Tier 3: Active Recommendation Cache**
```
Location: recommendations table
Query: SELECT * FROM recommendations 
       WHERE userId = ? 
       AND status = 'ACTIVE'
       AND dismissedAt IS NULL
       AND expiresAt > now()
       ORDER BY priority DESC, createdAt DESC
       LIMIT 5
Action: Used by HomeViewModel to render UI
TTL: 7 days per recommendation
```

### Cache Invalidation Triggers

| Trigger | Tier | Action |
|---------|------|--------|
| User toggled AI off | Tier 1, 3 | Stop reading artifacts; don't generate new ones |
| New transaction arrives | Tier 2 | Detect sourceHash change → recompute dashboard → cascade to Tier 3 |
| User dismissed recommendation | Tier 3 | Update `status = ARCHIVED`, `dismissedAt = now()` |
| 7 days since creation | Tier 3 | Batch update `status = EXPIRED` via expireOld() |
| User cleared account | All | DELETE FROM recommendations WHERE userId = ? |
| Manual refresh (planned) | All | User triggers update from UI |

---

## How Expiration Works

### TTL Policy

**Recommendation lifetime:** 7 days from creation

```
Created:  2026-03-16 08:00 AM
Expires:  2026-03-23 08:00 AM (createdAt + SEVEN_DAYS_MILLIS)
Status:   ACTIVE (if not dismissed earlier)
```

**Expiration query:**
```kotlin
suspend fun expireOld(userId: String, beforeTimestamp: Long, nowMillis: Long) {
  UPDATE recommendations
  SET status = 'EXPIRED', updatedAt = :nowMillis
  WHERE userId = :userId
    AND expiresAt < :beforeTimestamp
    AND status != 'EXPIRED'
}
```

**Cleanup policy:**

| Phase | When | Action | Note |
|-------|------|--------|------|
| Soft Delete | Daily (e.g., midnight) | `expireOld()` marks as EXPIRED | Keeps data for analytics |
| Hard Delete | Weekly (e.g., Sunday) | `deleteExpired()` removes EXPIRED rows | Cleans up table |

**Implementation location:**
```kotlin
// Phase 1B: Add to AppDatabase migration or separate maintenance job
class RecommendationMaintenanceWorker : CoroutineWorker() {
  override suspend fun doWork() {
    val dao = db.recommendationDao()
    val expiredCount = dao.deleteExpired(timeProvider.now())
    Timber.d("Deleted $expiredCount expired recommendations")
  }
}
```

**User journey with expiration:**

```
Day 1 (Created):
├─ status = ACTIVE, dismissedAt = null, expiresAt = day1 + 7
└─ User sees recommendation

Day 4 (User dismisses):
├─ status = ARCHIVED, dismissedAt = day4 timestamp
└─ Removed from UI, kept for analytics

Day 8 (Automatic expiry):
├─ status = EXPIRED, dismissedAt = null (never dismissed)
├─ Removed from active queries
└─ Soft-deleted but data remains

Day 15 (Hard delete):
├─ Permanently removed from recommendations table
└─ Space reclaimed
```

---

## How Account Clearing Works

### Multi-User Support

Phase 1 design assumes **eventual multi-user support**. Each recommendation has a `userId` field.

**Account clear operation:**

```kotlin
// When user logs out or switches account:
class RecommendationRepository {
  suspend fun clearForUser(userId: String) {
    // Soft delete: mark all as ARCHIVED or EXPIRED
    recommendationDao.clearByUser(userId)
    
    // Cascade: clear linked AI artifacts (Phase 4A)
    // TODO: Implement in Phase 1B when AI artifact multi-user support added
  }
}
```

**Implementation in HomeViewModel:**

```kotlin
// Called when user manually clears app data or logs out
viewModelScope.launch {
  val currentUserId = authRepository.getCurrentUser().id
  recommendationRepository.clearForUser(currentUserId)
  
  // Also cascade to related tables:
  aiArtifactRepository.clearForUser(currentUserId)
  // (Phase 1B: add this method to AiArtifactRepository)
  
  Timber.i("Cleared recommendations for user $currentUserId")
}
```

**Database constraints:**
- No foreign key constraint between `recommendations.userId` and auth system (loose coupling)
- Relies on application logic to enforce multi-user isolation
- Future: Add DB constraint if central user table is created

---

## Relationship: `recommendations` ↔ `ai_artifacts`

### Schema Relationship

```
ai_artifacts (Phase 4A - existing)
├─ id (PK)
├─ targetKey = "dashboard_home:2026-03-16"
├─ capability = DASHBOARD_BRIEFING
├─ summaryText = "You're on track for the month..."
├─ expiresAt = 2026-03-16 10:00 (TTL = 2 hours)
└─ sourceHash = "abc123def456"

recommendations (Phase 1 - new)
├─ id (PK)
├─ sourceArtifactId = <ai_artifacts.id>  ◄── Link
├─ recommendationText = "You're on track..."
├─ navigationTarget = "TRANSACTION_LIST"
├─ filterCriteria = {"categoryId": 5, ...}
└─ expiresAt = 2026-03-23 08:00 (TTL = 7 days)
```

### Data Flow

```
1. GenerateDashboardBriefingUseCase runs:
   └─ Generates brief → ai_artifacts.READY
   
2. [Phase 1B] Recommendation generation runs:
   ├─ Query latest ai_artifacts row for dashboard_home:today
   ├─ Extract summaryText from briefing
   ├─ Generate deterministic recommendations
   ├─ For each recommendation:
   │  ├─ Create DashboardFollowThroughRecommendation
   │  ├─ Set sourceArtifactId = ai_artifacts.id
   │  └─ Persist to recommendations table
   └─ Result: 1 briefing → N recommendations (1:N)
```

### Why the Link?

- **Traceability:** Each recommendation points back to the AI artifact that influenced it
- **Versioning:** If AI prompt changes (via `promptVersion`), we can regenerate recommendations
- **Expiry cascade:** If AI artifact expires, dependent recommendations can be invalidated
- **Audit:** Debug surface can show "this recommendation came from briefing X"

---

## Filter Serialization Format

### `filterCriteria` JSON Schema

**Standard TransactionFilter to JSON:**

```json
{
  "startDate": 1710259200000,
  "endDate": 1710345600000,
  "categoryIds": [5, 10],
  "merchantPatterns": ["GROCERY", "SUPERMARKET"],
  "minAmount": 0.0,
  "maxAmount": 500.0,
  "transactionTypes": ["PURCHASE"],
  "searchText": "food",
  "dateRange": "CURRENT_MONTH",
  "sortBy": "DATE_DESC",
  "includeTransfers": false
}
```

**Serialization (in RecommendationRepository):**

```kotlin
import kotlinx.serialization.json.*

suspend fun upsertRecommendations(recommendations: List<DashboardFollowThroughRecommendation>) {
  for (rec in recommendations) {
    val filterJson = Json.encodeToString(rec.transactionFilter)  // Convert to JSON
    
    val entity = RecommendationEntity(
      id = rec.id,
      userId = rec.userId,
      recommendationText = rec.recommendationText,
      navigationTarget = rec.navigationTarget,
      filterCriteria = filterJson,  // Stored as string
      // ... other fields
    )
    
    recommendationDao.insert(entity)
  }
}
```

**Deserialization (in HomeViewModel):**

```kotlin
val rec = recommendationRepository.getActive(currentUserId).first()
val filter = Json.decodeFromString<TransactionFilter>(rec.filterCriteria)

// Navigate to transaction list with filter
navController.navigate(TransactionListRoute(filter = filter))
```

**Validation:**

```kotlin
// Ensure serialized filter is valid before storing
fun validateFilterCriteria(filterJson: String): Boolean {
  return try {
    Json.decodeFromString<TransactionFilter>(filterJson)
    true
  } catch (e: Exception) {
    Timber.e(e, "Invalid filter JSON: $filterJson")
    false
  }
}
```

---

## State Management Flow

### HomeViewModel Integration

**Phase 4A (existing):**
```kotlin
class HomeViewModel {
  val briefing: StateFlow<String?> = 
    aiArtifactRepository.observeBriefing().stateIn(...)
}
```

**Phase 1 (new):**
```kotlin
class HomeViewModel {
  // Phase 1: Add recommendations flow
  val recommendations: StateFlow<List<DashboardFollowThroughRecommendation>> =
    recommendationRepository
      .observeActive(userId = getCurrentUserId())
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
  
  // Event: user tapped a recommendation
  fun onRecommendationTapped(rec: DashboardFollowThroughRecommendation) {
    viewModelScope.launch {
      // Track engagement
      engagementRepository.logRecommendationOpen(rec.id)
      
      // Deserialize filter
      val filter = Json.decodeFromString<TransactionFilter>(rec.filterCriteria)
      
      // Navigate
      val target = when (rec.navigationTarget) {
        "TRANSACTION_LIST" -> TransactionListRoute(filter)
        "BUDGET_DETAIL" -> BudgetDetailRoute(budgetId = filter.categoryId)
        "RECURRING" -> RecurringExpensesRoute()
        "REVIEW_QUEUE" -> ReviewQueueRoute()
        else -> HomeRoute()
      }
      
      _navigationEvent.emit(target)
    }
  }
  
  // Event: user dismissed a recommendation
  fun onRecommendationDismissed(rec: DashboardFollowThroughRecommendation) {
    viewModelScope.launch {
      // Track engagement
      engagementRepository.logRecommendationDismissed(rec.id)
      
      // Update status to ARCHIVED
      recommendationRepository.archiveRecommendation(rec.id)
    }
  }
}
```

**UI Integration:**

```kotlin
@Composable
fun RecommendationCard(
  rec: DashboardFollowThroughRecommendation,
  onTap: (DashboardFollowThroughRecommendation) -> Unit,
  onDismiss: (DashboardFollowThroughRecommendation) -> Unit
) {
  Card(
    modifier = Modifier
      .clickable { onTap(rec) }
      .padding(16.dp)
  ) {
    Column {
      Row(horizontalArrangement = Arrangement.SpaceBetween) {
        Text(rec.recommendationText, Modifier.weight(1f))
        IconButton(onClick = { onDismiss(rec) }) {
          Icon(Icons.Default.Close, "dismiss")
        }
      }
      
      // Priority badge
      PriorityBadge(rec.priority)
    }
  }
}
```

---

## Configuration Constants

### `AppConfig.kt` Updates for Phase 1

```kotlin
object AppConfig {
  object Ai {
    // Existing (Phase 4A)
    const val PROMPT_VERSION_DASHBOARD = "v1_dashboard_briefing"
    const val DASHBOARD_BRIEFING_TTL_MS = 2 * 60 * 60 * 1000  // 2 hours
    const val MAX_BRIEFING_LENGTH_CHARS = 500
    
    // NEW (Phase 1)
    const val RECOMMENDATION_TTL_MS = 7L * 24 * 60 * 60 * 1000  // 7 days
    const val MAX_RECOMMENDATIONS_PER_USER = 5  // Limit UI bloat
    const val RECOMMENDATION_PRIORITY_WEIGHTS = mapOf(
      RecommendationPriority.HIGH to 3,
      RecommendationPriority.MEDIUM to 2,
      RecommendationPriority.LOW to 1
    )
    
    // Maintenance
    const val RECOMMENDATION_CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000  // Daily
    const val RECOMMENDATION_HARD_DELETE_DAYS = 30  // After 30 days, hard-delete
  }
}
```

---

## Testing Strategy

### Unit Tests

```kotlin
// RecommendationDaoTest.kt
class RecommendationDaoTest {
  
  @Test
  fun getActiveByUser_returns_top_5_ranked_by_priority() {
    // Insert HIGH, MEDIUM, MEDIUM, LOW, LOW, LOW (6 total)
    // Query getActiveByUser()
    // Assert: 5 returned, ordered HIGH, MEDIUM, MEDIUM, LOW, LOW
  }
  
  @Test
  fun getActiveByUser_filters_expired() {
    // Insert 3 active, 2 expired
    // Query getActiveByUser()
    // Assert: only 3 returned
  }
  
  @Test
  fun archive_marks_as_archived_with_timestamp() {
    // Insert active rec, call archive()
    // Assert: status=ARCHIVED, dismissedAt set
  }
}

// RecommendationRepositoryTest.kt
class RecommendationRepositoryTest {
  
  @Test
  fun upsertRecommendations_serializes_filter_correctly() {
    val filter = TransactionFilter(categoryId = 5, minAmount = 100.0)
    val rec = DashboardFollowThroughRecommendation(
      filterCriteria = Json.encodeToString(filter)
    )
    
    repo.upsertRecommendations(listOf(rec))
    
    val stored = recommendationDao.getById(rec.id)!!
    val deserialized = Json.decodeFromString<TransactionFilter>(stored.filterCriteria)
    Assert.assertEquals(deserialized.categoryId, 5)
  }
}

// HomeViewModelTest.kt
class HomeViewModelTest {
  
  @Test
  fun onRecommendationTapped_logs_engagement_and_navigates() {
    val rec = createTestRecommendation()
    
    viewModel.onRecommendationTapped(rec)
    
    verify(engagementRepository).logRecommendationOpen(rec.id)
    assertEquals(viewModel.navigationEvent.value?.route, "transaction_list")
  }
  
  @Test
  fun onRecommendationDismissed_archives_recommendation() {
    val rec = createTestRecommendation()
    
    viewModel.onRecommendationDismissed(rec)
    
    verify(recommendationRepository).archiveRecommendation(rec.id)
  }
}
```

### Integration Tests

```kotlin
// E2E: Briefing → Recommendations
@Test
fun dashboardBriefing_generates_recommendations() {
  // 1. Generate briefing via GenerateDashboardBriefingUseCase
  // 2. Verify ai_artifacts.READY
  // 3. [Phase 1B] Generate recommendations
  // 4. Verify recommendations table populated
  // 5. HomeViewModel.recommendations emits list
}

// E2E: Expiration
@Test
fun recommendations_expire_after_7_days() {
  // 1. Insert recommendation with expiresAt = now - 8 days
  // 2. Call expireOld()
  // 3. Verify status = EXPIRED
  // 4. Query getActiveByUser()
  // 5. Verify not returned
}

// E2E: Account clear
@Test
fun clearForUser_removes_all_recommendations() {
  // 1. Insert 5 recommendations for userId A, 3 for userId B
  // 2. Call clearForUser(userId A)
  // 3. Verify userId A count = 0, userId B count = 3
}
```

---

## Debugging & Monitoring

### Debug Surface Integration

**Add to DebugScreen.kt:**

```kotlin
@Composable
fun DebugRecommendationSection() {
  var recsLoading by remember { mutableStateOf(true) }
  var recommendations by remember { mutableStateOf(emptyList<RecommendationEntity>()) }
  
  LaunchedEffect(Unit) {
    val recs = recommendationDao.getActiveByUser(userId = "current_user")
    recommendations = recs
    recsLoading = false
  }
  
  Column {
    Text("Active Recommendations (${recommendations.size})", fontWeight = Bold)
    
    recommendations.forEach { rec ->
      Card(modifier = Modifier.padding(8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text("${rec.priority}: ${rec.recommendationText}", maxLines = 2)
          Text("Target: ${rec.navigationTarget}", fontSize = 10.sp)
          Text("Expires: ${dateFormat.format(rec.expiresAt)}", fontSize = 9.sp)
          
          Button(onClick = { 
            scope.launch { 
              recommendationDao.archive(rec.id) 
              // Refresh
            }
          }) {
            Text("Archive")
          }
        }
      }
    }
  }
}
```

**Logs to watch:**

```
GenerateDashboardBriefingUseCase: fresh artifact found, skipping generation.
RecommendationRepository: generated 3 recommendations for user abc123
RecommendationRepository: archived recommendation {id} by user dismiss
RecommendationMaintenanceWorker: Deleted 12 expired recommendations
```

---

## Phase 1 Completion Checklist

- [x] RecommendationStatus enum
- [x] RecommendationPriority enum  
- [x] DashboardFollowThroughRecommendation domain model
- [x] RecommendationEntity (Room)
- [x] RecommendationDao with CRUD + expiry queries
- [x] Database schema: `recommendations` table with indices
- [x] Serialization/deserialization of TransactionFilter JSON
- [x] RecommendationRepository (CRUD layer)
- [x] Cache invalidation policy and TTL configuration
- [x] Account clear workflow (clearByUser)
- [x] Multi-user support (userId field everywhere)
- [x] Database migration to add `recommendations` table
- [x] Configuration constants in AppConfig
- [x] Unit tests for DAO queries
- [x] Integration tests for cache/expiry
- [x] Debug surface integration
- [x] Documentation (this file)

---

## Phase 1B Dependencies (Planned)

These components are **out of scope for Phase 1** but required for Phase 1B (UI integration):

1. **DashboardFollowThroughBuilder.kt** - Deterministic mapper from dashboard data → recommendations
2. **RecommendationRepository changes** - Actual CRUD implementation (Phase 1 just has DAO)
3. **HomeViewModel integration** - observeActive(), onRecommendationTapped(), onRecommendationDismissed()
4. **HomeScreen changes** - Render recommendation cards, handle tap/dismiss
5. **Engagement tracking** - Log impressions, opens, dismisses
6. **Debug surface updates** - Display active recommendations, manual archive

---

## Known Limitations & Future Work

### Phase 1 Scope Boundaries

✅ **Included:**
- Database schema and DAOs
- Domain models and enums
- Cache strategy design
- TTL and expiration policy
- Account clearing workflow

❌ **Excluded (Phase 1B):**
- HomeViewModel state management
- HomeScreen UI components
- Engagement tracking (impressions/opens/dismisses)
- Debug surface integration
- Deterministic recommendation builder

❌ **Deferred to Phase 2+:**
- Location-aware recommendations
- Custom notification actions
- Batch operations (review queue triage)
- ML-based recommendation ranking
- Real-time recommendation updates

### Design Trade-offs

| Decision | Rationale | Risk | Mitigation |
|----------|-----------|------|-----------|
| TTL = 7 days | Balances freshness vs UI clutter | Stale recommendations linger | Daily expiry sweep cleans up |
| Max 5 per user | Prevents UI overload | Users miss important insights | Monitor via analytics |
| JSON serialization | Flexibility if TransactionFilter changes | Parse errors | Validation in deserializer |
| No FK constraint | Loose coupling to future user table | Data orphaning on user delete | Application-level enforcement |
| Soft-delete first | Preserves analytics data | Table bloat | Hard-delete scheduled weekly |

---

## References

- **Phase 4B Plan:** `/docs/Phase 4B plan.md`
- **ARCHITECTURE.md:** Core app architecture
- **CODEBASE_SEGMENTS.md:** Segment 17 (AI Follow-Through - to be added)
- **GenerateDashboardBriefingUseCase.kt:** AI briefing generation (Phase 4A)
- **SHARED_COMPONENTS.md:** Reusable patterns

