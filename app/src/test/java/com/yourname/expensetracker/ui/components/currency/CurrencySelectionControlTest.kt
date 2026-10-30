package com.yourname.expensetracker.ui.components.currency

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
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
 * manual-entry-currency B2b (ux-flows section 2, INV-10): [CurrencySelectionControl] and its picker sheet.
 *
 * The caller supplies the already-filtered option list, so a currency that is not selectable is simply not in
 * the list: the control must omit it (never grey it). Rows are found by their description, which starts with
 * the ISO code followed by a comma; the control's own description starts with "Currency:" so the two never
 * collide. Search folding is exercised on ISO codes, which do not depend on the platform's display names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], qualifiers = "w411dp-h2000dp")
class CurrencySelectionControlTest {

    private val composeRule = createComposeRule()

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
    private val selections = mutableListOf<String>()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Application>().getString(resId, *args)

    private val controlMatcher: SemanticsMatcher
        get() = hasContentDescription(string(R.string.mec_control_cd, "ZZZ").substringBefore("ZZZ"), substring = true) and
            hasClickAction()

    private fun show(
        selectedCode: String? = "EUR",
        options: List<String> = listOf("USD", "GBP", "EUR", "JPY", "AUD"),
        homeCode: String? = "EUR",
        enabled: Boolean = true,
        isUnset: Boolean = false,
        readOnly: Boolean = false
    ) {
        composeRule.setContent {
            MaterialTheme {
                CurrencySelectionControl(
                    selectedCode = selectedCode,
                    options = options,
                    homeCode = homeCode,
                    enabled = enabled,
                    isUnset = isUnset,
                    readOnly = readOnly,
                    onSelect = { selections += it }
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun openPicker() {
        composeRule.onNode(controlMatcher).performClick()
        composeRule.waitForIdle()
    }

    /** A picker row: clickable, description starts "CODE," (or is exactly the code when no name exists). */
    private val anyRow: SemanticsMatcher = SemanticsMatcher("currency picker row") { node ->
        val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        node.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
            descriptions.any { Regex("^[A-Z]{3}(, .*)?$").matches(it) }
    }

    private fun rowFor(code: String): SemanticsMatcher = SemanticsMatcher("row $code") { node ->
        val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        node.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
            descriptions.any { it == code || it.startsWith("$code, ") }
    }

    private fun visibleRowCodes(): List<String> =
        composeRule.onAllNodes(anyRow).fetchSemanticsNodes().map { node ->
            node.config[SemanticsProperties.ContentDescription].first().take(3)
        }

    /** The sheet leaves with an exit animation, so rows can outlive the click by a few frames. */
    private fun waitUntilNoRows() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(anyRow).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun typeSearch(text: String) {
        composeRule.onNode(hasSetTextAction()).performTextInput(text)
        composeRule.waitForIdle()
    }

    // ---- list order: home first, then the others by code ------------------------------------------------------

    @Test
    fun inv10_pickerListsTheHomeCurrencyFirst_thenTheOthersByCode() {
        show(options = listOf("USD", "GBP", "EUR", "JPY", "AUD"), homeCode = "EUR")
        openPicker()

        assertEquals(listOf("EUR", "AUD", "GBP", "JPY", "USD"), visibleRowCodes())
        composeRule.onNodeWithText(string(R.string.mec_sheet_home)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_sheet_other)).assertExists()
    }

    @Test
    fun inv10_homeNotAmongTheOptions_listsOnlyTheOthersByCode_withoutAHomeHeader() {
        show(selectedCode = "USD", options = listOf("USD", "GBP", "AUD"), homeCode = "EUR")
        openPicker()

        assertEquals(listOf("AUD", "GBP", "USD"), visibleRowCodes())
        composeRule.onNodeWithText(string(R.string.mec_sheet_home)).assertDoesNotExist()
    }

    // ---- omitted codes are absent, not greyed ------------------------------------------------------------------

    @Test
    fun inv10_aCodeOmittedFromTheOptions_hasNoRowAtAll_notEvenADisabledOne() {
        show(options = listOf("USD", "EUR", "GBP"), homeCode = "EUR")
        openPicker()

        composeRule.onAllNodes(rowFor("CHF")).assertCountEquals(0)
        composeRule.onAllNodes(rowFor("JPY")).assertCountEquals(0)
        composeRule.onAllNodes(rowFor("USD")).assertCountEquals(1)
        assertTrue(
            "every row that exists is enabled",
            composeRule.onAllNodes(anyRow).fetchSemanticsNodes().none {
                it.config.contains(SemanticsProperties.Disabled)
            }
        )
    }

    // ---- search folds case and accents ---------------------------------------------------------------------------

    @Test
    fun search_foldsCase() {
        show()
        openPicker()

        typeSearch("UsD")

        assertEquals(listOf("USD"), visibleRowCodes())
    }

    @Test
    fun search_foldsAccents() {
        show()
        openPicker()

        typeSearch("ÚSD") // U with an acute accent, then "SD": folds to "usd"

        assertEquals(listOf("USD"), visibleRowCodes())
    }

    @Test
    fun search_ignoresSurroundingWhitespace() {
        show()
        openPicker()

        typeSearch("  gbp ")

        assertEquals(listOf("GBP"), visibleRowCodes())
    }

    @Test
    fun search_withNoMatch_showsTheNoResultsText_andNoRows() {
        show()
        openPicker()

        typeSearch("zzzzqq")

        composeRule.onNodeWithText(string(R.string.mec_sheet_no_results)).assertExists()
        composeRule.onAllNodes(anyRow).assertCountEquals(0)
    }

    @Test
    fun search_withMatches_doesNotShowTheNoResultsText() {
        show()
        openPicker()

        typeSearch("eur")

        composeRule.onNodeWithText(string(R.string.mec_sheet_no_results)).assertDoesNotExist()
        assertEquals(listOf("EUR"), visibleRowCodes())
    }

    // ---- selection --------------------------------------------------------------------------------------------------

    @Test
    fun picking_aRow_callsOnSelectExactlyOnce_andClosesThePicker() {
        show()
        openPicker()

        // Invoke the row's click action directly: a pointer click depends on the sheet's animated offset, which is
        // not settled under Robolectric and can land on the scrim instead of the row.
        composeRule.onNode(rowFor("GBP")).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        assertEquals(listOf("GBP"), selections)
        waitUntilNoRows()
        composeRule.onAllNodes(anyRow).assertCountEquals(0)
    }

    @Test
    fun closing_thePicker_doesNotCallOnSelect() {
        show()
        openPicker()

        composeRule.onNode(hasContentDescription(string(R.string.close_content_description)))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        assertTrue("closing selects nothing: $selections", selections.isEmpty())
        waitUntilNoRows()
        composeRule.onAllNodes(anyRow).assertCountEquals(0)
    }

    @Test
    fun theSelectedRow_isMarkedSelected_andOthersAreNot() {
        show(selectedCode = "GBP")
        openPicker()

        composeRule.onNode(rowFor("GBP")).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.add_expense_selected))
        )
        composeRule.onNode(rowFor("USD")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    // ---- control states ---------------------------------------------------------------------------------------------

    @Test
    fun enabledControl_isClickable() {
        show(enabled = true)

        composeRule.onNode(controlMatcher).assertIsEnabled()
    }

    @Test
    fun disabledControl_isNotEnabled_andOpensNoPicker() {
        show(enabled = false)

        val byDescription = hasContentDescription(
            string(R.string.mec_control_cd, "ZZZ").substringBefore("ZZZ"),
            substring = true
        )
        composeRule.onNode(byDescription).assertIsNotEnabled()
        composeRule.onNode(byDescription).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(anyRow).assertCountEquals(0)
    }

    @Test
    fun unsetControl_showsTheChooseCurrencyText_andIsStillOpenable() {
        show(selectedCode = null, isUnset = true)

        composeRule.onNodeWithText(string(R.string.mec_control_unset)).assertExists()
        composeRule.onNode(hasContentDescription(string(R.string.mec_control_unset)) and hasClickAction()).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("EUR", "AUD", "GBP", "JPY", "USD"), visibleRowCodes())
    }

    @Test
    fun readOnlyControl_hasNoClickAction_andTheReadOnlyDescription() {
        show(selectedCode = "EUR", readOnly = true)

        val node = composeRule.onNode(hasContentDescription(string(R.string.mec_control_readonly_cd, "EUR")))
        node.assertExists()
        node.assertHasNoClickAction()
        composeRule.onAllNodes(anyRow).assertCountEquals(0)
    }

    // ---- openRequest ("Change currency" on the blocked card) -------------------------------------------------------

    @Test
    fun openRequest_bump_opensThePicker_whenEnabled() {
        val request = mutableIntStateOf(0)
        composeRule.setContent {
            MaterialTheme {
                CurrencySelectionControl(
                    selectedCode = "EUR",
                    options = listOf("USD", "EUR"),
                    homeCode = "EUR",
                    enabled = true,
                    isUnset = false,
                    readOnly = false,
                    onSelect = { selections += it },
                    openRequest = request.intValue
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodes(anyRow).assertCountEquals(0)

        composeRule.runOnIdle { request.intValue = 1 }
        composeRule.waitForIdle()

        assertEquals(listOf("EUR", "USD"), visibleRowCodes())
    }

    @Test
    fun openRequest_bump_doesNotOpenADisabledControl() {
        val request = mutableIntStateOf(0)
        composeRule.setContent {
            MaterialTheme {
                CurrencySelectionControl(
                    selectedCode = "EUR",
                    options = listOf("USD", "EUR"),
                    homeCode = "EUR",
                    enabled = false,
                    isUnset = false,
                    readOnly = false,
                    onSelect = { selections += it },
                    openRequest = request.intValue
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { request.intValue = 1 }
        composeRule.waitForIdle()

        composeRule.onAllNodes(anyRow).assertCountEquals(0)
    }

    @Test
    fun openRequest_isConsumedOnce_andLayoutSlotFlipDoesNotReopenThePicker() {
        val request = mutableIntStateOf(0)
        val stacked = mutableIntStateOf(1)
        var consumed = 0
        composeRule.setContent {
            MaterialTheme {
                val control: @Composable () -> Unit = {
                    CurrencySelectionControl(
                        selectedCode = "EUR",
                        options = listOf("USD", "EUR"),
                        homeCode = "EUR",
                        enabled = true,
                        isUnset = false,
                        readOnly = false,
                        onSelect = { selections += it },
                        openRequest = request.intValue,
                        onOpenRequestConsumed = {
                            consumed++
                            request.intValue = 0
                        }
                    )
                }
                // Stacked and row layouts place the control in different composition slots, like the add sheet.
                if (stacked.intValue == 1) Column { control() } else Row { control() }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { request.intValue = 1 }
        composeRule.waitForIdle()
        assertEquals(listOf("EUR", "USD"), visibleRowCodes())
        assertEquals(1, consumed)
        assertEquals(0, request.intValue)

        // Layout flip: the control re-enters composition in a new slot with a fresh closed state.
        composeRule.runOnIdle { stacked.intValue = 0 }
        composeRule.waitForIdle()

        composeRule.onAllNodes(anyRow).assertCountEquals(0)
        assertEquals(1, consumed)
    }
}
