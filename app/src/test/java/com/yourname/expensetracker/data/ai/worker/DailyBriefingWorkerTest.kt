package com.yourname.expensetracker.data.ai.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.ai.usecase.DeliverProactiveBriefingNotificationUseCase
import com.yourname.expensetracker.domain.ai.usecase.GenerateDashboardBriefingUseCase
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardAnalyticsRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardDataProvider
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = 1000L }

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
                        deliverProactiveBriefingNotificationUseCase,
                        timeProvider
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
        coVerify(exactly = 1) { generateDashboardBriefingUseCase(processed, 1000L) }
        coVerify(exactly = 1) {
            deliverProactiveBriefingNotificationUseCase(dateKey = any(), startedAt = any(), notificationId = any())
        }
    }

    @Test
    fun `no data empty briefing stored`() = runTest {
        val emptyProcessed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(emptyProcessed)

        val result = buildWorker().doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { generateDashboardBriefingUseCase(emptyProcessed, 1000L) }
    }

    // TODO: Tautological mock test — consider adding real behavior assertion
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
        coEvery { generateDashboardBriefingUseCase(processed, 1000L) } throws IllegalStateException("engine down")

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 0) {
            deliverProactiveBriefingNotificationUseCase(dateKey = any(), startedAt = any(), notificationId = any())
        }
    }

    @Test
    fun `worker retries when delivery times out`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)
        coEvery { generateDashboardBriefingUseCase(processed, 1000L) } coAnswers {
            delay(12_100L)
        }

        val result = buildWorker().doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 0) {
            deliverProactiveBriefingNotificationUseCase(dateKey = any(), startedAt = any(), notificationId = any())
        }
    }

    @Test
    fun `worker propagates CancellationException instead of returning success`() = runTest {
        val processed = sampleProcessedData()
        coEvery { dashboardDataProvider.getProcessedDataFlow(analyticsRepository) } returns flowOf(processed)
        coEvery { generateDashboardBriefingUseCase(processed, 1000L) } throws CancellationException("cancelled")

        try {
            buildWorker().doWork()
            throw AssertionError("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected — cancellation must not be swallowed
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
                    headline = UiText.StringResource(R.string.domain_weather_headline_unavailable),
                    summary = UiText.StringResource(R.string.domain_weather_summary_unavailable),
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
