package com.yourname.expensetracker.ui.screens.transactions

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.util.CurrencyFormatter
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

/**
 * Refund netting S4 (ux-flows 2, 3 and 9): Compose tests of the new composables in TransactionsRefundUi.kt and the
 * two dialogs that host them (ChangeTypeDialog is public; the private delete dialog renders RefundDeleteNote, tested
 * directly). Synthetic data only. Expected texts come from string resources; amounts are formatted by the shared
 * formatter so each shows its own currency.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp")
class TransactionsRefundUiTest {

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

    private fun s(@StringRes id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private fun m(amount: Double, currency: String = "EUR"): String = CurrencyFormatter.formatMoney(amount, currency)

    private fun eur(amount: Double) = MoneyAmount(amount, CurrencyCode.EUR)

    private fun shows(text: String) {
        composeRule.onAllNodesWithText(text, useUnmergedTree = true)[0].assertExists()
    }

    private fun absent(text: String) {
        composeRule.onAllNodesWithText(text, useUnmergedTree = true).assertCountEquals(0)
    }

    private fun purchase(refunded: Double = 0.0, amount: Double = 100.0) = Expense(
        id = 2L, amount = amount, currency = "EUR", merchant = "Synthetic Shop", date = 1_000L,
        transactionType = TransactionType.PURCHASE, refundNettedAmount = refunded
    )

    private fun deposit(refunded: Double = 0.0, amount: Double = 40.0, exclusion: String? = null) = Expense(
        id = 1L, amount = amount, currency = "EUR", merchant = "Synthetic Refund", date = 2_000L,
        transactionType = TransactionType.DEPOSIT, refundNettedAmount = refunded, spendingExclusionReason = exclusion
    )

    // ---- notice ---------------------------------------------------------------------------------

    @Test
    fun `likely refund notice shows the text and the Apply to purchase button`() {
        var applied = 0
        composeRule.setContent { RefundLikelyNotice(onApply = { applied++ }) }
        shows(s(R.string.transactions_refund_likelihood_notice))
        composeRule.onNodeWithText("Apply to purchase").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, applied) }
    }

    @Test
    fun `likely refund notice button is disabled while the row is busy`() {
        var applied = 0
        composeRule.setContent { RefundLikelyNotice(onApply = { applied++ }, enabled = false) }
        composeRule.onNodeWithText("Apply to purchase").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, applied) }
    }

    // ---- chips ----------------------------------------------------------------------------------

    @Test
    fun `linked purchase shows Refunded and Net lines in its own currency`() {
        composeRule.setContent { RefundRowChips(purchase(refunded = 40.0)) }
        shows(s(R.string.refund_chip_purchase_format, m(40.0)))
        shows(s(R.string.refund_chip_net_format, m(60.0)))
    }

    @Test
    fun `fully refunded purchase shows Net zero and not blank`() {
        composeRule.setContent { RefundRowChips(purchase(refunded = 100.0)) }
        shows(s(R.string.refund_chip_net_format, m(0.0)))
    }

    @Test
    fun `over-refunded purchase clamps Net at zero`() {
        composeRule.setContent { RefundRowChips(purchase(refunded = 120.0)) }
        shows(s(R.string.refund_chip_net_format, m(0.0)))
    }

    @Test
    fun `unlinked purchase shows no refund chip`() {
        composeRule.setContent { RefundRowChips(purchase(refunded = 0.0)) }
        absent(s(R.string.refund_chip_purchase_format, m(0.0)))
        composeRule.onAllNodesWithText("Refunded", substring = true, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Net", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `linked deposit with unused part shows Applied and still counts as income`() {
        composeRule.setContent { RefundRowChips(deposit(refunded = 30.0, amount = 40.0)) }
        shows(s(R.string.refund_chip_deposit_format, m(30.0)))
        shows(s(R.string.refund_chip_deposit_left_format, m(10.0)))
    }

    @Test
    fun `fully applied deposit shows Applied and no still counts as income line`() {
        composeRule.setContent { RefundRowChips(deposit(refunded = 40.0, amount = 40.0)) }
        shows(s(R.string.refund_chip_deposit_format, m(40.0)))
        composeRule.onAllNodesWithText("still counts as income", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `unlinked deposit shows no chip`() {
        composeRule.setContent { RefundRowChips(deposit(refunded = 0.0)) }
        composeRule.onAllNodesWithText("Applied to a purchase", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    // ---- overflow items -------------------------------------------------------------------------

    private fun showMenu(expense: Expense, busy: Boolean = false, onApply: () -> Unit = {}, onDetails: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        composeRule.setContent {
            Column { RefundMenuItems(expense = expense, busy = busy, onApply = onApply, onDetails = onDetails, onDismiss = onDismiss) }
        }
    }

    @Test
    fun `unlinked eligible deposit offers This is a refund of and no details`() {
        showMenu(deposit())
        composeRule.onNodeWithText("This is a refund of...").assertExists()
        composeRule.onAllNodesWithText("Refund details").assertCountEquals(0)
    }

    @Test
    fun `linked deposit offers details only`() {
        showMenu(deposit(refunded = 40.0))
        composeRule.onAllNodesWithText("This is a refund of...").assertCountEquals(0)
        composeRule.onNodeWithText("Refund details").assertExists()
    }

    @Test
    fun `installment-flagged deposit does not offer This is a refund of`() {
        showMenu(deposit(exclusion = "INSTALLMENT_PURCHASE_RECORD"))
        composeRule.onAllNodesWithText("This is a refund of...").assertCountEquals(0)
        composeRule.onAllNodesWithText("Refund details").assertCountEquals(0)
    }

    @Test
    fun `unlinked purchase offers neither item`() {
        showMenu(purchase())
        composeRule.onAllNodesWithText("This is a refund of...").assertCountEquals(0)
        composeRule.onAllNodesWithText("Refund details").assertCountEquals(0)
    }

    @Test
    fun `linked purchase offers details only`() {
        showMenu(purchase(refunded = 40.0))
        composeRule.onAllNodesWithText("This is a refund of...").assertCountEquals(0)
        composeRule.onNodeWithText("Refund details").assertExists()
    }

    @Test
    fun `withdrawal rows offer neither item`() {
        showMenu(deposit().copy(transactionType = TransactionType.WITHDRAWAL))
        composeRule.onAllNodesWithText("This is a refund of...").assertCountEquals(0)
        composeRule.onAllNodesWithText("Refund details").assertCountEquals(0)
    }

    @Test
    fun `transfer rows offer neither item`() {
        showMenu(deposit().copy(transactionType = TransactionType.TRANSFER))
        composeRule.onAllNodesWithText("This is a refund of...").assertCountEquals(0)
        composeRule.onAllNodesWithText("Refund details").assertCountEquals(0)
    }

    @Test
    fun `menu item click dismisses the menu then opens the picker`() {
        var applied = 0
        var dismissed = 0
        showMenu(deposit(), onApply = { applied++ }, onDismiss = { dismissed++ })
        composeRule.onNodeWithText("This is a refund of...").performClick()
        composeRule.runOnIdle {
            assertEquals(1, applied)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun `menu items are disabled while the row is busy`() {
        showMenu(deposit(), busy = true)
        composeRule.onNodeWithText("This is a refund of...").assertIsNotEnabled()
    }

    // ---- picker sheet ---------------------------------------------------------------------------

    private val depositRef = RefundDepositRef(1L, eur(40.0), 2_000L)

    private fun row(id: Long, merchant: String, amount: Double = 100.0, remaining: Double = amount) =
        RefundCandidateRowUi(id, merchant, 1_000L, "Groceries", eur(amount), eur(remaining))

    private fun showPicker(state: RefundPickerUiState, onSelect: (RefundCandidateRowUi) -> Unit = {}, onRetry: () -> Unit = {}) {
        composeRule.setContent { RefundPickerSheet(state = state, onSelect = onSelect, onRetry = onRetry, onClose = {}) }
        composeRule.waitForIdle()
    }

    @Test
    fun `picker Idle renders nothing`() {
        showPicker(RefundPickerUiState.Idle)
        absent(s(R.string.refund_picker_title))
    }

    @Test
    fun `picker Loading shows only the title and progress not rows or messages`() {
        showPicker(RefundPickerUiState.Loading(depositRef))
        shows(s(R.string.refund_picker_title))
        absent(s(R.string.refund_picker_unavailable))
        absent("Retry")
    }

    @Test
    fun `picker None shows the currency and window message`() {
        showPicker(RefundPickerUiState.None(depositRef, "EUR", 90))
        shows(s(R.string.refund_picker_none_format, "EUR", 90))
        absent(s(R.string.refund_picker_unavailable))
    }

    @Test
    fun `picker Unavailable shows the message and Retry and never the None text`() {
        var retried = 0
        showPicker(RefundPickerUiState.Unavailable(depositRef), onRetry = { retried++ })
        shows(s(R.string.refund_picker_unavailable))
        composeRule.onAllNodes(hasClickAction() and hasText("Retry"))[0].performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(1, retried) }
        composeRule.onAllNodesWithText("No matching purchases found", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `picker Content lists rows in the given order and marks only partly refunded ones`() {
        val first = row(2L, "Synthetic Alpha", amount = 100.0, remaining = 70.0)
        val second = row(3L, "Synthetic Beta", amount = 55.0)
        showPicker(RefundPickerUiState.Content(depositRef, listOf(first, second)))
        val alphaY = composeRule.onAllNodesWithText("Synthetic Alpha", useUnmergedTree = true)[0]
            .fetchSemanticsNode().positionInRoot.y
        val betaY = composeRule.onAllNodesWithText("Synthetic Beta", useUnmergedTree = true)[0]
            .fetchSemanticsNode().positionInRoot.y
        assertTrue("rows keep the given order", alphaY < betaY)
        shows(s(R.string.refund_candidate_remaining_format, m(70.0)))
        composeRule.onAllNodesWithText("not yet refunded", substring = true, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `tapping a picker row selects that candidate`() {
        val selected = mutableListOf<RefundCandidateRowUi>()
        val only = row(2L, "Synthetic Alpha")
        showPicker(RefundPickerUiState.Content(depositRef, listOf(only)), onSelect = { selected += it })
        composeRule.onAllNodes(hasClickAction() and hasText("Synthetic Alpha", substring = true))[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(listOf(only), selected) }
    }

    // ---- confirm dialog -------------------------------------------------------------------------

    private fun confirmState(applied: Double = 40.0, netAfter: Double = 60.0, partly: Boolean = false, submitting: Boolean = false) =
        RefundConfirmUiState(
            deposit = depositRef,
            candidate = row(2L, "Synthetic Shop"),
            applied = eur(applied),
            netAfter = eur(netAfter),
            depositPartlyUsed = partly,
            submitting = submitting
        )

    private fun showConfirm(state: RefundConfirmUiState, onConfirm: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        composeRule.setContent { RefundConfirmDialog(state = state, onConfirm = onConfirm, onDismiss = onDismiss) }
        composeRule.waitForIdle()
    }

    @Test
    fun `confirm dialog shows the preview figures and the Apply action`() {
        var confirmed = 0
        showConfirm(confirmState(), onConfirm = { confirmed++ })
        shows(s(R.string.refund_confirm_title))
        shows(s(R.string.refund_label_purchase))
        shows(s(R.string.refund_label_refunded))
        shows(s(R.string.refund_label_net))
        shows(m(100.0)) // gross
        shows(m(40.0))  // refunded
        shows(m(60.0))  // net
        composeRule.onNodeWithText(s(R.string.refund_confirm_action)).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test
    fun `confirm dialog shows the partial line only when the deposit is partly used`() {
        showConfirm(confirmState(applied = 25.0, netAfter = 0.0, partly = true))
        shows(s(R.string.refund_confirm_partial_format, m(25.0), m(40.0)))
    }

    @Test
    fun `confirm dialog has no partial line when the whole deposit applies`() {
        showConfirm(confirmState(partly = false))
        composeRule.onAllNodesWithText("Only ", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `confirm dialog buttons are disabled while submitting`() {
        showConfirm(confirmState(submitting = true))
        composeRule.onNodeWithText(s(R.string.refund_confirm_action)).assertIsNotEnabled()
        composeRule.onNodeWithText(s(R.string.cancel_button)).assertIsNotEnabled()
    }

    // ---- details sheet and unlink dialog --------------------------------------------------------

    private val subject = RefundSubject(2L, false, "Synthetic Shop", 1_000L, eur(100.0), eur(40.0))

    private val linkRow = RefundLinkRowUi(70L, 1L, "Synthetic Refund", 2_000L, null, eur(40.0), eur(40.0), eur(0.0))

    private fun showDetails(state: RefundDetailsUiState, onRemove: (RefundLinkRowUi) -> Unit = {}, onRetry: () -> Unit = {}) {
        composeRule.setContent { RefundDetailsSheet(state = state, onRemove = onRemove, onRetry = onRetry, onClose = {}) }
        composeRule.waitForIdle()
    }

    @Test
    fun `details Content shows the row-derived numbers and a Remove link action per link`() {
        val removed = mutableListOf<RefundLinkRowUi>()
        showDetails(RefundDetailsUiState.Content(subject, listOf(linkRow)), onRemove = { removed += it })
        shows(m(100.0))
        shows(m(40.0))
        shows(m(60.0))
        composeRule.onAllNodes(hasClickAction() and hasText("Remove link"))[0].performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(listOf(linkRow), removed) }
    }

    @Test
    fun `details Unavailable shows the message and Retry but keeps the summary numbers`() {
        showDetails(RefundDetailsUiState.Unavailable(subject))
        shows(s(R.string.refund_details_unavailable))
        shows("Retry")
        shows(m(100.0))
    }

    @Test
    fun `details Loading shows the summary numbers and no link actions`() {
        showDetails(RefundDetailsUiState.Loading(subject))
        shows(m(100.0))
        absent("Remove link")
    }

    private fun unlinkState(lastLink: Boolean, shown: Double, submitting: Boolean = false) =
        RefundUnlinkConfirmUiState(linkRow, 1L, "Synthetic Shop", eur(shown), lastLink, submitting)

    @Test
    fun `unlink dialog for the last link says go back to the gross`() {
        composeRule.setContent { RefundUnlinkDialog(unlinkState(lastLink = true, shown = 100.0), onConfirm = {}, onDismiss = {}) }
        composeRule.waitForIdle()
        shows(s(R.string.refund_unlink_title))
        shows(s(R.string.refund_unlink_body_format, m(40.0), "Synthetic Shop", m(100.0)))
        composeRule.onAllNodesWithText("go back to", substring = true, useUnmergedTree = true)[0].assertExists()
    }

    @Test
    fun `unlink dialog with other links remaining says show the remaining net`() {
        composeRule.setContent { RefundUnlinkDialog(unlinkState(lastLink = false, shown = 70.0), onConfirm = {}, onDismiss = {}) }
        composeRule.waitForIdle()
        shows(s(R.string.refund_unlink_body_remaining_format, m(40.0), "Synthetic Shop", m(70.0)))
        composeRule.onAllNodesWithText("go back to", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `unlink dialog buttons are disabled while submitting`() {
        composeRule.setContent { RefundUnlinkDialog(unlinkState(true, 100.0, submitting = true), onConfirm = {}, onDismiss = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Remove link").assertIsNotEnabled()
        composeRule.onNodeWithText(s(R.string.cancel_button)).assertIsNotEnabled()
    }

    // ---- stacked dialog buttons (ux-flows 6/9) -------------------------------------------------------

    private fun showWithFontScale(fontScale: Float, content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { content() }
        }
        composeRule.waitForIdle()
    }

    private fun assertStacked(primaryText: String, stacked: Boolean) {
        val primary = composeRule.onNodeWithText(primaryText).fetchSemanticsNode().boundsInRoot
        val cancel = composeRule.onNodeWithText(s(R.string.cancel_button)).fetchSemanticsNode().boundsInRoot
        if (stacked) {
            assertTrue("cancel sits below the primary action", cancel.top >= primary.bottom - 1f)
            assertEquals("both actions have the same width", primary.width, cancel.width, 1f)
            assertEquals("both actions start at the same x", primary.left, cancel.left, 1f)
        } else {
            assertTrue("actions share one row", kotlin.math.abs(cancel.top - primary.top) < 2f)
        }
    }

    @Test
    fun `confirm dialog stacks its buttons full width at font scale 1_3`() {
        showWithFontScale(1.3f) { RefundConfirmDialog(state = confirmState(), onConfirm = {}, onDismiss = {}) }
        assertStacked(s(R.string.refund_confirm_action), stacked = true)
    }

    @Test
    fun `confirm dialog keeps its buttons in one row below font scale 1_3`() {
        showWithFontScale(1.0f) { RefundConfirmDialog(state = confirmState(), onConfirm = {}, onDismiss = {}) }
        assertStacked(s(R.string.refund_confirm_action), stacked = false)
    }

    @Test
    fun `unlink dialog stacks its buttons full width at font scale 1_3 and both still act`() {
        var confirmed = 0
        var dismissed = 0
        showWithFontScale(1.3f) {
            RefundUnlinkDialog(unlinkState(lastLink = true, shown = 100.0), onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
        }
        assertStacked("Remove link", stacked = true)
        composeRule.onNodeWithText("Remove link").performClick()
        composeRule.onNodeWithText(s(R.string.cancel_button)).performClick()
        composeRule.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, dismissed)
        }
    }

    // ---- delete dialog extra line ---------------------------------------------------------------

    @Test
    fun `delete note shows for a linked row`() {
        composeRule.setContent { RefundDeleteNote(purchase(refunded = 40.0)) }
        shows(s(R.string.transactions_delete_refund_linked_note))
    }

    @Test
    fun `delete note is absent for an unlinked row`() {
        composeRule.setContent { RefundDeleteNote(purchase(refunded = 0.0)) }
        absent(s(R.string.transactions_delete_refund_linked_note))
    }

    // ---- ChangeTypeDialog -----------------------------------------------------------------------

    private fun showChangeType(
        current: TransactionType = TransactionType.DEPOSIT,
        linked: Boolean = false,
        likely: Boolean = false,
        onConfirm: () -> Unit = {},
        onApplyRefund: () -> Unit = {}
    ) {
        composeRule.setContent {
            ChangeTypeDialog(
                currentType = current,
                currentTransferDirection = null,
                currentTransferAccountName = null,
                onDismiss = {},
                onConfirm = { _, _, _ -> onConfirm() },
                isLikelyRefund = likely,
                isRefundLinked = linked,
                onApplyRefund = onApplyRefund
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `ChangeTypeDialog on a linked row shows the lock line and keeps Confirm disabled after choosing a type`() {
        var confirmed = 0
        showChangeType(linked = true, onConfirm = { confirmed++ })
        shows(s(R.string.transactions_refund_linked_locked))
        composeRule.onNodeWithText("PURCHASE").performClick()
        composeRule.onNodeWithText(s(R.string.transactions_update_type_button)).assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, confirmed) }
    }

    @Test
    fun `ChangeTypeDialog on an unlinked row has no lock line and Confirm enables after a change`() {
        var confirmed = 0
        showChangeType(linked = false, onConfirm = { confirmed++ })
        absent(s(R.string.transactions_refund_linked_locked))
        composeRule.onNodeWithText(s(R.string.transactions_update_type_button)).assertIsNotEnabled() // nothing changed yet
        composeRule.onNodeWithText("PURCHASE").performClick()
        composeRule.onNodeWithText(s(R.string.transactions_update_type_button)).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test
    fun `ChangeTypeDialog shows the shared likely refund notice and its action`() {
        var applied = 0
        showChangeType(likely = true, onApplyRefund = { applied++ })
        shows(s(R.string.transactions_refund_likelihood_notice))
        composeRule.onNodeWithText("Apply to purchase").performClick()
        composeRule.runOnIdle { assertEquals(1, applied) }
    }

    @Test
    fun `ChangeTypeDialog without the likely flag shows no notice`() {
        showChangeType(likely = false)
        absent(s(R.string.transactions_refund_likelihood_notice))
        absent("Apply to purchase")
    }
}
