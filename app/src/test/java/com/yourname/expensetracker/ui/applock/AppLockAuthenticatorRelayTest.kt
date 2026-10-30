package com.yourname.expensetracker.ui.applock

import com.yourname.expensetracker.domain.privacy.applock.AuthLaunch
import com.yourname.expensetracker.domain.privacy.applock.AuthPromptCopy
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCallback
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthErrorBucket
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthOutcome
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthenticator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * S20-a: the process-level relay for the `DeviceAuthenticator` port (handoff S16 binding rules (a) to (e) and
 * the rotation adoption rule). Plain JVM: the relay holds no Android state, the main-thread check is the
 * injected seam, and the adapters are fakes.
 *
 * The expected values are written out per scenario (which callback gets which outcome, how many times); none is
 * derived from the relay's own bookkeeping.
 */
class AppLockAuthenticatorRelayTest {

    private val cancelled = DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED)

    private class RecordingCallback : DeviceAuthCallback {
        val received = mutableListOf<Pair<Long, DeviceAuthOutcome>>()
        var onEach: ((Long, DeviceAuthOutcome) -> Unit)? = null
        override fun onOutcome(attemptId: Long, outcome: DeviceAuthOutcome) {
            received += attemptId to outcome
            onEach?.invoke(attemptId, outcome)
        }
    }

    /** A fake prompt adapter. By default it behaves like the real one: cancel delivers SYSTEM_CANCELED once. */
    private class FakeAdapter {
        class Launch(val id: Long, val copy: AuthPromptCopy?, val callback: DeviceAuthCallback)

        val launches = mutableListOf<Launch>()
        val adoptions = mutableListOf<Launch>()
        val cancels = mutableListOf<Long>()
        var launchResult: AuthLaunch = AuthLaunch.LAUNCHED
        var launchFailure: Throwable? = null
        var duringLaunch: ((Launch) -> Unit)? = null
        var adoptResult: Boolean = true
        var duringAdopt: ((Launch) -> Unit)? = null
        var cancelFailure: Throwable? = null
        var cancelBehavior: (Launch) -> Unit = { it.callback.onOutcome(it.id, DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED)) }

        val port: DeviceAuthenticator = object : DeviceAuthenticator {
            override fun authenticate(attemptId: Long, copy: AuthPromptCopy, callback: DeviceAuthCallback): AuthLaunch {
                val launch = Launch(attemptId, copy, callback)
                launches += launch
                duringLaunch?.invoke(launch)
                launchFailure?.let { throw it }
                return launchResult
            }

            override fun cancel(attemptId: Long) {
                cancels += attemptId
                val launch = (launches + adoptions).lastOrNull { it.id == attemptId }
                if (launch != null) cancelBehavior(launch)
                cancelFailure?.let { throw it }
            }
        }

        val adopter = AttemptAdopter { id, copy, callback ->
            val adoption = Launch(id, copy, callback)
            adoptions += adoption
            duringAdopt?.invoke(adoption)
            adoptResult
        }

        /** The callback the relay gave this adapter for the latest launch or adoption. */
        val latestCallback: DeviceAuthCallback
            get() = (launches + adoptions).last().callback
    }

    private var onMain = true

    /** The "post to main" seam: orphan checks are queued here and run (or not) by the test, never by a looper. */
    private val posted = mutableListOf<Runnable>()
    private val relay = AppLockAuthenticatorRelay({ onMain }, { posted += it })

    /**
     * The relay holds registrations weakly (rule (a)); the prompt host owns them strongly in production. The tests
     * keep every registration reachable here so a garbage collection cannot change a scenario.
     */
    private val strongRegistrations = mutableListOf<RelayRegistration>()

    private fun registerOn(target: AppLockAuthenticatorRelay, adapter: FakeAdapter): RelayRegistration =
        target.register(adapter.port, adapter.adopter).also { strongRegistrations += it }

    private fun register(adapter: FakeAdapter): RelayRegistration = registerOn(relay, adapter)

    private fun launch(id: Long, callback: DeviceAuthCallback, copy: AuthPromptCopy = AuthPromptCopy.UNLOCK): AuthLaunch =
        relay.authenticate(id, copy, callback)

    // ---- main-thread enforcement -------------------------------------------------------------------------

    @Test
    fun mainThread_authenticateOffMain_throws_andReachesNeitherTheAdapterNorTheRecord() {
        val adapter = FakeAdapter()
        register(adapter)
        onMain = false

        assertThrows(IllegalStateException::class.java) { launch(1L, RecordingCallback()) }

        assertTrue(adapter.launches.isEmpty())
        assertNull(relay.openAttemptId)
    }

    @Test
    fun mainThread_cancelRegisterAndUnregisterOffMain_throw() {
        val adapter = FakeAdapter()
        val registration = register(adapter)
        val callback = RecordingCallback()
        launch(1L, callback)
        onMain = false

        assertThrows(IllegalStateException::class.java) { relay.cancel(1L) }
        assertThrows(IllegalStateException::class.java) { register(FakeAdapter()) }
        assertThrows(IllegalStateException::class.java) { relay.unregister(registration, false) }

        assertTrue("nothing reached the adapter", adapter.cancels.isEmpty())
        assertTrue("the attempt is untouched", callback.received.isEmpty())
        assertEquals(1L, relay.openAttemptId)
    }

    @Test
    fun mainThread_controlOnMain_allEntryPointsWork() {
        val adapter = FakeAdapter()
        val registration = register(adapter)

        assertEquals(AuthLaunch.LAUNCHED, launch(1L, RecordingCallback()))
        relay.cancel(1L)
        assertNull(relay.unregister(registration, false))
    }

    @Test
    fun mainThread_theOutcomeCallbackIsNotMainThreadChecked_andStillForwards() {
        val adapter = FakeAdapter()
        register(adapter)
        val callback = RecordingCallback()
        launch(1L, callback)
        onMain = false

        adapter.latestCallback.onOutcome(1L, DeviceAuthOutcome.Succeeded)

        assertEquals(listOf(1L to DeviceAuthOutcome.Succeeded), callback.received)
    }

    // ---- rule (b): HOST_UNAVAILABLE ----------------------------------------------------------------------

    @Test
    fun ruleB_noRegistration_returnsHostUnavailable_andNoCallbackFollows() {
        val callback = RecordingCallback()

        assertEquals(AuthLaunch.HOST_UNAVAILABLE, launch(1L, callback))

        assertFalse(relay.hasRegistration)
        assertNull(relay.openAttemptId)
        assertTrue(callback.received.isEmpty())
    }

    @Test
    fun ruleB_afterTheRegistrationIsCleared_returnsHostUnavailable_andTheAdapterIsNotCalled() {
        val adapter = FakeAdapter()
        val registration = register(adapter)
        assertTrue(relay.hasRegistration)
        relay.unregister(registration, false)
        val callback = RecordingCallback()

        assertEquals(AuthLaunch.HOST_UNAVAILABLE, launch(2L, callback))

        assertFalse(relay.hasRegistration)
        assertTrue(adapter.launches.isEmpty())
        assertTrue(callback.received.isEmpty())
        assertNull(relay.openAttemptId)
    }

    @Test
    fun ruleB_control_withARegistration_theRequestReachesTheAdapter() {
        val adapter = FakeAdapter()
        register(adapter)

        assertEquals(AuthLaunch.LAUNCHED, launch(1L, RecordingCallback()))

        assertEquals(listOf(1L), adapter.launches.map { it.id })
    }

    // ---- INV-34: one attempt at a time -------------------------------------------------------------------

    @Test
    fun inv34_secondAuthenticateWhileOpen_returnsAlreadyInFlight_andTheFirstStillCompletes() {
        val adapter = FakeAdapter()
        register(adapter)
        val first = RecordingCallback()
        val second = RecordingCallback()
        assertEquals(AuthLaunch.LAUNCHED, launch(1L, first))

        assertEquals(AuthLaunch.ALREADY_IN_FLIGHT, launch(2L, second))
        assertEquals(AuthLaunch.ALREADY_IN_FLIGHT, launch(1L, second))

        assertEquals("the refused requests never reach the adapter", 1, adapter.launches.size)
        assertEquals(1L, relay.openAttemptId)
        adapter.latestCallback.onOutcome(1L, DeviceAuthOutcome.Succeeded)
        assertEquals(listOf(1L to DeviceAuthOutcome.Succeeded), first.received)
        assertTrue(second.received.isEmpty())
        assertNull(relay.openAttemptId)
    }

    @Test
    fun inv34_afterTheOutcome_aNewAttemptIsAccepted() {
        val adapter = FakeAdapter()
        register(adapter)
        launch(1L, RecordingCallback())
        adapter.latestCallback.onOutcome(1L, DeviceAuthOutcome.Cancelled)

        assertEquals(AuthLaunch.LAUNCHED, launch(2L, RecordingCallback()))
        assertEquals(2L, relay.openAttemptId)
    }

    // ---- the record is set before the adapter call -------------------------------------------------------

    @Test
    fun record_isOpenBeforeTheAdapterIsCalled_soAReentrantAuthenticateIsRefused() {
        val adapter = FakeAdapter()
        register(adapter)
        var idSeenByAdapter: Long? = null
        var nested: AuthLaunch? = null
        adapter.duringLaunch = {
            idSeenByAdapter = relay.openAttemptId
            nested = launch(9L, RecordingCallback())
        }

        assertEquals(AuthLaunch.LAUNCHED, launch(1L, RecordingCallback()))

        assertEquals(1L, idSeenByAdapter)
        assertEquals(AuthLaunch.ALREADY_IN_FLIGHT, nested)
        assertEquals("the nested request never reached the adapter", 1, adapter.launches.size)
    }

    @Test
    fun record_aReentrantOutcomeInsideAuthenticate_isDeliveredExactlyOnce() {
        val adapter = FakeAdapter()
        register(adapter)
        val callback = RecordingCallback()
        adapter.duringLaunch = { it.callback.onOutcome(it.id, DeviceAuthOutcome.Succeeded) }

        assertEquals(AuthLaunch.LAUNCHED, launch(1L, callback))

        assertEquals(listOf(1L to DeviceAuthOutcome.Succeeded), callback.received)
        assertNull("the re-entrant outcome closed the record", relay.openAttemptId)
        // A duplicate of the same outcome afterwards is dropped.
        adapter.launches.single().callback.onOutcome(1L, DeviceAuthOutcome.Succeeded)
        assertEquals(1, callback.received.size)
    }

    @Test
    fun record_aReentrantOutcomeThenAThrow_stillDeliversOnce_andLeavesNothingOpen() {
        val adapter = FakeAdapter()
        register(adapter)
        val callback = RecordingCallback()
        adapter.duringLaunch = { it.callback.onOutcome(it.id, DeviceAuthOutcome.Cancelled) }
        adapter.launchFailure = IllegalStateException("synthetic")

        assertThrows(IllegalStateException::class.java) { launch(1L, callback) }

        assertEquals(listOf(1L to DeviceAuthOutcome.Cancelled), callback.received)
        assertNull(relay.openAttemptId)
    }

    // ---- the record is removed on a non-LAUNCHED result or a throw ---------------------------------------

    @Test
    fun record_removedOnEveryNonLaunchedResult_resultPassesThrough_andTheNextRequestIsAccepted() {
        for (result in AuthLaunch.entries.filter { it != AuthLaunch.LAUNCHED }) {
            val localRelay = AppLockAuthenticatorRelay { true }
            val adapter = FakeAdapter()
            registerOn(localRelay, adapter)
            val callback = RecordingCallback()
            adapter.launchResult = result

            assertEquals("result $result", result, localRelay.authenticate(1L, AuthPromptCopy.UNLOCK, callback))

            assertNull("record left open for $result", localRelay.openAttemptId)
            assertTrue("callback owed for $result", callback.received.isEmpty())
            adapter.launchResult = AuthLaunch.LAUNCHED
            assertEquals("retry after $result", AuthLaunch.LAUNCHED, localRelay.authenticate(2L, AuthPromptCopy.UNLOCK, callback))
            assertEquals(2L, localRelay.openAttemptId)
        }
    }

    @Test
    fun record_removedWhenTheAdapterThrows_theSameExceptionPropagates_andNoCallbackFollows() {
        val adapter = FakeAdapter()
        register(adapter)
        val callback = RecordingCallback()
        val failure = IllegalStateException("synthetic")
        adapter.launchFailure = failure

        val thrown = assertThrows(IllegalStateException::class.java) { launch(1L, callback) }

        assertSame(failure, thrown)
        assertNull(relay.openAttemptId)
        assertTrue(callback.received.isEmpty())
        adapter.launchFailure = null
        assertEquals(AuthLaunch.LAUNCHED, launch(2L, callback))
    }

    @Test
    fun record_removedWhenTheAdapterThrowsCancellation_andItPropagates() {
        val adapter = FakeAdapter()
        register(adapter)
        adapter.launchFailure = CancellationException("synthetic")

        assertThrows(CancellationException::class.java) { launch(1L, RecordingCallback()) }

        assertNull(relay.openAttemptId)
        adapter.launchFailure = null
        assertEquals(AuthLaunch.LAUNCHED, launch(2L, RecordingCallback()))
    }

    @Test
    fun record_control_launchedKeepsTheRecordOpenUntilTheOutcome() {
        val adapter = FakeAdapter()
        register(adapter)

        assertEquals(AuthLaunch.LAUNCHED, launch(1L, RecordingCallback()))

        assertEquals(1L, relay.openAttemptId)
        assertFalse(relay.isOpenAttemptOrphaned)
    }

    // ---- copy pass-through -------------------------------------------------------------------------------

    @Test
    fun copy_everyValueReachesTheAdapterUnchanged_withTheSameId() {
        for (copy in AuthPromptCopy.entries) {
            val localRelay = AppLockAuthenticatorRelay { true }
            val adapter = FakeAdapter()
            registerOn(localRelay, adapter)

            localRelay.authenticate(42L, copy, RecordingCallback())

            val launch = adapter.launches.single()
            assertEquals(42L, launch.id)
            assertSame("copy $copy", copy, launch.copy)
        }
    }

    // ---- at-most-once forwarding and stale-outcome drops -------------------------------------------------

    @Test
    fun forwarding_everyOutcomeKind_isForwardedUnchangedAndOnce() {
        val outcomes = listOf(
            DeviceAuthOutcome.Succeeded,
            DeviceAuthOutcome.Cancelled,
            DeviceAuthOutcome.LockedOut(permanent = false),
            DeviceAuthOutcome.LockedOut(permanent = true),
            DeviceAuthOutcome.NotAvailable,
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.HW_UNAVAILABLE),
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.TIMEOUT),
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED),
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER)
        )
        for (outcome in outcomes) {
            val localRelay = AppLockAuthenticatorRelay { true }
            val adapter = FakeAdapter()
            registerOn(localRelay, adapter)
            val callback = RecordingCallback()
            localRelay.authenticate(3L, AuthPromptCopy.UNLOCK, callback)

            adapter.latestCallback.onOutcome(3L, outcome)
            adapter.latestCallback.onOutcome(3L, DeviceAuthOutcome.Succeeded)

            assertEquals("outcome $outcome", listOf(3L to outcome), callback.received)
        }
    }

    @Test
    fun forwarding_aSecondOutcomeForTheSameAttempt_isDropped() {
        val adapter = FakeAdapter()
        register(adapter)
        val callback = RecordingCallback()
        launch(1L, callback)

        adapter.latestCallback.onOutcome(1L, DeviceAuthOutcome.Cancelled)
        adapter.latestCallback.onOutcome(1L, DeviceAuthOutcome.Succeeded)

        assertEquals(listOf(1L to DeviceAuthOutcome.Cancelled), callback.received)
    }

    @Test
    fun forwarding_anOutcomeWithAWrongId_isDropped_andTheAttemptStaysOpenForTheRightOne() {
        val adapter = FakeAdapter()
        register(adapter)
        val callback = RecordingCallback()
        launch(5L, callback)

        adapter.latestCallback.onOutcome(4L, DeviceAuthOutcome.Succeeded)
        adapter.latestCallback.onOutcome(6L, DeviceAuthOutcome.Succeeded)

        assertTrue(callback.received.isEmpty())
        assertEquals(5L, relay.openAttemptId)
        adapter.latestCallback.onOutcome(5L, DeviceAuthOutcome.Succeeded)
        assertEquals(listOf(5L to DeviceAuthOutcome.Succeeded), callback.received)
    }

    @Test
    fun forwarding_aLateOutcomeOfAClosedAttempt_isDropped_evenWhenANewAttemptIsOpen() {
        val adapter = FakeAdapter()
        register(adapter)
        val firstCallback = RecordingCallback()
        val secondCallback = RecordingCallback()
        launch(1L, firstCallback)
        val oldRelayCallback = adapter.latestCallback
        oldRelayCallback.onOutcome(1L, DeviceAuthOutcome.Cancelled)
        launch(2L, secondCallback)

        // The old callback, with the old id and with the newer id: neither may reach any controller callback.
        oldRelayCallback.onOutcome(1L, DeviceAuthOutcome.Succeeded)
        oldRelayCallback.onOutcome(2L, DeviceAuthOutcome.Succeeded)

        assertEquals(listOf(1L to DeviceAuthOutcome.Cancelled), firstCallback.received)
        assertTrue("a late outcome must never complete the newer attempt", secondCallback.received.isEmpty())
        assertEquals(2L, relay.openAttemptId)
    }

    @Test
    fun forwarding_theRecordIsClosedBeforeTheControllerCallbackRuns_soANewAttemptFromItIsAccepted() {
        val adapter = FakeAdapter()
        register(adapter)
        var nested: AuthLaunch? = null
        val callback = RecordingCallback().apply { onEach = { _, _ -> nested = launch(2L, RecordingCallback()) } }
        launch(1L, callback)

        adapter.latestCallback.onOutcome(1L, DeviceAuthOutcome.Cancelled)

        assertEquals(AuthLaunch.LAUNCHED, nested)
        assertEquals(2L, relay.openAttemptId)
        assertEquals(1, callback.received.size)
    }

    // ---- rule (c): cancel routing ------------------------------------------------------------------------

    @Test
    fun ruleC_cancelGoesToTheAdapterThatLaunchedTheId_notTheNewestRegistration() {
        val older = FakeAdapter()
        val newer = FakeAdapter()
        register(older)
        val callback = RecordingCallback()
        launch(1L, callback)
        register(newer) // a newer host, created while the older one is still alive: no adoption

        relay.cancel(1L)

        assertEquals(listOf(1L), older.cancels)
        assertTrue(newer.cancels.isEmpty())
        assertEquals(listOf(1L to cancelled), callback.received)
        assertNull(relay.openAttemptId)
    }

    @Test
    fun ruleC_cancelOfAnUnknownId_isIgnored_andTheAttemptStaysOpen() {
        val adapter = FakeAdapter()
        register(adapter)
        val callback = RecordingCallback()
        launch(5L, callback)

        relay.cancel(4L)
        relay.cancel(6L)

        assertTrue(adapter.cancels.isEmpty())
        assertTrue(callback.received.isEmpty())
        assertEquals(5L, relay.openAttemptId)
    }

    @Test
    fun ruleC_cancelWithNothingOpen_isANoOp_withOrWithoutARegistration() {
        relay.cancel(1L)
        val adapter = FakeAdapter()
        register(adapter)

        relay.cancel(1L)

        assertTrue(adapter.cancels.isEmpty())
    }

    @Test
    fun ruleC_cancelOfTheOpenAttempt_deliversExactlyOneSystemCanceled_whenTheAdapterDeliversIt() {
        val adapter = FakeAdapter()
        register(adapter)
        val callback = RecordingCallback()
        launch(1L, callback)

        relay.cancel(1L)
        relay.cancel(1L)

        assertEquals(listOf(1L), adapter.cancels)
        assertEquals(listOf(1L to cancelled), callback.received)
    }

    // ---- rule (c): the synthetic Failed(SYSTEM_CANCELED) -------------------------------------------------

    @Test
    fun ruleC_holderGone_cancelDeliversOneSyntheticSystemCanceled_synchronously_andNeverCallsAnAdapter() {
        val old = FakeAdapter()
        val registration = register(old)
        val callback = RecordingCallback()
        launch(1L, callback)
        relay.unregister(registration, isChangingConfigurations = true)
        assertTrue(relay.isOpenAttemptOrphaned)

        relay.cancel(1L)

        assertEquals("delivered before cancel returned", listOf(1L to cancelled), callback.received)
        assertTrue(old.cancels.isEmpty())
        assertNull(relay.openAttemptId)
        relay.cancel(1L)
        assertEquals("exactly once", 1, callback.received.size)
    }

    @Test
    fun ruleC_adapterDidNotCloseTheRecord_cancelDeliversOneSynthetic() {
        val adapter = FakeAdapter()
        register(adapter)
        val callback = RecordingCallback()
        launch(1L, callback)
        adapter.cancelBehavior = { } // an adapter that swallows the cancel

        relay.cancel(1L)

        assertEquals(listOf(1L), adapter.cancels)
        assertEquals(listOf(1L to cancelled), callback.received)
        assertNull(relay.openAttemptId)
    }

    @Test
    fun ruleC_adapterThatDeliversAWrongIdOnCancel_stillLeavesExactlyOneSyntheticForTheRightId() {
        val adapter = FakeAdapter()
        register(adapter)
        val callback = RecordingCallback()
        launch(1L, callback)
        adapter.cancelBehavior = { it.callback.onOutcome(it.id + 1, cancelled) }

        relay.cancel(1L)

        assertEquals(listOf(1L to cancelled), callback.received)
    }

    @Test
    fun ruleC_adapterCancelThrows_theExceptionPropagates_andTheSyntheticIsStillDeliveredOnce() {
        val adapter = FakeAdapter()
        register(adapter)
        val callback = RecordingCallback()
        launch(1L, callback)
        adapter.cancelBehavior = { }
        adapter.cancelFailure = IllegalStateException("synthetic")

        assertThrows(IllegalStateException::class.java) { relay.cancel(1L) }

        assertEquals(listOf(1L to cancelled), callback.received)
        assertNull(relay.openAttemptId)
    }

    @Test
    fun ruleC_adapterThatClosesThenThrows_deliversExactlyOnce() {
        val adapter = FakeAdapter()
        register(adapter)
        val callback = RecordingCallback()
        launch(1L, callback)
        adapter.cancelFailure = IllegalStateException("synthetic")

        assertThrows(IllegalStateException::class.java) { relay.cancel(1L) }

        assertEquals(listOf(1L to cancelled), callback.received)
    }

    @Test
    fun ruleC_aNewAttemptFromTheSyntheticCallback_isAccepted() {
        val adapter = FakeAdapter()
        register(adapter)
        var nested: AuthLaunch? = null
        val callback = RecordingCallback()
        launch(1L, callback)
        adapter.cancelBehavior = { } // the relay's own outcome is the only one
        callback.onEach = { _, _ -> nested = launch(2L, RecordingCallback()) }

        relay.cancel(1L)

        assertEquals(listOf(1L to cancelled), callback.received)
        assertEquals(AuthLaunch.LAUNCHED, nested)
        assertEquals(2L, relay.openAttemptId)
    }

    // ---- rotation: register adopts an orphaned record ----------------------------------------------------

    @Test
    fun adoption_registerAdoptsAnOrphanedRecord_handingTheAdapterTheIdAndACallbackThatForwardsOnce() {
        val old = FakeAdapter()
        val oldRegistration = register(old)
        val callback = RecordingCallback()
        launch(7L, callback)
        assertNull(relay.unregister(oldRegistration, isChangingConfigurations = true))
        assertTrue(relay.isOpenAttemptOrphaned)
        assertEquals("a rotation keeps the attempt open", 7L, relay.openAttemptId)
        val fresh = FakeAdapter()

        register(fresh)

        assertEquals(listOf(7L), fresh.adoptions.map { it.id })
        assertFalse(relay.isOpenAttemptOrphaned)
        assertEquals(7L, relay.openAttemptId)
        assertTrue(callback.received.isEmpty())
        fresh.latestCallback.onOutcome(7L, DeviceAuthOutcome.Succeeded)
        fresh.latestCallback.onOutcome(7L, DeviceAuthOutcome.Succeeded)
        assertEquals(listOf(7L to DeviceAuthOutcome.Succeeded), callback.received)
        assertNull(relay.openAttemptId)
    }

    @Test
    fun inv40_adoptionAfterRotation_passesTheCopyTheAttemptWasRegisteredWith_pinFingerprint() {
        val old = FakeAdapter()
        val oldRegistration = register(old)
        launch(7L, RecordingCallback(), AuthPromptCopy.PIN_FINGERPRINT)
        assertEquals(AuthPromptCopy.PIN_FINGERPRINT, old.launches.single().copy)
        assertNull(relay.unregister(oldRegistration, isChangingConfigurations = true))
        val fresh = FakeAdapter()

        register(fresh)

        assertEquals(listOf(7L), fresh.adoptions.map { it.id })
        assertEquals(
            "the adopting adapter must learn the attempt is a PIN fingerprint attempt",
            AuthPromptCopy.PIN_FINGERPRINT,
            fresh.adoptions.single().copy
        )
    }

    @Test
    fun inv40_adoptionAfterRotation_controlKeepsTheUnlockCopy_notPinFingerprint() {
        val old = FakeAdapter()
        val oldRegistration = register(old)
        launch(8L, RecordingCallback(), AuthPromptCopy.UNLOCK)
        relay.unregister(oldRegistration, isChangingConfigurations = true)
        val fresh = FakeAdapter()

        register(fresh)

        assertEquals(AuthPromptCopy.UNLOCK, fresh.adoptions.single().copy)
    }

    @Test
    fun adoption_aLateOutcomeFromTheOldAdapterAfterAdoption_isDropped() {
        val old = FakeAdapter()
        val oldRegistration = register(old)
        val callback = RecordingCallback()
        launch(7L, callback)
        val oldRelayCallback = old.latestCallback
        relay.unregister(oldRegistration, isChangingConfigurations = true)
        val fresh = FakeAdapter()
        register(fresh)

        oldRelayCallback.onOutcome(7L, DeviceAuthOutcome.Succeeded)

        assertTrue("the old adapter no longer holds the attempt", callback.received.isEmpty())
        assertEquals(7L, relay.openAttemptId)
        fresh.latestCallback.onOutcome(7L, DeviceAuthOutcome.Cancelled)
        assertEquals(listOf(7L to DeviceAuthOutcome.Cancelled), callback.received)
    }

    @Test
    fun adoption_anOutcomeFromTheOldAdapterBeforeAnyNewRegistration_isDropped() {
        val old = FakeAdapter()
        val oldRegistration = register(old)
        val callback = RecordingCallback()
        launch(7L, callback)
        val oldRelayCallback = old.latestCallback
        relay.unregister(oldRegistration, isChangingConfigurations = true)

        oldRelayCallback.onOutcome(7L, DeviceAuthOutcome.Succeeded)

        assertTrue(callback.received.isEmpty())
        assertEquals(7L, relay.openAttemptId)
        assertTrue(relay.isOpenAttemptOrphaned)
    }

    @Test
    fun adoption_isNotRepeatedByALaterRegistration() {
        val old = FakeAdapter()
        val oldRegistration = register(old)
        launch(7L, RecordingCallback())
        relay.unregister(oldRegistration, isChangingConfigurations = true)
        val second = FakeAdapter()
        val third = FakeAdapter()
        register(second)

        register(third)

        assertEquals(1, second.adoptions.size)
        assertTrue("the live holder is not adopted from", third.adoptions.isEmpty())
    }

    @Test
    fun adoption_withNoOpenAttempt_theAdopterIsNotCalled() {
        val adapter = FakeAdapter()

        register(adapter)

        assertTrue(adapter.adoptions.isEmpty())
    }

    @Test
    fun adoption_aRefusedAdoption_closesTheAttemptWithOneSyntheticSystemCanceled() {
        val old = FakeAdapter()
        val oldRegistration = register(old)
        val callback = RecordingCallback()
        launch(7L, callback)
        relay.unregister(oldRegistration, isChangingConfigurations = true)
        val fresh = FakeAdapter()
        fresh.adoptResult = false

        register(fresh)

        assertEquals(listOf(7L), fresh.adoptions.map { it.id })
        assertEquals(listOf(7L to cancelled), callback.received)
        assertNull(relay.openAttemptId)
        assertEquals("the new registration is still current", AuthLaunch.LAUNCHED, launch(8L, RecordingCallback()))
    }

    @Test
    fun adoption_aRefusedAdoptionThatAlreadyDeliveredAnOutcome_doesNotDeliverASecondOne() {
        val old = FakeAdapter()
        val oldRegistration = register(old)
        val callback = RecordingCallback()
        launch(7L, callback)
        relay.unregister(oldRegistration, isChangingConfigurations = true)
        val fresh = FakeAdapter()
        fresh.adoptResult = false
        fresh.duringAdopt = { it.callback.onOutcome(it.id, DeviceAuthOutcome.Cancelled) }

        register(fresh)

        assertEquals(listOf(7L to DeviceAuthOutcome.Cancelled), callback.received)
    }

    @Test
    fun adoption_anOutcomeDeliveredInsideTheAdoption_isForwardedOnce() {
        val old = FakeAdapter()
        val oldRegistration = register(old)
        val callback = RecordingCallback()
        launch(7L, callback)
        relay.unregister(oldRegistration, isChangingConfigurations = true)
        val fresh = FakeAdapter()
        fresh.duringAdopt = { it.callback.onOutcome(it.id, DeviceAuthOutcome.Succeeded) }

        register(fresh)

        assertEquals(listOf(7L to DeviceAuthOutcome.Succeeded), callback.received)
        assertNull(relay.openAttemptId)
    }

    @Test
    fun adoption_cancelAfterAdoption_goesToTheAdoptingAdapter() {
        val old = FakeAdapter()
        val oldRegistration = register(old)
        val callback = RecordingCallback()
        launch(7L, callback)
        relay.unregister(oldRegistration, isChangingConfigurations = true)
        val fresh = FakeAdapter()
        register(fresh)

        relay.cancel(7L)

        assertEquals(listOf(7L), fresh.cancels)
        assertTrue(old.cancels.isEmpty())
        assertEquals(listOf(7L to cancelled), callback.received)
    }

    // ---- CLEAR_TASK: a live holder means no adoption -----------------------------------------------------

    @Test
    fun clearTask_aNewerRegistrationDoesNotAdoptFromALiveHolder_andTheOldHostStillReportsTheLoss() {
        val older = FakeAdapter()
        val newer = FakeAdapter()
        val olderRegistration = register(older)
        val callback = RecordingCallback()
        launch(1L, callback)

        val newerRegistration = register(newer)

        assertTrue(newer.adoptions.isEmpty())
        assertFalse(relay.isOpenAttemptOrphaned)
        assertEquals(1L, relay.openAttemptId)
        // The older host is destroyed for real afterwards: it reports the loss, exactly once.
        assertEquals(1L, relay.unregister(olderRegistration, isChangingConfigurations = false))
        assertNull(relay.unregister(olderRegistration, isChangingConfigurations = false))
        assertNull("the newer host never held the attempt", relay.unregister(newerRegistration, isChangingConfigurations = false))
        assertTrue(callback.received.isEmpty())
    }

    @Test
    fun clearTask_unregisteringTheOlderHost_keepsTheNewerRegistrationCurrent() {
        val older = FakeAdapter()
        val newer = FakeAdapter()
        val olderRegistration = register(older)
        register(newer)

        relay.unregister(olderRegistration, isChangingConfigurations = false)

        assertTrue(relay.hasRegistration)
        assertEquals(AuthLaunch.LAUNCHED, launch(1L, RecordingCallback()))
        assertEquals(listOf(1L), newer.launches.map { it.id })
        assertTrue(older.launches.isEmpty())
    }

    // ---- activate: the started host becomes current again -------------------------------------------------

    @Test
    fun activate_aRestartedOlderRegistrationBecomesCurrent_forNewAttempts() {
        val older = FakeAdapter()
        val newer = FakeAdapter()
        val olderRegistration = register(older)
        register(newer) // the newest registration is current

        relay.activate(olderRegistration)
        launch(1L, RecordingCallback())

        assertEquals(listOf(1L), older.launches.map { it.id })
        assertTrue(newer.launches.isEmpty())
    }

    @Test
    fun activate_afterTheOlderIsActivated_aNewerRegistrationsDestroyDoesNotStrandIt() {
        val older = FakeAdapter()
        val newer = FakeAdapter()
        val olderRegistration = register(older)
        val newerRegistration = register(newer)
        relay.activate(olderRegistration)

        assertNull(relay.unregister(newerRegistration, isChangingConfigurations = false))

        assertTrue("the older registration is still current", relay.hasRegistration)
        assertEquals(AuthLaunch.LAUNCHED, launch(1L, RecordingCallback()))
        assertEquals(listOf(1L), older.launches.map { it.id })
    }

    @Test
    fun activate_control_withoutItTheNewestRegistrationsDestroyStrandsTheOlderOne() {
        val older = FakeAdapter()
        val newer = FakeAdapter()
        register(older)
        val newerRegistration = register(newer)

        relay.unregister(newerRegistration, isChangingConfigurations = false)

        assertFalse(relay.hasRegistration)
        assertEquals(AuthLaunch.HOST_UNAVAILABLE, launch(1L, RecordingCallback()))
    }

    @Test
    fun activate_neverAdopts_anOrphanedRecordStaysOrphanedUntilARegisterAdoptsIt() {
        val old = FakeAdapter()
        val oldRegistration = register(old)
        val other = FakeAdapter()
        val otherRegistration = register(other)
        val callback = RecordingCallback()
        relay.activate(oldRegistration)
        launch(7L, callback)
        relay.unregister(oldRegistration, isChangingConfigurations = true)
        assertTrue(relay.isOpenAttemptOrphaned)

        relay.activate(otherRegistration)

        assertTrue("activate must not adopt", relay.isOpenAttemptOrphaned)
        assertTrue(other.adoptions.isEmpty())
        assertEquals(7L, relay.openAttemptId)
        assertTrue(callback.received.isEmpty())
    }

    @Test
    fun activate_offMain_throws_andChangesNothing() {
        val older = FakeAdapter()
        val newer = FakeAdapter()
        val olderRegistration = register(older)
        register(newer)
        onMain = false

        assertThrows(IllegalStateException::class.java) { relay.activate(olderRegistration) }

        onMain = true
        launch(1L, RecordingCallback())
        assertEquals(listOf(1L), newer.launches.map { it.id })
        assertTrue(older.launches.isEmpty())
    }

    // ---- the posted orphan check (an unadopted orphan is closed without the watchdog) -----------------------

    @Test
    fun orphanCheck_orphaningPostsExactlyOneCheck_andRunsNothingByItself() {
        val registration = register(FakeAdapter())
        val callback = RecordingCallback()
        launch(7L, callback)

        relay.unregister(registration, isChangingConfigurations = true)

        assertEquals(1, posted.size)
        assertTrue("the check does not run inline", callback.received.isEmpty())
        assertEquals(7L, relay.openAttemptId)
    }

    @Test
    fun orphanCheck_anOrphanNotAdoptedBeforeTheCheck_isClosedOnceWithSystemCanceled() {
        val registration = register(FakeAdapter())
        val callback = RecordingCallback()
        launch(7L, callback)
        relay.unregister(registration, isChangingConfigurations = true)

        posted.single().run()

        assertEquals(listOf(7L to cancelled), callback.received)
        assertNull(relay.openAttemptId)
        posted.single().run()
        assertEquals("exactly once", 1, callback.received.size)
    }

    @Test
    fun orphanCheck_anOrphanAdoptedBeforeTheCheck_isNotClosed_andStillAcceptsTheSuccess() {
        val old = FakeAdapter()
        val oldRegistration = register(old)
        val callback = RecordingCallback()
        launch(7L, callback)
        relay.unregister(oldRegistration, isChangingConfigurations = true)
        val fresh = FakeAdapter()
        register(fresh) // the relaunch adopts inside the same message, before the posted check

        posted.single().run()

        assertTrue("a real adoption always wins", callback.received.isEmpty())
        assertEquals(7L, relay.openAttemptId)
        assertFalse(relay.isOpenAttemptOrphaned)
        fresh.latestCallback.onOutcome(7L, DeviceAuthOutcome.Succeeded)
        assertEquals(listOf(7L to DeviceAuthOutcome.Succeeded), callback.received)
    }

    @Test
    fun orphanCheck_aStaleCheckOfAnEarlierOrphaning_doesNotCloseALaterOrphaning() {
        val first = FakeAdapter()
        val firstRegistration = register(first)
        val callback = RecordingCallback()
        launch(7L, callback)
        relay.unregister(firstRegistration, isChangingConfigurations = true) // orphaning 1 -> check 1
        val second = FakeAdapter()
        val secondRegistration = register(second) // adopted
        relay.unregister(secondRegistration, isChangingConfigurations = true) // orphaning 2 -> check 2
        assertEquals(2, posted.size)

        posted[0].run()

        assertTrue("check 1 belongs to the earlier orphaning", callback.received.isEmpty())
        assertEquals(7L, relay.openAttemptId)
        assertTrue(relay.isOpenAttemptOrphaned)
        posted[1].run()
        assertEquals(listOf(7L to cancelled), callback.received)
        assertNull(relay.openAttemptId)
    }

    @Test
    fun orphanCheck_afterTheAttemptWasAlreadyClosed_deliversNothing() {
        val registration = register(FakeAdapter())
        val callback = RecordingCallback()
        launch(7L, callback)
        relay.unregister(registration, isChangingConfigurations = true)
        relay.cancel(7L) // the relay's own close: one SYSTEM_CANCELED

        posted.single().run()

        assertEquals(listOf(7L to cancelled), callback.received)
    }

    @Test
    fun orphanCheck_doesNotCloseANewerAttemptOpenedAfterTheOrphanWasClosed() {
        val registration = register(FakeAdapter())
        launch(7L, RecordingCallback())
        relay.unregister(registration, isChangingConfigurations = true)
        relay.cancel(7L)
        val adapter = FakeAdapter()
        register(adapter)
        val newerCallback = RecordingCallback()
        launch(8L, newerCallback)

        posted.single().run()

        assertTrue(newerCallback.received.isEmpty())
        assertEquals(8L, relay.openAttemptId)
    }

    @Test
    fun orphanCheck_aRealLoss_orNoOpenAttempt_postsNothing() {
        val registration = register(FakeAdapter())
        relay.unregister(registration, isChangingConfigurations = true) // nothing open
        assertTrue(posted.isEmpty())
        val second = register(FakeAdapter())
        launch(7L, RecordingCallback())

        relay.unregister(second, isChangingConfigurations = false) // a real loss

        assertTrue(posted.isEmpty())
    }

    @Test
    fun orphanCheck_aRefusedAdoptionClosesOnce_andTheLaterCheckAddsNothing() {
        val old = FakeAdapter()
        val oldRegistration = register(old)
        val callback = RecordingCallback()
        launch(7L, callback)
        relay.unregister(oldRegistration, isChangingConfigurations = true)
        val fresh = FakeAdapter()
        fresh.adoptResult = false
        register(fresh)

        posted.single().run()

        assertEquals(listOf(7L to cancelled), callback.received)
    }

    // ---- rule (a) / (d): unregister semantics ------------------------------------------------------------

    @Test
    fun ruleD_unregisterOfTheHoldingHost_returnsTheIdOnce_removesTheRecord_andDropsLateOutcomes() {
        val adapter = FakeAdapter()
        val registration = register(adapter)
        val callback = RecordingCallback()
        launch(3L, callback)
        val adapterCallback = adapter.latestCallback

        assertEquals(3L, relay.unregister(registration, isChangingConfigurations = false))
        assertNull(relay.unregister(registration, isChangingConfigurations = false))

        assertNull(relay.openAttemptId)
        adapterCallback.onOutcome(3L, DeviceAuthOutcome.Succeeded)
        assertTrue("a late outcome for a lost attempt is dropped", callback.received.isEmpty())
        assertEquals("no host is registered after the loss", AuthLaunch.HOST_UNAVAILABLE, launch(4L, callback))
    }

    @Test
    fun ruleD_unregisterWhileChangingConfigurations_returnsNull_andOrphansTheRecord() {
        val adapter = FakeAdapter()
        val registration = register(adapter)
        launch(3L, RecordingCallback())

        assertNull(relay.unregister(registration, isChangingConfigurations = true))

        assertEquals(3L, relay.openAttemptId)
        assertTrue(relay.isOpenAttemptOrphaned)
        assertFalse(relay.hasRegistration)
        assertEquals("no host, no launch", AuthLaunch.HOST_UNAVAILABLE, launch(4L, RecordingCallback()))
    }

    @Test
    fun ruleD_unregisterWithNoOpenAttempt_returnsNull_forBothConfigurationFlags() {
        val adapter = FakeAdapter()
        val first = register(adapter)
        assertNull(relay.unregister(first, isChangingConfigurations = false))
        val second = register(adapter)
        assertNull(relay.unregister(second, isChangingConfigurations = true))

        assertNull(relay.openAttemptId)
        assertFalse(relay.isOpenAttemptOrphaned)
    }

    @Test
    fun ruleD_unregisterOfANonHoldingRegistration_returnsNull_andLeavesTheAttemptAlone() {
        val holder = FakeAdapter()
        val other = FakeAdapter()
        register(holder)
        val callback = RecordingCallback()
        launch(3L, callback)
        val otherRegistration = register(other)

        assertNull(relay.unregister(otherRegistration, isChangingConfigurations = false))

        assertEquals(3L, relay.openAttemptId)
        assertFalse(relay.isOpenAttemptOrphaned)
        holder.latestCallback.onOutcome(3L, DeviceAuthOutcome.Succeeded)
        assertEquals(listOf(3L to DeviceAuthOutcome.Succeeded), callback.received)
    }

    @Test
    fun ruleA_unregisterClearsTheCurrentRegistration_onlyWhenItIsTheCurrentOne() {
        val a = FakeAdapter()
        val b = FakeAdapter()
        val registrationA = register(a)
        val registrationB = register(b)

        relay.unregister(registrationA, isChangingConfigurations = false)
        assertTrue("A was no longer current", relay.hasRegistration)

        relay.unregister(registrationB, isChangingConfigurations = false)
        assertFalse(relay.hasRegistration)
    }

    @Test
    fun ruleA_registerReplacesTheCurrentRegistration_forNewAttempts() {
        val a = FakeAdapter()
        val b = FakeAdapter()
        register(a)
        register(b)

        launch(1L, RecordingCallback())

        assertTrue(a.launches.isEmpty())
        assertEquals(listOf(1L), b.launches.map { it.id })
    }

    @Test
    fun ruleD_aRealLossThenTheHostRecreated_theNewHostHasNothingToAdopt() {
        val old = FakeAdapter()
        val oldRegistration = register(old)
        launch(3L, RecordingCallback())
        assertEquals(3L, relay.unregister(oldRegistration, isChangingConfigurations = false))
        val fresh = FakeAdapter()

        register(fresh)

        assertTrue(fresh.adoptions.isEmpty())
        assertNull(relay.openAttemptId)
    }
}
