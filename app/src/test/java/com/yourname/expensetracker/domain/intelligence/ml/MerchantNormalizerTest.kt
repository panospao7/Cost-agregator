package com.yourname.expensetracker.domain.intelligence.ml

import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.categorization.AliasLinkResult
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import android.content.Context

import com.yourname.expensetracker.data.repository.MerchantRulesRepository

class MerchantNormalizerTest {
    private val repository = mockk<MerchantNormalizationRepository>(relaxed = true)
    private val merchantRules = MerchantRulesRepository() // Use real instance to test logic
    private val greeklishNormalizer = mockk<GreeklishNormalizer>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var normalizer: MerchantNormalizer

    @Before
    fun setup() {
        every { timeProvider.now() } returns 1_700_000_000_000L
        normalizer = MerchantNormalizer(repository, merchantRules, greeklishNormalizer, context, timeProvider)
    }

    // Cleaning tests moved to MerchantRulesRepositoryTest

    @Test
    fun `normalize uses alias if exists`() = runBlocking {
        val alias = mockk<com.yourname.expensetracker.data.database.entity.MerchantAlias>()
        val canonical = MerchantCanonical(id = 1, normalizedName = "Target", searchKey = "target")
        
        coEvery { alias.canonicalId } returns 1
        coEvery { alias.isUserDefined } returns true
        coEvery { repository.getAliasByNormalizedKey("target") } returns alias
        coEvery { repository.getCanonicalById(1) } returns canonical

        val result = normalizer.normalize("Target")
        assertEquals("Target", result.canonical.normalizedName)
        assertEquals(MatchType.USER_DEFINED, result.matchType)
    }

    @Test
    fun `normalize handles empty name`() = runBlocking {
        val result = normalizer.normalize("")
        assertEquals("Unknown", result.canonical.normalizedName)
        assertEquals(MatchType.NEW_MERCHANT, result.matchType)
    }

    @Test
    fun `linkAliasToCanonical sameNormalizedKey sameCanonical returns UpdatedExisting`() = runBlocking {
        coEvery { repository.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns AliasLinkResult.UpdatedExisting(5L)
        coEvery { repository.getCanonicalBySearchKey(any()) } returns null
        coEvery { repository.getAliasByNormalizedKey(any()) } returns null

        val result = normalizer.linkAliasToCanonical("Test", 1L)
        assertTrue(result is AliasLinkResult.UpdatedExisting)
        assertEquals(5L, (result as AliasLinkResult.UpdatedExisting).aliasId)
    }

    @Test
    fun `linkAliasToCanonical sameNormalizedKey differentCanonical returns Conflict`() = runBlocking {
        val existingAlias = mockk<com.yourname.expensetracker.data.database.entity.MerchantAlias>()
        coEvery { existingAlias.canonicalId } returns 2L
        coEvery { repository.getAliasByNormalizedKey(any()) } returns existingAlias
        coEvery { repository.getCanonicalBySearchKey(any()) } returns null

        val result = normalizer.linkAliasToCanonical("Test", 1L)
        assertTrue(result is AliasLinkResult.Conflict)
        assertEquals(2L, (result as AliasLinkResult.Conflict).existingCanonicalId)
    }

    @Test
    fun `linkAliasToCanonical newAlias returns Created`() = runBlocking {
        coEvery { repository.linkAliasToCanonical(any(), any(), any(), any(), any()) } returns AliasLinkResult.Created(5L)
        coEvery { repository.getCanonicalBySearchKey(any()) } returns null
        coEvery { repository.getAliasByNormalizedKey(any()) } returns null

        val result = normalizer.linkAliasToCanonical("Test Merchant", 1L)
        assertTrue(result is AliasLinkResult.Created)
        assertEquals(5L, (result as AliasLinkResult.Created).aliasId)
    }

    @Test
    fun `fuzzy match ranks best candidate instead of first tree result`() = runBlocking {
        val first = MerchantCanonical(id = 1, normalizedName = "Market House", searchKey = "markethouse", totalOccurrences = 1)
        val best = MerchantCanonical(id = 2, normalizedName = "Market Home", searchKey = "markethome", totalOccurrences = 10, isVerified = true)

        coEvery { repository.getAliasByNormalizedKey(any()) } returns null
        coEvery { repository.getCanonicalBySearchKey("markethouse") } returns first
        coEvery { repository.getCanonicalBySearchKey("markethome") } returns best
        coEvery { repository.getTopMerchants(any<Int>()) } returns listOf(first, best)

        val result = normalizer.normalize("Market Hom", autoCreate = false)

        assertEquals(best.id, result.canonical.id)
        assertEquals(MatchType.FUZZY_MATCH, result.matchType)
    }
}
