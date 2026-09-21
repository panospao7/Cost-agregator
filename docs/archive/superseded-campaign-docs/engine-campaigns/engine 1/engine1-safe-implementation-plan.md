# Engine 1 Safe Implementation Plan

Engine: **Warranty / Subscription / Location / NLP**

Goal:

> Fix Engine 1 issues without regressing already-clean pipelines.

Rule:

> Do not do one huge “engine cleanup” PR. Do small contract-focused slices, each with engine tests + affected pipeline tests.

Sources used:

- Engine impact map:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/docs/architecture/ENGINE_INTERACTION_MAP.md
- Codebase segments:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/docs/architecture/CODEBASE_SEGMENTS.md
- `WarrantyTrackerRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt
- `SubscriptionManagerEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/subscription/SubscriptionManagerEngine.kt
- `SmartBillNegotiationEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/negotiation/SmartBillNegotiationEngine.kt
- `CloudQueryInterpretationService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt
- `ScannedReceipt.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt
- `ReceiptDocumentType.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptDocumentType.kt
- `ReceiptSourceType.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptSourceType.kt
- `ReceiptProcessingStatus.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptProcessingStatus.kt

---

# Phase 0 — Safety and baseline

Before Engine 1 work:

1. Keep your recovered DB backup and CSV.
2. Keep rescue disabled.
3. Tag current working app:

```bash
git tag working-v145-before-engine1
git push origin working-v145-before-engine1
```

4. Create branch:

```bash
git checkout -b engine1-safe-hardening
```

Do not change database schema in the first slice.

---

# Phase 1 — Engine 1 contract map

## Affected segments

From `CODEBASE_SEGMENTS.md`, Engine 1 touches:

| Area | Segment |
|---|---|
| Warranty/subscription/bill negotiation | Segment 34 |
| Receipt warranty side effects | Segment 4 |
| Recurring subscriptions | Segment 7 |
| Forecast/budget effects from subscriptions | Segments 1, 2, 13 |
| Location/map/NLP location | Segments 19, 26 |
| AI/cloud query | Segment 20 |
| Backup/write barrier | Segment 18 |
| Dashboard totals impacted by subscription values | Segment 10 |

## Pipeline regression rule

For every engine change, verify:

1. valid old user flow still works
2. invalid input is rejected clearly
3. no silent mixed-currency/raw-money behavior gets worse
4. no write happens during restore/backup maintenance
5. cloud paths remain privacy-gated/redacted
6. no side effect happens before DB commit
7. no Room migration unless isolated in its own PR

---

# Recommended implementation slices

## PR1 — No-schema hardening

### Goal

Fix the safest issues first without touching Room schema or Hilt graph.

### Issues covered

- warranty `addWarrantyIgnoreConflicts()` timestamp gap
- manual warranty placeholder metadata gap
- subscription validation gap
- subscription price-change validation gap
- cloud query `CancellationException` swallowing

### Risk

Low to medium.

### Affected pipelines

| Change | Pipelines |
|---|---|
| warranty timestamp normalization | Receipt/warranty, backup/export |
| manual placeholder metadata | Receipt lifecycle, warranty, return-window skip logic |
| subscription validation | recurring, budget, forecast, dashboard |
| cloud cancellation rethrow | AI assistant, workers/coroutines |

---

## PR1.A — Warranty timestamp normalization

### Current problem

`addWarranty()` normalizes `createdAt`/`updatedAt`, but `addWarrantyIgnoreConflicts()` inserts the object directly.

### File

```text
WarrantyTrackerRepository.kt
```

### Change

Inside `addWarrantyIgnoreConflicts()`:

```kotlin
val now = timeProvider.now()
val warrantyWithTimestamps = warranty.copy(
    createdAt = if (warranty.createdAt == 0L) now else warranty.createdAt,
    updatedAt = if (warranty.updatedAt == 0L) now else warranty.updatedAt
)
val id = warrantyDao.insertWarrantyIgnore(warrantyWithTimestamps)
```

Use `warrantyWithTimestamps` for audit metadata too.

### Tests

Add/update:

