package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

@OptIn(ExperimentalCoroutinesApi::class)
class SpendingPersonalityClassifierTest {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var insightsEngine: InsightsEngine
    private lateinit var spendingPaceCalculator: SpendingPaceCalculator
    private lateinit var anomalyDetector: AnomalyDetector
    private lateinit var totalsAggregationEngine: TotalsAggregationEngine
    private lateinit var timeProvider: TimeProvider

    private lateinit var classifier: SpendingPersonalityClassifier

    private val fixedNow = millis(2026, Calendar.MARCH, 15, 12)

    @Before
    fun setup() {
        expenseRepository = mockk(relaxed = true)
        budgetRepository = mockk(relaxed = true)
        insightsEngine = mockk(relaxed = true)
        spendingPaceCalculator = mockk(relaxed = true)
        anomalyDetector = mockk(relaxed = true)
        totalsAggregationEngine = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)

        every { timeProvider.now() } returns fixedNow
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { budgetRepository.getActiveBudgets() } returns emptyList()

        classifier = SpendingPersonalityClassifier(
            expenseRepository = expenseRepository,
            budgetRepository = budgetRepository,
            insightsEngine = insightsEngine,
            spendingPaceCalculator = spendingPaceCalculator,
            anomalyDetector = anomalyDetector,
            totalsAggregationEngine = totalsAggregationEngine,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `classify returns insufficient profile when there are no transactions`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()

        val result = classifier.classify()

        assertEquals(SpendingPersonalityType.BALANCED, result.personalityType)
        assertApproxEquals(0.0, result.confidence, 0.0)
        assertTrue(result.featureScores.isEmpty())
        assertTrue(result.explanation.first().contains("Need more transaction history", ignoreCase = true))
        assertEquals(2, result.coachingTips.size)
    }

    @Test
    fun `classify returns insufficient profile when there is only one transaction`() = runTest {
        val singlePurchase = purchase(
            id = 1L,
            amount = 12.0,
            merchant = "Cafe",
            date = millis(2026, Calendar.MARCH, 10, 10)
        )
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(singlePurchase)

        val result = classifier.classify()

        assertEquals(SpendingPersonalityType.BALANCED, result.personalityType)
        assertApproxEquals(0.0, result.confidence, 0.0)
        assertTrue(result.featureScores.isEmpty())
    }

    @Test
    fun `determinePersonalityType classifies PLANNER profile`() {
        val type = invokeDeterminePersonalityType(
            mapOf(
                "impulseRatio" to 0.05,
                "variance" to 0.05,
                "budgetAdherence" to 1.0,
                "anomalyFrequency" to 0.05,
                "weekendSpendShare" to 0.2,
                "nightSpendShare" to 0.1,
                "merchantDiversity" to 0.3,
                "categoryDiversity" to 0.3,
                "transactionsPerMonth" to 30.0
            )
        )

        assertEquals(SpendingPersonalityType.PLANNER, type)
    }

    @Test
    fun `determinePersonalityType classifies IMPULSE profile`() {
        val type = invokeDeterminePersonalityType(
            mapOf(
                "impulseRatio" to 0.95,
                "variance" to 0.9,
                "budgetAdherence" to 0.1,
                "anomalyFrequency" to 0.8,
                "weekendSpendShare" to 0.4,
                "nightSpendShare" to 0.4,
                "merchantDiversity" to 0.5,
                "categoryDiversity" to 0.4,
                "transactionsPerMonth" to 50.0
            )
        )

        assertEquals(SpendingPersonalityType.IMPULSE, type)
    }

    @Test
    fun `determinePersonalityType classifies OPTIMIZER profile`() {
        val type = invokeDeterminePersonalityType(
            mapOf(
                "impulseRatio" to 0.4,
                "variance" to 0.1,
                "budgetAdherence" to 0.4,
                "anomalyFrequency" to 0.05,
                "weekendSpendShare" to 0.05,
                "nightSpendShare" to 0.1,
                "merchantDiversity" to 0.95,
                "categoryDiversity" to 0.9,
                "transactionsPerMonth" to 40.0
            )
        )

        assertEquals(SpendingPersonalityType.OPTIMIZER, type)
    }

