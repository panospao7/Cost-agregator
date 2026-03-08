package com.yourname.expensetracker.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Shown before requesting ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION.
 * We never request background location — foreground only.
 */
@Composable
fun LocationPermissionDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit,
    onGrant: () -> Unit
) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Enable Location") },
            text = {
                Text(
                    "To show your spending on the map and identify nearby merchants, " +
                    "the app needs access to your device location while it is in use. " +
                    "No background location is requested."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    onGrant()
                }) {
                    Text("Allow")
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
