# Engine Debug Review — Warranty / Subscription / Location / NLP

Reviewed commits:

```text
8392878a1ba823d3df035989c3c4e378eb2636d2
c346cf3b05dbed9da4c132805da9c8d3ae58908b
18cf0281f907128898a77763fd61635c71c4ca78
1a480de669fab41d6fde8123f76231b78638d09b
```

Review type: static GitHub/code review, not local Gradle execution.

---

# 1. Executive verdict

These commits are a **major improvement**.

You fixed or materially improved:

```text
✅ subscription validateAndCreate is atomic
✅ subscription isSubscription=true fixed
✅ subscription baseline price history is in transaction for manual create
✅ map markers use displayCurrency/originalCurrency/conversionWarning
✅ map markers use effectiveAmount
✅ map markers use historical convertAsOf()
✅ heatmap and location insights use LocatedMoneyExpense normalized path
✅ GPS is privacy-gated
✅ GPS is no longer fetched immediately on permission grant
✅ GeoCoordinate validation exists
✅ legacy NL no longer pushes min/max amount to DAO
✅ legacy NL excludes failed conversions instead of raw fallback
✅ legacy NL merchant extraction uses original query and multi-word regex
✅ legacy NL category filter is pushed down to DAO
✅ keyset query exists for legacy NL
✅ warranty protected value is MoneyAggregate-based
✅ warranty/return windows use home currency fallback
✅ warranty CREATED/CLAIMED lifecycle events exist
✅ guard/test/scenario infrastructure improved
```

However:

> I would **not** yet mark Warranty / Subscription / Location / NLP as fully finalized.

I would mark them:

```text
Warranty:      mostly stable, minor lifecycle/TODO cleanup remains
Subscription:  core stable, candidate-accept path still not atomic
Location:      mostly stable, correction save result still ignored
NLP:           improved but not fully stable; legacy and assistant paths still have issues
```

There are no obvious catastrophic pipeline regressions, but there are several localized correctness gaps.

---

# 2. Commit-by-commit evaluation

## 2.1 `8392878`

Good:

```text
subscription validateAndCreate wrapped in database.withTransaction
isSubscription=true
assistant count/largest/total/average/breakdown closer to unified currency-aware helper
breakdown sort no longer uses 0 fallback
tracker wording improved
```

Remaining:

```text
executeList still has its own inline amount-filter path
executeList uses currencyConverter.convert(), not convertAsOf()
executeLargest still uses currencyConverter.convert() for mixed-currency max comparison
old assistantFilteredExpenses() still exists and still raw-passes min/max if accidentally reused
assistant comments are stale and still claim raw amount filters
```

Verdict:

```text
Good fix, but assistant query consistency is still not perfectly finalized.
```

---

## 2.2 `c346cf3`

Good:

```text
GroupSettlementEntity / DAO / migration v120 added
TaxSettings wiring started
InvestmentTransaction BUY transaction wiring started
Legacy NL marked feature-contained
Warranty CREATED/CLAIMED lifecycle events written
guard tests expanded
```

For this review, the most relevant improvement is:

```text
Warranty lifecycle event foundation exists.
```

Remaining:

```text
Warranty lifecycle events are not complete for all warranty/return actions.
Group/tax/investment are advanced-beta areas, not fully complete.
```

Verdict:

```text
Strong advanced-engine foundation, but not full finalization.
```

---

## 2.3 `18cf028`

Good:

```text
golden/scenario test infrastructure added
mixed-currency/privacy/groups/backup integrity coverage started
```

Remaining:

```text
Need ensure tests assert the exact engine contracts, not only smoke/fixture setup.
```

Verdict:

```text
Good regression-test direction.
```

---

## 2.4 `1a480de`

Good:

```text
W13 DAO returns Long for correction insert
W14 legacy NL multi-word merchant extraction + alias lookup
W15 category push-down to DAO
W16 no raw amount fallback in legacy NL
W27 GPS defer
W28 GeoCoordinate value object
W29 area/travel normalized MoneyAggregate paths
W25 MarketRateProvider interface/static provider
W34 AssistantHistorySettings added
CloudPayloadRedactor migrated to more providers
GroupLifecycleCoordinator added
guard scripts hardened
```

Remaining:

```text
W13 not complete at ViewModel level
W34 redacted mode does not actually redact text
legacy NL multi-merchant filtering can still return broad partial results
legacy NL only fetches first keyset page in executeSearch()
tracker still shows several now-fixed W-items as TODO ONLY
```

Verdict:

```text
Very good, but the final polish pass is still needed.
```

---

# 3. Warranty engine check

## Good

Warranty is now one of the better stabilized advanced engines.

Good current behavior:

```text
protected value aggregate uses MoneyAggregateBuilder
manual placeholder receipt uses homeCurrency
addWarranty sets createdAt/updatedAt
addWarranty runs in transaction
addWarranty writes CREATED lifecycle event
markWarrantyAsClaimed writes CLAIMED event
return-window refund currency falls back to linked expense currency, then home currency
return windows can be returned without refundAmount
warranty end-date helper uses half-open-style boundary
cloud extraction has confidence threshold
cloud warranty extraction uses AI route/policy decision
```

## Remaining issues

### WTY-1 — Stale TODO remains

`WarrantyTrackerRepository` still contains:

```text
TODO (W20): Use half-open warranty dates
```

but the code below it says this is already fixed.

This is tracker/comment cleanup, not functional risk.

### WTY-2 — Lifecycle event coverage is incomplete

Events currently appear to be written for:

```text
CREATED
CLAIMED
```

But not clearly for:

```text
updated
deleted
auto-detected confirmed
auto-detected rejected
return-window returned
expiry reconciliation
```

If lifecycle audit is a requirement, warranty is **partial**, not complete.

### WTY-3 — `markAsReturned()` is not wrapped in transaction

It loads a return window, optionally loads linked expense, updates row.

Risk is low, but for consistency:

```kotlin
database.withTransaction { ... }
```

would be better.

## Warranty verdict

```text
Core warranty/return-window engine: stable enough.
Full warranty lifecycle/audit engine: partial.
```

Recommended final warranty fixes:

```text
1. Remove stale W20 TODO.
2. Add lifecycle events for returned/rejected/deleted/expired if required.
3. Wrap markAsReturned in withTransaction.
4. Add tests for no-refund return, linked-expense currency, home-currency fallback, half-open expiry.
```

---

# 4. Subscription engine check

## Good

Manual subscription creation is now much better:

```text
validateAndCreate validates positive amount
validates currency length/nonblank
validates merchant nonblank
sets createdAt
sets isSubscription=true
wraps subscription insert + baseline price history in database.withTransaction
recordPriceChange is atomic
price history recordedAt uses timeProvider.now()
subscription aggregate uses MoneyAggregateBuilder
```

## Remaining issues

### SUB-1 — Candidate accept still bypasses `validateAndCreate()`

`SubscriptionManagementViewModel.acceptCandidate()` still does:

```text
repository.insertSubscription(subscription)
repository.insertPriceHistory(priceHistory)
repository.markCandidateAsConverted(...)
```

outside a single transaction and outside `SubscriptionManagerEngine.validateAndCreate()`.

So candidate acceptance can still fail halfway:

```text
subscription inserted
price history insert fails
candidate not marked converted
```

or:

```text
subscription inserted
candidate conversion marking fails
```

This is the biggest remaining subscription issue.

### SUB-2 — Candidate price history may miss currency

Manual validateAndCreate inserts:

```text
SubscriptionPriceHistory(currency = request.currency)
```

Candidate accept inserts price history without visibly setting currency in the reviewed snippet.

If `SubscriptionPriceHistory.currency` has a safe default, okay. If not, this is inconsistent.

### SUB-3 — Subscription ViewModel totals remain raw mixed-currency

