package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.data.location.internal.anonymizeForLog
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeoapifyKey
import com.yourname.expensetracker.di.LocationHttpClient
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.location.GeocodingBatchResult
import com.yourname.expensetracker.domain.location.GeocodingError
import com.yourname.expensetracker.domain.location.GeocodingLookupResult
import com.yourname.expensetracker.domain.location.GeocodingResult
import com.yourname.expensetracker.domain.location.GeocodingService
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Geoapify geocoding service — free tier 3000 req/day, augmented OSM data.
 * Requires SecureKeyStorage.getGeoapifyKey(). Returns empty list gracefully if
 * the key is missing/blank.
 *
 * Used as the second tier in the [CompositeGeocodingService] cascade.
 */
// CRITICAL FIX (CRITICAL-1): Now uses SecureKeyStorage instead of BuildConfig
@Singleton
class GeoapifyGeocodingService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage,
    @LocationHttpClient private val client: OkHttpClient
) : GeocodingService {

    private val apiKey get() = secureKeyStorage.getGeoapifyKey() ?: ""

    override suspend fun search(
        merchantName: String,
        biasLat: Double?,
        biasLon: Double?,
        cityHint: String?,
        bounded: Boolean
    ): GeocodingLookupResult = when (val result = searchMultiple(merchantName, biasLat, biasLon, limit = 1)) {
        is GeocodingBatchResult.Success -> GeocodingLookupResult.Success(result.results.firstOrNull())
        is GeocodingBatchResult.Failure -> GeocodingLookupResult.Failure(result.error)
    }

    override suspend fun searchMultiple(
        query: String,
        biasLat: Double?,
        biasLon: Double?,
        limit: Int,
        useGoogle: Boolean
    ): GeocodingBatchResult {
        if (apiKey.isBlank()) {
            Log.d(TAG, "Geoapify: API key missing, skipping")
            return GeocodingBatchResult.Failure(GeocodingError.ServiceDown)
        }

        val url = buildUrl(query, biasLat, biasLon, limit)
        Log.d(TAG, "==> Geoapify request ${buildSafeLogRoute(query, biasLat, biasLon, limit)}")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AppConfig.Location.NOMINATIM_USER_AGENT)
            .build()

        return try {
            executeWithRetry(request).use { response ->
                Log.d(TAG, "    HTTP ${response.code}")
                if (!response.isSuccessful) {
                    Log.w(TAG, "    Geoapify HTTP ${response.code}")
                    return@use GeocodingBatchResult.Failure(
                        if (response.code == 429) GeocodingError.RateLimited else GeocodingError.HttpError(response.code)
                    )
                }
                val body = response.body?.string()
                    ?: return@use GeocodingBatchResult.Failure(GeocodingError.ParseError)
                val parsed = parseResults(body)
                Log.d(TAG, "    <== ${parsed.size} results")
                if (parsed.isEmpty()) GeocodingBatchResult.Failure(GeocodingError.NoResults)
                else GeocodingBatchResult.Success(parsed)
            }
        } catch (e: IOException) {
            Log.e(TAG, "    Geoapify network error: ${e.message}")
            GeocodingBatchResult.Failure(GeocodingError.NetworkError)
        } catch (e: JSONException) {
            Log.e(TAG, "    Geoapify parse error: ${e.message}")
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
        throw lastError ?: IOException("Geoapify request failed after retries")
    }

    private fun buildUrl(query: String, biasLat: Double?, biasLon: Double?, limit: Int): String {
        val baseUrl = "${AppConfig.Location.GEOAPIFY_BASE_URL}/v1/geocode/search".toHttpUrl()
        return baseUrl.newBuilder()
            .addQueryParameter("text", query)
            .addQueryParameter("format", "json")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("lang", "en")
            .addQueryParameter(
                "bias",
                if (biasLat != null && biasLon != null) {
                    "proximity:$biasLon,$biasLat"
                } else {
                    "countrycode:gr"
                }
            )
            // Geoapify Geocoding API documents apiKey as a required query parameter.
            // Header-based auth is not documented for this endpoint.
            // IMPORTANT: never log the full URL (it includes secrets); use buildSafeLogRoute().
            // TODO(security): route Geoapify requests through backend proxy and remove client-side key usage.
            .addQueryParameter("apiKey", apiKey)
            .build()
            .toString()
    }

    private fun buildSafeLogRoute(query: String, biasLat: Double?, biasLon: Double?, limit: Int): String {
        val bias = if (biasLat != null && biasLon != null) {
            "proximity:<redacted:${"$biasLon,$biasLat".anonymizeForLog()}>"
        } else {
            "countrycode:gr"
        }
        return "/v1/geocode/search?text=<redacted:${query.length}>&format=json&limit=$limit&lang=en&bias=$bias"
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
