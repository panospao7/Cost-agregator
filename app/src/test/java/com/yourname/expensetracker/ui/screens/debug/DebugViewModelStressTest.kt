package com.yourname.expensetracker.ui.screens.debug

import com.yourname.expensetracker.data.database.entity.BlockedPackage
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebugViewModelStressTest : ViewModelTestUtils() {

    private lateinit var repository: NotificationRepository
    private lateinit var reviewQueueRepository: ReviewQueueRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var notificationSeeder: NotificationSeeder
    private lateinit var timeProvider: TimeProvider
    private lateinit var diagnostics: ServiceDiagnostics
    private lateinit var aiEnvironmentMonitor: AiEnvironmentMonitor
    private lateinit var viewModel: DebugViewModel

    @Before
    override fun setup() {
        super.setup()

        repository = mockk(relaxed = true)
        reviewQueueRepository = mockk(relaxed = true)
        expenseRepository = mockk(relaxed = true)
        budgetRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        notificationSeeder = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        diagnostics = mockk(relaxed = true)
        aiEnvironmentMonitor = mockk(relaxed = true)

        val now = 1_700_000_000_000L
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
        every { repository.getSourceStats() } returns flowOf(listOf(SourceStats("com.revolut", totalNotifications = 3)))
        every { repository.getClassifierStatsFlow() } returns flowOf(ClassifierStats(5, 2, 10, false))

        every { expenseRepository.getTotalSpent() } returns flowOf(42.5)
        every { diagnostics.getStats() } returns ServiceDiagnostics.Stats(1, 0, 0, now, 0)
        every { timeProvider.now() } returns now
        coEvery { aiEnvironmentMonitor.getOnDeviceModelStatus(any()) } returns OnDeviceModelStatus.AVAILABLE
        every { aiEnvironmentMonitor.isNetworkAvailable() } returns true
        every { aiEnvironmentMonitor.isWifiConnected() } returns false

        viewModel = DebugViewModel(
            repository = repository,
            reviewQueueRepository = reviewQueueRepository,
            expenseRepository = expenseRepository,
            budgetRepository = budgetRepository,
            categoryRepository = categoryRepository,
            notificationSeeder = notificationSeeder,
            timeProvider = timeProvider,
            diagnostics = diagnostics,
            aiEnvironmentMonitor = aiEnvironmentMonitor
        )
    }

    @Test
    fun `stress - AI runtime statuses load for all capabilities`() = runTest(testDispatcher) {
        advanceUntilIdle()

        assertEquals(AiCapability.entries.size, viewModel.aiRuntimeStatuses.value.size)
        assertEquals(OnDeviceModelStatus.AVAILABLE, viewModel.aiRuntimeStatuses.value[AiCapability.QUERY_INTERPRETATION])
        assertEquals(true, viewModel.aiRuntimeMeta.value.networkAvailable)
        assertEquals(false, viewModel.aiRuntimeMeta.value.wifiConnected)
    }

    @Test
    fun `stress - service diagnostics delegates to diagnostics provider`() = runTest(testDispatcher) {
        val stats = viewModel.getServiceDiagnostics()
        assertEquals(1, stats.startCount)

        viewModel.resetServiceDiagnostics()
        verify(exactly = 1) { diagnostics.resetStats() }
    }

    @Test
    fun `stress - clear and reset actions delegate to repositories`() = runTest(testDispatcher) {
        coJustRun { repository.deleteAll() }
        coJustRun { expenseRepository.deleteAllExpenses() }
        coJustRun { budgetRepository.deleteAll() }
        coJustRun { repository.resetSourceStats() }

        viewModel.clearAll()
        viewModel.resetExpenses()
        viewModel.resetBudgets()
        viewModel.resetSourceStats()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteAll() }
        coVerify(exactly = 1) { expenseRepository.deleteAllExpenses() }
        coVerify(exactly = 1) { budgetRepository.deleteAll() }
        coVerify(exactly = 1) { repository.resetSourceStats() }
    }

    @Test
    fun `stress - package filter state updates deterministically`() = runTest(testDispatcher) {
        assertEquals(null, viewModel.selectedPackageFilter.value)

        viewModel.setPackageFilter("com.revolut")
        assertEquals("com.revolut", viewModel.selectedPackageFilter.value)

        viewModel.setPackageFilter(null)
        assertEquals(null, viewModel.selectedPackageFilter.value)
    }

    @Test
    fun `stress - simulate test notification sends expected payload`() = runTest(testDispatcher) {
        coJustRun { repository.processAndSave(any()) }

        viewModel.simulateTestNotification()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.processAndSave(match {
                it.packageName == "com.test.bank" &&
                    it.appName == "Test Bank" &&
                    it.title == "Purchase Alert" &&
                    it.text?.contains("€12.50") == true
            })
        }
    }

    @Test
    fun `stress - simulate deposit notification remains crash free and dispatches`() = runTest(testDispatcher) {
        coJustRun { repository.processAndSave(any()) }

        viewModel.simulateDepositNotification()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.processAndSave(match {
                it.title == "Deposit Received" && !it.text.isNullOrBlank()
            })
        }
        assertFalse(viewModel.isSimulating.value)
    }
}
