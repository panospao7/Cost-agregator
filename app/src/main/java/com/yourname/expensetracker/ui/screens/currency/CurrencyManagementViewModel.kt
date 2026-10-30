package com.yourname.expensetracker.ui.screens.currency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import com.yourname.expensetracker.data.repository.CurrencyDataRepository
import com.yourname.expensetracker.data.repository.ExpenseCountFilter
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencyRateRefreshOutcome
import com.yourname.expensetracker.domain.currency.CurrencyRatesRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.ReadHomeCurrencyState
import com.yourname.expensetracker.domain.currency.ReadHomeFailure
import com.yourname.expensetracker.domain.currency.SupportedCurrency
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.core.money.StaleRatePolicy
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Bounded reasons for [HomeSwitchUi.Error]. */
enum class HomeSwitchError { WRITE_FAILED, UNSUPPORTED_HOME_CURRENCY }

/**
 * Home-currency switch flow (MC 1a-4). The only path that writes the home currency from this
 * screen: [CurrencyManagementViewModel.request] -> [PendingConfirm] -> [CurrencyManagementViewModel.confirm].
 */
sealed interface HomeSwitchUi {
    data object Idle : HomeSwitchUi

    /**
     * Awaiting the user's confirmation. [predictedCount] is the number of existing transactions
     * that will have no comparable value in [target] (rows without a home value, no filters), or
     * null when the count is not yet known or could not be read (omitted, never shown as zero).
     * The dialog opens immediately with a null count; the count is filled in when it arrives.
     */
    data class PendingConfirm(val target: CurrencyCode, val predictedCount: Int?) : HomeSwitchUi

    data object Writing : HomeSwitchUi

    data class Error(val reason: HomeSwitchError) : HomeSwitchUi
}

/**
 * UI state for currency management screen.
 */
data class CurrencyManagementUiState(
    /**
     * Typed read-time home currency. Starts as [ReadHomeCurrencyState.Loading]; no default
     * currency is ever assumed while it is Loading or Unavailable.
     */
    val homeState: ReadHomeCurrencyState = ReadHomeCurrencyState.Loading,
    val homeSwitch: HomeSwitchUi = HomeSwitchUi.Idle,
    /** Static active catalog; built up front, independent of the home currency state. */
    val supportedCurrencies: List<CurrencyInfo> = emptyList(),
    val exchangeRates: List<ExchangeRateInfo> = emptyList(),
    /** True once a rates load has completed successfully at least once. */
    val ratesLoaded: Boolean = false,
    val lastUpdated: Long? = null,
    val isRatesStale: Boolean = false,
    val isOffline: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    /**
     * True when the exchange-rate download privacy switch is off (or its state is unknown/corrupted).
     * Fail-closed placeholder until the privacy settings are read.
     */
    val rateDownloadDisabled: Boolean = true,
    val conversionResult: ConversionResult? = null
) {
    /** The resolved home currency code, or null while [homeState] is Loading or Unavailable. */
    val homeCurrency: String?
        get() = (homeState as? ReadHomeCurrencyState.Available)?.code?.code
}

data class CurrencyInfo(
    val code: String,
    val name: String,
    val symbol: String,
    val flag: String
)

data class ExchangeRateInfo(
    val fromCurrency: String,
    val toCurrency: String,
    val rate: Double,
    val lastUpdated: Long
)

/**
 * F2: keeps one row per [ExchangeRate.fromCurrency], the one with the newest
 * [ExchangeRate.validDate] (ties: larger [ExchangeRate.lastUpdated]). Currencies keep the order of
 * their first appearance in [rows]. A multi-day history would otherwise list one card per day.
 */
internal fun latestPerSource(rows: List<ExchangeRate>): List<ExchangeRate> {
    val newest = LinkedHashMap<String, ExchangeRate>()
    for (row in rows) {
        val current = newest[row.fromCurrency]
        val isNewer = current == null ||
            row.validDate > current.validDate ||
            (row.validDate == current.validDate && row.lastUpdated > current.lastUpdated)
        if (isNewer) newest[row.fromCurrency] = row
    }
    return newest.values.toList()
}

