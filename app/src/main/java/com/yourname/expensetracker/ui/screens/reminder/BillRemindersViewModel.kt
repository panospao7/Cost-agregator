package com.yourname.expensetracker.ui.screens.reminder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.reminder.BillReminder
import com.yourname.expensetracker.domain.reminder.BillReminderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BillRemindersViewModel @Inject constructor(
    private val billReminderManager: BillReminderManager,
    private val currencySettingsRepository: CurrencySettingsRepository
) : ViewModel() {
    
    private val _reminders = MutableStateFlow<List<BillReminder>>(emptyList())
    val reminders: StateFlow<List<BillReminder>> = _reminders.asStateFlow()
    
    private val _monthlyTotal = MutableStateFlow<MoneyAggregateResult>(unavailableMonthlyTotal())
    val monthlyTotal: StateFlow<MoneyAggregateResult> = _monthlyTotal.asStateFlow()
    private val monthlyRefreshRequests = MutableStateFlow(0L)
    
    init {
        loadReminders()
        observeCurrencyChanges()
    }
    
    private fun loadReminders() {
        viewModelScope.launch {
            try {
                val upcoming = billReminderManager.getUpcomingReminders()
                _reminders.value = upcoming
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _reminders.value = emptyList()
            }
        }
    }

    private fun observeCurrencyChanges() {
        viewModelScope.launch {
            try {
                // Currency changes and manual refreshes must cancel the same
                // in-flight calculation; separate launches can publish stale totals.
                currencySettingsRepository.homeCurrency()
                    .map { Unit }
                    .catch { failure ->
                        if (failure is CancellationException) throw failure
                        _monthlyTotal.value = unavailableMonthlyTotal()
                        // Retain a trigger after observer failure so a later refresh
                        // can retry the manager's typed home-currency resolution.
                        emit(Unit)
                    }
                    .combine(monthlyRefreshRequests) { _, _ -> Unit }
                    .collectLatest { calculateMonthlyTotalNow() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _monthlyTotal.value = unavailableMonthlyTotal()
            }
        }
    }

    private suspend fun calculateMonthlyTotalNow() {
        try {
            _monthlyTotal.value = billReminderManager.getMonthlyBillsTotal()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _monthlyTotal.value = unavailableMonthlyTotal()
        }
    }

    fun refresh() {
        loadReminders()
        monthlyRefreshRequests.value += 1
    }

    private companion object {
        fun unavailableMonthlyTotal(): MoneyAggregateResult.Unavailable =
            MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE.name,
                requestedRateBasis = RateBasis.LATEST_AVAILABLE,
                warningMessage = MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE.name
            )
    }
}
