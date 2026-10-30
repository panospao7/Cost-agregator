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
    REFUSED_UNAVAILABLE,

    /**
     * S18 ii-e (T19, INV-44, INV-45): a disable while `INERT` found the settings under review (`INERT(SETTINGS_UNREADABLE_NO_AUTH)`
     * or an unreadable load state). Nothing written, no prompt; the review-save is the way for a persistent unreadable
     * store, or the settings are temporarily unreadable (retry or re-read).
     */
    REVIEW_REQUIRED
}

/**
 * Bounded result of [AppLockSettingsOperations.requestTimeoutChange] (S18 part (i-b1)). No text, no platform detail.
 */
enum class TimeoutChangeResult {
    /** An equal or shorter timeout (or any timeout while the lock is off) was written durably, with no prompt. */
    COMMITTED,

    /** The write did not commit, or the settings were unreadable (nothing written). The previous value stays in force. */
    FAILED,

    /** A longer timeout: the CONFIRM_CHANGE prompt started. Its outcome is applied by this class. */
    STARTED,

    /** A prompt or an apply is already in flight (INV-34, INV-54). Nothing changed. */
    ALREADY_IN_FLIGHT,

    /**
     * A longer timeout while the state is not `UNLOCKED`, or the T33 gate is open. While the state is `INERT` this is
     * also the answer of TECH-13 (S18 i-b2: the spec's `Rejected(AUTH_UNAVAILABLE)`, mapped to this existing bounded
     * result): no write, no prompt and no state change, because nothing can authenticate.
     * Also the answer of a direct write whose conditional store edit found a stale pre-read (the lock is on and the
     * stored timeout is shorter than requested): nothing was written, a retry takes the prompt path.
     */
    NOT_IN_ORIGIN
}

/**
 * Bounded result of [AppLockSettingsOperations.requestDiscreetChange] and
 * [AppLockSettingsOperations.requestSecureScreenChange] (S18 part (i-b2)). No text, no platform detail.
 */
enum class PlainSettingResult {
    /** The value was written durably, with no prompt (the lock is off, so neither value is forced). */
    COMMITTED,

    /** The write did not commit, or the settings were unreadable (nothing written). The previous value stays in force. */
    FAILED,

    /** INV-48, AE-103: the lock is on, so the value is forced and read-only. Nothing written, no prompt, no record. */
    REJECTED_FORCED,

    /** A lock apply is already in flight (INV-54). Nothing changed. */
    ALREADY_IN_FLIGHT
}

/**
 * Bounded result of [AppLockSettingsOperations.requestReviewSave] (S18 part (ii-d): T27, INV-44, INV-45). No text, no
 * platform detail.
 */
enum class ReviewSaveResult {
    /** The record was written durably with no prompt (not weakening, or `INERT` with nothing to authenticate, D3). */
    COMMITTED,

    /** A weakening record: the CONFIRM_CHANGE prompt (CHANGE copy) started. Its outcome is applied by this class. */
    STARTED,

    /** The write did not commit (or was refused at the store). The sentinel and the state stay; nothing was applied. */
    FAILED,

    /** INV-45: the record has the lock on but the fresh capability is not `AVAILABLE`. Nothing written, no prompt. */
    REJECTED_CAPABILITY,

    /** The settings are not under review (the state is not `UNLOCKED`/`INERT`, or the load state is not persistently unreadable). */
    NOT_IN_REVIEW,

    /**
     * The request needs the prompt but the state is not its origin: a weakening record while `INERT` with a capability
     * that is `AVAILABLE` or `CHECK_FAILED` (D3), or the controller refused the start (T33 gate open, state moved).
     */
    NOT_IN_ORIGIN,

    /** A prompt or an apply is already in flight (INV-34, INV-54). Nothing changed. */
    ALREADY_IN_FLIGHT
}

