package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.MerchantLocationDao
import com.yourname.expensetracker.data.database.entity.MerchantLocation
import com.yourname.expensetracker.data.database.entity.MerchantLocationCorrection
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.location.LocationResolutionResult
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Repository for the merchant-location cache ([MerchantLocation]) and
 * user-location corrections ([MerchantLocationCorrection]).
 *
 * All cache-key lookups normalise the merchant name via
 * [com.yourname.expensetracker.domain.util.MerchantKeyGenerator] — the single
 * canonical key generator shared across all layers of the app.
 */
@Singleton
class MerchantLocationRepository @Inject constructor(
    private val dao: MerchantLocationDao,
    private val timeProvider: TimeProvider
) {

    // ── Cache lookups ─────────────────────────────────────────────────────────

    suspend fun getCachedLocation(merchantName: String): MerchantLocation? {
        val key = normalizeKey(merchantName)
        val cached = dao.getGlobalByNormalizedName(key) ?: return null
        // Evict if stale
        if (timeProvider.now() - cached.lastResolvedAt > AppConfig.Location.CACHE_TTL_MS) {
            return null
        }
        dao.incrementHitCount(key)
        return cached
    }

    /**
     * Area-scoped cache lookup (v30). Looks up by normalized name + area key.
     */
    suspend fun getCachedLocationForArea(merchantName: String, areaKey: String): MerchantLocation? {
        val key = normalizeKey(merchantName)
        val cached = dao.getByNormalizedNameAndArea(key, areaKey) ?: return null
        if (timeProvider.now() - cached.lastResolvedAt > AppConfig.Location.CACHE_TTL_MS) {
            return null
        }
        dao.incrementHitCountForArea(key, areaKey)
        return cached
    }

    /**
     * Compute the area key for a (lat, lon) coordinate.
     * Uses a ~5 km grid (same grid as MerchantLocationCorrection).
     */
    fun getMostLikelyArea(merchantName: String, lat: Double?, lon: Double?): String {
        val key = normalizeKey(merchantName)
        return if (lat != null && lon != null) {
            val gridLat = kotlin.math.floor(lat / 0.045).toLong()
            val gridLon = kotlin.math.floor(lon / 0.045).toLong()
            "$key|$gridLat|$gridLon"
        } else {
            "global"
        }
    }

    suspend fun saveLocation(
        merchantName: String,
        result: LocationResolutionResult.Resolved,
        areaKey: String = "global"
    ) {
        val key = normalizeKey(merchantName)
        dao.upsertLocation(
            MerchantLocation(
                normalizedMerchantName = key,
                areaKey = areaKey,
                displayName = merchantName,
                latitude = result.latitude,
                longitude = result.longitude,
                source = result.source,
                osmId = result.osmId,
                displayAddress = result.displayAddress,
                confidence = result.confidence,
                lastResolvedAt = timeProvider.now()
            )
        )
    }

    // ── Correction lookups ────────────────────────────────────────────────────

    /**
     * Returns a user correction for [merchantName] that applies to the given
     * device location ([deviceLat], [deviceLon]).  If no device location is
     * available, returns the most-recent global correction for this merchant.
     */
    suspend fun getCorrection(
        merchantName: String,
        deviceLat: Double?,
        deviceLon: Double?
    ): MerchantLocationCorrection? {
        val key = normalizeKey(merchantName)
        if (deviceLat == null || deviceLon == null) {
            return dao.getLatestGlobalCorrection(key)
        }
        val candidates = dao.getCorrectionCandidates(key, deviceLat, deviceLon)
        return candidates.firstOrNull { correction ->
            // Null area → global correction, always applies
            if (correction.areaLatitude == null || correction.areaLongitude == null) return@firstOrNull true
            val distKm = haversineKm(
                deviceLat, deviceLon,
                correction.areaLatitude, correction.areaLongitude
            )
            distKm <= correction.areaRadiusKm
        }
    }

    suspend fun saveCorrection(correction: MerchantLocationCorrection) {
        dao.upsertCorrection(correction)
        // Also update the main cache to reflect the user's fix immediately.
        // Bug #12 fix: correction.normalizedMerchantName is already the normalized
        // key — do NOT call normalizeKey() on it again (that would double-normalize).
        // Use the osmId's displayAddress as displayName if available; the normalized
        // key is not human-readable so we fall back to it only if nothing better exists.
        dao.upsertLocation(
            MerchantLocation(
                normalizedMerchantName = correction.normalizedMerchantName,
                areaKey = correction.areaKey,
                displayName = correction.displayAddress ?: correction.normalizedMerchantName,
                latitude = correction.correctedLatitude,
                longitude = correction.correctedLongitude,
                source = AppConfig.Location.SOURCE_USER_MANUAL,
                osmId = correction.osmId,
                displayAddress = correction.displayAddress,
                confidence = 1.0f,
                lastResolvedAt = timeProvider.now()
            )
        )
    }

    // ── Maintenance ───────────────────────────────────────────────────────────

    suspend fun evictStaleCache() {
        val cutoff = timeProvider.now() - AppConfig.Location.CACHE_TTL_MS
        dao.deleteStaleEntries(cutoff)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun normalizeKey(merchantName: String): String = MerchantKeyGenerator.generate(merchantName)

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * asin(sqrt(a))
    }
}
