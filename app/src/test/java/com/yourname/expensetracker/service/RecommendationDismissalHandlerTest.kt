package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.domain.model.recommendation.RecommendationStatus
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Unit tests for RecommendationDismissalHandler.
 * Tests dismissal operations, state management, and error handling.
 *
 * Near-identical cases are table-driven; every table row rebuilds fresh mocks and failures
 * name the row. Phase 2: AI Follow-Through - Filter & Navigation Integration
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
        buildFixture()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    /** Fresh mocks + handler; table-driven tests call this once per row. */
    private fun buildFixture() {
        repository = mockk(relaxed = true)
        stateManager = mockk(relaxed = true)
        handler = RecommendationDismissalHandler(
            repository = repository,
            stateManager = stateManager,
            ioDispatcher = testDispatcher
        )
    }

    /** Runs [block] and re-throws any failure with the table row name in the message. */
    private inline fun <T> row(name: String, block: () -> T): T {
        try {
            return block()
        } catch (e: Throwable) {
            throw AssertionError("row '$name' failed: ${e.message}", e)
        }
    }

    // ========== dismiss() Tests ==========

    @Test
    fun `dismiss archives in repository and removes from state for each recommendation shape`() =
        runTest(testDispatcher) {
            val rows = listOf(
                "default recommendation" to createRecommendation(id = "rec1"),
                "high priority" to createRecommendation(id = "rec_high", priority = RecommendationPriority.HIGH),
                "low priority" to createRecommendation(id = "rec_low", priority = RecommendationPriority.LOW),
                "expired (8 days ago)" to createRecommendation(
                    id = "rec_expired",
                    status = RecommendationStatus.EXPIRED,
                    expiresAt = System.currentTimeMillis() - (8L * 24 * 60 * 60 * 1000)
                ),
                "already archived" to createRecommendation(id = "rec_dismissed", status = RecommendationStatus.ARCHIVED),
                "special characters in id" to createRecommendation(id = "rec-123_test@domain")
            )
            for ((name, recommendation) in rows) {
                row(name) {
                    buildFixture()
                    coEvery { repository.dismiss(recommendation.id) } returns Unit

                    handler.dismiss(recommendation)
                    advanceUntilIdle()

                    coVerify(exactly = 1) { repository.dismiss(recommendation.id) }
                    verify(exactly = 1) { stateManager.removeFromState(recommendation.id) }
                }
            }
        }

    @Test
    fun `dismiss persists before state update`() = runTest(testDispatcher) {
        val recommendation = createRecommendation(id = "rec1")
        val callOrder = mutableListOf<String>()

        coEvery { repository.dismiss("rec1") } answers {
            callOrder.add("repo")
        }
        every { stateManager.removeFromState("rec1") } answers {
            callOrder.add("state")
        }

        handler.dismiss(recommendation)
        advanceUntilIdle()

        assertEquals(2, callOrder.size)
        assertEquals("repo", callOrder[0])
        assertEquals("state", callOrder[1])
    }

    @Test
    fun `dismiss does not touch state and does not throw when repository persistence fails`() =
        runTest(testDispatcher) {
            val rows = listOf<Pair<String, Throwable>>(
                "RuntimeException (database error)" to RuntimeException("Database error"),
                "SocketTimeoutException" to java.net.SocketTimeoutException(),
                "IOException" to java.io.IOException("Network error"),
                "IllegalStateException" to IllegalStateException("Invalid state")
            )
            for ((name, failure) in rows) {
                row(name) {
                    buildFixture()
                    val recommendation = createRecommendation(id = "rec1")
                    coEvery { repository.dismiss("rec1") } throws failure

                    // Must not throw
                    handler.dismiss(recommendation)
                    advanceUntilIdle()

                    coVerify(exactly = 1) { repository.dismiss("rec1") }
                    verify(exactly = 0) { stateManager.removeFromState("rec1") }
                }
            }
        }

    @Test
    fun `dismiss refreshes only the owning current user when state removal fails after persistence`() =
        runTest(testDispatcher) {
            // Row: current user id -> expected forced refresh count for "user123".
            val rows = listOf("user123" to 1, "otherUser" to 0)
            for ((currentUserId, expectedRefreshes) in rows) {
                row("current user '$currentUserId'") {
                    buildFixture()
                    val recommendation = createRecommendation(id = "rec1", userId = "user123")
                    every { stateManager.removeFromState("rec1") } throws RuntimeException("State error")
                    every { stateManager.getCurrentUserId() } returns currentUserId

                    handler.dismiss(recommendation)
                    advanceUntilIdle()

                    coVerify(exactly = 1) { repository.dismiss("rec1") }
                    verify(exactly = 1) { stateManager.removeFromState("rec1") }
                    verify(exactly = 1) { stateManager.getCurrentUserId() }
                    verify(exactly = expectedRefreshes) { stateManager.refreshForUser(any(), forceRefresh = true) }
                    if (expectedRefreshes > 0) {
                        verify(exactly = 1) { stateManager.refreshForUser("user123", forceRefresh = true) }
                    }
                }
            }
        }

    // ========== dismissAndRefresh() Tests ==========

    @Test
    fun `dismissAndRefresh force refreshes the given user id exactly once`() = runTest(testDispatcher) {
        // Includes the empty user id, which must be forwarded unchanged.
        for (userId in listOf("user123", "user456", "")) {
            row("userId='$userId'") {
                buildFixture()
                coEvery { stateManager.refreshForUser(userId, forceRefresh = true) } returns Unit

                handler.dismissAndRefresh(userId)
                advanceUntilIdle()

                coVerify(exactly = 1) { stateManager.refreshForUser(userId, forceRefresh = true) }
            }
        }
    }

    @Test
    fun `dismissAndRefresh does not throw when refresh fails`() = runTest(testDispatcher) {
        coEvery { stateManager.refreshForUser("user123", forceRefresh = true) } throws RuntimeException("Refresh error")

        // Should not throw
        handler.dismissAndRefresh("user123")
        advanceUntilIdle()

        coVerify(exactly = 1) { stateManager.refreshForUser("user123", forceRefresh = true) }
    }

    @Test
    fun `dismissAndRefresh can be called multiple times`() = runTest(testDispatcher) {
        handler.dismissAndRefresh("user123")
        handler.dismissAndRefresh("user123")
        handler.dismissAndRefresh("user123")
        advanceUntilIdle()

        coVerify(exactly = 3) { stateManager.refreshForUser("user123", forceRefresh = true) }
    }

    // ========== Integration Tests ==========

    @Test
    fun `dismiss followed by dismissAndRefresh works correctly`() = runTest(testDispatcher) {
        val recommendation = createRecommendation(id = "rec1", userId = "user123")

        handler.dismiss(recommendation)
        advanceUntilIdle()

        handler.dismissAndRefresh("user123")
        advanceUntilIdle()

        verify(exactly = 1) { stateManager.removeFromState("rec1") }
        coVerify(exactly = 1) { repository.dismiss("rec1") }
        verify(exactly = 1) { stateManager.refreshForUser("user123", forceRefresh = true) }
    }

    @Test
    fun `multiple dismissals work independently`() = runTest(testDispatcher) {
        val rec1 = createRecommendation(id = "rec1")
        val rec2 = createRecommendation(id = "rec2")
        val rec3 = createRecommendation(id = "rec3")

        handler.dismiss(rec1)
        handler.dismiss(rec2)
        handler.dismiss(rec3)
        advanceUntilIdle()

        verify(exactly = 1) { stateManager.removeFromState("rec1") }
        verify(exactly = 1) { stateManager.removeFromState("rec2") }
        verify(exactly = 1) { stateManager.removeFromState("rec3") }
        coVerify(exactly = 1) { repository.dismiss("rec1") }
        coVerify(exactly = 1) { repository.dismiss("rec2") }
        coVerify(exactly = 1) { repository.dismiss("rec3") }
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
            expiresAt = expiresAt,
            createdAt = 0L,
            updatedAt = 0L,
        )
    }
}
