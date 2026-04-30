# Location Enrichment / Spending Map Deep Analysis

Branch: `master-refactor`

Scope:
- merchant geocoding
- location cache/corrections
- background location backfill
- spending map ViewModel
- heatmap/place/area/travel insights
- geocoding providers: Nominatim, Overpass, Photon/Geoapify/Google via composite service

This is a static review.

---

## Executive verdict

The location layer has useful structure:

```text
Expense
→ LocationBackfillWorker / Map manual action
→ LocationResolver
→ corrections/cache/history clusters
→ geocoding providers
→ Expense latitude/longitude/resolvedAddress
→ map / heatmap / place insights
```

Strong foundations:
- resolver has clear priority order
- merchant keys are reused
- Nominatim has a service-level rate-limit mutex
- logs generally hash/anonymize merchant queries
- map observes located expenses reactively
- heatmap now filters spending-only transactions

But the biggest risks are:

1. **external geocoding lacks a hard privacy/consent gate**
2. **current device location can be used for old transactions**
3. **manual/Overpass selections can poison global merchant cache**
4. **background backfill can overwrite manual edits**
5. **partial lat/lon rows can disappear from both located and unlocated views**
6. **map insights raw-sum mixed currencies and sometimes include non-spending transactions**

Highest priority fix:

> Treat location enrichment as a sensitive capability with its own gate, lifecycle state, and validated update coordinator.

---

# Critical / high-priority findings

## 1. External geocoding has no hard privacy gate

### Where

- `LocationResolver`
- `CompositeGeocodingService`
- `NominatimGeocodingService`
- `OverpassNearbyService`
- `LocationBackfillWorker`
- `SpendingMapViewModel`

### Problem

The resolver and workers can send merchant names and optional location bias to external services:

- Nominatim
- Overpass
- Photon
- Geoapify
- Google Places if enabled in interactive mode

The app asks Android location permission for showing device position, but **merchant-name geocoding is separate**. It can happen without Android GPS permission.

I did not see a mandatory runtime gate like:

```kotlin
ExternalLocationGate.requireAllowed(payloadKind)
```

### Impact

A user may deny device location permission but still have merchant names sent to external geocoding APIs during background or manual location enrichment.

This is a privacy expectation bug.

### Severity

**Critical / privacy**

### Fix

Add an explicit location privacy gate.

Suggested settings:

```text
locationInsightsEnabled
externalGeocodingEnabled
backgroundLocationBackfillEnabled
interactiveLocationSearchEnabled
usePaidGeocodingProviders
useGooglePlaces
```

Every provider and worker should check the gate internally, not only the UI.

---

## 2. Current device location can be used for old transactions

### Where

`LocationResolver.resolve()`

### Problem

The resolver computes:

```text
isRecent = now - transactionDate < 2 hours
```

But it still fetches device location before some cache/geocoding steps:

- area-scoped correction lookup
- Overpass nearby POI fallback

The Overpass fallback uses current device location even if the transaction is old.

### Impact

An old unlocated transaction can be resolved near where the user is today, not where the transaction happened.

Example:

- user has a 2024 transaction: `Coffee`
- user opens app in 2026 at home
- resolver fails name-only
- Overpass checks POIs near current home
- one nearby coffee shop exists
- app auto-resolves old transaction to today’s nearby shop

### Severity

**Critical**

### Fix

Only use current GPS for:
- transactions within the recent threshold, or
- explicit interactive user action where the UI clearly says “search near me”.

For background backfill of old expenses:
- do not use current GPS
- use historical clusters, merchant cache, merchant address hints, or manual review

---

## 3. Overpass single result is auto-accepted

### Where

`LocationResolver`

If Overpass returns one POI, resolver auto-saves it.

### Problem

Even a single Overpass result can be wrong, especially when the search is based on current device location or a broad merchant name.

### Impact

Wrong coordinates become persisted to the expense and cache.

This is especially risky for:
- common merchant names
- chains
- translated/Greeklish names
- old transactions
- low-confidence provider results

### Severity

**High**

### Fix

Do not auto-accept Overpass results unless:
- transaction is recent,
- GPS bias is valid,
- name similarity is high,
- distance is within a strict threshold,
- confidence exceeds threshold.

Otherwise return `NeedsUserSelection`.

---

## 4. Manual POI selection can poison the global merchant cache

### Where

`SpendingMapViewModel.onPoiSelected()`

