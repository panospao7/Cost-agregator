package com.yourname.expensetracker.domain.location

import com.yourname.expensetracker.data.database.entity.MerchantLocationCorrection
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MerchantLocationRepository
import com.yourname.expensetracker.domain.categorization.CanonicalResult
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.categorization.MerchantCanonicalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2 unit tests for the cacheKey selection logic in [LocationResolver.resolve].
 *
 * Line 73 of LocationResolver:
 *   val cacheKey = merchantKey ?: MerchantKeyGenerator.generate(rawMerchantName)
 *
 * Test 1 – when a pre-computed [merchantKey] is supplied, it is used as the
 *           cacheKey (the correction lookup is called with that exact key).
 * Test 2 – when [merchantKey] is null, the cacheKey is derived via
 *           [MerchantKeyGenerator.generate] from the raw merchant name.
 */
class LocationResolverTest {

    private lateinit var resolver: LocationResolver

    private val geocodingService   = mockk<GeocodingService>(relaxed = true)
    private val nearbyPoiService   = mockk<NearbyPoiService>(relaxed = true)
    private val locationProvider   = mockk<ForegroundLocationProvider>(relaxed = true)
    private val locationRepository = mockk<MerchantLocationRepository>(relaxed = true)
    private val expenseRepository  = mockk<ExpenseRepository>(relaxed = true)
    private val merchantCleaner    = mockk<MerchantCleaner>(relaxed = true)
    private val canonicalizer      = mockk<MerchantCanonicalizer>(relaxed = true)
    private val greeklishNormalizer= mockk<GreeklishNormalizer>(relaxed = true)

    /** Arbitrary past transaction date (will not trigger GPS-bias branch). */
    private val oldTransactionMs = System.currentTimeMillis() - 24 * 60 * 60 * 1000L // 24 h ago

    @Before
    fun setup() {
        // android.util.Log is not available in JVM unit tests — mock the static methods.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0

        // MerchantCleaner.clean() and MerchantCanonicalizer.canonicalize() are NOT
        // suspend functions — use every{} not coEvery{}.
        every { merchantCleaner.clean(any()) } answers { firstArg() }
        every { canonicalizer.canonicalize(any()) } answers {
            CanonicalResult(
                canonicalName     = firstArg(),
                strippedParts     = emptyList(),
                confidencePenalty = 0.0
            )
        }
        every { greeklishNormalizer.normalize(any()) } answers { firstArg() }

        // Device location unavailable → no GPS-bias branches are entered.
        coEvery { locationProvider.getLastKnownLocation() } returns null

        // Cluster query returns empty → no history-biased branch.
        coEvery { expenseRepository.getMerchantLocationClusters(any()) } returns emptyList()

        // Global cache miss → resolver falls through to Unresolved.
        coEvery { locationRepository.getCachedLocation(any()) } returns null

        resolver = LocationResolver(
            geocodingService    = geocodingService,
            nearbyPoiService    = nearbyPoiService,
            locationProvider    = locationProvider,
            locationRepository  = locationRepository,
            expenseRepository   = expenseRepository,
            merchantCleaner     = merchantCleaner,
            canonicalizer       = canonicalizer,
            greeklishNormalizer = greeklishNormalizer
        )
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun correctionFor(key: String) = MerchantLocationCorrection(
        normalizedMerchantName = key,
        correctedLatitude      = 37.97,
        correctedLongitude     = 23.73,
        displayAddress         = "Athens, GR"
    )

    // ── Test 1: provided merchantKey is used as cacheKey ─────────────────────

    /**
     * When resolve() is called with a non-null [merchantKey], the correction
     * lookup (and all subsequent DB calls) must use that exact key — NOT a
     * value derived from rawMerchantName.
     */
    @Test
    fun `resolve uses provided merchantKey as cacheKey for correction lookup`() = runTest {
        val providedKey = "sklavenitis"           // pre-computed canonical key
        val rawMerchant = "ΣΚΛΑΒΕΝΙΤΗΣ Α.Ε."     // raw name that would produce a different key if re-derived

        // Stub: correction exists for the provided key.
        coEvery {
            locationRepository.getCorrection(
                merchantName = providedKey,
                deviceLat    = null,
                deviceLon    = null
            )
        } returns correctionFor(providedKey)

        val result = resolver.resolve(
            rawMerchantName  = rawMerchant,
            transactionDateMs = oldTransactionMs,
            merchantKey      = providedKey
        )

        // Should return Resolved with source USER_MANUAL (correction hit).
        assertTrue("Expected Resolved but got $result", result is LocationResolutionResult.Resolved)
        assertEquals(
            "USER_MANUAL",
            (result as LocationResolutionResult.Resolved).source
        )

        // The correction lookup must have been invoked with the provided key.
        coVerify(exactly = 1) {
            locationRepository.getCorrection(
                merchantName = providedKey,
                deviceLat    = null,
                deviceLon    = null
            )
        }
    }

    // ── Test 2: null merchantKey → MerchantKeyGenerator fallback ─────────────

    /**
     * When resolve() is called with merchantKey == null (legacy / not-yet-backfilled
     * row), the cacheKey must be computed via MerchantKeyGenerator.generate() from
     * rawMerchantName and that derived key must be used for correction lookups.
     */
    @Test
    fun `resolve derives cacheKey from rawMerchantName when merchantKey is null`() = runTest {
        val rawMerchant  = "Σκλαβενίτης"
        val expectedKey  = MerchantKeyGenerator.generate(rawMerchant)  // "sklavenitis"

        // Stub: correction exists only for the expected derived key.
        coEvery {
            locationRepository.getCorrection(
                merchantName = expectedKey,
                deviceLat    = null,
                deviceLon    = null
            )
        } returns correctionFor(expectedKey)

        val result = resolver.resolve(
            rawMerchantName   = rawMerchant,
            transactionDateMs = oldTransactionMs,
            merchantKey       = null            // ← no pre-computed key
        )

        // Should return Resolved with source USER_MANUAL (correction hit).
        assertTrue("Expected Resolved but got $result", result is LocationResolutionResult.Resolved)
        assertEquals(
            "USER_MANUAL",
            (result as LocationResolutionResult.Resolved).source
        )

        // The correction lookup must have been invoked with the MerchantKeyGenerator result.
        coVerify(exactly = 1) {
            locationRepository.getCorrection(
                merchantName = expectedKey,
                deviceLat    = null,
                deviceLon    = null
            )
        }
    }
}
