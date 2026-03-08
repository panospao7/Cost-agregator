package com.yourname.expensetracker.domain.util

import kotlin.math.*

/**
 * Shared geographic utilities.
 *
 * Previously, a private [haversineKm] copy existed in each of:
 *  - MerchantLocationRepository
 *  - CompositeGeocodingService
 *  - OverpassNearbyService
 *
 * All callers should migrate to this shared implementation.
 */
object GeoUtils {

    /**
     * Returns the great-circle distance in **kilometres** between two WGS-84 coordinates.
     *
     * Uses the Haversine formula, which gives sub-0.5% error for the distances
     * involved in consumer spending (< 200 km).
     */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * asin(sqrt(a))
    }

    /**
     * Convenience overload that accepts nullable coordinates.
     * Returns null when either coordinate pair is null.
     */
    fun haversineKmOrNull(
        lat1: Double?,
        lon1: Double?,
        lat2: Double?,
        lon2: Double?
    ): Double? {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return null
        return haversineKm(lat1, lon1, lat2, lon2)
    }
}
