package com.yourname.expensetracker.domain.categorization

import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CategorizationEngineTest {
    private val merchantCategoryDao = mockk<com.yourname.expensetracker.data.database.dao.MerchantCategoryDao>(relaxed = true)
    private val merchantNormalizer = mockk<NewMerchantNormalizer>(relaxed = true)
    private lateinit var engine: CategorizationEngine

    @Before
    fun setup() {
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } answers {
            val name = firstArg<String>().uppercase()
            MerchantLookupResult(
                canonical = MerchantCanonical(normalizedName = name, searchKey = name.lowercase()),
                alias = null,
                confidence = 1.0f,
                matchType = MatchType.EXACT_MATCH
            )
        }
        engine = CategorizationEngine(merchantCategoryDao, merchantNormalizer)
    }

    @Test
    fun `normalize uppercases`() = runBlocking {
        assertEquals("STARBUCKS", engine.normalize("starbucks"))
        assertEquals("UBER-EATS", engine.normalize("uber-eats"))
    }

    @Test
    fun `normalize handles Greek characters`() = runBlocking {
        val result = engine.normalize("ΣΚΛΑΒΕΝΙΤΗΣ")
        assertTrue(result.contains("ΣΚΛΑΒΕΝΙΤΗΣ"))
    }

    @Test
    fun `exact match returns category`() = runBlocking {
        coEvery { merchantCategoryDao.getAll() } returns listOf(
            MerchantCategory("STARBUCKS", 5L)
        )

        val result = engine.categorize("starbucks")
        assertEquals(5L, result)
    }

    @Test
    fun `substring match finds pattern within merchant name`() = runBlocking {
        coEvery { merchantCategoryDao.getCategoryForMerchant("UBER EATS DELIVERY 1234") } returns null
        coEvery { merchantCategoryDao.getAll() } returns listOf(
            MerchantCategory("UBER EATS", 3L),
            MerchantCategory("UBER", 4L)
        )
        // Word-level match for "UBER"
        coEvery { merchantCategoryDao.getCategoryForMerchant("UBER") } returns
            MerchantCategory("UBER", 4L)

        val result = engine.categorize("UBER EATS DELIVERY 1234")
        // Should match "UBER EATS" first (longer pattern) via substring, returning 3L
        assertEquals(3L, result)
    }

    @Test
    fun `returns null when no match found`() = runBlocking {
        coEvery { merchantCategoryDao.getCategoryForMerchant(any()) } returns null
        coEvery { merchantCategoryDao.getAll() } returns emptyList()

        val result = engine.categorize("COMPLETELY UNKNOWN MERCHANT")
        assertNull(result)
    }

    @Test
    fun `cache invalidation resets cache`() = runBlocking {
        engine.invalidateCache()
        // No assertion needed — just ensure no crash
    }
}
