package com.yourname.expensetracker.ui.screens.addexpense

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * nl-quick-add slice F (D-12 JVM Compose harness): the quick-add section's line holder, Fill gating
 * (INV-17), IME Done = Fill (G2-15), focus clearing after Fill, the 200 cap with counter, and the
 * polite live region. The line leaves the section only as the `onFill` argument.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class QuickAddSectionTest {

    private val composeRule = createComposeRule()

    // testReleaseUnitTest does not merge ui-test-manifest (debugImplementation only), so
    // ComponentActivity must be registered with Robolectric before the compose rule starts.
    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(
            object : ExternalResource() {
                override fun before() {
                    val context = ApplicationProvider.getApplicationContext<Application>()
                    shadowOf(context.packageManager).addActivityIfNotPresent(
                        ComponentName(context, ComponentActivity::class.java)
                    )
                }
            }
        )
        .around(composeRule)

    private val filled = mutableListOf<String>()

    /** Records every clearFocus(force) call; Robolectric's host-view focus is not a reliable oracle. */
    private class FakeFocusManager : FocusManager {
        val clearFocusCalls = mutableListOf<Boolean>()
        override fun clearFocus(force: Boolean) {
            clearFocusCalls += force
        }
        override fun moveFocus(focusDirection: FocusDirection): Boolean = false
    }

    /** Records every hide() call. */
    private class FakeKeyboardController : SoftwareKeyboardController {
        var hideCount = 0
        var showCount = 0
        override fun show() {
            showCount++
        }
        override fun hide() {
            hideCount++
        }
    }

    private val fakeFocusManager = FakeFocusManager()
    private val fakeKeyboard = FakeKeyboardController()

    private fun show(
        summary: List<QuickAddLine> = emptyList(),
        homeCurrencyLoaded: Boolean = true,
        isSaving: Boolean = false
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalFocusManager provides fakeFocusManager,
                LocalSoftwareKeyboardController provides fakeKeyboard
            ) {
                QuickAddSection(
                    summary = summary,
                    homeCurrencyLoaded = homeCurrencyLoaded,
                    isSaving = isSaving,
                    onFill = { filled += it }
                )
            }
        }
    }

    private fun stateDescriptionIs(expected: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)

    @Test
    fun `blank line disables Fill with the type a line first description`() {
        show()

        composeRule.onNodeWithTag(QUICK_ADD_FILL_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(QUICK_ADD_FILL_TAG).assert(stateDescriptionIs("Type a line first"))
    }

    @Test
    fun `typing enables Fill and the button passes the exact line once`() {
        show()

        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).performTextInput("coffee 3.50 yesterday")
        composeRule.onNodeWithTag(QUICK_ADD_FILL_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(QUICK_ADD_FILL_TAG).performClick()

        assertEquals(listOf("coffee 3.50 yesterday"), filled)
    }

    @Test
    fun `IME Done fills with the line and clears focus`() {
        show()

        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).performTextInput("taxi 12")
        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).assertIsFocused()
        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).performImeAction()

        // Intent verified through fakes: Robolectric's host-view focus makes Focused semantics unreliable.
        assertEquals(listOf("taxi 12"), filled)
        assertEquals(listOf(true), fakeFocusManager.clearFocusCalls)
        assertEquals(1, fakeKeyboard.hideCount)
    }

    @Test
    fun `IME Done on a blank line only clears focus`() {
        show()

        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).performClick()
        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).performImeAction()

        assertEquals(emptyList<String>(), filled)
        assertEquals(listOf(true), fakeFocusManager.clearFocusCalls)
        assertEquals(1, fakeKeyboard.hideCount)
    }

    @Test
    fun `currency loading disables Fill and IME Done does not fill`() {
        show(homeCurrencyLoaded = false)

        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).performTextInput("coffee 3.50")
        composeRule.onNodeWithTag(QUICK_ADD_FILL_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(QUICK_ADD_FILL_TAG).assert(stateDescriptionIs("Loading currency settings"))
        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).performImeAction()

        assertEquals(emptyList<String>(), filled)
    }

    @Test
    fun `a save in flight disables Fill`() {
        show(isSaving = true)

        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).performTextInput("coffee 3.50")
        composeRule.onNodeWithTag(QUICK_ADD_FILL_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).performImeAction()

        assertEquals(emptyList<String>(), filled)
    }

    @Test
    fun `counter shows from 180 characters and an edit past 200 is rejected`() {
        show()

        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).performTextInput("a".repeat(179))
        composeRule.onNodeWithText("179 / 200", useUnmergedTree = true).assertDoesNotExist()

        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).performTextInput("bcdef")
        composeRule.onNodeWithText("184 / 200", useUnmergedTree = true).assertExists()

        // 184 + 17 = 201 > 200: the whole edit is rejected, the line stays 184 characters.
        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).performTextInput("x".repeat(17))
        composeRule.onNodeWithText("184 / 200", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(QUICK_ADD_FILL_TAG).performClick()
        assertEquals(1, filled.size)
        assertEquals(184, filled.single().length)
    }

    @Test
    fun `summary is a polite live region`() {
        show(
            summary = listOf(
                QuickAddLine(
                    UiText.StringResource(R.string.quick_add_summary_nothing_new),
                    QuickAddTone.INFO
                )
            )
        )

        composeRule.onNodeWithTag(QUICK_ADD_SUMMARY_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithText("Nothing new was filled.", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `no summary rows while there is nothing to report but the live region is composed`() {
        show()

        // Item 71 #2: the polite live region exists up front so a screen reader registers it; it has no rows.
        composeRule.onNodeWithTag(QUICK_ADD_SUMMARY_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithTag(QUICK_ADD_SUMMARY_TAG, useUnmergedTree = true)
            .onChildren()
            .assertCountEquals(0)
    }

    @Test
    fun `INV-12 the typed line is lost on saved-state restoration and is nowhere else in the semantics tree`() {
        val sentinel = "SENTINEL-LINE-48210"
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            QuickAddSection(
                summary = emptyList(),
                homeCurrencyLoaded = true,
                isSaving = false,
                onFill = { filled += it }
            )
        }

        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).performTextInput(sentinel)
        composeRule.onNodeWithTag(QUICK_ADD_FILL_TAG).assertIsEnabled()
        // Before restoration the sentinel is held by the text field only.
        composeRule.onAllNodes(sentinelOutsideLineField(sentinel), useUnmergedTree = true).assertCountEquals(0)

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(QUICK_ADD_LINE_TAG).assert(
            SemanticsMatcher("line is empty") {
                it.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text == ""
            }
        )
        composeRule.onNodeWithTag(QUICK_ADD_FILL_TAG).assertIsNotEnabled()
        composeRule.onAllNodes(hasText(sentinel, substring = true), useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodes(sentinelOutsideLineField(sentinel), useUnmergedTree = true).assertCountEquals(0)
        assertEquals(emptyList<String>(), filled)
    }

    /** Any node, other than the line field itself, whose text or content description contains [sentinel]. */
    private fun sentinelOutsideLineField(sentinel: String): SemanticsMatcher =
        (hasText(sentinel, substring = true) or hasContentDescription(sentinel, substring = true)) and
            !hasTestTag(QUICK_ADD_LINE_TAG)
}
