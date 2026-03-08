# Location Enhancement Feature Plan

## Goal

Implement a 5-feature location enhancement segment for the ExpenseTracker Android app:

| Feature | Description |
|---------|-------------|
| **Feature A** | Merchant Location Affinity — history-based resolution that clusters past located expenses to bias Nominatim searches toward the correct city/area |
| **Feature B** | `resolvedAddress` field on `Expense` entity — persist human-readable address on each expense |
| **Feature C** | Location editing in Transactions screen — let users add/edit lat/lon from the transaction list |
| **Feature D** | Location editing in Review screen — show/edit location in `EditReviewDialog` before approving |
| **Feature E** | Unlocated expenses list on Map tab — bottom panel showing expenses without coordinates, with a "pin this" flow |

## Architecture Rules

- Strictly follow Clean Architecture: UI → Domain → Data → Database
- No direct DB calls from UI or Domain layers; always route through Repository
- ViewModels only expose `StateFlow`, no business logic in UI
- New DAOs/Repositories/Services must be bound in appropriate Hilt modules under `di/`
- Follow existing patterns for new screens, entities, parsers

## Key Project Info

- **Package:** `com.yourname.expensetracker`
- **Root path:** `C:\Users\panos\Desktop\cost agregator\ExpenseTracker\`
- **App src path:** `C:\Users\panos\Desktop\cost agregator\ExpenseTracker\app\src\main\java\com\yourname\expensetracker\`
- **Database version:** Started at v29 → Ended at **v31**
- **Navigation:** 6 tabs (0-5), tab 5 = Map

---

## Key Technical Decisions

### 1. MerchantLocation unique index change

Changed unique index from single-column (`normalizedMerchantName`) to composite (`normalizedMerchantName`, `areaKey`). Required SQLite table recreation via migration.

**Migration strategy:** Rename → Create → Copy → Drop

### 2. areaKey convention

```
Area-scoped: "${normalizedName}|${Math.round(lat/0.045)}|${Math.round(lon/0.045)}"
Global:      "${normalizedName}|global"
```

This enables clustering by geographic area with ~5km grid cells.

### 3. GeocodingService interface extension

Added optional parameters to `search()`:
- `cityHint: String? = null` — bias toward known city
- `bounded: Boolean = false` — restrict search to bounding box
- Changed `addressdetails=0` to `addressdetails=1` to get city/town/suburb details

### 4. LocationResolver changes

- Now takes `expenseRepository: ExpenseRepository` as constructor parameter (Hilt injects)
- Added Step 4.5: History-biased geocoding logic
- Calls `getMerchantLocationClusters()` to find past expense clusters

### 5. History-biased geocoding (Step 4.5)

Before calling Nominatim:
1. Query `expenses` for merchant clusters using `ROUND(lat/0.045)` grid
2. If top cluster has ≥2 occurrences → call Nominatim with `bounded=true`

This improves accuracy by narrowing search to areas where user has shopped before.

The grid calculation uses `ROUND(lat/0.045)` and `ROUND(lon/0.045)` which creates approximately 5km × 5km geographic cells for clustering.

### 7. NominatimGeocodingService Implementation Details

- Changed `addressdetails` parameter from `0` to `1` to receive city/town/suburb breakdown
- Now parses city, town, and suburb fields from the Nominatim response's `address` object
- Supports `cityHint` parameter to bias results toward user's known areas
- Uses `bounded=true` for history-biased searches to restrict to relevant area

### 8. LocationSearchPicker

Created as a new shared composable at `ui/components/LocationSearchPicker.kt`:
- Debounced search (500ms) calling Nominatim
- Result list for picking locations
- Manual lat/lon entry mode
- Clear location button

Used by Features C, D, and E.

---

## Discoveries & Implementation Details

### Key Discovery: Expense entity field name

**Important:** The `Expense` entity uses `merchant` (not `merchantName`) for the merchant name field. This caused confusion during implementation when accessing expense properties.

```kotlin
// Correct:
expense.merchant  // ✓

