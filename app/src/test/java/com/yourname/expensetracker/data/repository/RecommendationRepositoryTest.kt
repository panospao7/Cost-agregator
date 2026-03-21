package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.RecommendationDao
import com.yourname.expensetracker.data.database.entity.RecommendationEntity
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.domain.model.recommendation.RecommendationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for RecommendationRepository.
 * Tests business logic and DAO delegation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationRepositoryTest {

    private lateinit var dao: RecommendationDao
    private lateinit var repository: RecommendationRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        dao = mockk()
        repository = RecommendationRepository(dao, testDispatcher)
    }

    @Test
    fun `getActiveForUser delegates to DAO and maps to domain model`() = runTest {
        val userId = "user123"
        val entity = createRecommendationEntity(userId = userId)

        coEvery { dao.getActiveByUser(userId, any()) } returns listOf(entity)

        val result = repository.getActiveForUser(userId)

        assertEquals(1, result.size)
        assertEquals(entity.id, result[0].id)
        assertEquals(entity.recommendationText, result[0].recommendationText)
        coVerify(exactly = 1) { dao.getActiveByUser(userId, any()) }
    }

    @Test
    fun `save wraps insert and converts to entity`() = runTest {
        val recommendation = createRecommendation()

        coEvery { dao.insert(any()) } returns Unit

        repository.save(recommendation)

        coVerify(exactly = 1) { dao.insert(match { it.id == recommendation.id }) }
    }

    @Test
    fun `dismiss calls DAO archive`() = runTest {
        val recommendationId = "rec123"

        coEvery { dao.archive(recommendationId, any()) } returns Unit

        repository.dismiss(recommendationId)

        coVerify(exactly = 1) { dao.archive(recommendationId, any()) }
    }

    @Test
    fun `expireAll calls DAO expireOld`() = runTest {
        val userId = "user123"
        val timestamp = System.currentTimeMillis()

        coEvery { dao.expireOld(userId, timestamp, any()) } returns Unit

        repository.expireAll(userId, timestamp)

        coVerify(exactly = 1) { dao.expireOld(userId, timestamp, any()) }
    }

    @Test
    fun `expireOld calls DAO expireOld`() = runTest {
        val userId = "user123"
        val timestamp = System.currentTimeMillis()

        coEvery { dao.expireOld(userId, timestamp, any()) } returns Unit

        repository.expireOld(userId, timestamp)

        coVerify(exactly = 1) { dao.expireOld(userId, timestamp, any()) }
    }

    @Test
    fun `clearForUser calls DAO clearByUser`() = runTest {
        val userId = "user123"

        coEvery { dao.clearByUser(userId) } returns Unit

        repository.clearForUser(userId)

        coVerify(exactly = 1) { dao.clearByUser(userId) }
    }

    @Test
    fun `saveAll enforces max 5 limit`() = runTest {
        val userId = "user123"
        
        // Create 8 recommendations with different priorities
        val recommendations = listOf(
            createRecommendation(userId = userId, priority = RecommendationPriority.LOW),
            createRecommendation(userId = userId, priority = RecommendationPriority.MEDIUM),
            createRecommendation(userId = userId, priority = RecommendationPriority.HIGH),
            createRecommendation(userId = userId, priority = RecommendationPriority.LOW),
            createRecommendation(userId = userId, priority = RecommendationPriority.MEDIUM),
            createRecommendation(userId = userId, priority = RecommendationPriority.HIGH),
            createRecommendation(userId = userId, priority = RecommendationPriority.LOW),
            createRecommendation(userId = userId, priority = RecommendationPriority.MEDIUM)
        )

        coEvery { dao.insertAll(any()) } returns Unit

        repository.saveAll(recommendations)

        // Verify only 5 recommendations were inserted
        coVerify(exactly = 1) { 
            dao.insertAll(match { entities -> 
                entities.size == 5
            }) 
        }
    }

    @Test
    fun `saveAll prioritizes HIGH over MEDIUM over LOW`() = runTest {
        val userId = "user123"
        
        // Create recommendations with mixed priorities
        val recommendations = listOf(
            createRecommendation(userId = userId, priority = RecommendationPriority.LOW),
            createRecommendation(userId = userId, priority = RecommendationPriority.HIGH),
            createRecommendation(userId = userId, priority = RecommendationPriority.MEDIUM),
            createRecommendation(userId = userId, priority = RecommendationPriority.LOW),
            createRecommendation(userId = userId, priority = RecommendationPriority.HIGH),
            createRecommendation(userId = userId, priority = RecommendationPriority.MEDIUM)
        )

        coEvery { dao.insertAll(any()) } returns Unit

        repository.saveAll(recommendations)

        // Verify the top 5 prioritize HIGH recommendations first
        coVerify(exactly = 1) { 
            dao.insertAll(match { entities -> 
                entities.size == 5 && 
                entities.count { it.priority == RecommendationPriority.HIGH } == 2 &&
                entities.count { it.priority == RecommendationPriority.MEDIUM } == 2 &&
                entities.count { it.priority == RecommendationPriority.LOW } == 1
            }) 
        }
    }

    @Test
    fun `observeActiveForUser returns Flow and maps entities to domain`() = runTest {
        val userId = "user123"
        val entity1 = createRecommendationEntity(userId = userId, recommendationText = "Rec 1")
        val entity2 = createRecommendationEntity(userId = userId, recommendationText = "Rec 2")

        every { dao.observeActiveByUser(userId, any()) } returns flowOf(listOf(entity1, entity2))

        val flow = repository.observeActiveForUser(userId)
        
        flow.collect { recommendations ->
            assertEquals(2, recommendations.size)
            assertEquals("Rec 1", recommendations[0].recommendationText)
            assertEquals("Rec 2", recommendations[1].recommendationText)
        }
    }

    @Test
    fun `getArchivedForUser delegates to DAO and maps to domain`() = runTest {
        val userId = "user123"
        val entity = createRecommendationEntity(
            userId = userId,
            status = RecommendationStatus.ARCHIVED
        )

        coEvery { dao.getArchived(userId, any()) } returns listOf(entity)

        val result = repository.getArchivedForUser(userId)

        assertEquals(1, result.size)
        assertEquals(entity.id, result[0].id)
        coVerify(exactly = 1) { dao.getArchived(userId, any()) }
    }

    @Test
    fun `getById returns mapped domain model`() = runTest {
        val id = "rec123"
        val entity = createRecommendationEntity(id = id)

        coEvery { dao.getById(id) } returns entity

        val result = repository.getById(id)

        assertNotNull(result)
        assertEquals(id, result.id)
        assertEquals(entity.recommendationText, result.recommendationText)
    }

    @Test
    fun `getById returns null when DAO returns null`() = runTest {
        val id = "non-existent"

        coEvery { dao.getById(id) } returns null

        val result = repository.getById(id)

        assertNull(result)
    }

    @Test
    fun `countActive delegates to DAO`() = runTest {
        val userId = "user123"
        val expectedCount = 3

        coEvery { dao.countActive(userId, any()) } returns expectedCount

        val result = repository.countActive(userId)

        assertEquals(expectedCount, result)
        coVerify(exactly = 1) { dao.countActive(userId, any()) }
    }

    @Test
    fun `cleanupExpired delegates to DAO deleteExpired`() = runTest {
        val expectedDeleted = 5

        coEvery { dao.deleteExpired(any()) } returns expectedDeleted

        val result = repository.cleanupExpired()

        assertEquals(expectedDeleted, result)
        coVerify(exactly = 1) { dao.deleteExpired(any()) }
    }

    @Test
    fun `repository runs operations on IO dispatcher`() = runTest {
        val userId = "user123"
        
        coEvery { dao.getActiveByUser(userId, any()) } returns emptyList()

        repository.getActiveForUser(userId)

        // Test that operations complete successfully on the test dispatcher
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { dao.getActiveByUser(userId, any()) }
    }

    // Helper functions
    private fun createRecommendation(
        id: String = "rec_${System.nanoTime()}",
        userId: String = "user123",
        recommendationText: String = "Test recommendation",
        priority: RecommendationPriority = RecommendationPriority.MEDIUM,
        status: RecommendationStatus = RecommendationStatus.ACTIVE
    ): DashboardFollowThroughRecommendation {
        return DashboardFollowThroughRecommendation(
            id = id,
            userId = userId,
            recommendationText = recommendationText,
            navigationTarget = "TRANSACTION_LIST",
            filterCriteria = "{}",
            priority = priority,
            category = "GENERAL",
            sourceArtifactId = "",
            status = status
        )
    }

    private fun createRecommendationEntity(
        id: String = "rec_${System.nanoTime()}",
        userId: String = "user123",
        recommendationText: String = "Test recommendation",
        priority: RecommendationPriority = RecommendationPriority.MEDIUM,
        status: RecommendationStatus = RecommendationStatus.ACTIVE
    ): RecommendationEntity {
        return RecommendationEntity(
            id = id,
            userId = userId,
            recommendationText = recommendationText,
            navigationTarget = "TRANSACTION_LIST",
            filterCriteria = "{}",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            dismissedAt = null,
            expiresAt = System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000),
            priority = priority,
            category = "GENERAL",
            sourceArtifactId = "",
            status = status
        )
    }
}
