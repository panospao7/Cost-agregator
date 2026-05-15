# Slice 10 Debug Report — Spending Map + Location UI

Target repo/commit: `panospao7/Cost-agregator@18d442c5abb42a8997fd8b6bd04978776c5f6596`

Scope:
- `ui/screens/map/SpendingMapScreen.kt`
- `ui/screens/map/SpendingMapViewModel.kt`
- `ui/components/LocationPermissionDialog.kt`
- `ui/components/LocationSearchPicker.kt`
- `ui/components/LocationCorrectionSheet.kt`
- `ui/components/NearbyShopSuggestionCard.kt`
- `ui/components/PlaceInsightCard.kt`
- `domain/location/*`
- `data/location/*`
- `data/repository/MerchantLocationRepository.kt`
- location-related transaction update paths in `ExpenseRepository`

Sources inspected:
- Map screen folder: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/map
- `SpendingMapViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt
- `SpendingMapScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapScreen.kt
- `LocationSearchPicker.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/LocationSearchPicker.kt
- `LocationCorrectionSheet.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/LocationCorrectionSheet.kt
- `LocationPermissionDialog.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/LocationPermissionDialog.kt
- `NearbyShopSuggestionCard.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/NearbyShopSuggestionCard.kt
- `SpendingHeatmapEngine.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/location/SpendingHeatmapEngine.kt
- `LocationInsightsEngine.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/location/LocationInsightsEngine.kt
- `LocationResolver.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/location/LocationResolver.kt
- `LocationModels.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/location/LocationModels.kt
- `LocatedMoneyExpense.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/location/LocatedMoneyExpense.kt
- `OverpassNearbyService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/data/location/OverpassNearbyService.kt
- `NominatimGeocodingService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/data/location/NominatimGeocodingService.kt
- `MerchantLocationRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/data/repository/MerchantLocationRepository.kt
- Current map tests: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/test/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModelStressTest.kt
- Current location domain tests: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/test/java/com/yourname/expensetracker/domain/location
- UI map: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/COMPREHENSIVE_UI_MAP.md
- Segment map: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/architecture/CODEBASE_SEGMENTS.md

Note: This is static debugging from GitHub source. The resolving agent must run Gradle locally.

---

## 1. Executive summary

Slice 10 owns geospatial spending visualization and merchant-location enrichment. It is privacy-sensitive because it handles:
- device GPS permission and GPS access,
- external geocoding,
- Overpass/Google/Nominatim provider usage,
- merchant names and addresses,
- precise coordinates,
- user corrections,
- spending heatmap/insight totals.

The current implementation has several good fixes already:
- GPS is not fetched automatically after permission grant.
- `onCenterOnMeRequested()` checks the app privacy gate before accessing GPS.
- `LocationResolver` checks privacy gates before external geocoding and Overpass.
- `OverpassNearbyService` has a defense-in-depth privacy gate.
- heatmap and insights now have normalized money variants.
- non-spending transaction types are excluded from heatmap/insights.
- OSMDroid map lifecycle is partially handled with `onDetach()`.
- location resolver logs use anonymized merchant hashes in many domain/data paths.

But Slice 10 still has important issues:

1. `SpendingMapViewModel` exposes `GeocodingService` directly to Compose UI.
2. `LocationSearchPicker` performs network/domain calls and reverse geocoding directly in a composable.
3. Home currency is still initialized as `"EUR"` and map recomputation is not reactive to currency changes.
4. The My Location button can disappear exactly when the user needs it: permission granted but no cached location yet.
5. Filter recomputation can race with the continuous located-expense flow.
6. Save/correction operations are not idempotency-safe and do not keep sheets open on failure.
7. Location correction writes correction/cache and expense location as separate operations with partial-failure risk.
8. Search/reverse-geocode jobs can leave stale loading state or apply stale results.
9. `LocationSearchPicker` logs raw search queries and raw addresses.
10. Privacy-denied external geocoding/GPS states are snackbar/string-based, not typed blocked UI.
11. OSMDroid lifecycle handling is incomplete for Activity pause/resume.
12. Default map center is hardcoded Athens/Greece instead of home region/device/first marker.
13. Conversion-warning counts are inconsistent: marker conversion failures and heatmap conversion failures are mixed conceptually.
14. Current tests are mostly stress/partial filter tests; many critical privacy/race/mutation/currency paths are untested.

Recommended strategy:
- Do not rewrite map rendering first.
- First extract location search/pin/correction orchestration out of Compose.
- Fix currency reactivity and permission/GPS UX.
- Add mutation/race/privacy tests.
- Then split map UI into pure route/content/components.

---

## 2. Baseline commands

Run first:

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
```

Then targeted tests:

```bash
./gradlew :app:testDebugUnitTest --tests "*SpendingMap*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*LocationResolver*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*SpendingHeatmap*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*LocationInsights*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Overpass*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Geocoding*" --stacktrace
```

Inventory tests:

```bash
find app/src/test app/src/androidTest \
  -iname "*Map*" -o \
  -iname "*Location*" -o \
  -iname "*Geocoding*" -o \
  -iname "*Overpass*" -o \
  -iname "*Heatmap*"
```

If Compose/instrumented tests exist:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

OSMDroid behavior likely needs at least a small emulator smoke suite.

---

## 3. Current architecture map

### Map data pipeline

```text
ExpenseRepository.getLocatedExpenses()
        ↓
SpendingMapViewModel.recomputeMapData(...)
        ↓
date/category filters
        ↓
marker mapping
        ↓
currency conversion per marker
        ↓
LocatedMoneyExpense mapping for spending-only expenses
        ↓
SpendingHeatmapEngine.computeNormalized(...)
LocationInsightsEngine.computeNormalized(...)
        ↓
SpendingMapState
        ↓
SpendingMapScreen
        ↓
OSMDroid MapView + marker detail + stats + unlocated panel + insights list
```

### Device location pipeline

```text
SpendingMapScreen permission check
        ↓
onPermissionResult(granted)
        ↓
state.locationPermissionGranted
        ↓
user taps center-on-me
        ↓
onCenterOnMeRequested()
        ↓
PrivacyGate.DEVICE_GPS_LOCATION
        ↓
ForegroundLocationProvider.getLastKnownLocation()
        ↓
