package com.yourname.expensetracker.ui.screens.privacysettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.diagnostics.DiagnosticThrowableSummary
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingField
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteResult
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteSerializer
import com.yourname.expensetracker.domain.privacy.applock.DiscreetRepostTrigger
import com.yourname.expensetracker.domain.privacy.applock.EffectivePrivacyValues
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticMetadata
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import com.yourname.expensetracker.domain.privacy.applock.UpdateDiscreetNotificationsResult
import com.yourname.expensetracker.domain.privacy.applock.UpdateDiscreetNotificationsUseCase
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
 * Legal path: the only writers are [UpdateDiscreetNotificationsUseCase] (per-field discreet write plus
 * the post-commit re-post sweep) and [AppLockSettingsRepository.reviewSave] (INV-44). Both end in the
 * settings repository (INV-1). The section never holds an optimistic value: [uiState] is derived from
 * the repository's committed load state (INV-46).
 *
 * A committed review-save also runs the discreet sweep trigger (before value "was off"), so a review that
 * saves discreet on re-posts the shade in place like the per-field path.
 *
 * Write discipline (S9b carry-forwards):
 * - Serialised: every write runs under the singleton, domain-owned [AppLockSettingsWriteSerializer], so two taps,
 *   or a write started by a previous instance of this ViewModel, can never interleave their before-read, write
 *   and sweep. The same instance serialises the app-lock operations (S18 ii-a, F4). The wrappers here (WRITE 1 and
 *   WRITE 2) never call the operations, so the non-reentrant lock cannot deadlock.
 * - Survives screen exit: writes are launched in the [ApplicationScope] and run inside
 *   `withContext(NonCancellable)`, off Main (the scope uses the IO dispatcher). A committed ON therefore
 *   cannot miss its re-post sweep because the screen was left. `NonCancellable` defers cancellation; it
 *   does not swallow it, and `CancellationException` is rethrown everywhere it is caught.
 */
@HiltViewModel
class AppLockPrivacySettingsViewModel @Inject constructor(
    private val repository: AppLockSettingsRepository,
    private val updateDiscreetNotifications: UpdateDiscreetNotificationsUseCase,
    private val privacyDiagnostics: PrivacyDiagnosticsEmitter,
    private val writeSerializer: AppLockSettingsWriteSerializer,
    private val discreetRepostTrigger: DiscreetRepostTrigger,
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
                // Whole sequence (before-read, write, sweep) is uninterruptible and serialised.
                withContext(NonCancellable) {
                    writeSerializer.withExclusiveWrite {
                        handleDiscreetResult(updateDiscreetNotifications(enabled))
                    }
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
     * Review-save of the whole 1a record (INV-44). 1a has no lock, so no authentication applies
     * (INV-45, 1a note). Ignored unless the review card is showing and idle.
     */
    fun saveReview() {
        val current = uiState.value
        if (current !is AppLockPrivacySectionState.NeedsReview || current.isSaving) return
        val record = AppLockPrivacySettings(discreetNotifications = reviewChoice.value)
        if (!beginWrite()) return
        appScope.launch {
            try {
                withContext(NonCancellable) {
                    writeSerializer.withExclusiveWrite { handleReviewResult(repository.reviewSave(record)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Review save failed: class=%s", DiagnosticThrowableSummary.className(e))
                notice.value = AppLockPrivacySectionNotice.SAVE_FAILED
                recordPersistFailed(AppLockSettingField.REVIEW_SAVE)
            } finally {
                isWriting.value = false
            }
        }
    }

    /** "Try again" on the transient unreadable variant: shows Checking... and re-reads (INV-47). */
    fun retry() {
        applyLoadState(null)
        startObserving()
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

    private suspend fun handleDiscreetResult(result: UpdateDiscreetNotificationsResult) {
        when (result) {
            // The committed value arrives through the store observer (INV-46). A failed sweep is
            // silent by design (spec 4.6) and already recorded by the trigger.
            is UpdateDiscreetNotificationsResult.Committed -> Unit
            // The store became unreadable under us and nothing was written (owner decision 2026-10-24:
            // the tap must not be silently ignored). Show the save-failed notice and re-read so the
            // observer reports the true state. No diagnostic: nothing was written. The notice survives
            // a re-read that lands in the same readable category; applyLoadState clears it on a category
            // change, and beginWrite clears it on the next write.
            is UpdateDiscreetNotificationsResult.Rejected -> {
                notice.value = AppLockPrivacySectionNotice.SAVE_FAILED
                resubscribeOnMain()
            }
            // AE-105: the committed value is unchanged; the use case recorded the diagnostic.
            is UpdateDiscreetNotificationsResult.Failed ->
                notice.value = AppLockPrivacySectionNotice.SAVE_FAILED
        }
    }

    private suspend fun handleReviewResult(result: AppLockSettingsWriteResult) {
        when (result) {
            is AppLockSettingsWriteResult.Committed -> {
                // The next review (if the store is corrupted again) starts from the fail-closed choice.
                reviewChoice.value = FAIL_CLOSED_DISCREET
                // Keep the card busy until the observer shows a readable state: no duplicate review-save.
                reviewCommitted.value = true
                notice.value = AppLockPrivacySectionNotice.REVIEW_SAVED
                emitSafely { privacyDiagnostics.emit(PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED) }
                sweepAfterCommittedReview(result.settings)
            }
            is AppLockSettingsWriteResult.Failed -> {
                notice.value = AppLockPrivacySectionNotice.SAVE_FAILED
                recordPersistFailed(result.field)
            }
            is AppLockSettingsWriteResult.Rejected -> {
                // Reachable: the repository answers Rejected(UNREADABLE) when the read after a failed
                // commit is transient. Nothing was written. Keep the error and the diagnostic, and
                // re-read so the observer reports the true state.
                notice.value = AppLockPrivacySectionNotice.SAVE_FAILED
                recordPersistFailed(AppLockSettingField.REVIEW_SAVE)
                resubscribeOnMain()
            }
        }
    }

    /**
     * A committed review-save can set discreet on, and the review path has no trustworthy before value
     * (the store was unreadable), so the before value is "was off": a committed on sweeps, a committed
     * off does not. Runs inside the same serialised, uninterruptible block as the write. The trigger
     * records its own failures; a failure here never turns the committed save into a reported failure.
     */
    private suspend fun sweepAfterCommittedReview(committed: AppLockPrivacySettings) {
        try {
            discreetRepostTrigger.onCommittedWrite(
                wasEffectiveDiscreet = false,
                isEffectiveDiscreet = EffectivePrivacyValues.effectiveDiscreet(
                    AppLockSettingsLoadState.Loaded(committed)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Review-save sweep trigger failed: class=%s", DiagnosticThrowableSummary.className(e))
        }
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
     *   value and the post-commit busy flag.
     */
    private fun applyLoadState(new: AppLockSettingsLoadState?) {
        val oldCategory = categoryOf(loadState.value)
        val newCategory = categoryOf(new)
        if (newCategory == LoadCategory.REVIEW && oldCategory != LoadCategory.REVIEW) {
            reviewChoice.value = FAIL_CLOSED_DISCREET
            reviewCommitted.value = false
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
