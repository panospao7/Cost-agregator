package com.yourname.expensetracker.ui.screens.privacysettings.applockpin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsOperations
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapability
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapabilityProvider
import com.yourname.expensetracker.domain.privacy.applock.EffectiveLockMode
import com.yourname.expensetracker.domain.privacy.applock.PinOperationResult
import com.yourname.expensetracker.domain.privacy.applock.PinPresentation
import com.yourname.expensetracker.domain.privacy.applock.PinRejection
import com.yourname.expensetracker.domain.privacy.applock.PinSetupRequestResult
import com.yourname.expensetracker.domain.privacy.applock.PinStep
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayReason
import com.yourname.expensetracker.domain.privacy.applock.pin.PinFormatPolicy
import com.yourname.expensetracker.domain.privacy.applock.pin.PinFormatResult
import com.yourname.expensetracker.ui.applock.pin.delayMillisOfTier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Whether the App PIN row and its entries can be used (INV-50: unavailable while the lock is paused or off). */
enum class AppPinAvailability { AVAILABLE, PAUSED, LOCK_OFF, NOT_READY }

/** The in-app PIN step on screen (ux-flows 2.5b-2.5d). A step is never a system prompt. */
enum class PinFlowStep {
    NONE, SETUP_ACK, SETUP_ENTER, SETUP_CONFIRM, CHANGE_CURRENT, CHANGE_NEW, CHANGE_CONFIRM, REMOVE_ENTER, REMOVE_CONFIRM,

    /** "Turn off app lock" (T30 disable variant): the current PIN entry, then the confirm dialog. */
    TURN_OFF_ENTER, TURN_OFF_CONFIRM
}

/** Bounded, content-free message under the pad (no PIN, no failure count). */
enum class PinFlowMessage { NONE, MISMATCH, TOO_WEAK, WRONG, DELAYED, DELAYED_NEUTRAL, UNREADABLE, FAILED }

/** One-shot results. [OPEN_BACKUP_RESTORE] is navigation only: no backup is started (AE-104). */
enum class PinFlowEvent { PIN_SET, PIN_CHANGED, PIN_REMOVED, PIN_REMOVED_LOCK_OFF, TURN_OFF_INCOMPLETE, OPERATION_FAILED, OPEN_BACKUP_RESTORE }

/** Bounded UI state. It carries no PIN, no digit and no failure count (INV-38). */
data class AppPinSettingsUiState(
    val availability: AppPinAvailability = AppPinAvailability.NOT_READY,
    val pinSet: Boolean = false,
    val step: PinFlowStep = PinFlowStep.NONE,
    val ackChecked: Boolean = false,
    val message: PinFlowMessage = PinFlowMessage.NONE,
    /** Delay length for [PinFlowMessage.DELAYED]; the screen counts it down locally. Restart on [delayToken]. */
    val delayMillis: Long = 0L,
    val delayToken: Int = 0,
    val busy: Boolean = false,
    /** Remove dialog variant: the phone has no usable screen lock, so removing the PIN turns app lock off. */
    val removeNoSecure: Boolean = false
)

/**
 * ViewModel of the App PIN settings screen and its setup, change and remove steps (S27 b1).
 *
 * Legal path: setup -> [AppLockController.requestPinSetup] (CONFIRM_CHANGE prompt) then [AppLockController.enablePin];
 * change -> [AppLockController.beginPinChange] + [AppLockController.changePin]; remove -> [AppLockController.beginPinRemoval]
 * + [AppLockSettingsOperations.removePin]. No in-app reset exists (INV-42) and "Back up now" only navigates (AE-104).
 *
 * PIN material (INV-38): entries arrive as CharArrays and are held ONLY in the private fields below (never a String, a
 * SavedStateHandle or a Bundle). They are zeroed on cancel, mismatch, completion, re-lock and [onCleared]; the controller
 * zeroes the arrays it receives. Nothing PIN-related is logged. Fingerprint unlock is not handled here.
 */
