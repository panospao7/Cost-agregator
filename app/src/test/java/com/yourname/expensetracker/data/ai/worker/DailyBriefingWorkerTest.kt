package com.yourname.expensetracker.data.ai.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.domain.ai.usecase.DeliverProactiveBriefingNotificationUseCase
import com.yourname.expensetracker.domain.ai.usecase.GenerateDashboardBriefingUseCase
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardAnalyticsRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardDataProvider
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DailyBriefingWorkerTest {

    private lateinit var context: Context
    private lateinit var generateDashboardBriefingUseCase: GenerateDashboardBriefingUseCase
    private lateinit var dashboardDataProvider: DashboardDataProvider
    private lateinit var analyticsRepository: DashboardAnalyticsRepository
    private lateinit var deliverProactiveBriefingNotificationUseCase: DeliverProactiveBriefingNotificationUseCase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        generateDashboardBriefingUseCase = mockk(relaxed = true)
        dashboardDataProvider = mockk(relaxed = true)
        analyticsRepository = mockk(relaxed = true)
        deliverProactiveBriefingNotificationUseCase = mockk(relaxed = true)
    }

    private fun buildWorker(): DailyBriefingWorker {
        return TestListenableWorkerBuilder<DailyBriefingWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): DailyBriefingWorker {
                    return DailyBriefingWorker(
                        appContext,
                        workerParameters,
                        generateDashboardBriefingUseCase,
                        dashboardDataProvider,
                        analyticsRepository,
                        deliverProactiveBriefingNotificationUseCase
                    )
                }
            })
            .build()
    }

    @Test
    fun `briefing generated and stored`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { generateDashboardBriefingUseCase(processed) }
        coVerify(exactly = 1) {
            deliverProactiveBriefingNotificationUseCase(dateKey = any(), startedAt = any())
        }
    }

    @Test
    fun `no data empty briefing stored`() = runTest {
        val emptyProcessed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(emptyProcessed)

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { generateDashboardBriefingUseCase(emptyProcessed) }
    }

    @Test
    fun `worker returns success`() = runTest {
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(sampleProcessedData())

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
    }

    @Test
    fun `worker handles engine failure gracefully`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)
        coEvery { generateDashboardBriefingUseCase(processed) } throws IllegalStateException("engine down")

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 0) {
            deliverProactiveBriefingNotificationUseCase(dateKey = any(), startedAt = any())
        }
    }

    private fun sampleProcessedData(): ProcessedDashboardData {
        return ProcessedDashboardData(
            data = DashboardData(
                expenses = emptyList(),
                categories = emptyList(),
                budgetStatuses = emptyList(),
                pendingCount = 0,
                weather = com.yourname.expensetracker.domain.model.dashboard.FinancialWeather(
                    state = com.yourname.expensetracker.domain.model.dashboard.WeatherState.UNKNOWN,
                    headline = "",
                    summary = "",
                    icon = "",
                    riskLevel = 0,
                    totalCommitted = 0.0,
                    totalLikely = 0.0,
                    predictedDiscretionary = 0.0,
                    discretionaryBudget = 0.0
                ),
                recurringPatterns = emptyList(),
                plannedExpenses = emptyList(),
                goals = emptyList()
            ),
            summary = SpendingSummary(
                totalSpent = 0.0,
                previousTotalSpent = null,
                changePercent = null,
                dailyHistory = emptyList(),
                previousDailyHistory = emptyList(),
                transactionCount = 0
            ),
            categoryBreakdown = emptyList()
        )
    }
}
