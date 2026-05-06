# Categorization / Merchant Normalization Debug Report

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static GitHub code review, not local execution.

## 1. Executive summary

This subsystem is important because it feeds:

```text
notification parsing
manual expense autocomplete
receipt-created expenses
bank statement reviews
email receipt expenses
analytics/category totals
budget category spend
merchant location/map
subscription detection
recommendation enrichment
```

Intended flow:

```text
raw merchant
→ MerchantRulesRepository.cleanMerchantName()
→ MerchantKeyGenerator / GreeklishNormalizer
→ MerchantNormalizer canonical/alias lookup
→ CategorizationEngine layers:
   exact dictionary
   canonical stripped name
   Greek/Greeklish variation
   fuzzy match
   semantic keyword
   context inference
   ML fallback through HybridExpenseClassifier
→ categoryId
→ TransactionLifecycleCoordinator
→ TransactionSideEffectDispatcher learns merchant→category mapping
```

The overall design is good: layered matching, Greeklish support, learned mappings, ML fallback, and debug traces exist.

But there are several correctness risks.

Highest-risk findings:

1. `MerchantNormalizationDao.linkAliasToCanonical()` can silently fail when the same `normalizedKey` already exists under a different raw name.
2. `MerchantCanonical.createdAt/updatedAt` and `MerchantAlias.createdAt` are usually left as `0L`.
3. Category name matching is case/display-name fragile.
4. Categorization cache invalidation is incomplete.
5. `MerchantCategoryDao.insert()` uses `IGNORE` but returns `Unit`, so conflicts are invisible.
6. `normalizedCanonicalName` is indexed but not unique, so lookups can be ambiguous.
7. Fuzzy matching only considers top 1000 merchants, so long-tail merchants duplicate easily.
8. Semantic keyword categories are hardcoded by name, not stable category IDs.
9. Auto-learning can reinforce bad categories without conflict/negative-feedback handling.
10. Tests are good at engine-unit level, but weak at DB-backed end-to-end category propagation.

Main recommendation:

> Create a single `MerchantCategorizationCoordinator` contract that owns normalization, category lookup, learned mapping, cache invalidation, and confidence/audit output.

---

## 2. Strengths

Existing good pieces:

- `CategorizationEngine` has layered strategy.
- `MerchantNormalizer` has canonical + alias tables.
- `MerchantKeyGenerator` is deterministic and shared.
- `GreeklishNormalizer` handles Greek → Latin.
- `MerchantCanonicalizer` strips business/location suffixes.
- `SemanticKeywordMatcher` has ordered weighted keyword entries.
- `HybridExpenseClassifier` uses dictionary first, then ML, then fallback.
- `TransactionSideEffectDispatcher` learns merchant-category patterns after creation.
- There are unit/stress tests for categorization and merchant normalizer components.
- `MerchantNormalizationDaoTest` exists.
- `CategoryDaoTest` exists.

So this subsystem is not untested. The gaps are mostly persistence contracts and cross-pipeline behavior.

---

# 3. Major findings

## Finding P0-1 — Alias linking can silently fail on `normalizedKey` conflict

`merchant_aliases` has unique indexes:

```text
rawName unique
normalizedKey unique
```

But `MerchantNormalizationDao.linkAliasToCanonical()` checks only:

```kotlin
val existing = getAliasByRawName(rawName)
```

If `rawName` is new but `normalizedKey` already exists, then:

```kotlin
insertAlias(...)
```

uses `OnConflictStrategy.IGNORE`, returns nothing to the transaction, and the link is silently skipped.

Example:

```text
rawName = "McDonald's"
normalizedKey = "mcdonalds"

later:
rawName = "MCDONALDS"
normalizedKey = "mcdonalds"
```

The second insert can be ignored because normalizedKey already exists, but occurrence count and canonical link are not updated.

### Impact

- aliases do not learn correctly,
- occurrence counts wrong,
- user-defined alias corrections can fail invisibly,
- normalization appears inconsistent,
- fuzzy tree/top merchants become inaccurate.

### Fix

Change DAO logic:

```kotlin
@Transaction
suspend fun linkAliasToCanonical(...) {
    val existingByRaw = getAliasByRawName(rawName)
    val existingByKey = getAliasByNormalizedKey(normalizedKey)

    val existing = existingByRaw ?: existingByKey
    if (existing != null) {
        updateAlias(existing.copy(
            rawName = existing.rawName, // preserve or update policy explicitly
            canonicalId = canonicalId,
            isUserDefined = isUserDefined || existing.isUserDefined,
            occurrenceCount = existing.occurrenceCount + 1,
            lastUsedAt = timestamp
        ))
    } else {
        val inserted = insertAlias(...)
        require(inserted > 0) { "Alias insert failed" }
    }
}
```