Not exactly engine layer, but `SubscriptionManagementViewModel` computes:

```text
totalMonthly = sumOf monthly cost
totalAnnual = totalMonthly * 12
```

and UI likely formats in home currency.

Engine aggregate exists:

```text
getTotalMonthlySubscriptionCostAggregate()
```

but UI/ViewModel may not use it yet.

## Subscription verdict

```text
Manual subscription creation: stable.
Candidate acceptance path: not stable.
Subscription totals UI path: still needs MoneyAggregate consumption.
```

Recommended final subscription fixes:

```text
1. Move candidate accept into SubscriptionManagerEngine.acceptCandidate().
2. Wrap subscription insert + baseline history + candidate converted in one transaction.
3. Ensure candidate price history stores candidate.currency.
4. Update ViewModel totals to use MoneyAggregate or per-currency totals.
5. Add rollback test for candidate accept.
```

---

# 5. Location engine check

## Good

Location/map work improved a lot.

Good behavior:

```text
GPS is gated by PrivacyGate.DEVICE_GPS_LOCATION
permission grant no longer fetches GPS automatically
onCenterOnMeRequested explicitly fetches GPS
map markers use effectiveAmount
map markers use convertAsOf(expense.date)
failed conversion displays native currency
marker carries displayCurrency/originalCurrency/conversionWarning
heatmap uses computeNormalized()
insights use computeNormalized()
spending-only filter applied before heatmap/insights
date range uses half-open < end
mapConversionWarnings count exists
GeoCoordinate validation exists
AreaSpendingEngine.computeNormalized() exists
TravelDetectionEngine.computeNormalized() exists
MerchantLocationDao.upsertCorrection returns Long
```

## Remaining issues

### LOC-1 — W13 not complete: saveCorrection result ignored

DAO now returns:

```kotlin
upsertCorrection(...): Long
```

Repository returns that Long.

But `SpendingMapViewModel.onSaveCorrection()` does:

```kotlin
merchantLocationRepository.saveCorrection(...)
expenseRepository.updateExpenseLocation(...)
snackbar = "Correction saved"
```

It does **not** check the returned ID.

So a correction insert can still fail/ignore due conflict, while UI says success and expense location is updated.

Required:

```kotlin
val correctionId = merchantLocationRepository.saveCorrection(...)
if (correctionId <= 0L) {
    show "Correction could not be saved"
    return
}
```

or return a sealed `SaveCorrectionResult`.

### LOC-2 — `GeoCoordinate` exists but is not broadly enforced

`GeoCoordinate.create()` exists, but map pin/correction/update paths still accept raw doubles:

```text
onSaveCorrection(correctedLat: Double, correctedLon: Double)
assignLocationToExpense(lat: Double, lon: Double)
LocationResolver returns raw latitude/longitude
```

The validation object is useful, but it is not yet a hard invariant.

### LOC-3 — Raw/deprecated paths remain with stale warnings

`SpendingHeatmapEngine.compute()` and `LocationInsightsEngine.compute()` still raw-sum, but are now superseded by normalized paths in `SpendingMapViewModel`.

This is okay if all production callers use `computeNormalized()`.

Need guard/test:

```text
no production caller uses raw compute() for money totals
```

### LOC-4 — Map conversion warning may not be user-visible

State has:

```text
mapConversionWarnings
```

Need confirm UI actually displays it.

Engine is okay, but the user needs visible quality feedback.

## Location verdict

```text
Map/location engine is mostly stable.
Manual correction and coordinate-invariant enforcement still need final fixes.
```

Recommended final location fixes:

```text
1. Check saveCorrection result in ViewModel/repository and show failure if <=0.
2. Use GeoCoordinate.create() in correction/pin/update inputs.
3. Add test proving computeNormalized is the production path.
4. Show mapConversionWarnings in UI or route quality model.
```

---

# 6. NLP / Assistant / Smart Search engine check

## Good

Legacy NL has improved substantially:

