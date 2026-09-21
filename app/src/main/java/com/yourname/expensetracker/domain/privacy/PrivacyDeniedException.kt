package com.yourname.expensetracker.domain.privacy

/**
 * RP-15 (15-D): typed privacy denial for repository/service results, so
 * ViewModels can converge denial paths on [PrivacyBlockedUiState] WITHOUT
 * message matching (e.g. `contains("denied")`).
 *
 * The message is ALWAYS a bounded controlled code from
 * [PrivacyGateReasonCodes] — never decision text, never exception text.
 * Extends [SecurityException] so existing `catch (e: SecurityException)`
 * boundaries keep treating it as a denial.
 */
class PrivacyDeniedException(
    val capability: PrivacyCapability,
    reasonCode: String = PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE
) : SecurityException(reasonCode)
