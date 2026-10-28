package com.yourname.expensetracker.ui.screens.privacysettings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout

/** Test tags of the timeout picker (S22-b.1). Stable identifiers only, never user text. */
object AutoLockTimeoutDialogTags {
    const val DIALOG = "auto_lock_timeout_dialog"
    const val NOTE = "auto_lock_timeout_dialog_note"
    const val PAUSED_NOTE = "auto_lock_timeout_dialog_paused_note"
    const val CANCEL = "auto_lock_timeout_dialog_cancel"
    private const val OPTION_PREFIX = "auto_lock_timeout_option_"

    /** The tag of one option row, from the option's persisted token (a bounded enum name, never user text). */
    fun option(timeout: AutoLockTimeout): String = OPTION_PREFIX + timeout.token
}

/** The row label of a timeout choice. Exhaustive (no else): a new value must be mapped here. */
@StringRes
internal fun autoLockTimeoutLabelRes(timeout: AutoLockTimeout): Int = when (timeout) {
    AutoLockTimeout.IMMEDIATE -> R.string.applock_timeout_immediately
    AutoLockTimeout.S30 -> R.string.applock_timeout_30s
    AutoLockTimeout.M1 -> R.string.applock_timeout_1m
    AutoLockTimeout.M5 -> R.string.applock_timeout_5m
    AutoLockTimeout.M15 -> R.string.applock_timeout_15m
}

/** The helper line of a timeout choice, or null when it has none (ux 2.2b: only the two shortest). Exhaustive (no else). */
@StringRes
private fun autoLockTimeoutHintRes(timeout: AutoLockTimeout): Int? = when (timeout) {
    AutoLockTimeout.IMMEDIATE -> R.string.applock_timeout_immediately_hint
    AutoLockTimeout.S30 -> R.string.applock_timeout_30s_hint
    AutoLockTimeout.M1,
    AutoLockTimeout.M5,
    AutoLockTimeout.M15 -> null
}

/**
 * The "Lock after leaving the app" picker (ux 2.2b): a radio list in a dialog, every row at least 48dp and fully
 * selectable. It is stateless and holds no request: [onSelect] reports the person's choice and the caller closes the
 * dialog; [onDismiss] (Cancel, Back, a tap outside) changes nothing and shows no message.
 *
 * [selected] is the COMMITTED timeout the caller passes in (INV-46): choosing a row never moves the radio by itself.
 * When [longerDisabled] is true (TECH-13: the lock is paused, so nothing can authenticate) every option longer than
 * [selected] is disabled and a short note says why (D-S22-5); equal and shorter options stay available. The two shortest
 * options carry their helper line under the label, so the line is always truthful and reads with the row.
 */
@Composable
internal fun AutoLockTimeoutDialog(
    selected: AutoLockTimeout,
    longerDisabled: Boolean,
    onSelect: (AutoLockTimeout) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val disabledOptions = AutoLockTimeout.entries.filter { longerDisabled && it.millis > selected.millis }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(AutoLockTimeoutDialogTags.DIALOG),
        title = { Text(stringResource(R.string.privacy_app_lock_timeout_label)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Column(modifier = Modifier.selectableGroup()) {
                    AutoLockTimeout.entries.forEach { option ->
                        TimeoutOptionRow(
                            option = option,
                            selected = option == selected,
                            enabled = option !in disabledOptions,
                            onClick = { onSelect(option) }
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.privacy_app_lock_timeout_note),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(AutoLockTimeoutDialogTags.NOTE)
                )
                if (disabledOptions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.applock_timeout_paused_note),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(AutoLockTimeoutDialogTags.PAUSED_NOTE)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(AutoLockTimeoutDialogTags.CANCEL)
            ) {
                Text(stringResource(R.string.privacy_confirm_cancel))
            }
        }
    )
}

/** One radio row: the whole row is the selectable node (label and helper line read together). */
@Composable
private fun TimeoutOptionRow(
    option: AutoLockTimeout,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val hintRes = autoLockTimeoutHintRes(option)
    // A disabled option is dimmed (the radio dims itself); the text is dimmed the same way, never hidden.
    val textColor = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(AutoLockTimeoutDialogTags.option(option))
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(autoLockTimeoutLabelRes(option)),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )
            if (hintRes != null) {
                Text(
                    text = stringResource(hintRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor
                )
            }
        }
    }
}
