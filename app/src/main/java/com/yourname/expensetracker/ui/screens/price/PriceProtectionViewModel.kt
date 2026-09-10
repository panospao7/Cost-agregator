package com.yourname.expensetracker.ui.screens.price

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.price.PriceProtectionTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PriceProtectionViewModel @Inject constructor(
    private val priceTracker: PriceProtectionTracker,
    currencySettingsRepository: CurrencySettingsRepository,
    /** WRN-24: Used to persist excluded tracking keys across app restarts. */
    @ApplicationContext private val context: Context
) : ViewModel() {

    val homeCurrency: Flow<String> = currencySettingsRepository.homeCurrency()

    private val refreshSignals = MutableSharedFlow<Unit>(replay = 1)

    /** WRN-24: SharedPreferences-backed persistence for excluded tracking keys. */
    private val excludedKeysPrefs = context.getSharedPreferences(
        "price_protection_excluded_keys", Context.MODE_PRIVATE
    )

    val priceDrops: StateFlow<List<PriceProtectionTracker.PriceDropAlert>> = refreshSignals
        .onStart { emit(Unit) }
        .flatMapLatest { priceTracker.monitorPriceDrops() }
        .onEach { _isLoading.value = false }
        .catch {
            if (it is kotlinx.coroutines.CancellationException) throw it
            _isLoading.value = false
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )
    
    private val _protectedItems = MutableStateFlow<List<PriceProtectionTracker.PriceProtectedItem>>(emptyList())
    val protectedItems: StateFlow<List<PriceProtectionTracker.PriceProtectedItem>> = _protectedItems.asStateFlow()

    /**
     * WRN-24: Excluded tracking keys are now persisted in SharedPreferences.
     * On init, previously excluded keys are restored so the exclusion survives
     * app restarts.
     */
    private val _excludedTrackingKeys = MutableStateFlow<Set<String>>(
        excludedKeysPrefs.all.keys.toSet()
    )
    val excludedTrackingKeys: StateFlow<Set<String>> = _excludedTrackingKeys.asStateFlow()
    
    private val _deals = MutableStateFlow<List<PriceProtectionTracker.DealAlternative>>(emptyList())
    val deals: StateFlow<List<PriceProtectionTracker.DealAlternative>> = _deals.asStateFlow()
    
    private val _coupons = MutableStateFlow<List<PriceProtectionTracker.CouponMatch>>(emptyList())
    val coupons: StateFlow<List<PriceProtectionTracker.CouponMatch>> = _coupons.asStateFlow()
    
    private val _creditCardBenefits = MutableStateFlow<List<PriceProtectionTracker.CreditCardBenefit>>(emptyList())
    val creditCardBenefits: StateFlow<List<PriceProtectionTracker.CreditCardBenefit>> = _creditCardBenefits.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Load protected items
                _protectedItems.value = priceTracker.getPriceProtectedItems()

                // Load deals, coupons, and benefits from recent receipts
                loadDealsAndBenefits()

                // Trigger a refresh of the shared price-drop stream.
                refreshSignals.emit(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Price protection data load failed — UI handles empty states gracefully
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshPriceDrops() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                refreshSignals.emit(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _isLoading.value = false
            } finally {
                // set to false in stream onEach/catch to reflect completed refresh signal
            }
        }
    }

    private suspend fun loadDealsAndBenefits() {
        val payload = priceTracker.getDealsCouponsAndBenefits()
        _deals.value = payload.deals
        _coupons.value = payload.coupons
        _creditCardBenefits.value = payload.benefits
    }

    /**
     * WRN-24: Persists the exclusion to SharedPreferences so the setting
     * survives app restarts.
     */
    fun removeFromTracking(item: PriceProtectionTracker.PriceProtectedItem) {
        val key = trackingKey(item)
        _excludedTrackingKeys.value = _excludedTrackingKeys.value + key
        excludedKeysPrefs.edit().putBoolean(key, true).apply()
    }

    /**
     * WRN-24: Removes the exclusion from SharedPreferences so the item
     * reappears in tracking on next app launch.
     */
    fun trackItem(item: PriceProtectionTracker.PriceProtectedItem) {
        val key = trackingKey(item)
        _excludedTrackingKeys.value = _excludedTrackingKeys.value - key
        excludedKeysPrefs.edit().remove(key).apply()
    }

    fun isTracked(item: PriceProtectionTracker.PriceProtectedItem): Boolean {
        return trackingKey(item) !in _excludedTrackingKeys.value
    }

    private fun trackingKey(item: PriceProtectionTracker.PriceProtectedItem): String {
        return "${item.receiptId}:${item.itemName.lowercase()}:${item.purchaseDate}"
    }
}
