package com.yourname.expensetracker.ui.components.ai

import com.yourname.expensetracker.domain.ai.model.CategorizedReceiptItem
import com.yourname.expensetracker.domain.ai.model.CategorySuggestion
import com.yourname.expensetracker.domain.ai.model.ReceiptItemPlaceholder
import com.yourname.expensetracker.domain.dto.ReceiptItemCategorizationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The single placeholder definition and the breakdown card helpers built on it. */
class ReceiptItemPlaceholderPredicateTest {

    private fun item(
        category: CategorySuggestion? = null,
        confidence: Float = 0f,
        rationale: String = "",
        alternatives: List<CategorySuggestion> = emptyList()
    ) = CategorizedReceiptItem(
        itemDescription = "milk",
        amount = 1.0,
        suggestedCategory = category,
        confidence = confidence,
        rationale = rationale,
        alternatives = alternatives,
        needsReview = true
    )

    private val food = CategorySuggestion(categoryId = 1L, categoryName = "Food", confidence = 0f)

    private fun snapshot(
        confidence: Float = 0f,
        categoryId: Long? = null,
        categoryName: String? = null,
        rationale: String? = null,
        alternativesJson: String? = null
    ) = ReceiptItemCategorizationSnapshot(
        id = 1L, receiptId = 1L, expenseId = null, itemDescription = "milk", itemAmount = 1.0,
        suggestedCategoryId = categoryId, suggestedCategoryName = categoryName, confidence = confidence,
        aiRationale = rationale, alternativeCategoriesJson = alternativesJson, userCorrectedCategoryId = null,
        userCorrectedCategoryName = null, userCorrectedAt = null, taxAmount = null,
        isNewCategorySuggestion = false, createdAt = 0L, updatedAt = 0L
    )

    // ---- 12. predicate

    @Test
    fun `placeholder rationale constant is blank`() {
        assertEquals("", ReceiptItemPlaceholder.PLACEHOLDER_RATIONALE)
    }

    @Test
    fun `item is a placeholder only with no category, confidence 0 and blank rationale`() {
        assertTrue(ReceiptItemPlaceholder.isPlaceholder(item()))
        assertTrue(ReceiptItemPlaceholder.isPlaceholder(item(rationale = "   ")))
    }

    @Test
    fun `a real result with a category and confidence 0 is not a placeholder`() {
        assertFalse(ReceiptItemPlaceholder.isPlaceholder(item(category = food, confidence = 0f)))
    }

    @Test
    fun `each missing placeholder condition makes the item real`() {
        assertFalse(ReceiptItemPlaceholder.isPlaceholder(item(confidence = 0.1f)))
        assertFalse(ReceiptItemPlaceholder.isPlaceholder(item(rationale = "because")))
        assertFalse(ReceiptItemPlaceholder.isPlaceholder(item(alternatives = listOf(food))))
    }

    @Test
    fun `snapshot placeholder needs no category id or name, confidence 0, blank rationale and no alternatives`() {
        assertTrue(ReceiptItemPlaceholder.isPlaceholder(snapshot()))
        assertTrue(ReceiptItemPlaceholder.isPlaceholder(snapshot(categoryName = "  ", rationale = " ", alternativesJson = "[]")))
        assertTrue(ReceiptItemPlaceholder.isPlaceholder(snapshot(alternativesJson = "  ")))
    }

    @Test
    fun `snapshot with a category and confidence 0 is not a placeholder`() {
        assertFalse(ReceiptItemPlaceholder.isPlaceholder(snapshot(categoryName = "Food")))
        assertFalse(ReceiptItemPlaceholder.isPlaceholder(snapshot(categoryId = 5L)))
    }

    @Test
    fun `snapshot with confidence, rationale or alternatives is not a placeholder`() {
        assertFalse(ReceiptItemPlaceholder.isPlaceholder(snapshot(confidence = 0.2f)))
        assertFalse(ReceiptItemPlaceholder.isPlaceholder(snapshot(rationale = "because")))
        assertFalse(ReceiptItemPlaceholder.isPlaceholder(snapshot(alternativesJson = "[{\"id\":1}]")))
    }

    // ---- card badge: ReceiptItemBreakdownCard shows the confidence badge only when
    // !isUserCorrected && !ReceiptItemPlaceholder.isPlaceholder(item) on the stored snapshot.

