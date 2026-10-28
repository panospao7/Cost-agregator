package com.yourname.expensetracker.ui.applock

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.InertReason
import com.yourname.expensetracker.domain.privacy.applock.LockNotice
import com.yourname.expensetracker.ui.theme.SemanticColors

/** Constant resources for one banner (title, body). Pure, so it is unit-testable without a composition. */
internal data class LockNoticeBannerCopy(@StringRes val titleRes: Int, @StringRes val bodyRes: Int)

/** The actions one banner offers, before the operational-state limit (INV-52) is applied. */
internal data class LockNoticeActions(
    val systemSecuritySettings: Boolean,
    val appLockSettings: Boolean,
    val reviewSettings: Boolean
)

/** Banner text per notice (ux-flows 2.6). `null` for [LockNotice.BiometricInvalidated], which is Inc 2 and has no 1b copy. */
internal fun LockNotice.bannerCopy(): LockNoticeBannerCopy? = when (this) {
    is LockNotice.Inert -> LockNoticeBannerCopy(
        titleRes = R.string.applock_banner_inactive_title,
        bodyRes = when (reason) {
            InertReason.NO_CREDENTIAL -> R.string.applock_banner_inactive_body
            InertReason.HW_UNAVAILABLE, InertReason.UNKNOWN -> R.string.applock_banner_inactive_body_unavailable
            InertReason.API_UNSUPPORTED -> R.string.applock_banner_inactive_body_os
            InertReason.SETTINGS_UNREADABLE_NO_AUTH -> R.string.applock_banner_inactive_body_unreadable
        }
    )
    LockNotice.ReviewSettings -> LockNoticeBannerCopy(
        titleRes = R.string.applock_banner_review_title,
        bodyRes = R.string.applock_banner_review_body
    )
    LockNotice.BiometricInvalidated -> null
}

/** Which actions the notice offers (ux-flows 2.6 table). */
internal fun LockNotice.bannerActions(): LockNoticeActions = when (this) {
    is LockNotice.Inert -> LockNoticeActions(
        systemSecuritySettings = reason == InertReason.NO_CREDENTIAL ||
            reason == InertReason.SETTINGS_UNREADABLE_NO_AUTH,
        appLockSettings = true,
        reviewSettings = false
    )
    LockNotice.ReviewSettings -> LockNoticeActions(
        systemSecuritySettings = false,
        appLockSettings = false,
        reviewSettings = true
    )
    LockNotice.BiometricInvalidated -> LockNoticeActions(false, false, false)
}

/**
 * The gate banner slot content (domain-spec INV-51, INV-52, ux-flows 2.6): one slim strip for one [LockNotice].
 *
 * Rules:
 * - It is never composed on the lock surface; the gate decides that (INV-51).
 * - [LockNotice.Inert] and [LockNotice.ReviewSettings] cannot be dismissed, so there is no dismiss control.
 * - INV-52: with [inAppActionsAllowed] false (the operational state is restore or critical recovery), the in-app
 *   actions are not composed at all, so a tap cannot enqueue a navigation request. The system-intent action
 *   ("Open security settings") stays. The callbacks are invoked only by a tap.
 * - The text is one accessibility stop ("{title}. {body}"); each action is its own button stop. It is not a live
 *   region (it is persistent, not an event). Text and buttons are stacked so that 200% font scale does not clip.
 * - [LockNotice.BiometricInvalidated] is Inc 2 (the PIN) and the controller never derives it in 1b, so nothing is
 *   drawn for it here.
 */
@Composable
fun LockNoticeBanner(
    notice: LockNotice,
    inAppActionsAllowed: Boolean,
    onOpenSecuritySettings: () -> Unit,
    onOpenAppLockSettings: () -> Unit,
    onReviewSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val copy = notice.bannerCopy() ?: return
    val actions = notice.bannerActions()
    val title = stringResource(copy.titleRes)
    val body = stringResource(copy.bodyRes)
    val description = stringResource(R.string.applock_banner_description, title, body)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(AppLockUiTags.NOTICE_BANNER),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.WarningOrange.copy(alpha = 0.18f)
        )
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = description
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = SemanticColors.WarningOrange
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (actions.systemSecuritySettings) {
                    BannerAction(
                        label = stringResource(R.string.applock_banner_open_security_settings),
                        tag = AppLockUiTags.NOTICE_ACTION_SECURITY_SETTINGS,
                        onClick = onOpenSecuritySettings
                    )
                }
                if (inAppActionsAllowed && actions.appLockSettings) {
                    BannerAction(
                        label = stringResource(R.string.applock_banner_open_settings),
                        tag = AppLockUiTags.NOTICE_ACTION_APP_LOCK_SETTINGS,
                        onClick = onOpenAppLockSettings
                    )
                }
                if (inAppActionsAllowed && actions.reviewSettings) {
                    BannerAction(
                        label = stringResource(R.string.applock_banner_review_action),
                        tag = AppLockUiTags.NOTICE_ACTION_REVIEW,
                        onClick = onReviewSettings
                    )
                }
            }
        }
    }
}

@Composable
private fun BannerAction(label: String, tag: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(tag)
    ) {
        Text(label)
    }
}
