package com.yourname.expensetracker.ui.unsupported

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.DatabaseOpenGate
import com.yourname.expensetracker.domain.privacy.applock.AuthLaunch
import com.yourname.expensetracker.domain.privacy.applock.AuthPromptCopy
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCallback
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthErrorBucket
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthOutcome
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthenticator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * App lock S21-c (#10): the behaviour of the export button of the unsupported-database screen, driven through the two
 * test seams of the activity companion (`exportAuthRequiredForTest`, `authenticatorFactoryForTest`).
 *
 * Both seams are set BEFORE the activity is created (the factory is read once in `onCreate`) and reset to null in
 * [tearDown]. The real activity runs; only the gate read and the system prompt are replaced. The document picker is
 * observed through `ShadowActivity` (the same mechanism the denied-branch class uses), and every "no picker" case sits
 * beside a positive control (`requiredAndSucceeded_opensThePickerExactlyOnce_control`) that proves a picker launch is
 * visible to that observation.
 *
 * A stale attempt id is IGNORED (the state stays AUTHENTICATING and no message is shown); it never produces the
 * denied message by itself. The denied message follows the real outcome of the open attempt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UnsupportedDatabaseActivityExportAuthSeamTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var context: Context
    private var scenario: ActivityScenario<UnsupportedDatabaseActivity>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseOpenGate.resetForTests()
        DatabaseOpenGate.overrideForTests(DatabaseOpenGate.REASON_SCHEMA_TOO_NEW)
        UnsupportedDatabaseActivity.exportAuthRequiredForTest = null
        UnsupportedDatabaseActivity.authenticatorFactoryForTest = null
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        UnsupportedDatabaseActivity.exportAuthRequiredForTest = null
        UnsupportedDatabaseActivity.authenticatorFactoryForTest = null
        DatabaseOpenGate.resetForTests()
    }

    // ---- fakes ------------------------------------------------------------------------------------------------

    /** Records every call and lets the test deliver outcomes. Plain `Long` ids only. */
    private class FakeDeviceAuthenticator(
        private val launchAnswer: AuthLaunch = AuthLaunch.LAUNCHED,
        private val synchronousOutcome: DeviceAuthOutcome? = null,
        private val outcomeOnCancel: DeviceAuthOutcome? = null
    ) : DeviceAuthenticator {
        val authenticateIds = mutableListOf<Long>()
        val copies = mutableListOf<AuthPromptCopy>()
        val cancelIds = mutableListOf<Long>()
        private var callback: DeviceAuthCallback? = null

        override fun authenticate(attemptId: Long, copy: AuthPromptCopy, callback: DeviceAuthCallback): AuthLaunch {
            authenticateIds += attemptId
            copies += copy
            this.callback = callback
            synchronousOutcome?.let { callback.onOutcome(attemptId, it) }
            return launchAnswer
        }

        override fun cancel(attemptId: Long) {
            cancelIds += attemptId
            outcomeOnCancel?.let { callback?.onOutcome(attemptId, it) }
        }

        fun deliver(attemptId: Long, outcome: DeviceAuthOutcome) {
            checkNotNull(callback) { "authenticate was never called" }.onOutcome(attemptId, outcome)
        }

        fun deliverToLatest(outcome: DeviceAuthOutcome) = deliver(authenticateIds.last(), outcome)
    }

    /** A suspending gate held by a [CompletableDeferred]; counts its calls. */
    private class HeldGate {
        val release = CompletableDeferred<Boolean>()
        var calls = 0
        val gate: suspend (Context) -> Boolean = { _ ->
            calls++
            release.await()
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private val exportButton: String get() = context.getString(R.string.unsupported_db_export_button)
    private val deniedMessage: String get() = context.getString(R.string.unsupported_db_export_auth_denied)

    private fun startActivity(
        gate: suspend (Context) -> Boolean,
        authenticator: DeviceAuthenticator?
    ): UnsupportedDatabaseActivity {
        UnsupportedDatabaseActivity.exportAuthRequiredForTest = gate
        if (authenticator != null) {
            UnsupportedDatabaseActivity.authenticatorFactoryForTest = { _ -> authenticator }
        }
        val launched = ActivityScenario.launch(UnsupportedDatabaseActivity::class.java)
        scenario = launched
        var activity: UnsupportedDatabaseActivity? = null
        launched.onActivity { activity = it }
        assertNotNull("activity was not launched", activity)
        composeRule.waitForIdle()
        return activity!!
    }

    private fun tap() {
        composeRule.onNodeWithText(exportButton).performClick()
        composeRule.waitForIdle()
    }

    private fun deliver(auth: FakeDeviceAuthenticator, outcome: DeviceAuthOutcome) {
        composeRule.runOnUiThread { auth.deliverToLatest(outcome) }
        composeRule.waitForIdle()
    }

    private fun assertDeniedAndUsable() {
        composeRule.onNodeWithText(deniedMessage).assertExists()
        composeRule.onNodeWithText(exportButton).assertIsEnabled()
    }

    private fun assertNoPicker(activity: UnsupportedDatabaseActivity) {
        assertNull("no document picker", shadowOf(activity).peekNextStartedActivityForResult())
        assertNull("no activity of any kind was started", shadowOf(activity).peekNextStartedActivity())
    }

    private fun assertPickerOpenedExactlyOnce(activity: UnsupportedDatabaseActivity) {
        val first = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("the document picker was not opened", first)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, first!!.intent.action)
        assertNull("the document picker was opened more than once", shadowOf(activity).nextStartedActivityForResult)
    }

    // ---- not required -----------------------------------------------------------------------------------------

    @Test
    fun s21c_seam_authNotRequired_opensThePickerWithoutAnyPrompt() {
        val auth = FakeDeviceAuthenticator()
        var gateCalls = 0
        var gateContext: Context? = null
        val activity = startActivity({ ctx -> gateCalls++; gateContext = ctx; false }, auth)

        tap()

        assertEquals("the gate is read once per tap", 1, gateCalls)
        assertSame("the gate gets the application context", activity.applicationContext, gateContext)
        assertTrue("no prompt when authentication is not required", auth.authenticateIds.isEmpty())
        assertPickerOpenedExactlyOnce(activity)
        composeRule.onNodeWithText(deniedMessage).assertDoesNotExist()
        composeRule.onNodeWithText(exportButton).assertIsEnabled()
    }

    // ---- required + outcomes ----------------------------------------------------------------------------------

    @Test
    fun s21c_seam_requiredAndSucceeded_opensThePickerExactlyOnce_control() {
        val auth = FakeDeviceAuthenticator()
        val activity = startActivity({ _ -> true }, auth)

        tap()

        assertEquals("exactly one prompt", 1, auth.authenticateIds.size)
        assertEquals(listOf(AuthPromptCopy.UNLOCK), auth.copies)
        assertNoPicker(activity) // nothing is opened before the prompt succeeds
        composeRule.onNodeWithText(exportButton).assertIsNotEnabled()

        deliver(auth, DeviceAuthOutcome.Succeeded)

        assertPickerOpenedExactlyOnce(activity)
        composeRule.onNodeWithText(deniedMessage).assertDoesNotExist()
        composeRule.onNodeWithText(exportButton).assertIsEnabled()
        assertTrue("the prompt was not cancelled", auth.cancelIds.isEmpty())
    }

    @Test
    fun s21c_seam_aRepeatedSucceededCallback_doesNotOpenASecondPicker() {
        val auth = FakeDeviceAuthenticator()
        val activity = startActivity({ _ -> true }, auth)
        tap()

        deliver(auth, DeviceAuthOutcome.Succeeded)
        deliver(auth, DeviceAuthOutcome.Succeeded)

        assertPickerOpenedExactlyOnce(activity)
    }

    private fun assertOutcomeDeniesWithNoPicker(outcome: DeviceAuthOutcome) {
        val auth = FakeDeviceAuthenticator()
        val activity = startActivity({ _ -> true }, auth)
        tap()
        assertEquals(1, auth.authenticateIds.size)

        deliver(auth, outcome)

        assertDeniedAndUsable()
        assertNoPicker(activity)
        assertEquals(
            "the string is the one constant resource string",
            "Exporting needs your fingerprint or screen lock. Nothing was exported.",
            deniedMessage
        )
    }

    @Test
    fun s21c_seam_cancelled_deniesWithNoPicker() = assertOutcomeDeniesWithNoPicker(DeviceAuthOutcome.Cancelled)

    @Test
    fun s21c_seam_lockedOutTemporary_deniesWithNoPicker() =
        assertOutcomeDeniesWithNoPicker(DeviceAuthOutcome.LockedOut(permanent = false))

    @Test
    fun s21c_seam_lockedOutPermanent_deniesWithNoPicker() =
        assertOutcomeDeniesWithNoPicker(DeviceAuthOutcome.LockedOut(permanent = true))

    @Test
    fun s21c_seam_notAvailable_deniesWithNoPicker() = assertOutcomeDeniesWithNoPicker(DeviceAuthOutcome.NotAvailable)

    @Test
    fun s21c_seam_failed_everyBucket_deniesWithNoPicker() {
        // One activity per bucket: the loop proves no bucket is read as success.
        for (bucket in DeviceAuthErrorBucket.entries) {
            assertOutcomeDeniesWithNoPicker(DeviceAuthOutcome.Failed(bucket))
            scenario?.close()
            scenario = null
        }
    }

    @Test
    fun s21c_seam_aDeniedAttempt_canBeRetried_andASucceededRetryOpensThePicker() {
        val auth = FakeDeviceAuthenticator()
        val activity = startActivity({ _ -> true }, auth)
        tap()
        deliver(auth, DeviceAuthOutcome.Cancelled)
        assertDeniedAndUsable()
        assertNoPicker(activity)

        tap()
        assertEquals("a second prompt for the retry", 2, auth.authenticateIds.size)
        assertTrue("each attempt has a fresh id", auth.authenticateIds[1] > auth.authenticateIds[0])
        deliver(auth, DeviceAuthOutcome.Succeeded)

        assertPickerOpenedExactlyOnce(activity)
    }

    // ---- stale attempt id -------------------------------------------------------------------------------------

    @Test
    fun s21c_seam_aStaleAttemptId_isIgnored_andTheOpenAttemptStillDecides() {
        val auth = FakeDeviceAuthenticator()
        val activity = startActivity({ _ -> true }, auth)
        tap()
        val openId = auth.authenticateIds.single()

        composeRule.runOnUiThread { auth.deliver(openId + 1_000L, DeviceAuthOutcome.Succeeded) }
        composeRule.waitForIdle()

        assertNoPicker(activity)
        composeRule.onNodeWithText(deniedMessage).assertDoesNotExist()
        composeRule.onNodeWithText(exportButton).assertIsNotEnabled() // still waiting for the open attempt

        deliver(auth, DeviceAuthOutcome.Cancelled) // the open attempt decides
        assertDeniedAndUsable()
        assertNoPicker(activity)
    }

    @Test
    fun s21c_seam_aLateSucceededOfAClosedEarlierAttempt_neverOpensThePicker() {
        val auth = FakeDeviceAuthenticator()
        val activity = startActivity({ _ -> true }, auth)
        tap()
        val firstId = auth.authenticateIds.single()
        deliver(auth, DeviceAuthOutcome.Cancelled)
        tap() // attempt 2 is open
        assertEquals(2, auth.authenticateIds.size)

        composeRule.runOnUiThread { auth.deliver(firstId, DeviceAuthOutcome.Succeeded) }
        composeRule.waitForIdle()

        assertNoPicker(activity)
        composeRule.onNodeWithText(exportButton).assertIsNotEnabled()
        deliver(auth, DeviceAuthOutcome.Succeeded) // control: the current id still works
        assertPickerOpenedExactlyOnce(activity)
    }

    // ---- non-LAUNCHED launch answers --------------------------------------------------------------------------

    private fun assertLaunchAnswerDenies(answer: AuthLaunch) {
        val auth = FakeDeviceAuthenticator(launchAnswer = answer)
        val activity = startActivity({ _ -> true }, auth)

        tap()

        assertEquals(1, auth.authenticateIds.size)
        assertDeniedAndUsable()
        assertNoPicker(activity)
        // a callback that arrives anyway, after the denial, can no longer open the picker
        composeRule.runOnUiThread { auth.deliverToLatest(DeviceAuthOutcome.Succeeded) }
        composeRule.waitForIdle()
        assertNoPicker(activity)
    }

    @Test
    fun s21c_seam_launchAnswerAlreadyInFlight_deniesWithNoPicker() = assertLaunchAnswerDenies(AuthLaunch.ALREADY_IN_FLIGHT)

    @Test
    fun s21c_seam_launchAnswerHostUnavailable_deniesWithNoPicker() = assertLaunchAnswerDenies(AuthLaunch.HOST_UNAVAILABLE)

    @Test
    fun s21c_seam_launchAnswerAwaitingPriorTerminal_deniesWithNoPicker() =
        assertLaunchAnswerDenies(AuthLaunch.AWAITING_PRIOR_TERMINAL)

    @Test
    fun s21c_seam_launchAnswerPromptUnavailable_deniesWithNoPicker() = assertLaunchAnswerDenies(AuthLaunch.PROMPT_UNAVAILABLE)

    // ---- double tap -------------------------------------------------------------------------------------------

    @Test
    fun s21c_seam_aSecondTapWhileChecking_doesNothing() {
        val held = HeldGate()
        val auth = FakeDeviceAuthenticator()
        val activity = startActivity(held.gate, auth)

        tap()
        assertEquals(1, held.calls)
        composeRule.onNodeWithText(exportButton).assertIsNotEnabled()
        tap() // second tap while the gate read is held

        assertEquals("the gate is not read a second time", 1, held.calls)
        assertTrue(auth.authenticateIds.isEmpty())
        assertNoPicker(activity)

        held.release.complete(true) // control: the one check continues to exactly one prompt
        composeRule.waitForIdle()
        assertEquals(1, auth.authenticateIds.size)
        deliver(auth, DeviceAuthOutcome.Succeeded)
        assertPickerOpenedExactlyOnce(activity)
    }

    @Test
    fun s21c_seam_aSecondTapWhileAuthenticating_doesNothing() {
        val auth = FakeDeviceAuthenticator()
        var gateCalls = 0
        val activity = startActivity({ _ -> gateCalls++; true }, auth)

        tap()
        assertEquals(1, auth.authenticateIds.size)
        composeRule.onNodeWithText(exportButton).assertIsNotEnabled()
        tap() // second tap while the prompt is open

        assertEquals("no second gate read", 1, gateCalls)
        assertEquals("no second prompt", 1, auth.authenticateIds.size)
        assertNoPicker(activity)
        composeRule.onNodeWithText(deniedMessage).assertDoesNotExist()

        deliver(auth, DeviceAuthOutcome.Succeeded) // control
        assertPickerOpenedExactlyOnce(activity)
    }

    // ---- synchronous callback ---------------------------------------------------------------------------------

    @Test
    fun s21c_seam_aSynchronousSucceededInsideAuthenticate_opensThePickerOnce() {
        val auth = FakeDeviceAuthenticator(synchronousOutcome = DeviceAuthOutcome.Succeeded)
        val activity = startActivity({ _ -> true }, auth)

        tap()

        assertEquals(1, auth.authenticateIds.size)
        assertPickerOpenedExactlyOnce(activity)
        composeRule.onNodeWithText(deniedMessage).assertDoesNotExist()
        composeRule.onNodeWithText(exportButton).assertIsEnabled()
    }

    @Test
    fun s21c_seam_aSynchronousCancelledInsideAuthenticate_deniesWithNoPicker() {
        val auth = FakeDeviceAuthenticator(synchronousOutcome = DeviceAuthOutcome.Cancelled)
        val activity = startActivity({ _ -> true }, auth)

        tap()

        assertEquals(1, auth.authenticateIds.size)
        assertDeniedAndUsable()
        assertNoPicker(activity)
    }

    // ---- the gate-not-blocking finish path --------------------------------------------------------------------

    @Test
    fun s21c_seam_whenTheGateBlocks_theFactoryIsInvokedOnceWithTheActivity_control() {
        var calls = 0
        var host: FragmentActivity? = null
        UnsupportedDatabaseActivity.authenticatorFactoryForTest = { h ->
            calls++
            host = h
            FakeDeviceAuthenticator()
        }
        val launched = ActivityScenario.launch(UnsupportedDatabaseActivity::class.java)
        scenario = launched
        var activity: UnsupportedDatabaseActivity? = null
        launched.onActivity { activity = it }

        assertEquals(1, calls)
        assertSame(activity, host)
    }

    @Test
    fun s21c_seam_whenTheGateDoesNotBlock_theActivityFinishesAndTheFactoryIsNeverInvoked() {
        DatabaseOpenGate.resetForTests() // no override, no live file -> not blocked
        assertNull(DatabaseOpenGate.blockedReasonOrNull(context))
        var calls = 0
        UnsupportedDatabaseActivity.authenticatorFactoryForTest = { _ ->
            calls++
            FakeDeviceAuthenticator()
        }

        val activity = Robolectric.buildActivity(UnsupportedDatabaseActivity::class.java).create().get()

        assertTrue("the screen must finish when the gate does not block", activity.isFinishing)
        assertEquals("no authenticator is built on the finish path", 0, calls)
    }

    // ---- onStop -----------------------------------------------------------------------------------------------

    private fun stopAndRestart() {
        val current = checkNotNull(scenario)
        current.moveToState(Lifecycle.State.CREATED)
        current.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
    }

    @Test
    fun s21c_seam_onStopWhileAuthenticating_cancelsTheOpenAttempt_andEndsDeniedWithNoPicker_whenCancelDelivers() {
        val auth = FakeDeviceAuthenticator(
            outcomeOnCancel = DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED)
        )
        val activity = startActivity({ _ -> true }, auth)
        tap()
        val openId = auth.authenticateIds.single()

        stopAndRestart()

        assertEquals("cancel is called once, with the open attempt id", listOf(openId), auth.cancelIds)
        assertDeniedAndUsable()
        assertNoPicker(activity)
    }

    @Test
    fun s21c_seam_onStopWhileAuthenticating_cancelsTheOpenAttempt_andEndsDeniedWithNoPicker_whenNothingIsDelivered() {
        val auth = FakeDeviceAuthenticator() // cancel delivers nothing
        val activity = startActivity({ _ -> true }, auth)
        tap()
        val openId = auth.authenticateIds.single()

        stopAndRestart()

        assertEquals(listOf(openId), auth.cancelIds)
        assertDeniedAndUsable() // fail closed without an outcome
        assertNoPicker(activity)

        // a Succeeded that still arrives for the closed attempt cannot open the picker
        composeRule.runOnUiThread { auth.deliver(openId, DeviceAuthOutcome.Succeeded) }
        composeRule.waitForIdle()
        assertNoPicker(activity)
    }

    @Test
    fun s21c_seam_aSecondStopAfterTheAttemptEnded_doesNotCancelAgain() {
        val auth = FakeDeviceAuthenticator()
        startActivity({ _ -> true }, auth)
        tap()
        stopAndRestart()
        assertEquals(1, auth.cancelIds.size)

        stopAndRestart()

        assertEquals("no attempt is open any more", 1, auth.cancelIds.size)
    }

    @Test
    fun s21c_seam_onStopWhileIdle_doesNotCancel() {
        val auth = FakeDeviceAuthenticator()
        val activity = startActivity({ _ -> true }, auth)

        stopAndRestart()

        assertTrue(auth.cancelIds.isEmpty())
        assertTrue(auth.authenticateIds.isEmpty())
        composeRule.onNodeWithText(exportButton).assertIsEnabled()
        composeRule.onNodeWithText(deniedMessage).assertDoesNotExist()
        assertNoPicker(activity)
    }

    @Test
    fun s21c_seam_onStopWhileChecking_doesNotCancel() {
        val held = HeldGate()
        val auth = FakeDeviceAuthenticator()
        val activity = startActivity(held.gate, auth)
        tap()
        assertEquals(1, held.calls)

        checkNotNull(scenario).moveToState(Lifecycle.State.CREATED)

        assertTrue("no prompt is open while the gate is read, so nothing is cancelled", auth.cancelIds.isEmpty())
        assertTrue(auth.authenticateIds.isEmpty())
        assertNoPicker(activity)
    }

    // ---- cancellation and failure of the CHECKING coroutine ---------------------------------------------------

    @Test
    fun s21c_seam_cancellingTheCheckingCoroutine_leavesTheStateIdleWithNoPicker() {
        val held = HeldGate()
        val auth = FakeDeviceAuthenticator()
        val activity = startActivity(held.gate, auth)
        tap()
        composeRule.onNodeWithText(exportButton).assertIsNotEnabled()

        checkNotNull(scenario).onActivity { it.lifecycleScope.coroutineContext.cancelChildren() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(exportButton).assertIsEnabled()
        composeRule.onNodeWithText(deniedMessage).assertDoesNotExist()
        assertNoPicker(activity)
        held.release.complete(true) // a late answer reaches nobody
        composeRule.waitForIdle()
        assertTrue(auth.authenticateIds.isEmpty())
        assertNoPicker(activity)
    }

    @Test
    fun s21c_seam_aGateThatThrowsCancellation_leavesTheStateIdleWithNoPicker() {
        val release = CompletableDeferred<Unit>()
        val auth = FakeDeviceAuthenticator()
        val activity = startActivity({ _ ->
            release.await()
            throw CancellationException("synthetic cancellation")
        }, auth)
        tap()
        composeRule.onNodeWithText(exportButton).assertIsNotEnabled()

        release.complete(Unit)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(exportButton).assertIsEnabled()
        composeRule.onNodeWithText(deniedMessage).assertDoesNotExist()
        assertTrue(auth.authenticateIds.isEmpty())
        assertNoPicker(activity)
    }

    /**
     * A non-cancellation failure of the gate resets the state to idle and is not swallowed.
     *
     * If running the tap inside `runTest` ever reports `UncaughtExceptionsBeforeTest`, the documented fallback is
     * the state-only form of this test (reset assertions without the propagation assertion), with the
     * CHECKING-block structural pin as the non-swallow proof.
     */
    @Test
    fun s21c_seam_aGateThatThrowsANonCancellation_resetsTheState_andTheExceptionStillPropagates() {
        val boom = IllegalStateException("synthetic gate failure")
        val auth = FakeDeviceAuthenticator()
        val activity = startActivity({ _ -> throw boom }, auth)

        // The launched coroutine has no handler. Outside a TestScope, kotlinx-coroutines-test's global
        // ExceptionCollector would store it and fail the Compose rule's post-body runTest. Running the tap
        // inside runTest registers a scope, which receives the exception and rethrows it from runTest.
        val thrown = runCatching { runTest { tap() } }.exceptionOrNull()

        composeRule.onNodeWithText(exportButton).assertIsEnabled() // not left CHECKING
        composeRule.onNodeWithText(deniedMessage).assertDoesNotExist()
        assertTrue(auth.authenticateIds.isEmpty())
        assertNoPicker(activity)
        assertNotNull("the exception is not swallowed", thrown)
        assertTrue("boom must propagate, got: $thrown", thrown!!.reaches(boom))
    }

    private fun Throwable.reaches(target: Throwable, seen: MutableSet<Throwable> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())): Boolean {
        if (!seen.add(this)) return false
        return this === target || cause?.reaches(target, seen) == true || suppressed.any { it.reaches(target, seen) }
    }
}
