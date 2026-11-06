package com.yourname.expensetracker.ui.screens.privacysettings

import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * J-03-1: after a successful save of the notification raw-storage mode the ViewModel
 * calls TransactionClassifier.onRawStorageModeChanged(mode). The hook never runs when
 * the save fails, never runs for other setters, and its own failure is not a save failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivacySettingsViewModelRawStorageHookTest : ViewModelTestUtils() {

    private val repository = FakePrivacySettingsRepository()
    private val classifier = mockk<TransactionClassifier>(relaxed = true)

    private fun newViewModel() = PrivacySettingsViewModel(repository, classifier)

    @Test
    fun `J-03-1 setting notification raw storage mode saves then calls the classifier hook with that mode`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.setRawNotificationStorageMode(RawStorageMode.DO_NOT_STORE)
            advanceUntilIdle()

            assertEquals(RawStorageMode.DO_NOT_STORE, repository.current.rawNotificationStorageMode)
            coVerify(exactly = 1) { classifier.onRawStorageModeChanged(RawStorageMode.DO_NOT_STORE) }
            assertNull(viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.isSaving)
        }

    @Test
    fun `J-03-1 hook runs after the save is persisted not before`() = runTest(testDispatcher) {
        var modeSeenByHook: RawStorageMode? = null
        coEvery { classifier.onRawStorageModeChanged(any()) } coAnswers {
            modeSeenByHook = repository.current.rawNotificationStorageMode
        }
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.setRawNotificationStorageMode(RawStorageMode.STORE_METADATA_ONLY)
        advanceUntilIdle()

        assertEquals(RawStorageMode.STORE_METADATA_ONLY, modeSeenByHook)
    }

    @Test
    fun `J-03-1 hook is invoked for every mode including STORE_RAW and the classifier decides`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()

            for (mode in RawStorageMode.values()) {
                viewModel.setRawNotificationStorageMode(mode)
                advanceUntilIdle()
            }

            coVerifyOrder {
                for (mode in RawStorageMode.values()) {
                    classifier.onRawStorageModeChanged(mode)
                }
            }
        }

    @Test
    fun `J-03-1 hook is not called when the save throws and the error is surfaced`() = runTest(testDispatcher) {
        repository.failNextUpdateWith = java.io.IOException("synthetic save failure")
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.setRawNotificationStorageMode(RawStorageMode.DO_NOT_STORE)
        advanceUntilIdle()

        coVerify(exactly = 0) { classifier.onRawStorageModeChanged(any()) }
        assertEquals(
            "Failed to save privacy setting. Please retry.",
            viewModel.uiState.value.errorMessage
        )
        assertFalse(viewModel.uiState.value.isSaving)
        assertEquals(RawStorageMode.STORE_RAW, repository.current.rawNotificationStorageMode)
    }

    @Test
    fun `J-03-1 hook is not called for other setters`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.setNotificationCaptureEnabled(false)
        viewModel.setCloudAiEnabled(true)
        viewModel.setRedactBeforeCloud(false)
        viewModel.setReceiptImageCloudEnabled(true)
        viewModel.setBankStatementAiEnabled(true)
        viewModel.setExternalGeocodingEnabled(true)
        viewModel.setEncryptedBackupEnabled(false)
        viewModel.setDebugDataPersistenceEnabled(true)
        viewModel.setRawNotificationRetentionDays(7)
        viewModel.setRawOcrRetentionDays(7)
        viewModel.setRawOcrStorageMode(RawStorageMode.DO_NOT_STORE)
        viewModel.setEmailReceiptStorageMode(RawStorageMode.DO_NOT_STORE)
        viewModel.setRawBankStatementStorageMode(RawStorageMode.DO_NOT_STORE)
        advanceUntilIdle()

        coVerify(exactly = 0) { classifier.onRawStorageModeChanged(any()) }
        assertEquals(RawStorageMode.DO_NOT_STORE, repository.current.rawOcrStorageMode)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `J-03-1 hook failure does not set errorMessage and the save stays persisted`() = runTest(testDispatcher) {
        coEvery { classifier.onRawStorageModeChanged(any()) } throws IllegalStateException("synthetic hook failure")
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.setRawNotificationStorageMode(RawStorageMode.DO_NOT_STORE)
        advanceUntilIdle()

        coVerify(exactly = 1) { classifier.onRawStorageModeChanged(RawStorageMode.DO_NOT_STORE) }
        assertNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isSaving)
        assertEquals(RawStorageMode.DO_NOT_STORE, repository.current.rawNotificationStorageMode)
    }

    @Test
    fun `J-03-1 hook CancellationException is not reported as an error and releases the update lock`() =
        runTest(testDispatcher) {
            coEvery { classifier.onRawStorageModeChanged(any()) } throws CancellationException("synthetic cancel")
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.setRawNotificationStorageMode(RawStorageMode.DO_NOT_STORE)
            advanceUntilIdle()

            // Cancellation must propagate (never become an errorMessage) and finally must reset isSaving.
            assertNull(viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.isSaving)

            // The update mutex was released: a later setter still saves.
            viewModel.setRawOcrStorageMode(RawStorageMode.DO_NOT_STORE)
            advanceUntilIdle()
            assertEquals(RawStorageMode.DO_NOT_STORE, repository.current.rawOcrStorageMode)
        }

    private class FakePrivacySettingsRepository : PrivacySettingsRepository {
        private val state = MutableStateFlow<PrivacySettingsLoadState>(
            PrivacySettingsLoadState.Loaded(PrivacySettings())
        )

        var failNextUpdateWith: Throwable? = null

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
            failNextUpdateWith?.let {
                failNextUpdateWith = null
                throw it
            }
            state.value = PrivacySettingsLoadState.Loaded(transform(current))
        }
    }
}
