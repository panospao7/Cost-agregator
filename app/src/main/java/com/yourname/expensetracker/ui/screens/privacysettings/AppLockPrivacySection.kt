package com.yourname.expensetracker.ui.screens.privacysettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.InertReason
import com.yourname.expensetracker.ui.applock.SecuritySettingsLauncher
import com.yourname.expensetracker.ui.theme.SemanticColors

/** Test tags of the section (S12, S22-a.1). Stable identifiers only, never user text. */
object AppLockPrivacySectionTags {
    const val SECTION = "app_lock_privacy_section"
    const val DISCREET_ROW = "app_lock_privacy_discreet_row"
    const val DISCREET_CHECKING = "app_lock_privacy_discreet_checking"
    const val RETRY = "app_lock_privacy_retry"
    const val TITLE = "app_lock_privacy_title"
    const val REVIEW_CARD = "app_lock_privacy_review_card"
    const val REVIEW_CARD_TEXT = "app_lock_privacy_review_card_text"
    const val REVIEW_DISCREET_ROW = "app_lock_privacy_review_discreet_row"
    const val REVIEW_SAVE = "app_lock_privacy_review_save"
    const val NOTICE = "app_lock_privacy_notice"
    const val LOCK_ROW = "app_lock_privacy_lock_row"
    const val LOCK_CHECKING = "app_lock_privacy_lock_checking"
    const val LOCK_TRANSIENT = "app_lock_privacy_lock_transient"
    const val LOCK_SECURITY_SETTINGS = "app_lock_privacy_lock_security_settings"
    const val LOCK_TRY_AGAIN = "app_lock_privacy_lock_try_again"
}

/**
 * The "App lock & privacy" section: the app lock row (S22-a.1) and the discreet-notifications row (S12).
 *
 * Stateful wrapper. It re-reads at each foreground while the setting is transiently unreadable (INV-47) and re-reads
 * the capability hint (display only), then renders [AppLockPrivacySectionContent]. It holds no request of its own: every
 * action is a ViewModel call.
 */
@Composable
fun AppLockPrivacySection(
    modifier: Modifier = Modifier,
    viewModel: AppLockPrivacySettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshIfTransient()
                viewModel.refreshCapabilityHint()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AppLockPrivacySectionContent(
        state = state,
        onDiscreetChange = viewModel::setDiscreetNotifications,
        onRetry = viewModel::retry,
        onReviewDiscreetChange = viewModel::setReviewDiscreetChoice,
        onReviewSave = viewModel::saveReview,
        onEnableConfirmed = viewModel::onEnableConfirmed,
        onTurnOff = viewModel::onTurnOffRequested,
        onLockTryAgain = viewModel::refreshCapabilityHint,
        onOpenSecuritySettings = { SecuritySettingsLauncher.open(context) },
        modifier = modifier
    )
}

/**
 * Stateless rendering of the section. Test entry point (no Hilt).
 *
 * The explainer's visibility is local UI state (saved across rotation). It is dropped as soon as the lock row is no
 * longer Off, so it can never reappear later. The lock switch shows the COMMITTED value only (INV-46): tapping it from
 * Off opens the explainer, tapping it from On asks the ViewModel to turn off, and neither moves the switch.
 */
