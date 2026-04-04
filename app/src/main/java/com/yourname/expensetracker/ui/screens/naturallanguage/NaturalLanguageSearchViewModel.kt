package com.yourname.expensetracker.ui.screens.naturallanguage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpense
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class NaturalLanguageSearchViewModel @Inject constructor(
    private val searchEngine: NaturalLanguageSearchEngine
) : ViewModel() {
    
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()
    
    private val _interpretation = MutableStateFlow<NaturalLanguageSearchEngine.QueryInterpretation?>(null)
    val interpretation: StateFlow<NaturalLanguageSearchEngine.QueryInterpretation?> = _interpretation.asStateFlow()
    
    private val _results = MutableStateFlow<List<NaturalLanguageExpense>>(emptyList())
    val results: StateFlow<List<NaturalLanguageExpense>> = _results.asStateFlow()
    
    init {
        // Debounce search queries
        _query
            .debounce(300)
            .filter { it.length >= 3 }
            .distinctUntilChanged()
            .onEach { queryText ->
                performSearch(queryText)
            }
            .launchIn(viewModelScope)
    }
    
    fun updateQuery(newQuery: String) {
        _query.value = newQuery
        if (newQuery.isEmpty()) {
            clearResults()
        }
    }
    
    fun clearQuery() {
        _query.value = ""
        clearResults()
    }
    
    private fun clearResults() {
        _searchState.value = SearchState.Idle
        _interpretation.value = null
        _results.value = emptyList()
    }
    
    private suspend fun performSearch(queryText: String) {
        _searchState.value = SearchState.Interpreting
        
        try {
            // Interpret the natural language query
            val interpretation = searchEngine.interpretQuery(queryText)
            _interpretation.value = interpretation
            
            // Execute the search
            val expenses = searchEngine.executeSearch(interpretation)
            _results.value = expenses
            
            _searchState.value = if (expenses.isEmpty()) {
                SearchState.Empty
            } else {
                SearchState.Results
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            _searchState.value = SearchState.Error(e.message ?: "Unknown error")
        }
    }
    
    fun executeVoiceQuery(voiceText: String) {
        _query.value = voiceText
        viewModelScope.launch {
            performSearch(voiceText)
        }
    }
}

sealed class SearchState {
    object Idle : SearchState()
    object Interpreting : SearchState()
    object Results : SearchState()
    object Empty : SearchState()
    data class Error(val message: String) : SearchState()
}
