package com.yourname.expensetracker.ui.screens.captureskip

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.UiText
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P-01-3: "Apps to skip" screen. The ViewModel is a mock whose state is driven by the test, so the
 * screen is checked on its own: empty states, blocked and candidate rows, one-action-per-row click
 * routing, 48dp touch targets with spoken descriptions, in-flight disabling, load failure, and the
 * message snackbar. Synthetic package names only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1600dp")
class AppsToSkipScreenTest {

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

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val state = MutableStateFlow(AppsToSkipUiState(isLoading = false))
    private val messages = MutableSharedFlow<UiText>(extraBufferCapacity = 1)
    private val viewModel = mockk<AppsToSkipViewModel>(relaxed = true)
    private var backClicks = 0

    private val alpha = SkipAppItem("com.synthetic.alpha", "Alpha Bank")
    private val beta = SkipAppItem("com.synthetic.beta", "Beta Wallet")
    private val unlabelled = SkipAppItem("com.synthetic.unlabelled", "com.synthetic.unlabelled")

    private fun show() {
        every { viewModel.uiState } returns state
        every { viewModel.messages } returns messages
        composeRule.setContent {
            AppsToSkipScreen(onNavigateBack = { backClicks++ }, viewModel = viewModel)
        }
    }

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    // ── Empty and loading states ───────────────────────────────────────────────────

    @Test
    fun `empty lists show both section titles and both empty hints`() {
        show()

        composeRule.onNodeWithText(str(R.string.capture_skip_title)).assertExists()
        composeRule.onNodeWithText(str(R.string.capture_skip_intro)).assertExists()
        composeRule.onNodeWithText(str(R.string.capture_skip_section_skipped)).assertExists()
        composeRule.onNodeWithText(str(R.string.capture_skip_empty_skipped)).assertExists()
        composeRule.onNodeWithText(str(R.string.capture_skip_section_candidates)).assertExists()
        composeRule.onNodeWithText(str(R.string.capture_skip_empty_candidates)).assertExists()
        composeRule.onAllNodesWithText(str(R.string.capture_skip_action_skip)).assertCountEquals(0)
        composeRule.onAllNodesWithText(str(R.string.capture_skip_action_resume)).assertCountEquals(0)
    }

    @Test
    fun `loading state shows neither lists nor hints`() {
        state.value = AppsToSkipUiState(isLoading = true)
        show()

        composeRule.onAllNodesWithText(str(R.string.capture_skip_section_skipped)).assertCountEquals(0)
        composeRule.onAllNodesWithText(str(R.string.capture_skip_empty_skipped)).assertCountEquals(0)
    }

    @Test
    fun `load failure shows the failure text instead of the lists`() {
        state.value = AppsToSkipUiState(isLoading = false, loadFailed = true)
        show()

        composeRule.onNodeWithText(str(R.string.capture_skip_load_failed)).assertExists()
        composeRule.onAllNodesWithText(str(R.string.capture_skip_section_skipped)).assertCountEquals(0)
        composeRule.onAllNodesWithText(str(R.string.capture_skip_empty_candidates)).assertCountEquals(0)
    }

    // ── Rows ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `blocked and candidate rows render with the matching action and hide the empty hints`() {
        state.value = AppsToSkipUiState(isLoading = false, blocked = listOf(alpha), candidates = listOf(beta))
        show()

        composeRule.onNodeWithText("Alpha Bank").assertExists()
        composeRule.onNodeWithText("com.synthetic.alpha").assertExists()
        composeRule.onNodeWithText("Beta Wallet").assertExists()
        composeRule.onNodeWithText("com.synthetic.beta").assertExists()
        composeRule.onAllNodesWithText(str(R.string.capture_skip_action_resume)).assertCountEquals(1)
        composeRule.onAllNodesWithText(str(R.string.capture_skip_action_skip)).assertCountEquals(1)
        composeRule.onAllNodesWithText(str(R.string.capture_skip_empty_skipped)).assertCountEquals(0)
        composeRule.onAllNodesWithText(str(R.string.capture_skip_empty_candidates)).assertCountEquals(0)
    }

    @Test
    fun `a row whose label is the package name shows the name once`() {
        state.value = AppsToSkipUiState(isLoading = false, candidates = listOf(unlabelled))
        show()

        composeRule.onAllNodesWithText("com.synthetic.unlabelled").assertCountEquals(1)
    }

    // ── Click routing ────────────────────────────────────────────────────────────────

    @Test
    fun `tapping Skip on a candidate blocks that package only`() {
        state.value = AppsToSkipUiState(isLoading = false, blocked = listOf(alpha), candidates = listOf(beta))
        show()

        composeRule.onNodeWithContentDescription(str(R.string.capture_skip_cd_skip_format, "Beta Wallet")).performClick()

        verify(exactly = 1) { viewModel.block("com.synthetic.beta") }
        verify(exactly = 0) { viewModel.unblock(any()) }
    }

    @Test
    fun `tapping Capture again on a blocked app unblocks that package only`() {
        state.value = AppsToSkipUiState(isLoading = false, blocked = listOf(alpha), candidates = listOf(beta))
        show()

        composeRule.onNodeWithContentDescription(str(R.string.capture_skip_cd_resume_format, "Alpha Bank")).performClick()

        verify(exactly = 1) { viewModel.unblock("com.synthetic.alpha") }
        verify(exactly = 0) { viewModel.block(any()) }
    }

    @Test
    fun `the back button invokes onNavigateBack`() {
        show()

        composeRule.onNodeWithContentDescription(str(R.string.cd_back)).performClick()

        org.junit.Assert.assertEquals(1, backClicks)
    }

    // ── Accessibility ────────────────────────────────────────────────────────────────

    @Test
    fun `row actions are at least 48dp tall and carry per-app content descriptions`() {
        state.value = AppsToSkipUiState(isLoading = false, blocked = listOf(alpha), candidates = listOf(beta))
        show()

        composeRule.onNodeWithContentDescription(str(R.string.capture_skip_cd_resume_format, "Alpha Bank"))
            .assertExists()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription(str(R.string.capture_skip_cd_skip_format, "Beta Wallet"))
            .assertExists()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `the action of a row with a running write is disabled while other rows stay enabled`() {
        state.value = AppsToSkipUiState(
            isLoading = false,
            blocked = listOf(alpha),
            candidates = listOf(beta),
            inFlight = setOf("com.synthetic.beta")
        )
        show()

        composeRule.onNodeWithContentDescription(str(R.string.capture_skip_cd_skip_format, "Beta Wallet"))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(str(R.string.capture_skip_cd_resume_format, "Alpha Bank"))
            .assertIsEnabled()
    }

    // ── Messages ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a ViewModel message is shown to the user`() {
        show()
        composeRule.waitForIdle()

        messages.tryEmit(UiText.StringResource(R.string.ui_write_blocked_maintenance))

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(str(R.string.ui_write_blocked_maintenance))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
