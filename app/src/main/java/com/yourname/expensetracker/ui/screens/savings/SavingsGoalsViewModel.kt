package com.yourname.expensetracker.ui.screens.savings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.savings.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SavingsGoalsState(
    val goals: List<SavingsGoal> = emptyList(),
    val smartRecommendations: List<SmartRecommendation> = emptyList(),
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
    private val gamificationEngine: SavingsGamificationEngine
) : ViewModel() {

    private val _state = MutableStateFlow(SavingsGoalsState())
    val state: StateFlow<SavingsGoalsState> = _state.asStateFlow()

    init {
        loadGoals()
        loadGamification()
    }

    private fun loadGoals() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            
            savingsGoalRepository.getAllGoals()
                .collect { goals ->
                    var totalSaved = 0.0
                    for (goal in goals) {
                        totalSaved += goal.currentAmount
                    }
                    
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
                            totalSaved = totalSaved
                        )
                    }
                }
        }
    }

    private fun loadGamification() {
        viewModelScope.launch {
            val streak = gamificationEngine.calculateStreak()
            val achievements = gamificationEngine.getAchievements()
            val totalSaved = _state.value.totalSaved
            val level = gamificationEngine.calculateLevel(totalSaved)
            val title = gamificationEngine.getLevelTitle(level)
            
            _state.update {
                it.copy(
                    streak = streak,
                    achievements = achievements,
                    userLevel = level,
                    levelTitle = title
                )
            }
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
            val goals = savingsGoalRepository.getAllGoals().first()
            val goal = goals.find { it.id == goalId } ?: return@launch
            
            val updatedGoal = goal.copy(
                currentAmount = goal.currentAmount + amount
            )
            // Note: Would need update method in repository
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
    }
}
