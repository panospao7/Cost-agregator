package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.location.GeocodingResult
import com.yourname.expensetracker.domain.location.GeocodingService
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photon (photon.komoot.io) geocoding service — free, no API key required.
 * Uses OSM data with excellent fuzzy matching. Returns GeoJSON FeatureCollection.
 *
 * Used as the first tier in the [CompositeGeocodingService] cascade.
 */
@Singleton
class PhotonGeocodingService @Inject constructor() : GeocodingService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun search(
        merchantName: String,
        biasLat: Double?,
        biasLon: Double?,
        cityHint: String?,
        bounded: Boolean
    ): GeocodingResult? = searchMultiple(merchantName, biasLat, biasLon, limit = 1).firstOrNull()

    override suspend fun searchMultiple(
        query: String,
        biasLat: Double?,
        biasLon: Double?,
        limit: Int,
        useGoogle: Boolean
    ): List<GeocodingResult> {
        val url = buildUrl(query, biasLat, biasLon, limit)
        Log.d(TAG, "==> Photon request: $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AppConfig.Location.NOMINATIM_USER_AGENT)
            .build()

        return try {
            // B16 fix: use response.use {} to ensure body is closed even on exceptions
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "    HTTP ${response.code}")
                if (!response.isSuccessful) {
                    Log.w(TAG, "    Photon HTTP ${response.code}")
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                parseResults(body).also { Log.d(TAG, "    <== ${it.size} results") }
            }
        } catch (e: IOException) {
            Log.e(TAG, "    Photon network error: ${e.message}")
            emptyList()
        } catch (e: JSONException) {
            Log.e(TAG, "    Photon parse error: ${e.message}")
            emptyList()
        }
    }

    private fun buildUrl(query: String, biasLat: Double?, biasLon: Double?, limit: Int): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val sb = StringBuilder(AppConfig.Location.PHOTON_BASE_URL)
        sb.append("/api/?q=$encoded")
        sb.append("&limit=$limit")
        sb.append("&lang=en")
        if (biasLat != null && biasLon != null) {
            sb.append("&lat=$biasLat&lon=$biasLon")
        }
        return sb.toString()
    }

    private fun parseResults(body: String): List<GeocodingResult> {
        val root = JSONObject(body)
        val features = root.optJSONArray("features") ?: return emptyList()
        val results = mutableListOf<GeocodingResult>()

        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val geometry = feature.optJSONObject("geometry") ?: continue
            val coords = geometry.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue

            // GeoJSON coordinates are [lon, lat]
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lon.isNaN() || lat.isNaN()) continue

            val props = feature.optJSONObject("properties") ?: JSONObject()
            val name = props.optString("name").ifBlank { null }
            val street = props.optString("street").ifBlank { null }
            val city = props.optString("city").ifBlank {
                props.optString("town").ifBlank {
                    props.optString("village").ifBlank { null }
                }
            }
            val country = props.optString("country").ifBlank { null }
            val postcode = props.optString("postcode").ifBlank { null }

            // Build human-readable display address
            val addressParts = listOfNotNull(name, street, postcode, city, country)
            val displayAddress = if (addressParts.isNotEmpty()) addressParts.joinToString(", ") else null

            // Build OSM ID from osm_type + osm_id
            val osmType = props.optString("osm_type").ifBlank { null }
            val osmIdNum = props.optLong("osm_id", 0L)
            val osmId = if (osmType != null && osmIdNum != 0L) {
                "${osmType.uppercase().first()}$osmIdNum"
            } else null

            results.add(
                GeocodingResult(
                    latitude = lat,
                    longitude = lon,
                    osmId = osmId,
                    name = name,
                    displayAddress = displayAddress,
                    confidence = 0.7f, // Photon doesn't provide relevance scores
                    source = AppConfig.Location.SOURCE_PHOTON
                )
            )
        }
        return results
    }

    private companion object {
        const val TAG = "LocationSearch"
    }
}
