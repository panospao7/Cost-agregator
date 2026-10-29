package com.yourname.expensetracker.ui.screens.addexpense

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencyUnsetReason
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import com.yourname.expensetracker.ui.components.currency.CurrencyRegionUi
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Loan-installments S11b: the "Pay in installments" section of [AddExpenseSheet], driven through the REAL
 * [AddExpenseViewModel] built by [AddExpenseSheetHarness]. Oracle: plan amendment "S11 re-anchor after MEC B2/C"
 * sections 3, 4 (S11b) and 9, and ux-flows 3.1 (S11b notes).
 *
 * Gate: production calls `setInstallmentsEnabled(on)` with the default flag only, so the section is dormant while
 * `InstallmentsFeature.ENABLED` is false. These tests never change the flag: the section is shown through the
 * sheet's `installmentsFeatureEnabled` parameter, and installments are switched ON through the view model seam
 * (`setInstallmentsEnabled(true, enabled = true)`).
 *
 * Synthetic data only. Locale and TimeZone are pinned and restored; the harness is closed in `@After`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], qualifiers = "w411dp-h2000dp")
class AddExpenseSheetInstallmentsUiTest {

    private val composeRule = createComposeRule()

    // testReleaseUnitTest does not merge ui-test-manifest: register ComponentActivity first.
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

