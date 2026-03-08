package com.yourname.expensetracker.domain.location

/**
 * Result from a geocoding lookup (Nominatim or similar).
 */
data class GeocodingResult(
    val latitude: Double,
    val longitude: Double,
    /** OSM place ID (e.g. "N123456789") — useful for cache keying and re-lookup. */
    val osmId: String?,
    /**
     * Business / place name only (e.g. "YES! Stores", "Masoutis").
     * Shown as the bold headline in the picker list.
     * Null if the service doesn't return a name separately from the address.
     */
    val name: String?,
    /** Human-readable full address returned by the service. */
    val displayAddress: String?,
    /** Confidence in [0.0, 1.0] assigned by the resolver based on result rank / importance. */
    val confidence: Float,
    /** Source tag — one of AppConfig.Location.SOURCE_* constants. */
    val source: String
)
