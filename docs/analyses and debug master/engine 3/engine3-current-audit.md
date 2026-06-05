# Engine 3 Current Audit — Categorization / Merchant Normalization

Target branch inspected: `fix/pipeline-1-5-local-issues`  
Mode: static GitHub inspection only.  
No Gradle, compile, KSP, Hilt, Room, lint, or tests were run.

## Self-review verdict

**YELLOW / RED-LEANING for shared-engine risk**

Engine 3 is improved, but I would **not** call it clean.

The good news:

- `MerchantNormalizer` now has conflict checks before alias linking.
- `MerchantCanonical.createdAt/updatedAt` are set on normal creation.
- `MerchantAlias.createdAt` is now set in DAO `linkAliasToCanonical`.
- `MerchantCategoryRepository` uses `DatabaseWriteBarrier`.
- `MerchantCategoryRepository.insert/deleteAll` invalidate categorization cache.
- `CategoryRepository.add/merge/delete` invalidate categorization and hybrid snapshots.
- Semantic keyword ambiguity is detected.
- Merchant canonical stats are wired into post-commit side effects.
- Tests exist for categorization and merchant normalization.

The bad news:

- Several fixes are still partial.
- Important public bypass paths remain.
- Auto-learning is still source-blind.
- Ambiguity does not reach downstream review routing.
- Merchant stats can double-count on updates and still use raw mixed-currency amount.
- `normalizedCanonicalName` is still ambiguous.
- Decision trace stores raw merchant names and uses wall clock.
- `HybridExpenseClassifier` still catches broad `Exception` around ML classification and can swallow cancellation.

The core issue is the same pattern as Engine 2:

> The infrastructure exists, but the safe contracts are not fully enforced across all call sites.

---

# Sources inspected

Architecture:

- `ENGINE_INTERACTION_MAP.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/docs/architecture/ENGINE_INTERACTION_MAP.md
- `CODEBASE_SEGMENTS.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/docs/architecture/CODEBASE_SEGMENTS.md

Engine files:

- `CategorizationEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt
- `MerchantNormalizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/MerchantNormalizer.kt
- `MerchantNormalizationRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/repository/MerchantNormalizationRepository.kt
- `MerchantNormalizationDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantNormalizationDao.kt
- `MerchantAlias.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantAlias.kt
- `MerchantCanonical.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantCanonical.kt
- `MerchantCategoryRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/repository/MerchantCategoryRepository.kt
- `MerchantCategoryDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantCategoryDao.kt
- `MerchantCategory.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantCategory.kt
- `CategoryRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/repository/CategoryRepository.kt
- `CategoryDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/database/dao/CategoryDao.kt
- `HybridExpenseClassifier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/HybridExpenseClassifier.kt
- `ExpenseClassifier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/ExpenseClassifier.kt
- `FeatureExtractor.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/FeatureExtractor.kt
- `SemanticKeywordMatcher.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/categorization/SemanticKeywordMatcher.kt
- `ContextualInferenceEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/categorization/ContextualInferenceEngine.kt
- `TransactionSideEffectPlanner.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt
- `TransactionSideEffectDispatcher.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt
- `NotificationProcessingPipeline.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- `ReviewQueueRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt

Tests inspected/listed:

- Categorization tests directory  
  https://github.com/panospao7/Cost-agregator/tree/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/categorization
- ML tests directory  
  https://github.com/panospao7/Cost-agregator/tree/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/intelligence/ml
- `MerchantNormalizationDaoTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/MerchantNormalizationDaoTest.kt

---

# 1. Engine scout

## Engine

Engine 3 — Categorization / Merchant Normalization.

Main components:

- `MerchantNormalizer`
- `MerchantNormalizationRepository`
- `MerchantNormalizationDao`
- `CategorizationEngine`
- `MerchantCategoryRepository`
- `MerchantCategoryDao`
- `HybridExpenseClassifier`
- `SemanticKeywordMatcher`
- `ContextualInferenceEngine`
- category mutation paths in `CategoryRepository`
- post-commit learning/stats in `TransactionSideEffectPlanner`

## Risk level

**High / critical**

From the engine interaction map:

- `MerchantNormalizer` affects:
  - notification dedupe
  - transaction dedupe
  - receipt matching
  - recurring matching
  - email ingestion
  - analytics merchant grouping

- `CategorizationEngine` affects:
  - notification auto-categorization
  - receipt item categorization
  - email ingestion
  - budget category totals

So this engine can regress many pipelines if changed broadly.

## Affected pipelines

| Pipeline / segment | Impact |
|---|---|
| Segment 3 — Notification Capture, Parsing & Review | suggested categories, auto-accept category, merchant normalization |
| Segment 4 — Receipt Scanning / lifecycle | receipt merchant matching, review |
| Segment 5 — AI Receipt Item Categorization | category suggestions / fallback categories |
| Segment 6 — Merchant Categorization | primary owner |
| Segment 7 — Recurring Expenses | merchant key and recurring matching |
| Segment 8 — Analytics & Insights | merchant/category grouping |
| Segment 9 — Core Expense Management | transaction create side effects |
| Segment 10 — Dashboard Totals | category/merchant rollups |
| Segment 11 — Email ingestion | merchant/category mapping if email path uses same classifier |
| Segment 16 — Currency indirectly | merchant stats currently raw amount/mixed currency |
| Segment 18 — Backup/restore | write barrier for metadata writes |
| Segment 26 — NLP search | merchant alias normalization/search |

## Schema/migration impact

Current audit does not require immediate schema changes.

Potential future schema changes:

- unique or deterministic policy for `merchant_categories.normalizedCanonicalName`
- source/provisional fields on `MerchantCategory`
- merchant stats bucket table if replacing raw `totalSpent`
- persistent sanitized categorization trace table
- alias variant table if preserving multiple raw display names for same normalized key

Given your DB rescue history, schema changes should be delayed and isolated.

## Hilt/DI impact

Potential Hilt risk if introducing:

- `CategoryMappingWriter`
- `CategorizationLearningPolicy`
- `CategorizationDecisionTraceWriter`
- source-authority policy injected into `TransactionSideEffectPlanner`

Avoid big Hilt rewiring in first fix slice.

---

# 2. Positive findings

## 2.1 Merchant canonical creation timestamps improved

`MerchantNormalizer.createNewMerchant()` sets `createdAt` and `updatedAt` using `timeProvider.now()`.

Status: good for normal creation path.

## 2.2 Alias creation timestamp improved in DAO link path

`MerchantNormalizationDao.linkAliasToCanonical()` now creates `MerchantAlias` with both:

```kotlin
createdAt = timestamp
lastUsedAt = timestamp
```

This fixes the specific old “alias createdAt=0 in link path” bug.

## 2.3 Write barrier exists on repositories

`MerchantNormalizationRepository` checks `DatabaseWriteBarrier` before canonical/alias writes.

`MerchantCategoryRepository` checks `DatabaseWriteBarrier` before mapping writes.

Good for backup/restore safety.

## 2.4 `MerchantCategoryDao.insert()` returns `Long`

DAO insert returns row ID or `-1L` on ignored conflict.

Good base for conflict-aware learning.

## 2.5 Cache invalidation improved

`MerchantCategoryRepository.insert()` and `deleteAll()` call:

```kotlin
categorizationEngineProvider.get().invalidateAllCaches()
```

`CategoryRepository.addCategory`, `mergeCategories`, and `deleteCategory` also invalidate categorization/hybrid snapshots.

This is improvement over old stale-cache state.

## 2.6 Semantic ambiguity detection exists

`SemanticKeywordMatcher.findBestMatch()` returns:

```kotlin
bestMatch
alternatives
isAmbiguous
```

`CategorizationResult` exposes `isAmbiguous` and `requiresReview`.

Good base, but propagation is incomplete.

## 2.7 Post-commit merchant stats are wired

`TransactionSideEffectPlanner.planCreated()` and `planUpdated()` now include:

```kotlin
makeMerchantCanonicalStatsAction(...)
```

So C08 is no longer “not wired at all.”

But implementation is still financially/statistically unsafe.

## 2.8 Tests exist

There are multiple tests for:

- categorization components
- stress tests
- merchant normalizer
- hybrid classifier
- DAO uniqueness

This is a better test base than Engine 1.

---

# 3. Old issue reconciliation

## C01 — Alias linking silently fails on conflict

Old tracker: `FIXED`  
Current status: **PARTIAL / bug remains**

### Evidence

`MerchantNormalizer.linkAliasToCanonical()` checks:

- existing canonical by normalized key
- existing alias by normalized key if pointing to different canonical

This is good.

But then it calls:

```kotlin
repository.linkAliasToCanonical(...)
```

The repository delegates to DAO:

```kotlin
dao.linkAliasToCanonical(...)
```

DAO still checks only raw name:

```kotlin
val existing = getAliasByRawName(rawName)
```

If rawName differs but normalizedKey already exists for the same canonical:

1. `MerchantNormalizer` allows it.
2. DAO sees no rawName match.
3. DAO tries `insertAlias`.
4. unique index on `normalizedKey` ignores insert.
5. no occurrence count update happens.
6. `MerchantNormalizer` reloads alias by normalizedKey and reports success.

### Impact

- repeated merchant variants are not counted
- lastUsedAt is not updated
- caller receives false success
- alias learning confidence/stats drift

### Decision

**Downgrade to partial / reopen bug**

### Needed tests

```text
linkAlias_sameNormalizedKey_sameCanonical_updatesOccurrenceCount()
linkAlias_sameNormalizedKey_sameCanonical_updatesLastUsedAt()
linkAlias_differentCanonical_returnsConflict()
repository_linkAlias_returnsUpdatedInsteadOfUnit()
```

---

## C02 — Merchant timestamps

Old tracker: `FIXED`  
Current status: **MOSTLY FIXED**

### Evidence

Fixed:

- `MerchantNormalizer.createNewMerchant()` sets canonical timestamps.
- DAO `linkAliasToCanonical()` sets alias `createdAt`.

Remaining gaps:

- `MerchantNormalizationRepository.insertAlias(alias)` does not normalize `createdAt`/`lastUsedAt` if caller passes sentinel zero.
- direct DAO insert paths remain public/test-accessible.

### Decision

**Mostly fixed / needs guard**

---

## C03 — Category name lookup case-sensitive

Old tracker: `FIXED`  
Current status: **MOSTLY FIXED**

### Evidence

`CategorizationEngine` caches:

```kotlin
categories.associate { it.name.trim().lowercase() to it.id }
```

and lookup uses:

```kotlin
categoryName.trim().lowercase()
```

Remaining design caveat:

`CategoryRepository.addCategory()` lowercases the display name before insert:

```kotlin
val normalizedName = name.trim().lowercase()
val category = Category(name = normalizedName, ...)
```

This prevents case drift but also loses display capitalization.

### Decision

**Mostly fixed**

---

## C04 — Categorization cache stale

Old tracker: `TODO ONLY`  
Current status: **PARTIAL**

### Evidence

Improved:

- `MerchantCategoryRepository.insert/deleteAll` invalidate.
- `CategoryRepository.add/merge/delete` invalidate.
- `CategorizationEngine.learnMerchantCategory()` invalidates via `invalidateCache()`.

Remaining problems:

1. `CategoryRepository.ensureDefaultCategories()` directly calls:
   ```kotlin
   merchantCategoryDao.insertAll(...)
   merchantCategoryDao.updateNormalizedCanonicalName(...)
   ```
   without invalidating `CategorizationEngine` at the end.

2. `invalidateAllCaches()` is non-suspend and uses:
   ```kotlin
   synchronized(this)
   ```
   while normal cache reads/writes use:
   ```kotlin
   cacheMutex.withLock
   ```
   These locks do not coordinate. A concurrent `getCacheData()` can still race with invalidation.

3. Direct DAO mutation paths remain exposed.

### Decision

**Partial**

### Needed tests

```text
ensureDefaultCategories_invalidatesCategorizationCache()
invalidateAllCaches_isMutexSafeWithConcurrentCategorize()
merchantCategoryInsert_invalidatesCacheImmediately()
```

---

## C05 — MerchantCategoryDao insert conflict

Old tracker: `FIXED`  
Current status: **PARTIAL**

### Evidence

`MerchantCategoryDao.insert()` returns `Long`.

But `MerchantCategoryRepository.insert()` still returns `Unit` and only logs conflict:

```kotlin
if (rowId <= 0L) Timber.w(...)
```

`CategorizationEngine.learnMerchantCategory()` then logs as if learned successfully regardless of conflict.

### Impact

User correction/auto-learning can appear successful while DB ignored the mapping.

### Decision

**Partial**

### Needed contract

Return a sealed result:

```kotlin
MerchantCategoryWriteResult.Inserted
MerchantCategoryWriteResult.Updated
MerchantCategoryWriteResult.Conflict
MerchantCategoryWriteResult.Ignored
```

---

## C06 — `normalizedCanonicalName` ambiguous

Old tracker: `TODO ONLY`  
Current status: **OPEN**

### Evidence

`MerchantCategory` has:

```kotlin
Index(value = ["normalizedCanonicalName"])
```

not unique.

`MerchantCategoryDao.getCategoryByNormalizedCanonical()` uses:

```sql
SELECT * FROM merchant_categories
WHERE normalizedCanonicalName = :normalizedCanonicalName
```

No `ORDER BY`, no deterministic resolution, no all-candidates return.

DAO comments still acknowledge ambiguity.

### Decision

**Open**

### Safer first fix

Before schema migration, make query deterministic:

```sql
ORDER BY confidence DESC, timesUsed DESC, merchantPattern ASC
LIMIT 1
```

Better long-term fix:

- add unique index after cleanup, or
- return all candidates and resolve by source/confidence/user-confirmed policy.

---

## C07 — Fuzzy search top 1000 merchants

Old tracker: `DEFERRED`  
Current status: **OPEN BY DESIGN**

### Evidence

`MerchantNormalizer.getOrBuildTree()` still uses:

```kotlin
repository.getTopMerchants(1000)
```

Comment says long-tail fuzzy is deferred.

### Decision

**Deferred / acceptable for now**

Do not fix early. Lower priority than correctness/learning issues.

---

## C08 — Merchant stats update

Old tracker: `TODO ONLY`  
Current status: **PARTIAL / risky**

### Evidence

Stats update is now wired in `TransactionSideEffectPlanner`.

But:

- on create: increments raw `expense.amount`
- on update: increments raw `expense.amount` again
- on delete: no decrement/recompute
- bulk update: skipped
- raw mixed-currency `MerchantCanonical.totalSpent: Double`

### Impact

- update double-counts merchant stats
- deleted expenses remain in merchant stats
- EUR/USD/GBP can be summed into one `totalSpent`
- top merchant/fuzzy ranking can drift

### Decision

**Partial, not clean**

### Recommended fix

Prefer recomputation over deltas:

```text
merchant stats = occurrence count + per-currency spend buckets
```

If that is too much, track occurrences only and stop using `totalSpent` for financial meaning.

---

## C09 — `autoCreate=false` placeholder display name

Old tracker: `FIXED`  
Current status: **MOSTLY FIXED**

### Evidence

`createPlaceholder(cleaned, key, catId)` returns:

```kotlin
MerchantCanonical(normalizedName = cleaned, searchKey = key)
```

This is better than returning a misleading normalized/display mismatch.

### Decision

**Mostly fixed**

---

## C10 — Auto-learning reinforces mistakes

Old tracker: `FIXED`  
Current status: **PARTIAL / still serious**

### Evidence

`HybridExpenseClassifier.learnFromCorrection()` has a gate and avoids overriding an existing different mapping from one correction.

Good.

But `TransactionSideEffectPlanner.makeMerchantCategoryLearningAction()` runs after every create/update if `expense.categoryId != null`:

```kotlin
merchantCategoryRepository.learnPattern(expense.merchant, expense.categoryId!!)
```

This is source-blind.

It learns from:

- notification auto-accept
- review approval
- manual entry
- CSV/import
- bank sync
- possibly OCR/email-created expenses

No policy distinguishes:

```text
USER_CONFIRMED
REVIEW_APPROVED
AUTO_ACCEPTED
ML_GUESSED
OCR_GUESSED
EMAIL_PARSED
BANK_IMPORTED
```

### Impact

A wrong auto-accepted notification can become a permanent merchant-category mapping.

### Decision

**Partial / reopen**

### Needed fix

Introduce:

```kotlin
CategorizationLearningPolicy
```

Strong learning allowed only for:

- manual user-confirmed category
- review-approved category
- explicit correction
- maybe repeated high-confidence same mapping

Auto sources should either:

- not learn, or
- create provisional/weak mapping.

---

## C11 — Category corrections update old rows

Old tracker: `DEFERRED`  
Current status: **OPEN**

### Evidence

`CategoryRepository.updateExpenseCategoryBulk()` is still a no-op stub.

`CategoryDao.mergeCategories()` directly updates many tables, including expenses, without per-expense `TransactionEvent` or side-effect dispatch.

### Impact

- “always categorize this merchant as X” does not backfill
- category merge can mutate historical expenses without transaction lifecycle events
- budget/analytics side effects may not be properly emitted

### Decision

**Open**

Do not fix first; needs lifecycle-aware bulk update design.

---

## C12 — Semantic keyword collisions

Old tracker: `TODO ONLY`  
Current status: **PARTIAL**

### Evidence

`SemanticKeywordMatcher.findBestMatch()` returns alternatives and ambiguity.

`CategorizationEngine` maps this to:

```kotlin
CategorizationResult(isAmbiguous = true, requiresReview = true)
```

But:

- `CategorizationResult` does not carry alternatives
- `HybridExpenseClassifier.classifyWithMerchantDictionary()` drops `isAmbiguous/requiresReview`
- `ClassificationResult` has no `requiresReview`
- `NotificationProcessingPipeline` only uses:
  ```kotlin
  classification.categoryId.takeIf { it > 0 }
  ```
  and does not route ambiguous classification to review

### Impact

Ambiguous semantic matches can still be treated as normal category suggestions.

### Decision

**Partial**

### Needed fix

Add to `ClassificationResult`:

```kotlin
requiresReview: Boolean
isAmbiguous: Boolean
alternatives: List<CategoryScore>
classificationReason: String?
```

Then pipeline should downgrade/route ambiguous classification appropriately.

---

## C13 — Context inference too isolated

Old tracker: `DEFERRED`  
Current status: **OPEN / partial internals exist**

### Evidence

`ContextualInferenceEngine.inferFromContext()` accepts:

```kotlin
amount
timestamp
dayOfWeek
notificationSource
```

But `CategorizationEngine.categorizeWithContext()` signature only accepts:

```kotlin
merchant
amount
timestamp
```

It does not pass package/source/text/location/payment method/transaction type.

`NotificationProcessingPipeline` has package/text, but calls `HybridExpenseClassifier`, which calls `CategorizationEngine.categorize(merchantName)` without those context signals.

### Decision

**Open**

### Needed contract

Introduce:

```kotlin
CategorizationContext(
    source,
    packageName,
    notificationTitle,
    notificationText,
    receiptItems,
    transactionType,
    paymentMethod,
    locationHint,
    timestamp,
    amount
)
```

But do not do this as first PR; broad pipeline impact.

---

## C14 — Debug trace persistence/privacy

Old tracker: `TODO ONLY`  
Current status: **OPEN / privacy + concurrency risk**

### Evidence

`CategorizationEngine.traceDecision()` stores:

```kotlin
"${System.currentTimeMillis()}|$merchant|$category|$method"
```

Problems:

- raw merchant names stored
- direct wall clock
- `ArrayDeque` not protected by mutex
- not privacy-governed
- not durable
- `getRecentDecisions()` exposes raw entries

### Decision

**Open**

### Safer near-term fix

- use `timeProvider.now()`
- hash merchant key
- store redacted preview only if debug privacy allows
- guard with mutex or synchronized
- avoid raw strings

Longer-term:

- use `PipelineDiagnosticEvent` or sanitized `CategorizationDecisionTrace` table.

---

# 4. New/current issues found

## E3-NOW-001 — `invalidateAllCaches()` uses a different lock from cache reads

Severity: **P1_HIGH**

### Evidence

Reads/writes in `getCacheData()` use:

```kotlin
cacheMutex.withLock
```

But `invalidateAllCaches()` uses:

```kotlin
synchronized(this)
```

### Impact

Concurrent categorization and invalidation can observe inconsistent cache fields.

### Fix

Make `invalidateAllCaches()` suspend and use `cacheMutex.withLock`.

If non-suspend callers need it, create a small invalidator component with coroutine-safe state or `AtomicReference<CacheData?>`.

---

## E3-NOW-002 — `HybridExpenseClassifier` can swallow coroutine cancellation

Severity: **P2_MEDIUM**

### Evidence

It has:

```kotlin
try {
    val mlResults = nbClassifier.classify(features)
    ...
} catch (e: Exception) {
    Timber.w(e, "ML classifier failed, using fallback")
}
```

No explicit `CancellationException` rethrow.

### Impact

A cancelled classification coroutine can return fallback classification instead of cancelling.

### Fix

```kotlin
catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ...
}
```

---

## E3-NOW-003 — Ambiguous categorization is ignored by notification pipeline

Severity: **P1_HIGH**

### Evidence

`NotificationProcessingPipeline` gets classification and only reads:

```kotlin
classification.categoryId.takeIf { it > 0 }
```

No ambiguity/review flag is available on `ClassificationResult`.

### Impact

A semantic ambiguous result can become a suggested category and possibly auto-accepted if routing is otherwise high-confidence.

### Fix

Propagate `requiresReview` from `CategorizationResult` to `ClassificationResult`, then use it in notification/review routing.

---

## E3-NOW-004 — Post-commit learning happens on both create and update without source authority

Severity: **P1_HIGH**

### Evidence

`planCreated()` and `planUpdated()` both include merchant category learning.

On update, any category-bearing expense can learn/relearn.

### Impact

- update may reinforce stale/wrong category
- auto sources become dictionary truth
- repeated edits can inflate confidence/timesUsed if later implemented

### Fix

Gate learning by:

- source
- update kind
- whether category changed
- whether user confirmed

**Status: ✅ FIXED (PR5)** — `SourceLearningPolicy` implemented with `isTrustedForLearning()` enum and String overloads. `TransactionSideEffectPlanner.planCreated()` and `planUpdated()` now conditionally include `makeMerchantCategoryLearningAction` only when `SourceLearningPolicy.isTrustedForLearning(source)` is true. For updates, `TransactionUpdateKind.involvesCategoryChange()` further restricts learning to `FULL` and `CATEGORY_ONLY` kinds. Production source strings (`USER_EDIT`, `SYSTEM`, etc.) are explicitly mapped.

---

## E3-NOW-005 — Merchant stats update on update is additive, not delta/recompute

Severity: **P1_HIGH**

### Evidence

`makeMerchantCanonicalStatsAction()` calls:

```kotlin
incrementMerchantStats(id, expense.amount, expense.date)
```

for update as well as create.

### Impact

Updating an expense doubles merchant occurrences/spend.

### Fix

Do not increment on update. Either:

- recompute stats from expenses, or
- track previous snapshot and apply delta.

---

## E3-NOW-006 — Merchant stats use raw `Expense.amount`

Severity: **P1_HIGH**

### Evidence

Stats action passes:

```kotlin
amount = expense.amount
```

`MerchantCanonical.totalSpent` is `Double` with no currency.

### Impact

Mixed-currency merchant spend is wrong.

### Fix

Track occurrence count separately. For spend, use per-currency buckets or MoneyAggregate-backed stats.

---

## E3-NOW-007 — `CategoryRepository.ensureDefaultCategories()` bypasses repository-level mapping insert

Severity: **P1_MEDIUM/HIGH**

### Evidence

It directly uses:

```kotlin
merchantCategoryDao.insertAll(merchantEntities)
merchantCategoryDao.updateNormalizedCanonicalName(...)
```

This bypasses:

- `MerchantCategoryRepository`
- write result checking
- cache invalidation
- conflict reporting

It does check `DatabaseWriteBarrier` at method start, so restore safety is okay.

### Fix

Use repository/central writer or explicitly invalidate categorization cache after seeding/backfill.

---

## E3-NOW-008 — Category display name is lowercased on creation

Severity: **P2_MEDIUM**

### Evidence

`CategoryRepository.addCategory()` inserts:

```kotlin
Category(name = normalizedName, ...)
```

where `normalizedName = name.trim().lowercase()`.

### Impact

User-created category `"Dining Out"` becomes `"dining out"`.

This is not a categorization correctness bug, but UI/category identity behavior is rough.

### Fix

Store display name trimmed, enforce uniqueness via normalized/collation.

---

## E3-NOW-009 — `FeatureExtractor` still uses `Calendar`

Severity: **P2_MEDIUM**

### Evidence

`FeatureExtractor` imports and uses `java.util.Calendar`.

### Impact

Time features depend on system timezone and older Calendar behavior.

### Fix

Use `java.time` with explicit zone policy. This belongs partly to Engine 5, but affects Engine 3 ML features.

---

# 5. Current issue list

## P1 / high issues

| ID | Title |
|---|---|
| C01 / E3-NOW-001 | Alias linking still silently no-ops for same normalizedKey/same canonical |
| C04 / E3-NOW-001 | Cache invalidation still not safely coordinated |
| C05 | MerchantCategory insert conflict result is ignored by repository/engine |
| C06 | `normalizedCanonicalName` lookup remains ambiguous |
| C08 / E3-NOW-005/006 | Merchant stats update is additive/raw-money/mixed-currency |
| C10 / E3-NOW-004 | Auto-learning remains source-blind | ✅ FIXED — PR5: SourceLearningPolicy gates learning by source authority and update kind |
| C11 | Category correction/backfill is no-op or lifecycle-bypassing |
| C12 / E3-NOW-003 | Ambiguous categorization does not reach review routing |
| C14 | Decision trace stores raw merchant names and wall-clock timestamp |
| E3-NOW-007 | Default merchant seeding bypasses safe mapping writer/invalidation |

## P2 issues

| ID | Title |
|---|---|
| C02 | Direct alias insert can still persist zero timestamps |
| C07 | BK-tree fuzzy matching limited to top 1000 |
| C13 | Context inference lacks full context object |
| E3-NOW-002 | `HybridExpenseClassifier` swallows cancellation |
| E3-NOW-008 | User category display names are lowercased |
| E3-NOW-009 | `FeatureExtractor` still uses Calendar |

---

# 6. Recommended fix order

## PR1 — Alias linking contract and timestamp hardening

### Closes

- C01
- C02 residual
- E3-NOW-001 alias no-op part

### Files

```text
MerchantNormalizer.kt
MerchantNormalizationRepository.kt
MerchantNormalizationDao.kt
MerchantAlias.kt tests
MerchantNormalizationDaoTest.kt
MerchantNormalizerTest.kt
```

### Implementation

1. Move rawName + normalizedKey conflict/update logic into repository or DAO transaction.
2. Make repository `linkAliasToCanonical()` return result, not `Unit`.
3. If normalizedKey exists for same canonical:
   - update `occurrenceCount`
   - update `lastUsedAt`
   - preserve `createdAt`
4. If normalizedKey exists for different canonical:
   - return conflict
5. Normalize timestamps for `insertAlias`.

### Tests

```text
linkAlias_sameNormalizedKey_sameCanonical_updatesCount()
linkAlias_sameNormalizedKey_sameCanonical_updatesLastUsedAt()
linkAlias_sameNormalizedKey_differentCanonical_returnsConflict()
linkAlias_newAlias_setsCreatedAt()
repository_linkAlias_returnsCreatedUpdatedConflict()
```

### Risk

Medium. Affects dedupe, receipt matching, recurring matching.

No schema required.

---

## PR2 — Cache invalidation and mapping insert contract

### Closes

- C04
- C05
- E3-NOW-007

### Files

```text
CategorizationEngine.kt
MerchantCategoryRepository.kt
CategoryRepository.kt
MerchantCategoryDao.kt
tests
```

### Implementation

1. Make `invalidateAllCaches()` suspend and use `cacheMutex`.
2. Create a single mapping writer or invalidator path.
3. Ensure `ensureDefaultCategories()` invalidates after:
   - merchant dictionary insert
   - normalized canonical backfill
4. Change `MerchantCategoryRepository.insert()` to return a sealed result.
5. Change `CategorizationEngine.learnMerchantCategory()` to log/return actual result, not false success.

### Tests

```text
ensureDefaultCategories_invalidatesCategorizationCache()
merchantCategoryInsertConflict_reportsConflict()
learnMerchantCategory_conflictDoesNotLogSuccess()
concurrentCategorizeAndInvalidate_noPartialCacheState()
```

### Risk

Medium. No schema required.

---

## PR3 — Learning authority and ambiguity propagation

### Closes

- C10
- C12
- E3-NOW-003
- E3-NOW-004

### Files

```text
ClassificationResult.kt / ExpenseClassifier.kt
HybridExpenseClassifier.kt
CategorizationEngine.kt
TransactionSideEffectPlanner.kt
NotificationProcessingPipeline.kt
ReviewQueueRepository.kt
tests
```

### Implementation

1. Add to `ClassificationResult`:
   ```kotlin
   isAmbiguous: Boolean
   requiresReview: Boolean
   alternatives: List<CategoryScore>
   reason: String?
   ```
2. Preserve ambiguity from `CategorizationEngine`.
3. Add `CategorizationLearningPolicy`.
4. Strong-learn only for:
   - review-approved
   - manual user-confirmed
   - explicit user correction
5. Do not strong-learn from:
   - notification auto-accept
   - OCR guess
   - email parse guess
   - ML-only guess
6. Notification pipeline should downgrade ambiguous category suggestions to review or mark `PendingReview`.

### Tests

```text
semanticAmbiguity_reachesClassificationResult()
ambiguousNotificationClassification_routesToReviewOrMarksRequiresReview()
autoAcceptedNotification_doesNotStrongLearn()
reviewApprovedExpense_strongLearns()
manualCorrection_strongLearns()
```

### Risk

High. Affects notification/review pipelines. Do after PR1/PR2.

---

## PR4 — Merchant stats correctness

### Closes

- C08
- E3-NOW-005
- E3-NOW-006

### Files

```text
TransactionSideEffectPlanner.kt
MerchantNormalizationRepository.kt
MerchantNormalizationDao.kt
MerchantCanonical.kt maybe
tests
```

### Implementation options

Safer no-schema option:

1. Stop updating raw `totalSpent`.
2. Only increment occurrence count on create.
3. Do not increment stats on update.
4. Add TODO for MoneyAggregate-backed merchant stats.

Better but schema-heavy option:

- create merchant stats bucket table:
  ```text
  merchant_canonical_stats(currency, amount, count)
  ```

Recommended first: no-schema safety fix.

### Tests

```text
merchantStats_createIncrementsOnce()
merchantStats_updateDoesNotDoubleCount()
merchantStats_deleteDoesNotClaimRecomputedAccuracy()
merchantStats_rawTotalSpentNotUsedForFinancialDisplay()
```

### Risk

Medium/high.

---

## PR5 — Category backfill and merge lifecycle

### Closes

- C11

### Files

```text
CategoryRepository.kt
CategoryDao.kt
TransactionLifecycleCoordinator.kt
TransactionSideEffectPlanner.kt
tests
```

### Implementation

1. Do not directly bulk-update expenses without lifecycle events if user-facing.
2. Implement explicit user choice:
   - future only
   - backfill matching existing expenses
3. Backfill through lifecycle-aware bulk update:
   - write bulk event
   - dispatch side effects once
   - avoid per-row storm if many rows

### Tests

```text
updateExpenseCategoryBulk_futureOnly_doesNotChangeOldRows()
updateExpenseCategoryBulk_backfill_updatesMatchingExpenses()
categoryBackfill_writesBulkLifecycleEvent()
categoryMerge_invalidatesBudgetAndAnalyticsSideEffects()
```

### Risk

High. Do later.

---

## PR6 — Diagnostics, privacy, cancellation, and guards

### Closes

- C14
- E3-NOW-002
- E3-NOW-009 partial
- raw DAO guard issues

### Files

```text
CategorizationEngine.kt
HybridExpenseClassifier.kt
FeatureExtractor.kt
static guard tests
docs
```

### Implementation

1. Rethrow `CancellationException` in `HybridExpenseClassifier`.
2. Replace `System.currentTimeMillis()` in trace with `timeProvider.now()`.
3. Do not store raw merchant in trace:
   - use merchant key hash
   - optional redacted preview
4. Protect trace buffer with mutex/synchronized.
5. Add static guard against direct DAO mutators from production code.
6. Consider java.time migration in `FeatureExtractor`.

### Tests

```text
hybridClassifier_cancellationRethrows()
decisionTrace_doesNotStoreRawMerchant()
decisionTrace_usesTimeProvider()
noProductionCallToRawMerchantCategoryDaoMutators()
featureExtractor_usesExplicitEventTime()
```

### Risk

Low/medium.

---

## PR7 — `normalizedCanonicalName` policy

### Closes

- C06

### Option A — no-schema deterministic fix

Change DAO query:

```sql
ORDER BY confidence DESC, timesUsed DESC, merchantPattern ASC
LIMIT 1
```

and document ambiguity.

### Option B — schema fix

Add unique index after cleanup:

```sql
CREATE UNIQUE INDEX ...
```

Given your recent DB recovery, do **Option A first**. Do not add schema until baseline v145 is stable.

### Tests

```text
normalizedCanonical_duplicateCandidates_resolveDeterministically()
normalizedCanonical_tieBreaksByMerchantPattern()
```

### Risk

Option A: low/medium.  
Option B: high due migration.

---

# 7. Pipeline regression matrix

## Notification pipeline

Must verify after PR1/PR3:

- merchant normalization still produces stable `merchantKey`
- auto-accepted notifications still create expenses
- ambiguous categorization does not silently auto-accept wrong category
- pending review receives category suggestion + review flag if ambiguous
- duplicate detection still uses same merchant keys

## Review queue

Must verify:

- approving review still creates expense
- user-edited merchant still learns alias
- user-edited category can strong-learn if policy allows
- rejected review does not learn

## Receipt / receipt matching

Must verify:

- merchant alias matching still resolves receipt merchant to expense merchant
- alias conflict does not break match scoring
- raw OCR merchant variants still normalize correctly

## Recurring expenses

Must verify:

- recurring matching still uses stable merchant key
- alias learning does not break existing recurring rule matching
- fuzzy matching changes do not over-merge distinct merchants

## Email ingestion

Must verify:

- email merchant/category suggestions still work
- email auto-created expenses do not strong-learn unless confirmed

## Analytics/dashboard

Must verify:

- merchant grouping remains stable
- category grouping remains stable
- merchant stats are not displayed as raw mixed-currency truth
- top merchants do not double count after edit/delete

## Backup/restore

Must verify:

- merchant/category mapping writes are blocked during restore
- no direct DAO write bypasses write barrier in normal production paths

---

# 8. Static checks performed

Checked statically:

- merchant alias/canonical creation paths
- alias conflict/update path
- DAO uniqueness/index assumptions
- repository write barriers
- category/mapping cache invalidation paths
- default category/mapping seed path
- semantic ambiguity propagation
- hybrid classifier dictionary/ML/fallback path
- notification pipeline classification usage
- review approval alias learning path
- transaction side-effect learning/stats actions
- merchant stats raw amount usage
- category merge/backfill path
- decision trace privacy/time source
- test directory coverage

Not fully checked:

- email ingestion categorization path due source lookup uncertainty
- every receipt matching call site
- every analytics/dashboard merchant stats consumer
- compile/Hilt graph
- Room schema validation
- all tests

---

# 9. Known compile risks for future fixes

Potential risks:

- changing `ClassificationResult` constructor requires updating many tests/call sites
- making `invalidateAllCaches()` suspend requires updating callers
- changing repository `linkAliasToCanonical()` return type affects `MerchantNormalizer`
- adding learning policy to `TransactionSideEffectPlanner` may need Hilt binding
- adding category backfill lifecycle path may create circular dependencies
- moving raw DAO writes behind a writer may require DI cleanup
- schema changes for uniqueness/stats/trace require migration tests

---

# 10. Human validation commands

Do not run during individual static slices if following your orchestrator rule.

After all Engine 3 PRs are finalized:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

If schema/migration changes are introduced:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Hilt/DI changes are introduced:

```bash
./gradlew :app:assembleDebug --stacktrace
```

---

# 11. Final conclusion

Engine 3 is **substantially improved**, but it is **not clean**.

The most dangerous remaining items are:

1. alias same-normalized-key same-canonical no-op
2. source-blind auto-learning
3. ambiguity not reaching review routing
4. merchant stats double-counting/raw-mixed-currency
5. unsafe/non-mutex cache invalidation
6. raw merchant decision trace

Best first PR:

```text
PR1: Alias linking contract and timestamp hardening
```

Why first:

- no schema required
- affects core merchant identity
- protects dedupe/receipt/recurring/analytics
- closes a real silent-no-op bug
- manageable test scope

Second PR should be:

```text
PR2: Cache invalidation and mapping insert contract
```

Do not start with learning policy or schema migration. Those have broader pipeline blast radius.

Verdict: **YELLOW / RED-LEANING due shared-engine risk.**