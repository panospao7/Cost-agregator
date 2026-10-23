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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.theme.SemanticColors

/** Test tags of the section (S12). Stable identifiers only, never user text. */
object AppLockPrivacySectionTags {
    const val SECTION = "app_lock_privacy_section"
    const val DISCREET_ROW = "app_lock_privacy_discreet_row"
    const val DISCREET_CHECKING = "app_lock_privacy_discreet_checking"
    const val RETRY = "app_lock_privacy_retry"
    const val REVIEW_CARD = "app_lock_privacy_review_card"
    const val REVIEW_DISCREET_ROW = "app_lock_privacy_review_discreet_row"
    const val REVIEW_SAVE = "app_lock_privacy_review_save"
    const val NOTICE = "app_lock_privacy_notice"
}

/**
 * The 1a "App lock & privacy" section: the discreet-notifications row only (S12). The copy is the 1a
 * variant: it never mentions an app lock, a PIN or a secure screen.
 *
 * Stateful wrapper. It re-reads at each foreground while the setting is transiently unreadable
 * (INV-47), then renders [AppLockPrivacySectionContent].
 */
@Composable
fun AppLockPrivacySection(
    modifier: Modifier = Modifier,
    viewModel: AppLockPrivacySettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshIfTransient()
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
        modifier = modifier
    )
}

/** Stateless rendering of the section. Test entry point (no Hilt). */
@Composable
internal fun AppLockPrivacySectionContent(
    state: AppLockPrivacySectionState,
    onDiscreetChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onReviewDiscreetChange: (Boolean) -> Unit,
    onReviewSave: () -> Unit,
    modifier: Modifier = Modifier
) {
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
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
        when (state) {
            AppLockPrivacySectionState.Loading -> CheckingRow()

            is AppLockPrivacySectionState.Ready -> {
                DiscreetRow(
                    checked = state.discreetOn,
                    enabled = !state.isWriting,
                    supportText = stringResource(R.string.privacy_discreet_notifications_support),
                    onCheckedChange = onDiscreetChange,
                    tag = AppLockPrivacySectionTags.DISCREET_ROW
                )
                NoticeText(state.notice)
            }

            AppLockPrivacySectionState.TransientUnreadable -> {
                // Fail closed: the effective value is discreet on, shown checked and disabled. Every
                // per-field write is rejected while unreadable (INV-44), so the row cannot be changed.
                DiscreetRow(
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
                DiscreetRow(
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
}

/**
 * Loading: no switch is drawn at all, so no checked state (neither Off nor On) can be read from the
 * screen or from accessibility (INV-2). The row is announced as disabled, "Checking...".
 */
@Composable
private fun CheckingRow() {
    val checking = stringResource(R.string.privacy_app_lock_checking)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp)
            .testTag(AppLockPrivacySectionTags.DISCREET_CHECKING)
            .semantics(mergeDescendants = true) { disabled() }
    ) {
        Text(
            text = stringResource(R.string.privacy_toggle_discreet_notifications),
            style = MaterialTheme.typography.bodyMedium,
            color = SemanticColors.TextSecondary
        )
        Text(
            text = checking,
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
private fun DiscreetRow(
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
                text = stringResource(R.string.privacy_toggle_discreet_notifications),
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

@Composable
private fun NoticeText(notice: AppLockPrivacySectionNotice?) {
    if (notice == null) return
    val failed = notice == AppLockPrivacySectionNotice.SAVE_FAILED
    Text(
        text = stringResource(
            if (failed) R.string.privacy_setting_save_failed else R.string.privacy_review_saved
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (failed) MaterialTheme.colorScheme.error else SemanticColors.TextSecondary,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .testTag(AppLockPrivacySectionTags.NOTICE)
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
}
