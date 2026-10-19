package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.repository.RecommendationRepository
import com.yourname.expensetracker.domain.analytics.SpendingThresholdCalculator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for RecommendationLifecycleManager.
 * Tests expiration checking, cleanup operations, and periodic background tasks.
 *
 * Failure handling is table-driven (one row per failing step and throwable), including the
 * rule that CancellationException must always propagate while regular exceptions are
 * logged and swallowed. Each row rebuilds fresh mocks so rows cannot leak state.
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
        buildFixture()
    }

    @After
    fun teardown() {
        applicationScope.cancel()
        Dispatchers.resetMain()
    }

    /** Builds fresh mocks + manager. Table-driven tests call this once per row. */
    private fun buildFixture() {
        if (this::applicationScope.isInitialized) applicationScope.cancel()
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

    /** Runs [block] and re-throws any failure with the table row name in the message. */
    private inline fun <T> row(name: String, block: () -> T): T {
        try {
            return block()
        } catch (e: Throwable) {
            throw AssertionError("row '$name' failed: ${e.message}", e)
        }
    }

    // ========== checkAndExpire() ==========

    @Test
    fun `checkAndExpire runs expireOld, evictExpired and refresh exactly once for each user id`() =
        runTest(testDispatcher) {
            // Includes the empty user id, which must still be forwarded unchanged.
            for (userId in listOf("user123", "user456", "")) {
                row("userId='$userId'") {
                    buildFixture()

                    lifecycleManager.checkAndExpire(userId)
                    advanceUntilIdle()

                    coVerify(exactly = 1) { repository.expireOld(userId, any()) }
                    coVerify(exactly = 1) { cacheService.evictExpired() }
                    coVerify(exactly = 1) { stateManager.refreshForUser(userId) }
                }
            }
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

        assertEquals(listOf("expireOld", "evictExpired", "refresh"), callOrder)
    }

    @Test
    fun `checkAndExpire can be called repeatedly for the same user`() = runTest(testDispatcher) {
        // Once with idle in between, once back-to-back; both must run every step each time.
        lifecycleManager.checkAndExpire("user123")
        advanceUntilIdle()
        lifecycleManager.checkAndExpire("user123")
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.expireOld("user123", any()) }
        coVerify(exactly = 2) { cacheService.evictExpired() }
        coVerify(exactly = 2) { stateManager.refreshForUser("user123") }

        lifecycleManager.checkAndExpire("user123")
        lifecycleManager.checkAndExpire("user123")
        advanceUntilIdle()

        coVerify(exactly = 4) { repository.expireOld("user123", any()) }
    }

    @Test
    fun `multiple checkAndExpire calls for different users do not interfere`() = runTest(testDispatcher) {
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

    // ========== cleanupExpired() ==========

    @Test
    fun `cleanupExpired refreshes state only when a user id is available`() = runTest(testDispatcher) {
        // Row: current user id -> expected number of refreshForUser("user123") calls.
        val rows = listOf<Pair<String?, Int>>("user123" to 1, null to 0)
        for ((currentUserId, expectedRefreshes) in rows) {
            row("currentUserId=$currentUserId") {
                buildFixture()
                coEvery { repository.cleanupExpired() } returns 1
                coEvery { stateManager.getCurrentUserId() } returns currentUserId

                lifecycleManager.cleanupExpired()
                advanceUntilIdle()

                coVerify(exactly = 1) { repository.cleanupExpired() }
                coVerify(exactly = 1) { cacheService.evictExpired() }
                coVerify(exactly = expectedRefreshes) { stateManager.refreshForUser(any()) }
                if (currentUserId != null) {
                    coVerify(exactly = 1) { stateManager.refreshForUser(currentUserId) }
                }
            }
        }
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

        assertEquals(listOf("cleanup", "evict", "refresh"), callOrder)
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

    @Test
    fun `cleanupExpired can run together with checkAndExpire`() = runTest(testDispatcher) {
        lifecycleManager.checkAndExpire("user123")
        lifecycleManager.cleanupExpired()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.expireOld("user123", any()) }
        coVerify(exactly = 1) { repository.cleanupExpired() }
        coVerify(atLeast = 2) { cacheService.evictExpired() }
    }

    // ========== startPeriodicExpirationCheck() ==========

    @Test
    fun `periodic check runs immediately then once per 6 hour interval`() = runTest(testDispatcher) {
        val sixHoursMs = 6L * 60 * 60 * 1000

        lifecycleManager.startPeriodicExpirationCheck()

        // First cleanup runs immediately (before the first delay), not again until 6h.
        advanceTimeBy(1000)
        coVerify(exactly = 1) { repository.cleanupExpired() }

        // Second run at the 6h mark.
        advanceTimeBy(sixHoursMs)
        coVerify(exactly = 2) { repository.cleanupExpired() }

        // Third run at the 12h mark.
        advanceTimeBy(sixHoursMs)
        coVerify(exactly = 3) { repository.cleanupExpired() }
        applicationScope.cancel()
    }

    @Test
    fun `startPeriodicExpirationCheck can only be started once`() = runTest(testDispatcher) {
        lifecycleManager.startPeriodicExpirationCheck()
        lifecycleManager.startPeriodicExpirationCheck()
        lifecycleManager.startPeriodicExpirationCheck()

        // Advance past the first immediate cleanup only (not enough for second interval)
        advanceTimeBy(1000)

        // Only one periodic loop should be running - one immediate cleanup
        coVerify(exactly = 1) { repository.cleanupExpired() }
        applicationScope.cancel()
    }

    @Test
    fun `periodic loop outcome after a failing first cleanup`() = runTest(testDispatcher) {
        // Row: first-call failure -> total cleanup calls after two full 6h periods.
        // Regular exception: logged, loop continues (immediate + 6h + 12h = 3).
        // CancellationException: must end the loop instead of being swallowed (1).
        val rows = listOf<Triple<String, Throwable, Int>>(
            Triple("RuntimeException keeps looping", RuntimeException("First call fails"), 3),
            Triple(
                "CancellationException terminates the loop",
                CancellationException("cancelled in periodic cleanup"),
                1
            )
        )
        for ((name, firstFailure, expectedCalls) in rows) {
            row(name) {
                buildFixture()
                var callCount = 0
                coEvery { repository.cleanupExpired() } coAnswers {
                    callCount++
                    if (callCount == 1) throw firstFailure
                    1
                }

                lifecycleManager.startPeriodicExpirationCheck()
                advanceTimeBy(12L * 60 * 60 * 1000 + 100)

                coVerify(exactly = expectedCalls) { repository.cleanupExpired() }
                applicationScope.cancel()
            }
        }
    }

    // ========== Failure handling table (regular exception swallowed / CancellationException propagates) ==========
    //
    // Production: checkAndExpire / cleanupExpired / refreshThreshold each
    // `catch (e: Exception) { CancellationSafe.rethrowIfCancellation(e); ... }`.
    // Every propagating row fails if that rethrow is replaced by a swallow; every
    // non-propagating row fails if regular exceptions start escaping to the caller.
    // Errors (e.g. OutOfMemoryError) are deliberately not caught by production.

    private enum class Op { CHECK, CLEANUP, THRESHOLD }

    private class FailureRow(
        val name: String,
        val op: Op,
        val propagatesCancellation: Boolean,
        val arrange: () -> Unit,
        val verify: () -> Unit
    )

    private fun failureRows(): List<FailureRow> = listOf(
        // ---- checkAndExpire: regular exceptions are swallowed, later steps are skipped ----
        FailureRow("check: repository RuntimeException", Op.CHECK, false,
            { coEvery { repository.expireOld("user123", any()) } throws RuntimeException("Database error") },
            {
                coVerify(exactly = 1) { repository.expireOld("user123", any()) }
                coVerify(exactly = 0) { cacheService.evictExpired() }
                coVerify(exactly = 0) { stateManager.refreshForUser(any(), any()) }
            }),
        FailureRow("check: repository IOException", Op.CHECK, false,
            { coEvery { repository.expireOld("user123", any()) } throws java.io.IOException("Network error") },
            {
                coVerify(exactly = 1) { repository.expireOld("user123", any()) }
                coVerify(exactly = 0) { cacheService.evictExpired() }
            }),
        FailureRow("check: repository IllegalStateException (control)", Op.CHECK, false,
            { coEvery { repository.expireOld("user123", any()) } throws IllegalStateException("regular failure") },
            {
                coVerify(exactly = 1) { repository.expireOld("user123", any()) }
                coVerify(exactly = 0) { cacheService.evictExpired() }
            }),
        FailureRow("check: cache eviction RuntimeException", Op.CHECK, false,
            { coEvery { cacheService.evictExpired() } throws RuntimeException("Cache error") },
            {
                coVerify(exactly = 1) { repository.expireOld("user123", any()) }
                coVerify(exactly = 1) { cacheService.evictExpired() }
                coVerify(exactly = 0) { stateManager.refreshForUser(any(), any()) }
            }),
        FailureRow("check: state refresh RuntimeException", Op.CHECK, false,
            { every { stateManager.refreshForUser("user123", any()) } throws RuntimeException("State error") },
            {
                coVerify(exactly = 1) { repository.expireOld("user123", any()) }
                coVerify(exactly = 1) { cacheService.evictExpired() }
                coVerify(exactly = 1) { stateManager.refreshForUser("user123", any()) }
            }),
        // ---- checkAndExpire: CancellationException propagates and aborts later steps ----
        FailureRow("check: CancellationException from repository expireOld", Op.CHECK, true,
            { coEvery { repository.expireOld("user123", any()) } throws CancellationException("cancelled in expireOld") },
            {
                coVerify(exactly = 0) { cacheService.evictExpired() }
                coVerify(exactly = 0) { stateManager.refreshForUser(any(), any()) }
            }),
        FailureRow("check: CancellationException from cache evictExpired", Op.CHECK, true,
            { coEvery { cacheService.evictExpired() } throws CancellationException("cancelled in evictExpired") },
            { coVerify(exactly = 0) { stateManager.refreshForUser(any(), any()) } }),
        FailureRow("check: CancellationException from state refresh", Op.CHECK, true,
            { every { stateManager.refreshForUser("user123", any()) } throws CancellationException("cancelled in refresh") },
            {}),
        // ---- cleanupExpired: regular exceptions are swallowed ----
        FailureRow("cleanup: repository RuntimeException", Op.CLEANUP, false,
            { coEvery { repository.cleanupExpired() } throws RuntimeException("Cleanup error") },
            {
                coVerify(exactly = 1) { repository.cleanupExpired() }
                coVerify(exactly = 0) { cacheService.evictExpired() }
            }),
        FailureRow("cleanup: repository SQLiteConstraintException", Op.CLEANUP, false,
            { coEvery { repository.cleanupExpired() } throws android.database.sqlite.SQLiteConstraintException() },
            { coVerify(exactly = 1) { repository.cleanupExpired() } }),
        FailureRow("cleanup: repository IllegalStateException (control)", Op.CLEANUP, false,
            { coEvery { repository.cleanupExpired() } throws IllegalStateException("regular failure") },
            {
                coVerify(exactly = 1) { repository.cleanupExpired() }
                coVerify(exactly = 0) { cacheService.evictExpired() }
            }),
        FailureRow("cleanup: cache eviction RuntimeException", Op.CLEANUP, false,
            { coEvery { cacheService.evictExpired() } throws RuntimeException("Cache error") },
            {
                coVerify(exactly = 1) { repository.cleanupExpired() }
                coVerify(exactly = 1) { cacheService.evictExpired() }
                coVerify(exactly = 0) { stateManager.refreshForUser(any(), any()) }
            }),
        FailureRow("cleanup: getCurrentUserId RuntimeException", Op.CLEANUP, false,
            { every { stateManager.getCurrentUserId() } throws RuntimeException("State error") },
            {
                coVerify(exactly = 1) { repository.cleanupExpired() }
                coVerify(exactly = 1) { cacheService.evictExpired() }
                coVerify(exactly = 0) { stateManager.refreshForUser(any(), any()) }
            }),
        // ---- cleanupExpired: CancellationException propagates ----
        FailureRow("cleanup: CancellationException from repository cleanupExpired", Op.CLEANUP, true,
            { coEvery { repository.cleanupExpired() } throws CancellationException("cancelled in cleanupExpired") },
            { coVerify(exactly = 0) { cacheService.evictExpired() } }),
        FailureRow("cleanup: CancellationException from cache evictExpired", Op.CLEANUP, true,
            { coEvery { cacheService.evictExpired() } throws CancellationException("cancelled in evictExpired") },
            { coVerify(exactly = 0) { stateManager.refreshForUser(any(), any()) } }),
        FailureRow("cleanup: CancellationException from state refresh", Op.CLEANUP, true,
            {
                every { stateManager.getCurrentUserId() } returns "user123"
                every { stateManager.refreshForUser("user123", any()) } throws CancellationException("cancelled in refresh")
            },
            {}),
        FailureRow("cleanup: CancellationException from getCurrentUserId", Op.CLEANUP, true,
            { every { stateManager.getCurrentUserId() } throws CancellationException("cancelled in getCurrentUserId") },
            {}),
        // ---- refreshThreshold ----
        FailureRow("threshold: CancellationException", Op.THRESHOLD, true,
            { coEvery { thresholdCalculator.refreshThreshold() } throws CancellationException("cancelled in refreshThreshold") },
            {}),
        FailureRow("threshold: IllegalStateException (control)", Op.THRESHOLD, false,
            { coEvery { thresholdCalculator.refreshThreshold() } throws IllegalStateException("regular failure") },
            { coVerify(exactly = 1) { thresholdCalculator.refreshThreshold() } })
    )

    @Test
    fun `failure table - regular exceptions are swallowed and CancellationException always propagates`() =
        runTest(testDispatcher) {
            val rows = failureRows()
            assertEquals(19, rows.size, "failure table lost or gained a row")

            for (failure in rows) {
                row(failure.name) {
                    buildFixture()
                    failure.arrange()

                    val invoke: suspend () -> Unit = {
                        when (failure.op) {
                            Op.CHECK -> lifecycleManager.checkAndExpire("user123")
                            Op.CLEANUP -> lifecycleManager.cleanupExpired()
                            Op.THRESHOLD -> lifecycleManager.refreshThreshold()
                        }
                    }
                    if (failure.propagatesCancellation) {
                        assertFailsWith<CancellationException>(failure.name) { invoke() }
                    } else {
                        invoke() // must not throw
                    }
                    failure.verify()
                }
            }
        }

    @Test
    fun `refreshThreshold success calls calculator exactly once`() = runTest(testDispatcher) {
        lifecycleManager.refreshThreshold()

        coVerify(exactly = 1) { thresholdCalculator.refreshThreshold() }
    }
}
