package com.yourname.expensetracker.domain.intelligence.ml

import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import android.content.Context

import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.data.repository.MerchantRulesRepository

class MerchantNormalizerTest {
    private val repository = mockk<MerchantNormalizationRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val merchantRules = MerchantRulesRepository() // Use real instance to test logic
    private val greeklishNormalizer = GreeklishNormalizer()
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var normalizer: MerchantNormalizer

    @Before
    fun setup() {
        every { timeProvider.now() } returns System.currentTimeMillis()
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
}
