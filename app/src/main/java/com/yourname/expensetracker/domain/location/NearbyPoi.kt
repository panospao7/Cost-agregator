package com.yourname.expensetracker.domain.location

/**
 * A nearby point-of-interest returned by the Overpass API (or similar).
 */
data class NearbyPoi(
    val osmId: String,
    /** Shop/place name (e.g. "Σκλαβενίτης"). */
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** Distance from the query point in metres. */
    val distanceMetres: Double,
    /** OSM amenity/shop tag (e.g. "supermarket", "bakery"). */
    val category: String?,
    /** Full address string if available. */
    val displayAddress: String?
)
