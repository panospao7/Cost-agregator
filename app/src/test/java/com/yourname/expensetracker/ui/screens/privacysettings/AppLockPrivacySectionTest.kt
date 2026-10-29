package com.yourname.expensetracker.ui.screens.privacysettings

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.AuthPurpose
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.InertReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * S12: rendering of the 1a "App lock & privacy" section. Merged semantics: each switch row is one
 * toggleable node carrying its label and support text. An unknown state never renders as Off (INV-2).
 *
 * S22-a.1: the lock row (explainer on the Off tap, no dialog on the On tap, no optimistic switch, Waiting, Inert,
 * Unavailable variants, forced discreet) and P-113 (the explainer closing never disposes the composition).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockPrivacySectionTest {

    /**
     * createComposeRule() launches androidx.activity.ComponentActivity, which is declared only by
     * ui-test-manifest (debugImplementation). The release unit-test variant has no such manifest entry,
     * so the activity is registered with Robolectric's package manager before the compose rule launches
     * it. ui-test-manifest is deliberately not added to the release or main configuration.
     */
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

    private val discreetChanges = mutableListOf<Boolean>()
    private val reviewChanges = mutableListOf<Boolean>()
    private var retries = 0
    private var reviewSaves = 0
    private var enableConfirms = 0
    private var turnOffs = 0
    private var lockTryAgains = 0
    private var securitySettingsOpens = 0

    // S22-b.1 callbacks.
    private val timeoutSelections = mutableListOf<AutoLockTimeout>()
    private val secureChanges = mutableListOf<Boolean>()
    private val reviewLockChanges = mutableListOf<Boolean>()
    private val reviewTimeoutChanges = mutableListOf<AutoLockTimeout>()
    private val reviewSecureChanges = mutableListOf<Boolean>()

    private fun text(@StringRes id: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    /** A Ready state with the 1a defaults; the lock row is Off unless given. */
    private fun ready(
        lock: AppLockRowState = AppLockRowState.Off,
        discreetOn: Boolean = false,
        isWriting: Boolean = false,
        notice: AppLockPrivacySectionNotice? = null,
        discreetForced: Boolean = false,
        timeoutRow: AutoLockTimeoutRowState? = null,
        secureOn: Boolean = true,
        secureForced: Boolean = false
    ) = AppLockPrivacySectionState.Ready(
        discreetOn = discreetOn,
        isWriting = isWriting,
        notice = notice,
        discreetForced = discreetForced,
        lock = lock,
        timeoutRow = timeoutRow,
        secureOn = secureOn,
        secureForced = secureForced
    )

    /** A Ready state with the committed lock On: timeout row, forced discreet and forced secure rows. */
    private fun readyLockOn(
        lock: AppLockRowState = AppLockRowState.On,
        timeout: AutoLockTimeout = AutoLockTimeout.M1,
        paused: Boolean = false,
        isWriting: Boolean = false,
        notice: AppLockPrivacySectionNotice? = null
    ) = ready(
        lock = lock,
        discreetOn = true,
        isWriting = isWriting,
        notice = notice,
        discreetForced = true,
        timeoutRow = AutoLockTimeoutRowState(committed = timeout, paused = paused),
        secureOn = true,
        secureForced = true
    )

    private fun show(state: AppLockPrivacySectionState, showApi30ActionsPoint: Boolean = false) {
        showLive(mutableStateOf(state), showApi30ActionsPoint)
    }

    /** Like [show], but the state can change while the composition stays alive. */
    private fun showLive(
        state: MutableState<AppLockPrivacySectionState>,
        showApi30ActionsPoint: Boolean = false
    ) {
        composeRule.setContent {
            AppLockPrivacySectionContent(
                state = state.value,
                onDiscreetChange = { discreetChanges += it },
                onRetry = { retries++ },
                onReviewDiscreetChange = { reviewChanges += it },
                onReviewSave = { reviewSaves++ },
                onEnableConfirmed = { enableConfirms++ },
                onTurnOff = { turnOffs++ },
                onLockTryAgain = { lockTryAgains++ },
                onOpenSecuritySettings = { securitySettingsOpens++ },
                showApi30ActionsPoint = showApi30ActionsPoint,
                onTimeoutSelected = { timeoutSelections += it },
                onSecureChange = { secureChanges += it },
                onReviewLockChange = { reviewLockChanges += it },
                onReviewTimeoutChange = { reviewTimeoutChanges += it },
                onReviewSecureChange = { reviewSecureChanges += it }
            )
        }
    }

    private fun setState(state: MutableState<AppLockPrivacySectionState>, value: AppLockPrivacySectionState) {
        composeRule.runOnIdle { state.value = value }
        composeRule.waitForIdle()
    }

    private fun timeoutDialogCount(): Int =
        composeRule.onAllNodesWithTag(AutoLockTimeoutDialogTags.DIALOG).fetchSemanticsNodes().size

    private fun api30NoteCount(): Int =
        composeRule.onAllNodesWithTag(AppLockPrivacySectionTags.API30_NOTE, useUnmergedTree = true)
            .fetchSemanticsNodes().size

    /** Like [show], but leaves the Android 11 bullet to the production default (read from the SDK level). */
    private fun showWithDefaultApi30Point(state: AppLockPrivacySectionState) {
        composeRule.setContent {
            AppLockPrivacySectionContent(
                state = state,
                onDiscreetChange = { discreetChanges += it },
                onRetry = { retries++ },
                onReviewDiscreetChange = { reviewChanges += it },
                onReviewSave = { reviewSaves++ },
                onEnableConfirmed = { enableConfirms++ },
                onTurnOff = { turnOffs++ },
                onLockTryAgain = { lockTryAgains++ },
                onOpenSecuritySettings = { securitySettingsOpens++ }
            )
        }
    }

    private fun assertNoLockCallbacks() {
        assertEquals("enable confirms", 0, enableConfirms)
        assertEquals("turn-offs", 0, turnOffs)
        assertEquals("try again", 0, lockTryAgains)
        assertEquals("security settings", 0, securitySettingsOpens)
    }

    private fun explainerCount(): Int =
        composeRule.onAllNodesWithTag(AppLockEnableDialogTags.DIALOG).fetchSemanticsNodes().size

    // ---------------------------------------------------------------------------------------------------------
    // 1a rows (unchanged behaviour)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `loading shows Checking and no switch so it never reads as Off`() {
        show(AppLockPrivacySectionState.Loading)

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_CHECKING)
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_app_lock_checking)))
        composeRule.onNode(isToggleable()).assertDoesNotExist()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW).assertDoesNotExist()
    }

    @Test
    fun `ready off renders an enabled merged switch row with label and support text`() {
        show(ready())

        composeRule.onNodeWithText(text(R.string.privacy_section_app_lock)).assertExists()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW)
            .assertIsOff()
            .assertIsEnabled()
            .assert(hasText(text(R.string.privacy_toggle_discreet_notifications)))
            .assert(hasText(text(R.string.privacy_discreet_notifications_support)))
    }

    @Test
    fun `ready on renders On and a tap requests Off`() {
        show(ready(discreetOn = true))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW).assertIsOn().performClick()

        assertEquals(listOf(false), discreetChanges)
    }

    @Test
    fun `a tap on an off row requests On`() {
        show(ready())

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW).performClick()

        assertEquals(listOf(true), discreetChanges)
    }

    @Test
    fun `a write in flight disables the row and keeps the committed value`() {
        show(ready(isWriting = true))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW)
            .assertIsOff()
            .assertIsNotEnabled()
            .performClick()

        assertEquals(emptyList<Boolean>(), discreetChanges)
    }

    @Test
    fun `failed write shows the error and keeps the old value (AE-105)`() {
        show(ready(notice = AppLockPrivacySectionNotice.SAVE_FAILED))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW).assertIsOff()
        composeRule.onNodeWithText(text(R.string.privacy_setting_save_failed)).assertExists()
    }

    @Test
    fun `transient unreadable shows discreet on and disabled, with Try again`() {
        show(AppLockPrivacySectionState.TransientUnreadable)

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW)
            .assertIsOn()
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_discreet_notifications_unreadable)))
        composeRule.onNodeWithText(text(R.string.privacy_section_unreadable)).assertExists()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_CARD).assertDoesNotExist()

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.RETRY).performClick()

        assertEquals(1, retries)
        assertEquals(emptyList<Boolean>(), discreetChanges)
    }

    @Test
    fun `needs review shows the card, an editable discreet row once the lock choice is Off, and saves`() {
        // S22-b.1: the lock choice defaults to On, which forces the discreet row; an editable row needs lockChoice = false.
        show(
            AppLockPrivacySectionState.NeedsReview(
                discreetChoice = true,
                isSaving = false,
                notice = null,
                lockChoice = false
            )
        )

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_CARD).assertExists()
        composeRule.onNodeWithText(text(R.string.privacy_review_card_title)).assertExists()
        composeRule.onNodeWithText(text(R.string.privacy_review_card_body)).assertExists()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW).assertDoesNotExist()

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_DISCREET_ROW)
            .assertIsOn()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf(false), reviewChanges)
        assertEquals(emptyList<Boolean>(), discreetChanges)

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_SAVE).performClick()
        assertEquals(1, reviewSaves)
    }

    @Test
    fun `section title has heading semantics`() {
        show(ready())

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TITLE)
            .assert(hasText(text(R.string.privacy_section_app_lock)))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun `review card title and body are one merged focus stop and the button stays separate`() {
        show(AppLockPrivacySectionState.NeedsReview(discreetChoice = true, isSaving = false, notice = null))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_CARD_TEXT)
            .assert(hasText(text(R.string.privacy_review_card_title)))
            .assert(hasText(text(R.string.privacy_review_card_body)))
            .assert(!hasText(text(R.string.privacy_review_card_save)))
        // In the merged tree the title and body are not separate nodes: each text resolves to the one group.
        composeRule.onAllNodes(hasText(text(R.string.privacy_review_card_title))).assertCountEquals(1)
        composeRule.onAllNodes(hasText(text(R.string.privacy_review_card_body))).assertCountEquals(1)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_SAVE)
            .assertIsEnabled()
            .assert(hasText(text(R.string.privacy_review_card_save)))
    }

    @Test
    fun `while saving the review controls are disabled`() {
        show(
            AppLockPrivacySectionState.NeedsReview(
                discreetChoice = false,
                isSaving = true,
                notice = null,
                lockChoice = false,
                secureChoice = false
            )
        )

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_LOCK_ROW).assertIsOff().assertIsNotEnabled()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_DISCREET_ROW)
            .assertIsOff()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_SECURE_ROW)
            .assertIsOff()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_SAVE).assertIsNotEnabled()
    }

    @Test
    fun `review save failure shows the error and keeps the card`() {
        show(
            AppLockPrivacySectionState.NeedsReview(
                discreetChoice = true,
                isSaving = false,
                notice = AppLockPrivacySectionNotice.SAVE_FAILED
            )
        )

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_CARD).assertExists()
        composeRule.onNodeWithText(text(R.string.privacy_setting_save_failed)).assertExists()
    }

    @Test
    fun `review saved notice is shown once the store is readable`() {
        show(ready(discreetOn = true, notice = AppLockPrivacySectionNotice.REVIEW_SAVED))

        composeRule.onNodeWithText(text(R.string.privacy_review_saved)).assertExists()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_CARD).assertDoesNotExist()
    }

    // ---------------------------------------------------------------------------------------------------------
    // S22-a.1: the lock row and the enable explainer
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `the Off lock row renders an enabled switch row, Off, with the app lock label and support text`() {
        show(ready())

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW)
            .assertIsOff()
            .assertIsEnabled()
            .assert(hasText(text(R.string.privacy_toggle_app_lock)))
            .assert(hasText(text(R.string.privacy_app_lock_support)))
        assertEquals(0, explainerCount())
    }

    @Test
    fun `an Off tap opens the explainer and calls nothing`() {
        show(ready())

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).performClick()

        composeRule.onNodeWithTag(AppLockEnableDialogTags.DIALOG).assertExists()
        composeRule.onNodeWithText(text(R.string.privacy_app_lock_enable_title)).assertExists()
        assertNoLockCallbacks()
        // No optimistic switch (INV-46): the row still shows the committed Off.
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).assertIsOff()
    }

    @Test
    fun `Continue in the explainer calls onEnableConfirmed exactly once and closes it`() {
        show(ready())
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).performClick()

        composeRule.onNodeWithTag(AppLockEnableDialogTags.CONTINUE).performClick()

        assertEquals(1, enableConfirms)
        assertEquals(0, turnOffs)
        assertEquals(0, explainerCount())
        // The row still shows the committed value until the ViewModel reports otherwise.
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).assertIsOff()
    }

    @Test
    fun `Cancel in the explainer closes it and calls nothing`() {
        show(ready())
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).performClick()

        composeRule.onNodeWithTag(AppLockEnableDialogTags.CANCEL).performClick()

        assertEquals(0, explainerCount())
        assertNoLockCallbacks()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).assertIsOff()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `Back on the explainer closes it and calls nothing`() {
        show(ready())
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).performClick()
        composeRule.onNodeWithTag(AppLockEnableDialogTags.DIALOG).assertExists()

        composeRule.runOnUiThread {
            val dialog = checkNotNull(ShadowDialog.getLatestDialog()) { "the explainer dialog was not shown" }
            dialog.onBackPressed()
        }
        composeRule.waitForIdle()

        assertEquals(0, explainerCount())
        assertNoLockCallbacks()
    }

    @Test
    fun `an On tap calls onTurnOff once with no dialog, and the switch does not move`() {
        show(ready(lock = AppLockRowState.On, discreetOn = true, discreetForced = true))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW)
            .assertIsOn()
            .assertIsEnabled()
            .performClick()

        assertEquals(1, turnOffs)
        assertEquals(0, enableConfirms)
        assertEquals("ux 2.2c: no confirm dialog in device mode", 0, explainerCount())
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).assertIsOn()
    }

    @Test
    fun `a paused-On tap (AE-54) calls onTurnOff once with no dialog, for every reason`() {
        val state = mutableStateOf<AppLockPrivacySectionState>(
            ready(lock = AppLockRowState.InertOn(InertReason.API_UNSUPPORTED), discreetOn = true, discreetForced = true)
        )
        composeRule.setContent {
            AppLockPrivacySectionContent(
                state = state.value,
                onDiscreetChange = {},
                onRetry = {},
                onReviewDiscreetChange = {},
                onReviewSave = {},
                onEnableConfirmed = { enableConfirms++ },
                onTurnOff = { turnOffs++ },
                onLockTryAgain = {},
                onOpenSecuritySettings = {},
                showApi30ActionsPoint = false
            )
        }

        InertReason.values().forEachIndexed { index, reason ->
            composeRule.runOnIdle {
                state.value = ready(
                    lock = AppLockRowState.InertOn(reason),
                    discreetOn = true,
                    discreetForced = true
                )
            }
            val expectedSupport = if (reason == InertReason.API_UNSUPPORTED) {
                R.string.privacy_app_lock_unavailable_os_paused
            } else {
                R.string.privacy_review_lock_paused
            }

            composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW)
                .assertIsOn()
                .assertIsEnabled()
                .assert(hasText(text(expectedSupport)))
                .performClick()

            assertEquals("$reason", index + 1, turnOffs)
            assertEquals("$reason no dialog", 0, explainerCount())
        }
        assertEquals(0, enableConfirms)
    }

    @Test
    fun `a Waiting row is disabled and shows the committed value, so a tap does nothing (AE-113)`() {
        show(ready(lock = AppLockRowState.Waiting(AuthPurpose.ENABLE, committedOn = false)))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW)
            .assertIsOff()
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_app_lock_waiting)))
            .performClick()

        assertNoLockCallbacks()
        assertEquals(0, explainerCount())
    }

    @Test
    fun `a Waiting row over a committed-on lock shows On and is disabled (AE-114)`() {
        show(
            ready(
                lock = AppLockRowState.Waiting(AuthPurpose.CONFIRM_CHANGE, committedOn = true),
                discreetOn = true,
                discreetForced = true
            )
        )

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW)
            .assertIsOn()
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_app_lock_waiting)))
            .performClick()

        assertNoLockCallbacks()
    }

    @Test
    fun `the Checking lock row draws no switch and no checked state`() {
        show(AppLockPrivacySectionState.Loading)

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_CHECKING)
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_toggle_app_lock)))
            .assert(hasText(text(R.string.privacy_app_lock_checking)))
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).assertDoesNotExist()
        composeRule.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    @Test
    fun `the Transient lock row draws no switch and no checked state, while the discreet and secure rows keep their own switches`() {
        show(AppLockPrivacySectionState.TransientUnreadable)

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_TRANSIENT)
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_toggle_app_lock)))
            .assert(hasText(text(R.string.privacy_app_lock_unreadable)))
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).assertDoesNotExist()
        // Only the discreet row and the secure-screen row (S22-b.1) are switches.
        composeRule.onAllNodes(isToggleable()).assertCountEquals(2)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW).assertIsOn().assertIsNotEnabled()
    }

    @Test
    fun `an Unavailable NOW row is disabled Off and offers Try again, which calls onLockTryAgain`() {
        show(ready(lock = AppLockRowState.Unavailable(AppLockUnavailableReason.NOW)))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW)
            .assertIsOff()
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_app_lock_unavailable_now)))
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_SECURITY_SETTINGS).assertDoesNotExist()

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_TRY_AGAIN)
            .assert(hasText(text(R.string.privacy_app_lock_retry)))
            .performClick()

        assertEquals(1, lockTryAgains)
        assertEquals(0, securitySettingsOpens)
        assertEquals(0, enableConfirms)
        assertEquals(0, explainerCount())
    }

    @Test
    fun `an Unavailable OS row explains Android 11 and offers Open security settings`() {
        show(ready(lock = AppLockRowState.Unavailable(AppLockUnavailableReason.OS)))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW)
            .assertIsOff()
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_app_lock_unavailable_os)))
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_TRY_AGAIN).assertDoesNotExist()

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_SECURITY_SETTINGS)
            .assert(hasText(text(R.string.privacy_app_lock_open_security_settings)))
            .performClick()

        assertEquals(1, securitySettingsOpens)
        assertEquals(0, lockTryAgains)
    }

    @Test
    fun `an Unavailable NOT_SECURE row asks for a screen lock and offers Open security settings`() {
        show(ready(lock = AppLockRowState.Unavailable(AppLockUnavailableReason.NOT_SECURE)))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW)
            .assertIsOff()
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_app_lock_unavailable_no_secure)))
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_TRY_AGAIN).assertDoesNotExist()

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_SECURITY_SETTINGS).performClick()

        assertEquals(1, securitySettingsOpens)
        assertEquals(0, lockTryAgains)
    }

    @Test
    fun `an Unavailable row opens no explainer when tapped (AE-34, AE-35)`() {
        show(ready(lock = AppLockRowState.Unavailable(AppLockUnavailableReason.OS)))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).performClick()

        assertEquals(0, explainerCount())
        assertEquals(0, enableConfirms)
    }

    @Test
    fun `a forced discreet row is checked, disabled and shows the forced text (AE-65)`() {
        show(ready(lock = AppLockRowState.On, discreetOn = true, discreetForced = true))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW)
            .assertIsOn()
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_discreet_notifications_forced)))
            .assert(!hasText(text(R.string.privacy_discreet_notifications_support)))
            .performClick()

        assertEquals(emptyList<Boolean>(), discreetChanges)
    }

    @Test
    fun `a forced discreet row stays checked and disabled while the lock is paused (AE-65 under Inert)`() {
        show(
            ready(
                lock = AppLockRowState.InertOn(InertReason.NO_CREDENTIAL),
                discreetOn = true,
                discreetForced = true
            )
        )

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW)
            .assertIsOn()
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_discreet_notifications_forced)))
    }

    @Test
    fun `control - an unforced discreet row is editable and shows the normal support text`() {
        show(ready(discreetOn = false, discreetForced = false))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW)
            .assertIsEnabled()
            .assert(hasText(text(R.string.privacy_discreet_notifications_support)))
            .assert(!hasText(text(R.string.privacy_discreet_notifications_forced)))
    }

    @Test
    fun `each lock notice renders its own text in the notice line`() {
        val state = mutableStateOf<AppLockPrivacySectionState>(ready())
        composeRule.setContent {
            AppLockPrivacySectionContent(
                state = state.value,
                onDiscreetChange = {},
                onRetry = {},
                onReviewDiscreetChange = {},
                onReviewSave = {},
                onEnableConfirmed = {},
                onTurnOff = {},
                onLockTryAgain = {},
                onOpenSecuritySettings = {},
                showApi30ActionsPoint = false
            )
        }
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.NOTICE).assertDoesNotExist()

        val expected = listOf(
            AppLockPrivacySectionNotice.LOCK_ON to R.string.privacy_app_lock_snackbar_on,
            AppLockPrivacySectionNotice.LOCK_OFF to R.string.privacy_app_lock_snackbar_off,
            AppLockPrivacySectionNotice.LOCK_NOT_TURNED_ON to R.string.privacy_app_lock_not_enabled,
            AppLockPrivacySectionNotice.TOO_MANY_ATTEMPTS to R.string.applock_msg_too_many_attempts,
            AppLockPrivacySectionNotice.ENABLE_FAILED to R.string.privacy_app_lock_enable_failed,
            AppLockPrivacySectionNotice.SAVE_FAILED to R.string.privacy_setting_save_failed
        )
        expected.forEach { (notice, textRes) ->
            composeRule.runOnIdle { state.value = ready(notice = notice) }
            composeRule.onNodeWithTag(AppLockPrivacySectionTags.NOTICE)
                .assert(hasText(text(textRes)))
        }
    }

    @Test
    fun `the explainer shows the Android 11 point when it is asked for`() {
        show(ready(), showApi30ActionsPoint = true)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).performClick()

        composeRule.onNodeWithTag(AppLockEnableDialogTags.API30_POINT).assertExists()
        composeRule.onNodeWithText(text(R.string.privacy_app_lock_enable_point_api30_actions)).assertExists()
    }

    @Test
    fun `the explainer has no Android 11 point when it is not asked for`() {
        show(ready(), showApi30ActionsPoint = false)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).performClick()

        composeRule.onNodeWithTag(AppLockEnableDialogTags.DIALOG).assertExists()
        composeRule.onNodeWithTag(AppLockEnableDialogTags.API30_POINT).assertDoesNotExist()
    }

    @Test
    fun `at SDK 34 the default leaves the Android 11 point out of the explainer`() {
        showWithDefaultApi30Point(ready())
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).performClick()

        composeRule.onNodeWithTag(AppLockEnableDialogTags.DIALOG).assertExists()
        composeRule.onNodeWithTag(AppLockEnableDialogTags.API30_POINT).assertDoesNotExist()
    }

    @Test
    @Config(sdk = [30])
    fun `at SDK 30 the default puts the Android 11 point into the explainer`() {
        showWithDefaultApi30Point(ready())
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).performClick()

        composeRule.onNodeWithTag(AppLockEnableDialogTags.API30_POINT).assertExists()
    }

    // ---------------------------------------------------------------------------------------------------------
    // P-113 (AE-113, INV-53): the explainer closing never disposes the composition
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `P-113 the explainer closes when the row becomes Waiting, and the composition is not disposed`() {
        val state = mutableStateOf<AppLockPrivacySectionState>(ready())
        var disposals = 0
        var probe = ""
        composeRule.setContent {
            // A remember probe (not saveable) in the hosting slot, like the settings screen of AE-113.
            val remembered = remember { "P-113" }
            probe = remembered
            AppLockPrivacySectionContent(
                state = state.value,
                onDiscreetChange = {},
                onRetry = {},
                onReviewDiscreetChange = {},
                onReviewSave = {},
                onEnableConfirmed = { enableConfirms++ },
                onTurnOff = { turnOffs++ },
                onLockTryAgain = {},
                onOpenSecuritySettings = {},
                // Evaluated inside the section's own composition: it is disposed only if the section is.
                modifier = Modifier.composed {
                    DisposableEffect(Unit) { onDispose { disposals++ } }
                    this
                },
                showApi30ActionsPoint = false
            )
        }
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).performClick()
        composeRule.onNodeWithTag(AppLockEnableDialogTags.DIALOG).assertExists()

        // The person taps Continue elsewhere: the controller state becomes Authenticating(ENABLE).
        composeRule.runOnIdle {
            state.value = ready(lock = AppLockRowState.Waiting(AuthPurpose.ENABLE, committedOn = false))
        }
        composeRule.waitForIdle()

        assertEquals("the explainer closed", 0, explainerCount())
        assertEquals("the section was not disposed", 0, disposals)
        assertEquals("the remember probe survived", "P-113", probe)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.SECTION).assertExists()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW)
            .assertIsOff()
            .assertIsNotEnabled()
        assertEquals("nothing was confirmed or turned off", 0, enableConfirms + turnOffs)
    }

    @Test
    fun `P-113 control - the explainer stays open while the row stays Off through other state changes`() {
        val state = mutableStateOf<AppLockPrivacySectionState>(ready())
        composeRule.setContent {
            AppLockPrivacySectionContent(
                state = state.value,
                onDiscreetChange = {},
                onRetry = {},
                onReviewDiscreetChange = {},
                onReviewSave = {},
                onEnableConfirmed = {},
                onTurnOff = {},
                onLockTryAgain = {},
                onOpenSecuritySettings = {},
                showApi30ActionsPoint = false
            )
        }
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).performClick()
        composeRule.onNodeWithTag(AppLockEnableDialogTags.DIALOG).assertExists()

        composeRule.runOnIdle { state.value = ready(notice = AppLockPrivacySectionNotice.SAVE_FAILED) }
        composeRule.waitForIdle()

        assertEquals(1, explainerCount())
    }

    @Test
    fun `P-113 the explainer closes when the row leaves Off for Unavailable, and it does not return when the row is Off again`() {
        val state = mutableStateOf<AppLockPrivacySectionState>(ready())
        composeRule.setContent {
            AppLockPrivacySectionContent(
                state = state.value,
                onDiscreetChange = {},
                onRetry = {},
                onReviewDiscreetChange = {},
                onReviewSave = {},
                onEnableConfirmed = { enableConfirms++ },
                onTurnOff = {},
                onLockTryAgain = {},
                onOpenSecuritySettings = {},
                showApi30ActionsPoint = false
            )
        }
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).performClick()
        composeRule.onNodeWithTag(AppLockEnableDialogTags.DIALOG).assertExists()

        composeRule.runOnIdle {
            state.value = ready(lock = AppLockRowState.Unavailable(AppLockUnavailableReason.NOW))
        }
        composeRule.waitForIdle()
        assertEquals(0, explainerCount())

        composeRule.runOnIdle { state.value = ready() }
        composeRule.waitForIdle()

        assertEquals("a stale explainer must not reappear", 0, explainerCount())
        assertEquals(0, enableConfirms)
    }

    // ---------------------------------------------------------------------------------------------------------
    // S22-b.1: the timeout row (AE-116, INV-46, TECH-13)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `timeout row is absent while the committed lock is off or unknown`() {
        val state = mutableStateOf<AppLockPrivacySectionState>(ready())
        showLive(state)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).assertDoesNotExist()

        setState(state, AppLockPrivacySectionState.Loading)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).assertDoesNotExist()
    }

    @Test
    fun `timeout row is absent for an Unavailable lock and for a Waiting lock that is committed off`() {
        val state = mutableStateOf<AppLockPrivacySectionState>(
            ready(lock = AppLockRowState.Unavailable(AppLockUnavailableReason.NOW))
        )
        showLive(state)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).assertDoesNotExist()

        setState(state, ready(lock = AppLockRowState.Waiting(AuthPurpose.ENABLE, committedOn = false)))
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).assertDoesNotExist()
    }

    @Test
    fun `timeout row is absent in the transient unreadable state`() {
        show(AppLockPrivacySectionState.TransientUnreadable)

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).assertDoesNotExist()
    }

    @Test
    fun `timeout row is shown, enabled, with label note and the committed timeout when the lock is on`() {
        show(readyLockOn(timeout = AutoLockTimeout.M1))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW)
            .assertIsEnabled()
            .assert(hasText(text(R.string.privacy_app_lock_timeout_label)))
            .assert(hasText(text(R.string.privacy_app_lock_timeout_note)))
            .assert(hasText(text(R.string.applock_timeout_1m)))
    }

    @Test
    fun `timeout row shows the committed label for every timeout`() {
        val state = mutableStateOf<AppLockPrivacySectionState>(readyLockOn(timeout = AutoLockTimeout.IMMEDIATE))
        showLive(state)
        val labels = listOf(
            AutoLockTimeout.IMMEDIATE to R.string.applock_timeout_immediately,
            AutoLockTimeout.S30 to R.string.applock_timeout_30s,
            AutoLockTimeout.M1 to R.string.applock_timeout_1m,
            AutoLockTimeout.M5 to R.string.applock_timeout_5m,
            AutoLockTimeout.M15 to R.string.applock_timeout_15m
        )
        labels.forEach { (timeout, labelRes) ->
            setState(state, readyLockOn(timeout = timeout))
            composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).assert(hasText(text(labelRes)))
        }
    }

    @Test
    fun `timeout row is shown for a paused and for a Waiting committed-on lock`() {
        val state = mutableStateOf<AppLockPrivacySectionState>(
            readyLockOn(lock = AppLockRowState.InertOn(InertReason.NO_CREDENTIAL), paused = true)
        )
        showLive(state)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).assertExists()

        setState(
            state,
            readyLockOn(lock = AppLockRowState.Waiting(AuthPurpose.CONFIRM_CHANGE, committedOn = true))
        )
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).assertExists()
    }

    @Test
    fun `timeout row is disabled while a write is in flight and a tap opens nothing`() {
        show(readyLockOn(isWriting = true))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW)
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.applock_timeout_1m)))
            .performClick()

        assertEquals(0, timeoutDialogCount())
        assertEquals(emptyList<AutoLockTimeout>(), timeoutSelections)
    }

    @Test
    fun `timeout row is disabled while the lock row is Waiting and a tap opens nothing`() {
        show(readyLockOn(lock = AppLockRowState.Waiting(AuthPurpose.CONFIRM_CHANGE, committedOn = true)))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW)
            .assertIsNotEnabled()
            .performClick()

        assertEquals(0, timeoutDialogCount())
        assertEquals(emptyList<AutoLockTimeout>(), timeoutSelections)
    }

    @Test
    fun `a tap on the timeout row opens the picker with the committed timeout selected and calls nothing`() {
        show(readyLockOn(timeout = AutoLockTimeout.M5))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).performClick()

        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.DIALOG).assertExists()
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.option(AutoLockTimeout.M5)).assertIsSelected()
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.PAUSED_NOTE).assertDoesNotExist()
        assertEquals(emptyList<AutoLockTimeout>(), timeoutSelections)
        // INV-46: the row keeps showing the committed value.
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).assert(hasText(text(R.string.applock_timeout_5m)))
    }

    @Test
    fun `selecting an option calls onTimeoutSelected once, closes the picker and the row keeps the committed label`() {
        show(readyLockOn(timeout = AutoLockTimeout.M1))
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).performClick()

        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.option(AutoLockTimeout.M15)).performScrollTo().performClick()

        assertEquals(listOf(AutoLockTimeout.M15), timeoutSelections)
        assertEquals(0, timeoutDialogCount())
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW)
            .assert(hasText(text(R.string.applock_timeout_1m)))
        assertEquals(emptyList<AutoLockTimeout>(), reviewTimeoutChanges)
    }

    @Test
    fun `Cancel in the timeout picker closes it and selects nothing`() {
        show(readyLockOn())
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).performClick()

        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.CANCEL).performClick()

        assertEquals(0, timeoutDialogCount())
        assertEquals(emptyList<AutoLockTimeout>(), timeoutSelections)
    }

    @Test
    fun `TECH-13 a paused row opens a picker with the longer options disabled and the paused note`() {
        show(readyLockOn(lock = AppLockRowState.InertOn(InertReason.NO_CREDENTIAL), timeout = AutoLockTimeout.M1, paused = true))
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).assertIsEnabled().performClick()

        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.PAUSED_NOTE).assertExists()
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.option(AutoLockTimeout.M5)).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.option(AutoLockTimeout.S30)).assertIsEnabled().performClick()

        assertEquals(listOf(AutoLockTimeout.S30), timeoutSelections)
    }

    @Test
    fun `the timeout picker is dropped when the lock turns off and does not return when it turns on again`() {
        val state = mutableStateOf<AppLockPrivacySectionState>(readyLockOn())
        showLive(state)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).performClick()
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.DIALOG).assertExists()

        setState(state, ready())
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).assertDoesNotExist()
        assertEquals(0, timeoutDialogCount())

        setState(state, readyLockOn())
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).assertExists()
        assertEquals("a stale picker must not reappear", 0, timeoutDialogCount())
        assertEquals(emptyList<AutoLockTimeout>(), timeoutSelections)
    }

    @Test
    fun `the timeout picker is dropped when a write starts and when the lock row turns Waiting`() {
        val state = mutableStateOf<AppLockPrivacySectionState>(readyLockOn())
        showLive(state)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).performClick()
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.DIALOG).assertExists()

        setState(state, readyLockOn(isWriting = true))
        assertEquals(0, timeoutDialogCount())
        setState(state, readyLockOn())
        assertEquals("a stale picker must not reappear", 0, timeoutDialogCount())

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).performClick()
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.DIALOG).assertExists()
        setState(state, readyLockOn(lock = AppLockRowState.Waiting(AuthPurpose.CONFIRM_CHANGE, committedOn = true)))
        assertEquals(0, timeoutDialogCount())
        assertEquals(emptyList<AutoLockTimeout>(), timeoutSelections)
    }

    @Test
    fun `control - the timeout picker stays open through an unrelated state change`() {
        val state = mutableStateOf<AppLockPrivacySectionState>(readyLockOn())
        showLive(state)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).performClick()

        setState(state, readyLockOn(notice = AppLockPrivacySectionNotice.SAVE_FAILED))

        assertEquals(1, timeoutDialogCount())
    }

    // ---------------------------------------------------------------------------------------------------------
    // S22-b.1: the secure-screen row (AE-103, INV-48)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `secure row not forced is enabled, shows the normal support text and toggles to onSecureChange`() {
        show(ready(secureOn = false, secureForced = false))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.SECURE_ROW)
            .assertIsOff()
            .assertIsEnabled()
            .assert(hasText(text(R.string.privacy_toggle_secure_screen)))
            .assert(hasText(text(R.string.privacy_secure_screen_support)))
            .assert(!hasText(text(R.string.privacy_secure_screen_forced)))
            .performClick()

        assertEquals(listOf(true), secureChanges)
        assertEquals(emptyList<Boolean>(), discreetChanges)
    }

    @Test
    fun `secure row not forced and on requests Off on a tap`() {
        show(ready(secureOn = true, secureForced = false))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.SECURE_ROW).assertIsOn().assertIsEnabled().performClick()

        assertEquals(listOf(false), secureChanges)
    }

    @Test
    fun `secure row forced is checked, disabled, shows the forced text and a tap changes nothing`() {
        show(readyLockOn())

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.SECURE_ROW)
            .assertIsOn()
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_secure_screen_forced)))
            .assert(!hasText(text(R.string.privacy_secure_screen_support)))
            .performClick()

        assertEquals(emptyList<Boolean>(), secureChanges)
    }

    @Test
    fun `secure row is disabled while a write is in flight and keeps its committed value`() {
        show(ready(secureOn = false, isWriting = true))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.SECURE_ROW)
            .assertIsOff()
            .assertIsNotEnabled()
            .performClick()

        assertEquals(emptyList<Boolean>(), secureChanges)
    }

    @Test
    fun `secure row while Loading draws no switch and is announced as checking`() {
        show(AppLockPrivacySectionState.Loading)

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.SECURE_CHECKING)
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_toggle_secure_screen)))
            .assert(hasText(text(R.string.privacy_app_lock_checking)))
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.SECURE_ROW).assertDoesNotExist()
        composeRule.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    @Test
    fun `secure row in the transient state is checked and disabled`() {
        show(AppLockPrivacySectionState.TransientUnreadable)

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.SECURE_ROW)
            .assertIsOn()
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_toggle_secure_screen)))
            .performClick()

        assertEquals(emptyList<Boolean>(), secureChanges)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.SECURE_CHECKING).assertDoesNotExist()
    }

    // ---------------------------------------------------------------------------------------------------------
    // S22-b.1: the Android 11 note and the footnote
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `the Android 11 note is shown on the lock row for On, InertOn and a Waiting committed-on lock`() {
        val states = listOf(
            readyLockOn(lock = AppLockRowState.On),
            readyLockOn(lock = AppLockRowState.InertOn(InertReason.NO_CREDENTIAL), paused = true),
            readyLockOn(lock = AppLockRowState.Waiting(AuthPurpose.CONFIRM_CHANGE, committedOn = true))
        )
        val state = mutableStateOf<AppLockPrivacySectionState>(states.first())
        showLive(state, showApi30ActionsPoint = true)

        states.forEach { value ->
            setState(state, value)
            assertEquals("$value", 1, api30NoteCount())
            composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW)
                .assert(hasText(text(R.string.privacy_app_lock_api30_actions_note)))
        }
    }

    @Test
    fun `the Android 11 note is absent when the point is not asked for, even with the lock on`() {
        show(readyLockOn(), showApi30ActionsPoint = false)

        assertEquals(0, api30NoteCount())
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW)
            .assert(!hasText(text(R.string.privacy_app_lock_api30_actions_note)))
    }

    @Test
    fun `the Android 11 note is absent while the committed lock is off`() {
        val states = listOf(
            ready(lock = AppLockRowState.Off),
            ready(lock = AppLockRowState.Waiting(AuthPurpose.ENABLE, committedOn = false)),
            ready(lock = AppLockRowState.Unavailable(AppLockUnavailableReason.OS)),
            ready(lock = AppLockRowState.Unavailable(AppLockUnavailableReason.NOT_SECURE)),
            ready(lock = AppLockRowState.Unavailable(AppLockUnavailableReason.NOW))
        )
        val state = mutableStateOf<AppLockPrivacySectionState>(states.first())
        showLive(state, showApi30ActionsPoint = true)

        states.forEach { value ->
            setState(state, value)
            assertEquals("$value", 0, api30NoteCount())
            composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW)
                .assert(!hasText(text(R.string.privacy_app_lock_api30_actions_note)))
        }
    }

    @Test
    fun `the footnote is shown whenever the committed lock is on`() {
        val states = listOf(
            readyLockOn(lock = AppLockRowState.On),
            readyLockOn(lock = AppLockRowState.InertOn(InertReason.NO_CREDENTIAL), paused = true),
            readyLockOn(lock = AppLockRowState.Waiting(AuthPurpose.CONFIRM_CHANGE, committedOn = true))
        )
        val state = mutableStateOf<AppLockPrivacySectionState>(states.first())
        showLive(state)

        states.forEach { value ->
            setState(state, value)
            composeRule.onNodeWithTag(AppLockPrivacySectionTags.FOOTNOTE)
                .assert(hasText(text(R.string.privacy_app_lock_footnote)))
        }
    }

    @Test
    fun `the footnote is absent while the committed lock is off or unknown`() {
        val states = listOf(
            ready(lock = AppLockRowState.Off),
            ready(lock = AppLockRowState.Waiting(AuthPurpose.ENABLE, committedOn = false)),
            ready(lock = AppLockRowState.Unavailable(AppLockUnavailableReason.NOW)),
            AppLockPrivacySectionState.Loading,
            AppLockPrivacySectionState.TransientUnreadable,
            AppLockPrivacySectionState.NeedsReview(discreetChoice = true, isSaving = false, notice = null)
        )
        val state = mutableStateOf<AppLockPrivacySectionState>(states.first())
        showLive(state)

        states.forEach { value ->
            setState(state, value)
            composeRule.onNodeWithTag(AppLockPrivacySectionTags.FOOTNOTE).assertDoesNotExist()
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // S22-b.1: the review card's full record (item 55, INV-44, INV-45)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `review card defaults to the fail-closed record with lock On, IMMEDIATE, discreet and secure forced`() {
        show(AppLockPrivacySectionState.NeedsReview(discreetChoice = true, isSaving = false, notice = null))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_LOCK_ROW)
            .assertIsOn()
            .assertIsEnabled()
            .assert(hasText(text(R.string.privacy_toggle_app_lock)))
            .assert(hasText(text(R.string.privacy_app_lock_support)))
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_TIMEOUT_ROW)
            .assertIsEnabled()
            .assert(hasText(text(R.string.applock_timeout_immediately)))
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_DISCREET_ROW)
            .assertIsOn()
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_discreet_notifications_forced)))
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_SECURE_ROW)
            .assertIsOn()
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_secure_screen_forced)))
        // The review card replaces the normal rows.
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.LOCK_ROW).assertDoesNotExist()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).assertDoesNotExist()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.SECURE_ROW).assertDoesNotExist()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW).assertDoesNotExist()
    }

    @Test
    fun `review forced discreet and secure rows ignore taps while the lock choice is On`() {
        show(AppLockPrivacySectionState.NeedsReview(discreetChoice = false, isSaving = false, notice = null, secureChoice = false))

        // Forced on even though the stored choices are off.
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_DISCREET_ROW)
            .assertIsOn().assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_SECURE_ROW)
            .assertIsOn().assertIsNotEnabled().performClick()

        assertEquals(emptyList<Boolean>(), reviewChanges)
        assertEquals(emptyList<Boolean>(), reviewSecureChanges)
    }

    @Test
    fun `review lock row On reports false on a tap, without moving the switch`() {
        show(AppLockPrivacySectionState.NeedsReview(discreetChoice = true, isSaving = false, notice = null))
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_LOCK_ROW).assertIsOn().performClick()
        assertEquals(listOf(false), reviewLockChanges)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_LOCK_ROW).assertIsOn()
    }

    @Test
    fun `review lock row Off reports true on a tap`() {
        show(
            AppLockPrivacySectionState.NeedsReview(
                discreetChoice = true,
                isSaving = false,
                notice = null,
                lockChoice = false
            )
        )

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_LOCK_ROW).assertIsOff().assertIsEnabled().performClick()

        assertEquals(listOf(true), reviewLockChanges)
        assertEquals(0, enableConfirms)
        assertEquals("no explainer in the review card", 0, explainerCount())
    }

    @Test
    fun `review lock row while paused is Off, disabled, shows the paused reason and a tap changes nothing (INV-45)`() {
        show(
            AppLockPrivacySectionState.NeedsReview(
                discreetChoice = true,
                isSaving = false,
                notice = null,
                lockChoice = false,
                lockPaused = true
            )
        )

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_LOCK_ROW)
            .assertIsOff()
            .assertIsNotEnabled()
            .assert(hasText(text(R.string.privacy_review_lock_paused)))
            .assert(!hasText(text(R.string.privacy_app_lock_support)))
            .performClick()

        assertEquals(emptyList<Boolean>(), reviewLockChanges)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_TIMEOUT_ROW).assertDoesNotExist()
    }

    @Test
    fun `review timeout row is shown only while the lock choice is On`() {
        val state = mutableStateOf<AppLockPrivacySectionState>(
            AppLockPrivacySectionState.NeedsReview(
                discreetChoice = true,
                isSaving = false,
                notice = null,
                lockChoice = true,
                timeoutChoice = AutoLockTimeout.M5
            )
        )
        showLive(state)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_TIMEOUT_ROW)
            .assert(hasText(text(R.string.applock_timeout_5m)))
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.TIMEOUT_ROW).assertDoesNotExist()

        setState(
            state,
            AppLockPrivacySectionState.NeedsReview(
                discreetChoice = true,
                isSaving = false,
                notice = null,
                lockChoice = false,
                timeoutChoice = AutoLockTimeout.M5
            )
        )
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_TIMEOUT_ROW).assertDoesNotExist()
    }

    @Test
    fun `review timeout dialog opens with the choice selected, reports once to onReviewTimeoutChange and closes`() {
        show(
            AppLockPrivacySectionState.NeedsReview(
                discreetChoice = true,
                isSaving = false,
                notice = null,
                timeoutChoice = AutoLockTimeout.M1
            )
        )
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_TIMEOUT_ROW).performClick()

        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.DIALOG).assertExists()
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.option(AutoLockTimeout.M1)).assertIsSelected()
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.PAUSED_NOTE).assertDoesNotExist()
        assertEquals(emptyList<AutoLockTimeout>(), reviewTimeoutChanges)

        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.option(AutoLockTimeout.M15)).performScrollTo().performClick()

        assertEquals(listOf(AutoLockTimeout.M15), reviewTimeoutChanges)
        assertEquals(0, timeoutDialogCount())
        assertEquals("the committed-section callback is not used", emptyList<AutoLockTimeout>(), timeoutSelections)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_TIMEOUT_ROW)
            .assert(hasText(text(R.string.applock_timeout_1m)))
    }

    @Test
    fun `review timeout dialog is dropped when the lock choice turns Off or saving starts, and does not return`() {
        val state = mutableStateOf<AppLockPrivacySectionState>(
            AppLockPrivacySectionState.NeedsReview(discreetChoice = true, isSaving = false, notice = null)
        )
        showLive(state)
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_TIMEOUT_ROW).performClick()
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.DIALOG).assertExists()

        setState(
            state,
            AppLockPrivacySectionState.NeedsReview(discreetChoice = true, isSaving = false, notice = null, lockChoice = false)
        )
        assertEquals(0, timeoutDialogCount())
        setState(
            state,
            AppLockPrivacySectionState.NeedsReview(discreetChoice = true, isSaving = false, notice = null)
        )
        assertEquals("a stale picker must not reappear", 0, timeoutDialogCount())

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_TIMEOUT_ROW).performClick()
        composeRule.onNodeWithTag(AutoLockTimeoutDialogTags.DIALOG).assertExists()
        setState(
            state,
            AppLockPrivacySectionState.NeedsReview(discreetChoice = true, isSaving = true, notice = null)
        )
        assertEquals(0, timeoutDialogCount())
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_TIMEOUT_ROW).assertIsNotEnabled()
        assertEquals(emptyList<AutoLockTimeout>(), reviewTimeoutChanges)
    }

    @Test
    fun `review discreet and secure rows are editable and report their choice while the lock choice is Off`() {
        show(
            AppLockPrivacySectionState.NeedsReview(
                discreetChoice = false,
                isSaving = false,
                notice = null,
                lockChoice = false,
                secureChoice = true
            )
        )

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_DISCREET_ROW)
            .assertIsOff()
            .assertIsEnabled()
            .assert(hasText(text(R.string.privacy_discreet_notifications_support)))
            .assert(!hasText(text(R.string.privacy_discreet_notifications_forced)))
            .performClick()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_SECURE_ROW)
            .assertIsOn()
            .assertIsEnabled()
            .assert(hasText(text(R.string.privacy_secure_screen_support)))
            .assert(!hasText(text(R.string.privacy_secure_screen_forced)))
            .performClick()

        assertEquals(listOf(true), reviewChanges)
        assertEquals(listOf(false), reviewSecureChanges)
        assertEquals(emptyList<Boolean>(), discreetChanges)
        assertEquals(emptyList<Boolean>(), secureChanges)
    }

    @Test
    fun `review save is reported once and review callbacks do not leak into the committed ones`() {
        show(AppLockPrivacySectionState.NeedsReview(discreetChoice = true, isSaving = false, notice = null))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_SAVE).performClick()

        assertEquals(1, reviewSaves)
        assertEquals(emptyList<Boolean>(), reviewLockChanges)
        assertEquals(emptyList<AutoLockTimeout>(), reviewTimeoutChanges)
        assertEquals(emptyList<Boolean>(), reviewSecureChanges)
        assertEquals(0, turnOffs + enableConfirms)
    }
}