// Wrong (did not exist):
expense.merchantName  // ✗
```

### Database Changes

- **Entity:** Added `resolvedAddress: String? = null` to `Expense`
- **Entity:** Added `areaKey: String? = null` to `MerchantLocation`
- **Database:** Bumped from v29 → v30 → v31
- **Migration 29→30:** Recreated `merchant_locations` table with new composite index
- **Migration 30→31:** Added missing `lastResolvedAt` index

### SQL Query Fix

The `getExpensesPagedDynamic` SQL query in `ExpenseRepository` was fixed to include:
- `e.resolvedAddress` — the new address field
- `e.backfillAttempts` — for tracking geocoding attempts

### Key Methods Added

#### ExpenseDao
- `updateLocation()` — extended to accept address parameter
- `clearLocation()` — clear lat/lon/address
- `getUnlocatedExpensesFlow(limit: Int)` — reactive Flow of unlocated expenses
- `getMerchantLocationClusters()` — returns `LocationCluster` data class with merchant/lat/lon/count
- `LocationCluster` — data class with `normalizedMerchantName`, `roundedLat`, `roundedLon`, `count`

#### ExpenseRepository
- `updateExpenseLocation(expenseId, latitude, longitude, source, placeId, address)` — extended with address
- `clearExpenseLocation(expenseId)` — clear location
- `getUnlocatedExpensesFlow(limit)` — delegate to DAO

#### ReviewQueueRepository
- `approveReview()` — now accepts 9 parameters including:
  - `finalLatitude: Double?`
  - `finalLongitude: Double?`
  - `finalAddress: String?`
- Uses `SOURCE_USER_MANUAL` for user-provided locations (vs `SOURCE_DEVICE_GPS` for review-captured)
- Sets `resolvedAddress = finalAddress` in the expense

#### SpendingMapViewModel
- Added to `SpendingMapState`:
  - `unlocatedExpenses: List<Expense> = emptyList()`
  - `expenseToPin: Expense? = null`
- Added methods:
  - `onPinExpense(expense)` — opens pin sheet
  - `onDismissPinSheet()` — closes pin sheet
  - `assignLocationToExpense(expense, lat, lon, address, osmId)` — saves location

### EditReviewDialog Signature Change

The `onSave` lambda signature changed from 6 to 9 parameters:

```kotlin
// Old (6 params):
onSave: (isNotMine: Boolean, ownerName: String?, isSharedExpense: Boolean, sharedWithName: String?, mySharePercentage: Int?, myShareAmount: Double?) -> Unit

