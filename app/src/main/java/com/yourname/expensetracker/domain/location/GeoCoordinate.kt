package com.yourname.expensetracker.domain.location

/**
 * W28: Validated geographic coordinate.
 * Rejects NaN, Infinity, out-of-range values, and null-island (0,0).
 *
 * Use [GeoCoordinate.create] for validated construction.
 */
data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double
) {
    init {
        require(!latitude.isNaN() && !latitude.isInfinite()) { "Latitude must be finite" }
        require(!longitude.isNaN() && !longitude.isInfinite()) { "Longitude must be finite" }
        require(latitude in -90.0..90.0) { "Latitude out of range [-90, 90]: $latitude" }
        require(longitude in -180.0..180.0) { "Longitude out of range [-180, 180]: $longitude" }
    }

    companion object {
        /**
         * Creates a validated [GeoCoordinate].
         * @param allowNullIsland If false (default), rejects lat=0, lon=0.
         * @return null if validation fails, valid coordinate otherwise.
         */
        fun create(
            lat: Double?,
            lon: Double?,
            allowNullIsland: Boolean = false
        ): GeoCoordinate? {
            if (lat == null || lon == null) return null
            if (lat.isNaN() || lat.isInfinite() || lon.isNaN() || lon.isInfinite()) return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            if (!allowNullIsland && lat == 0.0 && lon == 0.0) return null
            return GeoCoordinate(lat, lon)
        }

        /** Sentinel for "no coordinate". */
        val NONE: GeoCoordinate? = null
    }

    val isValid: Boolean get() = true
    fun distanceKmTo(other: GeoCoordinate): Double {
        // Haversine formula
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return 6371.0 * c
    }
}