The selected POI is saved via:

```kotlin
merchantLocationRepository.saveLocation(forMarker.merchant, resolved)
```

with no area key, so it defaults to global cache.

### Problem

If the user selects one branch of a chain, that branch can become the global cached location for the merchant.

### Impact

Future expenses for the same merchant in another city/neighborhood may resolve to the previously selected branch.

Example:

- user selects Lidl Glyfada
- cache stores global `lidl → Glyfada`
- later Lidl Thessaloniki resolves to Glyfada

### Severity

**Critical for chain merchants**

### Fix

User-selected POI should create an **area-scoped correction**, not a global cache entry.

Use:

```text
merchantKey + areaKey
```

and source:

```text
USER_MANUAL or USER_CONFIRMED_POI
```

not generic `OVERPASS_POI`.

---

## 5. Background backfill can overwrite manual location edits

### Where

- `LocationBackfillWorker`
- `ExpenseDao.updateLocation()`

`updateLocation()` is unconditional:

```sql
UPDATE expenses
SET latitude = ..., longitude = ...
WHERE id = :expenseId
```

### Problem

Race:

1. worker fetches unlocated expense
2. user manually pins it
3. worker finishes geocoding
4. worker writes provider result over user pin

### Impact

Manual correction is lost.

### Severity

**High**

### Fix

Use conditional update for background writes:

```sql
WHERE id = :expenseId
  AND latitude IS NULL
  AND longitude IS NULL
  AND locationSource IS NOT 'USER_MANUAL'
```

Better: add:

```text
locationUserConfirmed: Boolean
locationUpdatedAt
locationResolvedBy
```

Backfill must never overwrite user-confirmed data.

---

## 6. Partial coordinate rows can become invisible

### Where

`ExpenseDao`

Located query:

```sql
latitude IS NOT NULL AND longitude IS NOT NULL
```

Unlocated query/count:

```sql
latitude IS NULL
```

### Problem

If a row has:

```text
latitude != null
longitude == null
```

then:
- it is not located
- it is not unlocated
- it may not appear in the map or backfill queue

### Impact

Bad partial location state can become stuck.

### Severity

**High**

### Fix

Define unlocated as:

```sql
latitude IS NULL OR longitude IS NULL
```

Also validate all location writes:

```text
both lat/lon present or both null
```

Add DB CHECK if possible.

---

## 7. Map place insights include non-spending transactions

### Where

`SpendingMapViewModel.recomputeMapData()`

Heatmap correctly filters spending-only expenses.

But place insights are computed from `domainExpenses`, which is built from all located expenses, including:
- deposits
- transfers
- unknown types
- maybe not-mine rows

### Impact

“Top spending places” can include income/transfer locations.

### Severity

**High**

### Fix

Use the same spending-only, ownership-adjusted list for:
- heatmap
- place insights
- area spending
- travel spending

If the map intentionally shows all transaction types, label separate layers:
- spending
- income
- transfers

Do not mix them in one “spending” insight.

---

## 8. Marker amount uses gross amount, not effective amount

### Where

`SpendingMapViewModel.recomputeMapData()`

Markers are created with:

```kotlin
amount = e.amount
```

while heatmap/domain amounts use `e.effectiveAmount`.

### Impact

Shared/not-mine transactions can show wrong marker amounts.

Example:
- group dinner gross = €100
- my share = €25
- marker displays €100

### Severity

**High**

### Fix

Use `effectiveAmount` for personal spending maps, or show both:

```text
gross amount
my share
```

depending on UI mode.

---

## 9. Location analytics raw-sum currencies

### Where

- `SpendingHeatmapEngine`
- `LocationInsightsEngine`
- `AreaSpendingEngine`
- `TravelDetectionEngine`
- `SpendingMapViewModel`

All location analytics use raw `Double` amounts without currency.

### Impact

Mixed currency example:

```text
€20 at Athens
$20 at New York
```

can become:

```text
40 total spend
```

with no currency meaning.

### Severity

**Critical if multi-currency is user-facing**

### Fix

Use normalized/base money or currency buckets.

Every location aggregate should declare:
- amount
- currency
- conversion status

---

## 10. Background geocoding is Greece-biased/country-filtered

### Where

`NominatimGeocodingService.buildUrl()`

Background search uses:

```text
countrycodes=gr
Greece viewbox
```

Interactive search disables the country filter.

### Problem

