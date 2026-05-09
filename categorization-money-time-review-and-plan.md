# Review — Categorization / Merchant Normalization + Money / Time

Commits reviewed:

```text
c477c8bbdc13eea9acb6b7af07cffd49bbe81dfa
b7e310bb8e519afba68116054c5fa7245e72a508
```

Sources:

- https://github.com/panospao7/Cost-agregator/commit/c477c8bbdc13eea9acb6b7af07cffd49bbe81dfa
- https://github.com/panospao7/Cost-agregator/commit/b7e310bb8e519afba68116054c5fa7245e72a508

---

# 1. Executive verdict

## Categorization / Merchant Normalization

```text
Status: improved but not stable/clean
Recommended label: PARTIAL / beta-stable
```

Good progress:

```text
✅ MerchantCategoryDao comment corrected: IGNORE returns -1L, not 0.
✅ SemanticKeywordMatcher now returns KeywordMatchResult with ambiguity flag.
✅ TransactionSideEffectDispatcher now tries merchant stats update post-create.
✅ bulkUpdateCategory(categoryId → newCategoryId) was added.
✅ invalidateAllCaches() exists.
✅ MerchantNormalizer top-1000 fuzzy limit is documented as deferred.
✅ ContextualInferenceEngine injects TimeProvider.
```

But major issues remain:

```text
❌ invalidateAllCaches() exists but is not actually wired into all repository/category/seed write paths.
❌ semantic ambiguity is detected but mostly ignored by CategorizationEngine.
❌ decision trace is in-memory only, not persisted, and stores raw merchant text.
❌ C06 deterministic lookup uses “latest row” instead of resolving ambiguity.
❌ merchant stats update likely uses raw merchant instead of merchantKey/canonical key and does not handle update/delete deltas.
❌ bulk category backfill is unbounded and per-row, with no dry-run/limit/result contract.
❌ ContextualInferenceEngine still uses Calendar.getInstance().
```

So this engine is not fully clean.

---

## Money / Time primitives

```text
Status: not stable/clean
Recommended label: PARTIAL / foundation only
```

Good progress:

```text
✅ MoneyAmount rejects NaN/Infinity.
✅ domain.model.PeriodRange is deprecated.
✅ Money is deprecated in favor of MoneyAmount.
✅ CurrencyFormatter unsafe EUR-default methods are deprecated.
✅ MoneyAggregate exposes failedTransactionCount and failedBucketCount.
✅ Guard comments/messages improved.
```

But major issues remain:

```text
❌ PeriodKind.toPeriodRange() still delegates to TimePeriodUtils / legacy system-zone helpers.
❌ toPeriodRangeZoned() is effectively a stub: all periods fall into a generic 30-day range.
❌ MoneyAmount.fromBigDecimal() converts BigDecimal to Double, losing the precision it claims to preserve.
❌ MoneyAggregate.partial() still reports failure bucket count as “transactions.”
❌ LAST_7_DAYS ambiguity is documented, not fixed.
❌ week helpers are documented, not split.
❌ direct time guard was weakened with broad allowlisted engine files.
❌ CurrencyFormatter safe wrappers call deprecated unsafe methods internally.
❌ ZERO_EUR and default EUR paths still exist.
```

So Money/Time is definitely not final.

---

# 2. Commit `c477c8b` detailed evaluation

## C04 — cache invalidation

### What improved

`CategorizationEngine.invalidateAllCaches()` was added.

### Remaining problem

It is just a method. It is not a real central invalidation architecture.

The code still says:

```text
Remaining: all MerchantCategoryRepository/CategoryRepository/seed/direct DAO writes
must emit CategoryMappingChanged and invalidate every categorization cache.
```

That means C04 is still **PARTIAL**, not fixed.

### Required fix

Create a real `CategoryMappingWriter` and route all category/mapping writes through it.

---

## C06 — normalized canonical lookup ambiguity

### What improved

Added:

```kotlin
getCanonicalByNormalizedNameLatest()
```

with:

```sql
ORDER BY createdAt DESC LIMIT 1
```

### Remaining problem

This is deterministic, but not semantically safe.

If two canonical merchants share the same normalized name but map to different categories, picking “latest” can still be wrong.

### Required fix

Return all candidates and resolve:

```text
same category → resolved
different categories + clear confidence gap → resolved with warning
different categories + close scores → ambiguous/review
```

