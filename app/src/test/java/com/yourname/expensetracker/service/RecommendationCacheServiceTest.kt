package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.domain.model.recommendation.RecommendationStatus
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for RecommendationCacheService.
 * Tests LRU cache behavior, TTL, and eviction policies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationCacheServiceTest {

    private lateinit var repository: RecommendationRepository
    private lateinit var cacheService: RecommendationCacheService
    private val testDispatcher = StandardTestDispatcher()
    private val timeProvider = FakeTimeProvider(1_775_001_600_000L)
    private var nextRecommendationId = 0

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        cacheService = RecommendationCacheService(repository, timeProvider, ioDispatcher = testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `getById returns cached item when present and not expired`() = runTest {
        val recommendation = createRecommendation(id = "rec1")

        // Put in cache
        cacheService.put(recommendation)

        // Get from cache (should not hit repository)
        val result = cacheService.getById("rec1")

        assertNotNull(result)
        assertEquals("rec1", result.id)
        coVerify(exactly = 0) { repository.getById(any()) }
    }

    @Test
    fun `getById fetches from repository on cache miss and caches the result`() = runTest {
        val recommendation = createRecommendation(id = "rec1")

        coEvery { repository.getById("rec1") } returns recommendation

        // First call - cache miss, must hit the repository
        val first = cacheService.getById("rec1")
        assertNotNull(first)
        assertEquals("rec1", first.id)
        coVerify(exactly = 1) { repository.getById("rec1") }

        // Second call - must be served from cache
        val second = cacheService.getById("rec1")
        assertNotNull(second)
        assertEquals("rec1", second.id)
        coVerify(exactly = 1) { repository.getById("rec1") }
    }

    @Test
    fun `getById removes expired entry from cache and fetches fresh`() = runTest {
        val nowMillis = timeProvider.now()
        val expiredTime = nowMillis - (8L * 24 * 60 * 60 * 1000) // 8 days ago
        val expiredRec = createRecommendation(
            id = "rec1",
            expiresAt = expiredTime
        )

        // Put expired recommendation in cache
        cacheService.put(expiredRec)

        // Try to get it - should fetch from repository
        val freshRec = createRecommendation(id = "rec1")
        coEvery { repository.getById("rec1") } returns freshRec

        val result = cacheService.getById("rec1")

        assertNotNull(result)
        assertEquals(freshRec.expiresAt, result.expiresAt)
        coVerify(exactly = 1) { repository.getById("rec1") }
    }

    @Test
    fun `getById checks TTL expiration (7 days)`() = runTest {
        val ttlMillis = 7L * 24 * 60 * 60 * 1000
        // Keep the recommendation itself active beyond the cache TTL so that
        // cache age, rather than recommendation expiry, is the reason for eviction.
        val recommendation = createRecommendation(
            id = "rec1",
            expiresAt = timeProvider.now() + 2 * ttlMillis
        )
        cacheService.put(recommendation)
        val refreshed = recommendation.copy(recommendationText = "Refreshed after cache TTL")
        coEvery { repository.getById("rec1") } returns refreshed

        timeProvider.advanceTime(ttlMillis - 1L)
        assertEquals(recommendation, cacheService.getById("rec1"))
        coVerify(exactly = 0) { repository.getById("rec1") }

        timeProvider.advanceTime(2L)
        assertEquals(refreshed, cacheService.getById("rec1"))
        assertEquals(refreshed, cacheService.getById("rec1"))
        coVerify(exactly = 1) { repository.getById("rec1") }
    }

    @Test
    fun `putAll adds multiple recommendations to cache`() = runTest {
        val recs = listOf(
            createRecommendation(id = "rec1"),
            createRecommendation(id = "rec2"),
            createRecommendation(id = "rec3")
        )

        cacheService.putAll(recs)

        // Verify all are cached
        val result1 = cacheService.getById("rec1")
        val result2 = cacheService.getById("rec2")
        val result3 = cacheService.getById("rec3")

        assertNotNull(result1)
        assertNotNull(result2)
        assertNotNull(result3)
        coVerify(exactly = 0) { repository.getById(any()) }
    }

    @Test
    fun `remove deletes recommendation from cache`() = runTest {
        val recommendation = createRecommendation(id = "rec1")

        cacheService.put(recommendation)
        cacheService.remove("rec1")

        // Should not be in cache anymore
        coEvery { repository.getById("rec1") } returns recommendation

        cacheService.getById("rec1")

        // Should have fetched from repository
        coVerify(exactly = 1) { repository.getById("rec1") }
    }

    @Test
    fun `clear removes all entries from cache`() = runTest {
        val recs = listOf(
            createRecommendation(id = "rec1"),
            createRecommendation(id = "rec2"),
            createRecommendation(id = "rec3")
        )

        cacheService.putAll(recs)
        cacheService.clear()

        // All should be gone from cache
        coEvery { repository.getById(any()) } returns null

        val result1 = cacheService.getById("rec1")
        val result2 = cacheService.getById("rec2")
        val result3 = cacheService.getById("rec3")

        assertNull(result1)
        assertNull(result2)
        assertNull(result3)
        coVerify(exactly = 3) { repository.getById(any()) }
    }

    @Test
    fun `clearForUser removes only specific user's recommendations`() = runTest {
        val user1Rec = createRecommendation(id = "rec1", userId = "user1")
        val user2Rec = createRecommendation(id = "rec2", userId = "user2")

        cacheService.putAll(listOf(user1Rec, user2Rec))

        cacheService.clearForUser("user1")

        // user1's recommendation should be gone
        coEvery { repository.getById("rec1") } returns user1Rec
        val result1 = cacheService.getById("rec1")
        assertNotNull(result1)
        coVerify(exactly = 1) { repository.getById("rec1") }

        // user2's recommendation should still be cached
        val result2 = cacheService.getById("rec2")
        assertNotNull(result2)
        coVerify(exactly = 0) { repository.getById("rec2") }
    }

    @Test
    fun `evictExpired removes only expired recommendations`() = runTest {
        val nowMillis = timeProvider.now()
        val expiredTime = nowMillis - (8L * 24 * 60 * 60 * 1000)
        val futureTime = nowMillis + (24 * 60 * 60 * 1000)

        val expiredRec = createRecommendation(id = "rec1", expiresAt = expiredTime)
        val activeRec = createRecommendation(id = "rec2", expiresAt = futureTime)

        cacheService.putAll(listOf(expiredRec, activeRec))

        cacheService.evictExpired()

        // Active should still be cached
        val result2 = cacheService.getById("rec2")
        assertNotNull(result2)
        coVerify(exactly = 0) { repository.getById("rec2") }

        // Expired should be gone
        coEvery { repository.getById("rec1") } returns expiredRec
        cacheService.getById("rec1")
        coVerify(exactly = 1) { repository.getById("rec1") }
    }

    @Test
    fun `LRU eviction when cache exceeds 50 items`() = runTest {
        // Create 51 recommendations (cache limit is 50)
        val recommendations = (1..51).map { i ->
            createRecommendation(id = "rec$i")
        }

        // Put all in cache
        cacheService.putAll(recommendations)

        // Get stats to verify size
        val stats = cacheService.getStats()
        assertEquals(50, stats.maxSize)
        assertEquals(50, stats.size) // Should have evicted 1 item
    }

    @Test
    fun `LRU evicts least recently used item`() = runTest {
        // Fill cache to capacity (50 items)
        val recommendations = (1..50).map { i ->
            createRecommendation(id = "rec$i")
        }
        cacheService.putAll(recommendations)

        // Access rec1 to make it recently used
        cacheService.getById("rec1")

        // Add a new item (should evict least recently used, not rec1)
        val newRec = createRecommendation(id = "rec51")
        cacheService.put(newRec)

        // rec1 should still be in cache
        val result1 = cacheService.getById("rec1")
        assertNotNull(result1)
        coVerify(exactly = 0) { repository.getById("rec1") }
    }

    @Test
    fun `getStats returns accurate cache statistics for putAll and for individual puts`() = runTest {
        // Rows: bulk insert via putAll, and 10 sequential puts (single-writer stand-in for
        // concurrent access; no real concurrency is exercised here).
        for (name in listOf("putAll", "sequential put")) {
            setup() // fresh cache per row
            val recs = (1..10).map { i -> createRecommendation(id = "rec$i") }
            if (name == "putAll") {
                cacheService.putAll(recs)
            } else {
                recs.forEach { cacheService.put(it) }
            }

            val stats = cacheService.getStats()

            assertEquals(10, stats.size, "row '$name': size")
            assertEquals(50, stats.maxSize, "row '$name': maxSize")
        }
    }

    @Test
    fun `getById returns null when repository returns null`() = runTest {
        coEvery { repository.getById("nonexistent") } returns null

        val result = cacheService.getById("nonexistent")

        assertNull(result)
    }

    @Test
    fun `getById does not cache inactive recommendations`() = runTest {
        val expiredRec = createRecommendation(
            id = "rec1",
            status = RecommendationStatus.EXPIRED
        )

        coEvery { repository.getById("rec1") } returns expiredRec

        // First call
        cacheService.getById("rec1")

        // Second call - should hit repository again because inactive items aren't cached
        cacheService.getById("rec1")

        coVerify(exactly = 2) { repository.getById("rec1") }
    }

    @Test
    fun `put overwrites existing cache entry`() = runTest {
        val originalRec = createRecommendation(
            id = "rec1",
            recommendationText = "Original text"
        )
        val updatedRec = createRecommendation(
            id = "rec1",
            recommendationText = "Updated text"
        )

        cacheService.put(originalRec)
        cacheService.put(updatedRec)

        val result = cacheService.getById("rec1")

        assertNotNull(result)
        assertEquals("Updated text", result.recommendationText)
        coVerify(exactly = 0) { repository.getById(any()) }
    }

    // ========== RecommendationDeduplicator (merged from RecommendationDeduplicatorTest) ==========

    private class DedupInput(
        val id: String,
        val navTarget: String,
        val filterJson: String,
        val category: String,
        val priority: RecommendationPriority = RecommendationPriority.MEDIUM
    )

    private class DedupRow(val name: String, val inputs: List<DedupInput>, val expectedIds: List<String>)

    private fun dedupRec(input: DedupInput): DashboardFollowThroughRecommendation =
        createRecommendation(id = input.id).copy(
            userId = "test-user",
            navigationTarget = input.navTarget,
            filterCriteria = input.filterJson,
            category = input.category,
            priority = input.priority
        )

    @Test
    fun `deduplicate keeps first occurrence per logical filter target and preserves distinct targets`() {
        val deduplicator = RecommendationDeduplicator(TransactionFilterSerializer())
        val list = "TRANSACTION_LIST"
        val cat5 = "{\"categoryId\":5}"
        val day = "{\"dateRangeStart\":0,\"dateRangeEnd\":86400000}"
        val complexBase = "{\"categoryId\":5,\"merchantName\":\"Amazon\",\"minAmount\":%d,\"dateRangeStart\":1000,\"dateRangeEnd\":2000}"

        val rows = listOf(
            DedupRow("removes exact duplicates, keeps first occurrence",
                listOf(DedupInput("1", list, cat5, "cat:5"), DedupInput("2", list, cat5, "cat:5"), DedupInput("3", list, cat5, "cat:5")),
                listOf("1")),
            DedupRow("preserves different navigation targets",
                listOf(
                    DedupInput("1", list, cat5, "cat:5"),
                    DedupInput("2", "CATEGORY_DETAIL", cat5, "cat:5"),
                    DedupInput("3", "BUDGET_DETAIL", cat5, "cat:5")
                ),
                listOf("1", "2", "3")),
            DedupRow("preserves different categories",
                listOf(
                    DedupInput("1", list, "{\"categoryId\":5}", "FOOD"),
                    DedupInput("2", list, "{\"categoryId\":6}", "TRANSPORT"),
                    DedupInput("3", list, "{\"categoryId\":7}", "ENTERTAINMENT")
                ),
                listOf("1", "2", "3")),
            DedupRow("ignores category label when filter target is otherwise identical",
                listOf(DedupInput("1", list, cat5, "FOOD"), DedupInput("2", list, cat5, "TRANSPORT")),
                listOf("1")),
            DedupRow("preserves different merchants",
                listOf(
                    DedupInput("1", list, "{\"merchantName\":\"Amazon\"}", "cat:1"),
                    DedupInput("2", list, "{\"merchantName\":\"Walmart\"}", "cat:1"),
                    DedupInput("3", list, "{\"merchantName\":\"Target\"}", "cat:1")
                ),
                listOf("1", "2", "3")),
            // AIML-21 identifies logical spans, not generation timestamps: 1 day, 7 days, 31 days stay distinct.
            DedupRow("preserves different semantic date ranges",
                listOf(
                    DedupInput("1", list, day, "cat:1"),
                    DedupInput("2", list, "{\"dateRangeStart\":0,\"dateRangeEnd\":604800000}", "cat:1"),
                    DedupInput("3", list, "{\"dateRangeStart\":0,\"dateRangeEnd\":2678400000}", "cat:1")
                ),
                listOf("1", "2", "3")),
            DedupRow("ignores generation timestamp shifts within a semantic span",
                listOf(
                    DedupInput("1", list, day, "cat:1"),
                    DedupInput("2", list, "{\"dateRangeStart\":1000,\"dateRangeEnd\":86401000}", "cat:1"),
                    DedupInput("3", list, "{\"dateRangeStart\":2000,\"dateRangeEnd\":86402000}", "cat:1")
                ),
                listOf("1")),
            DedupRow("empty list stays empty", emptyList(), emptyList()),
            DedupRow("single item is kept", listOf(DedupInput("1", list, cat5, "cat:5")), listOf("1")),
            DedupRow("keeps first occurrence regardless of priority order",
                listOf(
                    DedupInput("2", list, cat5, "cat:5", RecommendationPriority.MEDIUM),
                    DedupInput("1", list, cat5, "cat:5", RecommendationPriority.HIGH),
                    DedupInput("3", list, cat5, "cat:5", RecommendationPriority.LOW)
                ),
                listOf("2")),
            DedupRow("complex filter: only the identical one collapses, differing minAmount survives",
                listOf(
                    DedupInput("1", list, complexBase.format(100), "cat:5"),
                    DedupInput("2", list, complexBase.format(100), "cat:5"),
                    DedupInput("3", list, complexBase.format(150), "cat:5")
                ),
                listOf("1", "3")),
            // Both fall back to the same default filter, so they collapse to the first.
            DedupRow("identical fallback filters (version-only) collapse",
                listOf(
                    DedupInput("1", list, "{\"version\":1}", "cat:5"),
                    DedupInput("2", list, "{\"version\":1}", "cat:5")
                ),
                listOf("1"))
        )
        assertEquals(12, rows.size, "dedup table size")

        for (row in rows) {
            val actual = deduplicator.deduplicate(row.inputs.map { dedupRec(it) }).map { it.id }
            assertEquals(row.expectedIds, actual, "row '${row.name}'")
        }
    }

    // Helper function
    private fun createRecommendation(
        id: String = "rec_${++nextRecommendationId}",
        userId: String = "user123",
        recommendationText: String = "Test recommendation",
        status: RecommendationStatus = RecommendationStatus.ACTIVE,
        expiresAt: Long = timeProvider.now() + (7L * 24 * 60 * 60 * 1000)
    ): DashboardFollowThroughRecommendation {
        return DashboardFollowThroughRecommendation(
            id = id,
            userId = userId,
            recommendationText = recommendationText,
            navigationTarget = "TRANSACTION_LIST",
            filterCriteria = "{}",
            priority = RecommendationPriority.MEDIUM,
            category = "GENERAL",
            sourceArtifactId = "",
            status = status,
            expiresAt = expiresAt,
            createdAt = 0L,
            updatedAt = 0L,
        )
    }
}
