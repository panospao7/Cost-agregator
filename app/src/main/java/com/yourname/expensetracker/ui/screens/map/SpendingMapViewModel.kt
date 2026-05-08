package com.yourname.expensetracker.ui.screens.map

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import timber.log.Timber
import com.yourname.expensetracker.data.database.entity.MerchantLocationCorrection
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.domain.location.ForegroundLocationProvider
import com.yourname.expensetracker.domain.location.ConversionStatus
import com.yourname.expensetracker.domain.location.LocatedExpense
import com.yourname.expensetracker.domain.location.LocatedMoneyExpense
import com.yourname.expensetracker.domain.location.LocationResolutionResult
import com.yourname.expensetracker.domain.location.LocationResolver
import com.yourname.expensetracker.domain.location.NearbyPoi
import com.yourname.expensetracker.domain.location.SpendingHeatmapEngine
import com.yourname.expensetracker.domain.location.HeatmapPoint
import com.yourname.expensetracker.domain.location.LocationInsightsEngine
import com.yourname.expensetracker.domain.location.PlaceInsight
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
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
    val placeId: String?,
    val isConverted: Boolean = true
)

data class MapCategoryFilterOption(
    val key: String,
    val label: String
)

enum class DateRangePreset {
    LAST_7_DAYS,
    LAST_30_DAYS,
    LAST_90_DAYS
}

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
 val overpassCandidates: List<NearbyPoi> = emptyList(),
 val showCorrectionSheet: Boolean = false,
 val pendingCorrectionMerchant: String? = null,
 val pendingCorrectionLat: Double? = null,
 val pendingCorrectionLon: Double? = null,
 val pendingCorrectionAddress: String? = null,
 val pendingCorrectionOsmId: String? = null,
 val totalLocatedExpenses: Int = 0,
 val totalUnlocatedExpenses: Int = 0,
 val isResolvingLocation: Boolean = false,
 val snackbarMessage: String? = null,
 val unlocatedExpenses: List<Expense> = emptyList(),
 val expenseToPin: Expense? = null,
 val selectedCategories: Set<String> = emptySet(),
 val selectedDateRangePreset: DateRangePreset? = null,
 val dateRangeStartMs: Long? = null,
 val dateRangeEndMs: Long? = null,
 val availableCategories: List<MapCategoryFilterOption> = emptyList(),
  val highlightedMerchantQuery: String? = null,
   /**
    * Placeholder default; overridden by [CurrencySettingsRepository.homeCurrency] during init.
    *
    * ## Acceptable hardcoded "EUR"
    * This initial value is immediately replaced by the repository flow in the
    * ViewModel's `init` block. It only serves as a non-null default before the
    * async home-currency load completes. See [CURR-6] in MASTER-ISSUE-REGISTRY.
    */
   val homeCurrency: String = "EUR",
  val referenceNowMillis: Long = 0L
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class SpendingMapViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository,
    private val locationResolver: LocationResolver,
    private val locationProvider: ForegroundLocationProvider,
    private val merchantLocationRepository: MerchantLocationRepository,
    private val heatmapEngine: SpendingHeatmapEngine,
 private val insightsEngine: LocationInsightsEngine,
 val geocodingService: com.yourname.expensetracker.domain.location.GeocodingService,
  private val currencySettingsRepository: CurrencySettingsRepository,
  private val currencyConverter: CurrencyConverter,
  private val timeProvider: TimeProvider,
  private val privacyGate: PrivacyGate
) : ViewModel() {

    private val _state = MutableStateFlow(SpendingMapState(referenceNowMillis = timeProvider.now()))
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
 viewModelScope.launch(Dispatchers.IO) {
 currencySettingsRepository.homeCurrency().collect { hc ->
 _state.update { it.copy(homeCurrency = hc) }
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
            val result = try {
                locationResolver.resolve(
                    rawMerchantName = marker.merchant,
                    transactionDateMs = marker.date,
                    forceRefresh = true
                )
            } catch (e: Exception) {
                Log.w(TAG, "Temporary location resolution failure for expenseId=${marker.expenseId}", e)
                _state.update { it.copy(
                    isResolvingLocation = false,
                    snackbarMessage = TEMPORARY_LOCATION_FAILURE_MESSAGE
                ) }
                return@launch
            }
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
                is LocationResolutionResult.Retryable -> {
                    _state.update { it.copy(
                        isResolvingLocation = false,
                        snackbarMessage = TEMPORARY_LOCATION_FAILURE_MESSAGE
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
                source = com.yourname.expensetracker.domain.config.AppConfig.Location.SOURCE_USER_CONFIRMED_POI,
                placeId = poi.osmId,
                address = poi.displayAddress
            )
            val resolved = LocationResolutionResult.Resolved(
                latitude = poi.latitude,
                longitude = poi.longitude,
                source = com.yourname.expensetracker.domain.config.AppConfig.Location.SOURCE_USER_CONFIRMED_POI,
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
            // B15 fix: use the corrected location as the area center, not the
            // device's current GPS. If the user is at home correcting a remote
            // transaction, the device location would produce the wrong area key
            // and the correction wouldn't match future transactions near the store.
            merchantLocationRepository.saveCorrection(
                MerchantLocationCorrection(
                    normalizedMerchantName = merchantLocationRepository.normalizeKey(merchantName),
                    correctedLatitude = correctedLat,
                    correctedLongitude = correctedLon,
                    areaLatitude = correctedLat,
                    areaLongitude = correctedLon,
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
    private suspend fun recomputeMapData(locatedExpenses: List<com.yourname.expensetracker.data.database.entity.Expense>) {
        val currentState = _state.value
        val filteredExpenses = locatedExpenses.filter { expense ->
            val dateInRange = (currentState.dateRangeStartMs == null || expense.date >= currentState.dateRangeStartMs) &&
                (currentState.dateRangeEndMs == null || expense.date < currentState.dateRangeEndMs)
            val categoryMatch = currentState.selectedCategories.isEmpty() ||
                currentState.selectedCategories.contains(expense.categoryId?.toString() ?: UNCATEGORIZED_KEY)
            dateInRange && categoryMatch
        }

        // B11 fix: use safe-call instead of force-unwrap (!!). The Flow query
        // filters for non-null coordinates, but if a location is cleared between
        // emission and this mapping, force-unwrap would NPE-crash the app.
        // LOC-3: Normalize marker amounts to home currency so map markers
        // display amounts in the user's preferred currency regardless of the
        // original expense currency.
        val markers = filteredExpenses.mapNotNull { e ->
            val lat = e.latitude ?: return@mapNotNull null
            val lon = e.longitude ?: return@mapNotNull null
            val (homeAmount, isConverted) = if (e.currency != currentState.homeCurrency) {
                val converted = currencyConverter.convert(
                    amount = e.effectiveAmount,
                    fromCurrency = e.currency,
                    toCurrency = currentState.homeCurrency
                )
                if (converted != null) {
                    Pair(converted.convertedAmount, true)
                } else {
                    Timber.w("Conversion failed for marker ${e.merchant}: ${e.effectiveAmount} ${e.currency}")
                    Pair(e.effectiveAmount, false)
                }
            } else {
                Pair(e.effectiveAmount, true)
            }
            MapExpenseMarker(
                expenseId = e.id,
                latitude = lat,
                longitude = lon,
                amount = homeAmount,
                merchant = e.merchant,
                date = e.date,
                locationSource = e.locationSource,
                placeId = e.placeId,
                isConverted = isConverted
            )
        }

        // Map to domain LocatedExpense before calling domain engines
        // Apply the same spending-only filter used for heatmap — non-spending
        // types (deposits, transfers, withdrawals) must not contribute to insights.
        val spendingDomainExpenses = filteredExpenses.filter { e ->
            e.transactionType.toDomain().isSpending
        }.mapNotNull { e ->
            val lat = e.latitude ?: return@mapNotNull null
            val lon = e.longitude ?: return@mapNotNull null
            LocatedExpense(
                expenseId = e.id,
                latitude = lat,
                longitude = lon,
                amount = e.effectiveAmount,
                merchant = e.merchant,
                date = e.date,
                locationSource = e.locationSource,
                placeId = e.placeId,
                currency = e.currency
            )
        }

        // Filter to canonical spending types only for the heatmap.
        // Deposits, transfers, and withdrawals must not contribute to the
        // spending heatmap — they are kept in `markers` for UI display but
        // excluded from heatmap weight calculation.
        val spendingOnlyExpenses = filteredExpenses.filter { e ->
            e.transactionType.toDomain().isSpending
        }
        val heatmapExpenses = spendingOnlyExpenses.mapNotNull { e ->
            val lat = e.latitude ?: return@mapNotNull null
            val lon = e.longitude ?: return@mapNotNull null
            LocatedExpense(
                expenseId = e.id,
                latitude = lat,
                longitude = lon,
                amount = e.effectiveAmount,
                merchant = e.merchant,
                date = e.date,
                locationSource = e.locationSource,
                placeId = e.placeId,
                currency = e.currency
            )
        }

        // PR-E6: Create LocatedMoneyExpense instances for multi-currency-safe heatmap
        val moneyExpenses = spendingOnlyExpenses.mapNotNull { e ->
            val lat = e.latitude ?: return@mapNotNull null
            val lon = e.longitude ?: return@mapNotNull null
            val (normalizedAmount, conversionStatus) = if (e.currency == currentState.homeCurrency) {
                Pair(e.effectiveAmount, ConversionStatus.HOME_CURRENCY)
            } else {
                val converted = currencyConverter.convertAsOf(
                    amount = e.effectiveAmount,
                    fromCurrency = e.currency,
                    toCurrency = currentState.homeCurrency,
                    atMillis = e.date
                )
                if (converted != null) {
                    Pair(converted.convertedAmount, ConversionStatus.CONVERTED)
                } else {
                    Pair(null, ConversionStatus.FAILED)
                }
            }
            LocatedMoneyExpense(
                expenseId = e.id,
                latitude = lat,
                longitude = lon,
                normalizedAmount = normalizedAmount,
                normalizedCurrency = currentState.homeCurrency,
                originalAmount = e.effectiveAmount,
                originalCurrency = e.currency,
                conversionStatus = conversionStatus,
                merchant = e.merchant,
                date = e.date
            )
        }
        // TODO PR-E6: use moneyExpenses with heatmapEngine.computeNormalized() once implemented
        // val normalizedHeatmap = heatmapEngine.computeNormalized(moneyExpenses)
        val heatmap = heatmapEngine.compute(heatmapExpenses)
        val insights = insightsEngine.compute(spendingDomainExpenses)

        val categoriesById = runCatching {
            categoryRepository.getAll().associateBy { it.id }
        }.getOrElse { emptyMap() }

        val categoryIds = locatedExpenses.mapNotNull { it.categoryId?.toString() }.distinct().sorted()
        val hasUncategorized = locatedExpenses.any { it.categoryId == null }
        val availableCategories = buildList<MapCategoryFilterOption> {
            categoryIds.forEach { categoryId ->
                val label = categoriesById[categoryId.toLongOrNull()]?.name ?: "Category $categoryId"
                add(MapCategoryFilterOption(key = categoryId, label = label))
            }
            if (hasUncategorized) {
                add(MapCategoryFilterOption(key = UNCATEGORIZED_KEY, label = "Uncategorized"))
            }
        }

        _state.update { it.copy(
            isLoading = false,
            markers = markers,
            heatmapPoints = heatmap,
            placeInsights = insights,
            availableCategories = availableCategories,
            referenceNowMillis = timeProvider.now()
        ) }
    }

    fun toggleCategoryFilter(categoryKey: String) {
        _state.update { current ->
            val next = current.selectedCategories.toMutableSet().apply {
                if (contains(categoryKey)) remove(categoryKey) else add(categoryKey)
            }
            current.copy(selectedCategories = next)
        }
        viewModelScope.launch(Dispatchers.IO) {
            recomputeMapData(expenseRepository.getLocatedExpenses().first())
        }
    }

    fun clearFilters() {
        _state.update {
            it.copy(
                selectedCategories = emptySet(),
                selectedDateRangePreset = null,
                dateRangeStartMs = null,
                dateRangeEndMs = null,
                highlightedMerchantQuery = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            recomputeMapData(expenseRepository.getLocatedExpenses().first())
        }
    }

    fun focusOnMerchant(query: String?) {
        _state.update { it.copy(highlightedMerchantQuery = query?.trim()?.takeIf(String::isNotBlank)) }
    }

    fun setDateRange(startMs: Long?, endMs: Long?, preset: DateRangePreset? = null) {
        _state.update {
            it.copy(
                selectedDateRangePreset = preset,
                dateRangeStartMs = startMs,
                dateRangeEndMs = endMs
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            recomputeMapData(expenseRepository.getLocatedExpenses().first())
        }
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
     *
     * Checks app-level GPS privacy gate before accessing device location.
     */
    private fun fetchDeviceLocation() {
        viewModelScope.launch {
            // Check app-level GPS privacy before accessing device location
            val decision = privacyGate.check(PrivacyCapability.DEVICE_GPS_LOCATION)
            if (decision is PrivacyDecision.Denied) {
                Timber.d("Device GPS denied by privacy settings")
                _state.update {
                    it.copy(snackbarMessage = "Device GPS is disabled in Privacy settings.")
                }
                return@launch
            }
            try {
                val loc = locationProvider.getLastKnownLocation() ?: return@launch
                cachedDeviceLoc = loc
                _state.update { it.copy(
                    deviceLatitude = loc.first,
                    deviceLongitude = loc.second,
                    snackbarMessage = null
                ) }
            } catch (se: SecurityException) {
                Log.w(TAG, "Location permission changed during fetch", se)
                _state.update {
                    it.copy(snackbarMessage = "Location permission changed. Please re-enable to show device position.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch device location", e)
                _state.update {
                    it.copy(snackbarMessage = "Unable to fetch device location right now.")
                }
            }
        }
    }

    private companion object {
        const val TAG = "SpendingMapViewModel"
        const val UNCATEGORIZED_KEY = "uncategorized"
        const val TEMPORARY_LOCATION_FAILURE_MESSAGE = "Temporary location lookup failure. Please try again."
    }

    // Boundary mapper: data-layer TransactionType -> domain DomainTransactionType
    private fun TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }
}
