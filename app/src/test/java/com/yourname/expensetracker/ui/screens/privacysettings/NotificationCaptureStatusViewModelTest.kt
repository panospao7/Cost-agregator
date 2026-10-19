package com.yourname.expensetracker.ui.screens.privacysettings

import com.yourname.expensetracker.domain.notification.capture.NotificationCaptureStatus
import com.yourname.expensetracker.domain.notification.capture.NotificationListenerAccessChecker
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.util.ViewModelTestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCaptureStatusViewModelTest : ViewModelTestUtils() {

    private val repository = FakeRepository()
    private val checker = FakeChecker()

    @Test
    fun `consent on and access granted is active`() = runTest(testDispatcher) {
        repository.emit(PrivacySettingsLoadState.Loaded(PrivacySettings(notificationCaptureEnabled = true)))
        checker.granted = true
        val viewModel = NotificationCaptureStatusViewModel(repository, checker)
        advanceUntilIdle()

        assertEquals(NotificationCaptureStatus.ACTIVE, viewModel.status.value)
    }

    @Test
    fun `consent on and access missing is access off`() = runTest(testDispatcher) {
        repository.emit(PrivacySettingsLoadState.Loaded(PrivacySettings(notificationCaptureEnabled = true)))
        checker.granted = false
        val viewModel = NotificationCaptureStatusViewModel(repository, checker)
        advanceUntilIdle()

        assertEquals(NotificationCaptureStatus.ACCESS_OFF, viewModel.status.value)
    }

    @Test
    fun `consent off wins over granted access`() = runTest(testDispatcher) {
        repository.emit(PrivacySettingsLoadState.Loaded(PrivacySettings(notificationCaptureEnabled = false)))
        checker.granted = true
        val viewModel = NotificationCaptureStatusViewModel(repository, checker)
        advanceUntilIdle()

        assertEquals(NotificationCaptureStatus.CONSENT_OFF, viewModel.status.value)
    }

    @Test
    fun `corrupted settings are treated as consent off even with capture flag set`() = runTest(testDispatcher) {
        repository.emit(
            PrivacySettingsLoadState.CorruptedFailClosed(
                settings = PrivacySettings(notificationCaptureEnabled = true),
                reason = "TEST_CORRUPTED"
            )
        )
        checker.granted = true
        val viewModel = NotificationCaptureStatusViewModel(repository, checker)
        advanceUntilIdle()

        assertEquals(NotificationCaptureStatus.CONSENT_OFF, viewModel.status.value)
    }

    @Test
    fun `refresh re-checks access without writing settings`() = runTest(testDispatcher) {
        repository.emit(PrivacySettingsLoadState.Loaded(PrivacySettings(notificationCaptureEnabled = true)))
        checker.granted = false
        val viewModel = NotificationCaptureStatusViewModel(repository, checker)
        advanceUntilIdle()
        assertEquals(NotificationCaptureStatus.ACCESS_OFF, viewModel.status.value)

        checker.granted = true
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(NotificationCaptureStatus.ACTIVE, viewModel.status.value)

        checker.granted = false
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(NotificationCaptureStatus.ACCESS_OFF, viewModel.status.value)

        assertFalse(repository.updateCalled)
    }

    @Test
    fun `status follows consent changes`() = runTest(testDispatcher) {
        repository.emit(PrivacySettingsLoadState.Loaded(PrivacySettings(notificationCaptureEnabled = true)))
        checker.granted = true
        val viewModel = NotificationCaptureStatusViewModel(repository, checker)
        advanceUntilIdle()
        assertEquals(NotificationCaptureStatus.ACTIVE, viewModel.status.value)

        repository.emit(PrivacySettingsLoadState.Loaded(PrivacySettings(notificationCaptureEnabled = false)))
        advanceUntilIdle()
        assertEquals(NotificationCaptureStatus.CONSENT_OFF, viewModel.status.value)
    }

    @Test
    fun `settings flow failure fails closed to consent off without crashing`() = runTest(testDispatcher) {
        checker.granted = true
        val throwingRepository = object : PrivacySettingsRepository by repository {
            override fun observeLoadState(): Flow<PrivacySettingsLoadState> =
                flow { throw IllegalStateException("simulated settings failure") }
        }
        val viewModel = NotificationCaptureStatusViewModel(throwingRepository, checker)
        advanceUntilIdle()

        assertEquals(NotificationCaptureStatus.CONSENT_OFF, viewModel.status.value)
    }

    private class FakeChecker : NotificationListenerAccessChecker {
        var granted: Boolean = false
        override fun isListenerAccessGranted(): Boolean = granted
    }

    private class FakeRepository : PrivacySettingsRepository {
        private val state = MutableStateFlow<PrivacySettingsLoadState>(
            PrivacySettingsLoadState.Loaded(PrivacySettings())
        )
        var updateCalled = false
            private set

        fun emit(loadState: PrivacySettingsLoadState) {
            state.value = loadState
        }

        private fun current(): PrivacySettings = when (val s = state.value) {
            is PrivacySettingsLoadState.Loaded -> s.settings
            is PrivacySettingsLoadState.FirstRunDefault -> s.settings
            is PrivacySettingsLoadState.CorruptedFailClosed -> s.settings
        }

        override fun observeSettings(): Flow<PrivacySettings> = state.map { current() }
        override fun observeLoadState(): Flow<PrivacySettingsLoadState> = state
        override suspend fun getSettings(): PrivacySettings = current()
        override suspend fun getLoadState(): PrivacySettingsLoadState = state.value
        override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {
            updateCalled = true
        }
    }
}
