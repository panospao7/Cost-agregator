package com.yourname.expensetracker.ui.screens.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.applock.AppLockComposeTestBase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * FC-PAID-UNLINKED S3 (ux-flows sections 2.2, 6 and 9) on Home: the hint card renders only for
 * [PaymentLinkHintUiState.Ready], uses the plural wording (one vs other), is one clickable node of at least
 * 48dp, and a tap opens the paid-bill review (the Recurring Expenses screen), never the detected-patterns
 * callback `onNavigateToRecurring`.
 *
 * [HomeScreen] is stateless apart from the view model it calls on user actions; none is triggered here, so a
 * relaxed mock with the one flow the screen collects is enough.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], qualifiers = "w412dp-h2000dp")
class PaymentLinkHintComposablesTest : AppLockComposeTestBase() {

    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    private var reviewPaidBillsCalls = 0
    private var recurringCalls = 0
    private var reviewCalls = 0

    private fun showHomeScreen(hint: PaymentLinkHintUiState) {
        val viewModel = mockk<HomeViewModel>(relaxed = true)
        every { viewModel.writeBlockedMessages } returns MutableSharedFlow<UiText>()
        composeRule.setContent {
            MaterialTheme {
                HomeScreen(
                    state = DashboardState(isLoading = false),
                    categories = emptyList(),
                    recommendations = emptyList(),
                    homeCurrency = "EUR",
                    homeCurrencyState = HomeCurrencyUiState.Ready("EUR"),
                    viewModel = viewModel,
                    onNavigateToReview = { reviewCalls++ },
                    onNavigateToRecurring = { recurringCalls++ },
                    paymentLinkHint = hint,
                    onReviewPaidBills = { reviewPaidBillsCalls++ }
                )
            }
        }
        composeRule.waitForIdle()
    }

    private val hintTextMatcher get() = hasText("may already be paid", substring = true)

    // ---- Home screen: only Ready renders -------------------------------------------------------

    @Test
    fun `Home shows no hint for Loading`() {
        showHomeScreen(PaymentLinkHintUiState.Loading)

        composeRule.onAllNodes(hintTextMatcher).assertCountEquals(0)
    }

    @Test
    fun `Home shows no hint for None`() {
        showHomeScreen(PaymentLinkHintUiState.None)

        composeRule.onAllNodes(hintTextMatcher).assertCountEquals(0)
    }

    @Test
    fun `Home shows no hint and no zero bills text for Unavailable`() {
        showHomeScreen(PaymentLinkHintUiState.Unavailable)

        composeRule.onAllNodes(hintTextMatcher).assertCountEquals(0)
        composeRule.onAllNodes(hasText("0 bills", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasText("unavailable", substring = true, ignoreCase = true)).assertCountEquals(0)
    }

    @Test
    fun `Home shows the singular hint for Ready one`() {
        showHomeScreen(PaymentLinkHintUiState.Ready(1))

        composeRule.onNodeWithText("1 bill may already be paid.").assertExists()
        composeRule.onAllNodes(hintTextMatcher).assertCountEquals(1)
    }

    @Test
    fun `Home shows the plural hint for Ready two`() {
        showHomeScreen(PaymentLinkHintUiState.Ready(2))

        composeRule.onNodeWithText("2 bills may already be paid.").assertExists()
    }

    @Test
    fun `Home hint tap opens the paid bill review and not the detected patterns callback`() {
        showHomeScreen(PaymentLinkHintUiState.Ready(3))

        composeRule.onNodeWithText("3 bills may already be paid.").performClick()

        assertEquals(1, reviewPaidBillsCalls)
        assertEquals(0, recurringCalls)
        assertEquals(0, reviewCalls)
    }

    // ---- hint card in isolation ----------------------------------------------------------------

    private fun showCard(count: Int, onReview: () -> Unit = {}) {
        composeRule.setContent { MaterialTheme { PaymentLinkHintCard(count = count, onReview = onReview) } }
        composeRule.waitForIdle()
    }

    @Test
    fun `card for 1 uses the one quantity`() {
        showCard(count = 1)
        composeRule.onNodeWithText("1 bill may already be paid.").assertExists()
    }

    @Test
    fun `card for 11 uses the other quantity`() {
        showCard(count = 11)
        composeRule.onNodeWithText("11 bills may already be paid.").assertExists()
    }

    @Test
    fun `card is one clickable node at least 48dp high and a tap calls onReview once`() {
        var calls = 0
        showCard(count = 2, onReview = { calls++ })

        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
        composeRule.onNodeWithText("2 bills may already be paid.").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("2 bills may already be paid.").performClick()

        assertEquals(1, calls)
    }

    @Test
    fun `card click label is the review description and carries no money or merchant text`() {
        showCard(count = 2)

        val expectedLabel = text(R.string.home_paid_bill_hint_review_cd)
        val labelled = SemanticsMatcher("OnClick label is the review description") { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.label == expectedLabel
        }
        composeRule.onAllNodes(labelled).assertCountEquals(1)

        val texts = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
        assertTrue("only the count text is exposed: $texts", texts == listOf("2 bills may already be paid."))
        composeRule.onAllNodesWithText("Review").assertCountEquals(0)
    }
}
