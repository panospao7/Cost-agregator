package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.location.NearbyPoi
import com.yourname.expensetracker.domain.location.NearbyPoiService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Overpass API implementation of [NearbyPoiService].
 *
 * Queries Overpass for all named shops/amenities within [radiusMetres] of the
 * given coordinate, then filters/ranks by name similarity to [merchantName].
 */
@Singleton
class OverpassNearbyService @Inject constructor() : NearbyPoiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun findNearby(
        lat: Double,
        lon: Double,
        merchantName: String,
        radiusMetres: Int
    ): List<NearbyPoi> {
        val query = buildOverpassQuery(lat, lon, radiusMetres)
        val body = query.toRequestBody("text/plain".toMediaType())
        val request = Request.Builder()
            .url(AppConfig.Location.OVERPASS_BASE_URL)
            .header("User-Agent", AppConfig.Location.NOMINATIM_USER_AGENT)
            .post(body)
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Overpass HTTP ${response.code}")
                return emptyList()
            }
            val responseBody = response.body?.string() ?: return emptyList()
            parseAndRank(responseBody, lat, lon, merchantName)
        } catch (e: IOException) {
            Log.w(TAG, "Overpass network error: ${e.message}")
            emptyList()
        } catch (e: JSONException) {
            Log.w(TAG, "Overpass parse error: ${e.message}")
            emptyList()
        }
    }

    private fun buildOverpassQuery(lat: Double, lon: Double, radiusMetres: Int): String {
        // Query nodes and ways with a name tag that are shops or amenities
        return """
            [out:json][timeout:20];
            (
              node["name"]["shop"](around:$radiusMetres,$lat,$lon);
              node["name"]["amenity"](around:$radiusMetres,$lat,$lon);
              way["name"]["shop"](around:$radiusMetres,$lat,$lon);
              way["name"]["amenity"](around:$radiusMetres,$lat,$lon);
            );
            out center;
        """.trimIndent()
    }

    private fun parseAndRank(
        body: String,
        queryLat: Double,
        queryLon: Double,
        merchantName: String
    ): List<NearbyPoi> {
        val root = JSONObject(body)
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val pois = mutableListOf<NearbyPoi>()

        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val tags = el.optJSONObject("tags") ?: continue
            val nameRaw = tags.optString("name")
            if (nameRaw.isBlank()) continue
            val name = nameRaw

            // Get coordinates — nodes have direct lat/lon, ways have a "center" object
            val elLat: Double
            val elLon: Double
            if (el.has("center")) {
                val center = el.getJSONObject("center")
                elLat = center.optDouble("lat", Double.NaN)
                elLon = center.optDouble("lon", Double.NaN)
            } else {
                elLat = el.optDouble("lat", Double.NaN)
                elLon = el.optDouble("lon", Double.NaN)
            }
            if (elLat.isNaN() || elLon.isNaN()) continue

            val osmType = el.optString("type", "N")
            val osmId = "${osmType.uppercase().firstOrNull() ?: 'N'}${el.optLong("id", 0)}"
            val category = tags.optString("shop").ifBlank { tags.optString("amenity").ifBlank { null } }
            val distanceM = haversineMetres(queryLat, queryLon, elLat, elLon)

            pois.add(
                NearbyPoi(
                    osmId = osmId,
                    name = name,
                    latitude = elLat,
                    longitude = elLon,
                    distanceMetres = distanceM,
                    category = category,
                    displayAddress = buildAddress(tags)
                )
            )
        }

        // Sort by name-similarity score (desc) then distance (asc)
        val normalizedQuery = merchantName.lowercase().replace(Regex("[^a-z0-9]"), "")
        return pois.sortedWith(
            compareByDescending<NearbyPoi> { nameSimilarity(it.name, normalizedQuery) }
                .thenBy { it.distanceMetres }
        )
    }

    private fun buildAddress(tags: JSONObject): String? {
        val parts = listOfNotNull(
            tags.optString("addr:street").ifBlank { null },
            tags.optString("addr:housenumber").ifBlank { null },
            tags.optString("addr:city").ifBlank { null }
        )
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    /**
     * Simple Jaccard-like token overlap similarity in [0, 1].
     */
    private fun nameSimilarity(candidateName: String, normalizedQuery: String): Double {
        val candidate = candidateName.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (candidate.isEmpty() || normalizedQuery.isEmpty()) return 0.0
        if (candidate.contains(normalizedQuery) || normalizedQuery.contains(candidate)) return 0.9
        val shorter = minOf(candidate.length, normalizedQuery.length)
        val longer = maxOf(candidate.length, normalizedQuery.length)
        var matches = 0
        for (i in 0 until minOf(shorter, 4)) {
            if (candidate.getOrNull(i) == normalizedQuery.getOrNull(i)) matches++
        }
        return (matches.toDouble() / 4).coerceIn(0.0, 1.0) * (shorter.toDouble() / longer)
    }

    /** Haversine distance in metres between two lat/lon points. */
    private fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0  // Earth radius in metres
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * asin(sqrt(a))
    }

    private companion object {
        const val TAG = "OverpassNearbyService"
    }
}