    private val originalLocale: Locale = Locale.getDefault()
    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val harness = AddExpenseSheetHarness(home = "EUR")
    private lateinit var viewModel: AddExpenseViewModel
    private var dismissCalls = 0

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Athens"))
    }

    @After
    fun tearDown() {
        // Cancel every viewModelScope this harness created, then restore the process defaults.
        harness.close()
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Application>().getString(resId, *args)

    /**
     * [featureEnabled] null = the sheet's DEFAULT (the real flag, false); true/false = the composable parameter.
     */
    private fun showSheet(featureEnabled: Boolean? = true) {
        viewModel = harness.createViewModel()
        shadowOf(Looper.getMainLooper()).idle() // the home currency load completes
        composeRule.setContent {
            MaterialTheme {
                if (featureEnabled == null) {
                    AddExpenseSheet(onDismiss = { dismissCalls++ }, viewModel = viewModel)
                } else {
                    AddExpenseSheet(
                        onDismiss = { dismissCalls++ },
                        viewModel = viewModel,
                        installmentsFeatureEnabled = featureEnabled
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** The VM test seam: the only way these tests turn installments ON (the flag stays false). */
    private fun enableInstallments() {
        composeRule.runOnIdle { viewModel.setInstallmentsEnabled(true, enabled = true) }
        composeRule.waitForIdle()
    }

    private val installments get() = viewModel.state.value.installments

    private val switchNode get() = composeRule.onNodeWithTag(AddExpenseInstallmentsTags.SWITCH)

    private val repeatLabel: String get() = string(R.string.repeat_transaction_label)
    private val repeatHelper: String get() = string(R.string.add_expense_installments_repeat_exclusive)

    // ---- dormancy: nothing renders and nothing turns on while the feature flag is off ---------------------------

    @Test
    fun featureOff_theSectionIsHidden_andRepeatIsShown() {
        showSheet(featureEnabled = false)

        switchNode.assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.declare_installments_title)).assertDoesNotExist()
        composeRule.onNodeWithText(repeatLabel).assertExists()
        composeRule.onNodeWithText(repeatHelper).assertDoesNotExist()
    }

    @Test
    fun defaultFlag_theSheetRendersNoInstallmentsSection_andTheDefaultViewModelGateKeepsItOff() {
        showSheet(featureEnabled = null)

        switchNode.assertDoesNotExist()
        composeRule.runOnIdle { viewModel.setInstallmentsEnabled(true) } // the production call: default flag only
        composeRule.waitForIdle()

        assertFalse(installments.on)
        composeRule.onNodeWithText(repeatLabel).assertExists()
    }

    @Test
    fun productionSwitchCall_usesTheDefaultFlag_soAClickDoesNotTurnInstallmentsOn() {
        showSheet(featureEnabled = true)

        switchNode.assertExists().assertIsOff().assertIsEnabled()
        switchNode.performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertFalse("the sheet calls setInstallmentsEnabled(on) with the default flag only", installments.on)
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.COUNT).assertDoesNotExist()
    }

    @Test
    fun featureOnButSwitchOff_showsOnlyTheSwitch_andKeepsRepeat() {
        showSheet(featureEnabled = true)

        composeRule.onNodeWithText(string(R.string.declare_installments_title)).assertExists()
        switchNode.assertIsOff()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.COUNT).assertDoesNotExist()
        composeRule.onNodeWithText(repeatLabel).assertExists()
        composeRule.onNodeWithText(repeatHelper).assertDoesNotExist()
    }

    // ---- ux-flows 3.1 / amendment section 3: Repeat is hidden while installments are ON -------------------------

    @Test
    fun installmentsOn_hidesRepeat_andShowsTheHelper() {
        showSheet()
        enableInstallments()

        assertTrue(installments.on)
        composeRule.onNodeWithText(repeatLabel).assertDoesNotExist()
        composeRule.onNodeWithText(repeatHelper).assertExists()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.COUNT).assertExists()
    }

    @Test
    fun installmentsSwitchedBackOff_showsRepeatAgain_andRemovesTheHelper() {
        showSheet()
        enableInstallments()
        composeRule.runOnIdle { viewModel.setInstallmentsEnabled(false, enabled = true) }
        composeRule.waitForIdle()

        assertFalse(installments.on)
        composeRule.onNodeWithText(repeatLabel).assertExists()
        composeRule.onNodeWithText(repeatHelper).assertDoesNotExist()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.COUNT).assertDoesNotExist()
    }

    // ---- MEC AE-28 / UX-Q-6: an Unset currency keeps the control enabled so the user can pick -------------------

    private val unsetControl: SemanticsMatcher
        get() = hasContentDescription(string(R.string.mec_control_unset)) and hasClickAction()

    @Test
    fun jpyWhenSwitchingOn_isUnset_andTheCurrencyControlStaysEnabled() {
        showSheet()
        composeRule.runOnIdle { viewModel.selectCurrency("JPY") }
        composeRule.waitForIdle()
        assertEquals("JPY", viewModel.state.value.selectedCurrency)

        enableInstallments()

        val state = viewModel.state.value
        assertNull(state.selectedCurrency)
        assertEquals(CurrencyRegionUi.Unset(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE), state.currencyRegion)
        composeRule.onNode(unsetControl).assertExists().assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.mec_installment_unset)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_sheet_installment_note)).assertExists()
        composeRule.onNode(unsetControl).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        composeRule.onNodeWithText(string(R.string.save_button)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.save_button)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.mec_save_disabled_currency))
        )
    }

    @Test
    fun jpyUnset_thenAnExplicitUsdPick_leavesUnsetAndKeepsInstallmentsOn() {
        showSheet()
        composeRule.runOnIdle { viewModel.selectCurrency("JPY") }
        enableInstallments()

        composeRule.runOnIdle { viewModel.selectCurrency("USD") }
        composeRule.waitForIdle()

        assertEquals("USD", viewModel.state.value.selectedCurrency)
        assertTrue(installments.on)
        composeRule.onNodeWithText(string(R.string.mec_installment_unset)).assertDoesNotExist()
        composeRule.onNode(unsetControl).assertDoesNotExist()
    }

    // ---- eligibility: the switch is disabled, with a reason, for Not mine / Shared / non-spending --------------

    @Test
    fun notMine_disablesTheSwitch_withTheFixedReason() {
        showSheet()
        composeRule.runOnIdle { viewModel.setIsNotMine(true) }
        composeRule.waitForIdle()

        switchNode.assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.declare_installments_refused_not_mine)).assertExists()
    }

    @Test
    fun shared_disablesTheSwitch_withTheFixedReason() {
        showSheet()
        composeRule.runOnIdle { viewModel.setIsSharedExpense(true) }
        composeRule.waitForIdle()

        switchNode.assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.declare_installments_refused_shared)).assertExists()
    }

    @Test
    fun nonSpendingType_disablesTheSwitch_withTheNotSpendingReason() {
        showSheet()
        composeRule.runOnIdle { viewModel.selectTransactionType(TransactionType.DEPOSIT) }
        composeRule.waitForIdle()

        switchNode.assertIsNotEnabled()
        // S11c: the dedicated key replaces the "linked" expense-not-eligible string (wrong context here).
        composeRule.onNodeWithText(string(R.string.add_expense_installments_not_spending)).assertExists()
        composeRule.onNodeWithText(string(R.string.installments_action_refused_expense_not_eligible)).assertDoesNotExist()
    }

    @Test
    fun transferType_disablesTheSwitch_withTheNotSpendingReason() {
        showSheet()
        composeRule.runOnIdle { viewModel.selectTransactionType(TransactionType.TRANSFER) }
        composeRule.waitForIdle()

        switchNode.assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.add_expense_installments_not_spending)).assertExists()
    }

    @Test
    fun spendingType_doesNotShowTheNotSpendingReason() {
        showSheet()

        switchNode.assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.add_expense_installments_not_spending)).assertDoesNotExist()
    }

    @Test
    fun notMineWhileOn_turnsTheSectionOff_keepsTheValues_andDisablesTheSwitch() {
        showSheet()
        enableInstallments()
        composeRule.runOnIdle { viewModel.setInstallmentCountText("24") }

        composeRule.runOnIdle { viewModel.setIsNotMine(true) }
        composeRule.waitForIdle()

        assertFalse(installments.on)
        assertEquals("24", installments.countText)
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.COUNT).assertDoesNotExist()
        switchNode.assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.declare_installments_refused_not_mine)).assertExists()
    }

    // ---- the inputs bind to the view model ---------------------------------------------------------------------

    @Test
    fun countInput_bindsToTheViewModel_andAnInvalidCountShowsTheFixedError() {
        showSheet()
        enableInstallments()
        val error = string(R.string.declare_installments_refused_count)
        composeRule.onNodeWithText(error).assertDoesNotExist()

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.COUNT).performTextReplacement("24")
        composeRule.waitForIdle()
        assertEquals("24", installments.countText)
        assertEquals(24, installments.count)
        composeRule.onNodeWithText(error).assertDoesNotExist()

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.COUNT).performTextReplacement("1")
        composeRule.waitForIdle()
        assertEquals("1", installments.countText)
        assertNull(installments.count)
        composeRule.onNodeWithText(error).assertExists()
    }

    @Test
    fun splitChips_bindToTheViewModel_andFixedAmountAppearsOnlyInFixedMode() {
        showSheet()
        enableInstallments()
        assertEquals(InstallmentSplitMethod.EQUAL_SPLIT, installments.split)
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.FIXED_AMOUNT).assertDoesNotExist()

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.SPLIT_FIXED).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertEquals(InstallmentSplitMethod.FIXED_PAYMENT, installments.split)
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.FIXED_AMOUNT).assertExists()

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.SPLIT_EQUAL).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertEquals(InstallmentSplitMethod.EQUAL_SPLIT, installments.split)
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.FIXED_AMOUNT).assertDoesNotExist()
    }

    @Test
    fun fixedAmountInput_bindsToTheViewModel_andJunkShowsTheInvalidAmountError() {
        showSheet()
        enableInstallments()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.SPLIT_FIXED).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.FIXED_AMOUNT).performTextReplacement("50.00")
        composeRule.waitForIdle()
        assertEquals("50.00", installments.fixedText)
        assertEquals(5_000L, installments.fixedMinor)

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.FIXED_AMOUNT).performTextReplacement("abc")
        composeRule.waitForIdle()
        assertEquals("abc", installments.fixedText)
        assertNull(installments.fixedMinor)
    }

    @Test
    fun modeChips_andNameInput_bindToTheViewModel() {
        showSheet()
        enableInstallments()
        assertEquals(InstallmentFulfilmentMode.AUTO_RECORD, installments.mode)

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.MODE_CONFIRM).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertEquals(InstallmentFulfilmentMode.CONFIRM, installments.mode)

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.NAME).performTextReplacement("Laptop plan")
        composeRule.waitForIdle()
        assertEquals("Laptop plan", installments.nameText)
    }

    // ---- reset or close clears the whole section ---------------------------------------------------------------

    @Test
    fun reset_clearsTheSection_andRepeatReturns() {
        showSheet()
        enableInstallments()
        composeRule.runOnIdle {
            viewModel.setInstallmentCountText("36")
            viewModel.setInstallmentSplit(InstallmentSplitMethod.FIXED_PAYMENT)
            viewModel.setInstallmentFixedText("20.00")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.FIXED_AMOUNT).assertExists()

        composeRule.runOnIdle { viewModel.reset() }
        composeRule.waitForIdle()

        assertEquals(AddExpenseInstallmentsState(), installments)
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.COUNT).assertDoesNotExist()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.FIXED_AMOUNT).assertDoesNotExist()
        composeRule.onNodeWithText(repeatHelper).assertDoesNotExist()
        composeRule.onNodeWithText(repeatLabel).assertExists()
    }

    @Test
    fun closeIcon_clearsTheSection_andDismisses() {
        showSheet()
        enableInstallments()
        composeRule.runOnIdle { viewModel.setInstallmentCountText("36") }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(string(R.string.close_content_description))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals(1, dismissCalls)
        assertEquals(AddExpenseInstallmentsState(), installments)
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.COUNT).assertDoesNotExist()
        composeRule.onNodeWithText(repeatLabel).assertExists()
    }

    // ---- S11c: the live preview card (ux-flows 3.1 item 7) and the past-due notice (ux-flows :389) --------------
    // Clock: the harness pins now to 2023-11-14T22:13:20Z, which is Wed 15 Nov 2023 in the Europe/Athens session
    // zone, so today and the purchase day are 15 Nov 2023 and the default first payment is 15 Dec 2023.

    private fun epochDay(y: Int, mo: Int, d: Int): Long = java.time.LocalDate.of(y, mo, d).toEpochDay()

    private fun typeAmount(amount: String) {
        composeRule.runOnIdle { viewModel.updateAmount(amount) }
        composeRule.waitForIdle()
    }

    private val needsAmountText: String get() = string(R.string.add_expense_installments_preview_needs_amount)
    private val financingLessText: String get() = string(R.string.add_expense_installments_preview_financing_less)

    @Test
    fun previewOff_installmentsOff_showsNoPreviewCard() {
        showSheet()
        typeAmount("600")

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PREVIEW).assertDoesNotExist()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PAST_DUE).assertDoesNotExist()
    }

    @Test
    fun previewReady_showsTheSummary_theTotal_andTheEndDate() {
        showSheet()
        typeAmount("600")
        enableInstallments()

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PREVIEW).assertExists()
        // "12 x <payment> = <total>": EUR 600 over 12 is 50.00 each, 600.00 in total.
        composeRule.onNode(
            hasText("12 × ", substring = true) and hasText("50.00", substring = true) and
                hasText("600.00", substring = true)
        ).assertExists()
        // First payment 15 Dec 2023, twelfth payment 15 Nov 2024.
        val endsPrefix = string(R.string.add_expense_installments_preview_ends, "").trim()
        composeRule.onNode(hasText(endsPrefix, substring = true) and hasText("2024", substring = true)).assertExists()
        composeRule.onNodeWithText(needsAmountText).assertDoesNotExist()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PAST_DUE).assertDoesNotExist()
    }

    @Test
    fun previewNeedsAmount_whenTheAmountIsBlank_andIsReplacedByTheCardOnceTyped() {
        showSheet()
        enableInstallments()

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PREVIEW).assertExists()
        composeRule.onNodeWithText(needsAmountText).assertExists()
        composeRule.onNode(hasText("12 × ", substring = true)).assertDoesNotExist()

        typeAmount("600")

        composeRule.onNodeWithText(needsAmountText).assertDoesNotExist()
        composeRule.onNode(hasText("12 × ", substring = true)).assertExists()
    }

    @Test
    fun previewIsHidden_forAnInvalidCount() {
        showSheet()
        typeAmount("600")
        enableInstallments()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.COUNT).performTextReplacement("1")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PREVIEW).assertDoesNotExist()
        composeRule.onNodeWithText(needsAmountText).assertDoesNotExist()
    }

    @Test
    fun previewIsHidden_whenTheCurrencyIsUnset() {
        showSheet()
        typeAmount("600")
        composeRule.runOnIdle { viewModel.selectCurrency("JPY") }
        enableInstallments()

        assertNull(viewModel.state.value.selectedCurrency)
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PREVIEW).assertDoesNotExist()
    }

    @Test
    fun fixedPaymentBelowThePrice_showsTheLessThanPurchaseNote_andAboveOrEqualDoesNot() {
        showSheet()
        typeAmount("600")
        enableInstallments()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.SPLIT_FIXED).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        // Below the price: negative financing cost.
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.FIXED_AMOUNT).performTextReplacement("45.00")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(financingLessText).assertExists()

        // Equal to the share (cost 0) and above the price (cost > 0): no note.
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.FIXED_AMOUNT).performTextReplacement("50.00")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PREVIEW).assertExists()
        composeRule.onNodeWithText(financingLessText).assertDoesNotExist()

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.FIXED_AMOUNT).performTextReplacement("55.00")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(financingLessText).assertDoesNotExist()
    }

    @Test
    fun pastDueNotice_isShown_withTheCount_whenSomePaymentsAreBeforeToday() {
        showSheet()
        typeAmount("600")
        enableInstallments()
        // Payments on the 15th from Aug: Aug 15, Sep 15 and Oct 15 are before 15 Nov 2023; 15 Nov itself is not.
        composeRule.runOnIdle { viewModel.setInstallmentFirstDue(epochDay(2023, 8, 15)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PAST_DUE).assertExists()
        composeRule.onNodeWithText(string(R.string.add_expense_installments_past_due, 3)).assertExists()
    }

    @Test
    fun pastDueNotice_boundary_aFirstPaymentTodayHasNoNotice_andYesterdayHasOne() {
        showSheet()
        typeAmount("600")
        enableInstallments()

        composeRule.runOnIdle { viewModel.setInstallmentFirstDue(epochDay(2023, 11, 15)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PREVIEW).assertExists()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PAST_DUE).assertDoesNotExist()

        composeRule.runOnIdle { viewModel.setInstallmentFirstDue(epochDay(2023, 11, 14)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PAST_DUE).assertExists()
        composeRule.onNodeWithText(string(R.string.add_expense_installments_past_due, 1)).assertExists()
    }

    @Test
    fun pastDueNotice_isAbsent_forTheDefaultFutureFirstPayment() {
        showSheet()
        typeAmount("600")
        enableInstallments()

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PREVIEW).assertExists()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PAST_DUE).assertDoesNotExist()
    }

    @Test
    fun resetWhileOn_removesThePreviewAndThePastDueNotice() {
        showSheet()
        typeAmount("600")
        enableInstallments()
        composeRule.runOnIdle { viewModel.setInstallmentFirstDue(epochDay(2023, 8, 15)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PAST_DUE).assertExists()

        composeRule.runOnIdle { viewModel.reset() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PREVIEW).assertDoesNotExist()
        composeRule.onNodeWithTag(AddExpenseInstallmentsTags.PAST_DUE).assertDoesNotExist()
    }
}
