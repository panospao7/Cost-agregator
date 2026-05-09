# Categorization / Merchant Normalization Engine Implementation Plan

Scope:

```text
C04 C06 C07 C08 C11 C12 C13 C14
```

Reviewed against latest context around `ddfd8747ccc0420447fcc98ed68d3df056ec022b`.

Main verdict:

```text
Categorization is functional but not fully stable.
The biggest remaining risks are cache invalidation, ambiguous canonical lookup,
merchant stats not being updated, and weak observability for categorization decisions.
```

---

# 0. Current status correction

| ID | Current realistic status |
|---|---|
| C04 | **PARTIAL** — `CategorizationEngine.learnMerchantCategory()` invalidates its own cache, and `CategoryRepository` invalidates after add/merge/delete, but `MerchantCategoryRepository.insert/deleteAll`, default seeding, and direct DAO writes can still bypass full invalidation. |
| C06 | **OPEN** — `normalizedCanonicalName` has a non-unique index and DAO returns one row. Ambiguity remains. |
| C07 | **OPEN / DEFERRED** — `MerchantNormalizer` builds BK-tree from top 1000 merchants only. |
| C08 | **OPEN** — `incrementMerchantStats()` exists but is not called from `TransactionSideEffectDispatcher`. |
| C11 | **OPEN / OPTIONAL** — `updateExpenseCategoryBulk()` is a no-op stub. |
| C12 | **OPEN** — semantic keyword collisions still return first match. |
| C13 | **OPEN / ENHANCEMENT** — context model is still limited and uses direct `Calendar`. |
| C14 | **OPEN** — debug trace is generated but not persisted. |

Additional issues found:

```text
C15: MerchantCategoryDao insert comments say 0 = skipped, but Room IGNORE returns -1 for ignored inserts.
C16: Default merchant dictionary seeding does not clearly invalidate CategorizationEngine cache after bulk insert.
C17: Direct `merchantCategoryDao.insertAll()` paths bypass repository-level normalization/conflict handling.
C18: `ContextualInferenceEngine` uses Calendar.getInstance() directly instead of TimeProvider/ZoneId.
C19: category merge/delete changes historical expense category IDs; no category-name snapshot for analytics/history.
```

---

# 1. PR-C0 — Tracker and TODO comment reconciliation

## Goal

Make tracker/comments reflect reality before implementation.

## Fix tracker statuses

```text
C04 → PARTIAL
C06 → OPEN
C07 → DEFERRED or OPEN
C08 → OPEN
C11 → OPTIONAL/DEFERRED or OPEN
C12 → OPEN
C13 → DEFERRED_ENHANCEMENT
C14 → OPEN
```

## Fix misleading comments

### `MerchantCategoryDao`

Current comment says skipped insert returns `0`.

Change to:

```kotlin
// Room @Insert(onConflict = IGNORE) returns the new rowId, or -1L if ignored.
```

### `CategorizationEngine`

Current C04 comment says only internal write path invalidates. Keep, but add:

```kotlin
// C04 PARTIAL: internal `learnMerchantCategory()` invalidates this cache.
// Remaining: all MerchantCategoryRepository/CategoryRepository/seed/direct DAO writes
// must emit CategoryMappingChanged and invalidate every categorization cache.
```

### `SemanticKeywordMatcher`

Replace TODO with exact contract:

```kotlin
// C12 OPEN: findBestMatch() must detect close competing categories.
// If top score gap < threshold, return ambiguous alternatives instead of
// silently choosing the first category.
```

Acceptance:

```text
No stale “TODO ONLY” when code is partial/fixed.
No comment claims fixed behavior where direct DAO paths still bypass invariants.
```

---

# 2. PR-C1 — C04: central category/merchant mapping invalidation

## Problem

There are multiple mapping/category write paths:

```text
CategorizationEngine.learnMerchantCategory()
MerchantCategoryRepository.insert()
MerchantCategoryRepository.deleteAll()
CategoryRepository.addCategory()
CategoryRepository.mergeCategories()
CategoryRepository.deleteCategory()
CategoryRepository.ensureDefaultCategories()
direct MerchantCategoryDao.insertAll() during seeding
```

