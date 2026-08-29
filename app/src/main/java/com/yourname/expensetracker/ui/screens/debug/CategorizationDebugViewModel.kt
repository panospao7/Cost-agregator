package com.yourname.expensetracker.ui.screens.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.categorization.CategorizationDebugTrace
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategorizationDebugViewModel @Inject constructor(
    private val categorizationEngine: CategorizationEngine,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val _debugTrace = MutableStateFlow<CategorizationDebugTrace?>(null)
    val debugTrace: StateFlow<CategorizationDebugTrace?> = _debugTrace

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    /** G-TIME-01: the screen's single TimeProvider-backed "now" source. */
    fun referenceNowMillis(): Long = timeProvider.now()

    fun testCategorization(merchant: String, amount: Double, timestamp: Long) {
        if (merchant.isBlank()) return
        
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                _debugTrace.value = categorizationEngine.debugCategorize(merchant, amount, timestamp)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun clearTrace() {
        _debugTrace.value = null
    }

    fun exportTraceToJson(trace: CategorizationDebugTrace): String {
        return try {
            val root = org.json.JSONObject()
            
            val info = org.json.JSONObject()
            info.put("inputMerchant", trace.inputMerchant)
            info.put("normalizedMerchant", trace.normalizedMerchant)
            info.put("canonicalMerchant", trace.canonicalMerchant)
            val strippedArr = org.json.JSONArray()
            trace.strippedParts.forEach { strippedArr.put(it) }
            info.put("strippedParts", strippedArr)
            root.put("preprocessing", info)
            
            val layers = org.json.JSONArray()
            trace.layerResults.forEach { layer ->
                val layerObj = org.json.JSONObject()
                layerObj.put("layerName", layer.layerName)
                layerObj.put("matchFound", layer.matchFound)
                layerObj.put("categoryId", layer.categoryId)
                layerObj.put("categoryName", layer.categoryName)
                layerObj.put("confidence", layer.confidence)
                layerObj.put("details", layer.details)
                layers.put(layerObj)
            }
            root.put("layers", layers)
            
            val finalRes = org.json.JSONObject()
            finalRes.put("matchType", trace.finalResult.matchType.name)
            finalRes.put("categoryId", trace.finalResult.categoryId)
            finalRes.put("categoryName", trace.finalResult.categoryName)
            finalRes.put("confidence", trace.finalResult.confidence)
            finalRes.put("explanation", trace.finalResult.explanation)
            root.put("finalDecision", finalRes)
            
            root.toString(2)
        } catch (e: Exception) {
            "Error exporting to JSON: ${e.message}"
        }
    }
}
