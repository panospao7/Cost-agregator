package com.yourname.expensetracker.ui.screens.privacysettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.diagnostics.DiagnosticThrowableSummary
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingField
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsOperations
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticMetadata
import com.yourname.expensetracker.domain.privacy.applock.PlainSettingResult
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.applock.ReviewSaveResult
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** Bounded, content-free notice shown under the section after a write attempt. */
enum class AppLockPrivacySectionNotice {
    /** The write failed; the previous committed value is still in force (INV-46, AE-105). */
    SAVE_FAILED,

    /** A review-save committed (1a record). */
    REVIEW_SAVED
}

/**
 * What the "App lock & privacy" section renders (1a: the discreet row only).
 *
 * Every state is derived from the committed store state (INV-46). There is no optimistic value, and
 * an unknown state never renders as Off (INV-2): [Loading] has no switch at all and the unreadable
 * states render the fail-closed effective value (discreet on).
 */
sealed interface AppLockPrivacySectionState {

    /** First read in flight. No checked state is asserted for any row. */
    data object Loading : AppLockPrivacySectionState

    /** Readable store. [discreetOn] is the committed stored value; [isWriting] disables the row. */
    data class Ready(
        val discreetOn: Boolean,
        val isWriting: Boolean,
        val notice: AppLockPrivacySectionNotice?
    ) : AppLockPrivacySectionState

    /**
     * `IO_ERROR` or `TIMEOUT`. Every per-field write is rejected (INV-44); recovery is a re-read
     * (INV-47). The row shows the fail-closed effective value (discreet on), disabled.
     */
    data object TransientUnreadable : AppLockPrivacySectionState

    /**
     * `CORRUPTED` or `INVALID_VALUE`. The review card is shown; the only accepted write is one
     * review-save of the whole record (INV-44). [discreetChoice] starts at the fail-closed value (on).
     */
    data class NeedsReview(
        val discreetChoice: Boolean,
        val isSaving: Boolean,
        val notice: AppLockPrivacySectionNotice?
    ) : AppLockPrivacySectionState
}

/**
 * ViewModel of the 1a "App lock & privacy" section (S12). [PrivacySettingsViewModel] is untouched.
 *
 * Legal path: the only writers are [AppLockSettingsOperations.requestDiscreetChange] (WRITE 5: the forced-aware
 * per-field discreet write plus the post-commit re-post sweep, serialised inside the operations; S20-d) and
 * [AppLockSettingsOperations.requestReviewSave] (INV-44, INV-45; S20-e). Both end in the settings repository (INV-1).
 * The section never holds an optimistic value: [uiState] is derived from the repository's committed load state
 * (INV-46).
 *
 * The review-save diagnostics (`LOCK_SETTINGS_REVIEW_SAVED`, `LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE)`) and the
 * post-commit discreet sweep are owned by the controller and the operations (D7), once each. This ViewModel
 * emits neither.
 *
 * Write discipline (S9b carry-forwards):
 * - Serialised: both writes are serialised INSIDE [AppLockSettingsOperations] (the singleton, domain-owned
 *   write serializer), so this ViewModel must NOT wrap them in a serializer: the lock is non-reentrant and a wrap
 *   would deadlock.
 * - Survives screen exit: writes are launched in the [ApplicationScope] and run inside
 *   `withContext(NonCancellable)`, off Main (the scope uses the IO dispatcher). A committed ON therefore
 *   cannot miss its re-post sweep because the screen was left. `NonCancellable` defers cancellation; it
 *   does not swallow it, and `CancellationException` is rethrown everywhere it is caught.
 */
