package com.yourname.expensetracker.domain.categorization

import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.intelligence.MerchantNormalizer
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CategorizationEngineTest {
    private val merchantCategoryDao = mockk<com.yourname.expensetracker.data.database.dao.MerchantCategoryDao>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
    private lateinit var engine: CategorizationEngine

    @Before
    fun setup() {
        // Setup default normalizer behavior to match what test expects (simple uppercasing/cleaning)
        // Since normalize() in engine just delegates to normalizer, we mock it.
        every { merchantNormalizer.normalize(any()) } answers {
            val input = firstArg<String>()
            input.uppercase().replace(Regex("[^A-Z0-9 ]"), " ").trim().replace(Regex("\\s+"), " ")
        }
        engine = CategorizationEngine(merchantCategoryDao, merchantNormalizer)
    }

    @Test
    fun `normalize delegates to MerchantNormalizer`() {
        engine.normalize("starbucks")
        verify { merchantNormalizer.normalize("starbucks") }
    }

    @Test
    fun `exact match returns category`() = runBlocking {
        // Mock normalize to return expected string for this test case
        every { merchantNormalizer.normalize("starbucks") } returns "STARBUCKS"

        coEvery { merchantCategoryDao.getCategoryForMerchant("STARBUCKS") } returns
            MerchantCategory("STARBUCKS", 5L)

        val result = engine.categorize("starbucks")
        assertEquals(5L, result)
    }

    @Test
    fun `substring match finds pattern within merchant name`() = runBlocking {
        every { merchantNormalizer.normalize("UBER EATS DELIVERY 1234") } returns "UBER EATS DELIVERY 1234"

        coEvery { merchantCategoryDao.getCategoryForMerchant("UBER EATS DELIVERY 1234") } returns null
        coEvery { merchantCategoryDao.getAll() } returns listOf(
            MerchantCategory("UBER EATS", 3L),
            MerchantCategory("UBER", 4L)
        )

        val result = engine.categorize("UBER EATS DELIVERY 1234")
        // Should match "UBER EATS" first (longer pattern) via substring, returning 3L
        assertEquals(3L, result)
    }

    @Test
    fun `returns null when no match found`() = runBlocking {
        every { merchantNormalizer.normalize("COMPLETELY UNKNOWN MERCHANT") } returns "COMPLETELY UNKNOWN MERCHANT"

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
