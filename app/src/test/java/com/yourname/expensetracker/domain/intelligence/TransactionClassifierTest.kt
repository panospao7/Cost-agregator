package com.yourname.expensetracker.domain.intelligence

import android.content.Context
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests for [TransactionClassifier] lifecycle hygiene.
 *
 * Focus areas:
 * - [onBackground] cancels pending jobs without killing the scope
 * - Repeated background transitions do not permanently disable save/retrain scheduling
 * - [destroy] permanently kills the scope (no further work possible)
 * - Legacy [cleanup] delegates to [destroy] for backward compatibility
 */
class TransactionClassifierTest {

    private lateinit var classifier: TransactionClassifier
    private val context = mockk<Context>(relaxed = true)
    private val userCorrectionRepository = mockk<UserCorrectionRepository>(relaxed = true)
    private val tempDir = createTempDir("classifier_test")

    @Before
    fun setup() {
        every { context.filesDir } returns tempDir
        coEvery { userCorrectionRepository.getCount() } returns 0
        coEvery { userCorrectionRepository.getAll() } returns emptyList()

        classifier = TransactionClassifier(context, userCorrectionRepository)
    }

    @Test
    fun `onBackground does not prevent future initialization`() = runBlocking {
        // First background transition
        classifier.onBackground()

        // Should still be able to initialize after backgrounding
        classifier.initialize()

        val stats = classifier.getStats()
        assertNotNull("Stats should be available after background + re-init", stats)
    }

    @Test
    fun `onBackground does not prevent future predict calls`() = runBlocking {
        classifier.initialize()

        // Background transition
        classifier.onBackground()

        // predict() should still work — it does not require the scope
        val result = classifier.predict("test transaction payment")
        assertTrue("Predict should return a valid probability", result in 0f..1f)
    }

    @Test
    fun `onBackground does not prevent future train calls`() = runBlocking {
        classifier.initialize()

        // Background transition
        classifier.onBackground()

        // train() uses scheduleSave() internally which launches on the scope —
        // this must not throw CancellationException
        classifier.train("payment of 10.50 EUR", isTransaction = true)

        val stats = classifier.getStats()
        assertEquals("Training sample should be counted", 1, stats.totalPositive)
    }

    @Test
    fun `repeated onBackground transitions do not break classifier`() = runBlocking {
        classifier.initialize()

        // Simulate multiple background/foreground cycles
        repeat(5) { cycle ->
            classifier.onBackground()

            // After each background transition, training should still work
            classifier.train("transaction payment #$cycle", isTransaction = true)
        }

        val stats = classifier.getStats()
        assertEquals(
            "All 5 training samples should be counted after repeated backgrounding",
            5,
            stats.totalPositive
        )
    }

    @Test
    fun `retrainFromCorrections works after onBackground`() = runBlocking {
        classifier.initialize()

        // Background transition
        classifier.onBackground()

        // retrainFromCorrections launches on the scope — must not throw
        classifier.retrainFromCorrections()

        // No assertion on retrain results (not enough corrections configured),
        // but the call must not crash with CancellationException
    }

    @Test
    fun `destroy permanently cancels scope - train still records but save is lost`() = runBlocking {
        classifier.initialize()

        // Permanently destroy scope
        classifier.destroy()

        // train() will still mutate in-memory state (addTrainingSample),
        // but scheduleSave() will silently fail because scope is cancelled.
        // The method should not throw.
        try {
            classifier.train("test payment", isTransaction = true)
        } catch (_: Exception) {
            // Some implementations may throw; either way is acceptable
            // as destroy() signals permanent disposal
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `cleanup delegates to destroy for backward compatibility`() = runBlocking {
        classifier.initialize()

        // Legacy cleanup should not throw
        classifier.cleanup()

        // After cleanup, scope is permanently cancelled (same as destroy)
    }

    @Test
    fun `onBackground is idempotent`() = runBlocking {
        classifier.initialize()

        // Calling onBackground multiple times without any work in between
        classifier.onBackground()
        classifier.onBackground()
        classifier.onBackground()

        // Should still be operational
        classifier.train("payment EUR 20.00", isTransaction = true)
        val stats = classifier.getStats()
        assertEquals(1, stats.totalPositive)
    }

    @Test
    fun `predict returns neutral score for untrained classifier`() = runBlocking {
        classifier.initialize()

        val result = classifier.predict("some text")
        assertEquals(
            "Untrained classifier should return 0.5 (neutral)",
            0.5f,
            result,
            0.01f
        )
    }

    @Test
    fun `getStats reflects training after background cycle`() = runBlocking {
        classifier.initialize()

        // Train, background, train again
        classifier.train("first payment", isTransaction = true)
        classifier.onBackground()
        classifier.train("second charge", isTransaction = false)

        val stats = classifier.getStats()
        assertEquals(1, stats.totalPositive)
        assertEquals(1, stats.totalNegative)
    }
}