If the app is used outside Greece, background resolution can silently force results into Greece.

### Impact

A US/UK/Germany transaction can resolve to a Greece location with matching merchant name.

### Severity

**Critical if app is not Greece-only**

### Fix

Make geocoding country bias configurable:
- home country
- transaction country/account country
- travel mode
- no country filter for uncertain cases

If Greece-only is intentional, the UI should say so.

---

## 11. Nominatim retry loop can violate provider rate policy

### Where

`NominatimGeocodingService`

There is a service-level mutex enforcing ~1 request/sec before `executeRequest()`.

But `executeRequest()` calls `executeWithRetry()`, which can retry HTTP 429/5xx with delays like 300ms/600ms inside the same rate-limit window.

### Impact

A single logical request can produce multiple HTTP calls faster than Nominatim’s 1/sec policy, especially on 429.

### Severity

**High / provider-policy risk**

### Fix

Do not immediately retry 429.

For 429:
- return `RateLimited`
- persist backoff state
- retry later through WorkManager

For 5xx:
- retry, but still pass each HTTP attempt through the same rate limiter.

---

## 12. Backfill retry accounting still misses retryable/provider exceptions

### Where

`LocationBackfillWorker`

For `Retryable` and thrown resolver exceptions:
- `shouldRetry = true`
- `failed++`
- but no per-expense attempt is incremented

Only `NeedsUserSelection` and `Unresolved` increment attempts.

### Impact

A merchant/provider that keeps returning transient errors can be retried indefinitely.

### Severity

**High**

### Fix

Separate:
- permanent attempts
- transient attempts
- next eligible retry time
- last error

Do not retry the same expense every worker run.

---

## 13. Area spending can merge unrelated places with the same area name

### Where

`AreaSpendingEngine`

It clusters by grid cell, then merges cells only by representative area name.

### Problem

Two unrelated areas with the same label can merge.

Example:
- “Center”
- “Downtown”
- same suburb name in different cities

### Impact

Area totals can combine unrelated locations.

### Severity

**Medium / High**

### Fix

Merge by:

```text
normalized area name + city/region + coarse geo bucket
```

not area name alone.

---

## 14. Travel detection uses truncating buckets for negative coordinates

### Where

`TravelDetectionEngine`

Home area bucket:

```kotlin
(lat / HOME_GRID_DEG).toLong()
```

This truncates toward zero.

But `MerchantLocationGrid` already notes SQLite truncation problems and uses floor-based logic.

### Impact

For western/southern hemisphere coordinates, home/travel buckets can be wrong.

### Severity

**Medium / High**

### Fix

Use:

```kotlin
MerchantLocationGrid.bucketCoordinate()
```

or `floor()` consistently.

---

## 15. Travel detection assumes most frequent spending area is home

### Where

`TravelDetectionEngine`

### Problem

Home is inferred from the grid cell with most located transactions.

This can be wrong for:
- work area
- shopping district
- bad chain cache
- travel-heavy users
- small data sets
- imported historical data with uneven locations

### Impact

Home/local/travel spend can be misleading.

### Severity

**Medium / High UX risk**

### Fix

Ask for optional home area confirmation or use robust signals:
- nighttime/weekend transactions
- user-selected home city
- recurring local merchants
- confidence score

---

## 16. Location write API accepts invalid coordinates

### Where

- `ExpenseDao.updateLocation()`
- `SpendingMapViewModel.assignLocationToExpense()`
- `onSaveCorrection()`

### Problem

Manual writes can save:
- latitude outside -90..90
- longitude outside -180..180
- Null Island `(0,0)`
- partial coordinates if future callers misuse lower layers

Resolver checks Null Island, but manual paths should too.

### Severity

**High**

### Fix

Create `LocationDraftValidator`.

Reject:
- invalid ranges
- NaN/infinite
- `(0,0)` unless explicitly allowed
- one coordinate missing

---

# Strong parts

## 1. Resolver priority order is sensible

The resolver checks:
1. user correction
2. area correction
3. historical merchant clusters
4. cache
5. GPS-biased geocode
6. name-only geocode
7. nearby POIs
8. unresolved

Good structure.

## 2. Merchant key reuse is good

Location repository uses `MerchantKeyGenerator`, aligning with dedupe/search/merchant normalization.

Good.

## 3. Nominatim logs are mostly privacy-aware

Logs use query hashes / redacted routes, not raw query strings.