```text
merchant extraction now uses original query
multi-word merchant regex exists
alias lookup is attempted
category filters are pushed to DAO by category IDs
amount filters are not pushed as raw min/max
amount filter normalizes with convertAsOf()
failed conversions are excluded, not raw-fallbacked
QueryDataQuality records unsupportedLocations and failedCurrencyConversions
keyset query exists
legacy NL is marked deprecated/feature-contained
```

Assistant query engine also improved:

```text
executeTotal uses assistantFilteredExpensesCurrencyAware()
executeAverage uses assistantFilteredExpensesCurrencyAware()
executeCount uses assistantFilteredExpensesCurrencyAware()
executeCategoryBreakdown uses helper and dataQuality
executeMerchantBreakdown uses helper and dataQuality
executeLargest uses helper for filtering
```

## Remaining issues

### NLP-1 — Assistant `executeList()` still uses current-rate conversion

In `ExecuteFinancialQueryUseCase.executeList()` amount filtering still uses:

```kotlin
currencyConverter.convert(...)
```

not:

```kotlin
convertAsOf(..., expense.date)
```

So list queries with amount filters can disagree with total/count/breakdown queries.

### NLP-2 — Assistant `executeLargest()` still uses current-rate conversion for mixed-currency max

It now uses the helper for filtering, but the final mixed-currency comparison still uses:

```kotlin
currencyConverter.convert(...)
```

not historical:

```kotlin
convertAsOf(..., expense.date)
```

### NLP-3 — Old raw helper still exists

`assistantFilteredExpenses()` remains and still passes:

```text
minAmount
maxAmount
```

to repository.

It is deprecated, but still present.

Safer:

```text
delete it
```

or enforce:

```text
require(intent.filters.minAmount == null && intent.filters.maxAmount == null)
```

### NLP-4 — Legacy NL multi-merchant path can return broad partial rows

Repository keyset query only pushes merchant filter when:

```kotlin
merchants?.singleOrNull()
```

If multiple merchants are detected, DAO gets no merchant filter.

`executeSearch()` then does not filter nonmatching merchants out. It only labels rows as `PARTIAL`.

So a query that detects multiple merchants can return broad date/category/amount rows.

Fix:

```text
if merchants.size > 1:
  use SQL OR patterns, or
  filter in memory after DAO result before returning
```

### NLP-5 — Legacy NL only fetches first keyset page in `executeSearch()`

`executeSearch()` calls:

```kotlin
getExpensesBetweenFilteredKeyset(... cursor = null, limit = 500)
```

once.

It does not loop through subsequent pages.

So W30/W31 are partly fixed:

```text
keyset query exists
but executeSearch only returns first page
```

This may be acceptable for UI preview, but not for full result correctness.

### NLP-6 — W34 “REDACTED” mode does not redact message text

`AssistantHistorySettings.REDACTED` exists.

But `AiChatRepositoryImpl.appendMessage()` stores:

```kotlin
text = text
```

It only drops `payloadJson` unless RAW.

There is no call to `CloudPayloadRedactor` or any text redactor.

So W34 is **not fixed**:

```text
raw user query text is still stored in REDACTED mode
```

Also Assistant diagnostics still include:

```text
Query: ${query.take(50)}
```

which leaks raw query into runtime diagnostics.

### NLP-7 — Stale comments are misleading

`NaturalLanguageSearchEngine` header still says:

```text
conversion failure raw fallback
category/location not applied
```

but code now partly fixes those.

`ExecuteFinancialQueryUseCase` header still says:

```text
amount filters operate on raw effectiveAmount
```

but many paths no longer do.

Tracker also still marks W13/W14/W15/W16/W27/W28/W29/W30/W31 as TODO even though several are partly or mostly fixed.

## NLP verdict

```text
Legacy NL is much safer, but not fully stable.
Assistant financial query engine is mostly stable, but list/largest need convertAsOf consistency.
Assistant history privacy is not stable.
```

Recommended final NLP fixes:

