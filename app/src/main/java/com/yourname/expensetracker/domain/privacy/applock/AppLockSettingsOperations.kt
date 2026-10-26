package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.diagnostics.DiagnosticThrowableSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded result of [AppLockSettingsOperations.requestEnable] and [AppLockSettingsOperations.requestDisable].
 * No text and no platform detail; the caller (a later UI slice) maps it to its own constant copy.
 */
enum class SettingsRequestResult {
    /** The prompt attempt started. Its outcome is applied by this class; the result is observed in `AppLockState`. */
    STARTED,

    /** A prompt is already in flight, of any purpose (INV-34, INV-54). Nothing changed. */
    ALREADY_IN_FLIGHT,

    /** The state is not the request's origin (`DISABLED` for enable, `UNLOCKED` for disable), or the T33 gate is open. */
    NOT_IN_ORIGIN,

    /** T26: the capability is not `AVAILABLE` (or the check failed). No prompt, nothing written. */
    REFUSED_UNAVAILABLE
}

/**
 * The application-scoped owner of the app-lock enable and disable requests (S18 part (i-a): T17, T18, T26, T33;
 * INV-3, INV-13, INV-54). It is the ONLY production caller of `AppLockController.startOpenOriginAttempt`,
 * `onOpenOriginApplyCompleted` and the two state-move entries `onEnableRefusedAtRequest` and
 * `onOpenOriginSettingApplied` (pinned by a source scan). It holds no ViewModel, Activity, Compose or Android
 * type: the success handler it gives the controller captures only this singleton and a constant prompt.
 *
 * ## Enable ([requestEnable])
 *
 * T26 first: the capability is read here (outside the controller's monitor); anything other than `AVAILABLE`
 * (a failed read counts as `CHECK_FAILED`) is a refusal with no prompt, recorded once per process per
 * (code, cause) by the controller. API level < 30 reads as `UNSUPPORTED_API`. Otherwise the ENABLE attempt starts.
 *
 * ## The apply (T33)
 *
 * The controller hands a matching success to the handler on the main thread, after the attempt closed and the
 * state returned to its origin. The handler starts the apply and nothing else: starting the apply is its LAST
 * statement, it does not block, and it never throws `CancellationException` (`launch` does not throw it). The
 * apply runs on the application scope (an IO dispatcher), never on main:
 *
 * 1. (enable only) the effective discreet value is read BEFORE the write;
 * 2. the durable `appLockEnabled` write, under `NonCancellable` so a cancelled caller cannot leave a half
 *    applied change (the write is one atomic DataStore edit);
 * 3. ONE controller transition re-checks the state under its monitor and moves it (T17: `DISABLED` to
 *    `UNLOCKED`, `LOCK_ENABLED`; T18: `UNLOCKED` to `DISABLED`, `LOCK_DISABLED`). A write that did not commit
 *    moves nothing and records `LOCK_SETTING_PERSIST_FAILED(APP_LOCK_ENABLED)` (AE-114);
 * 4. (enable, committed) [DiscreetRepostTrigger] with the before and after effective discreet values (INV-13,
 *    INV-46: only after the commit). Disabling never re-posts;
 * 5. `onOpenOriginApplyCompleted(attemptId)` in a `finally`, on every end of the apply (committed, failed,
 *    a throwing step, a cancellation, which is rethrown), after the state move of step 3. That releases the T33
 *    gate for a held link.
 *
 * The unlock-equivalent discard of the pending background is done by the controller's close and is not
 * repeated here.
 *
 * Write serialisation: the lock flag is written by a per-field atomic store edit, so it cannot interleave with
 * the discreet write or a review-save at the store. The `AppLockSettingsWriteSerializer` of the settings
 * ViewModel is not shared (it lives in the ui package; moving it into the domain, with the pre-read, the write
 * and the trigger inside it, is a recorded BLOCKING carry-forward for any wiring, privacy review F4).
 *
 * One apply at a time (F5): a flag is set before the apply launches and cleared in its `finally`; while it is set
 * both requests answer [SettingsRequestResult.ALREADY_IN_FLIGHT] (the controller's gate alone would answer
 * `NOT_IN_ORIGIN`, and a `DISABLE` could otherwise start while an `ENABLE` apply is still writing).
 *
 * The state move is bound to the attempt: it passes the attempt id, and the controller derives enable or
 * disable from the prompt it recorded for that id (F2).
 */
