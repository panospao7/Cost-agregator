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
 loadCurrentMonth()
 loadUpcomingBills()
 collectHomeCurrency()
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
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val cashFlows = cashFlowCalculator.calculateDailyCashFlow(
                    startDate = startDate,
                    endDate = endDate,
                    startingBalance = _state.value.startingBalance
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
     // S8-024: No EUR fallback — null until loaded
     currencySettingsRepository.homeCurrency().collect { hc ->
         _state.update { it.copy(homeCurrency = hc) }
     }
 }
 }
}
