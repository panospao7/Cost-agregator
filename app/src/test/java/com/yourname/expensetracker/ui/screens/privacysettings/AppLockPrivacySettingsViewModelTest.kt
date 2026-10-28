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
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.AuthPurpose
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapability
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapabilityProvider
import com.yourname.expensetracker.domain.privacy.applock.InertReason
import com.yourname.expensetracker.domain.privacy.applock.OpenOriginEnd
import com.yourname.expensetracker.domain.privacy.applock.OpenOriginEndKind
import com.yourname.expensetracker.domain.privacy.applock.OpenOriginPrompt
import com.yourname.expensetracker.domain.privacy.applock.PlainSettingResult
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticMetadata
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.applock.ReviewSaveResult
import com.yourname.expensetracker.domain.privacy.applock.SettingsRequestResult
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.MockKMatcherScope
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S12: the ViewModel of the 1a "App lock & privacy" section (INV-44, INV-46, INV-47, AE-85, AE-88,
 * AE-89, AE-105). S20-e: the review-save routes through [AppLockSettingsOperations.requestReviewSave] with the D2
 * record, and the ViewModel emits no review-save diagnostic and runs no sweep (the controller and the operations own
 * them, D7).
 *
 * S22-a.1: the lock row (AE-34, 35, 36, 54, 65, 70, 93, 113, 114, 118) and the open-origin end notices, including the
 * S22-a.0 privacy-review bindings B-1 (the notice follows the end's prompt), B-2 (the replay baseline) and B-3 (no
 * optimistic switch; Waiting only from the controller state).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockPrivacySettingsViewModelTest : ViewModelTestUtils() {

    private val off = AppLockPrivacySettings(discreetNotifications = false)
    private val on = AppLockPrivacySettings(discreetNotifications = true)

    /** Committed lock on, stored discreet off: discreet is forced on (AE-65). */
    private val lockOn = AppLockPrivacySettings(discreetNotifications = false, appLockEnabled = true)

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
    private val capabilityProvider = mockk<DeviceAuthCapabilityProvider>()
    private val emitter = mockk<PrivacyDiagnosticsEmitter>(relaxed = true)

    /**
     * The controller's published state and open-origin end signal are real StateFlows (a relaxed mock is not a usable
     * StateFlow). The tests drive them the way the controller would.
     */
    private val controllerState = MutableStateFlow<AppLockState>(AppLockState.Disabled)
    private val endSignal = MutableStateFlow<OpenOriginEnd?>(null)

    /** What the capability provider reports on the next read, and an optional failure to throw instead. */
    private var capability = DeviceAuthCapability.AVAILABLE
    private var capabilityFailure: Throwable? = null

    init {
        every { controller.state } returns controllerState
        every { controller.openOriginEnd } returns endSignal
        every { capabilityProvider.current() } answers {
            val failure = capabilityFailure
            if (failure != null) throw failure
            capability
        }
    }

    private fun MockKMatcherScope.fieldIs(field: AppLockSettingField): PrivacyDiagnosticMetadata =
        match { it.entries[PrivacyDiagnosticKey.FIELD] == field }

    private fun appScope() = CoroutineScope(SupervisorJob() + testDispatcher)

    private fun viewModel(
        repository: FakeRepository,
        scope: CoroutineScope = appScope()
    ) = AppLockPrivacySettingsViewModel(repository, operations, controller, capabilityProvider, emitter, scope)

    /** The Ready state of a committed-off lock with nothing blocking it: the pre-S22 shape plus the lock row Off. */
    private fun readyLockOff(
        discreetOn: Boolean,
        isWriting: Boolean,
        notice: AppLockPrivacySectionNotice?
    ) = AppLockPrivacySectionState.Ready(
        discreetOn = discreetOn,
        isWriting = isWriting,
        notice = notice,
        discreetForced = false,
        lock = AppLockRowState.Off
    )

    private fun readyOf(viewModel: AppLockPrivacySettingsViewModel): AppLockPrivacySectionState.Ready {
        val state = viewModel.uiState.value
        assertTrue("expected Ready but was $state", state is AppLockPrivacySectionState.Ready)
        return state as AppLockPrivacySectionState.Ready
    }

    private fun TestScope.openSection(repository: FakeRepository): AppLockPrivacySettingsViewModel {
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        return viewModel
    }

    private fun endOf(
        kind: OpenOriginEndKind,
        prompt: OpenOriginPrompt = OpenOriginPrompt.ENABLE,
        attemptId: Long = 1L
    ) = OpenOriginEnd(attemptId, prompt, kind)

    // ---------------------------------------------------------------------------------------------------------
    // 1a: the discreet row, the review card, retry (unchanged behaviour, new Ready shape)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `state is Loading until the first read arrives and never Ready or Off`() = runTest(testDispatcher) {
        val viewModel = viewModel(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

        assertEquals(AppLockPrivacySectionState.Loading, viewModel.uiState.value)

        advanceUntilIdle()
        assertEquals(readyLockOff(false, false, null), viewModel.uiState.value)
    }

    @Test
    fun `first run default renders the committed default`() = runTest(testDispatcher) {
        val viewModel = viewModel(FakeRepository(AppLockSettingsLoadState.FirstRunDefault(off)))
        advanceUntilIdle()

        assertEquals(readyLockOff(false, false, null), viewModel.uiState.value)
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
            assertEquals(readyLockOff(false, true, null), viewModel.uiState.value)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(readyLockOff(true, false, null), viewModel.uiState.value)
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
                readyLockOff(false, false, AppLockPrivacySectionNotice.SAVE_FAILED),
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
                readyLockOff(false, false, AppLockPrivacySectionNotice.SAVE_FAILED),
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
                readyLockOff(false, false, AppLockPrivacySectionNotice.SAVE_FAILED),
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
            readyLockOff(false, false, AppLockPrivacySectionNotice.SAVE_FAILED),
            viewModel.uiState.value
        )

        viewModel.setDiscreetNotifications(true)
        advanceUntilIdle()
        assertEquals(readyLockOff(false, false, null), viewModel.uiState.value)
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
        assertEquals(readyLockOff(true, false, null), viewModel.uiState.value)
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
                readyLockOff(false, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
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
                readyLockOff(true, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
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
                readyLockOff(true, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
                viewModel.uiState.value
            )
            // The flag was consumed: the store turning unreadable and then readable again shows no second notice.
            repository.state.value = AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED)
            advanceUntilIdle()
            repository.state.value = AppLockSettingsLoadState.Loaded(d2Record(true))
            advanceUntilIdle()
            assertEquals(readyLockOff(true, false, null), viewModel.uiState.value)
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
            assertEquals(readyLockOff(true, false, null), viewModel.uiState.value)
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

            assertEquals(readyLockOff(true, false, null), viewModel.uiState.value)
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

            assertEquals(readyLockOff(true, false, null), viewModel.uiState.value)
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
                readyLockOff(true, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
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
                readyLockOff(true, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
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

            assertEquals(readyLockOff(true, false, null), viewModel.uiState.value)
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

            assertEquals(readyLockOff(true, false, null), viewModel.uiState.value)
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
                readyLockOff(true, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
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
                readyLockOff(false, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
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
            readyLockOff(false, false, AppLockPrivacySectionNotice.SAVE_FAILED),
            viewModel.uiState.value
        )

        repository.state.value = AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR)
        advanceUntilIdle()
        assertEquals(AppLockPrivacySectionState.TransientUnreadable, viewModel.uiState.value)

        repository.state.value = AppLockSettingsLoadState.Loaded(off)
        advanceUntilIdle()
        assertEquals(readyLockOff(false, false, null), viewModel.uiState.value)
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

        assertEquals(readyLockOff(true, false, null), viewModel.uiState.value)
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
                readyLockOff(true, false, AppLockPrivacySectionNotice.REVIEW_SAVED),
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
        assertEquals(readyLockOff(false, false, null), viewModel.uiState.value)
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
            assertEquals(readyLockOff(false, false, null), viewModel.uiState.value)
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
                    PlainSettingResult.COMMITTED -> readyLockOff(false, false, null)
                    PlainSettingResult.FAILED,
                    PlainSettingResult.REJECTED_FORCED,
                    PlainSettingResult.ALREADY_IN_FLIGHT ->
                        readyLockOff(false, false, AppLockPrivacySectionNotice.SAVE_FAILED)
                }
                assertEquals("$result", expected, viewModel.uiState.value)
                // COMMITTED does not re-read; every other value re-reads once (the initial subscription + 1).
                val expectedObserveCalls = if (result == PlainSettingResult.COMMITTED) 1 else 2
                assertEquals("$result", expectedObserveCalls, repository.observeCalls)
                // The failure diagnostic of a FAILED result is owned by the operations and the controller.
                coVerify(exactly = 0) { emitter.emit(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED, any()) }
            }
        }

    // ---------------------------------------------------------------------------------------------------------
    // S22-a.1: the lock row from the capability hint and the committed value (AE-34, AE-35, AE-65)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `AE-34 UNSUPPORTED_API with the lock off shows Unavailable OS and the discreet row stays editable`() =
        runTest(testDispatcher) {
            capability = DeviceAuthCapability.UNSUPPORTED_API
            coEvery { operations.requestDiscreetChange(true) } returns PlainSettingResult.COMMITTED
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

            assertEquals(
                AppLockPrivacySectionState.Ready(
                    discreetOn = false,
                    isWriting = false,
                    notice = null,
                    discreetForced = false,
                    lock = AppLockRowState.Unavailable(AppLockUnavailableReason.OS)
                ),
                viewModel.uiState.value
            )

            // OD-20: discreet is still available on API < 30.
            viewModel.setDiscreetNotifications(true)
            advanceUntilIdle()
            coVerify(exactly = 1) { operations.requestDiscreetChange(true) }
            assertFalse(readyOf(viewModel).discreetForced)
        }

    @Test
    fun `AE-35 NO_CREDENTIAL with the lock off shows Unavailable NOT_SECURE`() = runTest(testDispatcher) {
        capability = DeviceAuthCapability.NO_CREDENTIAL
        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

        assertEquals(
            AppLockRowState.Unavailable(AppLockUnavailableReason.NOT_SECURE),
            readyOf(viewModel).lock
        )
    }

    @Test
    fun `AE-34 AE-35 control - an AVAILABLE hint, and a transient hint before any refusal, leave the row Off and tappable`() =
        runTest(testDispatcher) {
            listOf(
                DeviceAuthCapability.AVAILABLE,
                DeviceAuthCapability.HW_UNAVAILABLE,
                DeviceAuthCapability.UNKNOWN,
                DeviceAuthCapability.CHECK_FAILED
            ).forEach { hint ->
                capability = hint
                val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
                assertEquals("$hint", AppLockRowState.Off, readyOf(viewModel).lock)
            }
        }

    @Test
    fun `a capability provider that throws is a CHECK_FAILED hint and never a crash or an Unavailable row`() =
        runTest(testDispatcher) {
            capabilityFailure = IllegalStateException("sensor service down")
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

            assertEquals(AppLockRowState.Off, readyOf(viewModel).lock)
        }

    @Test
    fun `a CancellationException from the capability provider is rethrown, not mapped to a hint`() =
        runTest(testDispatcher) {
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
            capabilityFailure = CancellationException("cancelled")

            var thrown: CancellationException? = null
            try {
                viewModel.refreshCapabilityHint()
            } catch (e: CancellationException) {
                thrown = e
            }

            assertNotNull(thrown)
        }

    @Test
    fun `AE-65 a committed lock on with stored discreet off shows discreet on and forced`() = runTest(testDispatcher) {
        controllerState.value = AppLockState.Unlocked
        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

        assertEquals(
            AppLockPrivacySectionState.Ready(
                discreetOn = true,
                isWriting = false,
                notice = null,
                discreetForced = true,
                lock = AppLockRowState.On
            ),
            viewModel.uiState.value
        )
    }

    @Test
    fun `AE-65 the discreet value stays forced while the lock is paused (Inert), for every reason`() =
        runTest(testDispatcher) {
            controllerState.value = AppLockState.Inert(InertReason.NO_CREDENTIAL)
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

            InertReason.values().forEach { reason ->
                controllerState.value = AppLockState.Inert(reason)
                advanceUntilIdle()
                val ready = readyOf(viewModel)
                assertTrue("$reason discreet shown on", ready.discreetOn)
                assertTrue("$reason discreet forced", ready.discreetForced)
            }
        }

    @Test
    fun `AE-65 control - a committed lock off never forces discreet, whatever the stored value`() =
        runTest(testDispatcher) {
            listOf(false, true).forEach { stored ->
                val viewModel = openSection(
                    FakeRepository(AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = stored)))
                )
                val ready = readyOf(viewModel)
                assertEquals("stored=$stored", stored, ready.discreetOn)
                assertFalse("stored=$stored", ready.discreetForced)
            }
        }

    @Test
    fun `AE-65 the forced flag follows the committed lock, not the prompt, when the lock turns on`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
            val viewModel = openSection(repository)
            assertFalse(readyOf(viewModel).discreetForced)

            // The prompt is in flight: nothing is committed, so nothing is forced yet.
            controllerState.value = AppLockState.Authenticating(3L, AuthPurpose.ENABLE)
            advanceUntilIdle()
            assertFalse(readyOf(viewModel).discreetForced)

            repository.state.value = AppLockSettingsLoadState.Loaded(lockOn)
            controllerState.value = AppLockState.Unlocked
            advanceUntilIdle()
            assertTrue(readyOf(viewModel).discreetForced)
            assertTrue(readyOf(viewModel).discreetOn)
        }

    // ---------------------------------------------------------------------------------------------------------
    // S22-a.1: AE-54 Inert row; turning off (T18, T19)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `AE-54 committed on with Inert API_UNSUPPORTED shows InertOn`() = runTest(testDispatcher) {
        capability = DeviceAuthCapability.UNSUPPORTED_API
        controllerState.value = AppLockState.Inert(InertReason.API_UNSUPPORTED)
        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

        assertEquals(AppLockRowState.InertOn(InertReason.API_UNSUPPORTED), readyOf(viewModel).lock)
    }

    @Test
    fun `AE-54 every other Inert reason with the lock committed on also shows InertOn with that reason`() =
        runTest(testDispatcher) {
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

            InertReason.values().forEach { reason ->
                controllerState.value = AppLockState.Inert(reason)
                advanceUntilIdle()
                assertEquals("$reason", AppLockRowState.InertOn(reason), readyOf(viewModel).lock)
            }
        }

    @Test
    fun `AE-54 boundary - an Inert controller state with the lock committed off never shows InertOn`() =
        runTest(testDispatcher) {
            controllerState.value = AppLockState.Inert(InertReason.API_UNSUPPORTED)
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

            assertEquals(AppLockRowState.Off, readyOf(viewModel).lock)
        }

    @Test
    fun `turning off from On calls requestDisable exactly once and the row stays On until it is committed off`() =
        runTest(testDispatcher) {
            every { operations.requestDisable() } returns SettingsRequestResult.STARTED
            controllerState.value = AppLockState.Unlocked
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

            viewModel.onTurnOffRequested()
            advanceUntilIdle()

            verify(exactly = 1) { operations.requestDisable() }
            assertEquals(AppLockRowState.On, readyOf(viewModel).lock)

            // The prompt is in flight: a second tap cannot reach the operations.
            controllerState.value = AppLockState.Authenticating(9L, AuthPurpose.CONFIRM_CHANGE)
            advanceUntilIdle()
            viewModel.onTurnOffRequested()
            advanceUntilIdle()
            verify(exactly = 1) { operations.requestDisable() }
        }

    @Test
    fun `T19 turning off while Inert calls requestDisable exactly once, and the negative-id DISABLE end shows LOCK_OFF`() =
        runTest(testDispatcher) {
            every { operations.requestDisable() } returns SettingsRequestResult.STARTED
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(lockOn))
            controllerState.value = AppLockState.Inert(InertReason.NO_CREDENTIAL)
            val viewModel = openSection(repository)
            assertEquals(AppLockRowState.InertOn(InertReason.NO_CREDENTIAL), readyOf(viewModel).lock)

            viewModel.onTurnOffRequested()
            advanceUntilIdle()
            verify(exactly = 1) { operations.requestDisable() }

            // T19 has no attempt: its end carries a negative id and the DISABLE prompt, and APPLIED comes after the apply.
            repository.state.value = AppLockSettingsLoadState.Loaded(
                AppLockPrivacySettings(discreetNotifications = false, appLockEnabled = false)
            )
            controllerState.value = AppLockState.Disabled
            endSignal.value = OpenOriginEnd(-4L, OpenOriginPrompt.DISABLE, OpenOriginEndKind.APPLIED)
            advanceUntilIdle()

            assertEquals(readyLockOff(false, false, AppLockPrivacySectionNotice.LOCK_OFF), viewModel.uiState.value)
        }

    @Test
    fun `B-3 a T19 disable refused by the re-check publishes no end, so the row stays InertOn with no notice`() =
        runTest(testDispatcher) {
            every { operations.requestDisable() } returns SettingsRequestResult.STARTED
            controllerState.value = AppLockState.Inert(InertReason.HW_UNAVAILABLE)
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

            viewModel.onTurnOffRequested()
            advanceUntilIdle()

            // No end arrives. The row must not wait for one and must not invent a value.
            val ready = readyOf(viewModel)
            assertEquals(AppLockRowState.InertOn(InertReason.HW_UNAVAILABLE), ready.lock)
            assertNull(ready.notice)
        }

    @Test
    fun `REVIEW_REQUIRED from a disable resubscribes to the repository and shows no notice`() =
        runTest(testDispatcher) {
            every { operations.requestDisable() } returns SettingsRequestResult.REVIEW_REQUIRED
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(lockOn))
            controllerState.value = AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH)
            val viewModel = openSection(repository)
            assertEquals(1, repository.observeCalls)

            viewModel.onTurnOffRequested()
            advanceUntilIdle()

            assertEquals("the settings are re-read", 2, repository.observeCalls)
            assertNull(readyOf(viewModel).notice)
            verify(exactly = 1) { operations.requestDisable() }
        }

    @Test
    fun `every SettingsRequestResult of a disable is mapped, with a resubscribe only where nothing was started`() =
        runTest(testDispatcher) {
            controllerState.value = AppLockState.Unlocked
            SettingsRequestResult.values().forEach { result ->
                capability = DeviceAuthCapability.AVAILABLE
                every { operations.requestDisable() } returns result
                val repository = FakeRepository(AppLockSettingsLoadState.Loaded(lockOn))
                val viewModel = openSection(repository)

                viewModel.onTurnOffRequested()
                advanceUntilIdle()

                // REFUSED_UNAVAILABLE re-reads the capability, not the repository.
                val expectedObserveCalls = when (result) {
                    SettingsRequestResult.STARTED,
                    SettingsRequestResult.REFUSED_UNAVAILABLE -> 1
                    SettingsRequestResult.ALREADY_IN_FLIGHT,
                    SettingsRequestResult.NOT_IN_ORIGIN,
                    SettingsRequestResult.REVIEW_REQUIRED -> 2
                }
                // The capability re-reads AVAILABLE here, so a refused disable is never silent: SAVE_FAILED.
                val expectedNotice = when (result) {
                    SettingsRequestResult.REFUSED_UNAVAILABLE -> AppLockPrivacySectionNotice.SAVE_FAILED
                    SettingsRequestResult.STARTED,
                    SettingsRequestResult.ALREADY_IN_FLIGHT,
                    SettingsRequestResult.NOT_IN_ORIGIN,
                    SettingsRequestResult.REVIEW_REQUIRED -> null
                }
                assertEquals("$result observer subscriptions", expectedObserveCalls, repository.observeCalls)
                assertEquals("$result notice", expectedNotice, readyOf(viewModel).notice)
                assertEquals("$result row", AppLockRowState.On, readyOf(viewModel).lock)
            }
        }

    @Test
    fun `a disable refused as unavailable whose capability re-read is AVAILABLE shows SAVE_FAILED and keeps the row On`() =
        runTest(testDispatcher) {
            every { operations.requestDisable() } returns SettingsRequestResult.REFUSED_UNAVAILABLE
            controllerState.value = AppLockState.Unlocked
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

            viewModel.onTurnOffRequested()
            advanceUntilIdle()

            assertEquals(
                AppLockPrivacySectionState.Ready(
                    discreetOn = true,
                    isWriting = false,
                    notice = AppLockPrivacySectionNotice.SAVE_FAILED,
                    discreetForced = true,
                    lock = AppLockRowState.On
                ),
                viewModel.uiState.value
            )
            verify(exactly = 1) { operations.requestDisable() }
        }

    @Test
    fun `a disable refused as unavailable while Inert whose capability re-read is AVAILABLE shows SAVE_FAILED and keeps InertOn`() =
        runTest(testDispatcher) {
            every { operations.requestDisable() } returns SettingsRequestResult.REFUSED_UNAVAILABLE
            controllerState.value = AppLockState.Inert(InertReason.NO_CREDENTIAL)
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

            viewModel.onTurnOffRequested()
            advanceUntilIdle()

            val ready = readyOf(viewModel)
            assertEquals(AppLockPrivacySectionNotice.SAVE_FAILED, ready.notice)
            assertEquals(AppLockRowState.InertOn(InertReason.NO_CREDENTIAL), ready.lock)
        }

    @Test
    fun `boundary - a disable refused as unavailable whose re-read is still unusable names no enable failure and keeps the row On`() =
        runTest(testDispatcher) {
            every { operations.requestDisable() } returns SettingsRequestResult.REFUSED_UNAVAILABLE
            controllerState.value = AppLockState.Unlocked
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

            capability = DeviceAuthCapability.HW_UNAVAILABLE
            viewModel.onTurnOffRequested()
            advanceUntilIdle()

            // The committed lock is on, so the row never becomes Unavailable (which is a committed-off variant), and the
            // enable failure is never shown for a disable.
            val ready = readyOf(viewModel)
            assertEquals(AppLockRowState.On, ready.lock)
            assertNull(ready.notice)
        }

    @Test
    fun `an enable refused as unavailable whose capability re-read is AVAILABLE shows ENABLE_FAILED, not SAVE_FAILED`() =
        runTest(testDispatcher) {
            every { operations.requestEnable() } returns SettingsRequestResult.REFUSED_UNAVAILABLE
            capability = DeviceAuthCapability.AVAILABLE
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

            viewModel.onEnableConfirmed()
            advanceUntilIdle()

            assertEquals(AppLockPrivacySectionNotice.ENABLE_FAILED, readyOf(viewModel).notice)
            assertEquals(AppLockRowState.Off, readyOf(viewModel).lock)
        }

    @Test
    fun `an exception from requestDisable shows SAVE_FAILED and keeps the row On`() = runTest(testDispatcher) {
        every { operations.requestDisable() } throws IllegalStateException("boom")
        controllerState.value = AppLockState.Unlocked
        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

        viewModel.onTurnOffRequested()
        advanceUntilIdle()

        val ready = readyOf(viewModel)
        assertEquals(AppLockPrivacySectionNotice.SAVE_FAILED, ready.notice)
        assertEquals(AppLockRowState.On, ready.lock)
    }

    @Test
    fun `a CancellationException from requestDisable is rethrown and is not mapped to a notice`() =
        runTest(testDispatcher) {
            every { operations.requestDisable() } throws CancellationException("cancelled")
            controllerState.value = AppLockState.Unlocked
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

            var thrown: CancellationException? = null
            try {
                viewModel.onTurnOffRequested()
            } catch (e: CancellationException) {
                thrown = e
            }
            advanceUntilIdle()

            assertNotNull(thrown)
            assertNull(readyOf(viewModel).notice)
        }

    @Test
    fun `the turn-off tap is ignored unless the lock row is On or paused-On`() = runTest(testDispatcher) {
        // Loading: nothing has been read yet.
        val loading = viewModel(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))
        loading.onTurnOffRequested()

        // Off.
        val offVm = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
        offVm.onTurnOffRequested()

        // Unavailable.
        capability = DeviceAuthCapability.NO_CREDENTIAL
        val unavailable = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
        unavailable.onTurnOffRequested()
        capability = DeviceAuthCapability.AVAILABLE

        // Waiting.
        controllerState.value = AppLockState.Authenticating(2L, AuthPurpose.CONFIRM_CHANGE)
        val waiting = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))
        waiting.onTurnOffRequested()
        controllerState.value = AppLockState.Disabled

        // Transient unreadable.
        val transient = openSection(
            FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR))
        )
        transient.onTurnOffRequested()
        advanceUntilIdle()

        verify(exactly = 0) { operations.requestDisable() }
    }

    // ---------------------------------------------------------------------------------------------------------
    // S22-a.1: enabling (T26, T17), AE-36, AE-93, AE-113
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `AE-113 Authenticating ENABLE with the lock committed off is Waiting ENABLE with committedOn false`() =
        runTest(testDispatcher) {
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

            controllerState.value = AppLockState.Authenticating(5L, AuthPurpose.ENABLE)
            advanceUntilIdle()

            assertEquals(
                AppLockRowState.Waiting(AuthPurpose.ENABLE, committedOn = false),
                readyOf(viewModel).lock
            )
        }

    @Test
    fun `AE-114 Authenticating CONFIRM_CHANGE with the lock committed on is Waiting with committedOn true`() =
        runTest(testDispatcher) {
            controllerState.value = AppLockState.Unlocked
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

            controllerState.value = AppLockState.Authenticating(6L, AuthPurpose.CONFIRM_CHANGE)
            advanceUntilIdle()

            assertEquals(
                AppLockRowState.Waiting(AuthPurpose.CONFIRM_CHANGE, committedOn = true),
                readyOf(viewModel).lock
            )
        }

    @Test
    fun `requestEnable is called once per tap, and a second tap while the prompt is Waiting never reaches it`() =
        runTest(testDispatcher) {
            every { operations.requestEnable() } returns SettingsRequestResult.STARTED
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

            viewModel.onEnableConfirmed()
            advanceUntilIdle()
            verify(exactly = 1) { operations.requestEnable() }

            controllerState.value = AppLockState.Authenticating(7L, AuthPurpose.ENABLE)
            advanceUntilIdle()
            viewModel.onEnableConfirmed()
            advanceUntilIdle()
            verify(exactly = 1) { operations.requestEnable() }
        }

    @Test
    fun `the enable confirmation is ignored unless the lock row is Off`() = runTest(testDispatcher) {
        // Loading: nothing has been read yet.
        val loading = viewModel(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
        loading.onEnableConfirmed()

        // On.
        controllerState.value = AppLockState.Unlocked
        openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn))).onEnableConfirmed()

        // Paused-On.
        controllerState.value = AppLockState.Inert(InertReason.NO_CREDENTIAL)
        openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn))).onEnableConfirmed()

        // Unavailable (OS, NOT_SECURE).
        controllerState.value = AppLockState.Disabled
        listOf(DeviceAuthCapability.UNSUPPORTED_API, DeviceAuthCapability.NO_CREDENTIAL).forEach { hint ->
            capability = hint
            openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off))).onEnableConfirmed()
        }
        capability = DeviceAuthCapability.AVAILABLE

        // Waiting.
        controllerState.value = AppLockState.Authenticating(2L, AuthPurpose.ENABLE)
        openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off))).onEnableConfirmed()
        controllerState.value = AppLockState.Disabled

        // Transient unreadable.
        openSection(FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR)))
            .onEnableConfirmed()
        advanceUntilIdle()

        verify(exactly = 0) { operations.requestEnable() }
    }

    @Test
    fun `AE-36 a cancelled ENABLE prompt shows LOCK_NOT_TURNED_ON and the row is Off`() = runTest(testDispatcher) {
        every { operations.requestEnable() } returns SettingsRequestResult.STARTED
        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

        viewModel.onEnableConfirmed()
        controllerState.value = AppLockState.Authenticating(7L, AuthPurpose.ENABLE)
        advanceUntilIdle()
        assertEquals(AppLockRowState.Waiting(AuthPurpose.ENABLE, committedOn = false), readyOf(viewModel).lock)

        controllerState.value = AppLockState.Disabled
        endSignal.value = endOf(OpenOriginEndKind.CANCELLED, OpenOriginPrompt.ENABLE, attemptId = 7L)
        advanceUntilIdle()

        assertEquals(
            readyLockOff(false, false, AppLockPrivacySectionNotice.LOCK_NOT_TURNED_ON),
            viewModel.uiState.value
        )
        verify(exactly = 1) { operations.requestEnable() }
    }

    @Test
    fun `AE-36 an APPLIED ENABLE end shows LOCK_ON, and the row follows the committed value`() =
        runTest(testDispatcher) {
            every { operations.requestEnable() } returns SettingsRequestResult.STARTED
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
            val viewModel = openSection(repository)

            viewModel.onEnableConfirmed()
            controllerState.value = AppLockState.Authenticating(8L, AuthPurpose.ENABLE)
            advanceUntilIdle()

            // The apply commits, then the controller moves to UNLOCKED and publishes the end.
            repository.state.value = AppLockSettingsLoadState.Loaded(lockOn)
            controllerState.value = AppLockState.Unlocked
            endSignal.value = endOf(OpenOriginEndKind.APPLIED, OpenOriginPrompt.ENABLE, attemptId = 8L)
            advanceUntilIdle()

            assertEquals(
                AppLockPrivacySectionState.Ready(
                    discreetOn = true,
                    isWriting = false,
                    notice = AppLockPrivacySectionNotice.LOCK_ON,
                    discreetForced = true,
                    lock = AppLockRowState.On
                ),
                viewModel.uiState.value
            )
        }

    @Test
    fun `no optimistic switch - the row stays Off through the tap and the prompt until committed true arrives`() =
        runTest(testDispatcher) {
            every { operations.requestEnable() } returns SettingsRequestResult.STARTED
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
            val viewModel = openSection(repository)

            viewModel.onEnableConfirmed()
            advanceUntilIdle()
            assertEquals("after the tap", AppLockRowState.Off, readyOf(viewModel).lock)

            controllerState.value = AppLockState.Authenticating(4L, AuthPurpose.ENABLE)
            advanceUntilIdle()
            assertEquals(
                "while the prompt is open",
                AppLockRowState.Waiting(AuthPurpose.ENABLE, committedOn = false),
                readyOf(viewModel).lock
            )

            // The end arrives before the observer has delivered the committed value (a lagging observer).
            controllerState.value = AppLockState.Disabled
            endSignal.value = endOf(OpenOriginEndKind.APPLIED, OpenOriginPrompt.ENABLE, attemptId = 4L)
            advanceUntilIdle()
            assertEquals("end seen, commit not yet observed", AppLockRowState.Off, readyOf(viewModel).lock)
            assertEquals(AppLockPrivacySectionNotice.LOCK_ON, readyOf(viewModel).notice)

            repository.state.value = AppLockSettingsLoadState.Loaded(lockOn)
            controllerState.value = AppLockState.Unlocked
            advanceUntilIdle()
            assertEquals("committed true observed", AppLockRowState.On, readyOf(viewModel).lock)
        }

    @Test
    fun `AE-93 a REFUSED_UNAVAILABLE enable with HW_UNAVAILABLE, UNKNOWN or CHECK_FAILED shows Unavailable NOW and no notice`() =
        runTest(testDispatcher) {
            every { operations.requestEnable() } returns SettingsRequestResult.REFUSED_UNAVAILABLE
            listOf(
                DeviceAuthCapability.HW_UNAVAILABLE,
                DeviceAuthCapability.UNKNOWN,
                DeviceAuthCapability.CHECK_FAILED
            ).forEachIndexed { index, refusal ->
                capability = DeviceAuthCapability.AVAILABLE
                val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

                capability = refusal
                viewModel.onEnableConfirmed()
                advanceUntilIdle()

                val ready = readyOf(viewModel)
                assertEquals("$refusal", AppLockRowState.Unavailable(AppLockUnavailableReason.NOW), ready.lock)
                assertNull("$refusal", ready.notice)
                verify(exactly = index + 1) { operations.requestEnable() }
            }
        }

    @Test
    fun `AE-93 Try again with the capability now AVAILABLE returns the row to Off, and still unavailable keeps NOW`() =
        runTest(testDispatcher) {
            every { operations.requestEnable() } returns SettingsRequestResult.REFUSED_UNAVAILABLE
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
            capability = DeviceAuthCapability.HW_UNAVAILABLE
            viewModel.onEnableConfirmed()
            advanceUntilIdle()
            assertEquals(AppLockRowState.Unavailable(AppLockUnavailableReason.NOW), readyOf(viewModel).lock)

            // Still unavailable: the explanation stays.
            viewModel.refreshCapabilityHint()
            advanceUntilIdle()
            assertEquals(AppLockRowState.Unavailable(AppLockUnavailableReason.NOW), readyOf(viewModel).lock)

            // Usable now: back to Off (T17 applies on the next tap).
            capability = DeviceAuthCapability.AVAILABLE
            viewModel.refreshCapabilityHint()
            advanceUntilIdle()
            assertEquals(AppLockRowState.Off, readyOf(viewModel).lock)

            // The hint re-read never starts a request.
            verify(exactly = 1) { operations.requestEnable() }
        }

    @Test
    fun `AE-93 a NOW row is replaced by the specific variant when the re-read finds NO_CREDENTIAL or UNSUPPORTED_API`() =
        runTest(testDispatcher) {
            every { operations.requestEnable() } returns SettingsRequestResult.REFUSED_UNAVAILABLE
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
            capability = DeviceAuthCapability.UNKNOWN
            viewModel.onEnableConfirmed()
            advanceUntilIdle()
            assertEquals(AppLockRowState.Unavailable(AppLockUnavailableReason.NOW), readyOf(viewModel).lock)

            capability = DeviceAuthCapability.NO_CREDENTIAL
            viewModel.refreshCapabilityHint()
            advanceUntilIdle()
            assertEquals(AppLockRowState.Unavailable(AppLockUnavailableReason.NOT_SECURE), readyOf(viewModel).lock)

            capability = DeviceAuthCapability.UNSUPPORTED_API
            viewModel.refreshCapabilityHint()
            advanceUntilIdle()
            assertEquals(AppLockRowState.Unavailable(AppLockUnavailableReason.OS), readyOf(viewModel).lock)
        }

    @Test
    fun `AE-93 a refusal whose re-read finds the capability AVAILABLE shows ENABLE_FAILED and the row is Off`() =
        runTest(testDispatcher) {
            every { operations.requestEnable() } returns SettingsRequestResult.REFUSED_UNAVAILABLE
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

            viewModel.onEnableConfirmed()
            advanceUntilIdle()

            assertEquals(
                readyLockOff(false, false, AppLockPrivacySectionNotice.ENABLE_FAILED),
                viewModel.uiState.value
            )
        }

    @Test
    fun `AE-93 a refusal with a throwing capability provider is a CHECK_FAILED read and shows Unavailable NOW`() =
        runTest(testDispatcher) {
            every { operations.requestEnable() } returns SettingsRequestResult.REFUSED_UNAVAILABLE
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

            capabilityFailure = IllegalStateException("sensor service down")
            viewModel.onEnableConfirmed()
            advanceUntilIdle()

            assertEquals(AppLockRowState.Unavailable(AppLockUnavailableReason.NOW), readyOf(viewModel).lock)
        }

    @Test
    fun `every SettingsRequestResult of an enable is mapped, with the notice and the resubscribe count per result`() =
        runTest(testDispatcher) {
            SettingsRequestResult.values().forEach { result ->
                every { operations.requestEnable() } returns result
                capability = DeviceAuthCapability.AVAILABLE
                val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
                val viewModel = openSection(repository)

                viewModel.onEnableConfirmed()
                advanceUntilIdle()

                // (observer subscriptions, notice). REFUSED_UNAVAILABLE re-reads the capability, not the repository.
                val expected: Pair<Int, AppLockPrivacySectionNotice?> = when (result) {
                    SettingsRequestResult.STARTED -> 1 to null
                    SettingsRequestResult.ALREADY_IN_FLIGHT,
                    SettingsRequestResult.NOT_IN_ORIGIN,
                    SettingsRequestResult.REVIEW_REQUIRED -> 2 to null
                    SettingsRequestResult.REFUSED_UNAVAILABLE -> 1 to AppLockPrivacySectionNotice.ENABLE_FAILED
                }
                assertEquals("$result observer subscriptions", expected.first, repository.observeCalls)
                assertEquals("$result notice", expected.second, readyOf(viewModel).notice)
                assertEquals("$result row", AppLockRowState.Off, readyOf(viewModel).lock)
            }
        }

    @Test
    fun `an exception from requestEnable shows ENABLE_FAILED and the row stays Off`() = runTest(testDispatcher) {
        every { operations.requestEnable() } throws IllegalStateException("boom")
        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

        viewModel.onEnableConfirmed()
        advanceUntilIdle()

        assertEquals(
            readyLockOff(false, false, AppLockPrivacySectionNotice.ENABLE_FAILED),
            viewModel.uiState.value
        )
    }

    @Test
    fun `a CancellationException from requestEnable is rethrown and is not mapped to ENABLE_FAILED`() =
        runTest(testDispatcher) {
            every { operations.requestEnable() } throws CancellationException("cancelled")
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

            var thrown: CancellationException? = null
            try {
                viewModel.onEnableConfirmed()
            } catch (e: CancellationException) {
                thrown = e
            }
            advanceUntilIdle()

            assertNotNull(thrown)
            assertNull(readyOf(viewModel).notice)
        }

    // ---------------------------------------------------------------------------------------------------------
    // S22-a.1: the open-origin end signal -> notices (AE-70, AE-114, AE-118, B-1, B-2, B-3)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `AE-114 APPLIED DISABLE shows LOCK_OFF, and the row is Off only after committed false arrives`() =
        runTest(testDispatcher) {
            every { operations.requestDisable() } returns SettingsRequestResult.STARTED
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(lockOn))
            controllerState.value = AppLockState.Unlocked
            val viewModel = openSection(repository)

            viewModel.onTurnOffRequested()
            controllerState.value = AppLockState.Authenticating(12L, AuthPurpose.CONFIRM_CHANGE)
            advanceUntilIdle()
            assertEquals(
                AppLockRowState.Waiting(AuthPurpose.CONFIRM_CHANGE, committedOn = true),
                readyOf(viewModel).lock
            )

            // The end arrives before the observer shows the commit.
            controllerState.value = AppLockState.Disabled
            endSignal.value = endOf(OpenOriginEndKind.APPLIED, OpenOriginPrompt.DISABLE, attemptId = 12L)
            advanceUntilIdle()
            assertEquals(AppLockRowState.On, readyOf(viewModel).lock)
            assertEquals(AppLockPrivacySectionNotice.LOCK_OFF, readyOf(viewModel).notice)

            repository.state.value = AppLockSettingsLoadState.Loaded(
                AppLockPrivacySettings(discreetNotifications = false, appLockEnabled = false)
            )
            advanceUntilIdle()
            assertEquals(
                readyLockOff(false, false, AppLockPrivacySectionNotice.LOCK_OFF),
                viewModel.uiState.value
            )
        }

    @Test
    fun `AE-114 a NOT_COMMITTED DISABLE end shows SAVE_FAILED with the row still On`() = runTest(testDispatcher) {
        every { operations.requestDisable() } returns SettingsRequestResult.STARTED
        controllerState.value = AppLockState.Unlocked
        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

        viewModel.onTurnOffRequested()
        controllerState.value = AppLockState.Authenticating(13L, AuthPurpose.CONFIRM_CHANGE)
        advanceUntilIdle()
        controllerState.value = AppLockState.Unlocked
        endSignal.value = endOf(OpenOriginEndKind.NOT_COMMITTED, OpenOriginPrompt.DISABLE, attemptId = 13L)
        advanceUntilIdle()

        val ready = readyOf(viewModel)
        assertEquals(AppLockPrivacySectionNotice.SAVE_FAILED, ready.notice)
        assertEquals(AppLockRowState.On, ready.lock)
    }

    @Test
    fun `a NOT_COMMITTED ENABLE end shows SAVE_FAILED with the row still Off`() = runTest(testDispatcher) {
        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

        endSignal.value = endOf(OpenOriginEndKind.NOT_COMMITTED, OpenOriginPrompt.ENABLE, attemptId = 14L)
        advanceUntilIdle()

        assertEquals(readyLockOff(false, false, AppLockPrivacySectionNotice.SAVE_FAILED), viewModel.uiState.value)
    }

    @Test
    fun `AE-70 a cancelled DISABLE prompt shows no notice and the row stays On`() = runTest(testDispatcher) {
        every { operations.requestDisable() } returns SettingsRequestResult.STARTED
        controllerState.value = AppLockState.Unlocked
        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

        viewModel.onTurnOffRequested()
        controllerState.value = AppLockState.Authenticating(15L, AuthPurpose.CONFIRM_CHANGE)
        advanceUntilIdle()
        controllerState.value = AppLockState.Unlocked
        endSignal.value = endOf(OpenOriginEndKind.CANCELLED, OpenOriginPrompt.DISABLE, attemptId = 15L)
        advanceUntilIdle()

        val ready = readyOf(viewModel)
        assertNull(ready.notice)
        assertEquals(AppLockRowState.On, ready.lock)
        assertTrue("discreet stays forced while the lock stays on", ready.discreetForced)
    }

    @Test
    fun `AE-118 a HOST_LOST end shows no notice and the row comes from the controller state alone`() =
        runTest(testDispatcher) {
            controllerState.value = AppLockState.Unlocked
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

            controllerState.value = AppLockState.Authenticating(16L, AuthPurpose.CONFIRM_CHANGE)
            advanceUntilIdle()
            assertEquals(
                AppLockRowState.Waiting(AuthPurpose.CONFIRM_CHANGE, committedOn = true),
                readyOf(viewModel).lock
            )

            // The host Activity is lost: the attempt ends as cancelled, the state returns to UNLOCKED, the switch is enabled again.
            controllerState.value = AppLockState.Unlocked
            endSignal.value = endOf(OpenOriginEndKind.HOST_LOST, OpenOriginPrompt.DISABLE, attemptId = 16L)
            advanceUntilIdle()

            val ready = readyOf(viewModel)
            assertNull(ready.notice)
            assertEquals(AppLockRowState.On, ready.lock)
        }

    @Test
    fun `AE-118 a HOST_LOST end of an ENABLE attempt shows no notice and the row is Off`() = runTest(testDispatcher) {
        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
        controllerState.value = AppLockState.Authenticating(17L, AuthPurpose.ENABLE)
        advanceUntilIdle()

        controllerState.value = AppLockState.Disabled
        endSignal.value = endOf(OpenOriginEndKind.HOST_LOST, OpenOriginPrompt.ENABLE, attemptId = 17L)
        advanceUntilIdle()

        assertEquals(readyLockOff(false, false, null), viewModel.uiState.value)
    }

    @Test
    fun `B-3 leaving Authenticating with no end at all shows the committed row and no notice`() =
        runTest(testDispatcher) {
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(lockOn))
            controllerState.value = AppLockState.Unlocked
            val viewModel = openSection(repository)

            // A DISABLE prompt succeeds and the apply never reaches onOpenOriginSettingApplied: no end is published.
            controllerState.value = AppLockState.Authenticating(18L, AuthPurpose.CONFIRM_CHANGE)
            advanceUntilIdle()
            assertTrue(readyOf(viewModel).lock is AppLockRowState.Waiting)

            repository.state.value = AppLockSettingsLoadState.Loaded(
                AppLockPrivacySettings(discreetNotifications = false, appLockEnabled = false)
            )
            controllerState.value = AppLockState.Disabled
            advanceUntilIdle()

            assertEquals(readyLockOff(false, false, null), viewModel.uiState.value)
        }

    @Test
    fun `B-3 an ENABLE prompt that returns to DISABLED with no end leaves the committed Off row`() =
        runTest(testDispatcher) {
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

            controllerState.value = AppLockState.Authenticating(19L, AuthPurpose.ENABLE)
            advanceUntilIdle()
            assertTrue(readyOf(viewModel).lock is AppLockRowState.Waiting)

            controllerState.value = AppLockState.Disabled
            advanceUntilIdle()

            assertEquals(readyLockOff(false, false, null), viewModel.uiState.value)
        }

    @Test
    fun `a LOCKED_OUT end shows TOO_MANY_ATTEMPTS for an ENABLE and for a DISABLE attempt`() =
        runTest(testDispatcher) {
            listOf(OpenOriginPrompt.ENABLE, OpenOriginPrompt.DISABLE).forEach { prompt ->
                endSignal.value = null
                val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

                endSignal.value = endOf(OpenOriginEndKind.LOCKED_OUT, prompt, attemptId = 20L)
                advanceUntilIdle()

                assertEquals("$prompt", AppLockPrivacySectionNotice.TOO_MANY_ATTEMPTS, readyOf(viewModel).notice)
            }
        }

    @Test
    fun `an ERROR end shows ENABLE_FAILED for an ENABLE attempt and nothing for DISABLE or CHANGE`() =
        runTest(testDispatcher) {
            val expected = mapOf(
                OpenOriginPrompt.ENABLE to AppLockPrivacySectionNotice.ENABLE_FAILED,
                OpenOriginPrompt.DISABLE to null,
                OpenOriginPrompt.CHANGE to null
            )
            expected.forEach { (prompt, notice) ->
                endSignal.value = null
                val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

                endSignal.value = endOf(OpenOriginEndKind.ERROR, prompt, attemptId = 21L)
                advanceUntilIdle()

                assertEquals("$prompt", notice, readyOf(viewModel).notice)
            }
        }

    @Test
    fun `a NOT_AVAILABLE end re-reads the capability hint and shows no notice`() = runTest(testDispatcher) {
        listOf(
            DeviceAuthCapability.NO_CREDENTIAL to AppLockRowState.Unavailable(AppLockUnavailableReason.NOT_SECURE),
            DeviceAuthCapability.UNSUPPORTED_API to AppLockRowState.Unavailable(AppLockUnavailableReason.OS),
            DeviceAuthCapability.HW_UNAVAILABLE to AppLockRowState.Unavailable(AppLockUnavailableReason.NOW)
        ).forEach { (reading, expectedRow) ->
            endSignal.value = null
            capability = DeviceAuthCapability.AVAILABLE
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
            assertEquals("$reading before", AppLockRowState.Off, readyOf(viewModel).lock)

            // The prompt reported no credential or hardware, and the controller's own check agreed.
            capability = reading
            endSignal.value = endOf(OpenOriginEndKind.NOT_AVAILABLE, OpenOriginPrompt.ENABLE, attemptId = 22L)
            advanceUntilIdle()

            val ready = readyOf(viewModel)
            assertEquals("$reading row", expectedRow, ready.lock)
            assertNull("$reading notice", ready.notice)
        }
    }

    @Test
    fun `a NOT_AVAILABLE end of a DISABLE attempt leaves a committed-on row On and shows no notice`() =
        runTest(testDispatcher) {
            controllerState.value = AppLockState.Unlocked
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

            capability = DeviceAuthCapability.NO_CREDENTIAL
            endSignal.value = endOf(OpenOriginEndKind.NOT_AVAILABLE, OpenOriginPrompt.DISABLE, attemptId = 23L)
            advanceUntilIdle()

            val ready = readyOf(viewModel)
            assertEquals(AppLockRowState.On, ready.lock)
            assertNull(ready.notice)
        }

    @Test
    fun `BACKGROUNDED shows nothing for any prompt`() = runTest(testDispatcher) {
        OpenOriginPrompt.values().forEach { prompt ->
            endSignal.value = null
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

            endSignal.value = endOf(OpenOriginEndKind.BACKGROUNDED, prompt, attemptId = 24L)
            advanceUntilIdle()

            assertNull("$prompt", readyOf(viewModel).notice)
        }
    }

    /**
     * The whole end -> notice table, written out from the plan ("End kind" mapping) and the B-1 binding. Rows are
     * (prompt, kind, notice). A lock notice follows the end's PROMPT: LOCK_ON only for ENABLE, LOCK_OFF only for DISABLE.
     */
    private val noticeTable: List<Triple<OpenOriginPrompt, OpenOriginEndKind, AppLockPrivacySectionNotice?>> = listOf(
        Triple(OpenOriginPrompt.ENABLE, OpenOriginEndKind.APPLIED, AppLockPrivacySectionNotice.LOCK_ON),
        Triple(OpenOriginPrompt.ENABLE, OpenOriginEndKind.NOT_COMMITTED, AppLockPrivacySectionNotice.SAVE_FAILED),
        Triple(OpenOriginPrompt.ENABLE, OpenOriginEndKind.CANCELLED, AppLockPrivacySectionNotice.LOCK_NOT_TURNED_ON),
        Triple(OpenOriginPrompt.ENABLE, OpenOriginEndKind.LOCKED_OUT, AppLockPrivacySectionNotice.TOO_MANY_ATTEMPTS),
        Triple(OpenOriginPrompt.ENABLE, OpenOriginEndKind.NOT_AVAILABLE, null),
        Triple(OpenOriginPrompt.ENABLE, OpenOriginEndKind.ERROR, AppLockPrivacySectionNotice.ENABLE_FAILED),
        Triple(OpenOriginPrompt.ENABLE, OpenOriginEndKind.HOST_LOST, null),
        Triple(OpenOriginPrompt.ENABLE, OpenOriginEndKind.BACKGROUNDED, null),
        Triple(OpenOriginPrompt.DISABLE, OpenOriginEndKind.APPLIED, AppLockPrivacySectionNotice.LOCK_OFF),
        Triple(OpenOriginPrompt.DISABLE, OpenOriginEndKind.NOT_COMMITTED, AppLockPrivacySectionNotice.SAVE_FAILED),
        Triple(OpenOriginPrompt.DISABLE, OpenOriginEndKind.CANCELLED, null),
        Triple(OpenOriginPrompt.DISABLE, OpenOriginEndKind.LOCKED_OUT, AppLockPrivacySectionNotice.TOO_MANY_ATTEMPTS),
        Triple(OpenOriginPrompt.DISABLE, OpenOriginEndKind.NOT_AVAILABLE, null),
        Triple(OpenOriginPrompt.DISABLE, OpenOriginEndKind.ERROR, null),
        Triple(OpenOriginPrompt.DISABLE, OpenOriginEndKind.HOST_LOST, null),
        Triple(OpenOriginPrompt.DISABLE, OpenOriginEndKind.BACKGROUNDED, null),
        Triple(OpenOriginPrompt.CHANGE, OpenOriginEndKind.APPLIED, null),
        Triple(OpenOriginPrompt.CHANGE, OpenOriginEndKind.NOT_COMMITTED, AppLockPrivacySectionNotice.SAVE_FAILED),
        Triple(OpenOriginPrompt.CHANGE, OpenOriginEndKind.CANCELLED, null),
        Triple(OpenOriginPrompt.CHANGE, OpenOriginEndKind.LOCKED_OUT, AppLockPrivacySectionNotice.TOO_MANY_ATTEMPTS),
        Triple(OpenOriginPrompt.CHANGE, OpenOriginEndKind.NOT_AVAILABLE, null),
        Triple(OpenOriginPrompt.CHANGE, OpenOriginEndKind.ERROR, null),
        Triple(OpenOriginPrompt.CHANGE, OpenOriginEndKind.HOST_LOST, null),
        Triple(OpenOriginPrompt.CHANGE, OpenOriginEndKind.BACKGROUNDED, null)
    )

    @Test
    fun `the end to notice table covers every prompt and kind pair exactly once`() {
        val pairs = noticeTable.map { it.first to it.second }
        assertEquals("no duplicate rows", pairs.size, pairs.toSet().size)
        assertEquals(
            "every pair is present",
            OpenOriginPrompt.values().size * OpenOriginEndKind.values().size,
            pairs.size
        )
    }

    @Test
    fun `every open-origin end maps to its notice per the table (plan End kind mapping, B-1)`() =
        runTest(testDispatcher) {
            noticeTable.forEach { (prompt, kind, expectedNotice) ->
                endSignal.value = null
                capability = DeviceAuthCapability.AVAILABLE
                val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

                endSignal.value = endOf(kind, prompt, attemptId = 30L)
                advanceUntilIdle()

                assertEquals("$prompt/$kind", expectedNotice, readyOf(viewModel).notice)
            }
        }

    @Test
    fun `B-1 an APPLIED ENABLE end shows LOCK_ON`() = runTest(testDispatcher) {
        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

        endSignal.value = endOf(OpenOriginEndKind.APPLIED, OpenOriginPrompt.ENABLE, attemptId = 31L)
        advanceUntilIdle()

        assertEquals(AppLockPrivacySectionNotice.LOCK_ON, readyOf(viewModel).notice)
    }

    @Test
    fun `B-1 an APPLIED DISABLE end shows LOCK_OFF, also with the negative attempt id of T19`() =
        runTest(testDispatcher) {
            listOf(32L, -32L).forEach { attemptId ->
                endSignal.value = null
                val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

                endSignal.value = endOf(OpenOriginEndKind.APPLIED, OpenOriginPrompt.DISABLE, attemptId)
                advanceUntilIdle()

                assertEquals("id=$attemptId", AppLockPrivacySectionNotice.LOCK_OFF, readyOf(viewModel).notice)
            }
        }

    @Test
    fun `B-1 an APPLIED CHANGE end (a timeout or review-save) shows no lock notice`() = runTest(testDispatcher) {
        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

        endSignal.value = endOf(OpenOriginEndKind.APPLIED, OpenOriginPrompt.CHANGE, attemptId = 33L)
        advanceUntilIdle()

        assertNull(readyOf(viewModel).notice)
    }

    @Test
    fun `B-1 a CHANGE end of any kind never produces a lock notice`() = runTest(testDispatcher) {
        val lockNotices = setOf(
            AppLockPrivacySectionNotice.LOCK_ON,
            AppLockPrivacySectionNotice.LOCK_OFF,
            AppLockPrivacySectionNotice.LOCK_NOT_TURNED_ON,
            AppLockPrivacySectionNotice.ENABLE_FAILED
        )
        OpenOriginEndKind.values().forEach { kind ->
            endSignal.value = null
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

            endSignal.value = endOf(kind, OpenOriginPrompt.CHANGE, attemptId = 34L)
            advanceUntilIdle()

            val notice = readyOf(viewModel).notice
            assertTrue("CHANGE/$kind produced $notice", notice !in lockNotices)
        }
    }

    @Test
    fun `B-1 LOCK_ON follows only an APPLIED ENABLE end and LOCK_OFF only an APPLIED DISABLE end, over every pair`() =
        runTest(testDispatcher) {
            OpenOriginPrompt.values().forEach { prompt ->
                OpenOriginEndKind.values().forEach { kind ->
                    endSignal.value = null
                    val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

                    endSignal.value = endOf(kind, prompt, attemptId = 35L)
                    advanceUntilIdle()

                    val notice = readyOf(viewModel).notice
                    assertEquals(
                        "$prompt/$kind LOCK_ON",
                        prompt == OpenOriginPrompt.ENABLE && kind == OpenOriginEndKind.APPLIED,
                        notice == AppLockPrivacySectionNotice.LOCK_ON
                    )
                    assertEquals(
                        "$prompt/$kind LOCK_OFF",
                        prompt == OpenOriginPrompt.DISABLE && kind == OpenOriginEndKind.APPLIED,
                        notice == AppLockPrivacySectionNotice.LOCK_OFF
                    )
                }
            }
        }

    @Test
    fun `B-2 a VM created after an end shows no notice`() = runTest(testDispatcher) {
        // The end was published before this ViewModel existed (the person left the screen and came back).
        endSignal.value = endOf(OpenOriginEndKind.CANCELLED, OpenOriginPrompt.ENABLE, attemptId = 40L)

        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

        assertNull("the replayed end is the baseline, not an event", readyOf(viewModel).notice)

        // Control: a later, different end is an event.
        endSignal.value = endOf(OpenOriginEndKind.ERROR, OpenOriginPrompt.ENABLE, attemptId = 41L)
        advanceUntilIdle()
        assertEquals(AppLockPrivacySectionNotice.ENABLE_FAILED, readyOf(viewModel).notice)
    }

    @Test
    fun `B-2 a VM created after an APPLIED end shows neither LOCK_ON nor LOCK_OFF`() = runTest(testDispatcher) {
        listOf(OpenOriginPrompt.ENABLE, OpenOriginPrompt.DISABLE).forEach { prompt ->
            endSignal.value = endOf(OpenOriginEndKind.APPLIED, prompt, attemptId = 42L)

            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))

            assertNull("$prompt", readyOf(viewModel).notice)
        }
    }

    @Test
    fun `B-2 a replayed end equal to the baseline shows nothing, even after a later end`() = runTest(testDispatcher) {
        val baseline = endOf(OpenOriginEndKind.APPLIED, OpenOriginPrompt.ENABLE, attemptId = 43L)
        endSignal.value = baseline
        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))
        assertNull("the collector's replay of the baseline", readyOf(viewModel).notice)

        // A later, different end is an event.
        endSignal.value = endOf(OpenOriginEndKind.CANCELLED, OpenOriginPrompt.ENABLE, attemptId = 44L)
        advanceUntilIdle()
        assertEquals(AppLockPrivacySectionNotice.LOCK_NOT_TURNED_ON, readyOf(viewModel).notice)

        // A value equal to the baseline is never an event: it must not turn the notice into LOCK_ON.
        endSignal.value = baseline
        advanceUntilIdle()
        assertEquals(AppLockPrivacySectionNotice.LOCK_NOT_TURNED_ON, readyOf(viewModel).notice)
    }

    @Test
    fun `B-2 a later different end shows its notice`() = runTest(testDispatcher) {
        endSignal.value = endOf(OpenOriginEndKind.CANCELLED, OpenOriginPrompt.ENABLE, attemptId = 45L)
        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
        assertNull(readyOf(viewModel).notice)

        endSignal.value = endOf(OpenOriginEndKind.LOCKED_OUT, OpenOriginPrompt.ENABLE, attemptId = 46L)
        advanceUntilIdle()

        assertEquals(AppLockPrivacySectionNotice.TOO_MANY_ATTEMPTS, readyOf(viewModel).notice)
    }

    @Test
    fun `B-2 a second VM created after a later end shows no notice either`() = runTest(testDispatcher) {
        val first = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
        endSignal.value = endOf(OpenOriginEndKind.ERROR, OpenOriginPrompt.ENABLE, attemptId = 47L)
        advanceUntilIdle()
        assertEquals(AppLockPrivacySectionNotice.ENABLE_FAILED, readyOf(first).notice)

        val second = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

        assertNull(readyOf(second).notice)
    }

    // ---------------------------------------------------------------------------------------------------------
    // S22-a.1: no diagnostics from the ViewModel (D7, F-5)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `the ViewModel emits no diagnostic on any lock path (requests, refusals, throws, every end)`() =
        runTest(testDispatcher) {
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

            // A refusal (T26) whose diagnostic is the controller's, once.
            every { operations.requestEnable() } returns SettingsRequestResult.REFUSED_UNAVAILABLE
            capability = DeviceAuthCapability.HW_UNAVAILABLE
            viewModel.onEnableConfirmed()
            advanceUntilIdle()
            capability = DeviceAuthCapability.AVAILABLE
            viewModel.refreshCapabilityHint()
            advanceUntilIdle()

            // A started request, then a throwing one.
            every { operations.requestEnable() } returns SettingsRequestResult.STARTED
            viewModel.onEnableConfirmed()
            every { operations.requestEnable() } throws IllegalStateException("boom")
            viewModel.onEnableConfirmed()
            advanceUntilIdle()

            // Every prompt x kind end.
            var attemptId = 100L
            OpenOriginPrompt.values().forEach { prompt ->
                OpenOriginEndKind.values().forEach { kind ->
                    endSignal.value = endOf(kind, prompt, attemptId++)
                    advanceUntilIdle()
                }
            }

            coVerify(exactly = 0) { emitter.emit(any()) }
            coVerify(exactly = 0) { emitter.emit(any(), any()) }
            confirmVerified(emitter)
        }

    @Test
    fun `diagnostics - zero emissions across every SettingsRequestResult for an enable and for a disable`() =
        runTest(testDispatcher) {
            SettingsRequestResult.values().forEach { result ->
                // Enable: the lock is committed off.
                every { operations.requestEnable() } returns result
                capability = DeviceAuthCapability.AVAILABLE
                val enableVm = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
                enableVm.onEnableConfirmed()
                advanceUntilIdle()

                // Disable: the lock is committed on (and once more with the capability unusable on the refusal).
                controllerState.value = AppLockState.Unlocked
                every { operations.requestDisable() } returns result
                listOf(DeviceAuthCapability.AVAILABLE, DeviceAuthCapability.HW_UNAVAILABLE).forEach { reading ->
                    capability = reading
                    val disableVm = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))
                    disableVm.onTurnOffRequested()
                    advanceUntilIdle()
                }
                controllerState.value = AppLockState.Disabled
            }

            coVerify(exactly = 0) { emitter.emit(any()) }
            coVerify(exactly = 0) { emitter.emit(any(), any()) }
            confirmVerified(emitter)
        }

    @Test
    fun `diagnostics - zero emissions across every OpenOriginEndKind and OpenOriginPrompt end`() =
        runTest(testDispatcher) {
            var attemptId = 500L
            OpenOriginPrompt.values().forEach { prompt ->
                OpenOriginEndKind.values().forEach { kind ->
                    listOf(off, lockOn).forEach { settings ->
                        endSignal.value = null
                        val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(settings)))

                        endSignal.value = endOf(kind, prompt, attemptId++)
                        advanceUntilIdle()

                        // The end was observed (a Ready section), and the section itself emitted nothing.
                        readyOf(viewModel)
                    }
                }
            }

            coVerify(exactly = 0) { emitter.emit(any()) }
            coVerify(exactly = 0) { emitter.emit(any(), any()) }
            confirmVerified(emitter)
        }

    // ---------------------------------------------------------------------------------------------------------
    // S22-a.1.1 (privacy guardian conditions): discreet no-op while forced, INV-46, AE-65, B-3
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `defence in depth - setDiscreetNotifications is a no-op while discreetForced, with no request and no state change`() =
        runTest(testDispatcher) {
            controllerState.value = AppLockState.Unlocked
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(lockOn))
            val viewModel = openSection(repository)
            val before = viewModel.uiState.value
            assertTrue("the row is forced", readyOf(viewModel).discreetForced)

            viewModel.setDiscreetNotifications(false)
            viewModel.setDiscreetNotifications(true)
            advanceUntilIdle()

            coVerify(exactly = 0) { operations.requestDiscreetChange(any()) }
            assertEquals(before, viewModel.uiState.value)
            assertEquals("no resubscribe", 1, repository.observeCalls)
            assertFalse(readyOf(viewModel).isWriting)
            assertNull(readyOf(viewModel).notice)
        }

    @Test
    fun `defence in depth - setDiscreetNotifications is also a no-op while the lock is paused (Inert) and committed on`() =
        runTest(testDispatcher) {
            controllerState.value = AppLockState.Inert(InertReason.NO_CREDENTIAL)
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(lockOn)))
            val before = viewModel.uiState.value

            viewModel.setDiscreetNotifications(false)
            advanceUntilIdle()

            coVerify(exactly = 0) { operations.requestDiscreetChange(any()) }
            assertEquals(before, viewModel.uiState.value)
        }

    @Test
    fun `defence in depth control - setDiscreetNotifications reaches the operations once the lock is committed off`() =
        runTest(testDispatcher) {
            coEvery { operations.requestDiscreetChange(true) } returns PlainSettingResult.COMMITTED
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))
            assertFalse(readyOf(viewModel).discreetForced)

            viewModel.setDiscreetNotifications(true)
            advanceUntilIdle()

            coVerify(exactly = 1) { operations.requestDiscreetChange(true) }
        }

    @Test
    fun `INV-46 after a STARTED enable the lock row and the discreet value stay unchanged until the store emits`() =
        runTest(testDispatcher) {
            every { operations.requestEnable() } returns SettingsRequestResult.STARTED
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(off))
            val viewModel = openSection(repository)
            val offReady = readyOf(viewModel)

            viewModel.onEnableConfirmed()
            advanceUntilIdle()
            assertEquals("after STARTED", offReady, readyOf(viewModel))

            controllerState.value = AppLockState.Authenticating(60L, AuthPurpose.ENABLE)
            advanceUntilIdle()
            val waiting = readyOf(viewModel)
            assertEquals(AppLockRowState.Waiting(AuthPurpose.ENABLE, committedOn = false), waiting.lock)
            assertFalse("discreet value while the prompt is open", waiting.discreetOn)
            assertFalse("discreet not yet forced", waiting.discreetForced)

            // The end arrives, the store has not emitted: the committed value is still the old one.
            controllerState.value = AppLockState.Disabled
            endSignal.value = endOf(OpenOriginEndKind.APPLIED, OpenOriginPrompt.ENABLE, attemptId = 60L)
            advanceUntilIdle()
            val beforeEmit = readyOf(viewModel)
            assertEquals(AppLockRowState.Off, beforeEmit.lock)
            assertFalse(beforeEmit.discreetOn)
            assertFalse(beforeEmit.discreetForced)

            repository.state.value = AppLockSettingsLoadState.Loaded(lockOn)
            advanceUntilIdle()
            val afterEmit = readyOf(viewModel)
            assertEquals(AppLockRowState.On, afterEmit.lock)
            assertTrue(afterEmit.discreetOn)
            assertTrue(afterEmit.discreetForced)
        }

    @Test
    fun `INV-46 after a STARTED disable the lock row and the discreet value stay unchanged until the store emits`() =
        runTest(testDispatcher) {
            every { operations.requestDisable() } returns SettingsRequestResult.STARTED
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(lockOn))
            controllerState.value = AppLockState.Unlocked
            val viewModel = openSection(repository)
            val onReady = readyOf(viewModel)
            assertEquals(AppLockRowState.On, onReady.lock)
            assertTrue(onReady.discreetOn)

            viewModel.onTurnOffRequested()
            advanceUntilIdle()
            assertEquals("after STARTED", onReady, readyOf(viewModel))

            controllerState.value = AppLockState.Authenticating(61L, AuthPurpose.CONFIRM_CHANGE)
            advanceUntilIdle()
            val waiting = readyOf(viewModel)
            assertEquals(AppLockRowState.Waiting(AuthPurpose.CONFIRM_CHANGE, committedOn = true), waiting.lock)
            assertTrue("discreet value while the prompt is open", waiting.discreetOn)
            assertTrue(waiting.discreetForced)

            // The end arrives, the store has not emitted: still on, discreet still forced.
            controllerState.value = AppLockState.Disabled
            endSignal.value = endOf(OpenOriginEndKind.APPLIED, OpenOriginPrompt.DISABLE, attemptId = 61L)
            advanceUntilIdle()
            val beforeEmit = readyOf(viewModel)
            assertEquals(AppLockRowState.On, beforeEmit.lock)
            assertTrue(beforeEmit.discreetOn)
            assertTrue(beforeEmit.discreetForced)

            repository.state.value = AppLockSettingsLoadState.Loaded(
                AppLockPrivacySettings(discreetNotifications = false, appLockEnabled = false)
            )
            advanceUntilIdle()
            val afterEmit = readyOf(viewModel)
            assertEquals(AppLockRowState.Off, afterEmit.lock)
            assertFalse(afterEmit.discreetForced)
        }

    @Test
    fun `AE-65 after the lock turns off the discreet row shows the stored value again and no write is recorded`() =
        runTest(testDispatcher) {
            controllerState.value = AppLockState.Unlocked
            listOf(false, true).forEach { stored ->
                val repository = FakeRepository(
                    AppLockSettingsLoadState.Loaded(
                        AppLockPrivacySettings(discreetNotifications = stored, appLockEnabled = true)
                    )
                )
                val viewModel = openSection(repository)
                assertTrue("stored=$stored forced display", readyOf(viewModel).discreetOn)
                assertTrue("stored=$stored forced", readyOf(viewModel).discreetForced)

                repository.state.value = AppLockSettingsLoadState.Loaded(
                    AppLockPrivacySettings(discreetNotifications = stored, appLockEnabled = false)
                )
                controllerState.value = AppLockState.Disabled
                advanceUntilIdle()
                controllerState.value = AppLockState.Unlocked

                val ready = readyOf(viewModel)
                assertEquals("stored=$stored shows the stored value", stored, ready.discreetOn)
                assertFalse("stored=$stored not forced", ready.discreetForced)
                assertEquals("stored=$stored", AppLockRowState.Off, ready.lock)
            }

            // The turn-off never wrote the discreet value (the restore is a display rule, AE-65).
            coVerify(exactly = 0) { operations.requestDiscreetChange(any()) }
            coVerify(exactly = 0) { operations.requestReviewSave(any()) }
        }

    @Test
    fun `B-3 a STARTED enable followed by a Disabled state with no end shows Off and no notice`() =
        runTest(testDispatcher) {
            every { operations.requestEnable() } returns SettingsRequestResult.STARTED
            val viewModel = openSection(FakeRepository(AppLockSettingsLoadState.Loaded(off)))

            viewModel.onEnableConfirmed()
            controllerState.value = AppLockState.Authenticating(62L, AuthPurpose.ENABLE)
            advanceUntilIdle()
            assertTrue(readyOf(viewModel).lock is AppLockRowState.Waiting)

            // The controller drops the attempt without publishing an end: the row must not wait for one.
            controllerState.value = AppLockState.Disabled
            advanceUntilIdle()

            assertEquals(readyLockOff(false, false, null), viewModel.uiState.value)
            assertEquals(AppLockRowState.Off, readyOf(viewModel).lock)
        }

    @Test
    fun `B-3 a STARTED disable followed by a Disabled state with no end shows Off once the store says off`() =
        runTest(testDispatcher) {
            every { operations.requestDisable() } returns SettingsRequestResult.STARTED
            val repository = FakeRepository(AppLockSettingsLoadState.Loaded(lockOn))
            controllerState.value = AppLockState.Unlocked
            val viewModel = openSection(repository)

            viewModel.onTurnOffRequested()
            controllerState.value = AppLockState.Authenticating(63L, AuthPurpose.CONFIRM_CHANGE)
            advanceUntilIdle()
            assertTrue(readyOf(viewModel).lock is AppLockRowState.Waiting)

            repository.state.value = AppLockSettingsLoadState.Loaded(
                AppLockPrivacySettings(discreetNotifications = false, appLockEnabled = false)
            )
            controllerState.value = AppLockState.Disabled
            advanceUntilIdle()

            assertEquals(readyLockOff(false, false, null), viewModel.uiState.value)
        }
}
