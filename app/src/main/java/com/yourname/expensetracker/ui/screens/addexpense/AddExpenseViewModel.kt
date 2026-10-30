package com.yourname.expensetracker.ui.screens.addexpense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlinx.coroutines.isActive
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.quickadd.CategoryRef
import com.yourname.expensetracker.domain.quickadd.CategoryMatch
import com.yourname.expensetracker.domain.quickadd.FieldParse
import com.yourname.expensetracker.domain.quickadd.FlagReason
import com.yourname.expensetracker.domain.quickadd.LearnedCategoryLookupResult
import com.yourname.expensetracker.domain.quickadd.LineHint
import com.yourname.expensetracker.domain.quickadd.QuickAddInput
import com.yourname.expensetracker.domain.quickadd.QuickAddLearnedCategoryLookup
import com.yourname.expensetracker.domain.quickadd.QuickAddParser
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyAmountPolicy
import com.yourname.expensetracker.domain.currency.CurrencyEntryMode
import com.yourname.expensetracker.domain.currency.CurrencyRateRefreshOutcome
import com.yourname.expensetracker.domain.currency.CurrencyRatesRepository
import com.yourname.expensetracker.domain.currency.CurrencySelectionPolicy
import com.yourname.expensetracker.domain.currency.CurrencyRateHistoryOutcome
import com.yourname.expensetracker.domain.currency.NoRemedyReason
import com.yourname.expensetracker.domain.currency.RateEligibility
import com.yourname.expensetracker.domain.currency.RateHistoryFailureReason
import com.yourname.expensetracker.domain.currency.RateRemedy
import com.yourname.expensetracker.domain.currency.RateRemedyDecision
import com.yourname.expensetracker.domain.currency.RateRemedyPolicy
import com.yourname.expensetracker.domain.currency.UserPresentRatePreview
import com.yourname.expensetracker.domain.currency.UserPresentRatePreviewResult
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.currency.CurrencyUnsetReason
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpFailure
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanTerms
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentResidualPlacement
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import com.yourname.expensetracker.domain.installment.schedule.InstallmentScheduleCalculator
import com.yourname.expensetracker.domain.transaction.RateRefusalCodes
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.navigation.InstallmentsFeature
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentActionMessages
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentAmountInput
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentPlanMutationState
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentPlanOperation
import com.yourname.expensetracker.ui.screens.installments.declare.DeclareForm
import com.yourname.expensetracker.ui.screens.installments.declare.declareMutationMessage
import com.yourname.expensetracker.ui.components.currency.CurrencyAmountInvalidKind
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockOrigin
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockVariant
import com.yourname.expensetracker.ui.components.currency.CurrencyHistoryStatus
import com.yourname.expensetracker.ui.components.currency.CurrencyOpenSettingRequest
import com.yourname.expensetracker.ui.components.currency.CurrencyRateFixAction
import com.yourname.expensetracker.ui.components.currency.CurrencyRatesSetting
import com.yourname.expensetracker.ui.components.currency.CurrencyRefreshStatus
import com.yourname.expensetracker.ui.components.currency.CurrencyRegionUi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate

data class AddExpenseState(
 val merchant: String = "",
 val amount: String = "",
 val selectedCategoryId: Long? = null,
 val paymentMethod: PaymentMethod = PaymentMethod.CASH,
 val transactionType: TransactionType = TransactionType.PURCHASE,
 val date: Long = 0L,
 val notes: String = "",
 val showNotes: Boolean = false,
 val showTransactionType: Boolean = false,
 val isRecurring: Boolean = false,
 val recurrenceFrequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
 val suggestions: List<MerchantSuggestion> = emptyList(),
 val showSuggestions: Boolean = false,
 val isSaving: Boolean = false,
 val saveResult: SaveResult? = null,
 val mutation: com.yourname.expensetracker.ui.model.MutationState = com.yourname.expensetracker.ui.model.MutationState.idle(),
 val merchantError: UiText? = null,
 val amountError: UiText? = null,
 val transferDirection: TransferDirection? = null,
 val transferAccountName: String = "",
 val isNotMine: Boolean = false,
 val ownerName: String = "",
 val isSharedExpense: Boolean = false,
 val sharedWithName: String = "",
 val mySharePercentage: String = "",
 val myShareAmount: String = "",
 /**
  * S5-001/S5-002: Typed currency state — never defaults to "EUR" sentinel.
  * null = still loading; non-null = loaded (including real "EUR" users).
  */
 val homeCurrency: String? = null,
 /**
  * nl-quick-add fill bookkeeping (spec 2.9, G2-11, G2-13). Enums only: no String, so the typed
  * line (INV-12) and any raw text can never be held here. All cleared by [AddExpenseViewModel.reset].
  */
 val fieldOrigins: Map<QuickAddField, FieldOrigin> = QuickAddFillContract.DEFAULT_ORIGINS,
 val fieldFlags: Map<QuickAddField, FlagReason> = emptyMap(),
 val lineHints: Set<LineHint> = emptySet(),
 /** G2-13: describes the last Fill and survives later edits; only [AddExpenseViewModel.reset] clears it. */
 val lastFillOutcome: OutcomeCode? = null,
 /** G2-11: why the current category was set by Fill; null once the user edits or removes the category. */
 val categoryFillSource: CategoryFillSource? = null,
 /**
  * Item 71a / G2-13: what the last Fill reported (outcome, hints, filled and flagged field sets, kept
  * edits). Computed once in the single Fill apply, so it survives later edits and rotation. Enums, sets
  * and a Boolean only; cleared by [AddExpenseViewModel.reset].
  */
 val fillSummary: QuickAddSummarySnapshot? = null,
 /**
  * MEC B2a: the currency the amount is entered in. Defaults to home on every sheet open; null while
  * home is loading. An ISO code only; never persisted (no SavedStateHandle) and never logged.
  */
 val selectedCurrency: String? = null,
 /** MEC INV-10: options from [CurrencySelectionPolicy] (STANDARD): home first, the others by code. */
 val currencyOptions: List<String> = emptyList(),
 /** MEC INV-15/INV-6: the preview or blocked-card state under the amount (enums, codes, LocalDate). */
 val currencyRegion: CurrencyRegionUi = CurrencyRegionUi.Loading,
 /** MEC R23: the exchange-rate download setting; UNKNOWN is treated as OFF. The VM never enables it. */
 val ratesSetting: CurrencyRatesSetting = CurrencyRatesSetting.UNKNOWN,
 /** MEC INV-20: progress and bounded outcome of a user-started refresh. */
 val rateRefresh: CurrencyRefreshStatus = CurrencyRefreshStatus.IDLE,
 /** MEC 1b: progress and bounded outcome of a user-started rate-history download (INV-1b-3). */
 val historyStatus: CurrencyHistoryStatus = CurrencyHistoryStatus.IDLE,
 /**
  * MEC 1b (ux 1b.2, C13): the home currency's rates could not be downloaded; the history action stays
  * hidden for the rest of the sheet session. Session-only; cleared by onSheetLeft() and reset().
  */
 val historyHomeUnavailable: Boolean = false,
 /**
  * MEC 1b INV-1b-5: the domain remedy chosen by [RateRemedyPolicy] at the last evaluation (enums only).
  * The default keeps the pre-1b behaviour (refresh offered) until an evaluation has run.
  */
 val rateRemedy: RateRemedyDecision = RateRemedyDecision(RateRemedy.REFRESH_LATEST),
 /** MEC INV-16: true when Repeat was switched off (and cleared) because a foreign currency was chosen. */
 val repeatClearedByCurrency: Boolean = false,
 /** MEC 1b INV-1b-20(c): one-shot; a home change discarded a home-denominated draft. Cleared by the next edit. */
 val homeChangeClearedNote: Boolean = false,
 /** MEC D-11: what the "Open exchange-rate setting" action needs from the host. */
 val openSettingRequest: CurrencyOpenSettingRequest = CurrencyOpenSettingRequest.NONE,
 /** The device-local calendar date of "today" (injected clock, entry zone) for rate-date wording; set on evaluation. */
 val ratesToday: LocalDate? = null,
 /**
  * MEC F-B2-A11Y-1: monotonically increasing counter, bumped only when the currency, the date or the
  * home currency changes (never on an amount keystroke). The preview line is announced politely only
  * for a change of this key. Session-only; not persisted.
  */
 val previewAnnounceKey: Int = 0,
 /** Loan-installments S11a: the session-only installment sub-state; default = switch off. */
 val installments: AddExpenseInstallmentsState = AddExpenseInstallmentsState()
) {
 /** True when the selected currency is known and differs from home. */
 val isForeignSelected: Boolean
  get() = selectedCurrency != null && homeCurrency != null && selectedCurrency != homeCurrency

 /**
  * MEC 1b (ux 1b.5): the one fix action of the blocked card. Setting Off/Unknown is always
  * [CurrencyRateFixAction.OPEN_SETTING] (R23, fail closed); otherwise the domain remedy is copied
  * through. Home rates not downloadable hides the action; no other date logic lives here.
  */
 val rateFixAction: CurrencyRateFixAction
  get() {
   val region = currencyRegion
   if (region !is CurrencyRegionUi.Blocked) return CurrencyRateFixAction.NONE
   if (region.variant != CurrencyBlockVariant.STALE && region.variant != CurrencyBlockVariant.NONE) {
    return CurrencyRateFixAction.NONE
   }
   if (ratesSetting != CurrencyRatesSetting.ON) return CurrencyRateFixAction.OPEN_SETTING
   if (rateRefresh == CurrencyRefreshStatus.FAILED_HOME_UNAVAILABLE) return CurrencyRateFixAction.NONE
   if (historyHomeUnavailable || historyStatus == CurrencyHistoryStatus.FAILED_HOME_UNAVAILABLE) {
    return CurrencyRateFixAction.NONE
   }
   return when (rateRemedy.remedy) {
    RateRemedy.REFRESH_LATEST -> CurrencyRateFixAction.REFRESH_LATEST
    RateRemedy.DOWNLOAD_HISTORY -> CurrencyRateFixAction.DOWNLOAD_HISTORY
    RateRemedy.NONE ->
     if (rateRemedy.noRemedyReason == NoRemedyReason.OUTSIDE_HISTORY_WINDOW) {
      CurrencyRateFixAction.TOO_OLD
     } else {
      CurrencyRateFixAction.NONE
     }
   }
  }

 /**
  * MEC ux section 4: Save is disabled for a blocked rate (except a retryable home-unresolved refusal),
  * an invalid amount, or an unset currency. While [CurrencyRegionUi.Evaluating] Save stays enabled
  * (the coordinator is authoritative, INV-6).
  */
 val isCurrencySaveBlocked: Boolean
  get() = when (currencyRegion) {
   is CurrencyRegionUi.Blocked -> currencyRegion.variant != CurrencyBlockVariant.HOME_UNRESOLVED
   is CurrencyRegionUi.AmountInvalid, is CurrencyRegionUi.Unset -> true
   else -> false
  }
}

sealed class SaveResult {
    object Success : SaveResult()
    object Duplicate : SaveResult()
    data class Error(val message: UiText) : SaveResult()
}

