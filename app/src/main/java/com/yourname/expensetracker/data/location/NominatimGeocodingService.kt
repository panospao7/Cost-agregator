package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.location.GeocodingResult
import com.yourname.expensetracker.domain.location.GeocodingService
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nominatim (OpenStreetMap) implementation of [GeocodingService].
 *
 * Rate-limit: enforced by the caller ([LocationResolver]) which uses a
 * token-bucket / delay strategy.  This class does not sleep itself so it
 * can be tested synchronously.
 *
 * Nominatim usage policy: must send a unique User-Agent header.
 * See [AppConfig.Location.NOMINATIM_USER_AGENT].
 */
@Singleton
class NominatimGeocodingService @Inject constructor() : GeocodingService {

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
    ): GeocodingResult? {
        val url = buildUrl(merchantName, biasLat, biasLon, cityHint, bounded)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AppConfig.Location.NOMINATIM_USER_AGENT)
            .header("Accept-Language", "el,en")
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Nominatim HTTP ${response.code} for query: $merchantName")
                return null
            }
            val body = response.body?.string() ?: return null
            parseResponse(body, biasLat != null)
        } catch (e: IOException) {
            Log.w(TAG, "Nominatim network error: ${e.message}")
            null
        } catch (e: JSONException) {
            Log.w(TAG, "Nominatim parse error: ${e.message}")
            null
        }
    }

    private fun buildUrl(
        merchantName: String,
        biasLat: Double?,
        biasLon: Double?,
        cityHint: String? = null,
        bounded: Boolean = false
    ): String {
        // Append city hint to query for better disambiguation
        val queryStr = if (!cityHint.isNullOrBlank()) "$merchantName $cityHint" else merchantName
        val encoded = java.net.URLEncoder.encode(queryStr, "UTF-8")
        val sb = StringBuilder(AppConfig.Location.NOMINATIM_BASE_URL)
        sb.append("/search?q=$encoded")
        sb.append("&format=json")
        sb.append("&limit=${AppConfig.Location.NOMINATIM_MAX_RESULTS}")
        sb.append("&addressdetails=1")
        sb.append("&countrycodes=${AppConfig.Location.GREECE_COUNTRY_CODE}")

        if (biasLat != null && biasLon != null) {
            val delta = 0.09  // ~10 km at Greek latitudes
            val minLon = (biasLon - delta).coerceAtLeast(-180.0)
            val maxLon = (biasLon + delta).coerceAtMost(180.0)
            val minLat = (biasLat - delta).coerceAtLeast(-90.0)
            val maxLat = (biasLat + delta).coerceAtMost(90.0)
            sb.append("&viewbox=$minLon,$maxLat,$maxLon,$minLat")
            sb.append(if (bounded) "&bounded=1" else "&bounded=0")
        } else {
            sb.append("&viewbox=${AppConfig.Location.GREECE_VIEWBOX}")
            sb.append(if (bounded) "&bounded=1" else "&bounded=0")
        }

        return sb.toString()
    }

    private fun parseResponse(body: String, hadGpsBias: Boolean): GeocodingResult? {
        val array = JSONArray(body)
        if (array.length() == 0) return null

        val first = array.getJSONObject(0)
        val lat = first.getString("lat").toDoubleOrNull() ?: return null
        val lon = first.getString("lon").toDoubleOrNull() ?: return null
        val osmType = first.optString("osm_type", "")
        val osmId = if (osmType.isNotEmpty()) {
            "${osmType.uppercase().firstOrNull() ?: 'N'}${first.optLong("osm_id", 0)}"
        } else null
        val displayAddress = first.optString("display_name")
        val importance = first.optDouble("importance", 0.5).toFloat().coerceIn(0f, 1f)

        // Parse city/town from addressdetails=1 response (for future use / logging)
        val addressObj = first.optJSONObject("address")
        val cityFromAddress = addressObj?.let {
            it.optString("city").ifBlank { null }
                ?: it.optString("town").ifBlank { null }
                ?: it.optString("suburb").ifBlank { null }
                ?: it.optString("municipality").ifBlank { null }
        }
        if (cityFromAddress != null) {
            Log.v(TAG, "Resolved city: $cityFromAddress")
        }

        val source = if (hadGpsBias) {
            AppConfig.Location.SOURCE_NOMINATIM_GPS_BIAS
        } else {
            AppConfig.Location.SOURCE_NOMINATIM_NAME_ONLY
        }

        return GeocodingResult(
            latitude = lat,
            longitude = lon,
            osmId = osmId,
            displayAddress = displayAddress.ifBlank { null },
            confidence = importance,
            source = source
        )
    }

    private companion object {
        const val TAG = "NominatimGeocodingService"
    }
}