@HiltViewModel
class CurrencyManagementViewModel @Inject constructor(
    private val currencyDataRepository: CurrencyDataRepository,
    private val currencyConverter: CurrencyConverter,
    private val currencyRatesRepository: CurrencyRatesRepository,
    private val settingsRepository: CurrencySettingsRepository,
    private val hybridClassifier: HybridExpenseClassifier,
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val expenseRepository: ExpenseRepository
) : ViewModel() {

    private companion object {
        const val ERROR_RATE_DOWNLOAD_DISABLED = "CURRENCY_RATE_DOWNLOAD_DISABLED"
        const val ERROR_RATE_REFRESH_UNAVAILABLE = "CURRENCY_RATE_REFRESH_UNAVAILABLE"
        const val ERROR_UNSUPPORTED_HOME_CURRENCY = "UNSUPPORTED_HOME_CURRENCY"
        const val ERROR_HOME_SWITCH_WRITE_FAILED = "HOME_SWITCH_WRITE_FAILED"

        /** No typed emission within this window -> Unavailable(TIMEOUT) (consumer-applied). */
        const val HOME_READ_TIMEOUT_MS = 5_000L
    }

    // The currency catalog is static, so the picker is usable while the home currency is still
    // Loading or Unavailable (the switch warning is shown in those states too).
    private val _uiState = MutableStateFlow(CurrencyManagementUiState(supportedCurrencies = buildCurrencyCatalog()))
    val uiState: StateFlow<CurrencyManagementUiState> = _uiState.asStateFlow()

    /** In-flight predicted-count lookup for the pending home switch. */
    private var requestJob: Job? = null

    init {
        viewModelScope.launch {
            // Typed read-time home currency (MC 1a-4): Loading first, never a placeholder code.
            val readTimeout = launch {
                delay(HOME_READ_TIMEOUT_MS)
                _uiState.update { state ->
                    if (state.homeState is ReadHomeCurrencyState.Loading) {
                        state.copy(homeState = ReadHomeCurrencyState.Unavailable(ReadHomeFailure.TIMEOUT))
                    } else {
                        state
                    }
                }
            }
            settingsRepository.observeReadHomeCurrency()
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(ReadHomeCurrencyState.Unavailable(ReadHomeFailure.READ_ERROR))
                }
                .collect { state ->
                    readTimeout.cancel()
                    applyHomeState(state)
                }
        }
        viewModelScope.launch {
            // Mirror the exchange-rate download privacy switch. Corrupted/fail-closed load state
            // maps to fail-closed defaults (switch off), so the flag then reads as disabled.
            privacySettingsRepository.observeSettings()
                .map { settings -> !settings.exchangeRateDownloadEnabled }
                .distinctUntilChanged()
                .collect { disabled ->
                    _uiState.update { state ->
                        state.copy(
                            rateDownloadDisabled = disabled,
                            // Switch off is never "offline". Switch on re-derives offline only once
                            // a load has completed.
                            isOffline = !disabled &&
                                state.ratesLoaded &&
                                !hasUsableRates(state.exchangeRates)
                        )
                    }
                }
        }
    }

    /**
     * Publishes the typed home state. When it becomes Available the data load starts (the
     * loading flag is raised in the same update). A pending switch confirmation is dropped only
     * when the new state is Available and already equals the target; a home that becomes
     * Unavailable keeps the dialog open (the warning is shown while the home is unknown).
     */
    private fun applyHomeState(state: ReadHomeCurrencyState) {
        _uiState.update { current ->
            val pending = current.homeSwitch as? HomeSwitchUi.PendingConfirm
            val stalePending = pending != null &&
                state is ReadHomeCurrencyState.Available && state.code == pending.target
            current.copy(
                homeState = state,
                isLoading = if (state is ReadHomeCurrencyState.Available) true else current.isLoading,
                homeSwitch = if (stalePending) HomeSwitchUi.Idle else current.homeSwitch
            )
        }
        if (state is ReadHomeCurrencyState.Available) {
            loadCurrencyData()
        }
    }

    private fun loadCurrencyData() {
        viewModelScope.launch {
            // Rates are always read against the resolved home currency, never a placeholder.
            val homeCurrency = _uiState.value.homeCurrency ?: return@launch
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                // Load exchange rates (the currency catalog is static and already in the state)
                val rates = latestPerSource(currencyDataRepository.getRatesToCurrency(homeCurrency).first())
                    .map { rate ->
                        ExchangeRateInfo(
                            fromCurrency = rate.fromCurrency,
                            toCurrency = rate.toCurrency,
                            rate = rate.rate,
                            lastUpdated = rate.lastUpdated
                        )
                    }
                
                // Get the most recent update time
                val lastUpdated = rates.maxByOrNull { it.lastUpdated }?.lastUpdated
                    ?: settingsRepository.lastRateUpdate().first()
                
                // Check if rates are stale (older than 24 hours)
                val isRatesStale = settingsRepository.areRatesStale()

                // Explicit semantics:
                // - isRatesStale: rates are older than freshness threshold
                // - isOffline: there are no usable rates for conversions AND downloads are permitted
                //   (a privacy-switch-off state is reported via rateDownloadDisabled, never as offline)
                // The settings collector in init is the only writer of rateDownloadDisabled; read it here.
                val isOffline = !_uiState.value.rateDownloadDisabled && !hasUsableRates(rates)

                _uiState.value = _uiState.value.copy(
                    exchangeRates = rates,
                    ratesLoaded = true,
                    lastUpdated = lastUpdated,
                    isRatesStale = isRatesStale,
                    isOffline = isOffline,
                    isLoading = false,
                    error = null
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val currentRates = _uiState.value.exchangeRates
                val isRatesStale = try {
                    settingsRepository.areRatesStale()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (ignored: Exception) {
                    _uiState.value.isRatesStale
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "CURRENCY_DATA_UNAVAILABLE",
                    isRatesStale = isRatesStale,
                    isOffline = !_uiState.value.rateDownloadDisabled && !hasUsableRates(currentRates)
                )
            }
        }
    }

    /**
     * Ask to switch the home currency (MC 1a-4). Never writes: a switch is only written by
     * [confirm] after the user has seen the warning ([HomeSwitchUi.PendingConfirm]).
     *
     * - Code not in the active catalog: the existing UNSUPPORTED_HOME_CURRENCY error (no dialog,
     *   no write), whatever the home state.
     * - Code equal to the home currency, only when the home is Available: nothing happens (no
     *   dialog, no write).
     * - Otherwise, including while the home is Loading or Unavailable (INV-12, AE-14, G2-6: the
     *   warning is always shown while the home is unknown): [HomeSwitchUi.PendingConfirm] opens
     *   immediately with a null count, then is updated with the number of existing transactions
     *   that would have no comparable value in the target. A slow, failed or absent count never
     *   blocks the dialog.
     */
    fun request(currencyCode: String) {
        val current = _uiState.value
        if (current.homeSwitch is HomeSwitchUi.Writing) return
        val home = (current.homeState as? ReadHomeCurrencyState.Available)?.code

        val selectedCurrency = SupportedCurrency.fromActiveCode(currencyCode)
        if (selectedCurrency == null) {
            requestJob?.cancel()
            _uiState.update { state ->
                if (state.homeSwitch is HomeSwitchUi.Writing) {
                    state
                } else {
                    state.copy(
                        isLoading = false,
                        error = ERROR_UNSUPPORTED_HOME_CURRENCY,
                        homeSwitch = HomeSwitchUi.Error(HomeSwitchError.UNSUPPORTED_HOME_CURRENCY)
                    )
                }
            }
            return
        }

        val target = CurrencyCode(selectedCurrency.code)
        if (home != null && target == home) return

        requestJob?.cancel()
        // Open the dialog at once with static text; the count line arrives later (or never).
        _uiState.update { state ->
            val stillHome = (state.homeState as? ReadHomeCurrencyState.Available)?.code
            if (state.homeSwitch is HomeSwitchUi.Writing || stillHome == target) {
                state
            } else {
                state.copy(homeSwitch = HomeSwitchUi.PendingConfirm(target, null))
            }
        }
        requestJob = viewModelScope.launch {
            val predictedCount = predictedCountWithoutHomeValue(target)
            _uiState.update { state ->
                val pending = state.homeSwitch as? HomeSwitchUi.PendingConfirm
                if (pending != null && pending.target == target) {
                    state.copy(homeSwitch = HomeSwitchUi.PendingConfirm(target, predictedCount))
                } else {
                    state
                }
            }
        }
    }

    /** Dismiss the home-switch dialog or error. Ignored while the write is in progress. */
    fun cancel() {
        requestJob?.cancel()
        _uiState.update { state ->
            if (state.homeSwitch is HomeSwitchUi.Writing) state else state.copy(homeSwitch = HomeSwitchUi.Idle)
        }
    }

    /**
     * Confirm the pending switch: exactly one [CurrencySettingsRepository.setHomeCurrency] call.
     * Ignored unless a switch is pending, so a second confirm while [HomeSwitchUi.Writing] does
     * nothing. This is the only home-currency write on this screen.
     *
     * ## CURR-6: Trigger re-normalization after home currency change
     * After saving the new home currency, the following re-normalization steps
     * are performed:
     *
     * 1. Invalidate the hybrid classifier's category snapshot so cached amounts
     *    are refreshed.
     * 2. (Future) Clear currency converter rate cache — no-op for now because
     *    [CurrencyConverter] does not maintain a mutable rate cache that needs
     *    invalidation.
     * 3. Signal the UI to refresh via the existing [loadCurrencyData] flow
     *    (triggered by the settings flow collector in [init]).
     *
     * Full amount re-normalization (converting stored expense amounts, budget
     * limits, and recurring obligations to the new home currency) is tracked as
     * a future step. Until then, existing amounts remain in their original
     * currency and only newly entered transactions will use the new setting.
     */
    fun confirm() {
        var target: CurrencyCode? = null
        _uiState.update { state ->
            val pending = state.homeSwitch as? HomeSwitchUi.PendingConfirm
            target = pending?.target
            if (pending == null) state else state.copy(homeSwitch = HomeSwitchUi.Writing)
        }
        val switchTo = target ?: return
        requestJob?.cancel()

        viewModelScope.launch {
            try {
                // Persist to settings
                settingsRepository.setHomeCurrency(switchTo.code)
            } catch (e: CancellationException) {
                _uiState.update { state ->
                    if (state.homeSwitch is HomeSwitchUi.Writing) state.copy(homeSwitch = HomeSwitchUi.Idle) else state
                }
                throw e
            } catch (e: Exception) {
                Timber.w("CurrencyManagement: HOME_SWITCH_WRITE_FAILED")
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        error = ERROR_HOME_SWITCH_WRITE_FAILED,
                        homeSwitch = HomeSwitchUi.Error(HomeSwitchError.WRITE_FAILED)
                    )
                }
                return@launch
            }

            // CURR-6: Invalidate caches so re-classification uses fresh data
            hybridClassifier.invalidateCategorySnapshot()
            // CurrencyConverter does not have a clearRateCache() method;
            // full amount re-normalization is a future step.

            // State will be updated by the flow collector in init. If it has already
            // delivered the new home currency, the reload is under way: do not raise
            // the loading flag again.
            // When the home is not Available (Loading/Unavailable) no reload is under way yet:
            // applyHomeState raises the flag itself once Available arrives.
            _uiState.update { state ->
                val homeAvailable = state.homeState is ReadHomeCurrencyState.Available
                val homeAlreadyDelivered =
                    (state.homeState as? ReadHomeCurrencyState.Available)?.code == switchTo
                state.copy(
                    isLoading = state.isLoading || (homeAvailable && !homeAlreadyDelivered),
                    homeSwitch = HomeSwitchUi.Idle
                )
            }
        }
    }

    /**
     * Number of existing transactions (no filters) with no comparable value in [target], or null
     * when it cannot be read. Only a bounded reason code is logged; no exception text.
     */
    private suspend fun predictedCountWithoutHomeValue(target: CurrencyCode): Int? {
        return try {
            expenseRepository.countExpensesWithoutHomeValue(ExpenseCountFilter(), target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("CurrencyManagement: HOME_SWITCH_COUNT_FAILED")
            null
        }
    }

    /**
     * Convert an amount between currencies.
     */
    fun convert(amount: Double, fromCurrency: String, toCurrency: String) {
        viewModelScope.launch {
            try {
                when (val outcome = currencyConverter.convertOutcome(
                    amount = amount,
                    fromCurrency = fromCurrency,
                    toCurrency = toCurrency,
                    rateBasis = RateBasis.LATEST_AVAILABLE,
                    stalePolicy = StaleRatePolicy.forBasis(RateBasis.LATEST_AVAILABLE)
                )) {
                    is ConversionOutcome.Converted -> {
                        _uiState.value = _uiState.value.copy(
                            conversionResult = ConversionResult(
                                originalAmount = outcome.originalAmount,
                                originalCurrency = outcome.originalCurrency.code,
                                convertedAmount = outcome.convertedAmount,
                                targetCurrency = outcome.targetCurrency.code,
                                rateUsed = outcome.rateUsed,
                                timestamp = outcome.rateLastUpdated ?: outcome.rateValidDate ?: 0L
                            ),
                            error = null
                        )
                    }
                    is ConversionOutcome.Failed -> {
                        _uiState.value = _uiState.value.copy(
                            conversionResult = null,
                            error = "CURRENCY_CONVERSION_UNAVAILABLE"
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    conversionResult = null,
                    error = "CURRENCY_CONVERSION_UNAVAILABLE"
                )
            }
        }
    }
    
    /**
     * Refresh exchange rates from server.
     */
    fun refreshRates() {
        viewModelScope.launch {
            // Never refresh against a placeholder: with the home currency Loading/Unavailable
            // the refresh is unavailable (the screen shows the home state).
            val homeCurrency = _uiState.value.homeCurrency
            if (homeCurrency == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = ERROR_RATE_REFRESH_UNAVAILABLE
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                when (val outcome = currencyRatesRepository.refresh(homeCurrency)) {
                    is CurrencyRateRefreshOutcome.DisabledByPrivacy -> {
                        // Privacy switch is off: not a network problem, so never present as offline or retryable.
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = ERROR_RATE_DOWNLOAD_DISABLED,
                            isOffline = false
                        )
                        return@launch
                    }
                    is CurrencyRateRefreshOutcome.PrivacyCheckFailed -> {
                        // Privacy state could not be verified: not a connectivity signal, so do not
                        // touch isOffline; surface only the generic retryable refresh error.
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = ERROR_RATE_REFRESH_UNAVAILABLE
                        )
                        return@launch
                    }
                    is CurrencyRateRefreshOutcome.Refreshed -> {
                        if (outcome.storedPairs <= 0) {
                            throw IllegalStateException("No rates returned from provider")
                        }
                    }
                }
                loadCurrencyData()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val currentRates = _uiState.value.exchangeRates
                val isRatesStale = try {
                    settingsRepository.areRatesStale()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (ignored: Exception) {
                    _uiState.value.isRatesStale
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = ERROR_RATE_REFRESH_UNAVAILABLE,
                    isRatesStale = isRatesStale,
                    isOffline = !_uiState.value.rateDownloadDisabled && !hasUsableRates(currentRates)
                )
            }
        }
    }

    private fun hasUsableRates(rates: List<ExchangeRateInfo>): Boolean = rates.isNotEmpty()
    
    /**
     * Clear any error state. [CurrencyManagementUiState.rateDownloadDisabled] mirrors the privacy
     * switch and is intentionally not reset here.
     */
    fun clearError() {
        _uiState.update { state ->
            state.copy(
                error = null,
                homeSwitch = if (state.homeSwitch is HomeSwitchUi.Error) HomeSwitchUi.Idle else state.homeSwitch
            )
        }
    }
    
    /**
     * Clear conversion result.
     */
    fun clearConversion() {
        _uiState.value = _uiState.value.copy(conversionResult = null)
    }
    
    private fun buildCurrencyCatalog(): List<CurrencyInfo> =
        SupportedCurrency.activeCatalog.map { currency ->
            CurrencyInfo(
                code = currency.code,
                name = currency.displayName,
                symbol = currency.symbol,
                flag = getCurrencyFlag(currency.code)
            )
        }

    private fun getCurrencyFlag(code: String): String {
        return when (code) {
            "USD" -> "🇺🇸"
            "EUR" -> "🇪🇺"
            "GBP" -> "🇬🇧"
            "JPY" -> "🇯🇵"
            "AUD" -> "🇦🇺"
            "CAD" -> "🇨🇦"
            "CHF" -> "🇨🇭"
            "CNY" -> "🇨🇳"
            "SEK" -> "🇸🇪"
            "NZD" -> "🇳🇿"
            "MXN" -> "🇲🇽"
            "SGD" -> "🇸🇬"
            "HKD" -> "🇭🇰"
            "NOK" -> "🇳🇴"
            "KRW" -> "🇰🇷"
            "TRY" -> "🇹🇷"
            "RUB" -> "🇷🇺"
            "INR" -> "🇮🇳"
            "BRL" -> "🇧🇷"
            "ZAR" -> "🇿🇦"
            else -> "💱"
        }
    }
}
