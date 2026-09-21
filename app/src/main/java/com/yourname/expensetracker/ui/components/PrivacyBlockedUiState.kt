package com.yourname.expensetracker.ui.components

import androidx.annotation.StringRes
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.PrivacyBlocked
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGateReasonCodes
import com.yourname.expensetracker.domain.privacy.toPrivacyBlocked

/**
 * RP-15 (15-D): typed, resource-backed denied state for the UI layer.
 *
 * Domain [PrivacyBlocked] carries developer-facing reason strings and must stay
 * a sealed hierarchy (no generic `(capability, resourceId)` constructor). This
 * adapter is the ONLY component that turns a blocked state or a privacy
 * decision into something renderable:
 *
 * - [messageResId] is a string RESOURCE — screens render `stringResource(...)`,
 *   never the domain `reason` string and never exception text.
 * - [reasonCode] is a bounded controlled constant from [PrivacyGateReasonCodes]
 *   (or one of the per-capability codes registered in
 *   [PrivacyBlockedUiState.fromBlocked]) — usable for diagnostics and tests,
 *   never rendered as user-facing text.
 */
data class PrivacyBlockedUiState(
    val capability: PrivacyCapability,
    @StringRes val messageResId: Int,
    val reasonCode: String
) {
    companion object {
        /**
         * Centralized mapping from the domain sealed hierarchy to resource IDs.
         * Every [PrivacyBlocked] subclass maps to a dedicated resource; the
         * [PrivacyBlocked.Custom] branch renders a generic resource (its domain
         * reason — even though it is a controlled code after 15-B — is never
         * passed through to the UI as text).
         */
        fun fromBlocked(blocked: PrivacyBlocked): PrivacyBlockedUiState = when (blocked) {
            is PrivacyBlocked.CloudAiDisabled ->
                PrivacyBlockedUiState(blocked.capability, R.string.privacy_blocked_cloud_ai, PrivacyGateReasonCodes.CLOUD_AI_DISABLED)
            is PrivacyBlocked.ReceiptImageUploadDisabled ->
                PrivacyBlockedUiState(blocked.capability, R.string.privacy_blocked_receipt_image_upload, PrivacyGateReasonCodes.RECEIPT_IMAGE_UPLOAD_DISABLED)
            is PrivacyBlocked.ExternalGeocodingDisabled ->
                PrivacyBlockedUiState(blocked.capability, R.string.privacy_blocked_external_geocoding, PrivacyGateReasonCodes.EXTERNAL_GEOCODING_DISABLED)
            is PrivacyBlocked.NotificationCaptureDisabled ->
                PrivacyBlockedUiState(blocked.capability, R.string.privacy_blocked_notification_capture, PrivacyGateReasonCodes.NOTIFICATION_CAPTURE_DISABLED)
            is PrivacyBlocked.RawExportDisabled ->
                PrivacyBlockedUiState(blocked.capability, R.string.privacy_blocked_raw_export, PrivacyGateReasonCodes.RAW_EXPORT_DISABLED)
            is PrivacyBlocked.DeviceGpsDisabled ->
                PrivacyBlockedUiState(blocked.capability, R.string.privacy_blocked_device_gps, PrivacyGateReasonCodes.DEVICE_GPS_DISABLED)
            is PrivacyBlocked.BackgroundLocationDisabled ->
                PrivacyBlockedUiState(blocked.capability, R.string.privacy_blocked_background_location, PrivacyGateReasonCodes.BACKGROUND_LOCATION_DISABLED)
            is PrivacyBlocked.BankStatementAiDisabled ->
                PrivacyBlockedUiState(blocked.capability, R.string.privacy_blocked_bank_statement_ai, PrivacyGateReasonCodes.BANK_STATEMENT_AI_DISABLED)
            is PrivacyBlocked.EncryptedBackupDisabled ->
                PrivacyBlockedUiState(blocked.capability, R.string.privacy_blocked_encrypted_backup, PrivacyGateReasonCodes.ENCRYPTED_BACKUP_DISABLED)
            is PrivacyBlocked.OverpassDisabled ->
                PrivacyBlockedUiState(blocked.capability, R.string.privacy_blocked_overpass, PrivacyGateReasonCodes.OVERPASS_DISABLED)
            is PrivacyBlocked.DebugDataPersistenceDisabled ->
                PrivacyBlockedUiState(blocked.capability, R.string.privacy_blocked_debug_persistence, PrivacyGateReasonCodes.DEBUG_PERSISTENCE_DISABLED)
            is PrivacyBlocked.Custom ->
                // Defense in depth: even if a Custom reason is not a recognized
                // code, the UI still renders the generic resource and the raw
                // text never reaches the state.
                PrivacyBlockedUiState(
                    blocked.capability,
                    R.string.privacy_blocked_generic,
                    if (blocked.reason in PrivacyGateReasonCodes.ALL) blocked.reason else PrivacyGateReasonCodes.PRIVACY_BLOCKED
                )
        }

        /** Maps a blocking decision, or null when the decision allows execution. */
        fun fromDecision(decision: PrivacyDecision, capability: PrivacyCapability): PrivacyBlockedUiState? =
            decision.toPrivacyBlocked(capability)?.let(::fromBlocked)

        /**
         * Like [fromDecision] but never returns null: permissive decisions fall
         * back to the generic resource + [PrivacyGateReasonCodes.PRIVACY_BLOCKED]
         * (single centralized fallback for ViewModels that must surface a
         * state for every outcome).
         */
        fun fromDecisionOrFallback(decision: PrivacyDecision, capability: PrivacyCapability): PrivacyBlockedUiState =
            fromDecision(decision, capability)
                ?: PrivacyBlockedUiState(capability, R.string.privacy_blocked_generic, PrivacyGateReasonCodes.PRIVACY_BLOCKED)
    }
}
