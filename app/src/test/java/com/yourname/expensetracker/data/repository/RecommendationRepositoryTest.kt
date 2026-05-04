package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.RecommendationDao
import com.yourname.expensetracker.data.database.entity.RecommendationEntity
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.domain.model.recommendation.RecommendationStatus
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.service.RecommendationDeduplicator
import com.yourname.expensetracker.service.TransactionFilterSerializer
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
    private lateinit var deduplicator: RecommendationDeduplicator
    private lateinit var timeProvider: FakeTimeProvider
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        dao = mockk()
        timeProvider = FakeTimeProvider(1_700_000_000_000L)
        val filterSerializer = TransactionFilterSerializer()
        deduplicator = RecommendationDeduplicator(filterSerializer)
        repository = RecommendationRepository(dao, deduplicator, timeProvider, testDispatcher)
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
        val timestamp = timeProvider.now()

        coEvery { dao.expireOld(userId, timestamp, any()) } returns Unit

        repository.expireAll(userId, timestamp)

        coVerify(exactly = 1) { dao.expireOld(userId, timestamp, any()) }
    }

    @Test
    fun `expireOld calls DAO expireOld`() = runTest {
        val userId = "user123"
        val timestamp = timeProvider.now()

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
        
        // Create 8 recommendations with unique filter targets so dedup does not collapse them
        val recommendations = listOf(
            createRecommendation(userId = userId, priority = RecommendationPriority.LOW, category = "CAT_1", filterCriteria = merchantFilter("merchant_1")),
            createRecommendation(userId = userId, priority = RecommendationPriority.MEDIUM, category = "CAT_2", filterCriteria = merchantFilter("merchant_2")),
            createRecommendation(userId = userId, priority = RecommendationPriority.HIGH, category = "CAT_3", filterCriteria = merchantFilter("merchant_3")),
            createRecommendation(userId = userId, priority = RecommendationPriority.LOW, category = "CAT_4", filterCriteria = merchantFilter("merchant_4")),
            createRecommendation(userId = userId, priority = RecommendationPriority.MEDIUM, category = "CAT_5", filterCriteria = merchantFilter("merchant_5")),
            createRecommendation(userId = userId, priority = RecommendationPriority.HIGH, category = "CAT_6", filterCriteria = merchantFilter("merchant_6")),
            createRecommendation(userId = userId, priority = RecommendationPriority.LOW, category = "CAT_7", filterCriteria = merchantFilter("merchant_7")),
            createRecommendation(userId = userId, priority = RecommendationPriority.MEDIUM, category = "CAT_8", filterCriteria = merchantFilter("merchant_8"))
        )

        coEvery { dao.getAllActiveByUser(userId, any()) } returns emptyList()
        coEvery { dao.insertAll(any()) } returns Unit
        coEvery { dao.archiveActiveOverflow(userId, any(), any()) } returns 0

        repository.saveAll(recommendations)

        // Verify only 5 recommendations were inserted
        coVerify(exactly = 1) { 
            dao.insertAll(match { entities -> 
                entities.size == 5
            }) 
        }
        coVerify(exactly = 0) { dao.archiveActiveOverflow(userId, any(), any()) }
    }

    @Test
    fun `saveAll prioritizes HIGH over MEDIUM over LOW`() = runTest {
        val userId = "user123"
        
        // Create recommendations with mixed priorities and unique filter targets
        val recommendations = listOf(
            createRecommendation(userId = userId, priority = RecommendationPriority.LOW, category = "CAT_1", filterCriteria = merchantFilter("merchant_1")),
            createRecommendation(userId = userId, priority = RecommendationPriority.HIGH, category = "CAT_2", filterCriteria = merchantFilter("merchant_2")),
            createRecommendation(userId = userId, priority = RecommendationPriority.MEDIUM, category = "CAT_3", filterCriteria = merchantFilter("merchant_3")),
            createRecommendation(userId = userId, priority = RecommendationPriority.LOW, category = "CAT_4", filterCriteria = merchantFilter("merchant_4")),
            createRecommendation(userId = userId, priority = RecommendationPriority.HIGH, category = "CAT_5", filterCriteria = merchantFilter("merchant_5")),
            createRecommendation(userId = userId, priority = RecommendationPriority.MEDIUM, category = "CAT_6", filterCriteria = merchantFilter("merchant_6"))
        )

        coEvery { dao.getAllActiveByUser(userId, any()) } returns emptyList()
        coEvery { dao.insertAll(any()) } returns Unit
        coEvery { dao.archiveActiveOverflow(userId, any(), any()) } returns 0

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
        coVerify(exactly = 0) { dao.archiveActiveOverflow(userId, any(), any()) }
    }

    @Test
    fun `saveAll merges with existing active set and archives overflow deterministically`() = runTest {
        val userId = "user123"
        val existing = listOf(
            createRecommendationEntity(id = "existing_high_old", userId = userId, priority = RecommendationPriority.HIGH, category = "CAT_E1", createdAt = 100L, filterCriteria = merchantFilter("existing_1")),
            createRecommendationEntity(id = "existing_medium_old", userId = userId, priority = RecommendationPriority.MEDIUM, category = "CAT_E2", createdAt = 90L, filterCriteria = merchantFilter("existing_2")),
            createRecommendationEntity(id = "existing_low_old", userId = userId, priority = RecommendationPriority.LOW, category = "CAT_E3", createdAt = 80L, filterCriteria = merchantFilter("existing_3")),
            createRecommendationEntity(id = "existing_high_newer", userId = userId, priority = RecommendationPriority.HIGH, category = "CAT_E4", createdAt = 110L, filterCriteria = merchantFilter("existing_4")),
            createRecommendationEntity(id = "existing_medium_newer", userId = userId, priority = RecommendationPriority.MEDIUM, category = "CAT_E5", createdAt = 105L, filterCriteria = merchantFilter("existing_5"))
        )
        val incoming = listOf(
            createRecommendation(id = "new_high_best", userId = userId, priority = RecommendationPriority.HIGH, category = "CAT_N1", createdAt = 120L, filterCriteria = merchantFilter("new_1")),
            createRecommendation(id = "new_medium_mid", userId = userId, priority = RecommendationPriority.MEDIUM, category = "CAT_N2", createdAt = 115L, filterCriteria = merchantFilter("new_2")),
            createRecommendation(id = "new_low_recent", userId = userId, priority = RecommendationPriority.LOW, category = "CAT_N3", createdAt = 130L, filterCriteria = merchantFilter("new_3"))
        )

        coEvery { dao.getAllActiveByUser(userId, any()) } returns existing
        coEvery { dao.insertAll(any()) } returns Unit
        coEvery { dao.archiveActiveOverflow(userId, any(), any()) } returns 3

        repository.saveAll(incoming)

        coVerify(exactly = 1) {
            dao.insertAll(match { inserted ->
                inserted.map { it.id } == listOf("new_high_best")
            })
        }
        coVerify(exactly = 1) {
            dao.archiveActiveOverflow(
                userId,
                match { retainedIds ->
                    retainedIds == listOf(
                        "new_high_best",
                        "existing_high_newer",
                        "existing_high_old",
                        "new_medium_mid",
                        "existing_medium_newer"
                    )
                },
                any()
            )
        }
    }

    @Test
    fun `saveAll prunes existing overflow even when incoming batch is fully duplicate`() = runTest {
        val userId = "user123"
        val existing = listOf(
            createRecommendationEntity(id = "existing_1", userId = userId, priority = RecommendationPriority.HIGH, category = "CAT_1", createdAt = 200L, filterCriteria = merchantFilter("merchant_1")),
            createRecommendationEntity(id = "existing_2", userId = userId, priority = RecommendationPriority.HIGH, category = "CAT_2", createdAt = 190L, filterCriteria = merchantFilter("merchant_2")),
            createRecommendationEntity(id = "existing_3", userId = userId, priority = RecommendationPriority.MEDIUM, category = "CAT_3", createdAt = 180L, filterCriteria = merchantFilter("merchant_3")),
            createRecommendationEntity(id = "existing_4", userId = userId, priority = RecommendationPriority.MEDIUM, category = "CAT_4", createdAt = 170L, filterCriteria = merchantFilter("merchant_4")),
            createRecommendationEntity(id = "existing_5", userId = userId, priority = RecommendationPriority.LOW, category = "CAT_5", createdAt = 160L, filterCriteria = merchantFilter("merchant_5")),
            createRecommendationEntity(id = "existing_6", userId = userId, priority = RecommendationPriority.LOW, category = "CAT_6", createdAt = 150L, filterCriteria = merchantFilter("merchant_6")),
            createRecommendationEntity(id = "existing_7", userId = userId, priority = RecommendationPriority.LOW, category = "CAT_7", createdAt = 140L, filterCriteria = merchantFilter("merchant_7"))
        )
        val duplicateIncoming = listOf(
            createRecommendation(id = "duplicate_1", userId = userId, priority = RecommendationPriority.HIGH, category = "CAT_1", filterCriteria = merchantFilter("merchant_1")),
            createRecommendation(id = "duplicate_2", userId = userId, priority = RecommendationPriority.MEDIUM, category = "CAT_3", filterCriteria = merchantFilter("merchant_3"))
        )

        coEvery { dao.getAllActiveByUser(userId, any()) } returns existing
        coEvery { dao.insertAll(any()) } returns Unit
        coEvery { dao.archiveActiveOverflow(userId, any(), any()) } returns 2

        repository.saveAll(duplicateIncoming)

        coVerify(exactly = 0) { dao.insertAll(any()) }
        coVerify(exactly = 1) {
            dao.archiveActiveOverflow(
                userId,
                match { retainedIds ->
                    retainedIds == listOf("existing_1", "existing_2", "existing_3", "existing_4", "existing_5")
                },
                any()
            )
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
        status: RecommendationStatus = RecommendationStatus.ACTIVE,
        category: String = "GENERAL",
        filterCriteria: String = "{}",
        createdAt: Long = 1_000L,
        updatedAt: Long = createdAt
    ): DashboardFollowThroughRecommendation {
        return DashboardFollowThroughRecommendation(
            id = id,
            userId = userId,
            recommendationText = recommendationText,
            navigationTarget = "TRANSACTION_LIST",
            filterCriteria = filterCriteria,
            createdAt = createdAt,
            updatedAt = updatedAt,
            priority = priority,
            category = category,
            sourceArtifactId = "",
            status = status,
            expiresAt = 0L,
        )
    }

    private fun createRecommendationEntity(
        id: String = "rec_${System.nanoTime()}",
        userId: String = "user123",
        recommendationText: String = "Test recommendation",
        priority: RecommendationPriority = RecommendationPriority.MEDIUM,
        status: RecommendationStatus = RecommendationStatus.ACTIVE,
        category: String = "GENERAL",
        filterCriteria: String = "{}",
        createdAt: Long = 1_000L,
        updatedAt: Long = createdAt,
        dismissedAt: Long? = null,
        expiresAt: Long = createdAt + (7L * 24 * 60 * 60 * 1000)
    ): RecommendationEntity {
        return RecommendationEntity(
            id = id,
            userId = userId,
            recommendationText = recommendationText,
            navigationTarget = "TRANSACTION_LIST",
            filterCriteria = filterCriteria,
            createdAt = createdAt,
            updatedAt = updatedAt,
            dismissedAt = dismissedAt,
            expiresAt = expiresAt,
            priority = priority,
            category = category,
            sourceArtifactId = "",
            status = status
        )
    }

    private fun merchantFilter(merchantName: String): String {
        return "{\"version\":1,\"merchantName\":\"$merchantName\"}"
    }
}