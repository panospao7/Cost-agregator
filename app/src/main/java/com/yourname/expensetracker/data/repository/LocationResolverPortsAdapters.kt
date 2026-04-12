package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.domain.location.CachedMerchantLocation
import com.yourname.expensetracker.domain.location.LocationCachePort
import com.yourname.expensetracker.domain.location.LocationCorrection
import com.yourname.expensetracker.domain.location.LocationResolutionResult
import com.yourname.expensetracker.domain.location.MerchantClusterPort
import com.yourname.expensetracker.domain.location.MerchantLocationCluster
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MerchantLocationCachePortAdapter @Inject constructor(
    private val merchantLocationRepository: MerchantLocationRepository
) : LocationCachePort {

    override suspend fun getCorrection(
        merchantName: String,
        deviceLat: Double?,
        deviceLon: Double?
    ): LocationCorrection? {
        return merchantLocationRepository.getCorrection(
            merchantName = merchantName,
            deviceLat = deviceLat,
            deviceLon = deviceLon
        )?.toDomain()
    }

    override suspend fun getCachedLocation(merchantName: String): CachedMerchantLocation? {
        return merchantLocationRepository.getCachedLocation(merchantName)?.toDomain()
    }

    override suspend fun getCachedLocationForArea(
        merchantName: String,
        areaKey: String
    ): CachedMerchantLocation? {
        return merchantLocationRepository.getCachedLocationForArea(merchantName, areaKey)?.toDomain()
    }

    override fun getMostLikelyArea(merchantName: String, lat: Double?, lon: Double?): String {
        return merchantLocationRepository.getMostLikelyArea(merchantName, lat, lon)
    }

    override suspend fun saveLocation(
        merchantName: String,
        result: LocationResolutionResult.Resolved,
        areaKey: String
    ) {
        merchantLocationRepository.saveLocation(merchantName, result, areaKey)
    }

    private fun com.yourname.expensetracker.data.database.entity.MerchantLocation.toDomain(): CachedMerchantLocation {
        return CachedMerchantLocation(
            latitude = latitude,
            longitude = longitude,
            source = source,
            osmId = osmId,
            displayAddress = displayAddress,
            confidence = confidence
        )
    }

    private fun com.yourname.expensetracker.data.database.entity.MerchantLocationCorrection.toDomain(): LocationCorrection {
        return LocationCorrection(
            correctedLatitude = correctedLatitude,
            correctedLongitude = correctedLongitude,
            osmId = osmId,
            displayAddress = displayAddress
        )
    }
}

@Singleton
class ExpenseMerchantClusterPortAdapter @Inject constructor(
    private val expenseRepository: ExpenseRepository
) : MerchantClusterPort {
    override suspend fun getMerchantLocationClusters(merchantKey: String): List<MerchantLocationCluster> {
        return expenseRepository.getMerchantLocationClusters(merchantKey)
            .map {
                MerchantLocationCluster(
                    centerLat = it.centerLat,
                    centerLon = it.centerLon,
                    count = it.count
                )
            }
    }
}
