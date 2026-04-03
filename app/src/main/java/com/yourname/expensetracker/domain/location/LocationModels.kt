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
    ): GeocodingLookupResult

    /**
     * Search for [query] and return **all** matching results (up to [limit]).
     *
     * Used by the interactive location picker UI where the user needs to
     * choose from multiple candidates.  The default implementation delegates
     * to [search] for backwards compatibility.
     *
     * @param useGoogle If true, include Google Places in the search (costs
     *   API quota). Defaults to false so callers opt-in explicitly.
     */
    suspend fun searchMultiple(
        query: String,
        biasLat: Double? = null,
        biasLon: Double? = null,
        limit: Int = 5,
        useGoogle: Boolean = false
    ): GeocodingBatchResult {
        val result = search(query, biasLat, biasLon)
        return when (result) {
            is GeocodingLookupResult.Success -> GeocodingBatchResult.Success(
                listOfNotNull(result.result)
            )
            is GeocodingLookupResult.Failure -> GeocodingBatchResult.Failure(result.error)
        }
    }

    /**
     * Reverse-geocode a coordinate to a [GeocodingResult].
     *
     * Used by the tap-to-pin feature: the user long-presses the results map to drop
     * a pin at an arbitrary coordinate, and this resolves it to a human-readable address.
     *
     * @return The best match for the given coordinate, or null on failure.
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): GeocodingLookupResult =
        GeocodingLookupResult.Failure(GeocodingError.ServiceDown)
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
    ): NearbyPoiResult
}

sealed interface GeocodingLookupResult {
    data class Success(val result: GeocodingResult?) : GeocodingLookupResult
    data class Failure(val error: GeocodingError) : GeocodingLookupResult
}

sealed interface GeocodingBatchResult {
    data class Success(val results: List<GeocodingResult>) : GeocodingBatchResult
    data class Failure(val error: GeocodingError) : GeocodingBatchResult
}

sealed interface NearbyPoiResult {
    data class Success(val pois: List<NearbyPoi>) : NearbyPoiResult
    data class Failure(val error: GeocodingError) : NearbyPoiResult
}

sealed interface GeocodingError {
    data object NoResults : GeocodingError
    data object ServiceDown : GeocodingError
    data object RateLimited : GeocodingError
    data object NetworkError : GeocodingError
    data object Timeout : GeocodingError
    data object ParseError : GeocodingError
    data class HttpError(val code: Int) : GeocodingError
    data class Unknown(val message: String? = null) : GeocodingError
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
