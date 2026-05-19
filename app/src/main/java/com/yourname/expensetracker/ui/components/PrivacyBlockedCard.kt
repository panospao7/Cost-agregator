package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
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
import com.yourname.expensetracker.domain.privacy.PrivacyBlocked
import com.yourname.expensetracker.domain.privacy.PrivacyCapability

/**
 * Displays a privacy-blocked state card with typed capability info.
 *
 * Shows the user why a feature is disabled and optionally provides
 * a button to navigate to privacy settings.
 */
@Composable
fun PrivacyBlockedCard(
    blocked: PrivacyBlocked,
    modifier: Modifier = Modifier,
    onOpenPrivacySettings: (() -> Unit)? = null
) {
    val description = "Feature disabled: ${blocked.capability.displayLabel()}. ${blocked.reason}"
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("privacy_blocked_card")
            .semantics { contentDescription = description },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.privacy_feature_disabled),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    blocked.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onOpenPrivacySettings != null) {
                TextButton(onClick = onOpenPrivacySettings) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.privacy_settings_cta))
                }
            }
        }
    }
}

/**
 * Human-readable label for each privacy capability.
 */
fun PrivacyCapability.displayLabel(): String = when (this) {
    PrivacyCapability.NOTIFICATION_CAPTURE -> "Notification Capture"
    PrivacyCapability.CLOUD_AI_GENERAL -> "Cloud AI"
    PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST -> "Receipt AI Assist"
    PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION -> "Item Categorization"
    PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION -> "Warranty Extraction"
    PrivacyCapability.CLOUD_AI_BANK_STATEMENT -> "Bank Statement AI"
    PrivacyCapability.AI_BANK_STATEMENT_PARSING -> "Bank Statement Parsing"
    PrivacyCapability.CLOUD_AI_DAILY_BRIEFING -> "Daily Briefing"
    PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD -> "Receipt Image Upload"
    PrivacyCapability.EXTERNAL_GEOCODING -> "Location Enrichment"
    PrivacyCapability.BACKGROUND_LOCATION_BACKFILL -> "Background Location"
    PrivacyCapability.DEVICE_GPS_LOCATION -> "Device GPS"
    PrivacyCapability.RAWBACKUP_EXPORT -> "Raw Export"
    PrivacyCapability.ENCRYPTED_BACKUP -> "Encrypted Backup"
    PrivacyCapability.OVERPASS_API -> "Overpass API"
    PrivacyCapability.NOTIFICATION_PACKAGE_ALLOWLIST -> "Package Allowlist"
    PrivacyCapability.RAW_NOTIFICATION_RETENTION -> "Notification Retention"
    PrivacyCapability.RAW_OCR_RETENTION -> "OCR Retention"
    PrivacyCapability.DEBUG_DATA_PERSISTENCE -> "Debug Data"
    PrivacyCapability.TIMBER_PII_LOGGING -> "PII Logging"
    PrivacyCapability.EXPENSE_EXPORT -> "Expense Export"
    PrivacyCapability.EXPENSE_EXPORT_RAW -> "Raw Expense Export"
    PrivacyCapability.EXPENSE_EXPORT_REDACTED -> "Redacted Export"
    PrivacyCapability.EXPENSE_EXPORT_ENCRYPTED -> "Encrypted Export"
    PrivacyCapability.DEBUG_RAW_EXPORT -> "Debug Raw Export"
    PrivacyCapability.RAW_DATABASE_EXPORT -> "Raw Database Export"
}
