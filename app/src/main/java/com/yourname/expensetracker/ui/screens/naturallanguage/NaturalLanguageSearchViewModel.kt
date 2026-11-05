package com.yourname.expensetracker.ui.screens.naturallanguage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpense
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngine
import com.yourname.expensetracker.domain.naturallanguage.SearchResult
import com.yourname.expensetracker.domain.naturallanguage.SpeechInputGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        // Replaces distinctUntilChanged: the distinct key is cleared by cancelSearch(), so
        // "abc" -> "ab" -> "abc" inside the debounce window searches again after the clear.
        _query
            .debounce(300)
            .filter { it.length >= 3 }
            .onEach { queryText ->
                if (queryText != lastSearchedQuery) startSearch(queryText)
            }
            .launchIn(viewModelScope)
    }

    /** The single in-flight search; a newer query, a clear or a retry cancels it. */
    private var searchJob: Job? = null
    private var lastSearchedQuery: String? = null

    private fun startSearch(queryText: String) {
        searchJob?.cancel()
        lastSearchedQuery = queryText
        searchJob = viewModelScope.launch { performSearch(queryText) }
    }

    private fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        lastSearchedQuery = null
    }

    override fun onCleared() {
        super.onCleared()
        speechInputGateway.destroy()
    }
    
    fun updateQuery(newQuery: String) {
        _query.value = newQuery
        if (newQuery.length < 3) {
            // Below the search threshold no search runs, so drop any stale results/error.
            clearResults()
        }
    }

    /** Re-runs the current query (identical text is otherwise swallowed by distinctUntilChanged). */
    fun retrySearch() {
        val current = _query.value
        if (current.length < 3) return
        startSearch(current)
    }
    
    fun clearQuery() {
        _query.value = ""
        clearResults()
    }
    
    private fun clearResults() {
        cancelSearch()
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

            _total.value = computeTotal(searchResults, homeCurrency.first())

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

    /**
     * Home-currency total of the rows that count as spending ([NaturalLanguageExpense.countsInTotal]).
     * Deposits, transfers and flagged (spending-neutralised) rows are shown in the results but add
     * nothing here and are never counted as left out.
     *
     * Each counted row is converted at its own transaction date ([CurrencyConverter.convertAsOf], the
     * same basis the search engine uses for amount filtering). A row that cannot be converted is left
     * out and counted in [SearchTotal.Available.leftOutCount]; a raw foreign amount is never added.
     * Rounding: none is applied here; the converted amounts are summed unrounded and the display
     * formatter rounds. No counted rows is a true zero; counted rows with none convertible is
     * [SearchTotal.Unavailable] (INV-12/13: never a fabricated zero).
     */
    private suspend fun computeTotal(searchResults: List<SearchResult>, home: String): SearchTotal {
        val counted = searchResults.map { it.expense }.filter { it.countsInTotal }
        if (counted.isEmpty()) return SearchTotal.Available(0.0, 0)
        if (CurrencyCode.parse(home)?.takeIf { it in CurrencyCode.ALL_SUPPORTED } == null) {
            return SearchTotal.Unavailable
        }

        val homeAmounts = ArrayList<Double>(counted.size)
        var leftOut = 0
        for (expense in counted) {
            val amount = expense.effectiveAmount
            val converted: Double? = when {
                !amount.isFinite() -> null
                expense.currency.equals(home, ignoreCase = true) -> amount
                else -> currencyConverter.convertAsOf(
                    amount = amount,
                    fromCurrency = expense.currency,
                    toCurrency = home,
                    atMillis = expense.date
                )?.convertedAmount
            }
            if (converted != null && converted.isFinite()) homeAmounts += converted else leftOut++
        }
        if (homeAmounts.isEmpty()) return SearchTotal.Unavailable

        val aggregate = MoneyAggregateBuilder.fromHomeCurrencyAmounts(homeAmounts, home)
        return SearchTotal.Available(aggregate.displayAmount, leftOut)
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
