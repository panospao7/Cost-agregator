package com.yourname.expensetracker.domain.location

/**
 * Domain interfaces and shared models for the location resolution system.
 */

/**
 * Resolves a merchant name to geographic coordinates via Nominatim or similar.
 */
interface GeocodingService {
    /**
     * Search for [merchantName] with an optional GPS bias point.
     *
     * @param merchantName  Cleaned/normalized merchant name to search for.
     * @param biasLat       Optional latitude to bias results toward (device location).
     * @param biasLon       Optional longitude to bias results toward.
     * @param cityHint      Optional city/area hint appended to the query for better disambiguation.
     * @param bounded       If true, restrict results strictly to the viewbox (no fallback outside it).
     * @return              Best match, or null if no result found.
     */
    suspend fun search(
        merchantName: String,
        biasLat: Double? = null,
        biasLon: Double? = null,
        cityHint: String? = null,
        bounded: Boolean = false
    ): GeocodingResult?
}

/**
 * Finds nearby points-of-interest around a given coordinate via Overpass or similar.
 */
interface NearbyPoiService {
    /**
     * Find shops/businesses within [radiusMetres] of ([lat], [lon]) whose name
     * roughly matches [merchantName].
     *
     * @return  Ranked list of candidates (closest first), empty if none found.
     */
    suspend fun findNearby(
        lat: Double,
        lon: Double,
        merchantName: String,
        radiusMetres: Int
    ): List<NearbyPoi>
}

/**
 * Provides the device's current foreground GPS location.
 */
interface ForegroundLocationProvider {
    /**
     * Returns the last known location as (lat, lon), or null if unavailable
     * (no permission, location disabled, cold start).
     */
    suspend fun getLastKnownLocation(): Pair<Double, Double>?
}

/**
 * Sealed result type for the full resolution pipeline.
 */
sealed class LocationResolutionResult {
    /** Successfully resolved to a single coordinate. */
    data class Resolved(
        val latitude: Double,
        val longitude: Double,
        val source: String,
        val osmId: String? = null,
        val displayAddress: String? = null,
        val confidence: Float = 1.0f
    ) : LocationResolutionResult()

    /**
     * Multiple candidates returned by Overpass — the UI must present them
     * to the user for manual selection.
     */
    data class NeedsUserSelection(val candidates: List<NearbyPoi>) : LocationResolutionResult()

    /** Could not resolve — expense latitude remains null. */
    object Unresolved : LocationResolutionResult()
}
