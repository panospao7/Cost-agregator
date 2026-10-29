package com.yourname.expensetracker.ui.components.currency

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.currency.CurrencyUnsetReason
import com.yourname.expensetracker.domain.currency.RateSourceLabel
import java.time.LocalDate
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
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
 * manual-entry-currency B2b (ux-flows sections 3, 4, 13): [CurrencyRateRegionContent] renders one region
 * variant at a time and reports user actions only as plain callbacks.
 *
 * Money rules pinned here: an unavailable converted amount is never shown as a figure (never "zero"); the
 * ratesSetting UNKNOWN is treated as OFF (fail closed); the card for a save refusal is announced assertively,
 * any other card politely.
 *
 * Rate dates are public ECB dates without a time zone; with `today` in the same year the year is omitted,
 * otherwise it is shown. Expected strings are written out for the pinned US-English locale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CurrencyRateRegionContentTest {

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
    private var refreshCalls = 0
    private var openSettingCalls = 0
    private var changeCurrencyCalls = 0

    private val today = LocalDate.of(2026, 10, 29)
    private val rateDate = LocalDate.of(2026, 10, 27)

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

    private fun show(
        region: CurrencyRegionUi,
        ratesSetting: CurrencyRatesSetting = CurrencyRatesSetting.OFF,
        rateRefresh: CurrencyRefreshStatus = CurrencyRefreshStatus.IDLE,
        ratesToday: LocalDate? = today,
        suppressHomeUnresolvedCard: Boolean = false
    ) {
        composeRule.setContent {
            MaterialTheme {
                CurrencyRateRegionContent(
                    region = region,
                    ratesSetting = ratesSetting,
                    rateRefresh = rateRefresh,
                    ratesToday = ratesToday,
                    suppressHomeUnresolvedCard = suppressHomeUnresolvedCard,
                    onRefresh = { refreshCalls++ },
                    onOpenSetting = { openSettingCalls++ },
                    onChangeCurrency = { changeCurrencyCalls++ }
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun blocked(
        variant: CurrencyBlockVariant,
        latest: LocalDate? = null,
        origin: CurrencyBlockOrigin = CurrencyBlockOrigin.PREVIEW
    ) = CurrencyRegionUi.Blocked(variant, latest, origin)

    private fun assertNothingRendered() {
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).assertCountEquals(0)
    }

    private fun liveRegionCount(mode: LiveRegionMode): Int =
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode))
            .fetchSemanticsNodes().size

    // ---- variants that render nothing ---------------------------------------------------------------------------

    @Test
    fun loading_rendersNothing() {
        show(CurrencyRegionUi.Loading)
        assertNothingRendered()
    }

    @Test
    fun readOnly_rendersNothing() {
        show(CurrencyRegionUi.ReadOnly)
        assertNothingRendered()
    }

    @Test
    fun home_rendersNothing() {
        show(CurrencyRegionUi.Home)
        assertNothingRendered()
    }

    @Test
    fun unset_rendersNothing_inTheRegion() {
        show(CurrencyRegionUi.Unset(CurrencyUnsetReason.NONE_PROPOSED))
        assertNothingRendered()
    }

    @Test
    fun amountInvalid_notPositive_rendersNothing_inTheRegion() {
        show(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.NOT_POSITIVE))
        assertNothingRendered()
    }

    @Test
    fun amountInvalid_precisionExceeded_rendersNothing_inTheRegion() {
        show(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.PRECISION_EXCEEDED))
        assertNothingRendered()
    }

    @Test
    fun amountInvalid_aboveCap_rendersNothing_inTheRegion() {
        show(CurrencyRegionUi.AmountInvalid(CurrencyAmountInvalidKind.ABOVE_CAP))
        assertNothingRendered()
    }

    @Test
    fun pending_rendersNothing() {
        show(CurrencyRegionUi.Pending)
        assertNothingRendered()
    }

    // ---- Evaluating -------------------------------------------------------------------------------------------------

    @Test
    fun evaluating_showsCheckingExchangeRate_inAPoliteLiveRegion() {
        show(CurrencyRegionUi.Evaluating)

        composeRule.onNodeWithText(string(R.string.mec_preview_checking)).assertExists()
        assertEquals(1, liveRegionCount(LiveRegionMode.Polite))
        assertEquals(0, liveRegionCount(LiveRegionMode.Assertive))
    }

    // ---- Eligible: the preview line ---------------------------------------------------------------------------------

    @Test
    fun eligibleEcb_withAmount_showsTheFormattedAmountAndTheRateDate_withoutALiveRegionUntilAChangeKeyBump() {
        // F-B2-A11Y-1 (rewritten in place): opening the screen is not a change, so the first figure shown
        // under the initial announce key is not a live region.
        show(CurrencyRegionUi.Eligible(rateDate, RateSourceLabel.ECB, "€12.34", amountUnavailable = false))

        composeRule.onNodeWithText(string(R.string.mec_preview_ecb, "€12.34", "27 Oct")).assertExists()
        assertEquals("opening the screen must not announce the figure", 0, liveRegionCount(LiveRegionMode.Polite))
        assertEquals(0, liveRegionCount(LiveRegionMode.Assertive))
    }

    private fun eligible(figure: String, date: LocalDate = rateDate) =
        CurrencyRegionUi.Eligible(date, RateSourceLabel.ECB, figure, amountUnavailable = false)

    private fun showDriven(
        region: androidx.compose.runtime.MutableState<CurrencyRegionUi>,
        key: androidx.compose.runtime.MutableState<Int>
    ) {
        composeRule.setContent {
            MaterialTheme {
                CurrencyRateRegionContent(
                    region = region.value,
                    ratesSetting = CurrencyRatesSetting.OFF,
                    rateRefresh = CurrencyRefreshStatus.IDLE,
                    ratesToday = today,
                    suppressHomeUnresolvedCard = false,
                    onRefresh = { },
                    onOpenSetting = { },
                    onChangeCurrency = { },
                    announceKey = key.value
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun previewAfterACurrencyChange_isAPoliteLiveRegion() {
        val region = androidx.compose.runtime.mutableStateOf<CurrencyRegionUi>(CurrencyRegionUi.Home)
        val key = androidx.compose.runtime.mutableStateOf(0)
        showDriven(region, key)

        // A currency change bumps the key and (after Pending) shows the new figure.
        key.value = 1
        region.value = CurrencyRegionUi.Pending
        composeRule.waitForIdle()
        region.value = eligible("€12.34")
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.mec_preview_ecb, "€12.34", "27 Oct")).assertExists()
        assertEquals(1, liveRegionCount(LiveRegionMode.Polite))
    }

    @Test
    fun previewAfterACurrencyChange_staleFigureStillShownForOneFrame_isNotTheAnnouncedOne() {
        val region = androidx.compose.runtime.mutableStateOf<CurrencyRegionUi>(eligible("€10.00"))
        val key = androidx.compose.runtime.mutableStateOf(0)
        showDriven(region, key)

        // The key moves while the old figure is still displayed; the old figure is not announced.
        key.value = 1
        composeRule.waitForIdle()
        assertEquals(0, liveRegionCount(LiveRegionMode.Polite))

        region.value = CurrencyRegionUi.Pending
        composeRule.waitForIdle()
        region.value = eligible("€12.34")
        composeRule.waitForIdle()

        assertEquals(1, liveRegionCount(LiveRegionMode.Polite))
    }

    @Test
    fun anAmountKeystrokeAfterTheChange_isNotAnnounced() {
        val region = androidx.compose.runtime.mutableStateOf<CurrencyRegionUi>(CurrencyRegionUi.Home)
        val key = androidx.compose.runtime.mutableStateOf(0)
        showDriven(region, key)
        key.value = 1
        region.value = eligible("€12.34")
        composeRule.waitForIdle()
        assertEquals(1, liveRegionCount(LiveRegionMode.Polite))

        // A keystroke: same key, Pending, then a different figure.
        region.value = CurrencyRegionUi.Pending
        composeRule.waitForIdle()
        region.value = eligible("€123.40")
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.mec_preview_ecb, "€123.40", "27 Oct")).assertExists()
        assertEquals("a keystroke must not announce the figure", 0, liveRegionCount(LiveRegionMode.Polite))

        // Further keystrokes stay silent.
        region.value = CurrencyRegionUi.Pending
        composeRule.waitForIdle()
        region.value = eligible("€1,234.00")
        composeRule.waitForIdle()
        assertEquals(0, liveRegionCount(LiveRegionMode.Polite))
    }

    @Test
    fun aDateChange_announcesAgain() {
        val region = androidx.compose.runtime.mutableStateOf<CurrencyRegionUi>(eligible("€12.34"))
        val key = androidx.compose.runtime.mutableStateOf(0)
        showDriven(region, key)
        assertEquals(0, liveRegionCount(LiveRegionMode.Polite))

        // Keystroke first (silent), then a date change (key bump) re-announces.
        region.value = CurrencyRegionUi.Pending
        composeRule.waitForIdle()
        region.value = eligible("€123.40")
        composeRule.waitForIdle()
        assertEquals(0, liveRegionCount(LiveRegionMode.Polite))

        key.value = 2
        region.value = CurrencyRegionUi.Pending
        composeRule.waitForIdle()
        region.value = eligible("€123.40", date = LocalDate.of(2026, 10, 20))
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.mec_preview_ecb, "€123.40", "20 Oct")).assertExists()
        assertEquals(1, liveRegionCount(LiveRegionMode.Polite))
    }

    @Test
    fun eligibleOtherSource_withAmount_usesTheNeutralWording() {
        show(CurrencyRegionUi.Eligible(rateDate, RateSourceLabel.OTHER, "€12.34", amountUnavailable = false))

        composeRule.onNodeWithText(string(R.string.mec_preview_other, "€12.34", "27 Oct")).assertExists()
    }

    @Test
    fun eligibleEcb_withoutAnAmount_showsOnlyTheRateDate() {
        show(CurrencyRegionUi.Eligible(rateDate, RateSourceLabel.ECB, null, amountUnavailable = false))

        composeRule.onNodeWithText("ECB rate of 27 Oct").assertExists()
        composeRule.onAllNodes(hasText("≈", substring = true)).assertCountEquals(0)
    }

    @Test
    fun eligibleEcb_amountUnavailable_neverShowsAFigure_onlyTheRateDate() {
        show(CurrencyRegionUi.Eligible(rateDate, RateSourceLabel.ECB, null, amountUnavailable = true))

        composeRule.onNodeWithText("ECB rate of 27 Oct").assertExists()
        composeRule.onAllNodes(hasText("≈", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasText("0", substring = true)).assertCountEquals(0)
    }

    @Test
    fun eligibleEcb_amountUnavailable_evenWithAStaleFormattedFigure_theFigureIsNotShown() {
        show(CurrencyRegionUi.Eligible(rateDate, RateSourceLabel.ECB, "€99.99", amountUnavailable = true))

        composeRule.onAllNodes(hasText("99.99", substring = true)).assertCountEquals(0)
        composeRule.onNodeWithText("ECB rate of 27 Oct").assertExists()
    }

    @Test
    fun eligibleOtherSource_amountUnavailable_rendersNothing() {
        show(CurrencyRegionUi.Eligible(rateDate, RateSourceLabel.OTHER, null, amountUnavailable = true))

        assertNothingRendered()
    }

    @Test
    fun rateDate_inAnotherYearThanToday_showsTheYear() {
        show(
            CurrencyRegionUi.Eligible(LocalDate.of(2025, 12, 31), RateSourceLabel.ECB, null, amountUnavailable = false)
        )

        composeRule.onNodeWithText("ECB rate of 31 Dec 2025").assertExists()
    }

    @Test
    fun rateDate_withNoToday_showsTheYear() {
        show(
            CurrencyRegionUi.Eligible(rateDate, RateSourceLabel.ECB, null, amountUnavailable = false),
            ratesToday = null
        )

        composeRule.onNodeWithText("ECB rate of 27 Oct 2026").assertExists()
    }

    // ---- Blocked variants -------------------------------------------------------------------------------------------

    @Test
    fun blockedNone_showsTheNoRateMessage_theAlternativeHint_andChangeCurrency() {
        show(blocked(CurrencyBlockVariant.NONE))

        composeRule.onNodeWithText(string(R.string.mec_block_none)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_block_alt_hint)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_action_change_currency)).assertExists()
    }

    @Test
    fun blockedStale_withALatestDate_namesThatDate() {
        show(blocked(CurrencyBlockVariant.STALE, latest = LocalDate.of(2026, 10, 12)))

        composeRule.onNodeWithText(string(R.string.mec_block_stale, "12 Oct")).assertExists()
    }

    @Test
    fun blockedStale_withoutALatestDate_fallsBackToTheNoRateMessage() {
        show(blocked(CurrencyBlockVariant.STALE, latest = null))

        composeRule.onNodeWithText(string(R.string.mec_block_none)).assertExists()
    }

    @Test
    fun blockedUnsupported_showsItsMessage_andOnlyChangeCurrency() {
        show(blocked(CurrencyBlockVariant.UNSUPPORTED), ratesSetting = CurrencyRatesSetting.ON)

        composeRule.onNodeWithText(string(R.string.mec_block_unsupported)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_action_change_currency)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_action_open_setting)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_block_alt_hint)).assertDoesNotExist()
    }

    @Test
    fun blockedHomeUnresolved_showsItsMessage_withNoActionButtons() {
        show(blocked(CurrencyBlockVariant.HOME_UNRESOLVED), ratesSetting = CurrencyRatesSetting.ON)

        composeRule.onNodeWithText(string(R.string.mec_block_home_unresolved)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_action_change_currency)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_action_open_setting)).assertDoesNotExist()
    }

    @Test
    fun homeUnresolved_isSuppressed_whenTheSaveErrorCardIsShown() {
        show(blocked(CurrencyBlockVariant.HOME_UNRESOLVED), suppressHomeUnresolvedCard = true)

        assertNothingRendered()
    }

    @Test
    fun suppression_doesNotHideOtherBlockedVariants() {
        show(blocked(CurrencyBlockVariant.NONE), suppressHomeUnresolvedCard = true)

        composeRule.onNodeWithText(string(R.string.mec_block_none)).assertExists()
    }

    // ---- live region: assertive only for a save refusal ----------------------------------------------------------------

    @Test
    fun blockedCard_fromSave_isAnnouncedAssertively() {
        show(blocked(CurrencyBlockVariant.NONE, origin = CurrencyBlockOrigin.SAVE))

        assertEquals(1, liveRegionCount(LiveRegionMode.Assertive))
        assertEquals(0, liveRegionCount(LiveRegionMode.Polite))
    }

    @Test
    fun blockedCard_fromPreview_isAnnouncedPolitely() {
        show(blocked(CurrencyBlockVariant.NONE, origin = CurrencyBlockOrigin.PREVIEW))

        assertEquals(1, liveRegionCount(LiveRegionMode.Polite))
        assertEquals(0, liveRegionCount(LiveRegionMode.Assertive))
    }

    @Test
    fun blockedStaleCard_fromSave_isAnnouncedAssertively() {
        show(blocked(CurrencyBlockVariant.STALE, LocalDate.of(2026, 10, 12), CurrencyBlockOrigin.SAVE))

        assertEquals(1, liveRegionCount(LiveRegionMode.Assertive))
    }

    // ---- refresh / open-setting follow the setting; UNKNOWN is OFF -----------------------------------------------------

    @Test
    fun settingOn_showsRefresh_andNeitherOpenSettingNorTheOffHint() {
        show(blocked(CurrencyBlockVariant.NONE), ratesSetting = CurrencyRatesSetting.ON)

        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_action_open_setting)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_block_off_hint)).assertDoesNotExist()
    }

    @Test
    fun settingOff_showsOpenSetting_andTheOffHint_butNoRefresh() {
        show(blocked(CurrencyBlockVariant.NONE), ratesSetting = CurrencyRatesSetting.OFF)

        composeRule.onNodeWithText(string(R.string.mec_action_open_setting)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_block_off_hint)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertDoesNotExist()
    }

    @Test
    fun settingUnknown_isTreatedAsOff_failClosed() {
        show(blocked(CurrencyBlockVariant.NONE), ratesSetting = CurrencyRatesSetting.UNKNOWN)

        composeRule.onNodeWithText(string(R.string.mec_action_open_setting)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_block_off_hint)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertDoesNotExist()
    }

    @Test
    fun settingOn_refreshRunning_showsRefreshingAndTheButtonIsDisabled() {
        show(
            blocked(CurrencyBlockVariant.NONE),
            ratesSetting = CurrencyRatesSetting.ON,
            rateRefresh = CurrencyRefreshStatus.RUNNING
        )

        composeRule.onNodeWithText(string(R.string.mec_action_refreshing)).assertExists().assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertDoesNotExist()
    }

    @Test
    fun settingOn_refreshIdle_theRefreshButtonIsEnabled() {
        show(blocked(CurrencyBlockVariant.NONE), ratesSetting = CurrencyRatesSetting.ON)

        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertIsEnabled()
    }

    @Test
    fun refreshOutcome_doneStillBlocked_showsItsLine() {
        show(
            blocked(CurrencyBlockVariant.NONE),
            ratesSetting = CurrencyRatesSetting.ON,
            rateRefresh = CurrencyRefreshStatus.DONE_STILL_BLOCKED
        )
        composeRule.onNodeWithText(string(R.string.mec_refresh_still_blocked)).assertExists()
    }

    @Test
    fun refreshOutcome_failedOffline_showsItsLine() {
        show(
            blocked(CurrencyBlockVariant.NONE),
            ratesSetting = CurrencyRatesSetting.ON,
            rateRefresh = CurrencyRefreshStatus.FAILED_OFFLINE
        )
        composeRule.onNodeWithText(string(R.string.mec_refresh_offline)).assertExists()
    }

    @Test
    fun refreshOutcome_failed_showsItsLine() {
        show(
            blocked(CurrencyBlockVariant.NONE),
            ratesSetting = CurrencyRatesSetting.ON,
            rateRefresh = CurrencyRefreshStatus.FAILED
        )
        composeRule.onNodeWithText(string(R.string.mec_refresh_failed)).assertExists()
    }

    @Test
    fun refreshIdle_orRunning_showsNoOutcomeLine() {
        show(blocked(CurrencyBlockVariant.NONE), ratesSetting = CurrencyRatesSetting.ON, rateRefresh = CurrencyRefreshStatus.IDLE)

        listOf(
            R.string.mec_refresh_still_blocked,
            R.string.mec_refresh_offline,
            R.string.mec_refresh_failed,
            R.string.mec_refresh_home_unavailable
        ).forEach { composeRule.onNodeWithText(string(it)).assertDoesNotExist() }
    }

    @Test
    fun homeUnavailableRefresh_withSettingOn_hidesTheRefreshButton() {
        show(
            blocked(CurrencyBlockVariant.NONE),
            ratesSetting = CurrencyRatesSetting.ON,
            rateRefresh = CurrencyRefreshStatus.FAILED_HOME_UNAVAILABLE
        )

        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_refresh_home_unavailable)).assertExists()
    }

    @Test
    fun unsupportedVariant_neverShowsARefreshOutcomeLine() {
        show(
            blocked(CurrencyBlockVariant.UNSUPPORTED),
            ratesSetting = CurrencyRatesSetting.ON,
            rateRefresh = CurrencyRefreshStatus.FAILED
        )

        composeRule.onNodeWithText(string(R.string.mec_refresh_failed)).assertDoesNotExist()
    }

    // ---- callbacks ----------------------------------------------------------------------------------------------------------

    @Test
    fun clickingRefresh_callsOnRefreshOnce() {
        show(blocked(CurrencyBlockVariant.NONE), ratesSetting = CurrencyRatesSetting.ON)

        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).performClick()

        assertEquals(listOf(1, 0, 0), listOf(refreshCalls, openSettingCalls, changeCurrencyCalls))
    }

    @Test
    fun clickingOpenSetting_callsOnOpenSettingOnce() {
        show(blocked(CurrencyBlockVariant.NONE), ratesSetting = CurrencyRatesSetting.OFF)

        composeRule.onNodeWithText(string(R.string.mec_action_open_setting)).performClick()

        assertEquals(listOf(0, 1, 0), listOf(refreshCalls, openSettingCalls, changeCurrencyCalls))
    }

    @Test
    fun clickingChangeCurrency_callsOnChangeCurrencyOnce() {
        show(blocked(CurrencyBlockVariant.UNSUPPORTED))

        composeRule.onNodeWithText(string(R.string.mec_action_change_currency)).performClick()

        assertEquals(listOf(0, 0, 1), listOf(refreshCalls, openSettingCalls, changeCurrencyCalls))
    }
}
