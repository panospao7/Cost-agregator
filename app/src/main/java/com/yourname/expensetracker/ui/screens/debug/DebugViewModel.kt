package com.yourname.expensetracker.ui.screens.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val budgetRepository: com.yourname.expensetracker.data.repository.BudgetRepository
) : ViewModel() {
    
    val notifications: StateFlow<List<RawNotification>> = repository
        .getRecentNotifications(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    val notificationCount: StateFlow<Int> = repository
        .getCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    
    val packages: StateFlow<List<String>> = repository
        .getAllPackages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blockedPackages: StateFlow<List<com.yourname.expensetracker.data.database.entity.BlockedPackage>> = repository
        .getBlockedPackages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    val totalSpent: StateFlow<Double> = repository
        .getTotalSpent()
        .map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val sourceStats: StateFlow<List<com.yourname.expensetracker.data.database.entity.SourceStats>> = repository
        .getSourceStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val classifierStats: StateFlow<com.yourname.expensetracker.domain.intelligence.ClassifierStats> = repository
        .getClassifierStatsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), repository.getClassifierStats())
    
    private val _selectedPackageFilter = MutableStateFlow<String?>(null)
    val selectedPackageFilter: StateFlow<String?> = _selectedPackageFilter
    
    val filteredNotifications: StateFlow<List<RawNotification>> = combine(
        notifications,
        _selectedPackageFilter
    ) { notifs, filter ->
        if (filter == null) notifs
        else notifs.filter { it.packageName == filter }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    fun setPackageFilter(packageName: String?) {
        _selectedPackageFilter.value = packageName
    }
    
    fun clearAll() {
        viewModelScope.launch {
            repository.deleteAll()
        }
    }

    fun resetExpenses() {
        viewModelScope.launch {
            repository.deleteAllExpenses()
        }
    }

    fun resetBudgets() {
        viewModelScope.launch {
            budgetRepository.deleteAll()
        }
    }
    
    fun markAsRelevant(id: Long, isRelevant: Boolean) {
        viewModelScope.launch {
            repository.markAsRelevant(id, isRelevant)
        }
    }
    
    fun blockPackage(packageName: String) {
        viewModelScope.launch {
            repository.blockPackage(packageName)
            // Also refresh filter if needed, but Flow should handle it
        }
    }
    
    fun unblockPackage(packageName: String) {
        viewModelScope.launch {
            repository.unblockPackage(packageName)
        }
    }

    fun retrainClassifier() {
        viewModelScope.launch {
            repository.retrainClassifier()
        }
    }
    
    @Inject
    lateinit var seeder: com.yourname.expensetracker.domain.debug.NotificationSeeder

    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating

    fun simulateMassData(count: Int) {
        viewModelScope.launch {
            _isSimulating.value = true
            val simulated = seeder.generate(count)
            repository.processAndSaveAll(simulated)
            _isSimulating.value = false
        }
    }
    
    fun simulateTestNotification() {
        viewModelScope.launch {
            val fakeNotification = RawNotification(
                packageName = "com.test.bank",
                appName = "Test Bank",
                title = "Purchase Alert",
                text = "You paid €12.50 at Amazon",
                timestamp = System.currentTimeMillis(),
                capturedAt = System.currentTimeMillis()
            )
            repository.processAndSave(fakeNotification)
        }
    }

    fun triggerManualSync(context: android.content.Context) {
        val intent = android.content.Intent(context, com.yourname.expensetracker.service.NotificationCaptureService::class.java).apply {
            action = com.yourname.expensetracker.service.NotificationCaptureService.ACTION_REFRESH_NOTIFICATIONS
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
