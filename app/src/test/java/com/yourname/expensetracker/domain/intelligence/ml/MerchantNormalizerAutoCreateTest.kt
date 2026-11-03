package com.yourname.expensetracker.domain.intelligence.ml

import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.domain.categorization.AliasLinkResult
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FIX-104 F: `autoCreate = false` must be side-effect free, including the 95%+ fuzzy auto-learn
 * branch. `autoCreate = true` keeps the existing learn/create behavior.
 */
class MerchantNormalizerAutoCreateTest {

    private val repository = mockk<MerchantNormalizationRepository>(relaxed = true)
    private val merchantRules = MerchantRulesRepository()
    private val greeklishNormalizer = mockk<GreeklishNormalizer>(relaxed = true)
    private val context = mockk<android.content.Context>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var normalizer: MerchantNormalizer

    private val starbucks = MerchantCanonical(id = 5L, normalizedName = "Starbucks", searchKey = "starbucks")

    @Before
    fun setup() {
        every { timeProvider.now() } returns NOW
        normalizer = MerchantNormalizer(repository, merchantRules, greeklishNormalizer, context, timeProvider)
        coEvery { repository.getAliasByNormalizedKey(any()) } returns null
        coEvery { repository.getCanonicalBySearchKey("starbucks") } returns starbucks
        coEvery { repository.getCanonicalBySearchKey("starbuks") } returns null
        coEvery { repository.getTopMerchants(1000) } returns listOf(starbucks)
        coEvery { repository.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns AliasLinkResult.Created(1L)
    }

    @Test
    fun `FIX-104 F fuzzy hit at or above 0_95 with autoCreate false returns fuzzy result and never links`() = runBlocking {
        val result = normalizer.normalize("Starbuks", autoCreate = false)

        assertEquals(MatchType.FUZZY_MATCH, result.matchType)
        assertEquals(5L, result.canonical.id)
        assertTrue("fixture must be a >= 0.95 fuzzy hit, was ${result.confidence}", result.confidence >= 0.95f)
        coVerify(exactly = 0) { repository.linkAliasToCanonical(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.insertCanonical(any()) }
    }

    @Test
    fun `FIX-104 F fuzzy hit at or above 0_95 with autoCreate true links alias to the matched canonical`() = runBlocking {
        val result = normalizer.normalize("Starbuks", autoCreate = true)

        assertEquals(MatchType.FUZZY_MATCH, result.matchType)
        assertEquals(5L, result.canonical.id)
        assertTrue("fixture must be a >= 0.95 fuzzy hit, was ${result.confidence}", result.confidence >= 0.95f)
        coVerify(exactly = 1) {
            repository.linkAliasToCanonical("Starbuks", "starbuks", 5L, false, NOW)
        }
        coVerify(exactly = 0) { repository.insertCanonical(any()) }
    }

    @Test
    fun `FIX-104 F default autoCreate keeps linking the high-confidence fuzzy hit`() = runBlocking {
        normalizer.normalize("Starbuks")

        coVerify(exactly = 1) {
            repository.linkAliasToCanonical("Starbuks", "starbuks", 5L, false, NOW)
        }
    }

    @Test
    fun `FIX-104 F miss with autoCreate false returns unpersisted placeholder and makes no create or link call`() = runBlocking {
        coEvery { repository.getTopMerchants(1000) } returns emptyList()
        coEvery { repository.getCanonicalBySearchKey(any()) } returns null

        val result = normalizer.normalize("Unknown Store", autoCreate = false)

        assertEquals(MatchType.NEW_MERCHANT, result.matchType)
        assertEquals("Unknown Store", result.canonical.normalizedName)
        assertEquals("placeholder must be unpersisted", 0L, result.canonical.id)
        assertEquals(0.0f, result.confidence, 0.0f)
        coVerify(exactly = 0) { repository.insertCanonical(any()) }
        coVerify(exactly = 0) { repository.linkAliasToCanonical(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `FIX-104 F miss with autoCreate true creates canonical and links alias`() = runBlocking {
        coEvery { repository.getTopMerchants(1000) } returns emptyList()
        coEvery { repository.getCanonicalBySearchKey(any()) } returns null
        coEvery { repository.insertCanonical(any()) } returns 77L

        val result = normalizer.normalize("Unknown Store", autoCreate = true)

        assertEquals(MatchType.NEW_MERCHANT, result.matchType)
        assertEquals(77L, result.canonical.id)
        coVerify(exactly = 1) { repository.insertCanonical(any()) }
        coVerify(exactly = 1) {
            repository.linkAliasToCanonical("Unknown Store", any(), 77L, false, NOW)
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
