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
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.AuthPurpose
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapability
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapabilityProvider
import com.yourname.expensetracker.domain.privacy.applock.InertReason
import com.yourname.expensetracker.domain.privacy.applock.OpenOriginEnd
import com.yourname.expensetracker.domain.privacy.applock.OpenOriginEndKind
import com.yourname.expensetracker.domain.privacy.applock.OpenOriginPrompt
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticMetadata
import com.yourname.expensetracker.domain.privacy.applock.PlainSettingResult
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.applock.ReviewSaveResult
import com.yourname.expensetracker.domain.privacy.applock.SettingsRequestResult
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import com.yourname.expensetracker.domain.privacy.applock.TimeoutChangeResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
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

/** Bounded, content-free notice shown under the section after a write attempt (inline, D-S22-3). */
enum class AppLockPrivacySectionNotice {
    /** The write failed; the previous committed value is still in force (INV-46, AE-105). */
    SAVE_FAILED,

    /** A review-save committed (1a record). */
    REVIEW_SAVED,

    /** An ENABLE prompt attempt ended with its change applied (B-1: only an ENABLE end produces it). */
    LOCK_ON,

    /** A DISABLE attempt ended with its change applied, including the T19 no-prompt disable (B-1). */
    LOCK_OFF,

    /** The person dismissed the ENABLE prompt: "App lock was not turned on." (AE-36). */
    LOCK_NOT_TURNED_ON,

    /** The platform locked authentication out: "Too many attempts..." (ux 4.3). */
    TOO_MANY_ATTEMPTS,

    /** The ENABLE prompt ended in a bounded error, or never launched: "Couldn't turn on app lock." (ux 4.3). */
    ENABLE_FAILED
}

/** Why the lock row cannot be switched on (the explanation variant of the unavailable row, ux 2.1). */
enum class AppLockUnavailableReason {
    /** The capability hint says the OS is older than Android 11 (AE-34). */
    OS,

    /** The capability hint says there is no screen lock (AE-35). */
    NOT_SECURE,

    /** A request or a prompt found the device not usable right now; "Try again" re-reads (AE-93, T26). */
    NOW
}

/**
 * What the lock row renders (S22-a.1). The checked value comes from the COMMITTED `appLockEnabled` only (INV-46):
 * there is no optimistic state. [Waiting] is derived from the controller state being `Authenticating` and from nothing
 * else (B-3); no variant ever waits for an end signal.
 */
sealed interface AppLockRowState {

    /** The first read is in flight: no switch, no checked state (INV-2). */
    data object Checking : AppLockRowState

    /** Committed off, and nothing blocks turning it on. */
    data object Off : AppLockRowState

    /** Committed on. Turning it off asks for a fresh authentication (T18). */
    data object On : AppLockRowState

    /** A prompt is in flight. The switch shows the committed value and is disabled. */
    data class Waiting(val purpose: AuthPurpose, val committedOn: Boolean) : AppLockRowState

    /** Committed on but the lock is paused (`INERT`): the switch can only be turned off, with no prompt (T19, AE-54). */
    data class InertOn(val reason: InertReason) : AppLockRowState

    /** Committed off and the switch is disabled with an explanation. */
    data class Unavailable(val reason: AppLockUnavailableReason) : AppLockRowState

    /** The settings are transiently unreadable: no switch, no checked state (INV-2). */
    data object Transient : AppLockRowState
}

/**
 * The "Lock after leaving the app" row (S22-b.1), present only while the COMMITTED lock is on (ux 2.1). [committed] is the
 * committed timeout and is the only value the row ever shows (AE-116, INV-46: no optimistic value). [paused] is true
 * while the lock is paused (`INERT`): TECH-13, options longer than [committed] are disabled in the picker, because
 * nothing can authenticate (a UI hint only; the controller enforces it).
 */
data class AutoLockTimeoutRowState(val committed: AutoLockTimeout, val paused: Boolean)

/**
 * What the "App lock & privacy" section renders (1a discreet row, 1b lock, timeout and secure-screen rows).
 *
 * Every state is derived from the committed store state (INV-46). There is no optimistic value, and
 * an unknown state never renders as Off (INV-2): [Loading] has no switch at all and the unreadable
 * states render the fail-closed effective value (discreet on).
 */
