package com.yourname.expensetracker.ui.screens.addexpense

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.currency.CurrencyRateHistoryOutcome
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.RateHistoryFailureReason
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
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
 * manual-entry-currency 1b S2: the visible wiring of [AddExpenseSheet] to the history download state of the
 * REAL [AddExpenseViewModel] (built by [AddExpenseSheetHarness]). Oracle: domain-spec "## 1b" (AE-1b-1, 3,
 * 4, 5, 15, 24) and ux-flows "## 1b".
 *
 * FIXED_NOW is 2023-11-14. A stored 8 Nov USD->EUR row with the entry dated 1 Nov makes a history download
 * the remedy (the entry is inside the 90-day window and a newer row exists). The harness rates repository
 * is the counting fake; a [CompletableDeferred] gate holds a download in RUNNING without virtual time.
 * Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], qualifiers = "w411dp-h2000dp")
class AddExpenseSheetRateHistoryUiTest {

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
    private val originalTimeZone: java.util.TimeZone = java.util.TimeZone.getDefault()
    private val harness = AddExpenseSheetHarness(home = "EUR")
    private lateinit var viewModel: AddExpenseViewModel

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
        java.util.TimeZone.setDefault(originalTimeZone)
        harness.close()
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(resId)

    private fun utcMidnight(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun noon(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun putRate(date: LocalDate, rate: Double = 0.9) {
        harness.rateStore.rows += DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = rate,
            lastUpdated = 0L,
            source = "ecb",
            validDate = utcMidnight(date)
        )
    }

    private fun stored(earliest: LocalDate) = CurrencyRateHistoryOutcome.Stored(
        days = 60,
        rows = 600,
        earliestDate = earliest,
        latestDate = LocalDate.of(2023, 11, 14)
    )

    /** Opens the real sheet with USD 20 entered for [entryDate]; [settingOn] controls the privacy flow. */
    private fun showBlockedSheet(
        settingOn: Boolean = true,
        entryDate: Long = noon(2023, 11, 1),
        newerRow: Boolean = true
    ) {
        if (newerRow) putRate(LocalDate.of(2023, 11, 8))
        harness.privacyFlow.value =
            PrivacySettingsLoadState.Loaded(PrivacySettings(exchangeRateDownloadEnabled = settingOn))
        viewModel = harness.createViewModel()
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.setContent {
            MaterialTheme {
                AddExpenseSheet(
                    onDismiss = {},
                    initialAmount = null,
                    initialMerchant = null,
                    onOpenExchangeRateSetting = {},
                    viewModel = viewModel
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            viewModel.updateDate(entryDate)
            viewModel.selectCurrency("USD")
            viewModel.updateAmount("20.00")
        }
        composeRule.waitForIdle()
    }

    private val downloadText: String get() = string(R.string.mec_history_action)

    private fun tapDownload() {
        composeRule.onNodeWithText(downloadText).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun politePreviewCount(): Int =
        composeRule.onAllNodes(
            hasText("ECB rate of", substring = true) and
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        ).fetchSemanticsNodes().size

    // ---- AE-1b-1: which action the blocked card offers ----

    @Test
    fun ae1b1_newerStoredRow_withSettingOn_offersDownloadAndDisclosure_notRefreshOrOpenSetting() {
        showBlockedSheet()

        composeRule.onNodeWithText(downloadText).assertExists().assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.mec_history_disclosure)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_action_open_setting)).assertDoesNotExist()
    }

    @Test
    fun ae1b1_noStoredRow_withSettingOn_offersRefresh_notDownload() {
        showBlockedSheet(newerRow = false)

        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertExists()
        composeRule.onNodeWithText(downloadText).assertDoesNotExist()
    }

    @Test
    fun ae1b1_settingOff_offersOpenSetting_andNeverTheDownload() {
        showBlockedSheet(settingOn = false)

        composeRule.onNodeWithText(string(R.string.mec_action_open_setting)).assertExists()
        composeRule.onNodeWithText(downloadText).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_history_disclosure)).assertDoesNotExist()
    }

    @Test
    fun ae1b1_entryOlderThan90Days_showsTheWindowMessage_andNoDownloadButton() {
        showBlockedSheet(entryDate = noon(2023, 8, 1))

        composeRule.onNodeWithText(string(R.string.mec_history_too_old)).assertExists()
        composeRule.onNodeWithText(downloadText).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertDoesNotExist()
    }

    // ---- AE-1b-4: never automatic ----

    @Test
    fun ae1b4_openingTheSheetAndTypingNeverCallsTheDownload() {
        showBlockedSheet()
        composeRule.runOnIdle { viewModel.updateAmount("25.50") }
        composeRule.waitForIdle()

        assertEquals(emptyList<String>(), harness.ratesRepository.historyCalls)
    }

    // ---- AE-1b-3 / AE-1b-24: tap, success, preview announced once ----

    @Test
    fun ae1b3_tappingDownload_callsTheRepositoryOnce_andASuccessShowsThePreviewAnnouncedOnce() {
        harness.ratesRepository.historyOutcome = stored(earliest = LocalDate.of(2023, 8, 16))
        harness.ratesRepository.historyAction = { putRate(LocalDate.of(2023, 10, 31)) }
        showBlockedSheet()

        tapDownload()

        assertEquals(listOf("EUR"), harness.ratesRepository.historyCalls)
        composeRule.onNode(hasText("ECB rate of", substring = true)).assertExists()
        composeRule.onNodeWithText(downloadText).assertDoesNotExist()
        assertEquals("the success is announced once, through the preview line", 1, politePreviewCount())

        composeRule.runOnIdle { viewModel.updateAmount("200") }
        composeRule.waitForIdle()
        assertEquals("a later keystroke is silent", 0, politePreviewCount())
    }

    // ---- AE-1b-3 / AE-1b-15: RUNNING and Cancel ----

    @Test
    fun ae1b3_running_showsCancelAndADisabledAction_andASecondTapCannotStartAnother() {
        val gate = CompletableDeferred<Unit>()
        harness.ratesRepository.historyOutcome = stored(earliest = LocalDate.of(2023, 8, 16))
        harness.ratesRepository.historyAction = { gate.await() }
        showBlockedSheet()

        tapDownload()

        composeRule.onNodeWithText(string(R.string.mec_history_action_running)).assertExists().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.mec_history_cancel_cd)).assertExists()
        composeRule.onNodeWithText(string(R.string.mec_history_action_running)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, harness.ratesRepository.historyCalls.size)

        composeRule.runOnIdle { gate.complete(Unit) }
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, harness.ratesRepository.historyCalls.size)
    }

    @Test
    fun ae1b15_cancel_showsTheStoppedMessage_andStoresNothing() {
        val gate = CompletableDeferred<Unit>()
        harness.ratesRepository.historyOutcome = stored(earliest = LocalDate.of(2023, 8, 16))
        harness.ratesRepository.historyAction = {
            gate.await()
            putRate(LocalDate.of(2023, 10, 31))
        }
        showBlockedSheet()
        val rowsBefore = harness.rateStore.rows.size
        tapDownload()

        composeRule.onNodeWithContentDescription(string(R.string.mec_history_cancel_cd)).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { gate.complete(Unit) }
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()

        composeRule.onNodeWithText(string(R.string.mec_history_cancelled)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.mec_history_cancel_cd)).assertDoesNotExist()
        composeRule.onNodeWithText(downloadText).assertExists().assertIsEnabled()
        assertEquals(rowsBefore, harness.rateStore.rows.size)
    }

    // ---- AE-1b-5 / AE-1b-6b: terminal messages ----

    @Test
    fun ae1b5_offlineFailure_showsItsMessage_andKeepsTheButtonForARetry() {
        harness.ratesRepository.historyOutcome = CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.OFFLINE)
        showBlockedSheet()

        tapDownload()

        composeRule.onNodeWithText(string(R.string.mec_history_offline)).assertExists()
        composeRule.onNodeWithText(downloadText).assertExists().assertIsEnabled()
    }

    @Test
    fun ae1b5_homeCurrencyFailure_showsTheHomeMessage_andHidesTheButton() {
        harness.ratesRepository.historyOutcome =
            CurrencyRateHistoryOutcome.Failed(RateHistoryFailureReason.HOME_QUOTE_UNAVAILABLE)
        showBlockedSheet()

        tapDownload()

        composeRule.onNodeWithText(string(R.string.mec_refresh_home_unavailable)).assertExists()
        composeRule.onNodeWithText(downloadText).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertDoesNotExist()
    }

    @Test
    fun ae1b6b_storedButStillNoRate_showsTheNoRateMessage_andNoFixButton() {
        harness.ratesRepository.historyOutcome = stored(earliest = LocalDate.of(2023, 8, 16))
        showBlockedSheet()

        tapDownload()

        composeRule.onNodeWithText(string(R.string.mec_history_no_rate)).assertExists()
        composeRule.onNodeWithText(downloadText).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.mec_action_refresh)).assertDoesNotExist()
    }

    @Test
    fun ae1b6b_storedButEntryBeforeTheEarliestDate_showsTheTooOldMessage() {
        harness.ratesRepository.historyOutcome = stored(earliest = LocalDate.of(2023, 11, 5))
        showBlockedSheet()

        tapDownload()

        composeRule.onNodeWithText(string(R.string.mec_history_too_old)).assertExists()
        composeRule.onNodeWithText(downloadText).assertDoesNotExist()
    }
}
