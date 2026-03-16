package com.yourname.expensetracker.ui.screens.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRuntimeStatusSummary
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val reviewQueueRepository: com.yourname.expensetracker.data.repository.ReviewQueueRepository,
    private val expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
    private val budgetRepository: com.yourname.expensetracker.data.repository.BudgetRepository,
    private val categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository,
    private val notificationSeeder: com.yourname.expensetracker.domain.debug.NotificationSeeder,
    private val timeProvider: TimeProvider,
    private val diagnostics: com.yourname.expensetracker.domain.debug.ServiceDiagnostics,
    private val aiEnvironmentMonitor: AiEnvironmentMonitor
) : ViewModel() {

    private val _aiRuntimeStatuses = MutableStateFlow<Map<AiCapability, OnDeviceModelStatus>>(emptyMap())
    val aiRuntimeStatuses: StateFlow<Map<AiCapability, OnDeviceModelStatus>> = _aiRuntimeStatuses
    private val _aiRuntimeMeta = MutableStateFlow(AiRuntimeStatusSummary(emptyList(), null))
    val aiRuntimeMeta: StateFlow<AiRuntimeStatusSummary> = _aiRuntimeMeta

    init {
        refreshAiRuntimeStatuses()
    }
    
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
        
    val totalSpent: StateFlow<Double> = expenseRepository
        .getTotalSpent()
        .map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val sourceStats: StateFlow<List<com.yourname.expensetracker.data.database.entity.SourceStats>> = repository
        .getSourceStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val classifierStats: StateFlow<com.yourname.expensetracker.domain.intelligence.ClassifierStats> = repository
        .getClassifierStatsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.yourname.expensetracker.domain.intelligence.ClassifierStats(0, 0, 0, false))

    fun getServiceDiagnostics(): com.yourname.expensetracker.domain.debug.ServiceDiagnostics.Stats {
        return diagnostics.getStats()
    }

    fun resetServiceDiagnostics() {
        diagnostics.resetStats()
    }
    
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
            expenseRepository.deleteAllExpenses()
        }
    }

    fun resetBudgets() {
        viewModelScope.launch {
            budgetRepository.deleteAll()
        }
    }
    
    fun markAsRelevant(id: Long, isRelevant: Boolean) {
        viewModelScope.launch {
            reviewQueueRepository.markAsRelevant(id, isRelevant)
        }
    }
    
    fun blockPackage(packageName: String) {
        viewModelScope.launch {
            repository.blockPackage(packageName)
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

    fun resetSourceStats() {
        viewModelScope.launch {
            repository.resetSourceStats()
        }
    }

    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating

    fun simulateMassData(count: Int) {
        viewModelScope.launch {
            _isSimulating.value = true
            
            withContext(Dispatchers.IO) {
                categoryRepository.ensureDefaultCategories()
                
                val cats = categoryRepository.allCategories.first()
                val catMap = cats.associate { it.name to it.id }
                
                notificationSeeder.categories.forEach { (catName, merchants) ->
                    val catId = catMap[catName]
                    if (catId != null) {
                        merchants.forEach { merchant ->
                            try {
                                categoryRepository.learnMerchantCategory(merchant, catId)
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
                
                val simulated = notificationSeeder.generate(count)
                repository.processAndSaveAll(simulated)
            }
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
                timestamp = timeProvider.now(),
                capturedAt = timeProvider.now()
            )
            repository.processAndSave(fakeNotification)
        }
    }

    fun simulateDepositNotification() {
        viewModelScope.launch {
            val depositTemplates = listOf(
                Triple("com.revolut", "Revolut", "deposit €500.00 from EMPLOYER"),
                Triple("gr.nbg.mobilebanking", "NBG", "Κατάθεση €500,00 από EMPLOYER"),
                Triple("com.eurobank.mobile", "Eurobank", "Πίστωση €500,00 μισθός"),
                Triple("com.revolut", "Revolut", "received €750.00 salary"),
                Triple("com.revolut", "Revolut", "€1000 credited from TRANSFER")
            )
            val template = depositTemplates.random()
            
            val fakeNotification = RawNotification(
                packageName = template.first,
                appName = template.second,
                title = "Deposit Received",
                text = template.third,
                timestamp = timeProvider.now(),
                capturedAt = timeProvider.now()
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

    fun refreshAiRuntimeStatuses() {
        viewModelScope.launch {
            val statuses = buildMap {
                AiCapability.entries.forEach { capability ->
                    put(capability, aiEnvironmentMonitor.getOnDeviceModelStatus(capability))
                }
            }
            _aiRuntimeStatuses.value = statuses
            _aiRuntimeMeta.value = AiRuntimeStatusSummary(
                capabilities = emptyList(),
                highestPriorityMessage = null,
                networkAvailable = aiEnvironmentMonitor.isNetworkAvailable(),
                wifiConnected = aiEnvironmentMonitor.isWifiConnected(),
                lastRefreshedAt = timeProvider.now()
            )
        }
    }
}
