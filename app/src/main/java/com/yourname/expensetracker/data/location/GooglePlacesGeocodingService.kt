package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGooglePlacesKey
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.location.GeocodingResult
import com.yourname.expensetracker.domain.location.GeocodingService
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

/**
 * Google Places (New) Text Search geocoding service.
 * Free tier: $200/month credit → ~6,250 requests/month at $32/1000.
 * Requires SecureKeyStorage.getGooglePlacesKey(). Returns empty list gracefully
 * if the key is missing/blank.
 *
 * Note: Cached results respect the existing 30-day [AppConfig.Location.CACHE_TTL_MS],
 * which coincidentally aligns with Google ToS refresh requirements.
 *
 * Used as the third tier in the [CompositeGeocodingService] cascade.
 */
// CRITICAL FIX (CRITICAL-1): Now uses SecureKeyStorage instead of BuildConfig
@Singleton
class GooglePlacesGeocodingService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage
) : GeocodingService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val apiKey get() = secureKeyStorage.getGooglePlacesKey() ?: ""

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
        if (apiKey.isBlank()) {
            Log.d(TAG, "Google Places: API key missing, skipping")
            return emptyList()
        }

        val requestBody = buildRequestBody(query, biasLat, biasLon, limit)
        val url = "${AppConfig.Location.GOOGLE_PLACES_BASE_URL}/v1/places:searchText"
        Log.d(TAG, "==> Google Places request for: $query")

        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask",
                "places.displayName,places.formattedAddress,places.location,places.id")
            .header("User-Agent", AppConfig.Location.NOMINATIM_USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "    HTTP ${response.code}")
                if (!response.isSuccessful) {
                    Log.w(TAG, "    Google Places HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                    return@use emptyList()
                }
                val body = response.body?.string() ?: return@use emptyList()
                parseResults(body).also { Log.d(TAG, "    <== ${it.size} results") }
            }
        } catch (e: IOException) {
            Log.e(TAG, "    Google Places network error: ${e.message}")
            emptyList()
        } catch (e: JSONException) {
            Log.e(TAG, "    Google Places parse error: ${e.message}")
            emptyList()
        }
    }

    private fun buildRequestBody(
        query: String,
        biasLat: Double?,
        biasLon: Double?,
        limit: Int
    ): String {
        val obj = JSONObject()
        obj.put("textQuery", query)
        obj.put("maxResultCount", limit.coerceIn(1, 20))

        // Add location bias if coordinates are available
        if (biasLat != null && biasLon != null) {
            val center = JSONObject()
            center.put("latitude", biasLat)
            center.put("longitude", biasLon)

            val circle = JSONObject()
            circle.put("center", center)
            circle.put("radius", 50000.0) // 50 km radius

            val locationBias = JSONObject()
            locationBias.put("circle", circle)

            obj.put("locationBias", locationBias)
        } else {
            // Default bias: Greece bounding box
            val low = JSONObject().apply { put("latitude", 34.8); put("longitude", 19.3) }
            val high = JSONObject().apply { put("latitude", 42.0); put("longitude", 29.6) }
            val rect = JSONObject().apply {
                put("low", low)
                put("high", high)
            }
            val locationBias = JSONObject()
            locationBias.put("rectangle", rect)
            obj.put("locationBias", locationBias)
        }

        return obj.toString()
    }

    private fun parseResults(body: String): List<GeocodingResult> {
        val root = JSONObject(body)
        val placesArr = root.optJSONArray("places") ?: return emptyList()
        val results = mutableListOf<GeocodingResult>()

        for (i in 0 until placesArr.length()) {
            val place = placesArr.getJSONObject(i)
            val location = place.optJSONObject("location") ?: continue
            val lat = location.optDouble("latitude", Double.NaN)
            val lon = location.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val name = place.optJSONObject("displayName")?.optString("text").ifNullOrBlank { null }
            val address = place.optString("formattedAddress").ifBlank { null }
            val placeId = place.optString("id").ifBlank { null }

            // Combine name + address for display, avoiding duplication
            val displayAddress = when {
                name != null && address != null && !address.startsWith(name) -> "$name, $address"
                address != null -> address
                name != null -> name
                else -> null
            }

            results.add(
                GeocodingResult(
                    latitude = lat,
                    longitude = lon,
                    osmId = placeId, // Google place_id stored in osmId field
                    name = name,
                    displayAddress = displayAddress,
                    confidence = 0.85f, // Google Places generally high quality
                    source = AppConfig.Location.SOURCE_GOOGLE_PLACES
                )
            )
        }
        return results
    }

    private fun String?.ifNullOrBlank(default: () -> String?): String? =
        if (this.isNullOrBlank()) default() else this

    private companion object {
        const val TAG = "LocationSearch"
    }
}
