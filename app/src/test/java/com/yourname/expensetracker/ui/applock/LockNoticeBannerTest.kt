package com.yourname.expensetracker.ui.applock

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.InertReason
import com.yourname.expensetracker.domain.privacy.applock.LockNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S19: the notice banner (INV-51, INV-52, ux-flows 2.6; AE-99, AE-101, AE-108). TalkBack: one text stop plus one
 * button stop per action, not a live region. 200% font scale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockNoticeBannerTest : AppLockComposeTestBase() {

    private var security = 0
    private var settings = 0
    private var review = 0
    private var dismissed = 0

    private fun show(notice: LockNotice, allowed: Boolean = true, fontScale: Float = 1f) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                LockNoticeBanner(
                    notice = notice,
                    inAppActionsAllowed = allowed,
                    onOpenSecuritySettings = { security++ },
                    onOpenAppLockSettings = { settings++ },
                    onReviewSettings = { review++ },
                    onDismissNotice = { dismissed++ }
                )
            }
        }
    }

    private fun count(tag: String, expected: Int) =
        composeRule.onAllNodesWithTag(tag).assertCountEquals(expected)

    @Test
    fun `copy is the ux table per inert reason and none for the Inc 2 notice`() {
        fun body(reason: InertReason) = LockNotice.Inert(reason).bannerCopy()!!.bodyRes
        assertEquals(R.string.applock_banner_inactive_body, body(InertReason.NO_CREDENTIAL))
        assertEquals(R.string.applock_banner_inactive_body_unavailable, body(InertReason.HW_UNAVAILABLE))
        assertEquals(R.string.applock_banner_inactive_body_unavailable, body(InertReason.UNKNOWN))
        assertEquals(R.string.applock_banner_inactive_body_os, body(InertReason.API_UNSUPPORTED))
        assertEquals(R.string.applock_banner_inactive_body_unreadable, body(InertReason.SETTINGS_UNREADABLE_NO_AUTH))
        InertReason.values().forEach {
            assertEquals(R.string.applock_banner_inactive_title, LockNotice.Inert(it).bannerCopy()!!.titleRes)
        }
        assertEquals(R.string.applock_banner_review_title, LockNotice.ReviewSettings.bannerCopy()!!.titleRes)
        assertEquals(R.string.applock_banner_review_body, LockNotice.ReviewSettings.bannerCopy()!!.bodyRes)
        assertEquals(
            R.string.applock_banner_biometric_invalidated_title,
            LockNotice.BiometricInvalidated.bannerCopy()!!.titleRes
        )
        assertEquals(
            R.string.applock_banner_biometric_invalidated_body,
            LockNotice.BiometricInvalidated.bannerCopy()!!.bodyRes
        )
    }

    @Test
    fun `biometric invalidated is the only notice with a dismiss action`() {
        assertEquals(LockNoticeActions(false, false, false, dismiss = true), LockNotice.BiometricInvalidated.bannerActions())
        InertReason.values().forEach { assertEquals(false, LockNotice.Inert(it).bannerActions().dismiss) }
        assertEquals(false, LockNotice.ReviewSettings.bannerActions().dismiss)
    }

    @Test
    fun `actions follow the ux table`() {
        fun a(reason: InertReason) = LockNotice.Inert(reason).bannerActions()
        assertEquals(LockNoticeActions(true, true, false), a(InertReason.NO_CREDENTIAL))
        assertEquals(LockNoticeActions(true, true, false), a(InertReason.SETTINGS_UNREADABLE_NO_AUTH))
        assertEquals(LockNoticeActions(false, true, false), a(InertReason.HW_UNAVAILABLE))
        assertEquals(LockNoticeActions(false, true, false), a(InertReason.UNKNOWN))
        assertEquals(LockNoticeActions(false, true, false), a(InertReason.API_UNSUPPORTED))
        assertEquals(LockNoticeActions(false, false, true), LockNotice.ReviewSettings.bannerActions())
    }

    @Test
    fun `no screen lock inert banner shows both actions and each tap is one callback`() {
        show(LockNotice.Inert(InertReason.NO_CREDENTIAL))

        count(AppLockUiTags.NOTICE_BANNER, 1)
        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_SECURITY_SETTINGS).performClick()
        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS).performClick()
        count(AppLockUiTags.NOTICE_ACTION_REVIEW, 0)
        assertEquals(1, security)
        assertEquals(1, settings)
        assertEquals(0, review)
    }

    @Test
    fun `hardware unavailable inert banner offers app lock settings only`() {
        show(LockNotice.Inert(InertReason.HW_UNAVAILABLE))

        count(AppLockUiTags.NOTICE_ACTION_SECURITY_SETTINGS, 0)
        count(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS, 1)
        count(AppLockUiTags.NOTICE_ACTION_REVIEW, 0)
    }

    @Test
    fun `review banner offers Review settings and cannot be dismissed`() {
        show(LockNotice.ReviewSettings)

        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_REVIEW).performClick()
        assertEquals(1, review)
        count(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS, 0)
        count(AppLockUiTags.NOTICE_ACTION_SECURITY_SETTINGS, 0)
    }

    @Test
    fun `INV-52 in-app actions are not composed during an operational lock but the system action stays`() {
        show(LockNotice.Inert(InertReason.SETTINGS_UNREADABLE_NO_AUTH), allowed = false)

        count(AppLockUiTags.NOTICE_BANNER, 1)
        count(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS, 0)
        count(AppLockUiTags.NOTICE_ACTION_REVIEW, 0)
        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_SECURITY_SETTINGS).performClick()
        assertEquals(1, security)
        assertEquals(0, settings)
        assertEquals(0, review)
    }

    @Test
    fun `INV-52 a review banner offers no action at all during an operational lock`() {
        show(LockNotice.ReviewSettings, allowed = false)

        count(AppLockUiTags.NOTICE_BANNER, 1)
        count(AppLockUiTags.NOTICE_ACTION_REVIEW, 0)
        assertEquals(0, review)
    }

    @Test
    fun `biometric invalidated banner renders and Dismiss calls onDismissNotice once`() {
        show(LockNotice.BiometricInvalidated)

        count(AppLockUiTags.NOTICE_BANNER, 1)
        count(AppLockUiTags.NOTICE_ACTION_SECURITY_SETTINGS, 0)
        count(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS, 0)
        count(AppLockUiTags.NOTICE_ACTION_REVIEW, 0)
        composeRule.onNodeWithTag(LOCK_NOTICE_DISMISS_TAG).performClick()
        assertEquals(1, dismissed)
        assertEquals(0, security + settings + review)
    }

    @Test
    fun `INV-52 Dismiss is not composed during an operational lock`() {
        show(LockNotice.BiometricInvalidated, allowed = false)

        count(AppLockUiTags.NOTICE_BANNER, 1)
        count(LOCK_NOTICE_DISMISS_TAG, 0)
        assertEquals(0, dismissed)
    }

    @Test
    fun `other notices never show Dismiss`() {
        show(LockNotice.Inert(InertReason.NO_CREDENTIAL))
        count(LOCK_NOTICE_DISMISS_TAG, 0)
    }

    @Test
    fun `text is one accessibility stop and the banner is not a live region`() {
        show(LockNotice.Inert(InertReason.NO_CREDENTIAL))

        val title = text(R.string.applock_banner_inactive_title)
        val body = text(R.string.applock_banner_inactive_body)
        val expected = text(R.string.applock_banner_description, title, body)
        composeRule.onNode(
            SemanticsMatcher("contentDescription == \"$expected\"") {
                it.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.contains(expected) == true
            }
        ).assertExists()
        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_BANNER)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `banner and its actions survive 200 percent font scale`() {
        show(LockNotice.Inert(InertReason.NO_CREDENTIAL), fontScale = 2f)

        count(AppLockUiTags.NOTICE_BANNER, 1)
        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_SECURITY_SETTINGS).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS).assertHeightIsAtLeast(48.dp)
    }
}
