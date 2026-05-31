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
 *
 * ## Provider coverage (P8-P1-10)
 * Every external location/geocoding provider self-checks the relevant capability
 * before making a network call:
 * - `CompositeGeocodingService`, `NominatimGeocodingService`, `GeoapifyGeocodingService`,
 *   `GooglePlacesGeocodingService`, `PhotonGeocodingService` → [PrivacyCapability.EXTERNAL_GEOCODING]
 * - `OverpassNearbyService` → [PrivacyCapability.OVERPASS_API]
 *
 * This is statically enforced by guard rule **G14** in
 * `scripts/verify_privacy_boundaries.py`: any `*GeocodingService`/`*NearbyService`
 * that declares a [PrivacyGate] dependency MUST call `privacyGate.check(...)`.
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
                PrivacyDecision.NotApplicable
            }
        }

        if (decision is PrivacyDecision.Denied) {
            Timber.d("Location gate denied: ${decision.reason} (capability=$capability)")
        }
        return decision
    }
}