```text
1. Make executeList use assistantFilteredExpensesCurrencyAware().
2. Make executeLargest mixed-currency comparison use convertAsOf().
3. Delete or hard-guard old assistantFilteredExpenses().
4. For legacy NL, filter multiple merchants in memory or SQL.
5. Decide whether executeSearch should loop keyset pages or explicitly be preview-only.
6. Actually redact text in AssistantHistorySettings.REDACTED.
7. Remove raw query from runtime diagnostics.
8. Reconcile stale comments/tracker.
```

---

# 7. Tracker/documentation status

The tracker is currently inconsistent with the code.

Examples:

```text
W13 still TODO, but DAO returns Long now.
W14 still TODO, but merchant extraction was improved.
W15 still TODO, but category push-down exists.
W16 still TODO, but amount filter no longer raw-fallbacks.
W27 still TODO, but GPS fetch is deferred.
W28 still TODO, but GeoCoordinate exists.
W29 still TODO, but Area/Travel computeNormalized exists.
W30/W31 still TODO, but keyset query exists.
W34 still deferred, but implementation exists — although incomplete.
```

Also the tracker says:

```text
Core engines stabilized (44 fixed)
```

but its detailed table still shows the W-items as TODO.

Required:

```text
Tracker reconciliation PR.
```

Suggested statuses:

```text
W13 → PARTIAL
W14 → FIXED/PARTIAL
W15 → PARTIAL
W16 → PARTIAL/FIXED for single-page path
W20 → FIXED, remove stale TODO
W22 → FIXED for manual create, PARTIAL for candidate accept
W23 → PARTIAL/FIXED, move engine ownership if desired
W27 → FIXED
W28 → PARTIAL
W29 → FIXED if all production callers use normalized paths
W30 → PARTIAL
W31 → PARTIAL
W34 → PARTIAL, not fixed
```

---

# 8. Regression assessment

No broad regression is obvious.

Good architecture signs:

```text
currency conversion now mostly uses convertAsOf where dates exist
MoneyAggregate use expanded
privacy gates improved
manual subscription create is atomic
map no longer labels failed foreign conversion as home currency
legacy NL no longer raw-fallbacks failed conversion
```

Localized regression/bug risks:

```text
1. candidate subscription accept can still partially commit
2. correction save failure still reports success
3. assistant REDACTED history stores raw text
4. assistant list/largest use current conversion, not historical
5. legacy NL multi-merchant query returns broad partial result set
6. executeSearch is keyset-capable but only fetches first page
7. tracker is stale, so future work may be misprioritized
```

---

# 9. Stability rating

Current state:

| Engine | Rating | Verdict |
|---|---:|---|
| Warranty core | 8.5/10 | stable enough, lifecycle audit incomplete |
| Subscription manual create | 9/10 | stable |
| Subscription candidate accept | 6.5/10 | not atomic, bypasses engine |
| Location map/heatmap/insights | 8/10 | mostly stable |
| Location correction | 6.5/10 | save failure ignored |
| Legacy NL | 7/10 | safer, still contained/beta |
| Assistant query | 8/10 | mostly stable, list/largest historical conversion gap |
| Assistant history privacy | 5/10 | REDACTED mode not actually redacting text |

Overall for the requested engines:

```text
Not fully finalized yet.
Close, but needs one final small stabilization PR.
```

---

# 10. Final blocker list before moving forward

I would fix these before saying “Warranty / Subscription / Location / NLP engines are stable”:

```text
1. Subscription candidate accept:
   create SubscriptionManagerEngine.acceptCandidate()
   wrap subscription + baseline + candidate-converted in transaction.

2. Location correction:
   handle saveCorrection() Long result.
   do not show success when id <= 0.

3. Assistant history:
   REDACTED mode must redact text, not only drop payloadJson.
   remove raw query from diagnostics.

4. Assistant query:
   executeList and executeLargest should use convertAsOf().
   remove/hard-guard old assistantFilteredExpenses().

5. Legacy NL:
   handle multiple merchants by filtering in SQL OR or in memory.
   decide whether executeSearch is first-page preview or full keyset loop.

6. Tracker:
   reconcile W statuses and remove stale TODO comments.
```

