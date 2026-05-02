# Location Enrichment / Spending Map — Review Against Current Codebase

Date: Sat May 02 2026
Review against analysis at: `docs\analyses and debug master\location-enrichment-map-analysis.md`
Codebase: `app\src\main\java\com\yourname\expensetracker`

---

## VERDICT: FAIL

16 original issues + 1 new issue identified. 2 fully resolved, 4 partially resolved, 10 still present, 1 new.

---

## Issue-by-Issue Cross-Check

### [ISSUE-1] External geocoding has no hard privacy gate — **RESOLVED**

**Analysis claim:** No mandatory runtime gate for merchant-name geocoding; providers/workers don't check internally.

**Current codebase evidence:**

| Component | Privacy gate check | File | Line |
|---|---|---|---|
| `LocationResolver.resolve()` — geocode helper | `privacyGate.check(EXTERNAL_GEOCODING)` | `LocationResolver.kt` | 304 |
| `LocationResolver.resolve()` — Overpass step | `privacyGate.check(OVERPASS_API)` | `LocationResolver.kt` | 248 |
| `NominatimGeocodingService.search()` | `privacyGate.check(EXTERNAL_GEOCODING)` | `NominatimGeocodingService.kt` | 74 |
| `NominatimGeocodingService.searchMultiple()` | `privacyGate.check(EXTERNAL_GEOCODING)` | `NominatimGeocodingService.kt` | 103 |
| `NominatimGeocodingService.reverseGeocode()` | `privacyGate.check(EXTERNAL_GEOCODING)` | `NominatimGeocodingService.kt` | 121 |
| `OverpassNearbyService.findNearby()` | `privacyGate.check(OVERPASS_API)` | `OverpassNearbyService.kt` | 47 |
| `LocationBackfillWorker.doWork()` | `privacyGate.check(BACKGROUND_LOCATION_BACKFILL)` | `LocationBackfillWorker.kt` | 67 |

A fully implemented `LocationPrivacyGate` exists (`domain/privacy/LocationPrivacyGate.kt`) that gates `EXTERNAL_GEOCODING`, `BACKGROUND_LOCATION_BACKFILL`, `DEVICE_GPS_LOCATION`, and `OVERPASS_API` against `PrivacySettings`. Checks are performed at the provider level (not just UI), satisfying the analysis' requirement of *"every provider and worker should check the gate internally, not only the UI."*

**Verdict: RESOLVED** ✅

---

### [ISSUE-2] Current device location can be used for old transactions — **PARTIALLY RESOLVED**

**Analysis concern:** Overpass uses current device location even for old transactions, causing old expenses to be resolved near today's location.

**Current codebase evidence:**

- **Step 5 (GPS-bias Nominatim):** Correctly gated by `isRecent` check at line 195-196 of `LocationResolver.kt`:
  ```kotlin
  val gpsBiasLocation = if (isRecent) deviceLocation else null
  if (isRecent && gpsBiasLocation != null) { ... }
  ```
  This is the proper fix. ✅

- **Step 7 (Overpass nearby POIs):** At lines 247-248:
  ```kotlin
  val overpassLocation = deviceLocation
  val overpassAllowed = privacyGate.check(PrivacyCapability.OVERPASS_API)
  ```
  There is **no `isRecent` check**. Device location is used unconditionally for Overpass. The privacy gate provides an on/off switch but doesn't enforce age-based restrictions.

**Mitigation:** Overpass only runs after all other resolution steps have failed, and the privacy gate gives users control. But the fundamental concern — *an old transaction can resolve near today's device location via Overpass* — is still technically possible when Overpass is enabled.

**Verdict: PARTIALLY RESOLVED** ⚠️

---

### [ISSUE-3] Overpass single result is auto-accepted — **STILL PRESENT**

**Analysis concern:** A single Overpass POI result is auto-saved without recency, distance, name-similarity, or confidence checks.

**Current codebase evidence:** `LocationResolver.kt` lines 269-281:
```kotlin
return if (pois.size == 1) {
    val poi = pois.first()
    val resolved = LocationResolutionResult.Resolved(
        latitude = poi.latitude,
        longitude = poi.longitude,
        source = AppConfig.Location.SOURCE_OVERPASS_POI,
        osmId = poi.osmId,
        displayAddress = poi.displayAddress,
        confidence = 0.7f
    )
    locationCachePort.saveLocation(cacheKey, resolved)
    resolved
}
```

The only pre-filter is `!isNullIsland()` (line 265). None of the recommended gates (recency, GPS validity, name similarity threshold, distance threshold, confidence threshold) have been added.

**Verdict: STILL PRESENT** ❌

---

### [ISSUE-4] Manual POI selection can poison the global merchant cache — **PARTIALLY RESOLVED**

