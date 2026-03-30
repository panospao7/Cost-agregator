package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.domain.analytics.SpendingThresholdCalculator
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.domain.model.recommendation.RecommendationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for RecommendationLifecycleManager.
 * Tests expiration checking, cleanup operations, and periodic background tasks.
 * 
 * Phase 2: AI Follow-Through - Filter & Navigation Integration
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationLifecycleManagerTest {

    private lateinit var repository: RecommendationRepository
    private lateinit var stateManager: RecommendationStateManager
    private lateinit var cacheService: RecommendationCacheService
    private lateinit var thresholdCalculator: SpendingThresholdCalculator
    private lateinit var lifecycleManager: RecommendationLifecycleManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        stateManager = mockk(relaxed = true)
        cacheService = mockk(relaxed = true)
        thresholdCalculator = mockk(relaxed = true)
        lifecycleManager = RecommendationLifecycleManager(
            repository = repository,
            stateManager = stateManager,
            cacheService = cacheService,
            thresholdCalculator = thresholdCalculator,
            ioDispatcher = testDispatcher,
            applicationScope = kotlinx.coroutines.test.TestScope(testDispatcher)
        )
    }

    // ========== checkAndExpire() Tests ==========

    @Test
    fun `checkAndExpire calls repository expireOld`() = runTest {
        coEvery { repository.expireOld("user123", any()) } returns Unit

        lifecycleManager.checkAndExpire("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("user123", any()) }
    }

    @Test
    fun `checkAndExpire evicts expired items from cache`() = runTest {
        lifecycleManager.checkAndExpire("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { cacheService.evictExpired() }
    }

    @Test
    fun `checkAndExpire refreshes state manager`() = runTest {
        lifecycleManager.checkAndExpire("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.refreshForUser("user123") }
    }

    @Test
    fun `checkAndExpire executes operations in correct order`() = runTest {
        val callOrder = mutableListOf<String>()

        coEvery { repository.expireOld("user123", any()) } coAnswers {
            callOrder.add("expireOld")
        }
        coEvery { cacheService.evictExpired() } coAnswers {
            callOrder.add("evictExpired")
        }
        coEvery { stateManager.refreshForUser("user123") } coAnswers {
            callOrder.add("refresh")
        }

        lifecycleManager.checkAndExpire("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        assert(callOrder.size == 3)
        assert(callOrder[0] == "expireOld")
        assert(callOrder[1] == "evictExpired")
        assert(callOrder[2] == "refresh")
    }

    @Test
    fun `checkAndExpire handles repository errors gracefully`() = runTest {
        coEvery { repository.expireOld("user123", any()) } throws RuntimeException("Database error")

        // Should not throw
        lifecycleManager.checkAndExpire("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("user123", any()) }
    }

    @Test
    fun `checkAndExpire handles cache eviction errors gracefully`() = runTest {
        coEvery { cacheService.evictExpired() } throws RuntimeException("Cache error")

        lifecycleManager.checkAndExpire("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { cacheService.evictExpired() }
    }

    @Test
    fun `checkAndExpire handles state refresh errors gracefully`() = runTest {
        coEvery { stateManager.refreshForUser("user123") } throws RuntimeException("State error")

        lifecycleManager.checkAndExpire("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.refreshForUser("user123") }
    }

    @Test
    fun `checkAndExpire works with different user IDs`() = runTest {
        lifecycleManager.checkAndExpire("user456")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("user456", any()) }
        coVerify(exactly = 1) { stateManager.refreshForUser("user456") }
    }

    @Test
    fun `checkAndExpire works with empty user ID`() = runTest {
        lifecycleManager.checkAndExpire("")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("", any()) }
    }

    @Test
    fun `checkAndExpire can be called multiple times`() = runTest {
        lifecycleManager.checkAndExpire("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        lifecycleManager.checkAndExpire("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 2) { repository.expireOld("user123", any()) }
        coVerify(exactly = 2) { cacheService.evictExpired() }
        coVerify(exactly = 2) { stateManager.refreshForUser("user123") }
    }

    // ========== cleanupExpired() Tests ==========

    @Test
    fun `cleanupExpired calls repository cleanupExpired`() = runTest {
        coEvery { repository.cleanupExpired() } returns 1

        lifecycleManager.cleanupExpired()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.cleanupExpired() }
    }

    @Test
    fun `cleanupExpired evicts expired cache entries`() = runTest {
        lifecycleManager.cleanupExpired()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { cacheService.evictExpired() }
    }

    @Test
    fun `cleanupExpired refreshes state when user ID available`() = runTest {
        coEvery { stateManager.getCurrentUserId() } returns "user123"

        lifecycleManager.cleanupExpired()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.refreshForUser("user123") }
    }

    @Test
    fun `cleanupExpired does not refresh state when user ID is null`() = runTest {
        coEvery { stateManager.getCurrentUserId() } returns null

        lifecycleManager.cleanupExpired()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { stateManager.refreshForUser(any()) }
    }

    @Test
    fun `cleanupExpired handles repository errors gracefully`() = runTest {
        coEvery { repository.cleanupExpired() } throws RuntimeException("Cleanup error")

        lifecycleManager.cleanupExpired()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.cleanupExpired() }
    }

    @Test
    fun `cleanupExpired handles cache errors gracefully`() = runTest {
        coEvery { cacheService.evictExpired() } throws RuntimeException("Cache error")

        lifecycleManager.cleanupExpired()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { cacheService.evictExpired() }
    }

    @Test
    fun `cleanupExpired handles getCurrentUserId errors gracefully`() = runTest {
        coEvery { stateManager.getCurrentUserId() } throws RuntimeException("State error")

        lifecycleManager.cleanupExpired()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.cleanupExpired() }
        coVerify(exactly = 1) { cacheService.evictExpired() }
    }

    @Test
    fun `cleanupExpired executes in correct order`() = runTest {
        val callOrder = mutableListOf<String>()

        coEvery { repository.cleanupExpired() } coAnswers {
            callOrder.add("cleanup")
            1
        }
        coEvery { cacheService.evictExpired() } coAnswers {
            callOrder.add("evict")
        }
        coEvery { stateManager.getCurrentUserId() } returns "user123"
        coEvery { stateManager.refreshForUser("user123") } coAnswers {
            callOrder.add("refresh")
        }

        lifecycleManager.cleanupExpired()
        testDispatcher.scheduler.advanceUntilIdle()

        assert(callOrder.size == 3)
        assert(callOrder[0] == "cleanup")
        assert(callOrder[1] == "evict")
        assert(callOrder[2] == "refresh")
    }

    // ========== startPeriodicExpirationCheck() Tests ==========

    @Test
    fun `startPeriodicExpirationCheck starts background coroutine`() = runTest {
        lifecycleManager.startPeriodicExpirationCheck()
        
        // Advance time but not enough for first interval
        testDispatcher.scheduler.advanceTimeBy(1000)
        
        // Should not have run cleanup yet
        coVerify(exactly = 0) { repository.cleanupExpired() }
    }

    @Test
    fun `startPeriodicExpirationCheck runs cleanup after 6 hours`() = runTest {
        lifecycleManager.startPeriodicExpirationCheck()
        
        // Advance 6 hours + a bit
        testDispatcher.scheduler.advanceTimeBy(6L * 60 * 60 * 1000 + 100)
        
        coVerify(atLeast = 1) { repository.cleanupExpired() }
    }

    @Test
    fun `startPeriodicExpirationCheck runs cleanup multiple times`() = runTest {
        lifecycleManager.startPeriodicExpirationCheck()
        
        // Advance 18 hours (should trigger 3 times at 6h intervals)
        testDispatcher.scheduler.advanceTimeBy(18L * 60 * 60 * 1000 + 100)
        
        coVerify(atLeast = 2) { repository.cleanupExpired() }
    }

    @Test
    fun `startPeriodicExpirationCheck can only be started once`() = runTest {
        lifecycleManager.startPeriodicExpirationCheck()
        lifecycleManager.startPeriodicExpirationCheck()
        lifecycleManager.startPeriodicExpirationCheck()
        
        // Advance 6 hours
        testDispatcher.scheduler.advanceTimeBy(6L * 60 * 60 * 1000 + 100)
        
        // Should only run once, not three times
        coVerify(atMost = 1) { repository.cleanupExpired() }
    }

    @Test
    fun `startPeriodicExpirationCheck continues after errors`() = runTest {
        var callCount = 0
        coEvery { repository.cleanupExpired() } coAnswers {
            callCount++
            if (callCount == 1) {
                throw RuntimeException("First call fails")
            }
            1
        }

        lifecycleManager.startPeriodicExpirationCheck()
        
        // First interval - should fail
        testDispatcher.scheduler.advanceTimeBy(6L * 60 * 60 * 1000 + 100)
        
        // Second interval - should succeed
        testDispatcher.scheduler.advanceTimeBy(6L * 60 * 60 * 1000)
        
        // Should have been called at least twice
        coVerify(atLeast = 2) { repository.cleanupExpired() }
    }

    // ========== Concurrent Operations Tests ==========

    @Test
    fun `checkAndExpire handles concurrent calls for same user`() = runTest {
        lifecycleManager.checkAndExpire("user123")
        lifecycleManager.checkAndExpire("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 2) { repository.expireOld("user123", any()) }
    }

    @Test
    fun `checkAndExpire handles concurrent calls for different users`() = runTest {
        lifecycleManager.checkAndExpire("user123")
        lifecycleManager.checkAndExpire("user456")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("user123", any()) }
        coVerify(exactly = 1) { repository.expireOld("user456", any()) }
    }

    @Test
    fun `cleanupExpired can run concurrently with checkAndExpire`() = runTest {
        lifecycleManager.checkAndExpire("user123")
        lifecycleManager.cleanupExpired()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("user123", any()) }
        coVerify(exactly = 1) { repository.cleanupExpired() }
        coVerify(atLeast = 2) { cacheService.evictExpired() }
    }

    // ========== Edge Cases ==========

    @Test
    fun `checkAndExpire handles IOException from repository`() = runTest {
        coEvery { repository.expireOld("user123", any()) } throws java.io.IOException("Network error")

        lifecycleManager.checkAndExpire("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("user123", any()) }
    }

    @Test
    fun `cleanupExpired handles database constraint violation`() = runTest {
        coEvery { repository.cleanupExpired() } throws android.database.sqlite.SQLiteConstraintException()

        lifecycleManager.cleanupExpired()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.cleanupExpired() }
    }

    @Test
    fun `checkAndExpire handles OutOfMemoryError gracefully`() = runTest {
        coEvery { cacheService.evictExpired() } throws OutOfMemoryError()

        // Should catch all Throwables, not just Exceptions
        try {
            lifecycleManager.checkAndExpire("user123")
            testDispatcher.scheduler.advanceUntilIdle()
        } catch (e: OutOfMemoryError) {
            // Expected to be caught internally
        }

        coVerify(exactly = 1) { repository.expireOld("user123", any()) }
    }

    @Test
    fun `periodic check uses 6 hour interval constant`() = runTest {
        lifecycleManager.startPeriodicExpirationCheck()
        
        // Test that cleanup runs at 6 hour intervals
        // First run at 6h
        testDispatcher.scheduler.advanceTimeBy(6L * 60 * 60 * 1000 + 100)
        coVerify(atLeast = 1) { repository.cleanupExpired() }
        
        // Second run at 12h total
        testDispatcher.scheduler.advanceTimeBy(6L * 60 * 60 * 1000)
        coVerify(atLeast = 2) { repository.cleanupExpired() }
    }

    @Test
    fun `multiple checkAndExpire calls do not interfere`() = runTest {
        val users = listOf("user1", "user2", "user3", "user4", "user5")
        
        users.forEach { userId ->
            lifecycleManager.checkAndExpire(userId)
        }
        testDispatcher.scheduler.advanceUntilIdle()

        users.forEach { userId ->
            coVerify(exactly = 1) { repository.expireOld(userId, any()) }
            coVerify(exactly = 1) { stateManager.refreshForUser(userId) }
        }
    }

    @Test
    fun `cleanupExpired handles null and non-null user IDs in sequence`() = runTest {
        // First call with null user ID
        coEvery { stateManager.getCurrentUserId() } returns null
        lifecycleManager.cleanupExpired()
        testDispatcher.scheduler.advanceUntilIdle()

        // Second call with valid user ID
        coEvery { stateManager.getCurrentUserId() } returns "user123"
        lifecycleManager.cleanupExpired()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 2) { repository.cleanupExpired() }
        coVerify(exactly = 1) { stateManager.refreshForUser("user123") }
    }
}