Also make `insertAlias()` return `Long` and check it everywhere.

Priority: highest.

---

## Finding P0-2 — Merchant timestamps are not set

Entities document:

```text
MerchantCanonical.createdAt/updatedAt must be set.
MerchantAlias.createdAt/lastUsedAt must be set.
0L = unset sentinel.
```

But `MerchantNormalizer.createNewMerchant()` creates:

```kotlin
MerchantCanonical(
  normalizedName = ...,
  searchKey = ...,
  categoryId = ...,
  totalOccurrences = 1
)
```

No `createdAt`, no `updatedAt`.

`linkAliasToCanonical()` creates `MerchantAlias` with `lastUsedAt = timestamp`, but not `createdAt`.

### Impact

- cleanup/retention policies are unreliable,
- sorting by recency impossible,
- analytics/debug timelines wrong,
- stale alias pruning may delete/keep wrong rows,
- migration/backup verification cannot trust timestamps.

### Fix

Set timestamps:

```kotlin
val now = timeProvider.now()
MerchantCanonical(
    ...,
    createdAt = now,
    updatedAt = now
)

MerchantAlias(
    ...,
    createdAt = timestamp,
    lastUsedAt = timestamp
)
```

Priority: highest.

---

## Finding P0-3 — Category name lookup is fragile

`SemanticKeywordMatcher` returns category names like:

```text
Food
Groceries
Transport
Shopping
Utilities
Subscriptions
```

`CategorizationEngine.getCategoryIdByName()` uses:

```kotlin
cachedCategoryNameToId = categories.associate { it.name to it.id }
cachedCategoryNameToId?.get(categoryName)
```

That is exact string matching.

But `CategoryRepository.addCategory()` normalizes new categories by doing:

```kotlin
val normalizedName = name.trim().lowercase()
Category(name = normalizedName, ...)
```

So user-created or migrated categories can be:

```text
food
groceries
shopping
```

while keyword matcher emits:

```text
Food
Groceries
Shopping
```

### Impact

Semantic categorization can fail even when a matching category exists with different case.

### Fix

Store lookup map normalized:

```kotlin
cachedCategoryNameToId =
    categories.associate { it.name.trim().lowercase() to it.id }

private suspend fun getCategoryIdByName(categoryName: String): Long? =
    cachedCategoryNameToId?.get(categoryName.trim().lowercase())
```

Also separate category display name from normalized key. Do not lowercase the display name unless UI wants that.

Priority: highest.

---

## Finding P0-4 — Categorization cache invalidation is incomplete

`CategorizationEngine` caches:

```text
merchant mappings
patterns set
category ID → name map
category name → ID map
```

for 5 minutes.

It invalidates when:

```text
learnMerchantCategory()
```

But category/mapping changes can happen elsewhere:

- default category seeding,
- category add,
- category rename/update,
- category delete,
- migration updates,
- merchant category DAO direct insert,
- category case normalization,
- user correction imports.

`CategoryRepository.addCategory()` invalidates the hybrid classifier category snapshot, but not the categorization engine cache.

### Impact

For up to 5 minutes:

- deleted category IDs can still be returned,
- semantic category lookup misses newly added categories,
- renamed categories show stale names,
- FK insert failures can happen if stale category ID is used,
- debug categorization shows stale data.

### Fix

Create one invalidation event path:

```kotlin
interface CategoryCatalogInvalidator {
    suspend fun invalidateCategoryCatalog(reason: String)
}
```

Call it from:

```text
CategoryRepository.add/update/delete
CategoryRepository.ensureDefaultCategories
MerchantCategoryRepository.insert/learn/delete
migrations/backfills if runtime
```

Or expose a `Flow` version from DAO and build cache from observed revision.

Priority: highest.

---

## Finding P0-5 — `MerchantCategoryDao.insert()` uses `IGNORE` but cannot report conflict

DAO comment says callers should check skipped insert, but method is:

```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insert(merchantCategory: MerchantCategory)
```

It returns `Unit`.

Same issue for `insertAll()`.

### Impact