**Analysis concern:** User-selected POI saved as global cache entry, causing chain-store selection in one city to resolve future expenses everywhere.

**Current codebase evidence:**

- **Correction path** (`onSaveCorrection`, lines 261-299): Properly area-scoped. Uses the corrected coordinates as area center (B15 fix), creates a `MerchantLocationCorrection` with `areaKey`, and saves to area-scoped cache with `SOURCE_USER_MANUAL`. ✅

- **POI selection path** (`onPoiSelected`, lines 216-241): Still saves globally:
  ```kotlin
  merchantLocationRepository.saveLocation(forMarker.merchant, resolved)
  // areaKey defaults to "global" — no area scoping
  ```
  The source is `SOURCE_OVERPASS_POI` (not `USER_MANUAL`) and there is no area key. A user selecting "Lidl Glyfada" from the Overpass list creates a global cache entry that could resolve future "Lidl" transactions everywhere to Glyfada. ❌

**Verdict: PARTIALLY RESOLVED** ⚠️

---

### [ISSUE-5] Background backfill can overwrite manual location edits — **RESOLVED**

**Analysis concern:** Race condition where backfill worker writes over a user's manual pin.

**Current codebase evidence:**

1. `ExpenseDao.kt` lines 1588-1607 (`conditionallySetLocation`):
   ```sql
   UPDATE expenses
   SET latitude = :latitude, ...
   WHERE id = :expenseId
     AND latitude IS NULL
     AND longitude IS NULL
   ```
   Conditional update that only writes if coordinates are NULL. ✅

2. `LocationBackfillWorker.kt` lines 122-138: Uses `conditionallySetLocation()` and checks affected rows:
   ```kotlin
   val affected = expenseRepository.conditionallySetLocation(...)
   if (affected > 0) { resolved++ }
   else { skipped++ /* user-set location preserved */ }
   ```

**Verdict: RESOLVED** ✅

---

### [ISSUE-6] Partial coordinate rows can become invisible — **STILL PRESENT**

**Analysis concern:** Rows with `latitude != null && longitude == null` fall through both the "located" and "unlocated" filters.

**Current codebase evidence:**

| Query | SQL | Line |
|---|---|---|
| Located | `WHERE latitude IS NOT NULL AND longitude IS NOT NULL` | ExpenseDao.kt:1518 ✅ |
| Unlocated | `WHERE latitude IS NULL` | ExpenseDao.kt:1530 ❌ |
| Unlocated count | `WHERE latitude IS NULL` | ExpenseDao.kt:1538 ❌ |
| Backfill queue | `WHERE latitude IS NULL AND backfillAttempts < :maxAttempts` | ExpenseDao.kt:1546 ❌ |
| Unlocated flow | `WHERE latitude IS NULL` | ExpenseDao.kt:1624 ❌ |

All unlocated queries check only `latitude IS NULL`, not `latitude IS NULL OR longitude IS NULL`. A row with `latitude=37.98, longitude=NULL` is invisible to both located and unlocated views.

**Verdict: STILL PRESENT** ❌

---

### [ISSUE-7] Map place insights include non-spending transactions — **PARTIALLY RESOLVED**

**Analysis concern:** "Top spending places" can include income/transfer locations.

**Current codebase evidence:**

- **Heatmap:** Correctly filtered to `spendingOnlyExpenses` using `e.transactionType.toDomain().isSpending` (lines 386-405). ✅
- **Place insights:** Built from `domainExpenses` (line 408) which is computed from `filteredExpenses` (line 370-383) — **no transaction-type filter**. Deposits, transfers, withdrawals, and unknown types are included in place insights. ❌

The heatmap is fixed but `LocationInsightsEngine.compute()` is fed `domainExpenses` which includes all transaction types.

**Verdict: PARTIALLY RESOLVED** ⚠️

---

### [ISSUE-8] Marker amount uses gross amount, not effective amount — **STILL PRESENT**

**Analysis concern:** Map markers display `e.amount` instead of `e.effectiveAmount`, showing gross amount for shared expenses.

**Current codebase evidence:** `SpendingMapViewModel.kt` lines 354-367:
```kotlin
val markers = filteredExpenses.mapNotNull { e ->
    val lat = e.latitude ?: return@mapNotNull null
    val lon = e.longitude ?: return@mapNotNull null
    MapExpenseMarker(
        ...
        amount = e.amount,  // ← GROSS amount (not effective)
        ...
    )
}
```

Meanwhile, `domainExpenses` (line 377) and `heatmapExpenses` (line 399) both correctly use `e.effectiveAmount`. Only the marker display is wrong.

**Verdict: STILL PRESENT** ❌

---

### [ISSUE-9] Location analytics raw-sum currencies — **STILL PRESENT**

