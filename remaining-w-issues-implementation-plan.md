# Implementation Plan — Remaining W Issues

Reviewed against commit `95b8449616c7a76691bbf6246a64c750b49f70b4`.

## 0. Current status correction

| ID | Current real status |
|---|---|
| W09 | **PARTIAL** — monthly equivalent used for savings, but script/opportunity still expose raw current price |
| W11 | **MOSTLY FIXED** in map path; tracker TODO is stale |
| W13 | **OPEN** — correction insert still returns `Unit` |
| W14 | **PARTIAL/FIXED CORE** — original query used now, but regex is still weak |
| W15 | **PARTIAL** — category partly applied in memory; location ignored |
| W16 | **OPEN** — raw SQL prefilter + raw fallback still exists |
| W20 | **MOSTLY FIXED** — TODO comment is stale |
| W22 | **PARTIAL** — validation exists, but creation not atomic and candidate path bypasses it |
| W23 | **MOSTLY FIXED** — candidate uses `RecurrenceCalculator.nextOccurrence()` |
| W24 | **DEFERRED / OPTIONAL MIGRATION** |
| W25 | **DEFERRED / BETA** |
| W26 | **PARTIAL** — map fixed; audit all date filters |
| W27 | **OPEN** — GPS still fetched immediately after permission grant |
| W28 | **OPEN** — no central `GeoCoordinate` value validation found |
| W29 | **OPEN** — area/travel engines still raw-sum `effectiveAmount` |
| W30 | **OPEN/PARTIAL** — legacy NL still broad date-bounded pull |
| W31 | **OPEN** — no snapshot/keyset contract |
| W34 | **DEFERRED/PARTIAL** — history privacy needs storage policy |

---

# PR 1 — Tracker + TODO comment reconciliation

## Fix tracker statuses

Use:

```text
FIXED
PARTIAL
OPEN
CONTAINED
DEFERRED_DESIGN
```

Update:

```text
W11 → FIXED/PARTIAL
W14 → PARTIAL
W20 → FIXED/PARTIAL
W23 → FIXED/PARTIAL
W09/W15/W16/W22/W27/W29/W30/W31 → OPEN/PARTIAL
```

## Fix misleading comments

### `NaturalLanguageSearchEngine`

Current comments say amount filtering is fixed, but code still does raw fallback and SQL prefilter. Replace with:

```kotlin
// W16 PARTIAL: Legacy NL still must not push minAmount/maxAmount to DAO.
// Currency-aware filtering must be applied after normalization.
// Failed conversions must be excluded and surfaced as partial.
```

### `WarrantyTrackerRepository`

Remove stale:

```kotlin
// TODO (W20): Use half-open warranty dates
```

Replace with:

```kotlin
// W20 FIXED for new paths: warranty end date is represented as exclusive
// end boundary. Keep tests for legacy persisted rows.
```

### `MerchantLocationDao`

Change “0 = skipped” wording to safer:

```kotlin
// W13: Change return type to Long. Treat non-positive/ignored insert result
// as conflict/failure, verified by Room integration test.
```

Acceptance:

```text
No TODO-only item is labeled fixed.
No comment claims fixed behavior while code still has fallback/raw path.
```

---

# PR 2 — Legacy NL safety bundle: W14, W15, W16, W30, W31

## W14 — Merchant extraction

Current:

```kotlin
extractMerchants(normalized, query)
```

Good start. But regex only catches one word after `at/from`.

Implement:

```kotlin
MerchantResolver.resolve(query)
```

Inputs:

```text
known merchant aliases from repository
case-insensitive contains/fuzzy match
regex fallback for "at/from <merchant phrase>"
```

Output:

```kotlin
data class MerchantMatch(
    val merchantKey: String,
    val displayName: String,
    val confidence: Double
)
```

Tests:

```text
extracts "Amazon"
extracts "Coffee Island"
extracts lowercase "at lidl"
does not treat category/location as merchant
```

---

## W15 — Parsed filters ignored

Short-term:

```text
category filter: apply
location filter: mark unsupported
```

Add to result model:

```kotlin
data class UnsupportedLegacyFilters(
    val categories: Boolean,
    val locations: Boolean
)
```

Apply category by resolving parsed names to IDs.

Do **not** show location as applied until a real geo query exists.

Tests:

```text
food query only returns food category
location query returns unsupportedLocations=true
combined merchant+category narrows correctly
```

---

## W16 — Currency-safe amount filter

Current bad behavior:

```text
min/max pushed to DAO
foreign conversion failure falls back to raw amount
```

Fix:

```text
1. never pass minAmount/maxAmount to DAO in legacy NL
2. fetch date/type/merchant/category narrowed rows
3. normalize each row to home currency with convertAsOf(expense.date)
4. failed conversion excluded
5. result carries dataQuality warning
```

Add:

```kotlin
LegacyNlFilterResult(
    results,
    dataQuality
)
```

Tests:

```text
USD row converted before "over 50"
GBP missing rate excluded + partial warning
no raw fallback
```

---

## W30/W31 — broad paging and snapshot stability

For MVP:

```text
single transaction/snapshot query with explicit limit
```

Repository API:

```kotlin
searchNlExpenses(
    startInclusive: Long,
    endExclusive: Long,
    merchants: List<String>?,
    categoryIds: Set<Long>?,
    limit: Int,
    after: SearchCursor? = null
)
```

Use keyset cursor:

```kotlin
SearchCursor(date: Long, id: Long)
```

SQL ordering:

```sql
ORDER BY date DESC, id DESC
```

Next page:

```sql
WHERE (date < :cursorDate OR (date = :cursorDate AND id < :cursorId))
```

Tests:

```text
no duplicate rows across pages
new insert between pages does not duplicate prior rows
query does not load whole history
```

---

# PR 3 — Location correctness bundle: W11, W13, W26, W27, W28, W29

## W11 — Location insights include non-spending

Current map path filters spending-only before `computeNormalized()`. Keep it.

Add tests:

```text
deposit located row excluded from insights
transfer located row excluded from heatmap
purchase included
```

Also update tracker to **FIXED for map path**.

---

## W13 — Manual correction insert failure

Change DAO:

```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertCorrection(correction: MerchantLocationCorrection): Long
```

Repository:

```kotlin
sealed interface SaveCorrectionResult {
    data class Created(val id: Long) : SaveCorrectionResult
    data class Conflict(val existing: MerchantLocationCorrection?) : SaveCorrectionResult
    data class CacheUpdated(val id: Long) : SaveCorrectionResult
    data class Failed(val reason: String) : SaveCorrectionResult
}
```

Flow:

```text
insert correction
if conflict, load existing by normalizedMerchantName+areaKey
update existing explicitly or return Conflict
only show "Correction saved" if Created/Updated succeeds
```

Tests:

```text
insert success returns Created
conflict returns Conflict or updates explicitly
cache not updated if correction failed
UI/ViewModel does not show success on failure
```

---

## W26 — Half-open date range audit

Map uses `< end`, good.

Audit:

```text
NaturalLanguageSearchEngine
Analytics period queries
Budget period queries
Review/date filters
Export date range
Cashflow/forecast period filters
```

Rule:

```text
[startInclusive, endExclusive)
```

Add central:

```kotlin
PeriodRange(startInclusiveMs, endExclusiveMs)
```

Tests:

```text
transaction exactly at endExclusive excluded
transaction at start included
adjacent ranges do not double-count
```

---

## W27 — Defer GPS fetch

Current:

```kotlin
onPermissionResult(true) -> fetchDeviceLocation()
```

Change to:

```text
permission granted only updates state
fetch GPS only for:
- center-on-me
- POI search needing bias
- explicit "use my location"
- correction flow if user requests nearby bias
```

Add:

```kotlin
fun onCenterOnMeRequested()
fun onUseDeviceLocationBiasRequested()
```

Tests:

```text
permission grant does not call location provider
center-on-me calls provider if privacy allowed
privacy denied blocks provider
```

---

## W28 — Coordinate validation

Add:

```kotlin
@JvmInline
value class Latitude private constructor(val value: Double)

@JvmInline
value class Longitude private constructor(val value: Double)

data class GeoCoordinate(
    val latitude: Latitude,
    val longitude: Longitude
)
```

Factory:

```kotlin
GeoCoordinate.create(lat, lon): Result<GeoCoordinate>
```

Reject:

```text
NaN
Infinity
lat outside -90..90
lon outside -180..180
null-island 0,0 unless explicitly allowed
```

Use in:

```text
LocationResolver results
manual correction
pin expense
POI selection
map marker creation
```

Tests:

```text
reject NaN/Infinity
reject out-of-range
reject 0,0 by default
allow 0,0 only with explicit flag
```

---

## W29 — Area/travel engines raw-sum mixed currencies

Current:

```text
AreaSpendingEngine uses expense.effectiveAmount
TravelDetectionEngine uses expense.effectiveAmount
```

Add normalized variants:

```kotlin
AreaSpendingEngine.computeNormalized(expenses: List<LocatedMoneyExpense>)
TravelDetectionEngine.computeNormalized(expenses: List<LocatedMoneyExpense>)
```