```text
WarrantyTrackerRepositoryTest
```

Test cases:

```kotlin
addWarrantyIgnoreConflicts_setsCreatedAtAndUpdatedAtWhenZero()
addWarrantyIgnoreConflicts_preservesExistingCreatedAt()
addWarrantyIgnoreConflicts_writesCreatedEventAfterInsert()
```

### Pipeline regression tests

Receipt/warranty:

```kotlin
receiptWarrantyAutoCreate_stillCreatesWarranty()
receiptWarrantyAutoCreate_timestampIsNonZero()
```

---

## PR1.B — Manual warranty placeholder metadata

### Current problem

`createManualPlaceholderReceipt()` now avoids raw product name in `rawOcrText`, which is good. But the created `ScannedReceipt` still uses defaults:

```text
sourceType = UNKNOWN
documentType = UNKNOWN
processingStatus = CAPTURED
createdAt = 0
updatedAt = 0
```

`ScannedReceipt` has fields for `createdAt`, `updatedAt`, `sourceType`, `documentType`, and `processingStatus`.

`ReceiptDocumentType` already contains `MANUAL_PLACEHOLDER`.  
`ReceiptSourceType` already contains `MANUAL_RECORD`.

### File

```text
WarrantyTrackerRepository.kt
```

### Change

Use explicit metadata:

```kotlin
val now = timeProvider.now()

val receipt = ScannedReceipt(
    imagePath = null,
    rawOcrText = "Manual warranty entry",
    parsedTotal = null,
    parsedMerchant = merchantName,
    parsedDate = purchaseDate,
    parsedItems = null,
    parsedTaxAmount = null,
    currency = homeCurrency,
    confidence = 1f,
    createdAt = now,
    updatedAt = now,
    sourceType = "MANUAL_RECORD",
    documentType = "MANUAL_PLACEHOLDER",
    processingStatus = "PARSED"
)
```

Do **not** put `productName` in `rawOcrText`.

### Tests

```kotlin
manualPlaceholder_doesNotStoreProductNameInRawOcrText()
manualPlaceholder_usesDocumentTypeManualPlaceholder()
manualPlaceholder_usesSourceTypeManualRecord()
manualPlaceholder_setsCreatedAtAndUpdatedAt()
```

### Pipeline regression tests

```kotlin
upsertReturnWindowForReceipt_skipsManualPlaceholder()
manualWarrantyCreation_stillCreatesWarranty()
```

Expected behavior:

- manual warranty still works
- placeholder is no longer treated as normal OCR receipt
- no product name enters raw OCR retention path

---

## PR1.C — Subscription validation helper

### Current problem

`validateAndCreate()` currently checks:

```kotlin
amount > 0
currency.length == 3
merchant nonblank
```

This still allows:

```text
amount = Infinity
currency = "123"
currency = "€€€"
startDate = 0
```

`acceptCandidate()` bypasses the same validation.

`recordPriceChange()` also does not validate `newAmount`.

### File

```text
SubscriptionManagerEngine.kt
```

### Add private validation helpers

Do this locally in `SubscriptionManagerEngine`. Do not change global `CurrencyCode` yet, because that is Engine 5.

```kotlin
private fun normalizeSubscriptionCurrency(raw: String): String {
    val normalized = raw.trim().uppercase()
    require(normalized.matches(Regex("^[A-Z]{3}$"))) {
        "Invalid currency code"
    }
    return normalized
}

private fun validatePositiveFiniteAmount(amount: Double, field: String = "Amount") {
    require(amount.isFinite() && amount > 0.0) {
        "$field must be a finite positive amount"
    }
}

private fun validateMerchant(merchant: String): String {
    val normalized = merchant.trim()
    require(normalized.isNotBlank()) {
        "Merchant is required"
    }
    return normalized
}

private fun validatePositiveTimestamp(value: Long, field: String) {
    require(value > 0L) {
        "$field must be set"
    }
}
```

### Apply to `validateAndCreate()`

```kotlin
val merchant = validateMerchant(request.merchant)
validatePositiveFiniteAmount(request.amount)
val currency = normalizeSubscriptionCurrency(request.currency)
validatePositiveTimestamp(request.startDate, "Start date")
```

Use normalized values:

```kotlin
ManualRecurringExpense(
    merchant = merchant,
    amount = request.amount,
    currency = currency,
    ...
)
```

Price history:

```kotlin
SubscriptionPriceHistory(
    subscriptionId = subscriptionId,
    amount = request.amount,
    currency = currency,
    recordedAt = now
)
```

### Apply to `acceptCandidate()`

Validate candidate values before insert:

```kotlin
val merchant = validateMerchant(candidate.merchant)
validatePositiveFiniteAmount(candidate.averageAmount)
val currency = normalizeSubscriptionCurrency(candidate.currency)
validatePositiveTimestamp(nextDate, "Next date")
```

### Apply to `recordPriceChange()`

Before DB work:

```kotlin
validatePositiveFiniteAmount(newAmount, "New subscription amount")
```

Also load subscription first and preserve currency for price history if entity supports it:

```kotlin
val subscription = recurringExpenseRepository.getAll()
    .find { it.id == subscriptionId }
    ?: return
```

Then:

```kotlin
SubscriptionPriceHistory(
    subscriptionId = subscriptionId,
    amount = newAmount,
    currency = subscription.currency,
    recordedAt = timeProvider.now(),
    changeReason = reason
)
```

If `SubscriptionPriceHistory.currency` does not exist in your current constructor overload, check entity before changing.

### Tests

```kotlin
validateAndCreate_rejectsNaNAmount()
validateAndCreate_rejectsInfiniteAmount()
validateAndCreate_rejectsZeroAmount()
validateAndCreate_rejectsCurrency123()
validateAndCreate_normalizesLowercaseCurrency()
validateAndCreate_rejectsBlankMerchant()
validateAndCreate_rejectsStartDateZero()

acceptCandidate_rejectsInvalidCurrency()
acceptCandidate_rejectsInfiniteAverageAmount()
acceptCandidate_rejectsNextDateZero()

recordPriceChange_rejectsNaN()
recordPriceChange_rejectsInfinity()
recordPriceChange_rejectsZeroOrNegative()
recordPriceChange_preservesSubscriptionCurrencyInPriceHistory()
```

### Pipeline regression tests

Recurring/subscription:

```kotlin
validSubscriptionCreation_stillCreatesManualRecurringExpense()
acceptValidCandidate_stillCreatesSubscriptionAndMarksCandidateConverted()
recordValidPriceChange_stillUpdatesRecurringAmountAndPriceHistory()
```

Budget/forecast/dashboard smoke tests:

```kotlin
subscriptionMonthlyTotal_validSubscriptionStillIncluded()
forecastInput_validSubscriptionStillIncluded()
dashboardRecurringWidget_validSubscriptionStillVisible()
```

### Expected pipeline change

Invalid subscriptions are now rejected. This is not a regression; it prevents bad data from reaching recurring/budget/forecast.

---

## PR1.D — Cloud query cancellation rethrow

### Current problem

`CloudQueryInterpretationService` catches broad `Exception`. This can swallow coroutine cancellation.

### File

```text
CloudQueryInterpretationService.kt
```

### Change

Import:

```kotlin
import kotlinx.coroutines.CancellationException
```

Before generic catch:

```kotlin
catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Timber.w(e, "CloudQueryInterpretationService: parse failure")
    return@withContext unsupported("Failed to parse response: ${e.message}")
}
```

### Tests

```kotlin
cloudQueryInterpretation_rethrowsCancellationException()
cloudQueryInterpretation_networkIOExceptionStillReturnsUnsupported()
cloudQueryInterpretation_parseExceptionStillReturnsUnsupported()
```

### Pipeline regression tests

Assistant/cloud:

```kotlin
executeFinancialQuery_cloudDisabledStillFallsBackOrUnsupported()
cloudQuery_redactionStillHappensBeforeHttp()
cloudQuery_privacyDeniedDoesNotCallHttp()
```

---

# PR1 docs update

Update:

