package com.yourname.expensetracker.domain.location

/**
 * Result from a geocoding lookup (Nominatim or similar).
 */
data class GeocodingResult(
    val latitude: Double,
    val longitude: Double,
    /** OSM place ID (e.g. "N123456789") — useful for cache keying and re-lookup. */
    val osmId: String?,
    /** Human-readable address returned by the service. */
    val displayAddress: String?,
    /** Confidence in [0.0, 1.0] assigned by the resolver based on result rank / importance. */
    val confidence: Float,
    /** Source tag — one of AppConfig.Location.SOURCE_* constants. */
    val source: String
)
