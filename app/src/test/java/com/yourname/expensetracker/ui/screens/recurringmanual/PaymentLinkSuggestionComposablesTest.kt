package com.yourname.expensetracker.ui.screens.recurringmanual

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.repository.ManualRecurringExpenseRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkConfirmResult
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkDeclineResult
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkFailureCodes
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkSuggestion
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkSuggestions
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkUnavailableReason
import com.yourname.expensetracker.domain.recurring.usecase.MarkRecurringBillPaidUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.applock.AppLockComposeTestBase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.math.abs

/**
 * FC-PAID-UNLINKED S3 (ux-flows sections 2.1, 3.1, 6 and 9) on the Recurring Expenses screen: the block is
 * rendered only for Content, the unavailable line only for Unavailable, the buttons are disabled while the
 * rule is in flight, and an unavailable state never shows a zero or a blank amount.
 *
 * The first group renders [PaymentLinkSuggestionBlock] and [PaymentLinkSuggestionsUnavailableLine] directly.
 * The second group renders the real [ManualRecurringExpenseScreen] on the real [ManualRecurringExpenseViewModel]
 * (repository and use case are mocks).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], qualifiers = "w412dp-h2000dp")
class PaymentLinkSuggestionComposablesTest : AppLockComposeTestBase() {

    private val originalLocale: Locale = Locale.getDefault()
    private val fixedNow = 1_700_000_000_000L
    private val createdViewModels = mutableListOf<ManualRecurringExpenseViewModel>()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
    }

    // ---- block and line fixtures --------------------------------------------------------------

    private fun suggestionUi(
        occurrenceId: Long = 100L,
        ruleId: Long = RULE_ID,
        expenseId: Long = 900L,
        paidAmount: Double = 400.0
    ) = PaymentLinkSuggestionUi(
        occurrenceId = occurrenceId,
        ruleId = ruleId,
        expenseId = expenseId,
        paidAmount = paidAmount,
        paidCurrency = "EUR",
        expenseDate = fixedNow - DAY_MS,
        dueDate = fixedNow + 2 * DAY_MS
    )

    private fun showBlocks(
        suggestions: List<PaymentLinkSuggestionUi> = listOf(suggestionUi()),
        enabled: Boolean = true,
        fontScale: Float = 1.0f,
        onLink: (PaymentLinkSuggestionUi) -> Unit = {},
        onDecline: (PaymentLinkSuggestionUi) -> Unit = {}
    ) {
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            val density = remember(baseDensity, fontScale) { Density(baseDensity.density, fontScale) }
            CompositionLocalProvider(LocalDensity provides density) {
                MaterialTheme {
                    androidx.compose.foundation.layout.Column {
                        suggestions.forEach { suggestion ->
                            PaymentLinkSuggestionBlock(
                                merchant = "Rent",
                                suggestion = suggestion,
                                enabled = enabled,
                                onLink = { onLink(suggestion) },
                                onDecline = { onDecline(suggestion) }
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private val linkLabel get() = text(R.string.recurring_link_action)
    private val declineLabel get() = text(R.string.recurring_link_decline)

    private fun allTexts(): List<String> =
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }

    // ---- block ---------------------------------------------------------------------------------

    @Test
    fun `block shows the paid amount with its currency the due line and both actions`() {
        showBlocks()

        composeRule.onNode(hasText("Paid already?", substring = true) and hasText("400", substring = true)).assertExists()
        composeRule.onNode(hasText("EUR", substring = true).or(hasText("€", substring = true))).assertExists()
        composeRule.onNode(hasText("Due ", substring = true)).assertExists()
        composeRule.onNodeWithText(linkLabel).assertExists()
        composeRule.onNodeWithText(declineLabel).assertExists()
    }

    @Test
    fun `block summary is one merged node described with the merchant`() {
        showBlocks()

        composeRule.onAllNodes(hasContentDescription("Possible payment for Rent:", substring = true))
            .assertCountEquals(1)
    }

    @Test
    fun `Link and Not this bill invoke their own callbacks with the suggestion`() {
        val linked = mutableListOf<PaymentLinkSuggestionUi>()
        val declined = mutableListOf<PaymentLinkSuggestionUi>()
        val suggestion = suggestionUi()
        showBlocks(listOf(suggestion), onLink = { linked += it }, onDecline = { declined += it })

        composeRule.onNodeWithText(linkLabel).performClick()
        assertEquals(listOf(suggestion), linked)
        assertTrue(declined.isEmpty())

        composeRule.onNodeWithText(declineLabel).performClick()
        assertEquals(listOf(suggestion), declined)
        assertEquals(1, linked.size)
    }

    @Test
    fun `buttons are disabled and inert while the rule is in flight`() {
        var calls = 0
        showBlocks(enabled = false, onLink = { calls++ }, onDecline = { calls++ })

        composeRule.onNodeWithText(linkLabel).assertIsNotEnabled()
        composeRule.onNodeWithText(declineLabel).assertIsNotEnabled()
        composeRule.onNodeWithText(linkLabel).performClick()
        composeRule.onNodeWithText(declineLabel).performClick()
        assertEquals(0, calls)
    }

    @Test
    fun `buttons are enabled when nothing is in flight`() {
        showBlocks(enabled = true)

        composeRule.onNodeWithText(linkLabel).assertIsEnabled()
        composeRule.onNodeWithText(declineLabel).assertIsEnabled()
    }

    @Test
    fun `both buttons are at least 48dp high`() {
        showBlocks()

        composeRule.onNodeWithText(linkLabel).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(declineLabel).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `buttons sit side by side at font scale 1`() {
        showBlocks(fontScale = 1.0f)
        val link = composeRule.onNodeWithText(linkLabel).fetchSemanticsNode().boundsInRoot
        val decline = composeRule.onNodeWithText(declineLabel).fetchSemanticsNode().boundsInRoot
        assertTrue("same row: link=$link decline=$decline", abs(link.top - decline.top) < 1f)
        assertTrue("decline right of link: link=$link decline=$decline", decline.left >= link.right)
    }

    @Test
    fun `buttons stack full width at font scale 1_3`() {
        showBlocks(fontScale = 1.3f)

        val link = composeRule.onNodeWithText(linkLabel).fetchSemanticsNode().boundsInRoot
        val decline = composeRule.onNodeWithText(declineLabel).fetchSemanticsNode().boundsInRoot
        assertTrue("decline below link: link=$link decline=$decline", decline.top >= link.bottom)
        assertTrue("same width: link=$link decline=$decline", abs(link.width - decline.width) < 1f)
        assertTrue("same left edge: link=$link decline=$decline", abs(link.left - decline.left) < 1f)
    }

    @Test
    fun `each button description names the merchant and the amount`() {
        showBlocks()

        val linkButton = hasClickAction() and hasContentDescription("Rent", substring = true) and
            hasContentDescription("400", substring = true) and hasText(linkLabel)
        val declineButton = hasClickAction() and hasContentDescription("Rent", substring = true) and
            hasContentDescription("400", substring = true) and hasText(declineLabel)
        composeRule.onNode(linkButton).assertExists()
        composeRule.onNode(declineButton).assertExists()
    }

    @Test
    fun `two suggestions render two stacked blocks that TalkBack can tell apart`() {
        showBlocks(
            listOf(
                suggestionUi(occurrenceId = 100L, expenseId = 900L, paidAmount = 400.0),
                suggestionUi(occurrenceId = 101L, expenseId = 901L, paidAmount = 123.0)
            )
        )

        composeRule.onAllNodesWithText(linkLabel).assertCountEquals(2)
        composeRule.onAllNodesWithText(declineLabel).assertCountEquals(2)
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("400", substring = true)).assertCountEquals(2)
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("123", substring = true)).assertCountEquals(2)
    }

    // ---- unavailable line ----------------------------------------------------------------------

    @Test
    fun `unavailable line shows only the unavailable message and no zero or blank amount`() {
        composeRule.setContent { MaterialTheme { PaymentLinkSuggestionsUnavailableLine() } }
        composeRule.waitForIdle()

        val texts = allTexts()
        assertEquals(listOf(text(R.string.recurring_link_suggestions_unavailable)), texts)
        assertTrue(texts.none { it.isBlank() })
        assertTrue(texts.none { Regex("""^\W*0([.,]0+)?\W*$""").matches(it) })
        composeRule.onAllNodesWithText(linkLabel).assertCountEquals(0)
        composeRule.onAllNodesWithText(declineLabel).assertCountEquals(0)
    }

    // ---- full screen on the real view model -----------------------------------------------------

    private fun rule(id: Long, merchant: String, active: Boolean = true, frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY) =
        ManualRecurringExpense(
            id = id,
            merchant = merchant,
            amount = 400.0,
            currency = "EUR",
            frequency = frequency,
            nextDate = fixedNow + 2 * DAY_MS,
            note = null,
            isSubscription = false,
            isActive = active,
            createdAt = fixedNow
        )

    private fun domainSuggestion(ruleId: Long = RULE_ID, occurrenceId: Long = 100L, expenseId: Long = 900L) =
        PaymentLinkSuggestion(
            occurrenceId = occurrenceId,
            ruleId = ruleId,
            occurrenceKey = "rule:$ruleId:$occurrenceId",
            dueDate = fixedNow + 2 * DAY_MS,
            expectedAmount = 400.0,
            expectedCurrency = "EUR",
            expenseId = expenseId,
            expenseDate = fixedNow - DAY_MS,
            paidAmount = 400.0,
            paidCurrency = "EUR",
            dayDelta = -3,
            alternatives = emptyList()
        )

    private val useCase = mockk<MarkRecurringBillPaidUseCase>()
    private var rules: List<ManualRecurringExpense> = listOf(rule(RULE_ID, "Rent"))
    private var suggestionsResult: PaymentLinkSuggestions = PaymentLinkSuggestions.Available(emptyList())

    /** When set, the suggestion read parks on it (the screen stays in Loading) until it completes. */
    private var suggestionsGate: CompletableDeferred<PaymentLinkSuggestions>? = null

    private fun showScreen() {
        val repository = mockk<ManualRecurringExpenseRepository>(relaxed = true)
        coEvery { repository.getAll() } answers { rules }
        coEvery { useCase.findSuggestions() } coAnswers { suggestionsGate?.await() ?: suggestionsResult }
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns fixedNow
        val currencyRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencyRepo.homeCurrency() } returns flowOf("EUR")
        coEvery { currencyRepo.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val viewModel = ManualRecurringExpenseViewModel(
            repository,
            timeProvider,
            currencySettingsRepository = currencyRepo,
            markRecurringBillPaid = useCase
        )
        createdViewModels += viewModel
        composeRule.setContent {
            MaterialTheme {
                ManualRecurringExpenseScreen(onNavigateBack = {}, viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()
    }

    private val blockMatcher get() = hasText("Paid already?", substring = true)
    private val unavailableMatcher get() = hasText(text(R.string.recurring_link_suggestions_unavailable))

    @Test
    fun `screen renders the block under the rule card only for Content`() {
        suggestionsResult = PaymentLinkSuggestions.Available(listOf(domainSuggestion()))

        showScreen()

        composeRule.onNodeWithText("Rent").assertExists()
        composeRule.onAllNodes(blockMatcher).assertCountEquals(1)
        composeRule.onNodeWithText(linkLabel).assertExists()
        composeRule.onNodeWithText(declineLabel).assertExists()
        composeRule.onNodeWithText(text(R.string.recurring_mark_paid)).assertExists()
        composeRule.onAllNodes(unavailableMatcher).assertCountEquals(0)
    }

    @Test
    fun `screen renders no block while the first read is Loading and the list stays usable`() {
        val gate = CompletableDeferred<PaymentLinkSuggestions>()
        suggestionsGate = gate

        showScreen()

        composeRule.onNodeWithText("Rent").assertExists()
        composeRule.onAllNodes(blockMatcher).assertCountEquals(0)
        composeRule.onAllNodes(unavailableMatcher).assertCountEquals(0)
        composeRule.onNodeWithText(text(R.string.recurring_mark_paid)).assertIsEnabled()

        // When the read arrives the block appears without any other change.
        composeRule.runOnIdle { gate.complete(PaymentLinkSuggestions.Available(listOf(domainSuggestion()))) }
        composeRule.waitForIdle()
        composeRule.onAllNodes(blockMatcher).assertCountEquals(1)
    }

    @Test
    fun `screen renders nothing at all for None`() {
        suggestionsResult = PaymentLinkSuggestions.Available(emptyList())

        showScreen()

        composeRule.onNodeWithText("Rent").assertExists()
        composeRule.onAllNodes(blockMatcher).assertCountEquals(0)
        composeRule.onAllNodes(unavailableMatcher).assertCountEquals(0)
        composeRule.onAllNodesWithText(linkLabel).assertCountEquals(0)
        // No "no suggestions" wording of any kind.
        composeRule.onAllNodes(hasText("suggestion", substring = true, ignoreCase = true)).assertCountEquals(0)
    }

    private fun assertUnavailableOnlyRendering() {
        composeRule.onAllNodes(unavailableMatcher).assertCountEquals(1)
        composeRule.onAllNodes(blockMatcher).assertCountEquals(0)
        composeRule.onAllNodesWithText(linkLabel).assertCountEquals(0)
        composeRule.onAllNodesWithText(declineLabel).assertCountEquals(0)
        // The rule list is still there and usable.
        composeRule.onNodeWithText("Rent").assertExists()
        composeRule.onNodeWithText(text(R.string.recurring_mark_paid)).assertExists()
        // The unavailable state is neither a zero nor a blank amount text.
        val texts = allTexts()
        assertTrue(texts.none { it.isBlank() })
        assertTrue(texts.none { it.contains("suggestion", ignoreCase = true) && it != text(R.string.recurring_link_suggestions_unavailable) })
    }

    @Test
    fun `screen renders the unavailable line for Unavailable READ_BLOCKED and no block`() {
        suggestionsResult = PaymentLinkSuggestions.Unavailable(PaymentLinkUnavailableReason.READ_BLOCKED)

        showScreen()

        assertUnavailableOnlyRendering()
    }

    @Test
    fun `screen renders the unavailable line for Unavailable READ_FAILED and no block`() {
        suggestionsResult = PaymentLinkSuggestions.Unavailable(PaymentLinkUnavailableReason.READ_FAILED)

        showScreen()

        assertUnavailableOnlyRendering()
    }

    @Test
    fun `screen shows no block for an irregular rule even when Content names it`() {
        rules = listOf(rule(RULE_ID, "Rent"), rule(8L, "Odd jobs", frequency = RecurrenceFrequency.IRREGULAR))
        suggestionsResult = PaymentLinkSuggestions.Available(listOf(domainSuggestion(ruleId = 8L, occurrenceId = 180L, expenseId = 980L)))

        showScreen()

        composeRule.onAllNodes(blockMatcher).assertCountEquals(0)
    }

    @Test
    fun `screen shows no block for an inactive rule even when Content names it`() {
        rules = listOf(rule(RULE_ID, "Rent", active = false))
        suggestionsResult = PaymentLinkSuggestions.Available(listOf(domainSuggestion()))

        showScreen()

        composeRule.onAllNodes(blockMatcher).assertCountEquals(0)
    }

    @Test
    fun `screen disables Link Not this bill and Mark as Paid while the rule is in flight`() {
        suggestionsResult = PaymentLinkSuggestions.Available(listOf(domainSuggestion()))
        val gate = CompletableDeferred<PaymentLinkConfirmResult>()
        coEvery { useCase.linkExistingPayment(RULE_ID, 100L, 900L) } coAnswers { gate.await() }
        showScreen()
        composeRule.onNodeWithText(linkLabel).assertIsEnabled()

        composeRule.onNodeWithText(linkLabel).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(linkLabel).assertIsNotEnabled()
        composeRule.onNodeWithText(declineLabel).assertIsNotEnabled()
        composeRule.onNodeWithText(text(R.string.recurring_mark_paid)).assertIsNotEnabled()

        composeRule.runOnIdle { gate.complete(PaymentLinkConfirmResult.Failed(PaymentLinkFailureCodes.INTERNAL)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(linkLabel).assertIsEnabled()
        composeRule.onNodeWithText(declineLabel).assertIsEnabled()
        composeRule.onNodeWithText(text(R.string.recurring_mark_paid)).assertIsEnabled()
        coVerify(exactly = 1) { useCase.linkExistingPayment(RULE_ID, 100L, 900L) }
    }

    @Test
    fun `screen shows the maintenance message for Failed WRITE_BLOCKED and keeps the block`() {
        suggestionsResult = PaymentLinkSuggestions.Available(listOf(domainSuggestion()))
        coEvery { useCase.linkExistingPayment(RULE_ID, 100L, 900L) } returns
            PaymentLinkConfirmResult.Failed(PaymentLinkFailureCodes.WRITE_BLOCKED)
        showScreen()

        // The snackbar auto-dismisses on the test clock, so the clock is advanced by hand.
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText(linkLabel).performClick()
        composeRule.mainClock.advanceTimeBy(600)

        composeRule.onNodeWithText(text(R.string.ui_write_blocked_maintenance)).assertExists()
        composeRule.onAllNodes(blockMatcher).assertCountEquals(1)
        composeRule.onNodeWithText(linkLabel).assertIsEnabled()
    }

    @Test
    fun `screen shows the linked message and removes the block after a successful Link`() {
        suggestionsResult = PaymentLinkSuggestions.Available(listOf(domainSuggestion()))
        coEvery { useCase.linkExistingPayment(RULE_ID, 100L, 900L) } coAnswers {
            suggestionsResult = PaymentLinkSuggestions.Available(emptyList())
            PaymentLinkConfirmResult.Linked
        }
        showScreen()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText(linkLabel).performClick()
        composeRule.mainClock.advanceTimeBy(600)

        composeRule.onNodeWithText(text(R.string.recurring_link_done_format, "Rent")).assertExists()
        composeRule.onAllNodes(blockMatcher).assertCountEquals(0)
        composeRule.onNodeWithText(text(R.string.recurring_mark_paid)).assertIsEnabled()
    }

    @Test
    fun `screen shows the not this bill message and removes the block after a decline`() {
        suggestionsResult = PaymentLinkSuggestions.Available(listOf(domainSuggestion()))
        coEvery { useCase.declinePayment(RULE_ID, 100L, 900L) } coAnswers {
            suggestionsResult = PaymentLinkSuggestions.Available(emptyList())
            PaymentLinkDeclineResult.Declined
        }
        showScreen()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText(declineLabel).performClick()
        composeRule.mainClock.advanceTimeBy(600)

        composeRule.onNodeWithText(text(R.string.recurring_link_declined)).assertExists()
        composeRule.onAllNodes(blockMatcher).assertCountEquals(0)
        assertFalse(allTexts().any { it.contains("Paid already?") })
    }

    private companion object {
        const val RULE_ID = 5L
        const val DAY_MS = 86_400_000L
    }
}