/**
 * The application-scoped owner of the app-lock enable and disable requests (S18 part (i-a): T17, T18, T26, T33;
 * INV-3, INV-13, INV-54). It is the ONLY production caller of `AppLockController.startOpenOriginAttempt`,
 * `startTimeoutChangeAttempt`, `onOpenOriginApplyCompleted` and the three state-move entries
 * `onEnableRefusedAtRequest`, `onOpenOriginSettingApplied` and `onTimeoutWrittenWithoutPrompt`
 * (pinned by a source scan). It holds no ViewModel, Activity, Compose or Android
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
 * Write serialisation (S18 ii-a, F4): every write path of this class (lock apply, timeout apply, direct timeout
 * write, discreet/secure-screen writes) runs inside the domain-owned singleton [AppLockSettingsWriteSerializer],
 * the SAME instance the settings ViewModel wraps its WRITE 1 (discreet toggle) and WRITE 2 (review-save) with. The
 * pre-read, the durable write, the controller entry and the trigger are one critical section, so a pre-read and its
 * INV-13 sweep decision can never go stale against another writer, and a review-save cannot interleave with a lock
 * apply. The serializer is not reentrant: nothing called inside it re-enters it, and no prompt is held inside it
 * (a prompt has closed before any apply starts).
 *
 * Cancelled durable write (S18 ii-a, F7): a write that ends with a `CancellationException` inside `NonCancellable`
 * has an unknown durable outcome (the store edit may have committed). The store is re-read ONCE: a loaded value equal
 * to the request is treated as committed (the normal committed path runs, trigger included); anything else
 * (another value, first-run default, unreadable, a throwing read) is treated as not committed (no move, the failure
 * recorded once, no trigger, INV-46). For the plain (discreet, secure-screen) writes a re-read that shows the stored
 * lock ON is the forced answer of the uncancelled path, `Rejected(FORCED)`, for either value (no move, no trigger, no
 * record). The `CancellationException` is then rethrown (a failure of the outcome step and a second cancellation of
 * the re-read are kept on it as suppressed exceptions), and the completion `finally` (INV-54) still runs. See
 * [settleCancelledWrite].
 *
 * One apply at a time (F5): a flag is set before the apply launches and cleared in its `finally`; while it is set
 * both requests answer [SettingsRequestResult.ALREADY_IN_FLIGHT] (the controller's gate alone would answer
 * `NOT_IN_ORIGIN`, and a `DISABLE` could otherwise start while an `ENABLE` apply is still writing).
 *
 * The state move is bound to the attempt: it passes the attempt id, and the controller derives enable or
 * disable from the prompt it recorded for that id (F2).
 *
 * Review-save (S18 ii-d, T27, INV-45): [requestReviewSave] recovers a persistently unreadable store. It is the only
 * production caller of `AppLockSettingsRepository.reviewSave` (since S20-e) and the only one behind the controller. A
 * weakening record confirms through the CHANGE prompt, then applies like the other prompt paths (serializer,
 * `NonCancellable`, F7 re-read, id-bound state move, completion in a `finally`). Its one production caller is the 1a
 * privacy settings ViewModel.
 */
