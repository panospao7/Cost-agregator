package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.domain.util.GeoUtils
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ensures GeoUtils.haversineKm produces consistent results and matches the expected
 * haversine formula. Other services (CompositeGeocodingService, OverpassNearbyService,
 * MerchantLocationRepository) have local haversine implementations; they should migrate
 * to GeoUtils. This test documents expected behavior and catches regressions.
 *
 * Formula: d = 2 * R * asin(sqrt(a)) where a = sin²(Δlat/2) + cos(lat1)*cos(lat2)*sin²(Δlon/2)
 * R = 6371 km for GeoUtils.
 */
class HaversineConsistencyTest {

    private companion object {
        const val EARTH_RADIUS_KM = 6371.0
        const val EARTH_RADIUS_M = 6_371_000.0
        const val TOLERANCE_KM = 0.01
        const val TOLERANCE_M = 10.0
    }

    @Test
    fun `consistency - GeoUtils haversineKm matches inline formula`() {
        val pairs = listOf(
            Triple(37.9838, 23.7275, 37.9755 to 23.7348),   // Athens, ~1.2 km
            Triple(52.5200, 13.4050, 52.5244 to 13.4105),  // Berlin, ~0.7 km
            Triple(0.0, 0.0, 0.01 to 0.01),                // Near equator
            Triple(51.5074, -0.1278, 48.8566 to 2.3522),   // London to Paris ~344 km
        )
        for ((lat1, lon1, pair) in pairs) {
            val (lat2, lon2) = pair
            val geoUtilsKm = GeoUtils.haversineKm(lat1, lon1, lat2, lon2)
            val expectedKm = haversineKmInline(lat1, lon1, lat2, lon2)
            assertEquals(
                "GeoUtils must match formula for ($lat1,$lon1) to ($lat2,$lon2)",
                expectedKm,
                geoUtilsKm,
                TOLERANCE_KM
            )
        }
    }

    @Test
    fun `consistency - GeoUtils km to meters conversion matches meter formula`() {
        val lat1 = 37.9838
        val lon1 = 23.7275
        val lat2 = 37.9755
        val lon2 = 23.7348
        val geoUtilsKm = GeoUtils.haversineKm(lat1, lon1, lat2, lon2)
        val geoUtilsMeters = geoUtilsKm * 1000.0
        val expectedMeters = haversineMetersInline(lat1, lon1, lat2, lon2)
        assertEquals(
            "GeoUtils km*1000 must match meter formula (CompositeGeocodingService, OverpassNearbyService)",
            expectedMeters,
            geoUtilsMeters,
            TOLERANCE_M
        )
    }

    @Test
    fun `consistency - zero distance for same point`() {
        val lat = 40.7128
        val lon = -74.0060
        assertEquals(0.0, GeoUtils.haversineKm(lat, lon, lat, lon), 0.0001)
    }

    @Test
    fun `consistency - haversineKmOrNull returns null for null inputs`() {
        assertEquals(null, GeoUtils.haversineKmOrNull(null, 0.0, 0.0, 0.0))
        assertEquals(null, GeoUtils.haversineKmOrNull(0.0, null, 0.0, 0.0))
        assertEquals(null, GeoUtils.haversineKmOrNull(0.0, 0.0, null, 0.0))
        assertEquals(null, GeoUtils.haversineKmOrNull(0.0, 0.0, 0.0, null))
    }

    @Test
    fun `consistency - haversineKmOrNull matches haversineKm for valid inputs`() {
        val lat1 = 37.9838
        val lon1 = 23.7275
        val lat2 = 37.9755
        val lon2 = 23.7348
        val direct = GeoUtils.haversineKm(lat1, lon1, lat2, lon2)
        val orNull = GeoUtils.haversineKmOrNull(lat1, lon1, lat2, lon2)
        assert(orNull != null)
        assertEquals(direct, orNull!!, 0.0001)
    }

    private fun haversineKmInline(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return EARTH_RADIUS_KM * 2 * asin(sqrt(a))
    }

    private fun haversineMetersInline(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return EARTH_RADIUS_M * 2 * asin(sqrt(a))
    }
}
