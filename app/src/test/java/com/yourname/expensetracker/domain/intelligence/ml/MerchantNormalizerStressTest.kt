package com.yourname.expensetracker.domain.intelligence.ml

import com.yourname.expensetracker.data.database.entity.MerchantAlias
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class MerchantNormalizerStressTest {

    private val repository = mockk<MerchantNormalizationRepository>(relaxed = true)
    private val merchantRules = MerchantRulesRepository()
    private val greeklishNormalizer = mockk<GreeklishNormalizer>(relaxed = true)
    private val context = mockk<android.content.Context>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var normalizer: MerchantNormalizer

    @Before
    fun setup() {
        every { timeProvider.now() } returns 1_700_000_000_000L
        normalizer = MerchantNormalizer(repository, merchantRules, greeklishNormalizer, context, timeProvider)
    }

    @Test
    fun `stress - empty string returns Unknown`() = runBlocking {
        val result = normalizer.normalize("")
        assertEquals("Unknown", result.canonical.normalizedName)
        assertEquals(MatchType.NEW_MERCHANT, result.matchType)
    }

    @Test
    fun `stress - whitespace only returns Unknown`() = runBlocking {
        val result = normalizer.normalize("   \t  ")
        assertEquals("Unknown", result.canonical.normalizedName)
        assertEquals(MatchType.NEW_MERCHANT, result.matchType)
    }

    @Test
    fun `stress - name over 200 chars truncated no crash`() = runBlocking {
        val longName = "A".repeat(250)
        coEvery { repository.getAliasByNormalizedKey(any()) } returns null
        coEvery { repository.getCanonicalBySearchKey(any()) } returns null
        coEvery { repository.getTopMerchants(any()) } returns emptyList()
        coEvery { repository.insertCanonical(any()) } returns 1L
        coEvery { repository.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns AliasLinkResult.Created(1L)

        val result = normalizer.normalize(longName)
        assertNotNull(result)
        assertEquals(MatchType.NEW_MERCHANT, result.matchType)
        assertEquals(200, result.canonical.normalizedName.length)
    }

    @Test
    fun `stress - alias match returns USER_DEFINED when user defined`() = runBlocking {
        val alias = mockk<MerchantAlias>()
        val canonical = MerchantCanonical(id = 1, normalizedName = "Starbucks", searchKey = "starbucks")
        coEvery { alias.canonicalId } returns 1L
        coEvery { alias.isUserDefined } returns true
        coEvery { repository.getAliasByNormalizedKey("starbucks") } returns alias
        coEvery { repository.getCanonicalById(1) } returns canonical

        val result = normalizer.normalize("Starbucks")
        assertEquals("Starbucks", result.canonical.normalizedName)
        assertEquals(MatchType.USER_DEFINED, result.matchType)
    }

    @Test
    fun `stress - alias match returns ALIAS_MATCH when not user defined`() = runBlocking {
        val alias = mockk<MerchantAlias>()
        val canonical = MerchantCanonical(id = 2, normalizedName = "McDonald's", searchKey = "mcdonalds")
        coEvery { alias.canonicalId } returns 2L
        coEvery { alias.isUserDefined } returns false
        coEvery { repository.getAliasByNormalizedKey("mcdonalds") } returns alias
        coEvery { repository.getCanonicalById(2) } returns canonical

        val result = normalizer.normalize("McDonald's")
        assertEquals("McDonald's", result.canonical.normalizedName)
        assertEquals(MatchType.ALIAS_MATCH, result.matchType)
    }

    @Test
    fun `stress - exact canonical match returns EXACT_MATCH`() = runBlocking {
        val canonical = MerchantCanonical(id = 3, normalizedName = "Lidl", searchKey = "lidl")
        coEvery { repository.getAliasByNormalizedKey("lidl") } returns null
        coEvery { repository.getCanonicalBySearchKey("lidl") } returns canonical

        val result = normalizer.normalize("Lidl")
        assertEquals("Lidl", result.canonical.normalizedName)
        assertEquals(MatchType.EXACT_MATCH, result.matchType)
    }

    @Test
    fun `stress - new merchant with autoCreate creates canonical`() = runBlocking {
        coEvery { repository.getAliasByNormalizedKey(any()) } returns null
        coEvery { repository.getCanonicalBySearchKey(any()) } returns null
        coEvery { repository.getTopMerchants(any()) } returns emptyList()
        coEvery { repository.insertCanonical(any()) } returns 99L
        coEvery { repository.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns AliasLinkResult.Created(1L)

        val result = normalizer.normalize("New Coffee Shop", autoCreate = true)
        assertEquals(MatchType.NEW_MERCHANT, result.matchType)
        assertEquals("New Coffee Shop", result.canonical.normalizedName)
        coVerify { repository.insertCanonical(any()) }
        coVerify { repository.linkAliasToCanonical(any(), any(), 99L, false, any()) }
    }

    @Test
    fun `stress - autoCreate false returns placeholder without insert`() = runBlocking {
        coEvery { repository.getAliasByNormalizedKey(any()) } returns null
        coEvery { repository.getCanonicalBySearchKey(any()) } returns null
        coEvery { repository.getTopMerchants(any()) } returns emptyList()

        val result = normalizer.normalize("Unknown Store", autoCreate = false)
        assertEquals(MatchType.NEW_MERCHANT, result.matchType)
        assertEquals("Unknown Store", result.canonical.normalizedName)
        assertEquals(0.0f, result.confidence, 0.001f)
        coVerify(exactly = 0) { repository.insertCanonical(any()) }
    }

    @Test
    fun `stress - fuzzy match when similar merchant in tree`() = runBlocking {
        val canonical = MerchantCanonical(id = 5, normalizedName = "Starbucks", searchKey = "starbucks")
        coEvery { repository.getAliasByNormalizedKey(any()) } returns null
        coEvery { repository.getCanonicalBySearchKey("starbucks") } returns canonical
        coEvery { repository.getCanonicalBySearchKey("starbuks") } returns null
        coEvery { repository.getTopMerchants(1000) } returns listOf(canonical)
        coEvery { repository.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns AliasLinkResult.Created(1L)

        val result = normalizer.normalize("Starbuks")
        assertNotNull(result)
        assertEquals("Starbucks", result.canonical.normalizedName)
        assertEquals(MatchType.FUZZY_MATCH, result.matchType)
    }

    @Test
    fun `stress - concurrent normalize calls no crash`() = runTest {
        coEvery { repository.getAliasByNormalizedKey(any()) } returns null
        coEvery { repository.getCanonicalBySearchKey(any()) } returns null
        coEvery { repository.getTopMerchants(any()) } returns emptyList()
        coEvery { repository.insertCanonical(any()) } answers { (args[0] as MerchantCanonical).searchKey.hashCode().toLong().and(0x7FFFFFFF) }
        coEvery { repository.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns AliasLinkResult.Created(1L)

        val results = (1..100).map { i ->
            async { normalizer.normalize("Merchant$i") }
        }.awaitAll()

        assertEquals(100, results.size)
        results.forEach { assertNotNull(it.canonical.normalizedName) }
    }

    @Test
    fun `stress - Greek text produces valid result`() = runBlocking {
        coEvery { repository.getAliasByNormalizedKey(any()) } returns null
        coEvery { repository.getCanonicalBySearchKey(any()) } returns null
        coEvery { repository.getTopMerchants(any()) } returns emptyList()
        coEvery { repository.insertCanonical(any()) } returns 1L
        coEvery { repository.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns AliasLinkResult.Created(1L)

        val result = normalizer.normalize("Σκλαβενίτης")
        assertNotNull(result)
        assertEquals(MatchType.NEW_MERCHANT, result.matchType)
        assert(result.canonical.searchKey.isNotEmpty())
    }

    @Test
    fun `stress - cleanMerchantName delegates to merchantRules`() {
        val cleaned = normalizer.cleanMerchantName("  COFFEE SHOP #123  ")
        assertTrue("Expected cleaned name to contain 'COFFEE' or 'coffee', got: $cleaned",
            cleaned.contains("COFFEE") || cleaned.contains("coffee"))
    }
}