```text
docs/analyses and debug master/ENGINE_ISSUES_MASTER_TRACKER.md
```

Mark:

- W18: mostly fixed → fixed for repository paths
- W21: partial → fixed for manual placeholder metadata
- W22: partial → mostly fixed for Engine 1 local validation
- W17: cancellation caveat fixed

Add note:

> Subscription currency validation is local strict ASCII-3-letter validation. Global supported-currency policy remains Engine 5.

---

# PR1 human validation commands

Do not run as AI. Human should run:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
```

If Hilt constructors touched:

```bash
./gradlew :app:assembleDebug --stacktrace
```

No migration expected.

---

# PR2 — Cloud privacy constructor hardening

## Goal

Prevent accidental main-source construction of cloud service with empty/no-op privacy gate.

## Current problem

`CloudQueryInterpretationService` has secondary constructors in main source using:

```kotlin
CompositePrivacyGate(emptyList(), PrivacyAuditLogger.NO_OP, ...)
```

This is okay for tests but risky in production source.

## Files

```text
CloudQueryInterpretationService.kt
test files using secondary constructors
```

## Options

### Option A — safest if compile allows

Delete secondary constructors from main source.  
Update tests to call the primary constructor with fake dependencies.

### Option B — less disruptive

Mark constructors:

```kotlin
@VisibleForTesting
internal constructor(...)
```

But Kotlin visibility + Java/Hilt/test package layout may complicate this.

### Option C — static guard only

Keep constructors temporarily, but add static test/guard:

```text
no CompositePrivacyGate(emptyList()) in app/src/main except allowlisted test constructor
```

## Recommended

Use **Option A** if tests can be updated cleanly. Otherwise Option C temporarily.

## Tests

```kotlin
cloudQuery_requiresPrivacyGateInMainConstructor()
cloudQuery_testUsesExplicitFakePrivacyGate()
noAllowAllPrivacyGateConstructorInMainSource()
```

## Pipeline impact

Cloud assistant only. Expected behavior unchanged, but privacy safety improves.

---

# PR3 — Warranty lifecycle events

## Goal

Improve observability without changing business behavior.

## Current problem

`WarrantyLifecycleEvent` exists but only covers some warranty events. Event type is raw string. Return-window events and expiry reconciliation are incomplete.

## Risk

Medium.

## Important schema note

Current `warranty_lifecycle_events` table has only:

```text
warrantyId
eventType
occurredAt
description
metadata
```

It does **not** have `returnWindowId`.

So do **not** force all return-window events into this table unless a warranty ID is available.

## Implementation strategy

### Step 1 — constants, no migration

Add:

```kotlin
object WarrantyLifecycleEventTypes {
    const val CREATED = "CREATED"
    const val CLAIMED = "CLAIMED"
    const val UPDATED = "UPDATED"
    const val DELETED = "DELETED"
    const val EXPIRED = "EXPIRED"
    const val AI_WARRANTY_CREATED = "AI_WARRANTY_CREATED"
    const val AI_EXTRACTION_DISCARDED = "AI_EXTRACTION_DISCARDED"
}
```

Use constants instead of raw strings.

### Step 2 — warranty events only

Add events for:

- `updateWarranty`
- `deleteWarranty`
- `reconcileExpiredItems` where warranty IDs can be known

If DAO only returns count for expired rows, do not add fake per-row events. Instead add a `PipelineDiagnosticEvent` or add a DAO query to fetch rows before update.

### Step 3 — return-window event path

Use one of:

1. receipt lifecycle events, because return windows are receipt-related
2. `PipelineDiagnosticEvent`
3. new `return_window_lifecycle_events` table in a later schema PR

For now, avoid migration. Use diagnostic/receipt event if available.

## Tests

```kotlin
markWarrantyAsClaimed_writesClaimedEvent()
updateWarranty_writesUpdatedEvent()
deleteWarranty_writesDeletedEvent()
reconcileExpiredItems_writesDiagnosticOrEvents()
eventTypes_useConstants()
```

## Pipeline regression tests

Receipt/warranty:

```kotlin
receiptWarrantyCreation_stillCreatesWarranty()
expiryReconciliation_stillMarksExpiredWarranties()
returnWindowCreation_stillWorks()
```

Backup/export:

```kotlin
warrantyEvents_doNotBreakBackupExport()
```

---

# PR4 — Low-confidence warranty extraction review routing

## Goal

Avoid silent discard of useful warranty extraction results.

## Current problem

`MIN_CLOUD_CONFIDENCE = 0.5f`, and results below that are discarded. Later code tries:

```kotlin
lowConfidence = confidence <= 0.3f
```

But that path is unreachable for cloud results below `0.5`.

## Risk

Medium.

## Recommended contract

Use three bands:

| Confidence | Behavior |
|---|---|
| `>= 0.75` | auto-create warranty |
| `0.30..0.75` | create warranty draft with `needsReview = true` |
| `< 0.30` | discard, write diagnostic |

Do not auto-create low-confidence warranties as active trusted items.

## Implementation

Change:

```kotlin
private const val MIN_CLOUD_CONFIDENCE = 0.5f
```

to:

```kotlin
private const val AUTO_ACCEPT_CLOUD_CONFIDENCE = 0.75f
private const val REVIEW_CLOUD_CONFIDENCE = 0.30f
```

Logic:

```kotlin
if (confidence < REVIEW_CLOUD_CONFIDENCE) {
    // diagnostic
    return null
}

