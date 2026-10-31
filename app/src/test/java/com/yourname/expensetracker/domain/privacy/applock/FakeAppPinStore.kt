package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStore
import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStoreState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinChangeResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDeleteResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinSetupResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinStoreWriteResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifyResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

/** One recorded call to [FakeAppPinStore]. [arrays] are the very objects the caller passed (never zeroed by the fake). */
internal class PinStoreCall(val op: String, val arrays: List<CharArray>, val contentAtCall: List<String>) {
    /** True when every passed array is all NUL right now: the caller zeroed what it handed over. */
    fun allZeroedNow(): Boolean = arrays.isNotEmpty() && arrays.all { array -> array.all { it == '\u0000' } }

    override fun toString(): String = "PinStoreCall(op=$op)"
}

/**
 * Scriptable in-memory [AppPinStore] for controller tests (S25, reused by S27 and later parts).
 *
 * - State: [stateFlow] is what [readState] returns and what [observeState] emits. Like the real store, [observeState]
 *   ENDS after emitting an UNREADABLE state (G24-5); [observeCalls] counts how many collections were started.
 * - Scripts: [verifyScript] (consumed in order; [defaultVerify] when empty), [setupResult], [changeResult],
 *   [deleteResult], [resetResult].
 * - Order: [log] holds the operation names in call order; [calls] holds the arguments. The fake NEVER zeroes an array,
 *   so a test can prove the caller did ([PinStoreCall.allZeroedNow]).
 * - Failure injection: [failures] throws the given throwable from the named operation (after recording it); [verifyGate]
 *   makes [verify] suspend until completed (for cancellation tests).
 */
internal class FakeAppPinStore(initial: AppPinStoreState = AppPinStoreState.ABSENT) : AppPinStore {
    val stateFlow = MutableStateFlow(initial)

    val verifyScript = ArrayDeque<PinVerifyResult>()
    var defaultVerify: PinVerifyResult = PinVerifyResult.Absent
    var setupResult: PinSetupResult = PinSetupResult.DONE
    var changeResult: PinChangeResult = PinChangeResult.Changed
    var deleteResult: PinDeleteResult = PinDeleteResult.DONE
    var resetResult: PinStoreWriteResult = PinStoreWriteResult.DONE

    /** Operation name ("readState", "verify", "setup", "changePin", "deleteAll", "resetAttempts", ...) to what it throws. */
    val failures = mutableMapOf<String, Throwable>()

    /** When set, [verify] records the call and then awaits this before answering. */
    var verifyGate: CompletableDeferred<Unit>? = null

    /** When set, [readState] records the call and then awaits this before answering (a hung PIN-store read). */
    var readGate: CompletableDeferred<Unit>? = null

    val log = mutableListOf<String>()
    val calls = mutableListOf<PinStoreCall>()
    var observeCalls = 0
        private set

    /** Jobs that collected [observeState]: the long-lived PIN observer is an expected child of the controller scope. */
    val observerJobs = mutableSetOf<Job>()

    /** Convenience: sets the committed verifier state (and flags) that readState / observeState report. */
    fun setVerifier(
        verifier: PinVerifierState,
        biometricEnabled: Boolean = false,
        noticePending: Boolean = false
    ) {
        stateFlow.value = AppPinStoreState(verifier, biometricEnabled, noticePending)
    }

    fun count(op: String): Int = log.count { it == op }

    fun callsOf(op: String): List<PinStoreCall> = calls.filter { it.op == op }

    private fun record(op: String, vararg arrays: CharArray) {
        log += op
        calls += PinStoreCall(op, arrays.toList(), arrays.map { String(it) })
        failures[op]?.let { throw it }
    }

    override suspend fun readState(): AppPinStoreState {
        record("readState")
        readGate?.await()
        return stateFlow.value
    }

    override fun observeState(): Flow<AppPinStoreState> = flow {
        observeCalls++
        log += "observeState"
        failures["observeState"]?.let { throw it }
        currentCoroutineContext()[Job]?.let { observerJobs += it }
        stateFlow.transformWhile { state ->
            emit(state)
            state.verifier != PinVerifierState.UNREADABLE
        }.collect { emit(it) }
    }

    override suspend fun setup(pin: CharArray): PinSetupResult {
        record("setup", pin)
        return setupResult
    }

    override suspend fun changePin(currentPin: CharArray, newPin: CharArray): PinChangeResult {
        record("changePin", currentPin, newPin)
        return changeResult
    }

    override suspend fun verify(pin: CharArray): PinVerifyResult {
        record("verify", pin)
        verifyGate?.await()
        return if (verifyScript.isEmpty()) defaultVerify else verifyScript.removeFirst()
    }

    override suspend fun resetAttempts(): PinStoreWriteResult {
        record("resetAttempts")
        return resetResult
    }

    /** S26b: every `setBiometricEnabled` argument, in order. */
    val biometricWrites = mutableListOf<Boolean>()

    /** S26b: the result of `setBiometricEnabled` (and of the notice dismissal); the default commits. */
    var biometricWriteResult: PinStoreWriteResult = PinStoreWriteResult.DONE

    /**
     * S26b: when true, a DONE flag write updates [stateFlow] like the real store (enable sets the flag, dismiss clears the
     * pending notice). False by default so earlier tests are unchanged.
     */
    var applyFlagWrites = false

    override suspend fun setBiometricEnabled(enabled: Boolean): PinStoreWriteResult {
        record("setBiometricEnabled")
        biometricWrites += enabled
        if (applyFlagWrites && biometricWriteResult == PinStoreWriteResult.DONE) {
            stateFlow.value = stateFlow.value.copy(biometricEnabled = enabled)
        }
        return biometricWriteResult
    }

    override suspend fun recordBiometricInvalidated(): PinStoreWriteResult {
        record("recordBiometricInvalidated")
        return PinStoreWriteResult.DONE
    }

    override suspend fun dismissBiometricInvalidatedNotice(): PinStoreWriteResult {
        record("dismissBiometricInvalidatedNotice")
        if (applyFlagWrites) stateFlow.value = stateFlow.value.copy(biometricInvalidatedNoticePending = false)
        return PinStoreWriteResult.DONE
    }

    override suspend fun deleteAll(): PinDeleteResult {
        record("deleteAll")
        return deleteResult
    }
}
