package com.yourname.expensetracker.ui.screens.debug

import android.content.Context
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.BlockedPackage
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus
import com.yourname.expensetracker.domain.ai.model.AiEngagementState
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRuntimeStatusSummary
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.GetAiRuntimeStatusUseCase
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.debug.NotificationSeeder
import com.yourname.expensetracker.domain.debug.ServiceDiagnostics
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * DebugViewModel behaviour: AI runtime diagnostics refresh (DebugViewModel.kt:64-74, :335-347),
 * engagement state exposure, service-diagnostics delegation (:109-115) and the simulated
 * purchase notification payload (:283-295).
 *
 * The destructive clearAll() path is intentionally not exercised here: it depends on the
 * deprecated-ERROR NotificationRepository.deleteAll().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugViewModelTest : ViewModelTestUtils() {

    private val now = 1_700_000_000_000L

    private lateinit var context: Context
    private lateinit var repository: NotificationRepository
    private lateinit var reviewQueueRepository: ReviewQueueRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var notificationSeeder: NotificationSeeder
    private lateinit var timeProvider: TimeProvider
    private lateinit var diagnostics: ServiceDiagnostics
    private lateinit var aiRuntimeDiagnostics: AiRuntimeDiagnostics
    private lateinit var getAiRuntimeStatusUseCase: GetAiRuntimeStatusUseCase
    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var aiEngagementRepository: AiEngagementRepository
    private lateinit var viewModel: DebugViewModel

    @Before
    override fun setup() {
        super.setup()

        context = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        reviewQueueRepository = mockk(relaxed = true)
        expenseRepository = mockk(relaxed = true)
        budgetRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        notificationSeeder = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        diagnostics = mockk(relaxed = true)
        aiRuntimeDiagnostics = mockk(relaxed = true)
        aiSettingsRepository = mockk(relaxed = true)
        aiEngagementRepository = mockk(relaxed = true)

        val sampleNotification = RawNotification(
            packageName = "com.revolut",
            appName = "Revolut",
            title = "Spent",
            text = "Spent EUR 12.30 at Cafe",
            timestamp = now,
            capturedAt = now
        )

        every { repository.getRecentNotifications(200) } returns flowOf(listOf(sampleNotification))
        every { repository.getCount() } returns flowOf(1)
        every { repository.getAllPackages() } returns flowOf(listOf("com.revolut"))
        every { repository.getBlockedPackages() } returns flowOf(listOf(BlockedPackage("com.spam.app")))
        every { repository.getSourceStats() } returns flowOf(
            listOf(SourceStats("com.revolut", totalNotifications = 3, lastSeen = 0L))
        )
        every { repository.getClassifierStatsFlow() } returns flowOf(ClassifierStats(5, 2, 10, false))

        every { expenseRepository.getTotalSpent() } returns flowOf(42.5)
        every { diagnostics.getStats() } returns ServiceDiagnostics.Stats(1, 0, 0, now, 0)
        every { timeProvider.now() } returns now
        every { aiSettingsRepository.settings() } returns flowOf(
            AiSettings(aiEnabled = true, allowCloudAi = true)
        )
        every { aiEngagementRepository.engagementState() } returns flowOf(
            AiEngagementState(
                lastDeliveredDashboardBriefingKey = "dashboard_home:2026-03-17",
                lastOpenedDashboardBriefingKey = "dashboard_home:2026-03-16"
            )
        )
        getAiRuntimeStatusUseCase = mockk(relaxed = true)
        coEvery { getAiRuntimeStatusUseCase(any()) } returns AiRuntimeStatusSummary(
            capabilities = AiCapability.entries.map { capability ->
                AiCapabilityRuntimeStatus(
                    capability = capability,
                    status = OnDeviceModelStatus.AVAILABLE,
                    message = null,
                    actionLabel = null,
                    route = AiRoute.CLOUD,
                    routeReason = "Cloud route available",
                    providerName = "google-ai-studio",
                    modelName = "gemini-2.5-flash"
                )
            },
            highestPriorityMessage = null,
            networkAvailable = true,
            wifiConnected = false,
            lastRefreshedAt = now
        )

        val databaseBackupRepository =
            mockk<com.yourname.expensetracker.domain.backup.DatabaseBackupRepository>(relaxed = true)

        viewModel = DebugViewModel(
            context = context,
            repository = repository,
            reviewQueueRepository = reviewQueueRepository,
            expenseRepository = expenseRepository,
            budgetRepository = budgetRepository,
            categoryRepository = categoryRepository,
            notificationSeeder = notificationSeeder,
            timeProvider = timeProvider,
            diagnostics = diagnostics,
            getAiRuntimeStatusUseCase = getAiRuntimeStatusUseCase,
            aiSettingsRepository = aiSettingsRepository,
            aiEngagementRepository = aiEngagementRepository,
            aiRuntimeDiagnostics = aiRuntimeDiagnostics,
            databaseBackupRepository = databaseBackupRepository,
            csvExpenseImporter = mockk(relaxed = true),
            legacyDataMigrationService = mockk(relaxed = true)
        )
    }

    @Test
    fun `AI runtime statuses load for all capabilities`() = runTest(testDispatcher) {
        val settingsJob = backgroundScope.launch(testDispatcher) {
            viewModel.aiSettings.collect { }
        }
        runCurrent()

        assertEquals(AiCapability.entries.size, viewModel.aiRuntimeStatuses.value.size)
        assertEquals(
            OnDeviceModelStatus.AVAILABLE,
            viewModel.aiRuntimeStatuses.value[AiCapability.QUERY_INTERPRETATION]
        )
        assertEquals(true, viewModel.aiRuntimeMeta.value.networkAvailable)
        assertEquals(false, viewModel.aiRuntimeMeta.value.wifiConnected)
        assertEquals(true, viewModel.aiSettings.value.allowCloudAi)
        assertEquals(AiRoute.CLOUD, viewModel.aiRuntimeMeta.value.capabilities.first().route)

        settingsJob.cancel()
    }

    @Test
    fun `engagement state is exposed for phase 4a diagnostics`() = runTest(testDispatcher) {
        val engagementJob = backgroundScope.launch(testDispatcher) {
            viewModel.aiEngagementState.collect { }
        }
        runCurrent()

        assertEquals(
            "dashboard_home:2026-03-17",
            viewModel.aiEngagementState.value.lastDeliveredDashboardBriefingKey
        )
        assertEquals(
            "dashboard_home:2026-03-16",
            viewModel.aiEngagementState.value.lastOpenedDashboardBriefingKey
        )

        engagementJob.cancel()
    }

    @Test
    fun `service diagnostics delegates to diagnostics provider`() = runTest(testDispatcher) {
        val stats = viewModel.getServiceDiagnostics()
        assertEquals(1, stats.startCount)

        viewModel.resetServiceDiagnostics()
        verify(exactly = 1) { diagnostics.resetStats() }
    }

    @Test
    fun `resetExpenses resetBudgets and resetSourceStats delegate to their repositories`() =
        runTest(testDispatcher) {
            viewModel.resetExpenses()
            viewModel.resetBudgets()
            viewModel.resetSourceStats()
            runCurrent()

            coVerify(exactly = 1) { expenseRepository.deleteAllExpenses() }
            coVerify(exactly = 1) { budgetRepository.deleteAll() }
            coVerify(exactly = 1) { repository.resetSourceStats() }
        }

    @Test
    fun `simulate test notification sends expected payload`() = runTest(testDispatcher) {
        // Real resource values from strings.xml (debug_purchase_alert_title /
        // debug_purchase_message_format); the message stub only matches the exact args the
        // view model is expected to pass, so a wrong amount or merchant yields an empty text.
        every { context.getString(R.string.debug_purchase_alert_title) } returns "Purchase Alert"
        every {
            context.getString(R.string.debug_purchase_message_format, 12.50, "Amazon")
        } returns "You paid €12.50 at Amazon"
        coJustRun { repository.processAndSave(any()) }

        viewModel.simulateTestNotification()
        runCurrent()

        coVerify(exactly = 1) {
            repository.processAndSave(match {
                it.packageName == "com.test.bank" &&
                    it.appName == "Test Bank" &&
                    it.title == "Purchase Alert" &&
                    it.text == "You paid €12.50 at Amazon" &&
                    it.timestamp == now &&
                    it.capturedAt == now
            })
        }
    }
}
