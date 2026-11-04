package com.yourname.expensetracker.ui.screens.privacysettings

import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.util.ViewModelTestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacySettingsViewModelCorruptionWarningTest : ViewModelTestUtils() {

    private val repository = CountingPrivacySettingsRepository(
        PrivacySettingsLoadState.CorruptedFailClosed(PrivacySettings(), reason = "TEST_CORRUPT")
    )

    @Test
    fun `corrupted load state shows the warning`() = runTest(testDispatcher) {
        val viewModel = PrivacySettingsViewModel(repository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showCorruptionWarning)
    }

    @Test
    fun `dismissCorruptionWarning clears the flag and writes nothing to settings`() = runTest(testDispatcher) {
        val viewModel = PrivacySettingsViewModel(repository)
        advanceUntilIdle()
        val settingsBefore = viewModel.uiState.value.settings
        assertTrue(viewModel.uiState.value.showCorruptionWarning)

        viewModel.dismissCorruptionWarning()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showCorruptionWarning)
        assertEquals(0, repository.updateCalls)
        assertEquals(settingsBefore, viewModel.uiState.value.settings)
        assertEquals(settingsBefore, repository.current)
    }

    private class CountingPrivacySettingsRepository(
        initial: PrivacySettingsLoadState
    ) : PrivacySettingsRepository {
        private val state = MutableStateFlow(initial)
        var updateCalls = 0
            private set

        val current: PrivacySettings
            get() = when (val s = state.value) {
                is PrivacySettingsLoadState.Loaded -> s.settings
                is PrivacySettingsLoadState.FirstRunDefault -> s.settings
                is PrivacySettingsLoadState.CorruptedFailClosed -> s.settings
            }

        override fun observeSettings(): Flow<PrivacySettings> = state.map { current }

        override fun observeLoadState(): Flow<PrivacySettingsLoadState> = state

        override suspend fun getSettings(): PrivacySettings = current

        override suspend fun getLoadState(): PrivacySettingsLoadState = state.value

        override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {
            updateCalls++
            state.value = PrivacySettingsLoadState.Loaded(transform(current))
        }
    }
}
