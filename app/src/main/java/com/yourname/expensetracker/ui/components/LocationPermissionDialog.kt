package com.yourname.expensetracker.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R

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
            title = { Text(stringResource(R.string.location_enable_title)) },
            text = {
                Text(stringResource(R.string.location_enable_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    onGrant()
                }) {
                    Text(stringResource(R.string.location_allow_button))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.location_not_now_button))
                }
            }
        )
    }
}
