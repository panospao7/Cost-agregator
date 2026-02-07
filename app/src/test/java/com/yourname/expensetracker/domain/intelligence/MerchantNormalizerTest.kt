package com.yourname.expensetracker.domain.intelligence

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.flow.flowOf

class MerchantNormalizerTest {
    private lateinit var normalizer: MerchantNormalizer

    @Before
    fun setup() {
        // We test the non-suspend, non-DAO methods.
        // For methods needing DAO, we'd need a mock. Here we test pure functions.
        // Create with a mock DAO for compile, but test only pure functions.
        normalizer = MerchantNormalizer(FakeUserCorrectionDao())
    }

    // === NORMALIZE (pure function, no DB) ===

    @Test
    fun `normalize uppercases`() {
        assertEquals("STARBUCKS", normalizer.normalize("starbucks"))
    }

    @Test
    fun `normalize removes trailing numbers`() {
        val result = normalizer.normalize("SKLAVENITIS #4532")
        assertFalse(result.contains("4532"))
    }

    @Test
    fun `normalize removes card info`() {
        val result = normalizer.normalize("MERCHANT CARD VISA *1234")
        assertFalse(result.contains("VISA"))
        assertFalse(result.contains("1234"))
    }

    @Test
    fun `normalize removes Greek city names`() {
        val result = normalizer.normalize("STARBUCKS ATHENS GR")
        assertFalse(result.contains("ATHENS"))
        assertFalse(result.contains("GR"))
    }

    @Test
    fun `normalize removes legal suffixes`() {
        val result = normalizer.normalize("COMPANY SA")
        assertFalse(result.endsWith("SA"))
    }

    @Test
    fun `normalize removes date patterns`() {
        val result = normalizer.normalize("MERCHANT 15/03/2024")
        assertFalse(result.contains("15/03"))
    }

    @Test
    fun `normalize collapses whitespace`() {
        val result = normalizer.normalize("MERCHANT   NAME")
        assertFalse(result.contains("  "))
    }

    @Test
    fun `normalize handles empty string`() {
        assertEquals("", normalizer.normalize(""))
    }

    @Test
    fun `normalize handles special characters only`() {
        val result = normalizer.normalize("***###!!!")
        assertEquals("", result)
    }

    // === SIMILARITY ===

    @Test
    fun `identical merchants have similarity 1`() {
        assertEquals(1.0f, normalizer.similarity("Starbucks", "Starbucks"), 0.01f)
    }

    @Test
    fun `case-insensitive similarity`() {
        assertEquals(1.0f, normalizer.similarity("starbucks", "STARBUCKS"), 0.01f)
    }

    @Test
    fun `substring containment gives high similarity`() {
        val sim = normalizer.similarity("UBER", "UBER EATS")
        assertTrue(sim >= 0.9f)
    }

    @Test
    fun `completely different merchants have low similarity`() {
        val sim = normalizer.similarity("Starbucks", "Vodafone")
        assertTrue(sim < 0.3f)
    }

    @Test
    fun `empty string similarity is 0`() {
        assertEquals(0f, normalizer.similarity("Starbucks", ""), 0.01f)
    }

    // === LEVENSHTEIN ===

    @Test
    fun `levenshtein distance of identical strings is 0`() {
        assertEquals(0, normalizer.levenshteinDistance("abc", "abc"))
    }

    @Test
    fun `levenshtein distance of single edit`() {
        assertEquals(1, normalizer.levenshteinDistance("abc", "abd"))
    }

    @Test
    fun `levenshtein similarity of identical is 1`() {
        assertEquals(1.0f, normalizer.levenshteinSimilarity("test", "test"), 0.01f)
    }

    @Test
    fun `levenshtein similarity of very different is low`() {
        val sim = normalizer.levenshteinSimilarity("abc", "xyz")
        assertTrue(sim < 0.5f)
    }

    // === FIND BEST MATCH ===

    @Test
    fun `findBestMatch returns exact match`() {
        val candidates = listOf("Starbucks", "Lidl", "Shell")
        val match = normalizer.findBestMatch("STARBUCKS", candidates)
        assertEquals("Starbucks", match)
    }

    @Test
    fun `findBestMatch returns null below threshold`() {
        val candidates = listOf("Starbucks", "Lidl", "Shell")
        val match = normalizer.findBestMatch("COMPLETELY DIFFERENT", candidates, threshold = 0.7f)
        assertNull(match)
    }
}

// Minimal fake for testing pure functions - you'd use Mockito/Mockk for real DAO mocking
private class FakeUserCorrectionDao : com.yourname.expensetracker.data.database.dao.UserCorrectionDao {
    override suspend fun insert(correction: com.yourname.expensetracker.data.database.entity.UserCorrection): Long = 0
    override fun getAllFlow() = flowOf(emptyList<com.yourname.expensetracker.data.database.entity.UserCorrection>())
    override suspend fun getAll() = emptyList<com.yourname.expensetracker.data.database.entity.UserCorrection>()
    override suspend fun getCount() = 0
    override suspend fun getByPackage(packageName: String) = emptyList<com.yourname.expensetracker.data.database.entity.UserCorrection>()
    override suspend fun getRejectionCount(packageName: String) = 0
    override suspend fun getTotalCorrections(packageName: String) = 0
    override suspend fun getMostCommonMerchantCorrection(originalMerchant: String): String? = null
    override suspend fun getMerchantTotalCorrections(merchant: String) = 0
    override suspend fun getMerchantRejectionCount(merchant: String) = 0
    override suspend fun getMostCommonCategoryForMerchant(merchant: String): Long? = null
    override suspend fun hasPreviousApprovals(merchant: String, packageName: String) = false
    override suspend fun deleteAll() {}
}
