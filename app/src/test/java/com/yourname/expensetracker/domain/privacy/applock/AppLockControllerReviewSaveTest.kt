package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The review-save of [AppLockSettingsOperations] together with the controller entries it drives (S18 part (ii-d): T27,
 * INV-44, INV-45, INV-46, AE-85, AE-86, AE-87, AE-89, D3, D6, D7, F2, F7). The repository is the in-memory fake, whose
 * `reviewSave` mirrors the conditional D8 check of the real store (it is covered against the real store in
 * `AppLockSettingsRepositoryImplTest`). Setup prompts and setup diagnostics are cleared before each scenario.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockControllerReviewSaveTest {

    /** AE-85: {lock off, M1, discreet off, secure off}: weakening, confirmed through the CHANGE prompt. */
    private val weakeningOff = AppLockPrivacySettings(
        appLockEnabled = false,
        autoLockTimeout = AutoLockTimeout.M1,
        discreetNotifications = false,
        secureScreen = false
    )

    /** AE-86: {lock on, IMMEDIATE, discreet off, secure off}: not weakening, no prompt. */
    private val notWeakening = AppLockPrivacySettings(
        appLockEnabled = true,
        autoLockTimeout = AutoLockTimeout.IMMEDIATE,
        discreetNotifications = false,
        secureScreen = false
    )

    /** Lock on with a timeout longer than IMMEDIATE: weakening, confirmed through the CHANGE prompt. */
    private val lockOnLonger = AppLockPrivacySettings(
        appLockEnabled = true,
        autoLockTimeout = AutoLockTimeout.M5,
        discreetNotifications = false,
        secureScreen = false
    )

    /** AE-87: {lock off, discreet on, secure off}. */
    private val lockOffDiscreetOn = AppLockPrivacySettings(
        appLockEnabled = false,
        autoLockTimeout = AutoLockTimeout.M1,
        discreetNotifications = true,
        secureScreen = false
    )

    // --- Rigs --------------------------------------------------------------------------------------------------

    /** `UNLOCKED` over a store that is unreadable with [cause] (a persistent one by default), setup noise cleared. */
    private fun unlockedRig(
        cause: SettingsUnreadableCause = SettingsUnreadableCause.CORRUPTED,
        deferred: Boolean = false,
        serializer: AppLockSettingsWriteSerializer = AppLockSettingsWriteSerializer()
    ): SettingsOperationsRig {
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(cause))
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppForegrounded()
        assertEquals(AppLockState.Unlocked, controller.state.value)
        return cleared(SettingsOperationsRig(h, controller, deferred, serializer))
    }

    /** `INERT(SETTINGS_UNREADABLE_NO_AUTH)` over a CORRUPTED store; the capability the request will see is [atRequest]. */
    private fun inertRig(
        atRequest: DeviceAuthCapability = DeviceAuthCapability.NO_CREDENTIAL,
        serializer: AppLockSettingsWriteSerializer = AppLockSettingsWriteSerializer()
    ): SettingsOperationsRig {
        val h = AppLockControllerHarness(
            AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED),
            DeviceAuthCapability.NO_CREDENTIAL
        )
        val controller = h.resolvedController()
        assertEquals(AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), controller.state.value)
        h.capabilityProvider.capability = atRequest
        return cleared(SettingsOperationsRig(h, controller, serializer = serializer))
    }

    private fun cleared(rig: SettingsOperationsRig): SettingsOperationsRig {
        rig.h.emitted.clear()
        rig.h.emittedMetadata.clear()
        rig.h.authenticator.calls.clear()
        return rig
    }

    private fun save(rig: SettingsOperationsRig, record: AppLockPrivacySettings): ReviewSaveResult =
        runBlocking { rig.ops.requestReviewSave(record) }

    /** Starts the weakening prompt and answers it with [outcome]; returns the attempt id. */
    private fun promptAnswered(rig: SettingsOperationsRig, record: AppLockPrivacySettings, outcome: DeviceAuthOutcome): Long {
        assertEquals(ReviewSaveResult.STARTED, save(rig, record))
        rig.h.settle()
        val attemptId = rig.h.authenticator.calls.last().attemptId
        assertEquals(AppLockState.Authenticating(attemptId, AuthPurpose.CONFIRM_CHANGE), rig.controller.state.value)
        rig.h.authenticator.deliver(outcome)
        return attemptId
    }

    private fun count(rig: SettingsOperationsRig, code: PrivacyDiagnosticCode): Int = rig.h.emitted.count { it == code }

    private fun assertStoreUntouched(rig: SettingsOperationsRig) {
        assertTrue("no review-save reached the store", rig.h.repository.reviewSaves.isEmpty())
        assertTrue(rig.h.repository.loadState is AppLockSettingsLoadState.Unreadable)
        assertTrue(rig.h.repository.lockWrites.isEmpty())
        assertTrue(rig.h.repository.timeoutWrites.isEmpty())
        assertTrue(rig.h.repository.plainWrites.isEmpty())
    }

    private fun assertFailureRecordedOnce(rig: SettingsOperationsRig) {
        assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED))
        val index = rig.h.emitted.indexOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.FIELD to AppLockSettingField.REVIEW_SAVE),
            rig.h.emittedMetadata[index].entries
        )
    }

    private fun assertRethrown(block: suspend () -> Any?) {
        try {
            runBlocking { block() }
            fail("the cancellation was swallowed")
        } catch (e: CancellationException) {
            // propagated
        }
    }

    private fun commitThenCancel(rig: SettingsOperationsRig, stored: AppLockPrivacySettings): Nothing {
        rig.h.repository.loadState = AppLockSettingsLoadState.Loaded(stored)
        throw CancellationException("cancelled")
    }

    // --- AE-85: weakening review-save --------------------------------------------------------------------------

    @Test
    fun `AE-85 a cancelled prompt writes nothing and keeps the sentinel, the same record then succeeds to DISABLED with REVIEW_SAVED once`() {
        val rig = unlockedRig()

        promptAnswered(rig, weakeningOff, DeviceAuthOutcome.Cancelled)

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertStoreUntouched(rig)
        assertEquals(0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertTrue(rig.controller.isPersistentUnreadable)
        // The next cold start still sees the sentinel.
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), rig.h.resolvedController().state.value)

        rig.h.authenticator.calls.clear()
        promptAnswered(rig, weakeningOff, DeviceAuthOutcome.Succeeded)

        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertEquals(listOf(weakeningOff), rig.h.repository.reviewSaves)
        assertEquals(AppLockSettingsLoadState.Loaded(weakeningOff), rig.h.repository.loadState)
        assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertEquals(emptyMap<PrivacyDiagnosticKey, Any>(), rig.h.emittedMetadata[rig.h.emitted.indexOf(PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED)].entries)
        assertEquals(AppLockState.Disabled, rig.h.resolvedController().state.value) // the next cold start
        assertTrue("only the review-save wrote", rig.h.repository.lockWrites.isEmpty() && rig.h.repository.timeoutWrites.isEmpty() && rig.h.repository.plainWrites.isEmpty())
        assertTrue(rig.uncaught.isEmpty())
    }

    @Test
    fun `AE-85 the weakening prompt uses the CHANGE copy and nothing is written before the prompt succeeds`() {
        val rig = unlockedRig()

        assertEquals(ReviewSaveResult.STARTED, save(rig, weakeningOff))
        rig.h.settle()

        assertEquals(AuthPromptCopy.CHANGE, rig.h.authenticator.calls.single().copy)
        assertStoreUntouched(rig)
        assertEquals(0, rig.h.repository.writeAttempts)
        rig.h.authenticator.deliver(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER))
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertStoreUntouched(rig)
    }

    @Test
    fun `AE-85 a weakening lock-on record with a longer timeout also confirms, then stays UNLOCKED with the record as the view`() {
        val rig = unlockedRig()

        promptAnswered(rig, lockOnLonger, DeviceAuthOutcome.Succeeded)

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(listOf(lockOnLonger), rig.h.repository.reviewSaves)
        assertFalse(rig.controller.isPersistentUnreadable)
        assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertEquals(AppLockState.Locked(LockCause.COLD_START), rig.h.resolvedController().state.value)
    }

    // --- AE-86: not weakening ----------------------------------------------------------------------------------

    @Test
    fun `AE-86 a record that is not weakening is written with no prompt, stays UNLOCKED, and the next cold start is LOCKED COLD_START`() {
        val rig = unlockedRig()

        assertEquals(ReviewSaveResult.COMMITTED, save(rig, notWeakening))
        rig.h.settle()

        assertTrue("no prompt", rig.h.authenticator.calls.isEmpty())
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(listOf(notWeakening), rig.h.repository.reviewSaves)
        assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertEquals(AppLockState.Locked(LockCause.COLD_START), rig.h.resolvedController().state.value)
        assertNull(rig.controller.notice.value)
    }

    // --- AE-87: INERT ------------------------------------------------------------------------------------------

    @Test
    fun `AE-87 INERT a lock-on record is REJECTED_CAPABILITY, nothing is written, the state and notice stay`() {
        val rig = inertRig()

        assertEquals(ReviewSaveResult.REJECTED_CAPABILITY, save(rig, notWeakening))

        assertEquals(0, rig.h.repository.writeAttempts)
        assertStoreUntouched(rig)
        assertEquals(AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), rig.controller.state.value)
        assertEquals(LockNotice.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), rig.controller.notice.value)
        assertTrue(rig.h.authenticator.calls.isEmpty())
        assertEquals(0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
    }

    @Test
    fun `AE-87 INERT a lock-off record with discreet on needs no auth - DISABLED, the inert notice is gone, discreet re-posts (false, true)`() {
        // The capabilities that cannot authenticate (neither AVAILABLE nor CHECK_FAILED), D3.
        val cannotAuthenticate = listOf(
            DeviceAuthCapability.NO_CREDENTIAL,
            DeviceAuthCapability.HW_UNAVAILABLE,
            DeviceAuthCapability.UNSUPPORTED_API,
            DeviceAuthCapability.UNKNOWN
        )
        for (capability in cannotAuthenticate) {
            val rig = inertRig(capability)

            assertEquals("$capability", ReviewSaveResult.COMMITTED, save(rig, lockOffDiscreetOn))

            assertEquals("$capability", AppLockState.Disabled, rig.controller.state.value)
            assertNull("$capability", rig.controller.notice.value)
            assertTrue("$capability: no prompt", rig.h.authenticator.calls.isEmpty())
            assertEquals("$capability", listOf(lockOffDiscreetOn), rig.h.repository.reviewSaves)
            assertEquals("$capability", listOf(false to true), rig.triggerCalls)
            assertEquals("$capability", 1, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        }
    }

    // --- D3 ----------------------------------------------------------------------------------------------------

    @Test
    fun `D3 INERT a weakening record with a fresh AVAILABLE or CHECK_FAILED capability is NOT_IN_ORIGIN, with no write and no prompt`() {
        for (capability in listOf(DeviceAuthCapability.AVAILABLE, DeviceAuthCapability.CHECK_FAILED)) {
            val rig = inertRig(capability)

            assertEquals("$capability", ReviewSaveResult.NOT_IN_ORIGIN, save(rig, weakeningOff))

            assertEquals("$capability", 0, rig.h.repository.writeAttempts)
            assertStoreUntouched(rig)
            assertTrue("$capability", rig.h.authenticator.calls.isEmpty())
            assertEquals("$capability", AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), rig.controller.state.value)
            assertNull("$capability", rig.controller.inFlightAttemptId)
            assertEquals("$capability", 0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        }
        // AVAILABLE also covers a lock-on weakening record (CHECK_FAILED refuses every lock-on record first).
        val available = inertRig(DeviceAuthCapability.AVAILABLE)
        assertEquals(ReviewSaveResult.NOT_IN_ORIGIN, save(available, lockOnLonger))
        assertEquals(0, available.h.repository.writeAttempts)
    }

    @Test
    fun `D3 INERT a record that is not weakening is still written directly when the capability is AVAILABLE, and the state becomes UNLOCKED`() {
        val rig = inertRig(DeviceAuthCapability.AVAILABLE)

        assertEquals(ReviewSaveResult.COMMITTED, save(rig, notWeakening))

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(listOf(notWeakening), rig.h.repository.reviewSaves)
        assertTrue(rig.h.authenticator.calls.isEmpty())
        assertNull(rig.controller.notice.value)
    }

    // --- INV-45: a lock-on record needs a fresh AVAILABLE capability -------------------------------------------

    @Test
    fun `REJECTED_CAPABILITY a lock-on record with a capability other than AVAILABLE writes nothing and starts no prompt`() {
        for (capability in DeviceAuthCapability.entries.filter { it != DeviceAuthCapability.AVAILABLE }) {
            for (record in listOf(notWeakening, lockOnLonger)) {
                val rig = unlockedRig()
                rig.h.capabilityProvider.capability = capability

                assertEquals("$capability $record", ReviewSaveResult.REJECTED_CAPABILITY, save(rig, record))

                assertEquals("$capability $record", 0, rig.h.repository.writeAttempts)
                assertStoreUntouched(rig)
                assertTrue("$capability $record", rig.h.authenticator.calls.isEmpty())
                assertEquals("$capability $record", AppLockState.Unlocked, rig.controller.state.value)
                assertEquals("$capability $record", 0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
                assertTrue("$capability $record", rig.controller.isPersistentUnreadable)
            }
        }
    }

    @Test
    fun `REJECTED_CAPABILITY the capability is read fresh at each request, a failing read counts as CHECK_FAILED`() {
        val rig = unlockedRig()
        rig.h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        assertEquals(ReviewSaveResult.REJECTED_CAPABILITY, save(rig, notWeakening))

        rig.h.capabilityProvider.capability = DeviceAuthCapability.AVAILABLE
        assertEquals(ReviewSaveResult.COMMITTED, save(rig, notWeakening)) // the same record now passes

        val failing = unlockedRig()
        failing.h.capabilityProvider.failure = IllegalStateException("secret /data/x")
        assertEquals(ReviewSaveResult.REJECTED_CAPABILITY, save(failing, notWeakening))
        assertEquals(0, failing.h.repository.writeAttempts)
    }

    // --- NOT_IN_REVIEW -----------------------------------------------------------------------------------------

    @Test
    fun `NOT_IN_REVIEW a Loaded store, a first run store or a transient cause is not a review, and nothing is written`() {
        val stores: List<Pair<String, AppLockSettingsLoadState>> = listOf(
            "loaded lock on" to AppLockTestSettings.enabled(),
            "loaded lock off" to AppLockTestSettings.DISABLED,
            "first run" to AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings()),
            "IO_ERROR" to AppLockTestSettings.unreadable(SettingsUnreadableCause.IO_ERROR),
            "TIMEOUT" to AppLockTestSettings.unreadable(SettingsUnreadableCause.TIMEOUT)
        )
        for ((label, store) in stores) {
            val h = AppLockControllerHarness(store)
            val controller = h.resolvedController()
            if (controller.state.value is AppLockState.Locked) h.unlock(controller)
            val rig = cleared(SettingsOperationsRig(h, controller))
            val stateBefore = controller.state.value

            for (record in listOf(notWeakening, weakeningOff)) {
                assertEquals("$label $record", ReviewSaveResult.NOT_IN_REVIEW, save(rig, record))
            }

            assertEquals(label, 0, h.repository.writeAttempts)
            assertTrue(label, h.repository.reviewSaves.isEmpty())
            assertTrue(label, h.authenticator.calls.isEmpty())
            assertEquals(label, stateBefore, controller.state.value)
            assertEquals(label, 0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        }
    }

    @Test
    fun `NOT_IN_REVIEW a LOCKED state is not a review even over a persistent cause, and a persistent cause in an open state is`() {
        val (lockedHarness, locked) = lockedControllerFor(LockCause.SETTINGS_UNREADABLE)
        val lockedRig = cleared(SettingsOperationsRig(lockedHarness, locked))
        assertEquals(ReviewSaveResult.NOT_IN_REVIEW, save(lockedRig, notWeakening))
        assertEquals(0, lockedHarness.repository.writeAttempts)
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), locked.state.value)

        for (cause in SettingsUnreadableCause.entries.filter { it.isPersistent }) {
            val rig = unlockedRig(cause)
            assertEquals("cause $cause", ReviewSaveResult.COMMITTED, save(rig, notWeakening))
        }
    }

    @Test
    fun `D8 a store that another writer already recovered gives NOT_IN_REVIEW on the direct path and changes nothing`() {
        val rig = unlockedRig()
        rig.h.repository.loadState = AppLockTestSettings.enabled(AutoLockTimeout.M15) // recovered behind the controller's back

        assertEquals(ReviewSaveResult.NOT_IN_REVIEW, save(rig, notWeakening))

        assertEquals(1, rig.h.repository.writeAttempts) // the store was asked and refused
        assertTrue(rig.h.repository.reviewSaves.isEmpty())
        assertEquals(AppLockTestSettings.enabled(AutoLockTimeout.M15), rig.h.repository.loadState) // not overwritten
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue("the controller view is untouched", rig.controller.isPersistentUnreadable)
        assertEquals(0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertEquals(0, count(rig, PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED))
        assertTrue(rig.triggerCalls.isEmpty())
    }

    @Test
    fun `D8 a store recovered while the weakening prompt was open is not overwritten, the state does not move, the apply completes`() {
        val rig = unlockedRig(deferred = true)
        val stored = AppLockTestSettings.enabled(AutoLockTimeout.M15)

        assertEquals(ReviewSaveResult.STARTED, save(rig, weakeningOff))
        rig.h.settle()
        rig.h.repository.loadState = stored // another writer recovered the store during the prompt
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        rig.h.settle()

        assertEquals(stored, rig.h.repository.loadState)
        assertTrue(rig.h.repository.reviewSaves.isEmpty())
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertEquals(0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertTrue(rig.triggerCalls.isEmpty())
        assertTrue(rig.uncaught.isEmpty())
        // The completion ran: the apply flag and the T33 gate are free again.
        assertEquals(ReviewSaveResult.NOT_IN_REVIEW, save(rig, notWeakening)) // not ALREADY_IN_FLIGHT
        assertTrue(rig.controller.startOpenOriginAttempt(OpenOriginPrompt.DISABLE, RecordingSuccessHandler()) is OpenOriginStartResult.Started)
    }

    // --- The controller entry guards the start itself ----------------------------------------------------------

    @Test
    fun `startReviewSaveAttempt starts only from UNLOCKED over a persistent unreadable view`() {
        val rig = unlockedRig()
        assertTrue(rig.controller.startReviewSaveAttempt(weakeningOff, RecordingSuccessHandler()) is OpenOriginStartResult.Started)
        rig.h.settle()
        rig.h.authenticator.deliver(DeviceAuthOutcome.Cancelled)

        // A transient view is not a review.
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.IO_ERROR))
        val transient = h.resolvedController()
        h.unlock(transient)
        h.authenticator.calls.clear()
        assertEquals(OpenOriginStartResult.NotInOrigin, transient.startReviewSaveAttempt(weakeningOff, RecordingSuccessHandler()))
        assertTrue(h.authenticator.calls.isEmpty())
        assertEquals(AppLockState.Unlocked, transient.state.value)

        // LOCKED is not the origin.
        val (lockedHarness, locked) = lockedControllerFor(LockCause.SETTINGS_UNREADABLE)
        assertEquals(OpenOriginStartResult.NotInOrigin, locked.startReviewSaveAttempt(weakeningOff, RecordingSuccessHandler()))
        assertTrue(lockedHarness.authenticator.calls.isEmpty())
    }

    @Test
    fun `onReviewSavedWithoutPrompt moves nothing when it is not committed and never relaxes LOCKED when it is`() {
        val (h, locked) = lockedControllerFor(LockCause.SETTINGS_UNREADABLE)
        h.emitted.clear()

        locked.onReviewSavedWithoutPrompt(weakeningOff, committed = true)
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), locked.state.value) // fail closed

        val rig = unlockedRig()
        rig.controller.onReviewSavedWithoutPrompt(weakeningOff, committed = false)
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue("the view stays unreadable (the sentinel stays)", rig.controller.isPersistentUnreadable)
        assertEquals(0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertFailureRecordedOnce(rig)
    }

    @Test
    fun `ISSUE-1 a direct committed weakening record never moves UNLOCKED to DISABLED, but the commit is still taken`() {
        val rig = unlockedRig()
        assertTrue(rig.controller.isPersistentUnreadable)

        rig.controller.onReviewSavedWithoutPrompt(weakeningOff, committed = true)

        assertEquals(AppLockState.Unlocked, rig.controller.state.value) // fail closed: no move on the caller's word
        assertFalse("the view is replaced by the committed record", rig.controller.isPersistentUnreadable)
        assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))

        // The writeSequence is bumped: a re-read still in flight when the commit lands is dropped (F2, D6). Were it
        // applied, its DISABLED snapshot would move the state.
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.IO_ERROR))
        val controller = h.resolvedController()
        h.unlock(controller)
        val gate = CompletableDeferred<Unit>()
        try {
            h.repository.scriptRead(AppLockTestSettings.DISABLED, gate)
            controller.requestSettingsReread()

            controller.onReviewSavedWithoutPrompt(weakeningOff, committed = true)
            gate.complete(Unit)
        } finally {
            gate.complete(Unit)
        }
        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    @Test
    fun `ISSUE-1 control the same direct committed weakening record from INERT moves to DISABLED`() {
        val rig = inertRig()

        rig.controller.onReviewSavedWithoutPrompt(weakeningOff, committed = true)

        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
    }

    // --- ALREADY_IN_FLIGHT, INV-46-----------------------------------------------------------------------------

    @Test
    fun `ALREADY_IN_FLIGHT a request while the apply runs changes nothing, the state moves only after the durable write, the flag is cleared afterwards`() {
        val rig = unlockedRig(deferred = true)
        val gate = CompletableDeferred<Unit>()
        try {
            rig.h.repository.reviewSaveGate = gate
            assertEquals(ReviewSaveResult.STARTED, save(rig, weakeningOff))
            rig.h.settle()
            rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            rig.h.settle() // the apply now waits inside the durable write

            // INV-46: nothing moved before the write returned.
            assertEquals(AppLockState.Unlocked, rig.controller.state.value)
            assertEquals(1, rig.h.repository.reviewSaves.size)
            assertEquals(ReviewSaveResult.ALREADY_IN_FLIGHT, save(rig, notWeakening))
            assertEquals(ReviewSaveResult.ALREADY_IN_FLIGHT, save(rig, weakeningOff))
            assertEquals(1, rig.h.repository.writeAttempts)

            gate.complete(Unit)
            rig.h.settle()
        } finally {
            gate.complete(Unit)
        }

        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        // The flag is cleared: the next request is not ALREADY_IN_FLIGHT (the store is recovered now).
        assertEquals(ReviewSaveResult.NOT_IN_REVIEW, save(rig, notWeakening))
    }

    @Test
    fun `a request while the weakening prompt is open is not accepted and writes nothing`() {
        val rig = unlockedRig()
        assertEquals(ReviewSaveResult.STARTED, save(rig, weakeningOff))
        rig.h.settle()

        val second = save(rig, weakeningOff)

        assertTrue("$second", second == ReviewSaveResult.NOT_IN_REVIEW || second == ReviewSaveResult.ALREADY_IN_FLIGHT)
        assertEquals(1, rig.h.authenticator.calls.size) // at most one prompt (INV-34)
        assertEquals(0, rig.h.repository.writeAttempts)
        rig.h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
    }

    @Test
    fun `a cancelled scope cannot leave a half applied review-save - the write, the state move and the completion still finish`() {
        val rig = unlockedRig(deferred = true)
        val gate = CompletableDeferred<Unit>()
        try {
            rig.h.repository.reviewSaveGate = gate
            assertEquals(ReviewSaveResult.STARTED, save(rig, weakeningOff))
            rig.h.settle()
            rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            rig.h.settle() // suspended inside the durable write

            rig.job.cancel()
            gate.complete(Unit)
            rig.h.settle()
        } finally {
            gate.complete(Unit)
        }

        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertEquals(listOf(false to false), rig.triggerCalls)
        assertTrue(rig.controller.startOpenOriginAttempt(OpenOriginPrompt.ENABLE, RecordingSuccessHandler()) is OpenOriginStartResult.Started)
    }

    // --- AE-89: a failing write ----------------------------------------------------------------------------------

    @Test
    fun `AE-89 a failing write on the direct path keeps the store, the sentinel and the state, records PERSIST_FAILED REVIEW_SAVE once, then a retry succeeds`() {
        val rig = unlockedRig()
        rig.h.repository.reviewSaveResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.REVIEW_SAVE) }

        assertEquals(ReviewSaveResult.FAILED, save(rig, notWeakening))
        assertEquals(ReviewSaveResult.FAILED, save(rig, notWeakening))

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue(rig.h.repository.loadState is AppLockSettingsLoadState.Unreadable)
        assertTrue(rig.controller.isPersistentUnreadable)
        assertEquals(LockNotice.ReviewSettings, rig.controller.notice.value)
        assertFailureRecordedOnce(rig) // the second failure is not recorded again
        assertEquals(0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertTrue("INV-46: no trigger without a commit", rig.triggerCalls.isEmpty())

        rig.h.repository.reviewSaveResult = null
        assertEquals(ReviewSaveResult.COMMITTED, save(rig, notWeakening))
        assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
    }

    @Test
    fun `AE-89 a failing write after a successful prompt keeps the store, the sentinel and the state and records PERSIST_FAILED REVIEW_SAVE once`() {
        val rig = unlockedRig()
        rig.h.repository.reviewSaveResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.REVIEW_SAVE) }

        promptAnswered(rig, weakeningOff, DeviceAuthOutcome.Succeeded)

        assertEquals(AppLockState.Unlocked, rig.controller.state.value) // not DISABLED
        assertTrue(rig.h.repository.loadState is AppLockSettingsLoadState.Unreadable)
        assertTrue(rig.controller.isPersistentUnreadable)
        assertEquals(LockNotice.ReviewSettings, rig.controller.notice.value)
        assertFailureRecordedOnce(rig)
        assertEquals(0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertTrue(rig.triggerCalls.isEmpty())
        assertTrue(rig.uncaught.isEmpty())
        // The completion ran: the gate is closed and a new weakening request can start its prompt.
        rig.h.authenticator.calls.clear()
        assertEquals(ReviewSaveResult.STARTED, save(rig, weakeningOff))
    }

    @Test
    fun `AE-89 a write that throws, or a store that refuses with UNREADABLE, is a bounded failure and never a commit`() {
        val failures: List<Pair<String, (SettingsOperationsRig) -> Unit>> = listOf(
            "throws" to { r: SettingsOperationsRig -> r.h.repository.reviewSaveFailure = IllegalStateException("secret-path /data/user/0/x") },
            "UNREADABLE" to { r: SettingsOperationsRig ->
                r.h.repository.reviewSaveResult = { AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE) }
            }
        )
        for ((label, arrange) in failures) {
            for (viaPrompt in listOf(false, true)) {
                val rig = unlockedRig()
                arrange(rig)

                if (viaPrompt) {
                    promptAnswered(rig, weakeningOff, DeviceAuthOutcome.Succeeded)
                } else {
                    assertEquals("$label", ReviewSaveResult.FAILED, save(rig, notWeakening))
                }

                val tag = "$label prompt=$viaPrompt"
                assertEquals(tag, AppLockState.Unlocked, rig.controller.state.value)
                assertTrue(tag, rig.controller.isPersistentUnreadable)
                assertFailureRecordedOnce(rig)
                assertEquals(tag, 0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
                assertTrue(tag, rig.triggerCalls.isEmpty())
                assertTrue(tag, rig.uncaught.isEmpty())
                assertTrue(tag, rig.h.uncaught.isEmpty())
                for (metadata in rig.h.emittedMetadata) {
                    for ((_, value) in metadata.entries) assertTrue(tag, value is Enum<*>)
                }
            }
        }
    }

    // --- F7: a cancelled durable write -------------------------------------------------------------------------

    @Test
    fun `F7 direct a cancelled write that the re-read shows committed runs the committed path, then rethrows and frees the serializer`() {
        val rig = unlockedRig()
        rig.h.repository.reviewSaveResult = { record -> commitThenCancel(rig, record) }

        assertRethrown { rig.ops.requestReviewSave(notWeakening) }

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertFalse(rig.controller.isPersistentUnreadable) // the view took the record
        assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertEquals(0, count(rig, PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED))
        assertEquals(listOf(false to true), rig.triggerCalls)
        // The serializer was released by the cancelled request: a later write that takes it is answered, not blocked.
        // (The lock is on now, so the discreet value is forced: Rejected(FORCED).)
        assertEquals(PlainSettingResult.REJECTED_FORCED, runBlocking { rig.ops.requestDiscreetChange(true) })
    }

    @Test
    fun `F7 direct a cancelled write with no commit, a different stored value or a throwing re-read records the failure once and rethrows`() {
        val arrangements: List<Pair<String, (SettingsOperationsRig) -> Unit>> = listOf(
            "no commit" to { r: SettingsOperationsRig -> r.h.repository.reviewSaveFailure = CancellationException("cancelled") },
            "another value" to { r: SettingsOperationsRig ->
                r.h.repository.reviewSaveResult = { commitThenCancel(r, AppLockPrivacySettings(autoLockTimeout = AutoLockTimeout.M15)) }
            },
            "throwing re-read" to { r: SettingsOperationsRig ->
                r.h.repository.reviewSaveFailure = CancellationException("cancelled")
                r.h.repository.failure = IllegalStateException("secret-path /data/x")
            }
        )
        for ((label, arrange) in arrangements) {
            val rig = unlockedRig()
            arrange(rig)

            assertRethrown { rig.ops.requestReviewSave(notWeakening) }

            assertEquals(label, AppLockState.Unlocked, rig.controller.state.value)
            assertTrue(label, rig.controller.isPersistentUnreadable) // the view did not take the record
            assertFailureRecordedOnce(rig)
            assertEquals(label, 0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
            assertTrue(label, rig.triggerCalls.isEmpty())
            assertTrue(label, rig.uncaught.isEmpty())
            // The serializer was released by the cancelled request: a later write that takes it is answered, not blocked.
            assertTrue(label, runBlocking { rig.ops.requestDiscreetChange(true) } != PlainSettingResult.ALREADY_IN_FLIGHT)
        }
    }

    @Test
    fun `F7 prompt apply a cancelled write that the re-read shows committed takes the record, sweeps, then rethrows and completes`() {
        val rig = unlockedRig(deferred = true)
        rig.h.repository.reviewSaveResult = { record -> commitThenCancel(rig, record) }

        assertEquals(ReviewSaveResult.STARTED, save(rig, weakeningOff))
        rig.h.settle()
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        val apply = rig.job.children.single()
        rig.h.settle()

        assertTrue("the cancellation must propagate, not be swallowed", apply.isCancelled)
        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertEquals(0, count(rig, PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED))
        assertEquals(listOf(false to false), rig.triggerCalls)
        assertTrue(rig.uncaught.isEmpty())
        assertTrue(rig.controller.startOpenOriginAttempt(OpenOriginPrompt.ENABLE, RecordingSuccessHandler()) is OpenOriginStartResult.Started)
    }

    @Test
    fun `F7 prompt apply a cancelled write with no commit moves nothing, records the failure once and completes`() {
        val rig = unlockedRig(deferred = true)
        rig.h.repository.reviewSaveFailure = CancellationException("cancelled")

        assertEquals(ReviewSaveResult.STARTED, save(rig, weakeningOff))
        rig.h.settle()
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        val apply = rig.job.children.single()
        rig.h.settle()

        assertTrue(apply.isCancelled)
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue(rig.controller.isPersistentUnreadable)
        assertFailureRecordedOnce(rig)
        assertEquals(0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertTrue(rig.triggerCalls.isEmpty())
        assertTrue(rig.uncaught.isEmpty())
        assertTrue(rig.controller.startOpenOriginAttempt(OpenOriginPrompt.DISABLE, RecordingSuccessHandler()) is OpenOriginStartResult.Started)
    }

    // --- The discreet trigger: (false, effective after) --------------------------------------------------------

    @Test
    fun `the discreet trigger gets (false, effective after) on the prompt path, for both discreet values and the forced lock-on value`() {
        val rows = listOf(
            weakeningOff to (false to false),
            weakeningOff.copy(discreetNotifications = true) to (false to true),
            lockOnLonger to (false to true) // discreet is forced while the lock is on
        )
        for ((record, expected) in rows) {
            val rig = unlockedRig()

            promptAnswered(rig, record, DeviceAuthOutcome.Succeeded)

            assertEquals("$record", listOf(expected), rig.triggerCalls)
        }
    }

    @Test
    fun `the discreet trigger gets (false, effective after) on the direct path, and no trigger runs without a commit`() {
        val rows = listOf(
            notWeakening to (false to true), // forced by the lock
            notWeakening.copy(discreetNotifications = true) to (false to true)
        )
        for ((record, expected) in rows) {
            val rig = unlockedRig()

            assertEquals(ReviewSaveResult.COMMITTED, save(rig, record))

            assertEquals("$record", listOf(expected), rig.triggerCalls)
        }
        val inertOff = inertRig()
        assertEquals(ReviewSaveResult.COMMITTED, save(inertOff, lockOffDiscreetOn.copy(discreetNotifications = false)))
        assertEquals(listOf(false to false), inertOff.triggerCalls)

        val refused = unlockedRig()
        refused.h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        save(refused, notWeakening)
        val cancelled = unlockedRig()
        promptAnswered(cancelled, weakeningOff, DeviceAuthOutcome.Cancelled)
        assertTrue(refused.triggerCalls.isEmpty())
        assertTrue(cancelled.triggerCalls.isEmpty())
    }

    // --- F2 / D6: a committed review-save drops older re-read snapshots ----------------------------------------

    private fun transientLocked(): Pair<AppLockControllerHarness, AppLockController> {
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.IO_ERROR))
        val controller = h.resolvedController()
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        return h to controller
    }

    private fun unlockOnce(h: AppLockControllerHarness, controller: AppLockController) {
        h.tap(controller)
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
    }

    @Test
    fun `F2 D6 a snapshot kept by T29 is dropped after a committed review-save, so the unlock does not apply it`() {
        val (h, controller) = transientLocked()
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.onAppForegrounded() // kept (T29): LOCKED is not relaxed

        controller.onReviewSavedWithoutPrompt(notWeakening, committed = true)
        assertEquals(AppLockState.Locked(LockCause.SETTINGS_UNREADABLE), controller.state.value)
        val readsAfterSave = h.repository.readCount
        unlockOnce(h, controller)

        assertEquals(AppLockState.Unlocked, controller.state.value) // not DISABLED from the dropped snapshot
        controller.onAppForegrounded()
        assertEquals(readsAfterSave, h.repository.readCount) // the view is Loaded: nothing to re-read
        assertEquals(AppLockState.Unlocked, controller.state.value)
    }

    @Test
    fun `F2 D6 a re-read still in flight when a review-save commits drops its result`() {
        val h = AppLockControllerHarness(AppLockTestSettings.unreadable(SettingsUnreadableCause.IO_ERROR))
        val controller = h.resolvedController()
        h.unlock(controller)
        val gate = CompletableDeferred<Unit>()
        try {
            h.repository.scriptRead(AppLockTestSettings.DISABLED, gate)
            controller.requestSettingsReread()

            controller.onReviewSavedWithoutPrompt(notWeakening, committed = true)
            gate.complete(Unit)
        } finally {
            gate.complete(Unit)
        }

        assertEquals(AppLockState.Unlocked, controller.state.value) // T28 did not apply DISABLED
    }

    @Test
    fun `D6 a review-save that did not commit does not drop a kept snapshot`() {
        val (h, controller) = transientLocked()
        h.repository.scriptRead(AppLockTestSettings.DISABLED)
        controller.onAppForegrounded() // kept

        controller.onReviewSavedWithoutPrompt(notWeakening, committed = false)
        unlockOnce(h, controller)

        assertEquals(AppLockState.Disabled, controller.state.value) // the kept snapshot applied
    }

    // --- D7: diagnostics claimed once and bounded --------------------------------------------------------------

    @Test
    fun `D7 REVIEW_SAVED is claimed once per process and PERSIST_FAILED once per code and field, each field on its own`() {
        val rig = unlockedRig()
        rig.controller.onReviewSavedWithoutPrompt(notWeakening, committed = true)
        rig.controller.onReviewSavedWithoutPrompt(notWeakening, committed = true)
        assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        // A new process (a new controller) claims again.
        val coldStart = rig.h.newController()
        coldStart.onReviewSavedWithoutPrompt(notWeakening, committed = true)
        assertEquals(2, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))

        val failing = unlockedRig()
        failing.controller.onReviewSavedWithoutPrompt(weakeningOff, committed = false)
        failing.controller.onReviewSavedWithoutPrompt(weakeningOff, committed = false)
        assertEquals(1, count(failing, PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED))
        failing.controller.onTimeoutWrittenWithoutPrompt(AutoLockTimeout.M1, committed = false) // another field
        assertEquals(2, count(failing, PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED))
        assertEquals(
            listOf<Any>(AppLockSettingField.REVIEW_SAVE, AppLockSettingField.AUTO_LOCK_TIMEOUT),
            failing.h.emittedMetadata.map { it.entries[PrivacyDiagnosticKey.FIELD]!! }
        )
    }

    @Test
    fun `D7 the review-save diagnostics carry nothing but enums, and a failing or stuck sink never fails a committed save`() {
        val sinks: List<Pair<String, (AppLockControllerHarness) -> Unit>> = listOf(
            "healthy" to { _: AppLockControllerHarness -> },
            "failing" to { h: AppLockControllerHarness -> h.emitFailure = IllegalStateException("secret-path /data/user/0/x") },
            "stuck" to { h: AppLockControllerHarness -> h.emitSuspendsForever = true }
        )
        for ((label, arrange) in sinks) {
            val rig = unlockedRig()
            arrange(rig.h)

            assertEquals(label, ReviewSaveResult.COMMITTED, save(rig, notWeakening))

            assertEquals(label, AppLockState.Unlocked, rig.controller.state.value)
            assertEquals(label, AppLockSettingsLoadState.Loaded(notWeakening), rig.h.repository.loadState)
            assertEquals(label, listOf(false to true), rig.triggerCalls)
            assertTrue(label, rig.h.uncaught.isEmpty())
            assertTrue(label, rig.uncaught.isEmpty())
            for (metadata in rig.h.emittedMetadata) {
                for ((_, value) in metadata.entries) assertTrue(label, value is Enum<*>)
            }
        }

        // A healthy sink with one failing and one committed save: the loop above is vacuous for the failing and the
        // stuck sink, so the bounded content is asserted here against emissions that are known to exist.
        val healthy = unlockedRig()
        healthy.h.repository.reviewSaveResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.REVIEW_SAVE) }
        assertEquals(ReviewSaveResult.FAILED, save(healthy, notWeakening))
        healthy.h.repository.reviewSaveResult = null
        assertEquals(ReviewSaveResult.COMMITTED, save(healthy, notWeakening))
        healthy.h.settle()

        assertTrue("diagnostics were emitted", healthy.h.emitted.isNotEmpty())
        assertEquals(healthy.h.emitted.size, healthy.h.emittedMetadata.size)
        assertFailureRecordedOnce(healthy) // PERSIST_FAILED with FIELD = REVIEW_SAVE
        assertEquals(1, count(healthy, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
        assertEquals(
            emptyMap<PrivacyDiagnosticKey, Any>(),
            healthy.h.emittedMetadata[healthy.h.emitted.indexOf(PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED)].entries
        )
        for (metadata in healthy.h.emittedMetadata) {
            for ((_, value) in metadata.entries) assertTrue("$value", value is Enum<*>)
        }
    }

    // --- C1 / INV-45: the D3 decision and the capability are re-proved inside the serializer -------------------

    @Test
    fun `C1 a D3 save that waited for the serializer is NOT_IN_ORIGIN with nothing written when authentication became possible meanwhile`() = runBlocking {
        for (becomes in listOf(DeviceAuthCapability.AVAILABLE, DeviceAuthCapability.CHECK_FAILED)) {
            val serializer = AppLockSettingsWriteSerializer()
            val rig = inertRig(serializer = serializer) // NO_CREDENTIAL at the request: the D3 direct path is taken
            val gate = CompletableDeferred<Unit>()
            try {
                val holder = async(start = CoroutineStart.UNDISPATCHED) { serializer.withExclusiveWrite { gate.await() } }
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { rig.ops.requestReviewSave(weakeningOff) }
                assertTrue("$becomes: the save waits for the serializer", waiting.isActive)
                rig.h.capabilityProvider.capability = becomes

                gate.complete(Unit)
                holder.await()

                assertEquals("$becomes", ReviewSaveResult.NOT_IN_ORIGIN, waiting.await())
                assertEquals("$becomes", 0, rig.h.repository.writeAttempts)
                assertStoreUntouched(rig)
                assertTrue("$becomes", rig.h.authenticator.calls.isEmpty())
                assertEquals("$becomes", AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), rig.controller.state.value)
                assertEquals("$becomes", 0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
                assertTrue("$becomes", rig.triggerCalls.isEmpty())
            } finally {
                gate.complete(Unit)
            }
        }
    }

    @Test
    fun `C1 a D3 save that waited for the serializer still commits when nothing changed meanwhile`() = runBlocking {
        val serializer = AppLockSettingsWriteSerializer()
        val rig = inertRig(serializer = serializer)
        val gate = CompletableDeferred<Unit>()
        try {
            val holder = async(start = CoroutineStart.UNDISPATCHED) { serializer.withExclusiveWrite { gate.await() } }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { rig.ops.requestReviewSave(weakeningOff) }
            assertTrue(waiting.isActive)

            gate.complete(Unit)
            holder.await()

            assertEquals(ReviewSaveResult.COMMITTED, waiting.await())
            assertEquals(listOf(weakeningOff), rig.h.repository.reviewSaves)
            assertEquals(AppLockState.Disabled, rig.controller.state.value)
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun `C1 a lock-on save that waited for the serializer is REJECTED_CAPABILITY with nothing written when the capability was lost meanwhile`() = runBlocking {
        val serializer = AppLockSettingsWriteSerializer()
        val rig = unlockedRig(serializer = serializer)
        val gate = CompletableDeferred<Unit>()
        try {
            val holder = async(start = CoroutineStart.UNDISPATCHED) { serializer.withExclusiveWrite { gate.await() } }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { rig.ops.requestReviewSave(notWeakening) }
            assertTrue("the save passed the request-time capability check and waits", waiting.isActive)
            rig.h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL

            gate.complete(Unit)
            holder.await()

            assertEquals(ReviewSaveResult.REJECTED_CAPABILITY, waiting.await())
            assertEquals(0, rig.h.repository.writeAttempts)
            assertStoreUntouched(rig)
            assertEquals(AppLockState.Unlocked, rig.controller.state.value)
            assertEquals(0, count(rig, PrivacyDiagnosticCode.LOCK_SETTINGS_REVIEW_SAVED))
            assertTrue(rig.triggerCalls.isEmpty())
        } finally {
            gate.complete(Unit)
        }
    }
}
