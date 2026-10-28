package com.yourname.expensetracker.ui.unsupported

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.DatabaseOpenGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * App lock S21-c (#10): the export button of the unsupported-database screen, with the real activity.
 *
 * This class runs WITHOUT the test seams (`exportAuthRequiredForTest`, `authenticatorFactoryForTest`), so it covers
 * the real, un-injected path, which can only end DENIED: the Robolectric application is `HiltTestApplication` with
 * no Hilt rule, so the narrow entry point cannot be resolved and the gate must fail closed (store unreadable,
 * capability unknown, authentication required). At SDK 28 the real prompt adapter then answers HOST_UNAVAILABLE
 * (API below 30), so no prompt can be launched. The expected result is the constant denied message and NO document
 * picker.
 *
 * The branches that need an injected lock state or an injected authenticator (auth not required, Succeeded,
 * Cancelled, LockedOut, a stale attempt id, a synchronous callback, a double tap while CHECKING or AUTHENTICATING,
 * onStop) are driven through the production seams by [UnsupportedDatabaseActivityExportAuthSeamTest].
 *
 * Nothing here needs a real document provider: the picker is never opened on these paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UnsupportedDatabaseActivityExportAuthTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var context: Context
    private var scenario: ActivityScenario<UnsupportedDatabaseActivity>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowLog.clear()
        DatabaseOpenGate.resetForTests()
        DatabaseOpenGate.overrideForTests(DatabaseOpenGate.REASON_SCHEMA_TOO_NEW)
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        DatabaseOpenGate.resetForTests()
    }

    private fun launchActivity(): UnsupportedDatabaseActivity {
        val launched = ActivityScenario.launch(UnsupportedDatabaseActivity::class.java)
        scenario = launched
        var activity: UnsupportedDatabaseActivity? = null
        launched.onActivity { activity = it }
        assertNotNull("activity was not launched", activity)
        return activity!!
    }

    private val exportButton: String get() = context.getString(R.string.unsupported_db_export_button)
    private val deniedMessage: String get() = context.getString(R.string.unsupported_db_export_auth_denied)

    private fun assertNoPickerWasOpened(activity: UnsupportedDatabaseActivity) {
        assertNull("no document picker", shadowOf(activity).peekNextStartedActivityForResult())
        assertNull("no activity of any kind was started", shadowOf(activity).peekNextStartedActivity())
    }

    @Test
    fun s21c_activityIsAFragmentActivity_forThePromptHost() {
        val activity: Any = launchActivity()

        assertTrue(activity is FragmentActivity)
        assertFalse("the screen is shown, not finished, while the gate blocks", (activity as FragmentActivity).isFinishing)
    }

    @Test
    fun s21c_beforeAnyTap_theExportButtonIsEnabled_andNoDeniedMessageIsShown() {
        launchActivity()

        composeRule.waitForIdle()

        composeRule.onNodeWithText(exportButton).assertIsEnabled()
        composeRule.onNodeWithText(deniedMessage).assertDoesNotExist()
    }

    @Test
    fun s21c_tapWithAuthRequiredAndNoPromptAvailable_showsTheConstantDeniedMessage_andOpensNoPicker() {
        val activity = launchActivity()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(exportButton).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(deniedMessage).assertExists()
        assertEquals(
            "the message is the one constant resource string",
            "Exporting needs your fingerprint or screen lock. Nothing was exported.",
            deniedMessage
        )
        assertNoPickerWasOpened(activity)
    }

    @Test
    fun s21c_aDeniedTap_keepsTheButtonUsable_andRecordsNoLogLineOrDiagnostic() {
        val activity = launchActivity()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(exportButton).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(exportButton).assertIsEnabled()
        val lines = ShadowLog.getLogsForTag(UnsupportedDatabaseActivity.LOG_TAG)
        assertEquals("a denied attempt writes no export log line and no new code: $lines", 0, lines.size)
        assertNoPickerWasOpened(activity)
    }

    @Test
    fun s21c_aSecondTapAfterADeniedAttempt_triesAgain_andStillOpensNoPicker() {
        val activity = launchActivity()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(exportButton).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(exportButton).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(deniedMessage).assertExists()
        assertNoPickerWasOpened(activity)
    }
}