**Analysis concern:** All location engines sum raw `Double` amounts without currency conversion.

**Current codebase evidence:**

| Engine | File | Currency handling |
|---|---|---|
| `SpendingHeatmapEngine` | Accumulator uses raw `Double` sum | None |
| `LocationInsightsEngine` | Accumulator uses raw `Double` sum | None |
| `AreaSpendingEngine` | Uses `effectiveAmount` but no conversion | None |
| `TravelDetectionEngine` | Uses `effectiveAmount` but no conversion | None |
| `SpendingMapViewModel` | No currency conversion before passing to engines | None |

The app has `MultiCurrencyRepository` and `CurrencyConverter` infrastructure, and many `ExpenseDao` aggregate queries are deprecated with `"Use MultiCurrencyRepository"` comments. But the location engines do not use them.

**Verdict: STILL PRESENT** ❌

---

### [ISSUE-10] Background geocoding is Greece-biased/country-filtered — **STILL PRESENT**

**Analysis concern:** Hardcoded `countrycodes=gr` and Greece viewbox in background resolution.

**Current codebase evidence:** `NominatimGeocodingService.kt`:

- `search()` calls `buildUrl(..., useCountryFilter = true)` (line 81 → line 293): `countrycodes=gr` and Greece viewbox always applied.
- `searchMultiple()` calls `buildUrl(..., useCountryFilter = false)` (line 111): country filter disabled for interactive search.
- No configurable home country, no transaction/account country detection, no travel mode.

**Verdict: STILL PRESENT** ❌

---

### [ISSUE-11] Nominatim retry loop can violate rate policy — **STILL PRESENT**

**Analysis concern:** `executeWithRetry()` retries within the same `withRateLimit` block, bypassing the 1 req/sec mutex for retries.

**Current codebase evidence:** `NominatimGeocodingService.kt`:

- `withRateLimit` (line 57-65) enforces ~1.1 sec between top-level requests.
- `executeWithRetry()` (lines 242-273) retries up to 3 times with delays of 300ms → 600ms → 1200ms — **all within one `withRateLimit` acquisition**. Retry attempts are not individually rate-limited.
- 429 responses are retried internally (line 253): `if (response.code >= 500 || response.code == 429)` — no persistent backoff, no deferral to WorkManager.

**Verdict: STILL PRESENT** ❌

---

### [ISSUE-12] Backfill retry accounting misses retryable/provider exceptions — **PARTIALLY RESOLVED**

**Analysis concern:** Transient errors don't increment per-expense attempt counters, leading to indefinite retries.

**Current codebase evidence:** `LocationBackfillWorker.kt`:

- `NeedsUserSelection` → `incrementBackfillAttempts()` called (line 152). ✅
- `Unresolved` → `incrementBackfillAttempts()` called (line 156). ✅
- `Retryable` → `shouldRetry = true; failed++` but **no** `incrementBackfillAttempts()` (lines 140-147). ❌
- Resolver-thrown exception → `shouldRetry = true; failed++` but **no** attempt increment (lines 114-118). ❌

Since `getUnlocatedExpensesForBackfill()` filters by `backfillAttempts < :maxAttempts`, the Retryable and exception paths can retry the same expense indefinitely — only the `shouldRetry` flag controls retry, and `incrementBackfillAttempts` is not called for these paths.

**Verdict: PARTIALLY RESOLVED** ⚠️

---

### [ISSUE-13] Area spending can merge unrelated places with same area name — **STILL PRESENT**

**Analysis concern:** Two "Center" or "Downtown" areas in different cities are merged by name alone.

**Current codebase evidence:** `AreaSpendingEngine.kt` lines 84-97:
```kotlin
val byArea = HashMap<String, Accumulator>()
for ((_, acc) in cells) {
    val resolvedAreaName = selectRepresentativeAreaName(acc.areaCandidates)
    val existing = byArea[resolvedAreaName]  // merged by name only
    ...
}
```

No city/region/coarse-geo-bucket qualifier is added to the area key before merging.

**Verdict: STILL PRESENT** ❌

---

### [ISSUE-14] Travel detection uses truncating buckets for negative coordinates — **STILL PRESENT**

**Analysis concern:** `.toLong()` truncates toward zero, producing wrong bucket values for negative coordinates.

**Current codebase evidence:** `TravelDetectionEngine.kt` line 68:
```kotlin
val cell = GridCell((lat / HOME_GRID_DEG).toLong(), (lon / HOME_GRID_DEG).toLong())
```

The `MerchantLocationGrid.bucketCoordinate()` helper uses `floor()` and is already imported (via `GeoUtils`), but `TravelDetectionEngine` does not use it. SQL queries in `ExpenseDao` use `MerchantLocationGrid.LATITUDE_BUCKET_SQL` which handles negative coordinates correctly.

