package com.yourname.expensetracker.ui.screens.savings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepository
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.savings.*
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsRecommendation
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SavingsGoalsState(
 val goals: List<SavingsGoal> = emptyList(),
 val smartRecommendations: List<SmartRecommendation> = emptyList(),
 val lifestyleRecommendation: LifestyleSavingsRecommendation? = null,
 val sweepRecommendation: SavingsSweepRecommendation? = null,
 val isSweepAvailable: Boolean = false,
 val streak: SavingsStreak? = null,
 val achievements: List<SavingsAchievement> = emptyList(),
 val userLevel: Int = 1,
 val levelTitle: String = "Savings Rookie",
 val isLoading: Boolean = false,
 val selectedGoal: SavingsGoal? = null,
 val totalSaved: Double = 0.0,
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
 val moneyTotalSaved: MoneyAmount get() = MoneyAmount(totalSaved, CurrencyCode(homeCurrency))
}

data class SmartRecommendation(
    val goal: SavingsGoal,
    val recommendedAmount: Double,
    val confidence: Double,
    val impact: String,
    val source: String
)

@HiltViewModel
class SavingsGoalsViewModel @Inject constructor(
    private val savingsGoalRepository: SavingsGoalRepository,
    private val savingsContributionHistoryRepository: SavingsContributionHistoryRepository,
    private val smartSavingsEngine: SmartSavingsEngine,
    private val gamificationEngine: SavingsGamificationEngine,
    private val lifestyleSavingsPromptUseCase: LifestyleSavingsPromptUseCase,
 private val monthlySavingsSweepUseCase: MonthlySavingsSweepUseCase,
 private val timeProvider: TimeProvider,
 private val currencySettingsRepository: CurrencySettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SavingsGoalsState())
    val state: StateFlow<SavingsGoalsState> = _state.asStateFlow()
    private var goalsCollectionJob: Job? = null

 init {
 loadGoals()
 loadGamification()
 loadLifestyleRecommendation()
 checkSweepAvailability()
 collectHomeCurrency()
 }

    private fun loadGoals() {
        goalsCollectionJob?.cancel()
        goalsCollectionJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            
            savingsGoalRepository.observeSavingsGoals()
                .collect { goals ->
                    var totalSaved = 0.0
                    for (goal in goals) {
                        totalSaved += goal.currentAmount
                    }

                    val level = gamificationEngine.calculateLevel(totalSaved)
                    val title = gamificationEngine.getLevelTitle(level)
                    
            val portfolioRecommendations = smartSavingsEngine.calculatePortfolioRecommendations(
                goals = goals,
                timeHorizon = SmartSavingsEngine.TimeHorizon.MONTH,
                homeCurrency = currencySettingsRepository.homeCurrency().first()
            )

                    // Generate smart recommendations
                    val recommendations = portfolioRecommendations.mapNotNull { goalRecommendation ->
                        val rec = goalRecommendation.recommendation

                        if (rec.safeAmount > 5.0) { // Only show meaningful recommendations
                            SmartRecommendation(
                                goal = goalRecommendation.goal,
                                recommendedAmount = rec.safeAmount,
                                confidence = rec.confidence,
                                impact = rec.impact,
                                source = rec.source.name.replace("_", " ")
                            )
                        } else null
                    }
                    
                    _state.update {
                        it.copy(
                            goals = goals,
                            smartRecommendations = recommendations,
                            isLoading = false,
                            totalSaved = totalSaved,
                            userLevel = level,
                            levelTitle = title
                        )
                    }
                }
        }
    }

    private fun loadGamification() {
        viewModelScope.launch {
            val streak = gamificationEngine.calculateStreak()
            val achievements = gamificationEngine.getAchievements(homeCurrency = currencySettingsRepository.homeCurrency().first())
            
            _state.update {
                it.copy(
                    streak = streak,
                    achievements = achievements
                )
            }
        }
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
            val recommendation = monthlySavingsSweepUseCase.computeSweepRecommendation()
            _state.update { it.copy(sweepRecommendation = recommendation) }
        }
    }

    /**
     * Accept the sweep recommendation and allocate to goals.
     */
    fun acceptSweepRecommendation() {
        viewModelScope.launch {
            val recommendation = _state.value.sweepRecommendation ?: return@launch
            
            // Apply allocations to goals atomically — no read-modify-write race
            for (allocation in recommendation.goalAllocations) {
                val wasAdded = savingsGoalRepository.incrementSavingsGoalAmount(
                    allocation.goalId,
                    allocation.suggestedAllocation
                )
                if (wasAdded) {
                    savingsContributionHistoryRepository.recordContribution(
                        goalId = allocation.goalId,
                        amount = allocation.suggestedAllocation,
                        source = "sweep"
                    )
                }
            }
            
            // Clear the recommendation
            _state.update { it.copy(sweepRecommendation = null) }
            
            // Refresh gamification
            loadGamification()
        }
    }

    /**
     * Dismiss the sweep recommendation without applying.
     */
    fun dismissSweepRecommendation() {
        viewModelScope.launch {
            _state.update { it.copy(sweepRecommendation = null) }
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
                createdAt = timeProvider.now()
            )
            savingsGoalRepository.createSavingsGoal(goal)
            loadGamification() // Refresh level/achievements
        }
    }

    fun contributeToGoal(goalId: Long, amount: Double) {
        viewModelScope.launch {
            val wasAdded = savingsGoalRepository.incrementSavingsGoalAmount(goalId, amount)
            if (wasAdded) {
                savingsContributionHistoryRepository.recordContribution(
                    goalId = goalId,
                    amount = amount,
                    source = "manual"
                )
                loadGamification()
            }
        }
    }

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
 loadGamification()
 loadLifestyleRecommendation()
 checkSweepAvailability()
 }

 private fun collectHomeCurrency() {
 viewModelScope.launch {
 currencySettingsRepository.homeCurrency().collect { hc ->
 _state.update { it.copy(homeCurrency = hc) }
 }
 }
 }
}
