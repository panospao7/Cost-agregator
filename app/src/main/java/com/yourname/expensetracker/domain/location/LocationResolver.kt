package com.yourname.expensetracker.domain.location

import android.util.Log
import com.yourname.expensetracker.data.location.internal.anonymizeForLog
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.categorization.MerchantCanonicalizer
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core location resolution engine.
 *
 * Resolution priority (first non-null result wins):
 *  1. User correction (area-scoped)
 *  2. Merchant cache hit
 *  3. Nominatim with GPS bias (transaction < 2 hrs AND device location available)
 *  4. Nominatim name-only with Greece bias
 *  5. Overpass nearby POIs  →  [LocationResolutionResult.NeedsUserSelection]
 *  6. Unresolved
 *
 * Rate limiting is enforced in the geocoding service layer.
 */
@Singleton
class LocationResolver @Inject constructor(
    private val geocodingService: GeocodingService,
    private val nearbyPoiService: NearbyPoiService,
    private val locationProvider: ForegroundLocationProvider,
    private val locationCachePort: LocationCachePort,
    private val merchantClusterPort: MerchantClusterPort,
    private val merchantCleaner: MerchantCleaner,
    private val canonicalizer: MerchantCanonicalizer,
    private val greeklishNormalizer: GreeklishNormalizer,
    private val timeProvider: TimeProvider
) {
    /**
     * Resolve the location for an expense identified by [rawMerchantName].
     *
     * @param rawMerchantName  Raw merchant string (as stored in Expense).
     * @param transactionDateMs  Epoch ms of the transaction — used to decide GPS bias.
     * @param forceRefresh  If true, skip the cache and re-geocode.
     * @return  A [LocationResolutionResult].
     */
    suspend fun resolve(
        rawMerchantName: String,
        transactionDateMs: Long,
        forceRefresh: Boolean = false,
        merchantKey: String? = null
    ): LocationResolutionResult {

        // ── Step 1: Prepare name variants ─────────────────────────────────────
        val cleanedName = merchantCleaner.clean(rawMerchantName)
        val canonicalResult = canonicalizer.canonicalize(cleanedName)
        val latinName = greeklishNormalizer.normalize(canonicalResult.canonicalName)
        // cacheKey: prefer a pre-computed canonical key (e.g. from Expense.merchantKey)
        // to avoid double-normalization; fall back to generating it from the raw name.
        val cacheKey = merchantKey ?: MerchantKeyGenerator.generate(rawMerchantName)

        // ── Step 2: Compute recency, but defer device location lookup ──────────
        val isRecent = (timeProvider.now() - transactionDateMs) < AppConfig.Location.RECENT_TRANSACTION_THRESHOLD_MS
        var cachedDeviceLocation: Pair<Double, Double>? = null
        var hasLoadedDeviceLocation = false

        suspend fun getDeviceLocation(): Pair<Double, Double>? {
            if (!hasLoadedDeviceLocation) {
                cachedDeviceLocation = locationProvider.getLastKnownLocation()
                hasLoadedDeviceLocation = true
            }
            return cachedDeviceLocation
        }

        // ── Step 3: Check global user corrections first ───────────────────────
        val correction = locationCachePort.getCorrection(
            merchantName = cacheKey,
            deviceLat = null,
            deviceLon = null
        )
        if (correction != null) {
            // HIGH-14 FIX: Anonymize merchant names in logs using hash
            Log.d(TAG, "Correction hit for merchant hash: ${cacheKey.anonymizeForLog()}")
            return LocationResolutionResult.Resolved(
                latitude = correction.correctedLatitude,
                longitude = correction.correctedLongitude,
                source = AppConfig.Location.SOURCE_USER_MANUAL,
                osmId = correction.osmId,
                displayAddress = correction.displayAddress,
                confidence = 1.0f
            )
        }

        // If device coordinates are available, run a second correction lookup
        // before cache/geocoding so area-scoped manual corrections still take
        // priority after device-location lookup was deferred.
        val deviceLocation = getDeviceLocation()
        if (deviceLocation != null) {
            val areaScopedCorrection = locationCachePort.getCorrection(
                merchantName = cacheKey,
                deviceLat = deviceLocation.first,
                deviceLon = deviceLocation.second
            )
            if (areaScopedCorrection != null) {
                Log.d(TAG, "Area correction hit for merchant hash: ${cacheKey.anonymizeForLog()}")
                return LocationResolutionResult.Resolved(
                    latitude = areaScopedCorrection.correctedLatitude,
                    longitude = areaScopedCorrection.correctedLongitude,
                    source = AppConfig.Location.SOURCE_USER_MANUAL,
                    osmId = areaScopedCorrection.osmId,
                    displayAddress = areaScopedCorrection.displayAddress,
                    confidence = 1.0f
                )
            }
        }

        // ── Step 4: History-biased lookup (Merchant Location Affinity) ──────────
        // Query past located expenses for this merchant, find the top cluster,
        // and use it to bias cache lookup and Nominatim with bounded=1.
        // This runs BEFORE global cache so area-scoped results take priority.
        // Use MerchantKeyGenerator so the query matches the merchantKey column.
        val clusters = try {
            merchantClusterPort.getMerchantLocationClusters(cacheKey)
        } catch (e: Exception) {
            // HIGH-14 FIX: Anonymize merchant names in logs
            Log.w(TAG, "Cluster query failed for merchant hash: ${cacheKey.anonymizeForLog()}", e)
            emptyList()
        }
        val topCluster = clusters.firstOrNull { it.count >= 2 }
        if (topCluster != null) {
            val areaKey = locationCachePort.getMostLikelyArea(cacheKey, topCluster.centerLat, topCluster.centerLon)
            if (!forceRefresh) {
                val cachedForArea = locationCachePort.getCachedLocationForArea(cacheKey, areaKey)
                if (cachedForArea != null) {
                    // HIGH-14 FIX: Anonymize merchant names in logs
                    Log.d(TAG, "Area-cache hit for merchant hash: ${cacheKey.anonymizeForLog()} in area $areaKey")
                    return LocationResolutionResult.Resolved(
                        latitude = cachedForArea.latitude,
                        longitude = cachedForArea.longitude,
                        source = cachedForArea.source,
                        osmId = cachedForArea.osmId,
                        displayAddress = cachedForArea.displayAddress,
                        confidence = cachedForArea.confidence
                    )
                }
            }
            // Call Nominatim bounded around cluster centre
            val clusterResult = geocode(
                name = latinName,
                biasLat = topCluster.centerLat,
                biasLon = topCluster.centerLon,
                bounded = true
            ).orElseGeocode(
                name = cleanedName,
                biasLat = topCluster.centerLat,
                biasLon = topCluster.centerLon,
                bounded = true
            )
            when (clusterResult) {
                is GeocodeAttempt.Found -> {
                // HIGH-14 FIX: Anonymize merchant names in logs
                    Log.d(TAG, "History-biased Nominatim resolved merchant hash: ${cacheKey.anonymizeForLog()} (cluster count=${topCluster.count})")
                    val resolved = clusterResult.result.toResolved()
                    locationCachePort.saveLocation(cacheKey, resolved, areaKey)
                    return resolved
                }
                is GeocodeAttempt.Retryable -> return LocationResolutionResult.Retryable(clusterResult.error)
                GeocodeAttempt.NoMatch -> Unit
            }
        }

        // ── Step 4b: Global cache fallback ────────────────────────────────────
        if (!forceRefresh) {
            val cached = locationCachePort.getCachedLocation(cacheKey)
            if (cached != null) {
                // HIGH-14 FIX: Anonymize merchant names in logs
                Log.d(TAG, "Cache hit for merchant hash: ${cacheKey.anonymizeForLog()}")
                return LocationResolutionResult.Resolved(
                    latitude = cached.latitude,
                    longitude = cached.longitude,
                    source = cached.source,
                    osmId = cached.osmId,
                    displayAddress = cached.displayAddress,
                    confidence = cached.confidence
                )
            }
        }

        // ── Step 5: Nominatim with GPS bias (recent transactions only) ─────────
        val gpsBiasLocation = if (isRecent) deviceLocation else null
        if (isRecent && gpsBiasLocation != null) {
            val result = geocode(
                name = latinName,
                biasLat = gpsBiasLocation.first,
                biasLon = gpsBiasLocation.second
            ).orElseGeocode(
                name = cleanedName,  // retry with original Greek
                biasLat = gpsBiasLocation.first,
                biasLon = gpsBiasLocation.second
            )
            when (result) {
                is GeocodeAttempt.Found -> {
                // HIGH-14 FIX: Anonymize merchant names in logs
                    Log.d(TAG, "Nominatim GPS-bias resolved merchant hash: ${cacheKey.anonymizeForLog()}")
                    val resolved = result.result.toResolved()
                    val areaKey = getAreaKeyForResolvedLocation(
                        merchantName = cacheKey,
                        resolvedLat = resolved.latitude,
                        resolvedLon = resolved.longitude,
                        fallbackLat = gpsBiasLocation.first,
                        fallbackLon = gpsBiasLocation.second
                    )
                    locationCachePort.saveLocation(cacheKey, resolved, areaKey)
                    return resolved
                }
                is GeocodeAttempt.Retryable -> return LocationResolutionResult.Retryable(result.error)
                GeocodeAttempt.NoMatch -> Unit
            }
        }

        // ── Step 6: Nominatim name-only (Greece bias) ─────────────────────────
        val nameOnlyResult = geocode(latinName)
            .orElseGeocode(cleanedName)
        when (nameOnlyResult) {
            is GeocodeAttempt.Found -> {
            // HIGH-14 FIX: Anonymize merchant names in logs
                Log.d(TAG, "Nominatim name-only resolved merchant hash: ${cacheKey.anonymizeForLog()}")
                val resolved = nameOnlyResult.result.toResolved()
                val areaKey = getAreaKeyForResolvedLocation(
                    merchantName = cacheKey,
                    resolvedLat = resolved.latitude,
                    resolvedLon = resolved.longitude
                )
                locationCachePort.saveLocation(cacheKey, resolved, areaKey)
                return resolved
            }
            is GeocodeAttempt.Retryable -> return LocationResolutionResult.Retryable(nameOnlyResult.error)
            GeocodeAttempt.NoMatch -> Unit
        }

        // ── Step 7: Overpass nearby POIs (requires device location) ───────────
        val overpassLocation = deviceLocation
        if (overpassLocation != null) {
            val nearbyResult = nearbyPoiService.findNearby(
                lat = overpassLocation.first,
                lon = overpassLocation.second,
                merchantName = cleanedName,
                radiusMetres = AppConfig.Location.OVERPASS_SEARCH_RADIUS_M
            )
            val pois = when (nearbyResult) {
                is NearbyPoiResult.Success -> nearbyResult.pois
                is NearbyPoiResult.Failure -> {
                    Timber.w("Overpass lookup failed for merchant hash ${cacheKey.anonymizeForLog()}: ${nearbyResult.error}")
                    if (isTransient(nearbyResult.error)) {
                        return LocationResolutionResult.Retryable(nearbyResult.error)
                    }
                    emptyList()
                }
            }.filter { !isNullIsland(it.latitude, it.longitude) }
            if (pois.isNotEmpty()) {
                // HIGH-14 FIX: Anonymize merchant names in logs
                Log.d(TAG, "Overpass found ${pois.size} candidates for merchant hash: ${cacheKey.anonymizeForLog()}")
                return if (pois.size == 1) {
                    // Single match — auto-resolve
                    val poi = pois.first()
                    val resolved = LocationResolutionResult.Resolved(
                        latitude = poi.latitude,
                        longitude = poi.longitude,
                        source = AppConfig.Location.SOURCE_OVERPASS_POI,
                        osmId = poi.osmId,
                        displayAddress = poi.displayAddress,
                        confidence = 0.7f
                    )
                    locationCachePort.saveLocation(cacheKey, resolved)
                    resolved
                } else {
                    LocationResolutionResult.NeedsUserSelection(pois)
                }
            }
        }

        // ── Step 8: Give up ───────────────────────────────────────────────────
        // HIGH-14 FIX: Anonymize merchant names in logs
        Log.d(TAG, "Could not resolve location for merchant hash: ${cacheKey.anonymizeForLog()}")
        return LocationResolutionResult.Unresolved
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun geocode(
        name: String,
        biasLat: Double? = null,
        biasLon: Double? = null,
        cityHint: String? = null,
        bounded: Boolean = false
    ): GeocodeAttempt {
        return when (val result = geocodingService.search(name, biasLat, biasLon, cityHint, bounded)) {
            is GeocodingLookupResult.Success -> result.result
                ?.takeUnless { isNullIsland(it.latitude, it.longitude) }
                ?.let(GeocodeAttempt::Found)
                ?: GeocodeAttempt.NoMatch
            is GeocodingLookupResult.Failure -> {
                Timber.w("Geocoding failed for merchant hash '${name.anonymizeForLog()}': ${result.error}")
                if (isTransient(result.error)) {
                    GeocodeAttempt.Retryable(result.error)
                } else {
                    GeocodeAttempt.NoMatch
                }
            }
        }
    }

    private suspend fun GeocodeAttempt.orElseGeocode(
        name: String,
        biasLat: Double? = null,
        biasLon: Double? = null,
        cityHint: String? = null,
        bounded: Boolean = false
    ): GeocodeAttempt = when (this) {
        is GeocodeAttempt.Found,
        is GeocodeAttempt.Retryable -> this
        GeocodeAttempt.NoMatch -> geocode(name, biasLat, biasLon, cityHint, bounded)
    }

    private fun GeocodingResult.toResolved() = LocationResolutionResult.Resolved(
        latitude = latitude,
        longitude = longitude,
        source = source,
        osmId = osmId,
        displayAddress = displayAddress,
        confidence = confidence
    )

    private fun getAreaKeyForResolvedLocation(
        merchantName: String,
        resolvedLat: Double?,
        resolvedLon: Double?,
        fallbackLat: Double? = null,
        fallbackLon: Double? = null
    ): String {
        val (areaLat, areaLon) = if (resolvedLat != null && resolvedLon != null) {
            resolvedLat to resolvedLon
        } else {
            fallbackLat to fallbackLon
        }
        return locationCachePort.getMostLikelyArea(merchantName, areaLat, areaLon)
    }

    /**
     * Reject coordinates at or very near (0.0, 0.0) — "Null Island".
     * GPS hardware, uninitialised DB fields, and some geocoders can emit
     * (0, 0) which is in the Gulf of Guinea, not a real merchant location.
     */
    private fun isNullIsland(lat: Double, lon: Double): Boolean =
        Math.abs(lat) < 0.01 && Math.abs(lon) < 0.01

    private fun isTransient(error: GeocodingError): Boolean = when (error) {
        GeocodingError.RateLimited,
        GeocodingError.ServiceDown,
        GeocodingError.NetworkError,
        GeocodingError.Timeout -> true
        is GeocodingError.HttpError -> error.code >= 500
        else -> false
    }

    private sealed interface GeocodeAttempt {
        data class Found(val result: GeocodingResult) : GeocodeAttempt
        data class Retryable(val error: GeocodingError) : GeocodeAttempt
        data object NoMatch : GeocodeAttempt
    }

    private companion object {
        const val TAG = "LocationResolver"
    }
}
