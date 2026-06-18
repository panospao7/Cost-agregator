package com.yourname.expensetracker.domain.usecase.expense

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.categorization.CategorizationResult
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CategorizeExpenseUseCaseTest : AnalyticsEngineTestBase() {

    private lateinit var categorizationEngine: CategorizationEngine
    private lateinit var merchantNormalizer: MerchantNormalizer
    private lateinit var useCase: CategorizeExpenseUseCase

    @Before
    fun initUseCase() {
        categorizationEngine = mockk(relaxed = true)
        merchantNormalizer = mockk(relaxed = true)
        useCase = CategorizeExpenseUseCase(categorizationEngine, merchantNormalizer)
    }

    @Test
    fun `expense categorized by engine`() = runTest {
        coEvery { merchantNormalizer.normalize("Starbucks") } returns merchantLookup("Starbucks")
        coEvery { categorizationEngine.categorize("Starbucks") } returns CategorizationResult(
            categoryId = 3L,
            categoryName = "Food & Dining",
            confidence = 0.92,
            matchType = com.yourname.expensetracker.domain.categorization.MatchType.EXACT,
            explanation = "Exact match"
        )

        val result = useCase("Starbucks")

        assertEquals("Starbucks", result.merchantName)
        assertEquals(3L, result.categoryId)
        assertApproxEquals(0.92f, result.confidence, 0.0001f)
        assertEquals("EXACT", result.matchType)
    }

    @Test
    fun `merchant normalized before categorization`() = runTest {
        coEvery { merchantNormalizer.normalize("  STARBUCKS #123 ") } returns merchantLookup("Starbucks")
        coEvery { categorizationEngine.categorize("Starbucks") } returns CategorizationResult(
            categoryId = 2L,
            categoryName = "Groceries",
            confidence = 0.80,
            matchType = com.yourname.expensetracker.domain.categorization.MatchType.CANONICAL,
            explanation = "Canonical match"
        )

        val result = useCase("  STARBUCKS #123 ")

        assertEquals("Starbucks", result.merchantName)
        coVerify(exactly = 1) { categorizationEngine.categorize("Starbucks") }
    }

    @Test
    fun `engine failure unknown category assigned`() = runTest {
        coEvery { merchantNormalizer.normalize("Unknown Merchant") } returns merchantLookup("Unknown Merchant")
        coEvery { categorizationEngine.categorize("Unknown Merchant") } returns CategorizationResult(
            categoryId = null,
            categoryName = null,
            confidence = 0.0,
            matchType = com.yourname.expensetracker.domain.categorization.MatchType.UNKNOWN,
            explanation = "No match found"
        )

        val result = useCase("Unknown Merchant")

        assertNull(result.categoryId)
        assertApproxEquals(0.0f, result.confidence, 0.0001f)
        assertEquals("UNKNOWN", result.matchType)
    }

    @Test
    fun `empty expense handled gracefully`() = runTest {
        coEvery { merchantNormalizer.normalize("") } returns merchantLookup("Unknown")
        coEvery { categorizationEngine.categorize("Unknown") } returns CategorizationResult(
            categoryId = null,
            categoryName = null,
            confidence = 0.0,
            matchType = com.yourname.expensetracker.domain.categorization.MatchType.UNKNOWN,
            explanation = "No match found"
        )

        val result = useCase("")

        assertEquals("Unknown", result.merchantName)
        assertNull(result.categoryId)
        assertApproxEquals(0.0f, result.confidence, 0.0001f)
    }

    private fun merchantLookup(normalized: String): MerchantLookupResult {
        return MerchantLookupResult(
            canonical = MerchantCanonical(normalizedName = normalized, searchKey = normalized.lowercase()),
            alias = null,
            confidence = 1.0f,
            matchType = MatchType.EXACT_MATCH
        )
    }
}