@Singleton
class AppLockSettingsOperations @Inject constructor(
    private val controller: AppLockController,
    private val settingsRepository: AppLockSettingsRepository,
    private val capabilityProvider: DeviceAuthCapabilityProvider,
    private val discreetRepostTrigger: DiscreetRepostTrigger,
    @ApplicationScope private val scope: CoroutineScope
) {

    /** True from before the apply launches until its `finally` (F5). */
    private val applyRunning = AtomicBoolean(false)

    /** T26 request-time check, then the ENABLE attempt (T17). Never writes anything itself. */
    fun requestEnable(): SettingsRequestResult {
        if (applyRunning.get()) return SettingsRequestResult.ALREADY_IN_FLIGHT
        val capability = readCapability()
        if (capability != DeviceAuthCapability.AVAILABLE) {
            controller.onEnableRefusedAtRequest(capability)
            return SettingsRequestResult.REFUSED_UNAVAILABLE
        }
        return start(OpenOriginPrompt.ENABLE)
    }

    /** The DISABLE attempt (T18, OD-8a: fresh authentication). Never writes anything itself. */
    fun requestDisable(): SettingsRequestResult =
        if (applyRunning.get()) SettingsRequestResult.ALREADY_IN_FLIGHT else start(OpenOriginPrompt.DISABLE)

    private fun start(prompt: OpenOriginPrompt): SettingsRequestResult =
        when (controller.startOpenOriginAttempt(prompt, OpenOriginSuccessHandler { attemptId, _ -> startApply(attemptId, prompt) })) {
            is OpenOriginStartResult.Started -> SettingsRequestResult.STARTED
            OpenOriginStartResult.AlreadyInFlight -> SettingsRequestResult.ALREADY_IN_FLIGHT
            OpenOriginStartResult.NotInOrigin -> SettingsRequestResult.NOT_IN_ORIGIN
        }

    /**
     * The T33 hand-off target. Its LAST (and only) statement starts the apply: nothing can throw after the apply
     * started, and `launch` does not throw `CancellationException`.
     */
    private fun startApply(attemptId: Long, prompt: OpenOriginPrompt) {
        applyRunning.set(true) // before the launch; cleared in runApply's finally (F5)
        try {
            // ATOMIC (arch review F4): once started, the coroutine cannot be cancelled before its first suspension
            // point, so runApply's finally (completion and the flag clear) always runs. If the launch itself fails
            // (a cancelled scope cannot happen with ATOMIC, but a rejecting dispatcher can), the flag is released.
            scope.launch(start = CoroutineStart.ATOMIC) { runApply(attemptId, prompt) }
        } catch (t: Throwable) {
            // CancellationException is a Throwable and is rethrown unchanged.
            applyRunning.set(false)
            throw t
        }
    }

    private suspend fun runApply(attemptId: Long, prompt: OpenOriginPrompt) {
        try {
            withContext(NonCancellable) {
                val enable = prompt == OpenOriginPrompt.ENABLE
                val wasEffectiveDiscreet = if (enable) readEffectiveDiscreetBeforeWrite() else false
                val result = write(enable)
                val committed = result as? AppLockSettingsWriteResult.Committed
                // The state move comes BEFORE the completion call (S17 binding).
                controller.onOpenOriginSettingApplied(attemptId, committed != null)
                if (enable && committed != null) {
                    discreetRepostTrigger.onCommittedWrite(
                        wasEffectiveDiscreet = wasEffectiveDiscreet,
                        isEffectiveDiscreet = EffectivePrivacyValues.effectiveDiscreet(
                            AppLockSettingsLoadState.Loaded(committed.settings)
                        )
                    )
                }
            }
        } finally {
            // Every end of the apply, also a throwing step or a cancellation (rethrown by the unwinding).
            try {
                controller.onOpenOriginApplyCompleted(attemptId)
            } finally {
                applyRunning.set(false)
            }
        }
    }

    /**
     * The effective discreet value before the lock write, mapped exactly as `UpdateDiscreetNotificationsUseCase`
     * does (F1, INV-13): `Loaded` and `FirstRunDefault` give the effective value; `Unreadable` is NOT evidence that
     * the notifications were already discreet, so it counts as "not discreet yet" (false). A thrown read counts
     * the same. So the sweep may run (idempotent and only more private), never be skipped for lack of a read.
     */
    private suspend fun readEffectiveDiscreetBeforeWrite(): Boolean =
        try {
            when (val before = settingsRepository.getLoadState()) {
                is AppLockSettingsLoadState.Loaded,
                is AppLockSettingsLoadState.FirstRunDefault -> EffectivePrivacyValues.effectiveDiscreet(before)
                is AppLockSettingsLoadState.Unreadable -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("App-lock enable pre-read failed: class=%s", DiagnosticThrowableSummary.className(e))
            false
        }

    /** The durable write. Any non-cancellation failure is a failed write (bounded: class name only in the log). */
    private suspend fun write(enabled: Boolean): AppLockSettingsWriteResult =
        try {
            settingsRepository.setAppLockEnabled(enabled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("App-lock settings write failed: class=%s", DiagnosticThrowableSummary.className(e))
            AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED)
        }

    /**
     * The request-time capability, read outside any monitor. A failed read is [DeviceAuthCapability.CHECK_FAILED]:
     * enabling never proceeds on an unknown capability (fail closed). `CancellationException` and `Error` propagate.
     */
    private fun readCapability(): DeviceAuthCapability =
        try {
            capabilityProvider.current()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("App-lock enable capability read failed: class=%s", DiagnosticThrowableSummary.className(e))
            DeviceAuthCapability.CHECK_FAILED
        }
}