    @Test
    fun `determinePersonalityType classifies SOCIAL_SPENDER profile`() {
        val type = invokeDeterminePersonalityType(
            mapOf(
                "impulseRatio" to 0.6,
                "variance" to 0.5,
                "budgetAdherence" to 0.4,
                "anomalyFrequency" to 0.3,
                "weekendSpendShare" to 0.9,
                "nightSpendShare" to 0.8,
                "merchantDiversity" to 0.9,
                "categoryDiversity" to 0.7,
                "transactionsPerMonth" to 60.0
            )
        )

        assertEquals(SpendingPersonalityType.SOCIAL_SPENDER, type)
    }

    @Test
    fun `determinePersonalityType classifies MINIMALIST profile`() {
        val type = invokeDeterminePersonalityType(
            mapOf(
                "impulseRatio" to 0.3,
                "variance" to 0.05,
                "budgetAdherence" to 0.3,
                "anomalyFrequency" to 0.0,
                "weekendSpendShare" to 0.1,
                "nightSpendShare" to 0.1,
                "merchantDiversity" to 0.1,
                "categoryDiversity" to 0.2,
                "transactionsPerMonth" to 5.0
            )
        )

        assertEquals(SpendingPersonalityType.MINIMALIST, type)
    }

    @Test
    fun `determinePersonalityType classifies BALANCED when score gap is too small`() {
        val type = invokeDeterminePersonalityType(
            mapOf(
                "impulseRatio" to 0.5,
                "variance" to 0.5,
                "budgetAdherence" to 0.5,
                "anomalyFrequency" to 0.5,
                "weekendSpendShare" to 0.5,
                "nightSpendShare" to 0.5,
                "merchantDiversity" to 0.5,
                "categoryDiversity" to 0.5,
                "transactionsPerMonth" to 25.0
            )
        )

        assertEquals(SpendingPersonalityType.BALANCED, type)
    }

    @Test
    fun `calculateFeatureScores computes expected values for impulse and diversity metrics`() {
        val incomeDate = millis(2026, Calendar.JANUARY, 5, 9)
        val purchases = listOf(
            purchase(
                id = 1L,
                amount = 100.0,
                merchant = "Alpha",
                merchantKey = "alpha",
                categoryId = 1L,
                date = millis(2026, Calendar.JANUARY, 5, 22)
            ),
            purchase(
                id = 2L,
                amount = 50.0,
                merchant = "Beta",
                merchantKey = "beta",
                categoryId = 1L,
                date = millis(2026, Calendar.JANUARY, 7, 10)
            ),
            purchase(
                id = 3L,
                amount = 50.0,
                merchant = "Beta",
                merchantKey = "beta",
                categoryId = 2L,
                date = millis(2026, Calendar.JANUARY, 7, 21)
            ),
            purchase(
                id = 4L,
                amount = 100.0,
                merchant = "Gamma",
                merchantKey = "gamma",
                categoryId = 2L,
                date = millis(2026, Calendar.JANUARY, 8, 11)
            )
        )
        val income = Expense(
            id = 99L,
            amount = 1200.0,
            merchant = "Salary",
            transactionType = TransactionType.DEPOSIT,
            date = incomeDate
        )
        val budgets = listOf(
            Budget(
                id = 1L,
                categoryId = 1L,
                amount = 200.0,
                period = BudgetPeriod.MONTHLY,
                startDate = incomeDate
            ),
            Budget(
                id = 2L,
                categoryId = 2L,
                amount = 100.0,
                period = BudgetPeriod.MONTHLY,
                startDate = incomeDate
            )
        )

        val featureScores = invokeCalculateFeatureScores(
            purchases = purchases,
            allExpenses = purchases + income,
            budgets = budgets
        )

        assertApproxEquals(0.25, featureScores.getValue("impulseRatio"), 0.0001)
        assertApproxEquals(0.75, featureScores.getValue("merchantDiversity"), 0.0001)
        assertApproxEquals(0.5, featureScores.getValue("nightSpendShare"), 0.0001)
        assertApproxEquals(0.0, featureScores.getValue("variance"), 0.0001)
        assertApproxEquals(0.7, featureScores.getValue("budgetAdherence"), 0.0001)
        assertApproxEquals(0.0, featureScores.getValue("anomalyFrequency"), 0.0001)
        assertApproxEquals(0.5, featureScores.getValue("categoryDiversity"), 0.0001)
        assertApproxEquals(4.0 / 3.0, featureScores.getValue("transactionsPerMonth"), 0.0001)
        assertApproxEquals(0.375, featureScores.getValue("avgTransactionSize"), 0.0001)
        assertTrue(featureScores.values.none { it.isNaN() })
    }

