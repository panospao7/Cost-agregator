# ARCHITECTURE_ADDENDUM: Phase 4B AI Follow-Through Infrastructure

**Date:** March 2026  
**Related:** `PHASE_4B_PHASE1.md`, `Phase 4B plan.md`

This document extends ARCHITECTURE.md with Phase 4B-specific architectural patterns, schema relationships, and state management flows.

---

## Table of Contents

1. [New Database Schema](#new-database-schema)
2. [Recommendations ↔ AI Artifacts Relationship](#recommendations--ai-artifacts-relationship)
3. [Filter Serialization Architecture](#filter-serialization-architecture)
4. [State Management Architecture](#state-management-architecture)
5. [Data Flow Patterns](#data-flow-patterns)

---

## New Database Schema

### `recommendations` Table (Phase 1)

**Purpose:** Persist dashboard follow-through recommendations with full lifecycle management

**Table Definition:**

```sql
CREATE TABLE recommendations (
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
  status TEXT NOT NULL,
  
  -- Indices
  UNIQUE INDEX idx_rec_active ON (userId, status, expiresAt) 
    WHERE status = 'ACTIVE',
  INDEX idx_rec_artifact ON (sourceArtifactId),
  INDEX idx_rec_created ON (createdAt),
  INDEX idx_rec_expiry ON (expiresAt)
);
```

### Column Details

| Column | Type | Constraints | Purpose |
|--------|------|-------------|---------|
| `id` | TEXT | PK | UUID-based unique identifier |
| `userId` | TEXT | NOT NULL | Multi-user isolation (future-proof) |
| `recommendationText` | TEXT | NOT NULL | AI-generated summary (≤500 chars) |
| `navigationTarget` | TEXT | NOT NULL | Deterministic target: `TRANSACTION_LIST`, `BUDGET_DETAIL`, `RECURRING`, `REVIEW_QUEUE` |
| `filterCriteria` | TEXT | NOT NULL | Serialized JSON of `TransactionFilter` |
| `createdAt` | BIGINT | NOT NULL | Epoch millis when created |
| `updatedAt` | BIGINT | NOT NULL | Epoch millis last modified |
| `dismissedAt` | BIGINT | NULLABLE | When user dismissed (null if auto-expired) |
| `expiresAt` | BIGINT | NOT NULL | Epoch millis for TTL (createdAt + 7 days) |
| `priority` | TEXT | NOT NULL | Enum: `HIGH`, `MEDIUM`, `LOW` |
| `category` | TEXT | NOT NULL | Category tag: `FOOD`, `TRANSPORT`, `GENERAL`, etc. |
| `sourceArtifactId` | TEXT | NOT NULL | FK to `ai_artifacts.id` (Phase 4A) |
| `status` | TEXT | NOT NULL | Enum: `ACTIVE`, `ARCHIVED`, `EXPIRED` |

### Index Design Rationale

| Index | Columns | Query Pattern | Selectivity |
|-------|---------|---------------|-------------|
| `PRIMARY` | `id` | Point lookups by recommendation ID | Very high |
| `active_lookup` | `(userId, status, expiresAt)` | Active recommendations per user | High (most queries) |
| `artifact_link` | `sourceArtifactId` | Traceability, cascade delete | Medium |
| `created_sort` | `createdAt` | Chronological queries | Low |
| `expiry_sweep` | `expiresAt` | Maintenance worker TTL cleanup | High |

### Room Entity Mapping

```kotlin
@Entity(
    tableName = "recommendations",
    indices = [
        Index(value = ["userId", "status", "expiresAt"]),
        Index(value = ["sourceArtifactId"]),
        Index(value = ["createdAt"]),
        Index(value = ["expiresAt"])
    ]
)
data class RecommendationEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val recommendationText: String,
    val navigationTarget: String,
    val filterCriteria: String,
    val createdAt: Long,
    val updatedAt: Long,
    val dismissedAt: Long? = null,
    val expiresAt: Long,
    val priority: RecommendationPriority,
    val category: String,
    val sourceArtifactId: String,
    val status: RecommendationStatus
)
```

### Migration Script (AppDatabase v32+)

```kotlin
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add recommendations table
        database.execSQL("""
            CREATE TABLE recommendations (
                id TEXT PRIMARY KEY NOT NULL,
                userId TEXT NOT NULL,
                recommendationText TEXT NOT NULL,
                navigationTarget TEXT NOT NULL,
                filterCriteria TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                dismissedAt INTEGER,
                expiresAt INTEGER NOT NULL,
                priority TEXT NOT NULL,
                category TEXT NOT NULL,
                sourceArtifactId TEXT NOT NULL,
                status TEXT NOT NULL
            )
        """)
        
        // Add indices
        database.execSQL("""
            CREATE INDEX idx_rec_active 
            ON recommendations(userId, status, expiresAt)
        """)
        database.execSQL("""
            CREATE INDEX idx_rec_artifact 
            ON recommendations(sourceArtifactId)
        """)
        database.execSQL("""
            CREATE INDEX idx_rec_created 
            ON recommendations(createdAt)
        """)
        database.execSQL("""
            CREATE INDEX idx_rec_expiry 
            ON recommendations(expiresAt)
        """)
    }
}

// Register in AppDatabase
@Database(
    entities = [
        // ... existing
        RecommendationEntity::class  // NEW
    ],
    version = 32,
    autoMigrations = []
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        private val MIGRATIONS = arrayOf(
            // ... existing
            MIGRATION_31_32
        )
    }
}
```

---

## Recommendations ↔ AI Artifacts Relationship

### Entity Relationship Diagram

```
┌───────────────────────────────────┐
│       ai_artifacts (Phase 4A)     │
│                                   │
│  id (PK)         [long]           │
│  targetKey       [string]         │
│  capability      [enum]           │
│  status          [enum]           │
│  summaryText     [string]         │
│  expiresAt       [long]           │
│  createdAt       [long]           │
│  promptVersion   [string]         │
│  sourceHash      [string]         │
└───────────────────────────────────┘
           ▲
           │ (1:N)
           │ sourceArtifactId
           │
┌───────────────────────────────────┐
│    recommendations (Phase 1)      │
│                                   │
│  id (PK)         [string]         │
│  userId          [string]         │
│  recommendationText [string]      │
│  navigationTarget [string]        │
│  filterCriteria  [JSON string]    │
│  priority        [enum]           │
│  status          [enum]           │
│  expiresAt       [long]           │
│  sourceArtifactId [long] FK ────┘
└───────────────────────────────────┘
```

### Data Flow Example

**Timeline: Generation from Briefing**

```
T0: HomeScreen loads, Dashboard data aggregated
    │
    ├─► GenerateDashboardBriefingUseCase invoked
    │   ├─► Input: ProcessedDashboardData
    │   ├─► Check cache: targetKey = "dashboard_home:2026-03-16"
    │   ├─► Cache miss → DashboardBriefingService.generate()
    │   └─► Store ai_artifacts:
    │       {
    │           id: 12345,
    │           targetKey: "dashboard_home:2026-03-16",
    │           capability: DASHBOARD_BRIEFING,
    │           summaryText: "You're on track...",
    │           status: READY,
    │           expiresAt: T0 + 2 hours
    │       }
    │
    └─► [Phase 1B] RecommendationBuilder invoked
        ├─► Query latest ai_artifacts for dashboard_home:today
        ├─► Extract summaryText (the brief)
        ├─► Build deterministic recommendations:
        │   {
        │       HIGH: "Review queue has 5 items"
        │       MEDIUM: "Grocery spending is 18% of budget"
        │       MEDIUM: "Upcoming bills: €300 this week"
        │   }
        ├─► For each, create recommendation:
        │   INSERT recommendations:
        │   {
        │       id: "rec-001",
        │       sourceArtifactId: 12345,  ◄── Link
        │       recommendationText: "Review queue has 5...",
        │       navigationTarget: "REVIEW_QUEUE",
        │       filterCriteria: {"type": "PENDING_REVIEW"},
        │       priority: HIGH,
        │       expiresAt: T0 + 7 days
        │   }
        │   {
        │       id: "rec-002",
        │       sourceArtifactId: 12345,  ◄── Same artifact
        │       recommendationText: "Grocery spending...",
        │       navigationTarget: "TRANSACTION_LIST",
        │       filterCriteria: {"categoryId": 5},
        │       priority: MEDIUM,
        │       expiresAt: T0 + 7 days
        │   }
        │   {...}
        │
        └─► Result: 1 ai_artifacts row → 3 recommendations rows
```

### Query Patterns

**Fetch recommendations with full artifact context:**

```kotlin
// In RecommendationRepository
suspend fun getActiveWithArtifacts(userId: String): List<RecommendationWithArtifact> {
    return database.query("""
        SELECT 
            r.*, 
            a.summaryText as artifactSummary,
            a.promptVersion
        FROM recommendations r
        LEFT JOIN ai_artifacts a ON r.sourceArtifactId = a.id
        WHERE r.userId = ?
          AND r.status = 'ACTIVE'
          AND r.expiresAt > ?
        ORDER BY 
            CASE r.priority
                WHEN 'HIGH' THEN 3
                WHEN 'MEDIUM' THEN 2
                ELSE 1
            END DESC,
            r.createdAt DESC
        LIMIT 5
    """, arrayOf(userId, System.currentTimeMillis()))
}

data class RecommendationWithArtifact(
    val recommendation: RecommendationEntity,
    val artifactSummary: String?,
    val promptVersion: String?
)
```

**Cascade delete (Phase 2 feature):**

```kotlin
// When AI briefing fails or is invalidated
suspend fun invalidateRecommendationsForArtifact(artifactId: Long) {
    val before = System.currentTimeMillis() + 1000  // Mark as expired
    database.query("""
        UPDATE recommendations
        SET status = 'EXPIRED', expiresAt = ?
        WHERE sourceArtifactId = ?
    """, arrayOf(before, artifactId))
}
```

### Consistency Guarantees

| Scenario | Guarantee | Implementation |
|----------|-----------|-----------------|
| Briefing generated, no recommendations created | Data inconsistency tolerated | Graceful degradation: show briefing without rec cards |
| Briefing invalidated | Recommendations auto-expire | Cascade update via maintenance worker |
| Briefing deleted before recommendations read | Orphaned recommendation | FK cascade (soft delete: status = EXPIRED) |
| User deletes all recommendations | Briefing persists | Independent lifecycle: AI artifact unaffected |

---

## Filter Serialization Architecture

### TransactionFilter → JSON Mapping

**TransactionFilter domain model:**

```kotlin
data class TransactionFilter(
    val startDate: Long? = null,
    val endDate: Long? = null,
    val categoryIds: List<String> = emptyList(),
    val merchantPatterns: List<String> = emptyList(),
    val minAmount: Double = 0.0,
    val maxAmount: Double = Double.MAX_VALUE,
    val transactionTypes: List<String> = listOf("PURCHASE"),
    val searchText: String = "",
    val dateRange: DateRangeType = DateRangeType.CURRENT_MONTH,
    val sortBy: SortOrder = SortOrder.DATE_DESC,
    val includeTransfers: Boolean = false
)

enum class DateRangeType {
    CUSTOM, CURRENT_MONTH, CURRENT_YEAR, LAST_30_DAYS, ALL
}

enum class SortOrder {
    DATE_DESC, DATE_ASC, AMOUNT_DESC, AMOUNT_ASC, MERCHANT_ASC
}
```

**Serialized JSON example:**

```json
{
  "startDate": 1710259200000,
  "endDate": 1710345600000,
  "categoryIds": ["5", "10", "12"],
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

### Serialization Pipeline

**Writing to database:**

```kotlin
// In RecommendationRepository.upsertRecommendations()
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class RecommendationRepository {
    suspend fun upsertRecommendations(
        recommendations: List<DashboardFollowThroughRecommendation>
    ) {
        val entities = recommendations.map { rec ->
            // Serialize filter to JSON string
            val filterJson = Json.encodeToString(rec.transactionFilter)
            
            RecommendationEntity(
                id = rec.id,
                userId = rec.userId,
                recommendationText = rec.recommendationText,
                navigationTarget = rec.navigationTarget,
                filterCriteria = filterJson,  // ◄── Stored as string
                createdAt = rec.createdAt,
                updatedAt = rec.updatedAt,
                dismissedAt = rec.dismissedAt,
                expiresAt = rec.expiresAt,
                priority = rec.priority,
                category = rec.category,
                sourceArtifactId = rec.sourceArtifactId,
                status = rec.status
            )
        }
        
        recommendationDao.insertAll(entities)
    }
}
```

**Reading from database:**

```kotlin
// In HomeViewModel.onRecommendationTapped()
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

fun onRecommendationTapped(recommendation: RecommendationEntity) {
    viewModelScope.launch(Dispatchers.Default) {
        try {
            // Deserialize JSON string to TransactionFilter
            val filter = Json.decodeFromString<TransactionFilter>(
                recommendation.filterCriteria
            )
            
            // Navigate with deserialized filter
            val navigationTarget = when (recommendation.navigationTarget) {
                "TRANSACTION_LIST" -> {
                    TransactionListRoute(filter = filter)
                }
                "BUDGET_DETAIL" -> {
                    BudgetDetailRoute(budgetId = filter.categoryIds.firstOrNull())
                }
                "RECURRING" -> {
                    RecurringExpensesRoute()
                }
                "REVIEW_QUEUE" -> {
                    ReviewQueueRoute()
                }
                else -> HomeRoute()
            }
            
            _navigationEvents.emit(navigationTarget)
        } catch (e: Exception) {
            Timber.e(e, "Failed to deserialize filter JSON: ${recommendation.filterCriteria}")
            // Fallback: navigate to home
            _navigationEvents.emit(HomeRoute())
        }
    }
}
```

### Validation & Error Handling

**Strict validation:**

```kotlin
// Validate before storing
object FilterValidator {
    fun validate(filter: TransactionFilter): Result<Unit> {
        return when {
            filter.startDate != null && filter.endDate != null && 
            filter.startDate > filter.endDate -> 
                Result.failure(IllegalArgumentException("startDate > endDate"))
            
            filter.minAmount < 0.0 -> 
                Result.failure(IllegalArgumentException("minAmount < 0"))
            
            filter.minAmount > filter.maxAmount -> 
                Result.failure(IllegalArgumentException("minAmount > maxAmount"))
            
            filter.categoryIds.isEmpty() && filter.navigationTarget == "BUDGET_DETAIL" ->
                Result.failure(IllegalArgumentException("BUDGET_DETAIL requires categoryId"))
            
            else -> Result.success(Unit)
        }
    }
}

// In RecommendationRepository.upsertRecommendations()
val validationResult = FilterValidator.validate(rec.transactionFilter)
if (validationResult.isFailure) {
    Timber.w("Invalid filter for recommendation ${rec.id}: ${validationResult.exceptionOrNull()}")
    // Skip this recommendation or use default filter
    return@map null
}
```

**Lenient deserialization (forward-compatible):**

```kotlin
// Custom deserializer to handle missing fields gracefully
@Serializable
data class TransactionFilter(
    val startDate: Long? = null,
    val endDate: Long? = null,
    @SerialName("categoryIds")
    val categoryIds: List<String> = emptyList(),
    // ... rest of fields with defaults
) {
    companion object {
        fun fromJsonSafe(jsonString: String): TransactionFilter {
            return try {
                Json.decodeFromString(jsonString)
            } catch (e: Exception) {
                Timber.w(e, "Deserialization failed, using defaults")
                TransactionFilter()  // Return sensible defaults
            }
        }
    }
}
```

---

## State Management Architecture

### ViewModel State Flow

**HomeViewModel with recommendations:**

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val dashboardRepository: DashboardRepository,
    private val recommendationRepository: RecommendationRepository,
    private val authRepository: AuthRepository,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {
    
    // Phase 4A: Dashboard briefing (existing)
    private val _dashboardData = MutableStateFlow<ProcessedDashboardData?>(null)
    val dashboardData: StateFlow<ProcessedDashboardData?> = _dashboardData.asStateFlow()
    
    val briefing: StateFlow<String?> = 
        dashboardData
            .filterNotNull()
            .flatMapLatest { data ->
                aiArtifactRepository.observeBriefing(data)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    
    // Phase 1: Dashboard recommendations (new)
    val recommendations: StateFlow<List<DashboardFollowThroughRecommendation>> =
        authRepository.currentUser
            .flatMapLatest { user ->
                recommendationRepository.observeActive(user.id)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    // Navigation events
    private val _navigationEvent = Channel<NavigationTarget>(Channel.BUFFERED)
    val navigationEvent = _navigationEvent.receiveAsFlow()
    
    // Lifecycle
    init {
        loadDashboardData()
    }
    
    private fun loadDashboardData() {
        viewModelScope.launch {
            dashboardRepository.getDashboardData()
                .collect { _dashboardData.value = it }
        }
    }
    
    // Recommendation interactions
    fun onRecommendationTapped(rec: DashboardFollowThroughRecommendation) {
        viewModelScope.launch(ioDispatcher) {
            try {
                // Track engagement
                engagementRepository.logRecommendationOpen(rec.id)
                
                // Deserialize filter
                val filter = Json.decodeFromString<TransactionFilter>(rec.filterCriteria)
                
                // Navigate
                val target = mapToNavigationTarget(rec.navigationTarget, filter)
                _navigationEvent.send(target)
            } catch (e: Exception) {
                Timber.e(e, "Failed to handle recommendation tap")
                _navigationEvent.send(HomeRoute())
            }
        }
    }
    
    fun onRecommendationDismissed(rec: DashboardFollowThroughRecommendation) {
        viewModelScope.launch(ioDispatcher) {
            try {
                // Track engagement
                engagementRepository.logRecommendationDismissed(rec.id)
                
                // Archive in database
                recommendationRepository.archiveRecommendation(rec.id)
                
                // Flow will emit updated list automatically
            } catch (e: Exception) {
                Timber.e(e, "Failed to dismiss recommendation")
            }
        }
    }
    
    private fun mapToNavigationTarget(
        target: String, 
        filter: TransactionFilter
    ): NavigationTarget = when (target) {
        "TRANSACTION_LIST" -> TransactionListRoute(filter = filter)
        "BUDGET_DETAIL" -> {
            val budgetId = filter.categoryIds.firstOrNull()?.toLongOrNull() ?: return HomeRoute()
            BudgetDetailRoute(budgetId = budgetId)
        }
        "RECURRING" -> RecurringExpensesRoute()
        "REVIEW_QUEUE" -> ReviewQueueRoute()
        else -> HomeRoute()
    }
}
```

### Engagement Tracking Integration

**New EngagementRepository (Phase 1B):**

```kotlin
enum class EngagementEventType {
    RECOMMENDATION_SHOWN,
    RECOMMENDATION_OPENED,
    RECOMMENDATION_DISMISSED
}

data class EngagementEvent(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val recommendationId: String,
    val eventType: EngagementEventType,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface EngagementEventDao {
    @Insert
    suspend fun insert(event: EngagementEvent)
    
    @Query("""
        SELECT COUNT(*) FROM engagement_events
        WHERE recommendationId = :recommendationId
          AND eventType = :eventType
    """)
    suspend fun countEvents(recommendationId: String, eventType: EngagementEventType): Int
}

@Singleton
class EngagementRepository @Inject constructor(
    private val dao: EngagementEventDao,
    private val timeProvider: TimeProvider
) {
    suspend fun logRecommendationShown(recommendationId: String, userId: String) {
        dao.insert(EngagementEvent(
            userId = userId,
            recommendationId = recommendationId,
            eventType = EngagementEventType.RECOMMENDATION_SHOWN
        ))
    }
    
    suspend fun logRecommendationOpen(recommendationId: String, userId: String) {
        dao.insert(EngagementEvent(
            userId = userId,
            recommendationId = recommendationId,
            eventType = EngagementEventType.RECOMMENDATION_OPENED
        ))
    }
    
    suspend fun logRecommendationDismissed(recommendationId: String, userId: String) {
        dao.insert(EngagementEvent(
            userId = userId,
            recommendationId = recommendationId,
            eventType = EngagementEventType.RECOMMENDATION_DISMISSED
        ))
    }
}
```

---

## Data Flow Patterns

### Complete End-to-End Flow

```
1. USER OPENS HOME SCREEN
   ├─► HomeViewModel initializes
   ├─► Load ProcessedDashboardData
   └─► Emit to dashboardData StateFlow

2. BRIEFING GENERATION (Phase 4A)
   ├─► GenerateDashboardBriefingUseCase.invoke(dashboardData)
   ├─► Check AiArtifactRepository cache
   ├─► If stale: call DashboardBriefingService.generate()
   ├─► Store AiArtifactEntity with status=READY, expiresAt=+2hrs
   └─► HomeViewModel.briefing StateFlow emits text

3. RECOMMENDATION GENERATION (Phase 1B - TBD)
   ├─► DashboardFollowThroughBuilder.build(dashboardData, briefing)
   ├─► Deterministic rules:
   │   ├─► IF pendingReviewCount > 3 → REVIEW_QUEUE rec
   │   ├─► IF criticalBudgets.isNotEmpty() → BUDGET rec
   │   ├─► IF topCategory.% > 15% → TRANSACTION rec
   │   └─► IF upcomingRecurring.isNotEmpty() → RECURRING rec
   ├─► For each recommendation:
   │   ├─► Serialize TransactionFilter to JSON
   │   ├─► Link sourceArtifactId to ai_artifacts.id
   │   └─► Set expiresAt = now + 7 days
   ├─► RecommendationRepository.upsertRecommendations()
   ├─► Insert into recommendations table
   └─► HomeViewModel.recommendations StateFlow emits list

4. UI RENDERING
   ├─► HomeScreen observes recommendations StateFlow
   ├─► For each recommendation:
   │   ├─► Render RecommendationCard
   │   ├─► Show priority badge
   │   └─► Tap/dismiss callbacks
   └─► User sees interactive cards under briefing

5. USER TAPS RECOMMENDATION
   ├─► HomeViewModel.onRecommendationTapped(rec)
   ├─► EngagementRepository.logRecommendationOpen()
   ├─► Deserialize rec.filterCriteria to TransactionFilter
   ├─► MapToNavigationTarget(rec.navigationTarget, filter)
   ├─► Emit NavigationEvent → Navigate to target screen
   └─► Target screen receives filter and applies it

6. USER DISMISSES RECOMMENDATION
   ├─► HomeViewModel.onRecommendationDismissed(rec)
   ├─► EngagementRepository.logRecommendationDismissed()
   ├─► RecommendationRepository.archiveRecommendation(rec.id)
   ├─► Update: status=ARCHIVED, dismissedAt=now
   ├─► Database query returns updated list (without this rec)
   └─► HomeViewModel.recommendations StateFlow emits updated list

7. DAILY MAINTENANCE (e.g., midnight)
   ├─► RecommendationMaintenanceWorker.doWork()
   ├─► expireOld(): Mark all expiredAt < now as status=EXPIRED
   ├─► getActiveByUser(): No longer returns EXPIRED recs
   ├─► Weekly: deleteExpired() removes all EXPIRED rows
   └─► Space reclaimed, table stays lean

8. USER LOGS OUT / ACCOUNT SWITCH
   ├─► AuthRepository.logout()
   ├─► HomeViewModel.onCleared() or similar
   ├─► RecommendationRepository.clearForUser(oldUserId)
   ├─► DELETE FROM recommendations WHERE userId = oldUserId
   ├─► Cascade: Clear linked ai_artifacts (Phase 2)
   └─► New user's recommendations load from fresh database
```

### Cache Coherence Pattern

```
Update Event → Multiple Systems → Eventually Consistent

Example: User deletes transaction

T0: User swipes to delete expense
    │
    └─► ExpenseRepository.delete()
        └─► notifyObservers()

T1: DashboardRepository detects change
    │
    ├─► Recompute dashboard widgets
    ├─► sourceHash changes
    └─► Emit new ProcessedDashboardData

T2: HomeViewModel observes new dashboard data
    │
    ├─► AiArtifactRepository cache misses (sourceHash changed)
    ├─► GenerateDashboardBriefingUseCase regenerates
    └─► Update ai_artifacts with new status=READY

T3: RecommendationBuilder detects new briefing
    │
    ├─► Old recommendations now stale (based on old data)
    ├─► Deterministic builder generates fresh recommendations
    ├─► Call upsertRecommendations()
    └─► Update recommendations table (new insertions, old archives)

T4: HomeViewModel.recommendations StateFlow emits
    │
    ├─► UI automatically re-renders
    ├─► Users see updated recommendations
    └─► All cached values consistent

Latency: T0 → T4 ≈ 200-500ms (network calls to AI service)
```

---

## Integration Points with Existing Systems

### Phase 4A Integration

| Component | Phase 4A | Phase 1 | Relationship |
|-----------|----------|--------|-------------|
| GenerateDashboardBriefingUseCase | Generates briefing | Reads artifact | One-way: Phase 1 depends on Phase 4A |
| AiArtifactEntity | Stores briefing | Links via sourceArtifactId | 1:N (1 artifact → N recommendations) |
| AiArtifactRepository | CRUD artifacts | Queries for cache check | Read-only in Phase 1 |
| Dashboard data | Aggregates data | Uses same data input | Shared ProcessedDashboardData |
| Settings toggle | dashboardBriefingEnabled | Will add followThroughEnabled | Parallel controls |

### Future Phase 2+ Integration

| Feature | Location | Phase 1 Foundation |
|---------|----------|-------------------|
| Engagement tracking | EngagementRepository | Placeholders in code, DAO schema ready |
| Auto-expiry worker | RecommendationMaintenanceWorker | DAO methods prepared, Worker scheduled in Phase 2 |
| Debug surface integration | DebugScreen | DAO queries ready for display |
| Deterministic builder | DashboardFollowThroughBuilder | Design pattern in PHASE_4B_PHASE1.md |
| Location-aware recommendations | LocationResolver (Phase 2+) | Store `location` field in recommendations (Phase 2 schema update) |

---

## Performance Considerations

### Query Performance

**Active recommendations lookup (most common):**
```sql
SELECT * FROM recommendations
WHERE userId = '12345' 
  AND status = 'ACTIVE'
  AND dismissedAt IS NULL
  AND expiresAt > 1710345600000
ORDER BY priority DESC, createdAt DESC
LIMIT 5
```

**Index:** `(userId, status, expiresAt)` → O(log N) range scan, <1ms typical

**Expiry sweep (batch operation):**
```sql
UPDATE recommendations
SET status = 'EXPIRED', updatedAt = ?
WHERE userId = ?
  AND expiresAt < ?
  AND status != 'EXPIRED'
```

**Index:** `(expiresAt)` → O(log N), 5-10ms for millions of rows

### Memory Usage

- **Per recommendation:** ~500 bytes (JSON + metadata)
- **Per user active set:** 5 max × 500 = 2.5 KB
- **Per VM StateFlow:** ~1 MB (shared across all instances)
- **Serialization overhead:** <5% (kotlinx.serialization is efficient)

### Caching Strategy

| Layer | Memory | TTL | Hit Rate |
|-------|--------|-----|----------|
| Flow-based (Tier 2) | In-memory | 24h or manual | 95%+ |
| Database (Tier 3) | ~2-5 KB/user | 7 days | 99%+ |
| Network (Phase 4A) | LRU cache | 2 hours | 80%+ |

---

## References

- PHASE_4B_PHASE1.md: Complete Phase 1 specification
- Phase 4B plan.md: High-level feature design
- CODEBASE_SEGMENTS.md: Segment mapping for Phase 4B
- ARCHITECTURE.md: Base architecture guide

