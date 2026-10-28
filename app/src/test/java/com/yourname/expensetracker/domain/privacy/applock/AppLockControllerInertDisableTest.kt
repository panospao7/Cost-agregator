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
import org.junit.Test

/**
 * S18 part (ii-e): T19, the disable of the lock while `INERT`, with no authentication (INV-3, INV-44, INV-45, INV-46,
 * INV-13, D3, D6, F2, F7), over [AppLockSettingsOperations] and one [AppLockController]. Conditions (a) to (f) of the
 * handoff and the F2 binding. Setup noise (prompts, diagnostics) is cleared before each scenario. The apply runs on the
 * rig's unconfined scope, so it has run (or is suspended inside the serializer or the write) when `requestDisable` returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockControllerInertDisableTest {

    private val inertCapabilities = listOf(
        DeviceAuthCapability.NO_CREDENTIAL to InertReason.NO_CREDENTIAL,
        DeviceAuthCapability.HW_UNAVAILABLE to InertReason.HW_UNAVAILABLE,
        DeviceAuthCapability.UNKNOWN to InertReason.UNKNOWN,
        DeviceAuthCapability.UNSUPPORTED_API to InertReason.API_UNSUPPORTED
    )

    // --- Rigs --------------------------------------------------------------------------------------------------

    /** Cold start over [store] with a capability that cannot authenticate: `INERT(reason)` straight from resolution. */
    private fun coldInert(
        store: AppLockSettingsLoadState = AppLockTestSettings.enabled(),
        capability: DeviceAuthCapability = DeviceAuthCapability.NO_CREDENTIAL,
        atRequest: DeviceAuthCapability = capability,
        serializer: AppLockSettingsWriteSerializer = AppLockSettingsWriteSerializer()
    ): SettingsOperationsRig {
        val h = AppLockControllerHarness(store, capability)
        val controller = h.resolvedController()
        assertTrue("expected INERT, was ${controller.state.value}", controller.state.value is AppLockState.Inert)
        h.capabilityProvider.capability = atRequest
        return cleared(SettingsOperationsRig(h, controller, serializer = serializer))
    }

    /** `INERT(NO_CREDENTIAL)` reached from an unlocked session (T16), so the controller can later return to `UNLOCKED` (T15). */
    private fun warmInert(
        serializer: AppLockSettingsWriteSerializer = AppLockSettingsWriteSerializer(),
        store: AppLockSettingsLoadState = AppLockTestSettings.enabled()
    ): SettingsOperationsRig {
        val h = AppLockControllerHarness(store)
        val controller = h.resolvedController()
        h.unlock(controller)
        controller.onAppBackgrounded()
        h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        controller.onAppForegrounded()
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        return cleared(SettingsOperationsRig(h, controller, serializer = serializer))
    }

    private fun cleared(rig: SettingsOperationsRig): SettingsOperationsRig {
        rig.h.emitted.clear()
        rig.h.emittedMetadata.clear()
        rig.h.authenticator.calls.clear()
        return rig
    }

    private fun count(rig: SettingsOperationsRig, code: PrivacyDiagnosticCode): Int = rig.h.emitted.count { it == code }

    private fun storedSettings(rig: SettingsOperationsRig): AppLockPrivacySettings =
        (rig.h.repository.loadState as AppLockSettingsLoadState.Loaded).settings

    private fun assertNothingWritten(rig: SettingsOperationsRig) {
        assertTrue("no lock write", rig.h.repository.lockWrites.isEmpty())
        assertEquals("no write of any kind reached the store", 0, rig.h.repository.writeAttempts)
        assertTrue("no timeout write", rig.h.repository.timeoutWrites.isEmpty())
        assertTrue("no plain write", rig.h.repository.plainWrites.isEmpty())
        assertTrue("no prompt", rig.h.authenticator.calls.isEmpty())
        assertTrue("no trigger", rig.triggerCalls.isEmpty())
        assertTrue(rig.uncaught.isEmpty())
    }

    private fun assertPersistFailedOnce(rig: SettingsOperationsRig) {
        assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED))
        val index = rig.h.emitted.indexOf(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.FIELD to AppLockSettingField.APP_LOCK_ENABLED),
            rig.h.emittedMetadata[index].entries
        )
    }

    // --- (a), (c), (e), (f): the committed path ----------------------------------------------------------------

    @Test
    fun `T19 a committed INERT disable moves to DISABLED after the write, records LOCK_DISABLED, prompts nothing and re-posts nothing`() {
        for ((capability, reason) in inertCapabilities) {
            val rig = coldInert(capability = capability)
            assertEquals(AppLockState.Inert(reason), rig.controller.state.value)

            assertEquals("$capability", SettingsRequestResult.STARTED, rig.ops.requestDisable())
            rig.h.settle()

            assertEquals("$capability", listOf(false), rig.h.repository.lockWrites)
            assertEquals("$capability", false, storedSettings(rig).appLockEnabled)
            assertEquals("$capability", AppLockState.Disabled, rig.controller.state.value)
            assertEquals("$capability", listOf(PrivacyDiagnosticCode.LOCK_DISABLED), rig.h.emitted)
            assertTrue("$capability: no prompt", rig.h.authenticator.calls.isEmpty())
            assertTrue("$capability: disabling never re-posts (INV-13)", rig.triggerCalls.isEmpty())
            assertTrue(rig.uncaught.isEmpty())
            // The apply released the one-apply flag: a second request is a plain not-in-origin from DISABLED.
            assertEquals("$capability", SettingsRequestResult.NOT_IN_ORIGIN, rig.ops.requestDisable())
        }
    }

    @Test
    fun `T19 disabling a lock whose discreet and secure values are on re-posts nothing`() {
        val store = AppLockSettingsLoadState.Loaded(
            AppLockPrivacySettings(
                appLockEnabled = true,
                autoLockTimeout = AutoLockTimeout.M5,
                discreetNotifications = true,
                secureScreen = true
            )
        )
        val rig = coldInert(store = store)

        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())

        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertTrue("no trigger call on disable, not even a no-op one", rig.triggerCalls.isEmpty())
        assertEquals(AutoLockTimeout.M5, storedSettings(rig).autoLockTimeout)
    }

    @Test
    fun `T19 a committed disable discards the pending background, and a later foreground stays DISABLED`() {
        val rig = warmInert()
        rig.controller.onAppBackgrounded()

        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
        rig.controller.onAppForegrounded()

        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertEquals(null, rig.controller.pendingBackgroundAtMillis)
    }

    // --- (a): the fresh capability decides ---------------------------------------------------------------------

    @Test
    fun `T19 a fresh AVAILABLE or CHECK_FAILED capability at the request is NOT_IN_ORIGIN with nothing written`() {
        for (fresh in listOf(DeviceAuthCapability.AVAILABLE, DeviceAuthCapability.CHECK_FAILED)) {
            val rig = coldInert(atRequest = fresh) // the state is still INERT(NO_CREDENTIAL): a stale AVAILABLE capability view

            assertEquals("$fresh", SettingsRequestResult.NOT_IN_ORIGIN, rig.ops.requestDisable())
            rig.h.settle()

            assertNothingWritten(rig)
            assertEquals("$fresh", AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)
            assertTrue("$fresh: no diagnostic", rig.h.emitted.isEmpty())
            assertEquals("$fresh", true, storedSettings(rig).appLockEnabled)
        }
    }

    @Test
    fun `T19 a throwing capability read counts as CHECK_FAILED, so it is NOT_IN_ORIGIN with nothing written`() {
        val rig = coldInert()
        rig.h.capabilityProvider.failure = IllegalStateException("binder")

        assertEquals(SettingsRequestResult.NOT_IN_ORIGIN, rig.ops.requestDisable())

        assertNothingWritten(rig)
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)
    }

    @Test
    fun `T19 a refusal releases the one-apply flag, so the next request with a no-auth capability goes through`() {
        val rig = coldInert()
        rig.h.capabilityProvider.capability = DeviceAuthCapability.AVAILABLE
        assertEquals(SettingsRequestResult.NOT_IN_ORIGIN, rig.ops.requestDisable())

        rig.h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())

        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertEquals(listOf(false), rig.h.repository.lockWrites)
    }

    @Test
    fun `T19 a cancelled capability read propagates and releases the flag`() {
        val rig = coldInert()
        rig.h.capabilityProvider.failure = CancellationException("c")

        try {
            rig.ops.requestDisable()
            org.junit.Assert.fail("the cancellation was swallowed")
        } catch (e: CancellationException) {
            // propagated
        }
        assertNothingWritten(rig)

        rig.h.capabilityProvider.failure = null
        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
    }

    // --- (d): unreadable settings need the review-save ---------------------------------------------------------

    @Test
    fun `T19 INERT(SETTINGS_UNREADABLE_NO_AUTH) is REVIEW_REQUIRED with nothing written, for every cause and every capability`() {
        for (cause in SettingsUnreadableCause.entries) {
            for (atRequest in listOf(DeviceAuthCapability.NO_CREDENTIAL, DeviceAuthCapability.AVAILABLE, DeviceAuthCapability.CHECK_FAILED)) {
                val rig = coldInert(store = AppLockTestSettings.unreadable(cause), atRequest = atRequest)
                assertEquals(AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), rig.controller.state.value)

                assertEquals("$cause/$atRequest", SettingsRequestResult.REVIEW_REQUIRED, rig.ops.requestDisable())
                rig.h.settle()

                assertNothingWritten(rig)
                assertEquals("$cause/$atRequest", AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), rig.controller.state.value)
                assertTrue("$cause/$atRequest: no diagnostic", rig.h.emitted.isEmpty())
                assertTrue("$cause/$atRequest: the store is untouched", rig.h.repository.loadState is AppLockSettingsLoadState.Unreadable)
            }
        }
    }

    @Test
    fun `T19 REVIEW_REQUIRED releases the flag and a later review-save still works`() {
        val rig = coldInert(store = AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED))
        assertEquals(SettingsRequestResult.REVIEW_REQUIRED, rig.ops.requestDisable())

        val saved = runBlocking {
            rig.ops.requestReviewSave(AppLockPrivacySettings(appLockEnabled = false))
        }

        assertEquals(ReviewSaveResult.COMMITTED, saved)
        assertEquals(AppLockState.Disabled, rig.controller.state.value)
    }

    // --- (b): the id-bound controller entries ------------------------------------------------------------------

    @Test
    fun `T19 beginInertDisable is NotInOrigin outside INERT and ReviewRequired for unreadable settings, and records nothing`() {
        val (hUnlocked, unlocked) = unlockedControllerFor()
        assertEquals(InertDisableBegin.NotInOrigin, unlocked.beginInertDisable())
        assertTrue(hUnlocked.emitted.none { it == PrivacyDiagnosticCode.LOCK_DISABLED })

        val hDisabled = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        assertEquals(InertDisableBegin.NotInOrigin, hDisabled.resolvedController().beginInertDisable())

        val hLocked = AppLockControllerHarness(AppLockTestSettings.enabled())
        assertEquals(InertDisableBegin.NotInOrigin, hLocked.resolvedController().beginInertDisable())

        val hReview = AppLockControllerHarness(
            AppLockTestSettings.unreadable(SettingsUnreadableCause.INVALID_VALUE),
            DeviceAuthCapability.NO_CREDENTIAL
        )
        assertEquals(InertDisableBegin.ReviewRequired, hReview.resolvedController().beginInertDisable())

        val hInert = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
        val inert = hInert.resolvedController()
        assertTrue(inert.beginInertDisable() is InertDisableBegin.Begun)
    }

    @Test
    fun `T19 onInertDisableApplied applies only for the exact unconsumed id`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()
        h.emitted.clear()
        val id = (controller.beginInertDisable() as InertDisableBegin.Begun).id

        for (wrong in listOf(id + 1, id - 1, 0L, -1L, Long.MAX_VALUE)) {
            controller.onInertDisableApplied(wrong, committed = true)
            controller.onInertDisableApplied(wrong, committed = false)
        }
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        assertTrue("a wrong id records nothing", h.emitted.isEmpty())

        controller.onInertDisableApplied(id, committed = true)
        assertEquals(AppLockState.Disabled, controller.state.value)
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_DISABLED), h.emitted)

        controller.onInertDisableApplied(id, committed = true) // the token is consumed
        assertEquals(listOf(PrivacyDiagnosticCode.LOCK_DISABLED), h.emitted)
    }

    @Test
    fun `T19 a newer begin replaces an older token, so the old id applies nothing`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()
        val first = (controller.beginInertDisable() as InertDisableBegin.Begun).id
        val second = (controller.beginInertDisable() as InertDisableBegin.Begun).id
        assertTrue(second > first)

        controller.onInertDisableApplied(first, committed = true)
        assertTrue(controller.state.value is AppLockState.Inert)

        controller.onInertDisableApplied(second, committed = true)
        assertEquals(AppLockState.Disabled, controller.state.value)
    }

    @Test
    fun `T19 while an apply is in flight a second disable and an enable answer ALREADY_IN_FLIGHT, and a held serializer defers the write`() = runBlocking {
        val serializer = AppLockSettingsWriteSerializer()
        val rig = coldInert(serializer = serializer)
        val gate = CompletableDeferred<Unit>()
        try {
            val holder = async(start = CoroutineStart.UNDISPATCHED) { serializer.withExclusiveWrite { gate.await() } }

            assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
            assertTrue("the apply waits for the serializer", rig.h.repository.lockWrites.isEmpty())
            assertEquals(SettingsRequestResult.ALREADY_IN_FLIGHT, rig.ops.requestDisable())
            assertEquals(SettingsRequestResult.ALREADY_IN_FLIGHT, rig.ops.requestEnable())
            assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)

            gate.complete(Unit)
            holder.await()
            rig.h.settle()

            assertEquals(listOf(false), rig.h.repository.lockWrites)
            assertEquals(AppLockState.Disabled, rig.controller.state.value)
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun `T19 INERT ending during the write - the write commits, the state is not moved to DISABLED, the view and the code follow the commit`() {
        val rig = warmInert()
        val gate = CompletableDeferred<Unit>()
        try {
            rig.h.repository.lockWriteGate = gate
            assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
            assertEquals("the apply is inside the durable write", listOf(false), rig.h.repository.lockWrites)

            // Authentication became possible again and the person returned within the timeout (T15): INERT ended.
            rig.h.capabilityProvider.capability = DeviceAuthCapability.AVAILABLE
            rig.controller.onAppBackgrounded()
            rig.controller.onAppForegrounded()
            val moved = rig.controller.state.value
            assertFalse("INERT ended", moved is AppLockState.Inert)
            assertFalse(moved == AppLockState.Disabled)

            gate.complete(Unit)

            assertEquals("fail closed: a moved state is never moved to DISABLED", moved, rig.controller.state.value)
            assertEquals(false, storedSettings(rig).appLockEnabled)
            assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_DISABLED))
            assertTrue(rig.triggerCalls.isEmpty())
            assertTrue(rig.uncaught.isEmpty())

            // Privacy review finding 1: the controller's settings VIEW follows the commit. A view still showing the
            // lock on (with the M1 timeout of the setup) would T14-lock this session after the timeout; a view that
            // follows the commit (lock off) leaves it unlocked.
            assertEquals(AppLockState.Unlocked, moved)
            rig.controller.onAppBackgrounded()
            rig.h.clock.advance(120_000L) // well past the M1 timeout of the setup store
            rig.controller.onAppForegrounded()
            assertEquals(
                "the view follows the commit: no T14 lock over a lock-off view",
                AppLockState.Unlocked,
                rig.controller.state.value
            )
            assertTrue(rig.h.authenticator.calls.isEmpty())
            assertTrue(rig.uncaught.isEmpty())
        } finally {
            gate.complete(Unit)
        }
    }

    // --- (a): the D3 re-proof INSIDE the serializer ------------------------------------------------------------

    @Test
    fun `C1 a disable that waited for the serializer writes nothing when authentication became possible meanwhile`() = runBlocking {
        for (becomes in listOf(DeviceAuthCapability.AVAILABLE, DeviceAuthCapability.CHECK_FAILED)) {
            val serializer = AppLockSettingsWriteSerializer()
            val rig = coldInert(serializer = serializer) // NO_CREDENTIAL at the request: the no-auth path is taken
            val gate = CompletableDeferred<Unit>()
            try {
                val holder = async(start = CoroutineStart.UNDISPATCHED) { serializer.withExclusiveWrite { gate.await() } }
                assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
                rig.h.capabilityProvider.capability = becomes

                gate.complete(Unit)
                holder.await()
                rig.h.settle()

                assertNothingWritten(rig)
                assertEquals("$becomes", AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)
                assertTrue("$becomes: no diagnostic", rig.h.emitted.isEmpty())
                assertEquals("$becomes", true, storedSettings(rig).appLockEnabled)

                // The flag was released: with the no-auth capability back, a new request goes through.
                rig.h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
                assertEquals("$becomes", SettingsRequestResult.STARTED, rig.ops.requestDisable())
                assertEquals("$becomes", AppLockState.Disabled, rig.controller.state.value)
            } finally {
                gate.complete(Unit)
            }
        }
    }

    @Test
    fun `C1 a disable that waited for the serializer writes nothing when the state left INERT meanwhile, even with a no-auth capability`() = runBlocking {
        val serializer = AppLockSettingsWriteSerializer()
        val rig = warmInert(serializer)
        val gate = CompletableDeferred<Unit>()
        try {
            val holder = async(start = CoroutineStart.UNDISPATCHED) { serializer.withExclusiveWrite { gate.await() } }
            assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())

            rig.h.capabilityProvider.capability = DeviceAuthCapability.AVAILABLE
            rig.controller.onAppBackgrounded()
            rig.controller.onAppForegrounded()
            assertFalse(rig.controller.state.value is AppLockState.Inert)
            val moved = rig.controller.state.value
            rig.h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL // only the state check can refuse now

            gate.complete(Unit)
            holder.await()
            rig.h.settle()

            assertNothingWritten(rig)
            assertEquals(moved, rig.controller.state.value)
            assertTrue(rig.h.emitted.none { it == PrivacyDiagnosticCode.LOCK_DISABLED })
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun `C1 a disable that waited for the serializer writes nothing when the store became unreadable meanwhile`() = runBlocking {
        val serializer = AppLockSettingsWriteSerializer()
        val rig = coldInert(serializer = serializer)
        val gate = CompletableDeferred<Unit>()
        try {
            val holder = async(start = CoroutineStart.UNDISPATCHED) { serializer.withExclusiveWrite { gate.await() } }
            assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
            rig.h.repository.loadState = AppLockTestSettings.unreadable(SettingsUnreadableCause.CORRUPTED)

            gate.complete(Unit)
            holder.await()
            rig.h.settle()

            assertNothingWritten(rig)
            assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)
            assertTrue(rig.h.emitted.isEmpty())
        } finally {
            gate.complete(Unit)
        }
    }

    // --- (c): a failed write stays INERT, once --------------------------------------------------------------------

    @Test
    fun `T19 a write that did not commit stays INERT, records PERSIST_FAILED(APP_LOCK_ENABLED) once and writes nothing else`() {
        val failures: List<Pair<String, (SettingsOperationsRig) -> Unit>> = listOf(
            "Failed" to { rig ->
                rig.h.repository.lockWriteResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED) }
            },
            "Rejected" to { rig ->
                rig.h.repository.lockWriteResult = { AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE) }
            },
            "thrown" to { rig -> rig.h.repository.lockWriteFailure = IllegalStateException("disk") }
        )
        for ((name, arrange) in failures) {
            val rig = coldInert()
            arrange(rig)

            assertEquals(name, SettingsRequestResult.STARTED, rig.ops.requestDisable())
            rig.h.settle()

            assertEquals(name, AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)
            assertEquals(name, listOf(false), rig.h.repository.lockWrites)
            assertEquals(name, true, storedSettings(rig).appLockEnabled)
            assertPersistFailedOnce(rig)
            assertEquals(name, 0, count(rig, PrivacyDiagnosticCode.LOCK_DISABLED))
            assertTrue(name, rig.triggerCalls.isEmpty())
            assertTrue(name, rig.uncaught.isEmpty())

            // The claim-once rule: a second failure records nothing more, and the flag was released (it ran again).
            assertEquals(name, SettingsRequestResult.STARTED, rig.ops.requestDisable())
            assertEquals(name, listOf(false, false), rig.h.repository.lockWrites)
            assertEquals(name, 1, count(rig, PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED))
            assertEquals(name, AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)
        }
    }

    @Test
    fun `T19 after a failed write a retry that commits moves to DISABLED`() {
        val rig = coldInert()
        rig.h.repository.lockWriteResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED) }
        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)

        rig.h.repository.lockWriteResult = null
        assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())

        assertEquals(AppLockState.Disabled, rig.controller.state.value)
        assertEquals(1, count(rig, PrivacyDiagnosticCode.LOCK_DISABLED))
    }

    // --- (f): a cancelled durable write is settled by one re-read, and the cancellation is rethrown -----------------

    @Test
    fun `F7 a cancelled write whose re-read shows lock off is the committed path, and the cancellation ends the apply`() {
        val rig = coldInert()
        val gate = CompletableDeferred<Unit>()
        try {
            rig.h.repository.lockWriteGate = gate
            assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
            val apply = rig.job.children.single()
            assertTrue(apply.isActive)

            // The store edit committed, then the caller was cancelled.
            rig.h.repository.loadState = AppLockSettingsLoadState.Loaded(storedSettings(rig).copy(appLockEnabled = false))
            rig.h.repository.lockWriteFailure = CancellationException("c")
            gate.complete(Unit)

            assertTrue("the cancellation was rethrown, not swallowed", apply.isCancelled)
            assertEquals(AppLockState.Disabled, rig.controller.state.value)
            assertEquals(listOf(PrivacyDiagnosticCode.LOCK_DISABLED), rig.h.emitted)
            assertTrue(rig.triggerCalls.isEmpty())
            assertTrue(rig.uncaught.isEmpty())

            // The one-apply flag was released by the finally.
            rig.h.repository.lockWriteFailure = null
            assertEquals(SettingsRequestResult.NOT_IN_ORIGIN, rig.ops.requestDisable())
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun `F7 a cancelled write whose re-read shows lock on is not committed - INERT stays, the failure is recorded once`() {
        val rig = coldInert()
        val gate = CompletableDeferred<Unit>()
        try {
            rig.h.repository.lockWriteGate = gate
            assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
            val apply = rig.job.children.single()

            rig.h.repository.lockWriteFailure = CancellationException("c")
            gate.complete(Unit)

            assertTrue(apply.isCancelled)
            assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)
            assertPersistFailedOnce(rig)
            assertEquals(0, count(rig, PrivacyDiagnosticCode.LOCK_DISABLED))
            assertTrue(rig.triggerCalls.isEmpty())

            rig.h.repository.lockWriteFailure = null
            assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
            assertEquals(AppLockState.Disabled, rig.controller.state.value)
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun `F7 a cancelled write whose re-read throws is not committed`() {
        val rig = coldInert()
        val gate = CompletableDeferred<Unit>()
        try {
            rig.h.repository.lockWriteGate = gate
            assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
            val apply = rig.job.children.single()

            rig.h.repository.lockWriteFailure = CancellationException("c")
            rig.h.repository.failure = IllegalStateException("read") // the re-read throws
            gate.complete(Unit)

            assertTrue(apply.isCancelled)
            assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), rig.controller.state.value)
            assertPersistFailedOnce(rig)
            assertEquals(0, count(rig, PrivacyDiagnosticCode.LOCK_DISABLED))
        } finally {
            gate.complete(Unit)
        }
    }

    // --- F2, D6: a committed T19 bumps writeSequence ---------------------------------------------------------------

    /** A resolved controller in `INERT(SETTINGS_UNREADABLE_NO_AUTH)` over a TRANSIENT unreadable store (a re-read is allowed). */
    private fun transientInert(): AppLockControllerHarness = AppLockControllerHarness(
        AppLockTestSettings.unreadable(SettingsUnreadableCause.IO_ERROR),
        DeviceAuthCapability.NO_CREDENTIAL
    )

    @Test
    fun `F2 D6 a re-read still in flight when a T19 write commits drops its result`() {
        val h = transientInert()
        val controller = h.resolvedController()
        assertEquals(AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), controller.state.value)
        val gate = CompletableDeferred<Unit>()
        try {
            h.repository.scriptRead(AppLockTestSettings.DISABLED, gate)
            controller.requestSettingsReread()

            // A committed T19 write is counted even when no token matches (fail closed, like onOpenOriginSettingApplied).
            controller.onInertDisableApplied(id = 4_242L, committed = true)
            gate.complete(Unit)
        } finally {
            gate.complete(Unit)
        }

        assertEquals(AppLockState.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), controller.state.value) // T28 did not apply DISABLED
    }

    @Test
    fun `D6 a T19 write that did not commit does not drop an in-flight re-read`() {
        val h = transientInert()
        val controller = h.resolvedController()
        val gate = CompletableDeferred<Unit>()
        try {
            h.repository.scriptRead(AppLockTestSettings.DISABLED, gate)
            controller.requestSettingsReread()

            controller.onInertDisableApplied(id = 4_242L, committed = false)
            gate.complete(Unit)
        } finally {
            gate.complete(Unit)
        }

        assertEquals(AppLockState.Disabled, controller.state.value) // the re-read applied (T28)
    }
}
