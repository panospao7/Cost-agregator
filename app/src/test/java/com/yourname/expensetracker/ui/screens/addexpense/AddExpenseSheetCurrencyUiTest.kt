package com.yourname.expensetracker.ui.screens.addexpense

import android.app.Application
import android.content.ComponentName
import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.ui.components.currency.CurrencyRegionUi
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
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
 * manual-entry-currency B2b: the visible wiring of [AddExpenseSheet] to the currency state of the REAL
 * [AddExpenseViewModel] (built by [AddExpenseSheetHarness]): the control/amount row layout (ux section 2),
 * the Repeat note (INV-16), the amount description and leading symbol (F4), Save disabled with a spoken
 * reason (ux section 4), and the open-setting confirm (D-11).
 *
 * The harness rate store has no rows, so selecting a foreign currency yields a blocked preview. Currency
 * changes are made through the view model after the sheet has opened (a new sheet session starts on the home
 * currency); the picker itself is covered by CurrencySelectionControlTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AddExpenseSheetCurrencyUiTest {

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
    private val harness = AddExpenseSheetHarness(home = "EUR")
    private lateinit var viewModel: AddExpenseViewModel
    private var openSettingCalls = 0
    private var dismissCalls = 0

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
        // MEC R-5: cancel every viewModelScope this harness created so nothing stays scheduled on the
        // shared Robolectric main looper after the test.
        harness.close()
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Application>().getString(resId, *args)

    /**
     * [preSeed] runs on the view model BEFORE the sheet composes (a retained draft from an earlier sheet
     * session); [initialAmount] and [initialMerchant] are the sheet's incoming prefill.
     */
    private fun showSheet(
        fontScale: Float = 1.0f,
        widthDp: Int = 412,
        initialAmount: String? = null,
        initialMerchant: String? = null,
        preSeed: (AddExpenseViewModel) -> Unit = {}
    ) {
        viewModel = harness.createViewModel()
        shadowOf(Looper.getMainLooper()).idle() // the home currency load completes
        preSeed(viewModel)
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            val baseConfiguration = LocalConfiguration.current
            val density = remember(baseDensity, fontScale) { Density(baseDensity.density, fontScale) }
            val configuration = remember(baseConfiguration, widthDp) {
                Configuration(baseConfiguration).apply { screenWidthDp = widthDp }
            }
            CompositionLocalProvider(
                LocalDensity provides density,
                LocalConfiguration provides configuration
            ) {
                MaterialTheme {
                    AddExpenseSheet(
                        onDismiss = { dismissCalls++ },
                        initialAmount = initialAmount,
                        initialMerchant = initialMerchant,
                        onOpenExchangeRateSetting = { openSettingCalls++ },
                        viewModel = viewModel
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun selectForeign(code: String = "USD") {
        composeRule.runOnIdle { viewModel.selectCurrency(code) }
        composeRule.waitForIdle()
    }

    // ---- node finders --------------------------------------------------------------------------------------

    private val controlMatcher: SemanticsMatcher
        get() = hasContentDescription(string(R.string.mec_control_cd, "ZZZ").substringBefore("ZZZ"), substring = true) and
            hasClickAction()

    private fun amountMatcher(amount: String, code: String): SemanticsMatcher =
        hasContentDescription(string(R.string.add_expense_amount_cd, amount, code))

    private val saveButton get() = composeRule.onNodeWithText(string(R.string.save_button))

    // ---- row layout: stacks at fontScale >= 1.3 or width < 360 dp -----------------------------------------

    private fun assertSideBySide() {
        val control = composeRule.onNode(controlMatcher).fetchSemanticsNode()
        val amount = composeRule.onNode(amountMatcher("", "EUR")).fetchSemanticsNode()
        assertTrue(
            "amount must start right of the control: control x=${control.positionInRoot.x} w=${control.size.width}, amount x=${amount.positionInRoot.x}",
            amount.positionInRoot.x >= control.positionInRoot.x + control.size.width
        )
    }

    private fun assertStacked() {
        val control = composeRule.onNode(controlMatcher).fetchSemanticsNode()
        val amount = composeRule.onNode(amountMatcher("", "EUR")).fetchSemanticsNode()
        assertTrue(
            "amount must start below the control: control y=${control.positionInRoot.y} h=${control.size.height}, amount y=${amount.positionInRoot.y}",
            amount.positionInRoot.y >= control.positionInRoot.y + control.size.height
        )
    }

    @Test
    fun layout_controlAndAmountShareARow_atFontScale1_and412dp() {
        showSheet(fontScale = 1.0f, widthDp = 412)
        assertSideBySide()
    }

    @Test
    fun layout_controlAndAmountStack_atFontScale1_3() {
        showSheet(fontScale = 1.3f, widthDp = 412)
        assertStacked()
    }

    @Test
    fun layout_controlAndAmountShareARow_justBelowTheFontScaleThreshold() {
        showSheet(fontScale = 1.29f, widthDp = 412)
        assertSideBySide()
    }

    @Test
    fun layout_controlAndAmountStack_at359dp() {
        showSheet(fontScale = 1.0f, widthDp = 359)
        assertStacked()
    }

    @Test
    fun layout_controlAndAmountShareARow_atExactly360dp() {
        showSheet(fontScale = 1.0f, widthDp = 360)
        assertSideBySide()
    }

    // ---- INV-16: Repeat is hidden (not disabled) for a foreign currency, with a note ----------------------

    @Test
    fun inv16_homeCurrency_showsTheRepeatSwitch_andNoRepeatNote() {
        showSheet()

        composeRule.onNodeWithText(string(R.string.repeat_transaction_label)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_repeat_home_only, "EUR")).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_repeat_cleared)).assertDoesNotExist()
    }

    @Test
    fun inv16_foreignCurrency_hidesRepeat_andNamesTheHomeCurrencyInTheNote() {
        showSheet()
        selectForeign("USD")

        composeRule.onNodeWithText(string(R.string.repeat_transaction_label)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_repeat_home_only, "EUR")).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_repeat_cleared)).assertDoesNotExist()
    }

    @Test
    fun inv16_foreignCurrencyAfterRepeatWasOn_showsTheRepeatClearedNote() {
        showSheet()
        composeRule.runOnIdle { viewModel.toggleRecurring() }
        selectForeign("USD")

        composeRule.onNodeWithText(string(R.string.repeat_transaction_label)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_repeat_cleared)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_repeat_home_only, "EUR")).assertDoesNotExist()
    }

    @Test
    fun inv16_switchingBackToHome_showsTheRepeatSwitchAgain() {
        showSheet()
        selectForeign("USD")
        selectForeign("EUR")

        composeRule.onNodeWithText(string(R.string.repeat_transaction_label)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_repeat_home_only, "EUR")).assertDoesNotExist()
    }

    // ---- F4: amount description and leading symbol follow the SELECTED currency --------------------------

    private fun symbolNodeCount(symbol: String): Int =
        composeRule.onAllNodes(hasText(symbol), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun f4_amountDescriptionAndLeadingSymbol_followTheSelectedCurrency() {
        showSheet()
        composeRule.runOnIdle { viewModel.updateAmount("12.50") }
        composeRule.waitForIdle()

        composeRule.onNode(amountMatcher("12.50", "EUR")).assertExists()
        assertEquals("the euro sign leads the home amount", 1, symbolNodeCount("€"))
        assertEquals(0, symbolNodeCount("$"))

        selectForeign("USD")

        composeRule.onNode(amountMatcher("12.50", "USD")).assertExists()
        composeRule.onNode(amountMatcher("12.50", "EUR")).assertDoesNotExist()
        assertEquals("the dollar sign now leads the amount", 1, symbolNodeCount("$"))
        assertEquals(0, symbolNodeCount("€"))
    }

    // ---- ux section 4: Save is disabled, with a spoken reason, when the rate is missing -------------------

    @Test
    fun save_homeCurrency_isEnabled_withoutAStateDescription() {
        showSheet()

        saveButton.assertIsEnabled()
        saveButton.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun save_foreignCurrencyWithoutARate_isDisabled_withTheRateStateDescription() {
        showSheet()
        selectForeign("USD")

        saveButton.assertIsNotEnabled()
        saveButton.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.mec_save_disabled_rate))
        )
    }

    @Test
    fun save_backToHome_isEnabledAgain() {
        showSheet()
        selectForeign("USD")
        selectForeign("EUR")

        saveButton.assertIsEnabled()
        saveButton.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    // ---- D-11: open the exchange-rate setting, with a confirm when an amount was entered ------------------

    private val openSettingText: String get() = string(R.string.mec_action_open_setting)
    private val dialogOpenSetting: SemanticsMatcher
        get() = hasText(openSettingText) and hasAnyAncestor(isDialog())

    @Test
    fun d11_blockedCardWithSettingOff_offersOpenSetting_andNoRefresh() {
        showSheet()
        selectForeign("USD")

        composeRule.onNodeWithText(openSettingText).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertDoesNotExist()
    }

    @Test
    fun d11_blockedCardWithSettingOn_offersRefresh_andNoOpenSetting() {
        showSheet()
        composeRule.runOnIdle {
            harness.privacyFlow.value =
                PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = true))
        }
        selectForeign("USD")

        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertExists()
        composeRule.onNodeWithText(openSettingText).assertDoesNotExist()
    }

    @Test
    fun d11_openSettingWithNoAmount_navigatesOnce_withoutADialog() {
        showSheet()
        selectForeign("USD")

        composeRule.onNodeWithText(openSettingText).performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals("the host callback runs exactly once", 1, openSettingCalls)
        composeRule.onNodeWithText(string(R.string.mec_open_setting_confirm_title)).assertDoesNotExist()
        assertEquals(
            "the request is consumed after navigation",
            com.yourname.expensetracker.ui.components.currency.CurrencyOpenSettingRequest.NONE,
            viewModel.state.value.openSettingRequest
        )
    }

    @Test
    fun d11_openSettingWithAnAmount_showsTheDialog_andConfirmingCallsTheHostOnce() {
        showSheet()
        selectForeign("USD")
        composeRule.runOnIdle { viewModel.updateAmount("12") }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(openSettingText).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.mec_open_setting_confirm_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_open_setting_confirm_body)).assertExists()
        assertEquals("nothing navigates before the user confirms", 0, openSettingCalls)

        composeRule.onNode(dialogOpenSetting).performClick()
        composeRule.waitForIdle()

        assertEquals("the host callback runs exactly once after the confirm", 1, openSettingCalls)
        composeRule.onNodeWithText(string(R.string.mec_open_setting_confirm_title)).assertDoesNotExist()
    }

    @Test
    fun d11_openSettingWithAnAmount_cancelKeepsTheForm_andNeverCallsTheHost() {
        showSheet()
        selectForeign("USD")
        composeRule.runOnIdle { viewModel.updateAmount("12") }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(openSettingText).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText(string(R.string.cancel_button)) and hasAnyAncestor(isDialog())).performClick()
        composeRule.waitForIdle()

        assertEquals(0, openSettingCalls)
        composeRule.onNodeWithText(string(R.string.mec_open_setting_confirm_title)).assertDoesNotExist()
        assertEquals("12", viewModel.state.value.amount)
    }

    // ---- MEC R-1(a): a confirmed navigate discards the entry, as the dialog copy promises -------------------

    @Test
    fun r1a_confirmedOpenSetting_clearsTheAmountAndMerchant_andNavigatesOnce() {
        showSheet()
        selectForeign("USD")
        composeRule.runOnIdle {
            viewModel.updateAmount("12")
            viewModel.updateMerchant("Shop")
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(openSettingText).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.mec_open_setting_confirm_title)).assertExists()
        composeRule.onNode(dialogOpenSetting).performClick()
        composeRule.waitForIdle()

        assertEquals("the host callback runs exactly once", 1, openSettingCalls)
        assertEquals("", viewModel.state.value.amount)
        assertEquals("", viewModel.state.value.merchant)
        assertEquals("EUR", viewModel.state.value.selectedCurrency)
        assertEquals(
            com.yourname.expensetracker.ui.components.currency.CurrencyOpenSettingRequest.NONE,
            viewModel.state.value.openSettingRequest
        )
        composeRule.onNodeWithText(string(R.string.mec_open_setting_confirm_title)).assertDoesNotExist()
    }

    @Test
    fun r1a_cancelledConfirm_keepsTheWholeDraft() {
        showSheet()
        selectForeign("USD")
        composeRule.runOnIdle {
            viewModel.updateAmount("12")
            viewModel.updateMerchant("Shop")
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(openSettingText).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText(string(R.string.cancel_button)) and hasAnyAncestor(isDialog())).performClick()
        composeRule.waitForIdle()

        assertEquals(0, openSettingCalls)
        assertEquals("12", viewModel.state.value.amount)
        assertEquals("Shop", viewModel.state.value.merchant)
        assertEquals("USD", viewModel.state.value.selectedCurrency)
    }

    // ---- MEC R-1(b) ordering: a new session discards a retained foreign draft BEFORE the prefill lands ------

    @Test
    fun r1b_newSessionWithInitialValues_overARetainedForeignDraft_showsTheInitialValuesOnHome() {
        showSheet(initialAmount = "7.50", initialMerchant = "Cafe") { vm ->
            // An earlier sheet session left a USD draft behind in the same view model.
            vm.onSheetOpened()
            vm.selectCurrency("USD")
            vm.updateAmount("20.00")
            vm.updateMerchant("Old")
            vm.onSheetLeft()
        }

        val state = viewModel.state.value
        assertEquals("7.50", state.amount)
        assertEquals("Cafe", state.merchant)
        assertEquals("EUR", state.selectedCurrency)
        composeRule.onNode(amountMatcher("7.50", "EUR")).assertExists()
        saveButton.assertIsEnabled()
    }

    @Test
    fun r1b_newSessionWithoutInitialValues_overARetainedForeignDraft_showsABlankHomeForm() {
        showSheet { vm ->
            vm.onSheetOpened()
            vm.selectCurrency("USD")
            vm.updateAmount("20.00")
            vm.onSheetLeft()
        }

        assertEquals("", viewModel.state.value.amount)
        assertEquals("EUR", viewModel.state.value.selectedCurrency)
        composeRule.onNode(amountMatcher("", "EUR")).assertExists()
    }

    // ---- F-B2-A11Y-1: Save speaks "Fix the amount" for an invalid amount ---------------------------------

    @Test
    fun a11y_amountInvalid_giveSaveTheFixTheAmountStateDescription() {
        showSheet()
        selectForeign("USD")
        // The sanitizer truncates extra decimals, so use an above-cap amount (10 integer digits is the input max).
        composeRule.runOnIdle { viewModel.updateAmount("9999999999") }
        composeRule.waitForIdle()

        assertTrue(viewModel.state.value.currencyRegion is CurrencyRegionUi.AmountInvalid)
        saveButton.assertIsNotEnabled()
        saveButton.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.mec_save_disabled_amount))
        )
        assertEquals("Fix the amount", string(R.string.mec_save_disabled_amount))
    }

    @Test
    fun a11y_amountFixed_removesTheFixTheAmountStateDescription() {
        showSheet()
        selectForeign("USD")
        composeRule.runOnIdle { viewModel.updateAmount("9999999999") }
        composeRule.waitForIdle()
        composeRule.runOnIdle { viewModel.updateAmount("12.34") }
        composeRule.waitForIdle()

        // No rate is stored: the state description is now the rate reason, not the amount one.
        saveButton.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.mec_save_disabled_rate))
        )
    }

    // ---- F-B2-A11Y-1: the preview is a polite live region after a currency or date change only -----------

    private fun utcMidnight(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun seedUsdRate(date: LocalDate) {
        harness.rateStore.rows += DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.9,
            lastUpdated = 0L,
            source = "ecb",
            validDate = utcMidnight(date)
        )
    }

    private val previewMatcher: SemanticsMatcher get() = hasText("ECB rate of", substring = true)

    private fun politePreviewCount(): Int =
        composeRule.onAllNodes(
            previewMatcher and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        ).fetchSemanticsNodes().size

    private fun previewCount(): Int = composeRule.onAllNodes(previewMatcher).fetchSemanticsNodes().size

    @Test
    fun a11y_preview_isPoliteAfterACurrencyChange_silentOnKeystrokes_andPoliteAgainAfterADateChange() {
        // FIXED_NOW is 2023-11-14; two stored rates let a date change move the shown rate date.
        seedUsdRate(LocalDate.of(2023, 11, 13))
        seedUsdRate(LocalDate.of(2023, 11, 10))
        showSheet()
        composeRule.runOnIdle { viewModel.updateAmount("10") }
        composeRule.waitForIdle()

        // A currency change: the preview appears and is announced politely.
        selectForeign("USD")
        assertEquals("the preview is shown", 1, previewCount())
        assertEquals("a currency change announces the preview", 1, politePreviewCount())

        // An amount keystroke: the preview changes and is not announced.
        composeRule.runOnIdle { viewModel.updateAmount("100") }
        composeRule.waitForIdle()
        assertEquals(1, previewCount())
        assertEquals("a keystroke must not announce the preview", 0, politePreviewCount())

        // A date change: the preview is announced again.
        composeRule.runOnIdle {
            viewModel.updateDate(AddExpenseSheetHarness.FIXED_NOW_MS - 3L * 24 * 60 * 60 * 1000)
        }
        composeRule.waitForIdle()
        composeRule.onNode(hasText("10 Nov", substring = true)).assertExists()
        assertEquals("a date change announces the preview again", 1, politePreviewCount())

        // And the keystroke after it is silent again.
        composeRule.runOnIdle { viewModel.updateAmount("1000") }
        composeRule.waitForIdle()
        assertEquals(0, politePreviewCount())
    }
}
