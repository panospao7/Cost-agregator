package com.yourname.expensetracker.ui.screens.privacysettings

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import org.junit.Assert.assertEquals
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
 * S22-b.1: the "Lock after leaving the app" picker (ux 2.2b, AE-116, TECH-13, D-S22-5). It is stateless: it reports the
 * person's choice and a dismissal, and never moves the radio by itself (the radio shows the COMMITTED timeout, INV-46).
 * While the lock is paused (`longerDisabled`) every option longer than the committed one is disabled with a note.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoLockTimeoutDialogTest {

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

    private val selections = mutableListOf<AutoLockTimeout>()
    private var dismissals = 0

    private fun text(@StringRes id: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    /** The five options in the order the picker shows them, with the label each one must carry. */
    private val expectedOptions: List<Pair<AutoLockTimeout, Int>> = listOf(
        AutoLockTimeout.IMMEDIATE to R.string.applock_timeout_immediately,
        AutoLockTimeout.S30 to R.string.applock_timeout_30s,
        AutoLockTimeout.M1 to R.string.applock_timeout_1m,
        AutoLockTimeout.M5 to R.string.applock_timeout_5m,
        AutoLockTimeout.M15 to R.string.applock_timeout_15m
    )

    /** The dialog arguments are state, so one test can walk through several selections with one composition. */
    private val selectedState = mutableStateOf(AutoLockTimeout.M1)
    private val longerDisabledState = mutableStateOf(false)

    private fun show(selected: AutoLockTimeout, longerDisabled: Boolean = false, fontScale: Float = 1f) {
        selectedState.value = selected
        longerDisabledState.value = longerDisabled
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                AutoLockTimeoutDialog(
                    selected = selectedState.value,
                    longerDisabled = longerDisabledState.value,
                    onSelect = { selections += it },
                    onDismiss = { dismissals++ }
                )
            }
        }
    }

    private fun update(selected: AutoLockTimeout, longerDisabled: Boolean) {
        composeRule.runOnIdle {
            selectedState.value = selected
            longerDisabledState.value = longerDisabled
        }
        composeRule.waitForIdle()
    }

    private fun option(timeout: AutoLockTimeout) =
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.option(timeout))

    private fun assertEnabledSet(enabled: Set<AutoLockTimeout>) {
        AutoLockTimeout.values().forEach { timeout ->
            if (timeout in enabled) {
                option(timeout).assertIsEnabled()
            } else {
                option(timeout).assertIsNotEnabled()
            }
        }
    }

    @Test
    fun `the dialog shows its title and the five options in order with their labels`() {
        show(AutoLockTimeout.M1)

        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.DIALOG).assertExists()
        composeRule.onNodeWithText(text(R.string.privacy_app_lock_timeout_label)).assertExists()
        assertEquals(5, expectedOptions.size)
        var previousTop = Float.NEGATIVE_INFINITY
        expectedOptions.forEach { (timeout, labelRes) ->
            option(timeout).assert(hasText(text(labelRes)))
            val top = option(timeout).fetchSemanticsNode().boundsInRoot.top
            assertTrue("$timeout must be below the previous option", top > previousTop)
            previousTop = top
        }
    }

    @Test
    fun `only the selected option is checked, for every selection`() {
        show(AutoLockTimeout.IMMEDIATE)
        AutoLockTimeout.values().forEach { selected ->
            update(selected, longerDisabled = false)
            AutoLockTimeout.values().forEach { timeout ->
                if (timeout == selected) {
                    option(timeout).assertIsSelected()
                } else {
                    option(timeout).assertIsNotSelected()
                }
            }
        }
    }

    @Test
    fun `a tap reports the option once and does not dismiss, and the radio does not move`() {
        show(AutoLockTimeout.M1)

        option(AutoLockTimeout.M5).performScrollTo().performClick()

        assertEquals(listOf(AutoLockTimeout.M5), selections)
        assertEquals(0, dismissals)
        // INV-46: the radio keeps showing the committed value until the caller passes a new one.
        option(AutoLockTimeout.M1).assertIsSelected()
        option(AutoLockTimeout.M5).assertIsNotSelected()
    }

    @Test
    fun `a tap on the selected option still reports it`() {
        show(AutoLockTimeout.S30)

        option(AutoLockTimeout.S30).performClick()

        assertEquals(listOf(AutoLockTimeout.S30), selections)
        assertEquals(0, dismissals)
    }

    @Test
    fun `Cancel calls onDismiss once and selects nothing`() {
        show(AutoLockTimeout.M1)

        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.CANCEL)
            .assertIsEnabled()
            .assert(hasText(text(R.string.privacy_confirm_cancel)))
            .performClick()

        assertEquals(1, dismissals)
        assertEquals(emptyList<AutoLockTimeout>(), selections)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `Back dismisses through onDismiss and selects nothing`() {
        show(AutoLockTimeout.M1)
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.DIALOG).assertExists()

        composeRule.runOnUiThread {
            val dialog = checkNotNull(ShadowDialog.getLatestDialog()) { "the picker dialog was not shown" }
            dialog.onBackPressed()
        }
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
        assertEquals(emptyList<AutoLockTimeout>(), selections)
    }

    @Test
    fun `longerDisabled false enables every option and shows no paused note`() {
        show(AutoLockTimeout.M1, longerDisabled = false)

        assertEnabledSet(AutoLockTimeout.values().toSet())
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.PAUSED_NOTE).assertDoesNotExist()
        composeRule.onAllNodesWithText(text(R.string.applock_timeout_paused_note)).assertCountEquals(0)
        // The fixed note is always shown.
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.NOTE)
            .assert(hasText(text(R.string.privacy_app_lock_timeout_note)))
    }

    @Test
    fun `TECH-13 longerDisabled with M1 disables M5 and M15 only, and shows the paused note`() {
        show(AutoLockTimeout.M1, longerDisabled = true)

        assertEnabledSet(setOf(AutoLockTimeout.IMMEDIATE, AutoLockTimeout.S30, AutoLockTimeout.M1))
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.PAUSED_NOTE)
            .assertExists()
            .assert(hasText(text(R.string.applock_timeout_paused_note)))
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.NOTE)
            .assert(hasText(text(R.string.privacy_app_lock_timeout_note)))
    }

    @Test
    fun `TECH-13 a disabled option reports nothing when tapped, an enabled shorter one does`() {
        show(AutoLockTimeout.M1, longerDisabled = true)

        option(AutoLockTimeout.M5).performScrollTo().performClick()
        option(AutoLockTimeout.M15).performScrollTo().performClick()
        assertEquals(emptyList<AutoLockTimeout>(), selections)

        option(AutoLockTimeout.S30).performClick()
        assertEquals(listOf(AutoLockTimeout.S30), selections)
        assertEquals(0, dismissals)
    }

    @Test
    fun `TECH-13 boundary - with the longest timeout selected nothing is disabled and there is no paused note`() {
        show(AutoLockTimeout.M15, longerDisabled = true)

        assertEnabledSet(AutoLockTimeout.values().toSet())
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.PAUSED_NOTE).assertDoesNotExist()
    }

    @Test
    fun `TECH-13 boundary - with IMMEDIATE selected S30, M1, M5 and M15 are disabled and the note is shown`() {
        show(AutoLockTimeout.IMMEDIATE, longerDisabled = true)

        assertEnabledSet(setOf(AutoLockTimeout.IMMEDIATE))
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.PAUSED_NOTE).assertExists()
    }

    @Test
    fun `TECH-13 each selection disables exactly the longer options when longerDisabled`() {
        show(AutoLockTimeout.IMMEDIATE, longerDisabled = true)
        AutoLockTimeout.values().forEach { selected ->
            update(selected, longerDisabled = true)

            val expectedEnabled = AutoLockTimeout.values().filter { it.ordinal <= selected.ordinal }.toSet()
            assertEnabledSet(expectedEnabled)
        }
    }

    @Test
    fun `the helper lines appear on IMMEDIATE and 30 seconds only`() {
        show(AutoLockTimeout.M1)

        option(AutoLockTimeout.IMMEDIATE).assert(hasText(text(R.string.applock_timeout_immediately_hint)))
        option(AutoLockTimeout.S30).assert(hasText(text(R.string.applock_timeout_30s_hint)))
        listOf(AutoLockTimeout.M1, AutoLockTimeout.M5, AutoLockTimeout.M15).forEach { timeout ->
            option(timeout)
                .assert(!hasText(text(R.string.applock_timeout_immediately_hint)))
                .assert(!hasText(text(R.string.applock_timeout_30s_hint)))
        }
        composeRule.onAllNodesWithText(text(R.string.applock_timeout_immediately_hint)).assertCountEquals(1)
        composeRule.onAllNodesWithText(text(R.string.applock_timeout_30s_hint)).assertCountEquals(1)
    }

    @Test
    fun `every option row is a radio button`() {
        show(AutoLockTimeout.M1)

        AutoLockTimeout.values().forEach { timeout ->
            option(timeout).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
    }

    @Test
    fun `every option row is at least 48dp tall`() {
        show(AutoLockTimeout.M1)

        AutoLockTimeout.values().forEach { timeout ->
            option(timeout).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun `at font scale 2 the last option stays reachable by scrolling and reports once`() {
        show(AutoLockTimeout.M1, fontScale = 2f)

        option(AutoLockTimeout.M15).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.CANCEL).assertIsDisplayed()
        option(AutoLockTimeout.M15).performClick()

        assertEquals(listOf(AutoLockTimeout.M15), selections)
        assertEquals(0, dismissals)
    }
}