- learned mapping conflicts are invisible,
- category corrections can appear successful but do nothing,
- duplicate canonical mappings are not detected,
- tests cannot assert row insertion vs ignored conflict.

### Fix

Change signatures:

```kotlin
suspend fun insert(merchantCategory: MerchantCategory): Long
suspend fun insertAll(merchantCategories: List<MerchantCategory>): List<Long>
```

Then handle:

```text
-1L = ignored
```

For learned corrections, prefer `UPSERT`/replace policy if user explicitly changed category.

Priority: highest.

---

## Finding P1-1 — `normalizedCanonicalName` lookup is ambiguous

`MerchantCategory` has:

```text
merchantPattern primary key
normalizedCanonicalName indexed, not unique
```

DAO has:

```kotlin
SELECT * FROM merchant_categories
WHERE normalizedCanonicalName = :normalizedCanonicalName
```

returning a single nullable entity.

If multiple merchant patterns canonicalize to the same value, result is undefined/unstable.

Example:

```text
"sklavenitis thessaloniki" → "sklavenitis"
"sklavenitis ae" → "sklavenitis"
```

If one maps Groceries and another maps Shopping by mistake, lookup can return either.

### Fix

Decide policy:

1. `normalizedCanonicalName` must be unique.
2. Or return all candidates and resolve by:
   - user-defined > system,
   - confidence,
   - timesUsed,
   - latest correction,
   - exact pattern match.

Recommended entity fields:

```text
source = DEFAULT / USER_CORRECTION / IMPORT / ML
updatedAt
isUserDefined
```

Priority: high.

---

## Finding P1-2 — Merchant normalizer fuzzy search only sees top 1000 merchants

`getOrBuildTree()` inserts:

```kotlin
repository.getTopMerchants(1000)
```

So merchants outside the top 1000 are invisible to fuzzy matching.

### Impact

Long-tail merchants can duplicate:

```text
"Small Cafe"
"Small Caffe"
```

if neither is in top 1000.

### Fix

Options:

- build BK-tree from all merchants if count is reasonable,
- use prefix-filtered DAO candidates,
- build multiple trees/shards,
- fallback to indexed prefix/contains search for long-tail.

Add telemetry:

```text
merchantCanonicalCount
bkTreeSize
fuzzySearchSkippedBecauseOutsideTopN
```

Priority: high.

---

## Finding P1-3 — Merchant stats are not consistently updated

`MerchantNormalizationRepository` exposes:

```kotlin
incrementMerchantStats(id, amount, timestamp)
```

But `MerchantNormalizer.normalize()` does not increment canonical stats on alias/exact/fuzzy hits.

New merchants start with:

```text
totalOccurrences = 1
```

but later usage seems mostly alias occurrence count, not canonical occurrence/spend.

### Impact

- `getTopMerchants()` is inaccurate,
- BK-tree top 1000 is based on stale canonical stats,
- merchant analytics/normalization ranking weak,
- fuzzy matching tie-breaks unreliable.

### Fix

After successful expense creation, update merchant canonical stats via post-commit hook:

```text
canonicalId
amount/effectiveAmount
timestamp
source
```

Do not update stats on mere preview/autocomplete classification; update only committed expenses.

Priority: high.

---

## Finding P1-4 — `autoCreate=false` returns placeholder with display name, not normalized key

`CategorizationEngine` calls:

```kotlin
merchantNormalizer.normalize(merchant, autoCreate = false)
```

If no canonical exists, `MerchantNormalizer` returns placeholder:

```kotlin
MerchantCanonical(normalizedName = cleaned, searchKey = key)
```

Then categorization uses:

```kotlin
normalized = lookupResult.canonical.normalizedName.lowercase()
```

not `searchKey`.

For Greek or punctuation-heavy names:

```text
normalizedName = "Σκλαβενίτης"
searchKey = "sklavenitis"
```

Matching then relies on Greeklish variation instead of the shared canonical key.

### Fix

Categorization should use both:

```kotlin
val displayName = lookup.canonical.normalizedName
val searchKey = lookup.canonical.searchKey
```

Matching order:

```text
exact merchantPattern by display normalized
exact by searchKey
canonical by searchKey
Greeklish variations
semantic
```

Better: define a single `MerchantIdentity` type:

```kotlin
data class MerchantIdentity(
    val raw: String,
    val displayName: String,
    val searchKey: String,
    val greeklishKey: String,
    val canonicalKey: String
)
```

Priority: high.

---

## Finding P1-5 — Auto-learning can reinforce mistakes