Some invalidate:

```text
CategorizationEngine cache
HybridExpenseClassifier snapshot
```

but not all write paths consistently invalidate every affected cache.

## Implementation

Create a central writer/orchestrator:

```kotlin
@Singleton
class CategoryMappingWriter @Inject constructor(
    private val merchantCategoryDao: MerchantCategoryDao,
    private val categoryDao: CategoryDao,
    private val categorizationEngine: CategorizationEngine,
    private val hybridExpenseClassifier: Lazy<HybridExpenseClassifier>,
    private val merchantNormalizer: Lazy<MerchantNormalizer>,
    private val eventBus: CategoryMappingEventBus,
    private val timeProvider: TimeProvider
)
```

Events:

```kotlin
sealed interface CategoryMappingChanged {
    data class MerchantMappingInserted(...)
    data class MerchantMappingUpdated(...)
    data class MerchantMappingsBulkInserted(...)
    data class MerchantMappingsDeleted(...)
    data class CategoryAdded(...)
    data class CategoryMerged(...)
    data class CategoryDeleted(...)
}
```

Invalidation method:

```kotlin
suspend fun invalidateAll(reason: CategoryMappingChanged) {
    categorizationEngine.invalidateCache()
    hybridExpenseClassifier.get().invalidateCategorySnapshot()
    merchantNormalizer.get().invalidateTreeCacheIfPublicOrExposeMethod()
    eventBus.emit(reason)
}
```

Route all writes through writer:

```text
MerchantCategoryRepository.insert()
MerchantCategoryRepository.insertAll()
MerchantCategoryRepository.deleteAll()
CategoryRepository.addCategory()
CategoryRepository.mergeCategories()
CategoryRepository.deleteCategory()
CategoryRepository.ensureDefaultCategories()
```

Avoid direct DAO write from repository except inside writer.

## Acceptance

```text
Every category/mapping write invalidates:
- CategorizationEngine cache
- HybridExpenseClassifier category snapshot
- MerchantNormalizer fuzzy tree if canonical/category-affecting
- optional SemanticKeyword cache if made dynamic later
```

## Tests

```text
CategoryMappingInsertInvalidatesCategorizationCacheTest
CategoryAddInvalidatesCategoryNameCacheTest
CategoryMergeInvalidatesAllCachesTest
DefaultSeedingInvalidatesCategorizationCacheTest
MerchantCategoryRepositoryInsertCannotBypassInvalidationTest
```

---

# 3. PR-C2 — C06: deterministic normalizedCanonicalName resolution

## Problem

`MerchantCategoryDao.getCategoryByNormalizedCanonical()` returns a single row:

```kotlin
SELECT * FROM merchant_categories WHERE normalizedCanonicalName = :normalizedCanonicalName
```

But `normalizedCanonicalName` is only indexed, not unique. Multiple rows can exist.

Making it unique may be wrong because several raw patterns may legitimately canonicalize to the same name.

## Recommended implementation

Do **not** force uniqueness immediately. Instead, return all and resolve deterministically.

DAO:

```kotlin
@Query("""
    SELECT * FROM merchant_categories
    WHERE normalizedCanonicalName = :normalizedCanonicalName
    ORDER BY confidence DESC, timesUsed DESC, merchantPattern ASC
""")
suspend fun getCategoriesByNormalizedCanonical(
    normalizedCanonicalName: String
): List<MerchantCategory>
```

Repository:

```kotlin
sealed interface CanonicalCategoryResolution {
    data class Resolved(val mapping: MerchantCategory) : CanonicalCategoryResolution
    data class Ambiguous(
        val normalizedCanonicalName: String,
        val candidates: List<MerchantCategory>
    ) : CanonicalCategoryResolution
    data object NotFound : CanonicalCategoryResolution
}
```

Policy:

```text
if no rows → NotFound
if all rows agree on categoryId → Resolved(best)
if categories differ and score gap >= threshold → Resolved(best) with warning
if categories differ and close → Ambiguous
```

`CategorizationEngine` should:

```text
use Resolved confidently
route Ambiguous to review or lower confidence
```