@Composable
internal fun AppLockPrivacySectionContent(
    state: AppLockPrivacySectionState,
    onDiscreetChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onReviewDiscreetChange: (Boolean) -> Unit,
    onReviewSave: () -> Unit,
    onEnableConfirmed: () -> Unit,
    onTurnOff: () -> Unit,
    onLockTryAgain: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    modifier: Modifier = Modifier,
    showApi30ActionsPoint: Boolean = isAndroid11()
) {
    val lockIsOff = state is AppLockPrivacySectionState.Ready && state.lock == AppLockRowState.Off
    var explainerVisible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(lockIsOff) {
        if (!lockIsOff) explainerVisible = false
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(AppLockPrivacySectionTags.SECTION),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.privacy_section_app_lock),
            style = MaterialTheme.typography.titleSmall,
            color = SemanticColors.PrimaryIndigo,
            modifier = Modifier
                .padding(top = 12.dp, bottom = 4.dp)
                .testTag(AppLockPrivacySectionTags.TITLE)
                .semantics { heading() }
        )
        when (state) {
            AppLockPrivacySectionState.Loading -> {
                LockRow(
                    lock = AppLockRowState.Checking,
                    onSwitchOn = {},
                    onTurnOff = {},
                    onTryAgain = {},
                    onOpenSecuritySettings = {}
                )
                NoSwitchRow(
                    title = stringResource(R.string.privacy_toggle_discreet_notifications),
                    supportText = stringResource(R.string.privacy_app_lock_checking),
                    tag = AppLockPrivacySectionTags.DISCREET_CHECKING
                )
            }

            is AppLockPrivacySectionState.Ready -> {
                LockRow(
                    lock = state.lock,
                    onSwitchOn = { explainerVisible = true },
                    onTurnOff = onTurnOff,
                    onTryAgain = onLockTryAgain,
                    onOpenSecuritySettings = onOpenSecuritySettings
                )
                SwitchRow(
                    title = stringResource(R.string.privacy_toggle_discreet_notifications),
                    checked = state.discreetOn,
                    enabled = !state.isWriting && !state.discreetForced,
                    supportText = stringResource(
                        if (state.discreetForced) {
                            R.string.privacy_discreet_notifications_forced
                        } else {
                            R.string.privacy_discreet_notifications_support
                        }
                    ),
                    onCheckedChange = onDiscreetChange,
                    tag = AppLockPrivacySectionTags.DISCREET_ROW
                )
                NoticeText(state.notice)
            }

            AppLockPrivacySectionState.TransientUnreadable -> {
                LockRow(
                    lock = AppLockRowState.Transient,
                    onSwitchOn = {},
                    onTurnOff = {},
                    onTryAgain = {},
                    onOpenSecuritySettings = {}
                )
                // Fail closed: the effective value is discreet on, shown checked and disabled. Every
                // per-field write is rejected while unreadable (INV-44), so the row cannot be changed.
                SwitchRow(
                    title = stringResource(R.string.privacy_toggle_discreet_notifications),
                    checked = true,
                    enabled = false,
                    supportText = stringResource(R.string.privacy_discreet_notifications_unreadable),
                    onCheckedChange = {},
                    tag = AppLockPrivacySectionTags.DISCREET_ROW
                )
                Text(
                    text = stringResource(R.string.privacy_section_unreadable),
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag(AppLockPrivacySectionTags.RETRY)
                ) {
                    Text(stringResource(R.string.privacy_app_lock_retry))
                }
            }

            is AppLockPrivacySectionState.NeedsReview -> {
                ReviewCard(onReviewSave = onReviewSave, enabled = !state.isSaving)
                SwitchRow(
                    title = stringResource(R.string.privacy_toggle_discreet_notifications),
                    checked = state.discreetChoice,
                    enabled = !state.isSaving,
                    supportText = stringResource(R.string.privacy_discreet_notifications_support),
                    onCheckedChange = onReviewDiscreetChange,
                    tag = AppLockPrivacySectionTags.REVIEW_DISCREET_ROW
                )
                NoticeText(state.notice)
            }
        }
        HorizontalDivider()
    }
    if (explainerVisible) {
        AppLockEnableDialog(
            onContinue = {
                explainerVisible = false
                onEnableConfirmed()
            },
            onDismiss = { explainerVisible = false },
            showApi30ActionsPoint = showApi30ActionsPoint
        )
    }
}

/**
 * The lock row, one rendering per [AppLockRowState] (exhaustive, no else). The switch shows the committed value; a
 * tap never moves it (INV-46). The unreadable and checking variants draw no switch at all, so no checked state can be
 * read from the screen or from accessibility (INV-2).
 */
