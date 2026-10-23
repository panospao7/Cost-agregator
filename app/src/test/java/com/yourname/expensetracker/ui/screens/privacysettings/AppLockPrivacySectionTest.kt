package com.yourname.expensetracker.ui.screens.privacysettings

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * S12: rendering of the 1a "App lock & privacy" section. Merged semantics: each switch row is one
 * toggleable node carrying its label and support text. An unknown state never renders as Off (INV-2).
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

    private fun text(@StringRes id: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun show(state: AppLockPrivacySectionState) {
        composeRule.setContent {
            AppLockPrivacySectionContent(
                state = state,
                onDiscreetChange = { discreetChanges += it },
                onRetry = { retries++ },
                onReviewDiscreetChange = { reviewChanges += it },
                onReviewSave = { reviewSaves++ }
            )
        }
    }

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
        show(AppLockPrivacySectionState.Ready(discreetOn = false, isWriting = false, notice = null))

        composeRule.onNodeWithText(text(R.string.privacy_section_app_lock)).assertExists()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW)
            .assertIsOff()
            .assertIsEnabled()
            .assert(hasText(text(R.string.privacy_toggle_discreet_notifications)))
            .assert(hasText(text(R.string.privacy_discreet_notifications_support)))
    }

    @Test
    fun `ready on renders On and a tap requests Off`() {
        show(AppLockPrivacySectionState.Ready(discreetOn = true, isWriting = false, notice = null))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW).assertIsOn().performClick()

        assertEquals(listOf(false), discreetChanges)
    }

    @Test
    fun `a tap on an off row requests On`() {
        show(AppLockPrivacySectionState.Ready(discreetOn = false, isWriting = false, notice = null))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW).performClick()

        assertEquals(listOf(true), discreetChanges)
    }

    @Test
    fun `a write in flight disables the row and keeps the committed value`() {
        show(AppLockPrivacySectionState.Ready(discreetOn = false, isWriting = true, notice = null))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.DISCREET_ROW)
            .assertIsOff()
            .assertIsNotEnabled()
            .performClick()

        assertEquals(emptyList<Boolean>(), discreetChanges)
    }

    @Test
    fun `failed write shows the error and keeps the old value (AE-105)`() {
        show(
            AppLockPrivacySectionState.Ready(
                discreetOn = false,
                isWriting = false,
                notice = AppLockPrivacySectionNotice.SAVE_FAILED
            )
        )

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
    fun `needs review shows the card, an editable row defaulting to On, and saves`() {
        show(AppLockPrivacySectionState.NeedsReview(discreetChoice = true, isSaving = false, notice = null))

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
    fun `while saving the review controls are disabled`() {
        show(AppLockPrivacySectionState.NeedsReview(discreetChoice = false, isSaving = true, notice = null))

        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_DISCREET_ROW)
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
        show(
            AppLockPrivacySectionState.Ready(
                discreetOn = true,
                isWriting = false,
                notice = AppLockPrivacySectionNotice.REVIEW_SAVED
            )
        )

        composeRule.onNodeWithText(text(R.string.privacy_review_saved)).assertExists()
        composeRule.onNodeWithTag(AppLockPrivacySectionTags.REVIEW_CARD).assertDoesNotExist()
    }
}
