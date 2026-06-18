package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.model.BudgetSnapshot
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
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
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        coEvery { budgetRepository.getActiveBudgetSnapshots() } returns emptyList()

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
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()

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
        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns listOf(singlePurchase)

        val result = classifier.classify()

        assertEquals(SpendingPersonalityType.BALANCED, result.personalityType)
        assertApproxEquals(0.0, result.confidence, 0.0)
        assertTrue(result.featureScores.isEmpty())
    }

    @Test
    fun `classify uses snapshot repositories and returns sufficient profile with ten purchases`() = runTest {
        val deposits = listOf(
            deposit(id = 100L, amount = 2000.0, date = millis(2026, Calendar.JANUARY, 1, 9)),
            deposit(id = 101L, amount = 2000.0, date = millis(2026, Calendar.FEBRUARY, 1, 9)),
            deposit(id = 102L, amount = 2000.0, date = millis(2026, Calendar.MARCH, 1, 9))
        )
        val purchases = listOf(
            purchase(id = 1L, amount = 100.0, merchant = "Grocer", date = millis(2026, Calendar.JANUARY, 1, 21), categoryId = 1L),
            purchase(id = 2L, amount = 120.0, merchant = "Grocer", date = millis(2026, Calendar.JANUARY, 8, 10), categoryId = 1L),
            purchase(id = 3L, amount = 90.0, merchant = "Fuel", date = millis(2026, Calendar.JANUARY, 15, 11), categoryId = 2L),
            purchase(id = 4L, amount = 110.0, merchant = "Fuel", date = millis(2026, Calendar.JANUARY, 22, 20), categoryId = 2L),
            purchase(id = 5L, amount = 80.0, merchant = "Market", date = millis(2026, Calendar.FEBRUARY, 2, 12), categoryId = 1L),
            purchase(id = 6L, amount = 95.0, merchant = "Market", date = millis(2026, Calendar.FEBRUARY, 10, 18), categoryId = 1L),
            purchase(id = 7L, amount = 105.0, merchant = "Utilities", date = millis(2026, Calendar.FEBRUARY, 16, 9), categoryId = 3L),
            purchase(id = 8L, amount = 115.0, merchant = "Utilities", date = millis(2026, Calendar.FEBRUARY, 24, 19), categoryId = 3L),
            purchase(id = 9L, amount = 98.0, merchant = "Grocer", date = millis(2026, Calendar.MARCH, 3, 10), categoryId = 1L),
            purchase(id = 10L, amount = 102.0, merchant = "Fuel", date = millis(2026, Calendar.MARCH, 9, 11), categoryId = 2L)
        )
        val budgets = listOf(
            BudgetSnapshot(categoryId = 1L, amount = 450.0, currency = "EUR"),
            BudgetSnapshot(categoryId = 2L, amount = 350.0, currency = "EUR"),
            BudgetSnapshot(categoryId = 3L, amount = 250.0, currency = "EUR")
        )

        coEvery { expenseRepository.getExpenseSnapshotsBetween(any(), any()) } returns (purchases + deposits)
        coEvery { budgetRepository.getActiveBudgetSnapshots() } returns budgets

        val result = classifier.classify()

        assertTrue(result.personalityType in SpendingPersonalityType.values())
        assertTrue(result.confidence in 0.0..1.0)
        assertFalse(result.featureScores.isEmpty())
        assertEquals(10.0 / 3.0, result.featureScores.getValue("transactionsPerMonth"), 0.0001)
        assertEquals(fixedNow, result.lastUpdated)
        assertEquals(3, result.coachingTips.size)
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
        val income = deposit(
            id = 99L,
            amount = 1200.0,
            date = incomeDate
        )
        val budgets = listOf(
            BudgetSnapshot(categoryId = 1L, amount = 200.0, currency = "EUR"),
            BudgetSnapshot(categoryId = 2L, amount = 100.0, currency = "EUR")
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
    fun `calculateConfidence normalizes transactionsPerMonth so result remains in range`() {
        val confidence = invokeCalculateConfidence(
            transactionCount = 90,
            featureScores = mapOf(
                "impulseRatio" to 0.4,
                "merchantDiversity" to 0.5,
                "weekendSpendShare" to 0.2,
                "nightSpendShare" to 0.2,
                "variance" to 0.3,
                "budgetAdherence" to 0.8,
                "anomalyFrequency" to 0.1,
                "categoryDiversity" to 0.5,
                "avgTransactionSize" to 0.4,
                "transactionsPerMonth" to 320.0
            )
        )

        assertFalse(confidence.isNaN())
        assertTrue(confidence in 0.0..1.0)
    }

    @Test
    fun `calculateConfidence still computes from normalized scale features when transaction count key absent`() {
        val confidence = invokeCalculateConfidence(
            transactionCount = 60,
            featureScores = mapOf(
                "impulseRatio" to 0.2,
                "merchantDiversity" to 0.6,
                "weekendSpendShare" to 0.3,
                "nightSpendShare" to 0.1,
                "variance" to 0.4,
                "budgetAdherence" to 0.7,
                "anomalyFrequency" to 0.2,
                "categoryDiversity" to 0.5,
                "avgTransactionSize" to 0.35
            )
        )

        assertFalse(confidence.isNaN())
        assertTrue(confidence in 0.0..1.0)
        assertTrue(confidence > 0.0)
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
        purchases: List<ExpenseSnapshot>,
        allExpenses: List<ExpenseSnapshot>,
        budgets: List<BudgetSnapshot>
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
    ): ExpenseSnapshot = ExpenseSnapshot(
        id = id,
        amount = amount,
        effectiveAmount = amount,
        currency = "EUR",
        merchant = merchant,
        merchantKey = merchantKey,
        transactionType = DomainTransactionType.PURCHASE,
        date = date,
        categoryId = categoryId,
        isNotMine = false,
        transferDirection = null,
        notes = null
    )

    private fun deposit(
        id: Long,
        amount: Double,
        date: Long
    ): ExpenseSnapshot = ExpenseSnapshot(
        id = id,
        amount = amount,
        effectiveAmount = amount,
        currency = "EUR",
        merchant = "Salary",
        merchantKey = "salary",
        transactionType = DomainTransactionType.DEPOSIT,
        date = date,
        categoryId = null,
        isNotMine = false,
        transferDirection = null,
        notes = null
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