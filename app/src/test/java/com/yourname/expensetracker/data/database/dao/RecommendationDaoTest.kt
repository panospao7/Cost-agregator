package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.RecommendationEntity
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.domain.model.recommendation.RecommendationStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for RecommendationDao.
 * Tests database operations for follow-through recommendations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecommendationDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: RecommendationDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        
        dao = database.recommendationDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `getActiveByUser returns only active non-archived non-expired recommendations`() = runTest {
        val userId = "user123"
        val nowMillis = System.currentTimeMillis()
        val futureMillis = nowMillis + (24 * 60 * 60 * 1000) // 1 day from now
        val pastMillis = nowMillis - (1 * 60 * 60 * 1000) // 1 hour ago

        // Insert active, non-dismissed, non-expired recommendation
        val activeRec = createRecommendation(
            userId = userId,
            status = RecommendationStatus.ACTIVE,
            dismissedAt = null,
            expiresAt = futureMillis
        )
        dao.insert(activeRec)

        // Insert archived recommendation
        val archivedRec = createRecommendation(
            userId = userId,
            status = RecommendationStatus.ARCHIVED,
            dismissedAt = nowMillis,
            expiresAt = futureMillis
        )
        dao.insert(archivedRec)

        // Insert expired recommendation
        val expiredRec = createRecommendation(
            userId = userId,
            status = RecommendationStatus.ACTIVE,
            dismissedAt = null,
            expiresAt = pastMillis
        )
        dao.insert(expiredRec)

        // Insert dismissed recommendation
        val dismissedRec = createRecommendation(
            userId = userId,
            status = RecommendationStatus.ACTIVE,
            dismissedAt = nowMillis,
            expiresAt = futureMillis
        )
        dao.insert(dismissedRec)

        // Fetch active recommendations
        val results = dao.getActiveByUser(userId, nowMillis)

        // Should only return the active, non-dismissed, non-expired recommendation
        assertEquals(1, results.size)
        assertEquals(activeRec.id, results[0].id)
    }

    @Test
    fun `insert adds record successfully`() = runTest {
        val userId = "user123"
        val recommendation = createRecommendation(userId = userId)

        dao.insert(recommendation)

        val result = dao.getById(recommendation.id)
        assertNotNull(result)
        assertEquals(recommendation.id, result.id)
        assertEquals(recommendation.recommendationText, result.recommendationText)
        assertEquals(recommendation.userId, result.userId)
    }

    @Test
    fun `archive sets dismissedAt and status to ARCHIVED`() = runTest {
        val userId = "user123"
        val recommendation = createRecommendation(
            userId = userId,
            status = RecommendationStatus.ACTIVE
        )
        dao.insert(recommendation)

        val archiveTime = System.currentTimeMillis()
        dao.archive(recommendation.id, archiveTime)

        val result = dao.getById(recommendation.id)
        assertNotNull(result)
        assertEquals(RecommendationStatus.ARCHIVED, result.status)
        assertEquals(archiveTime, result.dismissedAt)
        assertEquals(archiveTime, result.updatedAt)
    }

    @Test
    fun `expireOld marks old records as EXPIRED`() = runTest {
        val userId = "user123"
        val nowMillis = System.currentTimeMillis()
        val oldExpiry = nowMillis - (8 * 24 * 60 * 60 * 1000L) // 8 days ago
        val futureExpiry = nowMillis + (24 * 60 * 60 * 1000) // 1 day from now

        // Insert old recommendation
        val oldRec = createRecommendation(
            userId = userId,
            status = RecommendationStatus.ACTIVE,
            expiresAt = oldExpiry
        )
        dao.insert(oldRec)

        // Insert recent recommendation
        val recentRec = createRecommendation(
            userId = userId,
            status = RecommendationStatus.ACTIVE,
            expiresAt = futureExpiry
        )
        dao.insert(recentRec)

        // Expire old recommendations
        dao.expireOld(userId, nowMillis, nowMillis)

        // Check old recommendation is expired
        val oldResult = dao.getById(oldRec.id)
        assertNotNull(oldResult)
        assertEquals(RecommendationStatus.EXPIRED, oldResult.status)

        // Check recent recommendation is still active
        val recentResult = dao.getById(recentRec.id)
        assertNotNull(recentResult)
        assertEquals(RecommendationStatus.ACTIVE, recentResult.status)
    }

    @Test
    fun `expireOld does not update already expired records`() = runTest {
        val userId = "user123"
        val nowMillis = System.currentTimeMillis()
        val oldExpiry = nowMillis - (8 * 24 * 60 * 60 * 1000L) // 8 days ago
        val originalUpdateTime = nowMillis - (1000)

        // Insert already expired recommendation
        val expiredRec = createRecommendation(
            userId = userId,
            status = RecommendationStatus.EXPIRED,
            expiresAt = oldExpiry,
            updatedAt = originalUpdateTime
        )
        dao.insert(expiredRec)

        // Try to expire old recommendations
        dao.expireOld(userId, nowMillis, nowMillis)

        // Check that updatedAt was not changed (because status was already EXPIRED)
        val result = dao.getById(expiredRec.id)
        assertNotNull(result)
        assertEquals(RecommendationStatus.EXPIRED, result.status)
        assertEquals(originalUpdateTime, result.updatedAt)
    }

    @Test
    fun `clearByUser removes all recommendations for user`() = runTest {
        val userId1 = "user123"
        val userId2 = "user456"

        // Insert recommendations for user1
        dao.insert(createRecommendation(userId = userId1))
        dao.insert(createRecommendation(userId = userId1))

        // Insert recommendation for user2
        dao.insert(createRecommendation(userId = userId2))

        // Clear user1's recommendations
        dao.clearByUser(userId1)

        // Check user1 has no recommendations
        val user1Results = dao.getActiveByUser(userId1)
        assertEquals(0, user1Results.size)

        // Check user2 still has recommendations
        val user2Results = dao.getActiveByUser(userId2)
        assertEquals(1, user2Results.size)
    }

    @Test
    fun `getActiveByUser respects max 5 limit`() = runTest {
        val userId = "user123"
        val nowMillis = System.currentTimeMillis()
        val futureMillis = nowMillis + (24 * 60 * 60 * 1000)

        // Insert 8 active recommendations
        repeat(8) { i ->
            val rec = createRecommendation(
                userId = userId,
                status = RecommendationStatus.ACTIVE,
                dismissedAt = null,
                expiresAt = futureMillis,
                priority = RecommendationPriority.MEDIUM,
                createdAt = nowMillis + i // Different creation times
            )
            dao.insert(rec)
        }

        // Fetch active recommendations
        val results = dao.getActiveByUser(userId, nowMillis)

        // Should only return 5 recommendations
        assertEquals(5, results.size)
    }

    @Test
    fun `getActiveByUser orders by priority then createdAt DESC`() = runTest {
        val userId = "user123"
        val nowMillis = System.currentTimeMillis()
        val futureMillis = nowMillis + (24 * 60 * 60 * 1000)

        // Insert recommendations with different priorities
        val lowPriority = createRecommendation(
            userId = userId,
            priority = RecommendationPriority.LOW,
            createdAt = nowMillis + 1000,
            expiresAt = futureMillis
        )
        dao.insert(lowPriority)

        val highPriority = createRecommendation(
            userId = userId,
            priority = RecommendationPriority.HIGH,
            createdAt = nowMillis + 2000,
            expiresAt = futureMillis
        )
        dao.insert(highPriority)

        val mediumPriority = createRecommendation(
            userId = userId,
            priority = RecommendationPriority.MEDIUM,
            createdAt = nowMillis + 3000,
            expiresAt = futureMillis
        )
        dao.insert(mediumPriority)

        // Fetch active recommendations
        val results = dao.getActiveByUser(userId, nowMillis)

        // Should be ordered: HIGH, MEDIUM, LOW
        assertEquals(3, results.size)
        assertEquals(RecommendationPriority.HIGH, results[0].priority)
        assertEquals(RecommendationPriority.MEDIUM, results[1].priority)
        assertEquals(RecommendationPriority.LOW, results[2].priority)
    }

    @Test
    fun `countActive returns correct count`() = runTest {
        val userId = "user123"
        val nowMillis = System.currentTimeMillis()
        val futureMillis = nowMillis + (24 * 60 * 60 * 1000)

        // Insert 3 active recommendations
        repeat(3) {
            dao.insert(
                createRecommendation(
                    userId = userId,
                    status = RecommendationStatus.ACTIVE,
                    expiresAt = futureMillis
                )
            )
        }

        // Insert 2 archived recommendations
        repeat(2) {
            dao.insert(
                createRecommendation(
                    userId = userId,
                    status = RecommendationStatus.ARCHIVED,
                    expiresAt = futureMillis
                )
            )
        }

        val count = dao.countActive(userId, nowMillis)
        assertEquals(3, count)
    }

    @Test
    fun `getArchived returns archived recommendations ordered by dismissedAt DESC`() = runTest {
        val userId = "user123"
        val nowMillis = System.currentTimeMillis()

        // Insert archived recommendations with different dismissed times
        val archived1 = createRecommendation(
            userId = userId,
            status = RecommendationStatus.ARCHIVED,
            dismissedAt = nowMillis + 1000
        )
        dao.insert(archived1)

        val archived2 = createRecommendation(
            userId = userId,
            status = RecommendationStatus.ARCHIVED,
            dismissedAt = nowMillis + 2000
        )
        dao.insert(archived2)

        val results = dao.getArchived(userId)

        assertEquals(2, results.size)
        // Should be ordered by dismissedAt DESC
        assertTrue(results[0].dismissedAt!! >= results[1].dismissedAt!!)
    }

    @Test
    fun `deleteExpired removes expired recommendations`() = runTest {
        val userId = "user123"
        val nowMillis = System.currentTimeMillis()
        val pastExpiry = nowMillis - (1000)
        val futureExpiry = nowMillis + (24 * 60 * 60 * 1000)

        // Insert expired recommendation
        val expiredRec = createRecommendation(
            userId = userId,
            status = RecommendationStatus.EXPIRED,
            expiresAt = pastExpiry
        )
        dao.insert(expiredRec)

        // Insert active recommendation
        val activeRec = createRecommendation(
            userId = userId,
            status = RecommendationStatus.ACTIVE,
            expiresAt = futureExpiry
        )
        dao.insert(activeRec)

        // Delete expired recommendations
        val deletedCount = dao.deleteExpired(nowMillis)

        assertEquals(1, deletedCount)

        // Verify expired recommendation is deleted
        val expiredResult = dao.getById(expiredRec.id)
        assertNull(expiredResult)

        // Verify active recommendation still exists
        val activeResult = dao.getById(activeRec.id)
        assertNotNull(activeResult)
    }

    @Test
    fun `getById returns null for non-existent ID`() = runTest {
        val result = dao.getById("non-existent-id")
        assertNull(result)
    }

    @Test
    fun `insert with REPLACE strategy updates existing record`() = runTest {
        val userId = "user123"
        val id = UUID.randomUUID().toString()
        val originalRec = createRecommendation(
            id = id,
            userId = userId,
            recommendationText = "Original text"
        )
        dao.insert(originalRec)

        // Insert with same ID but different text
        val updatedRec = originalRec.copy(recommendationText = "Updated text")
        dao.insert(updatedRec)

        val result = dao.getById(id)
        assertNotNull(result)
        assertEquals("Updated text", result.recommendationText)
    }

    // Helper function to create test recommendations
    private fun createRecommendation(
        id: String = UUID.randomUUID().toString(),
        userId: String = "user123",
        recommendationText: String = "Test recommendation",
        navigationTarget: String = "TRANSACTION_LIST",
        filterCriteria: String = "{}",
        createdAt: Long = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis(),
        dismissedAt: Long? = null,
        expiresAt: Long = System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000),
        priority: RecommendationPriority = RecommendationPriority.MEDIUM,
        category: String = "GENERAL",
        sourceArtifactId: String = "",
        status: RecommendationStatus = RecommendationStatus.ACTIVE
    ): RecommendationEntity {
        return RecommendationEntity(
            id = id,
            userId = userId,
            recommendationText = recommendationText,
            navigationTarget = navigationTarget,
            filterCriteria = filterCriteria,
            createdAt = createdAt,
            updatedAt = updatedAt,
            dismissedAt = dismissedAt,
            expiresAt = expiresAt,
            priority = priority,
            category = category,
            sourceArtifactId = sourceArtifactId,
            status = status
        )
    }
}
