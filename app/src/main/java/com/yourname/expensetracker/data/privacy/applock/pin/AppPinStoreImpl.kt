package com.yourname.expensetracker.data.privacy.applock.pin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yourname.expensetracker.di.AppPinDataStore
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.privacy.applock.MonotonicClock
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStore
import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStoreState
import com.yourname.expensetracker.domain.privacy.applock.pin.BiometricPinKeyManager
import com.yourname.expensetracker.domain.privacy.applock.pin.BootCountSource
import com.yourname.expensetracker.domain.privacy.applock.pin.PinAttemptPolicy
import com.yourname.expensetracker.domain.privacy.applock.pin.PinAttemptState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinChangeResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinCounterOrigin
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayEvaluation
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayReason
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDeleteResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinFormatPolicy
import com.yourname.expensetracker.domain.privacy.applock.pin.PinFormatResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinSetupResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinStoreWriteResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifyResult
import com.yourname.expensetracker.domain.privacy.applock.pin.VerifierUnreadableCause
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * The only owner of the app PIN verifier, the shared attempt state and the PIN-mode flags (INV-42, S24).
 *
 * Own dedicated DataStore (`app_lock_pin_store`, noBackupFilesDir, NO corruption handler: an unreadable store is
 * present-but-unreadable, never absent and never auto-wiped). Every entry point is serialised by one [Mutex].
 * Verification is write-ahead (INV-39): the shared counter is persisted BEFORE the PIN is evaluated.
 *
 * Privacy (INV-38): a PIN is a [CharArray] zeroed in `finally`, never a String, never logged. The only diagnostics are
 * the three PIN codes, with no metadata. No exception text is kept anywhere.
 */
