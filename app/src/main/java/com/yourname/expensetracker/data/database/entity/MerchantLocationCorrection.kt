package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * User-supplied location correction for a merchant in a geographic area.
 *
 * When a user corrects a pin on the map (or picks a different shop from the
 * Overpass suggestion list), we record the correction here.  Future expenses
 * with the same normalizedMerchantName whose transaction occurred within
 * [areaRadiusKm] of [areaLatitude]/[areaLongitude] will use this override
 * instead of going through the full resolution chain.
 *
 * Added in DB v28.
 */
@Entity(
    tableName = "merchant_location_corrections",
    indices = [
        // Composite unique index so INSERT OR REPLACE actually replaces the existing
        // row for the same merchant+area instead of inserting a new one (bug #13 fix).
        Index(value = ["normalizedMerchantName", "areaKey"], unique = true),
        Index(value = ["createdAt"])
    ]
)
data class MerchantLocationCorrection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Normalized merchant name (same key as MerchantLocation). */
    val normalizedMerchantName: String,

    /** Corrected coordinates chosen by the user. */
    val correctedLatitude: Double,
    val correctedLongitude: Double,

    /**
     * Centre of the geographic area this correction applies to.
     * Typically the centroid of the city/district where the transaction happened.
     * Null means "apply globally to this merchant" (rare — city-named chains).
     */
    val areaLatitude: Double? = null,
    val areaLongitude: Double? = null,

    /**
     * Stable key used as the unique conflict target.
     * Format: "<normalizedMerchant>|<latBucket>|<lonBucket>" where buckets
     * snap to ~5 km grid cells.  For global corrections (areaLatitude == null)
     * the value is "<normalizedMerchant>|global".
     */
    val areaKey: String = buildAreaKey(normalizedMerchantName, areaLatitude, areaLongitude),

    /**
     * Radius in kilometres around (areaLatitude, areaLongitude) within which
     * this correction is considered valid.  Default 5 km covers most city areas.
     */
    @ColumnInfo(defaultValue = "5.0") val areaRadiusKm: Float = 5.0f,

    /** Optional OSM ID of the corrected node (if user picked from Overpass results). */
    val osmId: String? = null,

    /** Human-readable place name for the corrected location. */
    val displayAddress: String? = null,

    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Snap to ~5 km grid cells (0.045° ≈ 5 km at mid-latitude).
         * This means corrections within the same 5 km cell are treated as the
         * same "area" and will replace each other instead of accumulating.
         */
        private const val AREA_SNAP_DEG = 0.045

        fun buildAreaKey(
            normalizedMerchant: String,
            areaLat: Double?,
            areaLon: Double?
        ): String {
            if (areaLat == null || areaLon == null) return "$normalizedMerchant|global"
            val latBucket = kotlin.math.floor(areaLat / AREA_SNAP_DEG).toLong()
            val lonBucket = kotlin.math.floor(areaLon / AREA_SNAP_DEG).toLong()
            return "$normalizedMerchant|$latBucket|$lonBucket"
        }
    }
}
