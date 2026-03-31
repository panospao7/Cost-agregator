package com.yourname.expensetracker.ui.screens.reminder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.reminder.BillReminder
import com.yourname.expensetracker.domain.reminder.BillReminderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BillRemindersViewModel @Inject constructor(
    private val billReminderManager: BillReminderManager
) : ViewModel() {
    
    private val _reminders = MutableStateFlow<List<BillReminder>>(emptyList())
    val reminders: StateFlow<List<BillReminder>> = _reminders.asStateFlow()
    
    private val _monthlyTotal = MutableStateFlow(0.0)
    val monthlyTotal: StateFlow<Double> = _monthlyTotal.asStateFlow()
    
    init {
        loadReminders()
        calculateMonthlyTotal()
    }
    
    private fun loadReminders() {
        viewModelScope.launch {
            try {
                val upcoming = billReminderManager.getUpcomingReminders()
                _reminders.value = upcoming
            } catch (e: Exception) {
                _reminders.value = emptyList()
            }
        }
    }
    
    private fun calculateMonthlyTotal() {
        viewModelScope.launch {
            try {
                val total = billReminderManager.getMonthlyBillsTotal()
                _monthlyTotal.value = total
            } catch (e: Exception) {
                _monthlyTotal.value = 0.0
            }
        }
    }
    
    fun markBillPaid(recurringExpenseId: Long) {
        viewModelScope.launch {
            try {
                billReminderManager.markBillPaid(recurringExpenseId)
                loadReminders() // Refresh
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
    
    fun refresh() {
        loadReminders()
        calculateMonthlyTotal()
    }
}