So C06 is still **PARTIAL/OPEN**.

---

## C08 — merchant stats

### What improved

`TransactionSideEffectDispatcher.dispatchOnCreated()` now calls:

```kotlin
merchantNormalizationRepository.incrementMerchantStats(...)
```

### Remaining problems

Likely issues:

```text
uses expense.merchant instead of merchantKey/searchKey normalization
uses expense.amount, not clearly effectiveAmount
does not handle update/delete deltas
does not recompute stats when merchant changes
does not prove post-commit rollback safety with tests
```

### Required fix

Use canonical merchant resolution by merchantKey/searchKey and update stats through event-driven create/update/delete logic.

---

## C12 — semantic keyword collisions

### What improved

`SemanticKeywordMatcher.findBestMatch()` returns:

```kotlin
KeywordMatchResult(
    bestMatch,
    alternatives,
    isAmbiguous
)
```

### Remaining problem

`CategorizationEngine` computes:

```kotlin
val isAmbiguous = result.isAmbiguous
```

but then still returns the best match normally.

So ambiguity is detected but not acted on.

### Required fix

Extend `CategorizationResult`:

```kotlin
val alternatives: List<CategorizationAlternative>
val requiresReview: Boolean
val ambiguityReason: String?
```

If ambiguous:

```text
return low-confidence result or UNKNOWN/review
do not confidently auto-categorize
```

---

## C14 — decision trace

### What improved

A ring buffer was added:

```kotlin
private val decisionTrace = ArrayDeque<String>(100)
```

### Remaining problems

```text
not persisted
not privacy-safe
stores raw merchant text
uses System.currentTimeMillis()
not connected to debug/export UI
not controlled by privacy/debug settings
```

### Required fix

Add DB-backed trace entity with redacted merchant and retention.

---

## C11 — category correction backfill

### What improved

Added:

```kotlin
bulkUpdateCategory(categoryId, newCategoryId)
```

that loops and calls `updateCategory()`.

### Remaining problems

```text
loads all rows with 0L..Long.MAX_VALUE
no dry-run
no limit/batching
no result object
no transaction-level consistency
may dispatch per-row side effects and flood systems
not merchant-specific backfill
```

### Required fix

Create a real `BackfillMerchantCategoryUseCase` with dry-run, limit, lifecycle updates, and batch result.

---

## C13 / C18 — context inference

### What improved

`TimeProvider` is injected.

### Remaining problem

The engine still uses:

```kotlin
Calendar.getInstance()
```

So the injection is not used for time-zone-safe inference.

### Required fix

Use:

```kotlin
Instant.ofEpochMilli(timestamp).atZone(appZone)
```

and expand `CategorizationContext`.

---

# 3. Commit `b7e310b` detailed evaluation

## M04 — zone-aware time

### What improved

Added:

```kotlin
toPeriodRangeZoned(...)
```

### Serious problem

The implementation is not real. It effectively does:

```kotlin
else -> PeriodRange(
    startInclusiveMillis = nowMillis - 30 days,
    endExclusiveMillis = nowMillis
)
```

for all period kinds.

That means:

```text
TODAY
THIS_WEEK
THIS_MONTH
THIS_YEAR
LAST_7_DAYS
```

all collapse to a generic trailing 30-day range if this method is used.

Also production `toPeriodRange()` still uses legacy `TimePeriodUtils`.

### Required fix

Implement a real `ZonedTimePeriodCalculator`.

---

## M02/M06 — MoneyAmount and Money

### What improved

`MoneyAmount` rejects NaN/Infinity.

Added:

```kotlin
MoneyAmount.fromBigDecimal(...)
```

### Serious problem

The BigDecimal factory does:

```kotlin
MoneyAmount(value.toDouble(), currency)
```

This loses precision and does not unify money types.

So M06 is not fixed.

### Required fix

Either:

```text
MoneyAmount stores BigDecimal
```

or:

```text
MoneyAmount stores minorUnits: Long
```

The current factory is only a compatibility shim.

---

## M09 — formatting

### What improved

Comments/deprecations explain locale sensitivity.

### Remaining problem

The “safe” wrappers call deprecated methods internally:

```kotlin
formatMoney() -> format()
formatMoneyCompact() -> formatCompact()
formatMoneyWithSign() -> formatWithSign()
```

Also `formatForExport()` exists, good, but UI/accounting/export contracts are not fully separated.