Optional later migration:

```text
canonical_category_mappings table:
normalizedCanonicalName UNIQUE
categoryId
confidence
source
updatedAt
```

## Acceptance

```text
Ambiguous canonical mappings never silently pick arbitrary Room row.
```

## Tests

```text
NormalizedCanonicalSingleMatchTest
NormalizedCanonicalMultipleSameCategoryResolvedTest
NormalizedCanonicalConflictingCategoriesAmbiguousTest
NormalizedCanonicalConfidenceTieDeterministicTest
```

---

# 4. PR-C3 — C08: update merchant canonical stats post-commit

## Problem

`MerchantNormalizationRepository.incrementMerchantStats()` exists, but `TransactionSideEffectDispatcher` only has implementation-plan comments.

## Implementation

Inject repository:

```kotlin
class TransactionSideEffectDispatcher @Inject constructor(
    ...
    private val merchantNormalizationRepository: MerchantNormalizationRepository
)
```

Add helper:

```kotlin
private suspend fun updateMerchantStats(expense: Expense) {
    val key = expense.merchantKey ?: MerchantKeyGenerator.generate(expense.merchant)
    val canonical = merchantNormalizationRepository.getCanonicalBySearchKey(key)
        ?: merchantNormalizationRepository.getAliasByNormalizedKey(key)
            ?.let { merchantNormalizationRepository.getCanonicalById(it.canonicalId) }
        ?: return

    merchantNormalizationRepository.incrementMerchantStats(
        id = canonical.id,
        amount = expense.effectiveAmount,
        timestamp = expense.date
    )
}
```

Call from:

```text
dispatchOnCreated()
dispatchOnUpdated()
```

For updates, decide policy:

### Short-term

```text
recompute stats from expenses periodically or accept append-style approximate stats
```

### Better

Add delta support:

```kotlin
incrementMerchantStatsDelta(canonicalId, amountDelta, occurrenceDelta, timestamp)
```

For deletes:

```text
either recompute canonical stats for that merchant
or decrement with old amount
```

Recommended robust path:

```text
MerchantStatsRecalculator.recompute(canonicalId)
```

after update/delete if exactness matters.

## Acceptance

```text
merchant canonical totalOccurrences/totalSpent/updatedAt reflect committed expenses.
stats update only after transaction lifecycle commit.
```

## Tests

```text
MerchantStatsUpdatedOnExpenseCreatedTest
MerchantStatsUpdatedAfterExpenseUpdatedTest
MerchantStatsNotUpdatedOnRollbackTest
MerchantStatsDeleteRecomputeTest
```

---

# 5. PR-C4 — C12: semantic keyword collision policy

## Problem

`SemanticKeywordMatcher.findBestMatch()` returns the first sorted match. If top categories are close, it silently picks one.

## Implementation

Extend result:

```kotlin
data class SemanticDecision(
    val primary: SemanticMatch?,
    val alternatives: List<SemanticMatch>,
    val ambiguous: Boolean,
    val confidencePenalty: Double,
    val reason: String
)
```

New API:

```kotlin
fun decide(
    merchant: String,
    minConfidence: Double = 0.50,
    ambiguityGap: Double = 0.08
): SemanticDecision
```

Policy:

```text
top empty → no match
top - second >= ambiguityGap → primary
top - second < ambiguityGap → ambiguous
```

In `CategorizationEngine`:

```text
if semantic ambiguous:
  either return UNKNOWN with alternatives
  or return low-confidence primary and explanation says ambiguous
```

Better result model:

```kotlin
data class CategorizationResult(
    ...
    val alternatives: List<CategorizationAlternative> = emptyList(),
    val requiresReview: Boolean = false
)
```

## Acceptance

```text
Close semantic keyword collisions do not become confident category assignments.
```

## Tests

```text
SemanticClearWinnerTest
SemanticCollisionReturnsAlternativesTest
SemanticCollisionLowersConfidenceTest
CategorizationRoutesSemanticAmbiguityToReviewTest
```

---

# 6. PR-C5 — C14: persistent categorization decision trace ring buffer

## Problem

