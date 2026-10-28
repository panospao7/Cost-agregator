package com.yourname.expensetracker.ui.screens.privacysettings

import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingField
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsOperations
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteResult
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteSerializer
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.PlainSettingResult
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticMetadata
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.applock.ReviewSaveResult
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.MockKMatcherScope
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S12: the ViewModel of the 1a "App lock & privacy" section (INV-44, INV-46, INV-47, AE-85, AE-88,
 * AE-89, AE-105). S20-e: the review-save routes through [AppLockSettingsOperations.requestReviewSave] with the D2
 * record, and the ViewModel emits no review-save diagnostic and runs no sweep (the controller and the operations own
 * them, D7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockPrivacySettingsViewModelTest : ViewModelTestUtils() {

    private val off = AppLockPrivacySettings(discreetNotifications = false)
    private val on = AppLockPrivacySettings(discreetNotifications = true)

    /** The D2 record (S18-ii-h): lock off, IMMEDIATE, discreet = the user's choice, secure screen on. */
    private fun d2Record(discreet: Boolean) = AppLockPrivacySettings(
        discreetNotifications = discreet,
        appLockEnabled = false,
        autoLockTimeout = AutoLockTimeout.IMMEDIATE,
        secureScreen = true
    )

    private class FakeRepository(initial: AppLockSettingsLoadState) : AppLockSettingsRepository {
        val state = MutableStateFlow(initial)
        var observeCalls = 0

        /** Every direct review-save that reaches the repository. Since S20-e the ViewModel makes none. */
        val reviewSaves = mutableListOf<AppLockPrivacySettings>()

        override suspend fun getLoadState(): AppLockSettingsLoadState = state.value

        override fun observeLoadState(): Flow<AppLockSettingsLoadState> = state.onStart { observeCalls++ }

        override suspend fun reviewSave(record: AppLockPrivacySettings): AppLockSettingsWriteResult {
            reviewSaves += record
            return AppLockSettingsWriteResult.Failed(AppLockSettingField.REVIEW_SAVE)
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

    private val operations = mockk<AppLockSettingsOperations>()
    private val controller = mockk<AppLockController>(relaxed = true)
    private val emitter = mockk<PrivacyDiagnosticsEmitter>(relaxed = true)

    private fun MockKMatcherScope.fieldIs(field: AppLockSettingField): PrivacyDiagnosticMetadata =
        match { it.entries[PrivacyDiagnosticKey.FIELD] == field }

    private fun appScope() = CoroutineScope(SupervisorJob() + testDispatcher)

    private fun viewModel(
        repository: FakeRepository,
        scope: CoroutineScope = appScope()
    ) = AppLockPrivacySettingsViewModel(repository, operations, controller, emitter, scope)

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
        coEvery { operations.requestDiscreetChange(true) } coAnswers {
            gate.await()
            repository.state.value = AppLockSettingsLoadState.Loaded(on)
            PlainSettingResult.COMMITTED
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
            coVerify(exactly = 1) { operations.requestDiscreetChange(true) }
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun `failed write keeps the previous committed value and shows an error (AE-105, INV-46)`() =
        runTest(testDispatcher) {
            coEvery { operations.requestDiscreetChange(true) } returns PlainSettingResult.FAILED
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
            coEvery { operations.requestDiscreetChange(true) } coAnswers { throw IllegalStateException("boom") }
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
    fun `a refused write (forced) shows SAVE_FAILED, survives the same category re-read and records no diagnostic`() =
        runTest(testDispatcher) {
            coEvery { operations.requestDiscreetChange(true) } returns PlainSettingResult.REJECTED_FORCED
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
        coEvery { operations.requestDiscreetChange(true) } returnsMany listOf(
            PlainSettingResult.REJECTED_FORCED,
            PlainSettingResult.COMMITTED
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
        coVerify(exactly = 2) { operations.requestDiscreetChange(true) }
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
            coVerify(exactly = 0) { operations.requestDiscreetChange(any()) }
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
        coVerify(exactly = 0) { operations.requestDiscreetChange(any()) }
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
    fun `retry also asks the controller for a settings re-read exactly once (S20-e, INV-47)`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR))
            val viewModel = viewModel(repository)
            advanceUntilIdle()
            verify(exactly = 0) { controller.requestSettingsReread() }

            viewModel.retry()
            advanceUntilIdle()

            verify(exactly = 1) { controller.requestSettingsReread() }
        }

    @Test
    fun `the foreground refresh asks for the controller re-read only while transient unreadable`() =
        runTest(testDispatcher) {
            val ready = viewModel(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
            advanceUntilIdle()
            ready.refreshIfTransient()
            advanceUntilIdle()
            verify(exactly = 0) { controller.requestSettingsReread() }

            val transient = viewModel(FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.TIMEOUT)))
            advanceUntilIdle()
            transient.refreshIfTransient()
            advanceUntilIdle()
            verify(exactly = 1) { controller.requestSettingsReread() }
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
    fun `review save sends the D2 record through the operations and shows Settings saved (AE-85 1a record)`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            val sent = slot<AppLockPrivacySettings>()
            coEvery { operations.requestReviewSave(capture(sent)) } coAnswers {
                repository.state.value = AppLockSettingsLoadState.Loaded(sent.captured)
                ReviewSaveResult.COMMITTED
            }
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.setReviewDiscreetChoice(false)
            advanceUntilIdle()
            assertEquals(AppLockPrivacySectionState.NeedsReview(false, false, null), viewModel.uiState.value)

            viewModel.saveReview()
            advanceUntilIdle()

            assertEquals(d2Record(false), sent.captured)
            assertEquals(
                AppLockPrivacySectionState.Ready(false, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
                viewModel.uiState.value
            )
            coVerify(exactly = 1) { operations.requestReviewSave(any()) }
            coVerify(exactly = 0) { operations.requestDiscreetChange(any()) }
            // INV-44/INV-45: the repository is reached only through the operations.
            assertTrue(repository.reviewSaves.isEmpty())
            confirmVerified(emitter)
        }

    @Test
    fun `the review-save record is exactly the D2 record for both discreet choices (S18-ii-h)`() =
        runTest(testDispatcher) {
            listOf(true, false).forEach { choice ->
                val sent = slot<AppLockPrivacySettings>()
                coEvery { operations.requestReviewSave(capture(sent)) } returns ReviewSaveResult.STARTED
                val viewModel = viewModel(FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED)))
                advanceUntilIdle()

                viewModel.setReviewDiscreetChoice(choice)
                viewModel.saveReview()
                advanceUntilIdle()

                val record = sent.captured
                assertEquals("$choice", d2Record(choice), record)
                assertEquals("$choice", choice, record.discreetNotifications)
                assertFalse("$choice lock off", record.appLockEnabled)
                assertEquals("$choice IMMEDIATE", AutoLockTimeout.IMMEDIATE, record.autoLockTimeout)
                assertTrue("$choice secure screen on (fail closed)", record.secureScreen)
            }
        }

    @Test
    fun `the review-save record keeps the fail closed discreet choice when the user did not change it`() =
        runTest(testDispatcher) {
            val sent = slot<AppLockPrivacySettings>()
            coEvery { operations.requestReviewSave(capture(sent)) } returns ReviewSaveResult.STARTED
            val viewModel = viewModel(FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED)))
            advanceUntilIdle()

            viewModel.saveReview()
            advanceUntilIdle()

            assertEquals(d2Record(true), sent.captured)
        }

    @Test
    fun `every ReviewSaveResult value is mapped (S20-e), with no VM diagnostic on any path`() =
        runTest(testDispatcher) {
            ReviewSaveResult.values().forEach { result ->
                coEvery { operations.requestReviewSave(any()) } returns result
                // The store stays persistently unreadable: the observer has not reported anything yet.
                val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
                val viewModel = viewModel(repository)
                advanceUntilIdle()

                viewModel.saveReview()
                advanceUntilIdle()

                // Expected state, and the number of observer subscriptions (the initial one + 1 per re-read).
                val (expected, expectedObserveCalls) = when (result) {
                    // COMMITTED: the choice resets, the card stays busy until the observer reports, REVIEW_SAVED.
                    ReviewSaveResult.COMMITTED ->
                        AppLockPrivacySectionState.NeedsReview(true, true, AppLockPrivacySectionNotice.REVIEW_SAVED) to 1
                    // STARTED: the prompt is open; no notice, the observer reports the commit.
                    ReviewSaveResult.STARTED ->
                        AppLockPrivacySectionState.NeedsReview(true, false, null) to 1
                    ReviewSaveResult.FAILED ->
                        AppLockPrivacySectionState.NeedsReview(true, false, AppLockPrivacySectionNotice.SAVE_FAILED) to 1
                    ReviewSaveResult.REJECTED_CAPABILITY,
                    ReviewSaveResult.NOT_IN_REVIEW,
                    ReviewSaveResult.NOT_IN_ORIGIN,
                    ReviewSaveResult.ALREADY_IN_FLIGHT ->
                        AppLockPrivacySectionState.NeedsReview(true, false, AppLockPrivacySectionNotice.SAVE_FAILED) to 2
                }
                assertEquals("$result", expected, viewModel.uiState.value)
                assertEquals("$result observer subscriptions", expectedObserveCalls, repository.observeCalls)
                assertTrue("$result direct repository review-save", repository.reviewSaves.isEmpty())
            }
            // The diagnostics of a review-save are owned by the controller and the operations (D7).
            confirmVerified(emitter)
        }

    @Test
    fun `a started review save shows no notice and frees the card, and the commit arrives through the observer`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } returns ReviewSaveResult.STARTED
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview()
            advanceUntilIdle()
            assertEquals(AppLockPrivacySectionState.NeedsReview(true, false, null), viewModel.uiState.value)

            // The operations apply the confirmed save later; the VM shows what the observer reports (INV-46),
            // and the pending STARTED makes it announce the commit (owner-approvals 64).
            repository.state.value = AppLockSettingsLoadState.Loaded(d2Record(true))
            advanceUntilIdle()
            assertEquals(
                AppLockPrivacySectionState.Ready(true, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
                viewModel.uiState.value
            )
            confirmVerified(emitter)
        }

    @Test
    fun `a started review save then a committed Loaded shows REVIEW_SAVED, and later emissions keep exactly that one notice`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } returns ReviewSaveResult.STARTED
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview()
            advanceUntilIdle()
            repository.state.value = AppLockSettingsLoadState.Loaded(d2Record(true))
            advanceUntilIdle()
            // A second emission after the commit (a re-read, or the discreet value changing): still the one notice,
            // never a second REVIEW_SAVED transition.
            repository.state.value = AppLockSettingsLoadState.Loaded(d2Record(false))
            advanceUntilIdle()
            repository.state.value = AppLockSettingsLoadState.Loaded(d2Record(true))
            advanceUntilIdle()

            // The notice stays on the section (same readable category); the emissions did not re-arm or re-show it.
            assertEquals(
                AppLockPrivacySectionState.Ready(true, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
                viewModel.uiState.value
            )
            // The flag was consumed: the store turning unreadable and then readable again shows no second notice.
            repository.state.value = AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED)
            advanceUntilIdle()
            repository.state.value = AppLockSettingsLoadState.Loaded(d2Record(true))
            advanceUntilIdle()
            assertEquals(AppLockPrivacySectionState.Ready(true, false, null), viewModel.uiState.value)
        }

    @Test
    fun `a started review save whose prompt ends without a commit shows no notice once a later action clears the flag`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } returnsMany
                listOf(ReviewSaveResult.STARTED, ReviewSaveResult.FAILED)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview() // STARTED, then the prompt is cancelled: the store stays unreadable
            advanceUntilIdle()
            assertEquals(AppLockPrivacySectionState.NeedsReview(true, false, null), viewModel.uiState.value)

            viewModel.saveReview() // a later result (FAILED) replaces the flag
            advanceUntilIdle()
            assertEquals(
                AppLockPrivacySectionState.NeedsReview(true, false, AppLockPrivacySectionNotice.SAVE_FAILED),
                viewModel.uiState.value
            )

            // A commit that now arrives from elsewhere is not announced: no pending STARTED.
            repository.state.value = AppLockSettingsLoadState.Loaded(d2Record(true))
            advanceUntilIdle()
            assertEquals(AppLockPrivacySectionState.Ready(true, false, null), viewModel.uiState.value)
        }

    @Test
    fun `a started review save then retry clears the pending flag, so a later Loaded shows no notice`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } returns ReviewSaveResult.STARTED
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview()
            advanceUntilIdle()
            viewModel.retry()
            advanceUntilIdle()
            repository.state.value = AppLockSettingsLoadState.Loaded(d2Record(true))
            advanceUntilIdle()

            assertEquals(AppLockPrivacySectionState.Ready(true, false, null), viewModel.uiState.value)
        }

    /**
     * Arms the pending flag while the observed state is transient-unreadable (S20 F5): the first request is held
     * until the store has moved to a transient failure, then answers STARTED. Leaving the review category happened
     * BEFORE the flag was armed, so applyLoadState has nothing to clear and only [retry] can.
     */
    private fun TestScope.armPendingFlagWhileTransient(
        repository: FakeRepository
    ): AppLockPrivacySettingsViewModel {
        val gate = CompletableDeferred<ReviewSaveResult>()
        coEvery { operations.requestReviewSave(any()) } coAnswers { gate.await() }
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.saveReview()
        advanceUntilIdle() // the request is held at the gate
        repository.state.value = AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR)
        advanceUntilIdle()
        assertEquals(AppLockPrivacySectionState.TransientUnreadable, viewModel.uiState.value)
        gate.complete(ReviewSaveResult.STARTED) // armed now, while the state is TRANSIENT
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `S20 F5 retry itself clears a pending flag that applyLoadState did not, so a later commit shows no notice`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            val viewModel = armPendingFlagWhileTransient(repository)

            viewModel.retry() // TRANSIENT -> LOADING does not touch the flag in applyLoadState
            advanceUntilIdle()
            repository.state.value = AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED)
            advanceUntilIdle()
            repository.state.value = AppLockSettingsLoadState.Loaded(d2Record(true))
            advanceUntilIdle()

            assertEquals(AppLockPrivacySectionState.Ready(true, false, null), viewModel.uiState.value)
            verify(exactly = 1) { controller.requestSettingsReread() }
        }

    @Test
    fun `S20 F5 control - a flag armed while transient and not retried is consumed by the next review to readable move`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            val viewModel = armPendingFlagWhileTransient(repository)

            repository.state.value = AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED)
            advanceUntilIdle()
            repository.state.value = AppLockSettingsLoadState.Loaded(d2Record(true))
            advanceUntilIdle()

            assertEquals(
                AppLockPrivacySectionState.Ready(true, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
                viewModel.uiState.value
            )
        }

    @Test
    fun `S20 F5 a started review save then ALREADY_IN_FLIGHT leaves the flag armed, so the commit shows REVIEW_SAVED`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } returnsMany
                listOf(ReviewSaveResult.STARTED, ReviewSaveResult.ALREADY_IN_FLIGHT)
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview() // STARTED: the flag is armed
            advanceUntilIdle()
            viewModel.saveReview() // ALREADY_IN_FLIGHT: SAVE_FAILED and a re-read, but the flag is untouched
            advanceUntilIdle()
            assertEquals(
                AppLockPrivacySectionState.NeedsReview(true, false, AppLockPrivacySectionNotice.SAVE_FAILED),
                viewModel.uiState.value
            )

            repository.state.value = AppLockSettingsLoadState.Loaded(d2Record(true))
            advanceUntilIdle()

            assertEquals(
                AppLockPrivacySectionState.Ready(true, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
                viewModel.uiState.value
            )
        }

    @Test
    fun `S20 F5 a started review save then the observer moving to transient unreadable clears the flag without a notice`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } returns ReviewSaveResult.STARTED
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview() // STARTED: armed while in the review category
            advanceUntilIdle()
            repository.state.value = AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR)
            advanceUntilIdle() // REVIEW -> TRANSIENT clears the flag
            assertEquals(AppLockPrivacySectionState.TransientUnreadable, viewModel.uiState.value)

            repository.state.value = AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED)
            advanceUntilIdle()
            repository.state.value = AppLockSettingsLoadState.Loaded(d2Record(true))
            advanceUntilIdle()

            assertEquals(AppLockPrivacySectionState.Ready(true, false, null), viewModel.uiState.value)
        }

    @Test
    fun `S20 F4 the settings ViewModel source never names the write serializer or withExclusiveWrite`() {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        val file = candidates
            .map { File(it, "com/yourname/expensetracker/ui/screens/privacysettings/AppLockPrivacySettingsViewModel.kt") }
            .firstOrNull { it.isFile }
            ?: error("The settings ViewModel source was not found; user.dir=${System.getProperty("user.dir")}")
        // Comments and string literals are stripped: KDoc may explain why there is no wrap, only code counts.
        val code = file.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
            .replace(Regex("//[^\\n]*"), " ")
            .replace(Regex("\"(?:\\\\.|[^\"\\\\])*\""), "\"\"")
        assertTrue("the source must still be the ViewModel", code.contains("class AppLockPrivacySettingsViewModel"))
        assertFalse(code.contains("AppLockSettingsWriteSerializer"))
        assertFalse(code.contains("withExclusiveWrite"))
    }

    @Test
    fun `a Loaded emission without a pending started review save shows no notice`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            repository.state.value = AppLockSettingsLoadState.Loaded(d2Record(true))
            advanceUntilIdle()

            assertEquals(AppLockPrivacySectionState.Ready(true, false, null), viewModel.uiState.value)
            coVerify(exactly = 0) { operations.requestReviewSave(any()) }
        }

    @Test
    fun `committed review save with discreet on sends the on record and runs no VM sweep or diagnostic`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } returns ReviewSaveResult.COMMITTED
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.setReviewDiscreetChoice(true)
            viewModel.saveReview()
            advanceUntilIdle()

            // The re-post sweep after the commit is owned by the operations (their trigger call is covered by
            // AppLockControllerReviewSaveTest); the ViewModel has no trigger at all.
            coVerify(exactly = 1) { operations.requestReviewSave(d2Record(true)) }
            coVerify(exactly = 1) { operations.requestReviewSave(any()) }
            confirmVerified(emitter)
        }

    @Test
    fun `committed review save with discreet off sends the off record and runs no VM sweep or diagnostic`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } returns ReviewSaveResult.COMMITTED
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.setReviewDiscreetChoice(false)
            viewModel.saveReview()
            advanceUntilIdle()

            coVerify(exactly = 1) { operations.requestReviewSave(d2Record(false)) }
            coVerify(exactly = 1) { operations.requestReviewSave(any()) }
            confirmVerified(emitter)
        }

    @Test
    fun `a failed or thrown review save emits no VM diagnostic and never touches the repository directly`() =
        runTest(testDispatcher) {
            val failed = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } returns ReviewSaveResult.FAILED
            val failedViewModel = viewModel(failed)
            advanceUntilIdle()
            failedViewModel.saveReview()
            advanceUntilIdle()

            val thrown = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } coAnswers { throw IllegalStateException("boom") }
            val thrownViewModel = viewModel(thrown)
            advanceUntilIdle()
            thrownViewModel.saveReview()
            advanceUntilIdle()

            assertTrue(failed.reviewSaves.isEmpty())
            assertTrue(thrown.reviewSaves.isEmpty())
            confirmVerified(emitter)
        }

    @Test
    fun `review save failure keeps the card and the sentinel and shows an error (AE-89)`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } returns ReviewSaveResult.FAILED
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview()
            advanceUntilIdle()

            assertEquals(
                AppLockPrivacySectionState.NeedsReview(true, false, AppLockPrivacySectionNotice.SAVE_FAILED),
                viewModel.uiState.value
            )
            // PERSIST_FAILED(REVIEW_SAVE) is recorded once by the controller (D7), not by the ViewModel.
            coVerify(exactly = 0) { emitter.emit(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED, any()) }
        }

    @Test
    fun `review save refused with NOT_IN_ORIGIN keeps the error and re-reads, with no VM diagnostic`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } returns ReviewSaveResult.NOT_IN_ORIGIN
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview()
            advanceUntilIdle()

            assertEquals(
                AppLockPrivacySectionState.NeedsReview(true, false, AppLockPrivacySectionNotice.SAVE_FAILED),
                viewModel.uiState.value
            )
            assertEquals(2, repository.observeCalls)
            coVerify(exactly = 0) { emitter.emit(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED, any()) }
        }

    @Test
    fun `a stale 1a review save on a no longer corrupted store is NOT_IN_REVIEW and shows SAVE_FAILED (S18 ii-d D8 parity)`() =
        runTest(testDispatcher) {
            // The card was rendered over CORRUPTED; another writer has since recovered the store, so the operations
            // answer NOT_IN_REVIEW. The ViewModel shows the bounded error, never shows REVIEW_SAVED and re-reads the
            // true state. A stale card is not a persist failure, so no diagnostic is recorded anywhere (D8).
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } returns ReviewSaveResult.NOT_IN_REVIEW
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.setReviewDiscreetChoice(true)
            viewModel.saveReview()
            advanceUntilIdle()

            coVerify(exactly = 1) { operations.requestReviewSave(d2Record(true)) }
            assertEquals(
                AppLockPrivacySectionState.NeedsReview(true, false, AppLockPrivacySectionNotice.SAVE_FAILED),
                viewModel.uiState.value
            )
            assertEquals("the true state is re-read", 2, repository.observeCalls)
            confirmVerified(emitter)
        }

    @Test
    fun `unexpected exception during review save shows an error and the VM records nothing`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } coAnswers { throw IllegalStateException("boom") }
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview()
            advanceUntilIdle()

            assertEquals(
                AppLockPrivacySectionState.NeedsReview(true, false, AppLockPrivacySectionNotice.SAVE_FAILED),
                viewModel.uiState.value
            )
            confirmVerified(emitter)
        }

    @Test
    fun `a diagnostics sink that would fail is never reached by a committed review save`() =
        runTest(testDispatcher) {
            coEvery { emitter.emit(any()) } coAnswers { throw IllegalStateException("diagnostics down") }
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } coAnswers {
                repository.state.value = AppLockSettingsLoadState.Loaded(on)
                ReviewSaveResult.COMMITTED
            }
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview()
            advanceUntilIdle()

            assertEquals(
                AppLockPrivacySectionState.Ready(true, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
                viewModel.uiState.value
            )
            coVerify(exactly = 0) { emitter.emit(any()) }
            coVerify(exactly = 0) { emitter.emit(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED, any()) }
        }

    @Test
    fun `review choice resets to the fail closed value after a committed review save`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            coEvery { operations.requestReviewSave(any()) } coAnswers {
                repository.state.value = AppLockSettingsLoadState.Loaded(off)
                ReviewSaveResult.COMMITTED
            }
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
        coEvery { operations.requestDiscreetChange(true) } returns PlainSettingResult.FAILED
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
        coEvery { operations.requestReviewSave(any()) } returns ReviewSaveResult.FAILED
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
            // The commit is not (yet) visible through the observer.
            coEvery { operations.requestReviewSave(any()) } returns ReviewSaveResult.COMMITTED
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
            coVerify(exactly = 1) { operations.requestReviewSave(any()) }

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

        coVerify(exactly = 0) { operations.requestReviewSave(any()) }
        assertTrue(repository.reviewSaves.isEmpty())
        assertEquals(AppLockPrivacySectionState.Ready(false, false, null), viewModel.uiState.value)
    }

    @Test
    fun `the discreet write takes no outer serializer (the operations serialise it, and the lock is not reentrant)`() =
        runTest(testDispatcher) {
            // S20-d: serialisation of two writes (also from two ViewModel instances) is owned by
            // AppLockSettingsOperations. The ViewModel must NOT hold the shared serializer while it calls the
            // operations: a non-reentrant Mutex would deadlock. The mock takes a serializer the way the real
            // operations do. A wrapping ViewModel would never complete, and the row would stay busy.
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
            val serializer = AppLockSettingsWriteSerializer()
            var calls = 0
            coEvery { operations.requestDiscreetChange(true) } coAnswers {
                serializer.withExclusiveWrite {
                    calls++
                    PlainSettingResult.COMMITTED
                }
            }
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.setDiscreetNotifications(true)
            advanceUntilIdle()

            assertEquals(1, calls)
            assertEquals(AppLockPrivacySectionState.Ready(false, false, null), viewModel.uiState.value)
        }

    @Test
    fun `the review save takes no outer serializer either (S20-e, the operations serialise it)`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            val serializer = AppLockSettingsWriteSerializer()
            var calls = 0
            coEvery { operations.requestReviewSave(any()) } coAnswers {
                serializer.withExclusiveWrite {
                    calls++
                    ReviewSaveResult.FAILED
                }
            }
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.saveReview()
            advanceUntilIdle()

            assertEquals(1, calls)
            assertEquals(
                AppLockPrivacySectionState.NeedsReview(true, false, AppLockPrivacySectionNotice.SAVE_FAILED),
                viewModel.uiState.value
            )
        }

    @Test
    fun `a started write completes when the screen is left (application scope, not cancelled)`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
            val gate = CompletableDeferred<Unit>()
            var completed = false
            coEvery { operations.requestDiscreetChange(true) } coAnswers {
                gate.await()
                completed = true
                PlainSettingResult.COMMITTED
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

    @Test
    fun `a write whose application scope is cancelled mid write still completes (NonCancellable)`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
            val scope = appScope()
            val gate = CompletableDeferred<Unit>()
            var completed = false
            coEvery { operations.requestDiscreetChange(true) } coAnswers {
                gate.await()
                completed = true
                PlainSettingResult.COMMITTED
            }
            val viewModel = viewModel(repository, scope = scope)
            try {
                advanceUntilIdle()

                viewModel.setDiscreetNotifications(true)
                advanceUntilIdle()
                // The caller is cancelled while the write is in flight; the write must still run to its end.
                scope.cancel()
                gate.complete(Unit)
                advanceUntilIdle()

                assertTrue(completed)
                coVerify(exactly = 1) { operations.requestDiscreetChange(true) }
            } finally {
                gate.complete(Unit)
            }
        }

    @Test
    fun `a review save whose application scope is cancelled mid request still completes (NonCancellable)`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))
            val scope = appScope()
            val gate = CompletableDeferred<Unit>()
            var completed = false
            coEvery { operations.requestReviewSave(any()) } coAnswers {
                gate.await()
                completed = true
                ReviewSaveResult.COMMITTED
            }
            val viewModel = viewModel(repository, scope = scope)
            try {
                advanceUntilIdle()

                viewModel.saveReview()
                advanceUntilIdle()
                // The caller is cancelled while the request is in flight; the request must still run to its end.
                scope.cancel()
                gate.complete(Unit)
                advanceUntilIdle()

                assertTrue(completed)
                coVerify(exactly = 1) { operations.requestReviewSave(d2Record(true)) }
            } finally {
                gate.complete(Unit)
            }
        }

    @Test
    fun `every PlainSettingResult value is mapped (WRITE 5), with no VM diagnostic for a refusal or a failure result`() =
        runTest(testDispatcher) {
            PlainSettingResult.values().forEach { result ->
                coEvery { operations.requestDiscreetChange(true) } returns result
                val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
                val viewModel = viewModel(repository)
                advanceUntilIdle()

                viewModel.setDiscreetNotifications(true)
                advanceUntilIdle()

                val expected = when (result) {
                    PlainSettingResult.COMMITTED -> AppLockPrivacySectionState.Ready(false, false, null)
                    PlainSettingResult.FAILED,
                    PlainSettingResult.REJECTED_FORCED,
                    PlainSettingResult.ALREADY_IN_FLIGHT ->
                        AppLockPrivacySectionState.Ready(false, false, AppLockPrivacySectionNotice.SAVE_FAILED)
                }
                assertEquals("$result", expected, viewModel.uiState.value)
                // COMMITTED does not re-read; every other value re-reads once (the initial subscription + 1).
                val expectedObserveCalls = if (result == PlainSettingResult.COMMITTED) 1 else 2
                assertEquals("$result", expectedObserveCalls, repository.observeCalls)
                // The failure diagnostic of a FAILED result is owned by the operations and the controller.
                coVerify(exactly = 0) { emitter.emit(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED, any()) }
            }
        }
}