### Required fix

Implement three actual formatter paths:

```text
display(locale)
exportStable(Locale.US)
accounting(locale)
```

---

## M10 — direct time guard

### What changed

More files were allowlisted:

```text
GroupLifecycleCoordinator
GroupBalanceCalculator
BudgetVsActualEngine
DailyBucketEngine
AnalyticsInputAssembler
TaxEstimator
```

### Problem

This weakens the guard rather than fixing direct-time usage.

### Required fix

Remove broad allowlists after migrating those files to `TimeProvider` / `java.time`.

---

## M15 — MoneyAggregate.partial warning

### What improved

Comment explains problem.

### Remaining problem

Code still says:

```kotlin
"Total excludes ${failures.size} transaction(s)"
```

That is wrong because `failures.size` is bucket count, not transaction count.

### Required fix

Use:

```kotlin
val failedTx = failures.sumOf { it.transactionCount }
val failedBuckets = failures.size
```

---

# 4. Are the two engines stable now?

## Categorization / Merchant Normalization

```text
No.
```

It is functional and improved, but not stable/clean because cache invalidation, ambiguity handling, persistent trace, and stats correctness remain partial.

## Money / Time

```text
Definitely no.
```

This commit is mostly documentation and partial helpers. The core time bug is not fixed because `toPeriodRangeZoned()` is not a real implementation and is not the production path.

---

# 5. Implementation plan

## Phase C — Categorization / Merchant Normalization finalization

### PR-C1 — Real central invalidation

Implement:

```kotlin
CategoryMappingWriter
CategoryMappingEventBus
CategoryMappingChanged
```

Route these through writer:

```text
MerchantCategoryRepository.insert()
MerchantCategoryRepository.insertAll()
MerchantCategoryRepository.deleteAll()
CategoryRepository.addCategory()
CategoryRepository.mergeCategories()
CategoryRepository.deleteCategory()
default seeding
manual correction writes
```

Invalidate:

```text
CategorizationEngine cache
HybridExpenseClassifier snapshot
MerchantNormalizer BK-tree/cache
semantic keyword cache if dynamic later
```

Tests:

```text
CategoryMappingInsertInvalidatesAllCachesTest
CategoryMergeInvalidatesAllCachesTest
DefaultSeedingInvalidatesCategorizationCacheTest
DirectDaoBypassGuardTest
```

---

### PR-C2 — Canonical ambiguity resolver

DAO:

```kotlin
getCanonicalsByNormalizedName(name): List<MerchantCanonical>
```

Resolver:

```kotlin
sealed interface CanonicalResolution {
    data class Resolved(...)
    data class Ambiguous(...)
    data object NotFound
}
```

Policy:

```text
same category/source → resolved
different category + weak gap → ambiguous
different category + strong confidence → resolved with warning
```

Tests:

```text
CanonicalSingleMatchTest
CanonicalSameCategoryMultipleRowsResolvedTest
CanonicalConflictingRowsAmbiguousTest
CanonicalLatestDoesNotSilentlyWinTest
```

---

### PR-C3 — Semantic ambiguity affects categorization

Extend result:

```kotlin
data class CategorizationResult(
    ...
    val alternatives: List<CategorizationAlternative> = emptyList(),
    val requiresReview: Boolean = false
)
```

If `isAmbiguous`:

```text
requiresReview = true
confidence reduced
explanation mentions competing categories
```

Tests:

```text
SemanticClearWinnerAutoCategorizesTest
SemanticAmbiguousRequiresReviewTest
SemanticAlternativesReturnedTest
```

---

### PR-C4 — Merchant stats correctness

Implement canonical resolution:

```text
expense.merchantKey → canonical
alias → canonical
normalized merchant fallback
```

Use:

```text
effectiveAmount if stats mean user-paid spend
native amount + currency if stats are merchant-native
```

Support:

```text
create
update amount/merchant
delete
recompute
```

Tests:

```text
MerchantStatsUpdatedOnCreateTest
MerchantStatsDeltaOnAmountUpdateTest
MerchantStatsRecomputedOnMerchantChangeTest
MerchantStatsNotUpdatedOnRollbackTest
```

---

### PR-C5 — Persistent privacy-safe decision trace

Entity:

```kotlin
CategorizationDecisionTraceEntity(
    id,
    expenseId,
    merchantKey,
    rawMerchantRedacted,
    finalCategoryId,
    confidence,
    matchType,
    requiresReview,
    alternativesJson,
    traceJson,
    createdAt
)
```

Rules:

```text
no raw merchant by default
ring buffer max rows
retention policy
privacy setting controls persistence
```

Use `TimeProvider`, not `System.currentTimeMillis()`.

Tests:

```text
DecisionTraceInsertedTest
DecisionTraceRedactsMerchantTest
DecisionTraceRetentionPrunesOldRowsTest
DecisionTraceDisabledByPrivacyTest
```

---

### PR-C6 — Backfill category correction use case

Create:

```kotlin
BackfillMerchantCategoryUseCase
```

Command:

```kotlin
BackfillMerchantCategoryCommand(
    merchantKey,
    newCategoryId,
    dateRange?,
    onlyUncategorized,
    dryRun,
    limit
)
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

Use `TransactionLifecycleCoordinator.updateCategory()`.

Tests:

```text
BackfillDryRunDoesNotMutateTest
BackfillOnlyUncategorizedTest
BackfillLimitAppliedTest
BackfillWritesLifecycleEventsTest
```

---

### PR-C7 — Context inference java.time + richer context

Replace `Calendar`.

Use:

```kotlin
Instant.ofEpochMilli(timestamp).atZone(zoneId)
```

Create:

```kotlin
CategorizationContext(
    amount,
    timestamp,
    zoneId,
    dayOfWeek,
    hourOfDay,
    notificationSource,
    location?,
    recentCategoryDistribution?,
    recurringCandidate?,
    userCorrectionHistory?
)
```

Tests:

```text
ContextInferenceUsesProvidedZoneTest
ContextInferenceDstSafeTest
ContextInferenceNoCalendarGuardTest
```

---

## Phase M — Money / Time finalization

### PR-M1 — Real ZonedTimePeriodCalculator

Implement:

```kotlin
class ZonedTimePeriodCalculator(
    private val zoneId: ZoneId
)
```

Methods:

```text
today
thisWeek
lastWeek
thisMonth
lastMonth
thisQuarter
lastQuarter
thisYear
lastYear
lastNCalendarDaysIncludingToday
trailingDurationToNow
completeDays
```

Update:

```kotlin
PeriodKind.toPeriodRange(...)
```

to use the zoned calculator.

Delete or deprecate stub:

```kotlin
toPeriodRangeZoned()
```

Tests:

```text
TodayRangeUsesProvidedZoneTest
ThisMonthDstSafeTest
LastWeekIsoMondayStartTest
Last7CalendarDaysIncludesTodayTest
Trailing7DaysEndsAtNowTest
```

---

### PR-M2 — Period semantics cleanup

Add explicit kinds:

```text
LAST_7_CALENDAR_DAYS_INCLUDING_TODAY
TRAILING_7_DAYS_TO_NOW
LAST_7_COMPLETE_DAYS
LAST_30_CALENDAR_DAYS_INCLUDING_TODAY
TRAILING_30_DAYS_TO_NOW
LAST_30_COMPLETE_DAYS
```

Deprecate ambiguous:

```text
LAST_7_DAYS
LAST_30_DAYS
```

Tests:

```text
Last7CalendarEndsTomorrowStartTest
Trailing7EndsExactlyNowTest
Complete7EndsTodayStartTest
```

---

### PR-M3 — Week helpers split

Implement:

```kotlin
getIsoWeekNumber(timestamp, zoneId)
getIsoWeekYear(timestamp, zoneId)
getAppCalendarWeekNumber(timestamp, zoneId, firstDayOfWeek, minimalDays)
```

Tests:

```text
IsoWeekYearBoundaryTest
AppWeekSundayStartTest
AppWeekMondayStartTest
```

---

### PR-M4 — Canonical PeriodRange migration

Search and migrate imports:

```text
domain.model.PeriodRange → domain.core.time.PeriodRange
```

Add guard:

```text
No production import of domain.model.PeriodRange
```

Tests:

```text
NoOldPeriodRangeImportGuardTest
PeriodRangeHalfOpenEndExclusiveTest
```

---

### PR-M5 — MoneyAmount precision migration

Do not call current `fromBigDecimal()` fixed.

Choose one:

## Option A — BigDecimal

```kotlin
data class MoneyAmount(
    val amount: BigDecimal,
    val currency: CurrencyCode
)
```

## Option B — minor units

```kotlin
data class MoneyAmount(
    val minorUnits: Long,
    val currency: CurrencyCode,
    val exponent: Int
)
```

Short-term bridge:

```kotlin
MoneyAmount.ofMajor(String, currency)
MoneyAmount.ofMajor(BigDecimal, currency)
MoneyAmount.ofDoubleDeprecated(Double, currency)
```

Tests:

```text
MoneyAmountBigDecimalPreservesPrecisionTest
MoneyAmountRejectsNaNInfinityTest
MoneyAmountDifferentCurrencyAdditionFailsTest
MoneyAmountJpyExponentTest
MoneyAmountBtcExponentTest
```

---

### PR-M6 — Formatter split

Create:

```kotlin
MoneyDisplayFormatter
MoneyExportFormatter
MoneyAccountingFormatter
```

Rules:

```text
display = locale-aware
export = Locale.US, dot decimal, stable
accounting = parentheses for negatives
```

Tests:

```text
DisplayGreekLocaleTest
ExportStableAlwaysDotDecimalTest
AccountingNegativeParenthesesTest
JpyNoDecimalsTest
```

---

### PR-M7 — MoneyAggregate warning fix

Fix:

```kotlin
MoneyAggregate.partial()
```

Use:

```kotlin
val failedTx = failures.sumOf { it.transactionCount }
val bucketCount = failures.size
```

Warning:

```text
Total excludes X transaction(s) across Y currency bucket(s)
```

Tests:

```text
MoneyAggregatePartialUsesTransactionCountTest
MoneyAggregatePartialUsesBucketCountSeparatelyTest
```

---

### PR-M8 — Direct time guard hardening

Remove broad allowlist entries after migration.

Guard should fail on:

```text
System.currentTimeMillis()
Instant.now()
LocalDate.now()
Calendar.getInstance()
Date()
```

Allowed only in:

```text
TimeProvider
platform adapters
tests
migrations if explicitly allowed
```

Add seeded execution tests:

```text
DirectTimeGuardSeededViolationFailsTest
DirectTimeGuardAllowlistedProviderPassesTest
```

---

### PR-M9 — Raw money guard hardening

Flag public money fields:

```text
total: Double
amount: Double
price: Double
cost: Double
value: Double
fee: Double
```

unless explicitly allowed:

```kotlin
// RAW_MONEY_ALLOWED: Room persistence field
```

Tests:

```text
RawMoneyGuardPublicTotalFailsTest
RawMoneyGuardEntityAllowedWithReasonTest
```

---

### PR-M10 — Entity timestamp wrappers

Create:

```kotlin
CreatedAt
UpdatedAt
OccurredAt
```

Validate:

```text
createdAt > 0
updatedAt >= createdAt
occurredAt > 0
```

Use in domain mappers first; Room migration later.

Tests:

```text
CreatedAtRejectsZeroTest
UpdatedAtBeforeCreatedAtFailsTest
EntityMapperValidatesTimestampsTest
```

---

# 6. Minimum work before calling these engines stable

## Categorization minimum

```text
1. Real CategoryMappingWriter wired into all writes.
2. Semantic ambiguity affects CategorizationResult.
3. Decision trace persisted/redacted or explicitly marked debug-only.
4. Merchant stats uses canonical key and handles update/delete or recompute.
5. ContextualInferenceEngine stops using Calendar.
```

## Money / Time minimum

```text
1. Replace toPeriodRangeZoned stub with real zoned implementation.
2. Make production PeriodKind.toPeriodRange use zone-aware java.time.
3. Fix MoneyAggregate.partial warning.
4. Do not claim BigDecimal migration fixed while converting to Double.
5. Remove broad direct-time guard allowlists or add seeded tests proving guard behavior.
6. Split display/export formatting.
```

---

# 7. Final answer

Are these two engines stable and clean?

```text
No.
```

Are the commits useful?

```text
Yes, but they are mostly partial/foundation passes.
```

Recommended next move:

```text
Do not move on yet if these two engines must be stable.
Do one final Categorization PR and one final Money/Time PR using the minimum list above.
```

If you accept them as beta/partial:

```text
Categorization = beta-stable
Money/Time = not beta-stable yet because time periods can still be wrong
```

Money/Time needs the highest-priority fix first:

```text
real zone-aware PeriodKind range calculation
```