sealed interface AppLockPrivacySectionState {

    /** First read in flight. No checked state is asserted for any row. */
    data object Loading : AppLockPrivacySectionState

    /**
     * Readable store. [discreetOn] is the EFFECTIVE discreet value (stored value, or on while the committed lock is on);
     * [discreetForced] is true while the committed lock is on or paused, when the row is read-only (INV-48, AE-65).
     * [isWriting] disables every write row. [lock] is the lock row (S22-a.1). From S22-b.1: [timeoutRow] is the timeout
     * row, null while the committed lock is off; [secureOn] is the EFFECTIVE secure-screen value (stored value, or on
     * while the committed lock is on) and [secureForced] is true while the committed lock is on (INV-48, AE-103: the row
     * is read-only). The three new fields have defaults only so a state built without them reads as "lock off".
     */
    data class Ready(
        val discreetOn: Boolean,
        val isWriting: Boolean,
        val notice: AppLockPrivacySectionNotice?,
        val discreetForced: Boolean,
        val lock: AppLockRowState,
        val timeoutRow: AutoLockTimeoutRowState? = null,
        val secureOn: Boolean = true,
        val secureForced: Boolean = false
    ) : AppLockPrivacySectionState

    /**
     * `IO_ERROR` or `TIMEOUT`. Every per-field write is rejected (INV-44); recovery is a re-read
     * (INV-47). The row shows the fail-closed effective value (discreet on), disabled.
     */
    data object TransientUnreadable : AppLockPrivacySectionState

    /**
     * `CORRUPTED` or `INVALID_VALUE`. The review card is shown; the only accepted write is one
     * review-save of the whole record (INV-44). The choices start at the fail-closed record `F` (S22-b.1, item 55, the
     * full record): [lockChoice] On, [timeoutChoice] `IMMEDIATE`, [discreetChoice] on and [secureChoice] on.
     *
     * [lockChoice] is the EFFECTIVE choice: it is always false while [lockPaused] (`INERT`, INV-45: Off is the only choice,
     * and a record with the lock on would be rejected). While [lockChoice] is on, the discreet and secure rows are forced
     * (read-only, shown on) and the timeout row is shown; while it is off they are editable and the timeout row is hidden.
     * The three new fields have defaults (the fail-closed record) only so a state built without them reads as `F`.
     */
    data class NeedsReview(
        val discreetChoice: Boolean,
        val isSaving: Boolean,
        val notice: AppLockPrivacySectionNotice?,
        val lockChoice: Boolean = true,
        val lockPaused: Boolean = false,
        val timeoutChoice: AutoLockTimeout = AutoLockTimeout.IMMEDIATE,
        val secureChoice: Boolean = true
    ) : AppLockPrivacySectionState
}