Good pattern.

## 4. Map data is reactive

`SpendingMapViewModel` collects `getLocatedExpenses()` so map refreshes as DB changes.

Good.

## 5. Heatmap explicitly filters spending-only transactions

This is correct and should be extended to place/area/travel insights.

---

# Recommended fix order

## PR 1 — Add external location privacy gate

Create:

```kotlin
ExternalLocationGate
```

Use it in:
- `LocationResolver`
- all geocoding providers
- `LocationBackfillWorker`
- map interactive search
- reverse geocode

## PR 2 — Fix GPS/current-location semantics

Rules:
- background old transactions must not use current GPS
- Overpass near-device only for recent or interactive search
- area correction should not require fetching device GPS unless needed

## PR 3 — Add `LocationUpdateCoordinator`

All location writes go through one coordinator:
- validates coordinates
- preserves manual edits
- handles source precedence
- writes cache/correction consistently
- records audit metadata

## PR 4 — Fix cache scoping

Never save branch-specific user/Overpass selections as global cache.

Use:
- merchantKey
- areaKey
- confidence
- source
- userConfirmed flag

## PR 5 — Fix location SQL consistency

Change unlocated queries/counts to:

```sql
latitude IS NULL OR longitude IS NULL
```

Add validation/DB checks for coordinate pairs.

## PR 6 — Make all map analytics spending/currency-safe

Use:
- spending-only rows
- effective amount
- currency/base amount
- declared display currency

## PR 7 — Provider rate/backoff hardening

Nominatim retry attempts should pass through the same rate limiter.
429 should persist backoff and stop immediate retry.

## PR 8 — Improve travel/area inference

Use floor-based grid buckets and confidence labels.
Optionally ask user to confirm home area.

---

# Regression tests to add

1. External geocoding disabled → no provider HTTP call.
2. Background backfill disabled → worker does nothing/cancels.
3. Old transaction does not use current GPS/Overpass.
4. Recent transaction may use GPS bias if permission and setting allow.
5. User-selected POI is saved as area-scoped correction, not global cache.
6. Lidl branch A does not resolve Lidl branch B through global cache.
7. Backfill cannot overwrite a manual/user-confirmed location.
8. Partial lat/lon row appears in unlocated queue.
9. Invalid lat/lon and `(0,0)` are rejected on manual pin.
10. Place insights exclude deposits/transfers.
11. Marker amount uses effective amount or clearly displays gross/my-share.
12. Mixed-currency heatmap does not raw-sum.
13. Nominatim 429 does not immediate-retry within 1 second.
14. Retryable geocoding failure increments transient retry state.
15. Greece country filter is disabled/configurable for non-Greece users.
16. Travel bucket logic works for negative longitudes.
17. Area spending does not merge same-name areas across distant regions.

---

# Top three fixes

If you only fix three things first:

1. **Add a hard external geocoding privacy gate.**
2. **Stop using current device location for old/background transaction resolution.**
3. **Prevent global cache poisoning from manual/Overpass branch selections.**

Those remove the biggest privacy and wrong-location risks.

---

# Sources reviewed

- `CODEBASE_SEGMENTS.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/docs/architecture/CODEBASE_SEGMENTS.md

- `LocationResolver.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/location/LocationResolver.kt

- `LocationModels.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/location/LocationModels.kt

- `LocationResolverPorts.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/location/LocationResolverPorts.kt

- `SpendingMapViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt

- `SpendingHeatmapEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/location/SpendingHeatmapEngine.kt

- `LocationInsightsEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/location/LocationInsightsEngine.kt

- `AreaSpendingEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/location/AreaSpendingEngine.kt

- `TravelDetectionEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/location/TravelDetectionEngine.kt

- `CompositeGeocodingService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/location/CompositeGeocodingService.kt

- `NominatimGeocodingService.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/location/NominatimGeocodingService.kt

- `OverpassNearbyService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/location/OverpassNearbyService.kt

- `AndroidForegroundLocationProvider.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/location/AndroidForegroundLocationProvider.kt

- `LocationBackfillWorker.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt

- `MerchantLocation.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantLocation.kt

- `MerchantLocationCorrection.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantLocationCorrection.kt

- `MerchantLocationDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantLocationDao.kt

- `MerchantLocationRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/MerchantLocationRepository.kt

- `ExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

- `ExpenseRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt

- `AppConfig.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/config/AppConfig.kt