`TransactionSideEffectDispatcher` learns merchant-category pattern after every created expense with a category.

Receipt flows can additionally call:

```kotlin
hybridClassifier.learnFromCorrection(...)
```

which also calls:

```kotlin
categorizationEngine.learnMerchantCategory(...)
```

So the same row can train:

```text
merchant_categories dictionary
naive bayes classifier
```

even when category came from an uncertain parser, ML guess, or user did not explicitly confirm.

### Impact

- wrong category becomes persistent,
- future expenses auto-categorize wrong,
- ML and dictionary reinforce each other,
- difficult to debug because learned mapping has no source/confidence.

### Fix

Add learning policy:

```text
USER_CONFIRMED → strong learn
REVIEW_APPROVED → strong learn
MANUAL_ENTRY with explicit category → strong learn
AUTO_ACCEPT notification → weak/provisional learn
ML suggestion accepted implicitly → do not dictionary-learn until confirmed
RECEIPT parser low confidence → no learn
```

Extend `MerchantCategory`:

```text
source
confidence
isUserDefined
lastConfirmedAt
createdAt
updatedAt
negativeVotes
```

Priority: high.

---

## Finding P1-6 — Category corrections do not appear to update old expense rows consistently

Learning a merchant category improves future categorization, but old expenses with that merchant may remain in old category unless separate bulk update path runs.

There are bulk category/merchant update methods elsewhere, but Pipeline 2 found many direct DAO updates bypass lifecycle events.

### Fix

When user confirms:

```text
“Always categorize this merchant as X”
```

perform explicit lifecycle-aware backfill:

```text
update matching expenses?
future only?
ask user
```

If backfilling, route through lifecycle update API and write `BULK_UPDATED`.

Priority: medium-high.

---

## Finding P1-7 — Semantic keyword collisions are likely

`CategoryKeywords` includes overlapping terms:

```text
pharmacy → Shopping and Health
netflix → Entertainment and Subscriptions
mobile → Shopping, Utilities, Subscriptions
bar → Food/Entertainment ambiguity
market → Groceries/Food ambiguity
```

The matcher sorts by confidence, keyword length, match count, and category name. That is deterministic, but not necessarily correct.

### Fix

Add conflict policy:

```text
same keyword in multiple categories
→ require context or source package
→ return alternatives
→ lower confidence
→ route to review if top two close
```

Expose alternatives in `CategorizationResult`.

Priority: medium-high.

---

## Finding P1-8 — Context inference is too isolated

`ContextualInferenceEngine` is only used for likely surnames + amount/time.

But context categorization should also consider:

```text
source package
notification text
receipt item keywords
location/geocoded merchant type
payment method
transaction type
historical user corrections
```

Currently, `CategorizationEngine.categorizeWithContext()` has only:

```text
merchant
amount
timestamp
```

### Fix

Replace with:

```kotlin
data class CategorizationContext(
    val merchant: String,
    val amount: Double?,
    val timestamp: Long?,
    val source: ExpenseSource?,
    val packageName: String?,
    val notificationText: String?,
    val receiptItems: List<String>,
    val locationCategoryHint: String?,
    val transactionType: TransactionType?
)
```

Priority: medium.

---

## Finding P1-9 — Debug trace is good but not persisted

`debugCategorize()` returns layer results. Good.

But runtime classification does not persist:

```text
input merchant
normalized key
matched layer
confidence
alternatives
category chosen
learning action
```

### Fix

Add optional diagnostics table/ring buffer:

```text
CategorizationDecisionTrace
```

Store only sanitized merchant hash + preview if privacy requires.

Priority: medium.

---

# 5. Debugging checklist

## Merchant normalization

Check:

- [ ] raw merchant cleaned consistently,
- [ ] Greek/Greeklish transliteration stable,
- [ ] `MerchantKeyGenerator` key same across expense/dedupe/location,
- [ ] canonical merchant created once,
- [ ] aliases link by rawName and normalizedKey,
- [ ] alias conflict is not silent,
- [ ] timestamps set,
- [ ] canonical stats updated after committed expenses,
- [ ] fuzzy search includes long-tail merchants,
- [ ] user-defined aliases override auto aliases.

## Categorization

Check:

- [ ] exact mapping,
- [ ] canonical stripped mapping,
- [ ] Greeklish mapping,
- [ ] fuzzy mapping,
- [ ] semantic mapping,
- [ ] context mapping,
- [ ] ML fallback,
- [ ] alternatives returned when ambiguous,
- [ ] category name lookup case-insensitive,
- [ ] deleted/renamed categories do not return stale IDs,
- [ ] confidence thresholds route to review if low.

