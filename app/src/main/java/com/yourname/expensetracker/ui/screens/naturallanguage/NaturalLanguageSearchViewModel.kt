package com.yourname.expensetracker.ui.screens.naturallanguage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpense
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngine
import com.yourname.expensetracker.domain.naturallanguage.SearchResult
import com.yourname.expensetracker.domain.naturallanguage.SpeechInputGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * ViewModel for the natural language search screen.
 *
 * ## M9-M10: Known search/report display issues
 *
 * M7 (amounts shown without the original currency) and M8 (interpretation card amounts
 * without a currency qualifier) were closed in the fix104-g batch and are no longer open.
 *
 * ### M9: Category chip uses simple capitalization
 * Category names in `InterpretationCard` use a naive `capitalize()` that only
 * uppercases the first letter. Multi-word categories (e.g. "groceries" becomes
 * "Groceries", but "health & fitness" becomes "Health & fitness") may not be
 * properly capitalized for the user's locale.
 *
 * ### M10: Total conversion for mixed currencies may mislead
 * The [performSearch] method converts all expenses to home currency for the
 * total display. If conversion rates are stale or unavailable for a currency,
 * the total will be incorrect. This is noted in the conversion logic at line ~87
 * but no fallback is provided for missing conversion rates.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class NaturalLanguageSearchViewModel @Inject constructor(
    private val searchEngine: NaturalLanguageSearchEngine,
    currencySettingsRepository: CurrencySettingsRepository,
    private val currencyConverter: CurrencyConverter,
    private val speechInputGateway: SpeechInputGateway
) : ViewModel() {

    val homeCurrency: Flow<String> = currencySettingsRepository.homeCurrency()
    
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()
    
    private val _interpretation = MutableStateFlow<NaturalLanguageSearchEngine.QueryInterpretation?>(null)
    val interpretation: StateFlow<NaturalLanguageSearchEngine.QueryInterpretation?> = _interpretation.asStateFlow()
    
    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    /** Exposed for UI components that only need raw expense data without match-type labels. */
    val rawExpenses: StateFlow<List<NaturalLanguageExpense>> = _results.map { list ->
        list.map { it.expense }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _total = MutableStateFlow<SearchTotal>(SearchTotal.Unavailable)
    /** Home-currency total of the current results; never a fabricated zero when it cannot be computed. */
    val total: StateFlow<SearchTotal> = _total.asStateFlow()
    
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

    override fun onCleared() {
        super.onCleared()
        speechInputGateway.destroy()
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
        _total.value = SearchTotal.Unavailable
    }
    
    private suspend fun performSearch(queryText: String) {
        _searchState.value = SearchState.Interpreting
        
        try {
            // Interpret the natural language query
            val interpretation = searchEngine.interpretQuery(queryText)
            _interpretation.value = interpretation
            
            // Execute the search — results now include match-type labels (SRH-23)
            val searchResults = searchEngine.executeSearch(interpretation)
            _results.value = searchResults

            // Compute total in home currency using MoneyAggregateBuilder
            // (handles single-currency, single non-home, and mixed-currency results correctly)
            val home = homeCurrency.first()
            val grouped = searchResults.groupBy { it.expense.currency }.entries.toList()
            val amounts = grouped.map { (currency, expenseList) ->
                // SAFE: grouping by native currency first, then converting — correct multi-currency handling
                expenseList.sumOf { it.expense.effectiveAmount } to currency
            }
            val aggregate = MoneyAggregateBuilder.fromBuckets(
                buckets = amounts,
                homeCurrency = home,
                converter = currencyConverter,
                // Per-bucket row counts so a partial total can say how many rows it leaves out.
                transactionCounts = grouped.map { it.value.size }
            )
            // INV-12/13: a total with failed conversions is never shown as complete, and a total with
            // nothing convertible is never shown as an amount (no fabricated zero).
            val leftOut = aggregate.failedTransactionCount
            _total.value = when {
                aggregate.conversionQuality == ConversionQuality.UNAVAILABLE -> SearchTotal.Unavailable
                aggregate.isPartial && leftOut <= 0 -> SearchTotal.Unavailable
                else -> SearchTotal.Available(aggregate.displayAmount, if (aggregate.isPartial) leftOut else 0)
            }

            _searchState.value = if (searchResults.isEmpty()) {
                SearchState.Empty
            } else {
                SearchState.Results
            }
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _total.value = SearchTotal.Unavailable
            _searchState.value = SearchState.Error("SEARCH_FAILED")
        }
    }
}

/**
 * Home-currency total of the search results. [Available.leftOutCount] is the number of rows left out
 * because no rate was available (0 = complete); a positive count means the figure is understated.
 */
sealed interface SearchTotal {
    object Unavailable : SearchTotal
    data class Available(val amount: Double, val leftOutCount: Int = 0) : SearchTotal
}

sealed class SearchState {
    object Idle : SearchState()
    object Interpreting : SearchState()
    object Results : SearchState()
    object Empty : SearchState()
    data class Error(val message: String) : SearchState()
}
