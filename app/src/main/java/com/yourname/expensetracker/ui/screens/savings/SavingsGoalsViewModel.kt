package com.yourname.expensetracker.ui.screens.savings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.repository.SAVINGS_SWEEP_MONTH_ALREADY_ACCEPTED
import com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepository
import com.yourname.expensetracker.data.repository.SavingsSweepAcceptanceCoordinator
import com.yourname.expensetracker.data.repository.SavingsSweepAcceptanceResult
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.util.guardedWrite
import com.yourname.expensetracker.domain.savings.*
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsRecommendation
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import javax.inject.Inject

/** Quick-add step in the goal's own currency; capped at the remaining amount by [SavingsGoalsViewModel.quickAdd]. */
private const val QUICK_ADD_AMOUNT = 10.0

data class SavingsGoalsState(
 val goals: List<SavingsGoal> = emptyList(),
 val smartRecommendations: List<SmartRecommendation> = emptyList(),
 val smartComputationResult: SmartSavingsComputationResult? = null,
 val lifestyleRecommendation: LifestyleSavingsRecommendation? = null,
 val sweepRecommendation: SavingsSweepRecommendation? = null,
 val sweepUnavailableReason: String? = null,
 val contributionHistoryWriteFailure: String? = null,
 val sweepComputationResult: SavingsSweepComputationResult? = null,
 val isSweepAvailable: Boolean = false,
 val streak: SavingsStreak? = null,
 val achievements: List<SavingsAchievement> = emptyList(),
 val userLevel: Int? = null,
 val levelTitle: String = "Savings Rookie",
 val isLoading: Boolean = false,
 val selectedGoal: SavingsGoal? = null,
 val totalSaved: Double? = null,
 val totalSavedResult: MoneyAggregateResult? = null,
	/**
	 * Placeholder default; overridden by [CurrencySettingsRepository.homeCurrency] during init.
	 *
	 * ## Acceptable hardcoded "EUR"
	 * This initial value is immediately replaced by the repository flow in the
	 * ViewModel's `init` block. It only serves as a non-null default before the
	 * async home-currency load completes. See [CURR-6] in MASTER-ISSUE-REGISTRY.
	 */
	val homeCurrency: String = "EUR",
	/** Delete overlay (UX-3). At most one flow is active; owned by the VM so it survives rotation. */
	val deleteFlow: DeleteFlowState = DeleteFlowState.Idle
) {
 val smartUnavailableReason: String? get() =
     (smartComputationResult as? SmartSavingsComputationResult.Unavailable)?.reasonCode
 val moneyTotalSaved: MoneyAmount? get() =
     (totalSavedResult as? MoneyAggregateResult.Available)?.aggregate
         ?.takeIf { !it.isPartial && it.conversionQuality == ConversionQuality.COMPLETE }
         ?.let { MoneyAmount(it.displayAmount, it.displayCurrency) }

 /** Universal contract: typed loadable state. */
 val loadableState: com.yourname.expensetracker.ui.model.LoadableUiState<List<SavingsGoal>>
     get() = when {
         isLoading -> com.yourname.expensetracker.ui.model.LoadableUiState.Loading
         goals.isEmpty() -> com.yourname.expensetracker.ui.model.LoadableUiState.Empty(
             com.yourname.expensetracker.domain.model.UiText.DynamicString("No savings goals yet")
         )
         else -> com.yourname.expensetracker.ui.model.LoadableUiState.Data(goals)
     }
}

/** One-shot events emitted by [SavingsGoalsViewModel] (R18b). */
sealed interface SavingsGoalsEvent {
    /** Emitted only after the goal write completed successfully. */
    object GoalCreated : SavingsGoalsEvent

    /** Emitted when a write was rejected because backup/restore/reset holds the write barrier. */
    data class WriteBlocked(val message: UiText) : SavingsGoalsEvent

    /** Emitted after a goal was deleted, or was already gone (AlreadyAbsent). Carries no payload. */
    object GoalDeleted : SavingsGoalsEvent

    /** Emitted when a delete was refused or failed. Bounded enum only: no text, id, name or amount. */
    data class DeleteRejected(val reason: DeleteRejectReason) : SavingsGoalsEvent
}