## Learning

Check:

- [ ] explicit user correction learns strongly,
- [ ] review approval learns,
- [ ] auto-accepted uncertain input does not overlearn,
- [ ] duplicate training is avoided,
- [ ] conflicts are recorded,
- [ ] learned mapping has source/confidence/timestamp,
- [ ] user can undo learned mapping.

## Persistence

Check:

- [ ] `MerchantCategoryDaoTest` exists,
- [ ] `MerchantNormalizationDaoTest` covers normalizedKey conflict,
- [ ] FK category cascade behavior tested,
- [ ] category rename/delete invalidates cache,
- [ ] backup/restore preserves mappings,
- [ ] import/export handles merchant/category mapping policy.

---

# 6. Recommended fix plan

## PR 1 — Fix alias conflict and timestamps

- Update `linkAliasToCanonical()` to check both rawName and normalizedKey.
- Make insert return values visible.
- Set `createdAt/updatedAt/lastUsedAt`.

Acceptance:

```text
McDonald's + MCDONALDS update one alias/canonical path, not silent no-op.
```

Priority: P0.

---

## PR 2 — Fix category lookup and cache invalidation

- Category name lookup should be case-insensitive.
- CategoryRepository add/update/delete should invalidate CategorizationEngine.
- MerchantCategoryRepository insert/delete should invalidate.

Acceptance:

```text
Adding category "food" lets semantic "Food" resolve immediately.
Deleting category cannot return stale categoryId.
```

Priority: P0.

---

## PR 3 — Fix MerchantCategoryDao conflict reporting

Change:

```kotlin
insert(): Long
insertAll(): List<Long>
```

Handle ignored conflicts explicitly.

Acceptance:

```text
learnMerchantCategory reports inserted/updated/conflict.
```

Priority: P0.

---

## PR 4 — Add learning policy

Only strong-learn from confirmed sources.

Acceptance:

```text
ML suggestion does not become permanent dictionary mapping unless user/review confirms.
```

Priority: P1.

---

## PR 5 — Make normalization/categorization DB-backed contract tests

Add:

```text
MerchantCategorizationDbContractTest
MerchantAliasConflictDbTest
CategoryCacheInvalidationTest
MerchantCategoryDaoTest
```

Priority: P1.

---

## PR 6 — Add categorization fed-DB scenario

See scenario below.

Priority: P1.

---

# 7. Tests to add

## `MerchantAliasNormalizedKeyConflictTest`

Seed:

```text
canonical McDonald's
alias rawName="McDonald's", normalizedKey="mcdonalds"
```

Then link:

```text
rawName="MCDONALDS", normalizedKey="mcdonalds"
```

Assert:

```text
no silent insert ignore
alias/canonical still points to expected canonical
occurrenceCount increments
lastUsedAt updates
```

---

## `MerchantTimestampsContractTest`

Assert:

```text
new canonical createdAt > 0
new canonical updatedAt > 0
new alias createdAt > 0
new alias lastUsedAt > 0
```

---

## `CategorySemanticLookupCaseInsensitiveTest`

Seed category:

```text
name = "food" or "FOOD"
```

Input:

```text
"coffee island"
```

Assert:

```text
SemanticKeywordMatcher returns Food
CategorizationEngine resolves category ID despite case
```

---

## `CategoryCacheInvalidationDbTest`

Seed:

```text
Food category + coffee mapping
```

Run categorize.

Then:

```text
delete/rename category
add new category
```

Assert:

```text
categorization result updates immediately, not after 5-minute expiry
```

---

## `MerchantCategoryDaoConflictTest`

Assert:

```text
insert duplicate merchantPattern returns ignored result
insert duplicate normalizedCanonicalName follows chosen policy
```

---

## `CategorizationLearningPolicyTest`

Cases:

```text
manual explicit category → strong learn
review approved category → strong learn
ML-only auto category → no dictionary learn
auto notification high confidence → provisional learn
user correction overrides previous mapping
```

---

## `MerchantLongTailFuzzyTest`

Seed more than 1000 canonicals.

Assert:

```text
merchant outside top 1000 can still fuzzy match
```

or document that it intentionally cannot.

---

## `EndToEndCategorizationScenarioTest`

Seed:

