package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.MerchantLocation
import com.yourname.expensetracker.data.database.entity.MerchantLocationCorrection

@Dao
interface MerchantLocationDao {

    // ── MerchantLocation cache ────────────────────────────────────────────────

    /** Global cache lookup: prefers 'global' areaKey entries, falls back to any entry. */
    @Query("SELECT * FROM merchant_locations WHERE normalizedMerchantName = :key ORDER BY CASE WHEN areaKey = 'global' THEN 0 ELSE 1 END, hitCount DESC LIMIT 1")
    suspend fun getByNormalizedName(key: String): MerchantLocation?

    /** Area-scoped cache lookup (v30): looks up by normalized name AND area key. */
    @Query("SELECT * FROM merchant_locations WHERE normalizedMerchantName = :key AND areaKey = :areaKey LIMIT 1")
    suspend fun getByNormalizedNameAndArea(key: String, areaKey: String): MerchantLocation?

    /**
     * Upsert a location cache entry, preserving hitCount if the row already exists.
     * Uses INSERT OR REPLACE but explicitly carries forward the old hitCount + 1.
     */
    @Transaction
    suspend fun upsertLocation(location: MerchantLocation) {
        val effectiveAreaKey = location.areaKey
        val existing = getByNormalizedNameAndArea(location.normalizedMerchantName, effectiveAreaKey)
        if (existing != null) {
            updateExistingLocation(
                id = existing.id,
                displayName = location.displayName,
                latitude = location.latitude,
                longitude = location.longitude,
                source = location.source,
                osmId = location.osmId,
                displayAddress = location.displayAddress,
                confidence = location.confidence,
                lastResolvedAt = location.lastResolvedAt,
                hitCount = existing.hitCount + 1
            )
        } else {
            insertLocation(location)
        }
    }

    @Insert
    suspend fun insertLocation(location: MerchantLocation)

    @Query("""
        UPDATE merchant_locations 
        SET displayName = :displayName, latitude = :latitude, longitude = :longitude,
            source = :source, osmId = :osmId, displayAddress = :displayAddress,
            confidence = :confidence, lastResolvedAt = :lastResolvedAt, hitCount = :hitCount
        WHERE id = :id
    """)
    suspend fun updateExistingLocation(
        id: Long, displayName: String, latitude: Double, longitude: Double,
        source: String, osmId: String?, displayAddress: String?,
        confidence: Float, lastResolvedAt: Long, hitCount: Int
    )

    /** Increment the hit counter for a global cached entry (areaKey = 'global'). */
    @Query("UPDATE merchant_locations SET hitCount = hitCount + 1, lastResolvedAt = :now WHERE normalizedMerchantName = :key AND areaKey = 'global'")
    suspend fun incrementHitCount(key: String, now: Long = System.currentTimeMillis())

    /** Increment the hit counter for an area-scoped cached entry. */
    @Query("UPDATE merchant_locations SET hitCount = hitCount + 1, lastResolvedAt = :now WHERE normalizedMerchantName = :key AND areaKey = :areaKey")
    suspend fun incrementHitCountForArea(key: String, areaKey: String, now: Long = System.currentTimeMillis())

    /** Remove stale entries older than [cutoffMs]. Called by the backfill worker. */
    @Query("DELETE FROM merchant_locations WHERE lastResolvedAt < :cutoffMs")
    suspend fun deleteStaleEntries(cutoffMs: Long)

    @Query("SELECT COUNT(*) FROM merchant_locations")
    suspend fun count(): Int

    // ── MerchantLocationCorrection ────────────────────────────────────────────

    /**
     * Find a user correction for [merchantKey] where the transaction location
     * ([lat], [lon]) falls within the correction's area radius.
     *
     * SQLite has no native geo math, so we do a bounding-box pre-filter
     * (±[latDelta] / ±[lonDelta]) and return the best match; the caller
     * performs the exact Haversine check in Kotlin.
     */
    @Query("""
        SELECT * FROM merchant_location_corrections
        WHERE normalizedMerchantName = :merchantKey
          AND (
            areaLatitude IS NULL
            OR (
              areaLatitude  BETWEEN :lat - :latDelta AND :lat + :latDelta
              AND areaLongitude BETWEEN :lon - :lonDelta AND :lon + :lonDelta
            )
          )
        ORDER BY createdAt DESC
    """)
    suspend fun getCorrectionCandidates(
        merchantKey: String,
        lat: Double,
        lon: Double,
        latDelta: Double = 0.1,   // ~11 km — generous pre-filter
        lonDelta: Double = 0.15
    ): List<MerchantLocationCorrection>

    /** All corrections regardless of area — used when no device location is available. */
    @Query("SELECT * FROM merchant_location_corrections WHERE normalizedMerchantName = :merchantKey ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestCorrection(merchantKey: String): MerchantLocationCorrection?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCorrection(correction: MerchantLocationCorrection)

    @Delete
    suspend fun deleteCorrection(correction: MerchantLocationCorrection)

    @Query("SELECT COUNT(*) FROM merchant_location_corrections")
    suspend fun correctionCount(): Int
}