/** Bounded, text-free reason carried by [SavingsGoalsEvent.DeleteRejected]. */
enum class DeleteRejectReason { NOT_EMPTY, HISTORY_UNAVAILABLE, GENERIC }

/**
 * Delete overlay state held in [SavingsGoalsState]. [Confirm.name] is UI-only data for the dialog
 * and is never logged.
 */
sealed interface DeleteFlowState {
    data object Idle : DeleteFlowState
    data class Checking(val goalId: Long) : DeleteFlowState
    data class Confirm(val goalId: Long, val name: String) : DeleteFlowState
    data class NotAllowed(val goalId: Long) : DeleteFlowState
    data class Deleting(val goalId: Long) : DeleteFlowState
}

data class SmartRecommendation(
    val goal: SavingsGoal,
    val recommendedAmount: Double,
    val confidence: Double,
    val impact: String,
    val source: String,
    val currency: String = "",
    val rateTimestamp: Long = 0L,
    val computationId: Long = 0L
) {
    val moneyRecommendedAmount: MoneyAmount?
        get() = CurrencyCode.parse(currency)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?.let { MoneyAmount(recommendedAmount, it) }
}

@HiltViewModel
class SavingsGoalsViewModel @Inject constructor(
    private val savingsGoalRepository: SavingsGoalRepository,
    private val savingsContributionHistoryRepository: SavingsContributionHistoryRepository,
    private val smartSavingsEngine: SmartSavingsEngine,
    private val gamificationEngine: SavingsGamificationEngine,
    private val lifestyleSavingsPromptUseCase: LifestyleSavingsPromptUseCase,
 private val monthlySavingsSweepUseCase: MonthlySavingsSweepUseCase,
 private val savingsSweepAcceptanceCoordinator: SavingsSweepAcceptanceCoordinator,
 private val timeProvider: TimeProvider,
 private val currencySettingsRepository: CurrencySettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SavingsGoalsState())
    val state: StateFlow<SavingsGoalsState> = _state.asStateFlow()
    private var goalsCollectionJob: Job? = null
    private var recommendationComputationId = 0L

    private val _events = Channel<SavingsGoalsEvent>(Channel.BUFFERED)
    /** One-shot UI events (R18b): goal created after the write succeeded, or write blocked. */
    val events: Flow<SavingsGoalsEvent> = _events.receiveAsFlow()

 init {
 loadGoals()
 loadLifestyleRecommendation()
 checkSweepAvailability()
 }

    private fun loadGoals() {
        goalsCollectionJob?.cancel()
        goalsCollectionJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            
            combine(
                savingsGoalRepository.observeSavingsGoals(),
                currencySettingsRepository.homeCurrency().distinctUntilChanged(),
                currencySettingsRepository.lastRateUpdate().distinctUntilChanged()
            ) { goals, currency, rateTimestamp -> Triple(goals, currency, rateTimestamp) }
                .collectLatest { (goals, homeCurrency, rateTimestamp) ->
                    val computationId = ++recommendationComputationId
                    _state.update { it.copy(
                        isLoading = true, homeCurrency = homeCurrency, totalSaved = null,
                        totalSavedResult = null, userLevel = null, levelTitle = "Unavailable",
                        smartRecommendations = emptyList(), smartComputationResult = null,
                        sweepRecommendation = null,
                        sweepUnavailableReason = if (it.sweepRecommendation != null)
                            "SAVINGS_SWEEP_STALE_RECOMMENDATION" else it.sweepUnavailableReason,
                        sweepComputationResult = null,
                        achievements = emptyList()
                    ) }
                    val totalSavedResult = gamificationEngine.aggregateGoalBalances(goals, homeCurrency)
                    val totalSaved = totalSavedResult.completeDisplayAmountOrNull(homeCurrency)
                    val level = totalSaved?.let(gamificationEngine::calculateLevel)
                    val title = level?.let(gamificationEngine::getLevelTitle) ?: "Unavailable"
                    
                    val smartResult = smartSavingsEngine.calculatePortfolioRecommendationsResult(
                        goals = goals,
                        timeHorizon = SmartSavingsEngine.TimeHorizon.MONTH,
                        homeCurrency = homeCurrency
                    )
                    val portfolioRecommendations = (smartResult as? SmartSavingsComputationResult.Available)
                        ?.recommendations.orEmpty()

                    // Generate smart recommendations
                    val recommendations = portfolioRecommendations.mapNotNull { goalRecommendation ->
                        val rec = goalRecommendation.recommendation

                        if (rec.isAvailable && rec.safeAmount > 5.0 &&
                            SavingsGoalCurrencyPolicy.isUnfinished(goalRecommendation.goal) &&
                            SavingsGoalCurrencyPolicy.knownCurrencyOrNull(goalRecommendation.goal) != null &&
                            CurrencyCode.parse(rec.currency) == CurrencyCode.parse(homeCurrency) &&
                            CurrencyCode.parse(goalRecommendation.goal.currency) == CurrencyCode.parse(homeCurrency)
                        ) {
                            SmartRecommendation(
                                goal = goalRecommendation.goal,
                                recommendedAmount = rec.safeAmount,
                                confidence = rec.confidence,
                                impact = rec.impact,
                                source = rec.source.name.replace("_", " "),
                                currency = rec.currency,
                                rateTimestamp = rateTimestamp,
                                computationId = computationId
                            )
                        } else null
                    }
                    
                    val streak = gamificationEngine.calculateStreak()
                    val achievements = gamificationEngine.getAchievements(homeCurrency = homeCurrency)
                    if (currencySettingsRepository.homeCurrency().first() != homeCurrency ||
                        currencySettingsRepository.lastRateUpdate().first() != rateTimestamp) return@collectLatest
                    _state.update {
                        it.copy(
                            goals = goals,
                            smartRecommendations = if (it.contributionHistoryWriteFailure == null) recommendations else emptyList(),
                            smartComputationResult = smartResult,
                            isLoading = false,
                            totalSaved = totalSaved,
                            totalSavedResult = totalSavedResult,
                            homeCurrency = homeCurrency,
                            streak = if (it.contributionHistoryWriteFailure == null) streak else null,
                            achievements = if (it.contributionHistoryWriteFailure == null) achievements else emptyList(),
                            userLevel = level,
                            levelTitle = title
                        )
                    }
                }
        }
    }

    private fun loadGamification() {
        loadGoals()
    }
    
    private fun loadLifestyleRecommendation() {
        viewModelScope.launch {
            val recommendation = lifestyleSavingsPromptUseCase.evaluateAndPrompt()
            _state.update {
                it.copy(lifestyleRecommendation = recommendation)
            }
        }
    }

    /**
     * Check if sweep recommendations are available (month-end window).
     */
    private fun checkSweepAvailability() {
        val isAvailable = monthlySavingsSweepUseCase.shouldShowSweepPrompt()
        _state.update { it.copy(isSweepAvailable = isAvailable) }
        
        if (isAvailable) {
            computeSweepRecommendation()
        }
    }

    /**
     * Compute the monthly savings sweep recommendation.
     */
    fun computeSweepRecommendation() {
        viewModelScope.launch {
            val result = monthlySavingsSweepUseCase.computeSweepRecommendationResult()
            val recommendation = (result as? SavingsSweepComputationResult.Available)?.recommendation
            if (recommendation != null && (recommendation.currency != currencySettingsRepository.homeCurrency().first() ||
                    recommendation.rateTimestamp != currencySettingsRepository.lastRateUpdate().first())) return@launch
            _state.update {
                it.copy(
                    sweepRecommendation = if (it.contributionHistoryWriteFailure == null) recommendation else null,
                    sweepComputationResult = result,
                    sweepUnavailableReason = (result as? SavingsSweepComputationResult.Unavailable)?.reasonCode
                )
            }
        }
    }

    /**
     * Accept the sweep recommendation and allocate to goals.
     */
    fun acceptSweepRecommendation() {
        viewModelScope.launch {
            if (_state.value.contributionHistoryWriteFailure != null) return@launch
            val recommendation = _state.value.sweepRecommendation ?: return@launch
            val homeCurrency = currencySettingsRepository.homeCurrency().first()
            if (recommendation.currency != homeCurrency || recommendation.rateTimestamp != currencySettingsRepository.lastRateUpdate().first()) {
                rejectStaleSweep()
                return@launch
            }
            val refreshed = (monthlySavingsSweepUseCase.computeSweepRecommendationResult()
                as? SavingsSweepComputationResult.Available)?.recommendation
            if (refreshed == null || refreshed.copy(computedAt = recommendation.computedAt) != recommendation) {
                rejectStaleSweep()
                return@launch
            }
            val goalsById = savingsGoalRepository.observeSavingsGoals().first().associateBy { it.id }
            if (recommendation.goalAllocations.any { allocation ->
                val goal = goalsById[allocation.goalId]
                goal == null || !SavingsGoalCurrencyPolicy.isUnfinished(goal) ||
                    SavingsGoalCurrencyPolicy.knownCurrencyOrNull(goal) == null ||
                    goal.currency != homeCurrency || allocation.moneySuggestedAllocation?.currency != CurrencyCode.parse(goal.currency) || goal.currentAmount != allocation.currentProgress ||
                    goal.targetAmount != allocation.targetAmount
            }) {
                rejectStaleSweep()
                return@launch
            }

            if (currencySettingsRepository.homeCurrency().first() != homeCurrency ||
                recommendation.rateTimestamp != currencySettingsRepository.lastRateUpdate().first()) {
                rejectStaleSweep()
                return@launch
            }

            when (val acceptance = savingsSweepAcceptanceCoordinator.accept(recommendation)) {
                SavingsSweepAcceptanceResult.Accepted,
                SavingsSweepAcceptanceResult.AlreadyAccepted -> Unit
                is SavingsSweepAcceptanceResult.Rejected -> {
                    if (acceptance.reasonCode == SAVINGS_SWEEP_MONTH_ALREADY_ACCEPTED) {
                        // Terminal: the month is consumed, so the card must not stay actionable.
                        _state.update { it.copy(sweepRecommendation = null, sweepUnavailableReason = null) }
                        return@launch
                    }
                    _state.update { it.copy(sweepUnavailableReason = acceptance.reasonCode) }
                    return@launch
                }
            }
            
            // Clear the recommendation
            _state.update { it.copy(sweepRecommendation = null, sweepUnavailableReason = null) }
            
            // Refresh gamification
            loadGamification()
        }
    }

    private fun rejectStaleSweep() {
        _state.update {
            it.copy(sweepRecommendation = null, sweepUnavailableReason = "SAVINGS_SWEEP_STALE_RECOMMENDATION")
        }
    }

    /**
     * Dismiss the sweep recommendation without applying.
     */
    fun dismissSweepRecommendation() {
        viewModelScope.launch {
            _state.update { it.copy(sweepRecommendation = null, sweepUnavailableReason = null) }
        }
    }
    
    fun acceptLifestyleRecommendation(goalId: Long?) {
        viewModelScope.launch {
            guardedWrite(::emitWriteBlocked) {
                lifestyleSavingsPromptUseCase.recordAcceptance(goalId)
            } ?: return@launch
            _state.update { it.copy(lifestyleRecommendation = null) }
        }
    }

    fun dismissLifestyleRecommendation(reason: String? = null) {
        viewModelScope.launch {
            guardedWrite(::emitWriteBlocked) {
                lifestyleSavingsPromptUseCase.recordDismissal(reason)
            } ?: return@launch
            _state.update { it.copy(lifestyleRecommendation = null) }
        }
    }

    /** R18b: surfaces the bounded maintenance message through the one-shot [events] channel. */
    private fun emitWriteBlocked(message: UiText) {
        _events.trySend(SavingsGoalsEvent.WriteBlocked(message))
    }

    fun addGoal(name: String, targetAmount: Double, targetDate: Long?, protectionLevel: GoalProtectionLevel) {
        viewModelScope.launch {
            val goal = SavingsGoal(
                id = 0L,
                name = name,
                targetAmount = targetAmount,
                currentAmount = 0.0,
                targetDate = targetDate,
                protectionLevel = protectionLevel,
                currency = currencySettingsRepository.homeCurrency().first(),
                currencyAssumption = "EXPLICIT_HOME_CURRENCY",
                createdAt = timeProvider.now()
            )
            // R18b: the success event is emitted only after the write completed; a blocked write
            // emits the maintenance message instead and never reports "created".
            guardedWrite(::emitWriteBlocked) {
                savingsGoalRepository.createSavingsGoal(goal)
            } ?: return@launch
            loadGamification() // Refresh level/achievements
            _events.trySend(SavingsGoalsEvent.GoalCreated)
        }
    }

    fun acceptSmartRecommendation(recommendation: SmartRecommendation) {
        if (recommendation !in _state.value.smartRecommendations) return
        val amount = recommendation.moneyRecommendedAmount ?: return
        contributeToGoal(recommendation.goal.id, amount.amount, amount.currency.code, recommendation)
    }

    /**
     * Quick add of [QUICK_ADD_AMOUNT] capped at the goal's remaining amount (target minus current,
     * exact decimal subtraction, no rounding). A goal with no remaining amount, or an unreadable
     * balance, is a no-op. The cap uses the goal's own currency; the write goes through
     * [contributeToGoal], which re-checks the goal snapshot and currency.
     */
    fun quickAdd(goalId: Long) {
        viewModelScope.launch {
            // Held across read, cap calculation and the full contribution (incl. history write)
            // so a second tap reads the balance after the first write has landed.
            quickAddMutex.withLock {
                val goal = savingsGoalRepository.observeSavingsGoals().first()
                    .firstOrNull { it.id == goalId }
                    ?: return@withLock
                if (!goal.targetAmount.isFinite() || !goal.currentAmount.isFinite()) return@withLock
                val remaining = BigDecimal.valueOf(goal.targetAmount)
                    .subtract(BigDecimal.valueOf(goal.currentAmount))
                if (remaining.signum() <= 0) return@withLock
                val amount = remaining.min(BigDecimal.valueOf(QUICK_ADD_AMOUNT)).toDouble()
                performContribution(goalId, amount, goal.currency, null)
            }
        }
    }

    private val quickAddMutex = Mutex()

    fun contributeToGoal(goalId: Long, amount: Double, expectedCurrency: String? = null, recommendation: SmartRecommendation? = null) {
        viewModelScope.launch {
            performContribution(goalId, amount, expectedCurrency, recommendation)
        }
    }

    private suspend fun performContribution(goalId: Long, amount: Double, expectedCurrency: String?, recommendation: SmartRecommendation?) {
        if (_state.value.contributionHistoryWriteFailure != null) return
        if (!amount.isFinite() || amount <= 0.0) return
        val goals = savingsGoalRepository.observeSavingsGoals().first()
        val goal = goals
            .firstOrNull { it.id == goalId }
            ?: return
        if (SavingsGoalCurrencyPolicy.knownCurrencyOrNull(goal) == null) return
        if (recommendation != null && !SavingsGoalCurrencyPolicy.isUnfinished(goal)) return
        if (expectedCurrency != null && expectedCurrency != goal.currency) return
        if (recommendation != null && (recommendation !in _state.value.smartRecommendations ||
                recommendation.goal != goal ||
                recommendation.currency != currencySettingsRepository.homeCurrency().first() ||
                recommendation.rateTimestamp != currencySettingsRepository.lastRateUpdate().first())) return
        if (recommendation != null) {
            val result = smartSavingsEngine.calculatePortfolioRecommendationsResult(
                goals, SmartSavingsEngine.TimeHorizon.MONTH, recommendation.currency
            )
            if (result !is SmartSavingsComputationResult.Available) {
                _state.update { it.copy(smartRecommendations = emptyList(), smartComputationResult = result) }
                return
            }
            val current = result.recommendations.firstOrNull { it.goal.id == goalId } ?: return
            if (!current.recommendation.isAvailable || current.goal != goal ||
                SavingsGoalCurrencyPolicy.knownCurrencyOrNull(current.goal) == null ||
                current.recommendation.moneySafeAmount != recommendation.moneyRecommendedAmount ||
                recommendation !in _state.value.smartRecommendations ||
                recommendation.currency != currencySettingsRepository.homeCurrency().first() ||
                recommendation.rateTimestamp != currencySettingsRepository.lastRateUpdate().first()) return
        }
        if (savingsGoalRepository.observeSavingsGoals().first().firstOrNull { it.id == goalId } != goal) return
        if (recommendation != null && (recommendation.currency != currencySettingsRepository.homeCurrency().first() ||
                recommendation.rateTimestamp != currencySettingsRepository.lastRateUpdate().first())) return
        val wasAdded = guardedWrite(::emitWriteBlocked) {
            savingsGoalRepository.incrementSavingsGoalAmount(goalId, amount)
        } ?: return
        if (wasAdded) {
            val recorded = recordContributionHistory(
                goalId = goalId,
                amount = amount,
                currency = goal.currency,
                source = "manual"
            )
            if (!recorded) return
            loadGamification()
        }
    }

    /**
     * Balance/history are separate stores. A failed history write is not success;
     * block subsequent retries in this session after observing a recording failure.
     * Durable atomicity/idempotence requires the later owner-level persistence work.
     */
    private suspend fun recordContributionHistory(goalId: Long, amount: Double, currency: String, source: String): Boolean {
        val failureCode = try {
            if (savingsContributionHistoryRepository.recordContribution(
                    goalId = goalId, amount = amount, currency = currency, source = source
                )) null else "SAVINGS_HISTORY_WRITE_REJECTED"
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            "SAVINGS_HISTORY_WRITE_UNAVAILABLE"
        }
        if (failureCode == null) return true
        _state.update { it.copy(
            contributionHistoryWriteFailure = failureCode,
            smartRecommendations = emptyList(),
            sweepRecommendation = null,
            streak = null,
            achievements = emptyList()
        ) }
        return false
    }

    private fun MoneyAggregateResult.completeDisplayAmountOrNull(targetCurrency: String): Double? =
        (this as? MoneyAggregateResult.Available)
            ?.aggregate
            ?.takeIf { !it.isPartial && it.conversionQuality == ConversionQuality.COMPLETE &&
                it.displayCurrency == CurrencyCode.parse(targetCurrency) }
            ?.displayAmount

    /**
     * Starts the delete flow for [goalId]: a read-only eligibility pre-check owned by the
     * repository/policy (INV-16; the VM holds no emptiness logic). Ignored unless the flow is Idle.
     */
    fun requestDeleteGoal(goalId: Long) {
        var accepted = false
        _state.update {
            accepted = it.deleteFlow == DeleteFlowState.Idle
            if (accepted) it.copy(deleteFlow = DeleteFlowState.Checking(goalId)) else it
        }
        if (!accepted) return
        viewModelScope.launch {
            val eligibility = try {
                savingsGoalRepository.getSavingsGoalDeletionEligibility(goalId)
            } catch (e: CancellationException) {
                resetDeleteFlowIfActive(goalId)
                throw e
            } catch (_: Exception) {
                // A late failure of a dismissed check must not touch a newer flow.
                if (_state.value.deleteFlow != DeleteFlowState.Checking(goalId)) return@launch
                finishDelete(SavingsGoalsEvent.DeleteRejected(DeleteRejectReason.GENERIC))
                return@launch
            }
            // The user may have dismissed the flow while the read was in flight.
            if (_state.value.deleteFlow != DeleteFlowState.Checking(goalId)) return@launch
            when (eligibility) {
                SavingsGoalDeleteEligibility.Deletable -> {
                    val name = _state.value.goals.firstOrNull { it.id == goalId }?.name
                    if (name == null) {
                        // Not in the visible list: nothing was deleted here, so no GoalDeleted
                        // event; close silently and let the goal list refresh.
                        finishDelete(null)
                    } else {
                        _state.update { it.copy(deleteFlow = DeleteFlowState.Confirm(goalId, name)) }
                    }
                }
                SavingsGoalDeleteEligibility.AlreadyAbsent -> applyGoalRemoved(goalId)
                is SavingsGoalDeleteEligibility.Rejected -> when (eligibility.reasonCode) {
                    SAVINGS_GOAL_DELETE_HAS_BALANCE,
                    SAVINGS_GOAL_DELETE_HAS_HISTORY ->
                        _state.update { it.copy(deleteFlow = DeleteFlowState.NotAllowed(goalId)) }
                    else -> finishDelete(SavingsGoalsEvent.DeleteRejected(rejectReasonFor(eligibility.reasonCode)))
                }
            }
        }
    }

    /** Closes the Confirm / NotAllowed / Checking overlay. Ignored while [DeleteFlowState.Deleting]. */
    fun dismissDeleteFlow() {
        _state.update {
            if (it.deleteFlow is DeleteFlowState.Deleting) it else it.copy(deleteFlow = DeleteFlowState.Idle)
        }
    }

    /**
     * Confirm path of the delete flow. The repository re-checks eligibility inside the delete
     * transaction (INV-8); the pre-check result is never trusted here. Ignored while a check or a
     * delete is already running (double tap).
     */
    fun deleteGoal(goal: SavingsGoal) {
        var accepted = false
        _state.update {
            val flow = it.deleteFlow
            accepted = flow == DeleteFlowState.Idle ||
                (flow is DeleteFlowState.Confirm && flow.goalId == goal.id)
            if (accepted) it.copy(deleteFlow = DeleteFlowState.Deleting(goal.id)) else it
        }
        if (!accepted) return
        viewModelScope.launch {
            val result = guardedWrite(::emitWriteBlocked) {
                try {
                    savingsGoalRepository.deleteSavingsGoal(goal)
                } catch (e: CancellationException) {
                    resetDeleteFlowIfActive(goal.id)
                    throw e
                } catch (e: DatabaseAccessBlockedException) {
                    throw e
                } catch (_: Exception) {
                    SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_UNAVAILABLE)
                }
            }
            when (result) {
                // Write barrier held: guardedWrite already emitted WriteBlocked.
                null -> finishDelete(event = null)
                SavingsGoalDeleteResult.Deleted,
                SavingsGoalDeleteResult.AlreadyAbsent -> applyGoalRemoved(goal.id)
                is SavingsGoalDeleteResult.Rejected ->
                    finishDelete(SavingsGoalsEvent.DeleteRejected(rejectReasonFor(result.reasonCode)))
            }
        }
    }

    /** Maps a bounded repository rejection code to the bounded UI reason. No rule logic. */
    private fun rejectReasonFor(reasonCode: String): DeleteRejectReason = when (reasonCode) {
        SAVINGS_GOAL_DELETE_HAS_BALANCE,
        SAVINGS_GOAL_DELETE_HAS_HISTORY -> DeleteRejectReason.NOT_EMPTY
        SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE -> DeleteRejectReason.HISTORY_UNAVAILABLE
        else -> DeleteRejectReason.GENERIC
    }

    /** Single place for INV-11: the goal is gone, so drop every VM-held reference to its id. */
    private fun applyGoalRemoved(goalId: Long) {
        _state.update {
            it.copy(
                selectedGoal = it.selectedGoal?.takeUnless { selected -> selected.id == goalId },
                smartRecommendations = it.smartRecommendations.filterNot { rec -> rec.goal.id == goalId },
                sweepRecommendation = it.sweepRecommendation
                    ?.takeUnless { rec -> rec.goalAllocations.any { allocation -> allocation.goalId == goalId } },
                deleteFlow = DeleteFlowState.Idle
            )
        }
        _events.trySend(SavingsGoalsEvent.GoalDeleted)
    }

    /** Every terminal result returns the flow to Idle; [event] is optional. */
    private fun finishDelete(event: SavingsGoalsEvent?) {
        _state.update { it.copy(deleteFlow = DeleteFlowState.Idle) }
        if (event != null) _events.trySend(event)
    }

    /** Cancellation: back to Idle with no message. */
    private fun resetDeleteFlowIfActive(goalId: Long) {
        _state.update {
            val flow = it.deleteFlow
            val ours = (flow is DeleteFlowState.Checking && flow.goalId == goalId) ||
                (flow is DeleteFlowState.Deleting && flow.goalId == goalId)
            if (ours) it.copy(deleteFlow = DeleteFlowState.Idle) else it
        }
    }

    fun selectGoal(goal: SavingsGoal?) {
        _state.update { it.copy(selectedGoal = goal) }
    }

 fun refresh() {
 loadGoals()
 loadLifestyleRecommendation()
 checkSweepAvailability()
 }

}
