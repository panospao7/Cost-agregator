package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S18 part (ii-a), F7: a durable write that ends with a `CancellationException` inside `NonCancellable` has an unknown
 * outcome. [AppLockSettingsOperations] re-reads the store once: the requested value means committed (the normal
 * committed path runs), anything else (another value, unreadable, a throwing read) means not committed (no move, the
 * failure recorded once, no trigger). The cancellation is always rethrown and the completion `finally` still runs.
 * Covers WRITE 3 (lock apply), both WRITE 4 paths (direct and prompt apply) and WRITE 5 (plain writes).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockSettingsOperationsCancelledWriteTest {

    private fun cancelled() = CancellationException("cancelled")

    private fun disabledRig(deferred: Boolean = false): SettingsOperationsRig {
        val (h, controller) = disabledControllerFor()
        h.emitted.clear()
        h.emittedMetadata.clear()
        return SettingsOperationsRig(h, controller, deferred)
    }

    private fun unlockedRig(timeout: AutoLockTimeout, deferred: Boolean = false): SettingsOperationsRig {
        val (h, controller) = unlockedControllerFor(timeout)
        h.repository.loadState = AppLockTestSettings.enabled(timeout)
        h.emitted.clear()
        h.emittedMetadata.clear()
        h.authenticator.calls.clear()
        return SettingsOperationsRig(h, controller, deferred)
    }

    private fun stored(rig: SettingsOperationsRig): AppLockPrivacySettings =
        (rig.h.repository.loadState as AppLockSettingsLoadState.Loaded).settings

    /** The write commits to the fake store and THEN ends with a cancellation (the outcome the caller cannot see). */
    private fun commitThenCancel(rig: SettingsOperationsRig, next: (AppLockPrivacySettings) -> AppLockPrivacySettings): Nothing {
        rig.h.repository.loadState = AppLockSettingsLoadState.Loaded(next(stored(rig)))
        throw cancelled()
    }

    private fun assertFailureRecordedOnce(rig: SettingsOperationsRig, field: AppLockSettingField) {
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED), rig.h.emitted)
        assertEquals(mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.FIELD to field), rig.h.emittedMetadata[0].entries)
    }

    private fun assertRethrown(block: suspend () -> Any?) {
        try {
            runBlocking { block() }
            fail("the cancellation was swallowed")
        } catch (e: CancellationException) {
            // propagated
        }
    }

    /** True when a foreground after [awayMillis] in the background locks, so the controller's view holds a timeout below it. */
    private fun locksAfter(rig: SettingsOperationsRig, awayMillis: Long): Boolean {
        rig.controller.onAppForegrounded()
        rig.controller.onAppBackgrounded()
        rig.h.clock.advance(awayMillis)
        rig.controller.onAppForegrounded()
        return rig.controller.state.value != AppLockState.Unlocked
    }

    // --- WRITE 3: the lock apply ----------------------------------------------------------------------

    private fun enableAndGetApply(rig: SettingsOperationsRig): kotlinx.coroutines.Job {
        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestEnable())
        rig.h.settle()
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        return rig.job.children.single()
    }

    private fun assertApplyEndedCancelledAndCompleted(rig: SettingsOperationsRig, apply: kotlinx.coroutines.Job) {
        assertTrue("the cancellation must propagate, not be swallowed", apply.isCancelled)
        assertTrue(rig.uncaught.isEmpty())
        // The completion ran in the finally (INV-54): the T33 gate is closed, so a new attempt may start. The probe
        // is the open-origin prompt that is legal in the resulting state: ENABLE from DISABLED (nothing committed),
        // DISABLE from UNLOCKED (the re-read showed the enable committed). Any other state fails the check.
        val probe = when (rig.controller.state.value) {
            AppLockState.Disabled -> OpenOriginPrompt.ENABLE
            AppLockState.Unlocked -> OpenOriginPrompt.DISABLE
            else -> null
        }
        assertTrue("unexpected end state ${rig.controller.state.value}", probe != null)
        assertTrue(rig.controller.startOpenOriginAttempt(probe!!, RecordingSuccessHandler()) is OpenOriginStartResult.Started)
    }

    @Test
    fun `WRITE 3 a cancelled lock write that the re-read shows committed runs the committed path, sweeps, then rethrows`() {
        val rig = disabledRig(deferred = true)
        rig.h.repository.lockWriteResult = { enabled -> commitThenCancel(rig) { it.copy(appLockEnabled = enabled) } }

        val apply = enableAndGetApply(rig)
        rig.h.settle()

        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertTrue(stored(rig).appLockEnabled)
        assertFalse(rig.h.emitted.contains(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED))
        assertEquals("the sweep ran from the re-read value", listOf(false to true), rig.triggerCalls)
        assertApplyEndedCancelledAndCompleted(rig, apply)
    }

    @Test
    fun `WRITE 3 a cancelled lock write with no commit moves nothing, records the failure once and sweeps nothing`() {
        val rig = disabledRig(deferred = true)
        rig.h.repository.lockWriteFailure = cancelled()

        val apply = enableAndGetApply(rig)
        rig.h.settle()

        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertFailureRecordedOnce(rig, AppLockSettingField.APP_LOCK_ENABLED)
        assertTrue(rig.triggerCalls.isEmpty())
        assertApplyEndedCancelledAndCompleted(rig, apply)
    }

    @Test
    fun `WRITE 3 an unreadable or throwing re-read counts as not committed`() {
        // Unreadable store after the cancelled write.
        val unreadable = disabledRig(deferred = true)
        unreadable.h.repository.lockWriteResult = {
            unreadable.h.repository.loadState = AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED)
            throw cancelled()
        }
        val applyA = enableAndGetApply(unreadable)
        unreadable.h.settle()
        assertEquals(AppLockState.Disabled, unreadable.controller.state.value)
        assertFailureRecordedOnce(unreadable, AppLockSettingField.APP_LOCK_ENABLED)
        assertTrue(unreadable.triggerCalls.isEmpty())
        assertApplyEndedCancelledAndCompleted(unreadable, applyA)

        // The re-read itself throws (the pre-read throws too and counts as not discreet yet).
        val throwing = disabledRig(deferred = true)
        throwing.h.repository.failure = IllegalStateException("secret-path /data/user/0/x")
        throwing.h.repository.lockWriteFailure = cancelled()
        val applyB = enableAndGetApply(throwing)
        throwing.h.settle()
        assertEquals(AppLockState.Disabled, throwing.controller.state.value)
        assertFailureRecordedOnce(throwing, AppLockSettingField.APP_LOCK_ENABLED)
        assertTrue(throwing.triggerCalls.isEmpty())
        assertApplyEndedCancelledAndCompleted(throwing, applyB)
    }

    // --- WRITE 4, direct path ---------------------------------------------------------------------------

    @Test
    fun `WRITE 4 direct a cancelled write that the re-read shows committed takes the timeout into the view, then rethrows`() {
        val rig = unlockedRig(AutoLockTimeout.M5)
        rig.h.repository.timeoutWriteResult = { t -> commitThenCancel(rig) { it.copy(autoLockTimeout = t) } }

        assertRethrown { rig.ops.requestTimeoutChange(AutoLockTimeout.S30) }

        assertEquals(AutoLockTimeout.S30, stored(rig).autoLockTimeout)
        assertTrue(rig.h.emitted.isEmpty())
        assertTrue("the view holds S30, so 31 s away locks (an M5 view would not)", locksAfter(rig, AutoLockTimeout.S30.millis + 1))
    }

    @Test
    fun `WRITE 4 direct a cancelled write with no commit or an unreadable re-read records the failure once and rethrows`() {
        val noCommit = unlockedRig(AutoLockTimeout.M5)
        noCommit.h.repository.timeoutWriteFailure = cancelled()
        assertRethrown { noCommit.ops.requestTimeoutChange(AutoLockTimeout.S30) }
        assertFailureRecordedOnce(noCommit, AppLockSettingField.AUTO_LOCK_TIMEOUT)
        assertEquals(AutoLockTimeout.M5, stored(noCommit).autoLockTimeout)
        assertEquals(AppLockState.Unlocked, noCommit.controller.state.value)

        val unreadable = unlockedRig(AutoLockTimeout.M5)
        unreadable.h.repository.timeoutWriteResult = {
            unreadable.h.repository.loadState = AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED)
            throw cancelled()
        }
        assertRethrown { unreadable.ops.requestTimeoutChange(AutoLockTimeout.S30) }
        assertFailureRecordedOnce(unreadable, AppLockSettingField.AUTO_LOCK_TIMEOUT)
        assertEquals(AppLockState.Unlocked, unreadable.controller.state.value)

        // The serializer was released by the cancelled request: a later request is accepted.
        noCommit.h.repository.timeoutWriteFailure = null
        assertEquals(TimeoutChangeResult.COMMITTED, runBlocking { noCommit.ops.requestTimeoutChange(AutoLockTimeout.S30) })
    }

    // --- WRITE 4, prompt-apply path -----------------------------------------------------------------------

    private fun longerTimeoutApply(rig: SettingsOperationsRig): kotlinx.coroutines.Job {
        assertEquals(TimeoutChangeResult.STARTED, runBlocking { rig.ops.requestTimeoutChange(AutoLockTimeout.M5) })
        rig.h.settle()
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        return rig.job.children.single()
    }

    @Test
    fun `WRITE 4 prompt apply a cancelled write that the re-read shows committed takes the timeout, then rethrows and completes`() {
        val rig = unlockedRig(AutoLockTimeout.M1, deferred = true)
        rig.h.repository.timeoutWriteResult = { t -> commitThenCancel(rig) { it.copy(autoLockTimeout = t) } }

        val apply = longerTimeoutApply(rig)
        rig.h.settle()

        assertTrue("the cancellation must propagate", apply.isCancelled)
        assertTrue(rig.uncaught.isEmpty())
        assertTrue(rig.h.emitted.isEmpty())
        assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        assertFalse("the view holds M5, so M1 + 1 ms away does not lock", locksAfter(rig, AutoLockTimeout.M1.millis + 1))
        // The completion ran in the finally: a new request is accepted.
        rig.h.repository.timeoutWriteResult = null
        assertEquals(TimeoutChangeResult.COMMITTED, runBlocking { rig.ops.requestTimeoutChange(AutoLockTimeout.S30) })
    }

    @Test
    fun `WRITE 4 prompt apply a cancelled write with no commit or an unreadable re-read records the failure once and completes`() {
        val noCommit = unlockedRig(AutoLockTimeout.M1, deferred = true)
        noCommit.h.repository.timeoutWriteFailure = cancelled()
        val applyA = longerTimeoutApply(noCommit)
        noCommit.h.settle()
        assertTrue(applyA.isCancelled)
        assertFailureRecordedOnce(noCommit, AppLockSettingField.AUTO_LOCK_TIMEOUT)
        assertEquals(AutoLockTimeout.M1, stored(noCommit).autoLockTimeout)
        assertNotEquals(AutoLockTimeout.M5, stored(noCommit).autoLockTimeout)
        noCommit.h.repository.timeoutWriteFailure = null
        assertEquals(TimeoutChangeResult.COMMITTED, runBlocking { noCommit.ops.requestTimeoutChange(AutoLockTimeout.S30) })

        val unreadable = unlockedRig(AutoLockTimeout.M1, deferred = true)
        unreadable.h.repository.timeoutWriteResult = {
            unreadable.h.repository.loadState = AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED)
            throw cancelled()
        }
        val applyB = longerTimeoutApply(unreadable)
        unreadable.h.settle()
        assertTrue(applyB.isCancelled)
        assertFailureRecordedOnce(unreadable, AppLockSettingField.AUTO_LOCK_TIMEOUT)
        assertTrue(unreadable.uncaught.isEmpty())
    }

    // --- WRITE 5: the plain writes ----------------------------------------------------------------------------

    @Test
    fun `WRITE 5 a cancelled discreet write that the re-read shows committed sweeps once, then rethrows`() {
        val rig = disabledRig()
        rig.h.repository.plainWriteResult = { _, v -> commitThenCancel(rig) { it.copy(discreetNotifications = v) } }

        assertRethrown { rig.ops.requestDiscreetChange(true) }

        assertTrue(stored(rig).discreetNotifications)
        assertEquals(listOf(false to true), rig.triggerCalls)
        assertTrue(rig.h.emitted.isEmpty())
    }

    @Test
    fun `WRITE 5 a cancelled secure-screen write that the re-read shows committed takes the value with no sweep, then rethrows`() {
        val rig = disabledRig()
        rig.h.repository.plainWriteResult = { _, v -> commitThenCancel(rig) { it.copy(secureScreen = v) } }

        assertRethrown { rig.ops.requestSecureScreenChange(true) }

        assertTrue(stored(rig).secureScreen)
        assertTrue(rig.triggerCalls.isEmpty())
        assertTrue(rig.h.emitted.isEmpty())
    }

    @Test
    fun `WRITE 5 a cancelled plain write with no commit or an unreadable re-read records the failure once, sweeps nothing and rethrows`() {
        for (field in listOf(AppLockSettingField.DISCREET, AppLockSettingField.SECURE_SCREEN)) {
            val noCommit = disabledRig()
            noCommit.h.repository.plainWriteFailure = cancelled()
            assertRethrown {
                if (field == AppLockSettingField.DISCREET) noCommit.ops.requestDiscreetChange(true) else noCommit.ops.requestSecureScreenChange(true)
            }
            assertFailureRecordedOnce(noCommit, field)
            assertTrue("$field", noCommit.triggerCalls.isEmpty())

            val unreadable = disabledRig()
            unreadable.h.repository.plainWriteResult = { _, _ ->
                unreadable.h.repository.loadState = AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED)
                throw cancelled()
            }
            assertRethrown {
                if (field == AppLockSettingField.DISCREET) unreadable.ops.requestDiscreetChange(true) else unreadable.ops.requestSecureScreenChange(true)
            }
            assertFailureRecordedOnce(unreadable, field)
            assertTrue("$field", unreadable.triggerCalls.isEmpty())

            // The serializer was released: a later request on the first rig is accepted.
            noCommit.h.repository.plainWriteFailure = null
            assertEquals("$field", PlainSettingResult.COMMITTED, runBlocking {
                if (field == AppLockSettingField.DISCREET) noCommit.ops.requestDiscreetChange(true) else noCommit.ops.requestSecureScreenChange(true)
            })
        }
    }

    // --- Guardian fixes (S18 ii-a): lock-on mapping, throwing / cancelled re-reads, suppressed exceptions ----------

    private suspend fun plainRequest(rig: SettingsOperationsRig, field: AppLockSettingField, value: Boolean): PlainSettingResult =
        if (field == AppLockSettingField.DISCREET) rig.ops.requestDiscreetChange(value) else rig.ops.requestSecureScreenChange(value)

    /** The thrown cancellation (the instance may be a stack-trace-recovery copy: look along the cause chain). */
    private fun rethrownCancellation(block: suspend () -> Any?): Throwable {
        try {
            runBlocking { block() }
        } catch (e: CancellationException) {
            return e
        }
        throw AssertionError("the cancellation was swallowed")
    }

    private fun chain(t: Throwable): List<Throwable> = generateSequence(t) { it.cause }.toList()

    private fun suppressedMessages(t: Throwable): List<String?> = chain(t).flatMap { it.suppressed.asList() }.map { it.message }

    private fun assertOriginalCancellationPropagates(t: Throwable) {
        assertTrue("the original cancellation must propagate", chain(t).any { it is CancellationException && it.message == "cancelled" })
    }

    /** Sets the re-read failure from INSIDE the write, so the pre-read of the request has already succeeded. */
    private fun cancelAndBreakReread(rig: SettingsOperationsRig, reread: Throwable): Nothing {
        rig.h.repository.failure = reread
        throw cancelled()
    }

    @Test
    fun `WRITE 5 with the stored lock on a cancelled plain write is the forced answer for an equal and a different value, then rethrows`() {
        for (field in listOf(AppLockSettingField.DISCREET, AppLockSettingField.SECURE_SCREEN)) {
            for (equalToRequest in listOf(true, false)) {
                val label = "$field equal=$equalToRequest"
                val rig = unlockedRig(AutoLockTimeout.M5)
                val before = stored(rig).copy(discreetNotifications = equalToRequest, secureScreen = equalToRequest)
                rig.h.repository.loadState = AppLockSettingsLoadState.Loaded(before)
                rig.h.repository.plainWriteFailure = cancelled()

                assertRethrown { plainRequest(rig, field, true) }

                assertEquals(label, before, stored(rig))
                assertEquals(label, AppLockState.Unlocked, rig.controller.state.value)
                assertTrue("$label: no failure record", rig.h.emitted.isEmpty())
                assertTrue("$label: no trigger", rig.triggerCalls.isEmpty())

                // The serializer was released and the answer is the same as the uncancelled one: Rejected(FORCED).
                rig.h.repository.plainWriteFailure = null
                assertEquals(label, PlainSettingResult.REJECTED_FORCED, runBlocking { plainRequest(rig, field, true) })
                assertTrue(label, rig.h.emitted.isEmpty())
            }
        }
    }

    @Test
    fun `a cancellation thrown by the re-read itself counts as not committed, the original propagates and the second is suppressed`() {
        val direct = unlockedRig(AutoLockTimeout.M5)
        direct.h.repository.timeoutWriteResult = { cancelAndBreakReread(direct, CancellationException("second")) }
        val thrownDirect = rethrownCancellation { direct.ops.requestTimeoutChange(AutoLockTimeout.S30) }
        assertOriginalCancellationPropagates(thrownDirect)
        assertTrue(suppressedMessages(thrownDirect).contains("second"))
        assertFailureRecordedOnce(direct, AppLockSettingField.AUTO_LOCK_TIMEOUT)
        assertEquals(AutoLockTimeout.M5, stored(direct).autoLockTimeout)

        val plain = disabledRig()
        plain.h.repository.plainWriteResult = { _, _ -> cancelAndBreakReread(plain, CancellationException("second")) }
        val thrownPlain = rethrownCancellation { plain.ops.requestDiscreetChange(true) }
        assertOriginalCancellationPropagates(thrownPlain)
        assertTrue(suppressedMessages(thrownPlain).contains("second"))
        assertFailureRecordedOnce(plain, AppLockSettingField.DISCREET)
        assertTrue(plain.triggerCalls.isEmpty())

        val lock = disabledRig(deferred = true)
        lock.h.repository.lockWriteResult = { cancelAndBreakReread(lock, CancellationException("second")) }
        val applyLock = enableAndGetApply(lock)
        lock.h.settle()
        assertEquals(AppLockState.Disabled, lock.controller.state.value)
        assertFailureRecordedOnce(lock, AppLockSettingField.APP_LOCK_ENABLED)
        assertTrue(lock.triggerCalls.isEmpty())
        assertApplyEndedCancelledAndCompleted(lock, applyLock)
    }

    @Test
    fun `a throwing re-read isolated from the pre-read counts as not committed on both WRITE 4 paths and on WRITE 5`() {
        val secret = IllegalStateException("secret-path /data/user/0/x")

        val direct = unlockedRig(AutoLockTimeout.M5)
        direct.h.repository.timeoutWriteResult = { cancelAndBreakReread(direct, secret) }
        assertRethrown { direct.ops.requestTimeoutChange(AutoLockTimeout.S30) }
        assertFailureRecordedOnce(direct, AppLockSettingField.AUTO_LOCK_TIMEOUT)
        assertEquals(AppLockState.Unlocked, direct.controller.state.value)

        val prompt = unlockedRig(AutoLockTimeout.M1, deferred = true)
        prompt.h.repository.timeoutWriteResult = { cancelAndBreakReread(prompt, secret) }
        val applyPrompt = longerTimeoutApply(prompt)
        prompt.h.settle()
        assertTrue(applyPrompt.isCancelled)
        assertTrue(prompt.uncaught.isEmpty())
        assertFailureRecordedOnce(prompt, AppLockSettingField.AUTO_LOCK_TIMEOUT)

        for (field in listOf(AppLockSettingField.DISCREET, AppLockSettingField.SECURE_SCREEN)) {
            val plain = disabledRig()
            plain.h.repository.plainWriteResult = { _, _ -> cancelAndBreakReread(plain, secret) }
            assertRethrown { plainRequest(plain, field, true) }
            assertFailureRecordedOnce(plain, field)
            assertTrue("$field", plain.triggerCalls.isEmpty())
        }
    }

    @Test
    fun `DISABLE with a cancelled lock write and an unreadable re-read keeps the lock on and records the failure once`() {
        val rig = unlockedRig(AutoLockTimeout.M5, deferred = true)
        rig.h.repository.lockWriteResult = {
            rig.h.repository.loadState = AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED)
            throw cancelled()
        }
        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
        rig.h.settle()
        rig.h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        val apply = rig.job.children.single()
        rig.h.settle()

        assertTrue("the cancellation must propagate", apply.isCancelled)
        assertTrue(rig.uncaught.isEmpty())
        assertEquals("the lock stays on", AppLockState.Unlocked, rig.controller.state.value)
        assertFailureRecordedOnce(rig, AppLockSettingField.APP_LOCK_ENABLED)
        assertTrue(rig.triggerCalls.isEmpty())
        // The completion ran in the finally (INV-54): a new DISABLE attempt may start.
        assertTrue(rig.controller.startOpenOriginAttempt(OpenOriginPrompt.DISABLE, RecordingSuccessHandler()) is OpenOriginStartResult.Started)
    }

    @Test
    fun `a failure of the outcome step is kept as suppressed on the rethrown cancellation`() {
        val rig = disabledRig()
        rig.h.repository.plainWriteResult = { _, v -> commitThenCancel(rig) { it.copy(discreetNotifications = v) } }
        rig.triggerFailure = IllegalStateException("trigger-failed")

        val thrown = rethrownCancellation { rig.ops.requestDiscreetChange(true) }

        assertOriginalCancellationPropagates(thrown)
        assertTrue(suppressedMessages(thrown).contains("trigger-failed"))
        assertEquals(listOf(false to true), rig.triggerCalls)
    }
}
