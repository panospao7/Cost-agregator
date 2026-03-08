package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.BuildConfig
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
 * Geoapify geocoding service — free tier 3000 req/day, augmented OSM data.
 * Requires [BuildConfig.GEOAPIFY_API_KEY]. Returns empty list gracefully if
 * the key is missing/blank.
 *
 * Used as the second tier in the [CompositeGeocodingService] cascade.
 */
@Singleton
class GeoapifyGeocodingService @Inject constructor() : GeocodingService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val apiKey get() = BuildConfig.GEOAPIFY_API_KEY

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
            Log.d(TAG, "Geoapify: API key missing, skipping")
            return emptyList()
        }

        val url = buildUrl(query, biasLat, biasLon, limit)
        Log.d(TAG, "==> Geoapify request: $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AppConfig.Location.NOMINATIM_USER_AGENT)
            .build()

        return try {
            val response = client.newCall(request).execute()
            Log.d(TAG, "    HTTP ${response.code}")
            if (!response.isSuccessful) {
                Log.w(TAG, "    Geoapify HTTP ${response.code}")
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            parseResults(body).also { Log.d(TAG, "    <== ${it.size} results") }
        } catch (e: IOException) {
            Log.e(TAG, "    Geoapify network error: ${e.message}")
            emptyList()
        } catch (e: JSONException) {
            Log.e(TAG, "    Geoapify parse error: ${e.message}")
            emptyList()
        }
    }

    private fun buildUrl(query: String, biasLat: Double?, biasLon: Double?, limit: Int): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val sb = StringBuilder(AppConfig.Location.GEOAPIFY_BASE_URL)
        sb.append("/v1/geocode/search?text=$encoded")
        sb.append("&format=json")
        sb.append("&limit=$limit")
        sb.append("&lang=en")
        // Bias toward Greece but allow worldwide results
        if (biasLat != null && biasLon != null) {
            sb.append("&bias=proximity:$biasLon,$biasLat")
        } else {
            sb.append("&bias=countrycode:gr")
        }
        sb.append("&apiKey=$apiKey")
        return sb.toString()
    }

    private fun parseResults(body: String): List<GeocodingResult> {
        val root = JSONObject(body)
        val resultsArr = root.optJSONArray("results") ?: return emptyList()
        val results = mutableListOf<GeocodingResult>()

        for (i in 0 until resultsArr.length()) {
            val obj = resultsArr.getJSONObject(i)
            val lat = obj.optDouble("lat", Double.NaN)
            val lon = obj.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val formatted = obj.optString("formatted").ifBlank { null }
            val nameVal = obj.optString("name").ifBlank {
                obj.optString("address_line1").ifBlank { null }
            }
            val placeId = obj.optString("place_id").ifBlank { null }
            val confidence = obj.optJSONObject("rank")
                ?.optDouble("confidence", 0.6)?.toFloat()?.coerceIn(0f, 1f) ?: 0.6f

            results.add(
                GeocodingResult(
                    latitude = lat,
                    longitude = lon,
                    osmId = placeId,
                    name = nameVal,
                    displayAddress = formatted,
                    confidence = confidence,
                    source = AppConfig.Location.SOURCE_GEOAPIFY
                )
            )
        }
        return results
    }

    private companion object {
        const val TAG = "LocationSearch"
    }
}
