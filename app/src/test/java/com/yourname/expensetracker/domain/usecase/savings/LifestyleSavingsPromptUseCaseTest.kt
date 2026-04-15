package com.yourname.expensetracker.domain.usecase.savings

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.repository.PromptStateRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.lifestyle.LifestyleInflationDetector
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LifestyleSavingsPromptUseCaseTest {

    private lateinit var lifestyleInflationDetector: LifestyleInflationDetector
    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var promptStateRepository: PromptStateRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var useCase: LifestyleSavingsPromptUseCase

    @Before
    fun setup() {
        lifestyleInflationDetector = mockk()
        savingsGoalRepository = mockk()
        promptStateRepository = mockk()
        timeProvider = mockk(relaxed = true)

        useCase = LifestyleSavingsPromptUseCase(
            lifestyleInflationDetector = lifestyleInflationDetector,
            savingsGoalRepository = savingsGoalRepository,
            promptStateRepository = promptStateRepository,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `evaluateAndPrompt returns null when prompted within cooldown window`() = runTest {
        coEvery {
            promptStateRepository.hasPromptedRecently(
                LifestyleSavingsPromptUseCase.PROMPT_TYPE,
                LifestyleSavingsPromptUseCase.COOLDOWN_DAYS
            )
        } returns true

        val result = useCase.evaluateAndPrompt()

        assertNull(result)
        coVerify(exactly = 0) { lifestyleInflationDetector.analyzeLifestyleInflation(any()) }
        coVerify(exactly = 0) { promptStateRepository.recordPrompt(any()) }
    }

    @Test
    fun `evaluateAndPrompt returns null when user already accepted recently`() = runTest {
        coEvery { promptStateRepository.hasPromptedRecently(any(), any()) } returns false
        coEvery {
            promptStateRepository.hasUserTakenAction(
                LifestyleSavingsPromptUseCase.PROMPT_TYPE,
                "ACCEPTED",
                90
            )
        } returns true

        val result = useCase.evaluateAndPrompt()

        assertNull(result)
        coVerify(exactly = 0) { lifestyleInflationDetector.analyzeLifestyleInflation(any()) }
        coVerify(exactly = 0) { promptStateRepository.recordPrompt(any()) }
    }

    @Test
    fun `evaluateAndPrompt converts savings rate ratio to percentage and caps uplift`() = runTest {
        coEvery { promptStateRepository.hasPromptedRecently(any(), any()) } returns false
        coEvery { promptStateRepository.hasUserTakenAction(any(), any(), any()) } returns false
        coEvery { promptStateRepository.recordPrompt(LifestyleSavingsPromptUseCase.PROMPT_TYPE) } returns 1L
        coEvery { lifestyleInflationDetector.analyzeLifestyleInflation(12) } returns report(
            monthlyData = sixMonthData(
                lastSavingsRate = 0.10,
                previousIncome = 1000.0,
                currentIncome = 1200.0
            )
        )
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())

        val result = useCase.evaluateAndPrompt()

        assertNotNull(result)
        assertApproxEquals(10.0, result!!.currentSavingsRate, 0.0001)
        // income growth = 20% => alpha uplift = 10pp, capped at 20% of current savings rate = 2pp
        assertApproxEquals(2.0, result.suggestedMonthlyUplift, 0.0001)
        coVerify(exactly = 1) { promptStateRepository.recordPrompt(LifestyleSavingsPromptUseCase.PROMPT_TYPE) }
    }

    @Test
    fun `evaluateAndPrompt keeps percentage savings rate and uses growth-based uplift`() = runTest {
        coEvery { promptStateRepository.hasPromptedRecently(any(), any()) } returns false
        coEvery { promptStateRepository.hasUserTakenAction(any(), any(), any()) } returns false
        coEvery { promptStateRepository.recordPrompt(LifestyleSavingsPromptUseCase.PROMPT_TYPE) } returns 1L
        coEvery { lifestyleInflationDetector.analyzeLifestyleInflation(12) } returns report(
            monthlyData = sixMonthData(
                lastSavingsRate = 15.0,
                previousIncome = 1000.0,
                currentIncome = 1100.0
            )
        )
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())

        val result = useCase.evaluateAndPrompt()

        assertNotNull(result)
        assertApproxEquals(15.0, result!!.currentSavingsRate, 0.0001)
        // income growth = 10% => alpha uplift = 5pp, cap = 3pp => suggested = 3pp
        assertApproxEquals(3.0, result.suggestedMonthlyUplift, 0.0001)
        coVerify(exactly = 1) { promptStateRepository.recordPrompt(LifestyleSavingsPromptUseCase.PROMPT_TYPE) }
    }

    @Test
    fun `evaluateAndPrompt returns null when inflation is below threshold`() = runTest {
        coEvery { promptStateRepository.hasPromptedRecently(any(), any()) } returns false
        coEvery { promptStateRepository.hasUserTakenAction(any(), any(), any()) } returns false
        coEvery { lifestyleInflationDetector.analyzeLifestyleInflation(12) } returns report(
            lifestyleInflationRate = 0.049
        )
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())

        val result = useCase.evaluateAndPrompt()

        assertNull(result)
        coVerify(exactly = 0) { promptStateRepository.recordPrompt(any()) }
    }

    @Test
    fun `evaluateAndPrompt returns null when monthly data is missing`() = runTest {
        coEvery { promptStateRepository.hasPromptedRecently(any(), any()) } returns false
        coEvery { promptStateRepository.hasUserTakenAction(any(), any(), any()) } returns false
        coEvery { lifestyleInflationDetector.analyzeLifestyleInflation(12) } returns report(
            monthlyData = listOf(
                monthData("2026-01", income = 1000.0, savingsRate = 10.0)
            )
        )
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())

        val result = useCase.evaluateAndPrompt()

        assertNull(result)
        coVerify(exactly = 0) { promptStateRepository.recordPrompt(any()) }
    }

    @Test
    fun `evaluateAndPrompt returns null when lifestyle creep is not detected`() = runTest {
        coEvery { promptStateRepository.hasPromptedRecently(any(), any()) } returns false
        coEvery { promptStateRepository.hasUserTakenAction(any(), any(), any()) } returns false
        coEvery { lifestyleInflationDetector.analyzeLifestyleInflation(12) } returns report(
            lifestyleCreepDetected = false,
            incomeElasticity = 1.1
        )
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())

        val result = useCase.evaluateAndPrompt()

        assertNull(result)
        coVerify(exactly = 0) { promptStateRepository.recordPrompt(any()) }
    }

    @Test
    fun `evaluateAndPrompt returns null when confidence is below threshold`() = runTest {
        coEvery { promptStateRepository.hasPromptedRecently(any(), any()) } returns false
        coEvery { promptStateRepository.hasUserTakenAction(any(), any(), any()) } returns false
        coEvery { lifestyleInflationDetector.analyzeLifestyleInflation(12) } returns report(
            lifestyleCreepDetected = false,
            incomeElasticity = 1.2,
            incomeGrowthRate = 0.10,
            spendingGrowthRate = 0.12,
            monthlyData = sixMonthData()
        )
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())

        val result = useCase.evaluateAndPrompt()

        assertNull(result)
        coVerify(exactly = 0) { promptStateRepository.recordPrompt(any()) }
    }

    @Test
    fun `evaluateAndPrompt handles zero previous income with minimum uplift`() = runTest {
        coEvery { promptStateRepository.hasPromptedRecently(any(), any()) } returns false
        coEvery { promptStateRepository.hasUserTakenAction(any(), any(), any()) } returns false
        coEvery { promptStateRepository.recordPrompt(LifestyleSavingsPromptUseCase.PROMPT_TYPE) } returns 1L
        coEvery { lifestyleInflationDetector.analyzeLifestyleInflation(12) } returns report(
            monthlyData = listOf(
                monthData("2026-01", income = 0.0, savingsRate = 0.08),
                monthData("2026-02", income = 1000.0, savingsRate = 0.12)
            ),
            // Keep confidence high enough even with only 2 months of data.
            incomeElasticity = 1.6,
            incomeGrowthRate = 0.10,
            spendingGrowthRate = 0.35,
            lifestyleCreepDetected = true
        )
        every { savingsGoalRepository.getAllGoals() } returns flowOf(
            listOf(
                SavingsGoal(
                    id = 1L,
                    name = "Emergency Fund",
                    targetAmount = 5000.0,
                    currentAmount = 1200.0,
                    targetDate = null,
                    protectionLevel = GoalProtectionLevel.WARNING,
                    createdAt = 1_700_000_000_000L
                )
            )
        )

        val result = useCase.evaluateAndPrompt()

        assertNotNull(result)
        assertApproxEquals(12.0, result!!.currentSavingsRate, 0.0001)
        // previous income is zero => income growth delta is forced to 0 => uplift min floor applies (1pp).
        assertApproxEquals(1.0, result.suggestedMonthlyUplift, 0.0001)
        coVerify(exactly = 1) { promptStateRepository.recordPrompt(LifestyleSavingsPromptUseCase.PROMPT_TYPE) }
    }

    private fun report(
        lifestyleInflationRate: Double = 0.12,
        monthlyData: List<LifestyleInflationDetector.MonthlyLifestyleData> = sixMonthData(),
        lifestyleCreepDetected: Boolean = true,
        incomeElasticity: Double = 1.6,
        incomeGrowthRate: Double = 0.10,
        spendingGrowthRate: Double = 0.25
    ): LifestyleInflationDetector.LifestyleInflationReport {
        return LifestyleInflationDetector.LifestyleInflationReport(
            analysisPeriodMonths = 12,
            incomeSpendingCorrelation = 0.8,
            incomeElasticity = incomeElasticity,
            lifestyleCreepDetected = lifestyleCreepDetected,
            lifestyleCreepAlerts = listOf(
                LifestyleInflationDetector.LifestyleCreepAlert(
                    month = "2026-06",
                    incomeGrowthPercent = 10.0,
                    spendingGrowthPercent = 18.0,
                    discretionaryGrowthPercent = 12.0,
                    severity = LifestyleInflationDetector.CreepSeverity.MEDIUM,
                    description = "Spending increased faster than income"
                )
            ),
            incomeGrowthRate = incomeGrowthRate,
            spendingGrowthRate = spendingGrowthRate,
            lifestyleInflationRate = lifestyleInflationRate,
            hedonicAdaptationScore = 20.0,
            monthlyData = monthlyData,
            recommendations = emptyList()
        )
    }

    private fun sixMonthData(
        lastSavingsRate: Double = 12.0,
        previousIncome: Double = 1000.0,
        currentIncome: Double = 1200.0
    ): List<LifestyleInflationDetector.MonthlyLifestyleData> {
        return listOf(
            monthData("2026-01", income = 900.0, savingsRate = 10.0),
            monthData("2026-02", income = 950.0, savingsRate = 11.0),
            monthData("2026-03", income = 980.0, savingsRate = 11.5),
            monthData("2026-04", income = 1000.0, savingsRate = 12.0),
            monthData("2026-05", income = previousIncome, savingsRate = 12.0),
            monthData("2026-06", income = currentIncome, savingsRate = lastSavingsRate)
        )
    }

    private fun monthData(
        month: String,
        income: Double,
        savingsRate: Double
    ): LifestyleInflationDetector.MonthlyLifestyleData {
        return LifestyleInflationDetector.MonthlyLifestyleData(
            month = month,
            income = income,
            totalSpending = 0.0,
            discretionarySpending = 0.0,
            essentialSpending = 0.0,
            savingsRate = savingsRate,
            lifestyleScore = 50.0
        )
    }
}