val needsReview = confidence < AUTO_ACCEPT_CLOUD_CONFIDENCE
```

Then:

```kotlin
Warranty(
    ...
    needsReview = needsReview,
    extractionConfidence = confidence.toDouble()
)
```

## Tests

```kotlin
cloudWarrantyConfidence_0_8_autoCreatesWithoutReview()
cloudWarrantyConfidence_0_4_createsNeedsReviewDraft()
cloudWarrantyConfidence_0_1_discardsWithDiagnostic()
```

## Pipeline impact

Receipt/AI warranty pipeline will now show some review drafts that were previously discarded.

This is a behavior change but not a regression if:

- low-confidence items are visibly marked
- they do not affect protected-value totals unless approved, or UI indicates review

If current UI includes `needsReview` warranties in protected value, decide before this PR.

---

# PR5 — NLP location query semantics

## Goal

Prevent misleading NLP results for unsupported location filters.

## Current problem

Legacy NLP can parse location intent and set a warning, but still returns broad non-location-filtered results.

## Risk

Medium.

## Recommended behavior

Until true location filtering is implemented:

```text
If query has location filter:
    return unsupported result
    do not return broad results
```

This is safer than returning wrong answers.

## Files

```text
NaturalLanguageSearchEngine.kt
NaturalLanguageExpenseQueryRepositoryImpl.kt
assistant/query UI state if needed
```

## Implementation

When `interpretation.locations != null`:

```kotlin
return SearchResult.Unsupported(
    reason = "Location-specific natural language search is not yet supported"
)
```

If there is no `Unsupported` result type, return empty result with strong `QueryDataQuality.unsupportedLocations = true` and message requiring UI display.

## Tests

```kotlin
nlLocationQuery_returnsUnsupportedInsteadOfBroadResults()
nlNonLocationQuery_stillReturnsResults()
nlAmountFilter_stillCurrencyNormalizes()
```

## Pipeline regression tests

Assistant/search:

```kotlin
assistantLocationQuery_showsUnsupportedMessage()
assistantLargestQuery_stillWorks()
legacyMerchantQuery_stillWorks()
```

---

# PR6 — Bill negotiation provider wiring, no persistence yet

## Goal

Use `MarketRateProvider` instead of private static in-engine map.

## Current problem

`MarketRateProvider` exists, but `SmartBillNegotiationEngine` still has:

```kotlin
private val marketRates = ...
System.currentTimeMillis()
```

This makes rates look fresh every process start.

## Risk

Medium-high due Hilt/DI.

## Files

```text
SmartBillNegotiationEngine.kt
MarketRateProvider.kt
DI module if needed
tests
```

## Implementation

1. Inject:

```kotlin
private val marketRateProvider: MarketRateProvider
```

2. Remove private `marketRates` map from engine.

3. Query provider:

```kotlin
val rate = marketRateProvider.findRate(
    merchant = subscription.merchant,
    serviceType = detectServiceType(subscription.merchant),
    currency = subscription.currency
)
```

4. Propagate:

- rate source
- demo/static flag
- stale flag
- currency

5. Keep behavior read-only. Do not update subscription amount yet.

## Tests

```kotlin
negotiationEngine_usesInjectedMarketRateProvider()
staticMarketRateProvider_marksDemoRates()
staleMarketRatesRemainStaleAcrossEngineInstances()
```

## Pipeline impact

Subscription UI/negotiation only.

Expected:

- opportunities still appear
- warnings are more truthful
- demo/static rates are labeled

---

# PR7 — Bill negotiation monthly-equivalent script fix

## Goal

Fix misleading script text before adding persistence.

## Current problem

Opportunity computation uses monthly equivalent, but script generation can still use raw subscription amount.

## Implementation

Add fields to opportunity model:

```kotlin
rawBillingAmount: Double
billingFrequency: RecurrenceFrequency
monthlyEquivalentPrice: Double
currency: String
```

Script must use:

```kotlin
monthlyEquivalentPrice
```

for monthly comparison text.

## Tests

```kotlin
annualSubscriptionScript_says10PerMonthNot120()
quarterlySubscriptionScript_usesMonthlyEquivalent()
scriptPreservesRawBillingAmountSeparately()
```

## Pipeline impact

Only negotiation UX.

No DB migration.

---

# PR8 — Bill negotiation persistence

## Goal

Persist negotiation outcomes and optionally update subscription amount after successful negotiation.

## Risk

High.

Do this last.

## Requires schema migration

After your v145 baseline work, this would likely be:

```text
MIGRATION_145_146
```

## New table proposal

```kotlin
@Entity(
    tableName = "negotiation_outcomes",
    indices = [
        Index("subscriptionId"),
        Index("createdAt"),
        Index("outcome")
    ],
    foreignKeys = [
        ForeignKey(
            entity = ManualRecurringExpense::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class NegotiationOutcomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subscriptionId: Long,
    val outcome: String,
    val oldAmount: Double,
    val newAmount: Double?,
    val currency: String,
    val savingsAmount: Double?,
    val notes: String?,
    val marketRateSource: String?,
    val createdAt: Long
)
```

## Atomic behavior

If outcome is successful and `newPrice != null`:

Inside one transaction:

1. insert negotiation outcome
2. insert price history
3. update subscription amount

Use `DatabaseWriteBarrier`.

## Tests

```kotlin
negotiationSuccess_persistsOutcome()
negotiationSuccess_updatesSubscriptionAmount()
negotiationSuccess_insertsPriceHistory()
negotiationFailure_persistsOutcomeButDoesNotUpdatePrice()
negotiationWriteBlockedDuringRestore()
```

## Pipeline regression tests

Recurring/budget/forecast:

```kotlin
successfulNegotiation_updatesRecurringSubscriptionAmount()
budgetForecastUsesNewSubscriptionAmount()
dashboardSubscriptionTotalUsesNewAmount()
```

## Human validation

Because this includes Room/Hilt:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

---

# PR9 — Deprecated/raw API guard

## Goal

Prevent future pipeline regressions by blocking old raw paths.

## Targets

Examples:

```kotlin
WarrantyTrackerRepository.getTotalProtectedValue(): Double
SubscriptionManagerEngine.getTotalMonthlySubscriptionCost(): Double
raw location compute APIs
```

## Strategy

Phase 1:

```kotlin
@Deprecated(
    message = "Use MoneyAggregate API",
    replaceWith = ReplaceWith("getTotalProtectedValueAggregate()"),
    level = DeprecationLevel.WARNING
)
```

Phase 2 after call sites fixed:

```kotlin
level = DeprecationLevel.ERROR
```

Add static source guard:

```text
No production call to:
- getTotalProtectedValue()
- getTotalMonthlySubscriptionCost()
- AreaSpendingEngine.compute(List<Expense>)
- TravelDetectionEngine.compute(List<Expense>)
```

## Tests

```kotlin
noProductionCallToRawWarrantyTotal()
noProductionCallToRawSubscriptionTotal()
noProductionCallToRawLocationCompute()
```

---

# Pipeline regression matrix

## Warranty / receipt pipeline

Run after PR1, PR3, PR4:

```text
manual warranty create
auto warranty extraction high-confidence
auto warranty extraction low-confidence review
return-window creation
return-window expiry reconciliation
warranty protected-value aggregate
backup/export includes warranty rows
```

## Subscription / recurring pipeline

Run after PR1, PR6, PR7, PR8:

```text
manual subscription create
candidate accept
price change
subscription monthly aggregate
recurring projection
budget forecast
dashboard subscription total
```

## Location / map pipeline

Run after PR5 and PR9:

```text
map markers load
heatmap spending-only
conversion warning visible
center-on-me privacy gate
manual correction save
NLP location query unsupported/filter behavior
```

## AI / assistant pipeline

Run after PR1.D and PR2:

```text
cloud query privacy denied
cloud query redaction before HTTP
cloud query cancellation
assistant largest query
assistant total/count/average query
API key missing behavior
```

---

# Non-regression definition

A pipeline is **not regressed** if:

1. valid existing user flows still work
2. invalid values are rejected with clear errors
3. user financial data is preserved
4. no cloud call happens without privacy gate + redaction
5. no DB write happens during restore/backup blocked mode
6. currency values are not silently raw-summed
7. no “unsupported” query returns misleading broad results
8. no Hilt/Room schema break
9. no side effects fire before transaction commit

---

# What not to do

Do **not** do these in Engine 1 cleanup:

```text
- do not rewrite MoneyAmount/CurrencyCode globally
- do not change CurrencyConverter semantics
- do not change TimePeriodUtils globally
- do not add negotiation Room table in same PR as validation fixes
- do not change receipt lifecycle coordinator behavior while fixing manual placeholder metadata
- do not make cloud privacy policy weaker for tests
- do not use fallbackToDestructiveMigration
- do not rerun financial rescue
```

---

# Recommended execution order

## First week / first slice

Do only **PR1**.

This gives meaningful safety with low blast radius.

Expected commits:

```bash
git commit -m "fix(engine1): harden warranty placeholders and subscription validation"
git commit -m "fix(ai): rethrow cancellation in cloud query interpretation"
git commit -m "test(engine1): cover warranty placeholder and subscription validation contracts"
```

## Then

PR2 cloud constructor hardening.

## Then

PR3/PR4 warranty lifecycle + low-confidence review.

## Then

PR5 NLP location semantics.

## Then

PR6/PR7 negotiation provider/monthly script.

## Last

PR8 negotiation persistence with migration.

---

# Human validation commands

Suggested only. Do not claim green until human runs them.

For no-schema PRs:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
```

For Hilt changes:

```bash
./gradlew :app:assembleDebug --stacktrace
```

For Room/schema changes:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

Full check before merging an engine slice:

```bash
./gradlew :app:check --stacktrace
```

---

# Definition of done for Engine 1

Engine 1 can be called clean when:

- all warranty/subscription write paths respect `DatabaseWriteBarrier`
- warranty inserted by any production path has nonzero timestamps
- manual warranty placeholder is clearly `MANUAL_PLACEHOLDER`
- manual placeholder stores no product name in raw OCR text
- warranty lifecycle has durable events/diagnostics for major transitions
- low-confidence warranty extraction routes to review or diagnostic
- subscription creation/candidate/price-change reject invalid money/currency
- subscription potential savings are monthly-normalized and currency-safe
- negotiation uses injected market-rate provider
- negotiation scripts compare monthly-to-monthly
- negotiation outcomes persist, if feature is considered production
- cloud query always uses privacy gate + redacted payload
- cloud query cancellation is not swallowed
- NLP location queries are either truly filtered or explicitly unsupported
- deprecated raw mixed-currency APIs are blocked from production call sites