@HiltViewModel
class AppLockPrivacySettingsViewModel @Inject constructor(
    private val repository: AppLockSettingsRepository,
    private val operations: AppLockSettingsOperations,
    private val controller: AppLockController,
    private val privacyDiagnostics: PrivacyDiagnosticsEmitter,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    /** Latest committed load state. null = the first read has not arrived yet (never Off). */
    private val loadState = MutableStateFlow<AppLockSettingsLoadState?>(null)
    private val isWriting = MutableStateFlow(false)
    private val notice = MutableStateFlow<AppLockPrivacySectionNotice?>(null)
    private val reviewChoice = MutableStateFlow(FAIL_CLOSED_DISCREET)

    /**
     * A review-save committed but the observer has not yet shown a readable state. While true the review
     * card stays busy, so a second tap cannot issue a duplicate review-save. Reset on every move into the
     * review state, so it can never stay stale.
     */
    private val reviewCommitted = MutableStateFlow(false)

    /**
     * VM-internal, never rendered and never exposed (S20-e review-save notice, owner-approvals 64).
     *
     * Armed when [AppLockSettingsOperations.requestReviewSave] returns STARTED (the CHANGE prompt is open, nothing is
     * committed yet). It is consumed exactly once, by the load-state observer, when the observed state moves from the
     * review (persistent-unreadable) category to a readable one: that is the confirmed save committing, and only then
     * is REVIEW_SAVED shown. A commit that reaches the observer without this flag armed shows no notice.
     *
     * Clearing rule (no controller observation, no new API; the prompt outcome is not observable from here):
     * - consumed by the commit (above), so a second emission can never show the notice again;
     * - replaced by every later review-save result, except ALREADY_IN_FLIGHT (the confirmed apply may still be
     *   writing):
     *   STARTED re-arms it, COMMITTED, FAILED, REJECTED_CAPABILITY, NOT_IN_REVIEW and NOT_IN_ORIGIN clear it. A user
     *   who cancelled the prompt and saves again therefore never carries a stale flag into the next outcome;
     * - cleared by [retry] (the user re-reads) and when the observed state leaves the review category for anything
     *   but a readable state (loading or transient-unreadable);
     * - dies with the ViewModel.
     * A prompt that was cancelled and followed by no further action leaves the flag armed. The store stays
     * persistently unreadable then, so nothing is shown; the only effect is that an unrelated external recovery of the
     * store before the next action would show the notice. That is the accepted residual of choosing the simplest rule.
     * Atomic: it is armed from the application scope (IO) and consumed from the observer (Main).
     */
    private val pendingReviewSave = AtomicBoolean(false)

    @Volatile
    private var observeJob: Job? = null

    val uiState: StateFlow<AppLockPrivacySectionState> = combine(
        loadState,
        isWriting,
        notice,
        reviewChoice,
        reviewCommitted
    ) { state, writing, currentNotice, choice, committed ->
        when (state) {
            null -> AppLockPrivacySectionState.Loading
            is AppLockSettingsLoadState.Loaded ->
                AppLockPrivacySectionState.Ready(state.settings.discreetNotifications, writing, currentNotice)
            is AppLockSettingsLoadState.FirstRunDefault ->
                AppLockPrivacySectionState.Ready(state.settings.discreetNotifications, writing, currentNotice)
            is AppLockSettingsLoadState.Unreadable ->
                if (state.cause.isPersistent) {
                    AppLockPrivacySectionState.NeedsReview(choice, writing || committed, currentNotice)
                } else {
                    AppLockPrivacySectionState.TransientUnreadable
                }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppLockPrivacySectionState.Loading)

    init {
        startObserving()
    }

    /**
     * Per-field discreet write. Ignored unless the section is [AppLockPrivacySectionState.Ready] and idle
     * (INV-44: no per-field write while unreadable; the repository rejects it as well).
     */
    fun setDiscreetNotifications(enabled: Boolean) {
        val current = uiState.value
        if (current !is AppLockPrivacySectionState.Ready || current.isWriting) return
        if (!beginWrite()) return
        appScope.launch {
            try {
                // Whole sequence (before-read, write, sweep) is uninterruptible. It is serialised inside the
                // operations, so there is deliberately NO outer serializer here (non-reentrant: it would deadlock).
                withContext(NonCancellable) {
                    handlePlain(operations.requestDiscreetChange(enabled))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Discreet write failed: class=%s", DiagnosticThrowableSummary.className(e))
                notice.value = AppLockPrivacySectionNotice.SAVE_FAILED
                recordPersistFailed(AppLockSettingField.DISCREET)
            } finally {
                isWriting.value = false
            }
        }
    }

    /** Sets the review card's discreet choice. Nothing is written until [saveReview]. */
    fun setReviewDiscreetChoice(enabled: Boolean) {
        val current = uiState.value
        if (current !is AppLockPrivacySectionState.NeedsReview || current.isSaving) return
        reviewChoice.value = enabled
    }

    /**
     * Review-save of the whole record through [AppLockSettingsOperations.requestReviewSave] (INV-44, INV-45, S20-e).
     * Until the full settings UI (S22) the record is the D2 record: lock off, [AutoLockTimeout.IMMEDIATE], discreet =
     * the user's choice, secure screen on (the fail-closed value). It has no lock to enable, so the capability
     * refusal is unreachable here. Ignored unless the review card is showing and idle.
     */
    fun saveReview() {
        val current = uiState.value
        if (current !is AppLockPrivacySectionState.NeedsReview || current.isSaving) return
        val record = AppLockPrivacySettings(
            discreetNotifications = reviewChoice.value,
            appLockEnabled = false,
            autoLockTimeout = AutoLockTimeout.IMMEDIATE,
            secureScreen = true
        )
        if (!beginWrite()) return
        appScope.launch {
            try {
                // Serialised inside the operations: deliberately NO outer serializer (non-reentrant: it would deadlock).
                withContext(NonCancellable) {
                    handleReviewResult(operations.requestReviewSave(record))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The diagnostics of a review-save are owned by the controller and the operations (D7).
                Timber.w("Review save failed: class=%s", DiagnosticThrowableSummary.className(e))
                notice.value = AppLockPrivacySectionNotice.SAVE_FAILED
            } finally {
                isWriting.value = false
            }
        }
    }

    /**
     * "Try again" on the transient unreadable variant: shows Checking... and re-reads (INV-47). The controller's own
     * view is re-read as well, through its settings re-read request (it does nothing unless that view is
     * transient-unreadable).
     */
    fun retry() {
        pendingReviewSave.set(false)
        applyLoadState(null)
        startObserving()
        controller.requestSettingsReread()
    }

    /**
     * Foreground hook (INV-47): re-reads only while the state is transient-unreadable. Any other state
     * is left alone, so a normal resume never flashes the loading state.
     */
    fun refreshIfTransient() {
        val state = loadState.value
        if (state is AppLockSettingsLoadState.Unreadable && !state.cause.isPersistent) retry()
    }

    private fun beginWrite(): Boolean {
        if (!isWriting.compareAndSet(expect = false, update = true)) return false
        notice.value = null
        return true
    }

    /**
     * Exhaustive over [PlainSettingResult] (no else: a new value must be mapped here). The failure diagnostic and
     * the re-post sweep are owned by the operations and the controller, not by this ViewModel.
     */
    private fun handlePlain(result: PlainSettingResult) {
        when (result) {
            // The committed value arrives through the store observer (INV-46). A failed sweep is
            // silent by design (spec 4.6) and already recorded by the trigger.
            PlainSettingResult.COMMITTED -> Unit
            // FAILED: the commit failed or the store was unreadable; nothing changed (AE-105). The tap must not be
            // silently ignored (owner decision 2026-10-24), so show the save-failed notice and re-read so the
            // observer reports the true state. REJECTED_FORCED (INV-48: the lock became on, the value is
            // forced) and ALREADY_IN_FLIGHT (INV-54: a lock apply is running) wrote nothing; the same notice
            // and re-read apply. The notice survives a re-read that lands in the same readable category;
            // applyLoadState clears it on a category change, and beginWrite clears it on the next write.
            PlainSettingResult.FAILED,
            PlainSettingResult.REJECTED_FORCED,
            PlainSettingResult.ALREADY_IN_FLIGHT -> {
                notice.value = AppLockPrivacySectionNotice.SAVE_FAILED
                resubscribeOnMain()
            }
        }
    }

    /**
     * Exhaustive over [ReviewSaveResult] (no else: a new value must be mapped here). The review-save diagnostics and
     * the post-commit discreet sweep are owned by the controller and the operations (D7), not by this ViewModel.
     */
    private fun handleReviewResult(result: ReviewSaveResult) {
        // Every result except ALREADY_IN_FLIGHT (the confirmed apply may still be writing) replaces the pending flag.
        if (result != ReviewSaveResult.ALREADY_IN_FLIGHT) pendingReviewSave.set(result == ReviewSaveResult.STARTED)
        when (result) {
            ReviewSaveResult.COMMITTED -> {
                markReviewCommitted()
                notice.value = AppLockPrivacySectionNotice.REVIEW_SAVED
            }
            // STARTED: a weakening record confirms through the CHANGE prompt first. Nothing is committed yet and no
            // notice is shown now; the flag armed above makes the observer show REVIEW_SAVED once, when it reports
            // the commit (INV-46).
            ReviewSaveResult.STARTED -> Unit
            // FAILED: the write did not commit; the store and the sentinel are unchanged (AE-89).
            ReviewSaveResult.FAILED -> notice.value = AppLockPrivacySectionNotice.SAVE_FAILED
            // Nothing was written. Show the error and re-read so the observer reports the true state.
            // REJECTED_CAPABILITY is unreachable with the D2 record (lock off) and is mapped fail-closed.
            ReviewSaveResult.REJECTED_CAPABILITY,
            ReviewSaveResult.NOT_IN_REVIEW,
            ReviewSaveResult.NOT_IN_ORIGIN,
            ReviewSaveResult.ALREADY_IN_FLIGHT -> {
                notice.value = AppLockPrivacySectionNotice.SAVE_FAILED
                resubscribeOnMain()
            }
        }
    }

    /** Shared by both commit paths (COMMITTED result, and STARTED then the observed commit). */
    private fun markReviewCommitted() {
        // The next review (if the store is corrupted again) starts from the fail-closed choice.
        reviewChoice.value = FAIL_CLOSED_DISCREET
        // Keep the card busy until the observer shows a readable state: no duplicate review-save.
        reviewCommitted.value = true
    }

    private suspend fun recordPersistFailed(field: AppLockSettingField) {
        emitSafely {
            privacyDiagnostics.emit(
                PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED,
                PrivacyDiagnosticMetadata.builder().put(PrivacyDiagnosticKey.FIELD, field).build()
            )
        }
    }

    /** A diagnostics failure must never turn a committed write into a reported failure. */
    private suspend fun emitSafely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Privacy diagnostic emit failed: class=%s", DiagnosticThrowableSummary.className(e))
        }
    }

    /** [startObserving] mutates [observeJob]; writes run off Main, so hop to Main before calling it. */
    private fun resubscribeOnMain() {
        viewModelScope.launch { startObserving() }
    }

    /**
     * Single entry for every observed (or reset) load state. Applies the derived-state rules:
     * - the notice is cleared when the load-state category changes, except that a review-saved notice
     *   survives the move to a readable state it announces;
     * - moving into the review state from any other category resets the review choice to the fail-closed
     *   value and the post-commit busy flag;
     * - a move from the review category to a readable one while [pendingReviewSave] is armed is the confirmed
     *   review-save committing: REVIEW_SAVED is shown once and the flag is consumed. Leaving the review category for
     *   loading or transient-unreadable clears the flag without a notice.
     */
    private fun applyLoadState(new: AppLockSettingsLoadState?) {
        val oldCategory = categoryOf(loadState.value)
        val newCategory = categoryOf(new)
        if (newCategory == LoadCategory.REVIEW && oldCategory != LoadCategory.REVIEW) {
            reviewChoice.value = FAIL_CLOSED_DISCREET
            reviewCommitted.value = false
        }
        if (oldCategory == LoadCategory.REVIEW && newCategory != LoadCategory.REVIEW) {
            val committed = newCategory == LoadCategory.READY && pendingReviewSave.compareAndSet(true, false)
            if (committed) {
                markReviewCommitted()
                notice.value = AppLockPrivacySectionNotice.REVIEW_SAVED
            } else if (newCategory != LoadCategory.READY) {
                pendingReviewSave.set(false)
            }
        }
        if (newCategory != oldCategory) {
            val keepReviewSaved = newCategory == LoadCategory.READY &&
                notice.value == AppLockPrivacySectionNotice.REVIEW_SAVED
            if (!keepReviewSaved) notice.value = null
        }
        loadState.value = new
    }

    private fun categoryOf(state: AppLockSettingsLoadState?): LoadCategory = when (state) {
        null -> LoadCategory.LOADING
        is AppLockSettingsLoadState.Loaded, is AppLockSettingsLoadState.FirstRunDefault -> LoadCategory.READY
        is AppLockSettingsLoadState.Unreadable ->
            if (state.cause.isPersistent) LoadCategory.REVIEW else LoadCategory.TRANSIENT
    }

    private enum class LoadCategory { LOADING, READY, TRANSIENT, REVIEW }

    private fun startObserving() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            try {
                repository.observeLoadState().collect { applyLoadState(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fail closed: an unexpected observer failure is a transient unreadable state, never Off.
                Timber.w("App-lock settings observer failed: class=%s", DiagnosticThrowableSummary.className(e))
                applyLoadState(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR))
            }
        }
    }

    private companion object {
        /** Fail-closed effective value of the discreet flag (INV-2): on. */
        val FAIL_CLOSED_DISCREET: Boolean = AppLockPrivacySettings.FAIL_CLOSED.discreetNotifications
    }
}
