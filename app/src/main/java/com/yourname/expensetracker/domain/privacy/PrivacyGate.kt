package com.yourname.expensetracker.domain.privacy

/**
 * Contract for a privacy capability gate.
 *
 * Each [PrivacyGate] implementation is responsible for evaluating a subset of
 * [PrivacyCapability] values against the current [PrivacySettings] and returning
 * a [PrivacyDecision].
 *
 * ## Contract
 *
 * 1. **Single responsibility** — a gate handles only the capabilities it is
 *    designed for. Capabilities it does not recognise MUST return
 *    [PrivacyDecision.NotApplicable] (not [PrivacyDecision.Allowed]).
 * 2. **No individual audit** — individual gates do NOT audit final decisions.
 *    [CompositePrivacyGate] performs the final audit after all gates have been
 *    consulted.
 * 3. **Deterministic** — for the same capability + settings combination, the
 *    gate MUST always return the same decision.
 * 4. **Fail closed** — if the gate cannot determine the setting (e.g.
 *    repository error), it SHOULD return [PrivacyDecision.FailClosed] with a
 *    descriptive reason rather than silently allowing.
 *
 * ## Decision semantics
 *
 * - [PrivacyDecision.Allowed] — this gate positively handles the capability and
 *   allows it. The composite gate continues to the next gate.
 * - [PrivacyDecision.Denied] — this gate blocks the capability. The composite
 *   gate stops and returns Denied.
 * - [PrivacyDecision.FailClosed] — an error occurred; the composite gate stops
 *   and returns FailClosed.
 * - [PrivacyDecision.NotApplicable] — this gate does not handle the capability.
 *   If no gate handles a gate-handled capability, [CompositePrivacyGate] returns
 *   FailClosed (not Allowed).
 *
 * ## Existing implementations
 *
 * - [NotificationPrivacyGate] — guards NOTIFICATION_CAPTURE and
 *   NOTIFICATION_PACKAGE_ALLOWLIST.
 * - [LocationPrivacyGate] — guards EXTERNAL_GEOCODING,
 *   BACKGROUND_LOCATION_BACKFILL, DEVICE_GPS_LOCATION, and OVERPASS_API.
 * - [CloudAiPrivacyGate] — guards all CLOUD_AI_* capabilities plus
 *   RECEIPT_IMAGE_CLOUD_UPLOAD.
 * - [BackupPrivacyGate] — guards RAWBACKUP_EXPORT and ENCRYPTED_BACKUP.
 * - [ExportPrivacyGate] — guards EXPENSE_EXPORT_* and DEBUG/RAW export capabilities.
 *
 * Gates are composed via [CompositePrivacyGate], which iterates through the
 * chain and returns the first [PrivacyDecision.Denied] or [PrivacyDecision.FailClosed]
 * encountered, or FailClosed if a gate-handled capability has no handler.
 */
interface PrivacyGate {
    /**
     * Evaluates whether [capability] should be allowed given the current
     * privacy settings.
     *
     * @param capability the capability to check
     * @param context optional metadata about the caller / operation (logged
     *                for audit purposes)
     * @return [PrivacyDecision.Allowed] or [PrivacyDecision.Denied] with a
     *         human-readable reason
     */
    suspend fun check(capability: PrivacyCapability, context: Map<String, String> = emptyMap()): PrivacyDecision
}
