package com.yourname.expensetracker.domain.privacy

import javax.inject.Inject
import javax.inject.Singleton

/**
 * R23: Privacy gate for public-network capabilities that carry no user data.
 *
 * Sole owner of [PrivacyCapability.EXCHANGE_RATE_DOWNLOAD]:
 * - allowed only when [PrivacySettings.exchangeRateDownloadEnabled] is **true** (default OFF);
 * - fails closed ([PrivacyDecision.FailClosed] with
 *   [PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE]) when the persisted settings
 *   could not be trusted ([PrivacySettingsLoadState.CorruptedFailClosed]);
 * - denies with the bounded [PrivacyGateReasonCodes.EXCHANGE_RATE_DOWNLOAD_DISABLED] code.
 *
 * Any other capability returns [PrivacyDecision.NotApplicable] without reading
 * settings. Exceptions are not caught here: [CompositePrivacyGate] maps them to
 * a fail-closed decision and propagates cancellation.
 */
@Singleton
class NetworkPrivacyGate @Inject constructor(
    private val settingsRepository: PrivacySettingsRepository
) : PrivacyGate {

    override suspend fun check(
        capability: PrivacyCapability,
        context: Map<String, String>
    ): PrivacyDecision = when (capability) {
        PrivacyCapability.EXCHANGE_RATE_DOWNLOAD -> {
            when (val state = settingsRepository.getLoadState()) {
                is PrivacySettingsLoadState.CorruptedFailClosed ->
                    PrivacyDecision.FailClosed(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE)
                is PrivacySettingsLoadState.Loaded ->
                    decide(state.settings.exchangeRateDownloadEnabled)
                is PrivacySettingsLoadState.FirstRunDefault ->
                    decide(state.settings.exchangeRateDownloadEnabled)
            }
        }

        else -> PrivacyDecision.NotApplicable
    }

    private fun decide(enabled: Boolean): PrivacyDecision =
        if (enabled) {
            PrivacyDecision.Allowed
        } else {
            PrivacyDecision.Denied(PrivacyGateReasonCodes.EXCHANGE_RATE_DOWNLOAD_DISABLED)
        }
}