**Verdict: STILL PRESENT** ❌

---

### [ISSUE-15] Travel detection assumes most frequent spending area is home — **STILL PRESENT**

**Analysis concern:** Home is purely frequency-based, susceptible to misidentification.

**Current codebase evidence:** `TravelDetectionEngine.kt` line 74:
```kotlin
val homeCell = cellCounts.maxByOrNull { it.value }?.key ?: return null
```

No user confirmation, no confidence score, no nighttime/weekend signal, no user-selected home city. Minimum 5 expenses required (line 56), but no other robustness measures.

**Verdict: STILL PRESENT** ❌

---

### [ISSUE-16] Location write API accepts invalid coordinates — **STILL PRESENT**

**Analysis concern:** Manual location writes (pins, corrections) don't validate coordinate ranges, NaN, infinity, or Null Island.

**Current codebase evidence:**

| Write path | Validation | File | Lines |
|---|---|---|---|
| `assignLocationToExpense()` | None | `SpendingMapViewModel.kt` | 315-331 |
| `onSaveCorrection()` | None | `SpendingMapViewModel.kt` | 261-299 |
| `updateExpenseLocation()` | None (unconditional) | `ExpenseDao.kt` | 1560-1577 |
| `conditionallySetLocation()` | Conditional on NULL, but no range check | `ExpenseDao.kt` | 1588-1607 |

The resolver checks `isNullIsland()` internally but manual UI paths bypass it. No `LocationDraftValidator` exists.

**Verdict: STILL PRESENT** ❌

---

## New Issues Found (Not in Original Analysis)

### [ISSUE-17] [MAJOR] `onPoiSelected` uses `SOURCE_OVERPASS_POI` for user-confirmed selection — `SpendingMapViewModel.kt`

**Problem:** When a user explicitly picks a POI from the Overpass candidate list, the expense's `locationSource` is set to `SOURCE_OVERPASS_POI` (line 222). This is indistinguishable from an auto-accepted Overpass result (Issue 3). A user-confirmed location should use a distinct source like `USER_CONFIRMED_POI` to indicate user involvement and protect it from future automated overwrites.

There is no `USER_CONFIRMED_POI` source in `AppConfig.Location`.

**Suggested fix:** Add `SOURCE_USER_CONFIRMED_POI` to `AppConfig.Location` and use it in `onPoiSelected` and the resolver's auto-save path to differentiate user action from automated resolution.

---

## Summary Table

| Issue | Description | Severity | Status |
|---|---|---|---|
| 1 | Privacy gate | Critical | RESOLVED ✅ |
| 2 | GPS for old transactions | Critical | PARTIALLY ⚠️ |
| 3 | Overpass auto-accept | High | STILL PRESENT ❌ |
| 4 | Global cache poison | Critical | PARTIALLY ⚠️ |
| 5 | Backfill overwrite | High | RESOLVED ✅ |
| 6 | Partial coordinates | High | STILL PRESENT ❌ |
| 7 | Place insights non-spending | High | PARTIALLY ⚠️ |
| 8 | Marker gross amount | High | STILL PRESENT ❌ |
| 9 | Currency raw-sum | Critical | STILL PRESENT ❌ |
| 10 | Greece bias | Critical | STILL PRESENT ❌ |
| 11 | Nominatim retry rate | High | STILL PRESENT ❌ |
| 12 | Backfill retry accounting | High | PARTIALLY ⚠️ |
| 13 | Area name merging | Medium | STILL PRESENT ❌ |
| 14 | Travel negative coords | Medium | STILL PRESENT ❌ |
| 15 | Travel home inference | Medium | STILL PRESENT ❌ |
| 16 | Invalid coordinate writes | High | STILL PRESENT ❌ |
| 17 | POI source labeling | Major | NEW |

---

## Coverage

- **Requirements met:** Partial. The privacy gate infrastructure (Issue 1) and backfill race protection (Issue 5) represent significant progress. 4 of 16 original issues are fully or partially addressed in meaningful ways.
- **Testing adequate:** No tests were found for any of the location enrichment paths. The analysis' 17 regression tests remain unimplemented.

---

## Top Three Actions (by impact)

1. **Fix Issue 6 (partial coordinates):** Change all unlocated queries to `latitude IS NULL OR longitude IS NULL` — a simple SQL fix with no architectural risk.
2. **Fix Issue 3 (Overpass auto-accept):** Add `isRecent` gate to Overpass step and require higher confidence/distance thresholds for auto-accept.
3. **Fix Issue 4 completion (POI selection):** Make `onPoiSelected` save as area-scoped correction with `USER_CONFIRMED_POI` source, not global cache.
