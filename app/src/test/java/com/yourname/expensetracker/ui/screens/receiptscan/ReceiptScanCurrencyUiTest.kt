package com.yourname.expensetracker.ui.screens.receiptscan

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.SuggestedValue
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.RateRefusalReason
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockOrigin
import com.yourname.expensetracker.ui.components.currency.CurrencyBlockVariant
import com.yourname.expensetracker.ui.components.currency.CurrencyOpenSettingRequest
import com.yourname.expensetracker.ui.components.currency.CurrencyRateFixAction
import com.yourname.expensetracker.ui.components.currency.CurrencyRegionUi
import java.util.Locale
import java.util.TimeZone
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
 * manual-entry-currency C2: the visible wiring of [ReceiptScanScreen] to the currency state of the REAL
 * [ReceiptScanViewModel] (built by [ReceiptScanScreenHarness]): Save disabled with a spoken reason for an Unset
 * or invalid currency/amount, the SAVE-origin card plus the kept line after a rate refusal, the control's home
 * code, the one-shot change-currency request, the open-setting confirm (D-C-3) and the quick-save refusal.
 *
 * A tall viewport keeps the whole review column on screen. Synthetic data only; the Locale and TimeZone are
 * restored and every viewModelScope is cancelled after each test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], qualifiers = "w411dp-h2000dp")
