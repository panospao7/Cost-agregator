package com.yourname.expensetracker.data.privacy.applock.pin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yourname.expensetracker.domain.privacy.applock.MonotonicClock
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStoreState
import com.yourname.expensetracker.domain.privacy.applock.pin.BootCountSource
import com.yourname.expensetracker.domain.privacy.applock.pin.PinChangeResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayReason
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayTier
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDeleteResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinSetupResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinStoreWriteResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifyResult
import com.yourname.expensetracker.domain.privacy.applock.pin.VerifierUnreadableCause
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S24 [AppPinStoreImpl] over a real file DataStore (INV-38, INV-39, INV-42, INV-49, INV-51;
 * AE-71, AE-73, AE-74, AE-75, AE-84, AE-91, AE-92, AE-100, AE-106).
 *
 * AndroidKeyStore does not exist on the JVM, so the key comes from [FakePinKeyBinder]. Each
 * "process restart" is a new [AppPinStoreImpl] over the SAME file store. The persisted key names
 * below are the on-disk schema, used only to inspect or seed the store.
 */
class AppPinStoreImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var real: DataStore<Preferences>
    private lateinit var faulty: FaultyStore
    private lateinit var file: File
    private val binder = FakePinKeyBinder()
    private val clock = FakeClock(10_000L)
    private val boot = FakeBootCount(5)
    private val emitted = mutableListOf<PrivacyDiagnosticCode>()
    private var emitFailure: Throwable? = null
    private val emitter = mockk<PrivacyDiagnosticsEmitter>().also { mock ->
        coEvery { mock.emit(any(), any()) } coAnswers {
            emitted += firstArg<PrivacyDiagnosticCode>()
            emitFailure?.let { throw it }
        }
    }
    private lateinit var store: AppPinStoreImpl

    private val kVerifier = stringPreferencesKey("pin_verifier")
    private val kFailures = intPreferencesKey("pin_attempt_failures")
    private val kDelay = longPreferencesKey("pin_attempt_delay_ms")
    private val kStarted = longPreferencesKey("pin_attempt_started_elapsed")
    private val kBoot = intPreferencesKey("pin_attempt_boot_count")
    private val kOrigin = intPreferencesKey("pin_attempt_origin")

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        file = File(tmp.root, "app_lock_pin_store_test.preferences_pb")
        real = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        faulty = FaultyStore(real)
        store = newStore()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A new process over the same persisted store (the in-memory first-evaluation flag starts true again). */
    private fun newStore() = AppPinStoreImpl(faulty, binder, boot, clock, emitter, Dispatchers.IO)

    private fun pin(text: String = GOOD) = text.toCharArray()

    private fun prefs(): Preferences = runBlocking { real.data.first() }

    private fun failures(): Int? = prefs()[kFailures]

    private fun setUpPin() = runBlocking { assertEquals(PinSetupResult.DONE, store.setup(pin())) }

    private fun wrong(times: Int) = runBlocking { repeat(times) { assertTrue(store.verify(pin(WRONG)) is PinVerifyResult.Wrong) } }

    // ---- INV-38 / AE-71: setup ----

    @Test
    fun setup_then_verify_accepts_the_pin_and_rejects_another() = runBlocking {
        assertEquals(PinSetupResult.DONE, store.setup(pin()))

        assertEquals(PinVerifyResult.Correct, store.verify(pin()))
        assertEquals(PinVerifyResult.Wrong(PinDelayTier.NONE), store.verify(pin(WRONG)))
        assertEquals(PinVerifierState.PRESENT, store.readState().verifier)
    }

    @Test
    fun verify_without_a_verifier_is_absent_and_emits_nothing() = runBlocking {
        assertEquals(PinVerifyResult.Absent, store.verify(pin()))
        assertEquals(AppPinStoreState.ABSENT, store.readState())
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun setup_and_verify_zero_the_caller_pin_array_on_every_path() = runBlocking {
        val setupPin = pin()
        store.setup(setupPin)
        val correct = pin()
        store.verify(correct)
        val incorrect = pin(WRONG)
        store.verify(incorrect)
        val delayedWhileActive = pin()
        wrong(4) // with the one wrong PIN above this is the fifth failure: the 30 s delay starts
        store.verify(delayedWhileActive)
        val badFormat = pin("12")
        store.setup(badFormat)

        listOf(setupPin, correct, incorrect, delayedWhileActive, badFormat).forEach { array ->
            assertTrue(array.all { it == '\u0000' })
        }
    }

    @Test
    fun setup_rejects_an_invalid_format_without_creating_a_key_or_writing() = runBlocking {
        val writesBefore = faulty.writeCount.get()

        assertEquals(PinSetupResult.INVALID_FORMAT, store.setup(pin("111111")))
        assertEquals(PinSetupResult.INVALID_FORMAT, store.setup(pin("12ab56")))

        assertEquals(0, binder.createCount)
        assertEquals(writesBefore, faulty.writeCount.get())
        assertEquals(PinVerifierState.ABSENT, store.readState().verifier)
    }

    @Test
    fun setup_clears_stale_preferences_and_writes_everything_in_one_edit() = runBlocking {
        // G24-2: setup never runs over an existing verifier (see g24_2_*), so the stale state
        // here is leftover attempt data, a biometric flag and a foreign key with NO verifier.
        real.edit {
            it[stringPreferencesKey("stale_foreign_key")] = "stale"
            it[kFailures] = 2
            it[kDelay] = 30_000L
            it[androidx.datastore.preferences.core.booleanPreferencesKey("pin_biometric_enabled")] = true
        }
        val writesBefore = faulty.writeCount.get()

        assertEquals(PinSetupResult.DONE, store.setup(pin("975318")))

        assertEquals(1, faulty.writeCount.get() - writesBefore)
        val after = prefs()
        assertNull(after[stringPreferencesKey("stale_foreign_key")])
        assertEquals(0, after[kFailures])
        assertEquals(0L, after[kDelay])
        assertEquals(AppPinStoreState(PinVerifierState.PRESENT, false, false), store.readState())
        assertEquals(PinVerifyResult.Correct, store.verify(pin("975318")))
    }

    @Test
    fun the_pin_never_appears_as_plaintext_in_the_store_file() = runBlocking {
        store.setup(pin())
        store.verify(pin(WRONG))
        store.verify(pin())
        store.setBiometricEnabled(true)

        val bytes = file.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)

        assertTrue(bytes.isNotEmpty())
        assertFalse(text.contains(GOOD))
        assertFalse(text.contains(WRONG))
    }

    // ---- INV-39 / AE-73: write-ahead counter and delays ----

    @Test
    fun four_wrong_pins_carry_no_delay_and_the_fifth_starts_30_seconds_persisted_before_the_result() = runBlocking {
        setUpPin()

        repeat(4) { index ->
            assertEquals(PinVerifyResult.Wrong(PinDelayTier.NONE), store.verify(pin(WRONG)))
            assertEquals(index + 1, failures())
        }
        assertEquals(0L, prefs()[kDelay])

        assertEquals(PinVerifyResult.Wrong(PinDelayTier.S30), store.verify(pin(WRONG)))

        val persisted = prefs()
        assertEquals(5, persisted[kFailures])
        assertEquals(30_000L, persisted[kDelay])
        assertEquals(10_000L, persisted[kStarted])
        assertEquals(5, persisted[kBoot])
        assertEquals(0, persisted[kOrigin])
    }

    @Test
    fun an_active_delay_rejects_even_the_correct_pin_without_evaluating_or_counting() = runBlocking {
        setUpPin()
        wrong(5)

        val rejected = store.verify(pin())

        assertEquals(PinDelayReason.FAILURES, (rejected as PinVerifyResult.Delayed).reason)
        assertEquals(30_000L, rejected.remainingMillis)
        assertEquals(5, failures())
        clock.advance(30_000L)
        assertEquals(PinVerifyResult.Wrong(PinDelayTier.M1), store.verify(pin(WRONG)))
    }

    @Test
    fun a_failed_counter_write_gives_unavailable_and_never_unlocks_with_the_correct_pin() = runBlocking {
        setUpPin()
        val before = prefs()
        faulty.failWrite = IOException("injected")

        assertEquals(PinVerifyResult.Unavailable, store.verify(pin()))
        assertEquals(PinVerifyResult.Unavailable, store.verify(pin(WRONG)))

        faulty.failWrite = null
        assertEquals(before, prefs())
        assertEquals(PinVerifierState.PRESENT, store.readState().verifier)
        assertEquals(PinVerifyResult.Correct, store.verify(pin()))
    }

    @Test
    fun the_delay_survives_a_process_restart_with_the_remaining_time() = runBlocking {
        setUpPin()
        wrong(5)
        clock.advance(10_000L)
        store = newStore()

        val rejected = store.verify(pin()) as PinVerifyResult.Delayed

        assertEquals(PinDelayReason.FAILURES, rejected.reason)
        assertEquals(20_000L, rejected.remainingMillis)
        clock.advance(20_000L)
        assertEquals(PinVerifyResult.Correct, store.verify(pin()))
    }

    @Test
    fun a_reboot_with_a_changed_boot_count_restarts_the_tier_in_full_and_persists_the_anchor() = runBlocking {
        setUpPin()
        wrong(5)
        boot.value = 6
        clock.now = 2_000L
        store = newStore()

        val restarted = store.verify(pin()) as PinVerifyResult.Delayed

        assertEquals(30_000L, restarted.remainingMillis)
        val persisted = prefs()
        assertEquals(2_000L, persisted[kStarted])
        assertEquals(6, persisted[kBoot])
        assertEquals(5, persisted[kFailures])
        clock.advance(10_000L)
        assertEquals(20_000L, (store.verify(pin()) as PinVerifyResult.Delayed).remainingMillis)
        clock.advance(20_000L)
        assertEquals(PinVerifyResult.Correct, store.verify(pin()))
    }

    @Test
    fun elapsed_time_below_the_stored_start_is_a_reboot_even_with_an_unchanged_boot_count() = runBlocking {
        setUpPin()
        wrong(5)
        clock.now = 3_000L
        store = newStore()

        val restarted = store.verify(pin()) as PinVerifyResult.Delayed

        assertEquals(30_000L, restarted.remainingMillis)
        assertEquals(3_000L, prefs()[kStarted])
    }

    @Test
    fun an_unknown_boot_count_re_anchors_once_per_process_then_uses_elapsed_time() = runBlocking {
        boot.value = null
        setUpPin()
        wrong(5)
        assertEquals(-1, prefs()[kBoot])
        clock.advance(10_000L)

        val first = store.verify(pin()) as PinVerifyResult.Delayed
        assertEquals(30_000L, first.remainingMillis)
        assertEquals(20_000L, prefs()[kStarted])
        clock.advance(10_000L)
        val sameProcess = store.verify(pin()) as PinVerifyResult.Delayed
        assertEquals(20_000L, sameProcess.remainingMillis)

        store = newStore()
        clock.advance(5_000L)
        val nextProcess = store.verify(pin()) as PinVerifyResult.Delayed
        assertEquals(30_000L, nextProcess.remainingMillis)
        clock.advance(30_000L)
        assertEquals(PinVerifyResult.Correct, store.verify(pin()))
    }

    @Test
    fun a_stored_unknown_boot_count_re_anchors_once_and_then_adopts_the_known_count() = runBlocking {
        boot.value = null
        setUpPin()
        wrong(5)
        boot.value = 7
        clock.advance(10_000L)
        store = newStore()

        assertEquals(30_000L, (store.verify(pin()) as PinVerifyResult.Delayed).remainingMillis)
        assertEquals(7, prefs()[kBoot])
        clock.advance(10_000L)
        assertEquals(20_000L, (store.verify(pin()) as PinVerifyResult.Delayed).remainingMillis)
    }

    // ---- INV-39 / AE-74 / AE-75: reset and no wipe ----

    @Test
    fun a_correct_pin_resets_the_persisted_counter_to_zero() = runBlocking {
        setUpPin()
        wrong(3)

        assertEquals(PinVerifyResult.Correct, store.verify(pin()))

        assertEquals(0, failures())
        assertEquals(0L, prefs()[kDelay])
        wrong(4)
        assertEquals(4, failures())
    }

    @Test
    fun a_correct_pin_after_the_delay_elapsed_unlocks_and_resets() = runBlocking {
        setUpPin()
        wrong(5)
        clock.advance(30_000L)

        assertEquals(PinVerifyResult.Correct, store.verify(pin()))

        assertEquals(0, failures())
        assertEquals(0L, prefs()[kDelay])
    }

    @Test
    fun a_failed_reset_write_after_a_correct_pin_still_unlocks() = runBlocking {
        setUpPin()
        faulty.failWritesAfter(1) // the write-ahead increment succeeds, the reset fails

        assertEquals(PinVerifyResult.Correct, store.verify(pin()))

        assertEquals(1, failures())
    }

    @Test
    fun many_wrong_pins_never_wipe_anything_and_the_delay_is_capped_at_60_minutes() = runBlocking {
        setUpPin()
        repeat(12) {
            clock.advance(3_600_001L)
            assertTrue(store.verify(pin(WRONG)) is PinVerifyResult.Wrong)
        }

        assertEquals(12, failures())
        assertEquals(3_600_000L, prefs()[kDelay])
        assertEquals(PinVerifierState.PRESENT, store.readState().verifier)
        assertNotNull(binder.key)
        assertEquals(0, binder.deleteCount)
        assertEquals(3_600_000L, (store.verify(pin()) as PinVerifyResult.Delayed).remainingMillis)
        clock.advance(3_600_001L)
        assertEquals(PinVerifyResult.Correct, store.verify(pin()))
    }

    @Test
    fun the_failure_counter_saturates_at_1000() = runBlocking {
        setUpPin()
        real.edit {
            it[kFailures] = 999
            it[kDelay] = 3_600_000L
            it[kStarted] = 0L
            it[kBoot] = 5
            it[kOrigin] = 0
        }
        clock.now = 5_000_000L

        assertEquals(PinVerifyResult.Wrong(PinDelayTier.M60), store.verify(pin(WRONG)))
        clock.advance(3_600_001L)
        assertEquals(PinVerifyResult.Wrong(PinDelayTier.M60), store.verify(pin(WRONG)))

        assertEquals(1000, failures())
    }

    @Test
    fun reset_attempts_clears_the_delay_for_the_controller_biometric_path() = runBlocking {
        setUpPin()
        wrong(5)

        assertEquals(PinStoreWriteResult.DONE, store.resetAttempts())

        assertEquals(0, failures())
        assertEquals(PinVerifyResult.Correct, store.verify(pin()))
    }

    // ---- INV-39 / AE-91 / AE-92: one shared counter across surfaces ----

    @Test
    fun change_pin_failures_and_verify_failures_share_one_counter() = runBlocking {
        setUpPin()
        wrong(2)

        val first = store.changePin(pin(WRONG), pin("975318"))
        val second = store.changePin(pin(WRONG), pin("975318"))

        assertEquals(PinChangeResult.NotVerified(PinVerifyResult.Wrong(PinDelayTier.NONE)), first)
        assertEquals(PinChangeResult.NotVerified(PinVerifyResult.Wrong(PinDelayTier.NONE)), second)
        assertEquals(4, failures())
        assertEquals(PinVerifyResult.Wrong(PinDelayTier.S30), store.verify(pin(WRONG)))
        val delayed = store.changePin(pin(), pin("975318"))
        assertTrue((delayed as PinChangeResult.NotVerified).result is PinVerifyResult.Delayed)
        assertEquals(PinVerifyResult.Correct, run { clock.advance(30_000L); store.verify(pin()) })
    }

    @Test
    fun a_correct_change_pin_resets_the_counter_and_reuses_the_existing_key() = runBlocking {
        setUpPin()
        wrong(3)
        val keyBefore = binder.key

        assertEquals(PinChangeResult.Changed, store.changePin(pin(), pin("975318")))

        assertEquals(0, failures())
        assertEquals(1, binder.createCount)
        assertTrue(keyBefore === binder.key)
        assertEquals(PinVerifyResult.Correct, store.verify(pin("975318")))
        assertEquals(PinVerifyResult.Wrong(PinDelayTier.NONE), store.verify(pin()))
    }

    @Test
    fun change_pin_rejects_an_invalid_new_format_without_touching_the_counter() = runBlocking {
        setUpPin()
        val current = pin()
        val newPin = pin("222222")

        assertEquals(PinChangeResult.InvalidFormat, store.changePin(current, newPin))

        assertEquals(0, failures())
        assertTrue(current.all { it == '\u0000' } && newPin.all { it == '\u0000' })
        assertEquals(PinVerifyResult.Correct, store.verify(pin()))
    }

    // ---- INV-51 / AE-100: biometric flags ----

    @Test
    fun biometric_invalidation_is_one_write_that_turns_it_off_and_survives_a_restart_until_dismissed() = runBlocking {
        setUpPin()
        assertEquals(PinStoreWriteResult.DONE, store.setBiometricEnabled(true))
        assertTrue(store.readState().biometricEnabled)
        val writesBefore = faulty.writeCount.get()

        assertEquals(PinStoreWriteResult.DONE, store.recordBiometricInvalidated())

        assertEquals(1, faulty.writeCount.get() - writesBefore)
        store = newStore()
        assertEquals(AppPinStoreState(PinVerifierState.PRESENT, false, true), store.readState())
        assertEquals(PinStoreWriteResult.DONE, store.dismissBiometricInvalidatedNotice())
        assertEquals(AppPinStoreState(PinVerifierState.PRESENT, false, false), store.readState())
    }

    @Test
    fun a_failed_biometric_write_reports_failed_and_leaves_the_flags_unchanged() = runBlocking {
        setUpPin()
        store.setBiometricEnabled(true)
        faulty.failWrite = IOException("injected")

        assertEquals(PinStoreWriteResult.FAILED, store.recordBiometricInvalidated())

        faulty.failWrite = null
        assertEquals(AppPinStoreState(PinVerifierState.PRESENT, true, false), store.readState())
    }

    // ---- INV-42 / OD-15: present-but-unreadable verifier, never absent, never wiped ----

    @Test
    fun a_missing_keystore_key_is_verifier_unreadable_with_the_counter_untouched_and_nothing_wiped() = runBlocking {
        setUpPin()
        wrong(2)
        val before = prefs()
        binder.key = null
        emitted.clear()

        val result = store.verify(pin())

        assertEquals(PinVerifyResult.VerifierUnreadable(VerifierUnreadableCause.KEY_MISSING), result)
        assertEquals(listOf(PrivacyDiagnosticCode.PIN_VERIFIER_UNREADABLE), emitted)
        assertEquals(before, prefs())
        assertEquals(PinVerifierState.PRESENT, store.readState().verifier)
        assertEquals(0, binder.deleteCount)
    }

    @Test
    fun a_keystore_error_is_verifier_unreadable_counter_untouched_and_a_retry_can_succeed() = runBlocking {
        setUpPin()
        val before = prefs()
        binder.getFailure = VerifierUnreadableCause.KEYSTORE_ERROR
        emitted.clear()

        val result = store.verify(pin())

        assertEquals(PinVerifyResult.VerifierUnreadable(VerifierUnreadableCause.KEYSTORE_ERROR), result)
        assertEquals(listOf(PrivacyDiagnosticCode.PIN_VERIFIER_UNREADABLE), emitted)
        assertEquals(before, prefs())
        binder.getFailure = null
        assertEquals(PinVerifyResult.Correct, store.verify(pin()))
    }

    @Test
    fun an_unparseable_or_out_of_range_verifier_is_unreadable_never_absent() = runBlocking {
        setUpPin()
        val goodRaw = prefs()[kVerifier]!!
        val tooFewIterations = goodRaw.split(":").toMutableList().also { it[2] = "49999" }.joinToString(":")
        listOf("garbage", "", "1:1:150000:zz:1:zz", tooFewIterations).forEach { corrupt ->
            real.edit { it[kVerifier] = corrupt }
            emitted.clear()

            assertEquals(
                PinVerifyResult.VerifierUnreadable(VerifierUnreadableCause.RECORD_UNREADABLE),
                store.verify(pin())
            )
            assertEquals(listOf(PrivacyDiagnosticCode.PIN_VERIFIER_UNREADABLE), emitted)
            assertEquals(PinVerifierState.UNREADABLE, store.readState().verifier)
            assertEquals(0, failures())
        }
    }

    @Test
    fun a_tampered_tag_is_a_wrong_pin_and_not_a_silent_accept() = runBlocking {
        setUpPin()
        val parts = prefs()[kVerifier]!!.split(":").toMutableList()
        parts[5] = "00".repeat(32)
        real.edit { it[kVerifier] = parts.joinToString(":") }

        assertEquals(PinVerifyResult.Wrong(PinDelayTier.NONE), store.verify(pin()))
    }

    @Test
    fun an_unreadable_store_reports_unreadable_in_every_read_path_and_never_absent() = runBlocking {
        setUpPin()
        faulty.failRead = IOException("injected")
        emitted.clear()

        assertEquals(PinVerifierState.UNREADABLE, store.readState().verifier)
        assertEquals(PinVerifierState.UNREADABLE, store.observeState().first().verifier)
        assertEquals(
            PinVerifyResult.VerifierUnreadable(VerifierUnreadableCause.RECORD_UNREADABLE),
            store.verify(pin())
        )
        assertEquals(listOf(PrivacyDiagnosticCode.PIN_VERIFIER_UNREADABLE), emitted)
    }

    @Test
    fun observe_state_reports_absent_then_present_after_setup() = runBlocking {
        assertEquals(PinVerifierState.ABSENT, store.observeState().first().verifier)

        setUpPin()

        assertEquals(PinVerifierState.PRESENT, store.observeState().first().verifier)
    }

    // ---- INV-39 / AE-84 / AE-106: unreadable attempt state is repaired, never wiped ----

    @Test
    fun missing_attempt_keys_with_a_verifier_are_repaired_to_four_failures_and_one_30_second_delay() = runBlocking {
        setUpPin()
        val verifierBefore = prefs()[kVerifier]
        real.edit { it.remove(kFailures) }
        emitted.clear()

        val result = store.verify(pin())

        assertEquals(PinVerifyResult.Delayed(PinDelayReason.ATTEMPT_STATE_UNREADABLE, 30_000L), result)
        assertEquals(listOf(PrivacyDiagnosticCode.PIN_ATTEMPT_STATE_UNREADABLE), emitted)
        val repaired = prefs()
        assertEquals(4, repaired[kFailures])
        assertEquals(30_000L, repaired[kDelay])
        assertEquals(10_000L, repaired[kStarted])
        assertEquals(1, repaired[kOrigin])
        assertEquals(verifierBefore, repaired[kVerifier])
        assertEquals(PinVerifierState.PRESENT, store.readState().verifier)
    }

    @Test
    fun a_repaired_delay_persists_then_a_correct_pin_unlocks_and_a_wrong_one_continues_real_counting() = runBlocking {
        setUpPin()
        real.edit { it.remove(kOrigin) }
        store.verify(pin())
        clock.advance(10_000L)
        store = newStore()

        val stillDelayed = store.verify(pin()) as PinVerifyResult.Delayed

        assertEquals(PinDelayReason.ATTEMPT_STATE_UNREADABLE, stillDelayed.reason)
        assertEquals(20_000L, stillDelayed.remainingMillis)
        clock.advance(20_000L)
        assertEquals(PinVerifyResult.Wrong(PinDelayTier.S30), store.verify(pin(WRONG)))
        assertEquals(0, prefs()[kOrigin])
        assertEquals(5, failures())
    }

    @Test
    fun implausible_attempt_values_are_also_repaired_instead_of_trusted_or_wiped() = runBlocking {
        setUpPin()
        val breakages = listOf<(androidx.datastore.preferences.core.MutablePreferences) -> Unit>(
            { it[kFailures] = -3 },
            { it[kFailures] = 1001 },
            { it[kDelay] = 3_600_001L },
            { it[kStarted] = -5L },
            { it[kOrigin] = 9 }
        )
        breakages.forEach { breakIt ->
            clock.advance(31_000L)
            real.edit {
                it[kFailures] = 0
                it[kDelay] = 0L
                it[kStarted] = 0L
                it[kBoot] = 5
                it[kOrigin] = 0
                breakIt(it)
            }

            val result = store.verify(pin())

            assertEquals(PinVerifyResult.Delayed(PinDelayReason.ATTEMPT_STATE_UNREADABLE, 30_000L), result)
            assertEquals(4, failures())
        }
    }

    @Test
    fun a_failed_repair_write_is_unavailable_and_never_unlocks() = runBlocking {
        setUpPin()
        real.edit { it.remove(kDelay) }
        faulty.failWrite = IOException("injected")

        assertEquals(PinVerifyResult.Unavailable, store.verify(pin()))

        faulty.failWrite = null
        assertEquals(PinVerifierState.PRESENT, store.readState().verifier)
    }

    // ---- rehash after a successful verify (design decision 1) ----

    private fun outdatedRecordRaw(iterations: Int, secret: String = GOOD): String {
        val salt = ByteArray(16) { 7 }
        val derived = PinVerifierCrypto.derive(secret.toCharArray(), salt, iterations)
        val tag = PinVerifierCrypto.computeTag(PinVerifierCrypto.newMac(binder.key!!), iterations, salt, derived)
        return PinVerifierRecord(1, 1, iterations, salt, 1, tag).serialize()
    }

    @Test
    fun a_correct_pin_against_an_outdated_iteration_count_rehashes_to_the_current_parameters() = runBlocking {
        setUpPin()
        val oldRaw = outdatedRecordRaw(100_000)
        real.edit { it[kVerifier] = oldRaw }

        assertEquals(PinVerifyResult.Correct, store.verify(pin()))

        val newRaw = prefs()[kVerifier]!!
        assertNotEquals(oldRaw, newRaw)
        val parsed = PinVerifierRecord.parse(newRaw)!!
        assertEquals(PinVerifierCrypto.CURRENT_ITERATIONS, parsed.iterations)
        assertEquals(PinVerifyResult.Correct, store.verify(pin()))
        assertEquals(newRaw, prefs()[kVerifier])
    }

    @Test
    fun a_wrong_pin_or_a_failed_rehash_write_leaves_the_outdated_record_and_still_unlocks_when_correct() = runBlocking {
        setUpPin()
        val oldRaw = outdatedRecordRaw(100_000)
        real.edit { it[kVerifier] = oldRaw }

        assertEquals(PinVerifyResult.Wrong(PinDelayTier.NONE), store.verify(pin(WRONG)))
        assertEquals(oldRaw, prefs()[kVerifier])
        faulty.failWritesAfter(2) // increment and reset succeed, the rehash write fails

        assertEquals(PinVerifyResult.Correct, store.verify(pin()))

        assertEquals(oldRaw, prefs()[kVerifier])
    }

    // ---- INV-49: deletion order and idempotency ----

    @Test
    fun delete_all_clears_the_store_before_it_deletes_the_alias_and_leaves_no_pin_data() = runBlocking {
        setUpPin()
        store.setBiometricEnabled(true)
        wrong(2)
        var storeWasEmptyAtAliasDeletion: Boolean? = null
        binder.onDelete = { storeWasEmptyAtAliasDeletion = runBlocking { real.data.first().asMap().isEmpty() } }

        assertEquals(PinDeleteResult.DONE, store.deleteAll())

        assertEquals(true, storeWasEmptyAtAliasDeletion)
        assertNull(binder.key)
        assertEquals(1, binder.deleteCount)
        assertEquals(AppPinStoreState.ABSENT, store.readState())
        assertEquals(PinVerifyResult.Absent, store.verify(pin()))
    }

    @Test
    fun delete_all_is_idempotent_and_succeeds_on_a_store_that_never_had_a_pin() = runBlocking {
        assertEquals(PinDeleteResult.DONE, store.deleteAll())
        setUpPin()

        assertEquals(PinDeleteResult.DONE, store.deleteAll())
        assertEquals(PinDeleteResult.DONE, store.deleteAll())

        assertEquals(AppPinStoreState.ABSENT, store.readState())
    }

    @Test
    fun a_failed_store_clear_reports_failed_and_keeps_the_alias_and_the_verifier() = runBlocking {
        setUpPin()
        faulty.failWrite = IOException("injected")

        assertEquals(PinDeleteResult.FAILED, store.deleteAll())

        faulty.failWrite = null
        assertEquals(0, binder.deleteCount)
        assertNotNull(binder.key)
        assertEquals(PinVerifyResult.Correct, store.verify(pin()))
    }

    // ---- setup failures: rollback of the key ----

    @Test
    fun a_setup_write_failure_after_key_creation_deletes_the_alias_and_leaves_no_verifier() = runBlocking {
        faulty.failWrite = IOException("injected")
        val secret = pin()

        assertEquals(PinSetupResult.FAILED, store.setup(secret))

        faulty.failWrite = null
        assertEquals(1, binder.createCount)
        assertEquals(1, binder.deleteCount)
        assertNull(binder.key)
        assertEquals(PinVerifierState.ABSENT, store.readState().verifier)
        assertTrue(secret.all { it == '\u0000' })
    }

    @Test
    fun a_keystore_failure_during_setup_fails_without_writing_anything() = runBlocking {
        binder.createFailure = VerifierUnreadableCause.KEYSTORE_ERROR
        val writesBefore = faulty.writeCount.get()

        assertEquals(PinSetupResult.FAILED, store.setup(pin()))

        assertEquals(writesBefore, faulty.writeCount.get())
        assertEquals(PinVerifierState.ABSENT, store.readState().verifier)
    }

    // ---- cancellation is never swallowed; pins are still zeroed ----

    private suspend fun cancellationOf(block: suspend () -> Unit): CancellationException? = try {
        block()
        null
    } catch (e: CancellationException) {
        e
    }

    @Test
    fun a_cancellation_during_the_counter_write_propagates_and_the_pin_is_zeroed() = runBlocking {
        setUpPin()
        faulty.failWrite = CancellationException("injected")
        val secret = pin()

        val thrown = cancellationOf { store.verify(secret) }

        assertNotNull(thrown)
        assertTrue(secret.all { it == '\u0000' })
        faulty.failWrite = null
        assertEquals(0, failures())
    }

    @Test
    fun a_cancellation_during_setup_propagates_rolls_back_the_key_and_zeroes_the_pin() = runBlocking {
        faulty.failWrite = CancellationException("injected")
        val secret = pin()

        val thrown = cancellationOf { store.setup(secret) }

        assertNotNull(thrown)
        assertEquals(1, binder.deleteCount)
        assertTrue(secret.all { it == '\u0000' })
    }

    @Test
    fun a_cancelled_diagnostic_propagates_but_an_ordinary_diagnostic_failure_never_changes_the_outcome() = runBlocking {
        setUpPin()
        binder.key = null
        emitFailure = IOException("sink down")

        assertEquals(
            PinVerifyResult.VerifierUnreadable(VerifierUnreadableCause.KEY_MISSING),
            store.verify(pin())
        )

        emitFailure = CancellationException("injected")
        assertNotNull(cancellationOf { store.verify(pin()) })
    }

    // ---- G24 guardian regressions ----

    @Test
    fun g24_1_a_cancellation_after_the_setup_edit_committed_keeps_the_key_and_the_verifier() = runBlocking {
        faulty.failAfterCommit = CancellationException("injected after commit")
        val secret = pin()

        val thrown = cancellationOf { store.setup(secret) }

        faulty.failAfterCommit = null
        assertNotNull(thrown)
        assertEquals(0, binder.deleteCount)
        assertNotNull(binder.key)
        assertEquals(PinVerifierState.PRESENT, store.readState().verifier)
        assertNotNull(prefs()[kVerifier])
        assertTrue(secret.all { it == '\u0000' })
        assertEquals(PinVerifyResult.Correct, store.verify(pin()))
    }

    @Test
    fun g24_1b_a_setup_edit_that_fails_before_committing_deletes_the_key_and_leaves_no_verifier() = runBlocking {
        faulty.failWrite = IOException("injected before commit")

        assertEquals(PinSetupResult.FAILED, store.setup(pin()))

        faulty.failWrite = null
        assertEquals(1, binder.createCount)
        assertEquals(1, binder.deleteCount)
        assertNull(binder.key)
        assertNull(prefs()[kVerifier])
        assertEquals(PinVerifierState.ABSENT, store.readState().verifier)
    }

    @Test
    fun g24_2_setup_over_an_existing_verifier_fails_and_leaves_the_key_and_the_original_pin_untouched() = runBlocking {
        setUpPin()
        val keyBefore = binder.key
        val verifierBefore = prefs()[kVerifier]
        val createsBefore = binder.createCount

        assertEquals(PinSetupResult.FAILED, store.setup(pin("975318")))

        assertEquals(createsBefore, binder.createCount)
        assertEquals(0, binder.deleteCount)
        assertTrue(keyBefore === binder.key)
        assertEquals(verifierBefore, prefs()[kVerifier])
        assertEquals(PinVerifyResult.Correct, store.verify(pin()))
        assertEquals(PinVerifyResult.Wrong(PinDelayTier.NONE), store.verify(pin("975318")))
    }

    @Test
    fun g24_3_a_key_that_throws_during_verify_is_keystore_error_and_the_persisted_counter_is_restored() = runBlocking {
        setUpPin()
        wrong(2)
        val failuresBefore = failures()
        val workingKey = binder.key
        binder.key = ThrowingKey()
        emitted.clear()

        val result = store.verify(pin())

        assertEquals(PinVerifyResult.VerifierUnreadable(VerifierUnreadableCause.KEYSTORE_ERROR), result)
        assertEquals(2, failuresBefore)
        assertEquals(failuresBefore, failures())
        assertEquals(PinVerifierState.PRESENT, store.readState().verifier)
        assertEquals(0, binder.deleteCount)
        assertTrue(PrivacyDiagnosticCode.PIN_VERIFIER_UNREADABLE in emitted)
        binder.key = workingKey
        assertEquals(PinVerifyResult.Correct, store.verify(pin()))
    }

    /** A Keystore-style key whose use fails (its material cannot be extracted for the HMAC). */
    private class ThrowingKey : SecretKey {
        override fun getAlgorithm(): String = "HmacSHA256"

        override fun getFormat(): String = "RAW"

        override fun getEncoded(): ByteArray = throw IllegalStateException("injected key use failure")
    }

    private class FakeClock(var now: Long) : MonotonicClock {
        override fun elapsedRealtimeMillis(): Long = now

        fun advance(millis: Long) {
            now += millis
        }
    }

    private class FakeBootCount(var value: Int?) : BootCountSource {
        override fun currentBootCount(): Int? = value
    }

    /** Wraps the real store: injectable read/write failures and a count of successful writes. */
    private class FaultyStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile
        var failRead: Throwable? = null

        @Volatile
        var failWrite: Throwable? = null

        /** When >= 0, writes beyond this absolute count fail with an [IOException]. */
        @Volatile
        var writeLimit: Int = -1

        /** When set, the write is applied and durable, THEN this is thrown (models a cancel racing the commit). */
        @Volatile
        var failAfterCommit: Throwable? = null

        val writeCount = AtomicInteger(0)

        fun failWritesAfter(more: Int) {
            writeLimit = writeCount.get() + more
        }

        override val data: Flow<Preferences>
            get() {
                val failure = failRead
                return if (failure == null) delegate.data else flow { throw failure }
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            failWrite?.let { throw it }
            if (writeLimit >= 0 && writeCount.get() >= writeLimit) throw IOException("injected")
            writeCount.incrementAndGet()
            val committed = delegate.updateData(transform)
            failAfterCommit?.let { throw it }
            return committed
        }
    }

    private companion object {
        const val GOOD = "482915"
        const val WRONG = "739150"
    }
}
