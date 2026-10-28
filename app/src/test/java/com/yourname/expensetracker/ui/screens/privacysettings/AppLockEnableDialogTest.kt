package com.yourname.expensetracker.ui.screens.privacysettings

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * S22-a.1: the enable explainer "Turn on app lock?" (ux 2.2a). It is stateless: it only reports Continue and dismiss.
 * The Android 11 point is rendered only when asked (SDK_INT == 30, spec 4.4, R9), and the body scrolls so it stays
 * reachable at 200% font scale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockEnableDialogTest {

    /** Same registration as the sibling Compose tests: ui-test-manifest is not on the release unit-test variant. */
    private val registerActivityRule = object : ExternalResource() {
        override fun before() {
            val appContext = ApplicationProvider.getApplicationContext<Context>()
            shadowOf(appContext.packageManager)
                .addActivityIfNotPresent(ComponentName(appContext, ComponentActivity::class.java))
        }
    }

    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private var continues = 0
    private var dismissals = 0

    private fun text(@StringRes id: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun show(showApi30ActionsPoint: Boolean, fontScale: Float = 1f) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                AppLockEnableDialog(
                    onContinue = { continues++ },
                    onDismiss = { dismissals++ },
                    showApi30ActionsPoint = showApi30ActionsPoint
                )
            }
        }
    }

    private val alwaysShownPoints = listOf(
        R.string.privacy_app_lock_enable_point_unlock,
        R.string.privacy_app_lock_enable_point_notifications,
        R.string.privacy_app_lock_enable_point_replaced,
        R.string.privacy_app_lock_enable_point_screen,
        R.string.privacy_app_lock_enable_point_background,
        R.string.privacy_app_lock_enable_point_face
    )

    @Test
    fun `the dialog shows its title, the six standing points, Continue and Cancel`() {
        show(showApi30ActionsPoint = false)

        composeRule.onNodeWithTag(AppLockEnableDialogTags.DIALOG).assertExists()
        composeRule.onNodeWithText(text(R.string.privacy_app_lock_enable_title)).assertExists()
        alwaysShownPoints.forEach { point ->
            composeRule.onNodeWithText(text(point)).assertExists()
        }
        composeRule.onNodeWithTag(AppLockEnableDialogTags.CONTINUE)
            .assertIsEnabled()
            .assert(hasText(text(R.string.privacy_app_lock_enable_continue)))
        composeRule.onNodeWithTag(AppLockEnableDialogTags.CANCEL)
            .assertIsEnabled()
            .assert(hasText(text(R.string.privacy_confirm_cancel)))
    }

    @Test
    fun `the Android 11 point is absent when showApi30ActionsPoint is false`() {
        show(showApi30ActionsPoint = false)

        composeRule.onNodeWithTag(AppLockEnableDialogTags.API30_POINT).assertDoesNotExist()
        composeRule.onAllNodesWithText(text(R.string.privacy_app_lock_enable_point_api30_actions))
            .assertCountEquals(0)
    }

    @Test
    fun `the Android 11 point is present, with its text, when showApi30ActionsPoint is true`() {
        show(showApi30ActionsPoint = true)

        composeRule.onNodeWithTag(AppLockEnableDialogTags.API30_POINT).assertExists()
        composeRule.onNodeWithText(text(R.string.privacy_app_lock_enable_point_api30_actions)).assertExists()
        // The standing points are unchanged by the extra point.
        alwaysShownPoints.forEach { point ->
            composeRule.onNodeWithText(text(point)).assertExists()
        }
    }

    @Test
    fun `Continue calls onContinue once and does not dismiss`() {
        show(showApi30ActionsPoint = false)

        composeRule.onNodeWithTag(AppLockEnableDialogTags.CONTINUE).performClick()

        assertEquals(1, continues)
        assertEquals(0, dismissals)
    }

    @Test
    fun `Cancel calls onDismiss once and does not continue`() {
        show(showApi30ActionsPoint = false)

        composeRule.onNodeWithTag(AppLockEnableDialogTags.CANCEL).performClick()

        assertEquals(1, dismissals)
        assertEquals(0, continues)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `Back dismisses through onDismiss and does not continue`() {
        show(showApi30ActionsPoint = false)
        composeRule.onNodeWithTag(AppLockEnableDialogTags.DIALOG).assertExists()

        composeRule.runOnUiThread {
            val dialog = checkNotNull(ShadowDialog.getLatestDialog()) { "the explainer dialog was not shown" }
            dialog.onBackPressed()
        }
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
        assertEquals(0, continues)
    }

    @Test
    fun `the body is a scroll container`() {
        show(showApi30ActionsPoint = true)

        composeRule.onNodeWithTag(AppLockEnableDialogTags.BODY)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
    }

    @Test
    fun `at font scale 2 the body overflows and scrolls, and the last point and both buttons are reachable`() {
        show(showApi30ActionsPoint = true, fontScale = 2f)

        val range = composeRule.onNodeWithTag(AppLockEnableDialogTags.BODY)
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("the body overflows at 200% so it must be scrollable", range.maxValue() > 0f)

        composeRule.onNodeWithTag(AppLockEnableDialogTags.API30_POINT)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(AppLockEnableDialogTags.CONTINUE).assertIsDisplayed()
        composeRule.onNodeWithTag(AppLockEnableDialogTags.CANCEL).assertIsDisplayed()
    }

    @Test
    fun `at font scale 2 Continue still reports once`() {
        show(showApi30ActionsPoint = false, fontScale = 2f)

        composeRule.onNodeWithTag(AppLockEnableDialogTags.CONTINUE).performClick()

        assertEquals(1, continues)
        assertEquals(0, dismissals)
    }

    @Test
    fun `isAndroid11 is false at SDK 34`() {
        assertFalse(isAndroid11())
    }

    @Test
    @Config(sdk = [30])
    fun `isAndroid11 is true at SDK 30`() {
        assertTrue(isAndroid11())
    }
}