class ReceiptScanCurrencyUiTest {

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
    private val originalZone: TimeZone = TimeZone.getDefault()
    private val harness = ReceiptScanScreenHarness(home = "EUR")
    private val deps get() = harness.deps
    private lateinit var model: ReceiptScanViewModel
    private var openSettingCalls = 0
    private var fontScale by mutableStateOf(1.0f)

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        try {
            harness.close()
        } finally {
            TimeZone.setDefault(originalZone)
            Locale.setDefault(originalLocale)
        }
    }

    // ---- helpers ----

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Application>().getString(resId, *args)

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    /** Runs [block] on the UI thread (a view model call) and lets the UI settle. */
    private fun onModel(block: () -> Unit) {
        composeRule.runOnIdle(block)
        idle()
    }

    private fun showReview(receipt: com.yourname.expensetracker.data.database.entity.ScannedReceipt) {
        model = harness.createViewModel()
        harness.scan(model, receipt)
        showScreen()
    }

    private fun showScreen() {
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            val density = remember(baseDensity, fontScale) { Density(baseDensity.density, fontScale) }
            CompositionLocalProvider(LocalDensity provides density) {
                MaterialTheme {
                    ReceiptScanScreen(
                        onDismiss = {},
                        onOpenExchangeRateSetting = { openSettingCalls++ },
                        viewModel = model
                    )
                }
            }
        }
        idle()
    }

    private fun refuse(reason: RateRefusalReason) {
        deps.saveOutcome = {
            kotlin.Result.failure(ReceiptLifecycleCoordinator.RateNotEligibleException(reason, null))
        }
    }

    private val saveNode get() = composeRule.onNodeWithTag(TAG_RECEIPT_SAVE)

    private fun stateDescription(text: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    private val controlMatcher: SemanticsMatcher
        get() = hasContentDescription(string(R.string.mec_control_cd, "ZZZ").substringBefore("ZZZ"), substring = true) and
            hasClickAction()

    private fun rowFor(code: String): SemanticsMatcher = SemanticsMatcher("row $code") { node ->
        val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        node.config.contains(SemanticsActions.OnClick) && descriptions.any { it == code || it.startsWith("$code, ") }
    }

    private val anyRow: SemanticsMatcher = SemanticsMatcher("currency picker row") { node ->
        val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        node.config.contains(SemanticsActions.OnClick) &&
            descriptions.any { Regex("^[A-Z]{3}(, .*)?$").matches(it) }
    }

    private fun pickerRowCount(): Int = composeRule.onAllNodes(anyRow).fetchSemanticsNodes().size

    private fun waitUntilNoRows() {
        composeRule.waitUntil(timeoutMillis = 5_000) { pickerRowCount() == 0 }
    }

    private fun quickSaveState(): ReceiptScanState = ReceiptScanState(
        step = ScanStep.REVIEW, receiptId = 17L, editMerchant = "", editAmount = "",
        editCurrency = "USD", editDate = MEC_DATE, receiptQuickSaveEnabled = true, ocrConfidence = 0.8f,
        receiptAssistState = AiLoadState.Ready(
            ReceiptAssistSuggestion(merchant = SuggestedValue("Lidl"), total = SuggestedValue(12.34))
        )
    )

    private val openSettingText: String get() = string(R.string.mec_action_open_setting)
    private val dialogOpenSetting: SemanticsMatcher
        get() = hasText(openSettingText) and hasAnyAncestor(isDialog())
    private val dialogCancel: SemanticsMatcher
        get() = hasText(string(R.string.cancel_button)) and hasAnyAncestor(isDialog())

    // ---- 1. Unset proposal: Save disabled with a spoken reason ----

    @Test
    fun unsetProposal_disablesSave_withTheChooseCurrencyReason_andShowsTheUnsetControl() {
        showReview(mecReceipt(currency = "BGN"))

        saveNode.assertIsNotEnabled()
        saveNode.assert(stateDescription(string(R.string.mec_save_disabled_currency)))
        composeRule.onNode(hasContentDescription(string(R.string.mec_control_unset)) and hasClickAction()).assertExists()
        assertTrue(deps.requests.isEmpty())
    }

    @Test
    fun unsetProposal_pickingASelectableCurrencyFromTheControl_enablesSave() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        showReview(mecReceipt(currency = "BGN"))
        saveNode.assertIsNotEnabled()

        composeRule.onNode(hasContentDescription(string(R.string.mec_control_unset)) and hasClickAction()).performClick()
        idle()
        composeRule.onNode(rowFor("USD")).performSemanticsAction(SemanticsActions.OnClick)
        idle()
        waitUntilNoRows()

        assertEquals("USD", model.state.value.editCurrency)
        saveNode.assertIsEnabled()
        saveNode.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun homeCurrencyReceipt_saveIsEnabled_withoutAStateDescription() {
        showReview(mecReceipt(currency = "EUR"))

        saveNode.assertIsEnabled()
        saveNode.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    // ---- 2. RateNotEligible: SAVE card + kept line; the form keeps its values ----

    @Test
    fun rateNotEligibleSave_showsTheSaveCardAndTheKeptLine_andKeepsTheForm() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        showReview(mecReceipt(currency = "USD", total = 10.0, merchant = "Merchant"))
        saveNode.assertIsEnabled()
        composeRule.onNodeWithTag(TAG_RECEIPT_KEPT).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_block_none)).assertDoesNotExist()

        // The rate disappears between the preview and the tap: the coordinator refuses.
        deps.store.rows.clear()
        refuse(RateRefusalReason.NO_RATE)
        saveNode.performScrollTo().performClick()
        idle()

        assertEquals(1, deps.requests.size)
        composeRule.onNodeWithTag(TAG_RECEIPT_KEPT)
            .assertExists()
            .assert(hasText(string(R.string.mec_receipt_kept)))
            // Review F1: the SAVE card is the assertive announcement; the kept line is plain text.
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        composeRule.onNode(
            hasText(string(R.string.mec_block_none)) and
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive)
        ).assertExists()
        composeRule.onNodeWithTag(TAG_RECEIPT_RATE_REGION).assertExists()

        // The review stays and the form keeps its values.
        val state = model.state.value
        assertEquals(ScanStep.REVIEW, state.step)
        assertEquals("Merchant", state.editMerchant)
        assertEquals("10.00", state.editAmount)
        assertEquals("USD", state.editCurrency)
        assertEquals(CurrencyRegionUi.Blocked(CurrencyBlockVariant.NONE, null, CurrencyBlockOrigin.SAVE), state.currencyRegion)
        composeRule.onNodeWithTag(TAG_RECEIPT_AMOUNT).assert(hasText("10.00", substring = true))

        // Save is disabled with the rate reason until the user changes something.
        saveNode.assertIsNotEnabled()
        saveNode.assert(stateDescription(string(R.string.mec_save_disabled_rate)))
    }

    @Test
    fun f1_keptLine_rendersOnceDirectlyUnderTheRateRegion_aboveSave_andIsNotLive() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        showReview(mecReceipt(currency = "USD", total = 10.0, merchant = "Merchant"))
        composeRule.onAllNodesWithTag(TAG_RECEIPT_KEPT).assertCountEquals(0)

        deps.store.rows.clear()
        refuse(RateRefusalReason.NO_RATE)
        saveNode.performScrollTo().performClick()
        idle()

        composeRule.onAllNodesWithTag(TAG_RECEIPT_KEPT).assertCountEquals(1)
        composeRule.onNodeWithTag(TAG_RECEIPT_KEPT)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        // The SAVE-origin card remains the single assertive announcement.
        composeRule.onNode(
            hasText(string(R.string.mec_block_none)) and
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive)
        ).assertExists()

        val density = ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics.density
        val region = composeRule.onNodeWithTag(TAG_RECEIPT_RATE_REGION).fetchSemanticsNode().boundsInRoot
        val kept = composeRule.onNodeWithTag(TAG_RECEIPT_KEPT).fetchSemanticsNode().boundsInRoot
        val amount = composeRule.onNodeWithTag(TAG_RECEIPT_AMOUNT).fetchSemanticsNode().boundsInRoot
        val save = composeRule.onNodeWithTag(TAG_RECEIPT_SAVE).fetchSemanticsNode().boundsInRoot

        assertTrue("kept line sits below the amount field", kept.top >= amount.bottom)
        assertTrue("kept line starts at or below the rate region bottom", kept.top >= region.bottom)
        assertTrue("kept line is directly under the region (only a small spacer between)", kept.top - region.bottom <= 12f * density)
        assertTrue("kept line is above the Save button, not in the bottom block", kept.bottom <= save.top)
    }

    @Test
    fun f1_keptLine_isAbsentWithoutARateRefusal_andForAnOtherSaveResult() {
        showReview(mecReceipt(currency = "EUR", total = 10.0))
        composeRule.onAllNodesWithTag(TAG_RECEIPT_KEPT).assertCountEquals(0)

        onModel {
            harness.setState(
                model,
                model.state.value.copy(saveResult = SaveReceiptResult.Error("Receipt save failed (UNKNOWN_ERROR)"))
            )
        }
        composeRule.onAllNodesWithTag(TAG_RECEIPT_KEPT).assertCountEquals(0)
    }

    @Test
    fun f2_noEcho_unsetBgnProposal_neverShowsTheTokenInAnyNode_evenWithTheItemBreakdown() {
        showReview(mecReceipt(currency = "BGN", total = 10.0, merchant = "Merchant"))
        onModel {
            harness.setState(
                model,
                model.state.value.copy(
                    itemCategorizations = listOf(syntheticItem()),
                    showItemBreakdown = true
                )
            )
        }
        assertEquals(CurrencyRegionUi.Unset(com.yourname.expensetracker.domain.currency.CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE), model.state.value.currencyRegion)
        assertEquals(null, model.state.value.editCurrency)

        val texts = allNodeStrings()
        assertTrue("control: the item breakdown is rendered", texts.any { it.contains("Synthetic item") })
        assertTrue("no node text or description echoes the OCR token: $texts", texts.none { it.contains("BGN") })
    }

    private fun syntheticItem() = com.yourname.expensetracker.domain.dto.ReceiptItemCategorizationSnapshot(
        id = 1L, receiptId = 17L, expenseId = null, itemDescription = "Synthetic item", itemAmount = 4.5,
        suggestedCategoryId = null, suggestedCategoryName = null, confidence = 0.5f, aiRationale = null,
        alternativeCategoriesJson = null, userCorrectedCategoryId = null, userCorrectedCategoryName = null,
        userCorrectedAt = null, taxAmount = null, isNewCategorySuggestion = false, createdAt = 0L, updatedAt = 0L
    )

    /** Every text, editable text, content description and state description in the unmerged tree. */
    private fun allNodeStrings(): List<String> =
        composeRule.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                val config = node.config
                config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                    listOfNotNull(
                        config.getOrNull(SemanticsProperties.EditableText)?.text,
                        config.getOrNull(SemanticsProperties.StateDescription)
                    ) +
                    config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }

    @Test
    fun rateNotEligibleSave_theKeptLineLeavesOnAnAmountEdit_butTheAmountIndependentCardStays() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        showReview(mecReceipt(currency = "USD", total = 10.0))
        deps.store.rows.clear()
        refuse(RateRefusalReason.NO_RATE)
        saveNode.performScrollTo().performClick()
        idle()
        composeRule.onNodeWithTag(TAG_RECEIPT_KEPT).assertExists()

        onModel { model.updateAmount("11") }

        composeRule.onNodeWithTag(TAG_RECEIPT_KEPT).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_block_none)).assertExists()
        saveNode.assertIsNotEnabled()
    }

    // ---- 3. AmountInvalid: supporting text + Save disabled with the reason ----

    @Test
    fun amountInvalid_showsTheSupportingText_andDisablesSaveWithThatReason_thenRecovers() {
        deps.store.putRate("JPY", 0.006, mecDay(14))
        showReview(mecReceipt(currency = "EUR", total = 10.0))
        onModel {
            model.updateCurrency("JPY")
            model.updateAmount("1500.5")
        }

        val message = string(R.string.mec_amount_no_decimals, "JPY")
        composeRule.onAllNodesWithText(message).assertCountEquals(1)
        saveNode.assertIsNotEnabled()
        saveNode.assert(stateDescription(message))
        assertEquals(CurrencyRegionUi.AmountInvalid(com.yourname.expensetracker.ui.components.currency.CurrencyAmountInvalidKind.PRECISION_EXCEEDED), model.state.value.currencyRegion)

        onModel { model.updateAmount("1500") }

        composeRule.onAllNodesWithText(message).assertCountEquals(0)
        saveNode.assertIsEnabled()
        saveNode.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    // ---- 4. The control's home code is the real home, not options.first() ----

    @Test
    fun control_homeHeaderAndHomeRowUseTheRealHome_evenWhenOptionsDoNotStartWithHome() {
        showReview(mecReceipt(currency = "EUR"))
        onModel {
            harness.setState(
                model,
                model.state.value.copy(currencyOptions = listOf("AUD", "EUR", "GBP", "USD"), homeCurrency = "EUR")
            )
        }

        composeRule.onNode(controlMatcher).performClick()
        idle()

        composeRule.onNodeWithText(string(R.string.mec_sheet_home)).assertExists()
        val eurY = composeRule.onNode(rowFor("EUR")).fetchSemanticsNode().positionInRoot.y
        val audY = composeRule.onNode(rowFor("AUD")).fetchSemanticsNode().positionInRoot.y
        val gbpY = composeRule.onNode(rowFor("GBP")).fetchSemanticsNode().positionInRoot.y
        val usdY = composeRule.onNode(rowFor("USD")).fetchSemanticsNode().positionInRoot.y
        assertTrue("home EUR is listed first, not AUD (the first option)", eurY < audY)
        assertTrue("the others follow by code", audY < gbpY && gbpY < usdY)
    }

    @Test
    fun control_withAnUnresolvedHome_showsNoHomeHeader() {
        showReview(mecReceipt(currency = "EUR"))
        onModel {
            harness.setState(
                model,
                model.state.value.copy(currencyOptions = listOf("AUD", "EUR", "USD"), homeCurrency = null)
            )
        }

        composeRule.onNode(controlMatcher).performClick()
        idle()

        assertTrue(pickerRowCount() > 0)
        composeRule.onNodeWithText(string(R.string.mec_sheet_home)).assertDoesNotExist()
    }

    // ---- 5. Change currency from the card opens the control, once ----

    @Test
    fun changeCurrencyFromTheCard_opensTheControl_andDoesNotReopenAfterItIsClosedAndTheLayoutChanges() {
        showReview(mecReceipt(currency = "USD"))
        assertEquals(0, pickerRowCount())

        composeRule.onNodeWithText(string(R.string.mec_action_change_currency)).performScrollTo().performClick()
        idle()
        assertTrue("the picker opened", pickerRowCount() > 0)

        composeRule.onNode(hasContentDescription(string(R.string.close_content_description)) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        idle()
        waitUntilNoRows()

        // A layout change (the control moves to the stacked slot) must not replay the consumed request.
        fontScale = 1.4f
        idle()
        assertEquals("the request is one-shot", 0, pickerRowCount())
        onModel { model.updateAmount("11") }
        assertEquals(0, pickerRowCount())
    }

    // ---- 6. Open-setting confirm: reset then navigate once; a cancel keeps the draft ----

    @Test
    fun confirmedOpenSetting_clearsTheDraft_andCallsTheHostExactlyOnce() {
        showReview(mecReceipt(currency = "USD", total = 10.0, merchant = "Merchant"))

        composeRule.onNodeWithText(openSettingText).performScrollTo().performClick()
        idle()
        composeRule.onNodeWithText(string(R.string.mec_open_setting_confirm_title)).assertExists()
        assertEquals("nothing navigates before the user confirms", 0, openSettingCalls)
        assertEquals("Merchant", model.state.value.editMerchant)

        composeRule.onNode(dialogOpenSetting).performSemanticsAction(SemanticsActions.OnClick)
        idle()
        idle()

        assertEquals("the host callback runs exactly once", 1, openSettingCalls)
        val state = model.state.value
        assertEquals(ScanStep.CAPTURE, state.step)
        assertEquals("", state.editMerchant)
        assertEquals("", state.editAmount)
        assertEquals("EUR", state.editCurrency)
        assertEquals(CurrencyOpenSettingRequest.NONE, state.openSettingRequest)
        composeRule.onNodeWithText(string(R.string.mec_open_setting_confirm_title)).assertDoesNotExist()
        assertTrue(deps.requests.isEmpty())
    }

    @Test
    fun cancelledOpenSetting_keepsTheWholeDraft_andNeverCallsTheHost() {
        showReview(mecReceipt(currency = "USD", total = 10.0, merchant = "Merchant"))

        composeRule.onNodeWithText(openSettingText).performScrollTo().performClick()
        idle()
        composeRule.onNode(dialogCancel).performSemanticsAction(SemanticsActions.OnClick)
        idle()

        assertEquals(0, openSettingCalls)
        val state = model.state.value
        assertEquals(ScanStep.REVIEW, state.step)
        assertEquals("Merchant", state.editMerchant)
        assertEquals("10.00", state.editAmount)
        assertEquals("USD", state.editCurrency)
        assertEquals(CurrencyOpenSettingRequest.NONE, state.openSettingRequest)
        composeRule.onNodeWithText(string(R.string.mec_open_setting_confirm_title)).assertDoesNotExist()
    }

    @Test
    fun openSettingWithNoAmount_navigatesOnceWithoutADialog_andKeepsTheReview() {
        showReview(mecReceipt(currency = "USD", total = null, merchant = "Merchant"))
        assertEquals("", model.state.value.editAmount)

        composeRule.onNodeWithText(openSettingText).performScrollTo().performClick()
        idle()

        assertEquals(1, openSettingCalls)
        composeRule.onNodeWithText(string(R.string.mec_open_setting_confirm_title)).assertDoesNotExist()
        assertEquals(CurrencyOpenSettingRequest.NONE, model.state.value.openSettingRequest)
        assertEquals("Merchant", model.state.value.editMerchant)
    }

    // ---- 6b. MEC 1b S3: the rate-history download action in the receipt rate region ----

    private fun enableRateDownload() {
        deps.privacyFlow.value = com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState.Loaded(
            com.yourname.expensetracker.domain.privacy.PrivacySettings(exchangeRateDownloadEnabled = true)
        )
        idle()
    }

    /** USD receipt dated 2023-11-09; the only stored rate is dated 2023-11-14, so the remedy is a history download. */
    private fun showDownloadableReview() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        showReview(mecReceipt(currency = "USD", total = 10.0, date = MEC_DATE - 5 * MEC_DAY_MS))
    }

    @Test
    fun mec1b_downloadableReview_showsTheDisclosureAndTheDownloadButton_andTheTapCallsTheViewModelOnce() {
        enableRateDownload()
        showDownloadableReview()
        assertEquals(CurrencyRateFixAction.DOWNLOAD_HISTORY, model.state.value.rateFixAction)
        deps.ratesRepo.historyOutcome =
            com.yourname.expensetracker.domain.currency.CurrencyRateHistoryOutcome.Failed(
                com.yourname.expensetracker.domain.currency.RateHistoryFailureReason.OFFLINE
            )

        composeRule.onNodeWithText(string(R.string.mec_history_disclosure)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_history_action)).assertExists()
        composeRule.onNodeWithText(openSettingText).assertDoesNotExist()
        assertTrue("nothing downloads before the tap", deps.ratesRepo.historyCalls.isEmpty())

        composeRule.onNodeWithText(string(R.string.mec_history_action)).performScrollTo().performClick()
        idle()

        assertEquals(listOf("EUR"), deps.ratesRepo.historyCalls)
        assertEquals(
            com.yourname.expensetracker.ui.components.currency.CurrencyHistoryStatus.FAILED_OFFLINE,
            model.state.value.historyStatus
        )
        composeRule.onNodeWithText(string(R.string.mec_history_offline)).assertExists()
        assertTrue(deps.requests.isEmpty())
    }

    @Test
    fun mec1b_settingOff_showsOpenSettingAndNoDownloadButtonOrDisclosure() {
        showDownloadableReview()

        composeRule.onNodeWithText(openSettingText).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_history_action)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_history_disclosure)).assertDoesNotExist()
        assertTrue(deps.ratesRepo.historyCalls.isEmpty())
    }

    // ---- 7. Quick-save refusal closes the dialog and leaves the card in review ----

    @Test
    fun quickSaveRefusal_closesTheDialog_andLeavesTheSaveCardAndTheKeptLineInReview() {
        deps.store.putRate("USD", 0.9, mecDay(14))
        model = harness.createViewModel()
        harness.setState(model, quickSaveState())
        model.updateCurrency("USD")
        showScreen()
        composeRule.onNodeWithTag(TAG_RECEIPT_QUICK_SAVE).assertIsEnabled()

        composeRule.onNodeWithTag(TAG_RECEIPT_QUICK_SAVE).performScrollTo().performClick()
        idle()
        composeRule.onNodeWithText(string(R.string.receipt_quick_save_title)).assertExists()

        deps.store.rows.clear()
        refuse(RateRefusalReason.NO_RATE)
        composeRule.onNodeWithTag(TAG_RECEIPT_QUICK_SAVE_CONFIRM).performSemanticsAction(SemanticsActions.OnClick)
        idle()

        assertEquals(1, deps.requests.size)
        composeRule.onNodeWithText(string(R.string.receipt_quick_save_title)).assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_RECEIPT_KEPT).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_block_none)).assertExists()
        assertEquals(ScanStep.REVIEW, model.state.value.step)
        assertEquals(null, model.state.value.quickSavePreview)
        assertEquals("Lidl", model.state.value.editMerchant)
        assertEquals("12.34", model.state.value.editAmount)
    }

    @Test
    fun quickSaveButton_isDisabledWithTheRateReason_whileTheRateIsMissing() {
        model = harness.createViewModel()
        harness.setState(model, quickSaveState())
        model.updateCurrency("USD") // no stored rate: a Blocked preview
        showScreen()

        composeRule.onNodeWithTag(TAG_RECEIPT_QUICK_SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(TAG_RECEIPT_QUICK_SAVE).assert(stateDescription(string(R.string.mec_save_disabled_rate)))
        composeRule.onNodeWithText(string(R.string.receipt_quick_save_title)).assertDoesNotExist()
        assertTrue(deps.requests.isEmpty())
    }
}