/**
 * ViewModel of the "App lock & privacy" section (S12; the lock row is S22-a.1; the timeout row, the secure-screen row and
 * the full review card are S22-b.1). [PrivacySettingsViewModel] is untouched.
 *
 * Legal path: the only writers are [AppLockSettingsOperations.requestDiscreetChange] (WRITE 5: the forced-aware
 * per-field discreet write plus the post-commit re-post sweep, serialised inside the operations; S20-d),
 * [AppLockSettingsOperations.requestReviewSave] (INV-44, INV-45; S20-e), from S22-a.1 the lock requests
 * [AppLockSettingsOperations.requestEnable] and [AppLockSettingsOperations.requestDisable] (WRITE 3: T26, T17, T18,
 * T19) and, from S22-b.1, [AppLockSettingsOperations.requestTimeoutChange] (WRITE 4: INV-3, OD-8a, TECH-13) and
 * [AppLockSettingsOperations.requestSecureScreenChange] (INV-48, AE-103). The two lock requests are not suspend and are
 * called directly on Main; the controller starts the prompt on its own main dispatcher. The suspend requests (timeout,
 * secure screen, discreet, review-save) run in the application scope under `NonCancellable`. All of them end in the
 * settings repository (INV-1). The section never holds an optimistic value: [uiState] is derived from the repository's
 * committed load state (INV-46), so a timeout whose prompt was cancelled or never confirmed keeps showing the old value
 * (AE-116).
 *
 * Reads of the controller (S22-a.1, owner item 81b): exactly its published `state` (the lock row is Waiting only while
 * the state is `Authenticating`, B-3) and its read-only `openOriginEnd` signal (the notices). The signal is a
 * StateFlow, so a new collector gets the latest end replayed: the value at construction is the baseline and is never an
 * event (B-2). The capability provider is a DISPLAY HINT only (INV-23): the hint may suppress an enable tap
 * (fail-closed) and never authorizes a request; every request re-reads the capability itself (T26).
 *
 * The review-save diagnostics (`LOCK_SETTINGS_REVIEW_SAVED`, `LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE)`), the lock
 * diagnostics (`LOCK_ENABLED`, `LOCK_DISABLED`, `LOCK_UNAVAILABLE_*`) and the post-commit discreet sweep are owned by the
 * controller and the operations (D7), once each. This ViewModel emits none of them and adds no diagnostic code.
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
    private val capabilityProvider: DeviceAuthCapabilityProvider,
    private val privacyDiagnostics: PrivacyDiagnosticsEmitter,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    /** Latest committed load state. null = the first read has not arrived yet (never Off). */
    private val loadState = MutableStateFlow<AppLockSettingsLoadState?>(null)
    private val isWriting = MutableStateFlow(false)
    private val notice = MutableStateFlow<AppLockPrivacySectionNotice?>(null)
    private val reviewChoice = MutableStateFlow(FAIL_CLOSED_DISCREET)

    /**
     * The review card's other three choices (S22-b.1, item 55). They start at the fail-closed record `F`: the lock On, the
     * timeout `IMMEDIATE`, the secure screen on. Nothing is written until [saveReview]. The lock choice is the RAW choice;
     * the effective one is always false while the lock is paused (INV-45).
     */
    private val reviewLockChoice = MutableStateFlow(FAIL_CLOSED_LOCK)
    private val reviewTimeoutChoice = MutableStateFlow(FAIL_CLOSED_TIMEOUT)
    private val reviewSecureChoice = MutableStateFlow(FAIL_CLOSED_SECURE)

    /**
     * The latest capability read. A display hint only (INV-23): it picks the unavailable explanation of the lock row and
     * may suppress an enable tap (fail-closed), but never authorizes a request: the Operations' own T26 read decides.
     * A failed read is `CHECK_FAILED`.
     */
    private val capabilityHint = MutableStateFlow(readCapabilityHint())

    /** True after a request or a prompt found the device not usable right now (T26, AE-93); cleared when it reads usable. */
    private val refusedNow = MutableStateFlow(false)

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

    /** The review-card inputs, folded into one flow so the section combine stays within five typed sources. */
    private data class ReviewInputs(
        val choice: Boolean,
        val committed: Boolean,
        val lock: Boolean,
        val timeout: AutoLockTimeout,
        val secure: Boolean
    )

    /** What the lock row needs besides the committed settings: the controller state and the display hint (B-3). */
    private data class LockInputs(
        val state: AppLockState,
        val capability: DeviceAuthCapability,
        val refusedNow: Boolean
    )

    private val reviewInputs: Flow<ReviewInputs> = combine(
        reviewChoice,
        reviewCommitted,
        reviewLockChoice,
        reviewTimeoutChoice,
        reviewSecureChoice
    ) { choice, committed, lockChoice, timeoutChoice, secureChoice ->
        ReviewInputs(choice, committed, lockChoice, timeoutChoice, secureChoice)
    }

    private val lockInputs: Flow<LockInputs> =
        combine(controller.state, capabilityHint, refusedNow) { controllerState, capability, refused ->
            LockInputs(controllerState, capability, refused)
        }

    val uiState: StateFlow<AppLockPrivacySectionState> = combine(
        loadState,
        isWriting,
        notice,
        reviewInputs,
        lockInputs
    ) { state, writing, currentNotice, review, lock ->
        when (state) {
            null -> AppLockPrivacySectionState.Loading
            is AppLockSettingsLoadState.Loaded -> readySection(state.settings, writing, currentNotice, lock)
            is AppLockSettingsLoadState.FirstRunDefault -> readySection(state.settings, writing, currentNotice, lock)
            is AppLockSettingsLoadState.Unreadable ->
                if (state.cause.isPersistent) {
                    // INV-45: while the lock is paused (INERT) Off is the only choice, whatever was chosen before.
                    val paused = lock.state is AppLockState.Inert
                    AppLockPrivacySectionState.NeedsReview(
                        discreetChoice = review.choice,
                        isSaving = writing || review.committed,
                        notice = currentNotice,
                        lockChoice = review.lock && !paused,
                        lockPaused = paused,
                        timeoutChoice = review.timeout,
                        secureChoice = review.secure
                    )
                } else {
                    AppLockPrivacySectionState.TransientUnreadable
                }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppLockPrivacySectionState.Loading)

    /**
     * B-2: the latest end at construction. `openOriginEnd` is a StateFlow, so a collector gets the latest end replayed;
     * a value equal to this baseline is a replay, never an event.
     */
    private val openOriginEndBaseline: OpenOriginEnd? = controller.openOriginEnd.value

    init {
        startObserving()
        observeOpenOriginEnds()
    }

    /**
     * Per-field discreet write. Ignored unless the section is [AppLockPrivacySectionState.Ready], idle and the row is
     * not forced (INV-44: no per-field write while unreadable; INV-48: no write while the lock is on or paused; the
     * operations reject both as well).
     */
    fun setDiscreetNotifications(enabled: Boolean) {
        val current = uiState.value
        if (current !is AppLockPrivacySectionState.Ready || current.isWriting || current.discreetForced) return
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

    /**
     * Per-field secure-screen write (S22-b.1, INV-48, AE-103) through [AppLockSettingsOperations.requestSecureScreenChange].
     * Ignored unless the section is [AppLockPrivacySectionState.Ready], idle and the row is not forced (the operations
     * reject a forced value as well: REJECTED_FORCED shows the save-failed notice and re-reads). Same discipline as the
     * discreet write: application scope, `NonCancellable`, no outer serializer, `CancellationException` rethrown. Neither
     * the failure diagnostic nor any other `LOCK_*` code is emitted here (D7): the operations own them.
     */
    fun setSecureScreen(enabled: Boolean) {
        val current = uiState.value
        if (current !is AppLockPrivacySectionState.Ready || current.isWriting || current.secureForced) return
        if (!beginWrite()) return
        appScope.launch {
            try {
                withContext(NonCancellable) {
                    handlePlain(operations.requestSecureScreenChange(enabled))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Secure screen write failed: class=%s", DiagnosticThrowableSummary.className(e))
                notice.value = AppLockPrivacySectionNotice.SAVE_FAILED
            } finally {
                isWriting.value = false
            }
        }
    }

    /**
     * The timeout picker's choice (S22-b.1, INV-3, OD-8a, AE-116) through [AppLockSettingsOperations.requestTimeoutChange].
     * An equal or shorter timeout commits without a prompt; a longer one starts the CHANGE prompt and the row keeps showing
     * the COMMITTED timeout until (and unless) the apply commits: there is no optimistic value. The Operations return before
     * the prompt ends, so the lock row shows Waiting from the controller state while it is open (B-3), and a cancelled or
     * failed prompt leaves the old value (AE-116) with the notice of the end signal.
     *
     * Ignored unless the section is Ready, idle, the timeout row is present (committed lock on), no prompt is in flight, and
     * the choice differs from the committed value. TECH-13: while the lock is paused a LONGER choice is also ignored (a UI
     * hint only, the picker disables those options; the controller enforces it with NOT_IN_ORIGIN and writes nothing).
     */
    fun onTimeoutSelected(timeout: AutoLockTimeout) {
        val current = uiState.value
        if (current !is AppLockPrivacySectionState.Ready || current.isWriting) return
        if (current.lock is AppLockRowState.Waiting) return
        val row = current.timeoutRow ?: return
        if (timeout == row.committed) return
        if (row.paused && timeout.millis > row.committed.millis) return
        if (!beginWrite()) return
        appScope.launch {
            try {
                // Serialised inside the operations: deliberately NO outer serializer (non-reentrant: it would deadlock).
                withContext(NonCancellable) {
                    handleTimeoutResult(operations.requestTimeoutChange(timeout))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Timeout write failed: class=%s", DiagnosticThrowableSummary.className(e))
                notice.value = AppLockPrivacySectionNotice.SAVE_FAILED
            } finally {
                isWriting.value = false
            }
        }
    }

    /**
     * Sets the review card's discreet choice. Nothing is written until [saveReview]. Ignored while the lock choice is on
     * (the row is forced, INV-48) so the stored choice cannot change behind a read-only row.
     */
    fun setReviewDiscreetChoice(enabled: Boolean) {
        val current = uiState.value
        if (current !is AppLockPrivacySectionState.NeedsReview || current.isSaving || current.lockChoice) return
        reviewChoice.value = enabled
    }

    /** Sets the review card's secure-screen choice. Ignored while the lock choice is on (forced, INV-48). */
    fun setReviewSecureChoice(enabled: Boolean) {
        val current = uiState.value
        if (current !is AppLockPrivacySectionState.NeedsReview || current.isSaving || current.lockChoice) return
        reviewSecureChoice.value = enabled
    }

    /**
     * Sets the review card's lock choice (item 55). On is not an enable flow (no explainer, no prompt): it only selects the
     * record's lock field. While the lock is paused (`INERT`) On is refused (INV-45: Off is the only choice).
     */
    fun setReviewLockChoice(enabled: Boolean) {
        val current = uiState.value
        if (current !is AppLockPrivacySectionState.NeedsReview || current.isSaving) return
        if (enabled && current.lockPaused) return
        reviewLockChoice.value = enabled
    }

    /** Sets the review card's timeout choice. Ignored unless the lock choice is on (the row is only shown then). */
    fun setReviewTimeoutChoice(timeout: AutoLockTimeout) {
        val current = uiState.value
        if (current !is AppLockPrivacySectionState.NeedsReview || current.isSaving || !current.lockChoice) return
        reviewTimeoutChoice.value = timeout
    }

    /**
     * Review-save of the whole record through [AppLockSettingsOperations.requestReviewSave] (INV-44, INV-45, S20-e). From
     * S22-b.1 (owner-approvals item 55) the record is the FULL record the review card shows: lock = the lock choice (On
     * by default, never On while paused), timeout = the timeout choice (default `IMMEDIATE`), discreet and secure = the
     * stored choices (default on; read-only while the lock choice is on). The weakening classification, the CHANGE prompt,
     * the INERT exception and the capability check are the operations' (a lock-on record with a capability that is not
     * available is REJECTED_CAPABILITY, mapped to the save-failed notice). Ignored unless the review card is showing and idle.
     */
    fun saveReview() {
        val current = uiState.value
        if (current !is AppLockPrivacySectionState.NeedsReview || current.isSaving) return
        val record = AppLockPrivacySettings(
            discreetNotifications = current.discreetChoice,
            appLockEnabled = current.lockChoice,
            autoLockTimeout = current.timeoutChoice,
            secureScreen = current.secureChoice
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

    /**
     * Re-reads the capability hint (display only, INV-23): at each foreground, and from the "Try again" of the
     * unavailable-right-now row. It never starts a request and writes nothing. A hint that reads usable clears the
     * unavailable-right-now explanation.
     */
    fun refreshCapabilityHint() {
        applyCapabilityHint(readCapabilityHint(), markRefused = false)
    }

    /**
     * "Continue" of the enable explainer: T26 then the ENABLE prompt through [AppLockSettingsOperations.requestEnable],
     * called directly on Main. Ignored unless the lock row is Off. The row never changes here: it follows the committed
     * value and the controller state (INV-46, B-3), and the outcome notices come from the end signal.
     */
    fun onEnableConfirmed() {
        val current = uiState.value
        if (current !is AppLockPrivacySectionState.Ready || current.lock != AppLockRowState.Off) return
        notice.value = null
        val result = try {
            operations.requestEnable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Lock enable request failed: class=%s", DiagnosticThrowableSummary.className(e))
            notice.value = AppLockPrivacySectionNotice.ENABLE_FAILED
            return
        }
        handleLockRequestResult(result, LockRequestPurpose.ENABLE)
    }

    /**
     * The turn-off tap: the DISABLE prompt (T18), or the no-prompt disable while `INERT` (T19), through
     * [AppLockSettingsOperations.requestDisable], called directly on Main. There is no confirm dialog in device mode
     * (ux 2.2c). Ignored unless the lock row is On or paused-On.
     */
    fun onTurnOffRequested() {
        val current = uiState.value
        if (current !is AppLockPrivacySectionState.Ready) return
        if (current.lock != AppLockRowState.On && current.lock !is AppLockRowState.InertOn) return
        notice.value = null
        val result = try {
            operations.requestDisable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Lock disable request failed: class=%s", DiagnosticThrowableSummary.className(e))
            notice.value = AppLockPrivacySectionNotice.SAVE_FAILED
            return
        }
        handleLockRequestResult(result, LockRequestPurpose.DISABLE)
    }

    /** Which lock request produced a [SettingsRequestResult], so a refusal notice names the right failure. */
    private enum class LockRequestPurpose { ENABLE, DISABLE }

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
            // REJECTED_CAPABILITY (INV-45): the record has the lock on but the fresh capability is not AVAILABLE. The card
            // stays as it is, so the person can choose Off and save again.
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
        // The next review (if the store is corrupted again) starts from the fail-closed record.
        resetReviewChoices()
        // Keep the card busy until the observer shows a readable state: no duplicate review-save.
        reviewCommitted.value = true
    }

    /** Every review-card choice back to the fail-closed record `F` (lock On, `IMMEDIATE`, discreet on, secure on). */
    private fun resetReviewChoices() {
        reviewChoice.value = FAIL_CLOSED_DISCREET
        reviewLockChoice.value = FAIL_CLOSED_LOCK
        reviewTimeoutChoice.value = FAIL_CLOSED_TIMEOUT
        reviewSecureChoice.value = FAIL_CLOSED_SECURE
    }

    /**
     * Exhaustive over [TimeoutChangeResult] (no else: a new value must be mapped here). The failure diagnostic is owned by
     * the controller and the operations (D7), not by this ViewModel.
     */
    private fun handleTimeoutResult(result: TimeoutChangeResult) {
        when (result) {
            // COMMITTED: written with no prompt; the committed value arrives through the store observer (INV-46).
            // STARTED: the CHANGE prompt is open and nothing is committed; the row keeps the old committed value and the
            // lock row shows Waiting from the controller state (AE-116); the end signal gives the notice.
            TimeoutChangeResult.COMMITTED,
            TimeoutChangeResult.STARTED -> Unit
            // Nothing changed (the previous value is still in force): the tap must not be silently ignored, so show the
            // save-failed notice and re-read. NOT_IN_ORIGIN is also the controller's answer for TECH-13 (a longer
            // timeout while the lock is paused); ALREADY_IN_FLIGHT means a prompt or an apply is running.
            TimeoutChangeResult.FAILED,
            TimeoutChangeResult.ALREADY_IN_FLIGHT,
            TimeoutChangeResult.NOT_IN_ORIGIN -> {
                notice.value = AppLockPrivacySectionNotice.SAVE_FAILED
                resubscribeOnMain()
            }
        }
    }

    /**
     * Exhaustive over [SettingsRequestResult] (no else: a new value must be mapped here). Neither lock diagnostic nor
     * state change belongs to this ViewModel (D7, B-3): the result of a started prompt is the controller state
     * (Waiting) and later the end signal. [purpose] picks the notice of a refusal whose re-read reads usable: the enable
     * failure for an enable, the save failure for a disable.
     */
    private fun handleLockRequestResult(result: SettingsRequestResult, purpose: LockRequestPurpose) {
        when (result) {
            // The prompt attempt started (or the T19 apply was launched). The row follows the controller state.
            SettingsRequestResult.STARTED -> Unit
            // T26: the capability is not usable. The refusal itself is recorded once by the controller (F-5, D7). Re-read
            // the hint so the row explains why. A re-read that reads usable (the device changed since the request) shows
            // the enable failure for an enable and the save failure for a disable, so the tap is never silently ignored.
            SettingsRequestResult.REFUSED_UNAVAILABLE -> {
                val capability = applyCapabilityHint(readCapabilityHint(), markRefused = true)
                if (capability == DeviceAuthCapability.AVAILABLE) {
                    notice.value = when (purpose) {
                        LockRequestPurpose.ENABLE -> AppLockPrivacySectionNotice.ENABLE_FAILED
                        LockRequestPurpose.DISABLE -> AppLockPrivacySectionNotice.SAVE_FAILED
                    }
                }
            }
            // Another prompt or apply is running, the state is not the request's origin, or the settings are under
            // review: nothing was written. Re-read so the observer reports the true state; the row shows Waiting from
            // the controller state when a prompt really is in flight.
            SettingsRequestResult.ALREADY_IN_FLIGHT,
            SettingsRequestResult.NOT_IN_ORIGIN,
            SettingsRequestResult.REVIEW_REQUIRED -> resubscribeOnMain()
        }
    }

    /** Reads the capability hint. A failed read is `CHECK_FAILED` (never usable, never a crash); cancellation is rethrown. */
    private fun readCapabilityHint(): DeviceAuthCapability =
        try {
            capabilityProvider.current()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Capability hint read failed: class=%s", DiagnosticThrowableSummary.className(e))
            DeviceAuthCapability.CHECK_FAILED
        }

    /**
     * Publishes a hint. The unavailable-right-now explanation is armed only by a refusal ([markRefused]) and only for a
     * transient capability; any other reading (usable, no credential, unsupported OS) has its own variant or none, so it
     * clears the flag. Exhaustive over [DeviceAuthCapability] (no else).
     */
    private fun applyCapabilityHint(capability: DeviceAuthCapability, markRefused: Boolean): DeviceAuthCapability {
        capabilityHint.value = capability
        refusedNow.value = when (capability) {
            DeviceAuthCapability.HW_UNAVAILABLE,
            DeviceAuthCapability.UNKNOWN,
            DeviceAuthCapability.CHECK_FAILED -> markRefused || refusedNow.value
            DeviceAuthCapability.UNSUPPORTED_API,
            DeviceAuthCapability.AVAILABLE,
            DeviceAuthCapability.NO_CREDENTIAL -> false
        }
        return capability
    }

    /** B-2: reacts only to a value that differs from [openOriginEndBaseline]. */
    private fun observeOpenOriginEnds() {
        viewModelScope.launch {
            try {
                controller.openOriginEnd.collect { end ->
                    if (end != null && end != openOriginEndBaseline) handleOpenOriginEnd(end)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The notices are a courtesy; the row and the committed value do not depend on this observer.
                Timber.w("Open-origin end observer failed: class=%s", DiagnosticThrowableSummary.className(e))
            }
        }
    }

    private fun handleOpenOriginEnd(end: OpenOriginEnd) {
        // NOT_AVAILABLE: the prompt reported no credential or hardware and the controller's check agreed. Re-read the hint so
        // the row explains why (an ENABLE end also arms the unavailable-right-now variant for a transient capability).
        if (end.kind == OpenOriginEndKind.NOT_AVAILABLE) {
            applyCapabilityHint(readCapabilityHint(), markRefused = end.prompt == OpenOriginPrompt.ENABLE)
        }
        val next = noticeForEnd(end)
        if (next != null) notice.value = next
    }

    /**
     * The notice of one end (B-1): a lock notice follows the END'S PROMPT, never the kind alone. LOCK_ON only for an ENABLE
     * end, LOCK_OFF only for a DISABLE end (T19's negative attemptId included); a CHANGE end never produces a lock notice.
     * Exhaustive over [OpenOriginEndKind] and [OpenOriginPrompt] (no else: a new value must be mapped here).
     */
    private fun noticeForEnd(end: OpenOriginEnd): AppLockPrivacySectionNotice? = when (end.kind) {
        OpenOriginEndKind.APPLIED -> when (end.prompt) {
            OpenOriginPrompt.ENABLE -> AppLockPrivacySectionNotice.LOCK_ON
            OpenOriginPrompt.DISABLE -> AppLockPrivacySectionNotice.LOCK_OFF
            OpenOriginPrompt.CHANGE -> null
        }
        OpenOriginEndKind.NOT_COMMITTED -> AppLockPrivacySectionNotice.SAVE_FAILED
        OpenOriginEndKind.CANCELLED -> when (end.prompt) {
            OpenOriginPrompt.ENABLE -> AppLockPrivacySectionNotice.LOCK_NOT_TURNED_ON
            OpenOriginPrompt.DISABLE, OpenOriginPrompt.CHANGE -> null
        }
        OpenOriginEndKind.LOCKED_OUT -> AppLockPrivacySectionNotice.TOO_MANY_ATTEMPTS
        OpenOriginEndKind.ERROR -> when (end.prompt) {
            OpenOriginPrompt.ENABLE -> AppLockPrivacySectionNotice.ENABLE_FAILED
            OpenOriginPrompt.DISABLE, OpenOriginPrompt.CHANGE -> null
        }
        // Handled by the capability re-read above; no message of its own.
        OpenOriginEndKind.NOT_AVAILABLE -> null
        // The attempt was abandoned (host lost) or reserved: nothing to say.
        OpenOriginEndKind.HOST_LOST,
        OpenOriginEndKind.BACKGROUNDED -> null
    }

    private fun readySection(
        settings: AppLockPrivacySettings,
        writing: Boolean,
        currentNotice: AppLockPrivacySectionNotice?,
        lock: LockInputs
    ): AppLockPrivacySectionState.Ready {
        // INV-48, AE-65, AE-103: while the committed lock is on (an `INERT` lock is stored on too) discreet and secure
        // screen are forced.
        val forced = settings.appLockEnabled
        return AppLockPrivacySectionState.Ready(
            discreetOn = settings.discreetNotifications || forced,
            isWriting = writing,
            notice = currentNotice,
            discreetForced = forced,
            lock = lockRowOf(committedOn = settings.appLockEnabled, lock = lock),
            // The timeout row exists only while the COMMITTED lock is on and shows the COMMITTED timeout (AE-116). It is
            // paused (TECH-13) while the controller is INERT.
            timeoutRow = if (forced) {
                AutoLockTimeoutRowState(settings.autoLockTimeout, paused = lock.state is AppLockState.Inert)
            } else {
                null
            },
            secureOn = settings.secureScreen || forced,
            secureForced = forced
        )
    }

    /**
     * The lock row. The checked value is the COMMITTED `appLockEnabled` (INV-46). Waiting is derived from the controller
     * state being `Authenticating` and from nothing else (B-3): it never depends on an end signal.
     * Exhaustive over [DeviceAuthCapability] (no else).
     */
    private fun lockRowOf(committedOn: Boolean, lock: LockInputs): AppLockRowState {
        val controllerState = lock.state
        return when {
            controllerState is AppLockState.Authenticating ->
                AppLockRowState.Waiting(controllerState.purpose, committedOn)
            committedOn && controllerState is AppLockState.Inert -> AppLockRowState.InertOn(controllerState.reason)
            committedOn -> AppLockRowState.On
            else -> when (lock.capability) {
                DeviceAuthCapability.UNSUPPORTED_API ->
                    AppLockRowState.Unavailable(AppLockUnavailableReason.OS)
                DeviceAuthCapability.NO_CREDENTIAL ->
                    AppLockRowState.Unavailable(AppLockUnavailableReason.NOT_SECURE)
                DeviceAuthCapability.HW_UNAVAILABLE,
                DeviceAuthCapability.UNKNOWN,
                DeviceAuthCapability.CHECK_FAILED ->
                    if (lock.refusedNow) AppLockRowState.Unavailable(AppLockUnavailableReason.NOW) else AppLockRowState.Off
                DeviceAuthCapability.AVAILABLE -> AppLockRowState.Off
            }
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
     *   value and the post-commit busy flag;
     * - a move from the review category to a readable one while [pendingReviewSave] is armed is the confirmed
     *   review-save committing: REVIEW_SAVED is shown once and the flag is consumed. Leaving the review category for
     *   loading or transient-unreadable clears the flag without a notice.
     */
    private fun applyLoadState(new: AppLockSettingsLoadState?) {
        val oldCategory = categoryOf(loadState.value)
        val newCategory = categoryOf(new)
        if (newCategory == LoadCategory.REVIEW && oldCategory != LoadCategory.REVIEW) {
            resetReviewChoices()
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

        /** Fail-closed record `F` for the review card's other choices (spec 2.3, AMD-1): lock On, `IMMEDIATE`, secure on. */
        val FAIL_CLOSED_LOCK: Boolean = AppLockPrivacySettings.FAIL_CLOSED.appLockEnabled
        val FAIL_CLOSED_TIMEOUT: AutoLockTimeout = AppLockPrivacySettings.FAIL_CLOSED.autoLockTimeout
        val FAIL_CLOSED_SECURE: Boolean = AppLockPrivacySettings.FAIL_CLOSED.secureScreen
    }
}