@Composable
private fun LockRow(
    lock: AppLockRowState,
    onSwitchOn: () -> Unit,
    onTurnOff: () -> Unit,
    onTryAgain: () -> Unit,
    onOpenSecuritySettings: () -> Unit
) {
    val title = stringResource(R.string.privacy_toggle_app_lock)
    when (lock) {
        AppLockRowState.Checking -> NoSwitchRow(
            title = title,
            supportText = stringResource(R.string.privacy_app_lock_checking),
            tag = AppLockPrivacySectionTags.LOCK_CHECKING
        )

        AppLockRowState.Transient -> NoSwitchRow(
            title = title,
            supportText = stringResource(R.string.privacy_app_lock_unreadable),
            tag = AppLockPrivacySectionTags.LOCK_TRANSIENT
        )

        AppLockRowState.Off -> SwitchRow(
            title = title,
            checked = false,
            enabled = true,
            supportText = stringResource(R.string.privacy_app_lock_support),
            onCheckedChange = { turnedOn -> if (turnedOn) onSwitchOn() },
            tag = AppLockPrivacySectionTags.LOCK_ROW
        )

        AppLockRowState.On -> SwitchRow(
            title = title,
            checked = true,
            enabled = true,
            supportText = stringResource(R.string.privacy_app_lock_support),
            onCheckedChange = { turnedOn -> if (!turnedOn) onTurnOff() },
            tag = AppLockPrivacySectionTags.LOCK_ROW
        )

        is AppLockRowState.Waiting -> SwitchRow(
            title = title,
            checked = lock.committedOn,
            enabled = false,
            supportText = stringResource(R.string.privacy_app_lock_waiting),
            onCheckedChange = {},
            tag = AppLockPrivacySectionTags.LOCK_ROW
        )

        // Paused (INERT): the switch shows On and can only be turned off, with no prompt (T19, AE-54).
        is AppLockRowState.InertOn -> SwitchRow(
            title = title,
            checked = true,
            enabled = true,
            supportText = stringResource(
                if (lock.reason == InertReason.API_UNSUPPORTED) {
                    R.string.privacy_app_lock_unavailable_os_paused
                } else {
                    R.string.privacy_review_lock_paused
                }
            ),
            onCheckedChange = { turnedOn -> if (!turnedOn) onTurnOff() },
            tag = AppLockPrivacySectionTags.LOCK_ROW
        )

        is AppLockRowState.Unavailable -> {
            SwitchRow(
                title = title,
                checked = false,
                enabled = false,
                supportText = stringResource(
                    when (lock.reason) {
                        AppLockUnavailableReason.OS -> R.string.privacy_app_lock_unavailable_os
                        AppLockUnavailableReason.NOT_SECURE -> R.string.privacy_app_lock_unavailable_no_secure
                        AppLockUnavailableReason.NOW -> R.string.privacy_app_lock_unavailable_now
                    }
                ),
                onCheckedChange = {},
                tag = AppLockPrivacySectionTags.LOCK_ROW
            )
            when (lock.reason) {
                AppLockUnavailableReason.OS,
                AppLockUnavailableReason.NOT_SECURE -> TextButton(
                    onClick = onOpenSecuritySettings,
                    modifier = Modifier.testTag(AppLockPrivacySectionTags.LOCK_SECURITY_SETTINGS)
                ) {
                    Text(stringResource(R.string.privacy_app_lock_open_security_settings))
                }
                AppLockUnavailableReason.NOW -> TextButton(
                    onClick = onTryAgain,
                    modifier = Modifier.testTag(AppLockPrivacySectionTags.LOCK_TRY_AGAIN)
                ) {
                    Text(stringResource(R.string.privacy_app_lock_retry))
                }
            }
        }
    }
}

/**
 * A row with no switch: the checking and unreadable variants. No switch is drawn at all, so no checked state (neither Off
 * nor On) can be read from the screen or from accessibility (INV-2). The row is announced as disabled.
 */
@Composable
private fun NoSwitchRow(title: String, supportText: String, tag: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp)
            .testTag(tag)
            .semantics(mergeDescendants = true) { disabled() }
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = SemanticColors.TextSecondary
        )
        Text(
            text = supportText,
            style = MaterialTheme.typography.bodySmall,
            color = SemanticColors.TextSecondary
        )
    }
}

/**
 * One switch row with a supporting line. The whole row is one merged, toggleable node (label, support
 * text and switch announce together); the inner switch has no handler of its own.
 */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    supportText: String,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp)
            .testTag(tag)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) SemanticColors.TextPrimary else SemanticColors.TextSecondary
            )
            Text(
                text = supportText,
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled
        )
    }
}

@Composable
private fun ReviewCard(onReviewSave: () -> Unit, enabled: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(AppLockPrivacySectionTags.REVIEW_CARD),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Title and body are one accessibility focus stop; the save button stays separate.
            Column(
                modifier = Modifier
                    .testTag(AppLockPrivacySectionTags.REVIEW_CARD_TEXT)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.privacy_review_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = SemanticColors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.privacy_review_card_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )
            }
            TextButton(
                onClick = onReviewSave,
                enabled = enabled,
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag(AppLockPrivacySectionTags.REVIEW_SAVE)
            ) {
                Text(stringResource(R.string.privacy_review_card_save))
            }
        }
    }
}

/** One inline notice (D-S22-3). Exhaustive over [AppLockPrivacySectionNotice] (no else). */
@Composable
private fun NoticeText(notice: AppLockPrivacySectionNotice?) {
    if (notice == null) return
    val textRes = when (notice) {
        AppLockPrivacySectionNotice.SAVE_FAILED -> R.string.privacy_setting_save_failed
        AppLockPrivacySectionNotice.REVIEW_SAVED -> R.string.privacy_review_saved
        AppLockPrivacySectionNotice.LOCK_ON -> R.string.privacy_app_lock_snackbar_on
        AppLockPrivacySectionNotice.LOCK_OFF -> R.string.privacy_app_lock_snackbar_off
        AppLockPrivacySectionNotice.LOCK_NOT_TURNED_ON -> R.string.privacy_app_lock_not_enabled
        AppLockPrivacySectionNotice.TOO_MANY_ATTEMPTS -> R.string.applock_msg_too_many_attempts
        AppLockPrivacySectionNotice.ENABLE_FAILED -> R.string.privacy_app_lock_enable_failed
    }
    val isError = notice == AppLockPrivacySectionNotice.SAVE_FAILED ||
        notice == AppLockPrivacySectionNotice.ENABLE_FAILED
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else SemanticColors.TextSecondary,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .testTag(AppLockPrivacySectionTags.NOTICE)
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
}
