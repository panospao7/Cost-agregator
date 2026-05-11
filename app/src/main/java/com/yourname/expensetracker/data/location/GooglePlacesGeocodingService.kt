package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.data.location.internal.anonymizeForLog
import com.yourname.expensetracker.data.location.internal.executeCancellable
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGooglePlacesKey
import com.yourname.expensetracker.di.LocationHttpClient
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.location.GeocodingBatchResult
import com.yourname.expensetracker.domain.location.GeocodingError
import com.yourname.expensetracker.domain.location.GeocodingLookupResult
import com.yourname.expensetracker.domain.location.GeocodingResult
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.delay
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
    private val secureKeyStorage: SecureKeyStorage,
    @LocationHttpClient private val client: OkHttpClient,
    private val privacyGate: PrivacyGate
) : GeocodingService {

    private val apiKey get() = secureKeyStorage.getGooglePlacesKey() ?: ""

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override suspend fun search(
        merchantName: String,
        biasLat: Double?,
        biasLon: Double?,
        cityHint: String?,
        bounded: Boolean
    ): GeocodingLookupResult {
        val gateCheck = privacyGate.check(PrivacyCapability.EXTERNAL_GEOCODING)
        if (gateCheck.blocksExecution()) {
            Log.w(TAG, "Google Places geocoding blocked by privacy gate: ${gateCheck.reason()}")
            return GeocodingLookupResult.Failure(GeocodingError.Disabled)
        }
        return when (val result = searchMultiple(merchantName, biasLat, biasLon, limit = 1)) {
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
    ): GeocodingBatchResult {
        val gateCheck = privacyGate.check(PrivacyCapability.EXTERNAL_GEOCODING)
        if (gateCheck.blocksExecution()) {
            Log.w(TAG, "Google Places geocoding blocked by privacy gate: ${gateCheck.reason()}")
            return GeocodingBatchResult.Failure(GeocodingError.Disabled)
        }
        if (apiKey.isBlank()) {
            Log.d(TAG, "Google Places: API key missing, skipping")
            return GeocodingBatchResult.Failure(GeocodingError.ServiceDown)
        }

        val requestBody = buildRequestBody(query, biasLat, biasLon, limit)
        val url = "${AppConfig.Location.GOOGLE_PLACES_BASE_URL}/v1/places:searchText"
        val queryHash = query.anonymizeForLog()
        Log.d(TAG, "==> Google Places request route=${buildSafeLogRoute(query, biasLat, biasLon, limit)}")

        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask",
                "places.displayName,places.formattedAddress,places.location,places.id")
            .header("User-Agent", AppConfig.Location.NOMINATIM_USER_AGENT)
            .build()

        return try {
            executeWithRetry(request).use { response ->
                Log.d(TAG, "    HTTP ${response.code}")
                if (!response.isSuccessful) {
                    val errorPreview = response.body?.string().orEmpty().take(ERROR_PREVIEW_CHAR_COUNT)
                    Log.w(
                        TAG,
                        "    Google Places HTTP ${response.code} queryHash=$queryHash errorPreviewHash=${errorPreview.anonymizeForLog()} (previewChars=${errorPreview.length})"
                    )
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
            Log.e(TAG, "    Google Places network error: ${e.message}")
            GeocodingBatchResult.Failure(GeocodingError.NetworkError)
        } catch (e: JSONException) {
            Log.e(TAG, "    Google Places parse error: ${e.message}")
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
                val response = client.executeCancellable(request)
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
        throw lastError ?: IOException("Google Places request failed after retries")
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

    private fun buildSafeLogRoute(query: String, biasLat: Double?, biasLon: Double?, limit: Int): String {
        val bias = if (biasLat != null && biasLon != null) {
            "locationBias=circle(<redacted>)"
        } else {
            "locationBias=rectangle(greece-default)"
        }
        return "/v1/places:searchText?textQuery=<redacted:${query.length}>&maxResultCount=${limit.coerceIn(1, 20)}&$bias"
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
        const val ERROR_PREVIEW_CHAR_COUNT = 200
    }
}