state.deviceLatitude/deviceLongitude
```

### Resolve/correction pipeline

```text
marker selected
        ↓
onResolveLocationForMarker(marker)
        ↓
LocationResolver.resolve(...)
        ↓
Resolved / NeedsUserSelection / Retryable / Unresolved
        ↓
ExpenseRepository.updateExpenseLocation(...)
MerchantLocationRepository.saveLocation/saveCorrection(...)
```

### Interactive search/pin pipeline

```text
LocationCorrectionSheet / PinExpenseSheet
        ↓
LocationSearchPicker composable
        ↓
GeocodingService.searchMultiple(...)
GeocodingService.reverseGeocode(...)
        ↓
onResult(lat, lon, address, osmId)
        ↓
ViewModel save method
```

---

# 4. Issues

## S10-001 — `SpendingMapViewModel` exposes `GeocodingService` directly to UI

Severity: High  
Files:
- `SpendingMapViewModel.kt`
- `SpendingMapScreen.kt`
- `LocationCorrectionSheet.kt`
- `LocationSearchPicker.kt`

Evidence:
`SpendingMapViewModel` has a public `val geocodingService`.
`SpendingMapScreen` passes it into:
- `LocationCorrectionSheet`
- `PinExpenseSheet`
- `LocationSearchPicker`

Problem:
This repeats the same architecture smell identified in Transactions and Review:
- composables can perform domain/network work directly,
- privacy policy enforcement is split,
- tests/previews need domain services,
- provider failures are handled with local raw strings,
- race/idempotency logic is hidden in composable state.

Fix strategy:
Move search, reverse-geocode, and pin selection into a ViewModel/coordinator.

Implementation plan:
1. Add a coordinator:

```kotlin
class LocationSearchCoordinator @Inject constructor(
    private val geocodingService: GeocodingService,
    private val privacyGate: PrivacyGate
) {
    suspend fun search(command: LocationSearchCommand): LocationSearchResult
    suspend fun reverseGeocode(command: ReverseGeocodeCommand): ReverseGeocodeResult
}
```

2. ViewModel owns:
```kotlin
data class LocationPickerState(
    val query: String = "",
    val useGoogle: Boolean = false,
    val results: List<GeocodingResultUi> = emptyList(),
    val isSearching: Boolean = false,
    val error: UiText? = null,
    val selectedPin: GeocodingResultUi? = null,
    val privacyBlocked: PrivacyBlocked? = null
)
```

3. UI becomes pure:
```kotlin
LocationSearchPicker(
    state = state.locationPicker,
    onQueryChanged = viewModel::onLocationQueryChanged,
    onResultSelected = viewModel::onLocationResultSelected,
    onMapLongPressed = viewModel::onLocationMapLongPressed,
    onClear = viewModel::onLocationCleared
)
```

Acceptance:
- `SpendingMapViewModel` no longer exposes `GeocodingService`.
- `LocationSearchPicker` does not call domain/network services.
- search/reverse-geocode can be JVM-tested.
- privacy blocked state is typed and visible.

---

## S10-002 — Map currency is not reactive; `"EUR"` can leak into markers/heatmap/insights

Severity: Critical multi-currency correctness  
Files:
- `SpendingMapViewModel.kt`
- `MapExpenseMarker`
- `SpendingMapState`

Evidence:
- `MapExpenseMarker.displayCurrency` and `originalCurrency` default to `"EUR"`.
- `SpendingMapState.homeCurrency` defaults to `"EUR"`.
- located expenses are collected in one coroutine.
- home currency is collected in a separate coroutine.
- `recomputeMapData()` uses `_state.value.homeCurrency`.
- changing home currency only updates `homeCurrency` in state; it does not recompute markers/heatmap/insights.

Problem:
The first map computation can use placeholder EUR before `homeCurrency()` emits. Later, if home currency changes, marker amounts and heatmap/insights remain stale until some unrelated expense/filter event triggers recomputation.

Fix strategy:
Combine located expenses, filters, and home currency into one reactive state pipeline.

Implementation plan:
1. Replace placeholder with nullable/typed state:

```kotlin
sealed interface MapCurrencyState {
    data object Loading : MapCurrencyState
    data class Ready(val code: String) : MapCurrencyState
    data class Error(val message: UiText) : MapCurrencyState
}
```

2. Add filter state:

```kotlin
data class MapFilterState(
    val selectedCategories: Set<String> = emptySet(),
    val dateRangeStartMs: Long? = null,
    val dateRangeEndMs: Long? = null,
    val selectedDateRangePreset: DateRangePreset? = null,
    val highlightedMerchantQuery: String? = null
)
```

3. Use `combine`:

```kotlin
combine(
    expenseRepository.getLocatedExpenses(),
    currencySettingsRepository.homeCurrency(),
    filterState
) { expenses, currency, filters ->
    MapInputs(expenses, currency, filters)
}
.flatMapLatest { inputs ->
    flow { emit(mapDataAssembler.build(inputs)) }
}
```

4. If `homeCurrency()` fails, show currency unavailable state. Do not compute in EUR unless EUR is truly the user’s home currency.

Acceptance:
- no production map computation uses placeholder EUR.
- home currency change recomputes all marker amounts, heatmap, and insights.
- test: delayed USD home currency does not display/compute EUR.
- test: changing USD → GBP recomputes markers and heatmap.

---

## S10-003 — My Location button is hidden when permission is granted but no GPS has been fetched

Severity: High UX / functionality  
Files:
- `SpendingMapScreen.kt`
- `SpendingMapViewModel.kt`

Evidence:
The center-on-me FAB is rendered only when:
```text
locationPermissionGranted == true
AND deviceLatitude != null
AND deviceLongitude != null
```

But `onPermissionResult(granted)` intentionally does not fetch GPS automatically. The only path that fetches GPS is tapping the center-on-me FAB.

Problem:
After permission is granted, `deviceLatitude/deviceLongitude` are null, so the fetch button is not shown. The user has no obvious way to trigger the explicit GPS fetch.

Fix strategy:
Show the My Location button whenever permission is granted. Its first tap fetches GPS; if a location is returned, it then centers.

Implementation plan:
```kotlin
if (state.locationPermissionGranted) {
    FloatingActionButton(
        onClick = { viewModel.onCenterOnMeRequested() }
    ) {
        Icon(Icons.Default.MyLocation, ...)
    }
} else {
    FloatingActionButton(
        onClick = { viewModel.onShowPermissionRationale(true) }
    ) { ... }
}
```

Then convert center action into an event:
```kotlin
sealed interface MapUiEvent {
    data class CenterOnDevice(val lat: Double, val lon: Double) : MapUiEvent
}
```

Acceptance:
- after OS permission grant, user sees My Location button.
- tapping it checks privacy and fetches location.
- successful fetch emits center event.
- denied privacy shows blocked UI/snackbar and does not call GPS provider.
- test covers permission granted + null location.

---

## S10-004 — GPS privacy-denied state is only a snackbar

Severity: High privacy UX  
Files:
- `SpendingMapViewModel.kt`
- `SpendingMapScreen.kt`
- `PrivacyBlockedCard.kt`

Evidence:
`fetchDeviceLocation()` checks `PrivacyCapability.DEVICE_GPS_LOCATION`. If blocked, it sets a raw snackbar message.

Problem:
A GPS privacy denial is not an error. It is a deliberate policy state. It should be visible and actionable, not just a transient snackbar.

Fix strategy:
Expose typed privacy block state.

Implementation plan:
```kotlin
data class SpendingMapState(
    ...
    val gpsBlocked: PrivacyBlocked? = null,
    val geocodingBlocked: PrivacyBlocked? = null,
    val overpassBlocked: PrivacyBlocked? = null
)
```

Render:
```kotlin
state.gpsBlocked?.let {
    PrivacyBlockedCard(
        blocked = it,
        onOpenPrivacySettings = onNavigateToPrivacySettings
    )
}
```

Acceptance:
- GPS blocked state is persistent until dismissed or setting changes.
- no GPS provider call happens when privacy gate blocks.
- UI offers privacy settings navigation if available.

---

## S10-005 — Filter recomputation can race and overwrite newer state

Severity: High  
Files:
- `SpendingMapViewModel.kt`

Evidence:
- The ViewModel continuously collects `getLocatedExpenses()`.
- `toggleCategoryFilter`, `clearFilters`, and `setDateRange` manually call `recomputeMapData(expenseRepository.getLocatedExpenses().first())`.
- Multiple recompute jobs can overlap.
- `recomputeMapData()` reads `_state.value` internally.

Problem:
A slow recompute with old filters can finish after a fast recompute with new filters and overwrite state. This is especially possible because recompute includes currency conversion and category lookup.

Fix strategy:
Use one reactive pipeline with `combine + mapLatest`, or add request IDs.

Recommended:
- `combine(locatedExpenses, homeCurrency, filterState)`
- `mapLatest { assembler.build(...) }`

Acceptance:
- rapid filter changes always show last filter only.
- no explicit `getLocatedExpenses().first()` inside filter setters.
- test: filter A slow, filter B fast → final state B.

---

## S10-006 — Conversion warning count does not match marker warning semantics

Severity: High financial correctness / UX  
Files:
- `SpendingMapViewModel.kt`
- `SpendingMapScreen.kt`

Evidence:
`mapConversionWarnings` is calculated from `moneyExpenses` for spending-only rows used by heatmap/insights.
Marker conversion can fail for any located expense type, and each marker can have `conversionWarning`.

The UI banner says:
```text
N expense(s) shown in original currency...
```

Problem:
The banner count may exclude marker conversion failures for deposits/transfers/withdrawals/unknown, while the text says expenses shown in original currency. Conversely, heatmap/insight conversion failures are skipped, not “shown in original currency.”

Fix strategy:
Separate warning counters.

Implementation:
```kotlin
data class MapCurrencyQuality(
    val markerOriginalCurrencyCount: Int = 0,
    val heatmapExcludedConversionCount: Int = 0,
    val insightExcludedConversionCount: Int = 0,
    val warnings: List<UiText> = emptyList()
)
```

UI:
- marker warning: “3 markers shown in original currency.”
- heatmap warning: “2 spending points excluded from heatmap because conversion is unavailable.”

Acceptance:
- warning counts match actual behavior.
- tests cover failed conversion for purchase and deposit separately.
- no misleading “shown” text for skipped heatmap rows.

---

## S10-007 — Location search/reverse-geocode job cancellation can leave stale loading states

Severity: High  
File:
- `LocationSearchPicker.kt`

Evidence:
`launchSearch()` cancels the previous job. If a job catches `CancellationException`, it rethrows before reaching `isSearching = false`.
Reverse-geocode launched from long-press has no try/catch/finally and no request ID.

Problem:
- search spinner can remain stuck after cancellation;
- reverse-geocode failure can leave `isPinResolving = true`;
- older reverse-geocode result can overwrite a newer pin.

Fix strategy:
Move job logic out of Compose into coordinator/ViewModel. If kept short-term, use `try/finally` and request IDs.

Implementation:
```kotlin
private var searchRequestId = 0L
private var pinRequestId = 0L
```

Search:
```kotlin
val id = ++searchRequestId
searchJob?.cancel()
searchJob = scope.launch {
    try { ... }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { if (id == searchRequestId) error = ... }
    finally { if (id == searchRequestId) isSearching = false }
}
```

Reverse:
```kotlin
val id = ++pinRequestId
isPinResolving = true
try { ... }
finally { if (id == pinRequestId) isPinResolving = false }
```

Acceptance:
- cancelling search clears loading for the current request.
- failed reverse-geocode clears loading.
- stale reverse-geocode cannot overwrite latest pin.
- tests use virtual time.

---

## S10-008 — `LocationSearchPicker` logs raw search queries and addresses

Severity: High privacy/security  
Files:
- `LocationSearchPicker.kt`

Evidence:
The composable logs:
- raw query text,
- first result address,
- failure details.

Domain/data layers anonymize merchant names in several places, but this UI component logs raw user-entered place names and addresses.

Problem:
Search queries and addresses are sensitive location data.

Fix strategy:
Remove raw logs or hash/redact them. Gate detailed logs behind debug diagnostics.

Implementation:
```kotlin
Timber.d("Location search started queryHash=%s google=%s", query.anonymizeForLog(), withGoogle)
```

For result:
```kotlin
Timber.d("Location search returned count=%d", resolvedResults.size)
```

Acceptance:
- no raw merchant/address/query text in logs.
- release builds do not log precise coordinates or addresses.
- static grep test covers `LocationSearch`.

---

## S10-009 — Interactive Google Places toggle lacks privacy/cost policy integration

Severity: Medium/High  
Files:
- `LocationSearchPicker.kt`
- `CompositeGeocodingService.kt` if present
- privacy settings

Evidence:
The UI exposes a Google Places toggle. It says it conserves quota, but there is no visible typed permission/cost gate in the UI layer.

Problem:
Google Places may be paid/cloud/external. Users need a clear privacy/cost state and policy. If external geocoding is disabled, the toggle should not appear as usable.

Fix strategy:
Expose provider availability from ViewModel/coordinator.

Implementation:
```kotlin
data class LocationProviderAvailability(
    val externalGeocodingAllowed: Boolean,
    val googlePlacesAllowed: Boolean,
    val blockedReason: PrivacyBlocked?
)
```

UI:
- hide/disable Google toggle when blocked;
- show info text when unavailable;
- do not call provider when disabled.

Acceptance:
- privacy-denied external geocoding disables search.
- Google provider is opt-in and explicitly labeled.
- tests verify no `searchMultiple(... useGoogle=true)` when blocked.

---

## S10-010 — Location correction save is not atomic and has partial-failure risk

Severity: Critical data integrity  
Files:
- `SpendingMapViewModel.kt`
- `MerchantLocationRepository.kt`
- `ExpenseRepository.kt`

Evidence:
`onSaveCorrection()`:
1. saves merchant correction;
2. if correction ID > 0, updates expense location;
3. updates UI success.

`onPoiSelected()`:
1. updates expense location;
2. saves merchant cache location;
3. shows success.

These are separate repository operations and can fail halfway.

Problem:
Partial failures can cause inconsistent location state:
- correction saved/cache updated but expense not updated;
- expense updated but merchant cache not saved;
- conflict leaves sheet open or silent-ish behavior without typed state.

Fix strategy:
Create a domain coordinator for location mutations.

Implementation:
```kotlin
class LocationCorrectionCoordinator @Inject constructor(
    private val database: AppDatabase,
    private val expenseRepository: ExpenseRepository,
    private val merchantLocationRepository: MerchantLocationRepository,
    private val writeBarrier: DatabaseWriteBarrier
) {
    suspend fun saveCorrection(command: SaveLocationCorrectionCommand): LocationMutationResult
    suspend fun applyPoiSelection(command: ApplyPoiSelectionCommand): LocationMutationResult
    suspend fun assignManualLocation(command: AssignManualLocationCommand): LocationMutationResult
}
```

Prefer one DB transaction if both repositories use the same database. If not possible, return partial states:
```kotlin
sealed interface LocationMutationResult {
    data object Success : LocationMutationResult
    data class PartialFailure(val savedExpense: Boolean, val savedCorrection: Boolean, val message: UiText) : LocationMutationResult
    data class Conflict(val message: UiText) : LocationMutationResult
    data class Error(val message: UiText) : LocationMutationResult
}
```

Acceptance:
- correction failure does not pretend success.
- expense update failure after correction is visible/recoverable.
- POI cache failure policy is explicit.
- tests cover all partial failures.

---

## S10-011 — Location mutation actions are not idempotency-safe

Severity: High  
Files:
- `SpendingMapViewModel.kt`
- `LocationCorrectionSheet.kt`
- `PinExpenseSheet`

Problem:
Save buttons can be tapped multiple times. ViewModel methods do not guard in-flight mutations.

Fix strategy:
Add typed mutation state.

Implementation:
```kotlin
enum class LocationMutationKind {
    SAVE_CORRECTION,
    APPLY_POI,
    ASSIGN_PIN,
    RESOLVE_MARKER
}

