package com.yourname.expensetracker.ui.screens.price

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.price.PriceProtectionTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PriceProtectionViewModel @Inject constructor(
    private val priceTracker: PriceProtectionTracker
) : ViewModel() {
    
    private val _priceDrops = MutableStateFlow<List<PriceProtectionTracker.PriceDropAlert>>(emptyList())
    val priceDrops: StateFlow<List<PriceProtectionTracker.PriceDropAlert>> = _priceDrops.asStateFlow()
    
    private val _protectedItems = MutableStateFlow<List<PriceProtectionTracker.PriceProtectedItem>>(emptyList())
    val protectedItems: StateFlow<List<PriceProtectionTracker.PriceProtectedItem>> = _protectedItems.asStateFlow()
    
    private val _deals = MutableStateFlow<List<PriceProtectionTracker.DealAlternative>>(emptyList())
    val deals: StateFlow<List<PriceProtectionTracker.DealAlternative>> = _deals.asStateFlow()
    
    private val _coupons = MutableStateFlow<List<PriceProtectionTracker.CouponMatch>>(emptyList())
    val coupons: StateFlow<List<PriceProtectionTracker.CouponMatch>> = _coupons.asStateFlow()
    
    private val _creditCardBenefits = MutableStateFlow<List<PriceProtectionTracker.CreditCardBenefit>>(emptyList())
    val creditCardBenefits: StateFlow<List<PriceProtectionTracker.CreditCardBenefit>> = _creditCardBenefits.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Load protected items
                _protectedItems.value = priceTracker.getPriceProtectedItems()
                
                // Monitor for price drops
                priceTracker.monitorPriceDrops().collect { drops ->
                    _priceDrops.value = drops
                }
                
                // Load deals, coupons, and benefits from recent receipts
                loadDealsAndBenefits()
                
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun refreshPriceDrops() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                priceTracker.monitorPriceDrops().collect { drops ->
                    _priceDrops.value = drops
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    private suspend fun loadDealsAndBenefits() {
        // In a real implementation, this would load from recent receipts
        // For now, we'll leave it empty as the data would come from the price tracker
    }
}