    @Test
    fun `calculateConfidence returns zero and never NaN for empty feature map`() {
        val confidence = invokeCalculateConfidence(
            transactionCount = 25,
            featureScores = emptyMap()
        )

        assertFalse(confidence.isNaN())
        assertApproxEquals(0.0, confidence, 0.0)
    }

    @Test
    fun `generateCoachingTips returns non-empty type specific tips`() {
        val expectedKeywords = mapOf(
            SpendingPersonalityType.PLANNER to "budget",
            SpendingPersonalityType.IMPULSE to "24-hour rule",
            SpendingPersonalityType.OPTIMIZER to "value",
            SpendingPersonalityType.SOCIAL_SPENDER to "social",
            SpendingPersonalityType.MINIMALIST to "controlled",
            SpendingPersonalityType.BALANCED to "balanced"
        )

        expectedKeywords.forEach { (type, keyword) ->
            val tips = invokeGenerateCoachingTips(type, emptyMap())

            assertEquals(3, tips.size)
            assertTrue(
                "Expected coaching tips for $type to mention '$keyword'",
                tips.any { it.contains(keyword, ignoreCase = true) }
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeCalculateFeatureScores(
        purchases: List<Expense>,
        allExpenses: List<Expense>,
        budgets: List<Budget>
    ): Map<String, Double> {
        val method = SpendingPersonalityClassifier::class.java.getDeclaredMethod(
            "calculateFeatureScores",
            List::class.java,
            List::class.java,
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(classifier, purchases, allExpenses, budgets) as Map<String, Double>
    }

    private fun invokeDeterminePersonalityType(featureScores: Map<String, Double>): SpendingPersonalityType {
        val method = SpendingPersonalityClassifier::class.java.getDeclaredMethod(
            "determinePersonalityType",
            Map::class.java
        )
        method.isAccessible = true
        return method.invoke(classifier, featureScores) as SpendingPersonalityType
    }

    private fun invokeCalculateConfidence(
        transactionCount: Int,
        featureScores: Map<String, Double>
    ): Double {
        val method = SpendingPersonalityClassifier::class.java.getDeclaredMethod(
            "calculateConfidence",
            Int::class.javaPrimitiveType!!,
            Map::class.java
        )
        method.isAccessible = true
        return method.invoke(classifier, transactionCount, featureScores) as Double
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeGenerateCoachingTips(
        personalityType: SpendingPersonalityType,
        featureScores: Map<String, Double>
    ): List<String> {
        val method = SpendingPersonalityClassifier::class.java.getDeclaredMethod(
            "generateCoachingTips",
            SpendingPersonalityType::class.java,
            Map::class.java
        )
        method.isAccessible = true
        return method.invoke(classifier, personalityType, featureScores) as List<String>
    }

    private fun purchase(
        id: Long,
        amount: Double,
        merchant: String,
        date: Long,
        merchantKey: String? = null,
        categoryId: Long? = null
    ): Expense = Expense(
        id = id,
        amount = amount,
        merchant = merchant,
        merchantKey = merchantKey,
        transactionType = TransactionType.PURCHASE,
        date = date,
        categoryId = categoryId
    )

    private fun millis(year: Int, month: Int, day: Int, hour: Int = 0): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
