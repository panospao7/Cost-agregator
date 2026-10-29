package com.yourname.expensetracker.ui.screens.privacysettings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R

/** Test tags of the enable explainer (S22-a.1). Stable identifiers only, never user text. */
object AppLockEnableDialogTags {
    const val DIALOG = "app_lock_enable_dialog"
    const val BODY = "app_lock_enable_dialog_body"
    const val CONTINUE = "app_lock_enable_dialog_continue"
    const val CANCEL = "app_lock_enable_dialog_cancel"
    const val API30_POINT = "app_lock_enable_dialog_api30_point"
}

/**
 * True on Android 11 (API 30), the one level where bill reminders do not show Snooze or Dismiss while the lock is on
 * (spec 4.4, R9). Read at the call, never cached.
 */
internal fun isAndroid11(): Boolean = Build.VERSION.SDK_INT == Build.VERSION_CODES.R

/**
 * The "Turn on app lock?" explainer (ux 2.2a), shown after the person taps the switch from Off and before the system
 * prompt. It is stateless and holds no request: [onContinue] asks the caller to start the enable, [onDismiss] (Cancel,
 * Back, a tap outside) changes nothing and shows no message.
 *
 * The Android 11 bullet is rendered only when [showApi30ActionsPoint] is true. The body scrolls, so it stays reachable
 * at 200% font scale. "Continue" uses the default text colour: enabling protection is not a risky action.
 */
@Composable
internal fun AppLockEnableDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    showApi30ActionsPoint: Boolean,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(AppLockEnableDialogTags.DIALOG),
        title = { Text(stringResource(R.string.privacy_app_lock_enable_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag(AppLockEnableDialogTags.BODY),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EnablePoint(R.string.privacy_app_lock_enable_point_unlock)
                EnablePoint(R.string.privacy_app_lock_enable_point_notifications)
                EnablePoint(R.string.privacy_app_lock_enable_point_replaced)
                EnablePoint(R.string.privacy_app_lock_enable_point_screen)
                EnablePoint(R.string.privacy_app_lock_enable_point_background)
                EnablePoint(R.string.privacy_app_lock_enable_point_face)
                if (showApi30ActionsPoint) {
                    EnablePoint(R.string.privacy_app_lock_enable_point_api30_actions, AppLockEnableDialogTags.API30_POINT)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onContinue,
                modifier = Modifier.testTag(AppLockEnableDialogTags.CONTINUE)
            ) {
                Text(stringResource(R.string.privacy_app_lock_enable_continue))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(AppLockEnableDialogTags.CANCEL)
            ) {
                Text(stringResource(R.string.privacy_confirm_cancel))
            }
        }
    )
}

/** One bullet. The bullet glyph is decoration: it is hidden from accessibility so only the sentence is read. */
@Composable
private fun EnablePoint(@StringRes textRes: Int, tag: String? = null) {
    Row(
        modifier = if (tag == null) Modifier else Modifier.testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // The dialog's own content colour is used (no override), like the other privacy dialogs.
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics {}
        )
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}
