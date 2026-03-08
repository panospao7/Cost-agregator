package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached geocoding result for a merchant name.
 * Keyed by normalizedMerchantName (lowercased, stripped) so the same chain's
 * many raw notification strings all share one cache entry.
 *
 * Added in DB v28.
 */
@Entity(
    tableName = "merchant_locations",
    indices = [
        Index(value = ["normalizedMerchantName", "areaKey"], unique = true),
        Index(value = ["lastResolvedAt"])
    ]
)
data class MerchantLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Canonical normalized name used as the cache key (lowercase, no punctuation). */
    val normalizedMerchantName: String,

    /**
     * Area key scoping this cache entry to a geographic grid cell (v30).
     * Format: "${normalizedName}|${floor(lat/0.045).toLong()}|${floor(lon/0.045).toLong()}" for area-scoped,
     * or "${normalizedName}|global" for global/fallback entries.
     * New code always sets a non-null value; legacy NULL rows are treated as global by queries.
     */
    val areaKey: String? = "global",

    /** Raw merchant name as it appears in the notification/statement (for display). */
    val displayName: String,

    val latitude: Double,
    val longitude: Double,

    /**
     * One of: "NOMINATIM_GPS_BIAS", "NOMINATIM_NAME_ONLY", "OVERPASS_POI", "USER_MANUAL"
     */
    val source: String,

    /** OSM node/way/relation ID — allows re-lookup without a fresh geocode. */
    val osmId: String? = null,

    /** Human-readable place name returned by Nominatim (e.g. "Σκλαβενίτης, Γλυφάδα"). */
    val displayAddress: String? = null,

    /** Confidence score 0.0–1.0 assigned by the resolver. */
    val confidence: Float = 1.0f,

    /** Epoch ms of the last successful resolution. Used for cache-staleness checks. */
    val lastResolvedAt: Long = System.currentTimeMillis(),

    /** How many expense rows share this cache entry (informational). */
    val hitCount: Int = 1
)
