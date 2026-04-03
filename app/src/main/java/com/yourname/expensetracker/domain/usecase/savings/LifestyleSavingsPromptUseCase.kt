package com.yourname.expensetracker.domain.usecase.savings

import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.repository.PromptStateRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.lifestyle.LifestyleInflationDetector
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min

@Singleton
class LifestyleSavingsPromptUseCase @Inject constructor(
    private val lifestyleInflationDetector: LifestyleInflationDetector,
    private val savingsGoalRepository: SavingsGoalRepository,
    private val promptStateRepository: PromptStateRepository,
    private val timeProvider: TimeProvider
) {
    companion object {
        const val PROMPT_TYPE = "LIFESTYLE_SAVINGS"
        const val INFLATION_THRESHOLD = 0.05  // 5%
        const val MIN_MONTHS_SUSTAINED = 2
        const val COOLDOWN_DAYS = 30
        const val ALPHA = 0.5  // Conservative factor for suggested uplift
        const val MAX_SAVINGS_CAP_PERCENT = 0.20  // Max 20% of current savings rate
        const val CONFIDENCE_THRESHOLD = 0.6
    }
    
    /**
     * Evaluates if a lifestyle savings prompt should be shown.
     * Returns a recommendation if conditions are met, null otherwise.
     * 
     * Anti-nag logic:
     * - Only prompt once per 30 days
     * - Only prompt if inflation rate > 5% sustained over 2+ months
     * - Don't prompt if user has already increased savings rate
     * - Track user response to avoid repeating rejected suggestions
     */
    suspend fun evaluateAndPrompt(): LifestyleSavingsRecommendation? {
        // Check if we recently prompted (anti-nag)
        if (promptStateRepository.hasPromptedRecently(PROMPT_TYPE, COOLDOWN_DAYS)) {
            return null
        }
        
        // Check if user already accepted a savings increase recently
        if (promptStateRepository.hasUserTakenAction(PROMPT_TYPE, "ACCEPTED", 90)) {
            return null
        }
        
        // Analyze lifestyle inflation
        val report = lifestyleInflationDetector.analyzeLifestyleInflation(12)
        
        // Check if inflation is significant enough
        if (report.lifestyleInflationRate < INFLATION_THRESHOLD) {
            return null
        }
        
        // Check if sustained over minimum months (need enough data)
        if (report.monthlyData.size < MIN_MONTHS_SUSTAINED) {
            return null
        }
        
        // Check if lifestyle creep was detected
        if (!report.lifestyleCreepDetected && report.incomeElasticity < 1.2) {
            return null
        }
        
        // Get current savings goals
        val goals = savingsGoalRepository.getAllGoals().first()
        
        // Calculate suggested uplift
        val currentSavingsRate = report.monthlyData.lastOrNull()?.savingsRate ?: 0.0
        val currentSavingsRatePercent = if (abs(currentSavingsRate) <= 1.0) {
            currentSavingsRate * 100
        } else {
            currentSavingsRate
        }

        val (previousIncome, currentIncome) = report.monthlyData
            .takeLast(2)
            .let { months ->
                when (months.size) {
                    2 -> months[0].income to months[1].income
                    1 -> months[0].income to months[0].income
                    else -> 0.0 to 0.0
                }
            }
        
        // Formula: suggestedUplift = min(alpha * incomeGrowthDelta, cap)
        // Use income growth itself (not spending-income gap) to avoid suggesting savings from deficit pressure.
        val incomeGrowthDelta = if (previousIncome > 0) {
            ((currentIncome - previousIncome) / previousIncome).coerceAtLeast(0.0)
        } else {
            0.0
        }
        val maxCap = currentSavingsRatePercent * MAX_SAVINGS_CAP_PERCENT
        val suggestedUplift = min(ALPHA * incomeGrowthDelta * 100, maxCap) // Convert to percentage points
        
        // Calculate confidence score
        val confidence = calculateConfidence(report, goals)
        
        if (confidence < CONFIDENCE_THRESHOLD) {
            return null
        }
        
        // Get affected categories from alerts
        val affectedCategories = report.lifestyleCreepAlerts
            .flatMap { alert -> 
                listOf(
                    "Income: ${String.format("%.1f", alert.incomeGrowthPercent)}%",
                    "Spending: ${String.format("%.1f", alert.spendingGrowthPercent)}%"
                )
            }
            .distinct()
            .take(3)
        
        // Generate reason text
        val reason = generateReason(report, goals.isNotEmpty())
        
        return LifestyleSavingsRecommendation(
            inflationRate = report.lifestyleInflationRate,
            suggestedMonthlyUplift = suggestedUplift.coerceAtLeast(1.0), // At least 1%
            reason = reason,
            affectedCategories = affectedCategories,
            confidence = confidence,
            currentSavingsRate = currentSavingsRatePercent,
            incomeElasticity = report.incomeElasticity,
            goals = goals
        )
    }
    
    /**
     * Calculate confidence score for the recommendation.
     */
    private fun calculateConfidence(
        report: LifestyleInflationDetector.LifestyleInflationReport,
        goals: List<SavingsGoal>
    ): Double {
        var score = 0.0
        
        // Higher confidence if lifestyle creep was explicitly detected
        if (report.lifestyleCreepDetected) {
            score += 0.3
        }
        
        // Higher confidence with higher income elasticity
        if (report.incomeElasticity > 1.5) {
            score += 0.2
        } else if (report.incomeElasticity > 1.2) {
            score += 0.1
        }
        
        // Higher confidence if there's a gap between income and spending growth
        val gap = report.spendingGrowthRate - report.incomeGrowthRate
        score += (gap * 2).coerceIn(0.0, 0.2)
        
        // Having savings goals increases confidence (user is already savings-minded)
        if (goals.isNotEmpty()) {
            score += 0.2
        }
        
        // Higher confidence with more data points
        if (report.monthlyData.size >= 6) {
            score += 0.1
        }
        
        return score.coerceIn(0.0, 1.0)
    }
    
    /**
     * Generate human-readable reason for the recommendation.
     */
    private fun generateReason(
        report: LifestyleInflationDetector.LifestyleInflationReport,
        hasGoals: Boolean
    ): String {
        val spendingGrowth = String.format("%.1f", report.spendingGrowthRate * 100)
        val incomeGrowth = String.format("%.1f", report.incomeGrowthRate * 100)
        
        return when {
            report.lifestyleCreepAlerts.isNotEmpty() -> {
                val alert = report.lifestyleCreepAlerts.last()
                "Your spending grew ${String.format("%.1f", alert.spendingGrowthPercent)}% " +
                "while income only grew ${String.format("%.1f", alert.incomeGrowthPercent)}%. " +
                "Consider redirecting some of that increase toward savings."
            }
            report.incomeElasticity > 1.5 -> {
                "Your spending increases ${String.format("%.0f", report.incomeElasticity * 100)}% " +
                "for every 1% income increase. Capturing some of this growth for savings could help."
            }
            hasGoals -> {
                "With spending up $spendingGrowth% vs income up $incomeGrowth%, " +
                "boosting your savings rate could help you reach your goals faster."
            }
            else -> {
                "Your spending is growing faster than income. " +
                "Consider setting savings goals to capture some of this growth."
            }
        }
    }
    
    /**
     * Record that the user accepted the savings recommendation.
     */
    suspend fun recordAcceptance(goalId: Long? = null) {
        val details = goalId?.let { """{"goalId": $it}""" }
        promptStateRepository.recordAcknowledgment(PROMPT_TYPE, "ACCEPTED", details)
    }
    
    /**
     * Record that the user dismissed the savings recommendation.
     */
    suspend fun recordDismissal(reason: String? = null) {
        promptStateRepository.recordAcknowledgment(PROMPT_TYPE, "DISMISSED", reason)
    }
    
    /**
     * Record that the user deferred the decision.
     */
    suspend fun recordDeferral() {
        promptStateRepository.recordAcknowledgment(PROMPT_TYPE, "DEFERRED")
    }
}

data class LifestyleSavingsRecommendation(
    val inflationRate: Double,
    val suggestedMonthlyUplift: Double,
    val reason: String,
    val affectedCategories: List<String>,
    val confidence: Double,
    val currentSavingsRate: Double = 0.0,
    val incomeElasticity: Double = 0.0,
    val goals: List<SavingsGoal> = emptyList()
)
