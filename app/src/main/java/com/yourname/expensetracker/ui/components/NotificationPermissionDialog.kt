package com.yourname.expensetracker.ui.components

import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun NotificationPermissionDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit,
    onEnable: () -> Unit
) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Enable Notifications") },
            text = { Text("Enable notifications to receive budget alerts and expense reminders.") },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        onEnable()
                    }
                }) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Not now")
                }
            }
        )
    }
}