    @Test
    fun `card badge predicate - a stored placeholder row is a placeholder so the badge is hidden`() {
        val placeholderRow = snapshot(confidence = 0f, categoryId = null, categoryName = null, rationale = "", alternativesJson = null)
        assertTrue(ReceiptItemPlaceholder.isPlaceholder(placeholderRow))
    }

    @Test
    fun `card badge predicate - a real stored row with confidence 0 and a category is not a placeholder so the badge is shown`() {
        val realRow = snapshot(confidence = 0f, categoryId = 3L, categoryName = "Food", rationale = "", alternativesJson = null)
        assertFalse(ReceiptItemPlaceholder.isPlaceholder(realRow))
    }

    // ---- isRetryable: an untouched stored placeholder only

    private fun retryCandidate(
        correctedId: Long? = null,
        correctedName: String? = null,
        correctedAt: Long? = null,
        categoryName: String? = null,
        confidence: Float = 0f
    ) = snapshot(confidence = confidence, categoryName = categoryName).copy(
        userCorrectedCategoryId = correctedId,
        userCorrectedCategoryName = correctedName,
        userCorrectedAt = correctedAt
    )

    @Test
    fun `isRetryable is true for an untouched placeholder`() {
        assertTrue(ReceiptItemPlaceholder.isRetryable(retryCandidate()))
        assertTrue(ReceiptItemPlaceholder.isRetryable(retryCandidate(correctedName = "   ")))
    }

    @Test
    fun `isRetryable is false for a placeholder the user corrected`() {
        assertFalse(ReceiptItemPlaceholder.isRetryable(retryCandidate(correctedId = 4L)))
        assertFalse(ReceiptItemPlaceholder.isRetryable(retryCandidate(correctedName = "Mine")))
        assertFalse(ReceiptItemPlaceholder.isRetryable(retryCandidate(correctedAt = 10L)))
    }

    @Test
    fun `isRetryable is false for a real row`() {
        assertFalse(ReceiptItemPlaceholder.isRetryable(retryCandidate(categoryName = "Food")))
        assertFalse(ReceiptItemPlaceholder.isRetryable(retryCandidate(confidence = 0.3f)))
    }

    // ---- hasAlternativesJson truth table

    @Test
    fun `hasAlternativesJson is false for null, blank and an empty array`() {
        assertFalse(ReceiptItemPlaceholder.hasAlternativesJson(null))
        assertFalse(ReceiptItemPlaceholder.hasAlternativesJson(""))
        assertFalse(ReceiptItemPlaceholder.hasAlternativesJson("   "))
        assertFalse(ReceiptItemPlaceholder.hasAlternativesJson("[]"))
        assertFalse(ReceiptItemPlaceholder.hasAlternativesJson("  []  "))
    }

    @Test
    fun `hasAlternativesJson is true for a non empty array`() {
        assertTrue(ReceiptItemPlaceholder.hasAlternativesJson("""[{"id":1,"name":"Food","confidence":0.4}]"""))
        assertTrue(ReceiptItemPlaceholder.hasAlternativesJson(" [{\"id\":1}] "))
    }

    // ---- 13. UI helpers

    @Test
    fun `average confidence excludes placeholders`() {
        val average = averageAiConfidence(
            listOf(
                snapshot(confidence = 0.9f, categoryName = "Food", rationale = "a"),
                snapshot(confidence = 0.5f, categoryName = "Food", rationale = "b"),
                snapshot(),
                snapshot()
            )
        )
        assertNotNull(average)
        assertEquals(0.7f, average!!, 1e-6f)
    }

    @Test
    fun `average confidence counts a real item with confidence 0`() {
        val average = averageAiConfidence(
            listOf(
                snapshot(confidence = 0.8f, categoryName = "Food"),
                snapshot(confidence = 0f, categoryName = "Food")
            )
        )
        assertEquals(0.4f, average!!, 1e-6f)
    }

    @Test
    fun `badge is hidden when only placeholders remain or there are no items`() {
        assertNull(averageAiConfidence(listOf(snapshot(), snapshot())))
        assertNull(averageAiConfidence(emptyList()))
    }

    @Test
    fun `info button needs a non-blank rationale`() {
        assertFalse(shouldShowRationaleInfo(null))
        assertFalse(shouldShowRationaleInfo(""))
        assertFalse(shouldShowRationaleInfo("   "))
        assertTrue(shouldShowRationaleInfo("matches the store"))
    }
}
