package com.yourname.expensetracker.ui.screens.cashflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator
import com.yourname.expensetracker.domain.cashflow.DailyCashFlow
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

data class CashFlowCalendarState(
 val dailyCashFlows: List<DailyCashFlow> = emptyList(),
 val isLoading: Boolean = false,
 val error: String? = null,
 val currentMonth: Date = Date(0L),
 val selectedDate: Date? = null,
 val viewMode: CalendarViewMode = CalendarViewMode.MONTH,
 val startingBalance: Double = 0.0,
 val upcomingBillsCount: Int = 0,
	/** S8-024: null until loaded — never defaults to "EUR" */
	val homeCurrency: String? = null
) {
 val moneyStartingBalance: MoneyAmount get() = MoneyAmount(startingBalance, CurrencyCode(homeCurrency ?: ""))

 val loadableState: com.yourname.expensetracker.ui.model.LoadableUiState<List<DailyCashFlow>>
     get() = when {
         isLoading -> com.yourname.expensetracker.ui.model.LoadableUiState.Loading
         error != null -> com.yourname.expensetracker.ui.model.LoadableUiState.Error(
             com.yourname.expensetracker.domain.model.UiText.DynamicString(error)
         )
         dailyCashFlows.isEmpty() -> com.yourname.expensetracker.ui.model.LoadableUiState.Empty(
             com.yourname.expensetracker.domain.model.UiText.DynamicString("No cash flow data")
         )
         else -> com.yourname.expensetracker.ui.model.LoadableUiState.Data(dailyCashFlows)
     }
}

enum class CalendarViewMode {
    MONTH, WEEK, DAY
}

@HiltViewModel
class CashFlowCalendarViewModel @Inject constructor(
 private val cashFlowCalculator: CashFlowCalculator,
 private val timeProvider: TimeProvider,
 private val currencySettingsRepository: CurrencySettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(CashFlowCalendarState(currentMonth = Date(timeProvider.now())))
    val state: StateFlow<CashFlowCalendarState> = _state.asStateFlow()

    /** S8-020: Race guard — cancel prior load job */
    private var cashFlowJob: kotlinx.coroutines.Job? = null
    private var cashFlowRequestId = 0L

 init {
 // P6-CURRENT-020: collect the home currency FIRST so the typed starting balance
 // (MoneyAmount, denominated in home currency) can be built before the initial load
 // runs. The load guards the not-yet-loaded case, but observing currency first keeps
 // the common path from needlessly erroring on startup.
 collectHomeCurrency()
 loadCurrentMonth()
 loadUpcomingBills()
 }

    fun loadCurrentMonth() {
        val monthRange = TimePeriodUtils.getMonthRange(timeProvider.now())
        loadCashFlow(Date(monthRange.first), Date(monthRange.second))
    }

    fun loadCashFlow(startDate: Date, endDate: Date) {
        // S8-020: Cancel prior job and increment request ID
        val requestId = ++cashFlowRequestId
        cashFlowJob?.cancel()

        cashFlowJob = viewModelScope.launch {
            // DBG-01: Snapshot state ONCE so the balance currency and amount come from a
            // single consistent view. The MoneyAmount handed to the calculator is built
            // from the SAME resolved home currency captured here, avoiding the stale-
            // currency window where the calculator resolves a newer currency than the one
            // the balance was denominated in (which would trip the calculator's require).
            val snapshot = _state.value
            val homeCurrency = snapshot.homeCurrency
            if (homeCurrency.isNullOrBlank()) {
                // DBG-01: Not-ready guard. Home currency has not loaded yet, so building
                // CurrencyCode("") would throw IllegalArgumentException and get trapped in a
                // terminal error state that never recovers. Instead hold a benign Loading
                // state and return; collectHomeCurrency() will re-trigger the real load once
                // the currency arrives (or changes), so the screen recovers/refreshes.
                _state.update { it.copy(isLoading = true, error = null) }
                return@launch
            }
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                // P6-CURRENT-020 / DBG-01: pass the typed, home-currency-denominated balance,
                // built from the currency snapshot captured above so it matches the currency
                // the calculator will resolve.
                val startingBalance = MoneyAmount(snapshot.startingBalance, CurrencyCode(homeCurrency))
                val cashFlows = cashFlowCalculator.calculateDailyCashFlow(
                    startDate = startDate,
                    endDate = endDate,
                    startingBalance = startingBalance
                )
                // S8-020: Discard stale result
                if (requestId != cashFlowRequestId) return@launch
                _state.update {
                    it.copy(
                        dailyCashFlows = cashFlows,
                        isLoading = false,
                        currentMonth = startDate,
                        // S8-026: Clear selected date if it's outside the new month
                        selectedDate = it.selectedDate?.takeIf { d ->
                            d.time >= startDate.time && d.time < endDate.time
                        }
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // S8-019: Surface error instead of stuck loading
                if (requestId != cashFlowRequestId) return@launch
                _state.update { it.copy(isLoading = false, error = "Failed to load cash flow: ${e.message}") }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun selectDate(date: Date?) {
        _state.update { it.copy(selectedDate = date) }
    }

    fun changeViewMode(mode: CalendarViewMode) {
        _state.update { it.copy(viewMode = mode) }
    }

    fun setStartingBalance(balance: Double) {
        _state.update { it.copy(startingBalance = balance) }
        loadCurrentMonth()
    }

    fun navigateToPreviousMonth() {
        val monthRange = TimePeriodUtils.getMonthRange(_state.value.currentMonth.time, -1)
        loadCashFlow(Date(monthRange.first), Date(monthRange.second))
    }

    fun navigateToNextMonth() {
        val monthRange = TimePeriodUtils.getMonthRange(_state.value.currentMonth.time, 1)
        loadCashFlow(Date(monthRange.first), Date(monthRange.second))
    }

 private fun loadUpcomingBills() {
 viewModelScope.launch {
     runCatching {
         val bills = cashFlowCalculator.getUpcomingBills(30)
         _state.update { it.copy(upcomingBillsCount = bills.size) }
     }
 }
 }

 private fun collectHomeCurrency() {
 viewModelScope.launch {
     // S8-024: No EUR fallback — null until loaded.
     // DBG-01: distinctUntilChanged so we only react to genuine arrivals/changes,
     // and re-trigger the load each time so the screen recovers from the not-ready
     // guard on first emission and refreshes on a runtime currency change. Because
     // loadCashFlow reads the latest state snapshot, the reload always uses the new
     // currency for both the balance and the calculator resolution (no stale window).
     currencySettingsRepository.homeCurrency()
         .distinctUntilChanged()
         .collect { hc ->
             _state.update { it.copy(homeCurrency = hc) }
             if (!hc.isNullOrBlank()) {
                 // Reload the currently-viewed month using the resolved currency.
                 loadCashFlow(
                     Date(TimePeriodUtils.getMonthRange(_state.value.currentMonth.time).first),
                     Date(TimePeriodUtils.getMonthRange(_state.value.currentMonth.time).second)
                 )
             }
         }
 }
 }
}
