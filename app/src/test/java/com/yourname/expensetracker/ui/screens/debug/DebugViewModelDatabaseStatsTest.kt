package com.yourname.expensetracker.ui.screens.debug

import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.ai.model.AiEngagementState
import com.yourname.expensetracker.domain.ai.model.AiRuntimeStatusSummary
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.GetAiRuntimeStatusUseCase
import com.yourname.expensetracker.domain.backup.DatabaseBackupRepository
import com.yourname.expensetracker.domain.backup.DatabaseStats
import com.yourname.expensetracker.domain.backup.DatabaseStatsFailureReason
import com.yourname.expensetracker.domain.backup.DatabaseStatsUnavailableException
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
class DebugViewModelDatabaseStatsTest : ViewModelTestUtils() {
    private val backupRepository = mockk<DatabaseBackupRepository>()
    private lateinit var viewModel: DebugViewModel

    @Before
    override fun setup() {
        super.setup()
        val notifications = mockk<NotificationRepository>(relaxed = true)
        every { notifications.getRecentNotifications(200) } returns flowOf(emptyList())
        every { notifications.getCount() } returns flowOf(0)
        every { notifications.getAllPackages() } returns flowOf(emptyList())
        every { notifications.getBlockedPackages() } returns flowOf(emptyList())
        every { notifications.getSourceStats() } returns flowOf(emptyList())
        every { notifications.getClassifierStatsFlow() } returns flowOf(ClassifierStats(0, 0, 0, false))
        val expenses = mockk<ExpenseRepository>(relaxed = true)
        every { expenses.getTotalSpent() } returns flowOf(0.0)
        val settings = mockk<AiSettingsRepository>()
        every { settings.settings() } returns flowOf(AiSettings())
        val engagement = mockk<AiEngagementRepository>()
        every { engagement.engagementState() } returns flowOf(AiEngagementState())
        val runtimeStatus = mockk<GetAiRuntimeStatusUseCase>()
        coEvery { runtimeStatus(any()) } returns AiRuntimeStatusSummary(emptyList(), null)
        val runtimeDiagnostics = mockk<AiRuntimeDiagnostics>(relaxed = true)
        every { runtimeDiagnostics.getRecentEvents() } returns emptyList()

        viewModel = DebugViewModel(
            context = mockk(relaxed = true),
            repository = notifications,
            reviewQueueRepository = mockk(relaxed = true),
            expenseRepository = expenses,
            budgetRepository = mockk(relaxed = true),
            categoryRepository = mockk(relaxed = true),
            notificationSeeder = mockk(relaxed = true),
            timeProvider = FakeTimeProvider(1_700_000_000_000L),
            diagnostics = mockk(relaxed = true),
            getAiRuntimeStatusUseCase = runtimeStatus,
            aiSettingsRepository = settings,
            aiEngagementRepository = engagement,
            aiRuntimeDiagnostics = runtimeDiagnostics,
            databaseBackupRepository = backupRepository,
            csvExpenseImporter = mockk(relaxed = true),
            legacyDataMigrationService = mockk(relaxed = true)
        )
    }

    @After
    override fun tearDown() {
        try {
            runTest(testDispatcher) {
                if (::viewModel.isInitialized) {
                    viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
        } finally {
            super.tearDown()
        }
    }

    @Test
    fun blockedReadClearsPreviouslyDisplayedCountsInsteadOfShowingZeros() = runTest(testDispatcher) {
        val previous = DatabaseStats(7, 3, 2, 1)
        coEvery { backupRepository.getDatabaseStats() } returns previous
        viewModel.loadDatabaseStats()
        advanceUntilIdle()
        assertEquals(previous, viewModel.databaseStats.value)
        assertFalse(viewModel.databaseStatsUnavailable.value)

        coEvery { backupRepository.getDatabaseStats() } throws
            DatabaseStatsUnavailableException(DatabaseStatsFailureReason.READ_BLOCKED)
        viewModel.loadDatabaseStats()
        advanceUntilIdle()
        assertNull(viewModel.databaseStats.value)
        assertTrue(viewModel.databaseStatsUnavailable.value)
    }

    @Test
    fun validEmptyReadRecoversFromQueryFailure() = runTest(testDispatcher) {
        coEvery { backupRepository.getDatabaseStats() } throws
            DatabaseStatsUnavailableException(DatabaseStatsFailureReason.QUERY_FAILED)
        viewModel.loadDatabaseStats()
        advanceUntilIdle()
        assertTrue(viewModel.databaseStatsUnavailable.value)
        assertNull(viewModel.databaseStats.value)

        val empty = DatabaseStats(0, 0, 0, 0)
        coEvery { backupRepository.getDatabaseStats() } returns empty
        viewModel.loadDatabaseStats()
        advanceUntilIdle()
        assertEquals(empty, viewModel.databaseStats.value)
        assertFalse(viewModel.databaseStatsUnavailable.value)
    }

    @Test
    fun cancellationCancelsTheLoadWithoutPublishingFailure() = runTest(testDispatcher) {
        var loadJob: Job? = null
        coEvery { backupRepository.getDatabaseStats() } coAnswers {
            loadJob = currentCoroutineContext()[Job]
            throw CancellationException("test cancellation")
        }
        viewModel.loadDatabaseStats()
        advanceUntilIdle()
        assertTrue(requireNotNull(loadJob).isCancelled)
        assertFalse(viewModel.databaseStatsUnavailable.value)
        assertNull(viewModel.databaseStats.value)
    }

    @Test
    fun unexpectedFailureLogsOnlyControlledCodeAndExceptionClass() = runTest(testDispatcher) {
        val events = mutableListOf<Pair<String, Throwable?>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (message.startsWith("DATABASE_STATS_UNAVAILABLE")) events += message to t
            }
        }
        Timber.plant(tree)
        try {
            coEvery { backupRepository.getDatabaseStats() } throws
                IllegalStateException("SECRET /private/account.db financial payload")
            viewModel.loadDatabaseStats()
            advanceUntilIdle()
            assertTrue(viewModel.databaseStatsUnavailable.value)
            assertNull(viewModel.databaseStats.value)
            val event = events.single()
            assertTrue(event.first.contains("class=IllegalStateException"))
            assertFalse(event.first.contains("SECRET"))
            assertFalse(event.first.contains("/private/"))
            assertNull(event.second)
        } finally {
            Timber.uproot(tree)
        }
    }
}
