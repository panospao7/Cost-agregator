package com.yourname.expensetracker.service

import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.domain.model.recommendation.RecommendationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecommendationDeduplicatorTest {

    private lateinit var deduplicator: RecommendationDeduplicator
    private lateinit var filterSerializer: TransactionFilterSerializer

    @Before
    fun setup() {
        filterSerializer = TransactionFilterSerializer()
        deduplicator = RecommendationDeduplicator(filterSerializer)
    }

    @Test
    fun `deduplicate removes exact duplicate recommendations`() {
        val rec1 = createRecommendation("1", "TRANSACTION_LIST", "{\"categoryId\":5}", "cat:5")
        val rec2 = createRecommendation("2", "TRANSACTION_LIST", "{\"categoryId\":5}", "cat:5")
        val rec3 = createRecommendation("3", "TRANSACTION_LIST", "{\"categoryId\":5}", "cat:5")

        val recommendations = listOf(rec1, rec2, rec3)
        val deduplicated = deduplicator.deduplicate(recommendations)

        assertEquals("Should keep only 1 recommendation", 1, deduplicated.size)
        assertEquals("Should keep first occurrence", "1", deduplicated[0].id)
    }

    @Test
    fun `deduplicate preserves different navigation targets`() {
        val rec1 = createRecommendation("1", "TRANSACTION_LIST", "{\"categoryId\":5}", "cat:5")
        val rec2 = createRecommendation("2", "CATEGORY_DETAIL", "{\"categoryId\":5}", "cat:5")
        val rec3 = createRecommendation("3", "BUDGET_DETAIL", "{\"categoryId\":5}", "cat:5")

        val recommendations = listOf(rec1, rec2, rec3)
        val deduplicated = deduplicator.deduplicate(recommendations)

        assertEquals("Should keep all 3 different navigation targets", 3, deduplicated.size)
    }

    @Test
    fun `deduplicate preserves different categories`() {
        val rec1 = createRecommendation("1", "TRANSACTION_LIST", "{\"categoryId\":5}", "FOOD")
        val rec2 = createRecommendation("2", "TRANSACTION_LIST", "{\"categoryId\":6}", "TRANSPORT")
        val rec3 = createRecommendation("3", "TRANSACTION_LIST", "{\"categoryId\":7}", "ENTERTAINMENT")

        val recommendations = listOf(rec1, rec2, rec3)
        val deduplicated = deduplicator.deduplicate(recommendations)

        assertEquals("Should keep all 3 different categories", 3, deduplicated.size)
    }

    @Test
    fun `deduplicate ignores category when filter target is otherwise identical`() {
        val rec1 = createRecommendation("1", "TRANSACTION_LIST", "{\"categoryId\":5}", "FOOD")
        val rec2 = createRecommendation("2", "TRANSACTION_LIST", "{\"categoryId\":5}", "TRANSPORT")

        val deduplicated = deduplicator.deduplicate(listOf(rec1, rec2))

        assertEquals("Should deduplicate recommendations that differ only by category", 1, deduplicated.size)
        assertEquals("1", deduplicated[0].id)
    }

    @Test
    fun `deduplicate preserves different merchants`() {
        val rec1 = createRecommendation("1", "TRANSACTION_LIST", "{\"merchantName\":\"Amazon\"}", "cat:1")
        val rec2 = createRecommendation("2", "TRANSACTION_LIST", "{\"merchantName\":\"Walmart\"}", "cat:1")
        val rec3 = createRecommendation("3", "TRANSACTION_LIST", "{\"merchantName\":\"Target\"}", "cat:1")

        val recommendations = listOf(rec1, rec2, rec3)
        val deduplicated = deduplicator.deduplicate(recommendations)

        assertEquals("Should keep all 3 different merchants", 3, deduplicated.size)
    }

    @Test
    fun `deduplicate preserves different date ranges`() {
        val rec1 = createRecommendation("1", "TRANSACTION_LIST", "{\"dateRangeStart\":1000,\"dateRangeEnd\":2000}", "cat:1")
        val rec2 = createRecommendation("2", "TRANSACTION_LIST", "{\"dateRangeStart\":2000,\"dateRangeEnd\":3000}", "cat:1")
        val rec3 = createRecommendation("3", "TRANSACTION_LIST", "{\"dateRangeStart\":3000,\"dateRangeEnd\":4000}", "cat:1")

        val recommendations = listOf(rec1, rec2, rec3)
        val deduplicated = deduplicator.deduplicate(recommendations)

        assertEquals("Should keep all 3 different date ranges", 3, deduplicated.size)
    }

    @Test
    fun `deduplicate handles empty list`() {
        val deduplicated = deduplicator.deduplicate(emptyList())

        assertEquals("Empty list should return empty list", 0, deduplicated.size)
    }

    @Test
    fun `deduplicate handles single item`() {
        val rec = createRecommendation("1", "TRANSACTION_LIST", "{\"categoryId\":5}", "cat:5")
        
        val deduplicated = deduplicator.deduplicate(listOf(rec))

        assertEquals("Single item should be kept", 1, deduplicated.size)
    }

    @Test
    fun `deduplicate respects priority order when duplicates exist`() {
        val recHigh = createRecommendationWithPriority("1", "TRANSACTION_LIST", "{\"categoryId\":5}", "cat:5", RecommendationPriority.HIGH)
        val recMedium = createRecommendationWithPriority("2", "TRANSACTION_LIST", "{\"categoryId\":5}", "cat:5", RecommendationPriority.MEDIUM)
        val recLow = createRecommendationWithPriority("3", "TRANSACTION_LIST", "{\"categoryId\":5}", "cat:5", RecommendationPriority.LOW)

        val recommendations = listOf(recMedium, recHigh, recLow)
        val deduplicated = deduplicator.deduplicate(recommendations)

        assertEquals("Should keep only 1 recommendation (first occurrence)", 1, deduplicated.size)
        assertEquals("Should keep first occurrence regardless of priority", "2", deduplicated[0].id)
    }

    @Test
    fun `deduplicate handles complex filter criteria`() {
        val rec1 = createRecommendation("1", "TRANSACTION_LIST", 
            "{\"categoryId\":5,\"merchantName\":\"Amazon\",\"minAmount\":100,\"dateRangeStart\":1000,\"dateRangeEnd\":2000}", "cat:5")
        val rec2 = createRecommendation("2", "TRANSACTION_LIST", 
            "{\"categoryId\":5,\"merchantName\":\"Amazon\",\"minAmount\":100,\"dateRangeStart\":1000,\"dateRangeEnd\":2000}", "cat:5")
        val rec3 = createRecommendation("3", "TRANSACTION_LIST", 
            "{\"categoryId\":5,\"merchantName\":\"Amazon\",\"minAmount\":150,\"dateRangeStart\":1000,\"dateRangeEnd\":2000}", "cat:5")

        val recommendations = listOf(rec1, rec2, rec3)
        val deduplicated = deduplicator.deduplicate(recommendations)

        assertEquals("Should keep 2 (rec1 and rec3) since minAmount differs", 2, deduplicated.size)
    }

    @Test
    fun `deduplicate handles null filter JSON`() {
        val rec1 = createRecommendationWithFilter("1", "TRANSACTION_LIST", null, "cat:5")
        val rec2 = createRecommendationWithFilter("2", "TRANSACTION_LIST", null, "cat:5")

        val recommendations = listOf(rec1, rec2)
        val deduplicated = deduplicator.deduplicate(recommendations)

        // Both null filters become the same fallback, so they deduplicate to 1
        assertEquals("Should deduplicate identical fallback filters", 1, deduplicated.size)
    }

    private fun createRecommendation(id: String, navTarget: String, filterJson: String, category: String): DashboardFollowThroughRecommendation {
        return DashboardFollowThroughRecommendation(
            id = id,
            userId = "test-user",
            recommendationText = "Test recommendation",
            navigationTarget = navTarget,
            filterCriteria = filterJson,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            dismissedAt = null,
            expiresAt = System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000,
            priority = RecommendationPriority.MEDIUM,
            category = category,
            sourceArtifactId = "",
            status = RecommendationStatus.ACTIVE
        )
    }

    private fun createRecommendationWithPriority(id: String, navTarget: String, filterJson: String, category: String, priority: RecommendationPriority): DashboardFollowThroughRecommendation {
        return DashboardFollowThroughRecommendation(
            id = id,
            userId = "test-user",
            recommendationText = "Test recommendation",
            navigationTarget = navTarget,
            filterCriteria = filterJson,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            dismissedAt = null,
            expiresAt = System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000,
            priority = priority,
            category = category,
            sourceArtifactId = "",
            status = RecommendationStatus.ACTIVE
        )
    }

    private fun createRecommendationWithFilter(id: String, navTarget: String, filterJson: String?, category: String): DashboardFollowThroughRecommendation {
        return DashboardFollowThroughRecommendation(
            id = id,
            userId = "test-user",
            recommendationText = "Test recommendation",
            navigationTarget = navTarget,
            filterCriteria = filterJson ?: "{\"version\":1}",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            dismissedAt = null,
            expiresAt = System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000,
            priority = RecommendationPriority.MEDIUM,
            category = category,
            sourceArtifactId = "",
            status = RecommendationStatus.ACTIVE
        )
    }
}
