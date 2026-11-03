package com.yourname.expensetracker.ui.screens.naturallanguage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.NormalizedMoneyQuality
import com.yourname.expensetracker.domain.core.money.NormalizedMoneyRow
import com.yourname.expensetracker.domain.core.money.RateBasis
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
 * ## M7-M10: Known search/report display issues
 *
 * ### M7: Amounts displayed without original currency context
 * When results include expenses in multiple currencies, the `TransactionResultCard`
 * in the screen always formats amounts using `homeCurrency` from settings.
 * This means a JPY-denominated expense would be shown as "€50" instead of "¥50"
 * if the home currency is EUR. A proper fix should display the expense's native
 * currency alongside or instead of the home-currency equivalent.
 *
 * ### M8: Interpretation card shows amounts without currency
 * The `InterpretationCard` displays extracted amounts (e.g. "over 50") without
 * any currency qualifier. If the user asked "over €50" vs "over $50", the
 * distinction is lost. The chip should include the detected currency symbol.
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
        _totalInHomeCurrency.value = 0.0
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

            // Compute total in home currency from already-normalized searchResults
            // (preserves the engine's convertAsOf(date) basis and dataQuality)
            val home = homeCurrency.first()
            val targetCurrency = CurrencyCode.parse(home) ?: CurrencyCode.EUR
            val normalizedRows = searchResults.map { result ->
                NormalizedMoneyRow(
                    amount = result.normalizedAmount,
                    normalizedCurrency = targetCurrency,
                    sourceCurrency = CurrencyCode.parse(result.expense.currency),
                    rateValidDate = result.expense.date,
                    transactionId = result.expense.id
                )
            }
            val failedCount = interpretation.dataQuality.failedCurrencyConversions
            val quality = NormalizedMoneyQuality(
                excludedTransactionCount = failedCount,
                missingRateCount = failedCount,
                conversionQuality = when {
                    failedCount == 0 -> ConversionQuality.COMPLETE
                    normalizedRows.isEmpty() -> ConversionQuality.UNAVAILABLE
                    else -> ConversionQuality.PARTIAL
                }
            )
            val aggregateResult = MoneyAggregateBuilder.fromNormalizedRows(
                rows = normalizedRows,
                targetCurrency = targetCurrency,
                quality = quality,
                requestedRateBasis = RateBasis.TRANSACTION_DATE,
                actualRateBasis = if (normalizedRows.isNotEmpty() && normalizedRows.all { it.sourceCurrency == targetCurrency }) {
                    RateBasis.IDENTITY
                } else {
                    RateBasis.TRANSACTION_DATE
                }
            )
            _totalInHomeCurrency.value = when (aggregateResult) {
                is MoneyAggregateResult.Available -> aggregateResult.aggregate.displayAmount
                is MoneyAggregateResult.Unavailable -> 0.0
            }

            _searchState.value = if (searchResults.isEmpty()) {
                SearchState.Empty
            } else {
                SearchState.Results
            }
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _searchState.value = SearchState.Error("SEARCH_FAILED")
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