`debugCategorize()` returns rich trace but it is transient. Production/debug analysis cannot inspect real decisions later.

## Implementation

Add entity:

```kotlin
@Entity(
    tableName = "categorization_decision_traces",
    indices = [
        Index("createdAt"),
        Index("merchantKey"),
        Index("finalCategoryId"),
        Index("requiresReview")
    ]
)
data class CategorizationDecisionTraceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long?,
    val rawMerchantRedacted: String,
    val merchantKey: String,
    val amountBucket: String,
    val timestamp: Long,
    val finalCategoryId: Long?,
    val finalCategoryName: String?,
    val confidence: Double,
    val matchType: String,
    val requiresReview: Boolean,
    val alternativesJson: String?,
    val traceJson: String,
    val createdAt: Long
)
```

Privacy:

```text
Do not persist raw merchant by default.
Store merchantKey + redacted/truncated merchant.
Amount stored as bucket unless debug persistence enabled.
```

DAO:

```kotlin
insertTrace()
getRecent(limit)
deleteOlderThan(cutoff)
count()
deleteOldestOverLimit(maxRows)
```

Ring buffer policy:

```text
maxRows = 500 or 1000
retentionDays = from PrivacySettings.debugDataPersistenceEnabled / raw retention policy
```

Engine integration:

```text
CategorizationEngine.categorizeWithContext(..., traceMode)
```

or sidecar:

```text
CategorizationTraceRecorder.record(input, result, layerResults)
```

## Acceptance

```text
Recent categorization decisions can be inspected without leaking raw sensitive data.
```

## Tests

```text
CategorizationTraceInsertedTest
CategorizationTraceRingBufferPrunesOldestTest
CategorizationTraceRedactsMerchantTest
CategorizationTraceDisabledWhenPrivacyOffTest
```

---

# 7. PR-C6 — C07: fuzzy search long-tail strategy

## Problem

`MerchantNormalizer` BK-tree uses:

```text
repository.getTopMerchants(1000)
```

Long-tail merchants are invisible to fuzzy matching.

## Implementation options

### Option A — full BK-tree

Add DAO:

```kotlin
@Query("SELECT * FROM merchant_canonicals")
suspend fun getAllCanonicals(): List<MerchantCanonical>
```

Build tree from all.

Add cap/guard:

```text
if count <= 50k build full
else build top + prefix fallback
```

### Option B — prefix fallback

For large stores:

```text
query prefix candidates first
then distance rank
```

DAO:

```kotlin
@Query("""
SELECT * FROM merchant_canonicals
WHERE searchKey LIKE :prefix || '%'
ORDER BY totalOccurrences DESC
LIMIT :limit
""")
suspend fun getCanonicalsByPrefix(prefix: String, limit: Int)
```

Recommended:

```text
A for now if local dataset is small; B as scalability fallback.
```

## Acceptance

```text
fuzzy match sees more than top 1000 or has deterministic prefix fallback.
```

## Tests

```text
FuzzyMatchesMerchantOutsideTop1000Test
FuzzyPrefixFallbackTest
BKTreeRebuildAfterNewMerchantTest
BKTreeRebuildAfterAliasLearnedTest
```

---

# 8. PR-C7 — C11: lifecycle-aware category correction backfill

## Problem

`CategoryRepository.updateExpenseCategoryBulk()` is a no-op. If user says “always categorize merchant X as Food”, old rows do not update.

## Implementation

Add explicit use case:

```kotlin
class BackfillMerchantCategoryUseCase @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator,
    private val merchantNormalizer: MerchantNormalizer,
    private val timeProvider: TimeProvider
)
```

Command:

```kotlin
data class BackfillMerchantCategoryCommand(
    val merchant: String,
    val categoryId: Long,
    val dateRange: PeriodRange? = null,
    val onlyUncategorized: Boolean = false,
    val dryRun: Boolean = true,
    val limit: Int = 500
)
```

Flow:

```text
1. resolve merchantKey/canonical aliases
2. query matching expenses
3. dry-run returns count/sample
4. execute updates each via transactionLifecycleCoordinator.updateCategory()
5. write lifecycle events
6. dispatch side effects after commit
```

Return:

