package com.yourname.expensetracker.ui.screens.privacysettings

import com.yourname.expensetracker.domain.privacy.PrivacyBlocked
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacySettingsViewModelExchangeRateTest : ViewModelTestUtils() {

    private val repository = FakePrivacySettingsRepository()

    @Test
    fun `exchange rate download defaults off and is listed as blocked`() = runTest(testDispatcher) {
        val viewModel = PrivacySettingsViewModel(repository)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.settings.exchangeRateDownloadEnabled)
        assertTrue(viewModel.uiState.value.blocked.any { it is PrivacyBlocked.ExchangeRateDownloadDisabled })
    }

    @Test
    fun `enabling exchange rate download persists and clears blocked entry`() = runTest(testDispatcher) {
        val viewModel = PrivacySettingsViewModel(repository)
        advanceUntilIdle()

        viewModel.setExchangeRateDownloadEnabled(true)
        advanceUntilIdle()

        assertTrue(repository.current.exchangeRateDownloadEnabled)
        assertTrue(viewModel.uiState.value.settings.exchangeRateDownloadEnabled)
        assertFalse(viewModel.uiState.value.blocked.any { it is PrivacyBlocked.ExchangeRateDownloadDisabled })

        viewModel.setExchangeRateDownloadEnabled(false)
        advanceUntilIdle()

        assertFalse(repository.current.exchangeRateDownloadEnabled)
        assertTrue(viewModel.uiState.value.blocked.any { it is PrivacyBlocked.ExchangeRateDownloadDisabled })
    }

    private class FakePrivacySettingsRepository : PrivacySettingsRepository {
        private val state = MutableStateFlow<PrivacySettingsLoadState>(
            PrivacySettingsLoadState.Loaded(PrivacySettings())
        )

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
            state.value = PrivacySettingsLoadState.Loaded(transform(current))
        }
    }
}
