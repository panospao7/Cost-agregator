package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S18 part (i-b2): forced values (INV-48, AE-103, OD-2 a) over [AppLockSettingsOperations.requestDiscreetChange] and
 * [AppLockSettingsOperations.requestSecureScreenChange]. While the stored lock flag is on both values are forced and
 * read-only: either value is `Rejected(FORCED)`, nothing is written, no prompt and no record. With the lock off neither
 * is a weakening (INV-3 covers an enabled lock), so the write commits directly; the stored value applies again when
 * forcing ends (T18).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockControllerForcedValueTest {

    private fun unlockedRig(): SettingsOperationsRig {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        h.emitted.clear()
        h.emittedMetadata.clear()
        h.authenticator.calls.clear()
        return SettingsOperationsRig(h, controller)
    }

    private fun disabledRig(): SettingsOperationsRig {
        val (h, controller) = disabledControllerFor(DeviceAuthCapability.AVAILABLE)
        h.emitted.clear()
        h.emittedMetadata.clear()
        return SettingsOperationsRig(h, controller)
    }

    private fun stored(rig: SettingsOperationsRig): AppLockPrivacySettings =
        (rig.h.repository.loadState as AppLockSettingsLoadState.Loaded).settings

    private suspend fun request(rig: SettingsOperationsRig, field: AppLockSettingField, value: Boolean): PlainSettingResult =
        if (field == AppLockSettingField.DISCREET) rig.ops.requestDiscreetChange(value) else rig.ops.requestSecureScreenChange(value)

    private val fields = listOf(AppLockSettingField.DISCREET, AppLockSettingField.SECURE_SCREEN)

    // --- AE-103 ------------------------------------------------------------------------------------

    @Test
    fun `AE-103 with the lock on either value is Rejected FORCED, the stored value stays, no prompt, no record, no sweep`() = runBlocking {
        for (field in fields) {
            for (value in listOf(true, false)) {
                val rig = unlockedRig()

                assertEquals("$field $value", PlainSettingResult.REJECTED_FORCED, request(rig, field, value))
                rig.h.settle()

                val s = stored(rig)
                assertFalse("$field $value stored discreet unchanged", s.discreetNotifications)
                assertFalse("$field $value stored secure unchanged", s.secureScreen)
                assertTrue("$field $value no prompt", rig.h.authenticator.calls.isEmpty())
                assertTrue("$field $value no diagnostic", rig.h.emitted.isEmpty())
                assertTrue("$field $value no sweep", rig.triggerCalls.isEmpty())
                assertEquals(AppLockState.Unlocked, rig.controller.state.value)
                assertTrue(rig.uncaught.isEmpty())
            }
        }
    }

    @Test
    fun `AE-103 the stored value applies again when forcing ends, and the effective value follows it`() = runBlocking {
        val rig = unlockedRig()
        val h = rig.h
        assertEquals(PlainSettingResult.REJECTED_FORCED, rig.ops.requestDiscreetChange(true))
        assertTrue("forced on while the lock is on", EffectivePrivacyValues.effectiveDiscreet(h.repository.loadState))

        // T18: disable with a fresh authentication.
        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
        h.settle()
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertFalse("the stored false applies again", EffectivePrivacyValues.effectiveDiscreet(h.repository.loadState))
        assertFalse(EffectivePrivacyValues.effectiveSecureScreen(h.repository.loadState, resolving = false))
    }

    // --- Lock off: not a weakening, committed directly ----------------------------------------------

    @Test
    fun `with the lock off discreet and secure commit directly with no prompt, and the controller view takes them`() = runBlocking {
        val rig = disabledRig()
        val h = rig.h

        assertEquals(PlainSettingResult.COMMITTED, rig.ops.requestDiscreetChange(true))
        assertEquals(PlainSettingResult.COMMITTED, rig.ops.requestSecureScreenChange(true))

        assertTrue(stored(rig).discreetNotifications)
        assertTrue(stored(rig).secureScreen)
        assertTrue(h.authenticator.calls.isEmpty())
        assertTrue(h.emitted.isEmpty())
        assertEquals(AppLockState.Disabled, rig.controller.state.value)

        // Turning them off again (not a weakening with the lock off) also commits with no prompt.
        assertEquals(PlainSettingResult.COMMITTED, rig.ops.requestDiscreetChange(false))
        assertEquals(PlainSettingResult.COMMITTED, rig.ops.requestSecureScreenChange(false))
        assertFalse(stored(rig).discreetNotifications)
        assertFalse(stored(rig).secureScreen)
        assertTrue(h.authenticator.calls.isEmpty())
    }

    @Test
    fun `a committed discreet write hands the before and after effective values to the trigger, secure screen does not`() = runBlocking {
        val rig = disabledRig()

        rig.ops.requestDiscreetChange(true)
        rig.ops.requestDiscreetChange(false)
        rig.ops.requestSecureScreenChange(true)

        assertEquals(listOf(false to true, true to false), rig.triggerCalls)
    }

    // --- Failure: committed state only, once per (code, field) --------------------------------------

    @Test
    fun `an unreadable store or a failing write is FAILED, keeps the value and records PERSIST_FAILED once per field`() = runBlocking {
        for (field in fields) {
            val arranged = mapOf<String, (SettingsOperationsRig) -> Unit>(
                "failed" to { r -> r.h.repository.plainWriteResult = { f, _ -> AppLockSettingsWriteResult.Failed(f) } },
                "rejected" to { r -> r.h.repository.plainWriteResult = { _, _ -> AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE) } },
                "throws" to { r -> r.h.repository.plainWriteFailure = IllegalStateException("secret-path /data/user/0/x") }
            )
            for ((label, arrange) in arranged) {
                val rig = disabledRig()
                arrange(rig)

                assertEquals("$field $label", PlainSettingResult.FAILED, request(rig, field, true))
                assertEquals("$field $label", PlainSettingResult.FAILED, request(rig, field, true))

                assertEquals("$field $label", listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), rig.h.emitted)
                assertEquals(
                    "$field $label",
                    mapOf(PrivacyDiagnosticKey.FIELD to field),
                    rig.h.emittedMetadata[0].entries
                )
                assertFalse("$field $label", stored(rig).discreetNotifications)
                assertFalse("$field $label", stored(rig).secureScreen)
                assertTrue("$field $label no sweep", rig.triggerCalls.isEmpty())
            }
        }
    }

    @Test
    fun `an unreadable store is refused fail closed with nothing written`() = runBlocking {
        val rig = disabledRig()
        rig.h.repository.loadState = AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED)

        assertEquals(PlainSettingResult.FAILED, rig.ops.requestDiscreetChange(true))
        assertEquals(PlainSettingResult.FAILED, rig.ops.requestSecureScreenChange(true))

        assertTrue(rig.h.repository.loadState is AppLockSettingsLoadState.Unreadable)
        assertTrue(rig.triggerCalls.isEmpty())
    }

    // --- Races --------------------------------------------------------------------------------------

    @Test
    // The ops-level interleaving (an enable apply committing while a plain write is pending) is excluded by
    // directWriteMutex (S18 i-b2 C1; pinned by the C1 test below). What remains is a lock committed by ANOTHER
    // store writer (e.g. a review-save) while the plain write is pending. This test proves the OPS handling of a
    // FORCED decided at write time (the fake store decides after the gate): the result is surfaced, nothing leaks
    // into the stored value, no sweep, no prompt. Store-level atomicity (the forced check reading inside the same
    // edit transform as the write) is proven by AppLockSettingsRepositoryImplTest
    // forcedAware_check_reads_inside_the_same_edit_as_a_concurrent_lock_commit (S18 i-b2 ISSUE-3).
    // The requested value is TRUE over the stored false, so a write that leaked past the forced decision would be
    // detectable (a request equal to the stored value could not distinguish a leak).
    fun `a lock committed outside the ops mutex while a lock-off write is pending makes that write Rejected FORCED, nothing is stored`() = runBlocking {
        val rig = disabledRig()
        val h = rig.h
        h.authenticator.calls.clear()   // rig setup prompts must not pollute the no-prompt assertion
        val gate = CompletableDeferred<Unit>()
        h.repository.plainWriteGate = gate
        try {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { rig.ops.requestDiscreetChange(true) }
            assertEquals(1, h.repository.plainWrites.size)
            h.repository.loadState = AppLockTestSettings.enabled()   // lock committed outside the ops mutex
            gate.complete(Unit)

            assertEquals(PlainSettingResult.REJECTED_FORCED, pending.await())
            assertFalse("the forced-rejected true must not leak into the stored value", stored(rig).discreetNotifications)
            assertEquals(AppLockTestSettings.enabled(), h.repository.loadState)
            assertTrue("no sweep: ${rig.triggerCalls}", rig.triggerCalls.isEmpty())
            assertTrue("no prompt", h.authenticator.calls.isEmpty())
        } finally {
            gate.complete(Unit)   // a failed assertion must fail fast, never hang runBlocking
        }
    }

    @Test
    fun `C1 an enable apply waits for a pending plain discreet-off write, so its pre-read sees the committed value and the sweep runs`() = runBlocking {
        val rig = disabledRig()
        val h = rig.h
        // Stored: lock off, discreet ON. The plain discreet-off write starts first and holds the mutex at its gate.
        h.repository.loadState = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = true))
        val gate = CompletableDeferred<Unit>()
        h.repository.plainWriteGate = gate
        try {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { rig.ops.requestDiscreetChange(false) }
            assertEquals(1, h.repository.plainWrites.size)

            // The enable prompt succeeds while that write is pending: its apply must queue behind the mutex, not pre-read now.
            assertEquals(SettingsRequestResult.STARTED, rig.ops.requestEnable())
            h.settle()
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            assertTrue("the apply wrote the lock flag before the plain write finished", h.repository.lockWrites.isEmpty())

            gate.complete(Unit)
            assertEquals(PlainSettingResult.COMMITTED, pending.await())
            h.settle()

            // The apply's pre-read saw the committed discreet-off value, so the lock write is a false to true edge.
            assertEquals(listOf(true), h.repository.lockWrites)
            assertTrue(stored(rig).appLockEnabled)
            assertTrue("(false, true) sweep expected: ${rig.triggerCalls}", rig.triggerCalls.contains(false to true))
            assertFalse("a stale (true, true) skipped the sweep: ${rig.triggerCalls}", rig.triggerCalls.contains(true to true))
            assertEquals(AppLockState.Unlocked, rig.controller.state.value)
            assertTrue(rig.uncaught.isEmpty())
        } finally {
            gate.complete(Unit)   // a regression must fail fast, never hang runBlocking
        }
    }

    @Test
    fun `a plain write is ALREADY_IN_FLIGHT while a lock apply is running and nothing is written`() = runBlocking {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        h.authenticator.calls.clear()
        val rig = SettingsOperationsRig(h, controller, deferred = true)
        rig.ops.requestDisable()
        h.settle()
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded) // the prompt closed; the apply is queued

        assertEquals(PlainSettingResult.ALREADY_IN_FLIGHT, rig.ops.requestDiscreetChange(true))
        assertEquals(PlainSettingResult.ALREADY_IN_FLIGHT, rig.ops.requestSecureScreenChange(true))
        assertTrue(h.repository.plainWrites.isEmpty())
        h.settle()
        assertTrue(rig.uncaught.isEmpty())
    }

    // --- Cancellation -------------------------------------------------------------------------------

    @Test
    fun `a CancellationException from the write propagates, is not recorded, and releases the mutex`() {
        val rig = disabledRig()
        rig.h.repository.plainWriteFailure = CancellationException("c")

        try {
            runBlocking { rig.ops.requestDiscreetChange(true) }
            fail("the cancellation was swallowed")
        } catch (e: CancellationException) {
            // propagated
        }
        assertTrue(rig.h.emitted.isEmpty())

        rig.h.repository.plainWriteFailure = null
        assertEquals(PlainSettingResult.COMMITTED, runBlocking { rig.ops.requestDiscreetChange(true) })
    }
}
