package com.yourname.expensetracker.domain.location

import android.util.Log
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.categorization.MerchantCanonicalizer
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.MerchantCleaner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Rate limiting: enforced via a simple sequential lock + delay before each
 * Nominatim HTTP call.  Overpass does not have a strict per-second policy but
 * shares the same throttle for safety.
 */
@Singleton
class LocationResolver @Inject constructor(
    private val geocodingService: GeocodingService,
    private val nearbyPoiService: NearbyPoiService,
    private val locationProvider: ForegroundLocationProvider,
    private val locationRepository: MerchantLocationRepository,
    private val expenseRepository: ExpenseRepository,
    private val merchantCleaner: MerchantCleaner,
    private val canonicalizer: MerchantCanonicalizer,
    private val greeklishNormalizer: GreeklishNormalizer
) {
    /** Ensures at most one geocoding request at a time (rate-limiting). */
    private val rateLimitMutex = Mutex()
    private var lastRequestAt = 0L

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
        forceRefresh: Boolean = false
    ): LocationResolutionResult {

        // ── Step 1: Prepare name variants ─────────────────────────────────────
        val cleanedName = merchantCleaner.clean(rawMerchantName)
        val canonicalResult = canonicalizer.canonicalize(cleanedName)
        val latinName = greeklishNormalizer.normalize(canonicalResult.canonicalName)

        // ── Step 2: Get device location (if available) ─────────────────────────
        val deviceLocation: Pair<Double, Double>? = locationProvider.getLastKnownLocation()
        val isRecent = (System.currentTimeMillis() - transactionDateMs) < AppConfig.Location.RECENT_TRANSACTION_THRESHOLD_MS

        // ── Step 3: Check user corrections ────────────────────────────────────
        val correction = locationRepository.getCorrection(
            merchantName = cleanedName,
            deviceLat = deviceLocation?.first,
            deviceLon = deviceLocation?.second
        )
        if (correction != null) {
            Log.d(TAG, "Correction hit for '$cleanedName'")
            return LocationResolutionResult.Resolved(
                latitude = correction.correctedLatitude,
                longitude = correction.correctedLongitude,
                source = AppConfig.Location.SOURCE_USER_MANUAL,
                osmId = correction.osmId,
                displayAddress = correction.displayAddress,
                confidence = 1.0f
            )
        }

        // ── Step 4: Check merchant cache ──────────────────────────────────────
        if (!forceRefresh) {
            val cached = locationRepository.getCachedLocation(cleanedName)
            if (cached != null) {
                Log.d(TAG, "Cache hit for '$cleanedName'")
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

        // ── Step 4.5: History-biased Nominatim (Merchant Location Affinity) ──────
        // Query past located expenses for this merchant, find the top cluster,
        // and use it to bias Nominatim with bounded=1.
        val normalizedKey = locationRepository.normalizeKey(cleanedName)
        val clusters = try {
            expenseRepository.getMerchantLocationClusters(cleanedName)
        } catch (e: Exception) {
            Log.w(TAG, "Cluster query failed for '$cleanedName'", e)
            emptyList()
        }
        val topCluster = clusters.firstOrNull { it.count >= 2 }
        if (topCluster != null) {
            val areaKey = locationRepository.getMostLikelyArea(cleanedName, topCluster.centerLat, topCluster.centerLon)
            val cachedForArea = locationRepository.getCachedLocationForArea(cleanedName, areaKey)
            if (cachedForArea != null) {
                Log.d(TAG, "Area-cache hit for '$cleanedName' in area $areaKey")
                return LocationResolutionResult.Resolved(
                    latitude = cachedForArea.latitude,
                    longitude = cachedForArea.longitude,
                    source = cachedForArea.source,
                    osmId = cachedForArea.osmId,
                    displayAddress = cachedForArea.displayAddress,
                    confidence = cachedForArea.confidence
                )
            }
            // Build ~5 km viewbox around cluster centre and call Nominatim bounded
            val delta = 0.045  // ~5 km
            val clusterResult = geocodeWithRateLimit(
                name = latinName,
                biasLat = topCluster.centerLat,
                biasLon = topCluster.centerLon,
                bounded = true
            ) ?: geocodeWithRateLimit(
                name = cleanedName,
                biasLat = topCluster.centerLat,
                biasLon = topCluster.centerLon,
                bounded = true
            )
            if (clusterResult != null) {
                Log.d(TAG, "History-biased Nominatim resolved '$cleanedName' (cluster count=${topCluster.count})")
                val resolved = clusterResult.toResolved()
                locationRepository.saveLocation(cleanedName, resolved, areaKey)
                return resolved
            }
        }

        // ── Step 5: Nominatim with GPS bias (recent transactions only) ─────────
        if (isRecent && deviceLocation != null) {
            val result = geocodeWithRateLimit(
                name = latinName,
                biasLat = deviceLocation.first,
                biasLon = deviceLocation.second
            ) ?: geocodeWithRateLimit(
                name = cleanedName,  // retry with original Greek
                biasLat = deviceLocation.first,
                biasLon = deviceLocation.second
            )
            if (result != null) {
                Log.d(TAG, "Nominatim GPS-bias resolved '$cleanedName'")
                val resolved = result.toResolved()
                locationRepository.saveLocation(cleanedName, resolved)
                return resolved
            }
        }

        // ── Step 6: Nominatim name-only (Greece bias) ─────────────────────────
        val nameOnlyResult = geocodeWithRateLimit(latinName)
            ?: geocodeWithRateLimit(cleanedName)
        if (nameOnlyResult != null) {
            Log.d(TAG, "Nominatim name-only resolved '$cleanedName'")
            val resolved = nameOnlyResult.toResolved()
            locationRepository.saveLocation(cleanedName, resolved)
            return resolved
        }

        // ── Step 7: Overpass nearby POIs (requires device location) ───────────
        if (deviceLocation != null) {
            val pois = nearbyPoiService.findNearby(
                lat = deviceLocation.first,
                lon = deviceLocation.second,
                merchantName = cleanedName,
                radiusMetres = AppConfig.Location.OVERPASS_SEARCH_RADIUS_M
            )
            if (pois.isNotEmpty()) {
                Log.d(TAG, "Overpass found ${pois.size} candidates for '$cleanedName'")
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
                    locationRepository.saveLocation(cleanedName, resolved)
                    resolved
                } else {
                    LocationResolutionResult.NeedsUserSelection(pois)
                }
            }
        }

        // ── Step 8: Give up ───────────────────────────────────────────────────
        Log.d(TAG, "Could not resolve location for '$cleanedName'")
        return LocationResolutionResult.Unresolved
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun geocodeWithRateLimit(
        name: String,
        biasLat: Double? = null,
        biasLon: Double? = null,
        cityHint: String? = null,
        bounded: Boolean = false
    ): GeocodingResult? = rateLimitMutex.withLock {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRequestAt
        if (elapsed < AppConfig.Location.NOMINATIM_MIN_INTERVAL_MS) {
            kotlinx.coroutines.delay(AppConfig.Location.NOMINATIM_MIN_INTERVAL_MS - elapsed)
        }
        lastRequestAt = System.currentTimeMillis()
        geocodingService.search(name, biasLat, biasLon, cityHint, bounded)
    }

    private fun GeocodingResult.toResolved() = LocationResolutionResult.Resolved(
        latitude = latitude,
        longitude = longitude,
        source = source,
        osmId = osmId,
        displayAddress = displayAddress,
        confidence = confidence
    )

    private companion object {
        const val TAG = "LocationResolver"
    }
}
