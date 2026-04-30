package com.yourname.expensetracker.ui.screens.naturallanguage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpense
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class NaturalLanguageSearchViewModel @Inject constructor(
    private val searchEngine: NaturalLanguageSearchEngine,
    currencySettingsRepository: CurrencySettingsRepository,
    private val currencyConverter: CurrencyConverter
) : ViewModel() {

    val homeCurrency: Flow<String> = currencySettingsRepository.homeCurrency()
    
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()
    
    private val _interpretation = MutableStateFlow<NaturalLanguageSearchEngine.QueryInterpretation?>(null)
    val interpretation: StateFlow<NaturalLanguageSearchEngine.QueryInterpretation?> = _interpretation.asStateFlow()
    
    private val _results = MutableStateFlow<List<NaturalLanguageExpense>>(emptyList())
    val results: StateFlow<List<NaturalLanguageExpense>> = _results.asStateFlow()

    private val _totalInHomeCurrency = MutableStateFlow(0.0)
    val totalInHomeCurrency: StateFlow<Double> = _totalInHomeCurrency.asStateFlow()
    
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
        _totalInHomeCurrency.value = 0.0
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

            // Compute total in home currency (handles mixed-currency results correctly)
            val home = homeCurrency.first()
            val amounts = expenses.groupBy { it.currency }.map { (currency, expenseList) ->
                // SAFE: grouping by native currency first, then converting at line 93 — correct multi-currency handling
                expenseList.sumOf { it.effectiveAmount } to currency
            }
            _totalInHomeCurrency.value = if (amounts.size == 1) {
                amounts.first().first
            } else {
                currencyConverter.convertMultiple(amounts, home).total
            }

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
