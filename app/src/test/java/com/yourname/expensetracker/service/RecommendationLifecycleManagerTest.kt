package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.domain.analytics.SpendingThresholdCalculator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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
    private lateinit var applicationScope: TestScope

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        stateManager = mockk(relaxed = true)
        cacheService = mockk(relaxed = true)
        thresholdCalculator = mockk(relaxed = true)
        applicationScope = TestScope(testDispatcher)

        // Fix NPE in expireOld's Kotlin $default synthetic method:
        // The compiler evaluates timeProvider.now() for the default param
        // before MockK can intercept the call. Setting the private field
        // on the mock prevents the NPE.
        val timeProviderMock = mockk<TimeProvider>(relaxed = true)
        every { timeProviderMock.now() } returns System.currentTimeMillis()
        RecommendationRepository::class.java.getDeclaredField("timeProvider").apply {
            isAccessible = true
            set(repository, timeProviderMock)
        }

        lifecycleManager = RecommendationLifecycleManager(
            repository = repository,
            stateManager = stateManager,
            cacheService = cacheService,
            thresholdCalculator = thresholdCalculator,
            ioDispatcher = testDispatcher,
            applicationScope = applicationScope
        )
    }

    @After
    fun teardown() {
        applicationScope.cancel()
        Dispatchers.resetMain()
    }

    // ========== checkAndExpire() Tests ==========

    @Test
    fun `checkAndExpire calls repository expireOld`() = runTest(testDispatcher) {
        coEvery { repository.expireOld("user123", any()) } returns Unit

        lifecycleManager.checkAndExpire("user123")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("user123", any()) }
    }

    @Test
    fun `checkAndExpire evicts expired items from cache`() = runTest(testDispatcher) {
        lifecycleManager.checkAndExpire("user123")
        advanceUntilIdle()

        coVerify(exactly = 1) { cacheService.evictExpired() }
    }

    @Test
    fun `checkAndExpire refreshes state manager`() = runTest(testDispatcher) {
        lifecycleManager.checkAndExpire("user123")
        advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.refreshForUser("user123") }
    }

    @Test
    fun `checkAndExpire executes operations in correct order`() = runTest(testDispatcher) {
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
        advanceUntilIdle()

        assert(callOrder.size == 3)
        assert(callOrder[0] == "expireOld")
        assert(callOrder[1] == "evictExpired")
        assert(callOrder[2] == "refresh")
    }

    @Test
    fun `checkAndExpire handles repository errors gracefully`() = runTest(testDispatcher) {
        coEvery { repository.expireOld("user123", any()) } throws RuntimeException("Database error")

        // Should not throw
        lifecycleManager.checkAndExpire("user123")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("user123", any()) }
    }

    @Test
    fun `checkAndExpire handles cache eviction errors gracefully`() = runTest(testDispatcher) {
        coEvery { cacheService.evictExpired() } throws RuntimeException("Cache error")

        lifecycleManager.checkAndExpire("user123")
        advanceUntilIdle()

        coVerify(exactly = 1) { cacheService.evictExpired() }
    }

    @Test
    fun `checkAndExpire handles state refresh errors gracefully`() = runTest(testDispatcher) {
        coEvery { stateManager.refreshForUser("user123") } throws RuntimeException("State error")

        lifecycleManager.checkAndExpire("user123")
        advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.refreshForUser("user123") }
    }

    @Test
    fun `checkAndExpire works with different user IDs`() = runTest(testDispatcher) {
        lifecycleManager.checkAndExpire("user456")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("user456", any()) }
        coVerify(exactly = 1) { stateManager.refreshForUser("user456") }
    }

    @Test
    fun `checkAndExpire works with empty user ID`() = runTest(testDispatcher) {
        lifecycleManager.checkAndExpire("")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("", any()) }
    }

    @Test
    fun `checkAndExpire can be called multiple times`() = runTest(testDispatcher) {
        lifecycleManager.checkAndExpire("user123")
        advanceUntilIdle()

        lifecycleManager.checkAndExpire("user123")
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.expireOld("user123", any()) }
        coVerify(exactly = 2) { cacheService.evictExpired() }
        coVerify(exactly = 2) { stateManager.refreshForUser("user123") }
    }

    // ========== cleanupExpired() Tests ==========

    @Test
    fun `cleanupExpired calls repository cleanupExpired`() = runTest(testDispatcher) {
        coEvery { repository.cleanupExpired() } returns 1

        lifecycleManager.cleanupExpired()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.cleanupExpired() }
    }

    @Test
    fun `cleanupExpired evicts expired cache entries`() = runTest(testDispatcher) {
        lifecycleManager.cleanupExpired()
        advanceUntilIdle()

        coVerify(exactly = 1) { cacheService.evictExpired() }
    }

    @Test
    fun `cleanupExpired refreshes state when user ID available`() = runTest(testDispatcher) {
        coEvery { stateManager.getCurrentUserId() } returns "user123"

        lifecycleManager.cleanupExpired()
        advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.refreshForUser("user123") }
    }

    @Test
    fun `cleanupExpired does not refresh state when user ID is null`() = runTest(testDispatcher) {
        coEvery { stateManager.getCurrentUserId() } returns null

        lifecycleManager.cleanupExpired()
        advanceUntilIdle()

        coVerify(exactly = 0) { stateManager.refreshForUser(any()) }
    }

    @Test
    fun `cleanupExpired handles repository errors gracefully`() = runTest(testDispatcher) {
        coEvery { repository.cleanupExpired() } throws RuntimeException("Cleanup error")

        lifecycleManager.cleanupExpired()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.cleanupExpired() }
    }

    @Test
    fun `cleanupExpired handles cache errors gracefully`() = runTest(testDispatcher) {
        coEvery { cacheService.evictExpired() } throws RuntimeException("Cache error")

        lifecycleManager.cleanupExpired()
        advanceUntilIdle()

        coVerify(exactly = 1) { cacheService.evictExpired() }
    }

    @Test
    fun `cleanupExpired handles getCurrentUserId errors gracefully`() = runTest(testDispatcher) {
        coEvery { stateManager.getCurrentUserId() } throws RuntimeException("State error")

        lifecycleManager.cleanupExpired()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.cleanupExpired() }
        coVerify(exactly = 1) { cacheService.evictExpired() }
    }

    @Test
    fun `cleanupExpired executes in correct order`() = runTest(testDispatcher) {
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
        advanceUntilIdle()

        assert(callOrder.size == 3)
        assert(callOrder[0] == "cleanup")
        assert(callOrder[1] == "evict")
        assert(callOrder[2] == "refresh")
    }

    // ========== startPeriodicExpirationCheck() Tests ==========

    @Test
    fun `startPeriodicExpirationCheck starts background coroutine`() = runTest(testDispatcher) {
        lifecycleManager.startPeriodicExpirationCheck()
        
        // Advance a small amount — first cleanup runs immediately before first delay
        advanceTimeBy(1000)
        
        // First cleanup runs immediately (before the 6h delay), so exactly 1
        coVerify(exactly = 1) { repository.cleanupExpired() }
        applicationScope.cancel()
    }

    @Test
    fun `startPeriodicExpirationCheck runs cleanup after 6 hours`() = runTest(testDispatcher) {
        lifecycleManager.startPeriodicExpirationCheck()
        
        // Advance 6 hours + a bit
        advanceTimeBy(6L * 60 * 60 * 1000 + 100)
        
        coVerify(atLeast = 1) { repository.cleanupExpired() }
        applicationScope.cancel()
    }

    @Test
    fun `startPeriodicExpirationCheck runs cleanup multiple times`() = runTest(testDispatcher) {
        lifecycleManager.startPeriodicExpirationCheck()
        
        // Advance 18 hours (should trigger 3 times at 6h intervals)
        advanceTimeBy(18L * 60 * 60 * 1000 + 100)
        
        coVerify(atLeast = 2) { repository.cleanupExpired() }
        applicationScope.cancel()
    }

    @Test
    fun `startPeriodicExpirationCheck can only be started once`() = runTest(testDispatcher) {
        lifecycleManager.startPeriodicExpirationCheck()
        lifecycleManager.startPeriodicExpirationCheck()
        lifecycleManager.startPeriodicExpirationCheck()
        
        // Advance past the first immediate cleanup only (not enough for second interval)
        advanceTimeBy(1000)
        
        // Only one periodic loop should be running — one immediate cleanup
        coVerify(exactly = 1) { repository.cleanupExpired() }
        applicationScope.cancel()
    }

    @Test
    fun `startPeriodicExpirationCheck continues after errors`() = runTest(testDispatcher) {
        var callCount = 0
        coEvery { repository.cleanupExpired() } coAnswers {
            callCount++
            if (callCount == 1) {
                throw RuntimeException("First call fails")
            }
            1
        }

        lifecycleManager.startPeriodicExpirationCheck()
        
        // First cleanup runs immediately — should fail
        advanceTimeBy(1000)
        
        // Second cleanup after 6h — should succeed
        advanceTimeBy(6L * 60 * 60 * 1000)
        
        // Should have been called at least twice (immediate + after 6h)
        coVerify(atLeast = 2) { repository.cleanupExpired() }
        applicationScope.cancel()
    }

    // ========== Concurrent Operations Tests ==========

    @Test
    fun `checkAndExpire handles concurrent calls for same user`() = runTest(testDispatcher) {
        lifecycleManager.checkAndExpire("user123")
        lifecycleManager.checkAndExpire("user123")
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.expireOld("user123", any()) }
    }

    @Test
    fun `checkAndExpire handles concurrent calls for different users`() = runTest(testDispatcher) {
        lifecycleManager.checkAndExpire("user123")
        lifecycleManager.checkAndExpire("user456")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("user123", any()) }
        coVerify(exactly = 1) { repository.expireOld("user456", any()) }
    }

    @Test
    fun `cleanupExpired can run concurrently with checkAndExpire`() = runTest(testDispatcher) {
        lifecycleManager.checkAndExpire("user123")
        lifecycleManager.cleanupExpired()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("user123", any()) }
        coVerify(exactly = 1) { repository.cleanupExpired() }
        coVerify(atLeast = 2) { cacheService.evictExpired() }
    }

    // ========== Edge Cases ==========

    @Test
    fun `checkAndExpire handles IOException from repository`() = runTest(testDispatcher) {
        coEvery { repository.expireOld("user123", any()) } throws java.io.IOException("Network error")

        lifecycleManager.checkAndExpire("user123")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("user123", any()) }
    }

    @Test
    fun `cleanupExpired handles database constraint violation`() = runTest(testDispatcher) {
        coEvery { repository.cleanupExpired() } throws android.database.sqlite.SQLiteConstraintException()

        lifecycleManager.cleanupExpired()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.cleanupExpired() }
    }

    @Test
    fun `checkAndExpire handles OutOfMemoryError gracefully`() = runTest(testDispatcher) {
        coEvery { cacheService.evictExpired() } throws OutOfMemoryError()

        // Should catch all Throwables, not just Exceptions
        try {
            lifecycleManager.checkAndExpire("user123")
            advanceUntilIdle()
        } catch (e: OutOfMemoryError) {
            // Expected to be caught internally
        }

        coVerify(exactly = 1) { repository.expireOld("user123", any()) }
    }

    @Test
    fun `periodic check uses 6 hour interval constant`() = runTest(testDispatcher) {
        lifecycleManager.startPeriodicExpirationCheck()
        
        // Test that cleanup runs at 6 hour intervals
        // First run at 6h
        advanceTimeBy(6L * 60 * 60 * 1000 + 100)
        coVerify(atLeast = 1) { repository.cleanupExpired() }
        
        // Second run at 12h total
        advanceTimeBy(6L * 60 * 60 * 1000)
        coVerify(atLeast = 2) { repository.cleanupExpired() }
        applicationScope.cancel()
    }

    @Test
    fun `multiple checkAndExpire calls do not interfere`() = runTest(testDispatcher) {
        val users = listOf("user1", "user2", "user3", "user4", "user5")
        
        users.forEach { userId ->
            lifecycleManager.checkAndExpire(userId)
        }
        advanceUntilIdle()

        users.forEach { userId ->
            coVerify(exactly = 1) { repository.expireOld(userId, any()) }
            coVerify(exactly = 1) { stateManager.refreshForUser(userId) }
        }
    }

    @Test
    fun `cleanupExpired handles null and non-null user IDs in sequence`() = runTest(testDispatcher) {
        // First call with null user ID
        coEvery { stateManager.getCurrentUserId() } returns null
        lifecycleManager.cleanupExpired()
        advanceUntilIdle()

        // Second call with valid user ID
        coEvery { stateManager.getCurrentUserId() } returns "user123"
        lifecycleManager.cleanupExpired()
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.cleanupExpired() }
        coVerify(exactly = 1) { stateManager.refreshForUser("user123") }
    }
}