That is a manageable final PR.

---

# 11. Tests to add or verify

## Warranty

```text
WarrantyAddWritesCreatedEventTest
WarrantyClaimWritesClaimedEventTest
ReturnWindowReturnedNoRefundTest
ReturnWindowRefundCurrencyLinkedExpenseTest
WarrantyHalfOpenExpiryTest
```

## Subscription

```text
ValidateAndCreateAtomicRollbackTest
ValidateAndCreateSetsIsSubscriptionTest
AcceptCandidateAtomicTest
AcceptCandidateBaselineCurrencyTest
AcceptCandidateCalendarNextOccurrenceTest
```

## Location

```text
PermissionGrantDoesNotFetchGpsTest
CenterOnMePrivacyDeniedNoProviderCallTest
CorrectionInsertConflictDoesNotShowSuccessTest
MapMarkerFailedConversionNativeCurrencyTest
HeatmapUsesNormalizedAmountsTest
AreaTravelComputeNormalizedTest
GeoCoordinateRejectsInvalidInputTest
```

## NLP / Assistant

```text
AssistantListAmountFilterUsesHistoricalRateTest
AssistantLargestUsesHistoricalRateTest
AssistantOldRawHelperNotUsedTest
LegacyNlMultipleMerchantsFilteredTest
LegacyNlKeysetPaginationSecondPageTest
LegacyNlMissingRateExcludesWithWarningTest
AssistantRedactedHistoryDoesNotStoreRawQueryTest
AssistantDiagnosticsDoNotShowRawQueryTest
```

---

# 12. Final recommendation

Do **one more targeted stabilization PR**:

```text
PR-FINAL-W-S-L-NLP
1. subscription acceptCandidate atomic engine path
2. correction save result handling
3. assistant REDACTED text redaction + diagnostics redaction
4. assistant convertAsOf consistency
5. legacy NL multi-merchant/full-keyset decision
6. tracker/comment reconciliation
```

After that, I would be comfortable saying:

```text
Warranty / Subscription / Location / NLP engines are stable enough to move forward.
```

Right now I would say:

```text
They are close, but not done.
```

---

# Sources

Commits:

- https://github.com/panospao7/Cost-agregator/commit/8392878a1ba823d3df035989c3c4e378eb2636d2
- https://github.com/panospao7/Cost-agregator/commit/c346cf3b05dbed9da4c132805da9c8d3ae58908b
- https://github.com/panospao7/Cost-agregator/commit/18cf0281f907128898a77763fd61635c71c4ca78
- https://github.com/panospao7/Cost-agregator/commit/1a480de669fab41d6fde8123f76231b78638d09b

Key files reviewed:

- `ENGINE_ISSUES_MASTER_TRACKER.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/docs/analyses%20and%20debug%20master/ENGINE_ISSUES_MASTER_TRACKER.md

- `WarrantyTrackerRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt

- `SubscriptionManagerEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/domain/subscription/SubscriptionManagerEngine.kt

- `SubscriptionManagementViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/ui/screens/subscription/SubscriptionManagementViewModel.kt

- `SpendingMapViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt

- `MerchantLocationDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantLocationDao.kt

- `MerchantLocationRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/data/repository/MerchantLocationRepository.kt

- `NaturalLanguageSearchEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/domain/naturallanguage/NaturalLanguageSearchEngine.kt

- `NaturalLanguageExpenseQueryRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/data/repository/NaturalLanguageExpenseQueryRepositoryImpl.kt

- `ExecuteFinancialQueryUseCase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt

- `AiChatRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/data/repository/AiChatRepositoryImpl.kt

- `AreaSpendingEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/domain/location/AreaSpendingEngine.kt

- `TravelDetectionEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/domain/location/TravelDetectionEngine.kt