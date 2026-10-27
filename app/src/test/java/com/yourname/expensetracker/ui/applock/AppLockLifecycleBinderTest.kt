package com.yourname.expensetracker.ui.applock

import android.app.Activity
import android.app.Application
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.AuthPurpose
import com.yourname.expensetracker.domain.privacy.applock.InertReason
import com.yourname.expensetracker.domain.privacy.applock.LockCause
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * S20-a: the started-activity count and the foreground / background signals of the app lock (INV-25, AE-41),
 * and the binder's two resolve rules. Plain JVM: the Application is a mock that captures the registered
 * `ActivityLifecycleCallbacks`, the Activities are mocks with `isChangingConfigurations` stubbed, and the
 * application scope is a supervised scope on a StandardTestDispatcher that the test advances explicitly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockLifecycleBinderTest {

    private val scheduler = TestCoroutineScheduler()
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + StandardTestDispatcher(scheduler))

    private val state = MutableStateFlow<AppLockState>(AppLockState.Resolving)
    private val controller = mockk<AppLockController>(relaxed = true)
    private val events = mutableListOf<String>()

    private val application = mockk<Application>(relaxed = true)
    private val registered = slot<Application.ActivityLifecycleCallbacks>()

    private val logLines = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logLines += message + (t?.let { " THROWABLE:" + it.javaClass.name + ":" + it.message } ?: "")
        }
    }

    private lateinit var binder: AppLockLifecycleBinder

    @Before
    fun setUp() {
        Timber.plant(tree)
        every { controller.state } returns state
        every { controller.onAppForegrounded() } answers { events += "foreground" }
        every { controller.onAppBackgrounded() } answers { events += "background" }
        coEvery { controller.resolve() } just Runs
        every { application.registerActivityLifecycleCallbacks(capture(registered)) } just Runs
        binder = AppLockLifecycleBinder(controller, scope)
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
        scopeJob.cancel()
    }

    private fun install() {
        binder.install(application)
    }

    private fun callbacks(): Application.ActivityLifecycleCallbacks = registered.captured

    private fun newActivity(changingConfigurations: Boolean = false): Activity =
        mockk<Activity>(relaxed = true).also { every { it.isChangingConfigurations } returns changingConfigurations }

    private fun start(activity: Activity) = callbacks().onActivityStarted(activity)

    private fun stop(activity: Activity) = callbacks().onActivityStopped(activity)

    private fun runResolves() = scheduler.runCurrent()

    // ---- the exact started count -------------------------------------------------------------------------

    @Test
    fun count_isZeroBeforeAnythingStarts() {
        install()

        assertEquals(0, binder.startedActivityCount)
    }

    @Test
    fun count_tracksStartsAndStopsExactly() {
        install()
        val a = newActivity()
        val b = newActivity()
        val c = newActivity()

        start(a)
        assertEquals(1, binder.startedActivityCount)
        start(b)
        assertEquals(2, binder.startedActivityCount)
        start(c)
        assertEquals(3, binder.startedActivityCount)
        stop(b)
        assertEquals(2, binder.startedActivityCount)
        stop(c)
        assertEquals(1, binder.startedActivityCount)
        stop(a)
        assertEquals(0, binder.startedActivityCount)
    }

    @Test
    fun count_aStopAtZeroIsIgnored_neverNegative_andReportsNothing() {
        install()

        stop(newActivity())
        stop(newActivity(changingConfigurations = true))

        assertEquals(0, binder.startedActivityCount)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun count_afterAnIgnoredStopAtZero_theNextStartStillReportsTheForeground_evenIfTheStopWasAConfigChange() {
        install()
        stop(newActivity(changingConfigurations = true)) // unmatched: the binder was installed late

        start(newActivity())

        assertEquals(1, binder.startedActivityCount)
        assertEquals(listOf("foreground"), events)
    }

    // ---- 0 to 1 foreground, 1 to 0 background ------------------------------------------------------------

    @Test
    fun signals_firstStartReportsTheForegroundExactlyOnce_laterStartsDoNot() {
        install()

        start(newActivity())
        start(newActivity())
        start(newActivity())

        assertEquals(listOf("foreground"), events)
        verify(exactly = 1) { controller.onAppForegrounded() }
        verify(exactly = 0) { controller.onAppBackgrounded() }
    }

    @Test
    fun signals_onlyTheLastStopReportsTheBackground() {
        install()
        val a = newActivity()
        val b = newActivity()
        start(a)
        start(b)
        events.clear()

        stop(a)
        assertEquals("a 2 to 1 stop is not a background", emptyList<String>(), events)
        stop(b)

        assertEquals(listOf("background"), events)
        verify(exactly = 1) { controller.onAppBackgrounded() }
    }

    @Test
    fun signals_aFullForegroundBackgroundForegroundCycle_reportsInOrder() {
        install()
        val a = newActivity()

        start(a)
        stop(a)
        start(a)
        stop(a)

        assertEquals(listOf("foreground", "background", "foreground", "background"), events)
    }

    @Test
    fun signals_otherLifecycleCallbacksChangeNothing() {
        install()
        val a = newActivity()

        callbacks().onActivityCreated(a, null)
        callbacks().onActivityResumed(a)
        callbacks().onActivityPaused(a)
        callbacks().onActivityDestroyed(a)

        assertEquals(0, binder.startedActivityCount)
        assertEquals(emptyList<String>(), events)
    }

    // ---- AE-41: a configuration change is neither a background nor a foreground -------------------------

    @Test
    fun ae41_aConfigurationChangeStopStartPair_isNotReported() {
        install()
        val a = newActivity(changingConfigurations = true)
        start(a)
        assertEquals(listOf("foreground"), events)

        stop(a) // the rotation stop: 1 to 0 with isChangingConfigurations
        assertEquals(0, binder.startedActivityCount)
        start(newActivity()) // the recreated instance

        assertEquals("only the original foreground", listOf("foreground"), events)
        assertEquals(1, binder.startedActivityCount)
        verify(exactly = 0) { controller.onAppBackgrounded() }
        verify(exactly = 1) { controller.onAppForegrounded() }
    }

    @Test
    fun ae41_theSuppressionIsConsumedByTheNextStart_soTheNextRealCycleIsReported() {
        install()
        val rotating = newActivity(changingConfigurations = true)
        val after = newActivity()
        start(rotating)
        stop(rotating)
        start(after) // consumes the suppression
        events.clear()

        stop(after) // a real background
        start(after) // a real foreground

        assertEquals(listOf("background", "foreground"), events)
    }

    @Test
    fun ae41_twoConfigurationChangesInARow_areBothSilent() {
        install()
        val first = newActivity(changingConfigurations = true)
        val second = newActivity(changingConfigurations = true)
        start(first)
        events.clear()

        stop(first)
        start(second)
        stop(second)
        start(newActivity())

        assertEquals(emptyList<String>(), events)
        assertEquals(1, binder.startedActivityCount)
    }

    @Test
    fun ae41_aConfigurationChangeStopThatIsNotTheLastOne_setsNoSuppression() {
        install()
        val a = newActivity(changingConfigurations = true)
        val b = newActivity()
        start(a)
        start(b)
        events.clear()

        stop(a) // 2 to 1: nothing, and no suppression
        stop(b) // 1 to 0, real
        start(b) // must be reported

        assertEquals(listOf("background", "foreground"), events)
    }

    @Test
    fun ae41_control_theSameSequenceWithoutTheConfigurationFlag_isReported() {
        install()
        val a = newActivity(changingConfigurations = false)
        start(a)

        stop(a)
        start(newActivity())

        assertEquals(listOf("foreground", "background", "foreground"), events)
    }

    // ---- an unpaired configuration-change stop is reported when the relaunched instance is destroyed ---------

    private fun destroy(activity: Activity) = callbacks().onActivityDestroyed(activity)

    @Test
    fun ae41Unpaired_theRelaunchedInstanceDestroyedWithoutAStart_reportsOneBackground_andTheNextStartIsAForeground() {
        install()
        val original = newActivity(changingConfigurations = true)
        start(original)
        stop(original) // the rotation stop: held back
        destroy(original) // the original instance of the configuration change
        assertEquals("nothing is reported yet", listOf("foreground"), events)
        val relaunched = newActivity(changingConfigurations = false)

        callbacks().onActivityCreated(relaunched, null)
        destroy(relaunched) // finished in onCreate: it never started

        assertEquals(listOf("foreground", "background"), events)
        verify(exactly = 1) { controller.onAppBackgrounded() }
        start(newActivity())
        assertEquals("the next start is a real foreground", listOf("foreground", "background", "foreground"), events)
        assertEquals(1, binder.startedActivityCount)
    }

    @Test
    fun ae41Unpaired_theBackgroundIsReportedExactlyOnce_laterDestroysAddNothing() {
        install()
        val original = newActivity(changingConfigurations = true)
        start(original)
        stop(original)
        val relaunched = newActivity()

        destroy(relaunched)
        destroy(relaunched)
        destroy(newActivity())

        assertEquals(listOf("foreground", "background"), events)
    }

    @Test
    fun ae41Unpaired_aNormalRotationWithTheDestroyOfTheOriginal_isStillSilent() {
        install()
        val original = newActivity(changingConfigurations = true)
        val recreated = newActivity()
        start(original)
        events.clear()

        stop(original)
        destroy(original) // isChangingConfigurations is true while the original is destroyed
        callbacks().onActivityCreated(recreated, null)
        start(recreated) // consumes the suppression
        destroy(newActivity()) // a later unrelated destroy finds no pending suppression

        assertEquals(emptyList<String>(), events)
        assertEquals(1, binder.startedActivityCount)
    }

    @Test
    fun ae41Unpaired_aDestroyWhileAnActivityIsStarted_afterTheSuppressionWasConsumed_reportsNothing() {
        install()
        val original = newActivity(changingConfigurations = true)
        val other = newActivity()
        start(original)
        stop(original) // 1 to 0: suppression pending
        start(other) // 0 to 1: consumes it
        events.clear()

        destroy(newActivity())

        assertEquals(emptyList<String>(), events)
        assertEquals(1, binder.startedActivityCount)
    }

    @Test
    fun ae41Unpaired_aDestroyWithNoPendingSuppression_reportsNothing() {
        install()
        val a = newActivity()
        start(a)
        stop(a) // a real stop, already reported
        events.clear()

        destroy(a)
        destroy(newActivity())

        assertEquals(emptyList<String>(), events)
    }

    // ---- real stops during prompts are reported ----------------------------------------------------------

    @Test
    fun prompts_aRealStopWhileAPromptIsInFlight_isReported_andNotSuppressed() {
        install()
        val a = newActivity()
        start(a)
        state.value = AppLockState.Authenticating(7L, AuthPurpose.UNLOCK)
        events.clear()

        stop(a) // the OEM credential Activity covers the app
        start(a)

        assertEquals(listOf("background", "foreground"), events)
    }

    @Test
    fun prompts_stopBeforeTheOutcomeCallback_andOutcomeBeforeTheStop_areBothReportedExactlyOnce() {
        install()
        val a = newActivity()
        start(a)
        state.value = AppLockState.Authenticating(7L, AuthPurpose.UNLOCK)
        events.clear()

        // Order 1: the stop reaches the binder before the controller sees the prompt outcome.
        stop(a)
        events += "outcome"
        state.value = AppLockState.Unlocked
        // Order 2 (next prompt): the outcome is processed first, then the stop.
        start(a)
        state.value = AppLockState.Authenticating(8L, AuthPurpose.UNLOCK)
        events += "outcome"
        state.value = AppLockState.Unlocked
        stop(a)

        assertEquals(
            listOf("background", "outcome", "foreground", "outcome", "background"),
            events
        )
        verify(exactly = 2) { controller.onAppBackgrounded() }
    }

    @Test
    fun prompts_everyControllerStateStillReportsTheRealSignals() {
        val states = listOf(
            AppLockState.Disabled,
            AppLockState.Locked(LockCause.COLD_START),
            AppLockState.Authenticating(1L, AuthPurpose.ENABLE),
            AppLockState.Authenticating(2L, AuthPurpose.CONFIRM_CHANGE),
            AppLockState.Unlocked,
            AppLockState.Inert(InertReason.NO_CREDENTIAL)
        )
        install()
        val a = newActivity()
        for (s in states) {
            state.value = s
            events.clear()

            start(a)
            stop(a)

            assertEquals("state $s", listOf("foreground", "background"), events)
        }
    }

    // ---- install ----------------------------------------------------------------------------------------

    @Test
    fun install_isIdempotent_registersOnceAndLaunchesTheFirstResolveOnce() {
        val other = mockk<Application>(relaxed = true)

        binder.install(application)
        binder.install(application)
        binder.install(other)
        runResolves()

        verify(exactly = 1) { application.registerActivityLifecycleCallbacks(any()) }
        verify(exactly = 0) { other.registerActivityLifecycleCallbacks(any()) }
        coVerify(exactly = 1) { controller.resolve() }
    }

    @Test
    fun install_neverReportsASignalByItself() {
        install()
        runResolves()

        assertEquals(emptyList<String>(), events)
        assertEquals(0, binder.startedActivityCount)
    }

    // ---- resolve: at install, and on a start only while Resolving ----------------------------------------

    @Test
    fun resolve_isLaunchedAtInstall_onTheApplicationScope() {
        install()
        coVerify(exactly = 0) { controller.resolve() } // queued on the scope's dispatcher, not run inline

        runResolves()

        coVerify(exactly = 1) { controller.resolve() }
    }

    @Test
    fun resolve_everyStartWhileResolving_launchesItAgain() {
        install()
        runResolves()

        start(newActivity())
        start(newActivity())
        runResolves()

        coVerify(exactly = 3) { controller.resolve() } // install + two starts
    }

    @Test
    fun resolve_aStartWhileNotResolving_doesNotLaunchIt() {
        val settled = listOf(
            AppLockState.Disabled,
            AppLockState.Locked(LockCause.COLD_START),
            AppLockState.Authenticating(1L, AuthPurpose.UNLOCK),
            AppLockState.Unlocked,
            AppLockState.Inert(InertReason.UNKNOWN)
        )
        install()
        runResolves()
        for (s in settled) {
            state.value = s

            start(newActivity())
            runResolves()
        }

        coVerify(exactly = 1) { controller.resolve() } // only the one at install
    }

    @Test
    fun resolve_stopsDoNotLaunchIt_evenWhileResolving() {
        install()
        val a = newActivity()
        start(a)
        runResolves()
        coVerify(exactly = 2) { controller.resolve() }

        stop(a)
        runResolves()

        coVerify(exactly = 2) { controller.resolve() }
    }

    @Test
    fun resolve_afterTheStateLeavesResolving_laterStartsStopLaunchingIt() {
        install()
        runResolves()
        start(newActivity())
        runResolves()
        coVerify(exactly = 2) { controller.resolve() }

        state.value = AppLockState.Disabled
        start(newActivity())
        runResolves()

        coVerify(exactly = 2) { controller.resolve() }
    }

    // ---- resolve failure handling ------------------------------------------------------------------------

    @Test
    fun resolveFailure_aCancellationException_propagatesCancelsTheJob_andIsNotLogged() {
        coEvery { controller.resolve() } throws CancellationException("synthetic cancellation")
        install()
        val child = scopeJob.children.toList().single()

        runResolves()

        assertTrue("the cancellation must cancel the coroutine, not be swallowed", child.isCancelled)
        assertEquals("a cancellation is not a failure to log", emptyList<String>(), logLines)
    }

    @Test
    fun resolveFailure_anotherException_isLoggedByClassNameOnly_andTheScopeSurvives() {
        coEvery { controller.resolve() } throws IllegalStateException("secret-detail-do-not-log")
        install()
        val child = scopeJob.children.toList().single()

        runResolves()

        assertTrue(child.isCompleted)
        assertFalse("a plain failure is handled, not propagated as cancellation", child.isCancelled)
        assertEquals(1, logLines.size)
        assertTrue("class name expected in ${logLines.single()}", logLines.single().contains("IllegalStateException"))
        assertFalse("no message in the log", logLines.single().contains("secret-detail-do-not-log"))
        assertFalse("no throwable attached to the log", logLines.single().contains("THROWABLE"))
        assertTrue("the scope itself is alive", scopeJob.isActive)
        // A later start while still Resolving launches again.
        coEvery { controller.resolve() } just Runs
        start(newActivity())
        runResolves()
        coVerify(atLeast = 2) { controller.resolve() }
    }

    @Test
    fun resolveFailure_control_aSuccessfulResolveLogsNothing() {
        install()

        runResolves()

        assertEquals(emptyList<String>(), logLines)
    }
}