Return quality:

```kotlin
LocationMoneyDataQuality(
    isPartial,
    excludedCount,
    warnings
)
```

Rules:

```text
only HOME_CURRENCY/CONVERTED rows included
failed conversions excluded + warning
all output totals in home currency
```

Tests:

```text
area total converts USD to EUR
missing GBP excluded + partial
travelSpend excludes failed conversion
home/local/travel totals sum to included total
```

---

# PR 4 — Warranty/subscription bundle: W20, W22, W23, W24

## W20 — Warranty end-date semantics

Code mostly fixed. Finish with tests and cleanup.

Tests:

```text
warranty valid through final calendar day
expires at exclusive next-day boundary
expiringSoon includes correct range
legacy persisted start-of-day rows handled or migrated
```

Optional migration:

```text
if existing warrantyEndDate equals start-of-day, normalize to endExclusive
```

---

## W22 — Subscription create contract

Current `validateAndCreate()` validates and sets `isSubscription=true`, but not atomic.

Fix:

```kotlin
database.withTransaction {
    val id = recurringExpenseRepository.insert(subscription)
    if (request.recordPriceHistory) {
        priceHistoryDao.insert(...)
    }
    subscription.copy(id = id)
}
```

Also ensure candidate acceptance uses the same engine method.

Replace direct candidate path:

```kotlin
repository.insertSubscription(subscription)
repository.insertPriceHistory(...)
repository.markCandidateAsConverted(...)
```

with engine method:

```kotlin
subscriptionManagerEngine.acceptCandidate(candidate)
```

Inside transaction:

```text
create subscription
insert baseline
mark candidate converted
```

Tests:

```text
subscription insert rolls back if baseline insert fails
createdAt set
currency uppercase
isSubscription true
candidate accept uses RecurrenceCalculator
candidate accept is atomic
```

---

## W23 — Candidate date fixed millis

Already mostly fixed in ViewModel. Move to engine/repository so it is not UI-owned.

Add:

```kotlin
SubscriptionManagerEngine.acceptCandidate(candidate)
```

Uses:

```kotlin
RecurrenceCalculator.nextOccurrence(candidate.lastSeen, frequency)
```

Tests:

```text
Jan 31 monthly -> calendar-correct next occurrence
annual leap-year edge
quarterly month-end edge
```

---

## W24 — Candidate uniqueness

If you want implementation now:

Migration:

```sql
ALTER TABLE subscription_candidates ADD COLUMN billingCycleDay INTEGER;
CREATE UNIQUE INDEX IF NOT EXISTS idx_sub_candidates_pending_cycle
ON subscription_candidates(canonicalMerchant, billingCycleDay)
WHERE userAction = 'pending';
```

Backfill:

```text
billingCycleDay = local day-of-month from lastSeen
```

If not now, mark:

```text
DEFERRED_DESIGN
```

because it is schema-changing and product-policy dependent.

---

# PR 5 — Bill negotiation bundle: W09, W25

## W09 — Compare monthly-to-monthly everywhere

Current engine uses monthly equivalent for savings, but `NegotiationOpportunity.currentPrice` remains raw billing-cycle amount and script uses `subscription.amount`.

Change model:

```kotlin
data class NegotiationOpportunity(
    val rawBillingAmount: Double,
    val rawBillingCurrency: String,
    val rawBillingCycle: RecurrenceFrequency,
    val monthlyEquivalentPrice: Double,
    val monthlyCurrency: String,
    val marketAverageMonthlyPrice: Double,
    val competitiveMonthlyPrice: Double,
    ...
)
```

Script must use:

```text
monthlyEquivalentPrice vs competitiveMonthlyPrice
```

But also mention:

```text
“You currently pay €120/year, equivalent to €10/month.”
```

Tests:

```text
annual 120 compared as 10/month
quarterly 90 compared as 30/month
weekly converted using average month factor
script does not compare annual raw to monthly market
```

---

## W25 — MarketRateProvider

Create:

```kotlin
interface MarketRateProvider {
    suspend fun getRates(
        serviceType: ServiceType,
        region: String,
        currency: String
    ): MarketRateResult
}
```

Result:

```kotlin
data class MarketRateQuote(
    val averageMonthlyPrice: Double,
    val competitiveMonthlyPrice: Double,
    val bestMonthlyPrice: Double,
    val currency: String,
    val region: String,
    val source: String,
    val lastUpdatedAt: Long,
    val confidence: Double
)
```

Implement now:

```text
StaticMarketRateProvider
```

but make metadata explicit:

```text
source = "static_seed"
region = "GR"
currency = "EUR"
confidence = low/medium
```

Engine behavior:

```text
if subscription currency != rate currency:
  convert monthly subscription to rate currency or convert rate to home currency
if stale:
  opportunity.dataQuality.warning
```

Tests:

```text
rate provider requested with region/currency
stale rate produces warning
foreign subscription converted before comparison
no hardcoded EUR in engine output
```

---

# PR 6 — Conversation privacy: W34

## W34 — Raw sensitive queries in history

Add storage mode:

```kotlin
enum class AssistantHistoryMode {
    OFF,
    REDACTED,
    RAW
}
```

Settings:

```text
retentionDays
storePayloadJson
storeDiagnostics
```

Repository behavior:

```text
OFF: do not persist
REDACTED: store redacted text and safe payload only
RAW: store raw text, explicit opt-in
```

Use existing redactor:

```kotlin
CloudPayloadRedactor.redactText(query)
```

Add purge job:

```kotlin
deleteMessagesOlderThan(cutoff)
```

Tests:

```text
OFF stores nothing
REDACTED does not store merchant/account-like raw text
RAW stores raw only when opted in
retention purge deletes old rows
clear history deletes sessions/messages
```

---

# Suggested execution order

```text
1. PR 1: tracker/TODO reconciliation
2. PR 2: legacy NL safety W14-W16/W30/W31
3. PR 3: W13 correction insert result + W27 GPS defer
4. PR 4: W29 area/travel MoneyAggregate normalization
5. PR 5: W22/W23 subscription atomic candidate path
6. PR 6: W09/W25 negotiation monthly/provider model
7. PR 7: W20 tests + W26 date-range audit
8. PR 8: W28 GeoCoordinate validation
9. PR 9: W34 redacted assistant history
10. PR 10: W24 candidate uniqueness if you choose not to defer
```

---

# What can be marked fixed now

After tracker reconciliation:

```text
W11: fixed for SpendingMapViewModel path, keep tests.
W20: fixed for new warranty/return paths, remove stale TODO.
W23: fixed in UI path, but move to engine for durability.
W14: fixed for lowercasing bug, keep enhancement for multi-word/alias.
```

# What should remain open

```text
W13
W15
W16
W22
W27
W28
W29
W30
W31
W34
```

# What can be deferred

```text
W24 candidate uniqueness wider schema
W25 dynamic market-rate provider, if bill negotiation remains beta
```

---

# Sources checked

- `NaturalLanguageSearchEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/95b8449616c7a76691bbf6246a64c750b49f70b4/app/src/main/java/com/yourname/expensetracker/domain/naturallanguage/NaturalLanguageSearchEngine.kt

- `SpendingMapViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/95b8449616c7a76691bbf6246a64c750b49f70b4/app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt

- `LocationInsightsEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/95b8449616c7a76691bbf6246a64c750b49f70b4/app/src/main/java/com/yourname/expensetracker/domain/location/LocationInsightsEngine.kt

- `MerchantLocationRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/95b8449616c7a76691bbf6246a64c750b49f70b4/app/src/main/java/com/yourname/expensetracker/data/repository/MerchantLocationRepository.kt

- `MerchantLocationDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/95b8449616c7a76691bbf6246a64c750b49f70b4/app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantLocationDao.kt

- `SmartBillNegotiationEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/95b8449616c7a76691bbf6246a64c750b49f70b4/app/src/main/java/com/yourname/expensetracker/domain/negotiation/SmartBillNegotiationEngine.kt

- `SubscriptionManagerEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/95b8449616c7a76691bbf6246a64c750b49f70b4/app/src/main/java/com/yourname/expensetracker/domain/subscription/SubscriptionManagerEngine.kt

- `SubscriptionManagementViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/95b8449616c7a76691bbf6246a64c750b49f70b4/app/src/main/java/com/yourname/expensetracker/ui/screens/subscription/SubscriptionManagementViewModel.kt

- `SubscriptionCandidate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/95b8449616c7a76691bbf6246a64c750b49f70b4/app/src/main/java/com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt

- `AreaSpendingEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/95b8449616c7a76691bbf6246a64c750b49f70b4/app/src/main/java/com/yourname/expensetracker/domain/location/AreaSpendingEngine.kt

- `TravelDetectionEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/95b8449616c7a76691bbf6246a64c750b49f70b4/app/src/main/java/com/yourname/expensetracker/domain/location/TravelDetectionEngine.kt

- `WarrantyTrackerRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/95b8449616c7a76691bbf6246a64c750b49f70b4/app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt