package com.yourname.expensetracker.domain.privacy

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy gate that guards location-related capabilities.
 *
 * Checks:
 * 1. [PrivacySettings.externalGeocodingEnabled] — external geocoding (Nominatim/OSM)
 * 2. [PrivacySettings.backgroundLocationBackfillEnabled] — background location backfill
 * 3. [PrivacySettings.deviceGpsLocationEnabled] — device GPS access
 *
 * Each check returns [PrivacyDecision.Denied] with a specific reason when the
 * corresponding setting is disabled. Capabilities not handled by this gate
 * default to [PrivacyDecision.Allowed].
 */
@Singleton
class LocationPrivacyGate @Inject constructor(
    private val settingsRepository: PrivacySettingsRepository,
    private val auditLogger: PrivacyAuditLogger
) : PrivacyGate {

    override suspend fun check(
        capability: PrivacyCapability,
        context: Map<String, String>
    ): PrivacyDecision {
        val settings = settingsRepository.getSettings()

        val decision = when (capability) {
            PrivacyCapability.EXTERNAL_GEOCODING -> {
                if (!settings.externalGeocodingEnabled) {
                    PrivacyDecision.Denied("External geocoding is disabled by user setting")
                } else {
                    PrivacyDecision.Allowed
                }
            }

            PrivacyCapability.BACKGROUND_LOCATION_BACKFILL -> {
                if (!settings.backgroundLocationBackfillEnabled) {
                    PrivacyDecision.Denied("Background location backfill is disabled by user setting")
                } else {
                    PrivacyDecision.Allowed
                }
            }

            PrivacyCapability.DEVICE_GPS_LOCATION -> {
                if (!settings.deviceGpsLocationEnabled) {
                    PrivacyDecision.Denied("Device GPS location is disabled by user setting")
                } else {
                    PrivacyDecision.Allowed
                }
            }

            PrivacyCapability.OVERPASS_API -> {
                // Overpass API queries are also location-related; gate on external geocoding
                if (!settings.externalGeocodingEnabled) {
                    PrivacyDecision.Denied("Overpass API is disabled because external geocoding is disabled")
                } else {
                    PrivacyDecision.Allowed
                }
            }

            else -> {
                // Delegate to a default "allow" for capabilities this gate doesn't handle
                PrivacyDecision.Allowed
            }
        }

        auditLogger.logDecision(capability, decision, context)
        if (decision is PrivacyDecision.Denied) {
            Timber.d("Location gate denied: ${decision.reason} (capability=$capability)")
        }
        return decision
    }
}