@HiltViewModel
class AppPinSettingsViewModel @Inject constructor(
    private val controller: AppLockController,
    private val operations: AppLockSettingsOperations,
    private val capabilityProvider: DeviceAuthCapabilityProvider
) : ViewModel() {

    private val local = MutableStateFlow(AppPinSettingsUiState())
    private val busy = MutableStateFlow(false)
    private val events = Channel<PinFlowEvent>(Channel.BUFFERED)

    /** One-shot results (snackbar, navigation). Collect once. */
    val flowEvents: Flow<PinFlowEvent> = events.receiveAsFlow()

    // PIN material. Main thread only. The current PIN is held until the operation that verifies it.
    private var heldCurrent: CharArray? = null
    private var heldNew: CharArray? = null
    private var mismatches = 0
    private var lastControllerStep = PinStep.NONE

    val uiState: StateFlow<AppPinSettingsUiState> = combine(controller.state, controller.pin, local, busy) { s, p, l, b ->
        l.copy(
            availability = availabilityOf(s),
            pinSet = p.effectiveLockMode == EffectiveLockMode.APP_PIN,
            busy = b || s is AppLockState.Authenticating
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppPinSettingsUiState())

    init {
        // AE-119: a step left open by an earlier screen is closed; a step always starts empty.
        if (controller.pin.value.step != PinStep.NONE) controller.cancelPinStep()
        viewModelScope.launch {
            combine(controller.state, controller.pin) { s, p -> s to p }.collect { (s, p) -> onController(s, p) }
        }
    }

    private fun availabilityOf(state: AppLockState): AppPinAvailability = when (state) {
        is AppLockState.Inert -> AppPinAvailability.PAUSED
        is AppLockState.Disabled -> AppPinAvailability.LOCK_OFF
        is AppLockState.Unlocked, is AppLockState.Authenticating -> AppPinAvailability.AVAILABLE
        else -> AppPinAvailability.NOT_READY
    }

    /** Read-only reaction to the controller (R2: never calls a transition from here). */
    private fun onController(state: AppLockState, pin: PinPresentation) {
        val previous = lastControllerStep
        lastControllerStep = pin.step
        val step = local.value.step
        if (step == PinFlowStep.NONE) return
        if (availabilityOf(state) != AppPinAvailability.AVAILABLE) return reset() // re-locked or paused: nothing applied
        if (step == PinFlowStep.SETUP_ACK) {
            if (pin.step == PinStep.SETUP_ENTRY && previous != PinStep.SETUP_ENTRY) enterStep(PinFlowStep.SETUP_ENTER)
            return
        }
        if (previous != PinStep.NONE && pin.step == PinStep.NONE && !busy.value) reset() // the step was closed elsewhere
    }

    // --- Starting a flow ---------------------------------------------------------------------------------------

    /** "Set up app PIN": shows the recovery warning and acknowledgement (step 0). Nothing is requested yet. */
    fun startSetup() {
        val s = uiState.value
        if (s.availability != AppPinAvailability.AVAILABLE || s.pinSet || s.step != PinFlowStep.NONE) return
        // Reviewer B2: a step left open in the controller is closed first, so the setup request starts from a clean step.
        if (controller.pin.value.step != PinStep.NONE) controller.cancelPinStep()
        local.value = AppPinSettingsUiState(step = PinFlowStep.SETUP_ACK)
    }

    /** The single "I understand..." checkbox (UQ-4). */
    fun acknowledge(checked: Boolean) {
        if (local.value.step == PinFlowStep.SETUP_ACK) local.update { it.copy(ackChecked = checked) }
    }

    /** "Continue" on step 0: the one fresh device authentication, then the controller opens the setup step. */
    fun continueSetup() {
        val s = uiState.value
        if (s.step != PinFlowStep.SETUP_ACK || !s.ackChecked || s.busy || s.availability != AppPinAvailability.AVAILABLE) return
        val result = try {
            controller.requestPinSetup()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PinSetupRequestResult.NotAllowed
        }
        when (result) {
            is PinSetupRequestResult.Started, PinSetupRequestResult.AlreadyInFlight ->
                local.update { it.copy(message = PinFlowMessage.NONE) } // the step opens when the prompt succeeds
            else -> local.update { it.copy(message = PinFlowMessage.FAILED) }
        }
    }

    /** "Change app PIN": opens the controller step (no prompt) and asks for the current PIN. */
    fun startChange() {
        val s = uiState.value
        if (s.availability != AppPinAvailability.AVAILABLE || !s.pinSet || s.step != PinFlowStep.NONE) return
        if (!controller.beginPinChange()) return
        local.value = AppPinSettingsUiState(step = PinFlowStep.CHANGE_CURRENT)
    }

    /** "Remove app PIN": opens the controller step (no prompt) and asks for the current PIN, then the dialog. */
    fun startRemove() {
        val s = uiState.value
        if (s.availability != AppPinAvailability.AVAILABLE || !s.pinSet || s.step != PinFlowStep.NONE) return
        val noSecure = try {
            capabilityProvider.current().let { it != DeviceAuthCapability.AVAILABLE && it != DeviceAuthCapability.CHECK_FAILED }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!controller.beginPinRemoval()) return
        local.value = AppPinSettingsUiState(step = PinFlowStep.REMOVE_ENTER, removeNoSecure = noSecure)
    }

    /**
     * "Turn off app lock" (T30 disable variant): opens the controller step (no prompt) and asks for the current PIN, then
     * the dialog. The verifier is deleted first and the lock turned off by [AppLockSettingsOperations.removePin].
     */
    fun startTurnOff() {
        val s = uiState.value
        if (s.availability != AppPinAvailability.AVAILABLE || !s.pinSet || s.step != PinFlowStep.NONE) return
        if (!controller.beginPinRemoval()) return
        local.value = AppPinSettingsUiState(step = PinFlowStep.TURN_OFF_ENTER)
    }

    /** "Back up now" (AE-104): navigation to Backup & Restore only. No backup call exists in this class. */
    fun onBackUpNow() {
        events.trySend(PinFlowEvent.OPEN_BACKUP_RESTORE)
    }

    /** Leaves the flow with nothing applied. Ignored while an operation or the device prompt is running. */
    fun cancel() {
        if (uiState.value.busy) return
        reset()
        controller.cancelPinStep()
    }

    /**
     * The screen was left. This ViewModel is activity-scoped, so [onCleared] does not run here: zero the held digits and
     * close the controller step. Safe while an operation runs: [launchOp] owns the arrays it was given.
     */
    fun onScreenLeft() {
        reset()
        controller.cancelPinStep()
    }

    // --- Entries -----------------------------------------------------------------------------------------------

    /** Advances the current step with one confirmed entry. The VM takes ownership of [entry] and zeroes it. */
    fun submitEntry(entry: CharArray) {
        val s = uiState.value
        if (s.busy || s.availability != AppPinAvailability.AVAILABLE) return entry.zero()
        when (s.step) {
            PinFlowStep.SETUP_ENTER -> holdNew(entry, PinFlowStep.SETUP_ENTER, PinFlowStep.SETUP_CONFIRM)
            PinFlowStep.CHANGE_NEW -> holdNew(entry, PinFlowStep.CHANGE_NEW, PinFlowStep.CHANGE_CONFIRM)
            PinFlowStep.CHANGE_CURRENT -> holdCurrent(entry, PinFlowStep.CHANGE_NEW)
            PinFlowStep.REMOVE_ENTER -> holdCurrent(entry, PinFlowStep.REMOVE_CONFIRM)
            PinFlowStep.TURN_OFF_ENTER -> holdCurrent(entry, PinFlowStep.TURN_OFF_CONFIRM)
            PinFlowStep.SETUP_CONFIRM -> confirmNew(entry, PinFlowStep.SETUP_ENTER, ::runSetup)
            PinFlowStep.CHANGE_CONFIRM -> confirmNew(entry, PinFlowStep.CHANGE_NEW, ::runChange)
            else -> entry.zero()
        }
    }

    private fun holdCurrent(entry: CharArray, next: PinFlowStep) {
        heldCurrent.zero()
        heldCurrent = entry
        enterStep(next)
    }

    private fun holdNew(entry: CharArray, same: PinFlowStep, next: PinFlowStep) {
        if (PinFormatPolicy.validate(entry) != PinFormatResult.VALID) {
            entry.zero()
            return enterStep(same, PinFlowMessage.TOO_WEAK)
        }
        heldNew.zero()
        heldNew = entry
        mismatches = 0
        enterStep(next)
    }

    /** Compares the confirmation with the held first entry. A first mismatch stays; a second returns to [back]. */
    private fun confirmNew(entry: CharArray, back: PinFlowStep, run: (CharArray) -> Unit) {
        val first = heldNew
        if (first == null) {
            entry.zero()
            return enterStep(back)
        }
        val same = first.contentEquals(entry)
        entry.zero()
        if (!same) {
            if (++mismatches >= 2) {
                heldNew.zero()
                heldNew = null
                mismatches = 0
                return enterStep(back, PinFlowMessage.MISMATCH)
            }
            return enterStep(local.value.step, PinFlowMessage.MISMATCH)
        }
        heldNew = null
        mismatches = 0
        run(first)
    }

    private fun CharArray?.zero() {
        this?.fill('\u0000')
    }

    private fun enterStep(step: PinFlowStep, message: PinFlowMessage = PinFlowMessage.NONE) {
        local.update { it.copy(step = step, message = message, delayMillis = 0L) }
    }

    /** Zeroes every held digit and clears the flow. Does not touch the controller. */
    private fun reset() {
        heldCurrent.zero()
        heldNew.zero()
        heldCurrent = null
        heldNew = null
        mismatches = 0
        local.value = AppPinSettingsUiState()
    }

    private fun finish(event: PinFlowEvent) {
        reset()
        events.trySend(event)
    }

    // --- Operations --------------------------------------------------------------------------------------------

    /**
     * Runs one controller operation. The arrays are owned by the controller call (it zeroes them) and are zeroed here
     * again in `finally`. `ATOMIC` start so a cancelled scope still reaches the `finally`. Cancellation is rethrown.
     */
    private fun launchOp(vararg secrets: CharArray, block: suspend () -> Unit) {
        busy.value = true
        viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(message = PinFlowMessage.FAILED) }
            } finally {
                secrets.forEach { it.fill('\u0000') }
                busy.value = false
            }
        }
    }

    private fun runSetup(newPin: CharArray) = launchOp(newPin) {
        when (controller.enablePin(newPin)) {
            PinSetupRequestResult.Enabled -> finish(PinFlowEvent.PIN_SET)
            is PinSetupRequestResult.InvalidFormat -> enterStep(PinFlowStep.SETUP_ENTER, PinFlowMessage.TOO_WEAK)
            PinSetupRequestResult.AlreadyInFlight -> enterStep(PinFlowStep.SETUP_ENTER, PinFlowMessage.FAILED)
            PinSetupRequestResult.NotAllowed -> finish(PinFlowEvent.OPERATION_FAILED)
            else -> finish(PinFlowEvent.OPERATION_FAILED)
        }
    }

    private fun runChange(newPin: CharArray) {
        val current = heldCurrent
        heldCurrent = null
        if (current == null) {
            newPin.zero()
            return enterStep(PinFlowStep.CHANGE_CURRENT)
        }
        launchOp(current, newPin) {
            when (val result = controller.changePin(current, newPin)) {
                PinOperationResult.Done -> finish(PinFlowEvent.PIN_CHANGED)
                is PinOperationResult.NotVerified -> applyRejection(result.reason, PinFlowStep.CHANGE_CURRENT)
                PinOperationResult.NotInStep -> reset()
                PinOperationResult.Failed -> finish(PinFlowEvent.OPERATION_FAILED)
                else -> enterStep(PinFlowStep.CHANGE_CURRENT, PinFlowMessage.FAILED)
            }
        }
    }

    /** The "Remove" button of the dialog (after the current PIN was entered). Verification and removal are one operation. */
    fun confirmRemove() = runRemoval(PinFlowStep.REMOVE_CONFIRM, PinFlowStep.REMOVE_ENTER, turnLockOff = false)

    /** The "Turn off" button of its dialog: verification, verifier deletion and lock-off are one operation (T30). */
    fun confirmTurnOff() = runRemoval(PinFlowStep.TURN_OFF_CONFIRM, PinFlowStep.TURN_OFF_ENTER, turnLockOff = true)

    private fun runRemoval(confirmStep: PinFlowStep, entryStep: PinFlowStep, turnLockOff: Boolean) {
        val s = uiState.value
        if (s.step != confirmStep || s.busy) return
        val current = heldCurrent
        heldCurrent = null
        if (current == null) return enterStep(entryStep)
        launchOp(current) {
            when (val result = operations.removePin(current, turnLockOff)) {
                PinOperationResult.Done, PinOperationResult.ModeWriteFailed, PinOperationResult.RemovedLockOffRequired ->
                    finish(
                        if (controller.state.value is AppLockState.Disabled) {
                            PinFlowEvent.PIN_REMOVED_LOCK_OFF
                        } else if (turnLockOff) {
                            // Turn-off was requested but the lock is still on (e.g. ModeWriteFailed): never claim success.
                            PinFlowEvent.TURN_OFF_INCOMPLETE
                        } else {
                            PinFlowEvent.PIN_REMOVED
                        }
                    )
                is PinOperationResult.NotVerified -> applyRejection(result.reason, entryStep)
                PinOperationResult.NotInStep -> reset()
                PinOperationResult.Failed -> finish(PinFlowEvent.OPERATION_FAILED)
                else -> enterStep(entryStep, PinFlowMessage.FAILED)
            }
        }
    }

    /** A current PIN that was not accepted: back to its entry step with the bounded wrong / delay message (INV-38). */
    private fun applyRejection(reason: PinRejection, step: PinFlowStep) {
        when (reason) {
            is PinRejection.Wrong -> {
                val millis = delayMillisOfTier(reason.delay)
                if (millis > 0L) delayed(step, millis, neutral = false) else enterStep(step, PinFlowMessage.WRONG)
            }
            is PinRejection.Delayed ->
                delayed(step, reason.remainingMillis, reason.reason == PinDelayReason.ATTEMPT_STATE_UNREADABLE)
            is PinRejection.VerifierUnreadable -> enterStep(step, PinFlowMessage.UNREADABLE)
            PinRejection.Unavailable -> enterStep(step, PinFlowMessage.FAILED)
        }
    }

    private fun delayed(step: PinFlowStep, millis: Long, neutral: Boolean) {
        local.update {
            it.copy(
                step = step,
                message = if (neutral) PinFlowMessage.DELAYED_NEUTRAL else PinFlowMessage.DELAYED,
                delayMillis = millis,
                delayToken = it.delayToken + 1
            )
        }
    }

    override fun onCleared() {
        heldCurrent.zero()
        heldNew.zero()
        heldCurrent = null
        heldNew = null
        controller.cancelPinStep()
        events.close()
    }
}
