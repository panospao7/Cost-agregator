package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.domain.model.recommendation.RecommendationStatus
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused unit tests for [RecommendationStateManager].
 *
 * Covers:
 * - Basic refresh/dismiss/clear lifecycle
 * - Generation-guard: stale refresh cannot overwrite newer user state
 * - Current-user safety: dismiss/removeFromState/clearForUser respect user context
 * - Deterministic overlap tests using [CompletableDeferred] gates that hold one
 *   refresh in-flight while a competing mutation (user switch, clear, dismiss,
 *   removeFromState) executes, then verify stale publication is rejected.
 *
 * A.8 Batch 4 — thread-safety and state-publication guards (ISSUE-7 + ISSUE-8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationStateManagerTest {

    private lateinit var repository: RecommendationRepository
    private lateinit var timeProvider: TimeProvider
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var applicationScope: TestScope
    private lateinit var manager: RecommendationStateManager

    private val nowMillis = System.currentTimeMillis()
    private val futureExpiry = nowMillis + (7L * 24 * 60 * 60 * 1000)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        buildFixture()
    }

    /** Fresh mocks + manager; table-driven tests call this once per row. */
    private fun buildFixture() {
        if (this::applicationScope.isInitialized) applicationScope.cancel()
        repository = mockk(relaxed = true)

        // Prevent NPE from RecommendationRepository's default-param accessing timeProvider
        timeProvider = mockk(relaxed = true)
        every { timeProvider.now() } returns nowMillis
        try {
            RecommendationRepository::class.java.getDeclaredField("timeProvider").apply {
                isAccessible = true
                set(repository, timeProvider)
            }
        } catch (_: NoSuchFieldException) {
            // Field may not exist; safe to ignore
        }

        applicationScope = TestScope(testDispatcher)
        manager = RecommendationStateManager(
            repository = repository,
            timeProvider = timeProvider,
            applicationScope = applicationScope
        )
    }

    @After
    fun teardown() {
        applicationScope.cancel()
        Dispatchers.resetMain()
    }

    /** Runs [block] and re-throws any failure with the table row name in the message. */
    private inline fun <T> row(name: String, block: () -> T): T {
        try {
            return block()
        } catch (e: Throwable) {
            throw AssertionError("row '$name' failed: ${e.message}", e)
        }
    }

    // ========== Basic Refresh Tests ==========

    @Test
    fun `refreshForUser publishes active recommendations`() = runTest(testDispatcher) {
        // Before any refresh there is no current user.
        assertNull(manager.getCurrentUserId())

        val recs = listOf(createRecommendation("r1"), createRecommendation("r2"))
        coEvery { repository.getActiveForUser("user1") } returns recs

        manager.refreshForUser("user1")
        advanceUntilIdle()

        assertEquals(2, manager.recommendations.value.size)
        assertEquals("user1", manager.getCurrentUserId())
    }

    @Test
    fun `refreshForUser for the already-active user re-queries storage and publishes fresh data`() =
        runTest(testDispatcher) {
            // Row: second-call forceRefresh flag. Both the plain same-user reload (invalidate
            // path) and the forced reload must expire + re-query and publish the new data.
            for (forceRefresh in listOf(false, true)) {
                row("second refresh forceRefresh=$forceRefresh") {
                    buildFixture()
                    coEvery { repository.getActiveForUser("user1") } returnsMany listOf(
                        listOf(createRecommendation("initial", userId = "user1")),
                        listOf(createRecommendation("reloaded", userId = "user1"))
                    )

                    manager.refreshForUser("user1")
                    advanceUntilIdle()
                    assertEquals("initial", manager.recommendations.value.single().id)

                    manager.refreshForUser("user1", forceRefresh = forceRefresh)
                    advanceUntilIdle()

                    assertEquals("reloaded", manager.recommendations.value.single().id)
                    coVerify(exactly = 2) { repository.expireOld("user1", nowMillis) }
                    coVerify(exactly = 2) { repository.getActiveForUser("user1") }
                }
            }
        }

    @Test
    fun `invalidate all path refreshes against empty active set after expireAll`() = runTest(testDispatcher) {
        coEvery { repository.getActiveForUser("user1") } returns emptyList()

        manager.refreshForUser("user1")
        advanceUntilIdle()

        assertTrue(manager.recommendations.value.isEmpty())
        coVerify(exactly = 1) { repository.expireOld("user1", nowMillis) }
        coVerify(exactly = 1) { repository.getActiveForUser("user1") }
    }

    @Test
    fun `refreshForUser publishes at most 5 sorted by priority`() = runTest(testDispatcher) {
        val recs = (1..8).map { i ->
            createRecommendation(
                id = "r$i",
                priority = if (i % 2 == 0) RecommendationPriority.HIGH else RecommendationPriority.LOW
            )
        }
        coEvery { repository.getActiveForUser("user1") } returns recs

        manager.refreshForUser("user1")
        advanceUntilIdle()

        val published = manager.recommendations.value
        assertEquals(5, published.size)
        assertTrue(published[0].priority == RecommendationPriority.HIGH)
    }

    @Test
    fun `refreshForUser publishes empty on repository error`() = runTest(testDispatcher) {
        coEvery { repository.getActiveForUser("user1") } throws RuntimeException("DB error")

        manager.refreshForUser("user1")
        advanceUntilIdle()

        assertTrue(manager.recommendations.value.isEmpty())
    }

    // ========== Generation-Guard Tests ==========
    //
    // The stale-refresh / generation-guard races (user switch, same-user refresh, clear,
    // dismiss, removeFromState, clearForUser) are covered ONLY by the gated
    // "Deterministic Overlap Tests" section below. Earlier un-gated variants ran the two
    // refreshes strictly one after the other on a StandardTestDispatcher, so no overlap
    // ever happened; they were removed as vacuous/duplicate (see the gated equivalents).

    // ========== Dismiss Tests ==========

    @Test
    fun `dismiss removes item from state and calls repository`() = runTest(testDispatcher) {
        val recs = listOf(createRecommendation("r1"), createRecommendation("r2"))
        coEvery { repository.getActiveForUser("user1") } returns recs

        manager.refreshForUser("user1")
        advanceUntilIdle()

        // Set up refresh to only return r2
        coEvery { repository.getActiveForUser("user1") } returns listOf(createRecommendation("r2"))

        manager.dismiss("r1")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.dismiss("r1") }
        // The follow-up forced refresh publishes the repository's remaining item only.
        assertEquals(listOf("r2"), manager.recommendations.value.map { it.id })
    }

    @Test
    fun `dismiss handles error gracefully`() = runTest(testDispatcher) {
        coEvery { repository.getActiveForUser("user1") } returns listOf(createRecommendation("r1"))
        manager.refreshForUser("user1")
        advanceUntilIdle()

        coEvery { repository.dismiss("r1") } throws RuntimeException("fail")

        manager.dismiss("r1")
        advanceUntilIdle()

        // Should not crash
        coVerify(exactly = 1) { repository.dismiss("r1") }
    }

    // ========== removeFromState Tests ==========

    /**
     * ISSUE-9 regression: [removeFromState] must complete the in-memory mutation
     * inline, before it returns — no [advanceUntilIdle] required. This test asserts
     * that [recommendations].value reflects the removal immediately after the call.
     *
     * A.8 Batch 4 — behavioral synchrony contract for the non-suspend state owner.
     */
    @Test
    fun `removeFromState is immediately synchronous - removal visible without advanceUntilIdle`() =
        runTest(testDispatcher) {
            val recs = listOf(createRecommendation("r1"), createRecommendation("r2"))
            coEvery { repository.getActiveForUser("user1") } returns recs

            manager.refreshForUser("user1")
            advanceUntilIdle()

            assertEquals(2, manager.recommendations.value.size)

            // Call removeFromState — mutation must be visible immediately, with NO
            // advanceUntilIdle() or coroutine pump between the call and the assertion.
            manager.removeFromState("r1")

            val result = manager.recommendations.value
            assertEquals(
                "recommendations.value must reflect removal before any coroutine pump",
                1,
                result.size
            )
            assertEquals("r2", result[0].id)
            assertTrue("r1 must be absent immediately after removeFromState", result.none { it.id == "r1" })

            // The removal must also remain stable after the coroutines are pumped.
            advanceUntilIdle()
            assertEquals(1, manager.recommendations.value.size)
            assertEquals("r2", manager.recommendations.value[0].id)
        }

    // ========== Clear Tests ==========

    /**
     * ISSUE-10 regression: [clear] must perform its in-memory mutation inline,
     * before it returns — no [advanceUntilIdle] required. This test asserts that
     * [recommendations].value is empty and [getCurrentUserId] returns null
     * immediately after [clear] returns, without any coroutine pump.
     *
     * A.8 Batch 4 — public-API stability for the synchronous clear contract.
     */
    @Test
    fun `clear is immediately synchronous - state empty and userId null without advanceUntilIdle`() =
        runTest(testDispatcher) {
            coEvery { repository.getActiveForUser("user1") } returns listOf(createRecommendation("r1"))
            manager.refreshForUser("user1")
            advanceUntilIdle()

            assertEquals(1, manager.recommendations.value.size)
            assertEquals("user1", manager.getCurrentUserId())

            // Call clear() — mutation must be visible immediately, with NO
            // advanceUntilIdle() between the call and the assertions.
            manager.clear()

            assertTrue(
                "recommendations.value must be empty immediately after clear() returns",
                manager.recommendations.value.isEmpty()
            )
            assertNull(
                "getCurrentUserId() must return null immediately after clear() returns",
                manager.getCurrentUserId()
            )

            // Still cleared after the coroutines are pumped.
            advanceUntilIdle()
            assertTrue(manager.recommendations.value.isEmpty())
            assertNull(manager.getCurrentUserId())
        }

    // ========== clearForUser Tests ==========

    @Test
    fun `clearForUser always clears the repository but clears in-memory state only for the matching user`() =
        runTest(testDispatcher) {
            // Row: user passed to clearForUser -> in-memory recommendations expected afterwards
            // (current user is "user1" with one published item).
            val rows = listOf("user1" to 0, "user2" to 1)
            for ((targetUser, expectedRemaining) in rows) {
                row("clearForUser('$targetUser')") {
                    buildFixture()
                    coEvery { repository.getActiveForUser("user1") } returns listOf(createRecommendation("r1"))
                    manager.refreshForUser("user1")
                    advanceUntilIdle()

                    manager.clearForUser(targetUser)
                    advanceUntilIdle()

                    assertEquals(expectedRemaining, manager.recommendations.value.size)
                    coVerify { repository.clearForUser(targetUser) }
                }
            }
        }

    @Test
    fun `clearForUser handles error gracefully`() = runTest(testDispatcher) {
        coEvery { repository.clearForUser("user1") } throws RuntimeException("fail")

        manager.clearForUser("user1")
        advanceUntilIdle()

        // Should not crash
        coVerify(exactly = 1) { repository.clearForUser("user1") }
    }

    // ========== Concurrent Mutation Tests ==========

    @Test
    fun `rapid user switches settle on last user`() = runTest(testDispatcher) {
        coEvery { repository.getActiveForUser("user1") } returns listOf(createRecommendation("r-u1", userId = "user1"))
        coEvery { repository.getActiveForUser("user2") } returns listOf(createRecommendation("r-u2", userId = "user2"))
        coEvery { repository.getActiveForUser("user3") } returns listOf(createRecommendation("r-u3", userId = "user3"))

        manager.refreshForUser("user1")
        manager.refreshForUser("user2")
        manager.refreshForUser("user3")
        advanceUntilIdle()

        // The final state should be for user3
        assertEquals("user3", manager.getCurrentUserId())
    }

    @Test
    fun `dismiss followed by clear leaves state empty`() = runTest(testDispatcher) {
        coEvery { repository.getActiveForUser("user1") } returns listOf(createRecommendation("r1"))
        manager.refreshForUser("user1")
        advanceUntilIdle()

        manager.dismiss("r1")
        manager.clear()
        advanceUntilIdle()

        assertTrue(manager.recommendations.value.isEmpty())
        assertNull(manager.getCurrentUserId())
    }

    // ========== Deterministic Overlap Tests (ISSUE-8) ==========
    // These tests use CompletableDeferred gates to hold a refresh coroutine
    // suspended mid-flight while a competing mutation executes, then release
    // the gate and verify the stale result is rejected by the generation guard.

    /**
     * Holds user1's refresh in-flight (blocked on [gate]) while user2 is switched in.
     * After the gate is released, the user1 refresh result must be discarded because
     * the generation guard detects a newer mutation.
     */
    @Test
    fun `overlap - stale user1 refresh blocked by gate is discarded after user switch`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()

            val user1Recs = listOf(createRecommendation("stale-r1", userId = "user1"))
            val user2Recs = listOf(createRecommendation("fresh-r2", userId = "user2"))

            // user1's getActiveForUser suspends until gate is opened
            coEvery { repository.getActiveForUser("user1") } coAnswers {
                gate.await()
                user1Recs
            }
            coEvery { repository.getActiveForUser("user2") } returns user2Recs

            // Start user1 refresh — it will block inside getActiveForUser
            manager.refreshForUser("user1")
            // Advance enough to let the coroutine reach the suspension point inside getActiveForUser
            advanceUntilIdle()

            // While user1 refresh is in-flight (gated), switch to user2 — bumps generation
            manager.refreshForUser("user2")
            advanceUntilIdle()

            // user2 refresh completes first (not gated); state is now user2
            assertEquals("user2", manager.getCurrentUserId())
            assertEquals(1, manager.recommendations.value.size)
            assertEquals("fresh-r2", manager.recommendations.value[0].id)

            // Now release the gate — user1's refresh will complete, but its capturedGeneration
            // is now stale; the generation guard must reject it
            gate.complete(Unit)
            advanceUntilIdle()

            // State must still be user2 — the stale user1 result was discarded
            assertEquals("user2", manager.getCurrentUserId())
            assertEquals(1, manager.recommendations.value.size)
            assertEquals("fresh-r2", manager.recommendations.value[0].id)
        }

    @Test
    fun `overlap - stale same-user refresh blocked by gate is discarded after newer same-user refresh`() =
        runTest(testDispatcher) {
            val firstGate = CompletableDeferred<Unit>()
            val firstResponse = listOf(createRecommendation("stale-r1", userId = "user1"))
            val secondResponse = listOf(createRecommendation("fresh-r2", userId = "user1"))

            coEvery { repository.getActiveForUser("user1") } coAnswers {
                firstGate.await()
                firstResponse
            } andThen secondResponse

            manager.refreshForUser("user1")
            advanceUntilIdle()

            manager.refreshForUser("user1")
            advanceUntilIdle()

            assertEquals("user1", manager.getCurrentUserId())
            assertEquals(1, manager.recommendations.value.size)
            assertEquals("fresh-r2", manager.recommendations.value.single().id)

            firstGate.complete(Unit)
            advanceUntilIdle()

            assertEquals("user1", manager.getCurrentUserId())
            assertEquals(1, manager.recommendations.value.size)
            assertEquals("fresh-r2", manager.recommendations.value.single().id)
            coVerify(exactly = 2) { repository.getActiveForUser("user1") }
        }

    /**
     * Holds user1's refresh in-flight (blocked on a [CompletableDeferred] gate - no timers or
     * sleeps) while a competing state-clearing mutation executes. After the gate is released,
     * the stale user1 result must be discarded because the mutation bumped the generation.
     *
     * Rows: clear() (also nulls the user id), removeFromState("r1"), clearForUser("user1").
     * No prior state exists, so the in-memory list must be empty before and after release.
     */
    @Test
    fun `overlap - stale refresh blocked by gate is discarded after clear, removeFromState and clearForUser`() =
        runTest(testDispatcher) {
            class Row(val name: String, val expectNullUserId: Boolean, val mutate: () -> Unit)

            val rows = listOf(
                Row("clear", true) { manager.clear() },
                Row("removeFromState", false) { manager.removeFromState("r1") },
                Row("clearForUser", false) { manager.clearForUser("user1") }
            )

            for (case in rows) {
                row(case.name) {
                    buildFixture()
                    val gate = CompletableDeferred<Unit>()
                    val staleRecs = listOf(
                        createRecommendation("r1", userId = "user1"),
                        createRecommendation("r2", userId = "user1")
                    )
                    coEvery { repository.getActiveForUser("user1") } coAnswers {
                        gate.await()
                        staleRecs
                    }

                    // Start user1 refresh - it blocks inside getActiveForUser
                    manager.refreshForUser("user1")
                    advanceUntilIdle()

                    // While the refresh is in-flight, the competing mutation bumps the generation
                    case.mutate()
                    advanceUntilIdle()

                    if (case.expectNullUserId) assertNull(manager.getCurrentUserId())
                    assertTrue(manager.recommendations.value.isEmpty())

                    // Release the gate - the stale result must be rejected
                    gate.complete(Unit)
                    advanceUntilIdle()

                    if (case.expectNullUserId) assertNull(manager.getCurrentUserId())
                    assertTrue(
                        "Stale refresh must not publish after ${case.name} bumped generation",
                        manager.recommendations.value.isEmpty()
                    )
                }
            }
        }

    /**
     * Holds user1's refresh in-flight (blocked on [gate]) while dismiss() executes.
     * After the gate is released, the stale refresh result (containing the dismissed item)
     * must be discarded because dismiss() bumped the generation.
     */
    @Test
    fun `overlap - stale refresh blocked by gate is discarded after dismiss`() =
        runTest(testDispatcher) {
            // Phase 1: initial load — not gated
            val initialRecs = listOf(
                createRecommendation("r1", userId = "user1"),
                createRecommendation("r2", userId = "user1")
            )
            coEvery { repository.getActiveForUser("user1") } returns initialRecs

            manager.refreshForUser("user1")
            advanceUntilIdle()
            assertEquals(2, manager.recommendations.value.size)

            // Phase 2: set up a gated force-refresh that dismiss() will trigger
            val gate = CompletableDeferred<Unit>()
            val staleRecs = listOf(
                createRecommendation("r1", userId = "user1"),
                createRecommendation("r2", userId = "user1")
            )

            // The force-refresh launched by dismiss() will block here
            coEvery { repository.getActiveForUser("user1") } coAnswers {
                gate.await()
                staleRecs
            }
            // Also gate expireOld so the forced refresh stays suspended
            coEvery { repository.expireOld("user1") } coAnswers {
                // allow this to proceed normally
            }

            // dismiss("r1") will: remove r1 from state, bump generation, then launch force-refresh
            manager.dismiss("r1")
            // Advance to let dismiss's internal launch reach the gated getActiveForUser
            advanceUntilIdle()

            // At this point: r1 is gone from state, force-refresh is suspended inside gate.await()
            // State should have r2 only (dismiss removed r1 optimistically)
            val stateAfterDismiss = manager.recommendations.value
            assertTrue("r1 should be removed by dismiss", stateAfterDismiss.none { it.id == "r1" })

            // Now perform removeFromState("r2") — bumps generation again
            manager.removeFromState("r2")
            advanceUntilIdle()

            assertTrue("r2 should be removed by removeFromState", manager.recommendations.value.isEmpty())

            // Release gate — the stale force-refresh wants to publish [r1, r2] but its
            // capturedGeneration is now older than the generation bumped by removeFromState
            gate.complete(Unit)
            advanceUntilIdle()

            // Stale refresh must be rejected — state must remain empty
            assertTrue(
                "Stale force-refresh must not re-add r1 or r2",
                manager.recommendations.value.isEmpty()
            )
        }

    /**
     * Two concurrent removeFromState calls must not lose either removal.
     * Both r1 and r2 are removed without the second call inadvertently re-adding r1.
     */
    @Test
    fun `overlap - concurrent removeFromState calls do not lose either removal`() =
        runTest(testDispatcher) {
            val recs = listOf(
                createRecommendation("r1", userId = "user1"),
                createRecommendation("r2", userId = "user1"),
                createRecommendation("r3", userId = "user1")
            )
            coEvery { repository.getActiveForUser("user1") } returns recs

            manager.refreshForUser("user1")
            advanceUntilIdle()
            assertEquals(3, manager.recommendations.value.size)

            // removeFromState is now synchronous — call directly, no launch wrapper needed
            manager.removeFromState("r1")
            manager.removeFromState("r2")
            advanceUntilIdle()

            val result = manager.recommendations.value
            assertTrue("r1 must be removed", result.none { it.id == "r1" })
            assertTrue("r2 must be removed", result.none { it.id == "r2" })
            assertEquals("Only r3 should remain", 1, result.size)
            assertEquals("r3", result[0].id)
        }

    /**
     * Concurrent dismiss + removeFromState must not lose each other's removals.
     */
    @Test
    fun `overlap - concurrent dismiss and removeFromState do not lose either removal`() =
        runTest(testDispatcher) {
            val recs = listOf(
                createRecommendation("r1", userId = "user1"),
                createRecommendation("r2", userId = "user1")
            )
            coEvery { repository.getActiveForUser("user1") } returns recs

            manager.refreshForUser("user1")
            advanceUntilIdle()
            assertEquals(2, manager.recommendations.value.size)

            // Prevent the force-refresh from dismiss from re-adding items
            coEvery { repository.getActiveForUser("user1") } returns emptyList()

            // Concurrent dismiss("r1") and removeFromState("r2")
            // removeFromState is now synchronous — call directly, no launch wrapper needed
            manager.dismiss("r1")
            manager.removeFromState("r2")
            advanceUntilIdle()

            val result = manager.recommendations.value
            assertTrue("r1 must be removed by dismiss", result.none { it.id == "r1" })
            assertTrue("r2 must be removed by removeFromState", result.none { it.id == "r2" })
        }

    // ========== Helper ==========

    private fun createRecommendation(
        id: String = "rec_${System.nanoTime()}",
        userId: String = "user1",
        priority: RecommendationPriority = RecommendationPriority.MEDIUM,
        status: RecommendationStatus = RecommendationStatus.ACTIVE,
        expiresAt: Long = futureExpiry
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
            createdAt = nowMillis,
            expiresAt = expiresAt,
            updatedAt = 0L,
        )
    }
}