```text
categories Food/Groceries/Transport/Shopping
merchant dictionary defaults
Greek bank notification: ΣΚΛΑΒΕΝΙΤΗΣ 45.50
receipt merchant: Coffee Island
manual correction: "SKLAVENITIS THESS" → Groceries
```

Assert:

```text
notification categorizes as Groceries
receipt categorizes as Food
manual correction persists
future same merchant auto-categorizes
expense categoryId is valid
transaction event exists
analytics category totals use learned category
```

---

# 8. Suggested canonical scenario

## `categorization_merchant_learning_contract`

Seed:

```text
categories:
  Food
  Groceries
  Transport
  Shopping
  Uncategorized

default mappings:
  sklavenitis → Groceries
  coffee island → Food
```

Run:

```text
1. categorize "ΣΚΛΑΒΕΝΙΤΗΣ ΛΑΓΚΑΔΑ"
2. create notification expense with that merchant
3. user corrects "SKLAVENITIS LAGKADA" to Groceries
4. create second notification same merchant variant
5. create receipt "Coffee Island"
6. rename/delete a category and verify cache invalidation
```

Expected:

```text
Greek/Greeklish normalization produces stable merchantKey
canonical stripped variant maps to Groceries
learned mapping has source/confidence/timestamp
second expense uses learned category
Coffee Island maps to Food
no duplicate canonical merchants
no silent alias insert conflicts
category cache invalidates immediately
analytics category totals match final category IDs
```

---

# 9. Most likely real instability sources

Ranked:

1. **Silent alias conflict on `normalizedKey`.**
2. **Unset merchant timestamps.**
3. **Case-sensitive category-name lookup.**
4. **Stale categorization cache after category/mapping changes.**
5. **`MerchantCategoryDao.insert()` cannot report ignored conflicts.**
6. **Ambiguous `normalizedCanonicalName` lookup.**
7. **Fuzzy matching only top 1000 merchants.**
8. **Auto-learning from uncertain sources.**
9. **Overlapping semantic keywords with no ambiguity contract.**
10. **Lack of DB-backed end-to-end categorization scenario.**

---

# 10. Final recommendation

Stabilize this subsystem in this order:

```text
1. Fix alias linking by normalizedKey and rawName.
2. Set merchant canonical/alias timestamps.
3. Make category-name lookup normalized/case-insensitive.
4. Add explicit cache invalidation from category and merchant mapping writes.
5. Make MerchantCategoryDao insert conflicts observable.
6. Add learning-source/confidence metadata.
7. Add DB-backed categorization contract tests.
8. Add one fed-DB categorization scenario that reaches dashboard/analytics.
```

Guiding rule:

> Merchant normalization should produce one stable identity per real merchant, and categorization should produce one explainable category decision with confidence, source, and invalidation safety.

Second guiding rule:

> Learned categories should be trusted only when the source is trusted. Automatic guesses must not become permanent truth without a policy.

---

# Sources

- Dependency map:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- `CategorizationEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt

- `MerchantNormalizer.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/MerchantNormalizer.kt

- `HybridExpenseClassifier.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/HybridExpenseClassifier.kt

- `MerchantCanonicalizer.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/categorization/MerchantCanonicalizer.kt

- `GreeklishNormalizer.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/categorization/GreeklishNormalizer.kt

- `SemanticKeywordMatcher.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/categorization/SemanticKeywordMatcher.kt

- `CategoryKeywords.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/categorization/CategoryKeywords.kt

- `MerchantRulesRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/MerchantRulesRepository.kt

- `MerchantNormalizationRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/MerchantNormalizationRepository.kt

- `MerchantCategoryRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/MerchantCategoryRepository.kt

- `CategoryRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/CategoryRepository.kt

- `MerchantAlias.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantAlias.kt

- `MerchantCanonical.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantCanonical.kt

- `MerchantCategory.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantCategory.kt

- `MerchantNormalizationDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantNormalizationDao.kt

- `MerchantCategoryDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantCategoryDao.kt

- `CategoryDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/CategoryDao.kt

- `MerchantKeyGenerator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/util/MerchantKeyGenerator.kt

- `TransactionSideEffectDispatcher.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt

- Existing tests:  
  https://github.com/panospao7/Cost-agregator/tree/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/domain/categorization  
  https://github.com/panospao7/Cost-agregator/tree/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/domain/intelligence/ml  
  https://github.com/panospao7/Cost-agregator/tree/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/androidTest/java/com/yourname/expensetracker/data/database/dao