@Singleton
class AppPinStoreImpl @Inject constructor(
    @AppPinDataStore private val store: DataStore<Preferences>,
    private val keyBinder: PinKeyBinder,
    private val biometricKeyManager: BiometricPinKeyManager,
    private val bootCountSource: BootCountSource,
    private val clock: MonotonicClock,
    private val diagnostics: PrivacyDiagnosticsEmitter,
    @IoDispatcher private val dispatcher: CoroutineDispatcher
) : AppPinStore {

    private val mutex = Mutex()

    /** True until the first attempt-delay evaluation in this process that had an active delay (design decision 3). */
    private var firstEvaluationInProcess = true

    // ---- state ----

    override suspend fun readState(): AppPinStoreState = withContext(dispatcher) {
        val prefs = readPrefs()
        if (prefs == null) UNREADABLE_STATE else stateOf(prefs)
    }

    override fun observeState(): Flow<AppPinStoreState> =
        store.data.map { stateOf(it) }.catch { error ->
            if (error is CancellationException) throw error
            emit(UNREADABLE_STATE)
        }.distinctUntilChanged()

    // ---- setup / change ----

    override suspend fun setup(pin: CharArray): PinSetupResult {
        try {
            if (PinFormatPolicy.validate(pin) != PinFormatResult.VALID) return PinSetupResult.INVALID_FORMAT
            return withContext(dispatcher) { mutex.withLock { setupLocked(pin) } }
        } finally {
            pin.fill('\u0000')
        }
    }

    private suspend fun setupLocked(pin: CharArray): PinSetupResult {
        // G24-2: never touch the key while a verifier exists (readable or not) or the store cannot be read.
        val existing = readPrefs()
        if (existing == null || existing[K_VERIFIER] != null) return PinSetupResult.FAILED
        val key = when (val outcome = keyBinder.createForSetup()) {
            is PinKeyOutcome.Available -> outcome.key
            is PinKeyOutcome.Unavailable -> return PinSetupResult.FAILED
        }
        var record: PinVerifierRecord? = null
        try {
            record = buildRecord(pin, key)
            val raw = record.serialize()
            store.edit { prefs ->
                prefs.clear()
                prefs[K_VERIFIER] = raw
                putAttempt(prefs, PinAttemptPolicy.CLEAN)
                prefs[K_BIOMETRIC_ENABLED] = false
                prefs[K_BIOMETRIC_NOTICE] = false
            }
            return PinSetupResult.DONE
        } catch (e: Exception) {
            // G24-1: cleanup runs under NonCancellable first; a CancellationException is
            // then rethrown unchanged, anything else is a bounded FAILED.
            deleteKeyIfNoVerifier()
            if (e is CancellationException) throw e
            return PinSetupResult.FAILED
        } finally {
            record?.wipe()
        }
    }

    /** G24-1: the edit may have committed before the failure; a verifier without its key is a permanent lockout. */
    private suspend fun deleteKeyIfNoVerifier() {
        withContext(NonCancellable) {
            val prefs = readPrefs()
            if (prefs != null && prefs[K_VERIFIER] == null) keyBinder.delete()
        }
    }

    override suspend fun changePin(currentPin: CharArray, newPin: CharArray): PinChangeResult {
        try {
            if (PinFormatPolicy.validate(newPin) != PinFormatResult.VALID) return PinChangeResult.InvalidFormat
            return withContext(dispatcher) { mutex.withLock { changeLocked(currentPin, newPin) } }
        } finally {
            currentPin.fill('\u0000')
            newPin.fill('\u0000')
        }
    }

    private suspend fun changeLocked(currentPin: CharArray, newPin: CharArray): PinChangeResult {
        val verified = verifyLocked(currentPin, allowRehash = false)
        if (verified != PinVerifyResult.Correct) return PinChangeResult.NotVerified(verified)
        val key = when (val outcome = keyBinder.getExisting()) {
            is PinKeyOutcome.Available -> outcome.key
            is PinKeyOutcome.Unavailable -> return PinChangeResult.WriteFailed
        }
        var record: PinVerifierRecord? = null
        try {
            record = buildRecord(newPin, key)
            val raw = record.serialize()
            store.edit { prefs -> prefs[K_VERIFIER] = raw }
            return PinChangeResult.Changed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PinChangeResult.WriteFailed
        } finally {
            record?.wipe()
        }
    }

    // ---- verify ----

    override suspend fun verify(pin: CharArray): PinVerifyResult {
        try {
            return withContext(dispatcher) { mutex.withLock { verifyLocked(pin, allowRehash = true) } }
        } finally {
            pin.fill('\u0000')
        }
    }

    private suspend fun verifyLocked(pin: CharArray, allowRehash: Boolean): PinVerifyResult {
        val prefs = readPrefs() ?: return verifierUnreadable(VerifierUnreadableCause.RECORD_UNREADABLE)
        val raw = prefs[K_VERIFIER] ?: return PinVerifyResult.Absent
        val record = PinVerifierRecord.parse(raw)
            ?: return verifierUnreadable(VerifierUnreadableCause.RECORD_UNREADABLE)
        try {
            val now = clock.elapsedRealtimeMillis()
            val boot = bootCountSource.currentBootCount()
            val attempt = readAttempt(prefs)
            if (attempt == null) {
                // AMD-11: an unreadable attempt state is repaired (4 failures, one 30 s delay), never wiped.
                val repaired = PinAttemptPolicy.repairedState(now, boot)
                if (!writeAttempt(repaired)) return PinVerifyResult.Unavailable
                firstEvaluationInProcess = false
                emitSafe(PrivacyDiagnosticCode.PIN_ATTEMPT_STATE_UNREADABLE)
                return PinVerifyResult.Delayed(PinDelayReason.ATTEMPT_STATE_UNREADABLE, repaired.currentDelayMillis)
            }
            val delayResult = evaluateDelay(attempt, now, boot)
            if (delayResult != null) return delayResult

            val key = when (val outcome = keyBinder.getExisting()) {
                is PinKeyOutcome.Available -> outcome.key
                is PinKeyOutcome.Unavailable -> return verifierUnreadable(outcome.cause)
            }
            val mac = try {
                PinVerifierCrypto.newMac(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return verifierUnreadable(VerifierUnreadableCause.KEYSTORE_ERROR)
            }

            // Write-ahead (INV-39): the failure is durable BEFORE the PIN is evaluated.
            val next = PinAttemptPolicy.afterFailure(attempt, now, boot)
            if (!writeAttempt(next)) return PinVerifyResult.Unavailable

            val matches = try {
                matches(pin, record, mac)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // G24-3: best-effort restore of the previous attempt state (one write, failure ignored).
                writeAttempt(attempt)
                return verifierUnreadable(VerifierUnreadableCause.KEYSTORE_ERROR)
            }
            if (!matches) return PinVerifyResult.Wrong(PinAttemptPolicy.tierOf(next))

            writeAttempt(PinAttemptPolicy.CLEAN) // a failed reset write still unlocks
            if (allowRehash && record.isOutdated()) rehash(pin, mac)
            return PinVerifyResult.Correct
        } finally {
            record.wipe()
        }
    }

    /** Null when the entry may be evaluated; otherwise the rejection to return. */
    private suspend fun evaluateDelay(attempt: PinAttemptState, now: Long, boot: Int?): PinVerifyResult? {
        val evaluation = PinAttemptPolicy.evaluate(attempt, now, boot, firstEvaluationInProcess)
        if (evaluation is PinDelayEvaluation.Restarted && !writeAttempt(evaluation.state)) {
            return PinVerifyResult.Unavailable
        }
        if (attempt.currentDelayMillis > 0L) firstEvaluationInProcess = false
        return when (evaluation) {
            PinDelayEvaluation.Idle -> null
            is PinDelayEvaluation.Active -> PinVerifyResult.Delayed(evaluation.reason, evaluation.remainingMillis)
            is PinDelayEvaluation.Restarted -> PinVerifyResult.Delayed(evaluation.reason, evaluation.remainingMillis)
        }
    }

    private fun matches(pin: CharArray, record: PinVerifierRecord, mac: Mac): Boolean {
        if (pin.isEmpty() || pin.size > PinFormatPolicy.MAX_LENGTH) return false
        val derived = PinVerifierCrypto.derive(pin, record.salt, record.iterations)
        var expected: ByteArray? = null
        try {
            expected = PinVerifierCrypto.computeTag(mac, record.iterations, record.salt, derived)
            return PinVerifierCrypto.constantTimeEquals(expected, record.tag)
        } finally {
            derived.fill(0)
            expected?.fill(0)
        }
    }

    /** Best effort: a failed rehash never blocks the unlock. */
    private suspend fun rehash(pin: CharArray, mac: Mac) {
        var record: PinVerifierRecord? = null
        try {
            record = buildRecord(pin, mac)
            val raw = record.serialize()
            store.edit { prefs -> prefs[K_VERIFIER] = raw }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ignored on purpose; the old record stays valid.
        } finally {
            record?.wipe()
        }
    }

    // ---- attempts and flags ----

    override suspend fun resetAttempts(): PinStoreWriteResult =
        withContext(dispatcher) { mutex.withLock { writeResult { putAttemptEdit(PinAttemptPolicy.CLEAN) } } }

    override suspend fun setBiometricEnabled(enabled: Boolean): PinStoreWriteResult =
        withContext(dispatcher) {
            mutex.withLock { writeResult { store.edit { it[K_BIOMETRIC_ENABLED] = enabled } } }
        }

    override suspend fun recordBiometricInvalidated(): PinStoreWriteResult =
        withContext(dispatcher) {
            mutex.withLock {
                val result = writeResult {
                    store.edit {
                        it[K_BIOMETRIC_ENABLED] = false
                        it[K_BIOMETRIC_NOTICE] = true
                    }
                }
                if (result == PinStoreWriteResult.DONE) emitSafe(PrivacyDiagnosticCode.PIN_BIOMETRIC_INVALIDATED)
                result
            }
        }

    override suspend fun dismissBiometricInvalidatedNotice(): PinStoreWriteResult =
        withContext(dispatcher) {
            mutex.withLock { writeResult { store.edit { it[K_BIOMETRIC_NOTICE] = false } } }
        }

    // ---- deletion (INV-49) ----

    override suspend fun deleteAll(): PinDeleteResult = withContext(dispatcher) {
        mutex.withLock {
            // Step 1 is the only decisive step. A failure leaves the caller's settings untouched.
            try {
                store.edit { it.clear() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock PinDeleteResult.FAILED
            }
            // Step 2: best effort.
            keyBinder.delete()
            // Step 3 (S26): the fingerprint key, best effort. deleteKey() never throws except cancellation.
            biometricKeyManager.deleteKey()
            PinDeleteResult.DONE
        }
    }

    // ---- helpers ----

    private fun buildRecord(pin: CharArray, key: SecretKey): PinVerifierRecord =
        buildRecord(pin, PinVerifierCrypto.newMac(key))

    private fun buildRecord(pin: CharArray, mac: Mac): PinVerifierRecord {
        val salt = PinVerifierCrypto.newSalt()
        val iterations = PinVerifierCrypto.CURRENT_ITERATIONS
        val derived = PinVerifierCrypto.derive(pin, salt, iterations)
        try {
            val tag = PinVerifierCrypto.computeTag(mac, iterations, salt, derived)
            return PinVerifierRecord(
                PinVerifierRecord.CURRENT_RECORD_VERSION,
                PinVerifierCrypto.CURRENT_ALG_ID,
                iterations,
                salt,
                PinVerifierRecord.CURRENT_KEY_ALIAS_VERSION,
                tag
            )
        } finally {
            derived.fill(0)
        }
    }

    /** One fresh read; null means the store cannot be read (corrupt or I/O): present-but-unreadable. */
    private suspend fun readPrefs(): Preferences? = try {
        store.data.first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun writeAttempt(state: PinAttemptState): Boolean =
        writeResult { putAttemptEdit(state) } == PinStoreWriteResult.DONE

    private suspend fun putAttemptEdit(state: PinAttemptState) {
        store.edit { putAttempt(it, state) }
    }

    private suspend fun writeResult(block: suspend () -> Unit): PinStoreWriteResult = try {
        block()
        PinStoreWriteResult.DONE
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PinStoreWriteResult.FAILED
    }

    private suspend fun verifierUnreadable(cause: VerifierUnreadableCause): PinVerifyResult {
        emitSafe(PrivacyDiagnosticCode.PIN_VERIFIER_UNREADABLE)
        return PinVerifyResult.VerifierUnreadable(cause)
    }

    private suspend fun emitSafe(code: PrivacyDiagnosticCode) {
        try {
            diagnostics.emit(code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Diagnostics are best effort and never decide a lock outcome.
        }
    }

    private fun stateOf(prefs: Preferences): AppPinStoreState {
        val raw = prefs[K_VERIFIER]
        val verifier = when {
            raw == null -> PinVerifierState.ABSENT
            else -> PinVerifierRecord.parse(raw)?.let {
                it.wipe()
                PinVerifierState.PRESENT
            } ?: PinVerifierState.UNREADABLE
        }
        return AppPinStoreState(
            verifier = verifier,
            biometricEnabled = prefs[K_BIOMETRIC_ENABLED] ?: false,
            biometricInvalidatedNoticePending = prefs[K_BIOMETRIC_NOTICE] ?: false
        )
    }

    private fun putAttempt(prefs: MutablePreferences, state: PinAttemptState) {
        prefs[K_FAILURES] = state.consecutiveFailures
        prefs[K_DELAY_MS] = state.currentDelayMillis
        prefs[K_STARTED_ELAPSED] = state.delayStartedElapsed
        prefs[K_BOOT_COUNT] = state.bootCount
        prefs[K_ORIGIN] = when (state.counterOrigin) {
            PinCounterOrigin.FAILURES -> ORIGIN_FAILURES
            PinCounterOrigin.REPAIRED_UNREADABLE -> ORIGIN_REPAIRED
        }
    }

    /** Null when any attempt key is missing or a value is implausible (AMD-11: repaired, not wiped). */
    private fun readAttempt(prefs: Preferences): PinAttemptState? {
        val failures = prefs[K_FAILURES] ?: return null
        val delay = prefs[K_DELAY_MS] ?: return null
        val started = prefs[K_STARTED_ELAPSED] ?: return null
        val boot = prefs[K_BOOT_COUNT] ?: return null
        val origin = when (prefs[K_ORIGIN]) {
            ORIGIN_FAILURES -> PinCounterOrigin.FAILURES
            ORIGIN_REPAIRED -> PinCounterOrigin.REPAIRED_UNREADABLE
            else -> return null
        }
        val state = PinAttemptState(failures, delay, started, boot, origin)
        return if (PinAttemptPolicy.isPlausible(state)) state else null
    }

    private companion object {
        val UNREADABLE_STATE = AppPinStoreState(PinVerifierState.UNREADABLE, false, false)
        const val ORIGIN_FAILURES = 0
        const val ORIGIN_REPAIRED = 1

        val K_VERIFIER = stringPreferencesKey("pin_verifier")
        val K_FAILURES = intPreferencesKey("pin_attempt_failures")
        val K_DELAY_MS = longPreferencesKey("pin_attempt_delay_ms")
        val K_STARTED_ELAPSED = longPreferencesKey("pin_attempt_started_elapsed")
        val K_BOOT_COUNT = intPreferencesKey("pin_attempt_boot_count")
        val K_ORIGIN = intPreferencesKey("pin_attempt_origin")
        val K_BIOMETRIC_ENABLED = booleanPreferencesKey("pin_biometric_enabled")
        val K_BIOMETRIC_NOTICE = booleanPreferencesKey("pin_biometric_notice_pending")
    }
}
