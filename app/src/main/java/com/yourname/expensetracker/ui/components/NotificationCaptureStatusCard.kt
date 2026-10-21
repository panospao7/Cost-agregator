package com.yourname.expensetracker.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.notification.capture.NotificationCaptureStatus

/**
 * R17: stateless card describing notification-capture status.
 *
 * ACCESS_OFF explains that Android notification-listener access is off, offers a
 * "Manage access" button and (Android 13+) a hint about restricted settings for sideloaded
 * installs. ACTIVE / CONSENT_OFF render a compact status line for the privacy screen.
 * [alwaysShowManage] keeps the button visible in every state (privacy screen row).
 */
@Composable
fun NotificationCaptureStatusCard(
    status: NotificationCaptureStatus,
    onManageAccess: () -> Unit,
    modifier: Modifier = Modifier,
    alwaysShowManage: Boolean = false,
    openFailed: Boolean = false
) {
    val isAccessOff = status == NotificationCaptureStatus.ACCESS_OFF
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isAccessOff) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.notification_access_title),
                style = MaterialTheme.typography.titleSmall,
                color = if (isAccessOff) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = stringResource(
                    when (status) {
                        NotificationCaptureStatus.ACTIVE -> R.string.notification_access_active
                        NotificationCaptureStatus.ACCESS_OFF -> R.string.notification_access_off_body
                        NotificationCaptureStatus.CONSENT_OFF -> R.string.notification_access_consent_off
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (isAccessOff) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (isAccessOff && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Text(
                    text = stringResource(R.string.notification_access_restricted_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (openFailed) {
                Text(
                    text = stringResource(R.string.notification_access_open_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (isAccessOff || alwaysShowManage) {
                OutlinedButton(
                    onClick = onManageAccess,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(stringResource(R.string.notification_access_manage))
                }
            }
        }
    }
}
