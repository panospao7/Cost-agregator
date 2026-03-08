package com.yourname.expensetracker.data.location

import android.util.Log
import com.yourname.expensetracker.domain.location.GeocodingResult
import com.yourname.expensetracker.domain.location.GeocodingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Parallel merge geocoding service.
 *
 * [searchMultiple] (interactive picker) fires multiple providers in parallel,
 * merges all results, re-ranks them so that results whose address contains the
 * user's qualifier words (e.g. a street name) float to the top, then deduplicates
 * by proximity (50 m radius).
 *
 * Smart gating:
 *   - Single-word query  → Photon + Nominatim only (free, no quota concern)
 *   - Multi-word query   → all 4 providers in parallel (covers brand + street combos)
 *
 * [search] (background resolution via LocationResolver) delegates directly to
 * Nominatim to preserve existing bias/bounded/countrycodes logic unchanged.
 *
 * Services with a missing/blank API key return [emptyList] without making an
 * HTTP call — the provider simply contributes nothing to the merged result.
 */
@Singleton
class CompositeGeocodingService @Inject constructor(
    private val photon: PhotonGeocodingService,
    private val geoapify: GeoapifyGeocodingService,
    private val googlePlaces: GooglePlacesGeocodingService,
    private val nominatim: NominatimGeocodingService
) : GeocodingService {

    /**
     * Background single-result resolution — delegates entirely to Nominatim
     * so that countrycodes, bounded, and GPS bias behaviour is unchanged.
     */
    override suspend fun search(
        merchantName: String,
        biasLat: Double?,
        biasLon: Double?,
        cityHint: String?,
        bounded: Boolean
    ): GeocodingResult? = nominatim.search(merchantName, biasLat, biasLon, cityHint, bounded)

    /**
     * F2: Reverse-geocode a coordinate — delegates to Nominatim which has the
     * full implementation. The interface default returns null, so without this
     * override long-press pins would never resolve an address.
     */
    override suspend fun reverseGeocode(lat: Double, lon: Double): GeocodingResult? =
        nominatim.reverseGeocode(lat, lon)

    /**
     * Interactive picker multi-result search.
     *
     * 1. Fires providers in parallel (smart-gated by query complexity).
     * 2. Merges all results.
     * 3. Re-ranks: results whose displayAddress contains the user's qualifier
     *    words (accent-insensitive) are sorted to the top.
     * 4. Deduplicates results within 50 m of each other.
     * 5. Returns up to [limit] results (minimum 10 shown to user).
     *
     * @param useGoogle When true, Google Places is included in the parallel
     *   search (costs API quota). When false (default) Google is skipped —
     *   the user must explicitly opt-in via the toggle in the search UI.
     */
    override suspend fun searchMultiple(
        query: String,
        biasLat: Double?,
        biasLon: Double?,
        limit: Int,
        useGoogle: Boolean
    ): List<GeocodingResult> {
        val complex = isComplexQuery(query)
        Log.d(TAG, "searchMultiple: \"$query\" complex=$complex useGoogle=$useGoogle")

        // ── Step 1: Fire services in parallel ──────────────────────────────
        val allResults: List<Pair<String, List<GeocodingResult>>> = coroutineScope {
            val jobs = mutableListOf<Deferred<Pair<String, List<GeocodingResult>>>>()

            // Always fire Photon + Nominatim (free, no quota concerns)
            jobs += async { "Photon" to safeSearch("Photon") { photon.searchMultiple(query, biasLat, biasLon, limit) } }
            jobs += async { "Nominatim" to safeSearch("Nominatim") { nominatim.searchMultiple(query, biasLat, biasLon, limit) } }

            // Geoapify fires for complex queries (free tier 3000/day, generous)
            // Google Places fires only when explicitly enabled by the user
            if (complex) {
                jobs += async { "Geoapify" to safeSearch("Geoapify") { geoapify.searchMultiple(query, biasLat, biasLon, limit) } }
                if (useGoogle) {
                    jobs += async { "GooglePlaces" to safeSearch("GooglePlaces") { googlePlaces.searchMultiple(query, biasLat, biasLon, limit) } }
                }
            }

            jobs.map { it.await() }
        }

        // Log contributions from each service
        for ((name, results) in allResults) {
            Log.d(TAG, "Merge: $name contributed ${results.size} results for \"$query\"")
        }

        // ── Step 2: Merge all results ───────────────────────────────────────
        val merged = allResults.flatMap { it.second }
        if (merged.isEmpty()) {
            Log.d(TAG, "Merge: all providers returned 0 results for \"$query\"")
            return emptyList()
        }

        // ── Step 3: Re-rank by qualifier match + confidence ─────────────────
        val qualifiers = extractDiscriminatingQualifiers(query, merged)
        val ranked = if (qualifiers.isNotEmpty()) {
            Log.d(TAG, "Merge: discriminating qualifier words = $qualifiers")
            merged.sortedByDescending { result ->
                val addr = stripAccents(result.displayAddress?.lowercase() ?: "")
                val matchCount = qualifiers.count { q -> addr.contains(q) }
                // Primary sort key: number of qualifier matches (weighted heavily)
                // Secondary sort key: provider confidence score
                matchCount * 10f + result.confidence
            }
        } else {
            // Single-word query or all tokens are brand words: sort by confidence only
            merged.sortedByDescending { it.confidence }
        }

        // ── Step 4: Deduplicate at 50 m ────────────────────────────────────
        val deduped = deduplicateByProximity(ranked, radiusMeters = 50.0)

        // ── Step 5: Trim to limit (at least 10) ────────────────────────────
        val returnLimit = limit.coerceAtLeast(10)
        val final = deduped.take(returnLimit)
        Log.d(TAG, "Merge: returning ${final.size} results after dedup+rank for \"$query\"")
        return final
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Returns true if the query contains at least 2 meaningful word tokens
     * (tokens with at least one letter or digit). A "complex" query like
     * "YES! μοναστηριου" has 2 meaningful tokens; "LIDL" has only 1.
     */
    private fun isComplexQuery(query: String): Boolean {
        val meaningfulWords = query.trim()
            .split("\\s+".toRegex())
            .filter { token -> token.any { it.isLetterOrDigit() } }
        return meaningfulWords.size >= 2
    }

    /**
     * Extracts **discriminating** qualifier tokens from the query by comparing
     * candidate tokens against the merged result set.
     *
     * Strategy:
     * 1. Split the query into meaningful tokens (strip punctuation-only tokens).
     * 2. For each token, check how many results' displayAddresses contain it
     *    (accent-insensitive).
     * 3. A token is a **brand word** if it appears in >50% of results — it's
     *    just the chain name repeated everywhere, not useful for ranking.
     * 4. A token is a **qualifier** if it appears in ≤50% of results — it
     *    discriminates between branches (e.g. a street or neighbourhood name).
     *
     * Examples:
     *   "yes stores μοναστηριου" + 9 results where "yes"/"stores" appear in all,
     *   but "μοναστηριου" appears in only 1 → qualifiers = ["μοναστηριου"]
     *
     *   "masoutis asvestocho" + 4 results where "masoutis" appears in all,
     *   "asvestocho" appears in 1 → qualifiers = ["asvestocho"]
     *
     * Falls back gracefully: if no results yet (shouldn't happen here) or all
     * tokens are brand words, returns empty list → sort by confidence only.
     */
    private fun extractDiscriminatingQualifiers(
        query: String,
        results: List<GeocodingResult>
    ): List<String> {
        if (results.isEmpty()) return emptyList()

        val tokens = query.trim()
            .split("\\s+".toRegex())
            .filter { it.any { c -> c.isLetterOrDigit() } }
        if (tokens.size <= 1) return emptyList()

        val threshold = results.size * 0.5 // tokens in >50% of results = brand word

        return tokens
            .map { stripAccents(it.lowercase()) }
            .filter { token ->
                // Count how many results contain this token in their address
                val hitCount = results.count { result ->
                    val addr = stripAccents(result.displayAddress?.lowercase() ?: "")
                    addr.contains(token)
                }
                // Keep only tokens that appear in ≤50% of results (discriminating)
                hitCount <= threshold
            }
    }

    /**
     * Removes diacritic marks (accents) from a string using Unicode NFD
     * decomposition. Allows accent-insensitive matching between user input
     * (often typed without accents on a phone) and geocoder-returned addresses
     * (which typically include canonical accented forms).
     *
     * "Μοναστηρίου" → "μοναστηριου" (after also lowercasing)
     */
    private fun stripAccents(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
    }

    /**
     * Haversine great-circle distance between two lat/lon points, in meters.
     */
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0 // Earth radius in metres
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2).let { it * it } + cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * Greedy deduplication: iterates [results] in order (already ranked, so
     * best results first). Keeps a result only if no already-kept result is
     * within [radiusMeters] of it. This ensures that when two services both
     * return the same POI, we keep the better-ranked one and discard the duplicate.
     */
    private fun deduplicateByProximity(
        results: List<GeocodingResult>,
        radiusMeters: Double
    ): List<GeocodingResult> {
        val kept = mutableListOf<GeocodingResult>()
        outer@ for (candidate in results) {
            for (existing in kept) {
                val dist = haversineMeters(
                    candidate.latitude, candidate.longitude,
                    existing.latitude, existing.longitude
                )
                if (dist <= radiusMeters) continue@outer // duplicate — skip
            }
            kept += candidate
        }
        return kept
    }

    /**
     * Wraps a geocoding service call. Re-throws [CancellationException] so
     * that coroutine cancellation (debounce, screen exit) propagates correctly
     * and cancels all sibling parallel jobs via [coroutineScope]. All other
     * exceptions are caught and logged, returning an empty list so the merge
     * continues with results from other services.
     */
    private suspend fun safeSearch(
        name: String,
        block: suspend () -> List<GeocodingResult>
    ): List<GeocodingResult> = try {
        block()
    } catch (e: CancellationException) {
        Log.d(TAG, "safeSearch[$name]: cancelled, propagating")
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "safeSearch[$name]: failed — ${e.message}")
        emptyList()
    }

    private companion object {
        const val TAG = "LocationSearch"
    }
}
