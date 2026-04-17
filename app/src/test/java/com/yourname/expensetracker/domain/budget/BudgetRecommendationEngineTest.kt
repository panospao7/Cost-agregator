package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DomainTextKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BudgetRecommendationEngineTest {

    private lateinit var engine: BudgetRecommendationEngine

    @Before
    fun setUp() {
        engine = BudgetRecommendationEngine()
    }

    @Test
    fun `generate recommendations for critical risk returns ordered high urgency actions`() {
        val recommendations = engine.generateRecommendations(
            budget = BudgetRecommendationBudget(amount = 1000.0),
            forecast = BudgetRecommendationForecast(
                predictedSpending = 300.0,
                predictedRemaining = -150.0,
                confidenceScore = 0.90,
                riskLevel = BudgetRecommendationRiskLevel.CRITICAL,
                overspendProbability = 0.95
            ),
            currentSpending = 850.0
        )

        assertEquals(3, recommendations.size)
        assertEquals(RecommendationType.REDUCE_SPENDING, recommendations[0].type)
        assertEquals(RecommendationPriority.CRITICAL, recommendations[0].priority)
        assertApproxEquals(150.0, recommendations[0].potentialSavings ?: 0.0, 0.01)
        assertEquals(RecommendationType.PAUSE_NON_ESSENTIAL, recommendations[1].type)
        assertEquals(RecommendationPriority.HIGH, recommendations[1].priority)
        assertEquals(RecommendationType.INCREASE_BUDGET, recommendations[2].type)
        assertEquals(RecommendationPriority.LOW, recommendations[2].priority)
    }

    @Test
    fun `generate recommendations for medium risk and low confidence includes subscription history and early warning advice`() {
        val recommendations = engine.generateRecommendations(
            budget = BudgetRecommendationBudget(amount = 1000.0),
            forecast = BudgetRecommendationForecast(
                predictedSpending = 600.0,
                predictedRemaining = 400.0,
                confidenceScore = 0.50,
                riskLevel = BudgetRecommendationRiskLevel.MEDIUM,
                overspendProbability = 0.40
            ),
            currentSpending = 100.0
        )

        assertTrue(recommendations.any { it.type == RecommendationType.REVIEW_SUBSCRIPTIONS })
        assertTrue(
            recommendations.any {
                it.type == RecommendationType.GENERAL_ADVICE &&
                    it.title == UiText.fromKey(DomainTextKeys.BUDGET_BUILD_HISTORY)
            }
        )
        assertTrue(
            recommendations.any {
                it.type == RecommendationType.GENERAL_ADVICE &&
                    it.title == UiText.fromKey(DomainTextKeys.BUDGET_EARLY_WARNING)
            }
        )
    }

    @Test
    fun `budget health summary includes formatted spending and forecast metrics`() {
        val summary = engine.getBudgetHealthSummary(
            budget = BudgetRecommendationBudget(amount = 1000.0),
            forecast = BudgetRecommendationForecast(
                predictedSpending = 1120.45,
                predictedRemaining = -120.45,
                confidenceScore = 0.82,
                riskLevel = BudgetRecommendationRiskLevel.HIGH,
                overspendProbability = 0.67
            ),
            currentSpending = 700.0
        )

        assertTrue(Regex("Budget: €1000[.,]00").containsMatchIn(summary))
        assertTrue(Regex("Spent: €700[.,]00 \\(70[.,]0%\\)").containsMatchIn(summary))
        assertTrue(Regex("Remaining: €300[.,]00").containsMatchIn(summary))
        assertTrue(Regex("- Predicted spending: €1120[.,]45").containsMatchIn(summary))
        assertTrue(Regex("- Predicted remaining: €-120[.,]45").containsMatchIn(summary))
        assertTrue(summary.contains("- Risk level: HIGH"))
        assertTrue(summary.contains("- Confidence: 82%"))
        assertTrue(summary.contains("- Overspend probability: 67%"))
    }

    @Test
    fun `get risk emoji returns expected symbol for each risk tier`() {
        assertEquals("✅", engine.getRiskEmoji(BudgetRecommendationRiskLevel.LOW))
        assertEquals("⚠️", engine.getRiskEmoji(BudgetRecommendationRiskLevel.MEDIUM))
        assertEquals("🔴", engine.getRiskEmoji(BudgetRecommendationRiskLevel.HIGH))
        assertEquals("🚨", engine.getRiskEmoji(BudgetRecommendationRiskLevel.CRITICAL))
    }

    @Test
    fun `generate recommendations clamps negative potential savings to zero`() {
        val recommendations = engine.generateRecommendations(
            budget = BudgetRecommendationBudget(amount = 1000.0),
            forecast = BudgetRecommendationForecast(
                predictedSpending = 50.0,
                predictedRemaining = -10.0,
                confidenceScore = 0.9,
                riskLevel = BudgetRecommendationRiskLevel.HIGH,
                overspendProbability = 0.9
            ),
            currentSpending = 950.0
        )

        assertEquals(0.0, recommendations.first { it.type == RecommendationType.REDUCE_SPENDING }.potentialSavings, 0.0)
    }
}
