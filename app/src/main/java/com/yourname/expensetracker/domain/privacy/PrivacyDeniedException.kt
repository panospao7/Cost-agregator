package com.yourname.expensetracker.domain.privacy

/**
 * RP-15 (15-D): typed privacy denial for repository/service results, so
 * ViewModels can converge denial paths on [PrivacyBlockedUiState] WITHOUT
 * message matching (e.g. `contains("denied")`).
 *
 * [reasonCode] is the typed transport contract. Both it and the compatibility
 * message are bounded controlled codes; unrecognized input fails closed.
 * Extends [SecurityException] so existing `catch (e: SecurityException)`
 * boundaries keep treating it as a denial.
 */
class PrivacyDeniedException(
    val capability: PrivacyCapability,
    reasonCode: String = PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE
) : SecurityException(boundedPrivacyReasonCode(reasonCode)) {
    val reasonCode: String = boundedPrivacyReasonCode(reasonCode)
}

private fun boundedPrivacyReasonCode(reasonCode: String): String =
    reasonCode.takeIf { it in PrivacyGateReasonCodes.ALL }
        ?: PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE
