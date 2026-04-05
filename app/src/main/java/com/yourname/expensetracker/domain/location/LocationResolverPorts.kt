package com.yourname.expensetracker.domain.location

/**
 * Domain port for merchant location cache/correction access used by [LocationResolver].
 */
interface LocationCachePort {
    suspend fun getCorrection(
        merchantName: String,
        deviceLat: Double?,
        deviceLon: Double?
    ): LocationCorrection?

    suspend fun getCachedLocation(merchantName: String): CachedMerchantLocation?

    suspend fun getCachedLocationForArea(
        merchantName: String,
        areaKey: String
    ): CachedMerchantLocation?

    fun getMostLikelyArea(
        merchantName: String,
        lat: Double?,
        lon: Double?
    ): String

    suspend fun saveLocation(
        merchantName: String,
        result: LocationResolutionResult.Resolved,
        areaKey: String? = "global"
    )
}

/**
 * Domain port for historical merchant location clustering.
 */
interface MerchantClusterPort {
    suspend fun getMerchantLocationClusters(merchantKey: String): List<MerchantLocationCluster>
}

data class CachedMerchantLocation(
    val latitude: Double,
    val longitude: Double,
    val source: String,
    val osmId: String?,
    val displayAddress: String?,
    val confidence: Float
)

data class LocationCorrection(
    val correctedLatitude: Double,
    val correctedLongitude: Double,
    val osmId: String?,
    val displayAddress: String?
)

data class MerchantLocationCluster(
    val centerLat: Double,
    val centerLon: Double,
    val count: Int
)
