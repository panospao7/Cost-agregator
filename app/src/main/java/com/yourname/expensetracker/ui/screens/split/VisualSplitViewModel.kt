package com.yourname.expensetracker.ui.screens.split

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.yourname.expensetracker.data.database.entity.SplitShare
import com.yourname.expensetracker.data.database.entity.SplitTemplate
import com.yourname.expensetracker.domain.split.EnhancedSplitManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
            splitManager.createTemplate(
                name = name,
                totalSplits = participants.size,
                splitType = splitType,
                shares = participants
            )
        }
    }
    
    fun setDefaultTemplate(templateId: Long) {
        viewModelScope.launch {
            splitManager.setDefaultTemplate(templateId)
        }
    }
    
    fun deleteTemplate(template: SplitTemplate) {
        viewModelScope.launch {
            splitManager.deleteTemplate(template)
        }
    }
}
