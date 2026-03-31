package com.yourname.expensetracker.ui.screens.cashflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator
import com.yourname.expensetracker.domain.cashflow.DailyCashFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

data class CashFlowCalendarState(
    val dailyCashFlows: List<DailyCashFlow> = emptyList(),
    val isLoading: Boolean = false,
    val currentMonth: Date = Date(),
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
    private val cashFlowCalculator: CashFlowCalculator
) : ViewModel() {

    private val _state = MutableStateFlow(CashFlowCalendarState())
    val state: StateFlow<CashFlowCalendarState> = _state.asStateFlow()

    init {
        loadCurrentMonth()
        loadUpcomingBills()
    }

    fun loadCurrentMonth() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val startDate = calendar.time
        
        calendar.add(Calendar.MONTH, 1)
        calendar.add(Calendar.DAY_OF_MONTH, -1)
        val endDate = calendar.time
        
        loadCashFlow(startDate, endDate)
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
        val calendar = Calendar.getInstance()
        calendar.time = _state.value.currentMonth
        calendar.add(Calendar.MONTH, -1)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val startDate = calendar.time
        
        calendar.add(Calendar.MONTH, 1)
        calendar.add(Calendar.DAY_OF_MONTH, -1)
        val endDate = calendar.time
        
        loadCashFlow(startDate, endDate)
    }

    fun navigateToNextMonth() {
        val calendar = Calendar.getInstance()
        calendar.time = _state.value.currentMonth
        calendar.add(Calendar.MONTH, 1)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val startDate = calendar.time
        
        calendar.add(Calendar.MONTH, 1)
        calendar.add(Calendar.DAY_OF_MONTH, -1)
        val endDate = calendar.time
        
        loadCashFlow(startDate, endDate)
    }

    private fun loadUpcomingBills() {
        viewModelScope.launch {
            val bills = cashFlowCalculator.getUpcomingBills(30)
            _state.update { it.copy(upcomingBillsCount = bills.size) }
        }
    }
}
