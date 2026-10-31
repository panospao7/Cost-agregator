package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList

/**
 * S22-a.0 (D-S22-1): the read-only `openOriginEnd` signal. It tells how an ENABLE / DISABLE / CHANGE attempt ended,
 * which the state alone cannot (ENABLE success goes `DISABLED` to `UNLOCKED`, a failure returns to `DISABLED`).
 *
 * Covered: every closing path and its kind (T34, T35, the watchdog, launch failures, a cancelled launch, an
 * out-of-step close); APPLIED / NOT_COMMITTED only at the apply and only after the T17 / T18 state move; stale ids;
 * an UNLOCK attempt never publishing; the T19 INERT disable; and that the signal carries no settings, adds no
 * diagnostic and publishes outside the controller's monitors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockControllerOpenOriginEndTest {

    private val knownCodes = setOf(
        PrivacyDiagnosticCode.LOCK_AUTH_CANCELLED,
        PrivacyDiagnosticCode.LOCK_AUTH_LOCKOUT,
        PrivacyDiagnosticCode.LOCK_AUTH_ERROR,
        PrivacyDiagnosticCode.LOCK_UNAVAILABLE_CAPABILITY,
        PrivacyDiagnosticCode.LOCK_UNAVAILABLE_NOT_SECURE,
        PrivacyDiagnosticCode.LOCK_UNAVAILABLE_API
    )

    /**
     * Collects `openOriginEnd` on an unconfined collector, recording each emission, the state at that moment and whether
     * a controller monitor was held by the collecting thread (it must not be).
     */
    private class Ends(controller: AppLockController) {
        val seen = CopyOnWriteArrayList<OpenOriginEnd?>()
        val stateAtEmission = CopyOnWriteArrayList<AppLockState>()
        val monitorHeld = CopyOnWriteArrayList<Boolean>()
        private val locks: List<Any> = listOf("stateLock", "publishLock").map { name ->
            AppLockController::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(controller)!!
        }
        private val job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            controller.openOriginEnd.collect { end ->
                seen += end
                stateAtEmission += controller.state.value
                monitorHeld += locks.any { Thread.holdsLock(it) }
            }
        }

        /** The non-null emissions, in order. */
        val published: List<OpenOriginEnd> get() = seen.filterNotNull()

        /** The state read by the collector when the latest non-null end arrived. */
        fun stateAtLastPublish(): AppLockState = stateAtEmission[seen.indexOfLast { it != null }]

        fun close() {
            job.cancel()
        }
    }

    private inline fun <T> collecting(controller: AppLockController, block: (Ends) -> T): T {
        val ends = Ends(controller)
        try {
            return block(ends)
        } finally {
            ends.close()
        }
    }

    private fun controllerFor(prompt: OpenOriginPrompt): Pair<AppLockControllerHarness, AppLockController> {
        val rig = if (prompt.purpose == AuthPurpose.ENABLE) disabledControllerFor() else unlockedControllerFor()
        rig.first.emitted.clear()
        rig.first.emittedMetadata.clear()
        return rig
    }

    private fun originOf(prompt: OpenOriginPrompt): AppLockState =
        if (prompt.purpose == AuthPurpose.ENABLE) AppLockState.Disabled else AppLockState.Unlocked

    private fun end(id: Long, prompt: OpenOriginPrompt, kind: OpenOriginEndKind) = OpenOriginEnd(id, prompt, kind)

    /** Starts an attempt and lets its prompt succeed: the close hands over and publishes nothing yet. Returns the id. */
    private fun succeed(h: AppLockControllerHarness, controller: AppLockController, prompt: OpenOriginPrompt): Long {
        val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
        h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
        return id
    }

    // --- Shape ---------------------------------------------------------------------------------------------------

    @Test
    fun `the end value is a bounded triple of an id, the closed prompt selector and the closed kind, and the signal is read-only`() {
        val fields = OpenOriginEnd::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.type }
            .toSet()
        assertEquals(
            setOf<Class<*>>(Long::class.javaPrimitiveType!!, OpenOriginPrompt::class.java, OpenOriginEndKind::class.java),
            fields
        )
        assertEquals(
            listOf(
                "APPLIED", "NOT_COMMITTED", "CANCELLED", "LOCKED_OUT", "NOT_AVAILABLE", "ERROR", "HOST_LOST", "BACKGROUNDED"
            ),
            OpenOriginEndKind.entries.map { it.name }
        )

        val h = AppLockControllerHarness(AppLockTestSettings.DISABLED)
        val controller = h.newController()
        assertNull(controller.openOriginEnd.value) // before resolution
        assertFalse(controller.openOriginEnd is MutableStateFlow<*>)
        runResolve(h, controller)
        assertNull(controller.openOriginEnd.value) // resolution is not an attempt
    }

    private fun runResolve(h: AppLockControllerHarness, controller: AppLockController) {
        kotlinx.coroutines.runBlocking { controller.resolve() }
        h.settle()
    }

    // --- UNLOCK attempts never publish -----------------------------------------------------------------------------

    private class UnlockCase(
        val name: String,
        val setup: (AppLockControllerHarness) -> Unit = {},
        val act: (AppLockControllerHarness, AppLockController, Long) -> Unit = { _, _, _ -> }
    )

    @Test
    fun `an UNLOCK attempt never publishes, whichever way it ends`() {
        val cases = listOf(
            UnlockCase("success", act = { h, _, _ -> h.authenticator.deliver(DeviceAuthOutcome.Succeeded) }),
            UnlockCase("cancel", act = { h, _, _ -> h.authenticator.deliver(DeviceAuthOutcome.Cancelled) }),
            UnlockCase("lockout", act = { h, _, _ -> h.authenticator.deliver(DeviceAuthOutcome.LockedOut(permanent = false)) }),
            UnlockCase("error", act = { h, _, _ -> h.authenticator.deliver(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER)) }),
            UnlockCase("not available then INERT", act = { h, _, _ ->
                h.capabilityProvider.capability = DeviceAuthCapability.NO_CREDENTIAL
                h.authenticator.deliver(DeviceAuthOutcome.NotAvailable)
            }),
            UnlockCase("host lost", act = { _, controller, id -> controller.onPromptHostLost(id) }),
            UnlockCase("watchdog", act = { h, _, _ -> h.advance(AppLockController.PROMPT_WATCHDOG_MILLIS) }),
            UnlockCase(
                "launch not possible",
                setup = { h -> h.authenticator.defaultResult = AuthLaunch.HOST_UNAVAILABLE }
            ),
            UnlockCase(
                "launch throws",
                setup = { h -> h.authenticator.throwOnAuthenticate = IllegalStateException("boom") }
            )
        )
        for (case in cases) {
            val (h, controller) = lockedControllerFor(LockCause.COLD_START)
            case.setup(h)
            collecting(controller) { ends ->
                val id = h.tap(controller)
                case.act(h, controller, id)
                h.settle()
                assertEquals(case.name, listOf<OpenOriginEnd?>(null), ends.seen.toList())
                assertNull(case.name, controller.openOriginEnd.value)
            }
        }
    }

    // --- Non-success closes ----------------------------------------------------------------------------------------

    @Test
    fun `every prompt outcome that is not a success publishes its kind after the origin state, with no diagnostic of its own`() {
        val cases = buildList<Pair<DeviceAuthOutcome, OpenOriginEndKind>> {
            add(DeviceAuthOutcome.Cancelled to OpenOriginEndKind.CANCELLED)
            add(DeviceAuthOutcome.LockedOut(permanent = false) to OpenOriginEndKind.LOCKED_OUT)
            add(DeviceAuthOutcome.LockedOut(permanent = true) to OpenOriginEndKind.LOCKED_OUT)
            DeviceAuthErrorBucket.entries.forEach { add(DeviceAuthOutcome.Failed(it) to OpenOriginEndKind.ERROR) }
        }
        for (prompt in PUBLIC_OPEN_ORIGIN_PROMPTS) {
            for ((outcome, kind) in cases) {
                val label = "$prompt / $outcome"
                val (h, controller) = controllerFor(prompt)
                val handler = RecordingSuccessHandler()
                collecting(controller) { ends ->
                    val id = h.startOpenOrigin(controller, prompt, handler)
                    assertTrue(label, ends.published.isEmpty()) // opening the prompt is not an end

                    h.authenticator.deliver(outcome)

                    assertEquals(label, listOf(end(id, prompt, kind)), ends.published)
                    assertEquals(label, originOf(prompt), ends.stateAtLastPublish()) // published after the state move
                    assertEquals(label, originOf(prompt), controller.state.value)
                    assertTrue(label, ends.monitorHeld.none { it })
                    assertTrue(label, handler.delivered.isEmpty())
                    assertEquals(label, 0, h.repository.writeAttempts)
                    assertTrue(label, h.emitted.all { it in knownCodes })

                    // A second attempt that ends the same way is a new value (its id differs), never dropped as equal.
                    val second = h.startOpenOrigin(controller, prompt, handler)
                    h.authenticator.deliver(outcome)
                    assertEquals(label, listOf(end(id, prompt, kind), end(second, prompt, kind)), ends.published)
                    assertTrue(label, second > id)
                }
            }
        }
    }

    @Test
    fun `NotAvailable is a refusal for ENABLE and a bounded error otherwise, by the capability re-check`() {
        val enableKinds = mapOf(
            DeviceAuthCapability.HW_UNAVAILABLE to OpenOriginEndKind.NOT_AVAILABLE,
            DeviceAuthCapability.UNKNOWN to OpenOriginEndKind.NOT_AVAILABLE,
            DeviceAuthCapability.CHECK_FAILED to OpenOriginEndKind.NOT_AVAILABLE,
            DeviceAuthCapability.NO_CREDENTIAL to OpenOriginEndKind.NOT_AVAILABLE,
            DeviceAuthCapability.UNSUPPORTED_API to OpenOriginEndKind.NOT_AVAILABLE,
            DeviceAuthCapability.AVAILABLE to OpenOriginEndKind.ERROR
        )
        assertEquals(DeviceAuthCapability.entries.toSet(), enableKinds.keys)
        for (prompt in PUBLIC_OPEN_ORIGIN_PROMPTS) {
            for (capability in DeviceAuthCapability.entries) {
                val label = "$prompt / $capability"
                val expected = if (prompt == OpenOriginPrompt.ENABLE) enableKinds.getValue(capability) else OpenOriginEndKind.ERROR
                val (h, controller) = controllerFor(prompt)
                collecting(controller) { ends ->
                    val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
                    h.capabilityProvider.capability = capability

                    h.authenticator.deliver(DeviceAuthOutcome.NotAvailable)

                    assertEquals(label, listOf(end(id, prompt, expected)), ends.published)
                    assertEquals(label, originOf(prompt), controller.state.value)
                    assertTrue(label, h.emitted.all { it in knownCodes })
                }
            }
        }
    }

    @Test
    fun `a prompt that never launched publishes ERROR, so nobody waits for an end that never comes`() {
        for (result in listOf(AuthLaunch.ALREADY_IN_FLIGHT, AuthLaunch.HOST_UNAVAILABLE, AuthLaunch.PROMPT_UNAVAILABLE)) {
            for (prompt in PUBLIC_OPEN_ORIGIN_PROMPTS) {
                val label = "$result / $prompt"
                val (h, controller) = controllerFor(prompt)
                h.authenticator.defaultResult = result
                collecting(controller) { ends ->
                    val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())

                    assertEquals(label, listOf(end(id, prompt, OpenOriginEndKind.ERROR)), ends.published)
                    assertEquals(label, originOf(prompt), ends.stateAtLastPublish())
                    assertTrue(label, h.emitted.none { it in knownCodes })
                }
            }
        }
        for (prompt in PUBLIC_OPEN_ORIGIN_PROMPTS) {
            val (h, controller) = controllerFor(prompt)
            h.authenticator.throwOnAuthenticate = IllegalStateException("boom")
            collecting(controller) { ends ->
                val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
                assertEquals("$prompt", listOf(end(id, prompt, OpenOriginEndKind.ERROR)), ends.published)
            }
        }
    }

    @Test
    fun `a launch retry budget that runs out publishes ERROR at once, without the watchdog`() {
        for (prompt in PUBLIC_OPEN_ORIGIN_PROMPTS) {
            val (h, controller) = controllerFor(prompt)
            h.authenticator.defaultResult = AuthLaunch.AWAITING_PRIOR_TERMINAL
            val step = AppLockController.AWAITING_PRIOR_TERMINAL_STEP_MILLIS
            val steps = (AppLockController.AWAITING_PRIOR_TERMINAL_BOUND_MILLIS / step).toInt()
            collecting(controller) { ends ->
                val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
                repeat(steps - 1) { h.advance(step) }
                assertTrue("$prompt", ends.published.isEmpty()) // still waiting

                h.advance(step)

                assertEquals("$prompt", listOf(end(id, prompt, OpenOriginEndKind.ERROR)), ends.published)
                h.advance(AppLockController.PROMPT_WATCHDOG_MILLIS)
                assertEquals("$prompt", 1, ends.published.size)
            }
        }
    }

    @Test
    fun `a foreign cancellation of the launch publishes ERROR, and one that races a LAUNCHED answer publishes HOST_LOST`() {
        for (prompt in PUBLIC_OPEN_ORIGIN_PROMPTS) {
            val (h, controller) = controllerFor(prompt)
            h.authenticator.throwOnAuthenticate = CancellationException("cancelled")
            collecting(controller) { ends ->
                val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
                assertEquals("$prompt", listOf(end(id, prompt, OpenOriginEndKind.ERROR)), ends.published)
            }
        }
        for (prompt in PUBLIC_OPEN_ORIGIN_PROMPTS) {
            val (h, controller) = controllerFor(prompt)
            h.authenticator.onAuthenticate = { h.cancelControllerJobs() } // it still answers LAUNCHED: a prompt may show
            collecting(controller) { ends ->
                val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
                assertEquals("$prompt", listOf(end(id, prompt, OpenOriginEndKind.HOST_LOST)), ends.published)
                assertEquals("$prompt", originOf(prompt), controller.state.value)
            }
        }
    }

    @Test
    fun `T35 host loss publishes HOST_LOST once, the adapter echo and a late success publish nothing more`() {
        for (prompt in PUBLIC_OPEN_ORIGIN_PROMPTS) {
            val (h, controller) = controllerFor(prompt)
            collecting(controller) { ends ->
                val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
                val call = h.authenticator.calls.last()

                controller.onPromptHostLost(id)

                assertEquals("$prompt", listOf(end(id, prompt, OpenOriginEndKind.HOST_LOST)), ends.published)
                assertEquals("$prompt", originOf(prompt), ends.stateAtLastPublish())
                h.settle() // the adapter is asked to cancel; its Failed(SYSTEM_CANCELED) echo finds the attempt closed
                h.authenticator.deliver(DeviceAuthOutcome.Succeeded, call)
                h.authenticator.deliverWithId(id, DeviceAuthOutcome.Succeeded, call)
                assertEquals("$prompt", listOf(end(id, prompt, OpenOriginEndKind.HOST_LOST)), ends.published)
                assertEquals("$prompt", 1, ends.published.size)
            }
        }
    }

    @Test
    fun `the watchdog publishes HOST_LOST once at the bound, and a late success publishes nothing`() {
        for (prompt in PUBLIC_OPEN_ORIGIN_PROMPTS) {
            val (h, controller) = controllerFor(prompt)
            collecting(controller) { ends ->
                val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())
                val call = h.authenticator.calls.last()

                h.advance(AppLockController.PROMPT_WATCHDOG_MILLIS - 1)
                assertTrue("$prompt", ends.published.isEmpty())
                h.advance(1)

                assertEquals("$prompt", listOf(end(id, prompt, OpenOriginEndKind.HOST_LOST)), ends.published)
                h.authenticator.deliver(DeviceAuthOutcome.Succeeded, call)
                assertEquals("$prompt", 1, ends.published.size)
            }
        }
    }

    private class Closer(
        val name: String,
        val kind: OpenOriginEndKind,
        val close: (AppLockControllerHarness, AppLockController, Long) -> Unit
    )

    @Test
    fun `T34 a close that locks the app publishes its kind after the lock, and one that does not lock leaves the origin`() {
        for ((away, expected) in listOf(
            59_999L to AppLockState.Unlocked,
            60_000L to AppLockState.Locked(LockCause.TIMEOUT)
        )) {
            val closers = listOf(
                Closer("cancel", OpenOriginEndKind.CANCELLED) { h, _, _ ->
                    h.authenticator.deliver(DeviceAuthOutcome.Cancelled)
                },
                Closer("lockout", OpenOriginEndKind.LOCKED_OUT) { h, _, _ ->
                    h.authenticator.deliver(DeviceAuthOutcome.LockedOut(permanent = false))
                },
                Closer("error", OpenOriginEndKind.ERROR) { h, _, _ ->
                    h.authenticator.deliver(DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED))
                },
                Closer("T35 host loss", OpenOriginEndKind.HOST_LOST) { _, controller, id ->
                    controller.onPromptHostLost(id)
                }
            )
            for (closer in closers) {
                val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
                collecting(controller) { ends ->
                    val id = h.startOpenOrigin(controller, OpenOriginPrompt.CHANGE, RecordingSuccessHandler())
                    controller.onAppBackgrounded()
                    h.clock.advance(away)
                    controller.onAppForegrounded()

                    closer.close(h, controller, id)

                    val label = "${closer.name} at $away ms"
                    assertEquals(label, listOf(end(id, OpenOriginPrompt.CHANGE, closer.kind)), ends.published)
                    assertEquals(label, expected, controller.state.value)
                    // The end arrives after the state it belongs to, never before the T34 lock is published.
                    assertEquals(label, expected, ends.stateAtLastPublish())
                }
            }
        }
    }

    @Test
    fun `an open-origin attempt that closes out of step is dropped with ERROR, never with an applied kind`() {
        for (prompt in PUBLIC_OPEN_ORIGIN_PROMPTS) {
            val (h, controller) = controllerFor(prompt)
            val handler = RecordingSuccessHandler()
            collecting(controller) { ends ->
                val id = h.startOpenOrigin(controller, prompt, handler)
                // No public sequence breaks the state/attempt pairing; force the mismatch the guard defends against.
                val currentField = AppLockController::class.java.getDeclaredField("current")
                currentField.isAccessible = true
                currentField.set(controller, AppLockState.Authenticating(id + 1, AuthPurpose.UNLOCK))

                h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

                assertEquals("$prompt", AppLockState.Locked(LockCause.COLD_START), controller.state.value)
                assertEquals("$prompt", listOf(end(id, prompt, OpenOriginEndKind.ERROR)), ends.published)
                assertTrue("$prompt", handler.delivered.isEmpty())
                // The attempt is gone: a move for its id applies nothing and publishes nothing.
                controller.onOpenOriginSettingApplied(id, true)
                assertEquals("$prompt", 1, ends.published.size)
            }
        }
    }

    // --- Stale ids -------------------------------------------------------------------------------------------------

    @Test
    fun `T12 outcomes and host losses for a wrong, closed or older attempt publish nothing`() {
        for (prompt in PUBLIC_OPEN_ORIGIN_PROMPTS) {
            val (h, controller) = controllerFor(prompt)
            val handler = RecordingSuccessHandler()
            collecting(controller) { ends ->
                val id = h.startOpenOrigin(controller, prompt, handler)
                val call1 = h.authenticator.calls.last()

                // While open: an outcome or a host loss for another id changes nothing.
                for (bad in listOf(0L, -1L, id - 1, id + 1, Long.MAX_VALUE)) {
                    controller.onPromptHostLost(bad)
                    h.authenticator.deliverWithId(bad, DeviceAuthOutcome.Cancelled, call1)
                    h.authenticator.deliverWithId(bad, DeviceAuthOutcome.Succeeded, call1)
                    controller.onOpenOriginSettingApplied(bad, true)
                }
                h.settle()
                assertEquals("$prompt", listOf<OpenOriginEnd?>(null), ends.seen.toList())
                assertEquals("$prompt", AppLockState.Authenticating(id, prompt.purpose), controller.state.value)

                h.authenticator.deliver(DeviceAuthOutcome.Cancelled, call1)
                assertEquals("$prompt", listOf(end(id, prompt, OpenOriginEndKind.CANCELLED)), ends.published)

                // After the close: every late callback and a late host loss for the closed attempt is stale.
                listOf(
                    DeviceAuthOutcome.Succeeded,
                    DeviceAuthOutcome.Cancelled,
                    DeviceAuthOutcome.LockedOut(permanent = false),
                    DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER),
                    DeviceAuthOutcome.NotAvailable
                ).forEach { h.authenticator.deliver(it, call1) }
                controller.onPromptHostLost(id)
                h.settle()
                assertEquals("$prompt", 1, ends.published.size)
                assertTrue("$prompt", handler.delivered.isEmpty())

                // A newer attempt is open: the older attempt's callback still publishes nothing.
                val id2 = h.startOpenOrigin(controller, prompt, handler)
                listOf(
                    DeviceAuthOutcome.Succeeded,
                    DeviceAuthOutcome.Cancelled,
                    DeviceAuthOutcome.LockedOut(permanent = true)
                ).forEach { h.authenticator.deliver(it, call1) }
                assertEquals("$prompt", 1, ends.published.size)
                assertEquals("$prompt", AppLockState.Authenticating(id2, prompt.purpose), controller.state.value)

                h.authenticator.deliver(DeviceAuthOutcome.LockedOut(permanent = false))
                assertEquals(
                    "$prompt",
                    listOf(end(id, prompt, OpenOriginEndKind.CANCELLED), end(id2, prompt, OpenOriginEndKind.LOCKED_OUT)),
                    ends.published
                )
            }
        }
    }

    @Test
    fun `T32 backgrounds, foregrounds and queries during the prompt publish nothing and BACKGROUNDED has no producer`() {
        for (prompt in PUBLIC_OPEN_ORIGIN_PROMPTS) {
            val (h, controller) = controllerFor(prompt)
            collecting(controller) { ends ->
                val id = h.startOpenOrigin(controller, prompt, RecordingSuccessHandler())

                controller.onAppBackgrounded()
                h.clock.advance(10_000L)
                controller.onAppForegrounded()
                controller.onLockQuery()
                h.settle()

                assertEquals("$prompt", AppLockState.Authenticating(id, prompt.purpose), controller.state.value)
                assertEquals("$prompt", listOf<OpenOriginEnd?>(null), ends.seen.toList())
            }
        }
    }

    // --- Success: APPLIED / NOT_COMMITTED at the apply, after the state move (T17, T18) ------------------------------

    @Test
    fun `T33 a success publishes nothing at the close, and neither does the apply completion without a move`() {
        for (prompt in PUBLIC_OPEN_ORIGIN_PROMPTS) {
            val (h, controller) = controllerFor(prompt)
            val handler = RecordingSuccessHandler()
            collecting(controller) { ends ->
                val id = h.startOpenOrigin(controller, prompt, handler)

                h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

                assertEquals("$prompt", listOf(id to prompt.purpose), handler.delivered)
                assertEquals("$prompt", originOf(prompt), controller.state.value)
                assertEquals("$prompt", listOf<OpenOriginEnd?>(null), ends.seen.toList())

                // The completion closes the gate and drops the token: the move is then a no-op and publishes nothing.
                controller.onOpenOriginApplyCompleted(id)
                controller.onOpenOriginSettingApplied(id, true)
                controller.onOpenOriginSettingApplied(id, false)
                assertEquals("$prompt", listOf<OpenOriginEnd?>(null), ends.seen.toList())
                assertEquals("$prompt", originOf(prompt), controller.state.value)
            }
        }
    }

    @Test
    fun `T17 and T18 APPLIED is published only after the state move, and NOT_COMMITTED leaves the origin`() {
        for (prompt in listOf(OpenOriginPrompt.ENABLE, OpenOriginPrompt.DISABLE)) {
            for (committed in listOf(true, false)) {
                val label = "$prompt committed=$committed"
                val (h, controller) = controllerFor(prompt)
                collecting(controller) { ends ->
                    val id = succeed(h, controller, prompt)
                    assertTrue(label, ends.published.isEmpty())

                    controller.onOpenOriginSettingApplied(id, committed)

                    val kind = if (committed) OpenOriginEndKind.APPLIED else OpenOriginEndKind.NOT_COMMITTED
                    val moved = if (prompt == OpenOriginPrompt.ENABLE) AppLockState.Unlocked else AppLockState.Disabled
                    val expectedState = if (committed) moved else originOf(prompt)
                    assertEquals(label, listOf(end(id, prompt, kind)), ends.published)
                    assertEquals(label, expectedState, controller.state.value)
                    // The collector saw the moved state (T17: UNLOCKED, T18: DISABLED) when the end arrived, not before.
                    assertEquals(label, expectedState, ends.stateAtLastPublish())
                    assertTrue(label, ends.monitorHeld.none { it })
                    assertEquals(label, 0, h.repository.writeAttempts) // the controller itself writes nothing

                    // The token is consumed: a repeat, another id and a fresh id publish nothing.
                    controller.onOpenOriginSettingApplied(id, true)
                    controller.onOpenOriginSettingApplied(id, false)
                    controller.onOpenOriginSettingApplied(id + 1, true)
                    controller.onOpenOriginSettingApplied(0L, true)
                    assertEquals(label, 1, ends.published.size)
                }
            }
        }
    }

    @Test
    fun `S22-a_0 privacy review F4 - an applied DISABLE whose app timed out first still publishes APPLIED while the state stays LOCKED`() {
        val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
        collecting(controller) { ends ->
            // DISABLE confirmed: the close hands over (state is the origin, UNLOCKED), the durable write is still to come.
            val id = succeed(h, controller, OpenOriginPrompt.DISABLE)
            assertTrue(ends.published.isEmpty())

            // The app is backgrounded past the timeout and comes back: the lock engages before the write reports.
            controller.onAppBackgrounded()
            h.clock.advance(60_000L)
            controller.onAppForegrounded()
            assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
            assertTrue(ends.published.isEmpty()) // the lock itself is not an end

            controller.onOpenOriginSettingApplied(id, true)

            // The end is published even though the apply's state move did not happen: the state was no longer at the origin.
            assertEquals(listOf(end(id, OpenOriginPrompt.DISABLE, OpenOriginEndKind.APPLIED)), ends.published)
            assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
            assertEquals(AppLockState.Locked(LockCause.TIMEOUT), ends.stateAtLastPublish())
            assertTrue(ends.monitorHeld.none { it })

            // The token is consumed: a repeat publishes nothing.
            controller.onOpenOriginSettingApplied(id, true)
            controller.onOpenOriginSettingApplied(id, false)
            assertEquals(1, ends.published.size)
            assertEquals(AppLockState.Locked(LockCause.TIMEOUT), controller.state.value)
        }
    }

    @Test
    fun `a CHANGE of a longer timeout publishes APPLIED or NOT_COMMITTED at the apply, and the state stays UNLOCKED`() {
        for (committed in listOf(true, false)) {
            val (h, controller) = unlockedControllerFor(AutoLockTimeout.M1)
            collecting(controller) { ends ->
                val started = controller.startTimeoutChangeAttempt(AutoLockTimeout.M5, RecordingSuccessHandler())
                h.settle()
                val id = (started as OpenOriginStartResult.Started).attemptId
                h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
                assertTrue("$committed", ends.published.isEmpty())

                controller.onOpenOriginSettingApplied(id, committed)

                val kind = if (committed) OpenOriginEndKind.APPLIED else OpenOriginEndKind.NOT_COMMITTED
                assertEquals("$committed", listOf(end(id, OpenOriginPrompt.CHANGE, kind)), ends.published)
                assertEquals("$committed", AppLockState.Unlocked, ends.stateAtLastPublish())
            }
        }
    }

    @Test
    fun `a move with no token publishes nothing`() {
        val (h, controller) = disabledControllerFor()
        collecting(controller) { ends ->
            controller.onOpenOriginSettingApplied(1L, true)
            controller.onOpenOriginSettingApplied(1L, false)
            controller.onInertDisableApplied(1L, true)
            controller.onInertDisableApplied(1L, false)
            assertEquals(listOf<OpenOriginEnd?>(null), ends.seen.toList())
        }
        assertTrue(h.emitted.none { it in knownCodes })
    }

    // --- End to end through the settings operations --------------------------------------------------------------

    @Test
    fun `end to end an ENABLE request publishes APPLIED after UNLOCKED, and a failed write publishes NOT_COMMITTED`() {
        for (fail in listOf(false, true)) {
            val (h, controller) = disabledControllerFor()
            val rig = SettingsOperationsRig(h, controller)
            if (fail) {
                h.repository.lockWriteResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED) }
            }
            collecting(controller) { ends ->
                assertEquals(SettingsRequestResult.STARTED, rig.ops.requestEnable())
                h.settle()
                val id = controller.inFlightAttemptId!!

                h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

                val expectedKind = if (fail) OpenOriginEndKind.NOT_COMMITTED else OpenOriginEndKind.APPLIED
                val expectedState = if (fail) AppLockState.Disabled else AppLockState.Unlocked
                assertEquals("fail=$fail", listOf(end(id, OpenOriginPrompt.ENABLE, expectedKind)), ends.published)
                assertEquals("fail=$fail", expectedState, ends.stateAtLastPublish())
                assertEquals("fail=$fail", expectedState, controller.state.value)
                assertTrue(rig.uncaught.isEmpty())
            }
        }
    }

    @Test
    fun `end to end a DISABLE request publishes APPLIED after DISABLED, and a failed write publishes NOT_COMMITTED`() {
        for (fail in listOf(false, true)) {
            val (h, controller) = unlockedControllerFor()
            val rig = SettingsOperationsRig(h, controller)
            if (fail) {
                h.repository.lockWriteResult = { AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED) }
            }
            collecting(controller) { ends ->
                assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
                h.settle()
                val id = controller.inFlightAttemptId!!

                h.authenticator.deliver(DeviceAuthOutcome.Succeeded)

                val expectedKind = if (fail) OpenOriginEndKind.NOT_COMMITTED else OpenOriginEndKind.APPLIED
                val expectedState = if (fail) AppLockState.Unlocked else AppLockState.Disabled
                assertEquals("fail=$fail", listOf(end(id, OpenOriginPrompt.DISABLE, expectedKind)), ends.published)
                assertEquals("fail=$fail", expectedState, ends.stateAtLastPublish())
                assertTrue(rig.uncaught.isEmpty())
            }
        }
    }

    // --- T19: the INERT disable has no attempt ---------------------------------------------------------------------

    @Test
    fun `T19 an INERT disable publishes APPLIED after DISABLED under the negated token id, for the exact id only`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()
        assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        collecting(controller) { ends ->
            val first = (controller.beginInertDisable() as InertDisableBegin.Begun).id
            val second = (controller.beginInertDisable() as InertDisableBegin.Begun).id // replaces the older token
            assertTrue(second > first)

            for (wrong in listOf(first, second + 1, 0L, -1L, -second, Long.MAX_VALUE)) {
                controller.onInertDisableApplied(wrong, committed = true)
                controller.onInertDisableApplied(wrong, committed = false)
            }
            assertEquals(listOf<OpenOriginEnd?>(null), ends.seen.toList())
            assertTrue(controller.state.value is AppLockState.Inert)

            controller.onInertDisableApplied(second, committed = true)

            assertEquals(listOf(end(-second, OpenOriginPrompt.DISABLE, OpenOriginEndKind.APPLIED)), ends.published)
            assertEquals(AppLockState.Disabled, ends.stateAtLastPublish())
            assertTrue("a T19 end can never equal an attempt id", ends.published.single().attemptId < 0L)
            assertTrue(ends.monitorHeld.none { it })

            controller.onInertDisableApplied(second, committed = true) // consumed
            controller.onInertDisableApplied(second, committed = false)
            assertEquals(1, ends.published.size)
        }
    }

    @Test
    fun `T19 an INERT disable whose write did not commit publishes NOT_COMMITTED and stays INERT`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()
        collecting(controller) { ends ->
            val id = (controller.beginInertDisable() as InertDisableBegin.Begun).id

            controller.onInertDisableApplied(id, committed = false)

            assertEquals(listOf(end(-id, OpenOriginPrompt.DISABLE, OpenOriginEndKind.NOT_COMMITTED)), ends.published)
            assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), ends.stateAtLastPublish())
            assertEquals(AppLockState.Inert(InertReason.NO_CREDENTIAL), controller.state.value)
        }
    }

    @Test
    fun `end to end a request to disable while INERT publishes APPLIED after DISABLED`() {
        val h = AppLockControllerHarness(AppLockTestSettings.enabled(), DeviceAuthCapability.NO_CREDENTIAL)
        val controller = h.resolvedController()
        val rig = SettingsOperationsRig(h, controller)
        collecting(controller) { ends ->
            assertEquals(SettingsRequestResult.STARTED, rig.ops.requestDisable())
            h.settle()

            val published = ends.published.single()
            assertEquals(OpenOriginPrompt.DISABLE, published.prompt)
            assertEquals(OpenOriginEndKind.APPLIED, published.kind)
            assertTrue(published.attemptId < 0L)
            assertEquals(AppLockState.Disabled, ends.stateAtLastPublish())
            assertTrue(h.authenticator.calls.isEmpty()) // no prompt, no attempt
            assertTrue(rig.uncaught.isEmpty())
        }
    }
}