```kotlin
BackfillResult(
    matchedCount,
    updatedCount,
    skippedCount,
    failures
)
```

## Acceptance

```text
Backfill is opt-in, lifecycle-aware, previewable, and does not direct-update expenses.
```

## Tests

```text
BackfillDryRunDoesNotMutateTest
BackfillUpdatesOnlyMatchingMerchantTest
BackfillOnlyUncategorizedTest
BackfillWritesLifecycleEventsTest
BackfillRollbackFailureReportedTest
```

---

# 9. PR-C8 — C13: expanded categorization context

## Problem

`ContextualInferenceEngine` uses amount/time/source heuristics only. It also uses direct `Calendar`.

## Implementation

Create:

```kotlin
data class CategorizationContext(
    val amount: Double,
    val timestamp: Long,
    val zoneId: ZoneId,
    val dayOfWeek: DayOfWeek,
    val hourOfDay: Int,
    val notificationSource: String?,
    val location: GeoCoordinate?,
    val recentCategoryDistribution: Map<Long, Double>,
    val recurringCandidate: Boolean,
    val merchantKnownLocationCategoryHint: String?,
    val userCorrectionHistory: Map<Long, Int>
)
```

Context builder:

```kotlin
class CategorizationContextBuilder @Inject constructor(
    private val timeProvider: TimeProvider,
    private val merchantLocationRepository: MerchantLocationRepository,
    private val expenseRepository: ExpenseRepository,
    private val correctionRepository: CategoryCorrectionRepository?
)
```

Replace `Calendar.getInstance()` with:

```kotlin
Instant.ofEpochMilli(timestamp).atZone(zoneId)
```

Model:

```text
weighted scoring, not flat if/when boosts
```

Add confidence/data quality:

```text
contextSignalsUsed
missingSignals
confidencePenalty
```

## Acceptance

```text
context inference is deterministic, timezone-aware, and can explain signals.
```

## Tests

```text
ContextInferenceUsesProvidedZoneTest
ContextInferenceNoDirectCalendarTest
ContextIncludesRecentSpendingPatternTest
ContextUsesLocationHintWhenAvailableTest
ContextConfidenceLowerWithMissingSignalsTest
```

---

# 10. Additional issue C15 — fix Room insert conflict semantics

## Problem

Comments say:

```text
0 = skipped
```

Room `@Insert(onConflict = IGNORE)` returns:

```text
rowId or -1L on ignored insert
```

## Implementation

Standard helper:

```kotlin
fun Long.toInsertResult(): InsertResult =
    if (this > 0) InsertResult.Inserted(this)
    else InsertResult.Ignored
```

Use in repositories.

Tests:

```text
MerchantCategoryInsertConflictReturnsIgnoredTest
MerchantAliasInsertConflictReturnsIgnoredTest
```

---

# 11. Additional issue C16/C17 — default seeding and direct DAO bypass

## Problem

`CategoryRepository.ensureDefaultCategories()` bulk inserts merchant mappings directly:

```kotlin
merchantCategoryDao.insertAll(merchantEntities)
```

This bypasses repository-level conflict handling and full invalidation.

## Implementation

Route through:

```kotlin
CategoryMappingWriter.insertAllMerchantMappings(merchantEntities)
```

Add conflict accounting:

```kotlin
BulkInsertResult(
    insertedCount,
    ignoredCount,
    conflicts
)
```

Acceptance:

```text
default seeding cannot leave categorization cache stale.
bulk insert conflict count is observable.
```

Tests:

```text
DefaultMerchantSeedingInvalidatesCacheTest
DefaultMerchantSeedingReportsIgnoredConflictsTest
```

---

# 12. Additional issue C19 — historical category snapshots

## Problem

Category merge/delete can rewrite historical rows, making past analytics/history less explainable.

## Implementation options

### Option A — expense category snapshot

Add to `expenses`:

```text
categoryNameSnapshot
categoryIconSnapshot
categoryColorSnapshot
```

Set when category assigned/updated.

### Option B — category history table

```kotlin
CategoryHistoryEntity(
    categoryId,
    name,
    icon,
    color,
    validFrom,
    validTo
)
```

