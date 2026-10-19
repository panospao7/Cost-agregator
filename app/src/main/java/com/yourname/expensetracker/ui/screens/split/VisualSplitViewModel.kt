package com.yourname.expensetracker.ui.screens.split

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.yourname.expensetracker.data.database.entity.SplitShare
import com.yourname.expensetracker.data.database.entity.SplitTemplate
import com.yourname.expensetracker.domain.split.EnhancedSplitManager
import dagger.hilt.android.lifecycle.HiltViewModel
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.util.guardedWrite
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VisualSplitViewModel @Inject constructor(
    private val splitManager: EnhancedSplitManager,
    private val gson: Gson
) : ViewModel() {
    
    private val _templates = MutableStateFlow<List<SplitTemplate>>(emptyList())
    val templates: StateFlow<List<SplitTemplate>> = _templates.asStateFlow()
    
    private val _currentSplit = MutableStateFlow<EnhancedSplitManager.VisualSplitData?>(null)
    val currentSplit: StateFlow<EnhancedSplitManager.VisualSplitData?> = _currentSplit.asStateFlow()
    
    init {
        loadTemplates()
    }
    
    private fun loadTemplates() {
        viewModelScope.launch {
            splitManager.getAllTemplates().collect { templateList ->
                _templates.value = templateList
            }
        }
    }
    
    /**
     * Load a specific template by ID.
     * Returns the template if found, null otherwise.
     */
    suspend fun loadTemplate(templateId: Long): SplitTemplate? {
        return splitManager.getTemplateById(templateId)
    }
    
    fun calculateSplit(
        totalAmount: Double,
        participants: List<SplitShare>,
        splitType: SplitTemplate.SplitType
    ) {
        _currentSplit.value = splitManager.generateVisualSplitData(totalAmount, participants, splitType)
    }
    
    fun parseTemplateShares(template: SplitTemplate): List<SplitShare> {
        return splitManager.parseShares(template)
    }
    
    fun createTemplate(
        name: String,
        participants: List<SplitShare>,
        splitType: SplitTemplate.SplitType
    ) {
        viewModelScope.launch {
            guardedWrite(::emitWriteBlocked) {
                splitManager.createTemplate(
                    name = name,
                    totalSplits = participants.size,
                    splitType = splitType,
                    shares = participants
                )
            } ?: return@launch
            // Only a persisted template lets the editor close the dialog / clear the name.
            _templateCreated.tryEmit(Unit)
        }
    }

    // One-shot success event for createTemplate; never emitted when the write was blocked.
    private val _templateCreated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val templateCreated: SharedFlow<Unit> = _templateCreated.asSharedFlow()

    fun setDefaultTemplate(templateId: Long) {
        viewModelScope.launch {
            guardedWrite(::emitWriteBlocked) {
                splitManager.setDefaultTemplate(templateId)
            }
        }
    }

    fun deleteTemplate(template: SplitTemplate) {
        viewModelScope.launch {
            guardedWrite(::emitWriteBlocked) {
                splitManager.deleteTemplate(template)
            }
        }
    }

    // R18b: one-shot bounded message when a user-started write hits the maintenance write barrier.
    private val _writeBlockedMessages = MutableSharedFlow<UiText>(extraBufferCapacity = 1)
    val writeBlockedMessages: SharedFlow<UiText> = _writeBlockedMessages.asSharedFlow()

    private fun emitWriteBlocked(message: UiText) {
        _writeBlockedMessages.tryEmit(message)
    }
}
