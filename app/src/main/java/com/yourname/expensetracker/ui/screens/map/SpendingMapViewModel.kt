package com.yourname.expensetracker.ui.screens.map

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MerchantLocationCorrection
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.domain.location.ForegroundLocationProvider
import com.yourname.expensetracker.domain.location.LocatedExpense
import com.yourname.expensetracker.domain.location.LocationResolutionResult
import com.yourname.expensetracker.domain.location.LocationResolver
import com.yourname.expensetracker.domain.location.NearbyPoi
import com.yourname.expensetracker.domain.location.SpendingHeatmapEngine
import com.yourname.expensetracker.domain.location.HeatmapPoint
import com.yourname.expensetracker.domain.location.LocationInsightsEngine
import com.yourname.expensetracker.domain.location.PlaceInsight
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── State ─────────────────────────────────────────────────────────────────────

data class MapExpenseMarker(
    val expenseId: Long,
    val latitude: Double,
    val longitude: Double,
    val amount: Double,
    val merchant: String,
    val date: Long,
    val locationSource: String?,
    val placeId: String?
)

data class SpendingMapState(
    val isLoading: Boolean = true,
    val locationPermissionGranted: Boolean = false,
    val showPermissionRationale: Boolean = false,
    val deviceLatitude: Double? = null,
    val deviceLongitude: Double? = null,
    val markers: List<MapExpenseMarker> = emptyList(),
    val heatmapPoints: List<HeatmapPoint> = emptyList(),
    val placeInsights: List<PlaceInsight> = emptyList(),
    val selectedMarker: MapExpenseMarker? = null,
    // When Overpass returns multiple candidates for the selected marker
    val overpassCandidates: List<NearbyPoi> = emptyList(),
    val showCorrectionSheet: Boolean = false,
    // Pending correction context — set while the bottom sheet is open
    val pendingCorrectionMerchant: String? = null,
    val pendingCorrectionLat: Double? = null,
    val pendingCorrectionLon: Double? = null,
    val pendingCorrectionAddress: String? = null,
    val pendingCorrectionOsmId: String? = null,
    // Stats
    val totalLocatedExpenses: Int = 0,
    val totalUnlocatedExpenses: Int = 0,
    val isResolvingLocation: Boolean = false,
    val snackbarMessage: String? = null,
    // Feature E: unlocated expenses list
    val unlocatedExpenses: List<Expense> = emptyList(),
    val expenseToPin: Expense? = null
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class SpendingMapViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val locationResolver: LocationResolver,
    private val locationProvider: ForegroundLocationProvider,
    private val merchantLocationRepository: MerchantLocationRepository,
    private val heatmapEngine: SpendingHeatmapEngine,
    private val insightsEngine: LocationInsightsEngine
) : ViewModel() {

    private val _state = MutableStateFlow(SpendingMapState())
    val state: StateFlow<SpendingMapState> = _state.asStateFlow()

    // Cached device location — refreshed once per permission grant, not on
    // every loadMapData() call (fixes bug #11).
    private var cachedDeviceLoc: Pair<Double, Double>? = null

    init {
        // Bug #27 fix: use collect instead of first() so the map auto-updates
        // when the DB changes (new expenses added, locations resolved, etc.)
        viewModelScope.launch(Dispatchers.IO) {
            expenseRepository.getLocatedExpenses().collect { locatedExpenses ->
                recomputeMapData(locatedExpenses)
            }
        }
        // Load stats (located / unlocated counts) reactively
        viewModelScope.launch(Dispatchers.IO) {
            refreshStats()
        }
        // Feature E: collect unlocated expenses for the bottom panel
        viewModelScope.launch(Dispatchers.IO) {
            expenseRepository.getUnlocatedExpensesFlow(100).collect { expenses ->
                _state.update { it.copy(unlocatedExpenses = expenses) }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun onPermissionResult(granted: Boolean) {
        // Bug #1 fix: use _state.update for CAS safety
        _state.update { it.copy(locationPermissionGranted = granted) }
        if (granted) {
            viewModelScope.launch(Dispatchers.IO) { fetchDeviceLocation() }
        }
    }

    fun onShowPermissionRationale(show: Boolean) {
        _state.update { it.copy(showPermissionRationale = show) }
    }

    fun onMarkerSelected(marker: MapExpenseMarker?) {
        _state.update { it.copy(
            selectedMarker = marker,
            overpassCandidates = emptyList(),
            showCorrectionSheet = false
        ) }
    }

    fun onResolveLocationForMarker(marker: MapExpenseMarker) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isResolvingLocation = true) }
            val result = locationResolver.resolve(
                rawMerchantName = marker.merchant,
                transactionDateMs = marker.date,
                forceRefresh = true
            )
            when (result) {
                is LocationResolutionResult.Resolved -> {
                    expenseRepository.updateExpenseLocation(
                        expenseId = marker.expenseId,
                        latitude = result.latitude,
                        longitude = result.longitude,
                        source = result.source,
                        placeId = result.osmId,
                        address = result.displayAddress
                    )
                    _state.update { it.copy(
                        isResolvingLocation = false,
                        snackbarMessage = "Location resolved for ${marker.merchant}"
                    ) }
                    // DB change triggers collect → recomputeMapData automatically
                    refreshStats()
                }
                is LocationResolutionResult.NeedsUserSelection -> {
                    _state.update { it.copy(
                        isResolvingLocation = false,
                        overpassCandidates = result.candidates
                    ) }
                }
                is LocationResolutionResult.Unresolved -> {
                    _state.update { it.copy(
                        isResolvingLocation = false,
                        snackbarMessage = "Could not resolve location for ${marker.merchant}"
                    ) }
                }
            }
        }
    }

    fun onPoiSelected(poi: NearbyPoi, forMarker: MapExpenseMarker) {
        viewModelScope.launch(Dispatchers.IO) {
            expenseRepository.updateExpenseLocation(
                expenseId = forMarker.expenseId,
                latitude = poi.latitude,
                longitude = poi.longitude,
                source = com.yourname.expensetracker.domain.config.AppConfig.Location.SOURCE_OVERPASS_POI,
                placeId = poi.osmId,
                address = poi.displayAddress
            )
            val resolved = LocationResolutionResult.Resolved(
                latitude = poi.latitude,
                longitude = poi.longitude,
                source = com.yourname.expensetracker.domain.config.AppConfig.Location.SOURCE_OVERPASS_POI,
                osmId = poi.osmId,
                displayAddress = poi.displayAddress,
                confidence = 0.9f
            )
            merchantLocationRepository.saveLocation(forMarker.merchant, resolved)
            _state.update { it.copy(
                overpassCandidates = emptyList(),
                snackbarMessage = "Location set to ${poi.name}"
            ) }
            refreshStats()
        }
    }

    fun onOpenCorrectionSheet(marker: MapExpenseMarker) {
        _state.update { it.copy(
            showCorrectionSheet = true,
            pendingCorrectionMerchant = marker.merchant,
            pendingCorrectionLat = marker.latitude,
            pendingCorrectionLon = marker.longitude,
            pendingCorrectionAddress = null,
            pendingCorrectionOsmId = marker.placeId
        ) }
    }

    fun onCloseCorrectionSheet() {
        _state.update { it.copy(
            showCorrectionSheet = false,
            pendingCorrectionMerchant = null
        ) }
    }

    fun onSaveCorrection(
        merchantName: String,
        correctedLat: Double,
        correctedLon: Double,
        osmId: String?,
        displayAddress: String?,
        forMarker: MapExpenseMarker
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Use cached location so we don't trigger a new GPS fix here (#11)
            val deviceLoc = cachedDeviceLoc
            merchantLocationRepository.saveCorrection(
                MerchantLocationCorrection(
                    normalizedMerchantName = merchantLocationRepository.normalizeKey(merchantName),
                    correctedLatitude = correctedLat,
                    correctedLongitude = correctedLon,
                    areaLatitude = deviceLoc?.first,
                    areaLongitude = deviceLoc?.second,
                    osmId = osmId,
                    displayAddress = displayAddress
                )
            )
            expenseRepository.updateExpenseLocation(
                expenseId = forMarker.expenseId,
                latitude = correctedLat,
                longitude = correctedLon,
                source = com.yourname.expensetracker.domain.config.AppConfig.Location.SOURCE_USER_MANUAL,
                placeId = osmId,
                address = displayAddress
            )
            _state.update { it.copy(
                showCorrectionSheet = false,
                pendingCorrectionMerchant = null,
                snackbarMessage = "Correction saved for $merchantName"
            ) }
            refreshStats()
        }
    }

    fun onSnackbarDismissed() {
        _state.update { it.copy(snackbarMessage = null) }
    }

    // Feature E: pin-this flow for unlocated expenses
    fun onPinExpense(expense: Expense) {
        _state.update { it.copy(expenseToPin = expense) }
    }

    fun onDismissPinSheet() {
        _state.update { it.copy(expenseToPin = null) }
    }

    fun assignLocationToExpense(expense: Expense, lat: Double, lon: Double, address: String?, osmId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            expenseRepository.updateExpenseLocation(
                expenseId = expense.id,
                latitude = lat,
                longitude = lon,
                source = com.yourname.expensetracker.domain.config.AppConfig.Location.SOURCE_USER_MANUAL,
                placeId = osmId,
                address = address
            )
            _state.update { it.copy(
                expenseToPin = null,
                snackbarMessage = "Location saved for ${expense.merchant}"
            ) }
            refreshStats()
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Called from the collect block whenever the located-expenses flow emits.
     * Bug #10 fix: operates on [locatedExpenses] from [getLocatedExpenses()]
     * (no 500-row limit) instead of getAllExpenses().
     * Bug #7 fix: maps to [LocatedExpense] before passing to domain engines.
     */
    private fun recomputeMapData(locatedExpenses: List<com.yourname.expensetracker.data.database.entity.Expense>) {
        val markers = locatedExpenses.map { e ->
            MapExpenseMarker(
                expenseId = e.id,
                latitude = e.latitude!!,
                longitude = e.longitude!!,
                amount = e.amount,
                merchant = e.merchant,
                date = e.date,
                locationSource = e.locationSource,
                placeId = e.placeId
            )
        }

        // Map to domain LocatedExpense before calling domain engines
        val domainExpenses = locatedExpenses.map { e ->
            LocatedExpense(
                expenseId = e.id,
                latitude = e.latitude!!,
                longitude = e.longitude!!,
                amount = e.amount,
                merchant = e.merchant,
                date = e.date,
                locationSource = e.locationSource,
                placeId = e.placeId
            )
        }

        val heatmap = heatmapEngine.compute(domainExpenses)
        val insights = insightsEngine.compute(domainExpenses)

        _state.update { it.copy(
            isLoading = false,
            markers = markers,
            heatmapPoints = heatmap,
            placeInsights = insights
        ) }
    }

    /**
     * Refresh located/unlocated counts using DAO count queries.
     * Bug #32 fix: use countLocated() + countUnlocated() instead of
     * computing from an in-memory 500-row subset.
     */
    private suspend fun refreshStats() {
        try {
            val located = expenseRepository.countLocatedExpenses()
            val unlocated = expenseRepository.countUnlocatedExpenses()
            _state.update { it.copy(
                totalLocatedExpenses = located,
                totalUnlocatedExpenses = unlocated
            ) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh location stats", e)
        }
    }

    /**
     * Fetch device location once per permission grant and cache it.
     * Bug #11 fix: avoids repeated GPS calls on every data reload.
     */
    private suspend fun fetchDeviceLocation() {
        val loc = locationProvider.getLastKnownLocation() ?: return
        cachedDeviceLoc = loc
        _state.update { it.copy(
            deviceLatitude = loc.first,
            deviceLongitude = loc.second
        ) }
    }

    private companion object {
        const val TAG = "SpendingMapViewModel"
    }
}
