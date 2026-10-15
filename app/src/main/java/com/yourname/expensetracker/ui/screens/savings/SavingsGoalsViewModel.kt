package com.yourname.expensetracker.ui.screens.savings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.repository.SAVINGS_SWEEP_MONTH_ALREADY_ACCEPTED
import com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepository
import com.yourname.expensetracker.data.repository.SavingsSweepAcceptanceCoordinator
import com.yourname.expensetracker.data.repository.SavingsSweepAcceptanceResult
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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
	val homeCurrency: String = "EUR"
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
            lifestyleSavingsPromptUseCase.recordAcceptance(goalId)
            _state.update { it.copy(lifestyleRecommendation = null) }
        }
    }
    
    fun dismissLifestyleRecommendation(reason: String? = null) {
        viewModelScope.launch {
            lifestyleSavingsPromptUseCase.recordDismissal(reason)
            _state.update { it.copy(lifestyleRecommendation = null) }
        }
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
            savingsGoalRepository.createSavingsGoal(goal)
            loadGamification() // Refresh level/achievements
        }
    }

    fun acceptSmartRecommendation(recommendation: SmartRecommendation) {
        if (recommendation !in _state.value.smartRecommendations) return
        val amount = recommendation.moneyRecommendedAmount ?: return
        contributeToGoal(recommendation.goal.id, amount.amount, amount.currency.code, recommendation)
    }

    fun contributeToGoal(goalId: Long, amount: Double, expectedCurrency: String? = null, recommendation: SmartRecommendation? = null) {
        viewModelScope.launch {
            if (_state.value.contributionHistoryWriteFailure != null) return@launch
            if (!amount.isFinite() || amount <= 0.0) return@launch
            val goals = savingsGoalRepository.observeSavingsGoals().first()
            val goal = goals
                .firstOrNull { it.id == goalId }
                ?: return@launch
            if (SavingsGoalCurrencyPolicy.knownCurrencyOrNull(goal) == null) return@launch
            if (recommendation != null && !SavingsGoalCurrencyPolicy.isUnfinished(goal)) return@launch
            if (expectedCurrency != null && expectedCurrency != goal.currency) return@launch
            if (recommendation != null && (recommendation !in _state.value.smartRecommendations ||
                    recommendation.goal != goal ||
                    recommendation.currency != currencySettingsRepository.homeCurrency().first() ||
                    recommendation.rateTimestamp != currencySettingsRepository.lastRateUpdate().first())) return@launch
            if (recommendation != null) {
                val result = smartSavingsEngine.calculatePortfolioRecommendationsResult(
                    goals, SmartSavingsEngine.TimeHorizon.MONTH, recommendation.currency
                )
                if (result !is SmartSavingsComputationResult.Available) {
                    _state.update { it.copy(smartRecommendations = emptyList(), smartComputationResult = result) }
                    return@launch
                }
                val current = result.recommendations.firstOrNull { it.goal.id == goalId } ?: return@launch
                if (!current.recommendation.isAvailable || current.goal != goal ||
                    SavingsGoalCurrencyPolicy.knownCurrencyOrNull(current.goal) == null ||
                    current.recommendation.moneySafeAmount != recommendation.moneyRecommendedAmount ||
                    recommendation !in _state.value.smartRecommendations ||
                    recommendation.currency != currencySettingsRepository.homeCurrency().first() ||
                    recommendation.rateTimestamp != currencySettingsRepository.lastRateUpdate().first()) return@launch
            }
            if (savingsGoalRepository.observeSavingsGoals().first().firstOrNull { it.id == goalId } != goal) return@launch
            if (recommendation != null && (recommendation.currency != currencySettingsRepository.homeCurrency().first() ||
                    recommendation.rateTimestamp != currencySettingsRepository.lastRateUpdate().first())) return@launch
            val wasAdded = savingsGoalRepository.incrementSavingsGoalAmount(goalId, amount)
            if (wasAdded) {
                val recorded = recordContributionHistory(
                    goalId = goalId,
                    amount = amount,
                    currency = goal.currency,
                    source = "manual"
                )
                if (!recorded) return@launch
                loadGamification()
            }
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

    fun deleteGoal(goal: SavingsGoal) {
        viewModelScope.launch {
            savingsGoalRepository.deleteSavingsGoal(goal)
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