@Singleton
class AppLockSettingsOperations @Inject constructor(
    private val controller: AppLockController,
    private val settingsRepository: AppLockSettingsRepository,
    private val capabilityProvider: DeviceAuthCapabilityProvider,
    private val discreetRepostTrigger: DiscreetRepostTrigger,
    private val writeSerializer: AppLockSettingsWriteSerializer,
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

    /**
     * The DISABLE attempt (T18, OD-8a: fresh authentication) from `UNLOCKED`. Never writes anything itself, except in
     * `INERT`, where nothing can authenticate and T19 applies (see [requestInertDisable]).
     */
    fun requestDisable(): SettingsRequestResult =
        when {
            applyRunning.get() -> SettingsRequestResult.ALREADY_IN_FLIGHT
            controller.state.value is AppLockState.Inert -> requestInertDisable()
            else -> start(OpenOriginPrompt.DISABLE)
        }

    /**
     * S18 part (ii-e): the disable of the lock while `INERT`, with NO authentication (T19, INV-3, INV-45, D3). The
     * steps, in order:
     *
     * 1. The one-apply flag [applyRunning] is claimed atomically ([SettingsRequestResult.ALREADY_IN_FLIGHT] if it is
     *    taken) and released on every path that does not launch the apply.
     * 2. [AppLockController.beginInertDisable]: `INERT(SETTINGS_UNREADABLE_NO_AUTH)`, or any unreadable load state, is
     *    [SettingsRequestResult.REVIEW_REQUIRED] with nothing written (INV-44: the review-save is the way); a state that
     *    is not `INERT` is [SettingsRequestResult.NOT_IN_ORIGIN].
     * 3. The capability is read FRESH. Only a capability that is neither `AVAILABLE` nor `CHECK_FAILED` (nothing can
     *    authenticate, D3) allows the no-auth path; otherwise [SettingsRequestResult.NOT_IN_ORIGIN] (the state is not the
     *    origin of the DISABLE prompt, which starts only from `UNLOCKED`) and nothing is written.
     * 4. The apply launches on the application scope (ATOMIC) and the result is [SettingsRequestResult.STARTED]; the
     *    outcome is observed in `AppLockState`. The apply ([runInertDisableApply]) runs inside the [writeSerializer]
     *    under `NonCancellable` and RE-PROVES D3 there (state still `INERT`, FRESH capability still neither `AVAILABLE`
     *    nor `CHECK_FAILED`, store readable) before writing `appLockEnabled = false` (F7 re-read after a cancelled
     *    write), then the id-bound `onInertDisableApplied` moves `INERT` to `DISABLED` only after `Committed` and only if
     *    the state is still `INERT`. A failed write leaves `INERT` and records the failure once.
     *
     * Disabling never re-posts (INV-13): no [DiscreetRepostTrigger] call. No prompt is started, so no attempt id, no T33
     * gate and no held link are involved. This request has no production caller yet (dormant).
     */
    private fun requestInertDisable(): SettingsRequestResult {
        if (!applyRunning.compareAndSet(false, true)) return SettingsRequestResult.ALREADY_IN_FLIGHT
        var launched = false
        try {
            val begin = controller.beginInertDisable()
            if (begin !is InertDisableBegin.Begun) {
                return if (begin == InertDisableBegin.ReviewRequired) {
                    SettingsRequestResult.REVIEW_REQUIRED
                } else {
                    SettingsRequestResult.NOT_IN_ORIGIN
                }
            }
            val capability = readCapability()
            if (capability == DeviceAuthCapability.AVAILABLE || capability == DeviceAuthCapability.CHECK_FAILED) {
                // An unused token is replaced by the next begin and applies to no other id.
                return SettingsRequestResult.NOT_IN_ORIGIN
            }
            scope.launch(start = CoroutineStart.ATOMIC) { runInertDisableApply(begin.id) }
            launched = true
            return SettingsRequestResult.STARTED
        } finally {
            // A refusal, a cancellation of the capability read or a launch that failed releases the flag; a throwable
            // propagates unchanged. After a successful launch the apply's own finally clears it.
            if (!launched) applyRunning.set(false)
        }
    }

    private suspend fun runInertDisableApply(id: Long) {
        try {
            withContext(NonCancellable) {
                writeSerializer.withExclusiveWrite {
                    // D3 re-proof INSIDE the serializer (the decision was made outside it): the state must still be INERT,
                    // a FRESH capability must still be unable to authenticate and the store must still be readable.
                    // Anything else writes nothing; the state and the view stay as they are.
                    val fresh = readCapability()
                    if (controller.state.value !is AppLockState.Inert ||
                        fresh == DeviceAuthCapability.AVAILABLE ||
                        fresh == DeviceAuthCapability.CHECK_FAILED ||
                        readCommittedSettings() == null
                    ) {
                        return@withExclusiveWrite
                    }
                    // F7: a cancelled write is settled from one store re-read; the cancellation is rethrown afterwards.
                    val outcome = settleCancelledWrite(AppLockSettingField.APP_LOCK_ENABLED, { !it.appLockEnabled }) {
                        write(false)
                    }
                    outcome.applyThenRethrow { result ->
                        controller.onInertDisableApplied(id, result is AppLockSettingsWriteResult.Committed)
                    }
                }
            }
        } finally {
            applyRunning.set(false)
        }
    }

    /**
     * G25-7 (INV-49, AMD-4, T30): removes the app PIN. [AppLockController.disablePin] verifies, deletes the PIN data and
     * sets `lockMode = DEVICE_AUTH`; when the device cannot authenticate it returns
     * [PinOperationResult.RemovedLockOffRequired] instead, and the lock-off write (`appLockEnabled = false`) happens here,
     * the only production call site of the lock flag write: inside the [writeSerializer] under `NonCancellable`, with the
     * F7 settle/re-read, then the controller moves the view and `UNLOCKED` to `DISABLED`. A failed write is
     * [PinOperationResult.ModeWriteFailed] (the PIN is already deleted). Any other result is the controller's.
     */
    suspend fun removePin(current: CharArray): PinOperationResult {
        val result = controller.disablePin(current)
        if (result != PinOperationResult.RemovedLockOffRequired) return result
        return withContext(NonCancellable) {
            writeSerializer.withExclusiveWrite {
                // INV-49: the PIN was deleted, so the lock mode must leave APP_PIN before the lock flag is written.
                val mode = try {
                    settingsRepository.setLockMode(LockMode.DEVICE_AUTH)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w("App-lock lockMode write failed: class=%s", DiagnosticThrowableSummary.className(e))
                    AppLockSettingsWriteResult.Failed(AppLockSettingField.LOCK_MODE)
                }
                if (mode !is AppLockSettingsWriteResult.Committed) {
                    controller.onPinRemovalLockOffApplied(mode)
                    return@withExclusiveWrite PinOperationResult.ModeWriteFailed
                }
                val outcome = settleCancelledWrite(AppLockSettingField.APP_LOCK_ENABLED, { !it.appLockEnabled }) {
                    write(false)
                }
                outcome.applyThenRethrow { written ->
                    if (controller.onPinRemovalLockOffApplied(written)) PinOperationResult.Done else PinOperationResult.ModeWriteFailed
                }
            }
        }
    }

    /**
     * S18 part (i-b1): the auto-lock timeout change (INV-3, OD-8a). The committed timeout is read first (one fresh
     * read; an unreadable or failed read is a failure with nothing written, no prompt, fail closed).
     *
     * - Equal or shorter than the committed timeout, or the lock is off: written directly, no prompt (a longer
     *   timeout is the only weakening here), by the CONDITIONAL store edit, inside the [writeSerializer] across the
     *   pre-read and the write (privacy review F1). The controller's settings view is updated through
     *   `onTimeoutWrittenWithoutPrompt`. The result is [TimeoutChangeResult.COMMITTED] or [TimeoutChangeResult.FAILED].
     * - Longer, lock on: the CONFIRM_CHANGE attempt (CHANGE copy) starts, only from `UNLOCKED`. Nothing is written
     *   until the prompt succeeds; a cancelled or failed prompt leaves the old timeout (AE-116). The success handler
     *   starts the apply as its LAST statement, exactly as for enable and disable; the apply writes under
     *   `NonCancellable`, then the id-bound `onOpenOriginSettingApplied` (which applies the timeout recorded in the
     *   controller's one-shot token), then completes in a `finally`. [applyRunning] is honoured.
     *
     * TECH-13 (S18 i-b2): a longer timeout with the lock on while the state is `INERT` is refused with the existing
     * [TimeoutChangeResult.NOT_IN_ORIGIN] (the spec's `Rejected(AUTH_UNAVAILABLE)`): no write, no prompt, no state
     * change. An equal or shorter timeout (and any timeout with the lock off) still commits without auth. The check
     * sits after the direct section, so only a request that needs the prompt is refused.
     */
    suspend fun requestTimeoutChange(newTimeout: AutoLockTimeout): TimeoutChangeResult {
        if (applyRunning.get()) return TimeoutChangeResult.ALREADY_IN_FLIGHT
        // Privacy review F1: the pre-read and the direct write are one critical section (so two overlapping direct
        // requests cannot interleave their read and write), applyRunning is re-checked inside it, and the write itself
        // is the conditional store edit that re-checks the weakening inside the edit. A null result means "needs the
        // prompt": that part runs outside the serializer (the prompt can be open for a long time).
        val direct: TimeoutChangeResult? = writeSerializer.withExclusiveWrite {
            if (applyRunning.get()) return@withExclusiveWrite TimeoutChangeResult.ALREADY_IN_FLIGHT
            // S18 i-b1 final gate ISSUE-1: no write while a CONFIRM_CHANGE prompt is open (from its start until its
            // close). The controller has no "ended without success" hook, so a flag could stick; its own state is the
            // source. From the close to the apply start, the apply takes the [writeSerializer] (runTimeoutApply), so a
            // direct write that slipped into that window completes, view included, before the apply writes.
            if (isConfirmChangeOpen()) return@withExclusiveWrite TimeoutChangeResult.ALREADY_IN_FLIGHT
            val committed = readCommittedSettings()
            if (committed == null) {
                // Unreadable (INV-44) or a failed read: nothing is written and no prompt starts; the failure is recorded.
                controller.onTimeoutWrittenWithoutPrompt(newTimeout, false)
                return@withExclusiveWrite TimeoutChangeResult.FAILED
            }
            val longer = newTimeout.millis > committed.autoLockTimeout.millis
            if (!longer || !committed.appLockEnabled) writeTimeoutWithoutPrompt(newTimeout) else null
        }
        if (direct != null) return direct
        if (controller.state.value is AppLockState.Inert) return TimeoutChangeResult.NOT_IN_ORIGIN // TECH-13
        return when (
            controller.startTimeoutChangeAttempt(
                newTimeout,
                OpenOriginSuccessHandler { attemptId, _ -> startTimeoutApply(attemptId, newTimeout) }
            )
        ) {
            is OpenOriginStartResult.Started -> TimeoutChangeResult.STARTED
            OpenOriginStartResult.AlreadyInFlight -> TimeoutChangeResult.ALREADY_IN_FLIGHT
            OpenOriginStartResult.NotInOrigin -> TimeoutChangeResult.NOT_IN_ORIGIN
        }
    }

    /**
     * S18 part (i-b2): the discreet-notifications setting (INV-3, INV-48, AE-103, INV-13). Under INV-48 the value is
     * FORCED while the lock is on (also while `INERT`), so there is no weakening that needs a prompt: the forced-aware
     * conditional store edit refuses either value with `Rejected(FORCED)` ([PlainSettingResult.REJECTED_FORCED]; no
     * write, no prompt, no record), and with the lock off the value is not a weakening (INV-3 covers only an enabled
     * lock), so it commits directly. The effective discreet value is read before the write and handed with the
     * committed one to [DiscreetRepostTrigger] (a sweep only on a false to true edge). The pre-read, the write and the
     * trigger run inside the [writeSerializer].
     */
    suspend fun requestDiscreetChange(enabled: Boolean): PlainSettingResult =
        writePlainSetting(AppLockSettingField.DISCREET, enabled)

    /** S18 part (i-b2): the secure-screen setting; the same rule as [requestDiscreetChange], with no re-post. */
    suspend fun requestSecureScreenChange(enabled: Boolean): PlainSettingResult =
        writePlainSetting(AppLockSettingField.SECURE_SCREEN, enabled)

    /**
     * S18 part (ii-d): the review-save of a persistently unreadable store (T27, INV-44, INV-45, AE-85 to AE-89). The
     * steps, in order:
     *
     * 1. [applyRunning] is honoured ([ReviewSaveResult.ALREADY_IN_FLIGHT]).
     * 2. The state must be `UNLOCKED` or `INERT` and the controller's load state `Unreadable(CORRUPTED | INVALID_VALUE)`,
     *    else [ReviewSaveResult.NOT_IN_REVIEW].
     * 3. The capability is read FRESH. A lock-on record with a capability other than `AVAILABLE` is
     *    [ReviewSaveResult.REJECTED_CAPABILITY]: nothing written, no prompt.
     * 4. [ReviewSaveAuthorisation] classifies the record (weakening = lock off, or a timeout longer than `IMMEDIATE`).
     * 5. Weakening from `UNLOCKED`: the CONFIRM_CHANGE attempt (CHANGE copy) starts through
     *    `startReviewSaveAttempt`; the record is kept in the controller's one-shot token. On success the apply runs
     *    inside the [writeSerializer] under `NonCancellable` (the prompt is NOT held inside it): the conditional
     *    `reviewSave`, the F7 re-read after a cancelled write, then the id-bound `onOpenOriginSettingApplied` (T27 move
     *    from the token), then the discreet trigger `(false, effective after)`, and the completion call in a `finally`.
     * 6. Weakening while `INERT`: allowed without authentication ONLY under D3, when the fresh capability is neither
     *    `AVAILABLE` nor `CHECK_FAILED` (nothing can authenticate). Otherwise [ReviewSaveResult.NOT_IN_ORIGIN].
     * 7. Not weakening, or `INERT` without authentication: a direct write inside the [writeSerializer] (F7 as above),
     *    then `onReviewSavedWithoutPrompt` and the same trigger call.
     *
     * Both writes are the conditional `reviewSave` (D8): the store itself refuses with `Rejected(NOT_UNDER_REVIEW)` if
     * a concurrent commit already recovered it. A committed save bumps the controller's `writeSequence` (F2, D6). The
     * diagnostics (`LOCK_SETTINGS_REVIEW_SAVED`, `LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE)`) are claimed and recorded by
     * the controller (D7), fire and forget, so a diagnostics failure never fails a committed save.
     *
     * Single caller note (S20-e, S18-ii-h, D1): the 1a privacy settings ViewModel is the one production caller, and
     * this class is the only production caller of `AppLockSettingsRepository.reviewSave` (a source-scan pin holds both).
     * The ViewModel sends the full review record (the user's lock, timeout, discreet and secure-screen choices; D-S22-7,
     * owner-approvals item 55) under `NonCancellable` and takes no outer serializer.
     */
    suspend fun requestReviewSave(record: AppLockPrivacySettings): ReviewSaveResult {
        if (applyRunning.get()) return ReviewSaveResult.ALREADY_IN_FLIGHT
        if (!isReviewOpen()) return ReviewSaveResult.NOT_IN_REVIEW
        val capability = readCapability()
        if (record.appLockEnabled && capability != DeviceAuthCapability.AVAILABLE) return ReviewSaveResult.REJECTED_CAPABILITY
        var noAuthUnderD3 = false
        if (ReviewSaveAuthorisation.isWeakening(record)) {
            if (controller.state.value !is AppLockState.Inert) return startReviewSavePrompt(record)
            // D3: with nothing that can authenticate (neither AVAILABLE nor CHECK_FAILED) no authentication is possible.
            if (capability == DeviceAuthCapability.AVAILABLE || capability == DeviceAuthCapability.CHECK_FAILED) {
                return ReviewSaveResult.NOT_IN_ORIGIN
            }
            noAuthUnderD3 = true
        }
        return writeReviewSaveDirect(record, noAuthUnderD3)
    }

    /** True while the state is `UNLOCKED` or `INERT` and the controller's load state is persistently unreadable. */
    private fun isReviewOpen(): Boolean {
        val state = controller.state.value
        return (state is AppLockState.Unlocked || state is AppLockState.Inert) && controller.isPersistentUnreadable
    }

    private fun startReviewSavePrompt(record: AppLockPrivacySettings): ReviewSaveResult =
        when (
            controller.startReviewSaveAttempt(
                record,
                OpenOriginSuccessHandler { attemptId, _ -> startReviewSaveApply(attemptId, record) }
            )
        ) {
            is OpenOriginStartResult.Started -> ReviewSaveResult.STARTED
            OpenOriginStartResult.AlreadyInFlight -> ReviewSaveResult.ALREADY_IN_FLIGHT
            OpenOriginStartResult.NotInOrigin -> ReviewSaveResult.NOT_IN_ORIGIN
        }

    /**
     * The no-prompt review-save write (steps 6 and 7). The re-checks and the write are one critical section.
     *
     * [noAuthUnderD3] is true when the direct path was taken BECAUSE of D3 (a weakening record from `INERT` with nothing
     * that can authenticate). The decision was made outside the serializer, so it is re-proved inside it (INV-45): the
     * state must still be `INERT` and a FRESH capability must still be neither `AVAILABLE` nor `CHECK_FAILED`, else
     * [ReviewSaveResult.NOT_IN_ORIGIN] with nothing written (a weakening save may not skip the prompt once the state
     * moved to `UNLOCKED` or authentication became possible). A lock-on record re-requires a fresh `AVAILABLE`
     * capability, else [ReviewSaveResult.REJECTED_CAPABILITY].
     */
    private suspend fun writeReviewSaveDirect(record: AppLockPrivacySettings, noAuthUnderD3: Boolean): ReviewSaveResult =
        writeSerializer.withExclusiveWrite {
            if (applyRunning.get()) return@withExclusiveWrite ReviewSaveResult.ALREADY_IN_FLIGHT
            if (!isReviewOpen()) return@withExclusiveWrite ReviewSaveResult.NOT_IN_REVIEW
            if (noAuthUnderD3 || record.appLockEnabled) {
                val fresh = readCapability()
                if (noAuthUnderD3 &&
                    (
                        controller.state.value !is AppLockState.Inert ||
                            fresh == DeviceAuthCapability.AVAILABLE ||
                            fresh == DeviceAuthCapability.CHECK_FAILED
                        )
                ) {
                    return@withExclusiveWrite ReviewSaveResult.NOT_IN_ORIGIN
                }
                if (record.appLockEnabled && fresh != DeviceAuthCapability.AVAILABLE) {
                    return@withExclusiveWrite ReviewSaveResult.REJECTED_CAPABILITY
                }
            }
            withContext(NonCancellable) {
                val outcome = settleCancelledWrite(AppLockSettingField.REVIEW_SAVE, { it == record }) {
                    writeReviewSave(record)
                }
                outcome.applyThenRethrow { result ->
                    when (result) {
                        is AppLockSettingsWriteResult.Committed -> {
                            controller.onReviewSavedWithoutPrompt(result.settings, true)
                            runReviewSaveTrigger(result.settings)
                            ReviewSaveResult.COMMITTED
                        }
                        // D8: the store was already recovered by another commit; not a persist failure, nothing applied.
                        is AppLockSettingsWriteResult.Rejected ->
                            if (result.reason == AppLockWriteRejection.NOT_UNDER_REVIEW) {
                                ReviewSaveResult.NOT_IN_REVIEW
                            } else {
                                controller.onReviewSavedWithoutPrompt(record, false)
                                ReviewSaveResult.FAILED
                            }
                        is AppLockSettingsWriteResult.Failed -> {
                            controller.onReviewSavedWithoutPrompt(record, false)
                            ReviewSaveResult.FAILED
                        }
                    }
                }
            }
        }

    /** The T33 hand-off target of a weakening review-save. Its LAST (and only) statement starts the apply. */
    private fun startReviewSaveApply(attemptId: Long, record: AppLockPrivacySettings) {
        applyRunning.set(true) // before the launch; cleared in runReviewSaveApply's finally (F5)
        var launched = false
        try {
            scope.launch(start = CoroutineStart.ATOMIC) { runReviewSaveApply(attemptId, record) }
            launched = true
        } finally {
            // A launch that failed (a rejecting dispatcher) releases the flag; the throwable propagates unchanged.
            if (!launched) applyRunning.set(false)
        }
    }

    private suspend fun runReviewSaveApply(attemptId: Long, record: AppLockPrivacySettings) {
        try {
            withContext(NonCancellable) {
                // No prompt is held inside the serializer (the prompt closed before this apply started).
                writeSerializer.withExclusiveWrite {
                    val outcome = settleCancelledWrite(AppLockSettingField.REVIEW_SAVE, { it == record }) {
                        writeReviewSave(record)
                    }
                    // The state-move entry comes BEFORE the completion call (S17 binding). It applies T27 from its own
                    // token, bound to attemptId; a wrong or reused id is a no-op.
                    outcome.applyThenRethrow { result ->
                        val committed = result as? AppLockSettingsWriteResult.Committed
                        controller.onOpenOriginSettingApplied(attemptId, committed != null)
                        if (committed != null) runReviewSaveTrigger(committed.settings)
                    }
                }
            }
        } finally {
            try {
                controller.onOpenOriginApplyCompleted(attemptId)
            } finally {
                applyRunning.set(false)
            }
        }
    }

    /**
     * INV-13 after a committed review-save: the "before" is false (an unreadable store is not evidence that the
     * notifications were already discreet, see [readEffectiveDiscreetBeforeWrite]), the "after" is the effective value
     * of the committed record. The sweep is idempotent and only more private. The trigger isolates its own failures.
     */
    private suspend fun runReviewSaveTrigger(committed: AppLockPrivacySettings) {
        discreetRepostTrigger.onCommittedWrite(
            wasEffectiveDiscreet = false,
            isEffectiveDiscreet = EffectivePrivacyValues.effectiveDiscreet(AppLockSettingsLoadState.Loaded(committed))
        )
    }

    /** The conditional durable review-save. Any non-cancellation failure is a failed write (bounded: class name only). */
    private suspend fun writeReviewSave(record: AppLockPrivacySettings): AppLockSettingsWriteResult =
        try {
            settingsRepository.reviewSave(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("App-lock review-save write failed: class=%s", DiagnosticThrowableSummary.className(e))
            AppLockSettingsWriteResult.Failed(AppLockSettingField.REVIEW_SAVE)
        }

    private suspend fun writePlainSetting(field: AppLockSettingField, enabled: Boolean): PlainSettingResult {
        if (applyRunning.get()) return PlainSettingResult.ALREADY_IN_FLIGHT
        return writeSerializer.withExclusiveWrite {
            if (applyRunning.get()) return@withExclusiveWrite PlainSettingResult.ALREADY_IN_FLIGHT
            withContext(NonCancellable) {
                val discreet = field == AppLockSettingField.DISCREET
                val wasEffectiveDiscreet = if (discreet) readEffectiveDiscreetBeforeWrite() else false
                val outcome = settleCancelledWrite(
                    field,
                    { settings -> settings.plainValue(field) == enabled },
                    lockOnIsForced = true
                ) {
                    writePlain(field, enabled)
                }
                outcome.applyThenRethrow { written ->
                    when (written) {
                        is AppLockSettingsWriteResult.Committed -> {
                            controller.onPlainSettingWritten(field, enabled, true)
                            if (discreet) {
                                discreetRepostTrigger.onCommittedWrite(
                                    wasEffectiveDiscreet = wasEffectiveDiscreet,
                                    isEffectiveDiscreet = EffectivePrivacyValues.effectiveDiscreet(
                                        AppLockSettingsLoadState.Loaded(written.settings)
                                    )
                                )
                            }
                            PlainSettingResult.COMMITTED
                        }
                        is AppLockSettingsWriteResult.Rejected ->
                            if (written.reason == AppLockWriteRejection.FORCED) {
                                PlainSettingResult.REJECTED_FORCED
                            } else {
                                controller.onPlainSettingWritten(field, enabled, false)
                                PlainSettingResult.FAILED
                            }
                        is AppLockSettingsWriteResult.Failed -> {
                            controller.onPlainSettingWritten(field, enabled, false)
                            PlainSettingResult.FAILED
                        }
                    }
                }
            }
        }
    }

    /** The stored value of one plain (discreet or secure-screen) field, for the F7 re-read comparison. */
    private fun AppLockPrivacySettings.plainValue(field: AppLockSettingField): Boolean =
        if (field == AppLockSettingField.DISCREET) discreetNotifications else secureScreen

    /**
     * A durable write's result together with the `CancellationException` (if any) that ended the write. The exception
     * is held back until the caller has applied the outcome, then rethrown by [applyThenRethrow] (never swallowed).
     */
    private class WriteOutcome(val result: AppLockSettingsWriteResult, val cancellation: CancellationException?)

    /**
     * F7 (S18 ii-a): runs [write]; if it ends with a `CancellationException` the durable outcome is unknown, so the
     * store is re-read ONCE. Only a `Loaded` state whose value satisfies [isRequestedValue] counts as committed
     * ([AppLockSettingsWriteResult.Committed] with the re-read settings); another value, the first-run default (nothing
     * was ever stored), `Unreadable` or a throwing re-read count as not committed ([AppLockSettingsWriteResult.Failed]
     * for [field]: the caller moves nothing, records the failure once and runs no trigger, INV-46). Runs under
     * `NonCancellable` (every caller is inside it). A cancellation raised by the re-read itself also counts as not
     * committed; the ORIGINAL cancellation is kept for the caller to rethrow. Bounded log: class name only.
     */
    private suspend fun settleCancelledWrite(
        field: AppLockSettingField,
        isRequestedValue: (AppLockPrivacySettings) -> Boolean,
        lockOnIsForced: Boolean = false,
        write: suspend () -> AppLockSettingsWriteResult
    ): WriteOutcome =
        try {
            WriteOutcome(write(), null)
        } catch (cancelled: CancellationException) {
            WriteOutcome(rereadAfterCancelledWrite(field, isRequestedValue, lockOnIsForced, cancelled), cancelled)
        }

    private suspend fun rereadAfterCancelledWrite(
        field: AppLockSettingField,
        isRequestedValue: (AppLockPrivacySettings) -> Boolean,
        lockOnIsForced: Boolean,
        first: CancellationException
    ): AppLockSettingsWriteResult {
        val state = try {
            rereadStoreOrNull()
        } catch (second: CancellationException) {
            // Not committed as far as can be proven; the FIRST cancellation is rethrown by the caller, the second is
            // kept on it as a suppressed exception (never lost).
            if (second !== first) first.addSuppressed(second)
            null
        }
        return when (state) {
            is AppLockSettingsLoadState.Loaded ->
                when {
                    // Privacy review C2: the stored lock is on, so the plain field is forced. The uncancelled path
                    // answers Rejected(FORCED) for either value; so does this one (no move, no trigger, no record).
                    lockOnIsForced && state.settings.appLockEnabled ->
                        AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.FORCED)
                    isRequestedValue(state.settings) -> AppLockSettingsWriteResult.Committed(state.settings)
                    else -> AppLockSettingsWriteResult.Failed(field)
                }
            // Null (a throwing re-read), the first-run default and Unreadable all count as not committed.
            else -> AppLockSettingsWriteResult.Failed(field)
        }
    }

    /** The F7 re-read: the load state, or null when the read threw (bounded: class name only). Cancellation propagates. */
    private suspend fun rereadStoreOrNull(): AppLockSettingsLoadState? =
        try {
            settingsRepository.getLoadState()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("App-lock cancelled-write re-read failed: class=%s", DiagnosticThrowableSummary.className(e))
            null
        }

    /**
     * Applies the outcome (state move, record, trigger) via [apply], then rethrows the held cancellation, also when
     * [apply] itself throws (the cancellation wins and is never swallowed; the failure of [apply] stays on it as a
     * suppressed exception, so it is not lost either).
     */
    private inline fun <T> WriteOutcome.applyThenRethrow(apply: (AppLockSettingsWriteResult) -> T): T {
        val held = cancellation ?: return apply(result)
        try {
            apply(result)
        } catch (e: Throwable) {
            if (e === held) throw e
            held.addSuppressed(e)
        }
        throw held
    }

    /** The forced-aware durable write. Any non-cancellation failure is a failed write (bounded: class name only). */
    private suspend fun writePlain(field: AppLockSettingField, enabled: Boolean): AppLockSettingsWriteResult =
        try {
            if (field == AppLockSettingField.DISCREET) {
                settingsRepository.setDiscreetNotificationsIfNotForced(enabled)
            } else {
                settingsRepository.setSecureScreenIfNotForced(enabled)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("App-lock plain setting write failed: class=%s", DiagnosticThrowableSummary.className(e))
            AppLockSettingsWriteResult.Failed(field)
        }

    /**
     * The no-prompt write: durable first (under `NonCancellable`), then the controller's view and failure record. It is
     * the CONDITIONAL store edit (F1): if the stored lock is on and the stored timeout is shorter than [timeout] by the
     * time the edit runs (a stale pre-read), it writes nothing and answers [TimeoutChangeResult.NOT_IN_ORIGIN] with no
     * view change and no failure record; a retry then takes the prompt path.
     */
    private suspend fun writeTimeoutWithoutPrompt(timeout: AutoLockTimeout): TimeoutChangeResult =
        withContext(NonCancellable) {
            // F7 (S18 ii-a): a cancelled write is settled from one store re-read, then the cancellation is rethrown.
            val outcome = settleCancelledWrite(AppLockSettingField.AUTO_LOCK_TIMEOUT, { it.autoLockTimeout == timeout }) {
                writeTimeout(timeout, conditional = true)
            }
            outcome.applyThenRethrow { result ->
                when (result) {
                    is AppLockSettingsWriteResult.Committed -> {
                        controller.onTimeoutWrittenWithoutPrompt(timeout, true)
                        TimeoutChangeResult.COMMITTED
                    }
                    is AppLockSettingsWriteResult.Rejected ->
                        if (result.reason == AppLockWriteRejection.WOULD_WEAKEN) {
                            TimeoutChangeResult.NOT_IN_ORIGIN
                        } else {
                            controller.onTimeoutWrittenWithoutPrompt(timeout, false)
                            TimeoutChangeResult.FAILED
                        }
                    is AppLockSettingsWriteResult.Failed -> {
                        controller.onTimeoutWrittenWithoutPrompt(timeout, false)
                        TimeoutChangeResult.FAILED
                    }
                }
            }
        }

    /** The T33 hand-off target of a timeout change. Its LAST (and only) statement starts the apply (see [startApply]). */
    private fun startTimeoutApply(attemptId: Long, timeout: AutoLockTimeout) {
        applyRunning.set(true) // before the launch; cleared in runTimeoutApply's finally (F5)
        try {
            scope.launch(start = CoroutineStart.ATOMIC) { runTimeoutApply(attemptId, timeout) }
        } catch (t: Throwable) {
            // CancellationException is a Throwable and is rethrown unchanged.
            applyRunning.set(false)
            throw t
        }
    }

    private suspend fun runTimeoutApply(attemptId: Long, timeout: AutoLockTimeout) {
        try {
            withContext(NonCancellable) {
                // ISSUE-1(b): the write and the view update are one critical section with the direct path. No prompt is
                // held inside the serializer (the prompt closed before this apply started).
                writeSerializer.withExclusiveWrite {
                    // The prompt path: unconditional. F7: a cancelled write is settled from one store re-read.
                    val outcome = settleCancelledWrite(AppLockSettingField.AUTO_LOCK_TIMEOUT, { it.autoLockTimeout == timeout }) {
                        writeTimeout(timeout, conditional = false)
                    }
                    // The state-move entry comes BEFORE the completion call (S17 binding). It applies the timeout from
                    // its own token, bound to attemptId; a wrong or reused id is a no-op.
                    outcome.applyThenRethrow { result ->
                        controller.onOpenOriginSettingApplied(attemptId, result is AppLockSettingsWriteResult.Committed)
                    }
                }
            }
        } finally {
            try {
                controller.onOpenOriginApplyCompleted(attemptId)
            } finally {
                applyRunning.set(false)
            }
        }
    }

    /** True while the controller holds an open CONFIRM_CHANGE prompt (the state is the source; no flag can stick). */
    private fun isConfirmChangeOpen(): Boolean =
        (controller.state.value as? AppLockState.Authenticating)?.purpose == AuthPurpose.CONFIRM_CHANGE

    /** The durable timeout write. Any non-cancellation failure is a failed write (bounded: class name only in the log). */
    private suspend fun writeTimeout(timeout: AutoLockTimeout, conditional: Boolean): AppLockSettingsWriteResult =
        try {
            if (conditional) {
                settingsRepository.setAutoLockTimeoutIfNotWeakening(timeout)
            } else {
                settingsRepository.setAutoLockTimeout(timeout)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("App-lock timeout write failed: class=%s", DiagnosticThrowableSummary.className(e))
            AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT)
        }

    /** The committed settings, or null when unreadable or the read threw (fail closed). Cancellation propagates. */
    private suspend fun readCommittedSettings(): AppLockPrivacySettings? =
        try {
            when (val state = settingsRepository.getLoadState()) {
                is AppLockSettingsLoadState.Loaded -> state.settings
                is AppLockSettingsLoadState.FirstRunDefault -> state.settings
                is AppLockSettingsLoadState.Unreadable -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("App-lock timeout pre-read failed: class=%s", DiagnosticThrowableSummary.className(e))
            null
        }

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
                // S18 i-b2 privacy review C1: the pre-read, the write, the state move and the trigger are one critical
                // section with the plain discreet / secure-screen path, the timeout path and the settings ViewModel's
                // writes (the shared [writeSerializer]), so a plain discreet-off write that commits between the
                // pre-read and the lock write cannot leave a stale "was discreet" that skips the sweep. No prompt is
                // held inside the serializer (the prompt closed before the apply started).
                writeSerializer.withExclusiveWrite {
                    val enable = prompt == OpenOriginPrompt.ENABLE
                    val wasEffectiveDiscreet = if (enable) readEffectiveDiscreetBeforeWrite() else false
                    // F7: a cancelled write is settled from one store re-read (a committed value runs the normal
                    // committed path, anything else moves nothing); the cancellation is rethrown after the outcome.
                    val outcome = settleCancelledWrite(AppLockSettingField.APP_LOCK_ENABLED, { it.appLockEnabled == enable }) {
                        write(enable)
                    }
                    outcome.applyThenRethrow { result ->
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
     * The effective discreet value before the lock write, mapped as the retired `UpdateDiscreetNotificationsUseCase`
     * did (F1, INV-13; retired in S20-d, D4): `Loaded` and `FirstRunDefault` give the effective value; `Unreadable` is NOT evidence that
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