// New (9 params):
onSave: (isNotMine: Boolean, ownerName: String?, isSharedExpense: Boolean, sharedWithName: String?, mySharePercentage: Int?, myShareAmount: Double?, finalLatitude: Double?, finalLongitude: Double?, finalAddress: String?) -> Unit
```

### Constants Added

- `SOURCE_USER_MANUAL` — used when user manually provides location (Feature C, D, E)
- `SOURCE_DEVICE_GPS` — used when location captured from device during review

---

## Implementation Summary

### ✅ Step 1 — Infrastructure (DONE)

| File | Changes |
|------|---------|
| `data/database/entity/Expense.kt` | Added `val resolvedAddress: String? = null` |
| `data/database/entity/MerchantLocation.kt` | Added `areaKey`, updated unique index |
| `data/database/AppDatabase.kt` | Bumped to v30; added `MIGRATION_29_30` |
| `di/DatabaseModule.kt` | Added `MIGRATION_29_30` to migrations list |

### ✅ Step 2 — Feature B: Wire resolvedAddress (DONE)

| File | Changes |
|------|---------|
| `data/database/dao/ExpenseDao.kt` | Updated `updateLocation()`, added `clearLocation()`, `getUnlocatedExpensesFlow()`, `getMerchantLocationClusters()`, `LocationCluster` |
| `data/repository/ExpenseRepository.kt` | Extended methods, fixed SQL SELECT |
| `data/location/LocationBackfillWorker.kt` | Passes `address = result.displayAddress` |
| `ui/screens/map/SpendingMapViewModel.kt` | Added `address` param to location updates |

### ✅ Step 3 — Feature A: Merchant Location Affinity (DONE)

| File | Changes |
|------|---------|
| `data/database/dao/MerchantLocationDao.kt` | Added `getByNormalizedNameAndArea()` |
| `data/repository/MerchantLocationRepository.kt` | Added `getCachedLocationForArea()`, `getMostLikelyArea()`, updated `saveLocation()` |
| `domain/location/LocationModels.kt` | Extended `GeocodingService.search()` interface |
| `data/location/NominatimGeocodingService.kt` | Updated for `cityHint`, `bounded`, `addressdetails=1` |
| `domain/location/LocationResolver.kt` | Added `expenseRepository` dep, Step 4.5 logic |

### ✅ Step 4 — Shared Component: LocationSearchPicker.kt (DONE)

Created `ui/components/LocationSearchPicker.kt` — reusable location search composable

### ✅ Step 5 — Feature C: Location editing in Transactions screen (DONE)

| File | Changes |
|------|---------|
| `ui/screens/transactions/TransactionsViewModel.kt` | Added `updateLocation()`, `clearLocation()` |
| `ui/screens/transactions/TransactionsScreen.kt` | Full Feature C implementation: map-pin button, address subtitle, `EditLocationDialog` |

### ✅ Step 6 — Feature D: Location editing in Review screen (DONE)

| File | Changes |
|------|---------|
| `data/repository/ReviewQueueRepository.kt` | Added location params to `approveReview()`, wired `resolvedAddress` |
| `ui/screens/review/ReviewViewModel.kt` | Added location params to `approveReviewWithEdits()` |
| `ui/screens/review/ReviewScreen.kt` | Full Feature D implementation: location section in dialog, coordinates chip |

### ✅ Step 7 — Feature E: Unlocated expenses on Map tab (DONE)

| File | Changes |
|------|---------|
| `ui/screens/map/SpendingMapViewModel.kt` | Added unlocated state, collect, pin methods |
| `ui/screens/map/SpendingMapScreen.kt` | Added `UnlocatedExpensesPanel`, `PinExpenseSheet` |

### ✅ Step 8 — Build Verification (DONE)

Ran `gradlew.bat assembleDebug` — **BUILD SUCCESSFUL**

---

## Files Modified

### Database & Entity Files
| File | Purpose |
|------|---------|
| `data/database/entity/Expense.kt` | Added resolvedAddress field |
| `data/database/entity/MerchantLocation.kt` | Added areaKey, updated index |
| `data/database/AppDatabase.kt` | Version 30→31, migrations |
| `di/DatabaseModule.kt` | Added migrations |

### DAO Files
| File | Purpose |
|------|---------|
| `data/database/dao/ExpenseDao.kt` | Extended location methods, unlocated flow |
| `data/database/dao/MerchantLocationDao.kt` | Area-aware queries |

### Repository Files
| File | Purpose |
|------|---------|
| `data/repository/ExpenseRepository.kt` | Extended update/clear methods |
| `data/repository/MerchantLocationRepository.kt` | Area-based caching |
| `data/repository/ReviewQueueRepository.kt` | Location params in approve |

### Location Service Files (NEW)
| File | Purpose |
|------|---------|
| `data/location/NominatimGeocodingService.kt` | Nominatim API wrapper |
| `data/location/LocationBackfillWorker.kt` | Background geocoding |
| `data/location/OverpassNearbyService.kt` | Nearby POI queries |
| `data/location/AndroidForegroundLocationProvider.kt` | Device location |

### Domain Location Files (NEW)
| File | Purpose |
|------|---------|
| `domain/location/LocationModels.kt` | GeocodingService interface |
| `domain/location/LocationResolver.kt` | Affinity-based resolution |
| `domain/location/GeocodingResult.kt` | Result models |
| `domain/location/NearbyPoi.kt` | POI models |
| `domain/location/LocationInsightsEngine.kt` | Insights generation |
| `domain/location/SpendingHeatmapEngine.kt` | Heatmap data |

### UI Component Files (NEW)
| File | Purpose |
|------|---------|
| `ui/components/LocationSearchPicker.kt` | Shared location search (Features C, D, E) |
| `ui/components/LocationCorrectionSheet.kt` | Pin correction for map markers |
| `ui/components/LocationPermissionDialog.kt` | Permission request dialog |
| `ui/components/NearbyShopSuggestionCard.kt` | POI display in overpass results |

### New UI Composables Added to SpendingMapScreen

Two new composable functions were added to `SpendingMapScreen.kt`:

1. **`UnlocatedExpensesPanel`** — Collapsible panel showing unlocated expenses:
   - Shows count header with expand/collapse toggle
   - LazyColumn listing each expense (merchant, amount, address)
   - "Pin this" IconButton for each expense

2. **`PinExpenseSheet`** — Modal bottom sheet for pinning an expense:
   - Shows expense merchant and amount
   - Contains `LocationSearchPicker` for location selection
   - Save/Cancel buttons to assign location

### UI Screen Files
| File | Changes |
|------|---------|
| `ui/screens/transactions/TransactionsViewModel.kt` | Added location methods |
| `ui/screens/transactions/TransactionsScreen.kt` | Feature C full |
| `ui/screens/review/ReviewViewModel.kt` | Added location params |
| `ui/screens/review/ReviewScreen.kt` | Feature D full |
| `ui/screens/map/SpendingMapViewModel.kt` | Feature E state/methods |
| `ui/screens/map/SpendingMapScreen.kt` | Feature E UI |

---

## Migration Issues & Fixes

### Issue 1: Room Migration Schema Mismatch

**Problem:** First migration failed because Room expected specific column definitions:
- `notNull` columns without `DEFAULT` values
- Index on `lastResolvedAt`

**Error:**
```
Migration didn't properly handle: merchant_locations
Expected: index_merchant_locations_lastResolvedAt
Found: (missing index)
```

**Fix:** 
1. Removed all `DEFAULT` clauses from CREATE TABLE in migration
2. Added second migration (v30→v31) to create missing index

### Issue 2: Type Mismatches in Code

**Problem:** Various type mismatches between code and interfaces:
- `osmId` passed as `Long?` but method expected `String?`
- `merchantName` used but entity field is `merchant`

**Fix:** Added proper conversions and corrected field names.

### Issue 3: TransactionsScreen Syntax Error

**Problem:** The `SharedExpenseDialog` had malformed code — missing closing braces for Button:
```kotlin
confirmButton = {
    Button(
        onClick = { ... },
    // Missing: ), and Text("Save")
dismissButton = { ... }
```

**Fix:** Rewrote the button block with proper closing braces and modifier.

### Issue 4: LocationSearchPicker Parameter Names

**Problem:** SpendingMapScreen used wrong parameter names:
- `initialQuery`, `initialLat`, `initialLon`, `onLocationSelected` — wrong
- Should be: `currentLat`, `currentLon`, `currentAddress`, `onResult`

**Fix:** Corrected to match `LocationSearchPicker` signature.

---

## Testing Notes

- Build verification: `gradlew.bat assembleDebug` — SUCCESS
- Database migration tested with existing data
- All 5 features integrated and functional

---

## Files Fully Read During Implementation

The following files were read and analyzed to understand existing patterns and implement the features:

### Database & Entity Files
- `data/database/entity/Expense.kt` — Understanding Expense entity structure
- `data/database/entity/MerchantLocation.kt` — Understanding MerchantLocation entity
- `data/database/AppDatabase.kt` — Database version and migration patterns
- `di/DatabaseModule.kt` — Hilt module for database setup

### DAO & Repository Files
- `data/database/dao/ExpenseDao.kt` — DAO patterns for location queries
- `data/database/dao/MerchantLocationDao.kt` — Merchant location queries
- `data/repository/ExpenseRepository.kt` — Repository patterns, SQL queries
- `data/repository/MerchantLocationRepository.kt` — Area-based location caching
- `data/repository/ReviewQueueRepository.kt` — Review approval with location

### Location Service Files
- `data/location/LocationBackfillWorker.kt` — Background geocoding worker
- `data/location/NominatimGeocodingService.kt` — Nominatim API integration

### Domain Location Files
- `domain/location/LocationModels.kt` — GeocodingService interface
- `domain/location/LocationResolver.kt` — Resolution logic with affinity

### UI Files
- `ui/components/LocationCorrectionSheet.kt` — Existing location correction pattern
- `ui/screens/transactions/TransactionsViewModel.kt` — Transaction list patterns
- `ui/screens/transactions/TransactionsScreen.kt` — Full 1505-line transaction screen
- `ui/screens/review/ReviewViewModel.kt` — Review processing
- `ui/screens/review/ReviewScreen.kt` — Review cards and dialogs
- `ui/screens/map/SpendingMapViewModel.kt` — Map state management
- `ui/screens/map/SpendingMapScreen.kt` — Map UI implementation
- `ui/components/LocationSearchPicker.kt` — New shared component

---

## Commit

```
commit 6a81ba1
feat: Implement comprehensive location enrichment system (Features A-E)
```

**Branch:** `bug-fixes`

**Stats:** 130 files changed, +11,513 lines, -15,727 lines
