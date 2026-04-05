package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.data.location.internal.anonymizeForLog
import com.yourname.expensetracker.di.LocationHttpClient
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.location.GeocodingBatchResult
import com.yourname.expensetracker.domain.location.GeocodingError
import com.yourname.expensetracker.domain.location.GeocodingLookupResult
import com.yourname.expensetracker.domain.location.GeocodingResult
import com.yourname.expensetracker.domain.location.GeocodingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nominatim (OpenStreetMap) implementation of [GeocodingService].
 *
 * Rate-limit: B13 fix — rate limiting is now enforced at the service level
 * via [rateLimitMutex], ensuring compliance with Nominatim's 1 req/sec
 * usage policy regardless of which caller invokes the service (LocationResolver
 * for background resolution, CompositeGeocodingService for interactive search).
 *
 * Nominatim usage policy: must send a unique User-Agent header.
 * See [AppConfig.Location.NOMINATIM_USER_AGENT].
 */
@Singleton
class NominatimGeocodingService @Inject constructor(
    @LocationHttpClient private val client: OkHttpClient
) : GeocodingService {

    // B13 fix: rate-limit all Nominatim requests at the service level.
    // Ensures max 1 request per 1.1s regardless of caller.
    private val rateLimitMutex = Mutex()
    private var lastRequestAt = 0L

    /**
     * Enforce Nominatim's 1 req/sec policy before executing a request.
     * Wraps [block] with a mutex + delay to guarantee minimum spacing.
     */
    private suspend fun <T> withRateLimit(block: suspend () -> T): T = rateLimitMutex.withLock {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRequestAt
        if (elapsed < AppConfig.Location.NOMINATIM_MIN_INTERVAL_MS) {
            delay(AppConfig.Location.NOMINATIM_MIN_INTERVAL_MS - elapsed)
        }
        lastRequestAt = System.currentTimeMillis()
        block()
    }

    override suspend fun search(
        merchantName: String,
        biasLat: Double?,
        biasLon: Double?,
        cityHint: String?,
        bounded: Boolean
    ): GeocodingLookupResult = withRateLimit {
        val url = buildUrl(merchantName, biasLat, biasLon, cityHint, bounded)
        val safeLogRoute = buildSafeLogRoute(
            query = merchantName,
            biasLat = biasLat,
            biasLon = biasLon,
            cityHint = cityHint,
            bounded = bounded,
            useCountryFilter = true
        )
        when (val result = executeRequest(url, merchantName, biasLat != null, safeLogRoute)) {
            is GeocodingBatchResult.Success -> GeocodingLookupResult.Success(result.results.firstOrNull())
            is GeocodingBatchResult.Failure -> GeocodingLookupResult.Failure(result.error)
        }
    }

    override suspend fun searchMultiple(
        query: String,
        biasLat: Double?,
        biasLon: Double?,
        limit: Int,
        useGoogle: Boolean
    ): GeocodingBatchResult = withRateLimit {
        val queryHash = query.anonymizeForLog()
        Log.d(TAG, "searchMultiple() OVERRIDE called — queryHash=$queryHash, hasBias=${biasLat != null && biasLon != null}, limit=$limit")
        // No country filter for the interactive picker — the user may search for
        // any merchant by name and should not be restricted to Greece-only results.
        // The Greece viewbox is still sent as a soft bias so local results rank first.
        val url = buildUrl(query, biasLat, biasLon, cityHint = null, bounded = false, useCountryFilter = false)
        val safeLogRoute = buildSafeLogRoute(
            query = query,
            biasLat = biasLat,
            biasLon = biasLon,
            cityHint = null,
            bounded = false,
            useCountryFilter = false
        )
        executeRequest(url, query, biasLat != null, safeLogRoute)
    }

    override suspend fun reverseGeocode(lat: Double, lon: Double): GeocodingLookupResult = withRateLimit {
        val encoded = "%.7f".format(Locale.US, lat)
        val encodedLon = "%.7f".format(Locale.US, lon)
        val url = "${AppConfig.Location.NOMINATIM_BASE_URL}/reverse" +
                "?lat=$encoded&lon=$encodedLon&format=json&addressdetails=1"
        Log.d(TAG, "reverseGeocode() route=${buildSafeReverseLogRoute(lat, lon)}")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AppConfig.Location.NOMINATIM_USER_AGENT)
            .header("Accept-Language", "el,en")
            .build()
        try {
            // B16 fix: use response.use {} to ensure body is closed even on exceptions
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "reverseGeocode HTTP ${response.code}")
                    return@withRateLimit GeocodingLookupResult.Failure(GeocodingError.HttpError(response.code))
                }
                val body = response.body?.string()
                    ?: return@withRateLimit GeocodingLookupResult.Failure(GeocodingError.ParseError)
                val obj = org.json.JSONObject(body)
                if (obj.has("error")) {
                    Log.w(TAG, "reverseGeocode error: ${obj.optString("error")}")
                    return@withRateLimit GeocodingLookupResult.Failure(GeocodingError.ServiceDown)
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
                GeocodingLookupResult.Success(GeocodingResult(
                    latitude = resultLat,
                    longitude = resultLon,
                    osmId = osmId,
                    name = nameVal,
                    displayAddress = displayAddress,
                    confidence = 1.0f,
                    source = AppConfig.Location.SOURCE_NOMINATIM_GPS_BIAS
                ))
            }
        } catch (e: IOException) {
            Log.e(TAG, "reverseGeocode network error: ${e.message}", e)
            GeocodingLookupResult.Failure(GeocodingError.NetworkError)
        } catch (e: Exception) {
            Log.e(TAG, "reverseGeocode error: ${e.message}", e)
            GeocodingLookupResult.Failure(GeocodingError.Unknown(e.message))
        }
    }

    private suspend fun executeRequest(
        url: String,
        queryForLog: String,
        hadGpsBias: Boolean,
        safeLogRoute: String
    ): GeocodingBatchResult {
        val queryHash = queryForLog.anonymizeForLog()
        Log.d(TAG, "==> Nominatim request START")
        Log.d(TAG, "    Query hash: $queryHash")
        Log.d(TAG, "    Route: $safeLogRoute")
        
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AppConfig.Location.NOMINATIM_USER_AGENT)
            .header("Accept-Language", "el,en")
            .build()

        return try {
            // B16 fix: use response.use {} to ensure body is closed even on exceptions
            executeWithRetry(request).use { response ->
                Log.d(TAG, "    HTTP response code: ${response.code}")
                
                if (!response.isSuccessful) {
                    Log.w(TAG, "    <== Nominatim HTTP ${response.code} for query hash: $queryHash")
                    if (response.code == 429) {
                        return GeocodingBatchResult.Failure(GeocodingError.RateLimited)
                    }
                    if (response.code == 403) {
                        throw IOException("NOMINATIM_403")
                    }
                    return GeocodingBatchResult.Failure(GeocodingError.HttpError(response.code))
                }
                val body = response.body?.string() ?: run {
                    Log.w(TAG, "    <== Empty response body")
                    return GeocodingBatchResult.Failure(GeocodingError.ParseError)
                }
                Log.d(TAG, "    Response body length: ${body.length} chars")
                val bodyPreview = body.take(RESPONSE_PREVIEW_CHAR_COUNT)
                Log.d(
                    TAG,
                    "    Body preview hash: ${bodyPreview.anonymizeForLog()} (previewChars=${bodyPreview.length})"
                )
                
                val results = parseAllResults(body, hadGpsBias)
                Log.d(TAG, "    <== Nominatim request END - ${results.size} results")
                if (results.isEmpty()) GeocodingBatchResult.Failure(GeocodingError.NoResults)
                else GeocodingBatchResult.Success(results)
            }
        } catch (e: IOException) {
            Log.e(TAG, "    <== Nominatim network error: ${e.message}", e)
            when {
                e.message == "NOMINATIM_403" -> GeocodingBatchResult.Failure(GeocodingError.RateLimited)
                else -> GeocodingBatchResult.Failure(GeocodingError.NetworkError)
            }
        } catch (e: JSONException) {
            Log.e(TAG, "    <== Nominatim parse error: ${e.message}", e)
            GeocodingBatchResult.Failure(GeocodingError.ParseError)
        }
    }

    private suspend fun executeWithRetry(
        request: Request,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 300
    ): okhttp3.Response {
        var currentDelay = initialDelayMs
        var lastError: IOException? = null

        repeat(maxAttempts) { attempt ->
            try {
                val response = client.newCall(request).execute()
                if (response.code >= 500 || response.code == 429) {
                    if (attempt < maxAttempts - 1) {
                        response.close()
                        delay(currentDelay)
                        currentDelay = (currentDelay * 2).coerceAtMost(2000)
                        return@repeat
                    }
                    return response
                }
                return response
            } catch (e: IOException) {
                lastError = e
                if (attempt < maxAttempts - 1) {
                    delay(currentDelay)
                    currentDelay = (currentDelay * 2).coerceAtMost(2000)
                }
            }
        }

        throw lastError ?: IOException("Request failed after retries")
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

    private fun buildSafeLogRoute(
        query: String,
        biasLat: Double?,
        biasLon: Double?,
        cityHint: String?,
        bounded: Boolean,
        useCountryFilter: Boolean
    ): String {
        val queryStr = if (!cityHint.isNullOrBlank()) "$query $cityHint" else query
        val bias = if (biasLat != null && biasLon != null) {
            "viewbox=<redacted>&bounded=${if (bounded) 1 else 0}"
        } else {
            "viewbox=<greece-default>&bounded=${if (bounded) 1 else 0}"
        }
        val country = if (useCountryFilter) {
            "&countrycodes=${AppConfig.Location.GREECE_COUNTRY_CODE}"
        } else {
            ""
        }
        return "/search?q=<redacted:${queryStr.length}>" +
                "&format=json" +
                "&limit=${AppConfig.Location.NOMINATIM_MAX_RESULTS}" +
                "&addressdetails=1" +
                country +
                "&$bias"
    }

    private fun buildSafeReverseLogRoute(lat: Double, lon: Double): String {
        val coordinateHash = "$lat,$lon".anonymizeForLog()
        return "/reverse?lat=<redacted>&lon=<redacted>&format=json&addressdetails=1&coordsHash=$coordinateHash"
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
                Log.v(TAG, "Resolved city hash: ${cityFromAddress.anonymizeForLog()}")
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
        const val RESPONSE_PREVIEW_CHAR_COUNT = 500
    }
}