Recommended short-term:

```text
Option A for analytics/report display.
```

Acceptance:

```text
Historical analytics can display old category name even after category merge/delete.
```

Tests:

```text
ExpenseCategorySnapshotSetOnCreateTest
CategoryMergePreservesHistoricalSnapshotTest
DeletedCategoryHistoricalAnalyticsReadableTest
```

---

# 13. Recommended execution order

```text
1. PR-C0 tracker/comment reconciliation
2. PR-C1 central CategoryMappingWriter + invalidation
3. PR-C2 canonical mapping ambiguity resolution
4. PR-C3 merchant stats post-commit update
5. PR-C4 semantic collision policy
6. PR-C5 persistent decision trace ring buffer
7. PR-C6 fuzzy long-tail improvement
8. PR-C7 lifecycle-aware backfill
9. PR-C8 expanded context + remove Calendar
10. PR-C15/C16 insert semantics + seed routing cleanup
11. PR-C19 category history snapshot if analytics/history needs it
```

If you want minimum stabilization before moving on:

```text
Must do:
- C04
- C06
- C08
- C12
- C14

Can defer:
- C07
- C11
- C13
- C19
```

---

# 14. Golden scenario tests

## Scenario 1 — cache invalidation

Seed:

```text
merchant "LIDL" → Groceries
categorize LIDL
change mapping LIDL → Shopping
categorize LIDL again
```

Expected:

```text
second categorization returns Shopping immediately, no 5-minute stale cache.
```

## Scenario 2 — canonical ambiguity

Seed:

```text
merchantPattern "coffee lab" normalizedCanonicalName "coffee"
category Food
merchantPattern "coffee hardware" normalizedCanonicalName "coffee"
category Shopping
```

Expected:

```text
resolver returns Ambiguous or low-confidence alternatives, not arbitrary category.
```

## Scenario 3 — merchant stats

Create expense:

```text
merchantKey = lidl
effectiveAmount = 20
category = Groceries
```

Expected:

```text
merchant canonical stats totalOccurrences += 1
totalSpent += 20
updatedAt = expense.date or event time
```

## Scenario 4 — semantic collision

Input:

```text
"Fresh Market Coffee"
```

Expected:

```text
Food/Groceries alternatives shown if scores close.
```

## Scenario 5 — backfill

User confirms:

```text
"Always categorize Uber as Transport"
```

Expected:

```text
dry-run count shown
execute updates old matching rows through lifecycle events
```

---

# 15. Definition of done

Categorization / merchant normalization is stable when:

```text
1. Every category/mapping write invalidates every relevant cache.
2. normalizedCanonicalName lookup is deterministic or explicitly ambiguous.
3. merchant stats update after committed create/update/delete.
4. semantic keyword collisions produce alternatives/review, not silent wrong category.
5. debug traces are persisted safely with privacy controls.
6. fuzzy search covers long-tail merchants or has indexed fallback.
7. category correction backfill is lifecycle-aware and opt-in.
8. context inference is timezone-aware and uses richer context.
9. direct DAO insert bypasses are removed or guarded.
10. tests cover cache, ambiguity, stats, semantic collision, trace, and backfill.
```

---

# Sources checked

- `CategorizationEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt

- `MerchantNormalizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/MerchantNormalizer.kt

- `SemanticKeywordMatcher.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/categorization/SemanticKeywordMatcher.kt

- `MerchantCategoryDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantCategoryDao.kt

- `MerchantCategoryRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/data/repository/MerchantCategoryRepository.kt

- `CategoryRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/data/repository/CategoryRepository.kt

- `MerchantNormalizationRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/data/repository/MerchantNormalizationRepository.kt

- `TransactionSideEffectDispatcher.kt`  
  https://github.com/panospao7/Cost-agregator/blob/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt

- `ContextualInferenceEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/domain/categorization/ContextualInferenceEngine.kt

- `CategoryDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/data/database/dao/CategoryDao.kt

- `MerchantCategory.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantCategory.kt

- `MerchantCanonical.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/ddfd8747ccc0420447fcc98ed68d3df056ec022b/app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantCanonical.kt