data class LocationMutationUiState(
    val inFlight: LocationMutationKind? = null,
    val targetExpenseId: Long? = null,
    val error: UiText? = null
)
```

Guard:
```kotlin
if (_state.value.locationMutation.inFlight != null) return
```

Acceptance:
- double save calls repository/coordinator once.
- sheet save button disabled while saving.
- failure keeps sheet open with error.
- success closes sheet.

---

## S10-012 — User cannot clearly clear/remove a map marker location

Severity: Medium/High privacy UX  
Files:
- `LocationSearchPicker.kt`
- `LocationCorrectionSheet.kt`
- `PinExpenseSheet`
- `SpendingMapViewModel.kt`

Evidence:
`LocationSearchPicker` supports `onResult(null, null, null, null)` for “clear location.”
But `LocationCorrectionSheet` requires non-null pending lat/lon to enable save, so clear cannot be confirmed there.
`PinExpenseSheet` is for unlocated expenses; clear does not apply.

Problem:
If a merchant location is wrong or privacy-sensitive, the map correction flow should allow removing the location.

Fix strategy:
Use explicit location edit commands.

Implementation:
```kotlin
sealed interface LocationEditCommand {
    data object Unchanged : LocationEditCommand
    data class Set(val lat: Double, val lon: Double, val address: String?, val osmId: String?) : LocationEditCommand
    data object Clear : LocationEditCommand
}
```

UI:
- show “Remove location” when marker has a location.
- save command can be `Clear`.

Repository:
- user-initiated clear should route through transaction lifecycle if available, not raw worker path.

Acceptance:
- user can clear a marker location from map.
- clear is distinct from unchanged.
- test verifies clear updates expense and removes selected marker after flow emits.

---

## S10-013 — `clearExpenseLocation` bypass path may be reused by user actions

Severity: Medium/High  
Files:
- `ExpenseRepository.kt`
- potential location UI call sites

Evidence:
`clearExpenseLocation()` is documented as an intentional bypass for maintenance/backfill-style updates. It calls DAO directly.

Problem:
If a user-facing location clear action is added or already uses this method elsewhere, it bypasses transaction lifecycle events.

Fix strategy:
Add lifecycle-routed clear location method.

Implementation:
```kotlin
suspend fun clearExpenseLocationUserAction(expenseId: Long) {
    transactionLifecycleCoordinator.clearLocation(expenseId, source = "USER_EDIT")
}
```

Acceptance:
- user-initiated clear writes transaction lifecycle event.
- background/backfill clear remains intentionally direct.
- static call-site inventory distinguishes them.

---

## S10-014 — Permission dialog does not handle permanent denial / app settings

Severity: Medium  
Files:
- `LocationPermissionDialog.kt`
- `SpendingMapScreen.kt`

Problem:
The dialog only asks for permission. It does not handle:
- denied once,
- “Don’t ask again,”
- OS-level location disabled,
- app privacy GPS disabled.

Fix strategy:
Add a typed permission state.

Implementation:
```kotlin
sealed interface LocationPermissionUiState {
    data object Unknown : LocationPermissionUiState
    data object Granted : LocationPermissionUiState
    data object ShouldAsk : LocationPermissionUiState
    data object DeniedCanAskAgain : LocationPermissionUiState
    data object DeniedPermanently : LocationPermissionUiState
    data class BlockedByPrivacy(val blocked: PrivacyBlocked) : LocationPermissionUiState
}
```

UI:
- “Allow location” for askable state.
- “Open app settings” for permanently denied.
- `PrivacyBlockedCard` for privacy-blocked state.

Acceptance:
- permanent denial opens settings path.
- app privacy-denied state is separate from OS denial.
- tests cover permission state mapping.

---

## S10-015 — OSMDroid `MapView` lifecycle is only partially handled

Severity: Medium/High  
Files:
- `SpendingMapScreen.kt`
- `LocationSearchPicker.kt`

Evidence:
`MapView.onResume()` is called in `AndroidView.factory`.
`onPause()` and `onDetach()` are called only in `DisposableEffect.onDispose`.

Problem:
If the Activity goes to background while the composable remains in composition, `onPause()` is not called. Tile/network threads may continue.

Fix strategy:
Add a `LifecycleEventObserver`.

Implementation:
```kotlin
val lifecycleOwner = LocalLifecycleOwner.current
DisposableEffect(lifecycleOwner, mapViewRef.value) {
    val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> mapViewRef.value?.onResume()
            Lifecycle.Event.ON_PAUSE -> mapViewRef.value?.onPause()
            Lifecycle.Event.ON_DESTROY -> mapViewRef.value?.onDetach()
            else -> Unit
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
        lifecycleOwner.lifecycle.removeObserver(observer)
        mapViewRef.value?.onPause()
        mapViewRef.value?.onDetach()
    }
}
```

Acceptance:
- map pauses on Activity pause.
- map resumes on Activity resume.
- emulator smoke test verifies no crash on background/foreground.

---

## S10-016 — Default map center is hardcoded Athens/Greece

Severity: Medium  
Files:
- `SpendingMapScreen.kt`
- `LocationSearchPicker.kt`
- `NominatimGeocodingService.kt`

Evidence:
Map defaults use Athens coordinates.
Nominatim defaults to Greece country/viewbox for backward compatibility.

Problem:
The user may be outside Greece. A US user would see an irrelevant map center until markers/device location load.

Fix strategy:
Use configurable home region or data-driven center.

Implementation options:
1. If markers exist, center on marker bounds.
2. Else if device location exists, center on device.
3. Else use user-configured home region.
4. Else use neutral world/default.

Add:
```kotlin
data class MapRegionConfig(
    val defaultLat: Double,
    val defaultLon: Double,
    val countryCode: String?,
    val viewbox: String?
)
```

Acceptance:
- no hardcoded Athens in UI components except fallback config.
- default center can be configured.
- first-marker bounds center works.

---

## S10-017 — OSMDroid map auto-centers when device location changes

Severity: Medium UX  
Files:
- `SpendingMapScreen.kt`

Evidence:
`OsmMapView` tracks `lastCentredLoc` and animates when device coordinates first arrive or change.

Problem:
If future code starts collecting GPS updates, the map could jump while user is panning. Current code fetches only explicitly, but behavior should be defined.

Fix strategy:
Center only on explicit event.

Implementation:
- remove auto-center on any new device location;
- handle only `MapUiEvent.CenterOnDevice`.

Acceptance:
- user pan is not overridden by state changes.
- center action still works.
- test with fake map controller if wrapped.

---

## S10-018 — Map AndroidView overlays are not accessible

Severity: Medium/High accessibility  
Files:
- `SpendingMapScreen.kt`
- `LocationSearchPicker.kt`

Problem:
Markers and heatmap overlays inside `AndroidView` have poor Compose semantics. Screen readers cannot meaningfully navigate spending markers.

Fix strategy:
Provide accessible parallel UI summaries and semantics.

Implementation:
- Add `Modifier.semantics` to map container with summary:
  - marker count,
  - heatmap count,
  - selected marker info.
- Provide a “List markers” bottom panel or accessible list.
- Add test tags:
  - `spending_map`
  - `map_marker_detail`
  - `map_my_location`
  - `map_enable_location`
  - `map_conversion_warning`
  - `map_unlocated_panel`
  - `map_place_insight_list`.

Acceptance:
- TalkBack has meaningful map summary.
- marker details are accessible after selection.
- Compose tests can find core controls by tags.

---

## S10-019 — Search and distance formatting are locale/hardcoded-string fragile

Severity: Medium  
Files:
- `LocationSearchPicker.kt`
- `NearbyShopSuggestionCard.kt`
- `SpendingMapScreen.kt`

Evidence:
- distance labels use `%.0f m`, `%.1f km`, and raw format.
- search errors are raw English strings.
- conversion warning banner has raw English text.
- coordinate fallbacks use `%.5f, %.5f` without explicit `Locale.US`.

Problem:
User-visible strings are not fully localized and tests become locale-dependent.

Fix strategy:
- Move static text to resources.
- Use `DistanceFormatter`.
- Use `Locale.US` for coordinate technical strings or localized formatter if user-facing.

Implementation:
```kotlin
object DistanceFormatter {
    fun format(metres: Double, locale: Locale): String
}
```

Acceptance:
- no raw user-facing English in Slice 10 UI.
- distance formatting tests pass in US and comma-decimal locales.

---

## S10-020 — `LocationSearchPicker` combines too many responsibilities

Severity: High testability  
File:
- `LocationSearchPicker.kt`

Current responsibilities:
- search field
- provider toggle
- debounce
- network calls
- result list
- embedded OSMDroid map
- long-press reverse geocode
- pin confirmation
- manual coordinate entry
- clear location
- error display

Problem:
This component is too large and difficult to test.

Fix strategy:
Split into pure UI subcomponents after moving domain calls to ViewModel.

Implementation:
```text
LocationSearchPicker.kt              // container only
LocationSearchField.kt
LocationProviderToggle.kt
LocationSearchResultsList.kt
LocationResultsMap.kt
PinnedLocationCard.kt
ManualCoordinateEntry.kt
CurrentLocationChip.kt
```

Acceptance:
- each subcomponent has focused Compose tests.
- no component performs network calls.
- map subcomponent can be instrumented separately.

---

## S10-021 — Map screen is monolithic

Severity: High  
Files:
- `SpendingMapScreen.kt`

Problem:
The screen owns:
- permission launcher,
- snackbar,
- permission dialog,
- correction sheet,
- pin sheet,
- filters,
- conversion warning,
- OSMDroid map,
- marker detail,
- Overpass candidates,
- stats bar,
- unlocated panel,
- insights list.

Fix strategy:
Split route/content/components.

Implementation:
```text
SpendingMapRoute.kt
SpendingMapScreenContent.kt
SpendingMapFilters.kt
SpendingMapView.kt
MapMarkerDetailCard.kt
MapConversionWarningBanner.kt
MapOverpassCandidates.kt
MapStatsBar.kt
UnlocatedExpensesPanel.kt
PinExpenseSheet.kt
```

Acceptance:
- route owns Hilt, permission launcher, snackbar/event collection.
- content is pure state + callbacks.
- OSMDroid wrapper isolated.

---

## S10-022 — Location mutation success/failure is snackbar-only and may close UI too early

Severity: High UX/debuggability  
Files:
- `SpendingMapViewModel.kt`
- `LocationCorrectionSheet.kt`
- `SpendingMapScreen.kt`

Problem:
Correction/pin sheets do not receive saving/error state. Failures are only snackbar/string messages. The user can lose context or cannot retry clearly.

Fix strategy:
Keep sheet open on failure and show inline error.

Implementation:
```kotlin
data class CorrectionSheetState(
    val visible: Boolean,
    val merchantName: String,
    val selectedLocation: LocationSelection?,
    val isSaving: Boolean,
    val error: UiText?
)
```

Acceptance:
- correction conflict keeps sheet open with inline message.
- save failure keeps selected location.
- success closes sheet.

---

## S10-023 — External geocoding privacy state is not surfaced in `LocationSearchPicker`

Severity: High privacy UX  
Files:
- `LocationSearchPicker.kt`
- `LocationResolver.kt`
- `NominatimGeocodingService.kt`

Evidence:
Data/domain services return `GeocodingError.Disabled` when privacy blocks geocoding. UI maps failures to generic “Search unavailable (...)”.

Problem:
The user does not know search is intentionally disabled by privacy settings.

Fix strategy:
Map `GeocodingError.Disabled` to typed privacy blocked UI.

Implementation:
```kotlin
when (error) {
    GeocodingError.Disabled -> LocationSearchError.BlockedByPrivacy(...)
    GeocodingError.RateLimited -> LocationSearchError.RateLimited
    ...
}
```

Acceptance:
- disabled geocoding shows privacy-blocked card/inline message.
- no retry button for privacy-disabled state.
- settings action available.

---

## S10-024 — Precise coordinates and addresses are displayed broadly

Severity: Medium/High privacy  
Files:
- `SpendingMapScreen.kt`
- `LocationSearchPicker.kt`

Evidence:
UI fallback displays coordinates to 5 decimals and full addresses in search/current location areas.

Problem:
Precise coordinates and full addresses are sensitive. Some display is necessary for location correction, but it should be intentional.

Fix strategy:
- Normal mode: show place/address when available; avoid raw coordinates unless advanced/manual/debug.
- Advanced mode: coordinates allowed.
- Raw full address display should respect privacy mode if applicable.

Acceptance:
- main marker detail does not show exact lat/lon by default.
- exact coordinates visible only in advanced/manual/debug context.
- tests verify normal UI hides exact coordinates.

---

## S10-025 — Overpass candidate selection lacks per-candidate mutation state

Severity: Medium  
Files:
- `SpendingMapViewModel.kt`
- `NearbyShopSuggestionCard.kt`

Problem:
Selecting a POI triggers update and cache save without disabling the candidate list. Multiple taps can write multiple locations.

Fix strategy:
Use mutation state from S10-011.

Acceptance:
- selected candidate row shows loading/disabled.
- double tap calls mutation once.
- failure leaves candidate list visible.

---

## S10-026 — Manual coordinate input does not support negative sign well on all keyboards/locales

Severity: Medium  
File:
- `LocationSearchPicker.kt`

Evidence:
`KeyboardType.Decimal` may not expose minus sign on some keyboards. Parsing uses `toDoubleOrNull()` on raw input.

Problem:
Users need negative longitude/latitude in many countries.

Fix strategy:
Use a coordinate sanitizer/parser.

Implementation:
```kotlin
object CoordinateInputParser {
    fun parseLatitude(raw: String): CoordinateParseResult
    fun parseLongitude(raw: String): CoordinateParseResult
}
```

Support:
- negative sign,
- decimal dot/comma policy,
- trimming,
- range validation,
- NaN/Infinity rejection.

Acceptance:
- unit tests cover negative coordinates, comma decimal, NaN, Infinity, blank.
- UI shows field-specific error.

---

## S10-027 — Category filter labels are generated ad hoc and may be stale

Severity: Low/Medium  
Files:
- `SpendingMapViewModel.kt`

Evidence:
`availableCategories` is built by calling `categoryRepository.getAll()` inside `recomputeMapData()`.

Problem:
Category names/colors are not part of the reactive combine. Category rename may not update map filter labels unless map data recomputes.

Fix strategy:
Add categories flow to map inputs.

Implementation:
```kotlin
combine(locatedExpensesFlow, homeCurrencyFlow, categoryRepository.allCategories, filterState) { ... }
```

Acceptance:
- category rename updates filter label.
- category deletion/uncategorized policy tested.
- no blocking `getAll()` inside recompute.

---

## S10-028 — Location stats can become stale or load independently

Severity: Medium  
Files:
- `SpendingMapViewModel.kt`

Evidence:
`refreshStats()` is called in a separate coroutine and after mutations. The located/unlocated lists also have flows.

Problem:
Stats can temporarily disagree with marker/unlocated panel state. Failures are logged only.

Fix strategy:
Prefer reactive count flows or derive counts from same source if accurate.

Implementation:
- Add DAO count flows:
  - `countLocatedFlow()`
  - `countUnlocatedFlow()`
- Combine into state.
- If count load fails, show degraded state.

Acceptance:
- stats update when DB changes without manual refresh.
- stats failure is visible or tracked.

---

## S10-029 — Map data assembler belongs outside ViewModel

Severity: Medium/High  
Files:
- `SpendingMapViewModel.kt`

Problem:
`recomputeMapData()` handles filtering, conversion, marker models, heatmap inputs, insights inputs, category labels, warning counts, and state mapping.

Fix strategy:
Extract pure-ish assembler.

Implementation:
```kotlin
class SpendingMapDataAssembler @Inject constructor(
    private val currencyConverter: CurrencyConverter,
    private val heatmapEngine: SpendingHeatmapEngine,
    private val insightsEngine: LocationInsightsEngine
) {
    suspend fun build(inputs: SpendingMapInputs): SpendingMapData
}
```

Acceptance:
- assembler has JVM tests for currency/filter/heatmap/insight rules.
- ViewModel only combines flows and exposes state.

---

## S10-030 — Current test coverage is stress-heavy and misses critical paths

Severity: High  
Files:
- `SpendingMapViewModelStressTest.kt`
- `LocationResolverTest.kt`
- domain location stress tests

Evidence:
Existing map tests include:
- heatmap excludes non-spending rows,
- markers include all transaction types,
- retryable manual resolve snackbar,
- several stress tests marked ignored.
LocationResolverTest covers merchant key selection only.

Missing:
- currency reactivity,
- GPS permission/privacy,
- geocoding privacy-disabled UI,
- search cancellation/stale results,
- correction partial failures,
- save idempotency,
- OSMDroid lifecycle,
- location clear,
- category filter race,
- marker/heatmap conversion warnings.

Fix strategy:
Add focused tests listed below before refactors.

---

# 5. Recommended new tests

## JVM/ViewModel/domain tests

### `SpendingMapCurrencyTest`
Required cases:
- delayed home currency does not compute markers as EUR.
- home currency change recomputes marker amounts.
- purchase conversion failure increments heatmap excluded count.
- deposit conversion failure increments marker warning count but not heatmap warning.
- place insights use normalized home-currency amounts.
- marker displays original currency only when conversion failed.

### `SpendingMapFilterRaceTest`
Required cases:
- rapid category filter A then B final state uses B.
- rapid date range changes final state uses last range.
- clear filters cancels old recompute.
- category rename updates filter labels.

### `SpendingMapPermissionPrivacyTest`
Required cases:
- permission granted with null GPS still shows center button.
- center-on-me checks privacy before location provider.
- privacy denied does not call location provider.
- provider throws `SecurityException` produces typed error.
- OS permission denied shows permission state.
- permanently denied state maps to app settings action.

### `SpendingMapMutationTest`
Required cases:
- resolve success updates expense once.
- retryable resolve shows retryable state and no update.
- needs user selection populates candidates.
- POI double-tap updates once.
- correction save double-tap saves once.
- correction conflict keeps sheet open with error.
- correction partial failure is explicit.
- assign location to unlocated expense updates expense and closes only on success.
- clear location command clears through lifecycle path after fix.

### `LocationSearchCoordinatorTest`
Required cases:
- query length < 2 does not call provider.
- debounce only searches latest query.
- cancellation clears loading.
- provider disabled maps to privacy blocked.
- rate-limited maps to retryable error.
- search success maps typed UI results.
- stale search result ignored.
- reverse-geocode failure clears loading.
- stale reverse-geocode result ignored.
- Google toggle blocked by policy does not call provider.

### `CoordinateInputParserTest`
Required cases:
- valid positive/negative lat/lon.
- out-of-range lat/lon.
- comma decimal if supported.
- blank.
- NaN/Infinity.
- non-numeric input.
- trimming whitespace.

### `LocationResolverPrivacyTest`
Required cases:
- external geocoding denied makes no geocoding call.
- Overpass denied makes no Overpass call.
- device GPS denied makes no location provider call.
- Overpass single POI rejected when name dissimilar.
- Overpass single POI rejected when too far.
- transient provider error returns Retryable.

### `LocationInsightsCurrencyTest`
Required cases:
- normalized insights skip failed conversions.
- normalized insights total equals converted spend.
- raw `compute()` either deprecated or tested as raw-only legacy.
- merchant names sorted by count.

---

## Compose/component tests

### `SpendingMapScreenContentTest`
- loading shows skeleton.
- permission blocked card visible.
- conversion warning banner visible with correct type.
- filters render and callbacks fire.
- highlighted merchant chip close callback.
- marker detail card callbacks.
- unlocated panel pin callback.
- stats bar semantics.

### `LocationPermissionDialogTest`
- show/hide.
- grant invokes callback.
- dismiss invokes callback.
- permanent denial variant after refactor opens settings.

### `LocationSearchPickerContentTest`
After refactor to pure state:
- renders current location.
- clear location callback.
- search query callback.
- provider toggle disabled when blocked.
- results list click callback.
- pin card confirm/cancel callbacks.
- manual coordinate entry validation callbacks.
- privacy-blocked state visible.

### `NearbyShopSuggestionCardTest`
- renders name/address/category.
- distance formatted by formatter.
- click callback.
- accessible content description.
- loading/disabled state after mutation state added.

### `MapMarkerDetailCardTest`
- amount uses marker display currency.
- conversion warning visible.
- re-resolve disabled while resolving.
- correct-pin callback.
- close callback.

### OSMDroid instrumented smoke tests
- map creates/destroys without crash.
- background/foreground calls lifecycle safely.
- marker click opens detail.
- map touch does not get stolen by parent sheet.
- long-press pin callback fires in `ResultsMapView`.

---

# 6. Implementation order for agent

## Phase A — Baseline and inventory

1. Run compile.
2. Run current map/location tests.
3. Inventory location UI usage:
```bash
grep -R "LocationSearchPicker\|LocationCorrectionSheet\|geocodingService" app/src/main/java
```
4. Inventory logs:
```bash
grep -R "LocationSearch\|Log.d\|Log.e\|Timber" app/src/main/java/com/yourname/expensetracker/ui app/src/main/java/com/yourname/expensetracker/data/location app/src/main/java/com/yourname/expensetracker/domain/location
```
5. Inventory EUR/default center:
```bash
grep -R '"EUR"\|37.9838\|23.7275\|GREECE' app/src/main/java/com/yourname/expensetracker/ui/screens/map app/src/main/java/com/yourname/expensetracker/ui/components app/src/main/java/com/yourname/expensetracker/domain/location app/src/main/java/com/yourname/expensetracker/data/location
```

## Phase B — Add tests before broad refactor

Add:
```text
SpendingMapCurrencyTest.kt
SpendingMapFilterRaceTest.kt
SpendingMapPermissionPrivacyTest.kt
SpendingMapMutationTest.kt
LocationSearchCoordinatorTest.kt
CoordinateInputParserTest.kt
LocationResolverPrivacyTest.kt
LocationInsightsCurrencyTest.kt
```

## Phase C — Critical correctness fixes

1. Fix My Location button visibility.
2. Remove placeholder EUR from map computation.
3. Make home currency reactive with map recompute.
4. Split marker conversion warnings from heatmap/insight conversion warnings.
5. Add filter recompute `mapLatest` / request ID.
6. Add mutation idempotency and inline error state.
7. Fix correction partial-failure policy.

## Phase D — Privacy/security fixes

1. Stop raw query/address logs in `LocationSearchPicker`.
2. Add privacy-blocked states for GPS/geocoding/Overpass.
3. Hide/disable Google toggle when provider/policy blocks it.
4. Avoid exact coordinates in normal UI except advanced/manual/debug.
5. Add settings navigation for privacy/permission blocked states.

## Phase E — Extract location search/control logic

1. Add `LocationSearchCoordinator`.
2. Add ViewModel-managed `LocationPickerState`.
3. Make `LocationSearchPicker` pure UI.
4. Split `LocationSearchPicker` subcomponents.

## Phase F — Map UI extraction

Extract:
- `SpendingMapRoute`
- `SpendingMapScreenContent`
- `SpendingMapFilters`
- `SpendingMapView`
- `MapMarkerDetailCard`
- `MapOverpassCandidates`
- `MapStatsBar`
- `UnlocatedExpensesPanel`
- `PinExpenseSheet`

## Phase G — OSMDroid hardening

1. Add lifecycle observer.
2. Make default region configurable/data-driven.
3. Move center behavior to explicit UI events.
4. Add accessibility summary/test tags.
5. Add instrumented smoke tests.

## Phase H — Localization/theme/accessibility

1. Replace raw strings with resources/UiText.
2. Add `DistanceFormatter`.
3. Add coordinate formatter/parser.
4. Align colors with Slice 2 theme policy.
5. Add semantic summaries and stable test tags.

---

# 7. Cross-slice golden scenarios after local tests pass

Add only after Slice 10 local tests are green:

1. Manual Add Expense with location appears as map marker.
2. Location correction updates Transactions row, Map marker, and merchant correction cache.
3. Unlocated expense pinned on Map disappears from unlocated panel and appears as marker.
4. Privacy disabled GPS: Map still loads markers but center-on-me is blocked visibly.
5. Privacy disabled external geocoding: correction/search UI shows blocked state and no network call.
6. Multi-currency map: marker totals, heatmap, and analytics location insights use same normalized basis.
7. Missing FX rate: marker/heatmap warnings visible and counts correct.
8. Analytics location section and Spending Map place insight totals agree.
9. Restore mode blocks location writes but allows read-only map display if read barrier permits.
10. Clearing a location removes marker and logs a lifecycle update.

---

# 8. Acceptance checklist for Slice 10 green

Slice 10 is green when:

- [ ] `:app:compileDebugKotlin` passes.
- [ ] `:app:compileDebugUnitTestKotlin` passes.
- [ ] Map currency tests pass.
- [ ] Map filter race tests pass.
- [ ] Permission/privacy tests pass.
- [ ] Mutation/idempotency tests pass.
- [ ] Location search coordinator tests pass.
- [ ] Coordinate parser tests pass.
- [ ] No map computation can use placeholder EUR.
- [ ] Home currency changes recompute markers/heatmap/insights.
- [ ] My Location button is available after permission grant even before GPS fetch.
- [ ] GPS/geocoding/Overpass privacy-denied states are typed and visible.
- [ ] `GeocodingService` is not exposed directly to UI.
- [ ] `LocationSearchPicker` does not perform network/domain calls.
- [ ] Search/reverse-geocode cancellation cannot leave stale loading.
- [ ] Raw search queries/addresses are not logged.
- [ ] Location correction/POI/pin mutations are idempotency-safe.
- [ ] Correction partial failures are explicit/recoverable.
- [ ] OSMDroid lifecycle is handled for Activity pause/resume.
- [ ] Default map center is configurable or data-driven.
- [ ] Map UI has accessibility semantics/test tags.
- [ ] Docs are updated only after source/tests are green.

---

# 9. Agent guardrails

Do:
- Protect privacy and currency correctness first.
- Use fixed `TimeProvider`, fake currency converter, fake privacy gate.
- Add tests before extracting UI.
- Keep GPS access explicit; do not reintroduce auto-GPS-on-init.
- Treat geocoding/Overpass/Google as external privacy-sensitive providers.
- Make partial conversion and partial mutation states visible.
- Use instrumented tests for OSMDroid lifecycle/touch behavior.

Do not:
- Rewrite the entire location resolver.
- Let composables call geocoding services directly.
- Persist or display placeholder EUR.
- Log raw merchant/location/address/search text.
- Hide privacy blocks behind generic snackbars.
- Let old filter recompute overwrite newer filters.
- Add new map features before permission/currency/mutation tests exist.

Main invariant:

> Spending Map must render located spending on one explicit currency basis, never access GPS/geocoding without user/privacy permission, never leak raw location data through logs, and must make every location correction/search/save path deterministic, idempotent, and recoverable.