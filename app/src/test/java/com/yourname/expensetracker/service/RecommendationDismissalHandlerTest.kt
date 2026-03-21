package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.domain.model.recommendation.RecommendationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Unit tests for RecommendationDismissalHandler.
 * Tests dismissal operations, state management, and error handling.
 * 
 * Phase 2: AI Follow-Through - Filter & Navigation Integration
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationDismissalHandlerTest {

    private lateinit var repository: RecommendationRepository
    private lateinit var stateManager: RecommendationStateManager
    private lateinit var handler: RecommendationDismissalHandler
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        stateManager = mockk(relaxed = true)
        handler = RecommendationDismissalHandler(
            repository = repository,
            stateManager = stateManager,
            ioDispatcher = testDispatcher
        )
    }

    // ========== dismiss() Tests ==========

    @Test
    fun `dismiss removes recommendation from state manager`() = runTest {
        val recommendation = createRecommendation(id = "rec1")

        coEvery { repository.dismiss("rec1") } returns Unit

        handler.dismiss(recommendation)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.removeFromState("rec1") }
    }

    @Test
    fun `dismiss archives recommendation in repository`() = runTest {
        val recommendation = createRecommendation(id = "rec1")

        coEvery { repository.dismiss("rec1") } returns Unit

        handler.dismiss(recommendation)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.dismiss("rec1") }
    }

    @Test
    fun `dismiss updates state before repository call`() = runTest {
        val recommendation = createRecommendation(id = "rec1")
        val callOrder = mutableListOf<String>()

        coEvery { stateManager.removeFromState("rec1") } answers {
            callOrder.add("state")
        }
        coEvery { repository.dismiss("rec1") } answers {
            callOrder.add("repo")
        }

        handler.dismiss(recommendation)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, callOrder.size)
        assertEquals("state", callOrder[0])
        assertEquals("repo", callOrder[1])
    }

    @Test
    fun `dismiss handles repository errors gracefully`() = runTest {
        val recommendation = createRecommendation(id = "rec1")

        coEvery { repository.dismiss("rec1") } throws RuntimeException("Database error")

        // Should not throw exception
        handler.dismiss(recommendation)
        testDispatcher.scheduler.advanceUntilIdle()

        // State should still be updated
        coVerify(exactly = 1) { stateManager.removeFromState("rec1") }
    }

    @Test
    fun `dismiss handles state manager errors gracefully`() = runTest {
        val recommendation = createRecommendation(id = "rec1")

        coEvery { stateManager.removeFromState("rec1") } throws RuntimeException("State error")

        // Should not throw exception
        handler.dismiss(recommendation)
        testDispatcher.scheduler.advanceUntilIdle()

        // Repository should still be called
        coVerify(exactly = 1) { repository.dismiss("rec1") }
    }

    @Test
    fun `dismiss handles network timeout errors`() = runTest {
        val recommendation = createRecommendation(id = "rec1")

        coEvery { repository.dismiss("rec1") } throws java.net.SocketTimeoutException()

        handler.dismiss(recommendation)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.removeFromState("rec1") }
    }

    @Test
    fun `dismiss works with high priority recommendation`() = runTest {
        val recommendation = createRecommendation(
            id = "rec_high",
            priority = RecommendationPriority.HIGH
        )

        handler.dismiss(recommendation)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.removeFromState("rec_high") }
        coVerify(exactly = 1) { repository.dismiss("rec_high") }
    }

    @Test
    fun `dismiss works with low priority recommendation`() = runTest {
        val recommendation = createRecommendation(
            id = "rec_low",
            priority = RecommendationPriority.LOW
        )

        handler.dismiss(recommendation)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.removeFromState("rec_low") }
        coVerify(exactly = 1) { repository.dismiss("rec_low") }
    }

    @Test
    fun `dismiss handles expired recommendation`() = runTest {
        val nowMillis = System.currentTimeMillis()
        val expiredTime = nowMillis - (8L * 24 * 60 * 60 * 1000) // 8 days ago
        
        val recommendation = createRecommendation(
            id = "rec_expired",
            status = RecommendationStatus.EXPIRED,
            expiresAt = expiredTime
        )

        handler.dismiss(recommendation)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.removeFromState("rec_expired") }
        coVerify(exactly = 1) { repository.dismiss("rec_expired") }
    }

    @Test
    fun `dismiss handles already dismissed recommendation`() = runTest {
        val recommendation = createRecommendation(
            id = "rec_dismissed",
            status = RecommendationStatus.ARCHIVED
        )

        handler.dismiss(recommendation)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.dismiss("rec_dismissed") }
    }

    @Test
    fun `dismiss handles recommendation with special characters in id`() = runTest {
        val recommendation = createRecommendation(id = "rec-123_test@domain")

        handler.dismiss(recommendation)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.removeFromState("rec-123_test@domain") }
        coVerify(exactly = 1) { repository.dismiss("rec-123_test@domain") }
    }

    // ========== dismissAndRefresh() Tests ==========

    @Test
    fun `dismissAndRefresh calls stateManager refreshForUser`() = runTest {
        coEvery { stateManager.refreshForUser("user123") } returns Unit

        handler.dismissAndRefresh("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.refreshForUser("user123") }
    }

    @Test
    fun `dismissAndRefresh works with different user IDs`() = runTest {
        handler.dismissAndRefresh("user456")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.refreshForUser("user456") }
    }

    @Test
    fun `dismissAndRefresh handles errors gracefully`() = runTest {
        coEvery { stateManager.refreshForUser("user123") } throws RuntimeException("Refresh error")

        // Should not throw
        handler.dismissAndRefresh("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.refreshForUser("user123") }
    }

    @Test
    fun `dismissAndRefresh works with empty user ID`() = runTest {
        handler.dismissAndRefresh("")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.refreshForUser("") }
    }

    @Test
    fun `dismissAndRefresh can be called multiple times`() = runTest {
        handler.dismissAndRefresh("user123")
        handler.dismissAndRefresh("user123")
        handler.dismissAndRefresh("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 3) { stateManager.refreshForUser("user123") }
    }

    // ========== Integration Tests ==========

    @Test
    fun `dismiss followed by dismissAndRefresh works correctly`() = runTest {
        val recommendation = createRecommendation(id = "rec1", userId = "user123")

        handler.dismiss(recommendation)
        testDispatcher.scheduler.advanceUntilIdle()

        handler.dismissAndRefresh("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.removeFromState("rec1") }
        coVerify(exactly = 1) { repository.dismiss("rec1") }
        coVerify(exactly = 1) { stateManager.refreshForUser("user123") }
    }

    @Test
    fun `multiple dismissals work independently`() = runTest {
        val rec1 = createRecommendation(id = "rec1")
        val rec2 = createRecommendation(id = "rec2")
        val rec3 = createRecommendation(id = "rec3")

        handler.dismiss(rec1)
        handler.dismiss(rec2)
        handler.dismiss(rec3)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.removeFromState("rec1") }
        coVerify(exactly = 1) { stateManager.removeFromState("rec2") }
        coVerify(exactly = 1) { stateManager.removeFromState("rec3") }
        coVerify(exactly = 1) { repository.dismiss("rec1") }
        coVerify(exactly = 1) { repository.dismiss("rec2") }
        coVerify(exactly = 1) { repository.dismiss("rec3") }
    }

    @Test
    fun `dismiss handles concurrent calls correctly`() = runTest {
        val rec1 = createRecommendation(id = "rec1")
        val rec2 = createRecommendation(id = "rec2")

        // Launch concurrent dismissals
        handler.dismiss(rec1)
        handler.dismiss(rec2)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.removeFromState("rec1") }
        coVerify(exactly = 1) { stateManager.removeFromState("rec2") }
    }

    // ========== Error Recovery Tests ==========

    @Test
    fun `dismiss continues after IOException`() = runTest {
        val recommendation = createRecommendation(id = "rec1")

        coEvery { repository.dismiss("rec1") } throws java.io.IOException("Network error")

        handler.dismiss(recommendation)
        testDispatcher.scheduler.advanceUntilIdle()

        // State should still be updated
        coVerify(exactly = 1) { stateManager.removeFromState("rec1") }
    }

    @Test
    fun `dismiss continues after IllegalStateException`() = runTest {
        val recommendation = createRecommendation(id = "rec1")

        coEvery { repository.dismiss("rec1") } throws IllegalStateException("Invalid state")

        handler.dismiss(recommendation)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.removeFromState("rec1") }
    }

    @Test
    fun `dismissAndRefresh continues after repository error`() = runTest {
        coEvery { stateManager.refreshForUser("user123") } throws RuntimeException()

        // Should complete without throwing
        handler.dismissAndRefresh("user123")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.refreshForUser("user123") }
    }

    // ========== Helper Functions ==========

    private fun createRecommendation(
        id: String = "rec_${System.nanoTime()}",
        userId: String = "user123",
        priority: RecommendationPriority = RecommendationPriority.MEDIUM,
        status: RecommendationStatus = RecommendationStatus.ACTIVE,
        expiresAt: Long = System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000)
    ): DashboardFollowThroughRecommendation {
        return DashboardFollowThroughRecommendation(
            id = id,
            userId = userId,
            recommendationText = "Test recommendation",
            navigationTarget = "TRANSACTION_LIST",
            filterCriteria = "{}",
            priority = priority,
            category = "GENERAL",
            sourceArtifactId = "",
            status = status,
            expiresAt = expiresAt
        )
    }
}
