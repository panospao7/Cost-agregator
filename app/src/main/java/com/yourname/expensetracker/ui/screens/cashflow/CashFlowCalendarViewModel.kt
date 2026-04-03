package com.yourname.expensetracker.ui.screens.cashflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator
import com.yourname.expensetracker.domain.cashflow.DailyCashFlow
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

data class CashFlowCalendarState(
    val dailyCashFlows: List<DailyCashFlow> = emptyList(),
    val isLoading: Boolean = false,
    val currentMonth: Date = Date(0L),
    val selectedDate: Date? = null,
    val viewMode: CalendarViewMode = CalendarViewMode.MONTH,
    val startingBalance: Double = 0.0,
    val upcomingBillsCount: Int = 0
)

enum class CalendarViewMode {
    MONTH, WEEK, DAY
}

@HiltViewModel
class CashFlowCalendarViewModel @Inject constructor(
    private val cashFlowCalculator: CashFlowCalculator,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _state = MutableStateFlow(CashFlowCalendarState(currentMonth = Date(timeProvider.now())))
    val state: StateFlow<CashFlowCalendarState> = _state.asStateFlow()

    init {
        loadCurrentMonth()
        loadUpcomingBills()
    }

    fun loadCurrentMonth() {
        val monthRange = TimePeriodUtils.getMonthRange(timeProvider.now())
        loadCashFlow(Date(monthRange.first), Date(monthRange.second))
    }

    fun loadCashFlow(startDate: Date, endDate: Date) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            
            val cashFlows = cashFlowCalculator.calculateDailyCashFlow(
                startDate = startDate,
                endDate = endDate,
                startingBalance = _state.value.startingBalance
            )
            
            _state.update { 
                it.copy(
                    dailyCashFlows = cashFlows,
                    isLoading = false,
                    currentMonth = startDate
                )
            }
        }
    }

    fun selectDate(date: Date) {
        _state.update { it.copy(selectedDate = date) }
    }

    fun changeViewMode(mode: CalendarViewMode) {
        _state.update { it.copy(viewMode = mode) }
    }

    fun setStartingBalance(balance: Double) {
        _state.update { it.copy(startingBalance = balance) }
        // Reload with new balance
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
            val bills = cashFlowCalculator.getUpcomingBills(30)
            _state.update { it.copy(upcomingBillsCount = bills.size) }
        }
    }
}