@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    private val manualExpenseRepository: ManualExpenseRepository,
    private val expenseRepository: com.yourname.expensetracker.data.repository.ExpenseRepository,
 private val categoryRepository: CategoryRepository,
 private val timeProvider: TimeProvider,
 private val currencySettingsRepository: CurrencySettingsRepository,
 private val quickAddParser: QuickAddParser,
 private val quickAddLearnedCategoryLookup: QuickAddLearnedCategoryLookup,
 private val userPresentRatePreview: UserPresentRatePreview,
 private val currencyRatesRepository: CurrencyRatesRepository,
 private val privacySettingsRepository: PrivacySettingsRepository,
 private val installmentPlanCoordinator: InstallmentPlanLifecycleCoordinator,
 private val installmentPostCommitDispatcher: InstallmentPostCommitDispatcher
) : ViewModel() {

    private companion object {
        // INV-13: bounded diagnostic codes. No throwable, query, merchant or message text is ever attached.
        const val LOG_MERCHANT_SUGGESTION_SEARCH_FAILED = "MERCHANT_SUGGESTION_SEARCH_FAILED"
        const val LOG_ADD_EXPENSE_SAVE_FAILED = "ADD_EXPENSE_SAVE_FAILED"
        const val LOG_QUICK_ADD_CATEGORY_LOOKUP_FAILED = "QUICK_ADD_CATEGORY_LOOKUP_FAILED"

        // Spec 7.2: exactly one outcome constant per applied Fill.
        const val LOG_FILL_FULL = "FILL_FULL"
        const val LOG_FILL_PARTIAL = "FILL_PARTIAL"
        const val LOG_FILL_NONE = "FILL_NONE"
        const val LOG_FOREIGN_CURRENCY_BLOCKED = "FOREIGN_CURRENCY_BLOCKED"

        // MEC: bounded diagnostic codes only (INV-19): never an amount, currency, rate, date or throwable.
        const val LOG_RATE_PREVIEW_FAILED = "RATE_PREVIEW_FAILED"
        const val LOG_RATE_REFRESH_FAILED = "RATE_REFRESH_FAILED"
        const val LOG_RATES_SETTING_READ_FAILED = "RATES_SETTING_READ_FAILED"
        const val LOG_RATE_HISTORY_FAILED = "RATE_HISTORY_FAILED"

        // MEC ux section 3: "Checking exchange rate" shows only if an evaluation exceeds this delay.
        const val EVALUATING_DELAY_MS = 250L

        // Bounded message tokens of the existing refresh failures (matched by equality, never displayed).
        const val REFRESH_HOME_QUOTE_UNAVAILABLE = "HOME_CURRENCY_QUOTE_UNAVAILABLE"
        const val REFRESH_INVALID_HOME_CURRENCY = "INVALID_HOME_CURRENCY"
    }

    private val _state = MutableStateFlow(AddExpenseState(date = timeProvider.now()))
    val state: StateFlow<AddExpenseState> = _state.asStateFlow()

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

 private var searchJob: Job? = null

 // INV-18: latest-wins. Holds the Job only; the typed line is never stored (INV-12).
 private var fillJob: Job? = null
 private var initialValuesApplied: Boolean = false

 private var homeCurrencyJob: Job? = null
 /** MEC 1b INV-1b-20: the previously observed home, to detect a change (null until first emission). */
 private var homeObserved = false // AE-50 (owner item 63): no String-typed VM field; the previous home is read from state
 /** The single SAVING-wait for a deferred home-change discard (INV-1b-20(c)); replaced on each new home change. */
 private var pendingHomeDiscardJob: Job? = null

 /** True when the draft holds home-denominated money: amount, installment fixed amount, or a share amount. */
 private fun hasDraftMoney(s: AddExpenseState): Boolean =
  s.amount.isNotBlank() || (s.installments.on && s.installments.fixedText.isNotBlank()) ||
   s.myShareAmount.isNotBlank()

 /** INV-1b-20(c): discards the whole draft via reset() (keeps the open sheet's zone) and raises the one-shot note. */
 private fun discardDraftForHomeChange() {
  val zone = sessionZone
  reset()
  if (zone != null) {
   sessionZone = zone
   sessionZoneTick.value = zone
  }
  _state.update { it.copy(homeChangeClearedNote = true) }
 }

 // MEC AE-34: the entry zone captured when the sheet opened. A plain field: never in state, never
 // persisted or logged (QuickAddPrivacy G-1). Cleared by reset().
 private var sessionZone: java.time.ZoneId? = null

 // MEC B2a: the user's explicit currency choice for this sheet session; null = follow home. A plain
 // field (never persisted or logged, QuickAddPrivacy G-1). Cleared by reset() and on a new session.
 // Held in a non-inline wrapper so no field compiles to a JVM String (AE-50 field reflection).
 private data class CurrencyOverride(val code: CurrencyCode)

 private var currencyOverride: CurrencyOverride? = null

 // Loan-installments S11a (MEC UX-Q-6): a STANDARD-mode choice that installment mode cannot offer
 // (for example JPY) is parked here while installments are ON and restored when they are switched off.
 // A plain session-only field in the same non-inline wrapper; cleared by reset() and on a new session.
 private var stashedOverride: CurrencyOverride? = null

 // MEC INV-3: latest-wins evaluation, and the single user-started refresh.
 private var evaluationJob: Job? = null
 private var refreshJob: Job? = null
 private var ratesSettingJob: Job? = null

 // MEC 1b: the single user-started history download (INV-1b-3, Q-5). Session-only plain fields, never
 // persisted or logged: the success flag feeds RateRemedyPolicy (INV-1b-5), the earliest stored ECB date
 // feeds the DONE_TOO_OLD mapping (D-1b-5), the announce flag bumps the preview key once after success.
 // Cleared by onSheetLeft() and reset().
 private var historyJob: Job? = null
 private var historyDownloadedThisSession: Boolean = false
 private var historyEarliestDate: LocalDate? = null
 private var historyAnnouncePending: Boolean = false

 /**
  * Loan-installments S11c: the zone-dependent installment values (default first payment, before-purchase
  * warning, live preview card), derived from [state] with the entry zone (`sessionZone`, else the device
  * default: the B1 precedent) and the injected clock. Eagerly shared, so `.value` is current for tests.
  * Pure derivation; nothing is stored, persisted or logged.
  */
 // Mirror of [sessionZone] as a flow, so the derivation recomputes when the zone is captured or cleared.
 // Not a String and never in state (G-1); sessionZone stays the plain field used by save.
 private val sessionZoneTick = MutableStateFlow<java.time.ZoneId?>(null)

 val installmentsDerived: StateFlow<AddExpenseInstallmentsDerived> = combine(_state, sessionZoneTick) { s, z ->
   deriveInstallments(s, z ?: java.time.ZoneId.systemDefault())
  }
  .distinctUntilChanged()
  .stateIn(
   viewModelScope,
   SharingStarted.Eagerly,
   deriveInstallments(_state.value, sessionZoneTick.value ?: java.time.ZoneId.systemDefault()),
  )

 private fun deriveInstallments(s: AddExpenseState, zone: java.time.ZoneId): AddExpenseInstallmentsDerived {
  return AddExpenseInstallmentsDerivation.derive(s, zone, todayFor(zone).toEpochDay())
 }

 init {
 homeCurrencyJob = viewModelScope.launch {
 currencySettingsRepository.homeCurrency().collect { hc ->
 // MEC 1b INV-1b-20(c): a home-denominated draft is discarded on a home change (open sheet or retained).
 val before = _state.value
 val oldHome = if (homeObserved) before.homeCurrency else null
 homeObserved = true
 val homeDraftAtRisk = oldHome != null && hc != oldHome && hasDraftMoney(before) &&
  currencyOverride == null && before.selectedCurrency == oldHome
 _state.update { applyHomeAndSelection(it.copy(homeCurrency = hc, previewAnnounceKey = it.previewAnnounceKey + 1)) }
 reevaluateRegion(invalidate = true)
 if (homeDraftAtRisk) {
  if (before.isSaving) {
   // INV-1b-20(c) SAVING: never discard mid-save; wait for the outcome. A saved row closes the sheet.
   // One waiter at a time: a newer home change replaces the pending one, so the discard runs at most once.
   pendingHomeDiscardJob?.cancel()
   pendingHomeDiscardJob = viewModelScope.launch {
    val settled = _state.first { !it.isSaving }
    if (settled.saveResult != SaveResult.Success && hasDraftMoney(settled)) discardDraftForHomeChange()
   }
  } else {
   discardDraftForHomeChange()
  }
 }
 }
 }
 ratesSettingJob = viewModelScope.launch {
 // R23: the setting is read, never written. Loaded/FirstRunDefault map by flag; a corrupted
 // (fail-closed) state or a read error is Unknown, which the UI treats as Off.
 privacySettingsRepository.observeLoadState()
  .map { loadState ->
   when (loadState) {
    is PrivacySettingsLoadState.Loaded ->
     if (loadState.settings.exchangeRateDownloadEnabled) CurrencyRatesSetting.ON else CurrencyRatesSetting.OFF
    is PrivacySettingsLoadState.FirstRunDefault ->
     if (loadState.settings.exchangeRateDownloadEnabled) CurrencyRatesSetting.ON else CurrencyRatesSetting.OFF
    is PrivacySettingsLoadState.CorruptedFailClosed -> CurrencyRatesSetting.UNKNOWN
   }
  }
  .distinctUntilChanged()
  .catch { e ->
   if (e is CancellationException) throw e
   Timber.w(LOG_RATES_SETTING_READ_FAILED)
   emit(CurrencyRatesSetting.UNKNOWN)
  }
  .collect { setting -> _state.update { it.copy(ratesSetting = setting) } }
 }
 }

    fun updateMerchant(value: String) {
        val sanitized = value.take(100) // Max 100 chars
        _state.update {
            it.copy(
                merchant = sanitized,
                merchantError = null,
                saveResult = null
            ).withUserEdit(QuickAddField.MERCHANT)
        }

        // Debounced search
        searchJob?.cancel()
        if (sanitized.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(300)
                if (!isActive) return@launch
                
                try {
                    val suggestions = expenseRepository.searchMerchants(sanitized)
                    if (!isActive) return@launch
                    _state.update {
                        it.copy(
                            suggestions = suggestions,
                            showSuggestions = suggestions.isNotEmpty()
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // S5-008: Silently clear suggestions on failure (non-critical)
                    // INV-13: bounded code only; no throwable, query or merchant text.
                    Timber.w(LOG_MERCHANT_SUGGESTION_SEARCH_FAILED)
                    if (isActive) {
                        _state.update { it.copy(suggestions = emptyList(), showSuggestions = false) }
                    }
                }
            }
        } else {
            _state.update { it.copy(suggestions = emptyList(), showSuggestions = false) }
        }
    }

    fun selectSuggestion(suggestion: MerchantSuggestion) {
        _state.update {
            // INV-9: a suggestion tap is a USER edit; it writes the amount only if the amount is
            // blank AND carries no flag (a flagged blank amount stays flagged and blank, AE-26).
            val writeAmount = it.amount.isBlank() && QuickAddField.AMOUNT !in it.fieldFlags
            var next = it.copy(
                merchant = suggestion.merchant,
                selectedCategoryId = suggestion.categoryId ?: it.selectedCategoryId,
                amount = if (writeAmount) com.yourname.expensetracker.ui.util.AmountInputSanitizer.sanitize(String.format(java.util.Locale.US, "%.2f", suggestion.avgAmount)) else it.amount,
                suggestions = emptyList(),
                showSuggestions = false,
                merchantError = null
            ).withUserEdit(QuickAddField.MERCHANT)
            if (suggestion.categoryId != null) {
                next = next.copy(categoryFillSource = null).withUserEdit(QuickAddField.CATEGORY)
            }
            if (writeAmount) {
                next = next.withUserEdit(QuickAddField.AMOUNT)
            }
            next
        }
    }

    fun dismissSuggestions() {
        _state.update { it.copy(showSuggestions = false) }
    }

    fun updateAmount(value: String) {
        val sanitized = com.yourname.expensetracker.ui.util.AmountInputSanitizer.sanitize(value)
        _state.update {
            it.copy(
                amount = sanitized,
                amountError = null,
                saveResult = null,
                homeChangeClearedNote = false
            ).withUserEdit(QuickAddField.AMOUNT)
        }
        // MEC INV-3: the figure changed; drop to Pending at once so a converted amount for the
        // previous figure (or a SAVE-origin card) is never shown for the new one. MEC R-4: a Blocked card
        // that carries no amount is kept, so the card and Save do not toggle per keystroke.
        reevaluateRegion(invalidate = !_state.value.currencyRegion.isAmountIndependentBlock())
    }

    /** MEC R-4: true for a Blocked region whose variant carries no amount-dependent data. */
    private fun CurrencyRegionUi.isAmountIndependentBlock(): Boolean =
        this is CurrencyRegionUi.Blocked && when (variant) {
            CurrencyBlockVariant.NONE,
            CurrencyBlockVariant.STALE,
            CurrencyBlockVariant.UNSUPPORTED,
            CurrencyBlockVariant.HOME_UNRESOLVED -> true
        }

    fun selectCategory(categoryId: Long) {
        _state.update {
            it.copy(selectedCategoryId = categoryId, categoryFillSource = null)
                .withUserEdit(QuickAddField.CATEGORY)
        }
    }

    /**
     * G2-11: removes the category (the chip's Remove). Counts as a USER edit, so a later Fill never
     * refills it. A null category at Save is still resolved by the existing classifier.
     */
    fun clearCategory() {
        _state.update {
            it.copy(selectedCategoryId = null, categoryFillSource = null)
                .withUserEdit(QuickAddField.CATEGORY)
        }
    }

    fun selectPaymentMethod(method: PaymentMethod) {
        _state.update { it.copy(paymentMethod = method).withUserEdit(QuickAddField.PAYMENT) }
    }

    fun selectTransactionType(type: TransactionType) {
        // S5-011: Clear transfer metadata when leaving TRANSFER type
        var installmentsSwitchedOff = false
        _state.update {
            installmentsSwitchedOff = false
            val next = if (type == TransactionType.TRANSFER) {
                it.copy(transactionType = type)
            } else {
                it.copy(transactionType = type, transferDirection = null, transferAccountName = "")
            }
            // Loan-installments S11a: a non-spending type turns installments off (values kept).
            if (it.installments.on && next.installmentsBlockReason != null) {
                installmentsSwitchedOff = true
                leaveInstallmentMode(next)
            } else {
                next
            }
        }
        if (installmentsSwitchedOff) reevaluateRegion(invalidate = true)
    }

    fun updateDate(dateMs: Long) {
        _state.update {
            it.copy(date = dateMs, previewAnnounceKey = it.previewAnnounceKey + 1).withUserEdit(QuickAddField.DATE)
        }
        // MEC INV-3: a new date can change eligibility.
        reevaluateRegion(invalidate = true)
    }

    fun updateNotes(value: String) {
        _state.update { it.copy(notes = value.take(500)) } // Max 500 chars
    }

    fun toggleNotes() {
        _state.update { it.copy(showNotes = !it.showNotes) }
    }

    fun toggleTransactionType() {
        _state.update { it.copy(showTransactionType = !it.showTransactionType) }
    }
    
    fun toggleRecurring() {
        // MEC INV-16: Repeat is home-currency only; a foreign currency can never carry a recurrence.
        // Loan-installments S11a: Repeat and installments are mutually exclusive (no-op while ON).
        _state.update { if (it.isForeignSelected || it.installments.on) it else it.copy(isRecurring = !it.isRecurring) }
    }

    fun setRecurrenceFrequency(frequency: RecurrenceFrequency) {
        _state.update { if (it.isForeignSelected || it.installments.on) it else it.copy(recurrenceFrequency = frequency) }
    }

    fun setTransferDirection(direction: TransferDirection?) {
        _state.update { it.copy(transferDirection = direction) }
    }

    fun updateTransferAccountName(name: String) {
        _state.update { it.copy(transferAccountName = name.take(100)) }
    }

    fun setIsNotMine(value: Boolean) {
        var installmentsSwitchedOff = false
        _state.update {
            installmentsSwitchedOff = false
            if (value) {
                val next = it.copy(
                    isNotMine = true,
                    isSharedExpense = false,
                    sharedWithName = "",
                    mySharePercentage = "",
                    myShareAmount = ""
                )
                // Loan-installments S11a: Not mine turns installments off and keeps the plan values.
                if (it.installments.on) {
                    installmentsSwitchedOff = true
                    leaveInstallmentMode(next)
                } else {
                    next
                }
            } else {
                it.copy(isNotMine = false)
            }
        }
        if (installmentsSwitchedOff) reevaluateRegion(invalidate = true)
    }

    fun updateOwnerName(name: String) {
        _state.update { it.copy(ownerName = name.take(100)) }
    }

    fun setIsSharedExpense(value: Boolean) {
        var installmentsSwitchedOff = false
        _state.update {
            installmentsSwitchedOff = false
            if (value) {
                val next = it.copy(
                    isSharedExpense = true,
                    isNotMine = false,
                    ownerName = ""
                )
                // Loan-installments S11a: Shared (and its split) turns installments off, values kept.
                if (it.installments.on) {
                    installmentsSwitchedOff = true
                    leaveInstallmentMode(next)
                } else {
                    next
                }
            } else {
                it.copy(
                    isSharedExpense = false,
                    sharedWithName = "",
                    mySharePercentage = "",
                    myShareAmount = ""
                )
            }
        }
        if (installmentsSwitchedOff) reevaluateRegion(invalidate = true)
    }

    fun updateSharedWithName(name: String) {
        _state.update { it.copy(sharedWithName = name.take(100)) }
    }

    fun updateMySharePercentage(value: String) {
        _state.update { it.copy(mySharePercentage = value.filter { it.isDigit() }.take(3)) }
    }

    fun updateMyShareAmount(value: String) {
        val sanitized = com.yourname.expensetracker.ui.util.AmountInputSanitizer.sanitize(value)
        _state.update { it.copy(myShareAmount = sanitized) }
    }

    fun save() {
        val currentState = _state.value

        // S5-007: Guard against double-tap
        if (currentState.isSaving) return

        // S5-001: Block save until currency is loaded — null means still loading
        // Real EUR users (homeCurrency == "EUR") are allowed through once loaded
        val homeCurrency = currentState.homeCurrency
        if (homeCurrency == null) {
            _state.update { it.withSaveError(UiText.StringResource(R.string.add_expense_error_currency_loading)) }
            return
        }
        // MEC INV-11: the currency the user selected (home by default), captured at save-tap. The
        // transaction coordinator, not this screen, decides whether a foreign rate qualifies.
        // Loan-installments S11a (MEC AE-28): in installment mode an Unset currency (for example a JPY
        // choice that installment mode cannot offer) is never replaced by home; refuse before anything.
        val installmentsOn = currentState.installments.on
        if (installmentsOn && currentState.selectedCurrency == null) {
            _state.update { it.withSaveError(UiText.StringResource(R.string.mec_save_disabled_currency)) }
            return
        }
        val currency = currentState.selectedCurrency ?: homeCurrency
        val isForeign = currency != homeCurrency

        // Validate
        val merchantTrimmed = currentState.merchant.trim()
        if (merchantTrimmed.isBlank()) {
            _state.update { it.copy(merchantError = UiText.StringResource(R.string.add_expense_error_merchant_required)) }
            return
        }

        // R16: same strict locale-aware parser as AmountInputSanitizer.isValid / OwnershipValidator.
        // Ambiguous or invalid input keeps the existing invalid-amount error.
        val amount = com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(currentState.amount)
        if (amount == null || amount <= 0) {
            _state.update { it.copy(amountError = UiText.StringResource(R.string.add_expense_error_amount_invalid)) }
            return
        }

        // MEC INV-12/13/14: per-currency precision and cap, owned by CurrencyAmountPolicy. The amount
        // is never rounded or truncated; a failing amount keeps its text and nothing is saved.
        val amountCheckError = checkAmountForCurrency(amount, currency)
        if (amountCheckError != null) {
            _state.update { it.copy(amountError = amountCheckError) }
            return
        }

        // Reject future dates — allow up to end of today to accommodate timezone edge cases
        val endOfToday = run {
            TimePeriodUtils.getEndOfDay(timeProvider.now()) - 1
        }
        if (currentState.date > endOfToday) {
            _state.update { it.withSaveError(UiText.StringResource(R.string.add_expense_error_date_future)) }
            return
        }

        val transferAccountNameTrimmed = currentState.transferAccountName.trim()
        if (currentState.transactionType == TransactionType.TRANSFER) {
            if (currentState.transferDirection == null) {
                _state.update {
                    it.withSaveError(UiText.DynamicString("Transfer direction is required for transfer transactions"))
                }
                return
            }
            if (transferAccountNameTrimmed.isBlank()) {
                _state.update {
                    it.withSaveError(UiText.DynamicString("Transfer account name is required for transfer transactions"))
                }
                return
            }
        }

        val sharePercentageText = currentState.mySharePercentage.trim()
        val shareAmountText = currentState.myShareAmount.trim()
        val hasSharePercentage = sharePercentageText.isNotEmpty()
        val hasShareAmount = shareAmountText.isNotEmpty()

        // S5-011: Use shared OwnershipValidator
        val ownershipResult = com.yourname.expensetracker.ui.util.OwnershipValidator.validate(
            isNotMine = currentState.isNotMine,
            isSharedExpense = currentState.isSharedExpense,
            sharedWithName = currentState.sharedWithName,
            sharePercentageText = sharePercentageText,
            shareAmountText = shareAmountText
        )
        if (ownershipResult is com.yourname.expensetracker.ui.util.OwnershipValidator.ValidationResult.Invalid) {
            // OwnershipValidator messages are fixed user-safe English constants (localisation deferred).
            _state.update { it.withSaveError(UiText.DynamicString(ownershipResult.message)) }
            return
        }

        val sharePercentage: Int?
        val shareAmount: Double?
        if (currentState.isSharedExpense) {
            if (hasSharePercentage) {
                val parsedSharePercentage = sharePercentageText.toIntOrNull()!!
                sharePercentage = parsedSharePercentage
                shareAmount = null
            } else {
                // OwnershipValidator already accepted this text via the same parser.
                val parsedShareAmount = com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(shareAmountText)!!
                sharePercentage = null
                shareAmount = parsedShareAmount
            }
        } else {
            sharePercentage = null
            shareAmount = null
        }

        // MEC AE-34: the zone captured when the sheet opened; falls back to the device zone now.
        val entryZone = sessionZone ?: java.time.ZoneId.systemDefault()

        // Loan-installments S11a: the installment submit branch. The plain path below is untouched.
        if (installmentsOn) {
            saveWithInstallments(currentState, merchantTrimmed, amount, currency, entryZone)
            return
        }

        _state.update { it.copy(isSaving = true, saveResult = null, mutation = com.yourname.expensetracker.ui.model.MutationState.running("save")) }

        viewModelScope.launch {
            try {
                // 1. Save the actual transaction
                val result = manualExpenseRepository.addManualExpense(
                    merchant = merchantTrimmed,
                    amount = amount,
                    // S5-004: Use currency captured at save-tap, not live _state.value
                    currency = currency,
                    categoryId = currentState.selectedCategoryId,
                    transactionType = currentState.transactionType,
                    paymentMethod = currentState.paymentMethod,
                    date = currentState.date,
                    notes = currentState.notes.takeIf { it.isNotBlank() },
                    transferDirection = currentState.transferDirection.takeIf {
                        currentState.transactionType == TransactionType.TRANSFER
                    },
                    transferAccountName = transferAccountNameTrimmed.takeIf {
                        currentState.transactionType == TransactionType.TRANSFER && it.isNotBlank()
                    },
                    isNotMine = currentState.isNotMine,
                    ownerName = currentState.ownerName.takeIf { it.isNotBlank() },
                    isSharedExpense = currentState.isSharedExpense,
                    sharedWithName = currentState.sharedWithName.trim().takeIf {
                        currentState.isSharedExpense && it.isNotBlank()
                    },
                    mySharePercentage = sharePercentage,
                    myShareAmount = shareAmount,
                    recurrenceFrequency = currentState.recurrenceFrequency.takeIf { currentState.isRecurring && !isForeign },
                    entryZone = entryZone
                )

                when (result) {
                    is Result.Success -> {
                        _state.update {
                            it.copy(isSaving = false, saveResult = SaveResult.Success, mutation = com.yourname.expensetracker.ui.model.MutationState.success("save"))
                        }
                    }
                    is Result.Duplicate -> {
                        _state.update {
                            it.copy(isSaving = false, saveResult = SaveResult.Duplicate, mutation = com.yourname.expensetracker.ui.model.MutationState.error("save", UiText.StringResource(R.string.error_duplicate_transaction)))
                        }
                    }
                    is Result.Error -> {
                        // S5-003: Update mutation so Save button re-enables.
                        // INV-15: repository message/exception are never displayed.
                        val refusal = blockVariantFor(result.message)
                        if (refusal != null) {
                            applyRateRefusal(refusal)
                        } else {
                            _state.update { it.withSaveError(mapSaveError(result, currency, homeCurrency)) }
                        }
                    }
                    Result.Loading -> {
                        // S5-003: Result.Loading is not a valid terminal state for one-shot save
                        _state.update { it.withSaveError(saveFailedText()) }
                    }
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // S5-003: Update mutation so Save button re-enables after exception.
                // INV-13/INV-15: bounded code only; no throwable or message text.
                Timber.e(LOG_ADD_EXPENSE_SAVE_FAILED)
                _state.update { it.withSaveError(saveFailedText()) }
            } finally {
                // INV-15: isSaving never stays true, including on cancellation.
                _state.update {
                    if (it.isSaving) {
                        it.copy(isSaving = false, mutation = com.yourname.expensetracker.ui.model.MutationState.idle())
                    } else {
                        it
                    }
                }
            }
        }
    }

    /**
     * MEC R-2 / INV-6 / AE-22, shared by the plain and the installment save paths: a post-tap rate
     * refusal keeps the form and shows the SAVE card. The in-flight preview is cancelled first so a
     * late result cannot overwrite the card or re-enable Save; a stale refusal re-reads locally.
     */
    private fun applyRateRefusal(variant: CurrencyBlockVariant) {
        evaluationJob?.cancel()
        evaluationJob = null
        _state.update { it.withSaveRefusal(variant) }
        // A stale refusal carries only a bounded code; re-read the latest date locally.
        if (variant == CurrencyBlockVariant.STALE) {
            reevaluateRegion(
                invalidate = false,
                showEvaluating = false,
                origin = CurrencyBlockOrigin.SAVE,
                keepSaveBlock = true
            )
        }
    }

    /**
     * Loan-installments S11a (INV-27, AE-1): the installment submit branch. The purchase request is
     * built by the repository's shared builder (same merchant normalisation and classification as a
     * plain save, no rate flag), then ONE coordinator call creates the purchase and the plan
     * atomically. [addManualExpense][ManualExpenseRepository.addManualExpense] is never called. The
     * coordinator sets the user-present rate flag itself, so this class never builds the requirement.
     * Plan currency == purchase currency == [currency] (G2-6), declared total == [amount], no
     * conversion. Refusals map to fixed strings only (INV-31); a rate refusal reuses the MEC card.
     */
    private fun saveWithInstallments(
        s: AddExpenseState,
        merchant: String,
        amount: Double,
        currency: String,
        entryZone: java.time.ZoneId
    ) {
        val plan = s.installments
        val localRefusal = installmentLocalRefusal(s, currency)
        if (localRefusal != null) {
            _state.update { it.withInstallmentRefusal(localRefusal) }
            return
        }
        val count = plan.count ?: return
        val planUuid = plan.planUuid ?: java.util.UUID.randomUUID().toString()
        val equal = plan.split == InstallmentSplitMethod.EQUAL_SPLIT
        val purchaseDay = InstallmentScheduleCalculator.civilEpochDay(s.date, entryZone)
        val firstDue = plan.firstDueEpochDay ?: AddExpenseInstallmentsDerivation.defaultFirstDueEpochDay(purchaseDay)
        val terms = InstallmentPlanTerms(
            planUuid = planUuid,
            displayName = plan.effectiveName(merchant),
            lenderLabel = null,
            currency = currency,
            splitMethod = plan.split,
            declaredTotal = if (equal) amount else null,
            fixedPaymentAmount = if (equal) null else plan.fixedMinor?.let(InstallmentAmountInput::toAmount),
            installmentCount = count,
            firstDueEpochDay = firstDue,
            residualPlacement = InstallmentResidualPlacement.FIRST,
            fulfilmentMode = plan.mode,
            // The paid-before field is hidden (deferral 49(b)): 0 is always sent.
            paidBeforeTrackingCount = 0,
            declaredOnEpochDay = todayFor(entryZone).toEpochDay()
        )
        val categoryId = s.selectedCategoryId
        val transactionType = s.transactionType
        val paymentMethod = s.paymentMethod
        val date = s.date
        val notes = s.notes.takeIf { it.isNotBlank() }

        // The plan uuid is kept for the whole session so a retry after a refusal is idempotent (AE-15d).
        _state.update {
            it.copy(
                isSaving = true,
                saveResult = null,
                mutation = com.yourname.expensetracker.ui.model.MutationState.running("save"),
                installments = it.installments.copy(planUuid = planUuid)
            )
        }

        viewModelScope.launch {
            try {
                val request = manualExpenseRepository.buildInstallmentPurchaseRequest(
                    merchant = merchant,
                    amount = amount,
                    currency = currency,
                    categoryId = categoryId,
                    transactionType = transactionType,
                    paymentMethod = paymentMethod,
                    date = date,
                    notes = notes
                )
                val result = installmentPlanCoordinator.declareWithNewExpense(request, entryZone, terms)
                when (result) {
                    is InstallmentOpResult.Done -> {
                        // Post-commit batches go through the one shared dispatcher (F3).
                        installmentPostCommitDispatcher.dispatch(result.postCommit)
                        _state.update { it.withInstallmentSuccess() }
                    }
                    // AE-15d: a double submit finds the plan already there; that is success.
                    is InstallmentOpResult.AlreadyInState -> _state.update { it.withInstallmentSuccess() }
                    is InstallmentOpResult.Refused -> _state.update { it.withInstallmentRefusal(result.code) }
                    is InstallmentOpResult.RateRefused -> {
                        val variant = blockVariantFor(RateRefusalCodes.codeFor(result.reason))
                        if (variant != null) {
                            applyRateRefusal(variant)
                        } else {
                            _state.update { it.withSaveError(saveFailedText()) }
                        }
                    }
                    is InstallmentOpResult.Failed -> _state.update { it.withInstallmentFailure(result.failure) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // INV-13/INV-15/INV-31: bounded code only; no throwable or message text.
                Timber.e(LOG_ADD_EXPENSE_SAVE_FAILED)
                _state.update { it.withSaveError(saveFailedText()) }
            } finally {
                // INV-15: isSaving never stays true, including on cancellation.
                _state.update {
                    if (it.isSaving) {
                        it.copy(isSaving = false, mutation = com.yourname.expensetracker.ui.model.MutationState.idle())
                    } else {
                        it
                    }
                }
            }
        }
    }

    /**
     * Refusals decided locally, before any coordinator call: the switch's block reasons (Not mine,
     * Shared, non-spending), an invalid count or fixed payment, and a currency outside EUR/USD (the
     * coordinator's UNSUPPORTED_CURRENCY stays the backstop). Null when the form may be submitted.
     */
    private fun installmentLocalRefusal(s: AddExpenseState, currency: String): InstallmentRefusalCode? {
        val plan = s.installments
        val blockReason = s.installmentsBlockReason
        if (blockReason != null) return blockReason
        if (plan.count == null) return InstallmentRefusalCode.INVALID_COUNT
        if (plan.split == InstallmentSplitMethod.FIXED_PAYMENT && plan.fixedMinor == null) {
            return InstallmentRefusalCode.INVALID_AMOUNT
        }
        val code = CurrencyCode.parse(currency)
        if (code == null || code !in CurrencySelectionPolicy.INSTALLMENT_CURRENCIES) {
            return InstallmentRefusalCode.UNSUPPORTED_CURRENCY
        }
        return null
    }

    /** A committed installment save (or a silent AlreadyInState): the sheet's success path resets the form. */
    private fun AddExpenseState.withInstallmentSuccess(): AddExpenseState = copy(
        isSaving = false,
        saveResult = SaveResult.Success,
        mutation = com.yourname.expensetracker.ui.model.MutationState.success("save")
    )

    /** AE-22: a refusal code becomes the fixed declare string; the form is kept. Never a message text. */
    private fun AddExpenseState.withInstallmentRefusal(code: InstallmentRefusalCode): AddExpenseState {
        val resId = declareMutationMessage(
            InstallmentPlanMutationState.Refused(InstallmentPlanOperation.CREATE, code)
        ) ?: R.string.add_expense_error_save_failed
        return withSaveError(UiText.StringResource(resId))
    }

    /** A bounded coordinator failure becomes its fixed string; the form is kept. */
    private fun AddExpenseState.withInstallmentFailure(failure: InstallmentOpFailure): AddExpenseState {
        val resId = declareMutationMessage(
            InstallmentPlanMutationState.Failed(
                InstallmentPlanOperation.CREATE,
                InstallmentActionMessages.failureCode(failure)
            )
        ) ?: R.string.add_expense_error_save_failed
        return withSaveError(UiText.StringResource(resId))
    }

    /**
     * MEC INV-12/13/14: validates the parsed [amount] for [currency] with [CurrencyAmountPolicy]
     * (Double overload). Returns the inline error, or null when the amount may be saved. Never
     * rounds. An unparsable currency code skips the precision check and uses the default cap.
     */
    private fun checkAmountForCurrency(amount: Double, currency: String): UiText? {
        val code = com.yourname.expensetracker.domain.core.money.CurrencyCode.parse(currency)
        if (code == null) {
            return if (amount > com.yourname.expensetracker.domain.currency.CurrencyAmountPolicy.DEFAULT_CAP.toDouble()) {
                amountTooLargeText(com.yourname.expensetracker.domain.currency.CurrencyAmountPolicy.DEFAULT_CAP)
            } else {
                null
            }
        }
        return when (val check = com.yourname.expensetracker.domain.currency.CurrencyAmountPolicy.check(amount, code)) {
            com.yourname.expensetracker.domain.currency.AmountCheck.Ok -> null
            // Not reachable after the positive check; fail closed with the generic invalid-amount error.
            com.yourname.expensetracker.domain.currency.AmountCheck.NotPositive ->
                UiText.StringResource(R.string.add_expense_error_amount_invalid)
            // OD-6: digits unknown. Never assume 2 digits or "no limit".
            com.yourname.expensetracker.domain.currency.AmountCheck.DigitsUnavailable ->
                UiText.StringResource(R.string.add_expense_error_amount_invalid)
            is com.yourname.expensetracker.domain.currency.AmountCheck.PrecisionExceeded ->
                precisionText(code.code, check.allowedDigits)
            is com.yourname.expensetracker.domain.currency.AmountCheck.AboveCap ->
                amountTooLargeText(check.cap)
        }
    }

    private fun precisionText(currencyCode: String, allowedDigits: Int): UiText =
        if (allowedDigits == 0) {
            UiText.StringResource(R.string.mec_amount_no_decimals, listOf(currencyCode))
        } else {
            UiText.StringResource(R.string.mec_amount_max_decimals, listOf(currencyCode, allowedDigits))
        }

    private fun amountTooLargeText(cap: java.math.BigDecimal): UiText =
        UiText.StringResource(
            R.string.mec_amount_too_large,
            listOf(java.text.NumberFormat.getIntegerInstance().format(cap.toLong()))
        )

    /**
     * Maps a repository error to a bounded, localised message. The repository message and
     * exception are never displayed (INV-15). Only bounded repository error codes are matched, by
     * equality; the default is the generic save-failed resource. Non-suspend by design.
     * [currency] is the selected currency and [home] the home currency, both captured at save-tap.
     * Rate refusals (the four RATE_NOT_ELIGIBLE codes) are handled before this by [blockVariantFor].
     */
    private fun mapSaveError(result: Result.Error, currency: String, home: String): UiText = when (result.message) {
        // S4 / INV-16: recurring rule refused for a currency other than home; P1: names the HOME code.
        ManualExpenseRepository.RECURRING_FOREIGN_CURRENCY_ERROR_CODE ->
            UiText.StringResource(R.string.mec_repeat_home_only, listOf(home))
        // AE-14: the home currency could not be resolved at save; nothing was saved.
        com.yourname.expensetracker.domain.transaction.RateRefusalCodes.NOT_ELIGIBLE_HOME_UNRESOLVED ->
            UiText.StringResource(R.string.mec_block_home_unresolved)
        // OD-6: the currency's digits are unavailable; fail closed with the generic invalid-amount error.
        RateRefusalCodes.AMOUNT_DIGITS_UNAVAILABLE ->
            UiText.StringResource(R.string.add_expense_error_amount_invalid)
        // AE-19: the coordinator refused the amount's precision; digits null falls back to save-failed.
        com.yourname.expensetracker.domain.transaction.RateRefusalCodes.AMOUNT_PRECISION_EXCEEDED -> {
            val digits = com.yourname.expensetracker.domain.core.money.CurrencyCode.parse(currency)
                ?.let { com.yourname.expensetracker.domain.currency.CurrencyAmountPolicy.fractionDigits(it) }
            if (digits == null) saveFailedText() else precisionText(currency, digits)
        }
        else -> saveFailedText()
    }

    private fun saveFailedText(): UiText = UiText.StringResource(R.string.add_expense_error_save_failed)

    /** MEC INV-6: maps a bounded coordinator refusal code to the card variant; null for any other code. */
    private fun blockVariantFor(code: String?): CurrencyBlockVariant? = when (code) {
        RateRefusalCodes.NOT_ELIGIBLE_NONE -> CurrencyBlockVariant.NONE
        RateRefusalCodes.NOT_ELIGIBLE_STALE -> CurrencyBlockVariant.STALE
        RateRefusalCodes.NOT_ELIGIBLE_UNSUPPORTED -> CurrencyBlockVariant.UNSUPPORTED
        RateRefusalCodes.NOT_ELIGIBLE_HOME_UNRESOLVED -> CurrencyBlockVariant.HOME_UNRESOLVED
        else -> null
    }

    /**
     * MEC AE-22: a post-tap refusal. Nothing was saved; every input is kept and the card is shown with
     * origin SAVE. A home-unresolved refusal also keeps the B1 save error text (retry via Save).
     */
    private fun AddExpenseState.withSaveRefusal(variant: CurrencyBlockVariant): AddExpenseState {
        val withCard = copy(
            isSaving = false,
            saveResult = null,
            mutation = com.yourname.expensetracker.ui.model.MutationState.idle(),
            currencyRegion = CurrencyRegionUi.Blocked(variant, latestRateDate = null, origin = CurrencyBlockOrigin.SAVE)
        )
        return if (variant == CurrencyBlockVariant.HOME_UNRESOLVED) {
            withCard.withSaveError(UiText.StringResource(R.string.mec_block_home_unresolved))
        } else {
            withCard
        }
    }

    // ---------------------------------------------------------------------------------------------
    // MEC B2a: currency selection, rate preview, refresh and open-setting request
    // ---------------------------------------------------------------------------------------------

    /**
     * Derives [AddExpenseState.selectedCurrency] and [AddExpenseState.currencyOptions] from the home
     * currency and [currencyOverride] (INV-10). An override that is no longer selectable (or equals
     * home) is dropped, so the selection falls back to home. Idempotent.
     */
    private fun applyHomeAndSelection(s: AddExpenseState): AddExpenseState {
        val home = s.homeCurrency
        if (home == null) {
            currencyOverride = null
            return s.copy(selectedCurrency = null, currencyOptions = emptyList())
        }
        val homeCode = CurrencyCode.parse(home)
        // Loan-installments S11a: while installments are ON the control is in INSTALLMENT mode.
        if (s.installments.on) return applyInstallmentSelection(s, home, homeCode)
        if (homeCode == null) {
            currencyOverride = null
            return s.copy(selectedCurrency = home, currencyOptions = listOf(home))
        }
        val options = CurrencySelectionPolicy.selectable(homeCode, CurrencyEntryMode.STANDARD).map { it.code }
        val chosen = currencyOverride?.code?.takeIf { it.code in options && it != homeCode && it.code != home }
        currencyOverride = chosen?.let { CurrencyOverride(it) }
        return s.copy(selectedCurrency = chosen?.code ?: home, currencyOptions = options)
    }

    /**
     * INSTALLMENT-mode selection (MEC UX-Q-6, AE-28; EUR/USD only). A choice that is not an option
     * (for example JPY) is parked in [stashedOverride] and the selection becomes Unset
     * ([AddExpenseInstallmentsState.currencyUnset]); it is never silently replaced by home or another
     * currency. A home outside EUR/USD with no pick is also Unset. Idempotent.
     */
    private fun applyInstallmentSelection(s: AddExpenseState, home: String, homeCode: CurrencyCode?): AddExpenseState {
        val options = if (homeCode == null) {
            emptyList()
        } else {
            CurrencySelectionPolicy.selectable(homeCode, CurrencyEntryMode.INSTALLMENT).map { it.code }
        }
        val current = currencyOverride
        val chosen = current?.code?.takeIf { it.code in options && it.code != home }
        if (current != null && chosen == null) {
            // Not offered in installment mode: park it; the next pass finds the override empty.
            stashedOverride = current
        }
        currencyOverride = chosen?.let { CurrencyOverride(it) }
        val selected: String? = when {
            chosen != null -> chosen.code
            stashedOverride != null -> null
            home in options -> home
            else -> null
        }
        val unset: CurrencyUnsetReason? = when {
            selected != null -> null
            stashedOverride != null -> CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE
            else -> CurrencyUnsetReason.NONE_PROPOSED
        }
        return s.copy(
            selectedCurrency = selected,
            currencyOptions = options,
            installments = s.installments.copy(currencyUnset = unset)
        )
    }

    /**
     * Switches installment mode off (explicit switch-off, or Not mine / Shared / a non-spending type
     * while ON). The plan values are KEPT; the parked STANDARD choice is restored (UX-Q-6), the
     * selection is re-derived in STANDARD mode and the unchanged amount is re-validated (AE-16).
     * Callers re-evaluate the region afterwards.
     */
    private fun leaveInstallmentMode(s: AddExpenseState): AddExpenseState {
        val restored = stashedOverride
        stashedOverride = null
        if (restored != null) currencyOverride = restored
        val off = s.copy(
            installments = s.installments.copy(
                on = false,
                currencyUnset = null,
                repeatClearedByInstallments = false
            )
        )
        val withSelection = applyHomeAndSelection(off)
        val selected = withSelection.selectedCurrency
        return withSelection.copy(
            saveResult = null,
            amountError = if (selected == null) null else revalidatedAmountError(withSelection.amount, selected)
        )
    }

    /**
     * Switches installment mode on: Repeat is cleared (mutually exclusive), the plan uuid is created
     * once per session, and the selection is re-derived in INSTALLMENT mode. Callers re-evaluate the
     * region afterwards.
     */
    private fun enterInstallmentMode(s: AddExpenseState): AddExpenseState {
        val on = s.copy(
            isRecurring = false,
            recurrenceFrequency = RecurrenceFrequency.MONTHLY,
            saveResult = null,
            installments = s.installments.copy(
                on = true,
                planUuid = s.installments.planUuid ?: java.util.UUID.randomUUID().toString(),
                repeatClearedByInstallments = s.isRecurring || s.installments.repeatClearedByInstallments
            )
        )
        val withSelection = applyHomeAndSelection(on)
        val selected = withSelection.selectedCurrency
        return withSelection.copy(
            amountError = if (selected == null) null else revalidatedAmountError(withSelection.amount, selected)
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Loan-installments S11a: the installments switch and plan terms (gated by InstallmentsFeature)
    // ---------------------------------------------------------------------------------------------

    /**
     * Switches "Pay in installments" on or off. A no-op unless [enabled] ([InstallmentsFeature.ENABLED]
     * by default): with the feature off the sub-state can never become ON. Refused while saving, while
     * home is loading, and when the form has a block reason (Not mine, Shared, non-spending): the
     * switch is disabled in the UI with that reason.
     */
    fun setInstallmentsEnabled(on: Boolean, enabled: Boolean = InstallmentsFeature.ENABLED) {
        if (!enabled) return
        val snapshot = _state.value
        if (snapshot.isSaving || snapshot.homeCurrency == null) return
        if (snapshot.installments.on == on) return
        if (on && snapshot.installmentsBlockReason != null) return
        _state.update { current ->
            if (current.installments.on == on) current
            else if (on) enterInstallmentMode(current)
            else leaveInstallmentMode(current)
        }
        reevaluateRegion(invalidate = true)
    }

    private fun editInstallments(change: (AddExpenseInstallmentsState) -> AddExpenseInstallmentsState) {
        _state.update {
            if (!it.installments.on || it.isSaving) it
            else it.copy(installments = change(it.installments), saveResult = null)
        }
    }

    fun setInstallmentCountText(value: String) =
        editInstallments { it.copy(countText = value.filter(Char::isDigit).take(3)) }

    fun setInstallmentSplit(value: InstallmentSplitMethod) = editInstallments { it.copy(split = value) }

    fun setInstallmentFixedText(value: String) =
        editInstallments { it.copy(fixedText = value.take(DeclareForm.MAX_AMOUNT_CHARS)) }

    /** The plan name; blank returns to following the merchant. */
    fun setInstallmentName(value: String) = editInstallments {
        it.copy(nameText = value.take(DeclareForm.MAX_NAME_CHARS).takeIf { text -> text.isNotBlank() })
    }

    /** The first payment date as an epoch day; null returns to the default (purchase + 1 month). */
    fun setInstallmentFirstDue(epochDay: Long?) = editInstallments { it.copy(firstDueEpochDay = epochDay) }

    fun setInstallmentMode(value: InstallmentFulfilmentMode) = editInstallments { it.copy(mode = value) }

    /**
     * MEC INV-10/11: the user picked [code] in the currency control. Ignored unless it is selectable
     * for the home currency. Keeps the amount text, re-validates it for the new currency (AE-16),
     * turns Repeat off and clears it for a foreign currency (INV-16), and re-evaluates the rate.
     */
    fun selectCurrency(code: String) {
        val snapshot = _state.value
        val home = snapshot.homeCurrency ?: return
        val homeCode = CurrencyCode.parse(home)
        val chosen = CurrencyCode.parse(code) ?: return
        // Loan-installments S11a: INSTALLMENT mode (EUR/USD only) while installments are ON.
        val mode = if (snapshot.installments.on) CurrencyEntryMode.INSTALLMENT else CurrencyEntryMode.STANDARD
        val allowed = if (homeCode == null) chosen.code == home else
            CurrencySelectionPolicy.isSelectable(chosen, homeCode, mode)
        if (!allowed) return

        // An explicit pick in installment mode supersedes the parked STANDARD choice.
        if (mode == CurrencyEntryMode.INSTALLMENT) stashedOverride = null
        currencyOverride = chosen.takeIf { it.code != home }?.let { CurrencyOverride(it) }
        _state.update { current ->
            val withSelection = applyHomeAndSelection(current)
            val selected = withSelection.selectedCurrency
            val foreign = withSelection.isForeignSelected
            withSelection.copy(
                previewAnnounceKey = current.previewAnnounceKey + 1,
                saveResult = null,
                homeChangeClearedNote = false,
                amountError = if (selected == null) null else revalidatedAmountError(current.amount, selected),
                isRecurring = if (foreign) false else withSelection.isRecurring,
                recurrenceFrequency = if (foreign) RecurrenceFrequency.MONTHLY else withSelection.recurrenceFrequency,
                repeatClearedByCurrency = foreign && (withSelection.repeatClearedByCurrency || current.isRecurring)
            )
        }
        reevaluateRegion(invalidate = true)
    }

    /**
     * AE-16: re-validates the unchanged amount text for [currency]. A blank or unparsable text, or a
     * valid amount, returns null (the existing save-time check still reports a blank or invalid amount).
     */
    private fun revalidatedAmountError(amountText: String, currency: String): UiText? {
        if (amountText.isBlank()) return null
        val parsed = com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(amountText) ?: return null
        if (parsed <= 0) return null
        return checkAmountForCurrency(parsed, currency)
    }

    /** The bounded invalid-amount kind for the region, or null when the amount passes (INV-12/13/14). */
    private fun amountInvalidKind(amount: Double, currency: CurrencyCode): CurrencyAmountInvalidKind? =
        when (val check = CurrencyAmountPolicy.check(amount, currency)) {
            com.yourname.expensetracker.domain.currency.AmountCheck.Ok -> null
            com.yourname.expensetracker.domain.currency.AmountCheck.NotPositive -> CurrencyAmountInvalidKind.NOT_POSITIVE
            // OD-6: digits unknown. Never assume 2 digits; treat as an invalid amount.
            com.yourname.expensetracker.domain.currency.AmountCheck.DigitsUnavailable -> CurrencyAmountInvalidKind.NOT_POSITIVE
            is com.yourname.expensetracker.domain.currency.AmountCheck.PrecisionExceeded -> CurrencyAmountInvalidKind.PRECISION_EXCEEDED
            is com.yourname.expensetracker.domain.currency.AmountCheck.AboveCap -> CurrencyAmountInvalidKind.ABOVE_CAP
        }

    /**
     * MEC INV-3/INV-15: recomputes [AddExpenseState.currencyRegion]. Latest wins: the previous
     * evaluation is cancelled. Home and Loading need no evaluation. For a foreign currency a valid
     * amount (or none) is evaluated through the single read-only [UserPresentRatePreview]; "Checking"
     * ([CurrencyRegionUi.Evaluating]) appears only after [EVALUATING_DELAY_MS]. No fetch, no logging
     * of amounts, currencies, rates or dates.
     *
     * @param invalidate true when the currency, date, home or zone changed: the displayed result is
     * dropped to [CurrencyRegionUi.Pending] immediately so a stale amount is never shown.
     * @param refreshCompleted true when called after a user-started refresh finished.
     * @param keepSaveBlock true after a post-tap refusal: a SAVE-origin Blocked card stays unless the
     * re-read is also Blocked (then only its details are updated). Cleared by the next user input.
     */
    private fun reevaluateRegion(
        invalidate: Boolean,
        showEvaluating: Boolean = true,
        refreshCompleted: Boolean = false,
        origin: CurrencyBlockOrigin = CurrencyBlockOrigin.PREVIEW,
        keepSaveBlock: Boolean = false
    ) {
        evaluationJob?.cancel()
        evaluationJob = null

        val s = _state.value
        val home = s.homeCurrency
        val selected = s.selectedCurrency
        val today = todayFor(sessionZone ?: java.time.ZoneId.systemDefault())
        if (home != null && selected == null && s.installments.currencyUnset != null) {
            // Loan-installments S11a (MEC AE-28): installment mode has no selectable currency; the
            // control shows Unset and Save stays blocked until a pick (no preview, no evaluation).
            _state.update {
                it.copy(currencyRegion = CurrencyRegionUi.Unset(s.installments.currencyUnset), ratesToday = today)
            }
            return
        }
        if (home == null || selected == null) {
            _state.update { it.copy(currencyRegion = CurrencyRegionUi.Loading, ratesToday = today) }
            return
        }
        if (selected == home) {
            _state.update { it.copy(currencyRegion = CurrencyRegionUi.Home, ratesToday = today, rateRefresh = settledRefresh(it, false)) }
            return
        }
        val homeCode = CurrencyCode.parse(home)
        val selectedCode = CurrencyCode.parse(selected)
        if (homeCode == null || selectedCode == null) {
            val variant = if (homeCode == null) CurrencyBlockVariant.HOME_UNRESOLVED else CurrencyBlockVariant.UNSUPPORTED
            _state.update {
                it.copy(
                    currencyRegion = CurrencyRegionUi.Blocked(variant, null, origin),
                    ratesToday = today,
                    rateRefresh = settledRefresh(it, refreshCompleted)
                )
            }
            return
        }

        val amountText = s.amount
        val parsedAmount = if (amountText.isBlank()) null
        else com.yourname.expensetracker.ui.util.AmountInputSanitizer.parseOrNull(amountText)
        val invalidKind = when {
            amountText.isBlank() -> null
            parsedAmount == null -> CurrencyAmountInvalidKind.NOT_POSITIVE
            else -> amountInvalidKind(parsedAmount, selectedCode)
        }
        if (invalidKind != null) {
            _state.update {
                it.copy(currencyRegion = CurrencyRegionUi.AmountInvalid(invalidKind), ratesToday = today, rateRefresh = settledRefresh(it, refreshCompleted))
            }
            return
        }

        if (invalidate) {
            _state.update { it.copy(currencyRegion = CurrencyRegionUi.Pending, ratesToday = today) }
        }

        val atMillis = s.date
        val zone = sessionZone ?: java.time.ZoneId.systemDefault()
        evaluationJob = viewModelScope.launch {
            val marker = if (showEvaluating) {
                launch {
                    delay(EVALUATING_DELAY_MS)
                    _state.update { it.copy(currencyRegion = CurrencyRegionUi.Evaluating) }
                }
            } else {
                null
            }
            // INV-1b-5: the remedy is copied from RateRemedyPolicy; the VM supplies only today and the flag.
            var decision = RateRemedyDecision(RateRemedy.REFRESH_LATEST)
            val region: CurrencyRegionUi = try {
                val result = userPresentRatePreview.preview(
                    parsedAmount = parsedAmount ?: 1.0,
                    currency = selectedCode,
                    home = homeCode,
                    expenseAtMillis = atMillis,
                    entryZone = zone
                )
                decision = remedyFor(result, atMillis, zone, today)
                regionFor(result, hasAmount = parsedAmount != null, origin = origin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // INV-19: bounded code only. An unreadable rate store fails closed: no preview, blocked.
                Timber.w(LOG_RATE_PREVIEW_FAILED)
                CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, origin)
            }
            marker?.cancel()
            ensureActive()
            // F-B2-A11Y-1: after a history success the preview line is announced once (latest inputs win).
            // F-A11Y-LATCH-PURE: VM-side one-shot flag; reviewed at the 1b candidate.
            // Cleared on the first evaluation after a Stored outcome, whatever the region; the key is bumped
            // only when the Eligible region is actually applied (not when `keep` is true).
            val announcePending = historyAnnouncePending
            historyAnnouncePending = false
            val doneStatus = doneStatusFor(region, decision, atMillis, zone)
            _state.update {
                val current = it.currencyRegion
                val keep = keepSaveBlock && region !is CurrencyRegionUi.Blocked &&
                    current is CurrencyRegionUi.Blocked && current.origin == CurrencyBlockOrigin.SAVE
                val announce = announcePending && !keep && region is CurrencyRegionUi.Eligible
                it.copy(
                    currencyRegion = if (keep) current else region,
                    ratesToday = today,
                    rateRefresh = settledRefresh(it, refreshCompleted && region is CurrencyRegionUi.Blocked),
                    rateRemedy = decision,
                    historyStatus = when {
                        it.historyStatus == CurrencyHistoryStatus.RUNNING -> CurrencyHistoryStatus.RUNNING
                        it.historyHomeUnavailable -> CurrencyHistoryStatus.FAILED_HOME_UNAVAILABLE
                        else -> doneStatus
                    },
                    previewAnnounceKey = if (announce) it.previewAnnounceKey + 1 else it.previewAnnounceKey
                )
            }
        }
    }

    /**
     * The refresh status after an evaluation: RUNNING is kept; a finished refresh that is still blocked
     * becomes DONE_STILL_BLOCKED; any other settled evaluation clears an earlier outcome to IDLE.
     */
    private fun settledRefresh(current: AddExpenseState, doneStillBlocked: Boolean): CurrencyRefreshStatus = when {
        current.rateRefresh == CurrencyRefreshStatus.RUNNING -> CurrencyRefreshStatus.RUNNING
        doneStillBlocked -> CurrencyRefreshStatus.DONE_STILL_BLOCKED
        else -> CurrencyRefreshStatus.IDLE
    }

    /** INV-1b-5: the remedy for a blocked result; every other result needs none. Pure; no logging. */
    private fun remedyFor(
        result: UserPresentRatePreviewResult,
        atMillis: Long,
        zone: java.time.ZoneId,
        today: LocalDate
    ): RateRemedyDecision = when (result) {
        is UserPresentRatePreviewResult.Blocked -> RateRemedyPolicy.remedy(
            eligibility = result.eligibility,
            localDate = java.time.Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate(),
            latestStoredPairDate = result.latestStoredPairDate,
            today = today,
            historyDownloadedThisSession = historyDownloadedThisSession
        )
        else -> RateRemedyDecision(RateRemedy.NONE)
    }

    /**
     * D-1b-5: still blocked after a history download in this session. The entry date before the earliest
     * stored ECB date is DONE_TOO_OLD; any other case is DONE_NO_RATE. Anything else settles to IDLE.
     */
    private fun doneStatusFor(
        region: CurrencyRegionUi,
        decision: RateRemedyDecision,
        atMillis: Long,
        zone: java.time.ZoneId
    ): CurrencyHistoryStatus {
        if (region !is CurrencyRegionUi.Blocked || decision.noRemedyReason != NoRemedyReason.HISTORY_ALREADY_DOWNLOADED) {
            return CurrencyHistoryStatus.IDLE
        }
        val earliest = historyEarliestDate
        val entryDate = java.time.Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
        return if (earliest != null && entryDate.isBefore(earliest)) {
            CurrencyHistoryStatus.DONE_TOO_OLD
        } else {
            CurrencyHistoryStatus.DONE_NO_RATE
        }
    }

    private fun todayFor(zone: java.time.ZoneId): LocalDate =
        java.time.Instant.ofEpochMilli(timeProvider.now()).atZone(zone).toLocalDate()

    /** MEC AE-38: the preview text is the shared formatter applied to the exact snapshot amount. */
    private fun regionFor(
        result: UserPresentRatePreviewResult,
        hasAmount: Boolean,
        origin: CurrencyBlockOrigin
    ): CurrencyRegionUi = when (result) {
        UserPresentRatePreviewResult.NotApplicableHome -> CurrencyRegionUi.Home
        is UserPresentRatePreviewResult.ConvertedPreview -> CurrencyRegionUi.Eligible(
            rateDate = result.rateDate,
            sourceLabel = result.sourceLabel,
            formattedAmountInHome = if (hasAmount) CurrencyFormatter.formatMoney(result.amountInHome, result.home.code) else null,
            amountUnavailable = false
        )
        // INV-15: eligible rate but no computable amount. Never shown as zero; the date still is.
        is UserPresentRatePreviewResult.AmountUnavailable -> CurrencyRegionUi.Eligible(
            rateDate = result.rateDate,
            sourceLabel = result.sourceLabel,
            formattedAmountInHome = null,
            amountUnavailable = hasAmount
        )
        is UserPresentRatePreviewResult.Blocked -> when (val eligibility = result.eligibility) {
            is RateEligibility.Stale ->
                CurrencyRegionUi.Blocked(CurrencyBlockVariant.STALE, eligibility.latestRateDate, origin)
            RateEligibility.None ->
                CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, origin)
            is RateEligibility.Unsupported ->
                CurrencyRegionUi.Blocked(CurrencyBlockVariant.UNSUPPORTED, null, origin)
            // Not produced by the use case for a blocked result; fail closed.
            RateEligibility.Home, is RateEligibility.Eligible ->
                CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, origin)
        }
    }

    /**
     * MEC INV-20: user-started "Refresh rates". Runs only when the setting is On and home is loaded;
     * the only network path is [CurrencyRatesRepository.refresh], which is privacy-gated. The VM never
     * enables the setting. On success the region is re-evaluated; failures map to bounded statuses.
     */
    fun refreshRates() {
        val snapshot = _state.value
        val home = snapshot.homeCurrency ?: return
        if (snapshot.ratesSetting != CurrencyRatesSetting.ON) return
        if (!snapshot.isForeignSelected) return
        if (snapshot.rateRefresh == CurrencyRefreshStatus.RUNNING) return
        if (snapshot.historyStatus == CurrencyHistoryStatus.RUNNING) return

        _state.update { it.copy(rateRefresh = CurrencyRefreshStatus.RUNNING) }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            var status = CurrencyRefreshStatus.IDLE
            var setting: CurrencyRatesSetting? = null
            var refreshed = false
            try {
                when (currencyRatesRepository.refresh(home)) {
                    is CurrencyRateRefreshOutcome.Refreshed -> refreshed = true
                    // The setting was turned off meanwhile: show the Off variant, no failure line.
                    CurrencyRateRefreshOutcome.DisabledByPrivacy -> setting = CurrencyRatesSetting.OFF
                    is CurrencyRateRefreshOutcome.PrivacyCheckFailed -> setting = CurrencyRatesSetting.UNKNOWN
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                Timber.w(LOG_RATE_REFRESH_FAILED)
                status = CurrencyRefreshStatus.FAILED_OFFLINE
            } catch (e: Exception) {
                // INV-19: bounded code only; the exception message is matched by equality, never shown.
                Timber.w(LOG_RATE_REFRESH_FAILED)
                status = if (e.message == REFRESH_HOME_QUOTE_UNAVAILABLE || e.message == REFRESH_INVALID_HOME_CURRENCY) {
                    CurrencyRefreshStatus.FAILED_HOME_UNAVAILABLE
                } else {
                    CurrencyRefreshStatus.FAILED
                }
            }
            _state.update {
                it.copy(
                    rateRefresh = status,
                    ratesSetting = setting ?: it.ratesSetting
                )
            }
            if (refreshed) {
                reevaluateRegion(invalidate = false, showEvaluating = false, refreshCompleted = true)
            }
        }
    }

    /**
     * MEC 1b INV-1b-3: user-started "Download rate history". The only caller of
     * [CurrencyRatesRepository.downloadRecentHistory]; never reached from preview, save, init or any
     * automatic path. Single job: ignored while RUNNING (or a refresh runs). The VM never enables the
     * setting; the repository is privacy-gated. Outcomes map per ux D-1b-5.
     */
    fun onDownloadRateHistory() {
        val snapshot = _state.value
        val home = snapshot.homeCurrency ?: return
        if (snapshot.ratesSetting != CurrencyRatesSetting.ON) return
        if (!snapshot.isForeignSelected) return
        if (snapshot.rateFixAction != CurrencyRateFixAction.DOWNLOAD_HISTORY) return
        if (snapshot.historyStatus == CurrencyHistoryStatus.RUNNING || historyJob?.isActive == true) return
        if (snapshot.rateRefresh == CurrencyRefreshStatus.RUNNING) return
        // Spec 1b.10: a history download is illegal while saving.
        if (snapshot.isSaving) return

        _state.update { it.copy(historyStatus = CurrencyHistoryStatus.RUNNING) }
        historyJob = viewModelScope.launch {
            var status = CurrencyHistoryStatus.IDLE
            var setting: CurrencyRatesSetting? = null
            var stored = false
            try {
                val outcome = currencyRatesRepository.downloadRecentHistory(home)
                // A repository that ignored Cancel must not overwrite CANCELLED (rethrown below).
                ensureActive()
                when (outcome) {
                    is CurrencyRateHistoryOutcome.Stored -> {
                        stored = true
                        historyDownloadedThisSession = true
                        historyEarliestDate = outcome.earliestDate
                    }
                    // The setting was turned off meanwhile: show the Off variant, no failure line.
                    CurrencyRateHistoryOutcome.DisabledByPrivacy -> setting = CurrencyRatesSetting.OFF
                    is CurrencyRateHistoryOutcome.PrivacyCheckFailed -> setting = CurrencyRatesSetting.UNKNOWN
                    CurrencyRateHistoryOutcome.AlreadyInProgress -> status = CurrencyHistoryStatus.FAILED
                    is CurrencyRateHistoryOutcome.Failed -> status = historyFailureStatus(outcome.reason)
                    CurrencyRateHistoryOutcome.Cancelled -> status = CurrencyHistoryStatus.CANCELLED
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // INV-19: bounded code only; the exception is never attached.
                Timber.w(LOG_RATE_HISTORY_FAILED)
                status = CurrencyHistoryStatus.FAILED
            }
            if (stored) historyAnnouncePending = true
            _state.update {
                it.copy(
                    historyStatus = status,
                    historyHomeUnavailable = it.historyHomeUnavailable ||
                        status == CurrencyHistoryStatus.FAILED_HOME_UNAVAILABLE,
                    ratesSetting = setting ?: it.ratesSetting
                )
            }
            if (stored) {
                // Latest inputs win; the region becomes Eligible, or Blocked with a DONE_* status.
                reevaluateRegion(invalidate = false, showEvaluating = false)
            }
        }
    }

    /** INV-1b-21: user Cancel, offered only while RUNNING. The job's CancellationException is rethrown. */
    fun onCancelRateHistory() {
        if (_state.value.historyStatus != CurrencyHistoryStatus.RUNNING) return
        historyJob?.cancel()
        historyJob = null
        _state.update { it.copy(historyStatus = CurrencyHistoryStatus.CANCELLED) }
    }

    /** D-1b-5: bounded failure reason to UX status. */
    private fun historyFailureStatus(reason: RateHistoryFailureReason): CurrencyHistoryStatus = when (reason) {
        RateHistoryFailureReason.OFFLINE -> CurrencyHistoryStatus.FAILED_OFFLINE
        RateHistoryFailureReason.INVALID_HOME_CURRENCY,
        RateHistoryFailureReason.HOME_QUOTE_UNAVAILABLE -> CurrencyHistoryStatus.FAILED_HOME_UNAVAILABLE
        RateHistoryFailureReason.TIMEOUT,
        RateHistoryFailureReason.HTTP_ERROR,
        RateHistoryFailureReason.PAYLOAD_TOO_LARGE,
        RateHistoryFailureReason.PAYLOAD_EMPTY,
        RateHistoryFailureReason.PAYLOAD_MALFORMED,
        RateHistoryFailureReason.PAYLOAD_OUT_OF_BOUNDS,
        RateHistoryFailureReason.STORE_FAILED -> CurrencyHistoryStatus.FAILED
    }

    /** Q-5: leaving the sheet or a reset stops a running download and drops the session-only history data. */
    private fun clearHistorySession() {
        historyJob?.cancel()
        historyJob = null
        historyDownloadedThisSession = false
        historyEarliestDate = null
        historyAnnouncePending = false
    }

    /**
     * MEC D-11: the user tapped "Open exchange-rate setting". If an amount was entered the host must
     * confirm first ([CurrencyOpenSettingRequest.CONFIRM_NEEDED]); otherwise navigate now. The VM never
     * enables the setting.
     */
    fun requestOpenExchangeRateSetting() {
        _state.update {
            it.copy(
                openSettingRequest = if (it.amount.isNotBlank()) {
                    CurrencyOpenSettingRequest.CONFIRM_NEEDED
                } else {
                    CurrencyOpenSettingRequest.NAVIGATE
                }
            )
        }
    }

    /**
     * The user confirmed leaving the form to open the setting. MEC C-1: the confirmation copy promises the
     * entry is discarded, so reset first, then raise NAVIGATE exactly like the blank-amount path.
     */
    fun confirmOpenExchangeRateSetting() {
        if (_state.value.openSettingRequest != CurrencyOpenSettingRequest.CONFIRM_NEEDED) return
        reset()
        _state.update { it.copy(openSettingRequest = CurrencyOpenSettingRequest.NAVIGATE) }
    }

    /** The user declined the confirmation or the host finished navigating: clears the request. */
    fun consumeOpenSettingRequest() {
        _state.update { it.copy(openSettingRequest = CurrencyOpenSettingRequest.NONE) }
    }

    private fun AddExpenseState.withSaveError(message: UiText): AddExpenseState = copy(
        isSaving = false,
        saveResult = SaveResult.Error(message),
        mutation = com.yourname.expensetracker.ui.model.MutationState.error("save", message)
    )


    /**
     * Quick-add Fill (C3, spec 5.2). The typed [line] exists only as this argument and inside the
     * launched job (INV-12); it is never stored in a field or in [AddExpenseState].
     *
     * Gate (INV-17): blank line, unknown home currency or a save in flight is a no-op. Otherwise the
     * one-shot initial-values channel is consumed (INV-8), the previous Fill job and the suggestion
     * search are cancelled and suggestions hidden (INV-9, INV-18), and a single job parses, runs the
     * read-only learned-category lookup only when the contract asks for it, and applies the result in
     * one atomic [MutableStateFlow.update] that re-evaluates fillability at apply time.
     * Not a suspend function by design (S0 D-3).
     */
    fun fill(line: String) {
        val snapshot = _state.value
        val homeCurrency = snapshot.homeCurrency
        if (line.isBlank() || homeCurrency == null || snapshot.isSaving) return

        initialValuesApplied = true
        fillJob?.cancel()
        searchJob?.cancel()
        searchJob = null
        _state.update { it.copy(suggestions = emptyList(), showSuggestions = false) }

        val nowMs = timeProvider.now()
        val categoryRefs = categories.value.map { CategoryRef(id = it.id, name = it.name) }
        // Owner-approvals item 63: parse synchronously so the launched job captures only the parsed
        // result, the category refs and nowMs - never the QuickAddInput or the typed line.
        // The parser is pure and total (golden-tested); only the C2 read can fail.
        val parsed = quickAddParser.parse(
            QuickAddInput(
                line = line,
                nowEpochMs = nowMs,
                zone = java.time.ZoneId.systemDefault(),
                // MEC 80c: the SELECTED currency is the "home" the parser compares against, so "12 USD"
                // fills when USD is selected and "12 GBP" stays foreign-blocked. Fill never changes the
                // selection (INV-11).
                homeCurrency = snapshot.selectedCurrency ?: homeCurrency,
                categories = categoryRefs
            )
        )

        fillJob = viewModelScope.launch {
            var learned: CategoryMatch? = null
            val merchantValue = (parsed.merchant as? FieldParse.Value)?.value
            if (merchantValue != null &&
                QuickAddFillContract.wantsLearnedLookup(parsed, _state.value.toFillFields())
            ) {
                try {
                    when (val lookup = quickAddLearnedCategoryLookup.lookup(merchantValue, categoryRefs)) {
                        is LearnedCategoryLookupResult.Found -> learned = lookup.match
                        LearnedCategoryLookupResult.Absent -> Unit
                        LearnedCategoryLookupResult.Failed -> {
                            // INV-13: bounded code only; no throwable, merchant or key. Category stays Absent.
                            Timber.w(LOG_QUICK_ADD_CATEGORY_LOOKUP_FAILED)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // INV-13: bounded code only; no throwable, merchant or key. Category stays Absent.
                    Timber.w(LOG_QUICK_ADD_CATEGORY_LOOKUP_FAILED)
                }
            }

            // INV-18: a cancelled (superseded) job must never apply a stale result.
            ensureActive()
            var applied: QuickAddFillApplication? = null
            _state.update { current ->
                if (current.isSaving) {
                    applied = null
                    current
                } else {
                    val application = QuickAddFillContract.apply(current.toFillFields(), parsed, learned, nowMs)
                    applied = application
                    current.withFillApplication(application)
                }
            }
            applied?.let {
                logFillOutcome(it.outcome)
                // MEC INV-3: Fill may have written the amount and/or the date.
                if (QuickAddField.AMOUNT in it.writtenFields || QuickAddField.DATE in it.writtenFields) {
                    // R-3: an AMOUNT write also invalidates, so no stale converted figure stays visible.
                    reevaluateRegion(invalidate = true)
                }
            }
        }
    }

    private fun logFillOutcome(outcome: OutcomeCode) {
        when (outcome) {
            OutcomeCode.FILL_FULL -> Timber.i(LOG_FILL_FULL)
            OutcomeCode.FILL_PARTIAL -> Timber.i(LOG_FILL_PARTIAL)
            OutcomeCode.FILL_NONE -> Timber.i(LOG_FILL_NONE)
            OutcomeCode.FOREIGN_CURRENCY_BLOCKED -> Timber.i(LOG_FOREIGN_CURRENCY_BLOCKED)
        }
    }

    /** A user gesture on [field]: origin USER and its flag cleared (spec 5.1). */
    private fun AddExpenseState.withUserEdit(field: QuickAddField): AddExpenseState = copy(
        fieldOrigins = fieldOrigins + (field to FieldOrigin.USER),
        fieldFlags = fieldFlags - field
    )

    private fun AddExpenseState.toFillFields(): QuickAddFormFields = QuickAddFormFields(
        amount = amount,
        merchant = merchant,
        dateMs = date,
        categoryId = selectedCategoryId,
        payment = paymentMethod,
        origins = fieldOrigins,
        flags = fieldFlags,
        categorySource = categoryFillSource
    )

    private fun AddExpenseState.withFillApplication(application: QuickAddFillApplication): AddExpenseState {
        val form = application.form
        val written = application.writtenFields
        return copy(
            amount = form.amount,
            merchant = form.merchant,
            date = form.dateMs,
            selectedCategoryId = form.categoryId,
            paymentMethod = form.payment,
            fieldOrigins = form.origins,
            fieldFlags = form.flags,
            categoryFillSource = form.categorySource,
            lineHints = application.lineHints,
            lastFillOutcome = application.outcome,
            fillSummary = QuickAddUiMapper.snapshotOf(
                outcome = application.outcome,
                hints = application.lineHints,
                origins = form.origins,
                flags = form.flags
            ),
            // Step 5: a stale save result and the errors of the fields Fill rewrote are cleared.
            saveResult = null,
            amountError = if (QuickAddField.AMOUNT in written) null else amountError,
            merchantError = if (QuickAddField.MERCHANT in written) null else merchantError
        )
    }

    /**
     * MEC AE-34: called by the sheet when it opens. Captures the device zone once per sheet session
     * (null-guarded) so a zone change while the sheet is open cannot move the expense's local date.
     * A configuration change keeps the captured zone; [onSheetLeft] and [reset] clear it.
     */
    fun onSheetOpened() {
        if (sessionZone == null) {
            // MEC R-1(b): a retained draft with a NON-home selection is discarded whole (existing reset
            // path), so its amount is never re-denominated into the home currency. A home draft is kept.
            val retained = _state.value
            // Loan-installments S11a: a retained draft with installments ON and a non-home (or Unset)
            // selection, or a parked STANDARD choice, is discarded whole too (no re-denomination).
            val retainedNonHome = currencyOverride != null ||
                stashedOverride != null ||
                (retained.installments.on && retained.selectedCurrency != retained.homeCurrency) ||
                (retained.selectedCurrency != null && retained.selectedCurrency != retained.homeCurrency)
            if (retainedNonHome) {
                reset()
            }
            sessionZone = java.time.ZoneId.systemDefault()
            sessionZoneTick.value = sessionZone
            // MEC: a new sheet session starts on the home currency (Selected(home) on every open).
            currencyOverride = null
            stashedOverride = null
            _state.update { applyHomeAndSelection(it).copy(repeatClearedByCurrency = false) }
            // MEC INV-3: the captured zone may differ from the one used so far.
            reevaluateRegion(invalidate = true)
        }
    }

    /**
     * MEC AE-34: called when the sheet leaves composition for any reason other than a configuration
     * change (e.g. host navigation without [reset]), so the next session re-captures a fresh zone.
     */
    fun onSheetLeft() {
        sessionZone = null
        sessionZoneTick.value = null
        // MEC 1b Q-5: a running download is cancelled with no status; the session flag and date are dropped.
        clearHistorySession()
        _state.update {
            it.copy(
                historyStatus = if (it.historyStatus == CurrencyHistoryStatus.RUNNING) {
                    CurrencyHistoryStatus.IDLE
                } else {
                    it.historyStatus
                },
                historyHomeUnavailable = false
            )
        }
    }

    fun reset() {
        sessionZone = null
        sessionZoneTick.value = null
        searchJob?.cancel()
        searchJob = null
        fillJob?.cancel()
        fillJob = null
        evaluationJob?.cancel()
        evaluationJob = null
        refreshJob?.cancel()
        refreshJob = null
        // MEC 1b: the fresh state below drops historyStatus; the job, session flag and date go here.
        clearHistorySession()
        currencyOverride = null
        // Loan-installments S11a: the installment sub-state is session-only; the fresh AddExpenseState
        // below drops it (and the plan uuid), so every dismiss path and a confirmed NAVIGATE discard it.
        stashedOverride = null
        initialValuesApplied = false
        // S5-002: Preserve loaded currency — do not reset to null sentinel
        val loadedCurrency = _state.value.homeCurrency
        // MEC: the currency choice is session-only; the next open starts on home. The rates setting is
        // a live observation of stored settings and is kept.
        val keptSetting = _state.value.ratesSetting
        // F-B2-A11Y-1: the announce key stays monotonic across a reset (a drop would look like a change).
        val keptAnnounceKey = _state.value.previewAnnounceKey
        _state.value = applyHomeAndSelection(
            AddExpenseState(
                date = timeProvider.now(),
                homeCurrency = loadedCurrency,
                ratesSetting = keptSetting,
                previewAnnounceKey = keptAnnounceKey
            )
        ).let { fresh ->
            fresh.copy(
                currencyRegion = if (fresh.homeCurrency == null) CurrencyRegionUi.Loading else CurrencyRegionUi.Home,
                ratesToday = todayFor(java.time.ZoneId.systemDefault())
            )
        }
    }

    fun setInitialValuesIfBlank(amount: String? = null, merchant: String? = null) {
        if (initialValuesApplied) return

        _state.update { current ->
            val amountIsBlank = current.amount.isBlank()
            val merchantIsBlank = current.merchant.isBlank()

            if (!amountIsBlank || !merchantIsBlank) {
                // S5-021R: Mark consumed even when skipped due to dirty form
                // Prevents prefill applying later if user clears fields
                initialValuesApplied = true
                return@update current
            }

            initialValuesApplied = true
            // S5-020: Sanitize prefilled amount — same path as manual input
            // R16: the clipboard parser now hands over canonical text ("12.50"; ambiguous values such as
            // "1,234" are rejected there), but a direct caller may still pass machine-style text, so
            // convert to a number first and format locale-independently, as selectSuggestion does.
            val prefilledAmount = amount?.let { raw ->
                AmountUtils.parseAmount(raw)?.let { parsed ->
                    com.yourname.expensetracker.ui.util.AmountInputSanitizer.sanitize(
                        String.format(java.util.Locale.US, "%.2f", parsed)
                    )
                }
            }
            val prefilledMerchant = merchant?.take(100)?.trim()
            var origins = current.fieldOrigins
            // Spec 5.1: PREFILL is recorded only for a prefill that actually wrote a non-blank value.
            if (!prefilledAmount.isNullOrBlank()) origins = origins + (QuickAddField.AMOUNT to FieldOrigin.PREFILL)
            if (!prefilledMerchant.isNullOrBlank()) origins = origins + (QuickAddField.MERCHANT to FieldOrigin.PREFILL)
            current.copy(
                amount = prefilledAmount ?: current.amount,
                merchant = prefilledMerchant ?: current.merchant,
                fieldOrigins = origins
            )
        }
    }

    fun clearSaveResult() {
        _state.update { it.copy(saveResult = null) }
    }
}
