package com.yourname.expensetracker.ui.components

import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R

@Composable
fun NotificationPermissionDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit,
    onEnable: () -> Unit,
    // R24b: when the system will no longer show its dialog, the button opens app notification
    // settings (handled by the caller's onEnable) and is labelled accordingly.
    openSettings: Boolean = false
) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.notification_enable_title)) },
            text = { Text(stringResource(R.string.notification_enable_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    if (openSettings || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        onEnable()
                    }
                }) {
                    Text(
                        stringResource(
                            if (openSettings) R.string.notification_open_settings_button
                            else R.string.notification_enable_button
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.notification_not_now_button))
                }
            }
        )
    }
}
