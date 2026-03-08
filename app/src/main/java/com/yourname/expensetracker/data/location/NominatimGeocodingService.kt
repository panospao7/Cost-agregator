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
        return executeRequest(url, merchantName, biasLat != null).firstOrNull()
    }

    override suspend fun searchMultiple(
        query: String,
        biasLat: Double?,
        biasLon: Double?,
        limit: Int,
        useGoogle: Boolean
    ): List<GeocodingResult> {
        Log.d(TAG, "searchMultiple() OVERRIDE called — query=\"$query\", biasLat=$biasLat, biasLon=$biasLon, limit=$limit")
        // No country filter for the interactive picker — the user may search for
        // any merchant by name and should not be restricted to Greece-only results.
        // The Greece viewbox is still sent as a soft bias so local results rank first.
        val url = buildUrl(query, biasLat, biasLon, cityHint = null, bounded = false, useCountryFilter = false)
        return executeRequest(url, query, biasLat != null)
    }

    override suspend fun reverseGeocode(lat: Double, lon: Double): GeocodingResult? {
        val encoded = "%.7f".format(lat)
        val encodedLon = "%.7f".format(lon)
        val url = "${AppConfig.Location.NOMINATIM_BASE_URL}/reverse" +
                "?lat=$encoded&lon=$encodedLon&format=json&addressdetails=1"
        Log.d(TAG, "reverseGeocode() lat=$lat lon=$lon URL=$url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AppConfig.Location.NOMINATIM_USER_AGENT)
            .header("Accept-Language", "el,en")
            .build()
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "reverseGeocode HTTP ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val obj = org.json.JSONObject(body)
            if (obj.has("error")) {
                Log.w(TAG, "reverseGeocode error: ${obj.optString("error")}")
                return null
            }
            val resultLat = obj.optString("lat").toDoubleOrNull() ?: lat
            val resultLon = obj.optString("lon").toDoubleOrNull() ?: lon
            val displayAddress = obj.optString("display_name").ifBlank { null }
            val nameVal = obj.optString("name").ifBlank {
                displayAddress?.substringBefore(",")?.trim()
            }
            val osmType = obj.optString("osm_type", "")
            val osmId = if (osmType.isNotEmpty()) {
                "${osmType.uppercase().firstOrNull() ?: 'N'}${obj.optLong("osm_id", 0)}"
            } else null
            GeocodingResult(
                latitude = resultLat,
                longitude = resultLon,
                osmId = osmId,
                name = nameVal,
                displayAddress = displayAddress,
                confidence = 1.0f,
                source = AppConfig.Location.SOURCE_NOMINATIM_GPS_BIAS
            )
        } catch (e: Exception) {
            Log.e(TAG, "reverseGeocode error: ${e.message}", e)
            null
        }
    }

    private fun executeRequest(
        url: String,
        queryForLog: String,
        hadGpsBias: Boolean
    ): List<GeocodingResult> {
        Log.d(TAG, "==> Nominatim request START")
        Log.d(TAG, "    Query: $queryForLog")
        Log.d(TAG, "    URL: $url")
        
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AppConfig.Location.NOMINATIM_USER_AGENT)
            .header("Accept-Language", "el,en")
            .build()

        return try {
            val response = client.newCall(request).execute()
            Log.d(TAG, "    HTTP response code: ${response.code}")
            
            if (!response.isSuccessful) {
                Log.w(TAG, "    <== Nominatim HTTP ${response.code} for query: $queryForLog")
                if (response.code == 403) {
                    throw IOException("NOMINATIM_403")
                }
                return emptyList()
            }
            val body = response.body?.string() ?: run {
                Log.w(TAG, "    <== Empty response body")
                return emptyList()
            }
            Log.d(TAG, "    Response body length: ${body.length} chars")
            Log.d(TAG, "    Body preview: ${body.take(500)}")
            
            val results = parseAllResults(body, hadGpsBias)
            Log.d(TAG, "    <== Nominatim request END - ${results.size} results")
            results
        } catch (e: IOException) {
            Log.e(TAG, "    <== Nominatim network error: ${e.message}", e)
            emptyList()
        } catch (e: JSONException) {
            Log.e(TAG, "    <== Nominatim parse error: ${e.message}", e)
            emptyList()
        }
    }

    private fun buildUrl(
        merchantName: String,
        biasLat: Double?,
        biasLon: Double?,
        cityHint: String? = null,
        bounded: Boolean = false,
        useCountryFilter: Boolean = true
    ): String {
        // Append city hint to query for better disambiguation
        val queryStr = if (!cityHint.isNullOrBlank()) "$merchantName $cityHint" else merchantName
        val encoded = java.net.URLEncoder.encode(queryStr, "UTF-8")
        val sb = StringBuilder(AppConfig.Location.NOMINATIM_BASE_URL)
        sb.append("/search?q=$encoded")
        sb.append("&format=json")
        sb.append("&limit=${AppConfig.Location.NOMINATIM_MAX_RESULTS}")
        sb.append("&addressdetails=1")
        // Country filter: only applied for background resolution, not the interactive picker
        if (useCountryFilter) {
            sb.append("&countrycodes=${AppConfig.Location.GREECE_COUNTRY_CODE}")
        }

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

    private fun parseAllResults(body: String, hadGpsBias: Boolean): List<GeocodingResult> {
        val array = JSONArray(body)
        if (array.length() == 0) {
            Log.d(TAG, "    parseAllResults: JSON array is EMPTY — Nominatim returned 0 results for this query")
            return emptyList()
        }

        val source = if (hadGpsBias) {
            AppConfig.Location.SOURCE_NOMINATIM_GPS_BIAS
        } else {
            AppConfig.Location.SOURCE_NOMINATIM_NAME_ONLY
        }

        val results = mutableListOf<GeocodingResult>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val lat = obj.getString("lat").toDoubleOrNull() ?: continue
            val lon = obj.getString("lon").toDoubleOrNull() ?: continue
            val osmType = obj.optString("osm_type", "")
            val osmId = if (osmType.isNotEmpty()) {
                "${osmType.uppercase().firstOrNull() ?: 'N'}${obj.optLong("osm_id", 0)}"
            } else null
            val displayAddress = obj.optString("display_name")
            val importance = obj.optDouble("importance", 0.5).toFloat().coerceIn(0f, 1f)

            // Nominatim returns a top-level "name" field for named POIs (shops, etc.)
            // Falls back to extracting the first segment of display_name
            val nameVal = obj.optString("name").ifBlank {
                displayAddress.substringBefore(",").trim().ifBlank { null }
            }

            // Parse city/town from addressdetails=1 response (for logging)
            val addressObj = obj.optJSONObject("address")
            val cityFromAddress = addressObj?.let {
                it.optString("city").ifBlank { null }
                    ?: it.optString("town").ifBlank { null }
                    ?: it.optString("suburb").ifBlank { null }
                    ?: it.optString("municipality").ifBlank { null }
            }
            if (i == 0 && cityFromAddress != null) {
                Log.v(TAG, "Resolved city: $cityFromAddress")
            }

            results.add(
                GeocodingResult(
                    latitude = lat,
                    longitude = lon,
                    osmId = osmId,
                    name = nameVal,
                    displayAddress = displayAddress.ifBlank { null },
                    confidence = importance,
                    source = source
                )
            )
        }
        return results
    }

    private companion object {
        // Using "LocationSearch" so logs appear in the same Logcat filter as the picker UI
        const val TAG = "LocationSearch"
    }
}
