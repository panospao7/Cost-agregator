package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.data.location.internal.anonymizeForLog
import com.yourname.expensetracker.data.location.internal.executeCancellable
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photon (photon.komoot.io) geocoding service — free, no API key required.
 * Uses OSM data with excellent fuzzy matching. Returns GeoJSON FeatureCollection.
 *
 * Used as the first tier in the [CompositeGeocodingService] cascade.
 *
 * **Internal use only.** Do not inject this class directly — use [GeocodingService]
 * which routes through [CompositeGeocodingService] with privacy gate enforcement.
 */
@Singleton
class PhotonGeocodingService @Inject constructor(
    @LocationHttpClient private val client: OkHttpClient,
    private val privacyGate: PrivacyGate
) : GeocodingService {

    override suspend fun search(
        merchantName: String,
        biasLat: Double?,
        biasLon: Double?,
        cityHint: String?,
        bounded: Boolean
    ): GeocodingLookupResult {
        val gateCheck = privacyGate.check(PrivacyCapability.EXTERNAL_GEOCODING)
        if (gateCheck.blocksExecution()) {
            Log.w(TAG, "Photon geocoding blocked by privacy gate: ${gateCheck.reason()}")
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
            Log.w(TAG, "Photon geocoding blocked by privacy gate: ${gateCheck.reason()}")
            return GeocodingBatchResult.Failure(GeocodingError.Disabled)
        }
        val url = buildUrl(query, biasLat, biasLon, limit)
        Log.d(TAG, "==> Photon request ${buildSafeLogRoute(query, biasLat, biasLon, limit)}")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AppConfig.Location.NOMINATIM_USER_AGENT)
            .build()

        return try {
            // B16 fix: use response.use {} to ensure body is closed even on exceptions
            executeWithRetry(request).use { response ->
                Log.d(TAG, "    HTTP ${response.code}")
                if (!response.isSuccessful) {
                    Log.w(TAG, "    Photon HTTP ${response.code}")
                    return GeocodingBatchResult.Failure(
                        if (response.code == 429) GeocodingError.RateLimited else GeocodingError.HttpError(response.code)
                    )
                }
                val body = response.body?.string() ?: return GeocodingBatchResult.Failure(GeocodingError.ParseError)
                val parsed = parseResults(body)
                Log.d(TAG, "    <== ${parsed.size} results")
                if (parsed.isEmpty()) GeocodingBatchResult.Failure(GeocodingError.NoResults)
                else GeocodingBatchResult.Success(parsed)
            }
        } catch (e: IOException) {
            Log.e(TAG, "    Photon network error: ${e.message}")
            GeocodingBatchResult.Failure(GeocodingError.NetworkError)
        } catch (e: JSONException) {
            Log.e(TAG, "    Photon parse error: ${e.message}")
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
        throw lastError ?: IOException("Photon request failed after retries")
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

    private fun buildSafeLogRoute(query: String, biasLat: Double?, biasLon: Double?, limit: Int): String {
        val bias = if (biasLat != null && biasLon != null) {
            "lat=<redacted>&lon=<redacted>&coordsHash=${"$biasLat,$biasLon".anonymizeForLog()}"
        } else {
            "lat=<none>&lon=<none>"
        }
        return "/api/?q=<redacted:${query.length}>&limit=$limit&lang=en&$bias"
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
