package com.yourname.expensetracker.ui.screens.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiEngagementState
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiRuntimeStatusSummary
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.ai.usecase.GetAiRuntimeStatusUseCase
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
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
    private val getAiRuntimeStatusUseCase: GetAiRuntimeStatusUseCase,
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiEngagementRepository: AiEngagementRepository,
    private val aiRuntimeDiagnostics: AiRuntimeDiagnostics,
    private val databaseBackupRepository: com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
) : ViewModel() {

    private val _aiRuntimeStatuses = MutableStateFlow<Map<AiCapability, OnDeviceModelStatus>>(emptyMap())
    val aiRuntimeStatuses: StateFlow<Map<AiCapability, OnDeviceModelStatus>> = _aiRuntimeStatuses
    private val _aiRuntimeMeta = MutableStateFlow(AiRuntimeStatusSummary(emptyList(), null))
    val aiRuntimeMeta: StateFlow<AiRuntimeStatusSummary> = _aiRuntimeMeta
    private val _aiRuntimeEvents = MutableStateFlow(emptyList<com.yourname.expensetracker.domain.debug.AiRuntimeEvent>())
    val aiRuntimeEvents: StateFlow<List<com.yourname.expensetracker.domain.debug.AiRuntimeEvent>> = _aiRuntimeEvents
    val aiEngagementState: StateFlow<AiEngagementState> = aiEngagementRepository.engagementState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiEngagementState())

    val aiSettings: StateFlow<AiSettings> = aiSettingsRepository.settings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiSettings())

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
            val summary = getAiRuntimeStatusUseCase(AiCapability.entries)
            val statuses = summary.capabilities.associate { it.capability to it.status }
            _aiRuntimeStatuses.value = statuses
            _aiRuntimeMeta.value = summary
            aiRuntimeDiagnostics.recordRuntimeRefresh(
                message = "Debug refresh: network=${summary.networkAvailable}, wifi=${summary.wifiConnected}, highest='${summary.highestPriorityMessage ?: "none"}'",
                now = _aiRuntimeMeta.value.lastRefreshedAt
            )
            _aiRuntimeEvents.value = aiRuntimeDiagnostics.getRecentEvents()
        }
    }
    
    // Database Backup Operations
    private val _databaseExportResult = MutableStateFlow<com.yourname.expensetracker.domain.backup.DatabaseExportResult?>(null)
    val databaseExportResult: StateFlow<com.yourname.expensetracker.domain.backup.DatabaseExportResult?> = _databaseExportResult
    
    private val _databaseImportResult = MutableStateFlow<com.yourname.expensetracker.domain.backup.DatabaseImportResult?>(null)
    val databaseImportResult: StateFlow<com.yourname.expensetracker.domain.backup.DatabaseImportResult?> = _databaseImportResult
    
    private val _databaseStats = MutableStateFlow<com.yourname.expensetracker.domain.backup.DatabaseStats?>(null)
    val databaseStats: StateFlow<com.yourname.expensetracker.domain.backup.DatabaseStats?> = _databaseStats
    
    fun loadDatabaseStats() {
        viewModelScope.launch {
            _databaseStats.value = databaseBackupRepository.getDatabaseStats()
        }
    }
    
    fun exportDatabase() {
        viewModelScope.launch {
            _databaseExportResult.value = com.yourname.expensetracker.domain.backup.DatabaseExportResult.Loading
            val result = databaseBackupRepository.exportDatabase()
            _databaseExportResult.value = if (result.isSuccess) {
                com.yourname.expensetracker.domain.backup.DatabaseExportResult.Success(result.getOrNull()!!.absolutePath)
            } else {
                com.yourname.expensetracker.domain.backup.DatabaseExportResult.Error(result.exceptionOrNull()?.message ?: "Unknown error")
            }
        }
    }
    
    fun importDatabase(uri: android.net.Uri, context: android.content.Context) {
        viewModelScope.launch {
            _databaseImportResult.value = com.yourname.expensetracker.domain.backup.DatabaseImportResult.Loading
            
            // Preflight validation
            val contentResolver = context.contentResolver
            
            // Check if we can open the file
            val canOpen = try {
                contentResolver.openInputStream(uri)?.use { it.read() }
                true
            } catch (e: Exception) {
                false
            }
            
            if (!canOpen) {
                _databaseImportResult.value = com.yourname.expensetracker.domain.backup.DatabaseImportResult.Error(
                    "Cannot read selected file. Please choose a valid database file."
                )
                return@launch
            }
            
            // Check file size
            val fileSize = try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
                    } else -1L
                } ?: -1L
            } catch (e: Exception) {
                -1L
            }
            
            if (fileSize == 0L) {
                _databaseImportResult.value = com.yourname.expensetracker.domain.backup.DatabaseImportResult.Error(
                    "Selected file is empty."
                )
                return@launch
            }
            
            // Create temp file from URI
            val tempFile = withContext(Dispatchers.IO) {
                try {
                    val temp = java.io.File.createTempFile("import_", ".db", context.cacheDir)
                    contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    temp
                } catch (e: Exception) {
                    null
                }
            }
            
            if (tempFile == null) {
                _databaseImportResult.value = com.yourname.expensetracker.domain.backup.DatabaseImportResult.Error(
                    "Failed to prepare file for import."
                )
                return@launch
            }
            
            // Perform import
            val result = databaseBackupRepository.importDatabase(tempFile)
            
            // Clean up temp file
            tempFile.delete()
            
            _databaseImportResult.value = if (result.isSuccess) {
                val summary = result.getOrNull()
                val needsRestart = summary?.transactionCount == -1
                if (needsRestart) {
                    com.yourname.expensetracker.domain.backup.DatabaseImportResult.SuccessNeedsRestart
                } else {
                    com.yourname.expensetracker.domain.backup.DatabaseImportResult.Success(summary!!)
                }
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                com.yourname.expensetracker.domain.backup.DatabaseImportResult.Error(
                    if (errorMsg.contains("not found", ignoreCase = true)) {
                        "Import failed: Database file not accessible."
                    } else {
                        errorMsg
                    }
                )
            }
        }
    }
    
    fun resetDatabase() {
        viewModelScope.launch {
            databaseBackupRepository.resetDatabase()
        }
    }
    
    fun clearExportResult() {
        _databaseExportResult.value = null
    }
    
    fun clearImportResult() {
        _databaseImportResult.value = null
    }
}
