package com.yourname.expensetracker.ui.screens.savings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.savings.*
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsRecommendation
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation
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
    val totalSaved: Double = 0.0
)

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
    private val smartSavingsEngine: SmartSavingsEngine,
    private val gamificationEngine: SavingsGamificationEngine,
    private val lifestyleSavingsPromptUseCase: LifestyleSavingsPromptUseCase,
    private val monthlySavingsSweepUseCase: MonthlySavingsSweepUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(SavingsGoalsState())
    val state: StateFlow<SavingsGoalsState> = _state.asStateFlow()
    private var goalsCollectionJob: Job? = null

    init {
        loadGoals()
        loadGamification()
        loadLifestyleRecommendation()
        checkSweepAvailability()
    }

    private fun loadGoals() {
        goalsCollectionJob?.cancel()
        goalsCollectionJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            
            savingsGoalRepository.getAllGoals()
                .collect { goals ->
                    var totalSaved = 0.0
                    for (goal in goals) {
                        totalSaved += goal.currentAmount
                    }

                    val level = gamificationEngine.calculateLevel(totalSaved)
                    val title = gamificationEngine.getLevelTitle(level)
                    
                    // Generate smart recommendations
                    val recommendations = goals.mapNotNull { goal ->
                        val rec = smartSavingsEngine.calculateSafeToSaveAmount(
                            goal = goal,
                            timeHorizon = SmartSavingsEngine.TimeHorizon.MONTH
                        )
                        
                        if (rec.safeAmount > 5.0) { // Only show meaningful recommendations
                            SmartRecommendation(
                                goal = goal,
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
            val achievements = gamificationEngine.getAchievements()
            
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
                savingsGoalRepository.addToGoalAmount(allocation.goalId, allocation.suggestedAllocation)
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
                name = name,
                targetAmount = targetAmount,
                targetDate = targetDate,
                protectionLevel = protectionLevel
            )
            savingsGoalRepository.addGoal(goal)
            loadGamification() // Refresh level/achievements
        }
    }

    fun contributeToGoal(goalId: Long, amount: Double) {
        viewModelScope.launch {
            savingsGoalRepository.addToGoalAmount(goalId, amount)
            loadGamification()
        }
    }

    fun deleteGoal(goal: SavingsGoal) {
        viewModelScope.launch {
            savingsGoalRepository.deleteGoal(goal)
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
}
