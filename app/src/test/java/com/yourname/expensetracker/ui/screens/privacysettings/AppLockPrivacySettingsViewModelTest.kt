package com.yourname.expensetracker.ui.screens.privacysettings

import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingField
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteResult
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteSerializer
import com.yourname.expensetracker.domain.privacy.applock.AppLockWriteRejection
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.DiscreetRepostOutcome
import com.yourname.expensetracker.domain.privacy.applock.DiscreetRepostTrigger
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticMetadata
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import com.yourname.expensetracker.domain.privacy.applock.UpdateDiscreetNotificationsResult
import com.yourname.expensetracker.domain.privacy.applock.UpdateDiscreetNotificationsUseCase
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.MockKMatcherScope
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S12: the ViewModel of the 1a "App lock & privacy" section (INV-44, INV-46, INV-47, AE-85, AE-88,
 * AE-89, AE-105).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockPrivacySettingsViewModelTest : ViewModelTestUtils() {

    private val off = AppLockPrivacySettings(discreetNotifications = false)
    private val on = AppLockPrivacySettings(discreetNotifications = true)

    private class FakeRepository(initial: AppLockSettingsLoadState) : AppLockSettingsRepository {
        val state = MutableStateFlow(initial)
        var observeCalls = 0
        val reviewSaves = mutableListOf<AppLockPrivacySettings>()
        var reviewSaveResult: AppLockSettingsWriteResult? = null
        var reviewSaveThrows: Throwable? = null

        /** false = the commit is not (yet) visible through the observer. */
        var publishCommit = true

        override suspend fun getLoadState(): AppLockSettingsLoadState = state.value

        override fun observeLoadState(): Flow<AppLockSettingsLoadState> = state.onStart { observeCalls++ }

        override suspend fun setDiscreetNotifications(enabled: Boolean): AppLockSettingsWriteResult =
            error("The ViewModel must write the discreet flag only through the use case")

        override suspend fun reviewSave(record: AppLockPrivacySettings): AppLockSettingsWriteResult {
            reviewSaves += record
            reviewSaveThrows?.let { throw it }
            val result = reviewSaveResult ?: AppLockSettingsWriteResult.Committed(record)
            if (result is AppLockSettingsWriteResult.Committed && publishCommit) {
                state.value = AppLockSettingsLoadState.Loaded(result.settings)
            }
            return result
        }

        // S18 ii-f (finding 8): the write methods are abstract; this fake does not support them, so they fail closed.
        override suspend fun setAppLockEnabled(enabled: Boolean): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED)

        override suspend fun setAutoLockTimeout(timeout: AutoLockTimeout): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT)

        override suspend fun setAutoLockTimeoutIfNotWeakening(timeout: AutoLockTimeout): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT)

        override suspend fun setDiscreetNotificationsIfNotForced(enabled: Boolean): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.DISCREET)

        override suspend fun setSecureScreenIfNotForced(enabled: Boolean): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.SECURE_SCREEN)
    }

    private val useCase = mockk<UpdateDiscreetNotificationsUseCase>()
    private val emitter = mockk<PrivacyDiagnosticsEmitter>(relaxed = true)
    private val repostTrigger = mockk<DiscreetRepostTrigger> {
        coEvery { onCommittedWrite(any(), any()) } returns DiscreetRepostOutcome.NOT_TRIGGERED
    }

    private fun MockKMatcherScope.fieldIs(field: AppLockSettingField): PrivacyDiagnosticMetadata =
        match { it.entries[PrivacyDiagnosticKey.FIELD] == field }

    private fun appScope() = CoroutineScope(SupervisorJob() + testDispatcher)

    private fun viewModel(
        repository: FakeRepository,
        serializer: AppLockSettingsWriteSerializer = AppLockSettingsWriteSerializer()
    ) = AppLockPrivacySettingsViewModel(repository, useCase, emitter, serializer, repostTrigger, appScope())

    @Test
    fun `state is Loading until the first read arrives and never Ready or Off`() = runTest(testDispatcher) {
        val viewModel = viewModel(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

        assertEquals(AppLockPrivacySectionState.Loading, viewModel.uiState.value)

        advanceUntilIdle()
        assertEquals(AppLockPrivacySectionState.Ready(false, false, null), viewModel.uiState.value)
    }

    @Test
    fun `first run default renders the committed default`() = runTest(testDispatcher) {
        val viewModel = viewModel(FakeRepository(AppLockSettingsLoadState.FirstRunDefault(off)))
        advanceUntilIdle()

        assertEquals(AppLockPrivacySectionState.Ready(false, false, null), viewModel.uiState.value)
    }

    @Test
    fun `toggle shows the committed value only after the write commits`() = runTest(testDispatcher) {
        val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
        val gate = CompletableDeferred<Unit>()
        coEvery { useCase(true) } coAnswers {
            gate.await()
            repository.state.value = AppLockSettingsLoadState.Loaded(on)
            UpdateDiscreetNotificationsResult.Committed(true, DiscreetRepostOutcome.SWEPT)
        }
        val viewModel = viewModel(repository)
        try {
            advanceUntilIdle()

            viewModel.setDiscreetNotifications(true)
            advanceUntilIdle()
            // INV-46: still the old committed value while the write is in flight; the row is busy.
            assertEquals(AppLockPrivacySectionState.Ready(false, true, null), viewModel.uiState.value)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(AppLockPrivacySectionState.Ready(true, false, null), viewModel.uiState.value)
            coVerify(exactly = 1) { useCase(true) }
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun `failed write keeps the previous committed value and shows an error (AE-105, INV-46)`() =
        runTest(testDispatcher) {
            coEvery { useCase(true) } returns UpdateDiscreetNotificationsResult.Failed(AppLockSettingField.DISCREET)
            val viewModel = viewModel(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
            advanceUntilIdle()

            viewModel.setDiscreetNotifications(true)
            advanceUntilIdle()

            assertEquals(
                AppLockPrivacySectionState.Ready(false, false, AppLockPrivacySectionNotice.SAVE_FAILED),
                viewModel.uiState.value
            )
        }

    @Test
    fun `unexpected non cancellation failure surfaces as a save error and frees the row`() =
        runTest(testDispatcher) {
            coEvery { useCase(true) } coAnswers { throw IllegalStateException("boom") }
            val viewModel = viewModel(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
            advanceUntilIdle()

            viewModel.setDiscreetNotifications(true)
            advanceUntilIdle()

            assertEquals(
                AppLockPrivacySectionState.Ready(false, false, AppLockPrivacySectionNotice.SAVE_FAILED),
                viewModel.uiState.value
            )
            coVerify { emitter.emit(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED, fieldIs(AppLockSettingField.DISCREET)) }
        }

    @Test
    fun `rejected write shows SAVE_FAILED, survives the same category re-read and records no diagnostic`() =
        runTest(testDispatcher) {
            coEvery { useCase(true) } returns UpdateDiscreetNotificationsResult.Rejected(AppLockWriteRejection.UNREADABLE)
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.setDiscreetNotifications(true)
            advanceUntilIdle()

            assertEquals(2, repository.observeCalls)
            assertEquals(
                AppLockPrivacySectionState.Ready(false, false, AppLockPrivacySectionNotice.SAVE_FAILED),
                viewModel.uiState.value
            )
            coVerify(exactly = 0) { emitter.emit(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED, any()) }
        }

    @Test
    fun `SAVE_FAILED from a rejected write clears on the next successful write`() = runTest(testDispatcher) {
        coEvery { useCase(true) } returnsMany listOf(
            UpdateDiscreetNotificationsResult.Rejected(AppLockWriteRejection.UNREADABLE),
            UpdateDiscreetNotificationsResult.Committed(true, DiscreetRepostOutcome.SWEPT)
        )
        val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.setDiscreetNotifications(true)
        advanceUntilIdle()
        assertEquals(
            AppLockPrivacySectionState.Ready(false, false, AppLockPrivacySectionNotice.SAVE_FAILED),
            viewModel.uiState.value
        )

        viewModel.setDiscreetNotifications(true)
        advanceUntilIdle()
        assertEquals(AppLockPrivacySectionState.Ready(false, false, null), viewModel.uiState.value)
        coVerify(exactly = 2) { useCase(true) }
    }

    @Test
    fun `persistent unreadable shows the review card with the fail closed choice and blocks per field writes (AE-88)`() =
        runTest(testDispatcher) {
            val viewModel = viewModel(
                FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            )
            advanceUntilIdle()

            assertEquals(AppLockPrivacySectionState.NeedsReview(true, false, null), viewModel.uiState.value)

            viewModel.setDiscreetNotifications(false)
            advanceUntilIdle()
            coVerify(exactly = 0) { useCase(any()) }
        }

    @Test
    fun `invalid value is also a review state`() = runTest(testDispatcher) {
        val viewModel = viewModel(
            FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.INVALID_VALUE))
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is AppLockPrivacySectionState.NeedsReview)
    }

    @Test
    fun `transient unreadable blocks writes and never renders Ready or Off`() = runTest(testDispatcher) {
        val viewModel = viewModel(
            FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR))
        )
        advanceUntilIdle()

        assertEquals(AppLockPrivacySectionState.TransientUnreadable, viewModel.uiState.value)

        viewModel.setDiscreetNotifications(false)
        advanceUntilIdle()
        coVerify(exactly = 0) { useCase(any()) }
    }

    @Test
    fun `timeout is transient`() = runTest(testDispatcher) {
        val viewModel = viewModel(
            FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.TIMEOUT))
        )
        advanceUntilIdle()

        assertEquals(AppLockPrivacySectionState.TransientUnreadable, viewModel.uiState.value)
    }

    @Test
    fun `retry re-reads and a successful read returns the normal state (INV-47)`() = runTest(testDispatcher) {
        val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR))
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        assertEquals(AppLockPrivacySectionState.TransientUnreadable, viewModel.uiState.value)

        repository.state.value = AppLockSettingsLoadState.Loaded(on)
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(2, repository.observeCalls)
        assertEquals(AppLockPrivacySectionState.Ready(true, false, null), viewModel.uiState.value)
    }

    @Test
    fun `foreground refresh re-reads only while transient unreadable`() = runTest(testDispatcher) {
        val ready = FakeRepository(AppLockSettingsLoadState.Loaded(off))
        val readyViewModel = viewModel(ready)
        advanceUntilIdle()
        readyViewModel.refreshIfTransient()
        advanceUntilIdle()
        assertEquals(1, ready.observeCalls)

        val review = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
        val reviewViewModel = viewModel(review)
        advanceUntilIdle()
        reviewViewModel.refreshIfTransient()
        advanceUntilIdle()
        assertEquals(1, review.observeCalls)

        val transient = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR))
        val transientViewModel = viewModel(transient)
        advanceUntilIdle()
        transientViewModel.refreshIfTransient()
        advanceUntilIdle()
        assertEquals(2, transient.observeCalls)
    }

    @Test
    fun `review save writes the whole 1a record and shows Settings saved (AE-85 1a record)`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.setReviewDiscreetChoice(false)
            advanceUntilIdle()
            assertEquals(AppLockPrivacySectionState.NeedsReview(false, false, null), viewModel.uiState.value)

            viewModel.saveReview()
            advanceUntilIdle()

            assertEquals(listOf(off), repository.reviewSaves)
            assertEquals(
                AppLockPrivacySectionState.Ready(false, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
                viewModel.uiState.value
            )
            coVerify { emitter.emit(PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED) }
            coVerify(exactly = 0) { useCase(any()) }
        }

    @Test
    fun `committed review save with discreet on triggers the sweep exactly once as was-off to on`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.setReviewDiscreetChoice(true)
            viewModel.saveReview()
            advanceUntilIdle()

            assertEquals(listOf(on), repository.reviewSaves)
            coVerify(exactly = 1) { repostTrigger.onCommittedWrite(false, true) }
            coVerify(exactly = 1) { repostTrigger.onCommittedWrite(any(), any()) }
        }

    @Test
    fun `committed review save with discreet off still calls the trigger which does not sweep`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.setReviewDiscreetChoice(false)
            viewModel.saveReview()
            advanceUntilIdle()

            // The edge detector owns the decision: false to false is NOT_TRIGGERED, never a sweep.
            coVerify(exactly = 1) { repostTrigger.onCommittedWrite(false, false) }
            coVerify(exactly = 0) { repostTrigger.onCommittedWrite(any(), true) }
        }

    @Test
    fun `refused or failed review save never reaches the sweep trigger`() = runTest(testDispatcher) {
        val rejected = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
        rejected.reviewSaveResult = AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE)
        val rejectedViewModel = viewModel(rejected)
        advanceUntilIdle()
        rejectedViewModel.saveReview()
        advanceUntilIdle()

        val failed = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
        failed.reviewSaveResult = AppLockSettingsWriteResult.Failed(AppLockSettingField.REVIEW_SAVE)
        val failedViewModel = viewModel(failed)
        advanceUntilIdle()
        failedViewModel.saveReview()
        advanceUntilIdle()

        val thrown = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
        thrown.reviewSaveThrows = IllegalStateException("boom")
        val thrownViewModel = viewModel(thrown)
        advanceUntilIdle()
        thrownViewModel.saveReview()
        advanceUntilIdle()

        coVerify(exactly = 0) { repostTrigger.onCommittedWrite(any(), any()) }
    }

    @Test
    fun `review save failure keeps the card and the sentinel and shows an error (AE-89)`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            repository.reviewSaveResult = AppLockSettingsWriteResult.Failed(AppLockSettingField.REVIEW_SAVE)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview()
            advanceUntilIdle()

            assertEquals(
                AppLockPrivacySectionState.NeedsReview(true, false, AppLockPrivacySectionNotice.SAVE_FAILED),
                viewModel.uiState.value
            )
            coVerify { emitter.emit(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED, fieldIs(AppLockSettingField.REVIEW_SAVE)) }
        }

    @Test
    fun `review save rejected after a transient read keeps the error, records REVIEW_SAVE and re-reads`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            repository.reviewSaveResult = AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview()
            advanceUntilIdle()

            assertEquals(
                AppLockPrivacySectionState.NeedsReview(true, false, AppLockPrivacySectionNotice.SAVE_FAILED),
                viewModel.uiState.value
            )
            assertEquals(2, repository.observeCalls)
            coVerify { emitter.emit(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED, fieldIs(AppLockSettingField.REVIEW_SAVE)) }
        }

    @Test
    fun `a stale 1a review save on a no longer corrupted store is Rejected NOT_UNDER_REVIEW and shows SAVE_FAILED (S18 ii-d D8 parity)`() =
        runTest(testDispatcher) {
            // The card was rendered over CORRUPTED; another writer has since recovered the store, so the conditional
            // repository write refuses with NOT_UNDER_REVIEW. The ViewModel shows the bounded error, records the
            // REVIEW_SAVE persist failure, never reaches the sweep trigger and re-reads the true state.
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            repository.reviewSaveResult = AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.NOT_UNDER_REVIEW)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.setReviewDiscreetChoice(true)
            viewModel.saveReview()
            advanceUntilIdle()

            assertEquals(listOf(on), repository.reviewSaves)
            assertEquals(
                AppLockPrivacySectionState.NeedsReview(true, false, AppLockPrivacySectionNotice.SAVE_FAILED),
                viewModel.uiState.value
            )
            assertEquals("the true state is re-read", 2, repository.observeCalls)
            coVerify { emitter.emit(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED, fieldIs(AppLockSettingField.REVIEW_SAVE)) }
            coVerify(exactly = 0) { emitter.emit(PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED) }
            coVerify(exactly = 0) { repostTrigger.onCommittedWrite(any(), any()) }
        }

    @Test
    fun `unexpected exception during review save shows an error and records REVIEW_SAVE`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            repository.reviewSaveThrows = IllegalStateException("boom")
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview()
            advanceUntilIdle()

            assertEquals(
                AppLockPrivacySectionState.NeedsReview(true, false, AppLockPrivacySectionNotice.SAVE_FAILED),
                viewModel.uiState.value
            )
            coVerify { emitter.emit(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED, fieldIs(AppLockSettingField.REVIEW_SAVE)) }
        }

    @Test
    fun `a diagnostics failure after a committed review save never turns it into a failure`() =
        runTest(testDispatcher) {
            coEvery { emitter.emit(PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED) } coAnswers {
                throw IllegalStateException("diagnostics down")
            }
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview()
            advanceUntilIdle()

            assertEquals(
                AppLockPrivacySectionState.Ready(true, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
                viewModel.uiState.value
            )
            coVerify(exactly = 0) {
                emitter.emit(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED, any())
            }
        }

    @Test
    fun `review choice resets to the fail closed value after a committed review save`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.setReviewDiscreetChoice(false)
            viewModel.saveReview()
            advanceUntilIdle()
            assertEquals(
                AppLockPrivacySectionState.Ready(false, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
                viewModel.uiState.value
            )

            // The store is corrupted again later: the card must not keep the earlier Off choice.
            repository.state.value = AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.INVALID_VALUE)
            advanceUntilIdle()

            assertEquals(AppLockPrivacySectionState.NeedsReview(true, false, null), viewModel.uiState.value)
        }

    @Test
    fun `review choice resets when the state moves from readable into persistent unreadable`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            val viewModel = viewModel(repository)
            advanceUntilIdle()
            viewModel.setReviewDiscreetChoice(false)
            advanceUntilIdle()
            assertEquals(AppLockPrivacySectionState.NeedsReview(false, false, null), viewModel.uiState.value)

            repository.state.value = AppLockSettingsLoadState.Loaded(on)
            advanceUntilIdle()
            repository.state.value = AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED)
            advanceUntilIdle()

            assertEquals(AppLockPrivacySectionState.NeedsReview(true, false, null), viewModel.uiState.value)
        }

    @Test
    fun `notice is cleared when the load state category changes`() = runTest(testDispatcher) {
        coEvery { useCase(true) } returns UpdateDiscreetNotificationsResult.Failed(AppLockSettingField.DISCREET)
        val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.setDiscreetNotifications(true)
        advanceUntilIdle()
        assertEquals(
            AppLockPrivacySectionState.Ready(false, false, AppLockPrivacySectionNotice.SAVE_FAILED),
            viewModel.uiState.value
        )

        repository.state.value = AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR)
        advanceUntilIdle()
        assertEquals(AppLockPrivacySectionState.TransientUnreadable, viewModel.uiState.value)

        repository.state.value = AppLockSettingsLoadState.Loaded(off)
        advanceUntilIdle()
        assertEquals(AppLockPrivacySectionState.Ready(false, false, null), viewModel.uiState.value)
    }

    @Test
    fun `review save failure notice is cleared when the store becomes readable`() = runTest(testDispatcher) {
        val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
        repository.reviewSaveResult = AppLockSettingsWriteResult.Failed(AppLockSettingField.REVIEW_SAVE)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.saveReview()
        advanceUntilIdle()

        repository.state.value = AppLockSettingsLoadState.Loaded(on)
        advanceUntilIdle()

        assertEquals(AppLockPrivacySectionState.Ready(true, false, null), viewModel.uiState.value)
    }

    @Test
    fun `after a committed review save the card stays busy until the observer shows a readable state`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            repository.publishCommit = false
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview()
            advanceUntilIdle()
            assertEquals(
                AppLockPrivacySectionState.NeedsReview(true, true, AppLockPrivacySectionNotice.REVIEW_SAVED),
                viewModel.uiState.value
            )

            // A second tap before the observer catches up must not issue a duplicate review-save.
            viewModel.saveReview()
            advanceUntilIdle()
            assertEquals(1, repository.reviewSaves.size)

            repository.state.value = AppLockSettingsLoadState.Loaded(on)
            advanceUntilIdle()
            assertEquals(
                AppLockPrivacySectionState.Ready(true, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
                viewModel.uiState.value
            )
        }

    @Test
    fun `review actions are ignored when the card is not showing`() = runTest(testDispatcher) {
        val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.saveReview()
        viewModel.setReviewDiscreetChoice(false)
        advanceUntilIdle()

        assertTrue(repository.reviewSaves.isEmpty())
        assertEquals(AppLockPrivacySectionState.Ready(false, false, null), viewModel.uiState.value)
    }

    @Test
    fun `writes from two ViewModel instances never overlap (serialised)`() = runTest(testDispatcher) {
        val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        var calls = 0
        coEvery { useCase(true) } coAnswers {
            calls++
            active++
            maxActive = maxOf(maxActive, active)
            gate.await()
            active--
            UpdateDiscreetNotificationsResult.Committed(true, DiscreetRepostOutcome.SWEPT)
        }
        val serializer = AppLockSettingsWriteSerializer()
        val first = viewModel(repository, serializer)
        val second = viewModel(repository, serializer)
        try {
            advanceUntilIdle()

            first.setDiscreetNotifications(true)
            second.setDiscreetNotifications(true)
            advanceUntilIdle()
            assertEquals(1, calls)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, calls)
            assertEquals(1, maxActive)
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun `a started write completes when the screen is left (application scope, not cancelled)`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
            val gate = CompletableDeferred<Unit>()
            var completed = false
            coEvery { useCase(true) } coAnswers {
                gate.await()
                completed = true
                UpdateDiscreetNotificationsResult.Committed(true, DiscreetRepostOutcome.SWEPT)
            }
            val viewModel = viewModel(repository)
            try {
                advanceUntilIdle()

                viewModel.setDiscreetNotifications(true)
                advanceUntilIdle()
                viewModel.viewModelScope.cancel()
                gate.complete(Unit)
                advanceUntilIdle()

                assertTrue(completed)
            } finally {
                gate.complete(Unit)
            }
        }
}
